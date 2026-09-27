package uk.gov.hmcts.cp.yotresultsdistribution.config;

import com.azure.core.credential.TokenCredential;
import com.azure.core.http.policy.FixedDelayOptions;
import com.azure.core.http.policy.RetryOptions;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.identity.WorkloadIdentityCredentialBuilder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.appconfig.AppConfigurationFlagReader;
import uk.gov.hmcts.cp.yotresultsdistribution.application.FeatureFlagReader;

/**
 * The real reader of the one lever.
 *
 * <p>The counterpart of {@link StubGenerationConfig}'s flag bean, and chosen by the same key: this
 * one is contributed where {@code yotresultsdistribution.generation.flag-mode} is LIVE, which is the default
 * and is everywhere the service is deployed, and the stub is contributed where it says STUB.
 * {@link PropertiesValidator} refuses STUB outright wherever the deployed credential source is in
 * use, so the pair cannot be resolved the wrong way round in an environment that matters.
 *
 * <p><strong>It is not conditional on generation being enabled</strong>, and that is a decision
 * rather than an omission (T040/T041, 2026-09-21). {@code GET /operations/flag} is served on every
 * pod - it is the endpoint an operator checks the cutover with, and a pod that renders nothing can
 * still be asked what the lever says - so the reader has to exist wherever the operations API does.
 * What the generation switch still decides is what is <em>required</em>: {@link PropertiesValidator}
 * asks for the endpoint and the label only once generation is on, so a pod with no nightly job and
 * no App Configuration endpoint configured gets a reader with no store behind it, which answers
 * {@code NOT_CONFIGURED} - a reading with a cause on it, which is the honest answer to "what does
 * the flag say" on a pod that cannot see it.
 *
 * <p>Which is also why the workload identity is built only where an endpoint names a store. The
 * three projected variables are a deployed pod's and a laptop has none of them; refusing to start
 * for want of a credential to read a store nobody configured would make the flag endpoint cost
 * every non-generating pod its start-up. Where an endpoint <em>is</em> named the refusal stands
 * exactly as it did, and generation on with no endpoint is still refused before this bean is built.
 *
 * <p><strong>Which identity the read is authorised with is {@code yotresultsdistribution.feature.credential}
 * and nothing else.</strong> Both values contribute the same reader over the same properties, so
 * the endpoint, the key, the label, the budget and the fail-closed parsing are the deployed ones
 * either way; only the credential inside differs. {@code workload-identity} is the deployed value
 * and the default. {@code local-test} exists because a bearer credential cannot read a stand-in at
 * all - Azure refuses to send a token over anything but TLS - and {@link PropertiesValidator}
 * refuses it wherever the endpoint names a real store or the pod is a deployed one.
 *
 * <p>Excluded from the {@code test} profile alongside the rest of the live wiring.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class LiveFeatureFlagConfig {

    /** The pod's own client id, projected by the AKS workload-identity webhook. */
    private static final String CLIENT_ID = "AZURE_CLIENT_ID";

    /** The directory the pod's identity lives in, projected by the same webhook. */
    private static final String TENANT_ID = "AZURE_TENANT_ID";

    /** Where the projected federated token is mounted. */
    private static final String TOKEN_FILE = "AZURE_FEDERATED_TOKEN_FILE";

    /**
     * The identity {@code local-test} reads under: invented here and a secret of nothing.
     *
     * <p>The same fixed, published pair {@code AppConfigurationFlagReaderTest} and
     * {@code GenerationStackConfiguration} already read the stub through. It authorises nothing: no
     * Azure store has ever been given it, and the only thing that answers it is a WireMock mapping
     * that checks no credential at all.
     */
    private static final String LOCAL_TEST_ID = "0-l0-s0:yotresultsdistributionlocal";

    /** The HMAC secret that identity signs with; Base64 of {@code not-a-secret}. */
    private static final String LOCAL_TEST_SECRET = "bm90LWEtc2VjcmV0";

    /** The read is the whole budget, so the SDK is asked once and never asked again. */
    private static final RetryOptions NO_RETRIES =
            new RetryOptions(new FixedDelayOptions(0, Duration.ZERO));

    /**
     * The flag port, served by the App Configuration reader.
     *
     * <p>One reader, two identities, and the setting decides which:
     * {@link FeatureFlagProperties.Credential#WORKLOAD_IDENTITY} is the pod's own and is what every
     * deployment gets, {@link FeatureFlagProperties.Credential#LOCAL_TEST} is the compose loop's
     * and is refused at startup anywhere the reading could matter.
     *
     * @param properties  where the flag is read from, and under which key, label and budget
     * @param environment the deployment's own environment, which carries the pod's identity
     * @return the port
     */
    @Bean
    @ConditionalOnProperty(prefix = "yotresultsdistribution.generation", name = "flag-mode",
            havingValue = "LIVE", matchIfMissing = true)
    public FeatureFlagReader featureFlagReader(
            final FeatureFlagProperties properties, final Environment environment) {
        return switch (properties.credential()) {
            case WORKLOAD_IDENTITY -> new AppConfigurationFlagReader(properties,
                    namesAStore(properties) ? workloadIdentity(environment) : null);
            case LOCAL_TEST -> new AppConfigurationFlagReader(properties, localTestClient(properties));
        };
    }

    /**
     * Whether a store is configured to read the flag from at all.
     *
     * @param properties where the flag is read from
     * @return {@code true} where an endpoint names one
     */
    private static boolean namesAStore(final FeatureFlagProperties properties) {
        return properties.endpoint() != null && !properties.endpoint().isBlank();
    }

    /**
     * The client the local loop reads through: this stack's stub, on an identity nothing authorises.
     *
     * <p>A connection string rather than a credential, and that is the whole of the difference. The
     * SDK sends a {@code TokenCredential}'s token only over TLS - {@code
     * BearerTokenAuthenticationPolicy} refuses any other URL before a socket is opened - so a
     * WireMock stand-in for App Configuration, which speaks plain HTTP, cannot be read on a pod's
     * workload identity at all. Signing the read with a fixed HMAC pair instead is what leaves the
     * <em>reader</em> real: the key in the path, the label in the query, the media type, the
     * fail-closed reading of a store that answers 500 and the budget the whole read is bounded at
     * are all the deployed ones, which is exactly the trade
     * {@code GenerationStackConfiguration} already makes for the end-to-end suites.
     *
     * <p>Retries off and every leg bounded, for the reason the reader's own client has them so: the
     * budget is the whole of the read, three SDK attempts inside it would spend the run's decision
     * on the first attempt's back-off, and a leg nobody bounded is an abandoned read holding its
     * thread and its connection until the SDK's own default gives up. The four timeouts come from
     * {@link AppConfigurationFlagReader#httpClientFor}, which is the same factory the deployed
     * credential's client is built through - one client-building path, so this one cannot come to
     * differ from it by anything but the credential. The outer deadline is the reader's, so it
     * holds here too.
     *
     * <p>No endpoint is no client, which the reader reads as {@code NOT_CONFIGURED} - a skipped run
     * with a cause on it. {@link PropertiesValidator} has already refused the case that matters,
     * generation enabled with no endpoint, but bean order is not a thing to rely on for it.
     *
     * @param properties where the flag is read from
     * @return the client, or {@code null} where no store is configured
     */
    private static ConfigurationClient localTestClient(final FeatureFlagProperties properties) {
        ConfigurationClient client = null;
        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            client = new ConfigurationClientBuilder()
                    .connectionString("Endpoint=" + properties.endpoint()
                            + ";Id=" + LOCAL_TEST_ID + ";Secret=" + LOCAL_TEST_SECRET)
                    .retryOptions(NO_RETRIES)
                    .httpClient(AppConfigurationFlagReader.httpClientFor(properties))
                    .buildClient();
        }
        return client;
    }

    /**
     * The identity the store authorises the read against, built the way the producer builds its own.
     *
     * <p>Workload identity and never a secret: the client id, the tenant and the path to the
     * projected federated token, exactly the three facts {@code InformantRegisterQueuePublisher}
     * builds its Service Bus credential from in the service that produces this service's inbound
     * commands. Reading one flag the same way the producer reaches one queue is what keeps the
     * platform ask (an {@code App Configuration Data Reader} role assignment for the
     * {@code yotresultsdistribution} identity, design §8) a single, checkable statement.
     *
     * <p><strong>A missing variable is a refusal to start, not a skipped night.</strong> The three
     * are projected into a deployed pod by the AKS webhook and are absent from a laptop, so a local
     * run that asks for a LIVE reader is a run that would have failed at 18:00 with an unreadable
     * flag every night, looking exactly like a store outage. The message names each variable that is
     * missing, and names both local alternatives - the real reader on the {@code local-test}
     * credential, and the stub - because the alternative is the thing a reader of the failure
     * actually needs.
     */
    private static TokenCredential workloadIdentity(final Environment environment) {
        final String clientId = environment.getProperty(CLIENT_ID);
        final String tenantId = environment.getProperty(TENANT_ID);
        final String tokenFile = environment.getProperty(TOKEN_FILE);
        final List<String> missing = new ArrayList<>();
        addIfBlank(missing, CLIENT_ID, clientId);
        addIfBlank(missing, TENANT_ID, tenantId);
        addIfBlank(missing, TOKEN_FILE, tokenFile);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    String.join(", ", missing) + " must be set when"
                            + " yotresultsdistribution.generation.enabled is true and"
                            + " yotresultsdistribution.generation.flag-mode is LIVE - the App Configuration"
                            + " read is made on this pod's workload identity, and without it every"
                            + " nightly run would skip on an unreadable flag and look like a store"
                            + " outage. A deployed pod is given all three by the workload-identity"
                            + " webhook; a local run reads the compose stub through the same"
                            + " reader with yotresultsdistribution.feature.credential=local-test, or"
                            + " answers itself with yotresultsdistribution.generation.flag-mode=STUB");
        }
        return new WorkloadIdentityCredentialBuilder()
                .clientId(clientId)
                .tenantId(tenantId)
                .tokenFilePath(tokenFile)
                .build();
    }

    private static void addIfBlank(
            final List<String> missing, final String name, final String value) {
        if (value == null || value.isBlank()) {
            missing.add(name);
        }
    }
}
