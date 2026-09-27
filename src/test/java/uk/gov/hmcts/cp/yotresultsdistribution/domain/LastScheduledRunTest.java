package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The one most-recent-occurrence computation, which this increment needs twice.
 *
 * <p>The generation cron answers the never-batched BATCH_LATE rule and the report cron answers
 * where a scheduled run's window opens, so the cron and the zone are arguments here rather than
 * settings the class binds. Every case reads a wall-clock time in {@code Europe/London} and
 * compares the instant that time really is, which is the only way a case about a schedule in a
 * zone that changes offset twice a year says anything.
 *
 * <p>Soft assertions, so a case reports every occurrence it asked about rather than stopping at
 * the first one that disagreed - which is what makes a suite about a schedule readable when the
 * schedule changes.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the most recent occurrence of a schedule")
class LastScheduledRunTest {

    private static final ZoneId COURTS = ZoneId.of("Europe/London");

    private static final String COURTS_ZONE = "Europe/London";

    /** The nightly generation run: 18:00 on a weekday, in the courts' own zone. */
    private static final String GENERATION_CRON = "0 0 18 * * MON-FRI";

    /** The morning report: 07:00 on a weekday, read in the same zone. */
    private static final String REPORT_CRON = "0 0 7 * * MON-FRI";

    /** The same hour every day of the week, which is what makes the weekend rule visible. */
    private static final String EVERY_MORNING_CRON = "0 0 7 * * *";

    /**
     * What every read here is made through, so a case reports the property it is about.
     *
     * <p>The computation is pure and throws only where a cron or a zone cannot be read, which no
     * case below hands it. Made through {@code assertThatCode}, a refusal is recorded as a failure
     * of its own rather than stopping the case at the arrangement - so a case still says which
     * occurrence it expected, and a soft-assertion suite reports every property in one run.
     */
    private static final String SEAM =
            "these occurrences are computed, not caught: this read answered rather than refused";

    @InjectSoftAssertions
    private SoftAssertions softly;

    @Test
    void the_most_recent_weekday_occurrence_before_an_instant_is_answered() {
        final Instant wednesdayEvening = london(2026, 9, 16, 20, 0);

        softly.assertThat(before(GENERATION_CRON, COURTS_ZONE, wednesdayEvening))
                .as("two hours after the night's generation run, the most recent one is that "
                        + "evening's and not the day before's")
                .isEqualTo(london(2026, 9, 16, 18, 0));
    }

    @Test
    void a_bst_to_gmt_boundary_does_not_move_the_wall_clock_time() {
        final Instant mondayEvening = london(2026, 10, 26, 20, 0);
        final Instant mondayMorning = london(2026, 10, 26, 9, 0);

        softly.assertThat(before(GENERATION_CRON, COURTS_ZONE, mondayEvening))
                .as("the Monday after the clocks went back: 18:00 is a GMT 18:00, which is "
                        + "18:00Z, and the requirement is written in wall clock")
                .isEqualTo(Instant.parse("2026-10-26T18:00:00Z"));
        softly.assertThat(before(GENERATION_CRON, COURTS_ZONE, mondayMorning))
                .as("and the run before it is the Friday's, which was a BST 18:00 and so 17:00Z "
                        + "- the same wall-clock time at a different offset")
                .isEqualTo(Instant.parse("2026-10-23T17:00:00Z"));
    }

    @Test
    void a_monday_morning_looks_back_to_fridays_run() {
        final Instant mondayBeforeTheRun = london(2026, 9, 14, 6, 30);

        softly.assertThat(before(REPORT_CRON, COURTS_ZONE, mondayBeforeTheRun))
                .as("a weekday schedule has no Saturday and no Sunday occurrence, so the run "
                        + "before a Monday morning is the Friday's and the weekend is inside the "
                        + "window rather than outside every window")
                .isEqualTo(london(2026, 9, 11, 7, 0));
        softly.assertThat(before(EVERY_MORNING_CRON, COURTS_ZONE, mondayBeforeTheRun))
                .as("and the same instant under a schedule that does fire at weekends answers "
                        + "the Sunday, which is what makes the case about the cron and not about "
                        + "the arithmetic")
                .isEqualTo(london(2026, 9, 13, 7, 0));
    }

    @Test
    void a_register_recorded_after_the_last_run_is_not_late() {
        final Instant tuesdayMorning = london(2026, 9, 15, 7, 0);
        final Instant lastRun = before(GENERATION_CRON, COURTS_ZONE, tuesdayMorning);

        softly.assertThat(lastRun)
                .as("the cut-off the never-batched rule is read against is the run that should "
                        + "have picked the register up")
                .isEqualTo(london(2026, 9, 14, 18, 0));
        softly.assertThat(london(2026, 9, 14, 19, 0))
                .as("a register recorded after that run has not been passed over by anything yet")
                .isAfter(lastRun);
        softly.assertThat(london(2026, 9, 14, 17, 0))
                .as("and one recorded before it was there to be batched and was not")
                .isBefore(lastRun);
    }

    @Test
    void an_occurrence_at_the_instant_itself_is_answered_by_at_or_before_and_not_by_before() {
        final Instant onTheHour = london(2026, 9, 15, 7, 0);

        softly.assertThat(atOrBefore(REPORT_CRON, COURTS_ZONE, onTheHour))
                .as("a run handed its trigger on the very instant it was due is asking about its "
                        + "own occurrence, so at-or-before answers that instant rather than "
                        + "stepping a whole period back and reporting a period twice")
                .isEqualTo(onTheHour);
        softly.assertThat(before(REPORT_CRON, COURTS_ZONE, onTheHour))
                .as("and strictly-before still means strictly before, which is the question a "
                        + "bare command asks at some moment between two runs")
                .isEqualTo(london(2026, 9, 14, 7, 0));
        softly.assertThat(atOrBefore(REPORT_CRON, COURTS_ZONE, london(2026, 9, 15, 9, 0)))
                .as("away from an occurrence the two answer alike, which is what makes the "
                        + "difference between them exactly the boundary and nothing else")
                .isEqualTo(london(2026, 9, 15, 7, 0));
    }

    @Test
    void the_cron_and_the_zone_are_arguments_not_a_bound_setting() {
        final Instant wednesdayEvening = london(2026, 9, 16, 20, 0);

        softly.assertThat(before(GENERATION_CRON, COURTS_ZONE, wednesdayEvening))
                .as("the generation schedule's own answer")
                .isEqualTo(london(2026, 9, 16, 18, 0));
        softly.assertThat(before(REPORT_CRON, COURTS_ZONE, wednesdayEvening))
                .as("and the report's, from the same instant: one computation, two schedules, "
                        + "because the class binds neither")
                .isEqualTo(london(2026, 9, 16, 7, 0));
        softly.assertThat(before(GENERATION_CRON, "UTC", wednesdayEvening))
                .as("and the zone is an argument too - the same cron read in UTC is an hour "
                        + "later in September than the same cron read in London")
                .isEqualTo(Instant.parse("2026-09-16T18:00:00Z"));
    }

    // --- the computation, made so that a seam's refusal is recorded rather than thrown ----------

    private Instant before(final String cron, final String zone, final Instant instant) {
        return answered(() -> LastScheduledRun.before(cron, zone, instant));
    }

    private Instant atOrBefore(final String cron, final String zone, final Instant instant) {
        return answered(() -> LastScheduledRun.atOrBefore(cron, zone, instant));
    }

    private Instant answered(final Supplier<Instant> computation) {
        final AtomicReference<Instant> answer = new AtomicReference<>(Instant.EPOCH);
        softly.assertThatCode(() -> answer.set(computation.get()))
                .as(SEAM)
                .doesNotThrowAnyException();
        return answer.get();
    }

    private static Instant london(final int year, final int month, final int day, final int hour,
            final int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, COURTS).toInstant();
    }
}
