package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.util.UUID;

/**
 * The single-runner claim a delivery holds while its pipeline run is in flight.
 *
 * <p>Carries everything an outcome write needs to prove it may write: the key it settles, and the
 * owner and token it acquired the claim under. Every outcome write is predicated on both, so a
 * runner whose claim was reclaimed while it was working writes nothing — it discards its result and
 * abandons the delivery.
 *
 * <p>The token is minted fresh on <em>every</em> acquisition. Owner alone is never an acquisition
 * condition: a re-acquire path predicated on the owner would let one runner increment
 * {@code attempts} twice for a single run.
 *
 * <p>There is no expiry here. Claim liveness is decided by the database, comparing
 * {@code claim_expires_at} against {@code now()} inside the conditional update — never by comparing
 * a JVM clock reading against a stored timestamp.
 *
 * <p>The delivery's message identity travels with the claim rather than being handed separately to
 * the write that parks a request. The identity recorded as having exhausted the retries has to be
 * the delivery that was running, and carrying it here makes any other identity unrepresentable
 * instead of merely wrong.
 *
 * <p>{@code sequenceNumber} travels the same way, and for the same reason (spec
 * 007-replay-by-sequence-number): the delivery that acquires the claim is by definition the one
 * whose failure would park the request, so {@code recordNonTransientFailure}/{@code
 * recordExhaustion} write this claim's own sequence number into {@code parking_sequence_number}
 * rather than taking one as a separate parameter.
 *
 * @param source         the record's key, part 1
 * @param requestId      the record's key, part 2
 * @param owner          the runner identity stamped in {@code claim_owner}
 * @param token          the token minted for this acquisition
 * @param messageId      the broker identity of the delivery that acquired this claim
 * @param sequenceNumber the broker sequence number of the delivery that acquired this claim;
 *                       {@code -1L} where the caller has no opinion (every call site except the one
 *                       that acquires a claim for a real delivery)
 */
public record RunClaim(
        String source, UUID requestId, String owner, UUID token, String messageId,
        long sequenceNumber) {

    /** Mirrors {@link DeliveryIdentity}'s sentinel; the two must never collide in meaning. */
    private static final long NO_SEQUENCE_NUMBER = -1L;

    /**
     * A claim with no opinion about which delivery's sequence number acquired it.
     *
     * <p>Used throughout the test suites that predate spec 007-replay-by-sequence-number and never
     * exercise its decision. Forcing every one of those call sites to invent a sequence number they
     * do not care about would be exactly the blast radius minimalism exists to avoid.
     *
     * @param source    the record's key, part 1
     * @param requestId the record's key, part 2
     * @param owner     the runner identity stamped in {@code claim_owner}
     * @param token     the token minted for this acquisition
     * @param messageId the broker identity of the delivery that acquired this claim
     */
    public RunClaim(
            final String source, final UUID requestId, final String owner, final UUID token,
            final String messageId) {
        this(source, requestId, owner, token, messageId, NO_SEQUENCE_NUMBER);
    }
}
