package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * Thrown when the batch's payload could not be written to the framework file service.
 *
 * <p>Always {@link BatchFailureReason#PAYLOAD_STORE_UNAVAILABLE}, which is why it carries no reason
 * of its own: there is one way this can go wrong from the batch's point of view, and that is that
 * the document has nothing to be rendered from.
 *
 * <p><strong>It fails the batch and leaves its rows RECORDED.</strong> Nothing was asked of
 * systemdocgenerator, so nothing downstream believes a register is coming, and the next run
 * re-assembles the same records under a fresh batch identity. That is the whole difference between
 * this failure and a render refusal, and it is why the store is written before the request rather
 * than after it.
 *
 * <p>The message is a bounded phrase written here, never the driver's or the database's own words:
 * this reaches a log line about a register whose every defendant is a child. The cause is attached
 * where there is one, so the stack trace still says which statement failed.
 */
public class PayloadStoreUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the failure where the write itself raised nothing.
     *
     * @param reason a bounded phrase saying what the write did instead, such as a row count that
     *               was not the one row expected
     */
    public PayloadStoreUnavailableException(final String reason) {
        super(reason);
    }

    /**
     * Creates the failure around what the write raised.
     *
     * @param reason a bounded phrase saying which write failed
     * @param cause  what the file-service datasource raised
     */
    public PayloadStoreUnavailableException(final String reason, final Throwable cause) {
        super(reason, cause);
    }
}
