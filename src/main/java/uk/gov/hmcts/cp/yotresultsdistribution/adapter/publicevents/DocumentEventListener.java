package uk.gov.hmcts.cp.yotresultsdistribution.adapter.publicevents;

import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.application.DocumentOutcomeSink;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.config.PublicEventsConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletedBy;

/**
 * How a rendering outcome reaches this service: the durable subscription to {@code public.event}.
 *
 * <p>systemdocgenerator announces every document it renders and every generation it refuses on the
 * estate's public topic, so the two events this service cares about arrive among everybody else's.
 * Three filters narrow them, and each is there for a different reason:
 *
 * <ul>
 *   <li>the broker's own {@code CPPNAME} selector, so the subscription is handed the two event names
 *       rather than the whole topic;</li>
 *   <li>{@code originatingSource == YotResultsDistributionService}, because progression's leg is still
 *       deployed and still subscribed, and its documents are not this service's to act on. Such a
 *       message is acknowledged and counted, never left unsettled: a message this service has no
 *       business with is not a message the broker should redeliver;</li>
 *   <li>{@code sourceCorrelationId}, which is the batch id and the only thing that maps an outcome
 *       back to the rows the document was built from. An event of ours that names none is
 *       acknowledged and counted under {@code unknown-correlation}, the same reason the sink counts
 *       a correlation this store holds no batch for: both are announcements that reached this
 *       subscription and were applied to nothing. One that names its batch and leaves out
 *       {@code payloadFileServiceId} is dropped just the same and counted under
 *       {@code missing-payload-id} instead, because its correlation was never in doubt and the
 *       reading that says otherwise sends support after a batch identity nothing lost. One that
 *       addresses itself perfectly and leaves out the document, or the instant, is counted under
 *       {@code incomplete-outcome}: the fault there is the announcement, not the addressing.</li>
 * </ul>
 *
 * <p>What it does with a message it recognises is call
 * {@link uk.gov.hmcts.cp.yotresultsdistribution.application.DocumentOutcomeSink}, naming EVENT. Nothing about
 * batch state is decided here: this class knows JMS and envelopes, and the sink knows what an
 * outcome means.
 *
 * <p><strong>One failure leaves here, and it is the sink's.</strong> A listener that threw at
 * anything else would nack, and a nack on a durable subscription is a message the broker offers
 * again for ever - so a message that cannot be read, is not an envelope, contradicts its own header
 * or names no batch is said out loud and acknowledged. A sink that could not write is the opposite
 * case: the outcome is good and the store was not there for a second, so it is said at ERROR
 * naming the batch and handed back for the broker to offer again.
 *
 * <p>That hand-back is only a redelivery because of how the container is configured, and this class
 * cannot make it one on its own. {@link PublicEventsConfig} runs the listener in a
 * <strong>transacted session</strong>; a container left at the default {@code AUTO_ACKNOWLEDGE}
 * would have acknowledged the message before calling this method, and the exception would then be a
 * lost outcome dressed as a retry. The two statements are one arrangement written in two files, and
 * {@code DocumentEventListenerIT.an_outcome_the_sink_could_not_apply_should_be_offered_again} is
 * where it is held down.
 *
 * <p><strong>The subscription is configured, not hard-coded.</strong> The destination, the durable
 * subscription's name and the selector all come from {@code yotresultsdistribution.publicevents.*}, and the
 * container they run in is {@link PublicEventsConfig}'s - pub-sub, durable, and started only where
 * this deployment generates and learns its outcomes from events.
 *
 * <p><strong>It is a bean of {@link PublicEventsConfig}'s making rather than a component.</strong>
 * The sink it writes through lives with the register store, which a deployment without a database
 * does not have; declaring the listener where the container factory is declared is what lets one
 * condition - "this pod generates" - govern the subscription and everything it needs.
 */
// PMD.OnlyOneReturn: every filter below is a reason to acknowledge and stop, and each says so where
// it is decided. Funnelling them through one exit would turn each distinct refusal into a flag.
@SuppressWarnings("PMD.OnlyOneReturn")
public class DocumentEventListener {

    /**
     * The JMS string property carrying the event's name, which the broker's selector filters on.
     *
     * <p>The broker applies it as a selector so a subscriber to a topic the whole estate publishes
     * to is handed two event names rather than all of them; the envelope's own {@code _metadata.name}
     * is what the message says it is, and the two are read separately for that reason.
     *
     * <p><strong>They are also written separately, and only one of them verbatim.</strong> This
     * property's value has already been narrowed by the filter above to one of the two constants
     * below, so it may go on a line as it stands. The envelope's name may be anything at all - a
     * message that disagrees with its own header has shown it is not what it claimed, so its name
     * is another context's arbitrary text and could be a child's own details (Principle VII). It
     * therefore reaches a line only through {@link #claimed(String)}, which answers one of the two
     * constants or {@code not-a-subscribed-event} and never the text it was given.
     */
    public static final String EVENT_NAME_PROPERTY = "CPPNAME";

    /** systemdocgenerator's announcement that a document was rendered. */
    public static final String DOCUMENT_AVAILABLE =
            "public.systemdocgenerator.events.document-available";

    /** systemdocgenerator's announcement that a generation was refused. */
    public static final String GENERATION_FAILED =
            "public.systemdocgenerator.events.generation-failed";

    /**
     * The {@code originatingSource} this service asks for its documents under, and therefore the
     * only one whose outcomes are this service's to act on.
     *
     * <p>The same name the render request carries as {@code originatingSource}; progression's leg is
     * still deployed and still subscribed, and it keeps its own.
     */
    public static final String ORIGINATING_SOURCE = "YotResultsDistributionService";

    private static final Logger LOG = LoggerFactory.getLogger(DocumentEventListener.class);

    /** The event's own account of who asked for the document. */
    private static final String ORIGINATING_SOURCE_FIELD = "originatingSource";

    /** The batch the render was requested for, which is what an outcome is applied to. */
    private static final String SOURCE_CORRELATION_ID = "sourceCorrelationId";

    /** The payload the document was rendered from, and the correlation's cross-check. */
    private static final String PAYLOAD_FILE_SERVICE_ID = "payloadFileServiceId";

    /** The rendered document, which is what the e-mail will attach. */
    private static final String DOCUMENT_FILE_SERVICE_ID = "documentFileServiceId";

    /** When systemdocgenerator says it finished. */
    private static final String GENERATED_TIME = "generatedTime";

    /** When systemdocgenerator says it gave up. */
    private static final String FAILED_TIME = "failedTime";

    /** systemdocgenerator's own words for a refusal, kept by the sink and never logged. */
    private static final String REASON = "reason";

    /**
     * What is written where an envelope's own name is not one this service declares.
     *
     * <p>The bounded stand-in for {@code _metadata.name}. The header's name is one of the two
     * constants above by the time the two are compared, so it may be written; the envelope's is
     * the message's own account of itself and may be anything at all, so it is written only where
     * it is one of those two constants as well. Everything else - another event's name, an
     * envelope carrying no name, and whatever a mistake puts there - reads as this one code.
     */
    private static final String NOT_A_SUBSCRIBED_EVENT = "not-a-subscribed-event";

    private final DocumentOutcomeSink sink;

    private final GenerationMetrics metrics;

    private final DeliveryObserver deliveries;

    /**
     * Holds the outcome port, the instruments the routing uses and the observer of every delivery.
     *
     * @param sink              where a recognised outcome is applied, naming EVENT
     * @param metrics           where an event that reaches this subscription and is applied to
     *                          nothing is counted, under the reason it was not applied
     * @param deliveryObserver  told that the broker served this subscription, before any filter
     */
    public DocumentEventListener(final DocumentOutcomeSink sink, final GenerationMetrics metrics,
            final DeliveryObserver deliveryObserver) {
        this.sink = sink;
        this.metrics = metrics;
        this.deliveries = deliveryObserver;
    }

    /**
     * Handles one message from the public-event topic.
     *
     * <p>The three configured values are the subscription's identity as much as its address: the
     * destination is the estate's one multicast topic, the subscription name is the whole of what
     * the broker recognises this subscriber by across a restart - the subscription is shared and so
     * carries no client id, which is what lets every replica attach to that one name - and the
     * selector is the broker-side filter that keeps a subscriber to everybody's topic from being
     * handed everybody's traffic.
     *
     * @param message the framework {@code JsonEnvelope} as text, with {@code CPPNAME} as a string
     *                property
     */
    @JmsListener(
            destination = "${yotresultsdistribution.publicevents.topic}",
            subscription = "${yotresultsdistribution.publicevents.subscription}",
            selector = "${yotresultsdistribution.publicevents.selector}",
            containerFactory = PublicEventsConfig.LISTENER_CONTAINER_FACTORY)
    public void onPublicEvent(final TextMessage message) {
        // First, and before every filter below it. What is being recorded is that the broker served
        // this subscription, which is as true of progression's still-deployed leg announcing its own
        // document as it is of one of ours - and on a night the legacy generates and this service
        // does not, its events are the only proof the subscription is alive.
        deliveries.recordDelivery();
        final String eventName;
        final String body;
        try {
            eventName = message.getStringProperty(EVENT_NAME_PROPERTY);
            body = message.getText();
        } catch (final JMSException unreadable) {
            // The drop and what refused it, never the broker's own words. A JMSException's message
            // is the provider's text, and a provider explaining a body it could not decode or a
            // property it could not convert has both in hand and may quote either - so it is
            // another context's unvalidated value of unknown shape, exactly what Principle VII
            // keeps out of a log index, and it is named by class for the reason the readers below
            // name theirs. There is nothing else to write: a message that would not come off the
            // subscription named no event, no batch and no payload.
            LOG.warn("A public event could not be read off the subscription, so it is acknowledged "
                    + "and dropped: a message the broker cannot hand over will not read any better "
                    + "on the redelivery. cause={}", unreadable.getClass().getName());
            return;
        }
        if (!DOCUMENT_AVAILABLE.equals(eventName) && !GENERATION_FAILED.equals(eventName)) {
            LOG.debug("A public event the selector does not name reached the subscription: {}.",
                    eventName);
            return;
        }
        final PublicEventEnvelope envelope;
        try {
            envelope = PublicEventEnvelope.parse(body);
        } catch (final JacksonException | IllegalArgumentException notAnEnvelope) {
            // The event and what refused the body, never the body. Redaction of the parser's
            // source location is not redaction of the body: an unquoted token is reported as
            // "Unrecognized token '<the token>'", so the message quotes the body's own text, and
            // that text is another context's, did not parse, and may be anything at all
            // (Principle VII). The exception is named by class for the same reason the two field
            // readers name theirs, and the class is the reading a diagnosis needs: a Jackson
            // failure is a body that is not JSON, and IllegalArgumentException is JSON this
            // service refused as an envelope.
            metrics.unreadableEnvelopeIgnored();
            LOG.warn("A {} was not a readable JsonEnvelope, so it is acknowledged and dropped. "
                    + "cause={}", eventName, notAnEnvelope.getClass().getName());
            return;
        }
        if (!eventName.equals(envelope.metadataName())) {
            // Both sides where both are bounded, and never the envelope's own text. The header's
            // name is one of the two constants this class declares - the filter above has already
            // dropped everything else - so it is written; the envelope's is what the message says
            // it is, and a message that disagrees with its own header has already shown it is not
            // what it claims, so its name may be a person's own details as easily as an event
            // (Principle VII).
            metrics.headerEnvelopeMismatchIgnored();
            LOG.warn("A public event's CPPNAME and its envelope disagree, so it is acknowledged and "
                    + "dropped: the header said {} and the envelope says {}.", eventName,
                    claimed(envelope.metadataName()));
            return;
        }
        route(eventName, envelope.payload());
    }

    /**
     * What an envelope's own name may be written down as: one of this class's two constants, or the
     * one code that stands for everything else.
     *
     * <p>The constant is returned rather than the argument, deliberately: what reaches the line is
     * then a value this file spells, and no reading of this method can leak the message's text by
     * being changed later. A name that is one of the two is worth writing because it is the whole
     * of a genuine mismatch's diagnosis - a document-available body announced under a
     * generation-failed header, or the crossing the other way about, which is what
     * systemdocgenerator publishing the pair out of step would look like.
     *
     * <p>An envelope carrying no name at all reads as the same code as one naming another event.
     * The distinction is real - a {@code JsonEnvelope} without its own name is the publisher's
     * fault and not the routing's - and it is deliberately not made here: it would be a third
     * reading no case asks for, and this line is about the disagreement rather than about the
     * envelope's completeness.
     *
     * @param metadataName the envelope's {@code _metadata.name}, which may be {@code null}
     * @return the bounded value to write
     */
    private static String claimed(final String metadataName) {
        if (DOCUMENT_AVAILABLE.equals(metadataName)) {
            return DOCUMENT_AVAILABLE;
        }
        if (GENERATION_FAILED.equals(metadataName)) {
            return GENERATION_FAILED;
        }
        return NOT_A_SUBSCRIBED_EVENT;
    }

    /**
     * Applies one of systemdocgenerator's two announcements, if it is this service's to apply.
     *
     * @param eventName the event both the header and the envelope name
     * @param payload   the event's own fields
     */
    private void route(final String eventName, final JsonNode payload) {
        if (!ORIGINATING_SOURCE.equals(text(payload, ORIGINATING_SOURCE_FIELD))) {
            // Somebody else's document, or one from a request that carried no source at all - which
            // this service always sends, so an outcome without one answers a request that was not
            // ours. Counted, because a subscription hearing nothing of its own has to be legible
            // from a subscription hearing nothing at all.
            metrics.foreignEventIgnored();
            LOG.debug("A {} for another service's document was acknowledged and dropped.",
                    eventName);
            return;
        }
        final UUID correlationId = uuid(payload, SOURCE_CORRELATION_ID);
        if (correlationId == null) {
            // Counted before it is dropped. This one carries our own source, so it is not the
            // foreign-source reading; it is an announcement this service asked for that names
            // nothing to apply itself to, and a drop with no reading behind it is an outcome that
            // vanished between the renderer and the register.
            metrics.unknownCorrelationIgnored();
            LOG.warn("A {} named no batch to apply it to, so it is acknowledged and dropped: "
                    + "inventing one from the payload would be this service guessing at somebody "
                    + "else's document.", eventName);
            return;
        }
        final UUID payloadFileId = uuid(payload, PAYLOAD_FILE_SERVICE_ID);
        if (payloadFileId == null) {
            // The same drop and a different reading. The correlation is the one this service asked
            // for, so counting this as an unknown correlation would send support after a batch
            // identity that was never lost; what is missing is the cross-check, without which the
            // sink cannot tell this outcome from one that has crossed its correlation.
            metrics.missingPayloadIdIgnored();
            LOG.warn("A {} for batch {} named no payload to check the batch against, so it is "
                    + "acknowledged and dropped: an outcome that cannot be cross-checked is how a "
                    + "crossed correlation would complete the wrong night's registers.", eventName,
                    correlationId);
            return;
        }
        if (DOCUMENT_AVAILABLE.equals(eventName)) {
            documentAvailable(payload, correlationId, payloadFileId);
        } else {
            generationFailed(payload, correlationId, payloadFileId);
        }
    }

    /**
     * Applies a document this service asked for and systemdocgenerator rendered.
     *
     * @param payload       the event's own fields
     * @param correlationId the batch the render was requested for
     * @param payloadFileId the payload the document was rendered from
     */
    private void documentAvailable(final JsonNode payload, final UUID correlationId,
            final UUID payloadFileId) {

        final UUID documentFileId = uuid(payload, DOCUMENT_FILE_SERVICE_ID);
        final Instant generatedAt = instant(payload, GENERATED_TIME);
        if (documentFileId == null || generatedAt == null) {
            // The announcement's addressing is sound and its subject is missing, which is neither
            // of the two absences above: counted under its own reason so a batch given back by the
            // next run is not the only trace of an outcome that arrived and could not be used.
            metrics.incompleteOutcomeIgnored();
            LOG.warn("A document-available for batch {} carried no document or no instant, so it "
                    + "is acknowledged and dropped; the batch stays in flight and the next run "
                    + "gives it back.", correlationId);
            return;
        }
        apply(correlationId, () -> sink.documentAvailable(correlationId, payloadFileId,
                documentFileId, generatedAt, CompletedBy.EVENT));
    }

    /**
     * Applies a generation this service asked for and systemdocgenerator refused.
     *
     * <p>The generator's own words travel with it and are handed to the sink, never logged: they are
     * free text from another context and the batch's own reason is the bounded GENERATION_FAILED.
     *
     * @param payload       the event's own fields
     * @param correlationId the batch the render was requested for
     * @param payloadFileId the payload the render was requested for
     */
    private void generationFailed(final JsonNode payload, final UUID correlationId,
            final UUID payloadFileId) {

        final Instant failedAt = instant(payload, FAILED_TIME);
        if (failedAt == null) {
            // The same fault on the other event, and so the same reason: a refusal with no instant
            // is a refusal nothing can be stamped with.
            metrics.incompleteOutcomeIgnored();
            LOG.warn("A generation-failed for batch {} carried no instant, so it is acknowledged "
                    + "and dropped; the batch stays in flight and the next run gives it back.",
                    correlationId);
            return;
        }
        apply(correlationId, () -> sink.generationFailed(correlationId, payloadFileId,
                text(payload, REASON), failedAt, CompletedBy.EVENT));
    }

    /**
     * Applies one outcome, and says which batch it was if the sink could not.
     *
     * <p>The one exception this class lets past, reported at the level a lost-and-recovered outcome
     * is worth reading at (constitution Principle VI) and then rethrown, so the transacted session
     * rolls back and the broker offers the event again. The line carries the batch identity - which
     * is the thing the container's own handler cannot know - and the failure's type; neither is
     * about a defendant or a recipient, and the sink is handed the renderer's words rather than
     * this method (constitution Principle VII).
     *
     * @param correlationId the batch the outcome is about, for the line
     * @param outcome       the sink call this event turned into
     */
    // PMD.AvoidCatchingGenericException: what the sink raises is the store's own unchecked type
    // today and whatever the next adapter behind the port raises tomorrow. Narrowing this to the
    // types known here would silently take a future one back to the acknowledged-and-lost path this
    // exists to close.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static void apply(final UUID correlationId, final Runnable outcome) {
        try {
            outcome.run();
        } catch (final RuntimeException notApplied) {
            LOG.error("The outcome for batch {} could not be applied, so it is handed back to the "
                    + "container and the broker offers the event again. cause={}", correlationId,
                    notApplied.getClass().getName());
            throw notApplied;
        }
    }

    /**
     * The text of a field, or {@code null} where the event carries none.
     *
     * @param payload the event's own fields
     * @param field   the field name
     * @return the field's text, or {@code null}
     */
    private static String text(final JsonNode payload, final String field) {
        final JsonNode value = payload.path(field);
        return value.isString() ? value.stringValue() : null;
    }

    /**
     * The identity a field names, or {@code null} where the event carries none or names something
     * that is not one.
     *
     * @param payload the event's own fields
     * @param field   the field name
     * @return the identity, or {@code null}
     */
    private static UUID uuid(final JsonNode payload, final String field) {
        final String value = text(payload, field);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (final IllegalArgumentException notAnIdentity) {
            // The field and what refused it, never the value. What the field carried is another
            // context's and did not parse, so it is unvalidated data this service never asked for
            // and may be anything at all, including a person's own details (Principle VII). The
            // exception is named by class for the same reason its message is not written: the
            // message quotes the value.
            LOG.warn("A public event's {} was not an identity, so the event names no batch. "
                    + "cause={}", field, notAnIdentity.getClass().getName());
            return null;
        }
    }

    /**
     * The instant a field names, read with the offset the estate publishes it under.
     *
     * <p>Both schemas' times are {@code date-time} strings and the platform sends them with an
     * offset rather than in UTC - a register generated at 18:04 on a British summer evening is
     * announced as {@code 18:04:11.412+01:00} - so the offset is part of the value and reading it
     * as local time would put every summer batch an hour out.
     *
     * @param payload the event's own fields
     * @param field   the field name
     * @return the instant, or {@code null} where the event carries none or none that parses
     */
    private static Instant instant(final JsonNode payload, final String field) {
        final String value = text(payload, field);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (final DateTimeParseException notATime) {
            // The value is left out for the reason given at the other reader.
            LOG.warn("A public event's {} was not a date-time, so the outcome carries no instant. "
                    + "cause={}", field, notATime.getClass().getName());
            return null;
        }
    }
}
