package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.time.Instant;

/**
 * What one rollback of a period did, or would have done.
 *
 * @param superseded   how many registers were given up, or would have been on a dry run
 * @param sharedBefore the exclusive upper bound the caller named, never defaulted
 * @param dryRun       whether anything was actually superseded
 */
public record Supersession(int superseded, Instant sharedBefore, boolean dryRun) {
}
