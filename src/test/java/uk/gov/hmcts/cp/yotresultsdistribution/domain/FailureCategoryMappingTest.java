package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link FailureCategoryMapping#categoryFor(String)}, FR-010's explicit table for a re-park with no
 * recorded category (spec 007-replay-by-sequence-number): a row parked before the change, or by an
 * older consumer mid-rollout that does not know {@code parking_category} exists.
 */
@DisplayName("FailureCategoryMapping.categoryFor")
class FailureCategoryMappingTest {

    /** The seven reason codes the non-transient parking path can record today (FR-010). */
    private static final ReasonCode[] NON_TRANSIENT_REASONS = {
        ReasonCode.PAYLOAD_READ_REFUSED,
        ReasonCode.REFERENCE_DATA_REFUSED,
        ReasonCode.TRANSFORMATION_FAILED,
        ReasonCode.OUTBOUND_CONTRACT_VIOLATION,
        ReasonCode.REGISTER_NOT_RECORDED,
        ReasonCode.SUBMISSION_REJECTED,
        ReasonCode.SUBMISSION_NOT_ACCEPTED,
    };

    @ParameterizedTest
    @EnumSource(value = ReasonCode.class, names = {
        "PAYLOAD_READ_REFUSED", "REFERENCE_DATA_REFUSED", "TRANSFORMATION_FAILED",
        "OUTBOUND_CONTRACT_VIOLATION", "REGISTER_NOT_RECORDED", "SUBMISSION_REJECTED",
        "SUBMISSION_NOT_ACCEPTED"})
    void every_non_transient_path_reason_maps_to_non_transient(final ReasonCode reason) {
        assertThat(FailureCategoryMapping.categoryFor(reason.code()))
                .as("a reason the non-transient path can record must map to NON_TRANSIENT, "
                        + "the category that path itself would have written")
                .isEqualTo(DeadLetterReason.NON_TRANSIENT);
    }

    @ParameterizedTest
    @EnumSource(value = ReasonCode.class, mode = EXCLUDE, names = {
        "PAYLOAD_READ_REFUSED", "REFERENCE_DATA_REFUSED", "TRANSFORMATION_FAILED",
        "OUTBOUND_CONTRACT_VIOLATION", "REGISTER_NOT_RECORDED", "SUBMISSION_REJECTED",
        "SUBMISSION_NOT_ACCEPTED"})
    void every_other_declared_reason_maps_to_exhausted(final ReasonCode reason) {
        assertThat(FailureCategoryMapping.categoryFor(reason.code()))
                .as("every reason outside the non-transient path's own seven must map to EXHAUSTED")
                .isEqualTo(DeadLetterReason.EXHAUSTED);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"NOT_A_REASON_CODE", "payload_read_refused"})
    void a_missing_or_unrecognised_reason_maps_to_exhausted_and_never_throws(final String storedCode) {
        assertThat(FailureCategoryMapping.categoryFor(storedCode))
                .as("an unrecognised stored reason is the EXHAUSTED default, never a thrown "
                        + "exception and never the raw text reconstructed into a guess")
                .isEqualTo(DeadLetterReason.EXHAUSTED);
    }

    @Test
    void the_table_matches_the_mapping_s_own_declared_set() {
        // Guards the test fixture above against drifting from the production table silently.
        for (final ReasonCode reason : NON_TRANSIENT_REASONS) {
            assertThat(FailureCategoryMapping.categoryFor(reason.code()))
                    .isEqualTo(DeadLetterReason.NON_TRANSIENT);
        }
    }
}
