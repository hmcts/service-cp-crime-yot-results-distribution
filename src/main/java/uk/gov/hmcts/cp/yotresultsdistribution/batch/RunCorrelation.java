package uk.gov.hmcts.cp.yotresultsdistribution.batch;

import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * The correlation a scheduled unit of work has, in place of the two a delivery has.
 *
 * <p>Constitution Principle VII asks that every line about processing carry the correlation of the
 * unit of work it belongs to. For a delivery that is {@code requestId} and {@code hearingId}. A run
 * has neither and cannot: it is one unit of work across many hearings and many batches, and those
 * identifiers belong to the deliveries that recorded the registers, not to the night that renders
 * them. Before this the lines a night writes carried no correlation at all, so a night could not be
 * pulled out of the estate's index as one thing. On an evening where a run is releasing batches
 * from earlier nights as well as assembling tonight's, that is the difference between reading a run
 * and reading a haystack.
 *
 * <p><strong>Nesting is the whole reason this is a type rather than two calls.</strong> A unit of
 * work reached from inside another is part of it and must carry its id; the same unit fired on a
 * schedule of its own is a unit of work in its own right and needs one of its own. That is not
 * hypothetical here: {@code StaleBatchReleaser} opens a correlation of its own and is called from
 * inside {@code RegisterGenerationJob.run()}, which has already opened one, so the pass adopts the
 * run's id rather than minting a second for the same night's work. The work therefore adopts an
 * ambient correlation where there is one, and only the call that put one there takes it away again.
 *
 * <p>The removal matters as much as the minting, which is why the work is handed in rather than the
 * scope handed out: the scheduler's threads are pooled and reused, so an id left behind would be
 * inherited by the next run on that thread and by anything else that thread writes - and that is
 * worse than no correlation at all, because it reads as a true one. There is no way to call this
 * and forget the {@code finally}.
 */
public final class RunCorrelation {

    /**
     * The MDC key, camel-cased like the delivery path's four rather than snake-cased like the run
     * line's fields: this is a correlation slot beside {@code requestId} and {@code hearingId}, and
     * the same index filters read it.
     */
    /* default */ static final String KEY = "runId";

    private RunCorrelation() {
        // The key, the scope that mints it and the reader that names it on one line.
    }

    /**
     * Runs work under a correlation, adopting an ambient one where one is already set.
     *
     * @param work the unit of work
     * @param <T>  what it answers
     * @return whatever the work answered
     */
    public static <T> T under(final Supplier<T> work) {
        final boolean owned = MDC.get(KEY) == null;
        if (owned) {
            MDC.put(KEY, UUID.randomUUID().toString());
        }
        try {
            return work.get();
        } finally {
            if (owned) {
                MDC.remove(KEY);
            }
        }
    }

    /**
     * Runs work that answers nothing under a correlation.
     *
     * @param work the unit of work
     */
    public static void under(final Runnable work) {
        under(() -> {
            work.run();
            return null;
        });
    }

    /**
     * Runs work under a correlation the caller has already minted and handed out.
     *
     * <p>A run reached through HTTP answers its caller a run id <em>before</em> the work begins -
     * ids before calls - so the id exists before there is anything to put it on. That is the one
     * case the minting overload cannot serve: an id minted here would not be the id the caller was
     * given, and a run that cannot be found by the id it was accepted under is a run nobody can
     * read. The adoption rule and the removal rule are otherwise the minting overload's exactly:
     * an ambient correlation wins, because work reached from inside another unit of work is part
     * of it, and only the call that put an id there takes it away again.
     *
     * @param runId the id the caller was answered with
     * @param work  the unit of work
     */
    public static void under(final String runId, final Runnable work) {
        final boolean owned = MDC.get(KEY) == null;
        if (owned) {
            MDC.put(KEY, runId);
        }
        try {
            work.run();
        } finally {
            if (owned) {
                MDC.remove(KEY);
            }
        }
    }

    /**
     * The correlation this unit of work is running under, for the one line that names it as a field.
     *
     * @return the id, or {@code null} outside any correlation
     */
    public static String current() {
        return MDC.get(KEY);
    }
}
