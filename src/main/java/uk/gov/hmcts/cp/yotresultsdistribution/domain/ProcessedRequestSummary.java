package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One request as the exception report reads it: exactly the columns one statement selects.
 *
 * <p>A projection and deliberately not {@link ProcessedRequestRecord}, which is the branch
 * decision's view of the same row and carries a claim and a fingerprint the report has no business
 * with. What this carries instead is {@link #ageSeconds()}, and the age is the whole reason it
 * exists: it is computed by the database in the same statement that selects the row, so no reading
 * of a JVM clock is ever subtracted from a stored timestamp and two pods reading one row agree.
 *
 * <p>Which moment the age is measured from depends on the question. A request that was parked is
 * aged from {@code updated_at}, the moment it reached FAILED; a request that is still in flight is
 * aged from {@code created_at}, the moment it arrived - which is what "stuck for three days" means
 * to the support engineer reading it.
 *
 * @param source        the producing context
 * @param requestId     the request's identity, which with the source is its key
 * @param hearingId     the hearing the request is about
 * @param hearingDay    the hearing day the request named
 * @param status        the state the row is in, which is FAILED, RECEIVED or RETRYING here
 * @param attempts      the lifetime tally of deliveries this request has had
 * @param failureReason the bounded reason the row was parked, or {@code null} where it is not
 * @param createdAt     when the request arrived
 * @param updatedAt     when the row last moved
 * @param ageSeconds    how old the row is, measured by the database from the column the read asked
 *                      about
 */
public record ProcessedRequestSummary(
        String source,
        UUID requestId,
        UUID hearingId,
        LocalDate hearingDay,
        RequestStatus status,
        int attempts,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        long ageSeconds) {
}
