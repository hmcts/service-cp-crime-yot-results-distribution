package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

import java.sql.Types;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ProcessedOutputClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationAnomaly;

/**
 * The submission half of the processed log: what was sent for a request, and how it went.
 *
 * <p>The request-level log answers "has this request been dealt with"; this one answers "has this
 * hearing's register already gone", which is the only question that makes a redelivery safe.
 * {@code add-court-register} is not idempotent — every POST appends a {@code CourtRegisterRecorded}
 * event and a {@code court_register_request} row in progression — so a redelivery that could not
 * tell must either risk a duplicate or risk a loss.
 *
 * <p>The court register has no fan-out dimension: one hearing, one register, one POST. The row is
 * therefore keyed by the request itself, and the informant service's per-authority uniqueness gives
 * way to {@code UNIQUE (source, request_id)}. Should a fan-out dimension ever appear the constraint
 * widens rather than being rewritten, and so does the conflict target below.
 *
 * <p>Written in the same idiom as {@link ProcessedRequestRepository}, and for the same reason:
 * hand-written SQL, one method per statement, and <strong>the affected-row count is the
 * decision</strong>. In particular the claim and the skip are one statement, not a read followed by
 * a write. Two deliveries of the same request can be in flight at once, and a {@code SELECT status}
 * that came back {@code PENDING} would already be stale by the time the caller acted on it; a
 * conditional upsert cannot be, because the database decides.
 *
 * <p><strong>Every statement here writes a POSTing row and only a POSTing row.</strong> Since V2
 * this table is also the register store, and RECORDED, GENERATED, NOTIFIED and SUPERSEDED rows sit
 * beside the PENDING/POSTED/FAILED ones these three statements own. So each one requires
 * {@code status IN ('PENDING', 'FAILED')} - the two states a POST may be attempted from, POSTED
 * being terminal - <em>and</em> {@code batch_id IS NULL}. A widened predicate such as
 * {@code status <> 'POSTED'} would let a redelivery arriving in {@code progression-post} mode
 * re-claim a recorded register back to PENDING, replace the digest of the document with the digest
 * of a POST body, and settle it POSTED: the register would then be gone from {@code activeUnbatched}
 * while still stamped into a batch built from it.
 *
 * <p><strong>Every statement here is fenced on the request's claim.</strong> The state predicate
 * answers "has this register gone"; it does not answer "may
 * <em>this runner</em> speak for this request", and without that second question a runner whose
 * claim was reclaimed while it worked can still claim the output row, replace the digest of the body
 * the winner is about to send, and settle it POSTED or FAILED underneath the runner that holds the
 * request. So each statement joins back to {@code processed_request} and requires the claim owner
 * <em>and</em> the claim token this run acquired — the same predicate
 * {@link ProcessedRequestRepository}'s outcome writes carry, applied to the row that actually
 * decides whether a register is sent again.
 *
 * <p>Owner and token, and deliberately <strong>not</strong> the expiry. Claim liveness is decided by
 * the database in the reclaim statement, and only there: a run whose lease lapsed while nobody
 * reclaimed it still holds the only claim there is, and refusing its {@code recordPosted} would
 * strand an accepted register at PENDING for the next delivery to POST a second time. A lapsed claim
 * is not a superseded one; being superseded is what moves the token.
 *
 * <p>Every timestamp comes from the database, so no row's age depends on how well two pods' clocks
 * agree.
 */
public class ProcessedOutputRepository {

    private static final String SOURCE = "source";
    private static final String REQUEST_ID = "requestId";
    private static final String OWNER = "owner";
    private static final String TOKEN = "token";
    private static final String RESPONSE_CODE = "responseCode";

    /**
     * Statement 1 — claim this request for a POST, or discover its register has already gone.
     *
     * <p>The row is written <em>before</em> the POST so that an ambiguous outcome — a timeout, a
     * dropped connection — still leaves evidence that something was attempted and what was in it.
     *
     * <p>The {@code DO UPDATE ... WHERE} is the skip rule of the design rules expressed as a
     * predicate rather than as a branch in Java: a conflicting row that is already POSTED matches
     * nothing, so the statement affects no rows and the caller is told, in the same breath, both
     * that the row exists and that it must not send again. Only PENDING left by a crash and FAILED
     * left by a refusal are re-claimed, and their contents replaced, because they describe the body
     * that is about to be sent rather than the one that was. A row in any of the register states,
     * and any row already stamped with a batch, is not a POST at all and is left alone.
     * {@code response_code} is cleared with them: a PENDING row still carrying the status line of
     * the attempt before it would read as though this attempt had already been answered.
     *
     * <p>{@code output_id} is supplied by the caller and survives a re-claim untouched: the conflict
     * branch leaves it alone, so the identity of an output row is fixed the first time it is
     * written.
     *
     * <p>The values are selected <em>from</em> {@code processed_request} rather than supplied
     * outright, which is what fences the statement: the row the claim describes exists only where
     * that request still carries this run's owner and token, so a superseded runner's insert selects
     * nothing, reaches no conflict branch, and affects no rows. The key columns are taken from the
     * claim row itself, so the output can only ever be written against the request the runner
     * actually holds.
     *
     * <p><strong>The five V2 columns this statement fills say what a POST is.</strong> Since V2 the
     * table is the register store, and five of its columns exist to hold a register; a submission
     * has none of them to offer, because it records what was sent to progression rather than a
     * register this service holds. They are nullable and {@code processed_output_recorded_shape_chk}
     * does not bind a PENDING row, so the statement could leave them empty - it fills them anyway,
     * with the migration's own backfill values, so that a {@code progression-post} row says the same
     * thing however it arrived: an empty JSON object no reader can mistake for a
     * {@code CourtRegisterDocument} - which always carries at least a {@code documentType} - the
     * hearing and the hearing day the claim already names, the instant of the claim as the nearest
     * thing a POST has to a register instant, and UNKNOWN for a flag no submission ever reads.
     * {@code request_digest} on such a row goes on being the digest of the bytes posted; only rows
     * the recorder writes carry a digest of {@code document}.
     */
    private static final String CLAIM_PENDING = """
            INSERT INTO processed_output (
                output_id, source, request_id, court_centre_id, court_centre_ou_code,
                register_date, file_name, status, request_digest, anomaly_summary,
                document, hearing_id, hearing_date, register_time, recorded_flag_state,
                created_at, updated_at)
            SELECT
                :outputId, claimed.source, claimed.request_id, :courtCentreId, :courtCentreOuCode,
                :registerDate, :fileName, 'PENDING', :digest, :anomalySummary,
                CAST('{}' AS jsonb), claimed.hearing_id,
                CAST(claimed.hearing_day AS timestamp) AT TIME ZONE 'Europe/London', now(),
                'UNKNOWN',
                now(), now()
              FROM processed_request claimed
             WHERE claimed.source = :source
               AND claimed.request_id = :requestId
               AND claimed.claim_owner = :owner
               AND claimed.claim_token = :token
            ON CONFLICT (source, request_id) DO UPDATE
               SET status = 'PENDING',
                   court_centre_id = EXCLUDED.court_centre_id,
                   court_centre_ou_code = EXCLUDED.court_centre_ou_code,
                   register_date = EXCLUDED.register_date,
                   file_name = EXCLUDED.file_name,
                   request_digest = EXCLUDED.request_digest,
                   anomaly_summary = EXCLUDED.anomaly_summary,
                   response_code = NULL,
                   updated_at = now()
             WHERE processed_output.status IN ('PENDING', 'FAILED')
               AND processed_output.batch_id IS NULL
            """;

    /**
     * Statement 2 — the POST was accepted.
     *
     * <p>The state predicate is the same rule as the claim's, applied to the outcome:
     * <strong>POSTED is terminal</strong>, and a register this service recorded was never a POST. It
     * costs nothing on the ordinary path and it says, in the statement rather than in a comment,
     * that no later write may move a row out of the state that stops it being sent again, nor into
     * one it was never in.
     *
     * <p>The {@code EXISTS} is the other half, and the one the state predicate cannot stand in for:
     * a superseded runner writing POSTED before the winner's POST has happened would leave the log
     * saying a register went that nobody sent, and terminally, because POSTED is what stops it being
     * sent again.
     */
    private static final String RECORD_POSTED = """
            UPDATE processed_output
               SET status = 'POSTED', response_code = :responseCode, updated_at = now()
             WHERE source = :source AND request_id = :requestId
               AND status IN ('PENDING', 'FAILED')
               AND batch_id IS NULL
               AND EXISTS (SELECT 1
                             FROM processed_request claimed
                            WHERE claimed.source = processed_output.source
                              AND claimed.request_id = processed_output.request_id
                              AND claimed.claim_owner = :owner
                              AND claimed.claim_token = :token)
            """;

    /**
     * Statement 3 — the POST did not succeed, however it failed.
     *
     * <p>{@code request_digest} and {@code anomaly_summary} are deliberately left in place. What was
     * attempted is the reconciliation evidence, and it is worth more after a failure than after a
     * success.
     *
     * <p>{@code response_code} is nullable here and not in statement 2, because a connect failure or
     * a timeout has no status line to record — and an ambiguous POST is retried, so the row saying
     * an attempt happened without saying how it ended is exactly the state that warns a duplicate is
     * possible.
     *
     * <p>The state predicate is the one that has to be there. Two deliveries of a request can
     * overlap - a runner whose claim was reclaimed while it worked is still running - and without
     * the predicate that runner's late failure would move a register the winner had already POSTED
     * back to FAILED. The next delivery would then re-claim it and POST a second,
     * non-idempotent {@code add-court-register}: a duplicate register created by the very log that
     * exists to prevent one.
     *
     * <p>It is not sufficient on its own, which is what the {@code EXISTS} adds. The overlapping
     * runner's POST can also finish <em>before</em> the winner's, and a FAILED written then moves a
     * row the winner is still working on out of PENDING — the same duplicate, arrived at from the
     * other direction.
     */
    private static final String RECORD_FAILED = """
            UPDATE processed_output
               SET status = 'FAILED', response_code = :responseCode, updated_at = now()
             WHERE source = :source AND request_id = :requestId
               AND status IN ('PENDING', 'FAILED')
               AND batch_id IS NULL
               AND EXISTS (SELECT 1
                             FROM processed_request claimed
                            WHERE claimed.source = processed_output.source
                              AND claimed.request_id = processed_output.request_id
                              AND claimed.claim_owner = :owner
                              AND claimed.claim_token = :token)
            """;

    private final JdbcClient jdbcClient;

    /**
     * Creates the repository over the processed log's connection.
     *
     * @param jdbcClient the processed log's connection
     */
    public ProcessedOutputRepository(final JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Statement 1 — claim this request for submission, writing the row the POST will be judged by.
     *
     * @param runClaim the claim the run was made under; its key is the row written
     * @param claim    what is about to be sent, and what it was assembled from
     * @return whether this delivery may POST. False means either that the register is already
     *         POSTED and is skipped — how a replay of a request that succeeded avoids sending it
     *         twice — or that this runner's claim was reclaimed while it worked, in which case it
     *         has no request to speak for and must discard its result.
     */
    public boolean claimPending(final RunClaim runClaim, final ProcessedOutputClaim claim) {
        return StoreOutage.translating("claim an output row", () -> affected(jdbcClient
                .sql(CLAIM_PENDING)
                .param("outputId", claim.outputId())
                .param(SOURCE, runClaim.source())
                .param(REQUEST_ID, runClaim.requestId())
                .param(OWNER, runClaim.owner())
                .param(TOKEN, runClaim.token())
                .param("courtCentreId", claim.courtCentreId())
                .param("courtCentreOuCode", claim.courtCentreOuCode(), Types.VARCHAR)
                .param("registerDate", claim.registerDate())
                .param("fileName", claim.fileName())
                .param("digest", claim.requestDigest(), Types.VARCHAR)
                .param("anomalySummary", anomalySummary(claim.anomalies()), Types.VARCHAR)
                .update()));
    }

    /**
     * Statement 2 — record that progression accepted the register.
     *
     * @param runClaim     the claim the run was made under; its key is the row settled
     * @param responseCode the status line progression answered with
     * @return whether a row was moved to POSTED; false means no row was ever claimed for this
     *         request, it was already POSTED by an overlapping delivery, or this runner's claim is
     *         no longer the one the request carries
     */
    public boolean recordPosted(final RunClaim runClaim, final int responseCode) {
        return StoreOutage.translating("record an accepted submission",
                () -> affected(outcome(RECORD_POSTED, runClaim, responseCode)));
    }

    /**
     * Statement 3 — record that the register did not go.
     *
     * @param runClaim     the claim the run was made under; its key is the row settled
     * @param responseCode the status line progression answered with, or {@code null} where there
     *                     was no answer to record
     * @return whether a row was moved to FAILED; false means no row was ever claimed for this
     *         request, it is POSTED and must not be moved out of it, or this runner's claim is no
     *         longer the one the request carries
     */
    public boolean recordFailed(final RunClaim runClaim, final Integer responseCode) {
        return StoreOutage.translating("record a failed submission",
                () -> affected(outcome(RECORD_FAILED, runClaim, responseCode)));
    }

    /** The two outcome writes differ only in the status they set; the key predicate is common. */
    private int outcome(
            final String sql, final RunClaim runClaim, final Integer responseCode) {
        return jdbcClient.sql(sql)
                .param(SOURCE, runClaim.source())
                .param(REQUEST_ID, runClaim.requestId())
                .param(OWNER, runClaim.owner())
                .param(TOKEN, runClaim.token())
                .param(RESPONSE_CODE, responseCode, Types.INTEGER)
                .update();
    }

    /**
     * Renders the counted anomalies into the one column they are stored in.
     *
     * <p>Here and nowhere else. The domain counts anomalies as a bounded map, which is what makes
     * free text impossible; how that map becomes a column is a fact about the column.
     *
     * <p><strong>Sorted by code.</strong> The transformation meets these in whatever order the
     * hearing presents them, and a rendering that inherited that order would give one set of counts
     * several representations: two identical registers would compare unequal in the reconciliation
     * the column exists for, and a re-claim would look like a change when nothing had changed.
     *
     * <p>No counts is an absent summary rather than an empty string, so "nothing was skipped" and
     * "the summary was never written" are not the same value in the column.
     */
    private static String anomalySummary(final Map<TransformationAnomaly, Integer> anomalies) {
        return anomalies.isEmpty() ? null : anomalies.entrySet().stream()
                .sorted(Comparator.comparing(counted -> counted.getKey().value()))
                .map(counted -> counted.getKey().value() + ":" + counted.getValue())
                .collect(Collectors.joining(","));
    }

    private static boolean affected(final int rows) {
        return rows > 0;
    }
}
