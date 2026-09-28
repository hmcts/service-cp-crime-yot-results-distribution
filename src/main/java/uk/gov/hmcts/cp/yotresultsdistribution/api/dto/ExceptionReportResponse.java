package uk.gov.hmcts.cp.yotresultsdistribution.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionKind;

/**
 * What {@code POST /operations/exception-reports} answers, which is the command's table as JSON.
 *
 * <p>The same fields {@code report-exceptions} printed: the window, one row per exception oldest
 * first, the per-kind counts, how many late entries the cap dropped, what each of the two sinks
 * did, the run id and the duration. Nothing here is defendant detail, an unmasked address, a
 * payload or an exception message - every field is an identifier, a bounded code or a count.
 *
 * <p><strong>An empty window answers {@code entries: []} and the counts</strong>, never silence
 * and never an absent list. "Nothing is wrong" is a finding, and a report that said it by omitting
 * its own body would be indistinguishable from a report that did not run (FR-012).
 *
 * @param runId      the correlation this run opened, which its log lines carry too
 * @param window     what was asked for
 * @param entries    the exceptions found, oldest first
 * @param counts     how many of each kind the reads found, before the cap, zero-filled
 * @param truncated  how many late entries the cap dropped
 * @param delivered  the bounded word each of the two sinks gets
 * @param outcome    how the run as a whole went
 * @param durationMs how long the run took
 */
public record ExceptionReportResponse(
        String runId,
        Window window,
        List<Entry> entries,
        Map<String, Integer> counts,
        int truncated,
        Map<String, String> delivered,
        String outcome,
        long durationMs) {

    /**
     * The half-open window the reads were taken over.
     *
     * @param from when it opens
     * @param to   when it closes, which is the moment the run started
     */
    public record Window(Instant from, Instant to) {
    }

    /**
     * One exception, in identifiers, bounded codes and counts.
     *
     * <p><strong>Absent fields are omitted rather than rendered.</strong> The five kinds carry
     * different identifiers - a failed request has no batch, a refused notification has no hearing
     * day - and the same rule {@code ReportExceptionsCli.carried} applied to its key-value line
     * applies here: a field the kind does not have is left out, not emitted as a null a reader has
     * to interpret.
     *
     * @param kind           which of the five this is
     * @param source         the producer the request came from
     * @param requestId      the request's own identity
     * @param hearingId      the hearing the request is about
     * @param hearingDay     the hearing day the claim-check was keyed on
     * @param batchId        the batch this is about
     * @param notificationId the recipient row this is about
     * @param courtCentreId  the court centre the batch or register belongs to
     * @param registerDate   the London register day
     * @param status         the state the row is in, bounded by its own state machine
     * @param attempts       the lifetime tally, where the row keeps one
     * @param reason         the bounded reason: a failure reason, an overdue stage, or a code
     * @param ageSeconds     how old the exception is, computed by the database
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(
            ExceptionKind kind,
            String source,
            UUID requestId,
            UUID hearingId,
            LocalDate hearingDay,
            UUID batchId,
            UUID notificationId,
            UUID courtCentreId,
            LocalDate registerDate,
            String status,
            Integer attempts,
            String reason,
            long ageSeconds) {
    }
}
