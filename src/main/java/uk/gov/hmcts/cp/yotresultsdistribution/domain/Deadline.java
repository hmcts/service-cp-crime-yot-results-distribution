package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * How long a run may go on asking for renders.
 *
 * <p>An instant rather than a duration, because a bound that is re-derived at each step is a bound
 * that grows by whatever the step before it took. The run computes it once, at the moment it starts
 * requesting, and every batch after that is measured against the same moment.
 *
 * <p><strong>It bounds requesting and nothing else.</strong> A document arrives on the public-event
 * topic long after the run that asked for it has ended, so a deadline that covered completion would
 * be a deadline that failed batches for being rendered slowly. What it protects is the claim the run
 * holds and the schedule behind it: a run still asking at midnight is a run that would collide with
 * the next one.
 *
 * @param expiresAt the instant after which no further render may be requested
 */
public record Deadline(Instant expiresAt) {

    /**
     * The deadline a run starting now works to.
     *
     * @param start  when the requesting half began
     * @param budget how long it may go on for, from {@code yotresultsdistribution.generation.run-deadline}
     * @return the instant the budget runs out at
     */
    public static Deadline startingAt(final Instant start, final Duration budget) {
        return new Deadline(start.plus(budget));
    }

    /**
     * Whether the budget is spent.
     *
     * @param now the instant being judged, from the run's own clock
     * @return true where nothing further may be requested
     */
    public boolean hasPassedAt(final Instant now) {
        return !now.isBefore(expiresAt);
    }
}
