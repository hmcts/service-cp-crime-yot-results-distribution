package uk.gov.hmcts.cp.yotresultsdistribution.adapter.stub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterSubmission;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterSubmissionClient;
import uk.gov.hmcts.cp.yotresultsdistribution.application.SubmissionReceipt;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.SubmissionFailedException;

/**
 * A submission client that posts nothing, and refuses rather than pretending.
 *
 * <p>It exists because the pipeline is wired in full: the run needs somewhere to send a register
 * even in the configurations that can never build one. It is contributed only alongside the stub
 * payload source, which is the same condition {@code PropertiesValidator} already uses to decide
 * that progression need not be reachable — "a local stub run never fetches a hearing, so it never
 * reaches the POST at all" — and startup refuses that stub wherever the deployed credential source
 * is in use.
 *
 * <p><strong>A refusal, never a receipt.</strong> If a register ever did reach it, answering
 * {@code 202} would record a delivered register that nothing received: the silent-success failure
 * mode this whole service was commissioned to end (C1, C33). So it throws, non-transiently, with a
 * bounded reason, and the run is recorded FAILED and dead-lettered where somebody can see that a pod
 * was configured to send nowhere.
 */
public class StubRegisterSubmissionClient implements RegisterSubmissionClient {

    private static final Logger LOG = LoggerFactory.getLogger(StubRegisterSubmissionClient.class);

    @Override
    public SubmissionReceipt submit(final RegisterSubmission submission) {
        LOG.error("STUB submission client invoked: this pod has nowhere to post a register, so the "
                + "register is not sent and the run is failed. hearingId={} anomalies={}",
                submission.document().hearingId(), submission.anomalies().size());
        throw new SubmissionFailedException(
                FailureClassification.NON_TRANSIENT, ReasonCode.SUBMISSION_NOT_ACCEPTED);
    }
}
