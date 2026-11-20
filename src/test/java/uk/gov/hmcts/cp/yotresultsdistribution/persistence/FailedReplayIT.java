package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
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
import org.junit.jupiter.api.Nested;
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
                DeadLetterReason.EXHAUSTED, ReasonCode.PIPELINE_TRANSIENT_FAILURE));
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
                DeadLetterReason.EXHAUSTED, ReasonCode.PIPELINE_TRANSIENT_FAILURE));
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
                metrics, ProcessedLogTestSupport.LEGACY_MARGIN);

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

    // --- US4: a re-parked delivery says why the request failed (spec 007, FR-009/FR-010) ----------

    /**
     * FR-009, T039: a request parked {@code non-transient} with a recognised stored failure reason
     * is re-parked under that same reason and category, never the generic
     * {@code exhausted / DELIVERY_LIMIT_EXHAUSTED}.
     */
    @Test
    @DisplayName("a re-park of a non-transient failure dead-letters with the request's own reason "
            + "and category (FR-009)")
    void a_re_park_should_report_the_requests_own_non_transient_reason_and_category() {
        final RunClaim firstRun = runClaimOf(guard.admit(command, delivery("msg-1", 1)));
        guard.recordNonTransientFailure(firstRun, ReasonCode.OUTBOUND_CONTRACT_VIOLATION);

        final GuardDecision reParked = guard.admit(command, delivery("msg-1", 1));

        assertThat(reParked).isEqualTo(new GuardDecision.DeadLetter(
                DeadLetterReason.NON_TRANSIENT, ReasonCode.OUTBOUND_CONTRACT_VIOLATION));
    }

    /**
     * FR-009, T040: a request parked {@code exhausted} with a transient failure reason is re-parked
     * under that reason, still categorised {@code exhausted} — but the <em>first</em> parking of an
     * exhausted request is unchanged, because {@code recordExhaustion} does not go through a re-park
     * decision at all. Both halves are asserted here together, so neither can be mistaken for having
     * covered the other.
     */
    @Test
    @DisplayName("the first parking of an exhausted request is unchanged; its re-park reports the "
            + "request's own transient reason, still exhausted (FR-009)")
    void the_first_parking_should_be_generic_but_its_re_park_should_report_the_real_reason() {
        final RunClaim firstRun = runClaimOf(guard.admit(command, delivery("msg-1", 1)));

        final GuardDecision firstParking =
                guard.recordExhaustion(firstRun, ReasonCode.PAYLOAD_UNAVAILABLE);
        assertThat(firstParking)
                .as("the first parking of an exhausted request is unchanged by this feature")
                .isEqualTo(new GuardDecision.DeadLetter(
                        DeadLetterReason.EXHAUSTED, ReasonCode.DELIVERY_LIMIT_EXHAUSTED));

        final GuardDecision reParked = guard.admit(command, delivery("msg-1", 1));
        assertThat(reParked)
                .as("a re-park of the same delivery reports the request's own recorded reason")
                .isEqualTo(new GuardDecision.DeadLetter(
                        DeadLetterReason.EXHAUSTED, ReasonCode.PAYLOAD_UNAVAILABLE));
    }

    /**
     * FR-009, T042: a stored failure reason this version cannot classify — missing, or written by a
     * version that does not exist yet — is re-parked as {@code exhausted / UNEXPECTED_FAILURE}
     * rather than propagate text this service did not write. Logs a sanitised data-anomaly error
     * (identifiers only), never emits the unrecognised text, never throws.
     */
    @Test
    @DisplayName("an unrecognised stored failure reason re-parks as exhausted/UNEXPECTED_FAILURE "
            + "without emitting it, and logs a sanitised anomaly (FR-009)")
    void a_re_park_of_an_unrecognised_stored_reason_should_not_emit_it() {
        final RunClaim firstRun = runClaimOf(guard.admit(command, delivery("msg-1", 1)));
        guard.recordNonTransientFailure(firstRun, ReasonCode.OUTBOUND_CONTRACT_VIOLATION);
        final String unrecognisedReason = "A_FUTURE_REASON_THIS_VERSION_HAS_NEVER_HEARD_OF";
        final int restaled = ProcessedLogTestSupport.jdbcClient()
                .sql("UPDATE processed_request SET failure_reason = :reason "
                        + "WHERE source = :source AND request_id = :requestId")
                .param("reason", unrecognisedReason)
                .param("source", command.source())
                .param("requestId", command.requestId())
                .update();
        assertThat(restaled).isEqualTo(1);

        final GuardDecision reParked = guard.admit(command, delivery("msg-1", 1));

        assertThat(reParked)
                .as("never thrown, never the unrecognised text itself")
                .isEqualTo(new GuardDecision.DeadLetter(
                        DeadLetterReason.EXHAUSTED, ReasonCode.UNEXPECTED_FAILURE));
        assertThat(captured.list)
                .as("the anomaly is logged, but the unrecognised text itself is never emitted")
                .noneMatch(event -> event.getFormattedMessage().contains(unrecognisedReason));
        assertThat(captured.list)
                .as("an anomaly is actually logged, not merely silent about the unrecognised text")
                .anyMatch(event -> event.getLevel().equals(Level.ERROR));
    }

    // --- US3: requests parked before this change can be recovered (spec 007, FR-005) -------------

    /**
     * A row with no recorded parking sequence number — parked before this feature shipped, or by an
     * older consumer mid-rollout — has no exact signal to decide a redelivery by. FR-005's two-
     * condition heuristic applies instead: a first delivery (the broker's count is zero-based) whose
     * enqueue time is later than the row's own {@code updated_at} (the parking time, for a row
     * nothing else touches while it stays {@code FAILED}) by more than the configured margin is
     * treated as a resubmission; anything that fails either condition — including a delivery with no
     * enqueue time at all — is re-parked. Replay is still the same compare-and-set as the exact row
     * (FR-006); its null-safe half matches a {@code NULL} {@code parking_sequence_number} exactly as
     * it matches any other recorded value.
     *
     * <p>Every test here deliberately gives the legacy delivery a {@code messageId} the pre-FR-005
     * code's own messageId-identity fallback would have decided the <em>opposite</em> way, so each
     * one is a genuine pin of the new heuristic rather than an assertion the old code already
     * satisfied by coincidence.
     */
    @Nested
    @DisplayName("a FAILED row with no recorded parking sequence number (spec 007, US3, FR-005)")
    class LegacyRecords {

        /**
         * Parks the request through the normal five deliveries, then simulates a row that predates
         * this feature (or was parked by an older consumer mid-rollout) by forgetting the parking
         * triple this version's own exhaustion just wrote. {@code exhausted_message_id},
         * {@code failure_reason} and {@code attempts} are left exactly as the real parking wrote
         * them — only the three new columns are forgotten.
         */
        private void parkThenForgetTheParkingTriple() {
            exhaustEveryPermittedDelivery();
            final int cleared = ProcessedLogTestSupport.jdbcClient()
                    .sql("""
                            UPDATE processed_request
                               SET parking_sequence_number = NULL, parking_category = NULL,
                                   parking_attempt_count = NULL
                             WHERE source = :source AND request_id = :requestId
                            """)
                    .param("source", command.source())
                    .param("requestId", command.requestId())
                    .update();
            assertThat(cleared).isEqualTo(1);
        }

        private static DeliveryIdentity legacyDelivery(
                final String messageId, final long sequenceNumber, final long deliveryCount,
                final Instant enqueueTime) {
            return new DeliveryIdentity(
                    messageId, "runner-1/legacy", false, sequenceNumber, deliveryCount, enqueueTime);
        }

        @Test
        @DisplayName("count 0, enqueued well after the margin, replays (FR-005)")
        void should_replay_a_first_delivery_enqueued_well_after_the_margin() {
            parkThenForgetTheParkingTriple();
            final Instant parkedAt = row().updatedAt();

            // "msg-5" is the parking messageId itself - the old code's fallback would re-park this
            // outright as a redelivery of the exhausting identity. FR-005 does not look at it. An
            // hour clear of the margin, deliberately: unambiguous, nowhere near the boundary where
            // T034a (below) sits on purpose.
            final GuardDecision decision = guard.admit(command, legacyDelivery(
                    "msg-5", 5, 0L,
                    parkedAt.plus(ProcessedLogTestSupport.LEGACY_MARGIN).plus(Duration.ofHours(1))));

            assertThat(decision).isInstanceOf(GuardDecision.Run.class);
            final Row row = row();
            assertThat(row.status()).isEqualTo("RECEIVED");
            assertThat(row.attempts()).isEqualTo(PERMITTED_DELIVERIES + 1);
            assertThat(row.parkingSequenceNumber()).isNull();
        }

        @Test
        @DisplayName("count 1 or more re-parks, whatever the enqueue time (FR-005)")
        void should_re_park_a_redelivery_whatever_the_enqueue_time() {
            parkThenForgetTheParkingTriple();
            final Instant parkedAt = row().updatedAt();
            final Row before = row();

            // A fresh messageId - the old code's fallback would replay this outright.
            final GuardDecision decision = guard.admit(command, legacyDelivery(
                    "msg-6", 6, 1L, parkedAt.plus(ProcessedLogTestSupport.LEGACY_MARGIN).plusSeconds(1)));

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.PIPELINE_TRANSIENT_FAILURE));
            assertThat(row()).isEqualTo(before);
        }

        @Test
        @DisplayName("enqueue time not later than the margin re-parks (FR-005)")
        void should_re_park_a_first_delivery_enqueued_before_the_margin_elapses() {
            parkThenForgetTheParkingTriple();
            final Instant parkedAt = row().updatedAt();
            final Row before = row();

            final GuardDecision decision = guard.admit(command, legacyDelivery(
                    "msg-6", 6, 0L, parkedAt.plus(ProcessedLogTestSupport.LEGACY_MARGIN).minusSeconds(1)));

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.PIPELINE_TRANSIENT_FAILURE));
            assertThat(row()).isEqualTo(before);
        }

        @Test
        @DisplayName("no enqueue time at all re-parks (FR-005)")
        void should_re_park_a_first_delivery_with_no_enqueue_time() {
            parkThenForgetTheParkingTriple();
            final Row before = row();

            final GuardDecision decision = guard.admit(command, legacyDelivery("msg-6", 6, 0L, null));

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.PIPELINE_TRANSIENT_FAILURE));
            assertThat(row()).isEqualTo(before);
        }

        @Test
        @DisplayName("an enqueue time exactly equal to the margin re-parks - the boundary is "
                + "exclusive (T034)")
        void should_re_park_a_first_delivery_enqueued_exactly_at_the_margin() {
            parkThenForgetTheParkingTriple();
            final Instant parkedAt = row().updatedAt();
            final Row before = row();

            final GuardDecision decision = guard.admit(command, legacyDelivery(
                    "msg-6", 6, 0L, parkedAt.plus(ProcessedLogTestSupport.LEGACY_MARGIN)));

            assertThat(decision)
                    .as("later than the margin is required; exactly equal still re-parks")
                    .isEqualTo(new GuardDecision.DeadLetter(
                            DeadLetterReason.EXHAUSTED, ReasonCode.PIPELINE_TRANSIENT_FAILURE));
            assertThat(row()).isEqualTo(before);
        }

        /**
         * FR-005's own accepted residual risk, demonstrated rather than narrated (T034a): the broker
         * does not increment the delivery count when a message is released by a closed connection —
         * which this service causes deliberately on every stop and whenever it suspends intake — and
         * the enqueue-time test compares broker time with database time. A false replay needs both a
         * release without an increment <strong>and</strong> a broker clock ahead of the database
         * clock by more than the margin.
         *
         * <p>By design, the heuristic cannot tell this apart from the ordinary case above: the row
         * this test admits is decided by exactly the same two conditions, because the whole of
         * FR-005's accepted risk <em>is</em> that the heuristic cannot distinguish a clock-skewed
         * false positive from a genuine resubmission. What is deliberately different here is how
         * close to the boundary the enqueue time sits — one second past the margin, not an hour, as
         * above — because a second of apparent lead is exactly the order of clock skew between a
         * broker and a database that FR-005's own text accepts as plausible, where an hour would not
         * read as a clock discrepancy at all. This test exists so that a later reader who tries to
         * tighten the heuristic finds a named, reviewed case protecting today's accepted behaviour,
         * not merely an inference drawn from the test above.
         */
        @Test
        @DisplayName("a simulated broker-clock lead past the margin still replays - FR-005's "
                + "accepted residual risk (T034a)")
        void should_still_replay_under_a_simulated_broker_clock_lead_past_the_margin() {
            parkThenForgetTheParkingTriple();
            final Instant parkedAt = row().updatedAt();

            final GuardDecision decision = guard.admit(command, legacyDelivery(
                    "msg-5", 5, 0L, parkedAt.plus(ProcessedLogTestSupport.LEGACY_MARGIN).plusSeconds(1)));

            assertThat(decision)
                    .as("FR-005 cannot tell this apart from a genuine resubmission, and its own text "
                            + "does not claim to")
                    .isInstanceOf(GuardDecision.Run.class);
        }

        /**
         * US3 Acceptance Scenario 3: a legacy resubmission handed back before replay admission — the
         * store was unavailable and intake suspended, or a concurrent replay won the compare-and-set
         * — is judged afresh on its next delivery, not remembered. Nothing records "this delivery was
         * already seen and refused"; the broker's own redelivery count is the only state that moves.
         */
        @Test
        @DisplayName("a legacy resubmission handed back once is judged afresh, not remembered "
                + "(US3 Acceptance Scenario 3)")
        void should_judge_a_handed_back_legacy_resubmission_afresh_on_its_next_delivery() {
            parkThenForgetTheParkingTriple();
            final Instant qualifyingEnqueueTime =
                    row().updatedAt().plus(ProcessedLogTestSupport.LEGACY_MARGIN).plusSeconds(1);

            // The first attempt loses the compare-and-set (or finds the store unavailable) - stood in
            // for here, as FailedReplayIT's stale-decision case does, by a repository whose replay
            // statement always reports no row affected.
            final IdempotencyGuard contestedGuard = new IdempotencyGuard(
                    new ProcessedRequestRepository(ProcessedLogTestSupport.jdbcClient(), LEASE) {
                        @Override
                        public boolean replayFailed(
                                final RunClaim runClaim, final int observedAttempts,
                                final Long observedParkingSequenceNumber, final String auditNote) {
                            return false;
                        }
                    },
                    metrics, ProcessedLogTestSupport.LEGACY_MARGIN);
            final GuardDecision handedBack = contestedGuard.admit(
                    command, legacyDelivery("msg-6", 6, 0L, qualifyingEnqueueTime));
            assertThat(handedBack).isEqualTo(new GuardDecision.Abandon(ReasonCode.REPLAY_NOT_ADMITTED));
            assertThat(row().status())
                    .as("a delivery handed back for this reason leaves the row exactly as it was")
                    .isEqualTo("FAILED");

            // Its redelivery (broker count now 1, the same stored message) reaches the real guard and
            // is judged afresh against the row as it stands now - re-parked, because count is no
            // longer 0, not replayed on the strength of an earlier attempt nothing remembers.
            final GuardDecision redelivered = guard.admit(
                    command, legacyDelivery("msg-6", 6, 1L, qualifyingEnqueueTime));

            assertThat(redelivered)
                    .as("judged afresh: the same messageId that would have replayed at count 0 "
                            + "re-parks at count 1")
                    .isEqualTo(new GuardDecision.DeadLetter(
                            DeadLetterReason.EXHAUSTED, ReasonCode.PIPELINE_TRANSIENT_FAILURE));
        }

        /**
         * FR-005a / research.md R6 (T036): a row whose parking fields are present but stale — an
         * older consumer re-parked it without knowing these columns, so {@code parking_attempt_count}
         * no longer matches {@code attempts} — is decided by the legacy heuristic, not as exact, and
         * its stale {@code parking_sequence_number} is never read for the decision (only for the
         * compare-and-set's null-safe match against whatever is actually stored).
         */
        @Test
        @DisplayName("stale parking fields (attempts moved on) are judged by the legacy heuristic, "
                + "not as exact (FR-005a, T036)")
        void should_judge_a_stale_parking_row_by_the_legacy_heuristic() {
            exhaustEveryPermittedDelivery();
            final Row parked = row();
            // An older consumer re-parks this row with a statement that has no idea the parking
            // triple exists: attempts moves on, but parking_sequence_number/parking_attempt_count are
            // left exactly as this version's own exhaustion wrote them (5) - stale, now that
            // attempts is 6.
            final int restaled = ProcessedLogTestSupport.jdbcClient()
                    .sql("""
                            UPDATE processed_request
                               SET attempts = :attempts, failure_reason = :reason,
                                   exhausted_message_id = :messageId
                             WHERE source = :source AND request_id = :requestId
                            """)
                    .param("attempts", parked.attempts() + 1)
                    .param("reason", ReasonCode.PIPELINE_TRANSIENT_FAILURE.code())
                    .param("messageId", "msg-6")
                    .param("source", command.source())
                    .param("requestId", command.requestId())
                    .update();
            assertThat(restaled).isEqualTo(1);
            final Instant parkedAt = row().updatedAt();
            final Row before = row();
            assertThat(before.parkingSequenceNumber())
                    .as("the stale triple is still there, just no longer describing the current "
                            + "generation")
                    .isEqualTo(PERMITTED_DELIVERIES);

            // Delivery count 1, a different sequence number (99) from the stale parking_sequence_number
            // (5): if this row were (wrongly) judged exact, a differing sequence number would replay
            // it outright. Judged correctly by the legacy heuristic, delivery count 1 refuses it.
            final GuardDecision reParked = guard.admit(command, legacyDelivery(
                    "msg-99", 99, 1L, parkedAt.plus(ProcessedLogTestSupport.LEGACY_MARGIN).plusSeconds(1)));
            assertThat(reParked)
                    .as("the legacy heuristic refuses it on delivery count alone, not on sequence "
                            + "number")
                    .isEqualTo(new GuardDecision.DeadLetter(
                            DeadLetterReason.EXHAUSTED, ReasonCode.PIPELINE_TRANSIENT_FAILURE));
            assertThat(row()).isEqualTo(before);

            // Count 0, past the margin, carrying the STALE parking_sequence_number (5) itself: still
            // replayed, because the legacy heuristic never compares against it.
            final GuardDecision replayed = guard.admit(command, legacyDelivery(
                    "msg-100", PERMITTED_DELIVERIES, 0L,
                    parkedAt.plus(ProcessedLogTestSupport.LEGACY_MARGIN).plusSeconds(1)));
            assertThat(replayed)
                    .as("the stale parking_sequence_number is present but irrelevant to this row's "
                            + "decision")
                    .isInstanceOf(GuardDecision.Run.class);
        }

        /**
         * FR-010, T041: a legacy row has no recorded {@code parking_category} at all, so a re-park
         * reports {@link uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureCategoryMapping}'s own
         * answer for its stored reason, never the generic {@code exhausted}.
         */
        @Test
        @DisplayName("a legacy row with no recorded category is re-parked using the mapping "
                + "(FR-010, T041)")
        void should_re_park_a_legacy_row_using_the_category_mapping() {
            final RunClaim firstRun = runClaimOf(guard.admit(command, delivery("msg-1", 1)));
            guard.recordNonTransientFailure(firstRun, ReasonCode.OUTBOUND_CONTRACT_VIOLATION);
            final int cleared = ProcessedLogTestSupport.jdbcClient()
                    .sql("""
                            UPDATE processed_request
                               SET parking_sequence_number = NULL, parking_category = NULL,
                                   parking_attempt_count = NULL
                             WHERE source = :source AND request_id = :requestId
                            """)
                    .param("source", command.source())
                    .param("requestId", command.requestId())
                    .update();
            assertThat(cleared).isEqualTo(1);
            final Instant parkedAt = row().updatedAt();

            final GuardDecision reParked = guard.admit(command, legacyDelivery(
                    "msg-2", 2, 1L,
                    parkedAt.plus(ProcessedLogTestSupport.LEGACY_MARGIN).plus(Duration.ofHours(1))));

            assertThat(reParked)
                    .as("OUTBOUND_CONTRACT_VIOLATION maps to non-transient; nothing recorded the "
                            + "category directly, so the mapping answers it")
                    .isEqualTo(new GuardDecision.DeadLetter(
                            DeadLetterReason.NON_TRANSIENT, ReasonCode.OUTBOUND_CONTRACT_VIOLATION));
        }

        /**
         * FR-005a's own extension of FR-010: a row whose parking triple is physically present but
         * stale (attempts has moved on) must not have its {@code parking_category} trusted either,
         * even though the column itself is non-null — the same "decide as though absent" rule FR-005
         * already applies to the sequence number applies to the category too. Deliberately records a
         * category the mapping would <em>not</em> have produced from the same reason, so a guard that
         * wrongly trusted the stale column would report something this test can tell apart from the
         * mapping's own answer.
         */
        @Test
        @DisplayName("a stale recorded category is never trusted either - FR-005a decides it as "
                + "though absent, same as the sequence number (FR-010)")
        void should_ignore_a_stale_recorded_category_and_use_the_mapping_instead() {
            exhaustEveryPermittedDelivery();
            final Row parked = row();
            assertThat(parked.parkingCategory())
                    .as("this version's own exhaustion recorded EXHAUSTED")
                    .isEqualTo("EXHAUSTED");
            // An older consumer re-parks over it: attempts moves on, the stored reason becomes one
            // that maps to NON_TRANSIENT - deliberately disagreeing with the still-present, now-stale
            // "EXHAUSTED" category, so trusting the column would read as a different answer.
            final int restaled = ProcessedLogTestSupport.jdbcClient()
                    .sql("""
                            UPDATE processed_request
                               SET attempts = :attempts, failure_reason = :reason
                             WHERE source = :source AND request_id = :requestId
                            """)
                    .param("attempts", parked.attempts() + 1)
                    .param("reason", ReasonCode.OUTBOUND_CONTRACT_VIOLATION.code())
                    .param("source", command.source())
                    .param("requestId", command.requestId())
                    .update();
            assertThat(restaled).isEqualTo(1);
            assertThat(row().parkingCategory())
                    .as("the stale category is still physically there, non-null")
                    .isEqualTo("EXHAUSTED");
            final Instant parkedAt = row().updatedAt();

            final GuardDecision reParked = guard.admit(command, legacyDelivery(
                    "msg-9", 9, 1L, parkedAt.plus(ProcessedLogTestSupport.LEGACY_MARGIN).plusSeconds(1)));

            assertThat(reParked)
                    .as("decided as though no category were recorded at all: the mapping's own "
                            + "answer for OUTBOUND_CONTRACT_VIOLATION, non-transient - not the stale "
                            + "EXHAUSTED the column still physically holds")
                    .isEqualTo(new GuardDecision.DeadLetter(
                            DeadLetterReason.NON_TRANSIENT, ReasonCode.OUTBOUND_CONTRACT_VIOLATION));
        }
    }
}
