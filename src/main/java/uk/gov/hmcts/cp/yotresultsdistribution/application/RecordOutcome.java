package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.util.UUID;

/**
 * What became of a register that was handed to the register store.
 *
 * <p>A recording either starts a hearing's register or replaces the one that was there, and the two
 * are different events. Only the second one takes a row out of the next batch, so the row it
 * superseded is named rather than counted: a run that says "one recorded, one superseded" and cannot
 * say which row was superseded leaves support to guess at the register that will not be sent.
 *
 * <p><strong>A superseded row is not an error and not a duplicate.</strong> A hearing whose results
 * are shared twice before 18:00 produces two documents and one register, and the later one wins on
 * {@code register_time}. Progression reached the same answer by sweeping on the read side; this
 * service settles it in the write transaction, so nothing between the two reads can see two active
 * rows for one hearing (research §8).
 *
 * @param outputId            identity of the row this recording wrote
 * @param supersededOutputId  the earlier active row this recording replaced, or {@code null} where
 *                            this is the hearing's first register for the day
 */
public record RecordOutcome(UUID outputId, UUID supersededOutputId) {

    /**
     * Whether this recording replaced an earlier register for the same hearing and day.
     *
     * @return true where an earlier active row was superseded
     */
    public boolean superseded() {
        return supersededOutputId != null;
    }
}
