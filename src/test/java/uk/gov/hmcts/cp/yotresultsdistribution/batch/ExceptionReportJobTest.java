package uk.gov.hmcts.cp.yotresultsdistribution.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ExceptionReportService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ExceptionReportSink;
import uk.gov.hmcts.cp.yotresultsdistribution.config.ProcessingMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.config.ReportSchedulingConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionReport;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportDeliveryReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportRunOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportSinkName;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportWindow;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;

/**
 * The morning run: what it asks for, what it hands over, and what it says afterwards.
 *
 * <p>The job is thin on purpose - the reads are the service's and the writing is the sinks' - so
 * almost everything below is about the three things only the job can get wrong: the window it asks
 * for, the correlation it opens and takes away again, and the one line it leaves behind.
 *
 * <p><strong>The line is written last, and that is the assertion rather than a comment.</strong>
 * It carries {@code delivered_log} and {@code delivered_email}, which are claims about deliveries
 * that have happened; a run that wrote it before the sinks returned would be reporting a delivery
 * it had not yet observed, and the morning that mattered would be the morning a sink was refusing.
 *
 * <p><strong>A read that cannot be taken leaves.</strong> This is the other branch from
 * {@link IntakeAgeSweep}'s, and the two are deliberately opposite: the sweep absorbs a failed read
 * because a gauge is telemetry about the service, and a fixed-delay schedule cancels the task that
 * throws. Here the read <em>is</em> the report, there is a lock to release, and
 * the service's design rules on absorbed refusals are explicit that every other refusal still
 * leaves. So the
 * run counts itself failed, writes its line, and rethrows.
 *
 * <p>The window arithmetic is asserted against {@link ReportWindow#forScheduledRun} rather than
 * against an instant written out here. The Monday-to-Friday computation has exactly two homes,
 * {@code LastScheduledRunTest} and {@code ExceptionReportModelTest}, and an instant spelled in this
 * file would be a third place to change on the day the schedule moves.
 */
@DisplayName("ExceptionReportJob")
class ExceptionReportJobTest {

    /** Returned when a meter is absent, so a missing instrument fails as an assertion. */
    private static final double ABSENT = -1;

    /** The report's own schedule, which is also what its window is measured back through. */
    private static final String CRON = "0 0 7 * * MON-FRI";

    private static final String ZONE = "Europe/London";

    /** A Tuesday, at 07:00 Europe/London, which in September is 06:00Z. */
    private static final Instant FIRED_AT = Instant.parse("2026-09-15T06:00:00Z");

    /** The one event name the run's line is indexed under. */
    private static final String RUN_EVENT = "exception_report_run";

    /**
     * A correlation somebody else opened, which is the only kind the body is ever given.
     *
     * <p>Deliberately not a UUID this class mints and then looks for: a caller's id is whatever the
     * caller had, and a value that is plainly not one this job could have produced is the one an
     * assertion about "the argument, not the MDC" is worth making over.
     */
    private static final String A_CALLERS_RUN_ID = "a-run-the-caller-already-opened";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProcessingMetrics metrics = new ProcessingMetrics(registry);
    private final ExceptionReportService reporting = mock(ExceptionReportService.class);
    private final ExceptionReportSink logSink = sinkNamed(ReportSinkName.LOG);
    private final ExceptionReportSink emailSink = sinkNamed(ReportSinkName.EMAIL);
    private final Clock clock = Clock.fixed(FIRED_AT, ZoneOffset.UTC);

    @Test
    void the_report_is_scheduled_in_europe_london() throws NoSuchMethodException {
        final Method run = ExceptionReportJob.class.getDeclaredMethod("run");
        final Scheduled schedule = run.getAnnotation(Scheduled.class);
        final SchedulerLock lock = run.getAnnotation(SchedulerLock.class);

        assertThat(run.getReturnType())
                .as("void, like the nightly run's: ShedLock's interceptor refuses to lock a "
                        + "method returning a primitive, and a schedule has nobody to hand a "
                        + "report back to in any case")
                .isEqualTo(void.class);
        assertThat(schedule)
                .as("a report nothing fires is a morning support hears nothing, which is exactly "
                        + "the silence this feature exists to end")
                .isNotNull();
        assertThat(schedule == null ? null : schedule.cron()).isEqualTo("${yotresultsdistribution.report.cron}");
        assertThat(schedule == null ? null : schedule.zone())
                .as("07:00 WALL CLOCK in BST and GMT alike; a zone left to the JVM is an hour late "
                        + "for five months of the year")
                .isEqualTo("${yotresultsdistribution.report.zone}");
        assertThat(lock)
                .as("two replicas would e-mail support twice and write the morning's exceptions to "
                        + "the index twice")
                .isNotNull();
        assertThat(lock == null ? null : lock.lockAtMostFor())
                .as("the placeholder and not a literal, so the duration is written once and a "
                        + "deployment that lengthens the budget lengthens the lock with it")
                .isEqualTo("${yotresultsdistribution.report.lock-at-most-for}");
    }

    @Test
    void the_lock_name_is_not_generations() throws NoSuchMethodException {
        final SchedulerLock lock = ExceptionReportJob.class.getDeclaredMethod("run")
                .getAnnotation(SchedulerLock.class);

        assertThat(lock == null ? null : lock.name())
                .as("a 07:00 report waiting on the lock an 18:00 run that overran still holds is a "
                        + "report that does not happen (SC-008)")
                .isEqualTo("exception-report")
                .isNotEqualTo(RegisterGenerationJob.LOCK_NAME);
    }

    @Test
    void the_report_names_the_report_scheduler() throws NoSuchMethodException {
        final Scheduled schedule = ExceptionReportJob.class.getDeclaredMethod("run")
                .getAnnotation(Scheduled.class);

        assertThat(schedule == null ? null : schedule.scheduler())
                .as("three TaskScheduler beans route nothing by themselves - Spring resolves one "
                        + "scheduler for @Scheduled processing unless the method names one, so "
                        + "without this attribute the 07:00 run could be queued behind an 18:00 "
                        + "one and SC-008's separation would be a comment")
                .isEqualTo(ReportSchedulingConfig.REPORT_SCHEDULER);
    }

    @Test
    void the_window_the_job_asks_for_is_the_one_since_the_previous_scheduled_run() {
        aQuietMorning();
        delivered(DeliveryStatus.DELIVERED, ReportSinkName.LOG);

        jobOver(List.of(logSink)).run();

        verify(reporting).build(eq(ReportWindow.forScheduledRun(CRON, ZONE, FIRED_AT)), any());
    }

    @Test
    void the_run_id_is_opened_for_the_run_passed_into_build_and_removed_afterwards() {
        final AtomicReference<String> ambient = new AtomicReference<>();
        final ArgumentCaptor<String> given = ArgumentCaptor.forClass(String.class);
        when(reporting.build(any(), given.capture())).thenAnswer(call -> {
            ambient.set(RunCorrelation.current());
            return emptyReport(call.getArgument(1));
        });
        delivered(DeliveryStatus.DELIVERED, ReportSinkName.LOG);

        jobOver(List.of(logSink)).run();

        assertThat(ambient.get())
                .as("a run has no delivery identifiers and never can, so it needs a correlation of "
                        + "its own before it asks for anything")
                .isNotNull();
        assertThat(given.getValue())
                .as("and the service is given it rather than reading the MDC, which is what keeps "
                        + "the application layer free of one and makes the command's path the same")
                .isEqualTo(ambient.get());
        assertThat(RunCorrelation.current())
                .as("the scheduler's threads are pooled: an id left behind is inherited by "
                        + "whatever runs next on that thread, which reads as a true correlation")
                .isNull();
    }

    @Test
    void the_body_takes_its_run_id_from_the_caller_and_never_reads_the_mdc() {
        try (CapturedLog log = CapturedLog.capturing(ExceptionReportJob.class)) {
            final ArgumentCaptor<String> given = ArgumentCaptor.forClass(String.class);
            when(reporting.build(any(), given.capture()))
                    .thenAnswer(call -> emptyReport(call.getArgument(1)));
            delivered(DeliveryStatus.DELIVERED, ReportSinkName.LOG);

            jobOver(List.of(logSink)).report(A_CALLERS_RUN_ID);

            assertThat(given.getValue())
                    .as("the body is documented as directly callable, and a body that reads the "
                            + "MDC is one only its own wrapper can call correctly: called by "
                            + "anything else it reports a run under no correlation at all")
                    .isEqualTo(A_CALLERS_RUN_ID);
            assertThat(theRunLine(log))
                    .as("and the line names the caller's run rather than whatever the calling "
                            + "thread happened to be carrying")
                    .contains("run_id=" + A_CALLERS_RUN_ID);
            assertThat(RunCorrelation.current())
                    .as("the body opens no correlation of its own: minting and removing one is "
                            + "the wrapper's job, and a body that did both would do it twice on "
                            + "the scheduled path")
                    .isNull();
        }
    }

    @Test
    void the_run_line_is_written_after_every_sink_has_returned() {
        try (CapturedLog log = CapturedLog.capturing(ExceptionReportJob.class)) {
            final AtomicReference<List<String>> whenAsked = new AtomicReference<>();
            aQuietMorning();
            when(reporting.deliver(any(), anyCollection())).thenAnswer(call -> {
                whenAsked.set(log.renderings());
                return List.of(outcome(ReportSinkName.LOG, DeliveryStatus.DELIVERED));
            });

            jobOver(List.of(logSink)).run();

            assertThat(whenAsked.get())
                    .as("a job that wrote the line first would be reporting a delivery it had not "
                            + "yet observed")
                    .noneMatch(line -> line.contains(RUN_EVENT));
            assertThat(theRunLine(log))
                    .as("one flat line per run, carrying the window, the count and how each sink "
                            + "went - the shape RegisterGenerationJob.recorded writes after a night")
                    .contains("event=" + RUN_EVENT)
                    .contains("window_from=")
                    .contains("window_to=")
                    .contains("entries=0")
                    .contains("delivered_log=ok")
                    .contains("outcome=delivered")
                    .contains("duration_ms=");
        }
    }

    /**
     * The run line says how much of the morning the cap left out.
     *
     * <p>{@code entries} is what the report holds and {@code truncated} is what it does not, and a
     * line carrying only the first would say a bad night was an ordinary one - the number of lines
     * written is exactly the number a cap makes untrustworthy.
     */
    @Test
    void the_run_line_says_how_many_entries_the_cap_dropped() {
        try (CapturedLog log = CapturedLog.capturing(ExceptionReportJob.class)) {
            when(reporting.build(any(), any())).thenAnswer(call -> new ExceptionReport(
                    call.getArgument(1), ReportWindow.forScheduledRun(CRON, ZONE, FIRED_AT),
                    FIRED_AT, List.of(), 3, Map.of()));
            delivered(DeliveryStatus.DELIVERED, ReportSinkName.LOG);

            jobOver(List.of(logSink)).run();

            assertThat(theRunLine(log))
                    .as("a morning the cap touched says so on its own line, beside the count of "
                            + "what it did carry")
                    .contains("truncated=3");
        }
    }

    @Test
    void delivered_email_is_disabled_where_the_sink_is_not_on_the_context() {
        try (CapturedLog log = CapturedLog.capturing(ExceptionReportJob.class)) {
            aQuietMorning();
            delivered(DeliveryStatus.DELIVERED, ReportSinkName.LOG);

            jobOver(List.of(logSink)).run();

            assertThat(theRunLine(log))
                    .as("the e-mail output is switched off, so there is no sink and nothing was "
                            + "skipped - skipped is the command's word for a sink it chose not to "
                            + "ask, and the job asks every sink there is")
                    .contains("delivered_email=disabled")
                    .doesNotContain("delivered_email=skipped");
        }
    }

    @Test
    void a_run_delivered_to_both_sinks_is_outcome_delivered() {
        try (CapturedLog log = CapturedLog.capturing(ExceptionReportJob.class)) {
            aQuietMorning();
            delivered(DeliveryStatus.DELIVERED, ReportSinkName.LOG, ReportSinkName.EMAIL);

            jobOver(List.of(logSink, emailSink)).run();

            assertThat(theRunLine(log))
                    .contains("delivered_log=ok")
                    .contains("delivered_email=ok")
                    .contains("outcome=delivered");
            assertThat(runs(ReportRunOutcome.DELIVERED)).isEqualTo(1);
        }
    }

    @Test
    void a_run_delivered_to_one_sink_is_outcome_partial() {
        try (CapturedLog log = CapturedLog.capturing(ExceptionReportJob.class)) {
            aQuietMorning();
            when(reporting.deliver(any(), anyCollection())).thenReturn(List.of(
                    outcome(ReportSinkName.LOG, DeliveryStatus.DELIVERED),
                    outcome(ReportSinkName.EMAIL, DeliveryStatus.NOT_DELIVERED)));

            jobOver(List.of(logSink, emailSink)).run();

            assertThat(theRunLine(log))
                    .as("a report that reached one of its two audiences is a partially delivered "
                            + "report and not a failed one (FR-007)")
                    .contains("delivered_log=ok")
                    .contains("delivered_email=failed")
                    .contains("outcome=partial");
            assertThat(runs(ReportRunOutcome.PARTIAL)).isEqualTo(1);
        }
    }

    @Test
    void a_run_delivered_to_neither_is_outcome_failed() {
        try (CapturedLog log = CapturedLog.capturing(ExceptionReportJob.class)) {
            aQuietMorning();
            when(reporting.deliver(any(), anyCollection())).thenReturn(List.of(
                    outcome(ReportSinkName.LOG, DeliveryStatus.NOT_DELIVERED),
                    outcome(ReportSinkName.EMAIL, DeliveryStatus.NOT_DELIVERED)));

            jobOver(List.of(logSink, emailSink)).run();

            assertThat(theRunLine(log))
                    .as("nobody was told what went wrong overnight, which is the one outcome that "
                            + "has to be alertable on its own")
                    .contains("outcome=failed");
            assertThat(runs(ReportRunOutcome.FAILED)).isEqualTo(1);
        }
    }

    @Test
    void a_run_asked_of_one_sink_that_failed_is_outcome_failed() {
        try (CapturedLog log = CapturedLog.capturing(ExceptionReportJob.class)) {
            aQuietMorning();
            delivered(DeliveryStatus.NOT_DELIVERED, ReportSinkName.LOG);

            jobOver(List.of(logSink)).run();

            assertThat(theRunLine(log))
                    .as("the MVP's own deployment holds one sink, so a morning it refused is a "
                            + "morning nobody was told about - and a fold that only counted "
                            + "refusals against a second sink would call that one delivered")
                    .contains("delivered_log=failed")
                    .contains("delivered_email=disabled")
                    .contains("outcome=failed");
            assertThat(runs(ReportRunOutcome.FAILED)).isEqualTo(1);
        }
    }

    @Test
    void every_outcome_is_counted_on_the_runs_counter_so_a_quiet_morning_and_a_missing_run_are_different_observations() {
        aQuietMorning();
        delivered(DeliveryStatus.DELIVERED, ReportSinkName.LOG);

        jobOver(List.of(logSink)).run();

        assertThat(runs(ReportRunOutcome.DELIVERED))
                .as("a morning with nothing wrong still counts a run: without it, a report that "
                        + "found nothing and a report that never fired are the same absence of a "
                        + "series, and the second is the failure this feature exists to reveal")
                .isEqualTo(1);
        assertThat(runs(ReportRunOutcome.FAILED)).isEqualTo(ABSENT);
    }

    @Test
    void a_read_failure_counts_the_run_failed_and_rethrows() {
        try (CapturedLog log = CapturedLog.capturing(ExceptionReportJob.class)) {
            when(reporting.build(any(), any())).thenThrow(new StoreUnavailableException(
                    "the store could not be reached to read what went wrong overnight",
                    new IllegalStateException("the connection pool is empty")));

            assertThatThrownBy(jobOver(List.of(logSink, emailSink))::run)
                    .as("the other branch from the sweep's: this read IS the report, there is a "
                            + "lock to release, and a failure only logged about has not been "
                            + "settled (Principle VI)")
                    .isInstanceOf(StoreUnavailableException.class);

            assertThat(runs(ReportRunOutcome.FAILED)).isEqualTo(1);
            assertThat(theRunLine(log))
                    .as("and the line is still written, so the morning that produced no report "
                            + "says so rather than saying nothing at all")
                    .contains("outcome=failed")
                    .contains("delivered_log=failed");
        }
    }

    @Test
    void the_failure_paths_run_line_carries_every_field() {
        try (CapturedLog log = CapturedLog.capturing(ExceptionReportJob.class)) {
            when(reporting.build(any(), any())).thenThrow(new StoreUnavailableException(
                    "the store could not be reached to read what went wrong overnight",
                    new IllegalStateException("the connection pool is empty")));

            assertThatThrownBy(jobOver(List.of(logSink, emailSink))::run)
                    .isInstanceOf(StoreUnavailableException.class);

            assertThat(theRunLine(log))
                    .as("the morning that produced nothing is the morning whose line is read "
                            + "hardest, so it is the same line with the same fields and not a "
                            + "shorter one a saved query would have to allow for separately")
                    .contains("window_from=")
                    .contains("window_to=")
                    .contains("entries=0")
                    .contains("delivered_email=failed")
                    .contains("duration_ms=");
        }
    }

    @Test
    void the_cutover_flag_is_never_read() {
        assertThat(Arrays.stream(ExceptionReportJob.class.getDeclaredFields())
                        .map(Field::getType)
                        .map(Class::getSimpleName)
                        .filter(held -> held.contains("Flag"))
                        .toList())
                .as("the report reads and writes nothing the cutover decides, so it is not on the "
                        + "lever's circuit and adds no second reader of YotResultsDistributionService "
                        + "(constitution Cutover Rule)")
                .isEmpty();
    }

    /** The job over the sinks this context holds. */
    private ExceptionReportJob jobOver(final List<ExceptionReportSink> sinks) {
        return new ExceptionReportJob(reporting, sinks, CRON, ZONE, metrics, clock);
    }

    /** Answers {@code build} with an empty report over whatever window was asked for. */
    private void aQuietMorning() {
        when(reporting.build(any(), any()))
                .thenAnswer(call -> emptyReport(call.getArgument(1)));
    }

    /** Answers {@code deliver} with one outcome of the given status per named sink. */
    private void delivered(final DeliveryStatus status, final ReportSinkName... sinks) {
        when(reporting.deliver(any(), anyCollection())).thenReturn(
                Arrays.stream(sinks).map(sink -> outcome(sink, status)).toList());
    }

    /** A morning with nothing wrong on it, which is what most of these cases are about. */
    private static ExceptionReport emptyReport(final String runId) {
        return ExceptionReport.whole(runId, ReportWindow.forScheduledRun(CRON, ZONE, FIRED_AT),
                FIRED_AT, List.of());
    }

    private static DeliveryOutcome outcome(
            final ReportSinkName sink, final DeliveryStatus status) {
        return status == DeliveryStatus.DELIVERED
                ? DeliveryOutcome.delivered(sink)
                : new DeliveryOutcome(sink, status, ReportDeliveryReason.SEND_FAILED, 0, 1);
    }

    /** A sink that says who it is and takes whatever it is given. */
    private static ExceptionReportSink sinkNamed(final ReportSinkName name) {
        return new ExceptionReportSink() {
            @Override
            public ReportSinkName name() {
                return name;
            }

            @Override
            public DeliveryOutcome deliver(final ExceptionReport report) {
                return DeliveryOutcome.delivered(name);
            }
        };
    }

    /** The one line the run leaves behind, or a message saying it left none. */
    private static String theRunLine(final CapturedLog log) {
        return log.renderings().stream()
                .filter(line -> line.contains("event=" + RUN_EVENT))
                .findFirst()
                .orElse("no " + RUN_EVENT + " line was written at all");
    }

    private double runs(final ReportRunOutcome outcome) {
        final Counter found = registry.find(ProcessingMetrics.EXCEPTION_REPORT_RUNS)
                .tag(ProcessingMetrics.OUTCOME_TAG, outcome.name().toLowerCase(Locale.ROOT))
                .counter();
        return found == null ? ABSENT : found.count();
    }
}
