package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * Thrown when a hearing payload cannot be transformed into a court register.
 *
 * <p>Non-transient by construction. A payload the transformation cannot read reads the same way on
 * every redelivery, so a retry spends a delivery to reach the same answer; the failure goes straight
 * to {@code FAILED} with a bounded reason and a dead-letter, where support can see it and replay it
 * deliberately. The classification is fixed rather than supplied so no throw site can quietly ask
 * for a retry that cannot help.
 *
 * <p>The legacy has no such outcome. {@code SetCourtRegister/index.js:88-90} catches everything the
 * build throws, logs it, and returns {@code undefined}; the orchestrator's {@code :33} guard then
 * skips the remaining stages and the orchestration reports {@code Success: true}. Defect C13 is the
 * sharpest instance — {@code RegisterFragmentService.js:40-43} catches a date-parse failure and
 * calls {@code this.context.log} in an arrow-function module export where {@code this} is unbound,
 * <em>so the catch itself throws</em> and the original cause never reaches a log line at all. This
 * type is the fixed shape of both: one classified failure, carrying the reason the pipeline records
 * and nothing that could fail a second time on the way out.
 *
 * <p>Like every other failure this service reports it carries a bounded {@link ReasonCode} and never
 * a fragment of the payload: the message names the shape that was wrong, never the value that was in
 * it (constitution Principle VII).
 */
public class TransformationFailedException extends RuntimeException implements ClassifiedFailure {

    private static final long serialVersionUID = 1L;

    private final ReasonCode reasonCode;

    /**
     * Creates the failure.
     *
     * @param detail a bounded description of what could not be transformed; never payload content
     */
    public TransformationFailedException(final String detail) {
        this(detail, ReasonCode.TRANSFORMATION_FAILED);
    }

    /**
     * Creates the failure under a more specific bounded reason.
     *
     * <p>The <em>classification</em> is still fixed — every way the transformation can fail is
     * non-transient — but not every one of them is best read as "the payload could not be
     * transformed". A document that was assembled and then refused by the frozen progression
     * contract is {@link ReasonCode#OUTBOUND_CONTRACT_VIOLATION} (defect fix C29): support acts on
     * it differently, because what is wrong is the register rather than the hearing, and a
     * dead-letter that said {@code TRANSFORMATION_FAILED} would send them to the wrong end of the
     * pipeline.
     *
     * @param detail a bounded description of what could not be transformed; never payload content
     * @param reason the bounded code recorded for this failure
     */
    public TransformationFailedException(final String detail, final ReasonCode reason) {
        super(detail);
        this.reasonCode = reason;
    }

    /**
     * Always {@link FailureClassification#NON_TRANSIENT} — see the class comment.
     *
     * @return the classification
     */
    @Override
    public FailureClassification classification() {
        return FailureClassification.NON_TRANSIENT;
    }

    /**
     * The bounded code recorded for this failure.
     *
     * @return the reason code
     */
    @Override
    public ReasonCode reason() {
        return reasonCode;
    }
}
