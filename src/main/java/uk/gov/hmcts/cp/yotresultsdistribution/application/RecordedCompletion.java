package uk.gov.hmcts.cp.yotresultsdistribution.application;

import uk.gov.hmcts.cp.yotresultsdistribution.domain.GuardDecision;

/**
 * A recording and the completion it stands or falls with.
 *
 * <p>The two are one transaction, so they are one answer: there is no state in which the caller
 * holds a register that was written and a completion that was not, and nothing here can be read
 * apart from the other half. What the caller does with it is settle the delivery on
 * {@link #completion()} and log the identities on {@link #recording()}.
 *
 * @param recording  the row that was written and the row it superseded, if any
 * @param completion what the guard made of the completion written beside it
 */
public record RecordedCompletion(RecordOutcome recording, GuardDecision completion) {
}
