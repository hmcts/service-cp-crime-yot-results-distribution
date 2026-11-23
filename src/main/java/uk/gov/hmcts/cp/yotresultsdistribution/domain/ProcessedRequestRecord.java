package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.time.Instant;

/**
 * What the guard reads back about a request it has seen before.
 *
 * <p>The projection of {@code processed_request} the read-and-branch step selects — the state the
 * branch decision needs, and nothing else.
 *
 * <p>The two claim columns are carried for the log line, not for the decision. Whether a claim is
 * still live is decided inside the conditional update, by the database comparing
 * {@code claim_expires_at} against its own {@code now()}; a guard that decided liveness here would
 * be comparing a JVM clock reading against a stored timestamp, which is exactly the multi-node skew
 * the data model's single-time-authority rule exists to rule out.
 *
 * <p>{@code parkingSequenceNumber}, {@code parkingCategory} and {@code parkingAttemptCount} are
 * written together and cleared together (spec 007-replay-by-sequence-number, FR-001/FR-014); all
 * three are {@code null} on a legacy row or one an older consumer has re-parked without knowing
 * them. {@code parkingAttemptCount} is compared against {@link #attempts()} to tell an exact parking
 * (this version's own, still current) from a stale one (FR-005a) — it is never itself compared to a
 * delivery's anything. {@code updatedAt} is new to this read for the same reason: for a
 * {@code FAILED} row nothing changes it, so it <em>is</em> the parking time the FR-005 heuristic's
 * margin is measured from.
 *
 * @param status                the record's current state
 * @param fingerprint           the fingerprint written when the record was created; the collision
 *                              comparison
 * @param failureReason         the last recorded failure reason code, or {@code null}
 * @param exhaustedMessageId    the identity of the delivery that exhausted the retries, or
 *                              {@code null}
 * @param attempts              the lifetime count of pipeline-run starts
 * @param claimOwner            the current claim's owner, or {@code null} when no claim is held
 * @param claimExpiresAt        the current claim's expiry, or {@code null} when no claim is held
 * @param parkingSequenceNumber the broker sequence number of the delivery that parked this request's
 *                              current {@code FAILED} generation, or {@code null}
 * @param parkingCategory       the {@link DeadLetterReason} name the parking write used, or
 *                              {@code null}
 * @param parkingAttemptCount   the {@link #attempts()} value recorded with the two fields above, or
 *                              {@code null}
 * @param updatedAt             when the row was last written; the parking time for a {@code FAILED}
 *                              row
 */
public record ProcessedRequestRecord(
        RequestStatus status,
        String fingerprint,
        String failureReason,
        String exhaustedMessageId,
        int attempts,
        String claimOwner,
        Instant claimExpiresAt,
        Long parkingSequenceNumber,
        String parkingCategory,
        Integer parkingAttemptCount,
        Instant updatedAt) {
}
