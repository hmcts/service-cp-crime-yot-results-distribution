package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.time.Duration;
import java.util.Map;

/**
 * What one nightly run did, told in one line.
 *
 * <p>A value rather than a table. The per-batch facts are already durable in {@code register_batch};
 * what a run adds is the shape of the night - what the flag said, how many batches it asked the
 * renderer for, how many ended each way, how many registers were inside them, how many
 * court-centre days it had to pass over and how many registers are waiting under those, how much of
 * the night had come back by the time it reported, what its first act had to give back, and how
 * long the requesting half took against its deadline - and every one of them is a question asked of
 * a dashboard rather than of a database.
 *
 * <p><strong>Batches and registers are two different accounts of the same night.</strong> A batch
 * is one document and one e-mail; a register is one hearing's youth defendants. One batch left for
 * the next run is one court centre, and whether that matters tonight is decided by how many
 * registers are inside it - which no count of batches can answer. So the run keeps both, and both
 * add up to the night: {@link #outcomes} totals the batches the run accounted for and
 * {@link #rows()} totals the registers, each of them counted exactly once, in the batch it was
 * stamped into or under the day the assembler passed over.
 *
 * <p><strong>What the run asked for is not what came of it, and this reports both.</strong>
 * {@link #requested} counts the batches whose payload was written and whose render was asked for,
 * which is where the nightly job's leg ends: the outcome of a render arrives afterwards, on the
 * public-event topic. It is counted at the call rather than off the verdict, so a batch whose
 * render left and whose mark the store then refused is still one render here - and is in none of
 * the {@link #outcomes}, which is a divergence to be read rather than a total that does not add up.
 * {@link #settled} is the other side of that, and it is read rather than reasoned about - see
 * {@link Settled}, which says exactly what its four counts are and, as importantly, what they are
 * not.
 *
 * <p><strong>And three numbers that are a diagnostic rather than an account.</strong>
 * {@link #releasedBatches} and {@link #releasedRegisters} are what the run's first act gave back -
 * the batches that had not completed by the time this run began, and the registers that came back
 * with them - and {@link #contended} is what it could not give back. They are deliberately
 * <em>not</em> a third sum: the registers counted are re-batched by this same run, so they are
 * already inside {@link #rows()}, and adding them to either total would count them twice. A night
 * that released none says nought, because a night that released nothing and a night that did not
 * report are different lines (FR-009).
 *
 * <p><strong>A skipped run still produces one.</strong> A run that read OFF and did nothing is the
 * expected state for every night before cutover, and a report that only appeared when work happened
 * would make "the flag is off" and "the job did not fire" the same silence.
 *
 * <p><strong>The keys it passed over are counted, not named.</strong>
 * {@link BatchAssembly#deferred()} is what a run knowingly left for the next one - a court centre
 * and day whose earlier batch is still in flight - and a night of them is a night that produced no
 * document for those courts. Without a count here that fact appears in no total the report carries:
 * a deferred key is in none of the {@link #outcomes}, because no batch was assembled for it. It is
 * a number rather than the keys themselves because a court centre and a date are what the registers
 * behind them are addressed by, and a report is read from a log index (constitution Principle VII);
 * which keys they were is answered from {@code register_batch} and
 * {@code yotresultsdistribution_oldest_recorded_unbatched_age} says how long the oldest has waited.
 *
 * <p><strong>What the gate decided, not what the store answered.</strong> The report says what the
 * run knows, and what a run knows about the flag is the {@link GateDecision} it was given: a
 * {@link FlagDecision} here could say neither that an operator overrode a flag that had not said ON
 * - which is the one night in this flow most worth reading a report for - nor which of the six
 * unreadable causes stopped a run, since the gate does not pass the cause on. Those six keep their
 * own series on {@code yotresultsdistribution_generation_skipped_total}, which is where
 * {@link uk.gov.hmcts.cp.yotresultsdistribution.batch.FeatureFlagGate} already puts them.
 *
 * <p><strong>And who asked for it.</strong> {@link #trigger} is the one word that tells the run
 * the schedule fired from the run a named person asked for over {@code POST
 * /operations/batches/generate}. They are the same run doing the same work under the same lock, so
 * they write the same line; what differs is that one of them has somebody behind it, and an
 * operator's run over a flag that said OFF is the single night in this flow most worth finding
 * again. The schedule's value is the default - the eleven-component constructor below is the
 * schedule's, unchanged from the call site that has always used it - and the operator's is
 * {@link #byOperator}.
 *
 * @param trigger           what started the run: the schedule, or a named person over the
 *                          operations API
 * @param gateDecision      what the gate decided from its one read of the flag, which is the first
 *                          thing a run does and the reason a skipped run is a success
 * @param outcomes          how many batches ended in each state; empty for a skipped run
 * @param requested         how many batches this run asked systemdocgenerator to render - the
 *                          payload written and the request away - whatever the renderer then
 *                          answered and whatever the run afterwards managed to write down about it;
 *                          zero for a skipped run
 * @param rowOutcomes       how many registers this run stamped into a batch, under the state that
 *                          batch's requesting leg ended in; empty for a skipped run
 * @param deferredKeys      how many court-centre days the assembler passed over because a batch of
 *                          theirs is still in flight ({@link BatchAssembly#deferred()}); zero for a
 *                          skipped run, and the count of the court centres this run knowingly left
 *                          for the next one
 * @param deferredRows      how many registers are waiting under those days, which is what the
 *                          deferral costs measured in hearings rather than in court centres
 * @param settled           what the store said tonight's batches had come to at the moment this
 *                          report was made, which is a snapshot of a night that may still be
 *                          settling and not a final tally ({@link Settled})
 * @param releasedBatches   how many batches the run's first act failed and released, having found
 *                          them still awaiting a render this service can no longer ask about;
 *                          nought for a skipped run, which does not run the pass at all
 * @param releasedRegisters how many registers came back with them and are still the day's to
 *                          render - already inside {@link #rows()}, because the same run re-batches
 *                          them, and therefore not a total to be added to anything
 * @param contended         how many batches the pass left exactly as it found them, every attempt
 *                          at them having lost the race for the day's active register; they are
 *                          stale still, so the next run reaches them again
 * @param duration          how long the run took
 */
public record RunReport(
        Trigger trigger,
        GateDecision gateDecision,
        Map<BatchStatus, Integer> outcomes,
        int requested,
        Map<BatchStatus, Integer> rowOutcomes,
        int deferredKeys,
        int deferredRows,
        Settled settled,
        int releasedBatches,
        int releasedRegisters,
        int contended,
        Duration duration) {

    /**
     * Freezes both sets of counts, and settles absent and empty as one statement.
     *
     * <p>A copy because the run accumulates these as it goes: a report holding those same maps
     * would describe whatever the run did next rather than what it had done when the report was
     * made.
     *
     * <p>An absent snapshot is {@link Settled#UNREAD} rather than four zeroes, because a caller
     * that said nothing about it has not measured a night that settled nothing.
     */
    public RunReport {
        trigger = trigger == null ? Trigger.SCHEDULE : trigger;
        outcomes = outcomes == null ? Map.of() : Map.copyOf(outcomes);
        rowOutcomes = rowOutcomes == null ? Map.of() : Map.copyOf(rowOutcomes);
        settled = settled == null ? Settled.UNREAD : settled;
    }

    /**
     * The schedule's own report, which is every report this service wrote before increment 005.
     *
     * <p>The default is the schedule's because the schedule is what a run is unless somebody says
     * otherwise: a night nobody asked for is the ordinary case, and a constructor that made every
     * caller name it would have made the 18:00 job say out loud what it has never had to.
     *
     * @param gateDecision      what the gate decided from its one read of the flag
     * @param outcomes          how many batches ended in each state
     * @param requested         how many batches this run asked the renderer for
     * @param rowOutcomes       how many registers ended under each of those states
     * @param deferredKeys      how many court-centre days the assembler passed over
     * @param deferredRows      how many registers are waiting under those days
     * @param settled           what the store said tonight's batches had come to
     * @param releasedBatches   how many batches the run's first act failed and released
     * @param releasedRegisters how many registers came back with them
     * @param contended         how many batches the pass left exactly as it found them
     * @param duration          how long the run took
     */
    public RunReport(final GateDecision gateDecision, final Map<BatchStatus, Integer> outcomes,
            final int requested, final Map<BatchStatus, Integer> rowOutcomes,
            final int deferredKeys, final int deferredRows, final Settled settled,
            final int releasedBatches, final int releasedRegisters, final int contended,
            final Duration duration) {

        this(Trigger.SCHEDULE, gateDecision, outcomes, requested, rowOutcomes, deferredKeys,
                deferredRows, settled, releasedBatches, releasedRegisters, contended, duration);
    }

    /**
     * The same night, said to have been asked for by a person.
     *
     * <p>The second factory, and the only way a report becomes an operator's: a run is the
     * schedule's until somebody says it was theirs, which is the safe direction for a field an
     * alert reads to find the nights a person drove.
     *
     * @param report what the run did, counted exactly as the schedule's runs are counted
     * @return that same account, under {@link Trigger#OPERATOR}
     */
    public static RunReport byOperator(final RunReport report) {
        return new RunReport(Trigger.OPERATOR, report.gateDecision(), report.outcomes(),
                report.requested(), report.rowOutcomes(), report.deferredKeys(),
                report.deferredRows(), report.settled(), report.releasedBatches(),
                report.releasedRegisters(), report.contended(), report.duration());
    }

    /**
     * What started a run, as one bounded word on the line.
     *
     * <p>Two values and no third: either the schedule fired it or a named person asked for it over
     * the operations API. A caller is never named here - who the person was belongs in the audit
     * event, which is the one place this service names a caller on purpose (Principle VII).
     */
    public enum Trigger {

        /** The 18:00 Europe/London weekday run, which is every run this service used to have. */
        SCHEDULE("schedule"),

        /** A regeneration a named person asked for over {@code POST /operations/batches/generate}. */
        OPERATOR("operator");

        /** The bounded word the run line carries. */
        private final String spelling;

        Trigger(final String lineValue) {
            this.spelling = lineValue;
        }

        /**
         * The word the line says, which is what an alert filters on.
         *
         * @return the bounded value
         */
        public String wire() {
            return spelling;
        }
    }

    /**
     * How far tonight's batches had got by the time the run wrote its line.
     *
     * <p><strong>A snapshot, and it says so in the name.</strong> The requesting leg ends when
     * systemdocgenerator has been asked (FR-008) and the outcome of a render is applied afterwards
     * by the event listener, so these four counts are read back out of {@code register_batch} at
     * the moment the line is written rather than known by the leg that did the requesting. What
     * they describe is therefore a night that may still be settling: a render accepted at 18:04 and
     * marked at 18:04:30 is counted here and one accepted at 18:59 is not, and the same run
     * reported a minute later would count more. It is not a final tally of the night and must not
     * be read as one - what a night came to in the end is the rows themselves, and {@code
     * yotresultsdistribution_batches_total} by outcome is the estate's cumulative count across every night
     * rather than an answer about this one.
     *
     * <p><strong>It is worth taking even so</strong>, which is what the reading it replaced got
     * wrong. That reading was that these counts are zero by construction, the requesting leg ending
     * where it does; but the completion legs run while the run does, so a court centre whose render
     * comes back in seconds is marked and notified while the run is still asking about the court
     * centres behind it. Nothing else can be asked how much of <em>one</em> night had come back:
     * the cumulative counter cannot be scoped to a run and the oldest-generating and
     * oldest-generated gauges answer about ages rather than about a night.
     *
     * <p><strong>The arithmetic, and the sum that is deliberately not claimed.</strong>
     * {@link #generated} counts the batches whose document exists by now - GENERATED and every
     * state past it, because a batch that has been notified was generated first and a count that
     * went down as the night progressed would be unreadable. {@link #notified} counts the batches
     * the notifying leg has finished with, which is the three endings it can produce: everybody was
     * told, some were and the rest are resendable, and there was nobody to tell (defect fix P1).
     * Which of the three a batch reached is a distinction this service keeps everywhere it matters -
     * the row, and {@code yotresultsdistribution_batches_total} by outcome - and one a count of batches
     * cannot carry, so it is not collapsed here so much as not asked. So
     * {@code notified <= generated}, and both sit <em>inside</em> the requesting leg's own account
     * rather than partitioning it: every batch counted here was counted GENERATING by
     * {@link RunReport#outcomes} on a run that finished its requesting, and neither count is
     * subtracted from that one. The two sums the report does claim are unchanged and still hold -
     * the batch outcomes total the night's batches and {@link RunReport#rows()} totals its
     * registers - and there is no third sum over these four: adding them to either total would
     * count the same batches and the same registers twice.
     *
     * <p><strong>A run that stopped part way is the one case even {@code notified <= generated
     * <= generating} can come apart on</strong>, and it is a reading rather than a defect: a batch
     * whose render was accepted and whose verdict was lost with the run is in none of the
     * requesting leg's three counts and can still be settled by the time the line is written. A
     * snapshot larger than the account beside it is then exactly the divergence worth seeing.
     *
     * <p><strong>And the read is not allowed to cost the night.</strong> The batches are stamped and
     * the renders are away by the time it is taken, so a store that will not answer is not a reason
     * to fail a run - it is a reason to say the counts are missing. {@link #UNREAD} is that
     * statement, and the run writes it on the line as a word rather than as four zeroes, because a
     * night that settled nothing and a night nobody could read are otherwise the same line.
     *
     * <p><strong>And a reading nobody took is a third thing again.</strong> A regeneration takes no
     * settled reading at all - the night it is part of is read back from
     * {@code GET /operations/batches?date=D} - so it is {@link #NOT_TAKEN} rather than
     * {@link #UNREAD}. The distinction is not a nicety: a refused read also WARNs and moves
     * {@code yotresultsdistribution_generation_unrecorded_total}, and an operator run moves nothing, so one
     * word for both would make the line and the counter disagree and would fire an alert keyed on
     * the line every time somebody regenerated a date.
     *
     * @param reading       which of the three this is: the store answered, the store refused, or
     *                      nobody asked. The four counts beside it are measurements only under
     *                      {@link Reading#TAKEN}
     * @param generated     how many of the batches this run assembled have a document by now,
     *                      whether or not anybody has been told about them yet
     * @param notified      how many of them the notifying leg has finished with, under any of its
     *                      three endings
     * @param generatedRows how many registers are inside the batches counted by {@link #generated},
     *                      as this run stamped them
     * @param notifiedRows  how many are inside the batches counted by {@link #notified}
     */
    public record Settled(Reading reading, int generated, int notified, int generatedRows,
            int notifiedRows) {

        /**
         * What the four counts beside it are, in the one word the run line carries.
         *
         * <p>Three values rather than a boolean, because the two ways a run has no counts are not
         * the same event: one is a store that refused and is worth an alert, the other is a run
         * that never asks.
         */
        public enum Reading {

            /** The store answered, so the four counts are measurements. */
            TAKEN("taken"),

            /** The store was asked and refused, which is counted and said at WARN. */
            UNREAD("unread"),

            /** Nobody asked, because this kind of run takes no settled reading. */
            NOT_TAKEN("not-taken");

            /** The word the run line carries, which is what a dashboard partitions on. */
            private final String spelling;

            /**
             * Binds a value to the word the line carries.
             *
             * @param wireSpelling the word as the run line writes it
             */
            Reading(final String wireSpelling) {
                this.spelling = wireSpelling;
            }

            /**
             * The word the run line carries.
             *
             * @return the spelling, which is bounded and never composed
             */
            public String wire() {
                return spelling;
            }
        }

        /**
         * A reading nobody took, because this kind of run takes none.
         *
         * <p>What a regeneration writes. Deliberately not {@link #UNREAD}: that one is a read this
         * service issued and the store refused, and it is counted.
         */
        public static final Settled NOT_TAKEN = new Settled(Reading.NOT_TAKEN, 0, 0, 0, 0);

        /**
         * The read was refused, so nothing is claimed and the line says which.
         *
         * <p>Zeroes rather than a negative or an absent value, because the line an operator reads
         * is a set of counts and the word beside them is what says these four are not measurements.
         */
        public static final Settled UNREAD = new Settled(Reading.UNREAD, 0, 0, 0, 0);

        /**
         * The run assembled no batch, so there is nothing to ask about and the empty answer is
         * exact.
         *
         * <p>Distinct from {@link #UNREAD} and deliberately not the same statement: a skipped night
         * and a night with nothing waiting have settled nothing because there was nothing to settle,
         * which is a fact about the night, and no statement is issued against the store to learn it.
         */
        public static final Settled NOTHING_ASSEMBLED = new Settled(Reading.TAKEN, 0, 0, 0, 0);

        /**
         * A reading with no value stated is the refused one, which is the safe direction.
         *
         * @param reading       which of the three this is
         * @param generated     batches with a document by now
         * @param notified      batches the notifying leg has finished with
         * @param generatedRows registers inside the first
         * @param notifiedRows  registers inside the second
         */
        public Settled {
            reading = reading == null ? Reading.UNREAD : reading;
        }

        /**
         * What the store said, counted.
         *
         * @param generated     batches with a document by now
         * @param notified      batches the notifying leg has finished with
         * @param generatedRows registers inside the first
         * @param notifiedRows  registers inside the second
         * @return that snapshot, marked as one the store answered
         */
        public static Settled taken(final int generated, final int notified, final int generatedRows,
                final int notifiedRows) {
            return new Settled(Reading.TAKEN, generated, notified, generatedRows, notifiedRows);
        }
    }

    /**
     * Every register this run accounted for, batched or left waiting.
     *
     * <p>The total the row counts have to add up to. Derived rather than carried, because a total
     * held beside its parts is a second place for them to disagree - and the disagreement would be
     * a register the night reported and could not say what happened to.
     *
     * @return how many registers the run saw
     */
    public int rows() {
        return rowOutcomes.values().stream().mapToInt(Integer::intValue).sum() + deferredRows;
    }
}
