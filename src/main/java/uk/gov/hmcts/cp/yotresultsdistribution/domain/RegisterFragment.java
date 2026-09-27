package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One hearing's court register, before it is addressed and before it is mapped for the wire.
 *
 * <p>The legacy {@code CourtRegisterFragment} ({@code SetCourtRegister/index.js:7-17}) with two
 * changes and no others.
 *
 * <p><strong>{@code courtCentreId} is spelled correctly</strong> (defect C26). The legacy declares
 * and writes {@code courtCenterId} — "Center" — and the outbound mapper reads
 * {@code this.courtRegisterFragment.courtCenterId} while the fixture supplies
 * {@code courtCentreId}, so the field is {@code undefined} on both sides and the one Jest assertion
 * that names it compares {@code undefined} to {@code undefined}. The court centre's id is written to
 * every register progression stores and is asserted by nothing anywhere in the legacy suite; here it
 * has one spelling, end to end.
 *
 * <p><strong>{@code matchedSubscriptions} is not a field.</strong> The legacy hangs the matched
 * subscription list on this same object and hands the mutated object to the next activity; a Java
 * pipeline passes references, and a fragment that gains a field halfway down the chain cannot be
 * frozen at all. Matching returns its own answer instead, and the aggregation stage takes the
 * fragment and the matched subscriptions as two arguments.
 *
 * @param courtCentreId      the court centre the hearing sat at
 * @param registerDate       the instant the results were shared, unaltered (defect fix C10)
 * @param hearingDate        the sitting day the register covers, derived from the latest ordered date
 * @param hearingId          the hearing the register is for
 * @param registerDefendants every defendant the hearing gathered, adults included
 * @param courtCentreOUCode  the court centre's OU code — what subscriptions are matched by
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterFragment(
        String courtCentreId,
        String registerDate,
        String hearingDate,
        String hearingId,
        List<RegisterDefendant> registerDefendants,
        String courtCentreOUCode) {

    /** Freezes the defendant list, so a built fragment cannot be edited by a later stage. */
    public RegisterFragment {
        registerDefendants =
                registerDefendants == null ? List.of() : List.copyOf(registerDefendants);
    }
}
