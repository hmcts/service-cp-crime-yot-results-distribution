package uk.gov.hmcts.cp.yotresultsdistribution.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.kie.api.KieServices;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import uk.gov.moj.cpp.authz.drools.Action;
import uk.gov.moj.cpp.authz.drools.Outcome;
import uk.gov.moj.cpp.authz.http.RequestActionResolver;
import uk.gov.moj.cpp.authz.http.providers.UserAndGroupProvider;

/**
 * What the authorisation library resolves for a request this service has named, end to end.
 *
 * <p>{@code OperationsActionFilterTest} asserts what the wrapped request answers and
 * {@code OperationsRulesTest} asserts what the rules admit. Neither of them would have noticed the
 * hole between the two: {@code RequestActionResolver} reads {@code getContentType()} and then
 * {@code Accept} for an {@code application/vnd.<token>} <strong>before</strong> it reads
 * {@code CPP-ACTION}, so a caller admitted to the cheapest action could send that action as a media
 * type to any of the seven endpoints and be authorised against it while being served the endpoint
 * their path and method reach.
 *
 * <p>So this suite runs the real resolver over the real filter's output, and then the real rules
 * over what the resolver said. Every case sends the forged media type; what the caller is
 * authorised as must be the endpoint's own action, allowed for the admitted group and refused for
 * anybody else.
 */
@DisplayName("the action the authorisation library resolves")
class OperationsActionChainTest {

    /** Where the rules sit, as {@code authz.http.drools-classpath-pattern} globs them. */
    private static final String RULES = "acl/operations-rules.drl";

    /** The one group any rule may name. */
    private static final String ADMITTED = "Second Line Support";

    /** A group that exists in the estate and is not ours. */
    private static final String SOMEBODY_ELSE = "Court Clerks";

    /** Every action name this service admits, prefixed with its surface. */
    private static final String PREFIX = "yot-results-distribution-operations.";

    /** The header the library reads the action out of, as this service configures it. */
    private static final String ACTION_HEADER = "CPP-ACTION";

    /** The cheapest action of the seven, in the shape a media type would carry it. */
    private static final String FORGED = "application/vnd." + PREFIX + "check-flag+json";

    /** The built rules, shared by every case. */
    private static KieContainer container;

    /** The filter under test, which holds no state between requests. */
    private final OperationsActionFilter filter = new OperationsActionFilter();

    @BeforeAll
    static void compileTheRules() {
        final KieServices services = KieServices.Factory.get();
        final KieFileSystem files = services.newKieFileSystem();
        files.write(services.getResources().newClassPathResource(RULES)
                .setResourceType(ResourceType.DRL));
        services.newKieBuilder(files).buildAll();
        container = services.newKieContainer(services.getRepository().getDefaultReleaseId());
    }

    /**
     * What the library would authorise one request as, with the filter ahead of it.
     *
     * @param method      the request's method
     * @param path        the request's path
     * @param contentType a media type to send, or {@code null} for none
     * @param accept      an {@code Accept} to send, or {@code null} for none
     * @param sentAction  what the caller put in {@code CPP-ACTION}, or {@code null} for nothing
     * @return the action name the resolver settled on
     * @throws Exception where the filter or the chain refuses, which no case here expects
     */
    private String resolvedFor(final String method, final String path, final String contentType,
            final String accept, final String sentAction) throws Exception {

        final MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (contentType != null) {
            request.setContentType(contentType);
        }
        if (accept != null) {
            request.addHeader("Accept", accept);
        }
        if (sentAction != null) {
            request.addHeader(ACTION_HEADER, sentAction);
        }
        final FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        final ArgumentCaptor<HttpServletRequest> passed =
                ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(chain).doFilter(passed.capture(), any());
        return RequestActionResolver.resolve(passed.getValue(), ACTION_HEADER, path).name();
    }

    /**
     * Fires the rules for one action on behalf of a caller in the groups given.
     *
     * @param action what the resolver settled on
     * @param groups the groups usersgroups says the caller is in
     * @return what the outcome says, which starts false and is only ever set true by a rule
     */
    // PMD.CloseResource: the session is disposed in the finally below, on every path.
    @SuppressWarnings("PMD.CloseResource")
    private static boolean admits(final String action, final String... groups) {
        final KieSession session = container.newKieSession();
        try {
            final UserAndGroupProvider provider = mock(UserAndGroupProvider.class);
            when(provider.isMemberOfAnyOfTheSuppliedGroups(any(Action.class), any(String[].class)))
                    .thenAnswer(invocation -> {
                        final String[] allowed = (String[]) invocation.getRawArguments()[1];
                        return Arrays.stream(groups).anyMatch(Arrays.asList(allowed)::contains);
                    });
            session.setGlobal("userAndGroupProvider", provider);
            final Outcome outcome = new Outcome();
            session.insert(outcome);
            session.insert(new Action(action, Map.of()));
            session.fireAllRules();
            return outcome.isSuccess();
        } finally {
            session.dispose();
        }
    }

    @Nested
    @DisplayName("with a forged vendor media type on every endpoint")
    class Forged {

        @ParameterizedTest
        @CsvSource({
            "GET,  /operations/flag,                             check-flag",
            "GET,  /operations/batches,                          list-batches",
            "GET,  /operations/registers/recorded-while-off,     list-recorded-while-off",
            "POST, /operations/batches/generate,                 generate-register",
            "POST, /operations/batches/3f2504e0-4f89-11d3-9a0c-0305e82c3301/notify, notify-register",
            "POST, /operations/registers/supersede,              supersede-before",
            "POST, /operations/exception-reports,                report-exceptions",
        })
        void a_content_type_should_resolve_to_the_endpoints_own_action(final String method,
                final String path, final String verb) throws Exception {

            assertThat(resolvedFor(method, path, FORGED, null, null))
                    .as("the media type is read before CPP-ACTION, so this is the priority that "
                            + "decides what the caller is authorised as")
                    .isEqualTo(PREFIX + verb);
        }

        @ParameterizedTest
        @CsvSource({
            "GET,  /operations/flag,                             check-flag",
            "GET,  /operations/batches,                          list-batches",
            "GET,  /operations/registers/recorded-while-off,     list-recorded-while-off",
            "POST, /operations/batches/generate,                 generate-register",
            "POST, /operations/batches/3f2504e0-4f89-11d3-9a0c-0305e82c3301/notify, notify-register",
            "POST, /operations/registers/supersede,              supersede-before",
            "POST, /operations/exception-reports,                report-exceptions",
        })
        void an_accept_should_resolve_to_the_endpoints_own_action(final String method,
                final String path, final String verb) throws Exception {

            assertThat(resolvedFor(method, path, null, FORGED + ", application/json", null))
                    .isEqualTo(PREFIX + verb);
        }

        @ParameterizedTest
        @CsvSource({
            "GET,  /operations/flag,                             check-flag",
            "GET,  /operations/batches,                          list-batches",
            "GET,  /operations/registers/recorded-while-off,     list-recorded-while-off",
            "POST, /operations/batches/generate,                 generate-register",
            "POST, /operations/batches/3f2504e0-4f89-11d3-9a0c-0305e82c3301/notify, notify-register",
            "POST, /operations/registers/supersede,              supersede-before",
            "POST, /operations/exception-reports,                report-exceptions",
        })
        void every_route_a_caller_has_should_still_leave_the_endpoints_own_action(
                final String method, final String path, final String verb) throws Exception {

            assertThat(resolvedFor(method, path, FORGED, FORGED,
                    PREFIX + "generate-register"))
                    .as("the three priorities above the computed fallback are the media type, the "
                            + "Accept and the header, and none of them may be the caller's")
                    .isEqualTo(PREFIX + verb);
        }
    }

    @Nested
    @DisplayName("and what the rules then do with it")
    class AndTheRules {

        @ParameterizedTest
        @CsvSource({
            "GET,  /operations/batches,                          list-batches",
            "POST, /operations/batches/generate,                 generate-register",
            "POST, /operations/registers/supersede,              supersede-before",
            "POST, /operations/exception-reports,                report-exceptions",
        })
        void the_admitted_group_should_be_allowed_against_the_endpoints_own_action(
                final String method, final String path, final String verb) throws Exception {

            final String resolved = resolvedFor(method, path, FORGED, null, null);

            assertThat(resolved).isEqualTo(PREFIX + verb);
            assertThat(admits(resolved, ADMITTED)).isTrue();
        }

        @ParameterizedTest
        @CsvSource({
            "GET,  /operations/batches,                          list-batches",
            "POST, /operations/batches/generate,                 generate-register",
            "POST, /operations/registers/supersede,              supersede-before",
            "POST, /operations/exception-reports,                report-exceptions",
        })
        void anybody_else_should_be_refused_although_they_forged_the_cheapest_action(
                final String method, final String path, final String verb) throws Exception {

            final String resolved = resolvedFor(method, path, FORGED, null, null);

            assertThat(resolved).isEqualTo(PREFIX + verb);
            assertThat(admits(resolved, SOMEBODY_ELSE))
                    .as("forging the action a caller is admitted to must not admit them to the "
                            + "endpoint they are actually being served")
                    .isFalse();
        }

        @Test
        void a_path_this_service_serves_nothing_on_should_fall_to_the_computed_name()
                throws Exception {
            final String resolved =
                    resolvedFor("GET", "/operations/flag/history", FORGED, null, null);

            assertThat(resolved).isEqualTo("GET /operations/flag/history");
            assertThat(admits(resolved, ADMITTED)).isFalse();
        }
    }
}
