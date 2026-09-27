package uk.gov.hmcts.cp.yotresultsdistribution.adapter.progression;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterSubmission;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterSubmissionClient;
import uk.gov.hmcts.cp.yotresultsdistribution.application.SubmissionReceipt;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ProcessedOutputClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.SubmissionFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.ProcessedOutputRepository;

/**
 * The submission port, wired to progression's command API and to the processed log.
 *
 * <p>The order matters more than anything else in this class, because {@code add-court-register} is
 * not idempotent — every POST appends a {@code CourtRegisterRecorded} event and a
 * {@code court_register_request} row:
 *
 * <ol>
 *   <li><strong>Claim the row, before anything is sent.</strong> The same statement asks two
 *       questions at once: may this delivery send, and record that it is about to. A claim the
 *       database refuses means either that this hearing's register is already POSTED — so a replay
 *       skips it — or that this runner's claim was reclaimed while it worked, in which case it has
 *       no request to speak for.</li>
 *   <li><strong>POST.</strong> The transport's retry policy applies; what comes back out is either
 *       the accepted status or a classified failure.</li>
 *   <li><strong>Record the outcome.</strong> A failure is written down <em>before</em> it is
 *       rethrown, so the log never shows a submission in flight that nothing is going to finish, and
 *       the status progression answered is recorded with it.</li>
 * </ol>
 *
 * <p><strong>Every exit from the POST settles the row, and a settlement that lands on nothing
 * changes what happens next.</strong> Both are the same rule read twice. A failure nothing here
 * anticipated would otherwise leave the claimed row PENDING with nothing going to finish it; and a
 * fenced write that affects no row means an overlapping delivery reached the row first, or this
 * runner's claim was reclaimed while it worked — so what this runner believes happened is not what
 * the log durably says, and returning normally on it would complete the run {@code submitted} on an
 * in-memory belief. The delivery is handed back TRANSIENT instead, including where the write that
 * failed was a NON_TRANSIENT refusal: parking is a terminal verdict, and this runner's verdict is
 * not the recorded one.
 *
 * <p>What the redelivery then does is the processed log's decision, and it is the one
 * {@code SubmissionRedeliveryIT} pins against a real Postgres: a row an overlapping delivery already
 * POSTED is terminal, so the replay's claim is refused and its run completes {@code submitted}
 * without a second POST; a row left PENDING or FAILED is re-claimed and re-sent. That second case is
 * the crash-window trade this service makes deliberately — a duplicate the downstream sweep absorbs,
 * in preference to a loss nothing absorbs.
 *
 * <p><strong>What goes on the wire is the command, not the register this service records.</strong>
 * The body is projected back to the {@code add-court-register} command's own fields before anything
 * is serialised - see {@link #commandBody} - so the field increment 002 attaches to the register
 * after the contract check never reaches a POST the frozen command would refuse. The digest on the
 * row is the digest of that body, because the row exists to say what was sent.
 *
 * <p>Writing the row first is what makes an unknown outcome survivable. A POST that times out leaves
 * a PENDING row carrying the digest of exactly the bytes that were attempted and the bounded counts
 * of what the register was assembled without, so the next delivery re-sends and reconciliation can
 * tell whether the body changed. Writing it afterwards would lose precisely the case the evidence
 * exists for.
 *
 * <p><strong>This is where defect fix C1 becomes durable.</strong>
 * {@code ProcessOutboundCourtRegister/index.js:17-25} swallows the POST's errors and records
 * nothing at all, so a lost register and a delivered one are the same run. Here the status reaches
 * {@code processed_output.response_code} on both paths, and the failure continues upward carrying
 * the classification the pipeline settles the delivery on.
 *
 * <p><strong>The guarantee, stated honestly.</strong> At-most-once submission in normal operation,
 * redeliveries and replays included; across a crash in the instant between an accepted POST and the
 * row being marked POSTED, at-least-once. The duplicate is absorbed downstream by progression's
 * {@code max(register_time) per hearing_id} sweep, exactly like a re-share. Strict at-most-once
 * needs progression-side idempotency, which the frozen contract does not offer, and nothing here
 * claims otherwise.
 *
 * <p>The document is never logged, at any level. It is a register naming children and their
 * addresses; identifiers and counts are all a log line carries.
 */
public class ProgressionRegisterSubmissionClient implements RegisterSubmissionClient {

    private static final Logger LOG =
            LoggerFactory.getLogger(ProgressionRegisterSubmissionClient.class);

    private static final String DIGEST_ALGORITHM = "SHA-256";

    private final ProcessedOutputRepository outputs;
    private final ProgressionCommandGateway gateway;
    private final ObjectMapper objectMapper;

    /**
     * Creates the adapter over the processed log and the progression transport.
     *
     * @param outputs      the {@code processed_output} statements, each fenced on the run's claim
     * @param gateway      the {@code add-court-register} transport, with its retry policy
     * @param objectMapper the shared mapper, so what is sent is serialised exactly as everything else
     */
    public ProgressionRegisterSubmissionClient(
            final ProcessedOutputRepository outputs,
            final ProgressionCommandGateway gateway,
            final ObjectMapper objectMapper) {
        this.outputs = outputs;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    @Override
    public SubmissionReceipt submit(final RegisterSubmission submission) {
        final byte[] body = objectMapper.writeValueAsBytes(commandBody(submission.document()));
        final RunClaim claim = submission.claim();

        // One statement, two questions: may this delivery send, and record that it is about to. The
        // row carries the digest of exactly these bytes and the counts of what the register was
        // assembled without, because both are worth more after a failure than after a success.
        final boolean maySend = outputs.claimPending(claim, rowFor(submission, digestOf(body)));

        final SubmissionReceipt receipt;
        if (maySend) {
            receipt = new SubmissionReceipt(sent(body, submission, claim), true);
        } else {
            // Either this hearing's register is already POSTED — a replay, and the run still
            // completes `submitted` — or this runner's claim was reclaimed while it worked, and it
            // has no request to speak for. Neither may send.
            //
            // The receipt carries no status, because nothing answered this delivery;
            // `sentByThisDelivery` is what says so, and a 202 here would have the run log a call it
            // never made.
            LOG.info("The register for this request has already gone, or the claim behind it was "
                            + "reclaimed; nothing is posted. source={} requestId={}",
                    claim.source(), claim.requestId());
            receipt = new SubmissionReceipt(0, false);
        }
        return receipt;
    }

    /**
     * The POST, and the outcome write that follows it whichever way it goes.
     *
     * <p><strong>Every exit from here settles the claimed row first.</strong> The claim is a promise
     * made in the database — this delivery is about to POST, and until the row moves the log says a
     * submission is in flight — so a frame that let a failure past without settling it would leave a
     * PENDING row behind with nothing going to finish it.
     *
     * @return the status progression accepted the command with
     */
    // PMD.AvoidCatchingGenericException: this frame holds a claimed row, and the row has to be
    // settled whatever the failure was. It is a catch-and-record, not a catch-and-ignore: the
    // failure is written down and then continues upward unchanged (constitution Principle VI).
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private int sent(final byte[] body, final RegisterSubmission submission, final RunClaim claim) {
        final int responseCode;
        try {
            responseCode = gateway.post(body, submission.caller(), submission.deadline());
        } catch (SubmissionFailedException failure) {
            // Caught to record, never to absorb — the whole of defect fix C1. The row is moved to
            // FAILED carrying whatever progression answered, and a failure the log accepted
            // continues with the classification the pipeline settles the delivery on.
            throw settled(failure, claim);
        } catch (RuntimeException unexpected) {
            throw unrecorded(unexpected, claim);
        }

        if (!outputs.recordPosted(claim, responseCode)) {
            throw ambiguous(responseCode, claim);
        }
        LOG.info("The court register was submitted to progression. source={} requestId={} "
                        + "responseCode={} anomalies={}",
                claim.source(), claim.requestId(), responseCode, submission.anomalies().size());
        return responseCode;
    }

    /**
     * A classified failure, written down before it is allowed to continue.
     *
     * <p>Where the write lands on a row, the failure the transport raised is the failure the
     * pipeline settles on. Where it lands on nothing, it is not: a NON_TRANSIENT refusal is a
     * terminal verdict, and this runner has just been told its verdict is not the one the log holds.
     * Parking on it would dead-letter a request whose row may say POSTED, so the delivery is handed
     * back instead and the redelivery reads the row and decides.
     *
     * @param failure what the transport raised
     * @param claim   the claim this run holds
     * @return the failure to continue with
     */
    private SubmissionFailedException settled(
            final SubmissionFailedException failure, final RunClaim claim) {

        final SubmissionFailedException settled;
        if (outputs.recordFailed(claim, answered(failure))) {
            settled = failure;
        } else {
            unrecordable("FAILED", claim);
            settled = new SubmissionFailedException(FailureClassification.TRANSIENT,
                    ReasonCode.SUBMISSION_TRANSIENT, answered(failure));
        }
        return settled;
    }

    /**
     * Progression accepted the command and the processed log would not say so.
     *
     * <p>The ambiguous outcome, from the one direction the transport cannot see. Returning normally
     * would complete the run {@code submitted} on the strength of an in-memory belief while the row
     * that decides whether a redelivery may send again said something else — the silent success this
     * service exists to end. So the delivery is handed back TRANSIENT, and what happens next is the
     * processed log's to decide: a row an overlapping delivery already POSTED is terminal, so the
     * redelivery's claim is refused and its run completes {@code submitted} without a second POST;
     * a row left PENDING or FAILED is re-claimed and re-sent, which is the crash-window trade this
     * service makes deliberately — a duplicate progression's {@code max(register_time) per
     * hearing_id} sweep absorbs, in preference to a loss nothing absorbs and nobody sees.
     *
     * @param responseCode what progression answered
     * @param claim        the claim this run holds
     * @return the failure to continue with
     */
    private static SubmissionFailedException ambiguous(
            final int responseCode, final RunClaim claim) {
        unrecordable("POSTED", claim);
        return new SubmissionFailedException(FailureClassification.TRANSIENT,
                ReasonCode.SUBMISSION_TRANSIENT, responseCode);
    }

    /**
     * A failure nothing here anticipated, met after the row was claimed.
     *
     * <p>Settled FAILED and then let out unchanged: the pipeline's own catch-all classifies it and
     * settles the delivery, and this leg's business is only that the row it claimed does not stay
     * PENDING with nothing going to finish it. A settlement that itself affects no row needs no
     * second decision — the failure is already on its way to a hand-back, which is what a refused
     * write asks for.
     *
     * <p>The type reaches the log line and the message never does: a layer that was handling a
     * register can quote one in its message, and every defendant on a register is a child.
     *
     * @param unexpected what was raised
     * @param claim      the claim this run holds
     * @return the failure to continue with
     */
    private RuntimeException unrecorded(final RuntimeException unexpected, final RunClaim claim) {
        LOG.error("The add-court-register submission failed in a way nothing anticipated; the "
                        + "claimed row is settled before the failure continues. source={} "
                        + "requestId={} type={} reason={}",
                claim.source(), claim.requestId(), unexpected.getClass().getName(),
                ReasonCode.UNEXPECTED_FAILURE.code());
        if (!outputs.recordFailed(claim, null)) {
            unrecordable("FAILED", claim);
        }
        return unexpected;
    }

    /**
     * The status to record against a failure, or {@code null} where nothing answered.
     *
     * <p>A PENDING-turned-FAILED row with no {@code response_code} is exactly the state that warns a
     * duplicate is possible; an invented status would say an attempt was answered when none was.
     */
    private static Integer answered(final SubmissionFailedException failure) {
        return failure.responseCode().isPresent() ? failure.responseCode().getAsInt() : null;
    }

    /**
     * The register as the {@code add-court-register} command carries it.
     *
     * <p><strong>The command is not the register, and since increment 002 they differ by a
     * field.</strong> {@code defendantType} is a legal, optional field of the frozen
     * <em>register-document</em> schema ({@code courtRegisterDocumentRequest.json:30-32}) and is
     * what a recorded register is batched and printed under; the command this adapter POSTs does
     * <em>not</em> declare it and is {@code additionalProperties: false}, so a body carrying one is
     * refused with a 400 - the answer defect C1 used to swallow, on a register that was assembled
     * perfectly well.
     *
     * <p>The projection belongs here rather than in the chain because this is the only stage that
     * knows what a POST body is. {@code RegisterTransformationChain} holds the assembled document to
     * the command exactly as 001 did and attaches the type afterwards, so the register that leaves
     * the transformation is the one 002 records; what a {@code progression-post} deployment sends is
     * that register's command fields, and nothing else moves. A register with no defendant type on
     * it is handed straight back, so the ordinary body is not rebuilt to be identical to itself.
     *
     * @param document the register the run assembled
     * @return the command body: the same register, without the field the command does not declare
     */
    private static CourtRegisterDocument commandBody(final CourtRegisterDocument document) {
        return document.defendantType() == null
                ? document
                : new CourtRegisterDocument(
                        document.registerDate(),
                        document.hearingDate(),
                        document.hearingId(),
                        document.courtCentreId(),
                        document.fileName(),
                        null,
                        document.hearingVenue(),
                        document.recipients(),
                        document.defendants());
    }

    /**
     * The row written before anything is sent.
     *
     * <p>Three of its columns are not recoverable from the frozen {@code add-court-register} body,
     * which is why the submission carries them: the court centre's OU code appears there only inside
     * the file name, and the register day would have to be re-derived — a second derivation of the
     * day defect fix C12 exists to make single.
     */
    private static ProcessedOutputClaim rowFor(
            final RegisterSubmission submission, final String digest) {
        final CourtRegisterDocument document = submission.document();
        return new ProcessedOutputClaim(
                UUID.randomUUID(),
                UUID.fromString(document.courtCentreId()),
                submission.courtCentreOuCode(),
                submission.registerDay(),
                document.fileName(),
                digest,
                submission.anomalies());
    }

    /**
     * The outcome write is the durable half of a submission, so it is checked rather than assumed.
     *
     * <p>A claim was granted moments before, so a statement that affects no row means an overlapping
     * delivery reached it first and POSTED it, or that this runner's claim was reclaimed while it
     * worked. Either way two runners were working the same request, and what this one believes
     * happened is not what the log durably says — which is why every caller of this method changes
     * what it does next rather than only saying so.
     */
    private static void unrecordable(final String intended, final RunClaim claim) {
        LOG.error("Outcome write affected no row; an overlapping delivery reached it first, or "
                        + "the claim was reclaimed. source={} requestId={} intendedStatus={}",
                claim.source(), claim.requestId(), intended);
    }

    private static String digestOf(final byte[] body) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance(DIGEST_ALGORITHM).digest(body));
        } catch (NoSuchAlgorithmException unavailable) {
            // SHA-256 is required of every Java platform, so this cannot happen on a running JVM; if
            // it ever did, no submission could be recorded safely and failing loudly is the only
            // honest response.
            throw new IllegalStateException(DIGEST_ALGORITHM + " is not available", unavailable);
        }
    }
}
