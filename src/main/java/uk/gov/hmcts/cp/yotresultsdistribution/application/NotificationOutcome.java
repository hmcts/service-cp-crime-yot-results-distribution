package uk.gov.hmcts.cp.yotresultsdistribution.application;

import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationStatus;

/**
 * What became of one recipient's e-mail command.
 *
 * <p>The status line is carried rather than discarded because it is written to
 * {@code register_notification.response_code}: "notificationnotify accepted it" and
 * "notificationnotify answered something" are different facts, and only one of them means an e-mail
 * is on its way.
 *
 * <p>Only {@code 202} produces {@link NotificationStatus#ACCEPTED}; anything else is a failure the
 * notifier records against the row and carries on from, because the next recipient's e-mail is
 * unaffected by this one's refusal.
 *
 * @param status       ACCEPTED where notificationnotify answered 202, FAILED otherwise
 * @param responseCode the status it answered with, or {@code null} where nothing answered at all
 */
public record NotificationOutcome(NotificationStatus status, Integer responseCode) {
}
