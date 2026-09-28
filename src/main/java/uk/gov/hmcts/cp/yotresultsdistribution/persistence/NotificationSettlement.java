package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

/**
 * What a settlement statement did to one recipient's row.
 *
 * <p>Three answers rather than a changed-row count, because the count could not tell two of them
 * apart once the statement stopped refusing a row an acceptance had already made terminal. Every
 * POST made for a row is added to its lifetime {@code attempts} whatever state the row is in - a
 * POST that happened is a POST that happened - and only the three settlement columns are conditional
 * on the row not already being ACCEPTED. So a statement that changed a row is no longer the same
 * thing as a statement that settled one, and a caller that read a row count would count a tallied
 * late attempt as an applied settlement.
 *
 * <p>Bounded, and read by the caller rather than logged as it is: the bounded reason a late outcome
 * is counted under is derived from this together with what notificationnotify answered, and neither
 * may ever carry a batch identity or a recipient (constitution Principle VII).
 */
public enum NotificationSettlement {

    /**
     * The row was not yet settled, so it now carries this attempt's verdict and this attempt's
     * POSTs.
     */
    APPLIED,

    /**
     * The row was already ACCEPTED, so the POSTs were tallied and the settlement was not applied.
     *
     * <p>A team that has been told has been told: the acceptance, the status line that made it one
     * and the instant it was settled at all stand, and this attempt is on the row's attempt total
     * because it was really made. Which bounded reason the caller counts it under depends on how
     * this attempt itself ended - a late refusal and a late acceptance are two different readings of
     * two notifiers in the cycle at once.
     */
    ATTEMPTS_ONLY,

    /**
     * The store holds no row under that identity, so nothing was tallied and nothing was settled.
     *
     * <p>An invariant breach when met: the notifying leg only ever settles a row it read back or
     * minted itself, so this result is never expected, and it is named for exactly that reason: an
     * answer of {@code 0} rows shared this
     * meaning with {@link #ATTEMPTS_ONLY} and made a lost row indistinguishable from a row somebody
     * else had accepted. It is a store that no longer holds a row this service wrote, which is loud
     * and counted where it is met rather than folded into a reading about racing notifiers.
     */
    ABSENT
}
