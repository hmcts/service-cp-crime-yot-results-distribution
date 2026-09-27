package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterFragment;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterResult;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterVocabulary;
import uk.gov.hmcts.cp.yotresultsdistribution.support.LegacyFixtures;

/**
 * Addressing a built register: which of the subscriptions in force it goes to.
 *
 * <p>The legacy {@code CourtRegisterSubscriptions} suite has four cases, and all four mock the
 * matcher out entirely — so what they pin is the plumbing around it: reference data answered, the
 * court-register subscriptions were filtered, a subscription object was built. The twins below drive
 * the same four shapes through the real rules instead, which is what makes the two things the mocks
 * hid observable: which court-centre code reaches the rules, and whose vocabulary they are asked
 * about.
 *
 * <p>Two fixes meet here.
 *
 * <ul>
 *   <li><strong>C31</strong> — the legacy matches on {@code registerDefendants[0].vocabulary}, one
 *       vocabulary for a list the youth filter has not yet been applied to. Every court-register
 *       fixture in the legacy repo carries exactly one defendant, so the defect is unobservable
 *       there; the cases below carry two.</li>
 *   <li><strong>The CS1 split</strong> — the legacy's first case asserts that "no subscription"
 *       leaves the register with no recipients, and reaches that state from a reference-data call
 *       that answered nothing at all. Those are two different outcomes: an empty answer completes
 *       the run as {@code no-subscriptions}, and no answer is a transient failure. Conflating them
 *       reports a reference-data outage as this flow's commonest legitimate result.</li>
 * </ul>
 *
 * <p>Only the first of those two is asserted here. The stage is pure — reference data's answer is an
 * argument, not something it fetches (constitution Principle V) — so the read, the day it is made
 * for, the identity it is made as and its refusal to answer are asserted in
 * {@code DistributionPipelineTest}, at the seam that makes them.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> row C31
 */
@DisplayName("SubscriptionMatcher")
class SubscriptionMatcherTest {

    /** The court centre the fragments below sat at. */
    private static final String OU_CODE = "OU_CODE";

    /** The instant the register's results were shared. */
    private static final String REGISTER_DATE = "2020-06-01T10:00:00Z";

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    /**
     * The twins of the legacy {@code CourtRegisterSubscriptions} Jest suite, driven through the real
     * rules.
     */
    @Nested
    @DisplayName("CourtRegisterSubscriptions — legacy Jest twins")
    class LegacyJestTwins {

        @Test
        @DisplayName("Should set matchedSubscriptions property of court register fragment object")
        void should_set_matched_subscriptions() {
            assertThat(match(answering(subscriptions(courtRegisterSubscription("youthDefendant"))),
                    fragmentFor(youth()))).hasSize(1);
        }

        @Test
        @DisplayName("Should set multiple matchedSubscriptions property of court register fragment "
                + "object")
        void should_set_multiple_matched_subscriptions() {
            assertThat(match(answering(subscriptions(
                    courtRegisterSubscription("youthDefendant"),
                    courtRegisterSubscription("adultOrYouthDefendant"))),
                    fragmentFor(youth()))).hasSize(2);
        }

        @Test
        @DisplayName("Should filter out the subscription which is not court register subscription")
        void should_filter_out_the_subscription_which_is_not_a_court_register_one() {
            // The Jest case asserts the filter by inspecting the argument handed to a mocked
            // matcher. This asserts the answer instead, which is what the register is addressed by:
            // a NOWs subscription selecting this very court house is not among the recipients.
            final ObjectNode nowsSubscription = courtRegisterSubscription("youthDefendant");
            nowsSubscription.put("isCourtRegisterSubscription", false);
            nowsSubscription.put("isNowSubscription", true);

            final List<JsonNode> matched = match(
                    answering(subscriptions(
                            courtRegisterSubscription("youthDefendant"), nowsSubscription)),
                    fragmentFor(youth()));

            assertThat(matched).hasSize(1);
            assertThat(matched.get(0).get("isCourtRegisterSubscription").booleanValue()).isTrue();
        }

        @Test
        @DisplayName("matches on the court centre's own OU code and no other")
        void matches_on_the_court_centres_own_ou_code() {
            // The repair the Jest case needs. It compares a built `SubscriptionObject` with
            // `toStrictEqual`, and both sides' `ouCode` are `undefined` — the fragment class in the
            // test has no `courtCentreOUCode` property at all — so the one field the court-house
            // rule turns on is asserted by nothing. Here the register's own code is what decides.
            final ObjectNode elsewhere = courtRegisterSubscription("youthDefendant");
            elsewhere.putArray("selectedCourtHouses").add("SOMEWHERE_ELSE");

            assertThat(match(answering(subscriptions(elsewhere)), fragmentFor(youth()))).isEmpty();
            assertThat(match(answering(subscriptions(courtRegisterSubscription("youthDefendant"))),
                    fragmentFor(youth()))).hasSize(1);
        }
    }

    /**
     * The CS1 split, this half of it: reference data answering "none in force" is an answer, and the
     * register completes addressed to nobody. The other half — reference data not answering at all,
     * which is a failure the delivery comes back for — is asserted where the read is made.
     */
    @Nested
    @DisplayName("when nothing matches")
    class NothingMatches {

        @Test
        @DisplayName("Should NOT set matchedSubscriptions property when there is NO subscription")
        void an_empty_subscription_set_matches_nobody() {
            assertThat(match(answering("{\"nowSubscriptions\":[]}"), fragmentFor(youth())))
                    .isEmpty();
        }

        @Test
        @DisplayName("matches nobody when the answer carries no subscriptions field at all")
        void an_answer_carrying_no_subscriptions_field_matches_nobody() {
            // `!subscriptionsMetaData.nowSubscriptions` — reference data answering with a body that
            // names no subscriptions is still an answer, and the register completes with no
            // recipients.
            assertThat(match(answering("{}"), fragmentFor(youth()))).isEmpty();
        }

        @Test
        @DisplayName("matches nobody when none of the subscriptions in force is a court register one")
        void matches_nobody_when_none_is_a_court_register_subscription() {
            final ObjectNode nowsSubscription = courtRegisterSubscription("youthDefendant");
            nowsSubscription.put("isCourtRegisterSubscription", false);

            assertThat(match(answering(subscriptions(nowsSubscription)), fragmentFor(youth())))
                    .isEmpty();
        }

        @Test
        @DisplayName("matches nobody for a register that gathered no defendants")
        void matches_nobody_for_a_register_with_no_defendants() {
            assertThat(match(answering(subscriptions(courtRegisterSubscription("youthDefendant"))),
                    new RegisterFragment("cc-1", REGISTER_DATE, "2020-01-20T00:00:00Z", "hearing-1",
                            List.of(), OU_CODE))).isEmpty();
        }
    }

    /**
     * Defect C31, in the shape the legacy repo has no fixture for: a hearing whose first defendant is
     * an adult and whose second is the youth the register exists for.
     */
    @Nested
    @DisplayName("matching across every defendant (C31)")
    class EveryDefendant {

        @Test
        @DisplayName("any defendants vocabulary can match")
        void any_defendants_vocabulary_can_match() {
            // The legacy matches this register against the adult's vocabulary, the only one it
            // looks at, so a youth-keyed subscription — which is what a court-register subscription
            // is — matches nothing and the register reaches nobody, successfully.
            assertThat(match(answering(subscriptions(courtRegisterSubscription("youthDefendant"))),
                    fragmentFor(adult(), youth()))).hasSize(1);
        }

        @Test
        @DisplayName("still matches a subscription keyed on the first defendant's vocabulary")
        void still_matches_a_subscription_keyed_on_the_first_defendants_vocabulary() {
            // The fix widens the match; it must not move it. An adult-keyed subscription still
            // matches the same adult-first register.
            assertThat(match(answering(subscriptions(courtRegisterSubscription("adultDefendant"))),
                    fragmentFor(adult(), youth()))).hasSize(1);
        }

        @Test
        @DisplayName("matches nobody when no defendant's vocabulary satisfies the subscription")
        void matches_nobody_when_no_defendants_vocabulary_satisfies_it() {
            // The control without which "any defendant can match" would pass on a stage that matched
            // everything.
            final ObjectNode welshOnly = courtRegisterSubscription("youthDefendant");
            subscriptionVocabularyOf(welshOnly).put("anyCourtHearing", false);
            subscriptionVocabularyOf(welshOnly).put("welshCourtHearing", true);

            assertThat(match(answering(subscriptions(welshOnly)), fragmentFor(adult(), youth())))
                    .isEmpty();
        }

        @Test
        @DisplayName("returns a subscription both defendants match exactly once")
        void returns_a_subscription_both_defendants_match_once() {
            // Per-defendant evaluation, one recipient list: a subscription that both an adult and a
            // youth satisfy must not be emailed the same register twice.
            assertThat(match(answering(subscriptions(
                    courtRegisterSubscription("adultOrYouthDefendant"))),
                    fragmentFor(adult(), youth()))).hasSize(1);
        }

        @Test
        @DisplayName("collects the judicial results across every defendant, not only the first")
        void collects_the_judicial_results_across_every_defendant() {
            // The asymmetry that makes the legacy's single-vocabulary read look deliberate: the
            // results the prompt and result rules are asked about have always been gathered from
            // every defendant. This subscription's included prompt is carried only by the second
            // defendant's result.
            final ObjectNode promptSubscription = courtRegisterSubscription("adultOrYouthDefendant");
            subscriptionVocabularyOf(promptSubscription).set("includedPrompts", mapper.readTree(
                    "[{\"resultPromptReference\":\"suretyNameAndAddress\"}]"));

            final RegisterFragment fragment = new RegisterFragment(
                    "cc-1", REGISTER_DATE, "2020-01-20T00:00:00Z", "hearing-1",
                    List.of(defendant("master-adult", adult(), "{}"),
                            defendant("master-youth", youth(),
                                    "{\"judicialResultPrompts\":"
                                            + "[{\"promptReference\":\"suretyNameAndAddress\"}]}")),
                    OU_CODE);

            assertThat(match(answering(subscriptions(promptSubscription)), fragment)).hasSize(1);
        }

        @Test
        @DisplayName("returns the matched subscriptions in the order reference data gave them")
        void returns_them_in_reference_datas_order() {
            // Recipient order is the order the register's emails are queued in; a per-defendant
            // evaluation that walked defendants outermost would reorder it.
            final ObjectNode first = courtRegisterSubscription("adultDefendant");
            first.put("id", "first");
            final ObjectNode second = courtRegisterSubscription("youthDefendant");
            second.put("id", "second");

            assertThat(match(answering(subscriptions(first, second)), fragmentFor(adult(), youth())))
                    .extracting(subscription -> subscription.get("id").stringValue())
                    .containsExactly("first", "second");
        }
    }

    /**
     * A subscription for this court centre, keyed on one defendant flag, with the appearance,
     * court-hearing and ignore flags the kernel's own court-register case sets.
     *
     * @param defendantFlag the defendant flag the subscription is keyed on
     * @return the subscription, as a mutable copy
     */
    private ObjectNode courtRegisterSubscription(final String defendantFlag) {
        final ObjectNode subscription =
                (ObjectNode) LegacyFixtures.read("Subscriptions.json").get(0).deepCopy();
        subscription.put("isNowSubscription", false);
        subscription.put("isCourtRegisterSubscription", true);
        subscription.putArray("selectedCourtHouses").add(OU_CODE);
        subscriptionVocabularyOf(subscription).setAll((ObjectNode) mapper.readTree("""
            {"anyAppearance":true,"anyCourtHearing":true,"ignoreCustody":true,
             "ignoreResults":true}"""));
        subscriptionVocabularyOf(subscription).put(defendantFlag, true);
        return subscription;
    }

    /**
     * A subscription's own vocabulary block.
     *
     * @param subscription the subscription
     * @return its vocabulary block
     */
    private ObjectNode subscriptionVocabularyOf(final ObjectNode subscription) {
        return (ObjectNode) subscription.get("subscriptionVocabulary");
    }

    /**
     * A reference-data answer carrying the given subscriptions.
     *
     * @param subscriptions the subscriptions in force
     * @return the answer, as JSON text
     */
    private String subscriptions(final JsonNode... subscriptions) {
        final StringBuilder answer = new StringBuilder("{\"nowSubscriptions\":[");
        for (int index = 0; index < subscriptions.length; index++) {
            answer.append(index == 0 ? "" : ",").append(subscriptions[index]);
        }
        return answer.append("]}").toString();
    }

    /**
     * Reference data's answer, as this stage receives it.
     *
     * @param answer the answer, as JSON text
     * @return the answer, parsed
     */
    private JsonNode answering(final String answer) {
        return mapper.readTree(answer);
    }

    /**
     * The vocabulary of an adult at an English court.
     *
     * @return the vocabulary
     */
    private RegisterVocabulary adult() {
        return vocabulary("adultDefendant", "adultOrYouthDefendant", "englishCourtHearing",
                "anyCourtHearing");
    }

    /**
     * The vocabulary of a youth at an English court.
     *
     * @return the vocabulary
     */
    private RegisterVocabulary youth() {
        return vocabulary("youthDefendant", "adultOrYouthDefendant", "englishCourtHearing",
                "anyCourtHearing");
    }

    /**
     * A register vocabulary with the named flags on and every other flag off.
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
     * A fragment for this court centre carrying the given defendants, in the order given.
     *
     * @param vocabularies one vocabulary per defendant
     * @return the fragment
     */
    private RegisterFragment fragmentFor(final RegisterVocabulary... vocabularies) {
        final List<RegisterDefendant> defendants = new ArrayList<>();
        for (int index = 0; index < vocabularies.length; index++) {
            defendants.add(defendant("master-" + index, vocabularies[index], "{}"));
        }
        return new RegisterFragment("cc-1", REGISTER_DATE, "2020-01-20T00:00:00Z", "hearing-1",
                List.copyOf(defendants), OU_CODE);
    }

    /**
     * One register defendant carrying a single judicial result.
     *
     * @param masterDefendantId their identity across cases
     * @param vocabulary        their vocabulary
     * @param judicialResult    their one judicial result, as JSON text
     * @return the defendant
     */
    private RegisterDefendant defendant(
            final String masterDefendantId,
            final RegisterVocabulary vocabulary,
            final String judicialResult) {

        return new RegisterDefendant(
                List.of(masterDefendantId),
                List.of(new RegisterResult(null, null, null, null, null, masterDefendantId,
                        mapper.readTree(judicialResult), null, null)),
                List.of(), List.of(), masterDefendantId, vocabulary.youthDefendant(),
                "2020-01-20", vocabulary);
    }

    /**
     * Matches a fragment against reference data's answer.
     *
     * @param answer   reference data's now-subscriptions answer
     * @param fragment the built register
     * @return the matched subscriptions
     */
    private List<JsonNode> match(final JsonNode answer, final RegisterFragment fragment) {
        return new SubscriptionMatcher(new SubscriptionRules()).match(fragment, answer);
    }
}
