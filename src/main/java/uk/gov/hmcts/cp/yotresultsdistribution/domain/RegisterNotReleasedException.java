package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * Thrown when the store refused a stale batch's release for a reason the operation cannot account
 * for.
 *
 * <p>The stale-batch pass settles exactly one refusal itself - the race for the day's
 * active-register key, which it makes its statement against again on a fresh snapshot and, where
 * every attempt meets it, reports as a contended batch rather than throwing. Any other unique key
 * the release meets is a rule nobody wrote this statement against: the same row meets the same rule
 * on every attempt and on every run, so no snapshot and no retry changes it, and it is raised
 * rather than reported. It is a fault in the schema or in this statement, not a race, and it is
 * allowed to end the run the way any programming error is - what FR-003a forbids is one batch's
 * <em>ordinary</em> ending taking the other court centres' releases with it.
 *
 * <p><strong>Why the store's own class does not travel.</strong> The pass that calls the port lives
 * in {@code batch/}, which may name no {@code org.springframework.dao} type (constitution Principle
 * V), so a refusal that escaped untranslated could only be caught there as {@code RuntimeException}
 * - the catch that swallows every programming error beside it. This is the same translation
 * {@link StoreUnavailableException} and {@link StoreRefusedRowException} make at the same boundary.
 *
 * <p><strong>It is not a store outage.</strong> {@link StoreUnavailableException} says the store
 * could not be reached; this says the store was reached and declined to hold this row.
 *
 * <p><strong>The message is this repository's own words and the store's refusal is deliberately not
 * attached</strong>, which is the same choice {@link StoreRefusedRowException} makes and for a
 * sharper version of the same reason. The statement this refusal comes out of writes
 * {@code processed_output}, and a {@code processed_output} row holds the register document itself -
 * so Postgres reporting the refusal by quoting the row ({@code Failing row contains (...)} for a
 * CHECK, {@code Key (...)=(...) already exists} for a unique index) puts a youth defendant's name
 * and date of birth on the driver's message. A cause is how that message reaches a stack trace, and
 * this failure is raised out of the nightly run and written at ERROR, which is an estate-wide log
 * index (constitution Principle VII).
 *
 * <p>What a reader is given instead is bounded and written here: which <em>kind</em> of rule
 * refused the write - a unique key, or an integrity rule that is no key at all - and the batch's
 * own identity. That is the whole of what the pass or an on-call engineer can act on; which
 * constraint it was is in the store's own log, where the row it quotes belongs.
 */
public class RegisterNotReleasedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the failure.
     *
     * @param detail a bounded description of the refusal - the kind of rule and the batch, never
     *               the driver's words and never a value from a row
     */
    public RegisterNotReleasedException(final String detail) {
        super(detail);
    }
}
