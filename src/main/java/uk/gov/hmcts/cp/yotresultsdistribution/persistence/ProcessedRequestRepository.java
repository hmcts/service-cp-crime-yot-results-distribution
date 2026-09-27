package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ProcessedRequestRecord;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ProcessedRequestSummary;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunClaim;

/**
 * The processed log's statements, one method per statement, written out by hand.
 *
 * <p>Hand-written SQL rather than an ORM because the guard's correctness rests on what each
 * statement affects: <strong>the affected-row count is the decision</strong>. One row from the
 * insert means this delivery owns a fresh request; one row from the reclaim means it won the race
 * for an abandoned claim; one row from an outcome write means the claim it is settling is still its
 * own. A session cache and dirty-checking would sit between the code and exactly the property under
 * test.
 *
 * <p>Zero rows is never a signal to look again. Per the data model's no-spin rule the caller hands
 * the delivery back and lets broker redelivery re-enter the state machine, which already carries
 * back-off and a delivery budget; a re-read loop would burn CPU holding a lock and could starve the
 * runner that won.
 *
 * <p>Every timestamp in these statements comes from the database. Expiry is written as
 * {@code now() + lease} and compared against {@code now()}, both inside the database, so no claim
 * decision anywhere depends on how well two pods' clocks agree.
 *
 * <p>The lease is bound as an ISO-8601 duration and cast, which is what lets a {@link Duration}
 * reach an {@code interval} parameter unambiguously; the arithmetic itself stays in SQL.
 */
public class ProcessedRequestRepository {

    private static final String SOURCE = "source";
    private static final String REQUEST_ID = "requestId";
    private static final String OWNER = "owner";
    private static final String TOKEN = "token";
    private static final String LEASE_PARAM = "lease";
    private static final String MESSAGE_ID = "messageId";
    private static final String REASON = "reason";

    /** Statement 1 — the record, its claim and its first attempt, in one statement. */
    private static final String INSERT_NEW = """
            INSERT INTO processed_request (
                source, request_id, hearing_id, hearing_day, shared_time, event_type,
                request_fingerprint, status, attempts,
                claim_owner, claim_token, claim_expires_at,
                created_at, updated_at)
            VALUES (
                :source, :requestId, :hearingId, :hearingDay, :sharedTime, :eventType,
                :fingerprint, 'RECEIVED', 1,
                :owner, :token, now() + CAST(:lease AS interval),
                now(), now())
            ON CONFLICT (source, request_id) DO NOTHING
            """;

    /**
     * Statement 2 — everything the branch decision needs.
     *
     * <p>{@code failure_reason} is read alongside the data model's column list so the replay can
     * carry the reason the record was parked into its audit note before the transition clears the
     * column. It informs no decision.
     */
    private static final String READ_RECORD = """
            SELECT status, request_fingerprint, failure_reason, exhausted_message_id,
                   attempts, claim_owner, claim_expires_at
              FROM processed_request
             WHERE source = :source AND request_id = :requestId
            """;

    /** Statement 3 — take over a claim that is absent or past its expiry. */
    private static final String RECLAIM_STALE_CLAIM = """
            UPDATE processed_request
               SET claim_owner = :owner,
                   claim_token = :token,
                   claim_expires_at = now() + CAST(:lease AS interval),
                   attempts = attempts + 1,
                   updated_at = now()
             WHERE source = :source
               AND request_id = :requestId
               AND status IN ('RECEIVED', 'RETRYING')
               AND (claim_expires_at IS NULL OR claim_expires_at < now())
            """;

    /**
     * Statement 4 — the run succeeded, in one of the five ways a court-register run ends well.
     *
     * <p>{@code failure_reason} is cleared because it describes the current status, not history: a
     * COMPLETED row still carrying the transient reason a retried run once wrote reads as a
     * contradiction to the support engineer the log exists for. The retry history lives in the logs,
     * exactly as it does after a replay — {@code REPLAY_FAILED} below clears the column the same
     * way, carrying the old reason into the audit note.
     */
    private static final String RECORD_COMPLETED = """
            UPDATE processed_request
               SET status = 'COMPLETED', completion_reason = :reason,
                   failure_reason = NULL,
                   claim_owner = NULL, claim_token = NULL, claim_expires_at = NULL,
                   updated_at = now()
             WHERE source = :source AND request_id = :requestId
               AND claim_owner = :owner AND claim_token = :token
            """;

    /** Statement 4 — the run failed, and deliveries of this message remain. */
    private static final String RECORD_RETRYING = """
            UPDATE processed_request
               SET status = 'RETRYING', failure_reason = :reason,
                   claim_owner = NULL, claim_token = NULL, claim_expires_at = NULL,
                   updated_at = now()
             WHERE source = :source AND request_id = :requestId
               AND claim_owner = :owner AND claim_token = :token
            """;

    /** Statement 4 — the run failed on the final permitted delivery, or beyond any retrying. */
    private static final String RECORD_FAILED = """
            UPDATE processed_request
               SET status = 'FAILED', failure_reason = :reason,
                   exhausted_message_id = :messageId,
                   claim_owner = NULL, claim_token = NULL, claim_expires_at = NULL,
                   updated_at = now()
             WHERE source = :source AND request_id = :requestId
               AND claim_owner = :owner AND claim_token = :token
            """;

    /**
     * Statement 5 — a deliberate resubmission under a fresh identity.
     *
     * <p>{@code attempts} is carried forward, not reset: the counter is a lifetime tally, so five
     * failed deliveries followed by a successful replay leave the row showing 6. The
     * {@code exhausted_message_id <> :messageId} predicate is defence in depth — the same-identity
     * case is already decided on the read — and is safe against NULL because the FAILED check
     * guarantees the column is populated on every parked row.
     */
    private static final String REPLAY_FAILED = """
            UPDATE processed_request
               SET status = 'RECEIVED',
                   claim_owner = :owner, claim_token = :token,
                   claim_expires_at = now() + CAST(:lease AS interval),
                   attempts = attempts + 1,
                   failure_reason = NULL,
                   exhausted_message_id = NULL,
                   audit_note = :note,
                   updated_at = now()
             WHERE source = :source
               AND request_id = :requestId
               AND status = 'FAILED'
               AND exhausted_message_id <> :messageId
            """;

    /**
     * The columns the report reads a request through, which are not the columns a claim decision
     * reads it through.
     *
     * <p>Shared by the three statements below and closed by each of them with its own age
     * expression, because which moment a row's age is measured from is the whole difference between
     * them.
     */
    private static final String SUMMARY_COLUMNS = """
            SELECT source, request_id, hearing_id, hearing_day, status, attempts, failure_reason,
                   created_at, updated_at,
            """;

    /**
     * Statement 6 - the requests parked inside the window, oldest first.
     *
     * <p>The boundary is on {@code updated_at} because that is when a row reached the state being
     * asked about; {@code created_at} would answer which requests that <em>arrived</em> yesterday
     * failed, which is a different and less useful question on the morning after an outage. The age
     * is measured from the same column, so what the report says is how long ago the request was
     * parked.
     *
     * <p><strong>Half-open</strong>: the start is inclusive and the end exclusive, so one run's
     * window closes exactly where the next one opens and a request parked on that instant is named
     * by one report rather than by two.
     *
     * <p>Served by {@code idx_request_status_updated}, which V4 adds for it.
     */
    private static final String FAILED_BETWEEN = SUMMARY_COLUMNS + """
                   extract(epoch from (now() - updated_at))::bigint AS age_seconds
              FROM processed_request
             WHERE status = 'FAILED'
               AND updated_at >= :from
               AND updated_at < :to
             ORDER BY updated_at
            """;

    /**
     * Statement 7 - the requests still in flight that arrived before the cut-off, oldest first.
     *
     * <p><strong>The predicate is spelled exactly as {@code idx_request_non_terminal_created}
     * spells it.</strong> Postgres matches a partial index by proving the query's predicate implies
     * the index's, and a differently spelled equivalent is a planner coin toss - so the two are the
     * same text and the implication is trivial.
     *
     * <p>Oldest first: the worst problem is the one read first, on a screen and in a table.
     */
    private static final String NON_TERMINAL_OLDER_THAN = SUMMARY_COLUMNS + """
                   extract(epoch from (now() - created_at))::bigint AS age_seconds
              FROM processed_request
             WHERE status IN ('RECEIVED', 'RETRYING')
               AND created_at < :createdBefore
             ORDER BY created_at
            """;

    /**
     * Statement 8 - how many requests are still in flight past the cut-off, as a number.
     *
     * <p><strong>The same predicate again, spelled the same way again</strong>, for the same
     * reason: {@code idx_request_non_terminal_created} is partial, Postgres matches a partial
     * index by proving the query's predicate implies the index's, and a differently spelled
     * equivalent is a planner coin toss. The sweep runs this every refresh interval for the life
     * of every pod.
     *
     * <p>A count rather than the list it used to size. The sweep wants one number, and reading the
     * rows to get it is a read whose cost grows with the backlog it is reporting - slowest on the
     * morning the reading matters most - while every row it materialises is a case carried into a
     * JVM to be counted and dropped. On this register that case belongs to a youth.
     */
    private static final String COUNT_NON_TERMINAL_OLDER_THAN = """
            SELECT count(*)
              FROM processed_request
             WHERE status IN ('RECEIVED', 'RETRYING')
               AND created_at < :createdBefore
            """;

    /**
     * Statement 9 - the oldest request that has not finished, which is the sweep's first gauge.
     *
     * <p>The same predicate and the same index, without the cut-off and with a limit: the sweep
     * asks how old the oldest unfinished request is and nothing else, and reading the whole list to
     * answer one number would be a read that grows with the backlog it is reporting.
     */
    private static final String OLDEST_NON_TERMINAL = SUMMARY_COLUMNS + """
                   extract(epoch from (now() - created_at))::bigint AS age_seconds
              FROM processed_request
             WHERE status IN ('RECEIVED', 'RETRYING')
             ORDER BY created_at
             LIMIT 1
            """;

    private final JdbcClient jdbcClient;
    private final Duration claimLease;

    /**
     * Creates the repository over the processed log's connection.
     *
     * @param jdbcClient the processed log's connection
     * @param claimLease {@code yotresultsdistribution.claim.lease} — how long an acquired claim stays live.
     *                   Passed as the one value the statements bind rather than as the whole
     *                   configuration tree: the lease is all the SQL needs, and the expiry it
     *                   produces is computed by the database, not here.
     */
    public ProcessedRequestRepository(final JdbcClient jdbcClient, final Duration claimLease) {
        this.jdbcClient = jdbcClient;
        this.claimLease = claimLease;
    }

    /**
     * Statement 1 — insert a new request, taking the claim and the first attempt in one statement.
     *
     * @param command     the validated request
     * @param fingerprint the fingerprint of its immutable fields
     * @param runClaim    the claim this delivery would run under
     * @return whether this delivery created the record. False means a record already exists — this
     *         delivery lost the insert race, or the request is simply known — and the caller reads
     *         and branches.
     */
    public boolean insertNew(
            final DistributionCommand command,
            final String fingerprint,
            final RunClaim runClaim) {
        return StoreOutage.translating("record a new request", () -> affected(jdbcClient
                .sql(INSERT_NEW)
                .param(SOURCE, command.source())
                .param(REQUEST_ID, command.requestId())
                .param("hearingId", command.hearingId())
                .param("hearingDay", command.hearingDay())
                .param("sharedTime", OffsetDateTime.ofInstant(command.sharedTime(), ZoneOffset.UTC))
                .param("eventType", command.eventType())
                .param("fingerprint", fingerprint)
                .param(OWNER, runClaim.owner())
                .param(TOKEN, runClaim.token())
                .param(LEASE_PARAM, lease())
                .update()));
    }

    /**
     * Statement 2 — read the record the branch decision is made from.
     *
     * @param source    the record's key, part 1
     * @param requestId the record's key, part 2
     * @return the record, or empty where there is none
     */
    public Optional<ProcessedRequestRecord> read(final String source, final UUID requestId) {
        return StoreOutage.translating("read a request record", () -> jdbcClient.sql(READ_RECORD)
                .param(SOURCE, source)
                .param(REQUEST_ID, requestId)
                .query((rs, rowNumber) -> new ProcessedRequestRecord(
                        RequestStatus.valueOf(rs.getString("status")),
                        rs.getString("request_fingerprint"),
                        rs.getString("failure_reason"),
                        rs.getString("exhausted_message_id"),
                        rs.getInt("attempts"),
                        rs.getString("claim_owner"),
                        instant(rs.getObject("claim_expires_at", OffsetDateTime.class))))
                .optional());
    }

    /**
     * Statement 3 — reclaim an absent or expired claim on a non-terminal record.
     *
     * <p>The state is deliberately left alone: the reclaim moves the claim and the attempt counter,
     * and only an outcome moves the state.
     *
     * @param runClaim the claim this delivery would run under
     * @return whether this delivery acquired the claim. False means the claim is live, another
     *         delivery won, or the record turned terminal in between — all three are handed back.
     */
    public boolean reclaimStaleClaim(final RunClaim runClaim) {
        return StoreOutage.translating("reclaim a stale claim", () -> affected(jdbcClient
                .sql(RECLAIM_STALE_CLAIM)
                .param(SOURCE, runClaim.source())
                .param(REQUEST_ID, runClaim.requestId())
                .param(OWNER, runClaim.owner())
                .param(TOKEN, runClaim.token())
                .param(LEASE_PARAM, lease())
                .update()));
    }

    /**
     * Statement 4 — record a completed run, releasing the claim.
     *
     * @param runClaim         the claim the run was made under
     * @param completionReason the bounded reason the run completed for
     * @return whether the write was admitted by the owner-and-token predicate
     */
    public boolean recordCompleted(final RunClaim runClaim, final String completionReason) {
        return StoreOutage.translating("record a completed run", () -> affected(outcome(RECORD_COMPLETED, runClaim)
                .param(REASON, completionReason)
                .update()));
    }

    /**
     * Statement 4 — record a transient failure, releasing the claim.
     *
     * @param runClaim      the claim the run was made under
     * @param failureReason the bounded reason the run failed for
     * @return whether the write was admitted by the owner-and-token predicate
     */
    public boolean recordRetrying(final RunClaim runClaim, final String failureReason) {
        return StoreOutage.translating("record a transient failure", () -> affected(outcome(RECORD_RETRYING, runClaim)
                .param(REASON, failureReason)
                .update()));
    }

    /**
     * Statement 4 — park the request, recording the identity that exhausted the deliveries in the
     * same statement as the state, so a parked record can never exist without saying what parked it.
     *
     * <p>The identity is the claim's own — the delivery that acquired it is by definition the one
     * whose failure parks the request — so no caller can supply an unrelated one.
     *
     * @param runClaim      the claim the run was made under; its message identity parks the row
     * @param failureReason the bounded reason the run failed for
     * @return whether the write was admitted by the owner-and-token predicate
     */
    public boolean recordFailed(final RunClaim runClaim, final String failureReason) {
        return StoreOutage.translating("park a request", () -> affected(outcome(RECORD_FAILED,
                runClaim)
                .param(REASON, failureReason)
                .param(MESSAGE_ID, runClaim.messageId())
                .update()));
    }

    /**
     * Statement 5 — replay a parked request under a fresh message identity, the claim's own.
     *
     * @param runClaim  the claim the replayed run would be made under
     * @param auditNote the bounded note recording the replay
     * @return whether the replay was admitted. False means the record moved between the read and
     *         this update; it never means the identity was the same one, which the read decides.
     */
    public boolean replayFailed(final RunClaim runClaim, final String auditNote) {
        return StoreOutage.translating("replay a parked request", () -> affected(jdbcClient
                .sql(REPLAY_FAILED)
                .param(SOURCE, runClaim.source())
                .param(REQUEST_ID, runClaim.requestId())
                .param(OWNER, runClaim.owner())
                .param(TOKEN, runClaim.token())
                .param(LEASE_PARAM, lease())
                .param(MESSAGE_ID, runClaim.messageId())
                .param("note", auditNote)
                .update()));
    }

    /**
     * The report's REQUEST_FAILED read: the requests parked inside the window, oldest first.
     *
     * <p>Bounded by the window on {@code updated_at}, because that is when the row reached the
     * state being asked about: {@code created_at} would answer "which requests that arrived
     * yesterday failed", which is a different and less useful question on the morning after an
     * outage.
     *
     * <p>Both ends, and the end exclusive: a report is a statement about a period, and a period
     * with one end open is a statement two consecutive reports both make.
     *
     * @param from the window's start, inclusive
     * @param to   the window's end, exclusive
     * @return every parked request settled inside it, oldest first
     */
    public List<ProcessedRequestSummary> failedBetween(final Instant from, final Instant to) {
        return StoreOutage.translating("read the requests parked inside a window",
                () -> jdbcClient.sql(FAILED_BETWEEN)
                        .param("from", offsetOf(from))
                        .param("to", offsetOf(to))
                        .query((rs, rowNumber) -> summary(rs))
                        .list());
    }

    /**
     * The report's REQUEST_LATE read: the requests still in flight that arrived too long ago.
     *
     * <p><strong>Not bounded by the window, and it must not be.</strong> A request that has been
     * stuck for three days is late this morning whether or not it arrived inside the last
     * twenty-four hours, and a window filter would make the longest-running problem the first one
     * to disappear from the report.
     *
     * @param createdBefore the cut-off: now less the intake threshold
     * @return every RECEIVED or RETRYING request that arrived before it, oldest first
     */
    public List<ProcessedRequestSummary> nonTerminalOlderThan(final Instant createdBefore) {
        return StoreOutage.translating("read the requests still in flight past a cut-off",
                () -> jdbcClient.sql(NON_TERMINAL_OLDER_THAN)
                        .param("createdBefore", offsetOf(createdBefore))
                        .query((rs, rowNumber) -> summary(rs))
                        .list());
    }

    /**
     * The intake sweep's second gauge: how many requests are over the threshold, as a number.
     *
     * <p>Shares {@link #nonTerminalOlderThan(Instant)}'s predicate exactly, so the gauge and the
     * report's REQUEST_LATE list cannot disagree about which requests are late. The boundary is
     * exclusive - {@code created_at < :cutOff} - so a request created exactly the threshold ago
     * is not yet over it, and neither caller may nudge its cut-off to soften that.
     *
     * @param createdBefore the cut-off: now less the intake threshold
     * @return how many RECEIVED or RETRYING requests arrived before it
     */
    public long countNonTerminalOlderThan(final Instant createdBefore) {
        return StoreOutage.translating("count the requests still in flight past a cut-off",
                () -> jdbcClient.sql(COUNT_NON_TERMINAL_OLDER_THAN)
                        .param("createdBefore", offsetOf(createdBefore))
                        .query(Long.class)
                        .single());
    }

    /**
     * The intake sweep's first gauge: the oldest request that has not reached a terminal state.
     *
     * <p>Empty is an ordinary answer and the one a healthy service gives, which is why it is an
     * {@link Optional} rather than a row the caller has to know might not be there.
     *
     * @return the oldest unfinished request, or empty where nothing is unfinished
     */
    public Optional<ProcessedRequestSummary> oldestNonTerminal() {
        return StoreOutage.translating("read the oldest unfinished request",
                () -> jdbcClient.sql(OLDEST_NON_TERMINAL)
                        .query((rs, rowNumber) -> summary(rs))
                        .optional());
    }

    /** The three outcome writes differ only in what they set; the predicate is common to all. */
    private JdbcClient.StatementSpec outcome(final String sql, final RunClaim runClaim) {
        return jdbcClient.sql(sql)
                .param(SOURCE, runClaim.source())
                .param(REQUEST_ID, runClaim.requestId())
                .param(OWNER, runClaim.owner())
                .param(TOKEN, runClaim.token());
    }

    /** ISO-8601, which Postgres reads as an interval without a locale or a format assumption. */
    private String lease() {
        return claimLease.toString();
    }

    /**
     * One row as the report reads it, with the age the statement computed.
     *
     * <p>{@code age_seconds} is read off the result set rather than derived here, which is the
     * whole point of the three statements above computing it: no reading of this JVM's clock is
     * ever subtracted from a stored timestamp.
     */
    private static ProcessedRequestSummary summary(final ResultSet rs) throws SQLException {
        return new ProcessedRequestSummary(
                rs.getString("source"),
                rs.getObject("request_id", UUID.class),
                rs.getObject("hearing_id", UUID.class),
                rs.getObject("hearing_day", LocalDate.class),
                RequestStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getString("failure_reason"),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("updated_at", OffsetDateTime.class)),
                rs.getLong("age_seconds"));
    }

    private static boolean affected(final int rows) {
        return rows > 0;
    }

    private static OffsetDateTime offsetOf(final Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(final OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
