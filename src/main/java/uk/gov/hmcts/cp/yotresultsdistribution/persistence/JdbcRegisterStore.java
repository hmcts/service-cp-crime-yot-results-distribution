package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.application.NotificationSummary;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RecordOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RecordedCompletion;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterStore;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ReleasedBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.application.StaleReleaseOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.application.StaleReleaseProgress;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletedBy;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtCentreDay;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GuardDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedRegisterSummary;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotRecordedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotReleasedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterRecord;

/**
 * The register store over this service's own Postgres.
 *
 * <p>The adapter that owns the write-time supersession and the batch-scoped {@code mark} statements,
 * written in the same idiom as the rest of {@code persistence}: hand-written SQL, one method per
 * statement, and the affected-row count is the decision.
 *
 * <p><strong>Every write that spans two tables is one statement.</strong> The recording and the
 * supersession it causes, and each {@code mark} and the row flip it implies, are written as a single
 * statement with data-modifying {@code WITH} clauses rather than as two statements inside a
 * transaction. One statement is atomic on its own, and the clauses see one snapshot: the
 * supersession therefore cannot see the row being inserted beside it, so a recording can never
 * supersede itself (research §8).
 *
 * <p><strong>Three of them take a transaction as well, and for three different reasons.</strong>
 * Assembly's statement is one statement too, but the decision about it is not in the statement:
 * whether the batch is the batch that was asked for is a count compared in Java, and under
 * autocommit that comparison happens after the batch row and the stamps are already committed. A
 * refusal would then leave a PENDING batch holding {@code idx_register_batch_live_key} for that
 * court centre and day, and the day would never be rendered by any later run. So assembly runs
 * inside a transaction and the refusal rolls it back: the batch is assembled or it never existed.
 * Recording runs inside one because it may be issued more than once - a re-share that loses the
 * race for its key is refused by {@code idx_output_active_register_key} and tried again on a fresh
 * snapshot - and the supersession the refused attempt had already made has to go with it. Each
 * stale batch's release runs inside one for a reason that is not about its own statement at all,
 * which is one statement like the rest: the boundary is {@code REQUIRES_NEW}, so that a caller
 * already inside a transaction cannot fold every court centre's release into one that a single
 * refusal aborts (FR-003a).
 *
 * <p>The clauses are chained through their output, which is what orders them: Postgres does not
 * otherwise say which clause runs first. In the recording statement {@code recorded} counts
 * {@code replaced}'s rows, because the row being replaced holds the key the insert is about to take
 * and has to be out of the index before the insert asks for it; in {@code mark generated} and
 * {@code mark notified}, {@code flipped} reads {@code generated} through {@code FROM}, because the
 * rows that move are the rows of the batch the update just settled.
 *
 * <p><strong>A recording and its command's completion are one transaction.</strong> The completion
 * is a second statement against a second table and it belongs to the caller, not to this class, so
 * the commit boundary is the only place the two can be made one thing: the caller hands the
 * completion over and it is issued inside the recording's transaction, through a client over the
 * same {@code DataSource} this one issues against. Neither survives without the other, so there is
 * no moment in which a register stands against a request the broker will deliver again.
 *
 * <p><strong>Recording is idempotent on the command's own key even so.</strong> A delivery can
 * still arrive for a command this store has recorded - the transaction committed and the broker
 * never learned the message was settled - so the recording statement is preceded, inside the same
 * transaction, by a read of {@code (source, request_id)}: a command that has been recorded before
 * is answered with the row it already wrote, exactly as 001's POST path answers a re-claim through
 * {@code ON CONFLICT (source, request_id)}. The two unique
 * keys a recording can meet are told apart for the same reason - only
 * {@code idx_output_active_register_key} is a lost race worth retrying, and
 * {@code processed_output_unique_request} is this command arriving twice. Each is recognised by
 * name and nothing is recognised by elimination: a refusal that is neither of them is a constraint
 * this recorder cannot act on, and it is raised as a classified non-transient failure.
 *
 * <p><strong>Every {@code mark} is fenced on the status it read.</strong> The permitted moves belong
 * to {@link BatchStatus#canTransitionTo(BatchStatus)} and are asked there rather than re-encoded as
 * SQL predicates, so there is one state machine; the status that answered is then the predicate the
 * update carries, and a batch some other run moved in between changes no rows and is reported rather
 * than overwritten.
 */
public class JdbcRegisterStore implements RegisterStore {

    /** The zone the register day is the date part of, and the only one this service batches in. */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static final String DIGEST_ALGORITHM = "SHA-256";

    /** The one statement a fenced batch write is expected to change. */
    private static final long ONE_BATCH = 1;

    /**
     * How many times a recording that lost the race for its key re-reads and tries again.
     *
     * <p>Three, and bounded rather than open-ended for the reason every retry in this service is
     * bounded: a hearing being re-shared faster than the store can record it is a producer to look
     * at, and a loop that never gives up would hold a broker thread against it for as long as it
     * lasted. Each attempt loses only to a re-share that <em>committed</em> in between, so three of
     * them is already two more collisions than the race the invariant exists for.
     */
    private static final int RECORD_ATTEMPTS = 3;

    /** How every refusal in this class names the batch it is about, and the only thing it names. */
    private static final String BATCH = "batch ";

    /** V1's key on the request a row is evidence about: one output row per command, ever. */
    private static final String COMMAND_KEY = "processed_output_unique_request";

    /** V3's key on the day's active register: one RECORDED, unsuperseded, unbatched row per key. */
    private static final String ACTIVE_ROW_KEY = "idx_output_active_register_key";

    /**
     * The two kinds of rule a release refused by something it cannot retry is reported under.
     *
     * <p>A classification and not a constraint name, because the name is only in the driver's
     * message and that message is where a refused row's values are - see
     * {@link #unaccountedForRelease(UUID, DataIntegrityViolationException)}. Two values, both
     * written here, so a reader is told whether a key or a plain rule said no without a word of
     * the store's own travelling with it.
     */
    private static final String UNACCOUNTED_KEY = "a unique key";

    /** The other one: a CHECK, a foreign key, a NOT NULL - anything that is no unique index. */
    private static final String UNACCOUNTED_RULE = "an integrity rule that is no key";

    /** The four statuses the recorder writes, and the only ones a recorded register can be in. */
    private static final Set<String> RECORDER_STATUSES =
            Set.of("RECORDED", "SUPERSEDED", "GENERATED", "NOTIFIED");

    private static final String BATCH_ID = "batchId";
    private static final String OUTPUT_ID = "outputId";
    private static final String SOURCE = "source";
    private static final String REQUEST_ID = "requestId";
    private static final String SUPERSEDED_OUTPUT_ID = "superseded_output_id";
    private static final String OUTPUT_IDS = "outputIds";
    private static final String EXPECTED = "expected";

    /** The bound parameter naming the file-service id a batch's payload is written under. */
    private static final String PAYLOAD_FILE_ID = "payloadFileId";
    private static final String COURT_CENTRE_ID = "courtCentreId";
    private static final String REGISTER_DATE = "registerDate";

    /** The two key columns every row view reads back, named once for all of them. */
    private static final String COURT_CENTRE_ID_COLUMN = "court_centre_id";
    private static final String REGISTER_DATE_COLUMN = "register_date";
    private static final String REGISTER_TIME = "registerTime";
    private static final String HEARING_ID = "hearingId";

    /**
     * Statement 0 - the register this command already has, where it has been delivered before.
     *
     * <p>Read on the recording transaction's own snapshot and before anything is written, because a
     * redelivery is not a second register: the command is the same command, and the row it wrote the
     * first time is the answer to it. Without this read the insert meets {@value #COMMAND_KEY} and
     * the store, which cannot tell that refusal from the V3 key race, would fail a command whose
     * register is recorded and active and would go on failing it until the broker parked it.
     *
     * <p>The row it superseded comes back with it, so a redelivery is answered exactly as the
     * delivery that recorded it was - and it is asked for as <strong>the row this recording
     * replaced</strong> rather than as the row naming it, because more than one row can name it and
     * this store writes them itself. Statement 1 records an <em>earlier</em> share that arrives
     * after the register it belongs behind as SUPERSEDED against that register, so both the row the
     * later share replaced and the late arrival that never displaced it carry the same
     * {@code superseded_by}. A read of "the row naming this one" would find two and fail, on a
     * command whose register is recorded and active, every time the broker delivered it.
     *
     * <p>The two are told apart by <em>when</em> the supersession happened. Statement 1 supersedes
     * and inserts in one statement, so the row this recording replaced carries a
     * {@code superseded_at} equal to this row's own {@code created_at}, to the microsecond; a row
     * that recorded itself SUPERSEDED against this one stamped both of its own timestamps in its
     * own transaction, later. The scalar subquery stays scalar for the reason statement 1's is: it
     * answers {@code NULL} where nothing was superseded and <em>fails</em> where two rows were
     * superseded by one recording, which no single statement can produce and is a key that had
     * genuinely lost the invariant.
     */
    private static final String RECORDED_REGISTER = """
            SELECT recorded.output_id,
                   recorded.status,
                   (SELECT replaced.output_id
                      FROM processed_output replaced
                     WHERE replaced.superseded_by = recorded.output_id
                       AND replaced.superseded_at = recorded.created_at) AS superseded_output_id
              FROM processed_output recorded
             WHERE recorded.source = :source
               AND recorded.request_id = :requestId
            """;

    /**
     * Statement 1 - record this hearing's register, superseding the one it replaces.
     *
     * <p>{@code incumbent} is the hearing's active row for the day, read on the statement's own
     * snapshot: RECORDED, unsuperseded and <strong>unbatched</strong>. The last of those three is
     * what leaves a row that is already on its way to a PDF alone - systemdocgenerator has been
     * handed a payload built from it and an event will arrive naming that batch, so rewriting it
     * would take a register out of a document that already contains it.
     *
     * <p>The two orderings are written out rather than assumed. A re-share that is <em>later</em>
     * than the incumbent supersedes it and is recorded RECORDED; one that is <em>earlier</em> - a
     * redelivery that overtook the register it belongs behind - does not displace the later register
     * and is recorded SUPERSEDED against it. Either way the key keeps exactly one active row, which
     * is the invariant the whole batch half is written against; a predicate that only handled the
     * ordinary direction would leave two.
     *
     * <p><strong>The {@code <=} is one reading of a total order, and statements 9 and 9a read the
     * same order the other way.</strong> It is what makes a register that arrives later the current
     * one where the two instants are equal - the estate sets {@code register_time}, so two shares of
     * one hearing can carry the same one - and "arrives later" is exactly what the database clock
     * already wrote on the row: {@code created_at}, which V1 defaults to {@code now()} and V2
     * backfilled {@code register_time} from for the pre-002 rows. The persisted order over a key's
     * registers is therefore {@code (register_time, created_at, output_id)}, the identity being the
     * last deterministic tie-break for two rows written inside one clock tick, and the successor
     * search both release statements make is that triple. This predicate keeps its {@code <=}: it is
     * the same rule asked of one incumbent rather than read as a ranking.
     *
     * <p><strong>The supersession runs before the insert, and that order is the statement's to
     * keep.</strong> {@code idx_output_active_register_key} (V3) admits one active row per key, and
     * the row being replaced still holds that key until the update takes it out of the index: an
     * insert issued first would collide with the register it is replacing, on the ordinary
     * single-threaded re-share. So {@code replaced} is chained ahead of {@code recorded} - the
     * insert's source counts {@code replaced}'s rows, which cannot be counted until every one of
     * its updates has been made - and the supersession names the new row through {@code :outputId},
     * which the caller minted, rather than through the insert's {@code RETURNING}. The foreign key
     * to it is satisfied by the end of the statement, which is when Postgres checks it.
     *
     * <p>Reversing the chain costs nothing that research §8 asked for: {@code incumbent} is a read
     * of rows that already existed on this statement's snapshot, so the supersession still cannot
     * see the row being inserted beside it and a recording still cannot supersede itself.
     *
     * <p>The scalar subquery is deliberate: it answers {@code NULL} where there is no incumbent and
     * <em>fails</em> where there is more than one, so a key that had already lost the invariant is
     * reported rather than silently added to.
     *
     * <p><strong>{@code now()} is read once here, and statement 0 depends on that.</strong> The
     * supersession's {@code superseded_at} and the insert's {@code created_at} are the same
     * transaction timestamp, which is how a redelivery tells the row this recording replaced from
     * the rows that later came to name it. A rewrite that timed either of them differently would
     * have to give statement 0 another way to ask the question.
     */
    private static final String RECORD_REGISTER = """
            WITH incumbent AS (
                SELECT output_id, register_time
                  FROM processed_output
                 WHERE hearing_id = :hearingId
                   AND court_centre_id = :courtCentreId
                   AND register_date = :registerDate
                   AND status = 'RECORDED'
                   AND superseded_at IS NULL
                   AND batch_id IS NULL
            ), replaced AS (
                UPDATE processed_output superseded
                   SET status = 'SUPERSEDED',
                       superseded_at = now(),
                       superseded_by = :outputId,
                       updated_at = now()
                 WHERE superseded.output_id IN (SELECT output_id
                                                  FROM incumbent
                                                 WHERE register_time <= :registerTime)
                RETURNING superseded.output_id
            ), recorded AS (
                INSERT INTO processed_output (
                    output_id, source, request_id, court_centre_id, court_centre_ou_code,
                    register_date, file_name, status, request_digest, document, hearing_id,
                    hearing_date, court_house, register_time, defendant_type, recorded_flag_state,
                    superseded_at, superseded_by, created_at, updated_at)
                SELECT
                    :outputId, :source, :requestId, :courtCentreId, :courtCentreOuCode,
                    :registerDate, :fileName,
                    CASE WHEN later.output_id IS NULL THEN 'RECORDED' ELSE 'SUPERSEDED' END,
                    :digest, CAST(:document AS jsonb), :hearingId, :hearingDate, :courtHouse,
                    :registerTime, :defendantType, :flagState,
                    CASE WHEN later.output_id IS NULL THEN NULL ELSE now() END,
                    later.output_id, now(), now()
                  FROM (SELECT (SELECT output_id
                                  FROM incumbent
                                 WHERE register_time > :registerTime) AS output_id,
                               (SELECT count(*) FROM replaced) AS superseded_rows) later
                RETURNING output_id
            )
            SELECT (SELECT output_id FROM replaced) AS superseded_output_id
            """;

    /**
     * The four predicates of statement 2, written once because two readers ask them.
     *
     * <p>The nightly job asks for the registers it may pick up and the exception report asks which
     * of them the last scheduled run left behind. Two spellings of "RECORDED, unsuperseded,
     * unbatched and recorded while the flag was ON" are two answers waiting to disagree, and the
     * one they would disagree about is the fourth: a register recorded while the flag was not ON
     * may already have been sent by the legacy, and a report that named it would send a support
     * engineer after a register that is not missing.
     */
    private static final String ACTIVE_UNBATCHED_PREDICATE = """
             WHERE status = 'RECORDED'
               AND superseded_at IS NULL
               AND batch_id IS NULL
               AND recorded_flag_state = 'ON'
            """;

    /**
     * Statement 2 - the registers the nightly job may pick up.
     *
     * <p>Four predicates, and the fourth is the one that is easy to forget and expensive to get
     * wrong: a register recorded while the cutover flag was not ON may already have been sent by the
     * legacy, and batching it would send a second copy of the same day's register to the same Youth
     * Offending Team (research §12).
     *
     * <p>Oldest first, by the register instant. The batch takes its file name from the first record,
     * so the order the rows come back in is part of what the document is called.
     */
    private static final String ACTIVE_UNBATCHED = """
            SELECT output_id, hearing_id, hearing_date, court_centre_id, register_date,
                   register_time, file_name, defendant_type, recorded_flag_state, document
              FROM processed_output
            """ + ACTIVE_UNBATCHED_PREDICATE + """
             ORDER BY register_time, output_id
            """;

    /**
     * Statement 2a - the same registers, older than a cut-off, as a projection carrying an age.
     *
     * <p>The report's fourth BATCH_LATE source. It is a projection rather than
     * {@code activeUnbatched()} filtered in the JVM for the reason every other age in that report
     * is computed by the database: an age derived here from a stored timestamp is the cross-clock
     * comparison V1's header comment forbids, and it would leave one report carrying two kinds of
     * age. The document is not selected, because nothing about a late register needs reading.
     *
     * <p>The cut-off is the most recent scheduled generation run, which the caller computed and
     * passed in; a boundary a caller chose is a statement of what was asked for rather than a
     * comparison of two clocks.
     *
     * <p>Ordered on the same two columns {@code ACTIVE_UNBATCHED} orders on, and for the same
     * reason: a court centre's registers are recorded inside one microsecond often enough that
     * {@code register_time} alone is no order at all, so without the tie-break two runs of one
     * report list the same morning two ways and this read stops agreeing with the one it shares a
     * predicate with.
     */
    private static final String RECORDED_UNBATCHED_BEFORE = """
            SELECT output_id, hearing_id, court_centre_id, register_date, register_time,
                   extract(epoch from (now() - register_time))::bigint AS age_seconds
              FROM processed_output
            """ + ACTIVE_UNBATCHED_PREDICATE + """
               AND register_time < :recordedBefore
             ORDER BY register_time, output_id
            """;

    /**
     * Statement 3 - the registers one batch was assembled from.
     *
     * <p>By the batch identity rather than by the court centre and day the batch is about, which is
     * the predicate {@code mark generated} moves rows under asked in the other direction: the stamp
     * is the moment the batch became a fact, and a read that went back to the grouping would be
     * rendering what was true before it.
     *
     * <p>The assembly order, because it is part of what the document is: the first record names the
     * file and the render payload is progression's array of documents in the order the batch holds
     * them.
     */
    private static final String BATCH_REGISTERS = """
            SELECT output_id, hearing_id, hearing_date, court_centre_id, register_date,
                   register_time, file_name, defendant_type, recorded_flag_state, document
              FROM processed_output
             WHERE batch_id = :batchId
             ORDER BY register_time, output_id
            """;

    /**
     * Statement 4 - group a court centre's day into a batch and stamp it onto the rows.
     *
     * <p>The batch is inserted before the stamp because {@code processed_output.batch_id} carries a
     * foreign key to it, and both are one statement because a batch with no rows would hold the
     * partial unique key for that court centre and day against every later run.
     *
     * <p><strong>What the caller decides and what the row still reads for itself.</strong> The
     * identity, the file name, the trigger source and the supplementary link are bound parameters,
     * because they are the assembler's decisions and it is the only thing that has seen the key's
     * history: a statement that minted an identity and copied a file name off the first row would
     * be deciding all four again at the moment the rows are stamped, and would have no way at all
     * to write {@code supplement_of} - there is nothing in a register row to derive it from. What
     * is still selected from the first row is what only the row knows: the OU code, the court house
     * and the register date, which {@link RegisterRecord} does not carry.
     *
     * <p>The stamp repeats the active-unbatched predicates. Between the read and the write a row can
     * have been superseded by a re-share or stamped by another run, and a batch that quietly
     * contained fewer rows than it was asked for would render a register missing a hearing nobody
     * could name - so the count comes back, the caller refuses it, and the transaction the whole
     * thing runs in takes the batch row and the partial stamps back out with the refusal.
     */
    private static final String ASSEMBLE_BATCH = """
            WITH assembled AS (
                INSERT INTO register_batch (
                    batch_id, court_centre_id, court_centre_ou_code, court_house, register_date,
                    file_name, status, system_generated, assembled_at, attempts,
                    supplement_of, supplement_index)
                SELECT :batchId, first_row.court_centre_id, first_row.court_centre_ou_code,
                       first_row.court_house, first_row.register_date, :fileName,
                       'PENDING', :systemGenerated, now(), 0,
                       :supplementOf, :supplementIndex
                  FROM processed_output first_row
                 WHERE first_row.output_id = :firstOutputId
                RETURNING batch_id, court_centre_id, court_centre_ou_code, court_house,
                          register_date, file_name, system_generated, assembled_at,
                          supplement_of, supplement_index
            ), stamped AS (
                UPDATE processed_output waiting
                   SET batch_id = assembled.batch_id, updated_at = now()
                  FROM assembled
                 WHERE waiting.output_id IN (:outputIds)
                   AND waiting.status = 'RECORDED'
                   AND waiting.superseded_at IS NULL
                   AND waiting.batch_id IS NULL
                RETURNING waiting.output_id
            )
            SELECT assembled.batch_id, assembled.court_centre_id, assembled.court_centre_ou_code,
                   assembled.court_house, assembled.register_date, assembled.file_name,
                   assembled.system_generated, assembled.assembled_at,
                   assembled.supplement_of, assembled.supplement_index,
                   (SELECT count(*) FROM stamped) AS stamped_rows
              FROM assembled
            """;

    /**
     * Statement 4a - every batch already recorded for the keys a run holds registers for.
     *
     * <p>What the supplementary rule is decided from (design Q27). Whatever state each batch
     * reached, because both halves of the rule need the whole history: a key with a batch still
     * PENDING, GENERATING or GENERATED is left waiting, and a key whose batches are all terminal may
     * be followed by a supplement at the next index up.
     *
     * <p>Asked by the two columns the key is, rather than by a composite the schema does not hold,
     * so the read is the same predicate the live-key index is built on. A run with no active
     * registers asks nothing at all, which is why the caller answers an empty list without issuing
     * this.
     */
    private static final String BATCHES_FOR_KEYS = """
            SELECT batch_id, court_centre_id, court_centre_ou_code, court_house, register_date,
                   file_name, payload_file_id, document_file_id, status, failure_reason,
                   sdg_reason, system_generated, completed_by, assembled_at, requested_at,
                   generated_at, notified_at, failed_at, attempts, supplement_of, supplement_index
              FROM register_batch
             WHERE (court_centre_id, register_date) IN (:keys)
             ORDER BY register_date, supplement_index, batch_id
            """;

    /**
     * Statement 4b - every batch recorded for one register day, whatever state it reached.
     *
     * <p>The read a person's regeneration starts from, and the one thing statement 4a cannot answer:
     * a support call is about a day rather than about a set of keys, and the day's FAILED batches are
     * precisely the ones whose registers still carry a stamp and are therefore outside
     * {@code ACTIVE_UNBATCHED}. Asked for the keys the active registers fall under, that court centre
     * would not be among them at all.
     *
     * <p>Every state for the reason 4a reads every state, and it is the same three answers: a FAILED
     * batch may be released and re-assembled, a notified one is what a supplementary index is counted
     * over, and one still in flight is why a key is left alone (design Q27).
     *
     * <p>The day is the whole of the predicate, so the court centre takes the place statement 4a
     * gives the register date: the same three columns, ordered so that a day reads a court centre at
     * a time and the identity breaks the tie the key's supplements would otherwise leave to the
     * planner.
     */
    private static final String BATCHES_ON_DAY = """
            SELECT batch_id, court_centre_id, court_centre_ou_code, court_house, register_date,
                   file_name, payload_file_id, document_file_id, status, failure_reason,
                   sdg_reason, system_generated, completed_by, assembled_at, requested_at,
                   generated_at, notified_at, failed_at, attempts, supplement_of, supplement_index
              FROM register_batch
             WHERE register_date = :registerDate
             ORDER BY court_centre_id, supplement_index, batch_id
            """;

    /**
     * Statement 4c - the batches a set of identities names, as their rows stand right now.
     *
     * <p>The run report's settled read. Asked by identity because that is the only predicate that
     * says <em>tonight's</em> batches: 4a reads a key's whole history and 4b reads a day's, so a run
     * counting either would credit itself with an earlier run's documents.
     *
     * <p>The same columns, because the caller reads the state off the same record the other two
     * answer with, and no order at all: the caller counts them, and an ordering would be work for a
     * question nobody asks. The primary key answers it, so it is one index scan however many court
     * centres a night held.
     */
    private static final String BATCHES_BY_IDENTITY = """
            SELECT batch_id, court_centre_id, court_centre_ou_code, court_house, register_date,
                   file_name, payload_file_id, document_file_id, status, failure_reason,
                   sdg_reason, system_generated, completed_by, assembled_at, requested_at,
                   generated_at, notified_at, failed_at, attempts, supplement_of, supplement_index
              FROM register_batch
             WHERE batch_id IN (:batchIds)
            """;

    /** Statement 5 - the status a transition is asked about and then fenced on. */
    private static final String READ_BATCH_STATUS = """
            SELECT status FROM register_batch WHERE batch_id = :batchId
            """;

    /**
     * Statement 6 - the payload id this batch's render will be about, written before it is used.
     *
     * <p>Nothing else moves. The batch stays PENDING because the id says which payload the render
     * will be about and not that one was asked for, and {@code requested_at} and {@code attempts}
     * belong to the statement that does move it.
     *
     * <p>Fenced on PENDING rather than on a status the caller read, because this is not a
     * transition and there is no {@link BatchStatus} arrow for it to be judged against. PENDING is
     * the only state a payload id may be minted for: a batch already GENERATING has had a render
     * asked for about the id it already carries, and overwriting that would leave the outcome event
     * for the first payload correlated to a row naming the second.
     */
    private static final String MARK_PAYLOAD_MINTED = """
            UPDATE register_batch
               SET payload_file_id = :payloadFileId
             WHERE batch_id = :batchId AND status = 'PENDING'
            """;

    /**
     * Statement 7 - systemdocgenerator accepted the render request for this batch.
     *
     * <p>{@code attempts} is a lifetime tally and never a control variable: the run deadline decides
     * when to stop trying, and the column records how often this batch has been asked for.
     */
    private static final String MARK_REQUESTED = """
            UPDATE register_batch
               SET status = 'GENERATING',
                   payload_file_id = :payloadFileId,
                   requested_at = now(),
                   attempts = attempts + 1
             WHERE batch_id = :batchId AND status = :expected
            """;

    /**
     * Statement 8 - the document exists, and this batch's rows move with it.
     *
     * <p><strong>Defect fix P3, stated as a predicate.</strong> The rows are found through the batch
     * the update just settled ({@code flipped} joins {@code generated}), so the only rows that can
     * move are the ones stamped with that batch identity. Progression sweeps by court centre
     * instead, which marks another day's rows generated and leaves that day's register never
     * assembled, rendered or sent.
     *
     * <p>{@code completed_by} is set here, in this statement, because there is nowhere else left to
     * set it. The move is fenced on the status the caller read, so a write before it would be
     * claiming an outcome about a batch that is still GENERATING and a write after it would need
     * GENERATED to GENERATED, which {@link BatchStatus#canTransitionTo(BatchStatus)} refuses. Which
     * mechanism learned the outcome is still the sink's knowledge; it arrives as the mark's own
     * argument and is written by the mark's own statement.
     */
    private static final String MARK_GENERATED = """
            WITH generated AS (
                UPDATE register_batch
                   SET status = 'GENERATED',
                       document_file_id = :documentFileId,
                       generated_at = :generatedAt,
                       completed_by = :completedBy
                 WHERE batch_id = :batchId AND status = :expected
                RETURNING batch_id
            ), flipped AS (
                UPDATE processed_output recorded
                   SET status = 'GENERATED', updated_at = now()
                  FROM generated
                 WHERE recorded.batch_id = generated.batch_id
                   AND recorded.status = 'RECORDED'
                RETURNING recorded.output_id
            )
            SELECT count(*) FROM generated
            """;

    /**
     * Statement 9 - the batch ended without a document, under one bounded reason.
     *
     * <p>{@code sdg_reason} is bounded on the way in, by the rule the domain states once
     * ({@link RegisterBatch#boundedReason(String)}). How long systemdocgenerator's message is is
     * systemdocgenerator's decision, and an unbounded write against a bounded column would fail
     * this whole statement: the batch would stay GENERATING, unable to say why it failed, until
     * the next run gave up on it.
     *
     * <p>The rows stay RECORDED whatever the reason, because nothing was ever sent about them.
     * Three of the six reasons leave no document to wait for, and only for those is the stamp
     * released: the rows become unbatched again and the next run re-assembles them under a fresh
     * batch identity (data-model.md). Two of the three say the batch never left this service; the
     * third says the next run began and this one had not finished, which is the same thing for the
     * register - nothing is coming. The other three leave the stamp in place - systemdocgenerator
     * was asked, so a document may yet exist, and re-rendering it is a decision a person makes.
     *
     * <p><strong>A released register the estate has already replaced is superseded rather than
     * handed back.</strong> Statement 1's {@code incumbent} predicate supersedes a row that is
     * active and <em>unbatched</em>, so a hearing shared again between the assembly and the failure
     * leaves two RECORDED rows for one key - one stamped into this batch, one active. Clearing the
     * stamp off the older one would meet {@code idx_output_active_register_key}, and the release
     * being a branch of this same statement, the mark would go down with it: the batch would still
     * call itself in flight under a run that had already given up on it. So the successor is looked
     * for as the stamp is cleared and the older row is written SUPERSEDED against it, which is what
     * statement 9a does for the release a person types.
     *
     * <p><strong>And only a row that genuinely came later may be that successor.</strong> The key
     * can hold the pair either way round: a first batch that failed under one of the three reasons
     * that keep the stamp leaves its register RECORDED and stamped, so the batch failing here may
     * be the one holding the <em>newer</em> row with the stale one still beside it. A search that
     * asked only for another unsuperseded row would then write the current register SUPERSEDED
     * against the one it replaced - neither active nor unbatched, so no later run and no command
     * reaches it again, and the row left renderable is the one the re-share corrected. The
     * comparison is therefore statement 1's own direction test, read as the total order that
     * statement states: {@code (register_time, created_at, output_id)}.
     *
     * <p><strong>All three of it, because the estate can share one hearing twice at one
     * instant.</strong> {@code register_time} is the results' own moment, so an equal-time pair is
     * ordinary rather than exotic, and {@code created_at} is the instant this database took the row
     * - which is precisely what statement 1's {@code <=} means by later. A tie broken on the
     * identity alone would disagree with the recorder for one of the two orders: the successor
     * whose identity sorts lower is not seen at all, the older row is made active a second time
     * beside the register that replaced it, and {@code idx_output_active_register_key} refuses that
     * write - taking this whole statement, and with it the failure mark, down with it. The identity
     * stays as the last tie-break, for two rows the database clock could not separate.
     *
     * <p><strong>And only a register may be that successor at all.</strong> Since V2 this table is
     * also increment 001's submission log, and {@link ProcessedOutputRepository} writes a row for
     * every register POSTed to progression on these same three key columns, with the instant of the
     * claim in {@code register_time}. That row is the record of a POST rather than a register that
     * could replace one, and a rolling deployment has the writer of it live against a schema that
     * has already moved. So the predicate names the states the recorder leaves a live register in -
     * RECORDED, GENERATED and NOTIFIED - rather than excluding the ones it does not: a search that
     * admitted every row of the key but a SUPERSEDED one would write this register SUPERSEDED
     * against a POST, leaving it neither active nor unbatched and reachable by no later run and no
     * command, with nothing to say the day had lost its document. The three are the whole of the
     * live half, and closed rather than open-ended: {@code processed_output_status_chk} bounds the
     * column at seven statuses, this store writes four of them and SUPERSEDED is the one of those
     * four that is by definition not live, and PENDING, POSTED and FAILED are 001's.
     *
     * <p><strong>And the same ordering read the other way, which is {@code overtaken}.</strong> The
     * key can also hold a share the batched register <em>overtook</em>: a delivery that arrived
     * behind the register it belongs in front of is recorded active and unbatched, because the
     * recorder's incumbent search is over unbatched rows and a batched register is not its to
     * supersede. The register being given back is then the later of the two, so it supersedes that
     * earlier row rather than being handed back beside it - the mirror of what
     * {@link #RECORD_REGISTER} does when it is the recorder that meets the pair, and the rule
     * {@link #FAIL_AND_RELEASE_STALE} keeps for the same index. Without it the key holds two active
     * rows the moment the stamp is cleared, the index refuses the write, and this statement's
     * failure mark goes down with the release it is a branch of.
     *
     * <p>It is guarded by {@code :releaseRows} through {@code stamped}, which already carries that
     * guard: a reason that keeps the stamp releases nothing and therefore displaces nothing. It runs
     * only where the register being given back has no successor of its own, because a register that
     * is itself being superseded is leaving the index as it goes and takes no key. And it is
     * <strong>chained ahead of</strong> {@code released}, as {@link #RECORD_REGISTER} chains
     * {@code replaced} ahead of its insert: the row being superseded holds the key until its update
     * takes it out of the index, so a release issued first would collide with the very row it is
     * about to supersede, and Postgres does not otherwise say which clause of one statement runs
     * first.
     *
     * <p>{@code completed_by} is written here for the same reason it is written by statement 6, and
     * it is null for most of these endings: only a {@code generation-failed} event is somebody
     * else's answer about the render. The others are this service's own verdict about a render it
     * could not ask for, could not hear about, or stopped waiting for, and naming a completion
     * mechanism for those would credit a decision nobody made. Which is which
     * is {@link BatchFailureReason#isGeneratorAttributed()}, and a mark that disagrees with it is
     * refused before this statement is issued rather than persisted contradicting itself.
     */
    private static final String MARK_FAILED = """
            WITH failed AS (
                UPDATE register_batch
                   SET status = 'FAILED',
                       failure_reason = :reason,
                       sdg_reason = :sdgReason,
                       completed_by = :completedBy,
                       failed_at = now()
                 WHERE batch_id = :batchId AND status = :expected
                RETURNING batch_id
            ), stamped AS (
                SELECT recorded.output_id,
                       recorded.hearing_id,
                       recorded.court_centre_id,
                       recorded.register_date,
                       recorded.register_time,
                       recorded.created_at,
                       (SELECT successor.output_id
                          FROM processed_output successor
                         WHERE successor.hearing_id = recorded.hearing_id
                           AND successor.court_centre_id = recorded.court_centre_id
                           AND successor.register_date = recorded.register_date
                           AND successor.output_id <> recorded.output_id
                           AND successor.superseded_at IS NULL
                           AND successor.status IN ('RECORDED', 'GENERATED', 'NOTIFIED')
                           AND (successor.register_time, successor.created_at,
                                successor.output_id)
                               > (recorded.register_time, recorded.created_at,
                                  recorded.output_id)
                         ORDER BY successor.register_time DESC,
                                  successor.created_at DESC,
                                  successor.output_id DESC
                         LIMIT 1) AS successor_id
                  FROM processed_output recorded
                  JOIN failed ON failed.batch_id = recorded.batch_id
                 WHERE recorded.status = 'RECORDED'
                   AND CAST(:releaseRows AS boolean)
            ), overtaken AS (
                UPDATE processed_output earlier
                   SET status = 'SUPERSEDED',
                       superseded_at = now(),
                       superseded_by = stamped.output_id,
                       updated_at = now()
                  FROM stamped
                 WHERE stamped.successor_id IS NULL
                   AND earlier.hearing_id = stamped.hearing_id
                   AND earlier.court_centre_id = stamped.court_centre_id
                   AND earlier.register_date = stamped.register_date
                   AND earlier.output_id <> stamped.output_id
                   AND earlier.status = 'RECORDED'
                   AND earlier.superseded_at IS NULL
                   AND earlier.batch_id IS NULL
                   AND (earlier.register_time, earlier.created_at, earlier.output_id)
                       < (stamped.register_time, stamped.created_at, stamped.output_id)
                RETURNING earlier.output_id
            ), released AS (
                UPDATE processed_output recorded
                   SET batch_id = NULL,
                       status = CASE WHEN stamped.successor_id IS NULL
                                     THEN 'RECORDED' ELSE 'SUPERSEDED' END,
                       superseded_at = CASE WHEN stamped.successor_id IS NULL
                                            THEN NULL ELSE now() END,
                       superseded_by = stamped.successor_id,
                       updated_at = now()
                  FROM stamped,
                       (SELECT count(*) FROM overtaken) AS chained(overtaken_rows)
                 WHERE recorded.output_id = stamped.output_id
                RETURNING recorded.output_id
            )
            SELECT count(*) FROM failed
            """;

    /**
     * Statement 9a - the registers of a FAILED batch given back, so the day may be rendered again.
     *
     * <p>The other half of statement 9. Three of the six reasons release the stamp as they fail;
     * the other three leave it, because systemdocgenerator was asked and a document may yet exist - so no
     * later run will ever pick those registers up, a stamped row being neither active nor unbatched.
     * Re-rendering that day is a decision a person makes, and this is the statement it is written as.
     *
     * <p><strong>Fenced on the batch's own FAILED, in the statement as well as before it.</strong>
     * The status is read first because a count of nought here means two different things - a FAILED
     * batch whose failure had already released its rows, and a batch that never failed at all - and
     * only the first of those is an answer. The predicate is then carried into the statement too, so
     * a release is never made against a row the read no longer agrees with.
     *
     * <p>The rows are read as they are released rather than afterwards, and answered rather than
     * left to be read back: between a release and a second read a re-share can supersede a row, and
     * a caller re-assembling what it read a moment earlier would be stamping a register this store
     * no longer calls active. {@code recorded.status = 'RECORDED'} is the same predicate statement 9
     * releases under - the rows of a failed batch were never sent about, so RECORDED is all they can
     * be, and a row that somehow was not is left carrying its stamp rather than unstamped into a
     * second document.
     *
     * <p>The assembly order, because it is the order the batch held them in and the first of them
     * names the file the day is rendered under.
     *
     * <p><strong>A register a re-share has replaced is superseded as its stamp is cleared, and is
     * not answered with.</strong> Statement 1's {@code incumbent} predicate supersedes a row that is
     * active and <em>unbatched</em>, so a hearing re-shared while its first register was stamped
     * into this batch left two RECORDED rows for one key rather than one - and clearing the stamp
     * off the older of them is what would make both active. That is not a case the caller can be
     * left to handle: with the re-share still active the write collides with
     * {@code idx_output_active_register_key} and the whole regeneration fails on a key nothing said
     * was wrong, and once the re-share has been batched in its turn the write succeeds and hands
     * back a register the estate has already replaced, for a document a Youth Offending Team would
     * read after the current one. So the successor is looked for row by row, the stale row is
     * superseded against it in the same statement that unstamps it - which is what keeps the index
     * satisfied rather than defended - and the final {@code SELECT} answers with the rows that are
     * still this day's to render.
     *
     * <p>The successor is a <strong>later</strong> register of the key that is unsuperseded,
     * whatever batch it has reached: what makes the released row unassemblable is that a register
     * shared after it holds the key, and the newest of those is the one named as the replacement.
     * {@code superseded_by} therefore names a register rather than being left null, which is what
     * tells this apart from a rollback's supersession (statement 12).
     *
     * <p><strong>A register, which is narrower than a row.</strong> Since V2 this table is also
     * increment 001's submission log, and {@link ProcessedOutputRepository} writes a row for every
     * register POSTed to progression on these same three key columns, with the instant of the claim
     * in {@code register_time}; a rolling deployment has that writer live against a schema that has
     * already moved. So the predicate names the states the recorder leaves a live register in -
     * RECORDED, GENERATED and NOTIFIED - rather than excluding the ones it does not: a search that
     * admitted every row of the key but a SUPERSEDED one would supersede this register against a
     * POST, leave it out of the answer, and hand the operator a day it released nothing for while
     * the register that day is owed a document from is reachable by no later run. The three are the
     * whole of the live half, and closed rather than open-ended:
     * {@code processed_output_status_chk} bounds the column at seven statuses, this store writes
     * four of them and SUPERSEDED is the one of those four that is by definition not live, and
     * PENDING, POSTED and FAILED are 001's.
     *
     * <p><strong>Later, because the key can hold the pair either way round.</strong> A first batch
     * that failed under one of the three reasons that keep the stamp, or one that reached NOTIFIED,
     * leaves its register beside the re-share rather than superseded by it - so the batch a person
     * releases may be the one holding the <em>newer</em> row, with the stale or the sent one still
     * beside it. A search that asked only for another unsuperseded row would write the current
     * register SUPERSEDED against that one and leave it out of the answer: the command would print a
     * day it released nothing for and exit 0, while the register the estate shared last is neither
     * active nor unbatched and no later run reaches it again. The comparison is statement 1's own
     * direction test, read as the total order that statement states:
     * {@code (register_time, created_at, output_id)}.
     *
     * <p><strong>All three of it, because the estate can share one hearing twice at one
     * instant.</strong> {@code register_time} is the results' own moment, so an equal-time pair is
     * ordinary rather than exotic, and {@code created_at} is the instant this database took the row
     * - which is precisely what statement 1's {@code <=} means by later. A tie broken on the
     * identity alone would disagree with the recorder for one of the two orders: the successor whose
     * identity sorts lower is not seen at all, unstamping the older row makes it active beside the
     * register that replaced it, and {@code idx_output_active_register_key} refuses the write - so
     * the whole regeneration fails on a key nothing said was wrong. The identity stays as the last
     * tie-break, for two rows the database clock could not separate.
     *
     * <p><strong>And the same ordering read the other way, which is {@code overtaken}.</strong> The
     * key can hold a share the batched register <em>overtook</em> just as easily as one that
     * replaced it: a delivery that arrived behind the register it belongs in front of is recorded
     * active and unbatched, because the recorder's incumbent search is over unbatched rows and a
     * batched register is not its to supersede. The register coming back is then the later of the
     * two, so this statement supersedes that earlier row against it - the same rule
     * {@link #FAIL_AND_RELEASE_STALE} keeps, and the mirror of {@link #RECORD_REGISTER}'s own. Read
     * one way only, an operator's release would clear the stamp beside the earlier active row,
     * {@code idx_output_active_register_key} would refuse the second active row for the day, and
     * the command would fail on a key nothing said was wrong - for ever, because no re-run removes
     * a row committed before it.
     *
     * <p>It runs only where the released register has no successor of its own: a register that is
     * itself being superseded is leaving the index as it goes, so it takes no key and displaces
     * nothing. And it is <strong>chained ahead of</strong> {@code released}, as
     * {@link #RECORD_REGISTER} chains {@code replaced} ahead of its insert and for the same index -
     * the row being superseded holds the key until its update takes it out, so a release issued
     * first would collide with the very row it is about to supersede, and Postgres does not
     * otherwise order the clauses of one statement.
     */
    private static final String RELEASE_FAILED = """
            WITH stamped AS (
                SELECT recorded.output_id,
                       recorded.hearing_id,
                       recorded.court_centre_id,
                       recorded.register_date,
                       recorded.register_time,
                       recorded.created_at,
                       (SELECT successor.output_id
                          FROM processed_output successor
                         WHERE successor.hearing_id = recorded.hearing_id
                           AND successor.court_centre_id = recorded.court_centre_id
                           AND successor.register_date = recorded.register_date
                           AND successor.output_id <> recorded.output_id
                           AND successor.superseded_at IS NULL
                           AND successor.status IN ('RECORDED', 'GENERATED', 'NOTIFIED')
                           AND (successor.register_time, successor.created_at,
                                successor.output_id)
                               > (recorded.register_time, recorded.created_at,
                                  recorded.output_id)
                         ORDER BY successor.register_time DESC,
                                  successor.created_at DESC,
                                  successor.output_id DESC
                         LIMIT 1) AS successor_id
                  FROM processed_output recorded
                  JOIN register_batch failed ON failed.batch_id = recorded.batch_id
                 WHERE failed.batch_id = :batchId
                   AND failed.status = 'FAILED'
                   AND recorded.status = 'RECORDED'
            ), overtaken AS (
                UPDATE processed_output earlier
                   SET status = 'SUPERSEDED',
                       superseded_at = now(),
                       superseded_by = stamped.output_id,
                       updated_at = now()
                  FROM stamped
                 WHERE stamped.successor_id IS NULL
                   AND earlier.hearing_id = stamped.hearing_id
                   AND earlier.court_centre_id = stamped.court_centre_id
                   AND earlier.register_date = stamped.register_date
                   AND earlier.output_id <> stamped.output_id
                   AND earlier.status = 'RECORDED'
                   AND earlier.superseded_at IS NULL
                   AND earlier.batch_id IS NULL
                   AND (earlier.register_time, earlier.created_at, earlier.output_id)
                       < (stamped.register_time, stamped.created_at, stamped.output_id)
                RETURNING earlier.output_id
            ), released AS (
                UPDATE processed_output recorded
                   SET batch_id = NULL,
                       status = CASE WHEN stamped.successor_id IS NULL
                                     THEN 'RECORDED' ELSE 'SUPERSEDED' END,
                       superseded_at = CASE WHEN stamped.successor_id IS NULL
                                            THEN NULL ELSE now() END,
                       superseded_by = stamped.successor_id,
                       updated_at = now()
                  FROM stamped,
                       (SELECT count(*) FROM overtaken) AS chained(overtaken_rows)
                 WHERE recorded.output_id = stamped.output_id
                RETURNING recorded.output_id, recorded.hearing_id, recorded.hearing_date,
                          recorded.court_centre_id, recorded.register_date,
                          recorded.register_time, recorded.file_name, recorded.defendant_type,
                          recorded.recorded_flag_state, recorded.document,
                          recorded.superseded_at
            )
            SELECT output_id, hearing_id, hearing_date, court_centre_id, register_date,
                   register_time, file_name, defendant_type, recorded_flag_state, document
              FROM released
             WHERE superseded_at IS NULL
             ORDER BY register_time, output_id
            """;

    /**
     * Statement 9b - the batches a run found still waiting, named so each can be its own statement.
     *
     * <p><strong>This read decides nothing.</strong> It is the same predicate {@link
     * #FAIL_AND_RELEASE_STALE} carries, asked once so the pass has a list to walk, and every batch
     * it names is judged again by the statement that writes the row. A batch that stopped being
     * stale in between therefore matches nothing and is changed by nothing: the fence is the
     * write's own {@code WHERE}, and this list is only the order the writes are made in.
     *
     * <p>Ordered by register date so the oldest day is given back first, and by batch identity so
     * the order is total rather than merely mostly decided - two batches of one date must not swap
     * places between runs, or the run report's lines would be a different account of the same
     * night each time.
     */
    private static final String STALE_BATCHES = """
            SELECT batch_id
              FROM register_batch
             WHERE status IN ('PENDING', 'GENERATING')
               AND COALESCE(requested_at, assembled_at)
                     <= CASE WHEN system_generated THEN :scheduledCutoff
                             ELSE :manualCutoff END
             ORDER BY register_date, batch_id
            """;

    /**
     * Statement 9c - one batch the next run found still waiting, failed and released in one act.
     *
     * <p><strong>One statement per batch, whose {@code WHERE} clause is the staleness rule
     * itself.</strong> That is the whole design, and it is a correctness requirement rather than a
     * tidiness preference. A read followed by a per-batch {@code markFailed} - the shape the
     * retired reconciler used - fails in two ways this one cannot:
     *
     * <ul>
     *   <li><strong>A lost register.</strong> {@code markFailed} and {@link #releaseFailed} are two
     *       operations with a status read between them. A crash landing between a mark and a
     *       release leaves registers stamped to a terminal batch, and {@code ACTIVE_UNBATCHED}'s
     *       predicate is {@code batch_id IS NULL} - so those rows are invisible to every later run
     *       and to every command, and the hearing's youth defendants never reach a court register
     *       again. That is the precise failure this pass exists to end, reintroduced by the fix
     *       for it.</li>
     *   <li><strong>A refused mark ending the night.</strong> Between the read and the mark the
     *       outcome sink can commit a {@code markGenerated}. {@link #permitted} would then throw,
     *       and run inline in the night's generation that refusal propagates out of the run: one
     *       batch that came good in the wrong second would cost every court centre its document.
     *       Here a batch that stopped being stale simply does not match, and zero rows is an
     *       answer rather than an error.</li>
     * </ul>
     *
     * <p><strong>The predicate is in the UPDATE's own {@code WHERE}, and not in a CTE that feeds
     * it.</strong> Under READ COMMITTED an {@code UPDATE} that meets a row another transaction has
     * just committed re-evaluates its own qualification against the new version and skips the row
     * where it no longer matches. A staleness rule computed in a preceding {@code SELECT} would be
     * evaluated once, against the statement's snapshot, and the update would then fail a batch
     * whose render had been accepted in between - the very race the fence exists for. The rule is
     * therefore written where the re-check can see it.
     *
     * <p>{@code COALESCE(requested_at, assembled_at)} is "the stamp for whichever state it is in"
     * in one expression: a GENERATING batch has a {@code requested_at} and is measured from it, a
     * PENDING one has none and is measured from the assembly. The column is {@code assembled_at} -
     * there is no {@code created_at} on this table. A PENDING batch with a null
     * {@code payload_file_id} is deliberately included: staleness is state and age, not progress,
     * and the batch that never minted a payload is the one the retired reads excluded and left
     * deferring its court centre day at every subsequent run (FR-020).
     *
     * <p>{@code GENERATED} is in neither list, at any age. It holds a document somebody is owed
     * e-mails about, and failing it would throw that document away.
     *
     * <p>{@code system_generated} picks the cutoff. A batch the schedule made is judged by
     * {@code stale-after}; a batch an operator asked for is judged by the longer of that and the
     * run's own lock duration, because a manual generation holds no run lock and may legitimately
     * still be asking for its renders when the schedule fires (FR-017). Failing its batch would
     * orphan a render it is making and refuse its own record of having made it.
     *
     * <p>{@code completed_by} is written NULL, because nobody outside this service reported
     * anything: no event arrived and nothing was asked. The reason is not generator-attributed, so
     * {@link BatchFailureReason#isGeneratorAttributed()} and
     * {@code register_batch_completed_by_shape_chk} agree on the null, and a row written otherwise
     * is refused by the constraint rather than by a rule this class keeps of its own.
     *
     * <p><strong>And one batch at a time, which is the other half of the same requirement.</strong>
     * One statement over every stale batch is atomic in the wrong unit: a refusal met on any one
     * court centre's registers rolls back every other court centre's release with it, so one
     * hearing the estate re-shared at the wrong moment costs the whole country its documents that
     * night. FR-003a says no single batch's outcome may end the run, and a shared transaction is a
     * way of ending it that no amount of care in the caller can undo. Each batch is therefore its
     * own statement, its own transaction and its own bounded retry, and the failure and the release
     * of that batch stay one act - which is what the requirement was ever about.
     *
     * <p>The release is statement 9's own branch, narrowed to this batch: the same successor
     * search, the same total order {@code (register_time, created_at, output_id)}, and the same
     * reason for all three of it. A register the estate replaced while the batch was in flight is
     * superseded as its stamp is cleared rather than handed back - the key would otherwise hold two
     * active rows and {@code idx_output_active_register_key} would refuse the write, taking the
     * failure mark down with it and leaving the batch calling itself in flight under a run that had
     * already given up on it.
     *
     * <p><strong>And the same ordering read the other way, which is {@code overtaken}.</strong> The
     * key can also hold a share the batched register <em>overtook</em>: a delivery that arrived
     * behind the register it belongs in front of is recorded active and unbatched, because the
     * recorder's incumbent search is over unbatched rows and a batched register is not its to
     * supersede. The register being given back is then the later of the two, so this statement
     * supersedes that earlier row against it - the mirror of what {@link #RECORD_REGISTER} does
     * when it is the recorder that meets the pair, and the same rule: the later share is the one
     * the day is still to render, whichever of the two writers is the one that finds them
     * together. Without it the key holds two active rows the moment the stamp is cleared, the index
     * refuses the write, and no fresh snapshot ever changes that - the batch would be reported
     * contended by every run for ever, and no run could give its registers back.
     *
     * <p>It runs only where the given-back register has no successor of its own. A register that is
     * being superseded is leaving the index as it goes, so it takes no key and displaces nothing,
     * and superseding an earlier row against a row that is itself superseded would say the wrong
     * register replaced it.
     *
     * <p><strong>{@code overtaken} is chained ahead of {@code released}, and that order is the
     * statement's to keep</strong> - the same requirement {@link #RECORD_REGISTER} has, for the
     * same index. The row being superseded still holds the key until its update takes it out of
     * the index, so a release issued first collides with the very row it is about to supersede.
     * The release's source therefore counts {@code overtaken}'s rows, which cannot be counted until
     * every one of its updates has been made; Postgres does not otherwise say which clause runs
     * first.
     *
     * <p><strong>The successor search sees one snapshot, and a re-share can commit after it.</strong>
     * Every clause here reads the table as it stood when the statement began, so a register
     * re-shared while this statement is running is a successor it cannot find - and the release
     * then clears the stale register's stamp beside the replacement it could not see, which is the
     * second active row the index refuses. There is no predicate that can fence it, because the
     * write it collides with is not in the snapshot the predicate is evaluated against. It is
     * settled outside the statement instead: {@link #failAndReleaseStale} makes this batch's
     * statement again on a fresh snapshot, which has the re-share in it and supersedes against it.
     *
     * <p>The count answered is the registers that are <em>still this day's to render</em>, which is
     * what the same run re-assembles. A superseded one is not counted: the run report states it
     * beside its own accounts as the registers it re-batched, and a number that included a register
     * nothing will batch would not be that.
     */
    private static final String FAIL_AND_RELEASE_STALE = """
            WITH failed AS (
                UPDATE register_batch
                   SET status = 'FAILED',
                       failure_reason = :reason,
                       completed_by = NULL,
                       failed_at = now()
                 WHERE batch_id = :batchId
                   AND status IN ('PENDING', 'GENERATING')
                   AND COALESCE(requested_at, assembled_at)
                         <= CASE WHEN system_generated THEN :scheduledCutoff
                                 ELSE :manualCutoff END
                RETURNING batch_id, court_centre_id, register_date
            ), stamped AS (
                SELECT recorded.output_id,
                       failed.batch_id,
                       recorded.hearing_id,
                       recorded.court_centre_id,
                       recorded.register_date,
                       recorded.register_time,
                       recorded.created_at,
                       (SELECT successor.output_id
                          FROM processed_output successor
                         WHERE successor.hearing_id = recorded.hearing_id
                           AND successor.court_centre_id = recorded.court_centre_id
                           AND successor.register_date = recorded.register_date
                           AND successor.output_id <> recorded.output_id
                           AND successor.superseded_at IS NULL
                           AND successor.status IN ('RECORDED', 'GENERATED', 'NOTIFIED')
                           AND (successor.register_time, successor.created_at,
                                successor.output_id)
                               > (recorded.register_time, recorded.created_at,
                                  recorded.output_id)
                         ORDER BY successor.register_time DESC,
                                  successor.created_at DESC,
                                  successor.output_id DESC
                         LIMIT 1) AS successor_id
                  FROM processed_output recorded
                  JOIN failed ON failed.batch_id = recorded.batch_id
                 WHERE recorded.status = 'RECORDED'
            ), overtaken AS (
                UPDATE processed_output earlier
                   SET status = 'SUPERSEDED',
                       superseded_at = now(),
                       superseded_by = stamped.output_id,
                       updated_at = now()
                  FROM stamped
                 WHERE stamped.successor_id IS NULL
                   AND earlier.hearing_id = stamped.hearing_id
                   AND earlier.court_centre_id = stamped.court_centre_id
                   AND earlier.register_date = stamped.register_date
                   AND earlier.output_id <> stamped.output_id
                   AND earlier.status = 'RECORDED'
                   AND earlier.superseded_at IS NULL
                   AND earlier.batch_id IS NULL
                   AND (earlier.register_time, earlier.created_at, earlier.output_id)
                       < (stamped.register_time, stamped.created_at, stamped.output_id)
                RETURNING earlier.output_id
            ), released AS (
                UPDATE processed_output recorded
                   SET batch_id = NULL,
                       status = CASE WHEN stamped.successor_id IS NULL
                                     THEN 'RECORDED' ELSE 'SUPERSEDED' END,
                       superseded_at = CASE WHEN stamped.successor_id IS NULL
                                            THEN NULL ELSE now() END,
                       superseded_by = stamped.successor_id,
                       updated_at = now()
                  FROM stamped,
                       (SELECT count(*) FROM overtaken) AS chained(overtaken_rows)
                 WHERE recorded.output_id = stamped.output_id
                   AND recorded.status = 'RECORDED'
                RETURNING stamped.batch_id AS batch_id,
                          stamped.successor_id AS successor_id
            )
            SELECT failed.batch_id, failed.court_centre_id, failed.register_date,
                   (SELECT count(*)
                      FROM released
                     WHERE released.batch_id = failed.batch_id
                       AND released.successor_id IS NULL) AS released_registers
              FROM failed
             ORDER BY failed.register_date, failed.batch_id
            """;

    /**
     * Statement 10 - every recipient of the batch has been attempted.
     *
     * <p>The batch's own terminal state is the summary's verdict - NOTIFIED, PARTIALLY_NOTIFIED or
     * NOTIFIED_NOBODY - and its rows reach NOTIFIED under all three. A batch nobody subscribes to is
     * finished rather than left generated for ever waiting for an event nobody publishes, which is
     * defect fix P1.
     */
    private static final String MARK_NOTIFIED = """
            WITH notified AS (
                UPDATE register_batch
                   SET status = :outcome, notified_at = now()
                 WHERE batch_id = :batchId AND status = :expected
                RETURNING batch_id
            ), flipped AS (
                UPDATE processed_output generated
                   SET status = 'NOTIFIED', updated_at = now()
                  FROM notified
                 WHERE generated.batch_id = notified.batch_id
                   AND generated.status = 'GENERATED'
                RETURNING generated.output_id
            )
            SELECT count(*) FROM notified
            """;

    /**
     * Statement 11 - the registers automatic batching passed over.
     *
     * <p>Statement 2's predicate with its fourth test turned round, and the two are deliberately one
     * predicate: RECORDED, unsuperseded and unbatched in both, the flag state as recorded the only
     * thing that separates them (research §12). A register recorded while the legacy was generating
     * may already have been sent by the legacy, so it is left out of every automatic run - and
     * nothing else in this service would ever look at it again, which is why it is read here.
     *
     * <p>{@code <> 'ON'} rather than {@code IN ('OFF', 'UNKNOWN')}, because the column is NOT NULL
     * with a vocabulary the schema bounds ({@code processed_output_flag_state_chk}): a state added to
     * that vocabulary later is a state this read must answer with by default, since anything the
     * batching did not claim is exactly what this read is for.
     *
     * <p>Oldest first, by the register instant, as statement 2 is: the two answer one question and a
     * person comparing them should not have to reconcile two orders.
     */
    private static final String RECORDED_WHILE_OFF = """
            SELECT output_id, hearing_id, hearing_date, court_centre_id, register_date,
                   register_time, file_name, defendant_type, recorded_flag_state, document
              FROM processed_output
             WHERE status = 'RECORDED'
               AND superseded_at IS NULL
               AND batch_id IS NULL
               AND recorded_flag_state <> 'ON'
             ORDER BY register_time, output_id
            """;

    /**
     * Statement 12 - the registers shared before an instant superseded, so no run will batch them.
     *
     * <p>The rollback lever's other half. The bound is read against {@code register_time} - the
     * register's own shared instant - because that is the moment the estate agrees on: the period the
     * legacy has taken back over is a period of hearings, not a period of this pod's writes. Strictly
     * before it, because the caller states the bound and this statement never widens it.
     *
     * <p>Statement 2's first three predicates and not its fourth. A stamped row is the renderer's -
     * systemdocgenerator has been asked about its batch and a document may exist under it, so what
     * becomes of the row is that batch's ending to decide and not a period's; a row already
     * superseded is not superseded twice, which would restamp {@code superseded_at} over the pair
     * that says which register replaced which; and a GENERATED or NOTIFIED row is not rewritten to
     * say a register that was sent was never claimed. Whether the flag was on when a row arrived is
     * deliberately outside the predicate: a rollback supersedes the period, and a row recorded while
     * the flag was off is in that period too.
     *
     * <p><strong>So this write is not final over a day whose batches are still open.</strong>
     * Statements 9 and 9a both unstamp a RECORDED row back to unbatched, and neither can consult a
     * rollback - nothing in the schema records that a period was taken back - so a batch failed or
     * released after this ran hands its registers back into {@code ACTIVE_UNBATCHED} with their
     * recorded flag state unchanged, register instants inside the period included. The rollback is
     * therefore run again after any release of that period's batches, and the count is what says
     * whether it had anything left to take.
     *
     * <p><strong>{@code superseded_by} is left null, and that is the honest answer.</strong> Every
     * other supersession here names the register that replaced this one, because there is one; a
     * rollback replaces nothing - it says this service is no longer the one that will send this day -
     * so a column naming a successor would have to invent one. The pair is therefore
     * {@code superseded_at} alone, which is what statement 0 already tells apart from a row that
     * recorded itself SUPERSEDED against another.
     *
     * <p>Supersession rather than deletion: the rows stay, carrying what was recorded and when,
     * because the register store is the audit of what this service decided and a rollback is exactly
     * when that audit is read.
     */
    private static final String SUPERSEDE_SHARED_BEFORE = """
            UPDATE processed_output
               SET status = 'SUPERSEDED',
                   superseded_at = now(),
                   updated_at = now()
             WHERE status = 'RECORDED'
               AND superseded_at IS NULL
               AND batch_id IS NULL
               AND register_time < :sharedBefore
            """;

    /** The three states a notification tally is allowed to settle a batch in. */
    private static final Set<BatchStatus> NOTIFICATION_OUTCOMES = Set.of(
            BatchStatus.NOTIFIED, BatchStatus.PARTIALLY_NOTIFIED, BatchStatus.NOTIFIED_NOBODY);

    /**
     * The three failures that leave no document to wait for, and so give their rows back.
     *
     * <p>Two of them never left this service at all - no payload was stored, or the batch could not
     * be assembled into one. The third is the next run's verdict that this batch had not finished:
     * whether its request ever reached systemdocgenerator is a question this service can no longer
     * ask, and the register's position is the same either way. Holding a register against a
     * document nothing will now produce is what strands it.
     *
     * <p>Named here rather than at each call site, so the fenced statement, a person's per-batch
     * {@code markFailed} from the operations surface and {@link #releaseFailed} all release on one
     * rule. A reason that joined the set in one of the three and not the others would be a register
     * given back by one path and held by another.
     */
    private static final Set<BatchFailureReason> RELEASING_REASONS = Set.of(
            BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE, BatchFailureReason.ASSEMBLY_FAILED,
            BatchFailureReason.NOT_COMPLETED_BY_NEXT_RUN);

    /**
     * The connection every statement in this class is issued through.
     *
     * <p>Held from construction rather than looked up per method, so that the store is one client's
     * worth of statements and a caller can see what it talks to.
     */
    private final JdbcClient jdbcClient;

    /**
     * The mapper the recorded document is written and read back through.
     *
     * <p>The service's own contract mapper rather than one of this class's invention: the document
     * stored here is the one the payload mapper renders, and a mapper that read floating-point
     * amounts as binary would change a financial order on the way through.
     */
    private final ObjectMapper objectMapper;

    /**
     * The one write in this class whose decision is made outside its statement.
     *
     * <p>Held rather than reached for, and over the same {@code DataSource} the client issues
     * against, because a transaction manager bound to a second one would open a transaction nothing
     * in this class ever joins.
     */
    private final TransactionOperations transactions;

    /**
     * The boundary each stale batch's release is made inside, whatever the caller had open.
     *
     * <p>{@code PROPAGATION_REQUIRES_NEW}, and that is the whole point of it being a second
     * template. FR-003a says no single batch's outcome may end the run, and
     * {@link #failAndReleaseStale(Instant, Instant)} keeps that promise by giving each batch its
     * own statement, its own transaction and its own bounded retry - which is true only for as
     * long as those attempts are not folded into somebody else's transaction. A caller inside one
     * would have the first refusal abort it, every attempt after that made inside an aborted
     * transaction, and every court centre already released rolled back at the end: the exact
     * run-ending outcome the per-batch shape exists to prevent, reached without a line of this
     * class changing. So the boundary is taken here rather than asked for in a javadoc: the
     * caller's transaction is suspended for the length of an attempt and resumed after it.
     */
    private final TransactionOperations perBatchTransactions;

    /**
     * Binds the store to this service's own Postgres.
     *
     * @param jdbcClient         the client every statement in this class is issued through
     * @param transactionManager the manager over the same data source as the client, from which
     *                           both of this class's boundaries are built - the one
     *                           {@link #assemble(RegisterBatch, List)} and the recording run in,
     *                           and the {@code REQUIRES_NEW} one each stale batch's release is
     *                           made inside
     */
    public JdbcRegisterStore(final JdbcClient jdbcClient,
            final PlatformTransactionManager transactionManager) {
        this.jdbcClient = jdbcClient;
        this.transactions = new TransactionTemplate(transactionManager);
        final TransactionTemplate perBatch = new TransactionTemplate(transactionManager);
        perBatch.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.perBatchTransactions = perBatch;
        this.objectMapper = JacksonConfig.contractObjectMapper();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The row is written with the document as recorded and {@code request_digest} as its SHA-256,
     * which is what the differential audit compares. The register day is the London date part of the
     * document's own register instant rather than anything taken from the command, because that
     * instant is what orders two re-shares of one hearing and the day it falls on is what the batch
     * groups by.
     *
     * <p>The OU code is written here because {@link #assemble(RegisterBatch, List)} reads it off
     * the batch's first row, and nothing between the transformation and the render payload knows it
     * otherwise: the document does not carry it.
     *
     * <p><strong>A recording that lost the race re-reads and records again.</strong> Which register
     * a re-share replaces is decided by a read, so two re-shares of one hearing that both read
     * before either has committed both find the same incumbent - and
     * {@code idx_output_active_register_key} refuses the second of them rather than letting the day
     * be rendered with one hearing on it twice. That refusal is not the caller's to carry: the
     * losing re-share is a message the broker delivered and the pipeline completed, and handing the
     * listener a duplicate-key failure would abandon a command whose register is safely recorded and
     * turn an invariant the database keeps into an outage. So the attempt is made again, on a fresh
     * transaction and therefore a fresh snapshot: it finds the register the winner left and either
     * supersedes it or is recorded SUPERSEDED against it, exactly as an unraced re-share would.
     *
     * <p>Each attempt is its own transaction because a violated one cannot be continued: Postgres
     * refuses every further statement on an aborted transaction, so a retry inside it would fail on
     * the read rather than on the write. The identifier the caller is answered with is minted once
     * and reused, which is safe precisely because a refused attempt committed nothing.
     *
     * <p><strong>A command delivered again is answered rather than recorded again.</strong> The
     * completion is written inside this call now, so a delivery cannot stop between the two writes
     * - but it can stop after them, before the broker learns the message was settled, and the
     * message is delivered again. Each attempt therefore reads {@code (source, request_id)} first
     * and answers with the row it finds, which is the same answer the first delivery was given:
     * 001's POST path settles the same shape with {@code ON CONFLICT (source, request_id)}, and a
     * recording that failed here instead would park a command whose register is recorded and
     * active. The guard settles most such deliveries before they reach this store at all; this is
     * what makes the ones that do reach it harmless. The two unique keys are told apart rather than
     * both read as contention - {@value #COMMAND_KEY} is this command arriving beside itself and is
     * settled from the row that landed, and only {@value #ACTIVE_ROW_KEY} is a race for the day's
     * key worth trying again. A duplicate-key refusal that is neither of them is a rule nobody
     * wrote this recording against, and trying it three times would report contention - a transient
     * failure - for a register that meets the same rule on every delivery.
     *
     * @throws uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotRecordedException if the write was
     *                                     refused by a unique key this store does not account for,
     *                                     which no redelivery can change
     * @throws ConcurrencyFailureException if {@value #RECORD_ATTEMPTS} attempts all lost the race
     *                                     for this key, which is the store answering rather than
     *                                     the store being unreachable: the delivery is handed back
     *                                     and intake keeps running
     * @throws IllegalStateException       if the key already carries more than one active row, if
     *                                     the command already holds an output row no recording
     *                                     wrote, or if the statement recorded nothing
     */
    @Override
    public RecordedCompletion recordAndComplete(final DistributionCommand command,
            final CourtRegisterDocument document, final String courtCentreOuCode,
            final String defendantType, final RecordedFlagState flagState,
            final Supplier<GuardDecision> completion) {
        return StoreOutage.translating("record a register", () -> attemptedRecording(
                command, document, courtCentreOuCode, defendantType, flagState, completion));
    }

    /**
     * The recording itself, inside the translation the public method wraps it in.
     *
     * @param command           the request being recorded
     * @param document          the register the transformation produced
     * @param courtCentreOuCode the court centre's OU code, which the document has no field for
     * @param defendantType     the side of the court application the register's defendants are on
     * @param flagState         the cutover flag as the intake side last read it
     * @param completion        the completion of the command, run in the recording's transaction
     * @return what was written, what it replaced, and what the completion answered
     */
    private RecordedCompletion attemptedRecording(final DistributionCommand command,
            final CourtRegisterDocument document, final String courtCentreOuCode,
            final String defendantType, final RecordedFlagState flagState,
            final Supplier<GuardDecision> completion) {
        final Recording recording = new Recording(UUID.randomUUID(), command, document,
                objectMapper.writeValueAsString(document), courtCentreOuCode, defendantType,
                flagState);
        RecordedCompletion outcome = null;
        DuplicateKeyException lost = null;
        for (int attempt = 0; outcome == null && attempt < RECORD_ATTEMPTS; attempt++) {
            try {
                outcome = insert(recording, completion);
            } catch (DuplicateKeyException collision) {
                if (violates(collision, COMMAND_KEY)) {
                    outcome = answered(recording, completion, collision);
                } else if (violates(collision, ACTIVE_ROW_KEY)) {
                    lost = collision;
                } else {
                    throw unaccountedFor(command, collision);
                }
            }
        }
        if (outcome == null) {
            throw new ConcurrencyFailureException("the register for this hearing and day was "
                    + "re-recorded by another delivery on each of " + RECORD_ATTEMPTS
                    + " attempts; " + identityOf(command), lost);
        }
        return outcome;
    }

    /**
     * One attempt at the recording, and the completion of the command, in one transaction.
     *
     * <p><strong>The transaction is now what makes the pair atomic, as well as what undoes a lost
     * race.</strong> The recording statement is atomic on its own; the completion is a second
     * statement, against a second table, and it is the caller's rather than this class's - so the
     * commit boundary is the only place the two can be made one thing. It is issued through the
     * guard's own repository, over the same {@code DataSource} this store's client issues against,
     * so it joins this transaction rather than opening one of its own (data-model.md).
     *
     * <p>A re-share that loses the race has already superseded the incumbent by the time its insert
     * is refused, and without a transaction to roll back that supersession would stand: the register
     * the winner replaced would carry the loser's identity in {@code superseded_by}, pointing
     * support at a row that was never recorded. The retry that follows is safe for the same reason.
     *
     * <p>The read that opens it is inside the same transaction, so what it sees and what the write
     * is refused for are one snapshot's worth of the same table.
     */
    private RecordedCompletion insert(final Recording recording,
            final Supplier<GuardDecision> completion) {
        return transactions.execute(oneTransaction -> completed(oneTransaction,
                recorded(recording.command()).orElseGet(() -> write(recording)), completion));
    }

    /**
     * The register this command already holds, answered and completed in one transaction.
     *
     * <p>Reached where the insert met {@value #COMMAND_KEY} rather than the read that precedes it -
     * two deliveries of one command, racing, both finding nothing and both writing. The loser is
     * answered with the row the winner wrote, and its command is completed beside that answer, in a
     * transaction of its own: the delivery is settled on a register that exists, and the completion
     * it settles under is written under the same rule every other completion here is.
     *
     * @param recording  the recording that was refused
     * @param completion the completion of the command, run in this transaction
     * @param collision  the refusal, kept for the failure raised where no row is behind it
     * @return the register this command already had, and what the completion answered
     */
    private RecordedCompletion answered(final Recording recording,
            final Supplier<GuardDecision> completion, final DuplicateKeyException collision) {
        return transactions.execute(oneTransaction -> completed(oneTransaction,
                recorded(recording.command())
                        .orElseThrow(() -> unrecorded(recording.command(), collision)),
                completion));
    }

    /**
     * Writes the completion beside the recording, and undoes the recording where it does not stand.
     *
     * <p>Three outcomes and one rule. A completion that throws takes the transaction with it, which
     * is the ordinary behaviour of a transaction template and is exactly right: nothing was
     * recorded, and the caller is handed the failure. A completion the guard <em>refused</em> throws
     * nothing - the claim behind this run was reclaimed while it worked, and the guard reports
     * rather than raises - so the rollback is asked for explicitly: a register recorded under a
     * claim somebody else holds is a register the new owner records again and this one only
     * supersedes. And a completion that was admitted commits with the register it belongs to.
     *
     * <p>The rollback is local to this transaction, so it ends the transaction without raising: the
     * caller is answered with the guard's own decision and settles the delivery on it, which is the
     * division of labour the port describes.
     *
     * @param transaction the recording's transaction, marked for rollback where the completion
     *                    did not stand
     * @param recording   what the recording wrote, or the row it answered a redelivery with
     * @param completion  the completion of the command
     * @return the pair, whether or not it is about to be committed
     */
    private static RecordedCompletion completed(final TransactionStatus transaction,
            final RecordOutcome recording, final Supplier<GuardDecision> completion) {
        final GuardDecision decision = completion.get();
        if (!(decision instanceof GuardDecision.Complete)) {
            transaction.setRollbackOnly();
        }
        return new RecordedCompletion(recording, decision);
    }

    /**
     * The register a command already holds, where a delivery of it has been here before.
     *
     * <p>Answered from the row rather than inferred from a refusal, so the redelivery is settled the
     * same way whether it arrived after the first delivery committed or beside it.
     *
     * @param command the request being recorded
     * @return the recording this command already has, or empty where it has none
     * @throws IllegalStateException if the command holds an output row no recording wrote, which is
     *                               a {@code progression-post} row left by an earlier release: its
     *                               register was never recorded and this one cannot be, because the
     *                               command may hold only one output row
     */
    private Optional<RecordOutcome> recorded(final DistributionCommand command) {
        return jdbcClient.sql(RECORDED_REGISTER)
                .param(SOURCE, command.source())
                .param(REQUEST_ID, command.requestId())
                .query((rs, rowNumber) -> recordedBy(command, rs))
                .optional();
    }

    /** The recording a row stands for, and a refusal where the row is not a recording at all. */
    private static RecordOutcome recordedBy(final DistributionCommand command, final ResultSet rs)
            throws SQLException {
        final String status = rs.getString("status");
        if (!RECORDER_STATUSES.contains(status)) {
            throw new IllegalStateException(identityOf(command) + " already holds a " + status
                    + " output row, which a submission wrote and no recording may replace");
        }
        return new RecordOutcome(rs.getObject("output_id", UUID.class),
                rs.getObject(SUPERSEDED_OUTPUT_ID, UUID.class));
    }

    /**
     * Whether a refusal is the named unique key's, asked of the driver's own message.
     *
     * <p>The two keys a recording can meet mean opposite things - one delivery of one command twice,
     * and two commands racing for one hearing's day - so which was violated decides whether the
     * attempt is answered or made again, and a refusal that is neither of them is a third answer.
     * Spring reports all of them as {@link DuplicateKeyException}; the constraint's name survives
     * only on the cause the driver raised, so each key is asked for by name.
     */
    private static boolean violates(final DuplicateKeyException collision, final String key) {
        final Throwable cause = NestedExceptionUtils.getMostSpecificCause(collision);
        return cause.getMessage() != null && cause.getMessage().contains(key);
    }

    /**
     * A refusal that is neither of the two keys this recorder settles.
     *
     * <p>Reported as a classified, non-transient failure rather than tried again: the two keys the
     * recorder knows mean something it can act on - this command arriving beside itself, and the
     * race for the day's active register - and a constraint outside them is a rule nobody wrote
     * this recording against. Retrying it would spend three attempts reaching the same refusal and
     * then report contention, which is a transient failure: the broker would redeliver the same
     * register into the same rule four more times and park it under an exhaustion that says the
     * service ran out of tries rather than that the row was refused.
     *
     * <p>The message names the constraint through the cause and nothing about the register itself
     * (constitution Principle VII).
     */
    private static RegisterNotRecordedException unaccountedFor(final DistributionCommand command,
            final DuplicateKeyException collision) {
        return new RegisterNotRecordedException("the recording was refused by a unique key this "
                + "store does not account for, so no redelivery of it can be recorded either; "
                + identityOf(command), collision);
    }

    /**
     * The command a refusal is about, in the only vocabulary a message from this class may use.
     *
     * <p>The correlation set and nothing else: a refusal raised from a recording travels into an
     * ERROR line and a dead-letter description, and a register is a document about children
     * (constitution Principle VII).
     *
     * @param command the request being recorded
     * @return the source and request id, as every refusal here names them
     */
    private static String identityOf(final DistributionCommand command) {
        return "source=" + command.source() + " requestId=" + command.requestId();
    }

    /** The state a redelivery cannot be in: refused by the command key, with no row behind it. */
    private static IllegalStateException unrecorded(final DistributionCommand command,
            final DuplicateKeyException collision) {
        return new IllegalStateException(identityOf(command) + " was refused by " + COMMAND_KEY
                + ", and the row that refused it is not there to be answered with", collision);
    }

    /**
     * The recording statement itself, on a snapshot that has just been read for this command.
     *
     * <p>Issued inside {@link #insert(Recording)}'s transaction and opening none of its own: the
     * read that decided this command has no register yet and the write that gives it one belong to
     * the same transaction, or a redelivery could be answered from a snapshot the write no longer
     * agrees with.
     */
    private RecordOutcome write(final Recording recording) {
        final CourtRegisterDocument document = recording.document();
        final Instant registerTime = instantOf(document.registerDate(), "registerDate");
        final Instant hearingDate = instantOf(document.hearingDate(), "hearingDate");
        return jdbcClient.sql(RECORD_REGISTER)
                .param(OUTPUT_ID, recording.outputId())
                .param(SOURCE, recording.command().source())
                .param(REQUEST_ID, recording.command().requestId())
                .param(COURT_CENTRE_ID, UUID.fromString(document.courtCentreId()))
                .param("courtCentreOuCode", recording.courtCentreOuCode(), Types.VARCHAR)
                .param(REGISTER_DATE, LocalDate.ofInstant(registerTime, LONDON))
                .param("fileName", document.fileName())
                .param("digest", digestOf(recording.json()))
                .param("document", recording.json())
                .param(HEARING_ID, UUID.fromString(document.hearingId()))
                .param("hearingDate", offsetOf(hearingDate))
                .param("courtHouse", courtHouseOf(document))
                .param(REGISTER_TIME, offsetOf(registerTime))
                .param("defendantType", recording.defendantType())
                .param("flagState", recording.flagState().name())
                .query((rs, rowNumber) -> new RecordOutcome(recording.outputId(),
                        rs.getObject(SUPERSEDED_OUTPUT_ID, UUID.class)))
                .single();
    }

    @Override
    public List<RegisterRecord> activeUnbatched() {
        return StoreOutage.translating("read the registers awaiting a batch",
                () -> jdbcClient.sql(ACTIVE_UNBATCHED)
                        .query((rs, rowNumber) -> registerRecord(rs))
                        .list());
    }

    @Override
    public List<RecordedRegisterSummary> recordedUnbatchedBefore(final Instant recordedBefore) {
        return StoreOutage.translating("read the registers the last scheduled run left waiting",
                () -> jdbcClient.sql(RECORDED_UNBATCHED_BEFORE)
                        .param("recordedBefore", offsetOf(recordedBefore))
                        .query((rs, rowNumber) -> recordedSummary(rs))
                        .list());
    }

    @Override
    public List<RegisterRecord> batched(final UUID batchId) {
        return StoreOutage.translating("read the registers of a batch",
                () -> jdbcClient.sql(BATCH_REGISTERS)
                        .param(BATCH_ID, batchId)
                        .query((rs, rowNumber) -> registerRecord(rs))
                        .list());
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the batch is empty or holds a record from another key
     * @throws IllegalStateException    if a record stopped being available between the read and the
     *                                  stamp, so the assembled batch would not be the one asked for
     */
    @Override
    public RegisterBatch assemble(final RegisterBatch batch, final List<RegisterRecord> records) {
        final CourtCentreDay key = batch.key();
        if (records.isEmpty()) {
            throw new IllegalArgumentException("a batch is assembled from at least one register: "
                    + key.courtCentreId() + " on " + key.registerDate());
        }
        final List<UUID> outputIds = records.stream().map(RegisterRecord::outputId).toList();
        records.stream()
                .filter(record -> !key.equals(record.key()))
                .findFirst()
                .ifPresent(foreign -> {
                    throw new IllegalArgumentException("register " + foreign.outputId()
                            + " belongs to " + foreign.key() + ", not to " + key);
                });
        return StoreOutage.translating("assemble a batch",
                () -> transactions.execute(transaction -> stamp(batch, outputIds)).batch());
    }

    /**
     * {@inheritDoc}
     *
     * <p>A run with nothing active asks nothing at all: an empty {@code IN} list is a statement
     * Postgres refuses rather than answers, and there is no history to read for keys nobody holds a
     * register for.
     *
     * <p>The keys go down as row values, which is the same pair {@code idx_register_batch_live_key}
     * is built on. A read that asked for the court centres and the days separately would answer a
     * cross product - another day's finished batch offered as this day's history - and the
     * supplementary index would be counted off a document for a different set of children.
     */
    // PMD.OnlyOneReturn: "no keys" is answered without issuing anything, and saying so where it is
    // decided is the whole of the guard - an empty IN list is a statement Postgres refuses.
    @SuppressWarnings("PMD.OnlyOneReturn")
    @Override
    public List<RegisterBatch> batchesFor(final Collection<CourtCentreDay> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        final List<Object[]> pairs = keys.stream()
                .map(key -> new Object[] {key.courtCentreId(), key.registerDate()})
                .toList();
        return StoreOutage.translating("read the batches recorded for a run's keys",
                () -> jdbcClient.sql(BATCHES_FOR_KEYS)
                        .param("keys", pairs)
                        .query((rs, rowNumber) -> recordedBatch(rs))
                        .list());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Asked by the day alone, and so deliberately not narrowed by a court centre: a support call
     * is about a register date, and the court centre whose whole night failed is precisely the one
     * the caller cannot name in advance. {@link #batchesFor(Collection)} is the run's read and this
     * is the person's, and neither can answer the other's question.
     */
    @Override
    public List<RegisterBatch> batchesOn(final LocalDate registerDate) {
        return StoreOutage.translating("read the batches recorded for a register day",
                () -> jdbcClient.sql(BATCHES_ON_DAY)
                        .param(REGISTER_DATE, registerDate)
                        .query((rs, rowNumber) -> recordedBatch(rs))
                        .list());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Nothing asked for is nothing issued, exactly as {@link #batchesFor(Collection)} answers a
     * run with nothing active: an empty {@code IN} list is a statement Postgres refuses rather than
     * answers, and a caller that named no batch is not asking about one.
     *
     * <p>An identity with no row is left out rather than answered for, which is the contract: a
     * stamp that was refused took its row with it and a batch the run deadline never reached was
     * never written down, so the answer is smaller than the question on exactly the nights it should
     * be.
     */
    // PMD.OnlyOneReturn: "no identities" is answered without issuing anything, and saying so where
    // it is decided is the whole of the guard - an empty IN list is a statement Postgres refuses.
    @SuppressWarnings("PMD.OnlyOneReturn")
    @Override
    public List<RegisterBatch> batchesNamed(final Collection<UUID> batchIds) {
        if (batchIds.isEmpty()) {
            return List.of();
        }
        return StoreOutage.translating("read a run's own batches back by identity",
                () -> jdbcClient.sql(BATCHES_BY_IDENTITY)
                        .param("batchIds", List.copyOf(batchIds))
                        .query((rs, rowNumber) -> recordedBatch(rs))
                        .list());
    }

    /**
     * The assembly statement and the count that judges it, inside the transaction that undoes both.
     *
     * <p>The refusal is thrown from here rather than from the caller precisely so that it is thrown
     * <em>inside</em> the transaction: a check made after the transaction returned would be a check
     * on a batch that is already committed, which is the state this method exists to make
     * impossible.
     */
    private Assembled stamp(final RegisterBatch batch, final List<UUID> outputIds) {
        final Assembled assembled = jdbcClient.sql(ASSEMBLE_BATCH)
                .param(BATCH_ID, batch.batchId())
                .param("fileName", batch.fileName())
                .param("systemGenerated", batch.systemGenerated())
                .param("supplementOf", batch.supplementOf())
                .param("supplementIndex", batch.supplementIndex())
                .param("firstOutputId", outputIds.getFirst())
                .param(OUTPUT_IDS, outputIds)
                .query((rs, rowNumber) -> new Assembled(assembledBatch(rs), rs.getLong("stamped_rows")))
                .single();
        if (assembled.stampedRows() != outputIds.size()) {
            throw new IllegalStateException(BATCH + assembled.batch().batchId() + " was asked for "
                    + outputIds.size() + " registers and stamped " + assembled.stampedRows()
                    + "; a register was superseded or batched elsewhere in between");
        }
        return assembled;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The one write in this class that settles no transition, and it is fenced all the same. A
     * batch that is no longer PENDING has already had a render asked for about the id it carries,
     * and a second mint over the top of that would leave the outcome event for the first payload
     * correlated to a row naming the second - which is the attribution this whole ordering exists
     * to protect.
     *
     * @throws IllegalStateException if there is no such batch, if it is no longer PENDING, or if it
     *                               changed under the statement
     */
    @Override
    public void markPayloadMinted(final UUID batchId, final UUID payloadFileId) {
        StoreOutage.translatingUpdate("mint a batch's payload id", () -> {
            final long batches = jdbcClient.sql(MARK_PAYLOAD_MINTED)
                    .param(BATCH_ID, batchId)
                    .param(PAYLOAD_FILE_ID, payloadFileId)
                    .update();
            if (batches != ONE_BATCH) {
                throw new IllegalStateException(BATCH + batchId + " was not given a payload id; it "
                        + "is not " + BatchStatus.PENDING + ", or it changed under the statement");
            }
        });
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if there is no such batch, if the state machine refuses the
     *                               move, or if the batch changed under the statement
     */
    @Override
    public void markRequested(final UUID batchId, final UUID payloadFileId) {
        StoreOutage.translatingUpdate("mark a batch requested", () -> {
            final BatchStatus expected = permitted(batchId, BatchStatus.GENERATING);
            settle(jdbcClient.sql(MARK_REQUESTED)
                    .param(BATCH_ID, batchId)
                    .param(EXPECTED, expected.name())
                    .param(PAYLOAD_FILE_ID, payloadFileId)
                    .update(), batchId, expected, BatchStatus.GENERATING);
        });
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the mark names no completion mechanism
     * @throws IllegalStateException    if there is no such batch, if the state machine refuses the
     *                                  move, or if the batch changed under the statement
     */
    @Override
    public void markGenerated(final UUID batchId, final UUID documentFileId,
            final Instant generatedAt, final CompletedBy completedBy) {
        if (completedBy == null) {
            throw new IllegalArgumentException(BATCH + batchId + " cannot be marked GENERATED "
                    + "without naming the mechanism that learned it");
        }
        StoreOutage.translatingUpdate("mark a batch generated", () -> {
            final BatchStatus expected = permitted(batchId, BatchStatus.GENERATED);
            settle(jdbcClient.sql(MARK_GENERATED)
                    .param(BATCH_ID, batchId)
                    .param(EXPECTED, expected.name())
                    .param("documentFileId", documentFileId)
                    .param("generatedAt", offsetOf(generatedAt))
                    .param("completedBy", name(completedBy), Types.VARCHAR)
                    .query(Long.class)
                    .single(), batchId, expected, BatchStatus.GENERATED);
        });
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the attribution disagrees with the reason: a reason
     *                                  somebody outside this service reported that names no
     *                                  mechanism, or one of this service's own that names one
     * @throws IllegalStateException    if there is no such batch, if the state machine refuses the
     *                                  move, or if the batch changed under the statement
     */
    @Override
    public void markFailed(final UUID batchId, final BatchFailureReason reason,
            final String sdgReason, final CompletedBy completedBy) {
        attributionOf(batchId, reason, completedBy);
        StoreOutage.translatingUpdate("mark a batch failed", () -> {
            final BatchStatus expected = permitted(batchId, BatchStatus.FAILED);
            settle(jdbcClient.sql(MARK_FAILED)
                    .param(BATCH_ID, batchId)
                    .param(EXPECTED, expected.name())
                    .param("reason", reason.name())
                    .param("sdgReason", RegisterBatch.boundedReason(sdgReason), Types.VARCHAR)
                    .param("completedBy", name(completedBy), Types.VARCHAR)
                    .param("releaseRows", RELEASING_REASONS.contains(reason))
                    .query(Long.class)
                    .single(), batchId, expected, BatchStatus.FAILED);
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>The three reasons {@link #RELEASING_REASONS} does not name are the ones this statement
     * exists for, and nothing but a person's own command may issue it.
     *
     * <p><strong>The state is read before anything is written, and that read is the refusal.</strong>
     * A release that changed no rows means two different things - a FAILED batch whose own failure
     * had already released them, and a batch that never failed at all - and only the first is an
     * answer. So FAILED is asked for by name: a stamp cleared off a GENERATING batch's rows would
     * let the next run assemble a second batch for a day systemdocgenerator is still rendering and
     * both would reach the same Youth Offending Team, a GENERATED batch holds a document a release
     * would throw away, and a NOTIFIED one has already been sent.
     *
     * <p>No transition is settled and none is asked of {@link BatchStatus}: FAILED is terminal, so
     * there is no arrow for this to be judged against and nothing can move the batch out from under
     * the statement either. The batch row is left FAILED, carrying what happened to it.
     *
     * @throws IllegalStateException if there is no such batch, or if it is in any state but FAILED
     */
    @Override
    public List<RegisterRecord> releaseFailed(final UUID batchId) {
        return StoreOutage.translating("release a failed batch's registers", () -> {
            permittedRelease(batchId);
            return jdbcClient.sql(RELEASE_FAILED)
                    .param(BATCH_ID, batchId)
                    .query((rs, rowNumber) -> registerRecord(rs))
                    .list();
        });
    }

    /**
     * The one state a release may be asked for, read where the release is made.
     *
     * <p>Asked here rather than left to the statement's own count, because a count of nought is an
     * answer for a FAILED batch and a refusal for every other state, and one number cannot be both.
     * The statement carries the predicate as well, so nothing is released against a row this read no
     * longer agrees with.
     *
     * <p>The message names the batch and the state it is in and nothing else; neither is about a
     * document whose every defendant is a child (constitution Principle VII).
     */
    private void permittedRelease(final UUID batchId) {
        final BatchStatus current = jdbcClient.sql(READ_BATCH_STATUS)
                .param(BATCH_ID, batchId)
                .query(String.class)
                .optional()
                .map(BatchStatus::valueOf)
                .orElseThrow(() -> new IllegalStateException(
                        "no register batch " + batchId + " to release registers from"));
        if (current != BatchStatus.FAILED) {
            throw new IllegalStateException(BATCH + batchId + " is " + current + " rather than "
                    + BatchStatus.FAILED + ", so its registers are not a person's to take back");
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>No state is read before the write and no transition is asked of {@link BatchStatus}: the
     * staleness rule is the statement's own predicate, so a batch that ceased to match between the
     * call and the row being written is one this operation did not change rather than one it was
     * refused over. <strong>No batch that stopped being stale can therefore end a run</strong>,
     * which is the point - the caller goes on to assemble whatever the release gave back.
     *
     * <p><strong>The stale batches are read first, and each is then its own write.</strong> The
     * read carries the same predicate and decides nothing: every batch it names is judged again by
     * the statement that writes its row, so a batch that stopped being stale in between matches
     * nothing and is left alone. What the read buys is the unit of atomicity. One statement over
     * every stale batch would make one court centre's refusal every court centre's rollback, which
     * is the run-ending outcome FR-003a forbids, reached through the transaction rather than
     * through an exception.
     *
     * <p><strong>The one thing the predicate cannot fence, and what is done about it.</strong> A
     * statement reads one snapshot, so a hearing re-shared after this one began is a successor the
     * {@code stamped} search cannot see, however plainly it is one by the time the write lands. The
     * release would then clear the stale register's stamp <em>beside</em> its replacement rather
     * than superseding against it, and {@value #ACTIVE_ROW_KEY} refuses the second active row for
     * the key. That is a lost race and not a rule this operation can never meet, so it is retried
     * the way {@link #recordAndComplete} retries the same index: this batch's statement is made
     * again, on a fresh snapshot that has the re-share in it, up to {@value #RECORD_ATTEMPTS}
     * times. The rolled-back attempt changed nothing, so each retry starts from the store as it
     * stands.
     *
     * <p><strong>Exhaustion is reported, never thrown.</strong> A batch whose every attempt met the
     * same refusal is left exactly as it was found and named in
     * {@link StaleReleaseOutcome#contended()}, and the operation goes on to the batches after it
     * and answers normally. The alternative was an exception, and an exception here is the run-
     * ending outcome again: the pass runs inline in the night's generation, so one hearing shared
     * at the wrong moment would cost every court centre its document. A contended batch is stale
     * still and untouched, so the next run reaches it again, and the 07:00 report names its court
     * centre day as a late batch every morning meanwhile.
     *
     * <p><strong>Each attempt is its own transaction, and this method takes that boundary rather
     * than asking to be called outside one.</strong> Wrapped in a caller's transaction the first
     * refusal would abort that transaction, every retry would be made inside an aborted one and
     * all three would fail - and the other batches would go down with them, which is the very
     * thing the per-batch shape is for. A precondition nothing enforces is a comment, so each
     * attempt runs through {@link #perBatchTransactions} with
     * {@code PROPAGATION_REQUIRES_NEW}: whatever the caller had open is suspended for the length
     * of the attempt and resumed afterwards, and an attempt commits or rolls back by itself
     * whoever called this and from where.
     *
     * <p>A refusal on any other rule - another unique key, or a constraint that is no key at all,
     * such as the bounded reason a store left short of {@code V6} does not admit - is the store
     * saying this write may never be made, which no retry changes; it is raised, translated into
     * this package's own
     * {@link uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotReleasedException} so that no refusal
     * crosses the port into {@code batch/} as an {@code org.springframework.dao} type, where the
     * pass that calls this may name none (constitution Principle V). It is a fault in the schema or in
     * this statement rather than a race, and it is allowed to end the run the way any programming
     * error is: what FR-003a forbids is one batch's <em>ordinary</em> ending - a lost race for the
     * day's key - taking the other court centres' releases with it, and that ending is the
     * contended one above.
     */
    @Override
    public StaleReleaseOutcome failAndReleaseStale(final Instant scheduledCutoff,
            final Instant manualCutoff, final StaleReleaseProgress progress) {
        return StoreOutage.translating("fail and release the stale batches",
                () -> eachStaleBatch(scheduledCutoff, manualCutoff, progress));
    }

    /**
     * Every stale batch in turn, each accounted for as released or as contended.
     *
     * <p><strong>Each is announced where it is settled, and not only in the answer.</strong> Every
     * attempt commits by itself, so a batch this walk has passed is durably failed and released
     * whatever becomes of the batch after it. A walk that ends in a throw - a store that went away
     * between two batches, or a refusal no retry can settle - would otherwise take the account of
     * every batch before it with it, and the caller would publish a night that released nothing on
     * a night when it released some. The return value is the same account, whole, for a walk that
     * reached the end.
     *
     * @param scheduledCutoff the stamp at or before which a batch the schedule made is stale
     * @param manualCutoff    the stamp at or before which a batch an operator asked for is stale
     * @param progress        told about each batch as its own transaction commits
     * @return what was released, and what every attempt was refused over
     */
    private StaleReleaseOutcome eachStaleBatch(final Instant scheduledCutoff,
            final Instant manualCutoff, final StaleReleaseProgress progress) {
        final List<ReleasedBatch> released = new ArrayList<>();
        final List<UUID> contended = new ArrayList<>();
        for (final UUID batchId : staleBatches(scheduledCutoff, manualCutoff)) {
            final StaleReleaseOutcome one =
                    attemptedRelease(batchId, scheduledCutoff, manualCutoff);
            released.addAll(one.released());
            contended.addAll(one.contended());
            one.released().forEach(progress::recordReleased);
            one.contended().forEach(progress::recordContended);
        }
        return new StaleReleaseOutcome(List.copyOf(released), List.copyOf(contended));
    }

    /**
     * The batches the staleness rule names, read once so each can be written separately.
     *
     * @param scheduledCutoff the stamp at or before which a batch the schedule made is stale
     * @param manualCutoff    the stamp at or before which a batch an operator asked for is stale
     * @return their identities, oldest day first
     */
    private List<UUID> staleBatches(final Instant scheduledCutoff, final Instant manualCutoff) {
        return jdbcClient.sql(STALE_BATCHES)
                .param("scheduledCutoff", offsetOf(scheduledCutoff))
                .param("manualCutoff", offsetOf(manualCutoff))
                .query(UUID.class)
                .list();
    }

    /**
     * One batch's release, made again on a fresh snapshot for as long as a re-share keeps beating
     * it, and reported rather than thrown where none of the attempts got through.
     *
     * @param batchId         the batch this release is about, and the only row it may touch
     * @param scheduledCutoff the stamp at or before which a batch the schedule made is stale
     * @param manualCutoff    the stamp at or before which a batch an operator asked for is stale
     * @return this one batch's account: released by the winning attempt, or contended
     * @throws uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotReleasedException if the store
     *         refused the release over a rule this operation does not account for - another unique
     *         key, or a constraint that is no key at all
     */
    private StaleReleaseOutcome attemptedRelease(final UUID batchId, final Instant scheduledCutoff,
            final Instant manualCutoff) {
        List<ReleasedBatch> released = null;
        for (int attempt = 0; released == null && attempt < RECORD_ATTEMPTS; attempt++) {
            try {
                released = perBatchTransactions.execute(
                        own -> release(batchId, scheduledCutoff, manualCutoff));
            } catch (DuplicateKeyException collision) {
                if (!violates(collision, ACTIVE_ROW_KEY)) {
                    throw unaccountedForRelease(batchId, collision);
                }
            } catch (DataIntegrityViolationException refused) {
                // A rule that is no key at all - a CHECK the bounded reason does not satisfy, which
                // is what a store V6 never reached refuses every stale batch on. Not a race, so it
                // is not attempted again; translated here so that no org.springframework.dao type
                // crosses the port into batch/ (constitution Principle V).
                throw unaccountedForRelease(batchId, refused);
            }
        }
        return released == null
                ? new StaleReleaseOutcome(List.of(), List.of(batchId))
                : new StaleReleaseOutcome(released, List.of());
    }

    /**
     * The refusal a release may never make again, in the vocabulary the pass can read.
     *
     * <p>The mirror of {@link #unaccountedFor(DistributionCommand, DuplicateKeyException)} one
     * operation above, and for the same reason: the release knows one key it can act on, and a
     * rule outside it is one nobody wrote this statement against. Making the statement again would
     * spend three attempts reaching the same refusal and then report the batch as contended, which
     * says a hearing was re-shared at the wrong moment - and the next run, and every run after it,
     * would say the same about a batch no run can ever release.
     *
     * <p><strong>Every refusal, and not only the ones that are keys.</strong> A unique index is
     * one rule a store can refuse this write on and a CHECK constraint is another - a pod whose
     * store never reached {@code V6} meets {@code register_batch_failure_reason_chk} on every
     * stale batch it tries - and Spring reports the second as a bare
     * {@link DataIntegrityViolationException}. Both are translated here, because what the port
     * promises is that a refusal is readable in this package's own vocabulary: the pass in
     * {@code batch/} may name no {@code org.springframework.dao} type, so anything crossing
     * untranslated could only be caught there as {@code RuntimeException}, which is the catch that
     * swallows every programming error beside it (constitution Principle V).
     *
     * <p><strong>The store's own refusal does not travel with it, and that is the difference from
     * {@link #unaccountedFor(DistributionCommand, DuplicateKeyException)}.</strong> That one is
     * raised out of an {@code INSERT} whose refusal Postgres reports against the row being
     * inserted; this one is raised out of a statement that <em>updates</em>
     * {@code processed_output}, and a {@code processed_output} row holds the register document -
     * so a CHECK refusing it is reported with {@code Failing row contains (...)} and every column
     * of that row behind it, a youth defendant's name and date of birth included. A cause is how
     * that message reaches a stack trace, and this failure is raised out of the nightly run and
     * written at ERROR into the estate's log index (constitution Principle VII). So the driver's
     * words are dropped here, as they are for {@code StoreRefusedRowException}.
     *
     * <p>What is carried instead is bounded and written in this repository: which <em>kind</em> of
     * rule refused the write, and the batch's identity. The constraint's own name is not among it,
     * because the one idiom this class has for naming a constraint safely -
     * {@link #violates(DuplicateKeyException, String)} - asks the driver whether the refusal is a
     * key <em>this class already knows by name</em>, and a refusal that got this far is by
     * construction none of them. Reading a name back out of the message would be reading the
     * message, which is the thing that may not be kept.
     *
     * @param batchId the batch whose release was refused
     * @param refusal the store's own refusal, read for its kind and for nothing else
     * @return the failure to raise
     */
    private static RegisterNotReleasedException unaccountedForRelease(final UUID batchId,
            final DataIntegrityViolationException refusal) {
        final String rule = refusal instanceof DuplicateKeyException
                ? UNACCOUNTED_KEY : UNACCOUNTED_RULE;
        return new RegisterNotReleasedException("the release of a stale batch was refused by "
                + rule + " this store does not account for, so no attempt at it can be made again "
                + "either; batchId=" + batchId);
    }

    /**
     * One attempt at one batch's release, which is one statement inside a transaction of its own.
     *
     * @param batchId         the batch this attempt is about
     * @param scheduledCutoff the stamp at or before which a batch the schedule made is stale
     * @param manualCutoff    the stamp at or before which a batch an operator asked for is stale
     * @return the one record where this attempt changed the batch, and nothing where the batch had
     *         stopped being stale
     */
    private List<ReleasedBatch> release(final UUID batchId, final Instant scheduledCutoff,
            final Instant manualCutoff) {
        return jdbcClient.sql(FAIL_AND_RELEASE_STALE)
                .param(BATCH_ID, batchId)
                .param("reason", BatchFailureReason.NOT_COMPLETED_BY_NEXT_RUN.name())
                .param("scheduledCutoff", offsetOf(scheduledCutoff))
                .param("manualCutoff", offsetOf(manualCutoff))
                .query((rs, rowNumber) -> new ReleasedBatch(
                        rs.getObject("batch_id", UUID.class),
                        rs.getObject(COURT_CENTRE_ID_COLUMN, UUID.class),
                        rs.getObject(REGISTER_DATE_COLUMN, LocalDate.class),
                        rs.getInt("released_registers")))
                .list();
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the summary's verdict is not one a notification tally can
     *                                  produce
     * @throws IllegalStateException    if there is no such batch, if the state machine refuses the
     *                                  move, or if the batch changed under the statement
     */
    @Override
    public void markNotified(final UUID batchId, final NotificationSummary summary) {
        if (!NOTIFICATION_OUTCOMES.contains(summary.outcome())) {
            throw new IllegalArgumentException("a notification tally settles a batch NOTIFIED, "
                    + "PARTIALLY_NOTIFIED or NOTIFIED_NOBODY, not " + summary.outcome());
        }
        StoreOutage.translatingUpdate("mark a batch notified", () -> {
            final BatchStatus expected = permitted(batchId, summary.outcome());
            settle(jdbcClient.sql(MARK_NOTIFIED)
                    .param(BATCH_ID, batchId)
                    .param(EXPECTED, expected.name())
                    .param("outcome", summary.outcome().name())
                    .query(Long.class)
                    .single(), batchId, expected, summary.outcome());
        });
    }

    /**
     * The rows automatic batching passed over, which the operations CLI is the only reader of.
     *
     * <p>{@code RECORDED_WHILE_OFF} is {@code ACTIVE_UNBATCHED}'s predicate with the flag-state test
     * turned round, so the two are one predicate rather than two that can drift: a row this read
     * missed would be a row neither reading answers with, waiting for somebody to notice it.
     */
    @Override
    public List<RegisterRecord> recordedWhileOff() {
        return StoreOutage.translating("read the registers recorded while the flag was not on",
                () -> jdbcClient.sql(RECORDED_WHILE_OFF)
                        .query((rs, rowNumber) -> registerRecord(rs))
                        .list());
    }

    /**
     * The rollback's write, which the operations CLI is the only caller of.
     *
     * <p>One statement over the active predicate bounded by {@code register_time}, so the period a
     * person named is taken back in one commit and no run can read half of it. The bound is passed
     * straight through as the caller stated it: this store never defaults it, and a rollback that
     * quietly widened its own period would supersede registers nobody had decided about.
     */
    @Override
    public int supersedeSharedBefore(final Instant sharedBefore) {
        return StoreOutage.translating("supersede the registers shared before an instant",
                () -> jdbcClient.sql(SUPERSEDE_SHARED_BEFORE)
                        .param("sharedBefore", offsetOf(sharedBefore))
                        .update());
    }

    /**
     * The state machine asked once, where the move is attempted.
     *
     * <p>The status that answered is handed back so the update can carry it as a predicate: a batch
     * another run moved between the read and the write then changes no rows, and
     * {@link #settle(long, UUID, BatchStatus, BatchStatus)} says so rather than the store believing
     * a transition that did not happen.
     */
    private BatchStatus permitted(final UUID batchId, final BatchStatus next) {
        final BatchStatus current = jdbcClient.sql(READ_BATCH_STATUS)
                .param(BATCH_ID, batchId)
                .query(String.class)
                .optional()
                .map(BatchStatus::valueOf)
                .orElseThrow(() -> new IllegalStateException(
                        "no register batch " + batchId + " to move to " + next));
        if (!current.canTransitionTo(next)) {
            throw new IllegalStateException(
                    BATCH + batchId + " may not move from " + current + " to " + next);
        }
        return current;
    }

    /**
     * The attribution rule, asked of the reason itself and refused before any statement is issued.
     *
     * <p>Which endings carry a completion mechanism is
     * {@link BatchFailureReason#isGeneratorAttributed()}'s answer and not this class's, so the store
     * and {@code register_batch_completed_by_shape_chk} enforce one rule rather than two that can
     * drift. A refusal here leaves the batch exactly where the caller found it: the reason is
     * examined before the status is even read, so nothing at all was written to be undone.
     *
     * <p>Both messages name the batch and the reason and nothing else. Neither is about a document
     * whose every defendant is a child, so neither can carry a word of one (constitution Principle
     * VII).
     */
    private static void attributionOf(final UUID batchId, final BatchFailureReason reason,
            final CompletedBy completedBy) {
        if (reason.isGeneratorAttributed() && completedBy == null) {
            throw new IllegalArgumentException(BATCH + batchId + " failed " + reason
                    + ", which somebody outside this service reported, and named no mechanism");
        }
        if (!reason.isGeneratorAttributed() && completedBy != null) {
            throw new IllegalArgumentException(BATCH + batchId + " failed " + reason
                    + ", which is this service's own verdict, and named " + completedBy);
        }
    }

    /** The affected-row count is the decision, and a count of nothing is reported rather than kept. */
    private static void settle(final long batches, final UUID batchId, final BatchStatus expected,
            final BatchStatus next) {
        if (batches != ONE_BATCH) {
            throw new IllegalStateException(BATCH + batchId + " was not moved from " + expected
                    + " to " + next + "; it changed under the statement");
        }
    }

    /** The row view the batch half reads, with the document as it was stored. */
    private RegisterRecord registerRecord(final ResultSet rs) throws SQLException {
        return new RegisterRecord(
                rs.getObject("output_id", UUID.class),
                rs.getObject("hearing_id", UUID.class),
                instant(rs.getObject("hearing_date", OffsetDateTime.class)),
                new CourtCentreDay(rs.getObject(COURT_CENTRE_ID_COLUMN, UUID.class),
                        rs.getObject(REGISTER_DATE_COLUMN, LocalDate.class)),
                instant(rs.getObject("register_time", OffsetDateTime.class)),
                rs.getString("file_name"),
                rs.getString("defendant_type"),
                RecordedFlagState.valueOf(rs.getString("recorded_flag_state")),
                objectMapper.readValue(rs.getString("document"), CourtRegisterDocument.class));
    }

    /**
     * The row view the report reads: the key, the moment it was recorded, and the age of that.
     *
     * <p>No document, because nothing about a register that is late needs reading, and the less a
     * report carries the less there is to keep out of a log line.
     */
    private static RecordedRegisterSummary recordedSummary(final ResultSet rs) throws SQLException {
        return new RecordedRegisterSummary(
                rs.getObject("output_id", UUID.class),
                rs.getObject("hearing_id", UUID.class),
                rs.getObject(COURT_CENTRE_ID_COLUMN, UUID.class),
                rs.getObject(REGISTER_DATE_COLUMN, LocalDate.class),
                instant(rs.getObject("register_time", OffsetDateTime.class)),
                rs.getLong("age_seconds"));
    }

    /** The batch as the insert left it, so every stamp on it is the database's own. */
    private static RegisterBatch assembledBatch(final ResultSet rs) throws SQLException {
        return new RegisterBatch(
                rs.getObject("batch_id", UUID.class),
                rs.getObject(COURT_CENTRE_ID_COLUMN, UUID.class),
                rs.getString("court_centre_ou_code"),
                rs.getString("court_house"),
                rs.getObject(REGISTER_DATE_COLUMN, LocalDate.class),
                rs.getString("file_name"),
                null,
                null,
                BatchStatus.PENDING,
                null,
                null,
                rs.getBoolean("system_generated"),
                null,
                instant(rs.getObject("assembled_at", OffsetDateTime.class)),
                null,
                null,
                null,
                null,
                0,
                rs.getObject("supplement_of", UUID.class),
                rs.getInt("supplement_index"));
    }

    /**
     * A whole batch row, for the history a run reads before it assembles.
     *
     * <p>Every column, because the supplementary rule reads the status and the index and a caller
     * that was handed a partial row would have to know which parts were real. The two enumerated
     * columns are read through their own names, so a value the vocabulary does not hold is refused
     * here rather than carried into a decision as {@code null}.
     */
    private static RegisterBatch recordedBatch(final ResultSet rs) throws SQLException {
        return new RegisterBatch(
                rs.getObject("batch_id", UUID.class),
                rs.getObject(COURT_CENTRE_ID_COLUMN, UUID.class),
                rs.getString("court_centre_ou_code"),
                rs.getString("court_house"),
                rs.getObject(REGISTER_DATE_COLUMN, LocalDate.class),
                rs.getString("file_name"),
                rs.getObject("payload_file_id", UUID.class),
                rs.getObject("document_file_id", UUID.class),
                BatchStatus.valueOf(rs.getString("status")),
                enumOf(rs.getString("failure_reason"), BatchFailureReason::valueOf),
                rs.getString("sdg_reason"),
                rs.getBoolean("system_generated"),
                enumOf(rs.getString("completed_by"), CompletedBy::valueOf),
                instant(rs.getObject("assembled_at", OffsetDateTime.class)),
                instant(rs.getObject("requested_at", OffsetDateTime.class)),
                instant(rs.getObject("generated_at", OffsetDateTime.class)),
                instant(rs.getObject("notified_at", OffsetDateTime.class)),
                instant(rs.getObject("failed_at", OffsetDateTime.class)),
                rs.getInt("attempts"),
                rs.getObject("supplement_of", UUID.class),
                rs.getInt("supplement_index"));
    }

    /** A bounded column read back as its constant, and nothing where the column is null. */
    private static <E extends Enum<E>> E enumOf(
            final String value, final Function<String, E> constant) {
        return value == null ? null : constant.apply(value);
    }

    /** The batch the statement wrote, beside the count of rows it managed to stamp. */
    private record Assembled(RegisterBatch batch, long stampedRows) {
    }

    /**
     * One register as it will be written, held so that a retry writes the same row again.
     *
     * <p>The identifier above all: the row a retry records is the row the first attempt would have
     * recorded, under the identity the caller is answered with, so a re-share settled on the second
     * attempt is indistinguishable from one that met no race at all.
     */
    private record Recording(UUID outputId, DistributionCommand command,
            CourtRegisterDocument document, String json, String courtCentreOuCode,
            String defendantType, RecordedFlagState flagState) {
    }

    /**
     * The constant name a bounded column holds, and nothing where there is no constant.
     *
     * <p>Typed as VARCHAR at every call site: an untyped {@code null} leaves the driver to guess a
     * type from a parameter it can see nothing about, which Postgres refuses rather than guesses.
     */
    private static String name(final Enum<?> value) {
        return value == null ? null : value.name();
    }

    /**
     * The court house the register was produced at, where the document names one.
     *
     * <p>Progression kept this on its own request row and the batch's payload carries it, so it is
     * lifted out of the document at the write rather than re-derived at assembly from a document
     * that may by then have been superseded.
     */
    private static String courtHouseOf(final CourtRegisterDocument document) {
        return document.hearingVenue() == null ? null : document.hearingVenue().courtHouse();
    }

    /**
     * The document's own date-time strings, read as instants.
     *
     * <p>They are strings on the wire because the contract says {@code date-time} and defect fix C10
     * carries what was shared through unaltered; the store orders rows by them, so this is where
     * they become an instant and where a value that is not one is refused.
     */
    private static Instant instantOf(final String value, final String field) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException unparseable) {
            throw new IllegalArgumentException(
                    "the register document's " + field + " is not a date-time: " + value,
                    unparseable);
        }
    }

    /** The one hex form this service writes a SHA-256 in: sixty-four characters, lower case. */
    private static String digestOf(final String json) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(DIGEST_ALGORITHM)
                    .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            // SHA-256 is required of every Java platform, so this cannot happen on a running JVM; if
            // it ever did, no register could be recorded with the evidence of what it holds, and
            // failing loudly is the only honest response.
            throw new IllegalStateException(DIGEST_ALGORITHM + " is not available", unavailable);
        }
    }

    private static OffsetDateTime offsetOf(final Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(final OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
