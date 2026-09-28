package uk.gov.hmcts.cp.yotresultsdistribution.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationStatus;

/**
 * What {@code GET /operations/batches?date=D} answers: one register date's batches.
 *
 * <p>The lines {@code list-batches --date D} printed, as a value. Identifiers, bounded codes and
 * counts, and a masked address per team - nothing else may appear here, because this is a report
 * about children and it is read over a support call and pasted into tickets (FR-016, FR-026).
 *
 * <p>The date comes back with the listing so that an answer read on its own says what it is about.
 * It is this service's own parse of what was asked for, not the characters the caller sent.
 *
 * @param date    the register date the listing is for
 * @param batches the date's batches, in the order the statement put them in
 */
public record BatchListingResponse(LocalDate date, List<BatchSummary> batches) {

    /**
     * One batch of the date.
     *
     * <p>{@link #courtHouse} is omitted where the batch's row carries none - the command printed a
     * dash, and the absence is the same fact said in the shape JSON has for it.
     *
     * @param batchId    the batch's identity, minted at assembly
     * @param courtHouse the hearing venue's court house, absent where the row carries none
     * @param state      where the batch has got to
     * @param records    how many registers the batch holds
     * @param recipients one row per team the batch was addressed to
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BatchSummary(
            UUID batchId,
            String courtHouse,
            BatchStatus state,
            int records,
            List<RecipientOutcome> recipients) {
    }

    /**
     * One team the batch was addressed to, and what came of telling them.
     *
     * @param address the masked address, which is never the address
     * @param outcome what came of the send for that row
     */
    public record RecipientOutcome(String address, NotificationStatus outcome) {
    }
}
