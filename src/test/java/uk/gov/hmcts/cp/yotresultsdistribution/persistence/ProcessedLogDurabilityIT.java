package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import uk.gov.hmcts.cp.yotresultsdistribution.application.IdempotencyGuard;
import uk.gov.hmcts.cp.yotresultsdistribution.config.ProcessingMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletionReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GuardDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport.Row;

/**
 * The processed log is durable, not remembered.
 *
 * <p>A request is recorded, the database is restarted underneath the service, and the row is read
 * back unchanged. An in-memory guard — or one that never committed — passes every other suite in
 * this package and loses the register the first time a pod or a database moves.
 *
 * <p>This suite owns its container rather than sharing the one the other suites use, because a
 * restarted container is published on a new host port and the shared fixture's pool would be
 * pointing at the old one for every suite that ran afterwards. Owning a store is also what lets it
 * answer the other question this file covers — whether the log can be reached at all, which the
 * service asks before it examines a delivery rather than part-way through processing one.
 */
class ProcessedLogDurabilityIT {

    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final String DATABASE = "yotresultsdistribution";
    private static final int POSTGRES_PORT = 5432;

    /** How long a restarted container is given to answer before the wait is reported as a failure. */
    private static final Duration READY_BUDGET = Duration.ofMinutes(1);

    private static PostgreSQLContainer container;

    /**
     * The pool the suite reads through, held in a reference so that closing it around a restart
     * releases the old pool outright rather than leaving a closed one behind to be reused.
     */
    private static final AtomicReference<HikariDataSource> POOL = new AtomicReference<>();

    private final DistributionCommand command = ProcessedLogTestSupport.command();

    @BeforeAll
    static void startOwnStore() {
        container = new PostgreSQLContainer("postgres:16")
                .withDatabaseName(DATABASE)
                .withUsername(DATABASE)
                .withPassword(DATABASE);
        container.start();
        Flyway.configure()
                .dataSource(jdbcUrl(), DATABASE, DATABASE)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        openPool();
    }

    @AfterAll
    static void stopOwnStore() {
        closePool();
        container.stop();
    }

    @Test
    @DisplayName("a recorded request survives a restart of the store")
    void a_recorded_request_should_be_read_back_unchanged_after_a_restart() {
        final IdempotencyGuard guard = new IdempotencyGuard(
                new ProcessedRequestRepository(JdbcClient.create(POOL.get()), LEASE),
                new ProcessingMetrics(new SimpleMeterRegistry()));
        final GuardDecision admission =
                guard.admit(command, new DeliveryIdentity("msg-1", "runner-1/delivery-1"));
        assertThat(admission).isInstanceOf(GuardDecision.Run.class);
        guard.recordCompletion(
                ((GuardDecision.Run) admission).claim(), CompletionReason.NO_YOUTH_DEFENDANTS);
        final Row before = row();

        restartStore();

        assertThat(row()).isEqualTo(before);
        assertThat(row().completionReason())
                .as("the reason a register was not sent is exactly what support asks for after an "
                        + "outage, and it has to have been written, not remembered")
                .isEqualTo("no-youth-defendants");
    }

    /**
     * The question asked before a delivery is examined at all.
     *
     * <p>Store availability is a precondition, not a step: a delivery that arrives while the log is
     * unreachable is handed straight back, and the lifecycle controller asks the same question on a
     * schedule to know when the outage is over. Both answers matter, and the unreachable one matters
     * more — a probe that threw instead of answering would take the consumer thread with it.
     */
    @Nested
    @DisplayName("the store probe")
    class Reachability {

        @Test
        void should_answer_yes_while_the_store_is_up() {
            assertThat(new ProcessedLogProbe(JdbcClient.create(POOL.get())).available()).isTrue();
        }

        @Test
        void should_answer_no_rather_than_throw_when_the_store_refuses() {
            final DriverManagerDataSource refused =
                    new DriverManagerDataSource(jdbcUrl(), "nobody", "nothing");

            assertThat(new ProcessedLogProbe(JdbcClient.create(refused)).available()).isFalse();
        }
    }

    private Row row() {
        return ProcessedLogTestSupport.requireRow(
                JdbcClient.create(POOL.get()), command.source(), command.requestId());
    }

    /**
     * Restarts the container in place — the data directory survives, the process does not — and
     * reopens the pool against whichever host port Docker publishes the second time round.
     */
    private static void restartStore() {
        closePool();
        container.getDockerClient().restartContainerCmd(container.getContainerId()).exec();

        await().atMost(READY_BUDGET)
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(ProcessedLogDurabilityIT::assertStoreAnswers);
        openPool();
    }

    /**
     * One attempt at reaching the restarted database, as a retrying assertion.
     *
     * <p>The connection failure is wrapped and rethrown, never ignored: while the budget lasts it is
     * the reason this attempt failed, and when the budget runs out it is the cause hanging off the
     * timeout — which is the difference between "the store never came back" and a test that says
     * only that it waited. Awaitility's blanket exception-ignoring would discard exactly the
     * exception a reader needs (constitution Principle VI, which does not exempt tests).
     */
    private static void assertStoreAnswers() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(), DATABASE, DATABASE)) {
            assertThat(connection.isValid(1)).isTrue();
        } catch (SQLException unreachable) {
            throw new AssertionError(
                    "the restarted store did not answer on " + jdbcUrl(), unreachable);
        }
    }

    private static void openPool() {
        POOL.set(DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(jdbcUrl())
                .username(DATABASE)
                .password(DATABASE)
                .build());
    }

    private static void closePool() {
        Optional.ofNullable(POOL.getAndSet(null)).ifPresent(HikariDataSource::close);
    }

    /**
     * The current URL, re-inspected each time.
     *
     * <p>Testcontainers caches the port mapping it saw at start-up, and Docker publishes a freshly
     * chosen host port when a container with a dynamic mapping restarts, so the mapping is read back
     * from the daemon rather than remembered.
     */
    private static String jdbcUrl() {
        final Ports.Binding[] bindings = container.getDockerClient()
                .inspectContainerCmd(container.getContainerId())
                .exec()
                .getNetworkSettings()
                .getPorts()
                .getBindings()
                .get(new ExposedPort(POSTGRES_PORT));
        return "jdbc:postgresql://" + container.getHost() + ":" + bindings[0].getHostPortSpec()
                + "/" + DATABASE;
    }
}
