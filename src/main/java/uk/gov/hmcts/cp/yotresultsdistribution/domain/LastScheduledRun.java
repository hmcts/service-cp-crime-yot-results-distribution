package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.scheduling.support.CronExpression;

/**
 * When a given schedule last fired before a given moment.
 *
 * <p>One computation, needed twice by this increment and by two different schedules. The
 * <strong>generation</strong> cron answers the never-batched BATCH_LATE rule - a register the most
 * recent scheduled generation run left where it was is late, and one recorded since it is not - and
 * the <strong>report</strong> cron answers where a scheduled run's window opens, through
 * {@link ReportWindow#sinceLastScheduledRun(String, String, Instant)}. The cron and the zone are
 * arguments rather than settings this class binds, because two callers give it two.
 *
 * <p><strong>It lives in {@code domain/} rather than in {@code batch/}</strong>, and it is worth
 * saying why, because a cron expression looks like scheduling. It is a pure computation over a cron
 * expression, a zone and an instant: it reaches nothing, holds nothing, and decides nothing about
 * when anything runs. {@link ReportWindow} - itself a domain record - is one of its two callers, so
 * a {@code batch} home would make a domain type depend on a batch one.
 *
 * <p>The one type it borrows is Spring's {@code org.springframework.scheduling.support.CronExpression},
 * which is a parser and a pure {@code next(Temporal)} function over the six-field dialect this
 * service's two schedules are already written in. Re-implementing the dialect here so that the
 * domain imported nothing would be a second parser to keep in step with the one the schedules are
 * actually read by - a fact written twice, and the morning the two disagree is the morning a report
 * covers a window nothing ran over.
 */
public final class LastScheduledRun {

    /**
     * How far back a schedule may be searched before it is treated as one that never fires.
     *
     * <p>A year and a day. The two schedules this serves fire on every weekday, so the search ends
     * on its first or second step; the bound exists because a cron that matches nothing - a 30th of
     * February, a day-of-week and day-of-month pair that never coincide - would otherwise be an
     * unbounded walk backwards rather than a refusal.
     */
    private static final int SEARCH_DAYS = 366;

    private LastScheduledRun() {
        // A computation, not a thing.
    }

    /**
     * The most recent occurrence of a cron, in a zone, at or before an instant.
     *
     * <p>The one a scheduled run asks: a run handed its trigger on the very instant it was due is
     * asking about its own occurrence, and {@link #before(String, String, Instant)} would step a
     * whole period back past it.
     *
     * @param cron    the schedule, in Spring's six-field dialect
     * @param zone    the zone the schedule is read in
     * @param instant the moment to look back from, which may itself be an occurrence
     * @return the most recent occurrence at or before it
     */
    public static Instant atOrBefore(final String cron, final String zone, final Instant instant) {
        return lastOccurrence(cron, zone, instant, Boundary.INCLUSIVE);
    }

    /**
     * The most recent occurrence of a cron, in a zone, strictly before an instant.
     *
     * <p>The one a caller with no occurrence of its own asks: an operator typing the bare command
     * at some moment between two runs wants the run that last reported, and the moment asked about
     * is not itself an occurrence.
     *
     * @param cron    the schedule, in Spring's six-field dialect
     * @param zone    the zone the schedule is read in
     * @param instant the moment to look back from
     * @return the most recent occurrence strictly before it
     */
    public static Instant before(final String cron, final String zone, final Instant instant) {
        return lastOccurrence(cron, zone, instant, Boundary.STRICT);
    }

    /**
     * The search the two public answers share, differing only in what they do with the boundary.
     *
     * @param cron     the schedule, in Spring's six-field dialect
     * @param zone     the zone the schedule is read in
     * @param instant  the moment to look back from
     * @param boundary whether an occurrence at that moment is one of the answers
     * @return the most recent occurrence the boundary admits
     */
    private static Instant lastOccurrence(final String cron, final String zone,
            final Instant instant, final Boundary boundary) {
        final CronExpression schedule = CronExpression.parse(cron);
        final ZonedDateTime moment = instant.atZone(ZoneId.of(zone));
        ZonedDateTime latest = null;
        for (int daysBack = 0; daysBack <= SEARCH_DAYS && latest == null; daysBack++) {
            latest = lastOccurrenceOn(schedule, moment.minusDays(daysBack).toLocalDate(), moment,
                    boundary);
        }
        if (latest == null) {
            throw new IllegalArgumentException("the schedule fired at no point in the "
                    + SEARCH_DAYS + " days before the moment asked about");
        }
        return latest.toInstant();
    }

    /**
     * The last time the schedule fired on one local day, before a given moment.
     *
     * <p>Walked forwards from the start of the day rather than searched backwards, because
     * {@code CronExpression} only answers forwards. The day is entered a nanosecond early so that
     * an occurrence at midnight itself is found, and every candidate is checked to be still on the
     * day being asked about - which is what keeps the walk from wandering into the next one across
     * a clock change, where a local day is twenty-three or twenty-five hours long.
     *
     * @param schedule the parsed cron
     * @param day      the local day being searched
     * @param moment   the moment every occurrence must fall before, or at, as the boundary says
     * @param boundary whether an occurrence at the moment itself is admitted
     * @return the last occurrence on that day the boundary admits, or {@code null} where there is
     *     none
     */
    private static ZonedDateTime lastOccurrenceOn(final CronExpression schedule,
            final LocalDate day, final ZonedDateTime moment, final Boundary boundary) {
        ZonedDateTime latest = null;
        ZonedDateTime candidate =
                schedule.next(day.atStartOfDay(moment.getZone()).minusNanos(1));
        while (candidate != null && boundary.admits(candidate, moment)
                && candidate.toLocalDate().equals(day)) {
            latest = candidate;
            candidate = schedule.next(candidate);
        }
        return latest;
    }

    /**
     * What the moment asked about is: a boundary an occurrence may sit on, or one it may not.
     *
     * <p>The whole difference between the two answers this class gives, written once as the
     * comparison itself rather than twice as two nearly identical searches.
     */
    private enum Boundary {

        /** Strictly before: an occurrence at the moment itself is not one of the answers. */
        STRICT {
            @Override
            /* default */ boolean admits(final ZonedDateTime candidate, final ZonedDateTime moment) {
                return candidate.isBefore(moment);
            }
        },

        /** At or before: an occurrence at the moment itself is the answer. */
        INCLUSIVE {
            @Override
            /* default */ boolean admits(final ZonedDateTime candidate, final ZonedDateTime moment) {
                return !candidate.isAfter(moment);
            }
        };

        /**
         * Whether one occurrence is early enough to be an answer.
         *
         * @param candidate the occurrence being considered
         * @param moment    the moment asked about
         * @return whether it counts
         */
        /* default */ abstract boolean admits(ZonedDateTime candidate, ZonedDateTime moment);
    }
}
