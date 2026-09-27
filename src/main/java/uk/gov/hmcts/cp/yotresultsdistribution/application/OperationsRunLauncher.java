package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.LockSupport;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterRegenerationService.RegenerationTally;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterRegenerationService.Selection;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.FeatureFlagGate;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.RegisterGenerationJob;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.RunCorrelation;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision.Proceed;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision.Reason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision.Skipped;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException;

/**
 * The {@code 202} hand-off: validate, read the one lever, mint a run id, and get off the thread.
 *
 * <p>A regeneration POSTs {@code generate-document} for every batch of a day under a deadline
 * measured in tens of minutes, and no ingress or gateway will hold a connection that long
 * (research R16). So the caller is answered with a run id as soon as the two things a caller can
 * act on are known - whether the arguments were usable, and whether the flag admits the run - and
 * the work itself happens on the generation scheduler's own single thread, which is the same
 * thread the 18:00 run happens on. What the run then did is read back from the run line and from
 * {@code GET /operations/batches?date=D}.
 *
 * <p><strong>The lock is taken, not asked about</strong> (research R12). Reading the
 * {@code shedlock} row and refusing if it is held is a check-then-act against a scheduler that can
 * start in the gap. The background task therefore acquires the schedule's own lock, by its own
 * name, through the {@link LockProvider} the schedule uses - a non-blocking attempt by default,
 * bounded by {@code yotresultsdistribution.operations.lock-wait} where a deployment asks for one - and
 * releases it in a {@code finally}. A lock it cannot take is not an error: it is
 * {@link OperationsReason#SCHEDULE_RUNNING}, recorded as the run's outcome, and two launches for
 * one date on two replicas end with one of them saying that rather than with a failure.
 *
 * <p><strong>Ids before calls.</strong> The run id is minted and written down before the work is
 * submitted, so an outcome that arrives can always be correlated with the answer the caller was
 * given. And that same id is the correlation the whole background run is written under: the task
 * is submitted inside {@link RunCorrelation#under(String, Runnable)}, so every line the requesting
 * leg, the store, the assembler and the two clients write during an operator's run carries
 * {@code runId} exactly as it does under the 18:00 job. An id on four lines of this class and on
 * none of the work's would be a run that could not be read as one thing, which is the whole point
 * of having minted it.
 *
 * <p><strong>The background run records its own outcome and lets nothing leave.</strong> An
 * exception thrown on an executor's thread goes into a {@code Future} nobody reads, which is
 * precisely the silence this service exists to end - so the top of the submitted task is a
 * settlement boundary in the sense the message listener's is: every ending is classified and
 * written down under a bounded code, and none of them is dropped.
 */
public class OperationsRunLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(OperationsRunLauncher.class);

    /** This service's own name for the argument an override without a batch offends. */
    public static final String IGNORE_FLAG = "ignoreFlag";

    /** The one event name a launched run's lines are indexed under. */
    private static final String RUN_EVENT = "operations_generation_run";

    /** What the line calls a run a person asked for, as against one the schedule fired. */
    private static final String OPERATOR = "operator";

    /** What the line calls the reading a run goes ahead on when nobody overrode anything. */
    private static final String FLAG_ON = "flag-on";

    /** The one extra a refusal carries that every line already says for itself. */
    private static final String DATE = "date";

    /** How long the bounded wait sleeps between attempts at the schedule's lock. */
    private static final Duration POLL = Duration.ofMillis(20);

    /** The regeneration itself, which happens on the generation scheduler's own thread. */
    private final RegisterRegenerationService regeneration;

    /** The one lever's gate, asked exactly where {@code generate-register} asked it. */
    private final FeatureFlagGate gate;

    /** The schedule's own lock provider, so the lock is the schedule's lock and not a second one. */
    private final LockProvider locks;

    /** How long the lock is held for at most, which is the value the schedule is validated on. */
    private final Duration lockAtMostFor;

    /** How long the background run waits for the lock before recording that it could not have it. */
    private final Duration lockWait;

    /** The generation scheduler's executor, which is where the work happens and never here. */
    private final Executor generationExecutor;

    /** This pod's reading of now, which the lock configuration is stamped with. */
    private final Clock clock;

    /**
     * Creates the hand-off over the run, the lever, the lock and the thread the work happens on.
     *
     * @param regenerationService the regeneration the background run makes
     * @param featureFlagGate     the one lever's gate, read once per run and uncached
     * @param lockProvider        the schedule's own lock provider
     * @param generationLockAtMostFor how long the lock is held for at most
     * @param operationsLockWait  how long to wait for the lock before recording the refusal
     * @param executor            the generation scheduler's executor
     * @param runClock            this pod's reading of now
     */
    public OperationsRunLauncher(final RegisterRegenerationService regenerationService,
            final FeatureFlagGate featureFlagGate, final LockProvider lockProvider,
            final Duration generationLockAtMostFor, final Duration operationsLockWait,
            final Executor executor, final Clock runClock) {

        this.regeneration = regenerationService;
        this.gate = featureFlagGate;
        this.locks = lockProvider;
        this.lockAtMostFor = generationLockAtMostFor;
        this.lockWait = operationsLockWait;
        this.generationExecutor = executor;
        this.clock = runClock;
    }

    /**
     * Admits the run, writes its id down, and hands the work to the generation scheduler.
     *
     * @param selection the day, the narrowing and whether an override was asked for
     * @return the run id the caller correlates the run line by
     * @throws OperationsRefusedException where an override was asked for without the one batch it
     *         may cover, or where the flag did not admit the run
     */
    public RunAccepted launch(final Selection selection) {
        if (selection.ignoreFlag() && selection.batchId() == null) {
            LOG.warn("An override was asked for over a whole register date rather than over one "
                    + "batch of it, and was refused. reason={}",
                    OperationsReason.OVERRIDE_REQUIRES_BATCH.wire());
            throw new OperationsRefusedException(OperationsReason.OVERRIDE_REQUIRES_BATCH,
                    IGNORE_FLAG, null);
        }
        final boolean overridden = admittedBy(gate.decide(selection.ignoreFlag()));
        // Ids before calls: the id the caller is answered with exists, and is written down, before
        // anything is submitted that could produce an outcome to correlate against it.
        final String runId = UUID.randomUUID().toString();
        LOG.info("event={} run_id={} trigger={} date={} reason={} outcome=accepted", RUN_EVENT,
                runId, OPERATOR, selection.registerDate(),
                overridden ? Reason.OVERRIDDEN.code() : FLAG_ON);
        generationExecutor.execute(
                () -> RunCorrelation.under(runId, () -> run(runId, selection, overridden)));
        return new RunAccepted(runId, selection.registerDate(), overridden);
    }

    /**
     * Whether the run goes ahead, and whether it goes ahead over a flag that says otherwise.
     *
     * <p>The gate is asked before anything is read, released or assembled, and it is asked the
     * same question the schedule asks it - and the override is passed to it rather than acted on
     * before it, so an override on a stack that is already cut over overrides nothing.
     *
     * @param decision what the gate said
     * @return true where the run goes ahead over a flag that would have stopped it
     * @throws OperationsRefusedException where the flag did not admit the run
     */
    private static boolean admittedBy(final GateDecision decision) {
        return switch (decision) {
            case Proceed proceed -> proceed.overridden();
            case Skipped skipped -> throw new OperationsRefusedException(refusalOf(skipped));
        };
    }

    /**
     * The bounded code one skipped reading is refused under.
     *
     * @param skipped what the gate said and why
     * @return the reason the caller is told, which is the gate's own code
     */
    private static OperationsReason refusalOf(final Skipped skipped) {
        return skipped.reason() == Reason.FLAG_UNREADABLE
                ? OperationsReason.FLAG_UNREADABLE
                : OperationsReason.FLAG_OFF;
    }

    /**
     * The background run: take the schedule's lock, regenerate the day, give the lock back.
     *
     * <p>Nothing leaves this method, because there is nowhere for it to leave to. Every ending is
     * written down under the bounded code it belongs to and the lock is given back in a
     * {@code finally}, so a run that failed cannot also leave the night's lock held.
     *
     * @param runId      the id the caller was answered with
     * @param selection  what the run is about
     * @param overridden whether it goes ahead over a flag that would have stopped it
     */
    // PMD.AvoidCatchingGenericException: this is the top of a thread. A throwable that left here
    // would go into a Future nobody reads, which is the silence this service exists to end; both
    // catches classify and record, and neither continues as though nothing had happened.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void run(final String runId, final Selection selection, final boolean overridden) {
        final Optional<SimpleLock> held = acquired();
        if (held.isEmpty()) {
            LOG.warn("event={} run_id={} trigger={} date={} outcome={}", RUN_EVENT, runId, OPERATOR,
                    selection.registerDate(), OperationsReason.SCHEDULE_RUNNING.wire());
            return;
        }
        try {
            final RegenerationTally tally = regeneration.regenerate(selection, overridden);
            // The one line a night is read from, whoever asked for it. The run report carries
            // trigger=operator and, where the flag was overridden, reason=overridden; what a
            // regeneration knows beyond the schedule - the day, the batches it would not release -
            // is on the line beside it, because the report's fields are the schedule's and a
            // regeneration does not get to widen them.
            RegisterGenerationJob.recorded(tally.report(), runId);
            LOG.info("event={} run_id={} trigger={} date={} withheld={} outcome=generated",
                    RUN_EVENT, runId, OPERATOR, tally.registerDate(), tally.withheld().size());
        } catch (OperationsRefusedException refused) {
            LOG.error("event={} run_id={} trigger={} date={} outcome={}{}", RUN_EVENT, runId,
                    OPERATOR, selection.registerDate(), refused.reason().wire(),
                    recorded(refused));
        } catch (RuntimeException defect) {
            LOG.error("event={} run_id={} trigger={} date={} outcome={} cause={}", RUN_EVENT, runId,
                    OPERATOR, selection.registerDate(),
                    OperationsReason.GENERATION_FAILED.wire(), defect.getClass().getName());
        } finally {
            held.get().unlock();
        }
    }

    /**
     * What a run that stopped had already written down, as its own line carries it.
     *
     * <p>The caller was answered {@code 202} before any work began, so there is no status left to
     * carry a partial tally and no later response that could: this line is the only place the day
     * a stopped run left behind can be read. The extras are the refusal's own - counts and an
     * identifier, bounded where they were composed (constitution Principle VII) - and nothing is
     * rendered that the refusal did not carry.
     *
     * <p>The day is skipped because the line already says it, and a key written twice is a key a
     * log index reads once.
     *
     * @param refused what the regeneration refused under, with whatever it had written down
     * @return the extras as {@code key=value} pairs, each with its own leading space, or nothing
     *         at all where the refusal carried none
     */
    private static String recorded(final OperationsRefusedException refused) {
        final StringBuilder line = new StringBuilder();
        refused.properties().entrySet().stream()
                .filter(carried -> !DATE.equals(carried.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(carried -> line.append(' ').append(carried.getKey()).append('=')
                        .append(carried.getValue()));
        return line.toString();
    }

    /**
     * The schedule's own lock, attempted once or until the bounded wait runs out.
     *
     * <p>A zero wait - the default - is one non-blocking attempt, which is the only kind that can
     * be made without holding a thread for somebody else's run. The wait is measured on elapsed
     * time rather than on the injected clock, because the clock a run is correlated by may be a
     * fixed one and a budget nothing advances is a budget that never runs out.
     *
     * @return the lock, or empty where the schedule or another replica holds it
     */
    private Optional<SimpleLock> acquired() {
        final long giveUpAt = System.nanoTime() + lockWait.toNanos();
        Optional<SimpleLock> held = locks.lock(configuration());
        while (held.isEmpty() && System.nanoTime() < giveUpAt) {
            LockSupport.parkNanos(Math.min(POLL.toNanos(), giveUpAt - System.nanoTime()));
            held = locks.lock(configuration());
        }
        return held;
    }

    /**
     * The lock this run takes, which is the schedule's by name and by duration.
     *
     * @return the configuration, stamped with this pod's reading of now
     */
    private LockConfiguration configuration() {
        return new LockConfiguration(clock.instant(), RegisterGenerationJob.LOCK_NAME,
                lockAtMostFor, Duration.ZERO);
    }

    /**
     * What a caller is answered with when a regeneration has been accepted.
     *
     * @param runId        the id the run line is correlated by
     * @param registerDate the day the run is about, as this service parsed it
     * @param overridden   whether it goes ahead over a flag that would have stopped it
     */
    public record RunAccepted(String runId, LocalDate registerDate, boolean overridden) {
    }
}
