package uk.gov.hmcts.cp.yotresultsdistribution.adapter.systemdocgenerator;

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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
import java.util.stream.Stream;
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
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPause;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.progression.ProgressionCommandGateway;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GenerationFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RenderRequest;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.SubmissionFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.support.AdjustableClock;

/**
 * The one conversation this service has with systemdocgenerator, against a real socket.
 *
 * <p>It is somebody else's contract and it is not negotiable, so it is asserted on the wire rather
 * than against a mock that would agree with whatever the client did. The vendored schema in
 * {@code specs/002-consolidate-progression-leg/contracts/systemdocgenerator/} is read by this suite
 * rather than quoted in a comment: the body this service sends is held to the properties the
 * command schema declares, so a re-vendoring that changes it shows up here rather than at 18:00.
 *
 * <p><strong>The command's body is five fields and two of them are why it exists.</strong>
 * {@code sourceCorrelationId} is the batch id, and it is the only thing that correlates an outcome
 * event back to a night's rows - a render requested without it is a document nothing can be attached
 * to. {@code originatingSource} is this service's own name, and it is what keeps progression's
 * still-deployed listener out of these documents: progression's event processor branches on
 * {@code COURT_REGISTER.equalsIgnoreCase}, so a request that borrowed its source would have this
 * service's document announced to progression's leg (research §2).
 *
 * <p><strong>202 and nothing else is success</strong>, the same rule 001 holds progression to. A 2xx
 * that is not 202 means something other than the command endpoint answered - a proxy, or a route
 * that no longer reaches it - and treating it as accepted would leave a batch GENERATING for a render
 * nothing was ever asked for, waiting on an event that cannot come.
 *
 * <p><strong>The client asks once.</strong> The taxonomy is shared - that is what
 * {@code retry_taxonomy_matches_the_submission_client} states, by putting the same status in front of
 * this client and the submission gateway and reading both classifications - but the waiting and the
 * counting are not this class's, because the only bound on them is the run deadline and
 * {@code DocumentRenderer.requestRender} is not told what is left of it.
 * {@code RegisterGenerationService} holds the deadline and therefore holds the loop (T039).
 *
 * @see <a href="file:../../../../../../../../../specs/002-consolidate-progression-leg/contracts/README.md">the
 *     vendored systemdocgenerator contracts</a>
 */
@DisplayName("systemdocgenerator client")
class SystemDocGeneratorClientTest {

    /** The command's path under the systemdocgenerator context, exactly as its RAML declares it. */
    private static final String COMMAND_PATH =
            "/systemdocgenerator-command-api/command/api/rest/systemdocgenerator/generate-document";

    /** The command's vendor media type; the framework routes on it, so it is not a formality. */
    private static final String COMMAND_MEDIA_TYPE =
            "application/vnd.systemdocgenerator.generate-document+json";

    /** The CPP identity header. Its value is a secret or a user, and neither is ever logged. */
    private static final String IDENTITY_HEADER = "CJSCPPUID";

    /** The template progression renders the register with, unchanged. */
    private static final String TEMPLATE = "OEE_Layout5";

    /** The output format, and one of the three the command schema's enum permits. */
    private static final String FORMAT = "pdf";

    /** This service's own name, and what keeps progression's listener out of these documents. */
    private static final String ORIGINATING_SOURCE = "YotResultsDistributionService";

    /** The one status the contract calls success. */
    private static final int ACCEPTED = 202;

    /** The payload this service inserted into the file service and minted the id of. */
    private static final UUID PAYLOAD_FILE_ID =
            UUID.fromString("2c6f9b41-7d18-4e5a-9f03-6b8a1d4c7e25");

    /** The batch the render is for, which travels as {@code sourceCorrelationId}. */
    private static final UUID BATCH_ID = UUID.fromString("8a1d5e73-40b2-4c96-8f1e-3d7a9c05b264");

    /** The identity a run that names no user is made under; a configured secret, never logged. */
    private static final String SYSTEM_USER_ID = "b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234";

    /** The user the night's run is attributed to, distinct so the two cannot be confused. */
    private static final String SHARING_USER = "0b7a5c2e-4d19-4a6b-8c30-9e1f5d7b2a48";

    /** The run's caller, resolved once and passed through unchanged. */
    private static final CallerIdentity CALLER =
            new CallerIdentity(Optional.of(UUID.fromString(SHARING_USER)));

    /** Stands in for whatever systemdocgenerator writes in a {@code reason}. */
    private static final String SDG_TEXT_MARKER = "SDGREASONMARKERZQX7";

    private static final Instant REQUESTED_TIME = Instant.parse("2026-09-04T17:00:05Z");
    private static final Instant GENERATED_TIME = Instant.parse("2026-09-04T17:00:41Z");
    private static final Instant FAILED_TIME = Instant.parse("2026-09-04T17:00:38Z");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /**
     * The waiting the submission client would do, made into nothing.
     *
     * <p>The comparison below is about what one answer <em>means</em>, so neither client is given
     * the chance to spend a second on it.
     */
    private static final RetryPause NO_WAITING = duration -> {};

    /** The shared contract mapper, so the answer is read exactly as every other JSON is. */
    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    /** Where the consumed contracts are vendored, with their provenance. */
    private static final Path VENDORED = Path.of(
            "specs", "002-consolidate-progression-leg", "contracts", "systemdocgenerator");

    /** The request every command case makes, and the one the assembler will make. */
    private static final RenderRequest REQUEST =
            new RenderRequest(PAYLOAD_FILE_ID, BATCH_ID, TEMPLATE, FORMAT, ORIGINATING_SOURCE);

    private WireMockServer sdg;

    @BeforeEach
    void startSystemDocGenerator() {
        sdg = new WireMockServer(wireMockConfig().dynamicPort());
        sdg.start();
    }

    @AfterEach
    void stopSystemDocGenerator() {
        sdg.stop();
    }

    private SystemDocGeneratorClient client() {
        final SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return new SystemDocGeneratorClient(
                RestClient.builder()
                        .baseUrl(sdg.baseUrl())
                        .requestFactory(requestFactory)
                        .build(),
                SYSTEM_USER_ID,
                MAPPER);
    }

    private void commandAnswering(final int status) {
        sdg.stubFor(post(urlEqualTo(COMMAND_PATH)).willReturn(aResponse().withStatus(status)));
    }

    /**
     * Asks for the render and states, as an assertion, that the request was accepted.
     *
     * <p>Every case about the wire goes through here rather than calling the client directly, so a
     * client that refuses a request the contract accepts fails on a sentence about the contract
     * rather than on whatever exception it happened to raise.
     */
    private void render(final CallerIdentity caller) {
        assertThatCode(() -> client().requestRender(REQUEST, caller))
                .as("systemdocgenerator answered 202, which is the contract's one success")
                .doesNotThrowAnyException();
    }

    /** The body the command actually carried, parsed. */
    private JsonNode sentBody() {
        return MAPPER.readTree(
                sdg.findAll(postRequestedFor(urlEqualTo(COMMAND_PATH))).getFirst().getBodyAsString());
    }

    /** One vendored schema, read from the file this repository holds it in. */
    private static JsonNode vendoredSchema(final String fileName) {
        final Path schema = VENDORED.resolve(fileName);
        try {
            return MAPPER.readTree(Files.readString(schema, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(
                    "the vendored systemdocgenerator contract must be committed at " + schema,
                    unreadable);
        }
    }

    private static List<String> declaredProperties(final JsonNode schema) {
        return List.copyOf(schema.get("properties").propertyNames());
    }

    private static List<String> requiredProperties(final JsonNode schema) {
        final List<String> names = new ArrayList<>();
        schema.get("required").forEach(name -> names.add(name.stringValue()));
        return names;
    }

    private static List<String> enumValues(final JsonNode schema, final String property) {
        final List<String> values = new ArrayList<>();
        schema.get("properties").get(property).get("enum").forEach(
                value -> values.add(value.stringValue()));
        return values;
    }

    @Nested
    @DisplayName("the command on the wire")
    class Command {

        @Test
        @DisplayName("carries the contract path, media type and identity header")
        void the_command_carries_the_contract_path_media_type_and_identity() {
            commandAnswering(ACCEPTED);

            render(CALLER);

            sdg.verify(postRequestedFor(urlEqualTo(COMMAND_PATH))
                    .withHeader("Content-Type", equalTo(COMMAND_MEDIA_TYPE))
                    .withHeader(IDENTITY_HEADER, equalTo(SHARING_USER)));
        }

        @Test
        @DisplayName("is made as the configured identity when the run names nobody")
        void the_command_is_made_as_the_configured_identity_when_the_run_names_nobody() {
            commandAnswering(ACCEPTED);

            render(CallerIdentity.SYSTEM);

            sdg.verify(postRequestedFor(urlEqualTo(COMMAND_PATH))
                    .withHeader(IDENTITY_HEADER, equalTo(SYSTEM_USER_ID)));
        }

        /**
         * The five fields, verbatim. Four of them are constants of the contract and the fifth pair -
         * the payload id and the batch id - is what makes the request this batch's rather than any
         * other's.
         */
        @Test
        @DisplayName("sends the five fields the contract declares and no sixth")
        void the_body_is_the_five_fields_the_contract_declares() {
            commandAnswering(ACCEPTED);

            render(CALLER);

            final JsonNode body = sentBody();
            assertThat(List.copyOf(body.propertyNames()))
                    .as("a sixth field is a change to somebody else's contract")
                    .containsExactlyInAnyOrder("templateIdentifier", "conversionFormat",
                            "payloadFileServiceId", "sourceCorrelationId", "originatingSource");
            assertThat(body.get("templateIdentifier").stringValue()).isEqualTo(TEMPLATE);
            assertThat(body.get("conversionFormat").stringValue()).isEqualTo(FORMAT);
            assertThat(body.get("payloadFileServiceId").stringValue())
                    .isEqualTo(PAYLOAD_FILE_ID.toString());
        }

        /**
         * The correlation, on its own, because it is the whole of the completion leg. The outcome
         * arrives on a topic the estate publishes to and carries no batch of its own: this id is how
         * a {@code document-available} finds the rows it belongs to.
         */
        @Test
        @DisplayName("correlates the render to the batch by the batch's own id")
        void the_correlation_is_the_batch_id_the_outcome_event_will_carry_back() {
            commandAnswering(ACCEPTED);

            render(CALLER);

            assertThat(sentBody().get("sourceCorrelationId").stringValue())
                    .as("an event whose sourceCorrelationId is not a batch id is an event that "
                            + "reaches no rows")
                    .isEqualTo(BATCH_ID.toString());
        }

        /**
         * progression's leg is still deployed and still subscribed to {@code public.event}; its
         * processor keeps the events whose source is its own. Naming this service is what makes the
         * two subscriptions inert to each other during the cutover.
         */
        @Test
        @DisplayName("names this service as the originating source")
        void the_originating_source_keeps_progressions_listener_out_of_these_documents() {
            commandAnswering(ACCEPTED);

            render(CALLER);

            assertThat(sentBody().get("originatingSource").stringValue())
                    .isEqualTo(ORIGINATING_SOURCE);
        }

        /**
         * The vendored schema is {@code additionalProperties: false}, so a field this service
         * invents is a 400 rather than a field systemdocgenerator ignores. Reading the schema here
         * rather than restating it means a re-vendoring that moves the contract fails this suite.
         */
        @Test
        @DisplayName("sends a body the vendored generate-document schema declares")
        void the_body_is_one_the_vendored_command_schema_declares() {
            commandAnswering(ACCEPTED);

            render(CALLER);

            final JsonNode schema = vendoredSchema("systemdocgenerator.generate-document.json");
            final List<String> sent = List.copyOf(sentBody().propertyNames());
            assertThat(sent)
                    .as("the schema refuses additional properties")
                    .isSubsetOf(declaredProperties(schema));
            assertThat(sent)
                    .as("every property the schema requires")
                    .containsAll(requiredProperties(schema));
            assertThat(enumValues(schema, "conversionFormat"))
                    .as("pdf is one of the three formats the schema permits")
                    .contains(sentBody().get("conversionFormat").stringValue());
        }

        @Test
        @DisplayName("asks once and returns, leaving the document to the event")
        void an_accepted_request_asks_once_and_returns() {
            commandAnswering(ACCEPTED);

            render(CALLER);

            assertThat(sdg.getAllServeEvents())
                    .as("202 says a render was asked for, not that a document exists")
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("202 and nothing else is success")
    class ContractSuccess {

        /**
         * A 2xx that is not 202 means something other than the command endpoint answered. Treating
         * it as accepted would move the batch to GENERATING for a render nothing was asked for, and
         * the batch would then wait out its grace period for an event that cannot come.
         */
        @ParameterizedTest(name = "{0} is not the success the contract defines")
        @ValueSource(ints = {200, 201, 204})
        @DisplayName("only_202_is_success")
        void only_202_is_success(final int status) {
            commandAnswering(status);

            assertThat(catchThrowable(() -> client().requestRender(REQUEST, CALLER)))
                    .isInstanceOf(GenerationFailedException.class)
                    .asInstanceOf(InstanceOfAssertFactories.type(GenerationFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.reason())
                                .isEqualTo(BatchFailureReason.RENDER_REQUEST_REJECTED);
                        assertThat(failure.classification())
                                .as("asking again cannot turn a 200 into a 202")
                                .isEqualTo(FailureClassification.NON_TRANSIENT);
                        assertThat(failure.responseCode())
                                .as("the batch row records what answered, not what was hoped for")
                                .isEqualTo(OptionalInt.of(status));
                    });
        }

        @ParameterizedTest(name = "{0} is a refusal the batch is failed on")
        @ValueSource(ints = {400, 401, 403, 404, 422})
        @DisplayName("a refusal is a rejection carrying its status")
        void a_refusal_is_a_rejection_carrying_its_status(final int status) {
            commandAnswering(status);

            assertThat(catchThrowable(() -> client().requestRender(REQUEST, CALLER)))
                    .isInstanceOf(GenerationFailedException.class)
                    .asInstanceOf(InstanceOfAssertFactories.type(GenerationFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.reason())
                                .isEqualTo(BatchFailureReason.RENDER_REQUEST_REJECTED);
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
         * its own about a 429 fails here rather than at 18:00 on a busy renderer.
         */
        @ParameterizedTest(name = "{0} is classified as the submission client classifies it")
        @ValueSource(ints = {200, 201, 204, 400, 401, 403, 404, 408, 422, 429, 500, 502, 503})
        @DisplayName("retry_taxonomy_matches_the_submission_client")
        void retry_taxonomy_matches_the_submission_client(final int status) {
            assertThat(rendererClassificationOf(status))
                    .as("both clients hold the same RetryPolicy, so neither can decide on its own "
                            + "what is worth asking again")
                    .isEqualTo(submissionClassificationOf(status));
        }

        @ParameterizedTest(name = "{0} is worth asking again")
        @ValueSource(ints = {408, 429, 500, 502, 503})
        @DisplayName("a transient answer is handed back for the run to ask again")
        void a_transient_answer_is_handed_back_for_the_run_to_ask_again(final int status) {
            commandAnswering(status);

            assertThat(catchThrowable(() -> client().requestRender(REQUEST, CALLER)))
                    .isInstanceOf(GenerationFailedException.class)
                    .asInstanceOf(InstanceOfAssertFactories.type(GenerationFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.TRANSIENT);
                        assertThat(failure.reason())
                                .isEqualTo(BatchFailureReason.RENDER_REQUEST_FAILED);
                        assertThat(failure.responseCode()).isEqualTo(OptionalInt.of(status));
                    });
        }

        /**
         * A request whose answer never arrived may still have been made. It is transient for that
         * reason and carries no status at all: an invented one would say an attempt was answered
         * when nothing answered.
         */
        @Test
        @DisplayName("an unanswered attempt is transient and records no status")
        void an_unanswered_attempt_is_transient_and_records_no_status() {
            sdg.stubFor(post(urlEqualTo(COMMAND_PATH))
                    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            assertThat(catchThrowable(() -> client().requestRender(REQUEST, CALLER)))
                    .isInstanceOf(GenerationFailedException.class)
                    .asInstanceOf(InstanceOfAssertFactories.type(GenerationFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.TRANSIENT);
                        assertThat(failure.responseCode()).isEmpty();
                    });
        }

        /**
         * The division of labour, asserted rather than assumed. This client is handed a request and
         * a caller and is told nothing about what is left of the run, so a loop here would spend a
         * budget it cannot see; the service that holds the deadline holds the loop (T039).
         */
        @Test
        @DisplayName("asks once and leaves the waiting to the run that holds the deadline")
        void the_client_asks_once_and_leaves_the_waiting_to_the_run() {
            commandAnswering(503);

            assertThat(catchThrowable(() -> client().requestRender(REQUEST, CALLER)))
                    .isInstanceOf(GenerationFailedException.class);

            assertThat(sdg.getAllServeEvents())
                    .as("the run deadline bounds the retrying, and it is not this client's")
                    .hasSize(1);
        }

        private FailureClassification rendererClassificationOf(final int status) {
            commandAnswering(status);
            final Throwable refused = catchThrowable(() -> client().requestRender(REQUEST, CALLER));
            assertThat(refused)
                    .as("systemdocgenerator answered %s, which is not the contract's 202", status)
                    .isInstanceOf(GenerationFailedException.class);
            return ((GenerationFailedException) refused).classification();
        }

        /**
         * The submission client's own verdict on the same status, made against the same server.
         *
         * <p>One attempt, so the two are compared on the same question - what one answer means -
         * rather than on how many times each is willing to ask. The waiting is a no-op and the
         * deadline is unreachable, so neither can end this case.
         */
        private FailureClassification submissionClassificationOf(final int status) {
            sdg.stubFor(post(urlEqualTo(ProgressionCommandGateway.PATH))
                    .willReturn(aResponse().withStatus(status)));
            final Instant now = Instant.parse("2026-09-04T17:00:00Z");
            final SimpleClientHttpRequestFactory requestFactory =
                    new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
            requestFactory.setReadTimeout(READ_TIMEOUT);
            final ProgressionCommandGateway gateway = new ProgressionCommandGateway(
                    RestClient.builder()
                            .baseUrl(sdg.baseUrl())
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
     * FR-006 read off the wire: a generation asks for a render and asks nothing else.
     *
     * <p>Two halves, and neither is the other. The <strong>journal</strong> is what a generation
     * actually did - every request this client made to systemdocgenerator over the whole of the one
     * conversation a batch has with it - and it must hold POSTs to the command path and nothing
     * else; a GET in it is a synchronous coupling to the renderer that the increment removed. The
     * <strong>surface</strong> is what a generation could have done: a client still carrying a
     * second method is a second request one line of a later change reintroduces, with the journal
     * still empty because nothing called it yet.
     *
     * <p>The journal is read over {@code getAllServeEvents}, which is every request the server
     * received rather than every request a stub was declared for: a call to a path nothing stubbed
     * still appears there, which is exactly the call this case exists to catch.
     */
    @Test
    @DisplayName("a whole generation makes no request to the document endpoint")
    void a_whole_generation_makes_no_request_to_the_document_endpoint() {
        commandAnswering(ACCEPTED);

        render(CALLER);

        assertThat(sdg.getAllServeEvents())
                .as("everything a batch's generation asks of systemdocgenerator, and it is one "
                        + "command (FR-006)")
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.getRequest().getUrl())
                        .isEqualTo(COMMAND_PATH));
        assertThat(publishedCallsOf(SystemDocGeneratorClient.class))
                .as("and no second conversation to make a second request from: the query was the "
                        + "flow's one synchronous coupling to the renderer, and a client that "
                        + "still declared it is one line from making it again")
                .containsExactly("requestRender");
    }

    /**
     * The methods a class publishes, which is what a caller could reach.
     *
     * @param published the class under assertion
     * @return the names of its public declared methods
     */
    private static List<String> publishedCallsOf(final Class<?> published) {
        return Stream.of(published.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .toList();
    }
}
