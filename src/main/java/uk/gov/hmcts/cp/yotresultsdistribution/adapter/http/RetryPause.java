package uk.gov.hmcts.cp.yotresultsdistribution.adapter.http;

import java.time.Duration;

/**
 * The wait between two attempts at the same outbound call.
 *
 * <p>An interface rather than a call to {@link Thread#sleep(Duration)} for one reason: the back-off
 * policy is behaviour worth asserting, and a suite that proved a two-second {@code Retry-After} was
 * honoured by taking two seconds to run would be paid for in every build thereafter. The production
 * implementation is a method reference; the suites record what would have been waited.
 *
 * <p>One interface for all four clients (the C3 trio and the notification client), because there is
 * one policy. A per-client waiting abstraction would be the same shape four times over and would
 * let four suites disagree about what a wait is.
 */
@FunctionalInterface
public interface RetryPause {

    /**
     * Waits for the given duration.
     *
     * @param duration how long to wait
     * @throws InterruptedException if the waiting thread is interrupted; the caller restores the
     *                              interrupt and gives up the attempt rather than swallowing it
     */
    void pause(Duration duration) throws InterruptedException;
}
