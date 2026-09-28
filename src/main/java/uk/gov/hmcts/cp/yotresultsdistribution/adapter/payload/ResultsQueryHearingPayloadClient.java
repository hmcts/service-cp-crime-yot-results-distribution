package uk.gov.hmcts.cp.yotresultsdistribution.adapter.payload;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPause;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.PayloadUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;

/**
 * The results query API, read when the cache has nothing.
 *
 * <p>The endpoint, the vendor media type and the identity header are the query side's contract, not
 * this service's. {@code HearingResultedCacheQuery/index.js:63-76} declares three of them and this
 * service carries exactly one — the {@code INT_} entry, {@code GET
 * /results-query-api/query/api/rest/results/hearingDetails/internal/{hearingId}} with
 * {@code Accept: application/vnd.results.hearing-details-internal+json} and a {@code CJSCPPUID}
 * header. The internal variant returns the untransformed hearing, which is what the cache holds, and
 * it takes <strong>no</strong> {@code hearingDate} parameter: the {@code ?hearingDate=} form K8
 * asserts belongs to the {@code EXT_} endpoint, which this flow does not use.
 *
 * <p><strong>What the taxonomy decides, it decides twice.</strong> A status this client will not ask
 * again is one the query side understood and declined, so the failure it raises is
 * {@code NON_TRANSIENT} under {@link uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode#PAYLOAD_READ_REFUSED}
 * and the pipeline parks it where support can see it, rather than spending four more deliveries on
 * an answer that will not change and parking it under an exhausted retry budget instead. Everything
 * the taxonomy <em>does</em> retry stays transient.
 *
 * <p><strong>The retry taxonomy is the corrected one — defect fix C3.</strong>
 * {@code CommonUtility/AxiosRetryWrapper.js:19,34} abandons the moment a response arrives carrying a
 * status at or below 429, which makes 429 and 408 — the two statuses that most plainly mean "ask me
 * again" — the least-retried failures there are, and its fixed one-second interval never grows. This
 * client retries connect and read failures, 5xx, 429 and 408, never retries any other 4xx, backs off
 * exponentially and honours a {@code Retry-After} on every retryable answer — because it holds the
 * same {@link uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy} object the progression gateway
 * and the reference-data read hold, rather than its own implementation of the same sentence.
 *
 * <p><strong>Nothing it cannot answer is silent — defect fix C32.</strong>
 * {@code getPrefixHearing} ({@code index.js:34-57}) returns {@code undefined} when the body has no
 * content, because there is no {@code else}, and {@code null} when the read threw; both make the
 * orchestrator's {@code if (hearingResultedObj)} false and the run reports success having produced
 * nothing. Here the two are told apart and neither is silence: a query side that answered and held
 * the hearing under no key answers {@link Optional#empty()}, which
 * {@link CachedHearingPayloadAdapter} turns into a transient failure once the cache has missed too,
 * and a read that could not be made at all raises
 * {@link uk.gov.hmcts.cp.yotresultsdistribution.domain.PayloadUnavailableException} here.
 */
public class ResultsQueryHearingPayloadClient implements HearingPayloadQuery {

    /** The resource's path under the results context; {@code {hearingId}} is the only variable. */
    public static final String PATH =
            "/results-query-api/query/api/rest/results/hearingDetails/internal/{hearingId}";

    /** The vendor media type the internal hearing-details resource is served as. */
    public static final String ACCEPT = "application/vnd.results.hearing-details-internal+json";

    /**
     * The header carrying the user identity the query side authorises against.
     *
     * <p>Its value is the run's caller: the user who shared the results where the message named one,
     * and the configured system identity otherwise. The legacy sends {@code input.cjscppuid} and
     * skips the read entirely when there is none ({@code index.js:172-179}), which is a silent stop
     * this service does not have.
     */
    public static final String IDENTITY_HEADER = "CJSCPPUID";

    private static final Logger LOG =
            LoggerFactory.getLogger(ResultsQueryHearingPayloadClient.class);

    private final RestClient restClient;
    private final String systemUserId;
    private final ObjectMapper objectMapper;
    private final RetryPolicy retryPolicy;
    private final RetryPause pause;

    /**
     * Builds the client over an already-configured HTTP client.
     *
     * @param restClient   the client, carrying the results base URL and its timeouts
     * @param systemUserId the fallback identity for a message that names no user; a secret, never
     *                     logged
     * @param objectMapper the shared mapper, so a response is read exactly as any other JSON is
     * @param retryPolicy  the shared retry policy: attempts, back-off and what a
     *                     {@code Retry-After} is worth
     * @param pause        how a wait between attempts is taken
     */
    public ResultsQueryHearingPayloadClient(final RestClient restClient, final String systemUserId,
            final ObjectMapper objectMapper, final RetryPolicy retryPolicy,
            final RetryPause pause) {
        this.restClient = restClient;
        this.systemUserId = systemUserId;
        this.objectMapper = objectMapper;
        this.retryPolicy = retryPolicy;
        this.pause = pause;
    }

    @Override
    public Optional<JsonNode> fetch(final DistributionCommand command) {
        // The user who shared the results where the message named one, and the configured system
        // identity otherwise — one identity per run, resolved once (CallerIdentity).
        final String caller = CallerIdentity.of(command).orSystem(systemUserId);
        if (caller == null || caller.isBlank()) {
            // K3's repair. Neither the message nor the configuration named anybody, and the query
            // side authorises on this header — so an anonymous read is a 403 dressed as a cache
            // miss. The legacy drops the run here without a word (index.js:176-178); this records
            // it, and sends nothing.
            LOG.warn("No user identity is available, so the payload fallback cannot be used. "
                    + "requestId={} hearingId={}", command.requestId(), command.hearingId());
            throw unavailable();
        }
        return attempt(command, caller);
    }

    /**
     * The read, retried under the corrected taxonomy.
     *
     * <p>One exit: the loop ends when the query side answered, and every other outcome leaves it by
     * raising. An answer that held nothing is an answer — {@link CachedHearingPayloadAdapter} is the
     * participant that knows the cache missed too, so it is the one that classifies the pair.
     */
    private Optional<JsonNode> attempt(final DistributionCommand command, final String caller) {
        final int maxAttempts = retryPolicy.maxAttempts();
        Optional<JsonNode> payload = Optional.empty();
        boolean answered = false;
        for (int attempt = 1; !answered; attempt++) {
            final boolean lastAttempt = attempt >= maxAttempts;
            Optional<Duration> asked = Optional.empty();
            try {
                payload = content(get(command.hearingId(), caller));
                answered = true;
            } catch (RestClientResponseException refused) {
                answered = heldNothing(command, refused.getStatusCode().value(), lastAttempt);
                asked = RetryPolicy.retryAfter(refused.getResponseHeaders());
            } catch (RestClientException unanswered) {
                neverAnswered(command, lastAttempt);
            }
            if (!answered) {
                waited(command, retryPolicy.waitAfter(attempt, asked));
            }
        }
        return payload;
    }

    private String get(final UUID hearingId, final String caller) {
        return restClient.get()
                .uri(PATH, hearingId)
                .header(HttpHeaders.ACCEPT, ACCEPT)
                .header(IDENTITY_HEADER, caller)
                .retrieve()
                .body(String.class);
    }

    /**
     * What a status the query side answered with means.
     *
     * <p>A {@code 404} is the only one that ends the read without a payload and without a failure:
     * the resource is per-hearing, so its absence is the query side saying it does not hold this
     * hearing — the same answer as the empty-bodied {@code 200} the results context also serves.
     * Everything else is a failure, and the fixed C3 taxonomy decides only whether asking again can
     * change it: connect and read failures, 5xx, 429 and 408 can, and no other 4xx ever will.
     *
     * <p><strong>And the taxonomy decides the classification, not only the attempt count.</strong> A
     * status this client will not ask again is one the query side understood and declined, and it
     * will be declined identically on every redelivery: reported transient it would be handed back,
     * refused four more times, and parked at the end under {@code DELIVERY_LIMIT_EXHAUSTED} — a
     * reason that says the service ran out of tries rather than that its credential is wrong. So a
     * refusal comes out {@link FailureClassification#NON_TRANSIENT} under
     * {@link ReasonCode#PAYLOAD_READ_REFUSED}, and a retryable status that ran out of attempts stays
     * transient under {@link ReasonCode#PAYLOAD_UNAVAILABLE}.
     *
     * @return {@code true} when the query side answered and held nothing
     */
    private boolean heldNothing(final DistributionCommand command, final int status,
            final boolean lastAttempt) {
        final boolean notHeld = status == HttpStatus.NOT_FOUND.value();
        if (!notHeld && !RetryPolicy.retryable(status)) {
            LOG.warn("The results query API refused the payload read, and no redelivery can change "
                    + "that. requestId={} hearingId={} status={}",
                    command.requestId(), command.hearingId(), status);
            throw refused();
        }
        if (!notHeld && lastAttempt) {
            LOG.warn("The results query API refused the payload read. requestId={} hearingId={} "
                    + "status={}", command.requestId(), command.hearingId(), status);
            throw unavailable();
        }
        return notHeld;
    }

    /** A read that never reached an answer: retried while attempts remain, then reported. */
    private void neverAnswered(final DistributionCommand command, final boolean lastAttempt) {
        if (lastAttempt) {
            LOG.warn("The results query API did not answer. requestId={} hearingId={}",
                    command.requestId(), command.hearingId());
            throw unavailable();
        }
    }

    /**
     * The body, as a payload or as an empty answer.
     *
     * <p>The legacy's own content test is the right one and is kept ({@code index.js:50-52} returns
     * the body only when it has content); what is not kept is that an empty one and a failed read
     * are the same {@code undefined}.
     */
    private Optional<JsonNode> content(final String body) {
        Optional<JsonNode> payload = Optional.empty();
        if (body != null && !body.isBlank()) {
            payload = parsed(body);
        }
        return payload;
    }

    /**
     * A body that is not JSON is a failure rather than an empty answer.
     *
     * <p>A gateway error page served with a {@code 200} is the everyday case: nothing was obtained,
     * and reading that as "the hearing is not held" would spend the register on a proxy's bad day.
     */
    private Optional<JsonNode> parsed(final String body) {
        Optional<JsonNode> payload = Optional.empty();
        try {
            final JsonNode tree = objectMapper.readTree(body);
            if (tree != null && !tree.isNull() && !tree.isMissingNode() && !tree.isEmpty()) {
                payload = Optional.of(tree);
            }
        } catch (JacksonException notJson) {
            // By type, never by message. A parser quotes the token it choked on, and in a truncated
            // hearing that token is a name, an address or a URN (constitution Principle VII).
            LOG.warn("The results query API answered with something that is not JSON. type={}",
                    notJson.getClass().getName());
            throw unavailable();
        }
        return payload;
    }

    private void waited(final DistributionCommand command, final Duration wait) {
        if (!wait.isZero() && !wait.isNegative()) {
            try {
                pause.pause(wait);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOG.warn("Interrupted while waiting to retry the payload read. requestId={} "
                        + "hearingId={}", command.requestId(), command.hearingId());
                throw unavailable();
            }
        }
    }

    /**
     * The one failure this client raises, carrying the bounded code and nothing else.
     *
     * <p>The message travels into {@code processed_request.failure_reason}, a dead-letter
     * description and the log index, so it is the code — never a URL, a status, or the words the
     * layer beneath used.
     */
    private static PayloadUnavailableException unavailable() {
        return new PayloadUnavailableException(ReasonCode.PAYLOAD_UNAVAILABLE);
    }

    /**
     * A read the query side understood and declined, which no redelivery can change.
     *
     * <p>Its own bounded code as well as its own classification, because the two reach different
     * people: a rise in {@code PAYLOAD_UNAVAILABLE} is a producer or a cache to look at, and a rise
     * in this one is a credential or a route.
     */
    private static PayloadUnavailableException refused() {
        return new PayloadUnavailableException(
                FailureClassification.NON_TRANSIENT, ReasonCode.PAYLOAD_READ_REFUSED);
    }
}
