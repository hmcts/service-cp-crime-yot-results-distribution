package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * Why a gauge refresh the intake sweep tried to take could not be taken, as the bounded
 * {@code reason} label.
 *
 * <p>Two codes and no more, because the counter exists to make <em>one</em> absorbed refusal
 * visible and a third code would mean a third thing was being absorbed. Bounded by the compiler for
 * the reason {@link ReportRunOutcome} is: this counter is the only evidence the absorption leaves,
 * and evidence published under a mistyped label is evidence nobody reads.
 */
public enum SweepFailureReason {

    /** The store could not be reached at all: a refresh missing during an outage of theirs. */
    STORE_UNAVAILABLE,

    /** Anything else the read raised, which is a bug here rather than an outage there. */
    UNEXPECTED
}
