package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.util.OptionalInt;

/**
 * Thrown when a batch could not be rendered into a document.
 *
 * <p>The classification is supplied rather than fixed, because only the throw site knows: a connect
 * failure, a 5xx, a 429 and a 408 are worth another attempt inside the run deadline, and a refusal
 * of the same request is not. The generation service branches on the classification and never on the
 * type, which is what lets one exception cover the whole of the render leg.
 *
 * <p>The bounded {@link BatchFailureReason} is what reaches {@code register_batch.failure_reason},
 * the batches counter and the run report. systemdocgenerator's own words never travel on this
 * exception: they are written to {@code sdg_reason} by the sink that received them, where support
 * can read them and no INFO line can.
 *
 * <p><strong>The status line travels with the failure, and only the status line.</strong> A refusal
 * and a 2xx that is not 202 are different investigations, and a batch that says only "the render
 * request failed" sends nobody to the right one. It is an {@link OptionalInt} because a connect
 * failure has no status to record, and a batch carrying an invented one would claim an attempt was
 * answered when it was not.
 */
public class GenerationFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FailureClassification failureClassification;
    private final BatchFailureReason batchFailureReason;
    private final Integer status;

    /**
     * Creates a failure with no status line to record.
     *
     * @param classification whether another attempt inside the run deadline could change the answer
     * @param reason         the bounded reason the batch is failed under
     */
    public GenerationFailedException(
            final FailureClassification classification, final BatchFailureReason reason) {
        this(classification, reason, null);
    }

    /**
     * Creates a failure carrying the status systemdocgenerator answered with.
     *
     * @param classification whether another attempt inside the run deadline could change the answer
     * @param reason         the bounded reason the batch is failed under
     * @param responseCode   the status systemdocgenerator answered with, or {@code null} where
     *                       nothing answered at all
     */
    public GenerationFailedException(final FailureClassification classification,
            final BatchFailureReason reason, final Integer responseCode) {
        super(reason.name());
        this.failureClassification = classification;
        this.batchFailureReason = reason;
        this.status = responseCode;
    }

    /**
     * Whether another attempt could change the answer.
     *
     * @return the classification the throw site gave
     */
    public FailureClassification classification() {
        return failureClassification;
    }

    /**
     * The bounded reason the batch is failed under.
     *
     * @return the bounded batch failure reason
     */
    public BatchFailureReason reason() {
        return batchFailureReason;
    }

    /**
     * The status systemdocgenerator answered with, where it answered.
     *
     * @return the status line, or empty where the attempt reached no verdict
     */
    public OptionalInt responseCode() {
        return status == null ? OptionalInt.empty() : OptionalInt.of(status);
    }
}
