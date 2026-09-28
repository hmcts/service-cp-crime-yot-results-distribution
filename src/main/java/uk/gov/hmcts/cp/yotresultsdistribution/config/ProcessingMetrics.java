package uk.gov.hmcts.cp.yotresultsdistribution.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletionReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeadLetterReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionKind;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportRunOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportSinkName;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.SettlementOperation;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.SweepFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationAnomaly;

/**
 * The service's whole instrument surface, declared in one place.
 *
 * <p>Names and label sets are fixed here so the tests assert on them and the alert rules written
 * later have a stable surface to fire on. Labels are low-cardinality enumerations only: a request
 * id, hearing id, message id, court-centre id or exception message must never be a label value —
 * that is both a cardinality explosion and, on a register whose every defendant is a youth, a
 * privacy breach. Correlation identifiers live in the structured logs instead.
 *
 * <p>Two of the instruments answer questions this flow asks and the informant service did not. The
 * completions counter separates the five ways a run can end well, because a high
 * completed-but-not-submitted rate is normal here — two of the four no-op reasons are the commonest
 * results the service has — and a single undifferentiated success is the legacy defect C33. The
 * anomaly counter records a register that was produced with a part skipped (fixes C19, C20 and C27),
 * which is deliberately not a failure: {@code TRANSFORMATION_FAILED} is reserved for a
 * transformation that cannot produce a document at all.
 *
 * <p>Increment 003 adds the four instruments the design promised and 001 never built. Two are
 * gauges about work that has not finished - the age of the oldest unfinished request, and how many
 * of them are over the intake threshold - refreshed on their own fixed delay by
 * {@code batch.IntakeAgeSweep} in every JVM and under no lock, so a pod publishes its own reading
 * and an alert aggregates across pods with {@code max()}. The third is the request-duration timer,
 * tagged by the terminal outcome and by nothing else. The fourth is the report's own three
 * counters, beside which sits {@code yotresultsdistribution_intake_sweep_failures_total}: the sweep's read
 * failure is the one refusal this service absorbs, and this counter is what makes the absorption
 * visible.
 *
 * <p>Dead-letter <em>depth</em> is deliberately absent: it is read from Azure Monitor's native queue
 * metric. This service counts the dead-letters it performs, which is a different question.
 */
@Component
public class ProcessingMetrics {

    public static final String PROCESSED = "yotresultsdistribution_processed_total";
    public static final String COMPLETIONS = "yotresultsdistribution_completions_total";
    public static final String PROCESSING_FAILURES = "yotresultsdistribution_processing_failures_total";
    public static final String TRANSFORMATION_ANOMALIES =
            "yotresultsdistribution_transformation_anomalies_total";
    public static final String INTAKE_SUSPENSIONS = "yotresultsdistribution_intake_suspensions_total";
    public static final String INTAKE_SUSPENSION_FAILURES =
            "yotresultsdistribution_intake_suspension_failures_total";
    public static final String DEAD_LETTERED = "yotresultsdistribution_deadlettered_total";
    public static final String SETTLEMENT_FAILURES = "yotresultsdistribution_settlement_failures_total";
    public static final String LOCK_LOSS = "yotresultsdistribution_lock_loss_total";
    public static final String STALE_RUNNER_REJECTIONS =
            "yotresultsdistribution_stale_runner_rejections_total";
    public static final String INTAKE_SUSPENDED = "yotresultsdistribution_intake_suspended";
    public static final String SERVICEBUS_UP = "yotresultsdistribution_servicebus_up";
    public static final String OLDEST_NON_TERMINAL_REQUEST_AGE =
            "yotresultsdistribution_oldest_non_terminal_request_age";
    public static final String NON_TERMINAL_REQUESTS_OVER_THRESHOLD =
            "yotresultsdistribution_non_terminal_requests_over_threshold";
    public static final String REQUEST_DURATION = "yotresultsdistribution_request_duration";
    public static final String EXCEPTION_REPORT_RUNS = "yotresultsdistribution_exception_report_runs_total";
    public static final String EXCEPTION_REPORT_DELIVERIES =
            "yotresultsdistribution_exception_report_deliveries_total";
    public static final String EXCEPTIONS_REPORTED = "yotresultsdistribution_exceptions_reported_total";
    public static final String INTAKE_SWEEP_FAILURES =
            "yotresultsdistribution_intake_sweep_failures_total";

    public static final String OUTCOME_TAG = "outcome";
    public static final String CLASSIFICATION_TAG = "classification";
    public static final String REASON_TAG = "reason";
    public static final String OPERATION_TAG = "operation";
    public static final String SINK_TAG = "sink";
    public static final String KIND_TAG = "kind";

    private static final int UP = 1;
    private static final int DOWN = 0;

    private final MeterRegistry registry;

    /**
     * Gauge state. Held here rather than read from a collaborator so the gauges exist from
     * construction: a dashboard must be able to read them from a pod that has not yet seen a
     * message, and a gauge that only appears after the first incident is not an alerting surface.
     */
    private final AtomicInteger intakeSuspendedState = new AtomicInteger(DOWN);

    /**
     * The two readings the intake sweep publishes, held for the same reason and refreshed on their
     * own fixed delay in every JVM. Nothing locks the sweep, so each pod's pair describes the pod
     * that published it and an alert aggregates them across pods with {@code max()} - the oldest
     * unfinished request is the oldest any pod can see.
     */
    private final AtomicLong oldestNonTerminalSeconds = new AtomicLong();
    private final AtomicInteger nonTerminalOverThreshold = new AtomicInteger();

    /**
     * How the Service Bus gauge answers, at the moment it is asked.
     *
     * <p>A supplier rather than a remembered number, because the state it reports is partly a
     * function of time: an error goes stale, and a consumer that has never been answered stops being
     * given the benefit of the doubt. A value written at the last state change would be whatever it
     * was when something last happened, which for exactly those two transitions is the wrong answer
     * for as long as nothing happens. Up until something says otherwise, which is the honest
     * starting position for a healthy pod.
     */
    private final AtomicReference<BooleanSupplier> serviceBusState =
            new AtomicReference<>(() -> true);

    /**
     * Registers the service's gauges against the given registry.
     *
     * @param registry the registry every instrument is registered against
     */
    public ProcessingMetrics(final MeterRegistry registry) {
        this.registry = registry;

        Gauge.builder(INTAKE_SUSPENDED, intakeSuspendedState, AtomicInteger::get)
                .description("1 while intake is suspended, 0 while it is running")
                .register(registry);
        Gauge.builder(SERVICEBUS_UP, serviceBusState,
                        state -> state.get().getAsBoolean() ? UP : DOWN)
                .description("1 while the Service Bus health component is up, 0 while it is down")
                .register(registry);
        Gauge.builder(OLDEST_NON_TERMINAL_REQUEST_AGE, oldestNonTerminalSeconds,
                        AtomicLong::doubleValue)
                .description("Age in seconds of the oldest request that has not finished")
                .register(registry);
        Gauge.builder(NON_TERMINAL_REQUESTS_OVER_THRESHOLD, nonTerminalOverThreshold,
                        AtomicInteger::doubleValue)
                .description("Requests unfinished for longer than the intake threshold")
                .register(registry);
    }

    /**
     * A request reached a terminal state: counts it and records how long it took to get there.
     *
     * <p><strong>One settlement is one recording.</strong> The count and the duration were two
     * calls, made side by side at every settlement in {@code DistributionPipeline}, and a pair like
     * that can drift: a path that remembered one and forgot the other publishes a count with no
     * duration, or a duration with no count, and the two series an operator reads together stop
     * agreeing about how many requests finished. The terminal state is the same fact both of them
     * are about, so it is taken once and answers both - {@code RequestOutcome.reached} is where the two
     * vocabularies meet, and it is written once.
     *
     * <p>Refuses a state that is not terminal. A sample taken from {@code RECEIVED} or
     * {@code RETRYING} would time an attempt rather than a run - the broker is going to deliver the
     * message again - and would publish it under an {@code outcome} value nothing documents and no
     * alert reads. A timer records in silence, so the caller that did it would never find out;
     * refusing here is the only place that can tell them. The refusal comes before either
     * instrument moves, so a refused call leaves no half-recorded settlement behind.
     *
     * @param timing  the token {@link #startRequestTiming()} answered
     * @param outcome the terminal state the run reached
     * @throws IllegalArgumentException where the run has not reached a terminal state
     */
    public void requestSettled(final Timing timing, final RequestStatus outcome) {
        if (!outcome.isTerminal()) {
            throw new IllegalArgumentException(outcome
                    + " is not a terminal state, so a duration sample taken here would time an "
                    + "attempt rather than a run");
        }
        counter(PROCESSED, OUTCOME_TAG, RequestOutcome.reached(outcome).label()).increment();
        timing.sample.stop(Timer.builder(REQUEST_DURATION)
                .description("Time from the guard admitting a run to the terminal state it reached")
                .tag(OUTCOME_TAG, code(outcome))
                .register(registry));
    }

    /**
     * Starts timing one admitted run.
     *
     * @return the token to hand back when the run reaches a terminal state
     */
    public Timing startRequestTiming() {
        return new Timing(Timer.start(registry));
    }

    /**
     * A request completed, for one of the five reasons a run can end well.
     *
     * <p>Counted separately from {@link #requestSettled}, which answers "did it finish"; this
     * answers "did a register go out, and if not, which of the four business skips ended it".
     *
     * @param reason why the request completed
     */
    public void completed(final CompletionReason reason) {
        counter(COMPLETIONS, REASON_TAG, reason.value()).increment();
    }

    /**
     * A pipeline run failed — every failed run, including a transient one that ends in RETRYING, not
     * only terminal exhaustion. A request retrying quietly forever is exactly what this service
     * exists to make visible.
     *
     * @param classification whether the failure is worth retrying
     */
    public void pipelineFailed(final FailureClassification classification) {
        counter(PROCESSING_FAILURES, CLASSIFICATION_TAG, classification.label()).increment();
    }

    /**
     * A guarded, non-fatal transformation anomaly was met and skipped — once per occurrence, so the
     * counter matches the bounded count written to {@code processed_output.anomaly_summary}.
     *
     * @param anomaly which part of the register could not be built
     */
    public void transformationAnomaly(final TransformationAnomaly anomaly) {
        counter(TRANSFORMATION_ANOMALIES, REASON_TAG, anomaly.value()).increment();
    }

    /**
     * Intake moved into SUSPENDED.
     */
    public void intakeSuspended() {
        intakeSuspendedState.set(UP);
        counter(INTAKE_SUSPENSIONS).increment();
    }

    /**
     * Intake was asked to stop and the processor refused, so the pod is still consuming with no
     * store to record what it does.
     *
     * <p>Counted separately from {@link #intakeSuspended()}, and it is the more urgent of the two.
     * A suspension that happened is the design working; a suspension that could not be carried out
     * is a pod spending the broker's delivery budget on an outage of ours, and it is invisible
     * otherwise — the suspension counter cannot move, and the gauge reads the same as a healthy pod.
     * Counted per attempt rather than per outage, because a rising series is what says the retries
     * are not getting anywhere.
     */
    public void intakeSuspensionFailed() {
        counter(INTAKE_SUSPENSION_FAILURES).increment();
    }

    /**
     * Intake moved back into RUNNING. Deliberately not counted: the counter records incidents, and
     * recovering from one is not a second incident.
     */
    public void intakeResumed() {
        intakeSuspendedState.set(DOWN);
    }

    /**
     * A delivery was parked on the dead-letter queue.
     *
     * @param reason why it was parked
     */
    public void deadLettered(final DeadLetterReason reason) {
        counter(DEAD_LETTERED, REASON_TAG, reason.label()).increment();
    }

    /**
     * A settlement call itself failed.
     *
     * @param operation which settlement call failed
     */
    public void settlementFailed(final SettlementOperation operation) {
        counter(SETTLEMENT_FAILURES, OPERATION_TAG, operation.label()).increment();
    }

    /**
     * The delivery lock was lost before settlement.
     */
    public void lockLost() {
        counter(LOCK_LOSS).increment();
    }

    /**
     * An outcome write was rejected by the owner-and-token predicate.
     */
    public void staleRunnerRejected() {
        counter(STALE_RUNNER_REJECTIONS).increment();
    }

    /**
     * Mirrors the Service Bus health component.
     *
     * @param up whether the broker is reachable
     */
    public void serviceBusUp(final boolean up) {
        serviceBusState.set(() -> up);
    }

    /**
     * Points the Service Bus gauge at the component that knows the answer.
     *
     * <p>So that a scrape and a health check read the same live state rather than the same
     * remembered one, whichever of them happens first and whether or not the other ever happens at
     * all. A Prometheus scrape does not call the health endpoint on its way past.
     *
     * @param liveState answers, on demand, whether the broker is reachable
     */
    public void bindServiceBusUp(final BooleanSupplier liveState) {
        serviceBusState.set(liveState);
    }

    /**
     * Reports how old the oldest request that has not reached a terminal state is.
     *
     * @param age the age of the oldest RECEIVED or RETRYING request, or {@link Duration#ZERO}
     *            where nothing is unfinished
     */
    public void oldestNonTerminalRequestAge(final Duration age) {
        oldestNonTerminalSeconds.set(age.toSeconds());
    }

    /**
     * Reports how many requests have been unfinished for longer than the intake threshold.
     *
     * @param count how many requests are over it, or zero where none is
     */
    public void nonTerminalRequestsOverThreshold(final int count) {
        nonTerminalOverThreshold.set(count);
    }

    /**
     * Counts one exception-report run under the bounded outcome its run line carries.
     *
     * <p>The parameter is an enumeration rather than the word itself, which is the whole of review
     * gate 3's finding: a label a caller spells is a label a caller can mistype, and a mistyped
     * label is not a wrong reading but a new series, on which the alert written against the right
     * one is silent for ever. The three constants render to the three words the run line carries,
     * through the same {@link #code(Enum)} every other bounded label here goes through.
     *
     * @param outcome how the run as a whole went
     */
    public void exceptionReportRun(final ReportRunOutcome outcome) {
        counter(EXCEPTION_REPORT_RUNS, OUTCOME_TAG, code(outcome)).increment();
    }

    /**
     * Counts one sink's delivery of one report.
     *
     * @param sink    which sink delivered it
     * @param outcome how completely it was delivered
     */
    public void exceptionReportDelivery(final ReportSinkName sink, final DeliveryStatus outcome) {
        Counter.builder(EXCEPTION_REPORT_DELIVERIES)
                .tag(SINK_TAG, code(sink))
                .tag(OUTCOME_TAG, code(outcome))
                .register(registry)
                .increment();
    }

    /**
     * Counts the exceptions one report carried, by kind.
     *
     * @param kind  which of the five things was wrong
     * @param count how many of them the report carried
     */
    public void exceptionsReported(final ExceptionKind kind, final int count) {
        counter(EXCEPTIONS_REPORTED, KIND_TAG, code(kind)).increment(count);
    }

    /**
     * Counts a gauge refresh the intake sweep could not take, under a bounded reason.
     *
     * <p>Enumerated for the reason {@link #exceptionReportRun(ReportRunOutcome)} is, and with more
     * riding on it: this counter is the only evidence the service's one absorbed refusal leaves
     * behind, and evidence published under a label nobody queries is no evidence at all. Two codes,
     * because an outage of theirs and a bug of ours need telling apart - one series moves during
     * somebody else's incident and stops when it ends, the other should be flat at zero for ever.
     *
     * @param reason what stopped it, never a message
     */
    public void intakeSweepFailure(final SweepFailureReason reason) {
        counter(INTAKE_SWEEP_FAILURES, REASON_TAG, code(reason)).increment();
    }

    /**
     * How long a run has been going, as the one thing the application layer is handed.
     *
     * <p>Opaque on purpose. The timing underneath is a Micrometer sample, and a sample handed to
     * {@code DistributionPipeline} would put the metrics library in the application layer for the
     * sake of two lines (constitution Principle V, the same containment that keeps
     * {@code StructuredArguments} inside one adapter). So the pipeline holds a token it can do
     * nothing with except give back, and this class does the arithmetic.
     */
    public static final class Timing {

        private final Timer.Sample sample;

        private Timing(final Timer.Sample sample) {
            this.sample = sample;
        }
    }

    /**
     * The bounded label value an enumerated state is published under.
     *
     * @param state the enumerated state being labelled
     * @return the label value for that state
     */
    private static String code(final Enum<?> state) {
        return state.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private Counter counter(final String name) {
        return Counter.builder(name).register(registry);
    }

    private Counter counter(final String name, final String tag, final String value) {
        return Counter.builder(name).tag(tag, value).register(registry);
    }
}
