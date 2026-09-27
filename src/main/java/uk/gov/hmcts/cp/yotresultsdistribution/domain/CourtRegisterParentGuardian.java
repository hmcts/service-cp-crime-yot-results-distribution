package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The parent or guardian printed against a youth defendant.
 *
 * <p>Ports {@code Models/ParentGuardian.js} against the vendored
 * {@code courtRegisterParentGuardian.json}. The name is composed from the person's parts the way
 * counsel names are ({@code Mappers/ParentGuardian/ParentGuardianMapper.js:19}); the address is the
 * ordinary address mapping, {@code postcode} case change included.
 *
 * <p><strong>Both fields are required by the contract.</strong> A parent or guardian with no address
 * therefore produces a document progression rejects with a 400 — which the legacy swallows, losing
 * the whole hearing's register (defect C29). Nothing about that is visible in the legacy suite: its
 * one parent-guardian fixture has a full address.
 *
 * @param name    the parent or guardian's composed name
 * @param address their address — required, and the C29 mechanism when it is missing
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CourtRegisterParentGuardian(String name, CourtRegisterAddress address) {
}
