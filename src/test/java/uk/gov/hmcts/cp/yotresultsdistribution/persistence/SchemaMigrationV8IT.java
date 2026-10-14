package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;

/**
 * Pins the one constraint V8 rewrites, and pins that it rewrites nothing else.
 *
 * <p>V8 widens {@code register_batch_failure_reason_chk} by one value so
 * {@code RegisterGenerationJob.requested()} can fail a batch REQUEST_UNFINISHED and release its
 * registers at the moment its own call raises something no classified failure names, rather than
 * leaving it PENDING for a later stale-batch pass to find (review of #31, fix 3, part 2).
 * {@code SchemaMigrationV2IT} holds what the new vocabulary admits and refuses; this suite holds
 * the migration's <em>footprint</em>, exactly as {@link SchemaMigrationV6IT} holds V6's: that a
 * migration whose whole subject is one CHECK list did not also take a column, a table or an index
 * with it, and did not disturb the other constraints of the table it opened.
 *
 * <p>The suite migrates a database of its own to V7, takes the schema down, migrates it as far as
 * V8 and takes it down again - the arrangement {@link SchemaMigrationV4IT} explains.
 *
 * <p><strong>Both ends are pinned</strong>, to {@code target("7")} and {@code target("8")} rather
 * than to the head, for the reason {@link SchemaMigrationV6IT} states: a suite left at the head
 * states what one migration did only while that migration is the last one.
 */
class SchemaMigrationV8IT {

    private static final String DATABASE = "yotresultsdistribution_v8_vocabulary";

    /** The one constraint V8 exists to rewrite, named as {@link #constraints()} spells it. */
    private static final String FAILURE_REASON_CHECK =
            "register_batch.register_batch_failure_reason_chk";

    /** The seventh value, and the whole of the difference between the two definitions. */
    private static final String NEW_REASON = "REQUEST_UNFINISHED";

    /** The six V7 left standing, every one of which V8 must still admit: it widens only. */
    private static final List<String> THE_SIX = List.of(
            "PAYLOAD_STORE_UNAVAILABLE", "RENDER_REQUEST_FAILED", "RENDER_REQUEST_REJECTED",
            "GENERATION_FAILED", "ASSEMBLY_FAILED", "NOT_COMPLETED_BY_NEXT_RUN");

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
    static void migrateAcrossV8() throws SQLException {
        jdbcUrl = PostgresTestSupport.createEmptyDatabase(DATABASE);

        flyway().target("7").load().migrate();
        tablesBefore = tables();
        columnsBefore = columns();
        constraintsBefore = constraints();
        indexesBefore = indexes();

        flyway().target("8").load().migrate();
        tablesAfter = tables();
        columnsAfter = columns();
        constraintsAfter = constraints();
        indexesAfter = indexes();
    }

    // --- the one constraint -----------------------------------------------------------------

    @Test
    @DisplayName("V8 should rewrite exactly one constraint, and it is the failure-reason list")
    void v8_should_rewrite_only_the_failure_reason_check() {
        assertThat(onlyAfter())
                .as("one definition appeared: the widened list, and nothing beside it")
                .hasSize(1)
                .allSatisfy(definition -> assertThat(definition).startsWith(FAILURE_REASON_CHECK));
        assertThat(onlyBefore())
                .as("and one disappeared: the six-value list the same constraint used to hold - a "
                        + "DROP and an ADD on a table of eight constraints is the shape of edit "
                        + "that loses a neighbour")
                .hasSize(1)
                .allSatisfy(definition -> assertThat(definition).startsWith(FAILURE_REASON_CHECK));
    }

    @Test
    @DisplayName("every other constraint on the store survives V8 byte-identical")
    void v8_should_leave_every_other_constraint_untouched() {
        assertThat(everyConstraintBut(FAILURE_REASON_CHECK, constraintsAfter))
                .isEqualTo(everyConstraintBut(FAILURE_REASON_CHECK, constraintsBefore));
    }

    @Test
    @DisplayName("the widened list is the six plus the new reason, and nothing else")
    void v8_should_admit_the_six_and_the_new_reason() {
        final String widened = onlyAfter().iterator().next();

        assertThat(widened)
                .as("it widens only: every reason V7 left standing must still be admitted")
                .contains(THE_SIX)
                .contains(NEW_REASON);
        assertThat(onlyBefore().iterator().next())
                .as("and the definition it replaced named the six and not the seventh, which is "
                        + "what made this migration necessary")
                .contains(THE_SIX)
                .doesNotContain(NEW_REASON);
    }

    // --- and nothing else -------------------------------------------------------------------

    @Test
    @DisplayName("V8 should add no column, table or index")
    void v8_should_add_no_column_table_or_index() {
        assertThat(tablesAfter).isEqualTo(tablesBefore);
        assertThat(columnsAfter).isEqualTo(columnsBefore);
        assertThat(indexesAfter).isEqualTo(indexesBefore);
    }

    // --- helpers ----------------------------------------------------------------------------

    /** One snapshot without the named constraint's definition, whatever that definition says. */
    private static Set<String> everyConstraintBut(final String name, final Set<String> snapshot) {
        final Set<String> rest = new LinkedHashSet<>();
        for (final String definition : snapshot) {
            if (!definition.startsWith(name)) {
                rest.add(definition);
            }
        }
        return rest;
    }

    /** The definitions V8 introduced: present after the migration and absent before it. */
    private static Set<String> onlyAfter() {
        final Set<String> difference = new LinkedHashSet<>(constraintsAfter);
        difference.removeAll(constraintsBefore);
        return difference;
    }

    /** And the definitions it took away. */
    private static Set<String> onlyBefore() {
        final Set<String> difference = new LinkedHashSet<>(constraintsBefore);
        difference.removeAll(constraintsAfter);
        return difference;
    }

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
