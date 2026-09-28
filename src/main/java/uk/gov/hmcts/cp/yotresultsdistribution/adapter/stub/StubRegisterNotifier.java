package uk.gov.hmcts.cp.yotresultsdistribution.adapter.stub;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.yotresultsdistribution.application.NotificationOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterNotifier;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotification;

/**
 * A notifier that sends nothing, and refuses rather than pretending.
 *
 * <p>The sibling of {@link StubRegisterSubmissionClient}, refusing for exactly its reason: if a
 * register ever did reach it, answering ACCEPTED would record a Youth Offending Team as told about a
 * register nobody sent it - the silent-success failure mode this whole service was commissioned to
 * end (C1, C33). A stub is allowed to do nothing; it is not allowed to say it did something.
 *
 * <p><strong>The refusal is per recipient, and the batch carries on.</strong> That is not this
 * stub's leniency but the notification leg's shape: {@code NotificationFailedException} is caught per
 * row, the row is settled FAILED with no status line, and the batch ends NOTIFIED_NOBODY on the
 * tally - a bounded, terminal, counted outcome that says plainly that this pod has nowhere to send a
 * register. Non-transient, because no resend against a stub will ever answer differently.
 *
 * <p>It logs at ERROR rather than INFO, for the same reason its sibling does: reaching this class at
 * all means a run assembled a register for a real recipient on a pod configured to tell nobody. The
 * address and the recipient name are not logged - they are the two components that never reach a log
 * at INFO or above (constitution Principle VII) - so the row's own identity is what the line carries.
 */
public class StubRegisterNotifier implements RegisterNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(StubRegisterNotifier.class);

    @Override
    public NotificationOutcome send(final RegisterNotification notification,
            final UUID documentFileId, final CallerIdentity caller) {
        LOG.error("STUB register notifier invoked: this pod has nowhere to send a register, so the "
                        + "recipient is not told and the notification is failed. "
                        + "notificationId={} batchId={} documentFileId={} attempts={}",
                notification.notificationId(), notification.batchId(), documentFileId,
                notification.attempts());
        throw new NotificationFailedException(FailureClassification.NON_TRANSIENT);
    }
}
