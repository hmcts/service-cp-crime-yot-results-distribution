package uk.gov.hmcts.cp.yotresultsdistribution.adapter.payload;

import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * The hearing payload cache, as the composite adapter needs to see it.
 *
 * <p>Internal to {@code adapter/payload}: the application core knows only
 * {@link uk.gov.hmcts.cp.yotresultsdistribution.application.HearingPayloadSource}, and this seam exists so
 * the composite adapter's ordering and failure rules can be tested without a broker, a cache or a
 * network.
 *
 * <p><strong>An unreachable cache answers empty rather than failing</strong>, so a cache that is
 * down is not a reason to abandon a request the query side can still answer. The legacy absorbs a
 * failed {@code GET} the same way ({@code HearingResultedCacheQuery/index.js:154-163}); a failed
 * <em>connection</em> it does not — {@code getRedisClient} is awaited at {@code :150}, outside that
 * catch, so a TLS or auth failure throws out of the activity and the query fallback never gets its
 * turn. Here the absorb is scoped to the cache technology's own exception hierarchy
 * ({@code RedisException}), which covers the connection as well as the command, and to nothing
 * wider: a fault that is not the cache's own is a defect in this service, and absorbing it would
 * hide it behind a fallback round trip.
 */
public interface HearingPayloadCache {

    /**
     * Reads the payload stored under a key.
     *
     * @param key the key to read
     * @return the payload, or empty when the key is absent, unreadable or unparseable — or when the
     *     cache itself could not answer
     */
    Optional<JsonNode> read(String key);
}
