package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.util.OptionalInt;

/**
 * Thrown when the {@code add-court-register} POST did not succeed.
 *
 * <p>The one failure type whose classification is <em>supplied</em> rather than fixed, because only
 * the throw site knows: a connect failure, a 5xx, a 429 and a 408 are worth another delivery, and a
 * 4xx refusal of the same bytes is not. The pipeline branches on the classification and never on the
 * type, which is what lets one exception carry both answers.
 *
 * <p>This type is defect fix C1 in one line. {@code ProcessOutboundCourtRegister/index.js:17-25}
 * catches everything the POST can raise, logs it, and never inspects the response status at all — so
 * a failed submission and a delivered register are indistinguishable, which is the silent-loss mode
 * this service was commissioned to end. Here a submission that did not end in {@code 202} throws,
 * and the run is recorded as RETRYING or FAILED accordingly.
 *
 * <p>A bounded {@link ReasonCode} and never progression's own words: the code reaches
 * {@code failure_reason}, a dead-letter description and the log index.
 *
 * <p><strong>The status line travels with the failure, and only the status line.</strong> It is the
 * other half of C1: {@code processed_output.response_code} is what turns "the register did not go"
 * into "progression answered 400", and the throw site is the only place that knows which. It is an
 * {@code OptionalInt} because a connect failure or a timeout has no status to record, and a row
 * carrying an invented one would say an attempt was answered when it was not. A number is also all
 * that may cross this boundary: a status is bounded and says nothing about a child, where a response
 * body from a register command can name one.
 */
public class SubmissionFailedException extends RuntimeException implements ClassifiedFailure {

    private static final long serialVersionUID = 1L;

    private final FailureClassification failureClassification;
    private final ReasonCode reasonCode;
    private final Integer status;

    /**
     * Creates a failure with no status line to record.
     *
     * @param classification whether another delivery could change the answer
     * @param reason         the bounded reason recorded for this failure
     */
    public SubmissionFailedException(
            final FailureClassification classification, final ReasonCode reason) {
        this(classification, reason, null);
    }

    /**
     * Creates a failure carrying the status progression answered with.
     *
     * @param classification whether another delivery could change the answer
     * @param reason         the bounded reason recorded for this failure
     * @param responseCode   the status line progression answered with, or {@code null} where nothing
     *                       answered at all
     */
    public SubmissionFailedException(final FailureClassification classification,
            final ReasonCode reason, final Integer responseCode) {
        super(reason.code());
        this.failureClassification = classification;
        this.reasonCode = reason;
        this.status = responseCode;
    }

    /**
     * The status progression answered with, where it answered.
     *
     * @return the status line, or empty where the attempt reached no verdict
     */
    public OptionalInt responseCode() {
        return status == null ? OptionalInt.empty() : OptionalInt.of(status);
    }

    /**
     * Whether another delivery could change the answer.
     *
     * @return the classification the throw site gave
     */
    @Override
    public FailureClassification classification() {
        return failureClassification;
    }

    /**
     * The bounded code recorded for this failure.
     *
     * @return the bounded reason code
     */
    @Override
    public ReasonCode reason() {
        return reasonCode;
    }
}
