package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The two invariants the output claim carries, enforced where a record can enforce them.
 *
 * <p>Both are about what reaches {@code processed_output}, and both are the sort of rule that a
 * comment cannot hold. The digest is the reconciliation evidence for a POST that may have happened:
 * a row carrying something that is not a SHA-256 is a row a replay diff silently mis-answers, and by
 * the time anybody notices, the bytes it was supposed to describe are gone.
 *
 * <p>The anomaly counts are a privacy boundary. Every defendant on this register is a child, and the
 * summary is written by the transformation, read by support and shipped to a log index. Held as a
 * string it is one careless concatenation away from carrying a name; held as a map keyed by a
 * bounded enumeration, free text is not a value the type admits — which is a stronger guarantee than
 * any validation of a string could be, because there is nothing left to validate.
 */
@DisplayName("ProcessedOutputClaim")
class ProcessedOutputClaimTest {

    private static final UUID OUTPUT_ID = UUID.fromString("7c1f0a2b-3d4e-4f50-8617-2839a4b5c6d7");
    private static final UUID COURT_CENTRE = UUID.fromString("2f4a1c66-9d1e-4d3b-9a55-7c1a0f6b8e21");
    private static final LocalDate REGISTER_DATE = LocalDate.of(2026, 8, 20);
    private static final String FILE_NAME = "court-register-B01LY-20260820.pdf";

    private static final String DIGEST =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    private static ProcessedOutputClaim claimWith(final String digest) {
        return claimWith(digest, Map.of());
    }

    private static ProcessedOutputClaim claimWith(
            final String digest, final Map<TransformationAnomaly, Integer> anomalies) {
        return new ProcessedOutputClaim(
                OUTPUT_ID, COURT_CENTRE, "B01LY", REGISTER_DATE, FILE_NAME, digest, anomalies);
    }

    @Nested
    @DisplayName("the request digest")
    class Digest {

        @Test
        void a_sha_256_in_lower_case_hex_should_be_accepted() {
            assertThatCode(() -> claimWith(DIGEST)).doesNotThrowAnyException();
        }

        /**
         * There is no "no digest" case. The claim is written in the breath before the POST, by which
         * point the bytes exist and have been hashed; a null here is a caller that lost them, and
         * recording an attempt whose content nobody can identify is worse than not recording one.
         */
        @Test
        void a_missing_digest_should_be_refused() {
            assertThatThrownBy(() -> claimWith(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requestDigest");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            // Sixty-three characters: one short, which is what a truncating format produces.
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a0",
            // Sixty-five: one long.
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08a",
            // Upper-case hex. Half the estate's hex formatters produce it, and a column holding both
            // cases cannot be compared for equality, which is the only thing the column is for.
            "9F86D081884C7D659A2FEAA0C55AD015A3BF4F1B2B0B822CD15D6C15B0F00A08",
            // Not hex at all: the same length, and a diff against it answers a question nobody asked.
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00zzz",
            // Base64 of the same digest — a plausible mistake, and one no length check would catch.
            "n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg=",
            // The whitespace forms a trimmed-elsewhere value arrives as.
            " 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            "",
            "   "})
        @DisplayName("anything that is not sixty-four lower-case hex characters is refused")
        void a_malformed_digest_should_be_refused(final String malformed) {
            assertThatThrownBy(() -> claimWith(malformed))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requestDigest");
        }

        /**
         * The rejection says which component was wrong and nothing else. It travels into a log line
         * and a dead-letter description like every other bounded reason in this service, and the
         * value it refused is the fingerprint of a document about a named child.
         */
        @Test
        void a_rejection_should_not_quote_the_value_it_refused() {
            assertThatThrownBy(() -> claimWith("not-a-digest"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("not-a-digest");
        }
    }

    @Nested
    @DisplayName("the anomaly counts")
    class Anomalies {

        /**
         * The boundedness, asserted at the one place it is actually established: the component's
         * type. A test that fed the record a string and watched it be rejected would be testing a
         * validator; there is no validator, because the type admits no string to reject.
         */
        @Test
        @DisplayName("free text cannot be counted, because the key is the bounded enumeration")
        void the_counts_should_be_keyed_by_the_bounded_anomaly_enumeration() {
            final RecordComponent counts = componentNamed("anomalies");

            assertThat(counts.getType()).isEqualTo(Map.class);
            final ParameterizedType generic = (ParameterizedType) counts.getGenericType();
            assertThat(generic.getActualTypeArguments())
                    .as("a Map<String, ?> would put free text — and therefore a name — one "
                            + "concatenation away from a support query")
                    .containsExactly(TransformationAnomaly.class, Integer.class);
        }

        private RecordComponent componentNamed(final String name) {
            for (final RecordComponent component : ProcessedOutputClaim.class.getRecordComponents()) {
                if (name.equals(component.getName())) {
                    return component;
                }
            }
            throw new IllegalStateException("no record component named " + name);
        }

        @Test
        void a_register_with_nothing_skipped_should_carry_no_counts() {
            assertThat(claimWith(DIGEST, Map.of()).anomalies()).isEmpty();
        }

        /**
         * Absent and empty are the same statement — "nothing was skipped" — so the record settles it
         * once rather than leaving every reader to ask which it has.
         */
        @ParameterizedTest
        @NullSource
        void an_absent_count_map_should_read_as_no_counts(
                final Map<TransformationAnomaly, Integer> absent) {
            assertThat(claimWith(DIGEST, absent).anomalies()).isEmpty();
        }

        @Test
        void counted_anomalies_should_be_carried_verbatim() {
            final Map<TransformationAnomaly, Integer> counted = Map.of(
                    TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT, 1,
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED, 2);

            assertThat(claimWith(DIGEST, counted).anomalies()).isEqualTo(counted);
        }

        /**
         * A count of zero is a claim that an anomaly happened no times, which is what an absent key
         * already says. Written to the column it reads as an incident that did not occur, and the
         * metric it is supposed to agree with would never have been incremented.
         */
        @Test
        void a_count_of_zero_should_be_refused_rather_than_written_as_an_anomaly() {
            final Map<TransformationAnomaly, Integer> counted =
                    Map.of(TransformationAnomaly.RECIPIENT_MISSING_EMAIL, 0);

            assertThatThrownBy(() -> claimWith(DIGEST, counted))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("recipient-missing-email");
        }

        @Test
        void a_negative_count_should_be_refused() {
            final Map<TransformationAnomaly, Integer> counted =
                    Map.of(TransformationAnomaly.UNRESOLVABLE_APPLICATION, -1);

            assertThatThrownBy(() -> claimWith(DIGEST, counted))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unresolvable-application");
        }

        @Test
        void a_count_that_is_not_there_at_all_should_be_refused() {
            final Map<TransformationAnomaly, Integer> counted =
                    new EnumMap<>(TransformationAnomaly.class);
            counted.put(TransformationAnomaly.LETTER_DELIVERY_DROPPED, null);

            assertThatThrownBy(() -> claimWith(DIGEST, counted))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * The counts are taken, not borrowed. The transformation accumulates them in a mutable map
         * as it walks the hearing, and a claim holding that same map would describe whatever the
         * transformation did next rather than what it sent.
         */
        @Test
        void the_counts_should_be_taken_rather_than_shared_with_the_caller() {
            final Map<TransformationAnomaly, Integer> accumulating = new LinkedHashMap<>();
            accumulating.put(TransformationAnomaly.UNRESOLVABLE_APPLICATION, 1);
            final ProcessedOutputClaim claim = claimWith(DIGEST, accumulating);

            accumulating.put(TransformationAnomaly.RECIPIENT_MISSING_EMAIL, 3);

            assertThat(claim.anomalies())
                    .containsExactly(
                            Map.entry(TransformationAnomaly.UNRESOLVABLE_APPLICATION, 1));
        }

        @Test
        void the_counts_should_not_be_modifiable_through_the_claim() {
            // Handed a mutable map, deliberately: an assertion made against Map.of would pass on the
            // caller's immutability rather than on the record's.
            final Map<TransformationAnomaly, Integer> mutable = new LinkedHashMap<>();
            mutable.put(TransformationAnomaly.UNRESOLVABLE_APPLICATION, 1);
            final ProcessedOutputClaim claim = claimWith(DIGEST, mutable);

            assertThatThrownBy(() -> claim.anomalies()
                    .put(TransformationAnomaly.RECIPIENT_MISSING_EMAIL, 1))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
