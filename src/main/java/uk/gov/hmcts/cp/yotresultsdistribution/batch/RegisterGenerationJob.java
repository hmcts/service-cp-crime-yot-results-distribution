package uk.gov.hmcts.cp.yotresultsdistribution.batch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import uk.gov.hmcts.cp.yotresultsdistribution.application.BatchOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterGenerationService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterStore;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RenderProgress;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationProperties;
import uk.gov.hmcts.cp.yotresultsdistribution.config.RunProgress;
import uk.gov.hmcts.cp.yotresultsdistribution.config.SchedulingConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.AssembledBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchAssembly;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtCentreDay;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.Deadline;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision.Proceed;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision.Reason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision.Skipped;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterRecord;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunReport;

/**
 * The nightly run: flag, assemble, request, report.
 *
 * <p>The order is the whole of it. {@link FeatureFlagGate} is asked first and its answer ends the
 * run where it is not ON, because a run that assembled before it read the flag would have stamped
 * rows into batches the flag says this service may not generate (constitution Cutover Rule). Then
 * one batch at a time, sequentially, each through {@link RegisterGenerationService}; the run
 * deadline bounds the requesting and nothing else, because completion arrives on the public-event
 * topic long after the run has ended.
 *
 * <p><strong>Sequential is not an efficiency choice.</strong> Each request is a write to the shared
 * file service followed by a POST, and a run that fanned them out would make the run deadline
 * unenforceable and the file-service datasource's readiness reading meaningless. The deadline is
 * computed once, at the moment requesting begins, so a bound re-derived per batch cannot grow by
 * whatever the batch before it took; a batch it leaves no time for is left PENDING for the next run
 * rather than failed, because nothing has gone wrong with it.
 *
 * <p><strong>The release is the run's own first act.</strong> Before anything is read,
 * {@link StaleBatchReleaser} fails every batch that was still awaiting its render when this run
 * began and gives its registers back, so the assembly below picks them up and the court centre gets
 * its document tonight. The order is the point: a pass after the assembly would release into a
 * night that had already been decided. It happens on the nights the run happens and on no others -
 * a run the flag stopped releases nothing, because a service that may not generate may not decide
 * that a batch it would not be allowed to re-render has failed, and the batches an earlier ON night
 * left in flight wait for the first night the flag says ON again (FR-005, FR-018). Nothing is asked
 * of systemdocgenerator between runs: an outcome that was lost is not an outcome anybody can be
 * asked for.
 *
 * <p>Every run produces a {@link RunReport}, the skipped ones included: a report that only appeared
 * when work happened would make "the flag is off" and "the job did not fire" the same silence, and
 * before cutover the first of those is every night. The report is written as one structured line at
 * INFO and as the three gauges a nightly flow is read by between runs; every value in it is a count,
 * a duration or a bounded code, and no register, defendant or recipient reaches it (constitution
 * Principle VII). The keys the assembler deferred are among those counts, and they have to be: a
 * court centre whose day was passed over has no batch in the outcomes and no document tonight, so a
 * report without that number describes the night as if it had not happened.
 *
 * <p><strong>18:00 in the courts' own zone, and one of it.</strong> The schedule names the two
 * settings rather than repeating their values, so the hour this class runs at and the hour
 * {@code application.yaml} declares cannot drift apart, and {@link GenerationProperties#validate()}
 * is what holds the zone to {@code Europe/London}: the requirement is 18:00 wall clock in BST and
 * GMT alike, and the legacy fires in the scheduling JVM's default zone because its Quartz trigger
 * was built without one (research §4). The lock is the same argument about replicas rather than
 * hours: one replica today is a deployment fact and not a code guarantee, and the cost of being
 * wrong is two documents and two e-mails for every court centre in the country. Its duration is
 * named the same way the schedule is, as the setting that states it, because it is the run deadline
 * plus a fixed margin and a literal here would be that relationship written down twice.
 */
public class RegisterGenerationJob {

    /**
     * How long the lock is held for, named as the setting that says it rather than as a value.
     *
     * <p>An annotation's attribute has to be a constant, but it does not have to be a duration:
     * ShedLock resolves a property placeholder here through the context's own value resolver, so
     * the lock reads {@code yotresultsdistribution.generation.lock-at-most-for} and there is one place the
     * duration is written. That matters because {@code run-deadline} is configurable and this has
     * to outlast it - a deployment that lengthened the run past a literal seventy minutes would
     * leave a window in which a run still inside its hour has already lost the lock, and the
     * replica that takes it generates the same night a second time.
     *
     * <p>{@code application.yaml} ships it as the deadline plus the fixed
     * {@link uk.gov.hmcts.cp.yotresultsdistribution.config.PropertiesValidator#SCHEDULER_LOCK_MARGIN}, and
     * {@code PropertiesValidator} refuses startup on any pair that does not hold - so the two
     * settings cannot drift apart in a deployment either.
     */
    public static final String LOCK_AT_MOST_FOR = "${yotresultsdistribution.generation.lock-at-most-for}";

    /** The lock's own name, which is what makes it this job's lock and not the estate's. */
    public static final String LOCK_NAME = "register-generation";

    private static final Logger LOG = LoggerFactory.getLogger(RegisterGenerationJob.class);

    /** The one event name the run's line is indexed under. */
    private static final String RUN_EVENT = "register_generation_run";

    /** What the line calls a run that went ahead, and one that did not. */
    private static final String PROCEEDED = "proceed";

    private static final String SKIPPED = "skipped";

    /** What the line calls the reading a run goes ahead on when nobody overrode anything. */
    private static final String FLAG_ON = "flag-on";

    private final FeatureFlagGate gate;

    private final RegisterStore store;

    private final BatchAssembler assembler;

    private final RegisterGenerationService service;

    private final StaleBatchReleaser releaser;

    private final GenerationMetrics metrics;

    private final GenerationProperties properties;

    private final Clock clock;

    private final RunProgress runProgress;

    /**
     * Creates the run over the collaborators it asks in order, with no readiness to move.
     *
     * <p>The run a unit test builds. A deployment always uses the constructor below, because the
     * file service gates readiness for exactly as long as a run is in progress and only the run
     * knows when that is.
     *
     * @param gate       the one lever, asked first and before anything is read or assembled
     * @param store      the register store, for the records this run may batch
     * @param assembler  the grouping into one batch per court centre and register date
     * @param service    the requesting leg, asked once per batch and sequentially
     * @param releaser   the run's first act: the batches the night before did not finish
     * @param metrics    the instrument surface a nightly flow is read by between runs
     * @param properties the settings the run works to, the run deadline above all
     * @param clock      the run's own clock, which the deadline and the report's duration are
     *                   measured on
     */
    public RegisterGenerationJob(final FeatureFlagGate gate, final RegisterStore store,
            final BatchAssembler assembler, final RegisterGenerationService service,
            final StaleBatchReleaser releaser, final GenerationMetrics metrics,
            final GenerationProperties properties, final Clock clock) {
        this(gate, store, assembler, service, releaser, metrics, properties, clock,
                RunProgress.NONE);
    }

    /**
     * Creates the run a deployment schedules, which says while it is going on that it is.
     *
     * @param gate        the one lever, asked first and before anything is read or assembled
     * @param store       the register store, for the records this run may batch
     * @param assembler   the grouping into one batch per court centre and register date
     * @param service     the requesting leg, asked once per batch and sequentially
     * @param releaser    the run's first act: the batches the night before did not finish
     * @param metrics     the instrument surface a nightly flow is read by between runs
     * @param properties  the settings the run works to, the run deadline above all
     * @param clock       the run's own clock, which the deadline and the report's duration are
     *                    measured on
     * @param runProgress what the run tells the pod while it is in progress, so that the file
     *                    service gates readiness for that long and no longer
     */
    public RegisterGenerationJob(final FeatureFlagGate gate, final RegisterStore store,
            final BatchAssembler assembler, final RegisterGenerationService service,
            final StaleBatchReleaser releaser, final GenerationMetrics metrics,
            final GenerationProperties properties, final Clock clock,
            final RunProgress runProgress) {
        this.gate = gate;
        this.store = store;
        this.assembler = assembler;
        this.service = service;
        this.releaser = releaser;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
        this.runProgress = runProgress;
    }

    /**
     * Runs one generation, from the flag read to the report.
     *
     * <p>The flag is read before anything else and its answer ends the run: a skipped run touches
     * neither the releaser, the store, the assembler nor the service, because a run that read the
     * store first would already have stamped {@code batch_id} onto rows the flag says this service
     * may not generate - and getting them back is a person's decision about one batch at a time
     * (the release behind {@code generate-register}), not something a later run can undo.
     *
     * <p><strong>A run that stops part way still reports.</strong> The store can go away under the
     * release pass, and between the read and the stamp, and a run that left through one of those
     * without writing its line would be the one night that produced no report at all -
     * the night that stamped batches and asked for renders and then said nothing about how far it
     * got, which is worse than the silence the report exists to abolish. So the line is written
     * from what the run had done and the failure is then rethrown: reported <em>and</em> rethrown,
     * because a failure that is only logged about has not been settled (constitution Principle VI)
     * and both the schedule and the operations command decide what to do next from the throw.
     *
     * <p><strong>The last thing it does is read back what its own night has come to.</strong> The
     * batches this run assembled are read from the store as the report is made, so the line carries
     * how many of them have a document and how many the notifying leg has finished with -
     * {@link #settled}. That read is the one thing here that is not allowed to end the run: a report
     * that cannot be assembled is not a reason to lose a night's generation, and the line says the
     * counts are unread rather than reporting zeroes as facts.
     *
     * @return what the run did, including a run the flag stopped
     */
    // PMD.AvoidCatchingGenericException: what has to be reported is a run that stopped, whatever
    // stopped it - a store outage arrives as the store's own unchecked type, a refused stamp as
    // IllegalStateException - and a narrower catch would leave the classes it does not name as the
    // nights that report nothing. Nothing is swallowed: the same throwable leaves the method.
    // PMD.OnlyOneReturn: the two exits are the two nights - one the flag stopped and one it allowed
    // - and each reports where it ends; funnelling them through one would put the report after a
    // branch that has to be able to say which of the two it is describing.
    @Scheduled(cron = "${yotresultsdistribution.generation.cron}",
            zone = "${yotresultsdistribution.generation.zone}",
            scheduler = SchedulingConfig.GENERATION_SCHEDULER)
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = LOCK_AT_MOST_FOR)
    public RunReport run() {
        return RunCorrelation.under(this::correlatedRun);
    }

    /**
     * The run itself, under the correlation {@link #run()} opened for it.
     *
     * <p>Separate so that every exit - the gate's, the deadline's and the failure - leaves through
     * the same removal, rather than each of them having to remember to take the id away. The work
     * is handed to {@link RunCorrelation} rather than the scope handed back here for that reason:
     * the scheduler's threads are pooled, and there is no way to call it and forget the finally.
     *
     * @return what the run did
     */
    // PMD.AvoidCatchingGenericException and PMD.OnlyOneReturn: as stated above this method's
    // opening comment, which describes this body - the two exits are the two nights, and the wide
    // catch is what stops a night reporting nothing.
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
    private RunReport correlatedRun() {
        final Instant startedAt = clock.instant();
        final GateDecision decision = gate.decide(false);

        if (decision instanceof Skipped) {
            return recorded(new RunReport(decision, Map.of(), 0, Map.of(), 0, 0,
                    RunReport.Settled.NOTHING_ASSEMBLED, 0, 0, 0, sinceStart(startedAt)),
                    RunCorrelation.current());
        }
        // Only from here on is the file service anything readiness should have an opinion
        // about, and it stops being one however the run ends.
        final RunTally tally = new RunTally();
        runProgress.recordRunStarted();
        try {
            generate(tally);
            return recorded(tally.reportOf(decision, settled(tally), sinceStart(startedAt)),
                    RunCorrelation.current());
        } catch (RuntimeException stopped) {
            LOG.error("The run did not finish, so the line beside this one describes what it had "
                            + "done rather than a night that completed. cause={}",
                    stopped.getClass().getName());
            recorded(tally.reportOf(decision, settled(tally), sinceStart(startedAt)),
                    RunCorrelation.current());
            throw stopped;
        } finally {
            // Once, at the end, however the run ended - and only for what the run actually learned.
            publish(tally);
            runProgress.recordRunEnded();
        }
    }

    /**
     * The night the flag allowed: release, read, assemble, then request one batch at a time.
     *
     * <p>Everything it learns goes into the tally as it learns it rather than into a report built
     * at the end, because a run that stopped half way through has still learned the first half and
     * the report is the only place that says so.
     *
     * <p>The release is first and nothing is read before it. A batch still awaiting its render when
     * this run began is failed and its registers are given back inside the pass, so the read below
     * answers with them and the court centre day the assembler would otherwise have passed over is
     * no longer in flight. A store lost under the pass ends the run the way a store lost anywhere
     * else does - reported on the line and rethrown - and one batch the pass could not give back
     * ends nothing at all, which is the store's own rule and is counted rather than raised
     * (FR-003a).
     *
     * @param tally what the run has done, filled in as it goes
     */
    private void generate(final RunTally tally) {
        // Handed over rather than assigned from the return: a store lost under the pass leaves
        // through the throw, and a run that took the account from the return would then write
        // released_batches=0 beside the pass's own line saying it had given batches back.
        releaser.releaseStale(tally::released);

        final List<RegisterRecord> active = store.activeUnbatched();
        // The history the supplementary rule is decided from (design Q27): a key with a batch still
        // in flight is left waiting, and a key whose batches are all terminal may be followed by a
        // supplement at the next index. A run that read nothing here would make every late re-share
        // a day's first document all over again.
        final List<RegisterBatch> recorded = store.batchesFor(keysOf(active));
        // True because this is the schedule asking. The operations CLI assembles the same way and
        // says false, which is progression's own flag and is written to the batch row.
        tally.assembled(active, assembler.assemble(active, recorded, true));

        request(tally);
    }

    /**
     * The three gauges the run's own half of the instrument surface carries.
     *
     * <p>Published once, at the end of the run, and only where the run got far enough to have read
     * them: a run that stopped before it assembled does not know that no court centre day was
     * passed over, and a zero from it would erase the reading that says one has been waiting for
     * nights. A run that did assemble knows all three - how much of the estate it passed over, how
     * long the worst of those has waited and how many batches its deadline left behind - whether or
     * not it got to the end of the requesting.
     *
     * @param tally what the run had done when it ended, however it ended
     */
    private void publish(final RunTally tally) {
        final BatchAssembly assembly = tally.assembly();
        if (assembly != null) {
            metrics.oldestRecordedUnbatchedAge(oldestStillWaiting(tally.active(), assembly));
            metrics.deferredKeys(assembly.deferred().size());
            metrics.deferredRegisters(tally.registersWaiting);
            metrics.pendingAfterDeadline(tally.leftBehind());
        }
    }

    /**
     * The court centres and days this run holds registers for, each named once.
     *
     * <p>In the order the store answered, because everything downstream of the grouping is ordered
     * by it: which batches a deadline cuts off is decided by the registers' own order.
     *
     * @param active the active unbatched records, as the store answered
     * @return the keys those records fall under, first seen first
     */
    private static Collection<CourtCentreDay> keysOf(final List<RegisterRecord> active) {
        return active.stream()
                .map(RegisterRecord::key)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Asks for every batch the deadline holds, in the order the assembler answered.
     *
     * <p>One at a time, and each measured against the same instant. A batch the run had no time
     * left for is counted PENDING rather than failed and gauged as left behind: it is waiting for
     * tomorrow's run, which will find its registers active and unbatched, and a gauge that only
     * ever moved up would need a run to fail before it could come down again.
     *
     * @param tally what the run has done, which is where each batch's state is counted
     */
    private void request(final RunTally tally) {
        final Deadline deadline = Deadline.startingAt(clock.instant(), properties.runDeadline());

        for (final AssembledBatch assembled : tally.assembly().batches()) {
            final int registers = assembled.records().size();
            if (deadline.hasPassedAt(clock.instant())) {
                tally.noTimeLeftFor(registers);
            } else {
                tally.ended(requested(assembled, deadline, tally), registers);
            }
        }
    }

    /**
     * Writes one batch down and then asks for its render.
     *
     * <p>That order, and not the other one. Everything downstream is keyed on the batch:
     * {@code RegisterStore.batched} is what the payload is built from, the two marks move a row that
     * has to exist, and the public event correlates back on the same identity. A run that asked for
     * a render of a batch no row knew about would find no registers, fail every batch
     * ASSEMBLY_FAILED, and then ask the store to fail a batch that is not there.
     *
     * <p>The batch handed on is the one the store wrote rather than the one the assembler decided:
     * they carry the same identity, and the stored one also carries what only the rows knew - the
     * OU code and the court house the day is described by.
     *
     * <p><strong>A batch that could not be stamped does not end the run.</strong> The stamp is
     * refused when a register was superseded or batched between the read and the write, and the
     * refusal takes the batch row with it - so there is no row to fail and nothing to record. Its
     * registers are still active and unbatched, which is exactly the state the next run picks them
     * up in, so the batch is counted PENDING and the night carries on to the court centres behind
     * it. That isolation is defect fix P5's other half: one court centre's trouble is not a night's.
     *
     * <p><strong>Nor does a batch whose request did not finish.</strong> The same rule, one step
     * later and for anything the requesting leg throws: a store that will not take one of the
     * batch's marks, a client that raises something nobody classified, a defect. The batch is
     * counted PENDING, said at ERROR by class only, and counted on
     * {@code yotresultsdistribution_generation_request_unfinished_total}; its row is left where the
     * store holds it - PENDING or GENERATING, or wherever an outcome has since put it - and the
     * next run's stale-batch pass fails and releases whatever is still in flight, which is how its
     * registers are retried. Nothing is swallowed: the batch's ending is decided by that pass, and
     * the counter is what an alert fires on. The one throw that is not a batch's is a shutdown: a
     * request that fails on an interrupted thread ends the run, as any failure did before, because
     * every batch behind it would fail the same way.
     *
     * <p><strong>A batch whose render was asked for is counted where the call was made.</strong>
     * The requesting leg announces that to the tally as it makes the call, so a batch whose request
     * then threw - and is counted PENDING, never asked for - still has its render on the night's
     * account.
     *
     * @param assembled the batch the assembler decided on, beside the registers it groups
     * @param deadline  the run's requesting bound
     * @param progress  the night's own account, told as the render is asked for and before
     *                  anything is concluded about it
     * @return what this batch ended the requesting leg as, which for a batch that could not be
     *         written down, or whose request did not finish, is PENDING under no reason at all
     */
    // PMD.AvoidCatchingGenericException: the stamp refuses through IllegalStateException, the
    // requesting leg can throw anything its collaborators raise, and the store translates an
    // outage into its own unchecked type; all of them mean the same thing here - this
    // batch is left for the next run - and a narrower catch would leave one of them ending the run.
    // PMD.OnlyOneReturn: the three exits are the three things that can happen to a batch, and each
    // says so where it is decided; funnelling them through one would turn a verdict into a flag carried
    // past the call that must not be made once it exists.
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
    private BatchOutcome requested(final AssembledBatch assembled, final Deadline deadline,
            final RenderProgress progress) {
        final RegisterBatch batch;
        try {
            batch = store.assemble(assembled.batch(), assembled.records());
        } catch (RuntimeException notStamped) {
            LOG.error("Batch {} could not be written down, so no render is asked for and its "
                            + "registers are left for the next run. cause={}",
                    assembled.batch().batchId(), notStamped.getClass().getName());
            return new BatchOutcome(assembled.batch().batchId(), BatchStatus.PENDING, null, false);
        }
        try {
            return service.request(batch, deadline, progress);
        } catch (RuntimeException unfinished) {
            if (Thread.currentThread().isInterrupted()) {
                // A shutdown, not this batch's trouble: every batch behind it would fail the same
                // way and raise an alert each for a routine pod stop, so the run leaves.
                throw unfinished;
            }
            metrics.generationRequestUnfinished();
            LOG.error("The request for batch {} did not finish, so it is counted PENDING, left to "
                            + "the next run's stale-batch pass, and the run continues to the next "
                            + "batch. cause={}",
                    batch.batchId(), unfinished.getClass().getName());
            return new BatchOutcome(batch.batchId(), BatchStatus.PENDING, null, false);
        }
    }

    /**
     * How old the oldest register this run left unbatched is.
     *
     * <p>The reading that says a night was missed: a register that is never batched is invisible in
     * every counter here, because nothing happened to it. What the run left unbatched is what the
     * assembler deferred - a key whose earlier batch is still in flight - since everything else it
     * read is in a batch by now.
     *
     * <p>The age is half the reading and {@code yotresultsdistribution_deferred_keys} is the other half:
     * this says how long the worst of them has waited and that says how much of the estate is
     * waiting, and the report and the run's own line carry the same count so that a night's
     * deferral is legible without a dashboard as well as with one.
     *
     * @param active   the registers the store called active
     * @param assembly what the assembler made of them
     * @return the age of the oldest register still waiting, or {@link Duration#ZERO} where there is
     *         none
     */
    private Duration oldestStillWaiting(final List<RegisterRecord> active,
            final BatchAssembly assembly) {

        final Instant now = clock.instant();
        return stillWaiting(active, assembly).stream()
                .map(RegisterRecord::registerTime)
                .min(Instant::compareTo)
                .map(oldest -> Duration.between(oldest, now))
                .orElse(Duration.ZERO);
    }

    /**
     * The registers this run knowingly left for the next one.
     *
     * <p>Everything the assembler deferred, since everything else it read is in a batch by now.
     * Read in one place because the run says two things about them - how many there are, on its own
     * line, and how long the oldest has waited, on
     * {@code yotresultsdistribution_oldest_recorded_unbatched_age} - and a night whose two readings came
     * from two filters could report registers waiting under no court centre, or none waiting under
     * a court centre it had passed over.
     *
     * @param active   the registers the store called active
     * @param assembly what the assembler made of them
     * @return the registers under the court centre days it passed over
     */
    private static List<RegisterRecord> stillWaiting(final List<RegisterRecord> active,
            final BatchAssembly assembly) {

        final List<CourtCentreDay> passedOver = assembly.deferred();
        return active.stream()
                .filter(register -> passedOver.contains(register.key()))
                .toList();
    }

    /**
     * What the store says tonight's batches have come to, read once and never at the run's cost.
     *
     * <p><strong>Read rather than reasoned about, which is the whole of this method.</strong> The
     * requesting leg's own account cannot go past GENERATING, but it is not the only thing writing
     * to {@code register_batch} while a run is going on: a court centre whose render comes back in
     * seconds is marked and notified by the event listener while this run is still asking about the
     * court centres behind it. So the identities are kept as they are assembled and their states are
     * read back here, at the moment the line is written, and what the line carries is what the store
     * said then - a snapshot of a night that may still be settling, as {@link RunReport.Settled}
     * says at length.
     *
     * <p>One statement for the whole night rather than one per batch: the requesting leg is already
     * sequential and a read per court centre at the end of it would make a run's own reporting scale
     * with the estate. The registers behind each batch are the run's own count of what it stamped
     * rather than a second read, so the states come from the store and the sizes from the night.
     *
     * <p><strong>And it cannot fail the run.</strong> By the time it is taken the batches are
     * stamped and the renders are away, so a store that will not answer this is not a reason to
     * throw a night's generation away - the Youth Offending Teams are going to be told whatever
     * this read does. It is a reason to say the counts are missing, which the line does in a word,
     * the WARN does by naming what refused, and {@code yotresultsdistribution_generation_unrecorded_total}
     * does in the one form an alert can be written against: nothing is swallowed, and a reader who
     * cannot tell a night that settled nothing from a night nobody could read has been told less
     * than nothing.
     *
     * @param tally what the run has done, which is where the identities it assembled are kept
     * @return what the store said about them, or {@link RunReport.Settled#UNREAD} where it would
     *         not say
     */
    // PMD.AvoidCatchingGenericException: a store outage arrives as the store's own unchecked type
    // and a refused read as whatever the driver raised; both mean the same thing here - this run
    // cannot say what its batches came to - and a narrower catch would leave the classes it does
    // not name failing a run over its own report.
    // PMD.OnlyOneReturn: the three exits are the three answers - nothing to ask about, what the
    // store said, and a read that was refused - and each is stated where it is decided.
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
    private RunReport.Settled settled(final RunTally tally) {
        final Map<UUID, Integer> assembled = tally.registersPerBatch();
        if (assembled.isEmpty()) {
            return RunReport.Settled.NOTHING_ASSEMBLED;
        }
        try {
            return snapshotOf(store.batchesNamed(assembled.keySet()), assembled);
        } catch (RuntimeException notRead) {
            // The class and not the throwable: the message belongs to whatever library refused
            // the read, and a store failure is where a connection string or a fragment of a
            // statement turns up in one (Principle VII). The same rule the listener's four
            // readers were held to.
            metrics.settledSnapshotUnrecorded();
            LOG.warn("The batches this run assembled could not be read back, so its line says the "
                            + "settled counts are unread rather than nought. cause={}",
                    notRead.getClass().getName());
            return RunReport.Settled.UNREAD;
        }
    }

    /**
     * Counts the batches the store answered with, and the registers this run put inside them.
     *
     * <p>An identity nothing answers for is a batch that is not there rather than a batch that
     * settled nothing: a stamp that was refused left no row and a batch the deadline never reached
     * was never written down, and neither has come back from anywhere.
     *
     * @param asTheStoreHoldsThem the batches the store answered with
     * @param registersPerBatch   how many registers this run stamped into each identity it
     *                            assembled
     * @return the two counts and the registers behind each
     */
    private static RunReport.Settled snapshotOf(final List<RegisterBatch> asTheStoreHoldsThem,
            final Map<UUID, Integer> registersPerBatch) {

        int generated = 0;
        int notified = 0;
        int generatedRows = 0;
        int notifiedRows = 0;
        for (final RegisterBatch batch : asTheStoreHoldsThem) {
            final int registers = registersPerBatch.getOrDefault(batch.batchId(), 0);
            if (hasBeenNotifiedAbout(batch.status())) {
                notified++;
                notifiedRows += registers;
            }
            if (hasADocument(batch.status())) {
                generated++;
                generatedRows += registers;
            }
        }
        return RunReport.Settled.taken(generated, notified, generatedRows, notifiedRows);
    }

    /**
     * Whether the notifying leg has finished with a batch in this state.
     *
     * <p>Read off the state machine rather than listed here. The endings the notifying leg can
     * produce are exactly the states GENERATED may move to - everybody was told, some were and the
     * rest are resendable, and there was nobody to tell (defect fix P1) - so an ending drawn into
     * {@link BatchStatus} later joins this count with the diagram rather than needing a list here
     * to be remembered. Which of them a batch reached is kept where it matters, in the row and on
     * {@code yotresultsdistribution_batches_total} by outcome, and is a distinction no count of batches can
     * carry.
     *
     * @param status the state the store holds the batch in
     * @return whether the notifying leg is done with it
     */
    private static boolean hasBeenNotifiedAbout(final BatchStatus status) {
        return BatchStatus.GENERATED.canTransitionTo(status);
    }

    /**
     * Whether a batch in this state has a document.
     *
     * <p>GENERATED and everything past it, because a batch that has been notified was generated
     * first: a count that fell as the night progressed would be unreadable, and the point of the
     * count is that it only ever grows towards the number of batches the run asked for.
     *
     * @param status the state the store holds the batch in
     * @return whether its document exists
     */
    private static boolean hasADocument(final BatchStatus status) {
        return status == BatchStatus.GENERATED || hasBeenNotifiedAbout(status);
    }

    /**
     * The one line a night leaves behind.
     *
     * <p>Written for every run, including - especially - the ones that did nothing, since before
     * cutover that is every night. Counts, a duration and bounded codes only: the batches are
     * counted rather than named, and nothing a register carries is anywhere near it (constitution
     * Principle VII).
     *
     * <p><strong>Two accounts of the same night, each adding up.</strong> {@code batches} is the
     * total of the three states the requesting leg can leave a batch in, and {@code rows} is the
     * total of the registers inside them plus the ones waiting under a day the run passed over. A
     * fourth batch state or a register in neither place shows up as a total that no longer adds up
     * rather than as a count nobody notices is missing. {@code requested} sits inside the first
     * account rather than partitioning it: it is what the run asked systemdocgenerator for, which
     * is every batch it accepted plus the ones it refused, and never a batch that was left for the
     * next run or could not be written down.
     *
     * <p><strong>And the four settled counts sit inside it too, under a word that says whether
     * they are measurements at all.</strong> {@code generated} and {@code notified} are what the
     * store said tonight's batches had come to when this line was written, so a batch counted in
     * either was counted {@code generating} by the requesting leg and neither count is taken off
     * that one. Nothing is claimed to add up over them: the two totals above are the night, and
     * folding these into either would count the same batches twice. {@code snapshot=unread} is the
     * read that could not be taken, and the four zeroes under it are not a night that settled
     * nothing.
     *
     * <p><strong>And three numbers that are in neither account.</strong>
     * {@code released_batches} and {@code released_registers} are what the run's first act gave
     * back, and the registers among them are re-batched by this same run - so they are already
     * inside {@code rows} and are deliberately not added to it or to anything else (FR-009).
     * {@code contended} is what the pass could not give back: those batches are untouched and stale
     * still, so the next run reaches them again, and a line that said nothing about them would
     * describe a night as complete that had left work undone.
     *
     * <p><strong>The line is written here for both runs that can produce one.</strong> Since
     * increment 005 a regeneration a person asked for is the same run under the same lock, and it
     * has to be readable as one: two spellings of a night would mean two dashboards and one of
     * them always out of date. So the renderer is shared and the correlation is handed in - the
     * schedule's is the ambient {@link RunCorrelation}, and a launched run's is the id its caller
     * was answered with - and {@link RunReport#trigger()} is the one field that says which kind of
     * night this was.
     *
     * @param report what the run did
     * @param runId  the correlation the line is read by
     * @return that same report, so a caller can write the line and answer with it in one step
     */
    public static RunReport recorded(final RunReport report, final String runId) {
        final Map<BatchStatus, Integer> outcomes = report.outcomes();
        final Map<BatchStatus, Integer> rows = report.rowOutcomes();
        final RunReport.Settled settled = report.settled();
        LOG.info("event={} run_id={} trigger={} gate={} reason={} batches={} requested={} "
                        + "generating={} failed={} "
                        + "pending={} deferred={} rows={} rows_generating={} rows_failed={} "
                        + "rows_pending={} rows_deferred={} snapshot={} generated={} notified={} "
                        + "rows_generated={} rows_notified={} released_batches={} "
                        + "released_registers={} contended={} duration_ms={}",
                RUN_EVENT, runId, report.trigger().wire(), gateOf(report.gateDecision()),
                reasonOf(report.gateDecision()),
                outcomes.values().stream().mapToInt(Integer::intValue).sum(), report.requested(),
                counted(outcomes, BatchStatus.GENERATING), counted(outcomes, BatchStatus.FAILED),
                counted(outcomes, BatchStatus.PENDING), report.deferredKeys(), report.rows(),
                counted(rows, BatchStatus.GENERATING), counted(rows, BatchStatus.FAILED),
                counted(rows, BatchStatus.PENDING), report.deferredRows(),
                settled.reading().wire(), settled.generated(), settled.notified(),
                settled.generatedRows(), settled.notifiedRows(), report.releasedBatches(),
                report.releasedRegisters(), report.contended(), report.duration().toMillis());
        return report;
    }

    /**
     * Whether the run went ahead, in the one word the line is filtered on.
     *
     * @param decision what the gate decided
     * @return {@code proceed} or {@code skipped}
     */
    private static String gateOf(final GateDecision decision) {
        return decision instanceof Proceed ? PROCEEDED : SKIPPED;
    }

    /**
     * Why the run went ahead or did not, as one of the four bounded codes.
     *
     * <p>A run an operator overrode is {@code overridden} rather than {@code flag-on}, because the
     * night this service generated while the flag said the legacy was is the one night in this flow
     * most worth finding again.
     *
     * @param decision what the gate decided
     * @return the bounded code the line carries
     */
    private static String reasonOf(final GateDecision decision) {
        return switch (decision) {
            case Proceed proceed -> proceed.overridden() ? Reason.OVERRIDDEN.code() : FLAG_ON;
            case Skipped skipped -> skipped.reason().code();
        };
    }

    private static int counted(final Map<BatchStatus, Integer> outcomes, final BatchStatus status) {
        return outcomes.getOrDefault(status, 0);
    }

    private Duration sinceStart(final Instant startedAt) {
        return Duration.between(startedAt, clock.instant());
    }

    /**
     * What the run has done so far, as it does it.
     *
     * <p>The reason the report is not simply built at the end. A {@link RunReport} is a value and a
     * run that stops half way through has no end to build one at, so the counts accumulate here
     * from the moment they are earned and the report is made out of them wherever the run turns
     * out to finish - the night that completed and the night that fell over are then the same act
     * of reporting, differing only in whether a failure follows it.
     *
     * <p>Mutable and deliberately private to the run: nothing outside {@link RegisterGenerationJob}
     * holds one, one run holds exactly one, and {@link RunReport}'s own constructor copies the
     * counts on the way out, so the value a caller is answered with does not describe whatever the
     * run did next.
     *
     * <p>It is the night's {@link RenderProgress} for the same reason it is the night's counts: the
     * requesting leg announces each call as it makes it, and what a run does with that is add it to
     * the account it is already keeping.
     */
    private static final class RunTally implements RenderProgress {

        /** How many batches ended in each state, in the order the states are declared. */
        private final Map<BatchStatus, Integer> outcomes = new EnumMap<>(BatchStatus.class);

        /** How many registers ended the run in each of those states, counted the same way. */
        private final Map<BatchStatus, Integer> rowOutcomes = new EnumMap<>(BatchStatus.class);

        /**
         * How many registers this run stamped into each batch it assembled, by identity.
         *
         * <p>The identities are what makes the settled counts <em>tonight's</em>: a key's history
         * and a day's both hold batches earlier runs assembled, so a run that counted what came
         * back from either read would credit itself with last night's documents. They are recorded
         * the moment the assembler answers rather than as each batch is requested, so a run that
         * stops part way can still say what the store makes of the batches it did stamp.
         *
         * <p>The sizes are kept beside them because they are already known - the run stamped them -
         * and reading the registers of a settled batch back would be a second statement per court
         * centre for a number that cannot have changed: a batch with a document is past every state
         * in which its rows are released.
         */
        private final Map<UUID, Integer> registersByBatch = new LinkedHashMap<>();

        /**
         * What the run's first act gave back, and what it could not.
         *
         * <p>Held from the moment the pass answers rather than folded into the counts, because the
         * three numbers are a diagnostic beside the night's two accounts and not part of either:
         * the registers counted are re-batched by this same run and are therefore already inside
         * its row totals (FR-009).
         */
        private StaleBatchReleaser.ReleaseTally releaseTally =
                new StaleBatchReleaser.ReleaseTally(0, 0, 0);

        /** The registers the store called active, for the age of the oldest still waiting. */
        private List<RegisterRecord> activeRegisters = List.of();

        /** What the assembler made of the night, or {@code null} until it has been asked. */
        private BatchAssembly nightsAssembly;

        /** How many registers are waiting under the days the assembler passed over. */
        private int registersWaiting;

        /**
         * The batches the run asked systemdocgenerator to render, each named once.
         *
         * <p>Identities rather than a counter, because two things say a render was asked for and
         * they say it about the same batch: the requesting leg announces the call as it makes it
         * ({@link #recordRenderAsked}) and the outcome it answers with carries the same fact
         * ({@link BatchOutcome#renderRequested()}). Both are wanted - the announcement is the only
         * one that survives a store failure after the call, and the outcome is the only one a batch
         * the run counted normally is read from - and a set is what makes the pair one request
         * rather than two. A batch asked for three times inside the retry budget is one request for
         * the same reason.
         */
        private final Set<UUID> rendersAsked = new LinkedHashSet<>();

        /** How many batches the run deadline left unrequested. */
        private int batchesLeftBehind;

        /**
         * Records what the night held, which is everything the deferral readings are taken from.
         *
         * <p>The waiting registers are counted here rather than at the end, because they are known
         * the moment the assembler answers and a run that stops while requesting still has to be
         * able to say how much of the estate it had passed over.
         *
         * @param read what the store called active
         * @param made what the assembler made of it
         */
        private void assembled(final List<RegisterRecord> read, final BatchAssembly made) {
            this.activeRegisters = read;
            this.nightsAssembly = made;
            this.registersWaiting = stillWaiting(read, made).size();
            for (final AssembledBatch grouped : made.batches()) {
                registersByBatch.put(grouped.batch().batchId(), grouped.records().size());
            }
        }

        /**
         * Counts one batch, and the registers inside it, by what the requesting leg left it as.
         *
         * <p>A render is counted as requested where the requesting leg says it made the call, which
         * that leg carries on the outcome ({@code BatchOutcome.renderRequested}) rather than leaving
         * to be read off the reason here. The reason cannot answer it: RENDER_REQUEST_FAILED is what
         * a request that was made and answered nothing ends under and what a batch the run had too
         * little budget left to start an attempt for ends under, and counting the second would
         * report a renderer refusing documents nobody sent it. A batch that could not be written
         * down at all arrives here PENDING and unsent, which is the same answer for the same cause.
         *
         * <p>The batch will already have been announced by the leg that made the call, so this
         * names the same identity again rather than adding to a count - which is what a set is for.
         *
         * @param outcome   what the requesting leg answered about this batch
         * @param registers how many registers it groups
         */
        private void ended(final BatchOutcome outcome, final int registers) {
            if (outcome.renderRequested()) {
                recordRenderAsked(outcome.batchId());
            }
            account(outcome.status(), registers);
        }

        /**
         * Records that the renderer has been asked about a batch, however that turns out.
         *
         * <p>The half of the count that does not depend on an outcome coming back. A batch is
         * announced here before the request is made and its ending is written down after the answer
         * arrives, and the store can go away in between: this is then all the run will ever learn
         * about a render that is nonetheless being made, and a night reporting none would be read
         * as a night that asked systemdocgenerator for nothing.
         *
         * @param batchId the batch whose render was asked for
         */
        @Override
        public void recordRenderAsked(final UUID batchId) {
            rendersAsked.add(batchId);
        }

        /**
         * Counts a batch the run had no time left for: PENDING, left behind, and never asked for.
         *
         * <p>The two readings are one event, so they are moved by one call: a batch counted PENDING
         * without being counted as left behind would be a deadline nothing reads, and the reverse
         * would be a gauge that does not add up to the night.
         *
         * @param registers how many registers that batch groups, which are waiting with it
         */
        private void noTimeLeftFor(final int registers) {
            batchesLeftBehind++;
            account(BatchStatus.PENDING, registers);
        }

        /**
         * The one place a batch and its registers enter the night's two accounts.
         *
         * @param status    what the requesting leg left the batch as
         * @param registers how many registers it groups
         */
        private void account(final BatchStatus status, final int registers) {
            outcomes.merge(status, 1, Integer::sum);
            rowOutcomes.merge(status, registers, Integer::sum);
        }

        /**
         * Records what the run's first act came to.
         *
         * @param released what the pass released and what it could not release
         */
        private void released(final StaleBatchReleaser.ReleaseTally released) {
            this.releaseTally = released;
        }

        private List<RegisterRecord> active() {
            return activeRegisters;
        }

        private BatchAssembly assembly() {
            return nightsAssembly;
        }

        private int leftBehind() {
            return batchesLeftBehind;
        }

        private Map<UUID, Integer> registersPerBatch() {
            return registersByBatch;
        }

        /**
         * The report for a run that ended here, whether or not it meant to.
         *
         * @param decision what the gate decided, which the report carries unchanged
         * @param settled  what the store said tonight's batches had come to, taken as the report
         *                 is made and never before it
         * @param duration how long the run took
         * @return what the run had done
         */
        private RunReport reportOf(final GateDecision decision, final RunReport.Settled settled,
                final Duration duration) {

            return new RunReport(decision, outcomes, rendersAsked.size(), rowOutcomes,
                    nightsAssembly == null ? 0 : nightsAssembly.deferred().size(),
                    registersWaiting, settled, releaseTally.batches(), releaseTally.registers(),
                    releaseTally.contended(), duration);
        }
    }
}
