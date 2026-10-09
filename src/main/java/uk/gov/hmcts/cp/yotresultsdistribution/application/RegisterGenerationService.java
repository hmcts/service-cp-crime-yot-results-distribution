package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPause;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletedBy;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.Deadline;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GenerationFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.PayloadStoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterRecord;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RenderRequest;
import uk.gov.hmcts.cp.yotresultsdistribution.pipeline.PdfPayloadMapper;

/**
 * One batch, from the payload to the render request.
 *
 * <p>The order is the same discipline the intake half keeps: the payload file id is minted and
 * persisted on the batch row <em>before</em> the file-service insert, and the insert happens before
 * the render request, so nothing downstream is ever asked about an identifier this service has not
 * already written down. An id minted and not recorded is a document that comes back attributable to
 * nothing.
 *
 * <p>Each step has one failure and one bounded reason: a payload that could not be stored fails the
 * batch PAYLOAD_STORE_UNAVAILABLE and leaves its rows RECORDED for the next run to re-assemble, a
 * request systemdocgenerator answered with anything but 202 fails it RENDER_REQUEST_REJECTED, and a
 * transient failure is retried inside the run deadline and then fails it RENDER_REQUEST_FAILED.
 * Nothing is inferred from silence: a 202 moves the batch to GENERATING, which says a render was
 * asked for and not that a document exists.
 *
 * <p>The service returns when the request has been accepted or has failed. It never waits for the
 * document, which arrives on the public-event topic and is applied through
 * {@link DocumentOutcomeSink}, so a slow render cannot delay the next batch of the run.
 *
 * <p><strong>The retry loop is here rather than in the renderer</strong>, which is the opposite of
 * where 001 put it: {@link DocumentRenderer} names no deadline, because a run's budget is the run's
 * knowledge and not systemdocgenerator's, so the client classifies one attempt through the shared
 * {@link RetryPolicy} and this decides whether there is room for another. The taxonomy is still
 * stated once (defect fix C3); only the loop moved to the object that holds the budget.
 *
 * <p><strong>Defect fix P5: the verdict is returned, never thrown.</strong> progression's
 * {@code CourtRegisterHandler.processRequests} catches the stream exception, writes one error line
 * and walks on to the next batch, so a batch that failed and a batch that was never there are
 * indistinguishable from outside - no state on any row, no count anywhere. The isolation was the
 * right half and is kept: a batch that cannot be turned into a payload at all is failed FAILED
 * {@code ASSEMBLY_FAILED}, counted, and handed back to the run as an outcome, so the next batch of
 * the night is still asked for and the run report can name the one that was not.
 *
 * <p>Every line this class writes carries the batch identity and bounded codes. The service holds
 * whole register documents in memory for the length of a batch and every defendant on one is a
 * child, so a document, a defendant or another system's words never reach a line at INFO or above
 * (constitution Principle VII).
 */
// PMD.OnlyOneReturn: each step of the sequence answers where it is decided. The order the three
// writes happen in is the whole subject of this class, and a single exit would mean carrying a
// half-made verdict past the steps that must not run once it exists - which is the shape that loses
// a document.
@SuppressWarnings("PMD.OnlyOneReturn")
public class RegisterGenerationService {

    private static final Logger LOG = LoggerFactory.getLogger(RegisterGenerationService.class);

    /**
     * The key progression's generator reads the batch's documents from.
     *
     * <p>The port was written against the shape progression's own {@code CourtRegisterGenerated}
     * event carried, so the wrapper is spelled the way that event spelled it; any other spelling is
     * a payload the mapper reads as empty.
     */
    private static final String COURT_REGISTER_DOCUMENT_REQUESTS = "courtRegisterDocumentRequests";

    /** The systemdocgenerator template, unchanged from progression. */
    private static final String TEMPLATE_IDENTIFIER = "OEE_Layout5";

    /** The output format, which is what is asked for and what the metadata row records. */
    private static final String CONVERSION_FORMAT = "pdf";

    /** This service's own name, which is what keeps progression's still-deployed listener out. */
    private static final String ORIGINATING_SOURCE = "YotResultsDistributionService";

    /** The page count progression writes, which is one. */
    private static final int NUMBER_OF_PAGES = 1;

    /** The status the contract admits, and the only one; recorded as the request's dimension. */
    private static final int ACCEPTED_STATUS = 202;

    private final RegisterStore store;
    private final PdfPayloadMapper payloadMapper;
    private final PayloadFileStore payloadFileStore;
    private final DocumentRenderer renderer;
    private final ObjectMapper objectMapper;
    private final RetryPolicy retryPolicy;
    private final RetryPause pause;
    private final GenerationMetrics metrics;
    private final Clock clock;

    /**
     * Creates the service over the one store, the one payload leg and the one renderer.
     *
     * @param registerStore     where the batch and its registers are read and written
     * @param mapper            progression's payload generator, ported
     * @param fileStore         the framework file service the payload is written to
     * @param documentRenderer  systemdocgenerator, behind the port that names no status code
     * @param json              the shared mapper, which decides what the payload's bytes are and
     *                          therefore what the metadata's {@code fileSize} is
     * @param policy            the shared retry policy: attempts, back-off, and what one attempt
     *                          can cost against the run's budget
     * @param retryPause        how a wait between attempts is taken
     * @param generationMetrics the downstream half's instruments
     * @param runClock          this pod's reading of now, compared only with this run's own deadline
     */
    public RegisterGenerationService(final RegisterStore registerStore,
            final PdfPayloadMapper mapper, final PayloadFileStore fileStore,
            final DocumentRenderer documentRenderer, final ObjectMapper json,
            final RetryPolicy policy, final RetryPause retryPause,
            final GenerationMetrics generationMetrics, final Clock runClock) {
        this.store = registerStore;
        this.payloadMapper = mapper;
        this.payloadFileStore = fileStore;
        this.renderer = documentRenderer;
        this.objectMapper = json;
        this.retryPolicy = policy;
        this.pause = retryPause;
        this.metrics = generationMetrics;
        this.clock = runClock;
    }

    /**
     * Stores one batch's payload and asks for it to be rendered.
     *
     * @param batch    the assembled batch, already durable at PENDING
     * @param deadline the run's requesting bound; an attempt whose own worst case does not fit
     *                 inside what is left is not started
     * @param progress told the moment the renderer is asked about this batch, so a caller counting
     *                 the renders a night sent does not have to wait for an outcome to learn that
     *                 one left
     * @return what the batch ended the requesting leg as, for the run report
     */
    public BatchOutcome request(final RegisterBatch batch, final Deadline deadline,
            final RenderProgress progress) {
        return assemble(batch)
                .map(assembled -> storeAndRequest(batch, assembled, deadline, progress))
                .orElseGet(() -> failed(batch, BatchFailureReason.ASSEMBLY_FAILED, false));
    }

    /**
     * Reads the batch's registers back and turns them into the payload and its metadata.
     *
     * <p>Before anything is minted or written, which is what keeps an assembly failure from leaving
     * a payload id on a row that names no payload. A batch holding no registers is an assembly
     * failure of the same kind: there is nothing to render, and the mapper would answer an empty
     * header rather than say so.
     *
     * @param batch the batch being asked about
     * @return the payload and the metadata that goes beside it, or empty where the batch could not
     *         be assembled into one at all (defect fix P5)
     */
    // PMD.AvoidCatchingGenericException: P5 is about what a run leaves behind, and it is only worth
    // anything if it holds for every way the assembly can fail - the store read, the ported
    // generator, the serialisation of what it answered. A narrower catch would leave the classes
    // this list does not name failing the run instead of the batch.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private Optional<Assembled> assemble(final RegisterBatch batch) {
        try {
            final List<RegisterRecord> registers = store.batched(batch.batchId());
            if (registers.isEmpty()) {
                throw new IllegalStateException(
                        "batch " + batch.batchId() + " holds no registers to render");
            }
            final JsonNode payload = payloadMapper.mapPayload(wrap(registers));
            return Optional.of(new Assembled(payload,
                    new PayloadMetadata(registers.getFirst().fileName(), CONVERSION_FORMAT,
                            TEMPLATE_IDENTIFIER, NUMBER_OF_PAGES, sizeOf(payload))));
        } catch (RuntimeException notAssembled) {
            LOG.error("Batch {} could not be assembled into a payload, so it is failed {} and the "
                    + "run continues to the next batch. cause={}", batch.batchId(),
                    BatchFailureReason.ASSEMBLY_FAILED, notAssembled.getClass().getName());
            // No dump of the failure, at any level. What the mapper raised is about a document
            // whose every defendant is a child, and Principle VII puts a register fragment behind
            // DEBUG *and* an explicit local-only profile guard - this statement had the first and
            // never the second, so what it wrote was a fragment in a deployed log index. The
            // bounded reason above is what a reader gets; reproducing the mapper's own words needs
            // the payload, which is in the file service and is reached deliberately.
            LOG.debug("The assembly failure for batch {} was {}.", batch.batchId(),
                    notAssembled.getClass().getName());
            return Optional.empty();
        }
    }

    /**
     * Mints the payload id, writes it down, stores the payload under it and asks for the render.
     *
     * <p>The three writes in the one order that cannot lose a document: an id used before it is
     * recorded is a document that comes back correlated to nothing this service can find, and a
     * render asked for before the payload is stored is a render of a file that is not there yet.
     *
     * @param batch     the batch being asked about
     * @param assembled its payload and the metadata that goes beside it
     * @param deadline  the run's requesting bound
     * @param progress  told where the render is asked for, which is past both of these writes
     * @return what the batch ended the requesting leg as
     */
    private BatchOutcome storeAndRequest(final RegisterBatch batch, final Assembled assembled,
            final Deadline deadline, final RenderProgress progress) {

        final UUID payloadFileId = UUID.randomUUID();
        store.markPayloadMinted(batch.batchId(), payloadFileId);

        try {
            payloadFileStore.store(payloadFileId, assembled.payload(), assembled.metadata());
        } catch (PayloadStoreUnavailableException unavailable) {
            // The reason slot carries the batch's own bounded code, which is what an index filter
            // and an alert query key on. The store's phrase is one of five it wrote itself - which
            // of them says whether this was the network, the row count or the serialisation, and
            // that distinction survives nowhere else - so it stays on the line as prose, where free
            // text is what a sentence is made of and nothing parses it as a value.
            LOG.error("The payload for batch {} was not stored, so no render is asked for and its "
                    + "registers stay RECORDED for the next run: {}. reason={}", batch.batchId(),
                    unavailable.getMessage(), BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE);
            return failed(batch, BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE, false);
        }
        return askForRender(batch, payloadFileId, deadline, progress);
    }

    /**
     * Asks systemdocgenerator for the render, for as long as the run's budget holds an attempt.
     *
     * <p>The loop branches on the classification the client gave and never on the type of what it
     * threw: NON_TRANSIENT means another attempt at the same request cannot answer differently, and
     * waiting to prove it costs the run's budget. Neither the attempt nor the wait is started
     * unless it fits - an attempt is measured against its own worst case, because a connect that
     * hangs to its timeout and then a read that hangs to its own is the longest single thing this
     * class does.
     *
     * <p><strong>Whether the call was made is tracked here and carried on the outcome</strong>, not
     * inferred afterwards from the reason. Both the first check below and the exhausted budget end
     * the batch RENDER_REQUEST_FAILED, and only this loop knows the difference between a batch that
     * was sent and answered nothing and a batch this run reached with too little left to start a
     * single attempt. The run report counts the renders a night asked for
     * ({@code BatchOutcome.renderRequested}), and counting the second would report a renderer
     * refusing a document it was never sent.
     *
     * <p><strong>And it is announced as well as carried, because the outcome can fail to
     * arrive.</strong> The verdict is returned only after the batch's ending has been written down,
     * and the store can go away on that write: {@link #renderAccepted} and {@link #failed} both
     * leave through a throw then, and a caller counting the outcomes it was handed would report a
     * night that sent nothing while this render was away. So {@link RenderProgress} is told the
     * moment before the call is made, once per batch - a batch retried inside the budget is one
     * render and not three - and the flag that says the request left this service is set in the
     * same statement, because they are the same fact about the same batch.
     *
     * @param batch         the batch being asked about
     * @param payloadFileId the id the payload was stored under, which is what is rendered
     * @param deadline      the run's requesting bound
     * @param progress      told the moment before the renderer is asked, and once per batch
     * @return GENERATING where the request was accepted, and FAILED under its bounded reason
     *         otherwise
     */
    private BatchOutcome askForRender(final RegisterBatch batch, final UUID payloadFileId,
            final Deadline deadline, final RenderProgress progress) {

        final RenderRequest request = new RenderRequest(payloadFileId, batch.batchId(),
                TEMPLATE_IDENTIFIER, CONVERSION_FORMAT, ORIGINATING_SOURCE);
        final int maxAttempts = retryPolicy.maxAttempts();
        boolean sent = false;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!retryPolicy.attemptFitsBefore(clock.instant(), deadline.expiresAt())) {
                // The attempt this pass would have made has not been made, so what is reported is
                // the ones before it: none at all where the batch arrived with too little budget
                // left for a single attempt.
                return overran(batch, attempt - 1, sent);
            }
            try {
                if (!sent) {
                    // Said here rather than after the answer, and once for the batch rather than
                    // once per attempt: from the next statement on, the request has left this
                    // service whatever comes back and whatever this service manages to write down.
                    sent = true;
                    progress.recordRenderAsked(batch.batchId());
                }
                // A scheduled run is nobody's request: no message named a user, so the call is made
                // under the configured system identity.
                renderer.requestRender(request, CallerIdentity.SYSTEM);
                return renderAccepted(batch, payloadFileId);
            } catch (GenerationFailedException notAccepted) {
                count(notAccepted.responseCode());
                if (notAccepted.classification() != FailureClassification.TRANSIENT) {
                    LOG.error("systemdocgenerator refused the render request for batch {}, so it "
                            + "is failed {} without being asked again.", batch.batchId(),
                            notAccepted.reason());
                    return failed(batch, notAccepted.reason(), sent);
                }
                if (attempt < maxAttempts) {
                    final Duration wait = retryPolicy.waitAfter(attempt, Optional.empty());
                    if (!clock.instant().plus(wait).isBefore(deadline.expiresAt())) {
                        return overran(batch, attempt, sent);
                    }
                    if (!waitFor(batch, wait)) {
                        return failed(batch, BatchFailureReason.RENDER_REQUEST_FAILED, sent);
                    }
                }
            }
        }
        LOG.error("The render request for batch {} was not delivered in {} attempts, so it is "
                + "failed {} and its registers go back to the next run.", batch.batchId(),
                maxAttempts, BatchFailureReason.RENDER_REQUEST_FAILED);
        return failed(batch, BatchFailureReason.RENDER_REQUEST_FAILED, sent);
    }

    /**
     * Records the accepted request, which says a render was asked for and nothing more.
     *
     * <p>The batch is not counted here. {@code yotresultsdistribution_batches_total} is the terminal-outcome
     * series, and counting a batch on the way past would make every accepted request look like a
     * finished one and hide the batches that never came back.
     *
     * @param batch         the batch whose render was accepted
     * @param payloadFileId the payload the accepted request was about
     * @return the outcome the run counts this batch under for now
     */
    private BatchOutcome renderAccepted(final RegisterBatch batch,
            final UUID payloadFileId) {
        metrics.generationRequested(ACCEPTED_STATUS);
        try {
            store.markRequested(batch.batchId(), payloadFileId);
        } catch (IllegalStateException refused) {
            return overtaken(batch, refused);
        }
        LOG.info("systemdocgenerator accepted the render request for batch {}, which now waits for "
                + "its document on the public-event topic.", batch.batchId());
        return accepted(batch);
    }

    /**
     * Answers a batch whose mark the store refused because an outcome got to it first.
     *
     * <p><strong>Defect fix P5's isolation, held against the listener.</strong> This
     * leg is not the only writer of a batch between PENDING and GENERATING: the public-event
     * listener runs beside the run on every pod, so a render systemdocgenerator finishes before its
     * 202 arrives - or a request whose 202 was lost while this leg retried - is marked GENERATED or
     * FAILED by the listener first, and the mark made here is then one the state machine refuses.
     * Thrown, that refusal ended the night for every court centre behind this one.
     *
     * <p>An outcome exists only for a request systemdocgenerator accepted, so a batch that has one
     * is answered as the accepted request it was - GENERATING, the requesting leg's own view - and
     * what it came to is the settled snapshot's to report, read from the store like every other
     * batch the listener finished during the run. Nothing is counted on the terminal series here,
     * exactly as nothing is when the listener's outcome follows a mark that landed: a document is
     * counted by the notifier when the batch's teams have been told, and a {@code generation-failed}
     * ending is counted by neither.
     *
     * <p>The refusal is asked about rather than assumed, and only an ending an outcome writes is
     * taken as one: GENERATED or anything after it, or FAILED by an event. A batch still PENDING
     * or GENERATING, one failed by this service's own verdict, or one with no row at all was not
     * overtaken by anything, so the refusal is the defect it always was and is rethrown unchanged.
     * A store that cannot be read here leaves the request as an outage anywhere else in it does,
     * carrying the refusal it was asked about.
     *
     * @param batch   the batch whose mark was refused
     * @param refused what the store raised
     * @return GENERATING, where an outcome is what refused the mark
     * @throws IllegalStateException the refusal itself, where no outcome explains it
     */
    // PMD.AvoidCatchingGenericException: the read-back is asked only to explain a refusal, and
    // whatever stops it - the store's own outage type or anything the driver raised - leaves
    // unchanged; the catch exists only to keep the refusal on it as evidence.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private BatchOutcome overtaken(final RegisterBatch batch, final IllegalStateException refused) {
        final List<RegisterBatch> held;
        try {
            held = store.batchesNamed(List.of(batch.batchId()));
        } catch (RuntimeException unread) {
            unread.addSuppressed(refused);
            throw unread;
        }
        final BatchStatus now = held.stream()
                .findFirst()
                .filter(RegisterGenerationService::endedByAnOutcome)
                .map(RegisterBatch::status)
                .orElseThrow(() -> refused);
        LOG.info("The outcome for batch {} reached it before this run's own mark, so the batch "
                + "stands at {} and the run continues to the next batch.", batch.batchId(), now);
        return accepted(batch);
    }

    /**
     * Whether a batch stands where only an outcome event can have put it.
     *
     * <p>GENERATED and every state after it are written only once a document exists. FAILED is
     * written by this class as well, so it counts only where the row names the event as the
     * mechanism that learned it.
     *
     * @param batch the batch as the store holds it
     * @return whether an outcome is what moved it
     */
    private static boolean endedByAnOutcome(final RegisterBatch batch) {
        return switch (batch.status()) {
            case PENDING, GENERATING -> false;
            case FAILED -> batch.completedBy() == CompletedBy.EVENT;
            case GENERATED, NOTIFIED, PARTIALLY_NOTIFIED, NOTIFIED_NOBODY -> true;
        };
    }

    /**
     * The requesting leg's answer for a render systemdocgenerator accepted.
     *
     * @param batch the batch whose render was accepted
     * @return GENERATING, with the request recorded as having left this service
     */
    private static BatchOutcome accepted(final RegisterBatch batch) {
        return new BatchOutcome(batch.batchId(), BatchStatus.GENERATING, null, true);
    }

    /**
     * Takes the wait between two attempts.
     *
     * <p>An interrupt is restored and the batch given up on rather than swallowed: the thread has
     * been asked to stop, and a run that carried on asking would be a run outliving the shutdown
     * that ended it. The batch is failed under the reason its registers go back to the next run
     * with, which is the same thing an exhausted attempt budget leaves behind.
     *
     * @param batch the batch being asked about
     * @param wait  how long the shared policy said to wait
     * @return whether the wait completed and another attempt may be made
     */
    private boolean waitFor(final RegisterBatch batch, final Duration wait) {
        boolean waited = true;
        try {
            pause.pause(wait);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while waiting to ask for batch {}'s render again, so it is "
                    + "failed {} and no further attempt is made.", batch.batchId(),
                    BatchFailureReason.RENDER_REQUEST_FAILED);
            waited = false;
        }
        return waited;
    }

    /**
     * Fails a batch the run had no budget left to finish asking for.
     *
     * <p>Its own line rather than the exhausted-attempts one, because the two are different
     * investigations: a run that ran out of night is a scheduling question and a request that was
     * refused three times is a systemdocgenerator question. The bounded reason is the same, because
     * what the batch is owed is the same - re-assembly by the next run.
     *
     * <p>It says how many attempts had been made, and the outcome says whether any of them was: a
     * run that reached this batch with less budget left than one attempt costs at worst has asked
     * systemdocgenerator nothing, and a run that ran out between attempts has asked it already.
     *
     * <p><strong>Made, and never about to be made.</strong> The number is completed attempts, so
     * the guard before the first call reports none and the guard between two calls reports the one
     * that was answered. The wording is identical on both nights and this is the only thing on the
     * line that tells them apart - short of night, or a renderer slow to answer - so a number one
     * too high sends the reader to the wrong service.
     *
     * @param batch    the batch that was not asked for
     * @param attempts how many attempts had been made when the budget ran out
     * @param sent     whether a request had already left this service
     * @return the failed outcome
     */
    private BatchOutcome overran(final RegisterBatch batch, final int attempts,
            final boolean sent) {
        LOG.error("The run deadline would not hold another render attempt for batch {}, so it is "
                + "failed {} after {} attempts.", batch.batchId(),
                BatchFailureReason.RENDER_REQUEST_FAILED, attempts);
        return failed(batch, BatchFailureReason.RENDER_REQUEST_FAILED, sent);
    }

    /**
     * Writes the batch's ending down, counts it, and hands the run the verdict.
     *
     * <p>{@code sdgReason} and {@code completedBy} are both absent, and that is the rule rather than
     * an omission: the four reasons this class can produce are its own verdict about a render it
     * could not ask for, so none of them is generator-attributed and none of them carries another
     * system's words ({@link BatchFailureReason#isGeneratorAttributed()}).
     *
     * <p><strong>Whether the renderer had been asked is passed in rather than read off the
     * reason</strong>, because two of the four cannot be read that way. RENDER_REQUEST_FAILED is
     * the ending of a request that was made and answered nothing and of a batch no attempt could be
     * started for, and the caller is the only thing that knows which of those it is.
     *
     * <p>A batch an outcome has already reached is not failed over it: a document that came back
     * for a request whose 202 was lost proves the request was accepted, so the refusal the store
     * raises for that batch is answered by {@link #overtaken} rather than thrown.
     *
     * @param batch  the batch that failed
     * @param reason the bounded reason it is failed under
     * @param sent   whether the render request had left this service by then
     * @return the failed outcome, for the run report
     */
    private BatchOutcome failed(final RegisterBatch batch, final BatchFailureReason reason,
            final boolean sent) {
        try {
            store.markFailed(batch.batchId(), reason, null, null);
        } catch (IllegalStateException refused) {
            // Only a request that left this service can have an outcome to be overtaken by.
            if (!sent) {
                throw refused;
            }
            return overtaken(batch, refused);
        }
        metrics.batchCompleted(BatchStatus.FAILED);
        return new BatchOutcome(batch.batchId(), BatchStatus.FAILED, reason, sent);
    }

    /**
     * Counts an attempt by what systemdocgenerator answered, where it answered.
     *
     * <p>An attempt nothing answered at all has no status to record, and an invented one would say
     * the attempt reached a verdict when it did not.
     *
     * @param responseCode the status the failure carried, where it carried one
     */
    private void count(final OptionalInt responseCode) {
        if (responseCode.isPresent()) {
            metrics.generationRequested(responseCode.getAsInt());
        }
    }

    /**
     * The batch's documents in the shape the ported generator was written against.
     *
     * @param registers the batch's registers, in the order the batch holds them
     * @return an object carrying them under {@code courtRegisterDocumentRequests}
     */
    private JsonNode wrap(final List<RegisterRecord> registers) {
        final ArrayNode documents = objectMapper.createArrayNode();
        for (final RegisterRecord register : registers) {
            documents.add(objectMapper.valueToTree(register.document()));
        }
        final ObjectNode wrapped = objectMapper.createObjectNode();
        wrapped.set(COURT_REGISTER_DOCUMENT_REQUESTS, documents);
        return wrapped;
    }

    /**
     * The payload's size in bytes, as the shared mapper writes it.
     *
     * <p>The same mapper the adapter serialises with, so the metadata row describes the content row
     * beside it rather than a number nobody derived.
     *
     * @param payload the payload about to be stored
     * @return its size in bytes
     */
    private long sizeOf(final JsonNode payload) {
        return objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * What a batch was assembled into: the payload, and the metadata row that goes beside it.
     *
     * @param payload  the payload the ported generator produced, passed on unchanged
     * @param metadata progression's five keys, as progression spells them
     */
    private record Assembled(JsonNode payload, PayloadMetadata metadata) {
    }
}
