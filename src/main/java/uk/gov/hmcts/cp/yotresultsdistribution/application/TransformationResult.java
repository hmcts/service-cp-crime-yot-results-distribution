package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.util.Objects;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NoRegisterReason;

/**
 * What a hearing transformed into: a register to send, or a reason there is nothing to send.
 *
 * <p>A closed pair, because the legacy's answer to the second question is {@code undefined} and
 * every one of the four ways it can arise looks the same to the orchestrator. Three of them are
 * separate silent guards ({@code CourtRegisterOrchestrator/index.js:20, 23, 33, 50}) and the fourth
 * is a bare {@code null} from {@code OutboundCourtRegister/index.js:17-26}; all four end in
 * {@code Success: true}. Two of them — no matched subscriptions, and no youth defendants — are this
 * flow's commonest legitimate outcomes, so an undifferentiated success is the legacy defect C33
 * rather than an acceptable simplification.
 *
 * <p>Making the reason part of the type means a transformation cannot decline to produce a register
 * without saying which of the three it was — and the reason is a
 * {@link NoRegisterReason} rather than the wider {@link
 * uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletionReason}, so it cannot say {@code submitted} for a
 * register it never sent or {@code group-proceedings} for a decision made before it was called. The
 * five ways a run ends are mutually exclusive by construction.
 */
public sealed interface TransformationResult {

    /**
     * There is nothing to send, and this is which of the three reasons.
     *
     * @param reason which of the three no-register reasons it was; never {@code null}
     */
    record NoRegister(NoRegisterReason reason) implements TransformationResult {

        /** A reason is the whole content of this answer; there is no such thing as an absent one. */
        public NoRegister {
            Objects.requireNonNull(reason, "a transformation that produces no register says why");
        }
    }

    /**
     * A register to send.
     *
     * <p>The OU code travels beside the document rather than in it. The frozen
     * {@code add-court-register} contract has no field for it, and it is what an operator searches
     * {@code processed_output} by — so the transformation, which is the one participant holding the
     * fragment the register was addressed by, says it once rather than leaving the submission
     * adapter to recover it from a file name.
     *
     * @param document          the assembled {@code add-court-register} command
     * @param courtCentreOuCode the court centre's OU code, or {@code null} where the hearing carried
     *                          none
     */
    record Register(CourtRegisterDocument document, String courtCentreOuCode)
            implements TransformationResult {
    }
}
