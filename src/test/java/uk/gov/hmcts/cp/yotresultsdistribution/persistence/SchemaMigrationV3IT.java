package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;

/**
 * Pins the V3 index that keeps one active register per hearing, court centre and register day.
 *
 * <p>V2 stated the invariant the whole batch half is written against and left it to the recorder to
 * keep: at most one RECORDED, unsuperseded, unbatched row per {@code (hearing_id, court_centre_id,
 * register_date)}. A recorder keeps it by reading the hearing's active row and superseding what it
 * finds, and a read is exactly what two re-shares of one hearing can both do before either of them
 * has committed - so V3 puts the invariant where the database can see both writers.
 *
 * <p>What the index refuses is asserted here; what the store does when it meets that refusal is
 * {@code RegisterStoreIT.two_concurrent_re_shares_leave_exactly_one_active_row}. This suite is the
 * other half of the same guarantee, and it is deliberately about the <em>predicate</em>: three
 * kinds of row are outside it, each for a reason that would be expensive to get wrong, and a
 * behavioural case says so for each of them rather than trusting a definition read back as text.
 */
class SchemaMigrationV3IT {

    private static final String REQUEST_TABLE = "processed_request";
    private static final String OUTPUT_TABLE = "processed_output";

    private static final String INDEX = "idx_output_active_register_key";

    /** One hearing, one court centre and one day: the key every case in this suite is about. */
    private static final UUID HEARING = UUID.fromString("2f0c2a3e-6d1b-4f5a-9c07-7a1c9d5b3e11");
    private static final UUID COURT_CENTRE = UUID.fromString("5b8e4d21-9a3c-4e7f-8b02-1d6f0c4a7e93");

    private static final String MONDAY = "DATE '2026-08-24'";
    private static final String TUESDAY = "DATE '2026-08-25'";

    private static final String RECORDED = "RECORDED";

    private static final String NOTHING = "";

    @BeforeAll
    static void migrate() {
        PostgresTestSupport.applyFlyway();
    }

    // --- helpers ---------------------------------------------------------------------------

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                PostgresTestSupport.jdbcUrl(),
                PostgresTestSupport.username(),
                PostgresTestSupport.password());
    }

    private static Map<String, String> indexesOf(final String table) throws SQLException {
        final Map<String, String> indexes = new LinkedHashMap<>();
        final String sql = """
                SELECT indexname, indexdef FROM pg_indexes
                 WHERE schemaname = 'public' AND tablename = ?
                """;
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    indexes.put(rows.getString("indexname"), rows.getString("indexdef"));
                }
            }
        }
        return indexes;
    }

    /**
     * Runs the statements inside a transaction that is always rolled back, so behavioural probes
     * leave no rows behind for the guard suites that share this container.
     */
    private static void inRolledBackTransaction(final String... statements) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                for (final String sql : statements) {
                    statement.executeUpdate(sql);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * The smallest valid {@code processed_request} row, for probes that need a parent to exist.
     */
    private static String insertValidRequest(final UUID requestId) {
        return "INSERT INTO " + REQUEST_TABLE + " (source, request_id, hearing_id, hearing_day, "
                + "shared_time, event_type, request_fingerprint, status, attempts) VALUES ("
                + "'RESULTS', '" + requestId + "', '" + HEARING + "', DATE '2026-08-19', "
                + "TIMESTAMPTZ '2026-08-19T09:00:00Z', 'Hearing_Resulted', 'fingerprint', "
                + "'RECEIVED', 1)";
    }

    /**
     * One register for this suite's hearing, under the given status, day and extra columns.
     *
     * <p>Every column the shape check requires of a recorded row, and the key columns named rather
     * than minted, because the key is the whole subject: a fixture that minted a fresh hearing per
     * row could never collide with anything.
     */
    private static String insertRegister(final UUID requestId, final UUID outputId,
            final String status, final String registerDate, final String extraColumns,
            final String extraValues) {
        return "INSERT INTO " + OUTPUT_TABLE
                + " (output_id, source, request_id, court_centre_id, register_date, file_name, "
                + "status, document, hearing_id, hearing_date, register_time, recorded_flag_state"
                + extraColumns + ") VALUES ('"
                + outputId + "', 'RESULTS', '" + requestId + "', '" + COURT_CENTRE + "', "
                + registerDate + ", 'yotresultsdistribution_2026-08-24.json', '" + status + "', "
                + "'{\"documentType\": \"CourtRegister\"}'::jsonb, '" + HEARING + "', "
                + "TIMESTAMPTZ '2026-08-19T09:00:00Z', TIMESTAMPTZ '2026-08-24T09:00:00Z', 'ON'"
                + extraValues + ")";
    }

    /** An active register for this suite's key on Monday. */
    private static String insertActiveRegister(final UUID requestId) {
        return insertRegister(requestId, UUID.randomUUID(), RECORDED, MONDAY, NOTHING, NOTHING);
    }

    /**
     * The smallest valid {@code register_batch} row, so a register can be stamped with one.
     */
    private static String insertBatch(final UUID batchId) {
        return "INSERT INTO register_batch (batch_id, court_centre_id, register_date, file_name, "
                + "status, system_generated) VALUES ('" + batchId + "', '" + COURT_CENTRE + "', "
                + MONDAY + ", 'yotresultsdistribution_2026-08-24.json', 'PENDING', true)";
    }

    /**
     * The row a V1 database holds for a hearing that was re-shared while the register was posted.
     *
     * <p>POSTED rather than RECORDED, and it carries a {@code hearing_id} because V2's backfill gave
     * every existing row one from its parent request.
     */
    private static String insertPostedRow(final UUID requestId) {
        return insertRegister(requestId, UUID.randomUUID(), "POSTED", MONDAY, NOTHING, NOTHING);
    }

    // --- the index -------------------------------------------------------------------------

    @Test
    @DisplayName("V3 makes one active register per hearing and day the database's own invariant")
    void active_register_index_should_be_unique_on_the_hearing_and_the_batch_key()
            throws SQLException {
        // The hearing first, because the hearing is what the key is about: a court centre and a day
        // are the batch's key and are already indexed for the nightly sweep
        // (idx_output_active_unbatched), while this one exists to say that one hearing appears on
        // that day's register exactly once.
        assertThat(indexesOf(OUTPUT_TABLE).get(INDEX))
                .isNotNull()
                .contains("CREATE UNIQUE INDEX")
                .contains("(hearing_id, court_centre_id, register_date)")
                .contains("'RECORDED'")
                .contains("superseded_at IS NULL")
                .contains("batch_id IS NULL");
    }

    @Test
    void a_second_active_register_for_one_hearing_and_day_should_be_refused() {
        // The whole point of the index. Two active rows for one hearing render the day with that
        // hearing on it twice, under two different sets of results, with nothing in the store
        // saying which of them the Youth Offending Team should believe.
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();

        assertThatThrownBy(() -> inRolledBackTransaction(
                insertValidRequest(first),
                insertValidRequest(second),
                insertActiveRegister(first),
                insertActiveRegister(second)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(INDEX);
    }

    @Test
    void a_superseded_register_should_not_hold_the_key_against_the_one_that_replaced_it() {
        // Partial on the status and the stamp, not total. The register a re-share replaced is kept
        // as the evidence of what was assembled before the results were shared again, so a total
        // unique constraint would refuse the second register of every re-shared hearing - which is
        // the ordinary case, and the reason there are two rows at all.
        final UUID replaced = UUID.randomUUID();
        final UUID reshared = UUID.randomUUID();
        final UUID replacedOutput = UUID.randomUUID();
        final UUID resharedOutput = UUID.randomUUID();

        assertThatCode(() -> inRolledBackTransaction(
                insertValidRequest(replaced),
                insertValidRequest(reshared),
                insertRegister(reshared, resharedOutput, RECORDED, MONDAY, NOTHING, NOTHING),
                insertRegister(replaced, replacedOutput, "SUPERSEDED", MONDAY,
                        ", superseded_at, superseded_by",
                        ", now(), '" + resharedOutput + "'")))
                .doesNotThrowAnyException();
    }

    @Test
    void a_batched_register_should_not_hold_the_key_against_the_hearings_next_one() {
        // The exclusion that is easy to miss and expensive to lose. A row carrying a batch is on
        // its way to a PDF - systemdocgenerator has been handed a payload built from it - so the
        // store leaves it exactly as the payload described it and records the re-share beside it.
        // Both rows are RECORDED and unsuperseded, and only the batch stamp tells them apart: an
        // index that ignored batch_id would refuse the re-share and abandon a command whose
        // register was perfectly good.
        final UUID batched = UUID.randomUUID();
        final UUID reshared = UUID.randomUUID();
        final UUID batchId = UUID.randomUUID();

        assertThatCode(() -> inRolledBackTransaction(
                insertValidRequest(batched),
                insertValidRequest(reshared),
                insertBatch(batchId),
                insertRegister(batched, UUID.randomUUID(), RECORDED, MONDAY, ", batch_id",
                        ", '" + batchId + "'"),
                insertActiveRegister(reshared)))
                .doesNotThrowAnyException();
    }

    @Test
    void the_same_hearing_sitting_on_another_day_should_be_a_register_of_its_own() {
        // The register day is in the key because two sittings of one hearing on two days are two
        // registers for two court days, and the second is not a correction of the first (P3).
        final UUID monday = UUID.randomUUID();
        final UUID tuesday = UUID.randomUUID();

        assertThatCode(() -> inRolledBackTransaction(
                insertValidRequest(monday),
                insertValidRequest(tuesday),
                insertActiveRegister(monday),
                insertRegister(tuesday, UUID.randomUUID(), RECORDED, TUESDAY, NOTHING, NOTHING)))
                .doesNotThrowAnyException();
    }

    @Test
    void two_posted_rows_for_one_hearing_should_not_be_held_to_the_register_key() {
        // The rollout case, and the reason the status predicate is not simply "not SUPERSEDED". A
        // V1 database holds one POSTED row per delivery, so a hearing re-shared before this
        // increment has two of them for one day - and V2's backfill gave both a hearing_id. An
        // index that admitted POSTED rows would refuse to build on any database that ever saw a
        // re-share, which is every database this migration will run against.
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();

        assertThatCode(() -> inRolledBackTransaction(
                insertValidRequest(first),
                insertValidRequest(second),
                insertPostedRow(first),
                insertPostedRow(second)))
                .doesNotThrowAnyException();
    }
}
