package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterHearingVenue;

/**
 * Maps the hearing's court centre onto the register's venue.
 *
 * <p>Ports {@code .../Mappers/HearingVenue/HearingVenueMapper.js}: the court centre's name as the
 * court house, its local justice area's name where it has one, and its address — with the same
 * {@code postcode} to {@code postCode} case change {@link AddressMapper} makes, written out a second
 * time in the legacy rather than reused.
 *
 * <p><strong>Almost none of this has ever been executed.</strong> Every court-register fixture but
 * one carries a court centre of an id and an LJA only, so the address body has never run in the
 * legacy suite and {@code courtHouse} — the contract's required field — comes out absent. The legacy
 * constructor also reads {@code courtCentre.address} eagerly, so a hearing with no court centre at
 * all throws before {@code build()} is entered.
 */
final class HearingVenueMapper {

    private HearingVenueMapper() {
    }

    /**
     * Maps the venue.
     *
     * @param hearing the hearing payload
     * @return the mapped venue
     * @throws uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationFailedException if the hearing
     *     carries no court centre, which the legacy dereferences without a guard
     */
    /* default */ static CourtRegisterHearingVenue map(final JsonNode hearing) {
        // `this.hearingJson.courtCentre.address` in the constructor: a hearing with no court centre
        // is a TypeError there, swallowed at `OutboundCourtRegister/index.js:62-64` along with the
        // register. Kept as a refusal — `courtHouse` is required by the frozen contract, so there is
        // no venue to print — but a named, classified one rather than a silence.
        final JsonNode courtCentre = Json.dereferenced(hearing, "courtCentre");
        final JsonNode localJusticeArea = Json.at(courtCentre, "lja");

        return new CourtRegisterHearingVenue(
                Json.truthy(localJusticeArea) ? Json.text(localJusticeArea, "ljaName") : null,
                Json.text(courtCentre, "name"),
                // The legacy writes the address body out a second time here rather than reusing the
                // address mapper. It is the same six fields and the same case change, so it is the
                // address mapper.
                AddressMapper.map(Json.at(courtCentre, "address")));
    }
}
