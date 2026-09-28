package uk.gov.hmcts.cp.yotresultsdistribution.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Counter;
import jakarta.jms.Destination;
import java.util.Map;
import org.apache.activemq.artemis.jms.client.ActiveMQTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;
import uk.gov.hmcts.cp.filter.audit.model.AuditPayload;
import uk.gov.hmcts.cp.filter.audit.service.AuditService;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException;

/**
 * The audit publisher this service supplies in the starter's place, because the starter's swallows.
 *
 * <p>Two things are wrong with {@code AuditService.postMessageToArtemis} for a surface whose
 * existence is conditional on being audited. It catches every {@code Exception}, logs it and
 * returns - verified in the 1.0.5 bytecode - so a broker outage lets an operations call succeed
 * while publishing nothing, which Principle VI (nothing swallowed) and Principle III(b) (every
 * endpoint audited) both refuse. And the event it builds is a generic one: headers, query and path
 * parameters and, where the body switch is on, a body. What an operations event needs - the action,
 * what came of it, whether the one lever was overridden, a run id, a superseded count - the filter
 * can infer from none of that.
 *
 * <p>The starter registers its bean {@code @ConditionalOnMissingBean(AuditService.class)}
 * (research R10), so a subclass contributed here takes its place and the filter uses it unchanged.
 *
 * <p><strong>The request event is a precondition and the response event is not</strong> - on a
 * thread that is serving an operations call, which is the only kind there is anything to refuse
 * on. The filter
 * publishes the request event before the chain, so a failure there can still refuse the call:
 * {@link OperationsReason#AUDIT_UNAVAILABLE}, a {@code 503}, and the application service is never
 * reached. The response event is published after the action has happened, and no status can be
 * given back to a caller whose work is done - so a failure there is logged at ERROR naming the
 * action, the run id and the failing class, and moves a bounded counter. That second case is the
 * single entry in the plan's Complexity Tracking: it is recorded rather than closed, and a durable
 * outbox is the named way to close it.
 *
 * <p>Nothing of the failure's own words reaches either the log or the event: a caught exception is
 * named by class, because its message belongs to whatever library raised it and is exactly where a
 * connection string turns up.
 *
 * <p><strong>An event that was never built counts as one that was never published.</strong> A null
 * payload, and a payload whose content node is null, are both a call this service cannot put the
 * bounded facts onto - the action, the outcome, the override - and a call whose event says none of
 * those is not an audited call. Both therefore take the same two answers as a transport failure:
 * refused on the request event, counted and said at ERROR on the response one. Letting either
 * through would make fail-closed depend on which part of the publishing broke.
 */
public class OperationsAuditService extends AuditService {

    private static final Logger LOG = LoggerFactory.getLogger(OperationsAuditService.class);

    /** The topic the estate's audit context reads, as the starter's own service addresses it. */
    private static final Destination AUDIT_TOPIC = new ActiveMQTopic("jms.topic.auditing.event");

    /** The property the audit context routes on, which the starter sets from the payload's name. */
    private static final String CPPNAME = "CPPNAME";

    /** What the log names as the cause where the library handed over no event to publish. */
    private static final String NO_EVENT_BUILT = "no-event-built";

    /** The template the starter built against the audit broker's own connection. */
    private final JmsTemplate audit;

    /** The starter's own mapper, so the event on the wire is spelled as the library spells it. */
    private final ObjectMapper mapper;

    /**
     * What moves when an event could not be published and there was nothing to refuse: a
     * response event the caller has already been answered past, or an event raised on a
     * thread serving no operations call. Both are the recorded shortfall.
     */
    private final Counter unpublished;

    /**
     * Creates the publisher over the starter's own transport.
     *
     * @param auditJmsTemplate the template built against the audit broker
     * @param auditObjectMapper the starter's mapper
     * @param unpublishedResponseEvents the counter a lost response event moves
     */
    public OperationsAuditService(final JmsTemplate auditJmsTemplate,
            final ObjectMapper auditObjectMapper, final Counter unpublishedResponseEvents) {

        super(auditJmsTemplate, auditObjectMapper);
        this.audit = auditJmsTemplate;
        this.mapper = auditObjectMapper;
        this.unpublished = unpublishedResponseEvents;
    }

    /**
     * Merges this call's bounded facts into the event and publishes it, refusing nothing quietly.
     *
     * @param payload the event the filter built
     * @throws OperationsRefusedException under {@link OperationsReason#AUDIT_UNAVAILABLE} where the
     *         <em>request</em> event could not be published, which refuses the call before any
     *         application service is reached
     */
    // PMD.AvoidCatchingGenericException: the transport raises unchecked Spring types and the mapper
    // raises its own; both mean the same thing here - this event did not reach the audit context -
    // and both are classified and acted on rather than continued past.
    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void postMessageToArtemis(final AuditPayload payload) {
        final OperationsAuditFacts facts = OperationsAuditFacts.current();
        // No open facts means no open operations call: this bean is built wherever the audit
        // transport is, and OperationsActionFilter - which opens them - only where the operations
        // surface is served as well. There is then nothing to refuse and nowhere to render a
        // refusal, so such an event takes the answer a response event takes: counted, and said.
        final boolean requestEvent = facts != null && facts.firstPublish();
        if (payload == null || payload.content() == null) {
            // An event the library's own builder did not produce, or produced without the node the
            // bounded facts are written onto. Either way this call is not audited, and an
            // unaudited call is refused exactly as one whose transport failed is - the fail-closed
            // rule cannot depend on which part of the publishing broke.
            failed(facts, requestEvent, NO_EVENT_BUILT, null);
            return;
        }
        try {
            publish(merged(payload, facts));
        } catch (RuntimeException notPublished) {
            failed(facts, requestEvent, notPublished.getClass().getName(), notPublished);
        }
    }

    /**
     * The event with this call's bounded facts on it.
     *
     * @param payload the event the filter built
     * @param facts   this call's facts, or {@code null} for a request this surface did not serve
     * @return the same payload, whose content now carries the bounded fields
     */
    private static AuditPayload merged(final AuditPayload payload,
            final OperationsAuditFacts facts) {

        final ObjectNode content = payload.content();
        if (facts != null) {
            for (final Map.Entry<String, Object> fact : facts.asPublished().entrySet()) {
                putBounded(content, fact.getKey(), fact.getValue());
            }
        }
        return payload;
    }

    /**
     * Writes one bounded fact onto the event, by the type it is.
     *
     * @param content the event's content node
     * @param name    the field's name
     * @param value   a string, a boolean or a count, and never anything else
     */
    private static void putBounded(final ObjectNode content, final String name,
            final Object value) {

        switch (value) {
            case Boolean flag -> content.put(name, flag);
            case Integer count -> content.put(name, count);
            default -> content.put(name, String.valueOf(value));
        }
    }

    /**
     * Sends the event, exactly as the starter's own service addresses and routes it.
     *
     * @param payload the event to publish
     */
    private void publish(final AuditPayload payload) {
        final String body = serialised(payload);
        final String name = payload._metadata() == null ? null : payload._metadata().name();
        audit.convertAndSend(AUDIT_TOPIC, body, message -> {
            message.setStringProperty(CPPNAME, name);
            return message;
        });
    }

    /**
     * The event as the audit context reads it.
     *
     * @param payload the event
     * @return its JSON
     */
    private String serialised(final AuditPayload payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException notSerialised) {
            throw new IllegalStateException("the audit event could not be serialised",
                    notSerialised);
        }
    }

    /**
     * What an event that did not reach the audit context costs, which depends on which event it was.
     *
     * @param facts        this call's facts, or {@code null}
     * @param requestEvent whether the lost event was the request's
     * @param causeName    what stopped it, as a class name or one of this service's own bounded
     *                     words - never a message, which belongs to whatever library raised it
     * @param cause        the failure itself where there was one, so the refusal carries it, or
     *                     {@code null} where nothing was thrown and there was simply no event
     */
    private void failed(final OperationsAuditFacts facts, final boolean requestEvent,
            final String causeName, final RuntimeException cause) {

        final String action = facts == null ? null : facts.actionName();
        if (requestEvent) {
            LOG.error("An operations call was refused because its request could not be audited, "
                            + "which is the only moment there is still something to refuse. "
                            + "action={} reason={} cause={}", action,
                    OperationsReason.AUDIT_UNAVAILABLE.wire(), causeName);
            throw new OperationsRefusedException(OperationsReason.AUDIT_UNAVAILABLE, null, cause);
        }
        unpublished.increment();
        LOG.error("An operations call was answered and its response event was not published, so "
                        + "the audit trail for that call is incomplete and nothing can be refused "
                        + "about it. action={} run_id={} cause={}", action,
                facts == null ? null : facts.acceptedRunId(), causeName);
    }
}
