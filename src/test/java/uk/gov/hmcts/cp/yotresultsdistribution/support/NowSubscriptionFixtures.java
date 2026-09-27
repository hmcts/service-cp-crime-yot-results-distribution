package uk.gov.hmcts.cp.yotresultsdistribution.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;

/**
 * Reference data's answer, shaped the way the now-subscriptions read returns it.
 *
 * <p>Shared because more than one suite needs a subscription that really matches a base hearing, and
 * a second hand-built copy of the same object is how two suites come to disagree about what
 * "matches" means. The subscriptions themselves are the legacy fixture's, repaired for the
 * court-register branch: the legacy set is NOW subscriptions, and a court register is addressed by
 * {@code isCourtRegisterSubscription} (fix C5).
 */
public final class NowSubscriptionFixtures {

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    private NowSubscriptionFixtures() {
        // Static fixture holder.
    }

    /**
     * Reference data's answer for a register's day.
     *
     * @param subscriptions the subscriptions in force
     * @return the answer, under the {@code nowSubscriptions} key the adapter reads
     */
    public static JsonNode answerOf(final JsonNode... subscriptions) {
        final ArrayNode inForce = MAPPER.createArrayNode();
        for (final JsonNode subscription : subscriptions) {
            inForce.add(subscription);
        }
        return MAPPER.createObjectNode().set("nowSubscriptions", inForce);
    }

    /**
     * A court-register subscription for one court house, keyed on youth defendants and reachable by
     * email — the shape that produces a register with a recipient on it.
     *
     * @param ouCode the court house's OU code, which is the hearing's own court centre code
     * @return the subscription
     */
    public static ObjectNode youthCourtRegisterSubscription(final String ouCode) {
        final ObjectNode subscription =
                (ObjectNode) LegacyFixtures.read("Subscriptions.json").get(0).deepCopy();
        subscription.put("isNowSubscription", false);
        subscription.put("isCourtRegisterSubscription", true);
        subscription.put("emailTemplateName", "cr_youth");
        subscription.putArray("selectedCourtHouses").add(ouCode);

        final ObjectNode vocabulary = (ObjectNode) subscription.get("subscriptionVocabulary");
        vocabulary.setAll((ObjectNode) MAPPER.readTree("""
            {"anyAppearance":true,"anyCourtHearing":true,"ignoreCustody":true,
             "ignoreResults":true}"""));
        vocabulary.put("youthDefendant", true);

        final ObjectNode recipient = (ObjectNode) subscription.get("recipient");
        recipient.put("organisationName", "Youth Offending Service - South West London");
        recipient.put("emailAddress1", "yos.southwest@example.gov.uk");
        return subscription;
    }

    /**
     * The same subscriber, keyed on any defendant rather than on a youth.
     *
     * <p>What it is for is the {@code no-youth-defendants} outcome, which is only reachable through a
     * subscription that matches: the youth filter runs a stage after the matching, so a hearing whose
     * defendants are all adults must first be <em>addressed</em> and then found to have nobody on it.
     * A youth-keyed subscription would answer {@code no-subscriptions} instead, which is a different
     * completion reason and a different fault to diagnose.
     *
     * @param ouCode the court house's OU code
     * @return the subscription
     */
    public static ObjectNode forAnyDefendant(final String ouCode) {
        final ObjectNode subscription = youthCourtRegisterSubscription(ouCode);
        final ObjectNode vocabulary = (ObjectNode) subscription.get("subscriptionVocabulary");
        vocabulary.put("youthDefendant", false);
        vocabulary.put("adultOrYouthDefendant", true);
        return subscription;
    }

    /**
     * The same subscriber, asking for the post instead of email — a channel this service cannot
     * serve, so the subscription is dropped before the register is addressed (C36).
     *
     * @param ouCode the court house's OU code
     * @return the subscription
     */
    public static ObjectNode byFirstClassPost(final String ouCode) {
        final ObjectNode subscription = youthCourtRegisterSubscription(ouCode);
        subscription.put("emailDelivery", false);
        subscription.put("firstClassLetterDelivery", true);
        return subscription;
    }
}
