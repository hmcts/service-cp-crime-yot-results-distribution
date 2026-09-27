package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A prosecution case or a court application the defendant appeared on.
 *
 * <p>Ports {@code Models/ProsecutionCaseOrApplication.js} against the vendored
 * {@code courtRegisterCaseOrApplication.json}. One record for both, because the legacy uses one
 * model for both and concatenates the two lists
 * ({@code Mappers/ProsecutionCaseOrApplication/ProsecutionCaseOrApplicationMapper.js:16-20}) — cases
 * first, applications second, an order the register prints and the comparator holds to.
 *
 * <p><strong>This record is where defect C26 is settled.</strong> The legacy model declares
 * {@code arrestSummonsNumbers} — plural — and every mapper writes the singular the schema asks for;
 * it declares five fields no mapper ever populates ({@code prosecutorName},
 * {@code applicationDecision}, {@code applicationDecisionDate}, {@code applicationResponse},
 * {@code applicationResponseDate}); and it omits {@code courtApplicationId}, which the mapper writes
 * on every application ({@code :82}). Here the spelling is singular, the five dead fields are gone,
 * and the written field is declared. No wire byte moves: the shape today is defined by what the
 * mappers write, and that is exactly what this declares. The five dropped fields are the ones
 * flagged to progression, whose PDF generator expects a {@code prosecutorName} concept nothing has
 * ever supplied.
 *
 * @param caseOrApplicationReference the case URN or the application's reference — required
 * @param courtApplicationId         the application's id, on applications only
 * @param applicationType            the application type's description, on applications only
 * @param offences                   the offences on this case or application
 * @param results                    the results recorded at case or application level
 * @param prosecutionCounsels        prosecuting counsel for the case, or the applicant's counsel
 * @param arrestSummonsNumber        the defendant's ASN, where they are a person and carry one
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CourtRegisterCaseOrApplication(
        String caseOrApplicationReference,
        String courtApplicationId,
        String applicationType,
        List<CourtRegisterOffence> offences,
        List<CourtRegisterResult> results,
        List<CourtRegisterCounsel> prosecutionCounsels,
        String arrestSummonsNumber) {

    /**
     * Freezes the three lists without inventing any of them.
     *
     * <p>Each carries {@code minItems: 1}, and each of the three mappers behind them answers with
     * nothing rather than with an empty array when it has nothing — so {@code null} stays
     * {@code null} here rather than being helpfully turned into a list progression would reject.
     */
    public CourtRegisterCaseOrApplication {
        offences = FrozenList.frozen(offences);
        results = FrozenList.frozen(results);
        prosecutionCounsels = FrozenList.frozen(prosecutionCounsels);
    }
}
