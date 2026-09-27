package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailedNotification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreRefusedRowException;

/**
 * The {@code register_notification} table: one row per distinct recipient of a batch.
 *
 * <p>The row is written PENDING before the POST and settled after it, so what was attempted is on
 * record whether or not it was answered - the same discipline 001 applies to
 * {@code processed_output.request_digest}, and for the same reason.
 *
 * <p>Written in the same idiom as {@link ProcessedOutputRepository}: hand-written SQL, one method
 * per statement, the affected-row count as the decision, and every statement made through
 * {@code StoreOutage} so that what reaches the application core is a domain signal and never a
 * {@code org.springframework.dao} type (constitution Principle V) carrying a driver's words.
 *
 * <p>{@code notification_id} is the caller's, minted before the insert and never reissued: it is
 * what notificationnotify keys its aggregate on, so a retry under a fresh identity would send a
 * second e-mail rather than reach the attempt it is retrying. The statements below therefore never
 * generate one and never overwrite one.
 */
public class RegisterNotificationRepository {

    private static final String BATCH_ID = "batchId";

    /** Statement 1 - one recipient's row, minted before its POST is made. */
    private static final String INSERT_NOTIFICATION = """
            INSERT INTO register_notification (
                notification_id, batch_id, email_address, recipient_name, template_name,
                template_id, status, response_code, sent_at, attempts)
            VALUES (
                :notificationId, :batchId, :emailAddress, :recipientName, :templateName,
                :templateId, :status, :responseCode, :sentAt, :attempts)
            """;

    private static final String SELECT_NOTIFICATION = """
            SELECT notification_id, batch_id, email_address, recipient_name, template_name,
                   template_id, status, response_code, sent_at, attempts
              FROM register_notification
            """;

    /**
     * Statement 2 - every recipient row of a batch, in the order the addresses read.
     *
     * <p>Ordered by address rather than by insertion, so a run report and a resend list a person
     * compares by eye come back the same way twice.
     */
    private static final String FIND_BY_BATCH_ID = SELECT_NOTIFICATION + """
             WHERE batch_id = :batchId
             ORDER BY email_address
            """;

    /**
     * Statement 3 - the recipient rows a resend attempts, which are the ones never accepted.
     *
     * <p>PENDING as well as FAILED, because the two are the same debt. A row is minted PENDING
     * before its POST and settled after it, so a run that stopped in between - the pod died, the
     * store blipped on the settlement, the listener's session rolled the delivery back after the
     * mark had committed - leaves a row that cannot say whether the e-mail was asked for. Reading
     * only the refusals left such a row untouched for ever: nothing re-requests it, the batch is
     * settled PARTIALLY_NOTIFIED against a team that can never be told, and {@code notify} cannot
     * be run again because it would mint a second row for every address
     * {@code UNIQUE (batch_id, email_address)} refuses. An ambiguous outcome is retried
     * (constitution's Idempotency bullet), and it is safe to retry because the retry goes out under
     * the identity the row already holds.
     *
     * <p>ACCEPTED is the only state left out, and it is the whole selection: a team that was told
     * is not told twice.
     *
     * <p><strong>It answers what is outstanding; it is not what the sending path reads.</strong>
     * {@code RegisterNotifierService} reads the whole batch through statement 2, because it has to
     * know which addresses the batch <em>holds</em> a row for as well as which of them are owed one:
     * a recipient with no row at all is owed an e-mail too, and this statement cannot see one. So
     * this is the read for asking a batch what is still outstanding - the {@code notify-register}
     * CLI's report and an operator's question - over one index and without the accepted rows.
     */
    private static final String FIND_UNSETTLED_BY_BATCH_ID = SELECT_NOTIFICATION + """
             WHERE batch_id = :batchId AND status <> 'ACCEPTED'
             ORDER BY email_address
            """;

    /**
     * Statement 4 - one recipient's row settled on what notificationnotify answered, and its
     * lifetime attempt total moved by the POSTs this call made whatever state the row is in.
     *
     * <p>The address, the batch and the template are not among the columns set. What was sent, and
     * to whom, is decided when the row is minted; a settlement may only say how that attempt ended.
     *
     * <p><strong>ACCEPTED is terminal at the row level, and the three {@code CASE} expressions are
     * what make it so.</strong> Two mechanisms can reach one generated batch at the same moment, so
     * a run can read a row as unsettled, POST for it, and only then find that the other run's POST
     * was accepted in between. An unconditional settlement would write FAILED over that ACCEPTED
     * row: the team that has been told reads as untold, the batch goes back to PARTIALLY_NOTIFIED,
     * and the resend that follows sends a register about children to a team that already has it. So
     * the status, the status line and the settlement instant keep the values the acceptance wrote,
     * and the caller is told what happened rather than left to read a row count. An ACCEPTED write
     * onto an ACCEPTED row is held off by the same expressions and is not a loss: the row already
     * says what that write was going to say, and re-stamping it would make it describe whichever
     * sender wrote last.
     *
     * <p><strong>The attempt tally is not conditional, and that is the whole difference from the
     * predicate this statement used to carry.</strong> {@code WHERE ... AND status <> 'ACCEPTED'}
     * fenced the tally along with the settlement, so a POST made in the very window the fence
     * exists for was left off the total - a row two notifiers POSTed for read as one notifier's
     * work, in the one situation where knowing otherwise matters. The POST was really made, and
     * what the column accumulates is the POSTs made for the row, so it is added whatever state the
     * row is in.
     *
     * <p><strong>And the total is computed here rather than by the caller.</strong> Two runs that
     * each read the row at nought and each write an absolute total both write the same number, so
     * one run's attempts are simply lost - a row POSTed for four times reads as two, and the count
     * support tells an exhausted budget from a broken route by is wrong in the direction that hides
     * work. What arrives is how many POSTs the call made; what is written is that added to whatever
     * the row holds at the moment of the write.
     *
     * <p><strong>The row is read under a lock in the same statement, and that read is the
     * answer.</strong> {@code RETURNING} sees the values a statement wrote, so the state the row was
     * in before it cannot be read off the update itself; the {@code held} term reads it, and
     * {@code FOR UPDATE} is what makes that reading the current one - a second settlement of the
     * same row waits there and then reads the version the first one committed, rather than
     * evaluating against a snapshot taken before it. So one round trip decides the fence, does the
     * arithmetic and says which of the three things it did.
     */
    private static final String UPDATE_NOTIFICATION = """
            WITH held AS (
                SELECT notification_id, status
                  FROM register_notification
                 WHERE notification_id = :notificationId
                   FOR UPDATE
            )
            UPDATE register_notification n
               SET status = CASE WHEN held.status = 'ACCEPTED'
                                 THEN n.status ELSE :status END,
                   response_code = CASE WHEN held.status = 'ACCEPTED'
                                        THEN n.response_code ELSE :responseCode END,
                   sent_at = CASE WHEN held.status = 'ACCEPTED'
                                  THEN n.sent_at ELSE :sentAt END,
                   attempts = n.attempts + :posts
              FROM held
             WHERE n.notification_id = held.notification_id
            RETURNING held.status <> 'ACCEPTED' AS applied
            """;

    /**
     * Statement 5 - one row's lifetime attempt total moved by the POSTs a lost claim spent, and
     * nothing else touched.
     *
     * <p>The settlement columns are not among the columns set, and that is the whole statement. A
     * cycle whose claim was taken over between its POST and the settlement that would have recorded
     * it may not say how the attempt ended - the notifier that now holds the batch is deriving the
     * same owed set from the same records, and a status written from here would be written over its
     * work - but the POST was really made, and what {@code attempts} accumulates is the POSTs made
     * for the row.
     *
     * <p>Fenced on the row's identity and on nothing else. Not on the claim, which has already
     * gone, and not on the row's status: an accepted row's total is moved by a POST as readily as a
     * pending row's is, which is the reading {@link #UPDATE_NOTIFICATION} stopped fencing its own
     * tally for. And the total is arithmetic over whatever the row holds at the moment of the write,
     * so two runs that each read the row at nought cannot each write the same total.
     *
     * <p>{@code RETURNING} says whether there was a row to add to, because a write that recorded an
     * attempt nowhere is a row this service posted under that the store no longer holds - the same
     * fault {@link NotificationSettlement#ABSENT} names, and not one to make silently.
     */
    private static final String TALLY_ATTEMPTS = """
            UPDATE register_notification
               SET attempts = attempts + :posts
             WHERE notification_id = :notificationId
            RETURNING notification_id
            """;

    /**
     * Statement 6 - the sends settled FAILED inside the window, oldest first.
     *
     * <p>Joined to {@code register_batch} for the court centre and the register day, and the join
     * is what makes this one statement rather than one per row: {@code RegisterNotification}
     * carries neither its batch's key nor an age, and looking each batch up separately would be
     * N+1 reads on precisely the morning the list is longest.
     *
     * <p><strong>{@code email_address} is deliberately not in the select list.</strong> It is the
     * one personal value in the table, the report never carries it, and a column that is never read
     * cannot be logged by accident - so there is no value to mask and none to forget to mask. A
     * failed send is identified by its notification id and its batch, which is what a resend needs
     * anyway.
     *
     * <p>Ordered oldest first, because the team that has been waiting longest is the one a
     * morning's resend starts with.
     */
    private static final String FAILED_BETWEEN = """
            SELECT n.notification_id, n.batch_id, n.status, n.response_code, n.attempts, n.sent_at,
                   b.court_centre_id, b.register_date,
                   extract(epoch from (now() - n.sent_at))::bigint AS age_seconds
              FROM register_notification n
              JOIN register_batch b ON b.batch_id = n.batch_id
             WHERE n.status = 'FAILED'
               AND n.sent_at >= :from
               AND n.sent_at < :to
             ORDER BY n.sent_at
            """;

    private final JdbcClient jdbcClient;

    /**
     * Creates the repository over the register store's connection.
     *
     * @param jdbcClient the register store's connection
     */
    public RegisterNotificationRepository(final JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Statement 1 - mints one recipient's row before its POST is made.
     *
     * <p>Made through {@code StoreOutage} like every other statement in this package, in the form
     * for a write the store may refuse: a lost {@code UNIQUE (batch_id, email_address)} reaches the
     * caller as {@link StoreRefusedRowException} carrying this repository's own words. Postgres
     * reports that violation with a detail line quoting the key it refused, and this key is an
     * e-mail address - a component that may never reach a log index (constitution Principle VII) -
     * so neither the driver's message nor the cause travels with it. The refusal itself has to
     * travel: the operator's resend and the outcome sink can derive the same row for one batch at
     * the same moment, and the one that loses the key reads back the row that won.
     *
     * @param notification the row, carrying the identity the POST goes out under
     * @throws StoreRefusedRowException where the batch already holds a row for this address
     */
    public void insert(final RegisterNotification notification) {
        StoreOutage.translatingWrite("mint a recipient's notification row",
                "the store refused a notification row for one recipient of batch "
                        + notification.batchId() + ", which "
                        + "register_notification_unique_recipient does when the row is already held",
                () -> settlement(jdbcClient.sql(INSERT_NOTIFICATION)
                        .param("notificationId", notification.notificationId())
                        .param(BATCH_ID, notification.batchId())
                        .param("emailAddress", notification.emailAddress())
                        .param("recipientName", notification.recipientName(), Types.VARCHAR)
                        .param("templateName", notification.templateName())
                        .param("templateId", notification.templateId()), notification)
                        .param("attempts", notification.attempts())
                        .update());
    }

    /**
     * Statement 2 - every recipient row of a batch, whatever state it is in.
     *
     * @param batchId the batch
     * @return its notification rows
     */
    public List<RegisterNotification> findByBatchId(final UUID batchId) {
        return StoreOutage.translating("read a batch's notification rows",
                () -> jdbcClient.sql(FIND_BY_BATCH_ID)
                        .param(BATCH_ID, batchId)
                        .query((rs, rowNumber) -> notification(rs))
                        .list());
    }

    /**
     * Statement 3 - the recipient rows of a batch that notificationnotify never accepted.
     *
     * <p>FAILED and PENDING alike: a refusal and an attempt that reached no verdict are the same
     * debt to the same team, and only the row's own identity makes re-requesting either of them
     * safe. A recipient the batch holds no row for is owed an e-mail as well and is not here to be
     * seen, which is why the sending path reads {@link #findByBatchId} and this answers what is
     * outstanding.
     *
     * @param batchId the batch
     * @return its unsettled notification rows, each under the identity it was first attempted with
     */
    public List<RegisterNotification> findUnsettledByBatchId(final UUID batchId) {
        return StoreOutage.translating("read a batch's outstanding notification rows",
                () -> jdbcClient.sql(FIND_UNSETTLED_BY_BATCH_ID)
                        .param(BATCH_ID, batchId)
                        .query((rs, rowNumber) -> notification(rs))
                        .list());
    }

    /**
     * Statement 4 - settles one recipient's row on what notificationnotify answered, and tallies
     * the POSTs this call made either way.
     *
     * <p>The attempts this call made are handed over as a count rather than as a total, and the
     * total the row reaches is the statement's business. What the column accumulates is the POSTs
     * made for the row and not the runs that made them, so what a caller knows is how many it
     * made; the number it should be added to is whatever the row says at the moment of the write.
     *
     * <p>The answer is what the statement did and never a read-back. An empty result is a row this
     * store does not hold, which the changed-row count this used to return could not tell from a
     * row another sender had already accepted - both changed nothing, and one of them means a POST
     * was tallied onto a team that has been told while the other means a row this service wrote has
     * gone.
     *
     * @param notification the row as it should now stand, carrying the identity it was minted under
     * @param posts        how many POSTs this call made for the row
     * @return what the statement did: the settlement applied, the POSTs tallied onto a row an
     *     acceptance had already made terminal, or no such row at all
     */
    public NotificationSettlement update(
            final RegisterNotification notification, final int posts) {
        return StoreOutage.translating("settle a recipient's notification row",
                () -> settlementOf(settlement(jdbcClient.sql(UPDATE_NOTIFICATION)
                        .param("notificationId", notification.notificationId()), notification)
                        .param("posts", posts)
                        .query(Boolean.class)
                        .optional()));
    }

    /**
     * Statement 5 - adds the POSTs a call made to one row's lifetime total, and settles nothing.
     *
     * <p>The write a notifier makes on its way out. A cycle whose claim was taken over between its
     * POST and the settlement that would have recorded it may not touch the settlement columns - the
     * notifier that now holds the batch is deriving the same owed set from the same records, and a
     * status written from here would be written over its work - but the POST was really made, and
     * what {@code attempts} accumulates is the POSTs made for the row. Leaving them off made a row
     * two notifiers posted for read as one notifier's work, in the one situation where knowing
     * otherwise matters.
     *
     * <p>So this statement is the tally without the settlement: {@code attempts = attempts + :posts}
     * and no other column, fenced on the row's identity and on nothing else. It is not fenced on the
     * claim, because the claim has already gone; it is not fenced on the row's status, because an
     * accepted row's total is moved by a POST as readily as a pending row's is (the reason
     * {@link #update} stopped fencing the tally); and the number added is this call's own, so two
     * runs computing a total from one read cannot each write the same one.
     *
     * @param notificationId the identity the POSTs were made under
     * @param posts          how many POSTs this call made for the row
     * @return whether this store holds a row under that identity, which is the same fault
     *     {@link NotificationSettlement#ABSENT} names: a row this service wrote and the store no
     *     longer has
     */
    public boolean tallyAttempts(final UUID notificationId, final int posts) {
        return StoreOutage.translating("tally the POSTs a lost claim made",
                () -> jdbcClient.sql(TALLY_ATTEMPTS)
                        .param("notificationId", notificationId)
                        .param("posts", posts)
                        .query(UUID.class)
                        .optional()
                        .isPresent());
    }

    /**
     * The report's NOTIFICATION_FAILED read: the sends settled FAILED inside the window.
     *
     * <p>One statement, joined to {@code register_batch} for the court centre and the register day
     * the entry names, and ordered oldest first. {@code email_address} is deliberately not among
     * the columns it selects.
     *
     * <p>Both ends, and the end exclusive: a Youth Offending Team that has been chased once has
     * been chased, and a send on the boundary of two windows would otherwise be named by both.
     *
     * @param from the window's start, inclusive
     * @param to   the window's end, exclusive
     * @return every refused or unanswered send settled inside it, oldest first
     */
    public List<FailedNotification> failedBetween(final Instant from, final Instant to) {
        return StoreOutage.translating("read the sends refused inside a window",
                () -> jdbcClient.sql(FAILED_BETWEEN)
                        .param("from", offsetOf(from))
                        .param("to", offsetOf(to))
                        .query((rs, rowNumber) -> failed(rs))
                        .list());
    }

    /**
     * The statement's own account of what it did, as the three answers a caller acts on.
     *
     * @param applied what the statement returned: whether the row was still unsettled when it ran,
     *                or nothing at all where this store holds no such row
     * @return the settlement, the tally alone, or no such row
     */
    private static NotificationSettlement settlementOf(final Optional<Boolean> applied) {
        return applied
                .map(settled -> settled
                        ? NotificationSettlement.APPLIED
                        : NotificationSettlement.ATTEMPTS_ONLY)
                .orElse(NotificationSettlement.ABSENT);
    }

    /**
     * The three columns an attempt's verdict writes, bound once for the two statements that write
     * them.
     *
     * <p>{@code attempts} is not among them: the insert states the count a minted row starts at and
     * a settlement states how many POSTs to add to whatever the row already holds, which are two
     * different numbers, so each of the two callers binds its own.
     *
     * <p>{@code response_code} is a typed null because an attempt can end with no status line at
     * all - a connect failure or a read timeout reaches no verdict - and a row carrying an invented
     * one would say an attempt was answered when nothing answered.
     *
     * <p><strong>{@code sent_at} is the settlement instant of every terminal attempt</strong>, an
     * acceptance and a refusal alike: what it records is when this service decided how the attempt
     * ended, not when notificationnotify accepted anything. So a FAILED row carries it too, and a
     * connect failure that reached no verdict is settled at the instant the run gave up on it. It
     * is typed for the other row shape, the one this column really is empty on: a minted row,
     * written PENDING before its POST, which has nothing to stamp yet.
     */
    private static JdbcClient.StatementSpec settlement(
            final JdbcClient.StatementSpec statement, final RegisterNotification notification) {
        return statement
                .param("status", notification.status().name())
                .param("responseCode", notification.responseCode(), Types.INTEGER)
                .param("sentAt", offsetOf(notification.sentAt()), Types.TIMESTAMP_WITH_TIMEZONE);
    }

    /**
     * One refused send as the report reads it, with the age the statement computed and no address.
     */
    private static FailedNotification failed(final ResultSet rs) throws SQLException {
        return new FailedNotification(
                rs.getObject("notification_id", UUID.class),
                rs.getObject("batch_id", UUID.class),
                rs.getObject("court_centre_id", UUID.class),
                rs.getObject("register_date", LocalDate.class),
                NotificationStatus.valueOf(rs.getString("status")),
                rs.getObject("response_code", Integer.class),
                rs.getInt("attempts"),
                instant(rs.getObject("sent_at", OffsetDateTime.class)),
                rs.getLong("age_seconds"));
    }

    private static RegisterNotification notification(final ResultSet rs) throws SQLException {
        return new RegisterNotification(
                rs.getObject("notification_id", UUID.class),
                rs.getObject("batch_id", UUID.class),
                rs.getString("email_address"),
                rs.getString("recipient_name"),
                rs.getString("template_name"),
                rs.getObject("template_id", UUID.class),
                NotificationStatus.valueOf(rs.getString("status")),
                rs.getObject("response_code", Integer.class),
                instant(rs.getObject("sent_at", OffsetDateTime.class)),
                rs.getInt("attempts"));
    }

    private static OffsetDateTime offsetOf(final Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(final OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
