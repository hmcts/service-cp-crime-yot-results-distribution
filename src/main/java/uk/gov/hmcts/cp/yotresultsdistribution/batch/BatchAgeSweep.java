package uk.gov.hmcts.cp.yotresultsdistribution.batch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import uk.gov.hmcts.cp.yotresultsdistribution.config.BatchSweepConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.SweepFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterBatchRepository;

/**
 * The three in-flight batch readings, refreshed on a fixed delay of their own in every JVM that
 * generates.
 *
 * <p>They are the state a nightly flow cannot be understood without between runs: how long the
 * batch that has been waiting longest for its document has been waiting, how long the oldest batch
 * that never reached the renderer has been stuck, and how long the oldest batch holding a document
 * nobody was told about has stood there. The retired grace-period pass took all three on its way
 * past, and 004 deletes it - so without this sweep they would have no publisher at all.
 *
 * <p><strong>A Micrometer gauge never decays.</strong> That is the whole reason this class exists
 * rather than the readings simply moving into the nightly run. A gauge whose publisher goes away
 * does not fall to zero; it holds the last value it was given, for ever - so deleting the timer
 * without replacing it would not merely make the three readings stale, it would freeze them at
 * whatever the last reconciliation saw and leave them looking live. Taken once a night instead,
 * they would be a daily sample of a thing that is asked about hourly (FR-011, SC-006).
 *
 * <p><strong>Nothing locks this</strong>, for the reason {@link IntakeAgeSweep} gives at length: a
 * gauge describes the JVM that publishes it, so a locked sweep would have one replica reading the
 * store while the others went on publishing whatever they last saw. Unlocked, every replica
 * refreshes its own three and an alert aggregates them across pods with {@code max()}.
 *
 * <p>It holds a repository, the instruments and a clock, and no store, no renderer, no broker
 * client and no lock. <strong>The cutoff is the clock itself</strong>, and not a grace period: the
 * retired pass read a window because it was about to ask systemdocgenerator about whatever it
 * found, and a reading whose own meaning is "the oldest batch of this kind" has no window to take.
 * A batch requested a minute ago is in the reading at a minute old, which is what makes the series
 * continuous rather than a step function keyed to a setting.
 *
 * <p>It settles <strong>nothing</strong>, and the third read is where that matters. A batch parked
 * at GENERATED holds a document somebody is owed e-mails about; failing it would throw the document
 * away, so it is named at WARN and left exactly as it was found. Both recovery surfaces -
 * {@code RegisterNotifierService.resendFailed} and {@code notify-register --batch} - are
 * re-entrant, so the recovery is an operator's to start and not this pass's to guess at.
 */
public class BatchAgeSweep {

    private static final Logger LOG = LoggerFactory.getLogger(BatchAgeSweep.class);

    /** The {@code register_batch} table, read for the oldest batch of each in-flight kind. */
    private final RegisterBatchRepository batches;

    /** Where the three gauges and the absorbed-failure counter live. */
    private final GenerationMetrics metrics;

    /** The clock the three ages are measured back from, and the cutoff the reads are taken at. */
    private final Clock clock;

    /**
     * Creates the sweep over the batches it reads and the instruments it publishes.
     *
     * @param batches the {@code register_batch} table, read for the oldest of each in-flight kind
     * @param metrics where the three gauges and the absorbed-failure counter live
     * @param clock   the clock the three ages are measured back from
     */
    public BatchAgeSweep(final RegisterBatchRepository batches, final GenerationMetrics metrics,
            final Clock clock) {
        this.batches = batches;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * The schedule's own refresh, under a correlation of its own.
     *
     * <p>Opened rather than adopted, because a sweep on its own schedule is a unit of work in its
     * own right - unlike {@link StaleBatchReleaser}, which is reached from inside a run and carries
     * the run's id. The correlation is opened here rather than inside the body so that a caller
     * reaching the body directly keeps whatever correlation it opened for itself.
     *
     * <p>No {@code @SchedulerLock}, for the reason the class javadoc gives.
     */
    @Scheduled(fixedDelayString = "${yotresultsdistribution.generation.batch-age-refresh}",
            scheduler = BatchSweepConfig.BATCH_SWEEP_SCHEDULER)
    public void sweepScheduled() {
        RunCorrelation.under(this::sweep);
    }

    /**
     * Reads the store once and republishes all three gauges from what it said.
     *
     * <p>All three readings come from one pass, so they describe one moment: three reads taken
     * minutes apart would be three moments published as one, and the one thing these gauges are
     * read for is which of the three stages a night is stuck at.
     *
     * <p>The second catch is total on purpose, which is why the rule against it is suppressed here:
     * this method may not throw. A fixed-delay schedule cancels the task that throws, so a failure
     * let out of here would take all three readings off the air for the life of the pod - and a
     * gauge that has silently stopped moving is worse than one that was never registered. Every
     * failure is named, counted and said; none is ignored.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void sweep() {
        try {
            final Instant now = clock.instant();

            metrics.oldestGeneratingAge(
                    oldestOf(batches.generatingSince(now), now, RegisterBatch::requestedAt));
            metrics.oldestPendingAge(
                    oldestOf(batches.pendingSince(now), now, RegisterBatch::assembledAt));

            final List<RegisterBatch> parked = batches.generatedSince(now);
            metrics.oldestGeneratedAge(oldestOf(parked, now, RegisterBatch::generatedAt));
            report(parked);
        } catch (StoreUnavailableException gone) {
            absorbed(SweepFailureReason.STORE_UNAVAILABLE, gone);
        } catch (RuntimeException unexpected) {
            absorbed(SweepFailureReason.UNEXPECTED, unexpected);
        }
    }

    /**
     * Names the batches that hold a document nobody was told about, and settles none of them.
     *
     * <p>Reported rather than ended, which is the difference between this read and the two above
     * it. Those two are about a render that may never have happened, and the run's own pass gives
     * those batches back; this one is about a document that certainly exists, so there is nothing
     * to fail and nothing to re-render - the batch is owed its e-mails, and both resend surfaces
     * are re-entrant. Failing it here would throw away a document somebody has already paid to
     * render and leave its Youth Offending Teams no better off.
     *
     * <p>The line carries the identities and the count, which is all a parked batch is: how many
     * teams it is owed by is on its own rows.
     *
     * @param parked the batches this pass read, oldest first
     */
    private static void report(final List<RegisterBatch> parked) {
        if (!parked.isEmpty()) {
            LOG.warn("{} batches hold a document nobody has been told about, the oldest since {}; "
                    + "each is owed its e-mails and reaches them through a resend rather than "
                    + "through this sweep. oldest={}", parked.size(),
                    parked.getFirst().generatedAt(), parked.getFirst().batchId());
        }
    }

    /**
     * How long the oldest batch of one kind has been waiting.
     *
     * <p>Taken off the read this pass already made rather than from a second query. The reads come
     * back oldest first, but the minimum is taken rather than the first row, so a read whose order
     * changed would move a reading rather than silently publishing the wrong batch's age.
     *
     * <p>Zero where there is none, which is what brings the gauge back down: a reading that only
     * ever moved up would need a batch to fail before it could fall.
     *
     * @param inFlight the batches this read answered with
     * @param now      the instant the pass was made at
     * @param since    the stamp the age is measured from, which is the one the read was made
     *                 against
     * @return the age of the oldest, or {@link Duration#ZERO} where there is none
     */
    private static Duration oldestOf(final List<RegisterBatch> inFlight, final Instant now,
            final Function<RegisterBatch, Instant> since) {

        return inFlight.stream()
                .map(since)
                .filter(Objects::nonNull)
                .min(Instant::compareTo)
                .map(oldest -> Duration.between(oldest, now))
                .orElse(Duration.ZERO);
    }

    /**
     * Stops the refusal where it happened, counts it, and says so once.
     *
     * <p>The three gauges are left alone deliberately. Setting them to zero would publish a
     * reading the store never gave and would look exactly like a healthy night; leaving them says
     * "this is what was true when the last read succeeded", and the counter beside them says how
     * long ago that was.
     *
     * <p>This is the design rules' <em>one absorbed refusal</em> and it is absorbed on their terms:
     * a round-trip reading that cannot be taken may not cost a Youth Offending Team its e-mail, so
     * it stops where it happens, is counted, and is said at WARN. Every other refusal in this
     * service still leaves.
     *
     * @param reason  the bounded code this refresh is counted under
     * @param failure what stopped it, named by class and never by message
     */
    private void absorbed(final SweepFailureReason reason, final RuntimeException failure) {
        metrics.batchSweepFailure(reason);
        LOG.warn("Batch-age refresh could not be taken; the three gauges keep their last reading. "
                        + "reason={} type={}",
                reason, failure.getClass().getName());
    }
}
