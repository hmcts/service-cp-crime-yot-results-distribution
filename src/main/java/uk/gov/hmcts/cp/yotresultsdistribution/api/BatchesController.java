package uk.gov.hmcts.cp.yotresultsdistribution.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.yotresultsdistribution.api.dto.BatchListingResponse;
import uk.gov.hmcts.cp.yotresultsdistribution.api.dto.GenerateRegisterRequest;
import uk.gov.hmcts.cp.yotresultsdistribution.api.dto.GenerateRegisterResponse;
import uk.gov.hmcts.cp.yotresultsdistribution.api.dto.NotifyBatchResponse;
import uk.gov.hmcts.cp.yotresultsdistribution.application.BatchListing;
import uk.gov.hmcts.cp.yotresultsdistribution.application.BatchListingService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.NotificationSummary;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OperationsRunLauncher;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OperationsRunLauncher.RunAccepted;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterNotifierService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterRegenerationService.Selection;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NoSuchBatchException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;

/**
 * The batch endpoints: what a register date holds, and - from a later task - what to do about it.
 *
 * <p>An inbound adapter and nothing else. It parses the one argument, calls
 * {@link BatchListingService}, and maps the answer; the three reads a listing is built from are the
 * service's, because a controller that held a repository would not be an adapter any more.
 *
 * <p><strong>Nothing the caller typed comes back.</strong> A date that will not read is refused by
 * the name of the <em>argument</em>, never by its value (FR-025): a refusal is read in a log index
 * and in a ticket, and the characters an operator typed are the one thing this service cannot
 * vouch for. The date on a successful listing is this service's own parse, not those characters.
 *
 * <p>{@code @Profile("!test")} because {@link BatchListingService} carries it: the store and its
 * two repositories are declared `!test` in the processed-log configuration, that profile has no
 * database at all, and a controller over readers that do not exist is a context that will not
 * refresh.
 *
 * <p><strong>And the same condition the listings carry</strong>, because
 * {@code yotresultsdistribution.operations.enabled=false} is what stops this service answering the
 * operator's paths at all (FR-044). The switch withdraws the listings; a controller left scanned
 * over a listing nothing contributes is a pod that will not start, which is the one thing a
 * deployment shape setting may not do.
 *
 * <p><strong>And {@code yotresultsdistribution.generation.enabled}, because two of its three endpoints
 * need the generating half.</strong> The regeneration hand-off and the resend are contributed only
 * where the flag gate, the assembler, the requesting leg and the notifier are, so a pod that
 * renders nothing holds none of them - and a controller left scanned over beans that do not exist
 * is the refresh failure the operations switch is written to avoid. Such a pod answers all three
 * batch paths {@code 501 command-not-wired} through {@link NotWiredController}, which is exactly
 * what the commands they replace answered there (FR-052).
 */
@RestController
@Profile("!test")
@ConditionalOnProperty(prefix = "yotresultsdistribution.operations", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "yotresultsdistribution.generation", name = "enabled",
        havingValue = "true")
public class BatchesController {

    private static final Logger LOG = LoggerFactory.getLogger(BatchesController.class);

    /** This service's own name for the one argument the listing takes. */
    private static final String DATE = "date";

    /** This service's own name for the argument that names one batch of a date. */
    private static final String BATCH_ID = "batchId";

    /** This service's own name for the argument that bounds a run to part of a date. */
    private static final String RECORDED_BEFORE = "recordedBefore";

    /** The two listings, over the three reads they are built from. */
    private final BatchListingService listings;

    /** The regeneration hand-off, which validates, reads the flag and answers with a run id. */
    private final OperationsRunLauncher launcher;

    /** The resend, whose {@code resendFailed} is the whole of what the notify endpoint does. */
    private final RegisterNotifierService notifier;

    /**
     * Creates the endpoints over the services they answer from.
     *
     * @param batchListings   the application service holding the reads
     * @param runLauncher     the application service holding the regeneration hand-off
     * @param notifierService the application service holding the resend and its claim
     */
    public BatchesController(final BatchListingService batchListings,
            final OperationsRunLauncher runLauncher,
            final RegisterNotifierService notifierService) {
        this.listings = batchListings;
        this.launcher = runLauncher;
        this.notifier = notifierService;
    }

    /**
     * Re-requests the recipients of one batch that no e-mail has been accepted for.
     *
     * <p>Which rows are attempted, under which identities, and what the batch is then settled as
     * is {@link RegisterNotifierService#resendFailed}'s rule and nothing this endpoint
     * reimplements - including the claim that decides between the four dispositions, which is what
     * makes two concurrent notifies for one batch a clean refusal rather than two e-mails about
     * the same children to the same team.
     *
     * <p><strong>The three unsettled endings are not one.</strong> {@code ALREADY_NOTIFYING} is a
     * {@code 409} because this call changed nothing; {@code CLAIM_LOST} and {@code INCOMPLETE} are
     * {@code 500} because it tried and got part-way (spec assumption 3), and the batch is left for
     * another call to recover.
     *
     * @param batchId the batch an operator carried in from a support ticket
     * @return the tally as the batch now stands, and what this call did about it
     * @throws OperationsRefusedException where the identifier will not read, or the resend did
     *         not settle - all answered by {@link OperationsExceptionHandler} from the one status
     *         map
     */
    @PostMapping(path = "/operations/batches/{batchId}/notify",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public NotifyBatchResponse notify(@PathVariable(name = BATCH_ID) final String batchId) {
        final UUID batch = namedBatchOf(batchId);
        return settled(batch, resent(batch));
    }

    /**
     * The batch an operator named, which this endpoint never defaults.
     *
     * @param typed what the caller put in the path
     * @return the batch's identity
     */
    private static UUID namedBatchOf(final String typed) {
        try {
            return UUID.fromString(typed);
        } catch (IllegalArgumentException notAnIdentity) {
            throw new OperationsRefusedException(OperationsReason.UNREADABLE_ARGUMENT, BATCH_ID,
                    notAnIdentity);
        }
    }

    /**
     * The resend, with the three things that are not this service's defect classified apart.
     *
     * <p>The command caught all of them as one {@code RuntimeException} and printed
     * {@code resend-failed}; HTTP has truer codes and an operator acts on the difference - a store
     * that will not answer is an outage to wait out, and a far end that refused is somebody
     * else's incident. The notifier's own refusals are now two cases and not one:
     * {@link NoSuchBatchException} is an identity nothing was ever assembled under, which is a
     * {@code 404}, and every other {@code IllegalStateException} it raises - a batch that exists
     * and carries no document, a notification row the store refused and then holds none for - is a
     * batch this service knows about and could not finish with, which keeps the command's
     * {@code resend-failed}. Answering those two the same way was the whole reason the
     * {@code 404} could not be given: it would have sent an operator to check an identifier that
     * was right.
     *
     * @param batchId the batch to resend for
     * @return the tally the notifier answered with
     */
    private NotificationSummary resent(final UUID batchId) {
        try {
            return notifier.resendFailed(batchId);
        } catch (StoreUnavailableException | TransientDataAccessException
                | RecoverableDataAccessException | DataAccessResourceFailureException notRead) {
            // The shapes an unreachable store has on this path, and only those - for the reason
            // the listing states at the same catch. Anything else is left to reach the 500 the
            // status map keeps for a defect.
            LOG.error("The recipients owed by batch {} could not be re-requested, because this "
                    + "service's own store would not answer. cause={}", batchId,
                    notRead.getClass().getName());
            throw new OperationsRefusedException(OperationsReason.STORE_UNAVAILABLE, null,
                    Map.of(BATCH_ID, batchId.toString()), notRead);
        } catch (NotificationFailedException refused) {
            // notificationnotify is a consumed platform contract: a refusal is its verdict and a
            // silence is its absence, and the two are different things for whoever is on call.
            LOG.error("Batch {}'s resend was not made, because notificationnotify did not take "
                    + "it. classification={}", batchId, refused.classification().name());
            throw new OperationsRefusedException(downstream(refused), null,
                    Map.of(BATCH_ID, batchId.toString()), refused);
        } catch (NoSuchBatchException named) {
            // The one of the notifier's refusals that is about the identifier rather than about
            // the batch behind it: nothing was ever assembled under it, so there is nothing for a
            // later attempt to find and an operator's next step is to check what they typed.
            LOG.warn("A resend was asked for a batch this service never assembled. reason={}",
                    OperationsReason.UNKNOWN_BATCH.wire());
            throw new OperationsRefusedException(OperationsReason.UNKNOWN_BATCH, null,
                    Map.of(BATCH_ID, batchId.toString()), named);
        } catch (IllegalStateException notMade) {
            // Everything else the notifier refuses under this type is a batch that exists: one
            // carrying no document because it was never generated, and a notification row the
            // store refused and then holds none for. Both are the command's own resend-failed -
            // the call tried and could not finish - and neither is a 404, because the identifier
            // the operator gave is right.
            LOG.error("Batch {}'s resend was not made, because the notifier would not finish it. "
                    + "reason={} cause={}", batchId, OperationsReason.RESEND_FAILED.wire(),
                    notMade.getClass().getName());
            throw new OperationsRefusedException(OperationsReason.RESEND_FAILED, null,
                    Map.of(BATCH_ID, batchId.toString()), notMade);
        }
    }

    /**
     * Which of the two downstream codes a refused send is.
     *
     * @param refused what notificationnotify did about it
     * @return the bounded code, which is a refusal or an absence and never both
     */
    private static OperationsReason downstream(final NotificationFailedException refused) {
        return refused.classification() == FailureClassification.TRANSIENT
                ? OperationsReason.DOWNSTREAM_UNAVAILABLE
                : OperationsReason.DOWNSTREAM_REFUSED;
    }

    /**
     * The answer one of the four dispositions deserves.
     *
     * @param batchId the batch the attempt was about
     * @param tally   the batch as it now stands, and what this call did about it
     * @return the body, where the resend settled
     */
    private static NotifyBatchResponse settled(final UUID batchId,
            final NotificationSummary tally) {

        return switch (tally.disposition()) {
            case SETTLED -> new NotifyBatchResponse(batchId, tally.accepted(), tally.failed(),
                    tally.outcome(), tally.disposition().code());
            case ALREADY_NOTIFYING -> throw refusedResend(OperationsReason.ALREADY_NOTIFYING,
                    batchId, tally);
            case CLAIM_LOST -> throw refusedResend(OperationsReason.CLAIM_LOST, batchId, tally);
            case INCOMPLETE -> throw refusedResend(OperationsReason.INCOMPLETE, batchId, tally);
        };
    }

    /**
     * One unsettled ending, carrying the batch as it stands and nothing else.
     *
     * @param reason  the bounded code, which is the disposition's own
     * @param batchId the batch the attempt was about
     * @param tally   the batch as it now stands
     * @return the refusal to raise
     */
    private static OperationsRefusedException refusedResend(final OperationsReason reason,
            final UUID batchId, final NotificationSummary tally) {

        return new OperationsRefusedException(reason, Map.of(BATCH_ID, batchId.toString(),
                "accepted", tally.accepted(), "failed", tally.failed(),
                "state", tally.outcome().name()));
    }

    /**
     * Regenerates a register date, and answers as soon as the run has been accepted.
     *
     * <p>Every decision is the launcher's: the cross-field rule on the override, the one lever's
     * reading, the run id and the lock. This parses the three values that have to be read, one at
     * a time and each under its own name, so that a refusal can say <em>which</em> argument would
     * not read - the one thing about a refused value that may be written down, since the value
     * itself may not (FR-024).
     *
     * @param request what the caller asked for, or {@code null} where they sent no body
     * @return {@code 202} with the run id, the date as this service parsed it, and whether the run
     *         goes ahead over a flag that would have stopped it
     * @throws uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException where the date is
     *         absent, where any of the three values will not read, where an override was asked for
     *         without the one batch it may cover, or where the flag did not admit the run - all
     *         answered by {@link OperationsExceptionHandler} from the one status map
     */
    @PostMapping(path = "/operations/batches/generate",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> generate(
            @RequestBody(required = false) final GenerateRegisterRequest request) {

        final GenerateRegisterRequest asked =
                request == null ? GenerateRegisterRequest.NOTHING : request;
        if (asked.date() == null || asked.date().isBlank()) {
            throw new OperationsRefusedException(OperationsReason.MISSING_ARGUMENT, DATE, null);
        }
        final Selection selection = new Selection(dateOf(asked.date()), asked.courtHouse(),
                batchOf(asked.batchId()), instantOf(asked.recordedBefore()), asked.overrideAsked());
        final RunAccepted accepted = launcher.launch(selection);
        // An override is an operator's decision and is written down twice: here, on the audit
        // event with the caller's identity, and on the run report line the background run writes.
        recordForAudit(accepted);
        return ResponseEntity.accepted().body(new GenerateRegisterResponse(accepted.runId(),
                accepted.registerDate(), accepted.overridden()));
    }

    /**
     * Puts the two facts a regeneration adds onto this call's audit event.
     *
     * @param accepted what the launcher answered with
     */
    private static void recordForAudit(final RunAccepted accepted) {
        final OperationsAuditFacts facts = OperationsAuditFacts.current();
        if (facts != null) {
            facts.regeneration(accepted.overridden(), accepted.runId());
        }
    }

    /**
     * The register date, as this service reads it.
     *
     * @param typed what the caller sent
     * @return the date
     */
    private static LocalDate dateOf(final String typed) {
        try {
            return LocalDate.parse(typed);
        } catch (DateTimeParseException notADate) {
            throw new OperationsRefusedException(OperationsReason.UNREADABLE_ARGUMENT, DATE,
                    notADate);
        }
    }

    /**
     * The one batch a caller named, or the absence of one.
     *
     * @param typed what the caller sent, which may be absent
     * @return the batch's identity, or {@code null} for every batch of the day
     */
    // PMD.OnlyOneReturn: absent and unreadable are different answers to different questions, each
    // said where it is decided.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private static UUID batchOf(final String typed) {
        if (typed == null || typed.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(typed);
        } catch (IllegalArgumentException notAnIdentity) {
            throw new OperationsRefusedException(OperationsReason.UNREADABLE_ARGUMENT, BATCH_ID,
                    notAnIdentity);
        }
    }

    /**
     * The instant a caller bounded the run at, or the absence of a bound.
     *
     * @param typed what the caller sent, which may be absent
     * @return the exclusive bound, or {@code null} for the whole day
     */
    // PMD.OnlyOneReturn: as above - no bound and a bound that will not read are two answers.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private static Instant instantOf(final String typed) {
        if (typed == null || typed.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(typed);
        } catch (DateTimeParseException notAnInstant) {
            throw new OperationsRefusedException(OperationsReason.UNREADABLE_ARGUMENT,
                    RECORDED_BEFORE, notAnInstant);
        }
    }

    /**
     * One register date's batches, each with its record count and its recipients.
     *
     * @param date the register date, as an ISO local date; required
     * @return the date's batches, or the bounded refusal that stopped the listing being made
     * @throws RuntimeException any failure that is not the store being unreachable, which is a
     *         defect in this service and is answered {@code 500} rather than being dressed up as
     *         a dependency outage (FR-023)
     */
    @GetMapping(path = "/operations/batches", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> list(
            @RequestParam(name = DATE, required = false) final String date) {

        if (date == null || date.isBlank()) {
            throw new OperationsRefusedException(OperationsReason.MISSING_ARGUMENT, DATE, null);
        }
        final LocalDate registerDate;
        try {
            registerDate = LocalDate.parse(date);
        } catch (DateTimeParseException notADate) {
            throw new OperationsRefusedException(OperationsReason.UNREADABLE_ARGUMENT, DATE,
                    notADate);
        }
        try {
            return ResponseEntity.ok(listed(registerDate));
        } catch (StoreUnavailableException | TransientDataAccessException
                | RecoverableDataAccessException | DataAccessResourceFailureException notRead) {
            // The shapes an unreachable store has on this path, and only those: the store
            // translates its own outage into the first, and a repository that reached the driver
            // before the driver reached the database hands the refusal out as one of the other
            // three. The whole DataAccessException hierarchy is NOT what is caught - a bad grammar,
            // a violated constraint or a mapping that will not read are all defects in this
            // service wearing the store's exception type, and a defect answered 503 is a defect a
            // runbook retries for ever. Anything else is left to reach the 500 the design rules'
            // status map keeps for it, whose body OperationsErrorAttributes renders in the same
            // bounded fields.
            LOG.error("The batches of one register date could not be read, so no listing is given "
                    + "for it. cause={}", notRead.getClass().getName());
            throw new OperationsRefusedException(OperationsReason.LISTING_FAILED, null,
                    notRead);
        }
    }

    /**
     * Maps the service's answer onto the body.
     *
     * @param registerDate the date the listing is for
     * @return the listing as it is answered
     */
    private BatchListingResponse listed(final LocalDate registerDate) {
        final List<BatchListingResponse.BatchSummary> summaries =
                listings.batchesOn(registerDate).stream().map(BatchesController::summarised)
                        .toList();
        return new BatchListingResponse(registerDate, summaries);
    }

    /**
     * One batch, as the response describes it.
     *
     * @param listing the batch as the service answered it
     * @return the batch as the body carries it
     */
    private static BatchListingResponse.BatchSummary summarised(final BatchListing listing) {
        return new BatchListingResponse.BatchSummary(listing.batchId(), listing.courtHouse(),
                listing.state(), listing.records(),
                listing.recipients().stream()
                        .map(recipient -> new BatchListingResponse.RecipientOutcome(
                                recipient.address(), recipient.outcome()))
                        .toList());
    }

}
