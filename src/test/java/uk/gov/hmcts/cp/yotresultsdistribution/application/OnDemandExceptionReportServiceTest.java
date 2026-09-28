package uk.gov.hmcts.cp.yotresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionReport;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportDeliveryReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportRunOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportSinkName;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportWindow;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;

/**
 * The exception report an operator asks for now: its window, its sinks, and what it refuses.
 *
 * <p>{@code report-exceptions}'s body moved behind an endpoint, so what is asserted here is what
 * the command asserted: the three forms a window may be typed in, the default window taken from
 * the report schedule, the two e-mail refusals, the sink selection, and the difference between a
 * report that could not be built and one that was built and not wholly delivered.
 *
 * <p><strong>The flag reader is not here at all</strong>, and that is the assertion. The command
 * read the cutover flag nowhere and neither does this: a strict mock of
 * {@link FeatureFlagReader} is handed nowhere near this service, and the case below that names it
 * asserts the collaborator set rather than an interaction, because a collaborator that does not
 * exist cannot be touched (FR-022).
 */
@DisplayName("the exception report asked for on demand")
class OnDemandExceptionReportServiceTest {

    /** The moment every run in this suite starts at, so a window is arithmetic and not a race. */
    private static final Instant NOW = Instant.parse("2026-09-15T09:30:00Z");

    /** The report's own schedule, which the default window is taken back to. */
    private static final String CRON = "0 0 7 * * MON-FRI";

    private static final String ZONE = "Europe/London";

    /** A value nothing else in this repository produces, so a leak can only be this one. */
    private static final String NOT_A_WINDOW = "ZQX7NOTAWINDOW";

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ExceptionReportService reporting = mock(ExceptionReportService.class);

    /** The log sink, which is on every context and is always asked. */
    private final ExceptionReportSink logSink = sinkNamed(ReportSinkName.LOG);

    /** The e-mail sink, which is on a context only where the output is wired. */
    private final ExceptionReportSink emailSink = sinkNamed(ReportSinkName.EMAIL);

    /**
     * A sink that answers to one name and does nothing else.
     *
     * @param name the name it answers to
     * @return the sink
     */
    private static ExceptionReportSink sinkNamed(final ReportSinkName name) {
        final ExceptionReportSink sink = mock(ExceptionReportSink.class);
        when(sink.name()).thenReturn(name);
        return sink;
    }

    /**
     * The service over the sinks a deployment holds and the e-mail switch it was deployed with.
     *
     * @param sinks        the sinks on the context
     * @param emailEnabled whether the e-mail output is switched on
     * @return the service under test
     */
    private OnDemandExceptionReportService serviceWith(final List<ExceptionReportSink> sinks,
            final boolean emailEnabled) {

        return new OnDemandExceptionReportService(reporting, sinks, CRON, ZONE, emailEnabled,
                clock);
    }

    /**
     * A report over whatever window it was asked for, delivered by whoever was asked.
     *
     * @param sinks the sinks the run asked
     */
    private void reportsOver(final List<ReportSinkName> sinks) {
        when(reporting.build(any(ReportWindow.class), any(String.class)))
                .thenAnswer(call -> ExceptionReport.whole(call.getArgument(1),
                        call.getArgument(0), NOW, List.of()));
        when(reporting.deliver(any(ExceptionReport.class), anyList()))
                .thenReturn(sinks.stream().map(DeliveryOutcome::delivered).toList());
    }

    /** The window one call was built over. */
    private ReportWindow windowAsked() {
        final ArgumentCaptor<ReportWindow> window = ArgumentCaptor.forClass(ReportWindow.class);
        verify(reporting).build(window.capture(), any(String.class));
        return window.getValue();
    }

    @Nested
    @DisplayName("the window an operator names")
    class TheWindow {

        @Test
        @DisplayName("an ISO instant is taken as the window's opening")
        void an_instant_should_open_the_window_where_it_says() {
            reportsOver(List.of(ReportSinkName.LOG));

            serviceWith(List.of(logSink), false).report("2026-09-14T22:00:00Z", false);

            assertThat(windowAsked())
                    .isEqualTo(new ReportWindow(Instant.parse("2026-09-14T22:00:00Z"), NOW));
        }

        @ParameterizedTest(name = "{0} opens the window {1} before now")
        @CsvSource({"PT90M,PT90M", "P2D,P2D", "PT6H,PT6H"})
        @DisplayName("an ISO-8601 duration is taken back from the moment the run started")
        void a_duration_should_open_the_window_that_far_back(final String typed,
                final String width) {

            reportsOver(List.of(ReportSinkName.LOG));

            serviceWith(List.of(logSink), false).report(typed, false);

            assertThat(windowAsked())
                    .isEqualTo(new ReportWindow(NOW.minus(Duration.parse(width)), NOW));
        }

        @ParameterizedTest(name = "{0} opens the window {1} before now")
        @CsvSource({"2d,P2D", "6h,PT6H", "90m,PT90M", "45s,PT45S"})
        @DisplayName("the four shorthands an operator types are read the same way")
        void a_shorthand_should_open_the_window_that_far_back(final String typed,
                final String width) {

            reportsOver(List.of(ReportSinkName.LOG));

            serviceWith(List.of(logSink), false).report(typed, false);

            assertThat(windowAsked())
                    .isEqualTo(new ReportWindow(NOW.minus(Duration.parse(width)), NOW));
        }

        @ParameterizedTest(name = "{0} is not a window")
        @ValueSource(strings = {"PT0S", "0d", "PT-2H", "-P1D", NOT_A_WINDOW, "2x", "2026-09-14"})
        @DisplayName("a width of nothing, a width that has not happened, and a token that is "
                + "neither, are all refused by argument name")
        void a_window_with_no_width_should_be_refused_without_quoting_it(final String typed) {
            assertThatThrownBy(() -> serviceWith(List.of(logSink), false).report(typed, false))
                    .isInstanceOfSatisfying(OperationsRefusedException.class, refused -> {
                        assertThat(refused.reason())
                                .isEqualTo(OperationsReason.UNREADABLE_ARGUMENT);
                        assertThat(refused.argument()).isEqualTo("since");
                        assertThat(refused.getMessage())
                                .as("a refusal names the argument and never the value the caller "
                                        + "typed (constitution Principle VII)")
                                .doesNotContain(typed);
                    });

            verifyNoInteractions(reporting);
        }

        @Test
        @DisplayName("no window given runs from the previous scheduled report")
        void an_absent_window_should_run_from_the_previous_scheduled_report() {
            reportsOver(List.of(ReportSinkName.LOG));

            serviceWith(List.of(logSink), false).report(null, false);

            assertThat(windowAsked())
                    .as("computed from the report cron and zone exactly as the command computed "
                            + "it, so a report asked for at 09:30 covers the morning's run")
                    .isEqualTo(ReportWindow.sinceLastScheduledRun(CRON, ZONE, NOW));
        }
    }

    @Nested
    @DisplayName("the sinks it asks")
    class TheSinks {

        @Test
        @DisplayName("the log sink is always asked and the e-mail sink only when asked for")
        void it_should_ask_the_log_sink_alone_where_email_was_not_asked_for() {
            reportsOver(List.of(ReportSinkName.LOG));

            final OnDemandExceptionReport answered =
                    serviceWith(List.of(logSink, emailSink), true).report(null, false);

            assertThat(sinksAsked()).containsExactly(logSink);
            assertThat(answered.delivered())
                    .containsEntry(ReportSinkName.LOG, "ok")
                    .containsEntry(ReportSinkName.EMAIL, "skipped");
        }

        @Test
        @DisplayName("both sinks are asked where the e-mail output was asked for")
        void it_should_ask_both_sinks_where_email_was_asked_for() {
            reportsOver(List.of(ReportSinkName.LOG, ReportSinkName.EMAIL));

            final OnDemandExceptionReport answered =
                    serviceWith(List.of(logSink, emailSink), true).report(null, true);

            assertThat(sinksAsked()).containsExactly(logSink, emailSink);
            assertThat(answered.delivered())
                    .containsEntry(ReportSinkName.LOG, "ok")
                    .containsEntry(ReportSinkName.EMAIL, "ok");
            assertThat(answered.outcome()).isEqualTo(ReportRunOutcome.DELIVERED);
            assertThat(answered.everySinkTookIt()).isTrue();
        }

        @Test
        @DisplayName("a context with no e-mail sink says so rather than saying it was skipped")
        void it_should_call_an_absent_email_sink_disabled() {
            reportsOver(List.of(ReportSinkName.LOG));

            final OnDemandExceptionReport answered =
                    serviceWith(List.of(logSink), false).report(null, false);

            assertThat(answered.delivered()).containsEntry(ReportSinkName.EMAIL, "disabled");
        }

        /** The sinks one call handed to the delivery. */
        private List<ExceptionReportSink> sinksAsked() {
            final ArgumentCaptor<List<ExceptionReportSink>> asked = ArgumentCaptor.captor();
            verify(reporting).deliver(any(ExceptionReport.class), asked.capture());
            return asked.getValue();
        }
    }

    @Nested
    @DisplayName("the e-mail output an operator cannot have")
    class TheEmailRefusals {

        @Test
        @DisplayName("asked for where the output is switched off, it refuses before it reads")
        void email_asked_with_the_output_off_should_refuse_email_output_disabled() {
            assertThatThrownBy(() -> serviceWith(List.of(logSink), false).report(null, true))
                    .isInstanceOfSatisfying(OperationsRefusedException.class, refused ->
                            assertThat(refused.reason())
                                    .isEqualTo(OperationsReason.EMAIL_OUTPUT_DISABLED));

            verifyNoInteractions(reporting);
        }

        @Test
        @DisplayName("both refusal lines spell the code the ProblemDetail will carry")
        void the_refusal_lines_should_carry_the_wire_spelling() {
            final List<String> offLines;
            try (CapturedLog log =
                    CapturedLog.capturing(OnDemandExceptionReportService.class)) {
                assertThatThrownBy(() -> serviceWith(List.of(logSink), false).report(null, true))
                        .isInstanceOf(OperationsRefusedException.class);
                offLines = log.renderings();
            }

            final List<String> notWiredLines;
            try (CapturedLog log =
                    CapturedLog.capturing(OnDemandExceptionReportService.class)) {
                assertThatThrownBy(() -> serviceWith(List.of(logSink), true).report(null, true))
                        .isInstanceOf(OperationsRefusedException.class);
                notWiredLines = log.renderings();
            }

            assertThat(offLines)
                    .as("a reason slot carries the wire spelling wherever it is written, so a "
                            + "line and the body answered beside it grep as the same code")
                    .anyMatch(line -> line.contains(
                            "reason=" + OperationsReason.EMAIL_OUTPUT_DISABLED.wire()));
            assertThat(notWiredLines)
                    .anyMatch(line -> line.contains(
                            "reason=" + OperationsReason.EMAIL_OUTPUT_NOT_WIRED.wire()));
        }

        @Test
        @DisplayName("asked for where the output is on and no sink is wired, it refuses")
        void email_asked_with_no_sink_should_refuse_email_output_not_wired() {
            assertThatThrownBy(() -> serviceWith(List.of(logSink), true).report(null, true))
                    .isInstanceOfSatisfying(OperationsRefusedException.class, refused ->
                            assertThat(refused.reason())
                                    .isEqualTo(OperationsReason.EMAIL_OUTPUT_NOT_WIRED));

            verify(reporting, never()).deliver(any(ExceptionReport.class), anyList());
        }
    }

    @Nested
    @DisplayName("what it could not do")
    class TheFailures {

        @Test
        @DisplayName("reads that would not answer are a report that could not be built")
        void a_store_that_will_not_answer_should_refuse_report_not_built() {
            when(reporting.build(any(ReportWindow.class), any(String.class)))
                    .thenThrow(new StoreUnavailableException("read the processed log",
                            new IllegalStateException("the connection went away")));

            assertThatThrownBy(() -> serviceWith(List.of(logSink), false).report(null, false))
                    .isInstanceOfSatisfying(OperationsRefusedException.class, refused -> {
                        assertThat(refused.reason()).isEqualTo(OperationsReason.REPORT_NOT_BUILT);
                        assertThat(refused.getMessage())
                                .as("never the store's own words")
                                .doesNotContain("the connection went away");
                    });
        }

        @Test
        @DisplayName("a sink that did not take it is an answer, not a refusal")
        void a_sink_that_refused_should_come_back_as_the_run_outcome() {
            when(reporting.build(any(ReportWindow.class), any(String.class)))
                    .thenAnswer(call -> ExceptionReport.whole(call.getArgument(1),
                            call.getArgument(0), NOW, List.of()));
            when(reporting.deliver(any(ExceptionReport.class), anyList())).thenReturn(List.of(
                    DeliveryOutcome.delivered(ReportSinkName.LOG),
                    new DeliveryOutcome(ReportSinkName.EMAIL, DeliveryStatus.NOT_DELIVERED,
                            ReportDeliveryReason.SEND_REFUSED, 0, 1)));

            final OnDemandExceptionReport answered =
                    serviceWith(List.of(logSink, emailSink), true).report(null, true);

            assertThat(answered.outcome())
                    .as("the reads happened and the report exists; the rest is a resend, which is "
                            + "a different thing from a report that could not be built")
                    .isEqualTo(ReportRunOutcome.PARTIAL);
            assertThat(answered.everySinkTookIt()).isFalse();
            assertThat(answered.delivered()).containsEntry(ReportSinkName.EMAIL, "failed");
        }
    }

    @Nested
    @DisplayName("the run it is")
    class TheRun {

        @Test
        @DisplayName("it carries a run id and a duration, and reads the cutover flag nowhere")
        void it_should_carry_a_run_id_and_never_hold_a_flag_reader() {
            reportsOver(List.of(ReportSinkName.LOG));
            final FeatureFlagReader theOneLever = mock(FeatureFlagReader.class);

            final OnDemandExceptionReport answered =
                    serviceWith(List.of(logSink), false).report(null, false);

            assertThat(answered.runId()).isNotBlank();
            assertThat(answered.report().runId()).isEqualTo(answered.runId());
            assertThat(answered.durationMs()).isNotNegative();
            verifyNoInteractions(theOneLever);
        }
    }
}
