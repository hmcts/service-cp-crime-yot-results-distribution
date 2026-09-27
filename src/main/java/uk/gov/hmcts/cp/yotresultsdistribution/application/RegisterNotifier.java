package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.util.UUID;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotification;

/**
 * Who tells one Youth Offending Team that its register is ready.
 *
 * <p>notificationnotify, behind a port that names neither HTTP nor a template body. One call per
 * distinct recipient, carrying the document by file-service id rather than as bytes: the e-mail is
 * assembled on the other side, and a register about children never travels through this service
 * twice.
 *
 * <p><strong>The notification row comes in already persisted.</strong> Its
 * {@code notificationId} is the path parameter of {@code POST /notifications/{notificationId}} and
 * the key of notificationnotify's own aggregate, so it has to exist here before the call and be the
 * same on every retry - which is what makes a retry reach the same aggregate rather than send a
 * second e-mail.
 */
public interface RegisterNotifier {

    /**
     * Sends one recipient's e-mail.
     *
     * @param notification   the persisted row, carrying the identity the POST is made under
     * @param documentFileId the rendered document's file-service id, attached by reference
     * @param caller         who the call is made as
     * @return ACCEPTED with the 202, by construction
     * @throws NotificationFailedException if notificationnotify answered anything else, or did not
     *     answer; the row is settled FAILED and the batch carries on to the next recipient
     */
    NotificationOutcome send(RegisterNotification notification, UUID documentFileId,
            CallerIdentity caller) throws NotificationFailedException;
}
