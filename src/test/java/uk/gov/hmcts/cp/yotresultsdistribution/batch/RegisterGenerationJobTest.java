package uk.gov.hmcts.cp.yotresultsdistribution.batch;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import uk.gov.hmcts.cp.yotresultsdistribution.application.BatchOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterGenerationService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterStore;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RenderProgress;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationProperties;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationProperties.SourceMode;
import uk.gov.hmcts.cp.yotresultsdistribution.config.SchedulingConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.AssembledBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchAssembly;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtCentreDay;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.Deadline;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision.Proceed;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision.Reason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision.Skipped;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterRecord;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunReport;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.support.AdjustableClock;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PersonalDataMarkers;

/**
 * The night, in the order it happens.
 *
 * <p>The job decides almost nothing on its own - the gate answers about the flag, the releaser gives
 * back what the night before did not finish, the assembler groups, the service requests - and that
 * is exactly what makes it worth pinning: what a run <em>is</em> is the order it asks those four in,
 * and every failure this class can have is an order rather than a calculation.
 *
 * <p>Five of them matter enough to be stated as cases here:
 *
 * <ul>
 *   <li><strong>the flag first, and its answer ends the run.</strong> A run that read the store
 *       before it read the flag would have stamped {@code batch_id} onto rows the flag says this
 *       service may not generate, and getting them back is a person's decision about one batch at a
 *       time rather than something a later run can undo. The
 *       skipped run therefore touches nothing at all - not the releaser, not the store, not the
 *       assembler and not the service - which is what the first nested class asserts as
 *       interactions rather than as outcomes;</li>
 *   <li><strong>the stale-batch pass next, and before anything is read.</strong> A pass that ran
 *       after the assembly would give registers back into a batch that had already been made, so
 *       the court centre it was released for would wait another night for its document. It is the
 *       assertion the whole of increment 004 rests on;</li>
 *   <li><strong>one batch at a time.</strong> Sequential is not an efficiency choice: each request
 *       is a write to the shared file service followed by a POST, and a run that fanned them out
 *       would make the run deadline unenforceable and the file-service datasource's readiness
 *       reading meaningless;</li>
 *   <li><strong>the deadline bounds requesting and nothing else.</strong> It is computed once, so a
 *       bound re-derived per batch cannot grow by what the batch before it took, and a batch it
 *       leaves no time for is left PENDING for the next run rather than failed - nothing has gone
 *       wrong with it. What becomes of a render is learned long after the run has ended, on the
 *       public-event topic, and a batch nothing was ever learned about is what the next run's
 *       first act gives back;</li>
 *   <li><strong>every run reports.</strong> Including - especially - the ones that did nothing, since
 *       before cutover that is every night, and a report that only appeared when work happened would
 *       make "the flag is off" and "the scheduler never fired" the same silence.</li>
 * </ul>
 *
 * <p>The last case is {@code job_is_scheduled_in_europe_london}, and it reads annotations rather
 * than behaviour because the annotations <em>are</em> the behaviour: 18:00 is a wall-clock
 * requirement that has to hold in BST and in GMT alike, and the legacy fires in the scheduling JVM's
 * default zone because its Quartz trigger was built without one (research §4). A cron with no zone
 * on a UTC pod is an hour late for five months of the year, and nothing else in this suite would
 * notice. The lock is read in the same breath: a second replica running the same night is two
 * documents and two e-mails for every court centre in the country.
 *
 * <p><strong>Two seams this task adds, and why.</strong> The job's constructor, because a run's
 * whole content is the order it asks its collaborators in and a test that could not stand between
 * them would have nothing to pin. And {@link RunReport}'s first component, which was a
 * {@code FlagDecision} and is now the {@link GateDecision} the run was actually given: the run never
 * sees the reading, so a report built from one could say neither that an operator overrode a flag
 * that had not said ON - the night most worth reading a report for - nor, for a flag that could not
 * be read, which of the six causes stopped it, since the gate does not pass the cause on. Those six
 * keep their own series on {@code yotresultsdistribution_generation_skipped_total}, where the gate already
 * puts them, and the report says what the run knows.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the nightly generation run")
class RegisterGenerationJobTest {

    /** Quoted by every case, so the red run names the seam it is waiting on. */
    private static final String PENDING = "T050 wires the nightly run; this is its red run";

    /** 18:00 Monday to Friday, six fields, Spring's dialect. */
    private static final String COURT_CRON = "0 0 18 * * MON-FRI";

    /** The setting the lock's duration has to come from rather than be written twice. */
    private static final String LOCK_PROPERTY = "${yotresultsdistribution.generation.lock-at-most-for}";

    /** The one zone the requirement is written in. */
    private static final String COURTS_ZONE = "Europe/London";

    private static final Duration RUN_DEADLINE = Duration.ofMinutes(60);
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(70);
    private static final Duration STALE_AFTER = Duration.ofMinutes(30);
    private static final Duration BATCH_AGE_REFRESH = Duration.ofMinutes(10);

    /** 18:00 in Europe/London on a Thursday in August, which is 17:00 UTC. */
    private static final Instant SIX_PM = Instant.parse("2026-08-20T17:00:00Z");

    private static final LocalDate THURSDAY = LocalDate.of(2026, 8, 20);

    /** Returned when a meter is absent, so a missing instrument fails as an assertion. */
    private static final double ABSENT = -1;

    /** Returned when the line does not carry a field, so an omission fails as an assertion. */
    private static final int ABSENT_FIELD = -1;

    /** How the one line a night leaves behind starts, which is what an operator filters on. */
    private static final String RUN_EVENT = "event=register_generation_run";

    /**
     * What the stale-batch pass gave back on the mixed night, in its three numbers.
     *
     * <p>Three different numbers, none of them any other number on the line, for the reason the row
     * counts below are three different numbers: a fixture whose released batches, released registers
     * and contended batches were the same would be pinned by a line that carried the three in the
     * wrong order.
     */
    private static final int RELEASED_BATCHES = 4;

    private static final int RELEASED_REGISTERS = 7;

    private static final int CONTENDED = 6;

    /** What the pass answers on a night nothing was left in flight, which is most nights. */
    private static final StaleBatchReleaser.ReleaseTally NOTHING_RELEASED =
            new StaleBatchReleaser.ReleaseTally(0, 0, 0);

    /**
     * What a pass the store interrupted had committed before it stopped, in its three numbers.
     *
     * <p>Their own values rather than the mixed night's, so that a line carrying the interrupted
     * pass's account cannot be satisfied by a fixture that reported the whole night's.
     */
    private static final int INTERRUPTED_BATCHES = 1;

    private static final int INTERRUPTED_REGISTERS = 2;

    private static final int INTERRUPTED_CONTENDED = 3;

    /**
     * How many registers each of the mixed night's three batches groups.
     *
     * <p>Three different numbers on purpose, and none of them the count of batches: the row counts
     * are what says how many registers a night got out rather than how many documents it asked for,
     * and a fixture that gave every batch the same number of registers would be pinned by a line
     * that had those fields in the wrong order.
     */
    private static final int GENERATING_ROWS = 3;

    private static final int FAILING_ROWS = 2;

    private static final int UNSTAMPABLE_ROWS = 1;

    /** How many registers the mixed night leaves waiting under the two days it passed over. */
    private static final int WAITING_ROWS = 5;

    /** Every register the mixed night's store answered with, batched or left waiting. */
    private static final int THE_MIXED_NIGHTS_ROWS =
            GENERATING_ROWS + FAILING_ROWS + UNSTAMPABLE_ROWS + WAITING_ROWS;

    /**
     * How many registers each of the settling night's three batches groups.
     *
     * <p>Three different numbers again, and none of them the count of batches, for the reason
     * {@link #GENERATING_ROWS} gives: the four settled fields are two counts of batches and two
     * counts of the registers behind them, so a fixture whose batches were the same size would be
     * pinned by a line that had the four in the wrong order.
     */
    private static final int NOTIFIED_ROWS = 3;

    private static final int GENERATED_ROWS = 2;

    private static final int STILL_RENDERING_ROWS = 1;

    /**
     * What the line says about a night the store had nothing settled for when it was written.
     *
     * <p>The snapshot stands - the four counts beside it are what the store says - and what it says
     * is that none of tonight's batches had come back yet, which is the ordinary night. A night that
     * assembled no batch at all reads the same way and for the same reason: there is nothing to ask
     * about, so the empty answer is exact and no statement is issued for it. A snapshot that could
     * not be taken says {@code unread} instead, and the four counts beside it are then zeroes the
     * run did not earn rather than a night that settled nothing.
     */
    private static final String NOTHING_SETTLED_YET =
            " snapshot=taken generated=0 notified=0 rows_generated=0 rows_notified=0";

    /**
     * What the line says about a night whose first act found nothing left in flight.
     *
     * <p>Three noughts rather than three absences: a night that released nothing and a night that
     * did not report are different lines, and only a nought told apart from an absence can be
     * alerted on from the first night.
     */
    private static final String NOTHING_RELEASED_ON_THE_LINE =
            " released_batches=0 released_registers=0 contended=0";

    /**
     * The correlation as {@code normalisedRunLines} renders it, so an expectation can name the
     * field without naming the identity, which is minted per run.
     */
    private static final String NORMALISED_RUN_ID = " run_id=<id>";

    /**
     * What every scheduled night says about who asked for it, which is nobody.
     *
     * <p>The field exists for the run a person asks for over {@code POST
     * /operations/batches/generate}: the same run under the same lock writing this same line, told
     * apart by this one word. A schedule that stopped saying it would make a launched run
     * indistinguishable from a night nobody drove.
     */
    private static final String THE_SCHEDULE_ASKED = " trigger=schedule";

    /**
     * The line a night that did something leaves behind, in full.
     *
     * <p>Every field of {@link RunReport}: what the gate decided and why, how many batches the run
     * asked the renderer for, the three states the requesting leg can leave a batch in and their
     * total, the court centre days the run passed over, how many registers ended the run under each
     * of those outcomes, what the store said tonight's batches had come to by the time the line was
     * written, what its first act gave back and could not give back, and how long it took. Written
     * out rather than asserted field by field because the claim is the whole line - a field dropped
     * from it is a night an operator can no longer read, and a field renamed is an alert that stops
     * firing.
     */
    private static final String THE_MIXED_NIGHTS_LINE = RUN_EVENT
            + NORMALISED_RUN_ID + THE_SCHEDULE_ASKED
            + " gate=proceed reason=flag-on batches=3 requested=2 generating=1 failed=1 pending=1"
            + " deferred=2 rows=" + THE_MIXED_NIGHTS_ROWS
            + " rows_generating=" + GENERATING_ROWS
            + " rows_failed=" + FAILING_ROWS
            + " rows_pending=" + UNSTAMPABLE_ROWS
            + " rows_deferred=" + WAITING_ROWS
            + NOTHING_SETTLED_YET
            + " released_batches=" + RELEASED_BATCHES
            + " released_registers=" + RELEASED_REGISTERS
            + " contended=" + CONTENDED
            + " duration_ms=180000";

    /**
     * The line a run that stopped before it read anything can still write.
     *
     * <p>Two nights leave it: one the store went away under while the pass was giving batches back,
     * and one it went away under at the first read. Both are a night that did nothing, and both
     * still have to say so.
     */
    private static final String NOTHING_YET = RUN_EVENT
            + NORMALISED_RUN_ID + THE_SCHEDULE_ASKED
            + " gate=proceed reason=flag-on batches=0 requested=0 generating=0 failed=0"
            + " pending=0 deferred=0 rows=0 rows_generating=0 rows_failed=0 rows_pending=0"
            + " rows_deferred=0" + NOTHING_SETTLED_YET + NOTHING_RELEASED_ON_THE_LINE
            + " duration_ms=0";

    /** The same line for a night the flag stopped: the same fields, and nothing earned. */
    private static final String THE_SKIPPED_NIGHTS_LINE = RUN_EVENT
            + NORMALISED_RUN_ID + THE_SCHEDULE_ASKED
            + " gate=skipped reason=flag-off batches=0 requested=0 generating=0 failed=0 pending=0"
            + " deferred=0 rows=0 rows_generating=0 rows_failed=0 rows_pending=0 rows_deferred=0"
            + NOTHING_SETTLED_YET + NOTHING_RELEASED_ON_THE_LINE
            + " duration_ms=0";

    /**
     * What every value on the line is allowed to be: a count, a duration, or a bounded code.
     *
     * <p>The privacy claim stated where the line is written rather than only across the service
     * ({@code TelemetryPrivacyTest}): a court centre's name, a recipient's address or a renderer's
     * own reason text added to this line would be free text in the one INFO line every night
     * produces, and this refuses the shape rather than the particular words.
     */
    private static final Pattern BOUNDED_FIELDS_ONLY = Pattern.compile(
            "event=register_generation_run run_id=[0-9a-f-]{36} "
                    + "trigger=(?:schedule|operator) "
                    + "gate=(?:proceed|skipped) reason=[a-z-]+ batches=\\d+ "
                    + "requested=\\d+ generating=\\d+ failed=\\d+ pending=\\d+ deferred=\\d+ "
                    + "rows=\\d+ rows_generating=\\d+ rows_failed=\\d+ rows_pending=\\d+ "
                    + "rows_deferred=\\d+ snapshot=(?:taken|unread) generated=\\d+ notified=\\d+ "
                    + "rows_generated=\\d+ rows_notified=\\d+ released_batches=\\d+ "
                    + "released_registers=\\d+ contended=\\d+ duration_ms=\\d+");

    /** What the store answers with; the run's job is to pass it on unchanged. */
    private static final List<RegisterRecord> ACTIVE = List.of(record(), record());

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GenerationMetrics metrics = new GenerationMetrics(registry);
    private final FeatureFlagGate gate = mock(FeatureFlagGate.class);
    private final RegisterStore store = mock(RegisterStore.class);
    private final BatchAssembler assembler = mock(BatchAssembler.class);
    private final RegisterGenerationService service = mock(RegisterGenerationService.class);
    private final StaleBatchReleaser releaser = mock(StaleBatchReleaser.class);
    private final AdjustableClock clock = AdjustableClock.startingAt(SIX_PM);

    private final RegisterGenerationJob job = new RegisterGenerationJob(gate, store, assembler,
            service, releaser, metrics, settings(), clock);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Most nights leave nothing in flight, so the pass answers with nothing unless a case says
     * otherwise.
     *
     * <p>Stated once here rather than in every fixture: a run whose pass answered {@code null}
     * would fail inside the run's first statement, and every case below would then be about that
     * rather than about what it is written for.
     */
    @BeforeEach
    void nothingIsStaleUnlessACaseSaysSo() {
        theStaleBatchPassGaveBack(NOTHING_RELEASED);
    }

    /**
     * Stands the pass up to hand an account over the way the real one does.
     *
     * <p>Through the observer and not only through the return, because that is where the run reads
     * it from: the pass commits each batch by itself and tells its caller as it goes, so a run the
     * store stopped part way still has the part that happened. A double that only answered would
     * pin the run to a reading the real pass cannot give it on the night it matters.
     *
     * @param tally what the pass committed and could not commit
     */
    private void theStaleBatchPassGaveBack(final StaleBatchReleaser.ReleaseTally tally) {
        doAnswer(call -> {
            call.<Consumer<StaleBatchReleaser.ReleaseTally>>getArgument(0).accept(tally);
            return tally;
        }).when(releaser).releaseStale(any());
    }

    /**
     * The settings a deployed run works to, which are the ones {@code application.yaml} ships.
     *
     * @return generation enabled, at the court's hour, in the court's zone
     */
    private static GenerationProperties settings() {
        return new GenerationProperties(true, COURT_CRON, COURTS_ZONE, false, RUN_DEADLINE,
                LOCK_AT_MOST_FOR, STALE_AFTER, BATCH_AGE_REFRESH,
                SourceMode.LIVE, SourceMode.LIVE, SourceMode.LIVE, SourceMode.LIVE);
    }

    /**
     * Runs the night and hands back what it reported.
     *
     * <p>The call is made inside {@code assertThatCode(...).doesNotThrowAnyException()} so that the
     * seam's refusal is recorded as an assertion rather than ending the case, and the report it did
     * not produce is then asserted on as {@code null} - the red run is the report and the green run
     * is the same assertions unchanged.
     *
     * @return the run report, or {@code null} where the seam refused
     */
    private RunReport run() {
        final AtomicReference<RunReport> reported = new AtomicReference<>();
        softly.assertThatCode(() -> reported.set(job.run()))
                .as(PENDING)
                .doesNotThrowAnyException();
        return reported.get();
    }

    /**
     * Runs the night and hands back whatever stopped it, without ending the case.
     *
     * <p>The counterpart of {@link #run()} for the runs that do not finish: a failure has to leave
     * the run - nothing is swallowed - so the case cannot call the job directly and still assert on
     * what the run left behind afterwards.
     *
     * @return what ended the run, or {@code null} where nothing did
     */
    private Throwable whatStoppedTheRun() {
        return catchThrowable(job::run);
    }

    /**
     * One field of the report, read safely from a run that did not produce one.
     *
     * <p>A method reference applied to a null report throws where the case wants an assertion, and
     * the red run's whole point is that every failure it records is one.
     *
     * @param report what the run reported, or {@code null} where the seam refused
     * @param field  the field being asserted on
     * @param <T>    that field's type
     * @return the field's value, or {@code null} where there is no report
     */
    private static <T> T reported(final RunReport report, final Function<RunReport, T> field) {
        return report == null ? null : field.apply(report);
    }

    private void theGateAnswers(final GateDecision decision) {
        when(gate.decide(false)).thenReturn(decision);
    }

    /**
     * Sets the night up as one the flag allows, holding the given batches.
     *
     * @param assembled the batches the assembler makes of the night's active records
     */
    private void aNightHolding(final RegisterBatch... assembled) {
        theGateAnswers(new Proceed(false));
        when(store.activeUnbatched()).thenReturn(ACTIVE);
        when(assembler.assemble(any(), any(), anyBoolean())).thenReturn(assembly(assembled));
        // The store answers with the batch as the row now stands. It carries the same identity the
        // assembler decided - the OU code and the court house it gains are the rows' own and are
        // read by the notify leg rather than by anything here - so a stub that hands the argument
        // back is what a written batch looks like from this class.
        when(store.assemble(any(), any())).thenAnswer(call -> call.getArgument(0));
    }

    /**
     * Sets the night up as one the flag allows, holding one batch and deferring one key.
     *
     * <p>A key is deferred when a batch of its own is still in flight, which is the schema's
     * one-live-batch-per-key rule (design Q27) rather than anything going wrong. What makes it worth
     * reporting is that nothing else in the run says it happened: no batch was assembled for that
     * key, so it is in none of the outcome counts.
     *
     * @param deferred the court centre days the assembler passed over
     */
    private void aNightDeferring(final CourtCentreDay... deferred) {
        theGateAnswers(new Proceed(false));
        when(store.activeUnbatched()).thenReturn(ACTIVE);
        when(assembler.assemble(any(), any(), anyBoolean())).thenReturn(new BatchAssembly(
                List.of(new AssembledBatch(batch(), ACTIVE)), List.of(deferred)));
        when(store.assemble(any(), any())).thenAnswer(call -> call.getArgument(0));
        everyRequestIsAccepted();
    }

    private static CourtCentreDay key() {
        return new CourtCentreDay(UUID.randomUUID(), THURSDAY);
    }

    /**
     * A night with one of each outcome, two court centre days passed over and a pass that released.
     *
     * <p>Built so that the line it leaves behind exercises every field at once and none of them
     * with the same number: a report whose counts were all one would be pinned by a line that had
     * the fields in the wrong order, and one whose duration were zero would be pinned by a line
     * that did not measure it. The three batches end GENERATING, FAILED and PENDING - the three
     * states the requesting leg can produce - and the third gets there through a stamp that was
     * refused, so it costs the run no time and the duration stays the two advances the other two
     * make.
     *
     * <p><strong>Its registers are partitioned</strong>, unlike the nights above: each batch groups
     * its own and the two days the assembler passed over have their own waiting behind them, so
     * every register the store answered with is in exactly one of the run's row counts. Which
     * records belong to which batch is the assembler's own concern (T032) and nothing here asserts
     * it - what this shape makes assertable is that the run's own accounting adds up to the night.
     *
     * @return the batches the night holds, in the order the assembler answered
     */
    private List<RegisterBatch> aMixedNight() {
        final RegisterBatch generating = batch();
        final RegisterBatch failing = batch();
        final RegisterBatch unstampable = batch();
        final List<RegisterRecord> generatingRows = records(GENERATING_ROWS);
        final List<RegisterRecord> failingRows = records(FAILING_ROWS);
        final List<RegisterRecord> unstampableRows = records(UNSTAMPABLE_ROWS);
        final CourtCentreDay busiest = key();
        final CourtCentreDay quietest = key();
        final List<RegisterRecord> waiting = Stream.concat(
                recordsUnder(busiest, WAITING_ROWS - 1).stream(),
                recordsUnder(quietest, 1).stream()).toList();
        theGateAnswers(new Proceed(false));
        when(store.activeUnbatched()).thenReturn(Stream.of(generatingRows, failingRows,
                unstampableRows, waiting).flatMap(List::stream).toList());
        when(assembler.assemble(any(), any(), anyBoolean())).thenReturn(new BatchAssembly(
                List.of(new AssembledBatch(generating, generatingRows),
                        new AssembledBatch(failing, failingRows),
                        new AssembledBatch(unstampable, unstampableRows)),
                List.of(busiest, quietest)));
        when(store.assemble(any(), any())).thenAnswer(call -> call.getArgument(0));
        when(store.assemble(eq(unstampable), any())).thenThrow(new IllegalStateException(
                "batch " + unstampable.batchId() + " was asked for 2 registers and stamped 1"));
        when(service.request(eq(generating), any(), any())).thenAnswer(call -> {
            clock.advance(Duration.ofMinutes(1));
            return requested(generating, BatchStatus.GENERATING, null);
        });
        when(service.request(eq(failing), any(), any())).thenAnswer(call -> {
            clock.advance(Duration.ofMinutes(2));
            return requested(failing, BatchStatus.FAILED,
                    BatchFailureReason.RENDER_REQUEST_REJECTED);
        });
        theStaleBatchPassGaveBack(new StaleBatchReleaser.ReleaseTally(
                RELEASED_BATCHES, RELEASED_REGISTERS, CONTENDED));
        return List.of(generating, failing, unstampable);
    }

    /**
     * A night of three accepted renders, one of which the completion legs have already settled.
     *
     * <p>The night the run's own account and the store's come apart, which is the whole reason the
     * settled counts are read back rather than reasoned about. All three batches were stamped and
     * all three renders were accepted, so the requesting leg leaves every one of them GENERATING -
     * and while the run was still working through the court centres behind it, the event listener
     * marked the first one's document and its notification and then the second one's document. By
     * the time the line is written the store says one NOTIFIED, one GENERATED and
     * one still GENERATING, and nothing the requesting leg saw could have said so.
     *
     * <p>Each batch groups a different number of registers so that the two counts of batches and
     * the two counts of registers behind them cannot be pinned in the wrong order.
     *
     * @return the batches the night holds, in the order the assembler answered
     */
    private List<RegisterBatch> aNightThatIsAlreadySettling() {
        final RegisterBatch told = batch();
        final RegisterBatch rendered = batch();
        final RegisterBatch stillRendering = batch();
        final List<RegisterRecord> toldRows = records(NOTIFIED_ROWS);
        final List<RegisterRecord> renderedRows = records(GENERATED_ROWS);
        final List<RegisterRecord> stillRenderingRows = records(STILL_RENDERING_ROWS);
        theGateAnswers(new Proceed(false));
        when(store.activeUnbatched()).thenReturn(Stream.of(toldRows, renderedRows,
                stillRenderingRows).flatMap(List::stream).toList());
        when(assembler.assemble(any(), any(), anyBoolean())).thenReturn(new BatchAssembly(
                List.of(new AssembledBatch(told, toldRows),
                        new AssembledBatch(rendered, renderedRows),
                        new AssembledBatch(stillRendering, stillRenderingRows)),
                List.of()));
        when(store.assemble(any(), any())).thenAnswer(call -> call.getArgument(0));
        everyRequestIsAccepted();
        return List.of(told, rendered, stillRendering);
    }

    /**
     * What the store answers when the run reads tonight's batches back by identity.
     *
     * @param settled the batches as their rows stand at the moment the line is written
     */
    private void theStoreSaysTheyAreNow(final RegisterBatch... settled) {
        when(store.batchesNamed(any())).thenReturn(List.of(settled));
    }

    /**
     * The same batch as the store now holds it, in the state the completion legs moved it to.
     *
     * @param batch  the batch the run assembled
     * @param status the state its row has since reached
     * @return that batch under that state, everything else unchanged
     */
    private static RegisterBatch nowHeldAt(final RegisterBatch batch, final BatchStatus status) {
        return new RegisterBatch(batch.batchId(), batch.courtCentreId(), batch.courtCentreOuCode(),
                batch.courtHouse(), batch.registerDate(), batch.fileName(), batch.payloadFileId(),
                batch.documentFileId(), status, batch.failureReason(), batch.sdgReason(),
                batch.systemGenerated(), batch.completedBy(), batch.assembledAt(),
                batch.requestedAt(), batch.generatedAt(), batch.notifiedAt(), batch.failedAt(),
                batch.attempts(), batch.supplementOf(), batch.supplementIndex());
    }

    /**
     * A night that assembled nothing and passed over the day one old register waits under.
     *
     * <p>The deferred key is the key that register is addressed by, which is what makes the age
     * gauge readable at all: {@code oldestStillWaiting} reads the registers whose key the assembler
     * passed over, so a deferred key nothing is waiting under gauges nothing.
     *
     * @param recorded when that register was recorded
     * @return how long it has been waiting as of the run's own instant
     */
    private Duration aNightDeferringARegisterRecordedAt(final Instant recorded) {
        final CourtCentreDay waiting = key();
        theGateAnswers(new Proceed(false));
        when(store.activeUnbatched()).thenReturn(List.of(new RegisterRecord(UUID.randomUUID(),
                UUID.randomUUID(), SIX_PM, waiting, recorded,
                "yotresultsdistribution_" + THURSDAY + ".json", "Applicant", RecordedFlagState.ON, null)));
        when(assembler.assemble(any(), any(), anyBoolean()))
                .thenReturn(new BatchAssembly(List.of(), List.of(waiting)));
        return Duration.between(recorded, SIX_PM);
    }

    /**
     * Every line the run wrote that a night is indexed under, in the order it wrote them.
     *
     * @param log what the job logged
     * @return the run's own lines, which on a healthy night is exactly one
     */
    private static List<String> runLines(final CapturedLog log) {
        return log.messages().stream().filter(line -> line.startsWith(RUN_EVENT)).toList();
    }

    /**
     * The run's lines with the correlation's value normalised, for the cases that read the whole
     * line verbatim.
     *
     * <p>The id is minted per run, so a case comparing the whole line cannot name it. Normalising
     * rather than stripping is deliberate: the field stays visible in every expectation below, so a
     * reader sees what the line carries, and a field that vanished from the line would still fail
     * them. That the value is a real identity, and a different one each run, is asserted by the
     * three cases that own the correlation.
     *
     * @param log what the job logged
     * @return the run's lines, each with {@code run_id=<id>} in place of the minted value
     */
    private static List<String> normalisedRunLines(final CapturedLog log) {
        return runLines(log).stream()
                .map(line -> line.replaceAll("run_id=[0-9a-f-]{36}", "run_id=<id>"))
                .toList();
    }

    /**
     * The one line the night left behind, or {@code null} where it left none or more than one.
     *
     * <p>{@code null} rather than a refusal so that a run which wrote no line at all is recorded as
     * a failing assertion about the line rather than as an exception from the reading of it.
     *
     * @param log what the job logged
     * @return the single run line, or {@code null}
     */
    private static String theOneLine(final CapturedLog log) {
        final List<String> lines = runLines(log);
        return lines.size() == 1 ? lines.getFirst() : null;
    }

    /**
     * The line's {@code name=value} pairs, read the way a log index reads them.
     *
     * @param line the run's line, or {@code null} where it wrote none
     * @return each field against its value, or empty where there is no line
     */
    private static Map<String, String> fieldsOf(final String line) {
        return line == null
                ? Map.of()
                : Stream.of(line.split(" "))
                        .map(field -> field.split("=", 2))
                        .collect(Collectors.toMap(pair -> pair[0], pair -> pair[1]));
    }

    /**
     * One count the line carries.
     *
     * @param fields the line's fields
     * @param name   the field being read
     * @return its value, or {@link #ABSENT_FIELD} where the line does not carry it
     */
    private static int onTheLine(final Map<String, String> fields, final String name) {
        return Integer.parseInt(fields.getOrDefault(name, String.valueOf(ABSENT_FIELD)));
    }

    private double oldestRecordedUnbatchedAge() {
        final Gauge gauge =
                registry.find(GenerationMetrics.OLDEST_RECORDED_UNBATCHED_AGE).gauge();
        return gauge == null ? ABSENT : gauge.value();
    }

    private double deferredKeys() {
        final Gauge gauge = registry.find(GenerationMetrics.DEFERRED_KEYS).gauge();
        return gauge == null ? ABSENT : gauge.value();
    }

    private double deferredRegisters() {
        final Gauge gauge = registry.find(GenerationMetrics.DEFERRED_REGISTERS).gauge();
        return gauge == null ? ABSENT : gauge.value();
    }

    /**
     * The assembly the assembler answers with, one pairing per batch and nothing deferred.
     *
     * <p>Each batch is paired with the night's active records because
     * {@link AssembledBatch} refuses a batch assembled from none; which records belong to which
     * batch is the assembler's own concern (T032) and nothing here asserts it.
     *
     * @param assembled the batches the night holds
     * @return those batches as an assembly
     */
    private static BatchAssembly assembly(final RegisterBatch... assembled) {
        return new BatchAssembly(Stream.of(assembled)
                .map(batch -> new AssembledBatch(batch, ACTIVE))
                .toList(), List.of());
    }

    /**
     * A night of two batches that end under one reason, only one of which was ever sent.
     *
     * <p>The night the reason a batch failed under cannot say whether the renderer was asked.
     * {@code RENDER_REQUEST_FAILED} is what a request that was made and answered nothing inside the
     * budget ends under, and it is also what a batch the run reached with less budget left than one
     * whole attempt ends under - and that one asked systemdocgenerator nothing at all. So a night
     * whose two batches share the reason is the night a count read off the reason gets wrong, and it
     * gets it wrong in the direction that matters: it reports a renderer refusing a document it was
     * never sent.
     */
    private void aNightOfOneReasonAndOneRequest() {
        final RegisterBatch outOfBudget = batch();
        final RegisterBatch unanswered = batch();
        aNightHolding(outOfBudget, unanswered);
        when(service.request(eq(outOfBudget), any(), any())).thenAnswer(call -> neverRequested(
                outOfBudget, BatchFailureReason.RENDER_REQUEST_FAILED));
        when(service.request(eq(unanswered), any(), any())).thenAnswer(call -> requested(unanswered,
                BatchStatus.FAILED, BatchFailureReason.RENDER_REQUEST_FAILED));
    }

    /** Every request is accepted, and each answers about the batch it was given. */
    private void everyRequestIsAccepted() {
        when(service.request(any(), any(), any()))
                .thenAnswer(call -> requested(call.getArgument(0), BatchStatus.GENERATING, null));
    }

    private double pendingAfterDeadline() {
        final Gauge gauge = registry.find(GenerationMetrics.PENDING_AFTER_DEADLINE).gauge();
        return gauge == null ? ABSENT : gauge.value();
    }

    private static RegisterBatch batch() {
        return new RegisterBatch(UUID.randomUUID(), UUID.randomUUID(), "B01LY", "Youth Court",
                THURSDAY, "yotresultsdistribution_" + THURSDAY + ".json", null, null, BatchStatus.PENDING,
                null, null, true, null, SIX_PM, null, null, null, null, 0, null, 0);
    }

    private static RegisterRecord record() {
        return recordUnder(key());
    }

    /**
     * One register recorded under the given court centre and day.
     *
     * @param key the day it is addressed by, which is what the assembler groups and defers on
     * @return that register
     */
    private static RegisterRecord recordUnder(final CourtCentreDay key) {
        return new RegisterRecord(UUID.randomUUID(), UUID.randomUUID(), SIX_PM, key, SIX_PM,
                "yotresultsdistribution_" + THURSDAY + ".json", "Applicant", RecordedFlagState.ON, null);
    }

    /**
     * That many registers, each under a court centre day of its own.
     *
     * @param registers how many
     * @return the registers
     */
    private static List<RegisterRecord> records(final int registers) {
        return IntStream.range(0, registers).mapToObj(one -> record()).toList();
    }

    /**
     * That many registers, all under the one court centre day.
     *
     * @param key       the day they are all addressed by
     * @param registers how many
     * @return the registers
     */
    private static List<RegisterRecord> recordsUnder(final CourtCentreDay key,
            final int registers) {
        return IntStream.range(0, registers).mapToObj(one -> recordUnder(key)).toList();
    }

    /**
     * What the requesting leg answers about a batch it did ask systemdocgenerator for.
     *
     * @param batch  the batch it is about
     * @param status what the leg left the batch as
     * @param reason the bounded reason where it failed, and {@code null} where it did not
     * @return that outcome, saying the request left this service
     */
    private static BatchOutcome requested(final RegisterBatch batch, final BatchStatus status,
            final BatchFailureReason reason) {
        return new BatchOutcome(batch.batchId(), status, reason, true);
    }

    /**
     * What it answers about a batch that ended before the renderer was ever asked.
     *
     * <p>Always FAILED: the two endings that reach here are a payload the file service would not
     * take and a budget that would not hold one attempt, and both fail the batch and hand its
     * registers back to the next run.
     *
     * @param batch  the batch it is about
     * @param reason the bounded reason it failed under
     * @return that outcome, saying nothing was sent
     */
    private static BatchOutcome neverRequested(final RegisterBatch batch,
            final BatchFailureReason reason) {
        return new BatchOutcome(batch.batchId(), BatchStatus.FAILED, reason, false);
    }

    /**
     * What the annotation names, whether it says it or names the setting that says it.
     *
     * <p>The schedule may reasonably be written either way - as the two literals research §4 gives,
     * or as the placeholders of the two settings {@code application.yaml} declares and
     * {@link GenerationProperties#validate()} holds to the court's zone. What must not vary is the
     * schedule itself, so a placeholder is resolved here to what those settings hold and the
     * assertion is about the hour and the zone rather than about the spelling.
     *
     * @param annotated the annotation's own value
     * @return the value the run is scheduled by
     */
    private static String configured(final String annotated) {
        return switch (annotated) {
            case "${yotresultsdistribution.generation.cron}" -> settings().cron();
            case "${yotresultsdistribution.generation.zone}" -> settings().zone();
            case LOCK_PROPERTY -> settings().lockAtMostFor().toString();
            default -> annotated;
        };
    }

    /**
     * How long the lock is held for, in either spelling ShedLock accepts.
     *
     * <p>{@code PT70M} and {@code 70m} are the same lock, and which one is written is not what this
     * suite is about; how long it lasts against the run deadline is.
     *
     * @param lock the annotation the job carries, or {@code null} where it carries none
     * @return the duration the lock is held for, or {@code null} where none is stated
     */
    private static Duration lockedFor(final SchedulerLock lock) {
        final String stated = lock == null ? "" : configured(lock.lockAtMostFor());
        return stated.isBlank()
                ? null
                : Duration.parse(stated.regionMatches(true, 0, "P", 0, 1) ? stated : "PT" + stated);
    }

    /**
     * The one lever, and the first thing the run asks about.
     */
    @Nested
    @DisplayName("reading the flag before anything else")
    class TheGateFirst {

        @Test
        void a_flag_that_did_not_say_on_should_end_the_run_before_anything_is_read() {
            theGateAnswers(new Skipped(Reason.FLAG_OFF));

            run();

            verifyNoInteractions(releaser, store, assembler, service);
        }

        @Test
        void the_flag_should_be_read_before_the_store_is_asked_what_is_waiting() {
            aNightHolding(batch());
            everyRequestIsAccepted();

            run();

            final InOrder order = inOrder(gate, store, assembler, service);
            order.verify(gate).decide(false);
            order.verify(store).activeUnbatched();
            order.verify(assembler).assemble(any(), any(), anyBoolean());
            order.verify(service).request(any(), any(), any());
        }

        @Test
        void the_nightly_run_should_never_override_the_flag() {
            aNightHolding();

            run();

            verify(gate).decide(false);
            verify(gate, never()).decide(true);
        }

        @Test
        void a_skipped_run_should_still_report_what_the_gate_decided() {
            theGateAnswers(new Skipped(Reason.FLAG_OFF));

            final RunReport report = run();

            softly.assertThat(reported(report, RunReport::gateDecision))
                    .as("a night nobody generated on leaves no batch, no request and no row; the "
                            + "report is the only thing that distinguishes it from a scheduler "
                            + "that never fired")
                    .isEqualTo(new Skipped(Reason.FLAG_OFF));
            softly.assertThat(reported(report, RunReport::outcomes))
                    .as("and it did nothing, which the counts have to say rather than merely omit")
                    .isEqualTo(Map.of());
        }

        @Test
        void a_run_stopped_by_a_flag_nobody_could_read_should_say_so_and_not_that_it_was_off() {
            theGateAnswers(new Skipped(Reason.FLAG_UNREADABLE));

            final RunReport report = run();

            softly.assertThat(reported(report, RunReport::gateDecision))
                    .as("fail-closed and off are the same restraint and not the same night: one is "
                            + "the cutover working, the other is an outage this service rode out")
                    .isEqualTo(new Skipped(Reason.FLAG_UNREADABLE));
        }

        @Test
        void a_run_an_operator_overrode_should_go_ahead_and_the_report_should_say_it_was() {
            theGateAnswers(new Proceed(true));
            when(store.activeUnbatched()).thenReturn(ACTIVE);
            when(assembler.assemble(any(), any(), anyBoolean())).thenReturn(assembly());

            final RunReport report = run();

            verify(store).activeUnbatched();
            softly.assertThat(reported(report, RunReport::gateDecision))
                    .as("the one night on which this service generated while the flag said the "
                            + "legacy was; a report that could not say so would leave the warning "
                            + "line as the only trace of it")
                    .isEqualTo(new Proceed(true));
        }
    }

    /**
     * What the run assembles, and on whose behalf.
     */
    @Nested
    @DisplayName("assembling the night's work")
    class Assembling {

        @Test
        void the_run_should_assemble_exactly_the_records_the_store_calls_active() {
            aNightHolding();

            run();

            verify(assembler).assemble(eq(ACTIVE), any(), anyBoolean());
        }

        @Test
        void the_nightly_run_should_stamp_its_batches_as_system_generated() {
            aNightHolding();

            run();

            verify(assembler).assemble(any(), any(), eq(true));
        }

        /**
         * The run has to write the batch down before it asks anybody to render it.
         *
         * <p>Everything downstream is keyed on the batch: {@code store.batched(batchId)} is what the
         * payload is built from, {@code markPayloadMinted} and {@code markRequested} move a row that
         * has to exist, and the public event correlates back on the same identity. A run that handed
         * the assembler's batch straight to the service would ask for a render of a batch no row
         * knows about - the payload read would answer nothing, every batch would take the
         * ASSEMBLY_FAILED path, and {@code markFailed} would be asked about a batch that is not
         * there. Nothing about it is visible from a suite that mocks the store, which is exactly why
         * it is asserted as an order here.
         */
        @Test
        void every_assembled_batch_should_be_written_down_before_its_render_is_asked_for() {
            final RegisterBatch first = batch();
            final RegisterBatch second = batch();
            aNightHolding(first, second);
            everyRequestIsAccepted();

            run();

            final InOrder order = inOrder(store, service);
            order.verify(store).assemble(eq(first), any());
            order.verify(service).request(eq(first), any(), any());
            order.verify(store).assemble(eq(second), any());
            order.verify(service).request(eq(second), any(), any());
        }

        /**
         * And the history the supplementary rule is decided from has to be read at all.
         *
         * <p>The assembler is given the batches already recorded for the keys in play so that a late
         * re-share becomes a supplementary batch for its day rather than a second first one (design
         * Q27). A run that passed an empty list would answer "no earlier batch" for every key, and
         * the rule would be dead from end to end however carefully the assembler implemented it.
         */
        @Test
        void the_batches_already_recorded_for_tonights_keys_should_be_read_and_passed_on() {
            final RegisterBatch earlier = batch();
            aNightHolding(batch());
            everyRequestIsAccepted();
            when(store.batchesFor(any())).thenReturn(List.of(earlier));

            run();

            verify(assembler).assemble(any(), eq(List.of(earlier)), anyBoolean());
        }

        /**
         * A court centre whose registers moved is one court centre, not the night.
         *
         * <p>The stamp is refused where a register was superseded or batched between the read and
         * the write, and the refusal takes the batch row with it - so there is no row to fail and
         * nothing to record about it. Its registers are still active and unbatched, which is exactly
         * the state the next run finds them in, so the batch is counted PENDING and the court
         * centres behind it are still asked for. That isolation is the other half of defect fix P5:
         * progression's leg caught the stream exception and walked on, and what it got wrong was
         * leaving no trace, not the walking on.
         */
        @Test
        void a_batch_that_could_not_be_written_down_should_not_stop_the_batches_behind_it() {
            final RegisterBatch unstampable = batch();
            final RegisterBatch following = batch();
            aNightHolding(unstampable, following);
            everyRequestIsAccepted();
            when(store.assemble(eq(unstampable), any())).thenThrow(new IllegalStateException(
                    "batch " + unstampable.batchId() + " was asked for 2 registers and stamped 1"));

            final RunReport report = run();

            verify(service, never()).request(eq(unstampable), any(), any());
            verify(service).request(eq(following), any(), any());
            softly.assertThat(reported(report, RunReport::outcomes))
                    .as("PENDING rather than FAILED, because there is no batch row to have failed: "
                            + "the registers are where the next run will look for them")
                    .isEqualTo(Map.of(BatchStatus.GENERATING, 1, BatchStatus.PENDING, 1));
        }

        @Test
        void a_night_with_nothing_waiting_should_ask_the_renderer_for_nothing() {
            aNightHolding();

            final RunReport report = run();

            verifyNoInteractions(service);
            softly.assertThat(reported(report, RunReport::outcomes))
                    .as("a quiet night is a successful run with no outcomes, not a failed one")
                    .isEqualTo(Map.of());
        }
    }

    /**
     * One batch at a time, each against the same bound.
     */
    @Nested
    @DisplayName("requesting one batch at a time")
    class Requesting {

        @Test
        void every_batch_should_be_requested_in_the_order_it_was_assembled() {
            final RegisterBatch first = batch();
            final RegisterBatch second = batch();
            aNightHolding(first, second);
            everyRequestIsAccepted();

            run();

            final InOrder order = inOrder(service);
            order.verify(service).request(eq(first), any(), any());
            order.verify(service).request(eq(second), any(), any());
        }

        @Test
        void a_batch_that_failed_should_not_stop_the_batches_behind_it() {
            final RegisterBatch failing = batch();
            final RegisterBatch following = batch();
            aNightHolding(failing, following);
            when(service.request(eq(failing), any(), any())).thenReturn(requested(failing,
                    BatchStatus.FAILED, BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE));
            when(service.request(eq(following), any(), any()))
                    .thenReturn(requested(following, BatchStatus.GENERATING, null));

            run();

            verify(service).request(eq(following), any(), any());
        }

        @Test
        void every_batch_should_be_measured_against_the_deadline_the_run_started_with() {
            aNightHolding(batch(), batch());
            when(service.request(any(), any(), any())).thenAnswer(call -> {
                clock.advance(Duration.ofMinutes(5));
                return requested(call.getArgument(0), BatchStatus.GENERATING, null);
            });

            run();

            final ArgumentCaptor<Deadline> deadlines = ArgumentCaptor.forClass(Deadline.class);
            verify(service, times(2)).request(any(), deadlines.capture(), any());
            softly.assertThat(deadlines.getAllValues())
                    .as("computed once, at the moment requesting began: a bound re-derived per "
                            + "batch grows by whatever the batch before it took, and a run that "
                            + "kept extending its own deadline would collide with the next one")
                    .containsOnly(Deadline.startingAt(SIX_PM, RUN_DEADLINE));
        }
    }

    /**
     * The bound on requesting, and the two things it deliberately does not bound.
     */
    @Nested
    @DisplayName("the run deadline")
    class TheRunDeadline {

        /** Sets the night up as two batches, the first of which spends the whole budget. */
        private RegisterBatch aBatchTheRunRanOutOfTimeFor() {
            final RegisterBatch first = batch();
            final RegisterBatch stranded = batch();
            aNightHolding(first, stranded);
            when(service.request(eq(first), any(), any())).thenAnswer(call -> {
                clock.advance(RUN_DEADLINE.plusMinutes(1));
                return requested(first, BatchStatus.GENERATING, null);
            });
            return stranded;
        }

        @Test
        void a_batch_the_run_had_no_time_left_for_should_not_be_requested() {
            final RegisterBatch stranded = aBatchTheRunRanOutOfTimeFor();

            run();

            verify(service, never()).request(eq(stranded), any(), any());
        }

        @Test
        void a_batch_the_run_had_no_time_left_for_should_be_reported_as_still_pending() {
            aBatchTheRunRanOutOfTimeFor();

            final RunReport report = run();

            softly.assertThat(reported(report, RunReport::outcomes))
                    .as("the counts add up to the batches the night held, or a run that ran out "
                            + "of time reads as a quieter night than it was; and PENDING rather "
                            + "than FAILED because nothing went wrong with that batch - it is "
                            + "waiting for tomorrow's run, which will find it active and unbatched")
                    .isEqualTo(Map.of(BatchStatus.GENERATING, 1, BatchStatus.PENDING, 1));
        }

        @Test
        void the_batches_a_run_left_behind_should_be_gauged() {
            aBatchTheRunRanOutOfTimeFor();

            run();

            softly.assertThat(pendingAfterDeadline())
                    .as("the reading that says the night is no longer finishing inside its hour, "
                            + "which is the one failure a nightly flow can have repeatedly without "
                            + "anything ever failing")
                    .isEqualTo(1);
        }

        @Test
        void a_run_that_finished_inside_its_hour_should_leave_nothing_behind() {
            aNightHolding(batch(), batch());
            everyRequestIsAccepted();

            run();

            softly.assertThat(pendingAfterDeadline())
                    .as("a gauge that only ever moved up would need a run to fail before it could "
                            + "come down again")
                    .isZero();
        }

    }

    /**
     * The run's first act: giving back what the night before did not finish.
     *
     * <p>Where the pass sits is the whole of increment 004. A batch still awaiting its render when
     * this run begins is failed and its registers released, so the assembler puts them in a batch
     * tonight and the court centre gets its document tonight - which is true only if the pass has
     * already run by the time the store is read. A pass after the assembly would release into a
     * night that had already been decided, and the court centre would wait again.
     */
    @Nested
    @DisplayName("releasing what the night before did not finish")
    class Releasing {

        @Test
        void the_releaser_runs_after_the_gate_and_before_the_store_is_read() {
            aNightHolding(batch());
            everyRequestIsAccepted();

            run();

            final InOrder order = inOrder(gate, releaser, store, assembler);
            order.verify(gate).decide(false);
            order.verify(releaser).releaseStale(any());
            order.verify(store).activeUnbatched();
            order.verify(assembler).assemble(any(), any(), anyBoolean());
        }

        /**
         * Both nights FR-018 names, and not one of them standing in for the other.
         *
         * <p>FR-005 forbids the pass on a run the flag stopped, and FR-018 states the cost: a
         * court centre whose batch went stale is not given back tonight, because a run that may
         * not generate may not decide that a batch it could not re-render has failed. The
         * fail-closed night is the one worth asserting separately - it is the night nobody chose,
         * reached by an App Configuration outage rather than by the cutover, and the temptation to
         * "do the safe half of the run anyway" is exactly what the shared {@code Skipped} branch
         * would let somebody act on without failing a test.
         *
         * @param reason the two answers that stop a run
         */
        @ParameterizedTest(name = "[A] {0}")
        @EnumSource(value = Reason.class, names = {"FLAG_OFF", "FLAG_UNREADABLE"})
        void a_skipped_run_does_not_release_anything(final Reason reason) {
            theGateAnswers(new Skipped(reason));

            run();

            verifyNoInteractions(releaser);
        }

        /**
         * The promise the increment is for, stated as a sequence the store can see.
         *
         * <p>The store answers with the released registers only once the pass has run, so a run
         * that read before it released would assemble an empty night - and the assertion is that
         * the registers the pass gave back are the ones the assembler was handed, in the same run.
         */
        @Test
        void released_registers_reach_the_assembler_in_the_same_run() {
            final AtomicReference<Boolean> released = new AtomicReference<>(false);
            theGateAnswers(new Proceed(false));
            doAnswer(call -> {
                released.set(true);
                final StaleBatchReleaser.ReleaseTally tally =
                        new StaleBatchReleaser.ReleaseTally(1, ACTIVE.size(), 0);
                call.<Consumer<StaleBatchReleaser.ReleaseTally>>getArgument(0).accept(tally);
                return tally;
            }).when(releaser).releaseStale(any());
            when(store.activeUnbatched())
                    .thenAnswer(call -> released.get() ? ACTIVE : List.<RegisterRecord>of());
            when(assembler.assemble(any(), any(), anyBoolean())).thenReturn(assembly());

            run();

            verify(assembler).assemble(eq(ACTIVE), any(), anyBoolean());
        }

        /**
         * A store lost under the pass ends the run the way a store lost anywhere else does.
         *
         * <p>Reported and rethrown, never logged and continued: the pass is the run's first act, so
         * a night that lost the store there has generated nothing at all, and the one line it can
         * still write is a night of zeroes. What must not happen is a run that swallowed it and
         * went on to assemble against a store that is not answering (constitution Principle VI).
         */
        @Test
        void a_releaser_that_throws_still_writes_a_line_and_rethrows() {
            theGateAnswers(new Proceed(false));
            doThrow(new StoreUnavailableException("the register store did not answer", null))
                    .when(releaser).releaseStale(any());

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                final Throwable stopped = whatStoppedTheRun();

                softly.assertThat(stopped)
                        .as("a store outage under the pass is the run's own failure, and a failure "
                                + "that was only reported has not been settled")
                        .isInstanceOf(StoreUnavailableException.class);
                softly.assertThat(normalisedRunLines(log))
                        .as("and the night still leaves the one line that says it did nothing")
                        .containsExactly(NOTHING_YET);
            }
            verifyNoInteractions(store, assembler, service);
        }

        /**
         * The two lines one night leaves may not disagree about that night.
         *
         * <p>A pass the store interrupts has still committed what it gave back, and says so at
         * WARN and on the three counters. The run's line is written from the same night under the
         * same {@code run_id}, so a run that read the pass's account from the return value - which
         * a throw never delivers - would report {@code released_batches=0} beside a pass's line
         * saying one, and a reader would have two accounts of one night with no way to tell which
         * is the night (FR-009).
         */
        @Test
        void a_pass_the_store_interrupted_still_puts_its_account_on_the_run_line() {
            theGateAnswers(new Proceed(false));
            doAnswer(call -> {
                call.<Consumer<StaleBatchReleaser.ReleaseTally>>getArgument(0).accept(
                        new StaleBatchReleaser.ReleaseTally(
                                INTERRUPTED_BATCHES, INTERRUPTED_REGISTERS, INTERRUPTED_CONTENDED));
                throw new StoreUnavailableException("the register store did not answer", null);
            }).when(releaser).releaseStale(any());

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                softly.assertThat(whatStoppedTheRun())
                        .as("the outage is still the run's own failure and still leaves it")
                        .isInstanceOf(StoreUnavailableException.class);

                final Map<String, String> fields = fieldsOf(theOneLine(log));
                softly.assertThat(onTheLine(fields, "released_batches"))
                        .as("and the night's own line carries what the pass had already given "
                                + "back, rather than a nought the pass's line contradicts")
                        .isEqualTo(INTERRUPTED_BATCHES);
                softly.assertThat(onTheLine(fields, "released_registers"))
                        .as("with the hearings that came back with those batches")
                        .isEqualTo(INTERRUPTED_REGISTERS);
                softly.assertThat(onTheLine(fields, "contended"))
                        .as("and what the pass had already found it could not give back, which is "
                                + "undone work and may not be silent on a night that stopped")
                        .isEqualTo(INTERRUPTED_CONTENDED);
            }
        }

        @Test
        void the_run_line_carries_both_released_numbers() {
            aMixedNight();

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                final Map<String, String> fields = fieldsOf(theOneLine(log));
                softly.assertThat(onTheLine(fields, "released_batches"))
                        .as("how many court centre days this night had to give up on, which is "
                                + "the one number that says an outcome went missing")
                        .isEqualTo(RELEASED_BATCHES);
                softly.assertThat(onTheLine(fields, "released_registers"))
                        .as("and how many hearings' registers came back with them, because a batch "
                                + "is one document and a register is one hearing's youth defendants")
                        .isEqualTo(RELEASED_REGISTERS);
                softly.assertThat(onTheLine(fields, "contended"))
                        .as("and what the pass could not give back, which is a night's undone work "
                                + "and must not be silent on the line that describes the night")
                        .isEqualTo(CONTENDED);
            }
        }

        @Test
        void a_run_that_released_nothing_says_zero() {
            aNightHolding(batch());
            everyRequestIsAccepted();

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                final Map<String, String> fields = fieldsOf(theOneLine(log));
                softly.assertThat(onTheLine(fields, "released_batches"))
                        .as("a night that released nothing and a night that did not report are "
                                + "different lines, and only a nought told apart from an absence "
                                + "can be alerted on")
                        .isZero();
                softly.assertThat(onTheLine(fields, "released_registers")).isZero();
                softly.assertThat(onTheLine(fields, "contended")).isZero();
            }
        }

        /**
         * <strong>[A]</strong> A batch the pass could not give back does not stop the night.
         *
         * <p>FR-003a: exhaustion is reported, never thrown. A batch whose every attempt lost the
         * day's active-register key is left exactly as it was found and named in the pass's
         * answer, and the run goes on to read the store and assemble - the batches it could not
         * release are stale still, so the next run reaches them again. The plan's test matrix
         * claims this row for this suite; until now it was only carried incidentally, by the mixed
         * night's line happening to report a non-zero {@code contended}.
         *
         * <p>Green on introduction: the run has never read the third number for anything but the
         * line, which is the property being pinned - a pass that gave nothing back is not a pass
         * that failed.
         */
        @Test
        void a_batch_the_pass_could_not_release_should_not_stop_the_run() {
            aNightHolding(batch());
            everyRequestIsAccepted();
            theStaleBatchPassGaveBack(new StaleBatchReleaser.ReleaseTally(0, 0, CONTENDED));

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                verify(store).activeUnbatched();
                verify(assembler).assemble(any(), any(), anyBoolean());
                final Map<String, String> fields = fieldsOf(theOneLine(log));
                softly.assertThat(onTheLine(fields, "contended"))
                        .as("the night carried on and said what it had left undone, which is the "
                                + "whole of FR-003a: a court centre the pass could not free is "
                                + "not a reason to leave every other court centre unrendered")
                        .isEqualTo(CONTENDED);
                softly.assertThat(onTheLine(fields, "released_batches"))
                        .as("and nothing was given back, so neither released number may claim it "
                                + "was")
                        .isZero();
            }
        }

        /**
         * The word has to be gone from the format string, not merely nought.
         *
         * <p>A whole-line assertion rather than a field one: a line still carrying
         * {@code reconciled=0} would describe a mechanism this service no longer has, and every
         * dashboard and alert written against it would go on reading as though it did.
         */
        @Test
        void the_run_line_carries_no_reconciled_anywhere() {
            aMixedNight();

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                softly.assertThat(theOneLine(log))
                        .as("no vocabulary outlives the thing it names: nothing is reconciled any "
                                + "more, because nothing is asked of systemdocgenerator between "
                                + "runs")
                        .isNotNull()
                        .doesNotContain("reconciled");
            }
        }

        /**
         * The one thing a reader of a line of totals will otherwise assume.
         *
         * <p>The released registers are re-batched by this same run, so they are already inside
         * {@code rows}. They are a diagnostic beside the night's two accounts and not a third sum,
         * and a line whose totals had quietly grown by them would be a night that counted the same
         * registers twice (FR-009).
         */
        @Test
        void the_released_registers_are_not_added_to_either_total() {
            aMixedNight();

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                final Map<String, String> fields = fieldsOf(theOneLine(log));
                softly.assertThat(onTheLine(fields, "rows"))
                        .as("every register this run accounted for, batched or left waiting - and "
                                + "not one more for the seven the pass handed back into it")
                        .isEqualTo(THE_MIXED_NIGHTS_ROWS);
                softly.assertThat(onTheLine(fields, "batches"))
                        .as("and the batches it assembled, not the batches it failed")
                        .isEqualTo(3);
            }
        }
    }

    /**
     * The one line a night leaves behind.
     */
    @Nested
    @DisplayName("the run report")
    class TheReport {

        @Test
        void the_report_should_count_the_batches_by_how_the_run_left_them() {
            final RegisterBatch failing = batch();
            aNightHolding(batch(), failing, batch());
            when(service.request(any(), any(), any())).thenAnswer(call -> {
                final RegisterBatch asked = call.getArgument(0);
                return failing.equals(asked)
                        ? requested(asked, BatchStatus.FAILED,
                                BatchFailureReason.RENDER_REQUEST_REJECTED)
                        : requested(asked, BatchStatus.GENERATING, null);
            });

            final RunReport report = run();

            softly.assertThat(reported(report, RunReport::outcomes))
                    .as("what a run adds to the durable rows is the shape of the night, and the "
                            + "shape is how many ended each way")
                    .isEqualTo(Map.of(BatchStatus.GENERATING, 2, BatchStatus.FAILED, 1));
        }

        @Test
        void the_report_should_say_how_long_the_run_took() {
            aNightHolding(batch());
            when(service.request(any(), any(), any())).thenAnswer(call -> {
                clock.advance(Duration.ofMinutes(7));
                return requested(call.getArgument(0), BatchStatus.GENERATING, null);
            });

            final RunReport report = run();

            softly.assertThat(reported(report, RunReport::duration))
                    .as("measured on the run's own clock and against the deadline it works to, "
                            + "which is how a night that is drifting towards its hour is seen "
                            + "before the night it runs out of it")
                    .isEqualTo(Duration.ofMinutes(7));
        }

        /**
         * A deferred key is the one thing a run does that no other number it reports covers.
         *
         * <p>Its registers were read as active and no batch was assembled for them, so they appear
         * in none of the outcome counts, and the court centre gets no document tonight.
         * {@code BatchAssembly.deferred} exists so the run can say so, and until it reached the
         * report and the line it said it to nobody.
         */
        @Test
        void the_report_should_count_the_keys_the_run_deferred() {
            aNightDeferring(key());

            final RunReport report = run();

            softly.assertThat(reported(report, RunReport::deferredKeys))
                    .as("a key whose earlier batch is still in flight produced no batch tonight, "
                            + "so it is in none of the outcome counts and would otherwise leave "
                            + "the run with nothing at all to say about it")
                    .isEqualTo(1);
        }

        /**
         * What the run asked for, which is the half of the night it is answerable for.
         *
         * <p>The requesting leg ends when systemdocgenerator has been asked, so this is the count
         * of what the run actually achieved rather than of what came of it: a batch counted here
         * had its payload written and its render requested. Neither {@code batches} nor the outcome
         * counts say it - the first counts batches the run may never have got to and the second
         * puts a batch refused by the renderer and a batch whose payload never reached the file
         * service under the same FAILED.
         */
        @Test
        void the_report_should_count_the_batches_the_run_asked_the_renderer_for() {
            aMixedNight();

            final RunReport report = run();

            softly.assertThat(reported(report, RunReport::requested))
                    .as("what the run asked systemdocgenerator for: two of the three batches, the "
                            + "third having never been written down at all")
                    .isEqualTo(2);
        }

        /**
         * What the requesting leg says it did, rather than what its reason can be read to imply.
         *
         * <p>Both of this night's batches failed {@code RENDER_REQUEST_FAILED} and only one of them
         * was sent, so the count cannot be derived from the reason at all - it is the requesting
         * leg's own account of whether the call was made, and nothing else in the run has it.
         */
        @Test
        void the_report_should_count_only_the_renders_the_run_actually_sent() {
            aNightOfOneReasonAndOneRequest();

            final RunReport report = run();

            softly.assertThat(reported(report, RunReport::requested))
                    .as("one of the two: the request that left this service and was never "
                            + "answered. The batch beside it ended under the same reason with the "
                            + "run's budget too small to hold a single attempt, so nothing was "
                            + "ever sent about it")
                    .isEqualTo(1);
        }

        /**
         * The registers behind the batches, which are what a Youth Offending Team is waiting for.
         *
         * <p>A count of batches says how many documents were asked for and a count of registers
         * says how many hearings are in them, and the two come apart on exactly the night worth
         * reading: one batch left for the next run is one court centre, and how many youth
         * defendants' registers are inside it is what decides whether that matters tonight.
         */
        @Test
        void the_report_should_count_the_registers_the_run_accounted_for_by_outcome() {
            aMixedNight();

            final RunReport report = run();

            softly.assertThat(reported(report, RunReport::rowOutcomes))
                    .as("every register the run stamped into a batch, under the state that "
                            + "batch's requesting leg ended in")
                    .isEqualTo(Map.of(BatchStatus.GENERATING, GENERATING_ROWS,
                            BatchStatus.FAILED, FAILING_ROWS,
                            BatchStatus.PENDING, UNSTAMPABLE_ROWS));
            softly.assertThat(reported(report, RunReport::deferredRows))
                    .as("and the registers under the days it passed over, which are in no batch "
                            + "at all and would otherwise be counted by nothing")
                    .isEqualTo(WAITING_ROWS);
        }

        @Test
        void the_line_the_run_leaves_behind_should_carry_the_keys_it_deferred() {
            aNightDeferring(key());

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                softly.assertThat(log.messages())
                        .as("the report is a value a test can read and the line is what an "
                                + "operator reads; a count that reached only the first of them "
                                + "would be a night's deferral nobody sees")
                        .anyMatch(line -> line.contains("deferred=1"));
            }
        }

        @Test
        void the_keys_the_run_deferred_should_be_gauged() {
            aNightDeferring(key(), key());

            run();

            softly.assertThat(deferredKeys())
                    .as("the companion of the oldest-unbatched age: that says how long the worst "
                            + "of them has waited and this says how much of the estate is waiting")
                    .isEqualTo(2);
        }

        /**
         * The registers behind the deferred days, which the keys gauge cannot say.
         *
         * <p>A court centre day is one key however many children's registers are inside it, so
         * {@code deferred_keys=3} is the same reading whether three registers are waiting or four
         * hundred. The run line already draws that distinction - {@code rows} sits beside
         * {@code batches} for exactly this reason - but the line is per run and the gauges are what
         * a dashboard reads in the twenty-three hours between them. Without this, the only
         * between-runs readings are how many court centres are waiting and how long the worst has
         * waited, and neither answers how much is undelivered.
         */
        @Test
        void the_registers_behind_the_deferred_keys_should_be_gauged_too() {
            aNightDeferringARegisterRecordedAt(SIX_PM.minusSeconds(93_600));

            run();

            softly.assertThat(deferredRegisters())
                    .as("the registers waiting under those keys, which is the impact reading: a "
                            + "count of court centres cannot say how many children's registers "
                            + "have not gone out")
                    .isEqualTo(1);
        }

        @Test
        void a_run_that_deferred_nothing_should_bring_that_gauge_back_down() {
            aNightDeferring(key());
            run();

            aNightHolding(batch());
            when(assembler.assemble(any(), any(), anyBoolean()))
                    .thenReturn(assembly(batch()));
            run();

            softly.assertThat(deferredKeys())
                    .as("a gauge that only ever went up would need a run to fail before it could "
                            + "come down again")
                    .isZero();
        }

        @Test
        void a_run_that_did_nothing_at_all_should_still_report() {
            aNightHolding();

            final RunReport report = run();

            softly.assertThat(report)
                    .as("before cutover this is every night, and a silence that meant both "
                            + "\"the flag is off\" and \"the job did not fire\" would hide the "
                            + "second inside the first for months")
                    .isNotNull();
        }
    }

    /**
     * The line itself, field by field, as an operator and an alert read it.
     *
     * <p><strong>Mixed, and each case says which it is.</strong> The line has been written for
     * every run, skipped ones included, since {@code 6d7aca8} landed the nightly run, and the ten
     * fields it carried until this task were characterised here rather than driven - what was
     * missing was anything holding them down, so any of them could have been renamed or dropped
     * with the whole suite still green. The six fields this task adds are the other kind: the
     * report was carrying what the run <em>asked</em> for and nothing about what it accounted for,
     * and User Story 7 asks for the requested count and the registers per outcome as well. The
     * cases about {@code requested}, {@code rows} and the four {@code rows_*} counts, and the
     * whole-line cases they widen, therefore fail before the implementation that answers them; the
     * rest still pass on introduction and are shown non-vacuous by mutation instead.
     *
     * <p><strong>And the five the settled snapshot adds are the same kind.</strong> User Story 7
     * asks the line for {@code generated} and {@code notified} as well, and the reading that left
     * them out - that they are zero by construction, the requesting leg ending where it does - does
     * not hold: the event listener can mark and notify a fast render while later court centres of
     * the same run are still being requested, and neither the cumulative
     * {@code yotresultsdistribution_batches_total} counter nor the age gauges can be asked a question about
     * one night. So the run keeps the identities it assembled, reads their states back from the
     * store once at the moment it writes the line, and counts them - with {@code snapshot} saying
     * whether that read stands. The three cases below and the whole-line cases they widen fail
     * before the implementation that answers them.
     *
     * <p>The gauges are the other half of the same report (data model: "recorded as the run report
     * (log + gauges), not as a table"). Their names, their labels and their readings are pinned in
     * {@code GenerationMetricsTest}; what belongs here is that the run publishes them, which for
     * two of the three {@code TheReport} and {@code TheRunDeadline} already assert and for the
     * third - the age of the oldest register the run left waiting - nothing did.
     */
    @Nested
    @DisplayName("the fields on the line")
    class TheLine {

        @Test
        void every_field_of_the_report_should_be_on_the_line_a_night_is_read_by() {
            aMixedNight();

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                softly.assertThat(normalisedRunLines(log))
                        .as("one line per run and every field of the report on it: the durable "
                                + "rows say what happened to each batch, and this is the only "
                                + "place that says what happened to the night")
                        .containsExactly(THE_MIXED_NIGHTS_LINE);
            }
        }

        @Test
        void a_night_the_flag_stopped_should_leave_the_same_line_with_nothing_on_it() {
            theGateAnswers(new Skipped(Reason.FLAG_OFF));

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                softly.assertThat(normalisedRunLines(log))
                        .as("before cutover this is every night, and the same fields with zeroes "
                                + "in them is what makes \"the flag is off\" different from \"the "
                                + "scheduler never fired\" in a log index")
                        .containsExactly(THE_SKIPPED_NIGHTS_LINE);
            }
        }

        @Test
        void a_run_an_operator_overrode_should_be_on_the_line_as_overridden_and_not_as_flag_on() {
            theGateAnswers(new Proceed(true));
            when(store.activeUnbatched()).thenReturn(ACTIVE);
            when(assembler.assemble(any(), any(), anyBoolean())).thenReturn(assembly());

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                softly.assertThat(fieldsOf(theOneLine(log)))
                        .as("the one night on which this service generated while the flag said the "
                                + "legacy was; a line that called it flag-on would leave nothing "
                                + "at all to find it by")
                        .containsEntry("gate", "proceed")
                        .containsEntry("reason", Reason.OVERRIDDEN.code());
            }
        }

        @Test
        void a_flag_nobody_could_read_should_be_on_the_line_as_its_own_reason_and_not_as_off() {
            theGateAnswers(new Skipped(Reason.FLAG_UNREADABLE));

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                softly.assertThat(fieldsOf(theOneLine(log)))
                        .as("fail-closed and off are the same restraint and not the same night: "
                                + "one is the cutover working and the other is an outage this "
                                + "service rode out")
                        .containsEntry("gate", "skipped")
                        .containsEntry("reason", Reason.FLAG_UNREADABLE.code());
            }
        }

        /**
         * The total has to be the night, or the line describes a quieter one than there was.
         *
         * <p>{@code batches} is the sum over every state a batch ended in and the three named
         * counts are the three states the requesting leg can produce, so the two agreeing is what
         * says nothing has fallen out of the line. A fourth state reaching the report - a batch
         * counted GENERATED by the requesting leg, say - would show up here as a total that no
         * longer adds up rather than as a count nobody notices is missing.
         */
        @Test
        void the_counts_on_the_line_should_add_up_to_the_batches_the_run_saw() {
            final List<RegisterBatch> night = aMixedNight();

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                final Map<String, String> fields = fieldsOf(theOneLine(log));
                softly.assertThat(onTheLine(fields, "batches"))
                        .as("every batch the assembler answered with is counted exactly once, "
                                + "and under one of the three states the requesting leg can "
                                + "leave a batch in")
                        .isEqualTo(night.size())
                        .isEqualTo(onTheLine(fields, "generating") + onTheLine(fields, "failed")
                                + onTheLine(fields, "pending"));
            }
        }

        /**
         * Asked and refused is not the same night as never asked, and only one field says so.
         *
         * <p>{@code generating} counts the renders systemdocgenerator accepted, so on a night when
         * every request was accepted the two agree and {@code requested} looks redundant. The night
         * they come apart is the night worth finding: a batch whose payload never reached the file
         * service was never asked about, a batch the renderer answered with something other than
         * the contract's 202 was, and both end FAILED - so {@code failed} cannot tell an outage in
         * this service from a refusal by another one.
         */
        @Test
        void a_batch_that_failed_before_the_renderer_was_asked_should_not_be_counted_as_requested() {
            final RegisterBatch neverAsked = batch();
            final RegisterBatch refused = batch();
            aNightHolding(neverAsked, refused);
            when(service.request(eq(neverAsked), any(), any())).thenAnswer(call -> neverRequested(
                    neverAsked, BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE));
            when(service.request(eq(refused), any(), any())).thenAnswer(call -> requested(refused,
                    BatchStatus.FAILED, BatchFailureReason.RENDER_REQUEST_REJECTED));

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                final Map<String, String> fields = fieldsOf(theOneLine(log));
                softly.assertThat(onTheLine(fields, "requested"))
                        .as("one render left this service and one batch never got that far, and "
                                + "a line that counted both would report a renderer refusing "
                                + "documents it was never sent")
                        .isEqualTo(1);
                softly.assertThat(onTheLine(fields, "failed"))
                        .as("both still failed, which is why the outcome count cannot carry the "
                                + "distinction on its own")
                        .isEqualTo(2);
            }
        }

        /**
         * The same distinction on the night the reason cannot carry it either.
         *
         * <p>The case above pairs a reason no sent batch can end under with one only a sent batch
         * can, so a line that read the request off the reason would get that night right by
         * accident. This one gives both batches {@code RENDER_REQUEST_FAILED}: the ending of a
         * request that was made and answered nothing inside the budget, and the ending of a batch
         * the run reached with less budget left than one whole attempt, which asked the renderer
         * nothing. Only the requesting leg knows which, and the line has to say what it knew.
         */
        @Test
        void a_render_the_run_never_sent_should_not_be_on_the_line_as_requested() {
            aNightOfOneReasonAndOneRequest();

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                final Map<String, String> fields = fieldsOf(theOneLine(log));
                softly.assertThat(onTheLine(fields, "requested"))
                        .as("one request left this service; the other batch ended under the same "
                                + "reason without one being made, and a line counting both would "
                                + "have an operator investigating a renderer that was sent one "
                                + "document and refused none")
                        .isEqualTo(1);
                softly.assertThat(onTheLine(fields, "failed"))
                        .as("both failed, under one reason, so neither the outcome count nor the "
                                + "reason behind it can tell the two nights apart")
                        .isEqualTo(2);
            }
        }

        /**
         * A request the run cannot have made is one the line must not claim.
         *
         * <p>The bound in both directions: every batch the renderer accepted was asked for, and no
         * batch the run left for the next one was - so the count sits between what was accepted and
         * what the run got any verdict at all about. A {@code requested} outside that range is a
         * night reported as busier, or quieter, than the batches it accounted for.
         */
        @Test
        void the_batches_asked_for_should_sit_between_the_ones_accepted_and_the_ones_verdicted() {
            aMixedNight();

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                final Map<String, String> fields = fieldsOf(theOneLine(log));
                softly.assertThat(onTheLine(fields, "requested"))
                        .as("at least the renders that were accepted and at most the batches the "
                                + "requesting leg reached, which is the night minus the ones left "
                                + "for tomorrow")
                        .isBetween(onTheLine(fields, "generating"),
                                onTheLine(fields, "batches") - onTheLine(fields, "pending"));
            }
        }

        /**
         * The registers have to add up to the night too, or the line describes a smaller one.
         *
         * <p>{@code rows} is every register the store answered with, and each of them ended the run
         * in exactly one place: stamped into a batch that is generating, into one that failed, into
         * one left for the next run, or waiting under a day the assembler passed over. A register in
         * none of those four is one nobody is told is missing, which is the failure this whole leg
         * was absorbed to stop.
         */
        @Test
        void the_row_counts_on_the_line_should_add_up_to_the_registers_the_run_saw() {
            aMixedNight();

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                final Map<String, String> fields = fieldsOf(theOneLine(log));
                softly.assertThat(onTheLine(fields, "rows"))
                        .as("every register the store called active is counted exactly once, and "
                                + "under one of the four things a run can leave a register in")
                        .isEqualTo(THE_MIXED_NIGHTS_ROWS)
                        .isEqualTo(onTheLine(fields, "rows_generating")
                                + onTheLine(fields, "rows_failed")
                                + onTheLine(fields, "rows_pending")
                                + onTheLine(fields, "rows_deferred"));
            }
        }

        /**
         * What the store says tonight's batches came to, which is not what the run saw.
         *
         * <p>The requesting leg ends when the renderer has been asked, so the run's own account
         * cannot go past GENERATING - but the run is not the only thing writing to
         * {@code register_batch} while it is going on. A court centre whose render came back in
         * seconds is marked by the event listener and notified while the run is still asking about
         * the court centres behind it, and nothing the requesting leg saw can say so. Read back by
         * identity at the moment the line is written, the night says one batch told, one document
         * rendered and one still out - and the registers behind each, because one settled batch is
         * one court centre and how many youth defendants are inside it is the number that matters.
         *
         * <p><strong>The two counts sit inside the requesting leg's account rather than beside
         * it.</strong> Every batch counted here was counted GENERATING by the run, so
         * {@code generated} and {@code notified} do not partition anything and are not subtracted
         * from {@code generating}; they are read as bounds against it here for that reason.
         */
        @Test
        void the_batches_the_night_had_already_settled_should_be_counted_where_it_reports() {
            final List<RegisterBatch> night = aNightThatIsAlreadySettling();
            theStoreSaysTheyAreNow(nowHeldAt(night.get(0), BatchStatus.NOTIFIED),
                    nowHeldAt(night.get(1), BatchStatus.GENERATED),
                    nowHeldAt(night.get(2), BatchStatus.GENERATING));

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                final Map<String, String> fields = fieldsOf(theOneLine(log));
                softly.assertThat(fields)
                        .as("the counts are what the store said and the line says they are, so a "
                                + "reader is not left to guess whether the night was read back")
                        .containsEntry("snapshot", "taken");
                softly.assertThat(onTheLine(fields, "generated"))
                        .as("every batch whose document exists by the time the line is written, "
                                + "whether or not anybody has been told about it yet")
                        .isEqualTo(2);
                softly.assertThat(onTheLine(fields, "notified"))
                        .as("and the one the notifying leg has finished with, which is the batch "
                                + "a Youth Offending Team has actually had")
                        .isEqualTo(1);
                softly.assertThat(onTheLine(fields, "rows_generated"))
                        .as("the registers behind those two batches, which is what a count of "
                                + "court centres cannot say")
                        .isEqualTo(NOTIFIED_ROWS + GENERATED_ROWS);
                softly.assertThat(onTheLine(fields, "rows_notified"))
                        .as("and the registers behind the one that was told about")
                        .isEqualTo(NOTIFIED_ROWS);
                softly.assertThat(onTheLine(fields, "generated"))
                        .as("a settled batch was requested by this run, so the snapshot cannot "
                                + "claim more batches than the requesting leg left GENERATING")
                        .isLessThanOrEqualTo(onTheLine(fields, "generating"));
                softly.assertThat(onTheLine(fields, "rows_generated"))
                        .as("and the registers behind them are the same registers, counted in the "
                                + "same batches")
                        .isLessThanOrEqualTo(onTheLine(fields, "rows_generating"));
            }
        }

        /**
         * The ordinary night, on which nothing has come back yet and the line says so.
         *
         * <p>The other half of the case above, and the reason the snapshot is worth taking at all:
         * zeroes here are a reading rather than an absence. A night whose renders are all still out
         * and a night whose read was refused are the same four zeroes, and only {@code snapshot}
         * separates them.
         */
        @Test
        void a_night_nothing_had_come_back_for_should_be_on_the_line_as_nothing_settled() {
            final List<RegisterBatch> night = aNightThatIsAlreadySettling();
            theStoreSaysTheyAreNow(nowHeldAt(night.get(0), BatchStatus.GENERATING),
                    nowHeldAt(night.get(1), BatchStatus.GENERATING),
                    nowHeldAt(night.get(2), BatchStatus.GENERATING));

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                final Map<String, String> fields = fieldsOf(theOneLine(log));
                softly.assertThat(fields)
                        .as("the store answered, and what it answered is that the night is still "
                                + "out; that is a reading and not a gap")
                        .containsEntry("snapshot", "taken");
                softly.assertThat(onTheLine(fields, "generated"))
                        .as("nothing had a document yet")
                        .isZero();
                softly.assertThat(onTheLine(fields, "notified"))
                        .as("so nobody had been told")
                        .isZero();
                softly.assertThat(onTheLine(fields, "rows_generated"))
                        .as("and no register was behind either count")
                        .isZero();
                softly.assertThat(onTheLine(fields, "rows_notified")).isZero();
            }
        }

        /**
         * <strong>[A]</strong> All three of the notifying leg's endings, and the P1 one included.
         *
         * <p>Green on introduction: the classification is read off the state machine rather than
         * listed, so the three endings are already exactly the states GENERATED may move to and
         * this states which three they are. It is worth stating because the pair above only ever
         * drove two states through the count, NOTIFIED and GENERATED, and the two it did not are
         * the two a reader would most easily leave out - PARTIALLY_NOTIFIED, because some of the
         * recipients are still resendable, and NOTIFIED_NOBODY, because there was nobody to tell at
         * all (defect fix P1). Both are the notifying leg finished with a batch, and a
         * {@code notified} count that left them out would report a night's registers as still
         * waiting on an e-mail nobody is going to send.
         *
         * <p>The distinction between the three is kept where it belongs and is deliberately not on
         * this line: the row carries it and {@code yotresultsdistribution_batches_total} by outcome counts
         * it, and a count of batches cannot say which ending each reached without becoming three
         * more fields.
         *
         * <p><strong>Non-vacuity by a reverted mutation.</strong> With
         * {@code RegisterGenerationJob.hasBeenNotifiedAbout} narrowed from the state machine's own
         * answer to {@code status == BatchStatus.NOTIFIED} - the list a reader writes out by hand -
         * this case fails and only this case: 54 tests completed, 1 failed, all three of its
         * assertions - "[every ending the notifying leg can reach is the notifying leg finished
         * with the batch: told everybody, told some, and had nobody to tell] expected: 3 but was:
         * 1", "[and the registers behind all three, which is every register the night got out]
         * expected: 6 but was: 3", and "[a batch that was notified was generated first, so a count
         * of documents that fell as the night progressed would be unreadable] expected: 3 but was:
         * 1", the last of those because the document count is derived from this predicate and a
         * narrowing of it takes both counts down together. Mutation reverted.
         */
        @Test
        void every_ending_the_notifying_leg_can_reach_should_count_as_notified() {
            final List<RegisterBatch> night = aNightThatIsAlreadySettling();
            theStoreSaysTheyAreNow(nowHeldAt(night.get(0), BatchStatus.NOTIFIED),
                    nowHeldAt(night.get(1), BatchStatus.PARTIALLY_NOTIFIED),
                    nowHeldAt(night.get(2), BatchStatus.NOTIFIED_NOBODY));

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                final Map<String, String> fields = fieldsOf(theOneLine(log));
                softly.assertThat(onTheLine(fields, "notified"))
                        .as("every ending the notifying leg can reach is the notifying leg "
                                + "finished with the batch: told everybody, told some, and had "
                                + "nobody to tell")
                        .isEqualTo(3);
                softly.assertThat(onTheLine(fields, "rows_notified"))
                        .as("and the registers behind all three, which is every register the "
                                + "night got out")
                        .isEqualTo(NOTIFIED_ROWS + GENERATED_ROWS + STILL_RENDERING_ROWS);
                softly.assertThat(onTheLine(fields, "generated"))
                        .as("a batch that was notified was generated first, so a count of "
                                + "documents that fell as the night progressed would be unreadable")
                        .isEqualTo(3);
            }
        }

        /**
         * A snapshot the store refused, which must cost the line its four counts and nothing else.
         *
         * <p>The report is read from the night's own generation and not the other way round: the
         * batches are stamped, the renders are away and the Youth Offending Teams are going to be
         * told whatever this read answers, so a store that will not answer it is not a reason to
         * fail the run. It is a reason to say the counts are missing. Four zeroes under
         * {@code snapshot=unread} say exactly that, and the class of what refused is named beside
         * them rather than swallowed - a reading nobody can tell from a quiet night is worse than
         * no reading, which is the failure mode this whole leg was absorbed to stop.
         */
        @Test
        void a_snapshot_the_store_refused_should_be_said_on_the_line_and_not_reported_as_zeroes() {
            aNightThatIsAlreadySettling();
            when(store.batchesNamed(any()))
                    .thenThrow(new IllegalStateException("the register store did not answer"));

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                final RunReport report = run();

                final Map<String, String> fields = fieldsOf(theOneLine(log));
                softly.assertThat(fields)
                        .as("the one word that separates a night that settled nothing from a night "
                                + "nobody could read; without it the four zeroes below are a lie "
                                + "an alert would act on")
                        .containsEntry("snapshot", "unread");
                softly.assertThat(onTheLine(fields, "generated")).isZero();
                softly.assertThat(onTheLine(fields, "notified")).isZero();
                softly.assertThat(onTheLine(fields, "rows_generated")).isZero();
                softly.assertThat(onTheLine(fields, "rows_notified")).isZero();
                softly.assertThat(reported(report, RunReport::outcomes))
                        .as("and the night is not lost: three batches were stamped and three "
                                + "renders were asked for, and a report that could not be "
                                + "assembled is no reason to throw that away")
                        .containsEntry(BatchStatus.GENERATING, 3);
                softly.assertThat(log.messages())
                        .as("nothing is swallowed, so what refused the read is named where the "
                                + "line says the read is missing")
                        .anyMatch(line -> line.contains(IllegalStateException.class.getName()));
            }
        }

        /**
         * The third half of it, and the one a dashboard can act on.
         *
         * <p>The word and the WARN above are read by somebody already looking. Until this counter
         * a run whose settled read failed moved no metric at all, so nothing could alert on it and
         * a fortnight of unread snapshots would show as a healthy fortnight. It counts a report
         * that came up short, never a night that did - which is why it is its own series and not a
         * reading of any batch outcome.
         */
        @Test
        void a_snapshot_the_store_refused_should_move_a_counter_something_can_alert_on() {
            aNightThatIsAlreadySettling();
            when(store.batchesNamed(any()))
                    .thenThrow(new IllegalStateException("the register store did not answer"));

            run();

            final Counter counter = registry.find(GenerationMetrics.GENERATION_UNRECORDED)
                    .tag(GenerationMetrics.REASON_TAG, GenerationMetrics.SETTLED_SNAPSHOT)
                    .counter();
            softly.assertThat(counter)
                    .as("a lost snapshot that increments nothing is a gap only the log index can "
                            + "see, and an alert cannot be written against a log index")
                    .isNotNull();
            softly.assertThat(counter == null ? -1 : counter.count()).isEqualTo(1);
        }

        /**
         * The other half of that claim, and the half the line's own bounded fields cannot make.
         *
         * <p>Naming the class of what refused the read is this service's own bounded fact. The
         * exception's message is not: it is written by a driver or a pool, it carries whatever
         * that library chose to put in it, and a store failure is exactly the moment a connection
         * string or a fragment of a statement turns up in one. So the class is written down and
         * the throwable is not attached, which is the same rule the two field readers and the two
         * message readers of the public-event listener were held to (constitution Principle VII).
         */
        @Test
        void the_words_of_whatever_refused_the_snapshot_should_not_reach_the_log() {
            aNightThatIsAlreadySettling();
            when(store.batchesNamed(any())).thenThrow(
                    new IllegalStateException(PersonalDataMarkers.OPERATOR_TOKEN));

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                softly.assertThat(log.renderings())
                        .as("the class is the bounded fact and the message is the library's own "
                                + "words, so a rendering that carries the message carries whatever "
                                + "the library put in it")
                        .anyMatch(line -> line.contains(IllegalStateException.class.getName()))
                        .noneMatch(line -> line.contains(PersonalDataMarkers.OPERATOR_TOKEN));
            }
        }

        @Test
        void nothing_on_the_line_should_be_free_text_or_anything_a_register_carries() {
            aMixedNight();

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();

                softly.assertThat(theOneLine(log))
                        .as("counts, a duration and bounded codes only - no court centre, no "
                                + "batch, no recipient and no renderer's reason text, because "
                                + "every defendant on this register is a child (constitution "
                                + "Principle VII)")
                        .matches(BOUNDED_FIELDS_ONLY);
            }
        }

        /**
         * The correlation a run has, in place of the two a delivery has.
         *
         * <p>Principle VII asks that every line about processing carry {@code requestId} and
         * {@code hearingId}. A run has neither and cannot: it is one unit of work across many
         * hearings and many batches. Before this it carried nothing at all, and the lines a
         * night writes - the job's own, and the stale-batch pass's under the same id - could not
         * be pulled out of the index as one run. On a night where the pass is also releasing
         * batches from earlier nights, that is the difference between reading a run and reading a
         * haystack.
         */
        @Test
        void every_line_a_run_writes_should_name_the_run_it_belongs_to() {
            aNightThatIsAlreadySettling();

            try (CapturedLog log = CapturedLog.everything()) {
                run();

                final List<String> runIds = log.events().stream()
                        .filter(event -> event.getLoggerName()
                                .startsWith("uk.gov.hmcts.cp.yotresultsdistribution"))
                        .map(event -> event.getMDCPropertyMap().get("runId"))
                        .distinct()
                        .toList();
                softly.assertThat(runIds)
                        .as("every line the run wrote carries one run id, and the same one: a line "
                                + "without it belongs to no run a reader can find, and two ids in "
                                + "one run would split the night in the index")
                        .hasSize(1);
                softly.assertThat(runIds.getFirst())
                        .as("and it is an identity rather than an empty slot")
                        .isNotNull();
            }
        }

        @Test
        void the_run_id_should_not_outlive_the_run() {
            aNightThatIsAlreadySettling();

            run();

            softly.assertThat(MDC.get("runId"))
                    .as("the scheduler's thread is reused, so a run id left behind would be "
                            + "inherited by the next run and by anything else that thread writes")
                    .isNull();
        }

        /**
         * Two nights, over the simplest run that still writes a line: the flag stopped it, so
         * nothing but the gate and the report is exercised and the pair can be driven twice.
         */
        @Test
        void two_runs_should_not_share_a_run_id() {
            theGateAnswers(new Skipped(Reason.FLAG_OFF));

            final String first;
            final String second;
            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();
                first = fieldsOf(theOneLine(log)).get("run_id");
            }
            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                run();
                second = fieldsOf(theOneLine(log)).get("run_id");
            }

            softly.assertThat(first)
                    .as("a correlation that repeated would merge two nights into one in the index")
                    .isNotNull()
                    .isNotEqualTo(second);
        }

        @Test
        void the_age_of_the_oldest_register_the_run_left_waiting_should_be_gauged() {
            final Duration waited = aNightDeferringARegisterRecordedAt(SIX_PM.minusSeconds(93_600));

            run();

            softly.assertThat(oldestRecordedUnbatchedAge())
                    .as("the reading that says a night was missed: a register that is never "
                            + "batched moves no counter, because nothing happened to it")
                    .isEqualTo(waited.toSeconds());
        }
    }

    /**
     * The night that stopped before it was over, which still has to be a night somebody can read.
     *
     * <p>Every case above is a run that finished, and finishing is not the only thing a run does.
     * The store can go away between the read and the stamp, {@code markPayloadMinted} can refuse,
     * the stale-batch pass's own statements can refuse: each of those leaves the run through
     * {@link RegisterGenerationJob#run()} without the report ever being written, so the night that
     * went half way is the one night that produces <em>no</em> line at all. That is worse than the
     * silence the report exists to abolish, because it is the silence of a night that did
     * something: batches were stamped, renders were asked for, and the only place a reader could
     * have seen how far it got is the line that was not written.
     *
     * <p>What must still be true when it stops:
     *
     * <ul>
     *   <li>one line, with the same fields, carrying what the run had done when it stopped - which
     *       for a run that never read anything is a night of zeroes and for one that stopped part
     *       way through requesting is the batches it had already accounted for;</li>
     *   <li>a line of its own naming what stopped it, so the report is not read as a night that
     *       simply had nothing to do;</li>
     *   <li>the failure still leaves the run, because a reported failure that was also swallowed
     *       has not been settled (constitution Principle VI);</li>
     *   <li>the gauges carry what the run learned and nothing it did not: a run that stopped
     *       while requesting knows how much of the estate it passed over, and a run that stopped
     *       before it assembled knows nothing at all and must not overwrite last night's reading
     *       with a zero it has not earned.</li>
     * </ul>
     */
    @Nested
    @DisplayName("a run that stopped part way")
    class AnUnfinishedRun {

        /** What a store outage looks like from here: an unchecked refusal, on its own words. */
        private static final String OUTAGE = "the register store did not answer";

        /**
         * The line the mixed night can write once its second batch stops the run.
         *
         * <p>One batch requested and its registers accounted for, the two days it passed over and
         * the registers waiting under them known before any of it, and nothing chased. The snapshot
         * is still taken: the identities were known the moment the assembler answered, so a run that
         * stopped part way can still say what the store makes of the batches it did stamp.
         */
        private static final String AS_FAR_AS_IT_GOT = RUN_EVENT
                + NORMALISED_RUN_ID + THE_SCHEDULE_ASKED
                + " gate=proceed reason=flag-on batches=1 requested=1 generating=1 failed=0"
                + " pending=0 deferred=2 rows=" + (GENERATING_ROWS + WAITING_ROWS)
                + " rows_generating=" + GENERATING_ROWS
                + " rows_failed=0 rows_pending=0 rows_deferred=" + WAITING_ROWS
                + NOTHING_SETTLED_YET
                + " released_batches=" + RELEASED_BATCHES
                + " released_registers=" + RELEASED_REGISTERS
                + " contended=" + CONTENDED
                + " duration_ms=60000";

        /**
         * The line a night whose one render left and whose store then refused can still write.
         *
         * <p>One render away and no batch accounted for, which is the divergence worth reading:
         * the requesting leg never answered about this batch, so it is under none of the three
         * states and its registers are in none of the row counts, and the count of what was sent
         * is the only field on the line that says the night reached systemdocgenerator at all. A
         * line reporting {@code requested=0} here would have an operator concluding that nothing
         * was asked for on the one night a document is coming back to a service that has no idea
         * it asked.
         */
        private static final String A_RENDER_AWAY_AND_NOTHING_ACCOUNTED = RUN_EVENT
                + NORMALISED_RUN_ID + THE_SCHEDULE_ASKED
                + " gate=proceed reason=flag-on batches=0 requested=1 generating=0 failed=0"
                + " pending=0 deferred=0 rows=0 rows_generating=0 rows_failed=0 rows_pending=0"
                + " rows_deferred=0" + NOTHING_SETTLED_YET + NOTHING_RELEASED_ON_THE_LINE
                + " duration_ms=0";

        /** Sets the night up as one the flag allowed and the store then refused. */
        private void aStoreThatWentAway() {
            theGateAnswers(new Proceed(false));
            when(store.activeUnbatched()).thenThrow(new IllegalStateException(OUTAGE));
        }

        /**
         * Sets the mixed night up so that its second batch's render request stops the run.
         *
         * <p>Part way on purpose: the first batch is already accounted for, the third has not been
         * looked at, and the two deferred keys were known before any of it. A report that could
         * only be written at the end would carry none of that.
         */
        private void aRunStoppedWhileRequesting() {
            final List<RegisterBatch> night = aMixedNight();
            when(service.request(eq(night.get(1)), any(), any()))
                    .thenThrow(new IllegalStateException(OUTAGE));
        }

        @Test
        void a_run_that_stopped_before_it_read_anything_should_still_leave_its_one_line() {
            aStoreThatWentAway();

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                whatStoppedTheRun();

                softly.assertThat(normalisedRunLines(log))
                        .as("a night that stopped is still a night, and it is the one night that "
                                + "produced no line at all - which is the silence the report was "
                                + "written to abolish")
                        .containsExactly(NOTHING_YET);
            }
        }

        @Test
        void a_run_that_stopped_part_way_should_leave_the_line_the_night_had_got_to() {
            aRunStoppedWhileRequesting();

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                whatStoppedTheRun();

                softly.assertThat(normalisedRunLines(log))
                        .as("one batch was requested, two court centre days were passed over and "
                                + "nothing was chased; a run has to be able to say how far it got, "
                                + "because the batches it did stamp are waiting on somebody now")
                        .containsExactly(AS_FAR_AS_IT_GOT);
            }
        }

        /**
         * The one arrangement where the settled snapshot can exceed the account beside it.
         *
         * <p>{@code RunReport.Settled} documents that {@code notified <= generated <= generating}
         * is not an invariant, and this is the case it names: a batch whose render was accepted and
         * whose verdict was lost with the run is in none of the requesting leg's three counts, and
         * the completion legs can still settle it before the line is written. Nothing asserted it,
         * so the exception lived only in a javadoc - which is how a reader comes to write an alert
         * on the inequality, or to "fix" the code until it holds and take the divergence with it.
         *
         * <p>A divergence worth seeing rather than a fault: the night it happens on is a night that
         * stopped part way, which is the night an operator most needs the line to be readable.
         *
         * <p><strong>[A]</strong> - green on introduction; it states what the report already does.
         */
        @Test
        @DisplayName("[A] a snapshot may exceed the account beside it, on the night that stopped")
        void a_settled_snapshot_may_exceed_the_requesting_account_on_a_run_that_stopped() {
            final List<RegisterBatch> night = aMixedNight();
            when(service.request(eq(night.get(1)), any(), any()))
                    .thenThrow(new IllegalStateException(OUTAGE));
            // The completion legs run while the run does, so a batch requested at 18:04 can be
            // marked before the line is written - including the one whose verdict the run lost,
            // which the requesting leg never counted.
            theStoreSaysTheyAreNow(nowHeldAt(night.get(0), BatchStatus.GENERATED),
                    nowHeldAt(night.get(1), BatchStatus.GENERATED),
                    nowHeldAt(night.get(2), BatchStatus.GENERATED));

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                whatStoppedTheRun();

                final Map<String, String> fields = fieldsOf(theOneLine(log));
                softly.assertThat(onTheLine(fields, "generated"))
                        .as("the store settled every batch this run had assembled, including the "
                                + "one whose verdict the run lost, so the snapshot counts more "
                                + "than the requesting leg ever got to account for")
                        .isGreaterThan(onTheLine(fields, "generating"));
                softly.assertThat(fields)
                        .as("and the run still says the counts are the store's, because they are")
                        .containsEntry("snapshot", "taken");
            }
        }

        @Test
        void a_run_that_stopped_part_way_should_name_what_stopped_it_in_a_line_of_its_own() {
            aStoreThatWentAway();

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                whatStoppedTheRun();

                softly.assertThat(log.messages())
                        .as("a night of zeroes and a night that fell over read the same on the "
                                + "report's own line, so what stopped it has to be said beside it "
                                + "rather than left to the scheduler's handler")
                        .anyMatch(line -> line.contains(IllegalStateException.class.getName()));
            }
        }

        /**
         * <strong>[A]</strong> And it still fails, which it already did.
         *
         * <p>Green on introduction, because nothing catches the failure today - the run simply
         * leaves without reporting. It is stated here so that the reporting cannot be built by
         * swallowing what it reports: a failure that is only logged about has not been settled
         * (constitution Principle VI), and the scheduler and the operations command both decide
         * what to do next from the throw.
         *
         * <p><strong>Non-vacuous by a reverted mutation.</strong> With {@code run()}'s failure path
         * changed from {@code recorded(...); throw stopped;} to {@code return recorded(...)} - the
         * night reported instead of rethrown, which is the one way the reporting could have been
         * built - this case fails and only this case: 50 tests completed, 1 failed, on "[reported
         * and rethrown, not reported instead of thrown] Expecting actual not to be null", twice
         * over, once for the type asserted and once for the message. Mutation reverted.
         */
        @Test
        void a_run_that_stopped_part_way_should_still_fail() {
            aStoreThatWentAway();

            softly.assertThat(whatStoppedTheRun())
                    .as("reported and rethrown, not reported instead of thrown")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(OUTAGE);
        }

        /**
         * Sets a night up whose one batch's render is asked for and whose store then goes away.
         *
         * <p>The requesting leg is doubled here as it behaves: it tells the run the call has been
         * made, at the moment it makes it, and then the write that would have recorded what came of
         * the call refuses - so the leg leaves through a throw and the outcome the run counts a
         * batch from never arrives. Which of the two marks refused is invisible from here, and that
         * is the point of the arrangement: whether the render was accepted or refused, the run was
         * told the same thing by the same call and must count the same one render.
         *
         * @param statement which write the store would not take, in its own bounded words
         */
        private void aRenderThatLeftBeforeTheStoreRefused(final String statement) {
            final RegisterBatch asked = batch();
            aNightHolding(asked);
            when(service.request(eq(asked), any(), any())).thenAnswer(call -> {
                call.getArgument(2, RenderProgress.class).recordRenderAsked(asked.batchId());
                throw new StoreUnavailableException(
                        "the store could not be reached to " + statement,
                        new IllegalStateException("the connection pool is empty"));
            });
        }

        @Test
        void a_render_the_store_then_failed_to_record_should_still_be_on_the_line_as_requested() {
            aRenderThatLeftBeforeTheStoreRefused("mark a batch requested");

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                whatStoppedTheRun();

                softly.assertThat(normalisedRunLines(log))
                        .as("the render was accepted and the mark that would have recorded it was "
                                + "not written, so no outcome came back for this batch; the "
                                + "document is being rendered either way, and a night that "
                                + "reported nothing sent would be the one night this count is "
                                + "read on")
                        .containsExactly(A_RENDER_AWAY_AND_NOTHING_ACCOUNTED);
            }
        }

        @Test
        void a_render_the_store_then_failed_to_fail_should_still_be_on_the_line_as_requested() {
            aRenderThatLeftBeforeTheStoreRefused("fail a batch");

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                whatStoppedTheRun();

                softly.assertThat(normalisedRunLines(log))
                        .as("the same one render, refused by systemdocgenerator this time and "
                                + "failed on a row the store would not take: what the night sent "
                                + "cannot depend on which of the two marks was the one that could "
                                + "not be written")
                        .containsExactly(A_RENDER_AWAY_AND_NOTHING_ACCOUNTED);
            }
        }

        @Test
        void a_render_the_store_then_refused_should_still_stop_the_run() {
            aRenderThatLeftBeforeTheStoreRefused("mark a batch requested");

            softly.assertThat(whatStoppedTheRun())
                    .as("counting the render changes nothing about the failure: a store outage is "
                            + "reported and rethrown, because the schedule and the operations "
                            + "command decide what to do next from the throw")
                    .isInstanceOf(StoreUnavailableException.class)
                    .hasMessage("the store could not be reached to mark a batch requested");
        }

        @Test
        void a_run_that_stopped_while_requesting_should_still_gauge_what_it_had_assembled() {
            aRunStoppedWhileRequesting();

            whatStoppedTheRun();

            softly.assertThat(deferredKeys())
                    .as("the report is the line and the gauges together, and how much of the "
                            + "estate a run passed over was known before the batch that stopped it "
                            + "was ever asked for")
                    .isEqualTo(2);
        }

        /**
         * <strong>[A]</strong> And a run that learned nothing publishes nothing.
         *
         * <p>Green on introduction and stated so that the case above cannot be satisfied by
         * publishing the gauges unconditionally: a run that stopped before it assembled does not
         * know that no court centre was passed over, and a zero from it would erase the reading
         * that says a court centre has been waiting for nights.
         *
         * <p><strong>Non-vacuous by a reverted mutation.</strong> With the deferred-keys gauge
         * moved outside {@code RegisterGenerationJob.publish}'s "did this run assemble anything"
         * guard, so a run that learned nothing publishes a zero for it, this case fails and only
         * this case: 50 tests completed, 1 failed, on "[no reading is better than a reading the run
         * did not take] expected: 2.0 but was: 0.0". Mutation reverted.
         */
        @Test
        void a_run_that_stopped_before_it_assembled_should_leave_the_gauges_as_they_were() {
            aNightDeferring(key(), key());
            run();

            aStoreThatWentAway();
            whatStoppedTheRun();

            softly.assertThat(deferredKeys())
                    .as("no reading is better than a reading the run did not take")
                    .isEqualTo(2);
        }
    }

    /**
     * When the run happens, and how many of it there are.
     */
    @Nested
    @DisplayName("when the run happens")
    class WhenItRuns {

        @Test
        void job_is_scheduled_in_europe_london() throws NoSuchMethodException {
            final Method run = RegisterGenerationJob.class.getDeclaredMethod("run");
            final Scheduled schedule = run.getAnnotation(Scheduled.class);
            final SchedulerLock lock = run.getAnnotation(SchedulerLock.class);

            softly.assertThat(schedule)
                    .as("the run is a schedule this service keeps, and a job nothing fires is a "
                            + "night of registers nobody is told are missing")
                    .isNotNull();
            softly.assertThat(schedule == null ? null : configured(schedule.cron()))
                    .as("18:00 Monday to Friday, which is the hour the courts' day is over and the "
                            + "one the legacy has always generated at")
                    .isEqualTo(COURT_CRON);
            softly.assertThat(schedule == null ? null : configured(schedule.zone()))
                    .as("the requirement is 18:00 WALL CLOCK in BST and GMT alike; the legacy "
                            + "fires in the scheduling JVM's default zone because its Quartz "
                            + "trigger was built without one, and on a UTC pod that is an hour "
                            + "late for five months of the year")
                    .isEqualTo(COURTS_ZONE);
            softly.assertThat(lock)
                    .as("one replica today is a deployment fact and not a code guarantee, and the "
                            + "cost of being wrong is two documents and two e-mails for every "
                            + "court centre in the country")
                    .isNotNull();
            softly.assertThat(lock == null ? null : lock.name())
                    .as("a lock every job in the estate shares a name with is not a lock")
                    .isNotBlank();
            softly.assertThat(lockedFor(lock))
                    .as("the lock has to outlast the requesting the deadline permits, or a run "
                            + "still inside its hour would be joined by the replica that took the "
                            + "lock it had already lost")
                    .isGreaterThan(RUN_DEADLINE);
        }

        /**
         * And the scheduler the run goes on, named rather than left to Spring to resolve.
         *
         * <p>Three {@code TaskScheduler} beans route nothing by themselves: {@code @Scheduled}
         * processing resolves a single scheduler for the context unless the method names one, so
         * without this attribute the 07:00 report and the fixed-delay gauge refresh could land on
         * the thread an 18:00 run is holding, and SC-008's separation would be a comment. The
         * constant names the bean that already exists; no bean is added, renamed or moved.
         */
        @Test
        void the_run_names_the_generation_scheduler() throws NoSuchMethodException {
            final Scheduled schedule = RegisterGenerationJob.class.getDeclaredMethod("run")
                    .getAnnotation(Scheduled.class);

            softly.assertThat(schedule == null ? null : schedule.scheduler())
                    .as("the run keeps the executor it has always had, and says so: an attribute "
                            + "nobody asserts is an attribute a later edit removes")
                    .isEqualTo(SchedulingConfig.GENERATION_SCHEDULER);
        }

        /**
         * The lock and the deadline are one arrangement, so the lock is not allowed to be a second
         * statement of it.
         *
         * <p>{@code run-deadline} is configurable and a literal here is not, so a deployment that
         * lengthens the run past a hard-coded lock gets a window in which a run still inside its
         * hour has already lost the lock that keeps the second replica out. The annotation
         * therefore reads the setting, {@code application.yaml} declares it as the deadline plus a
         * fixed margin, and {@code ConfigurationValidationTest.SchedulerLockAgainstRunDeadline}
         * refuses startup on any pair that does not hold.
         */
        @Test
        void the_lock_duration_should_come_from_configuration_and_not_be_written_twice()
                throws NoSuchMethodException {

            final SchedulerLock lock = RegisterGenerationJob.class.getDeclaredMethod("run")
                    .getAnnotation(SchedulerLock.class);

            softly.assertThat(lock == null ? null : lock.lockAtMostFor())
                    .as("a literal here is a second copy of a configurable setting, and the day "
                            + "the two disagree is the day two pods generate the same night")
                    .isEqualTo(LOCK_PROPERTY);
        }
    }
}
