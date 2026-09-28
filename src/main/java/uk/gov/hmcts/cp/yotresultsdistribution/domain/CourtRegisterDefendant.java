package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One youth defendant as the register prints them.
 *
 * <p>Ports {@code Models/YouthDefendant.js} against the vendored
 * {@code courtRegisterDefendant.json}. Everything the document says about a child is here: their
 * name composed from its parts, their date of birth, address, nationality, ethnicity and gender,
 * where they were remanded to after the hearing, who stood as their parent or guardian, how the
 * hearing went for them, and every case, application, offence and result they appeared on.
 *
 * <p><strong>Required by the contract: {@code name}, {@code address} and
 * {@code prosecutionCasesOrApplications}.</strong> The address requirement is defect C29's
 * mechanism — a youth defendant with no address on the payload produces a document progression
 * answers 400 to, which the legacy swallows, losing the whole hearing's register for every other
 * defendant on it too.
 *
 * <p>{@code ethnicity} carries defect C25: the legacy emits it only when the payload holds
 * <em>both</em> an observed and a self-defined description, so a self-defined-only child has their
 * ethnicity dropped. Fixing that puts ethnicity data on registers that previously omitted it, which
 * is why it is the one fix on this record needing information-governance sign-off and not only
 * business sign-off.
 *
 * @param masterDefendantId              the defendant's identity across cases and applications
 * @param name                           their composed name — required by the contract
 * @param dateOfBirth                    their date of birth
 * @param address                        their address — required, and C29's mechanism when absent
 * @param nationality                    their nationality description
 * @param ethnicity                      their ethnicity description (defect C25)
 * @param gender                         their gender
 * @param postHearingCustodyStatus       where they were remanded after the hearing
 * @param parentGuardian                 their parent or guardian
 * @param hearing                        the hearing details printed against them
 * @param aliases                        names they have also been known by
 * @param prosecutionCasesOrApplications their cases and applications — required by the contract
 * @param defendantResults               results recorded at defendant level
 * @param defenceCounsels                counsel appearing for them
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CourtRegisterDefendant(
        String masterDefendantId,
        String name,
        String dateOfBirth,
        CourtRegisterAddress address,
        String nationality,
        String ethnicity,
        String gender,
        String postHearingCustodyStatus,
        CourtRegisterParentGuardian parentGuardian,
        CourtRegisterHearing hearing,
        List<CourtRegisterAlias> aliases,
        List<CourtRegisterCaseOrApplication> prosecutionCasesOrApplications,
        List<CourtRegisterResult> defendantResults,
        List<CourtRegisterCounsel> defenceCounsels) {

    /**
     * Freezes the four lists without inventing any of them.
     *
     * <p>All four carry {@code minItems: 1}. {@code aliases} is the one that proves the rule is not
     * pedantry: its mapper answers {@code undefined} for an absent alias list and {@code []} for an
     * empty one ({@code Mappers/Alias/AliasMapper.js:9}), where the counsel mapper answers
     * {@code undefined} for both — an asymmetry the legacy suite half-covers and this port has to
     * carry exactly. Collapsing null into empty here would erase it.
     */
    public CourtRegisterDefendant {
        aliases = FrozenList.frozen(aliases);
        prosecutionCasesOrApplications = FrozenList.frozen(prosecutionCasesOrApplications);
        defendantResults = FrozenList.frozen(defendantResults);
        defenceCounsels = FrozenList.frozen(defenceCounsels);
    }
}
