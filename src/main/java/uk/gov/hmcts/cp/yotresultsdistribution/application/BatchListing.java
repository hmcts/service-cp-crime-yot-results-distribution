package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.util.List;
import java.util.UUID;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationStatus;

/**
 * One batch as a listing describes it: identifiers, bounded codes and counts, and nothing else.
 *
 * <p>This is what {@code list-batches --date D} printed, as a value instead of as two kinds of
 * line. Every field is something support may say out loud about a register for children: a batch
 * identity, a court house, the state the batch stands at, how many registers it holds, and one
 * masked address per team with what came of telling them. No defendant, no case, no document and
 * no whole address appears here or can be derived from what does (constitution Principle VII,
 * FR-016, FR-026).
 *
 * <p>{@link #courtHouse} is {@code null} where the batch's row carries none - the command printed
 * {@code -} in that position, and the absence is the same fact said in the shape JSON has for it.
 *
 * @param batchId    the batch's identity, minted at assembly
 * @param courtHouse the hearing venue's court house, or {@code null} where the row carries none
 * @param state      where the batch has got to
 * @param records    how many registers the batch holds, read back by identity
 * @param recipients one row per team the batch was addressed to, in the statement's order
 */
public record BatchListing(
        UUID batchId,
        String courtHouse,
        BatchStatus state,
        int records,
        List<Recipient> recipients) {

    /**
     * One team the batch was addressed to, and what came of telling them.
     *
     * @param address the masked address, which is never the address
     * @param outcome what came of the send for that row
     */
    public record Recipient(String address, NotificationStatus outcome) {
    }
}
