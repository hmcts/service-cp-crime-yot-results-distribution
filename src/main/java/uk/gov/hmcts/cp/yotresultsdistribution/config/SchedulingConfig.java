package uk.gov.hmcts.cp.yotresultsdistribution.config;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterGenerationService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterStore;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.BatchAssembler;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.FeatureFlagGate;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.RegisterGenerationJob;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.StaleBatchReleaser;

/**
 * The nightly run's own executor, and the run itself.
 *
 * <p>What makes it one run rather than one run per replica is ShedLock, and what makes
 * {@code @Scheduled} mean anything at all is {@code @EnableScheduling} - both of which now live in
 * {@link SchedulingInfrastructureConfig}, because a pod that generates nothing still has a gauge
 * refresh to run and a report to write. What is left here is what is genuinely the downstream
 * half's: the thread the 18:00 run happens on, and the job that happens on it. The service deploys
 * with a single replica today, so the lock is insurance rather than a fix: relying on
 * {@code replicas: 1} is a deployment fact and not a code guarantee, and the cost of being wrong is
 * two documents and two e-mails for every court centre in the country.
 *
 * <p>The zone the schedule is read in is validated by {@link GenerationProperties#validate()},
 * because 18:00 is a wall-clock requirement that has to hold in BST and in GMT alike. The legacy
 * fires in the scheduling JVM's default zone, its Quartz trigger having been built without one, and
 * that ambiguity is not inherited: an override needs
 * {@code yotresultsdistribution.generation.zone-override-acknowledged}, and startup refuses without it.
 *
 * <p><strong>The run has an executor of its own.</strong> There are three schedulers on a fully
 * enabled context now - this one, the report's and the intake sweep's - and each scheduled method
 * names the one it belongs on, so the run still cannot land on a thread anything else is using. One
 * thread, because the run is sequential by design and a pool would only make it look otherwise. The
 * run is the only thing on this scheduler, and the generation half's only schedule at all: the pass
 * that gives stale batches back is the run's own first statement rather than a second timer
 * (FR-007).
 *
 * <p><strong>Only where the downstream half is deployed.</strong> The whole of this is conditional
 * on {@code yotresultsdistribution.generation.enabled}, so an intake-only pod holds no lock, keeps no
 * scheduler and fires nothing; the job itself is contributed only where the collaborators it asks in
 * order are on the context, for the reason {@link PublicEventsConfig} gives about the listener - a
 * schedule with nothing to run is a fire alarm nobody wired to anything.
 *
 * <p>The generation scheduler is also where {@code POST /operations/batches/generate} does its
 * work: {@code OperationsWebConfig} takes this scheduler by name, so a regeneration and a scheduled
 * run cannot interleave on one pod even before the 18:00 lock is considered.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "yotresultsdistribution.generation", name = "enabled", havingValue = "true")
public class SchedulingConfig {

    /**
     * The bean name of the scheduler the nightly run happens on.
     *
     * <p>The name of the bean {@link #registerGenerationScheduler()} already declares, published so
     * that {@code @Scheduled(scheduler = ...)} on {@code RegisterGenerationJob.run} names a constant
     * rather than a string spelled twice. One surface names it, and the routing stays explicit
     * anyway: a method naming no scheduler is routed to whichever of the three Spring resolves for
     * the context as a whole.
     */
    public static final String GENERATION_SCHEDULER = "registerGenerationScheduler";

    /** The one thread the run has, and the prefix its name is read by in a thread dump. */
    private static final String RUN_THREAD_PREFIX = "register-generation-";

    private static final Logger LOG = LoggerFactory.getLogger(SchedulingConfig.class);

    /**
     * The executor the nightly run happens on.
     *
     * <p>Named by {@link #GENERATION_SCHEDULER}, which the run's {@code @Scheduled} method carries:
     * with three schedulers on the context, a method that named none would be routed to whichever
     * one Spring resolved for the context as a whole.
     *
     * @return a single-threaded scheduler named for the run it carries
     */
    @Bean
    public TaskScheduler registerGenerationScheduler() {
        final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(RUN_THREAD_PREFIX);
        // A run that is still requesting when the pod is asked to stop finishes the batch it is on:
        // the file-service write and the POST that follows it are the pair that must not be halved.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        return scheduler;
    }

    /**
     * The run's first act: the batches the night before did not finish.
     *
     * <p>Declared beside the run rather than beside the rest of the downstream half, because this
     * configuration carries the two conditions the pass has to answer to and
     * {@link GenerationConfig} carries only one of them. The pass belongs to the scheduled run: it
     * is destructive - it fails a batch and re-renders a court centre's day - and it is safe only
     * inside the lock that makes the 18:00 run one run. An operations command holds no such lock,
     * and an operator regenerating one court centre must not, as a side effect, decide that
     * another court centre's in-flight batch has failed, so a JVM in CLI mode holds no pass at all
     * (the per-batch release the operations surface already offers is the supported way to free
     * one).
     *
     * <p>It takes the two durations it measures by rather than the settings record they are in: a
     * batch this service's schedule made is stale after {@code stale-after}, and one an operator
     * asked for is given the longer of that and the run's own lock duration, because a manual
     * generation has the whole requesting deadline to ask for its renders (FR-017).
     *
     * @param store      the register store, whose one fenced statement per batch does the deciding
     * @param metrics    where the released and contended counts are recorded
     * @param properties the settings the pass measures by, the minimum age above all
     * @param clock      this pod's reading of now, which both cutoffs are measured back from
     * @return the pass the nightly run calls first
     */
    @Bean
    public StaleBatchReleaser staleBatchReleaser(final RegisterStore store,
            final GenerationMetrics metrics, final GenerationProperties properties,
            final Clock clock) {

        return new StaleBatchReleaser(store, metrics, properties.staleAfter(),
                properties.lockAtMostFor(), clock);
    }

    /**
     * The nightly run, over the collaborators it asks in order.
     *
     * <p>Declared here rather than annotated as a component, and tolerant of a context that holds
     * only some of the downstream half: the generating adapters are contributed by their own
     * configurations and a deployment that is missing one has a run that could not ask for a render
     * anyway. Where that is so the schedule is left empty and the reason is said out loud, which is
     * the shape {@link PublicEventsConfig} uses for the same situation on the listener.
     *
     * @param gates       the one lever's gate, asked first by every run
     * @param stores      the register store, for the records a run may batch
     * @param assemblers  the grouping into one batch per court centre and register date
     * @param services    the requesting leg, asked once per batch
     * @param releasers   the pass the run calls first, before anything is read
     * @param metrics     the downstream half's instruments
     * @param properties  the settings the run works to
     * @param clock       this pod's reading of now
     * @param runProgress what the run tells readiness while it is in progress
     * @return the job, or {@code null} where this context could not generate anything
     */
    @Bean
    public RegisterGenerationJob registerGenerationJob(
            final ObjectProvider<FeatureFlagGate> gates,
            final ObjectProvider<RegisterStore> stores,
            final ObjectProvider<BatchAssembler> assemblers,
            final ObjectProvider<RegisterGenerationService> services,
            final ObjectProvider<StaleBatchReleaser> releasers,
            final GenerationMetrics metrics,
            final GenerationProperties properties,
            final Clock clock,
            final RunProgress runProgress) {

        final FeatureFlagGate gate = gates.getIfAvailable();
        final RegisterStore store = stores.getIfAvailable();
        final BatchAssembler assembler = assemblers.getIfAvailable();
        final RegisterGenerationService service = services.getIfAvailable();
        final StaleBatchReleaser releaser = releasers.getIfAvailable();

        final boolean complete = gate != null && store != null && assembler != null
                && service != null && releaser != null;
        if (!complete) {
            LOG.warn("The downstream half is enabled but incomplete on this context, so no "
                    + "generation run is scheduled: a job that could not read the flag, assemble a "
                    + "batch or ask for a render would report a quiet night rather than a missing "
                    + "one. gate={} store={} assembler={} service={} releaser={}", gate != null,
                    store != null, assembler != null, service != null, releaser != null);
        }
        return complete
                ? new RegisterGenerationJob(gate, store, assembler, service, releaser, metrics,
                        properties, clock, runProgress)
                : null;
    }
}
