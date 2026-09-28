package uk.gov.hmcts.cp.yotresultsdistribution.application;

import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.PayloadUnavailableException;

/**
 * Where the hearing payload for a request comes from.
 *
 * <p>The first of the four ports the core owns. The adapter behind it is the Redis claim-check with
 * the results-query fallback; nothing here names a cache, a client or a transport, which is the
 * whole point — swapping the adapter must not reopen the pipeline (constitution Principle V).
 *
 * <p>The payload is returned as a canonical tree rather than a bound model: it is large, sparsely
 * populated and owned elsewhere, and binding it would silently discard every field this service does
 * not know about (constitution Principle IV).
 */
public interface HearingPayloadSource {

    /**
     * Obtains the hearing payload the request refers to.
     *
     * @param command the validated request
     * @return the payload, as a canonical tree
     * @throws PayloadUnavailableException if the payload cannot be obtained; transient where another
     *     delivery could answer, and non-transient where the query side understood the read and
     *     declined it
     */
    JsonNode fetch(DistributionCommand command) throws PayloadUnavailableException;
}
