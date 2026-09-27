package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * The five things the exception report says can be wrong, and the one it says was put right.
 *
 * <p>Closed, and the {@code kind} field of every event, the {@code kind} column of the CSV and the
 * {@code kind} label of {@code yotresultsdistribution_exceptions_reported_total}. A seventh kind is a spec
 * change and not an addition: the report's whole claim is that these are what a morning can find.
 * {@link #BATCH_RELEASED} was the sixth, added by increment 004 against FR-019 and the only one
 * of them that is informational rather than a thing to act on.
 *
 * <p>The two intake kinds are disjoint by construction - FAILED is terminal and RECEIVED and
 * RETRYING are not - which is what makes a request that was late and has since failed appear once,
 * as {@link #REQUEST_FAILED}.
 */
public enum ExceptionKind {

    /** A request that reached FAILED inside the window; the intake half's parked work. */
    REQUEST_FAILED,

    /** A request still RECEIVED or RETRYING past the intake threshold, whenever it arrived. */
    REQUEST_LATE,

    /** A batch past a stage limit, or a recorded register the last scheduled run left unbatched. */
    BATCH_LATE,

    /** A batch that reached its own terminal failure inside the window. */
    BATCH_FAILED,

    /** A recipient's e-mail that was refused or never answered, inside the window. */
    NOTIFICATION_FAILED,

    /**
     * A batch a run gave up on and gave the registers back from, inside the window.
     *
     * <p><strong>The one informational kind, and the only one that says something was put
     * right.</strong> It is a FAILED batch like {@link #BATCH_FAILED}, read by the same statement
     * over the same window, and the reason on its row is what tells them apart. What makes it
     * different is what happened next: the same run that failed it released its registers, and
     * they were batched and rendered that night, so the court centre has its document and nobody
     * is owed anything. It is reported because support should know a court centre needed two
     * attempts; reporting it as a failure would send somebody after a document that exists, and
     * would make a genuinely refused render one entry harder to see on the same morning
     * (FR-019).
     */
    BATCH_RELEASED;

    /**
     * Whether a run that did not report this kind would be asked about it again.
     *
     * <p>The two late kinds are read against a cut-off rather than a window, so whatever is still
     * late at the next run is read by it: an entry left out of one morning's report is in the next
     * morning's, older. The three failure kinds are read over a half-open window aligned to the
     * schedule, so each row falls in exactly one run's window and no run reads that window again -
     * a failure one report leaves out is a failure no report ever states.
     *
     * <p>That difference is what the entry cap is allowed to bound, and it is a switch expression
     * so that a sixth kind cannot be added without deciding which of the two it is.
     *
     * @return whether the next run would find it again
     */
    public boolean recursEveryRun() {
        return switch (this) {
            case REQUEST_LATE, BATCH_LATE -> true;
            case REQUEST_FAILED, BATCH_FAILED, NOTIFICATION_FAILED, BATCH_RELEASED -> false;
        };
    }
}
