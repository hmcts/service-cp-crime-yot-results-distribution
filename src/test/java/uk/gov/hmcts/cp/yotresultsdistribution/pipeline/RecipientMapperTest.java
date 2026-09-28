package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationAnomaly;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;
import uk.gov.hmcts.cp.yotresultsdistribution.support.LegacyFixtures;

/**
 * Who the register is emailed to, and who quietly stops receiving it.
 *
 * <p>Twins the four cases of {@code $DF/…/Mappers/Recipient/test/RecipientMapper.test.js}. Two of
 * them are about the mapper (a populated subscription, and one with no recipient) and two about its
 * one-line trimming helper. Between them they leave the field that decides which template the email
 * is sent from unasserted, and every one of the mapper's four drop paths unexercised.
 *
 * <p><strong>{@code cr_standard} is pinned here for the first time.</strong> The legacy R1 fixture
 * puts {@code emailTemplateName} inside the {@code recipient} block, while
 * {@code RecipientMapper.js:20-22} reads it off the <em>subscription</em>. So the default is what
 * that case actually produces and the assertion it never makes; the twin makes it.
 *
 * <p><strong>Defect C27 is the silence, not the drops.</strong> This is an email channel: a
 * subscription asking for first- or second-class post cannot be served by it, and a recipient with
 * no email address cannot be written to. Those drops are product behaviour and they stay. What the
 * legacy does not do is say so — no log line, no count, not even the one its informant sibling
 * writes — so a court centre whose only subscriber is a letter subscription produces a register that
 * reaches nobody and reports success. Every drop below is WARN-logged and counted through the
 * anomaly recorder, which is what reaches {@code processed_output.anomaly_summary} as
 * {@code letter-delivery-dropped:n}, {@code recipient-missing-email:n} and
 * {@code recipient-not-for-distribution:n}, and the anomaly metric with it.
 *
 * <p>The three codes divide the mapper's single {@code if} ({@code :13}) by the reason a
 * subscription failed it, which is the only division an operator can act on:
 *
 * <ol>
 *   <li>a subscription asking for letter delivery — a channel this service does not have;</li>
 *   <li>a subscription not marked for email delivery or not for distribution — reference data
 *       saying "not this one";</li>
 *   <li>a subscription that is for us and carries no address to send to — reference data that is
 *       incomplete.</li>
 * </ol>
 *
 * <p>Where nothing survives, the answer is nothing rather than an empty list. The distinction is
 * load-bearing twice over: the contract puts {@code minItems: 1} on the recipient array, and the
 * assembled register with no recipients is the {@code no-subscriptions} completion rather than a
 * document posted to an empty audience.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> row C27
 */
@DisplayName("RecipientMapper")
class RecipientMapperTest {

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    /** Every drop the mapper counted, in the order it counted them. */
    private final List<TransformationAnomaly> anomalies = new ArrayList<>();

    @Nested
    @DisplayName("Recipient Mapper > Should return correct values — R1")
    class PopulatedSubscription {

        @Test
        @DisplayName("the organisation's name is the recipient's name")
        void the_organisation_name_is_the_recipient_name() {
            assertThat(legacyRecipients()).hasSize(1);
            assertThat(legacyRecipients().get(0).recipientName()).isEqualTo("John Smith");
        }

        @Test
        @DisplayName("both email addresses are carried")
        void both_email_addresses_are_carried() {
            // The second is mapped, carried and — the informant flow's open item R3 — used by
            // nothing downstream. Pinned so that its removal is a decision rather than a tidy-up.
            assertThat(legacyRecipients().get(0).emailAddress1()).isEqualTo("some email");
            assertThat(legacyRecipients().get(0).emailAddress2()).isEqualTo("email two");
        }

        @Test
        @DisplayName("the template defaults to cr_standard, which no legacy case observes")
        void the_template_defaults_to_cr_standard() {
            // R1's fixture carries `emailTemplateName` on the recipient; the mapper reads it off the
            // subscription. So the legacy case runs the default branch and asserts nothing about it.
            assertThat(legacyRecipients().get(0).emailTemplateName()).isEqualTo("cr_standard");
        }

        @Test
        @DisplayName("a template named on the subscription is the one carried")
        void a_named_template_is_carried() {
            final ObjectNode subscription = deliverable();
            subscription.put("emailTemplateName", "cr_youth");

            assertThat(map(subscription).get(0).emailTemplateName()).isEqualTo("cr_youth");
        }

        @Test
        @DisplayName("a template named as an empty string falls back to cr_standard")
        void an_empty_template_name_falls_back() {
            // `subscription.emailTemplateName ? … : 'cr_standard'` — an empty string is falsy, and
            // reference data has been seen carrying one.
            final ObjectNode subscription = deliverable();
            subscription.put("emailTemplateName", "");

            assertThat(map(subscription).get(0).emailTemplateName()).isEqualTo("cr_standard");
        }

        @Test
        @DisplayName("a recipient with one address carries no second one")
        void one_address_carries_no_second() {
            assertThat(map(deliverable()).get(0).emailAddress2()).isNull();
        }

        @Test
        @DisplayName("recipients come out in the order the subscriptions came in")
        void recipients_keep_their_order() {
            final List<CourtRegisterRecipient> recipients = map(matchedSubscriptions());

            assertThat(recipients)
                    .extracting(CourtRegisterRecipient::recipientName)
                    .containsExactly(
                            "Youth Offending Service - South West London",
                            "Lavender Hill Youth Panel");
        }

        @Test
        @DisplayName("a real matched pair carries one named template and one default")
        void a_real_matched_pair_carries_a_template_each() {
            assertThat(map(matchedSubscriptions()))
                    .extracting(CourtRegisterRecipient::emailTemplateName)
                    .containsExactly("cr_youth", "cr_standard");
        }
    }

    @Nested
    @DisplayName("addresses are trimmed — R3 and R4")
    class Trimming {

        @Test
        @DisplayName("a trailing space does not travel with the address")
        void a_trailing_space_does_not_travel() {
            assertThat(map(matchedSubscriptions()).get(0).emailAddress1())
                    .isEqualTo("yos.southwest@example.gov.uk");
        }

        @Test
        @DisplayName("the second address is trimmed on the same terms as the first")
        void the_second_address_is_trimmed_too() {
            final ObjectNode subscription = deliverable();
            ((ObjectNode) subscription.get("recipient"))
                    .put("emailAddress2", "  BF.ReceptionSenior@sodexogov.co.uk  ");

            assertThat(map(subscription).get(0).emailAddress2())
                    .isEqualTo("BF.ReceptionSenior@sodexogov.co.uk");
        }

        @Test
        @DisplayName("an address that is nothing but space has nothing to send to")
        void an_address_that_is_only_space_is_nothing() {
            // R4's claim, at the level that matters: an address the trim empties is not an address.
            final ObjectNode subscription = deliverable();
            ((ObjectNode) subscription.get("recipient")).put("emailAddress1", "   ");

            assertThat(map(subscription)).isNull();
            assertThat(anomalies).containsExactly(TransformationAnomaly.RECIPIENT_MISSING_EMAIL);
        }

        @Test
        @DisplayName("a non-breaking space is space too, because trim() there says it is")
        void a_non_breaking_space_is_space_too() {
            // `RecipientMapper.js:36-38` calls String.prototype.trim(), which strips the whole
            // Unicode whitespace set. Java's String.trim() strips codepoints up to U+0020 and
            // String.strip() asks Character.isWhitespace, which answers false for U+00A0 — so a
            // padded address survives here and does not survive there, and the difference reaches
            // progression as an address with a space in it.
            final ObjectNode subscription = deliverable();
            ((ObjectNode) subscription.get("recipient"))
                    .put("emailAddress1", "\u00a0yos.southwest@example.gov.uk\u00a0");

            assertThat(map(subscription).get(0).emailAddress1())
                    .isEqualTo("yos.southwest@example.gov.uk");
        }

        @Test
        @DisplayName("an address that is nothing but non-breaking space is nothing either")
        void an_address_that_is_only_non_breaking_space_is_nothing() {
            final ObjectNode subscription = deliverable();
            ((ObjectNode) subscription.get("recipient")).put("emailAddress1", "\u00a0\u00a0");

            assertThat(map(subscription)).isNull();
            assertThat(anomalies).containsExactly(TransformationAnomaly.RECIPIENT_MISSING_EMAIL);
        }
    }

    @Nested
    @DisplayName("a subscription with no recipient — R2")
    class NoRecipient {

        @Test
        @DisplayName("answers nothing, not an empty list")
        void answers_nothing_not_an_empty_list() {
            // Absent is not empty. The contract puts `minItems: 1` on the recipient array, and an
            // empty one would be refused where an absent one is simply a register with no audience.
            final ObjectNode subscription = mapper.createObjectNode();
            subscription.put("emailDelivery", true);
            subscription.put("forDistribution", true);

            assertThat(map(subscription)).isNull();
        }

        @Test
        @DisplayName("is counted as a subscription with nowhere to send")
        void is_counted_as_having_nowhere_to_send() {
            final ObjectNode subscription = mapper.createObjectNode();
            subscription.put("emailDelivery", true);
            subscription.put("forDistribution", true);

            map(subscription);

            assertThat(anomalies).containsExactly(TransformationAnomaly.RECIPIENT_MISSING_EMAIL);
        }

        @Test
        @DisplayName("as is a recipient carrying no first address")
        void a_recipient_with_no_first_address_is_counted() {
            final ObjectNode subscription = deliverable();
            ((ObjectNode) subscription.get("recipient")).remove("emailAddress1");

            assertThat(map(subscription)).isNull();
            assertThat(anomalies).containsExactly(TransformationAnomaly.RECIPIENT_MISSING_EMAIL);
        }
    }

    @Nested
    @DisplayName("a subscription asking for the post (C27)")
    class LetterDelivery {

        @Test
        @DisplayName("first class is dropped, because this is an email channel")
        void first_class_is_dropped() {
            assertThat(map(letterSubscription("firstClassLetterDelivery"))).isNull();
        }

        @Test
        @DisplayName("second class is dropped on the same terms")
        void second_class_is_dropped() {
            assertThat(map(letterSubscription("secondClassLetterDelivery"))).isNull();
        }

        @Test
        @DisplayName("but a subscription asking for both post and email is still emailed")
        void a_mixed_channel_subscription_is_still_emailed() {
            // `subscription.emailDelivery && subscription.forDistribution && subscription.recipient`
            // (`:13`) — the letter flags are never read. A subscriber who wants the register by post
            // as well as by email is a subscriber this channel can serve, and reading the letter
            // flag first would take them off a register the legacy sends them.
            final ObjectNode both = deliverable();
            both.put("firstClassLetterDelivery", true);

            assertThat(map(both)).hasSize(1);
            assertThat(anomalies).isEmpty();
        }

        @Test
        @DisplayName("and the drop is counted under its own reason, where the legacy says nothing")
        void the_drop_is_counted_under_its_own_reason() {
            // The sharpest C27 case: a court centre whose only subscriber asks for post produces a
            // register that reaches nobody, and today reports success with no line in any log.
            map(letterSubscription("firstClassLetterDelivery"));

            assertThat(anomalies).containsExactly(TransformationAnomaly.LETTER_DELIVERY_DROPPED);
            assertThat(TransformationAnomaly.LETTER_DELIVERY_DROPPED.value())
                    .isEqualTo("letter-delivery-dropped");
        }
    }

    @Nested
    @DisplayName("a subscription reference data is not offering us")
    class NotForDistribution {

        @Test
        @DisplayName("is dropped when it is not marked for distribution")
        void not_for_distribution_is_dropped() {
            final ObjectNode subscription = deliverable();
            subscription.put("forDistribution", false);

            assertThat(map(subscription)).isNull();
            assertThat(anomalies)
                    .containsExactly(TransformationAnomaly.RECIPIENT_NOT_FOR_DISTRIBUTION);
        }

        @Test
        @DisplayName("is dropped when it is not marked for email delivery")
        void not_for_email_delivery_is_dropped() {
            final ObjectNode subscription = deliverable();
            subscription.put("emailDelivery", false);

            assertThat(map(subscription)).isNull();
            assertThat(anomalies)
                    .containsExactly(TransformationAnomaly.RECIPIENT_NOT_FOR_DISTRIBUTION);
        }

        @Test
        @DisplayName("is dropped when it says nothing about either")
        void a_silent_subscription_is_dropped() {
            final ObjectNode subscription = mapper.createObjectNode();
            subscription.set("recipient", recipient());

            assertThat(map(subscription)).isNull();
            assertThat(anomalies)
                    .containsExactly(TransformationAnomaly.RECIPIENT_NOT_FOR_DISTRIBUTION);
        }
    }

    @Nested
    @DisplayName("what the drops say out loud")
    class WhatTheDropsSay {

        @Test
        @DisplayName("each drop writes one warning, and is counted under its own reason")
        void every_dropped_recipient_is_logged_and_counted() {
            try (CapturedLog log = CapturedLog.capturing(RecipientMapper.class)) {
                map(letterSubscription("firstClassLetterDelivery"), notForDistribution());

                assertThat(warnings(log)).hasSize(2);
                assertThat(anomalies).containsExactly(
                        TransformationAnomaly.LETTER_DELIVERY_DROPPED,
                        TransformationAnomaly.RECIPIENT_NOT_FOR_DISTRIBUTION);
            }
        }

        @Test
        @DisplayName("a warning names the bounded reason and not the address")
        void a_warning_names_the_reason_and_not_the_address() {
            // Every recipient here is an organisation, but an email address is contact detail all
            // the same and these lines reach a shared log index. The reason is what an operator
            // needs; the address is what reference data can be asked for.
            try (CapturedLog log = CapturedLog.capturing(RecipientMapper.class)) {
                map(letterSubscription("firstClassLetterDelivery"));

                assertThat(warnings(log)).singleElement().satisfies(message -> assertThat(message)
                        .contains(TransformationAnomaly.LETTER_DELIVERY_DROPPED.value())
                        .doesNotContain("panel@example.gov.uk"));
            }
        }

        @Test
        @DisplayName("a subscription that survives is not warned about")
        void a_surviving_subscription_is_not_warned_about() {
            try (CapturedLog log = CapturedLog.capturing(RecipientMapper.class)) {
                map(deliverable());

                assertThat(warnings(log)).isEmpty();
                assertThat(anomalies).isEmpty();
            }
        }

        @Test
        @DisplayName("a drop alongside a survivor costs only the drop")
        void a_drop_alongside_a_survivor_costs_only_the_drop() {
            final List<CourtRegisterRecipient> recipients =
                    map(letterSubscription("secondClassLetterDelivery"), deliverable());

            assertThat(recipients).hasSize(1);
            assertThat(anomalies).containsExactly(TransformationAnomaly.LETTER_DELIVERY_DROPPED);
        }
    }

    @Nested
    @DisplayName("nothing to map")
    class NothingToMap {

        @Test
        @DisplayName("no subscriptions at all answers nothing")
        void no_subscriptions_answers_nothing() {
            assertThat(new RecipientMapper(anomalies::add).map(List.of())).isNull();
        }

        @Test
        @DisplayName("an unanswered subscription list answers nothing, and counts nothing")
        void an_unanswered_list_answers_nothing() {
            assertThat(new RecipientMapper(anomalies::add).map(null)).isNull();
            assertThat(anomalies).isEmpty();
        }
    }

    /**
     * R1's inline fragment, as the Jest case builds it: a deliverable subscription whose recipient
     * carries two addresses, an organisation name, and a template name in the place the mapper does
     * not read.
     *
     * @return the mapped recipients
     */
    private List<CourtRegisterRecipient> legacyRecipients() {
        final ObjectNode recipient = mapper.createObjectNode();
        recipient.put("emailAddress1", "some email");
        recipient.put("emailAddress2", "email two");
        recipient.put("emailTemplateName", "templatename");
        recipient.put("organisationName", "John Smith");

        final ObjectNode subscription = mapper.createObjectNode();
        subscription.set("recipient", recipient);
        subscription.put("emailDelivery", true);
        subscription.put("forDistribution", true);
        return map(subscription);
    }

    /**
     * The two subscriptions the rebuilt complete fragment carries: a youth-templated one whose
     * address needs trimming, and one that names no template.
     *
     * @return the subscriptions, in fragment order
     */
    private List<JsonNode> matchedSubscriptions() {
        return List.copyOf(LegacyFixtures
                .readRebuilt("outboundcourtregister/court-register-fragment-complete.json")
                .get("matchedSubscriptions").valueStream().toList());
    }

    /**
     * Runs the mapper over the given subscriptions, collecting whatever it drops.
     *
     * @param subscriptions the matched subscriptions
     * @return the recipients, or {@code null} where none survived
     */
    private List<CourtRegisterRecipient> map(final JsonNode... subscriptions) {
        return new RecipientMapper(anomalies::add).map(List.of(subscriptions));
    }

    /**
     * Runs the mapper over a list read from a fixture.
     *
     * @param subscriptions the matched subscriptions
     * @return the recipients, or {@code null} where none survived
     */
    private List<CourtRegisterRecipient> map(final List<JsonNode> subscriptions) {
        return new RecipientMapper(anomalies::add).map(subscriptions);
    }

    /** A subscription this service can serve: email delivery, for distribution, one address. */
    private ObjectNode deliverable() {
        final ObjectNode subscription = mapper.createObjectNode();
        subscription.put("emailDelivery", true);
        subscription.put("forDistribution", true);
        subscription.set("recipient", recipient());
        return subscription;
    }

    /** The same subscription, marked as not for distribution. */
    private ObjectNode notForDistribution() {
        final ObjectNode subscription = deliverable();
        subscription.put("forDistribution", false);
        return subscription;
    }

    /**
     * A subscription asking for post rather than email.
     *
     * @param deliveryField the letter-delivery field the subscription carries
     * @return the subscription
     */
    private ObjectNode letterSubscription(final String deliveryField) {
        final ObjectNode subscription = mapper.createObjectNode();
        subscription.put(deliveryField, true);
        subscription.put("emailDelivery", false);
        subscription.put("forDistribution", true);
        subscription.set("recipient", recipient());
        return subscription;
    }

    /** One reference-data recipient. */
    private ObjectNode recipient() {
        final ObjectNode recipient = mapper.createObjectNode();
        recipient.put("organisationName", "Lavender Hill Youth Panel");
        recipient.put("emailAddress1", "panel@example.gov.uk");
        return recipient;
    }

    /**
     * Every WARN line the capture holds.
     *
     * @param log the capture
     * @return the messages
     */
    private static List<String> warnings(final CapturedLog log) {
        return log.events().stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
