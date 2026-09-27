package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.util.UUID;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;

/**
 * What one batch's requesting leg ended as, told back to the run.
 *
 * <p>The durable answer is the {@code register_batch} row, which the service has already written by
 * the time this is returned; this is what the run counts and what the run report is built from, so
 * that the job never has to re-read a row to know what it just did.
 *
 * <p>A batch that reached GENERATING is not finished, and this says so honestly: the outcome of the
 * <em>run</em> is that a render was asked for, and the outcome of the batch arrives later on the
 * public-event topic. Nothing here waits for it.
 *
 * <p><strong>Whether the render was asked for is carried here rather than inferred from the
 * reason.</strong> It is what the run report's {@code requested} count is, and the reason cannot
 * answer it: {@link BatchFailureReason#RENDER_REQUEST_FAILED} is the ending of a request that was
 * made and answered nothing <em>and</em> of a batch the run's deadline would not hold a single
 * attempt for, which asked systemdocgenerator nothing at all. Only the leg that makes the call knows
 * which of those happened, so it says so where the call is made, and a report built on it cannot
 * tell an operator that the renderer refused a document it was never sent.
 *
 * <p><strong>It is not the only thing that says so, and it cannot be.</strong> This is returned
 * only once the batch's ending has been written down, so a store that goes away on that write
 * takes the verdict with it and a run counting outcomes alone would report a night that sent
 * nothing.
 * {@link RenderProgress} is told at the call for that reason, and a run reconciles the two by the
 * batch identity they both name.
 *
 * @param batchId         the batch this is about
 * @param status          where the batch stood when the requesting leg let go of it
 * @param failureReason   the bounded reason where it failed, and {@code null} where it did not
 * @param renderRequested whether this service had asked systemdocgenerator for the render by the
 *                        time the requesting leg let go of the batch, whatever the renderer then
 *                        answered
 */
public record BatchOutcome(
        UUID batchId,
        BatchStatus status,
        BatchFailureReason failureReason,
        boolean renderRequested) {
}
