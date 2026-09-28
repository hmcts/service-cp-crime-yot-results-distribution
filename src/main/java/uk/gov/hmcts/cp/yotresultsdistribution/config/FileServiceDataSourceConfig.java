package uk.gov.hmcts.cp.yotresultsdistribution.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The second datasource: the framework file service's own database, which the render payload is
 * written into.
 *
 * <p>It exists because systemdocgenerator renders a payload that is <em>already</em> in the file
 * service and takes its id - so this service inserts the {@code metadata} and {@code content} rows
 * the framework's own repositories insert, and reads nothing back (plan, Complexity Tracking).
 *
 * <p>Present where <em>either</em> half writes a file, which is {@link FileServiceNeeded}: the
 * nightly run stores a render payload, and the morning report stores the CSV notificationnotify
 * attaches by id. Read as the generation half's switch alone - which it was until review gate 7 - a
 * pod in FR-004's shape with the e-mail output on held no pool at all, and the sink over it could
 * not be built. A deployment that writes no file still opens no connection to a database it has no
 * use for, and the local run and every context-load test are still spared a second Postgres.
 * {@link PropertiesValidator} refuses either half with no {@code yotresultsdistribution.fileservice.url}, so
 * this configuration never has to guess at one.
 *
 * <p><strong>Neither bean is a default autowiring candidate.</strong> Both would otherwise be found
 * by type: Spring Boot's own {@code DataSource} and {@code JdbcClient} auto-configurations are
 * conditional on there being no such bean, so declaring these unqualified would silently take the
 * processed log's datasource away the moment either half was enabled - the store this service
 * records into, removed by switching on a half that reads it. Both are therefore reached by name
 * only, and everything that already injects a {@code JdbcClient} by type keeps the one it had.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(FileServiceNeeded.class)
public class FileServiceDataSourceConfig {

    /** The name the file-service datasource is qualified by; it is never found by type. */
    public static final String DATA_SOURCE = "fileServiceDataSource";

    /** The name the file-service {@link JdbcClient} is qualified by; likewise never by type. */
    public static final String JDBC_CLIENT = "fileServiceJdbcClient";

    /**
     * The pool's name in Hikari's own metrics and thread names, so the two pools are told apart in
     * a heap dump and on a dashboard.
     */
    private static final String POOL_NAME = "yot-results-distribution-fileservice";

    /**
     * Lazy pool initialisation: no connection is opened during context refresh.
     *
     * <p>The same value the processed log's pool carries, for the same reason - the pod must start
     * with the database down so that actuator is up to report readiness honestly, and a pod that
     * cannot start cannot tell anyone why. This datasource gates readiness only while a generation
     * run is in progress, so a file service that is unreachable at 09:00 is not a pod that rolls -
     * and on a pod that only e-mails the report, no generation run ever starts, so it never gates
     * readiness at all.
     */
    private static final long INITIALIZATION_FAIL_TIMEOUT = -1L;

    /**
     * The PostgreSQL driver's socket timeout, in seconds, matching the processed log's.
     *
     * <p>A database that dies without closing its connections would otherwise hold a run's insert
     * for ever, and a batch that never returns from its payload write is a batch nothing times out.
     */
    private static final String SOCKET_TIMEOUT_SECONDS = "30";

    /**
     * The write-only connection pool to the stack's file-service database.
     *
     * @param properties the bound settings, for the URL and the write identity
     * @return the pool, closed with the context
     */
    @Bean(name = DATA_SOURCE, defaultCandidate = false, destroyMethod = "close")
    public HikariDataSource fileServiceDataSource(final YotResultsDistributionProperties properties) {
        final YotResultsDistributionProperties.Fileservice fileservice = properties.fileservice();
        final HikariConfig config = new HikariConfig();
        config.setPoolName(POOL_NAME);
        config.setJdbcUrl(fileservice.url());
        // Credentials are optional and unset in a stack that authenticates the pod itself: the URL
        // is what startup requires, because it is the only one of the three that has no other way
        // of arriving.
        config.setUsername(fileservice.username());
        config.setPassword(fileservice.password());
        config.setInitializationFailTimeout(INITIALIZATION_FAIL_TIMEOUT);
        config.addDataSourceProperty("socketTimeout", SOCKET_TIMEOUT_SECONDS);
        return new HikariDataSource(config);
    }

    /**
     * The statements the payload store writes with.
     *
     * @param dataSource the file-service pool, by name
     * @return the client
     */
    @Bean(name = JDBC_CLIENT, defaultCandidate = false)
    public JdbcClient fileServiceJdbcClient(
            @Qualifier(DATA_SOURCE) final DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }
}
