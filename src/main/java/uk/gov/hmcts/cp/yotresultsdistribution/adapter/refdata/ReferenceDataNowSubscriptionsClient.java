package uk.gov.hmcts.cp.yotresultsdistribution.adapter.refdata;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPause;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.yotresultsdistribution.application.NowSubscriptionsSource;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReferenceDataUnavailableException;

/**
 * The now-subscriptions read, against the reference-data query API.
 *
 * <p>The endpoint, the {@code on} query parameter, the vendor media type and the identity header are
 * reference data's contract, not this service's: {@code GET
 * /referencedata-query-api/query/api/rest/referencedata/now-subscriptions?on={YYYY-MM-DD}} with
 * {@code Accept: application/vnd.referencedata.query.get-now-subscriptions+json} and a
 * {@code CJSCPPUID} header ({@code NowsHelper/service/ReferenceDataService.js:33-54}).
 *
 * <p><strong>The day is chosen upstream, and it is chosen fixed — defect fix C12.</strong>
 * {@code ReferenceDataService.js:38} derives {@code on} as
 * {@code new Date(registerDate).toISOString().slice(0, 10)}, and the register date it is handed
 * carries C10's relabelled local time with a literal {@code Z}: a hearing shared between 23:00 and
 * midnight BST therefore reads the <em>next</em> day's subscription set, and is addressed to
 * whoever is subscribed tomorrow. The corrected day is {@code Dates.subscriptionDay}, the UTC day of
 * the share instant, and it is computed once in the pipeline. This client renders the day it is
 * handed as the plain {@code YYYY-MM-DD} the parameter takes and derives nothing of its own — one
 * derivation, in one place, is what makes the fix hold.
 *
 * <p><strong>The retry taxonomy is the corrected one — defect fix C3</strong>, and it is the same
 * taxonomy the progression submission client applies: connect and read failures, 5xx, 429 and 408
 * are retried; any other 4xx is a refusal no redelivery can change — and it comes out
 * {@code NON_TRANSIENT} under
 * {@link uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode#REFERENCE_DATA_REFUSED}, so the pipeline
 * parks it rather than spending the whole delivery budget on an answer that will not change and
 * parking it under an exhausted retry budget instead. It is not merely the same list: all three
 * clients hold the same {@link uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy} object, so
 * the taxonomy, the doubling back-off bounded by {@code max-backoff}, and what a
 * {@code Retry-After} is worth on any retryable answer are decided in one place and cannot drift
 * apart between them.
 *
 * <p><strong>An empty answer and no answer are different things.</strong> The legacy catches
 * everything and returns {@code null} ({@code :50-53}), which
 * {@code CourtRegisterSubscriptions/index.js:31-44} cannot tell from "reference data answered,
 * nobody is subscribed" — so an outage files a register that reaches nobody and nothing records
 * why. Here an answer carrying no subscriptions is returned as the answer it is, and the
 * subscription matcher decides {@code no-subscriptions}; a read that could not be made raises
 * {@link uk.gov.hmcts.cp.yotresultsdistribution.domain.ReferenceDataUnavailableException}, which is
 * transient, so the run comes back rather than completing addressed to nobody.
 */
public class ReferenceDataNowSubscriptionsClient implements NowSubscriptionsSource {

    /** The resource's path under the reference-data context. */
    public static final String PATH =
            "/referencedata-query-api/query/api/rest/referencedata/now-subscriptions";

    /** The query parameter carrying the day the subscription set is read as at. */
    public static final String ON = "on";

    /** The vendor media type the now-subscriptions resource is served as. */
    public static final String ACCEPT =
            "application/vnd.referencedata.query.get-now-subscriptions+json";

    /** The header carrying the user identity reference data authorises against. */
    public static final String IDENTITY_HEADER = "CJSCPPUID";

    private static final Logger LOG =
            LoggerFactory.getLogger(ReferenceDataNowSubscriptionsClient.class);

    private final RestClient restClient;
    private final String systemUserId;
    private final Map<String, String> extraHeaders;
    private final ObjectMapper objectMapper;
    private final RetryPolicy retryPolicy;
    private final RetryPause pause;

    /**
     * Builds the client over an already-configured HTTP client.
     *
     * @param restClient   the client, carrying the reference-data base URL and its timeouts
     * @param systemUserId the {@code CJSCPPUID} identity for a run naming no user; a secret, never
     *                     logged
     * @param extraHeaders any further headers the mesh requires, name to value
     * @param objectMapper the shared mapper, so a response is read exactly as any other JSON is
     * @param retryPolicy  the shared retry policy: attempts, back-off and what a
     *                     {@code Retry-After} is worth
     * @param pause        how a wait between attempts is taken
     */
    public ReferenceDataNowSubscriptionsClient(final RestClient restClient,
            final String systemUserId, final Map<String, String> extraHeaders,
            final ObjectMapper objectMapper, final RetryPolicy retryPolicy,
            final RetryPause pause) {
        this.restClient = restClient;
        this.systemUserId = systemUserId;
        this.extraHeaders = Map.copyOf(extraHeaders);
        this.objectMapper = objectMapper;
        this.retryPolicy = retryPolicy;
        this.pause = pause;
    }

    @Override
    public JsonNode subscriptionsOn(final LocalDate registerDay, final CallerIdentity caller) {
        // Resolved once, outside the retry loop: every attempt at this read is made as the same
        // caller, exactly as the legacy's one `input.cjscppuid` is
        // (ReferenceDataService.js:44).
        final String identity = caller.orSystem(systemUserId);
        final int maxAttempts = retryPolicy.maxAttempts();
        JsonNode answer = null;
        for (int attempt = 1; answer == null; attempt++) {
            final boolean lastAttempt = attempt >= maxAttempts;
            Optional<Duration> asked = Optional.empty();
            try {
                answer = content(get(registerDay, identity), registerDay);
            } catch (RestClientResponseException refused) {
                asked = RetryPolicy.retryAfter(refused.getResponseHeaders());
                refused(registerDay, refused.getStatusCode().value(), lastAttempt);
            } catch (RestClientException unanswered) {
                neverAnswered(registerDay, lastAttempt);
            }
            if (answer == null) {
                waited(registerDay, retryPolicy.waitAfter(attempt, asked));
            }
        }
        return answer;
    }

    /**
     * The read itself.
     *
     * <p>The configured mesh headers are applied first and the contract's two are set over them, so a
     * header configured under a contract name <em>replaces</em> the contract value rather than
     * joining it: two values of {@code Accept} is a 406 from a service doing content negotiation, and
     * two of {@code CJSCPPUID} is an ambiguous caller to one authorising on identity.
     *
     * <p>The default error handler is replaced so the failure carries the status and nothing
     * reference data wrote — the body names organisations and email addresses, and an exception
     * message reaches the log index (constitution Principle VII).
     */
    private String get(final LocalDate registerDay, final String identity) {
        return restClient.get()
                .uri(uri -> uri.path(PATH).queryParam(ON, registerDay).build())
                .headers(headers -> {
                    extraHeaders.forEach(headers::set);
                    headers.set(HttpHeaders.ACCEPT, ACCEPT);
                    headers.set(IDENTITY_HEADER, identity);
                })
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                    throw new RestClientResponseException(
                            "The now-subscriptions read was answered with "
                                    + response.getStatusCode(),
                            response.getStatusCode(), response.getStatusText(),
                            response.getHeaders(), null, null);
                })
                .body(String.class);
    }

    /**
     * A status reference data answered with.
     *
     * <p>Every one of them is a failure — unlike the payload read, where a {@code 404} means the
     * hearing is not held. The now-subscriptions resource always exists, so a {@code 404} on it is a
     * misconfigured path, and reading that as "nobody is subscribed" is precisely the substitution
     * this fix ends. What the taxonomy decides is only whether asking again can change the answer
     * (defect fix C3).
     */
    private void refused(final LocalDate registerDay, final int status, final boolean lastAttempt) {
        // The status is reference data's own answer and is bounded by HTTP; the body is text
        // somebody else wrote and this line reaches the log index.
        if (!RetryPolicy.retryable(status)) {
            LOG.warn("Reference data refused the now-subscriptions read and no redelivery can "
                    + "change that, so the register cannot be addressed. queryDate={} status={}",
                    registerDay, status);
            throw declined();
        }
        if (lastAttempt) {
            LOG.warn("Reference data refused the now-subscriptions read, so the register cannot be "
                    + "addressed. queryDate={} status={}", registerDay, status);
            throw unavailable();
        }
    }

    /** A read that never reached an answer: retried while attempts remain, then reported. */
    private void neverAnswered(final LocalDate registerDay, final boolean lastAttempt) {
        if (lastAttempt) {
            LOG.warn("Reference data did not answer the now-subscriptions read, so the register "
                    + "cannot be addressed. queryDate={}", registerDay);
            throw unavailable();
        }
    }

    /**
     * The answer, as reference data gave it.
     *
     * <p>Whatever is in it is passed through: an empty subscription set and a body with no
     * {@code nowSubscriptions} member at all are both answers the matcher can read, and turning
     * either into an outage here would invent one out of a shape reference data chose. What is
     * <em>not</em> an answer is a body that could not be read — a gateway's error page served with a
     * {@code 200} is the everyday case, and reading it as "nobody is subscribed" is the substitution
     * this fix ends.
     */
    private JsonNode content(final String body, final LocalDate registerDay) {
        if (body == null || body.isBlank()) {
            LOG.warn("Reference data answered the now-subscriptions read with an empty body, so the "
                    + "register cannot be addressed. queryDate={}", registerDay);
            throw unavailable();
        }
        final JsonNode answer;
        try {
            answer = objectMapper.readTree(body);
        } catch (JacksonException notJson) {
            // By type, never by message. A parser quotes the token it choked on, and a subscription
            // body names organisations and email addresses (constitution Principle VII).
            LOG.warn("Reference data answered the now-subscriptions read with something that is not "
                    + "JSON, so the register cannot be addressed. type={}",
                    notJson.getClass().getName());
            throw unavailable();
        }
        return answer;
    }

    private void waited(final LocalDate registerDay, final Duration wait) {
        if (!wait.isZero() && !wait.isNegative()) {
            try {
                pause.pause(wait);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOG.warn("Interrupted while waiting to retry the now-subscriptions read. "
                        + "queryDate={}", registerDay);
                throw unavailable();
            }
        }
    }

    /**
     * The one failure this client raises, carrying the bounded code and nothing else.
     *
     * <p>It is transient by construction, which is the whole of the difference from the legacy: a
     * register the run could not address is worth coming back for, where one completed
     * {@code no-subscriptions} because nobody was asked is a register lost.
     */
    private static ReferenceDataUnavailableException unavailable() {
        return new ReferenceDataUnavailableException(ReasonCode.REFERENCE_DATA_UNAVAILABLE.code());
    }

    /**
     * A read reference data understood and declined, which no redelivery can change.
     *
     * <p>Its own bounded code as well as its own classification, because the two reach different
     * people: a rise in {@code REFERENCE_DATA_UNAVAILABLE} is reference data's health, and a rise in
     * this one is this service's route or credential. The list includes {@code 404}: the
     * now-subscriptions resource always exists, so a 404 on it is a misconfigured path.
     */
    private static ReferenceDataUnavailableException declined() {
        return new ReferenceDataUnavailableException(
                FailureClassification.NON_TRANSIENT, ReasonCode.REFERENCE_DATA_REFUSED);
    }
}
