package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Which day the register says the hearing was held on.
 *
 * <p>Ports {@code NowsHelper/service/RegisterFragmentService.js:46-55}. The rule has two steps and
 * the legacy writes them in this order: if the hearing lists sitting days, look for one whose London
 * day is the latest ordered date and carry <em>that day's time</em>; otherwise carry the ordered
 * date, which has no time of its own and is therefore midnight. A hearing that lists no sitting days
 * at all gets no hearing date — the legacy's {@code if} has no {@code else}, so the function returns
 * {@code undefined}, and an absent hearing date is what the register then carries.
 *
 * <p><strong>An empty list is not an absent one.</strong> {@code if (hearingObj.hearingDays)} is
 * entered when the field is {@code []}, because an empty array is truthy in JavaScript — and the
 * {@code SetCourtRegister} fixture has exactly that. Reading "empty" as "absent" would take the
 * other branch and give the register no date at all.
 *
 * <p><strong>Two places the legacy stamps the wall clock, and this does not — defect fix C35.</strong>
 * {@code moment.tz(undefined, zone)} is the current time, so the legacy answers a hearing with no
 * ordered dates — and a sitting record carrying no {@code sittingDay}, whose absent day formats as
 * today and therefore matches an ordered date of today — with whenever the function happened to run.
 * Neither is reproduced: the transformation is pure and has no clock (constitution Principle V), so
 * a hearing with nothing to date by has no hearing date, and a sitting record that names no day
 * matches nothing.
 *
 * <p><strong>Where the legacy throws, this refuses.</strong> {@code hearingObj.hearingDays.find} is
 * not a function for a truthy value that is not an array, and {@code hearingDay.sittingDay} on a
 * {@code null} member is a {@code TypeError}; both kill the hearing, and the swallowed exception is
 * reported as success. Both are classified transformation failures here rather than iterated safely,
 * because a safe iteration would emit a register the legacy loses — a difference in the one
 * direction a port must not drift in.
 */
// PMD.OnlyOneReturn: the two absences below are distinct legacy branches — no sitting days at all,
// and nothing to match them against — and collapsing them into one exit would lose which of the
// legacy's two paths a hearing took.
@SuppressWarnings("PMD.OnlyOneReturn")
final class HearingDates {

    /** The hearing's own list of sitting days. */
    private static final String HEARING_DAYS = "hearingDays";

    /** The day one of those sittings was held on. */
    private static final String SITTING_DAY = "sittingDay";

    private HearingDates() {
    }

    /**
     * The hearing date a register covering these results carries.
     *
     * @param orderedDate the latest date any of the gathered results was ordered; may be
     *                    {@code null} when the hearing gathered none
     * @param hearing     the hearing payload, exactly as the producer sent it
     * @param dates       the register's date handling
     * @return the hearing date as an instant, or {@code null} where the legacy answers nothing
     * @throws uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationFailedException if a sitting day or
     *     the ordered date cannot be read as a date
     */
    /* default */ static String resolve(
            final String orderedDate, final JsonNode hearing, final Dates dates) {

        if (!Json.truthy(hearing, HEARING_DAYS)) {
            return null;
        }
        // `hearingObj.hearingDays.find(...)`: a truthy value that is not an array has no `find`, so
        // the legacy throws and the hearing produces no register at all. Answering "no sitting days"
        // here would date the register by the ordered date and send one the legacy loses.
        final String sittingDay =
                sittingDayFalling(Json.array(hearing, HEARING_DAYS), orderedDate, dates);
        if (sittingDay != null) {
            return dates.dateTime(sittingDay);
        }
        if (orderedDate == null) {
            // Nothing was ordered, so there is no day for the sittings to be matched against and
            // nothing to fall back to. The legacy formats `undefined` here, which is the wall clock
            // — the first leg of defect fix C35.
            return null;
        }
        return dates.dateTime(orderedDate);
    }

    /**
     * The first sitting day whose London day is the ordered date, as {@code find} answers it.
     *
     * <p>The ordered date is tested first, and the sitting day is only read against it when there is
     * one: with nothing ordered, {@code getLocalDate(sittingDay) === undefined} is false for every
     * record the legacy looks at, so no record can match and none is compared. That includes the
     * record carrying no {@code sittingDay} at all, whose absent day the legacy formats as
     * <em>today</em> and can therefore match an ordered date of today — the second leg of defect fix
     * C35, and the second place the legacy's hearing date is a clock reading.
     *
     * @param hearingDays the hearing's sitting days
     * @param orderedDate the ordered date to match against; may be {@code null}
     * @param dates       the register's date handling
     * @return that sitting day, or {@code null} where none falls on the ordered date
     */
    private static String sittingDayFalling(
            final List<JsonNode> hearingDays, final String orderedDate, final Dates dates) {

        for (final JsonNode hearingDay : hearingDays) {
            // `hearingDay.sittingDay` — reading a property off a null member is a TypeError in the
            // legacy and the hearing is lost with it. Reading the member as "no day set" would emit
            // a register the legacy never sent. An object that simply does not carry the field is a
            // different thing and is read, exactly as JavaScript reads it.
            final String sittingDay =
                    Json.text(Json.dereferencedElement(hearingDay, HEARING_DAYS), SITTING_DAY);
            if (orderedDate != null && sittingDay != null
                    && orderedDate.equals(dates.localDate(sittingDay))) {
                return sittingDay;
            }
        }
        return null;
    }
}
