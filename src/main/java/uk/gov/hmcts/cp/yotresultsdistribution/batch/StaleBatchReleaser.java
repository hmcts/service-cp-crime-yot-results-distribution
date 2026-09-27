package uk.gov.hmcts.cp.yotresultsdistribution.batch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterStore;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ReleasedBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.application.StaleReleaseProgress;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationMetrics;

/**
 * The run's first act: giving up on the batches the night before did not finish.
 *
 * <p>A batch still awaiting its render when the next run begins, and awaiting it for longer than
 * the minimum age, is failed under {@code NOT_COMPLETED_BY_NEXT_RUN} and its registers are given
 * back, so this same run's assembly puts them in a batch tonight and the court centre gets its
 * document tonight. Nothing is asked of systemdocgenerator: an outcome that was lost is not an
 * outcome anybody can be asked for.
 *
 * <p>The pass holds a clock, the store port and the instruments, and nothing else - no driver, no
 * broker client, no HTTP client (design rules: the job is application code too). The decision it
 * makes is which two instants to ask about; the decision about any one batch is the store's, in one
 * fenced statement per batch.
 *
 * <p><strong>Nothing about one batch may end the night.</strong> The store isolates each batch and
 * reports the ones it could not release rather than raising them, so this pass counts those, says
 * each once at WARN by identity, and goes on: the run behind it has the rest of the country's
 * documents to make (FR-003a). A contended batch is stale still and untouched, so the next run
 * reaches it again, and the 07:00 report names its court centre day every morning meanwhile. What
 * does leave this pass is a store that went away - that is the run's own failure, reported on its
 * line and rethrown, exactly as every other read the run cannot make is.
 *
 * <p><strong>Two cutoffs, computed once per pass from this pass's own clock.</strong> PENDING and
 * GENERATING are <strong>one</strong> rule and not two: with nothing left to ask systemdocgenerator
 * there is no question that could tell them apart, and "it did not complete before the next run
 * began" is true of both - of the batch whose outcome was lost, and of the batch whose render
 * request was never recorded, including the one that never minted a payload at all, which the
 * retired reads excluded and left deferring its court centre day at every run for ever (FR-020).
 * GENERATED is on neither arm at any age: it holds a document somebody is owed e-mails about, and
 * failing it would throw that document away (FR-002).
 *
 * <p>A batch the schedule made is judged by the minimum age. A batch an operator asked for is
 * judged by the longer of that and the run's own lock duration, because a manual generation holds
 * no run lock and has the whole requesting deadline to work in (FR-017). Which cutoff a batch is
 * judged by is the store's to decide from {@code system_generated}, in the statement's own
 * predicate; both instants are computed here, once, so that every batch in one pass is judged
 * against the same moment.
 */
public class StaleBatchReleaser {

    private static final Logger LOG = LoggerFactory.getLogger(StaleBatchReleaser.class);

    /** A pass that was told about no batch at all, which is not the same as a quiet night. */
    private static final ReleaseTally NOTHING = new ReleaseTally(0, 0, 0);

    /**
     * What a caller that keeps no account of the pass is given, so both forms share one body.
     *
     * <p>Named rather than inlined for the same reason {@code StaleReleaseProgress.NONE} is: the
     * two entry points have to be one pass, and a caller reading either should be able to see that
     * the only difference between them is whether anybody wanted to be told.
     */
    private static final Consumer<ReleaseTally> NO_ACCOUNT = tally -> {};

    /** The store, asked once per run for the fenced release of every stale batch. */
    private final RegisterStore store;

    /** Where the two released numbers and the contended one are counted. */
    private final GenerationMetrics metrics;

    /** How long a batch the schedule made may be in flight before a run gives up on it. */
    private final Duration staleAfter;

    /** How long the nightly run holds its lock, which is the grace an operator's batch gets. */
    private final Duration runLock;

    /** The clock both cutoffs are measured back from. */
    private final Clock clock;

    /**
     * Creates the pass over the store it releases through and the settings it measures by.
     *
     * @param store      the store, asked once per run for the fenced release of every stale batch
     * @param metrics    where the released and contended counts are recorded
     * @param staleAfter how long a batch the schedule made may be in flight before this pass gives
     *                   up on it
     * @param runLock    how long the nightly run holds its lock, which is the longer grace a batch
     *                   an operator asked for is given (FR-017)
     * @param clock      the clock both cutoffs are measured back from
     */
    public StaleBatchReleaser(final RegisterStore store, final GenerationMetrics metrics,
            final Duration staleAfter, final Duration runLock, final Clock clock) {
        this.store = store;
        this.metrics = metrics;
        this.staleAfter = staleAfter;
        this.runLock = runLock;
        this.clock = clock;
    }

    /**
     * Fails and releases every batch the run found still waiting, and says what that was.
     *
     * <p>One call, because the decision about any one batch is the store's: the staleness rule is
     * the write's own {@code WHERE} clause, so there is nothing for this pass to read first and
     * nothing for it to decide in between. A batch that stopped being stale between the question
     * and the write is simply not in the answer, which is a number here and not an error.
     *
     * <p>Under {@link RunCorrelation#under(java.util.function.Supplier)}, which adopts the run's
     * ambient id rather than minting a second one: a night that wrote itself down under two
     * correlations could not be read out of the estate's index as one thing. Where the pass is
     * driven on its own - a test, or any caller outside a run - it opens one of its own and removes
     * it again, because the scheduler's threads are pooled.
     *
     * @return what the pass released and what it could not release
     */
    public ReleaseTally releaseStale() {
        return releaseStale(NO_ACCOUNT);
    }

    /**
     * The same pass, for a caller that keeps an account of the run this pass is the first act of.
     *
     * <p><strong>Handed over, not returned.</strong> A return value only reaches a caller the pass
     * came back to, and a store that goes away between two batches leaves through the throw
     * instead - so a run that learned what the pass did from the return alone would write
     * {@code released_batches=0} on its own line for a night whose pass had already given batches
     * back and said so at WARN. Two lines under one {@code run_id} disagreeing about the same night
     * is the report failing at the one thing it is for (FR-009), so the account is handed over as
     * the pass ends, committed or interrupted, exactly as the counters and the pass's own line are.
     *
     * @param account told once, however the pass ended, what the pass had committed by then
     * @return what the pass released and what it could not release
     */
    public ReleaseTally releaseStale(final Consumer<ReleaseTally> account) {
        return RunCorrelation.under(() -> release(account));
    }

    /**
     * The pass itself, under whatever correlation {@link #releaseStale()} settled on.
     *
     * <p>The caller's account is told in the same {@code finally} the counters and the pass's own
     * line are written from, and before the throw that ended the pass leaves: everything said
     * about a pass is said about the part of it that committed, and there is one place that
     * decides what that part was.
     *
     * @param account told what this pass committed, however it ended
     * @return what the pass released and what it could not release
     */
    private ReleaseTally release(final Consumer<ReleaseTally> account) {
        final Instant now = clock.instant();
        final Account ledger = new Account();
        boolean reachedTheEnd = false;
        try {
            store.failAndReleaseStale(now.minus(staleAfter), now.minus(manualGrace()), ledger);
            reachedTheEnd = true;
        } finally {
            account.accept(ledger.tally());
            publish(ledger.tally(), reachedTheEnd);
        }
        return ledger.tally();
    }

    /**
     * Moves the three counters and writes the summary line, for a pass that ended either way.
     *
     * <p>In a {@code finally} and not only on the way out, because the numbers are of writes that
     * are already committed: the store tells this pass about each batch where it settles it, so a
     * store that went away between two batches leaves an account of the batches before it that is
     * every bit as true as a whole night's. Nothing is caught here and nothing is absorbed - what
     * ended the pass goes on leaving it, and this only makes sure the part that happened is said
     * before it does.
     *
     * <p><strong>A pass that learned nothing publishes nothing.</strong> A run that never got an
     * answer out of the store is not a quiet night, and creating the three series at zero for it
     * would put a reading on a dashboard the store never gave. The ordinary quiet night - the pass
     * that ran and found nothing stale - still writes its three zeros, because that is a night,
     * and a series that only appears the first time something goes wrong is not an alerting
     * surface.
     *
     * @param tally         what the pass was told about before it ended
     * @param reachedTheEnd whether the walk got to the last stale batch rather than ending in a
     *                      throw partway
     */
    private void publish(final ReleaseTally tally, final boolean reachedTheEnd) {
        if (reachedTheEnd || !NOTHING.equals(tally)) {
            metrics.staleBatchesReleased(tally.batches());
            metrics.staleRegistersReleased(tally.registers());
            metrics.staleBatchesContended(tally.contended());
            if (reachedTheEnd) {
                LOG.info("The stale-batch pass gave back what the night before had not finished, "
                        + "and the run goes on to assemble it. released_batches={} "
                        + "released_registers={} contended={}",
                        tally.batches(), tally.registers(), tally.contended());
            } else {
                LOG.warn("The stale-batch pass did not reach the end, so these are the batches it "
                        + "had already given back and not a whole night's account; what is counted "
                        + "here is committed, and the next run reaches the rest. "
                        + "released_batches={} released_registers={} contended={}",
                        tally.batches(), tally.registers(), tally.contended());
            }
        }
    }

    /**
     * The grace a batch an operator asked for is given, which is the longer of the two settings.
     *
     * <p><strong>The longer, and not the lock.</strong> A manual generation holds no run lock and
     * has the whole requesting deadline to ask for its renders, so a batch it assembled at 17:25 is
     * over the minimum age by the time the schedule fires at 18:00; failing it would orphan a
     * render that run is still making and refuse its own {@code markRequested} (FR-017). Taking the
     * lock alone would be the same rule stated wrong for a deployment that shortened it: an
     * operator's batch may never be given less grace than the schedule's own.
     *
     * @return the grace a batch this service's schedule did not make is given
     */
    private Duration manualGrace() {
        return staleAfter.compareTo(runLock) >= 0 ? staleAfter : runLock;
    }

    /**
     * One line about one batch the run gave up on.
     *
     * <p>Identities and counts, which is the whole of what a release is: the batch, the court
     * centre day it held and how many registers went back for tonight. Nothing here is a defendant,
     * a recipient or a word another system wrote, so the line is safe at INFO (constitution
     * Principle VII), and the run's own correlation is on it because
     * {@link #releaseStale()} opened or adopted one.
     *
     * @param released the batch, as the store answered with it
     */
    private static void said(final ReleasedBatch released) {
        LOG.info("Batch {} had not completed by the time this run began, so it is failed and its "
                + "{} registers go back to tonight's assembly. court_centre={} register_date={}",
                released.batchId(), released.releasedRegisters(), released.courtCentreId(),
                released.registerDate());
    }

    /**
     * One line about one batch nothing could be given back from.
     *
     * <p>At WARN and counted, because a path that leaves something undone moves a counter and "it
     * is in the log index" is not an alerting surface. The batch is untouched and stale still, so
     * the next run reaches it again; the pass says so and carries on rather than ending a night
     * over one hearing the estate re-shared at the wrong moment (FR-003a).
     *
     * @param batchId the batch every attempt at was refused over
     */
    private static void saidContended(final UUID batchId) {
        LOG.warn("Batch {} could not be given back: every attempt at it lost the race for its "
                + "day's active register, so it is left exactly as it was found and the next run "
                + "reaches it again. The rest of this pass is unaffected.", batchId);
    }

    /**
     * The account this pass keeps, written as the store settles each batch rather than after.
     *
     * <p>This is where the per-batch lines are said, because where a batch is announced is where
     * it is known to be committed: a line written from the answer is a line a later batch's
     * refusal can stop being written at all. Nothing here decides anything - it counts what it is
     * told and says it once.
     */
    private static final class Account implements StaleReleaseProgress {

        private int batches;

        private int registers;

        private int contended;

        @Override
        public void recordReleased(final ReleasedBatch released) {
            batches++;
            registers += released.releasedRegisters();
            said(released);
        }

        @Override
        public void recordContended(final UUID batchId) {
            contended++;
            saidContended(batchId);
        }

        /**
         * What this pass has been told about so far.
         *
         * @return the three numbers, of batches already committed
         */
        private ReleaseTally tally() {
            return new ReleaseTally(batches, registers, contended);
        }
    }

    /**
     * What one pass did, in the three numbers a night is read by.
     *
     * <p><strong>Two of them are a diagnostic and not a third sum.</strong> The registers counted
     * here are re-batched by the same run and are therefore already inside that run's own row
     * totals; they are here because a batch is one document and one e-mail while a register is one
     * hearing's youth defendants, and neither number answers the other's question (FR-009).
     *
     * <p>The third is the batches the store could not release because every attempt lost the day's
     * active-register key. They are stale still and untouched, so the next run reaches them again
     * and the 07:00 report names their court centre days meanwhile; a run that meets one goes on to
     * assemble, because no single batch's outcome may end the run (FR-003a).
     *
     * @param batches   how many batches this pass failed and released
     * @param registers how many registers came back with them and are still the day's to render
     * @param contended how many batches the pass left exactly as it found them
     */
    public record ReleaseTally(int batches, int registers, int contended) {
    }
}
