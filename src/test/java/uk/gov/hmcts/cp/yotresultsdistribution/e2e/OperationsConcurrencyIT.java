package uk.gov.hmcts.cp.yotresultsdistribution.e2e;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.yotresultsdistribution.application.BatchOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.application.DocumentOutcomeSinkImpl;
import uk.gov.hmcts.cp.yotresultsdistribution.application.NotificationDisposition;
import uk.gov.hmcts.cp.yotresultsdistribution.application.NotificationOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.application.NotificationSummary;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OperationsRunLauncher;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterGenerationService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterNotifier;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterNotifierService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterRegenerationService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterRegenerationService.RegenerationTally;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterRegenerationService.Selection;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.BatchAssembler;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.FeatureFlagGate;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.RegisterGenerationJob;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletedBy;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterHearingVenue;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GuardDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterRecord;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestFingerprint;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.JdbcRegisterStore;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterNotificationRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;

/**
 * What the separate CLI JVM used to make impossible, over the real store and the real lock.
 *
 * <p>An endpoint is served by a pod where the consumer, both schedulers and the public-event
 * listener are all running - which is exactly what a command JVM used to switch off, and what
 * Phase 9 exists to prove is safe (FR-040). Four races, and not one of them is
 * arbitrated by anything this increment invented: the notifier's claim decides two concurrent
 * notifies, the register-generation lock decides a regeneration against the schedule, and the live
 * -key index and {@code releaseFailed} decide two regenerations for one date.
 *
 * <p><strong>Real store, real lock.</strong> Testcontainers Postgres with the committed migrations,
 * this service's own {@code JdbcRegisterStore} and repositories, and ShedLock's own
 * {@code JdbcTemplateLockProvider} over the same datasource under the schedule's own lock name. A
 * race decided by a mock is a race the mock decided.
 *
 * <p><strong>The requesting leg is stood in for and nothing else is.</strong>
 * {@code RegisterGenerationService} writes a payload into the file service and POSTs to
 * systemdocgenerator, neither of which any of these races is about; it is mocked so that the run
 * reaches its own store writes, which is where the contention is.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("an operations call against the legs that are running beside it")
class OperationsConcurrencyIT {

    /** The first day a case may use; each case takes one of its own from here. */
    private static final Instant FIRST_DAY = Instant.parse("2026-03-02T16:30:00Z");

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /**
     * How many cases have taken a day, so that no two of them share one.
     *
     * <p>Every case here reads and writes a whole register <em>date</em> - a regeneration releases
     * the day's FAILED batches and re-assembles what it finds waiting - so two cases on one day
     * would each be contending with the other's leftovers rather than with the contender the case
     * is about. The store is shared across the suite and is not reset between cases, exactly as it
     * is not reset between nights.
     */
    private static final AtomicInteger DAYS_TAKEN = new AtomicInteger();

    private static final String OU_CODE = "B01LY00";

    private static final String APPLICANT = "Lavender Hill Youth Court";

    private static final String YOT_ADDRESS = "yot@wandsworth.example.gov.uk";

    private static final UUID TEMPLATE_ID =
            UUID.fromString("5c9a0e21-3d47-4f18-9b62-0a71c4e8d530");

    private static final Duration LEASE = Duration.ofMinutes(5);

    /** How long a contended attempt is given before the suite calls it stuck rather than slow. */
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    /** The lock is held for at most this, which is the schedule's own bound. */
    private static final Duration AT_MOST_FOR = Duration.ofMinutes(70);

    /** A non-blocking attempt, which is the default a deployment gets. */
    private static final Duration NO_WAIT = Duration.ZERO;


    /** This case's own share instant, and with it its own register date and court centre. */
    private final Instant shared = FIRST_DAY.plus(Duration.ofDays(DAYS_TAKEN.getAndIncrement()));

    private final LocalDate day = LocalDate.ofInstant(shared, LONDON);

    private final UUID courtCentre = UUID.randomUUID();

    private final Clock clock = Clock.fixed(shared, ZoneOffset.UTC);

    private final JdbcRegisterStore store = new JdbcRegisterStore(
            ProcessedLogTestSupport.jdbcClient(), ProcessedLogTestSupport.transactionManager());

    private final RegisterBatchRepository batches = new RegisterBatchRepository(
            ProcessedLogTestSupport.jdbcClient(), ProcessedLogTestSupport.transactions(), LEASE);

    private final RegisterNotificationRepository notifications =
            new RegisterNotificationRepository(ProcessedLogTestSupport.jdbcClient());

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final GenerationMetrics metrics = new GenerationMetrics(meters);

    @InjectSoftAssertions
    private SoftAssertions softly;

    @BeforeAll
    static void migrate() {
        PostgresTestSupport.applyFlyway();
    }

    // --- two notify calls for one batch -----------------------------------------------------

    /**
     * The claim {@code RegisterNotifierService} already takes, asked to arbitrate two callers.
     */
    @Nested
    @DisplayName("two notify calls for one batch at once")
    class TwoNotifiers {

        @Test
        void the_claim_should_settle_one_and_refuse_the_other_without_a_second_email()
                throws Exception {

            final UUID batchId = aGeneratedBatchWithOneOwedRecipient();
            final AtomicInteger sent = new AtomicInteger();
            final RegisterNotifierService first = notifier(sent, Duration.ofMillis(300));
            final RegisterNotifierService second = notifier(sent, Duration.ZERO);

            final List<NotificationSummary> answers = bothAtOnce(
                    () -> first.resendFailed(batchId), () -> second.resendFailed(batchId));

            softly.assertThat(answers)
                    .as("the claim is what arbitrates them, and its dispositions are the answer - "
                            + "nothing new was invented to decide this")
                    .extracting(NotificationSummary::disposition)
                    .containsExactlyInAnyOrder(NotificationDisposition.SETTLED,
                            NotificationDisposition.ALREADY_NOTIFYING);
            softly.assertThat(sent.get())
                    .as("two runs telling one batch's recipients is a second e-mail about the "
                            + "same children to every team on it")
                    .isEqualTo(1);
        }
    }

    // --- a regeneration against the schedule ------------------------------------------------

    /**
     * The lock the 18:00 run takes, taken rather than asked about, in both orders.
     */
    @Nested
    @DisplayName("a regeneration and the scheduled run")
    class TheLockBetweenThem {

        @Test
        void a_regeneration_should_do_nothing_while_the_schedule_holds_the_lock() {
            final LockProvider locks = theSchedulesLockProvider();
            final SimpleLock held = locks.lock(theSchedulesLock()).orElseThrow();
            final RegisterRegenerationService regeneration = mock(RegisterRegenerationService.class);
            try {
                launcher(regeneration, locks).launch(theWholeDay());

                softly.assertThat(regeneration)
                        .as("asking whether the lock is held and then acting races the scheduler; "
                                + "taking it cannot, and a run that cannot have it does nothing")
                        .satisfies(service -> org.mockito.Mockito.verifyNoInteractions(service));
            } finally {
                held.unlock();
            }
        }

        @Test
        void a_scheduled_run_should_stand_aside_while_a_regeneration_holds_the_lock() {
            final LockProvider locks = theSchedulesLockProvider();
            final RegisterRegenerationService regeneration = mock(RegisterRegenerationService.class);
            when(regeneration.regenerate(any(), org.mockito.ArgumentMatchers.anyBoolean()))
                    .thenAnswer(invocation -> {
                        softly.assertThat(locks.lock(theSchedulesLock()))
                                .as("the other order, by the mechanism the schedule already uses: "
                                        + "one run per night across every replica")
                                .isEmpty();
                        return anEmptyTally();
                    });

            launcher(regeneration, locks).launch(theWholeDay());

            softly.assertThat(locks.lock(theSchedulesLock()))
                    .as("and the lock is given back, so the next run is not locked out by a run "
                            + "that has finished")
                    .isNotEmpty()
                    .hasValueSatisfying(SimpleLock::unlock);
        }
    }

    // --- two regenerations for one date -----------------------------------------------------

    /**
     * The claims the store already holds, asked to arbitrate two operators on two replicas.
     */
    @Nested
    @DisplayName("two regenerations for one register date")
    class TwoRegenerations {

        @Test
        void the_loser_should_end_in_a_bounded_recorded_outcome_and_not_a_failure() {
            aFailedBatchOfOneRegister();
            final RegisterRegenerationService one = regeneration();
            final RegisterRegenerationService two = regeneration();

            final RegenerationTally won = one.regenerate(theWholeDay(), false);
            final Throwable lost = catchThrowable(() -> two.regenerate(theWholeDay(), false));

            softly.assertThat(won.released())
                    .as("the winner released the day's FAILED batch and re-assembled it")
                    .isEqualTo(1);
            softly.assertThat(won.batches()).isEqualTo(1);
            softly.assertThat(store.batchesOn(day))
                    .as("and the batch it made stands beside the one it failed")
                    .hasSize(2);

            softly.assertThat(lost)
                    .as("the loser is not an unexplained failure: the store's own claims decide "
                            + "this, and by the time it reads the day there is no FAILED batch "
                            + "left to release and nothing active and unbatched to assemble")
                    .isNull();
        }

        @Test
        void the_loser_should_have_taken_nothing_the_winner_had_already_taken() {
            aFailedBatchOfOneRegister();
            final RegisterRegenerationService one = regeneration();
            final RegisterRegenerationService two = regeneration();

            one.regenerate(theWholeDay(), false);
            final RegenerationTally lost = two.regenerate(theWholeDay(), false);

            softly.assertThat(lost.released())
                    .as("releaseFailed returns no rows to the loser")
                    .isZero();
            softly.assertThat(lost.registers())
                    .as("and its assemble is handed nothing, because the registers are stamped "
                            + "into the batch the winner made - which is the live-key index doing "
                            + "the arbitrating, not anything this increment invented")
                    .isZero();
            softly.assertThat(lost.batches()).isZero();
            softly.assertThat(store.batchesOn(day))
                    .as("so one regeneration, one new batch, whichever of the two got there first")
                    .hasSize(2);
        }
    }

    // --- a public event during a regeneration -----------------------------------------------

    /**
     * The listener is running in this pod, which it was not in a CLI JVM.
     */
    @Nested
    @DisplayName("a public document event during a regeneration")
    class AnOutcomeArrivingMidRun {

        @Test
        void it_should_be_applied_exactly_as_it_is_during_a_scheduled_run() {
            final UUID batchId = aRequestedBatch();
            final UUID documentId = UUID.randomUUID();
            final DocumentOutcomeSinkImpl sink = new DocumentOutcomeSinkImpl(store, batches,
                    metrics, mock(RegisterNotifierService.class));

            sink.documentAvailable(batchId, payloadOf(batchId), documentId, shared,
                    CompletedBy.EVENT);

            softly.assertThat(batches.findById(batchId))
                    .as("the outcome is the batch's, whoever asked for the render - an operator's "
                            + "run and the schedule's leave the same GENERATING batch behind")
                    .hasValueSatisfying(batch -> {
                        softly.assertThat(batch.status()).isEqualTo(BatchStatus.GENERATED);
                        softly.assertThat(batch.documentFileId()).isEqualTo(documentId);
                    });
        }
    }

    // --- the fixtures -------------------------------------------------------------------------

    /** The whole of this case's day, nothing narrowed and no override. */
    private Selection theWholeDay() {
        return new Selection(day, null, null, null, false);
    }

    /** A tally of a run that found nothing to do. */
    private RegenerationTally anEmptyTally() {
        return new RegenerationTally(day, 0, 0, 0, 0, 0, false, java.util.Map.of(),
                java.util.Map.of(), new uk.gov.hmcts.cp.yotresultsdistribution.domain.RunReport(
                        new GateDecision.Proceed(false), java.util.Map.of(), 0, java.util.Map.of(),
                        0, 0, uk.gov.hmcts.cp.yotresultsdistribution.domain.RunReport.Settled.UNREAD,
                        0, 0, 0, Duration.ZERO));
    }

    /** ShedLock over the same database the store is in, under the schedule's own name. */
    private static LockProvider theSchedulesLockProvider() {
        return new JdbcTemplateLockProvider(
                new JdbcTemplate(ProcessedLogTestSupport.dataSource()));
    }

    /** The schedule's lock, by its own name and its own bound. */
    private static LockConfiguration theSchedulesLock() {
        return new LockConfiguration(Instant.now(), RegisterGenerationJob.LOCK_NAME, AT_MOST_FOR,
                Duration.ZERO);
    }

    /**
     * The hand-off, over a regeneration this case decides the behaviour of.
     *
     * @param regeneration what the background run makes
     * @param locks        the schedule's lock provider
     * @return the launcher, with the flag admitting the run and the work run inline
     */
    private OperationsRunLauncher launcher(final RegisterRegenerationService regeneration,
            final LockProvider locks) {

        final FeatureFlagGate gate = mock(FeatureFlagGate.class);
        when(gate.decide(org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new GateDecision.Proceed(false));
        // Inline, because what is being asserted is the contention and not the hand-off: the
        // scheduler's own thread is OperationsRunLauncherTest's subject.
        //
        // AND THE LOCK'S CLOCK IS THIS MACHINE'S, NOT THE SUITE'S FIXED ONE. ShedLock computes
        // `locked_until` from the instant the configuration carries, so a fixed clock in the past
        // writes a lock that is already expired and every contender walks straight through it -
        // a green test over a lock that was never held.
        return new OperationsRunLauncher(regeneration, gate, locks, AT_MOST_FOR, NO_WAIT,
                Runnable::run, Clock.systemUTC());
    }

    /** A regeneration over the real store and the real assembler. */
    private RegisterRegenerationService regeneration() {
        final RegisterGenerationService requesting = mock(RegisterGenerationService.class);
        when(requesting.request(any(), any(), any())).thenAnswer(invocation -> {
            final RegisterBatch stored = invocation.getArgument(0);
            return new BatchOutcome(stored.batchId(), BatchStatus.GENERATING, null, true);
        });
        return new RegisterRegenerationService(store, new BatchAssembler(), requesting,
                Duration.ofMinutes(60), clock);
    }

    /**
     * A notifier over the real store, counting what it sent.
     *
     * @param sent  where every send is counted
     * @param dwell how long a send holds the claim, so two callers genuinely overlap
     * @return the notifier
     */
    private RegisterNotifierService notifier(final AtomicInteger sent, final Duration dwell) {
        final RegisterNotifier port = (notification, documentFileId, caller) -> {
            sent.incrementAndGet();
            if (!dwell.isZero()) {
                try {
                    Thread.sleep(dwell.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return new NotificationOutcome(NotificationStatus.ACCEPTED, 202);
        };
        return new RegisterNotifierService(store, batches, notifications, port, metrics,
                TEMPLATE_ID, new RetryPolicy(1, Duration.ZERO, Duration.ZERO, Duration.ZERO),
                waited -> {}, clock);
    }

    /**
     * Runs both callers at once and answers with what each of them said.
     *
     * @param first  one caller
     * @param second the other
     * @return both answers
     * @throws Exception where either thread did not finish inside the suite's patience
     */
    private static List<NotificationSummary> bothAtOnce(
            final java.util.concurrent.Callable<NotificationSummary> first,
            final java.util.concurrent.Callable<NotificationSummary> second) throws Exception {

        final CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService both = Executors.newFixedThreadPool(2)) {
            final Future<NotificationSummary> one = both.submit(() -> {
                go.await();
                return first.call();
            });
            final Future<NotificationSummary> two = both.submit(() -> {
                go.await();
                Thread.sleep(50);
                return second.call();
            });
            go.countDown();
            final List<NotificationSummary> answers =
                    List.of(one.get(PATIENCE.toSeconds(), TimeUnit.SECONDS),
                            two.get(PATIENCE.toSeconds(), TimeUnit.SECONDS));
            both.shutdownNow();
            return answers;
        }
    }

    /**
     * A batch that has a document and one recipient nobody has been told about.
     *
     * @return the batch's identity
     */
    private UUID aGeneratedBatchWithOneOwedRecipient() {
        final UUID hearingId = UUID.randomUUID();
        record(hearingId);
        final List<RegisterRecord> waiting = waiting();
        final RegisterBatch stored = store.assemble(pendingBatch(waiting), waiting);
        store.markRequested(stored.batchId(), UUID.randomUUID());
        store.markGenerated(stored.batchId(), UUID.randomUUID(), shared,
                CompletedBy.EVENT);
        return stored.batchId();
    }

    /**
     * A batch this day's run asked a render for and learned nothing about yet.
     *
     * @return the batch's identity
     */
    private UUID aRequestedBatch() {
        final UUID hearingId = UUID.randomUUID();
        record(hearingId);
        final List<RegisterRecord> waiting = waiting();
        final RegisterBatch stored = store.assemble(pendingBatch(waiting), waiting);
        store.markRequested(stored.batchId(), UUID.randomUUID());
        return stored.batchId();
    }

    /**
     * A FAILED batch holding one register, which is what a regeneration releases.
     *
     * @return the batch's identity
     */
    private UUID aFailedBatchOfOneRegister() {
        final UUID batchId = aRequestedBatch();
        store.markFailed(batchId, BatchFailureReason.GENERATION_FAILED, null,
                CompletedBy.EVENT);
        return batchId;
    }

    /**
     * The payload id the batch's render was asked under.
     *
     * @param batchId the batch
     * @return its payload file id
     */
    private UUID payloadOf(final UUID batchId) {
        return batches.findById(batchId).orElseThrow().payloadFileId();
    }

    /** The day's registers that are still waiting to be batched. */
    private List<RegisterRecord> waiting() {
        return store.activeUnbatched().stream()
                .filter(record -> courtCentre.equals(record.key().courtCentreId()))
                .toList();
    }

    /**
     * The batch the assembler would make of them.
     *
     * @param records the registers it is made of
     * @return the batch, as the assembler asks for it
     */
    private RegisterBatch pendingBatch(final List<RegisterRecord> records) {
        return new RegisterBatch(UUID.randomUUID(), courtCentre, null, null, day,
                records.isEmpty() ? null : records.getFirst().fileName(), null, null,
                BatchStatus.PENDING, null, null, true, null, null, null, null, null, null, 0,
                null, 0);
    }

    /** One hearing's register, recorded the way the pipeline records it. */
    private void record(final UUID hearingId) {
        final DistributionCommand command = new DistributionCommand(
                ProcessedLogTestSupport.SOURCE, UUID.randomUUID(), hearingId,
                day, shared, "Hearing_Resulted");
        ProcessedLogTestSupport.repository(LEASE).insertNew(command,
                RequestFingerprint.of(command),
                new RunClaim(command.source(), command.requestId(), "runner-1", UUID.randomUUID(),
                        "msg-1"));
        store.recordAndComplete(command, document(hearingId), OU_CODE, APPLICANT,
                RecordedFlagState.ON, () -> new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));
    }

    /**
     * The register document one hearing produces.
     *
     * @param hearingId the hearing
     * @return the document, with one youth defendant and one subscribed team
     */
    private CourtRegisterDocument document(final UUID hearingId) {
        return new CourtRegisterDocument(
                shared.toString(),
                shared.truncatedTo(java.time.temporal.ChronoUnit.DAYS).toString(),
                hearingId.toString(),
                courtCentre.toString(),
                "court-register_" + day + '_' + OU_CODE + '_' + hearingId + ".pdf",
                null,
                new CourtRegisterHearingVenue("Lavender Hill LJA", "Lavender Hill Youth Court",
                        null),
                List.of(new CourtRegisterRecipient(
                        "Wandsworth Youth Offending Team", YOT_ADDRESS, null, "cr_standard")),
                List.of(new CourtRegisterDefendant(
                        "b2b3f5a1-6c9d-4e21-8a7f-3d5c1e9b0426", "SMITH, John", "2008-04-11",
                        null, null, null, "MALE", "Not Applicable", null, null,
                        List.of(), List.of(), List.of(), List.of())));
    }
}
