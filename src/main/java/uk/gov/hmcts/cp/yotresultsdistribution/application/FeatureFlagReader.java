package uk.gov.hmcts.cp.yotresultsdistribution.application;

import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision;

/**
 * Who reads the one lever, {@code YotResultsDistributionService}.
 *
 * <p>This service is the flag's third reader, after the producer and the legacy, and it reads the
 * same key those two read - which is what makes it one lever rather than three (constitution Cutover
 * Rule). The nightly job asks once per run, with no cache: a flag that was on an hour ago says
 * nothing about a cutover that was rolled back ten minutes ago.
 *
 * <p><strong>It never throws.</strong> Every failure is a {@link FlagDecision.Unreadable} carrying a
 * bounded cause, so an App Configuration outage skips the run and is counted rather than crashing
 * the job and leaving the night indistinguishable from a scheduler that never fired. Unreadable is
 * treated exactly as off: the legacy is presumed to be generating, and generating twice is the one
 * failure that reaches a child's family.
 *
 * <p>Nothing here names Azure, a credential or an SDK client. The adapter owns those.
 */
public interface FeatureFlagReader {

    /**
     * Reads the flag now.
     *
     * @return what the flag says, or why it could not be read; never {@code null}
     */
    FlagDecision read();
}
