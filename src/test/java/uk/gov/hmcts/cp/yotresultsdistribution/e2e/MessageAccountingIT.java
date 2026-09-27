package uk.gov.hmcts.cp.yotresultsdistribution.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.models.SubQueue;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.application.HearingPayloadSource;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.config.ProcessingMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeadLetterReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.PayloadUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport.Row;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ServiceTestSupport;

/**
 * Spec SC-104: zero silent loss. Every message placed on the queue is accounted for as exactly one
 * outcome — and "exactly one" is the whole criterion.
 *
 * <p>The other suites each prove one path. This one proves the <strong>partition</strong>: a mixed
 * batch goes on the queue at once and every message in it is found in one bucket and no other. That
 * is a different claim, and the one an operator actually needs. A message counted twice is a register
 * somebody thinks was sent; a message counted nowhere is a register that vanished. Both look fine
 * from any single-path test.
 *
 * <p>The five buckets are the ones the criterion enumerates:
 *
 * <ol>
 *   <li>recorded COMPLETED, and gone from the queue;</li>
 *   <li>recorded FAILED, with the message parked on the dead-letter queue;</li>
 *   <li>parked as contract-invalid — on the dead-letter queue with a sanitised reason and a failure
 *       metric, and deliberately <em>no</em> processed-request row, because a body this service could
 *       not read may carry no usable key;</li>
 *   <li>actively in flight — locked and being processed, with a non-terminal record holding a live
 *       claim;</li>
 *   <li>still queued or in retry.</li>
 * </ol>
 *
 * <p>The fourth is the one a test usually cannot observe, because it lasts milliseconds. Here the
 * payload port holds one request open on a latch, so the accounting snapshot is taken while a run
 * genuinely is in flight rather than while the suite hopes one might be.
 *
 * <p><strong>How far this suite reaches, stated exactly.</strong> The broker and the store are real
 * — a Service Bus emulator and a Postgres container — and every settlement, claim and processed-log
 * row below is the running service's own. The three outward ports are <strong>not</strong> real:
 * the payload source is a Mockito bean, because the accounting needs a request that fails on demand
 * and a request that stays in flight until a latch is released, and neither is a thing a cache can
 * be asked for on cue; selecting the stub payload source selects the stub subscriptions source and
 * the stub submission client with it, so nothing here reaches a socket. That is the right trade for
 * a claim about <em>settlement</em>, which is what this file is about, and it is the wrong one for
 * any claim about what a delivery cost the world outside the pod. The suites that own real sockets
 * are the five that start {@code RegisterStackSupport}; the duplicate's negative — no second payload
 * read, no second subscriptions read, no second POST — is
 * {@code YotResultsDistributionEndToEndIT.should_touch_nothing_outside_the_pod_when_a_completed_request_is_delivered_again},
 * and it lives there rather than here for exactly that reason.
 *
 * <p><strong>And then the same claim under concurrency.</strong> The batch above arrives one message
 * at a time; production does not. A burst published at once is consumed
 * {@code maxConcurrentCalls} at a time, and the accounting has to survive that too: each request run
 * exactly once, each message accounted for exactly once. A request processed twice under concurrency
 * is the failure mode the claim is fenced against, and it does not show up in a batch that never
 * overlaps.
 *
 * <p>An acceptance suite over assembled behaviour: it may legitimately pass on introduction, and its
 * first observed result is recorded rather than a red run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// The context owns a running consumer on the shared emulator queue. Closing it with the class stops
// that consumer competing with the suites that run after this one.
class MessageAccountingIT {

    private static final Duration SETTLED_WITHIN = Duration.ofSeconds(120);
    private static final Duration POLL = Duration.ofSeconds(1);

    /** How long the held request waits before giving up, so a failure is never reported as a hang. */
    private static final Duration HELD_AT_MOST = Duration.ofMinutes(2);

    /** The broker's own concurrency, as `application.yaml` sets it: the burst is twice it and more. */
    private static final int BURST = 6;

    private static String connectionString;

    /**
     * The empty claim-check envelope: a hearing that gathered nobody, so every run that reaches the
     * end of the fetch completes {@code no-defendants} through the real transformation.
     *
     * <p>Nothing in it resembles hearing content — every defendant on a court register is a child —
     * but it is an envelope, because the pipeline reads the hearing and the share instant out of what
     * the payload port returns.
     */
    private static final JsonNode PLACEHOLDER =
            JacksonConfig.contractObjectMapper().readTree(
                    "{\"stub\":true,\"sharedTime\":\"1970-01-01T00:00:00Z\","
                            + "\"hearing\":{\"courtCentre\":{}}}");

    @MockitoBean
    private HearingPayloadSource payloadSource;

    @Autowired
    private MeterRegistry registry;

    private final UUID completing = UUID.randomUUID();
    private final UUID failing = UUID.randomUUID();
    private final UUID held = UUID.randomUUID();
    private final UUID invalid = UUID.randomUUID();

    /** Released once the accounting snapshot has been taken. */
    private final CountDownLatch release = new CountDownLatch(1);

    /** Where every message in the batch ends up, by the outcome the criterion names. */
    private enum Outcome {
        COMPLETED,
        FAILED_AND_PARKED,
        PARKED_AS_INVALID,
        IN_FLIGHT,
        QUEUED_OR_RETRYING
    }

    /** One published message: what it was, where it went. */
    private record Published(String label, UUID requestId, String messageId) {
    }

    @DynamicPropertySource
    static void wireTheContainers(final DynamicPropertyRegistry registry) {
        connectionString = ServiceBusEmulatorTestSupport.connectionString();
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
        registry.add("yotresultsdistribution.servicebus.connection-string", () -> connectionString);
        ServiceTestSupport.stubPayloadSource(registry);
        // Both identities: the service refuses to start a live client without one, and no suite here
        // reaches progression or the results query.
        registry.add("yotresultsdistribution.progression.system-user-id",
                () -> ServiceTestSupport.SYSTEM_USER_ID);
        registry.add("yotresultsdistribution.results.system-user-id",
                () -> ServiceTestSupport.SYSTEM_USER_ID);
    }

    @BeforeEach
    void controlThePayloadPort() {
        when(payloadSource.fetch(any(DistributionCommand.class))).thenAnswer(this::payloadFor);
    }

    @AfterEach
    void releaseTheHeldRequest() {
        release.countDown();
    }

    // --- the port, per request ------------------------------------------------------------------

    /**
     * The payload port, told apart by request.
     *
     * <p>A neighbouring suite's message on the shared queue is none of this suite's business and is
     * handed the placeholder, so it completes and leaves rather than being dragged into this
     * scenario.
     */
    private JsonNode payloadFor(final InvocationOnMock invocation) throws InterruptedException {
        final DistributionCommand command = invocation.getArgument(0);
        if (failing.equals(command.requestId())) {
            throw new PayloadUnavailableException(ReasonCode.PAYLOAD_UNAVAILABLE);
        }
        if (held.equals(command.requestId())) {
            // The run stays in flight — claim held, delivery locked — until the snapshot is taken.
            release.await(HELD_AT_MOST.toSeconds(), TimeUnit.SECONDS);
        }
        return PLACEHOLDER;
    }

    // --- publishing ------------------------------------------------------------------------

    private static String bodyFor(final UUID requestId) {
        return ServiceTestSupport.validBody(requestId, UUID.randomUUID());
    }

    private static String publish(final String body) {
        final String messageId = ServiceTestSupport.SOURCE + ':' + UUID.randomUUID();
        try (ServiceBusSenderClient sender = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .sender()
                .queueName(ServiceBusEmulatorTestSupport.QUEUE_NAME)
                .buildClient()) {
            sender.sendMessage(
                    new ServiceBusMessage(BinaryData.fromString(body)).setMessageId(messageId));
        }
        return messageId;
    }

    // --- reading the world back ------------------------------------------------------------

    private static Optional<Row> row(final UUID requestId) {
        return ProcessedLogTestSupport.row(ServiceTestSupport.SOURCE, requestId);
    }

    private static boolean onQueue(final String messageId) {
        return ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.NONE).isPresent();
    }

    private static boolean onDeadLetterQueue(final String messageId) {
        return ServiceBusEmulatorTestSupport
                .peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE).isPresent();
    }

    private static boolean hasStatus(final UUID requestId, final RequestStatus status) {
        return row(requestId).filter(found -> status.name().equals(found.status())).isPresent();
    }

    private static double counter(
            final MeterRegistry registry, final String name, final String tag, final String value) {
        final Counter found = registry.find(name).tag(tag, value).counter();
        return found == null ? 0 : found.count();
    }

    /**
     * What the two places an operator can look actually say about one message.
     *
     * @param status  what the processed log records, if it records anything
     * @param claimed whether a runner holds the request's claim
     * @param parked  whether the message is on the dead-letter queue
     * @param queued  whether the message is on the queue it arrived on
     */
    record Observed(Optional<RequestStatus> status, boolean claimed, boolean parked, boolean queued) {

        boolean recorded(final RequestStatus expected) {
            return status.filter(expected::equals).isPresent();
        }

        boolean nonTerminal() {
            return recorded(RequestStatus.RECEIVED) || recorded(RequestStatus.RETRYING);
        }

        boolean unrecorded() {
            return status.isEmpty();
        }
    }

    /**
     * <strong>Every</strong> outcome that currently describes a message, from all of the evidence.
     *
     * <p>The record's state and the message's whereabouts are read once, separately, and then each of
     * the five outcomes is evaluated against <em>all</em> of them. That is what makes the assertion
     * below mean anything. An outcome stated only in terms of the record — "the log says COMPLETED,
     * so it is completed" — cannot be contradicted by the queue, so a request recorded COMPLETED that
     * also has a copy sitting on the dead-letter queue would be reported as tidily accounted for.
     * That is two registers: one somebody believes was sent, and the same one parked as failed. Each
     * outcome therefore names where the message must be as well as what the log must say, and a
     * message whose evidence disagrees with itself satisfies none of them — an empty set, which fails
     * as loudly as a set of two.
     *
     * <p>The claim, not the queue, is what identifies a run in progress: a peek reports a locked
     * message exactly as it reports a waiting one, because locking is not deletion, whereas a claim
     * is held only while a runner is working.
     *
     * @param observed what the log and the broker say about one message
     * @return every outcome that describes it
     */
    static Set<Outcome> outcomesOf(final Observed observed) {
        final Set<Outcome> applicable = EnumSet.noneOf(Outcome.class);
        if (observed.recorded(RequestStatus.COMPLETED) && !observed.parked() && !observed.queued()) {
            applicable.add(Outcome.COMPLETED);
        }
        if (observed.recorded(RequestStatus.FAILED) && observed.parked() && !observed.queued()) {
            applicable.add(Outcome.FAILED_AND_PARKED);
        }
        if (observed.unrecorded() && observed.parked() && !observed.queued()) {
            applicable.add(Outcome.PARKED_AS_INVALID);
        }
        if (observed.nonTerminal() && observed.claimed() && observed.queued() && !observed.parked()) {
            // Queue presence is required, not implied: a peek reports a locked message exactly as it
            // reports a waiting one, so a genuinely in-flight delivery IS still visible on the queue.
            // A claimed record whose message is on neither queue is a contradiction — the broker has
            // lost what the log says is being worked on — and must account for nothing.
            applicable.add(Outcome.IN_FLIGHT);
        }
        if (observed.queued() && !observed.claimed() && !observed.parked()
                && (observed.unrecorded() || observed.nonTerminal())) {
            applicable.add(Outcome.QUEUED_OR_RETRYING);
        }
        return applicable;
    }

    private static Set<Outcome> outcomesFor(final Published message) {
        final Optional<Row> record = row(message.requestId());
        return outcomesOf(new Observed(
                record.map(found -> RequestStatus.valueOf(found.status())),
                record.map(found -> found.claimOwner() != null).orElse(false),
                onDeadLetterQueue(message.messageId()),
                onQueue(message.messageId())));
    }

    // --- the batch --------------------------------------------------------------------------

    @Test
    @DisplayName("a mixed batch lands in exactly one accounted outcome per message")
    void should_account_for_every_message_in_a_mixed_batch() {
        final double invalidParkedBefore = counter(registry, ProcessingMetrics.DEAD_LETTERED,
                ProcessingMetrics.REASON_TAG, DeadLetterReason.VALIDATION.label());

        // Built once and published twice: a duplicate is the SAME request, and a body that minted a
        // fresh hearing id per call would be a different one — an idempotency collision, which is its
        // own outcome and not the one this case is about.
        final String repeatable = bodyFor(completing);
        final Published valid = new Published("valid", completing, publish(repeatable));
        await().atMost(SETTLED_WITHIN).pollInterval(POLL)
                .until(() -> hasStatus(completing, RequestStatus.COMPLETED));

        // A duplicate of a request already completed: same key, fresh broker identity, so the
        // broker's own duplicate detection cannot be what settles it. The processed log must.
        //
        // What is asserted about it here is the accounting — one outcome, the record's, and the row
        // untouched. What it cost the outside world is asserted where the outside world is real,
        // over three countable sockets, in YotResultsDistributionEndToEndIT.
        final Published duplicate = new Published("duplicate", completing, publish(repeatable));
        await().atMost(SETTLED_WITHIN).pollInterval(POLL)
                .until(() -> !onQueue(duplicate.messageId()));

        final Published contractInvalid = new Published("contract-invalid", invalid,
                publish(ServiceTestSupport.contractInvalidBody(invalid, UUID.randomUUID())));
        await().atMost(SETTLED_WITHIN).pollInterval(POLL)
                .until(() -> onDeadLetterQueue(contractInvalid.messageId()));

        final Published doomed = new Published("failing", failing, publish(bodyFor(failing)));
        await().atMost(SETTLED_WITHIN).pollInterval(POLL)
                .until(() -> hasStatus(failing, RequestStatus.FAILED)
                        && onDeadLetterQueue(doomed.messageId()));

        final Published inFlight = new Published("in-flight", held, publish(bodyFor(held)));
        await().atMost(SETTLED_WITHIN).pollInterval(POLL)
                .until(() -> row(held).filter(found -> found.claimOwner() != null).isPresent());

        final List<Published> batch = List.of(valid, duplicate, contractInvalid, doomed, inFlight);
        final Map<String, Set<Outcome>> accounting = new LinkedHashMap<>();
        for (final Published message : batch) {
            accounting.put(message.label(), outcomesFor(message));
        }

        // Exclusivity first, and about every message at once: none of them may be describable two
        // ways, and none of them may be describable no way at all.
        assertThat(accounting)
                .as("exactly one outcome describes each message — not at least one, and not none")
                .allSatisfy((label, outcomes) -> assertThat(outcomes)
                        .as("%s was accounted for as %s", label, outcomes)
                        .hasSize(1));

        assertThat(accounting)
                .as("and it is the outcome the message earned")
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "valid", EnumSet.of(Outcome.COMPLETED),
                        // The duplicate is accounted for by the record it shares: acknowledged with
                        // no run, gone from the queue, and never parked.
                        "duplicate", EnumSet.of(Outcome.COMPLETED),
                        "contract-invalid", EnumSet.of(Outcome.PARKED_AS_INVALID),
                        "failing", EnumSet.of(Outcome.FAILED_AND_PARKED),
                        "in-flight", EnumSet.of(Outcome.IN_FLIGHT)));

        // A parked message is on the dead-letter queue and nowhere else. Left on both it would be
        // delivered again by the queue it is still sitting on, so support would be looking at a
        // parked copy of work that was quietly still running.
        assertThat(onQueue(contractInvalid.messageId()))
                .as("a message parked as contract-invalid has left the queue it arrived on")
                .isFalse();
        assertThat(onQueue(doomed.messageId()))
                .as("and so has a message parked after exhausting its deliveries")
                .isFalse();

        assertThat(row(completing).orElseThrow().attempts())
                .as("one run for the request, however many deliveries it received")
                .isEqualTo(1);
        assertThat(row(invalid))
                .as("a contract-invalid message never enters the state machine")
                .isEmpty();
        assertThat(counter(registry, ProcessingMetrics.DEAD_LETTERED,
                ProcessingMetrics.REASON_TAG, DeadLetterReason.VALIDATION.label()))
                .as("and is accounted for by its metric instead")
                .isEqualTo(invalidParkedBefore + 1);

        // Nothing is left in flight when the suite ends.
        release.countDown();
        await().atMost(SETTLED_WITHIN).pollInterval(POLL)
                .until(() -> hasStatus(held, RequestStatus.COMPLETED));
        assertThat(onDeadLetterQueue(inFlight.messageId()))
                .as("a request that was merely slow is not a request that failed")
                .isFalse();
    }

    // --- the same claim, under the concurrency the pod really runs at ----------------------------

    @Test
    @DisplayName("a burst consumed two at a time is still one run and one outcome per request")
    void should_account_for_every_message_in_a_burst_at_the_configured_concurrency() {
        final List<Published> burst = new ArrayList<>();
        for (int message = 0; message < BURST; message++) {
            final UUID requestId = UUID.randomUUID();
            burst.add(new Published("burst-" + message, requestId, publish(bodyFor(requestId))));
        }

        await().atMost(SETTLED_WITHIN).pollInterval(POLL).until(() -> burst.stream()
                .allMatch(message -> hasStatus(message.requestId(), RequestStatus.COMPLETED)));

        for (final Published message : burst) {
            assertThat(outcomesFor(message))
                    .as("%s under concurrency", message.label())
                    .containsExactly(Outcome.COMPLETED);
            assertThat(row(message.requestId()).orElseThrow().attempts())
                    .as("%s ran exactly once: the claim is what stops two deliveries of one request "
                            + "becoming two runs, and a burst is where that is tested rather than "
                            + "assumed", message.label())
                    .isEqualTo(1);
            assertThat(row(message.requestId()).orElseThrow().claimOwner())
                    .as("%s released its claim when it finished", message.label())
                    .isNull();
        }
    }

    /**
     * The accounting itself, held to the property the batch above rests on.
     *
     * <p>The batch can only show that the five outcomes are reachable. Whether they are
     * <em>exclusive</em> is a property of the rule, and the way to find out is to hand it evidence
     * that contradicts itself — which a healthy system will not produce on demand, and which is
     * exactly the shape of the incident somebody would eventually be asked to explain.
     */
    @Nested
    @DisplayName("the accounting rule")
    class TheAccountingRule {

        @Test
        @DisplayName("a completed request with a copy on the dead-letter queue is accounted for by "
                + "nothing")
        void should_refuse_to_account_for_a_completed_request_that_is_also_parked() {
            assertThat(outcomesOf(new Observed(
                    Optional.of(RequestStatus.COMPLETED), false, true, false)))
                    .as("one register believed sent, and the same one parked as failed — the log "
                            + "alone would call this completed, so the outcome must ask where the "
                            + "message is as well")
                    .isEmpty();
        }

        @Test
        @DisplayName("a parked request still sitting on the queue it arrived on is accounted for by "
                + "nothing")
        void should_refuse_to_account_for_a_message_that_is_parked_and_still_queued() {
            assertThat(outcomesOf(new Observed(
                    Optional.of(RequestStatus.FAILED), false, true, true)))
                    .as("parked, and still due to be delivered again by the queue it is on")
                    .isEmpty();
        }

        @Test
        @DisplayName("a request recorded failed but on no queue at all is accounted for by nothing")
        void should_refuse_to_account_for_a_failed_request_that_was_never_parked() {
            assertThat(outcomesOf(new Observed(
                    Optional.of(RequestStatus.FAILED), false, false, false)))
                    .as("recorded as parked, and nowhere to be found")
                    .isEmpty();
        }

        @Test
        @DisplayName("a claimed run whose message is on no queue at all is accounted for by nothing")
        void should_refuse_to_account_for_a_claimed_run_whose_message_has_vanished() {
            assertThat(outcomesOf(new Observed(
                    Optional.of(RequestStatus.RECEIVED), true, false, false)))
                    .as("the log says a runner is working on it, but the broker holds no copy "
                            + "anywhere — in-flight must require the queue's evidence too")
                    .isEmpty();
        }

        @Test
        @DisplayName("a message with no record and nowhere to be is accounted for by nothing")
        void should_refuse_to_account_for_a_message_that_simply_vanished() {
            assertThat(outcomesOf(new Observed(Optional.empty(), false, false, false)))
                    .as("the silent loss the whole criterion exists to detect")
                    .isEmpty();
        }

        @Test
        @DisplayName("each of the five honest states is accounted for by exactly one outcome")
        void should_account_for_every_consistent_state_exactly_once() {
            assertThat(outcomesOf(new Observed(
                    Optional.of(RequestStatus.COMPLETED), false, false, false)))
                    .containsExactly(Outcome.COMPLETED);
            assertThat(outcomesOf(new Observed(
                    Optional.of(RequestStatus.FAILED), false, true, false)))
                    .containsExactly(Outcome.FAILED_AND_PARKED);
            assertThat(outcomesOf(new Observed(Optional.empty(), false, true, false)))
                    .containsExactly(Outcome.PARKED_AS_INVALID);
            assertThat(outcomesOf(new Observed(
                    Optional.of(RequestStatus.RECEIVED), true, false, true)))
                    .containsExactly(Outcome.IN_FLIGHT);
            assertThat(outcomesOf(new Observed(
                    Optional.of(RequestStatus.RETRYING), false, false, true)))
                    .containsExactly(Outcome.QUEUED_OR_RETRYING);
        }
    }
}
