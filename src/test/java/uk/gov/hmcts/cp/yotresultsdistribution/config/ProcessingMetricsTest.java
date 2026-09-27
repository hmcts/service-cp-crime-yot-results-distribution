package uk.gov.hmcts.cp.yotresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletionReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeadLetterReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionKind;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportRunOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportSinkName;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.SettlementOperation;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.SweepFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationAnomaly;

/**
 * One case per instrument: the name, the type, the label set and the condition that moves it.
 *
 * <p>The names and labels are asserted literally because they are a published surface — dashboards
 * and alert rules are written against them, and a rename is a breaking change even though nothing in
 * this repository would notice.
 *
 * <p>Two of them are this flow's own. The completions counter separates the five ways a run can end
 * well, because on this flow "nothing was sent" is the ordinary answer and not an incident: two of
 * its four no-op reasons are the most common outcomes the service has, and an undifferentiated
 * success is the legacy defect C33 rather than a simplification. The anomaly counter is the
 * telemetry half of fixes C19, C20 and C27 — a register that survived with a part missing, which the
 * legacy pipeline either lost whole or dropped in silence.
 *
 * <p>Absences are asserted too: no instrument may carry an identifier as a label — every defendant
 * on this register is a youth, so a label that could name one is a privacy breach as well as a
 * cardinality explosion — and there is no dead-letter depth gauge, because depth is read from the
 * platform's own queue metric rather than polled by service code.
 */
class ProcessingMetricsTest {

    /** Returned when a meter is absent, so a missing instrument fails as an assertion. */
    private static final double ABSENT = -1;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProcessingMetrics metrics = new ProcessingMetrics(registry);

    private double counter(final String name) {
        final Counter counter = registry.find(name).counter();
        return counter == null ? ABSENT : counter.count();
    }

    private double counter(final String name, final String tag, final String value) {
        final Counter counter = registry.find(name).tag(tag, value).counter();
        return counter == null ? ABSENT : counter.count();
    }

    private double gauge(final String name) {
        final Gauge gauge = registry.find(name).gauge();
        return gauge == null ? ABSENT : gauge.value();
    }

    private double timer(final String name, final String tag, final String value) {
        final Timer found = registry.find(name).tag(tag, value).timer();
        return found == null ? ABSENT : found.count();
    }

    private List<String> tagKeysOf(final String name) {
        final Meter meter = registry.find(name).meter();
        return meter == null
                ? List.of("<meter absent>")
                : meter.getId().getTags().stream().map(Tag::getKey).toList();
    }

    @Nested
    @DisplayName("yotresultsdistribution_processed_total")
    class Processed {

        @Test
        void a_completed_request_should_increment_the_completed_series() {
            settled(RequestStatus.COMPLETED);

            assertThat(counter(ProcessingMetrics.PROCESSED, "outcome", "completed")).isEqualTo(1);
        }

        @Test
        void a_parked_request_should_increment_the_failed_series() {
            settled(RequestStatus.FAILED);

            assertThat(counter(ProcessingMetrics.PROCESSED, "outcome", "failed")).isEqualTo(1);
        }

        @Test
        void the_two_outcomes_should_be_separate_series() {
            settled(RequestStatus.COMPLETED);
            settled(RequestStatus.COMPLETED);
            settled(RequestStatus.FAILED);

            assertThat(counter(ProcessingMetrics.PROCESSED, "outcome", "completed")).isEqualTo(2);
            assertThat(counter(ProcessingMetrics.PROCESSED, "outcome", "failed")).isEqualTo(1);
        }

        /**
         * One settlement is one recording, made where the run is known to have ended.
         *
         * <p>Two calls said the same thing at every settlement in {@code DistributionPipeline} -
         * the counter by its outcome and the timer by its terminal status - and the pair could
         * drift: a path that remembered one and forgot the other would publish a count with no
         * duration, or a duration with no count, and the two series an operator reads side by side
         * would disagree about how many requests finished. The status the timer refuses to take
         * unless it is terminal is the same fact the counter's outcome carries, so the facade takes
         * it once and answers both.
         */
        @Test
        void the_terminal_outcome_is_counted_where_the_run_is_timed() {
            metrics.requestSettled(metrics.startRequestTiming(), RequestStatus.COMPLETED);
            metrics.requestSettled(metrics.startRequestTiming(), RequestStatus.FAILED);

            assertThat(counter(ProcessingMetrics.PROCESSED, "outcome", "completed"))
                    .as("counted by the same call that timed it, so a caller cannot record one "
                            + "without the other")
                    .isEqualTo(1);
            assertThat(counter(ProcessingMetrics.PROCESSED, "outcome", "failed"))
                    .isEqualTo(1);
            assertThat(timer(ProcessingMetrics.REQUEST_DURATION, "outcome", "completed"))
                    .as("and the two series are still the two they were, under the same names and "
                            + "the same label values a dashboard already reads")
                    .isEqualTo(1);
        }

        @Test
        void it_should_carry_the_outcome_label_and_nothing_else() {
            settled(RequestStatus.COMPLETED);

            assertThat(tagKeysOf(ProcessingMetrics.PROCESSED)).containsExactly("outcome");
        }

        /** One settlement, which is one call: the count and the duration are one recording. */
        private void settled(final RequestStatus outcome) {
            metrics.requestSettled(metrics.startRequestTiming(), outcome);
        }
    }

    /**
     * The distribution this flow's dashboards are actually read for.
     *
     * <p>Expect a high completed-but-not-submitted rate: a hearing with no youth defendants and a
     * court centre nobody subscribes to are the two commonest results the service has, and neither
     * is an incident. Telling them apart is what makes the difference between a quiet day and a
     * broken pipeline visible without opening the processed log.
     */
    @Nested
    @DisplayName("yotresultsdistribution_completions_total")
    class Completions {

        @Test
        void a_submitted_register_should_increment_its_own_series() {
            metrics.completed(CompletionReason.SUBMITTED);

            assertThat(counter(ProcessingMetrics.COMPLETIONS, "reason", "submitted")).isEqualTo(1);
        }

        @Test
        void every_reason_should_have_its_own_series() {
            metrics.completed(CompletionReason.SUBMITTED);
            metrics.completed(CompletionReason.GROUP_PROCEEDINGS);
            metrics.completed(CompletionReason.NO_DEFENDANTS);
            metrics.completed(CompletionReason.NO_SUBSCRIPTIONS);
            metrics.completed(CompletionReason.NO_YOUTH_DEFENDANTS);

            assertThat(counter(ProcessingMetrics.COMPLETIONS, "reason", "submitted")).isEqualTo(1);
            assertThat(counter(ProcessingMetrics.COMPLETIONS, "reason", "group-proceedings"))
                    .isEqualTo(1);
            assertThat(counter(ProcessingMetrics.COMPLETIONS, "reason", "no-defendants"))
                    .isEqualTo(1);
            assertThat(counter(ProcessingMetrics.COMPLETIONS, "reason", "no-subscriptions"))
                    .isEqualTo(1);
            assertThat(counter(ProcessingMetrics.COMPLETIONS, "reason", "no-youth-defendants"))
                    .isEqualTo(1);
        }

        @Test
        void the_no_op_reasons_should_not_be_folded_into_one_another() {
            metrics.completed(CompletionReason.NO_SUBSCRIPTIONS);
            metrics.completed(CompletionReason.NO_SUBSCRIPTIONS);
            metrics.completed(CompletionReason.NO_YOUTH_DEFENDANTS);

            assertThat(counter(ProcessingMetrics.COMPLETIONS, "reason", "no-subscriptions"))
                    .isEqualTo(2);
            assertThat(counter(ProcessingMetrics.COMPLETIONS, "reason", "no-youth-defendants"))
                    .isEqualTo(1);
        }

        @Test
        void it_should_carry_the_reason_label_and_nothing_else() {
            metrics.completed(CompletionReason.SUBMITTED);

            assertThat(tagKeysOf(ProcessingMetrics.COMPLETIONS)).containsExactly("reason");
        }
    }

    @Nested
    @DisplayName("yotresultsdistribution_processing_failures_total")
    class ProcessingFailures {

        @Test
        void a_transient_failure_ending_in_retrying_should_increment_it() {
            // Every failed run counts, not only the one that exhausts the delivery budget: a
            // request retrying quietly forever is exactly what this service exists to make visible.
            metrics.pipelineFailed(FailureClassification.TRANSIENT);

            assertThat(counter(ProcessingMetrics.PROCESSING_FAILURES, "classification", "transient"))
                    .isEqualTo(1);
        }

        @Test
        void a_non_transient_failure_should_increment_its_own_series() {
            metrics.pipelineFailed(FailureClassification.NON_TRANSIENT);

            assertThat(counter(ProcessingMetrics.PROCESSING_FAILURES,
                    "classification", "non-transient")).isEqualTo(1);
        }

        @Test
        void it_should_carry_the_classification_label_and_nothing_else() {
            metrics.pipelineFailed(FailureClassification.TRANSIENT);

            assertThat(tagKeysOf(ProcessingMetrics.PROCESSING_FAILURES))
                    .containsExactly("classification");
        }
    }

    /**
     * The telemetry half of fixes C19, C20 and C27.
     *
     * <p>Each of these was a place the legacy pipeline threw and lost the whole hearing's register,
     * or dropped a recipient without a log line. The register now survives with the unresolvable
     * part skipped, which is only an improvement if somebody can see it happening — so a skip is
     * counted here and written to {@code processed_output.anomaly_summary}, and is deliberately not
     * a failure.
     */
    @Nested
    @DisplayName("yotresultsdistribution_transformation_anomalies_total")
    class TransformationAnomalies {

        @Test
        void every_anomaly_should_have_its_own_series() {
            metrics.transformationAnomaly(TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT);
            metrics.transformationAnomaly(TransformationAnomaly.UNRESOLVABLE_APPLICATION);
            metrics.transformationAnomaly(TransformationAnomaly.LETTER_DELIVERY_DROPPED);
            metrics.transformationAnomaly(TransformationAnomaly.RECIPIENT_MISSING_EMAIL);
            metrics.transformationAnomaly(TransformationAnomaly.RECIPIENT_NOT_FOR_DISTRIBUTION);

            assertThat(counter(ProcessingMetrics.TRANSFORMATION_ANOMALIES,
                    "reason", "unresolvable-youth-defendant")).isEqualTo(1);
            assertThat(counter(ProcessingMetrics.TRANSFORMATION_ANOMALIES,
                    "reason", "unresolvable-application")).isEqualTo(1);
            assertThat(counter(ProcessingMetrics.TRANSFORMATION_ANOMALIES,
                    "reason", "letter-delivery-dropped")).isEqualTo(1);
            assertThat(counter(ProcessingMetrics.TRANSFORMATION_ANOMALIES,
                    "reason", "recipient-missing-email")).isEqualTo(1);
            assertThat(counter(ProcessingMetrics.TRANSFORMATION_ANOMALIES,
                    "reason", "recipient-not-for-distribution")).isEqualTo(1);
        }

        @Test
        void repeated_drops_in_one_register_should_count_once_each() {
            // The bounded summary the processed log records is a count — `letter-delivery-dropped:2`
            // — so the counter has to move once per dropped recipient, not once per register.
            metrics.transformationAnomaly(TransformationAnomaly.LETTER_DELIVERY_DROPPED);
            metrics.transformationAnomaly(TransformationAnomaly.LETTER_DELIVERY_DROPPED);

            assertThat(counter(ProcessingMetrics.TRANSFORMATION_ANOMALIES,
                    "reason", "letter-delivery-dropped")).isEqualTo(2);
        }

        @Test
        void an_anomaly_should_not_be_counted_as_a_processing_failure() {
            // A skipped part is not a failed run. TRANSFORMATION_FAILED is reserved for a
            // transformation that cannot produce a document at all.
            metrics.transformationAnomaly(TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT);

            assertThat(counter(ProcessingMetrics.PROCESSING_FAILURES,
                    "classification", "non-transient")).isEqualTo(ABSENT);
        }

        @Test
        void it_should_carry_the_reason_label_and_nothing_else() {
            metrics.transformationAnomaly(TransformationAnomaly.UNRESOLVABLE_APPLICATION);

            assertThat(tagKeysOf(ProcessingMetrics.TRANSFORMATION_ANOMALIES))
                    .containsExactly("reason");
        }
    }

    @Nested
    @DisplayName("the intake suspension counters and yotresultsdistribution_intake_suspended")
    class Intake {

        @Test
        void suspending_intake_should_increment_the_counter_and_raise_the_gauge() {
            metrics.intakeSuspended();

            assertThat(counter(ProcessingMetrics.INTAKE_SUSPENSIONS)).isEqualTo(1);
            assertThat(gauge(ProcessingMetrics.INTAKE_SUSPENDED)).isEqualTo(1);
        }

        @Test
        void resuming_intake_should_lower_the_gauge_without_touching_the_counter() {
            metrics.intakeSuspended();
            metrics.intakeResumed();

            assertThat(gauge(ProcessingMetrics.INTAKE_SUSPENDED)).isZero();
            assertThat(counter(ProcessingMetrics.INTAKE_SUSPENSIONS)).isEqualTo(1);
        }

        @Test
        void the_gauge_should_read_zero_before_anything_happens() {
            assertThat(gauge(ProcessingMetrics.INTAKE_SUSPENDED)).isZero();
        }

        @Test
        void a_second_suspension_should_count_again() {
            metrics.intakeSuspended();
            metrics.intakeResumed();
            metrics.intakeSuspended();

            assertThat(counter(ProcessingMetrics.INTAKE_SUSPENSIONS)).isEqualTo(2);
        }

        @Test
        void neither_should_carry_a_label() {
            metrics.intakeSuspended();

            assertThat(tagKeysOf(ProcessingMetrics.INTAKE_SUSPENSIONS)).isEmpty();
            assertThat(tagKeysOf(ProcessingMetrics.INTAKE_SUSPENDED)).isEmpty();
        }

        /**
         * A suspension that could not be carried out is the more urgent of the two readings, and
         * the only instrument that can report it. The suspension counter cannot move — the outage
         * was not contained — and the gauge reads exactly what a healthy pod reads, so without this
         * series a pod spending the broker's delivery budget on an outage of ours is invisible.
         */
        @Test
        void a_suspension_that_failed_should_count_on_its_own_series() {
            metrics.intakeSuspensionFailed();

            assertThat(counter(ProcessingMetrics.INTAKE_SUSPENSION_FAILURES)).isEqualTo(1);
            assertThat(counter(ProcessingMetrics.INTAKE_SUSPENSIONS))
                    .as("nothing was contained, so nothing may be counted as contained")
                    .isEqualTo(ABSENT);
            assertThat(gauge(ProcessingMetrics.INTAKE_SUSPENDED))
                    .as("and the gauge must not claim a stop that did not happen")
                    .isZero();
        }

        @Test
        void a_failed_suspension_should_count_per_attempt_and_carry_no_label() {
            metrics.intakeSuspensionFailed();
            metrics.intakeSuspensionFailed();

            assertThat(counter(ProcessingMetrics.INTAKE_SUSPENSION_FAILURES))
                    .as("a rising series is what says the retries are not getting anywhere")
                    .isEqualTo(2);
            assertThat(tagKeysOf(ProcessingMetrics.INTAKE_SUSPENSION_FAILURES)).isEmpty();
        }
    }

    @Nested
    @DisplayName("yotresultsdistribution_deadlettered_total")
    class DeadLettered {

        @Test
        void every_reason_should_have_its_own_series() {
            metrics.deadLettered(DeadLetterReason.VALIDATION);
            metrics.deadLettered(DeadLetterReason.COLLISION);
            metrics.deadLettered(DeadLetterReason.EXHAUSTED);
            metrics.deadLettered(DeadLetterReason.NON_TRANSIENT);

            assertThat(counter(ProcessingMetrics.DEAD_LETTERED, "reason", "validation"))
                    .isEqualTo(1);
            assertThat(counter(ProcessingMetrics.DEAD_LETTERED, "reason", "collision"))
                    .isEqualTo(1);
            assertThat(counter(ProcessingMetrics.DEAD_LETTERED, "reason", "exhausted"))
                    .isEqualTo(1);
            assertThat(counter(ProcessingMetrics.DEAD_LETTERED, "reason", "non-transient"))
                    .isEqualTo(1);
        }

        @Test
        void it_should_carry_the_reason_label_and_nothing_else() {
            metrics.deadLettered(DeadLetterReason.VALIDATION);

            assertThat(tagKeysOf(ProcessingMetrics.DEAD_LETTERED)).containsExactly("reason");
        }

        /**
         * The two reason-labelled counters are separate instruments, so a dead-letter can never be
         * read as a completion — which is exactly the confusion the legacy pipeline's single
         * {@code Success: true} produced.
         */
        @Test
        void a_dead_letter_should_not_touch_the_completions_counter() {
            metrics.deadLettered(DeadLetterReason.NON_TRANSIENT);

            assertThat(counter(ProcessingMetrics.COMPLETIONS, "reason", "non-transient"))
                    .isEqualTo(ABSENT);
        }
    }

    @Nested
    @DisplayName("yotresultsdistribution_settlement_failures_total")
    class SettlementFailures {

        @Test
        void every_settlement_call_should_have_its_own_series() {
            metrics.settlementFailed(SettlementOperation.COMPLETE);
            metrics.settlementFailed(SettlementOperation.ABANDON);
            metrics.settlementFailed(SettlementOperation.DEADLETTER);

            assertThat(counter(ProcessingMetrics.SETTLEMENT_FAILURES, "operation", "complete"))
                    .isEqualTo(1);
            assertThat(counter(ProcessingMetrics.SETTLEMENT_FAILURES, "operation", "abandon"))
                    .isEqualTo(1);
            assertThat(counter(ProcessingMetrics.SETTLEMENT_FAILURES, "operation", "deadletter"))
                    .isEqualTo(1);
        }

        @Test
        void it_should_carry_the_operation_label_and_nothing_else() {
            metrics.settlementFailed(SettlementOperation.ABANDON);

            assertThat(tagKeysOf(ProcessingMetrics.SETTLEMENT_FAILURES))
                    .containsExactly("operation");
        }
    }

    @Nested
    @DisplayName("the unlabelled counters")
    class UnlabelledCounters {

        @Test
        void a_lost_lock_should_increment_its_counter() {
            metrics.lockLost();

            assertThat(counter(ProcessingMetrics.LOCK_LOSS)).isEqualTo(1);
            assertThat(tagKeysOf(ProcessingMetrics.LOCK_LOSS)).isEmpty();
        }

        @Test
        void a_rejected_stale_runner_should_increment_its_counter() {
            metrics.staleRunnerRejected();

            assertThat(counter(ProcessingMetrics.STALE_RUNNER_REJECTIONS)).isEqualTo(1);
            assertThat(tagKeysOf(ProcessingMetrics.STALE_RUNNER_REJECTIONS)).isEmpty();
        }
    }

    @Nested
    @DisplayName("yotresultsdistribution_servicebus_up")
    class ServiceBusUp {

        @Test
        void it_should_start_up_because_no_outage_has_been_observed() {
            assertThat(gauge(ProcessingMetrics.SERVICEBUS_UP)).isEqualTo(1);
        }

        @Test
        void it_should_mirror_the_health_component_in_both_directions() {
            metrics.serviceBusUp(false);
            assertThat(gauge(ProcessingMetrics.SERVICEBUS_UP)).isZero();

            metrics.serviceBusUp(true);
            assertThat(gauge(ProcessingMetrics.SERVICEBUS_UP)).isEqualTo(1);
        }

        /**
         * A scrape and a health check must read the same live state rather than the same remembered
         * one, whichever of them happens first: a Prometheus scrape does not call the health
         * endpoint on its way past.
         */
        @Test
        void it_should_answer_from_the_component_it_is_bound_to() {
            final boolean[] reachable = {true};
            metrics.bindServiceBusUp(() -> reachable[0]);

            assertThat(gauge(ProcessingMetrics.SERVICEBUS_UP)).isEqualTo(1);

            reachable[0] = false;
            assertThat(gauge(ProcessingMetrics.SERVICEBUS_UP)).isZero();
        }

        @Test
        void it_should_not_carry_a_label() {
            assertThat(tagKeysOf(ProcessingMetrics.SERVICEBUS_UP)).isEmpty();
        }
    }

    /**
     * The four instruments design section 11 promised and 001 never built, plus the counter that
     * makes the sweep's one absorbed refusal visible.
     *
     * <p>Two gauges about the intake half, a timer over the run that produced them, and four
     * counters about the morning report. They are asserted together because they share one rule:
     * every label is drawn from a closed enumeration, and none of them is ever an identifier. On a
     * register whose every defendant is a youth, a label that could name one is a privacy breach
     * before it is a cardinality problem, and a metric label is a log line kept for a year.
     */
    @Nested
    @DisplayName("the intake gauges, the duration timer and the four report counters")
    class TheReportInstruments {

        @Test
        void the_two_intake_gauges_exist_from_construction_and_read_zero() {
            // Registered in the constructor, not on first use: a gauge that only appears after the
            // first incident is not an alerting surface, which is the argument this class already
            // makes for `yotresultsdistribution_intake_suspended`. A healthy pod that has swept once and
            // found nothing reads zero, and zero is a reading rather than an absence.
            assertThat(gauge(ProcessingMetrics.OLDEST_NON_TERMINAL_REQUEST_AGE))
                    .as("the oldest unfinished request, in seconds, from a pod that has swept "
                            + "nothing yet")
                    .isZero();
            assertThat(gauge(ProcessingMetrics.NON_TERMINAL_REQUESTS_OVER_THRESHOLD))
                    .as("and how many of them are over the intake threshold")
                    .isZero();
        }

        @Test
        void the_request_duration_timer_is_tagged_only_by_its_terminal_outcome() {
            metrics.requestSettled(metrics.startRequestTiming(), RequestStatus.COMPLETED);
            metrics.requestSettled(metrics.startRequestTiming(), RequestStatus.FAILED);

            assertThat(timer(ProcessingMetrics.REQUEST_DURATION, "outcome", "completed"))
                    .as("one sample per terminal transition the guard accepted")
                    .isEqualTo(1);
            assertThat(timer(ProcessingMetrics.REQUEST_DURATION, "outcome", "failed"))
                    .isEqualTo(1);
            assertThat(tagKeysOf(ProcessingMetrics.REQUEST_DURATION))
                    .as("the terminal outcome and nothing else — not the source, not the reason, "
                            + "and never the request")
                    .containsExactly("outcome");
        }

        @Test
        void the_timing_token_is_opaque_and_carries_no_micrometer_type_into_the_caller()
                throws NoSuchMethodException {
            final Method start = ProcessingMetrics.class.getMethod("startRequestTiming");
            final Method stop = ProcessingMetrics.class.getMethod(
                    "requestSettled", ProcessingMetrics.Timing.class, RequestStatus.class);

            assertThat(start.getReturnType())
                    .as("the start method answers a token of this class's own, not a Timer.Sample: "
                            + "a Micrometer type here would be a Micrometer type in application/ "
                            + "(Principle V)")
                    .isEqualTo(ProcessingMetrics.Timing.class);
            assertThat(stop.getReturnType()).isEqualTo(void.class);
            assertThat(ProcessingMetrics.Timing.class.getFields())
                    .as("nothing on the token is reachable, so the caller can do nothing with it "
                            + "but give it back")
                    .isEmpty();
            assertThat(Stream.of(ProcessingMetrics.Timing.class.getMethods())
                    .filter(method -> method.getDeclaringClass()
                            .equals(ProcessingMetrics.Timing.class))
                    .toList())
                    .isEmpty();
            assertThat(Stream.of(ProcessingMetrics.Timing.class.getDeclaredConstructors())
                    .map(constructor -> constructor.canAccess(null))
                    .toList())
                    .as("and nobody outside this class mints one")
                    .containsOnly(false);
        }

        @Test
        void the_four_counters_carry_only_bounded_labels() {
            exerciseTheReportCounters();

            assertThat(tagKeysOf(ProcessingMetrics.EXCEPTION_REPORT_RUNS))
                    .containsExactly("outcome");
            assertThat(tagKeysOf(ProcessingMetrics.EXCEPTION_REPORT_DELIVERIES))
                    .containsExactlyInAnyOrder("sink", "outcome");
            assertThat(tagKeysOf(ProcessingMetrics.EXCEPTIONS_REPORTED)).containsExactly("kind");
            assertThat(tagKeysOf(ProcessingMetrics.INTAKE_SWEEP_FAILURES))
                    .as("the sweep's absorbed read failure, counted under a bounded reason — a "
                            + "path that drops something moves a counter")
                    .containsExactly("reason");

            assertThat(seriesOf(ProcessingMetrics.EXCEPTION_REPORT_RUNS))
                    .as("the same three words the run line carries")
                    .containsExactlyInAnyOrder("delivered", "partial", "failed");
            assertThat(seriesOf(ProcessingMetrics.EXCEPTIONS_REPORTED))
                    .as("every kind, because a seventh is a spec change rather than an addition "
                            + "- batch-released was the sixth, added by 004 against FR-019")
                    .containsExactlyInAnyOrder("request-failed", "request-late", "batch-late",
                            "batch-failed", "notification-failed", "batch-released");
            assertThat(seriesOf(ProcessingMetrics.EXCEPTION_REPORT_DELIVERIES))
                    .containsExactlyInAnyOrder("log", "email", "delivered", "partially-delivered",
                            "not-delivered");
        }

        @Test
        void the_exceptions_counter_moves_by_the_number_reported() {
            metrics.exceptionsReported(ExceptionKind.REQUEST_LATE, 3);
            metrics.exceptionsReported(ExceptionKind.REQUEST_LATE, 2);

            assertThat(counter(ProcessingMetrics.EXCEPTIONS_REPORTED, "kind", "request-late"))
                    .as("a report carrying five late requests is five, not two reports")
                    .isEqualTo(5);
        }

        @Test
        void the_run_outcome_label_is_one_of_three_bounded_words() {
            // Review gate 3. The service's design rules on bounded reasons and labels require
            // every metric label to be a bounded code, and the only enforcement this counter had was that its three callers
            // happened to spell the three words correctly. A mistyped label is not a wrong reading
            // - it is a brand new series, on which the alert written against the right one is
            // silent. Bounded by the compiler costs nothing and cannot be forgotten.
            for (final ReportRunOutcome outcome : ReportRunOutcome.values()) {
                metrics.exceptionReportRun(outcome);
            }

            assertThat(seriesOf(ProcessingMetrics.EXCEPTION_REPORT_RUNS))
                    .as("the same three words the run line carries, and no fourth a caller "
                            + "could invent")
                    .containsExactlyInAnyOrder("delivered", "partial", "failed");
            assertThat(soleParameterTypesOf("exceptionReportRun"))
                    .as("and no String-taking way in beside them, because an overload that "
                            + "accepts free text is the bound not being one")
                    .containsExactly(ReportRunOutcome.class);
        }

        @Test
        void the_sweep_failure_reason_label_is_one_of_two_bounded_codes() {
            // Two, and a third would mean a third thing was being absorbed. This counter is the
            // only evidence the sweep's absorbed refusal leaves, so a label nobody can mistype is
            // the difference between evidence and a series nobody queries.
            for (final SweepFailureReason reason : SweepFailureReason.values()) {
                metrics.intakeSweepFailure(reason);
            }

            assertThat(seriesOf(ProcessingMetrics.INTAKE_SWEEP_FAILURES))
                    .as("an outage of theirs and a bug of ours, told apart")
                    .containsExactlyInAnyOrder("store-unavailable", "unexpected");
            assertThat(soleParameterTypesOf("intakeSweepFailure"))
                    .containsExactly(SweepFailureReason.class);
        }

        @Test
        void a_non_terminal_status_is_refused_by_the_timer() {
            // RETRYING is an attempt the broker is going to make again, not an outcome. A sample
            // taken from it would make `yotresultsdistribution_request_duration` a histogram of attempts
            // under a fifth `outcome` value that nothing documents and no alert reads - and the
            // caller that did it would never find out, because a timer records in silence.
            final ProcessingMetrics.Timing timing = metrics.startRequestTiming();

            assertThatThrownBy(() -> metrics.requestSettled(timing, RequestStatus.RETRYING))
                    .as("refused at the instrument, which is the only place that can refuse it")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("RETRYING")
                    .hasMessageContaining("not a terminal state");
            assertThat(timer(ProcessingMetrics.REQUEST_DURATION, "outcome", "retrying"))
                    .as("and no series is left behind by the attempt to record it")
                    .isEqualTo(ABSENT);
        }

        @Test
        void no_identifier_is_ever_a_label() {
            metrics.oldestNonTerminalRequestAge(Duration.ofSeconds(90));
            metrics.nonTerminalRequestsOverThreshold(2);
            metrics.requestSettled(metrics.startRequestTiming(), RequestStatus.COMPLETED);
            exerciseTheReportCounters();

            assertThat(labelsOfTheNewInstruments())
                    .as("no request id, hearing id, court centre id or address — the report is "
                            + "about children, and a label outlives the incident it described")
                    .containsExactlyInAnyOrder("outcome", "sink", "kind", "reason");
            assertThat(valuesOfTheNewInstruments())
                    .allSatisfy(value -> assertThat(value).matches("[a-z][a-z-]*"));
        }

        /** Every one of the four report counters, over every value its label can take. */
        private void exerciseTheReportCounters() {
            for (final ReportRunOutcome outcome : ReportRunOutcome.values()) {
                metrics.exceptionReportRun(outcome);
            }
            for (final ReportSinkName sink : ReportSinkName.values()) {
                for (final DeliveryStatus outcome : DeliveryStatus.values()) {
                    metrics.exceptionReportDelivery(sink, outcome);
                }
            }
            for (final ExceptionKind kind : ExceptionKind.values()) {
                metrics.exceptionsReported(kind, 1);
            }
            metrics.intakeSweepFailure(SweepFailureReason.STORE_UNAVAILABLE);
        }

        /**
         * The parameter type of every single-argument method of one name.
         *
         * <p>Read by reflection because the claim is about the surface rather than about a call:
         * "there is one way in and it takes a bounded type" is not something a call site can
         * assert, and it is exactly what stops the next caller passing a string.
         *
         * @param method the method name
         * @return one entry per overload, in no particular order
         */
        private List<Class<?>> soleParameterTypesOf(final String method) {
            return Stream.of(ProcessingMetrics.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(method))
                    .filter(candidate -> candidate.getParameterCount() == 1)
                    .map(candidate -> candidate.getParameterTypes()[0])
                    .toList();
        }

        /** Every distinct label value one instrument's series carry. */
        private List<String> seriesOf(final String name) {
            return registry.find(name).meters().stream()
                    .flatMap(meter -> meter.getId().getTags().stream())
                    .map(Tag::getValue)
                    .distinct()
                    .toList();
        }

        private List<String> labelsOfTheNewInstruments() {
            return newInstruments().flatMap(meter -> meter.getId().getTags().stream())
                    .map(Tag::getKey)
                    .distinct()
                    .toList();
        }

        private List<String> valuesOfTheNewInstruments() {
            return newInstruments().flatMap(meter -> meter.getId().getTags().stream())
                    .map(Tag::getValue)
                    .distinct()
                    .toList();
        }

        private Stream<Meter> newInstruments() {
            final List<String> names = List.of(
                    ProcessingMetrics.OLDEST_NON_TERMINAL_REQUEST_AGE,
                    ProcessingMetrics.NON_TERMINAL_REQUESTS_OVER_THRESHOLD,
                    ProcessingMetrics.REQUEST_DURATION,
                    ProcessingMetrics.EXCEPTION_REPORT_RUNS,
                    ProcessingMetrics.EXCEPTION_REPORT_DELIVERIES,
                    ProcessingMetrics.EXCEPTIONS_REPORTED,
                    ProcessingMetrics.INTAKE_SWEEP_FAILURES);
            return registry.getMeters().stream()
                    .filter(meter -> names.contains(meter.getId().getName()));
        }
    }

    @Nested
    @DisplayName("the surface as a whole")
    class Surface {

        @Test
        void the_four_gauges_should_be_registered_before_anything_happens() {
            // Gauges are state, not events: a dashboard must be able to read them from a pod that
            // has not yet seen a message. The two intake ages join the two the intake half already
            // published, under the same rule and for the same reason.
            assertThat(registry.getMeters().stream().map(meter -> meter.getId().getName()).toList())
                    .containsExactlyInAnyOrder(
                            ProcessingMetrics.INTAKE_SUSPENDED,
                            ProcessingMetrics.SERVICEBUS_UP,
                            ProcessingMetrics.OLDEST_NON_TERMINAL_REQUEST_AGE,
                            ProcessingMetrics.NON_TERMINAL_REQUESTS_OVER_THRESHOLD);
        }

        @Test
        void exercising_everything_should_register_exactly_the_documented_instruments() {
            metrics.requestSettled(metrics.startRequestTiming(),
                    RequestStatus.COMPLETED);
            metrics.completed(CompletionReason.SUBMITTED);
            metrics.pipelineFailed(FailureClassification.TRANSIENT);
            metrics.transformationAnomaly(TransformationAnomaly.LETTER_DELIVERY_DROPPED);
            metrics.intakeSuspended();
            metrics.intakeSuspensionFailed();
            metrics.deadLettered(DeadLetterReason.VALIDATION);
            metrics.settlementFailed(SettlementOperation.ABANDON);
            metrics.lockLost();
            metrics.staleRunnerRejected();
            metrics.requestSettled(metrics.startRequestTiming(), RequestStatus.COMPLETED);
            metrics.exceptionReportRun(ReportRunOutcome.DELIVERED);
            metrics.exceptionReportDelivery(ReportSinkName.LOG, DeliveryStatus.DELIVERED);
            metrics.exceptionsReported(ExceptionKind.REQUEST_LATE, 1);
            metrics.intakeSweepFailure(SweepFailureReason.STORE_UNAVAILABLE);

            assertThat(registry.getMeters().stream()
                    .map(meter -> meter.getId().getName())
                    .distinct()
                    .toList())
                    .containsExactlyInAnyOrder(
                            ProcessingMetrics.PROCESSED,
                            ProcessingMetrics.COMPLETIONS,
                            ProcessingMetrics.PROCESSING_FAILURES,
                            ProcessingMetrics.TRANSFORMATION_ANOMALIES,
                            ProcessingMetrics.INTAKE_SUSPENSIONS,
                            ProcessingMetrics.INTAKE_SUSPENSION_FAILURES,
                            ProcessingMetrics.DEAD_LETTERED,
                            ProcessingMetrics.SETTLEMENT_FAILURES,
                            ProcessingMetrics.LOCK_LOSS,
                            ProcessingMetrics.STALE_RUNNER_REJECTIONS,
                            ProcessingMetrics.INTAKE_SUSPENDED,
                            ProcessingMetrics.SERVICEBUS_UP,
                            ProcessingMetrics.OLDEST_NON_TERMINAL_REQUEST_AGE,
                            ProcessingMetrics.NON_TERMINAL_REQUESTS_OVER_THRESHOLD,
                            ProcessingMetrics.REQUEST_DURATION,
                            ProcessingMetrics.EXCEPTION_REPORT_RUNS,
                            ProcessingMetrics.EXCEPTION_REPORT_DELIVERIES,
                            ProcessingMetrics.EXCEPTIONS_REPORTED,
                            ProcessingMetrics.INTAKE_SWEEP_FAILURES);
        }

        @Test
        void no_instrument_should_carry_an_identifying_label() {
            metrics.requestSettled(metrics.startRequestTiming(),
                    RequestStatus.COMPLETED);
            metrics.completed(CompletionReason.NO_YOUTH_DEFENDANTS);
            metrics.pipelineFailed(FailureClassification.TRANSIENT);
            metrics.transformationAnomaly(TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT);
            metrics.deadLettered(DeadLetterReason.COLLISION);
            metrics.settlementFailed(SettlementOperation.COMPLETE);
            metrics.exceptionReportDelivery(ReportSinkName.EMAIL, DeliveryStatus.NOT_DELIVERED);
            metrics.exceptionsReported(ExceptionKind.BATCH_FAILED, 1);

            assertThat(registry.getMeters().stream()
                    .flatMap(meter -> meter.getId().getTags().stream())
                    .map(Tag::getKey)
                    .distinct()
                    .toList())
                    .containsExactlyInAnyOrder("outcome", "classification", "reason", "operation",
                            "sink", "kind");
        }

        @Test
        void every_label_value_should_be_a_bounded_code() {
            // Bounded codes, and never a hearing id, a court centre, a defendant's name or an
            // exception message: the label set is a published surface and, on this flow, a
            // youth-defendant privacy boundary.
            metrics.completed(CompletionReason.NO_SUBSCRIPTIONS);
            metrics.transformationAnomaly(TransformationAnomaly.RECIPIENT_MISSING_EMAIL);

            assertThat(registry.getMeters().stream()
                    .flatMap(meter -> meter.getId().getTags().stream())
                    .map(Tag::getValue)
                    .distinct()
                    .toList())
                    .containsExactlyInAnyOrder("no-subscriptions", "recipient-missing-email");
        }

        /**
         * The surface as Prometheus actually reads it, rather than as Micrometer holds it.
         *
         * <p>Everything above asks the registry what it was given. A dashboard and an alert rule ask
         * the scrape endpoint, and between the two sits a naming convention: a counter's name is
         * rewritten, a tag becomes a label, and a series is a name and a label set together. Names
         * that agree in the registry can still arrive at Prometheus renamed, and the first anyone
         * would know is an alert that has quietly stopped firing.
         *
         * <p>So the scrape text itself is asserted, series by series. The two counters that carry a
         * {@code reason} are the ones worth spelling out: the completions, because on this flow
         * "nothing was sent" is the ordinary answer and telling them apart is defect C33's fix;
         * and the anomalies, because they are the telemetry half of C19, C20 and C27.
         */
        @Nested
        @DisplayName("the surface as Prometheus scrapes it")
        class PrometheusSurface {

            private final PrometheusMeterRegistry prometheus =
                    new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
            private final ProcessingMetrics scraped = new ProcessingMetrics(prometheus);

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
                        .map(label -> label.substring(0, label.indexOf('=')).trim())
                        .distinct()
                        .sorted()
                        .toList();
            }

            @Test
            @DisplayName("every completion reason is its own series, named as documented")
            void the_completions_counter_should_scrape_one_reason_series_per_completion() {
                for (final CompletionReason reason : CompletionReason.values()) {
                    scraped.completed(reason);
                }

                assertThat(samplesOf(ProcessingMetrics.COMPLETIONS))
                        .as("four of the six sent nothing, and two of those four are this flow's "
                                + "commonest results — an undifferentiated success is defect C33")
                        .containsExactlyInAnyOrder(
                                "yotresultsdistribution_completions_total{reason=\"recorded\"}",
                                "yotresultsdistribution_completions_total{reason=\"submitted\"}",
                                "yotresultsdistribution_completions_total{reason=\"group-proceedings\"}",
                                "yotresultsdistribution_completions_total{reason=\"no-defendants\"}",
                                "yotresultsdistribution_completions_total{reason=\"no-subscriptions\"}",
                                "yotresultsdistribution_completions_total{reason=\"no-youth-defendants\"}");
            }

            @Test
            @DisplayName("every guarded anomaly is its own series, named as documented")
            void the_anomaly_counter_should_scrape_one_reason_series_per_anomaly() {
                for (final TransformationAnomaly anomaly : TransformationAnomaly.values()) {
                    scraped.transformationAnomaly(anomaly);
                }

                assertThat(samplesOf(ProcessingMetrics.TRANSFORMATION_ANOMALIES))
                        .containsExactlyInAnyOrder(
                                "yotresultsdistribution_transformation_anomalies_total"
                                        + "{reason=\"unresolvable-youth-defendant\"}",
                                "yotresultsdistribution_transformation_anomalies_total"
                                        + "{reason=\"unresolvable-application\"}",
                                "yotresultsdistribution_transformation_anomalies_total"
                                        + "{reason=\"letter-delivery-dropped\"}",
                                "yotresultsdistribution_transformation_anomalies_total"
                                        + "{reason=\"recipient-missing-email\"}",
                                "yotresultsdistribution_transformation_anomalies_total"
                                        + "{reason=\"recipient-not-for-distribution\"}",
                                // The one anomaly that skips nothing: a hearing whose
                                // `isGroupProceedings` is not a boolean, which under the legacy's
                                // loose comparison would have suppressed the whole register (C7).
                                "yotresultsdistribution_transformation_anomalies_total"
                                        + "{reason=\"non-boolean-group-proceedings\"}");
            }

            @Test
            @DisplayName("no scraped series carries a label beyond the four bounded dimensions")
            void the_scrape_should_carry_no_identifying_label() {
                // The registry-level assertion above proves the tags this code sets. This one proves
                // what leaves the pod: a label added by a convention, a common tag or a registry
                // filter would appear here and nowhere else, and a metric label is a log line that
                // is kept for a year.
                for (final CompletionReason reason : CompletionReason.values()) {
                    scraped.completed(reason);
                }
                for (final TransformationAnomaly anomaly : TransformationAnomaly.values()) {
                    scraped.transformationAnomaly(anomaly);
                }
                scraped.requestSettled(scraped.startRequestTiming(),
                        RequestStatus.COMPLETED);
                scraped.pipelineFailed(FailureClassification.TRANSIENT);
                scraped.deadLettered(DeadLetterReason.COLLISION);
                scraped.settlementFailed(SettlementOperation.COMPLETE);
                scraped.lockLost();
                scraped.staleRunnerRejected();
                scraped.intakeSuspended();
                scraped.intakeSuspensionFailed();
                scraped.requestSettled(scraped.startRequestTiming(), RequestStatus.FAILED);
                scraped.exceptionReportRun(ReportRunOutcome.PARTIAL);
                scraped.exceptionReportDelivery(ReportSinkName.EMAIL,
                        DeliveryStatus.PARTIALLY_DELIVERED);
                scraped.exceptionsReported(ExceptionKind.NOTIFICATION_FAILED, 1);
                scraped.intakeSweepFailure(SweepFailureReason.STORE_UNAVAILABLE);

                assertThat(scrapedLabelKeys())
                        .containsExactly("classification", "kind", "operation", "outcome", "reason",
                                "sink");
            }

            @Test
            @DisplayName("the unlabelled instruments scrape as single, unlabelled series")
            void the_unlabelled_instruments_should_scrape_without_a_label_set() {
                scraped.lockLost();
                scraped.staleRunnerRejected();
                scraped.intakeSuspended();
                scraped.intakeSuspensionFailed();

                assertThat(samplesOf(ProcessingMetrics.LOCK_LOSS))
                        .containsExactly(ProcessingMetrics.LOCK_LOSS);
                assertThat(samplesOf(ProcessingMetrics.STALE_RUNNER_REJECTIONS))
                        .containsExactly(ProcessingMetrics.STALE_RUNNER_REJECTIONS);
                assertThat(samplesOf(ProcessingMetrics.INTAKE_SUSPENSIONS))
                        .containsExactly(ProcessingMetrics.INTAKE_SUSPENSIONS);
                assertThat(samplesOf(ProcessingMetrics.INTAKE_SUSPENSION_FAILURES))
                        .containsExactly(ProcessingMetrics.INTAKE_SUSPENSION_FAILURES);
            }

            @Test
            @DisplayName("all four gauges scrape from a pod that has seen nothing")
            void the_gauges_should_scrape_before_any_message_has_arrived() {
                assertThat(samplesOf(ProcessingMetrics.INTAKE_SUSPENDED))
                        .containsExactly(ProcessingMetrics.INTAKE_SUSPENDED);
                assertThat(samplesOf(ProcessingMetrics.SERVICEBUS_UP))
                        .containsExactly(ProcessingMetrics.SERVICEBUS_UP);
                assertThat(samplesOf(ProcessingMetrics.OLDEST_NON_TERMINAL_REQUEST_AGE))
                        .containsExactly(ProcessingMetrics.OLDEST_NON_TERMINAL_REQUEST_AGE);
                assertThat(samplesOf(ProcessingMetrics.NON_TERMINAL_REQUESTS_OVER_THRESHOLD))
                        .containsExactly(
                                ProcessingMetrics.NON_TERMINAL_REQUESTS_OVER_THRESHOLD);
            }
        }

        @Test
        void there_should_be_no_dead_letter_depth_gauge() {
            // Depth comes from the platform's own queue metric. Polling it here would cost a
            // receiver connection and race with support tooling draining the queue.
            assertThat(registry.find("yotresultsdistribution_deadletter_depth").gauge()).isNull();
        }
    }
}
