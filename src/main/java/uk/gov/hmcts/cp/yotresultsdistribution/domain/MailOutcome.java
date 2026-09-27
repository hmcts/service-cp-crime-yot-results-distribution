package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * What became of one report e-mail.
 *
 * <p>The status line is carried rather than discarded for the reason
 * {@code NotificationOutcome} carries it: "notificationnotify accepted it" and "notificationnotify
 * answered something" are different facts, and the sink turns the second into a bounded
 * {@link ReportDeliveryReason} a support engineer can act on.
 *
 * @param status       how the send ended
 * @param responseCode what it answered with, or {@code null} where nothing answered at all
 */
public record MailOutcome(MailStatus status, Integer responseCode) {
}
