package uk.gov.hmcts.cp.yotresultsdistribution.e2e;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.yotresultsdistribution.application.BatchOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.application.DocumentOutcomeSinkImpl;
import uk.gov.hmcts.cp.yotresultsdistribution.application.DocumentRenderer;
import uk.gov.hmcts.cp.yotresultsdistribution.application.PayloadFileStore;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterGenerationService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterNotifierService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RenderProgress;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletedBy;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterHearingVenue;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.Deadline;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GenerationFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GuardDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterRecord;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestFingerprint;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.JdbcRegisterStore;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.pipeline.PdfPayloadMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;

/**
 * The outcome that reaches a batch before the requesting leg's own mark does, over the real store.
 *
 * <p>Audit finding F-01, and the other half of defect fix P5: one court centre's trouble is not a
 * night's. The public-event listener runs beside the 18:00 run on every pod, so a render
 * systemdocgenerator finishes quickly - or a request whose 202 was lost while the leg retries - can
 * be marked GENERATED, or FAILED, by the listener before the leg writes GENERATING. The state
 * machine then refuses the leg's mark, and before this fix that refusal ended the whole run.
 *
 * <p><strong>Real store, real sink, real service.</strong> Testcontainers Postgres with the
 * committed migrations, this service's own {@code JdbcRegisterStore}, {@code DocumentOutcomeSinkImpl}
 * and {@code RegisterGenerationService}. The renderer is the one stand-in that matters: it applies
 * the outcome through the sink <em>before</em> it answers, which is the ordering the race is about.
 * The refusal under test is therefore the store's own - {@code permitted()} or the fenced update -
 * and not a mock's guess at it. The payload mapper and the file service are stood in for because
 * neither is what the race is about.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("an outcome that reaches the batch before the requesting leg's own mark")
class OutcomeOvertakesRequestIT {

    /** The first day a case may use; each case takes one of its own, away from other suites'. */
    private static final Instant FIRST_DAY = Instant.parse("2025-11-03T16:30:00Z");

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static final AtomicInteger DAYS_TAKEN = new AtomicInteger();

    private static final String OU_CODE = "B01LY00";

    private static final String APPLICANT = "Lavender Hill Youth Court";

    private static final Duration LEASE = Duration.ofMinutes(5);

    /** Three attempts and no wait between them, so a lost 202 is retried inside the case. */
    private static final RetryPolicy POLICY =
            new RetryPolicy(3, Duration.ZERO, Duration.ZERO, Duration.ofMillis(1));

    private static final String SDG_REASON = "template OEE_Layout5 rendered no pages";

    private final Instant shared = FIRST_DAY.plus(Duration.ofDays(DAYS_TAKEN.getAndIncrement()));

    private final LocalDate day = LocalDate.ofInstant(shared, LONDON);

    private final UUID courtCentre = UUID.randomUUID();

    private final Clock clock = Clock.fixed(shared, ZoneOffset.UTC);

    private final ObjectMapper objectMapper = JacksonConfig.contractObjectMapper();

    private final JdbcRegisterStore store = new JdbcRegisterStore(
            ProcessedLogTestSupport.jdbcClient(), ProcessedLogTestSupport.transactionManager());

    private final RegisterBatchRepository batches = new RegisterBatchRepository(
            ProcessedLogTestSupport.jdbcClient(), ProcessedLogTestSupport.transactions(), LEASE);

    private final GenerationMetrics metrics = new GenerationMetrics(new SimpleMeterRegistry());

    private final DocumentOutcomeSinkImpl sink = new DocumentOutcomeSinkImpl(store, batches,
            metrics, mock(RegisterNotifierService.class));

    private final UUID documentFileId = UUID.randomUUID();

    @InjectSoftAssertions
    private SoftAssertions softly;

    @BeforeAll
    static void migrate() {
        PostgresTestSupport.applyFlyway();
    }

    @Test
    void a_document_announced_before_the_202_should_leave_the_batch_generated_and_the_run_going() {
        final RegisterBatch batch = aPendingBatch();
        final DocumentRenderer renderer = (request, caller) ->
                sink.documentAvailable(request.batchId(), request.payloadFileId(), documentFileId,
                        shared, CompletedBy.EVENT);

        final AtomicReference<BatchOutcome> answered = new AtomicReference<>();
        softly.assertThatCode(() -> answered.set(request(batch, renderer)))
                .as("the listener marking the batch first is the normal path at 18:00, not a "
                        + "failure, and it must not end the night for every court centre behind it")
                .doesNotThrowAnyException();

        softly.assertThat(answered.get())
                .as("answered as the accepted request it was")
                .isEqualTo(new BatchOutcome(batch.batchId(), BatchStatus.GENERATING, null, true));
        softly.assertThat(batches.findById(batch.batchId()))
                .as("and the batch is where the document left it, not moved back to GENERATING")
                .hasValueSatisfying(stored -> {
                    softly.assertThat(stored.status()).isEqualTo(BatchStatus.GENERATED);
                    softly.assertThat(stored.documentFileId()).isEqualTo(documentFileId);
                });
    }

    @Test
    void a_generation_failure_announced_before_the_202_should_keep_its_reason_and_the_run_going() {
        final RegisterBatch batch = aPendingBatch();
        final DocumentRenderer renderer = (request, caller) ->
                sink.generationFailed(request.batchId(), request.payloadFileId(), SDG_REASON,
                        shared, CompletedBy.EVENT);

        final AtomicReference<BatchOutcome> answered = new AtomicReference<>();
        softly.assertThatCode(() -> answered.set(request(batch, renderer)))
                .as("generation-failed is an outcome too, and arrives on the same listener")
                .doesNotThrowAnyException();

        softly.assertThat(answered.get())
                .isEqualTo(new BatchOutcome(batch.batchId(), BatchStatus.GENERATING, null, true));
        softly.assertThat(batches.findById(batch.batchId()))
                .as("systemdocgenerator's verdict stands; the requesting leg invents none over it")
                .hasValueSatisfying(stored -> {
                    softly.assertThat(stored.status()).isEqualTo(BatchStatus.FAILED);
                    softly.assertThat(stored.failureReason())
                            .isEqualTo(BatchFailureReason.GENERATION_FAILED);
                });
    }

    @Test
    void a_document_announced_while_a_lost_202_was_retried_should_not_be_failed_over() {
        final RegisterBatch batch = aPendingBatch();
        final AtomicInteger attempts = new AtomicInteger();
        final DocumentRenderer renderer = (request, caller) -> {
            if (attempts.getAndIncrement() == 0) {
                // Accepted, rendered and announced - and the 202 never reached this service.
                sink.documentAvailable(request.batchId(), request.payloadFileId(), documentFileId,
                        shared, CompletedBy.EVENT);
            }
            throw new GenerationFailedException(FailureClassification.TRANSIENT,
                    BatchFailureReason.RENDER_REQUEST_FAILED);
        };

        final AtomicReference<BatchOutcome> answered = new AtomicReference<>();
        softly.assertThatCode(() -> answered.set(request(batch, renderer)))
                .as("the retries run out on a batch that already has its document; failing it "
                        + "is refused by the store, and that refusal is not the night's")
                .doesNotThrowAnyException();

        softly.assertThat(answered.get())
                .isEqualTo(new BatchOutcome(batch.batchId(), BatchStatus.GENERATING, null, true));
        softly.assertThat(batches.findById(batch.batchId()))
                .as("a batch with a document is never failed over it")
                .hasValueSatisfying(stored ->
                        softly.assertThat(stored.status()).isEqualTo(BatchStatus.GENERATED));
    }

    /**
     * Asks for one batch's render, through the real service over the real store.
     *
     * @param batch    the batch, durable at PENDING
     * @param renderer what systemdocgenerator does with the request
     * @return what the requesting leg answered
     */
    private BatchOutcome request(final RegisterBatch batch, final DocumentRenderer renderer) {
        final PdfPayloadMapper mapper = mock(PdfPayloadMapper.class);
        when(mapper.mapPayload(any()))
                .thenReturn(objectMapper.createObjectNode().put("registerDate", day.toString()));
        final RegisterGenerationService service = new RegisterGenerationService(store, mapper,
                mock(PayloadFileStore.class), renderer, objectMapper, POLICY,
                waited -> {}, metrics, clock);
        return service.request(batch, Deadline.startingAt(shared, Duration.ofHours(1)),
                RenderProgress.NONE);
    }

    /** One register recorded and assembled into this case's batch, which stands at PENDING. */
    private RegisterBatch aPendingBatch() {
        record(UUID.randomUUID());
        final List<RegisterRecord> waiting = store.activeUnbatched().stream()
                .filter(record -> courtCentre.equals(record.key().courtCentreId()))
                .toList();
        return store.assemble(new RegisterBatch(UUID.randomUUID(), courtCentre, null, null, day,
                waiting.getFirst().fileName(), null, null, BatchStatus.PENDING, null, null, true,
                null, null, null, null, null, null, 0, null, 0), waiting);
    }

    /** One hearing's register, recorded the way the pipeline records it. */
    private void record(final UUID hearingId) {
        final DistributionCommand command = new DistributionCommand(
                ProcessedLogTestSupport.SOURCE, UUID.randomUUID(), hearingId,
                day, shared, "Hearing_Resulted");
        ProcessedLogTestSupport.repository(LEASE).insertNew(command,
                RequestFingerprint.of(command),
                new RunClaim(command.source(), command.requestId(), "runner-1", UUID.randomUUID(),
                        "msg-1"));
        store.recordAndComplete(command, document(hearingId), OU_CODE, APPLICANT,
                RecordedFlagState.ON, () -> new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));
    }

    private CourtRegisterDocument document(final UUID hearingId) {
        return new CourtRegisterDocument(
                shared.toString(),
                shared.truncatedTo(ChronoUnit.DAYS).toString(),
                hearingId.toString(),
                courtCentre.toString(),
                "court-register_" + day + '_' + OU_CODE + '_' + hearingId + ".pdf",
                null,
                new CourtRegisterHearingVenue("Lavender Hill LJA", "Lavender Hill Youth Court",
                        null),
                List.of(new CourtRegisterRecipient(
                        "Wandsworth Youth Offending Team", "yot@wandsworth.example.gov.uk", null,
                        "cr_standard")),
                List.of(new CourtRegisterDefendant(
                        "b2b3f5a1-6c9d-4e21-8a7f-3d5c1e9b0426", "SMITH, John", "2008-04-11",
                        null, null, null, "MALE", "Not Applicable", null, null,
                        List.of(), List.of(), List.of(), List.of())));
    }
}
