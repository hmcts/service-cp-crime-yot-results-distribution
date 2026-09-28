package uk.gov.hmcts.cp.yotresultsdistribution.api.dto;

/**
 * What {@code POST /operations/registers/supersede} takes.
 *
 * <p>{@code sharedBefore} is required and is <strong>never defaulted</strong> (FR-021): a rollback
 * that quietly chose its own period would give up registers nobody decided about. It is carried as
 * a string rather than bound to an {@code Instant} so that a value which will not read is this
 * service's own {@code 400 unreadable-argument} naming the argument, instead of a framework
 * message quoting back the characters the caller typed.
 *
 * @param sharedBefore the exclusive upper bound on the registers' shared instant, as an ISO instant
 * @param dryRun       whether to answer the count and supersede nothing
 */
public record SupersedeRequest(String sharedBefore, Boolean dryRun) {

    /** The request a call with no body at all is read as, which the missing argument refuses. */
    public static final SupersedeRequest NOTHING = new SupersedeRequest(null, null);

    /**
     * Whether a dry run was asked for, with an absent field read as "no".
     *
     * @return {@code true} only where the caller asked for one
     */
    public boolean dryRunAsked() {
        return Boolean.TRUE.equals(dryRun);
    }
}
