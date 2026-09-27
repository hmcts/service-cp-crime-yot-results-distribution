package uk.gov.hmcts.cp.yotresultsdistribution.config;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ExceptionReportService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ExceptionReportSink;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.ExceptionReportJob;

/**
 * The 07:00 run, and the thread it is the only thing on.
 *
 * <p>Behind {@code yotresultsdistribution.report.enabled} and behind nothing else about this service's two
 * halves. The report is a read of this service's own store and must keep running on a pod that
 * generates nothing (FR-004), so making it depend on {@code yotresultsdistribution.generation.enabled} would
 * tie a read-only morning report to the switch that decides whether this pod renders documents -
 * and turn a threshold change into a deployment decision. It is not a cutover lever either: the
 * report reads and writes nothing {@code YotResultsDistributionService} decides and never asks for it.
 *
 * <p><strong>The sweep is not here.</strong> It belongs to the intake half, and a pod with the
 * report switched off still needs its gauges - {@link IntakeSweepConfig} owns it for that reason.
 *
 * <p><strong>A scheduler of its own, single-threaded.</strong> That is what SC-008's separation
 * actually rests on, together with the {@code scheduler} attribute each scheduled method carries: a
 * 07:00 report queued behind an 18:00 run that overran is a report that does not happen, and it
 * would not happen on the morning after the night that overran - which is the morning it is most
 * worth reading. One thread, because the run is sequential and a pool would only make it look
 * otherwise.
 *
 * <p>The schedule is not the only caller of the report: {@code POST /operations/exception-reports}
 * asks for one over the same reads, and it is contributed in {@code ProcessedLogConfig} rather than
 * here, so a pod with this schedule switched off can still answer what is wrong with what it
 * recorded.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "yotresultsdistribution.report", name = "enabled", havingValue = "true")
public class ReportSchedulingConfig {

    /**
     * The bean name of the scheduler the report runs on.
     *
     * <p>Published so that {@code @Scheduled(scheduler = ...)} on
     * {@link ExceptionReportJob#run()} names a constant rather than a string spelled twice: a bean
     * name spelled twice is a bean name that can be renamed once.
     */
    public static final String REPORT_SCHEDULER = "exceptionReportScheduler";

    /** The one thread the report has, and the prefix its name is read by in a thread dump. */
    private static final String REPORT_THREAD_PREFIX = "exception-report-";

    /**
     * The executor the 07:00 run happens on, and nothing else does.
     *
     * @return a single-threaded scheduler named for the run it carries
     */
    @Bean(REPORT_SCHEDULER)
    public TaskScheduler exceptionReportScheduler() {
        final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(REPORT_THREAD_PREFIX);
        // A run part way through its sinks when the pod is asked to stop finishes them: the
        // delivery and the line that says how it went are the pair that must not be halved.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        return scheduler;
    }

    /**
     * The morning run, over the report it asks for and the sinks this context holds.
     *
     * <p>The sinks arrive as a provider rather than as a list so that the job is contributed
     * whatever a deployment switched on: the log sink is unconditional and the e-mail sink is
     * behind its own switch, and a job that could not be built because one of them was absent
     * would be a morning report that silently stopped happening. The job's own line says
     * {@code delivered_email=disabled} where there is no e-mail sink, which is the honest
     * statement of the same fact.
     *
     * <p>The schedule is handed in as well as annotated on. The annotation is what fires the run;
     * the two strings are what the run measures its window back through, and they have to be the
     * same two - a window computed from a different cron than the one that fired would open on a
     * morning that never ran.
     *
     * @param reporting  the report, built and delivered through it
     * @param sinks      whatever sinks this deployment switched on
     * @param properties the report's own settings, for the schedule its window is measured through
     * @param metrics    where the run's outcome is counted
     * @param clock      this pod's reading of now
     * @return the job the schedule fires
     */
    @Bean
    public ExceptionReportJob exceptionReportJob(final ExceptionReportService reporting,
            final ObjectProvider<ExceptionReportSink> sinks, final ReportProperties properties,
            final ProcessingMetrics metrics, final Clock clock) {

        return new ExceptionReportJob(reporting, sinks.orderedStream().toList(),
                properties.cron(), properties.zone(), metrics, clock);
    }
}
