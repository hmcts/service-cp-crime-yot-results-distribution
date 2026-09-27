package uk.gov.hmcts.cp.yotresultsdistribution.adapter.stub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.yotresultsdistribution.application.FeatureFlagReader;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision;

/**
 * A flag reader that asks App Configuration nothing, and answers the same thing every run.
 *
 * <p>For local runs and for the container suites whose subject is the batch state machine rather
 * than the cutover: standing an App Configuration store up for those would make what they prove
 * depend on infrastructure their scenarios never mention, and there is no emulator for it.
 *
 * <p><strong>It answers ON by default, which is the opposite of the live reader's
 * disposition.</strong> The live reader fails closed because an unreadable store must never be
 * mistaken for a cutover; this one has no store to fail against, and a stub that answered OFF would
 * skip every local run before it began and leave the job untestable without one. What makes an
 * invented ON safe is not the stub's own caution but the startup rules around it: this mode is
 * refused wherever the deployed credential source is in use, so the pod that decides a cutover is
 * never the pod that invented its answer (constitution Cutover Rule, Principle V).
 *
 * <p>The answer is configuration read once at construction, never a field in a message and never an
 * HTTP endpoint, either of which would let something outside the deployment decide whether a night's
 * registers are generated. Every other answer the reader can give is reachable through it, so the
 * skip paths can be exercised deliberately rather than only by taking a store away.
 *
 * <p>It logs at INFO on every read, because a stub that is quiet is a stub somebody will mistake for
 * the real thing - and this one is the stub whose answer decides whether a night runs at all.
 */
public class StubFeatureFlagReader implements FeatureFlagReader {

    private static final Logger LOG = LoggerFactory.getLogger(StubFeatureFlagReader.class);

    private final FlagDecision decision;

    /**
     * Fixes the answer this reader gives for the life of the context.
     *
     * @param configuredDecision the decision the configured answer maps to; never {@code null}
     */
    public StubFeatureFlagReader(final FlagDecision configuredDecision) {
        this.decision = configuredDecision;
    }

    @Override
    public FlagDecision read() {
        LOG.info("STUB feature-flag reader invoked: App Configuration is not asked, and the "
                + "configured answer is returned. decision={}", decision.code());
        return decision;
    }
}
