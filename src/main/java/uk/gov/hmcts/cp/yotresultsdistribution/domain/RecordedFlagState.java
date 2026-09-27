package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * The cutover flag as it stood when a register was recorded.
 *
 * <p>Stamped onto every {@code processed_output} row so a row can say which implementation was
 * meant to be generating when it arrived. Commands still on the queue after the producer stops
 * publishing may belong to hearings the resumed legacy also processed, and batching those
 * automatically would send the same register twice (research §12).
 *
 * <p><strong>{@link #UNKNOWN} is treated as {@link #OFF} for batching, and is not the same
 * statement.</strong> OFF says the flag was read and said the legacy generates; UNKNOWN says the
 * flag could not be read at all. Both are excluded from automatic batching and both are surfaced by
 * {@code list-batches --recorded-while-off}, but only one of them is a working App Configuration
 * store, and support needs to know which it has.
 */
public enum RecordedFlagState {

    /** The flag was read and said this service generates. */
    ON,

    /** The flag was read and said the legacy generates. */
    OFF,

    /** The flag was not read: recording never waits on a read it does not need. */
    UNKNOWN
}
