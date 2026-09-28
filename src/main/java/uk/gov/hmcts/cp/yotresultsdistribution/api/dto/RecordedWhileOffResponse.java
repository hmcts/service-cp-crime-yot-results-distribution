package uk.gov.hmcts.cp.yotresultsdistribution.api.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;

/**
 * What {@code GET /operations/registers/recorded-while-off} answers.
 *
 * <p>The registers automatic batching passed over because the flag did not say ON when they
 * arrived. They are the rows a rollback has to account for: the nightly run skips them
 * deliberately, so without an answer that names them every one would sit waiting for somebody to
 * notice it.
 *
 * <p>An empty window answers {@code records: []} rather than silence - "nothing is waiting" is an
 * answer, and an absent list is not.
 *
 * @param records the waiting registers, in the store's own order
 */
public record RecordedWhileOffResponse(List<RecordedRegister> records) {

    /**
     * One register recorded while the legacy was the implementation that generates.
     *
     * @param recordId     the recorded register's identity
     * @param hearingId    the hearing it was built from
     * @param registerDate the London register day it belongs to
     * @param flag         what the flag said when the register was recorded
     */
    public record RecordedRegister(
            UUID recordId,
            UUID hearingId,
            LocalDate registerDate,
            RecordedFlagState flag) {
    }
}
