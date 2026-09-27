package uk.gov.hmcts.cp.yotresultsdistribution.adapter.payload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;
import uk.gov.hmcts.cp.yotresultsdistribution.support.RedisTestSupport;

/**
 * The cache read, against a real server — the live half of K1, K4 and K6.
 *
 * <p>The question worth answering is whether the key this service builds finds the value the
 * producer wrote, and a mocked client answers it by agreeing with whatever the test assumed. So
 * every case here writes under the <strong>literal</strong> key form the producer uses and reads
 * through {@link HearingPayloadCacheKey}, which is the only arrangement in which a disagreement
 * between the two can fail. Both forms are exercised, because the producer publishes both: K6 pins
 * the dated key and K1/K4 the undated legacy twin, and this service tries the dated one first.
 *
 * <p>The unreadable-value case is parity, not tidiness. {@code getResultFromCache} runs its
 * {@code JSON.parse} inside the catch that returns {@code null}, so a value that will not parse is a
 * miss there and a miss here, and the query side gets its turn.
 *
 * <p><strong>A cache that cannot be reached is absorbed here, and only here.</strong> This is the
 * class that knows what a failure of the cache technology looks like, so it is the class that may
 * call one a miss. It is also a deliberate difference from the legacy, which obtains its client
 * outside the catch that absorbs a failed {@code GET}: there a cache it cannot connect to throws out
 * of the activity and the results-query fallback never gets its turn. Anything that is <em>not</em>
 * the cache's own failure is let out, because it is a defect rather than a miss.
 *
 * <p><strong>TLS is asserted by connecting, not by reading a setting back.</strong>
 * {@code LivePayloadConfigTest.transport_security} pins that the URI this service builds verifies
 * certificates (defect fix C15); what it cannot show is that the client acts on it. A client
 * configured for TLS against a server speaking plain Redis must fail to read — and fail as a miss,
 * because a cache that cannot be reached is a cache with nothing in it.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> rows C14,
 *     C15 and C32
 */
@DisplayName("Lettuce hearing payload cache")
class LettuceHearingPayloadCacheIT {

    private static final String PREFIX = "INT_";
    private static final LocalDate HEARING_DAY = LocalDate.of(2021, 3, 3);

    /** Stands in for the defendant detail a corrupt document would have the parser quote back. */
    private static final String DEFENDANT_MARKER = "DEFENDANTMARKERZQX7";

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    /** The claim-check envelope: the hearing, and the instant the results were shared. */
    private static final String PAYLOAD =
            "{\"hearing\":{\"id\":\"%s\"},\"sharedTime\":\"2021-03-03T08:00:00Z\"}";

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> writer;
    private static LettuceHearingPayloadCache cache;

    @BeforeAll
    static void connect() {
        client = RedisClient.create(RedisURI.create(RedisTestSupport.uri()));
        writer = client.connect();
        cache = new LettuceHearingPayloadCache(client, MAPPER);
    }

    @AfterAll
    static void disconnect() {
        writer.close();
        client.shutdown();
    }

    /** Writes under the dated key form the producer publishes, without asking this service for it. */
    private static void writeDated(final UUID hearingId, final String value) {
        writer.sync().set(PREFIX + hearingId + '_' + HEARING_DAY + "_result_", value);
    }

    /** Writes under the undated legacy twin the producer also publishes. */
    private static void writeUndated(final UUID hearingId, final String value) {
        writer.sync().set(PREFIX + hearingId + "_result_", value);
    }

    private static String datedKey(final UUID hearingId) {
        return HearingPayloadCacheKey.cacheKey(PREFIX, hearingId, HEARING_DAY);
    }

    private static String undatedKey(final UUID hearingId) {
        return HearingPayloadCacheKey.cacheKey(PREFIX, hearingId, null);
    }

    @Nested
    @DisplayName("a cached payload")
    class Cached {

        @Test
        @DisplayName("finds a payload written under the dated key form — K6")
        void read_should_find_a_payload_written_under_the_dated_key_form() {
            final UUID hearingId = UUID.randomUUID();
            writeDated(hearingId, PAYLOAD.formatted(hearingId));

            final Optional<JsonNode> read = cache.read(datedKey(hearingId));

            assertThat(read).isPresent();
            assertThat(read.orElseThrow().path("hearing").path("id").asString())
                    .isEqualTo(hearingId.toString());
        }

        @Test
        @DisplayName("finds a payload written under the legacy undated key form — K1 and K4")
        void read_should_find_a_payload_written_under_the_legacy_undated_key_form() {
            final UUID hearingId = UUID.randomUUID();
            writeUndated(hearingId, PAYLOAD.formatted(hearingId));

            assertThat(cache.read(undatedKey(hearingId))).isPresent();
        }

        /**
         * The two forms are two keys, not one key read two ways. A hearing published only under the
         * undated twin is absent from the dated key, which is exactly why the adapter above tries
         * the dated form first and then the other rather than choosing between them.
         */
        @Test
        @DisplayName("does not find an undated payload under the dated key")
        void read_should_not_find_an_undated_payload_under_the_dated_key() {
            final UUID hearingId = UUID.randomUUID();
            writeUndated(hearingId, PAYLOAD.formatted(hearingId));

            assertThat(cache.read(datedKey(hearingId))).isEmpty();
        }

        @Test
        @DisplayName("returns the whole envelope the producer cached")
        void read_should_return_the_whole_envelope_the_producer_cached() {
            final UUID hearingId = UUID.randomUUID();
            writeDated(hearingId, PAYLOAD.formatted(hearingId));

            assertThat(cache.read(datedKey(hearingId))).get().satisfies(node -> {
                assertThat(node.has("hearing")).isTrue();
                assertThat(node.has("sharedTime"))
                        .as("the shared time is the envelope's, and C10 reads it from here")
                        .isTrue();
            });
        }
    }

    @Nested
    @DisplayName("nothing cached")
    class Missing {

        @Test
        @DisplayName("reports nothing when the key is absent")
        void read_should_report_nothing_when_the_key_is_absent() {
            assertThat(cache.read(datedKey(UUID.randomUUID()))).isEmpty();
        }

        @Test
        @DisplayName("reports nothing when the cached value will not parse")
        void read_should_report_nothing_when_the_cached_value_will_not_parse() {
            final UUID hearingId = UUID.randomUUID();
            writeDated(hearingId, "not json at all");

            assertThat(cache.read(datedKey(hearingId))).isEmpty();
        }

        @Test
        @DisplayName("reports nothing when the cached value is empty")
        void read_should_report_nothing_when_the_cached_value_is_empty() {
            final UUID hearingId = UUID.randomUUID();
            writeDated(hearingId, "");

            assertThat(cache.read(datedKey(hearingId))).isEmpty();
        }

        /**
         * A cached {@code null} literal parses perfectly well and carries no payload. Handing it on
         * would put a null node into a transformation that has no way to tell it from a hearing.
         */
        @Test
        @DisplayName("reports nothing when the cached value is the JSON null literal")
        void read_should_report_nothing_when_the_cached_value_is_the_json_null_literal() {
            final UUID hearingId = UUID.randomUUID();
            writeDated(hearingId, "null");

            assertThat(cache.read(datedKey(hearingId))).isEmpty();
        }
    }

    @Nested
    @DisplayName("connection lifecycle")
    class Lifecycle {

        /**
         * A pod runs for weeks and a cache is restarted inside them, so the first read after a
         * connection has gone must open another one rather than fail for ever on a closed handle.
         */
        @Test
        @DisplayName("opens a fresh connection after the previous one was closed")
        void read_should_open_a_fresh_connection_after_the_previous_one_was_closed() {
            final UUID hearingId = UUID.randomUUID();
            writeDated(hearingId, PAYLOAD.formatted(hearingId));
            assertThat(cache.read(datedKey(hearingId))).isPresent();

            cache.close();

            assertThat(cache.read(datedKey(hearingId))).isPresent();
        }

        @Test
        @DisplayName("closing is safe when nothing is open")
        void close_should_be_safe_to_call_when_nothing_is_open() {
            try (LettuceHearingPayloadCache unused =
                         new LettuceHearingPayloadCache(client, MAPPER)) {
                assertThatCode(unused::close).doesNotThrowAnyException();
            }
        }
    }

    @Nested
    @DisplayName("a cache that cannot be reached")
    class Unreachable {

        /** A client whose failure is this service's own, rather than the cache technology's. */
        private static RedisClient brokenClient() {
            final RedisClient broken = mock(RedisClient.class);
            when(broken.connect()).thenThrow(new IllegalStateException("a defect, not a miss"));
            return broken;
        }

        private static RedisClient nowhere() {
            return RedisClient.create(RedisURI.builder()
                    .withHost(InetAddress.getLoopbackAddress().getHostAddress())
                    .withPort(1)
                    .withTimeout(Duration.ofMillis(250))
                    .build());
        }

        /**
         * The legacy obtains its client outside the catch that absorbs a failed {@code GET}, so a
         * connection it cannot make throws out of the activity and the query side is never asked.
         * Here it is a miss, because C32's transient outcome is "the cache <em>and</em> the fallback
         * had nothing" — which presumes the fallback is attempted when the cache is not there.
         */
        @Test
        @DisplayName("reports nothing when the cache cannot be reached")
        void read_should_report_nothing_when_the_cache_cannot_be_reached() {
            final RedisClient nowhere = nowhere();
            try (LettuceHearingPayloadCache unreachable =
                         new LettuceHearingPayloadCache(nowhere, MAPPER)) {
                assertThat(unreachable.read("INT_anything_result_")).isEmpty();
            } finally {
                nowhere.shutdown();
            }
        }

        /**
         * The outage is absorbed, not hidden: there is a line for it, and the line names the failure
         * by type rather than by the client's own words, which carry the address and the credentials
         * the connection was attempted with.
         */
        @Test
        @DisplayName("says a cache is unreachable without quoting the client")
        void read_should_report_an_unreachable_cache_without_quoting_the_client() {
            final RedisClient nowhere = nowhere();
            try (LettuceHearingPayloadCache unreachable =
                         new LettuceHearingPayloadCache(nowhere, MAPPER);
                 CapturedLog log = CapturedLog.capturing(LettuceHearingPayloadCache.class)) {
                unreachable.read("INT_anything_result_");

                assertThat(log.messages()).anyMatch(line -> line.contains("could not answer"));
                assertThat(log.events()).allSatisfy(event ->
                        assertThat(event.getThrowableProxy()).isNull());
            } finally {
                nowhere.shutdown();
            }
        }

        /**
         * Only the cache's own failures are absorbed. A client that fails for any other reason is a
         * defect in this service, and a defect that came back as a miss would be paid for with a
         * query-side round trip and never seen again.
         */
        @Test
        @DisplayName("lets a failure that is not the cache technology's out")
        void read_should_let_a_failure_that_is_not_the_cache_technology_out() {
            try (LettuceHearingPayloadCache defective =
                         new LettuceHearingPayloadCache(brokenClient(), MAPPER)) {
                assertThatThrownBy(() -> defective.read("INT_anything_result_"))
                        .isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Nested
    @DisplayName("transport security — defect fix C15")
    class TransportSecurity {

        /** The same address the suite reads over plain TCP, asked for over TLS instead. */
        private static RedisClient overTls() {
            return RedisClient.create(RedisURI.builder()
                    .withHost(RedisTestSupport.host())
                    .withPort(RedisTestSupport.port())
                    .withSsl(true)
                    .withVerifyPeer(true)
                    .withTimeout(Duration.ofSeconds(2))
                    .build());
        }

        /**
         * The live half of C15. The configuration test proves the URI this service builds asks for
         * TLS with the certificate verified; this proves the client connects with what the URI says
         * rather than quietly ignoring it — a cache reached over TLS cannot read a server speaking
         * plain Redis, and the read that could not be made is a miss like any other cache outage.
         *
         * <p>The container is deliberately plaintext. A container with a self-signed certificate
         * would only prove that a suite can be told to trust one, which is the opposite of the
         * property that matters.
         */
        @Test
        @DisplayName("a cache configured for TLS does not read a plaintext server")
        void read_should_not_reach_a_plaintext_server_when_tls_is_configured() {
            final UUID hearingId = UUID.randomUUID();
            writeDated(hearingId, PAYLOAD.formatted(hearingId));
            final RedisClient overTls = overTls();

            try (LettuceHearingPayloadCache secured =
                         new LettuceHearingPayloadCache(overTls, MAPPER)) {
                assertThat(secured.read(datedKey(hearingId)))
                        .as("the handshake the URI asks for is the handshake the client makes")
                        .isEmpty();
            } finally {
                overTls.shutdown();
            }
        }

        /** The same server, the same key, read without TLS: the value is there to be found. */
        @Test
        @DisplayName("and the same key over plain TCP finds the payload")
        void read_should_find_the_same_payload_without_tls() {
            final UUID hearingId = UUID.randomUUID();
            writeDated(hearingId, PAYLOAD.formatted(hearingId));

            assertThat(cache.read(datedKey(hearingId))).isPresent();
        }
    }

    @Nested
    @DisplayName("a corrupt cached value")
    class Corrupt {

        /**
         * A parser quotes the token it choked on. In a truncated hearing document that token is a
         * child's name, an address or a URN, so the line reports the failure's type and nothing the
         * producer wrote — the rule {@code DistributionCommandParser} already follows for a message
         * body.
         */
        @Test
        @DisplayName("writes out nothing the corrupt value contained")
        void read_should_not_write_out_anything_the_corrupt_value_contained() {
            final UUID hearingId = UUID.randomUUID();
            writeDated(hearingId, "{\"hearing\":{\"defendant\": " + DEFENDANT_MARKER);

            try (CapturedLog log = CapturedLog.capturing(LettuceHearingPayloadCache.class)) {
                assertThat(cache.read(datedKey(hearingId))).isEmpty();

                assertThat(log.renderings())
                        .as("the parser's words quote the payload it failed on")
                        .noneMatch(line -> line.contains(DEFENDANT_MARKER));
                assertThat(log.messages()).anyMatch(line -> line.contains("could not be parsed"));
            }
        }
    }
}
