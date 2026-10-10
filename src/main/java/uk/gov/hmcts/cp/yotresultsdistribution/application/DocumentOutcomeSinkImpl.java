package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletedBy;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterBatchRepository;

/**
 * The one place a rendering outcome becomes a batch state, whoever learned it.
 *
 * <p>Every outcome arrives here, which is the whole reason the port exists: one code path decides
 * what an outcome does to a batch and to that batch's rows, and one code path absorbs the duplicate
 * a durable subscription will eventually produce for the same batch.
 *
 * <p><strong>Scoped to the batch it was given.</strong> A document available for a batch flips that
 * batch's rows and no others, through {@code RegisterStore.markGenerated(batchId)}. Reaching for the
 * court centre and the day instead is exactly defect P3, where progression's completion flipped
 * every register for a court centre including the ones belonging to another day's batch.
 *
 * <p><strong>Defect fix P2 lands here.</strong> progression logs a {@code generation-failed} event
 * and records nothing, so a batch that systemdocgenerator refused is indistinguishable from one it
 * never answered about. Here it becomes a FAILED batch with the bounded reason GENERATION_FAILED,
 * carrying systemdocgenerator's own words in {@code sdg_reason} for support and never logging them
 * at INFO.
 *
 * <p><strong>The correlation is authoritative, and the payload has to agree with it.</strong>
 * {@code sourceCorrelationId} is the batch's own identity and is what the render request carried,
 * so it is the only identifier a batch is ever looked up by here. {@code payloadFileServiceId} is
 * a cross-check on that lookup and never a second route to a batch: the vendored schemas make the
 * correlation optional and the payload required, but this service always sends a correlation, so an
 * outcome that carries none - or one this store has no batch for - is not an outcome to go looking
 * for a batch for by other means. A read by payload would take an event whose own account of which
 * batch it is about is missing or wrong and complete a night's registers on it anyway, which is why
 * the repository offers none. The lookup is also what makes a redelivery idempotent - a batch
 * already where the outcome would put it is recognised rather than re-stamped, which is the reading
 * {@code BatchStatus} was narrowed to force.
 *
 * <p><strong>Four answers before a mark is made, and only one of them writes.</strong> An outcome
 * naming a correlation no batch answers to is counted and ignored; an outcome whose payload is not
 * the one its batch was requested for is counted and ignored, because an event that contradicts
 * itself is the one shape that could complete the wrong batch; an outcome for a batch already
 * standing where it would put it has nothing left to record - except that a document for a batch
 * still GENERATED is handed to the notifier again; and an outcome that would move a batch
 * along an arrow the data-model diagram does not draw - a document arriving for a batch the run
 * has already given up on and released, say - leaves that batch exactly where it is and is
 * reported
 * here instead. None of the four is silent: each says at what level it is worth reading, and only
 * the last three of them describe a batch this service actually holds.
 *
 * <p>The two that are ignored are counted on
 * {@code yotresultsdistribution_public_events_ignored_total{reason}} beside the listener's own
 * {@code foreign-source}, because together the three answer the question a night whose outcomes
 * went nowhere is read by: how many announcements reached this subscription and were applied to
 * nothing, and which of the three ways it happened.
 *
 * <p><strong>The mark is still the decision.</strong> The state read here is a read, and two
 * mechanisms can make it about one batch at the same moment; the store's own compare-and-set is
 * what settles that, refusing the second of two marks rather than letting both believe they moved
 * the batch. What this class does with the same question beforehand is keep the ordinary duplicate -
 * the one a durable subscription is for - from being reported as a refusal every night.
 *
 * <p><strong>And the mark is where the render's round trip is timed.</strong>
 * {@code yotresultsdistribution_generation_latency} is declared as the time from the render request to the
 * batch's outcome "however the outcome arrived", which is this class's whole subject: one code path
 * means one place the reading is taken, so every night reads on one series. Both instants come
 * off
 * the row - {@code requested_at} and whichever outcome stamp the mark wrote - because the pod that
 * asked for the render is not always this one. It is taken at the mark and before anything the mark
 * is followed by, and it can never cost the batch what follows: a reading nobody could take is a
 * gap in a histogram, said out loud, and not a register nobody was sent.
 *
 * <p><strong>Notification follows generation here, on the thread that learned of it.</strong> A
 * document that exists and has been sent to nobody is the state defect fix P1 is about, and the
 * moment the batch has one is the moment its Youth Offending Teams can be told; so the GENERATED
 * mark and {@link RegisterNotifierService#notify} are one step of one code path. It follows the
 * mark rather than replacing it, so the compare-and-set decides which of two racing deliveries
 * stamps the batch.
 *
 * <p><strong>And a batch still standing at GENERATED is handed on again (P1, amended).</strong> The
 * mark commits on its own transaction, so a notification that throws after it cannot be rolled
 * back with the event: the broker offers the event again and finds the batch already GENERATED.
 * A notification that finished moves the batch past GENERATED, so one still standing there may
 * have teams nobody has told, and the redelivery hands it to the notifier rather than
 * acknowledging it as already applied. One e-mail per team is the notifier's guarantee, not this
 * class's: its claim admits one caller per batch, a recipient keeps the one row it was minted,
 * and an ACCEPTED row is never asked for again.
 */
public class DocumentOutcomeSinkImpl implements DocumentOutcomeSink {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentOutcomeSinkImpl.class);

    /** Where a batch's outcome is written, one batch at a time. */
    private final RegisterStore store;

    /** The {@code register_batch} reads that say which batch an outcome is about. */
    private final RegisterBatchRepository batches;

    /** Where an outcome that is applied to nothing is counted, under its own bounded reason. */
    private final GenerationMetrics metrics;

    /** Who tells the batch's Youth Offending Teams, once the document is recorded as existing. */
    private final RegisterNotifierService notifier;

    /**
     * Creates the sink over the store it writes through and the batches it correlates against.
     *
     * @param store    where a batch's outcome is written, one batch at a time
     * @param batches  the {@code register_batch} reads that say which batch an outcome is about
     * @param metrics  where an outcome no batch takes is counted, by the reason it was not taken
     * @param notifier who tells the batch's recipients, on the same thread and immediately after
     *                 the mark that says the document exists
     */
    public DocumentOutcomeSinkImpl(final RegisterStore store,
            final RegisterBatchRepository batches, final GenerationMetrics metrics,
            final RegisterNotifierService notifier) {
        this.store = store;
        this.batches = batches;
        this.metrics = metrics;
        this.notifier = notifier;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The document's file-service id and the instant it was generated are what the batch had no
     * way of knowing until now, and the mechanism that learned them travels with the same mark: the
     * store writes {@code completed_by} in the statement that moves the batch, so there is no second
     * moment to write it in.
     *
     * <p>And then the recipients are told, in the same step and on this thread: after the mark,
     * which refuses where another mechanism has already moved the batch, or - for a batch the
     * announcement finds already GENERATED - instead of it, because a notification that finished
     * would have moved the batch on (P1, amended). One e-mail per team is the notifier's
     * guarantee in either case: its claim admits one caller per batch.
     *
     * <p><strong>The second hand-on is bounded.</strong> A failure on the first is rethrown and
     * the broker offers the event again, which is the retry. A failure on the second is rethrown
     * only where another delivery could clear it - a store outage; anything else would fail the
     * same way on every delivery of a durable subscription and hold the pod's one consumer in a
     * loop, so it is counted under {@code notification-not-retried}, said by class at WARN, and
     * acknowledged. The batch stays GENERATED, where its age gauge, the morning report and an
     * operator's resend find it.
     */
    @Override
    public void documentAvailable(final UUID correlationId, final UUID payloadFileId,
            final UUID documentFileId, final Instant generatedAt, final CompletedBy completedBy) {

        apply(correlationId, payloadFileId, BatchStatus.GENERATED,
                batch -> store.markGenerated(batch.batchId(), documentFileId, generatedAt,
                        completedBy))
                .ifPresent(this::handOn);
    }

    /**
     * Hands a batch with a document to the notifier: the first time after its mark, and again
     * where the announcement found it already GENERATED.
     *
     * @param batch the batch as it stood when the announcement was applied
     */
    private void handOn(final RegisterBatch batch) {
        if (batch.status() == BatchStatus.GENERATED) {
            handOnAgain(batch.batchId());
        } else {
            notifier.notify(batch.batchId());
        }
    }

    /**
     * The second hand-on, which is the one bounded: see {@link #documentAvailable}.
     *
     * @param batchId the batch the announcement found already GENERATED
     */
    // PMD.AvoidCatchingGenericException: whatever the notifier raises other than a store outage
    // is a failure no redelivery clears; a narrower catch would leave the classes it does not
    // name looping on the subscription.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void handOnAgain(final UUID batchId) {
        try {
            notifier.notify(batchId);
        } catch (StoreUnavailableException outage) {
            throw outage;
        } catch (RuntimeException notTold) {
            metrics.notificationNotRetried();
            LOG.warn("Batch {} could not be handed to the notifier again and another delivery "
                            + "would fail the same way, so the announcement is acknowledged and "
                            + "the batch stays GENERATED for an operator's resend. cause={}",
                    batchId, notTold.getClass().getName());
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The renderer's words are bounded where they cross into this service rather than only at
     * the row: {@link RegisterBatch#boundedReason(String)} is the one bound, and applying it here
     * means the store is handed a reason already the size its column admits. They are carried as an
     * argument and never logged - the batch's own reason is the bounded GENERATION_FAILED, and that
     * is what a log line and the counters get.
     *
     * <p>{@code failedAt} is the renderer's account of when it gave up and is not written: the
     * store stamps {@code failed_at} in the statement that fails the batch, so what the row records
     * is when this service learned of the refusal rather than another system's clock reading. It
     * stays on the port because the listener has it, and a port that dropped it would have to be
     * widened again by whatever wants it next.
     */
    @Override
    public void generationFailed(final UUID correlationId, final UUID payloadFileId,
            final String reason, final Instant failedAt, final CompletedBy completedBy) {

        apply(correlationId, payloadFileId, BatchStatus.FAILED,
                batch -> store.markFailed(batch.batchId(), BatchFailureReason.GENERATION_FAILED,
                        RegisterBatch.boundedReason(reason), completedBy));
    }

    /**
     * The one code path: find the batch, decide whether the outcome is news, and mark it if it is.
     *
     * <p>The mark itself is passed in rather than switched on here, so that what an outcome does to
     * a batch lives beside the method that received it and what an outcome has to survive to be
     * applied at all lives in exactly one place for both.
     *
     * <p>What it answers is the batch the mark actually moved, or a batch a document found already
     * GENERATED, so that telling the recipients is written where it cannot be reached any other
     * way.
     *
     * @param correlationId the batch identity the outcome named, which the contract allows to be
     *                      absent
     * @param payloadFileId the payload the outcome is about, which the contract requires
     * @param outcome       the state this outcome would put the batch in
     * @param mark          the store call that puts it there
     * @return the batch this outcome moved or found still GENERATED, or empty
     */
    private Optional<RegisterBatch> apply(final UUID correlationId, final UUID payloadFileId,
            final BatchStatus outcome, final Consumer<RegisterBatch> mark) {

        final Optional<RegisterBatch> named = find(correlationId);
        if (named.isEmpty()) {
            countUnattributed(correlationId, payloadFileId);
        }
        return named.flatMap(
                batch -> applyToTheBatchItNamed(batch, payloadFileId, outcome, mark));
    }

    /**
     * Applies the outcome to the batch the correlation named, if the payload agrees that it is
     * about it.
     *
     * <p>The batch's stored {@code payload_file_id} is what the render request asked
     * systemdocgenerator for, and the event's {@code payloadFileServiceId} is what it says it
     * rendered. Where they differ - including where the batch has no payload id at all, because it
     * never reached the renderer - the event is not this batch's, and there is nothing in it worth
     * believing about any other batch either. It moves nothing and is counted.
     *
     * @param batch         the batch the correlation named, as it stood when it was read
     * @param payloadFileId the payload the outcome is about
     * @param outcome       the state this outcome would put the batch in
     * @param mark          the store call that puts it there
     * @return the batch this outcome moved or found still GENERATED, or empty where the two
     *         identifiers disagree
     */
    private Optional<RegisterBatch> applyToTheBatchItNamed(final RegisterBatch batch,
            final UUID payloadFileId, final BatchStatus outcome,
            final Consumer<RegisterBatch> mark) {

        final Optional<RegisterBatch> marked;
        if (batch.payloadFileId() == null || !batch.payloadFileId().equals(payloadFileId)) {
            countPayloadMismatch(batch, payloadFileId);
            marked = Optional.empty();
        } else {
            marked = applyTo(batch, outcome, mark);
        }
        return marked;
    }

    /**
     * What an outcome does to the batch it was attributed to: nothing, nothing, or the mark.
     *
     * <p>The branches in the order they are worth reading. A document for a batch already
     * GENERATED is not re-stamped but is answered with the batch, so the caller hands it to the
     * notifier again - see the class's note on why. Any other batch already standing where the
     * outcome would put it is the redelivery a durable subscription is for, and it is at DEBUG
     * because it is expected. A move the state machine does not draw is not: it is a late outcome
     * for a batch already ended - a document for one the run gave up on and released, or a second
     * refusal for one already FAILED under a different reason - and re-stamping it would take
     * systemdocgenerator's verdict about one identity and attach it to a batch that has moved past
     * it.
     *
     * <p><strong>That third branch is counted, and the counter is the guarantee.</strong> It is
     * what stops a Youth Offending Team being told twice: the registers a released batch held are
     * in tonight's batch, and tonight's batch is what tells the court centre. Until 004 the drop
     * was a WARN and moved nothing - the one acknowledged-and-dropped path on this subscription
     * without a bounded reason, which the design rules forbid and which no alert and no end-to-end
     * case could see. The reason is {@code terminal-batch} on
     * {@code yotresultsdistribution_public_events_ignored_total}, beside the six the listener already
     * moves, and deliberately not one of the notification counter's {@code late-*} labels: those
     * describe two notifiers racing over one recipient's row, one leg further on, and reusing them
     * here would hide a rendering fact inside a notification series.
     *
     * <p>The reading is taken in the one branch that marked anything, immediately after the mark
     * and before whatever the caller does with the answer. The mark is what closes the round trip,
     * so the reading belongs to it: taken any later it would be lost whenever the step after it
     * threw, this batch standing where the outcome put it by then and every redelivery being
     * answered without a re-stamp. Taking it here costs nothing on the redelivery path, because
     * neither of the branches a redelivery reaches marks anything.
     *
     * @param batch   the batch the outcome was attributed to, as it stood when it was read
     * @param outcome the state this outcome would put it in
     * @param mark    the store call that puts it there
     * @return the batch this outcome moved or found still GENERATED, or empty where it was left
     *         where it stood
     */
    private Optional<RegisterBatch> applyTo(final RegisterBatch batch, final BatchStatus outcome,
            final Consumer<RegisterBatch> mark) {

        final Optional<RegisterBatch> marked;
        if (batch.status() == outcome && outcome == BatchStatus.GENERATED) {
            // Not re-stamped, but handed on: a notification that finished moves the batch past
            // GENERATED, so one still standing here may have teams nobody has told.
            LOG.info("Batch {} already stands at {}, so it is not re-stamped; it is handed to the "
                    + "notifier, which tells only the teams still owed an e-mail.",
                    batch.batchId(), outcome);
            marked = Optional.of(batch);
        } else if (batch.status() == outcome) {
            countIfAlreadyEnded(batch);
            LOG.debug("Batch {} already stands at {}, so the outcome that has just arrived for it "
                    + "again is recognised rather than re-stamped.", batch.batchId(), outcome);
            marked = Optional.empty();
        } else if (batch.status().canTransitionTo(outcome)) {
            mark.accept(batch);
            timeTheRoundTrip(batch.batchId());
            marked = Optional.of(batch);
        } else {
            countIfAlreadyEnded(batch);
            LOG.warn("Batch {} stands at {} and an outcome arrived that would move it to {}, which "
                    + "the state machine does not draw; the batch is left where it is and the "
                    + "outcome is reported here rather than applied.",
                    batch.batchId(), batch.status(), outcome);
            marked = Optional.empty();
        }
        return marked;
    }

    /**
     * Counts an outcome that arrived for a batch this service had already ended.
     *
     * <p><strong>Ended, and not merely unmoved.</strong> Both branches that call this leave the
     * batch where it stands, and only an ended batch is the drop FR-008 is about. A batch
     * mid-journey is not counted (a {@code document-available} for one still GENERATED does not
     * reach here at all: it is handed to the notifier again). A batch in a
     * state the machine draws no move out of is the other thing: its night is over, its registers
     * are in tonight's batch if a run gave them back, and the outcome now arriving is exactly what
     * would otherwise send a Youth Offending Team a second register for one court centre and
     * register date (SC-003, SC-010).
     *
     * <p>Terminal is asked of the machine rather than listed here, because the list is the
     * machine's: a state it draws no move out of is a state nothing can follow, and a second copy
     * of that list in this class would be a copy to forget to update.
     *
     * @param batch the batch the outcome was attributed to, as it stood when it was read
     */
    private void countIfAlreadyEnded(final RegisterBatch batch) {
        final boolean ended = Arrays.stream(BatchStatus.values())
                .noneMatch(batch.status()::canTransitionTo);
        if (ended) {
            metrics.terminalBatchIgnored();
        }
    }

    /**
     * Times how long the render this outcome answers took, off the row it has just settled.
     *
     * <p>After the mark and never before it, which is what makes the reading the outcomes this
     * service actually applied: {@code JdbcRegisterStore} refuses a move the batch has already made
     * by throwing, so a compare-and-set that lost a race never reaches here and one render is timed
     * once however many mechanisms announce it. And immediately after it, before anything the mark
     * is followed by: a reading taken after the recipients had been told would be lost whenever
     * telling them threw, because the batch stands at GENERATED from the mark on and the
     * redelivery that follows is recognised rather than re-stamped.
     *
     * <p><strong>And it may not cost the batch anything, which is the one thing this class
     * absorbs.</strong> Both halves of the reading can refuse - the row is read out of a store that
     * can be away, and the timer is Micrometer's, which raises on a measurement it will not take -
     * and neither is the batch's failure. Passed on, the refusal would reach the listener, which
     * would roll its delivery back and have the broker offer an outcome already applied. So it
     * stops here and is written down instead: the batch and the class of what refused
     * are what a gap in the series is diagnosed from, and neither is about a person. Every other
     * refusal still leaves, the store's compare-and-set included, because those say the outcome was
     * not applied.
     *
     * <p>The row is read back rather than assembled from what arrived, because the two instants the
     * reading is made of are columns and only one of them was ever in this method's hands:
     * {@code failed_at} is stamped by the statement that fails the batch, not by the renderer's
     * account of when it gave up. {@link RegisterBatch#generationRoundTrip()} is the whole of the
     * rule - which instants, and when there is no reading to take - and it is stated in one place
     * because there is one place an outcome is applied.
     *
     * <p>A batch the read no longer finds, or one whose row carries no round trip, moves the timer
     * nowhere. Nothing is reported about either: a settled batch that cannot be read back a moment
     * later is what {@code SETTLEMENT_ROW_ABSENT} is for on the notifying side, and it is not this
     * method's to claim on the strength of a telemetry read.
     *
     * @param batchId the batch whose outcome has just been written
     */
    // PMD.AvoidCatchingGenericException: the claim is that no failure of this reading can cost the
    // batch what follows it, and that is only worth anything if it holds for every way the reading
    // can fail - the store read, the rule, and Micrometer's own refusal of a measurement. A
    // narrower catch would leave the classes a list did not name suppressing the notification,
    // which is the defect being fixed rather than a smaller version of it.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void timeTheRoundTrip(final UUID batchId) {
        try {
            batches.findById(batchId)
                    .flatMap(RegisterBatch::generationRoundTrip)
                    .ifPresent(metrics::generationLatency);
        } catch (RuntimeException notTimed) {
            metrics.latencySampleUnrecorded();
            LOG.warn("Batch {} was settled and its render round trip could not be timed, so this "
                    + "outcome is missing from yotresultsdistribution_generation_latency, counted on "
                    + "yotresultsdistribution_generation_unrecorded_total and missing from nothing else: "
                    + "the mark stands and the leg carries on from it. cause={}",
                    batchId, notTimed.getClass().getName());
        }
    }

    /**
     * Counts and reports an outcome no batch in this store answers to.
     *
     * <p>Both identifiers are in the line because which of the two was carried is half of what a
     * lost correlation looks like, and neither is about a defendant, a recipient or a register
     * (constitution Principle VII).
     *
     * @param correlationId the batch identity the outcome named, or {@code null} where it named none
     * @param payloadFileId the payload the outcome is about
     */
    private void countUnattributed(final UUID correlationId, final UUID payloadFileId) {
        metrics.unknownCorrelationIgnored();
        LOG.warn("An outcome arrived for correlation {} and payload {}, which this service has no "
                + "batch for, so it is counted and ignored: it is another consumer's document, or "
                + "one from a batch that predates this store. The payload is a cross-check on the "
                + "correlation and not a second way to a batch, so no other lookup is made.",
                correlationId, payloadFileId);
    }

    /**
     * Counts and reports an outcome whose two identifiers do not describe one batch.
     *
     * <p>At WARN because it is not an ordinary event: the correlation is a batch this service
     * really did ask for, so this is not somebody else's document reaching the subscription - it is
     * this service's own render being announced against the wrong artefact, or an event whose
     * fields have been crossed between the renderer and the topic. Both identifiers are in the
     * line, and both are file-service and batch identities rather than anything about a person
     * (constitution Principle VII).
     *
     * @param batch         the batch the correlation named
     * @param payloadFileId the payload the outcome says it is about
     */
    private void countPayloadMismatch(final RegisterBatch batch, final UUID payloadFileId) {
        metrics.payloadMismatchIgnored();
        LOG.warn("An outcome named batch {}, which was requested for payload {}, and says it is "
                + "about payload {}; the two disagree, so the batch is left where it is and the "
                + "outcome is counted rather than applied.",
                batch.batchId(), batch.payloadFileId(), payloadFileId);
    }

    /**
     * The batch an outcome is about, by the identity it named and by nothing else.
     *
     * <p>{@code sourceCorrelationId} is the batch's own identity and is what this service put in
     * the render request, so it is the whole of the lookup. The vendored schemas make it optional,
     * which is why {@code null} is answered rather than refused - an outcome carrying no
     * correlation is contract-legal and is still not one this service can attribute, because it
     * always sends one.
     *
     * @param correlationId the batch identity the outcome named, or {@code null}
     * @return the batch, or empty where the correlation is absent or names none
     */
    private Optional<RegisterBatch> find(final UUID correlationId) {
        return correlationId == null ? Optional.empty() : batches.findById(correlationId);
    }
}
