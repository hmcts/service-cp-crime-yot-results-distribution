package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One recipient's e-mail for one batch, as the {@code register_notification} row records it.
 *
 * <p>One row per distinct address rather than one per record: the recipient set is the de-duplicated
 * union across the batch (defect fix P4), so the same Youth Offending Team on ten hearings is told
 * once and the row that says so is the evidence.
 *
 * <p><strong>{@code notificationId} is minted and persisted before the POST, and reused on every
 * retry.</strong> notificationnotify keys its aggregate on that id and takes it as the path
 * parameter of {@code POST /notifications/{notificationId}}, so a retry under the same id reaches
 * the same aggregate rather than sending a second e-mail; a retry under a fresh one would.
 *
 * <p>The address and the recipient name are the two components that never reach a log at INFO or
 * above (constitution Principle VII). They are here because they are what was sent, and the row is
 * the only place that may hold them.
 *
 * @param notificationId identity minted before the POST and reused on retry
 * @param batchId        the batch whose document this notification carries
 * @param emailAddress   the recipient's {@code emailAddress1}
 * @param recipientName  the recipient's name, sent as {@code personalisation.yotsName}, or
 *                       {@code null} where the record carried none
 * @param templateName   the template's logical name, {@code cr_standard}
 * @param templateId     the template's UUID, resolved from configuration at startup (defect fix P9)
 * @param status         where this recipient's e-mail has got to
 * @param responseCode   the status notificationnotify answered with, or {@code null} where nothing
 *                       answered at all
 * @param sentAt         when the attempt was settled, or {@code null} while it is pending
 * @param attempts       lifetime tally of POSTs made under this identity
 */
public record RegisterNotification(
        UUID notificationId,
        UUID batchId,
        String emailAddress,
        String recipientName,
        String templateName,
        UUID templateId,
        NotificationStatus status,
        Integer responseCode,
        Instant sentAt,
        int attempts) {
}
