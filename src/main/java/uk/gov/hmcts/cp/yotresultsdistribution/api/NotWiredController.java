package uk.gov.hmcts.cp.yotresultsdistribution.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException;

/**
 * The honest answer of a pod that holds no generating half, for the three paths that need one.
 *
 * <p>{@link BatchesController} is conditional on {@code yotresultsdistribution.generation.enabled}, because
 * two of its three endpoints call services contributed only behind that switch - the regeneration
 * hand-off and the resend - and a controller left scanned over beans that do not exist is an
 * {@code UnsatisfiedDependencyException} at refresh. This is what stands in its place, and it maps
 * all three of its paths rather than two: the listing shares a class with them, so a pod without
 * the generating half serves none of the three, and saying so is better than serving two of them
 * and mapping nothing for the third.
 *
 * <p><strong>{@code 501} and not {@code 404} or {@code 500}</strong> (FR-052). This is exactly what
 * {@code CliMain} answered on such a pod: a {@code 404} would read as a mistyped URL and send an
 * operator to check the path they typed, and a {@code 500} would read as a bean-definition error
 * they cannot act on. {@code command-not-wired} says the one true thing - this deployment does not
 * hold that half of the service - which is actionable, because the next step is to reach a pod
 * that does.
 *
 * <p><strong>The other four endpoints are served here as everywhere.</strong> The flag reading,
 * the recorded-while-off listing, the rollback and the exception report need no generating bean,
 * and {@code GET /operations/flag} in particular is served on every pod by decision
 * (2026-09-21): which implementation is live is not a property of the replica an operator happened
 * to reach.
 *
 * <p>Nothing about the request comes back. The refusal is the bounded code and nothing else, built
 * by {@link OperationsExceptionHandler} from the one status map exactly as every other refusal on
 * this surface is.
 */
@RestController
@Profile("!test")
@ConditionalOnProperty(prefix = "yotresultsdistribution.operations", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "yotresultsdistribution.generation", name = "enabled",
        havingValue = "false", matchIfMissing = true)
public class NotWiredController {

    /**
     * The batch listing, which shares its class with the two endpoints this pod cannot serve.
     *
     * @throws OperationsRefusedException always, under {@link OperationsReason#COMMAND_NOT_WIRED}
     */
    @GetMapping(path = "/operations/batches")
    public void list() {
        throw notWired();
    }

    /**
     * The regeneration, which needs the flag gate, the assembler and the requesting leg.
     *
     * @throws OperationsRefusedException always, under {@link OperationsReason#COMMAND_NOT_WIRED}
     */
    @PostMapping(path = "/operations/batches/generate")
    public void generate() {
        throw notWired();
    }

    /**
     * The resend, which needs the notifier.
     *
     * @throws OperationsRefusedException always, under {@link OperationsReason#COMMAND_NOT_WIRED}
     */
    @PostMapping(path = "/operations/batches/{batchId}/notify")
    public void notifyBatch() {
        throw notWired();
    }

    /**
     * The one refusal this controller exists to give.
     *
     * @return the refusal, carrying the bounded code and nothing about the request
     */
    private static OperationsRefusedException notWired() {
        return new OperationsRefusedException(OperationsReason.COMMAND_NOT_WIRED);
    }
}
