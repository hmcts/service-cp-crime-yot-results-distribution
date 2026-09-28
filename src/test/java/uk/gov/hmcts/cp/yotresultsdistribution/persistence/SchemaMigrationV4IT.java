package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;

/**
 * Pins the two indexes V4 adds, and pins that it adds nothing else.
 *
 * <p>Both of them serve reads this service did not used to make on a schedule. The intake sweep's
 * read runs every gauge-refresh interval for the life of every pod, and the morning report's
 * failed-since read runs over whatever a week of deliveries left behind - neither is a support
 * engineer with time to wait, and a sequential scan that is free on a developer's table is the
 * production cost nobody sees until the table is a production one.
 *
 * <p>The suite migrates a database of its own to V3, takes the schema down, migrates it the rest of
 * the way and takes it down again. Against the shared, already-migrated container "V4 changed
 * nothing" is unobservable: Flyway would find its own history and do nothing, and the assertion
 * would pass whether the migration was additive or not. Two snapshots of one database, either side
 * of one migration, is what makes the question answerable.
 *
 * <p>Whether the reads the indexes exist for actually reach them is
 * {@code ProcessedRequestReportReadsIT}'s {@code both_scheduled_reads_use_the_v4_indexes}, over
 * {@code EXPLAIN}. This suite is about the definitions; that one is about the plans.
 */
class SchemaMigrationV4IT {

    private static final String DATABASE = "yotresultsdistribution_v4_indexes";

    private static final String REQUEST_TABLE = "processed_request";

    /** The partial index: the rows still in flight, oldest first. */
    private static final String NON_TERMINAL_INDEX = "idx_request_non_terminal_created";

    /** The total index: the window boundary on the state a row reached. */
    private static final String STATUS_UPDATED_INDEX = "idx_request_status_updated";

    private static String jdbcUrl;

    private static Set<String> tablesBefore;
    private static Set<String> tablesAfter;
    private static Set<String> columnsBefore;
    private static Set<String> columnsAfter;
    private static Set<String> constraintsBefore;
    private static Set<String> constraintsAfter;
    private static Map<String, String> indexesBefore;
    private static Map<String, String> indexesAfter;

    @BeforeAll
    static void migrateAcrossV4() throws SQLException {
        jdbcUrl = PostgresTestSupport.createEmptyDatabase(DATABASE);

        flyway().target("3").load().migrate();
        tablesBefore = tables();
        columnsBefore = columns();
        constraintsBefore = constraints();
        indexesBefore = indexes();

        // Targeted at V4 and not at the head. This suite's claim is what ONE migration did, and a
        // migration that arrives after it would otherwise be counted as part of it - which is how
        // "V4 added exactly two indexes" quietly becomes "the migrations after V3 added seven".
        flyway().target("4").load().migrate();
        tablesAfter = tables();
        columnsAfter = columns();
        constraintsAfter = constraints();
        indexesAfter = indexes();
    }

    // --- the two indexes --------------------------------------------------------------------

    @Test
    @DisplayName("V4 adds the two indexes the scheduled reads are made through")
    void both_report_indexes_exist() {
        assertThat(indexesAfter)
                .as("the sweep's read and the report's failed-since read, each with its own index")
                .containsKeys(NON_TERMINAL_INDEX, STATUS_UPDATED_INDEX);

        assertThat(indexesAfter.get(NON_TERMINAL_INDEX))
                .isNotNull()
                .contains("CREATE INDEX " + NON_TERMINAL_INDEX)
                .contains("ON public." + REQUEST_TABLE)
                .contains("(created_at)");

        assertThat(indexesAfter.get(STATUS_UPDATED_INDEX))
                .isNotNull()
                .contains("CREATE INDEX " + STATUS_UPDATED_INDEX)
                .contains("ON public." + REQUEST_TABLE)
                .contains("(status, updated_at)")
                .as("not partial: it serves every status, and the window boundary is on updated_at")
                .doesNotContain("WHERE");
    }

    /**
     * The predicate is the whole of a partial index's usefulness.
     *
     * <p>Postgres matches a partial index by proving that the query's own predicate implies the
     * index's, and the proof it can do cheaply is the syntactic one. The migration writes
     * {@code status IN ('RECEIVED', 'RETRYING')} because that is exactly how the sweep's read and
     * the report's REQUEST_LATE read spell their own {@code WHERE} clause; Postgres normalises both
     * to the same {@code = ANY (ARRAY[...])} form, and the implication is then trivial. A
     * differently spelled equivalent - a pair of {@code OR}s, a different order, a cast - is a
     * planner coin toss, and the side it lands on changes with the table's statistics rather than
     * with anything in this repository.
     */
    @Test
    @DisplayName("the partial index is spelled the way the queries that use it are spelled")
    void the_non_terminal_index_carries_its_predicate() {
        assertThat(indexesAfter.get(NON_TERMINAL_INDEX))
                .isNotNull()
                .contains("WHERE")
                .contains("status = ANY")
                .contains("'RECEIVED'")
                .contains("'RETRYING'")
                .as("the two terminal states are outside it: they are the whole table, and the rows"
                        + " this index serves are the handful still in flight")
                .doesNotContain("'COMPLETED'")
                .doesNotContain("'FAILED'");
    }

    // --- and nothing else -------------------------------------------------------------------

    @Test
    @DisplayName("V4 adds two indexes and no column, constraint or table")
    void v4_adds_no_column_constraint_or_table() {
        // Additive and forward-only is a promise about what a migration does to a database that is
        // already carrying a week of production data. An index can be built on such a database; a
        // column, a constraint or a table is a different conversation and a different release.
        assertThat(tablesAfter).isEqualTo(tablesBefore);
        assertThat(columnsAfter).isEqualTo(columnsBefore);
        assertThat(constraintsAfter).isEqualTo(constraintsBefore);
    }

    @Test
    @DisplayName("every V1 to V3 object survives V4 exactly as it was")
    void every_v1_to_v3_object_is_unchanged() {
        // Not the same claim as the one above. That one says nothing was added; this one says
        // nothing that was already there was altered or dropped - including the three indexes the
        // batch half sweeps on and the unique one that keeps a hearing off a register twice.
        assertThat(indexesAfter).containsAllEntriesOf(indexesBefore);
        assertThat(indexesAfter)
                .as("exactly the two new ones, and nothing else appeared alongside them")
                .hasSize(indexesBefore.size() + 2);
    }

    // --- helpers ----------------------------------------------------------------------------

    private static FluentConfiguration flyway() {
        return Flyway.configure()
                .dataSource(jdbcUrl, PostgresTestSupport.username(), PostgresTestSupport.password())
                .locations("classpath:db/migration");
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                jdbcUrl, PostgresTestSupport.username(), PostgresTestSupport.password());
    }

    private static Set<String> tables() throws SQLException {
        return rowsOf("SELECT tablename FROM pg_tables WHERE schemaname = 'public'");
    }

    private static Set<String> columns() throws SQLException {
        return rowsOf("""
                SELECT table_name || '.' || column_name || ' ' || data_type
                       || ' null=' || is_nullable
                       || ' default=' || COALESCE(column_default, '-')
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                """);
    }

    private static Set<String> constraints() throws SQLException {
        return rowsOf("""
                SELECT conrelid::regclass || '.' || conname || ' ' || pg_get_constraintdef(oid)
                  FROM pg_constraint
                 WHERE connamespace = 'public'::regnamespace
                """);
    }

    private static Set<String> rowsOf(final String sql) throws SQLException {
        final Set<String> rows = new LinkedHashSet<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet answers = statement.executeQuery(sql)) {
            while (answers.next()) {
                rows.add(answers.getString(1));
            }
        }
        return rows;
    }

    private static Map<String, String> indexes() throws SQLException {
        final Map<String, String> found = new LinkedHashMap<>();
        final String sql = "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public'";
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet answers = statement.executeQuery(sql)) {
            while (answers.next()) {
                found.put(answers.getString("indexname"), answers.getString("indexdef"));
            }
        }
        return found;
    }
}
