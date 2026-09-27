package uk.gov.hmcts.cp.yotresultsdistribution.api;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.jms.UncategorizedJmsException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessagePostProcessor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import uk.gov.hmcts.cp.yotresultsdistribution.application.BatchListingService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.FeatureFlagReader;
import uk.gov.hmcts.cp.yotresultsdistribution.application.NotificationSummary;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OnDemandExceptionReportService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OperationsRunLauncher;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OperationsRunLauncher.RunAccepted;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OperationsSupersessionService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterNotifierService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.Supersession;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;

/**
 * What the audit context is actually told about each of the seven, end to end. <strong>[A]</strong>
 *
 * <p>An acceptance suite (tasks.md {@code [A]}): it records what the assembled chain does rather
 * than driving a change. {@code OperationsAuditFactsTest} asserts the publisher's own behaviour
 * over a seam; this asserts that an authorised call through the <em>real</em> audit filter, with
 * the real OpenAPI document behind it, leaves two events carrying the caller, the action and the
 * outcome - and no body.
 *
 * <p><strong>The seam is the JMS template and nothing nearer.</strong> The filter is real, the
 * payload generation is the library's, {@link OperationsAuditService} is the real one this service
 * contributes, and only the template it publishes through is replaced. So what is asserted here is
 * the JSON that would have left for the audit broker.
 *
 * <p><strong>Two events per call, and the second one needs a body to exist.</strong> The library
 * publishes the request event before the chain and the response event only where the response
 * carried text, so every case answers something - a success where one is cheap to stub and a
 * bounded refusal where it is not. A refusal is an outcome like any other, which is the point of
 * the field.
 */
@SpringBootTest(properties = {
    "authz.http.enabled=true",
    "audit.http.enabled=true",
    "cp.audit.enabled=true",
    "cp.audit.hosts=localhost",
    "cp.audit.port=61616",
    "yotresultsdistribution.consumer.enabled=false",
    "yotresultsdistribution.generation.enabled=false",
    "yotresultsdistribution.report.enabled=false",
    // Boot's JmsMessagingTemplate reads a message converter off whichever JmsTemplate it finds,
    // and the one replaced below has none. Nothing in this context consumes or publishes JMS of
    // its own - the generation half is off, so the public-event container is not built - so the
    // auto-configuration is excluded rather than satisfied with a converter nobody uses.
    "spring.autoconfigure.exclude=org.springframework.boot.jms.autoconfigure.JmsAutoConfiguration"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("what the audit context is told about an operations call")
class OperationsAuditIT {

    /** The one group any rule admits, so every case here is an authorised call. */
    private static final String ADMITTED = "Second Line Support";

    /** The header the identity is asserted in. */
    private static final String IDENTITY = "CJSCPPUID";

    /** The caller, and the one thing about them this service ever writes down. */
    private static final String A_CALLER = "6d3f1c20-9b4a-4e71-8f05-2c7d9a1e6b34";

    /** A well-formed batch identity, so the notify path is reached rather than its parser. */
    private static final String BATCH = "11111111-2222-4333-8444-555555555555";

    /** The run id the launcher answers a regeneration with. */
    private static final String RUN_ID = "9f2b6d44-6b1a-4f0a-9d24-0cc2b0d1f3aa";

    /** What an unclassified defect said, which must reach neither the caller nor the event. */
    private static final String A_DEFECTS_OWN_WORDS = "ZQX7 jdbc:postgresql://secret";

    /** What the audit transport said, which belongs to the library that raised it. */
    private static final String A_BROKERS_OWN_WORDS = "ZQX7BROKER tcp://audit:61616";

    /** The header one case asks the innermost test filter to take the caller away on. */
    private static final String GONE_AWAY = "X-Test-Client-Gone-Away";

    /** The register date every case that needs one is about. */
    private static final LocalDate A_DATE = LocalDate.of(2026, 9, 4);

    /** Where usersgroups answers. */
    private static WireMockServer usersgroups;

    /** The template the publisher sends through, which is the seam this suite reads. */
    @MockitoBean(name = "auditJmsTemplate")
    private JmsTemplate audit;

    @MockitoBean
    private FeatureFlagReader flag;

    @MockitoBean
    private BatchListingService listings;

    @MockitoBean
    private OperationsRunLauncher launcher;

    @MockitoBean
    private RegisterNotifierService notifier;

    @MockitoBean
    private OperationsSupersessionService supersession;

    @MockitoBean
    private OnDemandExceptionReportService reports;

    @InjectSoftAssertions
    private SoftAssertions softly;

    private final MockMvc mvc;

    /** Where the lost-response-event counter is registered, which is the recorded shortfall. */
    private final MeterRegistry meters;

    @Autowired
    OperationsAuditIT(final MockMvc mockMvc, final MeterRegistry meterRegistry) {
        this.mvc = mockMvc;
        this.meters = meterRegistry;
    }

    @BeforeAll
    static void startUsersgroups() {
        usersgroups = new WireMockServer(wireMockConfig().dynamicPort());
        usersgroups.start();
        usersgroups.stubFor(WireMock.get(urlPathMatching("/usersgroups-query-api/.*"))
                .willReturn(okJson("{\"groups\":[{\"groupId\":\"a-group-id\",\"groupName\":\""
                        + ADMITTED + "\",\"prosecutingAuthority\":null}],"
                        + "\"switchableRoles\":[],\"permissions\":[]}")));
    }

    @AfterAll
    static void stopUsersgroups() {
        usersgroups.stop();
    }

    @DynamicPropertySource
    static void pointTheIdentityClientAtIt(final DynamicPropertyRegistry registry) {
        registry.add("authz.http.identity-url-template",
                () -> usersgroups.baseUrl() + "/usersgroups-query-api/query/api/rest/usersgroups/"
                        + "users/logged-in-user/permissions");
    }

    @BeforeEach
    void everyEndpointAnswersSomething() {
        when(flag.read()).thenReturn(FlagDecision.ON);
        when(listings.batchesOn(any())).thenReturn(List.of());
        when(listings.recordedWhileOff()).thenReturn(List.of());
        when(launcher.launch(any())).thenReturn(new RunAccepted(RUN_ID, A_DATE, false));
        when(notifier.resendFailed(any()))
                .thenReturn(new NotificationSummary(1, 0, BatchStatus.NOTIFIED));
        when(supersession.supersede(any(), anyBoolean()))
                .thenReturn(new Supersession(47, Instant.parse("2026-09-04T17:00:00Z"), false));
        // The report's own success shape is a tree of five records; a bounded refusal is the
        // cheaper answer and is an outcome in exactly the sense the audit field means.
        when(reports.report(any(), anyBoolean())).thenThrow(
                new OperationsRefusedException(OperationsReason.EMAIL_OUTPUT_DISABLED));
    }

    /**
     * The seven, each with the action name this service derives for it.
     *
     * @return one call per endpoint
     */
    static Stream<Arguments> theSevenEndpoints() {
        return Stream.of(
                Arguments.of("check-flag", get("/operations/flag")),
                Arguments.of("list-batches",
                        get("/operations/batches").param("date", "2026-09-04")),
                Arguments.of("list-recorded-while-off",
                        get("/operations/registers/recorded-while-off")),
                Arguments.of("generate-register", post("/operations/batches/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-09-04\"}")),
                Arguments.of("notify-register", post("/operations/batches/" + BATCH + "/notify")),
                Arguments.of("supersede-before", post("/operations/registers/supersede")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sharedBefore\":\"2026-09-04T17:00:00Z\"}")),
                Arguments.of("report-exceptions", post("/operations/exception-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("theSevenEndpoints")
    void an_authorised_call_should_leave_two_events_naming_the_caller_the_action_and_the_outcome(
            final String action, final MockHttpServletRequestBuilder call) throws Exception {

        mvc.perform(call.header(IDENTITY, A_CALLER));

        final ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(audit, Mockito.atLeast(2)).convertAndSend(any(jakarta.jms.Destination.class),
                events.capture(), any(MessagePostProcessor.class));
        final List<String> published = events.getAllValues().stream().map(String::valueOf).toList();

        softly.assertThat(published)
                .as("the request event before the chain and the response event after it, which is "
                        + "what makes an unpublished request a refused call rather than a lost one")
                .hasSize(2);
        softly.assertThat(published)
                .as("the caller is named on purpose, and this is the one place they are")
                .allMatch(event -> event.contains(A_CALLER));
        softly.assertThat(published)
                .as("the action is the one this service derived from the path and the method, "
                        + "never the one the caller's header named")
                .allMatch(event -> event.contains("yot-results-distribution-operations." + action));
        softly.assertThat(published)
                .as("and what came of the call, as a bounded word or a status and a bounded code")
                .allMatch(event -> event.contains("\"outcome\":"));
        softly.assertThat(published)
                .as("no defendant detail could be here and no body is: the payload switch is off, "
                        + "and what this service adds is five bounded fields (FR-046)")
                .allSatisfy(event -> softly.assertThat(event).doesNotContain("\"_payload\":\"{"));
    }

    /**
     * The refusal every endpoint can answer, taken where it is actually taken: on the wire.
     *
     * <p>The request event is published by {@code AuditFilter} <strong>before</strong> the chain,
     * on the caller's own thread and outside the {@code DispatcherServlet}. So the refusal
     * {@link OperationsAuditService} raises reaches no {@code @RestControllerAdvice} at all: it
     * leaves the audit filter, is caught by {@code OperationsActionFilter} and is rendered from
     * the one status map. {@code OperationsAuditFactsTest} asserts that the publisher throws;
     * nothing but this asserts that what the caller is answered is a {@code 503} with a bounded
     * reason rather than the container's own 500 with the path they typed in it.
     *
     * @param action what the call is, for the case name
     * @param call   the call
     * @throws Exception where the call cannot be made, which no case here expects
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("theSevenEndpoints")
    void a_request_event_that_cannot_be_published_should_refuse_the_call(final String action,
            final MockHttpServletRequestBuilder call) throws Exception {

        doThrow(new UncategorizedJmsException(A_BROKERS_OWN_WORDS)).when(audit)
                .convertAndSend(any(jakarta.jms.Destination.class), any(),
                        any(MessagePostProcessor.class));

        final MockHttpServletResponse response =
                mvc.perform(call.header(IDENTITY, A_CALLER)).andReturn().getResponse();
        final String body = response.getContentAsString();

        softly.assertThat(response.getStatus())
                .as("a call that cannot be audited must not proceed as though it had been "
                        + "(Principle III(b)); the request event is the last moment there is "
                        + "still something to refuse")
                .isEqualTo(503);
        softly.assertThat(body)
                .as("and it says so in the bounded code every other refusal on this surface "
                        + "carries, from the same status map")
                .contains(OperationsReason.AUDIT_UNAVAILABLE.wire());
        softly.assertThat(body)
                .as("no path, no typed value, no broker's own words (FR-025)")
                .doesNotContain("/operations").doesNotContain(BATCH)
                .doesNotContain("2026-09-04").doesNotContain(A_BROKERS_OWN_WORDS);
        softly.assertThat(action).isNotBlank();
        Mockito.verifyNoInteractions(flag, listings, launcher, notifier, supersession, reports);
    }

    /**
     * The other half of the same rule: a response event nobody can publish changes no answer.
     *
     * <p>The action has happened by then and no status can say so to a caller whose work is done,
     * so the shortfall is recorded rather than refused - said at ERROR, and counted, because being
     * in the log index is not an alerting surface.
     *
     * @throws Exception where the call cannot be made, which this case does not expect
     */
    @org.junit.jupiter.api.Test
    void a_response_event_that_cannot_be_published_should_leave_the_answer_standing()
            throws Exception {

        final double before = unpublishedResponseEvents();
        doNothing().doThrow(new UncategorizedJmsException(A_BROKERS_OWN_WORDS)).when(audit)
                .convertAndSend(any(jakarta.jms.Destination.class), any(),
                        any(MessagePostProcessor.class));

        final int status = mvc.perform(get("/operations/flag").header(IDENTITY, A_CALLER))
                .andReturn().getResponse().getStatus();

        softly.assertThat(status)
                .as("the call was served and answered; there is nothing left to refuse")
                .isEqualTo(200);
        softly.assertThat(unpublishedResponseEvents() - before)
                .as("a path that drops something moves a counter")
                .isEqualTo(1.0d);
    }

    /**
     * What the lost-response-event counter reads now.
     *
     * @return its count, or zero where nothing has yet registered it
     */
    private double unpublishedResponseEvents() {
        final Search found = meters.find("yotresultsdistribution_operations_audit_unpublished");
        return found.counter() == null ? 0.0d : found.counter().count();
    }

    /**
     * The one content type that would be served without either event, refused before it is.
     *
     * <p>{@code cp-audit-filter-springboot} 1.0.5 reads {@code getContentType()} first and, where
     * it begins {@code multipart/}, calls the chain and returns - publishing neither event. Nothing
     * here declares {@code consumes}, notify takes no body and the report's body is optional with a
     * default window, so before the guard both of those actions could be taken with no audit trail
     * at all. These are the two that <em>do</em> something: one sends e-mail, the other builds and
     * delivers a report.
     *
     * @param call the multipart call
     * @throws Exception where the call cannot be made, which no case here expects
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("theTwoThatActOnTheEstate")
    void a_multipart_call_should_be_refused_rather_than_served_unaudited(final String action,
            final MockHttpServletRequestBuilder call) throws Exception {

        final int status = mvc.perform(call.header(IDENTITY, A_CALLER))
                .andReturn().getResponse().getStatus();

        softly.assertThat(status)
                .as("an endpoint reachable without an audit event is an endpoint that may not "
                        + "exist (constitution Principle III(b)); the refusal is taken outside the "
                        + "audit filter because by the time it has decided to skip there is "
                        + "nothing left to refuse")
                .isEqualTo(415);
        softly.assertThat(action).isNotBlank();
        Mockito.verifyNoInteractions(notifier);
        Mockito.verifyNoInteractions(reports);
    }

    /**
     * The two endpoints whose action reaches beyond this service, as multipart calls.
     *
     * @return one call per endpoint
     */
    static Stream<Arguments> theTwoThatActOnTheEstate() {
        return Stream.of(
                Arguments.of("notify-register",
                        post("/operations/batches/" + BATCH + "/notify")
                                .contentType(MediaType.MULTIPART_FORM_DATA)
                                .content("--x--")),
                Arguments.of("report-exceptions",
                        post("/operations/exception-reports")
                                .contentType(MediaType.MULTIPART_FORM_DATA)
                                .content("--x--")));
    }

    /**
     * A defect on the request thread still leaves both events, which is what says what came of it.
     *
     * <p>{@code AuditFilter.doFilterInternal} has no {@code try}/{@code finally} around
     * {@code chain.doFilter}, so an exception that leaves the dispatcher never reaches
     * {@code performResponseAudit}: the trail would carry a request event, no response event and
     * no counter, and nothing this service writes would record the shortfall. The advice's
     * fallback is what keeps the answer on the wire and therefore the event on the topic.
     *
     * @throws Exception where the call cannot be made, which this case does not expect
     */
    @org.junit.jupiter.api.Test
    void an_unclassified_defect_should_still_leave_a_complete_trail() throws Exception {
        when(flag.read()).thenThrow(new IllegalStateException(A_DEFECTS_OWN_WORDS));

        final int status = mvc.perform(get("/operations/flag").header(IDENTITY, A_CALLER))
                .andReturn().getResponse().getStatus();

        final ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(audit, Mockito.atLeastOnce()).convertAndSend(any(jakarta.jms.Destination.class),
                events.capture(), any(MessagePostProcessor.class));
        final List<String> published = events.getAllValues().stream().map(String::valueOf).toList();

        softly.assertThat(status).isEqualTo(500);
        softly.assertThat(published)
                .as("the request event before the chain and the response event after it, which is "
                        + "what FR-046 means by the event carrying the outcome")
                .hasSize(2);
        softly.assertThat(published.get(published.size() - 1))
                .as("and the outcome is the bounded code, not a gap a reader has to interpret")
                .contains("500 UNEXPECTED");
        softly.assertThat(published)
                .as("a defect's own words belong to whatever raised them")
                .allSatisfy(event ->
                        softly.assertThat(event).doesNotContain(A_DEFECTS_OWN_WORDS));
    }

    /**
     * The audit filter's own skip list, which is a substring match and therefore wider than it
     * reads.
     *
     * <p>{@code AuditFilter.shouldNotFilter} skips any request URI <em>containing</em>
     * {@code /health} or {@code /actuator}, so a batch id with either inside it is served with no
     * audit event too. It is harmless only because such an id is not a UUID and is refused by the
     * controller's own binding before any application service is reached - which is what this
     * records, so that a later change making that path reach a service is a failing test rather
     * than a silent hole.
     *
     * @throws Exception where the call cannot be made, which this case does not expect
     */
    @org.junit.jupiter.api.Test
    void a_batch_id_the_audit_filter_skips_on_should_still_reach_no_action() throws Exception {
        final int status = mvc.perform(post("/operations/batches/actuator/notify")
                .header(IDENTITY, A_CALLER)).andReturn().getResponse().getStatus();

        softly.assertThat(status)
                .as("the binding refuses it, so nothing is done unaudited")
                .isEqualTo(400);
        Mockito.verifyNoInteractions(notifier);
    }

    /**
     * What a failure <em>after</em> the work costs, which is nothing and must stay nothing.
     *
     * <p>FR-043. Every one of these three endpoints has done something by the time its answer is
     * written - a run has been accepted, e-mail has been re-requested, a period of registers has
     * been given up - so a response that cannot reach its caller is not a refusal of anything and
     * there is nothing to refuse. The two rules are that it is not reported as one, and that the
     * work is not attempted a second time to make up for an answer nobody read.
     *
     * @param action  what the call is, for the case name
     * @param call    the call
     * @throws Exception the failure written into the response, which is the point of the case
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("theThreeThatChangeSomething")
    void a_response_that_cannot_be_written_should_refuse_nothing_and_repeat_nothing(
            final String action, final MockHttpServletRequestBuilder call) throws Exception {

        final List<String> lines;
        final MockHttpServletResponse response;
        try (CapturedLog log = CapturedLog.everything()) {
            response = mvc.perform(call.header(IDENTITY, A_CALLER).header(GONE_AWAY, "true"))
                    .andReturn().getResponse();
            lines = log.renderings();
        }

        softly.assertThat(response.getStatus())
                .as("the answer the endpoint had already decided stands: the work happened, and a "
                        + "body nobody could read does not un-happen it or turn it into a refusal")
                .isIn(200, 202);
        softly.assertThat(response.getContentAsString())
                .as("and nothing was written, because there was nowhere to write it")
                .isEmpty();
        softly.assertThat(lines.stream()
                        .filter(line -> line.contains("reason=")).toList())
                .as("no bounded code from this surface's closed set is written about it: a "
                        + "refusal code here would say the call was refused, and it was not")
                .noneMatch(line -> Arrays.stream(OperationsReason.values())
                        .anyMatch(code -> line.contains("reason=" + code.wire())));
        softly.assertThat(invocationsOn(launcher) + invocationsOn(notifier)
                        + invocationsOn(supersession))
                .as("exactly one application service was asked exactly once; nothing retries the "
                        + "work to make up for an answer that was never read (FR-043)")
                .isEqualTo(1);
        softly.assertThat(action).isNotBlank();
    }

    /**
     * How many times one mocked application service was asked anything at all.
     *
     * @param service the mock
     * @return its invocation count, stubbing aside
     */
    private static int invocationsOn(final Object service) {
        return Mockito.mockingDetails(service).getInvocations().size();
    }

    /**
     * The three endpoints that have changed something by the time their answer is written.
     *
     * @return one call per endpoint
     */
    static Stream<Arguments> theThreeThatChangeSomething() {
        return Stream.of(
                Arguments.of("generate-register", post("/operations/batches/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-09-04\"}")),
                Arguments.of("notify-register", post("/operations/batches/" + BATCH + "/notify")),
                Arguments.of("supersede-before", post("/operations/registers/supersede")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sharedBefore\":\"2026-09-04T17:00:00Z\"}")));
    }

    /**
     * The controllers, contributed by hand for the reason {@code OperationsAuthzIT} states.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TheControllers {

        @Bean
        FlagController flagController(final FeatureFlagReader reader) {
            return new FlagController(reader);
        }

        /**
         * The caller that went away, innermost so the dispatcher writes through it.
         *
         * <p>Registered at the lowest precedence, which puts it inside every filter this service
         * has: what the controller's answer is written to is then the wrapper, and the failure
         * happens at exactly the moment FR-043 is about - after the application service returned
         * and while its answer is being written.
         *
         * @return the registration
         */
        @Bean
        FilterRegistrationBean<jakarta.servlet.Filter> theClientThatGoesAway() {
            final FilterRegistrationBean<jakarta.servlet.Filter> registration =
                    new FilterRegistrationBean<>((request, response, chain) -> {
                        final jakarta.servlet.http.HttpServletRequest asked =
                                (jakarta.servlet.http.HttpServletRequest) request;
                        if (asked.getHeader(GONE_AWAY) == null) {
                            chain.doFilter(request, response);
                        } else {
                            chain.doFilter(request, new GoneAway(
                                    (jakarta.servlet.http.HttpServletResponse) response));
                        }
                    });
            registration.setOrder(Ordered.LOWEST_PRECEDENCE);
            return registration;
        }

        @Bean
        BatchesController batchesController(final BatchListingService batchListings,
                final OperationsRunLauncher runLauncher, final RegisterNotifierService notifier) {
            return new BatchesController(batchListings, runLauncher, notifier);
        }

        @Bean
        RegistersController registersController(final BatchListingService batchListings,
                final OperationsSupersessionService rollback) {
            return new RegistersController(batchListings, rollback);
        }

        @Bean
        ExceptionReportsController exceptionReportsController(
                final OnDemandExceptionReportService reportService) {
            return new ExceptionReportsController(reportService);
        }

        /** A response whose body cannot be written, because nobody is there to read it. */
        static final class GoneAway
                extends jakarta.servlet.http.HttpServletResponseWrapper {

            GoneAway(final jakarta.servlet.http.HttpServletResponse response) {
                super(response);
            }

            @Override
            public jakarta.servlet.ServletOutputStream getOutputStream()
                    throws java.io.IOException {
                throw new java.io.IOException("the client went away");
            }

            @Override
            public java.io.PrintWriter getWriter() throws java.io.IOException {
                throw new java.io.IOException("the client went away");
            }
        }
    }
}
