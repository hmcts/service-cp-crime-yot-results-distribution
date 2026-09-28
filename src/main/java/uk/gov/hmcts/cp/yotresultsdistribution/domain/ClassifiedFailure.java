package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * A failure that already knows how the delivery carrying it should be settled.
 *
 * <p>The pipeline asks two questions of every failure — is it worth retrying, and is there a retry
 * left — and only the second is its own. The first belongs to the layer that met the failure: a
 * cache and fallback that both missed may have a payload next time; a document progression refused
 * as malformed will be refused identically for ever. This interface is that answer, carried on the
 * exception, so the core branches on a classification the throw site chose rather than on the
 * exception's Java type.
 *
 * <p>{@link TransformationFailedException} fixes its classification — every way a transformation can
 * fail reads the same on every delivery. The other three take it from the throw site, because the
 * same call can fail both ways: a query side, a reference-data context or a progression endpoint
 * that could not be reached may answer next time, and one that understood the request and declined
 * it will not.
 *
 * <p><strong>The reason is a bounded code</strong>, never free text and never a fragment of a
 * payload: it travels into {@code processed_request.failure_reason}, a dead-letter description and
 * the estate's log index (constitution Principle VII).
 */
public interface ClassifiedFailure {

    /**
     * Whether another delivery could change the answer.
     *
     * @return the classification the throw site chose
     */
    FailureClassification classification();

    /**
     * The bounded reason recorded for this failure.
     *
     * @return the reason code
     */
    ReasonCode reason();
}
