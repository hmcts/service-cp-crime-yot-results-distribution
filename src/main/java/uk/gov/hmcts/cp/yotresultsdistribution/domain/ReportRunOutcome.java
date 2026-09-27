package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.util.List;

/**
 * How one run of the exception report ended, as the bounded {@code outcome} label.
 *
 * <p>An enum rather than a string for the reason review gate 3 raised against the first shape of
 * {@code ProcessingMetrics.exceptionReportRun}: a label a caller spells is a label a caller can
 * mistype, and a mistyped label is a new series that no alert fires on and nobody notices. Three
 * words, the compiler holds the caller to them, and the rendering is the same
 * {@code name().toLowerCase(...)} every other bounded label in this service is published under.
 *
 * <p>The three are the same three the run line carries, deliberately: a dashboard filtered on the
 * counter and a query over the run lines must partition a morning the same way.
 */
public enum ReportRunOutcome {

    /** Every sink the run was configured with took the report. */
    DELIVERED,

    /** Somebody was told and somebody was not; the rest is resendable. */
    PARTIAL,

    /** No sink delivered anything at all, so the morning's exceptions were told to nobody. */
    FAILED;

    /**
     * How one run went, folded from what each sink it asked answered.
     *
     * <p>Here rather than in the job and again in the command. The two held a copy each until
     * review gate 6, and a fold written twice is a morning the counter and the terminal can come to
     * disagree about - which is the same class of defect as the two words for an absent sink that
     * {@link DeliveryWord} ends.
     *
     * <p><strong>{@link #FAILED} means nobody was told, and nothing weaker.</strong> It is the one
     * outcome an alert has to be able to mean on its own, so it is reserved for the run in which
     * every sink delivered nothing at all. A sink that told two of its three recipients told two
     * people: counting that as a morning nobody heard about would send an operator looking for a
     * report that most of support is reading, and would hide the real silence inside the same
     * series.
     *
     * <p>So a sink that delivered to some of its recipients is {@link #PARTIAL} even where it is
     * the only sink there is - the rest is a resend, and that nuance is expressed here rather than
     * hidden inside a sink's own answer. A run that asked nobody at all - one that could not build
     * a report, or one on a context holding no sink - is {@link #FAILED}, because nothing was told
     * and there is nothing to resend.
     *
     * @param delivered one outcome per sink asked, in the order they were asked
     * @return the bounded outcome
     */
    public static ReportRunOutcome from(final List<DeliveryOutcome> delivered) {
        final ReportRunOutcome ended;
        if (delivered.isEmpty()
                || delivered.stream().allMatch(ReportRunOutcome::toldNobody)) {
            ended = FAILED;
        } else if (delivered.stream().allMatch(outcome ->
                outcome.status() == DeliveryStatus.DELIVERED)) {
            ended = DELIVERED;
        } else {
            ended = PARTIAL;
        }
        return ended;
    }

    /**
     * Whether one sink's answer says that nobody it was asked about heard anything.
     *
     * @param outcome one sink's answer
     * @return true where it delivered to nobody
     */
    private static boolean toldNobody(final DeliveryOutcome outcome) {
        return outcome.status() == DeliveryStatus.NOT_DELIVERED;
    }
}
