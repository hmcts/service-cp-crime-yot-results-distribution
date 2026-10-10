package uk.gov.hmcts.cp.yotresultsdistribution.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.notificationnotify.NotificationNotifyClient;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.notificationnotify.NotificationNotifyReportMailer;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.publicevents.DeliveryObserver;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.publicevents.DocumentEventListener;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.report.EmailReportSink;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.report.LogEventReportSink;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.systemdocgenerator.SystemDocGeneratorClient;
import uk.gov.hmcts.cp.yotresultsdistribution.application.DocumentOutcomeSinkImpl;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ExceptionReportService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ExceptionReportSink;
import uk.gov.hmcts.cp.yotresultsdistribution.application.FeatureFlagReader;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OnDemandExceptionReportService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.PayloadFileStore;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterGenerationService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterNotifierService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterStore;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ReleasedBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RenderProgress;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ReportMailer;
import uk.gov.hmcts.cp.yotresultsdistribution.application.StaleReleaseOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.application.StaleReleaseProgress;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.BatchAgeSweep;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.BatchAssembler;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.ExceptionReportJob;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.FeatureFlagGate;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.IntakeAgeSweep;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.RegisterGenerationJob;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.StaleBatchReleaser;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationProperties;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.config.ProcessingMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.AssembledBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchAssembly;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletedBy;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtCentreDay;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterCaseOrApplication;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeadLetterReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.Deadline;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionReport;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailedNotification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.PayloadStoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ProcessedRequestSummary;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedRegisterSummary;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterRecord;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportMail;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportSinkName;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportWindow;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreRefusedRowException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.NotificationClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.NotificationSettlement;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.ProcessedRequestRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterNotificationRepository;

/**
 * A night's registers driven end to end, over a batch and a recipient made entirely of markers.
 *
 * <p>The subject of {@code config/TelemetryPrivacyTest}'s downstream group: everything that happens
 * to a register after the delivery path has recorded it. A batch is assembled, a payload is stored,
 * systemdocgenerator is asked for the render, the outcome comes back on the topic, and the
 * document is e-mailed to the Youth Offending Teams the subscription matched - and every one of
 * those steps writes lines and moves meters. What this fixture exists to do is make <em>every</em>
 * one of those lines happen once, with a marker wherever a person could be named, so a sweep over
 * the capture is a sweep over the whole leg rather than over a sample of it.
 *
 * <p><strong>Three doubles, and they are the three that write nothing.</strong> The register store
 * and its two repositories are mocks, because a database is the one outside a unit suite cannot
 * have and because none of the three writes a log line of its own. Everything above them is real:
 * the payload mapper that turns a batch into a render payload, the client that asks
 * systemdocgenerator, the client that asks notificationnotify, the topic listener, the outcome
 * sink, the stale-batch pass and the nightly run. The two HTTP clients matter most - they are the
 * classes
 * with a recipient's address in their hands and a far end's status line in their exceptions - so
 * they run over a real socket against a real server, and every answer below is one
 * systemdocgenerator or notificationnotify really could give.
 *
 * <p>The markers themselves are {@link PersonalDataMarkers}', because the container suite sweeps
 * for the same values through the same leg: two lists of markers is how one suite comes to look for
 * a field the other has stopped setting, with both of them green.
 *
 * <p>It is a wide class, and the width is the subject rather than a smell: a claim about every
 * line the leg can write cannot be made from a narrower graph, and one method per arrangement is
 * what keeps which arrangement produces which line legible.
 *
 * <p><strong>The drive is about what was written down, not about what was answered.</strong> Most
 * of the scenarios below are failures, and several of them end in a refusal reaching the caller -
 * that is what makes the line happen. Each is therefore run through
 * {@link #whateverItAnswers(Runnable)}, which is not a swallowed failure: what those calls answer
 * is asserted by the eight suites that own those classes, and this one asserts what they said out
 * loud on the way.
 */
public final class GenerationLegs implements AutoCloseable {

    /** The batch every scenario is about, fixed so a suite can look for it in the capture. */
    public static final UUID BATCH_ID = UUID.fromString("11111111-2222-4333-8444-555555555555");

    /** A second batch, the one the stale-batch pass could not give back. */
    public static final UUID CONTENDED_BATCH_ID =
            UUID.fromString("22222222-3333-4444-8555-666666666666");

    /** The identity one recipient's e-mail is asked for under, fixed for the same reason. */
    public static final UUID NOTIFICATION_ID =
            UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa");

    /**
     * The four classes the exception report writes its own lines from.
     *
     * <p>Named on their own as well as inside {@link #THE_LEGS}, because a class that declares no
     * statement contributes nothing to a sweep over declarations and is passed over in silence. A
     * suite that widens the enumeration has to be able to say the classes it added really brought
     * lines with them, or it has widened the list and not the claim.
     */
    public static final List<Class<?>> THE_REPORT = List.of(
            ExceptionReportService.class,
            LogEventReportSink.class,
            EmailReportSink.class,
            OnDemandExceptionReportService.class);

    /**
     * The classes that write a line about a register: the two legs, and the report over both.
     *
     * <p>The report is inside this list rather than in a sweep of its own because the rule is one
     * rule. Its entries name the batches and the notifications the legs above produced, and a
     * claim about what may reach the estate's index that held for the e-mail but not for the
     * morning's report would be a rule enforced on one half of a service.
     */
    public static final List<Class<?>> THE_LEGS = Stream.concat(
            Stream.of(
                    RegisterGenerationJob.class,
                    StaleBatchReleaser.class,
                    ExceptionReportJob.class,
                    IntakeAgeSweep.class,
                    BatchAgeSweep.class,
                    RegisterGenerationService.class,
                    SystemDocGeneratorClient.class,
                    DocumentEventListener.class,
                    DocumentOutcomeSinkImpl.class,
                    RegisterNotifierService.class,
                    NotificationNotifyClient.class,
                    NotificationNotifyReportMailer.class),
            THE_REPORT.stream()).toList();

    /** Everything a meter's name or label may never carry, whoever it describes. */
    public static final List<String> NOTHING_A_SERIES_MAY_CARRY = List.of(
            PersonalDataMarkers.RECIPIENT_EMAIL,
            PersonalDataMarkers.RECIPIENT_ORGANISATION,
            PersonalDataMarkers.GENERATOR_REASON);

    /** The request the morning's report is about, fixed so a suite can look for it. */
    public static final UUID REQUEST_ID = UUID.fromString("4c8e1a70-9b2d-4f36-8a57-c1d0e9f3b284");

    /**
     * A second request, unfinished, so the drive's report carries one of every kind.
     *
     * <p>Both intake rows used to carry {@link #REQUEST_ID}, which is one request answered by two
     * statements - and the service folds that to one entry now, leaving the drive with no
     * REQUEST_LATE line to sweep. Two requests, one parked and one still open, is what the
     * arrangement was always describing.
     */
    private static final UUID UNFINISHED_REQUEST_ID =
            UUID.fromString("1f7a3c85-2d69-4b04-9e13-8c5b0d2f7a46");

    /** The hearing that request carries, which the stranded register carries too. */
    private static final UUID HEARING_ID =
            UUID.fromString("9e2b4c60-1d38-4a75-9f04-6b3c8d1e5a72");

    /** The correlation the report's drive runs under, which the caller gives and never reads. */
    private static final String RUN_ID = "report-drive-1";

    /** The source the intake half attributes the request to, which is not a person's name. */
    private static final String SOURCE = "RESULTS";

    /** The schedule that decides what "the last run left this behind" means. */
    private static final String GENERATION_CRON = "0 0 18 * * MON-FRI";

    /** And the report's own, which is what its window is measured back through. */
    private static final String REPORT_CRON = "0 0 7 * * MON-FRI";

    /**
     * The one limit the three report thresholds are all given.
     *
     * <p>This fixture is about the lines, not about the thresholds: every row it seeds is answered
     * by a doubled read whatever cut-off the service computes, so three separate durations would
     * be three numbers nothing here depends on.
     */
    private static final Duration REPORT_LIMIT = Duration.ofMinutes(30);

    /** The ages the three kinds of projection carry, as the database would have computed them. */
    private static final long A_REQUESTS_AGE = 5_400L;

    private static final long A_BATCHS_AGE = 7_200L;

    private static final long A_REGISTERS_AGE = 9_000L;

    /** The status line notificationnotify refused one team's e-mail with. */
    private static final int REFUSED_STATUS = 400;

    /** The shipped entry cap, stated rather than defaulted: no case here is about truncation. */
    private static final int MAX_ENTRIES = 5000;

    /** The cap a morning too bad to carry is read under, so the truncation line is written. */
    private static final int ONE_ENTRY = 1;

    private static final UUID PAYLOAD_FILE_ID =
            UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-ffffffffffff");

    private static final UUID DOCUMENT_FILE_ID =
            UUID.fromString("0a1b2c3d-4e5f-4a6b-8c9d-1e2f3a4b5c6d");

    private static final UUID TEMPLATE_ID =
            UUID.fromString("7c1d9f42-3b8a-4e15-9c60-2d5f8a1b4e37");

    private static final UUID COURT_CENTRE =
            UUID.fromString("2f6b8d10-4a3c-4e57-9b21-8c0d5e7f1a94");

    private static final String OU_CODE = "B01LY00";

    private static final String COURT_HOUSE = "Lavender Hill Youth Court";

    private static final LocalDate REGISTER_DATE = LocalDate.of(2026, 3, 2);

    private static final String FILE_NAME = "CourtRegister_B01LY00_20260302.pdf";

    private static final String SYSTEM_USER_ID = "b2f8a1c4-6d3e-4f57-9a80-1c5b7e9d2f46";

    private static final Instant AT = Instant.parse("2026-03-02T18:04:00Z");

    /** The run's budget, long enough that only the scenario that shortens it overruns. */
    private static final Duration RUN_DEADLINE = Duration.ofMinutes(60);

    /** Attempts and back-off small enough that a retry loop finishes inside a unit suite. */
    private static final int MAX_ATTEMPTS = 2;

    private static final Duration BACKOFF = Duration.ofMillis(1);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    private static final Duration STALE_AFTER = Duration.ofMinutes(30);

    private static final Duration BATCH_AGE_REFRESH = Duration.ofMinutes(10);

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    /** Any generate-document command, which is one path and takes no parameter. */
    private static final String ANY_RENDER_COMMAND = SystemDocGeneratorClient.COMMAND_PATH;

    /** Any send-email-notification, whatever identity it was made under. */
    private static final String ANY_EMAIL_COMMAND = NotificationNotifyClient.COMMAND_PATH
            .replace("{notificationId}", "[^/]+");

    private final WireMockServer contexts;

    private final GenerationMetrics metrics;

    private final AdjustableClock clock = AdjustableClock.startingAt(AT);

    private final RegisterStore store = mock(RegisterStore.class);

    private final RegisterBatchRepository batches = mock(RegisterBatchRepository.class);

    private final RegisterNotificationRepository notifications =
            mock(RegisterNotificationRepository.class);

    private final ProcessedRequestRepository requestLog = mock(ProcessedRequestRepository.class);

    private final PayloadFileStore payloadFileStore = mock(PayloadFileStore.class);

    private final BatchAssembler assembler = mock(BatchAssembler.class);

    private final FeatureFlagGate gate = mock(FeatureFlagGate.class);

    private final SystemDocGeneratorClient renderer;

    private final NotificationNotifyClient notifier;

    private final RegisterGenerationService generation;

    private final RegisterNotifierService notifying;

    private final StaleBatchReleaser releaser;

    private final DocumentOutcomeSinkImpl sink;

    private final DocumentEventListener listener;

    private final RegisterGenerationJob job;

    private final ExceptionReportService reporting;

    private final LogEventReportSink logSink = new LogEventReportSink();

    /**
     * The intake half's instruments, on a registry of their own.
     *
     * <p>Separate from the one handed in, which the two meter cases above read back: those are
     * about what the downstream half publishes, and the report's and the sweep's counters are not
     * that. What this fixture wants from them is the lines the classes holding them write.
     */
    private final ProcessingMetrics intakeMetrics = new ProcessingMetrics(new SimpleMeterRegistry());

    private final ExceptionReportJob reportJob;

    /**
     * The report's own send, over the same socket the register leg's client uses.
     *
     * <p>Real rather than doubled for the reason the other two clients are: it is one of the two
     * classes in this increment with an address in its hands and a far end's status line in its
     * answers, so every line it can write has to be written over something that really answered.
     */
    private final NotificationNotifyReportMailer reportMailer;

    private final IntakeAgeSweep sweep;

    /** The three in-flight batch readings, whose only lines are a parked batch and a refusal. */
    private final BatchAgeSweep batchSweep;

    /** Whether the refusal the drive applied carried systemdocgenerator's words into the store. */
    private boolean generatorWordsKept;

    private GenerationLegs(final WireMockServer wireMock, final MeterRegistry registry) {
        this.contexts = wireMock;
        this.metrics = new GenerationMetrics(registry);
        this.renderer = new SystemDocGeneratorClient(
                restClientFor(wireMock.baseUrl()), SYSTEM_USER_ID, MAPPER);
        this.notifier = new NotificationNotifyClient(
                restClientFor(wireMock.baseUrl()), SYSTEM_USER_ID, MAPPER);
        this.generation = new RegisterGenerationService(store,
                new uk.gov.hmcts.cp.yotresultsdistribution.pipeline.PdfPayloadMapper(clock),
                payloadFileStore, renderer, MAPPER, retryPolicy(), waited -> {}, metrics, clock);
        this.notifying = new RegisterNotifierService(store, batches, notifications, notifier,
                metrics, TEMPLATE_ID, retryPolicy(), waited -> {}, clock);
        this.sink = sinkOf();
        this.listener = new DocumentEventListener(sink, metrics, DeliveryObserver.NONE);
        this.releaser = new StaleBatchReleaser(store, metrics, settings().staleAfter(),
                settings().lockAtMostFor(), clock);
        this.job = new RegisterGenerationJob(gate, store, assembler, generation, releaser,
                metrics, settings(), clock);
        this.reporting = new ExceptionReportService(requestLog, batches, notifications, store,
                REPORT_LIMIT, REPORT_LIMIT, REPORT_LIMIT, MAX_ENTRIES, GENERATION_CRON,
                GenerationProperties.COURTS_ZONE, intakeMetrics, clock);
        this.reportJob = new ExceptionReportJob(reporting, List.of(logSink), REPORT_CRON,
                GenerationProperties.COURTS_ZONE, intakeMetrics, clock);
        this.sweep = new IntakeAgeSweep(requestLog, intakeMetrics, REPORT_LIMIT, clock);
        this.batchSweep = new BatchAgeSweep(batches, metrics, clock);
        this.reportMailer = new NotificationNotifyReportMailer(
                restClientFor(wireMock.baseUrl()), SYSTEM_USER_ID, MAPPER);
    }

    /**
     * Starts the two contexts and assembles the leg over them.
     *
     * @param registry the registry the leg's meters are registered against and read back off
     * @return the leg, to be closed when the drive is done
     */
    public static GenerationLegs overMarkedRecipients(final MeterRegistry registry) {
        final WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        return new GenerationLegs(wireMock, registry);
    }

    @Override
    public void close() {
        contexts.stop();
    }

    /**
     * Whether the drive handed systemdocgenerator's own words to something that keeps them.
     *
     * <p>The vacuity guard for the privacy case that now says those words reach <em>no</em> line at
     * any level. They used to be allowed one: the retired query client wrote them into a DEBUG slot,
     * and the sweep proved it had carried one by finding it there. With the query gone the words
     * arrive on the public-event topic and go to {@code sdg_reason}, which is a column - so the
     * proof that the drive carried them has to be taken where they land, which is the store.
     *
     * <p>Read at the moment the call is made rather than at the end of the drive, because
     * {@code reset} clears a mock's recorded invocations and several arrangements after that one
     * reset the store.
     *
     * @return whether the refusal the drive applied carried the marker into the store
     */
    public boolean generatorWordsReachedTheStore() {
        return generatorWordsKept;
    }

    /**
     * Makes every line the leg can write happen once.
     *
     * <p>Grouped by the class each arrangement is about, and every group is one arrangement per
     * line rather than one per interesting behaviour: the claim the capture is swept for is about
     * the lines, so a branch nothing here reaches is a line outside it.
     */
    public void driveEverything() {
        theNightlyRun();
        theRequestingLeg();
        theRenderersClient();
        theStaleBatchPass();
        theTopicListener();
        theOutcomeSink();
        theNotifyingLeg();
        theNotifiersClient();
        theExceptionReport();
        theReportsEmail();
        theReportsMailer();
        theMorningRun();
        theOnDemandReport();
        theIntakeGaugeRefresh();
        theBatchAgeRefresh();
    }

    // --- the nightly run -------------------------------------------------------------------------

    private void theNightlyRun() {
        aNightTheOneLeverStopped();

        readyToGenerate();
        when(store.assemble(any(RegisterBatch.class), anyList()))
                .thenThrow(new StoreUnavailableException(
                        "the store could not be reached to stamp a batch onto its registers",
                        new IllegalStateException("the connection pool is empty")));
        whateverItAnswers(job::run);

        readyToGenerate();
        when(store.assemble(any(RegisterBatch.class), anyList())).thenReturn(pending());
        renderCommandAnswering(HttpStatus.ACCEPTED.value());
        whateverItAnswers(job::run);

        aNightThatStoppedPartWay();
        aNightOneOfWhoseRequestsDidNotFinish();
        aNightWhoseOwnBatchesCouldNotBeReadBack();
    }

    /**
     * The night the store answered everything except what the run's own batches had come to.
     *
     * <p>The one read of a run that is not allowed to end it. By the time it is taken the batches
     * are stamped and the renders are away, so a store that will not answer it costs the line its
     * four settled counts and nothing else - and the line says {@code snapshot=unread} rather than
     * reporting zeroes as facts. The class of what refused is named beside it for the reason this
     * sweep exists: a reading nobody can tell from a quiet night is worse than no reading, and the
     * words that name it have to be as bounded as the counts they stand in for.
     */
    private void aNightWhoseOwnBatchesCouldNotBeReadBack() {
        readyToGenerate();
        when(store.assemble(any(RegisterBatch.class), anyList())).thenReturn(pending());
        renderCommandAnswering(HttpStatus.ACCEPTED.value());
        when(store.batchesNamed(any())).thenThrow(new StoreUnavailableException(
                "the store could not be reached to read a run's own batches back by identity",
                new IllegalStateException("the connection pool is empty")));
        whateverItAnswers(job::run);
    }

    /**
     * The night one batch's request threw and the run carried on past it.
     *
     * <p>The store will not take the batch's payload id, so its request leaves through a throw; the
     * run counts it, says so by class, and goes on (defect fix P5). The line names the batch and
     * the class of what was raised - never the store's own words.
     */
    private void aNightOneOfWhoseRequestsDidNotFinish() {
        readyToGenerate();
        when(store.assemble(any(RegisterBatch.class), anyList())).thenReturn(pending());
        when(store.batched(BATCH_ID)).thenReturn(List.of(register(List.of(recipient()))));
        doThrow(new StoreUnavailableException(
                "the store could not be reached to mint a batch's payload id",
                new IllegalStateException("the connection pool is empty")))
                .when(store).markPayloadMinted(any(), any());
        whateverItAnswers(job::run);
    }

    /**
     * The night the store went away before the run could read what was waiting.
     *
     * <p>The one line the run writes about itself rather than about a batch: a run that stopped
     * part way still reports how far it got, and what stopped it is named beside that report. The
     * store's refusal carries its own words and the class of the cause, which is what the sweep is
     * here to hold to counts and bounded codes.
     */
    private void aNightThatStoppedPartWay() {
        readyToGenerate();
        when(store.activeUnbatched()).thenThrow(new StoreUnavailableException(
                "the store could not be reached to read what is waiting to be batched",
                new IllegalStateException("the connection pool is empty")));
        whateverItAnswers(job::run);
    }

    /**
     * The night the flag said the legacy generates, over the real gate rather than a doubled one.
     *
     * <p>The gate is the one class of the leg that counts a run without writing a line about it,
     * and its label is a bounded code {@code FlagDecision} owns. It is real here for that reason:
     * a doubled gate leaves {@code yotresultsdistribution_generation_skipped_total} with no series and its
     * label outside the sweep.
     */
    private void aNightTheOneLeverStopped() {
        reset(store, batches, assembler);
        final FeatureFlagReader reader = mock(FeatureFlagReader.class);
        when(reader.read()).thenReturn(new FlagDecision.Disabled());
        whateverItAnswers(new RegisterGenerationJob(new FeatureFlagGate(reader, metrics), store,
                assembler, generation, releaser, metrics, settings(), clock)::run);
    }

    // --- the stale-batch pass --------------------------------------------------------------------

    /**
     * The run's first act, in both of the endings it can write about a batch.
     *
     * <p>One batch given back and one the store could not give back, so all three of the pass's
     * statements are written and all three of its counters move. Every value on those lines is an
     * identity or a count: a released batch names the court centre day it held because that is
     * what was given back, and a contended one is named by its batch identity alone.
     */
    private void theStaleBatchPass() {
        reset(store);
        final ReleasedBatch given = new ReleasedBatch(BATCH_ID, COURT_CENTRE, REGISTER_DATE, 2);
        when(store.failAndReleaseStale(any(), any(), any())).thenAnswer(call -> {
            final StaleReleaseProgress progress = call.getArgument(2, StaleReleaseProgress.class);
            progress.recordReleased(given);
            progress.recordContended(CONTENDED_BATCH_ID);
            return new StaleReleaseOutcome(List.of(given), List.of(CONTENDED_BATCH_ID));
        });
        whateverItAnswers(releaser::releaseStale);
        aStaleBatchPassTheStoreLeftPartWayThrough();
    }

    /**
     * The pass the store went away in the middle of, after a batch had already been given back.
     *
     * <p>Its own line, because the numbers it carries are not a night's: each batch commits by
     * itself, so what the pass was told about before the store stopped answering is committed and
     * has to be said, and said as a part rather than as a whole. Identities and counts again -
     * the line names the three numbers and nothing else, and the refusal that ended the pass is
     * the run's own to report.
     */
    private void aStaleBatchPassTheStoreLeftPartWayThrough() {
        reset(store);
        when(store.failAndReleaseStale(any(), any(), any())).thenAnswer(call -> {
            call.getArgument(2, StaleReleaseProgress.class).recordReleased(
                    new ReleasedBatch(BATCH_ID, COURT_CENTRE, REGISTER_DATE, 2));
            throw new StoreUnavailableException(
                    "the store could not be reached to fail and release the stale batches",
                    new IllegalStateException("the connection was refused"));
        });
        whateverItAnswers(releaser::releaseStale);
    }

    // --- the requesting leg ----------------------------------------------------------------------

    private void theRequestingLeg() {
        aBatchThatHoldsNoRegisters();
        aPayloadTheFileServiceWouldNotTake();
        aRenderTheGeneratorRefusedOutright();
        aRenderNothingAnswered();
        aRenderThatWouldNotFitTheBudget();
        aRenderTheGeneratorAccepted();
        aRenderWhoseOutcomeOvertookItsMark();
        aWaitBetweenAttemptsThatWasInterrupted();
    }

    private void aBatchThatHoldsNoRegisters() {
        reset(store);
        when(store.batched(BATCH_ID)).thenReturn(List.of());
        whateverItAnswers(() -> generation.request(pending(), deadline(), RenderProgress.NONE));
    }

    private void aPayloadTheFileServiceWouldNotTake() {
        aBatchOfOneRegister();
        payloadStoreRefusing();
        whateverItAnswers(() -> generation.request(pending(), deadline(), RenderProgress.NONE));
    }

    private void aRenderTheGeneratorRefusedOutright() {
        aBatchOfOneRegister();
        renderCommandAnswering(HttpStatus.BAD_REQUEST.value());
        whateverItAnswers(() -> generation.request(pending(), deadline(), RenderProgress.NONE));
    }

    private void aRenderNothingAnswered() {
        aBatchOfOneRegister();
        renderCommandFaulting();
        whateverItAnswers(() -> generation.request(pending(), deadline(), RenderProgress.NONE));
    }

    private void aRenderThatWouldNotFitTheBudget() {
        aBatchOfOneRegister();
        renderCommandAnswering(HttpStatus.SERVICE_UNAVAILABLE.value());
        whateverItAnswers(() -> generation.request(pending(), new Deadline(clock.instant()),
                RenderProgress.NONE));
    }

    private void aRenderTheGeneratorAccepted() {
        aBatchOfOneRegister();
        renderCommandAnswering(HttpStatus.ACCEPTED.value());
        whateverItAnswers(() -> generation.request(pending(), deadline(), RenderProgress.NONE));
    }

    /**
     * The render whose document the listener applied before this leg could mark it requested.
     *
     * <p>The store refuses the mark, the leg reads the batch back and finds an outcome there, and
     * says so in a line that names the batch and the state it stands at - both bounded.
     */
    private void aRenderWhoseOutcomeOvertookItsMark() {
        aBatchOfOneRegister();
        renderCommandAnswering(HttpStatus.ACCEPTED.value());
        doThrow(new IllegalStateException("batch " + BATCH_ID
                + " may not move from GENERATED to GENERATING"))
                .when(store).markRequested(any(), any());
        when(store.batchesNamed(any())).thenReturn(List.of(
                batch(BatchStatus.GENERATED, UUID.randomUUID(), UUID.randomUUID())));
        whateverItAnswers(() -> generation.request(pending(), deadline(), RenderProgress.NONE));
    }

    private void aWaitBetweenAttemptsThatWasInterrupted() {
        aBatchOfOneRegister();
        renderCommandAnswering(HttpStatus.SERVICE_UNAVAILABLE.value());
        final RegisterGenerationService interruptible = new RegisterGenerationService(store,
                new uk.gov.hmcts.cp.yotresultsdistribution.pipeline.PdfPayloadMapper(clock),
                payloadFileStore, renderer, MAPPER, retryPolicy(), waited -> {
                    throw new InterruptedException("the run's thread was asked to stop");
                }, metrics, clock);
        whateverItAnswers(() -> interruptible.request(pending(), deadline(), RenderProgress.NONE));
        Thread.interrupted();
    }

    // --- the renderer's client -------------------------------------------------------------------

    private void theRenderersClient() {
        renderCommandAnswering(HttpStatus.OK.value());
        whateverItAnswers(this::askForARender);

        renderCommandAnswering(HttpStatus.SERVICE_UNAVAILABLE.value());
        whateverItAnswers(this::askForARender);

    }

    // --- the topic listener ----------------------------------------------------------------------

    private void theTopicListener() {
        whateverItAnswers(() -> listener.onPublicEvent(unreadableMessage()));
        deliver("public.somebodyelse.events.something-else", "{}");
        deliver(DocumentEventListener.GENERATION_FAILED, "{ not an envelope");
        deliver(DocumentEventListener.GENERATION_FAILED,
                envelope(DocumentEventListener.DOCUMENT_AVAILABLE, generationFailedPayload()));
        deliver(DocumentEventListener.DOCUMENT_AVAILABLE, envelope(
                DocumentEventListener.DOCUMENT_AVAILABLE,
                """
                  "originatingSource": "COURT_REGISTER"
                """));
        deliver(DocumentEventListener.DOCUMENT_AVAILABLE, envelope(
                DocumentEventListener.DOCUMENT_AVAILABLE,
                """
                  "originatingSource": "%s"
                """.formatted(DocumentEventListener.ORIGINATING_SOURCE)));
        deliver(DocumentEventListener.DOCUMENT_AVAILABLE, envelope(
                DocumentEventListener.DOCUMENT_AVAILABLE,
                """
                  "sourceCorrelationId": "%s",
                  "originatingSource": "%s"
                """.formatted(BATCH_ID, DocumentEventListener.ORIGINATING_SOURCE)));
        deliver(DocumentEventListener.DOCUMENT_AVAILABLE, envelope(
                DocumentEventListener.DOCUMENT_AVAILABLE,
                """
                  "sourceCorrelationId": "%s",
                  "payloadFileServiceId": "%s",
                  "originatingSource": "%s"
                """.formatted(BATCH_ID, PAYLOAD_FILE_ID,
                        DocumentEventListener.ORIGINATING_SOURCE)));
        deliver(DocumentEventListener.GENERATION_FAILED, envelope(
                DocumentEventListener.GENERATION_FAILED,
                """
                  "sourceCorrelationId": "%s",
                  "payloadFileServiceId": "%s",
                  "reason": "%s",
                  "originatingSource": "%s"
                """.formatted(BATCH_ID, PAYLOAD_FILE_ID, PersonalDataMarkers.GENERATOR_REASON,
                        DocumentEventListener.ORIGINATING_SOURCE)));
        deliver(DocumentEventListener.DOCUMENT_AVAILABLE, envelope(
                DocumentEventListener.DOCUMENT_AVAILABLE,
                """
                  "sourceCorrelationId": "%s",
                  "payloadFileServiceId": "not an identity",
                  "originatingSource": "%s"
                """.formatted(BATCH_ID, DocumentEventListener.ORIGINATING_SOURCE)));
        deliver(DocumentEventListener.DOCUMENT_AVAILABLE, envelope(
                DocumentEventListener.DOCUMENT_AVAILABLE,
                """
                  "sourceCorrelationId": "%s",
                  "payloadFileServiceId": "%s",
                  "documentFileServiceId": "%s",
                  "generatedTime": "not a date-time",
                  "originatingSource": "%s"
                """.formatted(BATCH_ID, PAYLOAD_FILE_ID, DOCUMENT_FILE_ID,
                        DocumentEventListener.ORIGINATING_SOURCE)));

        reset(batches);
        when(batches.findById(BATCH_ID)).thenThrow(new StoreUnavailableException(
                "the store could not be reached to read a batch",
                new IllegalStateException("the connection pool is empty")));
        deliver(DocumentEventListener.GENERATION_FAILED, envelope(
                DocumentEventListener.GENERATION_FAILED,
                """
                  "sourceCorrelationId": "%s",
                  "payloadFileServiceId": "%s",
                  "failedTime": "2026-03-02T18:06:23.004+00:00",
                  "reason": "%s",
                  "originatingSource": "%s"
                """.formatted(BATCH_ID, PAYLOAD_FILE_ID, PersonalDataMarkers.GENERATOR_REASON,
                        DocumentEventListener.ORIGINATING_SOURCE)));
    }

    // --- the outcome sink ------------------------------------------------------------------------

    private void theOutcomeSink() {
        anOutcomeForABatchNothingHolds();
        anOutcomeAboutAnotherPayload();
        anOutcomeThatArrivedTwice();
        aDocumentForABatchStillGenerated();
        anOutcomeTheStateMachineDoesNotDraw();
        anOutcomeThatClosesTheRoundTrip();
        anOutcomeWhoseRoundTripCouldNotBeRead();
    }

    private void anOutcomeForABatchNothingHolds() {
        reset(batches);
        when(batches.findById(BATCH_ID)).thenReturn(Optional.empty());
        whateverItAnswers(() -> sink.generationFailed(BATCH_ID, PAYLOAD_FILE_ID,
                PersonalDataMarkers.GENERATOR_REASON, AT, CompletedBy.EVENT));
    }

    private void anOutcomeAboutAnotherPayload() {
        holding(batch(BatchStatus.GENERATING, PAYLOAD_FILE_ID, null));
        whateverItAnswers(() -> sink.documentAvailable(BATCH_ID, DOCUMENT_FILE_ID, DOCUMENT_FILE_ID,
                AT, CompletedBy.EVENT));
    }

    private void anOutcomeThatArrivedTwice() {
        holding(batch(BatchStatus.FAILED, PAYLOAD_FILE_ID, null));
        whateverItAnswers(() -> sink.generationFailed(BATCH_ID, PAYLOAD_FILE_ID,
                PersonalDataMarkers.GENERATOR_REASON, AT, CompletedBy.EVENT));
    }

    /**
     * A document announced again for a batch whose notification never finished: not re-stamped,
     * handed on, and said in a line that names the batch and its state only - and, where the
     * second hand-on fails in a way no redelivery clears, acknowledged with a WARN by class and a
     * count under {@code notification-not-retried}.
     */
    private void aDocumentForABatchStillGenerated() {
        holding(batch(BatchStatus.GENERATED, PAYLOAD_FILE_ID, DOCUMENT_FILE_ID));
        when(batches.claimForNotification(any(UUID.class), any(UUID.class)))
                .thenReturn(NotificationClaim.ABSENT);
        whateverItAnswers(() -> sink.documentAvailable(BATCH_ID, PAYLOAD_FILE_ID,
                DOCUMENT_FILE_ID, AT, CompletedBy.EVENT));
    }

    private void anOutcomeTheStateMachineDoesNotDraw() {
        holding(batch(BatchStatus.NOTIFIED, PAYLOAD_FILE_ID, DOCUMENT_FILE_ID));
        whateverItAnswers(() -> sink.generationFailed(BATCH_ID, PAYLOAD_FILE_ID,
                PersonalDataMarkers.GENERATOR_REASON, AT, CompletedBy.EVENT));
    }

    /**
     * The outcome that settles a batch, which is the one that closes a render's round trip.
     *
     * <p>Here so that {@code yotresultsdistribution_generation_latency} has a series for the label sweep to
     * pass over: every other arrangement in this group is an outcome that moves nothing, and a
     * timer nothing recorded is a timer whose labels nothing swept. A refusal rather than a
     * document, because a document hands the batch on to the notifying leg and that leg has its own
     * group below; what is wanted here is the mark and the reading, and nothing after them.
     *
     * <p>The row is answered twice over because the sink reads it twice: GENERATING to decide what
     * the outcome means, and then back again for the two instants the reading is made of.
     */
    private void anOutcomeThatClosesTheRoundTrip() {
        reset(batches, store);
        when(batches.findById(BATCH_ID))
                .thenReturn(Optional.of(batch(BatchStatus.GENERATING, PAYLOAD_FILE_ID, null)))
                .thenReturn(Optional.of(refused()));
        whateverItAnswers(() -> sink.generationFailed(BATCH_ID, PAYLOAD_FILE_ID,
                PersonalDataMarkers.GENERATOR_REASON, AT, CompletedBy.EVENT));
        generatorWordsKept = mockingDetails(store).getInvocations().stream()
                .flatMap(invocation -> Stream.of(invocation.getArguments()))
                .anyMatch(PersonalDataMarkers.GENERATOR_REASON::equals);
    }

    /**
     * The outcome that was applied and could not be timed, which is a line and not a refusal.
     *
     * <p>The reading is taken off the row after the mark, so a store that has gone away between
     * the two refuses it - and the sink absorbs that rather than passing it on, because the outcome
     * was applied and a refusal reaching the listener would roll a delivery back over telemetry.
     * What it writes is the batch and the class of what refused, which is what this sweep is here
     * to hold to identities and bounded codes: a gap in a series has to be diagnosable without a
     * store's words about a row reaching the index.
     */
    private void anOutcomeWhoseRoundTripCouldNotBeRead() {
        reset(batches, store);
        when(batches.findById(BATCH_ID))
                .thenReturn(Optional.of(batch(BatchStatus.GENERATING, PAYLOAD_FILE_ID, null)))
                .thenThrow(new StoreUnavailableException(
                        "the store could not be reached to read a settled batch back",
                        new IllegalStateException("the connection pool is empty")));
        whateverItAnswers(() -> sink.generationFailed(BATCH_ID, PAYLOAD_FILE_ID,
                PersonalDataMarkers.GENERATOR_REASON, AT, CompletedBy.EVENT));
    }

    // --- the notifying leg -----------------------------------------------------------------------

    private void theNotifyingLeg() {
        aBatchSomebodyElseIsTelling();
        aBatchWithNobodyToTell();
        aRecipientWhoseEmailWasAccepted();
        aRecipientWhoseEmailWasRefused();
        aRowAnotherMechanismHadAlreadyMinted();
        aSettlementThatArrivedTooLate();
        aRowTheStoreNoLongerHolds();
        aClaimThatWasTakenOverMidCycle();
        aTallyTheStateMachineDoesNotDraw();
        aBatchThatAlreadyStandsWhereItsTallyPutsIt();
        aWaitBeforeAnotherPostThatWasInterrupted();
    }

    private void aBatchSomebodyElseIsTelling() {
        notifyingOne(NotificationClaim.ALREADY_CLAIMED, BatchStatus.GENERATED);
        whateverItAnswers(() -> notifying.notify(BATCH_ID));
    }

    private void aBatchWithNobodyToTell() {
        notifyingOne(NotificationClaim.CLAIMED, BatchStatus.GENERATED);
        when(store.batched(BATCH_ID)).thenReturn(List.of(register(null)));
        when(notifications.findByBatchId(BATCH_ID)).thenReturn(List.of());
        whateverItAnswers(() -> notifying.notify(BATCH_ID));
    }

    private void aRecipientWhoseEmailWasAccepted() {
        oneRecipientOwedAnEmail(NotificationStatus.PENDING);
        emailCommandAnswering(HttpStatus.ACCEPTED.value());
        whateverItAnswers(() -> notifying.notify(BATCH_ID));
    }

    private void aRecipientWhoseEmailWasRefused() {
        oneRecipientOwedAnEmail(NotificationStatus.PENDING);
        emailCommandAnswering(HttpStatus.SERVICE_UNAVAILABLE.value());
        whateverItAnswers(() -> notifying.notify(BATCH_ID));
    }

    private void aRowAnotherMechanismHadAlreadyMinted() {
        notifyingOne(NotificationClaim.CLAIMED, BatchStatus.GENERATED);
        when(store.batched(BATCH_ID)).thenReturn(List.of(register(List.of(recipient()))));
        when(notifications.findByBatchId(BATCH_ID))
                .thenReturn(List.of())
                .thenReturn(List.of(row(NotificationStatus.PENDING)));
        doRefuseTheInsert();
        emailCommandAnswering(HttpStatus.ACCEPTED.value());
        whateverItAnswers(() -> notifying.notify(BATCH_ID));
    }

    private void aSettlementThatArrivedTooLate() {
        oneRecipientOwedAnEmail(NotificationStatus.PENDING);
        when(notifications.update(any(RegisterNotification.class), anyInt()))
                .thenReturn(NotificationSettlement.ATTEMPTS_ONLY);
        emailCommandAnswering(HttpStatus.ACCEPTED.value());
        whateverItAnswers(() -> notifying.notify(BATCH_ID));

        oneRecipientOwedAnEmail(NotificationStatus.PENDING);
        when(notifications.update(any(RegisterNotification.class), anyInt()))
                .thenReturn(NotificationSettlement.ATTEMPTS_ONLY);
        emailCommandAnswering(HttpStatus.BAD_REQUEST.value());
        whateverItAnswers(() -> notifying.notify(BATCH_ID));
    }

    private void aRowTheStoreNoLongerHolds() {
        oneRecipientOwedAnEmail(NotificationStatus.PENDING);
        when(notifications.update(any(RegisterNotification.class), anyInt()))
                .thenReturn(NotificationSettlement.ABSENT);
        emailCommandAnswering(HttpStatus.ACCEPTED.value());
        whateverItAnswers(() -> notifying.notify(BATCH_ID));
    }

    private void aClaimThatWasTakenOverMidCycle() {
        oneRecipientOwedAnEmail(NotificationStatus.PENDING);
        when(batches.renewNotificationClaim(any(UUID.class), any(UUID.class))).thenReturn(false);
        when(batches.releaseNotificationClaim(any(UUID.class), any(UUID.class)))
                .thenReturn(false);
        when(notifications.tallyAttempts(any(UUID.class), anyInt())).thenReturn(false);
        whateverItAnswers(() -> notifying.notify(BATCH_ID));
    }

    private void aTallyTheStateMachineDoesNotDraw() {
        notifyingOne(NotificationClaim.CLAIMED, BatchStatus.NOTIFIED);
        when(store.batched(BATCH_ID)).thenReturn(List.of(register(null)));
        when(notifications.findByBatchId(BATCH_ID))
                .thenReturn(List.of(row(NotificationStatus.FAILED)));
        whateverItAnswers(() -> notifying.notify(BATCH_ID));
    }

    private void aBatchThatAlreadyStandsWhereItsTallyPutsIt() {
        notifyingOne(NotificationClaim.CLAIMED, BatchStatus.NOTIFIED_NOBODY);
        when(store.batched(BATCH_ID)).thenReturn(List.of(register(null)));
        when(notifications.findByBatchId(BATCH_ID)).thenReturn(List.of());
        whateverItAnswers(() -> notifying.notify(BATCH_ID));
    }

    private void aWaitBeforeAnotherPostThatWasInterrupted() {
        oneRecipientOwedAnEmail(NotificationStatus.PENDING);
        emailCommandAnswering(HttpStatus.SERVICE_UNAVAILABLE.value());
        final RegisterNotifierService interruptible = new RegisterNotifierService(store, batches,
                notifications, notifier, metrics, TEMPLATE_ID, retryPolicy(), waited -> {
                    throw new InterruptedException("the run's thread was asked to stop");
                }, clock);
        whateverItAnswers(() -> interruptible.notify(BATCH_ID));
        Thread.interrupted();
    }

    // --- the notifier's client -------------------------------------------------------------------

    private void theNotifiersClient() {
        emailCommandAnswering(HttpStatus.OK.value());
        whateverItAnswers(this::askForAnEmail);

        emailCommandFaulting();
        whateverItAnswers(this::askForAnEmail);
    }

    // --- arrangements ----------------------------------------------------------------------------

    private void readyToGenerate() {
        reset(store, batches, assembler, gate, payloadFileStore);
        when(gate.decide(false)).thenReturn(new GateDecision.Proceed(false));
        when(store.activeUnbatched()).thenReturn(List.of(register(List.of(recipient()))));
        when(store.batched(BATCH_ID)).thenReturn(List.of(register(List.of(recipient()))));
        when(assembler.assemble(anyList(), anyList(), anyBoolean())).thenReturn(new BatchAssembly(
                List.of(new AssembledBatch(pending(), List.of(register(List.of(recipient()))))),
                List.of(new CourtCentreDay(COURT_CENTRE, REGISTER_DATE))));
        // The run's first act, on the ordinary night: nothing was left in flight, so the pass
        // gives nothing back and every arrangement above is about what it says it is about. A
        // store that answered nothing here would end the run inside its first statement, and the
        // lines these nights exist to reach would never be written. The pass's own endings are
        // driven by theStaleBatchPass(), which answers with both of them.
        when(store.failAndReleaseStale(any(Instant.class), any(Instant.class),
                any(StaleReleaseProgress.class)))
                .thenReturn(new StaleReleaseOutcome(List.of(), List.of()));
        when(batches.generatingSince(any(Instant.class))).thenReturn(List.of());
        when(batches.pendingSince(any(Instant.class))).thenReturn(List.of());
        when(batches.generatedSince(any(Instant.class))).thenReturn(List.of());
    }

    private void aBatchOfOneRegister() {
        reset(store, payloadFileStore);
        when(store.batched(BATCH_ID)).thenReturn(List.of(register(List.of(recipient()))));
    }

    private void payloadStoreRefusing() {
        doRefuseThePayload();
    }

    private void notifyingOne(final NotificationClaim claim, final BatchStatus status) {
        reset(store, batches, notifications);
        when(batches.claimForNotification(any(UUID.class), any(UUID.class))).thenReturn(claim);
        when(batches.renewNotificationClaim(any(UUID.class), any(UUID.class))).thenReturn(true);
        when(batches.releaseNotificationClaim(any(UUID.class), any(UUID.class))).thenReturn(true);
        when(batches.findById(BATCH_ID))
                .thenReturn(Optional.of(batch(status, PAYLOAD_FILE_ID, DOCUMENT_FILE_ID)));
        when(notifications.update(any(RegisterNotification.class), anyInt()))
                .thenReturn(NotificationSettlement.APPLIED);
        when(notifications.tallyAttempts(any(UUID.class), anyInt())).thenReturn(true);
    }

    private void oneRecipientOwedAnEmail(final NotificationStatus status) {
        notifyingOne(NotificationClaim.CLAIMED, BatchStatus.GENERATED);
        when(store.batched(BATCH_ID)).thenReturn(List.of(register(List.of(recipient()))));
        when(notifications.findByBatchId(BATCH_ID)).thenReturn(List.of(row(status)));
    }

    private void holding(final RegisterBatch batch) {
        reset(batches, store);
        when(batches.findById(BATCH_ID)).thenReturn(Optional.of(batch));
        when(batches.compareAndSet(any(RegisterBatch.class), any(BatchStatus.class)))
                .thenReturn(true);
    }

    // --- the far ends ----------------------------------------------------------------------------

    private void renderCommandAnswering(final int status) {
        contexts.resetAll();
        contexts.stubFor(post(urlEqualTo(ANY_RENDER_COMMAND))
                .willReturn(aResponse().withStatus(status)));
    }

    private void renderCommandFaulting() {
        contexts.resetAll();
        contexts.stubFor(post(urlEqualTo(ANY_RENDER_COMMAND))
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));
    }

    private void emailCommandAnswering(final int status) {
        contexts.resetAll();
        contexts.stubFor(post(urlPathMatching(ANY_EMAIL_COMMAND))
                .willReturn(aResponse().withStatus(status)));
    }

    private void emailCommandFaulting() {
        contexts.resetAll();
        contexts.stubFor(post(urlPathMatching(ANY_EMAIL_COMMAND))
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));
    }

    // --- the calls -------------------------------------------------------------------------------

    private void askForARender() {
        renderer.requestRender(
                new uk.gov.hmcts.cp.yotresultsdistribution.domain.RenderRequest(PAYLOAD_FILE_ID, BATCH_ID,
                        "OEE_Layout5", "pdf", DocumentEventListener.ORIGINATING_SOURCE),
                uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity.SYSTEM);
    }

    private void askForAnEmail() {
        notifier.send(row(NotificationStatus.PENDING), DOCUMENT_FILE_ID,
                uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity.SYSTEM);
    }

    private void deliver(final String eventName, final String body) {
        whateverItAnswers(() -> listener.onPublicEvent(message(eventName, body)));
    }

    // --- the morning's report ---------------------------------------------------------------------

    /**
     * One morning with an exception of every kind, delivered to the log and to a sink that breaks.
     *
     * <p>A single arrangement rather than several, because the report's two classes write three
     * lines between them and all three are about one report: the summary, one line per entry, and
     * the one the service leaves behind when a sink could not be handed the report at all. The
     * entries cover all five kinds, and within {@code BATCH_LATE} all four stages, so the
     * per-entry line is written over every shape of entry there is - including the two that carry
     * no batch and the three that carry no request.
     *
     * <p><strong>The sink that breaks carries markers in its message</strong>, and that is the
     * whole point of driving it. The rule is that a caught failure is named by its class and never
     * by its message, because the message belongs to whoever raised it; a sink whose failure said
     * nothing about anybody would leave that rule asserted against a string that could not have
     * leaked in the first place.
     *
     * <p>The report reads and writes nothing of its own, so its reads are the doubles the two legs
     * above already hold, answering one row apiece.
     */
    private void theExceptionReport() {
        when(requestLog.failedBetween(any(), any())).thenReturn(List.of(aParkedRequest()));
        when(requestLog.nonTerminalOlderThan(any())).thenReturn(List.of(anUnfinishedRequest()));
        when(batches.latePending(any())).thenReturn(List.of(aLateBatch(BatchStatus.PENDING)));
        when(batches.lateGenerating(any())).thenReturn(List.of(aLateBatch(BatchStatus.GENERATING)));
        when(batches.lateGenerated(any())).thenReturn(List.of(aLateBatch(BatchStatus.GENERATED)));
        when(batches.failedBetween(any(), any())).thenReturn(List.of(aDeadBatch()));
        when(store.recordedUnbatchedBefore(any())).thenReturn(List.of(aStrandedRegister()));
        when(notifications.failedBetween(any(), any())).thenReturn(List.of(aRefusedNotification()));

        final ExceptionReport report =
                reporting.build(new ReportWindow(AT.minus(Duration.ofDays(1)), AT), RUN_ID);
        whateverItAnswers(() -> reporting.deliver(report, List.of(logSink, aSinkThatBreaks())));

        aMorningTooBadToCarry();
    }

    /**
     * The morning the entry cap reached, which is the one line the service writes about itself.
     *
     * <p>Over the same eight reads, through a service of its own capped at one, so the arrangement
     * above is left as the whole morning it is. What the line has to say is two numbers and the
     * run's correlation and nothing else: a cap that named the exceptions it dropped would be
     * writing the very lines the cap exists to stop it writing.
     */
    private void aMorningTooBadToCarry() {
        final ExceptionReportService capped = new ExceptionReportService(requestLog, batches,
                notifications, store, REPORT_LIMIT, REPORT_LIMIT, REPORT_LIMIT, ONE_ENTRY,
                GENERATION_CRON, GenerationProperties.COURTS_ZONE, intakeMetrics, clock);
        whateverItAnswers(() ->
                capped.build(new ReportWindow(AT.minus(Duration.ofDays(1)), AT), RUN_ID));
    }

    /** A request the intake half parked, carrying the bounded reason it was parked under. */
    private static ProcessedRequestSummary aParkedRequest() {
        return new ProcessedRequestSummary(SOURCE, REQUEST_ID, HEARING_ID, REGISTER_DATE,
                RequestStatus.FAILED, MAX_ATTEMPTS, DeadLetterReason.VALIDATION.label(), AT, AT,
                A_REQUESTS_AGE);
    }

    /** A request that arrived and has reached no terminal state since, which carries no reason. */
    private static ProcessedRequestSummary anUnfinishedRequest() {
        return new ProcessedRequestSummary(SOURCE, UNFINISHED_REQUEST_ID, HEARING_ID, REGISTER_DATE,
                RequestStatus.RECEIVED, 1, null, AT, AT, A_REQUESTS_AGE);
    }

    /** A batch still at one of its three stages long after it should have left it. */
    private static BatchException aLateBatch(final BatchStatus status) {
        return new BatchException(BATCH_ID, COURT_CENTRE, REGISTER_DATE, status, null, 1,
                A_BATCHS_AGE);
    }

    /** A batch that ended, named by its own bounded reason and never by the generator's words. */
    private static BatchException aDeadBatch() {
        return new BatchException(BATCH_ID, COURT_CENTRE, REGISTER_DATE, BatchStatus.FAILED,
                BatchFailureReason.GENERATION_FAILED, 1, A_BATCHS_AGE);
    }

    /** A register the last scheduled run left where it was, which has no batch to be named by. */
    private static RecordedRegisterSummary aStrandedRegister() {
        return new RecordedRegisterSummary(UUID.randomUUID(), HEARING_ID, COURT_CENTRE,
                REGISTER_DATE, AT, A_REGISTERS_AGE);
    }

    /** One team's e-mail that was refused, named by its identity and by no address. */
    private static FailedNotification aRefusedNotification() {
        return new FailedNotification(NOTIFICATION_ID, BATCH_ID, COURT_CENTRE, REGISTER_DATE,
                NotificationStatus.FAILED, REFUSED_STATUS, MAX_ATTEMPTS, AT, A_BATCHS_AGE);
    }

    /**
     * The 07:00 run's two lines: the one every morning leaves, and the one a morning that could
     * not read its own store leaves instead.
     *
     * <p>The first is written over the arrangement {@link #theExceptionReport()} has just made, so
     * it describes a real report delivered to a real sink rather than an empty one - the counts and
     * the window on it are the ones the eight reads produced. The second is the branch that makes
     * this job the opposite of the sweep below: the read is the report, so its refusal leaves, and
     * what the run says on the way out has to name the cause by class and repeat none of its words.
     */
    private void theMorningRun() {
        whateverItAnswers(reportJob::run);

        when(requestLog.failedBetween(any(), any())).thenThrow(new StoreUnavailableException(
                "the store could not be reached to read what went wrong overnight",
                new IllegalStateException("the connection pool is empty")));
        whateverItAnswers(reportJob::run);
    }

    /**
     * The five lines the report's e-mail sink can write, over one morning it is given four times.
     *
     * <p>The sink is the one class in this increment that has a <em>list</em> of addresses in its
     * hands, and every line it writes about one of them has to be masked - so the arrangements
     * below hand it the marked address and the sweep reads what came out. It is driven over the
     * report {@link #theExceptionReport()} has just built, so the counts in the body and the rows
     * in the file are a real morning's.
     *
     * <p>Four arrangements for five lines: a recipient who took it (the minted id, and the line
     * about the recipient), one who refused it, a file service that would not take the attachment -
     * where nobody is told at all, because an e-mail whose attachment is missing is an e-mail with
     * nothing in it - and a recipient list that is empty under a running pod.
     */
    private void theReportsEmail() {
        final ExceptionReport report =
                reporting.build(new ReportWindow(AT.minus(Duration.ofDays(1)), AT), RUN_ID);

        emailCommandAnswering(HttpStatus.ACCEPTED.value());
        whateverItAnswers(() -> emailSink(List.of(PersonalDataMarkers.RECIPIENT_EMAIL))
                .deliver(report));

        emailCommandAnswering(HttpStatus.BAD_REQUEST.value());
        whateverItAnswers(() -> emailSink(List.of(PersonalDataMarkers.RECIPIENT_EMAIL))
                .deliver(report));

        doRefuseTheText();
        whateverItAnswers(() -> emailSink(List.of(PersonalDataMarkers.RECIPIENT_EMAIL))
                .deliver(report));

        reset(payloadFileStore);
        whateverItAnswers(() -> emailSink(List.of()).deliver(report));

        emailCommandAnswering(HttpStatus.ACCEPTED.value());
        whateverItAnswers(() -> new EmailReportSink(payloadFileStore, aMailerThatBreaks(),
                TEMPLATE_ID, List.of(PersonalDataMarkers.RECIPIENT_EMAIL)).deliver(report));
    }

    /**
     * A mailer that throws rather than answering, which is a port that has broken its contract.
     *
     * <p>Its refusal names a team and an address on purpose, for the reason the sink that breaks
     * does: the rule is that a caught failure is named by class and never by message, and a failure
     * whose message said nothing about anybody would leave that rule asserted against a string that
     * could not have leaked in the first place.
     *
     * @return a mailer that throws
     */
    private static ReportMailer aMailerThatBreaks() {
        return mail -> {
            throw new IllegalStateException("the mail client refused to build a request for "
                    + PersonalDataMarkers.RECIPIENT_ORGANISATION + " at "
                    + PersonalDataMarkers.RECIPIENT_EMAIL);
        };
    }

    /**
     * The sink over this fixture's file store and its real mailer.
     *
     * @param recipients who this deployment is configured to tell
     * @return the sink
     */
    private EmailReportSink emailSink(final List<String> recipients) {
        return new EmailReportSink(payloadFileStore, reportMailer, TEMPLATE_ID, recipients);
    }

    /** A file service that will not take the CSV, in its own words and with a cause of its own. */
    private void doRefuseTheText() {
        doThrow(new PayloadStoreUnavailableException(
                        "the file service could not be reached to write the report's content row"))
                .when(payloadFileStore).storeText(any(UUID.class), any(String.class),
                        any(uk.gov.hmcts.cp.yotresultsdistribution.application.PayloadMetadata.class));
    }

    /**
     * The three lines the report's mailer can write, over three answers a far end really gives.
     *
     * <p>One of the two classes in this increment that holds an address, and the arrangements below
     * hand it a marked one on purpose: what this sweep is for is the claim that the address it was
     * given reaches no line of its own accord. Nothing here masks it - the mailer names the
     * notification, the file and a status code and nothing else, and the masking lives in the sink
     * above it, where the list of addresses is.
     *
     * <p>A refusal, a retryable answer and an answer that never came: the first two carry a status
     * line this service did not write, and the third carries a cause whose message names the host
     * it could not reach. All three are lines the sweep holds to identifiers and bounded codes.
     */
    private void theReportsMailer() {
        emailCommandAnswering(HttpStatus.BAD_REQUEST.value());
        whateverItAnswers(() -> reportMailer.send(reportMail()));

        emailCommandAnswering(HttpStatus.SERVICE_UNAVAILABLE.value());
        whateverItAnswers(() -> reportMailer.send(reportMail()));

        emailCommandFaulting();
        whateverItAnswers(() -> reportMailer.send(reportMail()));
    }

    /**
     * One report e-mail, addressed to a marker and carrying the five counts and the window.
     *
     * @return the mail the arrangements above send
     */
    private static ReportMail reportMail() {
        return new ReportMail(UUID.randomUUID(), TEMPLATE_ID, PersonalDataMarkers.RECIPIENT_EMAIL,
                UUID.randomUUID(), Map.of(
                        "request_failed", "1",
                        "request_late", "0",
                        "batch_late", "0",
                        "batch_failed", "1",
                        "notification_failed", "0",
                        "window_from", "2026-09-14T06:00:00Z",
                        "window_to", "2026-09-15T06:00:00Z"));
    }

    /**
     * The one line the seventh operations endpoint writes, which is about a report nobody got.
     *
     * <p>Driven immediately after {@link #theMorningRun()}, which leaves the request log refusing:
     * a report whose read will not answer is the one thing this service has to say out loud,
     * because the caller is answered a bounded code and there is nothing in it to read. The store's
     * own words and the cause it carries are exactly what the sweep holds this line to a class name
     * over.
     *
     * <p>What the endpoint answers goes nowhere here. That is
     * {@code OnDemandExceptionReportServiceTest}'s and {@code ExceptionReportsControllerTest}'s
     * claim; this fixture's subject is the log, which reaches the index the whole estate reads.
     */
    private void theOnDemandReport() {
        whateverItAnswers(() -> onDemand(false).report("2h", false));
        whateverItAnswers(() -> onDemand(false).report("2h", true));
        whateverItAnswers(() -> onDemand(true).report("2h", true));
        whateverItAnswers(() -> onDemand(false).report("not-a-window", false));
    }

    /**
     * The on-demand report over this fixture's reads and its one sink.
     *
     * <p>Built per invocation rather than held as a field because the e-mail switch is what tells
     * its two refusals apart, and both of them have a line of their own: the output switched off is
     * a deploy to fix, the output on with no sink behind it is this context holding one sink and
     * not two. The four drives above are the four things this service says out loud - the report it
     * could not produce, and those two, and a window an operator typed that will not read.
     *
     * @param emailEnabled whether the e-mail output is switched on at all
     * @return the report service, over the log sink this fixture already sweeps
     */
    private OnDemandExceptionReportService onDemand(final boolean emailEnabled) {
        return new OnDemandExceptionReportService(reporting, List.of(logSink), REPORT_CRON,
                GenerationProperties.COURTS_ZONE, emailEnabled, clock);
    }

    /**
     * The one refusal this service absorbs, and the two WARN lines that make it visible.
     *
     * <p>Driven last because it leaves the request log refusing, and because these are the only
     * lines the sweep can write: everything else it does is two gauges moving, which no capture
     * sees. The refusal carries the store's own words and a cause of its own, both of which the
     * sweep must keep out of the log - a caught exception's message belongs to whatever raised it.
     *
     * <p><strong>Both arms, because the sweep has two and they mean different things.</strong> An
     * outage of theirs and a bug of ours are counted under two bounded reasons precisely so one
     * cannot hide inside the other, and the arm that was never driven was the one whose reason no
     * sweep had ever read - so nothing here said whether {@code unexpected} was a word this service
     * is allowed to write into a reason slot at all.
     */
    private void theIntakeGaugeRefresh() {
        when(requestLog.oldestNonTerminal())
                .thenThrow(new StoreUnavailableException(
                        "the store could not be reached to read the oldest unfinished request",
                        new IllegalStateException("the connection pool is empty")))
                .thenThrow(new IllegalStateException(
                        "the gauge refresh met something nobody classified, about "
                                + PersonalDataMarkers.CHILD_NAME));
        whateverItAnswers(sweep::sweepScheduled);
        whateverItAnswers(sweep::sweepScheduled);
    }

    /**
     * The generation half's own sweep: a batch nobody was told about, and both absorbed refusals.
     *
     * <p>Three lines and no more, which is the whole of what this sweep can write. The first is
     * the WARN about a batch parked at GENERATED - a document that exists and Youth Offending
     * Teams who have not been sent it - and it may carry an identity, a stamp and a count and
     * nothing else. The other two are the two arms of the one refusal this service absorbs, driven
     * both ways round for the reason the intake sweep's are: an outage of theirs and a bug of ours
     * are counted under two bounded reasons precisely so that one cannot hide inside the other,
     * and an arm nothing drives is a reason no sweep has ever read.
     *
     * <p>Driven after the intake refresh because it leaves the batch reads refusing. Everything
     * else the sweep does is three gauges moving, which no log capture sees and which this class
     * reaches anyway - the meters are swept by name.
     */
    private void theBatchAgeRefresh() {
        reset(batches);
        when(batches.generatingSince(any(Instant.class))).thenReturn(List.of());
        when(batches.pendingSince(any(Instant.class))).thenReturn(List.of());
        when(batches.generatedSince(any(Instant.class)))
                .thenReturn(List.of(batch(BatchStatus.GENERATED, PAYLOAD_FILE_ID,
                        DOCUMENT_FILE_ID)));
        whateverItAnswers(batchSweep::sweepScheduled);

        when(batches.generatingSince(any(Instant.class)))
                .thenThrow(new StoreUnavailableException(
                        "the store could not be reached to read the oldest batch awaiting a render",
                        new IllegalStateException("the connection pool is empty")))
                .thenThrow(new IllegalStateException(
                        "the batch-age refresh met something nobody classified, about "
                                + PersonalDataMarkers.CHILD_NAME));
        whateverItAnswers(batchSweep::sweepScheduled);
        whateverItAnswers(batchSweep::sweepScheduled);
    }

    /**
     * A sink that cannot take the report, and whose refusal names a team and an address.
     *
     * <p>The second sink of the pair on purpose: the first has to still be asked, and what the
     * service says about this one has to name its class and nothing it wrote.
     *
     * @return a sink that throws rather than answering
     */
    private static ExceptionReportSink aSinkThatBreaks() {
        return new ExceptionReportSink() {
            @Override
            public ReportSinkName name() {
                return ReportSinkName.EMAIL;
            }

            @Override
            public DeliveryOutcome deliver(final ExceptionReport report) {
                throw new IllegalStateException("the mail context refused the report addressed to "
                        + PersonalDataMarkers.RECIPIENT_ORGANISATION + " at "
                        + PersonalDataMarkers.RECIPIENT_EMAIL);
            }
        };
    }

    /**
     * Runs one arrangement and lets it answer however it answers.
     *
     * <p>Not a swallowed failure. Most of the arrangements above are the leg's failure paths, and a
     * refusal reaching the caller is what makes the line under sweep happen; what each of them
     * <em>answers</em> is owned and asserted by the eight suites for the eight classes, and this
     * fixture's whole subject is what they said out loud on the way. A drive that stopped at the
     * first refusal would cover the first line and none after it.
     *
     * @param arrangement the call to make
     */
    // PMD.AvoidCatchingGenericException / PMD.EmptyCatchBlock: see above - the answer is another
    // suite's assertion, and the capture is this one's.
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.EmptyCatchBlock"})
    private static void whateverItAnswers(final Runnable arrangement) {
        try {
            arrangement.run();
        } catch (RuntimeException answered) {
            // Deliberately not read: see the method's javadoc.
        }
    }

    // --- fixtures --------------------------------------------------------------------------------

    private static RestClient restClientFor(final String baseUrl) {
        final SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    private static uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy retryPolicy() {
        return new uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy(
                MAX_ATTEMPTS, BACKOFF, BACKOFF, CONNECT_TIMEOUT.plus(READ_TIMEOUT));
    }

    private static GenerationProperties settings() {
        return new GenerationProperties(true, GENERATION_CRON, GenerationProperties.COURTS_ZONE,
                false,
                RUN_DEADLINE, RUN_DEADLINE.plusMinutes(10), STALE_AFTER,
                BATCH_AGE_REFRESH,
                GenerationProperties.SourceMode.LIVE, GenerationProperties.SourceMode.LIVE,
                GenerationProperties.SourceMode.LIVE, GenerationProperties.SourceMode.LIVE);
    }

    private DocumentOutcomeSinkImpl sinkOf() {
        return new DocumentOutcomeSinkImpl(store, batches, metrics, notifying);
    }

    private Deadline deadline() {
        return Deadline.startingAt(clock.instant(), RUN_DEADLINE);
    }

    private static RegisterBatch pending() {
        return batch(BatchStatus.PENDING, null, null);
    }

    private static RegisterBatch batch(final BatchStatus status, final UUID payloadFileId,
            final UUID documentFileId) {

        return new RegisterBatch(BATCH_ID, COURT_CENTRE, OU_CODE, COURT_HOUSE, REGISTER_DATE,
                FILE_NAME, payloadFileId, documentFileId, status, null, null, true, null, AT, AT,
                null, null, null, 1, null, 0);
    }

    /**
     * The row a refusal leaves behind: requested, and stamped as failed a minute and a half later.
     *
     * <p>The only arrangement in this class that carries an outcome stamp, because it is the only
     * one whose reading is a round trip rather than a state.
     *
     * @return the batch as the read after the mark answers it
     */
    private static RegisterBatch refused() {
        return new RegisterBatch(BATCH_ID, COURT_CENTRE, OU_CODE, COURT_HOUSE, REGISTER_DATE,
                FILE_NAME, PAYLOAD_FILE_ID, null, BatchStatus.FAILED,
                BatchFailureReason.GENERATION_FAILED, PersonalDataMarkers.GENERATOR_REASON, true,
                CompletedBy.EVENT, AT, AT, null, null, AT.plusSeconds(90), 1, null, 0);
    }

    /** One recorded register addressed to the given teams, about a child made of markers. */
    private static RegisterRecord register(final List<CourtRegisterRecipient> recipients) {
        final UUID hearingId = UUID.randomUUID();
        return new RegisterRecord(UUID.randomUUID(), hearingId, AT,
                new CourtCentreDay(COURT_CENTRE, REGISTER_DATE), AT, FILE_NAME, "Applicant",
                RecordedFlagState.ON,
                new CourtRegisterDocument("2026-03-02T18:04:00Z", "2026-03-02T09:00:00Z",
                        hearingId.toString(), COURT_CENTRE.toString(), FILE_NAME, "Applicant", null,
                        recipients,
                        List.of(new CourtRegisterDefendant(UUID.randomUUID().toString(),
                                PersonalDataMarkers.CHILD_NAME, PersonalDataMarkers.DATE_OF_BIRTH,
                                null, null, null, null, null, null, null, null,
                                List.of(new CourtRegisterCaseOrApplication("30GD1234521", null,
                                        null, null, null, null, null)),
                                null, null))));
    }

    /** The team the register goes to, made entirely of markers. */
    private static CourtRegisterRecipient recipient() {
        return new CourtRegisterRecipient(PersonalDataMarkers.RECIPIENT_ORGANISATION,
                PersonalDataMarkers.RECIPIENT_EMAIL, null, RegisterNotifierService.TEMPLATE_NAME);
    }

    /** One recipient's row, under the identity a suite looks for in the capture. */
    private static RegisterNotification row(final NotificationStatus status) {
        return new RegisterNotification(NOTIFICATION_ID, BATCH_ID,
                PersonalDataMarkers.RECIPIENT_EMAIL, PersonalDataMarkers.RECIPIENT_ORGANISATION,
                RegisterNotifierService.TEMPLATE_NAME, TEMPLATE_ID, status, null, null, 0);
    }

    private void doRefuseThePayload() {
        doThrow(new PayloadStoreUnavailableException(
                        "the file service could not be reached to write the payload's content row"))
                .when(payloadFileStore).store(any(UUID.class),
                        any(tools.jackson.databind.JsonNode.class),
                        any(uk.gov.hmcts.cp.yotresultsdistribution.application.PayloadMetadata.class));
    }

    private void doRefuseTheInsert() {
        doThrow(new StoreRefusedRowException(
                        "a notification row for this batch and address is already held"))
                .when(notifications).insert(any(RegisterNotification.class));
    }

    private static String generationFailedPayload() {
        return """
                  "sourceCorrelationId": "%s",
                  "payloadFileServiceId": "%s",
                  "reason": "%s",
                  "originatingSource": "%s"
                """.formatted(BATCH_ID, PAYLOAD_FILE_ID, PersonalDataMarkers.GENERATOR_REASON,
                DocumentEventListener.ORIGINATING_SOURCE);
    }

    /** A framework {@code JsonEnvelope} carrying the given payload members. */
    private static String envelope(final String name, final String members) {
        return """
                {
                  "_metadata": {
                    "id": "3d7e5b21-6a04-4c19-8f52-71b0d9e3a4c8",
                    "name": "%s",
                    "createdAt": "2026-03-02T18:06:23.100Z",
                    "source": "systemdocgenerator",
                    "stream": {"id": "%s"},
                    "correlation": {"client": "systemdocgenerator"}
                  },
                %s}""".formatted(name, BATCH_ID, members);
    }

    private static TextMessage message(final String eventName, final String body) {
        try {
            final TextMessage message = mock(TextMessage.class);
            when(message.getStringProperty(DocumentEventListener.EVENT_NAME_PROPERTY))
                    .thenReturn(eventName);
            when(message.getText()).thenReturn(body);
            return message;
        } catch (JMSException never) {
            throw new IllegalStateException("a doubled message throws nothing", never);
        }
    }

    /** A delivery the broker cannot hand over, which is the listener's first refusal. */
    private static TextMessage unreadableMessage() {
        try {
            final TextMessage message = mock(TextMessage.class);
            when(message.getStringProperty(DocumentEventListener.EVENT_NAME_PROPERTY))
                    .thenThrow(new JMSException("the broker could not hand the message over"));
            return message;
        } catch (JMSException never) {
            throw new IllegalStateException("a doubled message throws nothing", never);
        }
    }
}
