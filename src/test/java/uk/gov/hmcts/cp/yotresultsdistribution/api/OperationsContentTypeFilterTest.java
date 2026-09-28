package uk.gov.hmcts.cp.yotresultsdistribution.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The one content type that would reach an operations action with no audit event, refused.
 *
 * <p>These cases were {@code OperationsActionFilterTest}'s until the guard was given a filter of
 * its own, and they say the same things about the same behaviour: every endpoint refuses it, the
 * family is matched however it was spelled, the JSON the endpoints actually take still reaches the
 * chain, and a path that is not this service's is passed through untouched.
 *
 * <p>What they cannot say, because a filter run over servlet mocks has no chain around it, is
 * <em>where</em> the guard sits. That is the reason it moved, and it is pinned in
 * {@code OperationsAuthzIT}: an anonymous caller declaring a multipart body is answered
 * {@code 401}, a caller in the wrong group {@code 403}, and an admitted one {@code 415}.
 */
@DisplayName("a content type the audit filter would not see")
class OperationsContentTypeFilterTest {

    /** What a caller sends to make the audit filter hand the chain on without publishing. */
    private static final String MULTIPART = "multipart/form-data; boundary=----x";

    /** The bounded code such a call is refused under. */
    private static final String REFUSED_AS = "UNSUPPORTED_CONTENT_TYPE";

    /** The filter under test, which holds no state between requests. */
    private final OperationsContentTypeFilter filter = new OperationsContentTypeFilter();

    /**
     * Runs one request through the filter and reports what the caller was answered.
     *
     * @param method      the request's method
     * @param path        the request's path
     * @param contentType what the caller declared the body was
     * @param chain       the chain, so a case can assert whether it was reached
     * @return the response as it would leave
     * @throws Exception where the filter refuses to write, which no case here expects
     */
    private MockHttpServletResponse answered(final String method, final String path,
            final String contentType, final FilterChain chain) throws Exception {

        final MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContentType(contentType);
        final MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @ParameterizedTest
    @CsvSource({
        "GET,  /operations/flag",
        "GET,  /operations/batches",
        "GET,  /operations/registers/recorded-while-off",
        "POST, /operations/batches/generate",
        "POST, /operations/batches/3f2504e0-4f89-11d3-9a0c-0305e82c3301/notify",
        "POST, /operations/registers/supersede",
        "POST, /operations/exception-reports",
    })
    void a_multipart_call_to_any_endpoint_should_be_refused_before_the_chain(
            final String method, final String path) throws Exception {

        final FilterChain chain = mock(FilterChain.class);

        final MockHttpServletResponse response = answered(method, path, MULTIPART, chain);

        assertThat(response.getStatus())
                .as("cp-audit-filter-springboot hands a multipart request straight down the "
                        + "chain and publishes neither event, so an endpoint reachable with "
                        + "one is an endpoint reachable unaudited (Principle III(b))")
                .isEqualTo(415);
        assertThat(response.getContentAsString()).contains(REFUSED_AS);
        verifyNoInteractions(chain);
    }

    @Test
    void a_multipart_call_to_a_path_that_is_not_ours_should_be_passed_through()
            throws Exception {

        final FilterChain chain = mock(FilterChain.class);

        final MockHttpServletResponse response =
                answered("POST", "/actuator/loggers", MULTIPART, chain);

        assertThat(response.getStatus())
                .as("the actuator is not this surface and this filter refuses nothing on its "
                        + "behalf")
                .isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void the_family_should_be_matched_however_the_caller_spelled_it() throws Exception {
        final FilterChain chain = mock(FilterChain.class);

        final MockHttpServletResponse response = answered("POST",
                "/operations/exception-reports", "MULTIPART/MIXED; boundary=b", chain);

        assertThat(response.getStatus()).isEqualTo(415);
        verifyNoInteractions(chain);
    }

    @Test
    void the_json_every_endpoint_actually_takes_should_still_reach_the_chain()
            throws Exception {

        final FilterChain chain = mock(FilterChain.class);

        answered("POST", "/operations/exception-reports", "application/json", chain);

        verify(chain).doFilter(any(), any());
    }
}
