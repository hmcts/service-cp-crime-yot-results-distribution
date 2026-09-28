package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import uk.gov.hmcts.cp.yotresultsdistribution.application.NotificationSummary;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RecordOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RecordedCompletion;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterStore;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ReleasedBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.application.StaleReleaseOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.application.StaleReleaseProgress;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletedBy;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtCentreDay;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterHearingVenue;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GuardDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ProcessedOutputClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotRecordedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotReleasedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterRecord;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestFingerprint;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;

/**
 * The register store, against a real Postgres.
 *
 * <p>This is the port that replaces the POST. Everything increment 001 assembled now ends as a row
 * here, and three properties decide whether the register a Youth Offending Team eventually receives
 * is the right one: the recording is atomic with the supersession it causes, a row that is already
 * on its way to a PDF is never rewritten underneath the renderer, and a completion belongs to the
 * batch it was reported for and to no other.
 *
 * <p>The two endings a batch can have are asserted beside them, because both write
 * {@code processed_output} as well as {@code register_batch} and neither is reachable from a mocked
 * store: a failure gives its rows back to the next run only for the two reasons that say nothing
 * ever left this service, and a notification tally moves the rows of the batch it settles and of no
 * other, under all three of the endings a tally can produce.
 *
 * <p>The last of those is defect fix <strong>P3</strong>, pinned here by
 * {@link Generation#generation_flips_only_the_batchs_own_rows()}. Progression flips rows by court
 * centre ({@code CourtRegisterRequestRepository}'s read-side sweep), so a document generated for
 * Monday marks Tuesday's rows generated too and Tuesday's register is never sent at all. The test
 * seeds two days at one court centre, generates one of them, and counts: the count is what fails
 * against the progression behaviour, and no assertion about a single day could.
 *
 * <p><strong>Soft assertions throughout, deliberately.</strong> Under the TDD red-run convention
 * this class is written before {@link JdbcRegisterStore} has any statements in it, and every port
 * call therefore refuses. A hard assertion would stop each case at the first refusal and the
 * recorded red would be a stack trace from the arrangement rather than the property under test. Each
 * case instead drives the whole scenario through the port inside one
 * {@code assertThatCode(...).doesNotThrowAnyException()} - which records the refusal rather than
 * swallowing it - and then asserts on rows read straight back out of the database, so the red run is
 * the row count and the green run is the same assertions unchanged.
 *
 * <p>Every case mints its own court centre, so the several suites sharing one container share no
 * rows and none of them needs to truncate a table another is using. The store's own reads answer for
 * the whole table, so {@link #mine(List)} narrows them to the case that asked.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("register store")
class RegisterStoreIT {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static final Duration LEASE = Duration.ofMinutes(5);

    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 24);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 8, 25);

    private static final Instant MONDAY_SHARED = Instant.parse("2026-08-24T09:00:00Z");
    private static final Instant MONDAY_RESHARED = Instant.parse("2026-08-24T16:30:00Z");
    private static final Instant MONDAY_RESHARED_AGAIN = Instant.parse("2026-08-24T16:45:00Z");
    private static final Instant TUESDAY_SHARED = Instant.parse("2026-08-25T09:00:00Z");

    private static final Instant HEARING_DATE = Instant.parse("2026-08-19T00:00:00Z");

    private static final UUID HEARING_ONE = UUID.fromString("1828f356-f746-4f2d-932b-79ef2df95c80");
    private static final UUID HEARING_TWO = UUID.fromString("6b0d5a1f-4c8e-4a92-8f31-2d7c6e05b114");
    private static final UUID HEARING_THREE =
            UUID.fromString("c41e9a37-05b2-4f6d-9e18-7a3b2c5d8064");
    private static final UUID HEARING_FOUR = UUID.fromString("9d2c7b48-3e15-4a70-b6c9-0f8e1d4a2537");

    private static final UUID PAYLOAD_FILE_ID =
            UUID.fromString("5e08b6d1-92a7-4c33-8f10-6b4d3e79a281");
    private static final UUID SECOND_PAYLOAD_FILE_ID =
            UUID.fromString("0c6a4f18-b573-4d29-8e04-95f2a7c31b6e");

    /**
     * A day's first batch and its supplement, under identities the day-read case fixed.
     *
     * <p>The supplement's sorts before the base's, so a read ordered by identity alone would answer
     * them the other way round. Every other case lets {@link #assembled} mint its own.
     */
    private static final UUID BASE_BATCH_ID =
            UUID.fromString("f2b7a45c-9e10-4d83-8a62-c507b1934ade");

    private static final UUID SUPPLEMENT_BATCH_ID =
            UUID.fromString("104c8e63-27b5-49f1-a0d8-3e6b95c72f40");

    private static final UUID DOCUMENT_FILE_ID =
            UUID.fromString("3a7f1c92-6d84-4b05-9e73-1c2b8a4e07d5");
    private static final UUID SECOND_DOCUMENT_FILE_ID =
            UUID.fromString("7b53d0e4-1a86-4f97-b2c0-48e6d9f13a52");

    private static final Instant GENERATED_AT = Instant.parse("2026-08-24T18:04:11Z");

    /** A render's duration, chosen so a wrong zone or mapping lands as a wrong number. */
    private static final Duration ROUND_TRIP = Duration.ofSeconds(97);

    /** systemdocgenerator's own words about a failure, which the row keeps and no log prints. */
    private static final String SDG_REASON = "template OEE_Layout5 rendered no pages";

    /** The same words, from a renderer that said far more of them than the column holds. */
    private static final String OVERSIZED_SDG_REASON = (SDG_REASON + "; ").repeat(30);

    private static final String OU_CODE = "B01LY00";

    /** The bound parameter every batch-keyed read below names the identity by. */
    private static final String BATCH_ID = "batchId";

    /** The single batch row a fixture ageing a batch is expected to touch. */
    private static final int ONE_BATCH = 1;

    /**
     * What a day's second document is filed under, as {@code BatchAssembler} builds the name.
     *
     * <p>The first register's file name with {@code -supplementary-1} before the extension, which is
     * the assembler's decision and the store's to record rather than to make.
     */
    private static final String SUPPLEMENTARY_FILE_NAME =
            "court-register_2026-08-24_B01LY00-supplementary-1.pdf";

    /** Two re-shares of one hearing, which is the smallest number that can lose the invariant. */
    private static final int RACERS = 2;

    /** How long the suite waits for both recorders to reach the register they are superseding. */
    private static final Duration PARKED_DEADLINE = Duration.ofSeconds(10);

    private static final Duration PARKED_POLL = Duration.ofMillis(20);

    private static final String APPLICANT = "Applicant";
    private static final String RESPONDENT = "Respondent";

    /**
     * The day the rollback cases work in, which is deliberately nobody else's.
     *
     * <p><strong>The one write in this suite that is not scoped to a court centre.</strong> A
     * rollback takes back a <em>period</em>, so its statement reads the whole table and its answer
     * is a count of the whole table - and every suite sharing this container records registers of
     * its own. A period any of those fell in would have this suite superseding their rows and
     * counting them, which is a failure in whichever suite ran next and a count that depends on how
     * many other cases had run.
     *
     * <p>So the period sits before every register any suite records: the earliest of those is
     * {@code RecordEndToEndIT}'s {@code 2020-06-01T10:00:00Z}. A suite that later records one
     * earlier than this day has to move this day, and the count assertions below are what will say
     * so.
     */
    private static final LocalDate ROLLBACK_DAY = LocalDate.of(2019, 1, 7);

    /** A register shared inside the period a rollback takes back. */
    private static final Instant ROLLBACK_SHARED = Instant.parse("2019-01-07T09:00:00Z");

    /**
     * The instant a rollback takes the period back to, which the operator states and nothing
     * defaults.
     *
     * <p>Noon on that day, so the morning share is inside the period and the afternoon re-share is
     * outside it; the register shared <em>at</em> it is what makes the bound's exclusiveness
     * visible.
     */
    private static final Instant ROLLBACK_BOUND = Instant.parse("2019-01-07T12:00:00Z");

    /** A register shared after the bound, which every case here leaves active behind it. */
    private static final Instant ROLLBACK_AFTER = Instant.parse("2019-01-07T16:30:00Z");

    /**
     * A completion that could not be written, which is the pod dying between the two writes.
     *
     * <p>{@link StoreUnavailableException} rather than an invented type: the guard writes through
     * the same package this store does, so a completion that fails because the store went away
     * arrives as the signal that package raises for it.
     */
    private static final Supplier<GuardDecision> COMPLETION_THAT_FAILED = () -> {
        throw new StoreUnavailableException("the completion could not be written",
                new DataAccessResourceFailureException("connection reset by peer"));
    };

    /**
     * A completion the guard declined to write: this runner's claim was reclaimed while it worked.
     *
     * <p>Not an exception - the guard answers rather than throws - and the answer is the whole of
     * the decision the store has to act on.
     */
    private static final Supplier<GuardDecision> COMPLETION_REFUSED =
            () -> new GuardDecision.Abandon(ReasonCode.STALE_RUNNER);

    /**
     * The completion an ordinary case hands the store: admitted, and writing nothing of its own.
     *
     * <p>The store's contract is about what the completion <em>answers</em> and whether it throws -
     * the commit boundary is the store's, the meaning of a completion is the guard's - so a case
     * that is not about the completion says the least it can and still exercises the transaction
     * the recording runs in.
     */
    private static final Supplier<GuardDecision> COMPLETED =
            () -> new GuardDecision.Complete(ReasonCode.RUN_COMPLETED);

    private static final String RECORDED = "RECORDED";
    private static final String SUPERSEDED = "SUPERSEDED";
    private static final String GENERATING = "GENERATING";
    private static final String GENERATED = "GENERATED";
    private static final String NOTIFIED = "NOTIFIED";
    private static final String PARTIALLY_NOTIFIED = "PARTIALLY_NOTIFIED";
    private static final String NOTIFIED_NOBODY = "NOTIFIED_NOBODY";
    private static final String FAILED = "FAILED";

    /** The status a batch is assembled into, spelled out because {@code PENDING} is taken here. */
    private static final String PENDING_STATUS = "PENDING";

    /** The index one hearing's active register is kept unique by, named where a case asserts it. */
    private static final String ACTIVE_REGISTER_KEY = "idx_output_active_register_key";

    /** The bounded classification a release refused by a key it does not account for carries. */
    private static final String UNACCOUNTED_KEY = "a unique key";

    /** The bounded classification a release refused by a rule that is no key at all carries. */
    private static final String UNACCOUNTED_RULE = "an integrity rule that is no key";

    /** The package no refusal may still be travelling as by the time it crosses the port. */
    private static final String SPRING_DAO = "org.springframework.dao";

    /**
     * The words Postgres quotes a whole refused row under, which a register's row must never carry.
     *
     * <p>{@code FAIL_AND_RELEASE_STALE} writes {@code processed_output}, and a
     * {@code processed_output} row holds the register document itself - so a CHECK constraint
     * refusing one is reported with every value of that row on the detail line.
     */
    private static final String FAILING_ROW = "Failing row contains";

    /** The words Postgres quotes a refused <em>key</em>'s values under, which name the row too. */
    private static final String REFUSED_KEY_DETAIL = "already exists";

    /** The state increment 001 writes an output row in before it POSTs the register. */
    private static final String POST_PENDING = "PENDING";

    /**
     * The digest a POST row carries, which is of the bytes that were sent rather than of a document.
     *
     * <p>Any lower-case SHA-256 will do - {@code ProcessedOutputClaim} refuses anything else - and
     * this one is deliberately none of the digests the recorder writes.
     */
    private static final String POST_DIGEST =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /** The refusal every port call makes until T015 replaces it with a statement. */
    private static final String PENDING = "T015 implements the register store; this is its red run";

    /**
     * What an unexpected refusal means in the suites written after T015 landed.
     *
     * <p>Their arrangements walk the ordinary path a batch walks - assemble, request, generate - so
     * a refusal out of one of them is the statement under test saying no, not a fixture that needs
     * fixing, and the description says which of the two the reader is looking at.
     */
    private static final String WALKED =
            "the arrangement is the path every batch walks; a refusal here is the store's answer";

    /**
     * The refusal the four statements a person's own command asks make until they are implemented.
     *
     * <p>Nothing in the nightly flow reaches them - a by-day read, a release, the complement of the
     * batching predicate and the rollback's write are all typed by an operator - so they were left
     * as seams by the phase that wired the commands, and these are their red runs.
     */
    private static final String SEAM =
            "the operations CLI's own statements land here; this is their red run";

    @InjectSoftAssertions
    private SoftAssertions softly;

    /** This case's court centre: minted per test so no case can read another's rows. */
    private final UUID courtCentre = UUID.randomUUID();

    private final RegisterStore store =
            new JdbcRegisterStore(ProcessedLogTestSupport.jdbcClient(),
                    ProcessedLogTestSupport.transactionManager());

    @BeforeAll
    static void migrate() {
        PostgresTestSupport.applyFlyway();
    }

    /**
     * A recorded register is the row the whole downstream half is written against, so the two things
     * that decide what a batch can do with it - what it says, and that there is exactly one of it -
     * are asserted before anything else.
     *
     * <p>And the third thing, which is what a <em>second</em> delivery of one command makes of it.
     * The recording and the completion of the command are one transaction, so a delivery cannot
     * stop between them - but it can stop after both, before the broker learns the message was
     * settled, and the message is delivered again; 001's POST path met the same shape and answered
     * it with {@code ON CONFLICT (source, request_id)}. Recording is idempotent on the command's
     * own key for the same reason: a redelivery that met {@code processed_output_unique_request}
     * instead would fail a command whose register is recorded and active, and would go on failing
     * it until the broker parked it.
     *
     * <p>That the two writes are one transaction is asserted here from both sides - a completion
     * that could not be written and one the guard refused each take the register back with them -
     * and from the broker's side by {@code CrashWindowIT}.
     */
    @Nested
    @DisplayName("recording a register")
    class Recording {

        @Test
        void recording_a_register_should_write_one_recorded_row_for_the_command() {
            final DistributionCommand command = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatCode(() -> record(
                            command,
                            document(HEARING_ONE, MONDAY, MONDAY_SHARED),
                            APPLICANT,
                            RecordedFlagState.ON))
                    .as(PENDING)
                    .doesNotThrowAnyException();

            softly.assertThat(rowsAtCourtCentre())
                    .as("one command, one register: the row is written in the transaction that "
                            + "completes the command, and nothing is sent to progression")
                    .isEqualTo(1);
            softly.assertThat(statusOf(command))
                    .as("RECORDED is where a register waits for 18:00, not PENDING - which in this "
                            + "store still means a POST that has not settled")
                    .contains(RECORDED);
        }

        /**
         * Everything the batch half reads, read back through the port that will read it.
         *
         * <p>The document above all: it is the thing that was validated, and the payload mapper
         * renders it rather than re-deriving anything from the hearing, so a store that dropped a
         * field here would produce a PDF nobody could tell was wrong.
         */
        @Test
        void a_recorded_register_should_read_back_with_its_hearing_document_and_flag_state() {
            final DistributionCommand command = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final CourtRegisterDocument document = document(HEARING_ONE, MONDAY, MONDAY_SHARED);

            softly.assertThatCode(() -> record(
                            command, document, RESPONDENT, RecordedFlagState.ON))
                    .as(PENDING)
                    .doesNotThrowAnyException();

            final List<RegisterRecord> active = activeUnbatched();
            softly.assertThat(active)
                    .extracting(RegisterRecord::hearingId, RegisterRecord::hearingDate,
                            RegisterRecord::registerTime, RegisterRecord::defendantType,
                            RegisterRecord::flagState)
                    .as("the hearing, the instant that decides which re-share wins, the defendant "
                            + "type the register is rendered under, and the flag as it stood")
                    .containsExactly(tuple(HEARING_ONE, HEARING_DATE, MONDAY_SHARED, RESPONDENT,
                            RecordedFlagState.ON));
            softly.assertThat(active)
                    .extracting(RegisterRecord::key, RegisterRecord::fileName,
                            RegisterRecord::document)
                    .as("the key the row is batched under is the London day of the register "
                            + "instant, and the document is stored exactly as it will be rendered")
                    .containsExactly(tuple(new CourtCentreDay(courtCentre, MONDAY),
                            fileName(HEARING_ONE, MONDAY), document));
        }

        /**
         * The one fact the batch reads off a row that the document does not carry.
         *
         * <p>{@code assemble} copies the OU code from the batch's first row into
         * {@code register_batch}, where the render payload and the file name are built from it. The
         * recorder is the only writer that ever knows it - the transformation resolves it from
         * reference data and 001 carries it on {@code ProcessedOutputClaim} for exactly this reason
         * - so a recording that dropped it would leave every batch of the new shape addressed under
         * a court centre nothing downstream can name.
         */
        @Test
        void a_recorded_register_should_carry_the_court_centre_ou_code_the_batch_needs() {
            final DistributionCommand command = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                store.recordAndComplete(command, document(HEARING_ONE, MONDAY, MONDAY_SHARED),
                        OU_CODE, APPLICANT, RecordedFlagState.ON, COMPLETED);
                assembled(MONDAY, mine(store.activeUnbatched()));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(ouCodeOf(command))
                    .as("the OU code the transformation resolved, on the row that recorded it")
                    .contains(OU_CODE);
            softly.assertThat(batchOuCodeOn(MONDAY))
                    .as("and therefore on the batch, which takes it from its first row and has "
                            + "nowhere else to get it from")
                    .contains(OU_CODE);
        }

        /**
         * The redelivery of a command whose register is already recorded.
         *
         * <p>The recording and the completion of the command are separate statements, so a pod that
         * dies between them - or a completion that fails transiently - leaves the register written
         * and the request unfinished, and the broker delivers the message again. What the second
         * delivery must find is the register the first one wrote: one row, still active, answered
         * under the identity it already has.
         */
        @Test
        void a_redelivered_command_is_answered_with_the_register_it_already_recorded() {
            final DistributionCommand command = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final CourtRegisterDocument document = document(HEARING_ONE, MONDAY, MONDAY_SHARED);
            final AtomicReference<RecordOutcome> first = new AtomicReference<>();
            final AtomicReference<RecordOutcome> redelivered = new AtomicReference<>();

            softly.assertThatCode(() -> {
                first.set(record(command, document, APPLICANT, RecordedFlagState.ON));
                redelivered.set(record(command, document, APPLICANT, RecordedFlagState.ON));
            }).as(PENDING).doesNotThrowAnyException();

            softly.assertThat(rowsAtCourtCentre())
                    .as("one command, one register, however many times the broker delivers it")
                    .isEqualTo(1);
            softly.assertThat(statusOf(command))
                    .as("and it is still the active register: a redelivery neither supersedes it "
                            + "nor moves it out of the nightly sweep")
                    .contains(RECORDED);
            softly.assertThat(redelivered.get())
                    .as("the second delivery is answered with the row the first one wrote, so the "
                            + "run it belongs to can be completed rather than failed and retried "
                            + "until the queue parks a register that is safely recorded")
                    .isEqualTo(first.get());
        }

        /**
         * The same redelivery, of the re-share that replaced an earlier register.
         *
         * <p>The supersession is the part that must not happen twice. A second run of the statement
         * would find the register this command already recorded, supersede <em>it</em>, and record a
         * third row - so the hearing would end the day with a register nobody asked for and an
         * answer naming a row that was never dropped.
         */
        @Test
        void a_redelivered_re_share_supersedes_nothing_a_second_time() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand reshare = seededCommand(HEARING_ONE, MONDAY_RESHARED);
            final CourtRegisterDocument resharedDocument =
                    document(HEARING_ONE, MONDAY, MONDAY_RESHARED);
            final AtomicReference<RecordOutcome> redelivered = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(reshare, resharedDocument, APPLICANT, RecordedFlagState.ON);
                redelivered.set(
                        record(reshare, resharedDocument, APPLICANT, RecordedFlagState.ON));
            }).as(PENDING).doesNotThrowAnyException();

            softly.assertThat(rowsAtCourtCentre())
                    .as("two registers and no more: the redelivery supersedes nothing and records "
                            + "nothing")
                    .isEqualTo(2);
            softly.assertThat(statusOf(first))
                    .as("the register the re-share replaced is superseded once, by the recording "
                            + "that replaced it")
                    .contains(SUPERSEDED);
            softly.assertThat(statusOf(reshare)).contains(RECORDED);
            softly.assertThat(redelivered.get())
                    .as("and the answer is the recording that already happened, the row it "
                            + "superseded included")
                    .isEqualTo(new RecordOutcome(outputIdOf(reshare).orElse(null),
                            outputIdOf(first).orElse(null)));
        }

        /**
         * The same redelivery, once a second register has also come to name the one it answers with.
         *
         * <p>Two rows can point at one register, and the store writes them both itself. A re-share
         * supersedes the register it replaces and names itself on it; and an <em>earlier</em> share
         * that arrives afterwards - a redelivery that overtook the register it belongs behind, or
         * the loser of a race between two deliveries - is recorded SUPERSEDED against that same
         * later register. Both of them then carry it in {@code superseded_by}.
         *
         * <p>So a read of "the row naming this one" is a read of two rows, and the redelivery it is
         * for - a pod that stopped between the recording and the completion - is exactly when it is
         * asked. What the redelivery must be answered with is the register this recording actually
         * replaced, which is the answer its first delivery was given; a refusal here would fail a
         * command whose register is recorded and active, on every delivery, until the broker parked
         * it.
         */
        @Test
        void a_redelivered_re_share_is_answered_though_a_later_arrival_names_it_too() {
            final DistributionCommand shared = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand reshare = seededCommand(HEARING_ONE, MONDAY_RESHARED_AGAIN);
            final DistributionCommand overtaken = seededCommand(HEARING_ONE, MONDAY_RESHARED);
            final CourtRegisterDocument resharedDocument =
                    document(HEARING_ONE, MONDAY, MONDAY_RESHARED_AGAIN);
            final AtomicReference<RecordOutcome> redelivered = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(shared, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(reshare, resharedDocument, APPLICANT, RecordedFlagState.ON);
                record(overtaken, document(HEARING_ONE, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);
                redelivered.set(record(reshare, resharedDocument, APPLICANT,
                        RecordedFlagState.ON));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(statusOf(overtaken))
                    .as("the earlier results that arrived late do not displace the register that "
                            + "replaced them")
                    .contains(SUPERSEDED);
            softly.assertThat(supersessionOf(overtaken))
                    .as("and they name that register, which is the second row to do so")
                    .hasValueSatisfying(pair -> softly.assertThat(pair.supersededBy())
                            .isEqualTo(outputIdOf(reshare).orElse(null)));
            softly.assertThat(redelivered.get())
                    .as("the redelivery is still answered with the register the re-share itself "
                            + "replaced, and not refused because a later arrival names the same row")
                    .isEqualTo(new RecordOutcome(outputIdOf(reshare).orElse(null),
                            outputIdOf(shared).orElse(null)));
            softly.assertThat(rowsAtCourtCentre())
                    .as("three commands, three registers: the redelivery records nothing")
                    .isEqualTo(3);
        }

        /**
         * The recording and the completion of the command stand or fall together.
         *
         * <p>The data model says the row is written RECORDED in the transaction that completes the
         * command, and this is what that sentence is worth: a completion that could not be written
         * takes the register back with it. Two statements committing separately would leave the
         * register behind and the request unfinished, and the broker would deliver the command
         * again into a store that already holds its answer - safe, because the recording is
         * idempotent on the command, but only because of that. Here there is no window at all.
         */
        @Test
        void a_completion_that_could_not_be_written_takes_the_recording_with_it() {
            final DistributionCommand command = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatThrownBy(() -> store.recordAndComplete(command,
                            document(HEARING_ONE, MONDAY, MONDAY_SHARED), OU_CODE, APPLICANT,
                            RecordedFlagState.ON, COMPLETION_THAT_FAILED))
                    .as("the completion failed and nothing hid it: the signal reaches the caller, "
                            + "which is the only place that can stop intake")
                    .isInstanceOf(StoreUnavailableException.class);

            softly.assertThat(rowsAtCourtCentre())
                    .as("and no register survives it - the insert and the completion are one "
                            + "transaction, so a completion that did not happen is a recording "
                            + "that did not happen")
                    .isZero();
            softly.assertThat(statusOf(command))
                    .as("there is no row at all, rather than a row in some intermediate state")
                    .isEmpty();
        }

        /**
         * The same rule, for the completion the guard refuses rather than fails on.
         *
         * <p>A claim reclaimed while this run worked means another delivery owns the request, and
         * the guard writes nothing. A register left behind by that run is a register the new owner
         * records again and this one only supersedes: two rows for one hearing, one of them
         * belonging to a run whose outcome was discarded. The transaction takes it back, and the
         * decision the caller settles on is the guard's own.
         */
        @Test
        void a_completion_the_guard_refused_takes_the_recording_with_it() {
            final DistributionCommand command = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final AtomicReference<RecordedCompletion> answered = new AtomicReference<>();

            softly.assertThatCode(() -> answered.set(store.recordAndComplete(command,
                            document(HEARING_ONE, MONDAY, MONDAY_SHARED), OU_CODE, APPLICANT,
                            RecordedFlagState.ON, COMPLETION_REFUSED)))
                    .as("a refusal is an answer rather than a failure, and it comes back as one")
                    .doesNotThrowAnyException();

            softly.assertThat(answered.get())
                    .extracting(RecordedCompletion::completion)
                    .as("the guard's decision reaches the caller unchanged; the store decides what "
                            + "becomes of the row, not what becomes of the delivery")
                    .isEqualTo(new GuardDecision.Abandon(ReasonCode.STALE_RUNNER));
            softly.assertThat(rowsAtCourtCentre())
                    .as("and the register goes back with the completion nobody admitted")
                    .isZero();
        }

        /**
         * A refusal the recorder does not account for, which is not the race and is not retried.
         *
         * <p>The recorder knows two unique keys and settles both itself:
         * {@code processed_output_unique_request} is this command arriving beside itself and is
         * answered from the row that landed, and {@code idx_output_active_register_key} is the race
         * for the day's active register and is read again and recorded against the winner. Reading
         * every <em>other</em> duplicate-key refusal as the race spends three attempts on a
         * constraint no attempt can satisfy and then reports contention - a transient failure the
         * broker redelivers - for a register that will be refused in exactly the same way on every
         * delivery until the queue parks it under an exhaustion nobody can explain.
         *
         * <p>The constraint is made rather than imagined: a unique index over {@code court_house},
         * partial on this case's own court centre so no other suite can meet it, refuses the second
         * of two registers for two different hearings at the same court house. Neither key the
         * recorder knows is touched - the hearings differ and so do the commands - so what the
         * store meets is precisely the case this asserts.
         */
        @Test
        void a_unique_violation_that_is_not_the_active_row_race_is_propagated_not_retried() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand second = seededCommand(HEARING_TWO, MONDAY_SHARED);

            softly.assertThatCode(() -> record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED),
                            APPLICANT, RecordedFlagState.ON))
                    .as(WALKED)
                    .doesNotThrowAnyException();
            withACourtHouseUniqueIndex(() -> softly
                    .assertThatThrownBy(() -> record(second,
                            document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                            RecordedFlagState.ON))
                    .as("a constraint the recorder does not account for is the store answering "
                            + "that this row may not be written, which no redelivery changes - not "
                            + "a race to try again and report as contention")
                    .isInstanceOf(RegisterNotRecordedException.class)
                    .isNotInstanceOf(ConcurrencyFailureException.class)
                    .asInstanceOf(InstanceOfAssertFactories.type(RegisterNotRecordedException.class))
                    .extracting(RegisterNotRecordedException::classification)
                    .isEqualTo(FailureClassification.NON_TRANSIENT));

            softly.assertThat(rowsAtCourtCentre())
                    .as("and the refused recording left nothing behind it")
                    .isEqualTo(1);
        }
    }

    /**
     * Supersession, settled in the write transaction rather than swept up on the read side.
     *
     * <p>Progression takes the greatest {@code register_time} per hearing when it generates, which
     * gets the same answer most nights and cannot say, between two reads, which register will not be
     * sent. Here the recording that replaces a register is the event that says so, and the row it
     * replaced is named (research §8).
     */
    @Nested
    @DisplayName("superseding an earlier register")
    class Supersession {

        @Test
        void a_re_share_for_the_same_day_should_supersede_the_hearings_earlier_row() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand second = seededCommand(HEARING_ONE, MONDAY_RESHARED);
            final AtomicReference<RecordOutcome> reshare = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                reshare.set(record(second,
                        document(HEARING_ONE, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON));
            }).as(PENDING).doesNotThrowAnyException();

            softly.assertThat(rowsAtCourtCentre())
                    .as("both registers are kept: the earlier one is evidence of what was assembled "
                            + "before the results were shared again")
                    .isEqualTo(2);
            softly.assertThat(statusOf(first))
                    .as("the later register wins on register_time")
                    .contains(SUPERSEDED);
            softly.assertThat(statusOf(second)).contains(RECORDED);
            softly.assertThat(reshare.get())
                    .as("the row that will not be sent is named rather than counted, or support is "
                            + "left to guess which register the batch dropped")
                    .extracting(RecordOutcome::supersededOutputId)
                    .isEqualTo(outputIdOf(first).orElse(null));
            softly.assertThat(supersessionOf(first))
                    .as("and the pair is on the row, not only in the answer: superseded_at is what "
                            + "takes the register out of the nightly sweep, and superseded_by is "
                            + "the only thing that leads from a dropped register to its replacement")
                    .hasValueSatisfying(pair -> {
                        softly.assertThat(pair.supersededAt()).isNotNull();
                        softly.assertThat(pair.supersededBy())
                                .isEqualTo(outputIdOf(second).orElse(null));
                    });
            softly.assertThat(supersessionOf(second))
                    .as("the register that won carries neither, or the sweep that reads "
                            + "superseded_at IS NULL would leave it out as well and the day would "
                            + "be rendered without the hearing entirely")
                    .hasValueSatisfying(pair -> {
                        softly.assertThat(pair.supersededAt()).isNull();
                        softly.assertThat(pair.supersededBy()).isNull();
                    });
        }

        @Test
        void a_re_share_should_leave_exactly_one_active_row_for_the_hearing_and_day() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand second = seededCommand(HEARING_ONE, MONDAY_RESHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(second, document(HEARING_ONE, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);
            }).as(PENDING).doesNotThrowAnyException();

            softly.assertThat(activeUnbatched())
                    .as("two active rows for one hearing and one day would put the same hearing on "
                            + "one PDF twice")
                    .extracting(RegisterRecord::registerTime)
                    .containsExactly(MONDAY_RESHARED);
        }

        /**
         * The same invariant, put to two recorders at once, which is the only way it can be lost.
         *
         * <p>Which register a re-share replaces is decided by a read: the recording statement looks
         * for the hearing's active row for the day and writes what it found. Two re-shares that
         * both read before either of them has committed therefore both find the same incumbent,
         * both supersede it and both insert an active register - and the day is rendered with one
         * hearing on it twice, under two different sets of results, with nothing in the store
         * saying which of them the Youth Offending Team should believe. One recording at a time can
         * never show this, because the second read always sees the first row: the case above
         * asserts the same property and would stay green throughout.
         *
         * <p>The race is arranged rather than hoped for. The suite takes the incumbent row itself,
         * on its own connection, and only then releases both recorders: each statement reads the
         * incumbent on its own snapshot and then parks on the supersession it is about to make, so
         * neither can commit until the suite lets go and both have therefore read the register they
         * are replacing while it was still the active one. The suite waits for the database to say
         * both are parked rather than for a duration, so what it releases them into is a fact it
         * checked and not a delay it hoped was long enough.
         *
         * <p>Nothing here is the caller's problem. A re-share that loses this race is a message the
         * broker delivered and the pipeline completed, so the store settles it and reports nothing:
         * a duplicate-key failure escaping to the listener would abandon a command whose register
         * is safely recorded, and turn an invariant the database keeps into an outage.
         */
        @Test
        void two_concurrent_re_shares_leave_exactly_one_active_row() {
            final DistributionCommand shared = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand reshared = seededCommand(HEARING_ONE, MONDAY_RESHARED);
            final DistributionCommand resharedAgain =
                    seededCommand(HEARING_ONE, MONDAY_RESHARED_AGAIN);

            softly.assertThatCode(() -> record(shared,
                            document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                            RecordedFlagState.ON))
                    .as(WALKED)
                    .doesNotThrowAnyException();
            final List<Throwable> escaped = raceToRecord(shared, reshared, resharedAgain);

            softly.assertThat(escaped)
                    .as("a re-share that lost the race is still a register this service recorded, "
                            + "and the command that carried it completed: the store settles the "
                            + "collision itself rather than handing the listener a failure")
                    .isEmpty();
            softly.assertThat(activeUnbatched())
                    .as("one hearing, one day, one active register - whichever recorder got there "
                            + "first, the later results are the ones the day is rendered from")
                    .extracting(RegisterRecord::registerTime)
                    .containsExactly(MONDAY_RESHARED_AGAIN);
            softly.assertThat(statusOf(resharedAgain)).contains(RECORDED);
            softly.assertThat(statusOf(reshared))
                    .as("and the earlier of the two is superseded rather than lost or refused")
                    .contains(SUPERSEDED);
            softly.assertThat(supersessionOf(reshared))
                    .as("naming the register that replaced it, which is the only thing that leads "
                            + "from a dropped register to the one the batch will carry")
                    .hasValueSatisfying(pair -> {
                        softly.assertThat(pair.supersededAt()).isNotNull();
                        softly.assertThat(pair.supersededBy())
                                .isEqualTo(outputIdOf(resharedAgain).orElse(null));
                    });
            softly.assertThat(statusOf(shared))
                    .as("the register both of them replaced is superseded exactly once, whichever "
                            + "of them got to it")
                    .contains(SUPERSEDED);
        }

        /**
         * The row a renderer has already been asked about is not the store's to rewrite.
         *
         * <p>Once a row carries a batch, systemdocgenerator has been handed a payload built from it
         * and an event will arrive naming that batch. Superseding it would take a register out of a
         * PDF that already contains it, and the batch's own completion would then flip a row that
         * says it belongs to nothing.
         */
        @Test
        void a_batched_row_should_never_be_superseded_by_a_later_re_share() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand second = seededCommand(HEARING_ONE, MONDAY_RESHARED);
            final AtomicReference<RecordOutcome> reshare = new AtomicReference<>();
            final AtomicReference<Map<String, String>> beforeTheReshare = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                assembled(MONDAY, mine(store.activeUnbatched()));
                beforeTheReshare.set(wholeRowOf(first));
                reshare.set(record(second,
                        document(HEARING_ONE, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON));
            }).as(PENDING).doesNotThrowAnyException();

            softly.assertThat(wholeRowOf(first))
                    .as("every column, read before the re-share and after it: the payload handed "
                            + "to systemdocgenerator was built from this row, so anything at all "
                            + "moving on it - a status, a stamp, updated_at - would mean the "
                            + "document already asked for no longer matches the register it holds")
                    .isEqualTo(beforeTheReshare.get());
            softly.assertThat(statusOf(first))
                    .as("a row on its way to a PDF is left exactly as the payload described it")
                    .contains(RECORDED);
            softly.assertThat(statusOf(second)).contains(RECORDED);
            softly.assertThat(reshare.get())
                    .as("nothing was superseded, so nothing is named")
                    .extracting(RecordOutcome::supersededOutputId)
                    .isNull();
            softly.assertThat(rowsAtCourtCentre()).isEqualTo(2);
        }

        /**
         * The cross-date flip, refused at the write. Two sittings of one hearing on two days are two
         * registers for two court days, and the second is not a correction of the first.
         */
        @Test
        void a_re_share_on_a_later_day_should_start_a_fresh_row_of_its_own() {
            final DistributionCommand monday = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand tuesday = seededCommand(HEARING_ONE, TUESDAY_SHARED);

            softly.assertThatCode(() -> {
                record(monday, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(tuesday, document(HEARING_ONE, TUESDAY, TUESDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
            }).as(PENDING).doesNotThrowAnyException();

            softly.assertThat(statusOf(monday))
                    .as("Monday's register is not superseded by Tuesday's sitting")
                    .contains(RECORDED);
            softly.assertThat(statusOf(tuesday)).contains(RECORDED);
            softly.assertThat(activeUnbatched())
                    .as("both days wait for their own batch")
                    .extracting(RegisterRecord::key)
                    .containsExactlyInAnyOrder(new CourtCentreDay(courtCentre, MONDAY),
                            new CourtCentreDay(courtCentre, TUESDAY));
        }
    }

    /**
     * What the nightly job is allowed to pick up.
     *
     * <p>Three exclusions, and the third is the one that is easy to forget and expensive to get
     * wrong: a register recorded while the cutover flag was not ON may already have been sent by the
     * legacy, and batching it would send a second copy of the same day's register to the same Youth
     * Offending Team (research §12).
     */
    @Nested
    @DisplayName("the registers waiting to be batched")
    class ActiveUnbatched {

        @Test
        void the_unbatched_registers_should_exclude_rows_already_stamped_with_a_batch() {
            final DistributionCommand batched = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand waiting = seededCommand(HEARING_TWO, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(batched, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                assembled(MONDAY, mine(store.activeUnbatched()));
                record(waiting, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
            }).as(PENDING).doesNotThrowAnyException();

            softly.assertThat(activeUnbatched())
                    .as("a batched row is somebody else's work in progress; re-assembling it would "
                            + "put one hearing on two PDFs")
                    .extracting(RegisterRecord::hearingId)
                    .containsExactly(HEARING_TWO);
        }

        @Test
        void the_unbatched_registers_should_exclude_registers_recorded_while_the_flag_was_not_on() {
            final DistributionCommand on = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand off = seededCommand(HEARING_TWO, MONDAY_SHARED);
            final DistributionCommand unknown = seededCommand(HEARING_THREE, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(on, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(off, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.OFF);
                record(unknown, document(HEARING_THREE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.UNKNOWN);
            }).as(PENDING).doesNotThrowAnyException();

            softly.assertThat(rowsAtCourtCentre())
                    .as("all three are recorded; only one of them is this service's to generate")
                    .isEqualTo(3);
            softly.assertThat(activeUnbatched())
                    .as("OFF says the legacy was generating and UNKNOWN says nobody could tell - "
                            + "both are excluded, and both are surfaced by list-batches instead")
                    .extracting(RegisterRecord::hearingId)
                    .containsExactly(HEARING_ONE);
        }
    }

    /**
     * The other half of that predicate, and the only reading a rollback has of what it left behind.
     *
     * <p>{@link ActiveUnbatched} and this are one predicate asked in two directions: RECORDED,
     * unsuperseded and unbatched in both, and the flag state as recorded is what separates them -
     * ON there, anything else here (research §12). A register automatic batching passed over is a
     * register nothing else in this service will ever look at again, so a read that missed one would
     * leave it waiting for somebody to notice it, which is what
     * {@code list-batches --recorded-while-off} exists to prevent (FR-016).
     *
     * <p>UNKNOWN belongs here beside OFF, for the same reason the batching excludes it: a row
     * labelled from a reading that had not come back yet is a row nobody can say the legacy did not
     * also send.
     */
    @Nested
    @DisplayName("the registers automatic batching passed over")
    class RecordedWhileOff {

        @Test
        void the_registers_recorded_while_the_flag_was_not_on_should_come_back_oldest_first() {
            softly.assertThatCode(() -> {
                record(seededCommand(HEARING_ONE, MONDAY_SHARED),
                        document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                // Written second and shared later, so a statement that answered in the order the
                // rows happened to be written would still pass a case seeded oldest first.
                record(seededCommand(HEARING_TWO, MONDAY_RESHARED_AGAIN),
                        document(HEARING_TWO, MONDAY, MONDAY_RESHARED_AGAIN), APPLICANT,
                        RecordedFlagState.OFF);
                record(seededCommand(HEARING_THREE, MONDAY_RESHARED),
                        document(HEARING_THREE, MONDAY, MONDAY_RESHARED), RESPONDENT,
                        RecordedFlagState.UNKNOWN);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(recordedWhileOff())
                    .as("OFF says the legacy was generating and UNKNOWN says nobody could tell, so "
                            + "both are here; the register recorded while the flag was ON is the "
                            + "nightly job's and is not this command's to list")
                    .extracting(RegisterRecord::hearingId)
                    .containsExactly(HEARING_THREE, HEARING_TWO);
        }

        @Test
        void a_superseded_or_batched_register_should_not_be_among_them() {
            final AtomicReference<UUID> batched = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(seededCommand(HEARING_ONE, MONDAY_SHARED),
                        document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.OFF);
                // The re-share replaces the row above, which is the row a listing must not offer a
                // second time under a second set of results.
                record(seededCommand(HEARING_ONE, MONDAY_RESHARED),
                        document(HEARING_ONE, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.OFF);
                batched.set(record(seededCommand(HEARING_TWO, MONDAY_SHARED),
                        document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.OFF).outputId());
                // Stamped by hand: no active read answers with a register recorded while the flag
                // was off, and carrying a batch is the whole of what this row is here for.
                assembled(MONDAY,
                        List.of(recordedView(batched.get(), HEARING_TWO, MONDAY, MONDAY_SHARED)));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(recordedWhileOff())
                    .as("a superseded row was replaced by the results shared after it and a stamped "
                            + "row is on its way to a PDF; neither is a register waiting for "
                            + "somebody to notice it")
                    .extracting(RegisterRecord::hearingId)
                    .containsExactly(HEARING_ONE);
        }
    }

    /**
     * Assembly, which is all of it or none of it.
     *
     * <p>The batch row, the stamps and the count that judges them are one decision. Between the
     * read that produced the list and the write that stamps it, a re-share can supersede a row or
     * another run can stamp it, and a batch that quietly contained fewer registers than it was asked
     * for would render a document missing a hearing nobody could name. The store refuses such a
     * batch - and the refusal is only worth anything if the batch row goes with it, because
     * {@code idx_register_batch_live_key} admits one unfailed batch per court centre and day: a
     * PENDING row left behind by a refusal holds that key against every later run, and the day is
     * never rendered at all.
     */
    @Nested
    @DisplayName("assembling a batch")
    class Assembly {

        @Test
        void a_batch_asked_for_a_register_that_moved_should_leave_no_trace_of_itself() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand second = seededCommand(HEARING_TWO, MONDAY_SHARED);
            final List<RegisterRecord> stale = new ArrayList<>();

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(second, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                stale.addAll(mine(store.activeUnbatched()));
                // The re-share the run did not see: it lands after the list was read, supersedes
                // the first hearing's row, and leaves the list the run is holding one register out
                // of date.
                record(seededCommand(HEARING_ONE, MONDAY_RESHARED),
                        document(HEARING_ONE, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThatThrownBy(() ->
                            assembled(MONDAY, stale))
                    .as("a batch that would render one of the two registers it was asked for is "
                            + "refused rather than sent")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("was asked for 2 registers and stamped 1");
            softly.assertThat(batchOn(MONDAY))
                    .as("and the batch row goes with the refusal, or it holds the day's live key "
                            + "against every later run and the day is never rendered")
                    .isEmpty();
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("the register that was stamped before the count came back is unstamped "
                            + "again, and waits for the batch that does contain it")
                    .isZero();

            softly.assertThatCode(() -> assembled(MONDAY, mine(store.activeUnbatched())))
                    .as("the next run assembles the day as it now stands, which is the whole point "
                            + "of refusing the first one")
                    .doesNotThrowAnyException();
        }

        /**
         * The batch the assembler decided on is the batch that is written.
         *
         * <p>Four of its facts are decisions no statement here can make again: the identity every
         * downstream call correlates on, the file the day is rendered under, whether the schedule
         * or an operator asked, and which batch this one follows at which supplementary index. The
         * assembler is the only thing that has seen the key's history, so it is the only thing that
         * can decide the last of those - and a store that minted its own identity and copied its
         * own file name off the first row would silently overrule all four.
         *
         * <p>The supplementary shape is the one that cannot be faked. A day whose first batch has
         * ended and whose hearing is re-shared afterwards is design Q27's whole subject, and the
         * columns that record it - {@code supplement_of} and {@code supplement_index} - have no
         * other way of being written: there is nothing in a register row to derive them from.
         */
        @Test
        void an_assembled_batch_is_written_as_the_assembler_decided_it() {
            final AtomicReference<UUID> followed = new AtomicReference<>();
            final AtomicReference<RegisterBatch> decided = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(seededCommand(HEARING_ONE, MONDAY_SHARED),
                        document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch first = assembled(MONDAY, mine(store.activeUnbatched()));
                followed.set(first.batchId());
                // Terminal, so the day's live key is free and a supplement is admitted at all;
                // rejected rather than store-unavailable, so the first batch keeps its own rows.
                store.markFailed(first.batchId(), BatchFailureReason.RENDER_REQUEST_REJECTED, null,
                        null);
                // The late re-share: a fresh active register for a day that has already been
                // rendered once.
                record(seededCommand(HEARING_TWO, MONDAY_RESHARED),
                        document(HEARING_TWO, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);

                final List<RegisterRecord> late = mine(store.activeUnbatched());
                decided.set(new RegisterBatch(UUID.randomUUID(), courtCentre, null, null, MONDAY,
                        SUPPLEMENTARY_FILE_NAME, null, null, BatchStatus.PENDING, null, null,
                        false, null, null, null, null, null, null, 0, followed.get(), 1));
                store.assemble(decided.get(), late);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(assemblyOf(supplementIndexOn(MONDAY, 1)))
                    .as("the identity the render request correlates on, the name the day's second "
                            + "document is filed under, the batch it follows and the index its "
                            + "name was built from - all four decided by the assembler, and none "
                            + "of them re-derivable from a register row")
                    .contains(new AssemblyFacts(
                            decided.get() == null ? null : decided.get().batchId(),
                            SUPPLEMENTARY_FILE_NAME, followed.get(), 1, false));
            softly.assertThat(stampedWith(decided.get() == null ? null : decided.get().batchId()))
                    .as("and the rows carry that identity, not one the statement minted for itself")
                    .isEqualTo(1);
        }
    }

    /**
     * The history a run has to read before it can decide anything supplementary.
     *
     * <p>The assembler is handed the batches already recorded for the keys in play, and there is no
     * other way for it to learn either half of design Q27: whether a key still has a batch in flight
     * (leave its registers waiting) and, if not, which batch a supplement follows at which index. A
     * read that answered nothing would make every late re-share a day's first document all over
     * again.
     *
     * <p>Two cases, and the second is the one a wrong predicate passes: asked for one key, the read
     * must not answer with another key's batches. The run assembles per key, and a history that
     * carried Tuesday's finished batch into Monday's decision would number Monday's supplements off
     * Tuesday's.
     */
    @Nested
    @DisplayName("the batches recorded for a run's keys")
    class RecordedBatches {

        @Test
        void a_keys_batches_should_come_back_whatever_state_they_reached() {
            final AtomicReference<UUID> failed = new AtomicReference<>();
            final List<RegisterBatch> history = new ArrayList<>();

            softly.assertThatCode(() -> {
                record(seededCommand(HEARING_ONE, MONDAY_SHARED),
                        document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch first = assembled(MONDAY, mine(store.activeUnbatched()));
                failed.set(first.batchId());
                store.markFailed(first.batchId(), BatchFailureReason.RENDER_REQUEST_REJECTED, null,
                        null);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThatCode(() -> history.addAll(
                            store.batchesFor(List.of(new CourtCentreDay(courtCentre, MONDAY)))))
                    .as(PENDING)
                    .doesNotThrowAnyException();
            softly.assertThat(history)
                    .as("a terminal batch is exactly the one the supplementary rule needs to see: "
                            + "the day may be rendered again, following this one")
                    .extracting(RegisterBatch::batchId)
                    .containsExactly(failed.get());
        }

        @Test
        void another_days_batches_should_not_be_among_them() {
            final AtomicReference<UUID> monday = new AtomicReference<>();
            final List<RegisterBatch> history = new ArrayList<>();

            softly.assertThatCode(() -> {
                record(seededCommand(HEARING_ONE, MONDAY_SHARED),
                        document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(seededCommand(HEARING_THREE, TUESDAY_SHARED),
                        document(HEARING_THREE, TUESDAY, TUESDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final List<RegisterRecord> waiting = mine(store.activeUnbatched());
                monday.set(assembled(MONDAY, recordsOn(waiting, MONDAY)).batchId());
                assembled(TUESDAY, recordsOn(waiting, TUESDAY));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThatCode(() -> history.addAll(
                            store.batchesFor(List.of(new CourtCentreDay(courtCentre, MONDAY)))))
                    .as(PENDING)
                    .doesNotThrowAnyException();
            softly.assertThat(history)
                    .as("the same court centre's other day says nothing about whether this day may "
                            + "be rendered again, and a supplement numbered off it would name a "
                            + "document for a different set of children")
                    .extracting(RegisterBatch::batchId)
                    .containsExactly(monday.get());
        }

        @Test
        void a_run_with_no_keys_should_be_answered_without_a_statement() {
            final List<RegisterBatch> history = new ArrayList<>();

            softly.assertThatCode(() -> history.addAll(store.batchesFor(List.of())))
                    .as(PENDING)
                    .doesNotThrowAnyException();
            softly.assertThat(history)
                    .as("a quiet night asks the database nothing; an empty IN list is a statement "
                            + "Postgres refuses rather than answers")
                    .isEmpty();
        }
    }

    /**
     * The history a person's regeneration starts from, which is a day rather than a set of keys.
     *
     * <p>{@link RecordedBatches} answers for the keys a run holds active registers for, and that is
     * exactly the read a support call cannot use: a day's FAILED batches still carry their stamp, so
     * their registers are outside {@code activeUnbatched} and the court centre whose whole night
     * failed falls under no key the run would have asked about. That is the call actually made at
     * 08:00, and this is the read it is answered from.
     *
     * <p>Every state, because what to do with each of them differs and the decision is the
     * caller's: a FAILED batch may be released and re-assembled, a notified one is what a
     * supplementary index is counted over, and one still in flight is why a key is left alone
     * (design Q27). A read that filtered to FAILED would leave the second and the third
     * unanswerable from the same page, and the day would be re-rendered against a history it could
     * not see.
     */
    @Nested
    @DisplayName("the batches recorded for one register day")
    class DayBatches {

        @Test
        void a_days_batches_should_come_back_whatever_state_each_of_them_reached() {
            final AtomicReference<UUID> failedBatch = new AtomicReference<>();
            final AtomicReference<UUID> notifiedBatch = new AtomicReference<>();
            final AtomicReference<UUID> inFlight = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(seededCommand(HEARING_ONE, MONDAY_SHARED),
                        document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch first = assembled(MONDAY, mine(store.activeUnbatched()));
                failedBatch.set(first.batchId());
                store.markRequested(first.batchId(), PAYLOAD_FILE_ID);
                // The reason that keeps the stamp, which is the whole reason this read exists: the
                // registers of this batch are outside every later run's reading.
                store.markFailed(first.batchId(), BatchFailureReason.GENERATION_FAILED, SDG_REASON,
                        CompletedBy.EVENT);

                record(seededCommand(HEARING_TWO, MONDAY_RESHARED),
                        document(HEARING_TWO, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch second = assembled(MONDAY, mine(store.activeUnbatched()));
                notifiedBatch.set(second.batchId());
                walkedToNotified(second, SECOND_PAYLOAD_FILE_ID, DOCUMENT_FILE_ID);

                record(seededCommand(HEARING_THREE, MONDAY_RESHARED_AGAIN),
                        document(HEARING_THREE, MONDAY, MONDAY_RESHARED_AGAIN), APPLICANT,
                        RecordedFlagState.ON);
                inFlight.set(assembled(MONDAY, mine(store.activeUnbatched())).batchId());
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(batchesOn(MONDAY))
                    .as("the failure is what may be released and re-assembled, the notified one is "
                            + "what a supplementary index is counted over, and the one still in "
                            + "flight is why a key is left alone; a read that answered only the "
                            + "first would leave the day re-rendered against a history it cannot see")
                    .extracting(RegisterBatch::batchId, RegisterBatch::status)
                    .containsExactlyInAnyOrder(
                            tuple(failedBatch.get(), BatchStatus.FAILED),
                            tuple(notifiedBatch.get(), BatchStatus.NOTIFIED),
                            tuple(inFlight.get(), BatchStatus.PENDING));
        }

        @Test
        void the_neighbouring_days_batches_should_not_be_among_them() {
            final AtomicReference<UUID> monday = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(seededCommand(HEARING_ONE, MONDAY_SHARED),
                        document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(seededCommand(HEARING_THREE, TUESDAY_SHARED),
                        document(HEARING_THREE, TUESDAY, TUESDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final List<RegisterRecord> waiting = mine(store.activeUnbatched());
                monday.set(assembled(MONDAY, recordsOn(waiting, MONDAY)).batchId());
                assembled(TUESDAY, recordsOn(waiting, TUESDAY));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(batchesOn(MONDAY))
                    .as("a regeneration is asked for one day, and the next day's document rendered "
                            + "beside it is a second e-mail to a real Youth Offending Team about "
                            + "children whose register was never in question")
                    .extracting(RegisterBatch::batchId)
                    .containsExactly(monday.get());
        }

        /**
         * The order the read answers in, which the caller that releases a day depends on.
         *
         * <p><strong>[A] characterisation.</strong> The statement has ordered by court centre, then
         * supplementary index, then identity since it landed; nothing here changes it. What was
         * missing is a case that says so, the port having promised "no particular order" while
         * {@code GenerateRegisterCli} released the day's FAILED batches in the order this read
         * answered them - and a key's base batch before its supplement is the order a release has to
         * take them in, because the successor a release supersedes against has to be a later
         * register than the one it is unstamping.
         *
         * <p>The two identities are fixed and the supplement's sorts <em>before</em> the base's, so
         * the assertion is about the index rather than about the identity: an order that had lost
         * {@code supplement_index} would answer the supplement first and could not pass by accident.
         */
        @Test
        void a_keys_supplement_should_come_back_after_the_batch_it_follows() {
            softly.assertThatCode(() -> {
                record(seededCommand(HEARING_ONE, MONDAY_SHARED),
                        document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                store.assemble(dayBatch(BASE_BATCH_ID, null, 0),
                        mine(store.activeUnbatched()));
                // Terminal, so the day's live key is free and a supplement is admitted at all;
                // rejected rather than store-unavailable, so the first batch keeps its own rows.
                store.markFailed(BASE_BATCH_ID, BatchFailureReason.RENDER_REQUEST_REJECTED, null,
                        null);
                record(seededCommand(HEARING_TWO, MONDAY_RESHARED),
                        document(HEARING_TWO, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);
                store.assemble(dayBatch(SUPPLEMENT_BATCH_ID, BASE_BATCH_ID, 1),
                        mine(store.activeUnbatched()));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(batchesOn(MONDAY))
                    .as("a key's earlier batches come before its supplements, which is the order a "
                            + "release has to take them in: the supplement holds the register the "
                            + "base batch's row was replaced by, and releasing the supplement first "
                            + "would ask the store to supersede the newer register against the "
                            + "older one")
                    .extracting(RegisterBatch::batchId, RegisterBatch::supplementIndex)
                    .containsExactly(tuple(BASE_BATCH_ID, 0), tuple(SUPPLEMENT_BATCH_ID, 1));
        }

        /**
         * One batch of this day at this court centre, under an identity the case chose.
         *
         * <p>{@link #assembled} mints its own identity, which is what every other case wants and
         * exactly what the case above cannot have: the claim is that the index orders the answer
         * rather than the identity, so the identities have to sort against the index.
         *
         * @param batchId         the identity the case fixed
         * @param supplementOf    the batch it follows, or {@code null} on the day's first
         * @param supplementIndex nought on the day's first, counting up on each supplement
         * @return the batch the store is asked to write
         */
        private RegisterBatch dayBatch(final UUID batchId, final UUID supplementOf,
                final int supplementIndex) {
            return new RegisterBatch(batchId, courtCentre, null, null, MONDAY,
                    supplementIndex == 0 ? fileName(HEARING_ONE, MONDAY) : SUPPLEMENTARY_FILE_NAME,
                    null, null, BatchStatus.PENDING, null, null, true, null, null, null, null, null,
                    null, 0, supplementOf, supplementIndex);
        }
    }

    /**
     * The registers one batch was assembled from, read back by identity.
     *
     * <p>{@code BATCH_REGISTERS} landed with no automated case of its own, and it is what the whole
     * render is built from: the payload is progression's array of documents in the order the batch
     * holds them, and the first record names the file. Three properties therefore decide what
     * document a Youth Offending Team receives - that the read answers exactly the rows that were
     * stamped, that it answers them in the assembly order, and that another batch's rows are not
     * among them.
     *
     * <p>Asked of two batches at one court centre rather than one, because "exactly this batch's
     * rows" is a claim no single-batch case can fail: a read that had gone back to the court centre
     * and day, or dropped the predicate altogether, would satisfy every assertion a one-batch
     * scenario could make.
     */
    @Nested
    @DisplayName("the registers of a batch")
    class BatchRegisters {

        @Test
        void a_batchs_registers_should_be_exactly_its_own_rows_in_the_assembly_order() {
            final List<UUID> mondayHearings = new ArrayList<>();
            final AtomicReference<UUID> monday = new AtomicReference<>();
            final AtomicReference<UUID> tuesday = new AtomicReference<>();

            softly.assertThatCode(() -> {
                // Recorded out of order on purpose: the read is ordered by the register instant, so
                // a statement that answered in insertion order would still pass a case whose rows
                // were written oldest first.
                record(seededCommand(HEARING_TWO, MONDAY_RESHARED),
                        document(HEARING_TWO, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(seededCommand(HEARING_ONE, MONDAY_SHARED),
                        document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(seededCommand(HEARING_THREE, TUESDAY_SHARED),
                        document(HEARING_THREE, TUESDAY, TUESDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final List<RegisterRecord> waiting = mine(store.activeUnbatched());
                monday.set(assembled(MONDAY, recordsOn(waiting, MONDAY)).batchId());
                tuesday.set(assembled(TUESDAY, recordsOn(waiting, TUESDAY)).batchId());
                store.batched(monday.get()).forEach(
                        register -> mondayHearings.add(register.hearingId()));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(mondayHearings)
                    .as("the payload is the batch's documents in the order the batch holds them, "
                            + "and the first of them names the file; oldest first by the register "
                            + "instant, not by the order the rows happened to be written")
                    .containsExactly(HEARING_ONE, HEARING_TWO);
        }

        @Test
        void another_batchs_registers_should_not_be_among_them() {
            final List<UUID> tuesdayHearings = new ArrayList<>();

            softly.assertThatCode(() -> {
                record(seededCommand(HEARING_ONE, MONDAY_SHARED),
                        document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(seededCommand(HEARING_THREE, TUESDAY_SHARED),
                        document(HEARING_THREE, TUESDAY, TUESDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final List<RegisterRecord> waiting = mine(store.activeUnbatched());
                assembled(MONDAY, recordsOn(waiting, MONDAY));
                final RegisterBatch tuesday = assembled(TUESDAY, recordsOn(waiting, TUESDAY));
                store.batched(tuesday.batchId()).forEach(
                        register -> tuesdayHearings.add(register.hearingId()));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(tuesdayHearings)
                    .as("one court centre sitting on two days is the shape P3 is about, asked of "
                            + "the read instead of the write: a document built from the court "
                            + "centre rather than from the batch would carry Monday's children "
                            + "into Tuesday's register")
                    .containsExactly(HEARING_THREE);
        }

        @Test
        void a_batch_nobody_assembled_should_hold_no_registers() {
            final List<RegisterRecord> answered = new ArrayList<>();

            softly.assertThatCode(() -> {
                record(seededCommand(HEARING_ONE, MONDAY_SHARED),
                        document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                assembled(MONDAY, mine(store.activeUnbatched()));
                answered.addAll(store.batched(UUID.randomUUID()));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(answered)
                    .as("an identity nothing was stamped with holds nothing; the generation "
                            + "service reads this and fails the batch ASSEMBLY_FAILED, which is a "
                            + "batch that ends rather than one that renders somebody else's rows")
                    .isEmpty();
        }
    }

    /**
     * The payload id, written down before it is used and never written twice.
     *
     * <p>{@code MARK_PAYLOAD_MINTED} landed with no automated case either, and it is the one write
     * in the store that settles no transition. What it protects is attribution: an id minted, used
     * for a file-service insert and only then written down is an id that exists in the file service
     * and nowhere in this service if the pod dies in between - and, if the render request got out
     * first, a document that comes back attributable to nothing.
     *
     * <p>So it is fenced on PENDING and it is not a transition. A batch already GENERATING has had a
     * render asked for about the id it carries, and a second mint over the top of it would leave the
     * outcome event for the first payload correlated to a row naming the second.
     */
    @Nested
    @DisplayName("minting a batch's payload id")
    class PayloadMinting {

        @Test
        void a_pending_batch_should_take_the_payload_id_and_stay_pending() {
            softly.assertThatCode(() -> {
                record(seededCommand(HEARING_ONE, MONDAY_SHARED),
                        document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markPayloadMinted(monday.batchId(), PAYLOAD_FILE_ID);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(payloadFileIdOn(MONDAY))
                    .as("written before the file-service insert, so nothing downstream is ever "
                            + "asked about an identifier this service has not already written down")
                    .contains(PAYLOAD_FILE_ID);
            softly.assertThat(batchOn(MONDAY).map(BatchOutcome::status))
                    .as("and the batch has not moved: the id says which payload the render will be "
                            + "about, not that one was asked for")
                    .contains("PENDING");
        }

        @Test
        void a_batch_that_has_already_been_requested_should_refuse_a_second_payload_id() {
            final AtomicReference<UUID> monday = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(seededCommand(HEARING_ONE, MONDAY_SHARED),
                        document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                monday.set(assembled(MONDAY, mine(store.activeUnbatched())).batchId());
                store.markPayloadMinted(monday.get(), PAYLOAD_FILE_ID);
                store.markRequested(monday.get(), PAYLOAD_FILE_ID);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThatThrownBy(
                            () -> store.markPayloadMinted(monday.get(), SECOND_PAYLOAD_FILE_ID))
                    .as("systemdocgenerator has been asked about the first payload and will "
                            + "announce its outcome against this batch; a second id over the top "
                            + "would leave that announcement naming a payload nobody rendered")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("was not given a payload id");
            softly.assertThat(payloadFileIdOn(MONDAY))
                    .as("and the refusal changed nothing")
                    .contains(PAYLOAD_FILE_ID);
            softly.assertThat(batchOn(MONDAY).map(BatchOutcome::status)).contains(GENERATING);
        }

        @Test
        void a_batch_nobody_assembled_should_refuse_a_payload_id() {
            softly.assertThatThrownBy(
                            () -> store.markPayloadMinted(UUID.randomUUID(), PAYLOAD_FILE_ID))
                    .as("an id minted against no batch is a payload in the file service that "
                            + "nothing in this service points at")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("was not given a payload id");
        }
    }

    /**
     * Defect fix P3, stated as the count progression gets wrong.
     *
     * <p>Progression's generation marks a court centre's rows generated, not a batch's. One court
     * centre sitting on two days therefore loses a whole day's register: the Monday document flips
     * Tuesday's rows too, Tuesday's batch is never assembled, and nobody is told. The store's port
     * takes a batch identity precisely so the widening cannot come back without changing the
     * signature, and this is the test that would fail if it did.
     *
     * <p>The Monday batch is requested before it is generated because that is the only way a batch
     * reaches GENERATED: {@code BatchStatus.canTransitionTo} refuses PENDING -&gt; GENERATED, since a
     * document cannot exist before a render was asked for. The request writes {@code register_batch}
     * and nothing else, so it moves no row and the counts below are the counts P3 is about.
     */
    @Nested
    @DisplayName("marking a batch generated")
    class Generation {

        @Test
        void generation_flips_only_the_batchs_own_rows() {
            final DistributionCommand mondayFirst = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand mondaySecond = seededCommand(HEARING_TWO, MONDAY_SHARED);
            final DistributionCommand tuesdayFirst = seededCommand(HEARING_THREE, TUESDAY_SHARED);
            final DistributionCommand tuesdaySecond = seededCommand(HEARING_FOUR, TUESDAY_SHARED);

            softly.assertThatCode(() -> {
                record(mondayFirst, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(mondaySecond, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(tuesdayFirst, document(HEARING_THREE, TUESDAY, TUESDAY_SHARED),
                        APPLICANT, RecordedFlagState.ON);
                record(tuesdaySecond, document(HEARING_FOUR, TUESDAY, TUESDAY_SHARED),
                        APPLICANT, RecordedFlagState.ON);
                final List<RegisterRecord> waiting = mine(store.activeUnbatched());
                final RegisterBatch monday = assembled(MONDAY, recordsOn(waiting, MONDAY));
                assembled(TUESDAY, recordsOn(waiting, TUESDAY));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
                store.markGenerated(monday.batchId(), DOCUMENT_FILE_ID, GENERATED_AT,
                        CompletedBy.EVENT);
            }).as(PENDING).doesNotThrowAnyException();

            softly.assertThat(statusesOn(MONDAY))
                    .as("the batch that generated a document is the batch whose rows move")
                    .containsExactly(GENERATED, GENERATED);
            softly.assertThat(statusesOn(TUESDAY))
                    .as("P3: progression flips by court centre, so Monday's document would carry "
                            + "Tuesday's rows to GENERATED and Tuesday's register would never be "
                            + "assembled, rendered or sent")
                    .containsExactly(RECORDED, RECORDED);
            softly.assertThat(generatedRowsAtCourtCentre())
                    .as("two rows generated, not four: the count is the whole of defect fix P3")
                    .isEqualTo(2);
        }

        /**
         * What the request was stamped at, read back off the column.
         *
         * @param registerDate the day whose single batch to read
         * @return that batch's {@code requested_at}
         */
        private Instant requestedAtOn(final LocalDate registerDate) {
            return batchesOn(registerDate).get(0).requestedAt();
        }

        /**
         * The three instants the latency reading is taken from, across a live write and read.
         *
         * <p>{@code RegisterBatch.generationRoundTrip} is pinned at unit level over mocked
         * repositories, so what is proved there is the rule and the wiring - that it reads
         * {@code requested_at} and prefers {@code generated_at} over {@code failed_at}, and refuses
         * a negative. What no suite proved is that the columns carry what the rule assumes once a
         * real {@code markRequested} and {@code markGenerated} have been through JDBC and back:
         * a timestamp mapping, a column default or a zone round trip would be invisible to a mock
         * and would silently change every latency sample the service publishes.
         *
         * <p>Both routes, because {@code renderingOutcomeAt} prefers the document and falls back to
         * the refusal: a batch that generated, and a batch that failed.
         *
         * <p><strong>[A]</strong> - the behaviour is what it already was; this is where it is
         * asserted against a real database rather than against a mock. It cannot settle whether a
         * generator's clock ever actually runs behind this database's in production - only that the
         * columns hold what the reading is taken from.
         */
        @Test
        void the_round_trip_is_read_from_what_the_columns_actually_hold() {
            final DistributionCommand generated = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand failed = seededCommand(HEARING_THREE, TUESDAY_SHARED);

            softly.assertThatCode(() -> {
                record(generated, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(failed, document(HEARING_THREE, TUESDAY, TUESDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final List<RegisterRecord> waiting = mine(store.activeUnbatched());
                final RegisterBatch withDocument = assembled(MONDAY, recordsOn(waiting, MONDAY));
                final RegisterBatch withRefusal = assembled(TUESDAY, recordsOn(waiting, TUESDAY));
                store.markRequested(withDocument.batchId(), PAYLOAD_FILE_ID);
                // Read requested_at back rather than assuming it: it is the store's own now(), so
                // the only way to mark a document a known interval after the request is to ask the
                // column what the request was stamped at.
                store.markGenerated(withDocument.batchId(), DOCUMENT_FILE_ID,
                        requestedAtOn(MONDAY).plus(ROUND_TRIP), CompletedBy.EVENT);
                store.markRequested(withRefusal.batchId(), SECOND_PAYLOAD_FILE_ID);
                store.markFailed(withRefusal.batchId(), BatchFailureReason.GENERATION_FAILED,
                        SDG_REASON, CompletedBy.EVENT);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(batchesOn(MONDAY))
                    .as("the interval the columns actually hold, not merely that one is there: a "
                            + "timestamp mapping or a zone round trip would land here as a wrong "
                            + "duration, and every latency sample this service publishes is this "
                            + "reading")
                    .singleElement()
                    .extracting(batch -> batch.generationRoundTrip().orElse(null))
                    .isEqualTo(ROUND_TRIP);
            softly.assertThat(batchesOn(TUESDAY))
                    .as("and the batch with no document at all, whose round trip ends at the "
                            + "refusal instead - the renderingOutcomeAt fallback the rule states "
                            + "and no live write had exercised")
                    .singleElement()
                    .extracting(batch -> batch.generationRoundTrip().isPresent())
                    .isEqualTo(true);
        }

        /**
         * The one shape the columns can hold that is not a round trip, across a live write.
         *
         * <p>{@code generated_at} is systemdocgenerator's account of when it rendered and
         * {@code requested_at} is the store's own {@code now()}, so a generator whose clock is
         * behind this database's puts the outcome before the request. The rule refuses a negative
         * reading; this says the columns will carry such a pair, which is what makes the rule worth
         * having. It cannot say whether that ever happens in production - only that nothing between
         * the write and the read prevents it.
         */
        @Test
        void a_document_stamped_before_its_request_is_carried_and_refused_as_a_round_trip() {
            final DistributionCommand backwards = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(backwards, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch batch =
                        assembled(MONDAY, recordsOn(mine(store.activeUnbatched()), MONDAY));
                store.markRequested(batch.batchId(), PAYLOAD_FILE_ID);
                store.markGenerated(batch.batchId(), DOCUMENT_FILE_ID,
                        requestedAtOn(MONDAY).minus(ROUND_TRIP), CompletedBy.EVENT);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(batchesOn(MONDAY))
                    .as("the pair is stored as written - the database refuses nothing - and the "
                            + "rule declines to call it a round trip, which is a clock to fix "
                            + "rather than a latency to publish")
                    .singleElement()
                    .extracting(batch -> batch.generationRoundTrip().isPresent())
                    .isEqualTo(false);
        }

        /**
         * Which mechanism learned the outcome, written by the mark that learned it.
         *
         * <p>{@code completed_by} is the only record of it. Nothing else in the flow says which
         * mechanism delivered an outcome, so a batch that does not carry it is a batch whose
         * completion mechanism is lost - and a night whose outcomes are all missing it is a broker
         * or a subscription somebody has to look at, with nothing to look at it by.
         *
         * <p>It cannot be a second write. Batch state changes are compare-and-set through
         * {@code BatchStatus}: written before the mark, the batch is still GENERATING and the update
         * would be guessing at an outcome that has not arrived; written after it, the only move left
         * is GENERATED to GENERATED, which the machine refuses. The mark therefore carries it, and
         * this case asserts that the mark's own statement is where it lands - on <em>both</em> marks
         * that take an attribution, the generated one and the failed one, rather than on one of
         * them and an assumption about the other.
         */
        @Test
        void generation_records_who_completed_the_batch() {
            final DistributionCommand announced = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand refused = seededCommand(HEARING_THREE, TUESDAY_SHARED);

            softly.assertThatCode(() -> {
                record(announced, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(refused, document(HEARING_THREE, TUESDAY, TUESDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final List<RegisterRecord> waiting = mine(store.activeUnbatched());
                final RegisterBatch generated = assembled(MONDAY, recordsOn(waiting, MONDAY));
                final RegisterBatch failed = assembled(TUESDAY, recordsOn(waiting, TUESDAY));
                store.markRequested(generated.batchId(), PAYLOAD_FILE_ID);
                store.markGenerated(generated.batchId(), DOCUMENT_FILE_ID, GENERATED_AT,
                        CompletedBy.EVENT);
                store.markRequested(failed.batchId(), SECOND_PAYLOAD_FILE_ID);
                store.markFailed(failed.batchId(), BatchFailureReason.GENERATION_FAILED,
                        SDG_REASON, CompletedBy.EVENT);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(completedByOn(MONDAY))
                    .as("the document arrived on the public event, and the row that says the batch "
                            + "is generated says so in the same breath")
                    .contains("EVENT");
            softly.assertThat(completedByOn(TUESDAY))
                    .as("and the refusal arrived the same way, on the other mark that takes an "
                            + "attribution: the failing path records the mechanism too")
                    .contains("EVENT");
        }

        /**
         * A generated batch that names nobody, refused before anything is written.
         *
         * <p>A document exists because some mechanism outside this service said so, and the row that
         * records the document is the only place that says which one. A GENERATED row with no
         * {@code completed_by} is therefore not an incomplete row but a contradictory one: it claims
         * an answer arrived and denies that anything delivered it.
         *
         * <p>The refusal has to come before the statement. The mark is a compare-and-set that also
         * moves this batch's registers to GENERATED, and there is no second write afterwards that
         * could add the attribution: GENERATED to GENERATED is a move
         * {@link uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus} refuses. A row written without it
         * is a row that can never acquire it.
         */
        @Test
        void a_generated_mark_without_attribution_is_refused() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
            }).as(WALKED).doesNotThrowAnyException();
            final UUID batchId = batchIdOn(MONDAY);

            softly.assertThatThrownBy(() ->
                            store.markGenerated(batchId, DOCUMENT_FILE_ID, GENERATED_AT, null))
                    .as("a document arrived because some mechanism reported it, and this row is "
                            + "the only place that ever says which one")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(GENERATED);
            softly.assertThat(batchOn(MONDAY))
                    .as("and the refusal is made before the statement, so the batch is still "
                            + "waiting for the outcome it was asked about")
                    .contains(new BatchOutcome(GENERATING, null, null));
            softly.assertThat(completedByOn(MONDAY))
                    .as("nothing was attributed, because nothing was written")
                    .isEmpty();
            softly.assertThat(statusesOn(MONDAY))
                    .as("and the register the batch was assembled from did not move either")
                    .containsExactly(RECORDED);
        }
    }

    /**
     * The ending where no document was ever produced, and what it does to the rows.
     *
     * <p>Seven bounded reasons, and the store treats three of them differently from the other four.
     * The two that say the batch never left this service - the payload was not stored, the assembly
     * itself failed - release the stamp, so the registers become unbatched again and tonight's
     * failure is tomorrow's first batch, and so does the third, which says the next run began
     * before this batch finished. The other four leave the stamp exactly where it is:
     * systemdocgenerator was asked, a document may yet exist under that correlation, and re-rendering
     * one is a decision a person makes through the operations CLI rather than one a schedule makes
     * silently at 18:00.
     *
     * <p>The rows stay RECORDED under every reason. Nothing was ever sent about them, and a status
     * that said otherwise would take a register out of the next run without anybody having received
     * it.
     *
     * <p><strong>Unless the estate replaced one of them while the batch was in flight.</strong> The
     * recording predicate only supersedes an incumbent that is active and <em>unbatched</em>, so a
     * hearing shared again between the assembly and the failure leaves two RECORDED rows for one
     * key - one stamped, one active. Releasing the stamp off the older one would meet
     * {@code idx_output_active_register_key}, and the whole mark would be refused with it: the
     * batch would still call itself in flight under a run that had already given up on it. So the
     * release supersedes that row against the register that replaced it, exactly as the release a
     * person types does.
     */
    @Nested
    @DisplayName("failing a batch")
    class Failure {

        /**
         * A share of the hearing stamped before the register the batch already holds.
         *
         * <p>Earlier than {@code MONDAY_SHARED} and on the same register date, because it is the
         * ordering and not the day that decides which of two shares of one hearing is current.
         */
        private static final Instant MONDAY_OVERTAKEN = Instant.parse("2026-08-24T07:15:00Z");

        @Test
        void a_failed_batch_always_carries_its_failed_at() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markFailed(monday.batchId(),
                        BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE, null, null);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(failedAtOn(MONDAY))
                    .as("the stamp is written by the statement that writes the status, in the "
                            + "same UPDATE, so a FAILED batch with no failed_at is a row this "
                            + "service has no way to produce. The exception report's "
                            + "BATCH_FAILED read relies on exactly that: it bounds the batch by "
                            + "failed_at and measures its age from it, so a null there would be "
                            + "a dead batch silently missing from the morning report")
                    .isPresent();
        }

        @Test
        void a_failure_that_never_left_this_service_should_release_its_rows_for_the_next_run() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand second = seededCommand(HEARING_TWO, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(second, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markFailed(monday.batchId(),
                        BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE, null, null);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(batchOn(MONDAY))
                    .as("the batch is finished under the bounded code the run report counts it by, "
                            + "and systemdocgenerator said nothing because it was never asked")
                    .contains(new BatchOutcome(FAILED, "PAYLOAD_STORE_UNAVAILABLE", null));
            softly.assertThat(statusesOn(MONDAY))
                    .as("nothing was sent about these registers, so nothing about them moved on")
                    .containsExactly(RECORDED, RECORDED);
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("the stamp is released: a batch that never left this service holds no "
                            + "register hostage to a document that will never exist")
                    .isZero();
            softly.assertThat(activeUnbatched())
                    .as("and the next run picks the same registers up, under a fresh batch identity")
                    .extracting(RegisterRecord::hearingId)
                    .containsExactlyInAnyOrder(HEARING_ONE, HEARING_TWO);
        }

        @Test
        void a_failure_that_never_left_should_supersede_the_share_it_overtook() {
            final DistributionCommand batched = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand overtaken = seededCommand(HEARING_ONE, MONDAY_OVERTAKEN);

            softly.assertThatCode(() -> {
                record(batched, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                // The hearing's earlier share is delivered after the later one is already in the
                // batch, so the recorder writes it active: a batched register is not its to
                // supersede. It then holds the day's active-register key against the release.
                record(overtaken, document(HEARING_ONE, MONDAY, MONDAY_OVERTAKEN), APPLICANT,
                        RecordedFlagState.ON);
                store.markFailed(monday.batchId(),
                        BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE, null, null);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(batchOn(MONDAY))
                    .as("the failure is recorded whatever the overtaken share did: a mark the "
                            + "active-row index refused would leave the batch claiming to be in "
                            + "flight under a run that had already given up on it")
                    .contains(new BatchOutcome(FAILED, "PAYLOAD_STORE_UNAVAILABLE", null));
            softly.assertThat(supersessionOf(overtaken).map(SupersessionPair::supersededBy))
                    .as("and the earlier share is superseded against the register being given "
                            + "back, which is the ordering read the other way - the same rule the "
                            + "stale-batch release keeps, and the same one the recorder applies "
                            + "when it is the one that meets the two")
                    .contains(outputIdOf(batched).orElse(null));
            softly.assertThat(statusesOn(MONDAY))
                    .as("so the key keeps exactly one active row, as it does after every other "
                            + "write in this store")
                    .containsExactlyInAnyOrder(RECORDED, SUPERSEDED);
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("the stamp is released, because a batch that never left this service "
                            + "holds no register hostage to a document that cannot exist")
                    .isZero();
            softly.assertThat(activeUnbatched())
                    .as("and the register the day is still to render is the one that came back, "
                            + "rather than the share it had already overtaken")
                    .extracting(RegisterRecord::outputId)
                    .containsExactly(outputIdOf(batched).orElse(null));
        }

        /**
         * The same arrangement under a reason that keeps the stamp, where the release does nothing.
         *
         * <p>The negative arm of the clause above, and it is a case rather than an inference: the
         * {@code overtaken} clause is guarded by {@code :releaseRows} through {@code stamped}, so
         * three of the six reasons displace nothing at all. That guard is what makes the two
         * halves of statement 9 one rule - a reason that keeps the stamp keeps the register too,
         * and a register still in its batch has overtaken nobody's key. Superseding the earlier
         * share here would withdraw the day's one assemblable register for a document that may yet
         * arrive, and leave the court centre with neither.
         *
         * <p>{@code GENERATION_FAILED} because it is the reason a person is most likely to meet the
         * pair under: systemdocgenerator answered about the render, the batch keeps its rows
         * pending a decision somebody makes through the operations CLI, and the hearing was shared
         * again in the meantime.
         */
        @Test
        void a_failure_holding_the_stamp_should_leave_the_share_it_overtook_alone() {
            final DistributionCommand batched = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand overtaken = seededCommand(HEARING_ONE, MONDAY_OVERTAKEN);

            softly.assertThatCode(() -> {
                record(batched, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
                // The hearing's earlier share arrives behind the register already in the batch, so
                // the recorder writes it active: a batched register is not its to supersede.
                record(overtaken, document(HEARING_ONE, MONDAY, MONDAY_OVERTAKEN), APPLICANT,
                        RecordedFlagState.ON);
                store.markFailed(monday.batchId(), BatchFailureReason.GENERATION_FAILED, SDG_REASON,
                        CompletedBy.EVENT);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(batchOn(MONDAY))
                    .as("the batch is failed under the reason the event named, and systemdocgenerator's "
                            + "own word for it is kept beside it")
                    .contains(new BatchOutcome(FAILED, "GENERATION_FAILED", SDG_REASON));
            softly.assertThat(supersessionOf(overtaken))
                    .as("and the earlier share is not touched: nothing was given back, so nothing "
                            + "overtook it, and superseding it would withdraw the day's one "
                            + "assemblable register for a document that may yet arrive")
                    .contains(new SupersessionPair(null, null));
            softly.assertThat(statusesOn(MONDAY))
                    .as("both rows stay RECORDED, which is the arrangement the failure found: one "
                            + "the batch is still holding and one the day is still to render")
                    .containsExactly(RECORDED, RECORDED);
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("the stamp stays on, because a reason that keeps it is a reason a document "
                            + "may yet exist under, and re-rendering that day is a decision a "
                            + "person makes through the operations CLI")
                    .isEqualTo(1);
            softly.assertThat(activeUnbatched())
                    .as("so the register the next run picks up is the overtaken share alone, the "
                            + "batched one being neither active nor unbatched")
                    .extracting(RegisterRecord::outputId)
                    .containsExactly(outputIdOf(overtaken).orElse(null));
        }


        @Test
        void a_failure_that_never_left_should_supersede_a_register_a_re_share_has_replaced() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand reshare = seededCommand(HEARING_ONE, MONDAY_RESHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                // Between the assembly and the failure the hearing is shared again. The recording
                // predicate supersedes an incumbent that is active and unbatched, and this one is
                // stamped, so the day now holds two RECORDED rows for one key.
                record(reshare, document(HEARING_ONE, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);
                store.markFailed(monday.batchId(),
                        BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE, null, null);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(batchOn(MONDAY))
                    .as("the failure is recorded whatever the re-share did: a mark refused by the "
                            + "active-row index would leave the batch claiming to be in flight "
                            + "under a run that had already given up on it")
                    .contains(new BatchOutcome(FAILED, "PAYLOAD_STORE_UNAVAILABLE", null));
            softly.assertThat(supersessionOf(first).map(SupersessionPair::supersededBy))
                    .as("the register the batch was assembled from is superseded as its stamp is "
                            + "cleared, and by the register that replaced it")
                    .contains(outputIdOf(reshare).orElse(null));
            softly.assertThat(statusesOn(MONDAY))
                    .as("one SUPERSEDED and one RECORDED, which is the invariant the whole batch "
                            + "half is written against: at most one active row per key")
                    .containsExactlyInAnyOrder(SUPERSEDED, RECORDED);
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("and the failed batch holds nothing, because a batch that never left this "
                            + "service holds no register hostage to a document that cannot exist")
                    .isZero();
            softly.assertThat(activeUnbatched())
                    .as("so tomorrow's first batch is the re-share alone, and never the register "
                            + "the estate has already replaced")
                    .extracting(RegisterRecord::outputId)
                    .containsExactly(outputIdOf(reshare).orElse(null));
        }

        /**
         * All three rows at once, which is where the {@code overtaken} clause has to stand down.
         *
         * <p>The two cases above are each one row beside the failing batch's: a re-share that came
         * after it, or a share it overtook. A day can hold both, though only by the route a live
         * batch key leaves open - one batch per court centre day at a time, so the re-share's own
         * batch has to have ended first. It ends under a reason that keeps its stamp, which leaves
         * that register RECORDED and unsuperseded and therefore still a successor. The register
         * this batch holds then arrives behind it, is recorded active because no unbatched row is
         * there to weigh it against, and is assembled in its turn; and a share older than either
         * arrives behind that, active for the same reason.
         *
         * <p>The two clauses are then asking opposite questions about one register, and only one of
         * them may answer. {@code overtaken} is guarded on the successor search coming back empty
         * for exactly this: a register that is itself being superseded is leaving the active index
         * as it goes, so it takes no key and displaces nobody. Without the guard the older share
         * would be superseded against a register this same statement is superseding - pointed at a
         * row nothing will ever render - and the day would be left with no active register at all,
         * which no later run and no {@code generate-register} could recover.
         */
        @Test
        void a_failure_should_displace_nothing_where_the_register_is_itself_superseded() {
            final DistributionCommand batched = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand reshare = seededCommand(HEARING_ONE, MONDAY_RESHARED);
            final DistributionCommand overtaken = seededCommand(HEARING_ONE, MONDAY_OVERTAKEN);

            softly.assertThatCode(() -> {
                record(reshare, document(HEARING_ONE, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch earlier = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(earlier.batchId(), SECOND_PAYLOAD_FILE_ID);
                // A reason that keeps the stamp: the re-share stays RECORDED and unsuperseded, so
                // it is still a successor, and its batch is terminal so the day may batch again.
                store.markFailed(earlier.batchId(), BatchFailureReason.GENERATION_FAILED,
                        SDG_REASON, CompletedBy.EVENT);
                record(batched, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                // Both of the key's other rows are stamped by now, so the late share meets no
                // active unbatched incumbent and is recorded active itself.
                record(overtaken, document(HEARING_ONE, MONDAY, MONDAY_OVERTAKEN), APPLICANT,
                        RecordedFlagState.ON);
                store.markFailed(monday.batchId(),
                        BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE, null, null);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(supersessionOf(batched).map(SupersessionPair::supersededBy))
                    .as("the register the failing batch held is superseded against the one that "
                            + "replaced it, exactly as it is when that register is the key's only "
                            + "other row")
                    .contains(outputIdOf(reshare).orElse(null));
            softly.assertThat(supersessionOf(overtaken))
                    .as("and the share it had overtaken is left alone: a register on its way out "
                            + "of the active index takes no key, so there is nothing for it to "
                            + "displace, and displacing it would point the older row at a row "
                            + "nothing will render")
                    .contains(new SupersessionPair(null, null));
            softly.assertThat(activeUnbatched())
                    .as("so the day keeps exactly one assemblable register - the late share, the "
                            + "re-share still being stamped into the batch that failed holding it")
                    .extracting(RegisterRecord::outputId)
                    .containsExactly(outputIdOf(overtaken).orElse(null));
            softly.assertThat(statusesOn(MONDAY))
                    .as("one SUPERSEDED and two RECORDED, of which only one is active: the "
                            + "invariant is one active row per key, not one row")
                    .containsExactlyInAnyOrder(SUPERSEDED, RECORDED, RECORDED);
        }


        /**
         * The pair the other way round, which is the arrangement the successor search can invert.
         *
         * <p>The case above fails the batch holding the <em>older</em> of the key's two rows, so
         * the row it finds beside it is genuinely the register that replaced it. A day can hold the
         * pair the other way just as easily: a first batch that failed under one of the four
         * reasons that keep the stamp leaves its register RECORDED and stamped, the hearing is
         * shared again, and the re-share is assembled into a batch of its own - so the batch that
         * now fails on a releasing reason is holding the <em>newer</em> row, and the only other row
         * of the key is the stale one the first batch is still holding.
         *
         * <p>A search that asked only for another unsuperseded row of the key would answer with
         * that stale row and supersede the current register against it. The register the estate
         * shared last would then be neither active nor unbatched, so no run and no
         * {@code generate-register} would ever reach it again, and the row left renderable would be
         * the one the re-share replaced - which is the harm the supersession above exists to
         * prevent, done to the wrong row.
         */
        @Test
        void a_failure_holding_the_re_share_should_give_it_back_rather_than_supersede_it() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand reshare = seededCommand(HEARING_ONE, MONDAY_RESHARED);
            final AtomicReference<UUID> supplement = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
                // A reason that keeps the stamp, so the first register is still this batch's.
                store.markFailed(monday.batchId(), BatchFailureReason.GENERATION_FAILED, SDG_REASON,
                        CompletedBy.EVENT);
                record(reshare, document(HEARING_ONE, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);
                // The failed batch is terminal, so the re-share is assembled into one of its own -
                // and it is that batch which now fails on a reason that releases.
                final RegisterBatch second = assembled(MONDAY, mine(store.activeUnbatched()));
                supplement.set(second.batchId());
                store.markFailed(second.batchId(),
                        BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE, null, null);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(supersessionOf(reshare))
                    .as("the register the estate shared last is not superseded against the one it "
                            + "replaced: a supersession the wrong way round withdraws the current "
                            + "register for good and leaves the stale one as the day's")
                    .contains(new SupersessionPair(null, null));
            softly.assertThat(statusesOn(MONDAY))
                    .as("both rows are RECORDED, which is what the arrangement started with: the "
                            + "older one is a failed batch's to keep and the newer one is the "
                            + "day's to render")
                    .containsExactlyInAnyOrder(RECORDED, RECORDED);
            softly.assertThat(stampedWith(supplement.get()))
                    .as("and the batch that never left this service holds nothing")
                    .isZero();
            softly.assertThat(activeUnbatched())
                    .as("so the next run picks the re-share up, under a fresh batch identity")
                    .extracting(RegisterRecord::outputId)
                    .containsExactly(outputIdOf(reshare).orElse(null));
        }

        /**
         * The key's two rows shared at one instant, the register that arrived later sorting first.
         *
         * <p>{@code register_time} is the results' own shared moment and the estate is what sets
         * it, so two shares of one hearing can carry the same one. Statement 1 supersedes an
         * incumbent whose instant is not <em>after</em> the arriving register's, which says that of
         * two registers sharing an instant the one that <em>arrived</em> second is the current one,
         * whatever its identity sorts like.
         *
         * <p>A successor search that broke the tie on the identity alone disagrees with that
         * recorder for one of the two orders, and this is that order: the later row is not seen, so
         * the release makes the older row active a second time,
         * {@code idx_output_active_register_key} refuses it - and the release being a branch of the
         * statement that marks the batch, the mark goes down with the refusal and the batch is left
         * calling itself in flight under a run that had already given up on it.
         *
         * <p>The two identities are therefore stated rather than left to the recorder's own
         * {@code randomUUID} ({@link RegisterStoreIT#identifiedInOrder}): the order that loses the
         * register is the one a random pair produces about half the time, and a case that waited
         * for it would pass and fail by turns.
         */
        @Test
        void a_failure_should_supersede_against_an_equal_time_re_share_that_sorts_first() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand reshare = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                // The same results shared again at the same instant, while the first register is
                // stamped: the recorder supersedes an incumbent that is unbatched, and this one is
                // not, so the day holds two RECORDED rows for one key.
                record(reshare, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                identifiedInOrder(reshare, first);
                store.markFailed(monday.batchId(),
                        BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE, null, null);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(batchOn(MONDAY))
                    .as("the mark lands: a release that could not place the older row would take "
                            + "the whole statement with it, and the batch would still be waiting "
                            + "for a document the run had already stopped asking for")
                    .contains(new BatchOutcome(FAILED, "PAYLOAD_STORE_UNAVAILABLE", null));
            softly.assertThat(supersessionOf(first).map(SupersessionPair::supersededBy))
                    .as("the successor is the register that arrived second, which is the register "
                            + "the recorder would itself have superseded this one against")
                    .contains(outputIdOf(reshare).orElse(null));
            softly.assertThat(statusesOn(MONDAY))
                    .as("one SUPERSEDED and one RECORDED, which is the invariant the whole batch "
                            + "half is written against: at most one active row per key")
                    .containsExactlyInAnyOrder(SUPERSEDED, RECORDED);
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("and the failed batch holds nothing, because a batch that never left this "
                            + "service holds no register hostage to a document that cannot exist")
                    .isZero();
            softly.assertThat(activeUnbatched())
                    .as("so the day's one assemblable register is the re-share, and never the "
                            + "register it replaced at the same instant")
                    .extracting(RegisterRecord::outputId)
                    .containsExactly(outputIdOf(reshare).orElse(null));
        }

        /**
         * The same pair the other way round, so the rule is pinned in both directions.
         *
         * <p><strong>[A] characterisation.</strong> This is the order the identity tie-break
         * happened to agree with, so the statement has answered this way since it landed and
         * nothing here changes it. It is written beside the case above because the two together are
         * the rule: one order alone is also satisfied by a search that ranks the key's rows by
         * identity and nothing else, which is exactly the search that loses the register in the
         * other order.
         */
        @Test
        void a_failure_should_supersede_against_an_equal_time_re_share_that_sorts_last() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand reshare = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                record(reshare, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                identifiedInOrder(first, reshare);
                store.markFailed(monday.batchId(),
                        BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE, null, null);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(batchOn(MONDAY))
                    .as("the mark lands, as it does whichever way the pair sorts")
                    .contains(new BatchOutcome(FAILED, "PAYLOAD_STORE_UNAVAILABLE", null));
            softly.assertThat(supersessionOf(first).map(SupersessionPair::supersededBy))
                    .as("and the successor is the same register: which of the two arrived second "
                            + "is what decides it, and the identity only breaks a tie the arrival "
                            + "instants leave")
                    .contains(outputIdOf(reshare).orElse(null));
            softly.assertThat(statusesOn(MONDAY))
                    .containsExactlyInAnyOrder(SUPERSEDED, RECORDED);
            softly.assertThat(stampedRowsOn(MONDAY)).isZero();
            softly.assertThat(activeUnbatched())
                    .extracting(RegisterRecord::outputId)
                    .containsExactly(outputIdOf(reshare).orElse(null));
        }

        /**
         * The other row of the key is 001's record of a POST, which is not a register at all.
         *
         * <p>Increment 001's {@code ProcessedOutputRepository} writes a PENDING, POSTED or FAILED
         * row into this same table for every register it sends to progression, and it fills the
         * three columns a successor search matches a key on - the hearing the request names, the
         * court centre and register day the claim names - and a register instant with them. During a
         * rolling deployment a pod on the previous release goes on writing them, so a key genuinely
         * holds one beside a register this pod recorded.
         *
         * <p>A search that admitted every row of the key but a SUPERSEDED one takes that POST for
         * the register that replaced this one. The register is then written SUPERSEDED against a row
         * that is not a replacement for anything: it is neither active nor unbatched, so no later
         * run and no {@code generate-register} reaches it again, and the day's document is lost with
         * it. Only the three states the recorder leaves a live register in - RECORDED, GENERATED and
         * NOTIFIED - can be a replacement, which is a closed list because
         * {@code processed_output_status_chk} bounds the column and the other statuses in it are
         * SUPERSEDED, which is by definition not live, and 001's own three.
         */
        @Test
        void a_failure_should_give_its_register_back_though_a_post_row_shares_the_key() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            // The previous release POSTs the same hearing's register to progression: a rolling
            // deployment has both writers live over one queue, and this is the row it leaves.
            final DistributionCommand posting = postedToProgression(HEARING_ONE, MONDAY);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markFailed(monday.batchId(),
                        BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE, null, null);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(registerTimeOf(posting).orElse(null))
                    .as("the arrangement is the one a successor search can act on: the POST row is "
                            + "the later row of the key, so nothing but its status keeps it out")
                    .isAfter(MONDAY_SHARED);
            softly.assertThat(batchOn(MONDAY))
                    .as("the batch is finished under the bounded code the run report counts it by")
                    .contains(new BatchOutcome(FAILED, "PAYLOAD_STORE_UNAVAILABLE", null));
            softly.assertThat(statusOf(first))
                    .as("the register is handed back RECORDED: a POST to progression is not a "
                            + "register that could replace one, so there is nothing here to "
                            + "supersede it against")
                    .contains(RECORDED);
            softly.assertThat(supersessionOf(first))
                    .as("and nothing is written against it, because a supersession names the "
                            + "register that replaced this one and no register did")
                    .contains(new SupersessionPair(null, null));
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("the stamp is released, because a batch that never left this service holds "
                            + "no register hostage to a document that cannot exist")
                    .isZero();
            softly.assertThat(activeUnbatched())
                    .as("so the day's register is the next run's to re-assemble, which is the whole "
                            + "of what a releasing reason is for")
                    .extracting(RegisterRecord::outputId)
                    .containsExactly(outputIdOf(first).orElse(null));
            softly.assertThat(statusOf(posting))
                    .as("and the POST row is left exactly as 001 wrote it")
                    .contains(POST_PENDING);
        }

        /**
         * The successor is a register that has already been sent, which is the far end of the list.
         *
         * <p><strong>[A] characterisation.</strong> Both release statements have admitted RECORDED,
         * GENERATED and NOTIFIED since the successor search was narrowed to the live half, and no
         * case reached past the first of the three: with either predicate cut to
         * {@code IN ('RECORDED')} the whole of this suite went on passing. This case and its
         * pair in {@code Releasing} state the other two, and neither changes a statement.
         *
         * <p>The nearest case before them is
         * {@code a_register_replaced_by_a_re_share_that_is_itself_batched_should_not_come_back},
         * whose successor is stamped into a batch in flight - and a batch at GENERATING has not
         * moved its registers, so the row that search finds is RECORDED like every other one here.
         *
         * <p><strong>Which is why this arrangement is not a re-share at all.</strong> A successor
         * past RECORDED is one some batch took there, and {@code idx_register_batch_live_key}
         * admits one in-flight batch per court centre and day - so the batch failing here, which
         * has to be in flight to fail at all, cannot be the second of two. The day reaches the
         * shape the other way round: the afternoon's results are recorded, batched and sent, and
         * the morning's own delivery arrives <em>after</em> them. The recorder supersedes an
         * incumbent that is RECORDED and unbatched, a sent one is neither, so nothing is
         * superseded and the stale register is recorded RECORDED - waiting to be assembled, which
         * is what the next run does with it.
         *
         * <p>A search that asked only for a RECORDED successor would find nothing beside it, hand
         * it back active, and have the day rendered again from results the estate corrected before
         * the document went out - a second document to the same Youth Offending Team, after the
         * one that corrected it.
         */
        @Test
        void a_failure_should_supersede_against_a_register_that_has_already_been_sent() {
            final DistributionCommand sent = seededCommand(HEARING_ONE, MONDAY_RESHARED);
            final DistributionCommand late = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final AtomicReference<UUID> stale = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(sent, document(HEARING_ONE, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);
                walkedToNotified(assembled(MONDAY, mine(store.activeUnbatched())),
                        PAYLOAD_FILE_ID, DOCUMENT_FILE_ID);
                // The morning's own delivery arrives after the afternoon's has been sent, so the
                // recorder finds no incumbent to supersede and the stale register is left RECORDED.
                record(late, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch morning = assembled(MONDAY, mine(store.activeUnbatched()));
                stale.set(morning.batchId());
                store.markFailed(morning.batchId(),
                        BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE, null, null);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(statusOf(sent))
                    .as("the arrangement is the one the claim needs: the row beside the released "
                            + "one is past RECORDED, so a search that asked only for a RECORDED "
                            + "successor would see nothing at all")
                    .contains(NOTIFIED);
            softly.assertThat(supersessionOf(late).map(SupersessionPair::supersededBy))
                    .as("and it is still the register that replaced this one: what makes a "
                            + "successor is that it came later and is live, and a register a Youth "
                            + "Offending Team has read is as live as one gets")
                    .contains(outputIdOf(sent).orElse(null));
            softly.assertThat(statusesOn(MONDAY))
                    .as("one SUPERSEDED and one NOTIFIED, which is the invariant the whole batch "
                            + "half is written against: at most one active row per key")
                    .containsExactlyInAnyOrder(SUPERSEDED, NOTIFIED);
            softly.assertThat(stampedWith(stale.get()))
                    .as("the batch that never left this service holds nothing, which is also what "
                            + "says the mark landed: the release is a branch of the statement that "
                            + "marks the batch, so a refused write would take both down")
                    .isZero();
            softly.assertThat(activeUnbatched())
                    .as("and nothing of this day is waiting, so no run renders it again from "
                            + "results the estate had already corrected")
                    .isEmpty();
        }

        @Test
        void a_failure_after_the_render_request_should_keep_the_stamp_on_its_rows() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand second = seededCommand(HEARING_TWO, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(second, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
                store.markFailed(monday.batchId(), BatchFailureReason.GENERATION_FAILED, SDG_REASON,
                        CompletedBy.EVENT);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(batchOn(MONDAY))
                    .as("what systemdocgenerator said is kept beside this service's own code, for "
                            + "the person who has to decide whether to render the day again")
                    .contains(new BatchOutcome(FAILED, "GENERATION_FAILED", SDG_REASON));
            softly.assertThat(statusesOn(MONDAY)).containsExactly(RECORDED, RECORDED);
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("the renderer was asked, so a document may yet exist under this batch: "
                            + "re-assembling the same registers automatically could send the day "
                            + "twice, and the stamp is what stops the next run doing it")
                    .isEqualTo(2);
            softly.assertThat(activeUnbatched())
                    .as("nothing is waiting: these registers belong to a batch a person must look at")
                    .isEmpty();
        }

        /**
         * The one column here holding words this service did not author.
         *
         * <p>{@code sdg_reason} is systemdocgenerator's own message, and how long it is is
         * systemdocgenerator's decision rather than this service's. The column is bounded, so the
         * bound has to be applied before the write: an unbounded write against a bounded column
         * fails the whole failure statement, and the batch that could not say why it failed then
         * stays GENERATING until the next run's stale-batch pass gives up on it - the failure lost
         * twice over.
         */
        @Test
        void a_reason_longer_than_the_column_should_be_bounded_before_it_is_written() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
                store.markFailed(monday.batchId(), BatchFailureReason.GENERATION_FAILED,
                        OVERSIZED_SDG_REASON, CompletedBy.EVENT);
            }).as("the failure is recorded whatever the renderer chose to say")
                    .doesNotThrowAnyException();

            softly.assertThat(batchOn(MONDAY).map(BatchOutcome::sdgReason))
                    .as("bounded to the column, and saying so: a reader who cannot see the rest "
                            + "must be able to tell that there is a rest")
                    .hasValueSatisfying(reason -> {
                        softly.assertThat(reason).hasSize(512);
                        softly.assertThat(reason).startsWith(SDG_REASON);
                        softly.assertThat(reason).endsWith(" [truncated]");
                    });
            softly.assertThat(batchOn(MONDAY).map(BatchOutcome::status))
                    .as("and the batch still ends where the reason says it ended")
                    .contains(FAILED);
        }

        /**
         * The one ending somebody outside this service reported, and what it must carry.
         *
         * <p>GENERATION_FAILED is systemdocgenerator's own verdict about the render, learned on its
         * {@code generation-failed} event. The row is the only place that says which mechanism
         * brought it, so a batch that ends under it without naming one is a row that claims
         * somebody answered and refuses to say who, and no later write can supply it.
         */
        @Test
        void a_generator_failure_without_attribution_is_refused() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
            }).as(WALKED).doesNotThrowAnyException();
            final UUID batchId = batchIdOn(MONDAY);

            softly.assertThatThrownBy(() -> store.markFailed(batchId,
                            BatchFailureReason.GENERATION_FAILED, SDG_REASON, null))
                    .as("this reason is somebody else's answer about the render, so a caller that "
                            + "cannot say whose answer it is has lost half of it")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("GENERATION_FAILED");
            softly.assertThat(batchOn(MONDAY))
                    .as("and nothing is written: no ending, and no reason to explain one")
                    .contains(new BatchOutcome(GENERATING, null, null));
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("the batch is still the batch its register belongs to")
                    .isEqualTo(1);
        }

        /**
         * The five endings this service reached on its own, and what they must not carry.
         *
         * <p>The payload was never stored, the request was never delivered, it was refused, the
         * batch could not be assembled at all, or the next scheduled run began and the answer still
         * had not come. Nobody outside this service was ever in a position to answer, so naming
         * EVENT on one of these credits a decision that mechanism never made, and leaves the one
         * column that says which mechanism delivered an outcome naming one for an outcome nobody
         * delivered.
         */
        @Test
        void a_service_failure_with_attribution_is_refused() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                assembled(MONDAY, mine(store.activeUnbatched()));
            }).as(WALKED).doesNotThrowAnyException();
            final UUID batchId = batchIdOn(MONDAY);

            softly.assertThatThrownBy(() -> store.markFailed(batchId,
                            BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE, null, CompletedBy.EVENT))
                    .as("the file service was never written to, so no event and no query could "
                            + "have reported anything about a render nobody was asked for")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PAYLOAD_STORE_UNAVAILABLE");
            softly.assertThat(batchOn(MONDAY).map(BatchOutcome::status))
                    .as("and the batch is exactly where assembly left it")
                    .contains("PENDING");
            softly.assertThat(completedByOn(MONDAY))
                    .as("nothing was attributed, because nothing was written")
                    .isEmpty();
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("and the stamp this ending would have released is still on the register")
                    .isEqualTo(1);
        }

    }

    /**
     * The other half of {@code markFailed}, and the only write here a person types by hand.
     *
     * <p>Three of the six reasons leave the stamp in place because systemdocgenerator was asked and
     * document may yet exist, so no later run will ever pick those registers up again: a stamped row
     * is not active and unbatched. Re-rendering that day is therefore a decision a person makes, and
     * this is the statement the decision is written as.
     *
     * <p><strong>The refusal matters more than the release, so four of the eight cases are
     * refusals.</strong> A stamp cleared off a GENERATING batch's rows would let the next run
     * assemble a second batch for a day systemdocgenerator is still rendering and both would reach
     * the same Youth Offending Team; a GENERATED batch holds a document and a NOTIFIED one has
     * already been sent. Only the release's own order and the already-released ending are about what
     * it does rather than about what it will not do.
     *
     * <p><strong>And two are about the register a re-share has replaced under the stamp.</strong>
     * The recording predicate only supersedes an incumbent that is active and <em>unbatched</em>, so
     * a hearing re-shared while its first register is stamped into a batch that has failed leaves
     * two RECORDED rows for one key - one stamped, one active. Clearing the stamp off the older one
     * would either collide with {@code idx_output_active_register_key}, failing the whole
     * regeneration, or - once the re-share has been batched in its turn - hand the superseded
     * register back as something to render and e-mail after the register that replaced it. So the
     * release supersedes it as it unstamps it and does not answer with it.
     */
    @Nested
    @DisplayName("giving a failed batch's registers back")
    class Releasing {

        /**
         * A share of the hearing stamped before the register the batch already holds.
         *
         * <p>Earlier than {@code MONDAY_SHARED} and on the same register date, because it is the
         * ordering and not the day that decides which of two shares of one hearing is current.
         */
        private static final Instant MONDAY_OVERTAKEN = Instant.parse("2026-08-24T07:15:00Z");

        @Test
        void a_release_should_supersede_the_share_it_overtook() {
            final DistributionCommand batched = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand overtaken = seededCommand(HEARING_ONE, MONDAY_OVERTAKEN);
            final List<RegisterRecord> released = new ArrayList<>();

            softly.assertThatCode(() -> {
                record(batched, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
                store.markFailed(monday.batchId(), BatchFailureReason.GENERATION_FAILED, SDG_REASON,
                        CompletedBy.EVENT);
                // The hearing's earlier share is delivered after the later one is already in the
                // batch, so the recorder writes it active: a batched register is not its to
                // supersede. It then holds the day's active-register key against the release an
                // operator types.
                record(overtaken, document(HEARING_ONE, MONDAY, MONDAY_OVERTAKEN), APPLICANT,
                        RecordedFlagState.ON);
                released.addAll(store.releaseFailed(monday.batchId()));
            }).as(SEAM).doesNotThrowAnyException();

            softly.assertThat(released)
                    .as("the register the day is still to render is the one the batch held: the "
                            + "share it overtook is the earlier of the two, and a key the release "
                            + "cannot decide is a day an operator could never regenerate")
                    .extracting(RegisterRecord::outputId)
                    .containsExactly(outputIdOf(batched).orElse(null));
            softly.assertThat(supersessionOf(overtaken).map(SupersessionPair::supersededBy))
                    .as("so the earlier share is superseded against the register coming back, "
                            + "which is the same total order read the other way")
                    .contains(outputIdOf(batched).orElse(null));
            softly.assertThat(statusesOn(MONDAY))
                    .as("one SUPERSEDED and one RECORDED, which is the invariant the whole batch "
                            + "half is written against: at most one active row per key")
                    .containsExactlyInAnyOrder(RECORDED, SUPERSEDED);
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("and the stamp is gone, because the batch that failed holds nothing now")
                    .isZero();
            softly.assertThat(activeUnbatched())
                    .as("so the day's one assemblable register is the one that came back")
                    .extracting(RegisterRecord::outputId)
                    .containsExactly(outputIdOf(batched).orElse(null));
        }

        /**
         * All three rows at once, which is where the {@code overtaken} clause has to stand down.
         *
         * <p>The two cases above are each one row beside the released one: a re-share that came
         * after it, or a share it overtook. A day can hold both. The batch fails on a reason that
         * keeps its stamp, the hearing is re-shared and that re-share is assembled into a batch of
         * its own, and only then does a share older than either arrive - recorded active, because
         * by that point there is no active unbatched row for the recorder to weigh it against.
         *
         * <p>The two clauses are then asking opposite questions about one register, and only one of
         * them may answer. {@code overtaken} is guarded on the successor search coming back empty
         * for exactly this: a register that is itself being superseded is leaving the active index
         * as it goes, so it takes no key and displaces nobody. Without that guard the older share
         * would be superseded against a register the same statement is superseding - pointed at a
         * row nothing will ever render - and the day would be left with no active register at all,
         * which no run and no {@code generate-register} could recover.
         */
        @Test
        void a_release_should_displace_nothing_where_the_register_is_itself_superseded() {
            final DistributionCommand batched = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand reshare = seededCommand(HEARING_ONE, MONDAY_RESHARED);
            final DistributionCommand overtaken = seededCommand(HEARING_ONE, MONDAY_OVERTAKEN);
            final List<RegisterRecord> released = new ArrayList<>();

            softly.assertThatCode(() -> {
                record(batched, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
                store.markFailed(monday.batchId(), BatchFailureReason.GENERATION_FAILED, SDG_REASON,
                        CompletedBy.EVENT);
                record(reshare, document(HEARING_ONE, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch second = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(second.batchId(), SECOND_PAYLOAD_FILE_ID);
                // Both of the key's other rows are stamped by now, so the late share meets no
                // active unbatched incumbent and is recorded active itself.
                record(overtaken, document(HEARING_ONE, MONDAY, MONDAY_OVERTAKEN), APPLICANT,
                        RecordedFlagState.ON);
                released.addAll(store.releaseFailed(monday.batchId()));
            }).as(SEAM).doesNotThrowAnyException();

            softly.assertThat(released)
                    .as("nothing comes back: the register this batch held has been replaced, and "
                            + "rendering the day from it would put a superseded document behind "
                            + "the current one")
                    .isEmpty();
            softly.assertThat(supersessionOf(batched).map(SupersessionPair::supersededBy))
                    .as("it is superseded against the register that replaced it, exactly as it is "
                            + "when that register is the only other row of the key")
                    .contains(outputIdOf(reshare).orElse(null));
            softly.assertThat(supersessionOf(overtaken))
                    .as("and the share it had overtaken is left alone: a register on its way out "
                            + "of the active index takes no key, so there is nothing for it to "
                            + "displace, and displacing it would point the older row at a row "
                            + "nothing will render")
                    .contains(new SupersessionPair(null, null));
            softly.assertThat(activeUnbatched())
                    .as("so the day keeps exactly one assemblable register - the late share, the "
                            + "re-share being stamped into the batch in flight")
                    .extracting(RegisterRecord::outputId)
                    .containsExactly(outputIdOf(overtaken).orElse(null));
            softly.assertThat(statusesOn(MONDAY))
                    .as("one SUPERSEDED and two RECORDED, of which only one is active: the "
                            + "invariant is one active row per key, not one row")
                    .containsExactlyInAnyOrder(SUPERSEDED, RECORDED, RECORDED);
        }


        @Test
        void a_register_a_re_share_has_replaced_should_be_superseded_rather_than_given_back() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand reshare = seededCommand(HEARING_ONE, MONDAY_RESHARED);
            final List<RegisterRecord> released = new ArrayList<>();

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
                store.markFailed(monday.batchId(), BatchFailureReason.GENERATION_FAILED, SDG_REASON,
                        CompletedBy.EVENT);
                // The re-share arrives while the first register is still stamped into the batch
                // that failed, which is the one arrangement the recording predicate leaves two
                // RECORDED rows for: it supersedes an incumbent that is unbatched, and this one is
                // not.
                record(reshare, document(HEARING_ONE, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);
                released.addAll(store.releaseFailed(monday.batchId()));
            }).as(SEAM).doesNotThrowAnyException();

            softly.assertThat(released)
                    .as("the register the day's batch failed on has already been replaced, so it is "
                            + "not answered with: a caller re-assembling it would render the "
                            + "hearing as it stood before the re-share")
                    .isEmpty();
            softly.assertThat(supersessionOf(first).map(SupersessionPair::supersededBy))
                    .as("it is superseded as its stamp is cleared, and by the register that "
                            + "replaced it - the pair is what says which register replaced which")
                    .contains(outputIdOf(reshare).orElse(null));
            softly.assertThat(statusesOn(MONDAY))
                    .as("one SUPERSEDED and one RECORDED, which is the invariant the whole batch "
                            + "half is written against: at most one active row per key")
                    .containsExactlyInAnyOrder(SUPERSEDED, RECORDED);
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("and the stamp is gone, because the batch that failed holds nothing now")
                    .isZero();
            softly.assertThat(activeUnbatched())
                    .as("so the day's one assemblable register is the re-share and nothing else")
                    .extracting(RegisterRecord::outputId)
                    .containsExactly(outputIdOf(reshare).orElse(null));
        }

        @Test
        void a_register_replaced_by_a_re_share_that_is_itself_batched_should_not_come_back() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand reshare = seededCommand(HEARING_ONE, MONDAY_RESHARED);
            final List<RegisterRecord> released = new ArrayList<>();

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
                store.markFailed(monday.batchId(), BatchFailureReason.GENERATION_FAILED, SDG_REASON,
                        CompletedBy.EVENT);
                record(reshare, document(HEARING_ONE, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);
                // The failed batch is terminal, so the next automatic run assembles the re-share
                // into a batch of its own before anybody types the release.
                final RegisterBatch second = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(second.batchId(), SECOND_PAYLOAD_FILE_ID);
                released.addAll(store.releaseFailed(monday.batchId()));
            }).as(SEAM).doesNotThrowAnyException();

            softly.assertThat(released)
                    .as("giving this register back would have the day rendered a second time from "
                            + "the register the re-share replaced, and a Youth Offending Team would "
                            + "read the superseded one after the current one")
                    .isEmpty();
            softly.assertThat(supersessionOf(first).map(SupersessionPair::supersededBy))
                    .as("it is superseded against the register that replaced it, whether or not "
                            + "that one has reached a batch yet")
                    .contains(outputIdOf(reshare).orElse(null));
            softly.assertThat(activeUnbatched())
                    .as("and nothing of this day is waiting: the re-share belongs to the batch in "
                            + "flight and the register it replaced belongs to nothing")
                    .isEmpty();
            softly.assertThat(statusesOn(MONDAY))
                    .containsExactlyInAnyOrder(SUPERSEDED, RECORDED);
        }

        /**
         * The one arrangement in which the row beside the released one is older rather than newer.
         *
         * <p>Both cases above release the batch holding the key's older row, so the row found
         * beside it is the re-share that replaced it. A day reaches the opposite shape through the
         * ordinary path: a first batch is notified, the hearing is shared again - the recording
         * predicate supersedes an incumbent that is RECORDED and unbatched, and a notified row is
         * neither - and the re-share is assembled into a batch of its own, which then fails under
         * one of the three reasons that keep the stamp. The release an operator then types is
         * against the batch holding the <em>newer</em> row, and the only other row of the key is the
         * register that was sent this morning.
         *
         * <p>A search that asked only for another unsuperseded row would answer with that sent one
         * and supersede the re-share against it, then leave it out of the answer - so the command
         * would print a day it released nothing for and exit 0, while the register the estate
         * shared to correct the sent one has been withdrawn for good and no run will reach it again.
         */
        @Test
        void a_released_re_share_should_come_back_though_an_earlier_register_was_sent() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand reshare = seededCommand(HEARING_ONE, MONDAY_RESHARED);
            final List<RegisterRecord> released = new ArrayList<>();

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                walkedToNotified(monday, PAYLOAD_FILE_ID, DOCUMENT_FILE_ID);
                record(reshare, document(HEARING_ONE, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch second = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(second.batchId(), SECOND_PAYLOAD_FILE_ID);
                store.markFailed(second.batchId(), BatchFailureReason.GENERATION_FAILED, SDG_REASON,
                        CompletedBy.EVENT);
                released.addAll(store.releaseFailed(second.batchId()));
            }).as(SEAM).doesNotThrowAnyException();

            softly.assertThat(released)
                    .as("the re-share is the day's register to render again: the row beside it was "
                            + "sent this morning and is what the re-share corrects, not what "
                            + "replaced it")
                    .extracting(RegisterRecord::outputId)
                    .containsExactly(outputIdOf(reshare).orElse(null));
            softly.assertThat(supersessionOf(reshare))
                    .as("so nothing is written against it: a supersession the wrong way round "
                            + "would leave the command reporting a release it had silently "
                            + "withdrawn the register for")
                    .contains(new SupersessionPair(null, null));
            softly.assertThat(statusesOn(MONDAY))
                    .as("the sent register stays NOTIFIED, carrying what reached the Youth "
                            + "Offending Team, and the re-share is RECORDED again")
                    .containsExactlyInAnyOrder(NOTIFIED, RECORDED);
            softly.assertThat(activeUnbatched())
                    .as("and it is what the next run assembles, which is the whole of what the "
                            + "release is for")
                    .extracting(RegisterRecord::outputId)
                    .containsExactly(outputIdOf(reshare).orElse(null));
        }

        /**
         * The key's two rows shared at one instant, the register that arrived later sorting first.
         *
         * <p>The same disagreement statement 9's own pair is about, in the statement a person
         * types. {@code register_time} is the results' shared moment and two shares of one hearing
         * can carry the same one; statement 1 supersedes an incumbent whose instant is not
         * <em>after</em> the arriving register's, so of two registers sharing an instant the one
         * that arrived second is the current one, whatever its identity sorts like.
         *
         * <p>Where the identity alone breaks the tie and the later row sorts first, the successor is
         * not seen: unstamping the older row makes it active beside the re-share,
         * {@code idx_output_active_register_key} refuses the write, and the whole release fails on a
         * key nothing said was wrong - the day the operator asked to have rendered again is left
         * exactly as it was, with no register released and no reason given that names the pair.
         */
        @Test
        void a_release_should_supersede_against_an_equal_time_re_share_that_sorts_first() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand reshare = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final List<RegisterRecord> released = new ArrayList<>();

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
                store.markFailed(monday.batchId(), BatchFailureReason.GENERATION_FAILED, SDG_REASON,
                        CompletedBy.EVENT);
                // A reason that keeps the stamp, so the re-share arriving at the same instant
                // supersedes nothing and the key is left holding two RECORDED rows.
                record(reshare, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                identifiedInOrder(reshare, first);
                released.addAll(store.releaseFailed(monday.batchId()));
            }).as(SEAM).doesNotThrowAnyException();

            softly.assertThat(supersessionOf(first).map(SupersessionPair::supersededBy))
                    .as("the register the batch failed on is superseded as its stamp is cleared, "
                            + "and against the register that arrived after it - which is the "
                            + "register the recorder would itself have superseded it against")
                    .contains(outputIdOf(reshare).orElse(null));
            softly.assertThat(released)
                    .as("so it is not answered with: a caller re-assembling it would render the "
                            + "hearing as it stood before the second share of the same instant")
                    .isEmpty();
            softly.assertThat(statusesOn(MONDAY))
                    .as("one SUPERSEDED and one RECORDED, which is the invariant the whole batch "
                            + "half is written against: at most one active row per key")
                    .containsExactlyInAnyOrder(SUPERSEDED, RECORDED);
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("and the stamp is gone, which is the whole of what the person asked for")
                    .isZero();
            softly.assertThat(activeUnbatched())
                    .as("so the day's one assemblable register is the re-share and nothing else")
                    .extracting(RegisterRecord::outputId)
                    .containsExactly(outputIdOf(reshare).orElse(null));
        }

        /**
         * The same pair the other way round, so the rule is pinned in both directions.
         *
         * <p><strong>[A] characterisation.</strong> This is the order the identity tie-break
         * happened to agree with, so the statement has answered this way since it landed and
         * nothing here changes it. It is written beside the case above for the reason the pair in
         * {@code Failure} is written as a pair: one order alone is also satisfied by a search that
         * ranks the key's rows by identity and nothing else, which is the search that loses a
         * register in the other order.
         */
        @Test
        void a_release_should_supersede_against_an_equal_time_re_share_that_sorts_last() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand reshare = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final List<RegisterRecord> released = new ArrayList<>();

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
                store.markFailed(monday.batchId(), BatchFailureReason.GENERATION_FAILED, SDG_REASON,
                        CompletedBy.EVENT);
                record(reshare, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                identifiedInOrder(first, reshare);
                released.addAll(store.releaseFailed(monday.batchId()));
            }).as(SEAM).doesNotThrowAnyException();

            softly.assertThat(supersessionOf(first).map(SupersessionPair::supersededBy))
                    .as("the successor is the same register whichever way the pair sorts: which of "
                            + "the two arrived second is what decides it, and the identity only "
                            + "breaks a tie the arrival instants leave")
                    .contains(outputIdOf(reshare).orElse(null));
            softly.assertThat(released).isEmpty();
            softly.assertThat(statusesOn(MONDAY))
                    .containsExactlyInAnyOrder(SUPERSEDED, RECORDED);
            softly.assertThat(stampedRowsOn(MONDAY)).isZero();
            softly.assertThat(activeUnbatched())
                    .extracting(RegisterRecord::outputId)
                    .containsExactly(outputIdOf(reshare).orElse(null));
        }

        /**
         * The same POST row {@code Failure} is about, in the statement a person types.
         *
         * <p>Increment 001 writes a PENDING, POSTED or FAILED row into this table for every register
         * it sends to progression, on the same three key columns and with a register instant beside
         * them, and a rolling deployment has that writer live at the same time as this one.
         *
         * <p>Where the successor search admits it, the release supersedes the register against a
         * POST and answers with nothing: the operator's command prints a day it released nothing
         * for and exits 0, while the register the day is owed a document from has been withdrawn for
         * good - not active, not unbatched, and reachable by no later run. Only RECORDED, GENERATED
         * and NOTIFIED can be a replacement.
         */
        @Test
        void a_release_should_give_its_register_back_though_a_post_row_shares_the_key() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand posting = postedToProgression(HEARING_ONE, MONDAY);
            final List<RegisterRecord> released = new ArrayList<>();

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
                // A reason that keeps the stamp, so the release is the one a person types.
                store.markFailed(monday.batchId(), BatchFailureReason.GENERATION_FAILED, SDG_REASON,
                        CompletedBy.EVENT);
                released.addAll(store.releaseFailed(monday.batchId()));
            }).as(SEAM).doesNotThrowAnyException();

            softly.assertThat(registerTimeOf(posting).orElse(null))
                    .as("the arrangement is the one a successor search can act on: the POST row is "
                            + "the later row of the key, so nothing but its status keeps it out")
                    .isAfter(MONDAY_SHARED);
            softly.assertThat(released)
                    .as("the day's register is answered with, for the caller to re-assemble: a POST "
                            + "to progression is not a register that replaced it, so there is "
                            + "nothing here to leave it out of the answer for")
                    .extracting(RegisterRecord::outputId)
                    .containsExactly(outputIdOf(first).orElse(null));
            softly.assertThat(statusOf(first))
                    .as("it is RECORDED again, which is what makes it assemblable")
                    .contains(RECORDED);
            softly.assertThat(supersessionOf(first))
                    .as("and nothing is written against it, because a supersession names the "
                            + "register that replaced this one and no register did")
                    .contains(new SupersessionPair(null, null));
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("the stamp is gone, which is the whole of what the person asked for")
                    .isZero();
            softly.assertThat(activeUnbatched())
                    .as("so the next run assembles it, under a fresh batch identity")
                    .extracting(RegisterRecord::outputId)
                    .containsExactly(outputIdOf(first).orElse(null));
            softly.assertThat(statusOf(posting))
                    .as("and the POST row is left exactly as 001 wrote it")
                    .contains(POST_PENDING);
        }

        /**
         * The successor is a register systemdocgenerator has already answered for.
         *
         * <p><strong>[A] characterisation.</strong> The pair of the {@code Failure} case that
         * states a sent successor, and green on introduction for the same reason: this statement
         * has admitted RECORDED, GENERATED and NOTIFIED since the search was narrowed to the live
         * half, and what was missing was a case reaching past the first of the three.
         *
         * <p>The case three above -
         * {@code a_register_replaced_by_a_re_share_that_is_itself_batched_should_not_come_back} -
         * gets its successor as far as a batch in flight and no further: {@code markRequested}
         * moves the batch to GENERATING and leaves its registers RECORDED, so the row that search
         * finds is RECORDED like every other successor here. This one walks the same batch a step
         * on, to where the document exists under its correlation, so the row beside the released
         * one is GENERATED.
         *
         * <p>Which is the arrangement the operator's command must not undo. A search that asked
         * only for a RECORDED successor finds nothing beside the released register, hands it back,
         * and the next run renders the day a second time from the register the re-share replaced -
         * and this time both documents exist, so a Youth Offending Team reads the superseded one
         * after the current one.
         */
        @Test
        void a_release_should_supersede_against_a_re_share_a_document_already_exists_for() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand reshare = seededCommand(HEARING_ONE, MONDAY_RESHARED);
            final List<RegisterRecord> released = new ArrayList<>();
            final AtomicReference<UUID> stale = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                stale.set(monday.batchId());
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
                // A reason that keeps the stamp, so the release is the one a person types.
                store.markFailed(monday.batchId(), BatchFailureReason.GENERATION_FAILED, SDG_REASON,
                        CompletedBy.EVENT);
                record(reshare, document(HEARING_ONE, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);
                // The next run assembles the re-share and systemdocgenerator answers it, so by the
                // time the release is typed the successor is GENERATED rather than RECORDED.
                walkedToGenerated(assembled(MONDAY, mine(store.activeUnbatched())),
                        SECOND_PAYLOAD_FILE_ID, SECOND_DOCUMENT_FILE_ID);
                released.addAll(store.releaseFailed(stale.get()));
            }).as(SEAM).doesNotThrowAnyException();

            softly.assertThat(statusOf(reshare))
                    .as("the arrangement is the one the claim needs: the row beside the released "
                            + "one is past RECORDED, so a search that asked only for a RECORDED "
                            + "successor would see nothing at all")
                    .contains(GENERATED);
            softly.assertThat(released)
                    .as("so the register is not answered with: a caller re-assembling it would "
                            + "render the hearing as it stood before the re-share the day's own "
                            + "document has already been rendered from")
                    .isEmpty();
            softly.assertThat(supersessionOf(first).map(SupersessionPair::supersededBy))
                    .as("it is superseded against the register that replaced it, whatever state "
                            + "that one has reached - what makes it the replacement is that it "
                            + "came later and is still live")
                    .contains(outputIdOf(reshare).orElse(null));
            softly.assertThat(statusesOn(MONDAY))
                    .as("one SUPERSEDED and one GENERATED, which is the invariant the whole batch "
                            + "half is written against: at most one active row per key")
                    .containsExactlyInAnyOrder(SUPERSEDED, GENERATED);
            softly.assertThat(stampedWith(stale.get()))
                    .as("and the batch the person released holds nothing now")
                    .isZero();
            softly.assertThat(activeUnbatched())
                    .as("nothing of this day is waiting either: the re-share belongs to the batch "
                            + "its document was rendered for, and the register it replaced belongs "
                            + "to nothing")
                    .isEmpty();
        }

        @Test
        void a_failed_batchs_registers_should_be_given_back_in_the_order_the_batch_held_them() {
            final List<RegisterRecord> released = new ArrayList<>();

            softly.assertThatCode(() -> {
                // Recorded newest first on purpose: the batch holds them oldest first, so a
                // statement that answered in the order the rows were written would still pass a
                // case whose rows were recorded in that order.
                record(seededCommand(HEARING_TWO, MONDAY_RESHARED),
                        document(HEARING_TWO, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(seededCommand(HEARING_ONE, MONDAY_SHARED),
                        document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
                store.markFailed(monday.batchId(), BatchFailureReason.GENERATION_FAILED, SDG_REASON,
                        CompletedBy.EVENT);
                released.addAll(store.releaseFailed(monday.batchId()));
            }).as(SEAM).doesNotThrowAnyException();

            softly.assertThat(released)
                    .as("answered rather than left to be read back, so a caller cannot assemble a "
                            + "row this call did not release; in the order the batch held them, "
                            + "because the first of them names the file the day is rendered under")
                    .extracting(RegisterRecord::hearingId)
                    .containsExactly(HEARING_ONE, HEARING_TWO);
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("the stamp is cleared, which is the whole of what the person asked for")
                    .isZero();
            softly.assertThat(statusesOn(MONDAY))
                    .as("and nothing else about the registers moved: no document was ever produced "
                            + "from them and nobody was ever told")
                    .containsExactly(RECORDED, RECORDED);
            softly.assertThat(batchOn(MONDAY))
                    .as("the batch row is left FAILED carrying what happened to it, because the "
                            + "store is the audit of what this service decided and a re-run is "
                            + "exactly when that audit is read")
                    .contains(new BatchOutcome(FAILED, "GENERATION_FAILED", SDG_REASON));
            softly.assertThat(activeUnbatched())
                    .as("and the registers are active and unbatched again, which is what makes the "
                            + "day assemblable at all")
                    .extracting(RegisterRecord::hearingId)
                    .containsExactlyInAnyOrder(HEARING_ONE, HEARING_TWO);
        }

        @Test
        void a_batch_systemdocgenerator_is_still_rendering_should_release_nothing() {
            softly.assertThatCode(() -> {
                record(seededCommand(HEARING_ONE, MONDAY_SHARED),
                        document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
            }).as(WALKED).doesNotThrowAnyException();
            final UUID batchId = batchIdOn(MONDAY);

            softly.assertThatThrownBy(() -> store.releaseFailed(batchId))
                    .as("a stamp cleared off a batch systemdocgenerator is still rendering lets the "
                            + "next run assemble a second batch for the day, and both of them reach "
                            + "the same Youth Offending Team")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(GENERATING);
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("so the stamp stays exactly where the render request left it")
                    .isEqualTo(1);
            softly.assertThat(activeUnbatched())
                    .as("and nothing is waiting: the register belongs to a batch in flight")
                    .isEmpty();
        }

        @Test
        void a_batch_holding_a_document_should_release_nothing() {
            softly.assertThatCode(() -> {
                record(seededCommand(HEARING_ONE, MONDAY_SHARED),
                        document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                walkedToGenerated(assembled(MONDAY, mine(store.activeUnbatched())),
                        PAYLOAD_FILE_ID, DOCUMENT_FILE_ID);
            }).as(WALKED).doesNotThrowAnyException();
            final UUID batchId = batchIdOn(MONDAY);

            softly.assertThatThrownBy(() -> store.releaseFailed(batchId))
                    .as("the document exists under this correlation, so the day is owed its e-mails "
                            + "rather than a second render; releasing the rows here would throw the "
                            + "document away and build another one")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(GENERATED);
            softly.assertThat(stampedRowsOn(MONDAY)).isEqualTo(1);
            softly.assertThat(statusesOn(MONDAY))
                    .as("and the register still says a document was produced from it")
                    .containsExactly(GENERATED);
        }

        @Test
        void a_notified_batch_should_release_nothing() {
            softly.assertThatCode(() -> {
                record(seededCommand(HEARING_ONE, MONDAY_SHARED),
                        document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                walkedToNotified(assembled(MONDAY, mine(store.activeUnbatched())),
                        PAYLOAD_FILE_ID, DOCUMENT_FILE_ID);
            }).as(WALKED).doesNotThrowAnyException();
            final UUID batchId = batchIdOn(MONDAY);

            softly.assertThatThrownBy(() -> store.releaseFailed(batchId))
                    .as("this register has reached the Youth Offending Team; giving it back would "
                            + "have the day rendered and e-mailed a second time about children who "
                            + "were already written about")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(NOTIFIED);
            softly.assertThat(stampedRowsOn(MONDAY)).isEqualTo(1);
            softly.assertThat(statusesOn(MONDAY)).containsExactly(NOTIFIED);
        }

        @Test
        void a_failure_that_had_already_released_its_rows_should_release_nothing_and_refuse_nothing() {
            final List<RegisterRecord> released = new ArrayList<>();

            softly.assertThatCode(() -> {
                record(seededCommand(HEARING_ONE, MONDAY_SHARED),
                        document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markFailed(monday.batchId(), BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE,
                        null, null);
                released.addAll(store.releaseFailed(monday.batchId()));
            }).as(SEAM).doesNotThrowAnyException();

            softly.assertThat(released)
                    .as("the two reasons that say the batch never left this service released the "
                            + "stamp as they failed, so a person's command finds nothing left to "
                            + "give back - and says so rather than refusing a batch that did fail")
                    .isEmpty();
            softly.assertThat(activeUnbatched())
                    .as("the register was active and unbatched before the command and still is")
                    .extracting(RegisterRecord::hearingId)
                    .containsExactly(HEARING_ONE);
        }

        @Test
        void a_batch_nobody_assembled_should_release_nothing() {
            softly.assertThatThrownBy(() -> store.releaseFailed(UUID.randomUUID()))
                    .as("an identity nothing was ever stamped with is a correlation the caller has "
                            + "wrong, and an empty release would have the regeneration report a day "
                            + "given back that this service never held")
                    .isInstanceOf(IllegalStateException.class);
        }
    }


    /**
     * The ending a run gives a batch that was still waiting for its render when the next one began.
     *
     * <p>One statement, whose {@code WHERE} clause <em>is</em> the staleness rule: it fails every
     * matching batch under {@code NOT_COMPLETED_BY_NEXT_RUN} and releases its registers in the same
     * transaction, and answers with the batches it actually changed. There is no read followed by a
     * mark, and the two failures that shape is open to are the two properties asserted here.
     *
     * <p><strong>A lost register.</strong> A mark that landed while its release did not would leave
     * registers stamped to a terminal batch, and {@code ACTIVE_UNBATCHED}'s predicate is
     * {@code batch_id IS NULL} - so no later run and no command would ever see them again. That is
     * the precise failure this increment exists to end, and
     * {@link #the_mark_and_the_release_are_one_transaction()} refuses the release on purpose to
     * watch the mark go down with it.
     *
     * <p><strong>A refused mark ending the night.</strong> A batch that stopped being stale between
     * the decision and the write must simply not match, rather than be marked and refused: run
     * inline in a night's generation, one refusal would cost every court centre its document.
     * {@link #a_batch_that_no_longer_matches_yields_zero_rows_and_no_error()} is that property read
     * the way a caller reads it - zero rows is an answer.
     *
     * <p>The ages are written by the database's own clock, as the stamps themselves are: a case
     * ages the batch it seeded and then states a cutoff, which is exactly the shape the pass has.
     * No case states a cutoff in the future, so no case can reach a batch another suite sharing the
     * container is holding.
     */
    @Nested
    @DisplayName("releasing the batches a run gave up on")
    class StaleRelease {

        /** Older than any cutoff stated here, which is what the night between two runs leaves. */
        private static final Duration LAST_NIGHT = Duration.ofHours(2);

        /** Younger than any cutoff stated here: the render an operator started a moment ago. */
        private static final Duration MOMENTS_AGO = Duration.ofMinutes(5);

        /** Old enough for the scheduled cutoff and young enough for the manual one (FR-017). */
        private static final Duration BETWEEN_THE_CUTOFFS = Duration.ofMinutes(45);

        /** What a run gives up after, and the cutoff every case but the manual one states. */
        private static final Duration STALE_AFTER = Duration.ofMinutes(30);

        /** The longer grace a batch an operator asked for is given (FR-017). */
        private static final Duration MANUAL_GRACE = Duration.ofMinutes(60);

        /** The bounded code the pass writes, as the column holds it. */
        private static final String NOT_COMPLETED = "NOT_COMPLETED_BY_NEXT_RUN";

        /**
         * A share of the hearing stamped before the register the batch already holds.
         *
         * <p>Earlier than {@code MONDAY_SHARED} and on the same register date, because it is the
         * ordering and not the day that decides which of two shares of one hearing is current.
         */
        private static final Instant MONDAY_OVERTAKEN = Instant.parse("2026-08-24T07:15:00Z");

        @Test
        void a_generating_batch_past_its_cutoff_is_failed_and_its_registers_released() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand second = seededCommand(HEARING_TWO, MONDAY_SHARED);
            final AtomicReference<StaleReleaseOutcome> measured = new AtomicReference<>();
            final AtomicReference<StaleReleaseOutcome> released = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(second, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), UUID.randomUUID());
                // The assembly is a night old and the request is not, which is the batch a run
                // assembled yesterday and asked about again a moment ago.
                ageBatch(monday.batchId(), LAST_NIGHT, Duration.ZERO);
                measured.set(store.failAndReleaseStale(cutoff(STALE_AFTER), cutoff(STALE_AFTER)));
                ageBatch(monday.batchId(), Duration.ZERO, LAST_NIGHT);
                released.set(store.failAndReleaseStale(cutoff(STALE_AFTER), cutoff(STALE_AFTER)));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(mineReleased(measured.get()))
                    .as("a GENERATING batch is measured from when its render was requested and not "
                            + "from when it was assembled: a day re-requested a moment ago is a "
                            + "render in progress, and failing it would orphan one")
                    .isEmpty();
            softly.assertThat(batchOn(MONDAY))
                    .as("so the run stops waiting only once the request itself is old enough, and "
                            + "says why in the one bounded code that means the passage of time")
                    .contains(new BatchOutcome(FAILED, NOT_COMPLETED, null));
            softly.assertThat(statusesOn(MONDAY))
                    .as("nothing was ever sent about these registers, so nothing about them moved")
                    .containsExactly(RECORDED, RECORDED);
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("and the stamp is gone in the same act as the failure, because a register "
                            + "stamped to a terminal batch is invisible to every later run")
                    .isZero();
            softly.assertThat(activeUnbatched())
                    .as("which is what puts the court centre's day in tonight's batch after all")
                    .extracting(RegisterRecord::hearingId)
                    .containsExactlyInAnyOrder(HEARING_ONE, HEARING_TWO);
            softly.assertThat(mineReleased(released.get()))
                    .as("and the run is told what it gave back, by key and by count")
                    .extracting(ReleasedBatch::registerDate, ReleasedBatch::releasedRegisters)
                    .containsExactly(tuple(MONDAY, 2));
        }

        @Test
        void a_pending_batch_past_its_cutoff_is_failed_and_its_registers_released() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markPayloadMinted(monday.batchId(), UUID.randomUUID());
                ageBatch(monday.batchId(), LAST_NIGHT);
                store.failAndReleaseStale(cutoff(STALE_AFTER), cutoff(STALE_AFTER));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(requestedAtOn(MONDAY))
                    .as("the batch never reached the renderer, so it has no requested_at at all "
                            + "and is measured from the assembly instead")
                    .isEmpty();
            softly.assertThat(batchOn(MONDAY))
                    .as("whether the request ever left is not a question this service can ask any "
                            + "more, and both endings are the same: it did not complete in time")
                    .contains(new BatchOutcome(FAILED, NOT_COMPLETED, null));
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("released in the same act, exactly as a GENERATING batch's registers are")
                    .isZero();
            softly.assertThat(activeUnbatched())
                    .as("so the day is assemblable again by the run that gave up on it")
                    .extracting(RegisterRecord::hearingId)
                    .containsExactly(HEARING_ONE);
        }

        @Test
        void a_pending_batch_with_no_payload_id_is_released_too() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                ageBatch(monday.batchId(), LAST_NIGHT);
                store.failAndReleaseStale(cutoff(STALE_AFTER), cutoff(STALE_AFTER));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(payloadFileIdOn(MONDAY))
                    .as("the batch minted no payload at all, which is the row the retired reads "
                            + "excluded by asking about a payload id they had to have")
                    .isEmpty();
            softly.assertThat(batchOn(MONDAY))
                    .as("staleness is state and age, not progress (FR-020): without this the batch "
                            + "sits in flight for ever and defers its court centre day at every run")
                    .contains(new BatchOutcome(FAILED, NOT_COMPLETED, null));
            softly.assertThat(activeUnbatched())
                    .as("and its register is the next run's to batch, rather than nobody's")
                    .extracting(RegisterRecord::hearingId)
                    .containsExactly(HEARING_ONE);
        }

        @Test
        void a_generated_batch_is_never_matched_at_any_age() {
            final DistributionCommand monday = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand tuesday = seededCommand(HEARING_THREE, TUESDAY_SHARED);
            final AtomicReference<StaleReleaseOutcome> released = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(monday, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(tuesday, document(HEARING_THREE, TUESDAY, TUESDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final List<RegisterRecord> active = mine(store.activeUnbatched());
                final RegisterBatch generated =
                        assembled(MONDAY, recordsOn(active, MONDAY));
                walkedToGenerated(generated, UUID.randomUUID(), UUID.randomUUID());
                final RegisterBatch waiting = assembled(TUESDAY, recordsOn(active, TUESDAY));
                ageBatch(generated.batchId(), LAST_NIGHT);
                ageBatch(waiting.batchId(), LAST_NIGHT);
                released.set(store.failAndReleaseStale(cutoff(STALE_AFTER), cutoff(STALE_AFTER)));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(batchOn(MONDAY))
                    .as("a batch that holds a document is never failed by this pass at any age: "
                            + "somebody is owed e-mails about that document, and failing it would "
                            + "throw it away")
                    .contains(new BatchOutcome(GENERATED, null, null));
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("so its registers keep the stamp that says which document they are in")
                    .isEqualTo(1);
            softly.assertThat(batchOn(TUESDAY))
                    .as("and the statement did run: the day of the same age that was still waiting "
                            + "for its render is the one it gave up on")
                    .contains(new BatchOutcome(FAILED, NOT_COMPLETED, null));
            softly.assertThat(mineReleased(released.get()))
                    .as("which is the only day the run is told it released")
                    .extracting(ReleasedBatch::registerDate)
                    .containsExactly(TUESDAY);
        }

        @Test
        void a_manually_generated_batch_uses_the_longer_cutoff() {
            final DistributionCommand monday = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand tuesday = seededCommand(HEARING_THREE, TUESDAY_SHARED);
            final AtomicReference<StaleReleaseOutcome> first = new AtomicReference<>();
            final AtomicReference<StaleReleaseOutcome> second = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(monday, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(tuesday, document(HEARING_THREE, TUESDAY, TUESDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final List<RegisterRecord> active = mine(store.activeUnbatched());
                final RegisterBatch schedules = assembled(MONDAY, recordsOn(active, MONDAY));
                final List<RegisterRecord> operators = recordsOn(active, TUESDAY);
                final RegisterBatch typed = store.assemble(
                        manualBatchFor(TUESDAY, operators), operators);
                ageBatch(schedules.batchId(), BETWEEN_THE_CUTOFFS);
                ageBatch(typed.batchId(), BETWEEN_THE_CUTOFFS);
                first.set(store.failAndReleaseStale(cutoff(STALE_AFTER), cutoff(MANUAL_GRACE)));
                second.set(store.failAndReleaseStale(cutoff(STALE_AFTER), cutoff(STALE_AFTER)));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(mineReleased(first.get()))
                    .as("a manual generation holds no run lock and has the whole requesting "
                            + "deadline to ask for its renders (FR-017), so the schedule's batch of "
                            + "the same age goes and the operator's stays")
                    .extracting(ReleasedBatch::registerDate)
                    .containsExactly(MONDAY);
            softly.assertThat(mineReleased(second.get()))
                    .as("and the two cutoffs are genuinely two: the same batch matches once the "
                            + "cutoff it is judged by reaches it")
                    .extracting(ReleasedBatch::registerDate)
                    .containsExactly(TUESDAY);
        }

        @Test
        void a_batch_inside_its_cutoff_is_not_matched() {
            final DistributionCommand monday = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand tuesday = seededCommand(HEARING_THREE, TUESDAY_SHARED);
            final AtomicReference<StaleReleaseOutcome> released = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(monday, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(tuesday, document(HEARING_THREE, TUESDAY, TUESDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final List<RegisterRecord> active = mine(store.activeUnbatched());
                final RegisterBatch old = assembled(MONDAY, recordsOn(active, MONDAY));
                final RegisterBatch fresh = assembled(TUESDAY, recordsOn(active, TUESDAY));
                store.markRequested(old.batchId(), UUID.randomUUID());
                store.markRequested(fresh.batchId(), UUID.randomUUID());
                ageBatch(old.batchId(), LAST_NIGHT);
                ageBatch(fresh.batchId(), MOMENTS_AGO);
                released.set(store.failAndReleaseStale(cutoff(STALE_AFTER), cutoff(STALE_AFTER)));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(batchOn(TUESDAY))
                    .as("a render minutes old is very probably about to succeed, and the pass is "
                            + "only a safety net because of the age: without it the run would be a "
                            + "nightly destruction of whatever the evening was doing")
                    .contains(new BatchOutcome(GENERATING, null, null));
            softly.assertThat(stampedRowsOn(TUESDAY))
                    .as("so nothing about it is released, and the existing rule still defers its "
                            + "court centre day")
                    .isEqualTo(1);
            softly.assertThat(mineReleased(released.get()))
                    .as("while the batch that has been waiting since last night is given up on")
                    .extracting(ReleasedBatch::registerDate)
                    .containsExactly(MONDAY);
        }

        @Test
        void each_released_batch_is_announced_where_it_is_settled() {
            final DistributionCommand monday = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand tuesday = seededCommand(HEARING_THREE, TUESDAY_SHARED);
            final List<UUID> told = new ArrayList<>();
            final AtomicReference<StaleReleaseOutcome> released = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(monday, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(tuesday, document(HEARING_THREE, TUESDAY, TUESDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final List<RegisterRecord> active = mine(store.activeUnbatched());
                final RegisterBatch first = assembled(MONDAY, recordsOn(active, MONDAY));
                final RegisterBatch second = assembled(TUESDAY, recordsOn(active, TUESDAY));
                store.markRequested(first.batchId(), UUID.randomUUID());
                store.markRequested(second.batchId(), UUID.randomUUID());
                ageBatch(first.batchId(), LAST_NIGHT);
                ageBatch(second.batchId(), LAST_NIGHT);
                released.set(store.failAndReleaseStale(cutoff(STALE_AFTER), cutoff(STALE_AFTER),
                        recording(told)));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(told)
                    .as("every batch is committed by itself, so the caller is told about each one "
                            + "where it is settled rather than in an answer a later batch's "
                            + "refusal could take away with it - a walk that threw halfway would "
                            + "otherwise leave a night's released registers unaccounted for")
                    .containsExactlyElementsOf(released.get().released().stream()
                            .map(ReleasedBatch::batchId)
                            .toList());
            softly.assertThat(mineReleased(released.get()))
                    .as("and the two days this case stood up are both in it")
                    .hasSize(2);
        }

        @Test
        void a_batch_stamped_exactly_at_its_cutoff_is_stale() {
            final DistributionCommand monday = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand tuesday = seededCommand(HEARING_THREE, TUESDAY_SHARED);
            final Instant exactly = cutoff(STALE_AFTER).truncatedTo(ChronoUnit.MILLIS);
            final AtomicReference<StaleReleaseOutcome> released = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(monday, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(tuesday, document(HEARING_THREE, TUESDAY, TUESDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final List<RegisterRecord> active = mine(store.activeUnbatched());
                final RegisterBatch onIt = assembled(MONDAY, recordsOn(active, MONDAY));
                final RegisterBatch justInside = assembled(TUESDAY, recordsOn(active, TUESDAY));
                stampBatch(onIt.batchId(), exactly);
                stampBatch(justInside.batchId(), exactly.plusSeconds(1));
                released.set(store.failAndReleaseStale(exactly, exactly));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(batchOn(MONDAY))
                    .as("a batch stamped at exactly the cutoff is stale: the rule is at or before "
                            + "it, so the batch that has waited precisely the minimum age is the "
                            + "first one the pass is for, not the last one it misses")
                    .contains(new BatchOutcome(FAILED, NOT_COMPLETED, null));
            softly.assertThat(batchOn(TUESDAY))
                    .as("and one second the younger side of it is not, which is the other half of "
                            + "the same boundary")
                    .contains(new BatchOutcome(PENDING_STATUS, null, null));
            softly.assertThat(mineReleased(released.get()))
                    .as("so the run is told about the one day and not the other")
                    .extracting(ReleasedBatch::registerDate)
                    .containsExactly(MONDAY);
        }

        @Test
        void no_batch_a_run_has_finished_with_is_ever_matched_at_any_age() {
            final AtomicReference<UUID> failed = new AtomicReference<>();
            final AtomicReference<UUID> notified = new AtomicReference<>();
            final AtomicReference<UUID> partlyNotified = new AtomicReference<>();
            final AtomicReference<UUID> toldNobody = new AtomicReference<>();
            final AtomicReference<UUID> waiting = new AtomicReference<>();
            final AtomicReference<StaleReleaseOutcome> released = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(seededCommand(HEARING_ONE, MONDAY_SHARED),
                        document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch generationFailed =
                        assembled(MONDAY, mine(store.activeUnbatched()));
                failed.set(generationFailed.batchId());
                store.markRequested(generationFailed.batchId(), UUID.randomUUID());
                store.markFailed(generationFailed.batchId(), BatchFailureReason.GENERATION_FAILED,
                        SDG_REASON, CompletedBy.EVENT);

                record(seededCommand(HEARING_TWO, MONDAY_RESHARED),
                        document(HEARING_TWO, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch sent = assembled(MONDAY, mine(store.activeUnbatched()));
                notified.set(sent.batchId());
                walkedToNotified(sent, UUID.randomUUID(), UUID.randomUUID());

                record(seededCommand(HEARING_FOUR, MONDAY_SHARED),
                        document(HEARING_FOUR, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch someTold = assembled(MONDAY, mine(store.activeUnbatched()));
                partlyNotified.set(someTold.batchId());
                walkedToSettled(someTold, UUID.randomUUID(), UUID.randomUUID(),
                        new NotificationSummary(1, 1, BatchStatus.PARTIALLY_NOTIFIED));

                record(seededCommand(HEARING_FOUR, MONDAY_RESHARED),
                        document(HEARING_FOUR, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch nobodyToTell =
                        assembled(MONDAY, mine(store.activeUnbatched()));
                toldNobody.set(nobodyToTell.batchId());
                walkedToSettled(nobodyToTell, UUID.randomUUID(), UUID.randomUUID(),
                        new NotificationSummary(0, 0, BatchStatus.NOTIFIED_NOBODY));

                record(seededCommand(HEARING_THREE, TUESDAY_SHARED),
                        document(HEARING_THREE, TUESDAY, TUESDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch stillWaiting =
                        assembled(TUESDAY, mine(store.activeUnbatched()));
                waiting.set(stillWaiting.batchId());

                ageBatch(generationFailed.batchId(), LAST_NIGHT);
                ageBatch(sent.batchId(), LAST_NIGHT);
                ageBatch(someTold.batchId(), LAST_NIGHT);
                ageBatch(nobodyToTell.batchId(), LAST_NIGHT);
                ageBatch(stillWaiting.batchId(), LAST_NIGHT);
                released.set(store.failAndReleaseStale(cutoff(STALE_AFTER), cutoff(STALE_AFTER)));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(outcomeOf(failed.get()))
                    .as("PENDING and GENERATING is the whole of the predicate, and a batch already "
                            + "FAILED is not in it at any age: re-failing it would overwrite the "
                            + "reason support is reading and hand back registers a person may have "
                            + "asked for on the resend surface")
                    .contains(new BatchOutcome(FAILED, "GENERATION_FAILED", SDG_REASON));
            softly.assertThat(stampedWith(failed.get()))
                    .as("so its register keeps the stamp that reason leaves it, untouched by this "
                            + "pass")
                    .isEqualTo(1);
            softly.assertThat(outcomeOf(notified.get()))
                    .as("and a batch whose Youth Offending Teams have been told is no more in it "
                            + "than a GENERATED one: there is nothing left to give back, and a "
                            + "failure written over it would say a night that worked did not")
                    .contains(new BatchOutcome(NOTIFIED, null, null));
            softly.assertThat(outcomeOf(partlyNotified.get()))
                    .as("nor is the ending where some of the day's Youth Offending Teams were told "
                            + "and the rest are resendable: the ones that were told were told, and "
                            + "the rest are a resend of this batch rather than a reason to render "
                            + "the day again under another one")
                    .contains(new BatchOutcome(PARTIALLY_NOTIFIED, null, null));
            softly.assertThat(outcomeOf(toldNobody.get()))
                    .as("and neither is the ending where there was nobody to tell, which is a "
                            + "night that worked as much as any other: the document exists, it is "
                            + "simply that no team subscribed to that court centre day")
                    .contains(new BatchOutcome(NOTIFIED_NOBODY, null, null));
            softly.assertThat(outcomeOf(waiting.get()))
                    .as("while the day of the very same age that was still waiting for its render "
                            + "is the one the pass is about")
                    .contains(new BatchOutcome(FAILED, NOT_COMPLETED, null));
            softly.assertThat(mineReleased(released.get()))
                    .as("which is the only day the run is told it released")
                    .extracting(ReleasedBatch::registerDate)
                    .containsExactly(TUESDAY);
        }

        @Test
        void the_mark_and_the_release_are_one_transaction() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand second = seededCommand(HEARING_TWO, MONDAY_SHARED);
            final AtomicReference<UUID> batchId = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(second, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), UUID.randomUUID());
                ageBatch(monday.batchId(), LAST_NIGHT);
                batchId.set(monday.batchId());
            }).as(WALKED).doesNotThrowAnyException();

            final AtomicReference<Throwable> refusal = new AtomicReference<>();
            withOneUnbatchedRegisterAllowed(() -> refusal.set(catchThrowable(() ->
                    store.failAndReleaseStale(cutoff(STALE_AFTER), cutoff(STALE_AFTER)))));

            softly.assertThat(refusal.get())
                    .as("the release meets an index it cannot satisfy, and the refusal is not "
                            + "swallowed - as this package's own translation of the store's "
                            + "refusal, rather than as whatever class a later change happens to "
                            + "leave escaping. The class that has to read it is the pass in "
                            + "batch/, which may name no org.springframework.dao type, so an "
                            + "untranslated refusal could only be caught there as RuntimeException "
                            + "- the catch that swallows every programming error beside it")
                    .isInstanceOf(RegisterNotReleasedException.class)
                    .hasMessageNotContaining(ACTIVE_REGISTER_KEY)
                    .as("classified by the kind of rule that refused it and not by the "
                            + "active-register key, which is the difference the pass acts on: a "
                            + "refusal on a rule this operation does not account for is the store "
                            + "saying the write may never be made, and no fresh snapshot changes "
                            + "that, so it is raised rather than made again three times and then "
                            + "reported contended")
                    .hasMessageContaining(UNACCOUNTED_KEY)
                    .as("and it travels alone. The driver's own exception is not attached, "
                            + "because the statement it was raised from writes processed_output - "
                            + "the table the register document itself sits in - and Postgres "
                            + "reports a refusal by quoting the row")
                    .hasNoCause();
            nothingOfTheDriverTravels(refusal.get());

            softly.assertThat(batchOn(MONDAY))
                    .as("and the mark goes down with it. A mark that survived its own release "
                            + "would leave the batch FAILED with its registers still stamped to "
                            + "it - unbatched means batch_id IS NULL, so no later run and no "
                            + "command would ever see them again")
                    .contains(new BatchOutcome(GENERATING, null, null));
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("nothing intermediate is observable, because there is no intermediate "
                            + "state to observe: one statement, and it either happened or it did not")
                    .isEqualTo(2);

            softly.assertThatCode(() ->
                            store.failAndReleaseStale(cutoff(STALE_AFTER), cutoff(STALE_AFTER)))
                    .as("with the index gone the same call is made again")
                    .doesNotThrowAnyException();
            softly.assertThat(batchOn(MONDAY))
                    .as("and this time both halves land together")
                    .contains(new BatchOutcome(FAILED, NOT_COMPLETED, null));
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("which is the release, in the same act as the failure")
                    .isZero();
            softly.assertThat(stampedWith(batchId.get()))
                    .as("and the batch that failed holds no register hostage to a document that "
                            + "nothing will now produce")
                    .isZero();
        }

        @Test
        void a_refusal_that_is_not_a_key_at_all_is_the_domains_own_class_too() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), UUID.randomUUID());
                ageBatch(monday.batchId(), LAST_NIGHT);
            }).as(WALKED).doesNotThrowAnyException();

            final AtomicReference<Throwable> refusal = new AtomicReference<>();
            withThisCourtCentresFailureRefused(() -> refusal.set(catchThrowable(() ->
                    store.failAndReleaseStale(cutoff(STALE_AFTER), cutoff(STALE_AFTER)))));

            softly.assertThat(refusal.get())
                    .as("a unique key is not the only rule a store can refuse a write on: a CHECK "
                            + "constraint the failure reason does not satisfy - which is what a "
                            + "pod running against a store V6 never reached would meet on every "
                            + "batch - is refused as a DataIntegrityViolationException that is no "
                            + "DuplicateKeyException, and it is translated at the same boundary "
                            + "for the same reason. The pass in batch/ may name no "
                            + "org.springframework.dao type (Principle V), so a refusal that "
                            + "crossed the port untranslated could only be read there as "
                            + "RuntimeException")
                    .isInstanceOf(RegisterNotReleasedException.class)
                    .as("classified apart from the key race this operation retries, and apart "
                            + "from a key it does not account for: no fresh snapshot changes a "
                            + "rule, so it is raised rather than attempted three times and then "
                            + "reported contended")
                    .hasMessageContaining(UNACCOUNTED_RULE)
                    .hasMessageNotContaining(UNACCOUNTED_KEY)
                    .as("and the driver's refusal is not attached here either. A CHECK constraint "
                            + "is the one Postgres reports with 'Failing row contains' and the "
                            + "whole row after it, and the rows this statement writes are the "
                            + "rows a youth register is recorded in")
                    .hasNoCause();
            nothingOfTheDriverTravels(refusal.get());

            softly.assertThat(batchOn(MONDAY))
                    .as("and the mark goes down with the release it was refused beside, exactly as "
                            + "it does for a key: one statement, so there is no batch left FAILED "
                            + "with its registers still stamped to it")
                    .contains(new BatchOutcome(GENERATING, null, null));
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("with its register still its own, and still reachable by the run that "
                            + "follows once the rule is put right")
                    .isEqualTo(1);
        }

        @Test
        void the_failure_names_no_completion_mechanism() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), UUID.randomUUID());
                ageBatch(monday.batchId(), LAST_NIGHT);
                store.failAndReleaseStale(cutoff(STALE_AFTER), cutoff(STALE_AFTER));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(completedByOn(MONDAY))
                    .as("nobody outside this service reported anything about this batch: no event "
                            + "arrived and nothing was asked, so naming a completion mechanism "
                            + "would credit a decision nobody made. The reason is not generator-"
                            + "attributed, so the attribution rule is satisfied with null and "
                            + "register_batch_completed_by_shape_chk admits the row")
                    .isEmpty();
            softly.assertThat(batchOn(MONDAY))
                    .as("and systemdocgenerator's own words are absent for the same reason: it "
                            + "said nothing, because after this increment it is never asked")
                    .contains(new BatchOutcome(FAILED, NOT_COMPLETED, null));
        }

        @Test
        void a_release_supersedes_against_a_later_re_share() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand reshare = seededCommand(HEARING_ONE, MONDAY_RESHARED);
            final AtomicReference<StaleReleaseOutcome> released = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), UUID.randomUUID());
                // The hearing is shared again while the first register is stamped into the batch,
                // so the day holds two RECORDED rows for one key rather than one.
                record(reshare, document(HEARING_ONE, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);
                ageBatch(monday.batchId(), LAST_NIGHT);
                released.set(store.failAndReleaseStale(cutoff(STALE_AFTER), cutoff(STALE_AFTER)));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(batchOn(MONDAY))
                    .as("the release is recorded whatever the re-share did: a statement refused by "
                            + "the active-row index would leave the batch in flight under a run "
                            + "that had already given up on it")
                    .contains(new BatchOutcome(FAILED, NOT_COMPLETED, null));
            softly.assertThat(supersessionOf(first).map(SupersessionPair::supersededBy))
                    .as("so supersession decides, exactly as it does for the two reasons that "
                            + "release today: the stale register is superseded as its stamp is "
                            + "cleared, and by the register that replaced it")
                    .contains(outputIdOf(reshare).orElse(null));
            softly.assertThat(statusesOn(MONDAY))
                    .as("a release never makes two registers active for one hearing")
                    .containsExactlyInAnyOrder(SUPERSEDED, RECORDED);
            softly.assertThat(activeUnbatched())
                    .as("and tonight's batch is the re-share alone")
                    .extracting(RegisterRecord::outputId)
                    .containsExactly(outputIdOf(reshare).orElse(null));
            softly.assertThat(mineReleased(released.get()))
                    .as("the count is what came back to be rendered, so the superseded register is "
                            + "not in it: nothing will put it in tonight's batch, and a number the "
                            + "run report reads as registers it re-batched must not include it")
                    .extracting(ReleasedBatch::registerDate, ReleasedBatch::releasedRegisters)
                    .containsExactly(tuple(MONDAY, 0));
        }

        /**
         * The mirror of the case above: the share the batched register overtook.
         *
         * <p>A share delivered out of order - a redelivery that arrived behind the register it
         * belongs in front of - is recorded <strong>active</strong>, because the recorder's
         * incumbent search is over unbatched rows and the register the batch holds is batched, so
         * there was nothing for it to supersede. The key then has an active row that is
         * <em>earlier</em> than the register the release has to give back, and handing that
         * register back beside it is the second active row {@code idx_output_active_register_key}
         * refuses.
         *
         * <p>So the release decides it, the same way and in the same direction the recorder
         * decides it: the later share wins. The register being given back is the later one, so the
         * release supersedes the earlier row against it in the same statement. Latest share wins in
         * both directions, and neither a recorder nor a release ever leaves a key with two active
         * rows.
         *
         * <p>Without this the batch is contended on every run for ever: no fresh snapshot removes
         * the earlier row, and the recorder will not supersede a batched register on its behalf, so
         * the court centre day waits for a release no run can make.
         */
        @Test
        void a_release_supersedes_the_share_it_overtook() {
            final DistributionCommand batched = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand overtaken = seededCommand(HEARING_ONE, MONDAY_OVERTAKEN);
            final AtomicReference<StaleReleaseOutcome> released = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(batched, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), UUID.randomUUID());
                // The hearing's earlier share is delivered after the later one is already in the
                // batch, so the recorder writes it active: a batched register is not its to
                // supersede.
                record(overtaken, document(HEARING_ONE, MONDAY, MONDAY_OVERTAKEN), APPLICANT,
                        RecordedFlagState.ON);
                ageBatch(monday.batchId(), LAST_NIGHT);
                released.set(store.failAndReleaseStale(cutoff(STALE_AFTER), cutoff(STALE_AFTER)));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(batchOn(MONDAY))
                    .as("the batch is released like any other: a key the release cannot decide is "
                            + "a court centre day contended on every run for ever, because no "
                            + "fresh snapshot removes a row that was committed before the "
                            + "statement began")
                    .contains(new BatchOutcome(FAILED, NOT_COMPLETED, null));
            softly.assertThat(mineContended(released.get()))
                    .as("so it is not reported as contended, which is what it was while the "
                            + "release could only supersede in one direction")
                    .isEmpty();
            softly.assertThat(supersessionOf(overtaken).map(SupersessionPair::supersededBy))
                    .as("and the earlier share is superseded against the register being given "
                            + "back, which is the same ordering the recorder applies when it is "
                            + "the one that meets the two: the later share wins")
                    .contains(outputIdOf(batched).orElse(null));
            softly.assertThat(statusesOn(MONDAY))
                    .as("so the key keeps exactly one active row, as it does after every other "
                            + "write in this store")
                    .containsExactlyInAnyOrder(RECORDED, SUPERSEDED);
            softly.assertThat(activeUnbatched())
                    .as("and the register the day is still to render is the one that came back, "
                            + "rather than the share it had already overtaken")
                    .extracting(RegisterRecord::outputId)
                    .containsExactly(outputIdOf(batched).orElse(null));
            softly.assertThat(mineReleased(released.get()))
                    .as("which is the one register the run is told it gave back")
                    .extracting(ReleasedBatch::registerDate, ReleasedBatch::releasedRegisters)
                    .containsExactly(tuple(MONDAY, 1));
        }

        @Test
        void a_batch_that_no_longer_matches_yields_zero_rows_and_no_error() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final AtomicReference<StaleReleaseOutcome> released = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), UUID.randomUUID());
                released.set(store.failAndReleaseStale(cutoff(STALE_AFTER), cutoff(STALE_AFTER)));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(mineReleased(released.get()))
                    .as("a batch that is not stale is a batch the operation did not change, which "
                            + "is a number and not an error (FR-003a). A read-then-mark shape would "
                            + "instead be refused by the state machine and throw out of the run, "
                            + "and one batch that came good in the wrong second would cost every "
                            + "court centre its document that night")
                    .isEmpty();
            softly.assertThat(mineContended(released.get()))
                    .as("and it is not reported as contended either: contention is a race for the "
                            + "day's active-register key that every attempt lost, and a batch "
                            + "nothing was attempted against lost nothing")
                    .isEmpty();
            softly.assertThat(batchOn(MONDAY))
                    .as("nothing about it is written, so nothing about it has to be undone")
                    .contains(new BatchOutcome(GENERATING, null, null));
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("and its register is still in the batch the renderer was asked about")
                    .isEqualTo(1);
        }

        @Test
        void the_operation_returns_what_it_changed_with_its_register_counts() {
            final DistributionCommand mondayFirst = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand mondaySecond = seededCommand(HEARING_TWO, MONDAY_SHARED);
            final DistributionCommand tuesdayFirst = seededCommand(HEARING_THREE, TUESDAY_SHARED);
            final AtomicReference<StaleReleaseOutcome> released = new AtomicReference<>();
            final AtomicReference<UUID> monday = new AtomicReference<>();
            final AtomicReference<UUID> tuesday = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(mondayFirst, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(mondaySecond, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(tuesdayFirst, document(HEARING_THREE, TUESDAY, TUESDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final List<RegisterRecord> active = mine(store.activeUnbatched());
                final RegisterBatch two = assembled(MONDAY, recordsOn(active, MONDAY));
                final RegisterBatch one = assembled(TUESDAY, recordsOn(active, TUESDAY));
                store.markRequested(two.batchId(), UUID.randomUUID());
                ageBatch(two.batchId(), LAST_NIGHT);
                ageBatch(one.batchId(), LAST_NIGHT);
                monday.set(two.batchId());
                tuesday.set(one.batchId());
                released.set(store.failAndReleaseStale(cutoff(STALE_AFTER), cutoff(STALE_AFTER)));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(mineReleased(released.get()))
                    .as("each batch the operation changed, with the key the pass writes its line "
                            + "about and the count the run report carries beside its own accounts. "
                            + "A batch is one document and one e-mail; a register is one hearing's "
                            + "youth defendants, and neither number answers the other's question")
                    .extracting(ReleasedBatch::batchId, ReleasedBatch::courtCentreId,
                            ReleasedBatch::registerDate, ReleasedBatch::releasedRegisters)
                    .containsExactly(
                            tuple(monday.get(), courtCentre, MONDAY, 2),
                            tuple(tuesday.get(), courtCentre, TUESDAY, 1));
            softly.assertThat(mineContended(released.get()))
                    .as("and neither of them is reported as contended, because every batch the "
                            + "operation looked at is accounted for in exactly one of the two "
                            + "lists: a released day is not also a day the pass could not release")
                    .isEmpty();
        }
    }

    /**
     * The ending where everybody who could be told has been, and what it does to the rows.
     *
     * <p>Three of the seven states settle a notification run, and the rows reach NOTIFIED under all
     * three: everybody was told, somebody was not, or there was nobody to tell. The third is defect
     * fix P1 - the progression leg leaves a batch nobody subscribes to sitting generated for ever,
     * waiting for an event nobody publishes - and it is a state no count of failures can produce,
     * which is why the verdict travels with the tally rather than being derived from it.
     *
     * <p>The flip is scoped to the batch for the same reason {@code markGenerated}'s is, and the
     * scoping is asserted the same way: two days at one court centre, one of them notified, and the
     * other day's rows still saying they are waiting.
     */
    @Nested
    @DisplayName("settling a batch's notifications")
    class Notification {

        @Test
        void notification_flips_only_the_batchs_own_rows() {
            final DistributionCommand mondayFirst = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand mondaySecond = seededCommand(HEARING_TWO, MONDAY_SHARED);
            final DistributionCommand tuesdayFirst = seededCommand(HEARING_THREE, TUESDAY_SHARED);
            final DistributionCommand tuesdaySecond = seededCommand(HEARING_FOUR, TUESDAY_SHARED);

            softly.assertThatCode(() -> {
                record(mondayFirst, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(mondaySecond, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(tuesdayFirst, document(HEARING_THREE, TUESDAY, TUESDAY_SHARED),
                        APPLICANT, RecordedFlagState.ON);
                record(tuesdaySecond, document(HEARING_FOUR, TUESDAY, TUESDAY_SHARED),
                        APPLICANT, RecordedFlagState.ON);
                final List<RegisterRecord> waiting = mine(store.activeUnbatched());
                final RegisterBatch monday = assembled(MONDAY, recordsOn(waiting, MONDAY));
                final RegisterBatch tuesday = assembled(TUESDAY, recordsOn(waiting, TUESDAY));
                generate(monday, PAYLOAD_FILE_ID, DOCUMENT_FILE_ID);
                generate(tuesday, SECOND_PAYLOAD_FILE_ID, SECOND_DOCUMENT_FILE_ID);
                store.markNotified(monday.batchId(), new NotificationSummary(1, 0,
                        BatchStatus.NOTIFIED));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(statusesOn(MONDAY))
                    .as("the batch whose recipients were told is the batch whose rows move")
                    .containsExactly(NOTIFIED, NOTIFIED);
            softly.assertThat(statusesOn(TUESDAY))
                    .as("Tuesday's register has been generated and nobody has been told about it "
                            + "yet; a widened flip would say it had been sent")
                    .containsExactly(GENERATED, GENERATED);
            softly.assertThat(batchOn(TUESDAY))
                    .as("and Tuesday's batch is still waiting for its own notification run")
                    .contains(new BatchOutcome(GENERATED, null, null));
        }

        @ParameterizedTest
        @EnumSource(value = BatchStatus.class,
                names = {"NOTIFIED", "PARTIALLY_NOTIFIED", "NOTIFIED_NOBODY"})
        void every_notification_outcome_should_settle_the_batch_and_move_its_rows(
                final BatchStatus outcome) {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand second = seededCommand(HEARING_TWO, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(second, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = assembled(MONDAY, mine(store.activeUnbatched()));
                generate(monday, PAYLOAD_FILE_ID, DOCUMENT_FILE_ID);
                store.markNotified(monday.batchId(), tallyFor(outcome));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(batchOn(MONDAY))
                    .as("the verdict the tally carried is the state the batch ends in, and all "
                            + "three of them are endings rather than somewhere in the middle")
                    .contains(new BatchOutcome(outcome.name(), null, null));
            softly.assertThat(statusesOn(MONDAY))
                    .as("the rows follow the batch under every ending, P1's included: a batch with "
                            + "no recipients is finished, not generated for ever")
                    .containsExactly(NOTIFIED, NOTIFIED);
        }

        @Test
        void a_tally_that_settles_no_notification_should_be_refused_before_the_write() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand second = seededCommand(HEARING_TWO, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(second, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                generate(assembled(MONDAY, mine(store.activeUnbatched())), PAYLOAD_FILE_ID, DOCUMENT_FILE_ID);
            }).as(WALKED).doesNotThrowAnyException();
            final UUID batchId = batchIdOn(MONDAY);

            softly.assertThatThrownBy(() -> store.markNotified(batchId,
                            new NotificationSummary(0, 0, BatchStatus.FAILED)))
                    .as("a notification run ends in one of three states; a summary carrying any "
                            + "other verdict is a caller that lost the tally it meant to write")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("NOTIFIED_NOBODY");
            softly.assertThat(batchOn(MONDAY))
                    .as("and the refusal is made before the statement, so the batch is exactly "
                            + "where the document left it")
                    .contains(new BatchOutcome(GENERATED, null, null));
            softly.assertThat(statusesOn(MONDAY)).containsExactly(GENERATED, GENERATED);
        }

        /** The path from a stamped batch to a generated one, which every ending starts from. */
        private void generate(final RegisterBatch batch, final UUID payloadFileId,
                final UUID documentFileId) {
            store.markRequested(batch.batchId(), payloadFileId);
            store.markGenerated(batch.batchId(), documentFileId, GENERATED_AT, CompletedBy.EVENT);
        }

        /**
         * The counts each ending is reached with, so the tally and its verdict agree.
         *
         * <p>Nought and nought is NOTIFIED_NOBODY and nothing else: a batch that had recipients and
         * failed every one of them reports the failures, which is what makes the two tellable apart.
         */
        private NotificationSummary tallyFor(final BatchStatus outcome) {
            return switch (outcome) {
                case NOTIFIED -> new NotificationSummary(2, 0, outcome);
                case PARTIALLY_NOTIFIED -> new NotificationSummary(1, 1, outcome);
                case NOTIFIED_NOBODY -> new NotificationSummary(0, 0, outcome);
                default -> throw new IllegalArgumentException(
                        "no notification run ends in " + outcome);
            };
        }
    }

    /**
     * The rollback's write, which is the flag's other half.
     *
     * <p>The flag stops this service claiming new registers; this stops the ones it has already
     * claimed from being batched after the legacy has taken the period back over. The bound is read
     * against the register's own shared instant - the {@code registerTime} the document was recorded
     * under - because the period the legacy has resumed is a period of hearings rather than a period
     * of this pod's writes.
     *
     * <p><strong>What it leaves alone is the whole of its risk.</strong> A stamped row is the
     * renderer's, so what becomes of it is its batch's ending to decide rather than a period's; a
     * superseded row is already accounted for and must not be restamped over the pair that says
     * which register replaced which; and a row shared at or after the bound is outside the period a
     * person named. The flag state is deliberately <em>not</em> one of the exclusions: a rollback
     * supersedes the period, and a row recorded while the flag was off is in that period too.
     *
     * <p>The stamped row it leaves is also the one thing this write does not settle: a batch failed
     * or released afterwards unstamps its rows back into {@code activeUnbatched()}, period or no
     * period, so a rollback over a day whose batches are still open is made again after any release
     * of them.
     */
    @Nested
    @DisplayName("superseding the registers shared before an instant")
    class Rollback {

        @Test
        void the_registers_shared_before_the_bound_should_be_superseded_whatever_the_flag_said() {
            final DistributionCommand on = seededCommand(HEARING_ONE, ROLLBACK_SHARED);
            final DistributionCommand off = seededCommand(HEARING_TWO, ROLLBACK_SHARED);
            final DistributionCommand atTheBound = seededCommand(HEARING_THREE, ROLLBACK_BOUND);
            final DistributionCommand after = seededCommand(HEARING_FOUR, ROLLBACK_AFTER);
            final AtomicInteger superseded = new AtomicInteger(-1);

            softly.assertThatCode(() -> {
                record(on, document(HEARING_ONE, ROLLBACK_DAY, ROLLBACK_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(off, document(HEARING_TWO, ROLLBACK_DAY, ROLLBACK_SHARED), APPLICANT,
                        RecordedFlagState.OFF);
                record(atTheBound, document(HEARING_THREE, ROLLBACK_DAY, ROLLBACK_BOUND), APPLICANT,
                        RecordedFlagState.ON);
                record(after, document(HEARING_FOUR, ROLLBACK_DAY, ROLLBACK_AFTER), APPLICANT,
                        RecordedFlagState.ON);
                superseded.set(store.supersedeSharedBefore(ROLLBACK_BOUND));
            }).as(SEAM).doesNotThrowAnyException();

            softly.assertThat(superseded.get())
                    .as("the count is what the command prints, and a rollback that could not say "
                            + "how many registers it took back is a rollback nobody can check")
                    .isEqualTo(2);
            softly.assertThat(statusOf(on))
                    .as("supersession rather than deletion: the row stays, carrying what was "
                            + "recorded and when, because a rollback is exactly when that audit is "
                            + "read")
                    .contains(SUPERSEDED);
            softly.assertThat(statusOf(off))
                    .as("and whether the flag was on when a row arrived is not part of the "
                            + "predicate - a row recorded while it was off is in the period too")
                    .contains(SUPERSEDED);
            softly.assertThat(statusOf(atTheBound))
                    .as("the bound is exclusive, so the register shared at it is outside the period "
                            + "the caller named")
                    .contains(RECORDED);
            softly.assertThat(statusOf(after))
                    .as("and so is every register shared after it")
                    .contains(RECORDED);
            softly.assertThat(activeUnbatched())
                    .as("no run will batch the superseded pair again, which is the whole of what "
                            + "the write is for")
                    .extracting(RegisterRecord::hearingId)
                    .containsExactlyInAnyOrder(HEARING_THREE, HEARING_FOUR);
        }

        @Test
        void a_stamped_register_should_be_left_alone() {
            final DistributionCommand stamped = seededCommand(HEARING_ONE, ROLLBACK_SHARED);
            final AtomicInteger superseded = new AtomicInteger(-1);

            softly.assertThatCode(() -> {
                record(stamped, document(HEARING_ONE, ROLLBACK_DAY, ROLLBACK_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                assembled(ROLLBACK_DAY, mine(store.activeUnbatched()));
                superseded.set(store.supersedeSharedBefore(ROLLBACK_BOUND));
            }).as(SEAM).doesNotThrowAnyException();

            softly.assertThat(superseded.get())
                    .as("this register is the renderer's: systemdocgenerator has been asked about "
                            + "its batch, so what becomes of it is that batch's ending to decide "
                            + "and not a period's")
                    .isZero();
            softly.assertThat(statusOf(stamped))
                    .as("so the row says exactly what it said before the command")
                    .contains(RECORDED);
            softly.assertThat(stampedRowsOn(ROLLBACK_DAY))
                    .as("and it is still the batch's, which is what a GENERATED or NOTIFIED row is "
                            + "protected by too: both of them carry a stamp")
                    .isEqualTo(1);
        }

        @Test
        void a_register_already_superseded_should_not_be_superseded_twice() {
            final DistributionCommand first = seededCommand(HEARING_ONE, ROLLBACK_SHARED);
            final DistributionCommand reshared = seededCommand(HEARING_ONE, ROLLBACK_AFTER);
            final AtomicInteger superseded = new AtomicInteger(-1);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, ROLLBACK_DAY, ROLLBACK_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(reshared, document(HEARING_ONE, ROLLBACK_DAY, ROLLBACK_AFTER), APPLICANT,
                        RecordedFlagState.ON);
            }).as(WALKED).doesNotThrowAnyException();
            final Optional<SupersessionPair> replaced = supersessionOf(first);

            softly.assertThatCode(() -> superseded.set(store.supersedeSharedBefore(ROLLBACK_BOUND)))
                    .as(SEAM)
                    .doesNotThrowAnyException();

            softly.assertThat(superseded.get())
                    .as("the row shared before the bound was already replaced by the results shared "
                            + "after it, and the replacement is outside the period")
                    .isZero();
            softly.assertThat(supersessionOf(first))
                    .as("the pair the recording wrote is what support reads to see which register "
                            + "replaced which, and a rollback that restamped it would leave the "
                            + "earlier register pointing at a moment nothing happened at")
                    .isEqualTo(replaced);
            softly.assertThat(statusOf(reshared))
                    .as("and the active register is left where the bound leaves it")
                    .contains(RECORDED);
        }
    }

    /**
     * A command with its {@code processed_request} parent already written, holding the run claim.
     *
     * <p>{@code processed_output} carries a foreign key to the request, which is the schema saying
     * what the design rules say: an output row is evidence about a request, and evidence with
     * nothing to be about is not evidence.
     */
    private DistributionCommand seededCommand(final UUID hearingId, final Instant sharedTime) {
        final DistributionCommand command = new DistributionCommand(
                ProcessedLogTestSupport.SOURCE,
                UUID.randomUUID(),
                hearingId,
                LocalDate.ofInstant(sharedTime, LONDON),
                sharedTime,
                "Hearing_Resulted");
        ProcessedLogTestSupport.repository(LEASE).insertNew(
                command,
                RequestFingerprint.of(command),
                new RunClaim(command.source(), command.requestId(), "runner-1", UUID.randomUUID(),
                        "msg-1"));
        return command;
    }

    /**
     * Increment 001's own row for a POST to progression, on a key this case's registers hold.
     *
     * <p>Written through {@link ProcessedOutputRepository} rather than as an insert of this suite's
     * invention, so the row is exactly the shape the previous release writes - and that shape is the
     * point: it fills the three columns a successor search matches a key on, the hearing the request
     * names and the court centre and register day the claim names, and it carries a register
     * instant, the instant of the claim being the nearest thing a POST has to one. What it is not is
     * a register. During a rolling deployment a pod on the previous release goes on writing these
     * against a schema that has already moved, so a key can hold one beside a register this pod
     * recorded.
     *
     * <p>The register instant the 001 statement writes is {@code now()}, which is after every
     * register instant this suite records; the cases that use this assert that rather than assume
     * it, because a row that was not the later one of the key could not be a successor at all.
     *
     * <p>The request is seeded here rather than through {@link #seededCommand}, because the POST's
     * write is fenced on the run claim and that helper does not hand its claim back.
     *
     * @param hearingId    the hearing whose register was POSTed
     * @param registerDate the register day the POST names, which is the day the register falls on
     * @return the command the POST row is evidence about, so a case can read the row back
     */
    private DistributionCommand postedToProgression(
            final UUID hearingId, final LocalDate registerDate) {
        final DistributionCommand posting = new DistributionCommand(
                ProcessedLogTestSupport.SOURCE,
                UUID.randomUUID(),
                hearingId,
                registerDate,
                registerDate.atStartOfDay(LONDON).toInstant(),
                "Hearing_Resulted");
        final RunClaim claim = new RunClaim(posting.source(), posting.requestId(), "runner-001",
                UUID.randomUUID(), "msg-001");
        ProcessedLogTestSupport.repository(LEASE).insertNew(
                posting, RequestFingerprint.of(posting), claim);
        new ProcessedOutputRepository(ProcessedLogTestSupport.jdbcClient()).claimPending(claim,
                new ProcessedOutputClaim(UUID.randomUUID(), courtCentre, OU_CODE, registerDate,
                        fileName(hearingId, registerDate), POST_DIGEST, Map.of()));
        return posting;
    }

    /**
     * The recording every case makes, under this suite's court centre OU code.
     *
     * <p>The code is a fact about the court centre rather than about any one case, so it is named
     * once here instead of at each of the thirty call sites. The case that is <em>about</em> the OU
     * code calls the port directly, so the port's own shape is still asserted somewhere.
     *
     * <p>The completion these cases hand over is {@link #COMPLETED}: what the store does with the
     * answer is this suite's subject, and what a real completion writes is the guard's and is
     * asserted where the two meet, in {@code CrashWindowIT}. The two cases that are about the
     * completion pass their own.
     */
    private RecordOutcome record(final DistributionCommand command,
            final CourtRegisterDocument document, final String defendantType,
            final RecordedFlagState flagState) {
        return store.recordAndComplete(command, document, OU_CODE, defendantType, flagState,
                COMPLETED).recording();
    }

    /**
     * Runs the body with a unique constraint the recorder has never heard of in place.
     *
     * <p>Over {@code court_house} and partial on this case's court centre, so it refuses a second
     * register at the same court house and is invisible to every other suite sharing the container.
     * Dropped whatever the body does: an index left behind would refuse the next case to record two
     * hearings here, and the failure would name a constraint that is not in any migration.
     *
     * @param refused what is expected to meet the constraint
     */
    private void withACourtHouseUniqueIndex(final Runnable refused) {
        final String index = "test_only_court_house_" + courtCentre.toString().replace("-", "");
        ProcessedLogTestSupport.jdbcClient()
                .sql("CREATE UNIQUE INDEX " + index + " ON processed_output (court_house) "
                        + "WHERE court_centre_id = '" + courtCentre + "'")
                .update();
        try {
            refused.run();
        } finally {
            ProcessedLogTestSupport.jdbcClient().sql("DROP INDEX " + index).update();
        }
    }

    /**
     * Writes the identities of two of a key's registers, so an equal-time pair orders one way.
     *
     * <p>The identity is the recorder's to mint, so a case about two registers shared at the same
     * instant would otherwise be asserting whichever way {@code randomUUID} fell - and the two
     * orders are the two answers a successor search can give. The pair is minted here, sorted, and
     * written onto the rows the two commands recorded, so each case gets the order it is about on
     * every run rather than on about half of them.
     *
     * <p><strong>Sorted the way Postgres sorts a {@code uuid}</strong>, which is by the sixteen
     * bytes unsigned and therefore by the printed form. {@link UUID#compareTo(UUID)} compares the
     * two halves as <em>signed</em> longs, so a value with the high bit set sorts first under it and
     * last in the database; a pair ordered that way would arrange the opposite of what the case
     * asked for whenever the bit fell that way.
     *
     * <p>Only the identity moves, and only while nothing points at it: {@code superseded_by} is the
     * one column in the schema that references an {@code output_id}, and it is null on both rows in
     * every arrangement this is called from - the older row is stamped into a batch, which is what
     * kept the recorder from superseding it.
     *
     * @param lower  the command whose register is to carry the lower of the two identities
     * @param higher the command whose register is to carry the higher one
     */
    private static void identifiedInOrder(
            final DistributionCommand lower, final DistributionCommand higher) {
        final List<UUID> pair = Stream.of(UUID.randomUUID(), UUID.randomUUID())
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        reidentified(lower, pair.getFirst());
        reidentified(higher, pair.getLast());
    }

    /** One register's identity, written where the case needs the tie-break to be its own. */
    private static void reidentified(final DistributionCommand command, final UUID outputId) {
        ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        UPDATE processed_output
                           SET output_id = :outputId
                         WHERE source = :source AND request_id = :requestId
                        """)
                .param("outputId", outputId)
                .param("source", command.source())
                .param("requestId", command.requestId())
                .update();
    }

    /**
     * Two re-shares of one hearing recorded at once, with the collision made to happen.
     *
     * <p>Both recorders are submitted and then held at a start line the suite only opens once it
     * has the incumbent register under lock on its own connection. Each statement then reads that
     * incumbent on its own snapshot and parks on the supersession it is about to make, so neither
     * can commit before the other has read - which is the whole of the race, and the one thing a
     * sequence of calls cannot produce.
     *
     * @param incumbent the register both re-shares replace, held while they gather behind it
     * @param first     one re-share
     * @param second    the other, whose register instant is the later of the two
     * @return whatever escaped either recorder, which is expected to be nothing
     */
    private List<Throwable> raceToRecord(final DistributionCommand incumbent,
            final DistributionCommand first, final DistributionCommand second) {
        final CountDownLatch startLine = new CountDownLatch(1);
        try (ExecutorService racers = Executors.newFixedThreadPool(RACERS)) {
            final List<Future<RecordOutcome>> attempts = List.of(
                    racers.submit(reshare(startLine, first)),
                    racers.submit(reshare(startLine, second)));
            holdTheIncumbent(incumbent, startLine);
            return escapedFrom(attempts);
        }
    }

    /** One re-share, waiting at the start line until the register it replaces is under lock. */
    private Callable<RecordOutcome> reshare(
            final CountDownLatch startLine, final DistributionCommand command) {
        return () -> {
            startLine.await();
            return record(command, document(HEARING_ONE, MONDAY, command.sharedTime()), APPLICANT,
                    RecordedFlagState.ON);
        };
    }

    /**
     * Holds the register both re-shares supersede until both of them are parked behind it.
     *
     * <p>The lock is taken before the start line opens, so no recorder can be past it, and released
     * by this transaction committing once the database says both are waiting. A recorder that had
     * not yet issued its statement when the lock went would read an incumbent one of the others had
     * already committed, and the suite would be asserting the ordinary case under a concurrent
     * name.
     */
    private void holdTheIncumbent(
            final DistributionCommand incumbent, final CountDownLatch startLine) {
        ProcessedLogTestSupport.transactions().executeWithoutResult(held -> {
            ProcessedLogTestSupport.jdbcClient()
                    .sql("""
                            SELECT output_id
                              FROM processed_output
                             WHERE source = :source AND request_id = :requestId
                               FOR UPDATE
                            """)
                    .param("source", incumbent.source())
                    .param("requestId", incumbent.requestId())
                    .query(UUID.class)
                    .single();
            startLine.countDown();
            await().alias("both re-shares parked behind the register they supersede")
                    .atMost(PARKED_DEADLINE)
                    .pollInterval(PARKED_POLL)
                    .until(() -> recordersParkedOnALock() == RACERS);
        });
    }

    /**
     * How many other client backends this database has parked on a lock.
     *
     * <p>Asked of the database rather than inferred from a sleep, and asked on the connection
     * holding the lock, so the connection doing the asking is the one excluded.
     */
    private static long recordersParkedOnALock() {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT count(*)
                          FROM pg_stat_activity
                         WHERE datname = current_database()
                           AND backend_type = 'client backend'
                           AND pid <> pg_backend_pid()
                           AND wait_event_type = 'Lock'
                        """)
                .query(Long.class)
                .single();
    }

    /** What each recorder threw, unwrapped from the executor that carried it back. */
    private static List<Throwable> escapedFrom(final List<Future<RecordOutcome>> attempts) {
        final List<Throwable> escaped = new ArrayList<>();
        for (final Future<RecordOutcome> attempt : attempts) {
            try {
                attempt.get();
            } catch (ExecutionException failed) {
                escaped.add(failed.getCause());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "the race was interrupted before it settled", interrupted);
            }
        }
        return List.copyOf(escaped);
    }

    /** One hearing's register for this case's court centre, as the pipeline would hand it over. */
    private CourtRegisterDocument document(
            final UUID hearingId, final LocalDate registerDate, final Instant registerTime) {
        return new CourtRegisterDocument(
                registerTime.toString(),
                HEARING_DATE.toString(),
                hearingId.toString(),
                courtCentre.toString(),
                fileName(hearingId, registerDate),
                null,
                new CourtRegisterHearingVenue("Lavender Hill LJA", "Lavender Hill Youth Court",
                        null),
                List.of(new CourtRegisterRecipient(
                        "Wandsworth Youth Offending Team", "yot@example.gov.uk", null,
                        "cr_standard")),
                List.of(new CourtRegisterDefendant(
                        "b2b3f5a1-6c9d-4e21-8a7f-3d5c1e9b0426", "SMITH, John", "2008-04-11",
                        null, null, null, "MALE", "Not Applicable", null, null,
                        List.of(), List.of(), List.of(), List.of())));
    }

    private static String fileName(final UUID hearingId, final LocalDate registerDate) {
        return "court-register_" + registerDate + '_' + OU_CODE + '_' + hearingId + ".pdf";
    }

    /**
     * The store's answer, narrowed to this case, with a refusal recorded rather than thrown.
     *
     * <p>The port answers for the whole table because the job does; the several suites sharing one
     * container do not, so the case that asked is the only one that may be asserted on.
     */
    private List<RegisterRecord> activeUnbatched() {
        final List<RegisterRecord> waiting = new ArrayList<>();
        softly.assertThatCode(() -> waiting.addAll(mine(store.activeUnbatched())))
                .as(PENDING)
                .doesNotThrowAnyException();
        return List.copyOf(waiting);
    }

    private List<RegisterRecord> mine(final List<RegisterRecord> records) {
        return records.stream()
                .filter(record -> courtCentre.equals(record.key().courtCentreId()))
                .toList();
    }

    /**
     * The registers automatic batching passed over, narrowed to this case, refusal recorded.
     *
     * <p>The port answers for the whole table because the command does; the suites sharing one
     * container do not.
     */
    private List<RegisterRecord> recordedWhileOff() {
        final List<RegisterRecord> passedOver = new ArrayList<>();
        softly.assertThatCode(() -> passedOver.addAll(mine(store.recordedWhileOff())))
                .as(SEAM)
                .doesNotThrowAnyException();
        return List.copyOf(passedOver);
    }

    /**
     * The batches recorded for one register day, narrowed to this case, refusal recorded.
     *
     * <p>Narrowed here rather than by the statement: a support call is about a national day, so the
     * read is deliberately not keyed on a court centre and the case that asked is the only one whose
     * rows may be asserted on.
     *
     * @param registerDate the London register day being asked about
     * @return this case's batches for that day, in whatever order the read answered
     */
    private List<RegisterBatch> batchesOn(final LocalDate registerDate) {
        final List<RegisterBatch> day = new ArrayList<>();
        softly.assertThatCode(() -> store.batchesOn(registerDate).stream()
                        .filter(batch -> courtCentre.equals(batch.courtCentreId()))
                        .forEach(day::add))
                .as(SEAM)
                .doesNotThrowAnyException();
        return List.copyOf(day);
    }

    /**
     * One recorded register as a record view, described rather than read back.
     *
     * <p>For the one arrangement no read of this store can produce: a register recorded while the
     * flag was off and stamped into a batch. {@link #activeUnbatched()} excludes it by the flag and
     * {@code recordedWhileOff} is the statement under test, so the row a case needs stamped is
     * stated here instead of asked for.
     *
     * @param outputId     the identity the recording answered with
     * @param hearingId    the hearing the register is about
     * @param registerDate the London register day it falls on
     * @param registerTime the instant the results were shared, which orders the batch
     * @return the register as this store's own reads would have described it
     */
    private RegisterRecord recordedView(final UUID outputId, final UUID hearingId,
            final LocalDate registerDate, final Instant registerTime) {
        return new RegisterRecord(outputId, hearingId, HEARING_DATE,
                new CourtCentreDay(courtCentre, registerDate), registerTime,
                fileName(hearingId, registerDate), APPLICANT, RecordedFlagState.OFF,
                document(hearingId, registerDate, registerTime));
    }

    /**
     * A stamped batch walked to GENERATED, which is where a document exists under its correlation.
     *
     * <p>Walked rather than written there, exactly as the notification fixture is: every mark is a
     * compare-and-set and the state machine is what it is set against.
     *
     * @param batch          the batch assembly left at PENDING
     * @param payloadFileId  the payload the render was asked for
     * @param documentFileId the document systemdocgenerator produced
     */
    private void walkedToGenerated(final RegisterBatch batch, final UUID payloadFileId,
            final UUID documentFileId) {
        store.markRequested(batch.batchId(), payloadFileId);
        store.markGenerated(batch.batchId(), documentFileId, GENERATED_AT, CompletedBy.EVENT);
    }

    /**
     * The same batch walked one step further, which is where its registers have been sent.
     *
     * @param batch          the batch assembly left at PENDING
     * @param payloadFileId  the payload the render was asked for
     * @param documentFileId the document systemdocgenerator produced
     */
    private void walkedToNotified(final RegisterBatch batch, final UUID payloadFileId,
            final UUID documentFileId) {
        walkedToSettled(batch, payloadFileId, documentFileId,
                new NotificationSummary(1, 0, BatchStatus.NOTIFIED));
    }

    /**
     * The same walk, settled on a tally the case chooses rather than on the everybody-was-told one.
     *
     * <p>Three states settle a notification run - everybody was told, somebody was not, and there
     * was nobody to tell - and all three are endings a run has finished with. A case that can only
     * reach the first of them can only claim the predicate about the first of them.
     *
     * @param batch          the batch assembly left at PENDING
     * @param payloadFileId  the payload the render was asked for
     * @param documentFileId the document systemdocgenerator produced
     * @param summary        the tally and the terminal state it produces
     */
    private void walkedToSettled(final RegisterBatch batch, final UUID payloadFileId,
            final UUID documentFileId, final NotificationSummary summary) {
        walkedToGenerated(batch, payloadFileId, documentFileId);
        store.markNotified(batch.batchId(), summary);
    }

    private static List<RegisterRecord> recordsOn(
            final List<RegisterRecord> records, final LocalDate registerDate) {
        return records.stream()
                .filter(record -> registerDate.equals(record.key().registerDate()))
                .toList();
    }

    private long rowsAtCourtCentre() {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("SELECT count(*) FROM processed_output WHERE court_centre_id = :courtCentre")
                .param("courtCentre", courtCentre)
                .query(Long.class)
                .single();
    }

    private long generatedRowsAtCourtCentre() {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT count(*)
                          FROM processed_output
                         WHERE court_centre_id = :courtCentre AND status = 'GENERATED'
                        """)
                .param("courtCentre", courtCentre)
                .query(Long.class)
                .single();
    }

    private List<String> statusesOn(final LocalDate registerDate) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT status
                          FROM processed_output
                         WHERE court_centre_id = :courtCentre AND register_date = :registerDate
                         ORDER BY output_id
                        """)
                .param("courtCentre", courtCentre)
                .param("registerDate", registerDate)
                .query(String.class)
                .list();
    }

    private long stampedRowsOn(final LocalDate registerDate) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT count(*)
                          FROM processed_output
                         WHERE court_centre_id = :courtCentre AND register_date = :registerDate
                           AND batch_id IS NOT NULL
                        """)
                .param("courtCentre", courtCentre)
                .param("registerDate", registerDate)
                .query(Long.class)
                .single();
    }

    /**
     * The three columns an ending is judged by, read straight back out of {@code register_batch}.
     *
     * <p>Read rather than taken from the {@link RegisterBatch} the port answered with: the port
     * returns the batch as it stood at assembly, and what the endings are about is what the table
     * says afterwards.
     *
     * @param status        where the batch ended
     * @param failureReason this service's own bounded code, or {@code null}
     * @param sdgReason     systemdocgenerator's own words, or {@code null} where it said nothing
     */
    private record BatchOutcome(String status, String failureReason, String sdgReason) {
    }

    private Optional<BatchOutcome> batchOn(final LocalDate registerDate) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT status, failure_reason, sdg_reason
                          FROM register_batch
                         WHERE court_centre_id = :courtCentre AND register_date = :registerDate
                        """)
                .param("courtCentre", courtCentre)
                .param("registerDate", registerDate)
                .query((rs, rowNumber) -> new BatchOutcome(rs.getString("status"),
                        rs.getString("failure_reason"), rs.getString("sdg_reason")))
                .optional();
    }

    /** When the batch was failed, read back out of the column the report's window reads. */
    private Optional<OffsetDateTime> failedAtOn(final LocalDate registerDate) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT failed_at
                          FROM register_batch
                         WHERE court_centre_id = :courtCentre AND register_date = :registerDate
                        """)
                .param("courtCentre", courtCentre)
                .param("registerDate", registerDate)
                .query(OffsetDateTime.class)
                .optional();
    }

    /** Which mechanism the batch's ending was learned from, read back out of the column. */
    private Optional<String> completedByOn(final LocalDate registerDate) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT completed_by
                          FROM register_batch
                         WHERE court_centre_id = :courtCentre AND register_date = :registerDate
                        """)
                .param("courtCentre", courtCentre)
                .param("registerDate", registerDate)
                .query(String.class)
                .optional();
    }

    /**
     * The four assembly facts a batch carries that nothing else can re-derive, plus who asked.
     *
     * @param batchId         the identity every downstream call correlates on
     * @param fileName        what the day's document is filed under
     * @param supplementOf    the batch this one follows, or {@code null} on a day's first
     * @param supplementIndex nought on a day's first, counting up on each supplement
     * @param systemGenerated whether the nightly schedule asked, rather than an operator
     */
    private record AssemblyFacts(UUID batchId, String fileName, UUID supplementOf,
            int supplementIndex,
            boolean systemGenerated) {
    }

    /** This case's batch at one supplementary index for a day, if it was written at all. */
    private Optional<UUID> supplementIndexOn(final LocalDate registerDate, final int index) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT batch_id
                          FROM register_batch
                         WHERE court_centre_id = :courtCentre AND register_date = :registerDate
                           AND supplement_index = :index
                        """)
                .param("courtCentre", courtCentre)
                .param("registerDate", registerDate)
                .param("index", index)
                .query(UUID.class)
                .optional();
    }

    /** The assembly facts a batch row holds, read straight back out of {@code register_batch}. */
    private Optional<AssemblyFacts> assemblyOf(final Optional<UUID> batchId) {
        return batchId.flatMap(id -> ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT batch_id, file_name, supplement_of, supplement_index,
                               system_generated
                          FROM register_batch
                         WHERE batch_id = :batchId
                        """)
                .param(BATCH_ID, id)
                .query((rs, rowNumber) -> new AssemblyFacts(rs.getObject("batch_id", UUID.class),
                        rs.getString("file_name"), rs.getObject("supplement_of", UUID.class),
                        rs.getInt("supplement_index"), rs.getBoolean("system_generated")))
                .optional());
    }

    /** How many registers carry one batch's identity. */
    private static long stampedWith(final UUID batchId) {
        return batchId == null ? -1 : ProcessedLogTestSupport.jdbcClient()
                .sql("SELECT count(*) FROM processed_output WHERE batch_id = :batchId")
                .param(BATCH_ID, batchId)
                .query(Long.class)
                .single();
    }

    /**
     * The batch a case assembles, decided the way {@code BatchAssembler} decides a day's first one.
     *
     * <p>The batch is the assembler's decision and an argument to the port, so a case that is not
     * about the supplementary rule still has to make it: PENDING, the first register's file name,
     * asked for by the schedule, following nothing at index nought. The case that <em>is</em> about
     * the rule builds its own.
     *
     * @param registerDate the day being batched, at this case's court centre
     * @param records      the registers it groups, the first of which names the file
     * @return the batch the store is asked to write
     */
    private RegisterBatch firstBatchFor(
            final LocalDate registerDate, final List<RegisterRecord> records) {
        return new RegisterBatch(UUID.randomUUID(), courtCentre, null, null, registerDate,
                records.isEmpty() ? null : records.getFirst().fileName(), null, null,
                BatchStatus.PENDING, null, null, true, null, null, null, null, null, null, 0,
                null, 0);
    }

    /**
     * Assembles a day's first batch out of the given registers.
     *
     * @param registerDate the day being batched
     * @param records      the registers that belong to it
     * @return the batch as the row now stands
     */
    private RegisterBatch assembled(
            final LocalDate registerDate, final List<RegisterRecord> records) {
        return store.assemble(firstBatchFor(registerDate, records), records);
    }

    /**
     * The batch an operator typed for rather than the schedule made, otherwise as the assembler
     * decides a day's first one.
     *
     * <p>{@code system_generated} is the only difference, and it is the whole of what the longer
     * manual cutoff is chosen by: a manual generation holds no run lock and has the requesting
     * deadline to work in (FR-017).
     *
     * @param registerDate the day being batched, at this case's court centre
     * @param records      the registers it groups, the first of which names the file
     * @return the batch the store is asked to write
     */
    private RegisterBatch manualBatchFor(
            final LocalDate registerDate, final List<RegisterRecord> records) {
        return new RegisterBatch(UUID.randomUUID(), courtCentre, null, null, registerDate,
                records.isEmpty() ? null : records.getFirst().fileName(), null, null,
                BatchStatus.PENDING, null, null, false, null, null, null, null, null, null, 0,
                null, 0);
    }

    /**
     * A cutoff of a given age, as the pass computes one from its clock and its settings.
     *
     * <p>Always in the past, so a case here can only reach a batch it aged itself: the several
     * suites share one container, and a cutoff in the future would name every batch in it.
     *
     * @param age how long a batch may be in flight before this cutoff calls it stale
     * @return the instant a stamp at or before which is stale
     */
    private static Instant cutoff(final Duration age) {
        return Instant.now().minus(age);
    }

    /**
     * Ages both of a batch's in-flight stamps, as the night between two runs does.
     *
     * @param batchId the batch to age
     * @param age     how far into the past to move its stamps
     */
    private void ageBatch(final UUID batchId, final Duration age) {
        ageBatch(batchId, age, age);
    }

    /**
     * Ages a batch's two in-flight stamps separately, so the {@code COALESCE} can be told apart.
     *
     * <p>By the database's own clock and in its own units, exactly as the stamps were written.
     * {@code requested_at} is left null where it is null - a PENDING batch has none, and that is
     * the case the coalesce is for.
     *
     * @param batchId   the batch to age
     * @param assembled how far into the past to move {@code assembled_at}
     * @param requested how far into the past to move {@code requested_at}
     * @throws IllegalStateException if there was no batch to age, since a fixture that quietly
     *                               does nothing would turn a case green for the wrong reason
     */
    private void ageBatch(final UUID batchId, final Duration assembled, final Duration requested) {
        final int aged = ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        UPDATE register_batch
                           SET assembled_at = assembled_at - make_interval(secs => :assembled),
                               requested_at = requested_at - make_interval(secs => :requested)
                         WHERE batch_id = :batchId
                        """)
                .param("assembled", (double) assembled.toSeconds())
                .param("requested", (double) requested.toSeconds())
                .param(BATCH_ID, batchId)
                .update();
        if (aged != ONE_BATCH) {
            throw new IllegalStateException(
                    "expected one batch to age for " + batchId + ", aged " + aged);
        }
    }

    /**
     * Writes a batch's in-flight stamps to an exact instant, so a cutoff can be put on the boundary.
     *
     * <p>{@link #ageBatch} moves a stamp by a duration, which is what a case about "a night old"
     * wants and is no use at all to a case about "at exactly the cutoff": the two instants would
     * differ by however long the fixture itself took. Here the case names one instant and both the
     * stamp and the cutoff are it. It is truncated to the millisecond by the caller, because the
     * column keeps microseconds and would round a nanosecond-precision instant either way.
     *
     * <p>{@code requested_at} is written only where it is not null, so a PENDING batch stays the
     * {@code COALESCE}'s other case rather than being quietly turned into a GENERATING one's shape.
     *
     * @param batchId the batch to stamp
     * @param stamp   the instant both of its in-flight stamps are written to
     * @throws IllegalStateException if there was no batch to stamp, since a fixture that quietly
     *                               does nothing would turn a case green for the wrong reason
     */
    private void stampBatch(final UUID batchId, final Instant stamp) {
        final int stamped = ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        UPDATE register_batch
                           SET assembled_at = :stamp,
                               requested_at = CASE WHEN requested_at IS NULL THEN NULL
                                                   ELSE :stamp END
                         WHERE batch_id = :batchId
                        """)
                .param("stamp", stamp.atOffset(ZoneOffset.UTC))
                .param(BATCH_ID, batchId)
                .update();
        if (stamped != ONE_BATCH) {
            throw new IllegalStateException(
                    "expected one batch to stamp for " + batchId + ", stamped " + stamped);
        }
    }

    /**
     * A batch's outcome read by its identity, for a case holding more than one batch on a day.
     *
     * <p>{@link #batchOn(LocalDate)} reads by the day, which a case that fails a batch and then
     * assembles another for the same day cannot use - the day holds two rows and the read would
     * refuse them both.
     *
     * @param batchId the batch to read
     * @return its status and the two reason columns, or nothing where there is no such batch
     */
    private static Optional<BatchOutcome> outcomeOf(final UUID batchId) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT status, failure_reason, sdg_reason
                          FROM register_batch
                         WHERE batch_id = :batchId
                        """)
                .param(BATCH_ID, batchId)
                .query((rs, rowNumber) -> new BatchOutcome(rs.getString("status"),
                        rs.getString("failure_reason"), rs.getString("sdg_reason")))
                .optional();
    }

    /**
     * An observer that writes down the batches it is told about, in the order it is told.
     *
     * @param told where each released batch's identity is recorded
     * @return the observer to hand the release
     */
    private static StaleReleaseProgress recording(final List<UUID> told) {
        return new StaleReleaseProgress() {

            @Override
            public void recordReleased(final ReleasedBatch released) {
                told.add(released.batchId());
            }

            @Override
            public void recordContended(final UUID batchId) {
                // No case here stands a batch up that every attempt loses its day's key on;
                // StaleReleaseConcurrencyIT is where that ending is driven.
            }
        };
    }

    /**
     * The released batches of this case's court centre, and none of another suite's.
     *
     * <p>The operation answers for the whole store, as the pass needs it to; a case asserts on its
     * own court centre for the same reason {@link #mine(List)} exists. Null is answered as nothing
     * rather than thrown on, so a red run reports the assertion that was being made and not the
     * seam that had not been implemented yet.
     *
     * @param released what the operation answered with, or {@code null} where it refused
     * @return the records naming this case's court centre, in the order they came back
     */
    private List<ReleasedBatch> mineReleased(final StaleReleaseOutcome release) {
        return release == null ? List.of() : release.released().stream()
                .filter(batch -> courtCentre.equals(batch.courtCentreId()))
                .toList();
    }

    /**
     * The contended batches of this case's court centre, and none of another suite's.
     *
     * <p>Contention is reported by identity alone, so the court centre is read back out of the row
     * rather than carried on the answer - which is the same filter {@link #mineReleased} makes, for
     * the same reason: the operation answers for the whole store and a case may only speak for its
     * own court centre.
     *
     * @param release what the operation answered with, or {@code null} where it refused
     * @return the contended batches of this case's court centre, in the order they came back
     */
    private List<UUID> mineContended(final StaleReleaseOutcome release) {
        return release == null ? List.of() : release.contended().stream()
                .filter(batchId -> courtCentre.equals(courtCentreOf(batchId)))
                .toList();
    }

    /** The court centre a batch was assembled for, read straight out of {@code register_batch}. */
    private UUID courtCentreOf(final UUID batchId) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("SELECT court_centre_id FROM register_batch WHERE batch_id = :batchId")
                .param(BATCH_ID, batchId)
                .query(UUID.class)
                .single();
    }

    /**
     * Runs the body with an index that refuses a second unbatched register at this court centre.
     *
     * <p>Partial and on this case's court centre, so it is invisible to every other suite sharing
     * the container, and created while both registers are stamped so that it is empty until the
     * release tries to clear them. What it buys is the one thing a single-threaded case cannot
     * otherwise see: a release that fails after its mark would leave the batch FAILED with its
     * registers stamped to it, and this is how the mark is watched going down with the release.
     *
     * <p>Dropped whatever the body does: an index left behind would refuse the next case here.
     *
     * @param refused what is expected to meet the index
     */
    private void withOneUnbatchedRegisterAllowed(final Runnable refused) {
        final String index = testOnlyUnbatchedIndex();
        ProcessedLogTestSupport.jdbcClient()
                .sql("CREATE UNIQUE INDEX " + index + " ON processed_output (court_centre_id) "
                        + "WHERE batch_id IS NULL AND court_centre_id = '" + courtCentre + "'")
                .update();
        try {
            refused.run();
        } finally {
            ProcessedLogTestSupport.jdbcClient().sql("DROP INDEX " + index).update();
        }
    }

    /**
     * Runs the body with a rule that is not a key refusing this court centre's batch failures.
     *
     * <p>The other half of {@link #withOneUnbatchedRegisterAllowed}: a unique index is one thing a
     * store can refuse a write on, and a CHECK constraint is another - and Spring reports the two
     * as different classes, only one of which is a {@code DuplicateKeyException}. A pod whose store
     * never reached {@code V6} meets exactly this on every stale batch, because the bounded reason
     * the pass writes is one {@code register_batch_failure_reason_chk} did not admit until then.
     *
     * <p>Narrowed to this case's court centre and added {@code NOT VALID}, so no row any other
     * suite sharing the container holds is looked at, let alone refused; dropped whatever the body
     * does, because a constraint left behind would refuse every failure after it.
     *
     * @param refused what is expected to meet the constraint
     */
    private void withThisCourtCentresFailureRefused(final Runnable refused) {
        final String constraint = "test_only_no_failure_" + courtCentre.toString().replace("-", "");
        ProcessedLogTestSupport.jdbcClient()
                .sql("ALTER TABLE register_batch ADD CONSTRAINT " + constraint
                        + " CHECK (court_centre_id <> '" + courtCentre + "'::uuid "
                        + "OR failure_reason IS NULL) NOT VALID")
                .update();
        try {
            refused.run();
        } finally {
            ProcessedLogTestSupport.jdbcClient()
                    .sql("ALTER TABLE register_batch DROP CONSTRAINT " + constraint)
                    .update();
        }
    }

    /**
     * The name {@link #withOneUnbatchedRegisterAllowed} gives its index, so a case may name it too.
     *
     * <p>A case that asserts <em>which</em> rule refused a write has to know what that rule is
     * called. Derived here rather than written down twice, because the two spellings drifting apart
     * would leave the assertion passing against a refusal it was never about.
     *
     * @return the index name, unique to this case's court centre
     */
    private String testOnlyUnbatchedIndex() {
        return "test_only_unbatched_" + courtCentre.toString().replace("-", "");
    }

    /**
     * Asserts that nothing the driver said travels with a refusal, as a class or as a word.
     *
     * <p><strong>Why the whole chain and not only the exception itself.</strong> A cause is how a
     * driver's detail line reaches a stack trace, and a stack trace is written whole: naming the
     * top-level class would leave a later change free to re-attach the refusal underneath it and
     * this suite green. So the chain is walked, and both things are asked of every link - that it
     * is not an {@code org.springframework.dao} type, which is what Principle V forbids crossing
     * the port, and that nothing it says is anything Postgres quoted back.
     *
     * <p>Postgres reports a refused row two ways and both are checked: {@code Failing row contains
     * (...)} for a CHECK, and {@code Key (...)=(...) already exists} for a unique index. The
     * statement writes {@code processed_output}, whose row holds the register document, so what
     * those lines would quote is a child's name and date of birth - which may not reach a log
     * index at INFO or above, and a refusal raised out of the nightly run is written at ERROR
     * (constitution Principle VII).
     *
     * @param refusal what the release threw
     */
    private void nothingOfTheDriverTravels(final Throwable refusal) {
        softly.assertThat(everyClassIn(refusal))
                .as("no refusal crosses the port as a Spring data-access type, at any depth: the "
                        + "pass in batch/ may name none of them, so one that did could only be "
                        + "caught there as RuntimeException")
                .isNotEmpty()
                .noneMatch(travelling -> travelling.startsWith(SPRING_DAO));
        softly.assertThat(everythingSaidBy(refusal))
                .as("and nothing the driver said travels either - not the detail line it quotes a "
                        + "refused row under, not the court centre, and above all nothing of the "
                        + "child the register is about")
                .doesNotContain(FAILING_ROW, REFUSED_KEY_DETAIL, courtCentre.toString(),
                        "SMITH, John", "2008-04-11");
    }

    /** Every class a refusal travels as: the exception itself, and every cause beneath it. */
    private static List<String> everyClassIn(final Throwable refusal) {
        final List<String> travelling = new ArrayList<>();
        for (Throwable link = refusal; link != null; link = link.getCause()) {
            travelling.add(link.getClass().getName());
        }
        return travelling;
    }

    /** Everything a refusal says: its own message, and every message beneath it, run together. */
    private static String everythingSaidBy(final Throwable refusal) {
        final StringBuilder said = new StringBuilder();
        for (Throwable link = refusal; link != null; link = link.getCause()) {
            said.append(String.valueOf(link.getMessage())).append('\n');
        }
        return said.toString();
    }

    /** The moment a batch's render was asked for, read back out of {@code register_batch}. */
    private Optional<OffsetDateTime> requestedAtOn(final LocalDate registerDate) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT requested_at
                          FROM register_batch
                         WHERE court_centre_id = :courtCentre AND register_date = :registerDate
                        """)
                .param("courtCentre", courtCentre)
                .param("registerDate", registerDate)
                .query(OffsetDateTime.class)
                .optional();
    }

    /** The payload id a batch carries, read back out of {@code register_batch}. */
    private Optional<UUID> payloadFileIdOn(final LocalDate registerDate) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT payload_file_id
                          FROM register_batch
                         WHERE court_centre_id = :courtCentre AND register_date = :registerDate
                        """)
                .param("courtCentre", courtCentre)
                .param("registerDate", registerDate)
                .query(UUID.class)
                .optional();
    }

    /** The OU code the batch was assembled under, read back out of {@code register_batch}. */
    private Optional<String> batchOuCodeOn(final LocalDate registerDate) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT court_centre_ou_code
                          FROM register_batch
                         WHERE court_centre_id = :courtCentre AND register_date = :registerDate
                        """)
                .param("courtCentre", courtCentre)
                .param("registerDate", registerDate)
                .query(String.class)
                .optional();
    }

    /** This case's batch for a day, insisting the arrangement wrote one. */
    private UUID batchIdOn(final LocalDate registerDate) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT batch_id
                          FROM register_batch
                         WHERE court_centre_id = :courtCentre AND register_date = :registerDate
                        """)
                .param("courtCentre", courtCentre)
                .param("registerDate", registerDate)
                .query(UUID.class)
                .single();
    }

    private static Optional<String> statusOf(final DistributionCommand command) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT status
                          FROM processed_output
                         WHERE source = :source AND request_id = :requestId
                        """)
                .param("source", command.source())
                .param("requestId", command.requestId())
                .query(String.class)
                .optional();
    }

    /**
     * The supersession pair as the row holds it, rather than as the port reported it.
     *
     * @param supersededAt when the row stopped being the active register, or {@code null}
     * @param supersededBy the register that replaced it, or {@code null}
     */
    private record SupersessionPair(Instant supersededAt, UUID supersededBy) {
    }

    private static Optional<SupersessionPair> supersessionOf(final DistributionCommand command) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT superseded_at, superseded_by
                          FROM processed_output
                         WHERE source = :source AND request_id = :requestId
                        """)
                .param("source", command.source())
                .param("requestId", command.requestId())
                .query((rs, rowNumber) -> new SupersessionPair(
                        instant(rs.getObject("superseded_at", OffsetDateTime.class)),
                        rs.getObject("superseded_by", UUID.class)))
                .optional();
    }

    /**
     * Every column of the row, by name, so "unchanged" means the whole row and not a chosen part.
     *
     * <p>Read through the result set's own metadata rather than as a list this suite maintains: a
     * column added by a later migration is then compared too, which is exactly the column a
     * later statement would be the first to move without anybody noticing. Values are compared as
     * their printed form, because the point is that nothing about the row differs and not which
     * driver type each column arrives as.
     */
    private static Map<String, String> wholeRowOf(final DistributionCommand command) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT *
                          FROM processed_output
                         WHERE source = :source AND request_id = :requestId
                        """)
                .param("source", command.source())
                .param("requestId", command.requestId())
                .query((rs, rowNumber) -> allColumnsOf(rs))
                .single();
    }

    private static Instant instant(final OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static Map<String, String> allColumnsOf(final ResultSet rs) throws SQLException {
        final ResultSetMetaData columns = rs.getMetaData();
        final Map<String, String> row = new LinkedHashMap<>();
        for (int column = 1; column <= columns.getColumnCount(); column++) {
            row.put(columns.getColumnLabel(column), String.valueOf(rs.getObject(column)));
        }
        return row;
    }

    private static Optional<String> ouCodeOf(final DistributionCommand command) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT court_centre_ou_code
                          FROM processed_output
                         WHERE source = :source AND request_id = :requestId
                        """)
                .param("source", command.source())
                .param("requestId", command.requestId())
                .query(String.class)
                .optional();
    }

    /**
     * The register instant a row carries, read back out of the column.
     *
     * <p>So that a case saying one row of a key is later than another says it of the column the
     * successor search reads, rather than of the fixture it hoped had written one.
     */
    private static Optional<Instant> registerTimeOf(final DistributionCommand command) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT register_time
                          FROM processed_output
                         WHERE source = :source AND request_id = :requestId
                        """)
                .param("source", command.source())
                .param("requestId", command.requestId())
                .query((rs, rowNumber) ->
                        instant(rs.getObject("register_time", OffsetDateTime.class)))
                .optional();
    }

    private static Optional<UUID> outputIdOf(final DistributionCommand command) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT output_id
                          FROM processed_output
                         WHERE source = :source AND request_id = :requestId
                        """)
                .param("source", command.source())
                .param("requestId", command.requestId())
                .query(UUID.class)
                .optional();
    }
}
