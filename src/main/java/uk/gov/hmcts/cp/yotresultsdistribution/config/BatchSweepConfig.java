package uk.gov.hmcts.cp.yotresultsdistribution.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.BatchAgeSweep;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterBatchRepository;

/**
 * The three in-flight batch readings' refresh, on a thread of its own.
 *
 * <p>Behind {@code yotresultsdistribution.generation.enabled}, and there for the reason
 * {@link IntakeSweepConfig} is <em>not</em>: these three gauges describe batches, and a pod that
 * assembles none has none to describe. Three flat zeroes published by a report-only pod would
 * compete, under the {@code max()} an alert aggregates them with, with the readings of the pod
 * that can actually hold a batch - which is the one shape in which adding a reading makes the
 * estate blinder rather than clearer.
 *
 * <p><strong>A scheduler of its own, single-threaded.</strong> The sweep runs on a fixed delay and
 * the run and the report do not, so sharing a thread with them would mean a ten-minute reading
 * queued behind an 18:00 run that is asking for renders - a reading taken an hour late, on exactly
 * the evening it is worth reading. Together with the {@code scheduler} attribute on
 * {@link BatchAgeSweep#sweepScheduled()}, that is what the separation is made of; four
 * {@code TaskScheduler} beans route nothing unless the method names one.
 *
 * <p><strong>Nothing here locks.</strong> A gauge describes the JVM that publishes it, so every
 * replica refreshes its own three and an alert aggregates them across pods with {@code max()} -
 * the oldest batch in flight is the oldest any pod can see. {@link BatchAgeSweep} says why at
 * length; what this configuration contributes is that there is no lock to declare here and none is
 * declared.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "yotresultsdistribution.generation", name = "enabled",
        havingValue = "true")
public class BatchSweepConfig {

    /**
     * The bean name of the scheduler the sweep runs on.
     *
     * <p>Published for the reason {@link IntakeSweepConfig#INTAKE_SWEEP_SCHEDULER} is: the
     * {@code scheduler} attribute on the scheduled method names a constant rather than a string
     * spelled twice, and a bean name spelled twice is a bean name that can be renamed once.
     */
    public static final String BATCH_SWEEP_SCHEDULER = "batchSweepScheduler";

    /** The one thread the refresh has, and the prefix its name is read by in a thread dump. */
    private static final String SWEEP_THREAD_PREFIX = "batch-age-sweep-";

    /**
     * The executor the batch-age refresh happens on, and nothing else does.
     *
     * @return a single-threaded scheduler named for the refresh it carries
     */
    @Bean(BATCH_SWEEP_SCHEDULER)
    public TaskScheduler batchSweepScheduler() {
        final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(SWEEP_THREAD_PREFIX);
        // A refresh in progress when the pod is asked to stop finishes: it is three cheap reads,
        // and half of it published is three readings that describe two different moments.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        return scheduler;
    }

    /**
     * The refresh itself, over the batches it reads and the instruments it publishes.
     *
     * @param batches the {@code register_batch} table, read for the oldest of each in-flight kind
     * @param metrics where the three gauges and the absorbed-failure counter live
     * @param clock   the clock the three ages are measured back from
     * @return the sweep the schedule fires
     */
    @Bean
    public BatchAgeSweep batchAgeSweep(final RegisterBatchRepository batches,
            final GenerationMetrics metrics, final Clock clock) {
        return new BatchAgeSweep(batches, metrics, clock);
    }
}
