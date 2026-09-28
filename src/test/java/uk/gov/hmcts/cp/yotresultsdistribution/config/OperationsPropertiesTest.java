package uk.gov.hmcts.cp.yotresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Holds the operations API's settings to the values the plan's configuration table documents.
 *
 * <p>The binding half of increment 005's configuration surface, written the way
 * {@code ReportPropertiesTest} writes 003's: what the settings <em>refuse</em> is
 * {@code ConfigurationValidationTest.OperationsSettings}, in the suite that owns every other
 * startup refusal this service makes, and what they bind to is here - because a default nobody
 * reads back is a default that moves without anybody noticing. That class also holds the cases
 * saying what is <strong>not</strong> refused: {@code authz.http.enabled} and
 * {@code audit.http.enabled} are an operator's to set, they default on in {@code application.yaml},
 * and a pod with either off starts (constitution 5.0.0).
 *
 * <p>All three matter for a different reason. {@code enabled} defaults <strong>true</strong>
 * (FR-044) because an operator surface that has to be switched on is a surface a support engineer
 * discovers is missing at the moment they need it; it is deployment shape and never a cutover
 * lever. {@code supersede-max-age} defaults to thirty days because the endpoint it bounds gives a
 * period of registers up irreversibly and its command was bounded only by being hard to reach.
 * {@code lock-wait} defaults to <strong>zero</strong> because zero is a non-blocking attempt at the
 * nightly lock, and any other value is a request thread waiting on a scheduler.
 */
class OperationsPropertiesTest {

    /** The emulator connection string, the local and CI credential. */
    private static final String CONNECTION_STRING_PROPERTY =
            "yotresultsdistribution.servicebus.connection-string="
                    + "Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;"
                    + "SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;";

    /** The identity the query-side payload fallback authorises with; the live source demands one. */
    private static final String PAYLOAD_IDENTITY_PROPERTY =
            "yotresultsdistribution.results.system-user-id=9f61bdbb-6f1a-4c0f-9a3d-6b8f0f1c2a44";

    private static final String PROGRESSION_ENDPOINT_PROPERTY =
            "yotresultsdistribution.progression.base-url=http://localhost:8080";

    private static final String PROGRESSION_IDENTITY_PROPERTY =
            "yotresultsdistribution.progression.system-user-id=4d3c2b1a-9e8f-4a7b-8c6d-5e4f3a2b1c09";

    private static final String REFDATA_ENDPOINT_PROPERTY =
            "yotresultsdistribution.referencedata.base-url=http://localhost:8080";

    private static final String REFDATA_IDENTITY_PROPERTY =
            "yotresultsdistribution.referencedata.system-user-id=2c7b1e64-0f4a-4f0e-9b2c-8d1a6f3e5c07";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(PropertiesTestConfiguration.class)
                    .withPropertyValues(CONNECTION_STRING_PROPERTY, PAYLOAD_IDENTITY_PROPERTY,
                            PROGRESSION_ENDPOINT_PROPERTY, PROGRESSION_IDENTITY_PROPERTY,
                            REFDATA_ENDPOINT_PROPERTY, REFDATA_IDENTITY_PROPERTY);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({YotResultsDistributionProperties.class, GenerationProperties.class,
        FeatureFlagProperties.class, ReportProperties.class, OperationsProperties.class})
    @Import(PropertiesValidator.class)
    static class PropertiesTestConfiguration {
    }

    @Test
    @DisplayName("every operations setting falls back to the documented default")
    void every_operations_setting_should_fall_back_to_the_documented_default() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            final OperationsProperties operations = context.getBean(OperationsProperties.class);

            assertThat(operations.enabled())
                    .as("FR-044: the endpoints are served unless a deployment says otherwise. An "
                            + "operator surface that has to be switched on is one a support "
                            + "engineer finds missing at the moment they need it")
                    .isTrue();
            assertThat(operations.supersedeMaxAge())
                    .as("thirty days: supersede gives a period of registers up irreversibly, and "
                            + "its command was bounded only by being hard to reach")
                    .isEqualTo(Duration.ofDays(30));
            assertThat(operations.lockWait())
                    .as("zero is a non-blocking attempt at the nightly lock; any other value is a "
                            + "request thread waiting on a scheduler")
                    .isEqualTo(Duration.ZERO);
        });
    }

    @Test
    @DisplayName("every operations setting can be overridden by a deployment")
    void every_operations_setting_should_be_overridable() {
        runner.withPropertyValues(
                "yotresultsdistribution.operations.enabled=false",
                "yotresultsdistribution.operations.supersede-max-age=7d",
                "yotresultsdistribution.operations.lock-wait=15s").run(context -> {
                    assertThat(context).hasNotFailed();
                    final OperationsProperties operations =
                            context.getBean(OperationsProperties.class);

                    assertThat(operations.enabled()).isFalse();
                    assertThat(operations.supersedeMaxAge()).isEqualTo(Duration.ofDays(7));
                    assertThat(operations.lockWait()).isEqualTo(Duration.ofSeconds(15));
                });
    }
}
