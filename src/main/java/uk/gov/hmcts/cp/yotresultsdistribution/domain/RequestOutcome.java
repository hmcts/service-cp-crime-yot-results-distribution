package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * How a request finished, for the terminal-outcome counter.
 */
public enum RequestOutcome {

    /** The pipeline ran and the outcome was recorded. */
    COMPLETED("completed"),

    /** The request was parked — its permitted deliveries exhausted, or a failure no retry can fix. */
    FAILED("failed");

    private final String metricLabel;

    RequestOutcome(final String label) {
        this.metricLabel = label;
    }

    /**
     * The metric label value. Fixed here rather than derived from the constant name, so renaming a
     * constant cannot silently rename a dashboard's series.
     *
     * @return the label this outcome is counted under
     */
    public String label() {
        return metricLabel;
    }

    /**
     * How a request finished, from the terminal state it reached.
     *
     * <p>The two vocabularies say the same thing about the same moment - the state machine's
     * terminal states and the counter's two series - and this is the one place the mapping is
     * written. A caller that chose the outcome itself could choose one that disagreed with the
     * state it wrote down, and the counter and the log would then partition a day differently.
     *
     * @param status the terminal state the run reached
     * @return the outcome that state is counted as
     * @throws IllegalArgumentException where the state is not terminal, because a run that has not
     *                                  finished has not finished in any particular way
     */
    public static RequestOutcome reached(final RequestStatus status) {
        return switch (status) {
            case COMPLETED -> COMPLETED;
            case FAILED -> FAILED;
            case RECEIVED, RETRYING -> throw new IllegalArgumentException(
                    status + " is not a terminal state, so it is not an outcome");
        };
    }
}
