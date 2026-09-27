package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * Whether a failure is worth retrying.
 *
 * <p>The pipeline branches on this rather than on an exception type, because the progression
 * submission adapter needs both from one exception: connect, IO, 5xx, 429 and 408 are retried, a 4xx
 * refusal is not.
 */
public enum FailureClassification {

    /** Worth retrying: the delivery is returned to the broker. */
    TRANSIENT("transient"),

    /** Not worth retrying: no redelivery will change the outcome. */
    NON_TRANSIENT("non-transient");

    private final String metricLabel;

    FailureClassification(final String label) {
        this.metricLabel = label;
    }

    /**
     * The metric label value.
     *
     * @return the label this classification is counted under
     */
    public String label() {
        return metricLabel;
    }
}
