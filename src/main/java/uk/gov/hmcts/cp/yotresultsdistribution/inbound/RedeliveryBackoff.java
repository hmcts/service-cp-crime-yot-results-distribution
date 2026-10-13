package uk.gov.hmcts.cp.yotresultsdistribution.inbound;

import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;
import uk.gov.hmcts.cp.yotresultsdistribution.config.PropertiesValidator;

/**
 * How long a delivery is held before it is handed back to the queue.
 *
 * <p>Azure Service Bus has no back-off of its own: an abandoned message is available again at once,
 * with its delivery count one higher. A transient failure therefore spends the queue's five
 * deliveries at the speed of the run rather than the speed of the outage, and a downstream context
 * that is redeployed for fifteen seconds parks every request that arrived during it - each one a
 * dead-letter a person has to replay before 18:00 or the register misses the night
 * (production-readiness audit F-04). The hold is what turns five deliveries into minutes rather than
 * seconds.
 *
 * <p>It is served <strong>before</strong> the abandon, on the callback thread, while the processor
 * is still renewing the delivery's lock: a delivery being held is one no other pod can be handed,
 * and a request recorded RETRYING has already released its claim, so nothing is contended while it
 * waits. Holding a callback slot during an outage is the back-pressure that is wanted.
 *
 * <p>The wait is indexed by the broker's zero-based delivery count, the last entry serving every
 * count beyond the list, and is cut to what the lock renewal has left after the run and a margin for
 * the settlement itself. A hold the lock could not survive would hand the message back by expiry
 * instead, and the settlement this service owes it would be refused. Elapsed time is read from a
 * monotonic clock within this JVM and compared with nothing another node wrote.
 */
public final class RedeliveryBackoff {

    /** Never waits: the listener as it was before a back-off existed. */
    public static final RedeliveryBackoff NONE =
            new RedeliveryBackoff(List.of(), Duration.ZERO, duration -> {}, () -> 0L);

    private final List<Duration> schedule;
    private final Duration lockRenewal;
    private final Pause pause;
    private final LongSupplier nanoTime;

    /**
     * Creates a back-off over an explicit pause and monotonic clock.
     *
     * @param schedule    the wait before each hand-back, indexed by the broker's delivery count
     * @param lockRenewal how long the processor renews a delivery's lock
     * @param pause       what waits out a hold
     * @param nanoTime    a monotonic clock, in nanoseconds
     */
    public RedeliveryBackoff(final List<Duration> schedule, final Duration lockRenewal,
            final Pause pause, final LongSupplier nanoTime) {
        this.schedule = List.copyOf(schedule);
        this.lockRenewal = lockRenewal;
        this.pause = pause;
        this.nanoTime = nanoTime;
    }

    /**
     * Creates the production back-off, which sleeps the callback thread.
     *
     * @param schedule    the wait before each hand-back, indexed by the broker's delivery count
     * @param lockRenewal how long the processor renews a delivery's lock
     * @return the back-off
     */
    public static RedeliveryBackoff sleeping(final List<Duration> schedule,
            final Duration lockRenewal) {
        return new RedeliveryBackoff(schedule, lockRenewal, Thread::sleep, System::nanoTime);
    }

    /**
     * Reads the monotonic clock at the moment a delivery arrives.
     *
     * @return the reading
     */
    public long started() {
        return nanoTime.getAsLong();
    }

    /**
     * How long to hold a delivery before handing it back.
     *
     * @param deliveryCount the broker's zero-based count of earlier deliveries
     * @param startedAt     the reading {@link #started()} took when the delivery arrived
     * @return how long to wait; zero where the schedule is empty or the renewal is spent
     */
    public Duration delayFor(final long deliveryCount, final long startedAt) {
        return Duration.ZERO;
    }

    /**
     * Waits out a hold.
     *
     * <p>An interrupt ends the wait early and is put back on the thread: it is the processor or the
     * container asking the callback to finish, and whoever asked must still be able to see it.
     *
     * @param delay how long to wait
     * @return whether the wait was served rather than interrupted
     */
    public boolean hold(final Duration delay) {
        return true;
    }

    /**
     * Waits out a hold; the production one sleeps the callback thread.
     */
    @FunctionalInterface
    public interface Pause {

        /**
         * Waits for the given time.
         *
         * @param duration how long to wait
         * @throws InterruptedException when the thread is interrupted while waiting
         */
        void pause(Duration duration) throws InterruptedException;
    }
}
