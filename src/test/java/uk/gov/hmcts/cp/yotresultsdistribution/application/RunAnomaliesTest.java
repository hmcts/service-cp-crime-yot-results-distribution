package uk.gov.hmcts.cp.yotresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ProcessedOutputClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationAnomaly;

/**
 * What one run skipped, counted as the run goes.
 *
 * <p>The mappers have counted their guarded skips through an injected {@link
 * java.util.function.Consumer} since T054, and until now the only consumer that existed was the one
 * a test passed them. In the running service the counts went nowhere: no metric moved and no row
 * carried them, so C19, C20 and C27 — three fixes whose whole content is "the skip stops being
 * invisible" — were invisible in exactly the deployment they were written for.
 *
 * <p>This is the thing that receives them, and the two properties that make it safe to write down
 * are what the cases below are about.
 *
 * <ul>
 *   <li><strong>Bounded.</strong> The keys are an enum, so no payload can invent a code and no
 *       summary can carry a defendant's name, an address or a fragment of the message body — which
 *       is what {@code completion_reason} and {@code anomaly_summary} being bounded codes means
 *       (constitution Principle VII). The counts saturate rather than wrapping, because a count that
 *       went negative would be refused by {@link ProcessedOutputClaim} at the moment the register
 *       was about to be sent.</li>
 *   <li><strong>Run-scoped, and frozen when read.</strong> One accumulator per run, and
 *       {@link RunAnomalies#counts()} is a copy: the claim written before a POST describes the
 *       register being sent, not whatever the pod counted next.</li>
 * </ul>
 */
@DisplayName("RunAnomalies")
class RunAnomaliesTest {

    private final RunAnomalies anomalies = new RunAnomalies();

    @Nested
    @DisplayName("counting")
    class Counting {

        @Test
        @DisplayName("a run that skipped nothing counts nothing")
        void nothing_counted_is_nothing_written() {
            assertThat(anomalies.isEmpty()).isTrue();
            assertThat(anomalies.counts()).isEmpty();
        }

        @Test
        @DisplayName("each occurrence is counted, per reason")
        void each_occurrence_is_counted_per_reason() {
            anomalies.accept(TransformationAnomaly.LETTER_DELIVERY_DROPPED);
            anomalies.accept(TransformationAnomaly.LETTER_DELIVERY_DROPPED);
            anomalies.accept(TransformationAnomaly.UNRESOLVABLE_APPLICATION);

            assertThat(anomalies.counts()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED, 2,
                    TransformationAnomaly.UNRESOLVABLE_APPLICATION, 1));
            assertThat(anomalies.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("a reason that never happened is absent, never counted zero")
        void a_reason_that_never_happened_is_absent() {
            anomalies.accept(TransformationAnomaly.RECIPIENT_MISSING_EMAIL);

            assertThat(anomalies.counts())
                    .containsOnlyKeys(TransformationAnomaly.RECIPIENT_MISSING_EMAIL);
        }
    }

    @Nested
    @DisplayName("what the counts are safe to be written into")
    class SafeToWrite {

        @Test
        @DisplayName("the counts a claim accepts, because a claim refuses a count of zero or less")
        void the_counts_a_claim_accepts() {
            anomalies.accept(TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT);

            final ProcessedOutputClaim claim = new ProcessedOutputClaim(
                    UUID.randomUUID(), UUID.randomUUID(), "B01LY00",
                    LocalDate.parse("2020-06-01"), "court-register.pdf",
                    "a".repeat(64), anomalies.counts());

            assertThat(claim.anomalies()).containsEntry(
                    TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT, 1);
        }

        @Test
        @DisplayName("reading the counts takes a copy, so a claim cannot change under the POST")
        void reading_the_counts_takes_a_copy() {
            anomalies.accept(TransformationAnomaly.LETTER_DELIVERY_DROPPED);
            final Map<TransformationAnomaly, Integer> read = anomalies.counts();

            anomalies.accept(TransformationAnomaly.LETTER_DELIVERY_DROPPED);

            assertThat(read).containsEntry(TransformationAnomaly.LETTER_DELIVERY_DROPPED, 1);
            assertThat(anomalies.counts())
                    .containsEntry(TransformationAnomaly.LETTER_DELIVERY_DROPPED, 2);
        }

        @Test
        @DisplayName("the copy is unmodifiable, so nothing downstream can add to a run's record")
        void the_copy_is_unmodifiable() {
            anomalies.accept(TransformationAnomaly.LETTER_DELIVERY_DROPPED);

            assertThatThrownBy(() -> anomalies.counts()
                    .put(TransformationAnomaly.RECIPIENT_MISSING_EMAIL, 1))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("the summary a log line may carry")
    class Summary {

        @Test
        @DisplayName("is the bounded codes and their counts, and nothing else")
        void is_the_bounded_codes_and_their_counts() {
            anomalies.accept(TransformationAnomaly.LETTER_DELIVERY_DROPPED);
            anomalies.accept(TransformationAnomaly.LETTER_DELIVERY_DROPPED);

            assertThat(anomalies.summary()).isEqualTo("letter-delivery-dropped:2");
        }

        @Test
        @DisplayName("names every reason that happened, in a stable order")
        void names_every_reason_in_a_stable_order() {
            anomalies.accept(TransformationAnomaly.RECIPIENT_MISSING_EMAIL);
            anomalies.accept(TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT);

            assertThat(anomalies.summary())
                    .isEqualTo("unresolvable-youth-defendant:1,recipient-missing-email:1");
        }

        @Test
        @DisplayName("of a run that skipped nothing is empty")
        void of_a_run_that_skipped_nothing() {
            assertThat(anomalies.summary()).isEmpty();
        }
    }
}
