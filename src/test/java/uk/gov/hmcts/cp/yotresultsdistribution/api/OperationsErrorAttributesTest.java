package uk.gov.hmcts.cp.yotresultsdistribution.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

/**
 * What comes back from the answers this service does not write.
 *
 * <p>The authorisation filter refuses through {@code sendError}, which forwards to {@code /error}
 * and reaches no advice of ours (research R5), so the 401 and the 403 an operations caller is most
 * likely to meet are rendered by the container. Boot's default body carries the request path, and
 * on an unmapped path the request path is a string the caller typed.
 *
 * <p>Every case below asks for <strong>everything</strong> - the exception, the message, the stack
 * trace, the binding errors and the path - and asserts that none of it arrives. A body that is
 * bounded only while the defaults happen to be off is one configuration key away from not being
 * bounded at all.
 */
@DisplayName("the body of an error this service did not write")
class OperationsErrorAttributesTest {

    /** The attribute the container records the status it is rendering under. */
    private static final String STATUS_ATTRIBUTE = "jakarta.servlet.error.status_code";

    /** The attribute the container records the original path under. */
    private static final String PATH_ATTRIBUTE = "jakarta.servlet.error.request_uri";

    /** The attribute the container records its own message under. */
    private static final String MESSAGE_ATTRIBUTE = "jakarta.servlet.error.message";

    /** A path a caller could have typed, distinctive enough to find anywhere it leaked to. */
    private static final String TYPED = "/operations/../etc/passwd?marker=CALLERTYPEDVALUE";

    /** Words a container or a library might have put in a message, and that may not come back. */
    private static final String LIBRARY_WORDS = "jdbc:postgresql://host/db failed for user sa";

    /** The bean under test, which holds nothing between calls. */
    private final OperationsErrorAttributes attributes = new OperationsErrorAttributes();

    /**
     * Renders one error the way the container would ask for it, with every option switched on.
     *
     * @param status what the container recorded as the status it is rendering
     * @return the body this service would answer with
     */
    private Map<String, Object> bodyFor(final int status) {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(STATUS_ATTRIBUTE, status);
        request.setAttribute(PATH_ATTRIBUTE, TYPED);
        request.setAttribute(MESSAGE_ATTRIBUTE, LIBRARY_WORDS);
        final WebRequest wrapped = new ServletWebRequest(request);
        return attributes.getErrorAttributes(wrapped, ErrorAttributeOptions.of(
                ErrorAttributeOptions.Include.MESSAGE,
                ErrorAttributeOptions.Include.STACK_TRACE,
                ErrorAttributeOptions.Include.BINDING_ERRORS,
                ErrorAttributeOptions.Include.PATH,
                ErrorAttributeOptions.Include.STATUS,
                ErrorAttributeOptions.Include.ERROR));
    }

    @Nested
    @DisplayName("the two refusals the authorisation filter writes")
    class TheFiltersRefusals {

        @ParameterizedTest
        @CsvSource({
            "401, Unauthorized, not-identified",
            "403, Forbidden,    not-permitted",
        })
        void it_should_carry_the_status_a_title_and_a_bounded_reason(final int status,
                final String title, final String reason) {
            assertThat(bodyFor(status))
                    .containsExactlyInAnyOrderEntriesOf(
                            Map.of("status", status, "title", title, "reason", reason));
        }

        @ParameterizedTest
        @CsvSource({"401", "403"})
        void it_should_carry_nothing_the_framework_would_have_added(final int status) {
            assertThat(bodyFor(status))
                    .doesNotContainKeys("path", "trace", "message", "exception", "errors",
                            "timestamp", "error");
        }
    }

    @Nested
    @DisplayName("a path nothing is mapped to")
    class Unmapped {

        @Test
        void it_should_not_contain_the_path_that_was_tried() {
            assertThat(bodyFor(404).values())
                    .allSatisfy(value -> assertThat(String.valueOf(value))
                            .doesNotContain("CALLERTYPEDVALUE")
                            .doesNotContain("passwd"));
        }

        @Test
        void it_should_not_contain_anything_a_library_said() {
            assertThat(bodyFor(404).values())
                    .allSatisfy(value -> assertThat(String.valueOf(value))
                            .doesNotContain("jdbc")
                            .doesNotContain("sa"));
        }

        @Test
        void it_should_answer_under_its_own_bounded_reason() {
            assertThat(bodyFor(404))
                    .containsEntry("reason", "no-such-path")
                    .containsEntry("title", "Not Found");
        }

        @Test
        void a_method_that_is_not_served_should_answer_under_its_own_reason() {
            assertThat(bodyFor(405)).containsEntry("reason", "method-not-allowed");
        }
    }

    @Nested
    @DisplayName("anything else the container renders")
    class TheRest {

        @Test
        void another_refusal_of_the_request_should_fall_to_the_bounded_family_code() {
            assertThat(bodyFor(415)).containsEntry("reason", "request-refused");
        }

        @Test
        void a_failure_should_fall_to_the_bounded_family_code() {
            assertThat(bodyFor(500)).containsEntry("reason", "unexpected-failure");
        }

        @Test
        void a_status_the_framework_does_not_name_should_still_carry_a_title() {
            assertThat(bodyFor(499))
                    .containsEntry("title", "Error")
                    .containsEntry("reason", "request-refused");
        }

        @Test
        void an_error_the_container_recorded_no_status_for_should_be_a_failure() {
            final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
            request.setAttribute(PATH_ATTRIBUTE, TYPED);
            assertThat(attributes.getErrorAttributes(new ServletWebRequest(request),
                    ErrorAttributeOptions.defaults()))
                    .containsExactlyInAnyOrderEntriesOf(Map.of("status", 500,
                            "title", "Internal Server Error", "reason", "unexpected-failure"));
        }
    }
}
