package uk.gov.hmcts.cp.yotresultsdistribution.api.dto;

/**
 * What {@code POST /operations/batches/generate} takes.
 *
 * <p>{@code date} is required and is <strong>never defaulted</strong>: a regeneration that quietly
 * chose its own day would e-mail a Youth Offending Team about a day nobody asked about. The three
 * values that have to be parsed are carried as strings rather than bound to their types, so that a
 * value which will not read is this service's own {@code 400 unreadable-argument} naming the
 * argument, instead of a framework message quoting back the characters the caller typed
 * (FR-024, constitution Principle VII).
 *
 * <p>{@code ignoreFlag} is the break-glass {@code --ignore-flag} was, and it is admitted
 * <strong>only</strong> beside a {@code batchId} (design owner, 2026-09-19): a break-glass over a
 * whole register date is not the one the command had.
 *
 * @param date           the register date, as an ISO local date
 * @param courtHouse     one court house of that date, or absent for all of them
 * @param batchId        one batch of that date, or absent for all of them
 * @param recordedBefore the exclusive bound on the registers' shared instant, as an ISO instant
 * @param ignoreFlag     whether to proceed over a flag that does not say ON
 */
public record GenerateRegisterRequest(String date, String courtHouse, String batchId,
                                      String recordedBefore, Boolean ignoreFlag) {

    /** The request a call with no body at all is read as, which the missing argument refuses. */
    public static final GenerateRegisterRequest NOTHING =
            new GenerateRegisterRequest(null, null, null, null, null);

    /**
     * Whether an override was asked for, with an absent field read as "no".
     *
     * @return {@code true} only where the caller asked for one
     */
    public boolean overrideAsked() {
        return Boolean.TRUE.equals(ignoreFlag);
    }
}
