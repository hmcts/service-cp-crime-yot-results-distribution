package uk.gov.hmcts.cp.yotresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisURI;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * What the cache connection is actually made with.
 *
 * <p>Two claims, and both are fix-register rows the Redis suite cannot make. A container with a
 * self-signed certificate would only show that a test can be told to trust one, so C15 is proven
 * here, on the settings the client is built from; and C14 is a claim about settings that no longer
 * exist, which is a claim about the shape of the properties record rather than about a connection.
 *
 * <p><strong>C15 — certificates are verified.</strong>
 * {@code HearingResultedCacheQuery/index.js:107} connects with {@code rejectUnauthorized: false}.
 * The payload behind that connection is a whole hearing about named children, and a client that
 * accepts any certificate accepts one somebody else presents.
 *
 * <p><strong>C14 — the dead retry knobs are gone.</strong> The function app reads
 * {@code REDIS_MAX_RETRIES} and never uses it, passes a node-redis <em>v3</em> {@code retry_strategy}
 * to a <em>v4</em> client that ignores it, references an out-of-scope {@code hearingId} inside that
 * block ({@code :114}, a {@code ReferenceError} if it ever ran), and compares
 * {@code REDIS_TOTAL_RETRY_TIME_IN_MS} and {@code REDIS_NUMBER_OF_ATTEMPTS} string-to-number. Four
 * settings, none of which does anything, all of which read as though they did. They are not
 * transcribed, and the way to keep them from creeping back is to assert that the settings record has
 * no place to put them.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> rows C14
 *     and C15
 */
@DisplayName("Live payload configuration")
class LivePayloadConfigTest {

    /** The prefix the cache settings bind under. */
    private static final String PREFIX = "yotresultsdistribution.payload.redis";

    private static YotResultsDistributionProperties.Redis redis(final boolean ssl) {
        return new YotResultsDistributionProperties.Redis(
                "cache.internal", 6380, "a-key", ssl, "INT_",
                Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    @Nested
    @DisplayName("transport security — defect fix C15")
    class TransportSecurity {

        @Test
        @DisplayName("verifies the certificate wherever TLS is used")
        void the_cache_connection_should_verify_the_certificate_when_tls_is_used() {
            final RedisURI uri = LivePayloadConfig.cacheUri(redis(true));

            assertThat(uri.isSsl()).isTrue();
            assertThat(uri.isVerifyPeer())
                    .as("C15: the legacy connects with rejectUnauthorized:false; this does not")
                    .isTrue();
        }

        /**
         * A developer's local server speaks plain TCP, so peer verification has nothing to verify.
         * The setting follows TLS rather than standing on its own, which is what keeps every
         * deployed environment — all of which use TLS — verified without a second switch that could
         * be turned off on its own.
         */
        @Test
        @DisplayName("does not ask for verification it cannot perform")
        void the_cache_connection_should_not_ask_for_verification_it_cannot_perform() {
            final RedisURI uri = LivePayloadConfig.cacheUri(redis(false));

            assertThat(uri.isSsl()).isFalse();
            assertThat(uri.isVerifyPeer()).isFalse();
        }
    }

    @Nested
    @DisplayName("the address")
    class Address {

        @Test
        @DisplayName("carries the configured address and command timeout")
        void the_cache_connection_should_carry_the_configured_address_and_timeout() {
            final RedisURI uri = LivePayloadConfig.cacheUri(redis(true));

            assertThat(uri.getHost()).isEqualTo("cache.internal");
            assertThat(uri.getPort()).isEqualTo(6380);
            assertThat(uri.getTimeout()).isEqualTo(Duration.ofSeconds(5));
        }

        /**
         * A local server has no password, and an empty one is not a credential — sending it would
         * fail the handshake against a server that expects none.
         */
        @Test
        @DisplayName("carries no credential when none is configured")
        void the_cache_connection_should_carry_no_credential_when_none_is_configured() {
            final RedisURI uri = LivePayloadConfig.cacheUri(new YotResultsDistributionProperties.Redis(
                    "localhost", 6379, "  ", false, "INT_",
                    Duration.ofSeconds(5), Duration.ofSeconds(5)));

            assertThat(uri.getCredentialsProvider().resolveCredentials().block().hasPassword())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("the retired retry settings — defect fix C14")
    class RetiredSettings {

        /**
         * The whole of the cache's configuration surface, named. Lettuce's own reconnection replaces
         * the block that never worked, and what remains is an address, a credential, a transport
         * decision, the key prefix and two timeouts — every one of which does something.
         */
        @Test
        @DisplayName("the cache settings are exactly the seven that do something")
        void the_cache_settings_are_exactly_the_ones_that_do_something() {
            assertThat(componentNames())
                    .containsExactly("host", "port", "password", "ssl", "keyPrefix",
                            "connectTimeout", "commandTimeout");
        }

        /**
         * The four the function app carries, none of which reaches the client. Named individually so
         * that a reader meeting one of them in an old Helm values file can see it was retired on
         * purpose rather than overlooked.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "maxRetries", "retryStrategy", "totalRetryTimeInMs", "numberOfAttempts",
            "rejectUnauthorized",
        })
        @DisplayName("no legacy retry knob has anywhere to bind")
        void no_legacy_retry_knob_has_anywhere_to_bind(final String retired) {
            assertThat(componentNames()).doesNotContain(retired);
        }

        /**
         * And the same claim where it actually bites: an environment still exporting the legacy
         * names binds to a settings record indistinguishable from one that never saw them. A
         * component quietly added for any of them would make these two differ, which is the point.
         */
        @Test
        @DisplayName("an environment still exporting them binds exactly as one that does not")
        void a_legacy_environment_binds_to_the_same_settings() {
            final Map<String, String> live = new HashMap<>(Map.of(
                    PREFIX + ".host", "cache.internal",
                    PREFIX + ".port", "6380",
                    PREFIX + ".ssl", "true"));
            final Map<String, String> withRetired = new HashMap<>(live);
            withRetired.putAll(Map.of(
                    PREFIX + ".max-retries", "9",
                    PREFIX + ".total-retry-time-in-ms", "30000",
                    PREFIX + ".number-of-attempts", "7",
                    PREFIX + ".reject-unauthorized", "false"));

            assertThat(bind(withRetired))
                    .as("C14: the retired names are not settings, so they change nothing")
                    .isEqualTo(bind(live));
        }

        private static YotResultsDistributionProperties.Redis bind(final Map<String, String> properties) {
            return new Binder(new MapConfigurationPropertySource(properties))
                    .bind(PREFIX, YotResultsDistributionProperties.Redis.class)
                    .orElseThrow(() -> new IllegalStateException("the cache settings did not bind"));
        }

        private static List<String> componentNames() {
            return Arrays.stream(YotResultsDistributionProperties.Redis.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
        }
    }
}
