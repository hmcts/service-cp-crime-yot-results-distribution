package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterAddress;

/**
 * Maps a payload address onto the register's address.
 *
 * <p>Ports {@code OutboundCourtRegister/CourtRegisterRequest/Mappers/Address/AddressMapper.js}. Five
 * lines pass straight through and the post code changes case on the way out — the payload spells it
 * {@code postcode}, the wire spells it {@code postCode}.
 *
 * <p>An absent address maps to nothing, not to an empty address. It is the distinction defect C29
 * turns on, and it is also the one the legacy's own test file gets wrong — that file has never run,
 * because its name misses Jest's pattern (defect C28), so nobody has noticed that it asserts
 * {@code []} where the mapper answers {@code undefined}.
 */
// PMD.OnlyOneReturn: the absent branch is the legacy's own early return, and it is the branch
// defect C29 turns on — an address that is absent rather than empty. Funnelling it through a single
// exit would hide the one decision this mapper makes.
@SuppressWarnings("PMD.OnlyOneReturn")
final class AddressMapper {

    private AddressMapper() {
    }

    /**
     * Maps one address.
     *
     * @param addressInfo the payload's address object, or {@code null} where there is none
     * @return the mapped address, or {@code null} where there was no address to map
     */
    /* default */ static CourtRegisterAddress map(final JsonNode addressInfo) {
        if (!Json.truthy(addressInfo)) {
            return null;
        }
        return new CourtRegisterAddress(
                Json.text(addressInfo, "address1"),
                Json.text(addressInfo, "address2"),
                Json.text(addressInfo, "address3"),
                Json.text(addressInfo, "address4"),
                Json.text(addressInfo, "address5"),
                // The payload spells it `postcode` and the wire spells it `postCode`. This one case
                // change is the whole of what this mapper transforms.
                Json.text(addressInfo, "postcode"));
    }
}
