package uk.gov.hmcts.cp.yotresultsdistribution.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OperationsRunLauncher.RunAccepted;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterRegenerationService.RegenerationTally;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterRegenerationService.Selection;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.FeatureFlagGate;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.RegisterGenerationJob;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.RunCorrelation;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision.Proceed;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision.Reason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision.Skipped;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunReport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.AdjustableClock;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;

/**
 * The {@code 202} hand-off, and the one rule the command it replaces did not have.
 *
 * <p>The CLI left "do not regenerate during the nightly run" to a runbook. Asking whether the lock
 * is held and then acting races the scheduler, so the background run <strong>takes</strong> the
 * schedule's own lock by its own name and records the refusal as the run's outcome when it cannot
 * (research R12). Everything else here is about the order: the arguments, then the one lever, then
 * the run id, then the submit - and nothing of the run itself on the calling thread.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the launcher behind the 202")
class OperationsRunLauncherTest {

    private static final LocalDate THURSDAY = LocalDate.of(2026, 8, 20);

    private static final UUID BATCH = UUID.fromString("11111111-2222-4333-8444-555555555555");

    private static final Instant NOW = Instant.parse("2026-08-21T07:00:00Z");

    private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(70);

    /** The default: one non-blocking attempt, which is the only kind that holds no thread. */
    private static final Duration NO_WAIT = Duration.ZERO;

    private final RegisterRegenerationService regeneration =
            mock(RegisterRegenerationService.class);
    private final FeatureFlagGate gate = mock(FeatureFlagGate.class);
    private final LockProvider locks = mock(LockProvider.class);
    private final SimpleLock lock = mock(SimpleLock.class);
    private final AdjustableClock clock = AdjustableClock.startingAt(NOW);

    /** What was handed to the generation scheduler, held rather than run. */
    private final List<Runnable> submitted = new ArrayList<>();

    private final Executor executor = submitted::add;

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * The launcher a deployment gets, over whatever wait it was configured with.
     *
     * @param lockWait how long the background run waits for the schedule's lock
     * @return the launcher
     */
    private OperationsRunLauncher launcher(final Duration lockWait) {
        return new OperationsRunLauncher(regeneration, gate, locks, LOCK_AT_MOST_FOR, lockWait,
                executor, clock);
    }

    /** The whole day, with nothing narrowed and no override. */
    private static Selection theWholeDay() {
        return new Selection(THURSDAY, null, null, null, false);
    }

    /** An empty tally, for the cases that are about the run happening rather than about counts. */
    private static RegenerationTally nothingToDo() {
        return tallyReporting(new RunReport(new Proceed(false), Map.of(), 0, Map.of(), 0, 0,
                RunReport.Settled.UNREAD, 0, 0, 0, Duration.ZERO));
    }

    /**
     * A tally whose report is the night the run line will be written from.
     *
     * @param scheduleShaped the night, counted exactly as the schedule counts one
     * @return the tally a regeneration answers with
     */
    private static RegenerationTally tallyReporting(final RunReport scheduleShaped) {
        return new RegenerationTally(THURSDAY, 0, 0, 0, 0, 0, false, Map.of(), Map.of(),
                RunReport.byOperator(scheduleShaped));
    }

    /** What a run that stopped had already written down, as its refusal carries it. */
    private static Map<String, Object> partialTally() {
        return Map.of("date", THURSDAY.toString(), "released", 4, "registers", 9, "batches", 2,
                "requested", 1, "deferred", 3);
    }

    /** Runs whatever the launcher handed the generation scheduler. */
    private void theSchedulerRunsIt() {
        submitted.forEach(Runnable::run);
    }

    @Nested
    @DisplayName("before the caller is answered")
    class BeforeTheAnswer {

        @Test
        void an_override_without_a_batch_should_be_refused_and_read_nothing() {
            Assertions.assertThatThrownBy(() -> launcher(NO_WAIT)
                            .launch(new Selection(THURSDAY, null, null, null, true)))
                    .isInstanceOf(OperationsRefusedException.class)
                    .extracting(refused -> ((OperationsRefusedException) refused).reason())
                    .isEqualTo(OperationsReason.OVERRIDE_REQUIRES_BATCH);

            verifyNoInteractions(gate, locks, regeneration);
            softly.assertThat(submitted).isEmpty();
        }

        @Test
        void an_override_with_a_batch_should_be_admitted_and_said_out_loud() {
            when(gate.decide(true)).thenReturn(new Proceed(true));

            final RunAccepted accepted = launcher(NO_WAIT)
                    .launch(new Selection(THURSDAY, null, BATCH, null, true));

            softly.assertThat(accepted.overridden())
                    .as("the caller is told that the run went ahead over a flag that would have "
                            + "stopped it, because that is the run most worth finding again")
                    .isTrue();
            softly.assertThat(accepted.registerDate()).isEqualTo(THURSDAY);
        }

        @Test
        void a_flag_that_says_off_should_refuse_before_any_work_is_submitted() {
            when(gate.decide(anyBoolean())).thenReturn(new Skipped(Reason.FLAG_OFF));

            Assertions.assertThatThrownBy(() -> launcher(NO_WAIT).launch(theWholeDay()))
                    .isInstanceOf(OperationsRefusedException.class)
                    .extracting(refused -> ((OperationsRefusedException) refused).reason())
                    .isEqualTo(OperationsReason.FLAG_OFF);

            softly.assertThat(submitted)
                    .as("a refusal the caller can see means nothing was started")
                    .isEmpty();
            verifyNoInteractions(locks, regeneration);
        }

        @Test
        void a_flag_that_cannot_be_read_should_refuse_the_same_way() {
            when(gate.decide(anyBoolean())).thenReturn(new Skipped(Reason.FLAG_UNREADABLE));

            Assertions.assertThatThrownBy(() -> launcher(NO_WAIT).launch(theWholeDay()))
                    .isInstanceOf(OperationsRefusedException.class)
                    .extracting(refused -> ((OperationsRefusedException) refused).reason())
                    .as("fail-closed: every failure to read leaves the legacy in charge")
                    .isEqualTo(OperationsReason.FLAG_UNREADABLE);
        }

        @Test
        void the_run_id_should_be_minted_and_recorded_before_the_work_is_submitted() {
            when(gate.decide(anyBoolean())).thenReturn(new Proceed(false));

            final RunAccepted accepted;
            final List<String> lines;
            try (CapturedLog log = CapturedLog.capturing(OperationsRunLauncher.class)) {
                accepted = launcher(NO_WAIT).launch(theWholeDay());
                lines = log.renderings();
            }

            softly.assertThat(accepted.runId())
                    .as("an outcome that arrives for a run whose id was never written down is an "
                            + "outcome nothing can be applied to")
                    .isNotBlank();
            softly.assertThat(lines)
                    .anyMatch(line -> line.contains("run_id=" + accepted.runId())
                            && line.contains("trigger=operator"));
            softly.assertThat(submitted).hasSize(1);
            verifyNoInteractions(regeneration);
        }

        @Test
        void the_work_should_not_run_on_the_calling_thread() {
            when(gate.decide(anyBoolean())).thenReturn(new Proceed(false));

            launcher(NO_WAIT).launch(theWholeDay());

            verify(regeneration, never()).regenerate(any(), anyBoolean());
            softly.assertThat(submitted)
                    .as("a gateway will not hold a connection for a run measured in tens of "
                            + "minutes, which is the whole reason the endpoint is asynchronous")
                    .hasSize(1);
        }
    }

    /**
     * Where a launched run is read: the schedule's own line, said in the schedule's own words.
     */
    @Nested
    @DisplayName("the line a launched run leaves")
    class TheRunLine {

        @Test
        void a_finished_run_should_write_the_schedules_own_run_report_line() {
            when(gate.decide(anyBoolean())).thenReturn(new Proceed(false));
            when(locks.lock(any())).thenReturn(Optional.of(lock));
            when(regeneration.regenerate(any(), anyBoolean())).thenReturn(nothingToDo());

            final RunAccepted accepted = launcher(NO_WAIT).launch(theWholeDay());
            final List<String> lines;
            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                theSchedulerRunsIt();
                lines = log.renderings();
            }

            softly.assertThat(lines)
                    .as("one line a night is read from, whoever asked for it - two spellings would "
                            + "mean two dashboards and one of them always out of date")
                    .anyMatch(line -> line.startsWith("event=register_generation_run")
                            && line.contains("run_id=" + accepted.runId())
                            && line.contains("trigger=operator")
                            && line.contains("gate=proceed")
                            && line.contains("reason=flag-on"));
        }

        @Test
        void the_whole_background_run_should_carry_the_answered_run_id_on_the_mdc() {
            when(gate.decide(anyBoolean())).thenReturn(new Proceed(false));
            when(locks.lock(any())).thenReturn(Optional.of(lock));
            final List<String> seenByTheWork = new ArrayList<>();
            when(regeneration.regenerate(any(), anyBoolean())).thenAnswer(invocation -> {
                seenByTheWork.add(RunCorrelation.current());
                return nothingToDo();
            });

            final RunAccepted accepted = launcher(NO_WAIT).launch(theWholeDay());
            theSchedulerRunsIt();

            softly.assertThat(seenByTheWork)
                    .as("the requesting leg, the store, the assembler and the two clients all "
                            + "write lines during an operator's run, and under the 18:00 job every "
                            + "one of them carries runId - a run reached through HTTP is the same "
                            + "unit of work and is read the same way (Principle VII)")
                    .containsExactly(accepted.runId());
            softly.assertThat(RunCorrelation.current())
                    .as("and the correlation is taken away again: the generation scheduler's "
                            + "thread is the 18:00 run's own, and an id left on it would be "
                            + "inherited by that run - worse than none, because it reads as true")
                    .isNull();
        }

        @Test
        void a_run_over_an_overridden_flag_should_say_overridden_on_that_same_line() {
            when(gate.decide(anyBoolean())).thenReturn(new Proceed(true));
            when(locks.lock(any())).thenReturn(Optional.of(lock));
            when(regeneration.regenerate(any(), anyBoolean())).thenReturn(
                    tallyReporting(new RunReport(new Proceed(true), Map.of(), 0, Map.of(), 0, 0,
                            RunReport.Settled.UNREAD, 0, 0, 0, Duration.ZERO)));

            launcher(NO_WAIT).launch(new Selection(THURSDAY, null, BATCH, null, true));
            final List<String> lines;
            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationJob.class)) {
                theSchedulerRunsIt();
                lines = log.renderings();
            }

            softly.assertThat(lines)
                    .as("the field FeatureFlagGate already counts and logs, kept rather than "
                            + "replaced - the night this service generated while the flag said the "
                            + "legacy was is the one most worth finding again")
                    .anyMatch(line -> line.contains("trigger=operator")
                            && line.contains("reason=overridden"));
        }
    }

    @Nested
    @DisplayName("the lock the background run takes")
    class TheLock {

        @Test
        void it_should_be_the_schedules_own_lock_attempted_without_blocking() {
            when(gate.decide(anyBoolean())).thenReturn(new Proceed(false));
            when(locks.lock(any())).thenReturn(Optional.of(lock));
            when(regeneration.regenerate(any(), anyBoolean())).thenReturn(nothingToDo());

            launcher(NO_WAIT).launch(theWholeDay());
            theSchedulerRunsIt();

            final ArgumentCaptor<LockConfiguration> taken =
                    ArgumentCaptor.forClass(LockConfiguration.class);
            verify(locks).lock(taken.capture());
            softly.assertThat(taken.getValue().getName())
                    .as("reused rather than restated, so the operator run and the schedule cannot "
                            + "hold two different locks over one night")
                    .isEqualTo(RegisterGenerationJob.LOCK_NAME);
            softly.assertThat(taken.getValue().getLockAtMostFor()).isEqualTo(LOCK_AT_MOST_FOR);
            softly.assertThat(taken.getValue().getLockAtLeastFor()).isEqualTo(Duration.ZERO);
        }

        @Test
        void a_lock_it_cannot_take_should_record_schedule_running_and_do_nothing() {
            when(gate.decide(anyBoolean())).thenReturn(new Proceed(false));
            when(locks.lock(any())).thenReturn(Optional.empty());

            final RunAccepted accepted = launcher(NO_WAIT).launch(theWholeDay());
            final List<String> lines;
            try (CapturedLog log = CapturedLog.capturing(OperationsRunLauncher.class)) {
                theSchedulerRunsIt();
                lines = log.renderings();
            }

            verifyNoInteractions(regeneration);
            softly.assertThat(lines)
                    .as("two launches for one date on two replicas end with one of them saying "
                            + "this, rather than with a failure")
                    .anyMatch(line -> line.contains("run_id=" + accepted.runId())
                            && line.contains("outcome=SCHEDULE_RUNNING"));
        }

        @Test
        void a_bounded_wait_that_runs_out_should_still_end_in_schedule_running() {
            when(gate.decide(anyBoolean())).thenReturn(new Proceed(false));
            when(locks.lock(any())).thenReturn(Optional.empty());

            launcher(Duration.ofMillis(40)).launch(theWholeDay());
            final List<String> lines;
            try (CapturedLog log = CapturedLog.capturing(OperationsRunLauncher.class)) {
                theSchedulerRunsIt();
                lines = log.renderings();
            }

            softly.assertThat(lines)
                    .anyMatch(line -> line.contains("outcome=SCHEDULE_RUNNING"));
            verifyNoInteractions(regeneration);
        }

        @Test
        void a_lock_it_took_should_be_given_back_when_the_run_finishes() {
            when(gate.decide(anyBoolean())).thenReturn(new Proceed(false));
            when(locks.lock(any())).thenReturn(Optional.of(lock));
            when(regeneration.regenerate(any(), anyBoolean())).thenReturn(nothingToDo());

            launcher(NO_WAIT).launch(theWholeDay());
            theSchedulerRunsIt();

            verify(regeneration).regenerate(theWholeDay(), false);
            verify(lock).unlock();
        }

        @Test
        void a_lock_it_took_should_be_given_back_when_the_run_throws() {
            when(gate.decide(anyBoolean())).thenReturn(new Proceed(false));
            when(locks.lock(any())).thenReturn(Optional.of(lock));
            when(regeneration.regenerate(any(), anyBoolean())).thenThrow(
                    new OperationsRefusedException(OperationsReason.GENERATION_FAILED));

            launcher(NO_WAIT).launch(theWholeDay());
            final List<String> lines;
            try (CapturedLog log = CapturedLog.capturing(OperationsRunLauncher.class)) {
                theSchedulerRunsIt();
                lines = log.renderings();
            }

            verify(lock)
                    .unlock();
            softly.assertThat(lines)
                    .as("a run that failed must not also leave the night's lock held, and its "
                            + "ending is written down rather than dropped into a Future")
                    .anyMatch(line -> line.contains("outcome=generation-failed"));
        }

        @Test
        void a_run_that_stopped_part_way_should_say_what_it_had_already_written_down() {
            when(gate.decide(anyBoolean())).thenReturn(new Proceed(false));
            when(locks.lock(any())).thenReturn(Optional.of(lock));
            when(regeneration.regenerate(any(), anyBoolean())).thenThrow(
                    new OperationsRefusedException(OperationsReason.GENERATION_FAILED,
                            partialTally()));

            launcher(NO_WAIT).launch(theWholeDay());
            final List<String> lines;
            try (CapturedLog log = CapturedLog.capturing(OperationsRunLauncher.class)) {
                theSchedulerRunsIt();
                lines = log.renderings();
            }

            softly.assertThat(lines)
                    .as("the 202 was answered before any work began, so no status can carry the "
                            + "partial tally and this line is the only place the day it left "
                            + "behind can be read")
                    .anyMatch(line -> line.contains("outcome=generation-failed")
                            && line.contains("released=4") && line.contains("registers=9")
                            && line.contains("batches=2") && line.contains("requested=1")
                            && line.contains("deferred=3"));
        }

        @Test
        void a_partial_tally_should_not_write_the_date_onto_the_line_a_second_time() {
            when(gate.decide(anyBoolean())).thenReturn(new Proceed(false));
            when(locks.lock(any())).thenReturn(Optional.of(lock));
            when(regeneration.regenerate(any(), anyBoolean())).thenThrow(
                    new OperationsRefusedException(OperationsReason.GENERATION_FAILED,
                            partialTally()));

            launcher(NO_WAIT).launch(theWholeDay());
            final List<String> lines;
            try (CapturedLog log = CapturedLog.capturing(OperationsRunLauncher.class)) {
                theSchedulerRunsIt();
                lines = log.renderings();
            }

            softly.assertThat(lines.stream()
                            .filter(line -> line.contains("outcome=generation-failed"))
                            .map(line -> line.split("date=", -1).length - 1))
                    .as("a key written twice is a key a log index reads once, and the line "
                            + "already carries the day the run was asked for")
                    .containsExactly(1);
        }

        @Test
        void a_defect_in_the_run_should_be_recorded_rather_than_lost_on_the_executors_thread() {
            when(gate.decide(anyBoolean())).thenReturn(new Proceed(false));
            when(locks.lock(any())).thenReturn(Optional.of(lock));
            when(regeneration.regenerate(any(), anyBoolean()))
                    .thenThrow(new IllegalStateException("ZQX7DEFECT"));

            launcher(NO_WAIT).launch(theWholeDay());
            final List<String> lines;
            try (CapturedLog log = CapturedLog.capturing(OperationsRunLauncher.class)) {
                theSchedulerRunsIt();
                lines = log.renderings();
            }

            verify(lock).unlock();
            softly.assertThat(lines)
                    .anyMatch(line -> line.contains("cause=java.lang.IllegalStateException"));
            softly.assertThat(lines)
                    .as("named by class; its message belongs to whatever raised it")
                    .noneMatch(line -> line.contains("ZQX7DEFECT"));
        }
    }
}
