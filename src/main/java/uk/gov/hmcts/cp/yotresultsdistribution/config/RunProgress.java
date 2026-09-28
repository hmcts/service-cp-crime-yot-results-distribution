package uk.gov.hmcts.cp.yotresultsdistribution.config;

/**
 * What a generation run tells the pod about itself while it is going on.
 *
 * <p>One fact, and it exists because readiness depends on it: the file-service datasource is used
 * for a few seconds a night and not at all for the rest of it, so
 * {@link FileServiceRunHealthIndicator} gates readiness on it only while a run is actually in
 * progress. The run is the only thing that knows when that is, and this is the whole of what it has
 * to say.
 *
 * <p>An interface rather than the indicator itself, so that the nightly job depends on the sentence
 * "a run has started" rather than on a health component: the job is not in the business of deciding
 * what a pod reports, and a job holding a {@code HealthIndicator} would read as though it were.
 */
public interface RunProgress {

    /**
     * A run that gates nothing, for a caller with no readiness to move.
     *
     * <p>Unit tests build runs; they have no health endpoint for a run to be visible in, and a run
     * that insisted on one would be a job that could not be exercised without a Spring context. A
     * deployed run is always given the real one by {@code SchedulingConfig}.
     */
    RunProgress NONE = new RunProgress() {

        @Override
        public void recordRunStarted() {
            // Nothing gates readiness here, so there is nothing to move.
        }

        @Override
        public void recordRunEnded() {
            // Nothing gates readiness here, so there is nothing to move.
        }
    };

    /**
     * Records that a generation run has started, from which point the file service gates readiness.
     */
    void recordRunStarted();

    /**
     * Records that a generation run has ended, however it ended.
     *
     * <p>However it ended is the point: a run that threw leaves the file service exactly as
     * uninteresting to readiness as one that completed, and a flag left set by a failure would gate
     * readiness on a database nothing is using until the pod restarts.
     */
    void recordRunEnded();
}
