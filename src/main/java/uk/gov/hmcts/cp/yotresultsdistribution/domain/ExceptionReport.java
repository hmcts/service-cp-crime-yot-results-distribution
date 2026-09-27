package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What one run of the exception report found.
 *
 * <p>Read-only by construction: it is composed from the repositories and nothing about it is ever
 * written back (FR-014). The {@code runId} is the correlation the caller opened and passed in
 * rather than something this type reads for itself, which is what keeps the application layer free
 * of the MDC and makes the correlation true on the command path as well as on the scheduled one.
 *
 * <p><strong>The counts are stated and the recurring kinds are capped.</strong> One very bad night
 * can produce more exceptions than any output should write - the log sink writes an event each - so
 * a report carries at most {@code yotresultsdistribution.report.max-entries} of the two <em>late</em> kinds,
 * oldest first, and says how many it dropped. The three failure kinds are carried whole whatever
 * their count: they are read over a half-open window no later run reads again, so a failure the cap
 * dropped would be a failure no report ever states. The counts are therefore <em>not</em> derived
 * from {@code entries}: they are what the reads found, whole, because a count that shrank with the
 * cap would make the worst morning of the year read as a quieter one.
 *
 * @param runId      the correlation the caller opened, the same value {@code RunCorrelation} put in
 *                   the MDC
 * @param window     what was asked for
 * @param snapshotAt when the reads were taken, which is not when the events were written
 * @param entries    the exceptions this report carries, oldest first, across all five kinds
 * @param truncated  how many <em>late</em> entries the reads found and the cap dropped - never a
 *                   failure, which the cap cannot drop; nought on every ordinary morning
 * @param counts     how many of each kind the reads found, <strong>before</strong> the cap
 */
public record ExceptionReport(
        String runId,
        ReportWindow window,
        Instant snapshotAt,
        List<ExceptionEntry> entries,
        int truncated,
        Map<ExceptionKind, Integer> counts) {

    /**
     * Freezes the entries and the counts, zero-fills the counts, and refuses either as absent.
     *
     * <p>A null is not an empty morning. Read as one it would produce five zero counts and no
     * entries, which is precisely the report a quiet night produces, so a composition that lost its
     * list would announce that nothing was wrong. The rest of this model refuses a null rather than
     * interpreting one, and this is the place it would cost the most.
     *
     * <p>The counts are <strong>zero-filled</strong> here rather than at every reader, so the
     * summary event always carries five numbers and an empty morning is distinguishable from a
     * morning the report did not run (FR-012). A kind omitted because it had nothing is a kind a
     * dashboard reads as absent, which is the same silence this service exists to end.
     *
     * <p>A negative {@code truncated} is refused too: it is a count of what was dropped, and a
     * report claiming to have dropped less than nothing is a composition that has gone wrong
     * somewhere an output would report as a number.
     */
    public ExceptionReport {
        entries = List.copyOf(Objects.requireNonNull(entries,
                "a report states what the reads found, and an absent list is not an empty morning"));
        counts = zeroFilled(Objects.requireNonNull(counts,
                "a report states how many of each kind there were, zero included"));
        if (truncated < 0) {
            throw new IllegalArgumentException(
                    "a report drops a number of exceptions it cannot drop fewer than none of");
        }
    }

    /**
     * A report the cap did not reach, whose counts are its own entries'.
     *
     * <p>The ordinary morning, and the shape every caller that is not applying a cap wants: the
     * counts cannot disagree with the list because they are taken from it.
     *
     * @param runId      the correlation the caller opened
     * @param window     what was asked for
     * @param snapshotAt when the reads were taken
     * @param entries    every exception found, oldest first
     * @return the report, carrying all of them and having dropped none
     */
    public static ExceptionReport whole(final String runId, final ReportWindow window,
            final Instant snapshotAt, final List<ExceptionEntry> entries) {
        return new ExceptionReport(runId, window, snapshotAt, entries, 0, countsOf(entries));
    }

    /**
     * How many of each kind a list of entries holds, zero-filled.
     *
     * @param entries the entries to count
     * @return one count per kind, including the kinds that have none
     */
    public static Map<ExceptionKind, Integer> countsOf(final List<ExceptionEntry> entries) {
        final Map<ExceptionKind, Integer> counted = new EnumMap<>(ExceptionKind.class);
        for (final ExceptionEntry entry : entries) {
            counted.merge(entry.kind(), 1, Integer::sum);
        }
        return counted;
    }

    /**
     * One count per kind, <strong>zero-filled</strong>, of what the reads found before the cap.
     *
     * @return one count per kind, including the kinds that have none
     */
    @Override
    public Map<ExceptionKind, Integer> counts() {
        return counts;
    }

    /**
     * Fills in the kinds the caller did not mention, and freezes the map.
     *
     * @param counted what the caller stated
     * @return the same counts with every absent kind at nought
     */
    private static Map<ExceptionKind, Integer> zeroFilled(
            final Map<ExceptionKind, Integer> counted) {
        final Map<ExceptionKind, Integer> filled = new EnumMap<>(ExceptionKind.class);
        for (final ExceptionKind kind : ExceptionKind.values()) {
            filled.put(kind, counted.getOrDefault(kind, 0));
        }
        return Map.copyOf(filled);
    }
}
