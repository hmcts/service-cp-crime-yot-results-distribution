package uk.gov.hmcts.cp.yotresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision.Unreadable;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision.UnreadableReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.SweepFailureReason;

/**
 * One case per instrument of the downstream half: the name, the type, the label set and the
 * condition that moves it.
 *
 * <p>The sibling of {@code ProcessingMetricsTest}, written to the same rule: the names and labels
 * are asserted literally because they are a published surface - dashboards and alert rules are
 * written against them, and a rename is a breaking change even though nothing in this repository
 * would notice.
 *
 * <p>Three of them exist because a nightly, event-completed flow cannot be read from counters of
 * work that happened. The skipped counter separates "the flag is off" from "the flag could not be
 * read", which look identical from outside and are not the same night. The released counters say
 * what a run's first act had to give back, in batches and in registers, because a batch is one
 * document and one e-mail while a register is one hearing's youth defendants. And the three age
 * gauges are the only reading that moves when nothing happens at all - a record that is never
 * batched, a batch whose document never comes, or a batch whose render request was never recorded,
 * touches no counter here, which is exactly the
 * silence the batches counter's {@code notified-nobody} outcome (defect fix P1) also ends.
 *
 * <p>Absences are asserted too: no instrument may carry an identifier as a label - every defendant
 * on this register is a youth, so a label that could name one is a privacy breach as well as a
 * cardinality explosion - and systemdocgenerator's own {@code reason} text is nowhere on this
 * surface, because it belongs to the batch row and never to a series.
 */
class GenerationMetricsTest {

    /** Returned when a meter is absent, so a missing instrument fails as an assertion. */
    private static final double ABSENT = -1;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GenerationMetrics metrics = new GenerationMetrics(registry);

    private double counter(final String name) {
        final Counter counter = registry.find(name).counter();
        return counter == null ? ABSENT : counter.count();
    }

    private double counter(final String name, final String tag, final String value) {
        final Counter counter = registry.find(name).tag(tag, value).counter();
        return counter == null ? ABSENT : counter.count();
    }

    private double counter(final String name, final Tag... tags) {
        final Counter counter = registry.find(name).tags(List.of(tags)).counter();
        return counter == null ? ABSENT : counter.count();
    }

    private double gauge(final String name) {
        final Gauge gauge = registry.find(name).gauge();
        return gauge == null ? ABSENT : gauge.value();
    }

    private double timerCount(final String name) {
        final Timer timer = registry.find(name).timer();
        return timer == null ? ABSENT : timer.count();
    }

    private double timerTotalSeconds(final String name) {
        final Timer timer = registry.find(name).timer();
        return timer == null ? ABSENT : timer.totalTime(TimeUnit.SECONDS);
    }

    private List<String> tagKeysOf(final String name) {
        final Meter meter = registry.find(name).meter();
        return meter == null
                ? List.of("<meter absent>")
                : meter.getId().getTags().stream().map(Tag::getKey).toList();
    }

    /**
     * The counter a night is read by.
     *
     * <p>Four of the seven batch states are terminal, and they are terminal for four different
     * reasons: everybody who subscribes was told, somebody was not, there was nobody to tell, or
     * the document never came. Folding them into one "finished" is the shape the progression leg
     * ends a batch in, and telling the third apart from the others is defect fix P1.
     */
    @Nested
    @DisplayName("yotresultsdistribution_batches_total")
    class Batches {

        @Test
        void every_terminal_outcome_should_have_its_own_series() {
            metrics.batchCompleted(BatchStatus.NOTIFIED);
            metrics.batchCompleted(BatchStatus.PARTIALLY_NOTIFIED);
            metrics.batchCompleted(BatchStatus.NOTIFIED_NOBODY);
            metrics.batchCompleted(BatchStatus.FAILED);

            assertThat(counter(GenerationMetrics.BATCHES, "outcome", "notified")).isEqualTo(1);
            assertThat(counter(GenerationMetrics.BATCHES, "outcome", "partially-notified"))
                    .isEqualTo(1);
            assertThat(counter(GenerationMetrics.BATCHES, "outcome", "notified-nobody"))
                    .isEqualTo(1);
            assertThat(counter(GenerationMetrics.BATCHES, "outcome", "failed")).isEqualTo(1);
        }

        @Test
        void a_register_that_reached_nobody_should_count_as_neither_notified_nor_failed() {
            // Defect fix P1: the progression leg leaves a recipient-less batch at GENERATED and
            // publishes an event nothing subscribes to, so the failure is visible to no one. It is
            // a good ending, not a failure, and it is still worth alerting on.
            metrics.batchCompleted(BatchStatus.NOTIFIED_NOBODY);

            assertThat(counter(GenerationMetrics.BATCHES, "outcome", "notified-nobody"))
                    .isEqualTo(1);
            assertThat(counter(GenerationMetrics.BATCHES, "outcome", "notified"))
                    .isEqualTo(ABSENT);
            assertThat(counter(GenerationMetrics.BATCHES, "outcome", "failed")).isEqualTo(ABSENT);
        }

        @Test
        void repeated_outcomes_should_accumulate_on_their_own_series() {
            metrics.batchCompleted(BatchStatus.NOTIFIED);
            metrics.batchCompleted(BatchStatus.NOTIFIED);
            metrics.batchCompleted(BatchStatus.FAILED);

            assertThat(counter(GenerationMetrics.BATCHES, "outcome", "notified")).isEqualTo(2);
            assertThat(counter(GenerationMetrics.BATCHES, "outcome", "failed")).isEqualTo(1);
        }

        @Test
        void it_should_carry_the_outcome_label_and_nothing_else() {
            // Never the batch id, never the court centre: one register is one court centre's
            // youth defendants for one day, so either label would name them.
            metrics.batchCompleted(BatchStatus.NOTIFIED);

            assertThat(tagKeysOf(GenerationMetrics.BATCHES)).containsExactly("outcome");
        }

        @Test
        void the_failure_reason_should_not_be_a_label_on_this_counter() {
            // The bounded reason is a column on register_batch and a field of the run report. It is
            // not a second dimension here: the batches counter answers "how did the night end", and
            // six reasons multiplied by seven outcomes is a series count nobody reads.
            metrics.batchCompleted(BatchStatus.FAILED);

            assertThat(tagKeysOf(GenerationMetrics.BATCHES)).doesNotContain("reason");
        }
    }

    @Nested
    @DisplayName("yotresultsdistribution_generation_request_total")
    class GenerationRequest {

        @Test
        void an_accepted_request_should_count_under_the_contracts_202() {
            metrics.generationRequested(202);

            assertThat(counter(GenerationMetrics.GENERATION_REQUEST, "response_code", "202"))
                    .isEqualTo(1);
        }

        @Test
        void a_status_line_the_contract_does_not_allow_should_be_its_own_series() {
            // 200 is not 202 and is counted as what it was, not as a failure: the contract permits
            // one status line, and the series is what says which one arrived.
            metrics.generationRequested(202);
            metrics.generationRequested(200);
            metrics.generationRequested(500);

            assertThat(counter(GenerationMetrics.GENERATION_REQUEST, "response_code", "202"))
                    .isEqualTo(1);
            assertThat(counter(GenerationMetrics.GENERATION_REQUEST, "response_code", "200"))
                    .isEqualTo(1);
            assertThat(counter(GenerationMetrics.GENERATION_REQUEST, "response_code", "500"))
                    .isEqualTo(1);
        }

        @Test
        void it_should_carry_the_response_code_label_and_nothing_else() {
            metrics.generationRequested(202);

            assertThat(tagKeysOf(GenerationMetrics.GENERATION_REQUEST))
                    .containsExactly("response_code");
        }
    }

    @Nested
    @DisplayName("yotresultsdistribution_generation_latency")
    class GenerationLatency {

        @Test
        void a_batchs_time_from_request_to_outcome_should_be_timed() {
            metrics.generationLatency(Duration.ofSeconds(90));

            assertThat(timerCount(GenerationMetrics.GENERATION_LATENCY)).isEqualTo(1);
            assertThat(timerTotalSeconds(GenerationMetrics.GENERATION_LATENCY)).isEqualTo(90);
        }

        @Test
        void every_batch_should_be_timed_however_its_outcome_arrived() {
            // One distribution, not one per completion route: a batch is timed from the request
            // to the outcome, whatever the outcome turned out to be.
            metrics.generationLatency(Duration.ofSeconds(30));
            metrics.generationLatency(Duration.ofSeconds(60));

            assertThat(timerCount(GenerationMetrics.GENERATION_LATENCY)).isEqualTo(2);
            assertThat(timerTotalSeconds(GenerationMetrics.GENERATION_LATENCY)).isEqualTo(90);
        }

        @Test
        void it_should_carry_no_label() {
            metrics.generationLatency(Duration.ofSeconds(1));

            assertThat(tagKeysOf(GenerationMetrics.GENERATION_LATENCY)).isEmpty();
        }
    }

    /**
     * The three counters that say what a night's first act had to give back.
     *
     * <p><strong>[A]</strong> - characterisations of the series Phase 3 landed, asserted here
     * because this suite is where the published surface is held: the names are what dashboards and
     * alert rules are written against, and a batch id may never be a label on any of them. They
     * stand where {@code yotresultsdistribution_generation_reconciled_total} stood, which said how much of
     * a night the grace-period query had to fetch and now describes a mechanism that does not
     * exist.
     *
     * <p>Three series rather than one with a label, because a batch is one document and one e-mail
     * while a register is one hearing's youth defendants, and a batch nothing could be given back
     * from is neither: it is a night's undone work, and nought is the expected reading.
     */
    @Nested
    @DisplayName("the released and contended counters")
    class Released {

        @Test
        void what_a_run_gave_back_should_count_on_two_series_of_its_own() {
            metrics.staleBatchesReleased(2);
            metrics.staleRegistersReleased(7);

            assertThat(counter(GenerationMetrics.RELEASED_BATCHES))
                    .as("the batches, which is how much of the estate a lost outcome cost")
                    .isEqualTo(2);
            assertThat(counter(GenerationMetrics.RELEASED_REGISTERS))
                    .as("and the registers inside them, which no count of batches can answer for")
                    .isEqualTo(7);
        }

        @Test
        void what_a_run_could_not_give_back_should_count_on_a_third() {
            metrics.staleBatchesContended(1);

            assertThat(counter(GenerationMetrics.RELEASE_CONTENDED))
                    .as("a path that leaves something undone moves a counter, and without this the "
                            + "only trace of a court centre day nothing can give back is a WARN")
                    .isEqualTo(1);
        }

        @Test
        void none_of_the_three_should_carry_a_label() {
            metrics.staleBatchesReleased(1);
            metrics.staleRegistersReleased(1);
            metrics.staleBatchesContended(1);

            assertThat(tagKeysOf(GenerationMetrics.RELEASED_BATCHES)).isEmpty();
            assertThat(tagKeysOf(GenerationMetrics.RELEASED_REGISTERS)).isEmpty();
            assertThat(tagKeysOf(GenerationMetrics.RELEASE_CONTENDED))
                    .as("a batch id is an identifier and identifiers are never a label here")
                    .isEmpty();
        }
    }

    /**
     * The counter that says why a night generated nothing.
     *
     * <p>An off flag is the legacy generating, which is the cutover working. An unreadable flag is
     * this service fail-closed on an App Configuration outage, which is not. They produce the same
     * empty night, so only the label tells them apart.
     */
    @Nested
    @DisplayName("yotresultsdistribution_generation_skipped_total")
    class Skipped {

        @Test
        void a_run_skipped_because_the_flag_is_off_should_count_under_off() {
            metrics.runSkipped(FlagDecision.OFF);

            assertThat(counter(GenerationMetrics.GENERATION_SKIPPED, "reason", "off"))
                    .isEqualTo(1);
        }

        @Test
        void every_unreadable_cause_should_be_its_own_series() {
            metrics.runSkipped(new Unreadable(UnreadableReason.NOT_CONFIGURED));
            metrics.runSkipped(new Unreadable(UnreadableReason.NOT_FOUND));
            metrics.runSkipped(new Unreadable(UnreadableReason.ACCESS_DENIED));
            metrics.runSkipped(new Unreadable(UnreadableReason.TIMED_OUT));
            metrics.runSkipped(new Unreadable(UnreadableReason.MALFORMED));
            metrics.runSkipped(new Unreadable(UnreadableReason.CALL_FAILED));

            assertThat(counter(GenerationMetrics.GENERATION_SKIPPED,
                    "reason", "unreadable-not-configured")).isEqualTo(1);
            assertThat(counter(GenerationMetrics.GENERATION_SKIPPED,
                    "reason", "unreadable-not-found")).isEqualTo(1);
            assertThat(counter(GenerationMetrics.GENERATION_SKIPPED,
                    "reason", "unreadable-access-denied")).isEqualTo(1);
            assertThat(counter(GenerationMetrics.GENERATION_SKIPPED,
                    "reason", "unreadable-timed-out")).isEqualTo(1);
            assertThat(counter(GenerationMetrics.GENERATION_SKIPPED,
                    "reason", "unreadable-malformed")).isEqualTo(1);
            assertThat(counter(GenerationMetrics.GENERATION_SKIPPED,
                    "reason", "unreadable-call-failed")).isEqualTo(1);
        }

        @Test
        void an_unreadable_flag_should_not_be_folded_into_an_off_one() {
            metrics.runSkipped(new Unreadable(UnreadableReason.TIMED_OUT));

            assertThat(counter(GenerationMetrics.GENERATION_SKIPPED,
                    "reason", "unreadable-timed-out")).isEqualTo(1);
            assertThat(counter(GenerationMetrics.GENERATION_SKIPPED, "reason", "off"))
                    .as("an outage the service rode out fail-closed is not the cutover working")
                    .isEqualTo(ABSENT);
        }

        @Test
        void it_should_carry_the_reason_label_and_nothing_else() {
            metrics.runSkipped(FlagDecision.OFF);

            assertThat(tagKeysOf(GenerationMetrics.GENERATION_SKIPPED)).containsExactly("reason");
        }
    }

    @Nested
    @DisplayName("yotresultsdistribution_notifications_total")
    class Notifications {

        @Test
        void an_accepted_recipient_should_count_with_the_status_line_that_accepted_it() {
            metrics.notificationSettled(NotificationStatus.ACCEPTED, 202);

            assertThat(counter(GenerationMetrics.NOTIFICATIONS,
                    Tag.of("status", "accepted"), Tag.of("response_code", "202"))).isEqualTo(1);
        }

        @Test
        void a_refused_recipient_should_be_its_own_series() {
            metrics.notificationSettled(NotificationStatus.ACCEPTED, 202);
            metrics.notificationSettled(NotificationStatus.FAILED, 500);

            assertThat(counter(GenerationMetrics.NOTIFICATIONS,
                    Tag.of("status", "accepted"), Tag.of("response_code", "202"))).isEqualTo(1);
            assertThat(counter(GenerationMetrics.NOTIFICATIONS,
                    Tag.of("status", "failed"), Tag.of("response_code", "500"))).isEqualTo(1);
        }

        @Test
        void a_recipient_nothing_answered_for_should_still_be_counted() {
            // A connection that never produced a status line is the failure most worth seeing, and
            // an absent label would make it the one shape no query matches.
            metrics.notificationSettled(NotificationStatus.FAILED, null);

            assertThat(counter(GenerationMetrics.NOTIFICATIONS,
                    Tag.of("status", "failed"),
                    Tag.of("response_code", GenerationMetrics.NO_RESPONSE))).isEqualTo(1);
        }

        @Test
        void it_should_carry_the_status_and_response_code_labels_and_nothing_else() {
            // Never the address, never the recipient's name, never the batch: the recipients of a
            // youth court register are a protected list, and a metric label outlives a log line.
            metrics.notificationSettled(NotificationStatus.ACCEPTED, 202);

            assertThat(tagKeysOf(GenerationMetrics.NOTIFICATIONS))
                    .containsExactlyInAnyOrder("status", "response_code");
        }
    }

    /**
     * The five readings that move when nothing happens.
     *
     * <p>Every counter above records work. These record its absence: a record nobody batched, a
     * batch nobody rendered, a deadline that cut a run short, a court centre day the run passed
     * over, and a flag nobody could read. A nightly flow that stopped running moves none of the
     * counters at all.
     */
    @Nested
    @DisplayName("the seven gauges")
    class Gauges {

        @Test
        void all_seven_should_be_registered_before_a_run_has_happened() {
            assertThat(gauge(GenerationMetrics.OLDEST_RECORDED_UNBATCHED_AGE)).isZero();
            assertThat(gauge(GenerationMetrics.OLDEST_GENERATING_AGE)).isZero();
            assertThat(gauge(GenerationMetrics.OLDEST_PENDING_AGE)).isZero();
            assertThat(gauge(GenerationMetrics.OLDEST_GENERATED_AGE)).isZero();
            assertThat(gauge(GenerationMetrics.PENDING_AFTER_DEADLINE)).isZero();
            assertThat(gauge(GenerationMetrics.DEFERRED_KEYS)).isZero();
            assertThat(gauge(GenerationMetrics.FLAG_READ_OK)).isEqualTo(1);
        }

        /**
         * The other batch nothing else can see. A batch whose document arrived and whose
         * notification never happened - the store went away between the mark and the rows, or the
         * listener's session rolled the delivery back after the mark had committed - stands at
         * GENERATED, and {@code oldest_generating_age} reads GENERATING while
         * {@code oldest_pending_age} reads PENDING. Without this reading, the state that leaves a
         * Youth Offending Team untold is the one state no gauge moves for, which is defect fix P1's
         * failure mode by another route.
         */
        @Test
        void the_oldest_batch_holding_a_document_nobody_was_told_about_should_be_reported() {
            metrics.oldestGeneratedAge(Duration.ofMinutes(70));

            assertThat(gauge(GenerationMetrics.OLDEST_GENERATED_AGE)).isEqualTo(4_200);
        }

        @Test
        void a_sweep_with_nothing_parked_at_generated_should_bring_that_gauge_back_down() {
            metrics.oldestGeneratedAge(Duration.ofMinutes(70));
            metrics.oldestGeneratedAge(Duration.ZERO);

            assertThat(gauge(GenerationMetrics.OLDEST_GENERATED_AGE)).isZero();
        }

        /**
         * The batch nothing else can see. A batch left PENDING with a payload id moves no counter
         * and appears in no other gauge - the generating gauge reads GENERATING, and its registers
         * are stamped and so outside {@code activeUnbatched} - which is exactly why it needs one.
         */
        @Test
        void the_oldest_batch_that_never_reached_the_renderer_should_be_reported_in_seconds() {
            metrics.oldestPendingAge(Duration.ofMinutes(45));

            assertThat(gauge(GenerationMetrics.OLDEST_PENDING_AGE)).isEqualTo(2_700);
        }

        @Test
        void a_sweep_with_nothing_stuck_at_pending_should_bring_that_gauge_back_down() {
            metrics.oldestPendingAge(Duration.ofMinutes(45));
            metrics.oldestPendingAge(Duration.ZERO);

            assertThat(gauge(GenerationMetrics.OLDEST_PENDING_AGE)).isZero();
        }

        @Test
        void the_oldest_unbatched_record_should_be_reported_in_seconds() {
            metrics.oldestRecordedUnbatchedAge(Duration.ofHours(26));

            assertThat(gauge(GenerationMetrics.OLDEST_RECORDED_UNBATCHED_AGE)).isEqualTo(93_600);
        }

        @Test
        void an_empty_store_should_report_no_age_rather_than_the_last_one() {
            metrics.oldestRecordedUnbatchedAge(Duration.ofHours(26));
            metrics.oldestRecordedUnbatchedAge(Duration.ZERO);

            assertThat(gauge(GenerationMetrics.OLDEST_RECORDED_UNBATCHED_AGE)).isZero();
        }

        @Test
        void the_oldest_batch_awaiting_a_document_should_be_reported_in_seconds() {
            metrics.oldestGeneratingAge(Duration.ofMinutes(45));

            assertThat(gauge(GenerationMetrics.OLDEST_GENERATING_AGE)).isEqualTo(2_700);
        }

        @Test
        void batches_the_deadline_left_unrequested_should_be_reported_as_a_count() {
            metrics.pendingAfterDeadline(3);

            assertThat(gauge(GenerationMetrics.PENDING_AFTER_DEADLINE)).isEqualTo(3);
        }

        @Test
        void a_run_that_requested_everything_should_lower_the_pending_gauge() {
            metrics.pendingAfterDeadline(3);
            metrics.pendingAfterDeadline(0);

            assertThat(gauge(GenerationMetrics.PENDING_AFTER_DEADLINE)).isZero();
        }

        @Test
        void an_unreadable_flag_should_lower_the_flag_gauge_and_a_later_read_should_raise_it() {
            metrics.flagRead(new Unreadable(UnreadableReason.ACCESS_DENIED));
            assertThat(gauge(GenerationMetrics.FLAG_READ_OK)).isZero();

            metrics.flagRead(FlagDecision.ON);
            assertThat(gauge(GenerationMetrics.FLAG_READ_OK)).isEqualTo(1);
        }

        @Test
        void an_off_flag_should_leave_the_flag_gauge_up_because_it_is_an_answer() {
            // The gauge reports whether App Configuration answers, not what it answered. Off is the
            // cutover working; the skipped counter is where the two are told apart.
            metrics.flagRead(FlagDecision.OFF);

            assertThat(gauge(GenerationMetrics.FLAG_READ_OK)).isEqualTo(1);
        }

        @Test
        void none_of_them_should_carry_a_label() {
            assertThat(tagKeysOf(GenerationMetrics.OLDEST_RECORDED_UNBATCHED_AGE)).isEmpty();
            assertThat(tagKeysOf(GenerationMetrics.OLDEST_GENERATING_AGE)).isEmpty();
            assertThat(tagKeysOf(GenerationMetrics.OLDEST_PENDING_AGE)).isEmpty();
            assertThat(tagKeysOf(GenerationMetrics.OLDEST_GENERATED_AGE)).isEmpty();
            assertThat(tagKeysOf(GenerationMetrics.PENDING_AFTER_DEADLINE)).isEmpty();
            assertThat(tagKeysOf(GenerationMetrics.FLAG_READ_OK)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the surface as a whole")
    class Surface {

        @Test
        void only_the_gauges_should_be_registered_before_anything_happens() {
            // Gauges are state, not events: a dashboard must be able to read them from a pod that
            // has not yet run a night.
            assertThat(registry.getMeters().stream().map(meter -> meter.getId().getName()).toList())
                    .containsExactlyInAnyOrder(
                            GenerationMetrics.OLDEST_RECORDED_UNBATCHED_AGE,
                            GenerationMetrics.OLDEST_GENERATING_AGE,
                            GenerationMetrics.OLDEST_PENDING_AGE,
                            GenerationMetrics.OLDEST_GENERATED_AGE,
                            GenerationMetrics.PENDING_AFTER_DEADLINE,
                            GenerationMetrics.DEFERRED_KEYS,
                            GenerationMetrics.DEFERRED_REGISTERS,
                            GenerationMetrics.FLAG_READ_OK);
        }

        @Test
        void exercising_everything_should_register_exactly_the_documented_instruments() {
            exerciseEveryInstrument();

            assertThat(registry.getMeters().stream()
                    .map(meter -> meter.getId().getName())
                    .distinct()
                    .toList())
                    .containsExactlyInAnyOrder(
                            GenerationMetrics.BATCHES,
                            GenerationMetrics.GENERATION_REQUEST,
                            GenerationMetrics.GENERATION_LATENCY,
                            GenerationMetrics.RELEASED_BATCHES,
                            GenerationMetrics.RELEASED_REGISTERS,
                            GenerationMetrics.RELEASE_CONTENDED,
                            GenerationMetrics.GENERATION_SKIPPED,
                            GenerationMetrics.NOTIFICATIONS,
                            GenerationMetrics.OLDEST_RECORDED_UNBATCHED_AGE,
                            GenerationMetrics.OLDEST_GENERATING_AGE,
                            GenerationMetrics.OLDEST_PENDING_AGE,
                            GenerationMetrics.OLDEST_GENERATED_AGE,
                            GenerationMetrics.PENDING_AFTER_DEADLINE,
                            GenerationMetrics.DEFERRED_KEYS,
                            GenerationMetrics.DEFERRED_REGISTERS,
                            GenerationMetrics.BATCH_SWEEP_FAILURES,
                            GenerationMetrics.FLAG_READ_OK);
        }

        @Test
        void no_instrument_should_carry_an_identifying_label() {
            exerciseEveryInstrument();

            assertThat(registry.getMeters().stream()
                    .flatMap(meter -> meter.getId().getTags().stream())
                    .map(Tag::getKey)
                    .distinct()
                    .sorted()
                    .toList())
                    .containsExactly("outcome", "reason", "response_code", "status");
        }

        @Test
        void every_label_value_should_be_a_bounded_code_or_a_status_line() {
            // Bounded codes and status lines, and never a batch id, a court centre, a recipient's
            // address or systemdocgenerator's own words about the document. The renderer's reason
            // is kept on the batch row, where support can read it and no series carries it.
            metrics.batchCompleted(BatchStatus.NOTIFIED_NOBODY);
            metrics.generationRequested(202);
            metrics.runSkipped(new Unreadable(UnreadableReason.TIMED_OUT));
            metrics.notificationSettled(NotificationStatus.ACCEPTED, 202);

            assertThat(registry.getMeters().stream()
                    .flatMap(meter -> meter.getId().getTags().stream())
                    .map(Tag::getValue)
                    .distinct()
                    .toList())
                    .containsExactlyInAnyOrder(
                            "notified-nobody", "202", "unreadable-timed-out", "accepted");
        }

        /**
         * And no series for the mechanism the increment retired.
         *
         * <p>A retirement is only done when nothing can quietly put it back, and the surface case
         * above cannot say so: it lists the instruments {@code exerciseEveryInstrument} exercises,
         * so a {@code reconciled()} re-added to {@code GenerationMetrics} and called by a
         * re-added writer would register a series no case here ever asks for. The name is
         * therefore asserted by absence, and the two things that could reach it - the constant a
         * dashboard would be written against and the method a caller would reach for - are
         * asserted absent too, because a name that exists is a name something will use.
         *
         * <p>The series was {@code yotresultsdistribution_generation_reconciled_total}: outcomes the
         * grace-period pass fetched rather than received. Nothing is asked of systemdocgenerator
         * between runs any more (FR-006), so it counts a mechanism this service does not have.
         */
        @Test
        void no_series_should_be_named_for_the_retired_reconciler() {
            exerciseEveryInstrument();

            assertThat(registry.find("yotresultsdistribution_generation_reconciled_total").meter())
                    .as("no vocabulary outlives the thing it names: an outcome this service "
                            + "fetched is a thing that no longer happens")
                    .isNull();
            assertThat(Arrays.stream(GenerationMetrics.class.getDeclaredFields())
                            .map(Field::getName)
                            .toList())
                    .as("the constant is what a dashboard and an alert rule are written against, "
                            + "and one that still compiles is one a later change will reach for")
                    .doesNotContain("GENERATION_RECONCILED");
            assertThat(Arrays.stream(GenerationMetrics.class.getDeclaredMethods())
                            .map(Method::getName)
                            .toList())
                    .as("and the method is what would register it again on first call, since a "
                            + "counter comes into being when something increments it")
                    .doesNotContain("reconciled");
        }

        @Test
        void there_should_be_no_instrument_for_the_renderers_own_reason_text() {
            // sdg_reason is another system's prose about a document whose every defendant is a
            // child. It has a column and a support query; it has no series and no label.
            exerciseEveryInstrument();

            assertThat(registry.find("yotresultsdistribution_generation_failure_reason_total").counter())
                    .isNull();
        }

        private void exerciseEveryInstrument() {
            metrics.batchCompleted(BatchStatus.NOTIFIED);
            metrics.generationRequested(202);
            metrics.generationLatency(Duration.ofSeconds(30));
            metrics.staleBatchesReleased(1);
            metrics.staleRegistersReleased(2);
            metrics.staleBatchesContended(1);
            metrics.runSkipped(FlagDecision.OFF);
            metrics.notificationSettled(NotificationStatus.ACCEPTED, 202);
            metrics.oldestRecordedUnbatchedAge(Duration.ofHours(1));
            metrics.oldestGeneratingAge(Duration.ofMinutes(20));
            metrics.oldestPendingAge(Duration.ofMinutes(45));
            metrics.oldestGeneratedAge(Duration.ofMinutes(70));
            metrics.pendingAfterDeadline(1);
            metrics.deferredKeys(1);
            metrics.deferredRegisters(1);
            metrics.batchSweepFailure(SweepFailureReason.STORE_UNAVAILABLE);
            metrics.flagRead(FlagDecision.OFF);
        }
    }

    /**
     * The surface as Prometheus actually reads it, rather than as Micrometer holds it.
     *
     * <p>Everything above asks the registry what it was given. A dashboard and an alert rule ask
     * the scrape endpoint, and between the two sits a naming convention: a counter's name is
     * rewritten, a timer gains its unit and its suffixes, a tag becomes a label, and a series is a
     * name and a label set together. Names that agree in the registry can still arrive at Prometheus
     * renamed, and the first anyone would know is an alert that has quietly stopped firing.
     */
    @Nested
    @DisplayName("the surface as Prometheus scrapes it")
    class PrometheusSurface {

        private final PrometheusMeterRegistry prometheus =
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        private final GenerationMetrics scraped = new GenerationMetrics(prometheus);

        /** Every sample line for one series name, in scrape order. */
        private List<String> samplesOf(final String series) {
            return prometheus.scrape().lines()
                    .filter(line -> line.startsWith(series + "{") || line.startsWith(series + " "))
                    .map(line -> line.substring(0, line.lastIndexOf(' ')))
                    .toList();
        }

        /** Every label key that appears anywhere in the scrape. */
        private List<String> scrapedLabelKeys() {
            return prometheus.scrape().lines()
                    .filter(line -> !line.startsWith("#"))
                    .filter(line -> line.contains("{"))
                    .map(line -> line.substring(line.indexOf('{') + 1, line.indexOf('}')))
                    .flatMap(labels -> Stream.of(labels.split(",")))
                    .filter(label -> label.contains("="))
                    .map(label -> label.substring(0, label.indexOf('=')).trim())
                    .distinct()
                    .sorted()
                    .toList();
        }

        @Test
        @DisplayName("every terminal batch outcome is its own series, named as documented")
        void the_batches_counter_should_scrape_one_series_per_terminal_outcome() {
            scraped.batchCompleted(BatchStatus.NOTIFIED);
            scraped.batchCompleted(BatchStatus.PARTIALLY_NOTIFIED);
            scraped.batchCompleted(BatchStatus.NOTIFIED_NOBODY);
            scraped.batchCompleted(BatchStatus.FAILED);

            assertThat(samplesOf(GenerationMetrics.BATCHES))
                    .as("three of the four are good endings, and one of those is defect fix P1")
                    .containsExactlyInAnyOrder(
                            "yotresultsdistribution_batches_total{outcome=\"notified\"}",
                            "yotresultsdistribution_batches_total{outcome=\"partially-notified\"}",
                            "yotresultsdistribution_batches_total{outcome=\"notified-nobody\"}",
                            "yotresultsdistribution_batches_total{outcome=\"failed\"}");
        }

        @Test
        @DisplayName("every skip reason is its own series, named as documented")
        void the_skipped_counter_should_scrape_one_series_per_reason() {
            scraped.runSkipped(FlagDecision.OFF);
            scraped.runSkipped(new Unreadable(UnreadableReason.TIMED_OUT));

            assertThat(samplesOf(GenerationMetrics.GENERATION_SKIPPED))
                    .containsExactlyInAnyOrder(
                            "yotresultsdistribution_generation_skipped_total{reason=\"off\"}",
                            "yotresultsdistribution_generation_skipped_total"
                                    + "{reason=\"unreadable-timed-out\"}");
        }

        @Test
        @DisplayName("the latency timer scrapes in seconds")
        void the_latency_timer_should_scrape_under_a_unit_suffixed_name() {
            scraped.generationLatency(Duration.ofSeconds(30));

            assertThat(prometheus.scrape())
                    .contains("yotresultsdistribution_generation_latency_seconds_count");
        }

        @Test
        @DisplayName("all seven gauges scrape from a pod that has not run a night")
        void the_gauges_should_scrape_before_any_run_has_happened() {
            assertThat(samplesOf(GenerationMetrics.OLDEST_RECORDED_UNBATCHED_AGE))
                    .containsExactly(GenerationMetrics.OLDEST_RECORDED_UNBATCHED_AGE);
            assertThat(samplesOf(GenerationMetrics.OLDEST_GENERATING_AGE))
                    .containsExactly(GenerationMetrics.OLDEST_GENERATING_AGE);
            assertThat(samplesOf(GenerationMetrics.OLDEST_PENDING_AGE))
                    .containsExactly(GenerationMetrics.OLDEST_PENDING_AGE);
            assertThat(samplesOf(GenerationMetrics.OLDEST_GENERATED_AGE))
                    .containsExactly(GenerationMetrics.OLDEST_GENERATED_AGE);
            assertThat(samplesOf(GenerationMetrics.PENDING_AFTER_DEADLINE))
                    .containsExactly(GenerationMetrics.PENDING_AFTER_DEADLINE);
            assertThat(samplesOf(GenerationMetrics.DEFERRED_KEYS))
                    .containsExactly(GenerationMetrics.DEFERRED_KEYS);
            assertThat(samplesOf(GenerationMetrics.FLAG_READ_OK))
                    .containsExactly(GenerationMetrics.FLAG_READ_OK);
        }

        @Test
        @DisplayName("the unlabelled counter scrapes as a single, unlabelled series")
        void the_released_batches_counter_should_scrape_without_a_label_set() {
            scraped.staleBatchesReleased(1);

            assertThat(samplesOf(GenerationMetrics.RELEASED_BATCHES))
                    .containsExactly(GenerationMetrics.RELEASED_BATCHES);
        }

        @Test
        @DisplayName("no scraped series carries a label beyond the four bounded dimensions")
        void the_scrape_should_carry_no_identifying_label() {
            // The registry-level assertion above proves the tags this code sets. This one proves
            // what leaves the pod: a label added by a convention, a common tag or a registry filter
            // would appear here and nowhere else, and a metric label is a log line kept for a year.
            scraped.batchCompleted(BatchStatus.NOTIFIED_NOBODY);
            scraped.generationRequested(202);
            scraped.generationLatency(Duration.ofSeconds(30));
            scraped.staleBatchesReleased(1);
            scraped.runSkipped(new Unreadable(UnreadableReason.MALFORMED));
            scraped.notificationSettled(NotificationStatus.FAILED, null);

            assertThat(scrapedLabelKeys())
                    .containsExactly("outcome", "reason", "response_code", "status");
        }
    }
}
