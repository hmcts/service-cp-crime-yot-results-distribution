package uk.gov.hmcts.cp.yotresultsdistribution.adapter.fileservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.application.PayloadMetadata;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.PayloadStoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;

/**
 * The payload write, against a real Postgres carrying the framework file service's own schema.
 *
 * <p>This is the one database this service writes to and does not own. systemdocgenerator renders
 * what is already in the file service and takes its id (research §1), so the two rows here are read
 * by another context's code against another team's DDL, and the only way to be sure of them is to
 * write them into that DDL and read them back. A mocked {@code JdbcClient} would agree with whatever
 * statement it was handed, including one the real tables would refuse.
 *
 * <p><strong>The schema is the vendored one.</strong> The container is seeded from
 * {@code docker/fileservice/init.sql}, which is the plain-DDL translation of
 * {@code specs/002-consolidate-progression-leg/contracts/fileservice/} changesets 001-006
 * (framework-libraries {@code 58aad8664}) and carries, changeset by changeset, why each column looks
 * as it does - {@code content} nullable because 005 re-adds it without the constraint 001 gave it,
 * and the timestamp column named {@code deleted_at} rather than what 006's file name says. Reusing
 * that file rather than translating the changesets a second time here is deliberate: two hand-made
 * copies of somebody else's schema would drift, and the one that drifted would be the one no
 * container ever ran. <strong>Caveat:</strong> those changesets are dated 2023-12-22 and T005 -
 * comparing them against a live stack's {@code \d metadata} and {@code \d content} - is still
 * deferred for want of STE access, so what this suite pins is the vendored DDL, not yet a verified
 * one.
 *
 * <p><strong>The statement log is an assertion, not a diagnostic.</strong> The store's whole licence
 * on this database is INSERT, and the role it runs as is granted no more than that, so a read, an
 * update or a second write would fail in a stack long after it passed here. The connections the
 * subject writes through are proxied and every statement they prepare is recorded, which is why the
 * read-back below goes through a second, unrecorded client: a suite that read through the same
 * connections would log its own SELECTs and could never tell them from the subject's.
 *
 * <p>Both inserts are exactly the framework's own ({@code MetadataJdbcRepository},
 * {@code ContentJdbcRepository}), compared after whitespace is collapsed so the assertion is about
 * the statement rather than about how it was laid out in the source. {@code content} is written
 * before {@code metadata} because {@code metadata.file_id} is a foreign key onto it, so the order is
 * the schema's requirement and not a preference.
 *
 * <p>The five metadata keys are progression's, spelled progression's way
 * ({@code CourtRegisterEventProcessor}). A sixth key, or a different spelling of one of these, is a
 * change to a contract this service does not own made without asking, so the assertion is on the key
 * set entire rather than on the presence of the ones we happen to care about.
 */
@DisplayName("file-service payload store")
class FileServicePayloadStoreIT {

    /** The database created inside the shared container to carry the framework's schema. */
    private static final String DATABASE = "fileservice";

    /** The plain-DDL translation of the vendored changesets; the changesets remain the authority. */
    private static final Path SCHEMA = Path.of("docker", "fileservice", "init.sql");

    /** A file service that is not answering: the address is a port nothing listens on. */
    private static final String UNREACHABLE_URL = "jdbc:postgresql://localhost:1/fileservice";

    private static final String FILE_ID = "fileId";

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    /**
     * A payload with a multi-byte character in it, so the round trip through {@code bytea} says
     * something about encoding rather than only about length.
     */
    private static final String PAYLOAD_JSON =
            "{\"courtHouse\":\"Ynys M\u00f4n Youth Court\",\"registerDate\":\"2026-08-20\"}"; // o-circumflex

    private static final JsonNode PAYLOAD = MAPPER.readTree(PAYLOAD_JSON);

    private static final byte[] PAYLOAD_BYTES = MAPPER.writeValueAsBytes(PAYLOAD);

    private static final String FILE_NAME = "yotresultsdistribution_2026-08-20.json";

    /** The template systemdocgenerator renders the register with; unchanged from progression. */
    private static final String TEMPLATE_NAME = "OEE_Layout5";

    private static final String CONVERSION_FORMAT = "pdf";

    /** Progression writes one, whatever the register turns out to run to. */
    private static final int NUMBER_OF_PAGES = 1;

    private static final PayloadMetadata METADATA = new PayloadMetadata(
            FILE_NAME, CONVERSION_FORMAT, TEMPLATE_NAME, NUMBER_OF_PAGES, PAYLOAD_BYTES.length);

    /**
     * The exception report's CSV, with a quoted field and a multi-byte character in it.
     *
     * <p>What this file looks like is {@code EmailReportSinkStoreIT}'s claim and not this suite's -
     * here it is a string with the two properties the write has to survive: bytes that are not
     * ASCII, and a comma inside a quoted field, so a round trip that lost either says so.
     */
    private static final String CSV_TEXT =
            "kind,court_centre,reason\nBATCH_FAILED,"
                    + "\"Youth Court, M\u00f4n\",RENDER_REQUEST_FAILED\n"; // o-circumflex

    private static final byte[] CSV_BYTES = CSV_TEXT.getBytes(StandardCharsets.UTF_8);

    /** The name the framework is told the CSV has, dated the run's own day in Europe/London. */
    private static final String CSV_FILE_NAME = "court-register-exceptions_2026-09-15.csv";

    /** The template name the exception report's attachment carries; not a rendering template. */
    private static final String CSV_TEMPLATE_NAME = "yot-results-distribution-exception-report";

    private static final String CSV_CONVERSION_FORMAT = "csv";

    private static final PayloadMetadata CSV_METADATA = new PayloadMetadata(
            CSV_FILE_NAME, CSV_CONVERSION_FORMAT, CSV_TEMPLATE_NAME, NUMBER_OF_PAGES,
            CSV_BYTES.length);

    /** The framework's own content insert, character for character (data-model.md). */
    private static final String CONTENT_INSERT =
            "INSERT INTO content(file_id, content, deleted) VALUES (?, ?, false)";

    /** The framework's own metadata insert, character for character (data-model.md). */
    private static final String METADATA_INSERT =
            "INSERT INTO metadata(metadata, file_id) VALUES (to_json(?::json), ?)";

    /** Every statement the subject's connections prepared, in the order they prepared them. */
    private static final List<String> STATEMENT_LOG = Collections.synchronizedList(new ArrayList<>());

    /** Reads rows back, over connections the log never sees. */
    private static JdbcClient reader;

    private static FileServicePayloadStore store;

    /** The same subject over a database that is not there. */
    private static FileServicePayloadStore storeWithNoFileService;

    /** This case's file id: minted per test, so no case can read another's rows. */
    private final UUID fileId = UUID.randomUUID();

    @BeforeAll
    static void seedTheFrameworksSchema() {
        final String url = PostgresTestSupport.createEmptyDatabase(DATABASE);
        applySchema(url);
        final DataSource dataSource = DataSourceBuilder.create()
                .url(url)
                .username(PostgresTestSupport.username())
                .password(PostgresTestSupport.password())
                .build();
        reader = JdbcClient.create(dataSource);
        store = new FileServicePayloadStore(JdbcClient.create(recording(dataSource)));
        storeWithNoFileService = new FileServicePayloadStore(JdbcClient.create(
                DataSourceBuilder.create()
                        .url(UNREACHABLE_URL)
                        .username(PostgresTestSupport.username())
                        .password(PostgresTestSupport.password())
                        .build()));
    }

    @BeforeEach
    void forgetEarlierStatements() {
        STATEMENT_LOG.clear();
    }

    @Nested
    @DisplayName("a payload the batch minted an id for")
    class Stored {

        @Test
        void storing_a_payload_should_write_both_rows_under_the_id_the_caller_minted() {
            storePayload(fileId);

            assertThat(contentOf(fileId))
                    .as("systemdocgenerator is given the id and nothing else, so a payload written "
                            + "under any other one is a render request for a file that does not "
                            + "exist")
                    .isPresent();
            assertThat(metadataOf(fileId))
                    .as("the metadata row is what names the template the document is rendered from")
                    .isPresent();
        }

        @Test
        void storing_a_payload_should_write_the_bytes_the_caller_measured_as_a_live_content_row() {
            storePayload(fileId);

            assertThat(contentOf(fileId).orElseThrow())
                    .as("the payload is read back out of this column by another context; a lossy "
                            + "round trip is a document rendered from something the mapper did not "
                            + "produce")
                    .isEqualTo(PAYLOAD_BYTES);
            assertThat(deletedOf(fileId))
                    .as("the file service treats a deleted row as absent, and a payload that is "
                            + "absent the moment it is written is a render request that cannot be "
                            + "answered")
                    .contains(false);
            assertThat(deletedAtOf(fileId))
                    .as("nothing has deleted this row, and a deletion timestamp on a live row would "
                            + "be this service inventing a fact about somebody else's housekeeping")
                    .isEmpty();
        }

        @Test
        void storing_a_payload_should_write_progressions_five_metadata_keys_and_no_others() {
            storePayload(fileId);

            final JsonNode metadata = MAPPER.readTree(metadataOf(fileId).orElseThrow());

            assertThat(Set.copyOf(metadata.propertyNames()))
                    .as("the reader of this row is systemdocgenerator, not this service: a sixth "
                            + "key or a differently spelled one is a change to somebody else's "
                            + "contract made without asking")
                    .containsExactlyInAnyOrder(
                            "fileName", "conversionFormat", "templateName", "numberOfPages",
                            "fileSize");
            assertThat(metadata.get("fileName").stringValue()).isEqualTo(FILE_NAME);
            assertThat(metadata.get("conversionFormat").stringValue())
                    .isEqualTo(CONVERSION_FORMAT);
            assertThat(metadata.get("templateName").stringValue()).isEqualTo(TEMPLATE_NAME);
            assertThat(metadata.get("numberOfPages").intValue()).isEqualTo(NUMBER_OF_PAGES);
            assertThat(metadata.get("fileSize").longValue()).isEqualTo(PAYLOAD_BYTES.length);
            assertThat(metadata.get("numberOfPages").isNumber())
                    .as("progression writes both counts as JSON numbers, and a quoted one is a "
                            + "different value to anything that reads this row")
                    .isTrue();
            assertThat(metadata.get("fileSize").isNumber()).isTrue();
        }

        @Test
        void storing_a_payload_should_issue_the_frameworks_two_inserts_and_nothing_else() {
            storePayload(fileId);

            assertThat(issued())
                    .as("the role this service holds on the file service is granted INSERT and no "
                            + "more, so a read, an update or a third statement passes here and "
                            + "fails in a stack; content is written first because metadata.file_id "
                            + "is a foreign key onto it")
                    .containsExactly(CONTENT_INSERT, METADATA_INSERT);
        }
    }

    @Nested
    @DisplayName("the exception report's CSV, written as text")
    class StoredText {

        @Test
        void store_text_issues_exactly_two_statements_content_before_metadata() {
            storeCsv(fileId);

            assertThat(issued())
                    .as("the same licence and the same order as the payload write: INSERT and no "
                            + "more, content before metadata because metadata.file_id is a foreign "
                            + "key onto it, and no third statement - a second caller of somebody "
                            + "else's database that wrote it differently would be a second "
                            + "contract")
                    .containsExactly(CONTENT_INSERT, METADATA_INSERT);
        }

        @Test
        void store_text_writes_the_utf_8_bytes_of_the_string_into_the_bytea_column() {
            storeCsv(fileId);

            assertThat(contentOf(fileId).orElseThrow())
                    .as("notificationnotify attaches whatever is under this id, so a lossy round "
                            + "trip is an attachment support opens and cannot read - and the "
                            + "fileSize beside it is the count of these bytes")
                    .isEqualTo(CSV_BYTES);
            assertThat(deletedOf(fileId))
                    .as("a row that is deleted the moment it is written is an e-mail with nothing "
                            + "attached")
                    .contains(false);
        }

        @Test
        void store_text_writes_the_five_metadata_keys() {
            storeCsv(fileId);

            final JsonNode metadata = MAPPER.readTree(metadataOf(fileId).orElseThrow());

            assertThat(Set.copyOf(metadata.propertyNames()))
                    .as("the five keys the framework spells, whatever the file is: a CSV is not a "
                            + "reason to write a sixth key into somebody else's table")
                    .containsExactlyInAnyOrder(
                            "fileName", "conversionFormat", "templateName", "numberOfPages",
                            "fileSize");
            assertThat(metadata.get("fileName").stringValue()).isEqualTo(CSV_FILE_NAME);
            assertThat(metadata.get("conversionFormat").stringValue())
                    .isEqualTo(CSV_CONVERSION_FORMAT);
            assertThat(metadata.get("templateName").stringValue()).isEqualTo(CSV_TEMPLATE_NAME);
            assertThat(metadata.get("numberOfPages").intValue()).isEqualTo(NUMBER_OF_PAGES);
            assertThat(metadata.get("fileSize").longValue())
                    .as("the byte count and not the character count, which for this CSV are not "
                            + "the same number")
                    .isEqualTo(CSV_BYTES.length);
        }

        @Test
        void a_csv_that_could_not_be_written_should_be_reported_as_the_store_being_unavailable() {
            assertThatThrownBy(
                    () -> storeWithNoFileService.storeText(fileId, CSV_TEXT, CSV_METADATA))
                    .as("the sink reports ATTACHMENT_STORE_UNAVAILABLE and sends nothing on this, "
                            + "which it can only decide if what it catches is this service's own "
                            + "signal rather than the driver's")
                    .isInstanceOf(PayloadStoreUnavailableException.class)
                    .hasMessageNotContaining("localhost");
        }
    }

    @Nested
    @DisplayName("a file service that cannot be reached")
    class Unavailable {

        @Test
        void a_payload_that_could_not_be_written_should_be_reported_as_the_store_being_unavailable() {
            assertThatThrownBy(() -> storeWithNoFileService.store(fileId, PAYLOAD, METADATA))
                    .as("the batch fails PAYLOAD_STORE_UNAVAILABLE on this and its rows stay "
                            + "RECORDED for the next run, which the caller can only decide if what "
                            + "it catches is this service's own signal rather than the driver's")
                    .isInstanceOf(PayloadStoreUnavailableException.class);
        }

        @Test
        void a_payload_that_could_not_be_written_should_not_carry_the_databases_own_words() {
            assertThatThrownBy(() -> storeWithNoFileService.store(fileId, PAYLOAD, METADATA))
                    .isInstanceOf(PayloadStoreUnavailableException.class)
                    .as("this message reaches a log line about a register whose every defendant is "
                            + "a child, so it is a bounded phrase this service wrote and never the "
                            + "driver's, which quotes the host it could not reach")
                    .hasMessageNotContaining("localhost")
                    .hasMessageNotContaining(UNREACHABLE_URL);
        }
    }

    /**
     * Stores the fixture payload, insisting the write happened.
     *
     * <p>The write is the whole subject: a store that could not write it has nothing for the cases
     * below to read back, and this says so as an assertion rather than letting the failure arrive as
     * whatever the subject threw.
     */
    private static void storePayload(final UUID id) {
        assertThatCode(() -> store.store(id, PAYLOAD, METADATA))
                .as("a healthy file service and a payload the mapper produced: the write is "
                        + "expected to happen, and everything else here reads back what it wrote")
                .doesNotThrowAnyException();
    }

    /**
     * Stores the fixture CSV, insisting the write happened, exactly as {@link #storePayload} does.
     */
    private static void storeCsv(final UUID id) {
        assertThatCode(() -> store.storeText(id, CSV_TEXT, CSV_METADATA))
                .as("a healthy file service and a CSV the sink rendered: the write is expected to "
                        + "happen, and everything else here reads back what it wrote")
                .doesNotThrowAnyException();
    }

    private static Optional<byte[]> contentOf(final UUID id) {
        return reader.sql("SELECT content FROM content WHERE file_id = :fileId")
                .param(FILE_ID, id)
                .query((rs, rowNumber) -> rs.getBytes("content"))
                .optional();
    }

    private static Optional<Boolean> deletedOf(final UUID id) {
        return reader.sql("SELECT deleted FROM content WHERE file_id = :fileId")
                .param(FILE_ID, id)
                .query(Boolean.class)
                .optional();
    }

    private static Optional<String> deletedAtOf(final UUID id) {
        return reader.sql("SELECT deleted_at::text FROM content WHERE file_id = :fileId")
                .param(FILE_ID, id)
                .query(String.class)
                .optional();
    }

    private static Optional<String> metadataOf(final UUID id) {
        return reader.sql("SELECT metadata::text FROM metadata WHERE file_id = :fileId")
                .param(FILE_ID, id)
                .query(String.class)
                .optional();
    }

    /**
     * The statements the subject prepared, with runs of whitespace collapsed and any trailing
     * semicolon dropped, so that the assertion is about the statement and not about how it was laid
     * out in the source.
     */
    private static List<String> issued() {
        synchronized (STATEMENT_LOG) {
            return STATEMENT_LOG.stream()
                    .map(sql -> sql.replaceAll("\\s+", " ").trim())
                    .map(sql -> sql.endsWith(";") ? sql.substring(0, sql.length() - 1).trim() : sql)
                    .toList();
        }
    }

    /** Applies the vendored schema to the freshly created database. */
    private static void applySchema(final String url) {
        final String ddl;
        try {
            ddl = Files.readString(SCHEMA, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException("could not read " + SCHEMA, unreadable);
        }
        try (Connection connection = DriverManager.getConnection(
                url, PostgresTestSupport.username(), PostgresTestSupport.password());
             Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not apply " + SCHEMA + " to " + url, failed);
        }
    }

    /**
     * Wraps a data source so that every statement its connections prepare is recorded.
     *
     * <p>A proxy rather than a subclass because the interfaces are wide and only three methods
     * matter; the rest are handed to the real object untouched.
     */
    private static DataSource recording(final DataSource delegate) {
        return (DataSource) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, arguments) -> {
                    final Object answer = call(delegate, method, arguments);
                    return answer instanceof Connection connection
                            ? recordingConnection(connection) : answer;
                });
    }

    private static Connection recordingConnection(final Connection delegate) {
        return (Connection) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> {
                    if (prepares(method) && arguments != null && arguments.length > 0
                            && arguments[0] instanceof String sql) {
                        STATEMENT_LOG.add(sql);
                    }
                    final Object answer = call(delegate, method, arguments);
                    return answer instanceof Statement statement
                            && !(answer instanceof PreparedStatement)
                            ? recordingStatement(statement) : answer;
                });
    }

    /**
     * Wraps the plain {@code Statement} a caller made itself, which carries its SQL at execution
     * rather than at creation.
     */
    private static Statement recordingStatement(final Statement delegate) {
        return (Statement) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Statement.class},
                (proxy, method, arguments) -> {
                    if (executes(method) && arguments != null && arguments.length > 0
                            && arguments[0] instanceof String sql) {
                        STATEMENT_LOG.add(sql);
                    }
                    return call(delegate, method, arguments);
                });
    }

    private static boolean prepares(final Method method) {
        return "prepareStatement".equals(method.getName())
                || "prepareCall".equals(method.getName())
                || "nativeSQL".equals(method.getName());
    }

    private static boolean executes(final Method method) {
        return method.getName().startsWith("execute") || "addBatch".equals(method.getName());
    }

    /** Calls the real object, letting what it threw out rather than the reflection wrapper. */
    private static Object call(final Object target, final Method method, final Object... arguments)
            throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause();
        }
    }
}
