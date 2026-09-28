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
 * Pins the three constraints V7 rewrites, and pins that it rewrites nothing else.
 *
 * <p>V7 narrows the two vocabularies the grace-period reconciler wrote - the failure reason
 * {@code GENERATION_TIMED_OUT} and the attribution {@code RECONCILER} - and narrows the shape
 * constraint that names the attributed reasons with them. {@link SchemaMigrationV2IT} holds what
 * the narrowed vocabulary admits and refuses, and holds the operational caveat that the migration
 * refuses to apply over a violating row; this suite holds the migration's <em>footprint</em>.
 *
 * <p>That footprint is the question V6 could not be asked. V6 was one {@code DROP CONSTRAINT} and
 * one {@code ADD CONSTRAINT}; V7 is three of each on a table carrying eight constraints, and the
 * third of them rewrites a five-clause boolean whose arms cover all seven batch statuses. Three
 * paired edits are three chances to lose a neighbour, and rewriting a constraint by hand is three
 * chances to drop an arm of it while the value being removed is the only thing anyone is reading.
 * "Exactly these three definitions changed, and every other constraint is character-for-character
 * what V1 to V6 wrote" is the assertion that catches that, and no other suite makes it.
 *
 * <p>The suite migrates a database of its own to V6, takes the schema down, migrates it as far as
 * V7 and takes it down again - the arrangement {@link SchemaMigrationV4IT} explains. Against the
 * shared, already-migrated container "V7 changed nothing else" is unobservable, because Flyway
 * would find its own history and do nothing.
 *
 * <p><strong>Both ends are pinned</strong>, to {@code target("6")} and {@code target("7")} rather
 * than to the head, for the reason {@link SchemaMigrationV6IT} gives: a suite left at the head
 * states what one migration did only while that migration is the last one, and {@link
 * SchemaMigrationV5IT} became a claim about V6 the night V6 landed. A V8 that touched this table
 * finds this suite already saying what it meant to say.
 */
class SchemaMigrationV7IT {

    private static final String DATABASE = "yotresultsdistribution_v7_vocabulary";

    /** The failure vocabulary, named as {@link #constraints()} spells it. */
    private static final String FAILURE_REASON_CHECK =
            "register_batch.register_batch_failure_reason_chk";

    /** The attribution vocabulary. */
    private static final String COMPLETED_BY_CHECK =
            "register_batch.register_batch_completed_by_chk";

    /** And the shape that says which states and which reasons carry an attribution. */
    private static final String COMPLETED_BY_SHAPE_CHECK =
            "register_batch.register_batch_completed_by_shape_chk";

    /** Exactly the three V7 exists to rewrite, and the whole of the difference it may make. */
    private static final List<String> THE_THREE =
            List.of(FAILURE_REASON_CHECK, COMPLETED_BY_CHECK, COMPLETED_BY_SHAPE_CHECK);

    /** The reason that leaves: the reconciler's verdict about a renderer's silence. */
    private static final String RETIRED_REASON = "GENERATION_TIMED_OUT";

    /** And the attribution that leaves with it: the mechanism that reached that verdict. */
    private static final String RETIRED_ATTRIBUTION = "RECONCILER";

    /** The six reasons V7 must still admit; it narrows by one and no further. */
    private static final List<String> THE_SIX_THAT_STAY = List.of(
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
    static void migrateAcrossV7() throws SQLException {
        jdbcUrl = PostgresTestSupport.createEmptyDatabase(DATABASE);

        flyway().target("6").load().migrate();
        tablesBefore = tables();
        columnsBefore = columns();
        constraintsBefore = constraints();
        indexesBefore = indexes();

        flyway().target("7").load().migrate();
        tablesAfter = tables();
        columnsAfter = columns();
        constraintsAfter = constraints();
        indexesAfter = indexes();
    }

    // --- the three constraints --------------------------------------------------------------

    @Test
    @DisplayName("V7 should rewrite exactly three constraints, and they are the two vocabularies "
            + "and the shape")
    void v7_should_rewrite_only_the_three_named_constraints() {
        assertThat(onlyAfter())
                .as("three definitions appeared: the narrowed reason list, the narrowed "
                        + "attribution and the shape that names the attributed reasons - and "
                        + "nothing beside them")
                .hasSize(3)
                .allSatisfy(definition -> assertThat(THE_THREE)
                        .anyMatch(definition::startsWith));
        assertThat(onlyBefore())
                .as("and three disappeared: the same three constraints as V6 left them - three "
                        + "DROP-and-ADD pairs on a table of eight constraints is three chances to "
                        + "lose a neighbour")
                .hasSize(3)
                .allSatisfy(definition -> assertThat(THE_THREE)
                        .anyMatch(definition::startsWith));
    }

    @Test
    @DisplayName("every other constraint on the store survives V7 byte-identical")
    void v7_should_leave_every_other_constraint_untouched() {
        // The stronger statement, made the way SchemaMigrationV6IT makes it: the set subtracted is
        // the three constraints V7 may rewrite, found by name, and deliberately not "whatever the
        // diff turned out to be". Subtracting the diff from both sides would leave two sets equal
        // by construction, and the assertion could then never fail on anything the count above had
        // not already caught. Found by name, it fails if V7 takes a fourth definition with it -
        // including the primary keys, the foreign keys back to register_record and the status
        // check on the very table V7 opens three times.
        assertThat(everyConstraintBut(THE_THREE, constraintsAfter))
                .isEqualTo(everyConstraintBut(THE_THREE, constraintsBefore));
    }

    @Test
    @DisplayName("the narrowed reason list is the six that stay, without the reconciler's verdict")
    void v7_should_admit_the_six_that_stay_and_refuse_the_retired_reason() {
        final String narrowed = definitionStartingWith(onlyAfter(), FAILURE_REASON_CHECK);

        assertThat(narrowed)
                .as("it narrows by exactly one: every reason something still writes is still "
                        + "admitted, the stale-batch pass's ending among them")
                .contains(THE_SIX_THAT_STAY)
                .doesNotContain(RETIRED_REASON);
        assertThat(definitionStartingWith(onlyBefore(), FAILURE_REASON_CHECK))
                .as("and the definition it replaced named the retired reason, which is what made "
                        + "this migration necessary")
                .contains(RETIRED_REASON);
    }

    @Test
    @DisplayName("the narrowed attribution is EVENT alone, and the shape stops naming the "
            + "retired reason")
    void v7_should_narrow_the_attribution_and_the_shape_with_it() {
        assertThat(definitionStartingWith(onlyAfter(), COMPLETED_BY_CHECK))
                .as("one mechanism delivers an outcome now, and the column may name no other")
                .contains("EVENT")
                .doesNotContain(RETIRED_ATTRIBUTION);
        assertThat(definitionStartingWith(onlyAfter(), COMPLETED_BY_SHAPE_CHECK))
                .as("the shape's attributed arm narrows with the vocabulary: GENERATION_FAILED is "
                        + "the one reason somebody outside this service still reports")
                .contains("GENERATION_FAILED")
                .doesNotContain(RETIRED_REASON);
    }

    // --- and nothing else -------------------------------------------------------------------

    @Test
    @DisplayName("V7 should add or drop no column, table or index")
    void v7_should_add_or_drop_no_column_table_or_index() {
        // Additive and forward-only in the Flyway sense is a promise about what a migration does to
        // a database already carrying a week of data. Swapping three CHECK constraints reads the
        // table once and takes no row away; a column, a table or an index is a different
        // conversation, a different release, and a different amount of time holding a lock. Equal
        // sets state both directions at once - nothing gained, and nothing lost either.
        assertThat(tablesAfter).isEqualTo(tablesBefore);
        assertThat(columnsAfter).isEqualTo(columnsBefore);
        assertThat(indexesAfter).isEqualTo(indexesBefore);
    }

    // --- helpers ----------------------------------------------------------------------------

    /** The one definition in a snapshot that belongs to the named constraint. */
    private static String definitionStartingWith(final Set<String> snapshot, final String name) {
        return snapshot.stream()
                .filter(definition -> definition.startsWith(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no definition of " + name + " among " + snapshot));
    }

    /** One snapshot without the named constraints' definitions, whatever those definitions say. */
    private static Set<String> everyConstraintBut(final List<String> names,
            final Set<String> snapshot) {

        final Set<String> rest = new LinkedHashSet<>();
        for (final String definition : snapshot) {
            if (names.stream().noneMatch(definition::startsWith)) {
                rest.add(definition);
            }
        }
        return rest;
    }

    /** The definitions V7 introduced: present after the migration and absent before it. */
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
