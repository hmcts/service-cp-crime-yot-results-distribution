package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationAnomaly;

/**
 * Maps the matched subscriptions onto the organisations the register is emailed to.
 *
 * <p>Ports {@code .../Mappers/Recipient/RecipientMapper.js}. A subscription becomes a recipient when
 * it is marked for email delivery and for distribution and carries a recipient ({@code :12});
 * addresses are trimmed; the template name falls back to {@code cr_standard} ({@code :20-22}); and a
 * recipient with no first address is dropped ({@code :24-26}), because the contract requires one.
 * Where nothing survives, the answer is nothing rather than an empty list — and the register is then
 * never posted at all.
 *
 * <p><strong>Defect C27 is the silence, not the drops.</strong> The drops are product behaviour and
 * they stay: this is an email-only channel, so a first- or second-class-letter subscription cannot
 * be served. What the legacy does not do is say so — not a log line, not a count, where even its
 * informant sibling logs. Every drop is counted here through {@code anomalies}, which is what
 * reaches {@code processed_output.anomaly_summary} and the anomaly metric.
 *
 * <p>The single legacy {@code if} is divided here by the <em>reason</em> a subscription failed it,
 * which is the only division an operator can act on: a subscription asking for the post, which this
 * channel does not have; a subscription reference data is not offering us; and a subscription that
 * is for us and carries no address to send to, which is reference data that is incomplete.
 * <strong>The division names the drop; it never makes one.</strong> The email predicate is asked
 * first and asked alone, exactly as {@code :13} asks it, and the letter flags are read only to
 * label a subscription that has already failed it — so a subscriber who asked for both post and
 * email is emailed here as they are there, and no recipient set moves. An
 * address that is absent, an explicit JSON {@code null} or whitespace-only is one rule and one
 * count — the legacy's {@code !== undefined} check keeps the second of those, producing a recipient
 * the frozen contract refuses and so losing the whole register (C29).
 */
// PMD.OnlyOneReturn: each drop answers where its own reason is met, which is what keeps the three
// bounded codes and the three conditions in one place each. PMD.ReturnEmptyCollectionRatherThanNull:
// `recipients` carries `minItems: 1` and an empty array is a document progression rejects; nothing
// is also what tells the aggregation there is nobody to send to (C36).
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.ReturnEmptyCollectionRatherThanNull"})
final class RecipientMapper {

    private static final Logger LOG = LoggerFactory.getLogger(RecipientMapper.class);

    /** The notify template a subscription that names none is sent under. */
    private static final String DEFAULT_TEMPLATE = "cr_standard";

    /** The subscription's own template name, which the legacy reads off the subscription. */
    private static final String EMAIL_TEMPLATE_NAME = "emailTemplateName";

    /** The address the register is sent to. */
    private static final String EMAIL_ADDRESS_1 = "emailAddress1";

    /** The second address, carried and — as far as anyone has traced — used by nothing. */
    private static final String EMAIL_ADDRESS_2 = "emailAddress2";

    /** Where a dropped recipient is counted; called once per drop. */
    private final Consumer<TransformationAnomaly> anomalies;

    /**
     * Creates the mapper.
     *
     * @param anomalyRecorder where each dropped recipient is counted
     */
    /* default */ RecipientMapper(final Consumer<TransformationAnomaly> anomalyRecorder) {
        this.anomalies = anomalyRecorder;
    }

    /**
     * Maps the matched subscriptions.
     *
     * @param matchedSubscriptions the subscriptions matching this register
     * @return the recipients, or {@code null} where none survived the drops
     */
    /* default */ List<CourtRegisterRecipient> map(final List<JsonNode> matchedSubscriptions) {
        if (matchedSubscriptions == null) {
            // `if (this.courtRegisterFragment && this.courtRegisterFragment.matchedSubscriptions)`
            // — nothing was offered, so nothing was dropped and nothing is counted.
            return null;
        }
        final List<CourtRegisterRecipient> recipients =
                new ArrayList<>(matchedSubscriptions.size());
        for (final JsonNode subscription : matchedSubscriptions) {
            final CourtRegisterRecipient recipient = recipient(subscription);
            if (recipient != null) {
                recipients.add(recipient);
            }
        }
        return recipients.isEmpty() ? null : List.copyOf(recipients);
    }

    /**
     * Maps one matched subscription, or says why it cannot be one.
     *
     * @param subscription the matched subscription
     * @return the recipient, or {@code null} where the subscription was dropped
     */
    private CourtRegisterRecipient recipient(final JsonNode subscription) {
        if (!Json.truthy(subscription, "emailDelivery")
                || !Json.truthy(subscription, "forDistribution")) {
            // The legacy's whole predicate, asked first and asked alone. The letter flags decide
            // nothing here; they only say which of the two reasons this subscription failed it.
            return dropped(whyNotForEmail(subscription));
        }

        final JsonNode recipient = Json.at(subscription, "recipient");
        final String emailAddress1 = JsStrings.trimmed(Json.text(recipient, EMAIL_ADDRESS_1));
        if (emailAddress1 == null || emailAddress1.isEmpty()) {
            return dropped(TransformationAnomaly.RECIPIENT_MISSING_EMAIL);
        }

        return new CourtRegisterRecipient(
                Json.text(recipient, "organisationName"),
                emailAddress1,
                Json.truthy(recipient, EMAIL_ADDRESS_2)
                        ? JsStrings.trimmed(Json.text(recipient, EMAIL_ADDRESS_2))
                        : null,
                Json.truthy(subscription, EMAIL_TEMPLATE_NAME)
                        ? Json.text(subscription, EMAIL_TEMPLATE_NAME)
                        : DEFAULT_TEMPLATE);
    }

    /**
     * Which of the two reasons a subscription this channel cannot serve failed the email predicate.
     *
     * <p>Reference data saying "not this one", or reference data saying "by post" — an operator acts
     * on those differently, which is why C27 counts them apart. The letter flags are read
     * <strong>only</strong> here, on a subscription that has already failed
     * {@code emailDelivery && forDistribution}: reading them first would drop a subscriber who asked
     * for the register by post <em>as well as</em> by email, and
     * {@code RecipientMapper.js:13} never reads them at all, so that subscriber is on every register
     * the legacy sends. Losing them would be a recipient-set change, which is precisely what C27's
     * row says this fix does not make.
     *
     * @param subscription the matched subscription, already known not to be for email distribution
     * @return the bounded reason it is dropped under
     */
    private static TransformationAnomaly whyNotForEmail(final JsonNode subscription) {
        return Json.truthy(subscription, "firstClassLetterDelivery")
                || Json.truthy(subscription, "secondClassLetterDelivery")
                ? TransformationAnomaly.LETTER_DELIVERY_DROPPED
                : TransformationAnomaly.RECIPIENT_NOT_FOR_DISTRIBUTION;
    }

    /**
     * Counts and says out loud that a matched subscription will not be written to — defect fix C27.
     *
     * <p>The drop itself is unchanged product behaviour. What changes is that it leaves a trace: a
     * WARN line naming the bounded reason — never the address, which is contact detail on a shared
     * log index — and a count that reaches {@code processed_output.anomaly_summary}.
     *
     * @param reason why the subscription cannot be served
     * @return {@code null}, which is what a dropped subscription maps to
     */
    private CourtRegisterRecipient dropped(final TransformationAnomaly reason) {
        LOG.warn("Matched subscription dropped before the register was addressed. reason={}",
                reason.value());
        anomalies.accept(reason);
        return null;
    }
}
