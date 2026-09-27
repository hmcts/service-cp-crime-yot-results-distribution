package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One recorded register still waiting to be batched, as the exception report reads it.
 *
 * <p>The fourth BATCH_LATE source, and the one that has no batch to name: a register the most
 * recent scheduled generation run left where it was has never been part of a batch at all, so what
 * identifies it is its own output row and the hearing it was built from.
 *
 * <p>A projection rather than {@link RegisterRecord}, which carries the whole document and no age.
 * The age comes from {@code register_time} in the same statement that selects the row, for the
 * reason every other age in this report does: an age derived in the JVM from a stored timestamp is
 * the cross-clock comparison V1 forbids.
 *
 * @param outputId      the {@code processed_output} row's identity
 * @param hearingId     the hearing the register was built from
 * @param courtCentreId the court centre whose day it would have been batched under
 * @param registerDate  the register day it would have been batched under
 * @param registerTime  the document's register instant, which is when it was recorded
 * @param ageSeconds    how long it has been waiting, measured by the database from that instant
 */
public record RecordedRegisterSummary(
        UUID outputId,
        UUID hearingId,
        UUID courtCentreId,
        LocalDate registerDate,
        Instant registerTime,
        long ageSeconds) {
}
