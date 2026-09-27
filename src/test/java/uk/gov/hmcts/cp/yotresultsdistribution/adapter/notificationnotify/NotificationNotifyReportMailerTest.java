package uk.gov.hmcts.cp.yotresultsdistribution.adapter.notificationnotify;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.PathType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.application.NotificationOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.MailOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.MailStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportMail;

/**
 * The report's send, against a real socket and the contract 002 vendored.
 *
 * <p>Somebody else's contract, so the body is validated against
 * {@code specs/002-consolidate-progression-leg/contracts/notificationnotify/notificationnotify.email.json}
 * rather than against a mock that would agree with whatever this adapter sent. Nothing is
 * re-vendored: the 002 copy is the single copy, as {@code contracts/README.md} states.
 *
 * <p><strong>The counts travel in the body because the schema says they may.</strong> The body is
 * {@code additionalProperties: false} with {@code templateId} and {@code sendToAddress} required,
 * but {@code personalisation} is itself an object with an empty {@code properties} block and
 * {@code additionalProperties: true} - which is the whole of why FR-006 can put the five counts and
 * the window in the e-mail rather than only in the attachment. That is asserted of the vendored
 * file here, not assumed: a re-vendoring that closed it would land on this case rather than on a
 * morning's report.
 *
 * <p><strong>The register path is in this suite too</strong>, once: the two adapters compose their
 * request through one package-private builder, so the case that matters is that the register leg's
 * body is still exactly its four fields with {@code personalisation.yotsName}.
 * {@code NotificationNotifyClientTest} asserts the rest of that path and stays green unchanged,
 * which is the other half of the same claim.
 *
 * <p>The vendored-schema fixture below is deliberately a second copy of that suite's: the register
 * suite is the one this refactor must leave untouched, and reaching into it to share a helper is
 * exactly the change that would stop its green run meaning anything.
 */
@DisplayName("notificationnotify report mailer")
class NotificationNotifyReportMailerTest {

    /** The command's path under the notificationnotify context, one notification id short. */
    private static final String COMMAND_PATH_PREFIX =
            "/notificationnotify-command-api/command/api/rest/notificationnotify/notifications/";

    /** The command's vendor media type; the framework routes on it, so it is not a formality. */
    private static final String EMAIL_MEDIA_TYPE =
            "application/vnd.notificationnotify.email+json";

    /** The CPP identity header. Its value is a secret or a user, and neither is ever logged. */
    private static final String IDENTITY_HEADER = "CJSCPPUID";

    /** The one status the contract calls success. */
    private static final int ACCEPTED = 202;

    /** The identity this mail is asked for under, minted by the sink before the POST. */
    private static final UUID NOTIFICATION_ID =
            UUID.fromString("5d2f7a10-8c43-4b96-a1e7-2f9c0d6b4853");

    /** The CSV's file-service id, written down before the write that produced it. */
    private static final UUID FILE_ID = UUID.fromString("c4e70b18-6d92-4a35-8f60-1b5d3e9a7c02");

    private static final UUID TEMPLATE_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    /** Support's own inbox. A component, so it never reaches a log at INFO or above. */
    private static final String SEND_TO_ADDRESS = "support@example.invalid";

    /** The identity a run that names no user is made under; a configured secret, never logged. */
    private static final String SYSTEM_USER_ID = "b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /** The shared contract mapper, so the body is written exactly as every other JSON is. */
    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    /** Where the consumed notificationnotify contracts are vendored, with their provenance. */
    private static final Path VENDORED = Path.of(
            "specs", "002-consolidate-progression-leg", "contracts", "notificationnotify");

    /** The API-side schema: the body of the POST, and the only one this service ever sends. */
    private static final String EMAIL_SCHEMA = "notificationnotify.email.json";

    /**
     * The framework core definitions the API-side schema {@code $ref}s, mapped to a vendored copy.
     *
     * <p>Nothing here may go to the network to resolve a reference: a validation that degraded to
     * "could not fetch the schema, so nothing was checked" is the shape that passes for ever.
     */
    private static final Map<String, String> VENDORED_REFS = Map.of(
            "http://justice.gov.uk/domain/core/common/definitions.json",
            "classpath:contracts/progression/definitions.json");

    /** The five counts and the window, exactly as the sink composes them. */
    private static final Map<String, String> PERSONALISATION = Map.of(
            "request_failed", "1",
            "request_late", "0",
            "batch_late", "2",
            "batch_failed", "0",
            "notification_failed", "1",
            "window_from", "2026-09-14T06:00:00Z",
            "window_to", "2026-09-15T06:00:00Z");

    private static final ReportMail MAIL = new ReportMail(
            NOTIFICATION_ID, TEMPLATE_ID, SEND_TO_ADDRESS, FILE_ID, PERSONALISATION);

    private WireMockServer notificationNotify;

    @BeforeEach
    void startNotificationNotify() {
        notificationNotify = new WireMockServer(wireMockConfig().dynamicPort());
        notificationNotify.start();
    }

    @AfterEach
    void stopNotificationNotify() {
        notificationNotify.stop();
    }

    @Test
    void the_report_body_validates_against_the_vendored_schema() {
        commandAnswering(ACCEPTED);

        send(MAIL);

        final JsonNode body = sentBody(NOTIFICATION_ID);
        assertThat(refusalsOf(body))
                .as("the body is additionalProperties: false, so a fifth field is a 400 rather "
                        + "than a field notificationnotify ignores")
                .isEmpty();
        assertThat(List.copyOf(body.propertyNames()))
                .as("four fields, and the identity is the path parameter rather than one of them")
                .containsExactlyInAnyOrder(
                        "templateId", "sendToAddress", "fileId", "personalisation");
        assertThat(body.get("templateId").stringValue()).isEqualTo(TEMPLATE_ID.toString());
        assertThat(body.get("sendToAddress").stringValue()).isEqualTo(SEND_TO_ADDRESS);
        assertThat(body.get("fileId").stringValue())
                .as("the exception list travels by reference, as the register PDF does")
                .isEqualTo(FILE_ID.toString());
    }

    @Test
    void arbitrary_personalisation_keys_are_contract_legal() {
        final JsonNode schema = MAPPER.readTree(vendoredText(EMAIL_SCHEMA));
        final JsonNode personalisation = schema.get("properties").get("personalisation");

        assertThat(personalisation.get("type").stringValue()).isEqualTo("object");
        assertThat(personalisation.get("properties").isEmpty())
                .as("an empty properties block: the schema names no personalisation key at all")
                .isTrue();
        assertThat(personalisation.get("additionalProperties").booleanValue())
                .as("and admits any: this is what lets the five counts and the window travel in "
                        + "the body of a report (FR-006, scenario 4.4), inside a body that is "
                        + "otherwise closed")
                .isTrue();
        assertThat(schema.get("additionalProperties").booleanValue()).isFalse();
    }

    @Test
    void the_personalisation_values_are_strings() {
        commandAnswering(ACCEPTED);

        send(MAIL);

        final JsonNode personalisation = sentBody(NOTIFICATION_ID).get("personalisation");
        assertThat(List.copyOf(personalisation.propertyNames()))
                .containsExactlyInAnyOrderElementsOf(PERSONALISATION.keySet());
        assertThat(personalisation.properties())
                .as("Notify substitutes text, so a count sent as a JSON number is a substitution "
                        + "the template does not make")
                .allSatisfy(field -> assertThat(field.getValue().isString()).isTrue());
    }

    @Test
    void one_post_per_mail() {
        commandAnswering(ACCEPTED);

        send(MAIL);

        notificationNotify.verify(1, postRequestedFor(urlEqualTo(commandPath(NOTIFICATION_ID))));
    }

    @Test
    void the_media_type_is_application_vnd_notificationnotify_email_json() {
        commandAnswering(ACCEPTED);

        send(MAIL);

        notificationNotify.verify(postRequestedFor(urlEqualTo(commandPath(NOTIFICATION_ID)))
                .withHeader("Content-Type", equalTo(EMAIL_MEDIA_TYPE)));
    }

    @Test
    void the_cjscppuid_identity_header_is_sent() {
        commandAnswering(ACCEPTED);

        send(MAIL);

        notificationNotify.verify(postRequestedFor(urlEqualTo(commandPath(NOTIFICATION_ID)))
                .withHeader(IDENTITY_HEADER, equalTo(SYSTEM_USER_ID)));
    }

    @Test
    void two_hundred_and_two_is_the_only_success() {
        commandAnswering(ACCEPTED);

        assertThat(send(MAIL))
                .isEqualTo(new MailOutcome(MailStatus.ACCEPTED, ACCEPTED));

        stopNotificationNotify();
        startNotificationNotify();
        commandAnswering(200);

        assertThat(send(MAIL))
                .as("a 2xx that is not 202 means something other than the command endpoint "
                        + "answered - a proxy, or a route that no longer reaches it - and calling "
                        + "it a delivery would tell support an e-mail is on its way that nobody "
                        + "was asked to send")
                .isEqualTo(new MailOutcome(MailStatus.REFUSED, 200));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404, 422})
    void a_4xx_is_refused_and_never_retried(final int status) {
        commandAnswering(status);

        assertThat(send(MAIL))
                .as("the command was understood and declined, and the same command under the same "
                        + "identity will be declined again")
                .isEqualTo(new MailOutcome(MailStatus.REFUSED, status));
        notificationNotify.verify(1, postRequestedFor(urlEqualTo(commandPath(NOTIFICATION_ID))));
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 502, 503})
    void a_408_429_or_5xx_is_transient(final int status) {
        commandAnswering(status);

        assertThat(send(MAIL))
                .as("the same taxonomy the register path holds notificationnotify to, from the "
                        + "one shared policy: this is a resend for this recipient rather than a "
                        + "refusal to act on")
                .isEqualTo(new MailOutcome(MailStatus.FAILED, status));
    }

    /**
     * The absence of a loop, stated as a request count rather than as a reading of the class.
     *
     * <p>The three statuses another attempt <em>could</em> change are the ones a retry would be
     * hidden behind, so they are the ones counted: this mailer makes one attempt and answers, and
     * the resend is the support engineer's decision off the run's own line. A loop here would spend
     * a budget the scheduled run holds and cannot see.
     */
    @ParameterizedTest
    @ValueSource(ints = {408, 429, 503})
    void a_transient_status_is_one_request_and_no_retry(final int status) {
        commandAnswering(status);

        assertThat(send(MAIL)).isEqualTo(new MailOutcome(MailStatus.FAILED, status));
        notificationNotify.verify(1, postRequestedFor(urlEqualTo(commandPath(NOTIFICATION_ID))));
    }

    /**
     * The other two 2xx a proxy or a rewritten route answers with, beside the 200 already pinned.
     *
     * <p>202 and nothing else is success. A 201 or a 204 means something other than the command
     * endpoint answered, and counting either as a delivery would tell support a report is on its way
     * that nobody was asked to send.
     */
    @ParameterizedTest
    @ValueSource(ints = {201, 204})
    void two_hundred_and_one_and_two_hundred_and_four_are_refused(final int status) {
        commandAnswering(status);

        assertThat(send(MAIL))
                .as("a 2xx that is not 202 is not an acceptance, and asking again under the same "
                        + "identity cannot make it one")
                .isEqualTo(new MailOutcome(MailStatus.REFUSED, status));
        notificationNotify.verify(1, postRequestedFor(urlEqualTo(commandPath(NOTIFICATION_ID))));
    }

    @Test
    void the_register_paths_body_is_unchanged() {
        final UUID registerNotificationId =
                UUID.fromString("3f4b8c07-1d92-4e6a-b5c8-7a0d2e9f4b61");
        notificationNotify.stubFor(post(urlEqualTo(commandPath(registerNotificationId)))
                .willReturn(aResponse().withStatus(ACCEPTED)));
        final RegisterNotification notification = new RegisterNotification(
                registerNotificationId,
                UUID.fromString("8a1d5e73-40b2-4c96-8f1e-3d7a9c05b264"),
                "yot.inbox@example-yot.test",
                "Example Youth Offending Team",
                "cr_standard",
                TEMPLATE_ID,
                NotificationStatus.PENDING,
                null,
                null,
                0);

        final AtomicReference<NotificationOutcome> outcome = new AtomicReference<>();
        assertThatCode(() -> outcome.set(new NotificationNotifyClient(
                restClient(), SYSTEM_USER_ID, MAPPER)
                .send(notification, FILE_ID, new CallerIdentity(Optional.empty()))))
                .doesNotThrowAnyException();

        final JsonNode body = sentBody(registerNotificationId);
        assertThat(List.copyOf(body.propertyNames()))
                .as("the two adapters share a request builder, not a body: the register leg's four "
                        + "fields are what they were, and the report's personalisation is not on "
                        + "this path")
                .containsExactlyInAnyOrder(
                        "templateId", "sendToAddress", "fileId", "personalisation");
        assertThat(List.copyOf(body.get("personalisation").propertyNames()))
                .containsExactly("yotsName");
        assertThat(body.get("personalisation").get("yotsName").stringValue())
                .isEqualTo("Example Youth Offending Team");
        assertThat(outcome.get().status()).isEqualTo(NotificationStatus.ACCEPTED);
    }

    /**
     * The port's contract is that it <strong>answers</strong>, and it answers about everything.
     *
     * <p>Only {@code ResourceAccessException} was translated, and the body was written
     * <em>outside</em> the try at all - so a mapper that refused the body, or a client that
     * refused for any other reason, left this method as a throw. It reaches the sink's own catch,
     * which classifies it as the whole delivery having failed: one recipient's problem becomes
     * every recipient's, and the addresses after it in the list are never asked at all.
     *
     * <p>Two arms, because they are two different refusals and both were outside the one branch
     * that was handled: the serialisation of the body, and a client that refuses in its own type.
     */
    @Test
    void a_serialisation_or_client_failure_is_answered_not_thrown() {
        final ObjectMapper refusing = mock(ObjectMapper.class);
        when(refusing.writeValueAsBytes(any()))
                .thenThrow(new IllegalStateException("no serialiser for the personalisation"));
        final AtomicReference<MailOutcome> unwritable = new AtomicReference<>();

        assertThatCode(() -> unwritable.set(new NotificationNotifyReportMailer(
                restClient(), SYSTEM_USER_ID, refusing).send(aReportMail())))
                .as("a body that could not be written is this recipient's send failing, not the "
                        + "morning's report failing: the sink counts it and asks the next address")
                .doesNotThrowAnyException();
        assertThat(unwritable.get())
                .as("nothing was sent and nothing answered, which is exactly UNANSWERED")
                .isEqualTo(new MailOutcome(MailStatus.UNANSWERED, null));

        final AtomicReference<MailOutcome> refused = new AtomicReference<>();
        assertThatCode(() -> refused.set(new NotificationNotifyReportMailer(
                clientThatRefusesToCall(), SYSTEM_USER_ID, MAPPER).send(aReportMail())))
                .as("and a client that refuses in a type of its own - a pool that has been shut "
                        + "down, a factory that will not build a request - is the same fact to "
                        + "whoever reads the run's line: this recipient was not told")
                .doesNotThrowAnyException();
        assertThat(refused.get())
                .isEqualTo(new MailOutcome(MailStatus.UNANSWERED, null));
    }

    /** One report mail, for the cases whose subject is not the body. */
    private static ReportMail aReportMail() {
        return new ReportMail(NOTIFICATION_ID, TEMPLATE_ID, SEND_TO_ADDRESS, FILE_ID,
                Map.of("request_failed", "1"));
    }

    /**
     * A client that refuses before a request is ever made, in a type that is not an access failure.
     *
     * @return the client
     */
    private RestClient clientThatRefusesToCall() {
        return RestClient.builder()
                .baseUrl(notificationNotify.baseUrl())
                .requestFactory((uri, httpMethod) -> {
                    throw new IllegalStateException("the connection pool has been shut down");
                })
                .build();
    }

    /**
     * Sends one report e-mail, insisting the mailer answered rather than threw.
     *
     * @param mail the mail
     * @return what it answered
     */
    private MailOutcome send(final ReportMail mail) {
        final AtomicReference<MailOutcome> outcome = new AtomicReference<>();

        assertThatCode(() -> outcome.set(new NotificationNotifyReportMailer(
                restClient(), SYSTEM_USER_ID, MAPPER).send(mail)))
                .as("the port answers how the send went rather than throwing, because one "
                        + "recipient's refusal is a resend for that recipient and not the end of "
                        + "the morning's report")
                .doesNotThrowAnyException();
        return outcome.get();
    }

    private RestClient restClient() {
        final SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(notificationNotify.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /** The command's path for one notification, which is the id and nothing else. */
    private static String commandPath(final UUID notificationId) {
        return COMMAND_PATH_PREFIX + notificationId;
    }

    private void commandAnswering(final int status) {
        notificationNotify.stubFor(post(urlEqualTo(commandPath(NOTIFICATION_ID)))
                .willReturn(aResponse().withStatus(status)));
    }

    /** The body the command actually carried, parsed. */
    private JsonNode sentBody(final UUID notificationId) {
        return MAPPER.readTree(notificationNotify
                .findAll(postRequestedFor(urlEqualTo(commandPath(notificationId))))
                .getFirst()
                .getBodyAsString());
    }

    /** The text of the vendored schema, or a failure that names the copy that is missing. */
    private static String vendoredText(final String fileName) {
        final Path schema = VENDORED.resolve(fileName);
        try {
            return Files.readString(schema, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(
                    "the vendored notificationnotify contract must be committed at " + schema,
                    unreadable);
        }
    }

    /** One vendored schema, assembled with its references resolved against the vendored copies. */
    private static Schema vendoredSchema(final String fileName) {
        final SchemaRegistryConfig config = SchemaRegistryConfig.builder()
                .formatAssertionsEnabled(Boolean.TRUE)
                .pathType(PathType.JSON_POINTER)
                .preloadSchema(true)
                .build();
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_4,
                        builder -> builder
                                .schemaRegistryConfig(config)
                                .schemaIdResolvers(resolvers -> resolvers.mappings(VENDORED_REFS)))
                .getSchema(vendoredText(fileName));
    }

    /** Every rule a body breaks, empty where it satisfies the vendored contract. */
    private static List<Error> refusalsOf(final JsonNode body) {
        return vendoredSchema(EMAIL_SCHEMA).validate(body);
    }
}
