package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One offence on a case or application, as the register prints it.
 *
 * <p>Ports {@code Models/Offence.js} against the vendored {@code courtRegisterOffence.json}. Most of
 * it is copied from the payload's offence; three components are not, and two of those are catalogued
 * defects.
 *
 * <ul>
 *   <li><strong>{@code wording}</strong> — the legacy writes
 *       {@code wording + '####' + offenceLegislation} ({@code Mappers/Offence/OffenceMapper.js:17}),
 *       a sentinel progression's PDF generator substitutes for a newline at render time, and a
 *       literal {@code "…####undefined"} when there is no legislation (defect C24).</li>
 *   <li><strong>{@code verdictCode}</strong> — the legacy writes the verdict type's prose
 *       <em>description</em> into a field named for a code ({@code :20}, defect C23).</li>
 *   <li><strong>{@code results}</strong> — scoped to this offence, by result level and offence id
 *       ({@code :24-26}). This is the one place the court register is more correct than its
 *       informant sibling, and no legacy fixture exercises it: the only offence fixture's context
 *       carries an empty result list.</li>
 * </ul>
 *
 * @param offenceCode        the offence's CJS code — required by the contract
 * @param orderIndex         the offence's index within the case
 * @param offenceTitle       the offence title from reference data — required by the contract
 * @param wording            the charge's wording, with the legislation joined onto it
 * @param pleaValue          the defendant's plea
 * @param indicatedPleaValue the defendant's indicated plea
 * @param pleaDate           the date of the plea or indicated plea
 * @param allocationDecision the court's allocation decision, as its mode-of-trial reason
 * @param convictionDate     the conviction date, where the defendant was convicted
 * @param verdictCode        the verdict against the offence
 * @param results            the results ordered against this offence and nothing else
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CourtRegisterOffence(
        String offenceCode,
        Integer orderIndex,
        String offenceTitle,
        String wording,
        String pleaValue,
        String indicatedPleaValue,
        String pleaDate,
        String allocationDecision,
        String convictionDate,
        String verdictCode,
        List<CourtRegisterResult> results) {

    /**
     * Freezes the result list without inventing one.
     *
     * <p>A {@code null} list stays {@code null}. The contract puts {@code minItems: 1} on
     * {@code results}, so an empty array is not a quieter way of saying "no results" — it is a
     * document progression rejects. Absent, null and empty are three different statements here and
     * the comparator that guards this port treats them as three.
     */
    public CourtRegisterOffence {
        results = FrozenList.frozen(results);
    }
}
