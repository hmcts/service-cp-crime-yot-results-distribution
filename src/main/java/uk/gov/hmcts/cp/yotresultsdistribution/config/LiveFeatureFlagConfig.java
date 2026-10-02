package uk.gov.hmcts.cp.yotresultsdistribution.config;

import com.azure.core.http.HttpClient;
import com.azure.core.http.policy.FixedDelayOptions;
import com.azure.core.http.policy.RetryOptions;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.appconfig.AppConfigurationFlagReader;
import uk.gov.hmcts.cp.yotresultsdistribution.application.FeatureFlagReader;

/**
 * The real reader of the one lever.
 *
 * <p>The counterpart of {@link StubGenerationConfig}'s flag bean, and chosen by the same key: this
 * one is contributed where {@code yotresultsdistribution.generation.flag-mode} is LIVE, which is the default
 * and is everywhere the service is deployed, and the stub is contributed where it says STUB.
 * {@link PropertiesValidator} refuses STUB outright on a deployed pod, so the pair cannot be resolved
 * the wrong way round in an environment that matters.
 *
 * <p><strong>It is not conditional on generation being enabled</strong>, and that is a decision
 * rather than an omission (T040/T041, 2026-09-21). {@code GET /operations/flag} is served on every
 * pod - it is the endpoint an operator checks the cutover with, and a pod that renders nothing can
 * still be asked what the lever says - so the reader has to exist wherever the operations API does.
 * What the generation switch still decides is what is <em>required</em>: {@link PropertiesValidator}
 * asks for the connection string and the label only once generation is on, so a pod with no nightly
 * job and no connection string gets a reader with no store behind it, which answers
 * {@code NOT_CONFIGURED} - a reading with a cause on it, which is the honest answer to "what does
 * the flag say" on a pod that cannot see it.
 *
 * <p><strong>One way to authorise the read: the App Configuration connection string</strong>
 * (constitution 5.2.0). On a deployed pod it is the estate's one, the value the WildFly contexts
 * read from Vault, set in the deployment's values - no App Configuration role can be assigned to
 * this service's identity, so the workload-identity read it replaced could never have been granted. On the compose loop it is the published local
 * pair, which {@link PropertiesValidator} refuses anywhere a real flag is read. Both sign with HMAC
 * rather than a bearer token, so the one reader reads a plain-HTTP stub exactly as it reads the real
 * store: the key in the path, the label in the query, the media type, the fail-closed reading and
 * the budget are the deployed ones either way.
 *
 * <p>Excluded from the {@code test} profile alongside the rest of the live wiring.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class LiveFeatureFlagConfig {

    /** The setting a refusal names, since the value itself is never named. */
    private static final String CONNECTION_STRING_SETTING =
            "yotresultsdistribution.feature.connection-string";

    /** The read is the whole budget, so the SDK is asked once and never asked again. */
    private static final RetryOptions NO_RETRIES =
            new RetryOptions(new FixedDelayOptions(0, Duration.ZERO));

    /**
     * The flag port, served by the App Configuration reader.
     *
     * @param properties where the flag is read from, and under which key, label and budget
     * @return the port
     */
    @Bean
    @ConditionalOnProperty(prefix = "yotresultsdistribution.generation", name = "flag-mode",
            havingValue = "LIVE", matchIfMissing = true)
    public FeatureFlagReader featureFlagReader(final FeatureFlagProperties properties) {
        return new AppConfigurationFlagReader(properties, connectionStringClient(properties));
    }

    /**
     * The client the flag is read through, or {@code null} where no connection string is configured.
     *
     * <p>No connection string is no client, which the reader reads as {@code NOT_CONFIGURED} - a
     * skipped run with a cause on it.
     *
     * <p><strong>A string the builder cannot parse is refused here, in this service's own
     * words.</strong> {@code ConfigurationClientBuilder.connectionString} only stores the value;
     * {@code buildClient()} parses it, and throws {@code IllegalArgumentException} with the whole
     * value - secret included - in its message.
     * {@link PropertiesValidator} refuses every shape the builder would, under the setting's name,
     * but nothing orders it before this bean and bean order is not a thing to rely on for a secret.
     * So the builder's exception is classified and rethrown as one naming only the setting, with no
     * cause attached: the cause is the SDK's message, and carrying it would carry the value
     * (constitution Principle VII - never attach a throwable this service did not write).
     *
     * <p>Retries off and every leg bounded: the budget is the whole of the read, three SDK attempts
     * inside it would spend the run's decision on the first attempt's back-off, and a leg nobody
     * bounded is an abandoned read holding its thread and its connection until the SDK's own default
     * gives up. The four timeouts come from {@link AppConfigurationFlagReader#httpClientFor}; the
     * outer deadline is the reader's own.
     *
     * @param properties where the flag is read from
     * @return the client, or {@code null} where no store is configured
     * @throws IllegalStateException if the connection string cannot be parsed; it names the setting
     *                               and quotes nothing
     */
    private static ConfigurationClient connectionStringClient(final FeatureFlagProperties properties) {
        ConfigurationClient client = null;
        if (properties.hasConnectionString()) {
            // Built outside the try, so the catch below guards only the parse that can quote the
            // value and never mislabels an HTTP-client failure as an unparseable string.
            final HttpClient httpClient = AppConfigurationFlagReader.httpClientFor(properties);
            try {
                client = new ConfigurationClientBuilder()
                        .connectionString(properties.connectionString())
                        .retryOptions(NO_RETRIES)
                        .httpClient(httpClient)
                        .buildClient();
            } catch (final IllegalArgumentException unparseable) {
                // Classified and rethrown without its cause: the SDK's message quotes the value.
                throw new IllegalStateException(CONNECTION_STRING_SETTING + " could not be parsed by"
                        + " the App Configuration client - it must be Endpoint=<an http or https URL"
                        + " with a host>;Id=<the key's id>;Secret=<the key's Base64 secret>, as Key"
                        + " Vault holds it. The value is not quoted here");
            }
        }
        return client;
    }
}
