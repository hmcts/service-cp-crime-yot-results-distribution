package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPause;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.RecipientSet;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NoSuchBatchException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreRefusedRowException;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.NotificationClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.NotificationSettlement;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterNotificationRepository;

/**
 * One generated batch, from its recipients to the e-mails they are told by.
 *
 * <p>The order is the discipline the rest of the service keeps: the batch's recipients are the
 * de-duplicated union across its records ({@link RecipientSet}, defect fix P4), a
 * {@code register_notification} row is minted PENDING for each of them with the
 * {@code notificationId} its POST will be made under, and only then is anything sent. An id used in
 * a call and written down afterwards is an e-mail this service cannot show it asked for, and a retry
 * under a fresh id is a second e-mail to the same Youth Offending Team (research §10).
 *
 * <p>Each recipient is settled on its own: ACCEPTED where notificationnotify answered 202, FAILED
 * with the status otherwise, and the next recipient is asked either way, because one team's refusal
 * says nothing about another team's e-mail. The batch is then settled from the tally alone - all
 * accepted is NOTIFIED, some not is PARTIALLY_NOTIFIED, and no recipients at all is
 * NOTIFIED_NOBODY.
 *
 * <p><strong>One answer does end the cycle, and it is not a recipient's.</strong> A settlement made
 * for a row this run read back or minted, and that the store then does not hold, means the attempt
 * is recorded nowhere: no tally taken afterwards is a complete account of what this batch's
 * recipients were sent. So the cycle stops, the claim is given back, the batch is not settled and
 * the call answers {@link NotificationDisposition#INCOMPLETE}. Settling anyway was the worse
 * answer, and silently so where the vanished row was the only one - nought rows tallies to
 * NOTIFIED_NOBODY, which is terminal, and a batch addressed to a Youth Offending Team all along
 * would end the night claiming there had been nobody to tell.
 *
 * <p><strong>A refusal that may answer differently is asked again first.</strong> A 503, a 429 or a
 * read timeout is a moment in notificationnotify's night and not a verdict about the e-mail, so the
 * POST is repeated up to {@code yotresultsdistribution.endpoints.max-attempts} with the shared
 * {@link RetryPolicy}'s back-off between attempts, under the same notification id, and the row is
 * settled FAILED with the last status only once that budget is spent. A NON_TRANSIENT refusal is
 * not repeated at all: the same command under the same identity will be declined again, and waiting
 * to prove it costs the teams after this one. The loop is here rather than in the client for the
 * reason the generation leg's is - the client classifies one attempt and knows nothing about the
 * budget - and it is the same policy object both legs are given, so the taxonomy is stated once
 * (defect fix C3).
 *
 * <p><strong>Defect fix P1: no recipients is a terminal state, not a wait.</strong> The progression
 * leg leaves a batch nobody subscribes to sitting generated for ever, waiting on an event nobody
 * publishes. NOTIFIED_NOBODY says plainly that the document was rendered and there was nobody to
 * send it to, and it is counted rather than inferred from an absence.
 *
 * <p><strong>Defect fix P9's run-time half is here too.</strong> The template is resolved once at
 * wiring and a per-e-mail failure is a FAILED row carrying the status that came back, so a batch
 * with any recipient it could not tell ends PARTIALLY_NOTIFIED rather than reporting the state it
 * would have reported had everybody been e-mailed.
 *
 * <p>{@link #resendFailed} is the second half of that honesty: a PARTIALLY_NOTIFIED batch keeps its
 * unsettled rows under the identities they were first attempted with, so a resend re-requests those
 * rows and no others - the teams that were told are not told twice - and the batch reaches NOTIFIED
 * when the last of them is accepted.
 *
 * <p><strong>Unsettled means FAILED or PENDING or never written, and both halves of this class are
 * re-entrant.</strong> A run that stopped between the 202 and the settlement - the pod died, the
 * store blipped on the update, the listener's session rolled the delivery back after the mark had
 * already committed - leaves a row that cannot say whether the e-mail was asked for; a run that
 * stopped a moment earlier, between the mark and the first insert or between two inserts, leaves no
 * row for a team at all. An ambiguous downstream outcome is retried (constitution's Idempotency
 * bullet), and it is safe to retry because the retry goes out under the id the row already holds.
 *
 * <p>So both entry points ask the same question of the same batch, in the same order: <strong>who is
 * owed comes from the records, and the rows are what this service has written down about it.</strong>
 * The recipients are the union across the records, a row is minted for any of them the batch holds
 * none for, and every row that is not ACCEPTED is then posted for - the PENDING ones beside the
 * FAILED ones. Minting only where there is no row is what makes a second run possible at all:
 * {@code UNIQUE (batch_id, email_address)} refuses a second row per address, so a run that minted
 * for every recipient could be made exactly once - and the run it was made in is the one that may
 * have stopped half way. Reading the rows alone, on the other hand, answered a batch whose rows were
 * never written with "nothing to re-request", and a tally over an empty table is NOTIFIED_NOBODY:
 * the state below, on a batch that had recipients all along, and terminal. Deriving the debt from
 * the records leaves that state reachable only where the union itself is empty.
 *
 * <p><strong>The tally is read off the table rather than counted in flight</strong>, and the same
 * read serves both methods: a resend's verdict is about the batch as it now stands, in which the
 * team that was told last night still counts as told, and a fresh notify's rows are exactly the
 * rows it has just settled. One rule instead of two that could disagree about the same batch.
 *
 * <p><strong>One notifier per batch at a time, under a claim.</strong> Both entry points are
 * reachable at the same moment - the outcome sink on a delivered {@code document-available} and an
 * operator's resend - and they derive the same owed set from the same records, so without a claim
 * both POST for every recipient and both then settle the rows and the batch. Every call therefore
 * claims the batch first ({@code register_batch.notifying_since} / {@code notifier_token}, taken
 * under an advisory lock in a short transaction of its own) and gives the claim back at the end;
 * the call that does not get it posts nothing and answers
 * {@link NotificationDisposition#ALREADY_NOTIFYING}, which is not a failure but a batch somebody
 * else is telling. The claim carries a lease, because a pod that died mid-notification would
 * otherwise leave a batch nothing could ever pick up. <strong>The claim answers three things and
 * not two</strong> ({@link NotificationClaim}): a batch nothing was ever assembled under is the
 * caller's own correlation being wrong and surfaces as this service's existing not-found failure,
 * not as contention, because a lost correlation counted as contention makes the reading a stuck
 * claim is chased by mean nothing.
 *
 * <p><strong>The lease is the notifying leg's own, and is renewed before every POST and before
 * every settlement.</strong>
 * {@code yotresultsdistribution.notification.claim-lease} rather than {@code stale-after}, which answers a
 * different question: how long a batch may be awaiting its render before the next run gives up on
 * it is no bound at all on telling a generated batch's recipients, whose cost is the number of Youth Offending
 * Teams the batch is addressed to times whatever notificationnotify makes of each of them. So what
 * the lease is asked to cover is the retry cycle one recipient's turn can become - every attempt's
 * connect and read timeout with the bounded waits between them, which is the cycle startup holds it
 * to twice over - and {@code renewNotificationClaim(batchId, token)} - one token-fenced statement
 * that re-checks ownership and extends the lease together - is asked before every one of those
 * POSTs, the retries of one recipient included, before each row's settlement and before the batch's
 * own. Before every POST and not once per recipient: one renewal spent across a recipient's whole
 * cycle fenced the first attempt and none of the others, and the others are the ones made after a
 * read timeout and a back-off wait. A notifier whose renewal is refused has been taken
 * over: it stops, settles nothing further and answers
 * {@link NotificationDisposition#CLAIM_LOST}, counted apart from the notifier that never got the
 * claim, because the rows it left unsettled are re-requested by a later run under the identities
 * they already hold. Renewed in front of every POST is also why that answer says nothing about how
 * many POSTs were made: the refusal can be the one asked before the first of them, and can equally
 * be the one asked before the batch's own settlement, with every recipient already posted for.
 *
 * <p><strong>The one thing it does still write is the attempt.</strong> A POST made before the
 * renewal was refused was really made, so the POSTs of the recipient it was in the middle of are
 * added to that row's lifetime {@code attempts} by a tally-only write that touches no settlement
 * column ({@code tallyAttempts}). Anything else from here would be written over the work of the
 * notifier that now holds the batch; omitting this left off the total exactly the attempts made in
 * the window two notifiers were in the cycle at once, which is the window the count is reached for.
 *
 * <p>A claim rather than one transaction around the cycle, and for the same reason the intake half
 * holds a {@code RunClaim}: the cycle POSTs to notificationnotify once per recipient and waits
 * between its own retries, and a database transaction open across that would hold a connection and
 * a row lock for as long as another service takes to answer. <strong>Every settlement write this leg
 * makes - each row's and the batch's own - is fenced by a token ownership re-check</strong>, the
 * renewal statement being that re-check. The row-level writes are fenced independently of the claim
 * as well - a settlement is held off where the row is already ACCEPTED, and the attempt total is
 * computed in SQL - because defence at the row is what survives a claim whose lease ran out under
 * the run that held it. <strong>The one write that is deliberately unfenced is the tally after a
 * lost claim</strong>, which touches {@code attempts} and no settlement column, on the row's
 * identity and neither the claim nor the row's status: a POST made in the very window the fence
 * exists for is still a POST, and leaving it off the total made a row two notifiers posted for read
 * as one notifier's work. The tally inside the settlement statement sits outside that statement's
 * own ACCEPTED fence for the same reason.
 *
 * <p><strong>Notification may be asked more than once per batch, and is sent once.</strong>
 * {@code markGenerated} is a compare-and-set, so of two mechanisms racing to move one batch to
 * GENERATED only one wins the mark; but a redelivered {@code document-available} that finds the
 * batch still GENERATED hands it on again (defect fix P1, amended), because a notification that
 * finished would have moved it on. One e-mail per team is this class's guarantee: the claim admits
 * one caller per batch, a recipient keeps the one row it was minted, and an ACCEPTED row is never
 * asked for again. A batch whose notification could not be made at all - the store went away
 * mid-run - is therefore retried by the broker's redelivery, and an operator's
 * {@code POST /operations/batches/{batchId}/notify} recovers it otherwise. That batch is visible
 * while it stands there -
 * {@code yotresultsdistribution_oldest_generated_age} is the reading that says a batch has held its document
 * since before anybody was worried - and either entry point recovers it, because both are
 * re-entrant.
 *
 * <p>Every line this service writes carries ids, counts and bounded codes. A recipient's address and
 * name never reach a log at INFO or above and never a metric label (constitution Principle VII);
 * they live in the notification row, which is the only place that may hold them.
 */
public class RegisterNotifierService {

    /**
     * The logical template name written to {@code register_notification.template_name}.
     *
     * <p>The name the environment configures the id under, in
     * {@code yotresultsdistribution.email.templates.cr_standard}, and the value data-model.md gives the
     * column: the row says which template an e-mail was sent under and not only which UUID.
     *
     * <p>Written from here rather than copied off the recipient. One template is configured, so one
     * template is what every register goes out under, and a row naming the recipient's own
     * {@code emailTemplateName} beside the {@code cr_standard} id would be a row that described a
     * message nobody sent. The recipient mapper already defaults that field to this same name
     * wherever a subscription names none (C29).
     */
    public static final String TEMPLATE_NAME = "cr_standard";

    /** A minted row has been posted for nothing yet, which is what the column's default says. */
    private static final int NO_ATTEMPTS_YET = 0;

    private static final Logger LOG = LoggerFactory.getLogger(RegisterNotifierService.class);

    /** Where the batch's registers are read from and where the batch is settled. */
    private final RegisterStore store;

    /** The {@code register_batch} read that gives the generated document's file-service id. */
    private final RegisterBatchRepository batches;

    /** The {@code register_notification} rows: minted before a POST, settled after one. */
    private final RegisterNotificationRepository notifications;

    /** notificationnotify, behind the port that names neither HTTP nor a template body. */
    private final RegisterNotifier notifier;

    /** Where each recipient and each terminal batch state is counted. */
    private final GenerationMetrics metrics;

    /**
     * The {@code cr_standard} template id, validated for shape at startup (defect fix P9).
     *
     * <p>Resolved once at wiring rather than per recipient, which is the whole of what P9 is about:
     * the legacy resolved it per e-mail and, finding it blank, logged a line and moved on.
     */
    private final UUID templateId;

    /**
     * The shared retry policy: the attempt budget and the back-off between two of them.
     *
     * <p>The same object the generation leg spends, from the same five settings (defect fix C3):
     * one taxonomy, one back-off, and the loop belonging to whoever holds the budget.
     */
    private final RetryPolicy retryPolicy;

    /** How a wait between two attempts at one recipient is taken. */
    private final RetryPause pause;

    /** This pod's reading of now, which is what {@code sent_at} records. */
    private final Clock clock;

    /**
     * Creates the service over the one store, the two tables it reads and the one notifier.
     *
     * @param registerStore         where the batch's registers are read from and where the batch is
     *                              settled on its tally
     * @param registerBatches       the {@code register_batch} read that gives the generated
     *                              document's file-service id
     * @param registerNotifications the {@code register_notification} rows, minted before a POST and
     *                              settled after one
     * @param registerNotifier      notificationnotify, behind the port
     * @param generationMetrics     where each recipient and each terminal batch state is counted
     * @param crStandardTemplateId  the {@code cr_standard} template id, resolved once at wiring
     * @param policy                the shared retry policy: attempts, back-off, and what one
     *                              attempt is worth asking again for
     * @param retryPause            how a wait between two attempts at one recipient is taken
     * @param runClock              this pod's reading of now, which is what {@code sent_at} records
     */
    public RegisterNotifierService(final RegisterStore registerStore,
            final RegisterBatchRepository registerBatches,
            final RegisterNotificationRepository registerNotifications,
            final RegisterNotifier registerNotifier,
            final GenerationMetrics generationMetrics,
            final UUID crStandardTemplateId,
            final RetryPolicy policy,
            final RetryPause retryPause,
            final Clock runClock) {
        this.store = registerStore;
        this.batches = registerBatches;
        this.notifications = registerNotifications;
        this.notifier = registerNotifier;
        this.metrics = generationMetrics;
        this.templateId = crStandardTemplateId;
        this.retryPolicy = policy;
        this.pause = retryPause;
        this.clock = runClock;
    }

    /**
     * Tells every recipient of one generated batch, and settles the batch on the tally.
     *
     * <p>The rows come first, all of them, and the document id is read before the first of them is
     * minted: a batch that turned out to carry no document would otherwise leave a table full of
     * PENDING rows for e-mails nothing was ever going to ask for.
     *
     * <p>Re-entrant, and identical to {@link #resendFailed} in what it sends: a recipient the batch
     * already holds a row for keeps it, an ACCEPTED row is left alone, and everything else is asked
     * for again under the identity it holds. The two names are the two callers - the outcome sink on
     * a document, an operator on a batch - and not two rules about one batch.
     *
     * @param batchId the batch whose document has been generated
     * @return how many recipients were accepted, how many failed, and the terminal state the batch
     *     is settled in
     */
    public NotificationSummary notify(final UUID batchId) {
        return underTheClaim(batchId);
    }

    /**
     * Re-requests the recipients whose e-mail was never accepted, under the identities they hold.
     *
     * <p>FAILED and PENDING alike, because the two are the same debt to the same team: a row minted
     * by a run that stopped before it could settle cannot say whether the e-mail was asked for, and
     * an ambiguous outcome is retried rather than left standing. The retry is safe for the reason
     * every retry here is safe - it goes out under the notification id the row already holds, so it
     * reaches notificationnotify's own aggregate instead of asking for a second e-mail.
     *
     * <p><strong>And a recipient with no row at all is owed one too.</strong> The debt is the
     * recipient union across the batch's records, not the rows: a run that stopped before it could
     * write them leaves a batch whose teams are owed an e-mail nothing has recorded, and a resend
     * that read only the rows found nothing to send and settled the batch NOTIFIED_NOBODY - which
     * is terminal. So the missing rows are minted here, exactly as a first notification mints them.
     *
     * @param batchId the batch to resend for
     * @return the tally over the whole batch as it now stands, and the terminal state that produces
     */
    public NotificationSummary resendFailed(final UUID batchId) {
        return underTheClaim(batchId);
    }

    /**
     * The whole cycle for one batch, run by whichever notifier holds that batch's claim.
     *
     * <p><strong>Both entry points are reachable at the same moment, and only one may post.</strong>
     * The outcome sink on a delivered {@code document-available} and an operator's resend derive the
     * same owed set from the same records, so without a claim both POST for every recipient and both
     * then settle the rows and the batch: a Youth Offending Team gets a register about children
     * twice, an absolute settlement can write FAILED over the ACCEPTED row the other run had just
     * written, the two runs' attempt counts are lost against each other, and the second tally is
     * taken while the first run is still writing.
     *
     * <p><strong>A claim rather than one transaction around the cycle.</strong> The cycle POSTs to
     * notificationnotify once per recipient, and a database transaction open across those calls
     * would hold a connection and a row lock for as long as another service takes to answer - and
     * for as long as this leg's own retries wait. So the claim is taken in a short transaction of
     * its own (an advisory lock on the batch id and a compare-and-set on the claim columns), the
     * POSTs are made outside any transaction, and the claim is given back at the end. It is the
     * same shape the intake half's {@code RunClaim} has, for the same reason.
     *
     * <p><strong>The loser answers rather than throwing.</strong> A batch being told by somebody
     * else is not a failure and leaves this call nothing to do, so it reports the disposition and
     * the rows as they stood - which is the winner's work part-done, and why a caller branches on
     * {@link NotificationDisposition} and not on the counts.
     *
     * <p><strong>And a claim nothing could be taken on is not a loser at all.</strong> The claim
     * answers {@link NotificationClaim#ABSENT} where this store holds no batch under the identity
     * the caller named, which is that caller's own correlation being wrong: no notifier is telling
     * this batch's recipients, because there are no recipients and no batch. It is the not-found
     * failure this method already raised a step later, raised here instead, and it is deliberately
     * not counted as contention - a lost correlation in the reading a stuck claim is chased by makes
     * that reading mean nothing.
     *
     * <p><strong>The claim is released in a finally, and released by token.</strong> A claim held
     * past the run that took it is a batch no resend could pick up, which is defect fix P1's state
     * wearing a different hat; the lease is the second answer to that, for
     * the pod that dies before any {@code finally} runs. Releasing by token means a notifier whose
     * claim was reclaimed while it was working releases nothing, because what it would be giving
     * back is the claim the notifier that took over is relying on.
     *
     * @param batchId the batch to tell the recipients of
     * @return the tally and what this call did about it
     */
    // PMD.OnlyOneReturn: the two answers are two different things - what this run did to the batch,
    // and what another run is doing to it - and only one of them may be produced inside the
    // try/finally that holds the claim. A single exit would mean carrying a summary out of a branch
    // that never took a claim past the block whose whole job is to give one back.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private NotificationSummary underTheClaim(final UUID batchId) {
        final UUID token = UUID.randomUUID();
        final NotificationClaim claim = batches.claimForNotification(batchId, token);

        if (claim == NotificationClaim.ABSENT) {
            throw noSuchBatch(batchId);
        }
        if (claim == NotificationClaim.ALREADY_CLAIMED) {
            metrics.alreadyNotifying();
            LOG.info("Batch {} is already being notified by another mechanism, so this run posts "
                    + "nothing for it: two runs telling one batch's recipients is a second e-mail "
                    + "about the same children to every team on it.", batchId);
            final RegisterBatch standing = batchOf(batchId);
            final NotificationSummary seen = tally(notifications.findByBatchId(batchId));
            return NotificationSummary.alreadyNotifying(
                    seen.accepted(), seen.failed(), standing.status());
        }
        try {
            return tellWhoeverIsOwed(batchOf(batchId), token);
        } finally {
            release(batchId, token);
        }
    }

    /**
     * The answer of a notifier that held the claim, started the cycle and lost it inside one.
     *
     * <p>What it does about it is stop, which is the whole of the disposition: the notifier that now
     * holds the batch is deriving the same owed set from the same records, so a POST from here is a
     * second register about the same children to a team the other run is telling, and a settlement
     * from here is over that run's work - onto a row it is about to POST for, or a batch tally taken
     * while it is still writing. The rows this run did not settle stay unsettled under the
     * identities they already hold, which is exactly the state a later run re-requests: the retry
     * reaches notificationnotify's own aggregate rather than asking for a second e-mail.
     *
     * <p>The POSTs it had already made are on their row's attempt total all the same, written by
     * {@link #tallyWhatTheLostClaimSpent} on the way out: a POST that happened is a POST that
     * happened, whoever settles the row it was made for. Where that write finds no row to add to,
     * the fault is reported and this is still the answer: the batch is no longer this run's, so
     * what became of it is not this run's to say either.
     *
     * <p>Counted apart from the loser of the claim, because they are different events: that one
     * never got the claim, and this one held it and began the cycle. <strong>How far it had got is
     * not fixed</strong> - the renewal is asked in front of every POST, so a refusal before the
     * first of them leaves this run having posted for nobody, and one before the batch's own
     * settlement leaves it having posted for every recipient. This is the reading
     * {@code yotresultsdistribution.notification.claim-lease} is raised on, and the line carries the batch
     * and nothing else.
     *
     * @param batchId the batch this run has stopped telling
     * @return the rows and the state as they stood when the renewal was refused, carrying
     *     {@link NotificationDisposition#CLAIM_LOST}
     */
    private NotificationSummary claimLost(final UUID batchId) {
        metrics.claimLost();
        LOG.warn("Batch {}'s notification claim was taken over while this run was telling its "
                + "recipients, so it stops here and writes nothing further: the notifier that now "
                + "holds the batch is telling the teams this run had not reached, and anything "
                + "written from here would be written over its work. The rows this run did not "
                + "settle stay under the identities they hold and are re-requested by a later run.",
                batchId);
        final NotificationSummary seen = tally(notifications.findByBatchId(batchId));
        return NotificationSummary.claimLost(
                seen.accepted(), seen.failed(), batchOf(batchId).status());
    }

    /**
     * Gives the claim back, so a later run can pick the batch up where this one left it.
     *
     * <p>A release that changed nothing is reported and not raised: the claim was reclaimed while
     * this run was working, which means the lease expired under it, and the run it has to tell
     * about that is the one whose result it just wrote - not this one, which is finishing.
     *
     * @param batchId the batch to release
     * @param token   the token this run claimed under
     */
    private void release(final UUID batchId, final UUID token) {
        if (!batches.releaseNotificationClaim(batchId, token)) {
            LOG.warn("Batch {}'s notification claim was not this run's to release, so its lease "
                    + "ran out while this run was still telling the recipients and another "
                    + "mechanism has taken the batch over.", batchId);
        }
    }

    /**
     * Tells whichever of the batch's recipients is still owed an e-mail, and settles the batch.
     *
     * <p>The one path both entry points take, and the order in it is the whole of the discipline.
     * <strong>Who is owed is derived from the records first and from the rows second.</strong> The
     * recipients are the de-duplicated union across the batch's records ({@link RecipientSet},
     * defect fix P4) and that union is the debt; the rows are only what this service has written
     * down about it. A resend that read the rows alone answered a batch whose rows were never
     * written - the run stopped between {@code markGenerated} and the first insert, or between the
     * second insert and the third - with "nothing to re-request", and a tally over an empty table
     * is NOTIFIED_NOBODY: P1's terminal state, saying the document was rendered and there was
     * nobody to send it to, settled on a batch that had three Youth Offending Teams all along and
     * terminal, so no later resend could revisit it. The missing rows are minted before anything is
     * settled, which leaves NOTIFIED_NOBODY reachable only where the union itself is empty.
     *
     * <p>The document id is read before the first row is minted, for the reason it always was: a
     * batch that turned out to carry no document would otherwise leave a table full of PENDING rows
     * for e-mails nothing was ever going to ask for. A batch with neither a recipient nor a row is
     * the one case that reads no document at all, because there is nothing to attach and nobody to
     * attach it for.
     *
     * <p><strong>The batch is re-read before it is settled.</strong> The row this run started from
     * is minutes old by the time the last recipient has been posted for, and the mark that settles
     * a batch is a compare-and-set against the state the caller read: a stale state is either a
     * write the store refuses, or - where the state machine happens to draw the move - a second
     * settlement of a batch something else has already finished. The tally is likewise taken off
     * the table at that moment rather than counted in flight, so the two halves of the verdict are
     * read at the same instant and inside the same claim.
     *
     * <p><strong>And the claim is renewed before the settlement, as it is before every other
     * write.</strong> The batch's own mark is the last thing this run does and the furthest from the
     * moment the claim was taken, so it is the write most likely to be made by a notifier that no
     * longer holds the batch - and it is the one write that decides what the whole night's e-mails
     * came to. A renewal refused here means the notifier that took the batch over is the one whose
     * tally should settle it, so this run leaves the batch where it stands.
     *
     * @param batch the batch as this run read it
     * @param token the token this run holds the batch's notification claim under
     * @return how many recipients were accepted, how many failed, and the terminal state the batch
     *     is settled in - or the batch as it stood when this run's claim was taken over
     */
    private NotificationSummary tellWhoeverIsOwed(final RegisterBatch batch, final UUID token) {
        final UUID batchId = batch.batchId();
        final List<CourtRegisterRecipient> recipients =
                RecipientSet.unionOf(store.batched(batchId));
        final List<RegisterNotification> held = notifications.findByBatchId(batchId);
        Turn turn = Turn.SETTLED;

        if (recipients.isEmpty() && held.isEmpty()) {
            LOG.info("Batch {} has a document and no recipients at all, so there is nobody to tell "
                    + "and nothing to record an attempt against; it ends here rather than waiting "
                    + "on an e-mail nobody is owed.", batchId);
        } else {
            final UUID documentFileId = documentOf(batch);
            final List<RegisterNotification> owed = owedRows(batchId, recipients, held);
            LOG.info("Batch {} is addressed to {} recipients and owes {} of them an e-mail, each "
                    + "under the identity its own row holds and each minted before anything is "
                    + "asked of notificationnotify.", batchId, recipients.size(), owed.size());
            turn = tell(owed, documentFileId, token);
        }
        final NotificationSummary answer;
        if (turn == Turn.ROW_ABSENT) {
            answer = incomplete(batchId);
        } else if (turn == Turn.SETTLED && batches.renewNotificationClaim(batchId, token)) {
            answer = settle(batchOf(batchId));
        } else {
            answer = claimLost(batchId);
        }
        return answer;
    }

    /**
     * The answer of a notifier that held the claim throughout and could not finish the cycle.
     *
     * <p>A settlement the store had no row for means one of this batch's recipients is unaccounted
     * for: the POST was made under an identity this run read back or minted, and the row that would
     * have recorded how it ended is not there. So the tally the batch would be settled from is a
     * tally over rows that no longer describe what was sent, and the batch is left where it stands
     * rather than settled on it.
     *
     * <p><strong>Which is a smaller loss than settling anyway, and a much smaller one where the
     * vanished row was the only one.</strong> Nought rows tallies to NOTIFIED_NOBODY - defect fix
     * P1's terminal state, saying the document was rendered and there was nobody to send it to -
     * and writing that over a batch addressed to a Youth Offending Team all along ends the night
     * claiming there was nobody to tell, terminally, where no later resend could revisit it.
     *
     * <p><strong>GENERATED is recoverable.</strong> Either entry point re-derives the owed set from
     * the records and mints the row the store has no record of, so an operator's explicit
     * {@code POST /operations/batches/{batchId}/notify} recovers the batch, and so does a
     * redelivered {@code document-available}, which hands a batch still GENERATED to the notifier
     * again (defect fix P1, amended). The run's stale-batch pass does not: it never touches a GENERATED batch at
     * any age, because that batch holds a document somebody is owed e-mails about and failing it
     * would throw the document away. What names such a batch is
     * {@code yotresultsdistribution_oldest_generated_age}, the reading that says a batch has been standing
     * there since before anybody was worried, and a reading settles nothing.
     *
     * <p>This is the answer of a notifier that still held the claim, and only that one. The same
     * absent row met by the tally a lost claim writes instead of a settlement is the same fault and
     * is reported the same way, but that call answers
     * {@link NotificationDisposition#CLAIM_LOST}: the batch is no longer its own.
     *
     * <p>The fault itself was counted and logged at ERROR where the write met it
     * ({@link #reportTheRowHasGone}); this line says what became of the batch, and carries the
     * batch and nothing else.
     *
     * @param batchId the batch whose cycle could not be finished
     * @return the rows and the state as they stand, carrying
     *     {@link NotificationDisposition#INCOMPLETE}
     */
    private NotificationSummary incomplete(final UUID batchId) {
        LOG.warn("Batch {}'s notification cycle could not account for one of its recipients - the "
                + "store holds no row under an identity this run posted under - so the cycle stops "
                + "and the batch is not settled: a tally over the rows that are left would settle "
                + "it on an incomplete account of what was sent, and a batch of one vanished row "
                + "would be settled as having had nobody to tell. It stays where it stands until an "
                + "operator resends it or the announcement is offered again; the batch-age "
                + "reading names it and settles nothing.",
                batchId);
        final NotificationSummary seen = tally(notifications.findByBatchId(batchId));
        return NotificationSummary.incomplete(
                seen.accepted(), seen.failed(), batchOf(batchId).status());
    }

    /**
     * The rows this run has to post for: one per recipient, minted where the batch holds none yet.
     *
     * <p>The identity is minted here and persisted here, and it is the whole reason this happens
     * before any POST: it is the path parameter of {@code POST /notifications/{notificationId}} and
     * the key of notificationnotify's own aggregate, so a row written after the call would be
     * evidence of an e-mail this service could no longer name.
     *
     * <p><strong>And it is minted only where there is no row already</strong>, which is what makes
     * a second {@code notify} over the same batch possible at all. {@code UNIQUE (batch_id,
     * email_address)} refuses a second row for an address, so a notify that minted for every
     * recipient could be run exactly once - and the run it was run in is the one that may have
     * stopped half way, leaving rows PENDING that nothing would ever ask about again. A recipient
     * that already has a row keeps it: what was sent, and to whom, was decided when that row was
     * written.
     *
     * <p>A row already ACCEPTED is not returned. The team it belongs to has been told, and telling
     * it again would be a second register about the same children.
     *
     * <p><strong>Every other row is returned, whether or not the union still names it.</strong> A
     * row exists only because a run minted it for a recipient of this batch, so it is a debt to a
     * team that was matched for children on this document; reference data that has since stopped
     * naming that team does not settle it, and re-requesting it is safe for the reason every retry
     * here is safe - it goes out under the identity the row already holds.
     *
     * @param batchId    the batch these recipients are being told about
     * @param recipients the batch's recipients, de-duplicated by address
     * @param held       every row the batch already holds, read once for this and for the minting
     * @return the rows still owed an e-mail: the held ones in address order, then any newly minted
     *     in the order the addresses were first seen
     */
    private List<RegisterNotification> owedRows(final UUID batchId,
            final List<CourtRegisterRecipient> recipients,
            final List<RegisterNotification> held) {

        final Map<String, RegisterNotification> rows = new LinkedHashMap<>();
        for (final RegisterNotification row : held) {
            rows.put(row.emailAddress(), row);
        }
        for (final CourtRegisterRecipient recipient : recipients) {
            rows.computeIfAbsent(recipient.emailAddress1(),
                    address -> mint(batchId, address, recipient.recipientName()));
        }
        final List<RegisterNotification> owed = new ArrayList<>(rows.size());

        for (final RegisterNotification row : rows.values()) {
            if (row.status() != NotificationStatus.ACCEPTED) {
                owed.add(row);
            }
        }
        return owed;
    }

    /**
     * Mints one recipient's PENDING row and writes it down, before anything is asked for it.
     *
     * <p><strong>Or reads back the row that beat it there.</strong> Two mechanisms can reach one
     * batch at the same moment - the outcome sink on a delivered {@code document-available} and an
     * operator's resend - and both derive the same owed set from the same records, so whichever gets
     * to the insert second loses {@code UNIQUE (batch_id, email_address)}. That is two runs doing
     * the same work rather than a fault: the row the other one wrote is the row notificationnotify's
     * aggregate is keyed by, so this run posts under <em>that</em> identity. Letting the refusal out
     * instead would leave the team untold with the rest of the batch's recipients behind it, and
     * minting a second identity for the address is the one thing the key exists to prevent - it
     * would be a second register about the same children.
     *
     * @param batchId       the batch this recipient is being told about
     * @param emailAddress  the address the e-mail goes to
     * @param recipientName the name the template greets, where the subscription named one
     * @return the row the POST will be made under: this run's, or the one that won the key
     */
    private RegisterNotification mint(final UUID batchId, final String emailAddress,
            final String recipientName) {

        final RegisterNotification row = new RegisterNotification(UUID.randomUUID(), batchId,
                emailAddress, recipientName, TEMPLATE_NAME, templateId, NotificationStatus.PENDING,
                null, null, NO_ATTEMPTS_YET);
        RegisterNotification minted;
        try {
            notifications.insert(row);
            minted = row;
        } catch (StoreRefusedRowException lost) {
            minted = rowThatWon(batchId, emailAddress, lost);
        }
        return minted;
    }

    /**
     * The row another mechanism minted for this address a moment before this run tried to.
     *
     * <p>Read back rather than assumed: what was sent, and under which identity, was decided when
     * that row was written, and this run's job is to finish it. The line carries the batch and a
     * count and nothing about a person, and the refusal itself carries no address either - the
     * persistence layer bounded it where it was raised (constitution Principle VII).
     *
     * @param batchId      the batch the row belongs to
     * @param emailAddress the address the key was lost on
     * @param lost         the store's bounded refusal, kept as the cause of a failure to find it
     * @return the row the winner wrote
     * @throws IllegalStateException where the batch holds no row for the address after all, which is
     *     a store that refused a row on a rule this service does not know about rather than a race
     */
    private RegisterNotification rowThatWon(final UUID batchId, final String emailAddress,
            final StoreRefusedRowException lost) {

        LOG.info("Batch {} already held a notification row for one of its recipients, so this run "
                + "lost the (batch, address) key to another mechanism; the row that won is read "
                + "back and the e-mail asked for under its identity rather than a second one.",
                batchId);
        return notifications.findByBatchId(batchId).stream()
                .filter(row -> emailAddress.equals(row.emailAddress()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("register batch " + batchId
                        + " refused a notification row for a recipient it then holds none for",
                        lost));
    }

    /**
     * Asks notificationnotify for each row's e-mail and settles that row on the answer.
     *
     * <p>One row at a time, and the next row is asked whichever way this one went: an exception that
     * ended the batch would turn one bad address into a night's silence for a whole court centre.
     *
     * <p>The counter moves once per row and not once per POST. What
     * {@code yotresultsdistribution_notifications_total} answers is how many e-mails a night asked for and
     * got, and a transient refusal that cleared on the next attempt is not an e-mail that failed -
     * how many attempts it took is on the row's {@code attempts}.
     *
     * <p><strong>A settlement the store held off is not taken for one that landed.</strong>
     * ACCEPTED is terminal at the row level, so a row another mechanism accepted between this run's
     * read and its own write keeps that acceptance: the settlement is held off where the row is
     * rather than by this loop remembering to check, and what it means is that the team has been
     * told. The POST is on the row's attempt total either way, because it was really made.
     *
     * <p>What is <em>not</em> the same in the two cases is what it says about the night, so the two
     * are counted apart. A late refusal is an attempt that would have demoted a team's row; a late
     * acceptance is two notifiers that each got a 202 for one recipient, which is a Youth Offending
     * Team holding two copies of a register about children. Filing the second under the first's
     * reason would hide the worse reading inside the milder one, and the number that says a claim is
     * not holding would stop meaning what it says. Nothing else is done about either, because the
     * tally taken at settlement reads the row as it now stands.
     *
     * <p><strong>The claim is renewed before every POST, and the loop ends where a renewal is
     * refused.</strong> The lease cannot know how long this loop takes - a batch is addressed to as
     * many Youth Offending Teams as subscribed to its court centre, and each of them costs up to
     * {@code max-attempts} POSTs with a connect timeout, a read timeout and a wait apiece - so what
     * it is asked to cover is one recipient's retry cycle, renewed in front of each of that cycle's
     * attempts. A refusal means the batch has been taken over, and the notifier that took it is
     * deriving the same owed set from the same records: every POST from here is a second register
     * about the same children to a team that one is telling.
     *
     * <p><strong>And it ends where a row this run posted under turns out not to be there.</strong>
     * That is not a recipient's answer at all, it is this service's own store having lost a row it
     * wrote: the attempt is recorded nowhere, so nothing the tally says about the batch afterwards
     * is a complete account of what was sent. The recipients after it are not asked - a batch that
     * cannot be settled is not made more settleable by more POSTs - and the batch is left standing
     * for a resend that can account for every row it posts under.
     *
     * @param rows           the rows to post for, each already persisted under its own identity
     * @param documentFileId the rendered document's file-service id, attached by reference
     * @param token          the token this run holds the batch's notification claim under
     * @return how the last recipient's turn ended, which is whether the batch may now be settled on
     *     the rows this loop wrote
     */
    private Turn tell(final List<RegisterNotification> rows, final UUID documentFileId,
            final UUID token) {

        final Iterator<RegisterNotification> owed = rows.iterator();
        Turn turn = Turn.SETTLED;

        while (turn == Turn.SETTLED && owed.hasNext()) {
            turn = tellOne(owed.next(), documentFileId, token);
        }
        return turn;
    }

    /**
     * One recipient's turn: the claim renewed, the POST made, and the row settled on the answer.
     *
     * <p>Renewed in front of every call made under the claim, and the reason is the POST itself: it
     * is where a run spends its time, so a notifier waiting on notificationnotify for one recipient
     * is a notifier not renewing - which is exactly when a lease runs out under it. So this asks
     * before the recipient's first POST, {@link #attempt} asks before each of its retries, and
     * ownership is asked again before the write: a settlement made after the batch has been taken
     * over is written over the work of the notifier that now holds it, and the row is better left
     * unsettled under the identity it already holds, because that is the state a later run
     * re-requests and the re-request reaches notificationnotify's own aggregate rather than asking
     * for a second e-mail.
     *
     * <p><strong>The POSTs are tallied either way.</strong> A renewal refused before the settlement
     * leaves this run unable to say how the attempt ended and no less certain that it was made, so
     * what it writes instead is the tally alone ({@link #tallyWhatTheLostClaimSpent}). The
     * alternative was a row that says one notifier posted for it where two did, in the one
     * situation where knowing otherwise matters.
     *
     * <p>The e-mail counter moves once the POST has been made and an outcome reached, whether or
     * not the settlement that follows is this run's to write. What
     * {@code yotresultsdistribution_notifications_total} answers is how many e-mails a night asked for and
     * got, which is a question about recipients: a cycle abandoned between two attempts reached no
     * verdict about this one, so there is nothing to count under - what it has to say is on the
     * row's attempt total.
     *
     * @param row            the row to post for, already persisted under its own identity
     * @param documentFileId the rendered document's file-service id, attached by reference
     * @param token          the token this run holds the batch's notification claim under
     * @return how this recipient's turn ended: settled, the claim lost, or the row gone
     */
    private Turn tellOne(final RegisterNotification row, final UUID documentFileId,
            final UUID token) {

        Turn turn = Turn.CLAIM_LOST;

        if (batches.renewNotificationClaim(row.batchId(), token)) {
            final Attempted attempted = attempt(row, documentFileId, token);
            final NotificationOutcome outcome = attempted.outcome();

            if (attempted.stillOurs()) {
                metrics.notificationSettled(outcome.status(), outcome.responseCode());
            }
            if (attempted.stillOurs() && batches.renewNotificationClaim(row.batchId(), token)) {
                final NotificationSettlement did =
                        notifications.update(settledAs(row, outcome), attempted.posts());
                recordWhatTheStoreDid(did, row, outcome);
                turn = did == NotificationSettlement.ABSENT ? Turn.ROW_ABSENT : Turn.SETTLED;
            } else {
                tallyWhatTheLostClaimSpent(row, attempted.posts());
            }
        }
        return turn;
    }

    /**
     * The one thing a notifier that has lost the claim still owes the row it posted for.
     *
     * <p>Stopping is right about the settlement and wrong about the tally. How the attempt ended is
     * the verdict of the notifier that now holds the batch - it is deriving the same owed set from
     * the same records, and a status written from here would be written over its work - but the POST
     * was really made: notificationnotify has it, and the Youth Offending Team may have the e-mail.
     * Leaving it off the row's lifetime total loses exactly the attempts made in the window two
     * notifiers were in the cycle at once, which is the window the count is reached for, so a row
     * two notifiers posted for reads as one notifier's work.
     *
     * <p>So the tally-only write is made, and nothing else is. A tally that finds no row to add to
     * is reported where the settlement's own {@link NotificationSettlement#ABSENT} is: it is the
     * same fault - a row this service posted under that the store no longer holds - and this path
     * may not be the quieter of the two.
     *
     * @param row   the row whose POSTs were made before the claim went
     * @param posts how many POSTs this run made for it, which is at least one wherever this is
     *              asked: the claim is renewed before every POST, so a run that lost it before the
     *              first has nothing to tally and never reaches here
     */
    private void tallyWhatTheLostClaimSpent(final RegisterNotification row, final int posts) {
        if (!notifications.tallyAttempts(row.notificationId(), posts)) {
            reportTheRowHasGone(row);
        }
    }

    /**
     * Reports the two answers that are not an applied settlement, each under its own reason.
     *
     * <p>Both are things that changed no settlement, and something that changes nothing has to be
     * visible or the only trace is a row that looks untouched. The line carries the batch, the
     * notification id and a bounded code, and none of the three is about a person.
     *
     * <p>The two are reported and no more: the tally taken at settlement reads the row as it now
     * stands, so a late outcome needs nothing done about it. The third answer is the one the caller
     * acts on - a row the store does not hold ends the cycle - which is why what the statement did
     * is read there as well as reported here.
     *
     * @param did     what the settlement statement did
     * @param row     the row it was asked about
     * @param outcome how this run's own attempt ended, which is what tells a late acceptance from a
     *                late refusal
     */
    private void recordWhatTheStoreDid(final NotificationSettlement did,
            final RegisterNotification row, final NotificationOutcome outcome) {

        if (did == NotificationSettlement.ATTEMPTS_ONLY) {
            if (outcome.status() == NotificationStatus.ACCEPTED) {
                metrics.lateAcceptanceIgnored();
                LOG.info("A recipient of batch {} was accepted by another notifier as well as by "
                        + "this run, so its row keeps the settlement this service can evidence and "
                        + "the team has been sent the register twice: the claim did not hold. "
                        + "notificationId={}", row.batchId(), row.notificationId());
            } else {
                metrics.lateFailureIgnored();
                LOG.info("A recipient of batch {} was already accepted by the time this run "
                        + "settled it, so the settlement was held off and the row keeps the "
                        + "acceptance: the team has been told. notificationId={}",
                        row.batchId(), row.notificationId());
            }
        } else if (did == NotificationSettlement.ABSENT) {
            reportTheRowHasGone(row);
        }
    }

    /**
     * The row this run posted under, which the store turns out not to hold.
     *
     * <p>Named once because two writes meet it: the settlement, and the tally a run that lost the
     * claim makes instead of one. It is a fault of this service's own store rather than a racing
     * notifier - every row either write is made for was read back or minted by this run - so it is
     * loud where it is met, and the line carries the batch, the notification id and nothing about a
     * person.
     *
     * @param row the row the write was made for
     */
    private void reportTheRowHasGone(final RegisterNotification row) {
        metrics.settlementRowAbsent();
        LOG.error("Batch {} holds no notification row under the identity this run posted "
                + "under, so the attempt is recorded nowhere: the row was read back or minted "
                + "by this run, and the store no longer has it. notificationId={}",
                row.batchId(), row.notificationId());
    }

    /**
     * One recipient's POST, asked again for as long as the answer may change and the budget holds.
     *
     * <p>The refusal is caught here rather than left to end the batch, and it is not absorbed: the
     * status that made the last attempt one becomes the row's {@code response_code} and its own
     * series on {@code yotresultsdistribution_notifications_total}, and the transport detail was already
     * reported by the client that saw it. The line here carries the notification id, the batch id,
     * the status and the bounded classification, and none of those is about a person.
     *
     * <p><strong>The loop is here rather than in the client</strong>, which is exactly where the
     * generation leg puts it and for the same reason: the client classifies one attempt through the
     * shared {@link RetryPolicy} and is told nothing about what is left of the budget, so a loop
     * inside it would spend one it cannot see. The branch is on the classification and never on the
     * type of what was thrown - NON_TRANSIENT means the same command under the same identity will
     * be declined again, and waiting to prove it costs the teams after this one.
     *
     * <p><strong>And the retry is the same POST.</strong> The row arrives persisted, so the second
     * attempt goes out under the notification id the first did: notificationnotify keys its
     * aggregate on that id, so the retry reaches the attempt it is retrying instead of asking for a
     * second register about the same children (research §10).
     *
     * <p><strong>The wait is what the answer asked for where it asked for something.</strong> A
     * {@code Retry-After} is notificationnotify saying when it expects to be able to take the
     * command, and the whole point of the header is that it knows that better than this service's
     * schedule - so it is spent instead of the back-off's next step, on any retryable answer rather
     * than on a 429 alone. The client read it through the shared {@link RetryPolicy} and this leg
     * spends it through the same object, which is what bounds it: delta-seconds only, and never more
     * than {@code max-backoff} however long the other side asked for. An unusable value and no
     * header at all are the same thing, the back-off (defect fix C3).
     *
     * <p>No deadline is measured against each attempt, and that is the one place this differs from
     * the generation leg. That leg runs inside the nightly run's claim and charges every attempt
     * against what is left of it; this leg holds a claim too - the batch's own, taken by
     * {@code claimForNotification} - but bounds itself by renewing the lease before every POST
     * rather than by counting down against a deadline. So what bounds one recipient's cycle is the
     * attempt budget and {@code max-backoff}, which is what bounds every wait this policy hands out,
     * and {@code yotresultsdistribution.notification.claim-lease} is held at startup to twice that cycle.
     *
     * <p><strong>But the claim is asked about again before every retry, because a retry is a POST
     * like any other.</strong> The caller renews before the first attempt and this loop renews
     * before each of the rest, so no POST is made by a notifier that has not just asked whether the
     * batch is still its own. Renewing once for the recipient and spending it across the cycle
     * fenced the first attempt and none of the others - and the others are the ones made after a
     * read timeout and a back-off wait, which is precisely how long a lease has been left
     * unrenewed. A notifier that has been taken over would spend the rest of its budget POSTing for
     * a team the notifier that now holds the batch is telling, which is a second register about the
     * same children.
     *
     * @param row            the persisted row the POST is made under
     * @param documentFileId the rendered document's file-service id
     * @param token          the token this run holds the batch's notification claim under
     * @return ACCEPTED with the status notificationnotify answered, or FAILED with what the last
     *     attempt answered instead - which is nothing at all where it reached no verdict - and how
     *     many POSTs this call made either way; or no outcome, where the claim was taken over
     *     between two of them
     */
    // PMD.OnlyOneReturn: the accepted attempt answers where it happened, and so does the renewal
    // that is refused. A single exit would mean carrying an outcome past the branch that decides
    // whether to ask again, and the whole subject of the loop is that either of those stops it.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private Attempted attempt(final RegisterNotification row, final UUID documentFileId,
            final UUID token) {

        final int maxAttempts = retryPolicy.maxAttempts();
        OptionalInt lastAnswer = OptionalInt.empty();
        int posts = 0;

        while (posts < maxAttempts) {
            posts++;
            try {
                return new Attempted(
                        notifier.send(row, documentFileId, CallerIdentity.SYSTEM), posts);
            } catch (NotificationFailedException refused) {
                lastAnswer = refused.responseCode();
                LOG.warn("notificationnotify did not accept a register e-mail on attempt {} of {}. "
                        + "notificationId={} batchId={} responseCode={} classification={} "
                        + "cause={}",
                        posts, maxAttempts, row.notificationId(), row.batchId(),
                        statusOf(lastAnswer), refused.classification(),
                        refused.getClass().getName());
                if (refused.classification() != FailureClassification.TRANSIENT
                        || posts == maxAttempts
                        || !waitFor(row, retryPolicy.waitAfter(posts, refused.retryAfter()))) {
                    break;
                }
                if (!batches.renewNotificationClaim(row.batchId(), token)) {
                    return new Attempted(null, posts);
                }
            }
        }
        LOG.warn("The register e-mail for one recipient of batch {} was not accepted, so its row "
                + "records the attempts and the batch carries on to the recipients after it. "
                + "notificationId={} attempts={} responseCode={}", row.batchId(),
                row.notificationId(), posts, statusOf(lastAnswer));
        return new Attempted(
                new NotificationOutcome(NotificationStatus.FAILED, statusOf(lastAnswer)), posts);
    }

    /**
     * The status the last attempt carried, where it carried one.
     *
     * @param answered what notificationnotify answered, which is nothing at all where the attempt
     *                 reached no verdict
     * @return the status, or {@code null} - a row carrying an invented one would say an attempt was
     *     answered when nothing answered
     */
    private static Integer statusOf(final OptionalInt answered) {
        return answered.isPresent() ? answered.getAsInt() : null;
    }

    /**
     * Takes the wait between two attempts at one recipient.
     *
     * <p>An interrupt is restored and the recipient given up on rather than swallowed: the thread
     * has been asked to stop, and a notification that carried on asking would outlive the shutdown
     * that ended it. The row stays unsettled-or-FAILED under its own identity, so the resend that
     * follows reaches the same attempt.
     *
     * @param row  the row being asked about
     * @param wait how long the shared policy said to wait
     * @return whether the wait completed and another attempt may be made
     */
    private boolean waitFor(final RegisterNotification row, final Duration wait) {
        boolean waited = true;
        try {
            pause.pause(wait);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while waiting to ask for a register e-mail again, so no further "
                    + "attempt is made. notificationId={} batchId={}", row.notificationId(),
                    row.batchId());
            waited = false;
        }
        return waited;
    }

    /**
     * The row as this attempt leaves it.
     *
     * <p>The identity, the batch, the address, the name and the template are carried through
     * unchanged: what was sent, and to whom, was decided when the row was minted and an attempt may
     * only say how it ended. {@code sent_at} is this pod's reading of now for a failure as much as
     * for an acceptance, because what it records is when the attempt was settled.
     *
     * <p>{@code attempts} is carried through untouched, and the count this call made travels beside
     * the row instead. What the column accumulates is the POSTs made for the row and not the runs
     * that made them, so what this service knows is how many it made; the total the row reaches is
     * arithmetic over whatever the row holds at the moment of the write, which is the statement's
     * to do.
     *
     * @param row     the row as it stood before this attempt
     * @param outcome what notificationnotify answered, or did not
     * @return the row as it should now stand
     */
    private RegisterNotification settledAs(
            final RegisterNotification row, final NotificationOutcome outcome) {

        return new RegisterNotification(row.notificationId(), row.batchId(), row.emailAddress(),
                row.recipientName(), row.templateName(), row.templateId(), outcome.status(),
                outcome.responseCode(), clock.instant(), row.attempts());
    }

    /**
     * Settles the batch on the tally of its rows, through the store's own compare-and-set.
     *
     * <p>The three branches are the sink's, for the sink's reasons. A batch already standing where
     * the tally would put it has nothing left to record - a resend refused a second time is the same
     * state, not a worse one - and a move the state machine does not draw leaves the batch where it
     * is and is reported here rather than attempted. Only the middle branch writes, so the terminal
     * state is counted exactly where it is recorded.
     *
     * @param batch the batch as it stood when this run read it
     * @return the tally the batch was settled on
     */
    private NotificationSummary settle(final RegisterBatch batch) {
        final NotificationSummary summary = tally(notifications.findByBatchId(batch.batchId()));

        if (batch.status() == summary.outcome()) {
            LOG.debug("Batch {} already stands at {}, so the tally that has just been taken for it "
                    + "again is recognised rather than re-written.", batch.batchId(),
                    summary.outcome());
        } else if (batch.status().canTransitionTo(summary.outcome())) {
            store.markNotified(batch.batchId(), summary);
            metrics.batchCompleted(summary.outcome());
            LOG.info("Batch {} is settled {}: {} recipients accepted and {} failed.",
                    batch.batchId(), summary.outcome(), summary.accepted(), summary.failed());
        } else {
            LOG.warn("Batch {} stands at {} and its recipients tally to {}, which the state machine "
                    + "does not draw; the batch is left where it is and the tally is reported here "
                    + "rather than written.", batch.batchId(), batch.status(), summary.outcome());
        }
        return summary;
    }

    /**
     * What the batch's rows add up to, and the terminal state that produces.
     *
     * <p>The verdict is not derivable from the two counts alone, which is why
     * {@link NotificationSummary} carries all three: no rows at all is the batch that had nobody to
     * tell (defect fix P1) and is a state no count of failures can produce, while a row that is
     * neither accepted nor failed is an attempt this run could not finish - it is not a team that
     * was told, so the batch it belongs to has not told everybody.
     *
     * @param rows every {@code register_notification} row of the batch, as they now stand
     * @return the accepted and failed counts, and the terminal state they settle the batch in
     */
    private static NotificationSummary tally(final List<RegisterNotification> rows) {
        final int accepted = (int) rows.stream()
                .filter(row -> row.status() == NotificationStatus.ACCEPTED)
                .count();
        final int failed = (int) rows.stream()
                .filter(row -> row.status() == NotificationStatus.FAILED)
                .count();
        return new NotificationSummary(accepted, failed, outcomeOf(rows.size(), accepted));
    }

    /**
     * The terminal state a batch's rows put it in.
     *
     * @param recipients how many rows the batch holds, which is how many teams it is addressed to
     * @param accepted   how many of them notificationnotify accepted
     * @return NOTIFIED_NOBODY, NOTIFIED or PARTIALLY_NOTIFIED
     */
    private static BatchStatus outcomeOf(final int recipients, final int accepted) {
        final BatchStatus outcome;
        if (recipients == 0) {
            outcome = BatchStatus.NOTIFIED_NOBODY;
        } else if (accepted == recipients) {
            outcome = BatchStatus.NOTIFIED;
        } else {
            outcome = BatchStatus.PARTIALLY_NOTIFIED;
        }
        return outcome;
    }

    /**
     * How one recipient's turn ended, as the three things the cycle does about it.
     *
     * <p>Bounded and private: it is the loop's own control and never a label or a message. What a
     * caller of this service branches on is {@link NotificationDisposition}, which two of these
     * three produce.
     */
    private enum Turn {

        /**
         * The row carries this attempt's verdict, or kept the acceptance it already had, so the
         * next recipient is asked and the batch may be settled on the tally.
         */
        SETTLED,

        /**
         * The claim was taken over, so this run stops: the notifier that now holds the batch is
         * telling the recipients this one had not reached.
         */
        CLAIM_LOST,

        /**
         * The store holds no row under the identity this run posted under, so the cycle cannot
         * account for one of the batch's recipients and the batch is left where it stands.
         */
        ROW_ABSENT
    }

    /**
     * What one recipient's attempts came to, and how many of them there were.
     *
     * @param outcome ACCEPTED with the status notificationnotify answered, or FAILED with what the
     *                last attempt answered instead - or nothing at all, where the claim was taken
     *                over between two attempts and this run has no verdict to offer about the
     *                e-mail
     * @param posts   how many POSTs this call made for the row, which is what its {@code attempts}
     *                accumulates
     */
    private record Attempted(NotificationOutcome outcome, int posts) {

        /**
         * Whether the claim was still this run's throughout the attempts it made.
         *
         * <p>An outcome is a verdict about the e-mail, and a run that was taken over mid-cycle
         * reached none: it stopped asking rather than deciding anything, and the POSTs it had
         * already made are the only thing it has to say.
         *
         * @return whether these attempts ended in an outcome rather than in a refused renewal
         */
        /* default */ boolean stillOurs() {
            return outcome != null;
        }
    }

    /**
     * The batch a notification is about.
     *
     * @param batchId the batch identity the caller named
     * @return the batch row
     * @throws NoSuchBatchException where this store holds no such batch, which is a caller naming
     *     an identity nothing was ever assembled under rather than a batch with nothing to send
     */
    private RegisterBatch batchOf(final UUID batchId) {
        return batches.findById(batchId).orElseThrow(() -> noSuchBatch(batchId));
    }

    /**
     * The failure a caller naming an identity nothing was ever assembled under gets.
     *
     * <p>Named once because two things reach it: a read that came back empty, and a claim attempt
     * that found no batch to claim. The second used to be reported as contention, which is a batch
     * somebody else is telling - a different night from a batch that does not exist, and the wrong
     * one to tell an operator about.
     *
     * <p><strong>And it has its own type.</strong> It used to be a bare
     * {@code IllegalStateException}, which is also what a batch carrying no document and a
     * notification row the store holds none for raise - so a caller could not tell "this
     * identifier names nothing" from "this batch could not be finished with", and the operations
     * endpoint had to answer all three under the command's {@code resend-failed}. The type is what
     * lets the first be a {@code 404}. {@link NoSuchBatchException} extends
     * {@code IllegalStateException}, so nothing that caught the three together stops catching it.
     *
     * @param batchId the identity the caller named
     * @return the failure to raise; the message carries the identity and nothing else
     */
    private static NoSuchBatchException noSuchBatch(final UUID batchId) {
        return new NoSuchBatchException(batchId);
    }

    /**
     * The document every e-mail of the batch attaches, by file-service id.
     *
     * @param batch the batch the e-mails are about
     * @return the rendered document's file-service id
     * @throws IllegalStateException where the batch carries none, which is a batch that has not
     *     been generated and so has nothing any recipient could be sent
     */
    private static UUID documentOf(final RegisterBatch batch) {
        if (batch.documentFileId() == null) {
            throw new IllegalStateException("register batch " + batch.batchId() + " stands at "
                    + batch.status() + " and carries no document, so there is nothing to attach");
        }
        return batch.documentFileId();
    }
}
