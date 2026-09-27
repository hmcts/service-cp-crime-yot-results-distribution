package uk.gov.hmcts.cp.yotresultsdistribution.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.scheduling.annotation.Scheduled;
import uk.gov.hmcts.cp.yotresultsdistribution.config.BatchSweepConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.SweepFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.support.AdjustableClock;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;

/**
 * The three readings an alert fires on between runs, and what the sweep does when it cannot take
 * one.
 *
 * <p>They were taken by the grace-period pass 004 deletes, on its way past the batches it was about
 * to ask systemdocgenerator about. **A Micrometer gauge never decays**: a gauge whose publisher goes
 * away does not fall to zero, it holds the last value it was given for ever - so a removal that
 * took them with it would leave three readings frozen at whatever the last reconciliation saw and
 * still looking live. This sweep is the one addition in an otherwise subtractive increment, and it
 * exists only so that the removal does not cost three continuous measurements (FR-011).
 *
 * <p>Under <strong>no</strong> lock and in every generating JVM, for the reason {@link
 * IntakeAgeSweep} is: a gauge describes the pod that publishes it, so a locked sweep would show one
 * replica's view under every replica's labels and an alert would be a coin toss. Unlocked, each pod
 * refreshes its own three and an alert aggregates them with {@code max()}.
 *
 * <p>It settles <strong>nothing</strong>, and the third reading is where that matters. A batch
 * parked at GENERATED holds a document somebody is owed e-mails about; failing it would throw the
 * document away, so it is named at WARN and left exactly as it was found - the resend surfaces are
 * what owe it, and both of them are re-entrant.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("BatchAgeSweep")
class BatchAgeSweepTest {

    /** Just after an 18:00 run, which is when the readings are worth taking. */
    private static final Instant NOW = Instant.parse("2026-09-21T18:40:00Z");

    /** The oldest GENERATING batch: its render was asked for ninety minutes ago. */
    private static final Duration GENERATING_AGE = Duration.ofMinutes(90);

    /** The oldest PENDING one, which never reached the renderer at all. */
    private static final Duration PENDING_AGE = Duration.ofMinutes(50);

    /** And the oldest holding a document nobody has been told about. */
    private static final Duration GENERATED_AGE = Duration.ofMinutes(25);

    /** A younger batch of the same kind, so "oldest" is a claim and not an accident. */
    private static final Duration YOUNGER = Duration.ofMinutes(5);

    private static final UUID PARKED_BATCH =
            UUID.fromString("5c1d0b73-9a24-4f0e-bd18-63a7e2f4c095");

    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 21);

    /** Returned when a meter is absent, so a missing instrument fails as an assertion. */
    private static final double NO_METER = -1;

    private static final String SEAM =
            "T030 implements the batch-age sweep; this is its red run";

    /**
     * The store's own refusal, asserted <em>absent</em> from the log.
     *
     * <p>Both the wrapper's message and its cause's: a caught exception's words belong to whatever
     * raised them, and either is exactly where a connection string turns up.
     */
    private static final String STORE_REFUSAL =
            "the store could not be reached to read the oldest batch awaiting its render";

    private static final String STORE_CAUSE = "connection refused to cp-nle-01.postgres:5432";

    /** A bug of ours rather than an outage of theirs, naming a batch in passing. */
    private static final String UNEXPECTED_REFUSAL =
            "requested_at was null for batch 5c1d0b73-9a24-4f0e-bd18-63a7e2f4c095";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final GenerationMetrics metrics = new GenerationMetrics(registry);

    private final RegisterBatchRepository batches = mock(RegisterBatchRepository.class);

    private final AdjustableClock clock = AdjustableClock.startingAt(NOW);

    private final BatchAgeSweep sweep = new BatchAgeSweep(batches, metrics, clock);

    @InjectSoftAssertions
    private SoftAssertions softly;

    @Test
    void the_three_gauges_report_the_age_of_the_oldest_of_each_kind() {
        inFlight(List.of(generating(GENERATING_AGE), generating(YOUNGER)),
                List.of(pending(PENDING_AGE), pending(YOUNGER)),
                List.of(generated(GENERATED_AGE), generated(YOUNGER)));

        refresh();

        softly.assertThat(gauge(GenerationMetrics.OLDEST_GENERATING_AGE))
                .as("how long the batch that has been waiting longest for its document has been "
                        + "waiting - the oldest of the kind, not the first row that came back")
                .isEqualTo(GENERATING_AGE.toSeconds());
        softly.assertThat(gauge(GenerationMetrics.OLDEST_PENDING_AGE))
                .as("and how long the oldest batch that never reached the renderer has been "
                        + "stuck: it moves no counter and appears in no other gauge, because its "
                        + "registers are stamped and nothing else in the flow revisits it")
                .isEqualTo(PENDING_AGE.toSeconds());
        softly.assertThat(gauge(GenerationMetrics.OLDEST_GENERATED_AGE))
                .as("and the third, which is the only in-hours signal that a document is owed "
                        + "its e-mails")
                .isEqualTo(GENERATED_AGE.toSeconds());
    }

    @Test
    void a_kind_with_nothing_in_flight_reads_zero() {
        inFlight(List.of(generating(GENERATING_AGE)), List.of(), List.of());
        refresh();

        nothingInFlight();
        refresh();

        softly.assertThat(gauge(GenerationMetrics.OLDEST_GENERATING_AGE))
                .as("a reading that only ever moved up would need an incident before it could "
                        + "fall, and a gauge nothing publishes to never falls at all")
                .isZero();
        softly.assertThat(gauge(GenerationMetrics.OLDEST_PENDING_AGE)).isZero();
        softly.assertThat(gauge(GenerationMetrics.OLDEST_GENERATED_AGE)).isZero();
    }

    @Test
    void a_batch_holding_a_document_nobody_was_told_about_is_named_and_settled_nothing() {
        inFlight(List.of(), List.of(), List.of(parked()));

        try (CapturedLog log = CapturedLog.capturing(BatchAgeSweep.class)) {
            refresh();

            final List<String> warnings = warnings(log);
            softly.assertThat(warnings)
                    .as("said once: a document exists and somebody is owed it, which is a thing "
                            + "to report and not a thing to fail")
                    .hasSize(1);
            softly.assertThat(String.join(" | ", warnings))
                    .as("by identity, which is the whole of what a parked batch is on a line - "
                            + "nothing here is a defendant, a recipient or a word another system "
                            + "wrote (constitution Principle VII)")
                    .contains(PARKED_BATCH.toString())
                    .doesNotContain("defendant", "@");
        }

        softly.assertThat(gauge(GenerationMetrics.OLDEST_GENERATED_AGE))
                .as("the reading is published beside the line, because a WARN is not an alerting "
                        + "surface on its own")
                .isEqualTo(GENERATED_AGE.toSeconds());
        softly.assertThat(mockingDetails(batches).getInvocations().stream()
                        .map(invocation -> invocation.getMethod().getName())
                        .distinct()
                        .toList())
                .as("and nothing follows the line: the sweep holds no store and settles nothing, "
                        + "so the three reads are the whole of what it asks of the database - "
                        + "failing this batch would throw away a document that exists")
                .containsExactlyInAnyOrder("generatingSince", "pendingSince", "generatedSince");
    }

    @Test
    void a_read_that_refuses_keeps_the_last_value_and_is_counted() {
        inFlight(List.of(generating(GENERATING_AGE)), List.of(pending(PENDING_AGE)),
                List.of(generated(GENERATED_AGE)));
        refresh();

        when(batches.generatingSince(any(Instant.class))).thenThrow(new StoreUnavailableException(
                STORE_REFUSAL, new IllegalStateException(STORE_CAUSE)));

        try (CapturedLog log = CapturedLog.capturing(BatchAgeSweep.class)) {
            softly.assertThatCode(sweep::sweepScheduled)
                    .as("a fixed-delay schedule cancels the task that throws, so a refusal let "
                            + "out of here would take all three readings off the air for the life "
                            + "of the pod - and a gauge that has silently stopped moving is worse "
                            + "than one that was never registered")
                    .doesNotThrowAnyException();

            softly.assertThat(gauge(GenerationMetrics.OLDEST_GENERATING_AGE))
                    .as("the last reading, not a zero the store never said")
                    .isEqualTo(GENERATING_AGE.toSeconds());
            softly.assertThat(counter(SweepFailureReason.STORE_UNAVAILABLE))
                    .as("a path that drops something moves a counter - it is what makes the "
                            + "absorption visible at all, and the reading beside it says how long "
                            + "ago the gauges were last true")
                    .isEqualTo(1);
            softly.assertThat(counter(SweepFailureReason.UNEXPECTED))
                    .as("an outage of theirs is not counted as a bug of ours")
                    .isEqualTo(NO_METER);
            assertTheOneWarningNames(log, "StoreUnavailableException");
        }
    }

    /**
     * The second half of the absorbed refusal, which the case above cannot reach.
     *
     * <p>A sixth case where T029 names five, and it is here because the catch that makes this
     * method safe is total on purpose: a total catch is exactly the shape that hides a bug of ours
     * inside an outage of theirs, and it does not, because the two are counted apart.
     */
    @Test
    void a_read_that_refuses_for_any_other_reason_is_counted_unexpected() {
        inFlight(List.of(generating(GENERATING_AGE)), List.of(pending(PENDING_AGE)),
                List.of(generated(GENERATED_AGE)));
        refresh();

        when(batches.generatedSince(any(Instant.class)))
                .thenThrow(new IllegalStateException(UNEXPECTED_REFUSAL));

        try (CapturedLog log = CapturedLog.capturing(BatchAgeSweep.class)) {
            softly.assertThatCode(sweep::sweepScheduled)
                    .as("a bug of ours must not take the three readings off the air for the life "
                            + "of the pod either")
                    .doesNotThrowAnyException();

            softly.assertThat(gauge(GenerationMetrics.OLDEST_GENERATED_AGE))
                    .as("the last reading is kept whichever way the read refused")
                    .isEqualTo(GENERATED_AGE.toSeconds());
            softly.assertThat(counter(SweepFailureReason.UNEXPECTED))
                    .as("under its own reason, so an alert can be written on a series that is "
                            + "supposed to be flat at zero for ever")
                    .isEqualTo(1);
            softly.assertThat(counter(SweepFailureReason.STORE_UNAVAILABLE))
                    .as("and not hidden inside the reason that moves during every outage")
                    .isEqualTo(NO_METER);
            assertTheOneWarningNames(log, "IllegalStateException");
        }
    }

    /**
     * The correlation a scheduled unit of work has in place of the two a delivery has.
     *
     * <p>Opened rather than adopted: this sweep fires on a schedule of its own, so it is a unit of
     * work in its own right - unlike {@link StaleBatchReleaser}, which is reached from inside a run
     * and carries the run's id.
     */
    @Test
    void the_sweep_opens_its_own_run_id_and_removes_it() {
        final AtomicReference<String> seen = new AtomicReference<>();
        nothingInFlight();
        when(batches.generatingSince(any(Instant.class))).thenAnswer(call -> {
            seen.set(RunCorrelation.current());
            return List.of();
        });

        refresh();

        softly.assertThat(seen.get())
                .as("the scheduler's threads are pooled, so a sweep needs a correlation of its "
                        + "own in place of the two a delivery has")
                .isNotNull();
        softly.assertThat(RunCorrelation.current())
                .as("and an id left behind would be inherited by the next run on that thread, "
                        + "which reads as a true correlation and is worse than none")
                .isNull();
    }

    /**
     * The cadence, read off the annotation rather than off a context.
     *
     * <p>A placeholder nobody asserts is one a later edit inlines, and an inlined cadence is a
     * reading an operator can no longer change per environment without a release.
     *
     * @throws NoSuchMethodException where the scheduled method has been renamed out from under this
     */
    @Test
    void the_fixed_delay_reads_the_batch_age_refresh_key() throws NoSuchMethodException {
        final Method scheduled = BatchAgeSweep.class.getDeclaredMethod("sweepScheduled");
        final Scheduled schedule = scheduled.getAnnotation(Scheduled.class);

        softly.assertThat(scheduled.getReturnType())
                .as("a schedule has nobody to return a reading to")
                .isEqualTo(void.class);
        softly.assertThat(schedule)
                .as("the three readings are refreshed on a cadence of their own; taken once a "
                        + "night instead, they would be a daily sample of a thing that is asked "
                        + "about hourly (FR-011, SC-006)")
                .isNotNull();
        softly.assertThat(schedule == null ? null : schedule.fixedDelayString())
                .as("the generation half's own setting, and a fixed delay rather than a cron: a "
                        + "reading is not a decision, and nothing about it belongs to a wall clock")
                .isEqualTo("${yotresultsdistribution.generation.batch-age-refresh}");
    }

    /**
     * The thread it is the only thing on.
     *
     * @throws NoSuchMethodException where the scheduled method has been renamed out from under this
     */
    @Test
    void the_sweep_names_its_own_scheduler() throws NoSuchMethodException {
        final Scheduled schedule = BatchAgeSweep.class.getDeclaredMethod("sweepScheduled")
                .getAnnotation(Scheduled.class);

        softly.assertThat(schedule == null ? null : schedule.scheduler())
                .as("a ten-minute reading queued behind an 18:00 run that is asking for renders "
                        + "is a reading taken an hour late, and four TaskScheduler beans route "
                        + "nothing unless the method names one (SC-008)")
                .isEqualTo(BatchSweepConfig.BATCH_SWEEP_SCHEDULER);
    }

    /**
     * And the lock it deliberately does not have.
     *
     * @throws NoSuchMethodException where the scheduled method has been renamed out from under this
     */
    @Test
    void the_sweep_carries_no_scheduler_lock() throws NoSuchMethodException {
        final Method scheduled = BatchAgeSweep.class.getDeclaredMethod("sweepScheduled");

        softly.assertThat(scheduled.getAnnotation(SchedulerLock.class))
                .as("a gauge describes the JVM that publishes it, so a locked sweep would have "
                        + "one replica reading the store while every other pod went on publishing "
                        + "whatever it last saw - one pod's view under every pod's labels")
                .isNull();
    }

    /**
     * One refresh, with the seam's refusal recorded rather than thrown.
     *
     * <p>Against the seam every call refuses, and the suite's claims are about the readings:
     * recording the refusal as the case's first soft failure is what makes the red an assertion
     * rather than a stack trace out of the arrangement.
     */
    private void refresh() {
        softly.assertThatCode(sweep::sweepScheduled).as(SEAM).doesNotThrowAnyException();
    }

    /**
     * What the three reads answer for a store holding the given batches.
     *
     * @param generating the batches awaiting a render, oldest first
     * @param pending    the batches that never reached the renderer, oldest first
     * @param generated  the batches holding a document nobody was told about, oldest first
     */
    private void inFlight(final List<RegisterBatch> generating, final List<RegisterBatch> pending,
            final List<RegisterBatch> generated) {

        when(batches.generatingSince(any(Instant.class))).thenReturn(generating);
        when(batches.pendingSince(any(Instant.class))).thenReturn(pending);
        when(batches.generatedSince(any(Instant.class))).thenReturn(generated);
    }

    /** A store with nothing in flight, which is the ordinary answer a healthy night gives. */
    private void nothingInFlight() {
        inFlight(List.of(), List.of(), List.of());
    }

    /**
     * One WARN line, naming the caught failure by class and repeating none of its words.
     *
     * @param log  the capture over this class's logger
     * @param type the simple name of the class the line must name
     */
    private void assertTheOneWarningNames(final CapturedLog log, final String type) {
        final List<String> warnings = warnings(log);

        softly.assertThat(warnings)
                .as("said once - a refusal repeated per reading is an incident that looks three "
                        + "times as bad as it is")
                .hasSize(1);
        softly.assertThat(String.join(" | ", warnings))
                .as("named by class; neither the caught exception's own message nor its cause's "
                        + "reaches the log, and both are where a connection string turns up")
                .contains(type)
                .doesNotContain(STORE_REFUSAL, STORE_CAUSE, UNEXPECTED_REFUSAL, "cp-nle-01");
        softly.assertThat(log.events().stream()
                        .filter(event -> event.getLevel() == Level.WARN)
                        .map(ILoggingEvent::getThrowableProxy)
                        .toList())
                .as("and no throwable this service did not write is attached")
                .containsOnlyNulls();
    }

    /**
     * The lines the sweep wrote at WARN, which is where a refusal and a parked batch are said.
     *
     * @param log the capture over this class's logger
     * @return the rendered warnings
     */
    private static List<String> warnings(final CapturedLog log) {
        return log.events().stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(CapturedLog::rendering)
                .toList();
    }

    /**
     * One batch awaiting its render, aged by the stamp that state carries.
     *
     * @param age how long ago its render was asked for
     * @return the row the read answers with
     */
    private static RegisterBatch generating(final Duration age) {
        return batch(BatchStatus.GENERATING, null, NOW.minus(age), null);
    }

    /**
     * One batch that never reached the renderer, aged from assembly.
     *
     * @param age how long ago it was assembled
     * @return the row the read answers with
     */
    private static RegisterBatch pending(final Duration age) {
        return batch(BatchStatus.PENDING, NOW.minus(age), null, null);
    }

    /**
     * One batch holding a document nobody was told about, aged from the document.
     *
     * @param age how long ago the document arrived
     * @return the row the read answers with
     */
    private static RegisterBatch generated(final Duration age) {
        return batch(BatchStatus.GENERATED, null, null, NOW.minus(age));
    }

    /** The parked batch the WARN must name, with an identity the assertion can look for. */
    private static RegisterBatch parked() {
        return new RegisterBatch(PARKED_BATCH, UUID.randomUUID(), "B01CE", "Youth Court",
                MONDAY, "register.pdf", UUID.randomUUID(), UUID.randomUUID(),
                BatchStatus.GENERATED, null, null, true, null, NOW.minus(GENERATED_AGE),
                NOW.minus(GENERATED_AGE), NOW.minus(GENERATED_AGE), null, null, 1, null, 0);
    }

    /**
     * One row of the {@code register_batch} table, carrying only the stamp its state is aged by.
     *
     * @param status      what the batch is
     * @param assembledAt when it was assembled, where that is the stamp
     * @param requestedAt when its render was asked for, where that is the stamp
     * @param generatedAt when its document arrived, where that is the stamp
     * @return the row the read answers with
     */
    private static RegisterBatch batch(final BatchStatus status, final Instant assembledAt,
            final Instant requestedAt, final Instant generatedAt) {

        return new RegisterBatch(UUID.randomUUID(), UUID.randomUUID(), "B01CE", "Youth Court",
                MONDAY, "register.pdf", UUID.randomUUID(), null, status, null, null, true, null,
                assembledAt, requestedAt, generatedAt, null, null, 1, null, 0);
    }

    /**
     * One gauge's reading, or {@link #NO_METER} where the instrument does not exist.
     *
     * @param name the instrument's documented name
     * @return what it currently reads
     */
    private double gauge(final String name) {
        final Gauge found = registry.find(name).gauge();
        return found == null ? NO_METER : found.value();
    }

    /**
     * The absorbed-refusal counter's reading under one bounded reason.
     *
     * @param reason the code the refusal was counted under
     * @return what that series has counted, or {@link #NO_METER} where it does not exist
     */
    private double counter(final SweepFailureReason reason) {
        final Counter found = registry.find(GenerationMetrics.BATCH_SWEEP_FAILURES)
                .tag(GenerationMetrics.REASON_TAG,
                        reason.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                .counter();
        return found == null ? NO_METER : found.count();
    }
}
