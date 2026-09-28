package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

/**
 * What a claim attempt on one batch's notifying leg found.
 *
 * <p>Three answers rather than a changed-row count, because nought rows meant two unrelated things
 * and the caller had to pick one before it could know: the batch is being told by another notifier,
 * which is an ordinary night, or this store holds no such batch at all, which is a caller acting on
 * a correlation nothing was ever assembled under. Counting the second as the first put a lost
 * correlation into the reading a claim nobody can take is chased by, and reported a batch that does
 * not exist as a batch somebody else is busy with.
 *
 * <p>Bounded, and read by the caller rather than logged as it is: a {@code reason} label is derived
 * from it, and that label may never carry a batch identity (constitution Principle VII).
 */
public enum NotificationClaim {

    /**
     * The claim is now this notifier's: the batch was unclaimed, or its claim was past its lease.
     */
    CLAIMED,

    /**
     * Another notifier holds a live claim on the batch, so this one posts nothing.
     *
     * <p>Not a failure. The outcome sink on a delivered {@code document-available} and an operator's
     * resend can reach one generated batch at the same moment, and the one that does not get the
     * claim has nothing left to do. It is the one of these three that is counted, because a batch
     * nobody can ever claim - a claim left behind by a pod that died - has to read as a series
     * rather than as silence.
     */
    ALREADY_CLAIMED,

    /**
     * This store holds no batch under that identity, so there was nothing to claim.
     *
     * <p>A caller acting on a correlation nothing was ever assembled under, which is the existing
     * not-found failure and not contention: no notifier is telling this batch's recipients, because
     * there are no recipients and no batch. Kept apart from {@link #ALREADY_CLAIMED} so the
     * contention reading means contention.
     */
    ABSENT
}
