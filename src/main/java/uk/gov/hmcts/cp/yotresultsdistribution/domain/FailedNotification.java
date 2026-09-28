package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One recipient's e-mail that was not accepted, as the exception report reads it.
 *
 * <p>A projection over {@code register_notification} joined to its batch, and the join is what
 * makes the read one statement rather than one per row: {@link RegisterNotification} carries
 * neither its batch's court centre nor an age, and looking each batch up separately would be N+1
 * reads on precisely the morning the list is longest.
 *
 * <p><strong>There is no address component, and there is nothing to mask.</strong> The read does
 * not select {@code register_notification.email_address} at all - it is the one personal value in
 * the table, the report never carries it, and a column that is never read cannot be logged by
 * accident. A failed send is identified by its notification id and its batch, which is what a
 * support engineer resends from anyway.
 *
 * @param notificationId the identity the POST was made under, and the one a resend reuses
 * @param batchId        the batch whose document the e-mail was about
 * @param courtCentreId  the batch's court centre, from the join
 * @param registerDate   the batch's register day, from the join
 * @param status         the state the row is in, which is FAILED here
 * @param responseCode   what notificationnotify answered, or {@code null} where nothing answered
 * @param attempts       the lifetime tally of POSTs made for the row
 * @param sentAt         when this service settled the attempt
 * @param ageSeconds     how long ago that was, measured by the database from {@code sent_at}
 */
public record FailedNotification(
        UUID notificationId,
        UUID batchId,
        UUID courtCentreId,
        LocalDate registerDate,
        NotificationStatus status,
        Integer responseCode,
        int attempts,
        Instant sentAt,
        long ageSeconds) {
}
