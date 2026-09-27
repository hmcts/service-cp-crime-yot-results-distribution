package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * Thrown when this service's own store went away, so nothing about the delivery could be recorded.
 *
 * <p><strong>It is not a classified failure and deliberately carries no
 * {@link FailureClassification}.</strong> Every other failure in this service answers "is this worth
 * retrying" and is recorded on the processed log; this one cannot be recorded at all, because the
 * log is the thing that is gone. What the delivery needs is a suspension - handed back <em>and</em>
 * intake stopped - which belongs to the transport adapter and to nothing else (spec FR-015). So it
 * travels out of the run untouched, and the listener acts on it.
 *
 * <p><strong>It is a domain signal, and that is the whole point of it.</strong> The store outage is
 * discovered by JDBC, but the application core is not allowed to know that (constitution Principle
 * V): the persistence layer translates the three Spring classes a dead store actually produces -
 * {@code DataAccessResourceFailureException}, {@code RecoverableDataAccessException} and
 * {@code TransientDataAccessException} - into this, and the core and the listener key off this
 * alone. Which classes those are is one rule about one store, and it now lives in one place instead
 * of being copied into two.
 *
 * <p><strong>Contention is not one of them.</strong> {@code ConcurrencyFailureException} extends
 * {@code TransientDataAccessException} and is the store <em>answering</em>, over a connection that
 * plainly worked: two writers met on one row and this one lost, which clears itself on the next
 * delivery. Translating it here would stop the whole queue for it, so the persistence layer lets it
 * out unchanged and it is settled as the ordinary transient failure it is.
 *
 * <p>The message is a bounded phrase written by the layer that raises it, never the driver's own
 * words: this reaches an ERROR line about a flow whose every defendant is a child (constitution
 * Principle VII). The cause is attached, so the stack trace still says which statement failed.
 */
public class StoreUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the failure around what the store raised.
     *
     * @param reason a bounded phrase saying which read or write could not be made
     * @param cause  what the datasource raised
     */
    public StoreUnavailableException(final String reason, final Throwable cause) {
        super(reason, cause);
    }
}
