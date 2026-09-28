package uk.gov.hmcts.cp.yotresultsdistribution.inbound;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.yotresultsdistribution.application.FeatureFlagReader;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagStateSnapshot;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;

/**
 * What the intake side labels an arriving command with, without ever waiting to find out.
 *
 * <p>A recorded row says which implementation was meant to be generating when it arrived, because a
 * command still on the queue after the producer stopped publishing may belong to a hearing the
 * resumed legacy also processed, and batching it automatically would send one child's register
 * twice (research §12). The label is the flag as it was last read, and the reading is only allowed
 * to speak for a command that arrived inside its window.
 *
 * <p><strong>The read is never on the delivery's thread.</strong> A command arriving on a stale or
 * absent reading hands a refresh to the executor and is labelled from what is already known, which
 * for an absent reading is {@link RecordedFlagState#UNKNOWN}. The alternative is a register whose
 * recording waits on App Configuration: an outage there would then stall the queue behind a label,
 * and a register that has been built is worth more than the flag state it carries. The refresh is
 * for the commands behind this one.
 *
 * <p>It is a collaborator of {@link YotResultsDistributionMessageListener} rather than a part of it. The
 * listener's rule is one delivery in, exactly one settlement out; holding a flag reader, a clock and
 * an executor inside it would put a second concern on the class whose single concern is the whole of
 * its correctness (constitution Principle V).
 *
 * <p><strong>The reading is renewed on a clock, not on the traffic.</strong> This service takes
 * about 160 commands a day on a stack - one every nine minutes on average - so a reading refreshed
 * only when an arrival finds it stale is stale for very nearly every arrival there is: each one is
 * labelled {@code UNKNOWN} and schedules a read that comes back seconds later having labelled
 * nobody. Rows that are not {@code ON} are excluded from automatic batching, so that would leave
 * almost every register waiting for somebody to find it with
 * {@code list-batches --recorded-while-off}. So {@link #start()} renews the reading every half
 * window for as long as the pod is consuming, and an arrival meets a reading that was taken for it.
 *
 * <p><strong>At most one read per window, and at most one in flight.</strong> The renewal asks App
 * Configuration twice a minute however busy the queue is, and an arrival that still finds no
 * reading - before the first renewal has returned, or after one failed - asks for one refresh and
 * not one per command: a second arrival during a read that has not come back yet joins the first
 * one's refresh rather than starting another. The pair is what keeps a busy queue from turning a
 * labelling rule into a load test of somebody else's store.
 *
 * <p><strong>Both refreshes are the same refresh.</strong> The renewal and an arrival's on-demand
 * request take the one in-flight claim and release it the same way, so "at most one in flight" is a
 * property of the mechanism rather than of who asked. A renewal that only released the claim would
 * let every arrival during a periodic read hand over another, and each of those would release the
 * claim for the arrival behind it - a queue of reads one thread deep, which is the load the pair
 * exists to prevent. A renewal that finds a read already in flight has nothing to add and skips its
 * turn: the read in flight is younger than the reading the renewal would have replaced.
 */
public class RecordedFlagStateSource {

    private static final Logger LOG = LoggerFactory.getLogger(RecordedFlagStateSource.class);

    /**
     * How often the reading is renewed while this pod is consuming.
     *
     * <p>Half the window a reading speaks for, so that a command arriving at the worst moment - the
     * instant before the next renewal - still meets a reading half a window old. Deriving it from
     * {@link FlagStateSnapshot#WINDOW} rather than writing a number is what keeps the two in step:
     * an interval longer than the window would leave a gap in which every arrival is labelled
     * {@code UNKNOWN}, which is the whole defect this schedule answers.
     */
    private static final Duration RENEWAL = FlagStateSnapshot.WINDOW.dividedBy(2);

    /** The same reader the nightly job uses, which is what makes the flag one lever. */
    private final FeatureFlagReader reader;

    /** Where a refresh is handed to, so that no delivery thread is ever inside a read. */
    private final ScheduledExecutorService refreshes;

    /** What a reading's age is measured against. */
    private final Clock clock;

    /**
     * The reading every delivery is labelled from, and {@code null} until the first read returns.
     *
     * <p>Written by whichever thread the executor ran a refresh on and read by every delivery
     * thread, so the reference is atomic and the snapshot it holds is immutable: a delivery sees the
     * reading whole or sees the one before it, and never half of each.
     */
    private final AtomicReference<FlagStateSnapshot> reading = new AtomicReference<>();

    /**
     * Whether a refresh is already on its way, so that nothing behind it starts another.
     *
     * <p>Taken by the renewal and by an arrival alike, and released by the read that took it. It is
     * the whole of "one refresh in flight": a hand-over that does not take it does not happen, and
     * a read that did not take it does not release it.
     */
    private final AtomicBoolean refreshing = new AtomicBoolean();

    /** Whether the renewal is already running, so that {@link #start()} can only ask for one. */
    private final AtomicBoolean scheduled = new AtomicBoolean();

    /**
     * Creates the source; the reading it hands out is its own and is shared by every delivery.
     *
     * @param reader   the flag reader the nightly job also uses
     * @param refreshes where a read is run, which is never the caller's thread
     * @param clock    what the age of a reading is measured against
     */
    public RecordedFlagStateSource(
            final FeatureFlagReader reader, final ScheduledExecutorService refreshes,
            final Clock clock) {
        this.reader = reader;
        this.refreshes = refreshes;
        this.clock = clock;
    }

    /**
     * Starts keeping the reading inside its window, for as long as this pod is consuming.
     *
     * <p>Called once, when the source is built. The first read is asked for immediately and every
     * later one at {@link #RENEWAL}, on the executor's thread as ever, so a command arriving on a
     * quiet stack finds a reading that was taken for it rather than one it has to ask for. Each
     * tick goes through the same in-flight claim an arrival's refresh takes, so a renewal that
     * comes round while a read is still in flight lets that read stand for its turn.
     *
     * <p>A refusal by the executor is reported and no more, exactly as an on-demand refresh's is: a
     * pod that cannot start the schedule still labels every command, from whatever the on-demand
     * refreshes manage to put in place. The schedule ends when the executor is shut down with the
     * application context.
     */
    public void start() {
        if (scheduled.compareAndSet(false, true)) {
            try {
                refreshes.scheduleAtFixedRate(
                        this::renew, 0L, RENEWAL.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException notTaken) {
                scheduled.set(false);
                LOG.warn("The flag reading will not be renewed on a schedule, so a command is "
                        + "labelled only from what an arrival before it asked for. type={}",
                        notTaken.getClass().getName());
            }
        }
    }

    /**
     * What a command arriving now is labelled with, answered from what is already known.
     *
     * <p>Answered before anything is asked of App Configuration, and the refresh this arrival may
     * schedule is for the commands behind it. The two orderings are not interchangeable: a label
     * read on this thread would put another service's timeout inside every recording.
     *
     * @return the state the last reading stands for now, or {@link RecordedFlagState#UNKNOWN} where
     *         there is no reading or it has aged out of its window
     */
    public RecordedFlagState current() {
        final FlagStateSnapshot known = reading.get();
        final RecordedFlagState state = known == null
                ? RecordedFlagState.UNKNOWN
                : known.stateFor(clock.instant());
        if (state == RecordedFlagState.UNKNOWN) {
            scheduleRefresh();
        }
        return state;
    }

    /**
     * Hands a read to the executor, unless one is already on its way.
     *
     * <p>A rejected hand-over is reported and no more: the executor refusing work is a pod that is
     * shutting down or an outage in this service's own plumbing, and neither is a reason to fail a
     * delivery that has a register to build. The next arrival asks again. What it must not do is
     * leave the in-flight flag raised over a refresh that never ran, which would silence every
     * later one and label every row {@code UNKNOWN} for the life of the pod.
     */
    private void scheduleRefresh() {
        if (refreshing.compareAndSet(false, true)) {
            try {
                refreshes.execute(this::read);
            } catch (RejectedExecutionException notTaken) {
                refreshing.set(false);
                LOG.warn("A flag refresh could not be handed over, so the commands behind this one "
                        + "are labelled from what is already known. type={}",
                        notTaken.getClass().getName());
            }
        }
    }

    /**
     * One tick of the renewal, which reads only if no refresh is already in flight.
     *
     * <p>The claim is the same one an arrival's refresh takes, so the two are one mechanism rather
     * than two that happen to share a reading. A tick that finds a read in flight has nothing to
     * add - that read is younger than the reading this tick would have replaced - and skipping it
     * is what stops an arrival during a periodic read from being free to hand over another.
     */
    private void renew() {
        if (refreshing.compareAndSet(false, true)) {
            read();
        } else {
            LOG.debug("A flag read was already in flight when the renewal came round; the reading "
                    + "it produces is this renewal's own.");
        }
    }

    /**
     * The read itself, on the executor's thread and never on a delivery's.
     *
     * <p>The reading is timed by the same clock the window is measured with, and it is timed when
     * the flag was asked rather than when the answer came back: a store that took two seconds
     * answered about a flag as it stood when it was asked, and stamping the return would let a slow
     * read quietly lengthen the window its answer speaks for.
     *
     * <p>Reached only by a caller that took the in-flight claim, and it releases that claim however
     * the read ends. Two reads can therefore never be in flight at once, whichever of the renewal
     * and an arrival asked for them, and a reading is replaced by one of them whole.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    // Total, and for the renewal's sake rather than this read's. A throw out of a fixed-rate task
    // cancels every later execution of it, so a reader that failed once would stop the renewal for
    // the life of the pod and label every row UNKNOWN from then on - a far larger failure than the
    // read that caused it. The port's contract is that it never throws; this is what happens if
    // that is ever untrue, and it is reported rather than absorbed.
    private void read() {
        try {
            final Instant asked = clock.instant();
            reading.set(new FlagStateSnapshot(reader.read(), asked));
        } catch (RuntimeException failed) {
            LOG.warn("A flag read failed, so the reading in hand stands until one replaces it; the "
                    + "renewal after this one asks again. type={}", failed.getClass().getName());
        } finally {
            refreshing.set(false);
        }
    }
}
