package uk.gov.hmcts.cp.yotresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.jms.ConnectionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.artemis.autoconfigure.ArtemisAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.boot.jms.autoconfigure.JmsAutoConfiguration;
import org.springframework.boot.jms.autoconfigure.JmsProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

/**
 * Which broker the {@code public.event} subscription is actually opened against.
 *
 * <p>{@code PublicEventsConfigTest} asks the factory what shape of subscription it builds by
 * calling the {@code @Bean} method with a factory of its own. That question cannot see this one:
 * the shape is right in both cases, and what differs is <strong>which connection factory Spring
 * hands in</strong> - which is a property of the container, not of the method.
 *
 * <p>It matters because {@code cp-audit-filter-springboot} contributes an
 * {@code auditConnectionFactory} and an {@code auditJmsTemplate} and marks <strong>both
 * {@code @Primary}</strong>. {@code PublicEventsConfig} asked for a {@code ConnectionFactory} by
 * type, and by-type injection with a {@code @Primary} candidate present resolves to the
 * {@code @Primary} one. The failure that would cause has no symptom worth the name: the pod comes
 * up, the container reports itself started, the subscription is opened on the estate's <em>audit</em>
 * broker where no {@code document-available} event is ever published, and every batch waits for an
 * outcome that was delivered to nobody. Nothing logs, nothing is counted, and the first sign is a
 * court centre asking where its register went.
 *
 * <p>So the assertion is by <strong>identity</strong> against the two factories the context holds,
 * and it is made on a container the committed configuration built rather than on a bean definition:
 * {@code DefaultJmsListenerContainerFactory} has setters and no getters, so the only honest way to
 * ask which factory it was given is to build the container it would build and read the container.
 *
 * <p>Both auto-configurations are the real ones. A hand-written stand-in for either would be a test
 * asserting against its own fixture - and the {@code @Primary} that causes the fault lives in the
 * library, not in anything this repository could fake faithfully.
 */
@DisplayName("the broker the public-event subscription is opened against")
class PublicEventsFactoryTest {

    /** The estate's multicast address, named only so the endpoint has somewhere to point. */
    private static final String TOPIC = "public.event";

    /** The name that, on a shared durable subscription, is the whole of its identity. */
    private static final String SUBSCRIPTION = "yotresultsdistribution-service.sdg";

    /** The audit starter's bean name, and the bean it marks {@code @Primary}. */
    private static final String AUDIT_CONNECTION_FACTORY = "auditConnectionFactory";

    /**
     * Boot's own, under the name both of its Artemis configurations register it with.
     *
     * <p>The <em>type</em> behind that name is a deployment's choice - a
     * {@code CachingConnectionFactory} while {@code spring.jms.cache.enabled} is true, the raw
     * {@code ActiveMQConnectionFactory} when it is not - so the name is the only stable way to
     * name it, and asking for it by type is how a test ends up holding the audit one and saying
     * nothing.
     */
    private static final String BOOT_CONNECTION_FACTORY = "jmsConnectionFactory";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ArtemisAutoConfiguration.class,
                    JmsAutoConfiguration.class,
                    uk.gov.hmcts.cp.filter.audit.config.ArtemisAuditAutoConfiguration.class))
            .withUserConfiguration(PublicEventsTestConfiguration.class)
            .withPropertyValues(
                    // The downstream half, which is the only deployment that holds the subscription.
                    "yotresultsdistribution.generation.enabled=true",
                    "yotresultsdistribution.generation.completion=event",
                    // The estate's public-event broker: never connected to here, only resolved.
                    "spring.artemis.broker-url=tcp://public-events.invalid:61616",
                    "spring.artemis.embedded.enabled=false",
                    "spring.jms.pub-sub-domain=true",
                    "spring.jms.subscription-durable=true",
                    // The audit transport, which is a DIFFERENT broker - that being the whole point.
                    "cp.audit.enabled=true",
                    "cp.audit.hosts=artemis-audit.invalid",
                    "cp.audit.port=61616");

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({GenerationProperties.class, JmsProperties.class})
    @Import(PublicEventsConfig.class)
    static class PublicEventsTestConfiguration {

        /** The instruments the listener counts ignored events on; no registry of its own is needed. */
        @Bean
        GenerationMetrics generationMetrics() {
            return new GenerationMetrics(new SimpleMeterRegistry());
        }
    }

    @Test
    @DisplayName("holds both brokers' connection factories, and they are not the same object")
    void the_context_should_hold_the_audit_factory_beside_the_public_event_one() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(context.containsBean(AUDIT_CONNECTION_FACTORY))
                    .as("the premise of every case below: with the audit starter switched on it "
                            + "contributes a connection factory of its own, and marks it @Primary")
                    .isTrue();
            assertThat(context.containsBean(BOOT_CONNECTION_FACTORY))
                    .as("and Boot's Artemis auto-configuration still contributes one too. It is "
                            + "@ConditionalOnMissingBean(ConnectionFactory), so an ordering in "
                            + "which the audit library got there first would leave the estate's "
                            + "public-event broker with no factory at all - and this service "
                            + "reading a topic that does not carry its events")
                    .isTrue();
            assertThat(bootProvided(context))
                    .as("and they are two objects, pointed at two brokers")
                    .isNotSameAs(context.getBean(AUDIT_CONNECTION_FACTORY));
        });
    }

    @Test
    @DisplayName("opens it on the public-event broker and not on the audit one")
    void the_container_should_be_built_on_the_public_event_connection_factory() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            final ConnectionFactory used = container(context).getConnectionFactory();

            assertThat(used)
                    .as("a subscription opened on the audit broker hears no document-available "
                            + "event ever, and every batch waits for an outcome delivered to "
                            + "nobody - with the pod up, the container started and nothing logged")
                    .isNotSameAs(context.getBean(AUDIT_CONNECTION_FACTORY))
                    .isSameAs(bootProvided(context));
        });
    }

    /**
     * Boot's own Artemis connection factory, which is the one the estate's public-event broker is
     * behind.
     *
     * <p>Read through {@code ConnectionFactoryUnwrapper} because that is what
     * {@link PublicEventsConfig} does to it: Boot's shared factory is a caching one and the
     * container caches a connection itself, so the container is handed the native factory and the
     * comparison has to be made against the same thing.
     *
     * @param context the refreshed context
     * @return the unwrapped factory the container should have been built on
     */
    private static ConnectionFactory bootProvided(final AssertableApplicationContext context) {
        return ConnectionFactoryUnwrapper.unwrap(
                context.getBean(BOOT_CONNECTION_FACTORY, ConnectionFactory.class));
    }

    /**
     * The container the committed configuration builds, for an endpoint shaped as the listener's.
     *
     * @param context the refreshed context
     * @return the container, unstarted and never connected
     */
    private static DefaultMessageListenerContainer container(
            final AssertableApplicationContext context) {

        final DefaultJmsListenerContainerFactory factory = context.getBean(
                PublicEventsConfig.LISTENER_CONTAINER_FACTORY,
                DefaultJmsListenerContainerFactory.class);

        final SimpleJmsListenerEndpoint endpoint = new SimpleJmsListenerEndpoint();
        endpoint.setId("public-events");
        endpoint.setDestination(TOPIC);
        endpoint.setSubscription(SUBSCRIPTION);
        endpoint.setMessageListener(message -> {});
        return factory.createListenerContainer(endpoint);
    }
}
