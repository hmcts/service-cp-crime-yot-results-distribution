package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.time.Instant;

/**
 * Who is delivering, and under which broker identity.
 *
 * <p>The claim owner is stamped on the row for the run's lifetime; {@code messageId} is kept for
 * audit and is still what every normal producer publish and every replay note carries, but it no
 * longer decides the {@code FAILED} branch on its own. That decision is {@code sequenceNumber}'s
 * (spec 007-replay-by-sequence-number): the broker assigns a new one to every accepted, enqueued
 * send — a portal re-send included, whatever its {@code messageId} — and never changes it on an
 * ordinary redelivery of the same stored message.
 *
 * <p>Whether this is the last delivery the queue permits travels with them, because it is a fact
 * about the delivery rather than about the request: the record cannot tell you it, and the run
 * that fails has to know it before it writes its outcome. A failure with deliveries remaining is
 * recorded RETRYING and handed back; the same failure on the final permitted delivery is recorded
 * FAILED and parked, in the transaction that stamps the identity above onto the row.
 *
 * <p>{@code deliveryCount} and {@code enqueueTime} exist for one purpose: a {@code FAILED} row with
 * no recorded parking sequence number — parked before this feature, or stale (FR-005a) — is judged
 * by the FR-005 heuristic, which reads both. Neither is consulted when a parking sequence number is
 * present and current.
 *
 * @param messageId              the broker's messageId for this delivery
 * @param claimOwner             this runner's identity — instance and delivery — recorded in
 *                               {@code claim_owner}
 * @param finalPermittedDelivery whether the queue's delivery budget ends with this delivery
 * @param sequenceNumber         the broker's own identity for this delivery; {@code -1L} where the
 *                               caller has no opinion (every call site except the ones that exercise
 *                               replay — the guard never reaches a branch that reads this value for
 *                               those)
 * @param deliveryCount          the broker's zero-based delivery count for this delivery; {@code 0}
 *                               where the caller has no opinion
 * @param enqueueTime            the broker's enqueue time for this delivery, or {@code null} where
 *                               absent or where the caller has no opinion
 */
public record DeliveryIdentity(
        String messageId,
        String claimOwner,
        boolean finalPermittedDelivery,
        long sequenceNumber,
        long deliveryCount,
        Instant enqueueTime) {

    /**
     * No broker sequence number is carried by this identity.
     *
     * <p>Not zero: the broker's own sequence numbers start at a small positive value and {@code 0}
     * is therefore a value a real delivery could carry, where this sentinel must never be mistaken
     * for one.
     */
    private static final long NO_SEQUENCE_NUMBER = -1L;

    /**
     * A delivery with no opinion about the broker facts FR-005 and FR-011 read.
     *
     * <p>Used throughout the test suites that predate spec 007-replay-by-sequence-number and never
     * exercise its decision — collision, durability, crash-window, claim-fencing and stale-runner
     * scenarios among them — none of which reach {@code replayOrPark}'s sequence-number comparison.
     * Forcing every one of those call sites to invent a sequence number they do not care about would
     * be exactly the blast radius minimalism exists to avoid.
     *
     * @param messageId              the broker's messageId for this delivery
     * @param claimOwner             this runner's identity, recorded in {@code claim_owner}
     * @param finalPermittedDelivery whether the queue's delivery budget ends with this delivery
     */
    public DeliveryIdentity(
            final String messageId, final String claimOwner, final boolean finalPermittedDelivery) {
        this(messageId, claimOwner, finalPermittedDelivery, NO_SEQUENCE_NUMBER, 0L, null);
    }

    /**
     * A delivery with retries still to come, and no opinion about the broker facts FR-005 and
     * FR-011 read.
     *
     * @param messageId  the broker's messageId for this delivery
     * @param claimOwner this runner's identity, recorded in {@code claim_owner}
     */
    public DeliveryIdentity(final String messageId, final String claimOwner) {
        this(messageId, claimOwner, false);
    }
}
