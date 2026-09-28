package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The eighteen facts about a defendant that a NOW subscription's predicates are matched against.
 *
 * <p>The shared kernel's {@code VocabularyInfo} ({@code NowsHelper/service/VocabularyService.js:9-28}),
 * component for component and name for name. The names are the wire contract with reference data:
 * a subscription's vocabulary block names the flags it requires, and the matcher looks each one up
 * by name — so a component spelt differently is a predicate that silently never matches.
 *
 * <p><strong>{@code atleastOne…} carries a lower-case {@code l}.</strong> That is the legacy's
 * spelling and reference data's; seven of the court register's Jest fixtures carry
 * {@code atLeastOne…} instead, along with only seven of these eighteen keys, and no Jest test
 * inspects a vocabulary object closely enough to notice. A port that took its key set from those
 * fixtures would fail subscription matching in production — including on {@code youthDefendant},
 * which is this flow's entire business rule — and every legacy test would still pass.
 * {@code VocabularyBuilderTest} asserts the key set exactly for that reason.
 *
 * <p><strong>The two creditor lists are always empty here</strong>, and that is not an unfinished
 * port. The court register constructs the legacy service with two arguments
 * ({@code SetCourtRegister/index.js:65}), which leaves {@code complianceEnforcementList} undefined
 * and makes {@code buildApplicableMajorCreditorList} return {@code []} unconditionally
 * ({@code VocabularyService.js:329-334}). Major creditors are a NOWs and enforcement concept; a
 * court register has none. They are carried as present-and-empty rather than dropped because the
 * matcher's three creditor predicates have to be able to tell an empty list from an absent one —
 * which is the other half of defect C30.
 *
 * @param custodyLocationIsPolice     the defendant is held at a police station
 * @param custodyLocationIsPrison     the defendant is held at a prison
 * @param atleastOneCustodialResult   some result carries the prison prompt
 * @param allNonCustodialResults      no result carries the prison prompt
 * @param atleastOneNonCustodialResult some result carries a prompt that is not the prison one
 * @param appearedInPerson            the defendant attended in person on a day a result was ordered
 * @param appearedByVideoLink         the defendant attended by video on a day a result was ordered
 * @param isCpsProsecuted             some prosecution case is prosecuted by the CPS
 * @param anyAppearance               the defendant appeared at all
 * @param inCustody                   the defendant is held anywhere the legacy recognises
 * @param youthDefendant              the defendant is a youth
 * @param adultDefendant              the defendant is not a youth
 * @param adultOrYouthDefendant       always true; the legacy computes it as the disjunction
 * @param welshCourtHearing           the court centre is Welsh
 * @param englishCourtHearing         the court centre is not Welsh
 * @param anyCourtHearing             always true; the legacy computes it as the disjunction
 * @param prosecutorMajorCreditor     always empty for this flow — see the class comment
 * @param nonProsecutorMajorCreditor  always empty for this flow — see the class comment
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterVocabulary(
        boolean custodyLocationIsPolice,
        boolean custodyLocationIsPrison,
        boolean atleastOneCustodialResult,
        boolean allNonCustodialResults,
        boolean atleastOneNonCustodialResult,
        boolean appearedInPerson,
        boolean appearedByVideoLink,
        @JsonProperty("isCpsProsecuted") boolean isCpsProsecuted,
        boolean anyAppearance,
        boolean inCustody,
        boolean youthDefendant,
        boolean adultDefendant,
        boolean adultOrYouthDefendant,
        boolean welshCourtHearing,
        boolean englishCourtHearing,
        boolean anyCourtHearing,
        List<String> prosecutorMajorCreditor,
        List<String> nonProsecutorMajorCreditor) {

    /** Freezes the two creditor lists, so a vocabulary cannot be edited after it is attached. */
    public RegisterVocabulary {
        prosecutorMajorCreditor =
                prosecutorMajorCreditor == null ? List.of() : List.copyOf(prosecutorMajorCreditor);
        nonProsecutorMajorCreditor = nonProsecutorMajorCreditor == null
                ? List.of() : List.copyOf(nonProsecutorMajorCreditor);
    }
}
