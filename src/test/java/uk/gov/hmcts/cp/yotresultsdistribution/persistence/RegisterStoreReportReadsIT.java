package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

import static org.assertj.core.data.Offset.offset;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterStore;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterHearingVenue;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GuardDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedRegisterSummary;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterRecord;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestFingerprint;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ReportReadsDatabase;

/**
 * The fourth BATCH_LATE source: the registers the most recent scheduled generation run left behind.
 *
 * <p>A register recorded and never batched is a court centre's day that has no batch to name at
 * all, so it is the one exception the report reaches through the register store rather than through
 * the batch table. What the read adds to {@code activeUnbatched()} is a cut-off and an age computed
 * in SQL; what it does not add is a second spelling of "active, unsuperseded, unbatched and
 * recorded while the flag was on", because two spellings of that are two answers waiting to
 * disagree - which is what one case here asserts by comparing the two reads against each other.
 *
 * <p>A database of this suite's own, and soft assertions, for the reasons
 * {@link ProcessedRequestReportReadsIT} gives.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the register store's report read")
class RegisterStoreReportReadsIT {

    private static final String DATABASE = "yotresultsdistribution_store_report_reads";

    private static final String OUTPUT_TABLE = "processed_output";
    private static final String REQUEST_TABLE = "processed_request";

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static final String SOURCE = "RESULTS";

    private static final String OU_CODE = "B01LY00";

    private static final Duration LEASE = Duration.ofMinutes(5);

    /** The hearing's own date, which the document carries as a date-time and not as a day. */
    private static final Instant HEARING_DATE = Instant.parse("2026-09-14T10:00:00Z");

    private static final UUID HEARING_ONE =
            UUID.fromString("7c2f4a81-0e53-4b96-8d17-5a9c3e0b246f");
    private static final UUID HEARING_TWO =
            UUID.fromString("2d8b6e04-9f17-4c35-a802-6b41c5d7e093");
    private static final UUID HEARING_THREE =
            UUID.fromString("b51a3c67-48d2-4e09-9375-1f6e08d4a2b5");

    /** The completion an ordinary case hands the store: admitted, and writing nothing of its own. */
    private static final Supplier<GuardDecision> COMPLETED =
            () -> new GuardDecision.Complete(ReasonCode.RUN_COMPLETED);

    private static final String SEAM =
            "the report's recorded-unbatched read implements this statement; this is its red run";

    private static final long SECONDS_OF_SLACK = 5;

    /** Enough registers on one moment that an accidental agreement with insertion order is remote. */
    private static final int TIED_REGISTERS = 6;

    /**
     * How Postgres orders a {@code uuid} column, which is <strong>not</strong> how
     * {@link UUID#compareTo(UUID)} does.
     *
     * <p>Postgres compares the sixteen bytes unsigned, big-endian; Java compares the two halves as
     * signed longs, so the two disagree about every pair whose leading bit differs. A case that
     * asserted the database's order against Java's would pass or fail on which random identities it
     * happened to mint, which is the flake this comparator exists to remove.
     */
    private static final Comparator<UUID> AS_POSTGRES_ORDERS_THEM =
            Comparator.comparing(UUID::getMostSignificantBits, Long::compareUnsigned)
                    .thenComparing(UUID::getLeastSignificantBits, Long::compareUnsigned);

    private static ReportReadsDatabase database;
    private static RegisterStore store;

    /** This case's court centre: minted per test so no case reads another's rows. */
    private final UUID courtCentre = UUID.randomUUID();

    @InjectSoftAssertions
    private SoftAssertions softly;

    @BeforeAll
    static void migrate() {
        database = ReportReadsDatabase.migrated(DATABASE);
        store = new JdbcRegisterStore(database.jdbcClient(), database.transactionManager());
    }

    /** The database goes with the suite, so its name is free for a second load of this class. */
    @AfterAll
    static void dropTheDatabase() {
        database.drop();
    }

    @BeforeEach
    void emptyTheTables() {
        database.empty(OUTPUT_TABLE, REQUEST_TABLE);
    }

    @Test
    void recorded_unbatched_before_returns_registers_recorded_before_the_cut_off() {
        final UUID waiting = record(HEARING_ONE, minutesAgo(90), RecordedFlagState.ON);
        record(HEARING_TWO, minutesAgo(5), RecordedFlagState.ON);

        softly.assertThat(recordedUnbatchedBefore(minutesAgo(30)))
                .as("the most recent scheduled generation run is the cut-off, so a register "
                        + "recorded since it is not late - nothing has been along for it yet")
                .extracting(RecordedRegisterSummary::outputId)
                .containsExactly(waiting);
    }

    @Test
    void recorded_unbatched_before_returns_exactly_the_rows_active_unbatched_returns_that_are_older() {
        record(HEARING_ONE, minutesAgo(90), RecordedFlagState.ON);
        record(HEARING_TWO, minutesAgo(45), RecordedFlagState.ON);
        record(HEARING_THREE, minutesAgo(5), RecordedFlagState.ON);

        final List<UUID> waiting = store.activeUnbatched().stream()
                .filter(record -> record.registerTime().isBefore(minutesAgo(30)))
                .map(RegisterRecord::outputId)
                .toList();

        softly.assertThat(recordedUnbatchedBefore(minutesAgo(30)))
                .as("one predicate, written once: two spellings of active and unbatched are two "
                        + "answers waiting to disagree, and the cut-off is all this read adds")
                .extracting(RecordedRegisterSummary::outputId)
                .containsExactlyElementsOf(waiting);
    }

    @Test
    void a_register_recorded_while_the_flag_was_off_is_in_neither_read() {
        final UUID waiting = record(HEARING_ONE, minutesAgo(90), RecordedFlagState.ON);
        record(HEARING_TWO, minutesAgo(90), RecordedFlagState.OFF);
        record(HEARING_THREE, minutesAgo(90), RecordedFlagState.UNKNOWN);

        softly.assertThat(store.activeUnbatched())
                .as("002's predicate already excludes it: a register recorded while the legacy "
                        + "was generating may already have been sent by the legacy")
                .extracting(RegisterRecord::outputId)
                .containsExactly(waiting);
        softly.assertThat(recordedUnbatchedBefore(minutesAgo(30)))
                .as("and it is not an exception either - those rows are the existing "
                        + "list-batches --recorded-while-off command's concern")
                .extracting(RecordedRegisterSummary::outputId)
                .containsExactly(waiting);
    }

    @Test
    void registers_recorded_at_the_same_moment_are_answered_in_output_id_order() {
        final Instant sameMoment = minutesAgo(90);
        final List<UUID> recorded = new ArrayList<>();
        for (int register = 0; register < TIED_REGISTERS; register++) {
            recorded.add(record(UUID.randomUUID(), sameMoment, RecordedFlagState.ON));
        }

        softly.assertThat(recordedUnbatchedBefore(minutesAgo(30)))
                .as("a court centre's registers are recorded inside the same microsecond often "
                        + "enough that register_time alone is no order at all: without a "
                        + "tie-break the rows arrive in whatever order the scan found them, so "
                        + "two runs of one report list the same morning two ways and a support "
                        + "engineer comparing them reads a change that never happened")
                .extracting(RecordedRegisterSummary::outputId)
                .containsExactlyElementsOf(
                        recorded.stream().sorted(AS_POSTGRES_ORDERS_THEM).toList());
        softly.assertThat(recordedUnbatchedBefore(minutesAgo(30)))
                .as("and it is the order 002's read has always answered in, which is what keeps "
                        + "the two readings of one predicate from disagreeing about a morning")
                .extracting(RecordedRegisterSummary::outputId)
                .containsExactlyElementsOf(
                        store.activeUnbatched().stream().map(RegisterRecord::outputId).toList());
    }

    @Test
    void age_seconds_is_answered_in_seconds_from_the_stage_timestamp() {
        record(HEARING_ONE, minutesAgo(90), RecordedFlagState.ON);
        database.forgetStatements();

        final List<RecordedRegisterSummary> answered = recordedUnbatchedBefore(minutesAgo(30));

        softly.assertThat(ageOf(answered))
                .as("the real age, in seconds, measured from the moment the register was recorded")
                .isCloseTo(Duration.ofMinutes(90).toSeconds(), offset(SECONDS_OF_SLACK));
        softly.assertThat(String.join("\n", database.statements()))
                .as("and taken in the database, in the same statement that selects the row: this "
                        + "store holds no clock for a read, so there is no JVM reading here for "
                        + "register_time to be subtracted from (V1's single time authority)")
                .contains("now()")
                .contains("extract(epoch");
    }

    @Test
    void active_unbatched_is_unchanged() {
        final UUID first = record(HEARING_ONE, minutesAgo(90), RecordedFlagState.ON);
        final UUID second = record(HEARING_TWO, minutesAgo(5), RecordedFlagState.ON);

        softly.assertThat(store.activeUnbatched())
                .as("002's read keeps the generation job as its caller and keeps answering "
                        + "entities, oldest first, with no cut-off of its own")
                .extracting(RegisterRecord::outputId)
                .containsExactly(first, second);
        softly.assertThat(store.activeUnbatched())
                .as("and it answers whole records, which is what the assembler batches")
                .allSatisfy(record -> softly.assertThat(record.document()).isNotNull());
    }

    // --- the read, made so that a seam's refusal is recorded rather than thrown -----------------

    private List<RecordedRegisterSummary> recordedUnbatchedBefore(final Instant recordedBefore) {
        final AtomicReference<List<RecordedRegisterSummary>> answered =
                new AtomicReference<>(List.of());
        softly.assertThatCode(() -> answered.set(store.recordedUnbatchedBefore(recordedBefore)))
                .as(SEAM)
                .doesNotThrowAnyException();
        return answered.get();
    }

    // --- seeding ------------------------------------------------------------------------------

    /**
     * One register recorded through the port, under a command this case seeds the claim for.
     *
     * <p>Through {@code recordAndComplete} rather than as an insert of this suite's invention, so
     * the row is exactly the shape the intake half writes - including {@code register_time}, which
     * comes off the document and is what this read measures its age from.
     */
    private UUID record(final UUID hearingId, final Instant registerTime,
            final RecordedFlagState flagState) {
        final DistributionCommand command = seededCommand(hearingId, registerTime);
        return store.recordAndComplete(command, document(hearingId, registerTime), OU_CODE,
                "Applicant", flagState, COMPLETED).recording().outputId();
    }

    private DistributionCommand seededCommand(final UUID hearingId, final Instant sharedTime) {
        final DistributionCommand command = new DistributionCommand(SOURCE, UUID.randomUUID(),
                hearingId, LocalDate.ofInstant(sharedTime, LONDON), sharedTime, "Hearing_Resulted");
        new ProcessedRequestRepository(database.jdbcClient(), LEASE).insertNew(command,
                RequestFingerprint.of(command),
                new RunClaim(command.source(), command.requestId(), "runner-1", UUID.randomUUID(),
                        "msg-1"));
        return command;
    }

    private CourtRegisterDocument document(final UUID hearingId, final Instant registerTime) {
        return new CourtRegisterDocument(
                registerTime.toString(),
                HEARING_DATE.toString(),
                hearingId.toString(),
                courtCentre.toString(),
                "court-register_" + LocalDate.ofInstant(registerTime, LONDON) + '_' + OU_CODE
                        + '_' + hearingId + ".pdf",
                null,
                new CourtRegisterHearingVenue("Lavender Hill LJA", "Lavender Hill Youth Court",
                        null),
                List.of(new CourtRegisterRecipient("Wandsworth Youth Offending Team",
                        "yot@example.gov.uk", null, "cr_standard")),
                List.of(new CourtRegisterDefendant(
                        "b2b3f5a1-6c9d-4e21-8a7f-3d5c1e9b0426", "SMITH, John", "2008-04-11",
                        null, null, null, "MALE", "Not Applicable", null, null,
                        List.of(), List.of(), List.of(), List.of())));
    }

    // --- reading the answers ------------------------------------------------------------------

    private long ageOf(final List<RecordedRegisterSummary> answered) {
        return answered.isEmpty() ? -1 : answered.get(0).ageSeconds();
    }

    private static Instant minutesAgo(final long minutes) {
        return Instant.now().minus(Duration.ofMinutes(minutes)).truncatedTo(ChronoUnit.MICROS);
    }
}
