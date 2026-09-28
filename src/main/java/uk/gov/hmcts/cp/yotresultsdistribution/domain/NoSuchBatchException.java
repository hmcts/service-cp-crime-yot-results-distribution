package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.util.UUID;

/**
 * A caller named an identity nothing was ever assembled under.
 *
 * <p>Its own type because it is the one of the notifier's three refusals that is about the
 * <strong>identifier</strong> rather than about the batch behind it. A batch that exists and
 * carries no document, and a notification row the store refused and then holds none for, are both
 * a batch this service knows about and could not finish with; this one is a batch this service has
 * never heard of. Only that difference is worth a {@code 404}, and only a type can carry it: the
 * bare {@code IllegalStateException} the three used to share sent an operator to check an
 * identifier that was right, which is precisely what a bounded code exists to avoid.
 *
 * <p>It still <em>is</em> an {@link IllegalStateException}, so nothing that caught the three
 * together stops catching this one. What changes is that a caller who wants to tell it apart can.
 *
 * <p>The message carries the identity the caller named and nothing else - no status, no count, no
 * word of the store's own (constitution Principle VII).
 */
public class NoSuchBatchException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** The identity the caller named, which is the only thing about it worth writing down. */
    private final UUID named;

    /**
     * Records that nothing was ever assembled under this identity.
     *
     * @param namedBatch the identity the caller named
     */
    public NoSuchBatchException(final UUID namedBatch) {
        super("no register batch " + namedBatch + " to tell the recipients of");
        this.named = namedBatch;
    }

    /**
     * The identity nothing was assembled under.
     *
     * @return the batch the caller named
     */
    public UUID batchId() {
        return named;
    }
}
