package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.time.Duration;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision.Proceed;

/**
 * What a night's value says, and the one thing it deliberately does not claim.
 *
 * <p>The report is the only place a night is described, and the three numbers its first act
 * contributes are the ones most easily misread: a reader of a line of totals assumes every number
 * on it belongs to one of the totals. These do not. The registers the pass gave back are re-batched
 * by the same run, so they are already inside {@link RunReport#rows()}, and a report that added
 * them would count the same hearings twice (FR-009).
 *
 * <p><strong>[A]</strong> - these are characterisations of the record as T017's seam leaves it.
 * They are written here rather than left to the job's own suite because the arithmetic claim is the
 * record's and not the log line's: the line can be re-ordered, but a total that silently grew would
 * be wrong wherever it was read.
 *
 * <p><strong>And since increment 005, who asked for the night.</strong> A regeneration a named
 * person asked for over the operations API is the same run under the same lock writing the same
 * line, so the one thing that tells them apart has to be on the record rather than in the spelling
 * of whichever class happened to write it (T036).
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("what one nightly run did")
class RunReportTest {

    /** A night's registers, partitioned so that the row total has something to be wrong about. */
    private static final int GENERATING_ROWS = 3;

    private static final int DEFERRED_ROWS = 5;

    /** What the pass gave back that night, which is inside the rows above and not beside them. */
    private static final int RELEASED_BATCHES = 4;

    private static final int RELEASED_REGISTERS = 7;

    private static final int CONTENDED = 6;

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * A night that assembled one batch, passed over one day and released what the pass answered.
     *
     * @param batches   how many batches the pass failed and released
     * @param registers how many registers came back with them
     * @param contended how many batches it could not give back
     * @return that night's report
     */
    private static RunReport aNightThatReleased(final int batches, final int registers,
            final int contended) {

        return new RunReport(new Proceed(false),
                Map.of(BatchStatus.GENERATING, 1), 1,
                Map.of(BatchStatus.GENERATING, GENERATING_ROWS), 1, DEFERRED_ROWS,
                RunReport.Settled.NOTHING_ASSEMBLED, batches, registers, contended,
                Duration.ofMinutes(3));
    }

    /**
     * The three numbers the run's first act contributes.
     */
    @Nested
    @DisplayName("what the run's first act gave back")
    class TheReleasedNumbers {

        @Test
        void a_report_should_carry_the_two_released_numbers_and_the_contended_one() {
            final RunReport report =
                    aNightThatReleased(RELEASED_BATCHES, RELEASED_REGISTERS, CONTENDED);

            softly.assertThat(report.releasedBatches())
                    .as("a batch is one document and one e-mail, so the count of them is how much "
                            + "of the estate a lost outcome cost")
                    .isEqualTo(RELEASED_BATCHES);
            softly.assertThat(report.releasedRegisters())
                    .as("and a register is one hearing's youth defendants, which no count of "
                            + "batches can answer for")
                    .isEqualTo(RELEASED_REGISTERS);
            softly.assertThat(report.contended())
                    .as("and what the pass left exactly as it found it, which is a night's undone "
                            + "work and not a silence")
                    .isEqualTo(CONTENDED);
        }

        @Test
        void a_night_that_released_nothing_should_carry_noughts_rather_than_nothing() {
            final RunReport report = aNightThatReleased(0, 0, 0);

            softly.assertThat(report.releasedBatches()).isZero();
            softly.assertThat(report.releasedRegisters()).isZero();
            softly.assertThat(report.contended())
                    .as("a night that released nothing and a night that did not report are "
                            + "different nights, and the report says which this was")
                    .isZero();
        }

        @Test
        void the_released_registers_should_not_be_added_to_the_rows_total() {
            final RunReport released =
                    aNightThatReleased(RELEASED_BATCHES, RELEASED_REGISTERS, CONTENDED);
            final RunReport releasedNothing = aNightThatReleased(0, 0, 0);

            softly.assertThat(released.rows())
                    .as("every register the run accounted for, batched or left waiting - and the "
                            + "released ones are inside that already, because this same run "
                            + "re-batches them (FR-009)")
                    .isEqualTo(GENERATING_ROWS + DEFERRED_ROWS);
            softly.assertThat(released.rows())
                    .as("so two nights that differ only in what the pass gave back have the same "
                            + "total: the released numbers are a diagnostic and not a third sum")
                    .isEqualTo(releasedNothing.rows());
        }
    }

    /**
     * Which of the two things that can start a run started this one.
     */
    @Nested
    @DisplayName("who asked for the night")
    class TheTrigger {

        @Test
        void a_run_nobody_asked_for_should_be_the_schedules() {
            final RunReport report =
                    aNightThatReleased(RELEASED_BATCHES, RELEASED_REGISTERS, CONTENDED);

            softly.assertThat(report.trigger())
                    .as("the eleven-component constructor is the schedule's, so the 18:00 job says "
                            + "nothing it did not have to say before")
                    .isEqualTo(RunReport.Trigger.SCHEDULE);
            softly.assertThat(report.trigger().wire())
                    .as("and the line carries one bounded word, never a caller")
                    .isEqualTo("schedule");
        }

        @Test
        void a_run_a_person_asked_for_should_say_so() {
            final RunReport asked = RunReport.byOperator(
                    aNightThatReleased(RELEASED_BATCHES, RELEASED_REGISTERS, CONTENDED));

            softly.assertThat(asked.trigger())
                    .as("the second factory, and the only way a report becomes an operator's")
                    .isEqualTo(RunReport.Trigger.OPERATOR);
            softly.assertThat(asked.trigger().wire())
                    .as("which is what an alert looking for the nights a person drove filters on")
                    .isEqualTo("operator");
        }

        @Test
        void an_operators_run_should_change_nothing_else_about_the_night() {
            final RunReport scheduled =
                    aNightThatReleased(RELEASED_BATCHES, RELEASED_REGISTERS, CONTENDED);
            final RunReport asked = RunReport.byOperator(scheduled);

            softly.assertThat(asked)
                    .as("the same counts under a different word: a factory that also re-counted "
                            + "the night would make the two runs unreadable side by side")
                    .isEqualTo(new RunReport(RunReport.Trigger.OPERATOR, scheduled.gateDecision(),
                            scheduled.outcomes(), scheduled.requested(), scheduled.rowOutcomes(),
                            scheduled.deferredKeys(), scheduled.deferredRows(), scheduled.settled(),
                            scheduled.releasedBatches(), scheduled.releasedRegisters(),
                            scheduled.contended(), scheduled.duration()));
            softly.assertThat(asked.gateDecision())
                    .as("and the gate's own verdict is kept, not replaced - reason=overridden is "
                            + "the field FeatureFlagGate already counts and logs")
                    .isEqualTo(new Proceed(false));
        }

        @Test
        void a_report_made_without_a_trigger_should_be_the_schedules_rather_than_nothing() {
            final RunReport unstated = new RunReport(null, new Proceed(true),
                    Map.of(), 0, Map.of(), 0, 0, RunReport.Settled.UNREAD, 0, 0, 0,
                    Duration.ZERO);

            softly.assertThat(unstated.trigger())
                    .as("a run is the schedule's until somebody says it was theirs, which is the "
                            + "safe direction for a field an alert reads")
                    .isEqualTo(RunReport.Trigger.SCHEDULE);
        }

        @Test
        void a_reading_nobody_took_should_not_be_spelled_as_one_the_store_refused() {
            softly.assertThat(RunReport.Settled.NOT_TAKEN.reading())
                    .as("a regeneration takes no settled reading at all; the 18:00 run's `unread` "
                            + "is a read the store REFUSED, which also WARNs and moves "
                            + "yotresultsdistribution_generation_unrecorded_total. One word for both would "
                            + "make an alert keyed on the line fire on every regeneration")
                    .isEqualTo(RunReport.Settled.Reading.NOT_TAKEN);
            softly.assertThat(RunReport.Settled.NOT_TAKEN.reading().wire()).isEqualTo("not-taken");
            softly.assertThat(RunReport.Settled.UNREAD.reading().wire()).isEqualTo("unread");
            softly.assertThat(RunReport.Settled.NOTHING_ASSEMBLED.reading().wire())
                    .as("a night with nothing waiting settled nothing, and that is a measurement")
                    .isEqualTo("taken");
            softly.assertThat(RunReport.Settled.NOT_TAKEN)
                    .as("and it is its own value, not an alias of the refused one")
                    .isNotEqualTo(RunReport.Settled.UNREAD);
        }

        @Test
        void an_operators_overridden_run_should_keep_the_overridden_reading() {
            final RunReport overridden = RunReport.byOperator(new RunReport(new Proceed(true),
                    Map.of(), 0, Map.of(), 0, 0, RunReport.Settled.UNREAD, 0, 0, 0,
                    Duration.ZERO));

            softly.assertThat(overridden.gateDecision())
                    .as("the night this service generated while the flag said the legacy was is "
                            + "the one night most worth finding again")
                    .isEqualTo(new Proceed(true));
            softly.assertThat(overridden.trigger()).isEqualTo(RunReport.Trigger.OPERATOR);
        }
    }
}
