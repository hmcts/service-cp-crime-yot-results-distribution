package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.time.LocalDate;
import java.util.List;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterNotificationRepository;

/**
 * The two listings a support call about a register is answered from.
 *
 * <p>{@code ListBatchesCli}'s reads, moved here unchanged. The command held them because it was the
 * only caller; a controller may not, because a controller that reads three repositories is not an
 * inbound adapter any more (design rules, "Persistence"). Same reads, same order, same masking -
 * the difference is that the lines become values and the {@code Consumer<String>} is gone.
 *
 * <p><strong>Nothing here sorts, groups or de-duplicates.</strong> Both statements behind the
 * listing are ordered - batches by court house then identity, recipient rows by address - and this
 * class hands back what it was handed in the order it was handed it, which is what makes the read's
 * order the whole answer rather than this class's.
 *
 * <p><strong>A read that fails propagates.</strong> The command exited {@code FAILED} rather than
 * printing half a listing, because a listing that came back short is the answer support would act
 * on. The same rule in the shape a service has for it: the store's own unchecked type leaves here,
 * and the caller decides what a caller should be told.
 */
public class BatchListingService {

    /** What survives of an address whose local part is too short to keep a character of. */
    private static final String MASK = "***";

    /** What separates a local part from the domain it is at. */
    private static final char AT = '@';

    /** The date's batches, read in the order the statement puts them in. */
    private final RegisterBatchRepository batches;

    /** Each batch's recipient rows, which is where an outcome per team comes from. */
    private final RegisterNotificationRepository notifications;

    /**
     * The register rows: how many a batch holds, and which of them were recorded while the flag was
     * off.
     */
    private final RegisterStore store;

    /**
     * Creates the listings over the three reads they are built from.
     *
     * @param batchRepository        the date's batches
     * @param notificationRepository each batch's recipient rows
     * @param registerStore          the register rows behind a batch's count and the
     *                               recorded-while-off listing
     */
    public BatchListingService(final RegisterBatchRepository batchRepository,
            final RegisterNotificationRepository notificationRepository,
            final RegisterStore registerStore) {
        this.batches = batchRepository;
        this.notifications = notificationRepository;
        this.store = registerStore;
    }

    /**
     * One register date's batches, each with its recipients under it.
     *
     * @param registerDate the London register day to list
     * @return the date's batches in the statement's order, which may be empty
     */
    public List<BatchListing> batchesOn(final LocalDate registerDate) {
        return batches.findByRegisterDate(registerDate).stream().map(this::listed).toList();
    }

    /**
     * The registers automatic batching passed over because the flag did not say ON.
     *
     * <p>The date's two reads are not made at all: this is a question about registers rather than
     * about batches, and a listing that read both would put a date's documents under a heading
     * about rows that never reached one.
     *
     * @return the waiting registers in the store's own order, which may be empty
     */
    public List<RecordedWhileOff> recordedWhileOff() {
        return store.recordedWhileOff().stream()
                .map(waiting -> new RecordedWhileOff(waiting.outputId(), waiting.hearingId(),
                        waiting.key().registerDate(), waiting.flagState()))
                .toList();
    }

    /**
     * One batch, with its record count and its recipients.
     *
     * <p>The record count is the batch's own rows read back by identity - the same read the render
     * payload is built from, so the documents really are in front of the listing as it is made -
     * and a count is all that may come out of them.
     *
     * @param batch the batch as the date's read answered it
     * @return the batch as a listing describes it
     */
    private BatchListing listed(final RegisterBatch batch) {
        final int records = store.batched(batch.batchId()).size();
        final List<BatchListing.Recipient> recipients =
                notifications.findByBatchId(batch.batchId()).stream()
                        .map(recipient -> new BatchListing.Recipient(
                                masked(recipient.emailAddress()), recipient.status()))
                        .toList();
        return new BatchListing(batch.batchId(), orAbsent(batch.courtHouse()), batch.status(),
                records, recipients);
    }

    /**
     * A court house, or the absence of one said in the shape JSON has for it.
     *
     * @param courtHouse the court house the batch's row carries, or {@code null}
     * @return the court house, or {@code null} where there is none to name
     */
    private static String orAbsent(final String courtHouse) {
        return courtHouse == null || courtHouse.isEmpty() ? null : courtHouse;
    }

    /**
     * One recipient's address, masked to as little as tells one team from another.
     *
     * <p>Character for character what {@code ListBatchesCli.masked} did. One character of the local
     * part and the domain - and not even that where the local part is a single character, because
     * an address short enough to be masked whole is published whole otherwise. An address with no
     * domain to show is masked entirely: a subscription whose address is not an address is exactly
     * the row support is looking for, and printing it because it parsed badly is the one case
     * masking must not fall through on.
     *
     * @param address the address the notification row holds
     * @return the masked address, which is never the address
     */
    private static String masked(final String address) {
        // Absent, and an address with nothing before or after its at-sign, all mask to the same
        // thing: the mask is what is always printed, and the two halves are what may survive it.
        final int at = address == null ? -1 : address.lastIndexOf(AT);
        final String local = at > 0 ? address.substring(0, at) : "";
        final String kept = local.length() > 1 ? local.substring(0, 1) : "";
        return kept + MASK + (at < 0 ? "" : address.substring(at));
    }
}
