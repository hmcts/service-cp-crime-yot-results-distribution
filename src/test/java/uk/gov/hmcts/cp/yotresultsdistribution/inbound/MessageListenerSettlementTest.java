package uk.gov.hmcts.cp.yotresultsdistribution.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.springframework.dao.DataAccessResourceFailureException;
import uk.gov.hmcts.cp.yotresultsdistribution.application.DistributionPipeline;
import uk.gov.hmcts.cp.yotresultsdistribution.application.HearingPayloadSource;
import uk.gov.hmcts.cp.yotresultsdistribution.application.IdempotencyGuard;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.config.ProcessingMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletionReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeadLetterReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GuardDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.support.QueueHealthTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.StoreGateTestSupport;

/**
 * The settlement contract, asserted one delivery at a time.
 *
 * <p>Every case makes the same structural assertion beneath its own: <strong>exactly one settlement
 * call</strong>, counted across every settlement method and every overload the SDK offers, on every
 * path this handler controls. Two settlements is an error the broker reports and a lock the service
 * no longer holds; none at all is a delivery left to time out — the silent loss this service exists
 * to cure (spec FR-001, constitution Principle VI).
 *
 * <p>The other half is ordering: {@code complete()} may only follow an outcome the guard recorded
 * durably. A delivery acknowledged before the write is a request the processed log has never heard
 * of and the broker will never deliver again.
 */
class MessageListenerSettlementTest {

    /** Every settlement the SDK offers, so the count cannot be fooled by an overload. */
    private static final Set<String> SETTLEMENT_METHODS =
            Set.of("complete", "abandon", "deadLetter", "defer");

    private static final String MESSAGE_ID = "RESULTS:abcd";
    private static final String LOCK_TOKEN = "5a4f2f1e-0000-0000-0000-00000000000a";

    /** The queue's delivery budget. Every delivery here is well inside it. */
    private static final int MAX_DELIVERY_COUNT = 5;

    /** The pipeline's own bound; nothing here comes near it. */
    private static final Duration RUN_DEADLINE = Duration.ofMinutes(4);

    /** How long the timing case will wait for a thread to get where it is going. */
    private static final Duration PATIENCE = Duration.ofSeconds(5);

    /**
     * The held write's own escape hatch, deliberately far longer than {@link #PATIENCE}: the fixture
     * must never be the first thing to give up, or a failing assertion would be reported as a
     * timeout in the scaffolding instead of as itself.
     */
    private static final Duration FIXTURE_ESCAPE = Duration.ofSeconds(60);

    /**
     * How long "nothing has been settled" must stay true while the write is held. Short, because it
     * is a window in which a wrong implementation settles immediately, not a race being outwaited.
     */
    private static final Duration WHILE_THE_WRITE_IS_HELD = Duration.ofMillis(300);

    private final DistributionPipeline pipeline = mock(DistributionPipeline.class);
    private final ProcessingMetrics metrics = new ProcessingMetrics(new SimpleMeterRegistry());
    private final DistributionCommandParser parser =
            new DistributionCommandParser(JacksonConfig.contractObjectMapper());

    private final YotResultsDistributionMessageListener listener =
            new YotResultsDistributionMessageListener(
                    parser, pipeline, metrics, QueueHealthTestSupport.unwatched(),
                    StoreGateTestSupport.open(), MAX_DELIVERY_COUNT);

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    // --- helpers ---------------------------------------------------------------------------

    private String validBody() {
        return """
                {
                  "source": "RESULTS",
                  "requestId": "%s",
                  "hearingId": "%s",
                  "hearingDay": "2026-08-31",
                  "sharedTime": "2026-08-31T08:00:00Z",
                  "eventType": "Hearing_Resulted"
                }
                """.formatted(requestId, hearingId);
    }

    private ServiceBusReceivedMessageContext deliveryOf(final String body) {
        final ServiceBusReceivedMessage message = mock(ServiceBusReceivedMessage.class);
        when(message.getBody()).thenReturn(BinaryData.fromString(body));
        when(message.getMessageId()).thenReturn(MESSAGE_ID);
        when(message.getLockToken()).thenReturn(LOCK_TOKEN);
        when(message.getDeliveryCount()).thenReturn(1L);

        final ServiceBusReceivedMessageContext context = mock(ServiceBusReceivedMessageContext.class);
        when(context.getMessage()).thenReturn(message);
        return context;
    }

    private ServiceBusReceivedMessageContext validDelivery() {
        return deliveryOf(validBody());
    }

    private void pipelineDecides(final GuardDecision decision) {
        when(pipeline.process(any(DistributionCommand.class), any(DeliveryIdentity.class),
                any(RecordedFlagState.class)))
                .thenReturn(decision);
    }

    /**
     * The same listener over a source of flag readings - for the cases whose subject is the label.
     */
    private YotResultsDistributionMessageListener listenerLabelling(
            final RecordedFlagStateSource flagStates) {
        return new YotResultsDistributionMessageListener(
                parser, pipeline, metrics, QueueHealthTestSupport.unwatched(),
                StoreGateTestSupport.open(), MAX_DELIVERY_COUNT, flagStates);
    }

    /**
     * The same listener over a different store gate — for the cases whose subject is the gate.
     */
    private YotResultsDistributionMessageListener listenerOver(final StoreGate gate) {
        return new YotResultsDistributionMessageListener(
                parser, pipeline, metrics, QueueHealthTestSupport.unwatched(), gate,
                MAX_DELIVERY_COUNT);
    }

    /**
     * Every settlement made on this delivery so far, whichever methods they were.
     */
    private static List<String> settlementsOn(final ServiceBusReceivedMessageContext context) {
        return mockingDetails(context).getInvocations().stream()
                .map(invocation -> invocation.getMethod().getName())
                .filter(SETTLEMENT_METHODS::contains)
                .toList();
    }

    /**
     * The structural assertion every case shares: one settlement, whichever one it was.
     */
    private static void assertSettledExactlyOnce(final ServiceBusReceivedMessageContext context) {
        assertThat(settlementsOn(context)).hasSize(1);
    }

    // --- acknowledgement ----------------------------------------------------------------------

    @Nested
    @DisplayName("a delivery whose work the guard has recorded as done")
    class Acknowledged {

        @Test
        void should_acknowledge_it_exactly_once() {
            final ServiceBusReceivedMessageContext context = validDelivery();
            pipelineDecides(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));

            listener.onMessage(context);

            verify(context).complete();
            assertSettledExactlyOnce(context);
        }

        @Test
        void should_neither_hand_back_nor_park_a_delivery_it_acknowledges() {
            final ServiceBusReceivedMessageContext context = validDelivery();
            pipelineDecides(new GuardDecision.Complete(ReasonCode.ALREADY_COMPLETED));

            listener.onMessage(context);

            verify(context, never()).abandon();
            verify(context, never()).deadLetter(any(DeadLetterOptions.class));
        }
    }

    // --- when the acknowledgement happens ---------------------------------------------------------

    /**
     * The half of spec FR-001 that an invocation-order check cannot reach.
     *
     * <p>Verifying that {@code complete()} was recorded after the pipeline call proves only that the
     * two calls were <em>entered</em> in that order — Mockito records an invocation on entry — and it
     * says nothing at all about the guard, because a mocked pipeline never reaches one. An
     * implementation that acknowledged the delivery while the outcome write was still in flight would
     * satisfy it, and that implementation loses requests: the broker deletes the message, the write
     * then fails, and the processed log has never heard of a request that will never be delivered
     * again.
     *
     * <p>So this case uses the real pipeline over a guard whose durable write blocks, and asserts on
     * the delivery <em>while the write has not returned</em>: no settlement of any kind may have
     * happened yet. Only when the write is released may the acknowledgement appear.
     *
     * <p>The completion it is held open on is {@code no-defendants} — the commonest way a court
     * register run ends well, and the one a placeholder payload produces (defect fix C6/C33).
     */
    @Nested
    @DisplayName("the moment a delivery is acknowledged")
    class AcknowledgementTiming {

        private final IdempotencyGuard guard = mock(IdempotencyGuard.class);
        private final HearingPayloadSource payloadSource = mock(HearingPayloadSource.class);

        /** Raised by the fixture the instant the outcome write begins. */
        private final CountDownLatch writeInFlight = new CountDownLatch(1);

        /** Lowered by the test when it is ready for the write to return. */
        private final CountDownLatch releaseTheWrite = new CountDownLatch(1);

        /**
         * Unconditionally, so a failing assertion is reported as itself rather than as the held
         * write timing out afterwards — and so no blocked thread outlives the case.
         */
        @AfterEach
        void releaseAnyHeldWrite() {
            releaseTheWrite.countDown();
        }

        @Test
        void should_not_acknowledge_until_the_outcome_write_has_returned() throws InterruptedException {
            final RunClaim claim = new RunClaim(
                    "RESULTS", requestId, "runner-1/" + LOCK_TOKEN, UUID.randomUUID(), MESSAGE_ID);
            when(guard.admit(any(DistributionCommand.class), any(DeliveryIdentity.class)))
                    .thenReturn(new GuardDecision.Run(claim));
            when(payloadSource.fetch(any(DistributionCommand.class)))
                    .thenReturn(JacksonConfig.contractObjectMapper().readTree("{}"));
            when(guard.recordCompletion(claim, CompletionReason.NO_DEFENDANTS))
                    .thenAnswer(aDurableWriteThatTakesItsTime());

            final ServiceBusReceivedMessageContext context = validDelivery();
            final Thread delivery = new Thread(() -> listenerOverTheRealPipeline().onMessage(context),
                    "one-delivery");
            delivery.start();

            assertThat(writeInFlight.await(PATIENCE.toSeconds(), TimeUnit.SECONDS))
                    .as("the outcome write should have started")
                    .isTrue();
            await().during(WHILE_THE_WRITE_IS_HELD).atMost(PATIENCE)
                    .until(() -> settlementsOn(context).isEmpty());

            releaseTheWrite.countDown();
            delivery.join(PATIENCE.toMillis());

            verify(context).complete();
            assertSettledExactlyOnce(context);
        }

        private YotResultsDistributionMessageListener listenerOverTheRealPipeline() {
            return new YotResultsDistributionMessageListener(
                    parser,
                    new DistributionPipeline(
                            guard, payloadSource, metrics, Clock.systemUTC(), RUN_DEADLINE),
                    metrics,
                    QueueHealthTestSupport.unwatched(),
                    StoreGateTestSupport.open(),
                    MAX_DELIVERY_COUNT);
        }

        /** A write that has been issued and has not yet come back — a slow store, in one fixture. */
        private Answer<GuardDecision> aDurableWriteThatTakesItsTime() {
            return invocation -> {
                writeInFlight.countDown();
                if (!releaseTheWrite.await(FIXTURE_ESCAPE.toSeconds(), TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the outcome write was never released");
                }
                return new GuardDecision.Complete(ReasonCode.RUN_COMPLETED);
            };
        }
    }

    // --- return for retry ---------------------------------------------------------------------

    @Nested
    @DisplayName("a delivery the guard hands back")
    class HandedBack {

        @Test
        void should_abandon_it_exactly_once_and_never_acknowledge_it() {
            final ServiceBusReceivedMessageContext context = validDelivery();
            pipelineDecides(new GuardDecision.Abandon(ReasonCode.CLAIM_NOT_ACQUIRED));

            listener.onMessage(context);

            verify(context).abandon();
            verify(context, never()).complete();
            assertSettledExactlyOnce(context);
        }
    }

    // --- parking --------------------------------------------------------------------------------

    @Nested
    @DisplayName("a delivery the guard parks")
    class Parked {

        @Test
        void should_dead_letter_it_exactly_once_with_a_bounded_reason_and_description() {
            final ServiceBusReceivedMessageContext context = validDelivery();
            pipelineDecides(new GuardDecision.DeadLetter(
                    DeadLetterReason.COLLISION, ReasonCode.IDEMPOTENCY_COLLISION));

            listener.onMessage(context);

            final ArgumentCaptor<DeadLetterOptions> parked =
                    ArgumentCaptor.forClass(DeadLetterOptions.class);
            verify(context).deadLetter(parked.capture());
            assertThat(parked.getValue().getDeadLetterReason())
                    .isEqualTo(DeadLetterReason.COLLISION.label());
            assertThat(parked.getValue().getDeadLetterErrorDescription())
                    .isEqualTo(ReasonCode.IDEMPOTENCY_COLLISION.code());
            assertSettledExactlyOnce(context);
        }

        @Test
        void should_never_acknowledge_a_delivery_it_parks() {
            final ServiceBusReceivedMessageContext context = validDelivery();
            pipelineDecides(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.DELIVERY_LIMIT_EXHAUSTED));

            listener.onMessage(context);

            verify(context, never()).complete();
        }
    }

    // --- the paths that never reach the guard -----------------------------------------------------

    @Nested
    @DisplayName("a body that does not satisfy the contract")
    class ContractInvalid {

        @Test
        void should_settle_the_delivery_exactly_once_without_running_the_pipeline() {
            final ServiceBusReceivedMessageContext context = deliveryOf("{\"source\":\"RESULTS\"}");

            listener.onMessage(context);

            verifyNoInteractions(pipeline);
            assertSettledExactlyOnce(context);
        }

        @Test
        void should_never_acknowledge_a_body_it_could_not_read() {
            final ServiceBusReceivedMessageContext context = deliveryOf("not json at all");

            listener.onMessage(context);

            verify(context, never()).complete();
            assertSettledExactlyOnce(context);
        }
    }

    @Nested
    @DisplayName("a run that fails in a way nothing anticipated")
    class UnexpectedFailure {

        @Test
        void should_hand_the_delivery_back_rather_than_acknowledge_work_that_did_not_happen() {
            final ServiceBusReceivedMessageContext context = validDelivery();
            when(pipeline.process(any(DistributionCommand.class), any(DeliveryIdentity.class),
                any(RecordedFlagState.class)))
                    .thenThrow(new IllegalStateException("the store went away mid-run"));

            listener.onMessage(context);

            verify(context).abandon();
            verify(context, never()).complete();
            assertSettledExactlyOnce(context);
        }
    }

    /**
     * A store that answered the precondition and then went away underneath the run.
     *
     * <p>The two obligations are separate and both are owed: the delivery back, because a store
     * outage is not the message's fault, and intake stopped, because otherwise the next delivery
     * meets the same dead store, and the next, until the broker's budget is spent and recoverable
     * work is parked under a reason that describes the retry count rather than the fault (spec
     * FR-015).
     *
     * <p>It is told apart by the failure's own type and never by where it was thrown: this service's
     * store is reached from more than one stage of a run and the answer is the same wherever it went
     * away. That type is a domain one, because the listener is an adapter and the core it calls may
     * not import a JDBC type to raise (constitution Principle V) - the persistence layer translates
     * the outage classes, and this is the signal both of them read.
     */
    @Nested
    @DisplayName("a store that went away underneath the run")
    class StoreWentAway {

        @Test
        @DisplayName("hands the delivery back and asks for intake to stop")
        void should_hand_the_delivery_back_and_suspend_intake() {
            final ServiceBusReceivedMessageContext context = validDelivery();
            final StoreGateTestSupport.Recording gate = StoreGateTestSupport.open();
            when(pipeline.process(any(DistributionCommand.class), any(DeliveryIdentity.class),
                any(RecordedFlagState.class)))
                    .thenThrow(new StoreUnavailableException(
                            "the processed log cannot be reached",
                            new DataAccessResourceFailureException("connection refused")));

            listenerOver(gate).onMessage(context);

            assertThat(gate.suspensionsRequested())
                    .as("a delivery handed back into a running consumer meets the same dead store, "
                            + "and so does every message behind it")
                    .isEqualTo(1);
            verify(context).abandon();
            verify(context, never()).complete();
            verify(context, never()).deadLetter(any(DeadLetterOptions.class));
            assertSettledExactlyOnce(context);
        }
    }

    // --- the paths that fail before anything is examined ---------------------------------------------

    /**
     * The precondition itself failing — the one route out of {@code onMessage} with no decision.
     *
     * <p>Store availability is asked and intake is asked to stop <em>outside</em> the boundary that
     * turns every failure into a decision, so until the boundary is widened both calls escape with
     * the delivery unsettled. That is the single worst outcome this handler has: the message stays
     * locked until its lease runs out and is then delivered again, four more times, into the same
     * failure — and is finally parked by the broker's own rule with nothing recorded about why.
     *
     * <p>The two calls fail for genuinely different reasons and both are ordinary. The availability
     * question is answered by a probe that turns a data-access failure into {@code false}; anything
     * outside that hierarchy — a pool closed underneath a callback thread, a driver raising its own
     * type — arrives as a failure instead. The suspension request is a submission to an executor,
     * and the moment it is most likely to be refused is the moment it is most needed: a store outage
     * during a shutdown.
     *
     * <p>What is asserted is only what the handler owes: exactly one settlement, and a hand-back
     * rather than an acknowledgement, because nothing was examined and nothing was recorded.
     */
    @Nested
    @DisplayName("a store gate that fails instead of answering")
    class GateFails {

        @Test
        void should_hand_the_delivery_back_exactly_once_when_the_availability_question_throws() {
            final ServiceBusReceivedMessageContext context = validDelivery();

            listenerOver(StoreGateTestSupport.unanswerable()).onMessage(context);

            verify(context).abandon();
            verifyNoInteractions(pipeline);
            assertSettledExactlyOnce(context);
        }

        @Test
        void should_hand_the_delivery_back_exactly_once_when_the_suspension_request_throws() {
            final ServiceBusReceivedMessageContext context = validDelivery();

            listenerOver(StoreGateTestSupport.closedAndUnstoppable()).onMessage(context);

            verify(context).abandon();
            assertSettledExactlyOnce(context);
        }

        @Test
        void should_never_acknowledge_a_delivery_whose_gate_it_could_not_consult() {
            final ServiceBusReceivedMessageContext context = validDelivery();

            listenerOver(StoreGateTestSupport.unanswerable()).onMessage(context);

            verify(context, never()).complete();
            verify(context, never()).deadLetter(any(DeadLetterOptions.class));
        }
    }

    /**
     * The delivery the transport cannot even hand over.
     *
     * <p>{@code getMessage()} is a call into the SDK like any other, and it is made before the
     * boundary. A delivery whose message cannot be produced is still a delivery this handler was
     * given and still holds a lock, so it is owed the same single settlement as one that merely
     * failed to parse.
     */
    @Nested
    @DisplayName("a delivery whose message the transport cannot produce")
    class MessageUnavailable {

        @Test
        void should_hand_the_delivery_back_exactly_once() {
            final ServiceBusReceivedMessageContext context =
                    mock(ServiceBusReceivedMessageContext.class);
            when(context.getMessage())
                    .thenThrow(new IllegalStateException("the delivery has already been disposed"));

            listener.onMessage(context);

            verify(context).abandon();
            assertSettledExactlyOnce(context);
        }
    }

    // --- what the guard is told about the delivery -------------------------------------------------

    @Nested
    @DisplayName("the identity the delivery is processed under")
    class Identity {

        @Test
        void should_carry_the_broker_message_id_and_a_runner_identity_naming_this_delivery() {
            final ServiceBusReceivedMessageContext context = validDelivery();
            pipelineDecides(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));

            listener.onMessage(context);

            final ArgumentCaptor<DeliveryIdentity> identity =
                    ArgumentCaptor.forClass(DeliveryIdentity.class);
            verify(pipeline).process(any(DistributionCommand.class), identity.capture(),
                    any(RecordedFlagState.class));
            assertThat(identity.getValue().messageId()).isEqualTo(MESSAGE_ID);
            assertThat(identity.getValue().claimOwner()).contains(LOCK_TOKEN);
        }

        @Test
        void should_carry_the_command_the_body_parsed_to() {
            final ServiceBusReceivedMessageContext context = validDelivery();
            pipelineDecides(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));

            listener.onMessage(context);

            final ArgumentCaptor<DistributionCommand> parsed =
                    ArgumentCaptor.forClass(DistributionCommand.class);
            verify(pipeline).process(parsed.capture(), any(DeliveryIdentity.class),
                    any(RecordedFlagState.class));
            assertThat(parsed.getValue().requestId()).isEqualTo(requestId);
            assertThat(parsed.getValue().hearingId()).isEqualTo(hearingId);
        }

        @Test
        void should_carry_the_flag_state_the_intake_side_last_read() {
            // Research §12: the label is attached where the delivery begins and handed down with
            // the command, so the register recorded from it says which implementation was meant to
            // be generating when it arrived. The source answers from its last reading and is never
            // waited on, which RecordedFlagStateTest holds it to.
            final ServiceBusReceivedMessageContext context = validDelivery();
            final RecordedFlagStateSource flagStates = mock(RecordedFlagStateSource.class);
            when(flagStates.current()).thenReturn(RecordedFlagState.OFF);
            pipelineDecides(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));

            listenerLabelling(flagStates).onMessage(context);

            final ArgumentCaptor<RecordedFlagState> attached =
                    ArgumentCaptor.forClass(RecordedFlagState.class);
            verify(pipeline).process(any(DistributionCommand.class), any(DeliveryIdentity.class),
                    attached.capture());
            assertThat(attached.getValue()).isEqualTo(RecordedFlagState.OFF);
        }

        @Test
        void should_label_a_delivery_unknown_where_this_pod_reads_no_flag() {
            // The record-only shape: no nightly job, so no flag reader, so nothing has been read
            // about the one lever. UNKNOWN keeps the rows out of automatic batching, which is the
            // safe direction - a row wrongly labelled ON is a second copy of a child's register.
            final ServiceBusReceivedMessageContext context = validDelivery();
            pipelineDecides(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));

            listener.onMessage(context);

            final ArgumentCaptor<RecordedFlagState> attached =
                    ArgumentCaptor.forClass(RecordedFlagState.class);
            verify(pipeline).process(any(DistributionCommand.class), any(DeliveryIdentity.class),
                    attached.capture());
            assertThat(attached.getValue()).isEqualTo(RecordedFlagState.UNKNOWN);
        }
    }
}
