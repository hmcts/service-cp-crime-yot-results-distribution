package uk.gov.hmcts.cp.yotresultsdistribution.adapter.notificationnotify;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPause;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.progression.ProgressionCommandGateway;
import uk.gov.hmcts.cp.yotresultsdistribution.application.NotificationOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.SubmissionFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.support.AdjustableClock;

/**
 * The one conversation this service has with notificationnotify, against a real socket.
 *
 * <p>Somebody else's contract, so it is asserted on the wire rather than against a mock that would
 * agree with whatever the client did. The vendored API-side schema in
 * {@code specs/002-consolidate-progression-leg/contracts/notificationnotify/notificationnotify.email.json}
 * is read by this suite rather than quoted in a comment: the body this service sends is validated
 * against it, so a re-vendoring that moves the contract shows up here rather than at 18:00.
 *
 * <p><strong>The identity is the path parameter and is not in the body.</strong> The API-side schema
 * is {@code additionalProperties: false} and declares no {@code notificationId}; the framework lifts
 * it off the path and adds it before the internal
 * {@code notificationnotify.send-email-notification} command reaches its handler, which is what the
 * handler-side schema beside it records. So a body carrying one of its own would be a 400 rather
 * than a field notificationnotify ignored, and this suite asserts both halves of that: the id is in
 * the path, and the four fields in the body are the four the contract declares.
 *
 * <p><strong>202 and nothing else is success</strong>, the same rule 001 holds progression to and
 * the renderer's client holds systemdocgenerator to. A 2xx that is not 202 means something other
 * than the command endpoint answered - a proxy, or a route that no longer reaches it - and settling
 * the row ACCEPTED on it would record a Youth Offending Team as told about a register nobody was
 * asked to send, which is the silent-success failure mode this service was commissioned to end.
 *
 * <p><strong>A retry reuses the same {@code notificationId}, which is the whole reason it is in the
 * path.</strong> notificationnotify keys its {@code Notification} aggregate on that id, so a second
 * POST under the id the row was minted with reaches the attempt it is retrying; a fresh one would
 * send a second e-mail to the same recipient (research section 10). The row is minted before the
 * first POST and is handed to this client already persisted, so the client has no id of its own to
 * mint and this suite proves it does not invent one.
 *
 * <p><strong>The client asks once.</strong> The taxonomy is shared - that is what
 * {@code retry_taxonomy_matches_the_submission_client} states, by putting the same status in front
 * of this client and the submission gateway and reading both classifications - but the waiting and
 * the counting are not this class's: {@code RegisterNotifier.send} is handed a row and a caller and
 * is told nothing about what is left of the run, so a loop here would spend a budget it cannot see.
 *
 * @see <a href="file:../../../../../../../../../specs/002-consolidate-progression-leg/contracts/README.md">the
 *     vendored notificationnotify contracts</a>
 */
@DisplayName("notificationnotify client")
class NotificationNotifyClientTest {

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

    /** The header notificationnotify says when to come back in, honoured on any retryable answer. */
    private static final String RETRY_AFTER = "Retry-After";

    /** Three seconds, in the delta-seconds form this service acts on and no other. */
    private static final String RETRY_AFTER_SECONDS = "3";

    /** The row's own identity, minted and persisted before the POST and reused on every retry. */
    private static final UUID NOTIFICATION_ID =
            UUID.fromString("3f4b8c07-1d92-4e6a-b5c8-7a0d2e9f4b61");

    /** The batch whose document this notification carries; deliberately not the path parameter. */
    private static final UUID BATCH_ID = UUID.fromString("8a1d5e73-40b2-4c96-8f1e-3d7a9c05b264");

    /** The rendered register's file-service id, which is how the document is attached. */
    private static final UUID DOCUMENT_FILE_ID =
            UUID.fromString("f0b23c8d-5a49-4e71-b6c2-9d10e485a37f");

    /** The template's UUID, resolved from configuration at startup (defect fix P9). */
    private static final UUID TEMPLATE_ID =
            UUID.fromString("c71e4a20-6b3d-4f89-9a52-08d1c6e73b4f");

    /** The template's logical name, which the row records and the body never carries. */
    private static final String TEMPLATE_NAME = "cr_standard";

    /** The recipient's {@code emailAddress1}. A component, so it never reaches a log at INFO+. */
    private static final String EMAIL_ADDRESS = "yot.inbox@example-yot.test";

    /** The recipient's name, greeted by the template as {@code personalisation.yotsName}. */
    private static final String RECIPIENT_NAME = "Example Youth Offending Team";

    /** The identity a run that names no user is made under; a configured secret, never logged. */
    private static final String SYSTEM_USER_ID = "b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234";

    /** The user the night's run is attributed to, distinct so the two cannot be confused. */
    private static final String SHARING_USER = "0b7a5c2e-4d19-4a6b-8c30-9e1f5d7b2a48";

    /** The run's caller, resolved once and passed through unchanged. */
    private static final CallerIdentity CALLER =
            new CallerIdentity(Optional.of(UUID.fromString(SHARING_USER)));

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /**
     * The waiting the submission client would do, made into nothing.
     *
     * <p>The comparison below is about what one answer <em>means</em>, so neither client is given
     * the chance to spend a second on it.
     */
    private static final RetryPause NO_WAITING = duration -> {};

    /** The shared contract mapper, so the body is read exactly as every other JSON is. */
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
     * "could not fetch the schema, so nothing was checked" is the shape that passes for ever, and
     * one that reached the internet from a test would be worse. It is the same rule, and the same
     * mechanism, {@code OutboundContractValidator} resolves the frozen progression contract with.
     *
     * <p>The copy is the one already committed under {@code contracts/progression/}, which publishes
     * this exact identity in its own {@code id} - it is the same framework file, vendored once for
     * the register document and reused here rather than written a second time. The resolver honours
     * {@code classpath:} and nothing else; a {@code file:} mapping is passed over and the original
     * {@code http://} IRI is what gets opened.
     */
    private static final Map<String, String> VENDORED_REFS = Map.of(
            "http://justice.gov.uk/domain/core/common/definitions.json",
            "classpath:contracts/progression/definitions.json");

    /**
     * The persisted row every case sends, exactly as {@code RegisterNotifierService} mints it.
     *
     * <p>PENDING with no status line and no {@code sentAt}, because the row exists before the POST
     * is answered: what was attempted is the evidence.
     */
    private static final RegisterNotification NOTIFICATION = new RegisterNotification(
            NOTIFICATION_ID,
            BATCH_ID,
            EMAIL_ADDRESS,
            RECIPIENT_NAME,
            TEMPLATE_NAME,
            TEMPLATE_ID,
            NotificationStatus.PENDING,
            null,
            null,
            0);

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

    private NotificationNotifyClient client() {
        final SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return new NotificationNotifyClient(
                RestClient.builder()
                        .baseUrl(notificationNotify.baseUrl())
                        .requestFactory(requestFactory)
                        .build(),
                SYSTEM_USER_ID,
                MAPPER);
    }

    /** The command's path for one notification, which is the id and nothing else. */
    private static String commandPath(final UUID notificationId) {
        return COMMAND_PATH_PREFIX + notificationId;
    }

    private void commandAnswering(final int status) {
        notificationNotify.stubFor(post(urlEqualTo(commandPath(NOTIFICATION_ID)))
                .willReturn(aResponse().withStatus(status)));
    }

    /**
     * The same answer, carrying a {@code Retry-After} exactly as notificationnotify would send one.
     *
     * @param status     the status the command is refused with
     * @param retryAfter the header's value, verbatim
     */
    private void commandAnswering(final int status, final String retryAfter) {
        notificationNotify.stubFor(post(urlEqualTo(commandPath(NOTIFICATION_ID)))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader(RETRY_AFTER, retryAfter)));
    }

    /**
     * Sends the recipient's e-mail and states, as an assertion, that the command was accepted.
     *
     * <p>Every case about the wire goes through here rather than calling the client directly, so a
     * client that refuses a request the contract accepts fails on a sentence about the contract
     * rather than on whatever exception it happened to raise.
     */
    private NotificationOutcome sendEmail(final CallerIdentity caller) {
        final AtomicReference<NotificationOutcome> outcome = new AtomicReference<>();
        assertThatCode(() -> outcome.set(client().send(NOTIFICATION, DOCUMENT_FILE_ID, caller)))
                .as("notificationnotify answered 202, which is the contract's one success")
                .doesNotThrowAnyException();
        return outcome.get();
    }

    /** The body the command actually carried, parsed. */
    private JsonNode sentBody() {
        return MAPPER.readTree(sentBodyText());
    }

    /**
     * The same body as the characters that went down the socket, unparsed.
     *
     * <p>Parsing normalises away exactly what a byte-identity claim is about - the order the four
     * fields were written in, and whether anything was written between them - so the case that
     * pins those reads this instead.
     *
     * @return the request body, verbatim
     */
    private String sentBodyText() {
        return notificationNotify
                .findAll(postRequestedFor(urlEqualTo(commandPath(NOTIFICATION_ID))))
                .getFirst()
                .getBodyAsString();
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

    private static List<String> declaredProperties(final JsonNode schema) {
        return List.copyOf(schema.get("properties").propertyNames());
    }

    private static List<String> requiredProperties(final JsonNode schema) {
        final List<String> names = new ArrayList<>();
        schema.get("required").forEach(name -> names.add(name.stringValue()));
        return names;
    }

    @Nested
    @DisplayName("the command on the wire")
    class Command {

        @Test
        @DisplayName("carries the contract path, media type and identity header")
        void the_command_carries_the_contract_path_media_type_and_identity() {
            commandAnswering(ACCEPTED);

            sendEmail(CALLER);

            notificationNotify.verify(postRequestedFor(urlEqualTo(commandPath(NOTIFICATION_ID)))
                    .withHeader("Content-Type", equalTo(EMAIL_MEDIA_TYPE))
                    .withHeader(IDENTITY_HEADER, equalTo(SHARING_USER)));
        }

        @Test
        @DisplayName("is made as the configured identity when the run names nobody")
        void the_command_is_made_as_the_configured_identity_when_the_run_names_nobody() {
            commandAnswering(ACCEPTED);

            sendEmail(CallerIdentity.SYSTEM);

            notificationNotify.verify(postRequestedFor(urlEqualTo(commandPath(NOTIFICATION_ID)))
                    .withHeader(IDENTITY_HEADER, equalTo(SYSTEM_USER_ID)));
        }

        /**
         * The path parameter is the row's own identity and not the batch's. It is the key of
         * notificationnotify's aggregate, so a path carrying the batch id would give every recipient
         * of one register the same aggregate - and the second POST would be read as a retry of the
         * first recipient's e-mail.
         */
        @Test
        @DisplayName("puts the notification's own id in the path, not the batch's")
        void the_path_carries_the_notifications_own_id() {
            commandAnswering(ACCEPTED);

            sendEmail(CALLER);

            assertThat(notificationNotify.getAllServeEvents())
                    .singleElement()
                    .satisfies(served -> assertThat(served.getRequest().getUrl())
                            .as("the aggregate is keyed on the notification id, not on the batch")
                            .isEqualTo(commandPath(NOTIFICATION_ID)));
        }

        /**
         * The four fields, verbatim. Two are the row's own record of what was configured - the
         * template and the recipient - and two are what makes the e-mail this batch's: the rendered
         * document, attached by file-service id so a register about children never travels through
         * this service twice, and the name the template greets.
         */
        @Test
        @DisplayName("sends the four fields the contract declares and no fifth")
        void the_body_is_the_four_fields_the_contract_declares() {
            commandAnswering(ACCEPTED);

            sendEmail(CALLER);

            final JsonNode body = sentBody();
            assertThat(List.copyOf(body.propertyNames()))
                    .as("a fifth field is a change to somebody else's contract")
                    .containsExactlyInAnyOrder(
                            "templateId", "sendToAddress", "fileId", "personalisation");
            assertThat(body.get("templateId").stringValue()).isEqualTo(TEMPLATE_ID.toString());
            assertThat(body.get("sendToAddress").stringValue()).isEqualTo(EMAIL_ADDRESS);
            assertThat(body.get("fileId").stringValue()).isEqualTo(DOCUMENT_FILE_ID.toString());
            assertThat(body.get("personalisation").get("yotsName").stringValue())
                    .isEqualTo(RECIPIENT_NAME);
        }

        /**
         * The register leg's body, to the character.
         *
         * <p>The claim the shared request builder has to keep, said in the only form that cannot
         * quietly weaken: the four fields, in this order, with nothing between them. Every other
         * case here reads a parsed body, which normalises away the order the builder wrote and
         * would go on passing if a refactor reordered or reshaped it - and reordering somebody
         * else's command body is a change to their contract whether or not their parser tolerates
         * it. This is the assertion that says the report's arrival changed nothing on this path.
         */
        @Test
        @DisplayName("is byte for byte the body the register leg has always sent")
        void the_body_is_byte_for_byte_what_the_register_leg_has_always_sent() {
            commandAnswering(ACCEPTED);

            sendEmail(CALLER);

            assertThat(sentBodyText())
                    .as("a reordered or re-spelled body is a change to notificationnotify's "
                            + "contract made inside a refactor about a different e-mail")
                    .isEqualTo("{\"templateId\":\"" + TEMPLATE_ID + "\","
                            + "\"sendToAddress\":\"" + EMAIL_ADDRESS + "\","
                            + "\"fileId\":\"" + DOCUMENT_FILE_ID + "\","
                            + "\"personalisation\":{\"yotsName\":\"" + RECIPIENT_NAME + "\"}}");
        }

        /**
         * The absence, stated on its own, because it is the one thing about this body that is easy
         * to get wrong: the handler-side schema vendored beside the API-side one <em>does</em>
         * declare {@code notificationId}, and reading that copy instead would produce a body
         * notificationnotify refuses.
         */
        @Test
        @DisplayName("keeps the notification id out of the body, where the schema refuses it")
        void the_body_carries_no_notification_id() {
            commandAnswering(ACCEPTED);

            sendEmail(CALLER);

            assertThat(sentBody().has("notificationId"))
                    .as("the framework adds it from the path before the handler sees the command")
                    .isFalse();
        }

        /**
         * The vendored schema is {@code additionalProperties: false}, so a field this service
         * invents is a 400 rather than a field notificationnotify ignores. Reading the schema here
         * rather than restating it means a re-vendoring that moves the contract fails this suite.
         */
        @Test
        @DisplayName("sends a body the vendored API-side email schema declares")
        void the_body_is_one_the_vendored_api_side_schema_declares() {
            commandAnswering(ACCEPTED);

            sendEmail(CALLER);

            final JsonNode body = sentBody();
            assertThat(refusalsOf(body))
                    .as("the body notificationnotify is sent has to be one its schema accepts")
                    .isEmpty();
            final JsonNode schema = MAPPER.readTree(vendoredText(EMAIL_SCHEMA));
            final List<String> sent = List.copyOf(body.propertyNames());
            assertThat(sent)
                    .as("the schema refuses additional properties")
                    .isSubsetOf(declaredProperties(schema));
            assertThat(sent)
                    .as("every property the schema requires")
                    .containsAll(requiredProperties(schema));
        }

        /**
         * The check has teeth, and this is what says so: the same body with the id put back in is
         * refused by the vendored schema, so the case above is an assertion about the contract
         * rather than a validation that would pass on anything.
         */
        @Test
        @DisplayName("would be refused by the vendored schema if it carried the id")
        void the_vendored_schema_refuses_a_body_carrying_the_notification_id() {
            commandAnswering(ACCEPTED);

            sendEmail(CALLER);

            final ObjectNode withTheIdPutBack = (ObjectNode) sentBody();
            withTheIdPutBack.put("notificationId", NOTIFICATION_ID.toString());

            assertThat(refusalsOf(withTheIdPutBack))
                    .as("additionalProperties: false is why the id is the path parameter")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("answers ACCEPTED carrying the 202 the row records")
        void an_accepted_command_answers_accepted_carrying_its_status() {
            commandAnswering(ACCEPTED);

            assertThat(sendEmail(CALLER))
                    .as("the row records what answered, and only 202 means an e-mail is on its way")
                    .isEqualTo(new NotificationOutcome(NotificationStatus.ACCEPTED, ACCEPTED));
        }
    }

    @Nested
    @DisplayName("202 and nothing else is success")
    class ContractSuccess {

        /**
         * A 2xx that is not 202 means something other than the command endpoint answered. Settling
         * the row ACCEPTED on it would record a Youth Offending Team as told about a register
         * nobody was asked to send, and no resend would ever revisit the row.
         */
        @ParameterizedTest(name = "{0} is not the success the contract defines")
        @ValueSource(ints = {200, 201, 204})
        @DisplayName("only_202_is_success")
        void only_202_is_success(final int status) {
            commandAnswering(status);

            assertThat(catchThrowable(() -> client().send(NOTIFICATION, DOCUMENT_FILE_ID, CALLER)))
                    .isInstanceOf(NotificationFailedException.class)
                    .asInstanceOf(InstanceOfAssertFactories.type(NotificationFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .as("asking again cannot turn a 200 into a 202")
                                .isEqualTo(FailureClassification.NON_TRANSIENT);
                        assertThat(failure.responseCode())
                                .as("the row records what answered, not what was hoped for")
                                .isEqualTo(OptionalInt.of(status));
                    });
        }

        @ParameterizedTest(name = "{0} is a refusal the recipient's row is failed on")
        @ValueSource(ints = {400, 401, 403, 404, 422})
        @DisplayName("a refusal is a failure carrying its status")
        void a_refusal_is_a_failure_carrying_its_status(final int status) {
            commandAnswering(status);

            assertThat(catchThrowable(() -> client().send(NOTIFICATION, DOCUMENT_FILE_ID, CALLER)))
                    .isInstanceOf(NotificationFailedException.class)
                    .asInstanceOf(InstanceOfAssertFactories.type(NotificationFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.NON_TRANSIENT);
                        assertThat(failure.responseCode()).isEqualTo(OptionalInt.of(status));
                    });
        }
    }

    @Nested
    @DisplayName("what another attempt could change")
    class Taxonomy {

        /**
         * The C3 promise, held to across two contexts. It is not enough that this client's list of
         * retryable statuses reads like the submission client's: the two are put in front of the
         * same status and their classifications are compared, so a client that grew an opinion of
         * its own about a 429 fails here rather than at 18:00 on a busy notifier.
         */
        @ParameterizedTest(name = "{0} is classified as the submission client classifies it")
        @ValueSource(ints = {200, 201, 204, 400, 401, 403, 404, 408, 422, 429, 500, 502, 503})
        @DisplayName("retry_taxonomy_matches_the_submission_client")
        void retry_taxonomy_matches_the_submission_client(final int status) {
            assertThat(notifierClassificationOf(status))
                    .as("both clients hold the same RetryPolicy, so neither can decide on its own "
                            + "what is worth asking again")
                    .isEqualTo(submissionClassificationOf(status));
        }

        @ParameterizedTest(name = "{0} is worth asking again")
        @ValueSource(ints = {408, 429, 500, 502, 503})
        @DisplayName("a transient answer is handed back for the run to ask again")
        void a_transient_answer_is_handed_back_for_the_run_to_ask_again(final int status) {
            commandAnswering(status);

            assertThat(catchThrowable(() -> client().send(NOTIFICATION, DOCUMENT_FILE_ID, CALLER)))
                    .isInstanceOf(NotificationFailedException.class)
                    .asInstanceOf(InstanceOfAssertFactories.type(NotificationFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.TRANSIENT);
                        assertThat(failure.responseCode()).isEqualTo(OptionalInt.of(status));
                    });
        }

        /**
         * The header handed back with the refusal, because the waiting belongs to the run.
         *
         * <p>A {@code Retry-After} is notificationnotify saying when it expects to be able to take
         * the command, and this client is the only participant that sees it - so it reads it, in
         * the delta-seconds-only form the shared policy defines, and hands it on with the
         * classification. The object holding the attempt budget is what decides whether there is
         * room for another attempt and spends the wait, bounded by {@code max-backoff}.
         */
        @ParameterizedTest(name = "a {0} carrying Retry-After hands the wait back")
        @ValueSource(ints = {429, 503})
        @DisplayName("a Retry-After is read and handed back with the refusal")
        void a_retry_after_is_handed_back_with_the_refusal(final int status) {
            commandAnswering(status, RETRY_AFTER_SECONDS);

            assertThat(catchThrowable(() -> client().send(NOTIFICATION, DOCUMENT_FILE_ID, CALLER)))
                    .asInstanceOf(InstanceOfAssertFactories.type(NotificationFailedException.class))
                    .satisfies(failure -> assertThat(failure.retryAfter())
                            .as("read on any retryable answer rather than on a 429 alone: a 503 "
                                    + "carrying one is a service saying when it expects to be back")
                            .contains(Duration.ofSeconds(3)));
        }

        /**
         * RFC 9110 also permits an HTTP-date, and acting on one would mean measuring another
         * system's clock against this pod's. The shared policy recognises the form before it reads
         * it, so an unusable value is the same outcome as no header at all: the back-off.
         */
        @Test
        @DisplayName("a Retry-After this service cannot act on is not handed back")
        void a_retry_after_this_service_cannot_act_on_is_not_handed_back() {
            commandAnswering(503, "Wed, 21 Oct 2026 07:28:00 GMT");

            assertThat(catchThrowable(() -> client().send(NOTIFICATION, DOCUMENT_FILE_ID, CALLER)))
                    .asInstanceOf(InstanceOfAssertFactories.type(NotificationFailedException.class))
                    .satisfies(failure -> assertThat(failure.retryAfter())
                            .as("delta-seconds only, so a date falls back to the schedule rather "
                                    + "than parking a run on a remote clock")
                            .isEmpty());
        }

        /**
         * A request whose answer never arrived may still have been made, and the e-mail may already
         * be on its way. It is transient for that reason - the resend is safe because it reuses the
         * same id - and it carries no status at all: an invented one would say an attempt was
         * answered when nothing answered.
         */
        @Test
        @DisplayName("an unanswered attempt is transient and records no status")
        void an_unanswered_attempt_is_transient_and_records_no_status() {
            notificationNotify.stubFor(post(urlEqualTo(commandPath(NOTIFICATION_ID)))
                    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            assertThat(catchThrowable(() -> client().send(NOTIFICATION, DOCUMENT_FILE_ID, CALLER)))
                    .isInstanceOf(NotificationFailedException.class)
                    .asInstanceOf(InstanceOfAssertFactories.type(NotificationFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.TRANSIENT);
                        assertThat(failure.responseCode()).isEmpty();
                    });
        }

        /**
         * The division of labour, asserted rather than assumed. This client is handed a row and a
         * caller and is told nothing about what is left of the run, so a loop here would spend a
         * budget it cannot see; the service that holds the run holds the resending.
         */
        @Test
        @DisplayName("asks once and leaves the resending to the run that holds the rows")
        void the_client_asks_once_and_leaves_the_resending_to_the_run() {
            commandAnswering(503);

            assertThat(catchThrowable(() -> client().send(NOTIFICATION, DOCUMENT_FILE_ID, CALLER)))
                    .isInstanceOf(NotificationFailedException.class);

            assertThat(notificationNotify.getAllServeEvents())
                    .as("the run holds the rows, and the resending is the run's")
                    .hasSize(1);
        }

        private FailureClassification notifierClassificationOf(final int status) {
            commandAnswering(status);
            final Throwable refused =
                    catchThrowable(() -> client().send(NOTIFICATION, DOCUMENT_FILE_ID, CALLER));
            assertThat(refused)
                    .as("notificationnotify answered %s, which is not the contract's 202", status)
                    .isInstanceOf(NotificationFailedException.class);
            return ((NotificationFailedException) refused).classification();
        }

        /**
         * The submission client's own verdict on the same status, made against the same server.
         *
         * <p>One attempt, so the two are compared on the same question - what one answer means -
         * rather than on how many times each is willing to ask. The waiting is a no-op and the
         * deadline is unreachable, so neither can end this case.
         */
        private FailureClassification submissionClassificationOf(final int status) {
            notificationNotify.stubFor(post(urlEqualTo(ProgressionCommandGateway.PATH))
                    .willReturn(aResponse().withStatus(status)));
            final Instant now = Instant.parse("2026-09-04T18:00:00Z");
            final SimpleClientHttpRequestFactory requestFactory =
                    new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
            requestFactory.setReadTimeout(READ_TIMEOUT);
            final ProgressionCommandGateway gateway = new ProgressionCommandGateway(
                    RestClient.builder()
                            .baseUrl(notificationNotify.baseUrl())
                            .requestFactory(requestFactory)
                            .build(),
                    SYSTEM_USER_ID,
                    Map.of(),
                    new RetryPolicy(1, Duration.ofMillis(500), Duration.ofSeconds(5),
                            CONNECT_TIMEOUT.plus(READ_TIMEOUT)),
                    NO_WAITING,
                    AdjustableClock.startingAt(now));
            final Throwable refused = catchThrowable(() -> gateway.post(
                    "{}".getBytes(StandardCharsets.UTF_8),
                    CALLER,
                    now.plus(Duration.ofHours(1))));
            assertThat(refused).isInstanceOf(SubmissionFailedException.class);
            return ((SubmissionFailedException) refused).classification();
        }
    }

    /**
     * The idempotency the whole design rests on.
     *
     * <p>The row is minted with its id before the first POST and is handed to this client already
     * persisted, so a resend is the run calling {@code send} again with the same row. What has to
     * hold is that the client puts that row's id in the path both times: notificationnotify keys its
     * aggregate on it, so the second POST reaches the attempt it is retrying rather than sending a
     * second register to the same Youth Offending Team.
     */
    @Nested
    @DisplayName("a resend under the same identity")
    class SameIdentityOnRetry {

        @Test
        @DisplayName("a_retry_reuses_the_same_notification_id_in_the_path")
        void a_retry_reuses_the_same_notification_id_in_the_path() {
            commandAnswering(503);

            assertThat(catchThrowable(() -> client().send(NOTIFICATION, DOCUMENT_FILE_ID, CALLER)))
                    .isInstanceOf(NotificationFailedException.class);
            commandAnswering(ACCEPTED);
            sendEmail(CALLER);

            assertThat(notificationNotify.getAllServeEvents())
                    .as("a fresh id would send a second e-mail rather than retry the first")
                    .hasSize(2)
                    .allSatisfy(served -> assertThat(served.getRequest().getUrl())
                            .isEqualTo(commandPath(NOTIFICATION_ID)));
        }

        /**
         * The row's attempt tally moves, because the run counts what it has tried; the identity
         * does not, because that is the whole point of it. A client that took its path parameter
         * from anything that changes between attempts would fail here.
         */
        @Test
        @DisplayName("a later attempt of the same row still posts to the same path")
        void a_later_attempt_of_the_same_row_posts_to_the_same_path() {
            commandAnswering(ACCEPTED);
            final RegisterNotification retried = new RegisterNotification(
                    NOTIFICATION_ID,
                    BATCH_ID,
                    EMAIL_ADDRESS,
                    RECIPIENT_NAME,
                    TEMPLATE_NAME,
                    TEMPLATE_ID,
                    NotificationStatus.FAILED,
                    503,
                    Instant.parse("2026-09-04T18:00:07Z"),
                    1);

            assertThatCode(() -> client().send(retried, DOCUMENT_FILE_ID, CALLER))
                    .as("a resend of a FAILED row is the contract's one success again")
                    .doesNotThrowAnyException();

            notificationNotify.verify(postRequestedFor(urlEqualTo(commandPath(NOTIFICATION_ID))));
        }
    }
}
