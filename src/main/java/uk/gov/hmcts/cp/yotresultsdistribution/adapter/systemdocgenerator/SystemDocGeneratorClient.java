package uk.gov.hmcts.cp.yotresultsdistribution.adapter.systemdocgenerator;

import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.yotresultsdistribution.application.DocumentRenderer;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GenerationFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RenderRequest;

/**
 * The renderer port, wired to systemdocgenerator's command API.
 *
 * <p>One conversation over one {@code RestClient}, carrying the caller identity as
 * {@code CJSCPPUID} and using the shared {@code adapter/http/RetryPolicy}, so this client cannot
 * hold a different opinion about what is worth asking again than the two clients 001 already built
 * (defect fix C3). The service asks systemdocgenerator for a render and learns what became of it
 * from the public events; it asks it nothing else.
 *
 * <p>The command is {@code POST {SDG}/systemdocgenerator-command-api/command/api/rest/
 * systemdocgenerator/generate-document} with media type
 * {@code application/vnd.systemdocgenerator.generate-document+json} and a body of exactly five
 * fields: {@code templateIdentifier} OEE_Layout5, {@code conversionFormat} pdf, the
 * {@code payloadFileServiceId} this service minted, {@code sourceCorrelationId} the batch id, and
 * {@code originatingSource} YotResultsDistributionService. The last of those is what keeps progression's
 * still-deployed listener out of this service's documents, and the one before it is the only thing
 * that correlates an outcome event back to rows. The five are the request's own, passed through as
 * {@link RenderRequest} carries them: the vendored schema is
 * {@code additionalProperties: false}, so a sixth field this adapter added of its own would be a 400
 * rather than a field systemdocgenerator ignored.
 *
 * <p><strong>202 and nothing else is success.</strong> A 2xx that is not 202 means something other
 * than the command endpoint answered, which is a different investigation from a refusal and is never
 * treated as a render that will happen; it is RENDER_REQUEST_REJECTED and the batch fails.
 *
 * <p><strong>One attempt per call, classified and handed back.</strong> The taxonomy is the shared
 * one - 408, 429 and every 5xx are worth asking again, any other 4xx is a refusal - but the waiting
 * and the counting are not this class's, because the bound on them is the run deadline and this
 * class is not told what is left of it: {@code DocumentRenderer.requestRender} takes a request and a
 * caller and nothing else. {@code application/RegisterGenerationService} holds the deadline and
 * therefore holds the loop, and a client that retried underneath it would spend a budget it cannot
 * see.
 *
 * <p>Nothing systemdocgenerator wrote is carried out of this class in a log line. The failure that
 * leaves it names a bounded {@link BatchFailureReason} and a status, and nothing of another
 * system's free text about a document whose every defendant is a child (constitution Principle
 * VII). The refusal this service does read systemdocgenerator's own words out of arrives on the
 * public-event topic, where {@code DocumentEventListener} carries them to {@code sdg_reason}
 * without writing them to any log index.
 */
public class SystemDocGeneratorClient implements DocumentRenderer {

    /** The command's path under the systemdocgenerator context, exactly as its RAML declares it. */
    public static final String COMMAND_PATH =
            "/systemdocgenerator-command-api/command/api/rest/systemdocgenerator/generate-document";

    /** The command's vendor media type; the framework routes on it, so it is not a formality. */
    public static final String GENERATE_DOCUMENT_MEDIA_TYPE =
            "application/vnd.systemdocgenerator.generate-document+json";

    /**
     * The CPP identity header. Its value is never logged - it is either a secret or a user
     * identifier, and neither belongs in a log index.
     */
    public static final String IDENTITY_HEADER = "CJSCPPUID";

    /** The one status the contract calls success. */
    public static final int ACCEPTED = 202;

    private static final Logger LOG = LoggerFactory.getLogger(SystemDocGeneratorClient.class);

    /** The client, carrying the systemdocgenerator base URL and its timeouts. */
    private final RestClient restClient;

    /** The {@code CJSCPPUID} identity for a run naming no user; a secret, never logged. */
    private final String systemUserId;

    /** The shared mapper, so a body is written and an answer read exactly as any other JSON is. */
    private final ObjectMapper objectMapper;

    /**
     * Builds the client over an already-configured HTTP client.
     *
     * @param restClient   the client, carrying the systemdocgenerator base URL and its timeouts
     * @param systemUserId the {@code CJSCPPUID} identity for a run naming no user; a secret, never
     *                     logged
     * @param objectMapper the shared mapper, so the command body is written exactly as every other
     *                     JSON in this service is
     */
    public SystemDocGeneratorClient(final RestClient restClient, final String systemUserId,
            final ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.systemUserId = systemUserId;
        this.objectMapper = objectMapper;
    }

    @Override
    public void requestRender(final RenderRequest request, final CallerIdentity caller)
            throws GenerationFailedException {
        // Resolved once, exactly as the three clients 001 built resolve it: the run's user where the
        // message named one, and the configured identity otherwise.
        final String identity = caller.orSystem(systemUserId);
        final byte[] body = objectMapper.writeValueAsBytes(new GenerateDocument(
                request.templateIdentifier(),
                request.conversionFormat(),
                request.payloadFileId(),
                request.batchId(),
                request.originatingSource()));
        try {
            restClient.post()
                    .uri(COMMAND_PATH)
                    .headers(headers -> {
                        headers.setContentType(
                                MediaType.parseMediaType(GENERATE_DOCUMENT_MEDIA_TYPE));
                        headers.set(IDENTITY_HEADER, identity);
                    })
                    .body(body)
                    .exchange((sent, answer) -> classify(answer, request.batchId()));
        } catch (ResourceAccessException unreachable) {
            // Connect failure, read timeout, connection dropped: the request may or may not have
            // reached systemdocgenerator. Unknown is not refused, so it is handed back for the run
            // to ask again inside its deadline, and it carries no status - an invented one would
            // say an attempt was answered when nothing answered.
            //
            // Only the exception's type travels with the line. What the run acts on is the
            // classification; what a human acts on is what it *was*, and the class carries that
            // much where the bounded reason code cannot. The message does not go with it: it
            // belongs to whatever raised it, and a line carries this service's own words
            // (Principle VII), which the sweep in TelemetryPrivacyTest now holds by
            // construction.
            LOG.warn("The generate-document request reached no verdict, so whether the render was "
                    + "asked for is unknown. batchId={} cause={}", request.batchId(),
                    unreachable.getClass().getName());
            throw new GenerationFailedException(
                    FailureClassification.TRANSIENT, BatchFailureReason.RENDER_REQUEST_FAILED);
        }
    }

    /**
     * What systemdocgenerator's answer to the command means.
     *
     * <p>Nothing it wrote is read: the body of a refusal describes a document whose every defendant
     * is a child, and every decision here travels into a log line and a batch row.
     */
    private static int classify(final ClientHttpResponse response, final UUID batchId)
            throws IOException {
        final HttpStatusCode statusCode = response.getStatusCode();
        final int status = statusCode.value();
        if (status != ACCEPTED) {
            if (statusCode.is2xxSuccessful()) {
                // The contract declares one success. A 200 or a 204 means something other than the
                // command endpoint answered - a proxy, or a route that no longer reaches it - and
                // calling it success would move the batch to GENERATING for a render nothing was
                // asked for, to sit there until the next run released it, for an event that
                // cannot come.
                LOG.error("systemdocgenerator answered a success this contract does not define, so "
                        + "no render can be assumed. batchId={} status={}", batchId, status);
                throw rejected(status);
            }
            if (RetryPolicy.retryable(status)) {
                // 408, 429 and every server error, from the one policy all this service's clients
                // hold (C3). The asking again is the run's, bounded by the deadline this client is
                // not told about.
                LOG.warn("systemdocgenerator could not take the render request, so the run may ask "
                        + "again inside its deadline. batchId={} status={}", batchId, status);
                throw new GenerationFailedException(FailureClassification.TRANSIENT,
                        BatchFailureReason.RENDER_REQUEST_FAILED, status);
            }
            // Any other 4xx: the request was understood and declined, and the same request will be
            // declined again.
            LOG.error("systemdocgenerator refused the render request, and asking again cannot "
                    + "change that. batchId={} status={}", batchId, status);
            throw rejected(status);
        }
        return status;
    }

    /** A refusal no further attempt can change, carrying the status that made it one. */
    private static GenerationFailedException rejected(final int status) {
        return new GenerationFailedException(FailureClassification.NON_TRANSIENT,
                BatchFailureReason.RENDER_REQUEST_REJECTED, status);
    }

    /**
     * The command body: the five fields the contract declares, and no sixth.
     *
     * @param templateIdentifier  the systemdocgenerator template, {@code OEE_Layout5}, unchanged
     * @param conversionFormat    the output format, {@code pdf}
     * @param payloadFileServiceId the file-service id this service minted and inserted the payload
     *                            under
     * @param sourceCorrelationId the batch id, and the only thing that correlates the outcome event
     *                            back to rows
     * @param originatingSource   this service's own name, which keeps progression's still-deployed
     *                            listener out of these documents
     */
    private record GenerateDocument(
            String templateIdentifier,
            String conversionFormat,
            UUID payloadFileServiceId,
            UUID sourceCorrelationId,
            String originatingSource) {
    }
}
