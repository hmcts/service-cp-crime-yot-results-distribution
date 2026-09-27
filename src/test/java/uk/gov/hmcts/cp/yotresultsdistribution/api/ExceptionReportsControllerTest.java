package uk.gov.hmcts.cp.yotresultsdistribution.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OnDemandExceptionReport;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OnDemandExceptionReportService;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionEntry;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionKind;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionReport;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportRunOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportSinkName;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportWindow;

/**
 * What {@code POST /operations/exception-reports} answers, and what it refuses.
 *
 * <p>The report itself has suites of its own; this one is about the mapping and the refusals -
 * that the success shape is data-model §7, that an empty window answers a list and counts rather
 * than silence, that every refusal is a bounded code with no value the caller typed anywhere in
 * it, and that a report which was built and not wholly delivered is told apart from one that could
 * not be built at all.
 *
 * <p>Both estate filters are off. The authorisation and audit conditions have suites of their own.
 */
@WebMvcTest(controllers = ExceptionReportsController.class, properties = {
    "authz.http.enabled=false",
    "audit.http.enabled=false",
    "cp.audit.enabled=false",
})
@DisplayName("the exception-report endpoint")
class ExceptionReportsControllerTest {

    /** The path, written out so a change to it fails here rather than silently 404ing. */
    private static final String PATH = "/operations/exception-reports";

    private static final Instant TO = Instant.parse("2026-09-15T09:30:00Z");

    private static final Instant FROM = TO.minus(Duration.ofHours(26));

    private static final String RUN_ID = "run-4b19c7e0";

    /** A value nothing else in this repository produces, so a leak can only be this one. */
    private static final String NOT_A_WINDOW = "ZQX7NOTAWINDOW";

    private static final UUID REQUEST = UUID.fromString("11111111-2222-4333-8444-555555555555");

    private static final UUID HEARING = UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private OnDemandExceptionReportService reports;

    /**
     * A run that produced the given report and told both sinks whatever is said here.
     *
     * @param report  what the reads found
     * @param outcome how the run as a whole went
     * @param words   what each sink got
     * @return the run's result
     */
    private static OnDemandExceptionReport run(final ExceptionReport report,
            final ReportRunOutcome outcome, final Map<ReportSinkName, String> words) {

        return new OnDemandExceptionReport(RUN_ID, report,
                List.of(DeliveryOutcome.delivered(ReportSinkName.LOG)), words, outcome, 128L);
    }

    /** The two sinks' words for a run that told the log and was not asked for the e-mail. */
    private static Map<ReportSinkName, String> logOnly() {
        final Map<ReportSinkName, String> words = new EnumMap<>(ReportSinkName.class);
        words.put(ReportSinkName.LOG, "ok");
        words.put(ReportSinkName.EMAIL, "skipped");
        return words;
    }

    @Nested
    @DisplayName("a report that was built and delivered")
    class TheReport {

        @Test
        @DisplayName("it answers the shape of data-model §7")
        void it_should_answer_the_report_shape() throws Exception {
            final ExceptionEntry entry = new ExceptionEntry(ExceptionKind.REQUEST_FAILED,
                    "CPP", REQUEST, HEARING, LocalDate.parse("2026-09-14"), null, null, null,
                    null, "FAILED", 3, "schema-violation", 5400L);
            when(reports.report(null, false)).thenReturn(run(
                    ExceptionReport.whole(RUN_ID, new ReportWindow(FROM, TO), TO, List.of(entry)),
                    ReportRunOutcome.DELIVERED, logOnly()));

            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.runId").value(RUN_ID))
                    .andExpect(jsonPath("$.window.from").value(FROM.toString()))
                    .andExpect(jsonPath("$.window.to").value(TO.toString()))
                    .andExpect(jsonPath("$.entries.length()").value(1))
                    .andExpect(jsonPath("$.entries[0].kind").value("REQUEST_FAILED"))
                    .andExpect(jsonPath("$.entries[0].source").value("CPP"))
                    .andExpect(jsonPath("$.entries[0].requestId").value(REQUEST.toString()))
                    .andExpect(jsonPath("$.entries[0].hearingId").value(HEARING.toString()))
                    .andExpect(jsonPath("$.entries[0].hearingDay").value("2026-09-14"))
                    .andExpect(jsonPath("$.entries[0].status").value("FAILED"))
                    .andExpect(jsonPath("$.entries[0].attempts").value(3))
                    .andExpect(jsonPath("$.entries[0].reason").value("schema-violation"))
                    .andExpect(jsonPath("$.entries[0].ageSeconds").value(5400))
                    .andExpect(jsonPath("$.entries[0].batchId").doesNotExist())
                    .andExpect(jsonPath("$.entries[0].notificationId").doesNotExist())
                    .andExpect(jsonPath("$.entries[0].registerDate").doesNotExist())
                    .andExpect(jsonPath("$.counts.request_failed").value(1))
                    .andExpect(jsonPath("$.counts.batch_late").value(0))
                    .andExpect(jsonPath("$.truncated").value(0))
                    .andExpect(jsonPath("$.delivered.log").value("ok"))
                    .andExpect(jsonPath("$.delivered.email").value("skipped"))
                    .andExpect(jsonPath("$.outcome").value("delivered"))
                    .andExpect(jsonPath("$.durationMs").value(128));
        }

        @Test
        @DisplayName("a window with nothing wrong in it answers a list and the counts, not silence")
        void an_empty_window_should_answer_an_empty_list_and_the_counts() throws Exception {
            when(reports.report(null, false)).thenReturn(run(
                    ExceptionReport.whole(RUN_ID, new ReportWindow(FROM, TO), TO, List.of()),
                    ReportRunOutcome.DELIVERED, logOnly()));

            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entries").isArray())
                    .andExpect(jsonPath("$.entries.length()").value(0))
                    .andExpect(jsonPath("$.counts.request_failed").value(0))
                    .andExpect(jsonPath("$.counts.notification_failed").value(0));
        }

        @Test
        @DisplayName("the two arguments reach the service as they were asked for")
        void it_should_pass_the_window_and_the_email_flag_through() throws Exception {
            when(reports.report(eq("2d"), eq(true))).thenReturn(run(
                    ExceptionReport.whole(RUN_ID, new ReportWindow(FROM, TO), TO, List.of()),
                    ReportRunOutcome.DELIVERED, logOnly()));

            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"since\":\"2d\",\"email\":true}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("no body at all is the default window and no e-mail")
        void no_body_should_be_read_as_the_default_window() throws Exception {
            when(reports.report(null, false)).thenReturn(run(
                    ExceptionReport.whole(RUN_ID, new ReportWindow(FROM, TO), TO, List.of()),
                    ReportRunOutcome.DELIVERED, logOnly()));

            mvc.perform(post(PATH)).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("what it refuses")
    class TheRefusals {

        @Test
        @DisplayName("the e-mail output switched off is a 409 under its bounded code")
        void email_with_the_output_off_should_answer_409() throws Exception {
            when(reports.report(any(), anyBoolean())).thenThrow(
                    new OperationsRefusedException(OperationsReason.EMAIL_OUTPUT_DISABLED));

            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":true}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.reason").value("email-output-disabled"))
                    .andExpect(jsonPath("$.detail").doesNotExist())
                    .andExpect(jsonPath("$.instance").doesNotExist());
        }

        @Test
        @DisplayName("the e-mail output with no sink behind it is a 409 under its own code")
        void email_with_no_sink_should_answer_409() throws Exception {
            when(reports.report(any(), anyBoolean())).thenThrow(
                    new OperationsRefusedException(OperationsReason.EMAIL_OUTPUT_NOT_WIRED));

            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":true}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.reason").value("email-output-not-wired"));
        }

        @Test
        @DisplayName("a window that will not read is a 400 naming the argument, never the value")
        void an_unreadable_window_should_answer_400_without_echoing_it() throws Exception {
            when(reports.report(any(), anyBoolean())).thenThrow(
                    new OperationsRefusedException(OperationsReason.UNREADABLE_ARGUMENT, "since",
                            new IllegalArgumentException("a window is an instant")));

            final String body = mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"since\":\"" + NOT_A_WINDOW + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("unreadable-argument"))
                    .andExpect(jsonPath("$.argument").value("since"))
                    .andExpect(jsonPath("$.detail").doesNotExist())
                    .andReturn().getResponse().getContentAsString();

            Assertions.assertThat(body)
                    .as("a refusal names the argument and never the value the caller typed "
                            + "(constitution Principle VII, FR-024)")
                    .doesNotContain(NOT_A_WINDOW);
        }

        @Test
        @DisplayName("reads that would not answer are a 500 under report-not-built")
        void a_report_that_could_not_be_built_should_answer_500() throws Exception {
            when(reports.report(any(), anyBoolean())).thenThrow(
                    new OperationsRefusedException(OperationsReason.REPORT_NOT_BUILT));

            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.reason").value("report-not-built"));
        }

        @Test
        @DisplayName("a sink that did not take it is a 500 saying which sink is owed a resend")
        void a_report_a_sink_refused_should_answer_500_with_the_delivery() throws Exception {
            final Map<ReportSinkName, String> words = new EnumMap<>(ReportSinkName.class);
            words.put(ReportSinkName.LOG, "ok");
            words.put(ReportSinkName.EMAIL, "failed");
            when(reports.report(any(), anyBoolean())).thenReturn(run(
                    ExceptionReport.whole(RUN_ID, new ReportWindow(FROM, TO), TO, List.of()),
                    ReportRunOutcome.PARTIAL, words));

            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":true}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.reason").value("report-not-delivered"))
                    .andExpect(jsonPath("$.delivered.log").value("ok"))
                    .andExpect(jsonPath("$.delivered.email").value("failed"))
                    .andExpect(jsonPath("$.runId").value(RUN_ID))
                    .andExpect(jsonPath("$.detail").doesNotExist());
        }
    }
}
