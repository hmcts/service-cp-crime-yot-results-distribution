package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.yotresultsdistribution.application.IdempotencyGuard;
import uk.gov.hmcts.cp.yotresultsdistribution.config.ProcessingMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletionReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeadLetterReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GuardDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ProcessedOutputClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ProcessedRequestRecord;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestFingerprint;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport.Row;

/**
 * A parked request is replayable by a delivery carrying a different broker sequence number from the
 * one that parked it — whatever its {@code messageId} (spec 007-replay-by-sequence-number, FR-003,
 * FR-004).
 *
 * <p>The sequence number is the whole of the decision for a row this version parked (an "exact"
 * row: its recorded {@code parking_attempt_count} still matches the row's current {@code attempts}).
 * A different sequence number is a deliberate resubmission — the Azure portal's dead-letter "Re-send"
 * copies the body, {@code messageId} included, which is exactly what the headline case below proves
 * recovers the request. The same sequence number is dead-lettering that did not settle, and it must
 * not run anything, whatever {@code messageId} it happens to carry.
 *
 * <p>The full arithmetic is driven end to end — five failed deliveries, then one replay that
 * succeeds — because {@code attempts} = 6 is the number a support engineer reads, and it only comes
 * out right if every acquisition increments exactly once and the replay preserves what was there.
 *
 * <p>This is the supported way to recover a dead-lettered court register: there is no operator flag
 * to remember and no row to edit by hand, and — since this feature — no fresh {@code messageId} to
 * mint either.
 */
class FailedReplayIT {

    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final int PERMITTED_DELIVERIES = 5;

    private static final UUID COURT_CENTRE = UUID.fromString("6b1d3f2a-88c4-4a2e-9f10-2d7c5b9e0a41");
    private static final String OU_CODE = "B01LY";
    private static final LocalDate REGISTER_DATE = LocalDate.of(2026, 8, 20);
    private static final String FILE_NAME = "court-register-B01LY-20260820.pdf";
    private static final String OUTPUT_DIGEST =
            "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752";
    private static final int ACCEPTED = 202;

    private final ProcessingMetrics metrics = new ProcessingMetrics(new SimpleMeterRegistry());
    private final IdempotencyGuard guard = ProcessedLogTestSupport.guard(LEASE, metrics);
    private final DistributionCommand command = ProcessedLogTestSupport.command();
    private final Logger guardLogger = (Logger) LoggerFactory.getLogger(IdempotencyGuard.class);
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

    @BeforeEach
    void captureTheGuardsLog() {
        captured.start();
        guardLogger.addAppender(captured);
    }

    @AfterEach
    void detachAppender() {
        guardLogger.detachAppender(captured);
    }

    /**
     * A delivery under an explicit, distinct broker sequence number — the axis this feature's whole
     * decision turns on. {@code deliveryCount} and {@code enqueueTime} are not this file's concern
     * (they decide the legacy/stale heuristic, FR-005, proven elsewhere) and are left at their
     * no-opinion defaults.
     */
    private static DeliveryIdentity delivery(final String messageId, final long sequenceNumber) {
        return new DeliveryIdentity(
                messageId, "runner-1/delivery-" + sequenceNumber, false, sequenceNumber, 0L, null);
    }

    private static RunClaim runClaimOf(final GuardDecision decision) {
        assertThat(decision).isInstanceOf(GuardDecision.Run.class);
        return ((GuardDecision.Run) decision).claim();
    }

    private Row row() {
        return ProcessedLogTestSupport.requireRow(command.source(), command.requestId());
    }

    /**
     * Fails the request through all five permitted deliveries, parking it on the fifth under
     * {@code messageId} {@code "msg-5"} and sequence number {@code 5}.
     */
    private void exhaustEveryPermittedDelivery() {
        for (int number = 1; number < PERMITTED_DELIVERIES; number++) {
            guard.recordTransientFailure(
                    runClaimOf(guard.admit(command, delivery("msg-" + number, number))),
                    ReasonCode.PIPELINE_TRANSIENT_FAILURE);
        }
        guard.recordExhaustion(
                runClaimOf(guard.admit(command,
                        delivery("msg-" + PERMITTED_DELIVERIES, PERMITTED_DELIVERIES))),
                ReasonCode.PIPELINE_TRANSIENT_FAILURE);
    }

    @Test
    @DisplayName("five failed deliveries park the request with the sequence number that parked it")
    void the_fifth_failure_should_park_the_request() {
        exhaustEveryPermittedDelivery();

        final Row row = row();
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.attempts()).isEqualTo(PERMITTED_DELIVERIES);
        assertThat(row.failureReason()).isEqualTo("PIPELINE_TRANSIENT_FAILURE");
        assertThat(row.exhaustedMessageId()).isEqualTo("msg-5");
        assertThat(row.parkingSequenceNumber()).isEqualTo(5L);
        assertThat(row.parkingCategory()).isEqualTo("EXHAUSTED");
        assertThat(row.parkingAttemptCount()).isEqualTo(PERMITTED_DELIVERIES);
    }

    @Test
    @DisplayName("a resubmission under the SAME messageId but a DIFFERENT sequence number replays "
            + "(FR-003/FR-004) - the dead-letter portal \"Re-send\" case this feature exists for")
    void a_resubmission_reusing_the_message_id_should_replay_on_sequence_number_alone() {
        exhaustEveryPermittedDelivery();

        // "msg-5" is the parking messageId, reused unchanged - exactly as the Azure portal's
        // dead-letter Re-send copies the body. The sequence number is what the broker assigns fresh
        // to the accepted re-send, and it is 6, not 5.
        final RunClaim replay = runClaimOf(guard.admit(command, delivery("msg-5", 6)));

        assertReplayAdmittedCarryingSixAttempts(replay);
    }

    @Test
    @DisplayName("a resubmission under a fresh messageId and a different sequence number still "
            + "replays - the existing replay route keeps working (Acceptance Scenario 3)")
    void a_resubmission_under_a_fresh_message_id_should_also_replay() {
        exhaustEveryPermittedDelivery();

        final RunClaim replay = runClaimOf(guard.admit(command, delivery("msg-6", 6)));

        assertReplayAdmittedCarryingSixAttempts(replay);
    }

    private void assertReplayAdmittedCarryingSixAttempts(final RunClaim replay) {
        final Row row = row();
        assertThat(row.status()).isEqualTo("RECEIVED");
        assertThat(row.attempts()).isEqualTo(PERMITTED_DELIVERIES + 1);
        assertThat(row.failureReason()).isNull();
        assertThat(row.exhaustedMessageId()).isNull();
        assertThat(row.parkingSequenceNumber()).isNull();
        assertThat(row.parkingCategory()).isNull();
        assertThat(row.parkingAttemptCount()).isNull();
        assertThat(row.auditNote()).contains("PIPELINE_TRANSIENT_FAILURE");
        assertThat(row.claimOwner()).isEqualTo(replay.owner());
        assertThat(row.claimToken()).isEqualTo(replay.token());
        assertThat(row.claimExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("an admitted replay logs both the parked and the replaying sequence number, "
            + "identifiers only (FR-008)")
    void an_admitted_replay_should_log_both_sequence_numbers() {
        exhaustEveryPermittedDelivery();

        guard.admit(command, delivery("msg-5", 6));

        assertThat(captured.list)
                .anySatisfy(event -> {
                    final String message = event.getFormattedMessage();
                    assertThat(message).contains(command.requestId().toString());
                    assertThat(message).contains("5").contains("6");
                });
    }

    @Test
    @DisplayName("the replayed run completes, leaving six lifetime attempts")
    void a_successful_replay_should_leave_the_record_completed_with_six_attempts() {
        exhaustEveryPermittedDelivery();

        guard.recordCompletion(
                runClaimOf(guard.admit(command, delivery("msg-5", 6))), CompletionReason.SUBMITTED);

        final Row row = row();
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.completionReason()).isEqualTo("submitted");
        assertThat(row.attempts()).isEqualTo(6);
        assertThat(row.claimOwner()).isNull();
    }

    /**
     * A replayed run that turns out to have nothing to send still completes.
     *
     * <p>Worth its own case because the register's commonest outcomes send nothing: a replay that
     * ends {@code no-subscriptions} has recovered the request just as surely as one that posts, and
     * the audit note has to survive alongside a completion reason that never mentions the failure.
     */
    @Test
    @DisplayName("a replay that ends in a no-op completes under its own reason, note intact")
    void a_replay_that_sends_nothing_should_still_be_a_recovery() {
        exhaustEveryPermittedDelivery();

        guard.recordCompletion(
                runClaimOf(guard.admit(command, delivery("msg-5", 6))), CompletionReason.NO_SUBSCRIPTIONS);

        final Row row = row();
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.completionReason()).isEqualTo("no-subscriptions");
        assertThat(row.failureReason()).isNull();
        assertThat(row.auditNote()).contains("PIPELINE_TRANSIENT_FAILURE");
    }

    /**
     * The independent-enqueue regression, re-asserted under the new decision path: admitting a
     * replay by sequence number must not disturb the output row's own idempotency gate.
     *
     * <p>{@code guard.admit()} only decides whether the <em>request</em> runs again; whether a
     * register is sent a second time is {@code ProcessedOutputRepository}'s own claim, proven in
     * isolation by {@code OutputClaimFencingIT} and {@code SubmissionRedeliveryIT}. Those suites
     * never go through {@code guard.admit()} at all, so neither can say whether admitting a replay by
     * sequence number — rather than by a fresh {@code messageId} — changes what reaches the output
     * row.
     *
     * <p>The scenario this proves: a first run POSTs the register, then the request itself still
     * ends up {@code FAILED} (a non-transient failure unrelated to the POST, the only way a
     * {@code FAILED} row and an already-{@code POSTED} output row coexist). The replay below reuses
     * that <strong>same</strong> {@code messageId} — the headline case this feature exists for — so
     * it is the sequence number alone, not a fresh identity, that gets it admitted; the output claim
     * it then attempts is still refused by the row the first run already settled.
     */
    @Test
    @DisplayName("a replay of an already-POSTED register still posts nothing a second time, "
            + "admitted by sequence number alone")
    void a_replay_of_an_already_posted_register_should_not_post_it_again() {
        final ProcessedOutputRepository outputs =
                new ProcessedOutputRepository(ProcessedLogTestSupport.jdbcClient());
        final RunClaim firstRun = runClaimOf(guard.admit(command, delivery("msg-1", 1)));
        assertThat(outputs.claimPending(firstRun, outputClaim()))
                .as("the first run claims the output row")
                .isTrue();
        assertThat(outputs.recordPosted(firstRun, ACCEPTED))
                .as("the first run's POST was accepted and settled")
                .isTrue();
        guard.recordNonTransientFailure(firstRun, ReasonCode.REGISTER_NOT_RECORDED);
        assertThat(row().status()).isEqualTo("FAILED");

        final RunClaim secondRun = runClaimOf(guard.admit(command, delivery("msg-1", 2)));
        final boolean secondClaim = outputs.claimPending(secondRun, outputClaim());

        assertThat(secondClaim)
                .as("a request admitted again under the SAME messageId, by sequence number alone, "
                        + "still finds its output row POSTED, and the claim that would let it "
                        + "submit a second time is refused")
                .isFalse();
    }

    private static ProcessedOutputClaim outputClaim() {
        return new ProcessedOutputClaim(
                UUID.randomUUID(), COURT_CENTRE, OU_CODE, REGISTER_DATE, FILE_NAME,
                OUTPUT_DIGEST, null);
    }

    @Test
    @DisplayName("a delivery carrying the SAME sequence number that parked it changes nothing "
            + "(FR-003), whatever its messageId")
    void a_delivery_carrying_the_parking_sequence_number_should_change_nothing() {
        exhaustEveryPermittedDelivery();
        final Row before = row();

        // Same messageId as the parking delivery ("msg-5") AND the same sequence number (5) - the
        // straightforward redelivery-of-the-parking-delivery case.
        final GuardDecision decision = guard.admit(command, delivery("msg-5", 5));

        assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                DeadLetterReason.EXHAUSTED, ReasonCode.DELIVERY_LIMIT_EXHAUSTED));
        assertThat(row()).isEqualTo(before);
    }

    @Test
    @DisplayName("a DIFFERENT messageId carrying the SAME sequence number still changes nothing - "
            + "messageId plays no part in this decision (FR-003)")
    void a_delivery_carrying_the_parking_sequence_number_under_a_different_message_id_should_also_change_nothing() {
        exhaustEveryPermittedDelivery();
        final Row before = row();

        final GuardDecision decision = guard.admit(command, delivery("msg-looks-fresh-but-isnt", 5));

        assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                DeadLetterReason.EXHAUSTED, ReasonCode.DELIVERY_LIMIT_EXHAUSTED));
        assertThat(row()).isEqualTo(before);
    }

    /**
     * Zero rows on the replay update never means "the same sequence number" — that case is decided
     * on the read. It means the record moved between the read and the update: a concurrent replay
     * won, or the record is no longer parked. Per the no-spin rule the delivery is handed back rather
     * than re-read in a loop, and the broker's redelivery re-enters the state machine against
     * whatever the record says by then.
     *
     * <p>Driven with a repository whose read is deliberately stale, because that is precisely the
     * state the guard would have been in when the race was lost: the read still shows the exact
     * generation that parked the request ({@code attempts} = 5, {@code parking_sequence_number} = 5),
     * but the real row underneath has already moved on to COMPLETED.
     */
    @Test
    @DisplayName("a replay whose record moved under it is handed back, not retried in a loop "
            + "(FR-006, the stale replay decision)")
    void a_replay_update_matching_no_row_should_abandon_the_delivery() {
        guard.recordCompletion(
                runClaimOf(guard.admit(command, delivery("msg-1", 1))), CompletionReason.SUBMITTED);
        final Row before = row();

        final IdempotencyGuard staleReadingGuard = new IdempotencyGuard(
                new ProcessedRequestRepository(ProcessedLogTestSupport.jdbcClient(), LEASE) {
                    @Override
                    public Optional<ProcessedRequestRecord> read(
                            final String source, final UUID requestId) {
                        return Optional.of(new ProcessedRequestRecord(
                                RequestStatus.FAILED,
                                RequestFingerprint.of(command),
                                ReasonCode.PIPELINE_TRANSIENT_FAILURE.code(),
                                "msg-5",
                                5,
                                null,
                                null,
                                5L,
                                "EXHAUSTED",
                                5,
                                Instant.now()));
                    }
                },
                metrics);

        final GuardDecision decision = staleReadingGuard.admit(command, delivery("msg-5", 6));

        assertThat(decision).isEqualTo(new GuardDecision.Abandon(ReasonCode.REPLAY_NOT_ADMITTED));
        assertThat(row()).isEqualTo(before);
    }

    /**
     * The spec's "independent-enqueue policy" (Edge Cases, "A stale copy of an older parking
     * delivery…"): a stray or delayed copy arriving after a newer failure has already been recorded
     * carries a sequence number that differs from the row's <em>current</em> parking sequence number
     * — whatever it was reading for — and is therefore admitted and replays the current failure once.
     * This is the deliberate opposite of the previous case: that one is a decision caught moving
     * <em>during</em> the race and refused; this one is a decision reached cleanly, after the row has
     * already settled on a newer generation, and admitted.
     */
    @Test
    @DisplayName("a stray copy arriving after a newer failure is admitted and replays the current "
            + "failure once (the independent-enqueue policy, distinct from the stale-decision refusal "
            + "above)")
    void a_stray_copy_arriving_after_a_newer_failure_should_replay_the_current_failure() {
        exhaustEveryPermittedDelivery();
        // A first replay fails again, parking a newer generation under a different sequence number.
        guard.recordExhaustion(
                runClaimOf(guard.admit(command, delivery("msg-5", 6))),
                ReasonCode.PIPELINE_TRANSIENT_FAILURE);
        assertThat(row().parkingSequenceNumber()).isEqualTo(6L);

        // A stray copy of an even earlier attempt now arrives, carrying a sequence number (7) that
        // differs from the row's current parking sequence number (6) - whatever delivery it was
        // actually a copy of. It is admitted on that basis alone.
        final RunClaim stray = runClaimOf(guard.admit(command, delivery("msg-stray", 7)));

        final Row row = row();
        assertThat(row.status()).isEqualTo("RECEIVED");
        assertThat(row.attempts()).isEqualTo(PERMITTED_DELIVERIES + 2);
        assertThat(row.parkingSequenceNumber()).isNull();
        assertThat(row.claimOwner()).isEqualTo(stray.owner());
    }

    @Test
    @DisplayName("two concurrent resubmissions admit exactly one replay; the loser is handed back "
            + "(FR-006 concurrency)")
    void two_concurrent_resubmissions_should_admit_exactly_one_replay() {
        exhaustEveryPermittedDelivery();

        final GuardDecision first = guard.admit(command, delivery("msg-5", 6));
        // Both deliveries read the same FAILED/attempts=5/sequenceNumber=5 generation in a real race;
        // here the first admit() has already moved the record on, so the second's own read (inside
        // its own admit() call) sees the new RECEIVED state and is decided by claimOrHandBack, not by
        // replayOrPark - the record is simply no longer FAILED for it to race on.
        final GuardDecision second = guard.admit(command, delivery("msg-6", 7));

        assertThat(first).isInstanceOf(GuardDecision.Run.class);
        assertThat(second).isEqualTo(new GuardDecision.Abandon(ReasonCode.CLAIM_NOT_ACQUIRED));
        assertThat(row().status()).isEqualTo("RECEIVED");
        assertThat(row().attempts()).isEqualTo(PERMITTED_DELIVERIES + 1);
    }
}
