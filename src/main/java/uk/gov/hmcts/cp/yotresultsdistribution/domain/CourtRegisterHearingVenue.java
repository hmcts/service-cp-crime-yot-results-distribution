package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The court house the register was produced at.
 *
 * <p>Ports {@code Models/HearingVenue.js} against the vendored
 * {@code courtRegisterHearingVenue.json}: the court centre's name, its local justice area where it
 * has one, and its address.
 *
 * <p>{@code courtHouse} is the contract's required field, and it is fed straight from
 * {@code courtCentre.name} ({@code Mappers/HearingVenue/HearingVenueMapper.js:12}). Every
 * court-register fixture in the legacy suite but one has a court centre carrying only an id and an
 * LJA — no name, no code, no address — so the venue that reaches the assertions is a venue
 * progression would reject, and the address body of the mapper has never executed at all. That is
 * why this phase authors a base hearing with a complete court centre.
 *
 * @param ljaName    the local justice area's name, where the court centre has one
 * @param courtHouse the court centre's name — the contract's one required field
 * @param address    the court house's address
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CourtRegisterHearingVenue(
        String ljaName,
        String courtHouse,
        CourtRegisterAddress address) {
}
