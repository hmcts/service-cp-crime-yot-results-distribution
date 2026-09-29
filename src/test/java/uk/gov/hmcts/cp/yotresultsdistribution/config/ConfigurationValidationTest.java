package uk.gov.hmcts.cp.yotresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;

/**
 * Holds the configuration surface to the plan's table, and holds startup to the rules that make the
 * service safe to run.
 *
 * <p>The two timing relationships and the credential rule are checked at startup precisely because
 * they fail quietly otherwise: a run that outlives its claim shows up as a duplicate submission
 * weeks later, and a silently preferred credential source is how a deployed pod ends up talking to
 * the wrong broker. The court register adds a third family of them — a submission that cannot
 * authorise its POST, a retry policy that cannot make one, and the C29 pre-send validator switched
 * off where the service is deployed.
 *
 * <p>The downstream half adds a fourth family, and the same argument carries them: a schedule read
 * in the wrong zone, a run with no payload store, no flag, no renderer or no notifier, an
 * event-driven completion with no broker to hear from, a stub reachable where the service is
 * deployed, and a blank or malformed e-mail template id (fix P9). Every one of them is a
 * configuration error a deploy should fail on rather than a night's registers nobody receives.
 *
 * <p>The plan's Spring-level rows — datasource, Flyway, server and management — are not asserted
 * here. They arrive with {@code application.yaml} and are proven by the context boot and the
 * HTTP-surface and readiness suites.
 */
class ConfigurationValidationTest {

    /** The emulator connection string, the local and CI credential. */
    private static final String CONNECTION_STRING =
            "Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;"
                    + "SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;";

    private static final String NAMESPACE = "yotresultsdistribution.servicebus.windows.net";

    private static final String CONNECTION_STRING_PROPERTY =
            "yotresultsdistribution.servicebus.connection-string=" + CONNECTION_STRING;

    private static final String NAMESPACE_PROPERTY =
            "yotresultsdistribution.servicebus.namespace=" + NAMESPACE;

    /**
     * The identity the query-side payload fallback authorises with. Carried by every case here that
     * is not about it, because the live payload source cannot work without one and startup says so.
     */
    private static final String PAYLOAD_IDENTITY_PROPERTY =
            "yotresultsdistribution.results.system-user-id=9f61bdbb-6f1a-4c0f-9a3d-6b8f0f1c2a44";

    /**
     * The endpoint and identity the submission needs. Carried by every case here that is not about
     * them: the register is POSTed to progression on every hearing that produces one, and a POST
     * with nowhere to go or nobody to be from is refused every time.
     */
    private static final String PROGRESSION_ENDPOINT_PROPERTY =
            "yotresultsdistribution.progression.base-url=http://localhost:8080";

    private static final String PROGRESSION_IDENTITY_PROPERTY =
            "yotresultsdistribution.progression.system-user-id=4d3c2b1a-9e8f-4a7b-8c6d-5e4f3a2b1c09";

    /**
     * The endpoint and identity the live now-subscriptions source needs, carried for the same reason
     * the two above are: the live source is the default, and startup refuses one that cannot ask
     * reference data anything.
     */
    private static final String REFDATA_ENDPOINT_PROPERTY =
            "yotresultsdistribution.referencedata.base-url=http://localhost:8080";

    private static final String REFDATA_IDENTITY_PROPERTY =
            "yotresultsdistribution.referencedata.system-user-id=2c7b1e64-0f4a-4f0e-9b2c-8d1a6f3e5c07";

    /** The master switch for the downstream half, which is what turns its refusals on with it. */
    private static final String GENERATION_ENABLED_PROPERTY =
            "yotresultsdistribution.generation.enabled=true";

    /**
     * Everything an enabled generation deployment needs, carried by every case here that is about
     * something else. Each of the cases below blanks exactly one of them, which is how a refusal is
     * attributed to the setting that is missing rather than to whichever is checked first.
     */
    private static final String FILESERVICE_URL_PROPERTY =
            "yotresultsdistribution.fileservice.url=jdbc:postgresql://localhost:5432/fileservice";

    /** The setting the flag's connection string binds to. */
    private static final String FLAG_CONNECTION_STRING = "yotresultsdistribution.feature.connection-string";

    /** Invented here and distinctive, so a refusal that quoted it would be found. */
    private static final String DEPLOYED_SECRET = "ZGVwbG95ZWQtc2VjcmV0LW5ldmVyLXF1b3RlZA==";

    /** The shape Key Vault injects on a deployed pod. */
    private static final String FLAG_CONNECTION_STRING_PROPERTY = FLAG_CONNECTION_STRING
            + "=Endpoint=https://appconfig.internal;Id=ste-id;Secret=" + DEPLOYED_SECRET;

    /** The published local pair's secret; Base64 of {@code not-a-secret}. */
    private static final String LOCAL_PAIR_SECRET = "bm90LWEtc2VjcmV0";

    private static final String FLAG_LABEL_PROPERTY = "yotresultsdistribution.feature.label=ste86";

    private static final String SDG_ENDPOINT_PROPERTY =
            "yotresultsdistribution.endpoints.systemdocgenerator=http://systemdocgenerator.internal:8080";

    private static final String NN_ENDPOINT_PROPERTY =
            "yotresultsdistribution.endpoints.notificationnotify=http://notificationnotify.internal:8080";

    /** The CJSCPPUID both outward legs post under; a secret, and never quoted back in a refusal. */
    private static final String ENDPOINTS_IDENTITY_PROPERTY =
            "yotresultsdistribution.endpoints.system-user-id=6b1f0c94-2d75-4e38-a9c1-0f7b4e2d85a3";

    /** The notificationnotify template the register e-mail is sent with, and a UUID (P9). */
    private static final String TEMPLATE_ID = "5c9a0e21-3d47-4f18-9b62-0a71c4e8d530";

    private static final String TEMPLATE_PROPERTY =
            "yotresultsdistribution.email.templates.cr_standard=" + TEMPLATE_ID;

    /** The broker the event-driven completion listens on; Spring's own key, not this service's. */
    private static final String BROKER_URL_PROPERTY =
            "spring.artemis.broker-url=tcp://artemis.internal:61616";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(PropertiesTestConfiguration.class)
                    .withPropertyValues(PAYLOAD_IDENTITY_PROPERTY, PROGRESSION_ENDPOINT_PROPERTY,
                            PROGRESSION_IDENTITY_PROPERTY, REFDATA_ENDPOINT_PROPERTY,
                            REFDATA_IDENTITY_PROPERTY);

    /**
     * A deployment with the downstream half switched on and every setting it requires supplied.
     *
     * <p>The local credential source rather than a namespace, because the two discriminators are
     * independent: these cases are about what generation requires, and a namespace would bring the
     * deployed-environment rules along with them.
     */
    private final ApplicationContextRunner generating = runner.withPropertyValues(
            CONNECTION_STRING_PROPERTY, GENERATION_ENABLED_PROPERTY, FILESERVICE_URL_PROPERTY,
            FLAG_CONNECTION_STRING_PROPERTY, FLAG_LABEL_PROPERTY, SDG_ENDPOINT_PROPERTY, NN_ENDPOINT_PROPERTY,
            ENDPOINTS_IDENTITY_PROPERTY, TEMPLATE_PROPERTY, BROKER_URL_PROPERTY);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({YotResultsDistributionProperties.class, GenerationProperties.class,
        FeatureFlagProperties.class, ReportProperties.class, OperationsProperties.class})
    @Import(PropertiesValidator.class)
    static class PropertiesTestConfiguration {
    }

    @Nested
    @DisplayName("binding")
    class Binding {

        @Test
        void every_setting_should_fall_back_to_the_documented_default() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY).run(context -> {
                assertThat(context).hasNotFailed();
                final YotResultsDistributionProperties properties =
                        context.getBean(YotResultsDistributionProperties.class);

                assertThat(properties.consumer().enabled()).isTrue();

                assertThat(properties.servicebus().connectionString()).isEqualTo(CONNECTION_STRING);
                assertThat(properties.servicebus().namespace()).isNull();
                assertThat(properties.servicebus().queueName()).isEqualTo("yotresultsdistribution.requests");
                assertThat(properties.servicebus().maxConcurrentCalls()).isEqualTo(2);
                assertThat(properties.servicebus().maxDeliveryCount()).isEqualTo(5);
                assertThat(properties.servicebus().maxAutoLockRenewDuration())
                        .isEqualTo(Duration.ofMinutes(5));
                assertThat(properties.servicebus().healthStaleness())
                        .isEqualTo(Duration.ofSeconds(60));

                assertThat(properties.claim().lease()).isEqualTo(Duration.ofMinutes(5));
                assertThat(properties.claim().processingDeadline()).isEqualTo(Duration.ofMinutes(4));

                assertThat(properties.store().probeInterval()).isEqualTo(Duration.ofSeconds(10));

                assertThat(properties.stub().payloadFailureMode()).isEqualTo(PayloadFailureMode.NONE);

                assertThat(properties.payload().mode()).isEqualTo(PayloadSourceMode.LIVE);
                assertThat(properties.payload().redis().host()).isEqualTo("localhost");
                assertThat(properties.payload().redis().port()).isEqualTo(6379);
                assertThat(properties.payload().redis().password()).isNull();
                assertThat(properties.payload().redis().ssl()).isFalse();
                assertThat(properties.payload().redis().keyPrefix()).isEqualTo("INT_");
                assertThat(properties.payload().redis().connectTimeout())
                        .isEqualTo(Duration.ofSeconds(5));
                assertThat(properties.payload().redis().commandTimeout())
                        .isEqualTo(Duration.ofSeconds(5));
                assertThat(properties.payload().fallback().maxAttempts()).isEqualTo(3);
                assertThat(properties.payload().fallback().initialBackoff())
                        .isEqualTo(Duration.ofSeconds(1));
                assertThat(properties.payload().fallback().maxBackoff())
                        .isEqualTo(Duration.ofSeconds(2));
                assertThat(properties.payload().fallback().connectTimeout())
                        .isEqualTo(Duration.ofSeconds(5));
                assertThat(properties.payload().fallback().readTimeout())
                        .isEqualTo(Duration.ofSeconds(10));

                assertThat(properties.referencedata().mode())
                        .isEqualTo(SubscriptionsSourceMode.LIVE);
                assertThat(properties.referencedata().headers()).isEmpty();
                assertThat(properties.referencedata().maxAttempts()).isEqualTo(3);
                assertThat(properties.referencedata().initialBackoff())
                        .isEqualTo(Duration.ofSeconds(1));
                assertThat(properties.referencedata().maxBackoff())
                        .isEqualTo(Duration.ofSeconds(2));
                assertThat(properties.referencedata().connectTimeout())
                        .isEqualTo(Duration.ofSeconds(5));
                assertThat(properties.referencedata().readTimeout())
                        .isEqualTo(Duration.ofSeconds(10));

                assertThat(properties.progression().headers()).isEmpty();
                assertThat(properties.progression().maxAttempts()).isEqualTo(4);
                assertThat(properties.progression().initialBackoff())
                        .isEqualTo(Duration.ofMillis(500));
                assertThat(properties.progression().maxBackoff()).isEqualTo(Duration.ofSeconds(5));
                assertThat(properties.progression().connectTimeout())
                        .isEqualTo(Duration.ofSeconds(5));
                assertThat(properties.progression().readTimeout())
                        .isEqualTo(Duration.ofSeconds(10));

                assertThat(properties.submission().validateOutbound())
                        .as("the C29 pre-send validator is on unless something turns it off")
                        .isTrue();

                assertThat(properties.results().baseUrl())
                        .as("an endpoint a service invents is an endpoint it can talk to by mistake")
                        .isNull();
            });
        }

        @Test
        void every_setting_should_be_overridable() {
            runner.withPropertyValues(
                    NAMESPACE_PROPERTY,
                    "yotresultsdistribution.consumer.enabled=false",
                    "yotresultsdistribution.servicebus.queue-name=other.requests",
                    "yotresultsdistribution.servicebus.max-concurrent-calls=8",
                    "yotresultsdistribution.servicebus.max-delivery-count=3",
                    "yotresultsdistribution.servicebus.max-auto-lock-renew-duration=9m",
                    "yotresultsdistribution.servicebus.health-staleness=90s",
                    "yotresultsdistribution.claim.lease=8m",
                    "yotresultsdistribution.claim.processing-deadline=7m",
                    "yotresultsdistribution.store.probe-interval=45s",
                    "yotresultsdistribution.stub.payload-failure-mode=TRANSIENT",
                    "yotresultsdistribution.payload.redis.host=cache.internal",
                    "yotresultsdistribution.payload.redis.port=6380",
                    "yotresultsdistribution.payload.redis.password=a-secret",
                    "yotresultsdistribution.payload.redis.ssl=true",
                    "yotresultsdistribution.payload.redis.key-prefix=OTHER_",
                    "yotresultsdistribution.payload.fallback.max-attempts=2",
                    "yotresultsdistribution.payload.fallback.initial-backoff=3s",
                    "yotresultsdistribution.payload.fallback.max-backoff=6s",
                    "yotresultsdistribution.results.base-url=http://results.internal:8080",
                    "yotresultsdistribution.referencedata.max-attempts=4",
                    "yotresultsdistribution.referencedata.initial-backoff=2s",
                    "yotresultsdistribution.referencedata.max-backoff=4s",
                    "yotresultsdistribution.referencedata.headers.X-Mesh-Group=court-register",
                    "yotresultsdistribution.progression.max-attempts=2",
                    "yotresultsdistribution.progression.initial-backoff=250ms",
                    "yotresultsdistribution.progression.max-backoff=10s",
                    "yotresultsdistribution.progression.connect-timeout=3s",
                    "yotresultsdistribution.progression.read-timeout=15s",
                    "yotresultsdistribution.progression.headers.X-Mesh-Group=progression").run(context -> {
                        assertThat(context).hasNotFailed();
                        final YotResultsDistributionProperties properties =
                                context.getBean(YotResultsDistributionProperties.class);

                        assertThat(properties.consumer().enabled()).isFalse();

                        assertThat(properties.servicebus().connectionString()).isNull();
                        assertThat(properties.servicebus().namespace()).isEqualTo(NAMESPACE);
                        assertThat(properties.servicebus().queueName()).isEqualTo("other.requests");
                        assertThat(properties.servicebus().maxConcurrentCalls()).isEqualTo(8);
                        assertThat(properties.servicebus().maxDeliveryCount()).isEqualTo(3);
                        assertThat(properties.servicebus().maxAutoLockRenewDuration())
                                .isEqualTo(Duration.ofMinutes(9));
                        assertThat(properties.servicebus().healthStaleness())
                                .isEqualTo(Duration.ofSeconds(90));

                        assertThat(properties.claim().lease()).isEqualTo(Duration.ofMinutes(8));
                        assertThat(properties.claim().processingDeadline())
                                .isEqualTo(Duration.ofMinutes(7));

                        assertThat(properties.store().probeInterval())
                                .isEqualTo(Duration.ofSeconds(45));

                        assertThat(properties.stub().payloadFailureMode())
                                .isEqualTo(PayloadFailureMode.TRANSIENT);

                        assertThat(properties.payload().redis().host()).isEqualTo("cache.internal");
                        assertThat(properties.payload().redis().port()).isEqualTo(6380);
                        assertThat(properties.payload().redis().password()).isEqualTo("a-secret");
                        assertThat(properties.payload().redis().ssl()).isTrue();
                        assertThat(properties.payload().redis().keyPrefix()).isEqualTo("OTHER_");
                        assertThat(properties.payload().fallback().maxAttempts()).isEqualTo(2);
                        assertThat(properties.payload().fallback().initialBackoff())
                                .isEqualTo(Duration.ofSeconds(3));
                        assertThat(properties.payload().fallback().maxBackoff())
                                .isEqualTo(Duration.ofSeconds(6));

                        assertThat(properties.results().baseUrl())
                                .isEqualTo("http://results.internal:8080");

                        assertThat(properties.referencedata().maxAttempts()).isEqualTo(4);
                        assertThat(properties.referencedata().initialBackoff())
                                .isEqualTo(Duration.ofSeconds(2));
                        assertThat(properties.referencedata().maxBackoff())
                                .isEqualTo(Duration.ofSeconds(4));
                        assertThat(properties.referencedata().headers())
                                .isEqualTo(Map.of("X-Mesh-Group", "court-register"));

                        assertThat(properties.progression().baseUrl())
                                .isEqualTo("http://localhost:8080");
                        assertThat(properties.progression().maxAttempts()).isEqualTo(2);
                        assertThat(properties.progression().initialBackoff())
                                .isEqualTo(Duration.ofMillis(250));
                        assertThat(properties.progression().maxBackoff())
                                .isEqualTo(Duration.ofSeconds(10));
                        assertThat(properties.progression().connectTimeout())
                                .isEqualTo(Duration.ofSeconds(3));
                        assertThat(properties.progression().readTimeout())
                                .isEqualTo(Duration.ofSeconds(15));
                        assertThat(properties.progression().headers())
                                .isEqualTo(Map.of("X-Mesh-Group", "progression"));
                    });
        }
    }

    @Nested
    @DisplayName("the run must finish before the claim can be reclaimed")
    class ProcessingDeadlineAgainstLease {

        @Test
        void a_deadline_equal_to_the_lease_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.claim.lease=5m",
                    "yotresultsdistribution.claim.processing-deadline=5m").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.claim.processing-deadline")
                                .hasMessageContaining("yotresultsdistribution.claim.lease");
                    });
        }

        @Test
        void a_deadline_longer_than_the_lease_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.claim.lease=5m",
                    "yotresultsdistribution.claim.processing-deadline=6m").run(context -> {
                        assertThat(context).hasFailed();
                        // "It failed" is not the assertion. Whoever set the deadline sees only the
                        // startup failure, and one that does not name both settings and the relation
                        // between them leaves them guessing which of a dozen durations to move.
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.claim.processing-deadline")
                                .hasMessageContaining("yotresultsdistribution.claim.lease")
                                .hasMessageContaining("strictly shorter");
                    });
        }

        @Test
        void a_deadline_shorter_than_the_lease_should_start() {
            // The renewal is raised alongside the deadline so this case tests one rule only: at
            // 4m59s the default 5m renewal would break the lock rule, which has its own cases below.
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.claim.lease=5m",
                    "yotresultsdistribution.claim.processing-deadline=PT4M59S",
                    "yotresultsdistribution.servicebus.max-auto-lock-renew-duration=PT5M29S").run(context ->
                            assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("the broker lock must outlive any legitimate run")
    class LockRenewalAgainstDeadline {

        @Test
        void a_renewal_shorter_than_the_deadline_plus_the_margin_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.claim.lease=5m",
                    "yotresultsdistribution.claim.processing-deadline=4m",
                    "yotresultsdistribution.servicebus.max-auto-lock-renew-duration=PT4M29S")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(
                                        "yotresultsdistribution.servicebus.max-auto-lock-renew-duration")
                                .hasMessageContaining("yotresultsdistribution.claim.processing-deadline");
                    });
        }

        @Test
        void a_renewal_exactly_the_deadline_plus_the_margin_should_start() {
            // The margin is a fixed 30 seconds, so this is the boundary the rule allows.
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.claim.lease=5m",
                    "yotresultsdistribution.claim.processing-deadline=4m",
                    "yotresultsdistribution.servicebus.max-auto-lock-renew-duration=PT4M30S").run(context ->
                            assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("exactly one credential source")
    class CredentialSelection {

        @Test
        void a_connection_string_alone_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY)
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        void a_namespace_alone_should_start() {
            runner.withPropertyValues(NAMESPACE_PROPERTY)
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        void both_credential_sources_should_fail_startup_with_a_clear_message() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, NAMESPACE_PROPERTY)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.servicebus.connection-string")
                                .hasMessageContaining("yotresultsdistribution.servicebus.namespace")
                                .hasMessageContaining("exactly one");
                    });
        }

        @Test
        void neither_credential_source_should_fail_startup_with_a_clear_message() {
            runner.run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasMessageContaining("yotresultsdistribution.servicebus.connection-string")
                        .hasMessageContaining("yotresultsdistribution.servicebus.namespace")
                        .hasMessageContaining("exactly one");
            });
        }

        @Test
        void a_blank_connection_string_should_count_as_unset() {
            // A deployed environment overrides the local default with an empty value rather than
            // deleting the key, so blank must mean absent or every deployment would fail as
            // "both set".
            runner.withPropertyValues("yotresultsdistribution.servicebus.connection-string=",
                    NAMESPACE_PROPERTY).run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("the live payload source needs an identity to fall back with")
    class PayloadIdentity {

        /**
         * Without it the fallback cannot be used at all: every cold-cache request is abandoned,
         * redelivered and finally dead-lettered by a pod that reports itself perfectly healthy
         * throughout. A mount that did not arrive is a deployment fault, and a deployment fault
         * belongs at startup.
         */
        @Test
        void live_mode_without_an_identity_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.results.system-user-id=",
                    "yotresultsdistribution.payload.mode=LIVE").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.results.system-user-id")
                                .hasMessageContaining("yotresultsdistribution.payload.mode");
                    });
        }

        @Test
        void live_mode_with_an_identity_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.payload.mode=LIVE")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * The identity is the live source's requirement and nobody else's. A local run on the stub
         * fetches nothing and so authorises with nobody.
         */
        @Test
        void stub_mode_without_an_identity_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.results.system-user-id=",
                    "yotresultsdistribution.payload.mode=STUB")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("the stub payload source is not reachable where the service is deployed")
    class StubReachability {

        /**
         * Constitution Principle V: a stub must not be reachable in a production profile. The
         * discriminator is the credential source already used for exactly this distinction — a
         * namespace means workload identity, which means a deployed pod. Such a pod running the stub
         * would settle every message and produce no register at all, which is the failure this
         * service exists to end.
         */
        @Test
        void stub_mode_on_the_deployed_credential_source_should_fail_startup() {
            runner.withPropertyValues(NAMESPACE_PROPERTY,
                    "yotresultsdistribution.payload.mode=STUB").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.payload.mode")
                                .hasMessageContaining("yotresultsdistribution.servicebus.namespace");
                    });
        }

        @Test
        void stub_mode_on_the_local_credential_source_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.payload.mode=STUB")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        void live_mode_on_the_deployed_credential_source_should_start() {
            runner.withPropertyValues(NAMESPACE_PROPERTY,
                    "yotresultsdistribution.payload.mode=LIVE")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("the live now-subscriptions source must be able to ask reference data")
    class SubscriptionsReachability {

        /**
         * Without an endpoint the client has nowhere to send the query, so every hearing that
         * produced a register is abandoned, redelivered and finally parked — by a pod whose
         * readiness, liveness and queue metrics all say the deployment succeeded.
         */
        @Test
        void live_mode_without_an_endpoint_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.referencedata.base-url=").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.referencedata.base-url")
                                .hasMessageContaining("yotresultsdistribution.referencedata.mode");
                    });
        }

        /**
         * {@code CJSCPPUID} is part of the reference-data query's own contract and its
         * access-control rules authorise on it, so an anonymous query is a refused query — every
         * time, for ever.
         */
        @Test
        void live_mode_without_an_identity_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.referencedata.system-user-id=").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.referencedata.system-user-id")
                                .hasMessageContaining("yotresultsdistribution.referencedata.mode");
                    });
        }

        @Test
        void live_mode_with_an_endpoint_and_an_identity_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.referencedata.mode=LIVE")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /** Both are the live source's requirement and nobody else's; the stub asks nobody. */
        @Test
        void stub_mode_without_an_endpoint_or_an_identity_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.payload.mode=STUB",
                    "yotresultsdistribution.referencedata.mode=STUB",
                    "yotresultsdistribution.referencedata.base-url=",
                    "yotresultsdistribution.referencedata.system-user-id=")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * The stub answers "nobody is subscribed", which is a legitimate business outcome and this
         * flow's commonest one. Given about a real hearing it is indistinguishable from working:
         * every run completes {@code no-subscriptions}, and the log, the metrics and the queue all
         * agree the service is doing its job. The stub cannot refuse its way out of that — the read
         * happens before the transformation, so refusing would only trade a silent completion for a
         * queue that never drains — so the configuration is what is refused, at startup.
         */
        @Test
        void stub_mode_beside_a_live_payload_source_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.payload.mode=LIVE",
                    "yotresultsdistribution.referencedata.mode=STUB").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.referencedata.mode")
                                .hasMessageContaining("yotresultsdistribution.payload.mode");
                    });
        }

        /**
         * Constitution Principle V, the same rule the payload stub is held to: a deployed pod
         * running this one asks reference data nothing, so every hearing it reads completes
         * addressed to nobody and no register is ever sent.
         */
        @Test
        void stub_mode_on_the_deployed_credential_source_should_fail_startup() {
            runner.withPropertyValues(NAMESPACE_PROPERTY,
                    "yotresultsdistribution.referencedata.mode=STUB").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.referencedata.mode")
                                .hasMessageContaining("yotresultsdistribution.servicebus.namespace");
                    });
        }

        @Test
        void a_source_with_no_attempts_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.referencedata.max-attempts=0").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.referencedata.max-attempts");
                    });
        }

        @Test
        void a_negative_wait_between_attempts_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.referencedata.initial-backoff=-1s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.referencedata.initial-backoff");
                    });
        }

        /**
         * The shared policy's other rule, asked of this client because it is asked of all three: a
         * ceiling below the first wait shortens the very wait it exists to bound.
         */
        @Test
        void a_ceiling_below_the_first_wait_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.referencedata.initial-backoff=10s",
                    "yotresultsdistribution.referencedata.max-backoff=5s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.referencedata.max-backoff")
                                .hasMessageContaining("yotresultsdistribution.referencedata.initial-backoff");
                    });
        }

        @Test
        void a_timeout_that_never_expires_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.referencedata.read-timeout=0s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.referencedata.read-timeout");
                    });
        }

        /**
         * Every timeout here is positive and every attempt count is at least one, and the read can
         * still outlast the run: ten attempts against a minute-long read is over ten minutes of
         * waiting that startup would otherwise accept. The claim becomes reclaimable long before
         * that, so another delivery starts processing the request while this runner is still blocked
         * on the socket — the outcome the processing deadline exists to prevent, reached by a
         * configuration each individual rule calls valid.
         */
        @Test
        void a_read_that_can_outlast_the_processing_deadline_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.referencedata.max-attempts=10",
                    "yotresultsdistribution.referencedata.read-timeout=1m").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.referencedata")
                                .hasMessageContaining("yotresultsdistribution.claim.processing-deadline");
                    });
        }

        /**
         * The waits between attempts count too: they are spent inside the same run as the reads.
         * The shipped three attempts of 5s + 10s is 45s, comfortably inside the 4m deadline — until
         * the two waits between them are lengthened, which no other rule looks at. The wait counted
         * is {@code max-backoff}, not the doubling schedule: a {@code Retry-After} is honoured on
         * every retryable answer and the ceiling is the only thing bounding what a remote service
         * can ask for.
         */
        @Test
        void the_waits_between_attempts_should_count_towards_the_deadline() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    // 45s of reads, and two 70s waits between the three attempts, is 185s — inside
                    // the deadline on its own, and not inside the run it shares.
                    "yotresultsdistribution.referencedata.initial-backoff=70s",
                    "yotresultsdistribution.referencedata.max-backoff=70s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.referencedata")
                                .hasMessageContaining("yotresultsdistribution.claim.processing-deadline");
                    });
        }

        /**
         * The same reading of the bound the payload rule takes: a read that fills the deadline
         * exactly leaves the rest of the run nothing, because the run only tests the deadline once
         * the read has returned.
         */
        @Test
        void a_read_that_exactly_fills_the_deadline_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.referencedata.max-attempts=2",
                    "yotresultsdistribution.referencedata.initial-backoff=10s",
                    "yotresultsdistribution.referencedata.max-backoff=10s",
                    // Two attempts of 5s + 110s, with a 10s wait between them, is the 4m deadline
                    // to the second.
                    "yotresultsdistribution.referencedata.read-timeout=110s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.referencedata")
                                .hasMessageContaining("yotresultsdistribution.claim.processing-deadline");
                    });
        }

        /**
         * A read that finishes half a second inside the deadline satisfies the rule above and is
         * refused anyway, by the budget the whole run shares: a step that leaves nothing for the
         * payload fetch, the submission and the guard's writes has not finished inside the run, it
         * has finished inside the deadline and taken the rest of the run's time with it. Which is
         * why there are two rules and not one.
         */
        @Test
        void a_read_that_leaves_the_rest_of_the_run_nothing_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.referencedata.max-attempts=2",
                    "yotresultsdistribution.referencedata.initial-backoff=10s",
                    "yotresultsdistribution.referencedata.max-backoff=10s",
                    "yotresultsdistribution.referencedata.read-timeout=PT109.5S").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.referencedata")
                                .hasMessageContaining("yotresultsdistribution.claim.processing-deadline");
                    });
        }

        /**
         * The timing rule belongs to the live adapter, which STUB does not build — the same reason
         * the endpoint and the identity are not asked of a stub run.
         */
        @Test
        void stub_mode_should_not_be_held_to_the_live_read_timings() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.payload.mode=STUB",
                    "yotresultsdistribution.referencedata.mode=STUB",
                    "yotresultsdistribution.referencedata.max-attempts=10",
                    "yotresultsdistribution.referencedata.read-timeout=1m")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("the payload settings must describe a source that can answer")
    class PayloadReachability {

        @Test
        void a_fallback_with_no_attempts_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.payload.fallback.max-attempts=0").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(
                                        "yotresultsdistribution.payload.fallback.max-attempts");
                    });
        }

        @Test
        void a_single_attempt_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.payload.fallback.max-attempts=1")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        void a_negative_initial_backoff_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.payload.fallback.initial-backoff=-1s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(
                                        "yotresultsdistribution.payload.fallback.initial-backoff");
                    });
        }

        /** The same shared rule the reference-data block is held to, on the third client. */
        @Test
        void a_ceiling_below_the_first_wait_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.payload.fallback.initial-backoff=10s",
                    "yotresultsdistribution.payload.fallback.max-backoff=5s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.payload.fallback.max-backoff")
                                .hasMessageContaining(
                                        "yotresultsdistribution.payload.fallback.initial-backoff");
                    });
        }

        @Test
        void a_timeout_that_never_expires_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.payload.redis.command-timeout=0s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(
                                        "yotresultsdistribution.payload.redis.command-timeout");
                    });
        }

        @Test
        void a_cache_with_no_address_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.payload.redis.host=").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.payload.redis.host");
                    });
        }

        /**
         * {@code INT_} is the prefix the producer writes this flow's payload under, and an empty one
         * reads a key nobody writes.
         */
        @Test
        void a_cache_with_no_key_prefix_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.payload.redis.key-prefix=").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.payload.redis.key-prefix");
                    });
        }

        /**
         * The fetch happens inside the run, and the run must stop before its claim can be reclaimed.
         * A fallback whose own worst case outlasts the processing deadline therefore guarantees the
         * thing the deadline exists to prevent: a runner still waiting on a socket while another
         * runner takes its request.
         */
        @Test
        void a_fallback_that_can_outlast_the_processing_deadline_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.claim.processing-deadline=1m",
                    "yotresultsdistribution.payload.fallback.read-timeout=30s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.payload.fallback")
                                .hasMessageContaining("yotresultsdistribution.claim.processing-deadline");
                    });
        }

        @Test
        void a_fallback_that_finishes_inside_the_processing_deadline_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY)
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * The fallback is not the whole fetch. Two cache reads precede it — the dated key and the
         * legacy undated twin — and each of them can spend its connect and command timeouts before
         * the query side is asked at all. A budget that counts only the HTTP half licences a fetch
         * that overruns the deadline by everything the cache cost.
         */
        @Test
        void a_fetch_whose_cache_reads_push_it_past_the_deadline_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.claim.processing-deadline=2m",
                    "yotresultsdistribution.payload.redis.connect-timeout=5s",
                    "yotresultsdistribution.payload.redis.command-timeout=5s",
                    "yotresultsdistribution.payload.fallback.max-attempts=1",
                    "yotresultsdistribution.payload.fallback.connect-timeout=5s",
                    // 105s of query side alone fits inside 120s; the 20s of cache reads in front of
                    // it does not.
                    "yotresultsdistribution.payload.fallback.read-timeout=100s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.payload")
                                .hasMessageContaining("yotresultsdistribution.claim.processing-deadline");
                    });
        }

        /**
         * A fetch that fills the deadline exactly leaves the rest of the run nothing, and the run
         * only checks the deadline once the fetch has returned.
         */
        @Test
        void a_fetch_that_exactly_fills_the_deadline_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.claim.processing-deadline=2m",
                    "yotresultsdistribution.payload.redis.connect-timeout=5s",
                    "yotresultsdistribution.payload.redis.command-timeout=5s",
                    "yotresultsdistribution.payload.fallback.max-attempts=1",
                    "yotresultsdistribution.payload.fallback.connect-timeout=5s",
                    // 20s of cache reads plus 100s of query side is the deadline to the second.
                    "yotresultsdistribution.payload.fallback.read-timeout=95s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.payload")
                                .hasMessageContaining("yotresultsdistribution.claim.processing-deadline");
                    });
        }

        /**
         * The same relationship the reference-data cases show, on the fetch: 119s of payload fetch
         * is one second inside a two-minute deadline by the per-step rule, and leaves the
         * now-subscriptions read, the submission and the guard's writes one second between them.
         * The run's shared budget is what refuses it.
         */
        @Test
        void a_fetch_that_leaves_the_rest_of_the_run_nothing_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.claim.processing-deadline=2m",
                    "yotresultsdistribution.payload.redis.connect-timeout=5s",
                    "yotresultsdistribution.payload.redis.command-timeout=5s",
                    "yotresultsdistribution.payload.fallback.max-attempts=1",
                    "yotresultsdistribution.payload.fallback.connect-timeout=5s",
                    "yotresultsdistribution.payload.fallback.read-timeout=94s",
                    "yotresultsdistribution.progression.max-attempts=1").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.payload")
                                .hasMessageContaining("yotresultsdistribution.claim.processing-deadline");
                    });
        }

        /**
         * The cache and the query side belong to the live source, and STUB selects neither bean.
         * Holding a local stub run to settings nothing will read fails a run that is configured
         * exactly as it means to be.
         */
        @Test
        void stub_mode_should_not_be_held_to_the_live_payload_settings() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.payload.mode=STUB",
                    "yotresultsdistribution.payload.redis.host=",
                    "yotresultsdistribution.payload.fallback.max-attempts=0")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    /**
     * The submission must be able to make the call the whole service exists to make.
     *
     * <p>These settings fail in the same quiet way the timing rules do. A {@code max-attempts} of
     * zero attempts no POST at all: the loop that would send the register never runs, every hearing
     * comes back transient, and the queue fills with deliveries that were never even tried — the
     * silent non-delivery this service exists to remove, wearing a retry policy's clothes. A
     * negative wait reaches {@code Thread.sleep} and throws from inside the retry, and a ceiling
     * below the first wait is a bound that shortens the very back-off it is meant to bound.
     *
     * <p>The endpoint and the identity are asked of a configuration that can actually reach the
     * POST — a live payload source. A deployed pod is always one of those, because the stub payload
     * source is itself refused on the deployed credential source, so no deployment escapes the rule
     * through the exemption a local stub run relies on.
     */
    @Nested
    @DisplayName("the submission must be able to post the register")
    class SubmissionPolicy {

        @Test
        void a_live_pipeline_without_a_submission_identity_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.payload.mode=LIVE",
                    "yotresultsdistribution.progression.system-user-id=").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.progression.system-user-id")
                                .hasMessageContaining("yotresultsdistribution.payload.mode");
                    });
        }

        @Test
        void a_live_pipeline_without_a_submission_endpoint_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.payload.mode=LIVE",
                    "yotresultsdistribution.progression.base-url=").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.progression.base-url")
                                .hasMessageContaining("yotresultsdistribution.payload.mode");
                    });
        }

        /** A local stub run fetches no hearing, so it reaches no POST and needs no identity. */
        @Test
        void a_stubbed_pipeline_without_an_endpoint_or_an_identity_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.payload.mode=STUB",
                    "yotresultsdistribution.progression.base-url=",
                    "yotresultsdistribution.progression.system-user-id=")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        void no_attempts_at_all_should_fail_startup_rather_than_post_nothing() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.progression.max-attempts=0").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.progression.max-attempts");
                    });
        }

        @Test
        void a_negative_attempt_count_should_fail_startup_the_same_way() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.progression.max-attempts=-1").run(context -> {
                        assertThat(context).hasFailed();
                        // "The same way" is the point of the case, so it is asserted rather than
                        // asserted-about: the refusal names the setting and the floor it is under,
                        // exactly as the zero case's does.
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.progression.max-attempts")
                                .hasMessageContaining("-1")
                                .hasMessageContaining("must be at least 1");
                    });
        }

        @Test
        void a_single_attempt_should_start_because_no_retry_is_a_policy_too() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.progression.max-attempts=1").run(context ->
                            assertThat(context).hasNotFailed());
        }

        @Test
        void a_negative_initial_backoff_should_fail_startup_rather_than_throw_mid_retry() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.progression.initial-backoff=-1s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.progression.initial-backoff");
                    });
        }

        @Test
        void a_ceiling_below_the_first_wait_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.progression.initial-backoff=10s",
                    "yotresultsdistribution.progression.max-backoff=5s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.progression.max-backoff")
                                .hasMessageContaining("yotresultsdistribution.progression.initial-backoff");
                    });
        }

        @Test
        void a_timeout_that_never_expires_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.progression.read-timeout=0s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.progression.read-timeout");
                    });
        }

        /**
         * The POST happens inside the same run the payload fetch and the reference-data read happen
         * in, so its own worst case has to fit inside the processing deadline for the same reason
         * theirs do: a runner still waiting on a socket when its claim becomes reclaimable is a
         * second runner processing the same hearing.
         */
        @Test
        void a_submission_that_can_outlast_the_processing_deadline_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.progression.max-attempts=10",
                    "yotresultsdistribution.progression.read-timeout=1m").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.progression")
                                .hasMessageContaining("yotresultsdistribution.claim.processing-deadline");
                    });
        }

        /**
         * The back-off waits are spent inside the run as surely as the reads are, and nothing else
         * bounds them: four attempts of 5s + 10s is 60s and comfortably inside the 4m deadline until
         * the three waits between them are lengthened.
         */
        @Test
        void the_back_off_waits_should_count_towards_the_deadline() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    // 60s of attempts, and three 90s waits between them, is 330s.
                    "yotresultsdistribution.progression.initial-backoff=90s",
                    "yotresultsdistribution.progression.max-backoff=90s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.progression")
                                .hasMessageContaining("yotresultsdistribution.claim.processing-deadline");
                    });
        }

        /** The same reading of the bound every other timing rule takes: reached is already too far. */
        @Test
        void a_submission_that_exactly_fills_the_deadline_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.progression.max-attempts=2",
                    "yotresultsdistribution.progression.initial-backoff=10s",
                    "yotresultsdistribution.progression.max-backoff=20s",
                    "yotresultsdistribution.progression.connect-timeout=5s",
                    // Two attempts of 5s + 110s, with the single 10s wait between them, is the 4m
                    // deadline to the second.
                    "yotresultsdistribution.progression.read-timeout=110s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.progression")
                                .hasMessageContaining("yotresultsdistribution.claim.processing-deadline");
                    });
        }

        /**
         * And the same again on the POST. Half a second inside the deadline by the rule above, and
         * refused by the budget it shares with the two reads that precede it.
         */
        @Test
        void a_submission_that_leaves_the_rest_of_the_run_nothing_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.progression.max-attempts=2",
                    "yotresultsdistribution.progression.initial-backoff=10s",
                    "yotresultsdistribution.progression.max-backoff=20s",
                    "yotresultsdistribution.progression.connect-timeout=5s",
                    "yotresultsdistribution.progression.read-timeout=PT109.5S").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.progression")
                                .hasMessageContaining("yotresultsdistribution.claim.processing-deadline");
                    });
        }

        @Test
        void the_shipped_defaults_should_satisfy_their_own_rules() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY)
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    /**
     * The pre-send contract validation that fix C29 exists to add.
     *
     * <p>It is the difference between a schema-invalid document becoming an explicit, attributable
     * FAILED and becoming a 400 from progression that the legacy pipeline swallowed — the single
     * behaviour that lost whole registers most often. A deployed pod that has it switched off is
     * back in the legacy failure mode with none of the legacy's excuses, so startup refuses it.
     */
    @Nested
    @DisplayName("the outbound contract validator is never off where the service is deployed")
    class OutboundValidation {

        @Test
        void it_should_be_on_unless_something_turns_it_off() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(YotResultsDistributionProperties.class)
                        .submission().validateOutbound()).isTrue();
            });
        }

        @Test
        void disabling_it_on_the_deployed_credential_source_should_fail_startup() {
            runner.withPropertyValues(NAMESPACE_PROPERTY,
                    "yotresultsdistribution.submission.validate-outbound=false").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.submission.validate-outbound")
                                .hasMessageContaining("yotresultsdistribution.servicebus.namespace");
                    });
        }

        /**
         * Local and CI runs may turn it off — a fixture that deliberately sends a shape the vendored
         * schemas reject has to be able to reach the wire to prove what happens next.
         */
        @Test
        void disabling_it_on_the_local_credential_source_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.submission.validate-outbound=false").run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(YotResultsDistributionProperties.class)
                                .submission().validateOutbound()).isFalse();
                    });
        }

        @Test
        void leaving_it_on_where_the_service_is_deployed_should_start() {
            runner.withPropertyValues(NAMESPACE_PROPERTY,
                    "yotresultsdistribution.submission.validate-outbound=true")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    /**
     * The whole run's budget, not three budgets that each look reasonable on their own.
     *
     * <p>Every per-step rule above asks the same question of one step: can this one outlast the
     * processing deadline? Answering "no" three times does not answer the question that matters,
     * because the three steps are spent inside <strong>one</strong> run and one claim. A payload
     * fetch, a now-subscriptions read and a submission that each fit comfortably can add up to more
     * than twice the deadline, and the run that spends them is a runner still holding a socket while
     * its claim is reclaimed and a second delivery starts the same request — which, for a flow whose
     * POST progression <em>appends</em> rather than replaces, is a second register for the hearing.
     *
     * <p>The margin is the rest of the run: the guard's admission and outcome writes, and the
     * transformation between the reads. It is fixed rather than configured because nothing about it
     * is an environment's choice.
     */
    @Nested
    @DisplayName("every step together must fit inside the run")
    class CumulativeRunBudget {

        /**
         * The case the per-step rules cannot see. 127s of payload fetch, 107s of now-subscriptions
         * read and 143.5s of submission are each inside a four-minute deadline; together they are
         * 377.5s, and the run that spends them outlives its claim by more than a minute.
         */
        @Test
        void three_steps_that_each_fit_but_do_not_fit_together_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.claim.lease=5m",
                    "yotresultsdistribution.claim.processing-deadline=4m",
                    "yotresultsdistribution.payload.fallback.read-timeout=30s",
                    "yotresultsdistribution.referencedata.read-timeout=30s",
                    "yotresultsdistribution.progression.read-timeout=30s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.payload")
                                .hasMessageContaining("yotresultsdistribution.referencedata")
                                .hasMessageContaining("yotresultsdistribution.progression")
                                .hasMessageContaining("yotresultsdistribution.claim.processing-deadline");
                    });
        }

        /**
         * The margin counts. These three steps sum to 177.5s, comfortably inside a 200s deadline —
         * and leave the guard's two writes and the whole transformation twenty-two seconds, which is
         * the shape of budget that overruns in production and looks correct in review.
         */
        @Test
        void a_budget_with_no_room_for_the_rest_of_the_run_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.claim.processing-deadline=200s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.claim.processing-deadline");
                    });
        }

        /**
         * The shipped numbers have to satisfy the rule they are shipped under, or the service ships
         * unable to start.
         */
        @Test
        void the_shipped_settings_should_leave_room_for_every_step_and_the_margin() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY)
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * A step no adapter makes costs the run nothing, which is the same reason the per-step rules
         * are asked only of the source actually selected.
         */
        @Test
        void a_step_the_deployment_stubs_out_should_not_be_counted() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.claim.processing-deadline=2m",
                    "yotresultsdistribution.payload.mode=STUB",
                    "yotresultsdistribution.referencedata.mode=STUB",
                    "yotresultsdistribution.payload.fallback.read-timeout=5m",
                    "yotresultsdistribution.referencedata.read-timeout=5m")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    /**
     * The schedule the requirement is written in wall-clock terms.
     *
     * <p>18:00 in the court's own zone, in BST and GMT alike. The legacy fires in the scheduling
     * JVM's default zone, because its trigger is built without one, and that ambiguity is not
     * inherited: the zone is a value this service states and startup holds it to. The check is
     * unconditional - a job that happens to be disabled in this deployment is not a reason to accept
     * a schedule that would run at the wrong hour in the next one.
     */
    @Nested
    @DisplayName("the nightly run happens at 18:00 in the court's own zone")
    class GenerationSchedule {

        /**
         * The binding half of the rule, and the reason there is no separate scheduling suite: what
         * the annotation reads is what these two settings bind to, so the settings are what is
         * pinned.
         */
        @Test
        void job_is_scheduled_in_europe_london() {
            generating.run(context -> {
                assertThat(context).hasNotFailed();
                final GenerationProperties generation =
                        context.getBean(GenerationProperties.class);
                assertThat(generation.cron()).isEqualTo("0 0 18 * * MON-FRI");
                assertThat(generation.zone()).isEqualTo("Europe/London");
            });
        }

        @Test
        void another_zone_without_an_acknowledgement_should_fail_startup() {
            generating.withPropertyValues("yotresultsdistribution.generation.zone=Europe/Paris")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.generation.zone")
                                .hasMessageContaining("Europe/London")
                                .hasMessageContaining(
                                        "yotresultsdistribution.generation.zone-override-acknowledged");
                    });
        }

        /**
         * The override exists so that moving the run is a deliberate, reviewable act rather than a
         * typo nobody notices until the registers arrive an hour late.
         */
        @Test
        void another_zone_with_an_acknowledgement_should_start() {
            generating.withPropertyValues("yotresultsdistribution.generation.zone=Europe/Paris",
                    "yotresultsdistribution.generation.zone-override-acknowledged=true")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        void a_wrong_zone_should_be_refused_even_with_the_job_disabled() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.generation.zone=Europe/Paris").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.generation.zone");
                    });
        }
    }

    /**
     * The lock over the nightly run has to outlast the run it locks.
     *
     * <p>The job holds a ShedLock lock so a scaled deployment cannot generate the same night twice,
     * and the lock expires on its own after {@code lock-at-most-for} whether the run has finished
     * or not - that is what makes it safe against a pod that dies mid-run. The two settings are
     * therefore one arrangement: a deployment that lengthens the run deadline and leaves the lock
     * where it was has a window in which a run is still inside its hour and the lock it was holding
     * is free for another replica to take. What comes out of that is two documents and two e-mails
     * for every court centre in the country, and nothing about the deployment looks wrong until it
     * happens.
     *
     * <p>The same shape as the broker's own renewal rule above, and refused the same way: at
     * startup, naming both settings and the margin between them, rather than at 18:00 on the night
     * a run happens to be slow.
     */
    @Nested
    @DisplayName("the run's lock must outlast the run it locks")
    class SchedulerLockAgainstRunDeadline {

        @Test
        void a_run_deadline_the_lock_cannot_cover_refuses_to_start() {
            generating.withPropertyValues("yotresultsdistribution.generation.run-deadline=90m")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.generation.lock-at-most-for")
                                .hasMessageContaining("yotresultsdistribution.generation.run-deadline");
                    });
        }

        /**
         * The margin is fixed rather than configured, so a deployment cannot set it to nothing:
         * a lock that expires the instant the deadline does is a lock the last batch of a run
         * races.
         */
        @Test
        void a_lock_that_only_just_covers_the_run_refuses_to_start() {
            generating.withPropertyValues("yotresultsdistribution.generation.run-deadline=60m",
                    "yotresultsdistribution.generation.lock-at-most-for=65m")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.generation.lock-at-most-for");
                    });
        }

        @Test
        void a_longer_run_with_a_lock_lengthened_to_match_should_start() {
            generating.withPropertyValues("yotresultsdistribution.generation.run-deadline=90m",
                    "yotresultsdistribution.generation.lock-at-most-for=100m")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * Unconditional, like the zone rule beside it: a job that happens to be disabled in this
         * deployment is not a reason to accept a lock that cannot cover the run in the next one.
         */
        @Test
        void a_lock_shorter_than_the_run_is_refused_even_with_the_job_disabled() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.generation.run-deadline=90m").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.generation.lock-at-most-for");
                    });
        }

        @Test
        void the_shipped_lock_should_cover_the_shipped_run_deadline() {
            generating.run(context -> {
                assertThat(context).hasNotFailed();
                final GenerationProperties generation =
                        context.getBean(GenerationProperties.class);
                assertThat(generation.lockAtMostFor())
                        .as("the value the job's @SchedulerLock reads, and the one the run deadline "
                                + "is checked against")
                        .isEqualTo(generation.runDeadline()
                                .plus(PropertiesValidator.SCHEDULER_LOCK_MARGIN));
            });
        }
    }

    /**
     * The notifying leg's claim has to outlast one recipient's POST cycle, twice over.
     *
     * <p>A notification claim's lease is a bound on work whose length it cannot know from itself: a
     * batch is addressed to as many Youth Offending Teams as subscribed to its court centre, and
     * each of them costs up to {@code max-attempts} POSTs with a connect timeout, a read timeout and
     * a back-off wait apiece. The lease is renewed before every POST and before every write, so what
     * it has to cover is the retry cycle one recipient's turn can become - and a lease shorter than
     * that expires under a notifier still waiting on a socket, after which a second notifier takes
     * the batch over and the team is sent a register about children twice.
     *
     * <p>So the rule is the transport's own worst case for one recipient, the same arithmetic the
     * per-step run budgets are computed from -
     * {@code max-attempts x (connect-timeout + read-timeout) + (max-attempts - 1) x max-backoff} -
     * doubled as margin. <strong>The connect timeout is part of it</strong>, because a POST that
     * hangs on the connect and then on the read is the longest single thing this leg does and a
     * bound that charged only the read would licence a lease the first such attempt outlives; and
     * the waits are the gaps <em>between</em> attempts, of which there is one fewer than there are
     * attempts. Unconditional, like the zone and lock rules: a job that happens to be disabled in
     * this deployment is not a reason to accept a lease that cannot cover a POST cycle in the next
     * one.
     */
    @Nested
    @DisplayName("the notification claim must outlast one recipient's POST cycle")
    class NotificationLeaseAgainstOnePostCycle {

        @Test
        void a_lease_shorter_than_one_post_cycle_refuses_to_start() {
            generating.withPropertyValues("yotresultsdistribution.notification.claim-lease=30s")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.notification.claim-lease")
                                .hasMessageContaining("yotresultsdistribution.endpoints.max-attempts")
                                .hasMessageContaining("yotresultsdistribution.endpoints.connect-timeout")
                                .hasMessageContaining("yotresultsdistribution.endpoints.read-timeout")
                                .hasMessageContaining("yotresultsdistribution.endpoints.max-backoff");
                    });
        }

        /**
         * The margin is fixed rather than configured, for the reason the scheduler lock's is: a
         * lease that expires the instant the longest POST cycle does is a lease that recipient
         * races.
         *
         * <p>Forty-nine seconds is that cycle exactly at the shipped transport: three attempts of a
         * five-second connect and a ten-second read, with two two-second waits between them.
         */
        @Test
        void a_lease_that_only_just_covers_one_post_cycle_refuses_to_start() {
            generating.withPropertyValues("yotresultsdistribution.notification.claim-lease=49s")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.notification.claim-lease");
                    });
        }

        @Test
        void a_lease_that_covers_one_post_cycle_twice_over_should_start() {
            generating.withPropertyValues("yotresultsdistribution.notification.claim-lease=98s")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * The connect timeout is half of what one attempt can cost, and the half a lease is likeliest
         * to be short of.
         *
         * <p>A POST that hangs on the connect and then on the read is the longest single thing this
         * leg does, and it is the shape a claim is really lost inside: a notifier waiting on a
         * socket is a notifier not renewing. A bound computed from the read timeout alone said a
         * five-minute connect cost nothing at all, so a deployment could lengthen it and keep a
         * lease that the very first attempt of the night outlives - after which a second notifier
         * takes the batch over and a court centre's register goes out twice.
         */
        @Test
        void a_connect_timeout_the_lease_cannot_cover_refuses_to_start() {
            generating.withPropertyValues("yotresultsdistribution.endpoints.connect-timeout=5m")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.notification.claim-lease")
                                .hasMessageContaining("yotresultsdistribution.endpoints.connect-timeout");
                    });
        }

        /**
         * And the rule is a relationship, so lengthening the transport is what breaks it in
         * practice: nobody sets a short lease deliberately, and a deployment that gives
         * notificationnotify five minutes to answer has quietly made the shipped lease too short.
         */
        @Test
        void a_transport_the_shipped_lease_cannot_cover_refuses_to_start() {
            generating.withPropertyValues("yotresultsdistribution.endpoints.read-timeout=5m")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.notification.claim-lease");
                    });
        }

        @Test
        void a_lease_the_transport_outgrew_is_refused_even_with_the_job_disabled() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.endpoints.read-timeout=5m").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.notification.claim-lease");
                    });
        }

        @Test
        void the_shipped_lease_should_cover_the_shipped_transport_twice_over() {
            generating.run(context -> {
                assertThat(context).hasNotFailed();
                final YotResultsDistributionProperties properties =
                        context.getBean(YotResultsDistributionProperties.class);
                final YotResultsDistributionProperties.Endpoints endpoints = properties.endpoints();

                assertThat(properties.notification().claimLease())
                        .as("the shipped lease against the shipped transport's worst case for one "
                                + "recipient, doubled: %s attempts of a %s connect and a %s read, "
                                + "with a %s wait between two of them",
                                endpoints.maxAttempts(), endpoints.connectTimeout(),
                                endpoints.readTimeout(), endpoints.maxBackoff())
                        .isGreaterThanOrEqualTo(endpoints.connectTimeout()
                                .plus(endpoints.readTimeout())
                                .multipliedBy(endpoints.maxAttempts())
                                .plus(endpoints.maxBackoff()
                                        .multipliedBy(endpoints.maxAttempts() - 1L))
                                .multipliedBy(PropertiesValidator.NOTIFICATION_LEASE_MARGIN));
            });
        }
    }

    /**
     * Enabling the downstream half is enabling everything it depends on.
     *
     * <p>Each of these is the same failure wearing a different name: the pod starts, reports itself
     * healthy, waits until 18:00 and then cannot store the payload, cannot read the flag, cannot ask
     * for a render or cannot send an e-mail. The registers are not late, they are simply never
     * produced, and the first anybody hears of it is a Youth Offending Team asking where the
     * register is. Every one of these settings arrives from the deployment, so every one of them is
     * a deploy that should have failed.
     */
    @Nested
    @DisplayName("generation cannot be enabled without the downstreams it needs")
    class GenerationDownstreams {

        @Test
        void enabling_generation_without_a_payload_store_should_fail_startup() {
            generating.withPropertyValues("yotresultsdistribution.fileservice.url=").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasMessageContaining("yotresultsdistribution.fileservice.url")
                        .hasMessageContaining("yotresultsdistribution.generation.enabled");
            });
        }

        @Test
        void enabling_generation_without_a_flag_connection_string_should_fail_startup() {
            generating.withPropertyValues(FLAG_CONNECTION_STRING + "=").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasMessageContaining(FLAG_CONNECTION_STRING)
                        .hasMessageContaining("yotresultsdistribution.generation.enabled");
            });
        }

        /**
         * A connection string no client can be built from, or one that could be built and would
         * reach nothing.
         *
         * <p>The shape is this validator's to refuse rather than the SDK's: the builder throws while
         * {@code LiveFeatureFlagConfig.featureFlagReader} is built, during refresh, on an exception
         * that names no setting of this service's - and may quote the value it could not parse,
         * which is the one value this service holds that must never be quoted. {@code http://:}
         * is worse still, because the builder accepts it: every read at 18:00 fails to connect and
         * reads exactly like a store outage. So each shape below is refused under the setting's
         * name, and the refusal carries no part of what was supplied.
         */
        @ParameterizedTest
        @ValueSource(strings = {
            "Endpoint=yot-results-distribution-ste86.azconfig.io;Id=ste-id;Secret=" + DEPLOYED_SECRET,
            "Endpoint=wiremock:8080;Id=ste-id;Secret=" + DEPLOYED_SECRET,
            "Endpoint=https:/wiremock:8080;Id=ste-id;Secret=" + DEPLOYED_SECRET,
            "Endpoint=http://foo:bad;Id=ste-id;Secret=" + DEPLOYED_SECRET,
            "Endpoint=http://:;Id=ste-id;Secret=" + DEPLOYED_SECRET,
            "Id=ste-id;Secret=" + DEPLOYED_SECRET,
            "Endpoint=https://yot-results-distribution-ste86.azconfig.io;Secret=" + DEPLOYED_SECRET,
            "Endpoint=https://yot-results-distribution-ste86.azconfig.io;Id=ste-id",
            "Endpoint=https://yot-results-distribution-ste86.azconfig.io;Id=ste-id;Secret=",
            "Endpoint=https://yot-results-distribution-ste86.azconfig.io;Id=ste-id;Secret=not*base64!",
            "Endpoint =https://yot-results-distribution-ste86.azconfig.io;Id=ste-id;Secret="
                    + DEPLOYED_SECRET,
            "Endpoint=https://yot-results-distribution-ste86.azconfig.io;Id =ste-id;Secret="
                    + DEPLOYED_SECRET,
            "Endpoint=https://yot-results-distribution-ste86.azconfig.io;Id=ste-id;Secret ="
                    + DEPLOYED_SECRET,
            "Endpoint=  https://appconfig.internal;Id=ste-id;Secret=" + DEPLOYED_SECRET,
            "not-a-connection-string-" + DEPLOYED_SECRET,
        })
        void enabling_generation_with_a_flag_connection_string_that_cannot_be_read_should_fail_startup(
                final String connectionString) {

            generating.withPropertyValues(FLAG_CONNECTION_STRING + "=" + connectionString)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(FLAG_CONNECTION_STRING)
                                .hasMessageNotContaining(DEPLOYED_SECRET)
                                .hasMessageNotContaining("ste-id")
                                .hasMessageNotContaining("yot-results-distribution-ste86")
                                .hasMessageNotContaining("appconfig.internal")
                                .hasMessageNotContaining(connectionString);
                    });
        }

        /**
         * The shape is asked of any string that is set, not only of one a nightly job will read.
         *
         * <p>The reader is built on every pod whatever generation says, and the SDK's builder
         * quotes the whole value when it cannot parse it. So a pod with the job off that has been
         * given an unreadable string is refused here, under the setting's name, rather than there.
         */
        @ParameterizedTest
        @ValueSource(strings = {
            "Endpoint=https://yot-results-distribution-ste86.azconfig.io;Id=ste-id",
            "Endpoint=https://yot-results-distribution-ste86.azconfig.io;Id=ste-id;Secret=not*base64!",
            "Endpoint =https://yot-results-distribution-ste86.azconfig.io;Id=ste-id;Secret="
                    + DEPLOYED_SECRET,
        })
        void a_flag_connection_string_that_cannot_be_read_should_fail_startup_with_generation_off(
                final String connectionString) {

            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                            FLAG_CONNECTION_STRING + "=" + connectionString)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(FLAG_CONNECTION_STRING)
                                .hasMessageNotContaining(DEPLOYED_SECRET)
                                .hasMessageNotContaining("ste-id")
                                .hasMessageNotContaining("yot-results-distribution-ste86");
                    });
        }

        /**
         * A real store, or any store a deployed pod reads, is reached over TLS.
         *
         * <p>HMAC does not send the secret, but the signed request and the flag's answer would travel
         * in the clear, and the store refuses plain HTTP anyway - which reads at 18:00 as an
         * unreadable flag, a skipped run indistinguishable from an outage.
         */
        @Test
        void a_real_store_over_plain_http_should_fail_startup() {
            generating.withPropertyValues(FLAG_CONNECTION_STRING
                            + "=Endpoint=http://yot-results-distribution-ste86.azconfig.io;Id=ste-id;Secret="
                            + DEPLOYED_SECRET)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(FLAG_CONNECTION_STRING)
                                .hasMessageContaining("https")
                                .hasMessageNotContaining(DEPLOYED_SECRET)
                                .hasMessageNotContaining("yot-results-distribution-ste86");
                    });
        }

        @Test
        void a_deployed_pod_reading_its_store_over_plain_http_should_fail_startup() {
            runner.withPropertyValues(NAMESPACE_PROPERTY, FLAG_CONNECTION_STRING
                            + "=Endpoint=http://appconfig.internal;Id=ste-id;Secret=" + DEPLOYED_SECRET)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(FLAG_CONNECTION_STRING)
                                .hasMessageContaining("https")
                                .hasMessageNotContaining(DEPLOYED_SECRET)
                                .hasMessageNotContaining("appconfig.internal");
                    });
        }

        @Test
        void a_deployed_pod_reading_its_store_over_https_should_start() {
            runner.withPropertyValues(NAMESPACE_PROPERTY, FLAG_CONNECTION_STRING
                            + "=Endpoint=https://yot-results-distribution-ste86.azconfig.io;Id=ste-id;Secret="
                            + DEPLOYED_SECRET)
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /** Plain HTTP stays the local loop's: a stub that is neither a real store nor deployed. */
        @Test
        void a_stub_over_plain_http_should_start() {
            generating.withPropertyValues(FLAG_CONNECTION_STRING
                            + "=Endpoint=http://wiremock:8080;Id=ste-id;Secret=" + DEPLOYED_SECRET)
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * With the live reader in the context, the failure is still one this service wrote.
         *
         * <p>The validator's refusal and the builder's are both under the setting's name; which one
         * a start-up meets is bean order, so neither may depend on the other having run. Nothing in
         * any exception on the way out may carry a part of the value.
         */
        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void a_context_with_the_live_reader_should_refuse_an_unreadable_string_quoting_none_of_it(
                final boolean generationEnabled) {

            final ApplicationContextRunner withTheLiveReader = (generationEnabled ? generating : runner
                    .withPropertyValues(CONNECTION_STRING_PROPERTY))
                    .withUserConfiguration(LiveFeatureFlagConfig.class);
            withTheLiveReader.withPropertyValues(FLAG_CONNECTION_STRING
                            + "=Endpoint=https://yot-results-distribution-ste86.azconfig.io;Id=ste-id;Secret=not*base64!")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasStackTraceContaining(FLAG_CONNECTION_STRING);
                        for (Throwable link = context.getStartupFailure(); link != null;
                                link = link.getCause()) {
                            assertThat(String.valueOf(link.getMessage()))
                                    .as("every exception on the way out, not only the outermost")
                                    .doesNotContain("not*base64!")
                                    .doesNotContain("ste-id")
                                    .doesNotContain("yot-results-distribution-ste86");
                        }
                    });
        }

        /**
         * The label is how one App Configuration store serves every stack, so an unlabelled read is
         * not a read of this stack's flag - it is a read of somebody else's, or of none.
         */
        @Test
        void enabling_generation_without_a_flag_label_should_fail_startup() {
            generating.withPropertyValues("yotresultsdistribution.feature.label=").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasMessageContaining("yotresultsdistribution.feature.label")
                        .hasMessageContaining("yotresultsdistribution.generation.enabled");
            });
        }

        @Test
        void enabling_generation_without_a_renderer_endpoint_should_fail_startup() {
            generating.withPropertyValues("yotresultsdistribution.endpoints.systemdocgenerator=")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(
                                        "yotresultsdistribution.endpoints.systemdocgenerator")
                                .hasMessageContaining("yotresultsdistribution.generation.enabled");
                    });
        }

        @Test
        void enabling_generation_without_a_notifier_endpoint_should_fail_startup() {
            generating.withPropertyValues("yotresultsdistribution.endpoints.notificationnotify=")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(
                                        "yotresultsdistribution.endpoints.notificationnotify")
                                .hasMessageContaining("yotresultsdistribution.generation.enabled");
                    });
        }

        @Test
        void a_fully_configured_generation_deployment_should_start() {
            generating.run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * The conditionality is the point of the rule and not an accident of it. A local run and
         * every plain context-load test leave all of these unset, and they are configured exactly as
         * they mean to be: with the job, the listener and the second datasource absent, there is
         * nothing to store a payload in, nothing to render and nobody to notify.
         */
        @Test
        void leaving_generation_disabled_should_require_none_of_them() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY)
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    /**
     * Fix P9: the e-mail template id is a deployment fact, discovered at deploy time.
     *
     * <p>The legacy resolved it per recipient and, finding it blank, logged
     * {@code "Court register notification is not sent due to missing template Id"} at INFO and moved
     * on to the next one. The e-mail was never sent, nothing recorded that it had not been, and the
     * batch reported the state it would have reported had every recipient been e-mailed. A whole
     * evening's registers can be lost that way to a value nobody set, and the loss is invisible.
     *
     * <p>So the id is validated for shape at startup rather than for presence at send time: blank
     * or not a UUID is a refusal to start. A deployment that cannot start is a deployment that gets
     * fixed within the hour.
     */
    @Nested
    @DisplayName("the register e-mail's template id is checked at startup, not at 18:00")
    class EmailTemplate {

        @Test
        void blank_email_template_refuses_to_start_in_live_mode() {
            generating.withPropertyValues("yotresultsdistribution.email.templates.cr_standard=")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.email.templates.cr_standard")
                                .hasMessageContaining("yotresultsdistribution.generation.nn-mode");
                    });
        }

        /**
         * A malformed id fails in exactly the same way a blank one does, and later: notificationnotify
         * refuses the command, every recipient of every batch, on a value that was wrong before the
         * pod ever started.
         */
        @Test
        void a_template_id_that_is_not_a_uuid_should_fail_startup() {
            generating.withPropertyValues(
                    "yotresultsdistribution.email.templates.cr_standard=cr-standard-template")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.email.templates.cr_standard")
                                .hasMessageContaining("cr-standard-template");
                    });
        }

        @Test
        void the_configured_template_should_be_the_one_the_notifier_is_given() {
            generating.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(YotResultsDistributionProperties.class)
                        .email().templates().crStandard()).isEqualTo(TEMPLATE_ID);
            });
        }

        /** Nothing notifies where generation is off, so there is no template to be wrong. */
        @Test
        void a_blank_template_with_generation_disabled_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.email.templates.cr_standard=")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    /**
     * How a batch learns what became of its render, and how long a run waits before it stops.
     *
     * <p>There is one way an outcome arrives: systemdocgenerator publishes it and this service hears
     * it on a durable subscription. It cannot hear anything without a broker to subscribe to, and a
     * run that never learns an outcome is a batch that stays GENERATING until the next run gives up
     * on it - every batch, every night, silently. So the rule applies whenever the generation half is
     * enabled, and no setting can excuse it: the escape hatch that learned outcomes by asking the
     * query API went with the query, because after 004 it would mean "learn no outcome, fail every
     * batch at the next run, and render every day twice" - strictly worse than refusing to start.
     *
     * <p>The two durations beside it are the ones 004 introduces. {@code stale-after} is how long a
     * batch the schedule made may be awaiting its render before the next run gives up on it and
     * releases its registers, and it is destructive: it fails a batch and re-renders a day, so a
     * non-positive value would fail every batch the first run could see. {@code batch-age-refresh} is
     * how often the batch-age readings are taken between runs, and a non-positive one is a fixed
     * delay Spring cannot schedule.
     */
    @Nested
    @DisplayName("the generation half's outcome, and the durations that bound the wait for it")
    class GenerationOutcomeAndDurations {

        private static final String STALE_AFTER = "yotresultsdistribution.generation.stale-after";

        private static final String BATCH_AGE_REFRESH = "yotresultsdistribution.generation.batch-age-refresh";

        /**
         * The refusal, and it is unconditional - there is no setting left that excuses it.
         *
         * <p>It used to have a conjunct: a deployment could say it learned outcomes by asking the
         * query API instead, and the broker stopped being required of it. With the query gone that
         * shape would mean "learn no outcome, fail every batch at the next run, and render every
         * day twice", which is strictly worse than refusing to start, so the setting went rather
         * than being pinned to its one remaining legal value (FR-013). What is left is a rule with
         * one antecedent: the generation half is enabled, therefore a broker is named.
         */
        @Test
        @DisplayName("a generating deployment with no broker refuses to start, unconditionally")
        void a_generation_enabled_context_without_the_broker_configuration_refuses_to_start() {
            generating.withPropertyValues("spring.artemis.broker-url=").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .as("there is one way a batch learns what became of its render, and it is "
                                + "a durable subscription to a broker this deployment named")
                        .hasMessageContaining("spring.artemis.broker-url")
                        .hasMessageContaining("yotresultsdistribution.generation.enabled");
            });
        }

        /** The listener is part of the downstream half, so a disabled one subscribes to nothing. */
        @Test
        void a_disabled_generation_without_a_broker_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY)
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * The retired escape hatch, asserted as gone rather than as unused.
         *
         * <p>A value nothing binds is ignored by Spring in silence, so "it no longer works" is not
         * something a deployment could discover for itself. Both halves are stated: the record has no
         * such component, and a context that sets the old value still refuses to start without a
         * broker - which is exactly what the value used to excuse.
         */
        @Test
        void the_completion_setting_is_no_longer_bound() {
            assertThat(GenerationProperties.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .as("removed outright rather than left as a setting with one legal value")
                    .doesNotContain("completion");

            generating.withPropertyValues("spring.artemis.broker-url=",
                    "yotresultsdistribution.generation.completion=poll-only").run(context -> {
                        assertThat(context)
                                .as("and the value that used to buy a deployment its way out of"
                                        + " needing a broker now buys nothing at all")
                                .hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("spring.artemis.broker-url");
                    });
        }

        @Test
        void stale_after_defaults_to_thirty_minutes() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(GenerationProperties.class).staleAfter())
                        .as("comfortably longer than a render of this size takes and comfortably"
                                + " shorter than the gap between runs")
                        .isEqualTo(Duration.ofMinutes(30));
            });
        }

        @Test
        void a_zero_stale_after_refuses_to_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, STALE_AFTER + "=0s")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).hasMessageContaining(STALE_AFTER);
                    });
        }

        @Test
        void a_negative_stale_after_refuses_to_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, STALE_AFTER + "=-1m")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).hasMessageContaining(STALE_AFTER);
                    });
        }

        @Test
        void batch_age_refresh_defaults_to_ten_minutes() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(GenerationProperties.class).batchAgeRefresh())
                        .as("the cadence the retired timer took the readings on, kept, so the"
                                + " removal does not cost three continuous measurements")
                        .isEqualTo(Duration.ofMinutes(10));
            });
        }

        @Test
        void a_non_positive_batch_age_refresh_refuses_to_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, BATCH_AGE_REFRESH + "=0s")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(BATCH_AGE_REFRESH);
                    });

            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, BATCH_AGE_REFRESH + "=-30s")
                    .run(context -> {
                        assertThat(context)
                                .as("and a negative one the same way, because a fixed delay that"
                                        + " never elapses is a reading nobody ever takes again")
                                .hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(BATCH_AGE_REFRESH);
                    });
        }
    }

    /**
     * Constitution Principle V, applied to the four downstreams the nightly run has.
     *
     * <p>The same rule the payload and now-subscriptions stubs are held to, and the same two
     * discriminators: a namespace means workload identity, which means a deployed pod, and an
     * enabled generation means a pod that means to produce registers tonight. A stubbed renderer or
     * notifier in either of those places is a run that completes, counts its batches, reports itself
     * healthy - and sends nobody anything.
     */
    @Nested
    @DisplayName("the generation stubs are not reachable where registers are really produced")
    class GenerationStubs {

        @ParameterizedTest
        @ValueSource(strings = {"sdg-mode", "nn-mode", "fileservice-mode", "flag-mode"})
        void a_stub_on_the_deployed_credential_source_should_fail_startup(final String mode) {
            runner.withPropertyValues(NAMESPACE_PROPERTY,
                    "yotresultsdistribution.generation." + mode + "=STUB").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.generation." + mode)
                                .hasMessageContaining("yotresultsdistribution.servicebus.namespace");
                    });
        }

        @ParameterizedTest
        @ValueSource(strings = {"sdg-mode", "nn-mode", "fileservice-mode", "flag-mode"})
        void a_stub_beside_an_enabled_generation_should_fail_startup(final String mode) {
            generating.withPropertyValues("yotresultsdistribution.generation." + mode + "=STUB")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.generation." + mode)
                                .hasMessageContaining("yotresultsdistribution.generation.enabled");
                    });
        }

        /**
         * Which leaves the one place the stubs exist for: a local run and the container suites whose
         * subject is the batch state machine rather than the downstream itself.
         */
        @Test
        void the_stubs_should_start_locally_with_generation_disabled() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.generation.sdg-mode=STUB",
                    "yotresultsdistribution.generation.nn-mode=STUB",
                    "yotresultsdistribution.generation.fileservice-mode=STUB",
                    "yotresultsdistribution.generation.flag-mode=STUB")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    /**
     * The same rule again, on the connection string the flag read is authorised with.
     *
     * <p>The compose loop reads its WireMock stub with a fixed, published pair
     * ({@code Id=} {@link FeatureFlagProperties#PUBLISHED_LOCAL_ID}) that no real store has ever
     * been given. It is not a stub - the reader, the SDK client and the fail-closed parsing are all
     * the deployed ones - but it fails in the one way the STUB refusals exist to prevent: a pod
     * that starts, reports itself healthy, and at 18:00 is refused the flag, skips the run and
     * counts it, indistinguishable from a store outage.
     *
     * <p>Two discriminators, and the second is the one the STUB refusals already draw deployment on.
     * An endpoint whose host ends {@code .azconfig.io} is a real Azure App Configuration store
     * whatever else is configured, and a Service Bus namespace means a deployed pod. Both are
     * unconditional on the master switch: a job that happens to be disabled in this deployment is
     * no reason to accept a pair that cannot read the flag in the next one.
     */
    @Nested
    @DisplayName("the published local pair is nowhere a real flag is read")
    class PublishedLocalPair {

        /** What a real store's endpoint looks like; the estate has no other shape. */
        private static final String REAL_STORE_ENDPOINT =
                "https://yot-results-distribution-ste86.azconfig.io";

        @Test
        void the_published_local_pair_against_a_real_store_should_fail_startup() {
            generating.withPropertyValues(localPairAt(REAL_STORE_ENDPOINT)).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasMessageContaining(FLAG_CONNECTION_STRING)
                        .hasMessageContaining("published local pair")
                        .hasMessageContaining("real App Configuration store");
            });
        }

        @Test
        void the_published_local_pair_on_a_deployed_pod_should_fail_startup() {
            runner.withPropertyValues(NAMESPACE_PROPERTY, localPairAt("http://wiremock:8080"))
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(FLAG_CONNECTION_STRING)
                                .hasMessageContaining("yotresultsdistribution.servicebus.namespace");
                    });
        }

        /**
         * Which leaves the one place it exists for: the compose loop, whose App Configuration is a
         * WireMock stub on plain HTTP.
         */
        @Test
        void the_published_local_pair_against_the_compose_stub_should_start() {
            generating.withPropertyValues(localPairAt("http://wiremock:8080"))
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * A real store named with its port, its trailing slash, its own query, in upper case or
         * padded is what an operator actually pastes out of the portal, and any of them read as
         * "not a store" is a pod that starts on a published identity and skips every run at 18:00
         * with ACCESS_DENIED.
         */
        @ParameterizedTest
        @ValueSource(strings = {
            "https://yot-results-distribution-ste86.azconfig.io:443",
            "https://yot-results-distribution-ste86.azconfig.io/",
            "https://yot-results-distribution-ste86.azconfig.io/kv?api-version=2023-11-01",
            "HTTPS://YOT-RESULTS-DISTRIBUTION-STE86.AZCONFIG.IO",
            "  https://yot-results-distribution-ste86.azconfig.io  ",
        })
        void a_real_store_however_it_is_written_should_fail_startup(final String endpoint) {
            generating.withPropertyValues(localPairAt(endpoint)).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasMessageContaining(FLAG_CONNECTION_STRING)
                        .hasMessageContaining("real App Configuration store");
            });
        }

        /**
         * The same store written in the absolute DNS form: {@code store.azconfig.io.} and
         * {@code store.azconfig.io} resolve identically and the SDK builds the same client from
         * either, so the root dot is removed before the domain is asked about.
         */
        @ParameterizedTest
        @ValueSource(strings = {
            "https://yot-results-distribution-ste86.azconfig.io./",
            "https://YOT-RESULTS-DISTRIBUTION-STE86.AZCONFIG.IO.",
            "https://yot-results-distribution-ste86.azconfig.io.:443/kv",
        })
        void a_real_store_in_its_absolute_dns_form_should_fail_startup(final String endpoint) {
            generating.withPropertyValues(localPairAt(endpoint)).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasMessageContaining(FLAG_CONNECTION_STRING)
                        .hasMessageContaining("real App Configuration store");
            });
        }

        /**
         * The rule is about the host and says so: a path or a query that happens to mention the
         * store's domain is not a store, and a validator that searched the whole string would
         * refuse the local loop the pair exists for.
         */
        @ParameterizedTest
        @ValueSource(strings = {
            "http://wiremock:8080/x.azconfig.io",
            "http://wiremock:8080?store=x.azconfig.io",
        })
        void a_stub_whose_path_or_query_mentions_the_domain_should_still_start(
                final String endpoint) {

            generating.withPropertyValues(localPairAt(endpoint))
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        void the_published_local_pair_against_a_real_store_should_fail_with_generation_off() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, localPairAt(REAL_STORE_ENDPOINT))
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(FLAG_CONNECTION_STRING)
                                .hasMessageContaining("real App Configuration store");
                    });
        }

        @Test
        void a_deployed_connection_string_against_a_real_store_should_start() {
            final String deployed = "Endpoint=" + REAL_STORE_ENDPOINT + ";Id=ste-id;Secret="
                    + DEPLOYED_SECRET;
            generating.withPropertyValues(FLAG_CONNECTION_STRING + "=" + deployed).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(FeatureFlagProperties.class).connectionString())
                        .as("what Key Vault injects is what the reader is built from")
                        .isEqualTo(deployed);
            });
        }
    }

    /**
     * No refusal of the flag's connection string quotes it.
     *
     * <p>The string is the estate's App Configuration key. A start-up refusal is printed to the
     * pod's log and kept by the log index for as long as the index keeps anything, so the refusal
     * names the setting and the rule it broke and nothing it was given (constitution Principle
     * VII). The malformed shapes are asserted beside the rules that refuse them, in
     * {@code GenerationDownstreams}; these are the published-pair refusals.
     */
    @Nested
    @DisplayName("no refusal quotes the flag's connection string")
    class ConnectionStringPrivacy {

        private static final String REAL_STORE_HOST = "yot-results-distribution-ste86.azconfig.io";

        @Test
        void a_published_pair_refused_against_a_real_store_should_quote_none_of_it() {
            generating.withPropertyValues(localPairAt("https://" + REAL_STORE_HOST))
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageNotContaining(LOCAL_PAIR_SECRET)
                                .hasMessageNotContaining(FeatureFlagProperties.PUBLISHED_LOCAL_ID)
                                .hasMessageNotContaining(REAL_STORE_HOST);
                    });
        }

        @Test
        void a_published_pair_refused_on_a_deployed_pod_should_quote_none_of_it() {
            runner.withPropertyValues(NAMESPACE_PROPERTY, localPairAt("http://wiremock:8080"))
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageNotContaining(LOCAL_PAIR_SECRET)
                                .hasMessageNotContaining(FeatureFlagProperties.PUBLISHED_LOCAL_ID)
                                .hasMessageNotContaining("wiremock:8080");
                    });
        }
    }

    /**
     * What the shipped {@code application.yaml} actually binds.
     *
     * <p>Asserted against the real file rather than against property values a test invents, because
     * the failure this covers is a documented environment variable that reaches nothing. A comment
     * naming {@code YOT_RESULTS_DISTRIBUTION_SYSTEM_USER_ID} is not a binding, and a deployment that sets it
     * and still fails to start with "system-user-id is required" is a deployment nobody can debug
     * from the configuration in front of them.
     */
    @Nested
    @DisplayName("the shipped application.yaml")
    class ShippedConfiguration {

        /**
         * Deliberately not built from {@code runner}: that one carries the identities and endpoints
         * so the cases about something else are not refused startup by the live sources' own rules,
         * and a property value set on the runner outranks the file. A test about what the file binds
         * has to let the file be the only thing that binds it.
         */
        private final ApplicationContextRunner shipped = new ApplicationContextRunner()
                .withUserConfiguration(PropertiesTestConfiguration.class)
                .withInitializer(new ConfigDataApplicationContextInitializer());

        /**
         * The subject here is what the file binds, not which adapters are selected. The shipped file
         * ships both sources LIVE, and a live source without an identity is refused startup by
         * design; selecting the stubs takes that rule out of the way of a test about the binding of
         * a value.
         */
        private final ApplicationContextRunner shippedOnTheStub = shipped
                .withPropertyValues("yotresultsdistribution.payload.mode=STUB",
                        "yotresultsdistribution.referencedata.mode=STUB");

        @Test
        void the_flag_connection_string_should_arrive_from_the_variable_the_file_documents() {
            final String injected = "Endpoint=https://appconfig.internal;Id=ste-id;Secret="
                    + DEPLOYED_SECRET;
            shippedOnTheStub
                    .withSystemProperties("YOTRESULTSDISTRIBUTION_FEATURE_CONNECTION_STRING=" + injected)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(FeatureFlagProperties.class).connectionString())
                                .isEqualTo(injected);
                    });
        }

        @Test
        void the_flag_connection_string_should_default_to_nothing() {
            shippedOnTheStub.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(FeatureFlagProperties.class).connectionString())
                        .as("FR-034: no credential-shaped value is an environment default")
                        .isNullOrEmpty();
            });
        }

        @Test
        void the_queue_it_names_should_be_the_court_register_queue() {
            shippedOnTheStub.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(YotResultsDistributionProperties.class).servicebus().queueName())
                        .isEqualTo("yotresultsdistribution.requests");
            });
        }

        @Test
        void the_identity_should_arrive_from_the_environment_variable_the_file_documents() {
            shipped.withSystemProperties(
                    "YOT_RESULTS_DISTRIBUTION_SYSTEM_USER_ID=b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        final YotResultsDistributionProperties properties =
                                context.getBean(YotResultsDistributionProperties.class);
                        assertThat(properties.progression().systemUserId())
                                .isEqualTo("b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234");
                        assertThat(properties.results().systemUserId())
                                .isEqualTo("b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234");
                    });
        }

        @Test
        void the_submission_endpoint_should_arrive_from_the_variable_the_file_documents() {
            shippedOnTheStub
                    .withSystemProperties("PROGRESSION_BASE_URL=http://progression.internal:8080")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(YotResultsDistributionProperties.class)
                                .progression().baseUrl())
                                .isEqualTo("http://progression.internal:8080");
                    });
        }

        @Test
        void the_fallback_endpoint_should_arrive_from_the_variable_the_file_documents() {
            shippedOnTheStub.withSystemProperties("RESULTS_BASE_URL=http://results.internal:8080")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(YotResultsDistributionProperties.class)
                                .results().baseUrl())
                                .isEqualTo("http://results.internal:8080");
                    });
        }

        @Test
        void the_reference_data_endpoint_should_arrive_from_the_variable_the_file_documents() {
            shippedOnTheStub
                    .withSystemProperties("REFERENCEDATA_BASE_URL=http://referencedata.internal:8080")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(YotResultsDistributionProperties.class)
                                .referencedata().baseUrl())
                                .isEqualTo("http://referencedata.internal:8080");
                    });
        }

        /**
         * One identity, because the function app has one: {@code input.cjscppuid} authorises the
         * payload read and the now-subscriptions read alike. An environment that mounts this
         * service's identity is therefore not asked for a second one.
         */
        @Test
        void the_reference_data_identity_should_fall_back_to_this_services_own() {
            shippedOnTheStub.withSystemProperties(
                    "YOT_RESULTS_DISTRIBUTION_SYSTEM_USER_ID=b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(YotResultsDistributionProperties.class)
                                .referencedata().systemUserId())
                                .isEqualTo("b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234");
                    });
        }

        @Test
        void the_reference_data_identity_should_be_settable_on_its_own() {
            shippedOnTheStub.withSystemProperties(
                    "YOT_RESULTS_DISTRIBUTION_SYSTEM_USER_ID=b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234",
                    "REFERENCEDATA_SYSTEM_USER_ID=2c7b1e64-0f4a-4f0e-9b2c-8d1a6f3e5c07")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(YotResultsDistributionProperties.class)
                                .referencedata().systemUserId())
                                .isEqualTo("2c7b1e64-0f4a-4f0e-9b2c-8d1a6f3e5c07");
                    });
        }

        @Test
        void the_file_should_ship_the_outbound_validator_switched_on() {
            shippedOnTheStub.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(YotResultsDistributionProperties.class)
                        .submission().validateOutbound()).isTrue();
            });
        }

        @Test
        void an_unset_identity_should_stay_unset_so_a_local_run_borrows_nobodys() {
            shippedOnTheStub.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(YotResultsDistributionProperties.class)
                        .progression().systemUserId())
                        .as("absent is absent; startup refuses a live pipeline on it, which is the"
                                + " point")
                        .isNullOrEmpty();
            });
        }

        /**
         * The authorisation filter's switch, which the shipped file turns <strong>on</strong>.
         *
         * <p>Condition (a) of constitution Principle III, and since 5.0.0 the whole of how it is
         * carried: {@code AuthzAutoConfiguration} is
         * {@code @ConditionalOnProperty(havingValue = "true")} with no {@code matchIfMissing}, so
         * the library's own default is a pod that registers no authorisation filter at all. The
         * file reverses it. A deployment that wants the endpoints unguarded says so through
         * {@code AUTHZ_HTTP_ENABLED}, and the pod starts either way - there is no refusal behind
         * this default, which is exactly why the default is what has to be right.
         */
        @Test
        void the_authorisation_filter_should_ship_switched_on() {
            shippedOnTheStub.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getEnvironment().getProperty("authz.http.enabled"))
                        .as("secure by default: the file reads AUTHZ_HTTP_ENABLED and defaults it"
                                + " on, against a library default of off")
                        .isEqualTo("true");
            });
        }

        /**
         * The HTTP audit filter's switch, which the shipped file also turns <strong>on</strong>,
         * for condition (b) and by the same argument.
         *
         * <p>It costs a laptop nothing, because every bean the key gates lives inside the
         * auto-configuration class {@code cp.audit.enabled} gates - and that one ships off, on the
         * case below.
         */
        @Test
        void the_http_audit_filter_should_ship_switched_on() {
            shippedOnTheStub.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getEnvironment().getProperty("audit.http.enabled"))
                        .as("secure by default: the file reads HTTP_AUDIT_ENABLED and defaults it"
                                + " on, against a library default of off")
                        .isEqualTo("true");
            });
        }

        /**
         * The audit transport's own master switch, which the shipped file turns <em>off</em>.
         *
         * <p>It is not this service's setting and it is not read by any rule above, which is why
         * nothing else in this suite would notice it moving: {@code AuditComponentScanTest} sets it
         * explicitly and the {@code test} profile overrides it. What the default protects is a
         * laptop: with the key on, the audit starter's auto-configuration builds an Artemis
         * connection factory and validates {@code cp.audit.hosts} and {@code cp.audit.port} while
         * doing so - whether or not HTTP auditing is on - so a lost or flipped default is a
         * {@code bootRun} that fails on a broker nobody asked for.
         */
        @Test
        void the_audit_transport_should_ship_switched_off_for_a_laptop() {
            shippedOnTheStub.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getEnvironment().getProperty("cp.audit.enabled"))
                        .as("the file reads CP_AUDIT_ENABLED and defaults it off; a deployment that"
                                + " wants the transport says so through the environment variable")
                        .isEqualTo("false");
            });
        }
    }

    /**
     * The morning exception report's own refusals.
     *
     * <p>The same family as the generation half's, and the same argument carries them: a zero
     * threshold reports every request as late, a schedule read in UTC fires at 08:00 through the
     * summer, a lock shorter than the run it locks lets a second replica report the same morning
     * twice, and an e-mail output enabled with no template or nobody to send to is a deployment that
     * sends nothing every morning and says so only in a log line. Every one of them is discovered at
     * 07:00 the next morning if it is not discovered at startup.
     *
     * <p>The duration, zone and lock rules are unconditional on {@code yotresultsdistribution.report.enabled}
     * for the reason the generation half's zone rule is unconditional on its own switch: a report
     * that happens to be disabled in this deployment is not a reason to accept a setting that would
     * be wrong in the next one. The two e-mail rules are conditional on the e-mail output, because
     * neither setting is required until something sends.
     *
     * <p><strong>No refusal quotes an address or a template id back.</strong> Recipients are
     * people's addresses, and a startup failure is a log line in the same index as everything else.
     */
    @Nested
    @DisplayName("the morning report must be able to run and to reach somebody")
    class ReportRefusals {

        private static final String REPORT = "yotresultsdistribution.report";

        private static final String RECIPIENTS = REPORT + ".email.recipients";

        private static final String TEMPLATE = REPORT + ".email.template-id";

        private static final String EMAIL_ENABLED = REPORT + ".email.enabled=true";

        /** A recipient that parses, for the cases whose subject is one of the other settings. */
        private static final String A_RECIPIENT = RECIPIENTS + "=cr-support@justice.gov.uk";

        private static final String A_TEMPLATE =
                TEMPLATE + "=8f1d5c30-27b4-4f6a-9d18-0c3b7a2e5164";

        /** The words only the JVM-unknown branch of the zone rule says. */
        private static final String NOT_A_ZONE_THIS_JVM_KNOWS = "is not a zone this JVM knows";

        /** The words only the unacknowledged branch says, so the two cannot be confused. */
        private static final String MUST_BE_EUROPE_LONDON = "must be Europe/London";

        @Test
        void a_negative_request_threshold_refuses_to_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    REPORT + ".request-terminal-within=-1m").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(REPORT + ".request-terminal-within");
                    });
        }

        /**
         * A zero rendering limit reports every batch in the estate as late on its first morning, so
         * it is refused under the report's own key - which, since 004, is the only key it can come
         * from.
         */
        @Test
        void a_zero_batch_generated_within_refuses_to_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    REPORT + ".batch-generated-within=0s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(REPORT + ".batch-generated-within");
                    });
        }

        /**
         * A cap of zero is a report that carries nothing, which is the one reading this feature
         * exists to make impossible: every morning would look like a quiet one, and the counts
         * beside the empty list would be the only thing saying otherwise. It is the same argument
         * the zero durations are refused under, one setting along.
         */
        @Test
        void a_zero_max_entries_refuses_to_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    REPORT + ".max-entries=0").run(context -> {
                        assertThat(context)
                                .as("a report capped at nothing writes no exception event at all,"
                                        + " which is indistinguishable from a morning with nothing"
                                        + " wrong on it")
                                .hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(REPORT + ".max-entries");
                    });
        }

        @Test
        void a_zero_notified_within_refuses_to_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    REPORT + ".notified-within=0s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(REPORT + ".notified-within");
                    });
        }

        /**
         * The sweep's interval, and the one key in this family that is not under
         * {@code yotresultsdistribution.report}: a refresh of zero is a fixed delay Spring refuses to
         * schedule, on a pod whose gauges are then dark for the life of it.
         */
        @Test
        void a_zero_gauge_refresh_refuses_to_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.intake.gauge-refresh=0s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.intake.gauge-refresh");
                    });
        }

        /**
         * The shipped fifteen minutes is exactly the fixed run budget plus the <em>existing</em>
         * scheduler margin, so a value below it is a lock that can expire under a run still going
         * on - and the replica that takes it reports the same morning to the same people again.
         */
        @Test
        void a_lock_below_budget_plus_margin_refuses_to_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    REPORT + ".lock-at-most-for=14m").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(REPORT + ".lock-at-most-for");
                    });
        }

        @Test
        void a_zone_other_than_europe_london_refuses_without_the_acknowledgement() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    REPORT + ".zone=Europe/Paris").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(REPORT + ".zone")
                                .hasMessageContaining("Europe/London")
                                .hasMessageContaining(REPORT + ".zone-override-acknowledged");
                    });
        }

        /**
         * Asserted on the wording that belongs to <em>this</em> rule and to no other. Naming the
         * setting alone is not enough here: the unacknowledged refusal names the same setting, so a
         * case that asked only for that would still pass if the acknowledgement stopped being read
         * at all - which is precisely the regression that would leave {@code @Scheduled} to fail at
         * refresh with nothing pointing at the setting that caused it.
         */
        @Test
        void an_acknowledged_override_must_still_be_a_zone_the_jvm_knows() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    REPORT + ".zone=Mars/Olympus",
                    REPORT + ".zone-override-acknowledged=true").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(REPORT + ".zone")
                                .hasMessageContaining(NOT_A_ZONE_THIS_JVM_KNOWS)
                                .as("the unacknowledged refusal names the same setting, so the"
                                        + " distinguishing words are what pins this rule")
                                .hasMessageNotContaining(MUST_BE_EUROPE_LONDON);
                    });
        }

        @Test
        void an_acknowledged_override_of_a_known_zone_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    REPORT + ".zone=Europe/Paris",
                    REPORT + ".zone-override-acknowledged=true")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        void email_enabled_with_no_template_refuses_to_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, EMAIL_ENABLED, A_RECIPIENT)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(TEMPLATE)
                                .hasMessageContaining(REPORT + ".email.enabled");
                    });
        }

        @Test
        void email_enabled_with_no_recipient_refuses_to_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, EMAIL_ENABLED, A_TEMPLATE)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(RECIPIENTS)
                                .hasMessageContaining(REPORT + ".email.enabled");
                    });
        }

        /**
         * The address is never quoted back. It is somebody's address, the refusal is a log line, and
         * naming the setting is what an operator needs in order to fix it.
         */
        @Test
        void an_unparseable_recipient_refuses_to_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, EMAIL_ENABLED, A_TEMPLATE,
                    RECIPIENTS + "=cr-support@justice.gov.uk,not-an-address").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(RECIPIENTS)
                                .hasMessageNotContaining("not-an-address");
                    });
        }

        /**
         * Four settings and not two, after review gate 7 and the finding it left behind.
         *
         * <p>The e-mail output writes the exception list into the framework file service and
         * attaches it by id, so {@code yotresultsdistribution.fileservice.url} is required of it exactly as
         * it is required of the nightly run - and was required of the run alone until the gate,
         * which is what made a report pod with this output on a pod that could not start. The
         * endpoint it then posts through is the same argument one setting along, and the case below
         * is its refusal. What this one says is that the settings together are a deployment that
         * starts.
         */
        @Test
        void an_enabled_email_output_with_everything_it_needs_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, EMAIL_ENABLED, A_TEMPLATE,
                    A_RECIPIENT, FILESERVICE_URL_PROPERTY, NN_ENDPOINT_PROPERTY,
                    ENDPOINTS_IDENTITY_PROPERTY)
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * A blank id is an unset one. A deployed environment overrides the local binding with an
         * empty value rather than deleting the key, which is the exact shape fix P9 was: the
         * register's own template arrived blank, every send was refused, and the only thing that
         * said so was a log line nobody read.
         */
        @Test
        void a_blank_template_id_with_email_enabled_refuses_to_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, EMAIL_ENABLED, A_RECIPIENT,
                    TEMPLATE + "=  ").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(TEMPLATE)
                                .hasMessageContaining(REPORT + ".email.enabled");
                    });
        }

        /**
         * Non-blank is not the same as usable. notificationnotify refuses the command on anything
         * that is not its own canonical UUID, so a template id of the wrong shape is a morning
         * report nobody receives - refused here rather than at 07:00, and never quoted back,
         * because a startup refusal is a log line in the same index as every other.
         */
        @Test
        void a_malformed_template_id_refuses_to_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, EMAIL_ENABLED, A_RECIPIENT,
                    TEMPLATE + "=not-a-uuid").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(TEMPLATE)
                                .hasMessageContaining(REPORT + ".email.enabled")
                                .hasMessageNotContaining("not-a-uuid");
                    });
        }

        /**
         * An empty entry in the list is the stray-separator failure the address rule exists to
         * catch, and it is the one the list arrives from Key Vault carrying: a trailing comma and a
         * list pasted with a separator too many look identical to a correct list in a chart diff.
         * notificationnotify refuses the command on it, every recipient of every morning.
         *
         * <p>Two spellings, because they reach the binder differently and must reach the same
         * answer: a separator with nothing between two addresses, and a separator with nothing
         * after the last one.
         */
        @Test
        void a_recipient_list_with_an_empty_entry_refuses_to_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, EMAIL_ENABLED, A_TEMPLATE,
                    RECIPIENTS + "=cr-support@justice.gov.uk,,duty@justice.gov.uk")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(RECIPIENTS)
                                .hasMessageNotContaining("cr-support@justice.gov.uk");
                    });

            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, EMAIL_ENABLED, A_TEMPLATE,
                    RECIPIENTS + "=cr-support@justice.gov.uk, ").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(RECIPIENTS)
                                .hasMessageNotContaining("cr-support@justice.gov.uk");
                    });
        }

        /**
         * The schedule is also the window, so a cron nothing can read is two failures at once: a
         * job Spring refuses to schedule at refresh, and a window neither the run nor the command
         * can open. Both are discovered at 07:00 on a morning nobody is watching, which is why they
         * are discovered at startup instead. The value is not quoted back.
         */
        @Test
        void an_unparseable_cron_refuses_to_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    REPORT + ".cron=every morning please").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(REPORT + ".cron")
                                .hasMessageNotContaining("every morning please");
                    });
        }

        /**
         * The endpoint the send is made to, required of whichever half sends.
         *
         * <p>{@code ReportEmailConfig} builds the report's own {@code RestClient} over
         * {@code yotresultsdistribution.endpoints.notificationnotify}, because the register leg's client is
         * built only where the generation half is enabled and this output has to work where it is
         * not - and the setting was required of that half alone. A pod in FR-004's shape with the
         * e-mail output on therefore started clean, reported itself healthy, and failed every send
         * at 07:00 against a client with no base URL: the same shape as the file-service URL the
         * gate before this one moved, one setting along.
         *
         * <p>The refusal names the half that asked, for the reason that one does: the value to set
         * is the same either way, and what an operator has to know is why a pod that renders
         * nothing wants a notificationnotify endpoint at all.
         */
        @Test
        void the_notificationnotify_endpoint_is_required_whenever_either_half_sends() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, EMAIL_ENABLED, A_TEMPLATE,
                    A_RECIPIENT, FILESERVICE_URL_PROPERTY, ENDPOINTS_IDENTITY_PROPERTY,
                    "yotresultsdistribution.endpoints.notificationnotify=  ").run(context -> {
                        assertThat(context)
                                .as("the e-mail output posts the report to notificationnotify, so"
                                        + " a blank endpoint is a morning that fails every send on"
                                        + " a pod that started clean")
                                .hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.endpoints.notificationnotify")
                                .hasMessageContaining(REPORT + ".email.enabled");
                    });

            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, A_TEMPLATE, A_RECIPIENT)
                    .run(context -> assertThat(context)
                            .as("and of neither half on a pod that sends nothing at all: a setting"
                                    + " demanded of a deployment that cannot use it is a deploy"
                                    + " that fails for no reason")
                            .hasNotFailed());
        }

        /**
         * The identity the send is made under, required of whichever half sends.
         *
         * <p>The finding review gate 7 named and left: it fixed the endpoint and said out loud that
         * {@code yotresultsdistribution.endpoints.system-user-id} was asked of neither half, because giving
         * it a rule there would have been a new refusal inside a remediation commit. This is the
         * gate that catalogues it. Both outward legs put the value in the {@code CJSCPPUID} header
         * of every {@code send-email-notification} they make, and the framework refuses a command
         * without one - so a deployment that sets the endpoint and forgets the identity is a pod
         * that starts clean, reports itself healthy, and has every send refused: the Youth
         * Offending Teams at 18:00, or support at 07:00.
         *
         * <p>The value is <strong>never quoted back</strong>. It is a secret, and a startup failure
         * is a log line in the same index as every other.
         */
        @Test
        void the_system_user_id_is_required_whenever_either_half_sends() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, EMAIL_ENABLED, A_TEMPLATE,
                    A_RECIPIENT, FILESERVICE_URL_PROPERTY, NN_ENDPOINT_PROPERTY,
                    "yotresultsdistribution.endpoints.system-user-id=  ").run(context -> {
                        assertThat(context)
                                .as("an endpoint with no identity to post under is every send"
                                        + " refused, on a pod that started clean")
                                .hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.endpoints.system-user-id")
                                .hasMessageContaining(REPORT + ".email.enabled");
                    });

            generating.withPropertyValues("yotresultsdistribution.endpoints.system-user-id=  ")
                    .run(context -> {
                        assertThat(context)
                                .as("and the same of the half that tells the Youth Offending"
                                        + " Teams, because it is the same header on the same"
                                        + " command")
                                .hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.endpoints.system-user-id")
                                .hasMessageContaining("yotresultsdistribution.generation.enabled");
                    });

            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, A_TEMPLATE, A_RECIPIENT)
                    .run(context -> assertThat(context)
                            .as("and of neither half on a pod that sends nothing at all")
                            .hasNotFailed());
        }
    }

    /**
     * The operations API's own settings (increment 005, FR-045 and FR-053).
     *
     * <p><strong>Nothing here refuses a pod for the value of one switch given another's.</strong>
     * {@code authz.http.enabled} and {@code audit.http.enabled} are ordinary configuration an
     * operator may turn on or off, and they are secure by default instead - {@code application.yaml}
     * reads both as {@code true} against library defaults of off, which is what
     * {@code ShippedConfiguration} pins. Constitution 5.0.0 replaced the start-up refusal an earlier
     * round wrote here, and with it the {@code yotresultsdistribution.servicebus.namespace} discriminator
     * that decided where it applied: a service that will not start on a configuration its operator
     * chose is a service that cannot be operated. The first three cases below are that decision,
     * stated as tests.
     *
     * <p>What remains is refused because a <strong>value cannot mean what it says</strong>, which
     * is a different rule: a transport switched on that names no broker or an impossible port, an
     * audit filter switched on with no document to resolve, and an unusable age bound or lock wait.
     * The library will fail on some of these too, later and with a worse message; refusing first is
     * what names the setting.
     */
    @Nested
    @DisplayName("the operations API's settings")
    class OperationsSettings {

        /** The audit transport switched on, which is what the value rules below are gated on. */
        private static final String TRANSPORT_ON = "cp.audit.enabled=true";

        /** A transport that names somewhere, so a case about the port is only about the port. */
        private static final String TRANSPORT_HOSTS = "cp.audit.hosts=artemis-audit.internal";

        /** A port that is a port, so a case about the hosts is only about the hosts. */
        private static final String TRANSPORT_PORT = "cp.audit.port=61616";

        /** A deployed pod: a namespace, which is what the withdrawn discriminator read. */
        private final ApplicationContextRunner deployed =
                runner.withPropertyValues(NAMESPACE_PROPERTY);

        /** A deployed pod with the audit transport on and configured. */
        private final ApplicationContextRunner publishing =
                deployed.withPropertyValues(TRANSPORT_ON, TRANSPORT_HOSTS, TRANSPORT_PORT);

        /**
         * The decision of 2026-09-20, and the case this class exists for.
         *
         * <p>A deployed pod serving every {@code /operations/**} endpoint with both filters
         * switched off starts. It is not a configuration anybody should deploy, and nothing here
         * pretends otherwise - what keeps the filters on in a deployed environment is the default
         * below and the values file, not a pod that refuses to come up. An operator who switched
         * them off said what they meant.
         */
        @Test
        void a_pod_with_both_filter_switches_off_should_start() {
            deployed.withPropertyValues("authz.http.enabled=false", "audit.http.enabled=false")
                    .run(context -> assertThat(context)
                            .as("the two switches are configuration, not a start-up condition:"
                                    + " there is no cross-field rule against"
                                    + " yotresultsdistribution.operations.enabled and no environment"
                                    + " discriminator deciding where one would apply")
                            .hasNotFailed());
        }

        /**
         * The audit half on its own, because the withdrawn rule refused each half separately and a
         * single both-off case would not notice one of them coming back.
         */
        @Test
        void a_pod_serving_the_operations_api_unaudited_should_start() {
            deployed.withPropertyValues("yotresultsdistribution.operations.enabled=true",
                            "audit.http.enabled=false")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /** The authorisation half on its own, for the same reason. */
        @Test
        void a_pod_serving_the_operations_api_unauthorised_should_start() {
            deployed.withPropertyValues("yotresultsdistribution.operations.enabled=true",
                            "authz.http.enabled=false")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * A value rule, and the reason it is one: the audit filter <strong>swallows every
         * publishing failure</strong>, so a transport switched on and pointed at nothing publishes
         * nothing and says so only in a log line. The library's own {@code validateProps} checks
         * {@code hosts.isEmpty()} and {@code port > 0} and nothing else.
         */
        @Test
        void an_audit_transport_with_no_broker_refuses_to_start() {
            publishing.withPropertyValues("cp.audit.hosts=").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasMessageContaining("cp.audit.hosts")
                        .hasMessageContaining("cp.audit.enabled");
            });
        }

        /**
         * A list of one blank is not a list of none, and the starter accepts it as readily as it
         * accepts a list of real hosts - then builds connectors pointed at no host at all.
         */
        @Test
        void an_audit_transport_whose_only_host_is_blank_refuses_to_start() {
            publishing.withPropertyValues("cp.audit.hosts=   ").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasMessageContaining("cp.audit.hosts");
            });
        }

        /** And a list whose every element is blank, which is what a stray separator produces. */
        @Test
        void an_audit_transport_whose_hosts_are_all_blank_refuses_to_start() {
            publishing.withPropertyValues("cp.audit.hosts=,").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasMessageContaining("cp.audit.hosts");
            });
        }

        @Test
        void an_audit_transport_with_no_port_refuses_to_start() {
            publishing.withPropertyValues("cp.audit.port=0").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasMessageContaining("cp.audit.port");
            });
        }

        /**
         * Read as text and parsed here rather than asked of the environment as an {@code Integer}:
         * a conversion failure is raised by Spring during the refresh, names neither the setting
         * nor the transport it leaves unpublished, and quotes the offending value back.
         */
        @Test
        void an_audit_port_that_is_not_a_number_refuses_to_start() {
            publishing.withPropertyValues("cp.audit.port=sixty").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasMessageContaining("cp.audit.port");
            });
        }

        /**
         * The top of the range, and the case the old five-digit reading got wrong.
         *
         * <p>{@code 65536} is five digits and a positive whole number, so a rule that checked only
         * those two things accepted a value no TCP stack can bind - and the failure would have
         * arrived from Artemis at the first publish, inside a filter that swallows it.
         */
        @Test
        void an_audit_port_above_the_tcp_range_refuses_to_start() {
            publishing.withPropertyValues("cp.audit.port=65536").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasMessageContaining("cp.audit.port");
            });
        }

        /** The boundary from the other side: the highest port there is, and it is accepted. */
        @Test
        void the_highest_port_there_is_should_start() {
            publishing.withPropertyValues("cp.audit.port=65535")
                    .run(context -> assertThat(context)
                            .as("65535 is a port; the rule is a range, not a digit count")
                            .hasNotFailed());
        }

        /** And the lowest, for the same reason. */
        @Test
        void the_lowest_port_there_is_should_start() {
            publishing.withPropertyValues("cp.audit.port=1")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * An absent port is not a port either, and the base runner carries none - so this case is
         * built from a transport switched on with hosts and nothing else.
         */
        @Test
        void an_absent_audit_port_refuses_to_start() {
            deployed.withPropertyValues(TRANSPORT_ON, TRANSPORT_HOSTS).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasMessageContaining("cp.audit.port");
            });
        }

        /**
         * The transport switched off is the transport nothing is published over, so its connection
         * is not a value that has to mean anything.
         */
        @Test
        void an_unconfigured_transport_that_is_switched_off_should_start() {
            deployed.withPropertyValues("cp.audit.enabled=false", "cp.audit.hosts=",
                            "cp.audit.port=0")
                    .run(context -> assertThat(context)
                            .as("nothing publishes, so there is no connection to refuse")
                            .hasNotFailed());
        }

        /**
         * The audit filter finds the OpenAPI document by a <strong>suffix</strong> glob,
         * {@code classpath*:**}{@code /*<value>}. Unset, it globs for {@code *null}, finds nothing
         * and throws during the refresh - naming neither the setting nor this service.
         *
         * <p>Refused where the parser bean would actually be built, which is where <em>both</em>
         * halves are on: every {@code audit.http.*} bean sits inside the auto-configuration class
         * {@code cp.audit.enabled} gates, so the HTTP half on over a transport that is off builds
         * no parser and globs for nothing.
         */
        @Test
        void an_audit_filter_with_no_openapi_spec_key_refuses_to_start() {
            publishing.withPropertyValues("audit.http.enabled=true",
                            "audit.http.openapi-rest-spec=")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("audit.http.openapi-rest-spec")
                                .hasMessageContaining("audit.http.enabled");
                    });
        }

        /**
         * The same configuration is also the one the shipped defaults produce, and a pod in it
         * publishes <strong>no</strong> audit event at all — so it says so at start-up.
         *
         * <p>{@code audit.http.enabled} ships {@code true} and the transport ships {@code false},
         * because a laptop has no audit broker and the transport's connection factory validates its
         * hosts and port while it is being built. Every {@code audit.http.*} bean lives inside the
         * auto-configuration class the transport's key gates, so the HTTP half on over a transport
         * that is off is a filter that was never constructed: condition (b) of Principle III is
         * carried by a deployed values file setting {@code CP_AUDIT_ENABLED=true} beside it, and a
         * deployment that sets nothing serves the operations API unaudited.
         *
         * <p>Nothing refuses that — the switches are configuration, not a start-up condition — but
         * an unaudited pod that said so nowhere would be exactly the silence this service exists to
         * end, and the audit filter swallows every publishing failure, so there is no later line to
         * read either. The line names the two settings and nothing a caller or a secret could reach.
         */
        @Test
        void an_audit_filter_over_a_transport_that_is_off_should_say_so_at_start_up() {
            try (CapturedLog captured = CapturedLog.capturing(PropertiesValidator.class)) {
                deployed.withPropertyValues("audit.http.enabled=true", "cp.audit.enabled=false")
                        .run(context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(captured.events())
                                    .as("an unaudited operations API is said at start-up or"
                                            + " nowhere: the filter that would have published is"
                                            + " never built, and the one that is swallows its own"
                                            + " failures")
                                    .anySatisfy(said -> {
                                        assertThat(said.getLevel()).isEqualTo(Level.WARN);
                                        assertThat(said.getFormattedMessage())
                                                .contains("audit.http.enabled")
                                                .contains("cp.audit.enabled");
                                    });
                        });
            }
        }

        /**
         * And the pod whose whole audit path is configured says nothing, because there is nothing
         * to say: a warning every deployed pod carried would be one nobody reads.
         */
        @Test
        void a_pod_that_publishes_its_audit_events_should_say_nothing_about_them() {
            try (CapturedLog captured = CapturedLog.capturing(PropertiesValidator.class)) {
                publishing.withPropertyValues("audit.http.enabled=true",
                                "audit.http.openapi-rest-spec=yot-results-distribution-openapi.yaml")
                        .run(context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(captured.messages())
                                    .as("the transport is on and configured, so the filter is"
                                            + " built and every call it sees is published")
                                    .isEmpty();
                        });
            }
        }

        /** The other side of that gate: the HTTP half on, the transport off, no parser, no glob. */
        @Test
        void an_audit_filter_over_a_transport_that_is_off_should_start_without_a_spec_key() {
            deployed.withPropertyValues("audit.http.enabled=true", "cp.audit.enabled=false")
                    .run(context -> assertThat(context)
                            .as("every audit.http.* bean is inside the class cp.audit.enabled"
                                    + " gates, so nothing resolves the document and nothing traps")
                            .hasNotFailed());
        }

        @Test
        void a_zero_supersede_max_age_refuses_to_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.operations.supersede-max-age=0s").run(context -> {
                        assertThat(context)
                                .as("a bound of zero admits no instant at all, so the endpoint"
                                        + " refuses every call it is given and says the argument"
                                        + " was too old - which is a configuration error wearing a"
                                        + " refusal's clothes")
                                .hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.operations.supersede-max-age");
                    });
        }

        @Test
        void a_negative_lock_wait_refuses_to_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "yotresultsdistribution.operations.lock-wait=-1s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("yotresultsdistribution.operations.lock-wait");
                    });
        }

        @Test
        void a_pod_with_the_whole_audit_path_configured_should_start() {
            publishing.withPropertyValues("audit.http.enabled=true",
                            "audit.http.openapi-rest-spec=yot-results-distribution-openapi.yaml")
                    .run(context -> assertThat(context)
                            .as("the counterpart every refusal above needs: a transport that"
                                    + " names somewhere, a port in range and a document for the"
                                    + " filter to read")
                            .hasNotFailed());
        }
    }

    /** The flag's connection-string setting, signed with the published local pair. */
    private static String localPairAt(final String endpoint) {
        return FLAG_CONNECTION_STRING + "=Endpoint=" + endpoint + ";Id="
                + FeatureFlagProperties.PUBLISHED_LOCAL_ID + ";Secret=" + LOCAL_PAIR_SECRET;
    }
}
