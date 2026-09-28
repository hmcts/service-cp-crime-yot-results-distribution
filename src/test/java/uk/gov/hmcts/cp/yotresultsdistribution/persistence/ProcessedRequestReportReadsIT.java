package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.data.Offset.offset;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ProcessedRequestSummary;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ReportReadsDatabase;

/**
 * The three reads the exception report and the intake sweep make of the processed log.
 *
 * <p>Two of them are the report's intake kinds - the requests parked inside the window, and the
 * requests still in flight past the threshold - and the third is the sweep's first gauge. All three
 * answer a projection carrying an age, and the age is the property most of these cases are about:
 * it is computed by the database in the same statement that selects the row, so nothing here
 * subtracts a stored timestamp from a reading of a JVM clock.
 *
 * <p><strong>A database of this suite's own</strong>, migrated in full. Almost every case here is a
 * claim about what the read does <em>not</em> return - the terminal states, the rows outside the
 * window, the rows inside the cut-off - and one of them is about an empty table. None of those is
 * observable against the container the other persistence suites share, where what else is in the
 * table is whatever ran first.
 *
 * <p>Soft assertions throughout, for the reason {@code RegisterStoreIT} gives: under the red-run
 * convention these cases are written against seams that refuse, and a hard assertion would stop
 * each case at the refusal so the recorded red would be a stack trace from the arrangement rather
 * than the property under test. Each read is made inside {@code assertThatCode(...)}, which records
 * the refusal, and the case then asserts on what came back.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the processed log's report reads")
class ProcessedRequestReportReadsIT {

    private static final String DATABASE = "yotresultsdistribution_request_report_reads";

    private static final String REQUEST_TABLE = "processed_request";

    private static final String SOURCE = "RESULTS";

    private static final LocalDate HEARING_DAY = LocalDate.of(2026, 9, 14);

    /** The claim lease is irrelevant to a read, and is stated rather than defaulted. */
    private static final Duration LEASE = Duration.ofMinutes(5);

    /** The description every read made against an unwritten statement carries. */
    private static final String SEAM =
            "the report's own reads implement these statements; this is their red run";

    /** How much the two ages of one row may differ and still be the database's own reading. */
    private static final long SECONDS_OF_SLACK = 5;

    /** Enough rows that the planner has a table worth choosing an index for. */
    private static final int SEEDED_ROWS = 2000;

    /** The intake threshold, as `yotresultsdistribution.report.request-terminal-within` defaults it. */
    private static final Duration THRESHOLD = Duration.ofMinutes(30);

    private static ReportReadsDatabase database;
    private static ProcessedRequestRepository repository;

    @InjectSoftAssertions
    private SoftAssertions softly;

    @BeforeAll
    static void migrate() {
        database = ReportReadsDatabase.migrated(DATABASE);
        repository = new ProcessedRequestRepository(database.jdbcClient(), LEASE);
    }

    /** The database goes with the suite, so its name is free for a second load of this class. */
    @AfterAll
    static void dropTheDatabase() {
        database.drop();
    }

    @BeforeEach
    void emptyTheLog() {
        database.empty(REQUEST_TABLE);
    }

    /**
     * The window read, which is bounded, and the cut-off read, which deliberately is not.
     */
    @Nested
    @DisplayName("what each read answers with")
    class WhatIsAnswered {

        @Test
        void failed_since_returns_failed_rows_inside_the_window_and_nothing_else() {
            final UUID parked = seed(RequestStatus.FAILED, "store-unavailable",
                    Duration.ofHours(4), Duration.ofHours(1));
            seed(RequestStatus.FAILED, "store-unavailable", Duration.ofHours(9),
                    Duration.ofHours(8));
            seed(RequestStatus.COMPLETED, null, Duration.ofHours(4), Duration.ofHours(1));
            seed(RequestStatus.RETRYING, "store-unavailable", Duration.ofHours(4),
                    Duration.ofHours(1));

            final List<ProcessedRequestSummary> failed = failedBetween(hoursAgo(2), soon());

            softly.assertThat(failed)
                    .as("a report is a statement about a period: a request parked before the "
                            + "window belongs to the report that already named it, and a request "
                            + "that is not parked at all is not this kind")
                    .extracting(ProcessedRequestSummary::requestId)
                    .containsExactly(parked);
            softly.assertThat(failed)
                    .as("the row as the report reads it, and the columns the entry is built from")
                    .extracting(ProcessedRequestSummary::source,
                            ProcessedRequestSummary::hearingDay,
                            ProcessedRequestSummary::status,
                            ProcessedRequestSummary::attempts,
                            ProcessedRequestSummary::failureReason)
                    .containsExactly(tuple(
                            SOURCE, HEARING_DAY, RequestStatus.FAILED, 5, "store-unavailable"));
        }

        @Test
        void failed_since_includes_a_row_failed_exactly_at_the_window_start() {
            final UUID parked = seed(RequestStatus.FAILED, "store-unavailable",
                    Duration.ofHours(4), Duration.ofHours(1));
            final Instant parkedAt = parkedAtOf(failedBetween(hoursAgo(9), soon()));

            softly.assertThat(failedBetween(parkedAt, soon()))
                    .as("the window's start is inclusive, so a request parked on the very instant "
                            + "the previous run closed its window is named by this one rather "
                            + "than by neither: consecutive windows abut, and a failure on the "
                            + "boundary is the one a run is likeliest to have been in the middle "
                            + "of writing")
                    .extracting(ProcessedRequestSummary::requestId)
                    .containsExactly(parked);
        }

        @Test
        void failed_since_excludes_a_row_failed_at_or_after_the_window_end() {
            seed(RequestStatus.FAILED, "store-unavailable", Duration.ofHours(4),
                    Duration.ofHours(1));
            final Instant parkedAt = parkedAtOf(failedBetween(hoursAgo(9), hoursAgo(-1)));
            seed(RequestStatus.FAILED, "schema-violation", Duration.ofHours(4),
                    Duration.ofMinutes(30));

            softly.assertThat(failedBetween(hoursAgo(9), parkedAt))
                    .as("the window is half-open, so a request parked on the very instant a run's "
                            + "window closes belongs to the next run and not to this one: the two "
                            + "ends abut, and a row on the boundary counted by both is a failure "
                            + "support is sent after twice")
                    .isEmpty();
            softly.assertThat(failedBetween(hoursAgo(9), parkedAt.minusSeconds(1)))
                    .as("and neither is anything after it, which is the reads' half of the "
                            + "aligned window: a report is a statement about a closed period")
                    .isEmpty();
        }

        @Test
        void non_terminal_older_than_returns_received_and_retrying_oldest_first() {
            final UUID oldest = seed(RequestStatus.RECEIVED, null, Duration.ofHours(4),
                    Duration.ofHours(4));
            final UUID next = seed(RequestStatus.RETRYING, "store-unavailable",
                    Duration.ofHours(3), Duration.ofMinutes(20));
            seed(RequestStatus.RECEIVED, null, Duration.ofMinutes(5), Duration.ofMinutes(5));

            softly.assertThat(nonTerminalOlderThan(hoursAgo(1)))
                    .as("oldest first, because the worst problem is the one read first on a "
                            + "screen and in a table, and both states are the same debt")
                    .extracting(ProcessedRequestSummary::requestId)
                    .containsExactly(oldest, next);
        }

        @Test
        void non_terminal_older_than_excludes_terminal_rows() {
            final UUID waiting = seed(RequestStatus.RECEIVED, null, Duration.ofHours(4),
                    Duration.ofHours(4));
            seed(RequestStatus.COMPLETED, null, Duration.ofHours(4), Duration.ofHours(4));
            seed(RequestStatus.FAILED, "store-unavailable", Duration.ofHours(4),
                    Duration.ofHours(4));

            softly.assertThat(nonTerminalOlderThan(hoursAgo(1)))
                    .as("the two intake predicates are disjoint by construction, which is what "
                            + "makes a request that was late and has since failed appear once")
                    .extracting(ProcessedRequestSummary::requestId)
                    .containsExactly(waiting);
        }

        @Test
        void oldest_non_terminal_answers_empty_on_an_empty_table() {
            softly.assertThat(oldestNonTerminal())
                    .as("a service with nothing unfinished is the ordinary case, and the gauge "
                            + "reads zero from an absence rather than from a row that is not there")
                    .isEmpty();
        }

        @Test
        void count_non_terminal_older_than_answers_the_number_without_the_rows() {
            seed(RequestStatus.RECEIVED, null, Duration.ofHours(4), Duration.ofHours(4));
            seed(RequestStatus.RETRYING, "store-unavailable", Duration.ofHours(3),
                    Duration.ofMinutes(20));
            seed(RequestStatus.RECEIVED, null, Duration.ofMinutes(5), Duration.ofMinutes(5));
            seed(RequestStatus.COMPLETED, null, Duration.ofHours(4), Duration.ofHours(4));
            seed(RequestStatus.FAILED, "store-unavailable", Duration.ofHours(4),
                    Duration.ofHours(4));

            softly.assertThat(countNonTerminalOlderThan(hoursAgo(1)))
                    .as("the same predicate the list read spells, answered as a number: the "
                            + "gauge needs the count and nothing else, and reading the rows to "
                            + "size a list is a read that grows with the backlog it is reporting")
                    .isEqualTo(2);
        }

        @Test
        void count_non_terminal_older_than_answers_zero_on_an_empty_table() {
            softly.assertThat(countNonTerminalOlderThan(hoursAgo(1)))
                    .as("zero is a reading a healthy service gives every refresh interval, not "
                            + "an absence the gauge has to interpret")
                    .isZero();
        }

        @Test
        void a_request_exactly_at_the_threshold_is_not_over_it() {
            // The predicate is `created_at < :cutOff`, exclusive, and the boundary is shared with
            // the report's REQUEST_LATE read. Both are asserted against the row's OWN stored
            // instant, read back, rather than against one this suite derived from its own clock:
            // a cut-off computed here would be NEAR the boundary, and near is what the case exists
            // to rule out.
            seed(RequestStatus.RECEIVED, null, THRESHOLD, THRESHOLD);
            final Instant createdAt = createdAtOf(nonTerminalOlderThan(hoursAgo(-1)));

            softly.assertThat(countNonTerminalOlderThan(createdAt))
                    .as("a request created exactly the threshold ago has not yet been waiting "
                            + "longer than the threshold")
                    .isZero();
            softly.assertThat(countNonTerminalOlderThan(createdAt.plusSeconds(1)))
                    .as("and a second later it has")
                    .isEqualTo(1);
        }
    }

    /**
     * Where the ages come from, and what they are not derived from.
     */
    @Nested
    @DisplayName("the age of a row")
    class TheAge {

        @Test
        void age_seconds_is_answered_in_seconds_from_the_stage_timestamp() {
            seed(RequestStatus.RECEIVED, null, Duration.ofMinutes(10), Duration.ofMinutes(10));
            database.forgetStatements();

            final List<ProcessedRequestSummary> answered = nonTerminalOlderThan(hoursAgo(-1));

            softly.assertThat(ageOf(answered))
                    .as("the real age of the row, in seconds, measured from the moment it arrived")
                    .isCloseTo(Duration.ofMinutes(10).toSeconds(), offset(SECONDS_OF_SLACK));
            softly.assertThat(String.join("\n", database.statements()))
                    .as("and taken in the database, in the same statement that selects the row, "
                            + "which is the only place it can be taken: this repository holds no "
                            + "clock, so there is no JVM reading here to subtract a stored "
                            + "timestamp from (V1's single time authority), and two pods reading "
                            + "one row therefore agree about how old it is")
                    .contains("now()")
                    .contains("extract(epoch");
        }
    }

    /**
     * What an unreachable store answers with, and what the planner does with the two scheduled
     * reads.
     */
    @Nested
    @DisplayName("how the reads behave as statements")
    class AsStatements {

        @Test
        void every_read_goes_through_store_outage_translating() {
            PostgresTestSupport.refuseConnectionsTo(DATABASE);
            try {
                softly.assertThatThrownBy(
                        () -> repository.failedBetween(hoursAgo(2), soon()))
                        .as("an unreachable store is the intake half's own signal, and a "
                                + "org.springframework.dao type reaching the core is Principle V")
                        .isInstanceOf(StoreUnavailableException.class);
                softly.assertThatThrownBy(() -> repository.nonTerminalOlderThan(hoursAgo(2)))
                        .as("the same, for the read the sweep and the report share a predicate on")
                        .isInstanceOf(StoreUnavailableException.class);
                softly.assertThatThrownBy(repository::oldestNonTerminal)
                        .as("and for the sweep's own, which runs every refresh interval for the "
                                + "life of every pod and so meets an outage soonest")
                        .isInstanceOf(StoreUnavailableException.class);
                softly.assertThatThrownBy(() -> repository.countNonTerminalOlderThan(hoursAgo(2)))
                        .as("and for its second, which the sweep must be able to tell apart from "
                                + "a bug of ours - it is counted under its own bounded reason")
                        .isInstanceOf(StoreUnavailableException.class);
            } finally {
                PostgresTestSupport.allowConnectionsTo(DATABASE);
            }
        }

        @Test
        void every_scheduled_read_is_served_by_a_v4_index() throws SQLException {
            seedManyRows();

            softly.assertThat(planFor(hoursAgo(24),
                            () -> repository.failedBetween(hoursAgo(24), soon())))
                    .as("the window read is served by the total index on (status, updated_at), "
                            + "which is the whole reason V4 adds it")
                    .contains("idx_request_status_updated");
            softly.assertThat(planFor(hoursAgo(1),
                            () -> repository.nonTerminalOlderThan(hoursAgo(1))))
                    .as("and the in-flight read by the partial one, whose predicate is spelled "
                            + "exactly as this statement spells it so the implication is trivial")
                    .contains("idx_request_non_terminal_created");
            softly.assertThat(planFor(hoursAgo(1),
                            () -> repository.countNonTerminalOlderThan(hoursAgo(1))))
                    .as("and the count read by the same partial index, which is the whole reason "
                            + "it is spelled with the list read's predicate character for "
                            + "character - the sweep runs it every refresh interval for the life "
                            + "of every pod")
                    .contains("idx_request_non_terminal_created");
        }
    }

    // --- the reads, made so that a seam's refusal is recorded rather than thrown ----------------

    private List<ProcessedRequestSummary> failedBetween(final Instant from, final Instant to) {
        return answered(() -> repository.failedBetween(from, to));
    }

    private List<ProcessedRequestSummary> nonTerminalOlderThan(final Instant createdBefore) {
        return answered(() -> repository.nonTerminalOlderThan(createdBefore));
    }

    private long countNonTerminalOlderThan(final Instant createdBefore) {
        final AtomicLong answer = new AtomicLong(-1);
        softly.assertThatCode(
                        () -> answer.set(repository.countNonTerminalOlderThan(createdBefore)))
                .as(SEAM)
                .doesNotThrowAnyException();
        return answer.get();
    }

    private Optional<ProcessedRequestSummary> oldestNonTerminal() {
        final AtomicReference<Optional<ProcessedRequestSummary>> answer =
                new AtomicReference<>(Optional.empty());
        softly.assertThatCode(() -> answer.set(repository.oldestNonTerminal()))
                .as(SEAM)
                .doesNotThrowAnyException();
        return answer.get();
    }

    private List<ProcessedRequestSummary> answered(
            final Supplier<List<ProcessedRequestSummary>> read) {
        final AtomicReference<List<ProcessedRequestSummary>> answer =
                new AtomicReference<>(List.of());
        softly.assertThatCode(() -> answer.set(read.get())).as(SEAM).doesNotThrowAnyException();
        return answer.get();
    }

    // --- the plan the database makes of the statement the repository really ran -----------------

    /**
     * Runs one read, takes the statement it prepared, and asks the database to plan that statement.
     *
     * <p>The statement is taken from the driver rather than spelled again here, because a copy in a
     * test proves what the copy can use and says nothing about what the repository runs.
     *
     * <p>Two arrangements, and both matter. {@code ANALYZE} first: a table the planner has no
     * statistics for is a table it will sequentially scan whatever indexes exist. Then
     * {@code enable_seqscan = off} for this session, so what is asserted is "this query <em>can</em>
     * use this index" - the claim V4 makes - rather than "today's row count happened to make it
     * cheapest", which is a test that goes green on a small table and red on the production one.
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
            session.execute("ANALYZE " + REQUEST_TABLE);
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

    // --- seeding ------------------------------------------------------------------------------

    /**
     * One request row, aged by the database from its own {@code now()}.
     *
     * <p>{@code attempts} is five on every row, which is the delivery budget: a report that carried
     * the tally would be wrong about it, and a fixed number makes the assertion about the column
     * rather than about the fixture.
     */
    private UUID seed(final RequestStatus status, final String failureReason,
            final Duration createdAgo, final Duration updatedAgo) {
        final UUID requestId = UUID.randomUUID();
        database.jdbcClient()
                .sql("""
                        INSERT INTO processed_request (
                            source, request_id, hearing_id, hearing_day, shared_time, event_type,
                            request_fingerprint, status, attempts, failure_reason,
                            exhausted_message_id, created_at, updated_at)
                        VALUES (
                            :source, :requestId, :hearingId, :hearingDay, now(), 'Hearing_Resulted',
                            'fingerprint', :status, 5, :failureReason, :exhaustedMessageId,
                            now() - CAST(:createdAgo AS interval),
                            now() - CAST(:updatedAgo AS interval))
                        """)
                .param("source", SOURCE)
                .param("requestId", requestId)
                .param("hearingId", UUID.randomUUID())
                .param("hearingDay", HEARING_DAY)
                .param("status", status.name())
                .param("failureReason", failureReason, Types.VARCHAR)
                .param("exhaustedMessageId",
                        status == RequestStatus.FAILED ? SOURCE + ':' + requestId : null,
                        Types.VARCHAR)
                .param("createdAgo", createdAgo.toString())
                .param("updatedAgo", updatedAgo.toString())
                .update();
        return requestId;
    }

    /**
     * A table worth planning over: two thousand rows a week of deliveries could leave behind.
     *
     * <p>Written in one statement rather than two thousand, because what the case needs is a table
     * the planner has statistics about and not two thousand round trips.
     */
    private void seedManyRows() {
        database.jdbcClient()
                .sql("""
                        INSERT INTO processed_request (
                            source, request_id, hearing_id, hearing_day, shared_time, event_type,
                            request_fingerprint, status, attempts, exhausted_message_id,
                            created_at, updated_at)
                        SELECT :source, gen_random_uuid(), gen_random_uuid(), :hearingDay, now(),
                               'Hearing_Resulted', 'fingerprint',
                               CASE WHEN g % 40 = 0 THEN 'FAILED'
                                    WHEN g % 41 = 0 THEN 'RECEIVED'
                                    ELSE 'COMPLETED' END,
                               1,
                               CASE WHEN g % 40 = 0 THEN 'msg-' || g ELSE NULL END,
                               now() - (g || ' minutes')::interval,
                               now() - (g || ' minutes')::interval
                          FROM generate_series(1, :rows) AS g
                        """)
                .param("source", SOURCE)
                .param("hearingDay", HEARING_DAY)
                .param("rows", SEEDED_ROWS)
                .update();
    }

    // --- reading the answers ------------------------------------------------------------------

    private long ageOf(final List<ProcessedRequestSummary> answered) {
        return answered.isEmpty() ? -1 : answered.get(0).ageSeconds();
    }

    /**
     * The instant the database really parked the first row answered, to the precision it holds it.
     *
     * <p>Read back rather than computed here, because the boundary case is about the row's own
     * stored value: a cut-off this suite derived from its own clock would be near the boundary
     * rather than on it, and near is what the case exists to rule out.
     *
     * @param answered a read that found the row
     * @return its {@code updated_at}, or the epoch where nothing was answered
     */
    private Instant parkedAtOf(final List<ProcessedRequestSummary> answered) {
        return answered.isEmpty() ? Instant.EPOCH : answered.get(0).updatedAt();
    }

    /**
     * The instant the database really recorded the first row answered, to the precision it holds.
     *
     * @param answered a read that found the row
     * @return its {@code created_at}, or the epoch where nothing was answered
     */
    private Instant createdAtOf(final List<ProcessedRequestSummary> answered) {
        return answered.isEmpty() ? Instant.EPOCH : answered.get(0).createdAt();
    }

    private static Instant hoursAgo(final long hours) {
        return Instant.now().minus(Duration.ofHours(hours));
    }

    /**
     * A window end far enough ahead that it excludes nothing this suite seeded.
     *
     * <p>Every case but the boundary one is about the start; an end is now required of every read,
     * and one an hour out keeps those cases about the question they were written for.
     */
    private static Instant soon() {
        return Instant.now().plus(Duration.ofHours(1));
    }
}
