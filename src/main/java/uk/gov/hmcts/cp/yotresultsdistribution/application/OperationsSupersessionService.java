package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterRecord;

/**
 * The rollback lever: the registers shared before an instant, given up so no run will batch them.
 *
 * <p>{@code supersede-before}'s write, and <strong>three guards its command did not have</strong>.
 * They are requirements confirmed by the design owner on 2026-09-19, not precautions, and the
 * reason for all three is the same: {@code kubectl exec} reached as far as the people who held
 * exec rights, and HTTP reaches further.
 *
 * <p><strong>Admitted only while the flag says OFF.</strong> The flag is read here although the
 * command read it nowhere, uncached and once per call, and the answer is a refusal on anything but
 * {@link FlagDecision.Disabled}: {@code FLAG_ON} while this service is the live implementation,
 * because a rollback taken while this service is what generates is not a rollback; and
 * {@code flag-unreadable} when it cannot be read at all, fail-closed, because an unconditional
 * HTTP mutation that gives a period of registers up is a second lever however well authorised.
 * <strong>There is no override parameter and none may be added</strong> - that is the whole
 * difference between this endpoint and the generation one, which has a break-glass because its
 * command had one.
 *
 * <p><strong>Two bounds on the instant.</strong> One in the future has not happened, and one older
 * than {@code yotresultsdistribution.operations.supersede-max-age} would be an unbounded irreversible
 * mutation a keystroke away from giving up the estate's whole history of registers. Both are
 * refused before the flag is read: a malformed request is refused on its own terms, and consulting
 * the cutover state to reject one would make the refusal depend on something the caller cannot see.
 *
 * <p><strong>And a dry run</strong>, which reads the count and supersedes nothing. It is composed
 * from the two reads whose predicates together are the write's - the active unbatched registers
 * before the bound, and the ones recorded while the flag was not ON, which is the same predicate
 * with its flag-state test turned round - because the write's own predicate has no count read
 * beside it and inventing one would be a second definition of "what this rollback covers".
 */
public class OperationsSupersessionService {

    private static final Logger LOG =
            LoggerFactory.getLogger(OperationsSupersessionService.class);

    /** This service's own name for the one argument this call takes. */
    public static final String SHARED_BEFORE = "sharedBefore";

    /** The registers, through the store's own port. */
    private final RegisterStore registers;

    /** The one lever, read uncached and once per call. */
    private final FeatureFlagReader flag;

    /** How far back this endpoint may reach, which a deployment sets. */
    private final Duration maxAge;

    /** The one clock both bounds are taken against. */
    private final Clock clock;

    /**
     * Creates the rollback over the store, the lever and the bound.
     *
     * @param registerStore the registers
     * @param flagReader    the one lever
     * @param supersedeMaxAge how far back a rollback may reach
     * @param pods          the clock both bounds are taken against
     */
    public OperationsSupersessionService(final RegisterStore registerStore,
            final FeatureFlagReader flagReader, final Duration supersedeMaxAge,
            final Clock pods) {

        this.registers = registerStore;
        this.flag = flagReader;
        this.maxAge = supersedeMaxAge;
        this.clock = pods;
    }

    /**
     * Gives up every register shared before an instant, or says how many that would be.
     *
     * @param sharedBefore the exclusive upper bound, which this service never defaults
     * @param dryRun       whether to answer the count and supersede nothing
     * @return what was done, or what would have been
     * @throws OperationsRefusedException where the instant is absent or outside its bounds, where
     *         the flag does not say OFF, or where the store would not answer
     */
    public Supersession supersede(final Instant sharedBefore, final boolean dryRun) {
        if (sharedBefore == null) {
            throw new OperationsRefusedException(OperationsReason.MISSING_ARGUMENT, SHARED_BEFORE,
                    null);
        }
        final Instant now = clock.instant();
        if (sharedBefore.isAfter(now)) {
            throw new OperationsRefusedException(OperationsReason.SUPERSEDE_INSTANT_IN_FUTURE,
                    SHARED_BEFORE, null);
        }
        if (sharedBefore.isBefore(now.minus(maxAge))) {
            throw new OperationsRefusedException(OperationsReason.SUPERSEDE_INSTANT_TOO_OLD,
                    SHARED_BEFORE, null);
        }
        admittedBy(flag.read());
        return new Supersession(taken(sharedBefore, dryRun), sharedBefore, dryRun);
    }

    /**
     * The one lever, read once, with the only reading this endpoint is admitted under.
     *
     * @param reading what the flag said
     */
    private static void admittedBy(final FlagDecision reading) {
        switch (reading) {
            case FlagDecision.Disabled ignored -> {
                // The legacy is the implementation that generates, which is the only state a
                // rollback of a period means anything in.
            }
            case FlagDecision.Enabled ignored -> {
                LOG.warn("A rollback was asked for while this service is the implementation that "
                        + "generates, and was refused. reason={}",
                        OperationsReason.FLAG_ON.wire());
                throw new OperationsRefusedException(OperationsReason.FLAG_ON);
            }
            case FlagDecision.Unreadable unreadable -> {
                LOG.warn("A rollback was asked for and the one lever could not be read, so it was "
                        + "refused. reason={} cause={}",
                        OperationsReason.FLAG_UNREADABLE.wire(), unreadable.reason());
                throw new OperationsRefusedException(OperationsReason.FLAG_UNREADABLE);
            }
        }
    }

    /**
     * The count, written or merely read.
     *
     * @param sharedBefore the bound
     * @param dryRun       whether to leave the registers alone
     * @return how many registers were, or would have been, given up
     */
    // PMD.AvoidCatchingGenericException: the store translates an outage into its own unchecked
    // type and a refused statement arrives as another; both mean the same thing here - the period
    // was not given up - and a count answered over either would be a rollback reported as done.
    // Nothing is swallowed: the line below says it happened and the refusal leaves bounded.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private int taken(final Instant sharedBefore, final boolean dryRun) {
        try {
            return dryRun ? wouldTake(sharedBefore) : registers.supersedeSharedBefore(sharedBefore);
        } catch (RuntimeException notSuperseded) {
            LOG.error("The registers shared before an instant could not be superseded, so this "
                    + "service still claims them. dry_run={} cause={}", dryRun,
                    notSuperseded.getClass().getName());
            throw new OperationsRefusedException(OperationsReason.SUPERSESSION_FAILED, null,
                    Map.of(), notSuperseded);
        }
    }

    /**
     * How many registers the write would take, over the two reads whose union is its predicate.
     *
     * @param sharedBefore the bound
     * @return the count
     */
    private int wouldTake(final Instant sharedBefore) {
        final long whileTheFlagWasNotOn = registers.recordedWhileOff().stream()
                .map(RegisterRecord::registerTime)
                .filter(registerTime -> registerTime.isBefore(sharedBefore))
                .count();
        return registers.recordedUnbatchedBefore(sharedBefore).size()
                + (int) whileTheFlagWasNotOn;
    }
}
