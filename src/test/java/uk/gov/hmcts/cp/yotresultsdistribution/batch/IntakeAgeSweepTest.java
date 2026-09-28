package uk.gov.hmcts.cp.yotresultsdistribution.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;
import uk.gov.hmcts.cp.yotresultsdistribution.config.IntakeSweepConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.config.ProcessingMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ProcessedRequestSummary;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.SweepFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.ProcessedRequestRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.support.AdjustableClock;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;

/**
 * The two readings an alert fires on, and what the sweep does when it cannot take one.
 *
 * <p>Design section 11 promised both gauges and 001 built neither, so until now the only thing that
 * noticed a request stuck RECEIVED for three days was somebody looking. They are refreshed on a
 * fixed delay of their own, in <strong>every</strong> JVM and under <strong>no</strong> lock: a
 * gauge describes the pod that publishes it, so a locked sweep would have one replica reading the
 * store while the others published whatever they last saw - one pod's view under two pod labels,
 * and an alert that is a coin toss. Unlocked, each replica refreshes its own pair and an alert
 * aggregates them with {@code max()}, which is the honest reading: the oldest unfinished request is
 * the oldest any pod can see.
 *
 * <p>A read it cannot take is <strong>the one refusal this service absorbs</strong>, and it is
 * absorbed because the service's design rules on absorbed refusals say so: <em>"The one absorbed
 * refusal is
 * telemetry: a round-trip reading that cannot be taken may not cost a Youth Offending Team its
 * e-mail, so it stops where it happens, is counted, and is said at WARN."</em> All three halves are
 * asserted here, plus the fourth thing that makes it an absorption rather than a swallow: the
 * schedule survives it. A fixed-delay schedule cancels the task that throws, so a sweep that let a
 * store blip out would stop refreshing the gauges for the life of the pod - the two readings would
 * sit frozen at their last value with nothing saying they had stopped moving, which is the silent
 * failure this service exists to cure, reached through the instrument that was supposed to reveal
 * it.
 */
@DisplayName("IntakeAgeSweep")
class IntakeAgeSweepTest {

    /** Returned when a meter is absent, so a missing instrument fails as an assertion. */
    private static final double ABSENT = -1;

    /** The resolved intake threshold, as `yotresultsdistribution.report.request-terminal-within` binds it. */
    private static final Duration THRESHOLD = Duration.ofMinutes(30);

    private static final Instant NOW = Instant.parse("2026-09-15T09:00:00Z");

    /** Scenario 2.2's unfinished request: forty minutes old against a thirty-minute threshold. */
    private static final long FORTY_MINUTES = 2400;

    /**
     * The message the store's own refusal carries, asserted <em>absent</em> from the log.
     *
     * <p>Both halves matter and review gate 3's QA pass found only one of them asserted: a caught
     * exception's message belongs to whatever raised it, so neither the cause's text nor the
     * wrapper's own may be repeated - a connection string turns up in either.
     */
    private static final String STORE_REFUSAL =
            "the store could not be reached to read the oldest unfinished request";

    private static final String STORE_CAUSE = "connection refused to cp-nle-01.postgres:5432";

    /** A bug of ours rather than an outage of theirs, and it names a request in passing. */
    private static final String UNEXPECTED_REFUSAL =
            "claim_owner was null for RESULTS:9b1f2c74-0f1e-4f4e-9d6b-3a0f5f9a1c22";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProcessingMetrics metrics = new ProcessingMetrics(registry);
    private final ProcessedRequestRepository requests = mock(ProcessedRequestRepository.class);
    private final AdjustableClock clock = AdjustableClock.startingAt(NOW);

    private final IntakeAgeSweep sweep =
            new IntakeAgeSweep(requests, metrics, THRESHOLD, clock);

    @Test
    void both_gauges_move_from_the_repositorys_answers() {
        // Spec scenario 2.2 as it is written: one unfinished request forty minutes old against a
        // thirty-minute threshold. Review gate 3's QA pass found the old fixture contradicting
        // itself - it answered a 900-second row as the oldest while a 2400-second row was in the
        // same store, and then counted the 900-second one as over a 1800-second threshold - so it
        // could have passed against a sweep that had the two readings the wrong way round.
        stillRunning(unfinishedFor(FORTY_MINUTES), 1);

        sweep.sweepScheduled();

        assertThat(gauge(ProcessingMetrics.OLDEST_NON_TERMINAL_REQUEST_AGE))
                .as("the age the database computed, not one this JVM worked out from a stored "
                        + "timestamp")
                .isGreaterThanOrEqualTo(FORTY_MINUTES);
        assertThat(gauge(ProcessingMetrics.NON_TERMINAL_REQUESTS_OVER_THRESHOLD)).isEqualTo(1);
        verify(requests).countNonTerminalOlderThan(NOW.minus(THRESHOLD));
        verify(requests, never()).nonTerminalOlderThan(any(Instant.class));
    }

    @Test
    void the_over_threshold_gauge_is_set_from_the_count_read_not_from_a_materialised_list() {
        // The number is the whole reading. Sizing a list to get it makes the sweep's cost grow
        // with the backlog it is reporting - and it is the backlog that makes the reading
        // interesting, so the read is slowest on exactly the morning it matters most. Every row
        // materialised here is also a row of somebody's case carried into a JVM to be counted and
        // dropped, which on this register is a youth's.
        stillRunning(unfinishedFor(FORTY_MINUTES), 4);

        sweep.sweepScheduled();

        assertThat(gauge(ProcessingMetrics.NON_TERMINAL_REQUESTS_OVER_THRESHOLD))
                .as("the number the store answered with, published as it stands")
                .isEqualTo(4);
        verify(requests, never()).nonTerminalOlderThan(any(Instant.class));
    }

    @Test
    void the_cut_off_is_the_threshold_ago_exactly_and_is_not_nudged_either_way() {
        // The read behind it is `created_at < :cutOff`, so a request created exactly the threshold
        // ago is not over it. That boundary is shared with the report's REQUEST_LATE read, which
        // computes its cut-off the same way: a tolerance added here to make the boundary friendlier
        // would make the gauge and the morning report disagree about the same request.
        stillRunning(unfinishedFor(FORTY_MINUTES), 1);

        sweep.sweepScheduled();

        final ArgumentCaptor<Instant> cutOff = ArgumentCaptor.forClass(Instant.class);
        verify(requests).countNonTerminalOlderThan(cutOff.capture());
        assertThat(cutOff.getValue())
                .as("now less the threshold, to the instant, from the clock the sweep was given")
                .isEqualTo(NOW.minus(THRESHOLD));
    }

    @Test
    void both_gauges_return_to_zero_when_nothing_is_unfinished() {
        stillRunning(unfinishedFor(FORTY_MINUTES), 1);
        sweep.sweepScheduled();

        nothingRunning();
        sweep.sweepScheduled();

        assertThat(gauge(ProcessingMetrics.OLDEST_NON_TERMINAL_REQUEST_AGE))
                .as("a reading that only ever moved up would need an incident before it could fall")
                .isZero();
        assertThat(gauge(ProcessingMetrics.NON_TERMINAL_REQUESTS_OVER_THRESHOLD)).isZero();
    }

    @Test
    void a_failed_read_leaves_the_gauges_at_their_last_reading_counted_and_does_not_cancel_the_schedule() {
        stillRunning(unfinishedFor(FORTY_MINUTES), 1);
        sweep.sweepScheduled();

        when(requests.oldestNonTerminal()).thenThrow(
                new StoreUnavailableException(STORE_REFUSAL, new IllegalStateException(STORE_CAUSE)));

        try (CapturedLog log = CapturedLog.capturing(IntakeAgeSweep.class)) {
            assertThatCode(sweep::sweepScheduled)
                    .as("a fixed-delay schedule cancels the task that throws, and a sweep that "
                            + "stopped refreshing would leave the two readings frozen with nothing "
                            + "saying they had stopped moving")
                    .doesNotThrowAnyException();

            assertThat(gauge(ProcessingMetrics.OLDEST_NON_TERMINAL_REQUEST_AGE))
                    .as("the last reading, not a zero the store never said")
                    .isGreaterThanOrEqualTo(FORTY_MINUTES);
            assertThat(gauge(ProcessingMetrics.NON_TERMINAL_REQUESTS_OVER_THRESHOLD)).isEqualTo(1);
            assertThat(counter(ProcessingMetrics.INTAKE_SWEEP_FAILURES,
                    SweepFailureReason.STORE_UNAVAILABLE))
                    .as("a path that drops something moves a counter - it is what makes the "
                            + "absorption visible at all")
                    .isEqualTo(1);
            assertThat(counter(ProcessingMetrics.INTAKE_SWEEP_FAILURES,
                    SweepFailureReason.UNEXPECTED))
                    .as("an outage of theirs is not counted as a bug of ours")
                    .isEqualTo(ABSENT);

            assertTheOneWarningNames(log, "StoreUnavailableException");
        }
    }

    @Test
    void a_read_that_fails_for_any_other_reason_is_counted_unexpected() {
        // The second catch is total on purpose - this method may not throw - and a total catch is
        // exactly the shape that hides a bug of ours inside an outage of theirs. It does not,
        // because the two are counted apart: `store-unavailable` is a series that moves during
        // somebody else's incident and stops when it ends, and `unexpected` is one that should
        // never move at all. One counter for both would make the second invisible inside the first.
        stillRunning(unfinishedFor(FORTY_MINUTES), 1);
        sweep.sweepScheduled();

        when(requests.oldestNonTerminal())
                .thenThrow(new IllegalStateException(UNEXPECTED_REFUSAL));

        try (CapturedLog log = CapturedLog.capturing(IntakeAgeSweep.class)) {
            assertThatCode(sweep::sweepScheduled)
                    .as("a bug of ours must not take the two readings off the air for the life "
                            + "of the pod either")
                    .doesNotThrowAnyException();

            assertThat(gauge(ProcessingMetrics.OLDEST_NON_TERMINAL_REQUEST_AGE))
                    .as("the last reading is kept whichever way the read refused")
                    .isGreaterThanOrEqualTo(FORTY_MINUTES);
            assertThat(gauge(ProcessingMetrics.NON_TERMINAL_REQUESTS_OVER_THRESHOLD)).isEqualTo(1);
            assertThat(counter(ProcessingMetrics.INTAKE_SWEEP_FAILURES,
                    SweepFailureReason.UNEXPECTED))
                    .as("under its own reason, so an alert can be written on a series that is "
                            + "supposed to be flat at zero for ever")
                    .isEqualTo(1);
            assertThat(counter(ProcessingMetrics.INTAKE_SWEEP_FAILURES,
                    SweepFailureReason.STORE_UNAVAILABLE))
                    .as("and not hidden inside the reason that moves during every outage")
                    .isEqualTo(ABSENT);

            assertTheOneWarningNames(log, "IllegalStateException");
        }
    }

    @Test
    void the_sweep_opens_its_own_run_id_and_removes_it() {
        final AtomicReference<String> seen = new AtomicReference<>();
        when(requests.oldestNonTerminal()).thenAnswer(call -> {
            seen.set(RunCorrelation.current());
            return Optional.empty();
        });
        when(requests.countNonTerminalOlderThan(any(Instant.class))).thenReturn(0L);

        sweep.sweepScheduled();

        assertThat(seen.get())
                .as("the scheduler's threads are pooled, so a sweep needs a correlation of its own "
                        + "in place of the two a delivery has")
                .isNotNull();
        assertThat(RunCorrelation.current())
                .as("and an id left behind would be inherited by the next run on that thread, "
                        + "which reads as a true correlation and is worse than none")
                .isNull();
    }

    @Test
    void the_sweep_is_scheduled_and_deliberately_unlocked() throws NoSuchMethodException {
        final Method scheduled = IntakeAgeSweep.class.getDeclaredMethod("sweepScheduled");
        final Scheduled schedule = scheduled.getAnnotation(Scheduled.class);

        assertThat(scheduled.getReturnType())
                .as("a schedule has nobody to return a reading to")
                .isEqualTo(void.class);
        assertThat(schedule)
                .as("the gauges are refreshed on a cadence of their own, not on the nightly run's")
                .isNotNull();
        assertThat(schedule == null ? null : schedule.fixedDelayString())
                .as("the intake half's own setting, readable on a pod that binds neither the "
                        + "report's record nor the generation half's")
                .isEqualTo("${yotresultsdistribution.intake.gauge-refresh}");
        assertThat(scheduled.getAnnotation(SchedulerLock.class))
                .as("no lock, on purpose: a gauge describes the JVM that publishes it, so a locked "
                        + "sweep would show one pod's view under every pod's labels")
                .isNull();
    }

    @Test
    void the_intake_sweep_names_its_own_scheduler() throws NoSuchMethodException {
        final Scheduled schedule = IntakeAgeSweep.class.getDeclaredMethod("sweepScheduled")
                .getAnnotation(Scheduled.class);

        assertThat(schedule == null ? null : schedule.scheduler())
                .as("its own thread and not the run's: a refresh queued behind an 18:00 run that "
                        + "overran would leave both readings frozen for the hour they matter "
                        + "most, and three TaskScheduler beans route nothing unless the method "
                        + "names one (SC-008)")
                .isEqualTo(IntakeSweepConfig.INTAKE_SWEEP_SCHEDULER);
    }

    /**
     * One WARN line, naming the caught failure by class and repeating none of its words.
     *
     * <p>Shared by the two absorbed-refusal cases because the claim is identical for both and it
     * is the claim the service's design rules on logging make: <em>"Never attach a throwable this
     * service did not write. A caught exception is named by class; its message belongs to whatever
     * library raised it and is exactly where a connection string or a fragment of a statement turns
     * up."</em> Review gate 3's QA pass found the cause's text asserted absent and the caught
     * exception's own text not - and the wrapper's message is the one that names the statement.
     *
     * @param log  the captured logger
     * @param type the simple name of the class the line must name
     */
    private void assertTheOneWarningNames(final CapturedLog log, final String type) {
        final List<ILoggingEvent> warnings = log.events().stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .toList();

        assertThat(warnings)
                .as("said once - a refusal repeated per reading is an incident that looks twice "
                        + "as bad as it is")
                .hasSize(1);
        assertThat(warnings.getFirst().getFormattedMessage())
                .as("named by class; neither the caught exception's own message nor its cause's "
                        + "reaches the log, and both are where a connection string turns up")
                .contains(type)
                .doesNotContain(STORE_REFUSAL)
                .doesNotContain(STORE_CAUSE)
                .doesNotContain(UNEXPECTED_REFUSAL)
                .doesNotContain("cp-nle-01");
        assertThat(warnings.getFirst().getThrowableProxy())
                .as("and no throwable this service did not write is attached")
                .isNull();
    }

    /**
     * What the two reads answer for a store holding the given unfinished work.
     *
     * @param oldest        the oldest unfinished request, or null where nothing is unfinished
     * @param overThreshold how many the count read answers with
     */
    private void stillRunning(final ProcessedRequestSummary oldest, final long overThreshold) {
        when(requests.oldestNonTerminal()).thenReturn(Optional.ofNullable(oldest));
        when(requests.countNonTerminalOlderThan(any(Instant.class))).thenReturn(overThreshold);
    }

    /** A store with nothing in flight, which is the ordinary answer a healthy service gives. */
    private void nothingRunning() {
        stillRunning(null, 0);
    }

    /**
     * One request still in flight, aged by the database rather than by this JVM.
     *
     * @param ageSeconds how old the read said it was
     * @return the projection the read answers with
     */
    private ProcessedRequestSummary unfinishedFor(final long ageSeconds) {
        return new ProcessedRequestSummary(
                "RESULTS",
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocalDate.parse("2026-09-15"),
                RequestStatus.RETRYING,
                3,
                null,
                NOW.minusSeconds(ageSeconds),
                NOW.minusSeconds(ageSeconds),
                ageSeconds);
    }

    private double gauge(final String name) {
        final Gauge found = registry.find(name).gauge();
        return found == null ? ABSENT : found.value();
    }

    private double counter(final String name, final SweepFailureReason reason) {
        final Counter found = registry.find(name)
                .tag(ProcessingMetrics.REASON_TAG,
                        reason.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                .counter();
        return found == null ? ABSENT : found.count();
    }
}
