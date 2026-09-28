package uk.gov.hmcts.cp.yotresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import jakarta.jms.ConnectionFactory;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jms.autoconfigure.JmsProperties;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationProperties.SourceMode;

/**
 * The shape of the container the public-event subscription runs in, read off a container the
 * committed factory actually built.
 *
 * <p>{@code DocumentEventListenerIT} asks a broker what this configuration does; this asks the
 * configuration what it is, and the two questions are different. A subscription that is durable but
 * <strong>not shared</strong> works perfectly against one broker and one pod, which is every
 * assertion an integration suite with a single context can make on its own - and refuses the second
 * pod the moment the deployment is scaled. The four settings below are what separates those two
 * outcomes, and none of them is readable off the factory: {@code DefaultJmsListenerContainerFactory}
 * has setters and no getters, so the only honest way to ask is to build the container it would build
 * and read the container.
 *
 * <p><strong>Why the client id is asserted absent rather than simply not set.</strong> A shared
 * durable subscription is keyed by its name alone and needs no client id; a client id that is set is
 * carried onto every pod's connection, and a client id two connections share is the thing brokers
 * refuse. The property is gone from {@code application.yaml}, but a property removed from a file is
 * still a property an environment can bind (Boot reads {@code SPRING_JMS_CLIENT_ID} whether or not
 * this repository mentions it), so the factory is held to ignoring one that is set rather than to
 * being handed none.
 */
@DisplayName("the public-event listener container factory")
class PublicEventsConfigTest {

    /** A client id an environment might still bind, set here so the factory can be seen ignoring it. */
    private static final String A_BOUND_CLIENT_ID = "yotresultsdistribution-service";

    /** The estate's multicast address, named only so the endpoint has somewhere to point. */
    private static final String TOPIC = "public.event";

    /** The name that, on a shared durable subscription, is the whole of its identity. */
    private static final String SUBSCRIPTION = "yotresultsdistribution-service.sdg";

    @Nested
    @DisplayName("the container it builds")
    class TheContainerItBuilds {

        @Test
        @DisplayName("is a shared durable subscription on the topic, with no client id")
        void the_factory_should_build_a_shared_durable_topic_subscription_carrying_no_client_id() {
            final DefaultMessageListenerContainer container = container();

            assertThat(container.isSubscriptionShared())
                    .as("a non-shared durable subscription admits exactly one consumer, so the "
                            + "second pod's container is refused by the broker and retries for ever "
                            + "- which is a deployment that cannot be scaled past one replica")
                    .isTrue();
            assertThat(container.isSubscriptionDurable())
                    .as("shared and durable are two settings, not one: an outcome published while "
                            + "every pod was redeploying still has to be delivered afterwards")
                    .isTrue();
            assertThat(container.isPubSubDomain())
                    .as("public.event is a topic, and a queue-shaped container reads nothing off a "
                            + "multicast address")
                    .isTrue();
            assertThat(container.getClientId())
                    .as("a shared durable subscription is keyed by its name, and a client id every "
                            + "pod shares is exactly what refuses the second connection")
                    .isNull();
        }

        @Test
        @DisplayName("runs one consumer per pod, because the scaling is pods and not threads")
        void the_factory_should_build_a_container_with_one_consumer_per_pod() {
            final DefaultMessageListenerContainer container = container();

            assertThat(container.getConcurrentConsumers())
                    .as("outcomes arrive at the rate court centres are rendered at, which is tens a "
                            + "night: the replicas share the subscription, and a second thread "
                            + "inside one of them buys nothing")
                    .isOne();
            assertThat(container.getMaxConcurrentConsumers())
                    .as("the concurrency is a fixed one and not a range the container may grow into")
                    .isOne();
        }

        @Test
        @DisplayName("still rolls back the delivery the sink could not apply")
        void the_factory_should_build_a_transacted_container() {
            assertThat(container().isSessionTransacted())
                    .as("the listener hands the sink's failure back on purpose, and an "
                            + "auto-acknowledging container has nothing left to hand back")
                    .isTrue();
        }
    }

    /**
     * The container the committed factory builds, for an endpoint shaped as the listener's own.
     *
     * @return the container, unstarted and never connected: everything asserted above is settled
     *         before a connection is opened
     */
    private static DefaultMessageListenerContainer container() {
        final DefaultJmsListenerContainerFactory factory =
                new PublicEventsConfig().publicEventListenerContainerFactory(
                        mock(ConnectionFactory.class), jmsSettings(), generationSettings());

        final SimpleJmsListenerEndpoint endpoint = new SimpleJmsListenerEndpoint();
        endpoint.setId("public-events");
        endpoint.setDestination(TOPIC);
        endpoint.setSubscription(SUBSCRIPTION);
        endpoint.setMessageListener(message -> {});
        return factory.createListenerContainer(endpoint);
    }

    /**
     * The invariant the audit starter made fragile, stated so that it stops being true by accident.
     *
     * <p>Since increment 005 {@code cp-audit-filter-springboot} is on the classpath, and it
     * contributes an {@code auditConnectionFactory} - pointed at the estate's <em>audit</em> broker
     * - marked {@code @Primary}, and an {@code auditJmsTemplate}. Boot's default
     * {@code jmsListenerContainerFactory} resolves its connection factory <strong>by type</strong>,
     * so on this classpath the default factory is built against the audit broker. Any
     * {@code @JmsListener} that does not name {@link PublicEventsConfig#LISTENER_CONTAINER_FACTORY}
     * therefore attaches to the audit broker: a subscription that opens, reports itself started, and
     * hears no {@code document-available} event ever, while every batch waits for an outcome
     * delivered to nobody. Nothing logs and nothing is counted.
     *
     * <p>It is true today - there is one {@code @JmsListener} in {@code src/main} and it names the
     * factory - and until this case it was pinned by nothing, which is how a listener written next
     * year attaches to the wrong broker with every test green.
     */
    @Nested
    @DisplayName("every JMS listener this service declares")
    class TheFactoryEveryListenerMustName {

        /** Where the declarations are read from: the source, so an unwritten one cannot hide. */
        private static final java.nio.file.Path SOURCE_ROOT =
                java.nio.file.Path.of("src", "main", "java");

        /**
         * A real declaration, which is the annotation at the start of a line.
         *
         * <p>Line-anchored so that the word inside a javadoc paragraph - this very class has
         * several - is not read as a listener that names nothing.
         */
        private static final java.util.regex.Pattern DECLARATION =
                java.util.regex.Pattern.compile("(?m)^\\s*@JmsListener\\b");

        /** What naming it looks like, exactly as the one declaration spells it. */
        private static final String NAMES_THE_FACTORY =
                "containerFactory = PublicEventsConfig.LISTENER_CONTAINER_FACTORY";

        @Test
        void no_listener_should_be_left_to_boots_default_factory() throws java.io.IOException {
            final java.util.List<String> unnamed = new java.util.ArrayList<>();
            try (java.util.stream.Stream<java.nio.file.Path> sources =
                         java.nio.file.Files.walk(SOURCE_ROOT)) {

                for (final java.nio.file.Path source : sources
                        .filter(path -> path.toString().endsWith(".java")).toList()) {

                    if (!declaresWithFactory(java.nio.file.Files.readString(source))) {
                        unnamed.add(source.toString());
                    }
                }
            }

            assertThat(unnamed)
                    .as("a listener that names no container factory is built on Boot's default, "
                            + "which on this classpath resolves against the audit starter's "
                            + "@Primary connection factory - the audit broker, where no "
                            + "document-available event will ever arrive")
                    .isEmpty();
        }

        /**
         * Whether every {@code @JmsListener} in one source names the public-event factory.
         *
         * @param text the source
         * @return true where each occurrence is followed by the factory before the annotation ends
         */
        private static boolean declaresWithFactory(final String text) {
            final java.util.regex.Matcher found = DECLARATION.matcher(text);
            boolean named = true;
            while (named && found.find()) {
                final int ends = text.indexOf(')', found.end());
                final String declaration =
                        ends < 0 ? text.substring(found.end()) : text.substring(found.end(), ends);
                named = declaration.contains(NAMES_THE_FACTORY);
            }
            return named;
        }
    }

    /** Spring's own JMS settings as {@code application.yaml} leaves them, plus a bound client id. */
    private static JmsProperties jmsSettings() {
        final JmsProperties jms = new JmsProperties();
        jms.setPubSubDomain(true);
        jms.setSubscriptionDurable(true);
        jms.setClientId(A_BOUND_CLIENT_ID);
        return jms;
    }

    /** A generating pod's settings, which is the only deployment that holds the subscription. */
    private static GenerationProperties generationSettings() {
        return new GenerationProperties(true, "0 0 18 * * MON-FRI", "Europe/London", false,
                Duration.ofMinutes(60), Duration.ofMinutes(70), Duration.ofMinutes(30),
                Duration.ofMinutes(10), SourceMode.LIVE, SourceMode.LIVE,
                SourceMode.LIVE, SourceMode.LIVE);
    }
}
