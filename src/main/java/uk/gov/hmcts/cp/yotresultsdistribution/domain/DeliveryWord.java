package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.util.List;
import java.util.Locale;

/**
 * What a run line says about one sink, as the bounded {@code delivered_log} /
 * {@code delivered_email} value.
 *
 * <p>Four words, and each of them is a different operational fact. {@code ok} and {@code failed}
 * are about a sink that was asked. {@code skipped} is <em>nobody asked</em> - the output is on this
 * deployment and this invocation did not want it, which only the command can produce because the
 * 07:00 run asks every sink there is. {@code disabled} is <em>nobody could</em> - there is no such
 * sink on this context at all, so no decision was taken by anybody.
 *
 * <p><strong>One function, two callers.</strong> The job and the command write the same line about
 * the same two fields, and they wrote it from a copy each until review gate 6: the job called an
 * absent e-mail sink {@code disabled} and the command called the same absent sink {@code skipped},
 * which is the divergence a shared word makes impossible rather than merely unlikely. A dashboard
 * filtered on these values and an operator reading a terminal have to partition a morning the same
 * way, and two folds are two partitions waiting to happen.
 */
public enum DeliveryWord {

    /** The sink was asked and it took the report. */
    OK,

    /** The sink was asked and it did not, wholly or in part; the rest is a resend. */
    FAILED,

    /** The sink is on this context and this invocation chose not to ask it. */
    SKIPPED,

    /** There is no sink of this name here at all, so nothing was decided by anybody. */
    DISABLED;

    /**
     * The word for one sink, from what this context holds, what was asked and what came back.
     *
     * @param sink          which sink the field is about
     * @param delivered     one outcome per sink asked, in the order they were asked
     * @param onThisContext whether a sink of that name exists on this context at all
     * @param asked         whether this invocation asked it
     * @return the one word the line carries
     */
    public static DeliveryWord forSink(final ReportSinkName sink,
            final List<DeliveryOutcome> delivered, final boolean onThisContext,
            final boolean asked) {

        final DeliveryWord said;
        if (onThisContext && asked) {
            said = delivered.stream()
                    .filter(outcome -> outcome.sink() == sink)
                    .anyMatch(outcome -> outcome.status() == DeliveryStatus.DELIVERED)
                    ? OK : FAILED;
        } else if (onThisContext) {
            said = SKIPPED;
        } else {
            said = DISABLED;
        }
        return said;
    }

    /**
     * The word one sink's field carries, written the way a run line writes it.
     *
     * <p>The two callers had {@code forSink(...).said()} each, which is the same two steps spelled
     * twice: one line's worth of duplication, and the one line on which the job and the command
     * could come to render the same fact differently. This is {@link #forSink} and {@link #said}
     * in one call, so neither caller holds either half.
     *
     * @param sink          which sink the field is about
     * @param delivered     one outcome per sink asked, in the order they were asked
     * @param onThisContext whether a sink of that name exists on this context at all
     * @param asked         whether this invocation asked it
     * @return the one word the line carries, lower case
     */
    public static String said(final ReportSinkName sink, final List<DeliveryOutcome> delivered,
            final boolean onThisContext, final boolean asked) {
        return forSink(sink, delivered, onThisContext, asked).said();
    }

    /**
     * How the word is written, which is the rendering every bounded label here is published under.
     *
     * @return the lower-case word
     */
    public String said() {
        return name().toLowerCase(Locale.ROOT);
    }
}
