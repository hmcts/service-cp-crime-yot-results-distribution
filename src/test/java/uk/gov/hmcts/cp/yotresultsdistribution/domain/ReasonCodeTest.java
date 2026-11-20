package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ReasonCode#fromCode(String)}, the lookup a re-park resolves its dead-letter detail through
 * (spec 007-replay-by-sequence-number, FR-009).
 *
 * <p>Total over the declared enum, and total over everything else too: a stored reason text that
 * matches nothing declared here is the data anomaly FR-009 exists to catch, and the method answers
 * empty rather than throwing, so the caller — never this lookup — decides what an unrecognised value
 * means.
 */
@DisplayName("ReasonCode.fromCode")
class ReasonCodeTest {

    @ParameterizedTest
    @EnumSource(ReasonCode.class)
    void every_declared_reason_round_trips_through_its_own_code(final ReasonCode reason) {
        assertThat(ReasonCode.fromCode(reason.code()))
                .as("the stored code this reason writes must resolve back to itself")
                .contains(reason);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "NOT_A_REASON_CODE", "payload_read_refused"})
    void anything_not_declared_here_resolves_to_nothing(final String storedCode) {
        assertThat(ReasonCode.fromCode(storedCode))
                .as("a null, blank or unrecognised stored reason must never be guessed at")
                .isEqualTo(Optional.empty());
    }
}
