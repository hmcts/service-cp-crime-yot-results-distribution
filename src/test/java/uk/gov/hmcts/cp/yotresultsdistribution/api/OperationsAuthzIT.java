package uk.gov.hmcts.cp.yotresultsdistribution.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import uk.gov.hmcts.cp.yotresultsdistribution.application.BatchListingService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.FeatureFlagReader;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OnDemandExceptionReportService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OperationsRunLauncher;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OperationsSupersessionService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterNotifierService;

/**
 * The real authorisation filter, wired as a deployment wires it, refusing the people it should.
 *
 * <p>FR-039 is the reason this suite exists and the reason it may not take a short cut: <em>a test
 * that mocks the decision proves the test</em>. {@code OperationsRulesTest} runs the rules,
 * {@code OperationsActionFilterTest} runs the wrapper and {@code OperationsActionChainTest} runs
 * the resolver over both - and none of them would notice the two things a deployment actually gets
 * wrong, which are the {@code exclude-path-prefixes} list and the filter order. So the starter is
 * on, its settings are this repository's committed ones, and the only thing stood in for is
 * usersgroups, stubbed at the HTTP boundary by WireMock with the
 * {@code LoggedInUserPermissionsResponse} body shape the identity client deserialises (research
 * A9/R4).
 *
 * <p><strong>The audit filter is on beside it</strong>, because the two interleave and the order
 * matters: the audit filter sits at {@code HIGHEST_PRECEDENCE + 50}, <em>inside</em> the
 * authorisation filter at {@code +30}, so a denied request is not audited by the library at all.
 * Its transport is the one thing not wired: {@code AuditService} is registered
 * {@code @ConditionalOnMissingBean} and this service supplies {@link OperationsAuditService} in its
 * place, so that one is the bean replaced here and nothing reaches a broker. Everything the filter
 * itself does - the OpenAPI document, the path-parameter resolution, its place inside the
 * authorisation filter, the two publishes - is real. What the publisher does with a failure is
 * {@code OperationsAuditFactsTest}'s subject, and what its events carry is
 * {@code OperationsAuditIT}'s.
 *
 * <p><strong>The controllers are contributed by hand.</strong> Every one of them carries
 * {@code @Profile("!test")}, because the store and its repositories do; a context on any other
 * profile needs a database, and what this suite is about is the filter chain rather than the store.
 * Declaring them as {@code @Bean}s keeps the real classes, the real mappings and the real derived
 * action names, with the application services mocked - none of them is reached in a case that is
 * refused, which is itself one of the assertions.
 */
@SpringBootTest(properties = {
    // The two estate filters, on, with this repository's own committed settings underneath.
    "authz.http.enabled=true",
    "audit.http.enabled=true",
    "cp.audit.enabled=true",
    // The transport is built and never used: the capturing AuditService below replaces the
    // starter's, so nothing here opens a connection. The factory validates these while it is being
    // constructed, which is the only reason they are set at all.
    "cp.audit.hosts=localhost",
    "cp.audit.port=61616",
    "yotresultsdistribution.consumer.enabled=false",
    "yotresultsdistribution.generation.enabled=false",
    "yotresultsdistribution.report.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the real authorisation filter over the seven endpoints")
class OperationsAuthzIT {

    /** The one group any rule in {@code acl/operations-rules.drl} admits. */
    private static final String ADMITTED = "Second Line Support";

    /** A group that exists in the estate and is admitted to none of these actions. */
    private static final String SOMEBODY_ELSE = "Court Clerks";

    /** The header the identity is asserted in, which is the gateway's claim and not the caller's. */
    private static final String IDENTITY = "CJSCPPUID";

    /** The header a caller might forge an action in, and which the wrapper overwrites. */
    private static final String ACTION_HEADER = "CPP-ACTION";

    /** A caller, named by the one identifier this service ever writes down about one. */
    private static final String A_CALLER = "6d3f1c20-9b4a-4e71-8f05-2c7d9a1e6b34";

    /** A well-formed batch identity, so the notify path is reached rather than its parser. */
    private static final String BATCH = "11111111-2222-4333-8444-555555555555";

    /** Where usersgroups answers, stubbed at the HTTP boundary and nowhere nearer. */
    private static WireMockServer usersgroups;

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * The publisher, replaced so that nothing here reaches a broker.
     *
     * <p>Not the filter: {@link uk.gov.hmcts.cp.filter.audit.AuditFilter} is the real one, built
     * from the real OpenAPI document and running in its real place inside the authorisation
     * filter. What is stood in for is the one call it makes at each end of the chain - which is
     * the seam the starter itself offers, since it registers its own {@code AuditService}
     * {@code @ConditionalOnMissingBean} and this service supplies
     * {@link OperationsAuditService} in its place. A real one over a broker that is not there
     * refuses every call {@code 503 AUDIT_UNAVAILABLE} after a two-minute Artemis retry, which is
     * correct behaviour and a different suite's subject ({@code OperationsAuditFactsTest}).
     */
    @MockitoBean
    private OperationsAuditService auditPublisher;

    /** Nothing behind the endpoints is reached in a refused case, which is part of the claim. */
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

    private final MockMvc mvc;

    @Autowired
    OperationsAuthzIT(final MockMvc mockMvc) {
        this.mvc = mockMvc;
    }

    @BeforeAll
    static void startUsersgroups() {
        usersgroups = new WireMockServer(wireMockConfig().dynamicPort());
        usersgroups.start();
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
    void everybodyIsAdmitted() {
        usersgroups.resetAll();
        answerWith(ADMITTED);
    }

    /**
     * usersgroups answers with one group for whoever asks.
     *
     * @param group the group the caller is in
     */
    private static void answerWith(final String group) {
        usersgroups.stubFor(WireMock.get(urlPathMatching("/usersgroups-query-api/.*"))
                .willReturn(okJson("{\"groups\":[{\"groupId\":\"a-group-id\",\"groupName\":\""
                        + group + "\",\"prosecutingAuthority\":null}],"
                        + "\"switchableRoles\":[],\"permissions\":[]}")));
    }

    /** usersgroups cannot be asked, which the identity client answers as an empty identity. */
    private static void theIdentityServiceIsDown() {
        usersgroups.stubFor(WireMock.get(urlPathMatching("/usersgroups-query-api/.*"))
                .willReturn(aResponse().withStatus(500)));
    }

    /**
     * The seven endpoints, each as a request with no identity on it yet.
     *
     * @return one builder per endpoint, named by the action it is
     */
    static Stream<Arguments> theSevenEndpoints() {
        return Stream.of(
                Arguments.of("check-flag", get("/operations/flag")),
                Arguments.of("list-batches", get("/operations/batches").param("date", "2026-09-04")),
                Arguments.of("list-recorded-while-off",
                        get("/operations/registers/recorded-while-off")),
                Arguments.of("generate-register", post("/operations/batches/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-09-04\"}")),
                Arguments.of("notify-register",
                        post("/operations/batches/" + BATCH + "/notify")),
                Arguments.of("supersede-before", post("/operations/registers/supersede")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sharedBefore\":\"2026-09-04T17:00:00Z\"}")),
                Arguments.of("report-exceptions", post("/operations/exception-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")));
    }

    /**
     * Who is admitted, and who is not, on every one of the seven.
     */
    @Nested
    @DisplayName("who reaches the endpoints")
    class TheDecision {

        @ParameterizedTest(name = "{0}")
        @MethodSource("uk.gov.hmcts.cp.yotresultsdistribution.api.OperationsAuthzIT#theSevenEndpoints")
        void a_caller_in_second_line_support_should_be_served(final String action,
                final MockHttpServletRequestBuilder call) throws Exception {

            mvc.perform(call.header(IDENTITY, A_CALLER))
                    .andExpect(result -> softly.assertThat(result.getResponse().getStatus())
                            .as("the admitted group reaches the endpoint; what the endpoint then "
                                    + "answers is its own suite's business, and is never 401 or 403")
                            .isNotIn(401, 403));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("uk.gov.hmcts.cp.yotresultsdistribution.api.OperationsAuthzIT#theSevenEndpoints")
        void a_caller_in_another_group_should_be_refused_403(final String action,
                final MockHttpServletRequestBuilder call) throws Exception {

            answerWith(SOMEBODY_ELSE);

            mvc.perform(call.header(IDENTITY, A_CALLER))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("uk.gov.hmcts.cp.yotresultsdistribution.api.OperationsAuthzIT#theSevenEndpoints")
        void a_request_with_no_identity_should_be_refused_401(final String action,
                final MockHttpServletRequestBuilder call) throws Exception {

            mvc.perform(call).andExpect(status().isUnauthorized());
        }

        @Test
        void an_identity_service_that_cannot_be_asked_should_refuse_rather_than_admit()
                throws Exception {

            theIdentityServiceIsDown();

            mvc.perform(get("/operations/flag").header(IDENTITY, A_CALLER))
                    .andExpect(status().isForbidden());
        }

        @Test
        void a_forged_action_header_should_not_reach_an_action_the_caller_is_refused() throws
                Exception {

            answerWith(SOMEBODY_ELSE);

            mvc.perform(get("/operations/flag")
                            .header(IDENTITY, A_CALLER)
                            .header(ACTION_HEADER, "yot-results-distribution-operations.check-flag"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void a_multipart_call_with_no_identity_should_be_refused_401_before_415()
                throws Exception {

            mvc.perform(post("/operations/exception-reports")
                            .contentType(MediaType.MULTIPART_FORM_DATA)
                            .content("--x--"))
                    .andExpect(result -> softly.assertThat(result.getResponse().getStatus())
                            .as("the content-type guard sits inside the authorisation filter, not "
                                    + "ahead of it: an anonymous caller is told they are not "
                                    + "authenticated, and learns nothing about what this surface "
                                    + "consumes")
                            .isEqualTo(401));
        }

        @Test
        void a_multipart_call_from_an_admitted_caller_should_still_be_refused_415()
                throws Exception {

            mvc.perform(post("/operations/exception-reports")
                            .contentType(MediaType.MULTIPART_FORM_DATA)
                            .content("--x--")
                            .header(IDENTITY, A_CALLER))
                    .andExpect(result -> softly.assertThat(result.getResponse().getStatus())
                            .as("past authorisation the guard still runs, and it runs before the "
                                    + "audit filter: an endpoint reachable with a content type "
                                    + "that publishes neither event is reachable unaudited")
                            .isEqualTo(415));
        }

        @Test
        void a_multipart_call_from_a_caller_in_another_group_should_be_refused_403()
                throws Exception {

            answerWith(SOMEBODY_ELSE);

            mvc.perform(post("/operations/exception-reports")
                            .contentType(MediaType.MULTIPART_FORM_DATA)
                            .content("--x--")
                            .header(IDENTITY, A_CALLER))
                    .andExpect(result -> softly.assertThat(result.getResponse().getStatus())
                            .as("who may act is decided before what they may send")
                            .isEqualTo(403));
        }

        @Test
        void a_forged_vendor_media_type_should_not_name_the_action_either() throws Exception {
            answerWith(SOMEBODY_ELSE);

            mvc.perform(post("/operations/registers/supersede")
                            .contentType("application/vnd.yot-results-distribution-operations.check-flag+json")
                            .content("{\"sharedBefore\":\"2026-09-04T17:00:00Z\"}")
                            .header(IDENTITY, A_CALLER))
                    .andExpect(status().isForbidden());
        }
    }

    /**
     * What is deliberately not behind these filters.
     */
    @Nested
    @DisplayName("what the filters do not cover")
    class TheExcludedPaths {

        @Test
        void actuator_should_answer_with_no_identity_at_all() throws Exception {
            mvc.perform(get("/actuator/health"))
                    .andExpect(result -> softly.assertThat(result.getResponse().getStatus())
                            .as("the liveness and readiness probes carry no CJSCPPUID and never "
                                    + "will; a filter over them rolls the pod for ever")
                            .isNotIn(401, 403));
        }

        @Test
        void the_error_path_should_not_be_refused_by_the_filter_that_forwards_to_it()
                throws Exception {

            mvc.perform(get("/nothing-is-mapped-here"))
                    .andExpect(result -> softly.assertThat(result.getResponse().getStatus())
                            .as("the filter refuses through sendError, which forwards to /error - "
                                    + "a refusal whose own forward is refused answers nothing")
                            .isNotEqualTo(500));
        }
    }

    /**
     * The seven endpoints' controllers, contributed by hand.
     *
     * <p>Every one of them carries {@code @Profile("!test")} because the store and its
     * repositories do, and a class-level profile is evaluated against the bean definition rather
     * than against the type - so a {@code @Bean} method contributes the real class, with its real
     * mappings and its real derived action names, on the profile that has no database.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TheControllers {

        @Bean
        FlagController flagController(final FeatureFlagReader reader) {
            return new FlagController(reader);
        }

        @Bean
        BatchesController batchesController(final BatchListingService listings,
                final OperationsRunLauncher launcher, final RegisterNotifierService notifier) {
            return new BatchesController(listings, launcher, notifier);
        }

        @Bean
        RegistersController registersController(final BatchListingService listings,
                final OperationsSupersessionService supersession) {
            return new RegistersController(listings, supersession);
        }

        @Bean
        ExceptionReportsController exceptionReportsController(
                final OnDemandExceptionReportService reports) {
            return new ExceptionReportsController(reports);
        }
    }
}
