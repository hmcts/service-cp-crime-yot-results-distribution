package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.transaction.support.TransactionOperations;
import uk.gov.hmcts.cp.yotresultsdistribution.application.NotificationSummary;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterStore;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ReleasedBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.application.StaleReleaseOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletedBy;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterHearingVenue;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GuardDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterRecord;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestFingerprint;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;

/**
 * The fenced release raced against the two things that can overtake it - SC-009.
 *
 * <p>This is the reason {@code failAndReleaseStale} is one statement rather than a read followed by
 * a mark, and it is the suite the rest of the increment stands on: everything after Phase 2 assumes
 * the operation is atomic and fenced on the staleness rule itself.
 *
 * <p>Two contenders for the batch, because they are the two ways a batch stops being stale while a
 * run is deciding about it: a render request accepted for a PENDING batch ({@code markRequested}),
 * and a {@code document-available} applied to a GENERATING one ({@code markGenerated}). Each is run
 * in <strong>both winner orders</strong> - staged, so the two deterministic outcomes can be asserted
 * by name - and then <strong>repeatedly, genuinely concurrently</strong>, where either may win and
 * only the invariants may be asserted.
 *
 * <p>Those same two contenders are also run <strong>at the row itself</strong>, in a round each with
 * no timing in it: a session takes the batch row {@code FOR UPDATE}, the pass blocks on its write,
 * and the holder's own transaction then moves the batch and commits. What that pins is the rule the
 * whole fence rests on - under READ COMMITTED an {@code UPDATE} that waited re-evaluates its own
 * {@code WHERE} against the row version it was granted - and it pins it by arrangement rather than
 * by two threads landing inside the same few microseconds, which is all a {@code TOGETHER} round can
 * offer.
 *
 * <p>And a third contender for the <strong>key</strong> rather than for the batch: the hearing
 * re-shared while the pass is giving its register back ({@code recordAndComplete}). It cannot be
 * refused and it refuses nothing, but it and the release write into one active-register key, and
 * its commit can land inside the pass's own statement - where the statement's snapshot cannot see
 * it. That round is staged too, by holding the batch row the statement writes first.
 *
 * <p>The invariants, after every round:
 *
 * <ul>
 *   <li><strong>No stranded register.</strong> A register still awaiting its document is either
 *       stamped to a live batch or active and unbatched, and never stamped to a terminal one -
 *       {@code ACTIVE_UNBATCHED}'s predicate is {@code batch_id IS NULL}, so a RECORDED row stamped
 *       to a FAILED batch is a hearing's youth defendants that no later run and no command will ever
 *       reach again. That is the precise failure this increment exists to end, and the failure a
 *       read-then-mark shape reintroduces the moment a crash lands between its two statements.</li>
 *   <li><strong>One live batch per key.</strong> Two would render and e-mail one court centre day
 *       twice.</li>
 *   <li><strong>One notification aggregate per key and address.</strong> SC-003 read off the rows: a
 *       Youth Offending Team is never told twice about one register date, whichever contender won
 *       the race that night.</li>
 *   <li><strong>Every refusal is the state machine's.</strong> The loser of a race is refused with
 *       {@link IllegalStateException} - which the listener rethrows and the broker redelivers, the
 *       self-healing path - and never with anything else.</li>
 * </ul>
 *
 * <p>Each round mints its own court centre, so the rounds and the other suites sharing the container
 * hold no rows in common, and every cutoff stated here is in the past for the same reason.
 *
 * <p>Soft assertions, so a red run reports every round rather than stopping at the first.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the stale release under concurrency")
class StaleReleaseConcurrencyIT {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 24);

    private static final Instant MONDAY_SHARED = Instant.parse("2026-08-24T09:00:00Z");

    /**
     * The day after this suite's, for a round that has to know which batch is walked second.
     *
     * <p>{@code STALE_BATCHES} answers {@code ORDER BY register_date, batch_id}, so two batches on
     * one day are walked in whatever order two random identities happen to fall in. A round about
     * what the pass does <em>after</em> a batch it could not release cannot be left to that.
     */
    private static final LocalDate TUESDAY = LocalDate.of(2026, 8, 25);

    /** The moment the estate stamped a share of {@link #TUESDAY}'s register. */
    private static final Instant TUESDAY_SHARED = Instant.parse("2026-08-25T09:00:00Z");

    private static final Instant HEARING_DATE = Instant.parse("2026-08-19T00:00:00Z");

    private static final Duration LEASE = Duration.ofMinutes(5);

    /** Older than the cutoff every round states, which is what the night between two runs leaves. */
    private static final Duration LAST_NIGHT = Duration.ofHours(2);

    /** What a run gives up after, and the age every cutoff here is stated at. */
    private static final Duration STALE_AFTER = Duration.ofMinutes(30);

    private static final String OU_CODE = "B01LY00";

    private static final String APPLICANT = "Applicant";

    private static final String YOT_ADDRESS = "yot@example.gov.uk";

    private static final String NOT_COMPLETED = "NOT_COMPLETED_BY_NEXT_RUN";

    /** The states in which a batch is still owed something, which is what makes a key busy. */
    private static final List<String> LIVE = List.of("PENDING", "GENERATING", "GENERATED");

    /** How long a round waits for its two contenders before calling the race hung. */
    private static final int RACE_SECONDS = 30;

    /** The single row a fixture ageing a batch is expected to touch. */
    private static final int ONE_BATCH = 1;

    /**
     * How much later than the register it replaces the re-share held against the key is stamped.
     *
     * <p>Small enough to leave the share on the same register date, because it is the ordering and
     * not the day that decides which of two shares of one hearing the day is still to render.
     */
    private static final Duration LATER_SHARE = Duration.ofHours(1);

    /** How often the staged window asks whether the pass has reached the row it is held at. */
    private static final int POLL_MILLIS = 20;

    /**
     * The orders a round is run in: the two staged ones, then four genuine races.
     *
     * <p>Staged first because the two deterministic outcomes are the properties worth naming - the
     * pass wins and the outcome is refused, or the outcome wins and the pass matches nothing - and
     * concurrently afterwards because a property that only holds when the scheduler cooperates is
     * not a property.
     */
    private static final List<Order> ROUNDS = List.of(
            Order.RELEASE_FIRST, Order.OUTCOME_FIRST,
            Order.TOGETHER, Order.TOGETHER, Order.TOGETHER, Order.TOGETHER);

    /**
     * The orders the third contender is run in, which needs one more than the other two.
     *
     * <p>A re-share cannot lose this race by being refused - it is a register the estate sent and
     * this service records it whatever a batch is doing - so the order that matters is not which of
     * them wins but <strong>where the re-share's commit lands inside the pass's own statement</strong>.
     * {@link Order#INSIDE_THE_WINDOW} is that one, staged deterministically; the two staged winners
     * are here for the same reason they are in {@link #ROUNDS}, and the races afterwards because a
     * property that needs the scheduler's cooperation is not a property.
     */
    private static final List<Order> RESHARE_ROUNDS = List.of(
            Order.RELEASE_FIRST, Order.OUTCOME_FIRST, Order.INSIDE_THE_WINDOW,
            Order.TOGETHER, Order.TOGETHER);

    private final RegisterStore store =
            new JdbcRegisterStore(ProcessedLogTestSupport.jdbcClient(),
                    ProcessedLogTestSupport.transactionManager());

    private final TransactionOperations transactions = ProcessedLogTestSupport.transactions();

    private final RegisterNotificationRepository notifications =
            new RegisterNotificationRepository(ProcessedLogTestSupport.jdbcClient());

    @InjectSoftAssertions
    private SoftAssertions softly;

    @BeforeAll
    static void migrate() {
        PostgresTestSupport.applyFlyway();
    }

    /**
     * Which of the two contenders is let go first, or whether both are let go at once.
     *
     * <p>{@link #INSIDE_THE_WINDOW} is the fourth and is not an order of arrival at all: it is the
     * contender committing <em>while the pass's statement is already running</em>, which is the one
     * place a re-share is invisible to the statement that has to account for it.
     */
    private enum Order {
        RELEASE_FIRST, OUTCOME_FIRST, TOGETHER, INSIDE_THE_WINDOW
    }

    @Test
    void a_render_acceptance_racing_the_release_leaves_no_stranded_register() {
        final List<Ending> endings = new ArrayList<>();

        for (final Order order : ROUNDS) {
            final UUID courtCentre = UUID.randomUUID();
            final Instant cutoff = cutoff();
            final RegisterBatch batch = staleBatch(courtCentre, false);
            final Escapes escaped = raced(order, batch.batchId(),
                    () -> store.failAndReleaseStale(cutoff, cutoff),
                    () -> store.markRequested(batch.batchId(), UUID.randomUUID()));

            final Ending ending = endingOf(batch.batchId());
            settleTheNight(courtCentre);
            endings.add(ending);
            assertInvariants(courtCentre, order, cutoff, escaped);
            theRefusalExists(order, escaped);
        }

        softly.assertThat(endings.get(0))
                .as("the pass let go first fails the batch it found stale, under the one bounded "
                        + "reason that means the passage of time, and the render request that "
                        + "follows is refused by the state machine rather than accepted against a "
                        + "batch this service has already given up on")
                .isEqualTo(new Ending("FAILED", NOT_COMPLETED));
        softly.assertThat(endings.get(1))
                .as("and a render accepted first moves the batch out of the pass's reach without "
                        + "any read of a state that has since moved: the request is stamped now, "
                        + "so the predicate no longer matches and nothing is written for it")
                .isEqualTo(new Ending("GENERATING", null));
    }

    @Test
    void a_document_arrival_racing_the_release_leaves_no_stranded_register() {
        final List<Ending> endings = new ArrayList<>();

        for (final Order order : ROUNDS) {
            final UUID courtCentre = UUID.randomUUID();
            final Instant cutoff = cutoff();
            final RegisterBatch batch = staleBatch(courtCentre, true);
            final Escapes escaped = raced(order, batch.batchId(),
                    () -> store.failAndReleaseStale(cutoff, cutoff),
                    () -> store.markGenerated(batch.batchId(), UUID.randomUUID(), Instant.now(),
                            CompletedBy.EVENT));

            final Ending ending = endingOf(batch.batchId());
            settleTheNight(courtCentre);
            endings.add(ending);
            assertInvariants(courtCentre, order, cutoff, escaped);
            theRefusalExists(order, escaped);
        }

        softly.assertThat(endings.get(0))
                .as("the pass let go first fails the batch, and the document that arrives after it "
                        + "is refused by the state machine - the listener rethrows, the broker "
                        + "redelivers, and the sink then reads a FAILED batch and drops the "
                        + "outcome. Self-healing, and the registers are already back")
                .isEqualTo(new Ending("FAILED", NOT_COMPLETED));
        softly.assertThat(endings.get(1))
                .as("and a document that arrives first takes the batch to GENERATED, where the "
                        + "pass may not touch it at any age: it holds a document somebody is owed "
                        + "e-mails about")
                .isEqualTo(new Ending("GENERATED", null));
    }

    /**
     * The re-check the fence rests on, pinned rather than raced - the render accepted at the row.
     *
     * <p>The {@code TOGETHER} rounds above prove the fence only when the two contenders happen to
     * land within the same handful of microseconds, which is a property asserted at the scheduler's
     * discretion. What actually makes the operation safe is a rule of READ COMMITTED: an
     * {@code UPDATE} that reaches a row another transaction has just committed re-evaluates
     * <em>its own</em> {@code WHERE} against the row as it now stands, and skips it where it no
     * longer qualifies. That is why the staleness rule is written in the {@code UPDATE}'s own
     * predicate and not in a clause that feeds it.
     *
     * <p>This round stages exactly that, with no timing in it. A session takes the batch row
     * {@code FOR UPDATE}, so the pass reads its list of stale batches and then blocks on the write;
     * the <strong>holder's own transaction</strong> then moves the batch on - the render is
     * accepted, so a PENDING batch becomes GENERATING and is stamped {@code now()} - and commits.
     * The pass is granted the row it was waiting for, re-reads the version the holder left, finds a
     * batch that is no longer stale, and changes nothing about it.
     *
     * <p>And "changes nothing" is three separate claims, all asserted: the batch is not released,
     * it is not <em>contended</em> either - a batch nothing was refused over lost no race - and the
     * row stands exactly where the holder left it.
     */
    @Test
    void a_render_accepted_at_the_row_the_release_waits_for_leaves_the_batch_alone() {
        final UUID courtCentre = UUID.randomUUID();
        final Instant cutoff = cutoff();
        final RegisterBatch batch = staleBatch(courtCentre, false);
        final AtomicReference<StaleReleaseOutcome> answered = new AtomicReference<>();

        final Escapes escaped = whileTheRowIsHeld(batch.batchId(),
                () -> answered.set(store.failAndReleaseStale(cutoff, cutoff)),
                () -> store.markRequested(batch.batchId(), UUID.randomUUID()));

        theBatchWasLeftAlone(courtCentre, cutoff, batch, escaped, answered.get(),
                new Ending("GENERATING", null));
        settleTheNight(courtCentre);
    }

    /**
     * The same re-check, with the document arriving at the held row instead.
     *
     * <p>The other way a batch stops being stale, and the one the predicate answers by
     * <strong>status</strong> rather than by stamp: a GENERATING batch the outcome sink takes to
     * GENERATED is out of {@code PENDING, GENERATING} altogether, and a batch holding a document is
     * never matched at any age because somebody is owed e-mails about that document. Staged the
     * same way, so the property is pinned by the arrangement and not by which thread the scheduler
     * happened to favour.
     */
    @Test
    void a_document_arriving_at_the_row_the_release_waits_for_leaves_the_batch_alone() {
        final UUID courtCentre = UUID.randomUUID();
        final Instant cutoff = cutoff();
        final RegisterBatch batch = staleBatch(courtCentre, true);
        final AtomicReference<StaleReleaseOutcome> answered = new AtomicReference<>();

        final Escapes escaped = whileTheRowIsHeld(batch.batchId(),
                () -> answered.set(store.failAndReleaseStale(cutoff, cutoff)),
                () -> store.markGenerated(batch.batchId(), UUID.randomUUID(), Instant.now(),
                        CompletedBy.EVENT));

        theBatchWasLeftAlone(courtCentre, cutoff, batch, escaped, answered.get(),
                new Ending("GENERATED", null));
        settleTheNight(courtCentre);
    }

    /**
     * Everything the two staged re-check rounds ask, which is that nothing at all happened.
     *
     * @param courtCentre this round's court centre
     * @param cutoff      the cutoff this round's pass was given, which is the fence itself
     * @param batch       the batch the holder moved out of the pass's reach
     * @param escaped     whatever the pass and the holder's move threw
     * @param answered    what the pass answered with, or {@code null} where it refused
     * @param expected    where the holder left the batch, which is where it must still be
     */
    private void theBatchWasLeftAlone(final UUID courtCentre, final Instant cutoff,
            final RegisterBatch batch, final Escapes escaped, final StaleReleaseOutcome answered,
            final Ending expected) {
        softly.assertThat(escaped.release())
                .as("nothing escapes the pass: a batch that stopped being stale under it is a "
                        + "batch it did not change, and never a refusal it has to carry (FR-003a)")
                .isEmpty();
        softly.assertThat(escaped.outcome())
                .as("and the move the holder made is not refused either - it held the row the "
                        + "pass is waiting for, so it is the winner by construction")
                .isEmpty();
        softly.assertThat(releasedOf(answered))
                .as("the batch the holder moved while the pass was blocked on its row is not "
                        + "released: the staleness rule is re-evaluated against the row version "
                        + "the holder committed, which is what READ COMMITTED does for an UPDATE "
                        + "that waited - and the rule is in the UPDATE's own WHERE precisely so "
                        + "that it can be. A predicate computed into a list beforehand would fail "
                        + "a batch whose render had been accepted in between")
                .doesNotContain(batch.batchId());
        softly.assertThat(contendedOf(answered))
                .as("nor is it reported contended: contention is every attempt losing the race "
                        + "for the day's key, and this batch was refused nothing - it simply "
                        + "matched nothing, which is zero rows and an answer")
                .doesNotContain(batch.batchId());
        softly.assertThat(endingOf(batch.batchId()))
                .as("so the batch stands exactly where the holder left it, rather than being "
                        + "given up on by a run that had already read it as stale")
                .isEqualTo(expected);
        softly.assertThat(stampedTo(batch.batchId()))
                .as("and both of its registers are still its own: a release that ran anyway would "
                        + "have handed them back out of a batch that is still going to produce a "
                        + "document with them in it")
                .isEqualTo(2L);
        softly.assertThat(prematurelyFailed(courtCentre, cutoff))
                .as("which is the fence read the way the whole suite reads it: no batch is given "
                        + "up on that its own stamp says was not stale when the write landed")
                .isZero();
        softly.assertThat(strandedRegisters(courtCentre))
                .as("and no register is left awaiting a document while stamped to a batch nothing "
                        + "will finish")
                .isZero();
    }

    /**
     * The third contender: the hearing re-shared while the pass is giving its register back.
     *
     * <p>Not a race for the batch, which is why it is a case of its own. A re-share is a register
     * the estate sent and this service records it whatever a batch is doing, so neither contender
     * refuses the other - what they contend for is the <em>key</em>: one hearing, one court centre,
     * one register date may hold exactly one active unbatched register
     * ({@code idx_output_active_register_key}). The pass clearing a stale register's stamp and the
     * recorder writing the register that replaces it are two writes into that one key.
     *
     * <p>So the property is that a re-share is <strong>superseded against, never unstamped
     * beside</strong>, wherever its commit lands - including inside the pass's own statement, where
     * the statement's snapshot cannot see it. A release refused by that index is a store refusal
     * rather than a state-machine one, and FR-003a is written about the operation: no single
     * batch's outcome may end the run, and a refusal escaping the pass ends it for every court
     * centre.
     */
    @Test
    void a_re_share_racing_the_release_is_superseded_rather_than_unstamped() {
        final List<Ending> endings = new ArrayList<>();

        for (final Order order : RESHARE_ROUNDS) {
            final UUID courtCentre = UUID.randomUUID();
            final UUID reshared = UUID.randomUUID();
            final Instant cutoff = cutoff();
            final RegisterBatch batch =
                    staleBatch(courtCentre, reshared, UUID.randomUUID(), true);
            final Escapes escaped = raced(order, batch.batchId(),
                    () -> store.failAndReleaseStale(cutoff, cutoff),
                    () -> record(courtCentre, reshared));

            final Ending ending = endingOf(batch.batchId());
            settleTheNight(courtCentre);
            endings.add(ending);
            assertInvariants(courtCentre, order, cutoff, escaped);
            softly.assertThat(activeRegistersOf(courtCentre, reshared))
                    .as("%s: and the hearing holds exactly one register the day is still to "
                            + "render. Two would put one hearing's youth defendants on the court "
                            + "centre's document twice; none would lose the register the re-share "
                            + "replaced it with", order)
                    .isEqualTo(1L);
        }

        softly.assertThat(endings)
                .as("a re-share changes nothing about the batch, so every order ends the same way: "
                        + "the batch the run gave up on is failed under the one bounded reason that "
                        + "means the passage of time, and the register it held is accounted for by "
                        + "supersession rather than by being handed back beside its replacement")
                .containsOnly(new Ending("FAILED", NOT_COMPLETED));
    }

    /**
     * The batch no attempt can release, beside a batch that is released anyway.
     *
     * <p>The other end of the retry the round above is about. A re-share that lands inside one
     * attempt's window is a race the attempt after it wins, because that attempt reads a fresh
     * snapshot with the re-share in it. This round holds the key against <em>every</em> attempt, so
     * no fresh snapshot helps, and what the store does then is the whole of FR-003a: the batch is
     * left exactly as it was found and <strong>reported</strong> as contended, beside the batches
     * that were released.
     *
     * <p><strong>How the key is taken again inside every attempt, without a stopwatch.</strong>
     * There is no arrangement of <em>rows</em> that can do it any more, and that is a property of
     * the statement rather than a gap in this suite: a share of the key committed before the
     * statement begins is in its snapshot, so the release either supersedes it - where the register
     * being given back is the later one - or is superseded against it, and either way the key ends
     * with one active row. The only refusal left is a share that commits <em>after</em> the
     * snapshot and before the write, on every one of the three attempts, and chaining three of
     * those windows by timing was built and abandoned at review gate 3: the gap between a refused
     * attempt's rollback and the next holder taking the row cannot be closed from the test.
     *
     * <p>So it is staged by the database instead. An {@code AFTER UPDATE} trigger, scoped by
     * {@code WHEN} to this round's hearing and dropped in a {@code finally}, takes the day's active
     * register back the moment the release gives it up - an existing superseded share of the same
     * key, invisible to the statement's snapshot, made active again inside the attempt's own
     * transaction. Every attempt therefore meets {@code idx_output_active_register_key} on the day's
     * key, and rolls back with the share it met, which is exactly what a re-share committing inside
     * each window would produce and exactly the refusal the retry is bounded for. What is staged is
     * the refusal; what is asserted is what the operation does with a batch it cannot release, and
     * that is the contract this round is for.
     *
     * <p><strong>Why the second court centre is here, and why it is on the following day.</strong>
     * It is the isolation itself. One statement over every stale batch takes the other court
     * centre's release down with the refusal it met on this one - so one hearing shared out of
     * order would cost every court centre in the country its document that night. Each batch is its
     * own statement, its own transaction and its own bounded retry, so the ending of one says
     * nothing about the ending of another.
     *
     * <p>Which of the two is <em>reached</em> first is the part a round cannot leave to chance. The
     * pass reads its batches {@code ORDER BY register_date, batch_id}, so two batches on one day are
     * walked in the order two random identities happen to fall in, and a pass that stopped at the
     * batch it could not release - a return, a break, or an exception on exhaustion - would be
     * caught only on the runs where the contended one came first. The released batch is therefore
     * stood on the <strong>day after</strong> the contended one, which puts it after it in the walk
     * on every run, and what this round asserts is that the pass goes on.
     *
     * <p>And nothing escapes. The pass runs inline in the night's generation, so an exhaustion
     * raised out of the store would end the run before it assembled anything - the same cost by a
     * different route. The contended batch simply waits: it is stale still, the next run reaches it
     * again, and the 07:00 report names its court centre day as a late batch every morning until it
     * is released.
     */
    @Test
    void a_batch_no_attempt_can_release_is_reported_while_the_others_are_released() {
        final UUID contendedCentre = UUID.randomUUID();
        final UUID otherCentre = UUID.randomUUID();
        final UUID heldKey = UUID.randomUUID();
        final RegisterBatch contended =
                staleBatch(contendedCentre, heldKey, UUID.randomUUID(), true);
        final RegisterBatch other = staleBatch(otherCentre, true, TUESDAY_SHARED);
        final Instant cutoff = cutoff();
        final AtomicReference<StaleReleaseOutcome> answered = new AtomicReference<>();

        withTheKeyTakenBackInsideEveryAttempt(contendedCentre, heldKey, () -> {
            final List<Throwable> escaped =
                    escaping(() -> answered.set(store.failAndReleaseStale(cutoff, cutoff)));

            softly.assertThat(escaped)
                    .as("nothing escapes the pass, whatever became of any one batch (FR-003a). An "
                            + "exhaustion raised out of the store would end the night's generation "
                            + "before a single court centre had been assembled")
                    .isEmpty();
            softly.assertThat(contendedOf(answered.get()))
                    .as("the batch every attempt lost the key race for is reported instead, so the "
                            + "pass counts it and says so rather than the run failing over it")
                    .contains(contended.batchId())
                    .doesNotContain(other.batchId());
            softly.assertThat(releasedOf(answered.get()))
                    .as("and it is reported in the other list from the batches that were "
                            + "released, because a batch the pass could not release is not a "
                            + "batch it released")
                    .doesNotContain(contended.batchId())
                    .contains(other.batchId());
            softly.assertThat(endingOf(contended.batchId()))
                    .as("the contended batch is left exactly as it was found - still awaiting "
                            + "its render, still stale, and reachable by the run that follows. A "
                            + "batch failed without its registers coming back is the stranded "
                            + "register this increment exists to end")
                    .isEqualTo(new Ending("GENERATING", null));
            softly.assertThat(stampedTo(contended.batchId()))
                    .as("so both of its registers are still its own, rather than one of them given "
                            + "back and the other kept")
                    .isEqualTo(2L);
            softly.assertThat(endingOf(other.batchId()))
                    .as("while the other court centre's day is failed anyway, under the one "
                            + "bounded reason that means the passage of time")
                    .isEqualTo(new Ending("FAILED", NOT_COMPLETED));
            softly.assertThat(stampedTo(other.batchId()))
                    .as("and its registers are back, which is what puts them in tonight's batch")
                    .isZero();
            softly.assertThat(strandedRegisters(contendedCentre))
                    .as("no register of the contended day is left awaiting a document while "
                            + "stamped to a batch nothing will finish, because nothing about that "
                            + "batch moved")
                    .isZero();
            softly.assertThat(strandedRegisters(otherCentre))
                    .as("nor any of the released day's, because its failure and its release "
                            + "were one act")
                    .isZero();
        });
    }

    /**
     * The per-batch isolation held against a caller that is already inside a transaction.
     *
     * <p>The round above proves each batch is accounted for separately when the pass is called the
     * way the night calls it - outside any transaction of the caller's. That is a precondition, and
     * a precondition nothing enforces is a comment: a caller that wrapped the pass in a
     * {@code TransactionTemplate} or an outer {@code @Transactional} would have every attempt join
     * <em>its</em> transaction, and the whole of FR-003a would be gone without a line of this
     * adapter changing. One batch's refusal would abort the surrounding transaction, the attempts
     * after it would be made inside an aborted one, and every court centre already released would
     * be rolled back with them.
     *
     * <p>So the operation is not asked to be called correctly, it is made correct: each attempt runs
     * in a transaction of its own, suspending whatever the caller had open. This round is what says
     * so. The pass is called from inside a surrounding transaction that is then rolled back, with
     * the released batch stood on the <strong>earlier</strong> day so it is walked before the batch
     * no attempt can release - the reverse of the round above, because what is asserted here is
     * that a release already made is not taken back by what follows it.
     *
     * <p>Every assertion about a row is read after the surrounding transaction has ended, which is
     * what makes "committed" the claim rather than "written": a release that had joined the
     * caller's transaction is gone by then.
     */
    @Test
    void a_release_is_committed_though_the_callers_transaction_rolls_back() {
        final UUID releasedCentre = UUID.randomUUID();
        final UUID contendedCentre = UUID.randomUUID();
        final UUID heldKey = UUID.randomUUID();
        final RegisterBatch released = staleBatch(releasedCentre, true);
        final RegisterBatch contended = staleBatch(contendedCentre, heldKey, UUID.randomUUID(),
                true, TUESDAY_SHARED);
        final Instant cutoff = cutoff();
        final AtomicReference<StaleReleaseOutcome> answered = new AtomicReference<>();

        withTheKeyTakenBackInsideEveryAttempt(contendedCentre, heldKey, TUESDAY_SHARED, () -> {
            final List<Throwable> escaped = escaping(() -> transactions.execute(surrounding -> {
                answered.set(store.failAndReleaseStale(cutoff, cutoff));
                surrounding.setRollbackOnly();
                return null;
            }));

            softly.assertThat(escaped)
                    .as("nothing escapes the pass because the caller happened to be inside a "
                            + "transaction. Attempts folded into the caller's would leave the "
                            + "second and third made inside a transaction the first refusal had "
                            + "already aborted, and what escapes then is not a refusal anything "
                            + "was written to read")
                    .isEmpty();
            softly.assertThat(releasedOf(answered.get()))
                    .as("the batch the pass could release is still reported released, and the "
                            + "batch it could not is still only contended")
                    .contains(released.batchId())
                    .doesNotContain(contended.batchId());
            softly.assertThat(contendedOf(answered.get()))
                    .as("which is the same account the pass gives outside a transaction: the "
                            + "surrounding one is the caller's business and none of the store's")
                    .contains(contended.batchId())
                    .doesNotContain(released.batchId());
        });

        softly.assertThat(endingOf(released.batchId()))
                .as("and the release is committed, read back after the surrounding transaction "
                        + "rolled back. A release that had joined it would be gone - a night that "
                        + "reported registers handed back and handed back none, which is the "
                        + "stranded register this increment exists to end wearing a report saying "
                        + "otherwise")
                .isEqualTo(new Ending("FAILED", NOT_COMPLETED));
        softly.assertThat(stampedTo(released.batchId()))
                .as("with its registers genuinely back, and not merely back inside a transaction "
                        + "nobody committed")
                .isZero();
        softly.assertThat(endingOf(contended.batchId()))
                .as("while the batch every attempt was refused over is left exactly as it was "
                        + "found, its own rollback having taken nothing else with it")
                .isEqualTo(new Ending("GENERATING", null));
        softly.assertThat(stampedTo(contended.batchId()))
                .as("so both of its registers are still its own")
                .isEqualTo(2L);
    }

    /**
     * Runs the body with the day's active register taken back inside every attempt at it.
     *
     * <p>A re-share of the hearing is recorded and then superseded by hand, so the statement's
     * snapshot sees a key with one active row and nothing to decide; an {@code AFTER UPDATE}
     * trigger then makes that share active again the moment the release clears the stamp of the
     * register it is giving back, inside the attempt's own transaction. The second active row is
     * refused by {@code idx_output_active_register_key}, the attempt rolls back with the share it
     * met, and the next attempt - a fresh snapshot, and the same trigger - meets it again.
     *
     * <p><strong>Scoped by {@code WHEN} to this round's hearing</strong>, so the several suites
     * sharing this container are untouched by it, and <strong>dropped in a {@code finally}</strong>,
     * because a trigger left behind would refuse the release of any batch holding a register of
     * that hearing for every suite after it. Nothing is left held once it is gone: the share is
     * superseded still, so the batch is releasable by the next run like any other.
     *
     * @param courtCentre this round's court centre
     * @param hearingId   the hearing whose key is taken back, and the only rows the trigger sees
     * @param refused     the pass, which is expected to meet the refusal on every attempt
     */
    private void withTheKeyTakenBackInsideEveryAttempt(final UUID courtCentre, final UUID hearingId,
            final Runnable refused) {
        withTheKeyTakenBackInsideEveryAttempt(courtCentre, hearingId, MONDAY_SHARED, refused);
    }

    /**
     * The same, where the round's contended batch is not on this suite's own day.
     *
     * <p>The share held against the key has to fall on the <em>batch's</em> register date, because
     * the key is {@code (hearing, court centre, register date)} and a share on another day is
     * another key entirely - one the release never touches, so the trigger would fire against
     * nothing and the round would go green having staged no refusal at all.
     *
     * @param courtCentre this round's court centre
     * @param hearingId   the hearing whose key is taken back, and the only rows the trigger sees
     * @param shared      the moment the estate stamped the batch's registers, which is the day the
     *                    share held against the key has to be stamped on too
     * @param refused     the pass, which is expected to meet the refusal on every attempt
     */
    private void withTheKeyTakenBackInsideEveryAttempt(final UUID courtCentre, final UUID hearingId,
            final Instant shared, final Runnable refused) {
        record(courtCentre, hearingId, shared.plus(LATER_SHARE));
        final UUID share = supersededByHand(courtCentre, hearingId);
        final String name = "test_only_retake_" + courtCentre.toString().replace("-", "");
        ProcessedLogTestSupport.jdbcClient()
                .sql("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ "
                        + "BEGIN UPDATE processed_output SET status = 'RECORDED', "
                        + "superseded_at = NULL, superseded_by = NULL, updated_at = now() "
                        + "WHERE output_id = '" + share + "'; RETURN NULL; END; $$")
                .update();
        ProcessedLogTestSupport.jdbcClient()
                .sql("CREATE TRIGGER " + name + " AFTER UPDATE ON processed_output FOR EACH ROW "
                        + "WHEN (OLD.batch_id IS NOT NULL AND NEW.batch_id IS NULL "
                        + "AND NEW.hearing_id = '" + hearingId + "') "
                        + "EXECUTE FUNCTION " + name + "()")
                .update();
        try {
            refused.run();
        } finally {
            ProcessedLogTestSupport.jdbcClient()
                    .sql("DROP TRIGGER " + name + " ON processed_output")
                    .update();
            ProcessedLogTestSupport.jdbcClient().sql("DROP FUNCTION " + name + "()").update();
        }
    }

    /**
     * Supersedes the share just recorded, so the statement's snapshot has nothing to decide.
     *
     * <p>Written by hand rather than by a later re-share, because a re-share would supersede it
     * <em>against</em> a successor and the trigger would then make a superseded row active beside
     * one - a key with two active rows before the pass had done anything, which is not the
     * arrangement being staged.
     *
     * @param courtCentre this round's court centre
     * @param hearingId   the hearing whose active share is put out of the way
     * @return the share, which is what the trigger takes the key back with
     */
    private UUID supersededByHand(final UUID courtCentre, final UUID hearingId) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        UPDATE processed_output
                           SET status = 'SUPERSEDED', superseded_at = now(), updated_at = now()
                         WHERE court_centre_id = :courtCentre
                           AND hearing_id = :hearingId
                           AND batch_id IS NULL
                           AND superseded_at IS NULL
                        RETURNING output_id
                        """)
                .param("courtCentre", courtCentre)
                .param("hearingId", hearingId)
                .query(UUID.class)
                .single();
    }

    /**
     * The same race lost once, which is the race the attempt that follows wins.
     *
     * <p>The other half of the retry, asserted on the answer rather than on the rows: one window,
     * one refusal, and the attempt after it reads a snapshot the re-share is in and supersedes
     * against it. The batch is released and nothing is reported contended - a batch that took two
     * attempts is not a batch the pass could not release.
     */
    @Test
    void a_batch_contended_once_is_released_by_the_attempt_that_follows() {
        final UUID courtCentre = UUID.randomUUID();
        final UUID reshared = UUID.randomUUID();
        final Instant cutoff = cutoff();
        final RegisterBatch batch = staleBatch(courtCentre, reshared, UUID.randomUUID(), true);
        final AtomicReference<StaleReleaseOutcome> answered = new AtomicReference<>();

        final Escapes escaped = insideTheWindow(batch.batchId(),
                () -> answered.set(store.failAndReleaseStale(cutoff, cutoff)),
                () -> record(courtCentre, reshared));

        softly.assertThat(escaped.release())
                .as("nothing escapes the pass here either")
                .isEmpty();
        softly.assertThat(contendedOf(answered.get()))
                .as("one lost race is not contention: contention is every attempt losing, and the "
                        + "attempt after this one reads a snapshot the re-share is in")
                .doesNotContain(batch.batchId());
        softly.assertThat(releasedOf(answered.get()))
                .as("so the batch is released, by the attempt that followed the refusal")
                .contains(batch.batchId());
        softly.assertThat(endingOf(batch.batchId()))
                .as("and it ends where every stale batch ends")
                .isEqualTo(new Ending("FAILED", NOT_COMPLETED));
        softly.assertThat(activeRegistersOf(courtCentre, reshared))
                .as("with the re-shared hearing holding exactly one register the day is still to "
                        + "render: the stale one was superseded against its replacement rather "
                        + "than handed back beside it")
                .isEqualTo(1L);
    }

    /** The batches an answer named as released, or nothing where the operation refused. */
    private static List<UUID> releasedOf(final StaleReleaseOutcome answered) {
        return answered == null ? List.of()
                : answered.released().stream().map(ReleasedBatch::batchId).toList();
    }

    /**
     * The batches an answer named as contended, or nothing where the operation refused.
     *
     * <p>Null is answered as nothing rather than thrown on, so a red run reports the assertion that
     * was being made and not the seam that had not been implemented yet.
     *
     * @param answered what the operation answered with, or {@code null} where it refused
     * @return the contended batches it named
     */
    private static List<UUID> contendedOf(final StaleReleaseOutcome answered) {
        return answered == null ? List.of() : answered.contended();
    }

    /** How many registers are still stamped to a batch, which is what a release clears. */
    private long stampedTo(final UUID batchId) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("SELECT count(*) FROM processed_output WHERE batch_id = :batchId")
                .param("batchId", batchId)
                .query(Long.class)
                .single();
    }

    /**
     * The staged round whose loser is known, and whose refusal must therefore be there at all.
     *
     * <p>{@link #assertInvariants} says that whatever the losing contender threw was the state
     * machine's own refusal, and it says it with {@code allSatisfy} - which is true of an empty
     * list. For the {@code TOGETHER} rounds that is all that can honestly be asked: either
     * contender may win, the winner throws nothing, and teaching the fixture which one won is
     * exactly the decision {@code Order} declines to make for a race. For
     * {@link Order#RELEASE_FIRST} the winner is known by construction - the batch is FAILED before
     * the state-machine move is made - so the refusal is not merely well-formed if it happens, it
     * must exist. Without this, a {@code markRequested} or a {@code markGenerated} that quietly
     * moved nothing against a FAILED batch would leave the round green and the self-healing half
     * of it pinned by nothing: the listener rethrows, the broker redelivers, and the sink then
     * reads a batch it may no longer move.
     *
     * @param order   the order this round's contenders were let go in
     * @param escaped whatever the two contenders threw
     */
    private void theRefusalExists(final Order order, final Escapes escaped) {
        if (order != Order.RELEASE_FIRST) {
            return;
        }
        softly.assertThat(escaped.outcome())
                .as("%s: and the refusal is there to be well-formed. The pass went first, so the "
                        + "batch was FAILED before the move was made and the state machine had to "
                        + "refuse it; a move that silently did nothing would leave the broker "
                        + "nothing to redeliver and the outcome unaccounted for", order)
                .hasSize(1);
    }

    /**
     * Everything SC-009 asks of a round, whichever contender won it.
     *
     * @param courtCentre this round's court centre
     * @param order       the order its two contenders were let go in, named in every failure
     * @param cutoff      the cutoff this round's pass was given, which is the fence itself
     * @param escaped     whatever the two contenders threw
     */
    private void assertInvariants(final UUID courtCentre, final Order order, final Instant cutoff,
            final Escapes escaped) {
        softly.assertThat(escaped.release())
                .as("%s: **no single batch's outcome may end the run** (FR-003a). The pass is run "
                        + "inline in the night's generation, so a refusal escaping it costs every "
                        + "court centre its document - which is exactly what a read-then-mark "
                        + "shape does the moment the outcome sink commits between its two steps",
                        order)
                .isEmpty();
        softly.assertThat(escaped.outcome())
                .as("%s: and the contender that loses is refused by the state machine and by "
                        + "nothing else. That refusal is the self-healing path - the listener "
                        + "rethrows it, the broker redelivers, and the sink reads a batch it may "
                        + "no longer move", order)
                .allSatisfy(thrown -> softly.assertThat(thrown)
                        .isInstanceOf(IllegalStateException.class));
        softly.assertThat(prematurelyFailed(courtCentre, cutoff))
                .as("%s: and no batch is given up on that was not stale by the rule. The fence is "
                        + "the predicate itself, re-evaluated against the row as it stands when "
                        + "the write lands; a rule computed into a list beforehand would fail a "
                        + "batch whose render had been accepted in between - orphaning that render "
                        + "and rendering the same court centre day twice", order)
                .isZero();
        softly.assertThat(strandedRegisters(courtCentre))
                .as("%s: no register is left awaiting a document while stamped to a batch nothing "
                        + "will finish. Unbatched means batch_id IS NULL, so such a row is "
                        + "invisible to every later run and to every command - the lost register "
                        + "this increment exists to end", order)
                .isZero();
        softly.assertThat(liveBatches(courtCentre))
                .as("%s: at most one batch of the key is still owed something, because two would "
                        + "render and e-mail one court centre day twice", order)
                .isLessThanOrEqualTo(1L);
        softly.assertThat(aggregatesPerAddress(courtCentre))
                .as("%s: the day's one Youth Offending Team holds exactly one aggregate for the "
                        + "register date (SC-003), whichever way the race went - one, because the "
                        + "night is settled before this is read and a day nobody was told about "
                        + "would satisfy 'never twice' by having told nobody at all", order)
                .containsExactly(1L);
        softly.assertThat(liveRegisters(courtCentre))
                .as("%s: and both registers are still the day's, rather than one of them having "
                        + "been superseded or lost by a race nothing shared a key with", order)
                .isEqualTo(2L);
    }

    /**
     * A court centre day with two registers, batched and aged past the cutoff.
     *
     * @param courtCentre this round's court centre
     * @param requested   whether the batch has had its render requested, so GENERATING rather than
     *                    PENDING
     * @return the batch as the row stood at assembly
     */
    private RegisterBatch staleBatch(final UUID courtCentre, final boolean requested) {
        return staleBatch(courtCentre, UUID.randomUUID(), UUID.randomUUID(), requested);
    }

    /**
     * The same, on a register date of the round's choosing rather than this suite's own day.
     *
     * @param courtCentre this round's court centre
     * @param requested   whether the batch has had its render requested, so GENERATING rather than
     *                    PENDING
     * @param shared      the moment the estate stamped its registers, which decides its day
     * @return the batch as the row stood at assembly
     */
    private RegisterBatch staleBatch(final UUID courtCentre, final boolean requested,
            final Instant shared) {
        return staleBatch(courtCentre, UUID.randomUUID(), UUID.randomUUID(), requested, shared);
    }

    /**
     * The same, where a round has to know which hearing it is about to share again.
     *
     * @param courtCentre this round's court centre
     * @param first       the hearing whose register the round names
     * @param second      the other hearing of the day, so the batch is a batch and not a row
     * @param requested   whether the batch has had its render requested, so GENERATING rather than
     *                    PENDING
     * @return the batch as the row stood at assembly
     */
    private RegisterBatch staleBatch(final UUID courtCentre, final UUID first, final UUID second,
            final boolean requested) {
        return staleBatch(courtCentre, first, second, requested, MONDAY_SHARED);
    }

    /**
     * The same, on a register date the round chooses, so it knows where in the walk the batch sits.
     *
     * <p>The pass reads its stale batches {@code ORDER BY register_date, batch_id}, so the day a
     * round stands a batch on is the only part of that order it can state. A round asserting what
     * the pass does with the batches <em>after</em> one it could not release has to stand them on a
     * later day, or it is asserting it in about half of its runs.
     *
     * @param courtCentre this round's court centre
     * @param first       the hearing whose register the round names
     * @param second      the other hearing of the day, so the batch is a batch and not a row
     * @param requested   whether the batch has had its render requested, so GENERATING rather than
     *                    PENDING
     * @param shared      the moment the estate stamped both shares, which decides the register date
     * @return the batch as the row stood at assembly
     */
    private RegisterBatch staleBatch(final UUID courtCentre, final UUID first, final UUID second,
            final boolean requested, final Instant shared) {
        record(courtCentre, first, shared);
        record(courtCentre, second, shared);
        final List<RegisterRecord> waiting = waiting(courtCentre);
        final RegisterBatch batch = store.assemble(
                batchFor(courtCentre, waiting, LocalDate.ofInstant(shared, LONDON)), waiting);
        if (requested) {
            store.markRequested(batch.batchId(), UUID.randomUUID());
        }
        ageBatch(batch.batchId());
        return batch;
    }

    /**
     * The rest of the night, run after the race so the invariants are asserted on a settled day.
     *
     * <p>Two halves, and which of them has anything to do is exactly what the race decided. A batch
     * the race left still owed something - PENDING, GENERATING or holding a document - is walked to
     * its ending and notified once, as the renderer and the notifier do it; registers the pass gave
     * back are assembled into tonight's batch and walked the same way, as the same run does it.
     *
     * <p><strong>Every round settles, and that is what makes the aggregate invariant a claim.</strong>
     * A round whose batch was left GENERATING used to have neither half to run, so the day ended
     * with no notification at all and "no Youth Offending Team holds two aggregates" was satisfied
     * by holding none. A render this service did not give up on is one that goes on to produce its
     * document and its e-mail, so the fixture finishes it rather than stopping where the race did.
     *
     * @param courtCentre this round's court centre
     */
    private void settleTheNight(final UUID courtCentre) {
        store.batchesOn(MONDAY).stream()
                .filter(batch -> courtCentre.equals(batch.courtCentreId()))
                .filter(batch -> LIVE.contains(batch.status().name()))
                .forEach(this::finished);
        final List<RegisterRecord> given = waiting(courtCentre);
        if (!given.isEmpty()) {
            finished(store.assemble(batchFor(courtCentre, given), given));
        }
    }

    /**
     * A batch walked from wherever the race left it to its document, and told about once.
     *
     * @param batch the batch as it stood when it was read
     */
    private void finished(final RegisterBatch batch) {
        if (batch.status() == BatchStatus.PENDING) {
            store.markRequested(batch.batchId(), UUID.randomUUID());
        }
        if (batch.status() != BatchStatus.GENERATED) {
            store.markGenerated(batch.batchId(), UUID.randomUUID(), Instant.now(),
                    CompletedBy.EVENT);
        }
        notified(batch);
    }

    /** One aggregate for the day's one Youth Offending Team, and the batch settled on its tally. */
    private void notified(final RegisterBatch batch) {
        notifications.insert(new RegisterNotification(UUID.randomUUID(), batch.batchId(),
                YOT_ADDRESS, "Wandsworth Youth Offending Team", "cr_standard", UUID.randomUUID(),
                NotificationStatus.ACCEPTED, 201, Instant.now(), 1));
        store.markNotified(batch.batchId(), new NotificationSummary(1, 0, BatchStatus.NOTIFIED));
    }

    /**
     * What escaped each of the two contenders, kept apart because only one of them may throw.
     *
     * @param release what the pass threw, which must be nothing whoever won the race
     * @param outcome what the render acceptance or the document arrival threw
     */
    private record Escapes(List<Throwable> release, List<Throwable> outcome) {
    }

    /**
     * Runs the two contenders in this round's order, and answers with whatever escaped each.
     *
     * @param order   which is let go first, whether both are let go at once, or whether the
     *                contender is committed inside the pass's own statement
     * @param batchId the batch the round is raced over, which the staged window holds by the row
     * @param release the pass
     * @param outcome the render acceptance, the document arrival or the re-share racing it
     * @return what each of them threw
     */
    private Escapes raced(final Order order, final UUID batchId, final Runnable release,
            final Runnable outcome) {
        return switch (order) {
            case RELEASE_FIRST -> sequentially(release, outcome, true);
            case OUTCOME_FIRST -> sequentially(outcome, release, false);
            case TOGETHER -> concurrently(release, outcome);
            case INSIDE_THE_WINDOW -> insideTheWindow(batchId, release, outcome);
        };
    }

    /**
     * The contender committed after the pass's statement began and before its write landed.
     *
     * <p><strong>Why this round exists.</strong> {@code failAndReleaseStale} is one statement, and
     * a statement reads one snapshot: every one of its {@code WITH} clauses sees the table as it
     * stood when the statement began. A register re-shared after that moment is therefore not a
     * successor the statement can find, however plainly it is one by the time the write lands - and
     * the write then clears the stale register's stamp into a second active row for the key, which
     * {@code idx_output_active_register_key} refuses. That refusal is not a state-machine refusal
     * the pass can read as "this batch is no longer stale": it is the store refusing a row, and run
     * inline in the night's generation it would cost every court centre its document (FR-003a).
     *
     * <p><strong>How the window is held open.</strong> The pass's first act is to write the batch
     * row, so a transaction holding that row {@code FOR UPDATE} stops the statement there - after
     * its snapshot and before its release. The re-share is committed against that held statement,
     * the row is let go, and the pass finishes reading a snapshot the re-share is not in. Nothing
     * here reaches into the store: the lock is on the same table the statement writes, taken the
     * way any other session would take it.
     *
     * @param batchId the batch whose row is held while the contender commits
     * @param release the pass, run on a thread of its own because it is deliberately blocked
     * @param outcome the contender, committed while the pass is held
     * @return what each of them threw
     */
    private Escapes insideTheWindow(final UUID batchId, final Runnable release,
            final Runnable outcome) {
        final AtomicInteger holder = new AtomicInteger();
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch committed = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            final Future<?> holding = pool.submit(
                    () -> holdingTheBatchRow(batchId, holder, held, committed, () -> {}));
            held.await();
            final Future<List<Throwable>> passing = pool.submit(() -> escaping(release));
            awaitHeldBy(holder.get());
            final List<Throwable> escaped = escaping(outcome);
            committed.countDown();
            holding.get(RACE_SECONDS, TimeUnit.SECONDS);
            return new Escapes(passing.get(RACE_SECONDS, TimeUnit.SECONDS), escaped);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the staged window was interrupted", interrupted);
        } catch (ExecutionException | TimeoutException unfinished) {
            throw new IllegalStateException("a contender never finished", unfinished);
        }
    }

    /**
     * Holds the batch's row until the latch is counted down, and says which backend holds it.
     *
     * @param batchId      the batch whose row is held
     * @param holder       where the holding session's backend id is published, so the pass can be
     *                     waited for by what is blocking it rather than by a sleep
     * @param held         counted down once the row is genuinely held
     * @param committed    waited on, so the transaction ends only when the contender is done
     * @param beforeCommit what the holder does inside its own transaction before letting the row
     *                     go, which is nothing where the contender is a session of its own
     */
    private void holdingTheBatchRow(final UUID batchId, final AtomicInteger holder,
            final CountDownLatch held, final CountDownLatch committed,
            final Runnable beforeCommit) {
        transactions.executeWithoutResult(oneTransaction -> {
            holder.set(ProcessedLogTestSupport.jdbcClient()
                    .sql("SELECT pg_backend_pid()")
                    .query(Integer.class)
                    .single());
            ProcessedLogTestSupport.jdbcClient()
                    .sql("SELECT batch_id FROM register_batch WHERE batch_id = :batchId FOR UPDATE")
                    .param("batchId", batchId)
                    .query(UUID.class)
                    .single();
            held.countDown();
            try {
                if (!committed.await(RACE_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the contender never committed");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("the held row was interrupted", interrupted);
            }
            beforeCommit.run();
        });
    }

    /**
     * The contender committed <strong>by the session holding the row the pass is waiting for</strong>.
     *
     * <p>The difference from {@link #insideTheWindow} is which session moves the batch, and it is
     * the whole point of the fixture. There the contender is a session of its own and the held row
     * is only a way of keeping the pass's statement open; here the holder <em>is</em> the
     * contender, so the pass cannot possibly reach the row until the move has committed. What that
     * buys is a re-check with no timing in it: the pass takes its snapshot, blocks on the row, and
     * is granted a version of it that the staleness rule no longer matches.
     *
     * <p>Under READ COMMITTED that is exactly the case an {@code UPDATE} handles by re-evaluating
     * its own {@code WHERE} against the newly committed row version - which is why the staleness
     * rule lives in the {@code UPDATE}'s predicate rather than in a clause that feeds it, and which
     * no {@code TOGETHER} round can pin, because a round that depends on two threads landing inside
     * the same few microseconds is asserting the scheduler's goodwill.
     *
     * @param batchId the batch whose row is held, and which the holder then moves
     * @param release the pass, run on a thread of its own because it is deliberately blocked
     * @param moved   the state-machine move, made inside the holder's own transaction
     * @return what each of them threw
     */
    private Escapes whileTheRowIsHeld(final UUID batchId, final Runnable release,
            final Runnable moved) {
        final AtomicInteger holder = new AtomicInteger();
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch move = new CountDownLatch(1);
        final AtomicReference<List<Throwable>> byTheHolder = new AtomicReference<>(List.of());
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            final Future<?> holding = pool.submit(() -> holdingTheBatchRow(batchId, holder, held,
                    move, () -> byTheHolder.set(escaping(moved))));
            held.await();
            final Future<List<Throwable>> passing = pool.submit(() -> escaping(release));
            awaitHeldBy(holder.get());
            move.countDown();
            holding.get(RACE_SECONDS, TimeUnit.SECONDS);
            return new Escapes(passing.get(RACE_SECONDS, TimeUnit.SECONDS), byTheHolder.get());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the held row was interrupted", interrupted);
        } catch (ExecutionException | TimeoutException unfinished) {
            throw new IllegalStateException("a contender never finished", unfinished);
        }
    }

    /** Waits until some session is blocked by the one holding the row, which is the pass. */
    private void awaitHeldBy(final int holder) {
        await().atMost(Duration.ofSeconds(RACE_SECONDS))
                .pollInterval(Duration.ofMillis(POLL_MILLIS))
                .until(() -> blockedBy(holder) > 0);
    }

    /** How many sessions are waiting on the session holding the batch row. */
    private long blockedBy(final int holder) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT count(*)
                          FROM pg_stat_activity
                         WHERE :holder = ANY (pg_blocking_pids(pid))
                        """)
                .param("holder", holder)
                .query(Long.class)
                .single();
    }

    /** One after the other, so the winner is the one named rather than the one that got there. */
    private static Escapes sequentially(final Runnable first, final Runnable second,
            final boolean releaseFirst) {
        final List<Throwable> from = escaping(first);
        final List<Throwable> then = escaping(second);
        return releaseFirst ? new Escapes(from, then) : new Escapes(then, from);
    }

    /**
     * Both at once, off one latch, so either may win and the invariants have to hold regardless.
     *
     * <p>A pool of two and a latch both threads wait on, rather than two threads started in turn:
     * the second start would otherwise be the head start, and the round would be a staged order
     * wearing a race's clothes.
     */
    private static Escapes concurrently(final Runnable release, final Runnable outcome) {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            final CountDownLatch start = new CountDownLatch(1);
            final Future<List<Throwable>> first = pool.submit(() -> {
                start.await();
                return escaping(release);
            });
            final Future<List<Throwable>> second = pool.submit(() -> {
                start.await();
                return escaping(outcome);
            });
            start.countDown();
            return new Escapes(first.get(RACE_SECONDS, TimeUnit.SECONDS),
                    second.get(RACE_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the race was interrupted", interrupted);
        } catch (ExecutionException | TimeoutException unfinished) {
            throw new IllegalStateException("a contender never finished", unfinished);
        }
    }

    /**
     * Runs one contender and collects what it threw, because a refusal is a result here.
     *
     * <p>The loser of a race is refused, and the suite's whole subject is what that refusal leaves
     * behind. Collected rather than propagated, and asserted on by
     * {@link #assertInvariants(UUID, Order, Instant, Escapes)} - which is also what keeps the red
     * run an assertion while the operation is still a seam.
     */
    private static List<Throwable> escaping(final Runnable contender) {
        final Throwable refused = catchThrowable(contender::run);
        return refused == null ? List.of() : List.of(refused);
    }

    /** The registers of this court centre's day that are still waiting to be batched. */
    private List<RegisterRecord> waiting(final UUID courtCentre) {
        return store.activeUnbatched().stream()
                .filter(record -> courtCentre.equals(record.key().courtCentreId()))
                .toList();
    }

    /** The batch the assembler would make of them, asked for by the schedule. */
    private RegisterBatch batchFor(final UUID courtCentre, final List<RegisterRecord> records) {
        return batchFor(courtCentre, records, MONDAY);
    }

    /**
     * The same, on the day the registers were recorded for.
     *
     * <p>A batch's key has to be the key its registers hold, or {@code assemble} refuses them as
     * another day's - so a round standing a batch on a later day says so in both places.
     *
     * @param courtCentre  this round's court centre
     * @param records      the registers the batch is made of
     * @param registerDate the day they were recorded for
     * @return the batch as the assembler would ask for it
     */
    private RegisterBatch batchFor(final UUID courtCentre, final List<RegisterRecord> records,
            final LocalDate registerDate) {
        return new RegisterBatch(UUID.randomUUID(), courtCentre, null, null, registerDate,
                records.isEmpty() ? null : records.getFirst().fileName(), null, null,
                BatchStatus.PENDING, null, null, true, null, null, null, null, null, null, 0,
                null, 0);
    }

    /** A cutoff in the past, so a round can only reach the batch it aged itself. */
    private static Instant cutoff() {
        return Instant.now().minus(STALE_AFTER);
    }

    /** Ages a batch's two in-flight stamps, as the night between two runs does. */
    private void ageBatch(final UUID batchId) {
        final int aged = ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        UPDATE register_batch
                           SET assembled_at = assembled_at - make_interval(secs => :age),
                               requested_at = requested_at - make_interval(secs => :age)
                         WHERE batch_id = :batchId
                        """)
                .param("age", (double) LAST_NIGHT.toSeconds())
                .param("batchId", batchId)
                .update();
        if (aged != ONE_BATCH) {
            throw new IllegalStateException(
                    "expected one batch to age for " + batchId + ", aged " + aged);
        }
    }

    /** A batch given up on although its own stamp says it was not stale when the write landed. */
    private long prematurelyFailed(final UUID courtCentre, final Instant cutoff) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT count(*)
                          FROM register_batch
                         WHERE court_centre_id = :courtCentre
                           AND status = 'FAILED'
                           AND failure_reason = :reason
                           AND COALESCE(requested_at, assembled_at) > :cutoff
                        """)
                .param("courtCentre", courtCentre)
                .param("reason", NOT_COMPLETED)
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }

    /** A register still awaiting its document while stamped to a batch nothing will finish. */
    private long strandedRegisters(final UUID courtCentre) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT count(*)
                          FROM processed_output waiting
                          JOIN register_batch dead ON dead.batch_id = waiting.batch_id
                         WHERE waiting.court_centre_id = :courtCentre
                           AND waiting.status = 'RECORDED'
                           AND waiting.superseded_at IS NULL
                           AND dead.status NOT IN ('PENDING', 'GENERATING', 'GENERATED')
                        """)
                .param("courtCentre", courtCentre)
                .query(Long.class)
                .single();
    }

    /** How many batches of this court centre's day are still owed something. */
    private long liveBatches(final UUID courtCentre) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT count(*)
                          FROM register_batch
                         WHERE court_centre_id = :courtCentre AND register_date = :registerDate
                           AND status IN (:live)
                        """)
                .param("courtCentre", courtCentre)
                .param("registerDate", MONDAY)
                .param("live", LIVE)
                .query(Long.class)
                .single();
    }

    /** How many aggregates each recipient of this court centre's day holds. */
    private List<Long> aggregatesPerAddress(final UUID courtCentre) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT count(*)
                          FROM register_notification told
                          JOIN register_batch batch ON batch.batch_id = told.batch_id
                         WHERE batch.court_centre_id = :courtCentre
                           AND batch.register_date = :registerDate
                         GROUP BY told.email_address
                        """)
                .param("courtCentre", courtCentre)
                .param("registerDate", MONDAY)
                .query(Long.class)
                .list();
    }

    /** How many registers one hearing of this court centre's day still has, in whatever state. */
    private long activeRegistersOf(final UUID courtCentre, final UUID hearingId) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT count(*)
                          FROM processed_output
                         WHERE court_centre_id = :courtCentre
                           AND hearing_id = :hearingId
                           AND superseded_at IS NULL
                        """)
                .param("courtCentre", courtCentre)
                .param("hearingId", hearingId)
                .query(Long.class)
                .single();
    }

    /** How many of this court centre's registers are still the day's, in whatever state. */
    private long liveRegisters(final UUID courtCentre) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT count(*)
                          FROM processed_output
                         WHERE court_centre_id = :courtCentre AND superseded_at IS NULL
                        """)
                .param("courtCentre", courtCentre)
                .query(Long.class)
                .single();
    }

    /**
     * Where the round left the batch it raced over, read before the night is settled.
     *
     * <p>Before, because settling it moves a GENERATED batch on to NOTIFIED and the question here
     * is what the race decided, not what the notifier did afterwards.
     *
     * @param status        the state the race left the batch in
     * @param failureReason the bounded reason where it ended FAILED, and null otherwise
     */
    private record Ending(String status, String failureReason) {
    }

    private Ending endingOf(final UUID batchId) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("SELECT status, failure_reason FROM register_batch WHERE batch_id = :batchId")
                .param("batchId", batchId)
                .query((rs, row) -> new Ending(rs.getString("status"),
                        rs.getString("failure_reason")))
                .single();
    }

    /** One hearing's register, recorded the way the pipeline records it. */
    private void record(final UUID courtCentre, final UUID hearingId) {
        record(courtCentre, hearingId, MONDAY_SHARED);
    }

    /**
     * The same, where a round has to say when the estate stamped the share.
     *
     * <p>{@code register_time} is the results' own moment and the order the store's supersession is
     * decided by, so a share stamped before the register a batch already holds is a share delivered
     * out of order - recorded active, because the batched register is not the recorder's to
     * supersede, and never a successor, because the release's search is the mirror of the same
     * ordering.
     *
     * @param courtCentre  this round's court centre
     * @param hearingId    the hearing the share is of
     * @param registerTime the moment the estate stamped it, which decides both orderings
     */
    private void record(final UUID courtCentre, final UUID hearingId, final Instant registerTime) {
        final DistributionCommand command = new DistributionCommand(
                ProcessedLogTestSupport.SOURCE, UUID.randomUUID(), hearingId,
                LocalDate.ofInstant(registerTime, LONDON), registerTime, "Hearing_Resulted");
        ProcessedLogTestSupport.repository(LEASE).insertNew(command,
                RequestFingerprint.of(command),
                new RunClaim(command.source(), command.requestId(), "runner-1", UUID.randomUUID(),
                        "msg-1"));
        store.recordAndComplete(command, document(courtCentre, hearingId, registerTime), OU_CODE,
                APPLICANT, RecordedFlagState.ON,
                () -> new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));
    }

    private CourtRegisterDocument document(final UUID courtCentre, final UUID hearingId,
            final Instant registerTime) {
        return new CourtRegisterDocument(
                registerTime.toString(),
                HEARING_DATE.toString(),
                hearingId.toString(),
                courtCentre.toString(),
                "court-register_" + MONDAY + '_' + OU_CODE + '_' + hearingId + ".pdf",
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
