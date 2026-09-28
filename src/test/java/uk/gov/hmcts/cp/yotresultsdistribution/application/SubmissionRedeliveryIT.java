package uk.gov.hmcts.cp.yotresultsdistribution.application;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.progression.ProgressionCommandGateway;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.progression.ProgressionRegisterSubmissionClient;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ProcessedOutputClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestFingerprint;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.SubmissionFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationAnomaly;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.ProcessedOutputRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;

/**
 * The idempotency gate, proven end to end across the two halves that actually enforce it.
 *
 * <p>The unit suites hold each half to its own contract: {@code ProcessedOutputRepositoryIT} proves
 * the statements against a real Postgres, and {@code ProgressionRegisterSubmissionClientTest} proves
 * the adapter's ordering against a mocked repository and a mocked transport. Neither can fail if the
 * two agree with each other and disagree with the database — a mock returns whatever it was told to,
 * so "a register already POSTED is skipped" is asserted there against a stub of the very decision
 * under test. Here the repository is real, the store is real, and the POST reaches a socket.
 *
 * <p>The claim being made is the one the design rules make: <strong>a redelivery must not produce a
 * second submission</strong>. {@code add-court-register} appends an event and a
 * {@code court_register_request} row per POST, so a replay that could not tell must either risk a
 * duplicate register or risk losing the hearing's register altogether.
 *
 * <p>The three states of an output row are three different answers to a replay, and all three are
 * here: <strong>POSTED</strong> is terminal and the POST is skipped; <strong>FAILED</strong>, left
 * by a refusal, is re-claimed and re-sent; and <strong>PENDING</strong>, left by a runner that died
 * between claiming the row and learning the outcome, is re-claimed and re-sent too — that row is
 * evidence that a POST may have been made, and this service prefers a duplicate progression absorbs
 * to a loss nobody sees.
 *
 * <p>One attempt per POST here, because what is under test is what the log permits a second delivery
 * to do rather than how patiently the transport retries; the retry policy is settled against a
 * socket in {@code ProgressionCommandGatewayTest}.
 */
@DisplayName("submission under redelivery")
class SubmissionRedeliveryIT {

    private static final String PATH =
            "/progression-command-api/command/api/rest/progression/court-register";
    private static final String SYSTEM_USER_ID = "b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234";
    private static final Duration LEASE = Duration.ofMinutes(5);

    private static final String COURT_CENTRE_ID = "853b1ff8-fc2a-44d1-a621-0cd16419f54a";
    private static final String OU_CODE = "B01LY00";
    private static final LocalDate REGISTER_DAY = LocalDate.of(2026, 8, 20);

    private static final int ACCEPTED = 202;
    private static final int REFUSED = 400;

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    private WireMockServer progression;

    @BeforeAll
    static void migrate() {
        PostgresTestSupport.applyFlyway();
    }

    @BeforeEach
    void startProgression() {
        progression = new WireMockServer(wireMockConfig().dynamicPort());
        progression.start();
    }

    @AfterEach
    void stopProgression() {
        progression.stop();
    }

    /**
     * A submission client wired as the live configuration wires it, with one attempt per POST.
     *
     * <p>A fresh instance per delivery, and a fresh repository behind it, because a redelivery is
     * not the same object calling twice — it is another runner, possibly another pod, and an
     * instance that remembered anything in a field would prove the wrong thing.
     */
    private RegisterSubmissionClient delivery() {
        final SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        final ProgressionCommandGateway gateway = new ProgressionCommandGateway(
                RestClient.builder()
                        .baseUrl(progression.baseUrl())
                        .requestFactory(requestFactory)
                        .build(),
                SYSTEM_USER_ID,
                Map.of(),
                new RetryPolicy(1, Duration.ofMillis(1), Duration.ofSeconds(1),
                        Duration.ofSeconds(7)),
                duration -> {
                    // Nothing waits here; a single attempt never reaches a wait at all.
                },
                Clock.systemUTC());
        return new ProgressionRegisterSubmissionClient(
                new ProcessedOutputRepository(ProcessedLogTestSupport.jdbcClient()),
                gateway,
                MAPPER);
    }

    @Test
    @DisplayName("a replay of a register that has gone does not post it a second time")
    void a_replay_of_a_posted_register_does_not_post_it_again() {
        final RunClaim claim = seededRequest();
        progression.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(ACCEPTED)));

        delivery().submit(submission(claim));
        final SubmissionReceipt replay = delivery().submit(submission(claim));

        assertThat(posts())
                .as("add-court-register appends; a second POST is a second register for the hearing")
                .isEqualTo(1);
        assertThat(replay.sentByThisDelivery())
                .as("the run still completes submitted, but it did not send this one")
                .isFalse();
        assertThat(status(claim)).isEqualTo("POSTED");
    }

    @Test
    @DisplayName("a replay after a refusal re-attempts the POST")
    void a_replay_after_a_refusal_re_attempts_the_post() {
        final RunClaim claim = seededRequest();
        progression.stubFor(post(urlEqualTo(PATH)).inScenario("refuses then accepts")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(REFUSED))
                .willSetStateTo("accepting"));
        progression.stubFor(post(urlEqualTo(PATH)).inScenario("refuses then accepts")
                .whenScenarioStateIs("accepting")
                .willReturn(aResponse().withStatus(ACCEPTED)));

        assertThatThrownBy(() -> delivery().submit(submission(claim)))
                .isInstanceOf(SubmissionFailedException.class);
        assertThat(status(claim))
                .as("a refusal leaves FAILED behind, not a row that looks still in flight")
                .isEqualTo("FAILED");
        assertThat(responseCode(claim)).isEqualTo(REFUSED);

        delivery().submit(submission(claim));

        assertThat(posts()).isEqualTo(2);
        assertThat(status(claim)).isEqualTo("POSTED");
        assertThat(responseCode(claim)).isEqualTo(ACCEPTED);
    }

    /**
     * The crash window. A runner that died between claiming the row and learning the outcome leaves
     * PENDING behind, and the register may or may not have gone. The next delivery re-sends it: a
     * duplicate is absorbed by progression's {@code max(register_time) per hearing_id} sweep, and a
     * loss is absorbed by nothing.
     */
    @Test
    @DisplayName("a replay of a row left PENDING by a crash re-attempts the POST")
    void a_replay_of_a_row_left_pending_re_attempts_the_post() {
        final RunClaim claim = seededRequest();
        new ProcessedOutputRepository(ProcessedLogTestSupport.jdbcClient())
                .claimPending(claim, outputClaim());
        progression.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(ACCEPTED)));

        delivery().submit(submission(claim));

        assertThat(posts()).isEqualTo(1);
        assertThat(status(claim)).isEqualTo("POSTED");
    }

    /**
     * What the row says before anything has answered, and what it still says afterwards. The digest
     * and the counts describe the body that was attempted; they are the reconciliation evidence, and
     * a failure is exactly when they are worth having.
     */
    @Test
    @DisplayName("the row written before the POST survives the failure that follows it")
    void the_row_written_before_the_post_survives_the_failure() {
        final RunClaim claim = seededRequest();
        progression.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(REFUSED)));

        assertThatThrownBy(() -> delivery().submit(submission(claim)))
                .isInstanceOf(SubmissionFailedException.class);

        assertThat(column(claim, "request_digest", String.class)).isNotNull();
        assertThat(column(claim, "anomaly_summary", String.class))
                .isEqualTo("letter-delivery-dropped:2");
        assertThat(column(claim, "court_centre_ou_code", String.class)).isEqualTo(OU_CODE);
        assertThat(column(claim, "register_date", LocalDate.class)).isEqualTo(REGISTER_DAY);
    }

    /**
     * The fence, end to end. A runner whose claim was reclaimed while it worked has no request to
     * speak for: its claim statement selects nothing, so it never posts — which is what stops one
     * hearing acquiring two registers when a lease lapses under a slow run.
     */
    @Test
    @DisplayName("a runner whose claim was reclaimed cannot post at all")
    void a_superseded_runner_does_not_post() {
        final RunClaim superseded = seededRequest();
        reclaimBy(superseded, "runner-2");
        progression.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(ACCEPTED)));

        final SubmissionReceipt receipt = delivery().submit(submission(superseded));

        assertThat(posts())
                .as("the claim it holds is no longer the one the request carries")
                .isZero();
        assertThat(receipt.sentByThisDelivery()).isFalse();
    }

    /** Seeds the request row the output rows' foreign key requires, holding the claim. */
    private static RunClaim seededRequest() {
        final DistributionCommand command = ProcessedLogTestSupport.command();
        final RunClaim claim = new RunClaim(
                command.source(), command.requestId(), "runner-1", UUID.randomUUID(), "msg-1");
        ProcessedLogTestSupport.repository(LEASE)
                .insertNew(command, RequestFingerprint.of(command), claim);
        return claim;
    }

    /** Moves the request's claim to another runner, as a reclaim after a lapsed lease does. */
    private static void reclaimBy(final RunClaim claim, final String owner) {
        ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        UPDATE processed_request
                           SET claim_owner = :owner, claim_token = :token
                         WHERE source = :source AND request_id = :requestId
                        """)
                .param("owner", owner)
                .param("token", UUID.randomUUID())
                .param("source", claim.source())
                .param("requestId", claim.requestId())
                .update();
    }

    private int posts() {
        return progression.findAll(postRequestedFor(urlEqualTo(PATH))).size();
    }

    private static String status(final RunClaim claim) {
        return column(claim, "status", String.class);
    }

    private static Integer responseCode(final RunClaim claim) {
        return column(claim, "response_code", Integer.class);
    }

    private static <T> T column(final RunClaim claim, final String name, final Class<T> type) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("SELECT " + name + " FROM processed_output "
                        + "WHERE source = :source AND request_id = :requestId")
                .param("source", claim.source())
                .param("requestId", claim.requestId())
                .query(type)
                .optional()
                .orElse(null);
    }

    private static RegisterSubmission submission(final RunClaim claim) {
        return new RegisterSubmission(claim, deadline(), document(), OU_CODE, REGISTER_DAY,
                new CallerIdentity(Optional.of(
                        UUID.fromString("6e2f0a1c-9d4b-4f38-8a52-1c7b3e5d9f04"))),
                Map.of(TransformationAnomaly.LETTER_DELIVERY_DROPPED, 2));
    }

    /**
     * A budget no case here can exhaust: what is under test is what the processed log permits a
     * second delivery to do, and a run that stopped itself on the clock would never reach it.
     */
    private static Instant deadline() {
        return Clock.systemUTC().instant().plus(Duration.ofMinutes(4));
    }

    private static ProcessedOutputClaim outputClaim() {
        return new ProcessedOutputClaim(
                UUID.randomUUID(),
                UUID.fromString(COURT_CENTRE_ID),
                OU_CODE,
                REGISTER_DAY,
                "court-register_2026-08-20_B01LY00_crashed.pdf",
                "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                Map.of());
    }

    private static CourtRegisterDocument document() {
        final CourtRegisterDefendant defendant = new CourtRegisterDefendant(
                "b2b3f5a1-6c9d-4e21-8a7f-3d5c1e9b0426", "SMITH, John", "2008-04-11",
                null, null, null, "MALE", "Not Applicable", null, null,
                List.of(), List.of(), List.of(), List.of());
        final CourtRegisterRecipient recipient = new CourtRegisterRecipient(
                "Youth Offending Team", "yot@example.gov.uk", null, "cr_standard");
        return new CourtRegisterDocument(
                "2026-08-20T09:00:00Z",
                "2026-08-19T00:00:00Z",
                "1828f356-f746-4f2d-932b-79ef2df95c80",
                COURT_CENTRE_ID,
                "court-register_2026-08-20_B01LY00_1828f356-f746-4f2d-932b-79ef2df95c80.pdf",
                null,
                null,
                List.of(recipient),
                List.of(defendant));
    }
}
