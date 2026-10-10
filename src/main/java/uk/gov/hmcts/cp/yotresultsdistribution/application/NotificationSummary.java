package uk.gov.hmcts.cp.yotresultsdistribution.application;

import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;

/**
 * How a batch's notifications went, in the shape the batch row is settled from.
 *
 * <p>The tally and the verdict travel together because the verdict is not derivable from the tally
 * alone: nought accepted and nought failed is NOTIFIED_NOBODY where the batch had no recipients, and
 * it is not a state any count of failures can produce. That distinction is defect fix P1 - the
 * progression leg leaves a batch nobody subscribes to sitting generated for ever, waiting for an
 * event nobody publishes.
 *
 * <p><strong>And the disposition travels with both</strong>, because a summary that only carried a
 * tally could not tell the caller which of two notifiers it was. A batch is notified under a claim,
 * so a second notifier that met the claim posted nothing and settled nothing; what it can honestly
 * report is the rows as they stood when it was refused, which is the winner's work part-done and
 * not a verdict about the batch. {@link NotificationDisposition#SETTLED} is the ordinary answer and
 * the one the three-component constructor gives.
 *
 * @param accepted    how many recipients notificationnotify answered 202 for
 * @param failed      how many recipients ended FAILED and are resendable under their own identity
 * @param outcome     the terminal state the batch is settled in: NOTIFIED, PARTIALLY_NOTIFIED or
 *                    NOTIFIED_NOBODY. On an {@link NotificationDisposition#ALREADY_NOTIFYING}
 *                    answer it is instead where the batch stood when the claim was refused, which
 *                    this call did not put it in and did not write
 * @param disposition what this call did: settled the batch, or found another notifier holding it
 */
public record NotificationSummary(
        int accepted, int failed, BatchStatus outcome, NotificationDisposition disposition) {

    /**
     * The ordinary answer: a call that held the claim and settled the batch on its tally.
     *
     * @param accepted how many recipients notificationnotify answered 202 for
     * @param failed   how many recipients ended FAILED
     * @param outcome  the terminal state the batch is settled in
     */
    public NotificationSummary(final int accepted, final int failed, final BatchStatus outcome) {
        this(accepted, failed, outcome, NotificationDisposition.SETTLED);
    }

    /**
     * The answer of a notifier that found the batch already being told by another.
     *
     * <p>The tally is the rows as they stood at the moment the claim was refused and the state is
     * where the batch stood then: both are the winner's work in progress, which is why the
     * disposition and not the counts is what a caller branches on.
     *
     * @param accepted how many of the batch's rows were already accepted
     * @param failed   how many of them stood FAILED
     * @param standing where the batch stood when the claim was refused
     * @return the summary, carrying {@link NotificationDisposition#ALREADY_NOTIFYING}
     */
    public static NotificationSummary alreadyNotifying(
            final int accepted, final int failed, final BatchStatus standing) {
        return new NotificationSummary(
                accepted, failed, standing, NotificationDisposition.ALREADY_NOTIFYING);
    }

    /**
     * The answer of a notifier that held the claim, began the cycle and lost the claim inside it.
     *
     * <p>The tally is the rows as they stood when the renewal was refused and the state is where the
     * batch stood then, and neither is this call's work alone: the notifier that took the batch
     * over is telling whichever teams this one had not reached, and how many that leaves is not
     * fixed. The claim is renewed in front of every POST, so the refusal may have come before the
     * first of them, leaving this call nothing at all to its name, or before the batch's own
     * settlement, by which point it had posted for every recipient. Which is why the disposition
     * and not the counts is what a caller branches on.
     *
     * @param accepted how many of the batch's rows stood accepted when the claim was lost
     * @param failed   how many of them stood FAILED
     * @param standing where the batch stood when the claim was lost, which this call did not write
     * @return the summary, carrying {@link NotificationDisposition#CLAIM_LOST}
     */
    public static NotificationSummary claimLost(
            final int accepted, final int failed, final BatchStatus standing) {
        return new NotificationSummary(
                accepted, failed, standing, NotificationDisposition.CLAIM_LOST);
    }

    /**
     * The answer of a notifier that held the claim and could not account for one of the rows.
     *
     * <p>The tally is the rows as they stand and the state is where the batch stands, and neither
     * is a verdict: a settlement the store had no row for means one of this batch's recipients is
     * unaccounted for, so a tally over the rows that are left would settle the batch on an
     * incomplete account of what was sent. The batch is therefore left where it is, and an
     * operator's explicit {@code POST /operations/batches/{batchId}/notify} recovers it, as does a
     * redelivered {@code document-available}, which hands a batch still GENERATED on again. {@code yotresultsdistribution_oldest_generated_age}
     * names such a batch and ages it; a reading settles nothing.
     *
     * @param accepted how many of the batch's rows stood accepted when the cycle stopped
     * @param failed   how many of them stood FAILED
     * @param standing where the batch stands, which this call did not write
     * @return the summary, carrying {@link NotificationDisposition#INCOMPLETE}
     */
    public static NotificationSummary incomplete(
            final int accepted, final int failed, final BatchStatus standing) {
        return new NotificationSummary(
                accepted, failed, standing, NotificationDisposition.INCOMPLETE);
    }

    /**
     * Whether this call is the one that posted for the batch and settled it.
     *
     * @return true only for {@link NotificationDisposition#SETTLED}; false for every other
     *         disposition, including the ones where this call held the claim and did not settle
     */
    public boolean settled() {
        return disposition == NotificationDisposition.SETTLED;
    }
}
