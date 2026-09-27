package uk.gov.hmcts.cp.yotresultsdistribution.adapter.fileservice;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.yotresultsdistribution.application.PayloadFileStore;
import uk.gov.hmcts.cp.yotresultsdistribution.application.PayloadMetadata;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.PayloadStoreUnavailableException;

/**
 * The payload store, wired to the framework file service's own database.
 *
 * <p>Two inserts and nothing else, over the second datasource
 * ({@code FileServiceDataSourceConfig.JDBC_CLIENT}, the {@code fileServiceJdbcClient} bean, which is
 * reached by name because a second {@code JdbcClient} found by type would take the processed log's
 * away). They are the statements the framework's own repositories issue, character for character:
 *
 * <pre>{@code
 * INSERT INTO metadata(metadata, file_id) VALUES (to_json(?::json), ?);
 * INSERT INTO content(file_id, content, deleted) VALUES (?, ?, false);
 * }</pre>
 *
 * <p>The metadata is progression's five keys spelled progression's way. This is not this service's
 * database and systemdocgenerator is not this service's reader, so a sixth key or a different
 * spelling would be a change to somebody else's contract made without asking, and
 * {@code FileServicePayloadStoreIT} asserts that no other statement is issued at all.
 *
 * <p><strong>Content is written first.</strong> {@code metadata.file_id} is a foreign key onto
 * {@code content.file_id}, so the order is the schema's requirement and not a preference. The two
 * are issued as two statements under autocommit rather than inside a transaction, because this
 * class is handed a {@code JdbcClient} and nothing else: a transaction over somebody else's pool
 * would need that pool's own transaction manager, and taking the context's would open and commit
 * the boundary on the processed log's datasource instead. A metadata write that fails behind a
 * content write that succeeded therefore leaves one orphan {@code content} row in the file service,
 * carrying an id no batch is now waiting on - the batch fails PAYLOAD_STORE_UNAVAILABLE and the
 * next run mints a fresh id - and nothing reads a {@code content} row that no {@code metadata} row
 * names.
 *
 * <p>The parameters are positional rather than named because the statements above are the
 * assertion: a named parameter is rewritten before the driver sees it, and what would then reach
 * the file service is Spring's spelling of the framework's statement rather than the framework's.
 *
 * <p><strong>Anything that leaves the payload not durably stored becomes
 * {@link PayloadStoreUnavailableException}</strong>: the batch fails PAYLOAD_STORE_UNAVAILABLE and
 * its rows stay RECORDED, so the next run re-assembles them rather than a night's registers being
 * lost to a database that was briefly away. The translation is written here rather than taken from
 * {@code persistence/StoreOutage}: that one is package-private to the package that owns the
 * processed log's datasource and it answers with {@code StoreUnavailableException}, which is the
 * intake half's signal and stops the queue. This is the other database and the other failure.
 *
 * <p>Each message is a bounded phrase written in this repository, naming the statement and never a
 * parameter, a host or the driver's own words: it reaches a log line about a register whose every
 * defendant is a child (constitution Principle VII). The cause is attached in every case, so the
 * stack trace still says what actually failed.
 */
public class FileServicePayloadStore implements PayloadFileStore {

    /** The framework's own content insert, character for character (data-model.md). */
    private static final String CONTENT_INSERT =
            "INSERT INTO content(file_id, content, deleted) VALUES (?, ?, false)";

    /** The framework's own metadata insert, character for character (data-model.md). */
    private static final String METADATA_INSERT =
            "INSERT INTO metadata(metadata, file_id) VALUES (to_json(?::json), ?)";

    /** What each insert names itself in a failure, bounded and written here. */
    private static final String CONTENT_WRITE = "write the payload's content row";

    private static final String METADATA_WRITE = "write the payload's metadata row";

    /** An insert of one row affects one row; anything else is not a payload that is stored. */
    private static final int ONE_ROW = 1;

    /**
     * The same mapper contract the shared bean carries, so the bytes written here are the bytes the
     * caller measured.
     *
     * <p>The caller serialises nothing: it hands over the tree and the {@code fileSize} it measured
     * with the injected mapper, and a store that wrote those bytes under a differently configured
     * mapper would put a size in the metadata that does not describe the content beside it. The
     * mapper is not a constructor parameter because {@link JacksonConfig} is the single definition
     * both it and the injected bean are built from, and this store has no other JSON to read.
     */
    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    /**
     * The client the two inserts are issued through - the file service's, never the processed log's.
     */
    private final JdbcClient jdbcClient;

    /**
     * Binds the store to the file-service database and to no other.
     *
     * <p>The client arrives by name ({@code FileServiceDataSourceConfig.JDBC_CLIENT}) rather than by
     * type, because the type is ambiguous: this service holds two, and the one found by type is the
     * processed log's. A store that took that one would write a night's payloads into the register's
     * own database, where nothing would ever read them and the two tables do not exist.
     *
     * @param jdbcClient the file-service client, qualified by name
     */
    public FileServicePayloadStore(final JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "the file-service client is required");
    }

    @Override
    public void store(final UUID fileId, final JsonNode payload, final PayloadMetadata metadata)
            throws PayloadStoreUnavailableException {
        write(fileId, serialised(payload), metadata);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The same two inserts in the same order as {@link #store}, over the same client and with
     * the same licence: this is a second caller of somebody else's database and not a second use of
     * it. The CSV becomes bytes here, in UTF-8, because {@code content} is a {@code bytea} column
     * and the encoding of what support opens is not something to leave to whatever the platform
     * default happens to be.
     */
    @Override
    public void storeText(final UUID fileId, final String text, final PayloadMetadata metadata)
            throws PayloadStoreUnavailableException {
        write(fileId, text.getBytes(StandardCharsets.UTF_8), metadata);
    }

    /**
     * The two inserts both callers make, written once.
     *
     * <p><strong>Content first.</strong> {@code metadata.file_id} is a foreign key onto
     * {@code content.file_id}, so the order is the schema's requirement and not a preference - and
     * it is one order rather than two because a second copy of it is a second thing to get wrong
     * about a table this service does not own.
     *
     * @param fileId   the file-service id, minted and persisted before this call
     * @param content  the bytes, as the caller measured them for the metadata beside them
     * @param metadata the metadata row that goes beside them
     * @throws PayloadStoreUnavailableException if either row is not durably written, for any reason
     */
    private void write(final UUID fileId, final byte[] content, final PayloadMetadata metadata) {
        settle(CONTENT_WRITE, () -> jdbcClient.sql(CONTENT_INSERT)
                .param(fileId)
                .param(content)
                .update());
        final String metadataJson = serialised(metadata);
        settle(METADATA_WRITE, () -> jdbcClient.sql(METADATA_INSERT)
                .param(metadataJson)
                .param(fileId)
                .update());
    }

    /**
     * Issues one insert and insists it wrote the one row it is an insert of.
     *
     * <p>Two catches rather than one. The first is the one a file service that is away actually
     * takes: everything the driver raises, a connection that could not be acquired included,
     * arrives as Spring's translated {@link DataAccessException}. The second is there because the
     * port promises the caller this signal <em>for any reason</em> - a pool that refuses to
     * initialise for something that is not a {@code SQLException} raises its own unchecked failure
     * that no translator sees, and its message quotes the host it could not reach. Whatever the
     * shape, a payload that is not stored is not stored, and the batch has one decision to make
     * about it.
     *
     * @param statement a bounded phrase naming what was being written, for the failure's message
     * @param insert    the insert, answering with the rows it affected
     * @throws PayloadStoreUnavailableException if the row is not durably written, for any reason
     */
    // PMD.AvoidCatchingGenericException: the port promises this signal for any reason, and the
    // reason is not always a translated one. It is a catch-and-translate, not a catch-and-ignore:
    // what was caught travels on as the cause (constitution Principle VI).
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static void settle(final String statement, final IntSupplier insert) {
        final int rows;
        try {
            rows = insert.getAsInt();
        } catch (DataAccessException unreachable) {
            throw new PayloadStoreUnavailableException(
                    "the file service could not be reached to " + statement, unreachable);
        } catch (RuntimeException failed) {
            throw new PayloadStoreUnavailableException(
                    "the file service did not take the write that would " + statement, failed);
        }
        if (rows != ONE_ROW) {
            throw new PayloadStoreUnavailableException("the file service answered " + rows
                    + " rows, not one, to the write that would " + statement);
        }
    }

    /**
     * The payload's bytes, as the shared mapper produces them.
     *
     * @param payload the render payload, as the mapper produced it
     * @return the bytes systemdocgenerator will be rendered from
     * @throws PayloadStoreUnavailableException if the tree cannot be written out at all
     */
    private static byte[] serialised(final JsonNode payload) {
        try {
            return MAPPER.writeValueAsBytes(payload);
        } catch (JacksonException unwritable) {
            throw new PayloadStoreUnavailableException(
                    "the payload could not be written out for the file service", unwritable);
        }
    }

    /**
     * The metadata row, as progression's five keys and no others.
     *
     * <p>The two counts are written as JSON numbers because progression writes them as JSON
     * numbers, and a quoted one is a different value to everything that reads this row.
     *
     * @param metadata the metadata that goes beside the payload
     * @return the JSON the {@code to_json(?::json)} parameter is bound to
     * @throws PayloadStoreUnavailableException if the row cannot be written out at all
     */
    private static String serialised(final PayloadMetadata metadata) {
        final ObjectNode row = MAPPER.createObjectNode();
        row.put("fileName", metadata.fileName());
        row.put("conversionFormat", metadata.conversionFormat());
        row.put("templateName", metadata.templateName());
        row.put("numberOfPages", metadata.numberOfPages());
        row.put("fileSize", metadata.fileSize());
        try {
            return MAPPER.writeValueAsString(row);
        } catch (JacksonException unwritable) {
            throw new PayloadStoreUnavailableException(
                    "the payload's metadata could not be written out for the file service",
                    unwritable);
        }
    }
}
