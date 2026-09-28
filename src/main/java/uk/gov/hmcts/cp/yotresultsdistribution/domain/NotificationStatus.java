package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * The three states one recipient's e-mail can hold.
 *
 * <p>The constant names are the values written to {@code register_notification.status}. PENDING is
 * written <em>before</em> the POST rather than after it, so a notification that was attempted and
 * never answered is a row that says so rather than an absence indistinguishable from one that was
 * never tried.
 *
 * <p>There is no DELIVERED: notificationnotify's delivery events are out of scope for this
 * increment, so the furthest this service can honestly record is that the command was accepted.
 */
public enum NotificationStatus {

    /** The row was minted with its {@code notificationId} and the POST has not been answered. */
    PENDING,

    /** notificationnotify answered 202; the command is enqueued and this row is done. */
    ACCEPTED,

    /** The attempt ended without a 202; the row is resendable under the same identity. */
    FAILED
}
