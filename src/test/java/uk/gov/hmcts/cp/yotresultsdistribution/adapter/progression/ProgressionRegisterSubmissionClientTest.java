package uk.gov.hmcts.cp.yotresultsdistribution.adapter.progression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterSubmission;
import uk.gov.hmcts.cp.yotresultsdistribution.application.SubmissionReceipt;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterAddress;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterCaseOrApplication;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterHearingVenue;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ProcessedOutputClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.SubmissionFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationAnomaly;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.ProcessedOutputRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;

/**
 * The order the submission leg does things in, which is the whole of its safety — the P1 twin,
 * repaired.
 *
 * <p>{@code ProcessOutboundCourtRegister.test.js} is one case and three defects. It stubs
 * {@code axios.post} with a promise that is constructed and never returned, so the mock resolves
 * {@code undefined} and the test observes no status at all; it asserts the {@code CJSCPPUID} header
 * as the literal {@code undefined}, because the fixture supplies no {@code cjscppuid}; and its
 * fixture still records a {@code .csv} file name the code has not produced since it became a PDF.
 * What ports from it is the wire contract — the path, the vendor media type, the identity header and
 * "the body is the aggregation" — and that half is settled against a socket in
 * {@link ProgressionCommandGatewayTest}. What is settled here is everything the legacy has no
 * concept of: the row that is written before the POST, and the outcome that is written after it.
 *
 * <p>{@code add-court-register} is not idempotent — every POST appends an event and a
 * {@code court_register_request} row. Three properties keep an at-least-once delivery safe to submit
 * from, and each is asserted rather than argued for in a comment.
 *
 * <ul>
 *   <li><strong>The row is claimed before the POST.</strong> A POST whose outcome is never learned —
 *       a timeout, a dropped connection — must still leave evidence that it was attempted, what was
 *       in it, and what the register was assembled without. Writing the row afterwards would lose
 *       exactly the case the evidence is for.</li>
 *   <li><strong>A register already POSTED is skipped.</strong> A replay of a request whose register
 *       went must not send a second one, and the claim statement is what decides that — in the
 *       database, in one statement, because two deliveries can be in flight at once.</li>
 *   <li><strong>A failure is recorded before it is rethrown</strong>, carrying the status
 *       progression answered. This is defect fix C1 made durable: the legacy catches the POST's
 *       errors, records nothing, and reports the run a success.</li>
 * </ul>
 *
 * <p>The digest is asserted against the bytes that actually went out, not against a re-serialisation
 * of the document. It exists for reconciliation and replay diffing, and a digest of something other
 * than what was sent is worse than none.
 *
 * <p>The transport is mocked here on purpose, and so is the repository. What the wire looks like is
 * settled against a real socket next door; what the statements do is settled against a real Postgres
 * in {@code ProcessedOutputRepositoryIT}; what is settled here is <em>ordering</em>, which neither of
 * those can show. That the three agree with each other and with the database is what
 * {@code SubmissionRedeliveryIT} exists for.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> row C1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Progression register submission client")
class ProgressionRegisterSubmissionClientTest {

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    private static final String SOURCE = "RESULTS";
    private static final String COURT_CENTRE_ID = "853b1ff8-fc2a-44d1-a621-0cd16419f54a";
    private static final String OU_CODE = "B01LY00";
    private static final LocalDate REGISTER_DAY = LocalDate.of(2020, 6, 1);
    private static final String HEARING_ID = "1828f356-f746-4f2d-932b-79ef2df95c80";
    private static final String FILE_NAME =
            "court-register_2020-06-01_B01LY00_" + HEARING_ID + ".pdf";

    /** A child's name, which is what a register carries and what no log line may. */
    private static final String DEFENDANT_NAME = "SMITH, John";
    private static final String DEFENDANT_ADDRESS_LINE = "1 High Street";

    /** The instant the run promised to have stopped by; every case here fits comfortably inside it. */
    private static final Instant DEADLINE =
            Instant.parse("2020-06-01T10:00:00Z").plus(Duration.ofMinutes(4));

    private static final int ACCEPTED = 202;
    private static final int REFUSED = 400;

    /** The run's caller, resolved once by the pipeline and passed through unchanged. */
    private static final CallerIdentity CALLER = new CallerIdentity(
            Optional.of(UUID.fromString("6e2f0a1c-9d4b-4f38-8a52-1c7b3e5d9f04")));

    @Mock
    private ProcessedOutputRepository outputs;

    @Mock
    private ProgressionCommandGateway gateway;

    private final RunClaim claim = new RunClaim(
            SOURCE, UUID.fromString("9f1b8e2a-5c34-4a7d-9b1e-2f6a0d3c5e71"),
            "runner-1", UUID.randomUUID(), "msg-1");

    private ProgressionRegisterSubmissionClient client() {
        return new ProgressionRegisterSubmissionClient(outputs, gateway, MAPPER);
    }

    private RegisterSubmission submission() {
        return submission(Map.of());
    }

    private RegisterSubmission submission(final Map<TransformationAnomaly, Integer> anomalies) {
        return new RegisterSubmission(
                claim, DEADLINE, document(), OU_CODE, REGISTER_DAY, CALLER, anomalies);
    }

    /**
     * A submission carrying the register named, and everything else as the ordinary case has it.
     *
     * @param document the register the run assembled
     * @return the submission
     */
    private RegisterSubmission submissionOf(final CourtRegisterDocument document) {
        return new RegisterSubmission(
                claim, DEADLINE, document, OU_CODE, REGISTER_DAY, CALLER, Map.of());
    }

    private void claimGranted() {
        when(outputs.claimPending(eq(claim), any(ProcessedOutputClaim.class))).thenReturn(true);
    }

    @Nested
    @DisplayName("the happy path, in order")
    class Accepted {

        @Test
        @DisplayName("claims the row before the POST and marks it posted after")
        void the_row_is_claimed_before_the_post_and_marked_posted_after() {
            claimGranted();
            when(gateway.post(any(byte[].class), any(CallerIdentity.class), any(Instant.class))).thenReturn(ACCEPTED);
            when(outputs.recordPosted(claim, ACCEPTED)).thenReturn(true);

            client().submit(submission());

            final InOrder order = inOrder(outputs, gateway);
            order.verify(outputs).claimPending(eq(claim), any(ProcessedOutputClaim.class));
            order.verify(gateway).post(any(byte[].class), any(CallerIdentity.class), any(Instant.class));
            order.verify(outputs).recordPosted(claim, ACCEPTED);
            order.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("answers a receipt carrying the status, and saying this delivery sent it")
        void the_receipt_says_this_delivery_sent_it() {
            claimGranted();
            when(gateway.post(any(byte[].class), any(CallerIdentity.class), any(Instant.class))).thenReturn(ACCEPTED);
            when(outputs.recordPosted(claim, ACCEPTED)).thenReturn(true);

            final SubmissionReceipt receipt = client().submit(submission());

            assertThat(receipt).isEqualTo(new SubmissionReceipt(ACCEPTED, true));
        }

        @Test
        @DisplayName("posts the bytes this service produced from the document")
        void the_bytes_posted_are_the_document_this_service_produced() {
            claimGranted();
            when(gateway.post(any(byte[].class), any(CallerIdentity.class), any(Instant.class))).thenReturn(ACCEPTED);
            when(outputs.recordPosted(claim, ACCEPTED)).thenReturn(true);
            final ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);

            client().submit(submission());

            verify(gateway).post(sent.capture(), any(CallerIdentity.class), any(Instant.class));
            assertThat(new String(sent.getValue(), StandardCharsets.UTF_8))
                    .isEqualTo(MAPPER.writeValueAsString(document()));
        }

        /**
         * The adapter resolves no identity of its own. The run decided who it is made as, once, and
         * this leg passes it through — which is what {@code index.js:21} does with
         * {@code this.input.cjscppuid}, and what its one test asserts as {@code undefined}.
         */
        @Test
        @DisplayName("posts as the caller the run resolved")
        void the_caller_posted_as_is_the_one_the_run_resolved() {
            claimGranted();
            when(gateway.post(any(byte[].class), any(CallerIdentity.class), any(Instant.class))).thenReturn(ACCEPTED);
            when(outputs.recordPosted(claim, ACCEPTED)).thenReturn(true);
            final ArgumentCaptor<CallerIdentity> caller =
                    ArgumentCaptor.forClass(CallerIdentity.class);

            client().submit(submission());

            verify(gateway).post(any(byte[].class), caller.capture(), any(Instant.class));
            assertThat(caller.getValue()).isEqualTo(CALLER);
        }

        @Test
        @DisplayName("writes the digest of exactly the bytes that went out")
        void the_digest_written_is_the_sha_256_of_the_bytes_that_went_out() {
            claimGranted();
            when(gateway.post(any(byte[].class), any(CallerIdentity.class), any(Instant.class))).thenReturn(ACCEPTED);
            when(outputs.recordPosted(claim, ACCEPTED)).thenReturn(true);
            final ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);

            client().submit(submission());

            verify(gateway).post(sent.capture(), any(CallerIdentity.class), any(Instant.class));
            assertThat(claimed().requestDigest()).isEqualTo(sha256(sent.getValue()));
        }

        @Test
        @DisplayName("names the register the row is about, before it is sent")
        void the_row_written_before_the_post_names_the_register_it_is_about() {
            claimGranted();
            when(gateway.post(any(byte[].class), any(CallerIdentity.class), any(Instant.class))).thenReturn(ACCEPTED);
            when(outputs.recordPosted(claim, ACCEPTED)).thenReturn(true);

            client().submit(submission());

            final ProcessedOutputClaim written = claimed();
            assertThat(written.outputId()).isNotNull();
            assertThat(written.courtCentreId()).isEqualTo(UUID.fromString(COURT_CENTRE_ID));
            assertThat(written.courtCentreOuCode())
                    .as("the OU code an operator searches the row by; the document has no field "
                            + "for it, so it travels beside it")
                    .isEqualTo(OU_CODE);
            assertThat(written.registerDate())
                    .as("the day the recipients were read for, not a second derivation of it (C12)")
                    .isEqualTo(REGISTER_DAY);
            assertThat(written.fileName()).isEqualTo(FILE_NAME);
        }

        /**
         * C19, C20 and C27. A register assembled with a part missing is still sent, and what was
         * skipped to send it is written down in the same statement as the digest — before the POST,
         * because the counts are worth more after a failure than after a success.
         */
        @Test
        @DisplayName("writes the counts of what the register survived, before it is sent")
        void the_counts_of_what_the_register_survived_are_written_before_it_is_sent() {
            claimGranted();
            when(gateway.post(any(byte[].class), any(CallerIdentity.class), any(Instant.class))).thenReturn(ACCEPTED);
            when(outputs.recordPosted(claim, ACCEPTED)).thenReturn(true);

            client().submit(submission(Map.of(
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED, 2,
                    TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT, 1)));

            assertThat(claimed().anomalies()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED, 2,
                    TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT, 1));
        }
    }

    /**
     * What goes on the wire is the command, not the register this increment records.
     *
     * <p>Increment 002 attaches {@code defendantType} to the document after the contract check, so
     * the register that reaches this adapter carries a field the {@code add-court-register} command
     * does not declare - and that command is {@code additionalProperties: false}, so a POST carrying
     * it is a 400 progression answers and, before defect fix C1, a register nobody ever learned was
     * lost. The projection back to the command's own fields is this adapter's, because it is the
     * only stage that knows what a POST body is; the record arm keeps the field, which is the whole
     * reason it exists.
     *
     * <p>The two assertions are the two halves of that. The body is byte-identical to the register
     * with no defendant type on it, which is exactly what 001 sent; and it satisfies the vendored
     * command schema, read back through the same validator the transformation holds a document to,
     * which is what says the projection is complete rather than merely one field shorter.
     */
    @Nested
    @DisplayName("the body a progression-post deployment sends")
    class TheCommandBody {

        /** The side of the court application the register's defendants are on (FR-002). */
        private static final String DEFENDANT_TYPE = "Appellant";

        @Test
        @DisplayName("carries the command's own fields and never the defendant type")
        void the_body_posted_is_the_command_body_without_the_defendant_type() {
            claimGranted();
            when(gateway.post(any(byte[].class), any(CallerIdentity.class), any(Instant.class)))
                    .thenReturn(ACCEPTED);
            when(outputs.recordPosted(claim, ACCEPTED)).thenReturn(true);
            final ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);

            client().submit(submissionOf(inContract(DEFENDANT_TYPE)));

            verify(gateway).post(sent.capture(), any(CallerIdentity.class), any(Instant.class));
            assertThat(MAPPER.readTree(sent.getValue()).at("/defendantType").isMissingNode())
                    .as("the command schema is additionalProperties: false and declares no "
                            + "defendantType, so a body carrying one is refused")
                    .isTrue();
            assertThat(new String(sent.getValue(), StandardCharsets.UTF_8))
                    .as("and the rest of the body is the register unchanged, as 001 sent it")
                    .isEqualTo(MAPPER.writeValueAsString(inContract(null)));
        }

        @Test
        @DisplayName("satisfies the vendored add-court-register schema")
        void the_body_posted_satisfies_the_vendored_command_schema() {
            claimGranted();
            when(gateway.post(any(byte[].class), any(CallerIdentity.class), any(Instant.class)))
                    .thenReturn(ACCEPTED);
            when(outputs.recordPosted(claim, ACCEPTED)).thenReturn(true);
            final ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);

            client().submit(submissionOf(inContract(DEFENDANT_TYPE)));

            verify(gateway).post(sent.capture(), any(CallerIdentity.class), any(Instant.class));
            final CourtRegisterDocument received =
                    MAPPER.readValue(sent.getValue(), CourtRegisterDocument.class);
            assertThatCode(() -> new OutboundContractValidator(MAPPER).validate(received))
                    .as("what was received is what progression's own contract is applied to")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("writes the digest of the command body, which is what was sent")
        void the_digest_written_is_the_digest_of_the_command_body() {
            claimGranted();
            when(gateway.post(any(byte[].class), any(CallerIdentity.class), any(Instant.class)))
                    .thenReturn(ACCEPTED);
            when(outputs.recordPosted(claim, ACCEPTED)).thenReturn(true);
            final ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);

            client().submit(submissionOf(inContract(DEFENDANT_TYPE)));

            verify(gateway).post(sent.capture(), any(CallerIdentity.class), any(Instant.class));
            assertThat(claimed().requestDigest())
                    .as("a digest of something other than what went out is worse than none")
                    .isEqualTo(sha256(sent.getValue()));
        }
    }

    @Nested
    @DisplayName("a register that has already gone")
    class AlreadyPosted {

        @Test
        @DisplayName("skips the POST entirely when the claim is refused")
        void a_refused_claim_skips_the_post_entirely() {
            when(outputs.claimPending(eq(claim), any(ProcessedOutputClaim.class)))
                    .thenReturn(false);

            client().submit(submission());

            verify(gateway, never()).post(any(byte[].class), any(CallerIdentity.class), any(Instant.class));
            verify(outputs, never()).recordPosted(any(RunClaim.class), org.mockito.ArgumentMatchers
                    .anyInt());
            verify(outputs, never()).recordFailed(any(RunClaim.class), any());
        }

        /**
         * The run still completes {@code submitted}, because the register has gone; the receipt says
         * this delivery is not what sent it, so nothing reports a POST that never left the pod.
         */
        @Test
        @DisplayName("answers a receipt that says it sent nothing")
        void a_skipped_post_answers_a_receipt_that_says_it_sent_nothing() {
            when(outputs.claimPending(eq(claim), any(ProcessedOutputClaim.class)))
                    .thenReturn(false);

            final SubmissionReceipt receipt = client().submit(submission());

            assertThat(receipt.sentByThisDelivery()).isFalse();
        }
    }

    @Nested
    @DisplayName("a submission that did not go — defect fix C1")
    class Failed {

        /**
         * The register row for C1. A 400 is the C29 shape reaching progression, and the legacy's
         * catch makes it indistinguishable from a delivered register: no status recorded, no row
         * moved, and a run that reports success. Here it is FAILED, carrying the status, before the
         * exception continues.
         */
        @Test
        @DisplayName("a_400_is_a_recorded_failure_never_silence")
        void a_400_is_a_recorded_failure_never_silence() {
            claimGranted();
            when(outputs.recordFailed(claim, REFUSED)).thenReturn(true);
            doThrow(new SubmissionFailedException(FailureClassification.NON_TRANSIENT,
                    ReasonCode.SUBMISSION_REJECTED, REFUSED))
                    .when(gateway).post(any(byte[].class), any(CallerIdentity.class), any(Instant.class));

            assertThatThrownBy(() -> client().submit(submission()))
                    .isInstanceOf(SubmissionFailedException.class)
                    .extracting(failure -> ((SubmissionFailedException) failure).reason())
                    .isEqualTo(ReasonCode.SUBMISSION_REJECTED);

            final InOrder order = inOrder(gateway, outputs);
            order.verify(gateway).post(any(byte[].class), any(CallerIdentity.class), any(Instant.class));
            order.verify(outputs).recordFailed(claim, REFUSED);
            verify(outputs, never()).recordPosted(any(RunClaim.class),
                    org.mockito.ArgumentMatchers.anyInt());
        }

        @Test
        @DisplayName("a transient failure is recorded before it is rethrown")
        void a_transient_failure_is_recorded_before_it_is_rethrown() {
            claimGranted();
            when(outputs.recordFailed(claim, 503)).thenReturn(true);
            doThrow(new SubmissionFailedException(FailureClassification.TRANSIENT,
                    ReasonCode.SUBMISSION_TRANSIENT, 503))
                    .when(gateway).post(any(byte[].class), any(CallerIdentity.class), any(Instant.class));

            assertThatThrownBy(() -> client().submit(submission()))
                    .isInstanceOf(SubmissionFailedException.class)
                    .extracting(failure -> ((SubmissionFailedException) failure).classification())
                    .isEqualTo(FailureClassification.TRANSIENT);

            verify(outputs).recordFailed(claim, 503);
        }

        /**
         * A connect failure or a timeout has no status line, and the row says so rather than
         * carrying an invented one: a PENDING-turned-FAILED row with no {@code response_code} is
         * exactly the state that warns a duplicate is possible.
         */
        @Test
        @DisplayName("records no status where progression never answered")
        void a_failure_with_no_answer_records_no_status() {
            claimGranted();
            when(outputs.recordFailed(claim, null)).thenReturn(true);
            doThrow(new SubmissionFailedException(FailureClassification.TRANSIENT,
                    ReasonCode.SUBMISSION_TRANSIENT))
                    .when(gateway).post(any(byte[].class), any(CallerIdentity.class), any(Instant.class));

            assertThatThrownBy(() -> client().submit(submission()))
                    .isInstanceOf(SubmissionFailedException.class);

            verify(outputs).recordFailed(claim, null);
        }
    }

    /**
     * The outcome write is the durable half of a submission, and a refused one decides what happens
     * next rather than merely being mentioned.
     *
     * <p>A claim was granted moments before, so the only way one of these fenced statements can
     * affect no row is that a delivery this one overlapped with reached the row first and POSTED it —
     * POSTED being terminal in the log — or that this runner's claim was reclaimed while it worked.
     * Either means two runners were working the same request, and <strong>what this runner believes
     * happened is not what the log durably says</strong>. Returning normally from that is the
     * silent-success shape the whole service exists to end: the run would complete
     * {@code submitted} on the strength of an in-memory belief, and the row that decides whether a
     * redelivery may send again would say something else.
     *
     * <p>So the delivery is handed back, TRANSIENT. What the redelivery then finds is exactly the
     * question {@code SubmissionRedeliveryIT} answers against a real Postgres: a row that is POSTED
     * is skipped and the run completes {@code submitted} without a second POST; a row left PENDING
     * or FAILED is re-claimed and re-sent, which is the crash-window trade this service makes
     * deliberately — a duplicate progression's {@code max(register_time) per hearing_id} sweep
     * absorbs, in preference to a loss nothing absorbs and nobody sees.
     */
    @Nested
    @DisplayName("an outcome write that lands on nothing")
    class OutcomeNotRecorded {

        /**
         * The ambiguous-outcome case. Progression accepted the command and the log would not say so:
         * this delivery cannot prove the register is on record, so it does not claim it did.
         */
        @Test
        @DisplayName("a POST the log would not record hands the delivery back rather than completing")
        void a_post_the_log_would_not_record_hands_the_delivery_back() {
            claimGranted();
            when(gateway.post(any(byte[].class), any(CallerIdentity.class), any(Instant.class)))
                    .thenReturn(ACCEPTED);
            when(outputs.recordPosted(claim, ACCEPTED)).thenReturn(false);

            try (CapturedLog log =
                         CapturedLog.capturing(ProgressionRegisterSubmissionClient.class)) {
                assertThatThrownBy(() -> client().submit(submission()))
                        .isInstanceOf(SubmissionFailedException.class)
                        .asInstanceOf(
                                InstanceOfAssertFactories.type(SubmissionFailedException.class))
                        .satisfies(failure -> {
                            assertThat(failure.classification())
                                    .as("the redelivery reads the row and decides; this run cannot")
                                    .isEqualTo(FailureClassification.TRANSIENT);
                            assertThat(failure.reason())
                                    .isEqualTo(ReasonCode.SUBMISSION_TRANSIENT);
                            assertThat(failure.responseCode())
                                    .as("what progression answered is still what was observed")
                                    .isEqualTo(OptionalInt.of(ACCEPTED));
                        });

                assertThat(log.renderings())
                        .anyMatch(line -> line.contains("Outcome write affected no row"));
            }
        }

        /**
         * The same rule on the failure path, and it changes the classification. A refusal is
         * NON_TRANSIENT and would be parked — but a park is a terminal verdict, and this runner has
         * just been told its verdict was not the one recorded. It hands the delivery back instead.
         */
        @Test
        @DisplayName("a failure the log would not record comes back transient, not parked")
        void a_failure_the_log_would_not_record_comes_back_transient() {
            claimGranted();
            when(outputs.recordFailed(claim, REFUSED)).thenReturn(false);
            doThrow(new SubmissionFailedException(FailureClassification.NON_TRANSIENT,
                    ReasonCode.SUBMISSION_REJECTED, REFUSED))
                    .when(gateway).post(any(byte[].class), any(CallerIdentity.class),
                            any(Instant.class));

            try (CapturedLog log =
                         CapturedLog.capturing(ProgressionRegisterSubmissionClient.class)) {
                assertThatThrownBy(() -> client().submit(submission()))
                        .isInstanceOf(SubmissionFailedException.class)
                        .extracting(failure ->
                                ((SubmissionFailedException) failure).classification())
                        .as("parking a request whose row says something else is a lie the DLQ keeps")
                        .isEqualTo(FailureClassification.TRANSIENT);

                assertThat(log.renderings())
                        .as("a failure the log could not record is still a failure")
                        .anyMatch(line -> line.contains("Outcome write affected no row"));
            }
        }

        @Test
        @DisplayName("a failure the log did record keeps the classification the transport gave it")
        void a_failure_the_log_did_record_keeps_its_classification() {
            claimGranted();
            when(outputs.recordFailed(claim, REFUSED)).thenReturn(true);
            doThrow(new SubmissionFailedException(FailureClassification.NON_TRANSIENT,
                    ReasonCode.SUBMISSION_REJECTED, REFUSED))
                    .when(gateway).post(any(byte[].class), any(CallerIdentity.class),
                            any(Instant.class));

            assertThatThrownBy(() -> client().submit(submission()))
                    .isInstanceOf(SubmissionFailedException.class)
                    .extracting(failure -> ((SubmissionFailedException) failure).classification())
                    .isEqualTo(FailureClassification.NON_TRANSIENT);
        }
    }

    /**
     * A failure nothing here anticipated, met after the row was claimed.
     *
     * <p>The claim is a promise made in the database: this delivery is about to POST, and until the
     * row moves the log says a submission is in flight. A failure that escaped this frame would
     * leave that PENDING row behind with nothing going to finish it — and the next delivery, reading
     * a PENDING row, re-claims and re-sends, which is right for a crash and wrong for a body that
     * cannot be serialised or a gateway that threw before it reached the wire.
     *
     * <p>So the row is settled FAILED before the failure continues upward, and the failure continues
     * upward: caught to record, never to absorb, which is the whole of C1. The reason written is
     * bounded — the exception's type reaches the log line, never its message, because a message from
     * a layer that was handling a register can quote one.
     */
    @Nested
    @DisplayName("a failure nothing anticipated, after the row was claimed")
    class UnexpectedFailure {

        @Test
        @DisplayName("settles the claimed row FAILED before the failure continues")
        void an_unexpected_failure_settles_the_row_before_it_continues() {
            claimGranted();
            when(outputs.recordFailed(claim, null)).thenReturn(true);
            doThrow(new IllegalStateException("the gateway broke"))
                    .when(gateway).post(any(byte[].class), any(CallerIdentity.class),
                            any(Instant.class));

            assertThatThrownBy(() -> client().submit(submission()))
                    .as("the failure is recorded, not absorbed")
                    .isInstanceOf(IllegalStateException.class);

            final InOrder order = inOrder(gateway, outputs);
            order.verify(gateway).post(any(byte[].class), any(CallerIdentity.class),
                    any(Instant.class));
            order.verify(outputs).recordFailed(claim, null);
            verify(outputs, never()).recordPosted(any(RunClaim.class),
                    org.mockito.ArgumentMatchers.anyInt());
        }

        @Test
        @DisplayName("names the type and a bounded reason, never the failure's own words")
        void an_unexpected_failure_is_reported_by_type_and_bounded_reason() {
            claimGranted();
            when(outputs.recordFailed(claim, null)).thenReturn(true);
            doThrow(new IllegalStateException(DEFENDANT_NAME + " could not be serialised"))
                    .when(gateway).post(any(byte[].class), any(CallerIdentity.class),
                            any(Instant.class));

            try (CapturedLog log =
                         CapturedLog.capturing(ProgressionRegisterSubmissionClient.class)) {
                assertThatThrownBy(() -> client().submit(submission()))
                        .isInstanceOf(IllegalStateException.class);

                assertThat(log.renderings())
                        .anyMatch(line -> line.contains(IllegalStateException.class.getName())
                                && line.contains(ReasonCode.UNEXPECTED_FAILURE.code()))
                        .as("every defendant on this register is a child")
                        .noneMatch(line -> line.contains(DEFENDANT_NAME));
            }
        }

        @Test
        @DisplayName("says so when even that settlement lands on no row")
        void an_unexpected_failure_whose_settlement_lands_on_nothing_is_reported() {
            claimGranted();
            when(outputs.recordFailed(claim, null)).thenReturn(false);
            doThrow(new IllegalStateException("the gateway broke"))
                    .when(gateway).post(any(byte[].class), any(CallerIdentity.class),
                            any(Instant.class));

            try (CapturedLog log =
                         CapturedLog.capturing(ProgressionRegisterSubmissionClient.class)) {
                assertThatThrownBy(() -> client().submit(submission()))
                        .isInstanceOf(IllegalStateException.class);

                assertThat(log.renderings())
                        .anyMatch(line -> line.contains("Outcome write affected no row"));
            }
        }
    }

    @Nested
    @DisplayName("what the log is allowed to say")
    class Privacy {

        @Test
        @DisplayName("no line carries the document or anyone named in it")
        void no_line_carries_the_document_or_anyone_named_in_it() {
            claimGranted();
            when(gateway.post(any(byte[].class), any(CallerIdentity.class), any(Instant.class))).thenReturn(ACCEPTED);
            when(outputs.recordPosted(claim, ACCEPTED)).thenReturn(true);

            try (CapturedLog log =
                         CapturedLog.capturing(ProgressionRegisterSubmissionClient.class)) {
                client().submit(submission());

                assertThat(log.renderings())
                        .as("every defendant on this register is a child")
                        .noneMatch(line -> line.contains(DEFENDANT_NAME))
                        .noneMatch(line -> line.contains(DEFENDANT_ADDRESS_LINE));
            }
        }

        @Test
        @DisplayName("the caller never reaches a log line")
        void the_identity_never_reaches_a_log_line() {
            claimGranted();
            when(gateway.post(any(byte[].class), any(CallerIdentity.class), any(Instant.class))).thenReturn(ACCEPTED);
            when(outputs.recordPosted(claim, ACCEPTED)).thenReturn(true);

            try (CapturedLog log =
                         CapturedLog.capturing(ProgressionRegisterSubmissionClient.class)) {
                client().submit(submission());

                assertThat(log.renderings()).noneMatch(
                        line -> line.contains(CALLER.userId().orElseThrow().toString()));
            }
        }
    }

    /** The claim the adapter wrote, which is the only place several of these facts appear. */
    private ProcessedOutputClaim claimed() {
        final ArgumentCaptor<ProcessedOutputClaim> written =
                ArgumentCaptor.forClass(ProcessedOutputClaim.class);
        verify(outputs).claimPending(eq(claim), written.capture());
        return written.getValue();
    }

    private static String sha256(final byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * A register the frozen contract accepts, carrying the defendant type named.
     *
     * <p>The suite's other fixture is written for the privacy cases and is deliberately sparse - it
     * carries empty lists where the schemas put {@code minItems: 1} - so it cannot be used to say
     * anything about what a contract makes of the body. This one is populated to exactly the
     * contract's required set, so the only difference between the two bodies below is the field
     * this nested case is about.
     *
     * @param defendantType the side of the court application the register's defendants are on, or
     *                      {@code null} for the 001 shape
     * @return the register
     */
    private static CourtRegisterDocument inContract(final String defendantType) {
        final CourtRegisterDefendant defendant = new CourtRegisterDefendant(
                "b2b3f5a1-6c9d-4e21-8a7f-3d5c1e9b0426", DEFENDANT_NAME, "2008-04-11",
                new CourtRegisterAddress(
                        DEFENDANT_ADDRESS_LINE, null, null, null, null, "BS1 1AA"),
                null, null, "MALE", "Not Applicable", null, null, null,
                List.of(new CourtRegisterCaseOrApplication(
                        "TFL4359536", null, null, null, null, null, null)),
                null, null);
        return new CourtRegisterDocument(
                "2020-06-01T10:00:00Z",
                "2020-01-20T00:00:00Z",
                HEARING_ID,
                COURT_CENTRE_ID,
                FILE_NAME,
                defendantType,
                new CourtRegisterHearingVenue(
                        "South West London Magistrates' Court",
                        "Lavender Hill Magistrates' Court",
                        new CourtRegisterAddress(
                                "176A Lavender Hill", null, null, null, null, "SW11 1JU")),
                List.of(new CourtRegisterRecipient(
                        "Youth Offending Team", "yot@example.gov.uk", null, "cr_standard")),
                List.of(defendant));
    }

    /** A register with a child on it, so the privacy cases have something real to look for. */
    private static CourtRegisterDocument document() {
        final CourtRegisterDefendant defendant = new CourtRegisterDefendant(
                "b2b3f5a1-6c9d-4e21-8a7f-3d5c1e9b0426", DEFENDANT_NAME, "2008-04-11",
                new CourtRegisterAddress(
                        DEFENDANT_ADDRESS_LINE, null, null, null, null, "BS1 1AA"),
                null, null, "MALE", "Not Applicable", null, null,
                List.of(), List.of(), List.of(), List.of());
        final CourtRegisterRecipient recipient = new CourtRegisterRecipient(
                "Youth Offending Team", "yot@example.gov.uk", null, "cr_standard");
        return new CourtRegisterDocument(
                "2020-06-01T10:00:00Z",
                "2020-01-20T00:00:00Z",
                HEARING_ID,
                COURT_CENTRE_ID,
                FILE_NAME,
                null,
                null,
                List.of(recipient),
                List.of(defendant));
    }
}
