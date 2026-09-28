package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * The four states a processed-request record can hold.
 *
 * <p>The constant names are the values stored in {@code processed_request.status} and enumerated by
 * the V1 check constraint, so a rename here is a schema change, not a refactor.
 *
 * <p>{@code COMPLETED} and {@code FAILED} are terminal, but terminal is not the same as final: a
 * {@code FAILED} record is replayable under a fresh message identity, while a {@code COMPLETED} one
 * is never run again.
 */
public enum RequestStatus {

    /** Recorded, claimed, and being run for the first time or after a replay. */
    RECEIVED,

    /** A run failed transiently and deliveries of the message remain. */
    RETRYING,

    /** The run succeeded and its outcome is durable. */
    COMPLETED,

    /** The permitted deliveries were exhausted; parked, and replayable under a fresh identity. */
    FAILED;

    /**
     * Whether this state is one a run stops in.
     *
     * <p>Asked by the request-duration timer, which may only take a sample from a run that has
     * finished. The answer lives on the enumeration rather than at the instrument because it is a
     * property of the state and not of the measurement: a fifth constant added later has to decide
     * which side of this line it falls on, and deciding it here is deciding it once.
     *
     * <p>Terminal is not the same as final. A {@code FAILED} record is replayable under a fresh
     * message identity, and the run that reached it still finished.
     *
     * @return whether a run in this state has finished
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
