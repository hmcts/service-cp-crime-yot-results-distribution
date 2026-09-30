package uk.gov.hmcts.cp.yotresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;

/**
 * The downstream half's instruments, in the context that actually runs.
 *
 * <p>{@link GenerationMetricsTest} pins what every instrument is called and what it is labelled
 * with, against a registry it builds itself. That is the whole of what a name is worth and none of
 * what it costs: a class declaring ten instruments that Spring never constructs declares nothing at
 * all, and the first thing to notice would be an alert rule that never fires because the series it
 * names has never existed. {@link ProcessingMetrics} is a component and the intake half's meters
 * are therefore on the running registry; this suite is the same question asked of
 * {@link GenerationMetrics}.
 *
 * <p>Two instruments are asserted rather than ten, and they are the two shapes: a gauge, which
 * exists from construction because a nightly flow is read between runs as much as during one, and a
 * counter, which comes into existence the first time it is incremented and so has to be incremented
 * here. Between them they say that the bean was built and that it was built against the context's
 * own registry, which is the whole of what this suite can add to the naming pins.
 *
 * <p>The generation half is switched on, because that is the deployment the instruments describe.
 * They are not conditional on it - a dashboard must read them from a pod that has not run, and a
 * meter that appears only once a flag is set is not an alerting surface - so the case would pass
 * with the switch off too; it is set because a reader asking "are the batch instruments there on a
 * generating pod" should be able to see the answer rather than infer it. The four downstream modes
 * go LIVE with it, because startup refuses a stand-in on a deployment that means to produce
 * registers tonight - which is the refusal {@code ConfigurationValidationTest} pins.
 */
@SpringBootTest(properties = {
    "yotresultsdistribution.generation.enabled=true",
    "yotresultsdistribution.generation.sdg-mode=LIVE",
    "yotresultsdistribution.generation.nn-mode=LIVE",
    "yotresultsdistribution.generation.fileservice-mode=LIVE",
    "yotresultsdistribution.generation.flag-mode=LIVE",
    "yotresultsdistribution.fileservice.url=jdbc:postgresql://localhost:5432/fileservice",
    "yotresultsdistribution.feature.connection-string=Endpoint=https://appconfig.internal;Id=ste-id;Secret=c3RlLXNlY3JldA==",
    "yotresultsdistribution.feature.label=ste86",
    "yotresultsdistribution.endpoints.systemdocgenerator=http://systemdocgenerator.internal:8080",
    "yotresultsdistribution.endpoints.notificationnotify=http://notificationnotify.internal:8080",
    "yotresultsdistribution.endpoints.system-user-id=00000000-0000-4000-8000-000000000000",
    "yotresultsdistribution.email.templates.cr_standard=5c9a0e21-3d47-4f18-9b62-0a71c4e8d530",
    "spring.artemis.broker-url=tcp://artemis.internal:61616"})
@ActiveProfiles("test") // context-load only: no broker, no database, no Docker
@DisplayName("the generation instruments in the running context")
class GenerationMetricsContextTest {

    private final ApplicationContext context;
    private final MeterRegistry registry;

    @Autowired
    GenerationMetricsContextTest(final ApplicationContext context, final MeterRegistry registry) {
        this.context = context;
        this.registry = registry;
    }

    @Test
    @DisplayName("are declared by a bean the context actually holds")
    void the_generation_instruments_should_be_declared_by_a_bean_the_context_holds() {
        assertThat(context.getBeanNamesForType(GenerationMetrics.class))
                .as("a metrics class nothing constructs declares no metrics; ProcessingMetrics is "
                        + "a component for this reason and so is this one")
                .isNotEmpty();
    }

    @Test
    @DisplayName("put the flag gauge on the registry the service exports from")
    void the_flag_gauge_should_be_registered_before_anything_has_read_the_flag() {
        assertThat(registry.find(GenerationMetrics.FLAG_READ_OK).gauge())
                .as("the seven gauges are registered from construction, so a pod that has not run "
                        + "tonight still answers the question the dashboard asks of it")
                .isNotNull();
    }

    @Test
    @DisplayName("put the batches counter on it too, once a batch has ended")
    void the_batches_counter_should_reach_the_registry_the_service_exports_from() {
        context.getBean(GenerationMetrics.class).batchCompleted(BatchStatus.NOTIFIED);

        assertThat(registry.find(GenerationMetrics.BATCHES).counters())
                .as("a counter comes into existence when it is first incremented, and it has to "
                        + "come into existence on the context's own registry rather than on one "
                        + "the class was handed by a test")
                .extracting(counter -> counter.getId().getTag(GenerationMetrics.OUTCOME_TAG))
                .contains("notified");
    }

    @Test
    @DisplayName("sit beside the intake half's, on the same registry")
    void the_intake_instruments_should_be_on_the_same_registry() {
        assertThat(registry.find(ProcessingMetrics.SERVICEBUS_UP).gauge())
                .as("the two halves are one service and export from one registry; this is the "
                        + "comparison that says what the generation half was missing")
                .isNotNull();
        assertThat(registry.getMeters())
                .extracting(Meter::getId)
                .isNotEmpty();
    }
}
