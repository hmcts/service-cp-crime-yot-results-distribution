package uk.gov.hmcts.cp.yotresultsdistribution.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.IntakeAgeSweep;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.ProcessedRequestRepository;

/**
 * The two intake gauges' refresh, on a thread of its own, in every service JVM.
 *
 * <p>Conditional on the non-test profile and on <strong>nothing else</strong>. The gauges belong
 * to the intake half, so they refresh wherever the intake half runs, whichever of the other two
 * halves a deployment switched on. A pod with the report and the generation half both off still
 * consumes the queue, and it is exactly the pod whose stuck requests nothing else would report (user story 2
 * scenario 4): a flag condition here would leave that deployment with the two readings it was given
 * them for switched off.
 *
 * <p><strong>A scheduler of its own, single-threaded.</strong> The sweep runs on a fixed delay and
 * the other three schedules in this service do not, so sharing a thread with them would mean a
 * refresh queued behind an 18:00 run that overran - and both readings frozen for exactly the hour
 * they are worth reading. Together with the {@code scheduler} attribute on
 * {@link IntakeAgeSweep#sweepScheduled()}, this is what SC-008's separation is made of.
 *
 * <p><strong>Nothing here locks.</strong> A gauge describes the JVM that publishes it, so every
 * replica refreshes its own pair and an alert aggregates them across pods with {@code max()} - the
 * oldest unfinished request is the oldest any pod can see. {@link IntakeAgeSweep} says why at
 * length; what this configuration contributes to it is that there is no lock to declare here and
 * none is declared.
 *
 * <p>The threshold is the report's {@code request-terminal-within}, deliberately, and it is the
 * only thing the sweep shares with the report: the gauge and the morning report must agree about
 * which requests are late, and two settings would be two answers. The cadence is the intake half's
 * own {@code yotresultsdistribution.intake.gauge-refresh}, on a record a pod that binds neither other half
 * still reads.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class IntakeSweepConfig {

    /**
     * The bean name of the scheduler the sweep runs on.
     *
     * <p>Published for the reason {@link ReportSchedulingConfig#REPORT_SCHEDULER} is: the
     * {@code scheduler} attribute on the scheduled method names a constant rather than a string
     * spelled twice.
     */
    public static final String INTAKE_SWEEP_SCHEDULER = "intakeSweepScheduler";

    /** The one thread the refresh has, and the prefix its name is read by in a thread dump. */
    private static final String SWEEP_THREAD_PREFIX = "intake-sweep-";

    /**
     * The executor the gauge refresh happens on, and nothing else does.
     *
     * @return a single-threaded scheduler named for the refresh it carries
     */
    @Bean(INTAKE_SWEEP_SCHEDULER)
    public TaskScheduler intakeSweepScheduler() {
        final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(SWEEP_THREAD_PREFIX);
        // A refresh in progress when the pod is asked to stop finishes: it is two cheap reads, and
        // half of it published is a pair of readings that describe two different moments.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        return scheduler;
    }

    /**
     * The refresh itself, over the processed log it reads and the instruments it publishes.
     *
     * @param requests   the processed log, read for the oldest unfinished request and the count
     *                   over the threshold
     * @param report     the report's settings, for the one threshold the gauge and the report share
     * @param metrics    where the two gauges and the absorbed-failure counter live
     * @param clock      the clock the cut-off is measured back from
     * @return the sweep the schedule fires
     */
    @Bean
    public IntakeAgeSweep intakeAgeSweep(final ProcessedRequestRepository requests,
            final ReportProperties report, final ProcessingMetrics metrics, final Clock clock) {
        return new IntakeAgeSweep(requests, metrics, report.requestTerminalWithin(), clock);
    }
}
