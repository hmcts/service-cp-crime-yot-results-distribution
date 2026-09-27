package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.util.UUID;

/**
 * What this service asks systemdocgenerator to render, expressed without naming HTTP.
 *
 * <p>The renderer is given a file-service id and never bytes: {@code generate-document} reads its
 * payload out of the shared framework file service, which is why the payload is inserted there and
 * its id minted first (research §1). A request that named the payload inline would be a different
 * contract with a different failure mode.
 *
 * <p><strong>{@code originatingSource} is this service's own name, and that is what keeps two
 * listeners apart.</strong> progression's leg is still deployed and still subscribed to the same
 * public topic, so every outcome event carries the source that asked for it; a request that borrowed
 * progression's source would have its document announced to progression's listener.
 *
 * @param payloadFileId     the file-service id the payload was inserted under
 * @param batchId           the batch this render is for, sent as {@code sourceCorrelationId} and the
 *                          only thing that correlates the outcome event back to rows
 * @param templateIdentifier the systemdocgenerator template, {@code OEE_Layout5}, unchanged
 * @param conversionFormat  the output format, {@code pdf}
 * @param originatingSource this service's own name, {@code YotResultsDistributionService}
 */
public record RenderRequest(
        UUID payloadFileId,
        UUID batchId,
        String templateIdentifier,
        String conversionFormat,
        String originatingSource) {
}
