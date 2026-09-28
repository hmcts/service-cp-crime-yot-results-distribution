package uk.gov.hmcts.cp.yotresultsdistribution.adapter.progression;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPause;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.SubmissionFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.support.AdjustableClock;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;

/**
 * The outbound leg against a real HTTP server, stubbed — new tests N34 to N45.
 *
 * <p>Two things are under test and they are worth separating. The first is the
 * <strong>contract</strong>: one exact path, one exact vendor media type, and the identity header.
 * None of that is assertable against a mock of an HTTP client — a mock agrees with whatever the code
 * does — so the suite drives a socket.
 *
 * <p>The second is the <strong>classification</strong>, which is the whole of defect fix C1 and half
 * of C3. {@code ProcessOutboundCourtRegister/index.js:17-25} posts with a bare {@code axios.post},
 * catches everything, logs it and never inspects the status; its one Jest case
 * ({@code ProcessOutboundCourtRegister.test.js:20-22}) mocks the call with a promise that is
 * constructed and never returned, so it observes no status either. A refused register and a
 * delivered one are the same run today. Every case below is a status that run cannot tell apart.
 *
 * <p><strong>Success is 202 and nothing else</strong> (§4.5). A 200 or a 204 means something other
 * than the command endpoint answered, and treating it as success would mark the hearing POSTED for a
 * command nothing enqueued — a register lost with the log saying it was sent.
 *
 * <p><strong>What the body is is deliberately not this suite's business.</strong> The document is
 * held to the vendored progression schemas before it ever reaches the transport (fix C29,
 * {@code OutboundContractValidationTest}), so here it is opaque bytes and the assertion is that they
 * arrive unchanged. A gateway that inspected them would be a second place the contract was defined.
 *
 * <p><strong>Where exhaustion finishes.</strong> This class hands back a transient failure when the
 * attempts run out; the {@code FAILED} row and the {@code exhausted_message_id} that N42 also names
 * are the guard's, on the last permitted delivery, and are pinned by {@code DeliveryExhaustionIT}.
 * Two participants, one rule: the transport says the outcome is unresolved, and the delivery budget
 * says when unresolved becomes final.
 *
 * <p>Waiting is injected rather than performed, so a suite that proves a two-second
 * {@code Retry-After} was honoured takes no two seconds to do it.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> rows C1
 *     and C3
 */
@DisplayName("Progression command gateway")
class ProgressionCommandGatewayTest {

    private static final String PATH =
            "/progression-command-api/command/api/rest/progression/court-register";
    private static final String MEDIA_TYPE = "application/vnd.progression.add-court-register+json";

    /** The identity a run that names no user posts as; a configured secret, never logged. */
    private static final String SYSTEM_USER_ID = "b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234";

    /** The user a message names, distinct from the configured identity so the two cannot be confused. */
    private static final String SHARING_USER = "0b7a5c2e-4d19-4a6b-8c30-9e1f5d7b2a48";

    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(500);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(20);
    private static final int MAX_ATTEMPTS = 4;
    private static final int ACCEPTED = 202;

    /** The connect timeout every gateway here is built with; half of an attempt's worst case. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    /** The read timeout a case that is not about timing out gets; the other half. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /** Stands in for the defendant detail a progression error body would quote back. */
    private static final String DEFENDANT_MARKER = "DEFENDANTMARKERZQX7";

    /**
     * The bytes the transport carries. A literal rather than a serialised document on purpose: what
     * is under test is that the gateway sends what it was given, and building the document here
     * would let a change in the document quietly change what "unchanged" means.
     */
    private static final byte[] BODY =
            "{\"hearingId\":\"1828f356-f746-4f2d-932b-79ef2df95c80\"}"
                    .getBytes(StandardCharsets.UTF_8);

    /** The instant every case that is not about the budget is measured from. */
    private static final Instant NOW = Instant.parse("2020-06-01T10:00:00Z");

    private WireMockServer progression;
    private RecordingPause pause;
    private AdjustableClock clock;

    @BeforeEach
    void startProgression() {
        progression = new WireMockServer(wireMockConfig().dynamicPort());
        progression.start();
        pause = new RecordingPause();
        clock = AdjustableClock.startingAt(NOW);
    }

    @AfterEach
    void stopProgression() {
        progression.stop();
    }

    /**
     * A budget no case in this file can exhaust, so a case that is not about the deadline never
     * meets it. The clock does not move on its own, so an hour is unreachable by construction.
     */
    private static Instant farDeadline() {
        return NOW.plus(Duration.ofHours(1));
    }

    private ProgressionCommandGateway gateway() {
        return gateway(MAX_ATTEMPTS, Map.of(), READ_TIMEOUT, pause);
    }

    /**
     * A gateway whose declared attempt cost is the one a deployment gets: its own two timeouts,
     * added together, exactly as {@code LiveSubmissionConfig} builds the policy.
     */
    private ProgressionCommandGateway gateway(final int maxAttempts,
            final Map<String, String> extraHeaders, final Duration readTimeout,
            final RetryPause waiting) {
        return gateway(maxAttempts, extraHeaders, readTimeout, waiting,
                CONNECT_TIMEOUT.plus(readTimeout));
    }

    /**
     * A gateway whose declared attempt cost is stated separately from its socket timeouts.
     *
     * <p>They are two different numbers in principle and one number in a deployment: the policy is
     * told what an attempt can cost and the request factory is told when to give up, and
     * {@code LiveSubmissionConfig} reads both out of the same pair of settings. Separating them here
     * is what lets a budget case declare a cheap attempt — so a wait, not the attempt, is the thing
     * that does not fit — while the socket timeouts stay long enough that a case meaning to reach a
     * verdict reaches one.
     */
    private ProgressionCommandGateway gateway(final int maxAttempts,
            final Map<String, String> extraHeaders, final Duration readTimeout,
            final RetryPause waiting, final Duration attemptWorstCase) {
        final SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(readTimeout);
        return new ProgressionCommandGateway(
                RestClient.builder()
                        .baseUrl(progression.baseUrl())
                        .requestFactory(requestFactory)
                        .build(),
                SYSTEM_USER_ID,
                extraHeaders,
                new RetryPolicy(maxAttempts, INITIAL_BACKOFF, MAX_BACKOFF, attemptWorstCase),
                waiting,
                clock);
    }

    /** A run made by the user who shared the results, as a message naming one produces. */
    private static CallerIdentity sharingUser() {
        return new CallerIdentity(Optional.of(UUID.fromString(SHARING_USER)));
    }

    private void answering(final int status) {
        progression.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)));
    }

    /** Answers once with {@code first}, then with 202 — the shape every recovery case needs. */
    private void answeringThenAccepting(final int first, final String retryAfter) {
        progression.stubFor(post(urlEqualTo(PATH)).inScenario("recovers")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(retryAfter == null || retryAfter.isBlank()
                        ? aResponse().withStatus(first)
                        : aResponse().withStatus(first).withHeader("Retry-After", retryAfter))
                .willSetStateTo("up"));
        progression.stubFor(post(urlEqualTo(PATH)).inScenario("recovers")
                .whenScenarioStateIs("up")
                .willReturn(aResponse().withStatus(ACCEPTED)));
    }

    @Nested
    @DisplayName("the contract on the wire — N34")
    class Contract {

        @Test
        @DisplayName("an accepted command answers 202, once")
        void an_accepted_command_answers_202() {
            answering(ACCEPTED);

            final int status = gateway().post(BODY, CallerIdentity.SYSTEM, farDeadline());

            assertThat(status)
                    .as("the status is carried back so processed_output.response_code can hold it")
                    .isEqualTo(ACCEPTED);
            assertThat(progression.getAllServeEvents()).hasSize(1);
            assertThat(pause.waits).isEmpty();
        }

        @Test
        @DisplayName("carries the contract path, media type and identity header")
        void the_command_carries_the_contract_path_media_type_and_identity() {
            answering(ACCEPTED);

            gateway().post(BODY, CallerIdentity.SYSTEM, farDeadline());

            progression.verify(postRequestedFor(urlEqualTo(PATH))
                    .withHeader("Content-Type", equalTo(MEDIA_TYPE))
                    .withHeader("CJSCPPUID", equalTo(SYSTEM_USER_ID)));
        }

        /**
         * The P1 twin's repair. {@code ProcessOutboundCourtRegister/index.js:21} sends
         * {@code this.input.cjscppuid} and the Jest case asserts it as the literal
         * {@code undefined}, because the fixture supplies none: the register is filed by the user
         * who shared the results, and this is where that shows.
         */
        @Test
        @DisplayName("posts as the user the run names")
        void the_command_is_posted_as_the_user_the_run_names() {
            answering(ACCEPTED);

            gateway().post(BODY, sharingUser(), farDeadline());

            progression.verify(postRequestedFor(urlEqualTo(PATH))
                    .withHeader("CJSCPPUID", equalTo(SHARING_USER)));
        }

        @Test
        @DisplayName("posts as the configured identity when the run names nobody")
        void the_command_is_posted_as_the_configured_identity_when_the_run_names_nobody() {
            answering(ACCEPTED);

            gateway().post(BODY, CallerIdentity.SYSTEM, farDeadline());

            progression.verify(postRequestedFor(urlEqualTo(PATH))
                    .withHeader("CJSCPPUID", equalTo(SYSTEM_USER_ID)));
        }

        @Test
        @DisplayName("posts every attempt of one command as the same caller")
        void every_attempt_of_one_command_is_posted_as_the_same_caller() {
            // Resolved once per post, not once per attempt. A retry under a different caller would
            // be a second, differently attributed command for the same hearing.
            answeringThenAccepting(503, null);

            gateway().post(BODY, sharingUser(), farDeadline());

            progression.verify(2, postRequestedFor(urlEqualTo(PATH))
                    .withHeader("CJSCPPUID", equalTo(SHARING_USER)));
        }

        @Test
        @DisplayName("sends the document byte for byte")
        void the_body_is_sent_byte_for_byte() {
            answering(ACCEPTED);

            gateway().post(BODY, CallerIdentity.SYSTEM, farDeadline());

            assertThat(progression.getAllServeEvents().getFirst().getRequest().getBody())
                    .isEqualTo(BODY);
        }

        /**
         * The mesh's authorisation scheme is configuration because it is not documented. A header
         * configured under a contract name replaces the contract value rather than joining it: two
         * values of {@code CJSCPPUID} is an ambiguous caller to a service authorising on identity,
         * and two content types is a 415. It is the rule the reference-data client already follows.
         */
        @Test
        @DisplayName("sends the configured mesh headers without doubling the contract")
        void the_mesh_headers_are_sent_without_doubling_the_contract() {
            answering(ACCEPTED);

            gateway(MAX_ATTEMPTS,
                    Map.of("X-Mesh-Route", "progression", "Content-Type", "text/plain"),
                    Duration.ofSeconds(5), pause)
                    .post(BODY, CallerIdentity.SYSTEM, farDeadline());

            progression.verify(postRequestedFor(urlEqualTo(PATH))
                    .withHeader("X-Mesh-Route", equalTo("progression"))
                    .withHeader("Content-Type", equalTo(MEDIA_TYPE)));
        }

        @Test
        @DisplayName("never carries the caller anywhere but the identity header")
        void the_caller_never_appears_anywhere_but_the_identity_header() {
            // A user identifier in a path, a query or a register body would reach access logs and
            // progression's own store. The header is the only place it belongs.
            answering(ACCEPTED);

            gateway().post(BODY, sharingUser(), farDeadline());

            final LoggedRequest sent =
                    progression.findAll(postRequestedFor(urlEqualTo(PATH))).getFirst();
            assertThat(sent.getUrl()).doesNotContain(SHARING_USER);
            assertThat(sent.getBodyAsString()).doesNotContain(SHARING_USER);
        }
    }

    @Nested
    @DisplayName("success is 202 and nothing else — N35")
    class ContractSuccess {

        /**
         * The contract declares one success. Any other 2xx means something other than the command
         * endpoint answered — a proxy, or a route that no longer reaches it — and calling it success
         * would complete the run {@code submitted} for a command that was never enqueued.
         */
        @ParameterizedTest(name = "{0} is not the success the contract defines")
        @ValueSource(ints = {200, 201, 204})
        @DisplayName("only_202_is_success")
        void only_202_is_success(final int status) {
            answering(status);

            assertThatThrownBy(() -> gateway().post(BODY, CallerIdentity.SYSTEM, farDeadline()))
                    .isInstanceOf(SubmissionFailedException.class)
                    .asInstanceOf(InstanceOfAssertFactories.type(SubmissionFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.reason()).isEqualTo(ReasonCode.SUBMISSION_NOT_ACCEPTED);
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.NON_TRANSIENT);
                        assertThat(failure.responseCode())
                                .as("the row records what answered, not what was hoped for")
                                .isEqualTo(OptionalInt.of(status));
                    });
        }

        @Test
        @DisplayName("a success the contract does not define is never posted a second time")
        void a_success_the_contract_does_not_define_is_never_posted_again() {
            answering(200);

            assertThatThrownBy(() -> gateway().post(BODY, CallerIdentity.SYSTEM, farDeadline()))
                    .isInstanceOf(SubmissionFailedException.class);

            assertThat(progression.getAllServeEvents())
                    .as("the body may already have been applied; add-court-register appends")
                    .hasSize(1);
            assertThat(pause.waits).isEmpty();
        }
    }

    @Nested
    @DisplayName("outcomes no redelivery can change — N36, N37")
    class Refused {

        /**
         * The C29 shape, seen from the wire: a document progression will not accept. It is refused
         * here too, before the POST, but a 400 must still be a recorded failure rather than the
         * silence {@code index.js:23-25} makes of it.
         */
        @Test
        @DisplayName("a 400 is a refusal that carries its status for the output row")
        void a_400_is_a_refusal_that_carries_its_status_for_the_output_row() {
            answering(400);

            assertThatThrownBy(() -> gateway().post(BODY, CallerIdentity.SYSTEM, farDeadline()))
                    .isInstanceOf(SubmissionFailedException.class)
                    .asInstanceOf(InstanceOfAssertFactories.type(SubmissionFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.reason()).isEqualTo(ReasonCode.SUBMISSION_REJECTED);
                        assertThat(failure.responseCode()).isEqualTo(OptionalInt.of(400));
                    });
        }

        @ParameterizedTest(name = "{0} is not retried")
        @ValueSource(ints = {400, 401, 403, 404, 422})
        @DisplayName("a refusal is attempted once and handed back non-transient")
        void a_refusal_is_attempted_once(final int status) {
            answering(status);

            assertThatThrownBy(() -> gateway().post(BODY, CallerIdentity.SYSTEM, farDeadline()))
                    .isInstanceOf(SubmissionFailedException.class)
                    .extracting(failure -> ((SubmissionFailedException) failure).classification())
                    .isEqualTo(FailureClassification.NON_TRANSIENT);

            assertThat(progression.getAllServeEvents()).hasSize(1);
            assertThat(pause.waits).isEmpty();
        }

        @Test
        @DisplayName("a refusal carries a bounded code and none of progression's own words")
        void a_refusal_carries_a_bounded_code_and_none_of_progressions_words() {
            progression.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(422)
                    .withBody("{\"error\":\"defendant " + DEFENDANT_MARKER + " is not known\"}")));

            try (CapturedLog log = CapturedLog.capturing(ProgressionCommandGateway.class)) {
                assertThatThrownBy(() -> gateway().post(BODY, CallerIdentity.SYSTEM, farDeadline()))
                        .isInstanceOf(SubmissionFailedException.class)
                        .hasMessage(ReasonCode.SUBMISSION_REJECTED.code())
                        .hasMessageNotContaining(DEFENDANT_MARKER);

                assertThat(log.renderings())
                        .as("every defendant on this register is a child")
                        .noneMatch(line -> line.contains(DEFENDANT_MARKER));
            }
        }
    }

    @Nested
    @DisplayName("outcomes worth another attempt — N38, N40, N41, N45")
    class Retried {

        /**
         * The C3 gap itself. {@code AxiosRetryWrapper.js:34} abandons on
         * {@code error.response && status <= 429}, which makes 429 and 408 — the two statuses that
         * most plainly mean "ask me again" — the least-retried failures the legacy has.
         */
        @ParameterizedTest(name = "{0} is retried")
        @ValueSource(ints = {408, 429})
        @DisplayName("four_two_nine_and_four_oh_eight_are_retryable")
        void four_two_nine_and_four_oh_eight_are_retryable(final int status) {
            answering(status);

            assertThatThrownBy(() -> gateway().post(BODY, CallerIdentity.SYSTEM, farDeadline()))
                    .isInstanceOf(SubmissionFailedException.class)
                    .extracting(failure -> ((SubmissionFailedException) failure).classification())
                    .isEqualTo(FailureClassification.TRANSIENT);

            assertThat(progression.getAllServeEvents()).hasSize(MAX_ATTEMPTS);
        }

        @ParameterizedTest(name = "{0} is retried and the next attempt can succeed")
        @ValueSource(ints = {500, 502, 503})
        @DisplayName("a server error is retried and the next attempt can succeed")
        void a_server_error_is_retried_and_the_next_attempt_can_succeed(final int status) {
            answeringThenAccepting(status, null);

            assertThat(gateway().post(BODY, CallerIdentity.SYSTEM, farDeadline())).isEqualTo(ACCEPTED);
            assertThat(progression.getAllServeEvents()).hasSize(2);
            assertThat(pause.waits).containsExactly(INITIAL_BACKOFF);
        }

        /** The legacy waits a fixed second, every time, however unwell progression is. */
        @Test
        @DisplayName("the wait between attempts grows rather than hammering progression")
        void the_wait_between_attempts_grows_rather_than_hammering_progression() {
            answering(503);

            assertThatThrownBy(() -> gateway().post(BODY, CallerIdentity.SYSTEM, farDeadline()))
                    .isInstanceOf(SubmissionFailedException.class);

            assertThat(pause.waits).containsExactly(
                    INITIAL_BACKOFF, INITIAL_BACKOFF.multipliedBy(2), INITIAL_BACKOFF.multipliedBy(4));
        }

        @Test
        @DisplayName("a dropped connection is retried because the outcome is unknown")
        void a_dropped_connection_is_retried_because_the_outcome_is_unknown() {
            progression.stubFor(post(urlEqualTo(PATH)).inScenario("drops")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                    .willSetStateTo("up"));
            progression.stubFor(post(urlEqualTo(PATH)).inScenario("drops")
                    .whenScenarioStateIs("up")
                    .willReturn(aResponse().withStatus(ACCEPTED)));

            assertThat(gateway().post(BODY, CallerIdentity.SYSTEM, farDeadline())).isEqualTo(ACCEPTED);
            assertThat(pause.waits).containsExactly(INITIAL_BACKOFF);
        }

        @Test
        @DisplayName("a read that timed out is retried")
        void a_read_that_timed_out_is_retried() {
            progression.stubFor(post(urlEqualTo(PATH)).inScenario("slow")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(ACCEPTED).withFixedDelay(2000))
                    .willSetStateTo("up"));
            progression.stubFor(post(urlEqualTo(PATH)).inScenario("slow")
                    .whenScenarioStateIs("up")
                    .willReturn(aResponse().withStatus(ACCEPTED)));

            final int status = gateway(MAX_ATTEMPTS, Map.of(), Duration.ofMillis(250), pause)
                    .post(BODY, CallerIdentity.SYSTEM, farDeadline());

            assertThat(status).isEqualTo(ACCEPTED);
            assertThat(pause.waits).containsExactly(INITIAL_BACKOFF);
        }

        /**
         * N45, the acknowledged divergence. A POST whose answer never arrived may have been applied,
         * so retrying it can leave progression with two {@code court_register_request} rows for one
         * hearing — absorbed for generation by its {@code max(register_time) per hearing_id} sweep,
         * exactly as a re-share is. Not retrying it can lose the hearing, which nothing absorbs and
         * nobody sees. The trade is made in that direction deliberately, and the duplicate is
         * asserted here rather than hoped away.
         */
        @Test
        @DisplayName("an ambiguous outcome is retried, and the possible duplicate is acknowledged")
        void an_ambiguous_outcome_is_retried_and_the_duplicate_is_acknowledged() {
            progression.stubFor(post(urlEqualTo(PATH)).inScenario("ambiguous")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE))
                    .willSetStateTo("answering"));
            progression.stubFor(post(urlEqualTo(PATH)).inScenario("ambiguous")
                    .whenScenarioStateIs("answering")
                    .willReturn(aResponse().withStatus(ACCEPTED)));

            assertThat(gateway().post(BODY, CallerIdentity.SYSTEM, farDeadline())).isEqualTo(ACCEPTED);

            assertThat(progression.findAll(postRequestedFor(urlEqualTo(PATH))))
                    .as("progression received the command twice; the sweep absorbs the duplicate")
                    .hasSize(2);
        }

        /**
         * A transport failure that is classified and retried must still leave behind what it was.
         *
         * <p>Connect refused, read timed out and connection reset are three different
         * investigations — a wrong host, a slow progression, a mesh dropping the route — and the
         * exception is the only place the difference exists. The bounded reason code the pipeline
         * settles on is deliberately incapable of carrying it, so if this line does not, nothing
         * does. It is safe to keep: a transport exception carries the endpoint and the socket error
         * and never a register body.
         */
        @Test
        @DisplayName("a transport failure is recorded with its cause and not only its type")
        void a_transport_failure_is_recorded_with_its_cause_and_not_only_its_type() {
            progression.stubFor(post(urlEqualTo(PATH))
                    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            try (CapturedLog log = CapturedLog.capturing(ProgressionCommandGateway.class)) {
                assertThatThrownBy(() -> gateway().post(BODY, CallerIdentity.SYSTEM, farDeadline()))
                        .isInstanceOf(SubmissionFailedException.class);

                assertThat(log.renderings())
                        .as("the classification is kept, and so is what was classified")
                        .anyMatch(line -> line.contains("did not reach a verdict")
                                && line.contains("ResourceAccessException"));
            }
        }
    }

    @Nested
    @DisplayName("Retry-After — N39")
    class RetryAfter {

        /**
         * Delta-seconds only, and bounded by the same ceiling as the back-off.
         *
         * <p>RFC 9110 also permits an HTTP-date, and this client deliberately does not act on one:
         * honouring it would mean subtracting a remote clock's idea of now from this pod's, and a
         * server a few minutes ahead would park a run past the claim lease it holds. The form is
         * recognised before it is read, so an unusable header is classified rather than raised and
         * absorbed, and every unusable form falls back to the back-off — which is the same outcome
         * as no header at all.
         */
        @ParameterizedTest(name = "Retry-After: [{0}] waits {1}")
        @CsvSource({
            "2,                              PT2S",
            "3600,                           PT20S",
            "'Wed, 21 Oct 2026 07:28:00 GMT',PT0.5S",
            "when I say so,                  PT0.5S",
            "999999999999999999999,          PT0.5S",
            "'',                             PT0.5S",
        })
        @DisplayName("retry_after_is_bounded_and_delta_seconds_only")
        void retry_after_is_bounded_and_delta_seconds_only(
                final String header, final Duration expectedWait) {
            answeringThenAccepting(429, header);

            assertThat(gateway().post(BODY, CallerIdentity.SYSTEM, farDeadline())).isEqualTo(ACCEPTED);
            assertThat(pause.waits).containsExactly(expectedWait);
        }

        /**
         * <strong>Every retryable answer, not only a 429.</strong> A 503 carrying a
         * {@code Retry-After} is a service saying when it expects to be back — the header exists
         * because the server knows better than the client's schedule, and a reading that took it
         * only from a 429 would ignore precisely the service that told you. It is the same rule the
         * payload query and the reference-data read now apply, because all three read it out of one
         * shared policy.
         */
        @ParameterizedTest(name = "a Retry-After on a {0} is honoured")
        @ValueSource(ints = {408, 429, 500, 503})
        @DisplayName("retry_after_is_honoured_on_every_retryable_answer")
        void retry_after_is_honoured_on_every_retryable_answer(final int status) {
            answeringThenAccepting(status, "2");

            assertThat(gateway().post(BODY, CallerIdentity.SYSTEM, farDeadline()))
                    .isEqualTo(ACCEPTED);
            assertThat(pause.waits)
                    .as("the back-off would have been half a second")
                    .containsExactly(Duration.ofSeconds(2));
        }

        @Test
        @DisplayName("a Retry-After beyond the ceiling never outlives the run's claim")
        void a_retry_after_beyond_the_ceiling_is_capped() {
            answeringThenAccepting(429, "86400");

            gateway().post(BODY, CallerIdentity.SYSTEM, farDeadline());

            assertThat(pause.waits)
                    .as("a run holds its claim for a bounded lease")
                    .containsExactly(MAX_BACKOFF);
        }
    }

    @Nested
    @DisplayName("attempts that run out — N42")
    class Exhausted {

        /**
         * The transport's half of N42. The {@code FAILED} row and the {@code exhausted_message_id}
         * are written by the guard on the last permitted delivery ({@code DeliveryExhaustionIT});
         * what this class owes that outcome is an unresolved verdict handed back rather than a
         * register written off, and the last status answered so the output row can say what it was.
         */
        @Test
        @DisplayName("hands the delivery back transient when the attempts run out")
        void exhaustion_hands_back_the_transient_the_guard_turns_into_failed() {
            answering(500);

            assertThatThrownBy(() -> gateway().post(BODY, CallerIdentity.SYSTEM, farDeadline()))
                    .isInstanceOf(SubmissionFailedException.class)
                    .asInstanceOf(InstanceOfAssertFactories.type(SubmissionFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.TRANSIENT);
                        assertThat(failure.reason()).isEqualTo(ReasonCode.SUBMISSION_TRANSIENT);
                        assertThat(failure.responseCode()).isEqualTo(OptionalInt.of(500));
                    });

            assertThat(progression.getAllServeEvents()).hasSize(MAX_ATTEMPTS);
        }

        @Test
        @DisplayName("records no status where nothing ever answered")
        void exhaustion_with_no_answer_records_no_status() {
            progression.stubFor(post(urlEqualTo(PATH))
                    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            assertThatThrownBy(() -> gateway().post(BODY, CallerIdentity.SYSTEM, farDeadline()))
                    .isInstanceOf(SubmissionFailedException.class)
                    .asInstanceOf(InstanceOfAssertFactories.type(SubmissionFailedException.class))
                    .satisfies(failure -> assertThat(failure.responseCode())
                            .as("an invented status would say an attempt was answered")
                            .isEmpty());
        }

        @Test
        @DisplayName("says so loudly before handing the delivery back")
        void exhaustion_is_reported_before_the_delivery_is_handed_back() {
            answering(503);

            try (CapturedLog log = CapturedLog.capturing(ProgressionCommandGateway.class)) {
                assertThatThrownBy(() -> gateway().post(BODY, CallerIdentity.SYSTEM, farDeadline()))
                        .isInstanceOf(SubmissionFailedException.class);

                assertThat(log.events())
                        .anyMatch(event -> "ERROR".equals(event.getLevel().toString())
                                && event.getFormattedMessage().contains("exhausted"));
            }
        }
    }

    @Nested
    @DisplayName("configuration and shutdown — N43")
    class Policy {

        /**
         * N43. {@code AxiosRetryWrapper.js:19,34} reads its environment once, at module load, so a
         * changed setting needs a redeploy of the function app to take effect. Here the policy is
         * state of the gateway, and two gateways built from two settings behave differently in the
         * same JVM — which module-load state could not do.
         */
        @Test
        @DisplayName("the retry policy is the gateway's state, not module-load state")
        void the_retry_policy_is_read_per_gateway_and_not_once_at_class_load() {
            answering(500);

            assertThatThrownBy(() -> gateway(1, Map.of(), Duration.ofSeconds(5), pause)
                    .post(BODY, CallerIdentity.SYSTEM, farDeadline()))
                    .isInstanceOf(SubmissionFailedException.class);
            final int afterFirst = progression.getAllServeEvents().size();

            assertThatThrownBy(() -> gateway(3, Map.of(), Duration.ofSeconds(5), pause)
                    .post(BODY, CallerIdentity.SYSTEM, farDeadline()))
                    .isInstanceOf(SubmissionFailedException.class);

            assertThat(afterFirst).isEqualTo(1);
            assertThat(progression.getAllServeEvents()).hasSize(4);
        }

        @Test
        @DisplayName("an interrupted wait gives up transient with the interrupt restored")
        void an_interrupted_wait_gives_up_transient_with_the_interrupt_restored() {
            answering(500);
            final ProgressionCommandGateway interruptible =
                    gateway(MAX_ATTEMPTS, Map.of(), Duration.ofSeconds(5), duration -> {
                        throw new InterruptedException("shutting down");
                    });

            try {
                assertThatThrownBy(() -> interruptible.post(BODY, CallerIdentity.SYSTEM, farDeadline()))
                        .isInstanceOf(SubmissionFailedException.class)
                        .extracting(failure ->
                                ((SubmissionFailedException) failure).classification())
                        .isEqualTo(FailureClassification.TRANSIENT);

                assertThat(Thread.currentThread().isInterrupted())
                        .as("an interrupt is a shutdown, and swallowing it would hide one")
                        .isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }

    /**
     * The run's budget, enforced by the transport rather than only around it.
     *
     * <p>The pipeline reads what is left of the run before it hands a register over, and that check
     * bounds the instant the POST <em>starts</em> and nothing after it. What happens after it is this
     * class: up to {@code max-attempts} attempts, each able to spend a connect and a read timeout,
     * with a doubling wait — or a server-supplied {@code Retry-After} — between them. A policy that
     * kept waiting past the instant the run promised to stop by would be posting under a claim
     * another delivery may already hold, and {@code add-court-register} <em>appends</em>: the second
     * runner's POST is a second register for the hearing, which is the one outcome the budget exists
     * to prevent.
     *
     * <p>So the deadline travels in with the command. It is read before every attempt and every wait
     * is measured against it, whether the wait came from the back-off or from progression's own
     * header. An overrun is TRANSIENT under {@code PROCESSING_DEADLINE_EXCEEDED} — the run did not
     * fail, it ran out of the time its claim guarantees it — and the redelivery gets a whole fresh
     * budget with nothing sent twice.
     *
     * <p><strong>And the attempt is measured by what it can cost, not by whether it may begin.</strong>
     * "The deadline has not passed yet" is not the same question as "this attempt fits": an attempt
     * that hangs on its connect and then on its read spends the whole of both timeouts, so a run
     * that started one with a millisecond left would be posting a register long after the claim
     * behind it became reclaimable. The reservation is the shared policy's
     * ({@code RetryPolicy.attemptFitsBefore}), stated once for the three clients rather than in the
     * one that holds a deadline today.
     */
    @Nested
    @DisplayName("a budget that runs out before the attempts do")
    class RunBudget {

        /** Long enough to answer, far too short to wait out a back-off. */
        private static final Duration ALMOST_GONE = Duration.ofMillis(100);

        /**
         * A declared attempt cost small enough that the attempt is never the thing that does not
         * fit, so a case about a wait is about the wait.
         */
        private static final Duration CHEAP_ATTEMPT = Duration.ofMillis(20);

        /** What is left of a run when the reservation case reaches the gateway. */
        private static final Duration REMAINING = Duration.ofSeconds(1);

        /** Longer than that, so an attempt that is started answers after the deadline. */
        private static final int SLOWER_THAN_THE_BUDGET = 1_500;

        /** The gateway a case about waits uses: real timeouts, a declared attempt cost of nothing. */
        private ProgressionCommandGateway budgeted() {
            return gateway(MAX_ATTEMPTS, Map.of(), READ_TIMEOUT, pause, CHEAP_ATTEMPT);
        }

        @Test
        @DisplayName("starts no attempt at all once the run's budget is already spent")
        void no_attempt_is_started_once_the_budget_is_already_spent() {
            answering(ACCEPTED);

            assertThatThrownBy(() -> gateway().post(BODY, CallerIdentity.SYSTEM, NOW))
                    .isInstanceOf(SubmissionFailedException.class)
                    .asInstanceOf(InstanceOfAssertFactories.type(SubmissionFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.TRANSIENT);
                        assertThat(failure.reason())
                                .isEqualTo(ReasonCode.PROCESSING_DEADLINE_EXCEEDED);
                    });

            assertThat(progression.getAllServeEvents())
                    .as("a POST made past the deadline is a POST made under a claim that may "
                            + "already have been reclaimed")
                    .isEmpty();
        }

        /**
         * The attempt's own duration, reserved before it is started.
         *
         * <p>The budget is not spent — a second of it is left — so the "has the deadline passed"
         * question answers yes, go ahead. The question that matters answers no: this gateway's
         * attempt can cost its connect timeout and then its read timeout, which is seven seconds,
         * and seven seconds do not fit in one. Progression here takes a second and a half to answer,
         * which is inside the read timeout and outside the run's remaining budget — the everyday
         * shape of a busy downstream — so an attempt that <em>is</em> started returns its 202 with
         * the claim behind it already reclaimable, and {@code add-court-register} appends: a second
         * runner's POST is a second register for the same hearing.
         *
         * <p>So the assertion is about the socket rather than about the exception. Nothing may reach
         * progression at all.
         */
        @Test
        @DisplayName("starts no attempt whose own worst case would end after the deadline")
        void no_attempt_is_started_when_the_attempt_itself_cannot_fit_in_what_is_left() {
            progression.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                    .withStatus(ACCEPTED)
                    .withFixedDelay(SLOWER_THAN_THE_BUDGET)));

            final Throwable refused = catchThrowable(
                    () -> gateway().post(BODY, CallerIdentity.SYSTEM, NOW.plus(REMAINING)));

            assertThat(progression.getAllServeEvents())
                    .as("an attempt that can cost seven seconds was not started with one second of "
                            + "the claim left")
                    .isEmpty();
            assertThat(refused)
                    .isInstanceOf(SubmissionFailedException.class)
                    .asInstanceOf(InstanceOfAssertFactories.type(SubmissionFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .as("the run did not fail; it ran out of the time its claim "
                                        + "guarantees it, and the redelivery gets a fresh budget")
                                .isEqualTo(FailureClassification.TRANSIENT);
                        assertThat(failure.reason())
                                .isEqualTo(ReasonCode.PROCESSING_DEADLINE_EXCEEDED);
                    });
            assertThat(pause.waits).isEmpty();
        }

        /**
         * The ordinary back-off path. One attempt fits inside what is left; the 500ms wait after it
         * does not, so the wait is refused rather than taken and the delivery goes back with a
         * budget nothing has overspent.
         */
        @Test
        @DisplayName("refuses a back-off that would be taken past the deadline")
        void a_back_off_that_would_cross_the_deadline_is_refused_rather_than_taken() {
            answering(503);

            assertThatThrownBy(() -> budgeted()
                    .post(BODY, CallerIdentity.SYSTEM, NOW.plus(ALMOST_GONE)))
                    .isInstanceOf(SubmissionFailedException.class)
                    .asInstanceOf(InstanceOfAssertFactories.type(SubmissionFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.TRANSIENT);
                        assertThat(failure.reason())
                                .isEqualTo(ReasonCode.PROCESSING_DEADLINE_EXCEEDED);
                        assertThat(failure.responseCode())
                                .as("the row still records what progression last answered")
                                .isEqualTo(OptionalInt.of(503));
                    });

            assertThat(pause.waits)
                    .as("a wait that ends after the deadline is never begun")
                    .isEmpty();
            assertThat(progression.getAllServeEvents())
                    .as("the attempt that fitted was made; the ones behind the wait were not")
                    .hasSize(1);
        }

        /**
         * The other wait, and the one a remote service chooses. A {@code Retry-After} is bounded by
         * {@code max-backoff} already; it is bounded by the run's own budget too, because a run
         * holds a claim for a finite lease and progression does not know when that lease ends.
         */
        @Test
        @DisplayName("refuses a Retry-After that would be waited out past the deadline")
        void a_retry_after_that_would_cross_the_deadline_is_refused_rather_than_taken() {
            answeringThenAccepting(429, "2");

            assertThatThrownBy(() -> budgeted()
                    .post(BODY, CallerIdentity.SYSTEM, NOW.plus(REMAINING)))
                    .isInstanceOf(SubmissionFailedException.class)
                    .asInstanceOf(InstanceOfAssertFactories.type(SubmissionFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.TRANSIENT);
                        assertThat(failure.reason())
                                .isEqualTo(ReasonCode.PROCESSING_DEADLINE_EXCEEDED);
                    });

            assertThat(pause.waits)
                    .as("progression asked for two seconds the run does not have")
                    .isEmpty();
            assertThat(progression.getAllServeEvents()).hasSize(1);
        }

        /**
         * The check before the attempt is a second gate, not a restatement of the check before the
         * wait, and this is the case that tells them apart. A wait is permitted because it was going
         * to finish inside the budget — but {@link Thread#sleep(java.time.Duration)} guarantees a
         * minimum and not a maximum, and time is spent by the attempt itself as well. So the budget
         * is read again on the way in, and an attempt that no longer fits is not made.
         */
        @Test
        @DisplayName("starts no further attempt once a permitted wait has overrun the budget")
        void no_further_attempt_is_started_once_a_permitted_wait_has_overrun_the_budget() {
            answering(503);
            final ProgressionCommandGateway overrunning = gateway(MAX_ATTEMPTS, Map.of(),
                    READ_TIMEOUT, duration -> {
                        pause.pause(duration);
                        clock.advance(duration.multipliedBy(2));
                    }, CHEAP_ATTEMPT);

            assertThatThrownBy(() -> overrunning
                    .post(BODY, CallerIdentity.SYSTEM, NOW.plus(Duration.ofMillis(600))))
                    .isInstanceOf(SubmissionFailedException.class)
                    .extracting(failure -> ((SubmissionFailedException) failure).reason())
                    .isEqualTo(ReasonCode.PROCESSING_DEADLINE_EXCEEDED);

            assertThat(pause.waits)
                    .as("the wait was permitted: it was going to finish inside the budget")
                    .containsExactly(INITIAL_BACKOFF);
            assertThat(progression.getAllServeEvents())
                    .as("one attempt, one wait that overran, and then no second attempt")
                    .hasSize(1);
        }

        @Test
        @DisplayName("says the budget ran out rather than reporting exhausted attempts")
        void an_overrun_is_reported_as_an_overrun_and_not_as_exhaustion() {
            answering(503);

            try (CapturedLog log = CapturedLog.capturing(ProgressionCommandGateway.class)) {
                assertThatThrownBy(() -> budgeted()
                        .post(BODY, CallerIdentity.SYSTEM, NOW.plus(ALMOST_GONE)))
                        .isInstanceOf(SubmissionFailedException.class);

                assertThat(log.renderings())
                        .as("a run that ran out of time is a capacity signal, not a downstream one")
                        .anyMatch(line -> line.contains(
                                ReasonCode.PROCESSING_DEADLINE_EXCEEDED.code()))
                        .noneMatch(line -> line.contains("exhausted"));
            }
        }

        /** The budget bounds a run; it does not shorten one that finishes inside it. */
        @Test
        @DisplayName("leaves a command that fits inside the budget alone")
        void a_command_that_fits_inside_the_budget_is_posted_normally() {
            answeringThenAccepting(503, null);

            assertThat(gateway().post(BODY, CallerIdentity.SYSTEM, NOW.plus(Duration.ofMinutes(4))))
                    .isEqualTo(ACCEPTED);
            assertThat(pause.waits).containsExactly(INITIAL_BACKOFF);
        }
    }

    /** Records what the gateway would have waited, so the suite proves the policy without living it. */
    private static final class RecordingPause implements RetryPause {

        private final List<Duration> waits = new ArrayList<>();

        @Override
        public void pause(final Duration duration) {
            waits.add(duration);
        }
    }
}
