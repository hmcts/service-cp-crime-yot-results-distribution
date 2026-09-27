package uk.gov.hmcts.cp.yotresultsdistribution.adapter.appconfig;

import com.azure.core.credential.TokenCredential;
import com.azure.core.exception.ClientAuthenticationException;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.exception.ResourceNotFoundException;
import com.azure.core.http.HttpClient;
import com.azure.core.http.policy.FixedDelayOptions;
import com.azure.core.http.policy.RetryOptions;
import com.azure.core.util.HttpClientOptions;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.data.appconfiguration.models.ConfigurationSetting;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.application.FeatureFlagReader;
import uk.gov.hmcts.cp.yotresultsdistribution.config.FeatureFlagProperties;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision.UnreadableReason;

/**
 * The one lever, read from Azure App Configuration.
 *
 * <p>This service is the flag's third reader, after the producer and the legacy, and it reads the
 * same key and label those two read - which is what makes it one lever rather than three. The
 * setting is a feature flag, so the value is App Configuration's own feature-flag JSON and the
 * answer is its {@code enabled} member; the key and the stack's label are configuration, never
 * defaults invented here (research §3).
 *
 * <p><strong>It never throws</strong>, which is the port's contract and the reason this adapter
 * exists rather than an SDK call at the call site: a store that is absent, one that refuses this
 * pod's identity, one that is merely slow and one that answers with something this service cannot
 * read are four different {@link FlagDecision.Unreadable} causes, each bounded, and every one of
 * them means the run is skipped. Nothing about the SDK's exceptions reaches the caller, and nothing
 * of the store's text reaches a counter label or the log index.
 *
 * <p>The read is bounded by {@code yotresultsdistribution.feature.timeout} and is made once per run with no
 * cache: a flag that was on an hour ago says nothing about a cutover that was rolled back ten
 * minutes ago. The budget is the whole of the read, so the client this class builds retries
 * nothing: three SDK attempts inside a two-second budget would spend the run's decision on the
 * first attempt's back-off and answer nothing.
 *
 * <p><strong>"The whole of the read" is meant literally, and the budget is the outer
 * deadline.</strong> Every leg the HTTP client can see - connect, write, read, response - carries
 * the budget, and the call itself is made under an outer bound of exactly the budget, because the
 * legs the client cannot see are real: a credential the identity endpoint answers slowly, a
 * handshake that stalls after the connection is accepted, a pool with nothing free in it. The job
 * asks this question before it does anything else and waits on the answer, so a read that outlasts
 * the budget is not a slow read, it is a nightly run that never started - and at 18:00 that is
 * indistinguishable from a healthy stack with nothing to generate. Whichever of the two bounds ends
 * it, the answer is the same decision and the deadline is the one the deployment configured.
 *
 * <p><strong>Only a boolean {@code enabled} generates.</strong> The value is parsed here rather
 * than taken from the SDK's typed feature-flag view, because the shapes short of the contract are
 * the ones that matter: a value with no verdict in it, and one whose verdict is the string
 * {@code "true"}, are what a hand-edited setting produces, and a lenient reading of either
 * generates a night of registers the legacy is also generating.
 */
// PMD.OnlyOneReturn: every method below answers at the clause that decides it, because each clause
// is a different thing to go and fix at 18:00 - a setting nobody wrote, a role assignment nobody
// made, a store that was too slow, a value that is not a flag. One exit would collect them into a
// variable and lose the correspondence between the answer and the reason for it.
@SuppressWarnings("PMD.OnlyOneReturn")
public class AppConfigurationFlagReader implements FeatureFlagReader {

    /** The member of App Configuration's feature-flag JSON that carries the verdict. */
    private static final String ENABLED = "enabled";

    private static final Logger LOG = LoggerFactory.getLogger(AppConfigurationFlagReader.class);

    /** The same mapper contract every other read in this service is parsed under. */
    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    /** The read is the whole budget, so the SDK is asked once and never asked again. */
    private static final RetryOptions NO_RETRIES =
            new RetryOptions(new FixedDelayOptions(0, Duration.ZERO));

    /** The endpoint, the key, the stack label and the read's budget all come from here. */
    private final FeatureFlagProperties properties;

    /**
     * The client the setting is read through, or {@code null} where no store was configured.
     *
     * <p>Null is not an oversight and not a failure to be raised at construction: a deployment that
     * names no store is {@link UnreadableReason#NOT_CONFIGURED}, which is a skipped run with a cause
     * on it, and {@code PropertiesValidator} has already refused the case that matters - generation
     * enabled with no endpoint - before this class is built.
     */
    private final ConfigurationClient client;

    /**
     * Creates the reader the deployed pod uses, over the identity that pod holds.
     *
     * <p>The client is built here from {@code properties.endpoint()}, the credential and
     * {@code properties.timeout()}, because the adapter owns the SDK types - that is what makes it
     * the adapter. A deployment naming no endpoint gets no client and every read answers
     * {@link UnreadableReason#NOT_CONFIGURED}; the credential itself, and the refusal to start
     * without one, belong to {@code LiveFeatureFlagConfig}, which is the wiring that asked for a
     * live reader.
     *
     * @param properties where the flag is read from, and under which key, label and budget
     * @param credential the identity the store authorises the read against
     */
    public AppConfigurationFlagReader(
            final FeatureFlagProperties properties, final TokenCredential credential) {
        this(properties, clientFor(properties, credential));
    }

    /**
     * Creates the reader over a client somebody else built.
     *
     * <p>The adapter owns the SDK type - that is what makes it the adapter - so taking the client
     * rather than only the endpoint costs the design nothing and lets the read be exercised as the
     * SDK call it is: {@code AppConfigurationFlagReaderTest} (T026) hands in a client pointed at a
     * stub of App Configuration's {@code kv} resource, so the key in the path, the label in the
     * query and the mapping of every answer onto a decision are all asserted against the real
     * client rather than a stand-in for it.
     *
     * @param properties where the flag is read from, and under which key, label and budget
     * @param client     the client the read is made through
     */
    public AppConfigurationFlagReader(
            final FeatureFlagProperties properties, final ConfigurationClient client) {
        this.properties = properties;
        this.client = client;
    }

    /**
     * The client the deployed pod reads through: this stack's store, on this pod's identity.
     *
     * <p><strong>Every leg carries the budget, not just the response.</strong> A response timeout
     * bounds the wait for an answer to a request that was sent; it says nothing about a connection
     * that is never established, a request that cannot be written, or a body that arrives a byte at
     * a time. Each of those is a way the one lever's read can hang, and each of them at 18:00 is a
     * run that has not started rather than a run that was skipped, so all four take
     * {@code yotresultsdistribution.feature.timeout}. None of them is set longer than that, because the same
     * value is the outer deadline the whole read is bounded at: a leg allowed more than the deadline
     * could only ever be ended by the bound outside it. The read is one attempt - the client retries
     * nothing - so there is no schedule for the four to add up across.
     */
    private static ConfigurationClient clientFor(
            final FeatureFlagProperties properties, final TokenCredential credential) {
        if (properties.endpoint() == null || properties.endpoint().isBlank()) {
            return null;
        }
        return new ConfigurationClientBuilder()
                .endpoint(properties.endpoint())
                .credential(credential)
                .retryOptions(NO_RETRIES)
                .httpClient(httpClientFor(properties))
                .buildClient();
    }

    /**
     * The HTTP client a flag read is made through, bounded on every leg by the configured budget.
     *
     * <p>Public and here rather than inline above, because there are two credentials and one client:
     * {@code LiveFeatureFlagConfig} builds the {@code local-test} client from a connection string,
     * since a token credential cannot be sent over the compose stub's plain HTTP at all, and
     * everything else about that reader is meant to be the deployed one - the budget on all four
     * legs included. One factory, so the two cannot drift apart.
     *
     * @param properties where the flag is read from, and under which budget
     * @return the client, with connect, read, write and response all held to the budget
     */
    public static HttpClient httpClientFor(final FeatureFlagProperties properties) {
        return HttpClient.createDefault(new HttpClientOptions()
                .setConnectTimeout(properties.timeout())
                .setReadTimeout(properties.timeout())
                .setWriteTimeout(properties.timeout())
                .setResponseTimeout(properties.timeout()));
    }

    @Override
    public FlagDecision read() {
        if (client == null) {
            return refused(UnreadableReason.NOT_CONFIGURED);
        }
        return bounded();
    }

    /**
     * The read, held to the budget as a whole rather than to the budget of its slowest leg.
     *
     * <p>The client's four timeouts bound the parts of a call the HTTP layer can see; this bounds
     * the call. Between the two sit the legs it cannot: a credential the identity endpoint answers
     * slowly, a TLS handshake that stalls after the connection is accepted, a pool with nothing free
     * in it. Any of them makes the read outlast a budget every one of its legs respected, and the
     * consequence is not a slow read but a nightly run that has not started - which at 18:00 looks
     * exactly like a healthy stack with nothing to generate.
     *
     * <p>The wait is the budget itself, and no more: the configured timeout is the deadline the job
     * is promised rather than the first term of one, so a read that has not answered by then is
     * ended here. The client's own timeouts are set to the same budget, so an ordinary slow leg is
     * still usually ended by the client, which knows which leg it was waiting on; a decision made
     * here rather than there is still {@link UnreadableReason#TIMED_OUT}, which is what happened
     * either way. The abandoned read is interrupted and left to unwind on its own thread - a
     * virtual one, so
     * abandoning it costs a platform thread nothing - and its answer, if one ever arrives, is
     * dropped: the run has already been given its decision, and a second one would be a flag read
     * for a run that is over.
     *
     * @return the verdict, or the bounded cause there is none
     */
    // PMD.AvoidCatchingGenericException: the port's contract is that this method never throws, and
    // neither the SDK's failures nor an executor's are a closed set. It is a catch-and-record: every
    // cause becomes a bounded decision the caller acts on, and nothing is ignored (constitution
    // Principle VI).
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private FlagDecision bounded() {
        final CompletableFuture<FlagDecision> answer = new CompletableFuture<>();
        final Thread read = Thread.ofVirtual()
                .name("yot-results-distribution-flag-read")
                .unstarted(() -> answer.complete(attempted()));
        read.start();
        try {
            return answer.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            read.interrupt();
            return refused(UnreadableReason.TIMED_OUT);
        } catch (InterruptedException stopped) {
            // The pod is going down mid-read. The flag is restored so nothing above this mistakes a
            // shutdown for a working thread, and the run is skipped with a cause like any other.
            read.interrupt();
            Thread.currentThread().interrupt();
            return refused(UnreadableReason.CALL_FAILED);
        } catch (ExecutionException | RuntimeException noAnswer) {
            return refused(UnreadableReason.CALL_FAILED);
        }
    }

    /**
     * One attempt at the setting, with every way it can fail turned into a bounded cause.
     *
     * @return the verdict, or the bounded cause there is none
     */
    // PMD.AvoidCatchingGenericException: see the note on the caller. The SDK's failures are a status
    // it maps to its own exception type, a serialiser that refused a body, a connection that died -
    // not a closed set, and every one of them is a run to skip rather than a failure to raise.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private FlagDecision attempted() {
        try {
            final ConfigurationSetting setting =
                    client.getConfigurationSetting(properties.key(), properties.label());
            return verdictOf(setting);
        } catch (RuntimeException noAnswer) {
            // Not swallowed and not carried: the cause is recorded as a bounded code and the
            // failure's own type names what happened, while the SDK's message - which quotes the
            // store's response body and its URL - reaches neither the log nor the counter label
            // (constitution Principle VII).
            return refused(causeOf(noAnswer));
        }
    }

    /**
     * The verdict in the setting the store returned, or why there is none in it.
     *
     * <p>The contract is the vendored value schema: an object carrying a boolean {@code enabled}.
     * Everything short of that is {@link UnreadableReason#MALFORMED} and never on.
     */
    private static FlagDecision verdictOf(final ConfigurationSetting setting) {
        final String value = setting == null ? null : setting.getValue();
        if (value == null || value.isBlank()) {
            return refused(UnreadableReason.MALFORMED);
        }
        final JsonNode flag;
        try {
            flag = MAPPER.readTree(value);
        } catch (JacksonException notAFlag) {
            return refused(UnreadableReason.MALFORMED);
        }
        final JsonNode enabled = flag.isObject() ? flag.get(ENABLED) : null;
        if (enabled == null || !enabled.isBoolean()) {
            return refused(UnreadableReason.MALFORMED);
        }
        return enabled.booleanValue() ? FlagDecision.ON : FlagDecision.OFF;
    }

    /**
     * Which bounded cause a failed read is recorded under.
     *
     * <p>Four causes and four different things to go and fix at 18:00: a setting nobody has written,
     * a role assignment this pod has not been given, a store that did not answer in time and
     * everything else. The 401 and 403 the SDK reports as its own authentication type are the
     * platform ask (design §8) not yet landed, and a store that is merely slow has to be legible as
     * slowness rather than as an outage.
     */
    private static UnreadableReason causeOf(final RuntimeException failure) {
        if (failure instanceof ResourceNotFoundException) {
            return UnreadableReason.NOT_FOUND;
        }
        if (failure instanceof ClientAuthenticationException) {
            return UnreadableReason.ACCESS_DENIED;
        }
        if (timedOut(failure)) {
            return UnreadableReason.TIMED_OUT;
        }
        if (failure instanceof HttpResponseException answered
                && answered.getResponse() != null) {
            return causeOfStatus(answered.getResponse().getStatusCode());
        }
        return UnreadableReason.CALL_FAILED;
    }

    private static UnreadableReason causeOfStatus(final int status) {
        return switch (status) {
            case 401, 403 -> UnreadableReason.ACCESS_DENIED;
            case 404 -> UnreadableReason.NOT_FOUND;
            default -> UnreadableReason.CALL_FAILED;
        };
    }

    /**
     * Whether the read ran out of its budget rather than failing.
     *
     * <p>The budget is enforced by the HTTP client, which reports the abandonment from inside the
     * SDK's own wrapping, so the chain is walked rather than only its head inspected. Bounded to a
     * fixed depth because a cause chain that cycles must not become a flag read that never returns.
     */
    private static boolean timedOut(final Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 10; depth++) {
            if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Records a read that produced no verdict, and says only what a bounded code says.
     *
     * <p>At WARN because the consequence is a night's registers not generated by this service, and
     * the morning's question is which of the four causes it was.
     */
    private static FlagDecision refused(final UnreadableReason reason) {
        LOG.warn("The YotResultsDistributionService flag could not be read; the run will be skipped and "
                + "counted, and the legacy is presumed to be generating. reason={}", reason.code());
        return new FlagDecision.Unreadable(reason);
    }
}
