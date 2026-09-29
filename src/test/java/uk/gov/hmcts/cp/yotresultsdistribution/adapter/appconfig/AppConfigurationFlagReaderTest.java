package uk.gov.hmcts.cp.yotresultsdistribution.adapter.appconfig;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.azure.core.http.HttpClient;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.core.http.policy.FixedDelayOptions;
import com.azure.core.http.policy.RetryOptions;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.config.FeatureFlagProperties;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision.UnreadableReason;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;

/**
 * The one lever, read from a stub that answers as App Configuration does.
 *
 * <p>The read is a real SDK call: the reader is handed a {@code ConfigurationClient} built against
 * this suite's stub, so what runs is {@code getConfigurationSetting(key, label)} over App
 * Configuration's own {@code kv} resource - the request the deployed pod makes, down to the
 * URL-encoded key in the path and the label in the query - and not a hand-rolled HTTP call standing
 * in for it. The client is built the way the deployed pod's is, from a connection string
 * (constitution 5.1.0); only the pair differs - a fixed, invented test secret here, the estate's
 * key from Key Vault there - and which pair signs the request changes nothing about the resource,
 * the key, the label or the answer, which is what this suite is about.
 *
 * <p><strong>Three answers and never a fourth.</strong> {@link FlagDecision} is
 * {@code ON | OFF | UNREADABLE(reason)} and the port promises it never throws, so every case here
 * goes through {@link #decisionOf}, which asserts that promise before it looks at the answer. That
 * is deliberate: the flag gates the start of every nightly run, and a reader that threw would make
 * an App Configuration outage indistinguishable from a job that crashed. It is also why the suite
 * asserts the reason rather than only the verdict - {@code unreadable-not-found} and
 * {@code unreadable-access-denied} are a missing setting and a missing role assignment, two
 * different things to go and fix at 18:00.
 *
 * <p><strong>Fail-closed.</strong> Only {@code enabled: true} generates. A store that is absent, one
 * that refuses this pod's identity, one that is merely slow and one that answers with something this
 * service cannot read all mean the same thing to the run - skip, and count the skip - because the
 * legacy is presumed to be generating and generating twice is the one failure that reaches a child's
 * family (constitution Cutover Rule).
 *
 * <p><strong>What is deliberately not pinned.</strong> The {@code api-version} the SDK sends
 * (observed: {@code 2023-11-01}) is the SDK's contract with the store, not this service's with the
 * flag; pinning it here would fail the day the BOM moves and would say nothing about the lever. The
 * budget likewise lands on the client the reader is given, so the timeout case below states what the
 * reader does with a read that did not answer in time, which is the part that is this service's.
 *
 * @see <a href="file:../../../../../../../../../specs/002-consolidate-progression-leg/contracts/appconfiguration/feature-flag-value.schema.json">the
 *     feature-flag value shape</a>
 */
@DisplayName("App Configuration feature-flag reader")
class AppConfigurationFlagReaderTest {

    /** The shipped key: the same setting the producer and the legacy read, spelled the same way. */
    private static final String KEY = ".appconfig.featureflag/YotResultsDistributionService";

    /** That key as it reaches the wire - the separator is encoded, so the path is one segment. */
    private static final String KEY_PATH = "/kv/.appconfig.featureflag%2FYotResultsDistributionService";

    /** Any {@code kv} read, whatever key it is for, so a wrong key is caught by a verify not a 404. */
    private static final String ANY_KEY_PATH = "/kv/.*";

    /** The stack's label: one store serves every stack, and the label is which stack asked. */
    private static final String LABEL = "ste86";

    /** The shipped budget for the read. */
    private static final Duration BUDGET = Duration.ofSeconds(2);

    /** A budget short enough that the suite can outlast it without waiting on the shipped one. */
    private static final Duration SHORT_BUDGET = Duration.ofMillis(250);

    /** Longer than {@link #SHORT_BUDGET}, so the read is abandoned rather than merely slow. */
    private static final int BEYOND_THE_BUDGET_MS = 1500;

    /** Invented here and a secret of nothing: the stub authorises no one. */
    private static final String FIXED_TEST_SECRET =
            Base64.getEncoder().encodeToString("not-a-secret".getBytes(StandardCharsets.UTF_8));

    private static final String FIXED_TEST_ID = "0-l0-s0:yotresultsdistributiontest";

    /** Stands in for whatever text the store's answer carries, so the suite can look for it. */
    private static final String STORE_TEXT_MARKER = "STORETEXTMARKERZQX7";

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    private static WireMockServer server;

    @BeforeAll
    static void startStub() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stopStub() {
        server.stop();
    }

    @BeforeEach
    void resetStub() {
        server.resetAll();
    }

    /**
     * Asserts the port's promise before reading the answer, on every path this suite takes.
     *
     * <p>Written as an assertion rather than left to surface as a test error because "never throws"
     * is the contract {@link uk.gov.hmcts.cp.yotresultsdistribution.application.FeatureFlagReader} states and
     * the reason this adapter exists at all: an SDK exception reaching the job is a night with no
     * register and no reason recorded.
     */
    private static FlagDecision decisionOf(final AppConfigurationFlagReader reader) {
        final AtomicReference<FlagDecision> answer = new AtomicReference<>();
        assertThatCode(() -> answer.set(reader.read()))
                .as("the flag port answers or says why it could not; it never throws")
                .doesNotThrowAnyException();
        return answer.get();
    }

    private static AppConfigurationFlagReader reader() {
        return readerFor(KEY, LABEL, BUDGET);
    }

    private static AppConfigurationFlagReader readerFor(
            final String key, final String label, final Duration budget) {
        final FeatureFlagProperties properties =
                new FeatureFlagProperties(storeConnectionString(), key, label, budget);
        return new AppConfigurationFlagReader(properties, clientFor(properties));
    }

    /**
     * The SDK client the reader reads through, pointed at the stub.
     *
     * <p>Retries are off and the budget is the client's response timeout, which is where the
     * deployed reader puts it too: what the suite pins is the reader's reading of the answer, and a
     * retry policy of the SDK's choosing would make "the store was asked once, under this key" a
     * statement about the SDK rather than about the reader.
     */
    private static ConfigurationClient clientFor(final FeatureFlagProperties properties) {
        return new ConfigurationClientBuilder()
                .connectionString(properties.connectionString())
                .retryOptions(new RetryOptions(new FixedDelayOptions(0, Duration.ZERO)))
                .httpClient(new NettyAsyncHttpClientBuilder()
                        .responseTimeout(properties.timeout())
                        .build())
                .buildClient();
    }

    /**
     * The same client over an HTTP client somebody else built, which is the one the adapter offers.
     *
     * @param httpClient the bounded client under test
     * @param properties where the stub is
     * @return the SDK client, signed with the fixed test pair
     */
    private static ConfigurationClient clientOn(
            final HttpClient httpClient, final FeatureFlagProperties properties) {
        return new ConfigurationClientBuilder()
                .connectionString(properties.connectionString())
                .retryOptions(new RetryOptions(new FixedDelayOptions(0, Duration.ZERO)))
                .httpClient(httpClient)
                .buildClient();
    }

    /**
     * The same client with no timeout of its own, which is the shape a black-holed store produces.
     *
     * <p>Every case above hands the reader a client the suite has already bounded, so what they
     * assert is the reader's reading of an answer that came. This one asserts the part that is the
     * reader's whether the client was bounded or not: the budget is the whole read, and a leg of it
     * the HTTP client's own timeouts do not cover - a connection that is accepted and never
     * answered, a slow identity endpoint in front of the store, an SDK layer between the two - must
     * still leave the job with a decision inside the budget it was given.
     *
     * @param properties where the flag is read from
     * @return a client that will wait on the SDK's own defaults
     */
    private static ConfigurationClient unboundedClientFor(final FeatureFlagProperties properties) {
        return new ConfigurationClientBuilder()
                .connectionString(properties.connectionString())
                .retryOptions(new RetryOptions(new FixedDelayOptions(0, Duration.ZERO)))
                .httpClient(new NettyAsyncHttpClientBuilder().build())
                .buildClient();
    }

    /** The stub's connection string, signed with the fixed test pair. */
    private static String storeConnectionString() {
        return "Endpoint=" + server.baseUrl() + ";Id=" + FIXED_TEST_ID + ";Secret=" + FIXED_TEST_SECRET;
    }

    /** The store's answer for a setting that is there, carrying {@code value} verbatim. */
    private static String settingCarrying(final String value) {
        return "{\"key\":" + MAPPER.writeValueAsString(KEY)
                + ",\"label\":" + MAPPER.writeValueAsString(LABEL)
                + ",\"content_type\":\"application/vnd.microsoft.appconfig.ff+json\""
                + ",\"value\":" + MAPPER.writeValueAsString(value)
                + ",\"tags\":{},\"locked\":false"
                + ",\"last_modified\":\"2026-09-01T18:00:00+00:00\",\"etag\":\"cr-1\"}";
    }

    /** App Configuration's feature-flag JSON, as the vendored value schema declares it. */
    private static String flagValue(final boolean enabled) {
        return "{\"id\":\"YotResultsDistributionService\",\"description\":\"" + STORE_TEXT_MARKER + "\""
                + ",\"enabled\":" + enabled
                + ",\"conditions\":{\"client_filters\":[]}}";
    }

    private static void answering(final int status, final String body) {
        server.stubFor(get(urlPathMatching(ANY_KEY_PATH)).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/vnd.microsoft.appconfig.kv+json")
                .withBody(body)));
    }

    private static void answeringAfter(final int delayMillis, final String body) {
        server.stubFor(get(urlPathMatching(ANY_KEY_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withFixedDelay(delayMillis)
                .withHeader("Content-Type", "application/vnd.microsoft.appconfig.kv+json")
                .withBody(body)));
    }

    private static FlagDecision unreadable(final UnreadableReason reason) {
        return new FlagDecision.Unreadable(reason);
    }

    @Nested
    @DisplayName("the setting the deployment named")
    class TheSettingRead {

        @Test
        @DisplayName("reads the flag as an App Configuration key-value resource")
        void read_should_read_the_kv_resource() {
            answering(200, settingCarrying(flagValue(true)));

            decisionOf(reader());

            server.verify(getRequestedFor(urlPathEqualTo(KEY_PATH)));
        }

        /**
         * The key is the lever's identity. A reader that read a key of its own invention would be a
         * fourth lever wearing the flag's name, so the configured key - and not the shipped default
         * - is what reaches the wire.
         */
        @Test
        @DisplayName("asks under the configured key, not one of its own")
        void read_should_ask_under_the_configured_key() {
            answering(200, settingCarrying(flagValue(true)));

            decisionOf(readerFor(".appconfig.featureflag/AnotherService", LABEL, BUDGET));

            server.verify(getRequestedFor(
                    urlPathEqualTo("/kv/.appconfig.featureflag%2FAnotherService")));
        }

        /** One store serves every stack, so a read with no label is another stack's answer. */
        @Test
        @DisplayName("asks under the stack's label")
        void read_should_ask_under_the_stacks_label() {
            answering(200, settingCarrying(flagValue(true)));

            decisionOf(readerFor(KEY, "ste41", BUDGET));

            server.verify(getRequestedFor(urlPathEqualTo(KEY_PATH))
                    .withQueryParam("label", equalTo("ste41")));
        }

        /**
         * No cache, and none accumulated across calls either: a flag that was on an hour ago says
         * nothing about a cutover rolled back ten minutes ago, so each read is a read.
         */
        @Test
        @DisplayName("asks the store every time it is asked")
        void read_should_ask_the_store_every_time() {
            answering(200, settingCarrying(flagValue(true)));
            final AppConfigurationFlagReader reader = reader();

            decisionOf(reader);
            decisionOf(reader);

            server.verify(2, getRequestedFor(urlPathEqualTo(KEY_PATH)));
        }
    }

    @Nested
    @DisplayName("what the flag says")
    class TheAnswer {

        @Test
        @DisplayName("enabled is this service generating")
        void enabled_is_this_service_generating() {
            answering(200, settingCarrying(flagValue(true)));

            assertThat(decisionOf(reader()))
                    .as("only enabled: true lets a run generate")
                    .isEqualTo(FlagDecision.ON);
        }

        @Test
        @DisplayName("disabled is the legacy generating")
        void disabled_is_the_legacy_generating() {
            answering(200, settingCarrying(flagValue(false)));

            assertThat(decisionOf(reader())).isEqualTo(FlagDecision.OFF);
        }

        /**
         * The platform writes conditions, descriptions and whatever else a feature flag carries;
         * the vendored value schema is {@code additionalProperties: true} and this service reads
         * {@code enabled} and nothing else. Refusing a shape the portal produces would be a nightly
         * skip caused by a field nobody here reads.
         */
        @Test
        @DisplayName("reads enabled out of a flag carrying everything else the platform writes")
        void reads_enabled_out_of_a_fuller_flag() {
            answering(200, settingCarrying("{\"id\":\"YotResultsDistributionService\",\"enabled\":true,"
                    + "\"conditions\":{\"client_filters\":[{\"name\":\"Microsoft.Percentage\","
                    + "\"parameters\":{\"Value\":100}}]},\"description\":\"cutover\"}"));

            assertThat(decisionOf(reader())).isEqualTo(FlagDecision.ON);
        }
    }

    @Nested
    @DisplayName("no answer at all - unreadable, and which unreadable")
    class NoAnswer {

        /**
         * A setting nobody has written yet, or one written under another label. It is the case a
         * misconfigured label produces, and it is worth its own code: the fix is to write the
         * setting, not to chase a permission.
         */
        @Test
        @DisplayName("a store holding no such setting is unreadable, not found")
        void a_store_holding_no_such_setting_is_unreadable_not_found() {
            answering(404, "{\"type\":\"about:blank\",\"title\":\"not found\",\"status\":404}");

            assertThat(decisionOf(reader())).isEqualTo(unreadable(UnreadableReason.NOT_FOUND));
        }

        /**
         * A store that refuses the key - revoked, rotated, or never valid for this store - refuses
         * every read, and it must be refused as its own cause, or the night after a key rotation
         * looks like an empty store.
         */
        @ParameterizedTest(name = "{0} is access denied")
        @ValueSource(ints = {401, 403})
        @DisplayName("a store that refuses the connection string's key is unreadable, access denied")
        void a_store_that_refuses_this_identity_is_unreadable_access_denied(final int status) {
            answering(status, "{\"status\":" + status + "}");

            assertThat(decisionOf(reader())).isEqualTo(unreadable(UnreadableReason.ACCESS_DENIED));
        }

        @ParameterizedTest(name = "{0} is a failed call")
        @ValueSource(ints = {429, 500, 502, 503})
        @DisplayName("a store that failed is unreadable, call failed")
        void a_store_that_failed_is_unreadable_call_failed(final int status) {
            answering(status, "{\"status\":" + status + "}");

            assertThat(decisionOf(reader())).isEqualTo(unreadable(UnreadableReason.CALL_FAILED));
        }

        /**
         * The budget exists so that a slow store skips the run rather than holding it open, and the
         * skip has to be legible as slowness: a store that never answered and one that answered
         * "off" are the same verdict and completely different mornings.
         */
        @Test
        @DisplayName("a read that outlasts its budget is unreadable, timed out")
        void a_read_that_outlasts_its_budget_is_unreadable_timed_out() {
            answeringAfter(BEYOND_THE_BUDGET_MS, settingCarrying(flagValue(true)));

            assertThat(decisionOf(readerFor(KEY, LABEL, SHORT_BUDGET)))
                    .isEqualTo(unreadable(UnreadableReason.TIMED_OUT));
        }

        /**
         * A connection that died mid-answer reaches the SDK as an I/O failure rather than a status,
         * which is the shape most likely to escape a mapping written from status codes alone. Which
         * cause it is recorded under is the reader's to choose; that it is a bounded cause and not
         * an exception is not.
         */
        @Test
        @DisplayName("a connection that never answered is a decision, not an exception")
        void a_connection_that_never_answered_is_a_decision() {
            server.stubFor(get(urlPathMatching(ANY_KEY_PATH))
                    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            assertThat(decisionOf(reader()))
                    .asInstanceOf(InstanceOfAssertFactories.type(FlagDecision.Unreadable.class))
                    .extracting(FlagDecision.Unreadable::reason)
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("the budget is the whole read")
    class TheWholeRead {

        /**
         * A store that answers so late the budget cannot be what brought the read back.
         *
         * <p>Comfortably longer than {@link #SHORT_BUDGET} and comfortably shorter than the suite's
         * own patience: what it stands for is an endpoint that has accepted the connection and will
         * not answer.
         */
        private static final int BLACK_HOLED_MS = 5000;

        /**
         * What two calls to a wall clock cost, and nothing else.
         *
         * <p>Not a margin the reader is allowed: {@code yotresultsdistribution.feature.timeout} is the
         * deadline the job is promised, so the only thing this may absorb is the cost of measuring
         * it - handing the read to a thread, coming back off the wait, reading the clock twice.
         * A tolerance large enough to hide a leg of the read would let the deadline be the budget
         * plus whatever the reader felt like adding, which is the finding this case answers.
         */
        private static final Duration JITTER = Duration.ofMillis(150);

        /**
         * An outer deadline the client's own legs are deliberately well inside of.
         *
         * <p>A deployed pod gives one value to both, so which of the two ended a read cannot be
         * seen there at all; here they are separate properties, and the difference between them is
         * what the case below reads.
         */
        private static final Duration PATIENT_BUDGET = Duration.ofSeconds(3);

        /**
         * The nightly job asks this question first and does nothing until it is answered, so a read
         * that outlasts its budget is a run that has not started: at 18:00 the difference between a
         * skipped run and a stalled one is an alert nobody gets. The configured timeout is
         * therefore the <em>outer</em> deadline - the credential, the handshake, the pool and the
         * response are all inside it - and not the budget of whichever leg the HTTP client happens
         * to bound.
         */
        @Test
        @DisplayName("a black-holed store is unreadable within the configured timeout, not the "
                + "timeout plus a margin")
        void a_black_holed_store_answers_inside_the_budget() {
            answeringAfter(BLACK_HOLED_MS, settingCarrying(flagValue(true)));
            final FeatureFlagProperties properties =
                    new FeatureFlagProperties(storeConnectionString(), KEY, LABEL, SHORT_BUDGET);
            final AppConfigurationFlagReader reader =
                    new AppConfigurationFlagReader(properties, unboundedClientFor(properties));

            final Instant asked = Instant.now();
            final FlagDecision decision = decisionOf(reader);
            final Duration waited = Duration.between(asked, Instant.now());

            assertThat(decision)
                    .as("a read that did not answer in time is a skipped run with a cause on it")
                    .isEqualTo(unreadable(UnreadableReason.TIMED_OUT));
            assertThat(waited)
                    .as("and it says so within the timeout the deployment configured, which is "
                            + "the deadline itself and not the first term of one")
                    .isLessThan(properties.timeout().plus(JITTER));
        }

        /**
         * The other half of the same claim, and the half the client is answerable for.
         *
         * <p>The outer bound above catches a read whose legs nobody bounded, so it would catch this
         * one too - eventually. What the client's own four timeouts are for is the leg the outer
         * bound cannot see the shape of, and the cost of leaving them off is not a wrong decision
         * but an abandoned read holding its thread and its connection until the SDK's own default
         * gives up, which is a minute rather than the budget. So the two bounds are configured
         * separately here and the case reads how long the answer took: at the client's budget it is
         * the client that ended it, and the reader's own deadline was never reached.
         *
         * <p>The live wiring builds its client through the factory this asserts on, which is what
         * makes the compose loop's client the deployed one in this respect as well as in the key,
         * the label, the fail-closed parsing and the outer deadline.
         */
        @Test
        @DisplayName("the client the adapter builds ends a black-holed read at its own budget, not "
                + "at the reader's outer deadline")
        void the_adapters_client_ends_a_black_holed_read_at_the_budget_it_was_built_with() {
            answeringAfter(BLACK_HOLED_MS, settingCarrying(flagValue(true)));
            final FeatureFlagProperties legs = new FeatureFlagProperties(storeConnectionString(), KEY, LABEL, SHORT_BUDGET);
            final FeatureFlagProperties patient = new FeatureFlagProperties(storeConnectionString(), KEY, LABEL, PATIENT_BUDGET);
            final AppConfigurationFlagReader reader = new AppConfigurationFlagReader(patient,
                    clientOn(AppConfigurationFlagReader.httpClientFor(legs), legs));

            final Instant asked = Instant.now();
            final FlagDecision decision = decisionOf(reader);
            final Duration waited = Duration.between(asked, Instant.now());

            assertThat(decision)
                    .as("either bound answers the same thing, which is the point: a read that did "
                            + "not answer in time is a skipped run with a cause on it")
                    .isEqualTo(unreadable(UnreadableReason.TIMED_OUT));
            assertThat(waited)
                    .as("and it is the client that ended it, at the budget the client was built "
                            + "with - a client with no legs of its own would have been ended by "
                            + "the reader's outer deadline instead, having held the connection "
                            + "for it")
                    .isLessThan(SHORT_BUDGET.plus(JITTER).plus(JITTER));
        }
    }

    @Nested
    @DisplayName("an answer this service cannot read")
    class NotAFlag {

        /**
         * The vendored value schema is the contract: an object with {@code id} and a boolean
         * {@code enabled}. Everything short of that is unreadable and never on - a setting whose
         * value is a bare string, one with no verdict in it at all, and one whose verdict is a
         * string that happens to spell "true", which is the shape a hand-edited setting produces
         * and the one a lenient reader would generate on.
         */
        @ParameterizedTest(name = "value {0}")
        @ValueSource(strings = {
            "not json at all",
            "{\"id\":\"YotResultsDistributionService\"}",
            "{\"id\":\"YotResultsDistributionService\",\"enabled\":\"true\"}",
            "{\"id\":\"YotResultsDistributionService\",\"enabled\":null}",
            "[]",
        })
        @DisplayName("a value that is not a feature flag is unreadable, malformed")
        void a_value_that_is_not_a_feature_flag_is_unreadable_malformed(final String value) {
            answering(200, settingCarrying(value));

            assertThat(decisionOf(reader())).isEqualTo(unreadable(UnreadableReason.MALFORMED));
        }

        /**
         * A gateway's error page served with a 200 is the everyday version of this: the store did
         * not answer, whatever the status line says. Which cause it lands under is the reader's
         * choice - the setting never arrived, so it is not obviously the value that is malformed -
         * but it is a bounded cause and it is not on.
         */
        @Test
        @DisplayName("a body that is not a setting is unreadable, with a bounded cause")
        void a_body_that_is_not_a_setting_is_unreadable() {
            answering(200, "<html>" + STORE_TEXT_MARKER + "</html>");

            assertThat(decisionOf(reader()))
                    .asInstanceOf(InstanceOfAssertFactories.type(FlagDecision.Unreadable.class))
                    .extracting(FlagDecision.Unreadable::reason)
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("the promise the job depends on")
    class NeverThrows {

        /**
         * Stated once in its own right, and not only as the helper every case above passes
         * through. The job asks this question first, every night; an exception here ends the run
         * before a single batch is assembled and leaves nothing counted to say why.
         */
        @ParameterizedTest(name = "status {0}")
        @ValueSource(ints = {200, 400, 401, 403, 404, 412, 429, 500, 502, 503, 504})
        @DisplayName("read_never_throws_however_the_store_answers")
        void read_never_throws_however_the_store_answers(final int status) {
            answering(status, "{\"status\":" + status + "}");

            assertThatCode(() -> reader().read()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("answers off rather than throwing when the store is not there at all")
        void answers_rather_than_throwing_when_the_store_is_not_there() {
            server.stubFor(get(urlPathMatching(ANY_KEY_PATH))
                    .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

            assertThat(decisionOf(reader()).generates())
                    .as("nothing but a read enabled flag generates")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("what a failure is allowed to say")
    class Privacy {

        /**
         * The unreadable cause labels the skipped counter and goes into the log index, so it is a
         * fixed code and never the store's text. An SDK message quoting the response body would
         * make one series per response, which is a cardinality problem and a disclosure at the same
         * time (constitution Principle VII).
         */
        @Test
        @DisplayName("carries a bounded code and none of the store's text")
        void a_failure_carries_a_bounded_code_and_none_of_the_stores_text() {
            answering(500, "{\"detail\":\"" + STORE_TEXT_MARKER + "\"}");

            final String code = decisionOf(reader()).code();

            assertThat(code)
                    .isIn(Arrays.stream(UnreadableReason.values())
                            .map(UnreadableReason::code)
                            .toList());
            assertThat(code).doesNotContain(STORE_TEXT_MARKER).doesNotContain(server.baseUrl());
        }

        @Test
        @DisplayName("writes neither the answered body nor the endpoint into the log")
        void a_failure_writes_neither_the_body_nor_the_endpoint() {
            answering(500, "{\"detail\":\"" + STORE_TEXT_MARKER + "\"}");

            try (CapturedLog log = CapturedLog.capturing(AppConfigurationFlagReader.class)) {
                decisionOf(reader());

                assertThat(log.renderings())
                        .noneMatch(line -> line.contains(STORE_TEXT_MARKER)
                                || line.contains(server.baseUrl())
                                || line.contains(FIXED_TEST_SECRET));
            }
        }
    }
}
