package uk.gov.hmcts.cp.yotresultsdistribution.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision.Unreadable;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.SweepFailureReason;

/**
 * The instrument surface of the downstream half, declared in one place.
 *
 * <p>The counterpart of {@link ProcessingMetrics}, under the same two rules. Names and label sets
 * are fixed so the tests assert on them and the alert rules written later have a stable surface to
 * fire on; labels are low-cardinality enumerations only, so a batch id, a court centre id or a
 * recipient's address is never a series - that is both a cardinality explosion and, on a register
 * whose every defendant is a youth, a privacy breach.
 *
 * <p>Two of these answer questions nothing else in the flow can. A skipped run is counted by the
 * reason it was skipped, because "the flag is off" and "the flag could not be read" look identical
 * from outside and are not the same night; and what a run's first act had to give back is counted
 * in batches and in registers, because a batch is one document and one e-mail while a register is
 * one hearing's youth defendants and neither answers the other's question.
 *
 * <p>The eight gauges are the state a nightly flow cannot be understood without between runs: how
 * old the oldest unbatched record is, how long the oldest batch has been waiting for a document,
 * how long the oldest batch that never reached the renderer has been stuck, how long the oldest
 * batch holding a document nobody was told about has stood there, how many batches the
 * run deadline left behind, how many court centre days a run passed over and how many registers are
 * waiting under them, and whether the flag was readable at all. The last pair is deliberate: a
 * court centre day is one key however many children's registers are inside it, so the count of keys
 * and the count of registers are different questions and a dashboard wants both. Like {@link ProcessingMetrics}'s two, they are registered from construction,
 * because a dashboard must be able to read them from a pod that has not yet run.
 *
 * <p>Nothing here refuses a reading it does not recognise. Telemetry that threw would end the run it
 * was only supposed to describe, so every label is derived from a bounded enumeration - the
 * constant's own name, lower-cased and hyphenated, or the bounded code the decision carries - and
 * every state of every enumeration therefore has a series.
 *
 * <p>A component, exactly as {@link ProcessingMetrics} is, and unconditionally: the instruments
 * describe the downstream half, but a class Spring never constructs declares nothing at all, and
 * meters that appeared only once {@code yotresultsdistribution.generation.enabled} was set would be an
 * alerting surface that came and went with a deployment setting. {@code GenerationMetricsContextTest}
 * is what says the bean is there and that its meters land on the registry the service exports from.
 */
@Component
public class GenerationMetrics {

    public static final String BATCHES = "yotresultsdistribution_batches_total";
    public static final String GENERATION_REQUEST = "yotresultsdistribution_generation_request_total";
    public static final String GENERATION_LATENCY = "yotresultsdistribution_generation_latency";
    public static final String RELEASED_BATCHES = "yotresultsdistribution_generation_released_batches_total";
    public static final String RELEASED_REGISTERS =
            "yotresultsdistribution_generation_released_registers_total";
    public static final String RELEASE_CONTENDED = "yotresultsdistribution_generation_contended_total";
    public static final String GENERATION_SKIPPED = "yotresultsdistribution_generation_skipped_total";
    public static final String NOTIFICATIONS = "yotresultsdistribution_notifications_total";
    public static final String NOTIFICATIONS_IGNORED =
            "yotresultsdistribution_notifications_ignored_total";
    public static final String PUBLIC_EVENTS_IGNORED =
            "yotresultsdistribution_public_events_ignored_total";
    public static final String GENERATION_UNRECORDED =
            "yotresultsdistribution_generation_unrecorded_total";
    public static final String BATCH_SWEEP_FAILURES =
            "yotresultsdistribution_batch_sweep_failures_total";
    public static final String OLDEST_RECORDED_UNBATCHED_AGE =
            "yotresultsdistribution_oldest_recorded_unbatched_age";
    public static final String OLDEST_GENERATING_AGE = "yotresultsdistribution_oldest_generating_age";
    public static final String OLDEST_PENDING_AGE = "yotresultsdistribution_oldest_pending_age";
    public static final String OLDEST_GENERATED_AGE = "yotresultsdistribution_oldest_generated_age";
    public static final String PENDING_AFTER_DEADLINE = "yotresultsdistribution_pending_after_deadline";
    public static final String DEFERRED_KEYS = "yotresultsdistribution_deferred_keys";
    public static final String DEFERRED_REGISTERS = "yotresultsdistribution_deferred_registers";
    public static final String FLAG_READ_OK = "yotresultsdistribution_flag_read_ok";

    public static final String OUTCOME_TAG = "outcome";
    public static final String RESPONSE_CODE_TAG = "response_code";
    public static final String REASON_TAG = "reason";
    public static final String STATUS_TAG = "status";

    /**
     * The {@code response_code} label of an attempt nothing answered at all.
     *
     * <p>A bounded code rather than an absent label, so a connection that never got a status line
     * and a status line this service did not expect are two readings of the same series instead of
     * two series shapes.
     */
    public static final String NO_RESPONSE = "none";

    /**
     * The {@code reason} label of a public event another service asked for.
     *
     * <p>A bounded code, like every other label here: the event's own source is free text on the
     * wire and would be an unbounded series if it were carried through.
     */
    public static final String FOREIGN_SOURCE = "foreign-source";

    /**
     * The {@code reason} label of an outcome naming a batch this service never recorded.
     *
     * <p>Another consumer's document that carried this service's own source, one from a batch that
     * predates this store, or one of ours that named no batch at all. Zero is the expected reading,
     * and anything else is a correlation lost between the render request and the event.
     */
    public static final String UNKNOWN_CORRELATION = "unknown-correlation";

    /**
     * The {@code reason} label of an outcome of ours that names its batch and not its payload.
     *
     * <p>Separate from {@link #UNKNOWN_CORRELATION} because the two absences are different faults
     * and are read differently: that one is an announcement this service cannot attribute to any
     * batch at all, and this one names its batch perfectly well and leaves out the identifier the
     * correlation is cross-checked against. Counting it as an unknown correlation would put an
     * event whose correlation was never in doubt into the reading a lost correlation is chased by.
     */
    public static final String MISSING_PAYLOAD_ID = "missing-payload-id";

    /**
     * The {@code reason} label of an outcome whose two identifiers disagree.
     *
     * <p>The correlation names a batch this service does hold and the payload the event was
     * rendered from is not the payload that batch was requested for. Nothing is inferred from
     * either half: an inconsistent event is the one shape that could complete the wrong night's
     * registers, so it is counted here and applied nowhere.
     */
    public static final String PAYLOAD_MISMATCH = "payload-mismatch";

    /**
     * The {@code reason} label of an outcome of ours that is missing what it is an outcome about.
     *
     * <p>A {@code document-available} that names no document or no instant, and a
     * {@code generation-failed} that names no instant. Both identifiers are there and both check
     * out, so it is neither {@link #UNKNOWN_CORRELATION} nor {@link #MISSING_PAYLOAD_ID}: what is
     * missing is the announcement's own subject, which is a renderer or a broker to look at rather
     * than a correlation to go chasing. One reason for both shapes because they are one fault, with
     * the event's name on the WARN beside it for whoever reads further.
     *
     * <p>It reads nought on a healthy estate and it is the reading that says a batch went nowhere
     * for a reason nobody would otherwise see: nothing re-asks systemdocgenerator about a batch any
     * more, so an incomplete announcement is the whole of what was ever said about that render, and
     * the batch waits for the next run to give it back.
     */
    public static final String INCOMPLETE_OUTCOME = "incomplete-outcome";

    /**
     * The {@code reason} label of an outcome for a batch this service has already ended.
     *
     * <p>A {@code document-available} for a batch the run gave up on and released, or a refusal
     * for one already FAILED, or either for a batch whose teams have been told: the batch stands
     * in a state the machine draws no move out of, so it is left where it is and the outcome is
     * dropped. Until 004 that drop was a WARN and nothing else - the one
     * acknowledged-and-dropped path on the subscription that moved no counter, which the design
     * rules forbid.
     *
     * <p><strong>Ended, and not merely unmoved.</strong> A redelivered outcome for a batch
     * standing mid-journey where that outcome already put it - GENERATED, told a second time that
     * its document exists - is the redelivery a durable subscription is for, and it is not
     * counted here: it is expected, said at DEBUG, and nought is what this series has to read on a
     * healthy estate for an alert to be worth writing on it.
     *
     * <p>It is counted now because the drop is a <em>guarantee</em> rather than a curiosity: it is
     * what stops a Youth Offending Team being told twice about one court centre and register date
     * after a released batch's late outcome arrives (SC-003, SC-010). A guarantee that moves no
     * counter cannot be alerted on.
     *
     * <p><strong>Not one of the notification counter's late-* labels.</strong>
     * {@link #LATE_ACCEPTANCE_IGNORED} and {@link #LATE_FAILURE_IGNORED} are on
     * {@link #NOTIFICATIONS_IGNORED} and describe two notifiers racing over one recipient's row -
     * a different event entirely, one leg further on. Reusing them here would hide a rendering
     * fact inside a notification series.
     */
    public static final String TERMINAL_BATCH = "terminal-batch";

    /**
     * The {@code reason} label of a delivery whose body would not parse at all.
     *
     * <p>The four readings above are all taken from an envelope this service read: they say what a
     * readable announcement was about and why nothing could be done with it. This one is taken
     * before any of that, and it is the reading a broker feeding this subscription rubbish is seen
     * by - without it, a topic delivering nothing but unparseable bodies is indistinguishable on a
     * dashboard from a topic delivering nothing at all. Nothing of the body reaches the label: what
     * refused to parse is unvalidated text this service never asked for (Principle VII), so the
     * bounded reason is the whole of the series and the class that refused it stays on the line.
     */
    public static final String UNREADABLE_ENVELOPE = "unreadable-envelope";

    /**
     * The {@code reason} label of a delivery whose header and envelope name different events.
     *
     * <p>Its own series rather than a second reading of {@link #UNREADABLE_ENVELOPE}, because the
     * two are different faults with different owners: a body that will not parse is a publisher
     * writing malformed JSON, and a crossed pair is a message that parsed perfectly well and
     * contradicts itself - the selector matched on one name and the envelope carries another. Only
     * this one can be a broker routing on a header nobody kept in step with the payload, which is
     * the fault a subscription is re-declared over.
     */
    public static final String HEADER_ENVELOPE_MISMATCH = "header-envelope-mismatch";

    /**
     * The {@code reason} label of a run whose settled counts could not be read back.
     *
     * <p>{@code snapshot=unread} on the run line says the four settled counts are missing, and
     * before this series that word and a WARN were the whole of the signal: a night whose settled
     * read failed could not be alerted on, only found by somebody already reading the log index.
     * The run itself is unharmed - the batches are stamped and the renders are away by the time the
     * snapshot is taken - so this counts a report that came up short, never a night that did.
     *
     * <p>{@code snapshot=unread} is the <em>refused</em> read and nothing else. A run that takes no
     * settled reading at all - a regeneration an operator asked for, which the night is read back
     * for from {@code GET /operations/batches} - writes {@code snapshot=not-taken} and moves
     * nothing here, so the line and this series agree about what {@code unread} means and an alert
     * keyed on either does not fire on every regeneration.
     */
    public static final String SETTLED_SNAPSHOT = "settled-snapshot";

    /**
     * The {@code reason} label of a settled batch whose render round trip could not be timed.
     *
     * <p>The same gap one leg along: a lost sample leaves {@code yotresultsdistribution_generation_latency}
     * quietly under-counting, and a series that is under-counting looks exactly like a series that
     * is healthy. Counting the loss is what lets a dashboard say the latency reading is incomplete
     * rather than good. The one leg that takes the reading counts it here, and the label says how
     * many samples the series is missing rather than which leg missed them: a second leg that came
     * to take the reading would count the same loss under the same label.
     */
    public static final String LATENCY_SAMPLE = "latency-sample";

    /**
     * The {@code reason} label of a settlement that arrived after the row was already accepted.
     *
     * <p>A team that has been told has been told, so the write that would have demoted its row to
     * FAILED changes nothing - and something that changes nothing has to be visible, or the only
     * trace of two notifiers racing over one batch is a row that looks untouched. Zero is the
     * expected reading on a pod whose claim is doing its job.
     */
    public static final String LATE_FAILURE_IGNORED = "late-failure-ignored";

    /**
     * The {@code reason} label of an acceptance that arrived after the row was already accepted.
     *
     * <p>The other half of {@link #LATE_FAILURE_IGNORED}, and its own series because the two say
     * different things about the same window. A late refusal is an attempt that would have demoted a
     * team's row; a late acceptance is two notifiers that both got a 202 for one recipient, which is
     * a Youth Offending Team holding two copies of a register about children. Counting the second
     * under the first's reason would hide the worse of the two inside the reading for the milder one.
     */
    public static final String LATE_ACCEPTANCE_IGNORED = "late-acceptance-ignored";

    /**
     * The {@code reason} label of a settlement for a row the store no longer holds.
     *
     * <p>An invariant breach when met: the notifying leg only settles a row it read back or minted
     * itself, so this answer is never expected, and it is counted because it is the one result that
     * means the store lost a row this service wrote. Nought is the only reading a healthy pod produces, and it used to be indistinguishable
     * from {@link #LATE_FAILURE_IGNORED} - both were nought rows changed.
     */
    public static final String SETTLEMENT_ROW_ABSENT = "settlement-row-absent";

    /**
     * The {@code reason} label of a notifier that found the batch already claimed by another.
     *
     * <p>Not a failure: the outcome sink on a delivered {@code document-available} and an
     * operator's resend can reach one generated batch at the same moment, and the one that does not
     * get the claim has nothing left to do. It is counted so that a batch nobody can ever claim -
     * a claim left behind by a pod that died - reads as a series rather than as silence.
     */
    public static final String ALREADY_NOTIFYING = "already-notifying";

    /**
     * The {@code reason} label of a notifier that lost the batch's claim part way through the cycle.
     *
     * <p>Its own series and not a reading of {@link #ALREADY_NOTIFYING}, because that one is a
     * notifier that never got the claim and this one is a notifier that held it: whatever the rows
     * and the batch carry was written by two notifiers rather than one. How much of it this
     * notifier wrote is not fixed - the claim is renewed in front of every POST, so the refusal may
     * have come before the first of them or before the batch's own settlement, and zero or more
     * POSTs were really made. What it means is that the lease did not cover the work - more
     * recipients than it allows for, or a slower notificationnotify than it allows for - and it is
     * the reading {@code yotresultsdistribution.notification.claim-lease} is raised on.
     */
    public static final String CLAIM_LOST = "claim-lost";

    private static final int READABLE = 1;
    private static final int UNREADABLE = 0;

    private final MeterRegistry registry;

    /**
     * Gauge state, held here rather than read from a collaborator so the seven gauges exist from
     * construction: a nightly flow is read between runs as much as during one, and a gauge that
     * only appears after the first run is not an alerting surface.
     */
    private final AtomicLong oldestRecordedUnbatchedSeconds = new AtomicLong();
    private final AtomicLong oldestGeneratingSeconds = new AtomicLong();
    private final AtomicLong oldestPendingSeconds = new AtomicLong();
    private final AtomicLong oldestGeneratedSeconds = new AtomicLong();
    private final AtomicInteger pendingAfterDeadlineBatches = new AtomicInteger();
    private final AtomicInteger deferredCourtCentreDays = new AtomicInteger();

    private final AtomicInteger deferredRegistersWaiting = new AtomicInteger();

    /**
     * Whether the flag was readable, up until a read says otherwise - the honest starting position
     * for a pod that has not asked yet, and the same one {@code yotresultsdistribution_servicebus_up} takes.
     */
    private final AtomicInteger flagReadable = new AtomicInteger(READABLE);

    /**
     * Registers the downstream half's gauges against the given registry.
     *
     * @param registry the registry every instrument is registered against
     */
    public GenerationMetrics(final MeterRegistry registry) {
        this.registry = registry;

        Gauge.builder(OLDEST_RECORDED_UNBATCHED_AGE, oldestRecordedUnbatchedSeconds,
                        AtomicLong::doubleValue)
                .description("Age in seconds of the oldest record still waiting to be batched")
                .register(registry);
        Gauge.builder(OLDEST_GENERATING_AGE, oldestGeneratingSeconds, AtomicLong::doubleValue)
                .description("Age in seconds of the oldest batch still waiting for a document")
                .register(registry);
        Gauge.builder(OLDEST_PENDING_AGE, oldestPendingSeconds, AtomicLong::doubleValue)
                .description("Age in seconds of the oldest batch that never reached the renderer")
                .register(registry);
        Gauge.builder(OLDEST_GENERATED_AGE, oldestGeneratedSeconds, AtomicLong::doubleValue)
                .description("Age in seconds of the oldest batch holding a document nobody was "
                        + "told about")
                .register(registry);
        Gauge.builder(PENDING_AFTER_DEADLINE, pendingAfterDeadlineBatches,
                        AtomicInteger::doubleValue)
                .description("Batches a run ended without asking the renderer for")
                .register(registry);
        Gauge.builder(DEFERRED_KEYS, deferredCourtCentreDays, AtomicInteger::doubleValue)
                .description("Court centre days a run passed over, their earlier batch still in "
                        + "flight")
                .register(registry);
        Gauge.builder(DEFERRED_REGISTERS, deferredRegistersWaiting, AtomicInteger::doubleValue)
                .description("Registers waiting under the court centre days a run passed over")
                .register(registry);
        Gauge.builder(FLAG_READ_OK, flagReadable, AtomicInteger::doubleValue)
                .description("1 while the YotResultsDistributionService flag is readable, 0 while it is not")
                .register(registry);
    }

    /**
     * Counts a batch that reached a terminal state.
     *
     * @param outcome the state it ended in
     */
    public void batchCompleted(final BatchStatus outcome) {
        counter(BATCHES, OUTCOME_TAG, code(outcome)).increment();
    }

    /**
     * Counts a render request by what systemdocgenerator answered.
     *
     * @param responseCode the status line, which is a bounded dimension and the only one
     */
    public void generationRequested(final int responseCode) {
        counter(GENERATION_REQUEST, RESPONSE_CODE_TAG, String.valueOf(responseCode)).increment();
    }

    /**
     * Records how long a batch took from render request to outcome.
     *
     * @param latency request to outcome, however the outcome arrived
     */
    public void generationLatency(final Duration latency) {
        Timer.builder(GENERATION_LATENCY)
                .description("Time from the render request to the batch's outcome")
                .register(registry)
                .record(latency);
    }

    /**
     * Counts the batches one run gave up on and released.
     *
     * <p>The night's account of what it had to undo: a batch counted here is a court centre day
     * whose render outcome never arrived, and a series that is flat at zero is a topic delivering
     * every outcome it should. Every run moves it, by nought where it released nothing, so the
     * series exists to be alerted on from the first night rather than appearing the first time
     * something goes wrong.
     *
     * @param batches how many batches this run failed and released
     */
    public void staleBatchesReleased(final int batches) {
        counter(RELEASED_BATCHES).increment(batches);
    }

    /**
     * Counts the registers that came back with those batches.
     *
     * <p>Its own series rather than a label on the one above, because a batch is one document and
     * one e-mail while a register is one hearing's youth defendants: the count of batches says how
     * much of the estate a lost outcome cost and this says how many children's registers were in
     * it. Neither is added to the run's own row totals - the same run re-batches these registers,
     * so they are already inside them (FR-009).
     *
     * @param registers how many released registers are still the day's to render
     */
    public void staleRegistersReleased(final int registers) {
        counter(RELEASED_REGISTERS).increment(registers);
    }

    /**
     * Counts the batches a run could not release, having lost the day's key on every attempt.
     *
     * <p>A path that leaves something undone moves a counter: the batch is stale still and
     * untouched, the run goes on to assemble, and without this series the only trace of a court
     * centre day nothing can give back would be a WARN in the log index. Nought is the expected
     * reading, and anything that stays above it across runs is a day a person has to decide about.
     *
     * @param batches how many batches the pass left exactly as it found them
     */
    public void staleBatchesContended(final int batches) {
        counter(RELEASE_CONTENDED).increment(batches);
    }

    /**
     * Counts a batch-age refresh that could not be taken, under the reason it refused.
     *
     * <p>The generation half's copy of {@link ProcessingMetrics#intakeSweepFailure}, and its twin
     * for the same reason: the three readings are telemetry, and a round-trip reading that cannot
     * be taken may not cost a Youth Offending Team its e-mail, so the refusal stops where it
     * happens. This counter is what makes that an absorption rather than a swallow - the gauges
     * keep their last reading rather than dropping to a zero the store never said, and this series
     * says how long ago that reading was true.
     *
     * <p>Its own series and not the intake sweep's, because the two describe different halves of
     * the service running in different pods: a deployment with the generation half switched off
     * publishes one of them and not the other, and one counter for both would make a generating
     * pod's outage indistinguishable from an intake pod's.
     *
     * @param reason the bounded code this refresh is counted under
     */
    public void batchSweepFailure(final SweepFailureReason reason) {
        counter(BATCH_SWEEP_FAILURES, REASON_TAG, code(reason)).increment();
    }

    /**
     * Counts an outcome for a batch this service had already ended, which moved nothing.
     *
     * <p>The drop that stops a second e-mail. A batch the run released is FAILED, and the
     * {@code document-available} systemdocgenerator may still deliver for it must not re-stamp it:
     * its registers are in tonight's batch and that batch is what tells the court centre's Youth
     * Offending Teams. Nought is the expected reading, and a series that moves is the number of
     * times the guarantee was needed.
     */
    public void terminalBatchIgnored() {
        counter(PUBLIC_EVENTS_IGNORED, REASON_TAG, TERMINAL_BATCH).increment();
    }

    /**
     * Counts a public event that reached this service's subscription and belongs to somebody else.
     *
     * <p>The topic is the estate's, and progression's still-deployed leg renders through the same
     * systemdocgenerator: an outcome carrying another {@code originatingSource} is acknowledged and
     * dropped. It is counted rather than merely dropped because the number is how a subscription
     * that is hearing nothing of its own is told apart from one that is hearing nothing at all.
     */
    public void foreignEventIgnored() {
        counter(PUBLIC_EVENTS_IGNORED, REASON_TAG, FOREIGN_SOURCE).increment();
    }

    /**
     * Counts an outcome this service cannot attribute to a batch.
     *
     * <p>The sink's reading is an outcome for a batch identity this service has no record of; the
     * listener's is an event of ours that named no batch at all, which is the same fault one step
     * earlier - a correlation lost between the render request and the topic - and is counted here
     * rather than dropped in silence.
     *
     * <p>The same counter the foreign source is counted on, under its own bounded reason, because
     * the question they answer together is the one a night's outcomes going nowhere is read by:
     * how many announcements reached this subscription and were applied to nothing, and which of
     * the four ways it happened.
     */
    public void unknownCorrelationIgnored() {
        counter(PUBLIC_EVENTS_IGNORED, REASON_TAG, UNKNOWN_CORRELATION).increment();
    }

    /**
     * Counts an outcome of ours that names its batch and not the payload it was rendered from.
     *
     * <p>The correlation is there and the identifier it is cross-checked against is not, so there
     * is nothing to apply and nothing to check it with. Counted here rather than under
     * {@link #UNKNOWN_CORRELATION} because that reading is about a correlation this service cannot
     * place, and this event's correlation is exactly the one it asked for: what went missing is
     * systemdocgenerator's account of the payload, which is a renderer or a broker to look at and
     * not a batch to go looking for.
     */
    public void missingPayloadIdIgnored() {
        counter(PUBLIC_EVENTS_IGNORED, REASON_TAG, MISSING_PAYLOAD_ID).increment();
    }

    /**
     * Counts an outcome whose payload is not the payload its batch was requested for.
     *
     * <p>The reading that says an event contradicted itself. It is separate from
     * {@link #UNKNOWN_CORRELATION} because the two are different faults: the first is an
     * announcement about somebody else's work, and this one is an announcement about work this
     * service did that names the wrong artefact - which is a systemdocgenerator or a broker to
     * investigate rather than a subscription to widen.
     */
    public void payloadMismatchIgnored() {
        counter(PUBLIC_EVENTS_IGNORED, REASON_TAG, PAYLOAD_MISMATCH).increment();
    }

    /**
     * Counts an outcome of ours that is missing the thing it is an outcome about.
     *
     * <p>The correlation is this service's own and the payload cross-checks, so there is nothing
     * wrong with the announcement's addressing: what is absent is the document, or the instant the
     * batch's ending would be stamped with. It is dropped like the other two absences and counted
     * under a reason of its own, because the fault is systemdocgenerator publishing an incomplete
     * event and not a correlation this service lost.
     *
     * <p>Without the series the batch's whole story would be a WARN and a silence: the next run
     * gives the batch back under NOT_COMPLETED_BY_NEXT_RUN, and nothing anywhere would say that an
     * outcome for it had arrived and could not be used.
     */
    public void incompleteOutcomeIgnored() {
        counter(PUBLIC_EVENTS_IGNORED, REASON_TAG, INCOMPLETE_OUTCOME).increment();
    }

    /**
     * Counts a delivery whose body could not be parsed at all.
     *
     * <p>The four readings above are taken from an envelope this service read; this one is taken
     * where the read itself failed, and it is the only thing that separates a topic delivering
     * rubbish from a topic delivering nothing. The body does not reach the series - what would not
     * parse is unvalidated text (Principle VII) - so the bounded reason is the whole of it and the
     * class that refused stays on the line beside it.
     */
    public void unreadableEnvelopeIgnored() {
        counter(PUBLIC_EVENTS_IGNORED, REASON_TAG, UNREADABLE_ENVELOPE).increment();
    }

    /**
     * Counts a delivery whose {@code CPPNAME} and envelope name different events.
     *
     * <p>Its own reason rather than a reading of {@link #UNREADABLE_ENVELOPE}: that one is a
     * publisher writing malformed JSON, and this one is a message that parsed and contradicts
     * itself, which is a broker routing on a header nobody kept in step with the payload. Counting
     * them together would send a reader after JSON that was never malformed.
     */
    public void headerEnvelopeMismatchIgnored() {
        counter(PUBLIC_EVENTS_IGNORED, REASON_TAG, HEADER_ENVELOPE_MISMATCH).increment();
    }

    /**
     * Counts a run whose settled counts the store would not answer for.
     *
     * <p>The run line's {@code snapshot=unread} says the four counts are missing and the WARN
     * beside it names what refused, and both are read by somebody already looking. This is the
     * reading an alert fires on. It counts a report that came up short and never a night that did:
     * by the time the snapshot is taken the batches are stamped and the renders are away.
     */
    public void settledSnapshotUnrecorded() {
        counter(GENERATION_UNRECORDED, REASON_TAG, SETTLED_SNAPSHOT).increment();
    }

    /**
     * Counts a settled batch whose render round trip could not be timed.
     *
     * <p>{@link #GENERATION_LATENCY} under-counting looks exactly like {@link #GENERATION_LATENCY}
     * healthy - the count is lower and every reading in it is real - so the loss is counted here
     * and a dashboard can say the series is short rather than assume it is complete. The reading is
     * taken where an outcome is applied, which is one place, and what the label answers is how many
     * samples are missing rather than which leg missed them.
     */
    public void latencySampleUnrecorded() {
        counter(GENERATION_UNRECORDED, REASON_TAG, LATENCY_SAMPLE).increment();
    }

    /**
     * Counts a run that read the flag and did not generate.
     *
     * @param decision what the flag said, whose bounded code is the reason label
     */
    public void runSkipped(final FlagDecision decision) {
        counter(GENERATION_SKIPPED, REASON_TAG, decision.code()).increment();
    }

    /**
     * Counts one recipient's e-mail by how it was settled.
     *
     * @param status       ACCEPTED or FAILED
     * @param responseCode the status notificationnotify answered with, or {@code null} where nothing
     *                     answered at all
     */
    public void notificationSettled(final NotificationStatus status, final Integer responseCode) {
        Counter.builder(NOTIFICATIONS)
                .tag(STATUS_TAG, code(status))
                .tag(RESPONSE_CODE_TAG,
                        responseCode == null ? NO_RESPONSE : String.valueOf(responseCode))
                .register(registry)
                .increment();
    }

    /**
     * Counts a settlement the store refused because the recipient had already been told.
     *
     * <p>The one reading that says two notifiers raced over one batch and the row-level
     * compare-and-set caught it. Something that changed nothing has to be visible, or the only
     * trace is a row that looks untouched.
     */
    public void lateFailureIgnored() {
        counter(NOTIFICATIONS_IGNORED, REASON_TAG, LATE_FAILURE_IGNORED).increment();
    }

    /**
     * Counts an acceptance that arrived after the recipient's row was already accepted.
     *
     * <p>Its own series rather than a reading of {@link #lateFailureIgnored()}, because what it says
     * is worse: two notifiers each got a 202 for one recipient, so the team holds the register twice.
     */
    public void lateAcceptanceIgnored() {
        counter(NOTIFICATIONS_IGNORED, REASON_TAG, LATE_ACCEPTANCE_IGNORED).increment();
    }

    /**
     * Counts a settlement for a recipient row the store no longer holds.
     *
     * <p>Nought is the only reading a healthy pod produces: the notifying leg settles a row it read
     * back or minted itself, so anything here is a row this service wrote and the store has lost.
     */
    public void settlementRowAbsent() {
        counter(NOTIFICATIONS_IGNORED, REASON_TAG, SETTLEMENT_ROW_ABSENT).increment();
    }

    /**
     * Counts a notifier that found the batch already claimed by another and posted nothing.
     *
     * <p>Not a failure - the batch is being told by somebody else - and counted so that a batch
     * nobody can ever claim, its claim left behind by a pod that died, reads as a series rather
     * than as silence.
     */
    public void alreadyNotifying() {
        counter(NOTIFICATIONS_IGNORED, REASON_TAG, ALREADY_NOTIFYING).increment();
    }

    /**
     * Counts a notifier that lost the batch's claim part way through telling its recipients.
     *
     * <p>Separate from {@link #alreadyNotifying()}: that reading is a notifier that never started,
     * and this one made POSTs before the batch stopped being its own. It is the number the
     * notification lease is raised on, because what it says is that the lease did not cover the
     * work.
     */
    public void claimLost() {
        counter(NOTIFICATIONS_IGNORED, REASON_TAG, CLAIM_LOST).increment();
    }

    /**
     * Reports how old the oldest record still waiting to be batched is.
     *
     * <p>The reading that says a night was missed. A record that is never batched is invisible in
     * every counter here, because nothing happened to it.
     *
     * @param age the age of the oldest active, unbatched record, or {@link Duration#ZERO} where
     *            there is none
     */
    public void oldestRecordedUnbatchedAge(final Duration age) {
        oldestRecordedUnbatchedSeconds.set(age.toSeconds());
    }

    /**
     * Reports how long the oldest batch has been waiting for a document.
     *
     * @param age the age of the oldest batch still GENERATING, or {@link Duration#ZERO} where there
     *            is none
     */
    public void oldestGeneratingAge(final Duration age) {
        oldestGeneratingSeconds.set(age.toSeconds());
    }

    /**
     * Reports how long the oldest batch that never reached the renderer has been waiting.
     *
     * <p>A batch left PENDING with a payload id - the pod died between the render request and the
     * mark that records it, or the mark itself failed - moves no counter and appears in no other
     * gauge: {@link #OLDEST_GENERATING_AGE} reads
     * GENERATING only, and its registers are already stamped, so they are outside
     * {@code activeUnbatched} too. This is the reading that says so.
     *
     * @param age the age of the oldest stale PENDING batch, or {@link Duration#ZERO} where there is
     *            none
     */
    public void oldestPendingAge(final Duration age) {
        oldestPendingSeconds.set(age.toSeconds());
    }

    /**
     * Reports how long the oldest batch that holds a document nobody was told about has waited.
     *
     * <p>The third batch nothing else can see, and the third reading that exists because of one.
     * Notification follows the mark that records the document in one step of one code path, so a
     * store that went away in between - or a listener session that rolled the delivery back after
     * that mark had committed - leaves the batch at GENERATED with rows nothing settled.
     * {@link #OLDEST_GENERATING_AGE} reads GENERATING and {@link #OLDEST_PENDING_AGE} reads
     * PENDING, so without this the one state that leaves a Youth Offending Team untold is the one
     * state no reading moves for - defect fix P1's failure mode reached by another route.
     *
     * @param age the age of the oldest batch parked at GENERATED, or {@link Duration#ZERO} where
     *            there is none
     */
    public void oldestGeneratedAge(final Duration age) {
        oldestGeneratedSeconds.set(age.toSeconds());
    }

    /**
     * Reports how many batches the run deadline left unrequested.
     *
     * @param batches the number of batches a run ended without asking the renderer for
     */
    public void pendingAfterDeadline(final int batches) {
        pendingAfterDeadlineBatches.set(batches);
    }

    /**
     * Reports how many court centre days a run passed over.
     *
     * <p>The companion of {@link #OLDEST_RECORDED_UNBATCHED_AGE}, and not a substitute for it: the
     * age says how long the worst of them has waited and this says how much of the estate is
     * waiting. A key is deferred because a batch of its own is still in flight, so a reading that
     * stays up across nights is the schema's one-in-flight-batch-per-key rule holding a court centre
     * back rather than a run that failed.
     *
     * @param keys the number of court centre days this run assembled nothing for
     */
    public void deferredKeys(final int keys) {
        deferredCourtCentreDays.set(keys);
    }

    /**
     * Reports how many registers are waiting under the days a run passed over.
     *
     * <p>The impact reading beside {@link #DEFERRED_KEYS}'s count and
     * {@link #OLDEST_RECORDED_UNBATCHED_AGE}'s severity: one court centre day is one key however
     * many children's registers are inside it, so a count of keys cannot say how much is
     * undelivered. The run's line carries the same number as {@code rows_deferred}; this is the
     * reading between runs, when there is no line.
     *
     * @param registers the registers behind the deferred days
     */
    public void deferredRegisters(final int registers) {
        deferredRegistersWaiting.set(registers);
    }

    /**
     * Reports whether the last flag read produced an answer.
     *
     * <p>Separate from the skipped counter, which says how a run ended: this says whether App
     * Configuration is answering at all, and an off flag is an answer.
     *
     * @param decision what the last read produced
     */
    public void flagRead(final FlagDecision decision) {
        flagReadable.set(decision instanceof Unreadable ? UNREADABLE : READABLE);
    }

    /**
     * The bounded label a state is counted under: the constant's own name, lower-cased and
     * hyphenated, so every state of the enumeration has a series and none of them carries free text.
     *
     * @param state the enumerated state being counted
     * @return the label value for that state
     */
    private static String code(final Enum<?> state) {
        return state.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private Counter counter(final String name) {
        return Counter.builder(name).register(registry);
    }

    private Counter counter(final String name, final String tag, final String value) {
        return Counter.builder(name).tag(tag, value).register(registry);
    }
}
