package uk.gov.hmcts.cp.yotresultsdistribution.application;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPause;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletedBy;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtCentreDay;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.Deadline;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GenerationFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.PayloadStoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterRecord;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RenderRequest;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.pipeline.PdfPayloadMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.support.AdjustableClock;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PersonalDataMarkers;

/**
 * One batch, from the payload to the render request, and the order it does those in.
 *
 * <p>The order is the subject, not a detail of it. Every other suite in this phase asks whether one
 * step is right; this one asks whether the steps can be interrupted in the wrong place and leave
 * something nobody can attribute. Three writes have to happen in one sequence - the payload file id
 * is minted and written onto the batch, then the payload is inserted under it, then the render is
 * asked for - and each inversion has its own way of going wrong: an id used before it is recorded is
 * a document that comes back correlated to nothing this service can find, and a render asked for
 * before the payload is stored is a document systemdocgenerator renders from a file that is not
 * there yet.
 *
 * <p><strong>Each step has one failure and one bounded reason.</strong> The four this suite pins are
 * the four a run can produce on its own account - a payload that could not be assembled, a payload
 * that could not be stored, a request that was refused, and a request that never got an answer
 * inside the run's budget - and none of them names a completion mechanism, because nobody outside
 * this service answered for any of them ({@code BatchFailureReason.isGeneratorAttributed()}). The
 * two that are somebody's answer arrive later, on the public-event topic, and belong to
 * {@code DocumentOutcomeSinkTest}.
 *
 * <p><strong>The service returns a verdict; it does not throw one.</strong> That is defect fix P5
 * and it is what {@code assembly_failure_fails_the_batch_and_the_run_continues} pins: progression's
 * {@code processRequests} catches the stream exception, logs it and walks on, leaving no state on
 * any row and no count anywhere, so a batch that failed and a batch that was never there look
 * identical from outside. Per-batch isolation was the right half of that - one bad batch must not
 * cost the estate a night's registers - and it is kept: the batch is failed FAILED
 * {@code ASSEMBLY_FAILED}, counted, and the next batch of the run is still asked for.
 *
 * <p><strong>What GENERATING claims, and what it does not.</strong> A 202 is the only success the
 * contract admits and it moves the batch to GENERATING, which says a render was asked for and not
 * that a document exists. So this suite asserts that the batches counter - the terminal-outcome
 * series - does <em>not</em> move on an accepted request, and that nothing here waits for a
 * document.
 *
 * <p>The privacy case is last and is a "never": the batch this service is rendering is a register
 * every defendant on which is a child, so the run's own lines carry the batch identity and bounded
 * codes and nothing that came out of a document (constitution Principle VII).
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("one batch, from the payload to the render request")
class RegisterGenerationServiceTest {

    /** Quoted by every case, so the red run names the seam it is waiting on. */
    private static final String PENDING =
            "T049 implements the generation service; this is its red run";

    /** Returned when a meter is absent, so a missing instrument fails as an assertion. */
    private static final double ABSENT = -1;

    /** The instant every case that is not about the budget is measured from: 18:00, as scheduled. */
    private static final Instant NOW = Instant.parse("2026-08-20T17:00:00Z");

    private static final UUID BATCH_ID = UUID.fromString("6f6b1a8e-4c67-4f0f-9b2b-5f0f6a3a1d21");
    private static final UUID COURT_CENTRE_ID =
            UUID.fromString("853b1ff8-fc2a-44d1-a621-0cd16419f54a");
    private static final UUID OTHER_BATCH_ID =
            UUID.fromString("2b2f4d38-9d2e-4a41-9f4e-1a1c0b6d7e55");
    private static final LocalDate REGISTER_DATE = LocalDate.parse("2026-08-20");

    /** The file name progression builds, as the batch's first record named it. */
    private static final String FILE_NAME = "yotresultsdistribution_2026-08-20.json";

    /** The template and the format, spelled as systemdocgenerator's own contract spells them. */
    private static final String TEMPLATE = "OEE_Layout5";
    private static final String FORMAT = "pdf";

    /** This service's own name, which is what keeps progression's still-deployed listener out. */
    private static final String ORIGINATING_SOURCE = "YotResultsDistributionService";

    /** The key progression's generator reads the batch's documents from. */
    private static final String DOCUMENT_REQUESTS = "courtRegisterDocumentRequests";

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(200);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(2);

    /**
     * A declared attempt cost small enough that the attempt is never the thing that does not fit, so
     * a case about the run's budget is about the budget.
     */
    private static final Duration CHEAP_ATTEMPT = Duration.ofMillis(20);

    /** The status the contract admits, and the only one. */
    private static final int ACCEPTED = 202;

    /** A 2xx that is not 202: something other than the command endpoint answered. */
    private static final int NOT_THE_COMMAND_ENDPOINT = 200;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GenerationMetrics metrics = new GenerationMetrics(registry);
    private final ObjectMapper objectMapper = JacksonConfig.contractObjectMapper();
    private final RegisterStore store = mock(RegisterStore.class);
    private final PdfPayloadMapper payloadMapper = mock(PdfPayloadMapper.class);
    private final PayloadFileStore payloadFileStore = mock(PayloadFileStore.class);
    private final DocumentRenderer renderer = mock(DocumentRenderer.class);

    /** What the run is told as the call is made, which is where the night's count comes from. */
    private final RecordingProgress progress = new RecordingProgress();

    private RecordingPause pause;
    private AdjustableClock clock;

    /** What the ported generator answers with; opaque here, and pinned by T031 rather than by this. */
    private JsonNode payload;

    @InjectSoftAssertions
    private SoftAssertions softly;

    @BeforeEach
    void seedTheRun() {
        clock = AdjustableClock.startingAt(NOW);
        pause = new RecordingPause();
        payload = objectMapper.createObjectNode().put("registerDate", "2026-08-20");
        when(store.batched(BATCH_ID)).thenReturn(List.of(record("Fred Smith")));
        when(payloadMapper.mapPayload(any())).thenReturn(payload);
    }

    /** The service a case that is not about the budget uses. */
    private RegisterGenerationService service() {
        return service(MAX_ATTEMPTS, CHEAP_ATTEMPT);
    }

    private RegisterGenerationService service(
            final int maxAttempts, final Duration attemptWorstCase) {
        return new RegisterGenerationService(store, payloadMapper, payloadFileStore, renderer,
                objectMapper,
                new RetryPolicy(maxAttempts, INITIAL_BACKOFF, MAX_BACKOFF, attemptWorstCase),
                pause, metrics, clock);
    }

    /**
     * A budget no case that is not about the deadline can exhaust: the clock moves only when a wait
     * is taken, so an hour is unreachable by construction.
     */
    private static Deadline farDeadline() {
        return Deadline.startingAt(NOW, Duration.ofHours(1));
    }

    /** The batch as the assembler left it: durable, PENDING, and carrying no payload id yet. */
    private static RegisterBatch batch(final UUID batchId) {
        return new RegisterBatch(batchId, COURT_CENTRE_ID, "B01LY", "Lavender Hill", REGISTER_DATE,
                FILE_NAME, null, null, BatchStatus.PENDING, null, null, true, null, NOW, null, null,
                null, null, 0, null, 0);
    }

    private static RegisterBatch batch() {
        return batch(BATCH_ID);
    }

    /**
     * One recorded register, carrying a defendant name so that the privacy case has something it
     * would be a breach to log.
     *
     * @param defendantName the name that must never reach a line at INFO or above
     * @return the record, as the batch half reads it back
     */
    private static RegisterRecord record(final String defendantName) {
        final UUID hearingId = UUID.randomUUID();
        return new RegisterRecord(UUID.randomUUID(), hearingId, NOW,
                new CourtCentreDay(COURT_CENTRE_ID, REGISTER_DATE), NOW, FILE_NAME, "Applicant",
                RecordedFlagState.ON,
                new CourtRegisterDocument("2026-08-20T09:00:00Z", "2026-08-20T09:00:00Z",
                        hearingId.toString(), COURT_CENTRE_ID.toString(), FILE_NAME, "Applicant",
                        null, null,
                        List.of(new CourtRegisterDefendant(UUID.randomUUID().toString(),
                                defendantName, "2009-11-23", null, null, null, null, null, null,
                                null, null, null, null, null))));
    }

    /**
     * Puts one batch in front of the service and asks it to request a render.
     *
     * <p>The call is made inside {@code assertThatCode(...).doesNotThrowAnyException()} so that the
     * seam's refusal is recorded as an assertion rather than ending the case, and the outcome it did
     * not produce is then asserted on as {@code null} - the red run is the outcome and the green run
     * is the same assertions unchanged.
     *
     * @param requested the batch being asked about
     * @param deadline  the run's requesting bound
     * @return what the service answered, or {@code null} where the seam refused
     */
    private BatchOutcome request(final RegisterBatch requested, final Deadline deadline) {
        return request(service(), requested, deadline);
    }

    private BatchOutcome request(final RegisterGenerationService service,
            final RegisterBatch requested, final Deadline deadline) {
        final AtomicReference<BatchOutcome> answered = new AtomicReference<>();
        softly.assertThatCode(() -> answered.set(service.request(requested, deadline, progress)))
                .as(PENDING)
                .doesNotThrowAnyException();
        return answered.get();
    }

    private BatchOutcome request() {
        return request(batch(), farDeadline());
    }

    /**
     * Asks for a render and hands back whatever left the requesting leg, without ending the case.
     *
     * <p>The counterpart of {@link #request(RegisterBatch, Deadline)} for the calls that do not
     * answer: a store that will not write the batch's ending down is not something this leg can
     * turn into an outcome, so the failure travels out of it and a case about that cannot call the
     * service directly and still assert on what the run had been told.
     *
     * @param requested the batch being asked about
     * @return what stopped the request, or {@code null} where nothing did
     */
    private Throwable whatStoppedTheRequest(final RegisterBatch requested) {
        return catchThrowable(() -> service().request(requested, farDeadline(), progress));
    }

    /** The payload id the service minted, as the batch row was told it. */
    private UUID mintedId() {
        final ArgumentCaptor<UUID> minted = ArgumentCaptor.forClass(UUID.class);
        verify(store).markPayloadMinted(eq(BATCH_ID), minted.capture());
        return minted.getValue();
    }

    private RenderRequest renderRequest() {
        final ArgumentCaptor<RenderRequest> asked = ArgumentCaptor.forClass(RenderRequest.class);
        verify(renderer).requestRender(asked.capture(), any());
        return asked.getValue();
    }

    private PayloadMetadata storedMetadata() {
        final ArgumentCaptor<PayloadMetadata> written =
                ArgumentCaptor.forClass(PayloadMetadata.class);
        verify(payloadFileStore).store(any(), any(), written.capture());
        return written.getValue();
    }

    private double batches(final BatchStatus outcome) {
        return count(GenerationMetrics.BATCHES, GenerationMetrics.OUTCOME_TAG,
                outcome.name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    private double requests(final int responseCode) {
        return count(GenerationMetrics.GENERATION_REQUEST, GenerationMetrics.RESPONSE_CODE_TAG,
                String.valueOf(responseCode));
    }

    private double count(final String name, final String tag, final String value) {
        final Counter counter = registry.find(name).tag(tag, value).counter();
        return counter == null ? ABSENT : counter.count();
    }

    /**
     * Every line a log index would keep, at or above the given level.
     *
     * @param log   what the run wrote while the case ran
     * @param level the lowest level a reader outside this pod ever sees
     * @return the formatted messages of those events
     */
    private static List<String> atOrAbove(final CapturedLog log, final Level level) {
        return log.events().stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(level))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /**
     * The line the run's own budget writes, as an operator reads it.
     *
     * <p>Built from the count rather than quoted twice, because what the case is about is the
     * number: the wording is the same on both nights this line describes and only the number tells
     * a reader which of them happened.
     *
     * @param attempts how many attempts the line should say had been made
     * @return that ERROR line in full
     */
    private static String theDeadlineGaveUpAfter(final int attempts) {
        return "The run deadline would not hold another render attempt for batch " + BATCH_ID
                + ", so it is failed " + BatchFailureReason.RENDER_REQUEST_FAILED + " after "
                + attempts + " attempts.";
    }

    /** A refusal systemdocgenerator will give the same answer to however often it is asked. */
    private static GenerationFailedException refusal() {
        return new GenerationFailedException(FailureClassification.NON_TRANSIENT,
                BatchFailureReason.RENDER_REQUEST_REJECTED, NOT_THE_COMMAND_ENDPOINT);
    }

    /** A failure another attempt inside the run deadline could answer differently. */
    private static GenerationFailedException transientFailure() {
        return new GenerationFailedException(FailureClassification.TRANSIENT,
                BatchFailureReason.RENDER_REQUEST_FAILED);
    }

    /**
     * The three writes that cannot be reordered without losing a document.
     */
    @Nested
    @DisplayName("the payload id, written down before it is used")
    class MintingThePayloadId {

        @Test
        void the_payload_id_should_be_on_the_batch_row_before_the_payload_is_stored() {
            request();

            final InOrder order = inOrder(store, payloadFileStore, renderer);
            order.verify(store).markPayloadMinted(eq(BATCH_ID), any());
            order.verify(payloadFileStore).store(any(), any(), any());
            order.verify(renderer).requestRender(any(), any());
            order.verify(store).markRequested(eq(BATCH_ID), any());
        }

        @Test
        void the_id_written_down_should_be_the_one_the_payload_is_stored_under() {
            request();

            final ArgumentCaptor<UUID> stored = ArgumentCaptor.forClass(UUID.class);
            verify(payloadFileStore).store(stored.capture(), any(), any());

            softly.assertThat(stored.getValue())
                    .as("an insert made under an id other than the one the batch row carries is a "
                            + "payload this service cannot find again, and a document it could not "
                            + "attribute if one came back")
                    .isEqualTo(mintedId());
        }

        @Test
        void the_id_written_down_should_be_the_one_the_render_is_asked_about() {
            request();

            final ArgumentCaptor<UUID> requested = ArgumentCaptor.forClass(UUID.class);
            verify(store).markRequested(eq(BATCH_ID), requested.capture());

            softly.assertThat(renderRequest().payloadFileId())
                    .as("systemdocgenerator renders whatever payloadFileServiceId names, so this is "
                            + "the id that decides which payload becomes the document")
                    .isEqualTo(mintedId());
            softly.assertThat(requested.getValue())
                    .as("and the mark that moves the batch names the same one, so the row and the "
                            + "request cannot disagree about which payload was rendered")
                    .isEqualTo(mintedId());
        }

        @Test
        void the_payload_id_should_be_its_own_identity_and_never_the_batchs() {
            request();

            softly.assertThat(mintedId())
                    .as("two identities doing two jobs: the batch id correlates the outcome event "
                            + "back to rows and the payload id names a file in somebody else's "
                            + "database; one value doing both would make a file-service id a "
                            + "correlation key")
                    .isNotEqualTo(BATCH_ID);
        }
    }

    /**
     * What the renderer is given to render, and what is written beside it.
     */
    @Nested
    @DisplayName("the payload the batch is turned into")
    class ThePayload {

        @Test
        void the_mapper_should_be_given_the_batchs_registers_under_progressions_own_key() {
            final List<RegisterRecord> assembled = List.of(record("Fred Smith"), record("Ada Khan"));
            when(store.batched(BATCH_ID)).thenReturn(assembled);

            request();

            final ArgumentCaptor<JsonNode> mapped = ArgumentCaptor.forClass(JsonNode.class);
            verify(payloadMapper).mapPayload(mapped.capture());

            softly.assertThat(mapped.getValue().get(DOCUMENT_REQUESTS))
                    .as("the ported generator was written against the shape progression's own "
                            + "CourtRegisterGenerated event carried - every register of the batch, "
                            + "in the order the batch holds them, under that one key; a wrapper "
                            + "spelled any other way is a payload it reads as empty")
                    .isEqualTo(objectMapper.createArrayNode()
                            .add(objectMapper.valueToTree(assembled.get(0).document()))
                            .add(objectMapper.valueToTree(assembled.get(1).document())));
        }

        @Test
        void the_payload_stored_should_be_exactly_what_the_mapper_produced() {
            request();

            final ArgumentCaptor<JsonNode> written = ArgumentCaptor.forClass(JsonNode.class);
            verify(payloadFileStore).store(any(), written.capture(), any());

            softly.assertThat(written.getValue())
                    .as("the port is byte-identical to progression's generator (T031) and this "
                            + "service adds nothing to its answer on the way past; a field added "
                            + "here would be a template change nobody agreed to")
                    .isSameAs(payload);
        }

        @Test
        void the_metadata_should_carry_progressions_five_keys_as_progression_spells_them() {
            request();

            final PayloadMetadata metadata = storedMetadata();

            softly.assertThat(metadata.fileName())
                    .as("the first record's file name, as progression named the document")
                    .isEqualTo(FILE_NAME);
            softly.assertThat(metadata.conversionFormat())
                    .as("the format systemdocgenerator is asked for and the format the metadata "
                            + "row records are one decision, not two")
                    .isEqualTo(FORMAT);
            softly.assertThat(metadata.templateName())
                    .as("the file service is not this service's database: the keys and their "
                            + "values are progression's, unchanged")
                    .isEqualTo(TEMPLATE);
            softly.assertThat(metadata.numberOfPages())
                    .as("the page count progression writes, which is one")
                    .isEqualTo(1);
            softly.assertThat(metadata.fileSize())
                    .as("the payload's own size, so the metadata row describes the content row "
                            + "beside it rather than a number nobody derived")
                    .isEqualTo(objectMapper.writeValueAsString(payload)
                            .getBytes(StandardCharsets.UTF_8).length);
        }
    }

    /**
     * The file service could not be written to, so there is nothing to render.
     */
    @Nested
    @DisplayName("a payload that could not be stored")
    class WhenThePayloadCannotBeStored {

        @BeforeEach
        void refuseTheWrite() {
            doThrow(new PayloadStoreUnavailableException("the file-service insert wrote no row"))
                    .when(payloadFileStore).store(any(), any(), any());
        }

        @Test
        void a_payload_that_could_not_be_stored_should_fail_the_batch_under_one_bounded_reason() {
            final BatchOutcome outcome = request();

            verify(store).markFailed(BATCH_ID, BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE, null,
                    null);
            softly.assertThat(outcome)
                    .as("one way this can go wrong from the batch's point of view - the document "
                            + "has nothing to be rendered from - and the run is told so rather "
                            + "than left to read the row back")
                    .isEqualTo(new BatchOutcome(BATCH_ID, BatchStatus.FAILED,
                            BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE, false));
        }

        @Test
        void nothing_should_be_asked_of_the_renderer_when_there_is_no_payload_to_render() {
            request();

            verifyNoInteractions(renderer);
            verify(store, never()).markRequested(any(), any());
        }

        @Test
        void a_batch_that_ended_failed_should_be_counted_under_that_outcome() {
            request();

            softly.assertThat(batches(BatchStatus.FAILED))
                    .as("the rows go back to the next run, so nothing else in this flow will ever "
                            + "mention this batch again; the counter is the whole of what a "
                            + "dashboard sees")
                    .isEqualTo(1);
        }
    }

    /**
     * systemdocgenerator accepted the request, which says a render was asked for and nothing else.
     */
    @Nested
    @DisplayName("a render request the contract's 202 accepted")
    class WhenTheRenderIsAccepted {

        @Test
        void an_accepted_render_should_leave_the_batch_generating() {
            final BatchOutcome outcome = request();

            softly.assertThat(outcome)
                    .as("GENERATING is honest about what has happened: a render was asked for, and "
                            + "the document arrives later on the public-event topic")
                    .isEqualTo(new BatchOutcome(BATCH_ID, BatchStatus.GENERATING, null, true));
        }

        @Test
        void an_accepted_render_should_be_written_down_with_the_payload_it_asked_about() {
            request();

            verify(store).markRequested(eq(BATCH_ID), any());
        }

        @Test
        void the_render_request_should_be_the_five_fields_the_contract_names() {
            request();

            softly.assertThat(renderRequest())
                    .as("the batch id travels as sourceCorrelationId, which is the only thing that "
                            + "correlates an outcome event back to rows, and originatingSource is "
                            + "this service's own name, which is what keeps progression's "
                            + "still-deployed listener out of this service's documents")
                    .isEqualTo(new RenderRequest(mintedId(), BATCH_ID, TEMPLATE, FORMAT,
                            ORIGINATING_SOURCE));
        }

        @Test
        void a_nightly_render_should_be_asked_for_as_the_configured_system_identity() {
            request();

            final ArgumentCaptor<CallerIdentity> caller =
                    ArgumentCaptor.forClass(CallerIdentity.class);
            verify(renderer).requestRender(any(), caller.capture());

            softly.assertThat(caller.getValue())
                    .as("a scheduled run is nobody's request: no message named a user, so the call "
                            + "is made under the configured identity rather than under whichever "
                            + "user happened to share the last hearing of the day")
                    .isEqualTo(CallerIdentity.SYSTEM);
        }

        @Test
        void an_accepted_request_should_be_counted_under_the_status_the_contract_admits() {
            request();

            softly.assertThat(requests(ACCEPTED))
                    .as("the request counter's one dimension is what systemdocgenerator answered, "
                            + "so a night of 202s and a night of refusals are two series rather "
                            + "than one number")
                    .isEqualTo(1);
        }

        @Test
        void a_batch_that_only_reached_generating_should_not_be_counted_as_a_terminal_outcome() {
            request();

            softly.assertThat(registry.find(GenerationMetrics.BATCHES).counter())
                    .as("the batches counter is the terminal-outcome series; counting a batch on "
                            + "the way past would make every accepted request look like a finished "
                            + "one and hide the batches that never came back")
                    .isNull();
        }
    }

    /**
     * The renderer answered, and its answer was not the contract's 202.
     */
    @Nested
    @DisplayName("a render request systemdocgenerator refused")
    class WhenTheRenderIsRefused {

        @BeforeEach
        void refuseTheRequest() {
            doThrow(refusal()).when(renderer).requestRender(any(), any());
        }

        @Test
        void a_refusal_should_fail_the_batch_under_the_reason_the_renderer_classified_it_as() {
            final BatchOutcome outcome = request();

            verify(store).markFailed(BATCH_ID, BatchFailureReason.RENDER_REQUEST_REJECTED, null,
                    null);
            softly.assertThat(outcome)
                    .as("a 2xx that is not 202 means something other than the command endpoint "
                            + "answered, which is a different investigation from a refusal and is "
                            + "never treated as a render that will happen")
                    .isEqualTo(new BatchOutcome(BATCH_ID, BatchStatus.FAILED,
                            BatchFailureReason.RENDER_REQUEST_REJECTED, true));
        }

        @Test
        void a_refusal_should_not_be_asked_again() {
            request();

            verify(renderer, times(1)).requestRender(any(), any());
            softly.assertThat(pause.waits)
                    .as("the service branches on the classification and never on the type: "
                            + "NON_TRANSIENT means another attempt at the same request cannot "
                            + "answer differently, and waiting to prove it costs the run's budget")
                    .isEmpty();
        }

        @Test
        void a_refused_batch_should_never_be_marked_requested() {
            request();

            verify(store, never()).markRequested(any(), any());
        }

        @Test
        void a_batch_that_ended_failed_should_be_counted_under_that_outcome() {
            request();

            softly.assertThat(batches(BatchStatus.FAILED))
                    .as("terminal, and counted where the four failure reasons are read together")
                    .isEqualTo(1);
        }
    }

    /**
     * The request did not reach a verdict, and another attempt inside the run's budget might.
     */
    @Nested
    @DisplayName("a render request worth asking again")
    class WhenTheRequestIsWorthAnotherAttempt {

        @Test
        void a_transient_failure_should_be_asked_again_inside_the_run_deadline() {
            doThrow(transientFailure()).doNothing().when(renderer).requestRender(any(), any());

            final BatchOutcome outcome = request();

            verify(renderer, times(2)).requestRender(any(), any());
            softly.assertThat(outcome)
                    .as("a connect failure and a 503 are not a refusal, and a batch failed on the "
                            + "first of them is a night's register lost to a restart somewhere else")
                    .isEqualTo(new BatchOutcome(BATCH_ID, BatchStatus.GENERATING, null, true));
            softly.assertThat(pause.waits)
                    .as("the wait is the shared policy's, so this service cannot hold a different "
                            + "opinion about a back-off than the two clients 001 built (C3)")
                    .containsExactly(INITIAL_BACKOFF);
        }

        @Test
        void the_attempts_should_run_out_under_the_shared_policys_budget() {
            doThrow(transientFailure()).when(renderer).requestRender(any(), any());

            final BatchOutcome outcome = request();

            verify(renderer, times(MAX_ATTEMPTS)).requestRender(any(), any());
            verify(store).markFailed(BATCH_ID, BatchFailureReason.RENDER_REQUEST_FAILED, null, null);
            softly.assertThat(outcome)
                    .as("the request could not be delivered, which is its own reason: the rows go "
                            + "back to the next run and nothing downstream believes a register is "
                            + "coming")
                    .isEqualTo(new BatchOutcome(BATCH_ID, BatchStatus.FAILED,
                            BatchFailureReason.RENDER_REQUEST_FAILED, true));
            softly.assertThat(pause.waits)
                    .as("doubling per attempt, bounded by max-backoff, and a wait between each "
                            + "pair of attempts rather than after the last one")
                    .containsExactly(INITIAL_BACKOFF, INITIAL_BACKOFF.multipliedBy(2));
        }

        @Test
        void no_further_attempt_should_be_started_once_the_run_deadline_would_not_hold_one() {
            doThrow(transientFailure()).when(renderer).requestRender(any(), any());
            final Deadline nearlyGone = Deadline.startingAt(NOW, Duration.ofMillis(150));

            final BatchOutcome outcome = request(service(), batch(), nearlyGone);

            verify(renderer, times(1)).requestRender(any(), any());
            verify(store).markFailed(BATCH_ID, BatchFailureReason.RENDER_REQUEST_FAILED, null, null);
            softly.assertThat(outcome)
                    .as("the deadline bounds requesting, and it bounds it against what an attempt "
                            + "costs at worst rather than against the instant it starts: a run "
                            + "still asking at midnight is a run that collides with the next one")
                    .isEqualTo(new BatchOutcome(BATCH_ID, BatchStatus.FAILED,
                            BatchFailureReason.RENDER_REQUEST_FAILED, true));
        }

        @Test
        void a_batch_whose_render_was_never_accepted_should_never_be_marked_requested() {
            doThrow(transientFailure()).when(renderer).requestRender(any(), any());

            request();

            verify(store, never()).markRequested(any(), any());
        }
    }

    /**
     * Whether the request left this service, which the reason it failed under cannot answer.
     *
     * <p>RENDER_REQUEST_FAILED is the ending of two different nights. A request that was made and
     * answered nothing inside the budget ends that way, and so does a batch this class was handed
     * with less budget left than one attempt's own worst case - which asks systemdocgenerator
     * nothing at all. The outcome carries which of the two happened, because the run report's
     * {@code requested} count is read as "the renderer was sent this many documents", and a night
     * that counted the second would report a renderer refusing a document it was never sent.
     *
     * <p>The deadline both cases work to has <strong>not</strong> passed. The run checks that before
     * it hands a batch over ({@code RegisterGenerationJob.request}, which counts a batch it has no
     * time left for as PENDING without asking this class at all), so a batch reaches here with
     * budget left - and what is left may still be smaller than an attempt.
     */
    @Nested
    @DisplayName("whether the render request left this service")
    class WhetherTheRequestLeftThisService {

        @Test
        void a_budget_too_small_for_one_attempt_should_say_no_render_was_ever_asked_for() {
            final Deadline noRoomForAnAttempt =
                    Deadline.startingAt(NOW, CHEAP_ATTEMPT.dividedBy(2));

            final BatchOutcome outcome = request(batch(), noRoomForAnAttempt);

            softly.assertThat(noRoomForAnAttempt.hasPassedAt(NOW))
                    .as("the budget is not spent, so the run hands this batch over rather than "
                            + "counting it left behind; what it will not hold is one whole attempt")
                    .isFalse();
            verifyNoInteractions(renderer);
            softly.assertThat(outcome)
                    .as("nothing was sent, and the bounded reason cannot say so - the run report "
                            + "counts the renders a night asked for off this, and counting this "
                            + "one would tell an operator the renderer refused a document it "
                            + "never had")
                    .isEqualTo(new BatchOutcome(BATCH_ID, BatchStatus.FAILED,
                            BatchFailureReason.RENDER_REQUEST_FAILED, false));
        }

        @Test
        void a_request_that_left_and_then_ran_out_of_budget_should_say_it_was_made() {
            doThrow(transientFailure()).when(renderer).requestRender(any(), any());
            final Deadline roomForOneAttempt = Deadline.startingAt(NOW, Duration.ofMillis(150));

            final BatchOutcome outcome = request(batch(), roomForOneAttempt);

            verify(renderer, times(1)).requestRender(any(), any());
            softly.assertThat(outcome)
                    .as("the same bounded reason and the other night: the request was away and "
                            + "nothing answered it, so this batch belongs in the count and the "
                            + "distinction cannot be made by answering false for the reason")
                    .isEqualTo(new BatchOutcome(BATCH_ID, BatchStatus.FAILED,
                            BatchFailureReason.RENDER_REQUEST_FAILED, true));
        }
    }

    /**
     * What the line says a run gave up after, which has to be what happened.
     *
     * <p>The same statement describes both of the deadline's nights - a batch this leg was handed
     * with less budget left than one attempt's own worst case, and a request that was made and left
     * the run too little to wait before asking again - and the number is the only thing on it that
     * tells them apart. It is read by somebody deciding whether the night was short of time or
     * systemdocgenerator was slow to answer, and a line saying one attempt was made where none was
     * points that reader at the wrong service.
     */
    @Nested
    @DisplayName("what the deadline's own line says")
    class TheLineTheBudgetWrites {

        @Test
        void a_batch_no_attempt_could_be_started_for_should_say_no_attempt_was_made() {
            final Deadline noRoomForAnAttempt =
                    Deadline.startingAt(NOW, CHEAP_ATTEMPT.dividedBy(2));

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationService.class)) {
                request(batch(), noRoomForAnAttempt);

                verifyNoInteractions(renderer);
                softly.assertThat(atOrAbove(log, Level.ERROR))
                        .as("nothing was asked of systemdocgenerator, so nothing had been "
                                + "attempted: the count on the line is what the run did and not "
                                + "which attempt it was about to make")
                        .containsExactly(theDeadlineGaveUpAfter(0));
            }
        }

        /**
         * The guard before an attempt, on the pass where attempts have been made.
         *
         * <p>The case the two above cannot make between them, and the one that says the number is
         * arithmetic rather than a constant: this batch was asked for once, the back-off after that
         * attempt fitted and was taken, and the attempt the next pass would have made did not fit.
         * One attempt has been made and one is reported. A line that answered nought here would be
         * as wrong as one that answered two, and the same guard writes both.
         */
        @Test
        void a_budget_spent_between_attempts_should_say_only_the_attempts_that_were_made() {
            doThrow(transientFailure()).when(renderer).requestRender(any(), any());
            // Room for the first attempt and the wait after it, and none for the second attempt:
            // the pause moves the clock by the back-off the shared policy names.
            final Deadline roomForTheWaitAndNoMore =
                    Deadline.startingAt(NOW, INITIAL_BACKOFF.plusMillis(10));

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationService.class)) {
                request(batch(), roomForTheWaitAndNoMore);

                verify(renderer, times(1)).requestRender(any(), any());
                softly.assertThat(pause.waits)
                        .as("the wait after the first attempt was taken, which is what leaves the "
                                + "budget too small for the attempt that would have followed it")
                        .containsExactly(INITIAL_BACKOFF);
                softly.assertThat(atOrAbove(log, Level.ERROR))
                        .as("one attempt was made and the second was never started, so the line "
                                + "reports the one that happened: the count is the attempts "
                                + "behind this pass and not the pass number")
                        .containsExactly(theDeadlineGaveUpAfter(1));
            }
        }

        @Test
        void a_request_that_left_before_the_budget_ran_out_should_say_one_attempt_was_made() {
            doThrow(transientFailure()).when(renderer).requestRender(any(), any());
            final Deadline roomForOneAttempt = Deadline.startingAt(NOW, Duration.ofMillis(150));

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationService.class)) {
                request(batch(), roomForOneAttempt);

                verify(renderer, times(1)).requestRender(any(), any());
                softly.assertThat(atOrAbove(log, Level.ERROR))
                        .as("one attempt was made and answered nothing, and the wait before the "
                                + "second would have outlasted the night; the same line, and the "
                                + "number is what says which night it was")
                        .containsExactly(theDeadlineGaveUpAfter(1));
            }
        }
    }

    /**
     * The call was made and the store would not write down what came of it.
     *
     * <p>The night the outcome cannot be the only informant. Both endings an answered call has are
     * written down before the verdict is returned - the mark that records an accepted request, and
     * the mark that fails a refused one - and each of those writes can be the moment the store goes
     * away. The verdict never reaches the run then, so a run counting only the outcomes it was
     * handed would report a night that sent the renderer nothing while a render was away and a
     * document was on its way back to it. The count is what an operator reads to decide whether
     * systemdocgenerator has work in flight, and the reading that matters is the one taken on the
     * night something else broke.
     *
     * <p>So the call is announced where it is made, and the failure still leaves: a store outage is
     * not this leg's to settle (constitution Principle VI), and the run reports what it had done
     * and rethrows.
     */
    @Nested
    @DisplayName("a store that would not answer once the request had left")
    class WhenTheStoreFailsAfterTheCall {

        /** What a store outage looks like from here: a bounded phrase, and the driver's cause. */
        private static StoreUnavailableException outage(final String statement) {
            return new StoreUnavailableException("the store could not be reached to " + statement,
                    new IllegalStateException("the connection pool is empty"));
        }

        @Test
        void an_accepted_render_should_be_announced_before_the_mark_that_could_not_be_written() {
            doThrow(outage("mark a batch requested")).when(store).markRequested(any(), any());

            final Throwable stopped = whatStoppedTheRequest(batch());

            verify(renderer).requestRender(any(), any());
            softly.assertThat(progress.announced)
                    .as("systemdocgenerator has the request and will render this batch whatever "
                            + "this service manages to write down about it, so the render is the "
                            + "run's to count from the moment the call is made rather than from "
                            + "the outcome that never arrives")
                    .containsExactly(BATCH_ID);
            softly.assertThat(stopped)
                    .as("and the outage still leaves: a batch whose mark was not written is not a "
                            + "batch this leg can answer for, and nothing about that changes "
                            + "because the run has been told the call was made")
                    .isInstanceOf(StoreUnavailableException.class)
                    .hasMessage("the store could not be reached to mark a batch requested");
        }

        @Test
        void a_refused_render_should_be_announced_before_the_mark_that_could_not_be_written() {
            doThrow(refusal()).when(renderer).requestRender(any(), any());
            doThrow(outage("fail a batch")).when(store).markFailed(any(), any(), any(), any());

            final Throwable stopped = whatStoppedTheRequest(batch());

            verify(renderer).requestRender(any(), any());
            softly.assertThat(progress.announced)
                    .as("a refusal is an answer to a request that left this service, so the night "
                            + "asked systemdocgenerator for this document; the count is of what "
                            + "was sent and not of what was accepted, which is what generating "
                            + "already says")
                    .containsExactly(BATCH_ID);
            softly.assertThat(stopped)
                    .as("and the outage still leaves, from the failing mark exactly as from the "
                            + "requesting one")
                    .isInstanceOf(StoreUnavailableException.class)
                    .hasMessage("the store could not be reached to fail a batch");
        }

        @Test
        void three_attempts_at_one_batch_should_be_announced_as_one_render() {
            doThrow(transientFailure()).when(renderer).requestRender(any(), any());

            request();

            verify(renderer, times(MAX_ATTEMPTS)).requestRender(any(), any());
            softly.assertThat(progress.announced)
                    .as("one batch, one document, one register: the count is the documents the "
                            + "renderer was sent, and a night reported as three renders because "
                            + "the retry budget was spent on one would have an operator looking "
                            + "for two documents that were never asked for")
                    .containsExactly(BATCH_ID);
        }

        /**
         * The bound in the other direction, and it is green on introduction.
         *
         * <p>Deliberately not labelled <strong>[A]</strong>: it states a property the pair must not
         * break rather than one it introduces, and it is the red half's own pair - the announcement
         * has to be made at the call and not before the guard that decides whether a call is made
         * at all. A batch this leg is handed with less budget left than one attempt's worst case
         * asks systemdocgenerator nothing, and announcing it would put the whole failure back the
         * other way round: a night reporting a render nobody sent.
         */
        @Test
        void a_budget_too_small_for_one_attempt_should_announce_no_render_at_all() {
            final Deadline noRoomForAnAttempt =
                    Deadline.startingAt(NOW, CHEAP_ATTEMPT.dividedBy(2));

            request(batch(), noRoomForAnAttempt);

            verifyNoInteractions(renderer);
            softly.assertThat(progress.announced)
                    .as("nothing was sent, so there is nothing for the night to count; the "
                            + "announcement belongs after the guard that decides whether an "
                            + "attempt is started and never before it")
                    .isEmpty();
        }
    }

    /**
     * Defect fix P5's isolation, held against the outcome that arrives before the mark (F-01).
     *
     * <p>The requesting leg is not the only writer of a batch between PENDING and GENERATING. The
     * public-event listener runs on every pod while the night is being asked for, and
     * systemdocgenerator can render a batch and announce it before its 202 has reached this
     * service - or answer a request whose 202 was lost and then announce the document while this leg
     * is still retrying. Either way the listener moves the batch first, and the mark this leg then
     * makes is one the state machine refuses: GENERATED or FAILED to GENERATING, GENERATED or
     * NOTIFIED to FAILED. Thrown, that refusal took the whole night out with it and every court
     * centre behind the batch got no register.
     *
     * <p>An overtaken mark is not an error. The render was accepted - an outcome exists only for a
     * request systemdocgenerator took - so the requesting leg answers GENERATING, exactly as it would
     * had its mark landed first, and the settled snapshot the run takes from the store says what the
     * batch came to. A refused mark on a batch nothing moved is still the defect it always was, and
     * still leaves.
     *
     * <p><strong>[A]</strong> - the five {@code ..._should_still_leave} cases and the read-back outage case are green on
     * introduction: they state the bound the fix must not cross rather than the behaviour it adds,
     * so that the overtaken answer cannot be built by absorbing every refusal.
     */
    @Nested
    @DisplayName("a render whose outcome overtook its own mark (P5, F-01)")
    class WhenTheOutcomeOvertookTheMark {

        /** The refusal the store raises for a move the state machine does not draw. */
        private static IllegalStateException refusedMove(final BatchStatus from,
                final BatchStatus to) {
            return new IllegalStateException(
                    "batch " + BATCH_ID + " may not move from " + from + " to " + to);
        }

        /**
         * The batch as the store holds it once the listener has been at it.
         *
         * @param status where the outcome left it
         * @return the batch under that state, everything else as the assembler left it
         */
        private RegisterBatch heldAt(final BatchStatus status, final CompletedBy completedBy) {
            final RegisterBatch assembled = batch();
            return new RegisterBatch(assembled.batchId(), assembled.courtCentreId(),
                    assembled.courtCentreOuCode(), assembled.courtHouse(),
                    assembled.registerDate(), assembled.fileName(), assembled.payloadFileId(),
                    assembled.documentFileId(), status, assembled.failureReason(),
                    assembled.sdgReason(), assembled.systemGenerated(), completedBy,
                    assembled.assembledAt(), assembled.requestedAt(), assembled.generatedAt(),
                    assembled.notifiedAt(), assembled.failedAt(), assembled.attempts(),
                    assembled.supplementOf(), assembled.supplementIndex());
        }

        /**
         * What the store answers once the listener has been at the batch.
         *
         * <p>A FAILED batch is one {@code generation-failed} failed, so it names the event; every
         * other state carries the mechanism the listener's mark writes, which is also the event.
         *
         * @param status where the outcome left it
         */
        private void theStoreNowHolds(final BatchStatus status) {
            theStoreNowHolds(status, CompletedBy.EVENT);
        }

        private void theStoreNowHolds(final BatchStatus status, final CompletedBy completedBy) {
            when(store.batchesNamed(List.of(BATCH_ID)))
                    .thenReturn(List.of(heldAt(status, completedBy)));
        }

        @Test
        void a_document_that_landed_before_the_mark_should_leave_the_batch_answered_generating() {
            doThrow(refusedMove(BatchStatus.GENERATED, BatchStatus.GENERATING))
                    .when(store).markRequested(any(), any());
            theStoreNowHolds(BatchStatus.GENERATED);

            final BatchOutcome outcome = request();

            softly.assertThat(outcome)
                    .as("systemdocgenerator accepted the render and the listener got to the batch "
                            + "first; the night is owed the next court centre, not a throw, and "
                            + "what the batch came to is the settled snapshot's to say")
                    .isEqualTo(new BatchOutcome(BATCH_ID, BatchStatus.GENERATING, null, true));
        }

        @Test
        void a_batch_already_notified_before_the_mark_should_be_answered_generating_too() {
            doThrow(refusedMove(BatchStatus.NOTIFIED, BatchStatus.GENERATING))
                    .when(store).markRequested(any(), any());
            theStoreNowHolds(BatchStatus.NOTIFIED);

            softly.assertThat(request())
                    .as("the listener notifies inline, so by the time the 202 is read the teams "
                            + "may already have been told")
                    .isEqualTo(new BatchOutcome(BATCH_ID, BatchStatus.GENERATING, null, true));
        }

        @Test
        void a_generation_failure_that_landed_before_the_mark_should_not_end_the_run() {
            doThrow(refusedMove(BatchStatus.FAILED, BatchStatus.GENERATING))
                    .when(store).markRequested(any(), any());
            theStoreNowHolds(BatchStatus.FAILED);

            softly.assertThat(request())
                    .as("generation-failed is an outcome as much as document-available is: the "
                            + "request was accepted, and the batch's ending is the listener's")
                    .isEqualTo(new BatchOutcome(BATCH_ID, BatchStatus.GENERATING, null, true));
        }

        @Test
        void a_document_that_landed_while_a_lost_202_was_retried_should_not_be_failed_over() {
            doThrow(transientFailure()).when(renderer).requestRender(any(), any());
            doThrow(refusedMove(BatchStatus.GENERATED, BatchStatus.FAILED))
                    .when(store).markFailed(any(), any(), any(), any());
            theStoreNowHolds(BatchStatus.GENERATED);

            final BatchOutcome outcome = request();

            softly.assertThat(outcome)
                    .as("the first attempt was accepted and its answer lost; the document it "
                            + "produced is the proof, so the exhausted retries fail nothing and "
                            + "the batch is answered as the accepted request it was")
                    .isEqualTo(new BatchOutcome(BATCH_ID, BatchStatus.GENERATING, null, true));
            softly.assertThat(batches(BatchStatus.FAILED))
                    .as("and it is not counted on the terminal series as a failure it never was")
                    .isEqualTo(ABSENT);
        }

        @Test
        void a_refused_mark_on_a_batch_nothing_moved_should_still_leave() {
            final IllegalStateException refused =
                    refusedMove(BatchStatus.PENDING, BatchStatus.GENERATING);
            doThrow(refused).when(store).markRequested(any(), any());
            theStoreNowHolds(BatchStatus.PENDING);

            softly.assertThat(whatStoppedTheRequest(batch()))
                    .as("no outcome explains this refusal, so it is the defect it always was and "
                            + "is not this leg's to absorb")
                    .isSameAs(refused);
        }

        @Test
        void a_generation_failure_that_landed_while_a_lost_202_was_retried_should_not_end_the_run() {
            doThrow(transientFailure()).when(renderer).requestRender(any(), any());
            doThrow(refusedMove(BatchStatus.FAILED, BatchStatus.FAILED))
                    .when(store).markFailed(any(), any(), any(), any());
            theStoreNowHolds(BatchStatus.FAILED);

            softly.assertThat(request())
                    .as("systemdocgenerator's own verdict reached the batch while this leg was "
                            + "still retrying the request it answered; that verdict stands, and "
                            + "this leg's own failure over it is moot")
                    .isEqualTo(new BatchOutcome(BATCH_ID, BatchStatus.GENERATING, null, true));
        }

        @Test
        void a_refused_mark_on_a_batch_this_service_failed_itself_should_still_leave() {
            final IllegalStateException refused =
                    refusedMove(BatchStatus.FAILED, BatchStatus.GENERATING);
            doThrow(refused).when(store).markRequested(any(), any());
            theStoreNowHolds(BatchStatus.FAILED, null);

            softly.assertThat(whatStoppedTheRequest(batch()))
                    .as("a FAILED row that names no event is this service's own verdict, which no "
                            + "outcome wrote, so nothing overtook the mark")
                    .isSameAs(refused);
        }

        @Test
        void a_refused_failure_on_a_batch_still_in_flight_should_still_leave() {
            doThrow(transientFailure()).when(renderer).requestRender(any(), any());
            final IllegalStateException refused =
                    refusedMove(BatchStatus.GENERATING, BatchStatus.FAILED);
            doThrow(refused).when(store).markFailed(any(), any(), any(), any());
            theStoreNowHolds(BatchStatus.GENERATING);

            softly.assertThat(whatStoppedTheRequest(batch()))
                    .as("the request left, but the batch is still waiting for its outcome, so the "
                            + "refused failure is not explained by one and is not answered "
                            + "GENERATING")
                    .isSameAs(refused);
        }

        @Test
        void a_store_that_cannot_say_what_refused_the_mark_should_end_the_request_carrying_it() {
            final IllegalStateException refused =
                    refusedMove(BatchStatus.GENERATED, BatchStatus.GENERATING);
            doThrow(refused).when(store).markRequested(any(), any());
            when(store.batchesNamed(List.of(BATCH_ID))).thenThrow(new StoreUnavailableException(
                    "the store could not be reached to read batches by identity",
                    new IllegalStateException("the connection pool is empty")));

            final Throwable stopped = whatStoppedTheRequest(batch());

            softly.assertThat(stopped)
                    .as("an outage is an outage wherever it lands, and ends the run as before")
                    .isInstanceOf(StoreUnavailableException.class);
            softly.assertThat(stopped.getSuppressed())
                    .as("with the refusal it was asked to explain kept on it as evidence")
                    .containsExactly(refused);
        }

        @Test
        void a_refused_failure_for_a_batch_that_was_never_sent_should_still_leave() {
            doThrow(new PayloadStoreUnavailableException("the file service did not answer"))
                    .when(payloadFileStore).store(any(), any(), any());
            final IllegalStateException refused =
                    refusedMove(BatchStatus.GENERATED, BatchStatus.FAILED);
            doThrow(refused).when(store).markFailed(any(), any(), any(), any());
            theStoreNowHolds(BatchStatus.GENERATED);

            softly.assertThat(whatStoppedTheRequest(batch()))
                    .as("no render left this service, so no outcome can have overtaken this "
                            + "batch's failure, whatever the row says")
                    .isSameAs(refused);
            verifyNoInteractions(renderer);
        }

        @Test
        void a_refused_mark_on_a_batch_the_store_no_longer_holds_should_still_leave() {
            final IllegalStateException refused =
                    refusedMove(BatchStatus.PENDING, BatchStatus.GENERATING);
            doThrow(refused).when(store).markRequested(any(), any());
            when(store.batchesNamed(List.of(BATCH_ID))).thenReturn(List.of());

            softly.assertThat(whatStoppedTheRequest(batch()))
                    .as("a batch with no row has no outcome to have been overtaken by")
                    .isSameAs(refused);
        }
    }

    /**
     * Defect fix P5, which is about what a run leaves behind rather than about what it does.
     *
     * <p>progression's {@code CourtRegisterHandler.processRequests} catches the stream exception,
     * writes one error line and continues; the batch it was building leaves no state on any row and
     * no count anywhere, so nothing downstream can tell a batch that failed from a batch that was
     * never there. The isolation was right and is kept. The silence is what is removed.
     */
    @Nested
    @DisplayName("a batch that could not be assembled into a payload (P5)")
    class WhenTheBatchCannotBeAssembled {

        @BeforeEach
        void refuseToMapThePayload() {
            when(payloadMapper.mapPayload(any()))
                    .thenThrow(new IllegalStateException("the batch could not be mapped"));
        }

        @Test
        void assembly_failure_fails_the_batch_and_the_run_continues() {
            when(store.batched(OTHER_BATCH_ID)).thenReturn(List.of(record("Ada Khan")));
            final RegisterGenerationService service = service();

            final BatchOutcome failed = request(service, batch(), farDeadline());

            verify(store).markFailed(BATCH_ID, BatchFailureReason.ASSEMBLY_FAILED, null, null);
            softly.assertThat(failed)
                    .as("a batch that cannot be turned into a payload is recorded FAILED under a "
                            + "bounded reason, which is the half progression never wrote down")
                    .isEqualTo(new BatchOutcome(BATCH_ID, BatchStatus.FAILED,
                            BatchFailureReason.ASSEMBLY_FAILED, false));
            softly.assertThat(batches(BatchStatus.FAILED))
                    .as("and counted, which is the other half: a failure nothing counts is a "
                            + "failure nobody is paged about")
                    .isEqualTo(1);

            // doReturn, not when(...).thenReturn: the mapper is currently stubbed to throw, and
            // when() would evaluate that stubbing here - in the arrangement of the second batch,
            // where the throw belongs to the first.
            doReturn(payload).when(payloadMapper).mapPayload(any());
            final BatchOutcome next = request(service, batch(OTHER_BATCH_ID), farDeadline());

            softly.assertThat(next)
                    .as("per-batch isolation is the legacy's useful half and is kept: one bad "
                            + "batch must not cost the estate a night's registers, so the verdict "
                            + "is returned to the run rather than thrown at it")
                    .isEqualTo(new BatchOutcome(OTHER_BATCH_ID, BatchStatus.GENERATING, null, true));
        }

        @Test
        void a_batch_that_could_not_be_assembled_should_never_reach_the_file_service() {
            request();

            verifyNoInteractions(payloadFileStore, renderer);
            verify(store, never()).markRequested(any(), any());
        }
    }

    /**
     * What a run is allowed to write down about a register every defendant on which is a child.
     *
     * <p>A "never", and deliberately the last word of the suite. The service holds whole register
     * documents in memory for the length of a batch, so the interpolation that would leak one is
     * always one field reference away; every value it puts in a line is the batch identity or a
     * bounded code, and an implementer who wants to log a defendant, a file's contents or another
     * system's words has to widen that deliberately (constitution Principle VII).
     */
    @Nested
    @DisplayName("what the run writes down")
    class WhatItWritesDown {

        @Test
        void nothing_at_info_or_above_should_carry_the_register_being_rendered() {
            when(store.batched(BATCH_ID))
                    .thenReturn(List.of(record(PersonalDataMarkers.CHILD_NAME)));

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationService.class)) {
                request();
                doThrow(refusal()).when(renderer).requestRender(any(), any());
                request(service(), batch(), farDeadline());

                softly.assertThat(atOrAbove(log, Level.INFO))
                        .as("the batch is a register about children; the run's own lines carry the "
                                + "batch identity and bounded codes, and a defendant's name is "
                                + "never one of them")
                        .noneMatch(line -> line.contains(PersonalDataMarkers.CHILD_NAME));
            }
        }
    }

    /**
     * The run's own accounting, reduced to what it is told: the batches announced, in order.
     *
     * <p>A list rather than a set, because what is asserted is how many times the leg spoke as well
     * as which batch it spoke about: a batch announced once per attempt would be counted three
     * times by a caller that trusted the announcement, and the list is what says it was announced
     * once.
     */
    private static final class RecordingProgress implements RenderProgress {

        private final List<UUID> announced = new ArrayList<>();

        @Override
        public void recordRenderAsked(final UUID batchId) {
            announced.add(batchId);
        }
    }

    /**
     * Records what the run would have waited and moves the clock by it, so the suite proves the
     * policy and the budget without living either.
     */
    private final class RecordingPause implements RetryPause {

        private final List<Duration> waits = new ArrayList<>();

        @Override
        public void pause(final Duration duration) {
            waits.add(duration);
            clock.advance(duration);
        }
    }
}
