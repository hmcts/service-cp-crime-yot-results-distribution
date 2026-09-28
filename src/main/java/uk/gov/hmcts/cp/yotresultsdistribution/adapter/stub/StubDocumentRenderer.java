package uk.gov.hmcts.cp.yotresultsdistribution.adapter.stub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.yotresultsdistribution.application.DocumentRenderer;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RenderRequest;

/**
 * A renderer that asks systemdocgenerator nothing, and never invents a document.
 *
 * <p>For local runs and for the container suites whose subject is the batch state machine rather
 * than the downstream itself. The compose stack answers with WireMock when the subject <em>is</em>
 * systemdocgenerator's contract; this is for the suites that want a batch to move without one.
 *
 * <p><strong>The request is accepted and no document follows, and the pair is deliberate.</strong>
 * Accepting the request lets the batch reach GENERATING, which is the state the assembler, the
 * payload leg and the run deadline are all about, and it claims nothing: GENERATING says a render
 * was asked for, not that a document exists. Minting a document id would be the other thing
 * entirely - a GENERATED batch carrying a document nobody rendered, which is the silent success
 * this service was commissioned to end (C1, C33), and which a stubbed file-service leg would then
 * attach to a real e-mail if notification were left live. So nothing is invented, no outcome ever
 * arrives on the topic for such a batch, and the next run finds it still waiting and gives its
 * registers back - loudly, with a bounded reason, where somebody can see that this pod renders
 * nothing.
 *
 * <p>The call logs at INFO, because a stub that is quiet is a stub somebody will mistake for the
 * real thing. Startup refuses this mode wherever the deployed credential source is in use
 * (constitution Principle V).
 */
public class StubDocumentRenderer implements DocumentRenderer {

    private static final Logger LOG = LoggerFactory.getLogger(StubDocumentRenderer.class);

    @Override
    public void requestRender(final RenderRequest request, final CallerIdentity caller) {
        LOG.info("STUB document renderer invoked: no render is requested and no document will "
                        + "follow. batchId={} payloadFileId={} template={} format={} source={}",
                request.batchId(), request.payloadFileId(), request.templateIdentifier(),
                request.conversionFormat(), request.originatingSource());
    }
}
