package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.data.Offset.offset;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletedBy;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ReportReadsDatabase;

/**
 * The four reads the exception report makes of the batch table.
 *
 * <p>Three of them are BATCH_LATE - a batch nothing has been asked of the renderer for, one whose
 * answer has not come back, one holding a document nobody was told about - and the fourth is
 * BATCH_FAILED, the downstream half's equivalent of a parked request. All four answer a projection
 * carrying an age, and each measures it from its own stage timestamp, because "waiting" means a
 * different moment in each of the four.
 *
 * <p><strong>They are new reads rather than 002's three</strong>, and one case here says why by
 * asserting the old ones still answer entities and still answer what their own suites expect. A
 * report that used {@code pendingSince}, {@code generatingSince} and {@code generatedSince} would
 * have to subtract a stored timestamp from a JVM reading, and widening them to produce an age would
 * change a leg this increment does not otherwise touch.
 *
 * <p>A database of this suite's own, and soft assertions, for the reasons
 * {@link ProcessedRequestReportReadsIT} gives.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the batch table's report reads")
class RegisterBatchReportReadsIT {

    private static final String DATABASE = "yotresultsdistribution_batch_report_reads";

    private static final String BATCH_TABLE = "register_batch";

    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 14);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 9, 15);

    private static final String OU_CODE = "B01LY00";
    private static final String COURT_HOUSE = "Lavender Hill Youth Court";

    private static final UUID DOCUMENT_FILE_ID =
            UUID.fromString("3a7f1c92-6d84-4b05-9e73-1c2b8a4e07d5");

    /** systemdocgenerator's own words about somebody's document, and the column with no reader. */
    private static final String SDG_REASON = "template OEE_Layout5 rendered no pages";

    private static final String SDG_REASON_COLUMN = "sdg_reason";

    private static final Duration NOTIFIER_LEASE = Duration.ofMinutes(15);

    private static final String SEAM =
            "the report's four batch reads implement these statements; this is their red run";

    private static final long SECONDS_OF_SLACK = 5;

    /** Enough rows that the planner has a table worth choosing an index for. */
    private static final int SEEDED_ROWS = 2000;

    private static ReportReadsDatabase database;
    private static RegisterBatchRepository repository;

    @InjectSoftAssertions
    private SoftAssertions softly;

    @BeforeAll
    static void migrate() {
        database = ReportReadsDatabase.migrated(DATABASE);
        repository = new RegisterBatchRepository(database.jdbcClient(), database.transactions(),
                NOTIFIER_LEASE);
    }

    /** The database goes with the suite, so its name is free for a second load of this class. */
    @AfterAll
    static void dropTheDatabase() {
        database.drop();
    }

    @BeforeEach
    void emptyTheTable() {
        database.empty(BATCH_TABLE);
    }

    /**
     * What each of the four answers with, and what each of them leaves out.
     */
    @Nested
    @DisplayName("what each read answers with")
    class WhatIsAnswered {

        @Test
        void late_pending_returns_pending_batches_assembled_before_the_cut_off_oldest_first() {
            final RegisterBatch oldest = pending(MONDAY, minutesAgo(90));
            final RegisterBatch next = pending(TUESDAY, minutesAgo(45));
            pending(MONDAY, minutesAgo(2));
            generating(TUESDAY, minutesAgo(90));

            softly.assertThat(latePending(minutesAgo(30)))
                    .as("a court centre's day nothing has been asked of the renderer for, oldest "
                            + "first; a batch assembled a moment ago is a run still working")
                    .extracting(BatchException::batchId)
                    .containsExactly(oldest.batchId(), next.batchId());
        }

        @Test
        void late_generating_returns_generating_batches_requested_before_the_cut_off_oldest_first() {
            final RegisterBatch oldest = generating(MONDAY, minutesAgo(90));
            final RegisterBatch next = generating(TUESDAY, minutesAgo(45));
            generating(MONDAY, minutesAgo(2));
            pending(TUESDAY, minutesAgo(90));

            softly.assertThat(lateGenerating(minutesAgo(30)))
                    .as("a render systemdocgenerator accepted and has not answered, oldest first")
                    .extracting(BatchException::batchId)
                    .containsExactly(oldest.batchId(), next.batchId());
        }

        @Test
        void late_generated_returns_generated_batches_generated_before_the_cut_off_oldest_first() {
            final RegisterBatch oldest = generated(MONDAY, minutesAgo(90));
            final RegisterBatch next = generated(TUESDAY, minutesAgo(45));
            generated(MONDAY, minutesAgo(2));
            generating(TUESDAY, minutesAgo(90));

            softly.assertThat(lateGenerated(minutesAgo(30)))
                    .as("the one state that leaves a Youth Offending Team untold while a document "
                            + "for it exists, oldest first")
                    .extracting(BatchException::batchId)
                    .containsExactly(oldest.batchId(), next.batchId());
        }

        @Test
        void failed_since_returns_batches_failed_inside_the_window_and_nothing_else() {
            final RegisterBatch failed = failed(MONDAY, minutesAgo(45));
            failed(TUESDAY, hoursAgo(9));
            generating(MONDAY, hoursAgo(9));

            softly.assertThat(failedBetween(hoursAgo(2), soon()))
                    .as("a report is a statement about a period: a batch that ended before the "
                            + "window belongs to the report that already named it")
                    .extracting(BatchException::batchId)
                    .containsExactly(failed.batchId());
        }

        @Test
        void failed_since_excludes_a_row_failed_at_or_after_the_window_end() {
            final Instant boundary = minutesAgo(45);
            failed(MONDAY, boundary);
            failed(TUESDAY, minutesAgo(20));

            softly.assertThat(failedBetween(hoursAgo(4), boundary))
                    .as("the window is half-open, so a batch that ended on the very instant a "
                            + "run's window closes belongs to the next run: the two ends abut, "
                            + "and a dead batch counted by both is a court centre's day support "
                            + "chases twice")
                    .isEmpty();
            softly.assertThat(failedBetween(hoursAgo(4), boundary.minusSeconds(1)))
                    .as("and neither is the one that ended later still, which is what makes the "
                            + "morning's report a statement about a closed period")
                    .isEmpty();
        }

        @Test
        void every_read_carries_the_court_centre_the_register_date_and_the_bounded_failure_reason() {
            final RegisterBatch waiting = pending(MONDAY, minutesAgo(90));
            final RegisterBatch requested = generating(MONDAY, minutesAgo(90));
            final RegisterBatch rendered = generated(MONDAY, minutesAgo(90));
            final RegisterBatch ended = failed(TUESDAY, minutesAgo(90));

            softly.assertThat(latePending(minutesAgo(30)))
                    .as("the key, because a support engineer reads a national morning a court "
                            + "centre's day at a time; nothing has ended, so no reason")
                    .extracting(BatchException::courtCentreId, BatchException::registerDate,
                            BatchException::status, BatchException::failureReason,
                            BatchException::attempts)
                    .containsExactly(tuple(waiting.courtCentreId(), MONDAY, BatchStatus.PENDING,
                            null, 0));
            softly.assertThat(lateGenerating(minutesAgo(30)))
                    .as("the same three facts, and the attempt tally the render moved")
                    .extracting(BatchException::courtCentreId, BatchException::registerDate,
                            BatchException::status, BatchException::failureReason,
                            BatchException::attempts)
                    .containsExactly(tuple(requested.courtCentreId(), MONDAY,
                            BatchStatus.GENERATING, null, 1));
            softly.assertThat(lateGenerated(minutesAgo(30)))
                    .as("and again for the batch holding a document")
                    .extracting(BatchException::courtCentreId, BatchException::registerDate,
                            BatchException::status, BatchException::failureReason)
                    .containsExactly(tuple(rendered.courtCentreId(), MONDAY, BatchStatus.GENERATED,
                            null));
            softly.assertThat(failedBetween(hoursAgo(2), soon()))
                    .as("the bounded reason, which is this service's own code with a fixed "
                            + "meaning and never the renderer's words about the document")
                    .extracting(BatchException::courtCentreId, BatchException::registerDate,
                            BatchException::status, BatchException::failureReason)
                    .containsExactly(tuple(ended.courtCentreId(), TUESDAY, BatchStatus.FAILED,
                            BatchFailureReason.GENERATION_FAILED));
        }
    }

    /**
     * Where the ages come from, and the column none of the four may read.
     */
    @Nested
    @DisplayName("how the four behave as statements")
    class AsStatements {

        @Test
        void age_seconds_is_answered_in_seconds_from_the_stage_timestamp() {
            pending(MONDAY, minutesAgo(90));
            generating(MONDAY, minutesAgo(60));
            generated(MONDAY, minutesAgo(45));
            failed(TUESDAY, minutesAgo(30));

            final List<Long> ages = everyAge();
            final List<String> statements = everyStatement();
            final List<Long> stages = List.of(Duration.ofMinutes(90).toSeconds(),
                    Duration.ofMinutes(60).toSeconds(), Duration.ofMinutes(45).toSeconds(),
                    Duration.ofMinutes(30).toSeconds());

            for (int read = 0; read < stages.size(); read++) {
                softly.assertThat(ages.get(read))
                        .as("read %d measures from its own stage timestamp: assembly, the render "
                                + "request, the document, the ending", read)
                        .isCloseTo(stages.get(read), offset(SECONDS_OF_SLACK));
                softly.assertThat(statements.get(read))
                        .as("and read %d takes that reading in the database, in the same "
                                + "statement that selects the row - which is the only place it "
                                + "can be taken, because this repository holds no clock to "
                                + "compare a stored timestamp against (V1's single time "
                                + "authority)", read)
                        .contains("now()")
                        .contains("extract(epoch");
            }
        }

        /**
         * Every one of the four is made on a schedule, so every one of them is planned here.
         *
         * <p>Two arrangements, and both matter, for the reasons {@code ProcessedRequestReportReadsIT}
         * states: {@code ANALYZE} first, because a table the planner has no statistics for is a
         * table it will sequentially scan whatever indexes exist; then
         * {@code enable_seqscan = off} for the session, so what is asserted is "this query
         * <em>can</em> use this index" - the claim V5 makes - rather than "today's row count
         * happened to make it cheapest", which is a case that goes green on a small table and red
         * on the production one.
         */
        @Test
        void every_report_read_is_served_by_a_v5_index() throws SQLException {
            seedManyRows();

            softly.assertThat(planFor(minutesAgo(30), () -> latePending(minutesAgo(30))))
                    .as("a court centre's day nothing has been asked about, found without reading "
                            + "past every batch the estate has ever rendered")
                    .contains("idx_batch_late_pending");
            softly.assertThat(planFor(minutesAgo(30), () -> lateGenerating(minutesAgo(30))))
                    .as("and the render nobody answered")
                    .contains("idx_batch_late_generating");
            softly.assertThat(planFor(minutesAgo(30), () -> lateGenerated(minutesAgo(30))))
                    .as("and the document nobody was told about")
                    .contains("idx_batch_late_generated");
            softly.assertThat(planFor(hoursAgo(4), () -> failedBetween(hoursAgo(4), minutesAgo(1))))
                    .as("and the batches that ended inside the window, which is the read a bad "
                            + "morning makes longest")
                    .contains("idx_batch_failed_at");
        }

        @Test
        void every_read_goes_through_store_outage_translating() {
            PostgresTestSupport.refuseConnectionsTo(DATABASE);
            try {
                softly.assertThatThrownBy(() -> repository.latePending(minutesAgo(30)))
                        .as("an unreachable store is the generation half's own signal, and a "
                                + "org.springframework.dao type reaching the core is Principle V")
                        .isInstanceOf(StoreUnavailableException.class);
                softly.assertThatThrownBy(() -> repository.lateGenerating(minutesAgo(30)))
                        .as("the same for the render nobody has answered for")
                        .isInstanceOf(StoreUnavailableException.class);
                softly.assertThatThrownBy(() -> repository.lateGenerated(minutesAgo(30)))
                        .as("and for the document nobody has been told about")
                        .isInstanceOf(StoreUnavailableException.class);
                softly.assertThatThrownBy(
                        () -> repository.failedBetween(hoursAgo(2), soon()))
                        .as("and for the batches that ended, so a morning the database is away "
                                + "is a run that failed for a reason with a name rather than a "
                                + "driver exception nobody classified")
                        .isInstanceOf(StoreUnavailableException.class);
            } finally {
                PostgresTestSupport.allowConnectionsTo(DATABASE);
            }
        }

        @Test
        void the_sdg_reason_column_is_never_selected_by_any_of_the_four() {
            failed(MONDAY, minutesAgo(30));
            database.forgetStatements();

            latePending(minutesAgo(10));
            lateGenerating(minutesAgo(10));
            lateGenerated(minutesAgo(10));
            failedBetween(hoursAgo(2), soon());

            softly.assertThat(database.statements())
                    .as("four reads, four statements")
                    .hasSize(4);
            softly.assertThat(String.join("\n", database.statements()))
                    .as("another system's free text about a document whose every defendant is a "
                            + "child: a column that is never read cannot reach a line, a label or "
                            + "the CSV")
                    .doesNotContain(SDG_REASON_COLUMN);
        }

        @Test
        void the_002_entity_reads_are_untouched() {
            final RegisterBatch waiting = pending(MONDAY, minutesAgo(90));
            final RegisterBatch requested = generating(MONDAY, minutesAgo(90));
            final RegisterBatch rendered = generated(TUESDAY, minutesAgo(90));

            softly.assertThat(repository.generatingSince(minutesAgo(30)))
                    .as("002's read still answers entities, and still answers the same rows "
                            + "RegisterBatchRepositoryIT expects of it")
                    .containsExactly(requested);
            softly.assertThat(repository.generatedSince(minutesAgo(30)))
                    .as("likewise the parked read, unrenamed and unwidened")
                    .containsExactly(rendered);
            softly.assertThat(repository.pendingSince(minutesAgo(30)))
                    .as("and its own predicate is untouched: 002's read is about a batch that "
                            + "minted a payload, so one that never did is invisible to it")
                    .isEmpty();
            softly.assertThat(latePending(minutesAgo(30)))
                    .as("which is exactly why the report needs a read of its own - that batch has "
                            + "been waiting longest of all")
                    .extracting(BatchException::batchId)
                    .containsExactly(waiting.batchId());
        }
    }

    // --- the reads, made so that a seam's refusal is recorded rather than thrown ----------------

    private List<BatchException> latePending(final Instant assembledBefore) {
        return answered(() -> repository.latePending(assembledBefore));
    }

    private List<BatchException> lateGenerating(final Instant requestedBefore) {
        return answered(() -> repository.lateGenerating(requestedBefore));
    }

    private List<BatchException> lateGenerated(final Instant generatedBefore) {
        return answered(() -> repository.lateGenerated(generatedBefore));
    }

    private List<BatchException> failedBetween(final Instant from, final Instant to) {
        return answered(() -> repository.failedBetween(from, to));
    }

    // --- the plan the database makes of the statement the repository really ran -----------------

    /**
     * Runs one read, takes the statement it prepared, and asks the database to plan that statement.
     *
     * <p>The statement is taken from the driver rather than spelled again here, because a copy in a
     * test proves what the copy can use and says nothing about what the repository runs.
     *
     * @param parameter what to bind every placeholder to, which is the read's own cut-off
     * @param read      the read to make
     * @return the plan, as the lines EXPLAIN answered with
     * @throws SQLException where the session itself could not be opened
     */
    private String planFor(final Instant parameter, final Runnable read) throws SQLException {
        database.forgetStatements();
        softly.assertThatCode(read::run).as(SEAM).doesNotThrowAnyException();
        final List<String> executed = database.statements();
        softly.assertThat(executed)
                .as("one read is one statement, and the plan asked for below is that statement's")
                .hasSize(1);
        final String sql = executed.isEmpty() ? "SELECT 1" : executed.get(0);

        try (Connection connection = database.openConnection();
             Statement session = connection.createStatement()) {
            session.execute("ANALYZE " + BATCH_TABLE);
            session.execute("SET enable_seqscan = off");
            return plan(connection, sql, parameter);
        }
    }

    private static String plan(final Connection connection, final String sql,
            final Instant parameter) throws SQLException {
        final StringBuilder lines = new StringBuilder();
        try (PreparedStatement explain = connection.prepareStatement("EXPLAIN " + sql)) {
            for (int marker = 1; marker <= placeholders(sql); marker++) {
                explain.setObject(marker, OffsetDateTime.ofInstant(parameter, ZoneOffset.UTC));
            }
            try (ResultSet rows = explain.executeQuery()) {
                while (rows.next()) {
                    lines.append(rows.getString(1)).append('\n');
                }
            }
        }
        return lines.toString();
    }

    private static int placeholders(final String sql) {
        return (int) sql.chars().filter(character -> character == '?').count();
    }

    /**
     * A table worth planning over: two thousand batches a year of nights could leave behind.
     *
     * <p>Written in one statement rather than two thousand, because what the case needs is a table
     * the planner has statistics about and not two thousand round trips. The statuses are spread
     * so that every one of the four partial indexes has rows to find and rows to skip.
     */
    private void seedManyRows() {
        database.jdbcClient()
                .sql("""
                        INSERT INTO register_batch (
                            batch_id, court_centre_id, court_centre_ou_code, court_house,
                            register_date, file_name, status, failure_reason, completed_by,
                            system_generated, assembled_at, requested_at, generated_at, failed_at,
                            attempts, supplement_index)
                        SELECT gen_random_uuid(), gen_random_uuid(), :ouCode, :courtHouse,
                               :registerDate, 'court-register.pdf',
                               CASE WHEN g % 40 = 0 THEN 'PENDING'
                                    WHEN g % 41 = 0 THEN 'GENERATING'
                                    WHEN g % 43 = 0 THEN 'GENERATED'
                                    WHEN g % 47 = 0 THEN 'FAILED'
                                    ELSE 'NOTIFIED' END,
                               CASE WHEN g % 47 = 0 AND g % 40 <> 0 AND g % 41 <> 0
                                         AND g % 43 <> 0 THEN 'GENERATION_FAILED' END,
                               CASE WHEN g % 40 = 0 OR g % 41 = 0 THEN NULL
                                    ELSE 'EVENT' END,
                               true,
                               now() - (g || ' minutes')::interval,
                               now() - (g || ' minutes')::interval,
                               now() - (g || ' minutes')::interval,
                               now() - (g || ' minutes')::interval,
                               1, 0
                          FROM generate_series(1, :rows) AS g
                        """)
                .param("ouCode", OU_CODE)
                .param("courtHouse", COURT_HOUSE)
                .param("registerDate", MONDAY)
                .param("rows", SEEDED_ROWS)
                .update();
    }

    private List<BatchException> answered(final Supplier<List<BatchException>> read) {
        final AtomicReference<List<BatchException>> answer = new AtomicReference<>(List.of());
        softly.assertThatCode(() -> answer.set(read.get())).as(SEAM).doesNotThrowAnyException();
        return answer.get();
    }

    /** The one age each of the four reads answers with, in the order the four are listed. */
    private List<Long> everyAge() {
        return List.of(firstAge(latePending(minutesAgo(10))),
                firstAge(lateGenerating(minutesAgo(10))),
                firstAge(lateGenerated(minutesAgo(10))),
                firstAge(failedBetween(hoursAgo(4), soon())));
    }

    /** The statement each of the four really prepared, in the same order. */
    private List<String> everyStatement() {
        return List.of(statementOf(() -> latePending(minutesAgo(10))),
                statementOf(() -> lateGenerating(minutesAgo(10))),
                statementOf(() -> lateGenerated(minutesAgo(10))),
                statementOf(() -> failedBetween(hoursAgo(4), soon())));
    }

    /**
     * The SQL one read was prepared with, taken off the driver rather than spelled again here.
     *
     * @param read the read to make
     * @return its statement, or an empty string where the seam refused before preparing one
     */
    private String statementOf(final Runnable read) {
        database.forgetStatements();
        read.run();
        final List<String> executed = database.statements();
        return executed.isEmpty() ? "" : executed.get(0);
    }

    private static long firstAge(final List<BatchException> answered) {
        return answered.isEmpty() ? -1 : answered.get(0).ageSeconds();
    }

    // --- seeding: every batch walks the states the diagram draws --------------------------------

    /** A batch of its own court centre, where a batch enters the table. */
    private RegisterBatch pending(final LocalDate registerDate, final Instant assembledAt) {
        final RegisterBatch assembled = new RegisterBatch(UUID.randomUUID(), UUID.randomUUID(),
                OU_CODE, COURT_HOUSE, registerDate, fileName(registerDate), null, null,
                BatchStatus.PENDING, null, null, true, null, assembledAt, null, null, null, null,
                0, null, 0);
        repository.insert(assembled);
        return assembled;
    }

    /** The same batch after systemdocgenerator accepted its render request. */
    private RegisterBatch generating(final LocalDate registerDate, final Instant requestedAt) {
        final RegisterBatch assembled = pending(registerDate, requestedAt.minusSeconds(4));
        final RegisterBatch requested = new RegisterBatch(assembled.batchId(),
                assembled.courtCentreId(), OU_CODE, COURT_HOUSE, registerDate,
                assembled.fileName(), UUID.randomUUID(), null, BatchStatus.GENERATING, null, null,
                true, null, assembled.assembledAt(), requestedAt, null, null, null, 1, null, 0);
        repository.compareAndSet(requested, BatchStatus.PENDING);
        return requested;
    }

    /** And again once its document exists and nobody has been told yet. */
    private RegisterBatch generated(final LocalDate registerDate, final Instant generatedAt) {
        final RegisterBatch requested = generating(registerDate, generatedAt.minusSeconds(60));
        final RegisterBatch rendered = new RegisterBatch(requested.batchId(),
                requested.courtCentreId(), OU_CODE, COURT_HOUSE, registerDate,
                requested.fileName(), requested.payloadFileId(), DOCUMENT_FILE_ID,
                BatchStatus.GENERATED, null, null, true, CompletedBy.EVENT,
                requested.assembledAt(), requested.requestedAt(), generatedAt, null, null, 1, null,
                0);
        repository.compareAndSet(rendered, BatchStatus.GENERATING);
        return rendered;
    }

    /**
     * A batch the renderer said had failed, which is the one ending that carries an
     * {@code sdg_reason}.
     *
     * <p>GENERATION_FAILED deliberately: it is the reason whose row holds systemdocgenerator's own
     * words, so the column the four reads may never select is really populated in this table.
     */
    private RegisterBatch failed(final LocalDate registerDate, final Instant failedAt) {
        final RegisterBatch requested = generating(registerDate, failedAt.minusSeconds(120));
        final RegisterBatch ended = new RegisterBatch(requested.batchId(),
                requested.courtCentreId(), OU_CODE, COURT_HOUSE, registerDate,
                requested.fileName(), requested.payloadFileId(), null, BatchStatus.FAILED,
                BatchFailureReason.GENERATION_FAILED, SDG_REASON, true, CompletedBy.EVENT,
                requested.assembledAt(), requested.requestedAt(), null, null, failedAt, 1, null, 0);
        repository.compareAndSet(ended, BatchStatus.GENERATING);
        return ended;
    }

    private static String fileName(final LocalDate registerDate) {
        return "court-register_" + registerDate + '_' + OU_CODE + ".pdf";
    }

    /**
     * A moment in the past, at the precision the column holds.
     *
     * <p>Truncated to microseconds because {@code timestamptz} is: a nanosecond this JVM minted and
     * the database rounded away would make the entity cases fail on how precisely Postgres stores a
     * timestamp rather than on what the read answers.
     */
    private static Instant hoursAgo(final long hours) {
        return stored(Instant.now().minus(Duration.ofHours(hours)));
    }

    private static Instant minutesAgo(final long minutes) {
        return stored(Instant.now().minus(Duration.ofMinutes(minutes)));
    }

    /**
     * A window end far enough ahead that it excludes nothing this suite seeded.
     *
     * <p>Every case but the boundary one is about the start; an end is now required of the read,
     * and one an hour out keeps those cases about the question they were written for.
     */
    private static Instant soon() {
        return stored(Instant.now().plus(Duration.ofHours(1)));
    }

    private static Instant stored(final Instant moment) {
        return moment.truncatedTo(ChronoUnit.MICROS);
    }
}
