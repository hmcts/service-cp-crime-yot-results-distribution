package uk.gov.hmcts.cp.yotresultsdistribution.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jms.UncategorizedJmsException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessagePostProcessor;
import uk.gov.hmcts.cp.filter.audit.model.AuditPayload;
import uk.gov.hmcts.cp.filter.audit.model.Metadata;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;

/**
 * What an operations call puts into its audit event, and what a call that cannot be audited does.
 *
 * <p>Two claims, and they are the two halves of FR-046. The first is about content: the event
 * carries the action, the outcome, an override, a run id and a superseded count - and carries
 * <strong>no</strong> request or response body, because {@code audit.http.include-payload-body} is
 * false and what the event needs is none of what a body holds. The second is about failure: the
 * library's own {@code AuditService.postMessageToArtemis} catches every {@code Exception}, logs it
 * and returns, so without a replacement an operations call could succeed with no audit event at
 * all - which Principle VI and Principle III(b) both refuse.
 *
 * <p>Driven through the publisher seam rather than over HTTP. The starter registers its
 * {@code AuditService} {@code @ConditionalOnMissingBean} (research R10), and the replacement is a
 * plain class over a {@link JmsTemplate}: the template is the seam, and what reaches it is exactly
 * what would have reached the audit broker.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("what an operations call is audited as")
class OperationsAuditFactsTest {

    /** The action this call is, as the action filter derives it and the caller cannot. */
    private static final String ACTION = "yot-results-distribution-operations.generate-register";

    /** The run id a regeneration answered with, which correlates the event to the run line. */
    private static final String RUN_ID = "9f2b6d44-6b1a-4f0a-9d24-0cc2b0d1f3aa";

    /**
     * The event's own name, which is what the audit context routes on.
     *
     * <p>It leaves as the {@code CPPNAME} property rather than inside the body, so an event that
     * left without it is an event the context never reads - an unaudited call in its terms,
     * whatever the JSON on it says.
     */
    private static final String EVENT_NAME = "audit.events.audit-recorded";

    private final JmsTemplate template = mock(JmsTemplate.class);

    /**
     * The mapper the starter builds, module for module.
     *
     * <p>Named here rather than taken from a context, because it is part of what this suite
     * asserts: the payload's {@code Optional} fields need the JDK 8 module and its instants need
     * the time module, and a mapper without either turns every event into a serialisation failure
     * that this class would then report as an audit outage.
     */
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module());

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final Counter unpublished =
            Counter.builder("yotresultsdistribution_operations_audit_unpublished").register(registry);

    private final OperationsAuditService service =
            new OperationsAuditService(template, mapper, unpublished);

    @InjectSoftAssertions
    private SoftAssertions softly;

    private OperationsAuditFacts facts;

    @BeforeEach
    void openTheCall() {
        facts = OperationsAuditFacts.open();
        facts.action(ACTION);
    }

    @AfterEach
    void closeTheCall() {
        OperationsAuditFacts.clear();
    }

    /**
     * One event as the filter builds it, with a body on it so a case can assert it is not kept.
     *
     * @param body what the filter captured, which on this surface is never published
     * @return the payload
     */
    private AuditPayload anEvent(final String body) {
        final ObjectNode content = mapper.createObjectNode();
        content.put("_payload", body);
        return new AuditPayload(content, "yotresultsdistribution-service", "yotresultsdistribution-service",
                "2026-09-21T09:00:00Z",
                new Metadata(UUID.randomUUID(), EVENT_NAME,
                        "2026-09-21T09:00:00Z", Optional.empty(),
                        Optional.of(new Metadata.Context("a-caller"))));
    }

    /**
     * One event whose content node the library did not build, which is a defect in its builder.
     *
     * @return the payload, with no content to write the bounded facts onto
     */
    private AuditPayload anEventWithNoContent() {
        return new AuditPayload(null, "yotresultsdistribution-service", "yotresultsdistribution-service",
                "2026-09-21T09:00:00Z",
                new Metadata(UUID.randomUUID(), EVENT_NAME,
                        "2026-09-21T09:00:00Z", Optional.empty(),
                        Optional.of(new Metadata.Context("a-caller"))));
    }

    /**
     * What the template was asked to send, as JSON.
     *
     * @param call which of the sends to read, counting from one
     * @return the event's body on the wire
     */
    private String published(final int call) {
        final org.mockito.ArgumentCaptor<Object> body =
                org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(template, org.mockito.Mockito.atLeast(call))
                .convertAndSend(any(jakarta.jms.Destination.class), body.capture(),
                        any(MessagePostProcessor.class));
        final List<Object> sent = body.getAllValues();
        return String.valueOf(sent.get(call - 1));
    }

    /**
     * The bounded fields the event carries, and the one thing it must not.
     */
    @Nested
    @DisplayName("the bounded facts on the event")
    class TheFacts {

        @Test
        void the_event_should_carry_the_action_and_what_came_of_the_call() {
            service.postMessageToArtemis(anEvent(null));

            softly.assertThat(published(1))
                    .as("the action is server-derived and is the one thing that says which of the "
                            + "seven this call was")
                    .contains("\"action\":\"" + ACTION + "\"")
                    .as("a call that reached no refusal succeeded, which is a fact rather than a "
                            + "gap: every refusal on this surface records itself")
                    .contains("\"outcome\":\"SUCCEEDED\"");
        }

        @Test
        void a_refused_call_should_carry_the_status_and_the_bounded_reason() {
            facts.refusedWith(409, OperationsReason.FLAG_OFF.wire());

            service.postMessageToArtemis(anEvent(null));

            softly.assertThat(published(1))
                    .as("a status family and a bounded code, and nothing composed from an "
                            + "exception or from what the caller sent")
                    .contains("\"outcome\":\"409 flag-off\"");
        }

        @Test
        void a_regeneration_should_carry_the_override_and_the_run_id() {
            facts.regeneration(true, RUN_ID);

            service.postMessageToArtemis(anEvent(null));

            final String event = published(1);
            softly.assertThat(event)
                    .as("an override is an operator's decision and is written down twice: here, "
                            + "with the caller's identity, and on the run report line")
                    .contains("\"flagOverride\":true")
                    .contains("\"runId\":\"" + RUN_ID + "\"");
        }

        @Test
        void a_supersession_should_carry_the_count_it_gave_up() {
            facts.superseded(47);

            service.postMessageToArtemis(anEvent(null));

            softly.assertThat(published(1))
                    .as("how much of the estate's history a rollback gave up is the fact this "
                            + "call is read for (data-model section 6)")
                    .contains("\"superseded\":47");
        }

        @Test
        void an_endpoint_that_had_neither_should_carry_neither() {
            service.postMessageToArtemis(anEvent(null));

            final String event = published(1);
            softly.assertThat(event)
                    .as("a field nothing recorded is left out rather than written as a null a "
                            + "reader has to interpret")
                    .doesNotContain("flagOverride")
                    .doesNotContain("runId")
                    .doesNotContain("superseded");
        }

        @Test
        void what_this_service_adds_to_the_event_should_be_bounded_and_no_body() {
            facts.refusedWith(409, OperationsReason.FLAG_OFF.wire());
            facts.regeneration(true, RUN_ID);
            facts.superseded(47);

            softly.assertThat(facts.asPublished())
                    .as("five fields and no sixth: what this service merges in is the action, what "
                            + "came of the call, the override, the run id and the count - a body "
                            + "is not among them and no field here could hold one")
                    .containsOnlyKeys("action", "outcome", "flagOverride", "runId", "superseded");
            softly.assertThat(facts.asPublished().values())
                    .as("each of them a bounded code, a boolean or a count")
                    .allMatch(value -> value instanceof String || value instanceof Boolean
                            || value instanceof Integer);
        }

        @Test
        void the_event_should_leave_stamped_with_the_property_the_audit_context_routes_on()
                throws jakarta.jms.JMSException {

            service.postMessageToArtemis(anEvent(null));

            final org.mockito.ArgumentCaptor<MessagePostProcessor> stamp =
                    org.mockito.ArgumentCaptor.forClass(MessagePostProcessor.class);
            verify(template).convertAndSend(any(jakarta.jms.Destination.class), any(),
                    stamp.capture());
            final jakarta.jms.Message message = mock(jakarta.jms.Message.class);
            stamp.getValue().postProcessMessage(message);

            verify(message).setStringProperty("CPPNAME", EVENT_NAME);
        }

        @Test
        void the_shipped_configuration_should_switch_the_librarys_body_capture_off()
                throws Exception {

            final String shipped;
            try (java.io.InputStream source = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream("application.yaml")) {
                shipped = new String(source.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }

            softly.assertThat(shipped)
                    .as("the library's default is TRUE, and left alone it would publish every "
                            + "response this surface writes - the batch listing's masked addresses "
                            + "and court-centre ids, the exception report's whole entry table and "
                            + "every ProblemDetail (research R10)")
                    .contains("include-payload-body: false");
            softly.assertThat(shipped)
                    .as("and the connect attempt is bounded, because the request event is "
                            + "published on the caller's own thread before the action: the "
                            + "library's ten attempts over a growing interval cost about two "
                            + "minutes of a held connection before a broker that is not there is "
                            + "reported as one")
                    .contains("initial-connect-attempts:");
        }
    }

    /**
     * What a publish that does not happen costs, which is not the same at both ends of the call.
     */
    @Nested
    @DisplayName("an event that did not reach the audit context")
    class WhenItCannotBePublished {

        @BeforeEach
        void theBrokerIsGone() {
            doThrow(new UncategorizedJmsException("ZQX7BROKER"))
                    .when(template).convertAndSend(any(jakarta.jms.Destination.class), any(),
                            any(MessagePostProcessor.class));
        }

        @Test
        void a_request_event_that_cannot_be_published_should_refuse_the_call() {
            assertThatThrownBy(() -> service.postMessageToArtemis(anEvent(null)))
                    .as("the request event is published before the action, which is the only "
                            + "moment there is still something to refuse: a call that cannot be "
                            + "audited must not proceed as though it had been")
                    .isInstanceOf(OperationsRefusedException.class)
                    .extracting(refused -> ((OperationsRefusedException) refused).reason())
                    .isEqualTo(OperationsReason.AUDIT_UNAVAILABLE);
        }

        @Test
        void a_publish_on_a_thread_serving_no_operations_call_should_refuse_nothing() {
            OperationsAuditFacts.clear();

            final List<String> lines;
            try (CapturedLog log = CapturedLog.capturing(OperationsAuditService.class)) {
                assertThatCode(() -> service.postMessageToArtemis(anEvent(null)))
                        .as("there is no open operations call to refuse: this bean is built "
                                + "wherever the audit transport is, and the filter that opens the "
                                + "facts only where the operations surface is served too. A "
                                + "refusal raised here would leave the audit filter with nothing "
                                + "above it to render it, after the response was committed")
                        .doesNotThrowAnyException();
                lines = log.renderings();
            }

            softly.assertThat(lines)
                    .as("not silent either: the shortfall is said and counted, which is the "
                            + "answer for every event nothing can be refused about")
                    .anyMatch(line -> line.contains(UncategorizedJmsException.class.getName()));
            softly.assertThat(registry.get("yotresultsdistribution_operations_audit_unpublished")
                            .counter().count())
                    .isEqualTo(1.0d);
        }

        @Test
        void a_response_event_that_cannot_be_published_should_not_change_the_answer() {
            facts.regeneration(false, RUN_ID);
            // The first publish is the request event; it refuses, and the second is the response
            // event of a call whose work is done.
            assertThatThrownBy(() -> service.postMessageToArtemis(anEvent(null)))
                    .isInstanceOf(OperationsRefusedException.class);

            final List<String> lines;
            try (CapturedLog log = CapturedLog.capturing(OperationsAuditService.class)) {
                assertThatCode(() -> service.postMessageToArtemis(anEvent(null)))
                        .as("nothing is left to refuse: the action has already happened and no "
                                + "status can say so to a caller whose work is done")
                        .doesNotThrowAnyException();
                lines = log.renderings();
            }

            softly.assertThat(lines)
                    .as("not silent: the recorded shortfall names the action and the run id, and "
                            + "the failure by class rather than by its own words")
                    .anyMatch(line -> line.contains("action=" + ACTION)
                            && line.contains("run_id=" + RUN_ID)
                            && line.contains(UncategorizedJmsException.class.getName())
                            && !line.contains("ZQX7BROKER"));
            softly.assertThat(registry.get("yotresultsdistribution_operations_audit_unpublished")
                            .counter().count())
                    .as("a path that drops something moves a counter - it being in the log index "
                            + "is not an alerting surface")
                    .isEqualTo(1.0d);
        }
    }

    /**
     * Which of the two publishes is which, since the library's payload does not say.
     */
    @Nested
    @DisplayName("telling the two events apart")
    class TheOrderOfThem {

        @Test
        void the_first_publish_of_a_call_should_be_its_request_event() {
            softly.assertThat(facts.firstPublish())
                    .as("the filter publishes exactly two, the request before the chain and the "
                            + "response after it, and both carry the same _metadata.name")
                    .isTrue();
            softly.assertThat(facts.firstPublish()).isFalse();
            softly.assertThat(facts.firstPublish()).isFalse();
        }

        @Test
        void a_thread_serving_no_operations_call_should_hold_no_facts() {
            OperationsAuditFacts.clear();

            softly.assertThat(OperationsAuditFacts.current())
                    .as("the container's threads are pooled, and facts left behind would be "
                            + "inherited by whatever ran next - which reads as true")
                    .isNull();

            assertThatCode(() -> service.postMessageToArtemis(anEvent(null)))
                    .as("an actuator call or anything else the filter sees is published unchanged")
                    .doesNotThrowAnyException();
        }

        @Test
        void a_request_event_of_nothing_should_refuse_the_call_rather_than_let_it_proceed() {
            final List<String> lines;
            try (CapturedLog log = CapturedLog.capturing(OperationsAuditService.class)) {
                assertThatThrownBy(() -> service.postMessageToArtemis(null))
                        .as("an event the library did not build is a request event that was not "
                                + "published, and a call that cannot be audited must not proceed "
                                + "as though it had been - the same answer a transport failure "
                                + "gets, because it is the same fact")
                        .isInstanceOf(OperationsRefusedException.class)
                        .extracting(refused -> ((OperationsRefusedException) refused).reason())
                        .isEqualTo(OperationsReason.AUDIT_UNAVAILABLE);
                lines = log.renderings();
            }

            verify(template, org.mockito.Mockito.never())
                    .convertAndSend(any(jakarta.jms.Destination.class), eq(null),
                            any(MessagePostProcessor.class));
            softly.assertThat(lines).isNotEmpty();
        }

        @Test
        void an_event_with_no_content_should_refuse_the_call_too() {
            assertThatThrownBy(() -> service.postMessageToArtemis(anEventWithNoContent()))
                    .as("the bounded facts are written onto the content node, so an event without "
                            + "one would reach the audit context carrying no action and no "
                            + "outcome - an event that says nothing is not an audited call")
                    .isInstanceOf(OperationsRefusedException.class)
                    .extracting(refused -> ((OperationsRefusedException) refused).reason())
                    .isEqualTo(OperationsReason.AUDIT_UNAVAILABLE);
        }

        @Test
        void a_response_event_of_nothing_should_be_counted_rather_than_refused() {
            facts.regeneration(false, RUN_ID);
            assertThatThrownBy(() -> service.postMessageToArtemis(null))
                    .isInstanceOf(OperationsRefusedException.class);

            final List<String> lines;
            try (CapturedLog log = CapturedLog.capturing(OperationsAuditService.class)) {
                assertThatCode(() -> service.postMessageToArtemis(null))
                        .as("the action has already happened and no status can say so to a caller "
                                + "whose work is done")
                        .doesNotThrowAnyException();
                lines = log.renderings();
            }

            softly.assertThat(lines)
                    .anyMatch(line -> line.contains("action=" + ACTION)
                            && line.contains("run_id=" + RUN_ID));
            softly.assertThat(registry.get("yotresultsdistribution_operations_audit_unpublished")
                            .counter().count())
                    .as("a path that drops something moves a counter")
                    .isEqualTo(1.0d);
        }
    }
}
