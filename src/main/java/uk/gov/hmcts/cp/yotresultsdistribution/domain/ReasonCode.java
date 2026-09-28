package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * The bounded reason codes the guard decides with and records.
 *
 * <p>One of these — never a raw exception message and never a fragment of the message body — is what
 * reaches {@code processed_request.failure_reason}, a dead-letter description, and a log's reason
 * field. Both of the alternatives are producer-influenced content, and both would leak into the DLQ
 * and the log index of a flow whose every defendant is a child (constitution Principle VII).
 *
 * <p>The stored code is declared explicitly rather than derived from the constant name, so renaming
 * a constant cannot silently rename a value already written to thousands of rows.
 */
public enum ReasonCode {

    /** A delivery arrived for a record already COMPLETED; acknowledged without a run. */
    ALREADY_COMPLETED("ALREADY_COMPLETED"),

    /** A run's outcome was recorded successfully. */
    RUN_COMPLETED("RUN_COMPLETED"),

    /**
     * A conditional claim acquisition matched no row.
     *
     * <p>Deliberately one code for all three causes — a live runner holds the claim, another
     * delivery won the race, or the row turned terminal in between. Telling them apart would need a
     * second read, and the no-spin rule forbids one: broker redelivery is the retry mechanism.
     */
    CLAIM_NOT_ACQUIRED("CLAIM_NOT_ACQUIRED"),

    /**
     * The replay update matched no row: the record changed between the read and the update.
     *
     * <p>It never means "the same message identity" — that case is decided on the read.
     */
    REPLAY_NOT_ADMITTED("REPLAY_NOT_ADMITTED"),

    /** The key was reused for a request with different immutable fields. */
    IDEMPOTENCY_COLLISION("IDEMPOTENCY_COLLISION"),

    /** The delivery that exhausted the permitted deliveries, or a redelivery of that identity. */
    DELIVERY_LIMIT_EXHAUSTED("DELIVERY_LIMIT_EXHAUSTED"),

    /** An outcome write was refused by the owner-and-token predicate: the claim was reclaimed. */
    STALE_RUNNER("STALE_RUNNER"),

    /** The record was absent when the guard read it back after losing the insert race. */
    RECORD_ABSENT("RECORD_ABSENT"),

    /** The body did not satisfy the inbound contract, so the state machine was never entered. */
    CONTRACT_VALIDATION_FAILED("CONTRACT_VALIDATION_FAILED"),

    /**
     * A delivery failed in a way nothing anticipated.
     *
     * <p>The catch-all that keeps the settlement contract total: whatever went wrong, the delivery
     * still gets exactly one settlement and the failure still gets one ERROR line.
     */
    UNEXPECTED_FAILURE("UNEXPECTED_FAILURE"),

    /** A pipeline run failed in a way redelivery may fix. */
    PIPELINE_TRANSIENT_FAILURE("PIPELINE_TRANSIENT_FAILURE"),

    /**
     * Neither the claim-check cache nor the results-query fallback could supply the hearing.
     *
     * <p>Transient, and the whole of defect fix C32: the legacy pipeline treated a cache miss as an
     * ordinary end to the run and lost the register without a word. A hearing that cannot be read
     * is a reason to come back, not a reason to stop.
     */
    PAYLOAD_UNAVAILABLE("PAYLOAD_UNAVAILABLE"),

    /**
     * The results query API understood the payload read and declined it.
     *
     * <p>A 4xx other than 404, 408 and 429 — a malformed request, an unauthenticated one, a
     * forbidden one. Non-transient, and a code of its own rather than
     * {@link #PAYLOAD_UNAVAILABLE}: the same request will be declined identically on every
     * redelivery, so spending the delivery budget on it only delays the dead-letter by four
     * back-offs and then parks it under {@code DELIVERY_LIMIT_EXHAUSTED}, which tells support the
     * service ran out of tries rather than that the read is refused. A rise in this code is a
     * credential or a route to look at, where a rise in {@code PAYLOAD_UNAVAILABLE} is a producer or
     * a cache.
     *
     * <p>A {@code 404} is deliberately <em>not</em> this: the resource is per-hearing, so its
     * absence is the query side saying it does not hold this hearing — an empty answer, which
     * becomes a transient {@code PAYLOAD_UNAVAILABLE} only once the cache has missed too (C32).
     */
    PAYLOAD_READ_REFUSED("PAYLOAD_READ_REFUSED"),

    /**
     * The now-subscriptions reference data a register is addressed with could not be obtained.
     *
     * <p>Transient, and a code of its own rather than a payload failure: the hearing was read
     * perfectly well and the register was built — what is missing is who it goes to. A rise in this
     * code means the reference-data context is unwell, and completing the run anyway would report
     * {@code no-subscriptions} for a court centre that has subscribers.
     */
    REFERENCE_DATA_UNAVAILABLE("REFERENCE_DATA_UNAVAILABLE"),

    /**
     * Reference data understood the now-subscriptions read and declined it.
     *
     * <p>The now-subscriptions counterpart of {@link #PAYLOAD_READ_REFUSED}, and it covers one more
     * status: the resource always exists, so a {@code 404} on it is a misconfigured path rather than
     * an absence, and no redelivery mends a path. Non-transient for the same reason — the delivery
     * budget buys nothing against an answer that will not change, and a request parked under this
     * code sends support to the route and the credential rather than to reference data's health.
     */
    REFERENCE_DATA_REFUSED("REFERENCE_DATA_REFUSED"),

    /**
     * The hearing payload could not be transformed into a court register at all.
     *
     * <p>Non-transient: the same payload transforms the same way on every delivery. Reserved for a
     * transformation that cannot produce a document — an unparseable ordered date, say (C13). The
     * guarded skips the fixes introduce are deliberately <em>not</em> this: a register produced with
     * a part missing completes, and the part is counted in
     * {@code processed_output.anomaly_summary}.
     */
    TRANSFORMATION_FAILED("TRANSFORMATION_FAILED"),

    /**
     * The assembled document did not satisfy the vendored progression schemas.
     *
     * <p>Defect fix C29, on the near side of the wire: the document is refused here, loudly, rather
     * than posted and rejected with a 400 nothing looks at. Non-transient, because the same hearing
     * assembles the same invalid document on every delivery.
     */
    OUTBOUND_CONTRACT_VIOLATION("OUTBOUND_CONTRACT_VIOLATION"),

    /**
     * The POST to {@code add-court-register} failed in a way another attempt may fix.
     *
     * <p>Connect and IO failures, 5xx, 429 and 408. Ambiguous outcomes belong here too: a timeout
     * whose answer never arrived is retried, because a possible duplicate is absorbed downstream
     * and a possible loss is silent.
     */
    SUBMISSION_TRANSIENT("SUBMISSION_TRANSIENT"),

    /**
     * Progression refused an {@code add-court-register} body.
     *
     * <p>A 4xx other than 429 and 408: the request was understood and declined, so the same bytes
     * will be declined again and a redelivery would only spend the budget. The status is not part of
     * the code and the response body never is — both are the other side's words, and this code
     * reaches a dead-letter description and the log index.
     */
    SUBMISSION_REJECTED("SUBMISSION_REJECTED"),

    /**
     * Progression answered something other than the {@code 202 Accepted} its contract declares.
     *
     * <p>A separate code from a refusal because it is a separate investigation: a 4xx is this
     * service's body being declined, while a 2xx that is not 202 is the endpoint not behaving as the
     * contract says — a proxy answering on its behalf, or a route reaching something else. It is
     * never retried, because a second POST of a body that may already have been applied would append
     * a second register row, and it is never treated as success, because a command that was not
     * accepted was not enqueued.
     */
    SUBMISSION_NOT_ACCEPTED("SUBMISSION_NOT_ACCEPTED"),

    /**
     * The processed log could not be reached, so the delivery was not examined at all.
     *
     * <p>Distinct from every other failure here because it says nothing about the message. The
     * request may be perfectly good; the service simply was not fit to judge it, so the delivery is
     * handed back and intake stops rather than the delivery budget being spent on an outage of ours.
     */
    STORE_UNAVAILABLE("STORE_UNAVAILABLE"),

    /**
     * The register store refused the recording for a reason no redelivery can change.
     *
     * <p>Distinct from {@link #STORE_UNAVAILABLE}, which says nothing about the register: this is
     * the store <em>answering</em>, over a connection that plainly worked, and answering that the
     * row may not be written. The only refusals the recorder settles itself are the two it knows -
     * this command delivered again, and the race for the day's active-register key - so anything
     * else is a constraint nobody has accounted for, and delivering the same register into it four
     * more times would reach the same answer four more times.
     */
    REGISTER_NOT_RECORDED("REGISTER_NOT_RECORDED"),

    /**
     * A run reached its processing deadline and stopped itself.
     *
     * <p>Distinct from an ordinary transient failure on purpose: the run did not fail, it ran out of
     * the time its claim guarantees it. A rise in this code means runs are approaching their leases,
     * which is a capacity signal rather than a downstream one.
     */
    PROCESSING_DEADLINE_EXCEEDED("PROCESSING_DEADLINE_EXCEEDED");

    private final String storedCode;

    ReasonCode(final String code) {
        this.storedCode = code;
    }

    /**
     * The code as it is written to the processed log and reported onward.
     *
     * @return the bounded code for this reason
     */
    public String code() {
        return storedCode;
    }
}
