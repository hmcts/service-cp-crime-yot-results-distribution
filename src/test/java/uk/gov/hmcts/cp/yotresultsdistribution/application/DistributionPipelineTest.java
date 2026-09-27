package uk.gov.hmcts.cp.yotresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.progression.OutboundContractValidator;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.progression.ProgressionCommandGateway;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.progression.ProgressionRegisterSubmissionClient;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.config.OutputMode;
import uk.gov.hmcts.cp.yotresultsdistribution.config.ProcessingMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletionReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterAddress;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterCaseOrApplication;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterHearingVenue;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeadLetterReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GuardDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NoRegisterReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.PayloadUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ProcessedOutputClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReferenceDataUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotRecordedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.SubmissionFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationAnomaly;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.ProcessedOutputRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.pipeline.Dates;
import uk.gov.hmcts.cp.yotresultsdistribution.support.AdjustableClock;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;

/**
 * One request, from the payload fetch to the recorded outcome — the orchestration the legacy has no
 * test for at all.
 *
 * <p>{@code CourtRegisterOrchestrator/index.js} has no test file. Seventy-seven lines, five
 * activities, four silent guards and a catch-all that reports failure from a completed
 * orchestration, and nothing in the repository executes any of it. Every case in this file is
 * therefore written from the source rather than twinned, and four catalogued defects live in what it
 * asserts:
 *
 * <ul>
 *   <li><strong>C2</strong> — the catch-all at {@code :70-76} returns {@code {Success:false}} from an
 *       orchestration that <em>completed</em>, and reads {@code context.df.getInput()} inside the
 *       catch, which can itself throw. Nothing is recorded either way.</li>
 *   <li><strong>C6</strong> — a hearing that gathers no defendants flows on as an empty register.</li>
 *   <li><strong>C32</strong> — {@code if (hearingResultedObj)} at {@code :20} is false when the cache
 *       missed and the query fallback returned nothing, and the run stops there, silently, reporting
 *       success.</li>
 *   <li><strong>C33</strong> — those four guards and the bare {@code null} from
 *       {@code OutboundCourtRegister} all end in one undifferentiated {@code Success: true}, and two
 *       of them are this flow's commonest legitimate outcomes.</li>
 * </ul>
 *
 * <p>The suite drives the pipeline over test doubles for its four ports, because what is asserted
 * here is the order the stages run in, what each is handed, and what is recorded when one of them
 * refuses. The stages' own behaviour is asserted in their own suites.
 *
 * <p><strong>002 adds a fifth port and a second last stage.</strong> Under
 * {@code yotresultsdistribution.output=record} the assembled register is written into this service's own
 * store rather than POSTed to progression, and the run completes {@code recorded} instead of
 * {@code submitted}. Both stages are wired and the mode chooses between them once, when the
 * pipeline is assembled, so the two arms can be driven side by side here; {@link Recording} holds
 * the new one, and every case above it still describes the {@code progression-post} arm.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> rows C2,
 *     C6, C7, C32 and C33
 */
@DisplayName("DistributionPipeline")
class DistributionPipelineTest {

    /** Returned when a meter is absent, so a missing count fails as an assertion. */
    private static final double ABSENT = -1;

    /** Long enough that no case in this file reaches its processing deadline. */
    private static final Duration RUN_DEADLINE = Duration.ofMinutes(5);

    /**
     * The OU code the transformation addressed the register by.
     *
     * <p>It travels beside the document because the frozen contract has no field for it and the
     * output row is searched by it; the pipeline carries it to the submission port and reads none of
     * it.
     */
    private static final String OU_CODE = "B01LY00";

    /**
     * The register-document contract, for the cases whose subject is not the contract.
     *
     * <p>A permissive double rather than the real validator, because the registers those cases hand
     * over are stand-ins whose fields nothing reads: holding them to a frozen schema would make
     * every case about the recording stage's ordering fail for a reason it is not about. The
     * contract itself is asserted with the real validator in {@link TheRecordedContract}.
     */
    private static final RegisterDocumentValidator ANY_DOCUMENT = document -> {
    };

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProcessingMetrics metrics = new ProcessingMetrics(registry);

    private final IdempotencyGuard guard = mock(IdempotencyGuard.class);
    private final HearingPayloadSource payloadSource = mock(HearingPayloadSource.class);
    private final GroupProceedingsPolicy groupProceedings = mock(GroupProceedingsPolicy.class);
    private final NowSubscriptionsSource subscriptionsSource = mock(NowSubscriptionsSource.class);
    private final RegisterTransformer transformer = mock(RegisterTransformer.class);
    private final RegisterStore registerStore = mock(RegisterStore.class);
    private final RegisterSubmissionClient submissionClient = mock(RegisterSubmissionClient.class);

    private final DistributionCommand command = new DistributionCommand(
            "RESULTS",
            UUID.fromString("9f1b8e2a-5c34-4a7d-9b1e-2f6a0d3c5e71"),
            UUID.fromString("1828f356-f746-4f2d-932b-79ef2df95c80"),
            LocalDate.parse("2020-06-01"),
            Instant.parse("2020-06-01T10:00:00Z"),
            "Hearing_Resulted",
            java.util.Optional.of(UUID.fromString("6e2f0a1c-9d4b-4f38-8a52-1c7b3e5d9f04")));

    private final RunClaim claim = new RunClaim(
            command.source(), command.requestId(), "runner-1", UUID.randomUUID(), "msg-1");

    private final CourtRegisterDocument document = new CourtRegisterDocument(
            "2020-06-01T10:00:00Z",
            "2020-01-20T00:00:00Z",
            command.hearingId().toString(),
            "853b1ff8-fc2a-44d1-a621-0cd16419f54a",
            "court-register_2020-06-01_B01LY00_" + command.hearingId() + ".pdf",
            null,
            null,
            null,
            null);

    private final JsonNode payload = mapper.readTree(
            "{\"hearing\":{\"id\":\"1828f356-f746-4f2d-932b-79ef2df95c80\"},"
                    + "\"sharedTime\":\"2020-06-01T10:00:00Z\"}");

    /** Reference data's answer for the register's day: an answer, carrying nobody. */
    private final JsonNode subscriptions = mapper.readTree("{\"nowSubscriptions\":[]}");

    @BeforeEach
    void theOrdinaryRun() {
        when(guard.admit(any(DistributionCommand.class), any(DeliveryIdentity.class)))
                .thenReturn(new GuardDecision.Run(claim));
        when(guard.recordCompletion(any(RunClaim.class), any(CompletionReason.class)))
                .thenReturn(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));
        when(guard.recordTransientFailure(any(RunClaim.class), any(ReasonCode.class)))
                .thenAnswer(call -> new GuardDecision.Abandon(call.getArgument(1)));
        when(guard.recordNonTransientFailure(any(RunClaim.class), any(ReasonCode.class)))
                .thenAnswer(call -> new GuardDecision.DeadLetter(
                        DeadLetterReason.NON_TRANSIENT, call.getArgument(1)));
        when(guard.recordExhaustion(any(RunClaim.class), any(ReasonCode.class)))
                .thenAnswer(call -> new GuardDecision.DeadLetter(
                        DeadLetterReason.EXHAUSTED, call.getArgument(1)));

        when(payloadSource.fetch(any(DistributionCommand.class))).thenReturn(payload);
        when(groupProceedings.suppresses(any(DistributionCommand.class), any(JsonNode.class)))
                .thenReturn(false);
        when(subscriptionsSource.subscriptionsOn(any(LocalDate.class), any(CallerIdentity.class)))
                .thenReturn(subscriptions);
        when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                    any(JsonNode.class), any()))
                .thenReturn(new TransformationResult.Register(document, OU_CODE));
        when(submissionClient.submit(any(RegisterSubmission.class)))
                .thenReturn(new SubmissionReceipt(202, true));
    }

    /** N1 and N6: the order the stages run in, and what each of them is handed. */
    @Nested
    @DisplayName("the stage sequence")
    class StageSequence {

        @Test
        @DisplayName("fetches, decides, reads reference data, transforms, submits, records — in "
                + "that order")
        void runs_the_stages_in_order() {
            final GuardDecision decision = run();

            final InOrder stages = inOrder(payloadSource, groupProceedings, subscriptionsSource,
                    transformer, submissionClient, guard);
            stages.verify(payloadSource).fetch(command);
            stages.verify(groupProceedings).suppresses(eqCommand(), any(JsonNode.class));
            stages.verify(subscriptionsSource).subscriptionsOn(any(LocalDate.class),
                    any(CallerIdentity.class));
            stages.verify(transformer).transform(eqCommand(), any(JsonNode.class),
                    any(JsonNode.class), any());
            stages.verify(submissionClient).submit(any(RegisterSubmission.class));
            stages.verify(guard).recordCompletion(claim, CompletionReason.SUBMITTED);

            assertThat(decision).isInstanceOf(GuardDecision.Complete.class);
        }

        @Test
        @DisplayName("hands the transformation the payload the fetch returned, unaltered")
        void hands_the_transformation_the_payload_the_fetch_returned() {
            // The legacy hands the same mutable hearing object to `SetCourtRegister` and then to
            // `OutboundCourtRegister`, and is saved from the consequences only by the Durable
            // Functions serialisation boundary between activities. Java passes references, so the
            // pipeline has to hand on what it fetched and the stages have to derive rather than edit.
            final JsonNode pristine = payload.deepCopy();
            final ArgumentCaptor<JsonNode> handedOn = ArgumentCaptor.forClass(JsonNode.class);

            run();

            verify(transformer).transform(eqCommand(), handedOn.capture(), any(JsonNode.class), any());
            assertThat(handedOn.getValue()).isEqualTo(pristine);
            assertThat(payload)
                    .as("the fetched tree belongs to the producer, not to this service")
                    .isEqualTo(pristine);
        }

        @Test
        @DisplayName("asks the group-proceedings policy about the hearing inside the envelope")
        void asks_the_policy_about_the_hearing_inside_the_envelope() {
            // The claim-check payload is `{hearing, sharedTime}`; the flag is on the hearing
            // (`CourtRegisterOrchestrator/index.js:21`). Handing the envelope instead would read an
            // absent field and never suppress anything.
            final ArgumentCaptor<JsonNode> asked = ArgumentCaptor.forClass(JsonNode.class);

            run();

            verify(groupProceedings).suppresses(eqCommand(), asked.capture());
            assertThat(asked.getValue()).isEqualTo(payload.get("hearing"));
        }

        @Test
        @DisplayName("submits the document the transformation produced, as the request's own user")
        void submits_the_document_the_transformation_produced() {
            final ArgumentCaptor<RegisterSubmission> submitted =
                    ArgumentCaptor.forClass(RegisterSubmission.class);

            run();

            verify(submissionClient).submit(submitted.capture());
            assertThat(submitted.getValue().document()).isEqualTo(document);
            assertThat(submitted.getValue().caller()).isEqualTo(CallerIdentity.of(command));
            assertThat(submitted.getValue().claim())
                    .as("every processed_output write the adapter makes is fenced on this claim")
                    .isEqualTo(claim);
        }

        @Test
        @DisplayName("submits exactly once per document")
        void submits_exactly_once_per_document() {
            // Progression's `add-court-register` appends an event and a row per POST, so a second
            // submission inside one run is a second register for the hearing.
            run();

            verify(submissionClient, times(1)).submit(any(RegisterSubmission.class));
        }

        @Test
        @DisplayName("records the run as submitted, and counts it under that reason")
        void records_the_run_as_submitted() {
            run();

            verify(guard).recordCompletion(claim, CompletionReason.SUBMITTED);
            assertThat(completions("submitted")).isEqualTo(1);
        }
    }

    /**
     * The reference-data read, which belongs here and not behind the transformation port.
     *
     * <p>These cases were {@code SubscriptionMatcherTest}'s until the transformation was made pure.
     * The matcher reached {@link NowSubscriptionsSource} itself, which put I/O inside the chain the
     * constitution requires to be pure — "no I/O, no clock, no randomness" (Principle V) — and made
     * the stage that decides who a register reaches untestable without a port double. The read moves
     * up here, between two pure stages; the assertions are the same ones, made at the seam that now
     * owns them.
     *
     * <p>What they pin is the whole of the read's contract: the day it is made for is the day the
     * results were shared (defect fix C12, consumed here), the identity it is made as is the request's
     * own, its answer reaches the transformation unaltered, and reference data declining to answer is
     * a retry rather than a register addressed to nobody.
     */
    @Nested
    @DisplayName("the reference-data read")
    class ReferenceDataRead {

        @Test
        @DisplayName("asks for the subscriptions in force on the day the results were shared")
        void asks_for_the_day_the_results_were_shared() {
            // The `on=` day comes from the register date, which is the share instant rather than a
            // London wall clock relabelled `Z` — so an evening share reads the set in force on the
            // day it was shared and not the next day's (C12).
            final ArgumentCaptor<LocalDate> day = ArgumentCaptor.forClass(LocalDate.class);

            run();

            verify(subscriptionsSource).subscriptionsOn(day.capture(), any(CallerIdentity.class));
            assertThat(day.getValue()).isEqualTo(LocalDate.parse("2020-06-01"));
        }

        @Test
        @DisplayName("makes the read as the user who shared the results")
        void makes_the_read_as_the_user_who_shared_the_results() {
            final ArgumentCaptor<CallerIdentity> caller =
                    ArgumentCaptor.forClass(CallerIdentity.class);

            run();

            verify(subscriptionsSource).subscriptionsOn(any(LocalDate.class), caller.capture());
            assertThat(caller.getValue()).isEqualTo(CallerIdentity.of(command));
        }

        @Test
        @DisplayName("hands the transformation the answer reference data gave, unaltered")
        void hands_the_transformation_the_answer_reference_data_gave() {
            final ArgumentCaptor<JsonNode> handedOn = ArgumentCaptor.forClass(JsonNode.class);

            run();

            verify(transformer).transform(eqCommand(), any(JsonNode.class), handedOn.capture(), any());
            assertThat(handedOn.getValue()).isEqualTo(subscriptions);
        }

        @Test
        @DisplayName("reads reference data once per run, whatever the register turns out to be")
        void reads_reference_data_once_per_run() {
            run();

            verify(subscriptionsSource, times(1)).subscriptionsOn(any(LocalDate.class),
                    any(CallerIdentity.class));
        }

        @Test
        @DisplayName("retries a register whose recipients reference data would not name")
        void retries_a_register_whose_recipients_reference_data_would_not_name() {
            // The half of the legacy's single case that has to stop being a completion. A register
            // whose recipients are unknown is retried, not published to nobody and recorded as this
            // flow's commonest legitimate outcome.
            when(subscriptionsSource.subscriptionsOn(any(LocalDate.class),
                    any(CallerIdentity.class)))
                    .thenThrow(new ReferenceDataUnavailableException("subscriptions-read-failed"));

            final GuardDecision decision = run();

            assertThat(decision).isEqualTo(
                    new GuardDecision.Abandon(ReasonCode.REFERENCE_DATA_UNAVAILABLE));
            verify(transformer, never()).transform(any(DistributionCommand.class),
                    any(JsonNode.class), any(JsonNode.class), any());
            verify(guard, never()).recordCompletion(any(RunClaim.class),
                    any(CompletionReason.class));
        }

        @Test
        @DisplayName("asks reference data nothing about a hearing the group-proceedings skip took")
        void asks_reference_data_nothing_about_a_suppressed_hearing() {
            when(payloadSource.fetch(any(DistributionCommand.class)))
                    .thenReturn(payloadFlagged("true"));

            pipelineOverTheRealPolicy().process(command, delivery());

            verify(subscriptionsSource, never()).subscriptionsOn(any(LocalDate.class),
                    any(CallerIdentity.class));
        }
    }

    /**
     * The run's own time budget, spent across every stage rather than checked once.
     *
     * <p>A claim is a lease, and the processing deadline is the promise a runner makes to stop
     * before that lease can be reclaimed. Checking it only after the payload fetch keeps the promise
     * for one stage and breaks it for the three that follow: a reference-data read, a transformation
     * and a POST can each take minutes, and a run that starts its POST after the deadline is a run
     * whose claim another delivery may already hold — and progression's {@code add-court-register}
     * <em>appends</em> a register rather than replacing one, so the second runner's POST is a second
     * register for the hearing, not an overwrite of the first.
     *
     * <p>So the budget is remaining time, read before every send and before every outcome write. A
     * run that has spent it stops, records the overrun as TRANSIENT, and hands the delivery back —
     * the redelivery has a whole fresh budget and nothing has been sent twice. The one write that is
     * never withheld is the completion of a register that <em>was</em> sent: the POST happened, and
     * a run that failed to record it would send it again.
     */
    @Nested
    @DisplayName("the run's time budget")
    class TimeBudget {

        /** Past the five-minute deadline every case in this file is built around. */
        private static final Duration OVER_BUDGET = Duration.ofMinutes(6);

        @Test
        @DisplayName("asks reference data nothing once the payload fetch has spent the budget")
        void asks_reference_data_nothing_once_the_fetch_has_spent_the_budget() {
            final AdjustableClock clock = movingClock();
            when(payloadSource.fetch(any(DistributionCommand.class))).thenAnswer(call -> {
                clock.advance(OVER_BUDGET);
                return payload;
            });

            final GuardDecision decision = pipelineOn(clock).process(command, delivery());

            assertThat(decision).isEqualTo(
                    new GuardDecision.Abandon(ReasonCode.PROCESSING_DEADLINE_EXCEEDED));
            verify(subscriptionsSource, never()).subscriptionsOn(any(LocalDate.class),
                    any(CallerIdentity.class));
        }

        @Test
        @DisplayName("transforms nothing once the reference-data read has spent the budget")
        void transforms_nothing_once_the_reference_data_read_has_spent_the_budget() {
            final AdjustableClock clock = movingClock();
            when(subscriptionsSource.subscriptionsOn(any(LocalDate.class),
                    any(CallerIdentity.class))).thenAnswer(call -> {
                        clock.advance(OVER_BUDGET);
                        return subscriptions;
                    });

            final GuardDecision decision = pipelineOn(clock).process(command, delivery());

            assertThat(decision).isEqualTo(
                    new GuardDecision.Abandon(ReasonCode.PROCESSING_DEADLINE_EXCEEDED));
            verify(transformer, never()).transform(any(DistributionCommand.class),
                    any(JsonNode.class), any(JsonNode.class), any());
        }

        @Test
        @DisplayName("does not start a submission after the safe deadline has passed")
        void does_not_start_a_submission_after_the_deadline() {
            // The send is the stage the budget exists for: progression appends a register per POST,
            // so a run that starts one while a second runner may already hold its claim is how one
            // hearing acquires two registers.
            final AdjustableClock clock = movingClock();
            when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                    any(JsonNode.class), any())).thenAnswer(call -> {
                        clock.advance(OVER_BUDGET);
                        return new TransformationResult.Register(document, OU_CODE);
                    });

            final GuardDecision decision = pipelineOn(clock).process(command, delivery());

            assertThat(decision).isEqualTo(
                    new GuardDecision.Abandon(ReasonCode.PROCESSING_DEADLINE_EXCEEDED));
            verify(submissionClient, never()).submit(any(RegisterSubmission.class));
            verify(guard, never()).recordCompletion(any(RunClaim.class),
                    any(CompletionReason.class));
        }

        @Test
        @DisplayName("does not write a completion after the safe deadline has passed")
        void does_not_write_a_completion_after_the_deadline() {
            final AdjustableClock clock = movingClock();
            when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                    any(JsonNode.class), any())).thenAnswer(call -> {
                        clock.advance(OVER_BUDGET);
                        return new TransformationResult.NoRegister(
                                NoRegisterReason.NO_SUBSCRIPTIONS);
                    });

            final GuardDecision decision = pipelineOn(clock).process(command, delivery());

            assertThat(decision).isEqualTo(
                    new GuardDecision.Abandon(ReasonCode.PROCESSING_DEADLINE_EXCEEDED));
            verify(guard, never()).recordCompletion(any(RunClaim.class),
                    any(CompletionReason.class));
        }

        @Test
        @DisplayName("records the register it did send, even where the send ran past the deadline")
        void records_a_register_whose_send_ran_past_the_deadline() {
            // The one write the budget must never withhold. The POST has happened; a run that
            // declined to record it would be redelivered and would send the register a second time.
            final AdjustableClock clock = movingClock();
            when(submissionClient.submit(any(RegisterSubmission.class))).thenAnswer(call -> {
                clock.advance(OVER_BUDGET);
                return new SubmissionReceipt(202, true);
            });

            final GuardDecision decision = pipelineOn(clock).process(command, delivery());

            assertThat(decision).isInstanceOf(GuardDecision.Complete.class);
            verify(guard).recordCompletion(claim, CompletionReason.SUBMITTED);
        }

        @Test
        @DisplayName("parks an overrun on the last permitted delivery rather than losing it")
        void parks_an_overrun_on_the_last_permitted_delivery() {
            final AdjustableClock clock = movingClock();
            when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                    any(JsonNode.class), any())).thenAnswer(call -> {
                        clock.advance(OVER_BUDGET);
                        return new TransformationResult.Register(document, OU_CODE);
                    });

            final GuardDecision decision = pipelineOn(clock).process(command, lastDelivery());

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.PROCESSING_DEADLINE_EXCEEDED));
        }
    }

    /**
     * Defect C33. Four separate legacy guards, one undifferentiated success. Two of these four are
     * the commonest results this service has, so telling them apart is what makes a pipeline that
     * has quietly stopped working distinguishable from a quiet week.
     */
    @Nested
    @DisplayName("the four no-op outcomes (C33)")
    class NoOpOutcomes {

        @ParameterizedTest
        @EnumSource(NoRegisterReason.class)
        @DisplayName("no op outcomes are distinguishable")
        void no_op_outcomes_are_distinguishable(final NoRegisterReason reason) {
            when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                    any(JsonNode.class), any()))
                    .thenReturn(new TransformationResult.NoRegister(reason));

            final GuardDecision decision = run();

            assertThat(decision).isInstanceOf(GuardDecision.Complete.class);
            verify(guard).recordCompletion(claim, reason.completion());
            assertThat(completions(reason.completion().value())).isEqualTo(1);
            assertThat(completions("submitted")).isEqualTo(ABSENT);
        }

        @ParameterizedTest
        @EnumSource(NoRegisterReason.class)
        @DisplayName("sends nothing at all when there is nothing to send")
        void sends_nothing_when_there_is_nothing_to_send(final NoRegisterReason reason) {
            when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                    any(JsonNode.class), any()))
                    .thenReturn(new TransformationResult.NoRegister(reason));

            run();

            verify(submissionClient, never()).submit(any(RegisterSubmission.class));
        }

        @Test
        @DisplayName("records a hearing that gathered nobody as no-defendants (C6)")
        void records_a_hearing_that_gathered_nobody_as_no_defendants() {
            when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                    any(JsonNode.class), any()))
                    .thenReturn(new TransformationResult.NoRegister(
                            NoRegisterReason.NO_DEFENDANTS));

            run();

            verify(guard).recordCompletion(claim, CompletionReason.NO_DEFENDANTS);
        }
    }

    /**
     * What a run skipped on its way to an answer — the counting half of fixes C19, C20 and C27.
     *
     * <p>Each of those three fixes keeps a register the legacy loses, and each of them says, in its
     * own row, that the skip is counted rather than silent. The mappers have counted through an
     * injected consumer since they were written; nothing in the running service was holding one, so
     * in a deployed pod the count went nowhere and the fixes were as quiet as the defects.
     *
     * <p>What a run does with what it counted depends on how it ended, and the difference is not a
     * detail: a submitted register has a {@code processed_output} row to carry the counts into, and
     * a run that produced no register has no row at all — there is nothing to write against. Both
     * move the metric, and the run that has nowhere to persist them says so once, in bounded codes.
     */
    @Nested
    @DisplayName("what a run skipped (C19, C20, C27)")
    class WhatARunSkipped {

        @Test
        @DisplayName("the counts a sent register survived travel with it, to be written before it "
                + "is sent")
        void the_counts_travel_with_the_register() {
            transformCounting(new TransformationResult.Register(document, OU_CODE),
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED,
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED,
                    TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT);

            run();

            final ArgumentCaptor<RegisterSubmission> submitted =
                    ArgumentCaptor.forClass(RegisterSubmission.class);
            verify(submissionClient).submit(submitted.capture());
            assertThat(submitted.getValue().document()).isEqualTo(document);
            assertThat(submitted.getValue().anomalies()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED, 2,
                    TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT, 1));
        }

        @Test
        @DisplayName("and are counted on the anomaly metric, once per occurrence")
        void the_counts_reach_the_metric() {
            transformCounting(new TransformationResult.Register(document, OU_CODE),
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED,
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED,
                    TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT);

            run();

            assertThat(anomalies("letter-delivery-dropped")).isEqualTo(2);
            assertThat(anomalies("unresolvable-youth-defendant")).isEqualTo(1);
        }

        @Test
        @DisplayName("a run that produced no register counts them and says so once, in codes")
        void a_declining_run_counts_them_and_says_so_once() {
            transformCounting(new TransformationResult.NoRegister(
                            NoRegisterReason.NO_SUBSCRIPTIONS),
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED,
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED);

            try (CapturedLog log = CapturedLog.capturing(DistributionPipeline.class)) {
                run();

                assertThat(anomalies("letter-delivery-dropped")).isEqualTo(2);
                assertThat(warnings(log)).singleElement().satisfies(warning -> assertThat(warning)
                        .contains("letter-delivery-dropped:2")
                        .contains(command.hearingId().toString())
                        .doesNotContain("recipient"));
            }
        }

        @Test
        @DisplayName("and nothing is submitted for it, so nothing is written against a row that "
                + "does not exist")
        void a_declining_run_submits_nothing() {
            transformCounting(new TransformationResult.NoRegister(
                            NoRegisterReason.NO_SUBSCRIPTIONS),
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED);

            run();

            verify(submissionClient, never()).submit(any(RegisterSubmission.class));
        }

        @Test
        @DisplayName("a run that skipped nothing carries nothing and warns about nothing")
        void a_run_that_skipped_nothing_says_nothing() {
            try (CapturedLog log = CapturedLog.capturing(DistributionPipeline.class)) {
                run();

                final ArgumentCaptor<RegisterSubmission> submitted =
                        ArgumentCaptor.forClass(RegisterSubmission.class);
                verify(submissionClient).submit(submitted.capture());
                assertThat(submitted.getValue().document()).isEqualTo(document);
                assertThat(submitted.getValue().anomalies()).isEmpty();
                assertThat(warnings(log)).isEmpty();
                assertThat(anomalies("letter-delivery-dropped")).isEqualTo(ABSENT);
            }
        }

        @Test
        @DisplayName("each run counts its own, and never the run before it")
        void each_run_counts_its_own() {
            transformCounting(new TransformationResult.Register(document, OU_CODE),
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED);

            run();
            run();

            final ArgumentCaptor<RegisterSubmission> submitted =
                    ArgumentCaptor.forClass(RegisterSubmission.class);
            verify(submissionClient, times(2)).submit(submitted.capture());
            assertThat(submitted.getAllValues()).allSatisfy(submission -> assertThat(
                    submission.anomalies()).containsExactlyInAnyOrderEntriesOf(
                            Map.of(TransformationAnomaly.LETTER_DELIVERY_DROPPED, 1)));
        }
    }

    /**
     * Defect C7's second half. The suppression is a business rule and ports unchanged; that it is
     * recorded does not — the legacy skips the register and reports {@code Success: true} with
     * nothing to say which of the five things happened.
     *
     * <p>These two cases run over the <em>real</em> policy, because what they assert is the wiring:
     * that the pipeline consults it, that a suppression becomes a named completion, and that a
     * non-boolean flag does not suppress anything on the way through.
     */
    @Nested
    @DisplayName("group proceedings (C7)")
    class GroupProceedings {

        @Test
        @DisplayName("group proceedings skip is recorded")
        void group_proceedings_skip_is_recorded() {
            when(payloadSource.fetch(any(DistributionCommand.class)))
                    .thenReturn(payloadFlagged("true"));

            final GuardDecision decision = pipelineOverTheRealPolicy().process(command, delivery());

            assertThat(decision).isInstanceOf(GuardDecision.Complete.class);
            verify(guard).recordCompletion(claim, CompletionReason.GROUP_PROCEEDINGS);
            assertThat(completions("group-proceedings")).isEqualTo(1);
            verify(transformer, never()).transform(any(DistributionCommand.class),
                    any(JsonNode.class), any(JsonNode.class), any());
            verify(submissionClient, never()).submit(any(RegisterSubmission.class));
        }

        @Test
        @DisplayName("only boolean true suppresses the register")
        void only_boolean_true_suppresses_the_register() {
            // The legacy's `isGroupProceedings == null || == false` suppresses the register for the
            // string "true" and for every other truthy value; the run then reports success with
            // nothing produced and nothing recorded. Here the register is built and submitted.
            when(payloadSource.fetch(any(DistributionCommand.class)))
                    .thenReturn(payloadFlagged("\"true\""));

            pipelineOverTheRealPolicy().process(command, delivery());

            verify(guard).recordCompletion(claim, CompletionReason.SUBMITTED);
            final ArgumentCaptor<RegisterSubmission> submitted =
                    ArgumentCaptor.forClass(RegisterSubmission.class);
            verify(submissionClient).submit(submitted.capture());
            assertThat(submitted.getValue().document()).isEqualTo(document);
            assertThat(submitted.getValue().caller()).isEqualTo(CallerIdentity.of(command));
        }
    }

    /**
     * Defect C2, and the failure taxonomy the legacy has none of. Every path out of a run ends in a
     * recorded terminal state, and which one it is depends on whether another delivery could change
     * the answer.
     */
    @Nested
    @DisplayName("failures (C2, C32)")
    class Failures {

        @Test
        @DisplayName("every failure ends in a recorded terminal state")
        void every_failure_ends_in_a_recorded_terminal_state() {
            // The claim of the whole file, asserted across every way a run can fail at once. The
            // legacy's catch-all reports failure from a *completed* orchestration and records
            // nothing, and reads `context.df.getInput()` inside the catch, which can throw again and
            // lose even the log line.
            final ArgumentCaptor<ReasonCode> recorded = ArgumentCaptor.forClass(ReasonCode.class);

            for (final RuntimeException failure : everyWayARunCanFail()) {
                when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                    any(JsonNode.class), any()))
                        .thenThrow(failure);

                final GuardDecision decision = run();

                assertThat(decision)
                        .as("a %s left the delivery unsettled", failure.getClass().getSimpleName())
                        .isNotInstanceOf(GuardDecision.Run.class)
                        .isNotNull();
            }

            verify(guard, never()).recordCompletion(any(RunClaim.class),
                    any(CompletionReason.class));
            verify(guard, times(everyWayARunCanFail().length)).recordTransientFailure(
                    any(RunClaim.class), recorded.capture());
            assertThat(recorded.getAllValues())
                    .as("each failure is recorded under its own bounded reason, not a catch-all")
                    .containsExactly(
                            ReasonCode.REFERENCE_DATA_UNAVAILABLE, ReasonCode.UNEXPECTED_FAILURE);
        }

        @Test
        @DisplayName("retries a payload the cache and the fallback could not supply (C32)")
        void retries_a_payload_the_cache_and_fallback_could_not_supply() {
            // `getPrefixHearing` returns `undefined` on an empty query-API body and `null` on an
            // error, and `if (hearingResultedObj)` is false for both — so the run stops there,
            // records nothing and reports success. A register that was never buildable stops
            // masquerading as a delivered one.
            when(payloadSource.fetch(any(DistributionCommand.class)))
                    .thenThrow(new PayloadUnavailableException(ReasonCode.PAYLOAD_UNAVAILABLE));

            final GuardDecision decision = run();

            assertThat(decision).isEqualTo(
                    new GuardDecision.Abandon(ReasonCode.PAYLOAD_UNAVAILABLE));
            verify(guard).recordTransientFailure(claim, ReasonCode.PAYLOAD_UNAVAILABLE);
            verify(guard, never()).recordCompletion(any(RunClaim.class),
                    any(CompletionReason.class));
        }

        /**
         * The other half of the payload classification, and the one the adapter boundary used to
         * lose. A query side that <em>understood</em> the read and declined it — a wrong credential,
         * a route that does not authorise this caller — answers identically on every delivery. Handed
         * back as transient it would spend the whole delivery budget re-reading it and park at the
         * end under {@code DELIVERY_LIMIT_EXHAUSTED}, which tells support the service ran out of
         * tries rather than that the read is refused. It is parked here and now, under the code the
         * adapter named.
         */
        @Test
        @DisplayName("parks a payload read the query side refused, without spending the deliveries")
        void parks_a_payload_read_the_query_side_refused() {
            when(payloadSource.fetch(any(DistributionCommand.class)))
                    .thenThrow(new PayloadUnavailableException(
                            FailureClassification.NON_TRANSIENT, ReasonCode.PAYLOAD_READ_REFUSED));

            final GuardDecision decision = run();

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.NON_TRANSIENT, ReasonCode.PAYLOAD_READ_REFUSED));
            verify(guard, never()).recordTransientFailure(any(RunClaim.class), any(ReasonCode.class));
        }

        @Test
        @DisplayName("parks a now-subscriptions read reference data refused, without spending them")
        void parks_a_subscriptions_read_reference_data_refused() {
            when(subscriptionsSource.subscriptionsOn(any(LocalDate.class), any(CallerIdentity.class)))
                    .thenThrow(new ReferenceDataUnavailableException(
                            FailureClassification.NON_TRANSIENT,
                            ReasonCode.REFERENCE_DATA_REFUSED));

            final GuardDecision decision = run();

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.NON_TRANSIENT, ReasonCode.REFERENCE_DATA_REFUSED));
            verify(guard, never()).recordTransientFailure(any(RunClaim.class), any(ReasonCode.class));
        }

        @Test
        @DisplayName("does not transform a hearing it could not read")
        void does_not_transform_a_hearing_it_could_not_read() {
            when(payloadSource.fetch(any(DistributionCommand.class)))
                    .thenThrow(new PayloadUnavailableException(ReasonCode.PAYLOAD_UNAVAILABLE));

            run();

            verify(transformer, never()).transform(any(DistributionCommand.class),
                    any(JsonNode.class), any(JsonNode.class), any());
        }

        @Test
        @DisplayName("parks a transformation that cannot produce a register, never completes it")
        void parks_a_transformation_that_cannot_produce_a_register() {
            // The BS-02 chain, deliberately constructed: `RegisterFragmentService`'s catch throws a
            // second time, `SetCourtRegister` swallows it, the orchestrator's `:33` guard skips the
            // rest, and the orchestration returns `Success: true`. Here it is a dead-letter with a
            // bounded reason.
            when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                    any(JsonNode.class), any()))
                    .thenThrow(new TransformationFailedException("ordered-date-unreadable"));

            final GuardDecision decision = run();

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.NON_TRANSIENT, ReasonCode.TRANSFORMATION_FAILED));
            verify(guard, never()).recordCompletion(any(RunClaim.class),
                    any(CompletionReason.class));
        }

        @Test
        @DisplayName("retries a submission that may succeed next time")
        void retries_a_submission_that_may_succeed_next_time() {
            when(submissionClient.submit(any(RegisterSubmission.class)))
                    .thenThrow(new SubmissionFailedException(
                            FailureClassification.TRANSIENT, ReasonCode.SUBMISSION_TRANSIENT));

            assertThat(run()).isEqualTo(
                    new GuardDecision.Abandon(ReasonCode.SUBMISSION_TRANSIENT));
        }

        @Test
        @DisplayName("parks a submission progression refused, without spending the deliveries")
        void parks_a_submission_progression_refused() {
            when(submissionClient.submit(any(RegisterSubmission.class)))
                    .thenThrow(new SubmissionFailedException(
                            FailureClassification.NON_TRANSIENT, ReasonCode.SUBMISSION_REJECTED));

            assertThat(run()).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.NON_TRANSIENT, ReasonCode.SUBMISSION_REJECTED));
        }

        @Test
        @DisplayName("parks a transient submission failure on the last permitted delivery")
        void parks_a_transient_submission_failure_on_the_last_delivery() {
            when(submissionClient.submit(any(RegisterSubmission.class)))
                    .thenThrow(new SubmissionFailedException(
                            FailureClassification.TRANSIENT, ReasonCode.SUBMISSION_TRANSIENT));

            final GuardDecision decision = pipeline().process(command, lastDelivery());

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.SUBMISSION_TRANSIENT));
        }

        @Test
        @DisplayName("never records a completion for a run that failed after the register was built")
        void never_records_a_completion_after_a_failed_submission() {
            // The exact shape of C1: the POST failed and the legacy reports the run as a success,
            // so a lost register and a delivered one are the same row.
            when(submissionClient.submit(any(RegisterSubmission.class)))
                    .thenThrow(new SubmissionFailedException(
                            FailureClassification.NON_TRANSIENT, ReasonCode.SUBMISSION_REJECTED));

            final GuardDecision decision = run();

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.NON_TRANSIENT, ReasonCode.SUBMISSION_REJECTED));
            verify(guard, never()).recordCompletion(any(RunClaim.class),
                    any(CompletionReason.class));
            assertThat(completions("submitted")).isEqualTo(ABSENT);
        }
    }

    /**
     * The recording stage - what {@code yotresultsdistribution.output=record} makes of a register that was
     * built, and the whole of user story 1 as the core sees it.
     *
     * <p>The register stops being progression's to hold. It is written into this service's own
     * store, in the transaction that supersedes the hearing's earlier active row for the day
     * (research §8), and the run completes {@code recorded} rather than {@code submitted}: two
     * spellings of one success, never both live in one deployment. Nothing is POSTed, so a
     * {@code record}-mode pod makes no traffic to progression at all - which is the observable
     * claim {@code RecordEndToEndIT} makes at the far end and this suite makes at the seam.
     *
     * <p><strong>Five arguments go into the write and each of them is somebody's answer.</strong>
     * The document is the one the transformation validated, byte for byte; the OU code travels
     * beside it because the frozen contract has no field for it and the batch needs it (the batch's
     * file name and render payload are built from it); the defendant type is the one
     * {@code DefendantTypeResolver} put on the document from the hearing's own court application
     * (FR-002); and the flag state is what the intake side last learned about the one lever. This
     * suite pins that the pipeline forwards all four and invents none of them.
     *
     * <p><strong>The flag state is the delivery's to attach and the pipeline's to record.</strong>
     * The intake side reads the one lever with the same reader the nightly job uses and hands the
     * answer down with the command; the pipeline records that answer and invents none of its own.
     * {@code RecordedFlagStateTest} (T028) holds the reading itself to its window and to its three
     * answers - what belongs here is that what was attached is what is recorded, because a register
     * recorded while the legacy was generating is one the nightly sweep must leave alone (FR-015).
     *
     * <p>A run with no reading attached records {@code UNKNOWN}, which is a statement rather than a
     * placeholder: recording never waits on a flag read, because a register that has been built is
     * worth more than the label it carries, and {@code UNKNOWN} is exactly what "nobody read it"
     * means (research §12).
     *
     * <p><strong>Two failures are settled differently, and the difference is the point.</strong> A
     * register the frozen contract refuses is a document that must never become a row - the
     * command fails SCHEMA_INVALID and is parked, and the store is not called at all (FR-004). A
     * store that has gone away is not the register's fault: the pipeline classifies nothing and
     * lets the store's own refusal out to the transport, which hands the delivery back and stops
     * intake, exactly as it does when the processed log goes away underneath a run (FR-015,
     * spec US1 acceptance 5). Classifying it here would report a transient pipeline failure, hand
     * the delivery back with intake still running, and let the next delivery meet the same dead
     * store until the broker's budget parked work whose only fault was arriving during an outage
     * of ours.
     */
    @Nested
    @DisplayName("the recording stage (US1)")
    class Recording {

        /** The side of the court application this register's defendants are on (FR-002). */
        private static final String DEFENDANT_TYPE = "Respondent";

        /** Identity of the row the store answers with, so a recording has something to report. */
        private static final UUID OUTPUT_ID =
                UUID.fromString("0a5f6d31-6c2b-4c2e-9a8f-3d51b7e0c4a9");

        /** The register as T023 hands it over: the 001 document, with its defendant type on it. */
        private final CourtRegisterDocument recorded = new CourtRegisterDocument(
                document.registerDate(),
                document.hearingDate(),
                document.hearingId(),
                document.courtCentreId(),
                document.fileName(),
                DEFENDANT_TYPE,
                document.hearingVenue(),
                document.recipients(),
                document.defendants());

        @BeforeEach
        void theOrdinaryRecording() {
            when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                        any(JsonNode.class), any()))
                    .thenReturn(new TransformationResult.Register(recorded, OU_CODE));
            when(registerStore.recordAndComplete(any(DistributionCommand.class),
                        any(CourtRegisterDocument.class), any(), any(),
                        any(RecordedFlagState.class), any()))
                    .thenAnswer(recording -> new RecordedCompletion(
                            new RecordOutcome(OUTPUT_ID, null),
                            recording.<Supplier<GuardDecision>>getArgument(5).get()));
        }

        @Test
        @DisplayName("records the register it built, then completes the run as recorded")
        void records_the_register_and_completes_recorded() {
            final GuardDecision decision = runRecording();

            final InOrder stages = inOrder(transformer, registerStore, guard);
            stages.verify(transformer).transform(eqCommand(), any(JsonNode.class),
                    any(JsonNode.class), any());
            stages.verify(registerStore).recordAndComplete(eq(command), eq(recorded), eq(OU_CODE),
                    eq(DEFENDANT_TYPE), eq(RecordedFlagState.UNKNOWN), any());
            stages.verify(guard).recordCompletion(claim, CompletionReason.RECORDED);

            assertThat(decision).isInstanceOf(GuardDecision.Complete.class);
            assertThat(completions("recorded")).isEqualTo(1);
        }

        @Test
        @DisplayName("records the document the transformation validated, with the OU code beside "
                + "it")
        void records_the_document_the_transformation_validated() {
            final ArgumentCaptor<CourtRegisterDocument> written =
                    ArgumentCaptor.forClass(CourtRegisterDocument.class);
            final ArgumentCaptor<String> ouCode = ArgumentCaptor.forClass(String.class);

            runRecording();

            verify(registerStore).recordAndComplete(eqCommand(), written.capture(), ouCode.capture(),
                    any(), any(RecordedFlagState.class), any());
            assertThat(written.getValue()).isEqualTo(recorded);
            assertThat(ouCode.getValue())
                    .as("the batch's file name and render payload are built from it, and nothing "
                            + "downstream can re-derive it from a row that did not record it")
                    .isEqualTo(OU_CODE);
        }

        @Test
        @DisplayName("records the defendant type the document carries (FR-002)")
        void records_the_defendant_type_the_document_carries() {
            final ArgumentCaptor<String> defendantType = ArgumentCaptor.forClass(String.class);

            runRecording();

            verify(registerStore).recordAndComplete(eqCommand(), any(CourtRegisterDocument.class),
                    any(), defendantType.capture(), any(RecordedFlagState.class), any());
            assertThat(defendantType.getValue()).isEqualTo(DEFENDANT_TYPE);
        }

        @Test
        @DisplayName("records a hearing that carried no court application under no defendant type")
        void records_no_defendant_type_for_a_hearing_with_no_court_application() {
            when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                        any(JsonNode.class), any()))
                    .thenReturn(new TransformationResult.Register(document, OU_CODE));

            runRecording();

            verify(registerStore).recordAndComplete(eq(command), eq(document), eq(OU_CODE), isNull(),
                    eq(RecordedFlagState.UNKNOWN), any());
        }

        @Test
        @DisplayName("records UNKNOWN for a run no flag reading was attached to, and waits on none")
        void records_the_flag_state_no_reading_was_attached_for() {
            final ArgumentCaptor<RecordedFlagState> flagState =
                    ArgumentCaptor.forClass(RecordedFlagState.class);

            runRecording();

            verify(registerStore).recordAndComplete(eqCommand(), any(CourtRegisterDocument.class),
                    any(), any(), flagState.capture(), any());
            assertThat(flagState.getValue())
                    .as("a row written without a flag read says so, rather than claiming ON or OFF")
                    .isEqualTo(RecordedFlagState.UNKNOWN);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = RecordedFlagState.class, names = {"ON", "OFF"})
        @DisplayName("records the flag state the delivery attached to the command")
        void records_the_flag_state_the_delivery_attached(final RecordedFlagState attached) {
            // FR-015 / US4 acceptance 5. The intake side reads the one lever and hands the answer
            // down with the command; what is recorded is that answer and not a constant. A row
            // recorded while the legacy was generating must say OFF, because a row that does not
            // say so is a register the nightly sweep picks up and sends a second time.
            final ArgumentCaptor<RecordedFlagState> flagState =
                    ArgumentCaptor.forClass(RecordedFlagState.class);

            recordingPipeline().process(command, delivery(), attached);

            verify(registerStore).recordAndComplete(eqCommand(), any(CourtRegisterDocument.class),
                    any(), any(), flagState.capture(), any());
            assertThat(flagState.getValue())
                    .as("the row says what the delivery was told about the flag, not what a run "
                            + "with no reading behind it would have to say")
                    .isEqualTo(attached);
        }

        @Test
        @DisplayName("records exactly once per document")
        void records_exactly_once_per_document() {
            runRecording();

            verify(registerStore, times(1)).recordAndComplete(any(DistributionCommand.class),
                    any(CourtRegisterDocument.class), any(), any(), any(RecordedFlagState.class),
                    any());
        }

        @Test
        @DisplayName("sends nothing to progression, and never completes a recording as submitted")
        void sends_nothing_to_progression_when_it_records() {
            runRecording();

            verify(submissionClient, never()).submit(any(RegisterSubmission.class));
            verify(guard).recordCompletion(claim, CompletionReason.RECORDED);
            assertThat(completions("submitted")).isEqualTo(ABSENT);
        }

        @Test
        @DisplayName("records nothing at all for a register the frozen contract refuses "
                + "(SCHEMA_INVALID)")
        void records_nothing_a_contract_refusal_stopped() {
            // FR-004: the contract is enforced before the write, so a document that could never be
            // a legal register never becomes a row. It is parked here and now rather than carried
            // to exhaustion: the same bytes meet the same refusal on every redelivery.
            when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                        any(JsonNode.class), any()))
                    .thenThrow(new TransformationFailedException(
                            "the assembled register does not satisfy the frozen register contract",
                            ReasonCode.OUTBOUND_CONTRACT_VIOLATION));

            final GuardDecision decision = runRecording();

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.NON_TRANSIENT, ReasonCode.OUTBOUND_CONTRACT_VIOLATION));
            verify(registerStore, never()).recordAndComplete(any(DistributionCommand.class),
                    any(CourtRegisterDocument.class), any(), any(), any(RecordedFlagState.class),
                    any());
            verify(guard, never()).recordCompletion(any(RunClaim.class),
                    any(CompletionReason.class));
        }

        /**
         * The same rule as the case above, stated against the signal the core is allowed to know.
         *
         * <p>A dead store is discovered by JDBC and the core may not import a JDBC type
         * (constitution Principle V), so the persistence layer translates the outage classes into
         * {@link StoreUnavailableException} and this is what the core meets. The behaviour is
         * unchanged and has to be: the run classifies nothing, records nothing and lets the signal
         * out, because the only useful answer is a suspension and that is the transport's.
         */
        @Test
        @DisplayName("lets a domain store-unavailable signal out to the transport, unclassified")
        void a_domain_store_unavailable_signal_is_left_for_the_transport() {
            final StoreUnavailableException gone = new StoreUnavailableException(
                    "the register store cannot be reached",
                    new DataAccessResourceFailureException("connection refused"));
            when(registerStore.recordAndComplete(any(DistributionCommand.class),
                        any(CourtRegisterDocument.class), any(), any(),
                        any(RecordedFlagState.class), any()))
                    .thenThrow(gone);

            assertThatThrownBy(this::runRecording)
                    .as("a store that went away was classified inside the run instead of reaching "
                            + "the transport, which is the only place that can stop intake")
                    .isSameAs(gone);

            verify(guard, never()).recordCompletion(any(RunClaim.class),
                    any(CompletionReason.class));
            verify(guard, never()).recordTransientFailure(any(RunClaim.class),
                    any(ReasonCode.class));
        }

        /**
         * The other half of the same distinction, and the half that decides a delivery's fate.
         *
         * <p>A store that went away is a suspension; a store that was reached and declined to hold
         * this row is one register to look at. The recorder settles the two refusals it accounts
         * for itself, so what reaches here is a constraint nobody wrote this recording against, and
         * the same register meets it on every delivery. It is parked here and now under the reason
         * the store named, rather than classified transient by the catch-all and redelivered four
         * more times to be parked under an exhaustion that says the service ran out of tries.
         */
        @Test
        @DisplayName("parks a recording the store refused for a reason no redelivery can change")
        void a_register_the_store_refused_is_parked_under_its_own_reason() {
            when(registerStore.recordAndComplete(any(DistributionCommand.class),
                        any(CourtRegisterDocument.class), any(), any(),
                        any(RecordedFlagState.class), any()))
                    .thenThrow(new RegisterNotRecordedException(
                            "the recording was refused by a constraint the store does not account "
                                    + "for", new DuplicateKeyException("duplicate key value")));

            final GuardDecision decision = runRecording();

            assertThat(decision)
                    .as("the throw site classified this and the pipeline never second-guesses a "
                            + "classification by reading the exception's Java type")
                    .isEqualTo(new GuardDecision.DeadLetter(
                            DeadLetterReason.NON_TRANSIENT, ReasonCode.REGISTER_NOT_RECORDED));
            verify(guard).recordNonTransientFailure(claim, ReasonCode.REGISTER_NOT_RECORDED);
            verify(guard, never()).recordCompletion(any(RunClaim.class),
                    any(CompletionReason.class));
        }

        @Test
        @DisplayName("under progression-post the 001 submission path runs unchanged, and nothing "
                + "is recorded")
        void progression_post_mode_still_submits() {
            final GuardDecision decision =
                    progressionPostPipeline().process(command, delivery());

            assertThat(decision).isInstanceOf(GuardDecision.Complete.class);
            verify(submissionClient).submit(any(RegisterSubmission.class));
            verify(guard).recordCompletion(claim, CompletionReason.SUBMITTED);
            assertThat(completions("submitted")).isEqualTo(1);
            verify(registerStore, never()).recordAndComplete(any(DistributionCommand.class),
                    any(CourtRegisterDocument.class), any(), any(), any(RecordedFlagState.class),
                    any());
        }

        /**
         * Runs one ordinary delivery through a {@code record}-mode pipeline.
         *
         * @return what the pipeline decided the delivery is worth settling as
         */
        private GuardDecision runRecording() {
            return recordingPipeline().process(command, delivery());
        }

        /**
         * The pipeline over both last stages, with the mode that selects the recording one.
         *
         * <p>Both are wired, because the mode chooses between them rather than replacing one: a
         * pipeline that held only the port its mode selected could not be asked what the other
         * mode would have done, and this suite asks exactly that of the case below.
         *
         * @return the pipeline
         */
        private DistributionPipeline recordingPipeline() {
            return recordingPipelineHolding(ANY_DOCUMENT);
        }

        /**
         * The same pipeline under the 001 mode, with the register store wired and unused.
         *
         * <p>Assembled through the mode-bearing constructor rather than the 001 one, so what the
         * case asserts is that {@code progression-post} selects the submission - not that a
         * pipeline built without a store has nowhere else to go.
         *
         * @return the pipeline
         */
        private DistributionPipeline progressionPostPipeline() {
            return new DistributionPipeline(guard, payloadSource, groupProceedings,
                    subscriptionsSource, new Dates(), transformer, OutputMode.PROGRESSION_POST,
                    registerStore, ANY_DOCUMENT, submissionClient, metrics, fixedClock(),
                    RUN_DEADLINE);
        }

        /**
         * A {@code record}-mode pipeline holding the register-document contract named.
         *
         * @param contract what the write is held to
         * @return the pipeline
         */
        private DistributionPipeline recordingPipelineHolding(
                final RegisterDocumentValidator contract) {
            return new DistributionPipeline(guard, payloadSource, groupProceedings,
                    subscriptionsSource, new Dates(), transformer, OutputMode.RECORD, registerStore,
                    contract, submissionClient, metrics, fixedClock(), RUN_DEADLINE);
        }

    }

    /**
     * The contract the register is held to at the write, which is not the one it was assembled to.
     *
     * <p>The transformation validates against the {@code add-court-register} command and then
     * attaches {@code defendantType}, so the register that reaches the store is one field larger
     * than the register anything checked - and since 002 that stored document is what the nightly
     * batch reads back and what the PDF payload is built from (constitution Principle III). These
     * two cases run the real validator over the frozen register-document schema, because the claim
     * is about <em>which</em> schema and a double could not tell one from the other.
     *
     * <p>Everywhere else in this file the port is a permissive double. Those cases are about the
     * order the recording stage does things in, and their registers are stand-ins whose fields
     * nothing reads.
     */
    @Nested
    @DisplayName("the contract the recorded register is held to (US1)")
    class TheRecordedContract {

        /** The side of the court application this register's defendants are on (FR-002). */
        private static final String DEFENDANT_TYPE = "Appellant";

        /** Identity of the row the store answers with, so a recording has something to report. */
        private static final UUID OUTPUT_ID =
                UUID.fromString("0a5f6d31-6c2b-4c2e-9a8f-3d51b7e0c4a9");

        @BeforeEach
        void theStoreWouldAcceptAnything() {
            when(registerStore.recordAndComplete(any(DistributionCommand.class),
                        any(CourtRegisterDocument.class), any(), any(),
                        any(RecordedFlagState.class), any()))
                    .thenAnswer(recording -> new RecordedCompletion(
                            new RecordOutcome(OUTPUT_ID, null),
                            recording.<Supplier<GuardDecision>>getArgument(5).get()));
        }

        /**
         * The C29 shape, met on the way into the store rather than on the way to progression.
         *
         * <p>The transformation would refuse this register too, which is exactly why the case drives
         * the pipeline over a transformer that simply hands it over: what is asserted is that the
         * write has a contract of its own and does not take the document it was passed on trust.
         */
        @Test
        @DisplayName("a register the frozen document schema refuses is failed, and not recorded")
        void a_register_the_document_schema_refuses_is_not_recorded() {
            transformerProduces(withoutAHearingVenue(DEFENDANT_TYPE));

            final GuardDecision decision =
                    recordingOver(OutboundContractValidator.overTheRegisterDocument(mapper));

            assertThat(decision)
                    .as("a document nothing validated is a document nothing can rely on, so the "
                            + "write refuses it explicitly rather than storing it")
                    .isEqualTo(new GuardDecision.DeadLetter(
                            DeadLetterReason.NON_TRANSIENT,
                            ReasonCode.OUTBOUND_CONTRACT_VIOLATION));
            verify(registerStore, never()).recordAndComplete(any(DistributionCommand.class),
                    any(CourtRegisterDocument.class), any(), any(), any(RecordedFlagState.class),
                    any());
        }

        /**
         * The discriminator, and the reason the record arm cannot simply reuse the command
         * validator: {@code defendantType} is a legal field of the register document and an unknown
         * one to the {@code add-court-register} command, which is
         * {@code additionalProperties: false}. Held to the command, every register 002 records would
         * be refused under {@code UNKNOWN_FIELD [/defendantType]}.
         */
        @Test
        @DisplayName("a register carrying its defendant type satisfies that schema and is recorded")
        void a_register_carrying_its_defendant_type_is_recorded() {
            transformerProduces(inContract(DEFENDANT_TYPE));

            final GuardDecision decision =
                    recordingOver(OutboundContractValidator.overTheRegisterDocument(mapper));

            assertThat(decision).isInstanceOf(GuardDecision.Complete.class);
            verify(registerStore).recordAndComplete(eqCommand(), any(CourtRegisterDocument.class),
                    any(), any(), any(RecordedFlagState.class), any());
        }

        private void transformerProduces(final CourtRegisterDocument register) {
            when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                        any(JsonNode.class), any()))
                    .thenReturn(new TransformationResult.Register(register, OU_CODE));
        }

        private GuardDecision recordingOver(final RegisterDocumentValidator contract) {
            return new DistributionPipeline(guard, payloadSource, groupProceedings,
                    subscriptionsSource, new Dates(), transformer, OutputMode.RECORD, registerStore,
                    contract, submissionClient, metrics, fixedClock(), RUN_DEADLINE)
                    .process(command, delivery());
        }

        /**
         * The same register with one required field missing.
         *
         * @param defendantType the side of the court application its defendants are on
         * @return a register the frozen document schema refuses
         */
        private CourtRegisterDocument withoutAHearingVenue(final String defendantType) {
            final CourtRegisterDocument valid = inContract(defendantType);
            return new CourtRegisterDocument(
                    valid.registerDate(), valid.hearingDate(), valid.hearingId(),
                    valid.courtCentreId(), valid.fileName(), valid.defendantType(),
                    null, valid.recipients(), valid.defendants());
        }
    }

    /**
     * What a {@code progression-post} deployment actually puts on the wire, once 002's field is on
     * the register.
     *
     * <p>The one case in this file that runs a real adapter under the pipeline, and it is here
     * because the claim spans both: the core hands the submission port the register it built -
     * {@code defendantType} and all - and only the adapter knows that a POST body is the
     * {@code add-court-register} command, which is {@code additionalProperties: false} and declares
     * no such field. A mocked submission port can say what the core handed over and can never say
     * what left the pod, and the two suites either side of this one each see half of it. The
     * repository and the transport are still doubles; what is real is the adapter between them.
     *
     * <p>The digest is asserted with the body because the row is claimed before the POST and is the
     * evidence a timed-out submission leaves behind: a digest of anything but the bytes that went
     * is worse than none.
     */
    @Nested
    @DisplayName("the body a progression-post deployment sends")
    class TheBodySent {

        /** The side of the court application this register's defendants are on (FR-002). */
        private static final String DEFENDANT_TYPE = "Appellant";

        /** Progression's own success, and nothing else is one. */
        private static final int ACCEPTED = 202;

        private final ProcessedOutputRepository outputs = mock(ProcessedOutputRepository.class);
        private final ProgressionCommandGateway gateway = mock(ProgressionCommandGateway.class);

        @BeforeEach
        void theRegisterCarriesItsDefendantType() {
            when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                        any(JsonNode.class), any()))
                    .thenReturn(new TransformationResult.Register(
                            inContract(DEFENDANT_TYPE), OU_CODE));
            when(outputs.claimPending(any(RunClaim.class), any(ProcessedOutputClaim.class)))
                    .thenReturn(true);
            when(gateway.post(any(byte[].class), any(CallerIdentity.class), any(Instant.class)))
                    .thenReturn(ACCEPTED);
            when(outputs.recordPosted(any(RunClaim.class), anyInt())).thenReturn(true);
        }

        @Test
        @DisplayName("is the add-court-register command body, digest and all")
        void the_bytes_sent_are_the_command_body_and_the_digest_is_of_them() {
            final ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
            final ArgumentCaptor<ProcessedOutputClaim> claimed =
                    ArgumentCaptor.forClass(ProcessedOutputClaim.class);

            final GuardDecision decision = postingPipeline().process(command, delivery());

            assertThat(decision).isInstanceOf(GuardDecision.Complete.class);
            verify(gateway).post(sent.capture(), any(CallerIdentity.class), any(Instant.class));
            verify(outputs).claimPending(any(RunClaim.class), claimed.capture());
            assertThat(new String(sent.getValue(), StandardCharsets.UTF_8))
                    .as("the command body is the register without the field the command does not "
                            + "declare, which is byte for byte what 001 sent")
                    .isEqualTo(mapper.writeValueAsString(inContract(null)));
            assertThat(claimed.getValue().requestDigest())
                    .as("the row claimed before the POST names exactly the bytes that went")
                    .isEqualTo(sha256(sent.getValue()));
        }

        /**
         * The pipeline under the 001 mode, over the real submission adapter.
         *
         * @return the pipeline
         */
        private DistributionPipeline postingPipeline() {
            return new DistributionPipeline(guard, payloadSource, groupProceedings,
                    subscriptionsSource, new Dates(), transformer, OutputMode.PROGRESSION_POST,
                    registerStore,
                    new ProgressionRegisterSubmissionClient(outputs, gateway, mapper),
                    metrics, fixedClock(), RUN_DEADLINE);
        }
    }

    /**
     * A register the frozen contract accepts, carrying the defendant type named.
     *
     * <p>The suite's other document is a stand-in whose fields nothing reads; this one is populated
     * to the contract's required set, because the case above compares a real serialisation with a
     * real one.
     *
     * @param defendantType the side of the court application its defendants are on, or {@code null}
     *                      for the 001 shape
     * @return the register
     */
    private CourtRegisterDocument inContract(final String defendantType) {
        final CourtRegisterDefendant defendant = new CourtRegisterDefendant(
                "b2b3f5a1-6c9d-4e21-8a7f-3d5c1e9b0426", "SMITH, John", "2008-04-11",
                new CourtRegisterAddress("1 High Street", null, null, null, null, "BS1 1AA"),
                null, null, "MALE", "Not Applicable", null, null, null,
                List.of(new CourtRegisterCaseOrApplication(
                        "TFL4359536", null, null, null, null, null, null)),
                null, null);
        return new CourtRegisterDocument(
                document.registerDate(),
                document.hearingDate(),
                document.hearingId(),
                document.courtCentreId(),
                document.fileName(),
                defendantType,
                new CourtRegisterHearingVenue(
                        "South West London Magistrates' Court",
                        "Lavender Hill Magistrates' Court",
                        new CourtRegisterAddress(
                                "176A Lavender Hill", null, null, null, null, "SW11 1JU")),
                List.of(new CourtRegisterRecipient(
                        "Youth Offending Team", "yot@example.gov.uk", null, "cr_standard")),
                List.of(defendant));
    }

    /**
     * The digest the submission adapter writes beside a claimed row.
     *
     * @param body the bytes that went out
     * @return their SHA-256, hex-encoded
     */
    private static String sha256(final byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Every way a run can fail that the pipeline must turn into a recorded outcome. Listed rather
     * than parameterised so the one assertion can be made across all of them at once.
     *
     * @return one failure of each kind
     */
    private RuntimeException[] everyWayARunCanFail() {
        return new RuntimeException[] {
            new ReferenceDataUnavailableException("subscriptions-read-failed"),
            new IllegalStateException("a failure nothing anticipated"),
        };
    }

    /**
     * How long a run took, recorded once, where the guard accepted the write.
     *
     * <p>The pipeline already has the two places a request reaches a terminal state the guard has
     * <em>accepted</em>: {@code settled(...)}, under {@code GuardDecision.Complete}, and
     * {@code parked(...)}, under {@code GuardDecision.DeadLetter}. Both already refuse to count a
     * write the guard rejected, which is precisely the behaviour a duration timer needs - a
     * superseded runner's completion affects no rows, so a sample from it would time a run whose
     * work another delivery is still doing.
     *
     * <p>The sample is monotonic and in-process, taken where the guard admits the run. No JVM
     * reading is subtracted from a stored timestamp, which keeps the single-time-authority rule
     * intact; the wall-clock answer to "how long has this request been going" is the exception
     * report's own database-computed {@code age_seconds}, and the two instruments answer two
     * different questions.
     */
    @Nested
    @DisplayName("the request-duration timer")
    class TheDurationTimer {

        @Test
        void a_completed_run_records_one_duration_sample_tagged_completed() {
            run();

            assertThat(durationSamples("completed"))
                    .as("one sample for the run, tagged by the state it reached")
                    .isEqualTo(1);
            assertThat(durationSamples("failed")).isEqualTo(ABSENT);
        }

        @Test
        void a_parked_run_records_one_duration_sample_tagged_failed() {
            when(payloadSource.fetch(any(DistributionCommand.class)))
                    .thenThrow(new PayloadUnavailableException(ReasonCode.PAYLOAD_UNAVAILABLE));

            final GuardDecision decision = pipeline().process(command, lastDelivery());

            assertThat(decision).isInstanceOf(GuardDecision.DeadLetter.class);
            assertThat(durationSamples("failed"))
                    .as("a run that ended parked took as long as it took, and support asks how "
                            + "long the failures are running before they park")
                    .isEqualTo(1);
            assertThat(durationSamples("completed")).isEqualTo(ABSENT);
        }

        @Test
        void a_write_the_guard_refused_records_no_sample() {
            // A superseded runner: the completion it writes affects no rows and comes back as a
            // hand-back rather than a Complete. Timing it would report a run that finished while
            // the delivery that really holds the claim is still working.
            when(guard.recordCompletion(any(RunClaim.class), any(CompletionReason.class)))
                    .thenReturn(new GuardDecision.Abandon(ReasonCode.CLAIM_NOT_ACQUIRED));

            run();

            assertThat(durationSamples("completed")).isEqualTo(ABSENT);
            assertThat(durationSamples("failed")).isEqualTo(ABSENT);
        }

        @Test
        void a_parked_run_that_was_not_dead_lettered_records_no_sample() {
            // The other half of the guard's refusal, which review gate 3's QA pass found untested:
            // `parked(...)` counts under `GuardDecision.DeadLetter` and nothing else, so a
            // superseded runner whose parking write affects no rows leaves no sample either. The
            // completion side of the same refusal is the case above; both matter, because the two
            // are separate `instanceof` branches and one could be widened without the other.
            when(guard.recordExhaustion(any(RunClaim.class), any(ReasonCode.class)))
                    .thenReturn(new GuardDecision.Abandon(ReasonCode.CLAIM_NOT_ACQUIRED));
            when(payloadSource.fetch(any(DistributionCommand.class)))
                    .thenThrow(new PayloadUnavailableException(ReasonCode.PAYLOAD_UNAVAILABLE));

            final GuardDecision decision = pipeline().process(command, lastDelivery());

            assertThat(decision)
                    .as("the guard refused the parking write, so the delivery is handed back")
                    .isInstanceOf(GuardDecision.Abandon.class);
            assertThat(durationSamples("failed"))
                    .as("and a run another delivery is still doing is not one this one timed")
                    .isEqualTo(ABSENT);
            assertThat(durationSamples("completed")).isEqualTo(ABSENT);
        }

        @Test
        void a_transient_failure_short_of_the_budget_records_no_sample() {
            // RETRYING is not a terminal state. The redelivery will run the request again, and a
            // sample per attempt would make the timer a histogram of attempts rather than of runs.
            when(payloadSource.fetch(any(DistributionCommand.class)))
                    .thenThrow(new PayloadUnavailableException(ReasonCode.PAYLOAD_UNAVAILABLE));

            final GuardDecision decision = run();

            assertThat(decision).isEqualTo(
                    new GuardDecision.Abandon(ReasonCode.PAYLOAD_UNAVAILABLE));
            assertThat(durationSamples("completed")).isEqualTo(ABSENT);
            assertThat(durationSamples("failed")).isEqualTo(ABSENT);
        }

        @Test
        void the_pipeline_holds_a_timing_token_and_imports_no_micrometer_type() throws IOException {
            // Principle V, asserted rather than reviewed. The timer lives behind an opaque token
            // for exactly this reason: a `Timer.Sample` crossing into `application/` would put the
            // metrics library in the layer that is supposed to depend on ports alone.
            final List<String> micrometerFields = Stream.of(
                            DistributionPipeline.class.getDeclaredFields())
                    .map(field -> field.getType().getName())
                    .filter(name -> name.startsWith("io.micrometer"))
                    .toList();
            final List<String> micrometerImports = Files.readAllLines(Path.of(
                            System.getProperty("user.dir"),
                            "src/main/java/uk/gov/hmcts/cp/yotresultsdistribution/application",
                            "DistributionPipeline.java")).stream()
                    .filter(line -> line.startsWith("import io.micrometer"))
                    .toList();

            assertThat(micrometerFields)
                    .as("the pipeline holds ports, a clock and a deadline — and a token it can do "
                            + "nothing with but give back")
                    .isEmpty();
            assertThat(micrometerImports).isEmpty();
        }
    }

    /**
     * The pipeline over its four ports and a policy that suppresses nothing.
     *
     * @return the pipeline
     */
    private DistributionPipeline pipeline() {
        return new DistributionPipeline(guard, payloadSource, groupProceedings, subscriptionsSource,
                new Dates(), transformer, submissionClient, metrics, fixedClock(), RUN_DEADLINE);
    }

    /**
     * The same pipeline over the real group-proceedings policy, for the two cases whose claim is
     * about the wiring rather than about the pipeline's own branching.
     *
     * @return the pipeline
     */
    private DistributionPipeline pipelineOverTheRealPolicy() {
        return new DistributionPipeline(guard, payloadSource, new GroupProceedingsPolicy(metrics),
                subscriptionsSource, new Dates(), transformer, submissionClient, metrics,
                fixedClock(), RUN_DEADLINE);
    }

    /**
     * Runs one ordinary delivery.
     *
     * @return what the pipeline decided the delivery is worth settling as
     */
    private GuardDecision run() {
        return pipeline().process(command, delivery());
    }

    /**
     * A delivery with retries still to come.
     *
     * @return the delivery
     */
    private DeliveryIdentity delivery() {
        return new DeliveryIdentity("msg-1", "runner-1", false);
    }

    /**
     * The last delivery the queue permits.
     *
     * @return the delivery
     */
    private DeliveryIdentity lastDelivery() {
        return new DeliveryIdentity("msg-1", "runner-1", true);
    }

    /**
     * A claim-check payload whose hearing carries the given raw group-proceedings value.
     *
     * @param flag the raw JSON value
     * @return the payload
     */
    private JsonNode payloadFlagged(final String flag) {
        return mapper.readTree(("{\"hearing\":{\"id\":\"1828f356-f746-4f2d-932b-79ef2df95c80\","
                + "\"isGroupProceedings\":%s},\"sharedTime\":\"2020-06-01T10:00:00Z\"}")
                .formatted(flag));
    }

    /**
     * A clock that does not move, so no case in this file reaches its processing deadline by
     * accident.
     *
     * @return the clock
     */
    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2020-06-01T10:00:05Z"), ZoneOffset.UTC);
    }

    /**
     * A clock a stage moves by hand, so a run can be made to overrun exactly where a case wants it
     * to. Real waiting would make the boundary untestable and the suite slow.
     *
     * @return the clock
     */
    private AdjustableClock movingClock() {
        return AdjustableClock.startingAt(Instant.parse("2020-06-01T10:00:05Z"));
    }

    /**
     * The pipeline over its ports and a clock a case can move.
     *
     * @param clock the clock
     * @return the pipeline
     */
    private DistributionPipeline pipelineOn(final Clock clock) {
        return new DistributionPipeline(guard, payloadSource, groupProceedings, subscriptionsSource,
                new Dates(), transformer, submissionClient, metrics, clock, RUN_DEADLINE);
    }

    /**
     * The command matcher, spelled once so the stage-order assertions read as prose.
     *
     * @return a matcher for the command under test
     */
    private DistributionCommand eqCommand() {
        return eq(command);
    }

    /**
     * A transformation that counts the given anomalies on the sink it is handed, then answers.
     *
     * <p>Which is what the real chain does: the mappers beneath it count as they walk the hearing,
     * and the answer comes back afterwards. A stub that only answered could not tell whether the
     * pipeline gave the stages anywhere to count.
     *
     * @param result  what the transformation answers
     * @param counted what it counts on the way there
     */
    private void transformCounting(
            final TransformationResult result, final TransformationAnomaly... counted) {

        when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                any(JsonNode.class), any()))
                .thenAnswer(call -> {
                    final Consumer<TransformationAnomaly> sink = call.getArgument(3);
                    for (final TransformationAnomaly anomaly : counted) {
                        sink.accept(anomaly);
                    }
                    return result;
                });
    }

    /**
     * Every warning the pipeline wrote.
     *
     * @param log the captured log
     * @return the formatted warnings
     */
    private static List<String> warnings(final CapturedLog log) {
        return log.events().stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /**
     * How many anomalies have been counted under a reason.
     *
     * @param reason the bounded reason code
     * @return the count, or {@link #ABSENT} where the series does not exist
     */
    private double anomalies(final String reason) {
        final Counter counter = registry.find(ProcessingMetrics.TRANSFORMATION_ANOMALIES)
                .tag(ProcessingMetrics.REASON_TAG, reason)
                .counter();
        return counter == null ? ABSENT : counter.count();
    }

    /**
     * How many duration samples have been recorded under a terminal outcome.
     *
     * @param outcome the bounded outcome label
     * @return the sample count, or {@link #ABSENT} where the series does not exist
     */
    private double durationSamples(final String outcome) {
        final Timer timer = registry.find(ProcessingMetrics.REQUEST_DURATION)
                .tag(ProcessingMetrics.OUTCOME_TAG, outcome)
                .timer();
        return timer == null ? ABSENT : timer.count();
    }

    /**
     * How many completions have been counted under a reason.
     *
     * @param reason the bounded reason code
     * @return the count, or {@link #ABSENT} where the series does not exist
     */
    private double completions(final String reason) {
        final Counter counter = registry.find(ProcessingMetrics.COMPLETIONS)
                .tag(ProcessingMetrics.REASON_TAG, reason)
                .counter();
        return counter == null ? ABSENT : counter.count();
    }
}
