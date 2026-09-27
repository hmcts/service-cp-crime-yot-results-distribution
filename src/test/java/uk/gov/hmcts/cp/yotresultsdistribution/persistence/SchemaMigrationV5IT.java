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
 * Pins the five indexes V5 adds, and pins that it adds nothing else.
 *
 * <p>V4 gave the report's two intake reads an index each and left the other five reads scanning.
 * That was defensible while the batch tables were a night old; it is not defensible against a table
 * that grows by every court centre's day for ever, and the four batch reads and the notification
 * read all run on a schedule - every weekday morning, at the moment a support engineer is waiting
 * for the answer. Progression's own defect P8 is what an unindexed register table costs.
 *
 * <p><strong>All five are partial</strong>, and each one's predicate is spelled exactly as the
 * statement that uses it spells it: Postgres matches a partial index by proving the query's
 * predicate implies the index's, and the proof it does cheaply is the syntactic one. The rows each
 * serves are the handful in one state, so a total index on the timestamp would be the size of the
 * table and would be scanned past every terminal row in it.
 *
 * <p>The suite migrates a database of its own to V4, takes the schema down, migrates it as far as
 * V5 and takes it down again - the arrangement {@link SchemaMigrationV4IT} explains: against the
 * shared, already-migrated container "V5 changed nothing" is unobservable, and against the head it
 * would be a claim about every migration that has landed since.
 *
 * <p>Whether the reads the indexes exist for actually reach them is
 * {@code RegisterBatchReportReadsIT}'s and {@code RegisterNotificationReportReadsIT}'s, over
 * {@code EXPLAIN}. This suite is about the definitions; those are about the plans.
 */
class SchemaMigrationV5IT {

    private static final String DATABASE = "yotresultsdistribution_v5_indexes";

    private static final String BATCH_TABLE = "register_batch";

    private static final String NOTIFICATION_TABLE = "register_notification";

    /** The four batch reads' indexes, in the order the report makes the reads. */
    private static final String LATE_PENDING_INDEX = "idx_batch_late_pending";

    private static final String LATE_GENERATING_INDEX = "idx_batch_late_generating";

    private static final String LATE_GENERATED_INDEX = "idx_batch_late_generated";

    private static final String FAILED_INDEX = "idx_batch_failed_at";

    /** And the notification read's. */
    private static final String NOTIFICATION_FAILED_INDEX = "idx_notification_failed_sent";

    /** How many indexes V5 adds, stated once here and asserted against the two snapshots. */
    private static final int FIVE = 5;

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
    static void migrateAcrossV5() throws SQLException {
        jdbcUrl = PostgresTestSupport.createEmptyDatabase(DATABASE);

        flyway().target("4").load().migrate();
        tablesBefore = tables();
        columnsBefore = columns();
        constraintsBefore = constraints();
        indexesBefore = indexes();

        // Targeted at V5 and not at the head, for the reason SchemaMigrationV4IT gives at the same
        // line: this suite's claim is what ONE migration did, so a migration that lands after it
        // must not be counted as part of it. Left at the head it was right only while V5 was the
        // last migration, and V6 turned "V5 added five indexes and changed no constraint" into a
        // failure about a constraint V6 widened.
        flyway().target("5").load().migrate();
        tablesAfter = tables();
        columnsAfter = columns();
        constraintsAfter = constraints();
        indexesAfter = indexes();
    }

    // --- the five indexes -------------------------------------------------------------------

    @Test
    @DisplayName("V5 adds the five indexes the downstream report reads are made through")
    void every_downstream_report_index_exists() {
        assertThat(indexesAfter)
                .as("four batch reads and one notification read, each with an index of its own: "
                        + "they are made on a schedule, and the morning they are slowest is the "
                        + "morning somebody is waiting for the answer")
                .containsKeys(LATE_PENDING_INDEX, LATE_GENERATING_INDEX, LATE_GENERATED_INDEX,
                        FAILED_INDEX, NOTIFICATION_FAILED_INDEX);
    }

    @Test
    @DisplayName("each batch index is on the stage timestamp its own read orders by")
    void every_batch_index_is_on_its_own_stage_column() {
        assertThat(indexesAfter.get(LATE_PENDING_INDEX))
                .isNotNull()
                .contains("ON public." + BATCH_TABLE)
                .as("assembly, which is the moment a batch nothing has been asked about started "
                        + "waiting - and the column its read orders by")
                .contains("(assembled_at)");
        assertThat(indexesAfter.get(LATE_GENERATING_INDEX))
                .isNotNull()
                .contains("ON public." + BATCH_TABLE)
                .contains("(requested_at)");
        assertThat(indexesAfter.get(LATE_GENERATED_INDEX))
                .isNotNull()
                .contains("ON public." + BATCH_TABLE)
                .contains("(generated_at)");
        assertThat(indexesAfter.get(FAILED_INDEX))
                .isNotNull()
                .contains("ON public." + BATCH_TABLE)
                .contains("(failed_at)");
        assertThat(indexesAfter.get(NOTIFICATION_FAILED_INDEX))
                .isNotNull()
                .contains("ON public." + NOTIFICATION_TABLE)
                .contains("(sent_at)");
    }

    /**
     * The predicate is the whole of a partial index's usefulness.
     *
     * <p>The same argument {@link SchemaMigrationV4IT} makes about the intake half's partial index:
     * Postgres proves the implication syntactically, so the migration writes {@code status = 'X'}
     * because that is exactly how each read spells its own {@code WHERE} clause. A differently
     * spelled equivalent is a planner coin toss whose side changes with the table's statistics
     * rather than with anything in this repository.
     */
    @Test
    @DisplayName("every one of the five is partial on the state its read asks about")
    void every_index_carries_the_state_its_read_asks_about() {
        assertThat(indexesAfter.get(LATE_PENDING_INDEX)).isNotNull()
                .contains("WHERE").contains("'PENDING'");
        assertThat(indexesAfter.get(LATE_GENERATING_INDEX)).isNotNull()
                .contains("WHERE").contains("'GENERATING'");
        assertThat(indexesAfter.get(LATE_GENERATED_INDEX)).isNotNull()
                .contains("WHERE").contains("'GENERATED'");
        assertThat(indexesAfter.get(FAILED_INDEX)).isNotNull()
                .contains("WHERE").contains("'FAILED'");
        assertThat(indexesAfter.get(NOTIFICATION_FAILED_INDEX)).isNotNull()
                .contains("WHERE").contains("'FAILED'");
    }

    // --- and nothing else -------------------------------------------------------------------

    @Test
    @DisplayName("V5 adds five indexes and no column, constraint or table")
    void v5_adds_no_column_constraint_or_table() {
        // Additive and forward-only is a promise about what a migration does to a database already
        // carrying a week of production data. An index can be built on such a database; a column, a
        // constraint or a table is a different conversation and a different release.
        assertThat(tablesAfter).isEqualTo(tablesBefore);
        assertThat(columnsAfter).isEqualTo(columnsBefore);
        assertThat(constraintsAfter).isEqualTo(constraintsBefore);
    }

    @Test
    @DisplayName("every V1 to V4 object survives V5 exactly as it was")
    void every_v1_to_v4_object_is_unchanged() {
        assertThat(indexesAfter).containsAllEntriesOf(indexesBefore);
        assertThat(indexesAfter)
                .as("exactly the five new ones, and nothing else appeared alongside them")
                .hasSize(indexesBefore.size() + FIVE);
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
