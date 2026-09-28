package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterHearing;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterDefendant;

/**
 * Maps the hearing details printed against one defendant.
 *
 * <p>Ports {@code .../Mappers/Hearing/HearingMapper.js}. Jurisdiction, hearing type and the
 * attending solicitor's name are copied; the two attendance fields are computed, and both are
 * catalogued defects.
 *
 * <ul>
 *   <li><strong>C8</strong> — {@code find(d => d.defendantId = defendantId)} ({@code :13,22}) is an
 *       assignment, not a comparison. It always answers with element zero of
 *       {@code defendantAttendance} and mutates that element's id on the way past. The one Jest case
 *       covering this mapper has a one-element array, so element zero is coincidentally right.</li>
 *   <li><strong>C9</strong> — the day found is then compared against the fragment's
 *       {@code registerDate}, a datetime in production against a bare date on the payload. They
 *       never match, so {@code defendantPresent} is {@code false} and
 *       {@code defendantAppearanceDetails} absent on every register ever sent. The same Jest case
 *       supplies a bare-date {@code registerDate}, so it matches there and the defect is invisible.
 *       </li>
 * </ul>
 *
 * <p>The fix takes the attendance record by equality against the mapped defendant's own ids and
 * matches the day against <em>any</em> day one of their gathered results was ordered on — which is
 * why this takes the register defendant rather than the fragment: the results and the defendant ids
 * are both on it, and the fragment's {@code registerDate} is no longer part of the answer.
 */
// PMD.OnlyOneReturn: the attendance search answers where it finds its record, and the three
// appearance renderings answer where they are recognised; a single exit would replace the legacy's
// own `find` and its `if` ladder with control flow neither of them has.
@SuppressWarnings("PMD.OnlyOneReturn")
final class HearingMapper {

    /** The hearing's list of who attended, and on which days. */
    private static final String DEFENDANT_ATTENDANCE = "defendantAttendance";

    /** The days one attendance record covers. */
    private static final String ATTENDANCE_DAYS = "attendanceDays";

    private HearingMapper() {
    }

    /**
     * Maps the hearing details for one defendant.
     *
     * @param hearing           the hearing payload
     * @param registerDefendant the gathered defendant — their ids, and the day their results were
     *                          ordered
     * @param defendant         the payload defendant record they were gathered from, which carries
     *                          the defence organisation
     * @return the mapped hearing details, as they appear on {@link CourtRegisterDefendant}
     */
    /* default */ static CourtRegisterHearing map(
            final JsonNode hearing,
            final RegisterDefendant registerDefendant,
            final JsonNode defendant) {

        final JsonNode attendanceDay = attendanceDay(hearing, registerDefendant);

        return new CourtRegisterHearing(
                Json.text(hearing, "jurisdictionType"),
                // `this.hearingJson.type.description` — read through without a guard.
                Json.text(Json.dereferenced(hearing, "type"), "description"),
                attendanceDay != null,
                appearanceDetails(attendanceDay),
                attendingSolicitorName(defendant));
    }

    /**
     * The defendant's attendance record for the day their results were ordered — defect fix C8 and
     * C9 together.
     *
     * <p>C8 is the selection: {@code find(d => d.defendantId = defendantId)} is an assignment, so
     * the legacy answers with element zero whatever its id and writes the sought id over that
     * element's own on the way past. Here the record is the one whose {@code defendantId} is one of
     * the mapped defendant's own, and nothing is written to the hearing at all.
     *
     * <p>C9 is the day: the legacy compares the attendance day with the fragment's
     * {@code registerDate}, a datetime, so the comparison is false on every production register.
     * Here it is compared with the days the defendant's results were ordered on — all of them, which
     * is the shared kernel's own attendance rule ({@code VocabularyService.getAttendanceInfo:
     * 245-274}).
     *
     * @param hearing           the hearing payload, which is only ever read
     * @param registerDefendant the gathered defendant
     * @return the attendance day, or {@code null} where the defendant attended on no resulted day
     */
    private static JsonNode attendanceDay(
            final JsonNode hearing, final RegisterDefendant registerDefendant) {

        for (final JsonNode attendance : Json.array(hearing, DEFENDANT_ATTENDANCE)) {
            if (!registerDefendant.defendantIds().contains(Json.text(attendance, "defendantId"))) {
                continue;
            }
            for (final JsonNode day : Json.array(attendance, ATTENDANCE_DAYS)) {
                if (wasResultedOn(registerDefendant, Json.text(day, "day"))) {
                    return day;
                }
            }
        }
        return null;
    }

    /**
     * Whether any of the defendant's gathered results was ordered on the given day.
     *
     * <p>{@code results.some(result => result.judicialResult.orderedDate === attendanceDay.day)},
     * which is the kernel's rule verbatim ({@code VocabularyService.js:257}). <strong>Every</strong>
     * gathered result is asked, not only the latest: a hearing that sat over several days orders
     * results on each of them, and a defendant who attended on the first is present on the register
     * the fragment dates by the last. Reading only {@link RegisterDefendant#orderedDate()} — the
     * latest — would report that defendant absent, which is the same false answer C9 exists to end,
     * arrived at from a different direction.
     *
     * <p>A day the payload does not record is not a day anyone attended, so it matches nothing. The
     * kernel's {@code ===} would pair an absent {@code orderedDate} with an absent {@code day} and
     * report presence for a payload that records neither, which is not a fact about attendance.
     *
     * @param registerDefendant the gathered defendant, carrying their results
     * @param day               the attendance day being tested
     * @return whether a result was ordered on that day
     */
    private static boolean wasResultedOn(
            final RegisterDefendant registerDefendant, final String day) {

        return day != null && registerDefendant.results().stream()
                .anyMatch(result -> day.equals(Json.text(result.judicialResult(), "orderedDate")));
    }

    /**
     * How the defendant attended, as the register prints it.
     *
     * <p>Three renderings and no fourth: an attendance type this mapper does not know is described
     * by nothing, exactly as the legacy's {@code if}/{@code else if} ladder falls off its end.
     *
     * @param attendanceDay the attendance day, or {@code null} where there was none
     * @return the appearance details, or {@code null}
     */
    private static String appearanceDetails(final JsonNode attendanceDay) {
        return switch (Json.text(attendanceDay, "attendanceType")) {
            case "IN_PERSON" -> "In person";
            case "BY_VIDEO" -> "By video link";
            case "NOT_PRESENT" -> "Not present";
            case null, default -> null;
        };
    }

    /**
     * The defence organisation named against this defendant, where they had one.
     *
     * @param defendant the payload's defendant record
     * @return the organisation's name, or {@code null}
     */
    private static String attendingSolicitorName(final JsonNode defendant) {
        final JsonNode associated = Json.at(defendant, "associatedDefenceOrganisation");
        if (!Json.truthy(associated)) {
            return null;
        }
        // Guarded on the association and then read straight through, as the legacy's ternary does.
        return Json.text(
                Json.dereferenced(Json.dereferenced(associated, "defenceOrganisation"),
                        "organisation"),
                "name");
    }
}
