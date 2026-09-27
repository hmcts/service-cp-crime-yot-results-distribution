package uk.gov.hmcts.cp.yotresultsdistribution.api.dto;

import java.util.UUID;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;

/**
 * What {@code POST /operations/batches/{batchId}/notify} answers when the resend settled.
 *
 * <p>Counts, an identifier and two bounded codes, which is the whole of what a caller is told: a
 * recipient's address is not on it, because the listing is where a masked address belongs and a
 * resend's answer is about the batch.
 *
 * <p>A caller branches on the {@code disposition} and not on the counts - the tally is the batch as
 * it now stands, not this call's own work.
 *
 * @param batchId     the batch the resend was about
 * @param accepted    how many recipients notificationnotify has taken the command for
 * @param failed      how many it has not
 * @param state       the terminal state the batch is settled in
 * @param disposition what this call actually did, as its own bounded code
 */
public record NotifyBatchResponse(UUID batchId, int accepted, int failed, BatchStatus state,
                                  String disposition) {
}
