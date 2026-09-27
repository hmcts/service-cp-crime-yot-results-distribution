package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * Thrown when the hearing payload for a request cannot be obtained.
 *
 * <p><strong>Transient unless the query side declined the read.</strong> Almost every payload-fetch
 * failure is a cache, a network or a query-side problem, and every one of those is worth a
 * redelivery: a cache miss <em>and</em> a fallback miss is one of them rather than a silent stop,
 * which is defect fix C32. What is not is a read the query side <em>understood and refused</em> — a
 * 4xx other than the 404 that means "not held here", the 408 and the 429 that mean "ask again". The
 * same request is declined identically on every redelivery, so carrying it to exhaustion spends the
 * delivery budget to reach the same answer and then parks it under
 * {@link ReasonCode#DELIVERY_LIMIT_EXHAUSTED}, which tells support the service ran out of tries
 * rather than that its credential is wrong.
 *
 * <p>So the classification is supplied, but not positionally: the single-argument constructor is the
 * transient case and is the only one most call sites need, and the refusal is spelled out in full
 * where it is raised.
 *
 * <p>Like every other failure this service reports, it carries a bounded {@link ReasonCode} and
 * never a raw message from the layer beneath it — the code travels into {@code failure_reason}, a
 * dead-letter description and the log index.
 */
public class PayloadUnavailableException extends RuntimeException implements ClassifiedFailure {

    private static final long serialVersionUID = 1L;

    private final FailureClassification failureClassification;
    private final ReasonCode reasonCode;

    /**
     * Creates a transient failure carrying the reason the payload could not be fetched.
     *
     * @param reason the bounded reason recorded for this failure
     */
    public PayloadUnavailableException(final ReasonCode reason) {
        this(FailureClassification.TRANSIENT, reason);
    }

    /**
     * Creates the failure under the classification the read earned.
     *
     * @param classification whether another delivery could change the answer
     * @param reason         the bounded reason recorded for this failure
     */
    public PayloadUnavailableException(
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
