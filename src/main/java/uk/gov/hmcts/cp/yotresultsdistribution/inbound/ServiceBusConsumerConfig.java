package uk.gov.hmcts.cp.yotresultsdistribution.inbound;

import com.azure.core.amqp.AmqpRetryMode;
import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusErrorContext;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.cp.yotresultsdistribution.application.DistributionPipeline;
import uk.gov.hmcts.cp.yotresultsdistribution.application.FeatureFlagReader;
import uk.gov.hmcts.cp.yotresultsdistribution.config.ProcessingMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.config.ServiceBusHealthIndicator;
import uk.gov.hmcts.cp.yotresultsdistribution.config.YotResultsDistributionProperties;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.ProcessedLogProbe;

/**
 * The consumer, built entirely from the typed settings.
 *
 * <p>Nothing about the broker is decided here: the queue, the concurrency, the lock-renewal window
 * and the credential all come from {@code yotresultsdistribution.servicebus.*}, which startup validation has
 * already refused to let through in an unsafe combination. Peek-lock with auto-complete disabled is
 * the one setting that is not configurable — a message completed by the container rather than by the
 * code that recorded its outcome is the failure mode this service exists to remove (constitution
 * Principle VI).
 *
 * <p><strong>Who starts the processor.</strong> Building the client and starting it are separate
 * beans on purpose, and nothing here starts it. {@link ConsumerLifecycleController} does, once the
 * processed log has answered a probe and the deferred migration has run — because a service that
 * consumes without a store abandons a queue's worth of deliveries, and one that consumes against an
 * unmigrated schema loses them.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "yotresultsdistribution.consumer", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ServiceBusConsumerConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceBusConsumerConfig.class);

    /**
     * The reconnection budget, fixed here rather than inherited.
     *
     * <p>The service is given sixty seconds from the queue returning to be consuming again. The
     * SDK's defaults are chosen for a client that can afford to wait; this one cannot, because every
     * second past the budget is a resulted hearing whose register is not being built. Exponential
     * back-off from half a second, capped at ten, with five attempts and a thirty-second try
     * timeout, brings a reconnection comfortably inside the budget while still backing off enough
     * not to hammer a broker that is genuinely down.
     */
    private static final AmqpRetryOptions RECONNECTION = new AmqpRetryOptions()
            .setMode(AmqpRetryMode.EXPONENTIAL)
            .setMaxRetries(5)
            .setDelay(Duration.ofMillis(500))
            .setMaxDelay(Duration.ofSeconds(10))
            .setTryTimeout(Duration.ofSeconds(30));

    /**
     * The broker's own health component — deliberately not in the readiness group.
     *
     * <p>Named so that Spring's contributor naming yields {@code servicebus}: the component name is
     * what a probe, a dashboard and a runbook all refer to, so it is chosen here rather than
     * inherited from a class name somebody may later rename.
     *
     * @param properties the typed settings, for the staleness window
     * @param metrics    the instrument surface the broker gauge lives on
     * @param clock      the clock every age in the reachability rule is measured against
     * @return the queue-health component
     */
    @Bean
    public ServiceBusHealthIndicator servicebusHealthIndicator(
            final YotResultsDistributionProperties properties,
            final ProcessingMetrics metrics,
            final Clock clock) {
        return new ServiceBusHealthIndicator(
                properties.servicebus().healthStaleness(), metrics, clock);
    }

    /**
     * Where a flag refresh is run, so that no delivery thread is ever inside an App Configuration
     * read.
     *
     * <p>One thread, because that is all {@link RecordedFlagStateSource} can ever ask for: a
     * repeating refresh that keeps the reading inside its window, and at most one on-demand refresh
     * in flight behind it. One thread also means the two can never run at once, so a reading is
     * replaced by one read or the other and never by half of each. The rejection policy is the
     * default refusal rather than caller-runs, since caller-runs is exactly the delivery thread
     * doing the read this whole arrangement exists to keep it out of; a refusal is reported and the
     * next arrival asks again. The thread is a daemon and is created when the first refresh is
     * handed over, so a pod that reads no flag never starts one.
     *
     * <p>It is a <em>scheduled</em> executor because the flag has to be read on a clock rather than
     * on the traffic: a stack takes a command every few minutes, and a reading refreshed only when
     * an arrival finds it stale is stale for almost every arrival there is.
     *
     * @return the executor
     */
    @Bean
    public ScheduledExecutorService recordedFlagStateRefreshes() {
        return new ScheduledThreadPoolExecutor(1, refresh -> {
            final Thread thread = new Thread(refresh, "yot-results-distribution-flag-refresh");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * The inbound adapter: parse, label, dispatch to the pipeline, settle exactly once.
     *
     * <p><strong>What labels an arriving command is decided here, and it depends on whether this
     * deployment reads the flag at all.</strong> {@code LiveFeatureFlagConfig} contributes a reader
     * only where {@code yotresultsdistribution.generation.enabled} is true, so a pod that records but does
     * not generate - the intake-only shape, and every local run - has no reader to label from and
     * labels every command {@code UNKNOWN}. That is the honest answer rather than a gap: the flag
     * says which implementation is generating, a pod that does not generate has read nothing about
     * it, and {@code UNKNOWN} keeps those rows out of automatic batching until a person decides
     * what became of them ({@code list-batches --recorded-while-off}, research §12). Where a reader
     * is present the source is built over it, and the rows say ON or OFF.
     *
     * @param parser     reads a body into the validated command
     * @param pipeline   the use case every valid request is run through
     * @param metrics    the instrument surface settlements are counted on
     * @param health     where a refused or accepted settlement is reported as transport news
     * @param lifecycle  the store gate, resolved late — see {@link DeferredStoreGate}
     * @param properties the typed settings, for the queue's delivery budget
     * @param flagReader the reader of the one lever, where this deployment has one
     * @param refreshes  where a flag refresh is run, which is never a delivery thread
     * @param clock      what the age of a flag reading is measured against
     * @return the listener
     */
    @Bean
    public YotResultsDistributionMessageListener courtRegisterMessageListener(
            final DistributionCommandParser parser,
            final DistributionPipeline pipeline,
            final ProcessingMetrics metrics,
            final ServiceBusHealthIndicator health,
            final ObjectProvider<ConsumerLifecycleController> lifecycle,
            final YotResultsDistributionProperties properties,
            final ObjectProvider<FeatureFlagReader> flagReader,
            final ScheduledExecutorService refreshes,
            final Clock clock) {
        // The delivery budget is the queue's, mirrored in configuration: the listener recognises the
        // final permitted delivery from it, so the two are changed together or this service is wrong
        // about the broker.
        return new YotResultsDistributionMessageListener(
                parser, pipeline, metrics, health, new DeferredStoreGate(lifecycle::getObject),
                properties.servicebus().maxDeliveryCount(),
                flagStates(flagReader.getIfAvailable(), refreshes, clock));
    }

    /**
     * The label source, where there is a reader to build it over, started as it is built.
     *
     * <p>Started here rather than on the first arrival that finds no reading: the whole point of
     * the schedule is that a command arriving on a quiet stack finds a reading already taken, and a
     * source that waited to be asked would label that command from nothing.
     *
     * @param reader    the reader of the one lever, or {@code null} where this deployment has none
     * @param refreshes where a read is run
     * @param clock     what the age of a reading is measured against
     * @return the source, or {@code null} where every command is labelled UNKNOWN
     */
    private static RecordedFlagStateSource flagStates(
            final FeatureFlagReader reader, final ScheduledExecutorService refreshes,
            final Clock clock) {
        final RecordedFlagStateSource source =
                reader == null ? null : new RecordedFlagStateSource(reader, refreshes, clock);
        if (source != null) {
            source.start();
        }
        return source;
    }

    /**
     * The store gate, resolved when it is used rather than when the listener is built.
     *
     * <p>There is a genuine cycle in the object graph and it is not an accident of wiring: the
     * controller owns the processor, the processor is built around the listener, and the listener
     * has to be able to ask the controller to stop intake. Something has to be late, and the gate is
     * the honest place — it is only ever needed while a delivery is being handled, by which time
     * every bean in the cycle exists.
     */
    private record DeferredStoreGate(Supplier<ConsumerLifecycleController> lifecycle)
            implements StoreGate {

        @Override
        public boolean storeAvailable() {
            return lifecycle.get().storeAvailable();
        }

        @Override
        public void suspendIntake() {
            lifecycle.get().suspendIntake();
        }
    }

    /**
     * The processor client, built but deliberately not started.
     *
     * @param properties the typed settings the whole client is derived from
     * @param listener   the adapter each delivery is handed to
     * @param health     where transport news about the connection is reported
     * @return the processor client
     */
    @Bean(destroyMethod = "close")
    public ServiceBusProcessorClient courtRegisterProcessorClient(
            final YotResultsDistributionProperties properties,
            final YotResultsDistributionMessageListener listener,
            final ServiceBusHealthIndicator health) {
        final YotResultsDistributionProperties.Servicebus settings = properties.servicebus();
        LOG.info("Building the Service Bus consumer. queue={} maxConcurrentCalls={} "
                        + "maxAutoLockRenewDuration={} credential={}",
                settings.queueName(), settings.maxConcurrentCalls(),
                settings.maxAutoLockRenewDuration(), credentialSource(settings));
        return credentialledBuilder(settings)
                .retryOptions(RECONNECTION)
                .processor()
                .queueName(settings.queueName())
                .maxConcurrentCalls(settings.maxConcurrentCalls())
                .maxAutoLockRenewDuration(settings.maxAutoLockRenewDuration())
                .disableAutoComplete()
                .processMessage(delivery -> handle(delivery, listener, health))
                .processError(error -> reportProcessorError(error, health))
                .buildProcessorClient();
    }

    /**
     * One delivery, and the transport fact it carries.
     *
     * <p>The arrival is recorded here rather than inside the listener because it is a statement
     * about the connection, not about the request: the broker handed us a message, so the broker is
     * reachable, whatever this particular message turns out to be worth. Recording it at the same
     * boundary as {@code processError} keeps the health component's inputs beside each other and
     * leaves the listener to settlement, which is its whole job.
     */
    private static void handle(
            final ServiceBusReceivedMessageContext delivery,
            final YotResultsDistributionMessageListener listener,
            final ServiceBusHealthIndicator health) {
        health.recordTraffic();
        listener.onMessage(delivery);
    }

    /**
     * The one component permitted to start or stop intake.
     *
     * @param processor      the client it owns
     * @param storeProbe     the availability question the gated start waits on
     * @param flyway         the deferred migration, run before intake starts
     * @param metrics        the instrument surface suspensions are counted on
     * @param health         told when intake actually starts
     * @param properties     the typed settings, for the probe interval
     * @return the lifecycle controller
     */
    @Bean
    public ConsumerLifecycleController courtRegisterConsumerLifecycle(
            final ServiceBusProcessorClient processor,
            final ProcessedLogProbe storeProbe,
            final ObjectProvider<Flyway> flyway,
            final ProcessingMetrics metrics,
            final ServiceBusHealthIndicator health,
            final YotResultsDistributionProperties properties) {
        return new ConsumerLifecycleController(
                processor, storeProbe, flyway::getIfAvailable, metrics, health,
                properties.store().probeInterval());
    }

    /**
     * A selection, not a preference.
     *
     * <p>Exactly one of the connection string and the namespace is set — startup validation refuses
     * both and refuses neither — so there is no ordering here that could quietly send a deployed pod
     * to the wrong broker.
     */
    private static ServiceBusClientBuilder credentialledBuilder(
            final YotResultsDistributionProperties.Servicebus settings) {
        final ServiceBusClientBuilder builder = new ServiceBusClientBuilder();
        if (hasText(settings.connectionString())) {
            builder.connectionString(settings.connectionString());
        } else {
            builder.fullyQualifiedNamespace(settings.namespace())
                    .credential(new DefaultAzureCredentialBuilder().build());
        }
        return builder;
    }

    /** Which source was chosen, for the startup log. Never the credential itself. */
    private static String credentialSource(final YotResultsDistributionProperties.Servicebus settings) {
        return hasText(settings.connectionString()) ? "connection-string" : "workload-identity";
    }

    /**
     * Errors the processor reports outside a delivery — connection and link failures, chiefly.
     *
     * <p>There is no delivery to settle here, so the fault is reported and handed to the health
     * component, which decides whether it means the queue is unreachable. The exception itself is
     * carried into the log because these are transport faults rather than message content: nothing a
     * producer wrote and nothing about a defendant can appear in one, and a connection failure with
     * no diagnostics is the one nobody ever explains.
     */
    private static void reportProcessorError(
            final ServiceBusErrorContext error, final ServiceBusHealthIndicator health) {
        health.recordProcessorError(
                String.valueOf(error.getErrorSource()), error.getEntityPath(),
                error.getException());
    }

    private static boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }
}
