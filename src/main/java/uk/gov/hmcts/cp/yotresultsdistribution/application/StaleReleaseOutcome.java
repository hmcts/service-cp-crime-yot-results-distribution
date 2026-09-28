package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.util.List;
import java.util.UUID;

/**
 * What a pass over the stale batches did, and what it could not do.
 *
 * <p>What {@link RegisterStore#failAndReleaseStale(java.time.Instant, java.time.Instant)} answers
 * with. Every batch the operation looked at is accounted for in exactly one of the two lists, and a
 * batch that stopped being stale between the read and its own write is in neither: it was not
 * changed, and nothing was owed about it.
 *
 * <p><strong>Why there are two lists rather than one answer and an exception.</strong> The
 * operation isolates each batch, so the ending of one says nothing about the ending of another.
 * FR-003a is written about exactly that - no single batch's outcome may end the run - and an
 * exhaustion raised out of the operation would end it for every court centre over one hearing that
 * was re-shared a few times in a few milliseconds. A contended batch is therefore reported rather
 * than thrown: the pass counts it, says so in its line, and goes on to assemble what the rest of
 * the pass gave back. The batch itself is untouched and stale still, so the next run reaches it
 * again, and the 07:00 exception report names its court centre day as a late batch every morning
 * until it is released.
 *
 * <p>Nothing here is a defendant, a recipient or a word another system wrote, so both lists are
 * safe at INFO (constitution Principle VII).
 *
 * @param released  one record per batch the operation failed and gave the registers back from,
 *                  oldest day first
 * @param contended the batches the operation left exactly as it found them because every attempt
 *                  at them lost the same race for the day's active-register key, named by identity
 *                  alone - the count is what the run's line and its counter carry, and the court
 *                  centre day behind each one is what the morning report is already about
 */
public record StaleReleaseOutcome(List<ReleasedBatch> released, List<UUID> contended) {

    /**
     * Copies both lists, and refuses the account that is missing one.
     *
     * <p>The pass walks both of these as the run's first act, so a list that is not there ends the
     * night inside the pass - reported as an unexpected failure rather than as the store's own
     * signal, and on a run no court centre has had a document from yet. The refusal belongs here,
     * where the account is built and the mistake is plain, rather than at the walk.
     *
     * <p>And copied for the same reason: this record is read after the statements that produced it
     * have moved on, so it holds what it was given rather than a view of somebody's working list.
     */
    public StaleReleaseOutcome {
        released = List.copyOf(released);
        contended = List.copyOf(contended);
    }
}
