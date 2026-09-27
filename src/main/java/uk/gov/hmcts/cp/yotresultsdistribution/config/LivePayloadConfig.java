package uk.gov.hmcts.cp.yotresultsdistribution.config;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPause;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.payload.CachedHearingPayloadAdapter;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.payload.HearingPayloadCache;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.payload.HearingPayloadQuery;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.payload.LettuceHearingPayloadCache;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.payload.ResultsQueryHearingPayloadClient;
import uk.gov.hmcts.cp.yotresultsdistribution.application.HearingPayloadSource;

/**
 * The real payload source: the cache, and the query side behind it.
 *
 * <p>Selected by {@code yotresultsdistribution.payload.mode}, and selected by default — a service that has to
 * be told to fetch payloads is a service that will one day be deployed not fetching them.
 * {@link StubPayloadConfig} is the other half of the pair, and exactly one of the two contributes a
 * bean.
 *
 * <p>The two clients are separate beans rather than locals inside the adapter's method, because both
 * hold connections that have to be released. Declared this way Spring closes them at shutdown; built
 * inside a method they would leak an event-loop group and a connection pool on every context close,
 * which the container suites do dozens of times per build.
 *
 * <p>Excluded from the {@code test} profile alongside the rest of the pipeline wiring, which that
 * profile has no store for.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "yotresultsdistribution.payload", name = "mode", havingValue = "LIVE",
        matchIfMissing = true)
public class LivePayloadConfig {

    /**
     * The Lettuce client for the payload cache.
     *
     * <p>Creating it opens nothing — the connection is made on the first read — so a cache that is
     * down cannot stop the service from starting and reporting why. The connect timeout is applied
     * to the socket options here because {@link RedisURI} carries only the command timeout, and a
     * connect with no bound is a run that outlives the claim it holds.
     *
     * @param properties the bound settings
     * @return the client, shut down with the context
     */
    @Bean(destroyMethod = "shutdown")
    public RedisClient courtRegisterRedisClient(final YotResultsDistributionProperties properties) {
        final YotResultsDistributionProperties.Redis redis = properties.payload().redis();
        final RedisClient client = RedisClient.create(cacheUri(redis));
        client.setOptions(client.getOptions().mutate()
                .socketOptions(client.getOptions().getSocketOptions().mutate()
                        .connectTimeout(redis.connectTimeout())
                        .build())
                .build());
        return client;
    }

    /**
     * The address the cache is reached on, TLS included.
     *
     * <p>Separated from the client so the one security decision in it can be asserted.
     * <strong>Certificates are verified wherever TLS is used — defect fix C15.</strong> The function
     * app connects with {@code rejectUnauthorized: false}
     * ({@code HearingResultedCacheQuery/index.js:107}), which is a defect rather than a setting: the
     * payload it reads is a whole hearing about named children, and a connection that will accept
     * any certificate will accept one somebody else presents. A fix with nothing asserting it is a
     * fix that reverts the next time somebody meets a self-signed certificate in a test environment
     * and reaches for the setting that makes the error go away, so {@code LivePayloadConfigTest}
     * reads this back.
     *
     * <p>Verification follows TLS rather than standing on its own: a developer's local server speaks
     * plain TCP and has no certificate to verify, and a second switch is a second thing that can be
     * turned off in the environment that matters.
     *
     * @param redis the cache settings
     * @return the URI the Lettuce client connects with
     */
    /* default */ static RedisURI cacheUri(final YotResultsDistributionProperties.Redis redis) {
        final RedisURI.Builder uri = RedisURI.builder()
                .withHost(redis.host())
                .withPort(redis.port())
                .withSsl(redis.ssl())
                .withVerifyPeer(redis.ssl())
                .withTimeout(redis.commandTimeout());
        if (redis.password() != null && !redis.password().isBlank()) {
            // An empty password is not a credential: sending one fails the handshake against a
            // server that expects none, which is every local server.
            uri.withPassword(redis.password().toCharArray());
        }
        return uri.build();
    }

    /**
     * The payload cache. Closed at shutdown: the bean instance is {@link AutoCloseable}.
     *
     * @param redisClient  the configured client
     * @param objectMapper the shared mapper, so a cached payload is read exactly as any other JSON
     * @return the cache
     */
    @Bean
    public HearingPayloadCache hearingPayloadCache(final RedisClient redisClient,
            final ObjectMapper objectMapper) {
        return new LettuceHearingPayloadCache(redisClient, objectMapper);
    }

    /**
     * The query-side fallback.
     *
     * <p>Both timeouts are set deliberately. A read with no read timeout can outlive the run's
     * processing deadline and then its claim, which turns a slow query side into a request that two
     * runners believe they own — and for this flow a second runner's POST is a second register.
     *
     * @param properties   the bound settings
     * @param objectMapper the shared mapper
     * @return the query-side port
     */
    @Bean
    public HearingPayloadQuery hearingPayloadQuery(final YotResultsDistributionProperties properties,
            final ObjectMapper objectMapper) {
        final YotResultsDistributionProperties.Fallback fallback = properties.payload().fallback();
        return new ResultsQueryHearingPayloadClient(
                RestClient.builder()
                        .baseUrl(properties.results().baseUrl())
                        .requestFactory(requestFactory(fallback.connectTimeout(),
                                fallback.readTimeout()))
                        .build(),
                properties.results().systemUserId(),
                objectMapper,
                new RetryPolicy(fallback.maxAttempts(), fallback.initialBackoff(),
                        fallback.maxBackoff(),
                        fallback.connectTimeout().plus(fallback.readTimeout())),
                (RetryPause) Thread::sleep);
    }

    /**
     * The payload port, served by the cache with the query side behind it.
     *
     * @param cache      the cache, read first under both key forms
     * @param query      the query side, asked when neither key answered
     * @param properties the bound settings, for the key prefix the producer writes under
     * @return the port
     */
    @Bean
    public HearingPayloadSource hearingPayloadSource(final HearingPayloadCache cache,
            final HearingPayloadQuery query, final YotResultsDistributionProperties properties) {
        return new CachedHearingPayloadAdapter(cache, query,
                properties.payload().redis().keyPrefix());
    }

    /**
     * A request factory with both timeouts set.
     *
     * <p>The simple factory rather than a pooled client: this is one small GET on a cache miss, and a
     * pooled client would hold background threads for the lifetime of every context — which the
     * container suites create and close dozens of times in a build.
     */
    private static ClientHttpRequestFactory requestFactory(
            final Duration connectTimeout, final Duration readTimeout) {
        final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
