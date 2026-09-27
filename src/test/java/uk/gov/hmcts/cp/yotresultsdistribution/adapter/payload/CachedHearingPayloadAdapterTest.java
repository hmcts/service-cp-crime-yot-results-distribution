package uk.gov.hmcts.cp.yotresultsdistribution.adapter.payload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.lettuce.core.RedisConnectionException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.PayloadUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;

/**
 * The order the two sources are consulted in, and what happens when neither answers.
 *
 * <p>The order is the legacy's and this port keeps it: cache first, query side second
 * ({@code $DF/HearingResultedCacheQuery/index.js:166-187}). So is the rule that a cache which
 * cannot be read is not a request failure — there the {@code GET} sits inside a catch that returns
 * {@code null} and the caller carries straight on to the query API ({@code index.js:154-163}).
 *
 * <p>What this file changes on purpose is the end of the chain, and it is <strong>defect fix
 * C32</strong>. The legacy returns {@code null} for a hearing neither source could supply, the
 * orchestrator's {@code if (hearingResultedObj)} guard skips every remaining step, and the run
 * reports success having produced nothing — a register lost with no row, no log and no alert. Here
 * the request fails transiently, so a redelivery gets another attempt and an exhausted one is
 * dead-lettered where somebody can see it.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> row C32
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Cached hearing payload adapter")
class CachedHearingPayloadAdapterTest {

    private static final String PREFIX = "INT_";
    private static final UUID HEARING_ID = UUID.fromString("1828f356-f746-4f2d-932b-79ef2df95c80");
    private static final LocalDate HEARING_DAY = LocalDate.of(2020, 6, 1);
    private static final String DATED_KEY =
            "INT_1828f356-f746-4f2d-932b-79ef2df95c80_2020-06-01_result_";
    private static final String LEGACY_KEY =
            "INT_1828f356-f746-4f2d-932b-79ef2df95c80_result_";

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    @Mock
    private HearingPayloadCache cache;

    @Mock
    private HearingPayloadQuery query;

    private CachedHearingPayloadAdapter adapter;

    private static DistributionCommand command() {
        return new DistributionCommand(
                "RESULTS",
                UUID.fromString("9f1b8e2a-5c34-4a7d-9b1e-2f6a0d3c5e71"),
                HEARING_ID,
                HEARING_DAY,
                Instant.parse("2020-06-01T10:00:00Z"),
                "Hearing_Resulted",
                Optional.of(UUID.fromString("6e2f0a1c-9d4b-4f38-8a52-1c7b3e5d9f04")));
    }

    /**
     * A payload envelope shaped like the claim check, carrying nothing that resembles a hearing:
     * every defendant on a court register is a youth, and a fixture that looked like one would
     * invite a reader to think this suite depended on hearing content. It does not — it is about
     * which source the tree came from.
     */
    private static JsonNode payload(final String note) {
        return MAPPER.readTree("{\"note\":\"" + note + "\",\"hearing\":{},"
                + "\"sharedTime\":\"2020-06-01T10:00:00Z\"}");
    }

    @BeforeEach
    void setUp() {
        adapter = new CachedHearingPayloadAdapter(cache, query, PREFIX);
    }

    @Nested
    @DisplayName("cache first — K4's twin")
    class CacheFirst {

        @Test
        @DisplayName("answers with what the dated key holds")
        void fetch_should_return_the_payload_the_dated_key_holds() {
            final JsonNode cached = payload("from the cache");
            when(cache.read(DATED_KEY)).thenReturn(Optional.of(cached));

            assertThat(adapter.fetch(command())).isSameAs(cached);
        }

        /**
         * K4's missing half. The Jest case returns {@code {data:null}} from axios and asserts the
         * hearing came from the cache, which is true whether or not the query API was called; that
         * it is not called is the whole reason the cache exists, so it is asserted rather than
         * implied.
         */
        @Test
        @DisplayName("does not ask the query side at all on a cache hit")
        void fetch_should_not_touch_the_query_side_when_the_cache_answered() {
            when(cache.read(DATED_KEY)).thenReturn(Optional.of(payload("from the cache")));

            adapter.fetch(command());

            verifyNoInteractions(query);
        }

        @Test
        @DisplayName("does not read the legacy key once the dated key has answered")
        void fetch_should_not_read_the_legacy_key_when_the_dated_key_answered() {
            when(cache.read(DATED_KEY)).thenReturn(Optional.of(payload("from the cache")));

            adapter.fetch(command());

            verify(cache, never()).read(LEGACY_KEY);
        }
    }

    @Nested
    @DisplayName("the dated key, then its undated twin")
    class BothKeyForms {

        /**
         * The legacy builds one key from the hearing date it was handed and reads it once; both
         * forms are live, because its own suite reads the dated one (K6) and the undated one (K1,
         * K4) against the same producer. Reading only the dated form would send every
         * legacy-cached hearing to the query API — which answers — so the miss would cost a round
         * trip and show up nowhere.
         */
        @Test
        @DisplayName("falls back to the undated twin when the dated key is absent")
        void fetch_should_read_the_legacy_key_when_the_dated_key_is_absent() {
            final JsonNode cached = payload("under the legacy key");
            when(cache.read(DATED_KEY)).thenReturn(Optional.empty());
            when(cache.read(LEGACY_KEY)).thenReturn(Optional.of(cached));

            assertThat(adapter.fetch(command())).isSameAs(cached);
        }

        @Test
        @DisplayName("does not ask the query side when the undated twin answered")
        void fetch_should_not_touch_the_query_side_when_the_legacy_key_answered() {
            when(cache.read(DATED_KEY)).thenReturn(Optional.empty());
            when(cache.read(LEGACY_KEY)).thenReturn(Optional.of(payload("legacy")));

            adapter.fetch(command());

            verifyNoInteractions(query);
        }

        /**
         * The count, pinned. Two lookups precede the query side and both are budgeted against the
         * run's processing deadline, so removing one — or adding a third — is a decision somebody
         * takes against this assertion rather than a quiet edit.
         */
        @Test
        @DisplayName("reads exactly the two key forms and no others")
        void fetch_should_read_exactly_the_two_key_forms_and_no_others() {
            when(cache.read(any())).thenReturn(Optional.empty());
            when(query.fetch(any())).thenReturn(Optional.of(payload("from the query api")));

            adapter.fetch(command());

            verify(cache).read(DATED_KEY);
            verify(cache).read(LEGACY_KEY);
            verifyNoMoreInteractions(cache);
        }

        @Test
        @DisplayName("reads the dated key before the undated one")
        void fetch_should_read_the_dated_key_before_the_undated_one() {
            when(cache.read(any())).thenReturn(Optional.empty());
            when(query.fetch(any())).thenReturn(Optional.of(payload("from the query api")));

            adapter.fetch(command());

            final InOrder order = inOrder(cache);
            order.verify(cache).read(DATED_KEY);
            order.verify(cache).read(LEGACY_KEY);
        }
    }

    @Nested
    @DisplayName("query-side fallback — K1's twin")
    class Fallback {

        @Test
        @DisplayName("asks the query side when neither key answered")
        void fetch_should_ask_the_query_side_when_neither_key_answered() {
            final JsonNode queried = payload("from the query api");
            when(cache.read(any())).thenReturn(Optional.empty());
            when(query.fetch(any())).thenReturn(Optional.of(queried));

            assertThat(adapter.fetch(command())).isSameAs(queried);
        }

        @Test
        @DisplayName("hands the query side the command it was given")
        void fetch_should_pass_the_command_through_to_the_query_side() {
            final DistributionCommand command = command();
            when(cache.read(any())).thenReturn(Optional.empty());
            when(query.fetch(command)).thenReturn(Optional.of(payload("from the query api")));

            assertThat(adapter.fetch(command)).isNotNull();
        }

        /**
         * The scope repair. The legacy awaits {@code getRedisClient} at {@code index.js:150} —
         * <em>outside</em> the catch at {@code :153-163} — so a refused connection, an expired
         * certificate or a wrong password throws out of the activity and the query fallback never
         * gets its turn. Here the absorb is the cache adapter's, scoped to
         * {@link io.lettuce.core.RedisException} so that it covers the connection as well as the
         * command; from this frame that arrives as an ordinary empty read, and the query side is
         * asked exactly as it is for an absent key.
         */
        @Test
        @DisplayName("still asks the query side when the cache is down, not just when it is cold")
        void fetch_should_ask_the_query_side_when_the_cache_is_down_rather_than_cold() {
            final JsonNode queried = payload("from the query api");
            when(cache.read(any())).thenReturn(Optional.empty());
            when(query.fetch(any())).thenReturn(Optional.of(queried));

            assertThat(adapter.fetch(command())).isSameAs(queried);
        }
    }

    @Nested
    @DisplayName("a failure that is not the cache's own")
    class Unexpected {

        /**
         * Nothing is caught here. The cache adapter absorbs the failures of its own technology and
         * reports them as a miss; anything else reaching this frame is a defect in this service,
         * and turning it into a fallback would spend a query-side round trip hiding it. It escapes
         * to the pipeline, which records it and releases the claim.
         */
        @Test
        @DisplayName("is not absorbed into a fallback")
        void fetch_should_let_an_unexpected_failure_out_rather_than_absorb_it() {
            when(cache.read(DATED_KEY)).thenThrow(new IllegalStateException("a defect, not a miss"));

            assertThatThrownBy(() -> adapter.fetch(command()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("does not reach the query side")
        void fetch_should_not_reach_the_query_side_after_an_unexpected_failure() {
            when(cache.read(DATED_KEY)).thenThrow(new IllegalStateException("a defect, not a miss"));

            assertThatThrownBy(() -> adapter.fetch(command()))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(query);
        }

        /**
         * The other side of the scope rule, stated where a reader will look for it: a Redis failure
         * is the cache adapter's to absorb, and one that reaches this frame has escaped its own
         * handler. It is not re-absorbed here either — two places deciding what a cache outage
         * means is how one of them stops being read.
         */
        @Test
        @DisplayName("is still not absorbed when it happens to be the cache's own exception")
        void fetch_should_not_absorb_a_redis_failure_that_escaped_the_cache_adapter() {
            when(cache.read(DATED_KEY))
                    .thenThrow(new RedisConnectionException("escaped its own handler"));

            assertThatThrownBy(() -> adapter.fetch(command()))
                    .isInstanceOf(RedisConnectionException.class);

            verifyNoInteractions(query);
        }
    }

    @Nested
    @DisplayName("neither source answered — defect fix C32")
    class NeitherAnswered {

        /**
         * The C32 pin. Cache miss <em>and</em> fallback miss is a transient failure with a bounded
         * reason, never the silent stop the legacy reports as success.
         */
        @Test
        @DisplayName("a double miss is transient, never silent")
        void a_double_miss_is_transient_never_silent() {
            when(cache.read(any())).thenReturn(Optional.empty());
            when(query.fetch(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> adapter.fetch(command()))
                    .asInstanceOf(InstanceOfAssertFactories.type(PayloadUnavailableException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.TRANSIENT);
                        assertThat(failure.reason()).isEqualTo(ReasonCode.PAYLOAD_UNAVAILABLE);
                    });
        }

        @Test
        @DisplayName("never answers with a null tree the pipeline would have to guard")
        void fetch_should_never_answer_with_nothing() {
            when(cache.read(any())).thenReturn(Optional.empty());
            when(query.fetch(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> adapter.fetch(command()))
                    .isInstanceOf(PayloadUnavailableException.class);
        }

        /**
         * The reason travels into {@code processed_request.failure_reason}, a dead-letter
         * description and the log index, so the message must be the bounded code and nothing else —
         * no key, no hearing identifier, no text from the layer beneath.
         */
        @Test
        @DisplayName("carries only the bounded reason code in its message")
        void fetch_should_carry_only_the_bounded_reason_code_in_its_message() {
            when(cache.read(any())).thenReturn(Optional.empty());
            when(query.fetch(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> adapter.fetch(command()))
                    .hasMessage(ReasonCode.PAYLOAD_UNAVAILABLE.code());
        }
    }
}
