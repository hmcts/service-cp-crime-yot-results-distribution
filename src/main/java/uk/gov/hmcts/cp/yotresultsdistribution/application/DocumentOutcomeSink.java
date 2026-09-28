package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.time.Instant;
import java.util.UUID;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletedBy;

/**
 * Where a rendering outcome is applied, whoever learned it.
 *
 * <p>One port and, since 004, one driver: the {@code public.event} listener, which is how every
 * outcome arrives. The outcome is applied through here rather than by the listener writing the
 * store itself, so there is exactly one place that decides what an outcome does to a batch and its
 * rows - and exactly one place a duplicate has to be absorbed, which it will be: a durable
 * subscription redelivers, and an outcome can arrive for a batch the run has already given up on
 * and released.
 *
 * <p>Nothing here names JMS, a message or an envelope. The listener parses those and calls this with
 * the four facts an outcome is: which batch, which payload, what happened, and when.
 *
 * <p><strong>And who learned it.</strong> The caller names itself - the listener EVENT - because
 * the store writes {@code completed_by} in the same statement that moves the batch, and a
 * compare-and-set leaves no second moment to write it in. It is a parameter rather than something
 * the implementation infers from which class called it, and it stays one: the column exists to say
 * which mechanism learned an outcome, and a mechanism is exactly the kind of thing that comes
 * back.
 */
public interface DocumentOutcomeSink {

    /**
     * Applies a document that was generated.
     *
     * <p>Scoped to the batch it names and never widened to the court centre - the sink is where
     * defect P3 would be reintroduced if anything here reached for the batch's key instead of its
     * identity.
     *
     * @param correlationId  the batch identity systemdocgenerator was given as
     *                       {@code sourceCorrelationId}
     * @param payloadFileId  the payload the document was rendered from
     * @param documentFileId the rendered document's file-service id
     * @param generatedAt    when it was generated
     * @param completedBy    the caller naming itself, which is EVENT from the listener
     */
    void documentAvailable(UUID correlationId, UUID payloadFileId, UUID documentFileId,
            Instant generatedAt, CompletedBy completedBy);

    /**
     * Applies a generation that failed.
     *
     * @param correlationId the batch identity systemdocgenerator was given as
     *                      {@code sourceCorrelationId}
     * @param payloadFileId the payload the render was requested for
     * @param reason        systemdocgenerator's own words, kept for support and never logged at
     *                      INFO; the batch's own reason is the bounded GENERATION_FAILED
     * @param failedAt      when the generation failed
     * @param completedBy   the caller naming itself, which is EVENT from the listener
     */
    void generationFailed(UUID correlationId, UUID payloadFileId, String reason, Instant failedAt,
            CompletedBy completedBy);
}
