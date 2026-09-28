package uk.gov.hmcts.cp.yotresultsdistribution.adapter.payload;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPause;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.PayloadUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;

/**
 * The fallback read, against a stub that answers as the query side does.
 *
 * <p>The K twins live here, repaired. K7 and K8 assert the literal URL
 * {@code 'undefined/results-query-api/…'} — the {@code undefined} is the unset
 * {@code RESULTS_CONTEXT_API_BASE_URI} their own {@code beforeEach} deletes, so the two cases that
 * exist to pin the endpoint pin a string no deployment ever builds. Here the base URI is a real
 * one, and the path, the vendor media type and the identity header are all asserted, because all
 * three are the query side's contract: a wrong media type is a 406 from the real service and a
 * perfectly good payload from any stub that does not check, so the stub checks.
 *
 * <p>K8's {@code ?hearingDate=} form is <strong>not</strong> twinned. That query string belongs to
 * the {@code EXT_} endpoint ({@code index.js:63-67}); the {@code INT_} entry this flow uses takes
 * the hearing id and nothing else ({@code :68-71}), so the absence of a query string is asserted
 * rather than left unsaid.
 *
 * <p><strong>K2 is repointed at the corrected retry taxonomy — defect fix C3.</strong> It counts two
 * attempts after an {@code ECONNABORTED} and stops there; what decides a retry is not the count but
 * {@code AxiosRetryWrapper.js:34}'s rule that a response carrying a status at or below 429 is never
 * retried, which makes 429 and 408 the least-retried failures in the estate. The taxonomy asserted
 * below is the fixed one and it is the submission client's too.
 *
 * <p><strong>K3 is repaired.</strong> Its body contains no {@code expect} at all: it awaits the
 * handler with no {@code cjscppuid} and asserts nothing, which is a case that cannot fail. What it
 * was written over is a real branch — {@code index.js:176-178} logs "not found in cache and no
 * CJSCPPUID supplied" and returns, so a run with no identity loses its register in silence. The
 * repaired twin asserts the outcome: no identity anywhere is a recorded transient failure, and no
 * unauthenticated request is sent.
 *
 * <p><strong>C32 is the last nest.</strong> The legacy's {@code getPrefixHearing} returns
 * {@code undefined} for an empty body and {@code null} for a failed read, and both stop the run
 * reporting success. Here neither is silence.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> rows C3
 *     and C32
 */
@DisplayName("Results query hearing payload client")
class ResultsQueryHearingPayloadClientTest {

    private static final UUID HEARING_ID = UUID.fromString("1828f356-f746-4f2d-932b-79ef2df95c80");
    private static final String PATH =
            "/results-query-api/query/api/rest/results/hearingDetails/internal/" + HEARING_ID;
    private static final String SYSTEM_USER_ID = "9f61bdbb-6f1a-4c0f-9a3d-6b8f0f1c2a44";

    /** The user a message names, distinct from the configured identity so the two cannot be confused. */
    private static final String SHARING_USER = "6e2f0a1c-9d4b-4f38-8a52-1c7b3e5d9f04";

    /** The attempt budget every client in this suite is built with. */
    private static final int MAX_ATTEMPTS = 3;

    /** Stands in for the defendant detail a truncated response would have a parser quote back. */
    private static final String DEFENDANT_MARKER = "DEFENDANTMARKERZQX7";

    /**
     * K1's payload, reduced to what that case reads: the defendant identity it asserts. The legacy
     * fixture is a whole hearing; carrying one here would make the suite look as though it depended
     * on hearing content, which it does not.
     */
    private static final String PAYLOAD = """
            {"hearing":{"id":"1828f356-f746-4f2d-932b-79ef2df95c80",
             "prosecutionCases":[{"defendants":[
              {"id":"6647df67-6f4c-4b4f-9e94-1e0a4f2a1b3c"}]}]},
             "sharedTime":"2020-06-01T10:00:00Z"}
            """;

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    /** The shipped back-off settings for this client, so the suite asserts what an operator gets. */
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(2);

    /**
     * The shipped connect and read timeouts added together — what one attempt at this read can cost.
     *
     * <p>Nothing here reads it: this client is handed no deadline, so it makes no reservation
     * against one. It is still stated from the shipped pair rather than invented, so the day the
     * read does become deadline-bounded the number it reserves is the number startup budgets.
     */
    private static final Duration ATTEMPT_WORST_CASE = Duration.ofSeconds(15);

    private static WireMockServer server;

    private ResultsQueryHearingPayloadClient client;
    private RecordingPause pause;

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
        pause = new RecordingPause();
        client = clientFor(SYSTEM_USER_ID);
    }

    /**
     * The shipped policy waits a second and then two. Living through three of those to observe an
     * attempt count would make the suite slow without making it say anything more, so the waits are
     * recorded rather than taken — and recording them is also how the policy itself is asserted.
     */
    private ResultsQueryHearingPayloadClient clientFor(final String systemUserId) {
        return new ResultsQueryHearingPayloadClient(
                RestClient.builder().baseUrl(server.baseUrl()).build(),
                systemUserId,
                MAPPER,
                new RetryPolicy(MAX_ATTEMPTS, INITIAL_BACKOFF, MAX_BACKOFF, ATTEMPT_WORST_CASE),
                pause);
    }

    /** Records what the client would have waited, so the suite proves the policy without living it. */
    private static final class RecordingPause implements RetryPause {

        private final List<Duration> waits = new ArrayList<>();

        @Override
        public void pause(final Duration duration) {
            waits.add(duration);
        }
    }

    private static DistributionCommand command() {
        return commandFrom(Optional.of(UUID.fromString(SHARING_USER)));
    }

    private static DistributionCommand commandFrom(final Optional<UUID> userId) {
        return new DistributionCommand(
                "RESULTS",
                UUID.fromString("9f1b8e2a-5c34-4a7d-9b1e-2f6a0d3c5e71"),
                HEARING_ID,
                LocalDate.parse("2020-06-01"),
                Instant.parse("2020-06-01T10:00:00Z"),
                "Hearing_Resulted",
                userId);
    }

    private static void answering(final int status, final String body) {
        server.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", ResultsQueryHearingPayloadClient.ACCEPT)
                        .withBody(body)));
    }

    /** Answers once with {@code first}, carrying a {@code Retry-After}, then serves the payload. */
    private static void answeringThenServing(final int first, final String retryAfter) {
        final ResponseDefinitionBuilder refusal = aResponse().withStatus(first);
        server.stubFor(get(urlPathEqualTo(PATH)).inScenario("recovers")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(retryAfter == null || retryAfter.isBlank()
                        ? refusal
                        : refusal.withHeader("Retry-After", retryAfter))
                .willSetStateTo("up"));
        server.stubFor(get(urlPathEqualTo(PATH)).inScenario("recovers")
                .whenScenarioStateIs("up")
                .willReturn(aResponse().withStatus(200).withBody(PAYLOAD)));
    }

    @Nested
    @DisplayName("the query side's contract — K1, K7 and K8 repaired")
    class TheContract {

        @Test
        @DisplayName("reads the internal hearing-details resource for the hearing")
        void fetch_should_read_the_internal_hearing_details_resource() {
            answering(200, PAYLOAD);

            client.fetch(command());

            server.verify(getRequestedFor(urlEqualTo(PATH)));
        }

        /**
         * K8's query string is the {@code EXT_} endpoint's, not this one's. Asserted as an absence
         * because a stray {@code ?hearingDate=} would be answered by a lenient stub and refused by
         * the real service.
         */
        @Test
        @DisplayName("sends no hearingDate query string, which belongs to the external endpoint")
        void fetch_should_send_no_query_string() {
            answering(200, PAYLOAD);

            client.fetch(command());

            assertThat(server.findAll(getRequestedFor(urlPathEqualTo(PATH))))
                    .singleElement()
                    .satisfies(request -> assertThat(request.getUrl()).isEqualTo(PATH));
        }

        @Test
        @DisplayName("asks for the internal vendor media type")
        void fetch_should_ask_for_the_internal_vendor_media_type() {
            answering(200, PAYLOAD);

            client.fetch(command());

            server.verify(getRequestedFor(urlPathEqualTo(PATH))
                    .withHeader("Accept",
                            equalTo("application/vnd.results.hearing-details-internal+json")));
        }

        @Test
        @DisplayName("answers with the payload the query side served")
        void fetch_should_answer_with_the_payload_the_query_side_served() {
            answering(200, PAYLOAD);

            assertThat(client.fetch(command()))
                    .get()
                    .satisfies(payload -> assertThat(defendantId(payload))
                            .isEqualTo("6647df67-6f4c-4b4f-9e94-1e0a4f2a1b3c"));
        }

        private String defendantId(final JsonNode payload) {
            return payload.get("hearing").get("prosecutionCases").get(0)
                    .get("defendants").get(0).get("id").stringValue();
        }
    }

    @Nested
    @DisplayName("who the read is made as — K3 repaired")
    class Identity {

        @Test
        @DisplayName("sends the user the message named")
        void fetch_should_send_the_user_the_message_named() {
            answering(200, PAYLOAD);

            client.fetch(command());

            server.verify(getRequestedFor(urlPathEqualTo(PATH))
                    .withHeader("CJSCPPUID", equalTo(SHARING_USER)));
        }

        /**
         * A message published before {@code userId} was agreed, or a replay rebuilt without the
         * original body. The legacy skips the read altogether for exactly this input
         * ({@code index.js:176-178}); here the configured identity carries it.
         */
        @Test
        @DisplayName("sends the configured identity when the message named no user")
        void fetch_should_send_the_configured_identity_when_the_message_named_none() {
            answering(200, PAYLOAD);

            client.fetch(commandFrom(Optional.empty()));

            server.verify(getRequestedFor(urlPathEqualTo(PATH))
                    .withHeader("CJSCPPUID", equalTo(SYSTEM_USER_ID)));
        }

        /**
         * The repair K3 needed. Neither the message nor the configuration named anybody, and the
         * query side authorises on that header — so an anonymous read is a 403 dressed as a cache
         * miss. The outcome is recorded rather than dropped, which is the whole of the difference
         * from the branch this twin was written over.
         */
        @Test
        @DisplayName("records a transient failure when nobody at all is named")
        void fetch_should_record_a_transient_failure_when_no_identity_is_available() {
            answering(200, PAYLOAD);

            assertThatThrownBy(() -> clientFor("  ").fetch(commandFrom(Optional.empty())))
                    .asInstanceOf(InstanceOfAssertFactories.type(PayloadUnavailableException.class))
                    .satisfies(failure -> assertThat(failure.classification())
                            .isEqualTo(FailureClassification.TRANSIENT));
        }

        @Test
        @DisplayName("sends no unauthenticated request when nobody at all is named")
        void fetch_should_send_no_unauthenticated_request() {
            answering(200, PAYLOAD);

            assertThatThrownBy(() -> clientFor("  ").fetch(commandFrom(Optional.empty())))
                    .isInstanceOf(PayloadUnavailableException.class);

            server.verify(0, getRequestedFor(urlPathEqualTo(PATH)));
        }
    }

    @Nested
    @DisplayName("the retry taxonomy — K2 repointed, defect fix C3")
    class Retries {

        /**
         * The fixed taxonomy, and the name the fix register carries: connect and read failures,
         * every 5xx, 429 and 408 are worth asking again. The legacy's rule is the inverse for the
         * two that matter — {@code AxiosRetryWrapper.js:34} abandons on any status at or below 429,
         * so a service that answered "slow down" or "you timed out" is the one thing it never
         * retries.
         */
        @ParameterizedTest(name = "{0} is retried")
        @ValueSource(ints = {408, 429, 500, 502, 503, 504})
        @DisplayName("retry_taxonomy_matches_the_submission_client")
        void retry_taxonomy_matches_the_submission_client(final int status) {
            answering(status, "");

            assertThatThrownBy(() -> client.fetch(command()))
                    .isInstanceOf(PayloadUnavailableException.class);

            server.verify(MAX_ATTEMPTS, getRequestedFor(urlPathEqualTo(PATH)));
        }

        /**
         * The other half of the same taxonomy. A malformed request, an unauthenticated one or a
         * forbidden one is not made truer by repetition, and retrying it spends the run's deadline
         * on an answer that will not change.
         *
         * <p><strong>And the answer travels with the failure.</strong> Attempting it once is only
         * half the fix: a refusal reported as transient is handed back to the broker, redelivered
         * four more times to be refused four more times, and parked at the end under
         * {@code DELIVERY_LIMIT_EXHAUSTED} — a reason that tells support the service ran out of
         * tries rather than that its credential is wrong. The classification the client chose is
         * what the pipeline settles on, so it is asserted here rather than inferred from the
         * attempt count.
         */
        @ParameterizedTest(name = "{0} is not retried")
        @ValueSource(ints = {400, 401, 403, 422})
        @DisplayName("a refusal no redelivery can change is attempted once")
        void a_refusal_is_attempted_once(final int status) {
            answering(status, "");

            assertThatThrownBy(() -> client.fetch(command()))
                    .asInstanceOf(InstanceOfAssertFactories.type(PayloadUnavailableException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .as("a redelivery cannot make a refused read succeed")
                                .isEqualTo(FailureClassification.NON_TRANSIENT);
                        assertThat(failure.reason())
                                .as("support is sent to the credential and the route, not to the "
                                        + "producer")
                                .isEqualTo(ReasonCode.PAYLOAD_READ_REFUSED);
                    });

            server.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
        }

        /**
         * The one 4xx that is not a refusal, held apart from them explicitly. The resource is
         * per-hearing, so a {@code 404} is the query side saying it does not hold this hearing —
         * an empty answer, and only the composite adapter, which alone knows the cache missed too,
         * turns the pair into the transient failure of C32.
         */
        @Test
        @DisplayName("a 404 is not a refusal: it is the empty answer C32 classifies")
        void a_not_found_is_not_a_refusal() {
            answering(404, "");

            assertThat(client.fetch(command()))
                    .as("nothing is raised at all, so no classification is chosen here")
                    .isEmpty();
        }

        /**
         * A retryable status that ran out of attempts stays transient. It is the pairing that makes
         * the classification mean something: the same client, the same exception type, two answers.
         */
        @Test
        @DisplayName("an exhausted retryable read stays transient")
        void an_exhausted_retryable_read_stays_transient() {
            answering(503, "");

            assertThatThrownBy(() -> client.fetch(command()))
                    .asInstanceOf(InstanceOfAssertFactories.type(PayloadUnavailableException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.TRANSIENT);
                        assertThat(failure.reason()).isEqualTo(ReasonCode.PAYLOAD_UNAVAILABLE);
                    });
        }

        @Test
        @DisplayName("retries a connection that never answered")
        void fetch_should_retry_a_connection_that_never_answered() {
            server.stubFor(get(urlPathEqualTo(PATH))
                    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            assertThatThrownBy(() -> client.fetch(command()))
                    .isInstanceOf(PayloadUnavailableException.class);

            server.verify(MAX_ATTEMPTS, getRequestedFor(urlPathEqualTo(PATH)));
        }

        @Test
        @DisplayName("answers from a retry that succeeded rather than failing the run")
        void fetch_should_answer_from_a_retry_that_succeeded() {
            server.stubFor(get(urlPathEqualTo(PATH))
                    .inScenario("recovers")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(503))
                    .willSetStateTo("up"));
            server.stubFor(get(urlPathEqualTo(PATH))
                    .inScenario("recovers")
                    .whenScenarioStateIs("up")
                    .willReturn(aResponse().withStatus(200).withBody(PAYLOAD)));

            assertThat(client.fetch(command())).isPresent();
            server.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
        }

        @Test
        @DisplayName("carries only the bounded reason code out of an exhausted read")
        void an_exhausted_read_carries_only_the_bounded_reason_code() {
            answering(503, "");

            assertThatThrownBy(() -> client.fetch(command()))
                    .hasMessage(ReasonCode.PAYLOAD_UNAVAILABLE.code());
        }
    }

    /**
     * The policy the three clients share, seen from this one — defect fix C3.
     *
     * <p>The register's row promises a policy "applied identically to all three named clients", and
     * for a while that was true of the status taxonomy and of nothing else: this client waited the
     * legacy's fixed second, however unwell the results context was, and ignored a
     * {@code Retry-After} it was sent. It now holds the same
     * {@link uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy} object the progression gateway
     * and the reference-data read hold, so the three cannot disagree about a wait.
     *
     * <p><strong>503 is the status these cases are written on.</strong> It is the one every client
     * retries and the one a service under load actually answers with, and it is where honouring a
     * {@code Retry-After} matters most — a 429-only reading would ignore precisely the service that
     * has told you when it will be back.
     */
    @Nested
    @DisplayName("the shared retry policy — defect fix C3")
    class SharedPolicy {

        @Test
        @DisplayName("the wait between attempts grows rather than hammering the query side")
        void the_wait_between_attempts_grows_rather_than_hammering_the_query_side() {
            answering(503, "");

            assertThatThrownBy(() -> client.fetch(command()))
                    .isInstanceOf(PayloadUnavailableException.class);

            assertThat(pause.waits)
                    .as("the legacy waits a fixed second, every time, however unwell the other "
                            + "side is")
                    .containsExactly(INITIAL_BACKOFF, MAX_BACKOFF);
        }

        @ParameterizedTest(name = "Retry-After: [{0}] waits {1}")
        @CsvSource({
            "1,                              PT1S",
            "2,                              PT2S",
            "3600,                           PT2S",
            "'Wed, 21 Oct 2026 07:28:00 GMT',PT1S",
            "when I say so,                  PT1S",
            "'',                             PT1S",
        })
        @DisplayName("retry_after_is_honoured_on_a_503_bounded_and_delta_seconds_only")
        void retry_after_is_honoured_on_a_503_bounded_and_delta_seconds_only(
                final String header, final Duration expectedWait) {
            answeringThenServing(503, header);

            assertThat(client.fetch(command())).isPresent();
            assertThat(pause.waits).containsExactly(expectedWait);
        }
    }

    @Nested
    @DisplayName("an answer that held no hearing")
    class NothingHeld {

        /**
         * The results context answers a hearing it does not hold with {@code 200} and an empty
         * object, so a successful status proves nothing — the legacy's own content test
         * ({@code index.js:50-52}) is the right one and is kept. What is not kept is what it means:
         * there it is an undifferentiated {@code undefined}; here it is an answer, and the composite
         * adapter is what decides that an answer holding nothing, after two cache misses, is a
         * transient failure.
         */
        @ParameterizedTest(name = "[{index}]")
        @ValueSource(strings = {"", "{}", "   "})
        @DisplayName("is an empty answer, not a payload")
        void a_body_with_no_content_is_an_empty_answer(final String body) {
            answering(200, body);

            assertThat(client.fetch(command())).isEmpty();
        }

        @Test
        @DisplayName("a 404 is an empty answer, attempted once")
        void a_not_found_is_an_empty_answer() {
            answering(404, "");

            assertThat(client.fetch(command())).isEmpty();
            server.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
        }

        /**
         * A gateway error page served with a 200 is the everyday case. Nothing was obtained, so it
         * is reported for the same reason a 502 is — an unreadable answer is not an answer that the
         * hearing is not held.
         */
        @Test
        @DisplayName("a body that is not JSON is a failure, not an empty answer")
        void a_body_that_is_not_json_is_a_failure() {
            answering(200, "<html>gateway timeout</html>");

            assertThatThrownBy(() -> client.fetch(command()))
                    .isInstanceOf(PayloadUnavailableException.class);
        }
    }

    @Nested
    @DisplayName("neither source answered — defect fix C32")
    class DoubleMiss {

        /**
         * The end of the chain, over real HTTP. The cache is cold and the query side holds nothing;
         * the legacy reports success having produced nothing, and this raises a transient failure
         * the broker will redeliver.
         */
        @ParameterizedTest(name = "[{index}]")
        @ValueSource(ints = {200, 404})
        @DisplayName("an empty answer after a cache miss is transient, never silence")
        void an_empty_answer_after_a_cache_miss_is_transient(final int status) {
            answering(status, status == 200 ? "{}" : "");

            final CachedHearingPayloadAdapter source =
                    new CachedHearingPayloadAdapter(key -> Optional.empty(), client, "INT_");

            assertThatThrownBy(() -> source.fetch(command()))
                    .asInstanceOf(InstanceOfAssertFactories.type(PayloadUnavailableException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.TRANSIENT);
                        assertThat(failure.reason()).isEqualTo(ReasonCode.PAYLOAD_UNAVAILABLE);
                    });
        }
    }

    @Nested
    @DisplayName("what a failure is allowed to say")
    class Privacy {

        /**
         * A truncated or malformed response has a parser quote the token it choked on, and in a
         * hearing that token is a name, an address or a URN. The line may carry the failure's type;
         * it may not carry the body (constitution Principle VII).
         */
        @Test
        @DisplayName("never writes the answered body into the log")
        void fetch_should_never_write_the_answered_body_into_the_log() {
            answering(200, "{\"hearing\":{\"defendant\":\"" + DEFENDANT_MARKER + "\"");

            try (CapturedLog log = CapturedLog.everything()) {
                assertThatThrownBy(() -> client.fetch(command()))
                        .isInstanceOf(PayloadUnavailableException.class);

                assertThat(log.renderings())
                        .as("a parser quotes the token it choked on, and the token is a defendant")
                        .noneMatch(line -> line.contains(DEFENDANT_MARKER));
            }
        }

        @Test
        @DisplayName("never writes the identity it authorised with into the log")
        void fetch_should_never_write_the_identity_into_the_log() {
            answering(503, "");

            try (CapturedLog log = CapturedLog.everything()) {
                assertThatThrownBy(() -> client.fetch(command()))
                        .isInstanceOf(PayloadUnavailableException.class);

                assertThat(log.renderings())
                        .noneMatch(line -> line.contains(SHARING_USER)
                                || line.contains(SYSTEM_USER_ID));
            }
        }
    }
}
