package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An address as a court register carries it — a defendant's, a parent or guardian's, or the court
 * house's.
 *
 * <p>Ports {@code OutboundCourtRegister/CourtRegisterRequest/Models/Address.js} against the vendored
 * {@code courtRegisterAddress.json}. Five unnamed lines and a post code, and one thing worth saying
 * out loud: <strong>the payload spells it {@code postcode} and the wire spells it
 * {@code postCode}</strong> ({@code Mappers/Address/AddressMapper.js:16}). The case change is real
 * behaviour, pinned in exactly one place in the legacy suite (the parent-guardian twin), and a port
 * that carried the payload's spelling through would produce a document progression rejects.
 *
 * <p>{@code address1} is the schema's only required field, which is defect C29's whole mechanism: an
 * absent address makes the mapper return nothing, the defendant's required {@code address} is then
 * missing, progression answers 400, and the legacy swallows it — losing the hearing's entire
 * register with no trace. This record can represent that document; the pre-send validator is what
 * refuses to send it.
 *
 * @param address1 the first address line — the one the contract requires
 * @param address2 the second address line
 * @param address3 the third address line
 * @param address4 the fourth address line
 * @param address5 the fifth address line
 * @param postCode the post code, spelled the way the wire spells it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CourtRegisterAddress(
        String address1,
        String address2,
        String address3,
        String address4,
        String address5,
        String postCode) {
}
