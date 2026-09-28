package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.time.LocalDate;
import java.util.UUID;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;

/**
 * One register automatic batching passed over because the flag did not say ON.
 *
 * <p>The rows {@code list-batches --recorded-while-off} named. They are the ones a rollback has to
 * account for: the nightly run skips them deliberately, so without something that names them every
 * one would sit waiting for somebody to notice it.
 *
 * @param recordId     the recorded register's identity
 * @param hearingId    the hearing it was built from
 * @param registerDate the London register day it belongs to
 * @param flag         what the flag said when the register was recorded
 */
public record RecordedWhileOff(
        UUID recordId,
        UUID hearingId,
        LocalDate registerDate,
        RecordedFlagState flag) {
}
