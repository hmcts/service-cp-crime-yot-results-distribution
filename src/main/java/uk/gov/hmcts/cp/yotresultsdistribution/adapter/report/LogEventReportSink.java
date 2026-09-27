package uk.gov.hmcts.cp.yotresultsdistribution.adapter.report;

import static net.logstash.logback.argument.StructuredArguments.value;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.logstash.logback.argument.StructuredArgument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ExceptionReportSink;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionEntry;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionKind;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionReport;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportSinkName;

/**
 * The report, written as the two structured events Log Analytics reads.
 *
 * <p><strong>The only class in {@code src/main} that imports
 * {@code net.logstash.logback.argument.StructuredArguments}</strong>, and that containment is the
 * whole reason the log output is a sink rather than a {@code log.info} in the service. Log
 * Analytics has to read {@code kind}, {@code request_id} and {@code batch_id} as separate fields
 * (SC-003, FR-005), which means the values must reach the encoder as structured arguments; the
 * shape of a structured event is an adapter's concern in exactly the way an HTTP body is, so the
 * application layer imports no logging library (Principle V).
 *
 * <p>Two events. {@code yotresultsdistribution_exception_report}, once per run, carries twelve fields: the
 * event name, the run id, the window's two ends, the snapshot, <strong>one count per
 * {@link ExceptionKind}</strong> - present even when they are nought, so an empty morning is
 * distinguishable from a morning the report did not run - and the number the entry cap dropped.
 * The counts are of what the reads found and the events are of what the report carries, so
 * without that last field a query would find fewer events than the counts imply and nothing would
 * say whether a sink had broken.
 *
 * <p><strong>One count per kind, and the list is checked against the enumeration by
 * {@code LogEventReportSinkTest}.</strong> Increment 004 added {@code BATCH_RELEASED} and extended
 * the CSV sink's header and the command's counts line to six while leaving this line at five, so a
 * morning whose only exception was a released batch wrote five noughts beside one
 * {@code yotresultsdistribution_exception} event - the exact shape the paragraph above tells a reader to
 * treat as a sink that broke. A kind added without a count here is that bug again.
 *
 * <p>{@code yotresultsdistribution_exception}, once per entry, carries the event name, the run id, the
 * kind, and then <strong>only the fields that apply to that kind</strong>: an absent field is what
 * makes a KQL {@code isnotempty()} mean what it says, where a null would read as "was not known"
 * rather than as "does not apply".
 *
 * <p><strong>The summary claims no delivery.</strong> It is written by a sink, while the other sink
 * may not have been asked yet and while this one cannot observe its own write reaching an index. A
 * line announcing {@code delivered_email} would be reporting an outcome nobody had observed. How
 * the report travelled is the run's own fact, and it belongs to whoever held every outcome - the
 * job, or the command, after every sink has returned.
 *
 * <p>Nothing is rendered into the message text of either line. A value inside the message is a
 * value every saved query has to {@code parse()} back out, which is exactly the field parsing the
 * structured arguments exist to avoid.
 */
public class LogEventReportSink implements ExceptionReportSink {

    /** The summary event's name, which is also the field a query selects on. */
    public static final String SUMMARY_EVENT = "yotresultsdistribution_exception_report";

    /** The per-exception event's name. */
    public static final String EXCEPTION_EVENT = "yotresultsdistribution_exception";

    private static final Logger LOG = LoggerFactory.getLogger(LogEventReportSink.class);

    private static final String EVENT = "event";

    private static final String RUN_ID = "run_id";

    @Override
    public ReportSinkName name() {
        return ReportSinkName.LOG;
    }

    /**
     * Writes the summary and one event per exception, and says the log was told.
     *
     * <p>{@code truncated} is a count of <strong>late</strong> entries the cap dropped and never of
     * a failure: the failure kinds are read over a window no later run reads again, so they are
     * carried whole however many there are. A reader who finds fewer {@code REQUEST_LATE} or
     * {@code BATCH_LATE} events than the counts imply is reading a capped morning whose tail the
     * next run will say again; a shortfall on the three failure kinds is a sink that broke.
     *
     * @param report the report to write
     * @return delivered, with the one audience a log has
     */
    @Override
    public DeliveryOutcome deliver(final ExceptionReport report) {
        final Map<ExceptionKind, Integer> counts = report.counts();
        LOG.info("The exception report for this run has been built; its window, its six counts "
                        + "and how many late entries the entry cap dropped are the fields of this "
                        + "line.",
                value(EVENT, SUMMARY_EVENT),
                value(RUN_ID, report.runId()),
                value("window_from", report.window().from().toString()),
                value("window_to", report.window().to().toString()),
                value("snapshot_at", report.snapshotAt().toString()),
                value("request_failed", counts.get(ExceptionKind.REQUEST_FAILED)),
                value("request_late", counts.get(ExceptionKind.REQUEST_LATE)),
                value("batch_late", counts.get(ExceptionKind.BATCH_LATE)),
                value("batch_failed", counts.get(ExceptionKind.BATCH_FAILED)),
                value("notification_failed", counts.get(ExceptionKind.NOTIFICATION_FAILED)),
                value("batch_released", counts.get(ExceptionKind.BATCH_RELEASED)),
                value("truncated", report.truncated()));

        for (final ExceptionEntry entry : report.entries()) {
            LOG.info("One of the exceptions this report found, with everything known about it in "
                    + "the fields of this line.", fieldsOf(entry, report.runId()));
        }
        return DeliveryOutcome.delivered(ReportSinkName.LOG);
    }

    /**
     * One entry's fields, in the order the entry declares them, with the inapplicable ones absent.
     *
     * @param entry one thing wrong
     * @param runId the correlation every line of the run carries
     * @return the structured arguments the encoder emits as fields
     */
    private static Object[] fieldsOf(final ExceptionEntry entry, final String runId) {
        final List<StructuredArgument> fields = new ArrayList<>();
        fields.add(value(EVENT, EXCEPTION_EVENT));
        fields.add(value(RUN_ID, runId));
        fields.add(value("kind", entry.kind().name()));
        text(fields, "source", entry.source());
        text(fields, "request_id", entry.requestId());
        text(fields, "hearing_id", entry.hearingId());
        text(fields, "hearing_day", entry.hearingDay());
        text(fields, "batch_id", entry.batchId());
        text(fields, "notification_id", entry.notificationId());
        text(fields, "court_centre_id", entry.courtCentreId());
        text(fields, "register_date", entry.registerDate());
        text(fields, "status", entry.status());
        number(fields, "attempts", entry.attempts());
        text(fields, "reason", entry.reason());
        fields.add(value("age_seconds", entry.ageSeconds()));
        return fields.toArray();
    }

    /**
     * Adds an identifier, a date or a bounded code, or adds nothing at all.
     *
     * @param fields the fields being assembled
     * @param name   the field's name
     * @param carried its value, or {@code null} where the kind does not carry it
     */
    private static void text(final List<StructuredArgument> fields, final String name,
            final Object carried) {
        if (carried != null) {
            fields.add(value(name, carried.toString()));
        }
    }

    /**
     * Adds a count as a number, so a query can compare it without casting, or adds nothing.
     *
     * @param fields the fields being assembled
     * @param name   the field's name
     * @param carried its value, or {@code null} where the kind does not carry it
     */
    private static void number(final List<StructuredArgument> fields, final String name,
            final Integer carried) {
        if (carried != null) {
            fields.add(value(name, carried));
        }
    }
}
