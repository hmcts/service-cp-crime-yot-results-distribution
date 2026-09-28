package uk.gov.hmcts.cp.yotresultsdistribution.adapter.stub;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.application.PayloadFileStore;
import uk.gov.hmcts.cp.yotresultsdistribution.application.PayloadMetadata;

/**
 * A payload file store that writes nothing, and says so on every call.
 *
 * <p>For local runs and for the container suites whose subject is the batch state machine rather
 * than the downstream itself: standing a second Postgres up with the framework file service's
 * changesets in it would make what those suites prove depend on infrastructure their scenarios never
 * mention. The compose stack renders through WireMock and a real file-service database when the
 * subject <em>is</em> the downstream; this is for when it is not.
 *
 * <p><strong>It accepts rather than refuses</strong>, which is the choice
 * {@link StubNowSubscriptionsSource} makes and not the one {@link StubRegisterSubmissionClient}
 * makes, so it is worth saying why. A refusal here fails the batch PAYLOAD_STORE_UNAVAILABLE before
 * anything downstream is asked, which means the one path a stubbed run could exercise is the path
 * where the run does nothing - and the state machine these suites are about would never leave its
 * first state. Accepting claims nothing to anybody: no e-mail is sent, no document is invented, and
 * the batch's next step is a render request whose own stub declines to produce a document. Startup
 * refuses this mode wherever the deployed credential source is in use, so it can never be the thing
 * a deployed pod is quietly running (constitution Principle V).
 *
 * <p>It logs at INFO on every invocation, because a stub that is quiet is a stub somebody will
 * mistake for the real thing. The payload itself is never logged and neither is the file name: both
 * are a register about children, and the metadata's bounded fields say enough to follow a run.
 */
public class StubPayloadFileStore implements PayloadFileStore {

    private static final Logger LOG = LoggerFactory.getLogger(StubPayloadFileStore.class);

    @Override
    public void store(
            final UUID fileId, final JsonNode payload, final PayloadMetadata metadata) {
        LOG.info("STUB payload file store invoked: nothing is written to the file service, and the "
                        + "id is minted against no row. fileId={} template={} format={} "
                        + "fileSize={}",
                fileId, metadata.templateName(), metadata.conversionFormat(), metadata.fileSize());
    }

    /**
     * The same no-op {@link #store} is, for the exception report's CSV.
     *
     * <p>It says the same thing in the same shape: nothing was written, and under which id. A
     * context on the stub profile can therefore compose a report e-mail end to end without a
     * refusal from a store nobody asked to be real - and what it sends carries a file id the file
     * service has nothing under, which is exactly what the line says out loud.
     *
     * <p>The CSV itself is never logged. It is a list of identifiers about a register whose every
     * defendant is a child, and its size says as much about a run as its contents would.
     *
     * @param fileId   the id the caller minted and wrote down before asking
     * @param text     the CSV
     * @param metadata what the file service would have been told about it
     */
    @Override
    public void storeText(final UUID fileId, final String text, final PayloadMetadata metadata) {
        LOG.info("STUB payload file store invoked for text: nothing is written to the file "
                        + "service, and the id is minted against no row. fileId={} template={} "
                        + "format={} fileSize={}",
                fileId, metadata.templateName(), metadata.conversionFormat(), metadata.fileSize());
    }
}
