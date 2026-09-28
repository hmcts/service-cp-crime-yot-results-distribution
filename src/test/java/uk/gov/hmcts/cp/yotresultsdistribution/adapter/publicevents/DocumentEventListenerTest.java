package uk.gov.hmcts.cp.yotresultsdistribution.adapter.publicevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.PathType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.application.DocumentOutcomeSink;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletedBy;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PersonalDataMarkers;

/**
 * What the listener does with one message off {@code public.event}, and what it refuses to do.
 *
 * <p>The topic is the estate's, not this service's: every context that publishes a public event
 * publishes it here, and systemdocgenerator announces every document it renders for anybody. Three
 * things therefore have to hold before an outcome may touch a batch, and each of them is a case
 * below.
 *
 * <ul>
 *   <li><strong>The message says what it is.</strong> A framework {@code JsonEnvelope} is
 *       {@code _metadata} plus the event's own fields beside it, and the name is read from the
 *       envelope rather than trusted from the {@code CPPNAME} property the broker selected on -
 *       the property is a filter, the envelope is the message's own account of itself.</li>
 *   <li><strong>The document is this service's.</strong> progression's court-register leg is still
 *       deployed, still subscribed and still renders through the same systemdocgenerator, so an
 *       outcome whose {@code originatingSource} is not this service's is not this service's to act
 *       on. It is acknowledged and counted, never left unsettled: a durable subscription offers an
 *       unsettled message again for ever, and this one would never become ours.</li>
 *   <li><strong>The outcome names a batch.</strong> {@code sourceCorrelationId} is the batch id and
 *       the only thing that maps a rendered document back to the rows it was built from.</li>
 * </ul>
 *
 * <p>What the listener does with a message that passes all three is call
 * {@link DocumentOutcomeSink} naming EVENT, with the two ids the event carries. Nothing about batch
 * state is asserted here, because nothing about batch state is decided here: the sink's own suite
 * (T037) owns what an outcome does to a batch, and this one owns only whether the outcome arrives
 * at the sink at all, with which fields.
 *
 * <p><strong>The fixtures are held to the vendored schemas rather than to a comment.</strong>
 * systemdocgenerator's two public events are its contract and not this service's, and every case
 * below is only as good as the envelopes it puts in front of the listener: a fixture that has
 * drifted from the shape the platform publishes would let this suite pass while the deployed
 * listener drops every real event. So each fixture's payload is validated against the copy in
 * {@code specs/002-consolidate-progression-leg/contracts/systemdocgenerator/}, offline, with the
 * same validator and the same no-network rule {@code OutboundContractValidator} holds the frozen
 * progression contract to - and the negative control below deliberately breaks a fixture, so a
 * validation that had quietly stopped checking anything would be caught rather than trusted.
 *
 * <p>The other half is the routing: every field this listener reads out of a payload has to be a
 * field the schema declares. A field the schema does not have is one the platform never sends, and
 * a listener reading it would drop or mis-apply every event for a reason no test would show.
 *
 * <p>The times are read with their offsets. The two schemas' {@code generatedTime} and
 * {@code failedTime} are {@code date-time} strings and the estate publishes them with an offset
 * rather than in UTC - a register generated at 18:04 on a British summer evening is announced as
 * {@code 18:04:11.412+01:00} - so the fixtures carry the offset and the expectations are the
 * instants it resolves to. Reading them as local time would put every summer batch an hour out.
 */
class DocumentEventListenerTest {

    /** Returned when a meter is absent, so a missing count fails as an assertion. */
    private static final double ABSENT = -1;

    /** The batch the render was requested for, and the event's {@code sourceCorrelationId}. */
    private static final UUID BATCH_ID = UUID.fromString("6f3a1c58-9b1e-4b0a-9d8c-2f7a4e5c1b30");

    /** The payload file-service id the render was asked for, and the event's own cross-check. */
    private static final UUID PAYLOAD_FILE_ID =
            UUID.fromString("b4c9d2e1-77a3-4c56-8f21-0d3e6a9b4c77");

    /** The rendered document's file-service id, which is what the e-mail will attach. */
    private static final UUID DOCUMENT_FILE_ID =
            UUID.fromString("0a1b2c3d-4e5f-4a6b-8c9d-1e2f3a4b5c6d");

    /** {@code 2026-09-05T18:04:11.412+01:00}, the instant that offset resolves to. */
    private static final Instant GENERATED_AT = Instant.parse("2026-09-05T17:04:11.412Z");

    /** {@code 2026-09-05T18:06:23.004+01:00}, the instant that offset resolves to. */
    private static final Instant FAILED_AT = Instant.parse("2026-09-05T17:06:23.004Z");

    /** systemdocgenerator's own words for a refusal, which the sink keeps and nothing logs. */
    private static final String SDG_REASON =
            "OEE_Layout5 produced no pages for the payload supplied";

    /**
     * The source progression's still-deployed leg asks for its own documents under, which is the
     * one its own listener branches on ({@code COURT_REGISTER.equalsIgnoreCase}, research section
     * 2) and the reason this service was given a distinct name of its own.
     */
    private static final String PROGRESSION_SOURCE = "COURT_REGISTER";

    /**
     * The most of {@link PersonalDataMarkers#OPERATOR_TOKEN} a JSON parser will quote back.
     *
     * <p>A parse failure reports an unquoted token by reading identifier characters and stopping at
     * the first one that is not, so a body carrying the marker where a value belongs is refused
     * with "Unrecognized token 'zqx7'" - the marker's own first segment and no more of it. That is
     * still the body's own text in a log line, and it is all that can reach one by this route: a
     * case that swept for the whole marker would pass while a fragment of somebody's typing sat in
     * the index. Derived from the marker rather than written out, so the two cannot drift apart.
     */
    private static final String QUOTED_BACK = PersonalDataMarkers.OPERATOR_TOKEN.substring(0,
            PersonalDataMarkers.OPERATOR_TOKEN.indexOf('.'));

    /** The single-quoted event names inside the shipped {@code CPPNAME} selector. */
    private static final Pattern SELECTOR_EVENT_NAME = Pattern.compile("'([^']+)'");

    /** The vendored copy of systemdocgenerator's announcement that a document was rendered. */
    private static final String DOCUMENT_AVAILABLE_SCHEMA =
            "public.systemdocgenerator.events.document-available.json";

    /** The vendored copy of its announcement that a generation was refused. */
    private static final String GENERATION_FAILED_SCHEMA =
            "public.systemdocgenerator.events.generation-failed.json";

    /**
     * The vendored contracts, with their provenance, read from the file this repository holds them
     * in.
     *
     * <p>The same directory {@code SystemDocGeneratorClientTest} reads the command schema from, and
     * for the same reason: the vendored copy is the thing that has to be right, so a re-vendoring
     * that changes either event shows up here rather than at 18:00.
     */
    private static final Path VENDORED = Path.of(
            "specs", "002-consolidate-progression-leg", "contracts", "systemdocgenerator");

    /**
     * The framework core definitions the two event schemas {@code $ref}, mapped to the vendored
     * copy.
     *
     * <p>Nothing here may go to the network to resolve a reference: a validation that degraded to
     * "could not fetch the schema, so nothing was checked" is the shape that passes for ever, and
     * one that reached the internet from a test would be worse. It is the same rule, and the same
     * mechanism, {@code OutboundContractValidator} resolves the frozen progression contract with.
     *
     * <p>The stand-in is on the test classpath rather than beside the two event schemas because
     * this resolver honours {@code classpath:} and nothing else - a {@code file:} mapping is passed
     * over and the original {@code http://} IRI is what gets opened. Its provenance, and the fact
     * that it is written rather than vendored, are recorded in {@code contracts/README.md}.
     */
    private static final Map<String, String> VENDORED_REFS = Map.of(
            "http://cpp.moj.gov.uk/core/domain/json/schema/data-types.json",
            "classpath:contracts/systemdocgenerator/data-types.json");

    /** The shared contract mapper, so a fixture is parsed exactly as the listener parses it. */
    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    /**
     * Every field this listener reads out of an event payload.
     *
     * <p>Each of them has to be declared by the schema of the event it is read from, or the
     * listener is reading a field the platform does not send. The two times are one each - a
     * document has a {@code generatedTime} and a refusal a {@code failedTime} - so which schema
     * each is asserted against is stated by the cases rather than by the list.
     */
    private static final List<String> COMMON_ROUTED_FIELDS =
            List.of("sourceCorrelationId", "payloadFileServiceId", "originatingSource");

    private final DocumentOutcomeSink sink = mock(DocumentOutcomeSink.class);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final GenerationMetrics metrics = new GenerationMetrics(registry);

    private final DeliveryObserver deliveries = mock(DeliveryObserver.class);

    private final DocumentEventListener listener =
            new DocumentEventListener(sink, metrics, deliveries);

    /**
     * The framework envelope itself: a name and the event beside it.
     *
     * <p>Read as two halves because they are two different things to this service. The name decides
     * which of systemdocgenerator's two announcements this is; the payload is systemdocgenerator's
     * own schema, vendored under {@code contracts/systemdocgenerator/} and never redefined here,
     * which is why it is carried as a tree rather than as a model of theirs.
     */
    @Nested
    @DisplayName("the framework JsonEnvelope")
    class Envelope {

        @Test
        void the_event_name_should_be_read_from_the_metadata() {
            final PublicEventEnvelope envelope =
                    PublicEventEnvelope.parse(ourDocumentAvailable());

            assertThat(envelope.metadataName())
                    .isEqualTo(DocumentEventListener.DOCUMENT_AVAILABLE);
        }

        @Test
        void the_events_own_fields_should_be_the_payload() {
            final PublicEventEnvelope envelope =
                    PublicEventEnvelope.parse(ourDocumentAvailable());

            assertThat(envelope.payload().path("sourceCorrelationId").stringValue())
                    .isEqualTo(BATCH_ID.toString());
            assertThat(envelope.payload().path("documentFileServiceId").stringValue())
                    .isEqualTo(DOCUMENT_FILE_ID.toString());
            assertThat(envelope.payload().path("templateIdentifier").stringValue())
                    .isEqualTo("OEE_Layout5");
        }

        /**
         * The envelope's two halves stay two halves. Handing the whole body on as the payload would
         * make {@code _metadata} part of systemdocgenerator's schema, which it is not: it is the
         * framework's, and the only two facts of it this service reads are already the record's
         * own.
         */
        @Test
        void the_metadata_should_not_also_be_inside_the_payload() {
            final PublicEventEnvelope envelope =
                    PublicEventEnvelope.parse(ourDocumentAvailable());

            assertThat(envelope.payload().has("_metadata")).isFalse();
        }
    }

    /**
     * The broker's filter, which is why a subscriber to the estate's topic is handed two event names
     * rather than all of them.
     *
     * <p>Asserted against the shipped {@code application.yaml} rather than against a value this test
     * invents, because the selector and the listener have to name the same two events and nothing
     * else would notice if they stopped: a selector naming an event the listener drops is a
     * subscription doing work for nobody, and a listener expecting an event the selector excludes is
     * a batch that is released unrendered by the next run, every night, for ever.
     */
    @Nested
    @DisplayName("the CPPNAME selector the broker applies")
    class Selector {

        @Test
        void the_shipped_selector_should_name_exactly_the_two_events_the_listener_routes()
                throws IOException {
            final String selector = shippedSelector();

            assertThat(selector).contains(DocumentEventListener.EVENT_NAME_PROPERTY);
            assertThat(SELECTOR_EVENT_NAME.matcher(selector).results()
                    .map(match -> match.group(1))
                    .toList())
                    .containsExactly(DocumentEventListener.DOCUMENT_AVAILABLE,
                            DocumentEventListener.GENERATION_FAILED);
        }

        /**
         * The selector is the broker's filter and not this service's only one. A subscription
         * created before the selector was set, or a broker that ignores it, delivers the whole
         * topic; every one of those messages is somebody else's, and none of them may reach the
         * sink.
         */
        @Test
        void an_event_the_selector_does_not_name_should_not_be_routed() throws JMSException {
            listener.onPublicEvent(message("public.progression.events.court-register-generated",
                    otherContextEvent()));

            verifyNoInteractions(sink);
        }
    }

    /**
     * A document somebody else asked systemdocgenerator for.
     *
     * <p>Spec US2 scenario 6, and the reason the render request carries this service's own
     * {@code originatingSource} in the first place: two subscribers to one topic, each rendering
     * court registers through the same generator, with nothing but the source to tell whose document
     * has just been announced.
     */
    @Nested
    @DisplayName("a document another service asked for")
    class ForeignDocuments {

        @Test
        void should_not_reach_the_sink() throws JMSException {
            listener.onPublicEvent(message(DocumentEventListener.DOCUMENT_AVAILABLE,
                    documentAvailable(PROGRESSION_SOURCE)));

            verifyNoInteractions(sink);
        }

        /**
         * Acknowledged, which here means the handler returns. A handler that threw would nack a
         * message that is never going to become ours, and a durable subscription offers a nacked
         * message again until somebody empties it by hand.
         */
        @Test
        void should_be_acknowledged_rather_than_left_for_the_broker_to_redeliver()
                throws JMSException {
            final TextMessage message = message(DocumentEventListener.DOCUMENT_AVAILABLE,
                    documentAvailable(PROGRESSION_SOURCE));

            assertThatCode(() -> listener.onPublicEvent(message)).doesNotThrowAnyException();
        }

        @Test
        void should_be_counted_so_that_a_silent_subscription_is_still_a_visible_one()
                throws JMSException {
            listener.onPublicEvent(message(DocumentEventListener.DOCUMENT_AVAILABLE,
                    documentAvailable(PROGRESSION_SOURCE)));

            assertThat(ignored(GenerationMetrics.FOREIGN_SOURCE)).isEqualTo(1);
        }

        /**
         * {@code originatingSource} is optional in systemdocgenerator's schema, and an event without
         * one is not this service's: this service always sends it, so an outcome that carries none
         * answers a request that was not ours.
         */
        @Test
        void an_event_carrying_no_source_at_all_should_be_ignored_and_counted()
                throws JMSException {
            listener.onPublicEvent(message(DocumentEventListener.DOCUMENT_AVAILABLE,
                    documentAvailableWithoutSource()));

            verifyNoInteractions(sink);
            assertThat(ignored(GenerationMetrics.FOREIGN_SOURCE)).isEqualTo(1);
        }
    }

    /**
     * A document this service asked for, which is the only kind that moves a batch.
     *
     * <p>Both outcomes go to the same port naming EVENT, which is the one way an outcome is
     * learned: the topic is the whole of what this service hears about a render it asked for.
     */
    @Nested
    @DisplayName("a document this service asked for")
    class OurDocuments {

        @Test
        void a_document_available_should_reach_the_sink_with_both_ids_and_the_generated_instant()
                throws JMSException {
            listener.onPublicEvent(message(DocumentEventListener.DOCUMENT_AVAILABLE,
                    ourDocumentAvailable()));

            verify(sink).documentAvailable(BATCH_ID, PAYLOAD_FILE_ID, DOCUMENT_FILE_ID,
                    GENERATED_AT, CompletedBy.EVENT);
        }

        /**
         * The generator's own words travel with the failure. They are what a support call is
         * answered from, and they are the half progression drops - it logs the event and records
         * nothing, which is defect P2, pinned in the sink's own suite.
         */
        @Test
        void a_generation_failed_should_reach_the_sink_with_the_generators_own_reason()
                throws JMSException {
            listener.onPublicEvent(message(DocumentEventListener.GENERATION_FAILED,
                    generationFailed(DocumentEventListener.ORIGINATING_SOURCE)));

            verify(sink).generationFailed(BATCH_ID, PAYLOAD_FILE_ID, SDG_REASON, FAILED_AT,
                    CompletedBy.EVENT);
        }

        /**
         * {@code sourceCorrelationId} is optional in the schema and mandatory in practice: without
         * it there is no batch to apply the outcome to, and inventing one from the payload id would
         * be this service guessing at somebody else's document.
         *
         * <p>Nothing is routed, and the event is still counted. It is one of ours by its source, so
         * it is not the foreign-source reading; it is an announcement that reached this
         * subscription and was applied to nothing, which is the question
         * {@code yotresultsdistribution_public_events_ignored_total} answers. An event dropped here without
         * a count is an outcome that vanished between the renderer and the register, and a night of
         * them would look exactly like a night nothing was published at all.
         */
        @Test
        void an_outcome_that_names_no_batch_should_be_ignored_and_counted() throws JMSException {
            listener.onPublicEvent(message(DocumentEventListener.DOCUMENT_AVAILABLE,
                    documentAvailableWithoutCorrelation()));

            verifyNoInteractions(sink);
            assertThat(ignored(GenerationMetrics.UNKNOWN_CORRELATION))
                    .as("an outcome this service cannot attribute is counted under the reason that "
                            + "is true of it, exactly as the sink counts the one whose correlation "
                            + "names a batch this store has never held")
                    .isEqualTo(1);
        }

        /**
         * The other absence, and it is not the same one. An event that names its batch and leaves
         * out {@code payloadFileServiceId} has nothing to cross-check the correlation against, so
         * it is applied nowhere either - but its correlation was never in doubt, and counting it
         * as an unknown correlation would put it in the reading a correlation lost between the
         * render request and the topic is chased by. It gets a bounded reason of its own on the
         * same counter, so the two are added together when the question is "how many
         * announcements went nowhere" and told apart when the question is which fault.
         *
         * <p>The fixture leaves out a member the schema requires, which is deliberate and is why
         * it is not among the contract-legal ones above: a document-available that satisfies
         * systemdocgenerator's schema always carries a payload id. This is the message a broker
         * hands over anyway - the same reason the selector case above exists - and the guard is
         * only worth having if its reading is honest about which half was missing.
         */
        @Test
        void an_outcome_naming_a_batch_but_no_payload_should_be_counted_under_its_own_reason()
                throws JMSException {
            listener.onPublicEvent(message(DocumentEventListener.DOCUMENT_AVAILABLE,
                    documentAvailableWithoutPayloadId()));

            verifyNoInteractions(sink);
            assertThat(ignored(GenerationMetrics.MISSING_PAYLOAD_ID))
                    .as("the absent payload id is the fault that happened, so it is the reason the "
                            + "event is counted under")
                    .isEqualTo(1);
            assertThat(ignored(GenerationMetrics.UNKNOWN_CORRELATION))
                    .as("and the unknown-correlation series is not touched: this event named its "
                            + "batch, and a reading that says otherwise sends support after a "
                            + "correlation that was never lost")
                    .isEqualTo(ABSENT);
        }

        /**
         * The third absence: an outcome of ours that names both identifiers and leaves out the
         * thing it is an outcome about.
         *
         * <p>A {@code document-available} with no document or no instant, and a
         * {@code generation-failed} with no instant, are applied nowhere for the same reason as the
         * two above - there is nothing to apply - and until now they were the only drops on this
         * listener that moved no counter. That mattered more once the grace-period reconciler went:
         * nothing re-asks about such a batch any more, so the whole of what happens to it is the
         * next run giving it back, and the only trace of the announcement that went nowhere was a
         * WARN in the log index. A path that drops something moves a counter.
         *
         * <p>One bounded reason for both shapes, because they are one fault - systemdocgenerator
         * announced an outcome without the fields that outcome consists of - and because the event
         * name is on the line beside it for whoever reads further. It is not
         * {@link GenerationMetrics#MISSING_PAYLOAD_ID}: that reading is about the cross-check going
         * missing from an otherwise complete announcement, and this one is about the announcement
         * itself being incomplete, which is a renderer to look at rather than a correlation.
         */
        @Test
        void a_document_available_with_no_document_should_be_counted_under_its_own_reason()
                throws JMSException {
            listener.onPublicEvent(message(DocumentEventListener.DOCUMENT_AVAILABLE,
                    documentAvailableWithoutTheDocument()));

            verifyNoInteractions(sink);
            assertThat(ignored(GenerationMetrics.INCOMPLETE_OUTCOME))
                    .as("an announcement missing the document it announces is dropped, and a drop "
                            + "that moves no counter is an outcome that vanished between the "
                            + "renderer and the register")
                    .isEqualTo(1);
            assertThat(ignored(GenerationMetrics.MISSING_PAYLOAD_ID))
                    .as("and it is not the missing-payload-id reading: this event carried its "
                            + "cross-check and left out its subject")
                    .isEqualTo(ABSENT);
        }

        /**
         * The same fault on the other event. A refusal with no {@code failedTime} cannot be applied
         * either - the instant is what the batch's ending is stamped with - so it is dropped, and
         * counted under the same reason as the document that announced no document.
         */
        @Test
        void a_generation_failed_with_no_instant_should_be_counted_under_the_same_reason()
                throws JMSException {
            listener.onPublicEvent(message(DocumentEventListener.GENERATION_FAILED,
                    generationFailedWithoutInstant()));

            verifyNoInteractions(sink);
            assertThat(ignored(GenerationMetrics.INCOMPLETE_OUTCOME))
                    .as("both shapes of an outcome that is missing what it is about are one fault, "
                            + "counted on one series, with the event name on the line beside it")
                    .isEqualTo(1);
        }

        /**
         * The header and the envelope have to agree. {@code CPPNAME} is what the broker selected on
         * and the envelope is what the message says it is; where they differ the message is not what
         * the header claimed, and routing it on the header alone would apply a failure as a
         * generation or the other way about.
         */
        @Test
        void an_event_whose_header_and_envelope_disagree_should_not_be_routed()
                throws JMSException {
            listener.onPublicEvent(message(DocumentEventListener.DOCUMENT_AVAILABLE,
                    generationFailed(DocumentEventListener.ORIGINATING_SOURCE)));

            verifyNoInteractions(sink);
        }
    }

    /**
     * The contract the fixtures above are only useful if they honour.
     *
     * <p>systemdocgenerator owns these two events and this service adapts to them (constitution
     * Principle III), so the vendored copies under
     * {@code specs/002-consolidate-progression-leg/contracts/systemdocgenerator/} are what every
     * fixture in this suite is measured against. Two claims, and they fail in opposite directions:
     * a fixture that does not satisfy the schema is a suite testing a message the platform never
     * sends, and a routed field the schema does not declare is a listener reading a field the
     * platform never fills.
     */
    @Nested
    @DisplayName("the vendored systemdocgenerator event schemas")
    class VendoredContracts {

        @Test
        void the_document_available_fixture_should_be_a_message_the_platform_could_publish() {
            assertThat(refusals(DOCUMENT_AVAILABLE_SCHEMA, ourDocumentAvailable()))
                    .as("every case above hands the listener this payload, so a drift between it "
                            + "and the schema would let the whole suite pass against a message "
                            + "systemdocgenerator does not send")
                    .isEmpty();
        }

        @Test
        void the_generation_failed_fixture_should_be_a_message_the_platform_could_publish() {
            assertThat(refusals(GENERATION_FAILED_SCHEMA,
                    generationFailed(DocumentEventListener.ORIGINATING_SOURCE)))
                    .as("the refusal half of the same claim, and the one carrying the generator's "
                            + "own reason")
                    .isEmpty();
        }

        /**
         * The two members the schema makes optional are the two the suite leaves out, and leaving
         * them out has to stay contract-legal: those cases are about what the listener does with a
         * message it may really receive, and they would say nothing about a message that could not
         * exist.
         */
        @Test
        void the_fixtures_that_leave_an_optional_member_out_should_still_satisfy_the_schema() {
            assertThat(refusals(DOCUMENT_AVAILABLE_SCHEMA, documentAvailableWithoutSource()))
                    .as("originatingSource is optional, and an event without one is what the "
                            + "foreign-source filter is asserted on")
                    .isEmpty();
            assertThat(refusals(DOCUMENT_AVAILABLE_SCHEMA, documentAvailableWithoutCorrelation()))
                    .as("sourceCorrelationId is optional, and an event without one is what the "
                            + "names-no-batch case is asserted on")
                    .isEmpty();
        }

        /**
         * The negative control, and the reason the three claims above are worth anything.
         *
         * <p>A validator whose references had silently failed to resolve, or one applied to the
         * wrong half of the envelope, would return no refusals for everything put in front of it
         * and every assertion above would pass for ever. This one hands it a payload that is
         * missing a field the schema requires and states that it is refused.
         */
        @Test
        void a_payload_missing_a_field_the_schema_requires_should_be_refused() {
            assertThat(refusals(DOCUMENT_AVAILABLE_SCHEMA, documentAvailableWithoutTheDocument()))
                    .as("documentFileServiceId is required, so a validator that accepted this one "
                            + "is a validator that is checking nothing at all")
                    .isNotEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"documentFileServiceId", "generatedTime"})
        void every_field_a_document_available_is_routed_by_should_be_declared(final String field) {
            assertThat(declaredProperties(DOCUMENT_AVAILABLE_SCHEMA))
                    .as("the listener reads this field out of a document-available; a field the "
                            + "schema does not declare is one the platform never fills, and every "
                            + "real event would be acknowledged and dropped for a reason nothing "
                            + "here would show")
                    .contains(field);
        }

        @ParameterizedTest
        @ValueSource(strings = {"failedTime", "reason"})
        void every_field_a_generation_failed_is_routed_by_should_be_declared(final String field) {
            assertThat(declaredProperties(GENERATION_FAILED_SCHEMA))
                    .as("the same claim for the refusal, whose reason is what a support call is "
                            + "answered from")
                    .contains(field);
        }

        @Test
        void the_fields_both_events_are_routed_by_should_be_declared_by_both_schemas() {
            assertThat(declaredProperties(DOCUMENT_AVAILABLE_SCHEMA))
                    .as("the correlation, its cross-check and the source filter are read from "
                            + "whichever of the two arrives")
                    .containsAll(COMMON_ROUTED_FIELDS);
            assertThat(declaredProperties(GENERATION_FAILED_SCHEMA))
                    .as("so a field declared by only one of the two would be a filter that worked "
                            + "for documents and not for refusals")
                    .containsAll(COMMON_ROUTED_FIELDS);
        }
    }

    /**
     * The reading that says the subscription is being served at all.
     *
     * <p>{@code PublicEventsHealthIndicator} publishes {@code lastDeliveryAt} and
     * {@code lastDeliveryAgeSeconds}, and its own javadoc says what has to feed them: <em>every</em>
     * delivery, not only the two this service acts on. Nothing else can feed them - the container
     * knows only that it is running, and a service that had heard nothing all evening and a service
     * whose broker had stopped serving it look identical from anywhere but here.
     *
     * <p>So the observation is made before any filter. progression's leg is still deployed, still
     * subscribed and still renders through the same systemdocgenerator: on a night the legacy
     * generates and this service does not, its documents are the only proof the subscription is
     * alive, and an observer told only about our own events would report an outage every one of
     * those nights.
     */
    @Nested
    @DisplayName("the age of the last delivery")
    class Deliveries {

        @Test
        void a_delivery_this_service_acts_on_should_be_recorded() throws JMSException {
            listener.onPublicEvent(message(DocumentEventListener.DOCUMENT_AVAILABLE,
                    ourDocumentAvailable()));

            verify(deliveries).recordDelivery();
        }

        @Test
        void a_delivery_for_another_services_document_should_be_recorded_too() throws JMSException {
            listener.onPublicEvent(message(DocumentEventListener.DOCUMENT_AVAILABLE,
                    documentAvailable(PROGRESSION_SOURCE)));

            verify(deliveries).recordDelivery();
        }

        /**
         * Before every filter, and this is the one that proves it: a message the listener cannot
         * even read off the subscription still reached this pod, and a broker that is delivering
         * rubbish is a broker that is delivering.
         */
        @Test
        void a_delivery_that_could_not_be_read_should_still_be_recorded() throws JMSException {
            final TextMessage unreadable = mock(TextMessage.class);
            when(unreadable.getStringProperty(DocumentEventListener.EVENT_NAME_PROPERTY))
                    .thenThrow(new JMSException("the broker could not hand the message over"));

            listener.onPublicEvent(unreadable);

            verify(deliveries).recordDelivery();
        }
    }

    /**
     * One vendored schema, assembled with its references resolved against the vendored copies.
     *
     * @param fileName the schema's file name under {@link #VENDORED}
     * @return the assembled contract
     */
    private static Schema vendoredSchema(final String fileName) {
        final SchemaRegistryConfig config = SchemaRegistryConfig.builder()
                .formatAssertionsEnabled(Boolean.TRUE)
                .pathType(PathType.JSON_POINTER)
                .preloadSchema(true)
                .build();
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_4,
                        builder -> builder
                                .schemaRegistryConfig(config)
                                .schemaIdResolvers(resolvers -> resolvers.mappings(VENDORED_REFS)))
                .getSchema(vendoredText(fileName));
    }

    /** The text of a vendored schema, or a failure that names the copy that is missing. */
    private static String vendoredText(final String fileName) {
        final Path schema = VENDORED.resolve(fileName);
        try {
            return Files.readString(schema, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(
                    "the vendored systemdocgenerator contract must be committed at " + schema,
                    unreadable);
        }
    }

    /** The properties one vendored schema declares, which is what the routing may read. */
    private static List<String> declaredProperties(final String fileName) {
        return List.copyOf(MAPPER.readTree(vendoredText(fileName)).get("properties").propertyNames());
    }

    /**
     * What the vendored schema makes of one fixture's payload.
     *
     * <p>The payload and not the whole envelope: {@code _metadata} is the framework's and is not in
     * systemdocgenerator's schema at all, which is exactly the split
     * {@link PublicEventEnvelope} makes and this suite's first nested class pins.
     *
     * @param fileName the schema's file name under {@link #VENDORED}
     * @param envelope the fixture, as the platform would publish it
     * @return every rule the payload broke, empty where it satisfies the contract
     */
    private static List<Error> refusals(final String fileName, final String envelope) {
        return vendoredSchema(fileName).validate(PublicEventEnvelope.parse(envelope).payload());
    }

    /**
     * One delivery off the topic: the body as text, and the name as the property the broker selected
     * on.
     *
     * @param eventName what {@code CPPNAME} carries
     * @param body      the envelope
     * @return the message the listener is handed
     * @throws JMSException never; the JMS accessors declare it
     */
    private static TextMessage message(final String eventName, final String body)
            throws JMSException {
        final TextMessage message = mock(TextMessage.class);
        when(message.getStringProperty(DocumentEventListener.EVENT_NAME_PROPERTY))
                .thenReturn(eventName);
        when(message.getText()).thenReturn(body);
        return message;
    }

    /**
     * How many events were ignored for one bounded reason.
     *
     * @param reason the {@code reason} label
     * @return the count, or {@link #ABSENT} where the series does not exist
     */
    private double ignored(final String reason) {
        final Counter counter = registry.find(GenerationMetrics.PUBLIC_EVENTS_IGNORED)
                .tag(GenerationMetrics.REASON_TAG, reason)
                .counter();
        return counter == null ? ABSENT : counter.count();
    }

    /**
     * The selector line the service ships, read off the file rather than from a property this test
     * sets, because the file is the thing that has to be right.
     *
     * @return the shipped {@code yotresultsdistribution.publicevents.selector} line
     * @throws IOException if the shipped configuration cannot be read
     */
    private static String shippedSelector() throws IOException {
        return Files.readAllLines(Path.of("src", "main", "resources", "application.yaml"),
                        StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(line -> line.startsWith("selector:"))
                .findFirst()
                .orElse("<application.yaml declares no publicevents selector>");
    }

    /**
     * The {@code document-available} envelope for a document this service asked for.
     *
     * @return the envelope as text
     */
    private static String ourDocumentAvailable() {
        return documentAvailable(DocumentEventListener.ORIGINATING_SOURCE);
    }

    /**
     * A {@code document-available} envelope as the platform publishes it, for a document requested
     * by the given source.
     *
     * @param originatingSource the source the render was requested under
     * @return the envelope as text
     */
    private static String documentAvailable(final String originatingSource) {
        return documentAvailableEnvelope(
                "  \"sourceCorrelationId\": \"" + BATCH_ID + "\",\n",
                "  \"originatingSource\": \"" + originatingSource + "\"\n");
    }

    /**
     * The same envelope with the optional {@code originatingSource} absent, which the schema allows
     * and this service never sends.
     *
     * @return the envelope as text
     */
    private static String documentAvailableWithoutSource() {
        return documentAvailableEnvelope(
                "  \"sourceCorrelationId\": \"" + BATCH_ID + "\"\n", "");
    }

    /**
     * The same envelope with the optional {@code sourceCorrelationId} absent, which is an outcome
     * with no batch to apply it to.
     *
     * @return the envelope as text
     */
    private static String documentAvailableWithoutCorrelation() {
        return documentAvailableEnvelope("",
                "  \"originatingSource\": \"" + DocumentEventListener.ORIGINATING_SOURCE + "\"\n");
    }

    /**
     * The same envelope with {@code payloadFileServiceId} taken out, which is an outcome that names
     * its batch and nothing to cross-check it against.
     *
     * <p>The schema requires the member, so this is not a message systemdocgenerator publishes; it
     * is one the guard in the listener exists for, and what the guard has to get right is which of
     * the two identifiers was missing.
     *
     * @return the envelope as text
     */
    private static String documentAvailableWithoutPayloadId() {
        return ourDocumentAvailable()
                .replace("  \"payloadFileServiceId\": \"" + PAYLOAD_FILE_ID + "\",\n", "");
    }

    /**
     * The same envelope with a member the schema <em>requires</em> taken out.
     *
     * <p>The negative control, and nothing routes it: it exists so that a validator that had
     * stopped refusing anything is caught by an assertion rather than trusted.
     *
     * @return the envelope as text, one required member short
     */
    private static String documentAvailableWithoutTheDocument() {
        return ourDocumentAvailable()
                .replace("  \"documentFileServiceId\": \"" + DOCUMENT_FILE_ID + "\",\n", "");
    }

    /**
     * The {@code document-available} envelope, with the two optional members supplied by the caller
     * so that a case can leave either of them out exactly as the schema does.
     *
     * @param correlationMember the {@code sourceCorrelationId} member, or empty
     * @param sourceMember      the {@code originatingSource} member, or empty
     * @return the envelope as text
     */
    private static String documentAvailableEnvelope(final String correlationMember,
            final String sourceMember) {
        return """
                {
                  "_metadata": {
                    "id": "1c0f9d84-1f3a-4a55-9d6b-8c2e5a7b90f1",
                    "name": "%s",
                    "createdAt": "2026-09-05T17:04:11.500Z",
                    "source": "systemdocgenerator",
                    "stream": {"id": "%s"},
                    "correlation": {"client": "systemdocgenerator"}
                  },
                  "payloadFileServiceId": "%s",
                  "templateIdentifier": "OEE_Layout5",
                  "conversionFormat": "pdf",
                  "requestedTime": "2026-09-05T18:03:58.117+01:00",
                  "documentFileServiceId": "%s",
                  "generatedTime": "2026-09-05T18:04:11.412+01:00",
                  "generateVersion": 1,
                %s%s}"""
                .formatted(DocumentEventListener.DOCUMENT_AVAILABLE, BATCH_ID, PAYLOAD_FILE_ID,
                        DOCUMENT_FILE_ID, correlationMember, sourceMember);
    }

    /**
     * A {@code generation-failed} envelope as the platform publishes it, carrying the generator's
     * own reason.
     *
     * @param originatingSource the source the render was requested under
     * @return the envelope as text
     */
    private static String generationFailed(final String originatingSource) {
        return """
                {
                  "_metadata": {
                    "id": "3d7e5b21-6a04-4c19-8f52-71b0d9e3a4c8",
                    "name": "%s",
                    "createdAt": "2026-09-05T17:06:23.100Z",
                    "source": "systemdocgenerator",
                    "stream": {"id": "%s"},
                    "correlation": {"client": "systemdocgenerator"}
                  },
                  "payloadFileServiceId": "%s",
                  "templateIdentifier": "OEE_Layout5",
                  "conversionFormat": "pdf",
                  "requestedTime": "2026-09-05T18:03:58.117+01:00",
                  "failedTime": "2026-09-05T18:06:23.004+01:00",
                  "reason": "%s",
                  "sourceCorrelationId": "%s",
                  "originatingSource": "%s"
                }"""
                .formatted(DocumentEventListener.GENERATION_FAILED, BATCH_ID, PAYLOAD_FILE_ID,
                        SDG_REASON, BATCH_ID, originatingSource);
    }

    /**
     * A {@code generation-failed} for a document this service asked for, with {@code failedTime}
     * taken out.
     *
     * <p>The schema requires the member, so this is not a refusal systemdocgenerator publishes; it
     * is the one the guard in the listener exists for. Without the instant there is nothing to
     * stamp the batch's ending with, so the event is applied nowhere and counted.
     *
     * @return the envelope as text, one required member short
     */
    private static String generationFailedWithoutInstant() {
        return generationFailed(DocumentEventListener.ORIGINATING_SOURCE)
                .replace("  \"failedTime\": \"2026-09-05T18:06:23.004+01:00\",\n", "");
    }

    /**
     * A public event from another context altogether, which is what the whole topic looks like when
     * the selector is not doing its job.
     *
     * @return the envelope as text
     */
    private static String otherContextEvent() {
        return """
                {
                  "_metadata": {
                    "id": "9f14c2a7-8b3d-4e60-91a5-2c7f6b0d84e3",
                    "name": "public.progression.events.court-register-generated",
                    "createdAt": "2026-09-05T17:04:11.500Z",
                    "source": "progression",
                    "stream": {"id": "%s"}
                  },
                  "courtCentreId": "ac21d0f1-8f45-4d9c-9a3e-6b0c5d2e7a11"
                }""".formatted(BATCH_ID);
    }

    /**
     * What this service writes down about a field it could not read.
     *
     * <p>The values in a public event are another context's, and a field that should have carried
     * an identity or a time carried something else: whatever it did carry is data this service
     * never asked for and cannot vouch for. So the reading is written down - which field, and what
     * refused to read it - and the value is not, because a value nobody validated may be anything,
     * including a person's own details, and a log line is the one place it must not turn up
     * (constitution Principle VII). The same rule the operations commands were held to for what an
     * operator types, one door along, for what another service publishes.
     */
    @Nested
    @DisplayName("a field this service could not read")
    class UnreadableFields {

        @Test
        void a_correlation_that_is_not_an_identity_should_not_be_quoted() throws JMSException {
            try (CapturedLog log = CapturedLog.capturing(DocumentEventListener.class)) {
                listener.onPublicEvent(message(DocumentEventListener.DOCUMENT_AVAILABLE,
                        documentAvailableEnvelope("  \"sourceCorrelationId\": \""
                                        + PersonalDataMarkers.OPERATOR_TOKEN + "\",\n",
                                "  \"originatingSource\": \""
                                        + DocumentEventListener.ORIGINATING_SOURCE + "\"\n")));

                assertThat(log.renderings())
                        .as("the field is named because a reader needs to know which one, and the "
                                + "value is not, because another context's unvalidated value may "
                                + "be anything at all")
                        .anySatisfy(line -> assertThat(line)
                                .contains("sourceCorrelationId")
                                .doesNotContain(PersonalDataMarkers.OPERATOR_TOKEN));
                assertThat(log.renderings())
                        .noneMatch(line -> line.contains(PersonalDataMarkers.OPERATOR_TOKEN));
            }
        }

        @Test
        void a_generated_time_that_is_not_a_date_time_should_not_be_quoted() throws JMSException {
            try (CapturedLog log = CapturedLog.capturing(DocumentEventListener.class)) {
                listener.onPublicEvent(message(DocumentEventListener.DOCUMENT_AVAILABLE,
                        ourDocumentAvailable().replace("2026-09-05T18:04:11.412+01:00",
                                PersonalDataMarkers.OPERATOR_TOKEN)));

                assertThat(log.renderings())
                        .as("the same rule for the other reader: which field, and what refused it")
                        .anySatisfy(line -> assertThat(line)
                                .contains("generatedTime")
                                .doesNotContain(PersonalDataMarkers.OPERATOR_TOKEN));
                assertThat(log.renderings())
                        .noneMatch(line -> line.contains(PersonalDataMarkers.OPERATOR_TOKEN));
            }
        }
    }

    /**
     * What this service writes down about a message it cannot take at its word.
     *
     * <p>The same rule as the fields above, one level out, and the two halves fail differently.
     * A body that would not parse is another context's text and the parser quotes what it choked
     * on: Jackson redacts the <em>source location</em> of a failure, which is not the same thing
     * as redacting the body, because an unquoted token is reported as
     * "Unrecognized token '&lt;the token&gt;'". And an envelope's own {@code _metadata.name} is
     * whatever the publisher put there - a message that disagrees with its own header has already
     * shown it is not what it claims, so its name is arbitrary text and may be anything at all,
     * a person's name or an address included (constitution Principle VII).
     *
     * <p>What is written instead is what this service owns on each side: the event the header
     * named, which the filter before the parse has already narrowed to one of the two constants
     * this class declares; the class that refused the body; and, where the envelope's name is one
     * of those two constants as well, the name itself. That last one is the reading a genuine
     * mismatch is diagnosed from, and the third case below is what keeps it.
     */
    @Nested
    @DisplayName("a message this service could not take at its word")
    class UnreadableMessages {

        @Test
        void a_body_that_would_not_parse_should_not_be_quoted() throws JMSException {
            try (CapturedLog log = CapturedLog.capturing(DocumentEventListener.class)) {
                listener.onPublicEvent(message(DocumentEventListener.DOCUMENT_AVAILABLE,
                        ourDocumentAvailable().replace("\"" + DOCUMENT_FILE_ID + "\"",
                                PersonalDataMarkers.OPERATOR_TOKEN)));

                assertThat(log.renderings())
                        .as("the event is named because a reader needs to know which announcement "
                                + "was dropped, and the body is not, because a body that would "
                                + "not parse is unvalidated text this service never asked for")
                        .anySatisfy(line -> assertThat(line)
                                .contains(DocumentEventListener.DOCUMENT_AVAILABLE)
                                .doesNotContain(QUOTED_BACK));
                assertThat(log.renderings()).noneMatch(line -> line.contains(QUOTED_BACK));
            }
        }

        /**
         * The reading a broker feeding this subscription rubbish is seen by. Without it a topic
         * delivering nothing but unparseable bodies and a topic delivering nothing at all are the
         * same picture on a dashboard, the four reasons beside it all being counted downstream of
         * a parse that succeeded.
         */
        @Test
        void a_body_that_would_not_parse_should_be_counted_under_its_own_reason()
                throws JMSException {
            listener.onPublicEvent(message(DocumentEventListener.DOCUMENT_AVAILABLE,
                    ourDocumentAvailable().replace("\"" + DOCUMENT_FILE_ID + "\"",
                            PersonalDataMarkers.OPERATOR_TOKEN)));

            assertThat(ignored(GenerationMetrics.UNREADABLE_ENVELOPE))
                    .as("a delivery dropped before it could be read is still a delivery this "
                            + "subscription took, and the only place it can be seen is a counter")
                    .isEqualTo(1);
        }

        /**
         * Its own reason rather than a second reading of the one above: a body that will not parse
         * is a publisher writing malformed JSON, and a crossed pair is a message that parsed and
         * contradicts itself, which is the fault a subscription is re-declared over.
         */
        @Test
        void a_header_that_disagrees_with_its_envelope_should_be_counted_under_its_own_reason()
                throws JMSException {
            listener.onPublicEvent(message(DocumentEventListener.DOCUMENT_AVAILABLE,
                    generationFailed(DocumentEventListener.ORIGINATING_SOURCE)));

            assertThat(ignored(GenerationMetrics.HEADER_ENVELOPE_MISMATCH))
                    .as("the header and the envelope disagreeing is a fault of its own, and "
                            + "counting it as an unreadable body would send a reader after a "
                            + "publisher whose JSON was never malformed")
                    .isEqualTo(1);
            assertThat(ignored(GenerationMetrics.UNREADABLE_ENVELOPE))
                    .as("and it is not also counted as one that would not parse")
                    .isEqualTo(ABSENT);
        }

        @Test
        void an_envelope_naming_something_else_should_not_be_quoted() throws JMSException {
            try (CapturedLog log = CapturedLog.capturing(DocumentEventListener.class)) {
                listener.onPublicEvent(message(DocumentEventListener.DOCUMENT_AVAILABLE,
                        ourDocumentAvailable().replace(DocumentEventListener.DOCUMENT_AVAILABLE,
                                PersonalDataMarkers.OPERATOR_TOKEN)));

                assertThat(log.renderings())
                        .as("the header's own name is one of the two this subscription takes and "
                                + "is written down; the envelope's is arbitrary text from another "
                                + "context and may be anything at all, a name or an address "
                                + "included")
                        .anySatisfy(line -> assertThat(line)
                                .contains(DocumentEventListener.DOCUMENT_AVAILABLE)
                                .doesNotContain(PersonalDataMarkers.OPERATOR_TOKEN));
                assertThat(log.renderings())
                        .noneMatch(line -> line.contains(PersonalDataMarkers.OPERATOR_TOKEN));
            }
        }

        /**
         * <strong>[A]</strong> - a characterisation of the one reading a genuine mismatch is
         * diagnosed from, green on introduction, with no implementation commit following it.
         *
         * <p>Both sides of a crossed pair are this class's own two constants, so both may be
         * written down. It is here so that the case above cannot be satisfied by dropping the
         * envelope's side of the line altogether: that would take with it the only reading that
         * says which two events were crossed. The mismatch is counted as well now, under
         * {@code header-envelope-mismatch}, so a dropped line no longer costs the fault its only
         * trace - but a counter says how many and never which, and which is what a genuine
         * mismatch is diagnosed from.
         */
        @Test
        void a_crossed_pair_should_still_name_the_event_on_each_side() throws JMSException {
            try (CapturedLog log = CapturedLog.capturing(DocumentEventListener.class)) {
                listener.onPublicEvent(message(DocumentEventListener.DOCUMENT_AVAILABLE,
                        generationFailed(DocumentEventListener.ORIGINATING_SOURCE)));
                listener.onPublicEvent(message(DocumentEventListener.GENERATION_FAILED,
                        ourDocumentAvailable()));

                assertThat(log.renderings())
                        .as("a document-available header over a generation-failed envelope, and "
                                + "the same crossing the other way round; each side is one of the "
                                + "two events this subscription takes, so an operator can still "
                                + "tell which way round it happened")
                        .hasSize(2)
                        .allSatisfy(line -> assertThat(line)
                                .contains(DocumentEventListener.DOCUMENT_AVAILABLE)
                                .contains(DocumentEventListener.GENERATION_FAILED));
            }
        }
    }

    /**
     * What this service writes down about a delivery it could not read off the subscription.
     *
     * <p>The third door, and the one a sweep of the first two does not reach. A
     * {@code JMSException} is the broker client's own account of why it could not hand a message
     * over, and the text of it is that client's rather than this service's: a provider explaining a
     * body it could not decode, or a property it could not convert to a string, has the body and
     * the property in hand and nothing stops it quoting either. So its message is another context's
     * unvalidated value by exactly the reading a payload field's is, and a log index is the one
     * place it must not reach (constitution Principle VII).
     *
     * <p>Both accessors the listener calls are inside the one try, so one case holds the whole
     * statement; the body is the one used here because a body is the larger thing for a provider to
     * quote back. What is written instead is that a delivery was dropped and what refused it, and
     * there is nothing else to write: a message that would not come off the subscription named no
     * event, no batch and no payload, so the class is the whole of what this service knows of it.
     */
    @Nested
    @DisplayName("a delivery this service could not read off the subscription")
    class UnreadableDeliveries {

        @Test
        void a_broker_that_would_not_hand_a_message_over_should_not_be_quoted()
                throws JMSException {
            final TextMessage undeliverable = mock(TextMessage.class);
            when(undeliverable.getText()).thenThrow(new JMSException(
                    "the broker could not decode " + PersonalDataMarkers.OPERATOR_TOKEN));

            try (CapturedLog log = CapturedLog.capturing(DocumentEventListener.class)) {
                listener.onPublicEvent(undeliverable);

                assertThat(log.renderings())
                        .as("the drop is written down and the broker's own words are not, because "
                                + "a provider explaining a message it could not hand over may "
                                + "quote what that message carried")
                        .anySatisfy(line -> assertThat(line)
                                .contains("could not be read off the subscription")
                                .doesNotContain(PersonalDataMarkers.OPERATOR_TOKEN));
                assertThat(log.renderings())
                        .noneMatch(line -> line.contains(PersonalDataMarkers.OPERATOR_TOKEN));
            }
        }
    }
}
