package uk.gov.hmcts.cp.yotresultsdistribution.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.notificationnotify.NotificationNotifyClient;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterNotifier;

/**
 * The real last leg of the nightly run: notificationnotify, which tells the Youth Offending Teams.
 *
 * <p>The counterpart of {@link StubGenerationConfig}'s notifier bean, chosen by the same key: this
 * is contributed where {@code yotresultsdistribution.generation.nn-mode} is LIVE, which is the default and is
 * everywhere the service is deployed, and the refusing stand-in where it says STUB.
 * {@link PropertiesValidator} refuses STUB outright wherever generation is enabled or a namespace is
 * set, so the pair cannot be resolved the wrong way round in an environment that matters
 * (constitution Principle V).
 *
 * <p>Its own file rather than a third bean on {@link LiveGenerationConfig}, because the two halves
 * are asked for at different moments and fail differently: generation is a payload, a render request
 * and a document, and notification is what happens to a document that already exists. A deployment
 * reading which downstream a pod really talks to should not have to tell the render mode from the
 * e-mail mode inside one class.
 *
 * <p>Conditional on generation being enabled as well, because a deployment that runs no nightly job
 * renders nothing and so has nothing to send: the client would hold an endpoint and an identity for
 * a call nobody makes.
 *
 * <p><strong>What is wired here and what is not.</strong> The endpoint and the identity are the
 * client's, because they are how the call is made. The template is not: the
 * {@code yotresultsdistribution.email.templates.cr_standard} id is validated for shape at startup by
 * {@link PropertiesValidator} (defect fix P9) and reaches the wire on the
 * {@code register_notification} row, which is minted by
 * {@code application/RegisterNotifierService} before any POST and is the evidence of what was sent.
 * It is deliberately not a bean of this configuration: the notifier service is contributed whatever
 * the mode is, and a template read only under LIVE would be a dependency it could not resolve
 * against the stub.
 *
 * <p>Excluded from the {@code test} profile alongside the rest of the live wiring.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "yotresultsdistribution.generation", name = "enabled", havingValue = "true")
public class LiveNotificationConfig {

    /** The prefix the mode key lives under. */
    private static final String GENERATION = "yotresultsdistribution.generation";

    /** The value the mode key holds where the real adapter is wanted, and the default. */
    private static final String LIVE = "LIVE";

    /**
     * The notifier port, served by notificationnotify's command API.
     *
     * <p>Both timeouts are set deliberately, as they are on every other client this service builds:
     * a POST with no read timeout can outlive the bound that was supposed to hold it, and for this
     * flow that is a run hanging on one recipient while the rest of the batch waits to be told.
     *
     * <p>The retry loop is not here. The client classifies one attempt through the shared
     * {@link uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy} and the notifier service
     * decides whether the run's budget holds another, because the budget is the run's knowledge and
     * not notificationnotify's.
     *
     * @param properties   the bound settings, for the endpoint, the identity and the two timeouts -
     *                     one identity and one transport for both downstream calls, because the run
     *                     is one caller
     * @param objectMapper the shared mapper, so the command body is serialised exactly as
     *                     {@code NotificationNotifyClientTest} pins it
     * @return the port
     */
    @Bean
    @ConditionalOnProperty(prefix = GENERATION, name = "nn-mode", havingValue = LIVE,
            matchIfMissing = true)
    public RegisterNotifier registerNotifier(
            final YotResultsDistributionProperties properties, final ObjectMapper objectMapper) {

        final YotResultsDistributionProperties.Endpoints endpoints = properties.endpoints();
        return new NotificationNotifyClient(
                RestClient.builder()
                        .baseUrl(endpoints.notificationnotify())
                        .requestFactory(requestFactory(endpoints))
                        .build(),
                endpoints.systemUserId(),
                objectMapper);
    }

    /**
     * A request factory with both timeouts set.
     *
     * <p>The simple factory rather than a pooled client, for the reason the other clients use it:
     * this is one POST per recipient on one evening, and a pooled client would hold background
     * threads for the lifetime of every context to save nothing.
     */
    private static ClientHttpRequestFactory requestFactory(
            final YotResultsDistributionProperties.Endpoints endpoints) {
        final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(endpoints.connectTimeout());
        factory.setReadTimeout(endpoints.readTimeout());
        return factory;
    }
}
