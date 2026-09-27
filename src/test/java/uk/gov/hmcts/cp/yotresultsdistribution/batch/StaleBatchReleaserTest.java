package uk.gov.hmcts.cp.yotresultsdistribution.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterStore;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ReleasedBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.application.StaleReleaseOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.application.StaleReleaseProgress;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.StaleBatchReleaser.ReleaseTally;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.support.AdjustableClock;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;

/**
 * The pass the whole increment is for, and the account it keeps of a night.
 *
 * <p>The decision about any one batch belongs to the store, in one fenced statement per batch; what
 * this class decides is which two instants to ask about and what to say about the answer. So the
 * store is a mock here and every case is about the question, the account and the lines - a plain
 * object over a port, two durations and a clock, with no Spring context and no Docker.
 *
 * <p><strong>P2's re-pointed pinning test lives here.</strong> The defect-fix register's `P2` row
 * promises that a failed render is never silently dropped, and the half of it that named the
 * retired reconciler's timeout test is re-pointed at
 * {@link #a_batch_still_generating_past_the_minimum_age_should_be_failed_and_released}: a batch
 * nothing was ever learned about reaches an explicit recorded failure rather than sitting in flight
 * for ever. The mechanism changed in 004; the promise did not.
 *
 * <p><strong>Three things this class may never do</strong>, and each is a case rather than a
 * comment. It may not let one batch end the night - a batch the store reports contended is counted,
 * said once at WARN and walked past, because the run has the rest of the country's documents to
 * make (FR-003a). It may not invent a correlation where the run already opened one, because a
 * night has to be readable out of the estate's index as one thing. And it may not name anything but
 * identities and counts: every defendant on these registers is a child (constitution Principle
 * VII).
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("StaleBatchReleaser")
class StaleBatchReleaserTest {

    /** 18:00 Europe/London on the Monday, which is when a run asks. */
    private static final Instant NOW = Instant.parse("2026-09-21T17:00:00Z");

    /** The documented default: how long a batch the schedule made may be in flight. */
    private static final Duration STALE_AFTER = Duration.ofMinutes(30);

    /** The nightly run's own lock duration, which is the grace an operator's batch gets. */
    private static final Duration RUN_LOCK = Duration.ofMinutes(70);

    /**
     * A run lock shorter than the minimum age, so neither ordering of the two settings is assumed.
     *
     * <p>Not a configuration this service ships - the lock outlasts the run deadline by design -
     * but the rule is a `max` and a rule stated as a `max` has to be asserted both ways round, or
     * an implementation that simply always took the lock would pass.
     */
    private static final Duration SHORT_LOCK = Duration.ofMinutes(10);

    private static final UUID FIRST_BATCH = UUID.fromString("3b3a9c02-7d61-4f4c-8f10-1a5bb9d0f7c2");
    private static final UUID SECOND_BATCH =
            UUID.fromString("c1f4e6a8-05b7-4a3d-9f2e-6d8c3b17a940");
    private static final UUID CONTENDED_BATCH =
            UUID.fromString("7e59d4b1-2c86-40af-b3d7-5f0a8e2c6913");
    private static final UUID COURT_CENTRE = UUID.fromString("f0a7c3d9-41b2-4e58-9c6a-2b7d5e138046");

    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 21);

    /** What the tally reads where the pass refused to answer at all, so the red is an assertion. */
    private static final ReleaseTally ABSENT = new ReleaseTally(-1, -1, -1);

    private static final String SEAM =
            "T012 implements the stale-batch pass; this is its red run";

    /** Returned when a meter is absent, so a missing instrument fails as an assertion. */
    private static final double NO_METER = -1;

    private final RegisterStore store = mock(RegisterStore.class);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final GenerationMetrics metrics = new GenerationMetrics(registry);

    private final AdjustableClock clock = AdjustableClock.startingAt(NOW);

    private final StaleBatchReleaser releaser =
            new StaleBatchReleaser(store, metrics, STALE_AFTER, RUN_LOCK, clock);

    /** What the store was asked about, recorded rather than verified, so every claim is soft. */
    private final AtomicReference<Instant> scheduledCutoff = new AtomicReference<>();

    private final AtomicReference<Instant> manualCutoff = new AtomicReference<>();

    private final AtomicInteger asked = new AtomicInteger();

    /** The correlation the pass was running under when it asked the store. */
    private final AtomicReference<String> correlation = new AtomicReference<>();

    @InjectSoftAssertions
    private SoftAssertions softly;

    @Test
    void a_batch_still_generating_past_the_minimum_age_should_be_failed_and_released() {
        answering(new StaleReleaseOutcome(List.of(released(FIRST_BATCH, 2)), List.of()));

        final ReleaseTally tally = pass();

        softly.assertThat(asked.get())
                .as("the store is asked once per run: the fence is the statement's own predicate, "
                        + "so the pass has nothing to read first and nothing to decide")
                .isEqualTo(1);
        softly.assertThat(tally)
                .as("a batch nothing was ever learned about reaches an explicit recorded failure "
                        + "and its registers come back for tonight - which is P2's promise, held "
                        + "by a mechanism that no longer asks systemdocgenerator anything")
                .isEqualTo(new ReleaseTally(1, 2, 0));
        softly.assertThat(counter(GenerationMetrics.RELEASED_BATCHES))
                .as("and the night's own account of what it had to undo moves")
                .isEqualTo(1);
        softly.assertThat(correlation.get())
                .as("and the pass driven on its own opens a correlation rather than running "
                        + "under none: a release said at INFO with no run behind it is a line "
                        + "the estate's index cannot tie to anything")
                .isNotNull();
        softly.assertThat(RunCorrelation.current())
                .as("which it removes again, because the scheduler's threads are pooled")
                .isNull();
    }

    @Test
    void the_two_numbers_are_batches_and_registers() {
        answering(new StaleReleaseOutcome(
                List.of(released(FIRST_BATCH, 2), released(SECOND_BATCH, 3)), List.of()));

        final ReleaseTally tally = pass();

        softly.assertThat(tally)
                .as("a batch is one document and one e-mail; a register is one hearing's youth "
                        + "defendants. Neither number answers the other's question, so the pass "
                        + "keeps both accounts of a night rather than choosing")
                .isEqualTo(new ReleaseTally(2, 5, 0));
        softly.assertThat(counter(GenerationMetrics.RELEASED_BATCHES)).isEqualTo(2);
        softly.assertThat(counter(GenerationMetrics.RELEASED_REGISTERS))
                .as("and each is its own series, because a dashboard is asked both questions")
                .isEqualTo(5);
    }

    @Test
    void one_line_per_released_batch_names_it_by_id_and_nothing_else() {
        answering(new StaleReleaseOutcome(
                List.of(released(FIRST_BATCH, 2), released(SECOND_BATCH, 3)), List.of()));

        try (CapturedLog log = CapturedLog.capturing(StaleBatchReleaser.class)) {
            pass();

            final List<String> lines = renderedLines(log);
            softly.assertThat(lines.stream().filter(line -> line.contains(FIRST_BATCH.toString())))
                    .as("one line per batch given up on, so a night is read batch by batch and "
                            + "not as a number somebody has to go looking behind")
                    .hasSize(1);
            softly.assertThat(lines.stream().filter(line -> line.contains(SECOND_BATCH.toString())))
                    .hasSize(1);
            softly.assertThat(lines)
                    .as("the court centre day is named because that is what was given back, and "
                            + "the identities and the counts are the whole of it: nothing here is "
                            + "a defendant, a recipient or a word another system wrote")
                    .allSatisfy(line -> assertThat(line)
                            .doesNotContain("hearing", "defendant", "@"));
            softly.assertThat(lines.stream().filter(line -> line.contains(MONDAY.toString())))
                    .as("the register date is on the lines about a batch, and the summary line "
                            + "after them is about the run rather than about a day")
                    .hasSize(2);
        }
    }

    @Test
    void a_pass_that_released_nothing_says_so() {
        answering(new StaleReleaseOutcome(List.of(), List.of()));

        try (CapturedLog log = CapturedLog.capturing(StaleBatchReleaser.class)) {
            final ReleaseTally tally = pass();

            softly.assertThat(tally)
                    .as("a night that released nothing and a night that did not look are "
                            + "different nights, and only one of them is a run that worked")
                    .isEqualTo(new ReleaseTally(0, 0, 0));
            softly.assertThat(renderedLines(log))
                    .as("so the pass still says what it did, in one line and no more")
                    .hasSize(1);
            softly.assertThat(String.join(" | ", renderedLines(log)))
                    .as("and the line carries all three numbers by name, because the run report "
                            + "is to read them off it and a quiet night is the one where a "
                            + "missing key would go unnoticed")
                    .contains("released_batches=0", "released_registers=0", "contended=0");
            softly.assertThat(counter(GenerationMetrics.RELEASED_BATCHES))
                    .as("and the series exists from the first quiet night, rather than appearing "
                            + "the first time something goes wrong - a meter nobody can graph "
                            + "until the incident is not an alerting surface")
                    .isZero();
            softly.assertThat(counter(GenerationMetrics.RELEASED_REGISTERS)).isZero();
        }
    }

    @Test
    void a_contended_batch_is_counted_and_the_pass_goes_on() {
        answering(new StaleReleaseOutcome(
                List.of(released(FIRST_BATCH, 2)), List.of(CONTENDED_BATCH)));

        try (CapturedLog log = CapturedLog.capturing(StaleBatchReleaser.class)) {
            final ReleaseTally tally = pass();

            softly.assertThat(tally)
                    .as("the store reports a batch every attempt was refused over rather than "
                            + "throwing, so the account has a third number and the pass answers "
                            + "normally with it: no single batch's outcome may end the run, and "
                            + "the other court centres' registers are given back regardless")
                    .isEqualTo(new ReleaseTally(1, 2, 1));
            softly.assertThat(counter(GenerationMetrics.RELEASE_CONTENDED))
                    .as("a path that leaves something undone moves a counter - \"it is in the log "
                            + "index\" is not an alerting surface")
                    .isEqualTo(1);
            softly.assertThat(warnings(log))
                    .as("and it is said once, at WARN: the batch is stale still, so the next run "
                            + "reaches it and the 07:00 report names its court centre day every "
                            + "morning meanwhile")
                    .hasSize(1);
            softly.assertThat(String.join(" | ", warnings(log)))
                    .as("by identity, which is the whole of what a contended batch is on a line")
                    .contains(CONTENDED_BATCH.toString());
            softly.assertThat(renderedLines(log))
                    .as("and the summary line carries the three numbers by name, the contended "
                            + "one beside the two released, so a night is read off one line")
                    .anySatisfy(line -> assertThat(line)
                            .contains("released_batches=1", "released_registers=2",
                                    "contended=1"));
        }
    }

    @Test
    void the_pass_adopts_the_runs_correlation() {
        answering(new StaleReleaseOutcome(List.of(released(FIRST_BATCH, 2)), List.of()));
        final AtomicReference<String> ambient = new AtomicReference<>();

        RunCorrelation.under(() -> {
            ambient.set(RunCorrelation.current());
            pass();
        });

        softly.assertThat(correlation.get())
                .as("the pass runs inside the night's own run, so it carries the run's id rather "
                        + "than minting a second one - a night that wrote itself down under two "
                        + "correlations could not be read out of the index as one thing")
                .isEqualTo(ambient.get());
        softly.assertThat(RunCorrelation.current())
                .as("and the id is the run's to remove, not this pass's: the scheduler's threads "
                        + "are pooled, and an id left behind reads as a true correlation")
                .isNull();
    }

    @Test
    void a_store_that_cannot_be_reached_leaves_the_pass() {
        final StoreUnavailableException outage = new StoreUnavailableException(
                "the store could not be reached to fail and release the stale batches",
                new IllegalStateException("the connection was refused"));
        when(store.failAndReleaseStale(any(), any(), any())).thenThrow(outage);

        final Throwable escaped = catchThrowable(releaser::releaseStale);

        softly.assertThat(escaped)
                .as("a store that went away is the run's own failure and not one batch's: the run "
                        + "reports what it had done and rethrows, exactly as it does for every "
                        + "other read it cannot make, and the port's own signal is what crosses - "
                        + "no org.springframework type reaches this package (Principle V)")
                .isSameAs(outage);
        softly.assertThat(counter(GenerationMetrics.RELEASED_BATCHES))
                .as("and nothing is counted for a pass that learned nothing")
                .isEqualTo(NO_METER);
        softly.assertThat(RunCorrelation.current())
                .as("a correlation this pass opened is removed however the pass ended")
                .isNull();
    }

    @Test
    void a_store_that_goes_away_partway_should_leave_the_account_of_what_it_had_released() {
        final StoreUnavailableException outage = new StoreUnavailableException(
                "the store could not be reached to fail and release the stale batches",
                new IllegalStateException("the connection was refused"));
        when(store.failAndReleaseStale(any(), any(), any())).thenAnswer(call -> {
            told(call.getArgument(2, StaleReleaseProgress.class), new StaleReleaseOutcome(
                    List.of(released(FIRST_BATCH, 2)), List.of(CONTENDED_BATCH)));
            throw outage;
        });

        try (CapturedLog log = CapturedLog.capturing(StaleBatchReleaser.class)) {
            final Throwable escaped = catchThrowable(releaser::releaseStale);

            softly.assertThat(escaped)
                    .as("the store going away is still the run's own failure and still leaves")
                    .isSameAs(outage);
            softly.assertThat(counter(GenerationMetrics.RELEASED_BATCHES))
                    .as("but each batch was committed by itself before the walk reached the one "
                            + "that could not be: those registers really are back for tonight, so "
                            + "the night's account of them is published rather than thrown away "
                            + "with the walk that was making it")
                    .isEqualTo(1);
            softly.assertThat(counter(GenerationMetrics.RELEASED_REGISTERS)).isEqualTo(2);
            softly.assertThat(counter(GenerationMetrics.RELEASE_CONTENDED))
                    .as("and a batch nothing could be given back from is counted on the way past, "
                            + "which is where it was learned")
                    .isEqualTo(1);
            softly.assertThat(renderedLines(log))
                    .as("with one line per batch, said where it was settled rather than after an "
                            + "answer that never came")
                    .anySatisfy(line -> assertThat(line).contains(FIRST_BATCH.toString()));
            softly.assertThat(String.join(" | ", renderedLines(log)))
                    .as("and a closing line that says the pass did not get to the end, carrying "
                            + "the three numbers it did reach - a run line reading zero for a "
                            + "night that released some is the silence this service exists to end")
                    .contains("released_batches=1", "released_registers=2", "contended=1");
        }
        softly.assertThat(RunCorrelation.current())
                .as("a correlation this pass opened is removed however the pass ended")
                .isNull();
    }

    /**
     * The account reaches the run that asked for the pass, and not only the caller it returns to.
     *
     * <p>A pass the store interrupts leaves through the throw, so a run that read what the pass did
     * from the return value alone would learn nothing at all about a night whose pass had already
     * given batches back - and the run's own line would then say {@code released_batches=0} beside
     * the pass's WARN line saying one. Two lines under one {@code run_id} disagreeing about the
     * same night is the report failing at the one thing it exists for (FR-009), so what the pass
     * committed is handed to the caller's account as the pass ends, however it ends.
     */
    @Test
    void an_interrupted_pass_should_hand_its_committed_account_to_the_run() {
        final StoreUnavailableException outage = new StoreUnavailableException(
                "the store could not be reached to fail and release the stale batches",
                new IllegalStateException("the connection was refused"));
        when(store.failAndReleaseStale(any(), any(), any())).thenAnswer(call -> {
            told(call.getArgument(2, StaleReleaseProgress.class), new StaleReleaseOutcome(
                    List.of(released(FIRST_BATCH, 2)), List.of(CONTENDED_BATCH)));
            throw outage;
        });
        final AtomicReference<ReleaseTally> handedOver = new AtomicReference<>(ABSENT);

        final Throwable escaped =
                catchThrowable(() -> releaser.releaseStale(handedOver::set));

        softly.assertThat(escaped)
                .as("the outage is still the run's own failure and still leaves, because a "
                        + "failure that was only reported has not been settled")
                .isSameAs(outage);
        softly.assertThat(handedOver.get())
                .as("and the run is told what the pass had already committed, so the line it "
                        + "writes about the night agrees with the line the pass wrote about it")
                .isEqualTo(new ReleaseTally(1, 2, 1));
    }

    /**
     * A pass that got to the end hands the same account over as it answers with.
     *
     * <p>The ordinary night, asserted so that the handover cannot quietly become a failure-only
     * path: the run takes its two released numbers from the account on every night, and a
     * hand-over that only happened when something went wrong would leave every good night's line
     * reading nought.
     */
    @Test
    void a_pass_that_reached_the_end_should_hand_over_what_it_answered() {
        answering(new StaleReleaseOutcome(
                List.of(released(FIRST_BATCH, 2)), List.of(CONTENDED_BATCH)));
        final AtomicReference<ReleaseTally> handedOver = new AtomicReference<>(ABSENT);

        final ReleaseTally answered = releaser.releaseStale(handedOver::set);

        softly.assertThat(handedOver.get())
                .as("one account, told once, whichever way the caller reads it")
                .isEqualTo(answered);
    }

    @Test
    void the_scheduled_cutoff_is_the_clock_minus_the_minimum_age() {
        answering(new StaleReleaseOutcome(List.of(), List.of()));

        pass();

        softly.assertThat(scheduledCutoff.get())
                .as("a batch the schedule made is stale once it has been in flight for the "
                        + "minimum age, measured back from the clock this run reads - not from "
                        + "the run before it, and not from a duration the store defaults")
                .isEqualTo(NOW.minus(STALE_AFTER));
    }

    @Test
    void the_manual_cutoff_is_the_longer_of_the_minimum_age_and_the_run_lock() {
        answering(new StaleReleaseOutcome(List.of(), List.of()));

        pass();

        softly.assertThat(manualCutoff.get())
                .as("a batch an operator asked for holds no run lock and has the whole requesting "
                        + "deadline to work in, so it is given the longer grace: failing it would "
                        + "orphan a render the manual run is still making and refuse its own "
                        + "record of having made it (FR-017)")
                .isEqualTo(NOW.minus(RUN_LOCK));
    }

    @Test
    void the_manual_cutoff_is_the_minimum_age_where_it_outlasts_the_run_lock() {
        final StaleBatchReleaser shortLocked =
                new StaleBatchReleaser(store, metrics, STALE_AFTER, SHORT_LOCK, clock);
        answering(new StaleReleaseOutcome(List.of(), List.of()));

        softly.assertThatCode(shortLocked::releaseStale).as(SEAM).doesNotThrowAnyException();

        softly.assertThat(manualCutoff.get())
                .as("the rule is the longer of the two and not the lock: an operator's batch is "
                        + "never given less grace than the schedule's, whichever way a deployment "
                        + "sets the two settings")
                .isEqualTo(NOW.minus(STALE_AFTER));
    }

    @Test
    void a_batch_at_exactly_the_minimum_age_is_stale() {
        answering(new StaleReleaseOutcome(List.of(), List.of()));

        pass();

        softly.assertThat(Duration.between(scheduledCutoff.get(), NOW))
                .as("the boundary is inclusive and lives in the store's predicate, which is "
                        + "`<=`, so the pass's half of it is a cutoff that is exactly the minimum "
                        + "age back - a pass that shaved a millisecond off would leave a batch at "
                        + "exactly thirty minutes for another night, and the rule would need two "
                        + "clauses to state instead of one")
                .isEqualTo(STALE_AFTER);
    }

    /**
     * The pass, with a refusal recorded rather than thrown.
     *
     * <p>Against the seam the call refuses, and the suite's claim is about what the pass answers:
     * recording the refusal as the case's first soft failure is what makes the red an assertion
     * rather than a stack trace out of the arrangement.
     *
     * @return what the pass answered, or {@link #ABSENT} where it answered nothing at all
     */
    private ReleaseTally pass() {
        final AtomicReference<ReleaseTally> tally = new AtomicReference<>(ABSENT);
        softly.assertThatCode(() -> tally.set(releaser.releaseStale()))
                .as(SEAM)
                .doesNotThrowAnyException();
        return tally.get();
    }

    /**
     * Stands the store up to answer one pass, recording what it was asked and under what.
     *
     * @param outcome what the fenced statements between them released and could not release
     */
    private void answering(final StaleReleaseOutcome outcome) {
        when(store.failAndReleaseStale(any(), any(), any())).thenAnswer(call -> {
            scheduledCutoff.set(call.getArgument(0));
            manualCutoff.set(call.getArgument(1));
            correlation.set(RunCorrelation.current());
            asked.incrementAndGet();
            told(call.getArgument(2, StaleReleaseProgress.class), outcome);
            return outcome;
        });
    }

    /**
     * Tells the observer about each batch the way the store does, as each one is settled.
     *
     * @param progress what the pass handed the store
     * @param outcome  the batches the fenced statements between them settled
     */
    private static void told(final StaleReleaseProgress progress,
            final StaleReleaseOutcome outcome) {
        outcome.released().forEach(progress::recordReleased);
        outcome.contended().forEach(progress::recordContended);
    }

    /**
     * One batch the store gave back, as the port answers with it.
     *
     * @param batchId   the batch that was failed
     * @param registers how many of its registers are still the day's to render
     * @return the record the pass reads
     */
    private static ReleasedBatch released(final UUID batchId, final int registers) {
        return new ReleasedBatch(batchId, COURT_CENTRE, MONDAY, registers);
    }

    /**
     * Every line the pass wrote, as an index would hold it.
     *
     * @param log the capture over this class's logger
     * @return the rendered messages
     */
    private static List<String> renderedLines(final CapturedLog log) {
        return log.events().stream().map(CapturedLog::rendering).toList();
    }

    /**
     * The lines the pass wrote at WARN, which is where a batch it could not release is said.
     *
     * @param log the capture over this class's logger
     * @return the rendered warnings
     */
    private static List<String> warnings(final CapturedLog log) {
        return log.events().stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(CapturedLog::rendering)
                .toList();
    }

    /**
     * An unlabelled counter's reading, or {@link #NO_METER} where the series does not exist.
     *
     * @param name the instrument's documented name
     * @return what it has counted
     */
    private double counter(final String name) {
        final Counter counter = registry.find(name).counter();
        return counter == null ? NO_METER : counter.count();
    }
}
