package uk.gov.hmcts.cp.yotresultsdistribution.application;

/**
 * What became of a register that was handed to the submission port.
 *
 * <p>The status is carried rather than discarded because it is written to
 * {@code processed_output.response_code}: "we sent it and it was accepted" and "we sent it and
 * something answered" are different facts, and the legacy records neither — it never inspects the
 * response at all (defect C1). Only {@code 202} reaches this record; anything else is a failure, so
 * this is a receipt and never a verdict.
 *
 * <p><strong>Two ways a register ends up on record as accepted, and they are not the same event.</strong>
 * This delivery posted it, or the processed log already held it POSTED and this delivery skipped the
 * POST — which is how a replay of a request whose register went avoids sending a second one, given
 * that {@code add-court-register} appends. Both complete the run {@code submitted}, because in both
 * the register has gone; only one of them is a POST that happened just now, and a run that reported
 * "submitted, status 202" for the other would be describing a call it never made.
 *
 * @param responseCode        the status progression answered with — {@code 202}, by construction;
 *                            for a skipped POST it is the contract's success status standing for
 *                            what the log already holds, not a status this delivery observed
 * @param sentByThisDelivery  whether this delivery is the one that posted it
 */
public record SubmissionReceipt(int responseCode, boolean sentByThisDelivery) {
}
