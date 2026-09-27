package uk.gov.hmcts.cp.yotresultsdistribution.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterRegenerationService.RegenerationTally;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterRegenerationService.Selection;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.BatchAssembler;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.AssembledBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchAssembly;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtCentreDay;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterHearingVenue;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.Deadline;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterRecord;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.support.AdjustableClock;

/**
 * {@code generate-register}'s orchestration, moved out of the command and asked for over HTTP.
 *
 * <p>The cases are the command's own, re-pointed: what may be released, what must be left where it
 * is and under which bounded reason, which registers reach the assembler, and the counts a person
 * reads afterwards. What is <strong>not</strong> here is the flag: the one lever is read once per
 * run by {@code OperationsRunLauncher}, before the caller is answered, so this service is told
 * whether an override happened and never asks.
 *
 * <p>The collaborators are doubled as small ledgers rather than with fixed answers, because what
 * this service does <em>is</em> the sequence of things it asks of them.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the regeneration an operator asks for")
class RegisterRegenerationServiceTest {

    /** The day a person is regenerating: the Thursday whose 18:00 run left a court centre out. */
    private static final LocalDate THURSDAY = LocalDate.of(2026, 8, 20);

    /** The court centre whose whole night failed. */
    private static final UUID LEEDS = UUID.fromString("6f1d0a4c-1c2b-4f8e-9a3d-70b2c5e41a01");

    /** Another court centre of the same day, which the narrowing has to leave alone. */
    private static final UUID BRADFORD = UUID.fromString("6f1d0a4c-1c2b-4f8e-9a3d-70b2c5e41a02");

    private static final String LEEDS_HOUSE = "Leeds Youth Court";

    private static final String BRADFORD_HOUSE = "Bradford Youth Court";

    /** 09:00 Europe/London on the Thursday, which is when the first hearing was shared. */
    private static final Instant MORNING = Instant.parse("2026-08-20T08:00:00Z");

    /** 18:00 Europe/London on the Thursday: the bound a part-finished re-run is completed to. */
    private static final Instant SIX_PM = Instant.parse("2026-08-20T17:00:00Z");

    /** 08:00 the next morning, which is when the support call is made. */
    private static final Instant NEXT_MORNING = Instant.parse("2026-08-21T07:00:00Z");

    private static final Duration RUN_DEADLINE = Duration.ofMinutes(60);

    private final RegisterStore store = mock(RegisterStore.class);
    private final BatchAssembler assembler = mock(BatchAssembler.class);
    private final RegisterGenerationService service = mock(RegisterGenerationService.class);
    private final AdjustableClock clock = AdjustableClock.startingAt(NEXT_MORNING);

    /** The day's batches the store holds, by the day they were recorded for. */
    private final Map<LocalDate, List<RegisterBatch>> recorded = new LinkedHashMap<>();

    /** The registers each batch still holds stamped, which a release gives back. */
    private final Map<UUID, List<RegisterRecord>> stamped = new LinkedHashMap<>();

    /** The registers the store calls active and unbatched, whatever day they are for. */
    private final List<RegisterRecord> unbatched = new ArrayList<>();

    /** The batches whose registers were released, in the order they were released. */
    private final List<UUID> released = new ArrayList<>();

    /** Every grouping this service asked the assembler for. */
    private final List<Grouping> groupings = new ArrayList<>();

    /** Every render it asked for, beside the bound it asked under. */
    private final List<Render> renders = new ArrayList<>();

    private final RegisterRegenerationService regeneration = new RegisterRegenerationService(
            store, assembler, service, RUN_DEADLINE, clock);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * One grouping this service asked the assembler for.
     *
     * @param registers       the registers it handed over
     * @param history         the batches it said the day already held
     * @param systemGenerated whether it claimed the schedule had asked
     */
    private record Grouping(List<RegisterRecord> registers, List<RegisterBatch> history,
            boolean systemGenerated) {
    }

    /**
     * One render this service asked for.
     *
     * @param batch    the batch it asked about
     * @param deadline the requesting bound it asked under
     */
    private record Render(RegisterBatch batch, Deadline deadline) {
    }

    @BeforeEach
    void theStoreAnswersFromItsRowsAndRecordsWhatItWasAsked() {
        when(store.batchesOn(any())).thenAnswer(
                call -> recorded.getOrDefault(call.getArgument(0), List.of()));
        when(store.activeUnbatched()).thenAnswer(call -> List.copyOf(unbatched));
        when(store.batched(any()))
                .thenAnswer(call -> stamped.getOrDefault(call.getArgument(0), List.of()));
        when(store.releaseFailed(any())).thenAnswer(call -> {
            final UUID batchId = call.getArgument(0);
            released.add(batchId);
            return stamped.getOrDefault(batchId, List.of());
        });
        when(store.assemble(any(), any())).thenAnswer(call -> call.getArgument(0));
        when(assembler.assemble(any(), any(), anyBoolean())).thenAnswer(call -> {
            final List<RegisterRecord> registers = call.getArgument(0);
            groupings.add(new Grouping(List.copyOf(registers), call.getArgument(1),
                    call.getArgument(2)));
            return groupingOf(registers);
        });
        when(service.request(any(), any(), any())).thenAnswer(call -> {
            final RegisterBatch batch = call.getArgument(0);
            renders.add(new Render(batch, call.getArgument(1)));
            return new BatchOutcome(batch.batchId(), BatchStatus.GENERATING, null, true);
        });
    }

    /**
     * The whole day, with nothing narrowed and no override.
     *
     * @return the selection an operator makes when a court centre's whole night failed
     */
    private static Selection theWholeDay() {
        return new Selection(THURSDAY, null, null, null, false);
    }

    /**
     * A batch the day holds, recorded against the day it names.
     *
     * @param batch     the batch the store answers with
     * @param registers the registers it still holds stamped, which a release gives back
     */
    private void theDayHolds(final RegisterBatch batch, final RegisterRecord... registers) {
        recorded.computeIfAbsent(batch.registerDate(), day -> new ArrayList<>()).add(batch);
        stamped.put(batch.batchId(), List.of(registers));
    }

    /**
     * A register the store calls active and unbatched.
     *
     * @param register the register waiting for a batch
     */
    private void stillWaiting(final RegisterRecord register) {
        unbatched.add(register);
    }

    /**
     * The assembler's own answer: one batch per key, nothing deferred.
     *
     * @param registers the registers it was handed
     * @return the grouping
     */
    private static BatchAssembly groupingOf(final List<RegisterRecord> registers) {
        final Map<CourtCentreDay, List<RegisterRecord>> byKey = registers.stream()
                .collect(Collectors.groupingBy(RegisterRecord::key, LinkedHashMap::new,
                        Collectors.toList()));
        return new BatchAssembly(byKey.entrySet().stream()
                .map(group -> new AssembledBatch(
                        batch(group.getKey(), courtHouseOf(group.getValue().getFirst()),
                                BatchStatus.PENDING),
                        group.getValue()))
                .toList(), List.of());
    }

    /**
     * The court house a register was produced at, as the batch row copies it.
     *
     * @param register the register that names the file
     * @return its court house
     */
    private static String courtHouseOf(final RegisterRecord register) {
        return register.document().hearingVenue().courtHouse();
    }

    /**
     * A batch as the store holds it.
     *
     * @param key        the court centre and register day it groups
     * @param courtHouse the court house it is described by
     * @param status     where it has got to
     * @return the batch row
     */
    private static RegisterBatch batch(final CourtCentreDay key, final String courtHouse,
            final BatchStatus status) {
        return new RegisterBatch(UUID.randomUUID(), key.courtCentreId(), "B01LY", courtHouse,
                key.registerDate(), "yotresultsdistribution_" + key.registerDate() + ".json", null, null,
                status, null, null, true, null, SIX_PM, null, null, null, null, 0, null, 0);
    }

    /**
     * A register recorded at a court centre, a court house and a moment.
     *
     * @param courtCentre  the court centre it was recorded for
     * @param day          the register day it falls under
     * @param courtHouse   the court house it was produced at
     * @param registerTime the moment the results were shared
     * @return the register as the batch half reads it back
     */
    private static RegisterRecord register(final UUID courtCentre, final LocalDate day,
            final String courtHouse, final Instant registerTime) {

        final UUID hearingId = UUID.randomUUID();
        final String fileName = "yotresultsdistribution_" + day + ".json";
        return new RegisterRecord(UUID.randomUUID(), hearingId, registerTime,
                new CourtCentreDay(courtCentre, day), registerTime, fileName, "Applicant",
                RecordedFlagState.ON,
                new CourtRegisterDocument(registerTime.toString(), registerTime.toString(),
                        hearingId.toString(), courtCentre.toString(), fileName, "Applicant",
                        new CourtRegisterHearingVenue("West Yorkshire", courtHouse, null),
                        List.of(), List.of()));
    }

    /** A register of the Thursday at Leeds, shared in the morning. */
    private static RegisterRecord leedsRegister() {
        return register(LEEDS, THURSDAY, LEEDS_HOUSE, MORNING);
    }

    /** A register of the Thursday at Bradford, shared in the morning. */
    private static RegisterRecord bradfordRegister() {
        return register(BRADFORD, THURSDAY, BRADFORD_HOUSE, MORNING);
    }

    /** The registers the assembler was handed on the one grouping this run asked for. */
    private List<RegisterRecord> grouped() {
        return groupings.isEmpty() ? List.of() : groupings.getLast().registers();
    }

    @Nested
    @DisplayName("what it releases")
    class WhatItReleases {

        @Test
        void a_failed_batch_of_the_day_should_be_released() {
            final RegisterRecord leeds = leedsRegister();
            theDayHolds(batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.FAILED), leeds);

            final RegenerationTally tally = regeneration.regenerate(theWholeDay(), false);

            softly.assertThat(released).as("the one FAILED batch of the day").hasSize(1);
            softly.assertThat(tally.released())
                    .as("no later run would ever pick a stamped row up, which is why this exists")
                    .isEqualTo(1);
            softly.assertThat(grouped()).extracting(RegisterRecord::outputId)
                    .containsExactly(leeds.outputId());
        }

        @Test
        void a_batch_that_has_not_failed_should_be_left_exactly_where_it_is() {
            theDayHolds(batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.NOTIFIED), leedsRegister());

            final RegenerationTally tally = regeneration.regenerate(theWholeDay(), false);

            softly.assertThat(released)
                    .as("a stamp cleared off a batch that is rendering is a second document, and "
                            + "a notified one's registers have already reached the team")
                    .isEmpty();
            softly.assertThat(tally.withheld())
                    .as("nor is it withheld: it was never a candidate")
                    .isEmpty();
        }

        @Test
        void a_failed_batch_whose_key_is_in_flight_should_be_withheld_key_in_flight() {
            final CourtCentreDay key = new CourtCentreDay(LEEDS, THURSDAY);
            final RegisterBatch failed = batch(key, LEEDS_HOUSE, BatchStatus.FAILED);
            theDayHolds(failed, leedsRegister());
            theDayHolds(batch(key, LEEDS_HOUSE, BatchStatus.GENERATING));

            final RegenerationTally tally = regeneration.regenerate(theWholeDay(), false);

            softly.assertThat(released)
                    .as("releasing rows the assembler will defer only moves them to the schedule")
                    .isEmpty();
            softly.assertThat(tally.withheld())
                    .containsExactly(Assertions.entry(failed.batchId(),
                            OperationsReason.KEY_IN_FLIGHT));
        }

        @Test
        void a_failed_batch_holding_a_register_outside_the_bound_should_be_withheld() {
            final RegisterBatch failed = batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.FAILED);
            theDayHolds(failed, leedsRegister(),
                    register(LEEDS, THURSDAY, LEEDS_HOUSE, SIX_PM));

            final RegenerationTally tally = regeneration.regenerate(
                    new Selection(THURSDAY, null, null, MORNING.plusSeconds(60), false), false);

            softly.assertThat(released)
                    .as("a batch is re-assembled whole, so one holding an excluded register "
                            + "cannot be released without handing that register to the 18:00 run")
                    .isEmpty();
            softly.assertThat(tally.withheld())
                    .containsExactly(Assertions.entry(failed.batchId(),
                            OperationsReason.OUTSIDE_THE_BOUND));
        }
    }

    @Nested
    @DisplayName("what the narrowing admits")
    class TheNarrowing {

        @Test
        void a_court_house_should_leave_another_court_houses_batch_alone() {
            final RegisterBatch leeds = batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.FAILED);
            final RegisterBatch bradford = batch(new CourtCentreDay(BRADFORD, THURSDAY),
                    BRADFORD_HOUSE, BatchStatus.FAILED);
            theDayHolds(leeds, leedsRegister());
            theDayHolds(bradford, bradfordRegister());

            regeneration.regenerate(new Selection(THURSDAY, LEEDS_HOUSE, null, null, false), false);

            softly.assertThat(released)
                    .as("another court house's registers are a wrong e-mail to a real team")
                    .containsExactly(leeds.batchId());
        }

        @Test
        void a_batch_id_should_leave_the_days_other_failed_batch_alone() {
            final RegisterBatch leeds = batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.FAILED);
            final RegisterBatch bradford = batch(new CourtCentreDay(BRADFORD, THURSDAY),
                    BRADFORD_HOUSE, BatchStatus.FAILED);
            theDayHolds(leeds, leedsRegister());
            theDayHolds(bradford, bradfordRegister());

            regeneration.regenerate(
                    new Selection(THURSDAY, null, leeds.batchId(), null, false), false);

            softly.assertThat(released).containsExactly(leeds.batchId());
        }

        @Test
        void a_named_batch_should_not_take_the_active_read_at_all() {
            final RegisterBatch leeds = batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.FAILED);
            theDayHolds(leeds, leedsRegister());
            stillWaiting(bradfordRegister());

            regeneration.regenerate(
                    new Selection(THURSDAY, null, leeds.batchId(), null, false), false);

            verify(store, never()).activeUnbatched();
            softly.assertThat(grouped())
                    .as("a register waiting for its first batch is not part of re-rendering one "
                            + "that failed")
                    .hasSize(1);
        }

        @Test
        void the_active_read_should_be_folded_in_where_no_batch_was_named() {
            stillWaiting(leedsRegister());

            final RegenerationTally tally = regeneration.regenerate(theWholeDay(), false);

            softly.assertThat(tally.registers())
                    .as("the one command answers both 'last night's court centre failed' and "
                            + "'these hearings arrived after 18:00'")
                    .isEqualTo(1);
        }

        @Test
        void another_days_waiting_register_should_not_be_grouped() {
            stillWaiting(register(LEEDS, THURSDAY.minusDays(1), LEEDS_HOUSE, MORNING));
            stillWaiting(leedsRegister());

            final RegenerationTally tally = regeneration.regenerate(theWholeDay(), false);

            softly.assertThat(tally.registers())
                    .as("the store answers with everything waiting, whatever day it is for")
                    .isEqualTo(1);
        }

        @Test
        void a_register_released_and_then_read_again_as_active_should_be_grouped_once() {
            final RegisterRecord leeds = leedsRegister();
            theDayHolds(batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.FAILED), leeds);
            stillWaiting(leeds);

            final RegenerationTally tally = regeneration.regenerate(theWholeDay(), false);

            softly.assertThat(grouped()).extracting(RegisterRecord::outputId)
                    .as("handing the assembler both copies would render a hearing into the day's "
                            + "document twice")
                    .containsExactly(leeds.outputId());
            softly.assertThat(tally.registers()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("what it asks for, and what it answers")
    class WhatItAsksFor {

        @Test
        void every_batch_it_assembles_should_be_written_down_as_not_system_generated() {
            stillWaiting(leedsRegister());

            regeneration.regenerate(theWholeDay(), false);

            softly.assertThat(groupings).extracting(Grouping::systemGenerated)
                    .as("progression's own flag, and the only thing that tells a later reader "
                            + "that a person asked for this document rather than 18:00")
                    .containsExactly(false);
        }

        @Test
        void the_render_should_be_asked_for_under_the_schedules_own_run_deadline() {
            stillWaiting(leedsRegister());

            regeneration.regenerate(theWholeDay(), false);

            softly.assertThat(renders).extracting(Render::deadline)
                    .as("computed once, at the moment requesting begins; a regeneration cannot "
                            + "outlive it by re-deriving it per batch")
                    .containsExactly(Deadline.startingAt(NEXT_MORNING, RUN_DEADLINE));
        }

        @Test
        void the_tally_should_carry_the_counts_the_command_printed() {
            final RegisterRecord leeds = leedsRegister();
            theDayHolds(batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.FAILED), leeds);
            stillWaiting(bradfordRegister());

            final RegenerationTally tally = regeneration.regenerate(theWholeDay(), false);

            softly.assertThat(tally.registerDate()).isEqualTo(THURSDAY);
            softly.assertThat(tally.released()).isEqualTo(1);
            softly.assertThat(tally.registers()).isEqualTo(2);
            softly.assertThat(tally.batches()).isEqualTo(2);
            softly.assertThat(tally.requested()).isEqualTo(2);
            softly.assertThat(tally.deferred()).isZero();
            softly.assertThat(tally.states().values())
                    .as("where each batch stood when the requesting leg let go of it")
                    .containsOnly(BatchStatus.GENERATING);
        }

        @Test
        void a_deadline_that_left_no_attempt_should_not_be_counted_as_requested() {
            stillWaiting(leedsRegister());
            // doAnswer rather than when(...), which would call the mock and run the stub this
            // one replaces, over the nulls the matchers stand in for.
            doAnswer(call -> {
                final RegisterBatch batch = call.getArgument(0);
                return new BatchOutcome(batch.batchId(), BatchStatus.FAILED,
                        BatchFailureReason.RENDER_REQUEST_FAILED, false);
            }).when(service).request(any(), any(), any());

            final RegenerationTally tally = regeneration.regenerate(theWholeDay(), false);

            softly.assertThat(tally.batches())
                    .as("the batch was written down")
                    .isEqualTo(1);
            softly.assertThat(tally.requested())
                    .as("and never asked for, which is what the count says")
                    .isZero();
        }

        @Test
        void a_deferred_key_should_be_counted_rather_than_invented_into_a_batch() {
            stillWaiting(leedsRegister());
            doReturn(new BatchAssembly(List.of(), List.of(new CourtCentreDay(LEEDS, THURSDAY))))
                    .when(assembler).assemble(any(), any(), anyBoolean());

            final RegenerationTally tally = regeneration.regenerate(theWholeDay(), false);

            softly.assertThat(tally.deferred()).isEqualTo(1);
            softly.assertThat(tally.batches()).isZero();
            softly.assertThat(renders).isEmpty();
        }

        @Test
        void an_override_should_be_carried_on_the_tally_rather_than_re_decided_here() {
            stillWaiting(leedsRegister());

            final RegenerationTally tally = regeneration.regenerate(theWholeDay(), true);

            softly.assertThat(tally.overridden())
                    .as("the run that went ahead over a flag that would have stopped it is the "
                            + "one run in this flow most worth finding again")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("a day that could not be worked through")
    class NotGenerated {

        @Test
        void a_store_that_will_not_answer_should_refuse_under_generation_failed() {
            doThrow(new StoreUnavailableException("batchesOn", new IllegalStateException("ZQX7")))
                    .when(store).batchesOn(any());

            Assertions.assertThatThrownBy(() -> regeneration.regenerate(theWholeDay(), false))
                    .isInstanceOf(OperationsRefusedException.class)
                    .extracting(refused -> ((OperationsRefusedException) refused).reason())
                    .isEqualTo(OperationsReason.GENERATION_FAILED);
        }

        @Test
        void the_refusal_should_carry_what_the_run_had_already_written_down() {
            theDayHolds(batch(new CourtCentreDay(LEEDS, THURSDAY), LEEDS_HOUSE,
                    BatchStatus.FAILED), leedsRegister());
            doThrow(new IllegalStateException("the live-key index refused the stamp"))
                    .when(store).assemble(any(), any());

            Assertions.assertThatThrownBy(() -> regeneration.regenerate(theWholeDay(), false))
                    .isInstanceOf(OperationsRefusedException.class)
                    .extracting(refused -> ((OperationsRefusedException) refused).properties())
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .as("the day stands as whatever this run had already written down")
                    .containsEntry("released", 1)
                    .containsEntry("registers", 1)
                    .containsEntry("requested", 0)
                    .containsEntry("date", THURSDAY.toString());
        }
    }
}
