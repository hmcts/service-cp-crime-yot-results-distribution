package uk.gov.hmcts.cp.yotresultsdistribution.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.notificationnotify.NotificationNotifyClient;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.publicevents.DocumentEventListener;
import uk.gov.hmcts.cp.yotresultsdistribution.application.NotificationSummary;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterNotifierService;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.RegisterGenerationJob;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletedBy;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunReport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.GeneratedRegisters;
import uk.gov.hmcts.cp.yotresultsdistribution.support.GeneratedRegisters.Notified;
import uk.gov.hmcts.cp.yotresultsdistribution.support.GenerationStackSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.GenerationStackSupport.SentEmail;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;

/**
 * The four ways a night does not go to plan, through the whole assembled service (T061, T037).
 *
 * <p>{@code GenerationEndToEndIT} is the night that worked: the render was accepted, the topic
 * delivered the document and every Youth Offending Team was told. This is its counterpart, and every
 * case here is a silence the progression leg leaves as one - a failed render that stayed
 * indistinguishable from a successful one (defect fix P2) and a batch reported as delivered when one
 * of its recipients was never written to (defect fix P9).
 *
 * <p><strong>The four cases, and the ordering that makes them four.</strong>
 *
 * <ul>
 *   <li>A {@code generation-failed} on the topic ends the batch FAILED under the bounded
 *       GENERATION_FAILED, carrying systemdocgenerator's own words into {@code sdg_reason} - and
 *       nobody is e-mailed, because there is no document to attach.</li>
 *   <li>notificationnotify refuses one recipient: that row is FAILED with the status that made it
 *       one, the recipient behind it is still told, and the batch is PARTIALLY_NOTIFIED rather than
 *       reporting the state it would have reported had everybody been e-mailed.</li>
 *   <li>A resend re-requests the failed row only, under the identity it already holds - the same
 *       {@code notificationId} in the same path - and the batch reaches NOTIFIED.</li>
 *   <li>A batch left in flight overnight is given back by the next run's first act, re-rendered
 *       into tonight's batch and e-mailed once per team - and the outcome that finally arrives for
 *       the batch that was given up on moves nothing and is counted.</li>
 * </ul>
 *
 * <p><strong>The outcome that never arrived is the fourth of them now.</strong> It was the
 * reconciler's case, and with the query gone there is nothing to fetch: a batch whose outcome is
 * lost is released by the next run's own first act, which is {@code StaleBatchReleaser}'s to prove
 * in the small and this suite's to prove through the whole assembled service (SC-001, SC-003).
 *
 * <p><strong>An acceptance suite (tasks.md [A]).</strong> Nothing here is driven test-first: it
 * records what the assembled service does.
 */
@DisplayName("a night that does not go to plan, end to end")
class GenerationFailureEndToEndIT {

    private static final LocalDate REGISTER_DAY = LocalDate.parse("2026-08-24");

    private static final Instant REGISTER_TIME = Instant.parse("2026-08-24T16:30:00Z");

    /** The document systemdocgenerator says it rendered, where it says it rendered one. */
    private static final UUID DOCUMENT_FILE_ID =
            UUID.fromString("6a1c8e40-52b7-4d19-8f03-9b7e2c4a1d68");

    /** When it says it finished. */
    private static final Instant GENERATED_AT = Instant.parse("2026-08-24T17:02:44Z");

    /** When it says it gave up instead. */
    private static final Instant FAILED_AT = Instant.parse("2026-08-24T17:03:19Z");

    /**
     * systemdocgenerator's own words about the refusal.
     *
     * <p>Free text from another context about a document whose every defendant is a child: it
     * belongs in {@code sdg_reason}, where support reads it, and in no log line (constitution
     * Principle VII). What the batch is failed under is the bounded reason beside it.
     */
    private static final String SDG_REASON = "template OEE_Layout5 rejected the payload at page 1";

    /**
     * What notificationnotify answers the refused recipient with.
     *
     * <p>A retryable status by the one policy all this service's clients hold (C3), which is what
     * makes the resend the honest second half of this: the row records an attempt that may be worth
     * making again, and it is the run and not the client that decides whether it is made.
     */
    private static final int SERVER_ERROR = 500;

    /**
     * The shared attempt budget, as {@code yotresultsdistribution.endpoints.max-attempts} configures it.
     *
     * <p>A 500 is transient under the one policy all this service's clients hold, so the refused
     * team's row carries the whole budget rather than a single attempt.
     */
    private static final int MAX_ATTEMPTS = 3;

    /** How long an outcome is given to travel the topic and reach the batch row. */
    private static final Duration DELIVERED_WITHIN = Duration.ofSeconds(30);

    private static final Duration POLL = Duration.ofMillis(200);

    /**
     * How long ago the abandoned batch asked for the render it never heard about.
     *
     * <p>Longer than either cutoff the release pass measures by - the shipped
     * {@code yotresultsdistribution.generation.stale-after} of thirty minutes and the seventy-minute lock a
     * batch an operator asked for is judged by instead - so this batch is stale whichever of them
     * it is judged against, which is what "since the evening before" means at eighteen hundred.
     */
    private static final Duration SINCE_THE_EVENING_BEFORE = Duration.ofHours(14);

    /** A team both of the batch's recipients cases keep telling. */
    private static final CourtRegisterRecipient DURHAM = new CourtRegisterRecipient(
            "Durham Youth Offending Team", "durham.yot@example.gov.uk", null, "cr_standard");

    /** The team notificationnotify is made to refuse, and then to accept on the resend. */
    private static final CourtRegisterRecipient GATESHEAD = new CourtRegisterRecipient(
            "Gateshead Youth Offending Team", "gateshead.yot@example.gov.uk", null, "cr_standard");

    /**
     * This case's registers, at a court centre nobody else holds a row for.
     *
     * <p>Minted per case for the reason {@code GenerationEndToEndIT} mints one: the shared store
     * holds other suites' registers and batches, and what a case asserts has to be about its own.
     */
    private final GeneratedRegisters registers =
            new GeneratedRegisters(service, UUID.randomUUID());

    private static GenerationStackSupport stack;

    private static ConfigurableApplicationContext service;

    @BeforeAll
    static void startTheWholeStack() {
        PostgresTestSupport.applyFlyway();
        ProcessedLogTestSupport.dataSource();
        stack = GenerationStackSupport.start();
        service = GenerationStackSupport.startService(stack.settings());
    }

    @AfterAll
    static void stopTheWholeStack() {
        service.close();
        stack.close();
    }

    @BeforeEach
    void aNightWithTwoSubscribedTeamsWaiting() {
        stack.reset();
        stack.flagIs(true);
        registers.record(UUID.randomUUID(), REGISTER_DAY, REGISTER_TIME,
                List.of(DURHAM, GATESHEAD));
    }

    @Test
    @DisplayName("a generation-failed event fails the batch with the generator's own reason (P2)")
    void a_refused_render_should_fail_the_batch_and_tell_nobody() {
        run();
        final UUID batchId = registers.batches().getFirst();
        final UUID payloadFileId = registers.payloadFileId().orElseThrow();

        publishGenerationFailed(batchId, payloadFileId);

        await().alias("the batch reaches FAILED off the public-event topic")
                .atMost(DELIVERED_WITHIN)
                .pollInterval(POLL)
                .until(() -> registers.batchStatuses().equals(List.of(BatchStatus.FAILED.name())));
        assertThat(registers.failureReason())
                .as("the bounded reason, which is this service's own vocabulary and not the "
                        + "renderer's; the progression leg has no court-register branch to reach "
                        + "here at all and leaves the rows where they were (P2)")
                .contains(BatchFailureReason.GENERATION_FAILED.name());
        assertThat(registers.sdgReason())
                .as("and the renderer's own words beside it, for support to read out of the column")
                .contains(SDG_REASON);
        assertThat(registers.completedBy())
                .as("the topic is how the refusal was learned, and it is the only way")
                .contains(CompletedBy.EVENT.name());
        assertThat(registers.documentFileId())
                .as("a render that failed produced no document, so none is recorded")
                .isEmpty();
        assertThat(stack.emailsSent())
                .as("and nobody is written to: an e-mail about a register that was never rendered "
                        + "would be an attachment that does not exist")
                .isEmpty();
        assertThat(registers.notifications())
                .as("nor is an attempt invented against a recipient nothing was ever asked for")
                .isEmpty();
    }

    @Test
    @DisplayName("one refused recipient makes the batch PARTIALLY_NOTIFIED, not NOTIFIED (P9)")
    void a_refused_recipient_should_partially_notify_the_batch() {
        generateWithGatesheadRefused();

        assertThat(registers.batchStatuses())
                .as("a batch with a recipient it could not tell says so, rather than reporting the "
                        + "state it would have reported had everybody been e-mailed (P9)")
                .containsExactly(BatchStatus.PARTIALLY_NOTIFIED.name());
        assertThat(registers.notifications())
                .as("each recipient settled on its own answer, and the refusal carries the status "
                        + "that made it one - after the shared attempt budget was spent on it, "
                        + "because a 500 is transient and one of them says nothing about whether "
                        + "the next would be accepted")
                .containsExactly(
                        new Notified(idOf(DURHAM), DURHAM.emailAddress1(), DURHAM.recipientName(),
                                RegisterNotifierService.TEMPLATE_NAME,
                                NotificationStatus.ACCEPTED.name(),
                                NotificationNotifyClient.ACCEPTED, 1),
                        new Notified(idOf(GATESHEAD), GATESHEAD.emailAddress1(),
                                GATESHEAD.recipientName(), RegisterNotifierService.TEMPLATE_NAME,
                                NotificationStatus.FAILED.name(), SERVER_ERROR, MAX_ATTEMPTS));
        assertThat(addressedTo(stack.emailsSent()))
                .as("the recipient behind the refusal was still asked for: one team's refusal says "
                        + "nothing about another team's e-mail - and the refused team was asked "
                        + "for its whole budget, because a 500 is transient")
                .containsExactly(DURHAM.emailAddress1(), GATESHEAD.emailAddress1(),
                        GATESHEAD.emailAddress1(), GATESHEAD.emailAddress1());
        assertThat(registers.statuses())
                .as("the registers move with the batch, because the day's document exists and was "
                        + "sent to somebody")
                .containsExactly(BatchStatus.NOTIFIED.name());
    }

    @Test
    @DisplayName("a resend re-requests the failed recipient only, under the identity it holds")
    void a_resend_should_reuse_the_failed_rows_own_notification_id() {
        final UUID batchId = generateWithGatesheadRefused();
        final UUID refused = idOf(GATESHEAD);
        stack.nnRecovers();

        final NotificationSummary resent =
                service.getBean(RegisterNotifierService.class).resendFailed(batchId);

        assertThat(resent.accepted()).isEqualTo(2);
        assertThat(resent.failed()).isZero();
        assertThat(registers.batchStatuses())
                .as("the batch reaches NOTIFIED when the last of the teams it owed is accepted")
                .containsExactly(BatchStatus.NOTIFIED.name());
        assertThat(registers.notifications())
                .as("the refused row is accepted on its second attempt and keeps the identity it "
                        + "was minted with; nothing is re-minted, because a fresh identity would be "
                        + "a second e-mail to the same team rather than the attempt it is retrying")
                .extracting(Notified::notificationId, Notified::status, Notified::responseCode,
                        Notified::attempts)
                .containsExactly(
                        tuple(idOf(DURHAM), NotificationStatus.ACCEPTED.name(),
                                NotificationNotifyClient.ACCEPTED, 1),
                        tuple(refused, NotificationStatus.ACCEPTED.name(),
                                NotificationNotifyClient.ACCEPTED, MAX_ATTEMPTS + 1));
        assertThat(pathsAsked())
                .as("and every POST for that team went to the path the first one did, which is what "
                        + "makes it idempotent on notificationnotify's side: five requests in all, "
                        + "four of them the refused team's own - three inside the run's budget and "
                        + "the fifth the resend that was accepted")
                .containsExactly(
                        GenerationStackSupport.notificationPathFor(idOf(DURHAM)),
                        GenerationStackSupport.notificationPathFor(refused),
                        GenerationStackSupport.notificationPathFor(refused),
                        GenerationStackSupport.notificationPathFor(refused),
                        GenerationStackSupport.notificationPathFor(refused));
    }

    @Test
    @DisplayName("a batch left in flight overnight is released, re-rendered, and its late outcome "
            + "moves nothing (SC-001, SC-003)")
    void a_stale_batch_should_be_released_re_rendered_and_left_alone_by_its_late_outcome() {
        run();
        final UUID abandoned = registers.batches().getFirst();
        final UUID abandonedPayload = registers.payloadFileIdOf(abandoned).orElseThrow();
        registers.hasBeenWaitingFor(SINCE_THE_EVENING_BEFORE);
        final double ignoredBefore = terminalBatchIgnored();

        final RunReport report = run();

        assertThat(report.releasedBatches())
                .as("the run's first act found last night's batch still waiting for a render "
                        + "nothing can be asked about any more, and gave up on it (SC-001)")
                .isEqualTo(1);
        assertThat(report.releasedRegisters())
                .as("and the register it held came back to be batched by this same run")
                .isEqualTo(1);
        assertThat(registers.statusOf(abandoned)).contains(BatchStatus.FAILED.name());
        assertThat(registers.failureReasonOf(abandoned))
                .as("under the bounded reason that says what happened rather than inventing an "
                        + "outcome nobody delivered")
                .contains(BatchFailureReason.NOT_COMPLETED_BY_NEXT_RUN.name());
        assertThat(registers.batches())
                .as("two batches for this court centre day: the one that was given up on, and "
                        + "tonight's, which is the document the court centre is owed")
                .hasSize(2);

        final UUID tonight = registers.batches().get(1);
        publishDocumentAvailable(tonight, registers.payloadFileIdOf(tonight).orElseThrow());
        await().alias("tonight's batch reaches NOTIFIED off the public-event topic")
                .atMost(DELIVERED_WITHIN)
                .pollInterval(POLL)
                .until(() -> registers.statusOf(tonight)
                        .equals(Optional.of(BatchStatus.NOTIFIED.name())));
        assertThat(registers.notifications())
                .as("exactly one e-mail per team for this court centre day, across both batches: "
                        + "a released batch told nobody, so the teams hear about the day once "
                        + "(SC-003)")
                .extracting(Notified::emailAddress, Notified::status, Notified::attempts)
                .containsExactly(
                        tuple(DURHAM.emailAddress1(), NotificationStatus.ACCEPTED.name(), 1),
                        tuple(GATESHEAD.emailAddress1(), NotificationStatus.ACCEPTED.name(), 1));

        publishDocumentAvailable(abandoned, abandonedPayload);

        await().alias("the late outcome is counted under its bounded reason")
                .atMost(DELIVERED_WITHIN)
                .pollInterval(POLL)
                .until(() -> terminalBatchIgnored() >= ignoredBefore + 1);
        assertThat(registers.statusOf(abandoned))
                .as("the batch this service had already ended stays ended: re-stamping it would "
                        + "send a second e-mail about a day that has had its document")
                .contains(BatchStatus.FAILED.name());
        assertThat(registers.documentFileIdOf(abandoned))
                .as("and nothing of the document it was told about is written to the row it was "
                        + "told about it for")
                .isEmpty();
        assertThat(registers.notifications())
                .as("still one per team, which is the guarantee the counter above says was needed")
                .hasSize(2);
    }

    /**
     * How many outcomes this service has dropped because their batch had already ended.
     *
     * @return the reading of {@code yotresultsdistribution_public_events_ignored_total} under
     *         {@code terminal-batch}, and nought where nothing has moved it yet
     */
    private static double terminalBatchIgnored() {
        final Counter counted = service.getBean(MeterRegistry.class)
                .find(GenerationMetrics.PUBLIC_EVENTS_IGNORED)
                .tag(GenerationMetrics.REASON_TAG, GenerationMetrics.TERMINAL_BATCH)
                .counter();
        return counted == null ? 0 : counted.count();
    }

    /**
     * A batch whose document exists, one of whose two teams notificationnotify would not take.
     *
     * <p>The whole of it happens on the service's own thread: the mark that records the document is
     * the step that asks for the e-mails, so there is no moment at which a caller could ask for the
     * notification itself. The wait is for the batch to leave GENERATING, which is where the run
     * left it.
     *
     * @return the batch, for the resend to be asked about
     */
    private UUID generateWithGatesheadRefused() {
        stack.nnAnswers(GATESHEAD.emailAddress1(), SERVER_ERROR);
        run();
        final UUID batchId = registers.batches().getFirst();
        publishDocumentAvailable(batchId, registers.payloadFileId().orElseThrow());
        await().alias("the batch settles PARTIALLY_NOTIFIED off the public-event topic")
                .atMost(DELIVERED_WITHIN)
                .pollInterval(POLL)
                .until(() -> registers.batchStatuses()
                        .equals(List.of(BatchStatus.PARTIALLY_NOTIFIED.name())));
        return batchId;
    }

    /**
     * The identity the row for one recipient was minted with.
     *
     * @param recipient the team whose row is wanted
     * @return its {@code notification_id}
     */
    private UUID idOf(final CourtRegisterRecipient recipient) {
        return registers.notifications().stream()
                .filter(row -> recipient.emailAddress1().equals(row.emailAddress()))
                .map(Notified::notificationId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no notification row was minted for a recipient of this batch"));
    }

    /**
     * Which addresses this stack was asked to write to.
     *
     * @param sent the e-mails notificationnotify received
     * @return their addresses, read off the path's own row rather than the body
     */
    private List<String> addressedTo(final List<SentEmail> sent) {
        return sent.stream().map(email -> addressOf(email.path())).toList();
    }

    /**
     * Every path this stack was asked for an e-mail under, in arrival order.
     *
     * @return the command paths, one per request received
     */
    private static List<String> pathsAsked() {
        return stack.emailsSent().stream().map(SentEmail::path).toList();
    }

    /**
     * The address the row behind one command path holds.
     *
     * <p>Read from the row rather than from the body, because that is the stronger statement: the
     * identity in the path and the address the service recorded against it have to be the same
     * recipient, or the evidence a batch leaves behind describes a different e-mail from the one it
     * sent.
     *
     * @param path the command path a request was made under
     * @return the address of the row whose identity that path carries
     */
    private String addressOf(final String path) {
        return registers.notifications().stream()
                .filter(row -> path.equals(
                        GenerationStackSupport.notificationPathFor(row.notificationId())))
                .map(Notified::emailAddress)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "an e-mail was asked for under an identity this batch never minted: "
                                + path));
    }

    /**
     * Publishes systemdocgenerator's announcement that it rendered the document.
     *
     * @param batchId       the batch the render was requested for
     * @param payloadFileId the payload it was rendered from
     */
    private static void publishDocumentAvailable(final UUID batchId, final UUID payloadFileId) {
        publish(DocumentEventListener.DOCUMENT_AVAILABLE, """
                {"_metadata":{"id":"%s","name":"%s"},
                 "sourceCorrelationId":"%s",
                 "payloadFileServiceId":"%s",
                 "documentFileServiceId":"%s",
                 "templateIdentifier":"OEE_Layout5",
                 "originatingSource":"%s",
                 "generatedTime":"%s"}
                """.formatted(UUID.randomUUID(), DocumentEventListener.DOCUMENT_AVAILABLE, batchId,
                payloadFileId, DOCUMENT_FILE_ID, DocumentEventListener.ORIGINATING_SOURCE,
                OffsetDateTime.ofInstant(GENERATED_AT, ZoneOffset.UTC)));
    }

    /**
     * Publishes systemdocgenerator's announcement that it refused the render.
     *
     * @param batchId       the batch the render was requested for
     * @param payloadFileId the payload it was asked to render
     */
    private static void publishGenerationFailed(final UUID batchId, final UUID payloadFileId) {
        publish(DocumentEventListener.GENERATION_FAILED, """
                {"_metadata":{"id":"%s","name":"%s"},
                 "sourceCorrelationId":"%s",
                 "payloadFileServiceId":"%s",
                 "templateIdentifier":"OEE_Layout5",
                 "originatingSource":"%s",
                 "failedTime":"%s",
                 "reason":"%s"}
                """.formatted(UUID.randomUUID(), DocumentEventListener.GENERATION_FAILED, batchId,
                payloadFileId, DocumentEventListener.ORIGINATING_SOURCE,
                OffsetDateTime.ofInstant(FAILED_AT, ZoneOffset.UTC), SDG_REASON));
    }

    /**
     * Puts one framework envelope onto {@code public.event}, as the estate publishes it.
     *
     * <p>{@code CPPNAME} as the string property the broker's selector filters on, and the times
     * carrying the offset the platform sends them with rather than in UTC.
     *
     * @param eventName the event's own name, which is both the property and the envelope's
     * @param body      the envelope
     */
    private static void publish(final String eventName, final String body) {
        try (JMSContext jms = service.getBean(ConnectionFactory.class).createContext()) {
            jms.createProducer()
                    .setProperty(DocumentEventListener.EVENT_NAME_PROPERTY, eventName)
                    .send(jms.createTopic("public.event"), body);
        }
    }

    /**
     * Runs the night through the bean the schedule would have fired.
     *
     * <p>Three of the four cases drop the return: what a run reports about its requesting half is
     * {@code GenerationEndToEndIT}'s subject, and they are about what happens to the batch
     * afterwards. The fourth reads it, because what the run's first act gave back is the thing
     * being asserted and the report is where a run says so (FR-009).
     *
     * @return what the run reported
     */
    private static RunReport run() {
        return service.getBean(RegisterGenerationJob.class).run();
    }
}
