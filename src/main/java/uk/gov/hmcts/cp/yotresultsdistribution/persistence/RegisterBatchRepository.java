package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletedBy;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;

/**
 * The {@code register_batch} table: one row per court centre per register day.
 *
 * <p>Separate from {@link ProcessedOutputRepository} because the two answer different questions. The
 * output rows say what was recorded for a hearing; this one says what was asked of the renderer for
 * a group of them, and it is the row a public event correlates back to. The progression leg had
 * neither, which is why a failed generation there left nothing behind to look at.
 *
 * <p>Written in the same idiom as {@link ProcessedOutputRepository}: hand-written SQL, one method
 * per statement, and the affected-row count is the decision.
 *
 * <p><strong>The single-table half of the batch's life.</strong> {@link JdbcRegisterStore} owns the
 * writes that have to move {@code processed_output} in the same statement - assembly and every
 * {@code mark} - because those are atomic or they are wrong. What is left is what the other
 * collaborators need and can do alone: the three in-flight reads the batch-age readings will be
 * taken from, the read by the identity every outcome is attributed by, the operations CLI's own
 * assembly, and the whole-row compare-and-set a caller that read a batch and decided about it
 * writes it back through.
 *
 * <p><strong>Three of those reads belong to a sweep of their own.</strong> The retired reconciler
 * took them on its way past; {@code batch/BatchAgeSweep} takes them now, on its own fixed delay
 * and under no lock, so {@code yotresultsdistribution_oldest_generating_age}, {@code _pending_age} and
 * {@code _generated_age} are refreshed by a reader whose only job that is. They outlived their old
 * caller because the readings are what FR-011 asks for, and a gauge that came and went with a
 * mechanism would be a gauge nobody could alert on across the change.
 *
 * <p><strong>A batch is read by its identity and by nothing else.</strong> There is no read by the
 * payload a batch was rendered from, because there is no caller for one: the sink finds a batch by
 * {@code sourceCorrelationId} alone and treats the payload as a cross-check on that. A lookup by payload would only ever be reached by an outcome whose own account of which
 * batch it is about was missing or wrong, and completing a night's registers on one of those is the
 * guess the correlation exists to make unnecessary.
 *
 * <p><strong>Every state change here is a compare-and-set through the state machine.</strong> The
 * caller names the state it read the batch in and the state it decided on; {@code BatchStatus}
 * refuses the moves the data-model diagram does not draw, and the state read is the predicate the
 * update carries. Insertion is restricted to PENDING for the same reason, which is where the two
 * writers of {@link #insert} both begin.
 *
 * <p><strong>The notification claim is the one thing here that is not a state change and not part
 * of the domain record.</strong> {@link #claimForNotification} and
 * {@link #releaseNotificationClaim} write two columns {@link RegisterBatch} does not carry, because
 * what a batch <em>is</em> does not include who is currently telling its recipients: a claim on the
 * domain record would be a component every caller of {@link #compareAndSet}'s whole-row write could
 * overwrite, which is the opposite of what a claim is for. It is also the one operation here that
 * needs a transaction, and the only reason this class holds a {@link TransactionOperations}.
 */
public class RegisterBatchRepository {

    private static final String BATCH_ID = "batchId";

    /**
     * The two states a batch is still in flight in, and so has not been completed by anything.
     *
     * <p>PENDING is a batch nothing has been asked of the renderer for; GENERATING is one whose
     * answer has not come back. Neither has an outcome for a mechanism to have learned, and
     * {@code register_batch_completed_by_shape_chk} says the same of the row - which is why these
     * two, and only these two, are what the run's stale-batch pass may give up on.
     */
    private static final Set<BatchStatus> UNFINISHED =
            Set.of(BatchStatus.PENDING, BatchStatus.GENERATING);

    /**
     * Statement 1 - a batch written whole, by a caller that already holds every fact about it.
     *
     * <p>The CLI's assembly, where {@code system_generated} is false, and the re-assembly of a
     * FAILED batch under a fresh identity. Nothing is defaulted here: a row this repository writes
     * says what its caller decided, which is what makes it possible to tell a run started by the
     * schedule from one a person started.
     *
     * <p>Both of those writers are assembling, so both start at PENDING and {@link #insert} admits
     * nothing else. A row inserted further along the machine is a batch that skipped the states it
     * should have been moved through, carrying stamps for events that never happened.
     */
    private static final String INSERT_BATCH = """
            INSERT INTO register_batch (
                batch_id, court_centre_id, court_centre_ou_code, court_house, register_date,
                file_name, payload_file_id, document_file_id, status, failure_reason, sdg_reason,
                system_generated, completed_by, assembled_at, requested_at, generated_at,
                notified_at, failed_at, attempts, supplement_of, supplement_index)
            VALUES (
                :batchId, :courtCentreId, :courtCentreOuCode, :courtHouse, :registerDate,
                :fileName, :payloadFileId, :documentFileId, :status, :failureReason, :sdgReason,
                :systemGenerated, :completedBy, :assembledAt, :requestedAt, :generatedAt,
                :notifiedAt, :failedAt, :attempts, :supplementOf, :supplementIndex)
            """;

    private static final String SELECT_BATCH = """
            SELECT batch_id, court_centre_id, court_centre_ou_code, court_house, register_date,
                   file_name, payload_file_id, document_file_id, status, failure_reason, sdg_reason,
                   system_generated, completed_by, assembled_at, requested_at, generated_at,
                   notified_at, failed_at, attempts, supplement_of, supplement_index
              FROM register_batch
            """;

    /** Statement 2 - one batch by the identity every downstream call correlates on. */
    private static final String FIND_BY_ID = SELECT_BATCH + " WHERE batch_id = :batchId";

    /**
     * Statement 3 - the batches whose outcome is overdue, oldest first.
     *
     * <p>Ordered so that a caller that cannot read all of them reads the ones that have been
     * waiting longest, which are the ones a Youth Offending Team is already missing a register for.
     * What is taken from it now is a reading - how long the oldest batch awaiting its render has
     * been waiting - and nothing is settled on the strength of it.
     */
    private static final String GENERATING_SINCE = SELECT_BATCH + """
             WHERE status = 'GENERATING' AND requested_at < :requestedBefore
             ORDER BY requested_at, batch_id
            """;

    /**
     * Statement 4 - the batches that never reached the renderer, oldest first.
     *
     * <p>The second of the three readings. A batch whose payload id was minted and whose
     * {@code markRequested} never landed - the pod died after the 202, or the store blipped on the
     * mark - stays PENDING: {@link #generatingSince(Instant)} does not see it, its rows are stamped
     * and so outside {@code activeUnbatched}, and the live-key index keeps every later re-share of
     * that key waiting behind it.
     *
     * <p><strong>It no longer stays there.</strong> The run's own stale-batch pass gives such a
     * batch back - staleness is state and age, not how far a batch got (FR-020) - so what this read
     * is for is the reading beside it: how long the oldest batch that never reached the renderer
     * has been stuck. {@code payload_file_id IS NOT NULL} is kept because this is the reading about
     * a render that was asked for, and the cutoff is read against {@code assembled_at} because
     * {@code requested_at} is exactly the column this batch never got.
     */
    private static final String PENDING_SINCE = SELECT_BATCH + """
             WHERE status = 'PENDING'
               AND payload_file_id IS NOT NULL
               AND assembled_at < :assembledBefore
             ORDER BY assembled_at, batch_id
            """;

    /**
     * Statement 5 - the batches holding a document nobody was told about, oldest first.
     *
     * <p>The third reading, and the third batch nothing else in the flow can see.
     * Notification follows {@code markGenerated} in one step of one code path, so a store that went
     * away in between - or a listener session that rolled the JMS delivery back after that mark had
     * already committed - leaves the batch at GENERATED with rows that were never settled.
     * {@link #generatingSince(Instant)} reads GENERATING and {@link #pendingSince(Instant)} reads
     * PENDING, so the one state that leaves a Youth Offending Team untold is the one state nothing
     * settles for - the stale-batch pass never touches a GENERATED batch at any age, because it
     * holds a document somebody is owed e-mails about.
     *
     * <p>The cutoff is read against {@code generated_at} because that is when the batch became the
     * notifying leg's to finish, and a batch whose document arrived a moment ago is one that leg is
     * still working through.
     */
    private static final String GENERATED_SINCE = SELECT_BATCH + """
             WHERE status = 'GENERATED' AND generated_at < :generatedBefore
             ORDER BY generated_at, batch_id
            """;

    /**
     * Statement 6 - the batch as it should now stand, if it still stands where the caller left it.
     *
     * <p>The whole mutable row, so a caller that read a batch, decided about it and writes it back
     * cannot leave half of its decision behind. The key and the assembly facts are not among the
     * columns set: what a batch is for was decided when it was assembled, and only where it has got
     * to changes afterwards.
     *
     * <p><strong>Fenced on the state the caller read.</strong> A whole-row write keyed on the batch
     * identity alone would let anything overwrite anything: a FAILED batch - terminal, and reported
     * to an operator as such - would be revived by a late outcome, keeping systemdocgenerator's
     * verdict about that identity attached to a batch whose registers have since been given back
     * and rendered again; and two runs deciding about one batch would each believe they had moved
     * it. The status the
     * caller read is therefore the predicate the update carries, and a batch that moved in between
     * changes no rows and is reported rather than overwritten - the same shape
     * {@link JdbcRegisterStore}'s {@code mark} statements are written in.
     */
    private static final String UPDATE_BATCH = """
            UPDATE register_batch
               SET payload_file_id = :payloadFileId,
                   document_file_id = :documentFileId,
                   status = :status,
                   failure_reason = :failureReason,
                   sdg_reason = :sdgReason,
                   completed_by = :completedBy,
                   requested_at = :requestedAt,
                   generated_at = :generatedAt,
                   notified_at = :notifiedAt,
                   failed_at = :failedAt,
                   attempts = :attempts
             WHERE batch_id = :batchId AND status = :expected
            """;

    /**
     * Statement 7 - every claim attempt for one batch, one at a time.
     *
     * <p>{@code hashtext} rather than the identity itself, because an advisory lock is keyed by a
     * {@code bigint} and a batch is keyed by a UUID. A hash collision costs two unrelated batches
     * one serialised claim attempt each, which is a moment of waiting and never a wrong answer: the
     * decision is statement 8's compare-and-set, and this only decides who gets to make it first.
     *
     * <p>Transaction-scoped, so it is released when the claiming transaction commits - before the
     * first POST. What survives that transaction is the claim on the row, which is the thing a
     * second notifier reads.
     */
    private static final String SERIALISE_NOTIFIERS = """
            SELECT true AS locked
              FROM (SELECT pg_advisory_xact_lock(hashtext(CAST(:batchKey AS text)))) AS taken
            """;

    /**
     * Statement 8 - the claim, taken where nobody holds one or the holder's lease has run out.
     *
     * <p>The status is not a predicate. A batch is notified out of GENERATED and re-notified out of
     * PARTIALLY_NOTIFIED, and an operator's resend is the second of those; fencing the claim on one
     * state would make the resend unable to take it, and the state machine is already the
     * predicate of the mark that settles the batch.
     */
    private static final String CLAIM_FOR_NOTIFICATION = """
            UPDATE register_batch
               SET notifying_since = now(),
                   notifier_token = :token
             WHERE batch_id = :batchId
               AND (notifying_since IS NULL
                    OR notifying_since < now() - CAST(:lease AS interval))
            """;

    /**
     * Statement 9 - whether this store holds the batch at all, asked when the claim was not taken.
     *
     * <p>The compare-and-set above changes no row for two unrelated reasons: another notifier holds
     * a live claim, or there is no such batch. Read inside the claim's own transaction and under its
     * own advisory lock, so the two readings are of one moment rather than of two - a batch
     * assembled between a failed claim and a later existence read would otherwise be answered
     * absent when it is merely somebody else's.
     *
     * <p>Asked only where the claim was refused, because that is the only answer it can change.
     */
    private static final String BATCH_EXISTS = """
            SELECT EXISTS (SELECT 1 FROM register_batch WHERE batch_id = :batchId) AS held
            """;

    /**
     * Statement 10 - the claim kept alive, by the notifier whose token it was taken under.
     *
     * <p>The ownership re-check and the lease extension are one statement because they are one
     * question asked at one moment: a notifier about to POST for a recipient, or about to write what
     * a POST answered, needs to know the claim is still its own and needs the lease to cover what it
     * is about to do. Two statements would leave a window between the answer and the work, which is
     * the window the whole claim exists to close.
     *
     * <p>Fenced on the token for the reason the release is: a renewal keyed on the batch alone would
     * let a notifier whose claim had already been taken over extend the claim of the notifier that
     * took it, and carry on posting believing the batch was still its own.
     *
     * <p>{@code now()} rather than an interval added to what the row holds, so the lease is dated by
     * the database exactly as the claim is - a JVM clock that had drifted would otherwise decide how
     * long a claim lives.
     */
    private static final String RENEW_NOTIFICATION_CLAIM = """
            UPDATE register_batch
               SET notifying_since = now()
             WHERE batch_id = :batchId
               AND notifier_token = :token
            """;

    /** Statement 11 - the claim given back, by the notifier whose token it was taken under. */
    private static final String RELEASE_NOTIFICATION_CLAIM = """
            UPDATE register_batch
               SET notifying_since = NULL,
                   notifier_token = NULL
             WHERE batch_id = :batchId
               AND notifier_token = :token
            """;

    /**
     * Statement 12 - every batch of one register date, in the order a listing reads them.
     *
     * <p>The read behind {@code list-batches --date D}, and the one statement here not asked by the
     * identity every outcome is correlated on: a support call is about a date, and the identities of
     * that date's batches are what the caller is asking to be told.
     *
     * <p>Court house then identity, which is what makes the output stable across two runs: a person
     * reading a national date reads it a court house at a time, and the identity breaks the tie a
     * date's several batches for one court house would otherwise leave to the planner. A court house
     * the hearing venue never named sorts last, which is where a row that cannot say where it sat
     * belongs in a listing read by court house - and it is written {@code NULLS LAST} rather than
     * left to be ascending's default, because the rule is this statement's to state and a reader
     * checking it against the javadoc should not have to know which way round a planner sorts nulls.
     */
    private static final String FIND_BY_REGISTER_DATE = SELECT_BATCH + """
             WHERE register_date = :registerDate
             ORDER BY court_house NULLS LAST, batch_id
            """;

    private static final String BATCH_KEY = "batchKey";
    private static final String TOKEN = "token";
    private static final String LEASE_PARAM = "lease";

    /**
     * The columns the report reads a batch through, which are not the columns the generation leg
     * reads it through.
     *
     * <p>Seven of the row's twenty-one, and {@code sdg_reason} is not among them. That column is
     * systemdocgenerator's own words about a document whose every defendant is a child, and
     * constitution Principle VII keeps free text this service did not write out of a line, a label,
     * an event and the CSV alike - so none of the four statements below reads it, and there is no
     * value for anything downstream to leak.
     *
     * <p>Each statement closes the list with its own age expression, because "waiting" means a
     * different moment in each of the four.
     */
    private static final String EXCEPTION_COLUMNS = """
            SELECT batch_id, court_centre_id, register_date, status, failure_reason, attempts,
            """;

    /**
     * Statement 10 - the batches nothing has been asked of the renderer for, oldest first.
     *
     * <p>Deliberately not {@link #PENDING_SINCE}, which serves the render-age reading and so admits
     * only the batches that minted a payload. The report is saying that a court centre's day has
     * been waiting, and a batch that never minted a payload has been waiting longest of all.
     */
    private static final String LATE_PENDING = EXCEPTION_COLUMNS + """
                   extract(epoch from (now() - assembled_at))::bigint AS age_seconds
              FROM register_batch
             WHERE status = 'PENDING'
               AND assembled_at < :assembledBefore
             ORDER BY assembled_at
            """;

    /** Statement 11 - the batches whose accepted render has not been answered, oldest first. */
    private static final String LATE_GENERATING = EXCEPTION_COLUMNS + """
                   extract(epoch from (now() - requested_at))::bigint AS age_seconds
              FROM register_batch
             WHERE status = 'GENERATING'
               AND requested_at < :requestedBefore
             ORDER BY requested_at
            """;

    /** Statement 12 - the batches holding a document nobody was told about, oldest first. */
    private static final String LATE_GENERATED = EXCEPTION_COLUMNS + """
                   extract(epoch from (now() - generated_at))::bigint AS age_seconds
              FROM register_batch
             WHERE status = 'GENERATED'
               AND generated_at < :generatedBefore
             ORDER BY generated_at
            """;

    /**
     * Statement 13 - the batches that ended inside the window, oldest first.
     *
     * <p>{@code failure_reason} is in the select list and {@code sdg_reason} is not, which is the
     * whole difference between a reason a support engineer can paste into a ticket and a sentence
     * another system wrote about somebody's document.
     */
    private static final String FAILED_BETWEEN = EXCEPTION_COLUMNS + """
                   extract(epoch from (now() - failed_at))::bigint AS age_seconds
              FROM register_batch
             WHERE status = 'FAILED'
               AND failed_at >= :from
               AND failed_at < :to
             ORDER BY failed_at
            """;

    private final JdbcClient jdbcClient;

    /**
     * The transaction the advisory lock and the claim's compare-and-set are taken in together.
     *
     * <p>The only thing here that needs one: everything else is a single statement, whose implicit
     * transaction is the whole of what it needs.
     */
    private final TransactionOperations transactions;

    /**
     * How long a notification claim stays live, measured from the last renewal, before another
     * notifier may take it over.
     */
    private final Duration notifierLease;

    /**
     * Creates the repository over the register store's connection.
     *
     * @param jdbcClient    the register store's connection
     * @param transactionOperations the transaction the claim's two statements are taken in together
     * @param notificationClaimLease how long a notification claim stays live, measured from the
     *                      last renewal: {@code yotresultsdistribution.notification.claim-lease}, the
     *                      notifying leg's own setting. Not {@code stale-after}, which answers a
     *                      different question - how long a batch may be awaiting its render before
     *                      the next run gives up on it is no bound at all on telling a batch's
     *                      recipients, whose cost is the number of Youth Offending Teams it is
     *                      addressed to times what notificationnotify makes of each of them. What
     *                      this has to cover is one recipient's turn, because
     *                      {@link #renewNotificationClaim(UUID, UUID)} is asked before every one
     */
    public RegisterBatchRepository(final JdbcClient jdbcClient,
            final TransactionOperations transactionOperations,
            final Duration notificationClaimLease) {
        this.jdbcClient = jdbcClient;
        this.transactions = transactionOperations;
        this.notifierLease = notificationClaimLease;
    }

    /**
     * Statement 1 - inserts a newly assembled batch.
     *
     * @param batch the batch, carrying the identity minted at assembly
     * @throws IllegalArgumentException if the batch does not start where a batch starts, or if it
     *                                  already names the mechanism that completed it
     */
    public void insert(final RegisterBatch batch) {
        if (batch.status() != BatchStatus.PENDING) {
            throw new IllegalArgumentException("a batch enters the table at PENDING and is moved "
                    + "from there; " + batch.batchId() + " was offered as " + batch.status());
        }
        unattributed(batch);
        mutable(jdbcClient.sql(INSERT_BATCH)
                .param(BATCH_ID, batch.batchId())
                .param("courtCentreId", batch.courtCentreId())
                .param("courtCentreOuCode", batch.courtCentreOuCode(), Types.VARCHAR)
                .param("courtHouse", batch.courtHouse(), Types.VARCHAR)
                .param("registerDate", batch.registerDate())
                .param("fileName", batch.fileName())
                .param("systemGenerated", batch.systemGenerated())
                .param("assembledAt", offsetOf(batch.assembledAt()),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("supplementOf", batch.supplementOf(), Types.OTHER)
                .param("supplementIndex", batch.supplementIndex()), batch)
                .update();
    }

    /**
     * Statement 2 - reads one batch by its identity.
     *
     * @param batchId the batch identity
     * @return the batch, or empty where this service recorded no such batch
     */
    public Optional<RegisterBatch> findById(final UUID batchId) {
        return jdbcClient.sql(FIND_BY_ID)
                .param(BATCH_ID, batchId)
                .query((rs, rowNumber) -> batch(rs))
                .optional();
    }

    /**
     * Statement 3 - the batches that have been GENERATING since before the given instant.
     *
     * @param requestedBefore the far edge of the reading's window
     * @return every batch whose outcome is overdue, oldest first
     */
    public List<RegisterBatch> generatingSince(final Instant requestedBefore) {
        return jdbcClient.sql(GENERATING_SINCE)
                .param("requestedBefore", offsetOf(requestedBefore))
                .query((rs, rowNumber) -> batch(rs))
                .list();
    }

    /**
     * Statement 4 - the batches that minted a payload before the given instant and got no further.
     *
     * @param assembledBefore the far edge of the reading's window, measured from assembly
     * @return every stale PENDING batch that minted a payload, oldest first
     */
    public List<RegisterBatch> pendingSince(final Instant assembledBefore) {
        return jdbcClient.sql(PENDING_SINCE)
                .param("assembledBefore", offsetOf(assembledBefore))
                .query((rs, rowNumber) -> batch(rs))
                .list();
    }

    /**
     * Statement 5 - the batches that have held a document since before the given instant.
     *
     * @param generatedBefore the far edge of the reading's window, measured from the document
     * @return every batch parked at GENERATED with nobody told, oldest first
     */
    public List<RegisterBatch> generatedSince(final Instant generatedBefore) {
        return jdbcClient.sql(GENERATED_SINCE)
                .param("generatedBefore", offsetOf(generatedBefore))
                .query((rs, rowNumber) -> batch(rs))
                .list();
    }

    /**
     * The report's BATCH_LATE read for a batch nothing has been asked of the renderer for.
     *
     * <p>A projection rather than {@link #pendingSince(Instant)}, and the two are different
     * questions. That read is the reading about a render that was asked for, so it admits only the
     * batches that minted a payload; this one is the report saying a court centre's day has been
     * waiting, and a batch that never minted a payload has been waiting longest of all.
     *
     * @param assembledBefore the cut-off, measured from assembly
     * @return every PENDING batch assembled before it, oldest first
     */
    public List<BatchException> latePending(final Instant assembledBefore) {
        return exceptions(LATE_PENDING, "assembledBefore", assembledBefore);
    }

    /**
     * The report's BATCH_LATE read for a batch whose render has not been answered.
     *
     * @param requestedBefore the cut-off, measured from the render request
     * @return every GENERATING batch requested before it, oldest first
     */
    public List<BatchException> lateGenerating(final Instant requestedBefore) {
        return exceptions(LATE_GENERATING, "requestedBefore", requestedBefore);
    }

    /**
     * The report's BATCH_LATE read for a batch holding a document nobody was told about.
     *
     * @param generatedBefore the cut-off, measured from the document
     * @return every GENERATED batch generated before it, oldest first
     */
    public List<BatchException> lateGenerated(final Instant generatedBefore) {
        return exceptions(LATE_GENERATED, "generatedBefore", generatedBefore);
    }

    /**
     * The report's BATCH_FAILED read: the batches that ended inside the window.
     *
     * <p>The downstream half's equivalent of a parked request. It carries the bounded
     * {@link BatchFailureReason} and never {@code sdg_reason}, which is not among the columns the
     * statement selects at all.
     *
     * <p>Both ends, and the end exclusive: a court centre's dead day belongs to one morning's
     * report, and a batch on the boundary of two windows would otherwise be chased twice.
     *
     * @param from the window's start, inclusive
     * @param to   the window's end, exclusive
     * @return every batch failed inside it, oldest first
     */
    public List<BatchException> failedBetween(final Instant from, final Instant to) {
        return StoreOutage.translating("read the batches that ended inside a window",
                () -> jdbcClient.sql(FAILED_BETWEEN)
                        .param("from", offsetOf(from))
                        .param("to", offsetOf(to))
                        .query((rs, rowNumber) -> exception(rs))
                        .list());
    }

    /**
     * Statement 6 - moves a batch from the state the caller read it in to the state it decided on.
     *
     * <p>The move is asked of {@link BatchStatus} before it is attempted, so the state machine is
     * the domain's and not this statement's, and a move nobody drew is refused where it is made
     * rather than discovered afterwards from a row that already changed. The state the caller read
     * is then the predicate: a batch some other run moved in between changes no rows, and this
     * answers false rather than letting the caller believe a transition that did not happen.
     *
     * @param batch    the batch as it should now stand; its status is the state moved to
     * @param expected the state the caller read the batch in, and the state the write is fenced on
     * @return whether a row changed
     * @throws IllegalArgumentException if the state moved to is still in flight and the batch names
     *                                  the mechanism that completed it
     * @throws IllegalStateException    if the state machine does not draw the move
     */
    public boolean compareAndSet(final RegisterBatch batch, final BatchStatus expected) {
        if (!expected.canTransitionTo(batch.status())) {
            throw new IllegalStateException("batch " + batch.batchId() + " may not move from "
                    + expected + " to " + batch.status());
        }
        unattributed(batch);
        return mutable(jdbcClient.sql(UPDATE_BATCH)
                .param(BATCH_ID, batch.batchId())
                .param("expected", expected.name()), batch)
                .update() > 0;
    }

    /**
     * Statements 7, 8 and 9 - claims the batch for notification, or answers why it could not.
     *
     * <p><strong>Up to three statements in one short transaction, and the transaction is the
     * point.</strong>
     * The advisory lock serialises every claim attempt for one batch, so the compare-and-set that
     * follows it is the only one running: two notifiers that arrived together are two attempts one
     * after the other rather than two reads of the same row, and the second of them sees the first
     * one's claim. The lock is transaction-scoped, so it is given back when this transaction
     * commits - which is before the first POST is made, and is why this is a claim rather than a
     * lock held across the cycle. The POSTs go to notificationnotify once per recipient; a
     * transaction open across those would hold a connection and a row lock for as long as another
     * service takes to answer.
     *
     * <p>The claim itself is the two columns, exactly as {@code processed_request}'s is: a
     * compare-and-set the database evaluates, admitting the batch that is unclaimed or whose claim
     * is past its lease, and the lease compared by the database against its own {@code now()}
     * rather than by this pod's clock against a stored timestamp.
     *
     * <p><strong>A claim that outlives its notifier is recoverable, which is what the lease is
     * for.</strong> A pod that died mid-notification left the claim behind, and a claim nothing can
     * ever take is a batch no resend could pick up - the state defect fix P1 is about, wearing a
     * different hat.
     *
     * <p><strong>And a compare-and-set that changed nothing is asked why.</strong> Nought rows means
     * another notifier holds a live claim, or it means this store holds no such batch, and the two
     * are not the same night: the first is an ordinary evening with the outcome sink and an
     * operator's resend both reaching one generated batch, and the second is a caller acting on a
     * correlation nothing was ever assembled under. Answering the first for the second put a lost
     * correlation into the reading a claim nobody can take is chased by. The existence read is made
     * inside this transaction, under this advisory lock, so the two answers are two readings of one
     * moment - a batch assembled between a failed claim and a later read would otherwise be answered
     * absent when it is merely somebody else's.
     *
     * @param batchId the batch to claim
     * @param token   the token this notifier claims under, minted fresh for the attempt
     * @return whether this notifier took the claim, another notifier holds one, or this store holds
     *     no such batch at all
     */
    public NotificationClaim claimForNotification(final UUID batchId, final UUID token) {
        return StoreOutage.translating("claim a batch for notification", () -> {
            final NotificationClaim claim = transactions.execute(oneTransaction -> {
                jdbcClient.sql(SERIALISE_NOTIFIERS)
                        .param(BATCH_KEY, batchId.toString())
                        .query(Boolean.class)
                        .single();
                return jdbcClient.sql(CLAIM_FOR_NOTIFICATION)
                        .param(BATCH_ID, batchId)
                        .param(TOKEN, token)
                        .param(LEASE_PARAM, lease())
                        .update() > 0
                        ? NotificationClaim.CLAIMED
                        : whyNot(batchId);
            });
            return claim == null ? NotificationClaim.ALREADY_CLAIMED : claim;
        });
    }

    /**
     * Statement 9 - why a claim's compare-and-set changed nothing, asked where it did not.
     *
     * @param batchId the batch the claim was refused for
     * @return contention where this store holds the batch, and no such batch where it does not
     */
    private NotificationClaim whyNot(final UUID batchId) {
        return Boolean.TRUE.equals(jdbcClient.sql(BATCH_EXISTS)
                .param(BATCH_ID, batchId)
                .query(Boolean.class)
                .single())
                ? NotificationClaim.ALREADY_CLAIMED
                : NotificationClaim.ABSENT;
    }

    /**
     * Statement 10 - keeps this notifier's claim alive, and says whether it is still this
     * notifier's.
     *
     * <p>Asked before every POST the cycle makes and before every write it makes about one, because
     * the two things it answers are wanted at exactly those moments: that the batch is still this
     * notifier's to tell, and that the lease covers what is about to be done to it. A notifier whose
     * claim has been taken over is told so by the same statement that would have extended it, and
     * stops.
     *
     * <p>No advisory lock, exactly as the release needs none: the token is the whole predicate and
     * only one token can be on the row.
     *
     * @param batchId the batch whose claim is being kept alive
     * @param token   the token the claim was taken under; a renewal under any other changes nothing
     * @return whether the claim is still this notifier's
     */
    public boolean renewNotificationClaim(final UUID batchId, final UUID token) {
        return StoreOutage.translating("renew a batch's notification claim",
                () -> jdbcClient.sql(RENEW_NOTIFICATION_CLAIM)
                        .param(BATCH_ID, batchId)
                        .param(TOKEN, token)
                        .update() > 0);
    }

    /**
     * Statement 11 - releases a notification claim this notifier holds.
     *
     * <p>Fenced on the token, so a notifier whose claim was reclaimed while it was working releases
     * nothing: the claim it would be giving back is the one the notifier that took it over is
     * relying on. No advisory lock is needed for this one - the token is the whole predicate, and
     * only one token can be on the row.
     *
     * @param batchId the batch to release
     * @param token   the token the claim was taken under; a release under any other changes nothing
     * @return whether the claim was this notifier's to release
     */
    public boolean releaseNotificationClaim(final UUID batchId, final UUID token) {
        return StoreOutage.translating("release a batch's notification claim",
                () -> jdbcClient.sql(RELEASE_NOTIFICATION_CLAIM)
                        .param(BATCH_ID, batchId)
                        .param(TOKEN, token)
                        .update() > 0);
    }

    /**
     * The lease as Postgres reads an interval, which is what the claiming statement compares.
     *
     * <p>In milliseconds rather than seconds. The deployed lease is minutes and would read the same
     * either way, but a suite's lease is a fraction of a second and truncating that to whole
     * seconds makes it nought - which is a lease that has always already expired, so every claim is
     * granted and the statement appears to do nothing at all.
     */
    private String lease() {
        return notifierLease.toMillis() + " milliseconds";
    }

    /**
     * Statement 12 - every batch of one register date, in the order a listing reads them.
     *
     * <p>The read behind {@code list-batches --date D}, and the one read here not asked by the
     * identity a batch is correlated on: a support call is about a date, and the identities of that
     * date's batches are what the caller is asking to be told.
     *
     * <p>The order is the statement's rather than the listing's, which is what makes the output
     * stable across two runs. Court house then identity: a person reading a national date reads it
     * a court house at a time, and the identity breaks the tie a date's supplementary batches would
     * otherwise leave to the planner.
     *
     * @param registerDate the register date whose batches are wanted
     * @return every batch recorded for that date, court house then identity; empty where the date
     *         has none
     */
    public List<RegisterBatch> findByRegisterDate(final LocalDate registerDate) {
        return jdbcClient.sql(FIND_BY_REGISTER_DATE)
                .param("registerDate", registerDate)
                .query((rs, rowNumber) -> batch(rs))
                .list();
    }

    /**
     * A batch that has not finished has been completed by nothing, refused before either write.
     *
     * <p>Both statements here bind {@code completed_by} from the batch they are handed - the insert
     * because a caller assembling by hand states every column, and the update because it is a
     * whole-row write - so both can carry an attribution onto a state that has no outcome yet. The
     * store cannot: assembly never names the column and {@code markRequested} does not set it, so
     * this is the path the shape check exists behind, and refusing it here is what turns a
     * constraint violation at the driver into a refusal that names the batch and the pair.
     *
     * <p>The message names the batch, its state and the mechanism, and nothing else; none of the
     * three is about a document whose every defendant is a child (constitution Principle VII).
     */
    private static void unattributed(final RegisterBatch batch) {
        if (UNFINISHED.contains(batch.status()) && batch.completedBy() != null) {
            throw new IllegalArgumentException("batch " + batch.batchId() + " is " + batch.status()
                    + ", which nothing has reported an outcome for, and named "
                    + batch.completedBy());
        }
    }

    /**
     * The columns that change after assembly, bound once for the two statements that write them.
     *
     * <p>Every one of them is nullable and every one is typed: an untyped {@code null} leaves the
     * driver to guess a type from a parameter it can see nothing about, which Postgres refuses
     * rather than guesses.
     */
    private static JdbcClient.StatementSpec mutable(
            final JdbcClient.StatementSpec statement, final RegisterBatch batch) {
        return statement
                .param("payloadFileId", batch.payloadFileId(), Types.OTHER)
                .param("documentFileId", batch.documentFileId(), Types.OTHER)
                .param("status", batch.status().name())
                .param("failureReason", name(batch.failureReason()), Types.VARCHAR)
                .param("sdgReason", batch.sdgReason(), Types.VARCHAR)
                .param("completedBy", name(batch.completedBy()), Types.VARCHAR)
                .param("requestedAt", offsetOf(batch.requestedAt()),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("generatedAt", offsetOf(batch.generatedAt()),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("notifiedAt", offsetOf(batch.notifiedAt()),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("failedAt", offsetOf(batch.failedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("attempts", batch.attempts());
    }

    /**
     * The four report reads, which differ only in their statement and their one cut-off.
     *
     * <p>Written once because they are one shape: the report is asking each of the four stages the
     * same question, and four copies of the binding would be four places for the projection to
     * drift apart.
     *
     * <p>Translated like every other statement in this class. A morning the database is away must
     * reach the report as this service's own signal, under a bounded reason it can count the run
     * failed by - not as a {@code org.springframework.dao} type the application layer never
     * classified (Principle V).
     */
    private List<BatchException> exceptions(final String sql, final String parameter,
            final Instant cutoff) {
        return StoreOutage.translating("read the batches the report asks about",
                () -> jdbcClient.sql(sql)
                        .param(parameter, offsetOf(cutoff))
                        .query((rs, rowNumber) -> exception(rs))
                        .list());
    }

    /**
     * One batch as the report reads it, with the age its own statement computed.
     *
     * <p>There is no {@code sdg_reason} to read and no component to put one in, which is the
     * containment rather than a rule somebody has to remember.
     */
    private static BatchException exception(final ResultSet rs) throws SQLException {
        return new BatchException(
                rs.getObject("batch_id", UUID.class),
                rs.getObject("court_centre_id", UUID.class),
                rs.getObject("register_date", LocalDate.class),
                BatchStatus.valueOf(rs.getString("status")),
                failureReason(rs.getString("failure_reason")),
                rs.getInt("attempts"),
                rs.getLong("age_seconds"));
    }

    private static RegisterBatch batch(final ResultSet rs) throws SQLException {
        return new RegisterBatch(
                rs.getObject("batch_id", UUID.class),
                rs.getObject("court_centre_id", UUID.class),
                rs.getString("court_centre_ou_code"),
                rs.getString("court_house"),
                rs.getObject("register_date", LocalDate.class),
                rs.getString("file_name"),
                rs.getObject("payload_file_id", UUID.class),
                rs.getObject("document_file_id", UUID.class),
                BatchStatus.valueOf(rs.getString("status")),
                failureReason(rs.getString("failure_reason")),
                rs.getString("sdg_reason"),
                rs.getBoolean("system_generated"),
                completedBy(rs.getString("completed_by")),
                instant(rs.getObject("assembled_at", OffsetDateTime.class)),
                instant(rs.getObject("requested_at", OffsetDateTime.class)),
                instant(rs.getObject("generated_at", OffsetDateTime.class)),
                instant(rs.getObject("notified_at", OffsetDateTime.class)),
                instant(rs.getObject("failed_at", OffsetDateTime.class)),
                rs.getInt("attempts"),
                rs.getObject("supplement_of", UUID.class),
                rs.getInt("supplement_index"));
    }

    private static BatchFailureReason failureReason(final String value) {
        return value == null ? null : BatchFailureReason.valueOf(value);
    }

    private static CompletedBy completedBy(final String value) {
        return value == null ? null : CompletedBy.valueOf(value);
    }

    private static String name(final Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static OffsetDateTime offsetOf(final Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(final OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
