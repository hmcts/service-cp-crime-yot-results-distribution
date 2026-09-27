package uk.gov.hmcts.cp.yotresultsdistribution.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared Postgres fixture for the persistence and end-to-end suites.
 *
 * <p>One container per JVM, started on first use and left to the Ryuk reaper at exit, so the
 * several {@code *IT} suites that need a processed-log store pay the start-up cost once between
 * them rather than once each.
 *
 * <p>Production migration is deliberately off the context-refresh path (research §7: a no-op
 * {@code FlywayMigrationStrategy}, with the lifecycle controller migrating on the first successful
 * store probe). Persistence-slice suites therefore boot no controller and must migrate their own
 * container — {@link #applyFlyway()} is how they do it.
 */
public final class PostgresTestSupport {

    private static final String IMAGE = "postgres:16";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IMAGE)
            .withDatabaseName("yotresultsdistribution")
            .withUsername("yotresultsdistribution")
            .withPassword("yotresultsdistribution");

    private PostgresTestSupport() {
        // Static fixture holder.
    }

    /**
     * Returns the shared container, starting it if this is the first call.
     */
    public static PostgreSQLContainer container() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        return POSTGRES;
    }

    /**
     * The shared container's JDBC URL, starting it if it is not already running.
     */
    public static String jdbcUrl() {
        return container().getJdbcUrl();
    }

    /**
     * The shared container's username, starting it if it is not already running.
     */
    public static String username() {
        return container().getUsername();
    }

    /**
     * The shared container's password, starting it if it is not already running.
     */
    public static String password() {
        return container().getPassword();
    }

    /**
     * Applies the committed Flyway migrations to the shared container.
     *
     * <p>Idempotent: Flyway skips migrations already recorded in its schema history, so suites may
     * call this from every {@code @BeforeAll} without coordinating with each other.
     */
    public static void applyFlyway() {
        Flyway.configure()
                .dataSource(jdbcUrl(), username(), password())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /**
     * Creates an empty, unmigrated database inside the shared container and returns its JDBC URL.
     *
     * <p>For the one suite that has to watch a migration happen. Against the shared database, which
     * every other suite has already migrated, "the deferred migration ran" is unobservable — Flyway
     * would find its own history table and do nothing, and the assertion would pass whether the
     * migration was invoked or not. A database with nothing in it makes the question answerable.
     *
     * @param name the database to create; must be a plain identifier
     * @return the JDBC URL of the new, empty database
     */
    public static String createEmptyDatabase(final String name) {
        onTheServer(name, "CREATE DATABASE " + name);
        return urlFor(name);
    }

    /**
     * The JDBC URL of one database inside the shared container.
     *
     * @param name the database; must be a plain identifier
     * @return its JDBC URL
     */
    public static String urlFor(final String name) {
        requirePlainIdentifier(name);
        return "jdbc:postgresql://" + container().getHost() + ':'
                + container().getFirstMappedPort() + '/' + name;
    }

    /**
     * Opens and closes one connection to a database inside the shared container.
     *
     * <p>For a suite that has to show an outage it staged is a real one. Nothing is caught: however
     * the server refused is the answer, and a fixture that turned a refusal into a boolean would
     * leave a case asserting that something went wrong without saying what.
     *
     * @param name the database to connect to
     * @throws SQLException however the server refused
     */
    public static void connectTo(final String name) throws SQLException {
        DriverManager.getConnection(urlFor(name), username(), password()).close();
    }

    /**
     * Refuses every connection to one database in the shared container, and severs the ones it has.
     *
     * <p>The narrowest outage this container allows, and it exists because {@link #pause()} is too
     * wide for one question. There is one Postgres per JVM, so the file-service database and the
     * processed log are in the same server: a suite that froze the container to take the file
     * service away would take the processed log with it, and readiness would then be DOWN for the
     * wrong reason. From a pool's point of view this is the same outage - the server is there and
     * will not let it in, which is what a database taken out of service looks like to a client -
     * and it reaches exactly one of the two databases.
     *
     * <p>Two statements, because either alone leaves the outage half-made. The first refuses what
     * comes next; the second ends what is already open, and a pool holding an idle connection would
     * otherwise answer a health probe over it quite happily.
     *
     * @param name the database to take out of service
     */
    public static void refuseConnectionsTo(final String name) {
        onTheServer(name,
                "ALTER DATABASE " + name + " WITH ALLOW_CONNECTIONS false",
                // The name is a validated plain identifier, so it cannot carry a quote and this
                // literal cannot be anything but a database name.
                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '"
                        + name + '\'');
    }

    /**
     * Removes one database from the shared container, severing whatever is still attached to it.
     *
     * <p>The counterpart of {@link #createEmptyDatabase(String)}, for the suites that take a
     * database of their own. Without it the name survives its suite for the life of the JVM, and a
     * second {@code CREATE DATABASE} under the same name - a suite loaded twice, a retried run -
     * fails in a {@code @BeforeAll} rather than in a case, which is a fixture error where an
     * assertion should be.
     *
     * <p>Idempotent, and it refuses connections first: {@code DROP DATABASE} is refused while
     * anything is attached, and an {@code @AfterAll} that threw would replace whatever the suite
     * really did with a teardown failure.
     *
     * @param name the database to remove
     */
    public static void dropDatabase(final String name) {
        onTheServer(name,
                "ALTER DATABASE " + name + " WITH ALLOW_CONNECTIONS false",
                // The name is a validated plain identifier, so it cannot carry a quote and this
                // literal cannot be anything but a database name.
                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '"
                        + name + '\'',
                "DROP DATABASE IF EXISTS " + name);
    }

    /**
     * Lets connections back into a database, whether or not they were refused.
     *
     * <p>Idempotent deliberately, for the reason {@link #unpause()} is: the suites that stage this
     * outage undo it from an {@code @AfterEach}, so a failing assertion cannot leave the rest of
     * the build talking to a database that will not answer.
     *
     * @param name the database to put back in service
     */
    public static void allowConnectionsTo(final String name) {
        onTheServer(name, "ALTER DATABASE " + name + " WITH ALLOW_CONNECTIONS true");
    }

    /**
     * Runs statements against the container's own database, which is never the one being altered.
     *
     * @param name       the database the statements are about, validated before any of them runs
     * @param statements the statements, in order
     */
    private static void onTheServer(final String name, final String... statements) {
        requirePlainIdentifier(name);
        try (Connection connection = DriverManager.getConnection(jdbcUrl(), username(), password());
             Statement statement = connection.createStatement()) {
            for (final String sql : statements) {
                statement.execute(sql);
            }
        } catch (SQLException failed) {
            throw new IllegalStateException("could not administer the database " + name, failed);
        }
    }

    private static void requirePlainIdentifier(final String name) {
        if (!name.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("not a plain database identifier: " + name);
        }
    }

    /**
     * Freezes the database process, severing every open connection without touching the volume.
     *
     * <p>This is how the store-outage suites produce an outage: the driver sees the connections
     * die, and {@link #unpause()} brings the same data back. Toxiproxy stays the recorded fallback
     * should pausing ever report a misleading error class (plan §Test matrix).
     */
    public static void pause() {
        container().getDockerClient()
                .pauseContainerCmd(container().getContainerId())
                .exec();
    }

    /**
     * Thaws a container frozen by {@link #pause()}, whether or not it is frozen.
     *
     * <p>Idempotent deliberately. The outage suites thaw the store from an {@code @AfterEach} so
     * that a failing assertion cannot leave the rest of the build running against a frozen
     * database; Docker refuses an unpause of a running container with a 500, and that refusal would
     * replace the assertion the suite actually failed on with a fixture error nobody can read.
     */
    public static void unpause() {
        if (paused()) {
            container().getDockerClient()
                    .unpauseContainerCmd(container().getContainerId())
                    .exec();
        }
    }

    private static boolean paused() {
        return Boolean.TRUE.equals(container().getDockerClient()
                .inspectContainerCmd(container().getContainerId())
                .exec()
                .getState()
                .getPaused());
    }
}
