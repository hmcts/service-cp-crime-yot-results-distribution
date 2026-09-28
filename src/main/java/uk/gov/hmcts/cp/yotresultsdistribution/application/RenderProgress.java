package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.util.UUID;

/**
 * What the requesting leg tells its caller as it asks systemdocgenerator for a render.
 *
 * <p>One fact, and it exists because the run report depends on it. A night's {@code requested}
 * count is the batches this service sent the renderer, and {@link BatchOutcome#renderRequested()},
 * the other thing that says so, is only returned once the batch's ending has been written down.
 * The write that follows an answered call can fail: a store that will not take the mark leaves the
 * requesting leg through a throw, and a run counting outcomes alone would then report a night that
 * sent nothing while a render was away and a document was on its way back to it.
 *
 * <p><strong>So the call itself is announced, where it is made and before anything is concluded
 * about it.</strong> The two informants name the same batch and are reconciled by that identity,
 * so a batch announced here and counted from its outcome is one request. A batch asked for three
 * times inside the retry budget is one request for the same reason: the count is of documents the
 * renderer was sent rather than of times it was called, and this is told once per batch, at the
 * first attempt.
 *
 * <p>An interface rather than the run itself, so the requesting leg depends on the sentence "the
 * renderer has been asked about this batch" rather than on the run's own accounting, which is a
 * report's business and not a batch's.
 */
public interface RenderProgress {

    /**
     * A caller that counts no renders, for one with no report to make of them.
     *
     * <p>The operations command and the suites that ask one batch about itself: neither writes a
     * run report, and a requesting leg that insisted on somebody to tell would make a caller invent
     * an accounting it has no use for.
     */
    RenderProgress NONE = new RenderProgress() {

        @Override
        public void recordRenderAsked(final UUID batchId) {
            // Nothing here counts what the renderer was sent, so there is nothing to record.
        }
    };

    /**
     * Records that systemdocgenerator has been asked to render the named batch.
     *
     * @param batchId the batch whose render was asked for, which is what makes a retried request
     *                one request rather than three
     */
    void recordRenderAsked(UUID batchId);
}
