package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * Thrown when the now-subscriptions a register is addressed with cannot be obtained.
 *
 * <p><strong>Transient unless reference data declined the read.</strong> Reference data that cannot
 * be read now is a network, a gateway or a context-side problem, and every one of those is worth a
 * redelivery. A read reference data <em>understood and refused</em> is not: a 4xx other than 408 and
 * 429 — a 404 on this resource included, because the now-subscriptions resource always exists and a
 * 404 on it is a misconfigured path — answers the same way on every delivery, so carrying it to
 * exhaustion buys nothing and parks it under a reason that describes the retry budget rather than
 * the fault.
 *
 * <p><strong>It exists because the legacy cannot tell "nobody subscribes" from "nobody
 * answered".</strong> {@code CourtRegisterSubscriptions/index.js:20-24} tests
 * {@code if (!subscriptionsMetaData || !subscriptionsMetaData.nowSubscriptions)} and returns the
 * fragment unchanged either way, so a reference-data outage completes the run with no recipients and
 * reports it as a success — and {@code no-subscriptions}, which is one of this flow's two commonest
 * legitimate outcomes, is exactly what it looks like. An empty subscription set is an answer and
 * completes the run; no answer at all is this exception.
 *
 * <p>A bounded {@link ReasonCode} and nothing from the layer beneath: the code travels into
 * {@code failure_reason}, a dead-letter description and the log index.
 */
public class ReferenceDataUnavailableException extends RuntimeException implements ClassifiedFailure {

    private static final long serialVersionUID = 1L;

    private final FailureClassification failureClassification;
    private final ReasonCode reasonCode;

    /**
     * Creates a transient failure.
     *
     * @param detail a bounded description of what could not be read; never response content
     */
    public ReferenceDataUnavailableException(final String detail) {
        super(detail);
        this.failureClassification = FailureClassification.TRANSIENT;
        this.reasonCode = ReasonCode.REFERENCE_DATA_UNAVAILABLE;
    }

    /**
     * Creates the failure under the classification the read earned.
     *
     * @param classification whether another delivery could change the answer
     * @param reason         the bounded reason recorded for this failure
     */
    public ReferenceDataUnavailableException(
            final FailureClassification classification, final ReasonCode reason) {
        super(reason.code());
        this.failureClassification = classification;
        this.reasonCode = reason;
    }

    /**
     * Whether another delivery could change the answer.
     *
     * @return the classification the throw site chose
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
