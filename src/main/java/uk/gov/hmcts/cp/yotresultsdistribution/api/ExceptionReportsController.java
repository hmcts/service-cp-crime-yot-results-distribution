package uk.gov.hmcts.cp.yotresultsdistribution.api;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.yotresultsdistribution.api.dto.ExceptionReportRequest;
import uk.gov.hmcts.cp.yotresultsdistribution.api.dto.ExceptionReportResponse;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OnDemandExceptionReport;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OnDemandExceptionReportService;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionEntry;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionKind;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionReport;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportSinkName;

/**
 * {@code POST /operations/exception-reports}, which replaces {@code report-exceptions}.
 *
 * <p>An inbound adapter. It reads two optional fields, calls
 * {@link OnDemandExceptionReportService} once, and maps the answer. No logic, no read, no decision.
 *
 * <p><strong>Synchronous, and that is deliberate</strong> (FR-022): the report is built and every
 * sink that was asked has been delivered to before this answers, so an operator who asked for the
 * e-mail knows whether it went. It is the one endpoint of the seven that does real reads and still
 * answers inline, because the reads are bounded by the entry cap and the window, unlike a night's
 * worth of renders.
 *
 * <p><strong>It reads the cutover flag nowhere</strong>, exactly as its command did not. The
 * report is this service's account of itself and sits on no cutover circuit.
 *
 * <p>{@code @Profile("!test")} and {@code yotresultsdistribution.operations.enabled} for the reasons
 * {@link BatchesController} carries them: the reads behind this endpoint are declared {@code !test}
 * and that profile has no database, and the switch that stops this service answering the operator's
 * paths must withdraw this one too rather than leave a controller scanned over a service that is
 * gone (FR-044).
 */
@RestController
@Profile("!test")
@ConditionalOnProperty(prefix = "yotresultsdistribution.operations", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ExceptionReportsController {

    /** The report, its window and its sinks, behind the one call this adapter makes. */
    private final OnDemandExceptionReportService reports;

    /**
     * Creates the endpoint over the service that does the work.
     *
     * @param reportService the on-demand report
     */
    public ExceptionReportsController(final OnDemandExceptionReportService reportService) {
        this.reports = reportService;
    }

    /**
     * Builds the report over the window asked for and delivers it to the sinks asked for.
     *
     * @param request what the caller asked for, or {@code null} where they sent no body
     * @return the report, or the {@code 500} that says which sink is owed a resend
     * @throws uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException where the window
     *         will not read or the e-mail output was asked for and is unavailable, which
     *         {@link OperationsExceptionHandler} answers from the one status map
     */
    @PostMapping(path = "/operations/exception-reports",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> report(
            @RequestBody(required = false) final ExceptionReportRequest request) {

        final ExceptionReportRequest asked =
                request == null ? ExceptionReportRequest.NOTHING : request;
        final OnDemandExceptionReport answered =
                reports.report(asked.since(), asked.emailAsked());
        return answered.everySinkTookIt()
                ? ResponseEntity.ok(bodyOf(answered))
                : notDelivered(answered);
    }

    /**
     * The report that was built and that a sink did not take.
     *
     * <p>A {@code 500} rather than a refusal, because the call tried and got part-way: the reads
     * happened and the report exists. {@code delivered} travels with it so the operator can see
     * which sink is owed a resend without having to go and read a log line to find out.
     *
     * @param answered what the run produced
     * @return the {@code 500}
     */
    private static ResponseEntity<Object> notDelivered(final OnDemandExceptionReport answered) {
        return OperationsProblem.answering(HttpStatus.INTERNAL_SERVER_ERROR,
                OperationsReason.REPORT_NOT_DELIVERED, null,
                Map.of("delivered", words(answered.delivered()),
                        "runId", answered.runId()));
    }

    /**
     * The success body, in the field order data-model §7 states it in.
     *
     * @param answered what the run produced
     * @return the body
     */
    private static ExceptionReportResponse bodyOf(final OnDemandExceptionReport answered) {
        final ExceptionReport report = answered.report();
        return new ExceptionReportResponse(
                answered.runId(),
                new ExceptionReportResponse.Window(report.window().from(), report.window().to()),
                report.entries().stream().map(ExceptionReportsController::entryOf).toList(),
                counts(report.counts()),
                report.truncated(),
                words(answered.delivered()),
                answered.outcome().name().toLowerCase(Locale.ROOT),
                answered.durationMs());
    }

    /**
     * One exception as the row a support engineer reads.
     *
     * @param entry what the reads found
     * @return the row, whose absent fields the dto omits
     */
    private static ExceptionReportResponse.Entry entryOf(final ExceptionEntry entry) {
        return new ExceptionReportResponse.Entry(entry.kind(), entry.source(), entry.requestId(),
                entry.hearingId(), entry.hearingDay(), entry.batchId(), entry.notificationId(),
                entry.courtCentreId(), entry.registerDate(), entry.status(), entry.attempts(),
                entry.reason(), entry.ageSeconds());
    }

    /**
     * The per-kind counts, keyed the way the command's counts line named them.
     *
     * @param counted one count per kind, zero-filled by the report itself
     * @return the same counts under lower-case kind names
     */
    private static Map<String, Integer> counts(final Map<ExceptionKind, Integer> counted) {
        final Map<String, Integer> named = new LinkedHashMap<>();
        for (final ExceptionKind kind : ExceptionKind.values()) {
            named.put(kind.name().toLowerCase(Locale.ROOT), counted.get(kind));
        }
        return named;
    }

    /**
     * The two sinks' words, keyed by sink name.
     *
     * @param said what each sink got
     * @return the same words under lower-case sink names
     */
    private static Map<String, String> words(final Map<ReportSinkName, String> said) {
        final Map<String, String> named = new LinkedHashMap<>();
        for (final ReportSinkName sink : ReportSinkName.values()) {
            named.put(sink.name().toLowerCase(Locale.ROOT), said.get(sink));
        }
        return named;
    }
}
