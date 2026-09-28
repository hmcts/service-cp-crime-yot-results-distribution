package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.BatchAssembler;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.AssembledBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchAssembly;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.Deadline;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision.Proceed;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterRecord;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunReport;

/**
 * The night, asked for again by a person: read the day, release what failed, group it, ask again.
 *
 * <p>{@code GenerateRegisterCli.generate}, {@code narrowed}, {@code released},
 * {@code withheldReason}, {@code registers} and {@code request}, moved here whole with the printing
 * replaced by a record. Nothing about what a regeneration does is changed by the move: the same
 * reads in the same order, the same two reasons a FAILED batch is left where it is, the same
 * merge on the register's own identity, and the same run deadline computed once as requesting
 * begins.
 *
 * <p><strong>The one lever is not read here.</strong> It is read once per run by
 * {@link OperationsRunLauncher}, before the caller is answered, because a flag the caller is
 * refused on has to be a refusal the caller can see - and a second read in here would be a second
 * reader of the one lever with different semantics (constitution Cutover Rule). What this service
 * is told is whether the run it is about to make went ahead over a flag that would have stopped
 * it, so that the line it writes can say so.
 *
 * <p><strong>Every batch it assembles is written down as not system-generated</strong>, which is
 * progression's own flag and is what tells a later reader that a person asked for this document
 * rather than 18:00.
 *
 * <p><strong>A batch is released only where this run will re-assemble it.</strong> Clearing the
 * stamp is not undoable, and a row this run then leaves out is a row the 18:00 schedule picks up
 * as its own. So a FAILED batch whose key still has a batch in flight, and one holding a register
 * the narrowing excludes, are left carrying their stamps and named in the tally under the bounded
 * reason they were left under.
 *
 * <p><strong>A register is handed to the assembler once.</strong> A row released from its failed
 * batch is answered by {@link RegisterStore#activeUnbatched()} as well, and the store cannot tell
 * a caller which of the two reads happened first - so the two sources are merged on the register's
 * own identity rather than on the order they arrived in.
 */
public class RegisterRegenerationService {

    private static final Logger LOG =
            LoggerFactory.getLogger(RegisterRegenerationService.class);

    /**
     * False, because this is a person asking rather than the schedule.
     *
     * <p>progression's own flag, and the only thing that tells a later reader that a person asked
     * for this document rather than 18:00.
     */
    private static final boolean BY_HAND = false;

    /** Where the day's batches are read, its FAILED ones released and its new ones stamped. */
    private final RegisterStore store;

    /** The grouping, asked to say false where the schedule says true. */
    private final BatchAssembler assembler;

    /** The requesting leg, asked once per batch and sequentially, as the run asks it. */
    private final RegisterGenerationService service;

    /** The run deadline every regeneration works to, which is the schedule's own. */
    private final Duration runDeadline;

    /** This pod's reading of now, which the requesting deadline is measured from. */
    private final Clock clock;

    /**
     * Creates the regeneration over the three collaborators a generation needs.
     *
     * @param registerStore      where the day's batches are read, its FAILED ones released and its
     *                           new ones written down
     * @param batchAssembler     the grouping into one batch per court centre and register date
     * @param generationService  the requesting leg, asked once per batch and sequentially
     * @param generationDeadline the run deadline, handed in as a value rather than as the shape of
     *                           a configuration file
     * @param runClock           this pod's reading of now, which the deadline is measured from
     */
    public RegisterRegenerationService(final RegisterStore registerStore,
            final BatchAssembler batchAssembler, final RegisterGenerationService generationService,
            final Duration generationDeadline, final Clock runClock) {

        this.store = registerStore;
        this.assembler = batchAssembler;
        this.service = generationService;
        this.runDeadline = generationDeadline;
        this.clock = runClock;
    }

    /**
     * Re-assembles and re-requests the register date the selection names.
     *
     * @param selection the day, the narrowing and whether an override was asked for
     * @param overridden whether the flag read before this run said something other than ON and an
     *                   operator overrode it, which the line says out loud
     * @return what the run did, batch by batch and in counts
     * @throws OperationsRefusedException under {@link OperationsReason#GENERATION_FAILED} where the
     *         day could not be worked through, carrying what the run had already written down
     */
    // PMD.AvoidCatchingGenericException: the store translates an outage into its own unchecked type
    // and refuses a stamp with another; both mean the same thing here - this regeneration did not
    // finish - and a tally answered over either would say a day had been asked for that had not.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public RegenerationTally regenerate(final Selection selection, final boolean overridden) {
        final Progress progress = new Progress();
        final Instant startedAt = clock.instant();
        try {
            final List<RegisterBatch> day = store.batchesOn(selection.registerDate());
            final List<RegisterRecord> released =
                    released(narrowed(day, selection), day, selection, progress);
            progress.released(released.size());
            final List<RegisterRecord> registers = registers(released, selection);
            progress.registers(registers.size());
            final BatchAssembly assembly = assembler.assemble(registers, day, BY_HAND);
            progress.assembled(assembly.batches().size(), assembly.deferred().size(),
                    waiting(registers, assembly));
            request(assembly, progress);
            return progress.tallyOf(selection, overridden,
                    Duration.between(startedAt, clock.instant()));
        } catch (RuntimeException notGenerated) {
            LOG.error("The regeneration of register date {} did not finish, so the day stands as "
                            + "whatever this run had already written down. cause={}",
                    selection.registerDate(), notGenerated.getClass().getName());
            throw new OperationsRefusedException(OperationsReason.GENERATION_FAILED,
                    progress.partial(selection));
        }
    }

    /**
     * The day's batches this run is about, which is all of them unless an argument narrowed it.
     *
     * <p>This narrows what may be released and nothing else. The key's history is the day as the
     * store holds it: the supplementary link, the file name and the deferral are all decided from
     * it (design Q27), so an assembler told only about the batch an operator named would name the
     * day's supplement as its first document.
     *
     * @param day       every batch the day holds, whatever state it reached
     * @param selection the narrowing an operator asked for
     * @return the batches this run may release
     */
    private static List<RegisterBatch> narrowed(final List<RegisterBatch> day,
            final Selection selection) {

        return day.stream()
                .filter(batch -> selection.batchId() == null
                        || selection.batchId().equals(batch.batchId()))
                .filter(batch -> selection.courtHouse() == null
                        || selection.courtHouse().equals(batch.courtHouse()))
                .toList();
    }

    /**
     * The registers given back by the day's FAILED batches, in the order those batches were read.
     *
     * <p>The failure reasons that leave the stamp in place are exactly why this exists: no later
     * run will ever pick those registers up, because a stamped row is not active and unbatched. A
     * batch that has not failed is left exactly where it is.
     *
     * @param narrowed  the day's batches this run may release
     * @param day       every batch the day holds, which is where a key's other batches are
     * @param selection the day and the narrowing every register is held to
     * @param progress  what the run has done, which the withheld batches are named on
     * @return the registers whose stamp was cleared
     */
    private List<RegisterRecord> released(final List<RegisterBatch> narrowed,
            final List<RegisterBatch> day, final Selection selection, final Progress progress) {

        final List<RegisterRecord> released = new ArrayList<>();
        narrowed.stream()
                .filter(batch -> batch.status() == BatchStatus.FAILED)
                .forEach(batch -> {
                    final OperationsReason withheld = withheldReason(batch, day, selection);
                    if (withheld == null) {
                        released.addAll(store.releaseFailed(batch.batchId()));
                        progress.releasedBatch();
                    } else {
                        LOG.warn("The registers of batch {} were left where they are, because this "
                                        + "run would not have re-assembled them. reason={}",
                                batch.batchId(), withheld.wire());
                        progress.withheld(batch.batchId(), withheld);
                    }
                });
        return released;
    }

    /**
     * Why one FAILED batch is not released, or {@code null} where it is.
     *
     * <p>{@link OperationsReason#KEY_IN_FLIGHT} is the assembler's own deferral asked before the
     * release rather than after it; {@link OperationsReason#OUTSIDE_THE_BOUND} is the operator's
     * narrowing: a batch is re-assembled whole, so one holding a register the bound or the court
     * house excludes cannot be released without handing that register to the 18:00 run.
     *
     * @param batch     one FAILED batch of the day, narrowed to this run
     * @param day       every batch the day holds
     * @param selection the day and the narrowing every register is held to
     * @return the bounded reason it is left alone, or {@code null} where it may be released
     */
    private OperationsReason withheldReason(final RegisterBatch batch,
            final List<RegisterBatch> day, final Selection selection) {

        OperationsReason reason = null;
        if (day.stream().anyMatch(other -> batch.key().equals(other.key())
                && BatchAssembler.inFlight(other.status()))) {
            reason = OperationsReason.KEY_IN_FLIGHT;
        } else if (!store.batched(batch.batchId()).stream().allMatch(selection::holds)) {
            reason = OperationsReason.OUTSIDE_THE_BOUND;
        }
        return reason;
    }

    /**
     * The registers this run hands the assembler: what it released, and what never reached a batch.
     *
     * <p>Merged on the register's own identity, because the two reads overlap. The active read is
     * not taken at all where a batch was named: a register waiting for its first batch is not part
     * of re-rendering one that failed.
     *
     * @param released  the registers the day's FAILED batches gave back
     * @param selection the day and the narrowing every register is held to
     * @return the registers to be grouped, first seen first
     */
    private List<RegisterRecord> registers(final List<RegisterRecord> released,
            final Selection selection) {

        final Map<UUID, RegisterRecord> byIdentity = new LinkedHashMap<>();
        released.forEach(register -> byIdentity.putIfAbsent(register.outputId(), register));
        if (selection.batchId() == null) {
            store.activeUnbatched()
                    .forEach(register -> byIdentity.putIfAbsent(register.outputId(), register));
        }
        return byIdentity.values().stream().filter(selection::holds).toList();
    }

    /**
     * How many registers are waiting under the days the assembler passed over.
     *
     * <p>Counted by subtraction rather than by reading the deferred keys back, because every
     * register handed to the assembler either went into a batch or did not: a second read to learn
     * which would be a second statement for a number the run already knows.
     *
     * @param handed   the registers this run gave the assembler
     * @param assembly what it made of them
     * @return how many of them reached no batch
     */
    private static int waiting(final List<RegisterRecord> handed, final BatchAssembly assembly) {
        final int batched = assembly.batches().stream()
                .mapToInt(assembled -> assembled.records().size())
                .sum();
        return handed.size() - batched;
    }

    /**
     * Writes each batch down and then asks for its render, in that order and one at a time.
     *
     * <p>Everything downstream is keyed on the batch, so a render asked for before the row exists
     * would find no registers. The bound is computed once, at the moment requesting begins, and is
     * the same run deadline the schedule works to.
     *
     * @param assembly what the assembler made of the day
     * @param progress what the run has done, which each batch's state is written onto
     */
    private void request(final BatchAssembly assembly, final Progress progress) {
        final Deadline deadline = Deadline.startingAt(clock.instant(), runDeadline);
        for (final AssembledBatch assembled : assembly.batches()) {
            final RegisterBatch stored = store.assemble(assembled.batch(), assembled.records());
            final BatchOutcome outcome = service.request(stored, deadline, RenderProgress.NONE);
            // The requesting leg's own account of what it did, exactly as the run's line reads it.
            // A batch the deadline left without an attempt was written down and never asked for.
            progress.requested(stored.batchId(), outcome.status(), outcome.renderRequested(),
                    assembled.records().size());
        }
    }

    /**
     * What an operator asked for, read once and never re-interpreted.
     *
     * @param registerDate   the London register day being regenerated
     * @param courtHouse     one court house of it, or {@code null} for all of them
     * @param batchId        one batch of it, or {@code null} for all of them
     * @param recordedBefore the exclusive bound on what had been shared, or {@code null} for the
     *                       whole day
     * @param ignoreFlag     whether the operator asked to proceed over a flag that says otherwise
     */
    public record Selection(LocalDate registerDate, String courtHouse, UUID batchId,
                            Instant recordedBefore, boolean ignoreFlag) {

        /**
         * Whether one register is part of the run the operator asked for.
         *
         * <p>The store answers with everything waiting, whatever day it is for, so the day is
         * tested here as well as asked for. The bound is tested against the register's own shared
         * instant, which is what {@code recordedBefore} names a period of.
         *
         * @param register a register released or waiting
         * @return true where the day, the court house and the bound all admit it
         */
        public boolean holds(final RegisterRecord register) {
            return registerDate.equals(register.key().registerDate())
                    && (courtHouse == null || courtHouse.equals(courtHouseOf(register)))
                    && (recordedBefore == null
                            || register.registerTime().isBefore(recordedBefore));
        }

        /**
         * The court house a register was produced at, as the batch row copies it.
         *
         * @param register the register that names it
         * @return its court house, or {@code null} where the document names no venue
         */
        private static String courtHouseOf(final RegisterRecord register) {
            return register.document() == null || register.document().hearingVenue() == null
                    ? null
                    : register.document().hearingVenue().courtHouse();
        }
    }

    /**
     * What one regeneration did, in the counts the command printed and in the batches it touched.
     *
     * @param registerDate the day the run was about
     * @param released     how many registers the day's FAILED batches gave back
     * @param registers    how many registers were handed to the assembler
     * @param batches      how many batches it made of them
     * @param requested    how many of those this service asked systemdocgenerator to render
     * @param deferred     how many court centre days were left waiting by the assembler
     * @param overridden   whether the run went ahead over a flag that would have stopped it
     * @param withheld     the FAILED batches left carrying their stamps, under the bounded reason
     * @param states       where each batch stood when the requesting leg let go of it
     * @param report       the same night in the shape the run line is written from, under
     *                     {@link RunReport.Trigger#OPERATOR} - so that a regeneration and the
     *                     schedule leave one line an operator can read, and not two
     */
    public record RegenerationTally(LocalDate registerDate, int released, int registers,
                                    int batches, int requested, int deferred, boolean overridden,
                                    Map<UUID, OperationsReason> withheld,
                                    Map<UUID, BatchStatus> states, RunReport report) {

        /**
         * Copies the two maps, so a tally cannot be changed after the run that answered it.
         */
        public RegenerationTally {
            withheld = Map.copyOf(withheld);
            states = Map.copyOf(states);
        }
    }

    /**
     * What the run has done, filled in as it goes.
     *
     * <p>Mutable and private, for the reason the nightly run's tally is: a run that stopped half
     * way through has still done the first half, and the counts are the only place that says so.
     */
    private static final class Progress {

        private final Map<UUID, OperationsReason> withheldBatches = new LinkedHashMap<>();

        private final Map<UUID, BatchStatus> batchStates = new LinkedHashMap<>();

        private int releasedCount;

        private int registerCount;

        private int batchCount;

        private int requestedCount;

        private int deferredCount;

        /** How many batches the release actually gave back, as against the registers inside them. */
        private int releasedBatchCount;

        /** How many registers ended under each state, counted exactly as the schedule counts them. */
        private final Map<BatchStatus, Integer> rowOutcomes = new EnumMap<>(BatchStatus.class);

        /** How many registers are waiting under the days the assembler passed over. */
        private int deferredRowCount;

        /**
         * Records a FAILED batch left carrying its stamp.
         *
         * @param batchId the batch left alone
         * @param reason  the bounded reason it was
         */
        private void withheld(final UUID batchId, final OperationsReason reason) {
            withheldBatches.put(batchId, reason);
        }

        /**
         * Records how many registers the release gave back.
         *
         * @param count the number released
         */
        private void released(final int count) {
            releasedCount = count;
        }

        /**
         * Records how many registers were handed to the assembler.
         *
         * @param count the number grouped
         */
        private void registers(final int count) {
            registerCount = count;
        }

        /**
         * Records what the assembler made of them, and what it left waiting.
         *
         * <p>The waiting registers are counted here rather than at the end, for the reason the
         * nightly run counts them here: they are known the moment the assembler answers, and a run
         * that stops while requesting still has to be able to say how much of the day it passed
         * over.
         *
         * @param batches  how many batches it made
         * @param deferred how many court centre days it left waiting
         * @param waiting  how many registers are inside those days
         */
        private void assembled(final int batches, final int deferred, final int waiting) {
            batchCount = batches;
            deferredCount = deferred;
            deferredRowCount = waiting;
        }

        /** Records one FAILED batch whose registers this run took back. */
        private void releasedBatch() {
            releasedBatchCount++;
        }

        /**
         * Records one batch's state, and whether its render was asked for.
         *
         * @param batchId  the batch
         * @param status   where it stood when the requesting leg let go of it
         * @param asked    whether systemdocgenerator had been asked by then
         */
        private void requested(final UUID batchId, final BatchStatus status, final boolean asked,
                final int registers) {

            batchStates.put(batchId, status);
            if (asked) {
                requestedCount++;
            }
            rowOutcomes.merge(status, registers, Integer::sum);
        }

        /**
         * The tally of a run that was worked through.
         *
         * @param selection  what the run was asked for
         * @param overridden whether it went ahead over a flag that would have stopped it
         * @return the tally
         */
        private RegenerationTally tallyOf(final Selection selection, final boolean overridden,
                final Duration took) {

            return new RegenerationTally(selection.registerDate(), releasedCount, registerCount,
                    batchCount, requestedCount, deferredCount, overridden, withheldBatches,
                    batchStates, reportOf(overridden, took));
        }

        /**
         * The same night, in the shape the run line is written from.
         *
         * <p>Every count here is one this run earned. The snapshot is
         * {@link RunReport.Settled#NOT_TAKEN} because a regeneration takes none - the night it is
         * part of is read back from {@code GET /operations/batches?date=D}, and four zeroes
         * claiming a settled nothing would be a measurement nobody took. Not
         * {@link RunReport.Settled#UNREAD}, which is a read the store refused and which the 18:00
         * run counts on {@code yotresultsdistribution_generation_unrecorded_total}: this run counts
         * nothing, and one word for both would fire that alert on every regeneration. {@code contended} is
         * nought for the same kind of reason: the release a regeneration makes is the operator's
         * narrowing rather than the stale-batch pass, and it has no contended case to report.
         *
         * @param overridden whether the run went ahead over a flag that would have stopped it
         * @param took       how long it took
         * @return the report, under {@link RunReport.Trigger#OPERATOR}
         */
        private RunReport reportOf(final boolean overridden, final Duration took) {
            final Map<BatchStatus, Integer> outcomes = new EnumMap<>(BatchStatus.class);
            batchStates.values().forEach(status -> outcomes.merge(status, 1, Integer::sum));
            return RunReport.byOperator(new RunReport(new Proceed(overridden), outcomes,
                    requestedCount, rowOutcomes, deferredCount, deferredRowCount,
                    RunReport.Settled.NOT_TAKEN, releasedBatchCount, releasedCount, 0, took));
        }

        /**
         * What a run that stopped had already written down, as the bounded extras of its refusal.
         *
         * @param selection what the run was asked for
         * @return counts and an identifier, and nothing that came from outside this service
         */
        private Map<String, Object> partial(final Selection selection) {
            return Map.of("date", selection.registerDate().toString(),
                    "released", releasedCount,
                    "registers", registerCount,
                    "batches", batchCount,
                    "requested", requestedCount,
                    "deferred", deferredCount);
        }
    }
}
