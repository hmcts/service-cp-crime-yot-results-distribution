package uk.gov.hmcts.cp.yotresultsdistribution.application;

import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GenerationFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RenderRequest;

/**
 * Who turns a stored payload into a PDF.
 *
 * <p>systemdocgenerator, behind a port that names neither HTTP nor a status code. One method,
 * because there is one conversation: asking for a document is a command, and this service asks
 * systemdocgenerator nothing else, on no schedule and at no point in a run (FR-006).
 *
 * <p><strong>Success for the command is {@code 202 Accepted} and nothing else.</strong> The same
 * rule 001 applies to progression, for the same reason: a 2xx that is not 202 means something other
 * than the command endpoint answered, which is a different investigation from a refusal and is never
 * treated as a render that will happen.
 *
 * <p>The request is a command and not a promise. The document arrives later, on the public-event
 * topic, and the batch waits in GENERATING until it does - or until the next run finds it still
 * waiting, gives up on it and gives its registers back.
 */
public interface DocumentRenderer {

    /**
     * Asks systemdocgenerator to render one batch.
     *
     * @param request the payload id, the batch it correlates to, and the template
     * @param caller  who the call is made as
     * @throws GenerationFailedException if the request was not accepted with a 202, carrying whether
     *     another attempt inside the run deadline could change that
     */
    void requestRender(RenderRequest request, CallerIdentity caller)
            throws GenerationFailedException;
}
