package uk.gov.hmcts.cp.yotresultsdistribution.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A migrated database of one suite's own inside the shared container, and a record of every
 * statement executed against it.
 *
 * <p>The report's reads are asserted with "and nothing else" attached to almost every one of them:
 * a window that returns one row, a cut-off that excludes the terminal states, an empty table that
 * answers empty. None of those is observable against the container the other persistence suites
 * share, where the answer to "what else is in the table" is whatever ran first. A database per
 * suite makes the claim exact, and it is the same reasoning {@code SchemaMigrationV4IT} already
 * takes a database of its own for.
 *
 * <p><strong>Every statement is recorded, and that is a second question this fixture answers.</strong>
 * Two of the report's reads carry a claim about their <em>select list</em> - no
 * {@code email_address}, no {@code sdg_reason} - and one carries a claim about how many statements
 * it takes. Neither can be asserted from the rows that come back: a column that is never selected
 * leaves no trace in a projection that has no component for it, and one statement and thirty
 * produce the same list. So the connection is proxied and the SQL each statement is prepared with
 * is kept, which is also what lets a case run {@code EXPLAIN} over the statement the repository
 * really ran rather than over a second copy of it spelled by the test.
 */
public final class ReportReadsDatabase {

    private final String databaseName;
    private final String url;
    private final List<String> executed = new CopyOnWriteArrayList<>();
    private final DataSource dataSource;
    private final JdbcClient client;
    private final TransactionOperations transactionTemplate;
    private final PlatformTransactionManager platformTransactionManager;

    private ReportReadsDatabase(final String name, final String url) {
        this.databaseName = name;
        this.url = url;
        final DriverManagerDataSource driver = new DriverManagerDataSource(url,
                PostgresTestSupport.username(), PostgresTestSupport.password());
        this.dataSource = new RecordingDataSource(driver);
        this.client = JdbcClient.create(dataSource);
        this.platformTransactionManager = new JdbcTransactionManager(dataSource);
        this.transactionTemplate = new TransactionTemplate(platformTransactionManager);
    }

    /**
     * Creates an empty database of the given name in the shared container and migrates it.
     *
     * @param name the database; a plain identifier, so one suite's name cannot be another's
     * @return the fixture over it
     */
    public static ReportReadsDatabase migrated(final String name) {
        final String url = PostgresTestSupport.createEmptyDatabase(name);
        Flyway.configure()
                .dataSource(url, PostgresTestSupport.username(), PostgresTestSupport.password())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return new ReportReadsDatabase(name, url);
    }

    /**
     * Removes this database from the container, so the name is free again.
     *
     * <p>Called from each suite's {@code @AfterAll}. One JVM runs all four of these suites, and a
     * database that outlived its suite would make a second {@code migrated(...)} under the same
     * name - a class loaded twice, a retried run - fail where no case can report it.
     */
    public void drop() {
        PostgresTestSupport.dropDatabase(databaseName);
    }

    /**
     * The database's name, for the outage a case stages against it.
     *
     * @return the database name
     */
    public String name() {
        return databaseName;
    }

    /**
     * A client over this database, recording every statement it prepares.
     *
     * @return the client
     */
    public JdbcClient jdbcClient() {
        return client;
    }

    /**
     * A transaction template over the same connections the client uses.
     *
     * @return the transaction template
     */
    public TransactionOperations transactions() {
        return transactionTemplate;
    }

    /**
     * The manager those transactions are taken through, for a class that builds its own.
     *
     * <p>{@code JdbcRegisterStore} needs two boundaries over this one data source and only one of
     * them is the ordinary kind, so it is handed the manager rather than a template.
     *
     * @return the transaction manager, over this fixture's data source and no other
     */
    public PlatformTransactionManager transactionManager() {
        return platformTransactionManager;
    }

    /**
     * The SQL of every statement prepared since the last {@link #forgetStatements()}, in order.
     *
     * @return the statements, as the driver was given them
     */
    public List<String> statements() {
        return List.copyOf(executed);
    }

    /** Forgets what has been executed, so a case asserts over its own read and no arrangement. */
    public void forgetStatements() {
        executed.clear();
    }

    /**
     * Empties the given tables in the order they are named, which is child before parent.
     *
     * <p>Rather than truncating: a suite that owns its database owns its rows, and a case that
     * starts from nothing is what makes "and nothing else" a statement about the read.
     *
     * @param tables the tables, referencing table first
     */
    public void empty(final String... tables) {
        for (final String table : tables) {
            client.sql("DELETE FROM " + table).update();
        }
        forgetStatements();
    }

    /**
     * Runs statements over one connection of this fixture's own, outside the recording.
     *
     * <p>For the arrangement a case makes about the planner rather than about the data -
     * {@code ANALYZE}, and the session setting that asks the planner what it <em>can</em> use. The
     * connection is the caller's to close, because a session setting only lasts as long as one.
     *
     * @return an open connection to this database
     * @throws SQLException however the server refused
     */
    public Connection openConnection() throws SQLException {
        return DriverManager.getConnection(url, PostgresTestSupport.username(),
                PostgresTestSupport.password());
    }

    /**
     * Runs one statement over a connection of this fixture's own and closes it.
     *
     * @param sql the statement
     * @throws SQLException however the server refused
     */
    public void run(final String sql) throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** The data source, wrapped so that what the driver is asked to prepare is kept. */
    private final class RecordingDataSource extends DelegatingDataSource {

        private RecordingDataSource(final DataSource delegate) {
            super(delegate);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return recording(super.getConnection());
        }

        @Override
        public Connection getConnection(final String username, final String password)
                throws SQLException {
            return recording(super.getConnection(username, password));
        }

        private Connection recording(final Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        if (prepares(method.getName()) && args != null && args.length > 0
                                && args[0] instanceof String sql) {
                            executed.add(sql);
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException invoked) {
                            // The driver's own failure, handed on as itself: a reflective wrapper
                            // reaching a caller would be a failure this fixture invented, and the
                            // outage case is about exactly which class arrives.
                            throw invoked.getCause();
                        }
                    });
        }

        private boolean prepares(final String method) {
            return "prepareStatement".equals(method) || "prepareCall".equals(method)
                    || "createStatement".equals(method);
        }
    }
}
