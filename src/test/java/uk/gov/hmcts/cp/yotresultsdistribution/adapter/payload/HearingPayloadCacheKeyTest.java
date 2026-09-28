package uk.gov.hmcts.cp.yotresultsdistribution.adapter.payload;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.yotresultsdistribution.adapter.payload.HearingPayloadCacheKey.cacheKey;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The cache key is somebody else's decision, so it is pinned character by character.
 *
 * <p>The producer writes the key and this service reads it. A key that is nearly right reads
 * nothing, and reading nothing is indistinguishable from a hearing that was never cached — which
 * would send every request to the query API and look, from here, like a cache that is simply cold.
 * That is exactly the sort of failure a running system does not report, so it is asserted against
 * the literal form {@code getCacheKey} builds
 * ({@code $DF/HearingResultedCacheQuery/index.js:83-88}) rather than against a second copy of the
 * same string concatenation.
 *
 * <p>The hearing identifier is the one from the legacy fixture
 * ({@code $DF/testing/hearing.1828f356-….test.json}) and the hearing day is K6's
 * ({@code index.js:189-218}, {@code hearingDate: '2021-03-03'}), so the two literals below are the
 * keys that suite reads.
 */
@DisplayName("Hearing payload cache key")
class HearingPayloadCacheKeyTest {

    private static final String PREFIX = "INT_";
    private static final UUID HEARING_ID = UUID.fromString("1828f356-f746-4f2d-932b-79ef2df95c80");
    private static final LocalDate HEARING_DAY = LocalDate.of(2021, 3, 3);

    @Nested
    @DisplayName("with a hearing day — the form K6 reads")
    class Dated {

        @Test
        @DisplayName("is prefix, hearing, day, then the literal suffix")
        void cacheKey_with_a_hearing_day_should_be_prefix_hearing_day_result() {
            assertThat(cacheKey(PREFIX, HEARING_ID, HEARING_DAY))
                    .isEqualTo("INT_1828f356-f746-4f2d-932b-79ef2df95c80_2021-03-03_result_");
        }

        @Test
        @DisplayName("renders the hearing day as an ISO date, zero-padded")
        void cacheKey_should_render_the_hearing_day_as_an_iso_date() {
            assertThat(cacheKey(PREFIX, HEARING_ID, LocalDate.of(2026, 1, 5)))
                    .contains("_2026-01-05_")
                    .doesNotContain("2026-1-5");
        }

        /**
         * The producer writes the identifier as the canonical lower-case UUID, and Redis keys are
         * bytes: a key differing only in case is a different key and reads nothing.
         */
        @Test
        @DisplayName("renders the hearing id in canonical lower case")
        void cacheKey_should_render_the_hearing_id_in_canonical_lower_case() {
            final UUID upperCased = UUID.fromString("1828F356-F746-4F2D-932B-79EF2DF95C80");

            assertThat(cacheKey(PREFIX, upperCased, HEARING_DAY))
                    .isEqualTo(cacheKey(PREFIX, HEARING_ID, HEARING_DAY));
        }
    }

    @Nested
    @DisplayName("without a hearing day — the legacy twin K1 and K4 read")
    class Undated {

        @Test
        @DisplayName("is prefix, hearing, then the literal suffix")
        void cacheKey_without_a_hearing_day_should_be_the_legacy_prefix_hearing_result_form() {
            assertThat(cacheKey(PREFIX, HEARING_ID, null))
                    .isEqualTo("INT_1828f356-f746-4f2d-932b-79ef2df95c80_result_");
        }

        @Test
        @DisplayName("leaves no separator standing where the day would have been")
        void cacheKey_without_a_hearing_day_should_not_leave_a_separator_where_the_day_was() {
            assertThat(cacheKey(PREFIX, HEARING_ID, null)).doesNotContain("__");
        }
    }

    @Nested
    @DisplayName("prefix")
    class Prefix {

        /**
         * The prefix is configuration, not a constant baked in here. Only {@code INT_} is in scope
         * for this flow — the legacy's {@code EXT_} and {@code SJP_} endpoints belong to flows this
         * service does not carry ({@code index.js:63-76}) — so the assertion uses a prefix that is
         * nobody's, to prove the value is passed through rather than to suggest another flow is
         * supported.
         */
        @Test
        @DisplayName("is used verbatim, whatever it is configured to be")
        void cacheKey_should_use_the_supplied_prefix_verbatim() {
            assertThat(cacheKey("ZZZ_", HEARING_ID, HEARING_DAY)).startsWith("ZZZ_");
        }
    }
}
