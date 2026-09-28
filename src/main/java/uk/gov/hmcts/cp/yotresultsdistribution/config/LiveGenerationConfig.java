package uk.gov.hmcts.cp.yotresultsdistribution.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.systemdocgenerator.SystemDocGeneratorClient;
import uk.gov.hmcts.cp.yotresultsdistribution.application.DocumentRenderer;

/**
 * The real downstream of the nightly run: systemdocgenerator, asked to render a payload.
 *
 * <p>The counterpart of {@link StubGenerationConfig}'s renderer, chosen by the same key: this one
 * is contributed where {@code yotresultsdistribution.generation.sdg-mode} is LIVE, which is the default and
 * is everywhere the service is deployed, and the stand-in where it says STUB.
 * {@link PropertiesValidator} refuses STUB outright wherever generation is enabled or a namespace is
 * set, so the pair cannot be resolved the wrong way round in an environment that matters
 * (constitution Principle V).
 *
 * <p>Conditional on generation being enabled as well, because a deployment that runs no nightly job
 * has nothing to render: the client would hold an endpoint and an identity for a call nobody makes.
 *
 * <p><strong>The payload store is no longer here</strong>, and review gate 7 is why. A file in the
 * framework file service is what both outward legs have in common - the run stores a render payload,
 * the morning report stores its CSV - so a port declared on a configuration conditional on the
 * generation half made a pod in FR-004's shape with the e-mail output on a pod that could not start.
 * It is in {@link FileServiceConfig} now, behind {@link FileServiceNeeded}, with the same mode key
 * choosing the same two adapters. Nothing about either class changed; only where its bean is
 * declared.
 *
 * <p>Excluded from the {@code test} profile alongside the rest of the live wiring.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "yotresultsdistribution.generation", name = "enabled", havingValue = "true")
public class LiveGenerationConfig {

    /** The prefix the renderer's mode key lives under. */
    private static final String GENERATION = "yotresultsdistribution.generation";

    /** The value each mode key holds where the real adapter is wanted, and the default. */
    private static final String LIVE = "LIVE";

    /**
     * The renderer port, served by systemdocgenerator's command API.
     *
     * <p>Both timeouts are set deliberately, as they are on every other client this service builds:
     * the render request is spent inside the run's deadline, and a POST with no read timeout can
     * outlive the bound that was supposed to hold it - which for this flow is a night that generates
     * nothing behind the batch it hung on.
     *
     * <p>The retry loop is not here. The client classifies one attempt through the shared
     * {@link uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy} and
     * {@link uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterGenerationService} decides whether
     * the run's budget holds another, because the budget is the run's knowledge and not
     * systemdocgenerator's.
     *
     * @param properties   the bound settings, for the endpoint, the identity and the two timeouts
     * @param objectMapper the shared mapper, so the command body is serialised exactly as
     *                     {@code SystemDocGeneratorClientTest} pins it
     * @return the port
     */
    @Bean
    @ConditionalOnProperty(prefix = GENERATION, name = "sdg-mode", havingValue = LIVE,
            matchIfMissing = true)
    public DocumentRenderer documentRenderer(
            final YotResultsDistributionProperties properties, final ObjectMapper objectMapper) {

        final YotResultsDistributionProperties.Endpoints endpoints = properties.endpoints();
        return new SystemDocGeneratorClient(
                RestClient.builder()
                        .baseUrl(endpoints.systemdocgenerator())
                        .requestFactory(requestFactory(endpoints))
                        .build(),
                endpoints.systemUserId(),
                objectMapper);
    }

    /**
     * A request factory with both timeouts set.
     *
     * <p>The simple factory rather than a pooled client, for the reason the other clients use it:
     * this is one POST per batch on one evening, and a pooled client would hold background threads
     * for the lifetime of every context to save nothing.
     */
    private static ClientHttpRequestFactory requestFactory(
            final YotResultsDistributionProperties.Endpoints endpoints) {
        final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(endpoints.connectTimeout());
        factory.setReadTimeout(endpoints.readTimeout());
        return factory;
    }
}
