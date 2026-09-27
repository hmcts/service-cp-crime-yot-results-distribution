package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * Why the transformation produced no register.
 *
 * <p>Three reasons, and only three. {@link CompletionReason} carries five, but the other two are not
 * the transformation's to give: {@code submitted} is progression's answer and
 * {@code group-proceedings} is a decision made before the transformation is called at all. A
 * transformation that could name either of them would let a hearing complete as submitted having
 * sent nothing, or as suppressed having never been asked — outcomes the processed log cannot tell
 * from the real ones, in a service whose reason to exist is that the legacy's five endings are one
 * undifferentiated {@code Success: true} (defect C33).
 *
 * <p>So the three are their own type, and the five outcomes are mutually exclusive by construction
 * rather than by the pipeline remembering to check.
 */
public enum NoRegisterReason {

    /** The hearing gathered no register defendants at all (C6). */
    NO_DEFENDANTS(CompletionReason.NO_DEFENDANTS),

    /** Reference data answered, and nothing in force matched the court centre. */
    NO_SUBSCRIPTIONS(CompletionReason.NO_SUBSCRIPTIONS),

    /** Subscriptions matched, and the youth filter left nobody on the register. */
    NO_YOUTH_DEFENDANTS(CompletionReason.NO_YOUTH_DEFENDANTS);

    // PMD.AvoidFieldNameMatchingMethodName: the accessor is deliberately named after what it
    // answers, in the record-style this codebase uses throughout; renaming the field would say less.
    @SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
    private final CompletionReason completion;

    NoRegisterReason(final CompletionReason completion) {
        this.completion = completion;
    }

    /**
     * How the run that met this reason is recorded and counted.
     *
     * @return the completion reason written to the processed log
     */
    public CompletionReason completion() {
        return completion;
    }
}
