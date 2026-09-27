package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import uk.gov.hmcts.cp.yotresultsdistribution.application.IdempotencyGuard;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RecordedCompletion;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterStore;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletionReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterHearingVenue;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GuardDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport.Row;

/**
 * The accepted crash window: a run finishes, the pod dies before its outcome is written, and the
 * redelivery runs the request again.
 *
 * <p>What the service promises is not a bounded number of runs but their shape — never two at once,
 * and never any run once COMPLETED has been durably recorded. Both halves are asserted here, and the
 * crash is simulated the only honest way: the outcome write simply never happens.
 *
 * <p>Sequencing is proven by what the guard refuses. While the crashed runner's claim is still live,
 * the redelivery is handed back rather than run; only once the claim has lapsed does a second run
 * become possible. That ordering is the whole of "never concurrent" at this layer.
 *
 * <p><strong>The narrowest crash of all is the one inside the last stage</strong>, and it is
 * asserted here against the real store rather than against the guard alone: a run that recorded a
 * register and died as its completion was written. There is no such moment - the recording and the
 * completion are one transaction (data-model.md) - so what a redelivery finds is both writes or
 * neither, never a register standing against a request the broker is about to deliver again.
 */
class CrashWindowIT {

    private static final Duration LEASE = Duration.ofMinutes(5);

    /** The court centre's OU code, which the document has no field for and the batch needs. */
    private static final String OU_CODE = "B01LY00";

    /** The day the register covers, and the instant inside it the register was assembled at. */
    private static final LocalDate REGISTER_DATE = LocalDate.of(2026, 8, 24);

    private static final Instant REGISTER_TIME = Instant.parse("2026-08-24T09:00:00Z");

    private static final Instant HEARING_DATE = Instant.parse("2026-08-19T00:00:00Z");

    private final DistributionCommand command = ProcessedLogTestSupport.command();
    private final IdempotencyGuard guard = ProcessedLogTestSupport.guard(LEASE);

    /** This case's court centre, minted here so no other suite can read the rows it writes. */
    private final UUID courtCentre = UUID.randomUUID();

    private final RegisterStore store = new JdbcRegisterStore(
            ProcessedLogTestSupport.jdbcClient(), ProcessedLogTestSupport.transactionManager());

    private Row row() {
        return ProcessedLogTestSupport.requireRow(command.source(), command.requestId());
    }

    private static RunClaim runClaimOf(final GuardDecision decision) {
        assertThat(decision).isInstanceOf(GuardDecision.Run.class);
        return ((GuardDecision.Run) decision).claim();
    }

    private static DeliveryIdentity delivery(final int number) {
        return new DeliveryIdentity("msg-" + number, "runner-" + number + "/delivery-" + number);
    }

    /**
     * A run that started and never recorded anything, leaving a claim nobody will release — aged an
     * hour into the past by the database's clock, which is what the next delivery finds.
     */
    private RunClaim crashedRun(final int number) {
        final RunClaim claim = runClaimOf(guard.admit(command, delivery(number)));
        ProcessedLogTestSupport.expireClaim(command.source(), command.requestId());
        return claim;
    }

    @Test
    @DisplayName("while the crashed runner's claim is still live, the redelivery waits")
    void a_redelivery_should_not_run_concurrently_with_a_claim_that_is_still_live() {
        runClaimOf(guard.admit(command, delivery(1)));

        final GuardDecision redelivery = guard.admit(command, delivery(2));

        assertThat(redelivery).isEqualTo(new GuardDecision.Abandon(ReasonCode.CLAIM_NOT_ACQUIRED));
        assertThat(row().attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("once the claim has lapsed, the redelivery runs — one further run, in sequence")
    void a_redelivery_should_rerun_the_request_after_the_claim_lapses() {
        crashedRun(1);

        final RunClaim rerun = runClaimOf(guard.admit(command, delivery(2)));
        guard.recordCompletion(rerun, CompletionReason.SUBMITTED);

        final Row row = row();
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.completionReason()).isEqualTo("submitted");
        assertThat(row.attempts()).isEqualTo(2);
        assertThat(row.claimOwner()).isNull();
    }

    @Test
    @DisplayName("a crash repeated in the same window repeats the run, one at a time")
    void repeated_crashes_should_produce_repeated_sequential_runs() {
        crashedRun(1);
        crashedRun(2);
        crashedRun(3);

        final Row row = row();
        assertThat(row.attempts()).isEqualTo(3);
        assertThat(row.status()).isEqualTo("RECEIVED");
        // Three runs started, one claim: the row can only ever hold the newest.
        assertThat(row.claimOwner()).isEqualTo("runner-3/delivery-3");
    }

    @Test
    @DisplayName("no run happens once COMPLETED is durable, however many redeliveries arrive")
    void a_completed_record_should_end_the_window() {
        final RunClaim first = runClaimOf(guard.admit(command, delivery(1)));
        guard.recordCompletion(first, CompletionReason.GROUP_PROCEEDINGS);

        final GuardDecision redelivery = guard.admit(command, delivery(2));

        assertThat(redelivery).isEqualTo(new GuardDecision.Complete(ReasonCode.ALREADY_COMPLETED));
        assertThat(row().attempts()).isEqualTo(1);
    }

    /**
     * The crash the recording stage cannot have: between the register and its command's completion.
     *
     * <p>The completion is written inside the recording's transaction, so a pod that dies as it is
     * written takes the register with it. The redelivery therefore finds a request it has never
     * recorded anything for, runs it, and ends with both writes - which is what "either both or
     * neither" means from the broker's side.
     *
     * <p>The crash is simulated the way this suite simulates every other one: the write simply
     * fails. A completion that could not be written arrives as {@link StoreUnavailableException},
     * because the guard writes through the same package the store does.
     */
    @Test
    @DisplayName("a crash between the register and its completion leaves neither of them")
    void a_crash_between_the_register_and_its_completion_should_leave_neither() {
        PostgresTestSupport.applyFlyway();
        final RunClaim crashed = runClaimOf(guard.admit(command, delivery(1)));

        assertThatThrownBy(() -> store.recordAndComplete(command, register(), OU_CODE, "Applicant",
                RecordedFlagState.ON, () -> {
                    throw new StoreUnavailableException("the completion could not be written",
                            new DataAccessResourceFailureException("connection reset by peer"));
                }))
                .as("the crash is the completion write, and it is not swallowed")
                .isInstanceOf(StoreUnavailableException.class);

        assertThat(registers())
                .as("neither: the register went back with the completion that never landed")
                .isZero();
        assertThat(row().status())
                .as("and the request is exactly where the crashed run left it")
                .isEqualTo("RECEIVED");

        ProcessedLogTestSupport.expireClaim(command.source(), command.requestId());
        final RunClaim rerun = runClaimOf(guard.admit(command, delivery(2)));
        final RecordedCompletion recorded = store.recordAndComplete(command, register(), OU_CODE,
                "Applicant", RecordedFlagState.ON,
                () -> guard.recordCompletion(rerun, CompletionReason.RECORDED));

        assertThat(recorded.completion()).isInstanceOf(GuardDecision.Complete.class);
        assertThat(registers())
                .as("or both: the redelivery recorded the register and completed the command in "
                        + "one transaction, and the crashed run left nothing for it to trip over")
                .isEqualTo(1);
        assertThat(crashed.token()).isNotEqualTo(rerun.token());
        final Row row = row();
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.completionReason()).isEqualTo("recorded");
    }

    /** How many registers this command holds, which is 0 or 1 and never anything else. */
    private long registers() {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT count(*)
                          FROM processed_output
                         WHERE source = :source AND request_id = :requestId
                        """)
                .param("source", command.source())
                .param("requestId", command.requestId())
                .query(Long.class)
                .single();
    }

    /** One hearing's register, carrying the least a recording needs and nothing about a person. */
    private CourtRegisterDocument register() {
        return new CourtRegisterDocument(
                REGISTER_TIME.toString(),
                HEARING_DATE.toString(),
                command.hearingId().toString(),
                courtCentre.toString(),
                "court-register_" + REGISTER_DATE + '_' + OU_CODE + ".pdf",
                null,
                new CourtRegisterHearingVenue("Lavender Hill LJA", "Lavender Hill Youth Court",
                        null),
                List.of(new CourtRegisterRecipient(
                        "Wandsworth Youth Offending Team", "yot@example.gov.uk", null,
                        "cr_standard")),
                List.of(new CourtRegisterDefendant(
                        "b2b3f5a1-6c9d-4e21-8a7f-3d5c1e9b0426", "SMITH, John", "2008-04-11",
                        null, null, null, "MALE", "Not Applicable", null, null,
                        List.of(), List.of(), List.of(), List.of())));
    }
}
