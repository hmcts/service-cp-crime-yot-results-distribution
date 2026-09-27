package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * Which completion mechanism learned a batch's outcome.
 *
 * <p>Recorded rather than inferred. There were two, and the second - the grace-period reconciler,
 * which asked systemdocgenerator what had become of a render nothing had announced - is gone with
 * the mechanism it named: nothing asks, so no outcome can arrive that way, and a batch nothing is
 * learned about is failed {@link BatchFailureReason#NOT_COMPLETED_BY_NEXT_RUN} by the next
 * scheduled run, which attributes itself to nobody.
 *
 * <p><strong>It stays a type with one constant rather than collapsing into a boolean.</strong> It
 * is an argument before it is a column: {@code DocumentOutcomeSink} carries it into the store's
 * {@code mark} calls, and a batch state change is a compare-and-set, so there is no moment between
 * the transition and the transition's own statement in which this could be written separately. A
 * second mechanism is exactly the kind of thing that comes back - a delivery callback, a
 * supplementary render, a platform event this service does not consume yet - and a boolean would
 * have to be widened at every call site to admit one.
 */
public enum CompletedBy {

    /** The {@code public.event} listener, which is the platform pattern and the only one left. */
    EVENT
}
