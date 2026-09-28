package uk.gov.hmcts.cp.yotresultsdistribution.adapter.payload;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The hearing payload cache over Lettuce.
 *
 * <p>One string {@code GET} and a parse. The function app's client settings that carried real
 * meaning — TLS, a connect timeout — belong to the {@link RedisClient} handed in here; the ones that
 * never worked are not ported. {@code HearingResultedCacheQuery/index.js} reads
 * {@code REDIS_MAX_RETRIES} and never uses it, and configures a node-redis v3 {@code retry_strategy}
 * on a v4 client that does not honour it (defect C14), so Lettuce's own reconnection replaces a
 * transcription of dead code.
 *
 * <p><strong>Certificates are verified — defect fix C15.</strong> {@code index.js:107} connects with
 * {@code rejectUnauthorized: false}. What this cache holds is a whole hearing about named children,
 * and a connection that will accept any certificate will accept one somebody else presents. The
 * decision itself is made where the URI is built ({@code config/LivePayloadConfig}); what this class
 * owes the fix is that it connects with the client it was given and quietly builds nothing of its
 * own.
 *
 * <p>A cache that cannot answer is reported as a cache with nothing in it, and this is the one class
 * that may decide that. It catches {@link RedisException} — the cache technology's own hierarchy,
 * connection failures included — and nothing wider: a fault that is not the cache's is a fault
 * somebody has to see, and absorbing it here would hide a bug in this service behind a fallback. The
 * composite adapter above therefore catches nothing at all.
 *
 * <p><strong>The scope of that absorb is a deliberate difference from the legacy.</strong>
 * {@code index.js:100-121} obtains its client <em>outside</em> the catch that absorbs a failed
 * {@code GET}, so a cache it cannot connect to throws out of the activity and the results-query
 * fallback never gets its turn — the run stops with the register unbuilt. Here a connect failure is
 * a miss, so the fallback is asked, and only a miss on both is the transient failure C32 makes of
 * it.
 *
 * <p>A value that will not parse is a miss for the same reason, and that half is straight parity:
 * {@code getResultFromCache} runs its {@code JSON.parse} inside the catch that returns {@code null}.
 *
 * <p>Neither failure is logged with the words the library used. A parser quotes the token it choked
 * on, and that token is a fragment of a hearing — a child's name, an address, a URN — so the line
 * carries the failure's type and never its message (constitution Principle VII).
 */
public class LettuceHearingPayloadCache implements HearingPayloadCache, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LettuceHearingPayloadCache.class);

    private final RedisClient client;
    private final ObjectMapper objectMapper;

    /**
     * The connection, opened on the first read and reopened when it has gone.
     *
     * <p>Volatile because a pod reads the payload on every delivery and the processor runs those
     * concurrently; the reopen itself is synchronised, so two deliveries meeting a closed connection
     * open one between them rather than one each.
     */
    private volatile StatefulRedisConnection<String, String> connection;

    /**
     * Wraps an already-configured client.
     *
     * @param client       the Redis client, carrying the address, credentials, TLS and timeouts
     * @param objectMapper the shared mapper, so a cached payload is read exactly as any other JSON
     */
    public LettuceHearingPayloadCache(final RedisClient client, final ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<JsonNode> read(final String key) {
        return cached(key).flatMap(this::parsed);
    }

    /**
     * The stored string, or nothing when the key is absent or the cache could not answer.
     *
     * <p>{@link RedisException} covers the connection as well as the command, which is the whole of
     * the scope repair: the legacy obtains its client outside the catch, so a cache it cannot reach
     * ends the run before the query side is asked.
     */
    private Optional<String> cached(final String key) {
        Optional<String> value;
        try {
            value = Optional.ofNullable(openConnection().sync().get(key));
        } catch (RedisException unreadable) {
            // Logged, because a cache outage should be read from a log rather than inferred from a
            // rise in query-side traffic — and logged by type, because the client's own message may
            // name the address and the credentials the connection was attempted with.
            LOG.warn("The hearing payload cache could not answer; treating the key as absent. "
                    + "type={}", unreadable.getClass().getName());
            value = Optional.empty();
        }
        return value;
    }

    /**
     * The parsed payload, or nothing when the stored value is not one.
     *
     * <p>Parity with {@code getResultFromCache}, whose {@code JSON.parse} runs inside the catch that
     * returns {@code null}: a value that will not parse is a miss, and the query side gets its turn.
     * A cached {@code null} literal parses perfectly well and carries no hearing, so it is a miss
     * too — handing it on would put a null node into a transformation with no way to tell it from a
     * payload.
     */
    private Optional<JsonNode> parsed(final String cached) {
        Optional<JsonNode> payload = Optional.empty();
        if (!cached.isBlank()) {
            try {
                final JsonNode tree = objectMapper.readTree(cached);
                if (tree != null && !tree.isNull() && !tree.isMissingNode()) {
                    payload = Optional.of(tree);
                }
            } catch (JacksonException unparseable) {
                // By type, never by message: a parser quotes the token it choked on, and in a
                // truncated hearing that token is a child's name, an address or a URN.
                LOG.warn("A cached hearing payload could not be parsed; treating it as absent. "
                        + "type={}", unparseable.getClass().getName());
            }
        }
        return payload;
    }

    /** Opens a connection, or reuses the one already open. */
    private synchronized StatefulRedisConnection<String, String> openConnection() {
        if (connection == null || !connection.isOpen()) {
            connection = client.connect();
        }
        return connection;
    }

    /**
     * Closes the connection if one is open.
     *
     * <p>Safe to call when nothing is open, and safe to call twice: a pod is shut down once but a
     * suite closes what it built whether or not it ever read anything.
     */
    @Override
    public synchronized void close() {
        if (connection != null) {
            connection.close();
        }
    }
}
