package uk.gov.hmcts.cp.yotresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.yotresultsdistribution.application.DistributionPipeline;
import uk.gov.hmcts.cp.yotresultsdistribution.application.HearingPayloadSource;
import uk.gov.hmcts.cp.yotresultsdistribution.application.IdempotencyGuard;
import uk.gov.hmcts.cp.yotresultsdistribution.application.NowSubscriptionsSource;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterSubmission;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterSubmissionClient;
import uk.gov.hmcts.cp.yotresultsdistribution.application.SubmissionReceipt;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletionReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeadLetterReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GuardDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.support.LegacyFixtures;
import uk.gov.hmcts.cp.yotresultsdistribution.support.NowSubscriptionFixtures;

/**
 * The bean graph the running service actually has, driven end to end over one hearing.
 *
 * <p>Every other suite here builds its own object graph: the chain suite constructs the four stages
 * by hand, the pipeline suite mocks the transformation port outright. Both are right about what they
 * assert and neither can see the thing that decides whether any of it runs in production — the
 * wiring in {@link PipelineConfig}. A service whose stages are all correct and whose configuration
 * assembles the walking skeleton settles every message having produced no register at all, and the
 * only test that can say so is one that asks Spring for a {@link DistributionPipeline} rather than
 * building one.
 *
 * <p>So this suite builds nothing. It puts the real {@code PipelineConfig} in a context, gives it
 * doubles for the <strong>outermost ports only</strong> — the payload source, the now-subscriptions
 * source, the submission client, and the processed-log guard, which are the four things that talk to
 * something outside this service — and asks the pipeline that comes out to run a hearing. Between
 * the fetch and the POST everything is the real thing: the fragment builder, the subscription
 * matcher, the twelve mappers, the aggregation, and the validator holding the result to the
 * vendored progression contract.
 *
 * <p>Three hearings, chosen because each proves a different half of the wiring is real:
 *
 * <ul>
 *   <li>a child with no address, whose register the frozen contract refuses — <strong>FAILED</strong>
 *       with {@code OUTBOUND_CONTRACT_VIOLATION}, and the submission client never called (C29 with
 *       C1: the legacy sends it and swallows the 400);</li>
 *   <li>a register whose every matched subscriber asked for the post — <strong>COMPLETED
 *       no-subscriptions</strong>, and the submission client never called (C36: the legacy posts a
 *       document with no recipients, which progression renders and nobody ever receives);</li>
 *   <li>a child, a court centre and a subscriber who can be emailed — one submission, and the run
 *       completes {@code submitted}.</li>
 * </ul>
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> rows C29
 *     and C36
 */
@DisplayName("PipelineComposition")
class PipelineCompositionTest {

    private static final String HEARING_ID = "1828f356-f746-4f2d-932b-79ef2df95c80";

    /** The court house every subscription here selects, which is the base hearings' own. */
    private static final String OU_CODE = "B01LY00";

    private final IdempotencyGuard guard = mock(IdempotencyGuard.class);
    private final HearingPayloadSource payloadSource = mock(HearingPayloadSource.class);
    private final NowSubscriptionsSource subscriptionsSource = mock(NowSubscriptionsSource.class);
    private final RegisterSubmissionClient submissionClient = mock(RegisterSubmissionClient.class);

    private final RunClaim claim = new RunClaim(
            "RESULTS", command().requestId(), "runner-1", UUID.randomUUID(), "msg-1");

    /**
     * The wiring this suite is about is the one that ends in the POST, so the output mode is stated
     * rather than inherited: {@code yotresultsdistribution.output} decides which last stage
     * {@link PipelineConfig} gives the pipeline, and the {@code record} default's stage is T024's,
     * whose own suite is {@code DistributionPipelineTest}. Stating it here keeps the three cases
     * below about what they were written about - the twelve mappers, the matcher and the validator
     * between the fetch and the POST.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("yotresultsdistribution.output=progression-post")
            .withUserConfiguration(PipelineConfig.class, CompositionTestConfiguration.class)
            .withBean(ObjectMapper.class, JacksonConfig::contractObjectMapper)
            .withBean(IdempotencyGuard.class, () -> guard)
            .withBean(HearingPayloadSource.class, () -> payloadSource)
            .withBean(NowSubscriptionsSource.class, () -> subscriptionsSource)
            .withBean(RegisterSubmissionClient.class, () -> submissionClient);

    /**
     * The settings and the instrument surface, which the configuration under test consumes and does
     * not own. Everything else in the context is either the real wiring or one of the four ports.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(YotResultsDistributionProperties.class)
    static class CompositionTestConfiguration {

        /**
         * The metrics component, which is a {@code @Component} in the running service and is not
         * component-scanned here.
         *
         * @return the instrument surface
         */
        @Bean
        @ConditionalOnMissingBean
        ProcessingMetrics processingMetrics() {
            return new ProcessingMetrics(new SimpleMeterRegistry());
        }
    }

    @Test
    @DisplayName("a register the frozen contract refuses is FAILED, and progression is never asked "
            + "to take it (C29)")
    void a_document_the_contract_refuses_is_a_failure_and_nothing_is_sent() {
        admitOneRun();
        when(payloadSource.fetch(any(DistributionCommand.class)))
                .thenReturn(payload("hearing-with-address-less-youth-and-parent.json"));
        answerWith(youthSubscription());

        runner.run(context -> {
            final GuardDecision decision = run(context.getBean(DistributionPipeline.class));

            assertThat(decision).isInstanceOf(GuardDecision.DeadLetter.class);
            verify(guard).recordNonTransientFailure(
                    claim, ReasonCode.OUTBOUND_CONTRACT_VIOLATION);
            verifyNoInteractions(submissionClient);
        });
    }

    @Test
    @DisplayName("a register whose every subscriber asked for the post reaches nobody, and is not "
            + "sent to nobody (C36)")
    void a_register_that_reaches_nobody_is_completed_and_nothing_is_sent() {
        admitOneRun();
        when(payloadSource.fetch(any(DistributionCommand.class)))
                .thenReturn(payload("hearing-with-surviving-youth-defendant.json"));
        answerWith(byFirstClassPost());

        runner.run(context -> {
            run(context.getBean(DistributionPipeline.class));

            verify(guard).recordCompletion(claim, CompletionReason.NO_SUBSCRIPTIONS);
            verifyNoInteractions(submissionClient);
        });
    }

    @Test
    @DisplayName("a child, a court centre and a subscriber who can be emailed make one submission")
    void a_register_with_a_recipient_is_submitted_once() {
        admitOneRun();
        when(payloadSource.fetch(any(DistributionCommand.class)))
                .thenReturn(payload("hearing-with-surviving-youth-defendant.json"));
        answerWith(youthSubscription());
        when(submissionClient.submit(any(RegisterSubmission.class)))
                .thenReturn(new SubmissionReceipt(202, true));

        runner.run(context -> {
            run(context.getBean(DistributionPipeline.class));

            verify(submissionClient, times(1)).submit(any(RegisterSubmission.class));
            verify(guard).recordCompletion(claim, CompletionReason.SUBMITTED);
        });
    }

    // --- the ports -----------------------------------------------------------------------------

    /** The guard admits the run and accepts whatever the run reports. */
    private void admitOneRun() {
        when(guard.admit(any(DistributionCommand.class), any(DeliveryIdentity.class)))
                .thenReturn(new GuardDecision.Run(claim));
        when(guard.recordCompletion(any(RunClaim.class), any(CompletionReason.class)))
                .thenReturn(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));
        when(guard.recordNonTransientFailure(any(RunClaim.class), any(ReasonCode.class)))
                .thenAnswer(call -> new GuardDecision.DeadLetter(
                        DeadLetterReason.NON_TRANSIENT, call.getArgument(1)));
    }

    /**
     * Reference data's answer for the register's day.
     *
     * @param subscriptions the subscriptions in force
     */
    private void answerWith(final JsonNode... subscriptions) {
        when(subscriptionsSource.subscriptionsOn(any(LocalDate.class), any(CallerIdentity.class)))
                .thenReturn(NowSubscriptionFixtures.answerOf(subscriptions));
    }

    /**
     * Runs one request through the pipeline the context assembled.
     *
     * @param pipeline the pipeline
     * @return what the run decided the delivery is worth
     */
    private GuardDecision run(final DistributionPipeline pipeline) {
        return pipeline.process(command(), new DeliveryIdentity("msg-1", "runner-1", false));
    }

    /** The validated request, as the listener would have parsed it. */
    private static DistributionCommand command() {
        return new DistributionCommand(
                "RESULTS",
                UUID.fromString("3f4a2b1c-5d6e-4f70-8912-a3b4c5d6e7f8"),
                UUID.fromString(HEARING_ID),
                LocalDate.parse("2020-01-20"),
                Instant.parse("2020-06-01T10:00:00Z"),
                "Hearing_Resulted");
    }

    /**
     * One base payload, deep-copied so a case cannot change it for the next one.
     *
     * @param fixture the file name below {@code fixtures/base/}
     * @return the claim-check envelope
     */
    private JsonNode payload(final String fixture) {
        return LegacyFixtures.readBase(fixture).deepCopy();
    }

    /** A court-register subscription for this court centre, keyed on youth defendants. */
    private static ObjectNode youthSubscription() {
        return NowSubscriptionFixtures.youthCourtRegisterSubscription(OU_CODE);
    }

    /** The same subscriber, asking for the post instead of email, which this channel cannot serve. */
    private static ObjectNode byFirstClassPost() {
        return NowSubscriptionFixtures.byFirstClassPost(OU_CODE);
    }
}
