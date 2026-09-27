package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One batch the exception report has something to say about, as the four batch reads answer it.
 *
 * <p>A projection and deliberately not {@link RegisterBatch}, which carries five timestamps and no
 * age. The age is computed by the database in the same statement that selects the row, from
 * whichever stage timestamp the read is about - {@code assembled_at} for a batch still awaiting a
 * render, {@code requested_at} for one whose answer has not come back, {@code generated_at} for one
 * holding a document nobody was told about, {@code failed_at} for one that ended. Deriving that in
 * the JVM from a stored timestamp is the cross-clock comparison V1 forbids, and it would leave the
 * report with two kinds of age.
 *
 * <p><strong>There is no {@code sdgReason} component at all</strong>, so there is nowhere for
 * another system's free text to be put even by accident. {@code register_batch.sdg_reason} is
 * systemdocgenerator's own words about somebody's document, and constitution Principle VII keeps
 * free text this service did not write out of a line, a label, an event and the CSV alike. What a
 * failed batch reports is {@link #failureReason()}, which is bounded by its enumeration and has a
 * fixed meaning a support engineer can paste into a ticket.
 *
 * @param batchId       the batch's identity, which every outcome correlates on
 * @param courtCentreId the court centre whose day the batch is, as a UUID like {@link RegisterBatch}
 * @param registerDate  the register day the batch is for
 * @param status        the state the batch is in
 * @param failureReason the bounded reason it ended, or {@code null} where it has not
 * @param attempts      the lifetime tally of generation attempts
 * @param ageSeconds    how long it has been in that state, measured by the database from the stage
 *                      timestamp the read asked about
 */
public record BatchException(
        UUID batchId,
        UUID courtCentreId,
        LocalDate registerDate,
        BatchStatus status,
        BatchFailureReason failureReason,
        int attempts,
        long ageSeconds) {
}
