package uk.gov.hmcts.cp.yotresultsdistribution.batch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import uk.gov.hmcts.cp.yotresultsdistribution.config.IntakeSweepConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.config.ProcessingMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ProcessedRequestSummary;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.SweepFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.ProcessedRequestRepository;

/**
 * The two intake gauges, refreshed on a fixed delay of their own in every JVM.
 *
 * <p>Design section 11 promised both readings and 001 built neither, so until now the only thing
 * that noticed a request stuck RECEIVED for three days was the morning after, or somebody looking.
 * A counter cannot answer it: every counter here moves when something <em>happens</em>, and a
 * request that is stuck is a request nothing is happening to. So the question is asked of the store
 * on a cadence instead, and the answer is a gauge an alert can fire on within one refresh interval.
 *
 * <p><strong>Nothing locks this, and that is the design rather than an omission.</strong> A gauge
 * describes the JVM that publishes it. Lock the sweep and one replica reads the store while the
 * others go on publishing whatever they last saw, so a two-pod deployment shows one pod's view
 * under two pod labels and an alert is a coin toss. Unlocked, every replica refreshes its own pair
 * and an alert aggregates them across pods with {@code max()} - the oldest unfinished request is
 * the oldest any pod can see. It is also why the sweep is conditional on neither the report nor the
 * generation half: a pod with both switched off still consumes the queue, and it is exactly the pod
 * whose stuck requests nothing else would report.
 *
 * <p>It holds a clock, a repository and the instruments, and no lock, driver, broker or HTTP
 * client. Its read is bounded by the V4 partial index rather than by a deadline - a {@code LIMIT 1}
 * and a count over the handful of rows still in flight - so there is no lock budget to state and
 * none is stated.
 *
 * <p><strong>The read it cannot take is the one refusal this service absorbs</strong>, and it is
 * absorbed because the service's design rules on absorbed refusals say so: <em>"The one absorbed
 * refusal is
 * telemetry: a round-trip reading that cannot be taken may not cost a Youth Offending Team its
 * e-mail, so it stops where it happens, is counted, and is said at WARN. Every other refusal still
 * leaves."</em> Every other refusal in this service still leaves. This one stops here for two
 * reasons and no others: there is nothing above it to tell - the caller is a scheduler, and a
 * fixed-delay schedule <em>cancels the task that throws</em>, so letting a store blip out would
 * freeze both readings for the life of the pod with nothing saying they had stopped moving - and
 * the failure is about the instrument rather than about a register. The absorption is made visible
 * three ways: the gauges keep their last reading rather than dropping to a zero the store never
 * said, {@code yotresultsdistribution_intake_sweep_failures_total} moves under a bounded reason, and one
 * WARN line names the caught failure by class.
 */
public class IntakeAgeSweep {

    private static final Logger LOG = LoggerFactory.getLogger(IntakeAgeSweep.class);

    private final ProcessedRequestRepository requests;
    private final ProcessingMetrics metrics;
    private final Duration requestTerminalWithin;
    private final Clock clock;

    /**
     * Builds the sweep over the processed log it reads and the instruments it publishes.
     *
     * @param requests              the processed log, read for the oldest unfinished request and
     *                              for how many are over the threshold
     * @param metrics               where the two gauges and the absorbed-failure counter live
     * @param requestTerminalWithin how long a request may stay RECEIVED or RETRYING before it is
     *                              over the threshold
     * @param clock                 the clock the cut-off is measured back from
     */
    public IntakeAgeSweep(final ProcessedRequestRepository requests,
            final ProcessingMetrics metrics, final Duration requestTerminalWithin,
            final Clock clock) {
        this.requests = requests;
        this.metrics = metrics;
        this.requestTerminalWithin = requestTerminalWithin;
        this.clock = clock;
    }

    /**
     * The schedule's own refresh, under a correlation of its own.
     *
     * <p>No {@code @SchedulerLock}, for the reason the class javadoc gives. The correlation is
     * opened here rather than inside the body so that a caller reaching the body directly - a test,
     * or a future command - carries whatever correlation it opened for itself. It is the same split
     * {@code StaleBatchReleaser} relies on from the other side: called from inside the run, it
     * adopts the run's correlation rather than minting a second for the same night's work.
     */
    @Scheduled(fixedDelayString = "${yotresultsdistribution.intake.gauge-refresh}",
            scheduler = IntakeSweepConfig.INTAKE_SWEEP_SCHEDULER)
    public void sweepScheduled() {
        RunCorrelation.under(this::sweep);
    }

    /**
     * Reads the store once and republishes both gauges from what it said.
     *
     * <p>Both readings come from the same pass, so the age and the count describe one moment. The
     * age is the one the database computed in the statement that selected the row: no stored
     * timestamp is subtracted from a JVM reading, and two pods reading one row agree.
     *
     * <p>The second reading is a <em>count</em>, not the size of a list. Sizing a list would make
     * this read's cost grow with the backlog it is reporting - slowest on the morning the reading
     * matters most - and would carry every unfinished request's row into this JVM to be counted
     * and dropped. The cut-off is {@code now} less the threshold exactly, and the statement's
     * boundary is exclusive, so a request created exactly the threshold ago is not over it: the
     * report's REQUEST_LATE read shares that boundary, and a tolerance added here to soften it
     * would make the gauge and the morning report disagree about the same request.
     *
     * <p>The second catch is total on purpose, which is why the rule against it is suppressed here:
     * this method may not throw. A fixed-delay schedule cancels the task that throws, so a failure
     * this method let out would take both readings off the air for the life of the pod - and a
     * gauge that has silently stopped moving is worse than one that was never registered. Every
     * failure is named, counted and said; none is ignored.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void sweep() {
        try {
            final Instant cutOff = clock.instant().minus(requestTerminalWithin);
            final Duration oldest = requests.oldestNonTerminal()
                    .map(ProcessedRequestSummary::ageSeconds)
                    .map(Duration::ofSeconds)
                    .orElse(Duration.ZERO);
            final int overThreshold = Math.toIntExact(requests.countNonTerminalOlderThan(cutOff));

            metrics.oldestNonTerminalRequestAge(oldest);
            metrics.nonTerminalRequestsOverThreshold(overThreshold);
        } catch (StoreUnavailableException gone) {
            absorbed(SweepFailureReason.STORE_UNAVAILABLE, gone);
        } catch (RuntimeException unexpected) {
            absorbed(SweepFailureReason.UNEXPECTED, unexpected);
        }
    }

    /**
     * Stops the refusal where it happened, counts it, and says so once.
     *
     * <p>The gauges are left alone deliberately. Setting them to zero would publish a reading the
     * store never gave and would look exactly like a healthy service; leaving them says "this is
     * what was true when the last read succeeded", and the counter beside them says how long ago
     * that was.
     *
     * @param reason  the bounded code this refresh is counted under
     * @param failure what stopped it, named by class and never by message
     */
    private void absorbed(final SweepFailureReason reason, final RuntimeException failure) {
        metrics.intakeSweepFailure(reason);
        LOG.warn("Intake gauge refresh could not be taken; the gauges keep their last reading. "
                        + "reason={} type={}",
                reason, failure.getClass().getName());
    }
}
