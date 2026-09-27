package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * Thrown when this service's own store refused a write because a rule about the row said no.
 *
 * <p>The store <em>answering</em>, over a connection that plainly worked, which is what separates
 * this from {@link StoreUnavailableException}: nothing is retried on its account, nothing is
 * suspended, and the caller is the one that knows what to do about it. On
 * {@code register_notification} there is exactly one such rule -
 * {@code UNIQUE (batch_id, email_address)} - and the caller that meets it is a notification run
 * whose recipient was minted a row by another mechanism a moment earlier: the operator's resend and
 * the outcome sink can reach one batch at the same time, and only one of them can write the row. It
 * settles that by reading back the row that won and posting under <em>its</em> identity, which is
 * the whole reason the refusal has to reach it rather than be absorbed.
 *
 * <p><strong>The message is this repository's own words and the cause is deliberately not
 * attached.</strong> Postgres reports a unique violation with a detail line quoting the colliding
 * key's values, and one of those values is a recipient's e-mail address - so the driver's exception
 * carries a component that may never reach a log line at INFO or above, a metric label or an
 * estate-wide log index (constitution Principle VII), and a stack trace is all three. What the cause
 * would have told a reader - which rule refused the row, and for which batch - is in the bounded
 * phrase the call site passes instead.
 *
 * <p>It is a domain signal for the reason {@link StoreUnavailableException} is: the refusal is
 * discovered by JDBC, and the application core may not name a {@code org.springframework.dao} type
 * at all (constitution Principle V). The persistence layer translates it at the boundary of the
 * package that owns the datasource, and the core keys off this alone.
 */
public class StoreRefusedRowException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the failure around a row the store would not take.
     *
     * @param reason a bounded phrase naming the write and the rule that refused it - never the
     *               driver's words, and never a value from the row
     */
    public StoreRefusedRowException(final String reason) {
        super(reason);
    }
}
