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
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailedNotification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotification;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ReportReadsDatabase;

/**
 * The one read the exception report makes of the notification rows.
 *
 * <p>NOTIFICATION_FAILED is the kind that says a register exists and a Youth Offending Team was not
 * told about it, so what the entry has to carry is which team's day it was - the batch's court
 * centre and register date - and how long ago the attempt was settled. Both come from the same
 * statement: the join is what stops the read being one query per row on precisely the morning the
 * list is longest.
 *
 * <p><strong>Two of these cases are about the statement rather than about the rows</strong>, and
 * neither is answerable from what comes back. One statement and thirty produce the same list, and a
 * column that is never selected leaves no trace in a projection that has no component for it. So
 * the fixture records the SQL the driver was asked to prepare, and those two cases read it.
 *
 * <p>A database of this suite's own, and soft assertions, for the reasons
 * {@link ProcessedRequestReportReadsIT} gives.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the notification rows' report read")
class RegisterNotificationReportReadsIT {

    private static final String DATABASE = "yotresultsdistribution_notification_report_reads";

    private static final String NOTIFICATION_TABLE = "register_notification";
    private static final String BATCH_TABLE = "register_batch";

    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 14);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 9, 15);

    private static final String OU_CODE = "B01LY00";
    private static final String COURT_HOUSE = "Lavender Hill Youth Court";

    private static final UUID TEMPLATE_ID =
            UUID.fromString("5f0b2d71-8c43-4e19-a6d2-7b1e4c093a58");
    private static final String TEMPLATE_NAME = "yot-results-distribution-notification";

    /**
     * The one personal value the table holds, and the string no captured statement may mention.
     */
    private static final String ADDRESS_COLUMN = "email_address";

    /** How long a claim stays live here; irrelevant to a read, and stated rather than defaulted. */
    private static final Duration NOTIFIER_LEASE = Duration.ofMinutes(15);

    private static final String SEAM =
            "the report's failed-notification read implements this statement; this is its red run";

    private static final long SECONDS_OF_SLACK = 5;

    /** Enough rows that the planner has a table worth choosing an index for. */
    private static final int SEEDED_ROWS = 2000;

    private static ReportReadsDatabase database;
    private static RegisterBatchRepository batches;
    private static RegisterNotificationRepository repository;

    @InjectSoftAssertions
    private SoftAssertions softly;

    @BeforeAll
    static void migrate() {
        database = ReportReadsDatabase.migrated(DATABASE);
        batches = new RegisterBatchRepository(database.jdbcClient(), database.transactions(),
                NOTIFIER_LEASE);
        repository = new RegisterNotificationRepository(database.jdbcClient());
    }

    /** The database goes with the suite, so its name is free for a second load of this class. */
    @AfterAll
    static void dropTheDatabase() {
        database.drop();
    }

    @BeforeEach
    void emptyTheTables() {
        database.empty(NOTIFICATION_TABLE, BATCH_TABLE);
    }

    @Test
    void failed_since_returns_failed_notifications_with_their_batchs_court_centre_and_register_date() {
        final RegisterBatch batch = batch(MONDAY);
        final UUID refused = settled(batch, "yot@example.gov.uk", NotificationStatus.FAILED, 400,
                minutesAgo(30));
        settled(batch, "accepted@example.gov.uk", NotificationStatus.ACCEPTED, 202, minutesAgo(30));
        settled(batch, "stale@example.gov.uk", NotificationStatus.FAILED, 500, hoursAgo(9));

        final List<FailedNotification> failed = failedBetween(hoursAgo(2), soon());

        softly.assertThat(failed)
                .as("a report is a statement about a period, and a team that was told is not an "
                        + "exception at all")
                .extracting(FailedNotification::notificationId)
                .containsExactly(refused);
        softly.assertThat(failed)
                .as("the batch's key, carried by the join, is what says whose day went untold")
                .extracting(FailedNotification::batchId, FailedNotification::courtCentreId,
                        FailedNotification::registerDate, FailedNotification::status,
                        FailedNotification::responseCode, FailedNotification::attempts)
                .containsExactly(tuple(batch.batchId(), batch.courtCentreId(), MONDAY,
                        NotificationStatus.FAILED, 400, 1));
    }

    @Test
    void failed_since_orders_oldest_first() {
        final RegisterBatch monday = batch(MONDAY);
        final RegisterBatch tuesday = batch(TUESDAY);
        final UUID oldest = settled(tuesday, "late@example.gov.uk", NotificationStatus.FAILED, 500,
                minutesAgo(90));
        final UUID next = settled(monday, "later@example.gov.uk", NotificationStatus.FAILED, 500,
                minutesAgo(45));
        final UUID newest = settled(monday, "newest@example.gov.uk", NotificationStatus.FAILED,
                null, minutesAgo(5));

        softly.assertThat(failedBetween(hoursAgo(4), soon()))
                .as("oldest first, because the team that has been waiting longest is the one a "
                        + "morning's resend starts with")
                .extracting(FailedNotification::notificationId)
                .containsExactly(oldest, next, newest);
    }

    @Test
    void failed_since_is_one_statement_not_one_per_row() {
        final RegisterBatch monday = batch(MONDAY);
        final RegisterBatch tuesday = batch(TUESDAY);
        settled(monday, "one@example.gov.uk", NotificationStatus.FAILED, 500, minutesAgo(30));
        settled(monday, "two@example.gov.uk", NotificationStatus.FAILED, 500, minutesAgo(20));
        settled(tuesday, "three@example.gov.uk", NotificationStatus.FAILED, 500, minutesAgo(10));
        database.forgetStatements();

        final List<FailedNotification> failed = failedBetween(hoursAgo(4), soon());

        softly.assertThat(database.statements())
                .as("N+1 reads land on precisely the morning the list is longest, which is the "
                        + "morning the report has to be quick")
                .hasSize(1);
        softly.assertThat(failed)
                .as("and it answered all three, across two batches")
                .hasSize(3);
    }

    @Test
    void failed_since_excludes_a_row_failed_at_or_after_the_window_end() {
        final RegisterBatch batch = batch(MONDAY);
        final Instant boundary = minutesAgo(45);
        settled(batch, "boundary@example.gov.uk", NotificationStatus.FAILED, 500, boundary);
        settled(batch, "later@example.gov.uk", NotificationStatus.FAILED, 500, minutesAgo(20));

        softly.assertThat(failedBetween(hoursAgo(4), boundary))
                .as("the window is half-open, so a send refused on the very instant a run's "
                        + "window closes belongs to the next run and not to this one: the two "
                        + "ends abut, and a Youth Offending Team named by both reports is a team "
                        + "support chases twice for one e-mail")
                .isEmpty();
        softly.assertThat(failedBetween(hoursAgo(4), boundary.minusSeconds(1)))
                .as("and neither is the refusal after it, which is what makes the morning's "
                        + "report a statement about a closed period")
                .isEmpty();
    }

    @Test
    void failed_since_includes_a_row_failed_exactly_at_the_window_start() {
        final RegisterBatch batch = batch(MONDAY);
        final Instant settledAt = minutesAgo(30);
        final UUID refused =
                settled(batch, "yot@example.gov.uk", NotificationStatus.FAILED, 500, settledAt);

        softly.assertThat(failedBetween(settledAt, soon()))
                .as("the window's start is inclusive, so a send refused on the very instant the "
                        + "previous run closed its window is named by this one rather than by "
                        + "neither: consecutive windows abut, and a Youth Offending Team that "
                        + "fell between two of them is a team nobody is ever told about")
                .extracting(FailedNotification::notificationId)
                .containsExactly(refused);
    }

    @Test
    void age_seconds_is_answered_in_seconds_from_the_stage_timestamp() {
        final RegisterBatch batch = batch(MONDAY);
        settled(batch, "yot@example.gov.uk", NotificationStatus.FAILED, 500, minutesAgo(10));
        database.forgetStatements();

        final List<FailedNotification> failed = failedBetween(hoursAgo(4), soon());

        softly.assertThat(ageOf(failed))
                .as("the real age, in seconds, measured from the moment the attempt was settled")
                .isCloseTo(Duration.ofMinutes(10).toSeconds(), offset(SECONDS_OF_SLACK));
        softly.assertThat(String.join("\n", database.statements()))
                .as("and taken in the database, in the same statement that selects the row: this "
                        + "repository holds no clock, so there is no JVM reading here for a "
                        + "stored timestamp to be subtracted from (V1's single time authority)")
                .contains("now()")
                .contains("extract(epoch");
    }

    /**
     * The read is made every weekday morning, so it is planned rather than assumed.
     *
     * <p>{@code ANALYZE} first, then {@code enable_seqscan = off} for the session, for the reasons
     * {@code ProcessedRequestReportReadsIT} states: what is asserted is that the statement
     * <em>can</em> use the index V5 adds, rather than that today's row count made it cheapest.
     */
    @Test
    void the_report_read_is_served_by_a_v5_index() throws SQLException {
        seedManyRows();

        softly.assertThat(planFor(hoursAgo(4)))
                .as("the refused sends inside the window, found without reading past every "
                        + "e-mail every batch in the estate has ever had accepted")
                .contains("idx_notification_failed_sent");
    }

    @Test
    void the_email_address_column_is_never_selected() {
        final RegisterBatch batch = batch(MONDAY);
        settled(batch, "yot@example.gov.uk", NotificationStatus.FAILED, 500, minutesAgo(30));
        database.forgetStatements();

        failedBetween(hoursAgo(4), soon());

        softly.assertThat(database.statements())
                .as("the read this case is about is the only statement it made")
                .hasSize(1);
        softly.assertThat(String.join("\n", database.statements()))
                .as("the one personal value in the table: a column that is never read cannot be "
                        + "logged by accident, so there is no value to mask and none to forget to")
                .doesNotContain(ADDRESS_COLUMN);
    }

    // --- the read, made so that a seam's refusal is recorded rather than thrown -----------------

    private List<FailedNotification> failedBetween(final Instant from, final Instant to) {
        final AtomicReference<List<FailedNotification>> answered = new AtomicReference<>(List.of());
        softly.assertThatCode(() -> answered.set(repository.failedBetween(from, to)))
                .as(SEAM)
                .doesNotThrowAnyException();
        return answered.get();
    }

    // --- the plan the database makes of the statement the repository really ran -----------------

    /**
     * Runs the read, takes the statement it prepared, and asks the database to plan that statement.
     *
     * @param since the window's start, which is what the placeholders bind to
     * @return the plan, as the lines EXPLAIN answered with
     * @throws SQLException where the session itself could not be opened
     */
    private String planFor(final Instant since) throws SQLException {
        database.forgetStatements();
        failedBetween(since, Instant.now());
        final List<String> executed = database.statements();
        softly.assertThat(executed)
                .as("one read is one statement, and the plan asked for below is that statement's")
                .hasSize(1);
        final String sql = executed.isEmpty() ? "SELECT 1" : executed.get(0);

        try (Connection connection = database.openConnection();
             Statement session = connection.createStatement()) {
            session.execute("ANALYZE " + NOTIFICATION_TABLE);
            session.execute("ANALYZE " + BATCH_TABLE);
            session.execute("SET enable_seqscan = off");
            final StringBuilder lines = new StringBuilder();
            try (PreparedStatement explain = connection.prepareStatement("EXPLAIN " + sql)) {
                for (int marker = 1; marker <= placeholders(sql); marker++) {
                    explain.setObject(marker, OffsetDateTime.ofInstant(since, ZoneOffset.UTC));
                }
                try (ResultSet rows = explain.executeQuery()) {
                    while (rows.next()) {
                        lines.append(rows.getString(1)).append('\n');
                    }
                }
            }
            return lines.toString();
        }
    }

    private static int placeholders(final String sql) {
        return (int) sql.chars().filter(character -> character == '?').count();
    }

    // --- seeding ------------------------------------------------------------------------------

    /**
     * A table worth planning over: one batch and two thousand recipients' rows on it.
     *
     * <p>Written in one statement rather than two thousand, because what the case needs is a table
     * the planner has statistics about. Most of them are accepted, which is the shape a healthy
     * estate has and the shape the partial index exists for.
     */
    private void seedManyRows() {
        final RegisterBatch batch = batch(TUESDAY);
        database.jdbcClient()
                .sql("""
                        INSERT INTO register_notification (
                            notification_id, batch_id, email_address, recipient_name,
                            template_name, template_id, status, response_code, sent_at, attempts)
                        SELECT gen_random_uuid(), :batchId, 'yot-' || g || '@example.gov.uk',
                               'Example Youth Offending Team', :templateName, :templateId,
                               CASE WHEN g % 40 = 0 THEN 'FAILED' ELSE 'ACCEPTED' END,
                               CASE WHEN g % 40 = 0 THEN 500 ELSE 202 END,
                               now() - (g || ' minutes')::interval,
                               1
                          FROM generate_series(1, :rows) AS g
                        """)
                .param("batchId", batch.batchId())
                .param("templateName", TEMPLATE_NAME)
                .param("templateId", TEMPLATE_ID)
                .param("rows", SEEDED_ROWS)
                .update();
    }

    /**
     * A batch of its own court centre, inserted where a batch enters the table.
     *
     * <p>Its state is irrelevant to this read - the join is for the key, not for the status - so it
     * is left where {@link RegisterBatchRepository#insert} admits it.
     */
    private RegisterBatch batch(final LocalDate registerDate) {
        final RegisterBatch assembled = new RegisterBatch(UUID.randomUUID(), UUID.randomUUID(),
                OU_CODE, COURT_HOUSE, registerDate, fileName(registerDate), null, null,
                BatchStatus.PENDING, null, null, true, null, Instant.now(), null, null, null, null,
                0, null, 0);
        batches.insert(assembled);
        return assembled;
    }

    /**
     * One recipient's row, minted already settled.
     *
     * <p>Written through the repository's own insert rather than as SQL of this suite's invention,
     * so the row is exactly the shape the notifying leg writes; {@code sent_at} is a value that leg
     * supplies, which is why the age this read computes is the database's {@code now()} less a
     * timestamp a pod wrote.
     */
    private UUID settled(final RegisterBatch batch, final String address,
            final NotificationStatus status, final Integer responseCode, final Instant sentAt) {
        final UUID notificationId = UUID.randomUUID();
        repository.insert(new RegisterNotification(notificationId, batch.batchId(), address,
                "Wandsworth Youth Offending Team", TEMPLATE_NAME, TEMPLATE_ID, status, responseCode,
                sentAt, 1));
        return notificationId;
    }

    // --- reading the answers ------------------------------------------------------------------

    private long ageOf(final List<FailedNotification> answered) {
        return answered.isEmpty() ? -1 : answered.get(0).ageSeconds();
    }

    private static String fileName(final LocalDate registerDate) {
        return "court-register_" + registerDate + '_' + OU_CODE + ".pdf";
    }

    /**
     * A moment in the past, at the precision {@code timestamptz} holds.
     *
     * <p>Truncated to microseconds because the boundary case compares a cut-off against the very
     * value it seeded: a nanosecond this JVM minted and the database rounded would make that case
     * about rounding rather than about whether the predicate is inclusive.
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
