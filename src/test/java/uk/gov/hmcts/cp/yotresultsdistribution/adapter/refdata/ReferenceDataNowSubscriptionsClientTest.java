package uk.gov.hmcts.cp.yotresultsdistribution.adapter.refdata;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReferenceDataUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.pipeline.Dates;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;

/**
 * The now-subscriptions read, against a stub that answers as reference data does.
 *
 * <p>Four things are reference data's contract and not this service's — the path, the {@code on}
 * query parameter, the vendor media type and the identity header
 * ({@code NowsHelper/service/ReferenceDataService.js:33-54}) — so the stub checks all four.
 *
 * <p><strong>The day is C12, and this suite asserts the consistency rather than the derivation.</strong>
 * The legacy computes {@code on} at {@code :38} from the register date, which carries C10's
 * relabelled local time with a literal {@code Z}; a hearing shared between 23:00 and midnight BST
 * therefore reads tomorrow's subscription set and is addressed to whoever is subscribed tomorrow.
 * The corrected day is {@code Dates.subscriptionDay} and it is derived once, in the pipeline —
 * {@code DatesTest.bst_evening_share_uses_the_share_day} is the fix's own pin. What this client owes
 * the fix is that it derives nothing of its own: the cases below build the day with the real
 * {@link Dates} from a BST evening share and assert the parameter carries that day, so a second
 * derivation appearing here would be caught by the one test that would not otherwise notice.
 *
 * <p><strong>An empty answer is an answer.</strong> The legacy catches everything and returns
 * {@code null}, which {@code CourtRegisterSubscriptions/index.js:31-44} cannot tell from "nobody is
 * subscribed" — so an outage files a register that reaches nobody and nothing records why. Here a
 * subscription set with nothing in it is returned as it stands and the matcher decides
 * {@code no-subscriptions}; a read that could not be made raises, and the run comes back.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> rows C3
 *     and C12
 */
@DisplayName("Reference data now-subscriptions client")
class ReferenceDataNowSubscriptionsClientTest {

    private static final String PATH =
            "/referencedata-query-api/query/api/rest/referencedata/now-subscriptions";
    private static final String SYSTEM_USER_ID = "9f61bdbb-6f1a-4c0f-9a3d-6b8f0f1c2a44";
    private static final String SHARING_USER = "6e2f0a1c-9d4b-4f38-8a52-1c7b3e5d9f04";

    /** The attempt budget every client in this suite is built with. */
    private static final int MAX_ATTEMPTS = 3;

    /**
     * The share that separates the fix from the defect: 23:30 on 1 June, in British Summer Time.
     * The legacy reads 2 June's subscription set for it.
     */
    private static final String BST_EVENING_SHARE = "2020-06-01T23:30:00Z";

    /** Stands in for the recipient detail a subscription body carries. */
    private static final String RECIPIENT_MARKER = "RECIPIENTMARKERZQX7";

    private static final String SUBSCRIPTIONS = """
            {"nowSubscriptions":[
             {"id":"6a0c2f1e-2b47-4d9a-8f13-5c7e0b4a9d21",
              "isCourtRegisterSubscription":true,
              "selectedCourtHouses":["B01LY00"]}]}
            """;

    /** Reference data's shape for "nobody is subscribed", which is an answer and not a silence. */
    private static final String NOBODY = "{\"nowSubscriptions\":[]}";

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    private static final Dates DATES = new Dates();

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

    private ReferenceDataNowSubscriptionsClient client;
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
        client = clientWith(Map.of());
    }

    /**
     * The shipped policy waits a second and then two. Living through three of those to observe an
     * attempt count would make the suite slow without making it say anything more, so the waits are
     * recorded rather than taken — and recording them is also how the policy itself is asserted.
     */
    private ReferenceDataNowSubscriptionsClient clientWith(
            final Map<String, String> extraHeaders) {
        return new ReferenceDataNowSubscriptionsClient(
                RestClient.builder().baseUrl(server.baseUrl()).build(),
                SYSTEM_USER_ID,
                extraHeaders,
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

    private static CallerIdentity caller() {
        return new CallerIdentity(Optional.of(UUID.fromString(SHARING_USER)));
    }

    private static LocalDate shareDay() {
        return DATES.subscriptionDay(BST_EVENING_SHARE);
    }

    private static void answering(final int status, final String body) {
        server.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", ReferenceDataNowSubscriptionsClient.ACCEPT)
                        .withBody(body)));
    }

    /** Answers once with {@code first}, carrying a {@code Retry-After}, then serves the answer. */
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
                .willReturn(aResponse().withStatus(200).withBody(SUBSCRIPTIONS)));
    }

    @Nested
    @DisplayName("reference data's contract")
    class TheContract {

        @Test
        @DisplayName("reads the now-subscriptions resource")
        void fetch_should_read_the_now_subscriptions_resource() {
            answering(200, SUBSCRIPTIONS);

            client.subscriptionsOn(shareDay(), caller());

            server.verify(getRequestedFor(urlPathEqualTo(PATH)));
        }

        @Test
        @DisplayName("asks for the now-subscriptions vendor media type")
        void fetch_should_ask_for_the_vendor_media_type() {
            answering(200, SUBSCRIPTIONS);

            client.subscriptionsOn(shareDay(), caller());

            server.verify(getRequestedFor(urlPathEqualTo(PATH))
                    .withHeader("Accept", equalTo(
                            "application/vnd.referencedata.query.get-now-subscriptions+json")));
        }

        @Test
        @DisplayName("sends the user the run is attributed to")
        void fetch_should_send_the_run_s_caller() {
            answering(200, SUBSCRIPTIONS);

            client.subscriptionsOn(shareDay(), caller());

            server.verify(getRequestedFor(urlPathEqualTo(PATH))
                    .withHeader("CJSCPPUID", equalTo(SHARING_USER)));
        }

        @Test
        @DisplayName("sends the configured identity for a run that names no user")
        void fetch_should_send_the_configured_identity_for_a_run_naming_no_user() {
            answering(200, SUBSCRIPTIONS);

            client.subscriptionsOn(shareDay(), CallerIdentity.SYSTEM);

            server.verify(getRequestedFor(urlPathEqualTo(PATH))
                    .withHeader("CJSCPPUID", equalTo(SYSTEM_USER_ID)));
        }

        /**
         * The mesh's authorisation scheme is configuration because it is not documented. A header
         * configured under a contract name replaces the contract value rather than joining it: two
         * values of {@code Accept} is a 406 from a service doing content negotiation, and two of
         * {@code CJSCPPUID} is an ambiguous caller to one authorising on identity.
         */
        @Test
        @DisplayName("sends the configured mesh headers, without letting them double the contract")
        void fetch_should_send_the_configured_mesh_headers() {
            answering(200, SUBSCRIPTIONS);

            clientWith(Map.of("X-Mesh-Group", "court-register", "Accept", "text/plain"))
                    .subscriptionsOn(shareDay(), caller());

            server.verify(getRequestedFor(urlPathEqualTo(PATH))
                    .withHeader("X-Mesh-Group", equalTo("court-register"))
                    .withHeader("Accept", equalTo(
                            "application/vnd.referencedata.query.get-now-subscriptions+json")));
        }
    }

    @Nested
    @DisplayName("the on= day — defect fix C12")
    class TheDay {

        /**
         * The consistency check. The day is derived once, in the pipeline, and this client renders
         * whatever it is handed — so a BST evening share reads the set in force on the day it was
         * shared. The legacy reads 2 June for this instant.
         */
        @Test
        @DisplayName("carries the day the pipeline derived, not one of its own")
        void fetch_should_carry_the_day_it_was_handed() {
            answering(200, SUBSCRIPTIONS);

            client.subscriptionsOn(shareDay(), caller());

            server.verify(getRequestedFor(urlPathEqualTo(PATH))
                    .withQueryParam("on", equalTo("2020-06-01")));
        }

        @Test
        @DisplayName("does not read the next day's subscription set for an evening share")
        void fetch_should_not_read_the_next_days_set() {
            answering(200, SUBSCRIPTIONS);

            client.subscriptionsOn(shareDay(), caller());

            assertThat(server.findAll(getRequestedFor(urlPathEqualTo(PATH))))
                    .singleElement()
                    .satisfies(request -> assertThat(request.getUrl())
                            .as("C12: the legacy reads 2020-06-02 for a 23:30 BST share")
                            .doesNotContain("2020-06-02"));
        }

        @Test
        @DisplayName("renders the day as a plain ISO date, zero-padded")
        void fetch_should_render_the_day_as_a_plain_iso_date() {
            answering(200, SUBSCRIPTIONS);

            client.subscriptionsOn(LocalDate.of(2026, 1, 5), caller());

            server.verify(getRequestedFor(urlPathEqualTo(PATH))
                    .withQueryParam("on", equalTo("2026-01-05")));
        }
    }

    @Nested
    @DisplayName("an answer, whatever is in it")
    class TheAnswer {

        @Test
        @DisplayName("returns the subscription set reference data holds")
        void fetch_should_return_the_subscription_set() {
            answering(200, SUBSCRIPTIONS);

            final JsonNode answer = client.subscriptionsOn(shareDay(), caller());

            assertThat(answer.get("nowSubscriptions").size()).isEqualTo(1);
        }

        /**
         * The one the legacy cannot express. Nobody subscribed is a business outcome the matcher
         * turns into {@code no-subscriptions}; it is not an outage, and it must not arrive here as
         * the same {@code null} an outage does.
         */
        @Test
        @DisplayName("returns an empty subscription set as the answer it is")
        void fetch_should_return_an_empty_set_as_an_answer() {
            answering(200, NOBODY);

            final JsonNode answer = client.subscriptionsOn(shareDay(), caller());

            assertThat(answer.get("nowSubscriptions").size()).isZero();
        }

        /**
         * A body with no {@code nowSubscriptions} member at all is still an answer reference data
         * gave; refusing it here would invent an outage out of a shape the matcher can read.
         */
        @Test
        @DisplayName("passes a body with no subscriptions member through to the matcher")
        void fetch_should_pass_a_body_with_no_subscriptions_member_through() {
            answering(200, "{}");

            assertThat((Object) client.subscriptionsOn(shareDay(), caller())).isNotNull();
        }
    }

    /**
     * The policy the three clients share, seen from this one — defect fix C3.
     *
     * <p>The register's row promises a policy "applied identically to all three named clients", and
     * for a while that was true of the status taxonomy and of nothing else: this client waited the
     * legacy's fixed second, however unwell reference data was, and ignored a {@code Retry-After} it
     * was sent. It now holds the same
     * {@link uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy} object the progression gateway
     * and the payload query hold, so the three cannot disagree about a wait.
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
        @DisplayName("the wait between attempts grows rather than hammering reference data")
        void the_wait_between_attempts_grows_rather_than_hammering_reference_data() {
            answering(503, "");

            assertThatThrownBy(() -> client.subscriptionsOn(shareDay(), caller()))
                    .isInstanceOf(ReferenceDataUnavailableException.class);

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

            assertThat((Object) client.subscriptionsOn(shareDay(), caller())).isNotNull();
            assertThat(pause.waits).containsExactly(expectedWait);
        }
    }

    @Nested
    @DisplayName("no answer at all — transient, never nobody")
    class NoAnswer {

        /**
         * The fixed C3 taxonomy, and the name the fix register carries. It is the same list the
         * progression submission client applies, which is what makes the two agree about what a
         * redelivery can fix.
         */
        @ParameterizedTest(name = "{0} is retried")
        @ValueSource(ints = {408, 429, 500, 502, 503, 504})
        @DisplayName("retry_taxonomy_matches_the_submission_client")
        void retry_taxonomy_matches_the_submission_client(final int status) {
            answering(status, "");

            assertThatThrownBy(() -> client.subscriptionsOn(shareDay(), caller()))
                    .isInstanceOf(ReferenceDataUnavailableException.class);

            server.verify(MAX_ATTEMPTS, getRequestedFor(urlPathEqualTo(PATH)));
        }

        /**
         * <strong>The answer travels with the failure.</strong> Attempting it once is only half
         * the fix: a refusal reported as transient is handed back to the broker, redelivered four
         * more times to be refused four more times, and parked at the end under
         * {@code DELIVERY_LIMIT_EXHAUSTED} — a reason that sends support to reference data's health
         * rather than to the route and the credential that are actually wrong. The {@code 404}
         * belongs in this list and not in the payload read's: the now-subscriptions resource always
         * exists, so a 404 on it is a misconfigured path and no redelivery mends a path.
         */
        @ParameterizedTest(name = "{0} is not retried")
        @ValueSource(ints = {400, 401, 403, 404, 422})
        @DisplayName("a refusal no redelivery can change is attempted once")
        void a_refusal_is_attempted_once(final int status) {
            answering(status, "");

            assertThatThrownBy(() -> client.subscriptionsOn(shareDay(), caller()))
                    .asInstanceOf(InstanceOfAssertFactories.type(
                            ReferenceDataUnavailableException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .as("a redelivery cannot make a refused read succeed")
                                .isEqualTo(FailureClassification.NON_TRANSIENT);
                        assertThat(failure.reason())
                                .isEqualTo(ReasonCode.REFERENCE_DATA_REFUSED);
                    });

            server.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
        }

        @Test
        @DisplayName("a connection that never answered is retried, then reported")
        void an_unanswered_connection_is_retried_then_reported() {
            server.stubFor(get(urlPathEqualTo(PATH))
                    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            assertThatThrownBy(() -> client.subscriptionsOn(shareDay(), caller()))
                    .isInstanceOf(ReferenceDataUnavailableException.class);

            server.verify(MAX_ATTEMPTS, getRequestedFor(urlPathEqualTo(PATH)));
        }

        @Test
        @DisplayName("answers from a retry that succeeded rather than failing the run")
        void fetch_should_answer_from_a_retry_that_succeeded() {
            server.stubFor(get(urlPathEqualTo(PATH)).inScenario("recovers")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(503))
                    .willSetStateTo("up"));
            server.stubFor(get(urlPathEqualTo(PATH)).inScenario("recovers")
                    .whenScenarioStateIs("up")
                    .willReturn(aResponse().withStatus(200).withBody(SUBSCRIPTIONS)));

            assertThat((Object) client.subscriptionsOn(shareDay(), caller())).isNotNull();
            server.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
        }

        /**
         * The whole of the difference from the legacy, said as a classification: an outage is
         * transient and carries a code of its own. A register the run could not address is worth
         * coming back for; one completed {@code no-subscriptions} because nobody was asked is a
         * register lost.
         */
        @Test
        @DisplayName("is transient, and never an empty subscription set")
        void an_outage_is_transient_and_never_an_empty_set() {
            answering(503, "");

            assertThatThrownBy(() -> client.subscriptionsOn(shareDay(), caller()))
                    .asInstanceOf(InstanceOfAssertFactories.type(
                            ReferenceDataUnavailableException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.TRANSIENT);
                        assertThat(failure.reason())
                                .isEqualTo(ReasonCode.REFERENCE_DATA_UNAVAILABLE);
                    });
        }

        /**
         * A gateway's error page served with a 200 is the everyday case. Nothing was obtained, so it
         * is reported for the same reason a 502 is — reading it as "nobody is subscribed" is exactly
         * the substitution this fix ends.
         */
        @Test
        @DisplayName("a body that is not JSON is an outage, not an empty set")
        void a_body_that_is_not_json_is_an_outage() {
            answering(200, "<html>bad gateway</html>");

            assertThatThrownBy(() -> client.subscriptionsOn(shareDay(), caller()))
                    .isInstanceOf(ReferenceDataUnavailableException.class);
        }

        @Test
        @DisplayName("carries only the bounded reason code in its message")
        void an_outage_carries_only_the_bounded_reason_code() {
            answering(503, "");

            assertThatThrownBy(() -> client.subscriptionsOn(shareDay(), caller()))
                    .hasMessage(ReasonCode.REFERENCE_DATA_UNAVAILABLE.code());
        }
    }

    @Nested
    @DisplayName("what a failure is allowed to say")
    class Privacy {

        /**
         * A subscription body names organisations, recipients and email addresses, and a parser
         * quotes the token it choked on. The line may carry the failure's type and the day; it may
         * not carry the body or the identity it authorised with (constitution Principle VII).
         */
        @Test
        @DisplayName("writes neither the answered body nor the identity into the log")
        void a_failure_should_write_neither_the_body_nor_the_identity() {
            answering(200, "{\"nowSubscriptions\":[{\"email\":\"" + RECIPIENT_MARKER + "\"");

            try (CapturedLog log = CapturedLog.everything()) {
                assertThatThrownBy(() -> client.subscriptionsOn(shareDay(), caller()))
                        .isInstanceOf(ReferenceDataUnavailableException.class);

                assertThat(log.renderings())
                        .noneMatch(line -> line.contains(RECIPIENT_MARKER)
                                || line.contains(SHARING_USER)
                                || line.contains(SYSTEM_USER_ID));
            }
        }
    }
}
