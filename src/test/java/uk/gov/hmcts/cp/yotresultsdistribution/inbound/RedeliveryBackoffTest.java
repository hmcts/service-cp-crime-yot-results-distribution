package uk.gov.hmcts.cp.yotresultsdistribution.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The hold a delivery is served before it is handed back (production-readiness audit F-04).
 *
 * <p>Azure Service Bus offers an abandoned message again at once, so the queue's delivery budget is
 * spent at the speed of the run rather than the speed of the outage. The schedule is what buys the
 * downstream time to come back; the lock-renewal bound is what stops the hold costing the delivery
 * its lock, which would hand the message back by expiry and lose the settlement this service owes
 * it.
 */
class RedeliveryBackoffTest {

    private static final List<Duration> SCHEDULE = List.of(
            Duration.ofSeconds(15), Duration.ofSeconds(30),
            Duration.ofSeconds(60), Duration.ofSeconds(120));

    private static final Duration LOCK_RENEWAL = Duration.ofMinutes(5);

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final List<Duration> pauses = new CopyOnWriteArrayList<>();

    private final RedeliveryBackoff backoff = new RedeliveryBackoff(
            SCHEDULE, LOCK_RENEWAL, pauses::add, now::get);

    private void elapse(final Duration duration) {
        now.addAndGet(duration.toNanos());
    }

    @AfterEach
    void clearTheInterrupt() {
        // A case that interrupts this thread must not leave the flag for the next one to trip on.
        Thread.interrupted();
    }

    @Nested
    @DisplayName("the wait before a hand-back")
    class Schedule {

        @Test
        void a_first_delivery_should_wait_the_first_entry() {
            final long started = backoff.started();

            assertThat(backoff.delayFor(0, started)).isEqualTo(Duration.ofSeconds(15));
        }

        @Test
        void each_later_delivery_should_wait_its_own_entry() {
            final long started = backoff.started();

            assertThat(List.of(
                    backoff.delayFor(1, started),
                    backoff.delayFor(2, started),
                    backoff.delayFor(3, started)))
                    .containsExactly(Duration.ofSeconds(30), Duration.ofSeconds(60),
                            Duration.ofSeconds(120));
        }

        @Test
        void a_delivery_past_the_schedule_should_wait_the_last_entry() {
            final long started = backoff.started();

            assertThat(backoff.delayFor(9, started)).isEqualTo(Duration.ofSeconds(120));
        }

        @Test
        void a_count_below_zero_should_wait_the_first_entry() {
            final long started = backoff.started();

            assertThat(backoff.delayFor(-1, started)).isEqualTo(Duration.ofSeconds(15));
        }

        @Test
        void a_long_run_should_be_cut_to_what_the_lock_renewal_has_left() {
            final long started = backoff.started();
            elapse(Duration.ofMinutes(4));

            // Five minutes of renewal, four spent running, thirty seconds kept for the settlement.
            assertThat(backoff.delayFor(3, started)).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        void a_run_that_spent_the_renewal_should_not_wait_at_all() {
            final long started = backoff.started();
            elapse(Duration.ofMinutes(5));

            assertThat(backoff.delayFor(0, started)).isZero();
        }

        @Test
        void the_listener_with_no_backoff_should_never_wait() {
            final long started = RedeliveryBackoff.NONE.started();

            assertThat(RedeliveryBackoff.NONE.delayFor(0, started)).isZero();
        }
    }

    @Nested
    @DisplayName("serving the wait")
    class ServingTheHold {

        @Test
        void a_delay_should_pause_for_exactly_that_long() {
            final boolean served = backoff.hold(Duration.ofSeconds(30));

            assertThat(served).isTrue();
            assertThat(pauses).containsExactly(Duration.ofSeconds(30));
        }

        @Test
        void an_interrupted_wait_should_report_it_and_keep_the_interrupt() {
            final RedeliveryBackoff interrupted = new RedeliveryBackoff(SCHEDULE, LOCK_RENEWAL,
                    duration -> {
                        throw new InterruptedException();
                    }, now::get);

            final boolean served = interrupted.hold(Duration.ofSeconds(30));

            assertThat(served).isFalse();
            assertThat(Thread.currentThread().isInterrupted())
                    .as("a shutdown asked for by interrupt must still be seen by whoever asked")
                    .isTrue();
        }
    }
}
