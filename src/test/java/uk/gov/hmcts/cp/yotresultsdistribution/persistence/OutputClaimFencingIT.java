package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.hmcts.cp.yotresultsdistribution.application.IdempotencyGuard;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GuardDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ProcessedOutputClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;

/**
 * The submission half of the processed log is fenced on the request claim, exactly as the request
 * half is.
 *
 * <p>{@link StaleRunnerRejectionIT} proves a superseded runner cannot settle the <em>request</em>.
 * That is only half the guarantee, and the cheaper half: the row that decides whether a register is
 * sent again is the output row, and until it carries the same fence a runner whose claim was
 * reclaimed while it worked can still claim it, replace the digest of what the winner is about to
 * send, and settle it POSTED or FAILED underneath the runner that actually holds the request.
 *
 * <p>Each of those three is a way to lose or duplicate a register:
 *
 * <ul>
 *   <li><strong>Claim.</strong> The loser re-claims the row for a body only it assembled, so the
 *       digest and the anomaly summary describe an attempt nobody makes.</li>
 *   <li><strong>Settle POSTED.</strong> The winner's POST has not happened yet, and the row that
 *       says it has is the row that stops the register being sent at all.</li>
 *   <li><strong>Settle FAILED.</strong> The winner's POST may already have been accepted, and a
 *       row moved out of PENDING by somebody else invites the next delivery to re-claim it and
 *       POST a second, non-idempotent {@code add-court-register}.</li>
 * </ul>
 *
 * <p>The fence is therefore the request's own claim — owner <em>and</em> token — asserted against
 * {@code processed_request} inside each statement, so the database decides and no read-then-write
 * window exists for two runners to overlap in. Liveness stays where the data model puts it: in SQL,
 * against {@code now()}, in the reclaim statement. This suite never compares a JVM clock reading
 * against a stored timestamp; it ages the claim through the database and lets the guard reclaim it.
 */
@DisplayName("processed_output writes are fenced on the request claim")
class OutputClaimFencingIT {

    private static final Duration LEASE = Duration.ofMinutes(5);

    private static final UUID COURT_CENTRE = UUID.fromString("6b1d3f2a-88c4-4a2e-9f10-2d7c5b9e0a41");
    private static final String OU_CODE = "B01LY";
    private static final LocalDate REGISTER_DATE = LocalDate.of(2026, 8, 20);
    private static final String FILE_NAME = "court-register-B01LY-20260820.pdf";

    private static final String SUPERSEDED_DIGEST =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
    private static final String WINNING_DIGEST =
            "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752";

    private static final int ACCEPTED = 202;
    private static final int REFUSED = 400;

    private static final int RACERS = 2;

    /** The two ways a run settles its output row, both of which the fence has to cover. */
    private enum OutputWrite {
        POSTED, FAILED
    }

    private final ExecutorService executor = Executors.newFixedThreadPool(RACERS);
    private final IdempotencyGuard guard = ProcessedLogTestSupport.guard(LEASE);
    private final DistributionCommand command = ProcessedLogTestSupport.command();

    /** The runner that was working when its claim lapsed. */
    private RunClaim superseded;

    /** The runner that reclaimed the request and holds it now. */
    private RunClaim winner;

    @BeforeAll
    static void migrate() {
        PostgresTestSupport.applyFlyway();
    }

    @BeforeEach
    void supersedeARunner() {
        superseded = runClaimOf(guard.admit(command, new DeliveryIdentity("msg-1", "runner-1")));
        ProcessedLogTestSupport.expireClaim(command.source(), command.requestId());
        winner = runClaimOf(guard.admit(command, new DeliveryIdentity("msg-2", "runner-2")));
    }

    @AfterEach
    void shutDown() {
        executor.shutdownNow();
    }

    private static RunClaim runClaimOf(final GuardDecision decision) {
        assertThat(decision).isInstanceOf(GuardDecision.Run.class);
        return ((GuardDecision.Run) decision).claim();
    }

    private static ProcessedOutputRepository repository() {
        return new ProcessedOutputRepository(ProcessedLogTestSupport.jdbcClient());
    }

    private static ProcessedOutputClaim outputClaim(final String digest) {
        return new ProcessedOutputClaim(
                UUID.randomUUID(), COURT_CENTRE, OU_CODE, REGISTER_DATE, FILE_NAME, digest, null);
    }

    private boolean settle(final OutputWrite write, final RunClaim claim) {
        return switch (write) {
            case POSTED -> repository().recordPosted(claim, ACCEPTED);
            case FAILED -> repository().recordFailed(claim, REFUSED);
        };
    }

    private record Row(String status, Integer responseCode, String requestDigest) {
    }

    private Optional<Row> row() {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT status, response_code, request_digest
                          FROM processed_output
                         WHERE source = :source AND request_id = :requestId
                        """)
                .param("source", command.source())
                .param("requestId", command.requestId())
                .query((rs, rowNumber) -> new Row(
                        rs.getString("status"),
                        rs.getObject("response_code", Integer.class),
                        rs.getString("request_digest")))
                .optional();
    }

    private Row requireRow() {
        return row().orElseThrow(() -> new IllegalStateException(
                "no processed_output row for " + command.source() + "/" + command.requestId()));
    }

    @Test
    @DisplayName("a superseded runner cannot claim the output at all")
    void a_superseded_runner_should_not_be_able_to_claim_the_output() {
        final boolean claimed =
                repository().claimPending(superseded, outputClaim(SUPERSEDED_DIGEST));

        assertThat(claimed)
                .as("the runner that no longer holds the request claim has nothing to submit for "
                        + "it, so it must not be told it may POST")
                .isFalse();
        assertThat(row())
                .as("a refused claim writes no evidence: a PENDING row for an attempt nobody makes "
                        + "is a register support would go looking for")
                .isEmpty();
    }

    @Test
    @DisplayName("a superseded runner cannot replace the digest of what the winner is sending")
    void a_superseded_runner_should_not_be_able_to_re_digest_the_winners_row() {
        repository().claimPending(winner, outputClaim(WINNING_DIGEST));

        final boolean reclaimed =
                repository().claimPending(superseded, outputClaim(SUPERSEDED_DIGEST));

        assertThat(reclaimed).isFalse();
        assertThat(requireRow().requestDigest())
                .as("the digest is the evidence of the bytes actually about to be sent; a "
                        + "superseded runner overwriting it leaves the log describing an attempt "
                        + "that never happened")
                .isEqualTo(WINNING_DIGEST);
        assertThat(requireRow().status()).isEqualTo("PENDING");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(OutputWrite.class)
    @DisplayName("a superseded runner cannot settle the winner's output row")
    void a_superseded_runner_should_not_be_able_to_settle_the_output(final OutputWrite write) {
        repository().claimPending(winner, outputClaim(WINNING_DIGEST));

        final boolean settled = settle(write, superseded);

        assertThat(settled)
                .as("the loser of an overlap is told its write affected nothing")
                .isFalse();
        final Row row = requireRow();
        assertThat(row.status())
                .as("the row is left exactly as the runner that holds the claim left it")
                .isEqualTo("PENDING");
        assertThat(row.responseCode()).isNull();
        assertThat(row.requestDigest()).isEqualTo(WINNING_DIGEST);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(OutputWrite.class)
    @DisplayName("the runner that holds the claim settles its own output row")
    void the_current_runner_should_still_settle_its_own_output(final OutputWrite write) {
        repository().claimPending(winner, outputClaim(WINNING_DIGEST));

        final boolean settled = settle(write, winner);

        assertThat(settled).isTrue();
        assertThat(requireRow().status()).isEqualTo(write.name());
    }

    @Test
    @DisplayName("a superseded runner cannot settle an output row it claimed before it was lapped")
    void a_superseded_runner_should_not_settle_a_row_it_claimed_before_being_lapped() {
        // The ordering the review names: A claims the request and its output, A's lease expires, B
        // reclaims the request and re-claims the output with its own digest. A is still running and
        // its POST still returns — and it must change nothing.
        final ProcessedOutputRepository repository = repository();
        repository.claimPending(superseded, outputClaim(SUPERSEDED_DIGEST));
        repository.claimPending(winner, outputClaim(WINNING_DIGEST));

        final boolean posted = repository.recordPosted(superseded, ACCEPTED);
        final boolean failed = repository.recordFailed(superseded, REFUSED);

        assertThat(posted).isFalse();
        assertThat(failed).isFalse();
        final Row row = requireRow();
        assertThat(row.status()).isEqualTo("PENDING");
        assertThat(row.requestDigest()).isEqualTo(WINNING_DIGEST);
    }

    /**
     * The claim and the skip are one statement, so two claimants arriving together are decided by
     * the database rather than by whichever of them read the row first.
     *
     * <p>Both threads are released off a barrier, exactly as two pods would be. Only one of them
     * holds the request claim, and that is the whole of the decision: the other is refused however
     * the two happen to interleave.
     */
    @Test
    @DisplayName("two claimants racing for one output produce exactly one winner")
    void two_concurrent_claimants_should_produce_exactly_one_output_claim() throws Exception {
        final CyclicBarrier startLine = new CyclicBarrier(RACERS);
        final List<Callable<Boolean>> claimants = List.of(
                claimant(startLine, superseded, SUPERSEDED_DIGEST),
                claimant(startLine, winner, WINNING_DIGEST));

        final List<Boolean> admitted = new ArrayList<>();
        for (final Future<Boolean> outcome : executor.invokeAll(claimants)) {
            admitted.add(outcome.get());
        }

        assertThat(admitted)
                .as("exactly one of two concurrent claimants may POST this hearing's register")
                .containsExactly(false, true);
        assertThat(requireRow().requestDigest())
                .as("and the row describes the winner's body, not the loser's")
                .isEqualTo(WINNING_DIGEST);
    }

    private Callable<Boolean> claimant(
            final CyclicBarrier startLine, final RunClaim claim, final String digest) {
        return () -> {
            final ProcessedOutputRepository repository = repository();
            startLine.await();
            return repository.claimPending(claim, outputClaim(digest));
        };
    }
}
