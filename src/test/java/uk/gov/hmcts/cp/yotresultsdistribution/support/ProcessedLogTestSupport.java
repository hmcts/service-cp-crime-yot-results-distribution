package uk.gov.hmcts.cp.yotresultsdistribution.support;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.yotresultsdistribution.application.IdempotencyGuard;
import uk.gov.hmcts.cp.yotresultsdistribution.config.ProcessingMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.ProcessedRequestRepository;

/**
 * What every guard suite needs: a pooled connection to the shared Postgres, a guard built over it,
 * and a way to read a whole row back.
 *
 * <p>The pool is real (Hikari, several connections) rather than a single-connection helper, because
 * the contention and reclamation suites run genuinely concurrent deliveries and a serialising
 * connection would quietly turn those races into a queue.
 *
 * <p>Every suite mints fresh identifiers through {@link #command()}, so the suites share one
 * container without sharing rows and none of them needs to truncate a table another might be using.
 */
public final class ProcessedLogTestSupport {

    /** The single value {@code source} is permitted to take. */
    public static final String SOURCE = "RESULTS";

    /** The single row a fixture update is expected to touch. */
    private static final int ONE_ROW = 1;

    private static DataSource pooledDataSource;

    private ProcessedLogTestSupport() {
        // Static fixture holder.
    }

    /**
     * The shared, migrated data source. Started and migrated on first use.
     */
    public static synchronized DataSource dataSource() {
        if (pooledDataSource == null) {
            PostgresTestSupport.applyFlyway();
            pooledDataSource = DataSourceBuilder.create()
                    .url(PostgresTestSupport.jdbcUrl())
                    .username(PostgresTestSupport.username())
                    .password(PostgresTestSupport.password())
                    .build();
        }
        return pooledDataSource;
    }

    /**
     * A client over the pooled connection, for suites that read rows back directly.
     */
    public static JdbcClient jdbcClient() {
        return JdbcClient.create(dataSource());
    }

    /**
     * A transaction template over the pooled connection, for the writes whose decision is in Java.
     *
     * <p>Over {@link #dataSource()} and no other, because {@link #jdbcClient()} obtains its
     * connections through {@code DataSourceUtils} against that same instance: a manager bound to a
     * second data source would open a transaction the client never joins, and the suite would prove
     * nothing while appearing to.
     */
    public static TransactionOperations transactions() {
        return new TransactionTemplate(transactionManager());
    }

    /**
     * A transaction manager over the pooled connection, for the classes that build their own.
     *
     * <p>{@code JdbcRegisterStore} is one: it needs two boundaries over the one data source and
     * only one of them is the ordinary kind, so it takes the manager and makes both rather than
     * being handed a template it could not vary.
     *
     * @return a manager over {@link #dataSource()}, and over no other
     */
    public static PlatformTransactionManager transactionManager() {
        return new JdbcTransactionManager(dataSource());
    }

    /**
     * A repository holding the claim lease a suite wants.
     */
    public static ProcessedRequestRepository repository(final Duration lease) {
        return new ProcessedRequestRepository(jdbcClient(), lease);
    }

    /**
     * Ages an existing claim until it is unambiguously past its expiry — what a crashed runner
     * leaves behind — using the database's own clock and without sleeping.
     *
     * <p>An hour into the past rather than a lease of zero. Zero writes an expiry of {@code now()},
     * and the reclaim requires {@code claim_expires_at < now()} strictly, so two transactions
     * landing on the same microsecond would leave the claim un-reclaimable and the suite flaky for
     * reasons that have nothing to do with the guard.
     *
     * <p>{@code updated_at} is deliberately left alone: this is a fixture ageing a claim, not the
     * service recording a change.
     *
     * @throws IllegalStateException if there was no claim to age — a fixture that quietly does
     *                               nothing would turn every test using it green for the wrong
     *                               reason
     */
    public static void expireClaim(final String source, final UUID requestId) {
        final int aged = jdbcClient()
                .sql("""
                        UPDATE processed_request
                           SET claim_expires_at = now() - interval '1 hour'
                         WHERE source = :source AND request_id = :requestId
                           AND claim_owner IS NOT NULL
                        """)
                .param("source", source)
                .param("requestId", requestId)
                .update();
        if (aged != ONE_ROW) {
            throw new IllegalStateException("expected one live claim to age for " + source + "/"
                    + requestId + ", aged " + aged);
        }
    }

    /**
     * A guard holding the claim lease a suite wants, instrumented by the given metrics.
     */
    public static IdempotencyGuard guard(final Duration lease, final ProcessingMetrics metrics) {
        return new IdempotencyGuard(repository(lease), metrics);
    }

    /**
     * A guard whose instruments nothing asserts on.
     */
    public static IdempotencyGuard guard(final Duration lease) {
        return guard(lease, new ProcessingMetrics(new SimpleMeterRegistry()));
    }

    /**
     * Seeds {@code updated_at} an hour into the past, by the database's clock.
     *
     * <p>So that "the write moved the timestamp on" can be asserted strictly. Against a timestamp
     * written moments earlier, a statement that forgot {@code updated_at = now()} still satisfies
     * "not before what it was", which is no assertion at all.
     *
     * @throws IllegalStateException if the row is not there to seed
     */
    public static void ageUpdatedAt(final String source, final UUID requestId) {
        final int aged = jdbcClient()
                .sql("""
                        UPDATE processed_request
                           SET updated_at = now() - interval '1 hour'
                         WHERE source = :source AND request_id = :requestId
                        """)
                .param("source", source)
                .param("requestId", requestId)
                .update();
        if (aged != ONE_ROW) {
            throw new IllegalStateException(
                    "expected one row to age for " + source + "/" + requestId + ", aged " + aged);
        }
    }

    /**
     * A fresh, valid command — a request no other test has seen.
     */
    public static DistributionCommand command() {
        return new DistributionCommand(
                SOURCE,
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocalDate.of(2026, 8, 20),
                Instant.parse("2026-08-20T09:00:00Z"),
                "Hearing_Resulted");
    }

    /**
     * Every column of a processed-request row, so a test can assert that nothing at all changed.
     */
    public record Row(
            String source,
            UUID requestId,
            UUID hearingId,
            LocalDate hearingDay,
            Instant sharedTime,
            String eventType,
            String requestFingerprint,
            String status,
            int attempts,
            String completionReason,
            String failureReason,
            String exhaustedMessageId,
            String auditNote,
            String claimOwner,
            UUID claimToken,
            Instant claimExpiresAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * Reads a row back in full, or reports its absence.
     */
    public static Optional<Row> row(final String source, final UUID requestId) {
        return row(jdbcClient(), source, requestId);
    }

    /**
     * The same read against a caller-supplied connection — for the durability suite, which owns its
     * own container and rebuilds its pool underneath itself.
     */
    public static Optional<Row> row(
            final JdbcClient client, final String source, final UUID requestId) {
        return client
                .sql("""
                        SELECT source, request_id, hearing_id, hearing_day, shared_time, event_type,
                               request_fingerprint, status, attempts, completion_reason,
                               failure_reason, exhausted_message_id, audit_note, claim_owner,
                               claim_token, claim_expires_at, created_at, updated_at
                          FROM processed_request
                         WHERE source = :source AND request_id = :requestId
                        """)
                .param("source", source)
                .param("requestId", requestId)
                .query((rs, rowNumber) -> new Row(
                        rs.getString("source"),
                        rs.getObject("request_id", UUID.class),
                        rs.getObject("hearing_id", UUID.class),
                        rs.getObject("hearing_day", LocalDate.class),
                        instant(rs.getObject("shared_time", OffsetDateTime.class)),
                        rs.getString("event_type"),
                        rs.getString("request_fingerprint"),
                        rs.getString("status"),
                        rs.getInt("attempts"),
                        rs.getString("completion_reason"),
                        rs.getString("failure_reason"),
                        rs.getString("exhausted_message_id"),
                        rs.getString("audit_note"),
                        rs.getString("claim_owner"),
                        rs.getObject("claim_token", UUID.class),
                        instant(rs.getObject("claim_expires_at", OffsetDateTime.class)),
                        instant(rs.getObject("created_at", OffsetDateTime.class)),
                        instant(rs.getObject("updated_at", OffsetDateTime.class))))
                .optional();
    }

    /**
     * The row, insisting it exists — the ordinary case in a suite that has just written one.
     */
    public static Row requireRow(final String source, final UUID requestId) {
        return requireRow(jdbcClient(), source, requestId);
    }

    /**
     * The row, insisting it exists, against a caller-supplied connection.
     */
    public static Row requireRow(
            final JdbcClient client, final String source, final UUID requestId) {
        return row(client, source, requestId).orElseThrow(() -> new IllegalStateException(
                "no processed_request row for " + source + "/" + requestId));
    }

    /**
     * The submission half of the processed log: what was sent for a request, and how it went.
     *
     * @param outputId          the row's own identity, fixed the first time it is written
     * @param courtCentreId     the court centre the register covers
     * @param courtCentreOuCode the court centre's OU code
     * @param registerDate      the day the register's recipients were read for
     * @param fileName          the name progression renders the PDF under
     * @param status            PENDING, POSTED or FAILED
     * @param responseCode      what progression answered, where anything did
     * @param requestDigest     the digest of exactly the bytes that were sent
     * @param anomalySummary    the bounded counts of what the register was assembled without
     */
    public record OutputRow(
            UUID outputId,
            UUID courtCentreId,
            String courtCentreOuCode,
            LocalDate registerDate,
            String fileName,
            String status,
            Integer responseCode,
            String requestDigest,
            String anomalySummary) {
    }

    /**
     * Reads the output row for a request, or reports its absence.
     *
     * <p>Absence is a real answer here and the one four of the five completion reasons produce: the
     * output cardinality is 0..1, and a run that sent nothing has nothing to say about a submission.
     *
     * @param source    the producing context
     * @param requestId the request
     * @return the row, if one was ever claimed
     */
    public static Optional<OutputRow> outputRow(final String source, final UUID requestId) {
        return jdbcClient()
                .sql("""
                        SELECT output_id, court_centre_id, court_centre_ou_code, register_date,
                               file_name, status, response_code, request_digest, anomaly_summary
                          FROM processed_output
                         WHERE source = :source AND request_id = :requestId
                        """)
                .param("source", source)
                .param("requestId", requestId)
                .query((rs, rowNumber) -> new OutputRow(
                        rs.getObject("output_id", UUID.class),
                        rs.getObject("court_centre_id", UUID.class),
                        rs.getString("court_centre_ou_code"),
                        rs.getObject("register_date", LocalDate.class),
                        rs.getString("file_name"),
                        rs.getString("status"),
                        rs.getObject("response_code", Integer.class),
                        rs.getString("request_digest"),
                        rs.getString("anomaly_summary")))
                .optional();
    }

    /**
     * The output row, insisting it exists.
     *
     * @param source    the producing context
     * @param requestId the request
     * @return the row
     */
    public static OutputRow requireOutputRow(final String source, final UUID requestId) {
        return outputRow(source, requestId).orElseThrow(() -> new IllegalStateException(
                "no processed_output row for " + source + "/" + requestId));
    }

    private static Instant instant(final OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
