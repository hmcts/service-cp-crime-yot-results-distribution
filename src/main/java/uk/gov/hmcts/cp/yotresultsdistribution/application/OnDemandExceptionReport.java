package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionReport;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportRunOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportSinkName;

/**
 * What one on-demand exception report run produced: the report, who took it, and how long it took.
 *
 * <p>Exactly the fields {@code report-exceptions} printed - the table, the counts, the window, the
 * two delivery words, the run outcome and the duration - as a value rather than as lines on a
 * terminal. The endpoint maps it; nothing here knows that an endpoint is what asked.
 *
 * @param runId      the correlation the run opened, which its log lines carry too
 * @param report     the exceptions the reads found over the window, oldest first
 * @param outcomes   one outcome per sink asked, in the order they were asked
 * @param delivered  the bounded word for each of the two sinks, whether it was asked or not
 * @param outcome    how the run as a whole went, folded from {@link #outcomes}
 * @param durationMs how long the run took, on the one clock the service holds
 */
public record OnDemandExceptionReport(
        String runId,
        ExceptionReport report,
        List<DeliveryOutcome> outcomes,
        Map<ReportSinkName, String> delivered,
        ReportRunOutcome outcome,
        long durationMs) {

    /**
     * Freezes the two collections and refuses either as absent.
     *
     * <p>A null delivery map read as an empty one would render a report whose sinks said nothing,
     * which is indistinguishable from a context holding no sink - the exact silence
     * {@link uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryWord} exists to end.
     */
    public OnDemandExceptionReport {
        outcomes = List.copyOf(Objects.requireNonNull(outcomes,
                "a run states what each sink it asked answered, and an absent list is not none"));
        delivered = Map.copyOf(new EnumMap<>(Objects.requireNonNull(delivered,
                "a run states a word for both sinks, and an absent map is not two disabled ones")));
    }

    /**
     * Whether every sink that was asked took the report.
     *
     * <p>The same verdict {@code report-exceptions} turned into its exit code, stated once here so
     * the endpoint does not re-derive it: {@link ReportRunOutcome#DELIVERED} is the only outcome in
     * which nobody is owed a resend.
     *
     * @return {@code true} where every sink asked delivered in full
     */
    public boolean everySinkTookIt() {
        return outcome == ReportRunOutcome.DELIVERED;
    }
}
