package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One batch a run gave up on, and the registers that came back with it.
 *
 * <p>What {@link RegisterStore#failAndReleaseStale(java.time.Instant, java.time.Instant)} answers
 * with, one of these per batch the statement actually changed. A batch that stopped being stale
 * between the operation being asked for and the row being written is simply not here: zero rows is
 * an answer, and the caller reports it as a batch the pass did not release rather than as an error.
 *
 * <p><strong>The key travels with the identity</strong> because the line the pass writes about a
 * release has to say which court centre day was given back, and a caller that had to read the batch
 * again to find out would be reading a row that has moved on. Nothing here is a defendant, a
 * recipient or a word another system wrote, so the whole record is safe at INFO (constitution
 * Principle VII).
 *
 * @param batchId           the batch that was failed
 * @param courtCentreId     the court centre whose day it held
 * @param registerDate      the register date it held
 * @param releasedRegisters how many of its registers are still this day's to render, which is what
 *                          the same run re-assembles - a register the estate replaced while the
 *                          batch was in flight is superseded as its stamp is cleared and is
 *                          deliberately not counted here, because it is not a register anything
 *                          will put in tonight's batch
 */
public record ReleasedBatch(UUID batchId, UUID courtCentreId, LocalDate registerDate,
        int releasedRegisters) {
}
