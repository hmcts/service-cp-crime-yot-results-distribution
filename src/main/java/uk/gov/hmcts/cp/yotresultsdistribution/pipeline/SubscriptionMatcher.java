package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterFragment;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterResult;

/**
 * Who a built register is addressed to.
 *
 * <p>Ports the deciding half of {@code CourtRegisterSubscriptions/index.js}: given the subscriptions
 * in force on the register's day, keep the court-register ones and ask {@link SubscriptionRules}
 * about each.
 *
 * <p><strong>The read that produces them is not made here.</strong> The legacy activity both reads
 * reference data and matches against it; this class does only the second half, and the answer
 * arrives as an argument. The constitution requires the whole fragment/matching/mapping chain to be
 * pure — no I/O, no clock, no randomness (Principle V) — and a stage that reaches a port of its own
 * cannot be tested against fixtures alone. {@code DistributionPipeline} makes the read, between this
 * stage and the one before it, and hands the answer in.
 *
 * <p><strong>Defect C31 is fixed here.</strong> {@code index.js:49} builds the matcher's input with
 * {@code subscriptionObj.vocabulary = registerDefendants[0].vocabulary} — one vocabulary, taken from
 * whichever defendant the hearing happened to gather first. A register carries every defendant the
 * hearing gathered, adults included, because the youth filter runs a stage later; so an adult-first
 * hearing has its youth register matched against adult vocabulary, and a subscription keyed on
 * {@code youthDefendant} — which is what a court-register subscription is keyed on — matches nothing.
 * The register is built, completes successfully, and reaches nobody. Matching is evaluated per
 * defendant instead: a subscription matches if <em>any</em> register defendant's vocabulary
 * satisfies it.
 *
 * <p>The judicial results handed to the rules are unchanged: they are collected across all
 * defendants ({@code index.js:53-63}), which is the asymmetry that makes the single-vocabulary read
 * look deliberate and is not.
 */
public final class SubscriptionMatcher {

    private final SubscriptionRules rules;

    /**
     * Creates the matcher over the rules it asks.
     *
     * @param rules whether one subscription wants one defendant's register
     */
    public SubscriptionMatcher(final SubscriptionRules rules) {
        this.rules = rules;
    }

    /**
     * The subscriptions this register is addressed to.
     *
     * @param fragment the built register
     * @param answer   reference data's now-subscriptions answer for the register's own day, already
     *                 read by the core
     * @return the matched subscriptions, in the order reference data returned them; empty where
     *     nothing matched, which is an answer and not a failure
     */
    public List<JsonNode> match(final RegisterFragment fragment, final JsonNode answer) {
        // `index.js:22` — reference data answering with no `nowSubscriptions` is still an answer,
        // and the register completes addressed to nobody. Not answering at all throws out of the
        // port the core reads through and never reaches here, which is the half of the legacy's
        // single case that stops being a completion.
        final List<JsonNode> inForce = Json.array(answer, "nowSubscriptions");
        final String ouCode = fragment.courtCentreOUCode();
        final List<JsonNode> judicialResults = judicialResultsOf(fragment);

        // The legacy's two remaining guards — no subscriptions in force, and a register that
        // gathered no defendants (`index.js:26-29`) — need no branch of their own: an empty set in
        // force filters to nothing, and a register with no defendants satisfies nothing. Both end
        // where the legacy's early returns end, at a register addressed to nobody.
        return inForce.stream()
                .filter(subscription -> Json.truthy(subscription, "isCourtRegisterSubscription"))
                .filter(subscription -> wantedByAnyDefendant(
                        subscription, ouCode, fragment, judicialResults))
                .toList();
    }

    /**
     * Whether any register defendant's vocabulary satisfies this subscription — defect fix C31.
     *
     * <p>The subscription is the outer loop and the defendants the inner one, which is what keeps
     * the answer in reference data's own order: recipient order is the order the register's emails
     * are queued in, and walking defendants outermost would reorder it. A subscription that several
     * defendants satisfy is still one recipient.
     *
     * @param subscription    the subscription being asked about
     * @param ouCode          the court centre's OU code
     * @param fragment        the built register
     * @param judicialResults every judicial result the register gathered
     * @return whether the subscription wants this register
     */
    private boolean wantedByAnyDefendant(
            final JsonNode subscription,
            final String ouCode,
            final RegisterFragment fragment,
            final List<JsonNode> judicialResults) {

        return fragment.registerDefendants().stream()
                .anyMatch(defendant -> rules.matches(
                        subscription, ouCode, defendant.vocabulary(), judicialResults));
    }

    /**
     * {@code collectJudicialResults}: every defendant's results, flattened, in gather order.
     *
     * <p>Unchanged from the legacy, and deliberately so — it is the asymmetry that makes the
     * single-vocabulary read at {@code index.js:49} look deliberate. The prompt and result rules
     * have always been asked about the whole hearing; only the vocabulary was narrowed to one
     * defendant.
     *
     * @param fragment the built register
     * @return the judicial results, in the order the defendants were gathered
     */
    private static List<JsonNode> judicialResultsOf(final RegisterFragment fragment) {
        return fragment.registerDefendants().stream()
                .flatMap(defendant -> defendant.results().stream())
                .map(RegisterResult::judicialResult)
                .filter(Objects::nonNull)
                .toList();
    }
}
