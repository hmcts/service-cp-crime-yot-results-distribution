package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterHearing;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterResult;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ResultLevel;
import uk.gov.hmcts.cp.yotresultsdistribution.support.LegacyFixtures;

/**
 * The hearing details printed against one defendant — and the two defects that make them noise.
 *
 * <p>{@code $DF/…/Mappers/Hearing/test/HearingMapper.test.js} is one case, and it is the most
 * misleading case in the corpus: it asserts {@code defendantPresent === true} against a mapper that
 * has never once answered {@code true} in production, and it does so by masking two defects at the
 * same time.
 *
 * <ul>
 *   <li><strong>C8</strong> — {@code find(d => d.defendantId = defendantId)} ({@code :13,22}) is an
 *       assignment, not a comparison. It matches the first element of {@code defendantAttendance}
 *       whatever its id, and writes the sought id over that element's own on the way past. The Jest
 *       fixture's attendance array has exactly one element, and it is the right one, so the wrong
 *       answer and the right answer are the same value.</li>
 *   <li><strong>C9</strong> — the day found is then compared against the fragment's
 *       {@code registerDate}. On the payload that is a date ({@code 2019-02-01}); on a fragment it
 *       is a datetime ({@code 2020-06-01T11:00:00Z}). They are never equal, so
 *       {@code defendantPresent} is {@code false} and {@code defendantAppearanceDetails} absent on
 *       every register the legacy has ever sent. The Jest fixture supplies {@code registerDate} as a
 *       bare date, which is the one shape that makes them equal.</li>
 * </ul>
 *
 * <p>The two are fixed together, as the register requires: the attendance record is selected by
 * equality against the mapped defendant's own ids, and the day is matched against the defendant's
 * latest {@code judicialResult.orderedDate} — the shared kernel's own attendance rule
 * ({@code VocabularyService.getAttendanceInfo:245-274}). That is why this mapper takes the register
 * defendant rather than the fragment: both the ids and the ordered day are on it, and the fragment's
 * {@code registerDate} is no longer part of the answer.
 *
 * <p>The twin therefore runs on the authored base hearing rather than on the legacy's inline data.
 * That hearing carries a two-entry {@code defendantAttendance} with the mapped defendant's record
 * <em>second</em> and someone else's first, and its results are ordered on the day the second entry
 * records — so it answers one way under C8 and C9 and the other way under the fix, in a single case.
 *
 * <p><strong>One thing here is uncatalogued and is deliberately kept.</strong> The legacy computes
 * presence from a day being listed at all ({@code :15}), not from the day's {@code attendanceType}
 * or its {@code isInAttendance} flag — so a defendant recorded {@code NOT_PRESENT} on the register's
 * day is reported present, with appearance details reading "Not present". No C-number covers that,
 * so under Principle I the legacy stands and the case below pins it; it is raised with the C8/C9
 * sign-off rather than fixed in passing.
 */
@DisplayName("HearingMapper")
class HearingMapperTest {

    private static final String YOUTH_DEFENDANT = "6647df67-a065-4d07-90ba-a8daa064ecc4";

    private static final String OTHER_DEFENDANT = "aeb6328d-19d4-49e8-8426-290f096b81dc";

    private static final String ORDERED_DAY = "2020-01-20";

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    @Nested
    @DisplayName("Hearing Mapper > Should return correct values — legacy Jest twin, repointed")
    class LegacyTwin {

        @Test
        @DisplayName("the jurisdiction and the hearing type are copied off the hearing")
        void jurisdiction_and_hearing_type_are_copied() {
            final CourtRegisterHearing hearing = mapBaseHearing();

            assertThat(hearing.jurisdiction()).isEqualTo("MAGISTRATES");
            assertThat(hearing.hearingType()).isEqualTo("Sentence");
        }

        @Test
        @DisplayName("the attending solicitor is the defence organisation on the defendant")
        void the_attending_solicitor_is_the_defence_organisation() {
            assertThat(mapBaseHearing().attendingSolicitorName())
                    .isEqualTo("Harold Benjamin Solicitors");
        }

        @Test
        @DisplayName("a defendant with no associated defence organisation has no solicitor named")
        void a_defendant_with_no_defence_organisation_names_no_solicitor() {
            final CourtRegisterHearing hearing = HearingMapper.map(
                    baseHearing(), defendantWith(ORDERED_DAY, YOUTH_DEFENDANT),
                    mapper.readTree("{\"id\":\"" + YOUTH_DEFENDANT + "\"}"));

            assertThat(hearing.attendingSolicitorName()).isNull();
        }
    }

    @Nested
    @DisplayName("C8 — which attendance record is read")
    class WhichAttendanceRecord {

        @Test
        @DisplayName("attendance is selected for the mapped defendant, not for the first record")
        void attendance_is_selected_for_the_mapped_defendant() {
            final CourtRegisterHearing hearing = mapBaseHearing();

            assertThat(hearing.defendantAppearanceDetails()).isEqualTo("In person");
        }

        @Test
        @DisplayName("an attendance list carrying no record for this defendant reports absence")
        void an_attendance_list_without_this_defendant_reports_absence() {
            final CourtRegisterHearing hearing = HearingMapper.map(
                    baseHearing(), defendantWith(ORDERED_DAY, "no-such-defendant"), anyDefendant());

            assertThat(hearing.defendantPresent()).isFalse();
            assertThat(hearing.defendantAppearanceDetails()).isNull();
        }

        @Test
        @DisplayName("any of the defendant's ids may carry the attendance record")
        void any_of_the_defendants_ids_may_carry_the_record() {
            final CourtRegisterHearing hearing = HearingMapper.map(
                    baseHearing(),
                    defendantWith(ORDERED_DAY, "another-id", YOUTH_DEFENDANT),
                    anyDefendant());

            assertThat(hearing.defendantAppearanceDetails()).isEqualTo("In person");
        }

        @Test
        @DisplayName("the hearing the mapper was handed is not written to")
        void the_hearing_is_not_written_to() {
            final JsonNode hearing = baseHearing();

            HearingMapper.map(hearing, defendantWith(ORDERED_DAY, YOUTH_DEFENDANT), anyDefendant());

            assertThat(hearing.get("defendantAttendance").get(0).get("defendantId").stringValue())
                    .isEqualTo(OTHER_DEFENDANT);
            assertThat(hearing).isEqualTo(baseHearing());
        }
    }

    @Nested
    @DisplayName("C9 — which day is matched")
    class WhichDay {

        @Test
        @DisplayName("defendant present reflects attendance on the ordered day")
        void defendant_present_reflects_attendance_on_the_ordered_day() {
            assertThat(mapBaseHearing().defendantPresent()).isTrue();
        }

        @Test
        @DisplayName("a defendant whose results were ordered on another day was not there")
        void a_defendant_ordered_on_another_day_was_not_present() {
            final CourtRegisterHearing hearing = HearingMapper.map(
                    baseHearing(), defendantWith("2020-02-20", YOUTH_DEFENDANT), anyDefendant());

            assertThat(hearing.defendantPresent()).isFalse();
            assertThat(hearing.defendantAppearanceDetails()).isNull();
        }

        @Test
        @DisplayName("a defendant with no ordered day at all was not there")
        void a_defendant_with_no_ordered_day_was_not_present() {
            final CourtRegisterHearing hearing = HearingMapper.map(
                    baseHearing(), defendantWith(null, YOUTH_DEFENDANT), anyDefendant());

            assertThat(hearing.defendantPresent()).isFalse();
            assertThat(hearing.defendantAppearanceDetails()).isNull();
        }

        @Test
        @DisplayName("attendance on an earlier resulted day counts, not only on the latest one")
        void attendance_on_any_resulted_day_counts() {
            final CourtRegisterHearing hearing = HearingMapper.map(
                    baseHearing(), defendantResultedOn("2020-02-20", ORDERED_DAY), anyDefendant());

            assertThat(hearing.defendantPresent()).isTrue();
            assertThat(hearing.defendantAppearanceDetails()).isEqualTo("In person");
        }
    }

    @Nested
    @DisplayName("how an appearance is described")
    class AppearanceDetails {

        @Test
        @DisplayName("IN_PERSON reads In person")
        void in_person_reads_in_person() {
            assertThat(appearanceOn("IN_PERSON")).isEqualTo("In person");
        }

        @Test
        @DisplayName("BY_VIDEO reads By video link")
        void by_video_reads_by_video_link() {
            assertThat(appearanceOn("BY_VIDEO")).isEqualTo("By video link");
        }

        @Test
        @DisplayName("NOT_PRESENT reads Not present")
        void not_present_reads_not_present() {
            assertThat(appearanceOn("NOT_PRESENT")).isEqualTo("Not present");
        }

        @Test
        @DisplayName("an attendance type this mapper does not know is described by nothing")
        void an_unknown_attendance_type_is_described_by_nothing() {
            assertThat(appearanceOn("BY_TELEPHONE")).isNull();
        }

        @Test
        @DisplayName("a day recorded NOT_PRESENT still reports the defendant present — legacy, "
                + "uncatalogued, raised with the C8/C9 sign-off")
        void a_day_recorded_not_present_still_reports_presence() {
            assertThat(hearingOn("NOT_PRESENT").defendantPresent()).isTrue();
        }
    }

    @Nested
    @DisplayName("when there is no attendance to read")
    class NoAttendance {

        @Test
        @DisplayName("a hearing carrying no attendance at all reports absence")
        void no_attendance_reports_absence() {
            final CourtRegisterHearing hearing = HearingMapper.map(
                    hearingWithAttendance(null),
                    defendantWith(ORDERED_DAY, YOUTH_DEFENDANT),
                    anyDefendant());

            assertThat(hearing.defendantPresent()).isFalse();
            assertThat(hearing.defendantAppearanceDetails()).isNull();
        }

        @Test
        @DisplayName("an empty attendance list is guarded, not dereferenced")
        void an_empty_attendance_list_is_guarded() {
            final JsonNode hearing = hearingWithAttendance("[]");

            assertThatCode(() -> HearingMapper.map(
                    hearing, defendantWith(ORDERED_DAY, YOUTH_DEFENDANT), anyDefendant()))
                    .doesNotThrowAnyException();
            assertThat(HearingMapper.map(
                    hearing, defendantWith(ORDERED_DAY, YOUTH_DEFENDANT), anyDefendant())
                    .defendantPresent()).isFalse();
        }

        @Test
        @DisplayName("an attendance record carrying no days is guarded, not dereferenced")
        void an_attendance_record_with_no_days_is_guarded() {
            final JsonNode hearing = hearingWithAttendance(
                    "[{\"defendantId\":\"" + YOUTH_DEFENDANT + "\"}]");

            assertThat(HearingMapper.map(
                    hearing, defendantWith(ORDERED_DAY, YOUTH_DEFENDANT), anyDefendant())
                    .defendantPresent()).isFalse();
        }
    }

    /**
     * The authored base hearing, freshly parsed on every call so that a mutation made by one case
     * cannot reach the next.
     *
     * @return the hearing node
     */
    private JsonNode baseHearing() {
        return LegacyFixtures.readBase("hearing-with-surviving-youth-defendant.json").get("hearing");
    }

    /**
     * The base hearing mapped for its youth defendant.
     *
     * @return the mapped hearing details
     */
    private CourtRegisterHearing mapBaseHearing() {
        return HearingMapper.map(
                baseHearing(), defendantWith(ORDERED_DAY, YOUTH_DEFENDANT), anyDefendant());
    }

    /**
     * The base hearing's youth defendant record, which carries the defence organisation.
     *
     * @return the payload defendant node
     */
    private JsonNode anyDefendant() {
        return baseHearing().get("prosecutionCases").get(0).get("defendants").get(0);
    }

    /**
     * A hearing carrying the given attendance array and nothing else that matters here.
     *
     * @param attendance the {@code defendantAttendance} value as JSON text, or {@code null} for a
     *                   hearing that carries no such field
     * @return the hearing node
     */
    private JsonNode hearingWithAttendance(final String attendance) {
        final String field = attendance == null ? "" : ",\"defendantAttendance\":" + attendance;
        return mapper.readTree(
                "{\"jurisdictionType\":\"MAGISTRATES\",\"type\":{\"description\":\"Sentence\"}"
                        + field + "}");
    }

    /**
     * The mapped hearing for a defendant attending on the ordered day in the given way.
     *
     * @param attendanceType the payload's attendance type
     * @return the mapped hearing details
     */
    private CourtRegisterHearing hearingOn(final String attendanceType) {
        final JsonNode hearing = hearingWithAttendance(
                "[{\"defendantId\":\"" + YOUTH_DEFENDANT + "\",\"attendanceDays\":"
                        + "[{\"day\":\"" + ORDERED_DAY + "\",\"attendanceType\":\""
                        + attendanceType + "\",\"isInAttendance\":false}]}]");
        return HearingMapper.map(
                hearing, defendantWith(ORDERED_DAY, YOUTH_DEFENDANT), anyDefendant());
    }

    /**
     * How an appearance of the given type is described.
     *
     * @param attendanceType the payload's attendance type
     * @return the appearance details
     */
    private String appearanceOn(final String attendanceType) {
        return hearingOn(attendanceType).defendantAppearanceDetails();
    }

    /**
     * A register defendant carrying only what this mapper reads off one — the ids their attendance
     * is recorded against, and the day their results were ordered.
     *
     * @param orderedDate  the day their latest judicial result was ordered
     * @param defendantIds the ids they are known by across cases and applications
     * @return the register defendant
     */
    private RegisterDefendant defendantWith(
            final String orderedDate, final String... defendantIds) {

        return new RegisterDefendant(
                List.of(defendantIds),
                orderedDate == null ? List.of() : resultsOrderedOn(orderedDate),
                List.of(), List.of(),
                YOUTH_DEFENDANT, true, orderedDate, null);
    }

    /**
     * A register defendant whose results were ordered on several days, the first of them the latest.
     *
     * <p>The shape the kernel's rule is about and a single-result defendant cannot show: the
     * fragment's own {@code orderedDate} is one day, the gathered results name others, and the
     * defendant attended on one of the others.
     *
     * @param orderedDays the days their results were ordered, latest first
     * @return the register defendant
     */
    private RegisterDefendant defendantResultedOn(final String... orderedDays) {
        return new RegisterDefendant(
                List.of(YOUTH_DEFENDANT), resultsOrderedOn(orderedDays), List.of(), List.of(),
                YOUTH_DEFENDANT, true, orderedDays[0], null);
    }

    /**
     * Gathered results carrying nothing but the days they were ordered on.
     *
     * @param orderedDays the days
     * @return the results, in the order given
     */
    private List<RegisterResult> resultsOrderedOn(final String... orderedDays) {
        return Arrays.stream(orderedDays)
                .map(day -> new RegisterResult(
                        null, null, null, null, ResultLevel.OFFENCE, YOUTH_DEFENDANT,
                        mapper.readTree("{\"orderedDate\":\"" + day + "\"}"), null, null))
                .toList();
    }
}
