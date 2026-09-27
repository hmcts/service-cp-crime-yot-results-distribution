package uk.gov.hmcts.cp.yotresultsdistribution.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.hmcts.cp.yotresultsdistribution.application.FeatureFlagReader;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagStateSnapshot;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;
import uk.gov.hmcts.cp.yotresultsdistribution.support.AdjustableClock;

/**
 * The label a recorded register carries about the one lever, and the read it never waits for.
 *
 * <p>Every row this service records says which implementation was meant to be generating when the
 * command arrived. It matters only once, and it matters completely: commands still on the queue
 * after the producer has stopped publishing may belong to hearings the resumed legacy also
 * processed, and batching those automatically would send one child's register to a Youth Offending
 * Team twice (research §12). A row labelled anything other than {@code ON} is kept out of automatic
 * batching and surfaced to an operator instead.
 *
 * <p><strong>Two rules, and they pull in opposite directions.</strong> The label has to be
 * <em>true</em>, so a reading is only allowed to speak for a command that arrived inside its window;
 * a flag read a quarter of an hour ago says nothing about a cutover that was rolled back ten minutes
 * ago, and a reading that has aged out is worth exactly what no reading at all is worth. And the
 * label may never <em>cost</em> anything: a recording that waited on App Configuration would stall
 * the whole queue behind an outage in a store this service does not own, to decide a column. So the
 * arriving command schedules the refresh and is labelled from what is already known, and the refresh
 * is for the commands behind it.
 *
 * <p>{@link RecordedFlagState#UNKNOWN} is what both of those rules fall back to, and it is not a
 * synonym for {@code OFF}. OFF says the flag was read and the legacy generates; UNKNOWN says nobody
 * knows. Both are excluded from automatic batching, but only one of them is a working App
 * Configuration store, and support needs to be able to tell which it has.
 *
 * <p>Guards T030: {@link FlagStateSnapshot#stateFor} and {@link RecordedFlagStateSource#current()}.
 */
@DisplayName("Recorded flag state")
class RecordedFlagStateTest {

    /** When the reading in these cases was taken. Any instant does; this one is a nightly's eve. */
    private static final Instant READ_AT = Instant.parse("2026-09-06T17:59:30Z");

    /**
     * How long a reading may speak for a command, from research §12.
     *
     * <p>Stated here rather than read from the production constant on purpose: a test that imports
     * the window it is checking asserts that the number equals itself, and would follow a change to
     * sixty minutes without a word.
     */
    private static final Duration WINDOW = Duration.ofSeconds(60);

    /** Enough to be outside the window and too little to be a different rule. */
    private static final Duration A_MOMENT = Duration.ofMillis(1);

    /**
     * How many windows of nothing a quiet stack leaves between two commands.
     *
     * <p>The plan's traffic is about 160 commands a day on a stack, which is one every nine minutes
     * on average - nine windows, and every one of them long enough for a reading to age out.
     */
    private static final int QUIET_WINDOWS = 9;

    /** How long the off-thread case will wait for a refresh to get where it is going. */
    private static final Duration PATIENCE = Duration.ofSeconds(5);

    private final FeatureFlagReader reader = mock(FeatureFlagReader.class);

    private final AdjustableClock clock = AdjustableClock.startingAt(READ_AT);

    /**
     * The refreshes handed over, held rather than run.
     *
     * <p>An executor that runs nothing is the whole of the "never waits" assertion: a source that
     * read the flag on the caller's thread would reach the reader with this list still empty.
     */
    private final List<Runnable> scheduled = new ArrayList<>();

    /** The repeating refresh the source asked for, held rather than run, and how often it asked. */
    private final AtomicReference<Runnable> periodic = new AtomicReference<>();

    private final AtomicLong periodMillis = new AtomicLong();

    private final ScheduledExecutorService refreshes = mock(ScheduledExecutorService.class);

    private final RecordedFlagStateSource source =
            new RecordedFlagStateSource(reader, refreshes, clock);

    private ScheduledExecutorService offThread;

    /**
     * Holds everything handed to the executor instead of running any of it.
     *
     * <p>Both hand-overs are captured rather than executed, so what a case asserts is what the
     * source asked for and when it asked - a real executor would decide both for it, and a
     * schedule's own timing is the one thing a unit test cannot wait for.
     */
    @BeforeEach
    void holdWhatIsHandedOver() {
        doAnswer(handOver -> {
            scheduled.add(handOver.getArgument(0));
            return null;
        }).when(refreshes).execute(any());
        when(refreshes.scheduleAtFixedRate(any(), anyLong(), anyLong(), any()))
                .thenAnswer(handOver -> {
                    periodic.set(handOver.getArgument(0));
                    periodMillis.set(handOver.getArgument(2));
                    return null;
                });
    }

    @AfterEach
    void stopTheExecutor() {
        if (offThread != null) {
            offThread.shutdownNow();
        }
    }

    @Nested
    @DisplayName("the window a reading speaks for")
    class Window {

        @Test
        @DisplayName("a reading taken now stands for the flag it read")
        void a_reading_taken_now_should_stand_for_the_flag_it_read() {
            assertThat(new FlagStateSnapshot(FlagDecision.ON, READ_AT).stateFor(READ_AT))
                    .as("the flag was read, and it was on")
                    .isEqualTo(RecordedFlagState.ON);
            assertThat(new FlagStateSnapshot(FlagDecision.OFF, READ_AT).stateFor(READ_AT))
                    .as("the flag was read, and it was off")
                    .isEqualTo(RecordedFlagState.OFF);
        }

        /**
         * The last instant the reading is allowed to speak for, asserted rather than approached: an
         * inclusive window and an exclusive one differ by a millisecond, and only a case that lands
         * on the boundary can say which of the two this service implements.
         */
        @Test
        @DisplayName("a reading exactly at the edge of the window still stands for its flag")
        void a_reading_at_the_edge_of_the_window_should_still_stand_for_its_flag() {
            final Instant edge = READ_AT.plus(WINDOW);

            assertThat(new FlagStateSnapshot(FlagDecision.ON, READ_AT).stateFor(edge))
                    .as("a reading %s old is inside a window of %s", WINDOW, WINDOW)
                    .isEqualTo(RecordedFlagState.ON);
            assertThat(new FlagStateSnapshot(FlagDecision.OFF, READ_AT).stateFor(edge))
                    .as("the edge is the same edge whichever way the flag read")
                    .isEqualTo(RecordedFlagState.OFF);
        }

        @Test
        @DisplayName("a reading older than the window stands for nothing")
        void a_reading_older_than_the_window_should_stand_for_nothing() {
            final Instant past = READ_AT.plus(WINDOW).plus(A_MOMENT);

            assertThat(new FlagStateSnapshot(FlagDecision.ON, READ_AT).stateFor(past))
                    .as("a stale on is not a statement about the flag now")
                    .isEqualTo(RecordedFlagState.UNKNOWN);
            assertThat(new FlagStateSnapshot(FlagDecision.OFF, READ_AT).stateFor(past))
                    .as("a stale off is not one either, and is not OFF")
                    .isEqualTo(RecordedFlagState.UNKNOWN);
        }

        /**
         * Once per bounded cause, because a cause added later is a new way for a read to produce no
         * answer and must inherit the same label rather than be discovered stamping ON.
         */
        @ParameterizedTest(name = "{0}")
        @EnumSource(FlagDecision.UnreadableReason.class)
        @DisplayName("a reading that could not be taken stands for nothing")
        void an_unreadable_reading_should_stand_for_nothing(
                final FlagDecision.UnreadableReason reason) {
            final FlagStateSnapshot unreadable =
                    new FlagStateSnapshot(new FlagDecision.Unreadable(reason), READ_AT);

            assertThat(unreadable.stateFor(READ_AT))
                    .as("a read that failed is not a flag that is off")
                    .isEqualTo(RecordedFlagState.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("what an arriving command is labelled with")
    class Attachment {

        @Test
        @DisplayName("a command arriving before any read is labelled unknown")
        void a_command_arriving_before_any_read_should_be_labelled_unknown() {
            assertThat(source.current())
                    .as("nobody has read the flag, and that is exactly what UNKNOWN says")
                    .isEqualTo(RecordedFlagState.UNKNOWN);
        }

        @Test
        @DisplayName("a command arriving inside the window of a read saying on is labelled on")
        void a_command_arriving_inside_the_window_of_an_on_read_should_be_labelled_on() {
            aCompletedReadAnswering(FlagDecision.ON);

            assertThat(source.current())
                    .as("the flag has just been read and said this service generates")
                    .isEqualTo(RecordedFlagState.ON);
        }

        /**
         * The half that decides something: an OFF row is the one the nightly job leaves alone, and
         * it is left alone because the legacy was generating when the command arrived.
         */
        @Test
        @DisplayName("a command arriving inside the window of a read saying off is labelled off")
        void a_command_arriving_inside_the_window_of_an_off_read_should_be_labelled_off() {
            aCompletedReadAnswering(FlagDecision.OFF);

            assertThat(source.current())
                    .as("the legacy was generating, and the row has to say so")
                    .isEqualTo(RecordedFlagState.OFF);
        }

        @Test
        @DisplayName("a command arriving after the window has passed is labelled unknown")
        void a_command_arriving_after_the_window_should_be_labelled_unknown() {
            aCompletedReadAnswering(FlagDecision.ON);
            clock.advance(WINDOW.plus(A_MOMENT));

            assertThat(source.current())
                    .as("a reading this old cannot say what the lever is set to now")
                    .isEqualTo(RecordedFlagState.UNKNOWN);
        }

        @Test
        @DisplayName("a command arriving on an unreadable flag is labelled unknown")
        void a_command_arriving_on_an_unreadable_flag_should_be_labelled_unknown() {
            aCompletedReadAnswering(
                    new FlagDecision.Unreadable(FlagDecision.UnreadableReason.TIMED_OUT));

            assertThat(source.current())
                    .as("App Configuration did not answer, so nothing is known about the lever")
                    .isEqualTo(RecordedFlagState.UNKNOWN);
        }

        /**
         * The refresh a stale arrival schedules is for the commands behind it, and this is the case
         * that says the refresh is real: the reading is replaced, and by the answer the reader gave
         * this time rather than the one it gave a minute ago.
         */
        @Test
        @DisplayName("the refresh a stale arrival schedules replaces the reading")
        void a_stale_reading_should_be_replaced_by_the_refresh_the_arrival_schedules() {
            aCompletedReadAnswering(FlagDecision.ON);
            clock.advance(WINDOW.plus(A_MOMENT));
            when(reader.read()).thenReturn(FlagDecision.OFF);

            source.current();
            runTheScheduledRefreshes();

            assertThat(source.current())
                    .as("the cutover was rolled back, and the next command says so")
                    .isEqualTo(RecordedFlagState.OFF);
        }

        /**
         * The case the traffic makes ordinary rather than exceptional.
         *
         * <p>A stack takes about 160 commands a day, one every nine minutes on average, so a
         * reading refreshed only when a command finds it stale is stale for almost every command
         * there is: the arrival schedules a read that comes back in a second or two and labels
         * nobody, and the row it did label says {@code UNKNOWN}. {@code activeUnbatched()} excludes
         * every row that is not {@code ON}, so almost every register would sit outside automatic
         * batching and have to be found with {@code list-batches --recorded-while-off} - which is
         * FR-007 and US2 undone by a label.
         *
         * <p>So the reading is kept inside its window by a schedule instead of by the traffic:
         * while the consumer is running the source renews it at least once a window, off the
         * delivery thread as ever, and an arrival after any amount of quiet finds a reading that
         * still stands for something.
         */
        @Test
        @DisplayName("a command arriving after a quiet period is labelled from a fresh read")
        void a_command_arriving_after_a_quiet_period_should_be_labelled_from_a_fresh_read() {
            when(reader.read()).thenReturn(FlagDecision.ON);

            source.start();
            for (int window = 0; window < QUIET_WINDOWS; window++) {
                runThePeriodicRefresh();
                clock.advance(WINDOW.plus(A_MOMENT));
            }
            runThePeriodicRefresh();

            assertThat(source.current())
                    .as("nine minutes in which no command arrived, and the one that does is still "
                            + "labelled from a reading: UNKNOWN here keeps a register the flag was "
                            + "on for out of the nightly batch")
                    .isEqualTo(RecordedFlagState.ON);
            assertThat(periodMillis.get())
                    .as("and the interval is what makes that true - a reading is renewed at least "
                            + "once a window, so an arrival between two of them is inside one")
                    .isBetween(1L, WINDOW.toMillis());
        }
    }

    @Nested
    @DisplayName("what a recording never waits for")
    class NeverWaits {

        /**
         * The reader is not touched while a delivery is being labelled, and the executor is holding
         * the refresh that proves the labelling asked for one. A source that read the flag here
         * would put an App Configuration timeout inside every recording, and a store that had gone
         * away would stall the queue behind a column.
         */
        @Test
        @DisplayName("labelling a command never reaches the flag reader")
        void a_command_should_never_wait_on_a_flag_read() {
            when(reader.read()).thenReturn(FlagDecision.ON);

            final RecordedFlagState label = source.current();

            verifyNoInteractions(reader);
            assertThat(label)
                    .as("labelled from what is known now, not from a read that has not happened")
                    .isEqualTo(RecordedFlagState.UNKNOWN);
            assertThat(scheduled)
                    .as("the arrival still asked for a refresh; it just did not wait for it")
                    .isNotEmpty();
        }

        /**
         * The other half of the same rule, and the half a held executor cannot show: the read does
         * happen, and it happens somewhere else. The listener's thread belongs to one delivery and
         * to its single settlement.
         */
        @Test
        @DisplayName("the read a command schedules runs off the delivery thread")
        void a_scheduled_flag_read_should_run_off_the_delivery_thread() {
            final AtomicReference<String> readOn = new AtomicReference<>();
            when(reader.read()).thenAnswer(invocation -> {
                readOn.set(Thread.currentThread().getName());
                return FlagDecision.ON;
            });
            offThread = Executors.newSingleThreadScheduledExecutor();
            final RecordedFlagStateSource offThreadSource =
                    new RecordedFlagStateSource(reader, offThread, clock);

            offThreadSource.current();

            await().atMost(PATIENCE).until(() -> readOn.get() != null);
            verify(reader).read();
            assertThat(readOn.get())
                    .as("the flag was read, and not on the thread that holds the delivery")
                    .isNotEqualTo(Thread.currentThread().getName());
        }
    }

    @Nested
    @DisplayName("one refresh in flight, whoever asked for it")
    class OneRefreshInFlight {

        /** More arrivals than the executor has threads, which is the shape a busy minute has. */
        private static final int ARRIVALS = 8;

        /** How long an arrival may take while a read is in flight; a blocking one takes for ever. */
        private static final Duration ARRIVAL_PATIENCE = Duration.ofSeconds(2);

        /**
         * The renewal and the arrivals are one mechanism, and it admits one read at a time.
         *
         * <p>Research §12 states it as a pair: the flag is asked for twice a minute however busy
         * the queue is, and an arrival that finds no reading asks for one refresh and not one per
         * command. A periodic refresh that does not take the in-flight claim breaks the second
         * half - every arrival during a read that has not come back yet is free to hand over
         * another, and a minute's worth of commands becomes a minute's worth of reads of somebody
         * else's store, queued behind one thread.
         *
         * <p>The read is held open rather than timed: the reader blocks until this case lets it go,
         * so every arrival happens while a refresh is genuinely in flight and the count afterwards
         * is a fact rather than a race the suite hoped to win. The executor has one thread, exactly
         * as the deployed one does, so a second read that was handed over is not lost - it waits,
         * and is counted when the queue drains.
         */
        @Test
        @DisplayName("concurrent arrivals join the refresh already in flight rather than adding one")
        void concurrent_arrivals_should_share_one_flag_refresh() throws InterruptedException {
            final CountDownLatch reading = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final AtomicLong reads = new AtomicLong();
            offThread = Executors.newSingleThreadScheduledExecutor();
            final RecordedFlagStateSource source = new RecordedFlagStateSource(
                    heldOpen(reads, reading, release), offThread, clock);

            source.start();
            assertThat(reading.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS))
                    .as("the periodic refresh is the read this case's arrivals are behind")
                    .isTrue();
            final List<RecordedFlagState> observed = arrivalsDuringTheRead(source);
            release.countDown();
            offThread.shutdown();

            assertThat(offThread.awaitTermination(PATIENCE.toMillis(), TimeUnit.MILLISECONDS))
                    .as("everything handed to the executor has run, so nothing is still queued")
                    .isTrue();
            assertThat(reads.get())
                    .as("one refresh was in flight and every arrival joined it: a read per "
                            + "arrival is the load test of somebody else's store research 12 "
                            + "says this mechanism exists to prevent")
                    .isEqualTo(1);
            assertThat(observed)
                    .as("and none of them waited for it - they are labelled from what is known, "
                            + "which before the first read returns is UNKNOWN")
                    .hasSize(ARRIVALS)
                    .containsOnly(RecordedFlagState.UNKNOWN);
        }

        /**
         * A reader that blocks inside the read, counting how many times it was entered.
         *
         * @param reads   how many reads have been made
         * @param reading counted down as the first read begins
         * @param release held until this case lets the read finish
         * @return the reader
         */
        private FeatureFlagReader heldOpen(final AtomicLong reads, final CountDownLatch reading,
                final CountDownLatch release) {
            return () -> {
                reads.incrementAndGet();
                reading.countDown();
                try {
                    release.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
                return FlagDecision.ON;
            };
        }

        /**
         * Every arrival labelled while the read is in flight, each on its own thread.
         *
         * <p>They are released together and every one of them is required to answer well inside
         * the read it arrived during, which is the "never waits" half stated against a read that
         * really is in progress rather than against an executor holding one.
         *
         * @param source the source under test
         * @return what each arrival was labelled with
         */
        private List<RecordedFlagState> arrivalsDuringTheRead(
                final RecordedFlagStateSource source) {
            final CountDownLatch startLine = new CountDownLatch(1);
            final List<RecordedFlagState> observed = new ArrayList<>();
            try (ExecutorService arrivals = Executors.newFixedThreadPool(ARRIVALS)) {
                final List<Future<RecordedFlagState>> labels = new ArrayList<>();
                for (int arrival = 0; arrival < ARRIVALS; arrival++) {
                    labels.add(arrivals.submit(() -> {
                        startLine.await();
                        return source.current();
                    }));
                }
                startLine.countDown();
                labels.forEach(label -> observed.add(labelFrom(label)));
            }
            return List.copyOf(observed);
        }

        /** One arrival's label, insisting it did not wait on the read it arrived during. */
        private RecordedFlagState labelFrom(final Future<RecordedFlagState> label) {
            try {
                return label.get(ARRIVAL_PATIENCE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("the arrivals were interrupted", stopped);
            } catch (ExecutionException | TimeoutException waited) {
                throw new IllegalStateException(
                        "an arrival did not answer while a flag read was in flight", waited);
            }
        }
    }

    /**
     * A read that has already happened, which is the only way a reading ever gets in place: an
     * arrival with nothing known schedules one, and this stands in for the executor's thread.
     */
    private void aCompletedReadAnswering(final FlagDecision decision) {
        when(reader.read()).thenReturn(decision);
        source.current();
        runTheScheduledRefreshes();
    }

    /** Runs what the arrivals handed over, which is what the executor's thread would have done. */
    private void runTheScheduledRefreshes() {
        final List<Runnable> handedOver = List.copyOf(scheduled);
        scheduled.clear();
        handedOver.forEach(Runnable::run);
    }

    /**
     * Runs one tick of the repeating refresh, where the source asked for one.
     *
     * <p>Where it asked for none there is nothing to run and the case says so through the label it
     * gets, which is what makes the red run a comparison rather than a missing collaborator.
     */
    private void runThePeriodicRefresh() {
        Optional.ofNullable(periodic.get()).ifPresent(Runnable::run);
    }
}
