package uk.gov.hmcts.cp.yotresultsdistribution.application;

import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionReport;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportSinkName;

/**
 * Where one built report is delivered.
 *
 * <p>Two implementations ship - the structured events the platform's log collection carries into
 * Log Analytics, and the e-mail with the exception list attached as a CSV - and neither knows the
 * other exists. Behind one port they are two implementations of one capability, which is what stops
 * them being able to take each other down and what keeps the logging library out of
 * {@code application/}: the structured-event shape is an adapter's concern in exactly the way an
 * HTTP body is.
 *
 * <p>Nothing here names a logging library, an HTTP client or a file store. What the service knows
 * is that a report goes somewhere and that the somewhere says how it went.
 */
public interface ExceptionReportSink {

    /**
     * Which of the two audiences this sink is, as the bounded label a line and a series carry.
     *
     * <p>A sink says its own name because the service has to be able to name a sink that
     * <strong>broke</strong>. The contract below is that a sink answers rather than throws, and an
     * implementation that throws anyway has to be classified by whoever asked it - and an outcome
     * is addressed to a sink. Without this, the one delivery nobody planned for would be the one
     * the run could not attribute, and {@code delivered_log} and {@code delivered_email} on the
     * run's own line would be guesses.
     *
     * @return the bounded name of this sink
     */
    ReportSinkName name();

    /**
     * Delivers one report, answering how it went rather than throwing.
     *
     * <p>A sink that could not deliver says so with a bounded reason. It never stops the other
     * sink and it never ends the run: a report that reached one of its two audiences is a
     * partially delivered report, not a failed one (FR-007).
     *
     * @param report the report to deliver
     * @return how the delivery went, with a bounded reason and the per-recipient counts
     */
    DeliveryOutcome deliver(ExceptionReport report);
}
