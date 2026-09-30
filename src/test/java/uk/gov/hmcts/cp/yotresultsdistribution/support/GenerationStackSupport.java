package uk.gov.hmcts.cp.yotresultsdistribution.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.Application;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.notificationnotify.NotificationNotifyClient;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.systemdocgenerator.SystemDocGeneratorClient;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;

/**
 * Everything the nightly run talks to, for the two end-to-end suites that drive it.
 *
 * <p>The run has five outsides and this fixture is four of them: App Configuration, from which the
 * one lever is read; systemdocgenerator's command API; notificationnotify's command API,
 * which is where a generated register turns into the e-mails the Youth Offending Teams are told by;
 * and the framework file service's own database, which the payload is written into and which nothing
 * in this service migrates. The fifth is the broker, and each suite owns that itself because whether
 * an outcome is published at all is the thing those suites differ about.
 *
 * <p><strong>One WireMock server, three contexts.</strong> The App Configuration {@code kv}
 * resource, systemdocgenerator's two paths and notificationnotify's one are disjoint, so one server
 * can be all three; more would be more ports, more lifecycles and more chances to reset the wrong
 * one. What it buys is the assertion {@code FlagGateEndToEndIT} exists to make - that a run the flag
 * stopped reached <em>no</em> socket - because "nothing was requested" is a claim about the one
 * server that would have received it.
 *
 * <p>The flag is read through the live wiring's own {@code AppConfigurationFlagReader} over a real
 * SDK client, with a connection string exactly as a deployed pod is given one - only the pair is a
 * fixed test value the stub does not check.
 *
 * <p>The file-service database is its own database inside the shared container, named apart from
 * {@code FileServicePayloadStoreIT}'s so the two suites can run in one JVM without one creating a
 * database the other already made.
 */
public final class GenerationStackSupport implements AutoCloseable {

    /** The file-service database this fixture creates, seeded from the committed local schema. */
    public static final String DATABASE = "fileservice_e2e";

    /** The identity the App Configuration client authenticates with; a test value and no secret. */
    public static final String STORE_ID = "0-l0-s0:yotresultsdistributiontest";

    /** The HMAC secret that identity signs with; a fixed, published test value. */
    public static final String STORE_SECRET =
            "c2VydmljZS1jcC1jcmltZS1jb3VydC1yZWdpc3Rlci10ZXN0LXNlY3JldA==";

    /** The App Configuration key the flag is read under, as the deployment configures it. */
    public static final String FLAG_KEY = ".appconfig.featureflag/YotResultsDistributionService";

    /** The label one store serves this stack under. */
    public static final String FLAG_LABEL = "e2e";

    /** Any {@code kv} read, whatever key and label the reader asks under. */
    private static final String ANY_KEY_PATH = "/kv/.*";

    /**
     * Any {@code send-email-notification}, whatever notification identity it was made under.
     *
     * <p>The identity is the last path segment and is minted per recipient at run time, so a stub
     * can only be written against the shape of the path; which identity each e-mail actually went
     * out under is read back off {@link #emailsSent()} and compared with the row that was minted.
     */
    private static final String ANY_NOTIFICATION_PATH = NotificationNotifyClient.COMMAND_PATH
            .replace("{notificationId}", "[^/]+");

    /**
     * The precedence a stub about one recipient is registered at.
     *
     * <p>Stated rather than left to WireMock's default, because a refusal for one address and the
     * catch-all that accepts everything else both match that address's request: the two are ordered
     * here so which one answers is a decision and not an accident of registration order.
     */
    private static final int ONE_RECIPIENT = 1;

    /** The precedence the accept-everything stubs are registered at, behind the specific ones. */
    private static final int EVERY_RECIPIENT = 10;

    /** The media type App Configuration answers a key-value read with. */
    private static final String KV_MEDIA_TYPE = "application/vnd.microsoft.appconfig.kv+json";

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    /** The committed local schema, generated by hand from the vendored liquibase changesets. */
    private static final Path FILE_SERVICE_SCHEMA = Path.of("docker", "fileservice", "init.sql");

    /** The file-service database's URL, created once per JVM however many suites ask for it. */
    private static String fileServiceDatabaseUrl;

    private final WireMockServer contexts;

    private final JdbcClient fileService;

    /** The per-recipient answers this stack holds, kept so they can be withdrawn again. */
    private final List<StubMapping> recipientAnswers = new ArrayList<>();

    private GenerationStackSupport(final WireMockServer contexts, final JdbcClient fileService) {
        this.contexts = contexts;
        this.fileService = fileService;
    }

    /**
     * Starts the two HTTP contexts and makes sure the file-service database exists.
     *
     * @return the running stack, to be closed by the caller
     */
    public static GenerationStackSupport start() {
        final WireMockServer contexts = new WireMockServer(wireMockConfig().dynamicPort());
        contexts.start();
        final GenerationStackSupport stack =
                new GenerationStackSupport(contexts, JdbcClient.create(fileServiceDataSource()));
        stack.sdgAcceptsEveryRequest();
        stack.nnAcceptsEveryEmail();
        return stack;
    }

    /**
     * The settings that point a context at this stack.
     *
     * <p>Generation on and every mode LIVE, because that is the deployment these suites are about
     * and {@code PropertiesValidator} refuses a stand-in wherever generation is enabled. Intake is
     * off: it is the other half of the service and needs a broker these suites have no business
     * standing up. The broker settings are the caller's, because whether an outcome is published is
     * what the two suites differ about.
     *
     * @return the settings
     */
    public Map<String, String> settings() {
        final Map<String, String> settings = new LinkedHashMap<>();
        settings.put("spring.datasource.url", PostgresTestSupport.jdbcUrl());
        settings.put("spring.datasource.username", PostgresTestSupport.username());
        settings.put("spring.datasource.password", PostgresTestSupport.password());
        settings.put("yotresultsdistribution.consumer.enabled", "false");
        settings.put("yotresultsdistribution.payload.mode", "STUB");
        settings.put("yotresultsdistribution.referencedata.mode", "STUB");
        settings.put("yotresultsdistribution.generation.enabled", "true");
        settings.put("yotresultsdistribution.generation.sdg-mode", "LIVE");
        settings.put("yotresultsdistribution.generation.nn-mode", "LIVE");
        settings.put("yotresultsdistribution.generation.fileservice-mode", "LIVE");
        settings.put("yotresultsdistribution.generation.flag-mode", "LIVE");
        settings.put("yotresultsdistribution.feature.connection-string",
                "Endpoint=" + contexts.baseUrl() + ";Id=" + STORE_ID + ";Secret=" + STORE_SECRET);
        settings.put("yotresultsdistribution.feature.key", FLAG_KEY);
        settings.put("yotresultsdistribution.feature.label", FLAG_LABEL);
        settings.put("yotresultsdistribution.feature.timeout", "5s");
        settings.put("yotresultsdistribution.fileservice.url", fileServiceUrl());
        settings.put("yotresultsdistribution.fileservice.username", PostgresTestSupport.username());
        settings.put("yotresultsdistribution.fileservice.password", PostgresTestSupport.password());
        settings.put("yotresultsdistribution.endpoints.systemdocgenerator", contexts.baseUrl());
        settings.put("yotresultsdistribution.endpoints.notificationnotify", contexts.baseUrl());
        settings.put("yotresultsdistribution.endpoints.system-user-id", ServiceTestSupport.SYSTEM_USER_ID);
        settings.put("yotresultsdistribution.email.templates.cr_standard",
                "5c9a0e21-3d47-4f18-9b62-0a71c4e8d530");
        // An in-VM Artemis, which is the whole of the broker these suites need: the subscription is
        // durable and topic-scoped exactly as it is deployed, and nothing outside this JVM can
        // publish onto it.
        settings.put("spring.artemis.mode", "embedded");
        settings.put("spring.artemis.embedded.enabled", "true");
        settings.put("spring.artemis.embedded.topics", "public.event");
        // Never connected to - embedded mode dials vm://0 - but startup requires a broker URL
        // wherever completion is event-driven, and it is right to.
        settings.put("spring.artemis.broker-url", "tcp://localhost:61616");
        return settings;
    }

    /** Where this stack's App Configuration and systemdocgenerator answer. */
    public String baseUrl() {
        return contexts.baseUrl();
    }

    /**
     * Starts the service against this stack, with the flag reader these suites can point at a stub.
     *
     * <p>The settings go down as command-line arguments for the reason
     * {@link ServiceTestSupport#start(Map)} gives: default properties sit <em>below</em>
     * {@code application.yaml}, so a suite pointing the service at a container would silently be
     * answered by the committed local development value.
     *
     * @param settings this stack's settings, with whatever the suite added to them
     * @return the running context, to be closed by the caller
     */
    public static ConfigurableApplicationContext startService(final Map<String, String> settings) {
        return new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE)
                .run(settings.entrySet().stream()
                        .map(setting -> "--" + setting.getKey() + '=' + setting.getValue())
                        .toArray(String[]::new));
    }

    // --- the one lever ---------------------------------------------------------------------------

    /**
     * App Configuration answers with the flag, enabled or not.
     *
     * @param enabled what the store says the flag is
     */
    public void flagIs(final boolean enabled) {
        answering(200, settingCarrying(flagValue(enabled)));
    }

    /** App Configuration is there and cannot answer, which the reader reads as UNREADABLE. */
    public void flagIsUnreadable() {
        answering(500, "{\"error\":\"the store could not answer\"}");
    }

    private void answering(final int status, final String body) {
        contexts.stubFor(get(urlPathMatching(ANY_KEY_PATH)).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", KV_MEDIA_TYPE)
                .withBody(body)));
    }

    /** The store's answer for a setting that is there, carrying {@code value} verbatim. */
    private static String settingCarrying(final String value) {
        return "{\"key\":" + MAPPER.writeValueAsString(FLAG_KEY)
                + ",\"label\":" + MAPPER.writeValueAsString(FLAG_LABEL)
                + ",\"content_type\":\"application/vnd.microsoft.appconfig.ff+json\""
                + ",\"value\":" + MAPPER.writeValueAsString(value)
                + ",\"tags\":{},\"locked\":false"
                + ",\"last_modified\":\"2026-09-01T18:00:00+00:00\",\"etag\":\"cr-e2e\"}";
    }

    /** App Configuration's feature-flag JSON, as the vendored value schema declares it. */
    private static String flagValue(final boolean enabled) {
        return "{\"id\":\"YotResultsDistributionService\",\"description\":\"the e2e stack's flag\""
                + ",\"enabled\":" + enabled
                + ",\"conditions\":{\"client_filters\":[]}}";
    }

    // --- systemdocgenerator ----------------------------------------------------------------------

    /** systemdocgenerator accepts every render request, which is the only success the contract has. */
    public void sdgAcceptsEveryRequest() {
        contexts.stubFor(post(urlEqualTo(SystemDocGeneratorClient.COMMAND_PATH))
                .willReturn(aResponse().withStatus(202)));
    }

    /**
     * Every {@code generate-document} this stack has been sent, as bodies.
     *
     * @return the request bodies, in arrival order
     */
    public List<String> renderRequests() {
        return contexts.findAll(postRequestedFor(
                        urlEqualTo(SystemDocGeneratorClient.COMMAND_PATH)))
                .stream()
                .map(LoggedRequest::getBodyAsString)
                .toList();
    }

    // --- notificationnotify ----------------------------------------------------------------------

    /** notificationnotify accepts every e-mail, which is the one success the contract has. */
    public void nnAcceptsEveryEmail() {
        contexts.stubFor(post(urlPathMatching(ANY_NOTIFICATION_PATH))
                .atPriority(EVERY_RECIPIENT)
                .willReturn(aResponse().withStatus(NotificationNotifyClient.ACCEPTED)));
    }

    /**
     * notificationnotify answers one recipient's e-mail with something other than acceptance.
     *
     * <p>Matched on the address in the body rather than on the path, because the path carries the
     * notification identity and that is minted at run time: a suite that had to know it in advance
     * could not stub anything at all. One address is one recipient and one row, so the address is
     * exactly as selective as the fix requires.
     *
     * @param emailAddress the recipient this answer is for
     * @param status       what notificationnotify answers that recipient with
     */
    public void nnAnswers(final String emailAddress, final int status) {
        recipientAnswers.add(contexts.stubFor(post(urlPathMatching(ANY_NOTIFICATION_PATH))
                .atPriority(ONE_RECIPIENT)
                .withRequestBody(matchingJsonPath("$.sendToAddress", equalTo(emailAddress)))
                .willReturn(aResponse().withStatus(status))));
    }

    /**
     * notificationnotify stops answering any one recipient specially and accepts every e-mail.
     *
     * <p>The stubs are withdrawn by identity rather than by resetting the server, because what a
     * resend has to be asserted on is the requests already received: a reset that forgot them would
     * take with it the evidence that the second POST went to the same path as the first.
     */
    public void nnRecovers() {
        recipientAnswers.forEach(contexts::removeStub);
        recipientAnswers.clear();
    }

    /**
     * Every {@code send-email-notification} this stack has been sent.
     *
     * @return the path, media type and body of each, in arrival order
     */
    public List<SentEmail> emailsSent() {
        return contexts.findAll(postRequestedFor(urlPathMatching(ANY_NOTIFICATION_PATH))).stream()
                .map(request -> new SentEmail(request.getUrl(),
                        request.getHeader("Content-Type"), request.getBodyAsString()))
                .toList();
    }

    /**
     * The path one notification's e-mail is asked for under, as the contract writes it.
     *
     * @param notificationId the identity the row was minted with
     * @return the command path carrying it
     */
    public static String notificationPathFor(final UUID notificationId) {
        return NotificationNotifyClient.COMMAND_PATH
                .replace("{notificationId}", notificationId.toString());
    }

    /**
     * One e-mail as notificationnotify received it.
     *
     * @param path        the command path, whose last segment is the notification identity
     * @param contentType the media type the framework routes the command on
     * @param body        the command body, verbatim
     */
    public record SentEmail(String path, String contentType, String body) {
    }

    // --- the file service ------------------------------------------------------------------------

    /**
     * Whether the payload rows for a file id are both there.
     *
     * @param fileId the id the batch minted
     * @return true where the {@code content} and {@code metadata} rows both exist
     */
    public boolean payloadStoredUnder(final UUID fileId) {
        return rows("SELECT count(*) FROM content WHERE file_id = :fileId", fileId) == 1
                && rows("SELECT count(*) FROM metadata WHERE file_id = :fileId", fileId) == 1;
    }

    /**
     * The metadata row written beside a payload.
     *
     * @param fileId the id the batch minted
     * @return the metadata JSON, or empty where there is no row
     */
    public String metadataUnder(final UUID fileId) {
        return fileService.sql("SELECT metadata::text FROM metadata WHERE file_id = :fileId")
                .param("fileId", fileId)
                .query(String.class)
                .optional()
                .orElse("");
    }

    /**
     * The text stored under a file id, as the bytes the file service holds.
     *
     * <p>The morning report's attachment is a CSV rather than a render payload, and it is written
     * through the same two inserts the nightly run's payload is - so what is read back here is the
     * {@code content} row itself and not the string a sink happened to build.
     *
     * @param fileId the id the sink minted before it wrote
     * @return the stored text, or empty where nothing was written under that id
     */
    public String textUnder(final UUID fileId) {
        return fileService.sql("SELECT content FROM content WHERE file_id = :fileId")
                .param("fileId", fileId)
                .query((rs, rowNumber) -> new String(rs.getBytes("content"), StandardCharsets.UTF_8))
                .optional()
                .orElse("");
    }

    private long rows(final String sql, final UUID fileId) {
        return fileService.sql(sql).param("fileId", fileId).query(Long.class).single();
    }

    // --- lifecycle -------------------------------------------------------------------------------

    /**
     * Forgets every stub and every recorded request, and puts the two catch-alls back.
     *
     * <p>The ordinary night is the one both downstreams accept, so that is what a case starts from
     * and a case about a refusal says which refusal it means. A stub is not a request: a run the
     * flag stopped still reaches no socket, which is what {@code FlagGateEndToEndIT} asserts on the
     * recorded requests rather than on the stubs.
     */
    public void reset() {
        contexts.resetAll();
        recipientAnswers.clear();
        sdgAcceptsEveryRequest();
        nnAcceptsEveryEmail();
    }

    @Override
    public void close() {
        contexts.stop();
    }

    /**
     * The file-service database, created and seeded once per JVM.
     *
     * <p>Its own database rather than the shared one, because this service is write-only against a
     * schema it does not own and mixing the framework's two tables into the processed log's database
     * would make the second datasource a fiction.
     */
    private static synchronized String fileServiceUrl() {
        if (fileServiceDatabaseUrl == null) {
            final String url = PostgresTestSupport.createEmptyDatabase(DATABASE);
            applyFileServiceSchema(url);
            fileServiceDatabaseUrl = url;
        }
        return fileServiceDatabaseUrl;
    }

    private static DataSource fileServiceDataSource() {
        return DataSourceBuilder.create()
                .url(fileServiceUrl())
                .username(PostgresTestSupport.username())
                .password(PostgresTestSupport.password())
                .build();
    }

    private static void applyFileServiceSchema(final String url) {
        final String ddl;
        try {
            ddl = Files.readString(FILE_SERVICE_SCHEMA, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new IllegalStateException("could not read " + FILE_SERVICE_SCHEMA, unreadable);
        }
        try (Connection connection = DriverManager.getConnection(
                        url, PostgresTestSupport.username(), PostgresTestSupport.password());
                Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        } catch (final SQLException failed) {
            throw new IllegalStateException(
                    "could not apply " + FILE_SERVICE_SCHEMA + " to " + url, failed);
        }
    }
}
