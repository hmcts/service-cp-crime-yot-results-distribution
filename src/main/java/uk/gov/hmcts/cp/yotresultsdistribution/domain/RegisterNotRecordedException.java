package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * Thrown when the register store refused a recording for a reason it does not account for.
 *
 * <p>Non-transient by construction. The recorder settles exactly two refusals itself - this command
 * arriving beside itself, which is answered from the row that landed, and the race for the day's
 * active-register key, which is read again and recorded against the winner - and both of those are
 * named refusals it can recognise. Any other constraint the write meets is a rule nobody wrote this
 * recording against: the same register meets the same rule on every delivery, so the failure is
 * recorded FAILED with a bounded reason and dead-lettered where support can see it, rather than
 * spending four more deliveries reaching the same answer.
 *
 * <p><strong>It is not a store outage.</strong> {@link StoreUnavailableException} says the store
 * could not be reached and stops intake, because nothing is recordable while it is gone. This says
 * the store was reached and declined to hold this row, which is one register to look at and not a
 * queue to suspend.
 *
 * <p>The classification is fixed rather than supplied, so no throw site can ask for a retry that
 * cannot help, and the message names the constraint rather than anything in the document: a
 * register is a document about children (constitution Principle VII).
 */
public class RegisterNotRecordedException extends RuntimeException implements ClassifiedFailure {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the failure.
     *
     * @param detail a bounded description of the refusal; the constraint, never the register
     * @param cause  the store's own refusal
     */
    public RegisterNotRecordedException(final String detail, final Throwable cause) {
        super(detail, cause);
    }

    /**
     * Always {@link FailureClassification#NON_TRANSIENT} - see the class comment.
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
     * @return {@link ReasonCode#REGISTER_NOT_RECORDED}
     */
    @Override
    public ReasonCode reason() {
        return ReasonCode.REGISTER_NOT_RECORDED;
    }
}
