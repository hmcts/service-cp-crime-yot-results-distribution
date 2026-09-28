package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The hearing details printed against one defendant on the register.
 *
 * <p>Ports {@code Models/Hearing.js} against the vendored {@code courtRegisterHearing.json}. Three
 * of the five fields are required by the contract, {@code defendantPresent} among them — which is
 * why it is a primitive here: there is no such thing as a register that declines to say whether the
 * defendant turned up.
 *
 * <p><strong>Two catalogued defects live behind those two attendance fields.</strong> C8: the legacy
 * finds the attendance record with {@code find(d => d.defendantId = defendantId)} — an assignment,
 * so it always returns element zero and mutates it. C9: it then compares an attendance
 * <em>day</em> with the register's <em>datetime</em>, which cannot be equal in production, so
 * {@code defendantPresent} is {@code false} and {@code defendantAppearanceDetails} absent on every
 * register the service has ever sent. The single Jest case that covers this mapper masks both at
 * once, with a one-element attendance array and a bare-date {@code registerDate}. The fixed
 * behaviour these fields carry is real attendance data; the shape does not change.
 *
 * @param jurisdiction               the hearing's jurisdiction
 * @param hearingType                the hearing type's description
 * @param defendantPresent           whether the defendant attended on the day the register covers
 * @param defendantAppearanceDetails how they attended — in person, by video link, or not present
 * @param attendingSolicitorName     the defence organisation's name, where the defendant had one
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CourtRegisterHearing(
        String jurisdiction,
        String hearingType,
        boolean defendantPresent,
        String defendantAppearanceDetails,
        String attendingSolicitorName) {
}
