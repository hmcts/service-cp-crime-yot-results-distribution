package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.util.UUID;

/**
 * What the stale-batch release tells its caller as each batch is settled, rather than at the end.
 *
 * <p>The twin of {@link RenderProgress}, and it exists for the same reason: an operation that only
 * answers once it has finished tells a caller nothing about the part of it that did happen. Each
 * stale batch is failed and released in a transaction of its own and is <strong>durably
 * committed</strong> before the next one is attempted, so a store that goes away partway through
 * leaves a night in which some court centres' registers really were given back. Announced only in
 * the return value, that account is lost with the throw: no line about those batches, no counter
 * moved, and a run report that says the pass released nothing on a night when it released some.
 *
 * <p><strong>So each batch is announced where it is settled</strong> - after its own transaction
 * has committed, and before the walk goes on to the batch after it. A caller that keeps an account
 * therefore has one whether the walk finished or not, and the numbers it publishes are of writes
 * that are already in the store rather than of writes it hopes will be.
 *
 * <p>An interface rather than the pass itself, so the store depends on the sentence "this batch has
 * been given back" and not on the run's accounting, which is a report's business.
 */
public interface StaleReleaseProgress {

    /**
     * A caller that keeps no account, for one with no report to make of the pass.
     *
     * <p>The operations commands and the suites that ask the store about one day: neither writes a
     * run report, and an operation that insisted on somebody to tell would make those callers
     * invent an accounting they have no use for.
     */
    StaleReleaseProgress NONE = new StaleReleaseProgress() {

        @Override
        public void recordReleased(final ReleasedBatch released) {
            // Nothing here keeps an account of the pass, so there is nothing to record.
        }

        @Override
        public void recordContended(final UUID batchId) {
            // Nothing here keeps an account of the pass, so there is nothing to record.
        }
    };

    /**
     * Records that one batch has been failed and its registers given back, and is committed.
     *
     * @param released the batch, the court centre day it held and how many registers came back
     */
    void recordReleased(ReleasedBatch released);

    /**
     * Records that one batch was left exactly as it was found, every attempt at it having lost the
     * race for its day's active register.
     *
     * @param batchId the batch nothing could be given back from
     */
    void recordContended(UUID batchId);
}
