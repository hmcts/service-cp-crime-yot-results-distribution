package uk.gov.hmcts.cp.yotresultsdistribution.support;

import com.azure.core.http.policy.FixedDelayOptions;
import com.azure.core.http.policy.RetryOptions;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import java.time.Duration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.appconfig.AppConfigurationFlagReader;
import uk.gov.hmcts.cp.yotresultsdistribution.application.FeatureFlagReader;
import uk.gov.hmcts.cp.yotresultsdistribution.config.FeatureFlagProperties;

/**
 * The one bean an end-to-end suite replaces, and the only one.
 *
 * <p>{@code LiveFeatureFlagConfig} builds its reader on the pod's workload identity, and a
 * federated-token exchange is the single leg of the flag read no fixture can stand up: it happens
 * against Entra ID, not against the store. So the reader is rebuilt here over the same
 * {@link AppConfigurationFlagReader}, the same SDK client and the same
 * {@link FeatureFlagProperties} - and a connection string in place of the credential, which is what
 * {@code AppConfigurationFlagReaderTest} already reads through.
 *
 * <p>What that leaves real is everything the suites are about: the key in the path, the label in the
 * query, the media type, the fail-closed reading of a store that answers 500, and the budget the
 * whole read is bounded at. The bean name is the live configuration's, so this overrides it rather
 * than standing beside it as a second candidate - which is why the suites set
 * {@code spring.main.allow-bean-definition-overriding}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class GenerationStackConfiguration {

    /**
     * The flag port, over a client pointed at this stack's App Configuration stub.
     *
     * @param properties where the flag is read from, and under which key, label and budget
     * @return the port, under the name the live configuration would have used
     */
    @Bean("featureFlagReader")
    public FeatureFlagReader stackFeatureFlagReader(final FeatureFlagProperties properties) {
        return new AppConfigurationFlagReader(properties, clientFor(properties));
    }

    /**
     * The SDK client the reader reads through.
     *
     * <p>Retries off, because what these suites assert about the store is that it was asked once and
     * that the answer decided the night; an SDK retry policy would make a 500 into three 500s and
     * the skipped-run count into a statement about the SDK.
     */
    private static ConfigurationClient clientFor(final FeatureFlagProperties properties) {
        return new ConfigurationClientBuilder()
                .connectionString("Endpoint=" + properties.endpoint()
                        + ";Id=" + GenerationStackSupport.STORE_ID
                        + ";Secret=" + GenerationStackSupport.STORE_SECRET)
                .retryOptions(new RetryOptions(new FixedDelayOptions(0, Duration.ZERO)))
                .buildClient();
    }
}
