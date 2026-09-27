package uk.gov.hmcts.cp.yotresultsdistribution.api;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.yotresultsdistribution.api.dto.RecordedWhileOffResponse;
import uk.gov.hmcts.cp.yotresultsdistribution.api.dto.SupersedeRequest;
import uk.gov.hmcts.cp.yotresultsdistribution.api.dto.SupersedeResponse;
import uk.gov.hmcts.cp.yotresultsdistribution.application.BatchListingService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OperationsSupersessionService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.Supersession;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;

/**
 * The register endpoints: what is waiting, and what a rollback gives up.
 *
 * <p>An inbound adapter. It parses, calls one application service, and maps the answer.
 *
 * <p>The listing exists because automatic batching passes these rows over deliberately: they were
 * recorded while the flag said the legacy generates. Without an answer that names them, a rollback
 * would leave every one waiting for somebody to find it.
 *
 * <p>{@code @Profile("!test")} for the reason {@link BatchesController} carries it: the store is
 * declared `!test` in the processed-log configuration and that profile has no database. And
 * {@code yotresultsdistribution.operations.enabled} for the reason it carries that: the switch that stops
 * this service answering the operator's paths withdraws the listings too, and a controller left
 * scanned over one that is gone is a pod that will not start (FR-044).
 */
@RestController
@Profile("!test")
@ConditionalOnProperty(prefix = "yotresultsdistribution.operations", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RegistersController {


    private static final Logger LOG = LoggerFactory.getLogger(RegistersController.class);


    /** This service's own name for the one argument the rollback takes. */
    private static final String SHARED_BEFORE = "sharedBefore";

    /** The two listings, over the three reads they are built from. */
    private final BatchListingService listings;

    /** The rollback, with the flag read and the two bounds it is admitted under. */
    private final OperationsSupersessionService supersession;

    /**
     * Creates the endpoints over the services they answer from.
     *
     * @param batchListings the application service holding the reads
     * @param rollback      the application service holding the rollback and its guards
     */
    public RegistersController(final BatchListingService batchListings,
            final OperationsSupersessionService rollback) {

        this.listings = batchListings;
        this.supersession = rollback;
    }

    /**
     * Gives up every register shared before an instant, or says how many that would be.
     *
     * <p>Every guard is the service's: the flag read, the two bounds and the dry run. This maps
     * the one argument that has to be parsed and nothing else - and it parses it here rather than
     * letting the binder do it, so that a value which will not read is refused by the name of the
     * argument instead of by a framework message carrying the caller's characters.
     *
     * @param request what the caller asked for, or {@code null} where they sent no body
     * @return the count, the bound and whether anything was written
     * @throws uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException where the instant is
     *         absent, will not read or is outside its bounds, where the flag does not say OFF, or
     *         where the store would not answer - all answered by
     *         {@link OperationsExceptionHandler} from the one status map
     */
    @PostMapping(path = "/operations/registers/supersede",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public SupersedeResponse supersede(
            @RequestBody(required = false) final SupersedeRequest request) {

        final SupersedeRequest asked = request == null ? SupersedeRequest.NOTHING : request;
        final Supersession done = supersession.supersede(instantOf(asked.sharedBefore()),
                asked.dryRunAsked());
        // The count goes into the audit event as well as the response (data-model section 6): how
        // much of the estate's history a rollback gave up is the fact this call is read for.
        final OperationsAuditFacts facts = OperationsAuditFacts.current();
        if (facts != null) {
            facts.superseded(done.superseded());
        }
        return new SupersedeResponse(done.superseded(), done.sharedBefore(), done.dryRun());
    }

    /**
     * The bound, as this service reads it.
     *
     * @param typed what the caller sent, which may be absent
     * @return the instant, or {@code null} where nothing was sent - which the service refuses as
     *         the missing argument it is, because this endpoint defaults no period
     */
    // PMD.OnlyOneReturn: absent and unreadable are different answers to different questions, each
    // said where it is decided; a sentinel carrying "not read" to one exit is the null this avoids
    // having to interpret twice.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private static Instant instantOf(final String typed) {
        if (typed == null || typed.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(typed);
        } catch (DateTimeParseException notAnInstant) {
            throw new OperationsRefusedException(OperationsReason.UNREADABLE_ARGUMENT,
                    SHARED_BEFORE, notAnInstant);
        }
    }

    /**
     * The registers automatic batching passed over because the flag did not say ON.
     *
     * @return the waiting registers, or the bounded refusal that stopped the listing being made
     * @throws RuntimeException any failure that is not the store being unreachable, which is a
     *         defect in this service and is answered {@code 500} rather than being dressed up as
     *         a dependency outage (FR-023)
     */
    @GetMapping(path = "/operations/registers/recorded-while-off",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> recordedWhileOff() {
        try {
            final List<RecordedWhileOffResponse.RecordedRegister> waiting =
                    listings.recordedWhileOff().stream()
                            .map(record -> new RecordedWhileOffResponse.RecordedRegister(
                                    record.recordId(), record.hearingId(), record.registerDate(),
                                    record.flag()))
                            .toList();
            return ResponseEntity.ok(new RecordedWhileOffResponse(waiting));
        } catch (StoreUnavailableException | TransientDataAccessException
                | RecoverableDataAccessException | DataAccessResourceFailureException notRead) {
            // The shapes an unreachable store has, and only those - for the reason
            // BatchesController states at the same catch. A defect answered 503 is a defect a
            // runbook retries for ever, so anything else, the rest of the DataAccessException
            // hierarchy included, is left to reach the 500 the status map keeps for it.
            LOG.error("The registers recorded while the flag was off could not be read, so no "
                    + "listing is given. cause={}", notRead.getClass().getName());
            throw new OperationsRefusedException(OperationsReason.LISTING_FAILED, null,
                    notRead);
        }
    }

}
