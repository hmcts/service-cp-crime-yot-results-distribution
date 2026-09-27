package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.util.UUID;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.PayloadStoreUnavailableException;

/**
 * Where a batch's render payload is put so that systemdocgenerator can read it.
 *
 * <p>The framework file service, written to directly. systemdocgenerator's
 * {@code generate-document} takes a {@code payloadFileServiceId} and renders only what is already in
 * the file service, and its own upload command hides the id it minted, so a caller that used it
 * could not correlate the document it got back (research §1). This service therefore mints the id
 * and writes the two rows itself, and its use of that database is write-only.
 *
 * <p>Nothing here names JDBC, a datasource or a statement. The adapter owns those; what the core
 * knows is that a payload goes somewhere under an id it chose, and that the id is worthless until
 * the write has actually happened.
 */
public interface PayloadFileStore {

    /**
     * Stores one batch's payload under an id the caller has already minted and persisted.
     *
     * <p>The id comes in rather than out because it has to exist on the batch row before the write:
     * an insert that succeeded and an id nobody recorded is a payload that will be rendered into a
     * document this service cannot attribute to anything.
     *
     * @param fileId   the file-service id, minted and persisted before this call
     * @param payload  the render payload, as the mapper produced it
     * @param metadata the metadata row that goes beside it
     * @throws PayloadStoreUnavailableException if the payload is not durably stored, for any reason;
     *     the batch is failed PAYLOAD_STORE_UNAVAILABLE and its rows stay RECORDED for the next run
     */
    void store(UUID fileId, JsonNode payload, PayloadMetadata metadata)
            throws PayloadStoreUnavailableException;

    /**
     * Stores a text file (the exception CSV) under an id the caller has already minted and
     * written down, through the same two inserts in the same order as {@link #store}: content
     * first, because {@code metadata.file_id} is a foreign key onto {@code content.file_id}.
     *
     * @param fileId   the file-service id, minted and persisted before this call
     * @param text     the file's content, which is written as UTF-8
     * @param metadata the metadata row that goes beside it
     * @throws PayloadStoreUnavailableException if the file is not durably stored, for any reason;
     *     the e-mail sink reports ATTACHMENT_STORE_UNAVAILABLE and sends nothing, because an
     *     e-mail whose attachment is not there is an e-mail with nothing attached
     */
    void storeText(UUID fileId, String text, PayloadMetadata metadata)
            throws PayloadStoreUnavailableException;
}
