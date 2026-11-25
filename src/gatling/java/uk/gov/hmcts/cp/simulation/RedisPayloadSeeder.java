package uk.gov.hmcts.cp.simulation;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Seeds the ephemeral validation Redis before a message is published. The envelope comes from
 * {@link HearingPayloadFixture}, which also backs the query-API WireMock response, so cache hits and
 * misses record the same registers.
 * An absent {@code gatling.redisUri} keeps local emulator runs on the query fallback.
 *
 * <p>Every key is written with an expiry ({@code gatling.redisTtlSeconds}, default six hours), so a
 * run pointed at a Redis that outlives it leaves no synthetic {@code INT_} payloads behind.
 */
final class RedisPayloadSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(RedisPayloadSeeder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RedisPayloadSeeder INSTANCE = create();

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final int hitPercent;
    private final long ttlSeconds;
    private final AtomicLong seeded = new AtomicLong();
    private final AtomicLong unseeded = new AtomicLong();

    private RedisPayloadSeeder(final String uri, final int configuredHitPercent,
                               final long configuredTtlSeconds) {
        if (configuredHitPercent < 0 || configuredHitPercent > 100) {
            throw new IllegalArgumentException("gatling.redisHitPercent must be between 0 and 100");
        }
        if (configuredTtlSeconds < 1) {
            throw new IllegalArgumentException("gatling.redisTtlSeconds must be at least 1");
        }
        hitPercent = configuredHitPercent;
        ttlSeconds = configuredTtlSeconds;
        client = RedisClient.create(uri);
        connection = client.connect();
        if (!"PONG".equals(connection.sync().ping())) {
            throw new IllegalStateException("Validation Redis did not answer PING");
        }
        LOG.info("Validation Redis ready; {}% of unique hearings will be cached", hitPercent);
    }

    static void verifyReady() {
        if (INSTANCE != null && !"PONG".equals(INSTANCE.connection.sync().ping())) {
            throw new IllegalStateException("Validation Redis stopped answering PING");
        }
    }

    /**
     * Seeds this message's hearing if it falls in the cached share.
     *
     * @param message the message about to be published
     * @return true if the hearing is now in Redis; false if the service will take the query fallback
     */
    static boolean seed(final PublishedMessage message) {
        if (INSTANCE == null) {
            return false;
        }
        return INSTANCE.seedMessage(message);
    }

    static void close() {
        if (INSTANCE != null) {
            LOG.info("Validation Redis seeding complete: cachedPublishes={} fallbackPublishes={}",
                    INSTANCE.seeded.get(), INSTANCE.unseeded.get());
            INSTANCE.connection.close();
            INSTANCE.client.shutdown();
        }
    }

    private boolean seedMessage(final PublishedMessage message) {
        // A duplicate has the same hearingId and therefore keeps the same cache-hit decision.
        if (Math.floorMod(message.hearingId().hashCode(), 100) >= hitPercent) {
            unseeded.incrementAndGet();
            return false;
        }
        final String key = "INT_" + message.hearingId() + '_'
                + message.hearingDay() + "_result_";
        connection.sync().setex(key, ttlSeconds,
                MAPPER.writeValueAsString(HearingPayloadFixture.envelopeFor(message.hearingId())));
        seeded.incrementAndGet();
        return true;
    }

    private static RedisPayloadSeeder create() {
        final String uri = System.getProperty("gatling.redisUri", "");
        if (uri.isBlank()) {
            return null;
        }
        return new RedisPayloadSeeder(uri,
                Integer.parseInt(System.getProperty("gatling.redisHitPercent", "80")),
                Long.parseLong(System.getProperty("gatling.redisTtlSeconds", "21600")));
    }
}
