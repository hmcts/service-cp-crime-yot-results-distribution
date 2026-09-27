package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.throwable;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationFailedException;

/**
 * The register's dates, with three catalogued defects fixed and nothing else moved.
 *
 * <p>Every expectation attributed to the legacy below was taken by <em>running</em> the vendored
 * {@code NowsHelper/service/DateService.js} under {@code TZ=Europe/London}, not by reading what it
 * ought to do. The three cases the register's fix specifications name — C10, C12 and C13 — assert
 * the <strong>fixed</strong> answer and therefore fail against that oracle, which is the point of
 * them; each carries the legacy's answer alongside so a reviewer can see the size of the change
 * without leaving the file.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> rows C10,
 *     C12 and C13
 */
@DisplayName("Dates")
class DatesTest {

    private final Dates dates = new Dates();

    @Nested
    @DisplayName("dateTime — the instant a value records (C10)")
    class DateTime {

        /**
         * C10. The legacy formats a {@code Europe/London} wall clock and appends a literal
         * {@code Z}: {@code DateService.getLocalDateTime('2020-06-01T10:00:00Z')} answers
         * {@code '2020-06-01T11:00:00Z'}, an hour that never happened at that instant, and that is
         * the value {@code registerDate} carries into progression and onto the printed register for
         * every hearing shared between late March and late October.
         */
        @Test
        @DisplayName("carries a summer share as the instant it is, never an hour later")
        void shared_time_is_never_relabelled() {
            assertThat(dates.dateTime("2020-06-01T10:00:00Z"))
                    .isEqualTo("2020-06-01T10:00:00Z")
                    .isNotEqualTo("2020-06-01T11:00:00Z");
        }

        @Test
        @DisplayName("leaves a winter share alone, where the legacy happens to agree")
        void leaves_a_winter_share_alone() {
            // London is UTC in January, so this is the half of the year the defect hides in.
            assertThat(dates.dateTime("2020-01-20T09:00:00Z")).isEqualTo("2020-01-20T09:00:00Z");
        }

        @Test
        @DisplayName("resolves an offset the producer wrote out to the instant it names")
        void resolves_a_written_offset_to_the_instant() {
            // The same instant as the summer case above, written the other way round. A port that
            // kept the digits and swapped the label would answer 11:00:00Z here and would be the
            // legacy defect wearing a different input.
            assertThat(dates.dateTime("2020-06-01T11:00:00+01:00")).isEqualTo("2020-06-01T10:00:00Z");
        }

        @Test
        @DisplayName("truncates a fractional second rather than rounding it")
        void truncates_a_fractional_second() {
            assertThat(dates.dateTime("2021-03-11T22:18:24.506Z")).isEqualTo("2021-03-11T22:18:24Z");
        }

        @Test
        @DisplayName("reads a bare day as its own midnight")
        void reads_a_bare_day_as_its_own_midnight() {
            assertThat(dates.dateTime("2020-01-20")).isEqualTo("2020-01-20T00:00:00Z");
        }

        @Test
        @DisplayName("carries a date-time with no offset exactly as it was recorded")
        void carries_an_offsetless_date_time_as_recorded() {
            // Nothing in the value says which hour of the day it was, so there is nothing to
            // convert. The legacy reads it as London-local and then labels it Z, which is the same
            // digits in winter and a different instant in summer; carrying it is the fix's rule —
            // never re-labelled through a timezone conversion — applied to the one input shape
            // where the two answers coincide by construction.
            assertThat(dates.dateTime("2020-06-15T09:30:00")).isEqualTo("2020-06-15T09:30:00Z");
        }

        @ParameterizedTest
        @ValueSource(strings = {"not a date", "2020-13-45", "", "22/07/2022"})
        @DisplayName("refuses a value it cannot read rather than shipping the literal Invalid date")
        void refuses_a_value_it_cannot_read(final String unreadable) {
            // moment renders an unreadable value as the string "Invalid date" and the legacy appends
            // its Z, so `Invalid dateZ` reaches progression inside a field the frozen contract types
            // as a date-time. One classified refusal is the fixed answer.
            assertThatThrownBy(() -> dates.dateTime(unreadable))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("refuses an absent shared time rather than stamping the wall clock")
        void refuses_an_absent_value_rather_than_stamping_the_clock() {
            // `moment.tz(undefined, zone)` is now, so the legacy files a register dated whenever the
            // function happened to run. `sharedTime` is a required field of the inbound contract;
            // an absent one is a contract failure, not a value to invent.
            assertThatThrownBy(() -> dates.dateTime(null))
                    .isInstanceOf(TransformationFailedException.class);
        }
    }

    @Nested
    @DisplayName("subscriptionDay — the reference-data on= day (C12)")
    class SubscriptionDay {

        /**
         * C12, and the case the fix specification names. A hearing shared at 23:30 UTC on 1 June is
         * 00:30 BST on 2 June, so the legacy's relabelled {@code registerDate} of
         * {@code 2020-06-02T00:30:00Z} makes {@code ReferenceDataService.js:38} ask reference data
         * for the subscriptions in force on <strong>2 June</strong> — a set that may add, drop or
         * re-key recipients relative to the day the results were actually shared.
         */
        @Test
        @DisplayName("uses the day a share happened, not the day it looked like locally")
        void bst_evening_share_uses_the_share_day() {
            assertThat(dates.subscriptionDay("2020-06-01T23:30:00Z"))
                    .isEqualTo(LocalDate.of(2020, 6, 1))
                    .isNotEqualTo(LocalDate.of(2020, 6, 2));
        }

        @Test
        @DisplayName("uses the same day for a daytime share, where the legacy already agreed")
        void uses_the_same_day_for_a_daytime_share() {
            assertThat(dates.subscriptionDay("2020-06-01T10:00:00Z"))
                    .isEqualTo(LocalDate.of(2020, 6, 1));
        }

        @Test
        @DisplayName("does not shift a winter evening share either, in the other direction")
        void does_not_shift_a_winter_evening_share() {
            // London is UTC in January, so the legacy answers 20 January here too. The case is here
            // to prove the fix is a change of rule and not a constant subtracted from every day.
            assertThat(dates.subscriptionDay("2020-01-20T23:30:00Z"))
                    .isEqualTo(LocalDate.of(2020, 1, 20));
        }

        @Test
        @DisplayName("refuses a shared time it cannot read")
        void refuses_a_shared_time_it_cannot_read() {
            assertThatThrownBy(() -> dates.subscriptionDay("not a date"))
                    .isInstanceOf(TransformationFailedException.class);
        }
    }

    @Nested
    @DisplayName("orderingKey — ISO parsing, and one refusal (C13)")
    class OrderingKey {

        /**
         * C13, both halves. The legacy parses the producer's {@code YYYY-MM-DD} ordered dates with
         * {@code DateService.parse}'s default {@code 'YYYY/MM/DD'} format, and the
         * {@code RegisterFragmentService.js:40-43} catch that is supposed to report a failure calls
         * {@code this.context.log} in an arrow-function module export where {@code this} is
         * unbound — so the catch throws a {@code TypeError} of its own, the original cause is lost,
         * and {@code SetCourtRegister} swallows the lot into {@code Success: true}. Nothing in the
         * legacy repository executes that catch in either direction.
         */
        @Test
        @DisplayName("reads an ISO ordered date, and classifies one it cannot read")
        void iso_dates_parse_and_failures_are_classified() {
            assertThat(dates.orderingKey("2020-10-29")).isEqualTo(LocalDate.of(2020, 10, 29));

            assertThatThrownBy(() -> dates.orderingKey("not a date"))
                    .asInstanceOf(throwable(TransformationFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.NON_TRANSIENT);
                        assertThat(failure.reason()).isEqualTo(ReasonCode.TRANSFORMATION_FAILED);
                        assertThat(failure).hasMessage("Invalid date format");
                    });
        }

        @Test
        @DisplayName("orders an ordered date recorded as a timestamp by its day")
        void orders_a_timestamp_by_its_day() {
            assertThat(dates.orderingKey("2021-03-11T22:18:24.506Z"))
                    .isEqualTo(LocalDate.of(2021, 3, 11));
        }

        @Test
        @DisplayName("refuses the day-first value the legacy format silently mis-reads")
        void refuses_the_day_first_value_the_legacy_misreads() {
            // Non-strict moment walks the tokens of 'YYYY/MM/DD' and gives the year up to four
            // digits, so `20-01-2020` reads as year 20, month 1, day 20 and the two-digit-year rule
            // makes it 20 January 2020 — a date nobody wrote, arrived at by accident, and used to
            // order the register. Under ISO parsing it is a value this service will not guess at.
            assertThatThrownBy(() -> dates.orderingKey("20-01-2020"))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"2020", "2020-06", "20200120", "2020/01/20", "2020-13-45", ""})
        @DisplayName("refuses every shape the legacy token walk quietly completed")
        void refuses_every_shape_the_token_walk_completed(final String value) {
            // moment defaults a missing month or day to 1 and skips whatever separates the numbers,
            // so all of these produce an ordering key today. A defaulted day is not a date the court
            // sent, and ordering a register by one is how a hearing acquires the wrong hearing date.
            assertThatThrownBy(() -> dates.orderingKey(value))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("refuses an absent ordered date rather than ordering it as now")
        void refuses_an_absent_ordered_date() {
            assertThatThrownBy(() -> dates.orderingKey(null))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("names the shape that was wrong and never the value that was in it")
        void names_the_shape_and_never_the_value() {
            // Principle VII: an ordered date is payload content and every defendant on this register
            // is a child. The refusal has to be legible in a log index without carrying the payload
            // into one, and it must not be able to fail a second time on its way out — which is the
            // half of C13 that has no observable value to assert, only this shape.
            assertThatThrownBy(() -> dates.orderingKey("1999-12-31T00:00:00-EARLY"))
                    .asInstanceOf(throwable(TransformationFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure).hasMessage("Invalid date format");
                        assertThat(failure.getMessage()).doesNotContain("1999");
                        assertThat(failure.getCause()).isNull();
                    });
        }
    }

    @Nested
    @DisplayName("localDate — the London day a court recorded")
    class LondonDay {

        @Test
        @DisplayName("gives the London day of a mid-morning instant")
        void gives_the_london_day_of_a_mid_morning_instant() {
            assertThat(dates.localDate("2020-06-19T09:00:00.000Z")).isEqualTo("2020-06-19");
        }

        @Test
        @DisplayName("gives the day of a bare day unchanged")
        void gives_the_day_of_a_bare_day_unchanged() {
            assertThat(dates.localDate("2020-01-20")).isEqualTo("2020-01-20");
        }

        /**
         * The one place the register keeps a London day on purpose, contrasted with the one place
         * C12 takes it away. Both values below come from the same instant; they differ because they
         * answer different questions — which sitting day a court sat on, and which day's
         * subscription set was in force when the results were shared.
         */
        @Test
        @DisplayName("stays the London day where the subscription day is the share day")
        void stays_the_london_day_where_the_subscription_day_is_the_share_day() {
            assertThat(dates.localDate("2020-06-01T23:30:00Z")).isEqualTo("2020-06-02");
            assertThat(dates.subscriptionDay("2020-06-01T23:30:00Z"))
                    .isEqualTo(LocalDate.of(2020, 6, 1));
        }

        @Test
        @DisplayName("refuses a sitting day it cannot read")
        void refuses_a_sitting_day_it_cannot_read() {
            assertThatThrownBy(() -> dates.localDate("not a date"))
                    .isInstanceOf(TransformationFailedException.class);
        }
    }

    @Nested
    @DisplayName("registerDay — the day the register is filed under (C10, C11, C12)")
    class RegisterDay {

        @Test
        @DisplayName("gives the UTC day of the register instant")
        void gives_the_utc_day_of_the_register_instant() {
            assertThat(dates.registerDay("2020-06-01T10:00:00Z")).isEqualTo("2020-06-01");
        }

        @Test
        @DisplayName("files an evening share under the day it was shared, not London's next one")
        void files_an_evening_share_under_the_day_it_was_shared() {
            // The three values C10's row says move together for a BST evening share: the register
            // date, the file-name day and the subscription day. Reading the file-name day as a
            // London calendar day — which localDate does, correctly, for the sitting-day match it
            // exists for — leaves the register filed under a day the other two disagree with.
            assertThat(dates.registerDay("2020-06-01T23:30:00Z")).isEqualTo("2020-06-01");
            assertThat(dates.localDate("2020-06-01T23:30:00Z")).isEqualTo("2020-06-02");
        }

        @Test
        @DisplayName("answers the same day the subscription set was read for")
        void answers_the_same_day_the_subscription_set_was_read_for() {
            assertThat(dates.registerDay("2020-06-01T23:30:00Z"))
                    .isEqualTo(dates.subscriptionDay("2020-06-01T23:30:00Z").toString());
        }

        @Test
        @DisplayName("refuses a register date it cannot read")
        void refuses_a_register_date_it_cannot_read() {
            assertThatThrownBy(() -> dates.registerDay("not a date"))
                    .isInstanceOf(TransformationFailedException.class);
        }
    }

    @Nested
    @DisplayName("isGreater — the ordered-date comparison the sorts perform")
    class IsGreater {

        @Test
        @DisplayName("answers false when the first date is the earlier one")
        void answers_false_when_the_first_date_is_earlier() {
            assertThat(dates.isGreater("2020-01-10", "2020-01-19")).isFalse();
        }

        @Test
        @DisplayName("answers true when the first date is the later one")
        void answers_true_when_the_first_date_is_later() {
            assertThat(dates.isGreater("2020-01-29", "2020-01-19")).isTrue();
        }

        @Test
        @DisplayName("answers false for the same day, which is not greater than itself")
        void answers_false_for_the_same_day() {
            assertThat(dates.isGreater("2020-01-19", "2020-01-19")).isFalse();
        }

        @Test
        @DisplayName("refuses rather than ordering against a date it cannot read")
        void refuses_rather_than_ordering_against_an_unreadable_date() {
            assertThatThrownBy(() -> dates.isGreater("2020-01-19", "not a date"))
                    .isInstanceOf(TransformationFailedException.class);
        }
    }

    /**
     * The day the register says the hearing was held on — {@code getHearingDate}, and the two things
     * the port had to decide about it.
     *
     * <p><strong>Reach.</strong> {@code RegisterFragmentService.js:47} guards on
     * {@code if (hearingObj.hearingDays)} and then calls {@code .find} on it, and inside the
     * predicate reads {@code hearingDay.sittingDay}. Both of those <em>throw</em> for shapes a
     * payload can carry: a truthy value that is not an array has no {@code find}, and reading a
     * property off a {@code null} member is a {@code TypeError}. The exception is swallowed upstream
     * into {@code Success: true}, so the legacy loses the whole hearing's register. Iterating those
     * shapes safely would be the one direction this port must not drift in — it would produce a
     * register the legacy never sends, for a payload the transformation cannot actually read. They
     * are classified transformation failures instead: recorded, parked, and replayable.
     *
     * <p><strong>Determinism — defect fix C35.</strong> The legacy has two legs that answer with
     * {@code moment.tz(undefined, zone)}, which is the wall clock: a hearing that gathered no ordered
     * date at all, and a sitting record carrying no {@code sittingDay} whose absent day formats as
     * today and so matches an ordered date of today. Neither is reproduced — the transformation is
     * pure and has no clock (constitution Principle V) — and neither is silent: they are registered
     * as C35 with the deterministic answers pinned below.
     *
     * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> row C35
     */
    @Nested
    @DisplayName("hearingDate — reach and determinism (C35)")
    class HearingDate {

        /** The ordered date most cases below are dated by. */
        private static final String ORDERED = "2020-01-20";

        @ParameterizedTest
        @ValueSource(strings = {"{\"sittingDay\":\"2020-01-20\"}", "\"2020-01-20\"", "7", "true"})
        @DisplayName("refuses a hearingDays that is truthy and is not a list")
        void refuses_a_hearing_days_that_is_not_a_list(final String hearingDays) {
            // `hearingObj.hearingDays.find` is not a function for any of these, so the legacy throws
            // and the hearing produces no register at all. Iterating them as "no sitting days" would
            // date the register by the ordered date and send it.
            assertThatThrownBy(() -> resolve(ORDERED, "{\"hearingDays\":" + hearingDays + "}"))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("refuses a sitting record that is null rather than reading through it")
        void refuses_a_sitting_record_that_is_null() {
            // `hearingDay.sittingDay` on a null member is a TypeError, and the register is lost.
            assertThatThrownBy(() -> resolve(ORDERED, "{\"hearingDays\":[null]}"))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("reads a sitting record that names no day, which the legacy reads too")
        void reads_a_sitting_record_that_names_no_day() {
            // The control the refusals need. Reading `.sittingDay` off an object that does not
            // carry it is legal JavaScript and answers `undefined`; only `null` and `undefined`
            // members throw. Refusing this shape would lose registers the legacy sends.
            assertThat(resolve(ORDERED, "{\"hearingDays\":[{\"courtRoom\":\"1\"}]}"))
                    .isEqualTo("2020-01-20T00:00:00Z");
        }

        @Test
        @DisplayName("carries the sitting day that falls on the ordered date, with its time")
        void carries_the_sitting_day_falling_on_the_ordered_date() {
            assertThat(resolve(ORDERED,
                    "{\"hearingDays\":[{\"sittingDay\":\"2020-01-20T09:30:00Z\"}]}"))
                    .isEqualTo("2020-01-20T09:30:00Z");
        }

        /**
         * C35, first leg. A hearing whose gathered results name no ordered date reaches
         * {@code dateService.getLocalDateTime(undefined)}, and {@code moment.tz(undefined, zone)} is
         * the current time — so the legacy dates the register by whenever the function app happened
         * to run, and two replays of the same hearing produce two different registers.
         */
        @Test
        @DisplayName("has no hearing date at all where the legacy stamps the wall clock")
        void has_no_hearing_date_where_the_legacy_stamps_the_wall_clock() {
            assertThat(resolve(null, "{\"hearingDays\":[{\"sittingDay\":\"2020-01-20\"}]}")).isNull();
            assertThat(resolve(null, "{\"hearingDays\":[]}")).isNull();
        }

        /**
         * C35, second leg. A sitting record carrying no {@code sittingDay} has
         * {@code getLocalDate(undefined)} formatted as <em>today</em>, so on the day a hearing's
         * results are ordered that record matches — and the hearing date it then carries is
         * {@code getLocalDateTime(undefined)}, the wall clock again. Here the record matches nothing
         * and the ordered date is carried, which is what the register says on every other day.
         */
        @Test
        @DisplayName("a sitting record naming no day matches nothing, even on the ordered day")
        void a_sitting_record_naming_no_day_matches_nothing() {
            final String today =
                    LocalDate.now(java.time.ZoneId.of("Europe/London")).toString();

            assertThat(resolve(today, "{\"hearingDays\":[{}]}"))
                    .isEqualTo(today + "T00:00:00Z");
        }

        @Test
        @DisplayName("has no hearing date when the hearing lists no sitting days at all")
        void has_no_hearing_date_when_the_hearing_lists_none() {
            // `getHearingDate`'s `if` has no `else`, so an absent list answers nothing — unchanged.
            assertThat(resolve(ORDERED, "{\"id\":\"hearing-1\"}")).isNull();
        }

        /**
         * The hearing date for one hearing and one ordered date.
         *
         * @param orderedDate the latest date any gathered result was ordered; may be {@code null}
         * @param hearing     the hearing, as JSON text
         * @return the hearing date the register carries
         */
        private String resolve(final String orderedDate, final String hearing) {
            return HearingDates.resolve(orderedDate,
                    JacksonConfig.contractObjectMapper().readTree(hearing), dates);
        }
    }

    /**
     * The JUnit twins of the legacy {@code DateService} Jest suite.
     *
     * <p>That suite declares eight cases across five functions. Seven are twinned below under their
     * Jest names; the eighth, {@code should return local time}, covers {@code getLocalTime}, which
     * <strong>no court-register call site reaches</strong> — the register carries no hearing start
     * time and has no {@code CourtSessionMapper}. Twinning it would mean writing production code no
     * hearing can reach in order to have something to assert against, which the constitution's TDD
     * principle rejects; the omission is recorded here rather than left to be noticed.
     *
     * <p>Three of the seven are <strong>repointed at a fix</strong> and so disagree with the value
     * the Jest case asserts:
     *
     * <ul>
     *   <li>{@code should return local date time} — C10. The Jest case computes its expectation with
     *       {@code moment-timezone} rather than writing a literal, so what it asserts is
     *       {@code 2020-06-19T10:00:00Z}: the June morning shifted into British Summer Time and
     *       labelled {@code Z}.</li>
     *   <li>{@code should return local date time after formatting date} — the
     *       {@code formatDateAndGetLocalDateTime} re-read, which has no court-register call site
     *       either: no mapper in {@code OutboundCourtRegister} formats a date at all. Rather than
     *       port an unreachable method, the twin pins the absence — a {@code DD/MM/YYYY} value is
     *       one this service refuses.</li>
     *   <li>{@code isGreater} — reached, but through the ordering the register's sorts perform
     *       rather than through a standalone helper the legacy exports.</li>
     * </ul>
     */
    @Nested
    @DisplayName("DateService — legacy Jest twins")
    class LegacyJestTwins {

        /** The instant two of the twinned Jest cases are written around. */
        private static final String JUNE_MORNING = "2020-06-19T09:00:00.000Z";

        @Test
        @DisplayName("it should return the correct date")
        void it_should_return_the_correct_date() {
            // The Jest case builds `${2020}-${10}-${29}` and asserts the parsed year, month and day.
            assertThat(dates.orderingKey("2020-10-29")).isEqualTo(LocalDate.of(2020, 10, 29));
        }

        @Test
        @DisplayName("isGreater should return false when date is newer")
        void is_greater_should_return_false_when_date_is_newer() {
            assertThat(dates.isGreater("2020-01-10", "2020-01-19")).isFalse();
        }

        @Test
        @DisplayName("isGreater should return true when date is older")
        void is_greater_should_return_true_when_date_is_older() {
            assertThat(dates.isGreater("2020-01-29", "2020-01-19")).isTrue();
        }

        @Test
        @DisplayName("should return local date")
        void should_return_local_date() {
            assertThat(dates.localDate(JUNE_MORNING)).isEqualTo("2020-06-19");
        }

        @Test
        @DisplayName("should return local date time — repointed at C10")
        void should_return_local_date_time() {
            assertThat(dates.dateTime(JUNE_MORNING))
                    .isEqualTo("2020-06-19T09:00:00Z")
                    .isNotEqualTo("2020-06-19T10:00:00Z");
        }

        @Test
        @DisplayName("should return local date time when time missing")
        void should_return_local_date_time_when_time_missing() {
            // Unchanged by C10: a bare day carries no hour for the relabelling to move.
            assertThat(dates.dateTime("2020-06-19")).isEqualTo("2020-06-19T00:00:00Z");
        }

        @Test
        @DisplayName("should return local date time after formatting date — not reached, pinned absent")
        void should_return_local_date_time_after_formatting_date() {
            // The Jest case asserts `formatDateAndGetLocalDateTime('22/07/2022')` is
            // '2022-07-22T00:00:00Z'. Nothing on the court-register path calls it, so the port has
            // no day-first re-read at all and the value is one it refuses.
            assertThatThrownBy(() -> dates.dateTime("22/07/2022"))
                    .isInstanceOf(TransformationFailedException.class);
        }
    }
}
