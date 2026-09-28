package uk.gov.hmcts.cp.yotresultsdistribution.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.notificationnotify.NotificationNotifyClient;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.stub.StubRegisterNotifier;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterNotifier;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterNotifierService;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotification;

/**
 * Which notifier a context resolves, and what the live one was built out of. <strong>[A]</strong>
 *
 * <p><strong>An [A] characterisation, green on introduction, and recorded as an approved exception
 * in {@code specs/002-consolidate-progression-leg/tasks.md}.</strong> The seams commit for the
 * notification slice landed {@link LiveNotificationConfig} complete - the two conditions, the
 * endpoint, the identity and both timeouts - so three test authors could write against a real bean
 * graph in parallel, and no red run preceded it. Nothing then held any of it down: the client's own
 * suite builds a {@code RestClient} by hand and the wiring context test asks only that a
 * {@code DocumentRenderer} and a {@code PayloadFileStore} resolve live. So a condition inverted, an
 * endpoint read off the wrong setting or a timeout left unset would have been found by a deployed
 * pod. This suite is where that stops being true; it accepts the wiring as it stands rather than
 * specifying it, which is why the run recorded for it is a passing one.
 *
 * <p><strong>What the two conditions have to answer.</strong> The bean is contributed where
 * {@code yotresultsdistribution.generation.nn-mode} is LIVE, which is the default, and only where
 * {@code yotresultsdistribution.generation.enabled} is true: a pod that runs no nightly job renders nothing
 * and so has nothing to send, and the client would otherwise hold an endpoint and an identity for a
 * call nobody makes. STUB is the refusing stand-in, and {@link PropertiesValidator} refuses STUB
 * outright wherever generation is enabled, so the pair cannot be resolved the wrong way round in an
 * environment that matters (constitution Principle V).
 *
 * <p><strong>What the bean is made of is asked of the wire, because there is nowhere else to ask
 * it.</strong> A {@code RestClient}'s base URL and a request factory's timeouts are not readable off
 * the objects that hold them, and the identity is a secret the client deliberately exposes nowhere.
 * So the context is stood up against a real socket and the bean it resolved is asked to send: what
 * notificationnotify received is the endpoint the deployment configured, the path the row's identity
 * belongs in and the {@code CJSCPPUID} the deployment configured, and what the caller got back when
 * that socket went quiet is the read timeout it was built with.
 *
 * <p><strong>The template is deliberately not here</strong>, and the last case says so. The
 * {@code cr_standard} id is validated for shape at startup by {@link PropertiesValidator} (defect
 * fix P9) and reaches the wire on the {@code register_notification} row, so the notifier bean is
 * contributed whether or not the setting is present: a template read only under LIVE would be a
 * dependency the notifier service could not resolve against the stub.
 */
@DisplayName("Live notification configuration")
class LiveNotificationConfigTest {

    /** The identity every command of this suite is expected to carry. */
    private static final String SYSTEM_USER_ID = "3f2c8ba4-9d61-4e07-8a35-71b0c4d29e58";

    /** The template id the row carries to the wire, which this configuration never reads. */
    private static final UUID TEMPLATE_ID =
            UUID.fromString("0b7f2c31-6a4d-4e59-8c02-9a1e3f5d7b60");

    private static final UUID NOTIFICATION_ID =
            UUID.fromString("c41d7e08-5b93-4a26-9f70-8d2e1a63b054");
    private static final UUID BATCH_ID =
            UUID.fromString("a4c1f0d2-7e6b-4c8a-9f31-2d5b6e0a7c14");
    private static final UUID DOCUMENT_FILE_ID =
            UUID.fromString("2b8a9d10-77c5-4a6d-8f3e-0d51c9a2e4b7");

    private static final String ADDRESS = "yot.lambeth@example.gov.uk";
    private static final String RECIPIENT_NAME = "Lambeth Youth Offending Team";

    /** The read timeout the deployment configures, short enough for a suite to wait it out. */
    private static final Duration READ_TIMEOUT = Duration.ofMillis(400);

    /**
     * How long notificationnotify is made to hold the connection: well past the read timeout.
     *
     * <p>The point of the case is that the wait the caller actually takes is the configured one and
     * not this, so the two have to be far enough apart that a client built with no read timeout at
     * all could not be mistaken for one built with the configured one.
     */
    private static final Duration LONGER_THAN_THE_READ_TIMEOUT = Duration.ofSeconds(8);

    private static final String GENERATION_ENABLED = "yotresultsdistribution.generation.enabled=true";
    private static final String NN_MODE_LIVE = "yotresultsdistribution.generation.nn-mode=LIVE";
    private static final String NN_MODE_STUB = "yotresultsdistribution.generation.nn-mode=STUB";
    private static final String SYSTEM_USER_ID_PROPERTY =
            "yotresultsdistribution.endpoints.system-user-id=" + SYSTEM_USER_ID;
    private static final String READ_TIMEOUT_PROPERTY =
            "yotresultsdistribution.endpoints.read-timeout=" + READ_TIMEOUT.toMillis() + "ms";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(NotificationTestConfiguration.class);

    private WireMockServer notificationNotify;

    /**
     * The configuration under test, over the settings and the shared mapper it asks for.
     *
     * <p>The mapper is contributed as the contract mapper rather than left to Jackson's
     * auto-configuration, because the bean this configuration receives in a deployment is the one
     * {@link JacksonConfig}'s customizer has already been applied to.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(YotResultsDistributionProperties.class)
    @Import(LiveNotificationConfig.class)
    static class NotificationTestConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return JacksonConfig.contractObjectMapper();
        }
    }

    @BeforeEach
    void startNotificationNotify() {
        notificationNotify = new WireMockServer(wireMockConfig().dynamicPort());
        notificationNotify.start();
    }

    @AfterEach
    void stopNotificationNotify() {
        notificationNotify.stop();
    }

    /** The endpoint setting, pointed at the socket this case is listening on. */
    private String endpointProperty() {
        return "yotresultsdistribution.endpoints.notificationnotify=" + notificationNotify.baseUrl();
    }

    /** The command path one notification's POST belongs at, which is the id and nothing else. */
    private static String commandPath() {
        return NotificationNotifyClient.COMMAND_PATH
                .replace("{notificationId}", NOTIFICATION_ID.toString());
    }

    /** One recipient's row as it stands the moment before its POST is made. */
    private static RegisterNotification pending() {
        return new RegisterNotification(NOTIFICATION_ID, BATCH_ID, ADDRESS, RECIPIENT_NAME,
                RegisterNotifierService.TEMPLATE_NAME, TEMPLATE_ID,
                NotificationStatus.PENDING, null, null, 0);
    }

    @Nested
    @DisplayName("which notifier a context resolves")
    class WhichNotifierIsContributed {

        @Test
        @DisplayName("the live client where the mode says LIVE and generation is enabled")
        void the_live_notifier_should_be_contributed_where_the_mode_and_the_job_both_say_so() {
            runner.withPropertyValues(GENERATION_ENABLED, NN_MODE_LIVE, endpointProperty(),
                            SYSTEM_USER_ID_PROPERTY)
                    .run(context -> assertThat(context.getBean(RegisterNotifier.class))
                            .as("the real last leg of the nightly run: a pod resolving anything "
                                    + "else here tells no Youth Offending Team anything")
                            .isInstanceOf(NotificationNotifyClient.class)
                            .isNotInstanceOf(StubRegisterNotifier.class));
        }

        /**
         * LIVE is the default, and this is where that is stated. Every deployed environment leaves
         * the key unset, so a condition without {@code matchIfMissing} would leave the whole estate
         * with no notifier at all and a nightly run that could not be constructed.
         */
        @Test
        @DisplayName("the live client where the mode is not named at all, because LIVE is default")
        void the_live_notifier_should_be_contributed_where_the_mode_is_not_named() {
            runner.withPropertyValues(GENERATION_ENABLED, endpointProperty(),
                            SYSTEM_USER_ID_PROPERTY)
                    .run(context -> assertThat(context.getBeanProvider(RegisterNotifier.class)
                                    .getIfAvailable())
                            .as("LIVE is the default and is what an environment that says nothing "
                                    + "gets, which is every environment this service is deployed to")
                            .isInstanceOf(NotificationNotifyClient.class));
        }

        @Test
        @DisplayName("nothing where the mode says STUB")
        void no_live_notifier_should_be_contributed_where_the_mode_says_stub() {
            runner.withPropertyValues(GENERATION_ENABLED, NN_MODE_STUB, endpointProperty(),
                            SYSTEM_USER_ID_PROPERTY)
                    .run(context -> assertThat(context)
                            .as("the refusing stand-in is StubGenerationConfig's, chosen by the "
                                    + "same key: this configuration contributes nothing, rather "
                                    + "than contributing a second bean the mode cannot choose "
                                    + "between")
                            .doesNotHaveBean(RegisterNotifier.class));
        }

        /**
         * The second condition, and the one on the configuration as a whole. A deployment that runs
         * no nightly job renders nothing and so has nothing to send; a client contributed there
         * would hold an endpoint and an identity for a call nobody makes.
         */
        @Test
        @DisplayName("nothing where the nightly job is not enabled, whatever the mode says")
        void no_live_notifier_should_be_contributed_where_generation_is_not_enabled() {
            runner.withPropertyValues(NN_MODE_LIVE, endpointProperty(), SYSTEM_USER_ID_PROPERTY)
                    .run(context -> assertThat(context)
                            .as("an intake-only pod sends no register e-mail, because it renders "
                                    + "no register")
                            .doesNotHaveBean(RegisterNotifier.class));
        }

        /**
         * The {@code test} profile has no downstream at all, which is what makes the unit suites
         * unit suites: they build the client they are about in one line and nothing else may reach
         * a socket behind them.
         */
        @Test
        @DisplayName("nothing in the test profile, alongside the rest of the live wiring")
        void no_live_notifier_should_be_contributed_in_the_test_profile() {
            runner.withPropertyValues(GENERATION_ENABLED, NN_MODE_LIVE, endpointProperty(),
                            SYSTEM_USER_ID_PROPERTY)
                    .withSystemProperties("spring.profiles.active=test")
                    .run(context -> assertThat(context)
                            .as("excluded from the test profile alongside every other live "
                                    + "configuration, so a suite cannot reach notificationnotify "
                                    + "by accident")
                            .doesNotHaveBean(RegisterNotifier.class));
        }
    }

    @Nested
    @DisplayName("what the live client was built out of")
    class WhatTheClientWasBuiltFrom {

        /**
         * The endpoint and the identity, asked of the wire.
         *
         * <p>Neither is readable off the objects that hold them, and neither has any business being
         * readable: the base URL is the {@code RestClient}'s and the identity is a secret the client
         * exposes nowhere. What a deployment cares about is where the command went and who it went
         * as, and both of those are on the request notificationnotify received.
         */
        @Test
        @DisplayName("the configured endpoint and the configured CJSCPPUID identity")
        void the_client_should_post_to_the_configured_endpoint_as_the_configured_identity() {
            notificationNotify.stubFor(post(urlEqualTo(commandPath()))
                    .willReturn(aResponse().withStatus(NotificationNotifyClient.ACCEPTED)));

            runner.withPropertyValues(GENERATION_ENABLED, NN_MODE_LIVE, endpointProperty(),
                            SYSTEM_USER_ID_PROPERTY)
                    .run(context -> {
                        assertThat(context.getBean(RegisterNotifier.class).send(
                                pending(), DOCUMENT_FILE_ID, CallerIdentity.SYSTEM).status())
                                .as("202 and nothing else is acceptance")
                                .isEqualTo(NotificationStatus.ACCEPTED);

                        notificationNotify.verify(postRequestedFor(urlEqualTo(commandPath()))
                                .withHeader(NotificationNotifyClient.IDENTITY_HEADER,
                                        equalTo(SYSTEM_USER_ID)));
                    });
        }

        /**
         * The read timeout, asked of a socket that goes quiet.
         *
         * <p>A POST with no read timeout can outlive the bound that was supposed to hold it, and for
         * this flow that is a run hanging on one recipient while the rest of the batch waits to be
         * told. So the case is not only that the refusal is transient - it is that the caller was
         * given the connection back on the configured schedule rather than notificationnotify's.
         */
        @Test
        @DisplayName("the configured read timeout, which is what hands a stalled POST back")
        void the_client_should_give_up_on_a_stalled_post_after_the_configured_read_timeout() {
            notificationNotify.stubFor(post(urlEqualTo(commandPath()))
                    .willReturn(aResponse()
                            .withStatus(NotificationNotifyClient.ACCEPTED)
                            .withFixedDelay((int) LONGER_THAN_THE_READ_TIMEOUT.toMillis())));

            runner.withPropertyValues(GENERATION_ENABLED, NN_MODE_LIVE, endpointProperty(),
                            SYSTEM_USER_ID_PROPERTY, READ_TIMEOUT_PROPERTY)
                    .run(context -> {
                        final RegisterNotifier notifier = context.getBean(RegisterNotifier.class);
                        final Instant asked = Instant.now();

                        final NotificationFailedException refused = catchThrowableOfType(
                                NotificationFailedException.class,
                                () -> notifier.send(pending(), DOCUMENT_FILE_ID,
                                        CallerIdentity.SYSTEM));

                        assertThat(refused)
                                .as("a POST with no read timeout can outlive the bound that was "
                                        + "supposed to hold it, so a client built without one "
                                        + "waits notificationnotify out and answers 202 here")
                                .isNotNull();
                        assertThat(refused.classification())
                                .as("a read timeout says nothing about whether the e-mail was "
                                        + "asked for, so the run may ask again under the same "
                                        + "identity")
                                .isEqualTo(FailureClassification.TRANSIENT);
                        assertThat(refused.responseCode())
                                .as("nothing answered, and a row carrying an invented status would "
                                        + "say an attempt was answered when nothing was")
                                .isEmpty();
                        assertThat(Duration.between(asked, Instant.now()))
                                .as("the timeout the deployment configured is the one that was "
                                        + "spent: a factory left with no read timeout would still "
                                        + "be waiting here")
                                .isLessThan(LONGER_THAN_THE_READ_TIMEOUT);
                    });
        }

        /**
         * And what is deliberately not wired here.
         *
         * <p>The {@code cr_standard} id reaches the wire on the notification row, which is minted
         * before any POST and is the evidence of what was sent. A template read as a bean of this
         * configuration would be a dependency the notifier service could not resolve against the
         * stub, so the notifier is contributed with the setting absent altogether.
         */
        @Test
        @DisplayName("no template: it reaches the wire on the row, not through this configuration")
        void the_client_should_be_contributed_with_no_template_setting_at_all() {
            runner.withPropertyValues(GENERATION_ENABLED, NN_MODE_LIVE, endpointProperty(),
                            SYSTEM_USER_ID_PROPERTY)
                    .run(context -> {
                        assertThat(context.getBean(YotResultsDistributionProperties.class)
                                        .email().templates().crStandard())
                                .as("the setting PropertiesValidator refuses at startup wherever "
                                        + "generation is enabled (defect fix P9), absent here "
                                        + "because this configuration is not who reads it")
                                .isNull();
                        assertThat(context.getBean(RegisterNotifier.class))
                                .as("the client is the endpoint and the identity; the template is "
                                        + "the row's")
                                .isInstanceOf(NotificationNotifyClient.class);
                    });
        }
    }
}
