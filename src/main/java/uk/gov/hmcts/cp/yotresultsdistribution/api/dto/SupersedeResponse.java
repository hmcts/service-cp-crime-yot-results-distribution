package uk.gov.hmcts.cp.yotresultsdistribution.api.dto;

import java.time.Instant;

/**
 * What {@code POST /operations/registers/supersede} answers.
 *
 * <p>Three bounded fields: a count, this service's own parse of the bound it was given, and
 * whether anything was written. The instant is the parsed value rather than the characters the
 * caller sent, so nothing here is an echo (FR-024).
 *
 * <p>The count goes into the audit event as well as this body, because it is the whole of what a
 * rollback did and an audit trail that recorded only that one was attempted would not say whether
 * anything was given up.
 *
 * @param superseded   how many registers were given up, or would have been on a dry run
 * @param sharedBefore the bound the rollback was taken at
 * @param dryRun       whether anything was actually superseded
 */
public record SupersedeResponse(int superseded, Instant sharedBefore, boolean dryRun) {
}
