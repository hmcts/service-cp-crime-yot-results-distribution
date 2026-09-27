package uk.gov.hmcts.cp.yotresultsdistribution.adapter.publicevents;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;

/**
 * A framework {@code JsonEnvelope} as this service reads it: what the event is called, and what it
 * says.
 *
 * <p>Public events on the {@code public.event} topic are an envelope with two halves, the
 * {@code _metadata} object that names and traces the event and the payload beside it. Only two facts
 * of the first half matter here, and the second is passed on as a tree rather than as a typed model
 * because it belongs to systemdocgenerator: this service adapts to the two event schemas vendored
 * under {@code contracts/systemdocgenerator/} and never redefines them.
 *
 * <p>The name is read from the envelope itself rather than trusted from the JMS {@code CPPNAME}
 * property. The property is the broker's selector, which is how the subscription avoids being handed
 * every public event in the estate; the envelope is the message's own account of what it is, and
 * where the two disagree the message is not what the header claimed.
 *
 * @param metadataName the event name from {@code _metadata.name}, or {@code null} where the
 *                     envelope carries none
 * @param payload      everything the event says, as a tree
 */
public record PublicEventEnvelope(String metadataName, JsonNode payload) {

    /** The framework's half of the envelope, which is never part of the event's own schema. */
    private static final String METADATA = "_metadata";

    /** What the envelope calls itself. */
    private static final String NAME = "name";

    /**
     * The mapper the whole service reads contracts with, so an event tree is built under the same
     * rules as every other tree here.
     */
    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    /**
     * Reads one message body as an envelope.
     *
     * <p>The payload is the body with {@code _metadata} taken out of it rather than the body
     * itself: handing the whole thing on would make the framework's half part of
     * systemdocgenerator's schema, which it is not, and the only two facts of it this service reads
     * are already the record's own.
     *
     * @param body the JMS text message's body
     * @return the envelope it carries
     * @throws tools.jackson.core.JacksonException if the body is not JSON at all
     * @throws IllegalArgumentException            if the body is JSON but not an envelope object
     */
    public static PublicEventEnvelope parse(final String body) {
        final JsonNode envelope = MAPPER.readTree(body);
        if (!envelope.isObject()) {
            throw new IllegalArgumentException(
                    "a public event body is a JsonEnvelope object, and this one is not");
        }
        final JsonNode name = envelope.path(METADATA).path(NAME);
        final String metadataName = name.isString() ? name.stringValue() : null;
        // The tree was parsed a line ago and is nobody else's; removing the framework's half in
        // place is the same result as a deep copy without the second tree.
        final ObjectNode payload = (ObjectNode) envelope;
        payload.remove(METADATA);
        return new PublicEventEnvelope(metadataName, payload);
    }
}
