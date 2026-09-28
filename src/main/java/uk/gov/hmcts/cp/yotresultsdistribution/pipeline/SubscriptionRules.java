package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterVocabulary;

/**
 * Whether one now-subscription wants this register.
 *
 * <p>Ports the predicate half of {@code NowsHelper/service/SubscriptionsService.js} — the court-house
 * rule and the whole of {@code matchVocabularyRules}: attendance, major creditors, court house,
 * defendant, custody, custodial results, then the included and excluded prompt and result lists. The
 * kernel's own court-register case is the shape this answers for, and every branch below it is
 * shared with the NOWs flow and ports unchanged.
 *
 * <p>Pure: a subscription, a court-centre code, one defendant's vocabulary and the hearing's
 * judicial results in; a yes or a no out. No I/O, no clock (constitution Principle V).
 *
 * <p><strong>Three catalogued defects are fixed here.</strong>
 *
 * <ul>
 *   <li><strong>C4</strong> — the legacy feeds the same {@code ouCode} to {@code matchCourtHouse}
 *       ({@code selectedCourtHouses.includes}) <em>and</em> to {@code matchProsecutor}
 *       ({@code informantCode ===}), so a subscription whose informant code happens to equal a
 *       court-centre code is matched by a register nobody subscribed it to. A court centre's OU code
 *       has no meaning as an informant code; here it reaches the court-house rule and nothing
 *       else.</li>
 *   <li><strong>C5</strong> — the legacy has no branch keyed on
 *       {@code isCourtRegisterSubscription} at all ({@code SubscriptionsService.js:14-45}). A
 *       court-register subscription matches only by accident: through
 *       {@code selectedCourtHouses}, through a coincidental informant code, or through the
 *       {@code isNowSubscription} / {@code isEDTSubscription} /
 *       {@code isPrisonCourtRegisterSubscription} routes, none of which belongs to this flow.
 *       ({@code matchCpsProsecuted} at {@code :56} is dead code and is not reproduced.) Here the
 *       branch is explicit: a court-register subscription matches through its selected court houses
 *       and the vocabulary rules, and a subscription that is not one cannot match at all.</li>
 *   <li><strong>C30</strong> — {@code checkIfMajorCreditorTypeMatch} is asymmetric on empty data.
 *       {@code prosecutorMajorCreditor} and {@code nonProsecutorMajorCreditor} require a non-empty
 *       list and so can never match a court register, whose lists are always empty; but
 *       {@code anyMajorCreditor} tests {@code != null}, and an empty array is not null, so it always
 *       passes. All three now require a non-empty applicable list, consistently.</li>
 * </ul>
 *
 * <p><strong>JavaScript truthiness, not Java truth.</strong> Every flag a subscription carries is
 * read through {@link Json}, because the legacy reads them with {@code &&} and a subscription that
 * omits a flag is a subscription that does not ask for it. The two exceptions are the two places the
 * legacy is deliberately strict — {@code isCpsProsecuted === true} and the custodial-results
 * equality tail — and both are answered by {@link #strictlyEquals}, which is {@code ===} against a
 * boolean: a flag that is absent, null or a string equals neither {@code true} nor {@code false}.
 */
// PMD.OnlyOneReturn: each rule below answers at the clause that decides it, which is the shape the
// legacy predicates have — a chain of guarded `return true` / `return false` lines. Funnelling them
// through a single exit would reshape the control flow the port is reviewed against.
@SuppressWarnings("PMD.OnlyOneReturn")
public final class SubscriptionRules {

    /** The prompt type the legacy matches on a prefix rather than on equality. */
    private static final String NAME_ADDRESS = "NAMEADDRESS";

    /**
     * Whether the given subscription wants a register carrying this defendant's vocabulary.
     *
     * @param subscription    one now-subscription, exactly as reference data sent it
     * @param ouCode          the court centre's OU code; the court-house rule's only input
     * @param vocabulary      one register defendant's vocabulary, or {@code null} where the register
     *                        has none
     * @param judicialResults every judicial result the register gathered, across all its defendants
     * @return whether this subscription is matched
     */
    public boolean matches(
            final JsonNode subscription,
            final String ouCode,
            final RegisterVocabulary vocabulary,
            final List<JsonNode> judicialResults) {

        // C5 first, and on its own line: whatever else is true of a subscription, one that is not a
        // court-register subscription is not a recipient of a court register.
        return Json.truthy(subscription, "isCourtRegisterSubscription")
                && matchesCourtHouse(subscription, ouCode)
                && matchesVocabularyRules(subscription, vocabulary, judicialResults);
    }

    /**
     * The court-house rule — {@code matchCourtHouse}, and the only rule the court centre's OU code
     * takes part in (defect fix C4).
     *
     * @param subscription the subscription
     * @param ouCode       the court centre's OU code, or {@code null} where the hearing named none
     * @return whether the subscription selected this court house
     */
    private static boolean matchesCourtHouse(final JsonNode subscription, final String ouCode) {
        if (ouCode == null) {
            // `selectedCourtHouses.includes(undefined)` is false for every real court house, and a
            // register whose court centre has no code has to reach nobody rather than everybody.
            return false;
        }
        return Json.array(subscription, "selectedCourtHouses").stream()
                .anyMatch(house -> house.isString() && ouCode.equals(house.stringValue()));
    }

    /**
     * {@code matchVocabularyRules}, clause for clause.
     *
     * @param subscription    the subscription
     * @param vocabulary      the defendant's vocabulary, or {@code null} where there is none
     * @param judicialResults the register's judicial results
     * @return whether the subscription's rules are satisfied
     */
    private static boolean matchesVocabularyRules(
            final JsonNode subscription,
            final RegisterVocabulary vocabulary,
            final List<JsonNode> judicialResults) {

        if (!Json.truthy(subscription, "applySubscriptionRules")) {
            return true;
        }
        if (vocabulary == null) {
            // `:117`. The guard the Jest case names for the *subscription's* vocabulary is really on
            // the register's: a register with no vocabulary matches nothing, where matching
            // everything would address it to every subscriber of the court centre.
            return false;
        }
        final JsonNode rules = Json.at(subscription, "subscriptionVocabulary");
        if (!Json.truthy(rules)) {
            return true;
        }
        if (strictlyEquals(rules, "isCpsProsecuted", true) && vocabulary.isCpsProsecuted()) {
            // `:125-127` answers yes the moment both sides are CPS, before attendance, custody or
            // results are looked at. Uncatalogued, and therefore ported as it stands.
            return true;
        }
        return matchesAttendance(rules, vocabulary)
                && matchesMajorCreditor(rules, vocabulary)
                && matchesCourtHearing(rules, vocabulary)
                && matchesDefendant(rules, vocabulary)
                && matchesCustody(rules, vocabulary)
                && matchesCustodialResults(rules, vocabulary)
                && matchesPromptLists(rules, judicialResults)
                && matchesResultLists(rules, judicialResults);
    }

    /**
     * {@code checkIfAttendanceTypeMatch}.
     *
     * <p>The legacy spends three clauses on {@code anyAppearance} — one for a defendant who appeared
     * in no recorded way, and two for the two ways they might have — and their disjunction is simply
     * {@code anyAppearance}. It is written here as the one clause it is; the three cover the same
     * inputs and the parameterised table asserts all of them.
     *
     * <p>A subscription that names neither {@code anyAppearance} nor a specific type falls out of
     * the last line false and can therefore never match anything, which is worth knowing before the
     * reference-data snapshot is read.
     *
     * @param rules      the subscription's vocabulary block
     * @param vocabulary the defendant's vocabulary
     * @return whether the attendance rule is satisfied
     */
    private static boolean matchesAttendance(
            final JsonNode rules, final RegisterVocabulary vocabulary) {

        if (Json.truthy(rules, "anyAppearance")) {
            return true;
        }
        if (Json.truthy(rules, "appearedByVideoLink") && vocabulary.appearedByVideoLink()) {
            return true;
        }
        return Json.truthy(rules, "appearedInPerson") && vocabulary.appearedInPerson();
    }

    /**
     * {@code checkIfMajorCreditorTypeMatch}, with defect C30 fixed: all three flags now require a
     * non-empty applicable major-creditor list.
     *
     * <p>The legacy's per-value scan of the {@code FCOMP} results for a {@code CREDITOR_NAME}
     * prompt is not ported, because it is unreachable from this flow and its own first line says so:
     * both {@code isMajorCreditorProsecutor} and {@code isMajorCreditorNonProsecutor} return false
     * unless the applicable list is non-empty, and a court register's two lists are empty by
     * construction — the two-argument service leaves {@code complianceEnforcementList} undefined and
     * {@code buildApplicableMajorCreditorList} then returns {@code []} unconditionally
     * ({@code VocabularyService.js:329-334}). What the fix changes is the third predicate, which
     * reached past that same emptiness on a {@code != null} test and passed vacuously.
     *
     * @param rules      the subscription's vocabulary block
     * @param vocabulary the defendant's vocabulary
     * @return whether the major-creditor rule is satisfied
     */
    private static boolean matchesMajorCreditor(
            final JsonNode rules, final RegisterVocabulary vocabulary) {

        final boolean any = Json.truthy(rules, "anyMajorCreditor");
        final boolean prosecutor = Json.truthy(rules, "prosecutorMajorCreditor");
        final boolean nonProsecutor = Json.truthy(rules, "nonProsecutorMajorCreditor");
        if (!any && !prosecutor && !nonProsecutor) {
            return true;
        }
        if ((nonProsecutor || any) && !vocabulary.nonProsecutorMajorCreditor().isEmpty()) {
            return true;
        }
        return (prosecutor || any) && !vocabulary.prosecutorMajorCreditor().isEmpty();
    }

    /**
     * {@code checkIfCourtHouseMatch} — the Welsh/English rule, which despite its name is about the
     * hearing and not about the court house the register was addressed by.
     *
     * @param rules      the subscription's vocabulary block
     * @param vocabulary the defendant's vocabulary
     * @return whether the court-hearing rule is satisfied
     */
    private static boolean matchesCourtHearing(
            final JsonNode rules, final RegisterVocabulary vocabulary) {

        if (Json.truthy(rules, "anyCourtHearing")
                && (vocabulary.anyCourtHearing() || vocabulary.englishCourtHearing()
                        || vocabulary.welshCourtHearing())) {
            return true;
        }
        if (Json.truthy(rules, "englishCourtHearing") && vocabulary.englishCourtHearing()) {
            return true;
        }
        return Json.truthy(rules, "welshCourtHearing") && vocabulary.welshCourtHearing();
    }

    /**
     * {@code checkIfDefendantMatch} — the rule this whole flow turns on, since a court-register
     * subscription is keyed on {@code youthDefendant}.
     *
     * @param rules      the subscription's vocabulary block
     * @param vocabulary the defendant's vocabulary
     * @return whether the defendant rule is satisfied
     */
    private static boolean matchesDefendant(
            final JsonNode rules, final RegisterVocabulary vocabulary) {

        if (Json.truthy(rules, "adultOrYouthDefendant")
                && (vocabulary.adultOrYouthDefendant() || vocabulary.youthDefendant()
                        || vocabulary.adultDefendant())) {
            return true;
        }
        if (Json.truthy(rules, "youthDefendant") && vocabulary.youthDefendant()) {
            return true;
        }
        return Json.truthy(rules, "adultDefendant") && vocabulary.adultDefendant();
    }

    /**
     * {@code checkIfCustodyMatch}. A location flag narrows {@code inCustody} rather than widening
     * it: naming police custody excludes a defendant held at a prison, and naming both excludes
     * everybody, which is the legacy's arithmetic and is kept.
     *
     * @param rules      the subscription's vocabulary block
     * @param vocabulary the defendant's vocabulary
     * @return whether the custody rule is satisfied
     */
    private static boolean matchesCustody(
            final JsonNode rules, final RegisterVocabulary vocabulary) {

        if (Json.truthy(rules, "ignoreCustody")) {
            return true;
        }
        if (!Json.truthy(rules, "inCustody")) {
            return false;
        }
        final boolean police = Json.truthy(rules, "custodyLocationIsPolice");
        final boolean prison = Json.truthy(rules, "custodyLocationIsPrison");
        if (!police && !prison) {
            return vocabulary.inCustody();
        }
        if (police && !prison) {
            return vocabulary.custodyLocationIsPolice();
        }
        return !police && vocabulary.custodyLocationIsPrison();
    }

    /**
     * {@code checkIfCustodialResultMatch}. Both branches carry the same equality tail, and it is an
     * <em>equality</em> rather than an implication: a subscription asking for wholly non-custodial
     * results does not match a register that also carries a custodial one.
     *
     * @param rules      the subscription's vocabulary block
     * @param vocabulary the defendant's vocabulary
     * @return whether the custodial-results rule is satisfied
     */
    private static boolean matchesCustodialResults(
            final JsonNode rules, final RegisterVocabulary vocabulary) {

        if (Json.truthy(rules, "ignoreResults")) {
            return true;
        }
        if (!strictlyEquals(rules, "atleastOneCustodialResult",
                vocabulary.atleastOneCustodialResult())) {
            return false;
        }
        if (Json.truthy(rules, "allNonCustodialResults") && vocabulary.allNonCustodialResults()) {
            return true;
        }
        return Json.truthy(rules, "atleastOneNonCustodialResult")
                && vocabulary.atleastOneNonCustodialResult();
    }

    /**
     * The included and excluded prompt lists, which are read only when the subscription carries
     * them: an absent list is not an empty one, and the legacy branches on that before it looks.
     *
     * @param rules           the subscription's vocabulary block
     * @param judicialResults the register's judicial results
     * @return whether the prompt lists admit this register
     */
    private static boolean matchesPromptLists(
            final JsonNode rules, final List<JsonNode> judicialResults) {

        if (Json.truthy(rules, "includedPrompts")
                && !hasMatchedPrompt(judicialResults, Json.array(rules, "includedPrompts"))) {
            return false;
        }
        if (Json.truthy(rules, "excludedPrompts")
                && hasMatchedPrompt(judicialResults, Json.array(rules, "excludedPrompts"))) {
            return false;
        }
        return true;
    }

    /**
     * The included and excluded result lists, on the same absent-is-not-empty rule.
     *
     * @param rules           the subscription's vocabulary block
     * @param judicialResults the register's judicial results
     * @return whether the result lists admit this register
     */
    private static boolean matchesResultLists(
            final JsonNode rules, final List<JsonNode> judicialResults) {

        if (Json.truthy(rules, "includedResults")
                && !hasMatchedResult(judicialResults, Json.array(rules, "includedResults"))) {
            return false;
        }
        if (Json.truthy(rules, "excludedResults")
                && hasMatchedResult(judicialResults, Json.array(rules, "excludedResults"))) {
            return false;
        }
        return true;
    }

    /**
     * {@code checkForMatchedPrompts}: whether any result carries any of the named prompts.
     *
     * @param judicialResults the register's judicial results
     * @param references      the reference-data prompts to look for
     * @return whether one of them was found
     */
    private static boolean hasMatchedPrompt(
            final List<JsonNode> judicialResults, final List<JsonNode> references) {

        return judicialResults.stream()
                .flatMap(result -> Json.array(result, "judicialResultPrompts").stream())
                .anyMatch(prompt -> references.stream()
                        .anyMatch(reference -> promptMatches(reference, prompt)));
    }

    /**
     * {@code getMatchingPrompt}: a {@code NAMEADDRESS} prompt matches on a prefix, and every other
     * prompt on equality — both lower-cased.
     *
     * @param reference the reference-data prompt
     * @param prompt    the prompt a judicial result carries
     * @return whether the two match
     */
    private static boolean promptMatches(final JsonNode reference, final JsonNode prompt) {
        final String promptReference = Json.text(prompt, "promptReference");
        if (promptReference == null || promptReference.isEmpty()) {
            return false;
        }
        // The legacy reads `prompt.resultPromptReference.toLowerCase()` unguarded, so a reference
        // entry without one throws and the hearing produces nothing — which is what `dereferenced`
        // says here rather than quietly answering "no match" and sending a register the legacy
        // never sent.
        final String required = Json.dereferenced(reference, "resultPromptReference")
                .stringValue().toLowerCase(Locale.ROOT);
        final String actual = promptReference.toLowerCase(Locale.ROOT);
        return NAME_ADDRESS.equals(Json.text(prompt, "type"))
                ? actual.contains(required)
                : actual.equals(required);
    }

    /**
     * {@code checkForMatchedResults}: whether any result's type is on the named list.
     *
     * @param judicialResults the register's judicial results
     * @param references      the reference-data result type ids to look for
     * @return whether one of them was found
     */
    private static boolean hasMatchedResult(
            final List<JsonNode> judicialResults, final List<JsonNode> references) {

        return judicialResults.stream()
                .map(result -> Json.text(result, "judicialResultTypeId"))
                .filter(Objects::nonNull)
                .anyMatch(typeId -> references.stream()
                        .anyMatch(reference -> reference.isString()
                                && typeId.equals(reference.stringValue())));
    }

    /**
     * JavaScript's {@code ===} against a boolean: true only where the flag is present and is that
     * boolean. An absent flag, a null and a string all answer no — which is what makes the two
     * strict comparisons in the legacy different from the truthiness the rest of it is written in.
     *
     * @param rules the subscription's vocabulary block
     * @param field the flag to read
     * @param value the boolean to compare it with
     * @return whether the flag strictly equals the value
     */
    private static boolean strictlyEquals(
            final JsonNode rules, final String field, final boolean value) {

        final JsonNode flag = Json.at(rules, field);
        return flag != null && flag.isBoolean() && flag.booleanValue() == value;
    }
}
