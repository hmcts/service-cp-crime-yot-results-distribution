package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One thing wrong, of one kind.
 *
 * <p>One shape for five kinds: every field that does not apply to the kind is {@code null}, and the
 * log sink omits absent fields rather than emitting nulls. A shape per kind would be five event
 * schemas for one question, and a query that had to know which one it was reading.
 *
 * <p><strong>Every value here is an identifier, a bounded code, a count or a timing.</strong> There
 * is no defendant name, address, date of birth, ASN or URN anywhere, and no recipient address at
 * all - a failed send is identified by its notification id and its batch. Nor is there any free
 * text another system wrote: {@link ExceptionKind#BATCH_FAILED}'s reason is this service's own
 * {@link BatchFailureReason} and never systemdocgenerator's {@code sdg_reason}.
 *
 * <p>{@code status} is a {@link String} rather than a union of three enums because the five kinds
 * read three different state machines; the values are bounded by those machines' own {@code CHECK}
 * constraints, and this service never composes one.
 *
 * @param kind           which of the five this is, and the only field every kind carries besides
 *                       the age
 * @param source         the producing context, on the two intake kinds
 * @param requestId      the request, on the two intake kinds
 * @param hearingId      the hearing, on the two intake kinds
 * @param hearingDay     the hearing day, on the two intake kinds
 * @param batchId        the batch, on the batch and notification kinds; absent for a register no
 *                       batch was ever assembled for
 * @param notificationId the notification row, on NOTIFICATION_FAILED alone
 * @param courtCentreId  the court centre, on the three downstream kinds
 * @param registerDate   the register day, on the three downstream kinds
 * @param status         the state the row is in, bounded by its own state machine
 * @param attempts       the lifetime tally, where the row keeps one
 * @param reason         the bounded reason: a failure reason, an overdue stage, or a response code
 * @param ageSeconds     how old the exception is, computed by the database
 */
public record ExceptionEntry(
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

    /**
     * Refuses an entry with no kind, and a dead batch with no reason.
     *
     * <p>The kind is the one field all five carry, and the one every count, every query and every
     * CSV column is taken over. An entry without it is a row a dashboard cannot count and a support
     * engineer cannot select - and, because the log sink omits absent fields rather than emitting
     * nulls, one that would leave no trace of its own absence.
     *
     * <p>{@link ExceptionKind#BATCH_FAILED}'s reason is refused for the same reason in a narrower
     * place. The reason is the whole of what a dead batch tells an operator: a
     * {@link ExceptionKind#BATCH_LATE} entry names the stage it stopped at and a support engineer
     * knows what to look at, while a failed batch carrying nothing says only that a batch ended.
     * The read that produces one selects a column the table declares {@code NOT NULL} on a row
     * whose status is {@code FAILED}, so an absent value is a projection that has drifted from the
     * table - a thing to be told about rather than a field to leave quietly out of an event. It is
     * an {@link IllegalArgumentException} rather than a null check because the kind and the reason
     * are wrong <em>together</em>: either one alone is a perfectly good entry.
     */
    public ExceptionEntry {
        Objects.requireNonNull(kind, "an exception is of one of the five kinds");
        if (kind == ExceptionKind.BATCH_FAILED && reason == null) {
            throw new IllegalArgumentException(
                    "a batch that failed is reported under the bounded reason it failed for");
        }
    }
}
