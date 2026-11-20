package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.util.Set;

/**
 * The reviewed table a re-park falls back to when no category was recorded with it.
 *
 * <p>A {@code FAILED} row parked before spec 007-replay-by-sequence-number, or re-parked by an older
 * consumer mid-rollout (fix the table does not change the write-once rule for: a recorded category is
 * used as recorded and never reconstructed, FR-010), carries no {@code parking_category}. This is the
 * one place its stored {@code failure_reason} is mapped back to a {@link DeadLetterReason} instead.
 *
 * <p>Pure, total and closed over {@link ReasonCode}: every value maps to something, and nothing here
 * reads the row, the claim or the clock. Kept beside {@link ReasonCode} and {@link DeadLetterReason}
 * in {@code domain} for the same reason both of those are here — a closed-vocabulary lookup with no
 * I/O belongs with the vocabularies it closes over, not in the guard that calls it.
 */
public final class FailureCategoryMapping {

    /**
     * Every reason code the non-transient parking path can record today (fix the pipeline's own throw
     * sites choose these at — {@code recordNonTransientFailure}'s callers, cross-checked against
     * {@link ReasonCode}'s javadoc). A reason added to that path later MUST be added here in the same
     * change (FR-010) — there is deliberately no fallback rule that could silently cover it.
     */
    private static final Set<ReasonCode> NON_TRANSIENT_REASONS = Set.of(
            ReasonCode.PAYLOAD_READ_REFUSED,
            ReasonCode.REFERENCE_DATA_REFUSED,
            ReasonCode.TRANSFORMATION_FAILED,
            ReasonCode.OUTBOUND_CONTRACT_VIOLATION,
            ReasonCode.REGISTER_NOT_RECORDED,
            ReasonCode.SUBMISSION_REJECTED,
            ReasonCode.SUBMISSION_NOT_ACCEPTED);

    private FailureCategoryMapping() {
    }

    /**
     * The category a re-park reports when none was recorded with the row.
     *
     * <p>Total: an unrecognised or {@code null} stored reason maps to {@link DeadLetterReason#EXHAUSTED}
     * rather than throwing — the caller's own {@link ReasonCode#fromCode(String)} check is what decides
     * whether the reason text itself is usable; this method only ever answers the category.
     *
     * @param storedReasonCode the text read from {@code processed_request.failure_reason}, or
     *                         {@code null}
     * @return {@link DeadLetterReason#NON_TRANSIENT} for the reasons the non-transient path records;
     *         {@link DeadLetterReason#EXHAUSTED} for every other value
     */
    public static DeadLetterReason categoryFor(final String storedReasonCode) {
        return ReasonCode.fromCode(storedReasonCode)
                .filter(NON_TRANSIENT_REASONS::contains)
                .map(reason -> DeadLetterReason.NON_TRANSIENT)
                .orElse(DeadLetterReason.EXHAUSTED);
    }
}
