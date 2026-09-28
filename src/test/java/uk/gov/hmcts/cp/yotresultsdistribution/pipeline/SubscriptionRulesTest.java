package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterVocabulary;
import uk.gov.hmcts.cp.yotresultsdistribution.support.LegacyFixtures;

/**
 * Whether one subscription wants this register: the court-house branch, and the vocabulary rules
 * behind it.
 *
 * <p>The kernel's {@code SubscriptionsService} Jest suite has twenty-two cases, two of them written
 * for the court register. Both are twinned here; so are the vocabulary, prompt and result branches
 * the court register shares with the NOWs flow. The user-group and child-subscription cases are not:
 * {@code CourtRegisterSubscriptions/index.js:44-49} sets only {@code vocabulary},
 * {@code subscriptions}, {@code ouCode} and {@code judicialResults}, so no court register ever
 * carries a user group or a NOW id, and twinning those cases would mean building routes this flow
 * cannot take.
 *
 * <p><strong>Every twin below adds two fields to its loaded fixture</strong> —
 * {@code isCourtRegisterSubscription} and {@code selectedCourtHouses} — which is precisely what the
 * kernel's own court-register case does to the same file. Without them the fixtures are NOWs
 * subscriptions, and under C5 a NOWs subscription cannot match this flow at all; the twins would
 * then all answer "no match" for a reason that has nothing to do with the branch each was written to
 * exercise. That refusal is asserted on its own, in the first nest.
 *
 * <p><strong>Three of the Jest cases are vacuous</strong> and are repaired rather than inherited.
 * The two "should NOT include" cases both fail earlier than their names claim — one on its included
 * prompts and one on its attendance rule — so neither exclusion list is exercised by the legacy suite
 * at all; and the "included results are matched" case loads the fixture whose subscription carries no
 * result lists. Each is repaired to assert what its name says, and the case it was actually asserting
 * is kept beside it under an honest name.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> rows C4,
 *     C5 and C30
 */
@DisplayName("SubscriptionRules")
class SubscriptionRulesTest {

    /** The court centre the kernel's own court-register case matches on. */
    private static final String OU_CODE = "OU_CODE";

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    private final SubscriptionRules rules = new SubscriptionRules();

    /**
     * The branch the legacy does not have. {@code SubscriptionsService.js:14-45} keys on
     * {@code isNowSubscription}, {@code isEDTSubscription},
     * {@code isPrisonCourtRegisterSubscription} and a matching {@code informantCode}, and on
     * {@code selectedCourtHouses} — but never on {@code isCourtRegisterSubscription}. A court
     * register's recipients are therefore whoever those four routes happen to admit.
     */
    @Nested
    @DisplayName("the court-register branch (C4, C5)")
    class CourtRegisterBranch {

        @Test
        @DisplayName("court register subscriptions match via selected court houses")
        void court_register_subscriptions_match_via_selected_court_houses() {
            assertThat(matches(courtRegisterSubscription(), OU_CODE, youthAtAnyCourt())).isTrue();
        }

        @Test
        @DisplayName("ou code matches court houses only")
        void ou_code_matches_court_houses_only() {
            // C4. The legacy hands the same value to `matchCourtHouse` and to `matchProsecutor`,
            // which compares it to `informantCode` — so a subscription whose informant code happens
            // to equal a court-centre code is matched by a register nobody subscribed it to. This
            // subscription carries the OU code as its informant code and no selected court houses,
            // and the legacy matches it.
            final ObjectNode informantOnly = courtRegisterSubscription();
            informantOnly.remove("selectedCourtHouses");
            informantOnly.put("informantCode", OU_CODE);

            assertThat(matches(informantOnly, OU_CODE, youthAtAnyCourt())).isFalse();
        }

        @Test
        @DisplayName("does not match a subscription that is not a court register subscription")
        void does_not_match_a_subscription_that_is_not_a_court_register_one() {
            // C5. Selected court houses and a satisfied vocabulary, but the subscription is a NOWs
            // one: in the legacy the court-house branch admits it, because that branch never asks
            // what kind of subscription it is.
            final ObjectNode nowsSubscription = courtRegisterSubscription();
            nowsSubscription.put("isCourtRegisterSubscription", false);
            nowsSubscription.put("isNowSubscription", true);

            assertThat(matches(nowsSubscription, OU_CODE, youthAtAnyCourt())).isFalse();
        }

        @Test
        @DisplayName("does not match a prison court register subscription")
        void does_not_match_a_prison_court_register_subscription() {
            // The prison court register is its own pipeline with its own future migration; the
            // legacy's `:39` branch would admit it to this one.
            final ObjectNode prison = courtRegisterSubscription();
            prison.put("isCourtRegisterSubscription", false);
            prison.put("isPrisonCourtRegisterSubscription", true);

            assertThat(matches(prison, OU_CODE, youthAtAnyCourt())).isFalse();
        }

        @Test
        @DisplayName("does not match a court house the subscription did not select")
        void does_not_match_an_unselected_court_house() {
            assertThat(matches(courtRegisterSubscription(), "OTHER_OU", youthAtAnyCourt()))
                    .isFalse();
        }

        @Test
        @DisplayName("does not match a subscription that selected no court houses at all")
        void does_not_match_a_subscription_with_no_selected_court_houses() {
            final ObjectNode noCourtHouses = courtRegisterSubscription();
            noCourtHouses.remove("selectedCourtHouses");

            assertThat(matches(noCourtHouses, OU_CODE, youthAtAnyCourt())).isFalse();
        }

        @Test
        @DisplayName("matches nothing when the hearing's court centre has no OU code")
        void matches_nothing_when_the_court_centre_has_no_ou_code() {
            // Most court-register fixtures omit `courtCentre.code`, so this is not a hypothetical
            // shape. An absent code has to match nothing rather than match everything.
            assertThat(matches(courtRegisterSubscription(), null, youthAtAnyCourt())).isFalse();
        }
    }

    /**
     * {@code matchVocabularyRules}, which every route funnels into and which the court register
     * shares with the NOWs flow unchanged.
     */
    @Nested
    @DisplayName("the vocabulary rules")
    class VocabularyRules {

        @Test
        @DisplayName("a subscription that does not apply the rules matches on the court house alone")
        void a_subscription_that_does_not_apply_the_rules_matches_on_the_court_house() {
            final ObjectNode noRules = courtRegisterSubscription();
            noRules.put("applySubscriptionRules", false);

            assertThat(matches(noRules, OU_CODE, vocabulary())).isTrue();
        }

        @Test
        @DisplayName("a subscription carrying no vocabulary block matches on the court house alone")
        void a_subscription_with_no_vocabulary_block_matches_on_the_court_house() {
            final ObjectNode noVocabulary = courtRegisterSubscription();
            noVocabulary.remove("subscriptionVocabulary");

            assertThat(matches(noVocabulary, OU_CODE, vocabulary())).isTrue();
        }

        @Test
        @DisplayName("Should exclude subscriptions if subscription vocabulary is not defined")
        void should_exclude_subscriptions_if_vocabulary_is_not_defined() {
            // The Jest case's name says "subscription vocabulary"; the guard it exercises is on the
            // *register's* vocabulary (`SubscriptionsService.js:117`). A register with no vocabulary
            // matches nothing, which is the answer this port has to keep: matching everything would
            // address a register to every subscriber of the court centre.
            assertThat(matches(courtRegisterSubscription(), OU_CODE, null)).isFalse();
        }

        @Test
        @DisplayName("matches a CPS subscription to a CPS prosecution before any other rule")
        void matches_a_cps_subscription_to_a_cps_prosecution_first() {
            // `:125-127` returns true the moment both sides are CPS, before attendance, custody or
            // results are looked at — so this subscription matches although its attendance rule
            // cannot be satisfied by this register. Uncatalogued, and therefore ported as it stands.
            final ObjectNode cps = courtRegisterSubscription();
            subscriptionVocabularyOf(cps).put("isCpsProsecuted", true);
            subscriptionVocabularyOf(cps).put("appearedInPerson", true);

            assertThat(matches(cps, OU_CODE, vocabulary("isCpsProsecuted", "anyCourtHearing")))
                    .isTrue();
        }

        @Test
        @DisplayName("matches nothing when the subscription names no appearance at all")
        void matches_nothing_when_the_subscription_names_no_appearance() {
            // `checkIfAttendanceTypeMatch` ends in `appearedInPerson && vocabulary.appearedInPerson`,
            // so a subscription that names neither `anyAppearance` nor a specific type falls out
            // false and can never match anything. Every live court-register subscription therefore
            // has to carry an appearance flag, which is worth knowing before the reference-data
            // snapshot is read (Q9).
            final ObjectNode silent = courtRegisterSubscription();
            subscriptionVocabularyOf(silent).put("anyAppearance", false);

            assertThat(matches(silent, OU_CODE, youthAtAnyCourt())).isFalse();
        }

        @ParameterizedTest
        @CsvSource({
            "anyAppearance,       -,                    true",
            "anyAppearance,       appearedInPerson,     true",
            "anyAppearance,       appearedByVideoLink,  true",
            "appearedInPerson,    appearedInPerson,     true",
            "appearedInPerson,    appearedByVideoLink,  false",
            "appearedByVideoLink, appearedByVideoLink,  true",
            "appearedByVideoLink, appearedInPerson,     false",
        })
        @DisplayName("matches attendance the way the kernel does")
        void matches_attendance_the_way_the_kernel_does(
                final String subscriptionFlag, final String appearance, final boolean expected) {

            // `anyAppearance` matches whether or not the defendant appeared: its first branch is
            // written for the case where neither appearance flag is set.
            final ObjectNode subscription = courtRegisterSubscription();
            subscriptionVocabularyOf(subscription).put("anyAppearance", false);
            subscriptionVocabularyOf(subscription).put(subscriptionFlag, true);

            assertThat(matches(subscription, OU_CODE,
                    vocabularyOf("anyCourtHearing youthDefendant " + appearance)))
                    .isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource({
            "anyCourtHearing,     anyCourtHearing,      true",
            "anyCourtHearing,     englishCourtHearing,  true",
            "anyCourtHearing,     welshCourtHearing,    true",
            "englishCourtHearing, englishCourtHearing,  true",
            "englishCourtHearing, welshCourtHearing,    false",
            "welshCourtHearing,   welshCourtHearing,    true",
        })
        @DisplayName("matches the court hearing the way the kernel does")
        void matches_the_court_hearing_the_way_the_kernel_does(
                final String subscriptionFlag, final String hearing, final boolean expected) {

            final ObjectNode subscription = courtRegisterSubscription();
            subscriptionVocabularyOf(subscription).put("anyCourtHearing", false);
            subscriptionVocabularyOf(subscription).put(subscriptionFlag, true);

            assertThat(matches(subscription, OU_CODE,
                    vocabularyOf("youthDefendant " + hearing)))
                    .isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource({
            "adultOrYouthDefendant, youthDefendant,        true",
            "adultOrYouthDefendant, adultDefendant,        true",
            "adultOrYouthDefendant, adultOrYouthDefendant, true",
            "youthDefendant,        youthDefendant,        true",
            "youthDefendant,        adultDefendant,        false",
            "adultDefendant,        adultDefendant,        true",
            "adultDefendant,        youthDefendant,        false",
        })
        @DisplayName("matches the defendant the way the kernel does")
        void matches_the_defendant_the_way_the_kernel_does(
                final String subscriptionFlag, final String defendant, final boolean expected) {

            final ObjectNode subscription = courtRegisterSubscription();
            subscriptionVocabularyOf(subscription).put("youthDefendant", false);
            subscriptionVocabularyOf(subscription).put(subscriptionFlag, true);

            assertThat(matches(subscription, OU_CODE,
                    vocabularyOf("anyCourtHearing " + defendant)))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("ignores custody entirely when the subscription says to")
        void ignores_custody_when_the_subscription_says_to() {
            assertThat(matches(courtRegisterSubscription(), OU_CODE, youthAtAnyCourt())).isTrue();
        }

        @ParameterizedTest
        @CsvSource({
            "inCustody,                            inCustody,                            true",
            "inCustody,                            -,                                    false",
            "inCustody custodyLocationIsPolice,    inCustody custodyLocationIsPolice,    true",
            "inCustody custodyLocationIsPolice,    inCustody custodyLocationIsPrison,    false",
            "inCustody custodyLocationIsPrison,    inCustody custodyLocationIsPrison,    true",
        })
        @DisplayName("matches custody the way the kernel does")
        void matches_custody_the_way_the_kernel_does(
                final String custodyRule, final String custody, final boolean expected) {

            final ObjectNode subscription = courtRegisterSubscription();
            subscriptionVocabularyOf(subscription).remove("ignoreCustody");
            for (final String flag : custodyRule.split(" ")) {
                subscriptionVocabularyOf(subscription).put(flag, true);
            }

            assertThat(matches(subscription, OU_CODE,
                    vocabularyOf("anyCourtHearing youthDefendant " + custody)))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("matches non-custodial results only when the custodial flags agree exactly")
        void matches_non_custodial_results_when_the_custodial_flags_agree() {
            // Both result branches carry the same equality tail: the subscription's
            // `atleastOneCustodialResult` must *equal* the register's, so a subscription asking for
            // wholly non-custodial results does not match a register that also has a custodial one.
            final ObjectNode subscription = courtRegisterSubscription();
            subscriptionVocabularyOf(subscription).remove("ignoreResults");
            subscriptionVocabularyOf(subscription).put("allNonCustodialResults", true);

            assertThat(matches(subscription, OU_CODE, vocabulary(
                    "anyCourtHearing", "youthDefendant", "allNonCustodialResults"))).isTrue();
            assertThat(matches(subscription, OU_CODE, vocabulary(
                    "anyCourtHearing", "youthDefendant", "allNonCustodialResults",
                    "atleastOneCustodialResult"))).isFalse();
        }
    }

    /**
     * Defect C30. The legacy's three major-creditor predicates disagree about what an empty list
     * means: {@code prosecutorMajorCreditor} and {@code nonProsecutorMajorCreditor} require
     * {@code .length > 0} and can never match a court register, whose lists are empty by
     * construction; {@code anyMajorCreditor} tests {@code != null}, and an empty array is not null,
     * so it always passes. Same emptiness, opposite answers.
     */
    @Nested
    @DisplayName("major creditors (C30)")
    class MajorCreditors {

        @Test
        @DisplayName("major creditor flags never match a court register")
        void major_creditor_flags_never_match_a_court_register() {
            // The legacy matches this subscription against every court register ever produced,
            // because the register's creditor lists are empty rather than absent.
            final ObjectNode anyCreditor = courtRegisterSubscription();
            subscriptionVocabularyOf(anyCreditor).put("anyMajorCreditor", true);

            assertThat(matches(anyCreditor, OU_CODE, youthAtAnyCourt())).isFalse();
        }

        @ParameterizedTest
        @CsvSource({"prosecutorMajorCreditor", "nonProsecutorMajorCreditor"})
        @DisplayName("neither of the specific creditor flags matches one either")
        void neither_specific_creditor_flag_matches_one(final String flag) {
            // These two already answer no in the legacy. Their answer is what the third one is being
            // made consistent with, so they are pinned rather than assumed.
            final ObjectNode specific = courtRegisterSubscription();
            subscriptionVocabularyOf(specific).put(flag, true);

            assertThat(matches(specific, OU_CODE, youthAtAnyCourt())).isFalse();
        }

        @Test
        @DisplayName("leaves a subscription that asks for no creditor alone")
        void leaves_a_subscription_that_asks_for_no_creditor_alone() {
            // The fix narrows one predicate; it must not make the creditor check a gate on
            // subscriptions that never mentioned a creditor, which is every live court-register one.
            assertThat(matches(courtRegisterSubscription(), OU_CODE, youthAtAnyCourt())).isTrue();
        }
    }

    /**
     * The included and excluded lists, which the legacy suite covers less than its case names claim.
     */
    @Nested
    @DisplayName("included and excluded prompts and results")
    class IncludedAndExcluded {

        @Test
        @DisplayName("Should include subscriptions if included Prompts are matched with result "
                + "prompts")
        void should_include_subscriptions_if_included_prompts_are_matched() {
            assertThat(matches(promptSubscription(), OU_CODE, adultOrYouthAtAnyCourt(),
                    judicialResults("judicial-results-with-included-prompts.json"))).isTrue();
        }

        @Test
        @DisplayName("does not match when no result carries an included prompt")
        void does_not_match_when_no_result_carries_an_included_prompt() {
            // This is what the Jest case named "Should Not include subscriptions if excluded Prompts
            // are matched" actually asserts: its results carry `witnessName` and no
            // `suretyNameAndAddress`, so the subscription fails its *included* list and the excluded
            // list is never reached. The name is repaired here and the exclusion is asserted below.
            assertThat(matches(promptSubscription(), OU_CODE, adultOrYouthAtAnyCourt(),
                    judicialResults("judicial-results-with-excluded-prompts.json"))).isFalse();
        }

        @Test
        @DisplayName("does not match when a result carries an excluded prompt")
        void does_not_match_when_a_result_carries_an_excluded_prompt() {
            // The repair: with the included list removed, the excluded list is the only thing left to
            // decide, and the same results now answer the question the Jest case meant to ask.
            final ObjectNode excludedOnly = promptSubscription();
            subscriptionVocabularyOf(excludedOnly).remove("includedPrompts");

            assertThat(matches(excludedOnly, OU_CODE, adultOrYouthAtAnyCourt(),
                    judicialResults("judicial-results-with-excluded-prompts.json"))).isFalse();
        }

        @Test
        @DisplayName("matches a NAMEADDRESS prompt on a prefix and every other prompt on equality")
        void matches_a_name_address_prompt_on_a_prefix() {
            // `getMatchingPrompt` compares a NAMEADDRESS prompt with `includes` and everything else
            // with `===`, both lower-cased. The included-prompts fixture only reaches the first of
            // those: its matching prompt is `suretynameandaddressOrganisationName`, which contains
            // the reference and does not equal it.
            final ObjectNode subscription = promptSubscription();
            final JsonNode notNameAddress = mapper.readTree("""
                {"judicialResults":[{"judicialResultPrompts":[
                  {"promptReference":"suretyNameAndAddressOrganisationName"}]}]}""");

            assertThat(matches(subscription, OU_CODE, adultOrYouthAtAnyCourt(),
                    resultsOf(notNameAddress))).isFalse();
        }

        @Test
        @DisplayName("Should include subscriptions if included results are matched with results")
        void should_include_subscriptions_if_included_results_are_matched() {
            // Repaired: the Jest case loads `Subscriptions.json`, whose subscription carries no
            // result lists at all, so it asserts nothing about results. This one loads the fixture
            // that has them.
            assertThat(matches(resultSubscription(), OU_CODE, adultOrYouthAtAnyCourt(),
                    judicialResults("judicial-results-with-included-prompts.json"))).isTrue();
        }

        @Test
        @DisplayName("does not match when no result is on the included list")
        void does_not_match_when_no_result_is_on_the_included_list() {
            assertThat(matches(resultSubscription(), OU_CODE, adultOrYouthAtAnyCourt(),
                    judicialResults("judicial-results-with-excluded-prompts.json"))).isFalse();
        }

        @Test
        @DisplayName("Should Not include subscriptions if excluded results are matched with results")
        void should_not_include_subscriptions_if_excluded_results_are_matched() {
            // Repaired: the Jest case leaves every vocabulary flag false, so it fails at the
            // attendance rule and never reaches the excluded list. Here the included list is removed
            // and everything else matches, so the exclusion is the only thing that can answer.
            final ObjectNode excludedOnly = resultSubscription();
            subscriptionVocabularyOf(excludedOnly).remove("includedResults");

            assertThat(matches(excludedOnly, OU_CODE, adultOrYouthAtAnyCourt(),
                    judicialResults("judicial-results-with-excluded-prompts.json"))).isFalse();
        }
    }

    /**
     * The NOWs routes, which a court register cannot take and which carry the included and excluded
     * NOWS lists with them.
     */
    @Nested
    @DisplayName("the NOWs lists this flow never reads")
    class NowsLists {

        @Test
        @DisplayName("matches although the subscription's excluded NOWS name the register's results")
        void matches_although_excluded_nows_name_the_results() {
            // `excludedNOWS` and `includedNOWS` are read in `matchSubscriptionRules`, which only the
            // `isNowSubscription` / `isEDTSubscription` branch reaches, and they are keyed on a
            // variant's `nowId`. `CourtRegisterSubscriptions/index.js:44-49` sets no `nowId`, so a
            // court register has none to be excluded by — before C5 and after it.
            final ObjectNode excluding = courtRegisterSubscription();
            excluding.putArray("excludedNOWS").add("10115268-8efc-49fe-b8e8-feee216a03da");

            assertThat(matches(excluding, OU_CODE, youthAtAnyCourt())).isTrue();
        }

        @Test
        @DisplayName("matches although the register is on none of the subscription's included NOWS")
        void matches_although_the_register_is_on_no_included_nows() {
            assertThat(matches(courtRegisterSubscription(), OU_CODE, youthAtAnyCourt())).isTrue();
        }
    }

    /**
     * The kernel's own Jest twins: the two written for the court register, and the four vocabulary
     * cases this flow shares with the NOWs one.
     */
    @Nested
    @DisplayName("SubscriptionsService — legacy Jest twins")
    class LegacyJestTwins {

        @Test
        @DisplayName("Should return the correct subscriptions for court register")
        void should_return_the_correct_subscriptions_for_court_register() {
            assertThat(matches(courtRegisterSubscription(), OU_CODE, youthAtAnyCourt())).isTrue();
        }

        @Test
        @DisplayName("Should return the correct subscriptions for court register 2")
        void should_return_the_correct_subscriptions_for_court_register_2() {
            // Repaired. The Jest case builds a local vocabulary — `anyCourtHearing`,
            // `adultOrYouthDefendant` — and then never uses it: the call passes the JSON-loaded
            // object, whose own vocabulary is what answers. Here the local vocabulary drives the
            // assertion, which is what the case set out to do.
            final JsonNode subscriptionObject = LegacyFixtures.read("subscriptionObject.json");

            assertThat(matches(subscriptionObject.get("subscriptions").get(0),
                    subscriptionObject.get("ouCode").stringValue(),
                    adultOrYouthAtAnyCourt(),
                    resultsOf(subscriptionObject))).isTrue();
        }

        @Test
        @DisplayName("does not match that subscription on a vocabulary satisfying neither rule")
        void does_not_match_that_subscription_on_a_vocabulary_satisfying_neither_rule() {
            // The control the repaired twin needs: without it, driving the assertion from the local
            // vocabulary proves nothing, because the fixture's own vocabulary matches too.
            final JsonNode subscriptionObject = LegacyFixtures.read("subscriptionObject.json");

            assertThat(matches(subscriptionObject.get("subscriptions").get(0),
                    subscriptionObject.get("ouCode").stringValue(),
                    vocabulary(),
                    resultsOf(subscriptionObject))).isFalse();
        }

        @Test
        @DisplayName("Should exclude subscriptions if subscription vocabulary is defined but NOT "
                + "matched")
        void should_exclude_subscriptions_if_vocabulary_is_defined_but_not_matched() {
            final ObjectNode subscription = courtRegisterSubscription();
            subscriptionVocabularyOf(subscription).put("anyAppearance", false);
            subscriptionVocabularyOf(subscription).put("appearedByVideoLink", true);

            assertThat(matches(subscription, OU_CODE, vocabulary())).isFalse();
        }

        @Test
        @DisplayName("Should include subscriptions if subscription vocabulary is defined AND matched")
        void should_include_subscriptions_if_vocabulary_is_defined_and_matched() {
            final ObjectNode subscription = courtRegisterSubscription();
            subscriptionVocabularyOf(subscription).setAll((ObjectNode) mapper.readTree("""
                {"appearedByVideoLink":true,"anyCourtHearing":true,"adultOrYouthDefendant":true,
                 "inCustody":true,"allNonCustodialResults":false,
                 "atleastOneNonCustodialResult":true,"atleastOneCustodialResult":true,
                 "isCpsProsecuted":false,"anyAppearance":false}"""));
            subscriptionVocabularyOf(subscription).remove("ignoreCustody");
            subscriptionVocabularyOf(subscription).remove("ignoreResults");

            assertThat(matches(subscription, OU_CODE, vocabulary(
                    "appearedByVideoLink", "anyCourtHearing", "adultOrYouthDefendant", "inCustody",
                    "atleastOneNonCustodialResult", "atleastOneCustodialResult"))).isTrue();
        }

        @Test
        @DisplayName("Should include subscriptions if subscription vocabulary is defined AND matched "
                + "with results")
        void should_include_subscriptions_if_vocabulary_is_matched_with_results() {
            final ObjectNode subscription = courtRegisterSubscription();
            subscriptionVocabularyOf(subscription).setAll((ObjectNode) mapper.readTree("""
                {"appearedByVideoLink":true,"anyCourtHearing":true,"adultOrYouthDefendant":true,
                 "inCustody":true,"allNonCustodialResults":true,
                 "atleastOneNonCustodialResult":true,"atleastOneCustodialResult":false,
                 "anyAppearance":false}"""));
            subscriptionVocabularyOf(subscription).remove("ignoreCustody");
            subscriptionVocabularyOf(subscription).remove("ignoreResults");

            assertThat(matches(subscription, OU_CODE, vocabulary(
                    "appearedByVideoLink", "anyCourtHearing", "adultOrYouthDefendant", "inCustody",
                    "allNonCustodialResults", "atleastOneNonCustodialResult"))).isTrue();
        }
    }

    /**
     * The kernel's court-register subscription: {@code Subscriptions.json}'s single entry with the
     * five vocabulary flags and the three subscription fields the kernel's own court-register case
     * sets on it.
     *
     * @return the subscription, as a mutable copy
     */
    private ObjectNode courtRegisterSubscription() {
        final ObjectNode subscription = subscriptionFrom("Subscriptions.json");
        subscriptionVocabularyOf(subscription).setAll((ObjectNode) mapper.readTree("""
            {"youthDefendant":true,"anyAppearance":true,"anyCourtHearing":true,
             "ignoreCustody":true,"ignoreResults":true}"""));
        return subscription;
    }

    /**
     * The kernel's prompt subscription, made a court-register one and given the same five flags.
     *
     * @return the subscription, as a mutable copy
     */
    private ObjectNode promptSubscription() {
        return withRegisterFlags(subscriptionFrom("subscriptions-with-prompts.json"));
    }

    /**
     * The kernel's included-and-excluded-results subscription, made a court-register one.
     *
     * @return the subscription, as a mutable copy
     */
    private ObjectNode resultSubscription() {
        return withRegisterFlags(subscriptionFrom("subscriptions-with-inc-exc-results.json"));
    }

    /**
     * Adds the vocabulary flags the kernel's court-register case sets, leaving every list the
     * fixture carries alone.
     *
     * @param subscription the subscription to flag
     * @return the same subscription
     */
    private ObjectNode withRegisterFlags(final ObjectNode subscription) {
        subscriptionVocabularyOf(subscription).setAll((ObjectNode) mapper.readTree("""
            {"anyAppearance":true,"anyCourtHearing":true,"adultOrYouthDefendant":true,
             "ignoreCustody":true,"ignoreResults":true}"""));
        return subscription;
    }

    /**
     * Loads a subscription fixture's single entry and makes it a court-register subscription for
     * this court centre — the two fields the kernel's own court-register case adds.
     *
     * @param fixture the fixture file name
     * @return a mutable copy of its first subscription
     */
    private ObjectNode subscriptionFrom(final String fixture) {
        final ObjectNode subscription =
                (ObjectNode) LegacyFixtures.read(fixture).get(0).deepCopy();
        subscription.put("isNowSubscription", false);
        subscription.put("isCourtRegisterSubscription", true);
        subscription.putArray("selectedCourtHouses").add(OU_CODE);
        return subscription;
    }

    /**
     * A subscription's own vocabulary block, for a case that needs to change one flag of it.
     *
     * @param subscription the subscription
     * @return its vocabulary block
     */
    private ObjectNode subscriptionVocabularyOf(final ObjectNode subscription) {
        return (ObjectNode) subscription.get("subscriptionVocabulary");
    }

    /**
     * A register vocabulary, written the way the Jest suite writes one: every flag false except the
     * ones this case names. The two creditor lists are always empty, which is the C30 shape.
     *
     * @param trueFlags the flags to turn on
     * @return the vocabulary
     */
    private RegisterVocabulary vocabulary(final String... trueFlags) {
        final Set<String> on = Set.of(trueFlags);
        return new RegisterVocabulary(
                on.contains("custodyLocationIsPolice"),
                on.contains("custodyLocationIsPrison"),
                on.contains("atleastOneCustodialResult"),
                on.contains("allNonCustodialResults"),
                on.contains("atleastOneNonCustodialResult"),
                on.contains("appearedInPerson"),
                on.contains("appearedByVideoLink"),
                on.contains("isCpsProsecuted"),
                on.contains("anyAppearance"),
                on.contains("inCustody"),
                on.contains("youthDefendant"),
                on.contains("adultDefendant"),
                on.contains("adultOrYouthDefendant"),
                on.contains("welshCourtHearing"),
                on.contains("englishCourtHearing"),
                on.contains("anyCourtHearing"),
                List.of(),
                List.of());
    }

    /**
     * The same, for a parameterised table that carries its flags as one space-separated cell.
     *
     * @param trueFlags the flags to turn on, space separated; {@code -} for none
     * @return the vocabulary
     */
    private RegisterVocabulary vocabularyOf(final String trueFlags) {
        return vocabulary(trueFlags.trim().split(" "));
    }

    /**
     * The vocabulary of a youth at any court — enough to satisfy the kernel's court-register
     * subscription and nothing more.
     *
     * @return the vocabulary
     */
    private RegisterVocabulary youthAtAnyCourt() {
        return vocabulary("anyCourtHearing", "youthDefendant");
    }

    /**
     * The vocabulary the prompt and result cases match on.
     *
     * @return the vocabulary
     */
    private RegisterVocabulary adultOrYouthAtAnyCourt() {
        return vocabulary("anyCourtHearing", "adultOrYouthDefendant");
    }

    /**
     * The judicial results a fixture carries.
     *
     * @param fixture the fixture file name
     * @return its judicial results
     */
    private List<JsonNode> judicialResults(final String fixture) {
        return resultsOf(LegacyFixtures.read(fixture));
    }

    /**
     * The judicial results of a document carrying a {@code judicialResults} array.
     *
     * @param document the document
     * @return its judicial results
     */
    private List<JsonNode> resultsOf(final JsonNode document) {
        final List<JsonNode> results = new ArrayList<>();
        document.get("judicialResults").forEach(results::add);
        return List.copyOf(results);
    }

    /**
     * Asks the rules about a register carrying no judicial results, which is the shape every case
     * outside the prompt and result nests is about.
     *
     * @param subscription the subscription
     * @param ouCode       the court centre's OU code
     * @param vocabulary   the defendant's vocabulary
     * @return whether the subscription is matched
     */
    private boolean matches(
            final JsonNode subscription, final String ouCode, final RegisterVocabulary vocabulary) {
        return matches(subscription, ouCode, vocabulary, List.of());
    }

    /**
     * Asks the rules.
     *
     * @param subscription    the subscription
     * @param ouCode          the court centre's OU code
     * @param vocabulary      the defendant's vocabulary
     * @param judicialResults the register's judicial results
     * @return whether the subscription is matched
     */
    private boolean matches(
            final JsonNode subscription,
            final String ouCode,
            final RegisterVocabulary vocabulary,
            final List<JsonNode> judicialResults) {
        return rules.matches(subscription, ouCode, vocabulary, judicialResults);
    }
}
