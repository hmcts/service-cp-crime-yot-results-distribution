package uk.gov.hmcts.cp.yotresultsdistribution.adapter.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.fileservice.FileServicePayloadStore;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionEntry;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionKind;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionReport;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.MailOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.MailStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportMail;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportWindow;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;

/**
 * What the attachment actually is, read back out of the file service it was written into.
 *
 * <p>The sink's <strong>composition</strong>: the CSV's header and its thirteen columns, one row per
 * entry in the entries' own order, the encoding and the quoting, and the five metadata keys it hands
 * down. It is asserted through a real {@link FileServicePayloadStore} against a real Postgres
 * carrying the framework's own schema, because what support opens is the bytes in the
 * {@code content} row and not the string the sink happened to build - a column that lost them, or a
 * size that describes something else, is an attachment nobody can read.
 *
 * <p><strong>It asserts nothing about statement counts.</strong> That claim is
 * {@code FileServicePayloadStoreIT}'s, in the suite that owns the class whose method it is, so
 * neither suite restates the other's.
 *
 * <p>The mailer is a recorder rather than a transport: what goes on the wire is
 * {@code NotificationNotifyReportMailerTest}'s, and what this suite needs from it is the file id
 * the sink put on the mail, which is how a case knows which row to read back.
 */
@DisplayName("the exception report's CSV attachment")
class EmailReportSinkStoreIT {

    /** The database created inside the shared container to carry the framework's schema. */
    private static final String DATABASE = "fileservice_email";

    /** The plain-DDL translation of the vendored changesets; the changesets remain the authority. */
    private static final Path SCHEMA = Path.of("docker", "fileservice", "init.sql");

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    /** The thirteen columns, which are the entry's own components and no others. */
    private static final String HEADER = "kind,source,request_id,hearing_id,hearing_day,batch_id,"
            + "notification_id,court_centre_id,register_date,status,attempts,reason,age_seconds";

    /**
     * Half past midnight in London, half past eleven the evening before in UTC.
     *
     * <p>The file's name is dated the run's own day, and the two dates differ across this instant:
     * a name taken in UTC would call this morning's report yesterday's, five months of the year.
     */
    private static final Instant SNAPSHOT_AT = Instant.parse("2026-09-15T23:30:00Z");

    /** The name that instant has to produce, which is the London day and not the UTC one. */
    private static final String EXPECTED_FILE_NAME = "court-register-exceptions_2026-09-16.csv";

    private static final UUID TEMPLATE_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    /** One address, fixed, so a case can say which mail it read the file id off. */
    private static final String RECIPIENT = "support@example.invalid";

    /** The producing context, which is the one field on an entry another system chose the text of. */
    private static final String SOURCE = "cpp-context-results";

    /** The one character RFC 4180 reserves, named so the expected text below is readable. */
    private static final String QUOTE = "\"";

    /**
     * The record ending RFC 4180 states, which is this file's dialect.
     *
     * <p>A line break <em>inside</em> a quoted field is whatever the producing context wrote and is
     * not a record ending; only the endings this sink writes are CRLF.
     */
    private static final String CRLF = "\r\n";

    /**
     * Every awkward thing a producing context's own text can be, in one value.
     *
     * <p>The separator, the quote character, a line break and a character outside ASCII: the four
     * that respectively split a row, end a field early, split an entry in two, and make a size
     * counted in characters differ from a size counted in bytes.
     */
    private static final String AWKWARD_SOURCE =
            "a source with a comma, a \"quote\", a line break\nand Ynys Môn"; // o-circ

    private static final UUID REQUEST_ID = UUID.fromString("4c8e1a70-9b2d-4f36-8a57-c1d0e9f3b284");

    private static final UUID HEARING_ID = UUID.fromString("9e2b4c60-1d38-4a75-9f04-6b3c8d1e5a72");

    private static final UUID BATCH_ID = UUID.fromString("11111111-2222-4333-8444-555555555555");

    private static final UUID COURT_CENTRE_ID =
            UUID.fromString("7a1c9e40-3b52-4d86-9f27-0e4b8d6a1c35");

    private static final LocalDate HEARING_DAY = LocalDate.of(2026, 9, 14);

    private static final LocalDate REGISTER_DATE = LocalDate.of(2026, 9, 12);

    /** Reads the two rows back. */
    private static JdbcClient reader;

    private static FileServicePayloadStore store;

    /** Every mail the sink asked for, in the order it asked. */
    private final List<ReportMail> mails = new ArrayList<>();

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
        store = new FileServicePayloadStore(JdbcClient.create(dataSource));
    }

    @Test
    void the_csv_has_one_header_row_and_one_row_per_entry_in_the_entries_own_order() {
        final String csv = csvOf(reportOf(requestFailed(600), batchFailed(900), notified(1200)));

        assertThat(csv.split("\n", -1))
                .as("a header and three rows and a trailing newline: an attachment with more rows "
                        + "than exceptions, or fewer, is a list support cannot count from")
                .hasSize(5);
        assertThat(csv.lines().toList())
                .element(0)
                .as("the header is the thirteen column names, spelled as the events spell their "
                        + "fields, so a spreadsheet and a saved query name the same things")
                .isEqualTo(HEADER);
        assertThat(csv.lines().skip(1).map(row -> row.split(",", -1)[0]).toList())
                .as("the entries' own order is the report's order, which is oldest first: a sink "
                        + "that sorted again would answer a different list from the events")
                .containsExactly("REQUEST_FAILED", "BATCH_FAILED", "NOTIFICATION_FAILED");
    }

    @Test
    void the_csv_columns_are_the_union_of_the_entry_table_with_an_empty_field_where_the_kind_does_not_carry_it() {
        final String csv = csvOf(reportOf(requestFailed(600)));

        final String[] header = csv.lines().toList().getFirst().split(",", -1);
        final String[] row = csv.lines().toList().get(1).split(",", -1);

        assertThat(header)
                .as("thirteen, `kind` included: one shape for five kinds, so a reader opens one "
                        + "table rather than five")
                .hasSize(13);
        assertThat(row).hasSameSizeAs(header);
        assertThat(row[0]).isEqualTo("REQUEST_FAILED");
        assertThat(row[1]).isEqualTo(SOURCE);
        assertThat(row[2]).isEqualTo(REQUEST_ID.toString());
        assertThat(row[3]).isEqualTo(HEARING_ID.toString());
        assertThat(row[4]).isEqualTo(HEARING_DAY.toString());
        assertThat(row[5]).as("a request has no batch, and the field is empty rather than absent, "
                        + "because a row with fewer fields than the header is not a CSV").isEmpty();
        assertThat(row[6]).isEmpty();
        assertThat(row[7]).isEmpty();
        assertThat(row[8]).isEmpty();
        assertThat(row[9]).isEqualTo("FAILED");
        assertThat(row[10]).isEqualTo("3");
        assertThat(row[11]).isEqualTo("DEAD_LETTERED");
        assertThat(row[12]).isEqualTo("600");
    }

    @Test
    void the_csv_is_utf_8_with_crlf_endings_and_rfc_4180_quoting() {
        final ExceptionEntry awkward = new ExceptionEntry(ExceptionKind.REQUEST_FAILED,
                AWKWARD_SOURCE, REQUEST_ID, HEARING_ID,
                HEARING_DAY, null, null, null, null, "FAILED", 1, "DEAD_LETTERED", 600);
        final ReportMail mail = deliver(reportOf(awkward)).getFirst();

        final String csv =
                new String(contentOf(mail.fileId()).orElseThrow(), StandardCharsets.UTF_8);

        assertThat(csv)
                .as("the bytes are UTF-8 and every record ends CRLF, which is the ending RFC 4180 "
                        + "states: this attachment is opened in a spreadsheet on somebody's "
                        + "desktop rather than parsed, and a bare newline is the ending a reader "
                        + "has to guess at")
                .contains(QUOTE + "a source with a comma, a \"\"quote\"\", a line break\nand Ynys "
                        + "Môn" + QUOTE) // o-circ
                .endsWith(CRLF);
        assertThat(csv.lines().toList().getFirst())
                .as("and the header's own record ends the same way, which is what makes the "
                        + "dialect the file's rather than the last row's")
                .isEqualTo(HEADER);
        assertThat(csv.indexOf(CRLF))
                .as("the first record ending is the header's, immediately after the last column "
                        + "name: a header ended LF and rows ended CRLF is two dialects in one file")
                .isEqualTo(HEADER.length());
        assertThat(csv.lines())
                .as("a line break inside a quoted field is a field and not a row: a header, the "
                        + "two physical lines that one entry occupies, and nothing else - a sink "
                        + "that left the break unquoted would make one exception read as two")
                .hasSize(3);
    }

    @Test
    void the_metadata_names_the_file_court_register_exceptions_dated_in_europe_london() {
        // A non-ASCII producing context, so the size claim below is about bytes: every other
        // fixture here is ASCII, and over ASCII a fileSize taken off String.length() agrees with
        // the byte count of the file and the row would describe it correctly by accident.
        final ReportMail mail = deliver(reportOf(new ExceptionEntry(ExceptionKind.REQUEST_FAILED,
                AWKWARD_SOURCE, REQUEST_ID, HEARING_ID, HEARING_DAY, null, null, null, null,
                "FAILED", 3, "DEAD_LETTERED", 600))).getFirst();
        final byte[] content = contentOf(mail.fileId()).orElseThrow();

        final JsonNode metadata = MAPPER.readTree(metadataOf(mail.fileId()).orElseThrow());

        assertThat(Set.copyOf(metadata.propertyNames()))
                .containsExactlyInAnyOrder(
                        "fileName", "conversionFormat", "templateName", "numberOfPages",
                        "fileSize");
        assertThat(metadata.get("fileName").stringValue())
                .as("the run's own day in Europe/London, which at half past midnight in BST is not "
                        + "the day the UTC clock is on")
                .isEqualTo(EXPECTED_FILE_NAME);
        assertThat(metadata.get("conversionFormat").stringValue()).isEqualTo("csv");
        assertThat(metadata.get("templateName").stringValue())
                .isEqualTo("yot-results-distribution-exception-report");
        assertThat(metadata.get("numberOfPages").intValue()).isEqualTo(1);
        assertThat(metadata.get("fileSize").longValue())
                .as("the count of the bytes beside it, so the row describes the file it is about")
                .isEqualTo(content.length);
    }

    @Test
    void a_batch_failed_row_fills_the_existing_columns_and_adds_none() {
        final String csv = csvOf(reportOf(batchFailed(900)));

        final String[] row = csv.lines().toList().get(1).split(",", -1);

        assertThat(csv.lines().toList().getFirst())
                .as("the fifth kind adds no column: it is a row shape the thirteen already carry")
                .isEqualTo(HEADER);
        assertThat(row).hasSize(13);
        assertThat(row[0]).isEqualTo("BATCH_FAILED");
        assertThat(row[1]).isEmpty();
        assertThat(row[5]).isEqualTo(BATCH_ID.toString());
        assertThat(row[7]).isEqualTo(COURT_CENTRE_ID.toString());
        assertThat(row[8]).isEqualTo(REGISTER_DATE.toString());
        assertThat(row[9]).isEqualTo("FAILED");
        assertThat(row[11])
                .as("the bounded reason this service wrote, which is the whole of what a dead "
                        + "batch tells an operator")
                .isEqualTo(BatchFailureReason.RENDER_REQUEST_FAILED.name());
        assertThat(row[12]).isEqualTo("900");
    }

    @Test
    void the_csv_carries_no_address_no_defendant_data_and_no_generator_text() {
        final String csv = csvOf(reportOf(requestFailed(600), batchFailed(900), notified(1200)));

        assertThat(csv)
                .as("every cell is an identifier, a bounded code, a date or a number: no read this "
                        + "feature makes selects an address or a name, and sdg_reason is another "
                        + "system's free text about a document (Principle VII)")
                .doesNotContain(RECIPIENT)
                .doesNotContain("@")
                .doesNotContain("sdg_reason")
                .doesNotContain("the generator could not read the payload");
    }

    /** The report the sink is given: one window, one snapshot, the entries in the order handed in. */
    private static ExceptionReport reportOf(final ExceptionEntry... entries) {
        return ExceptionReport.whole("a-run-the-caller-already-opened",
                new ReportWindow(SNAPSHOT_AT.minusSeconds(3600), SNAPSHOT_AT), SNAPSHOT_AT,
                List.of(entries));
    }

    private static ExceptionEntry requestFailed(final long ageSeconds) {
        return new ExceptionEntry(ExceptionKind.REQUEST_FAILED, SOURCE, REQUEST_ID, HEARING_ID,
                HEARING_DAY, null, null, null, null, "FAILED", 3, "DEAD_LETTERED", ageSeconds);
    }

    private static ExceptionEntry batchFailed(final long ageSeconds) {
        return new ExceptionEntry(ExceptionKind.BATCH_FAILED, null, null, null, null, BATCH_ID,
                null, COURT_CENTRE_ID, REGISTER_DATE, "FAILED", null,
                BatchFailureReason.RENDER_REQUEST_FAILED.name(), ageSeconds);
    }

    private static ExceptionEntry notified(final long ageSeconds) {
        return new ExceptionEntry(ExceptionKind.NOTIFICATION_FAILED, null, null, null, null,
                BATCH_ID, UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa"), COURT_CENTRE_ID,
                REGISTER_DATE, "FAILED", 2, "502", ageSeconds);
    }

    /** The CSV the sink stored, read back out of the file service as the text it is. */
    private String csvOf(final ExceptionReport report) {
        final ReportMail mail = deliver(report).getFirst();
        return new String(contentOf(mail.fileId()).orElseThrow(), StandardCharsets.UTF_8);
    }

    /**
     * Delivers one report, insisting the sink did not throw.
     *
     * <p>Said as an assertion rather than let out as whatever the sink raised: the contract is that
     * a sink answers how it went, so a sink that throws is a failure of this claim and not an error
     * in the fixture.
     *
     * @param report the report to deliver
     * @return every mail the sink asked for, in order
     */
    private List<ReportMail> deliver(final ExceptionReport report) {
        final EmailReportSink sink = new EmailReportSink(store, this::recorded, TEMPLATE_ID,
                List.of(RECIPIENT));

        assertThatCode(() -> sink.deliver(report))
                .as("a healthy file service and a recipient who takes it: the sink is expected to "
                        + "store the CSV and answer, and everything else here reads back what it "
                        + "stored")
                .doesNotThrowAnyException();
        return List.copyOf(mails);
    }

    /** The mailer that takes whatever it is given and keeps it. */
    private MailOutcome recorded(final ReportMail mail) {
        mails.add(mail);
        return new MailOutcome(MailStatus.ACCEPTED, 202);
    }

    private static Optional<byte[]> contentOf(final UUID id) {
        return reader.sql("SELECT content FROM content WHERE file_id = :fileId")
                .param("fileId", id)
                .query((rs, rowNumber) -> rs.getBytes("content"))
                .optional();
    }

    private static Optional<String> metadataOf(final UUID id) {
        return reader.sql("SELECT metadata::text FROM metadata WHERE file_id = :fileId")
                .param("fileId", id)
                .query(String.class)
                .optional();
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
}
