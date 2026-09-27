package uk.gov.hmcts.cp.yotresultsdistribution.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OnDemandExceptionReportService;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException;

/**
 * The status map and the no-echo rule, asserted once for the whole operations surface.
 *
 * <p>The CLI had three exit codes; HTTP has eight statuses worth using, and the value of that
 * refinement is lost the moment two endpoints answer one refusal differently. So there is one map,
 * it is closed over {@link OperationsReason}, and this is where that is proven - through a real
 * endpoint, so what is asserted is what a caller receives rather than what a table says.
 *
 * <p><strong>And nothing a caller sent comes back.</strong> Every case here drives distinctive
 * values through the request and asserts that none of them appears anywhere in the response text -
 * not in a field, not in a message, not in a framework's own words about what it could not read
 * (FR-024, constitution Principle VII).
 *
 * <p>The endpoint chosen is the exception report, because it is the one with a body: the closed
 * request contract (FR-028) can only be asserted where there is a body to carry an unknown field.
 */
@WebMvcTest(controllers = ExceptionReportsController.class, properties = {
    "authz.http.enabled=false",
    "audit.http.enabled=false",
    "cp.audit.enabled=false",
})
@DisplayName("the one status map every operations endpoint answers from")
class OperationsExceptionHandlerTest {

    private static final String PATH = "/operations/exception-reports";

    /** Values nothing else in this repository produces, so a leak can only be from the request. */
    private static final String TYPED_WINDOW = "ZQX7TYPEDWINDOW";

    private static final String TYPED_FIELD = "zqx7TypedField";

    private static final String TYPED_VALUE = "ZQX7TYPEDVALUE";

    /** An exception message that must not reach a caller under any status. */
    private static final String LIBRARY_WORDS = "ZQX7 connection string jdbc:postgresql://secret";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private OnDemandExceptionReportService reports;

    /**
     * The whole response, as text, so an echo anywhere in it fails rather than only in a field.
     *
     * @param body what to send
     * @return the response body
     * @throws Exception where the request could not be performed
     */
    private String answerTo(final String body) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
    }

    @Nested
    @DisplayName("the map itself")
    class TheMap {

        @Test
        @DisplayName("it names every bounded code exactly once")
        void the_map_should_be_closed_over_the_bounded_codes() {
            assertThat(OperationsProblem.statuses().keySet())
                    .as("a reason with no row would reach a caller as an unexplained 500, which is "
                            + "the silence this service exists to end")
                    .containsExactlyInAnyOrder(OperationsReason.values());
        }

        @Test
        @DisplayName("no bounded code is answered 500 except the three that mean it tried")
        void only_a_call_that_tried_should_be_answered_five_hundred() {
            final List<OperationsReason> asDefects = Arrays.stream(OperationsReason.values())
                    .filter(reason -> OperationsProblem.statusOf(reason).is5xxServerError())
                    .filter(reason -> OperationsProblem.statusOf(reason).value() == 500)
                    .toList();

            assertThat(asDefects)
                    .as("a 500 this service can explain is a 409, a 503 or a 502 it failed to "
                            + "classify (FR-023): what is left is the call that got part-way")
                    .containsExactlyInAnyOrder(OperationsReason.CLAIM_LOST,
                            OperationsReason.INCOMPLETE, OperationsReason.REPORT_NOT_BUILT,
                            OperationsReason.REPORT_NOT_DELIVERED,
                            OperationsReason.GENERATION_FAILED, OperationsReason.RESEND_FAILED,
                            OperationsReason.UNEXPECTED);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(OperationsReason.class)
        @DisplayName("every bounded code reaches the caller as reason, under its own status, with "
                + "no detail")
        void every_reason_should_reach_the_caller_as_a_bounded_code(final OperationsReason reason)
                throws Exception {

            when(reports.report(any(), anyBoolean()))
                    .thenThrow(new OperationsRefusedException(reason));

            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().is(OperationsProblem.statusOf(reason).value()))
                    .andExpect(jsonPath("$.reason").value(reason.wire()))
                    .andExpect(jsonPath("$.detail").doesNotExist())
                    .andExpect(jsonPath("$.instance").doesNotExist());
        }
    }

    @Nested
    @DisplayName("the closed request contract")
    class TheClosedContract {

        @Test
        @DisplayName("a field this service does not take is refused, and is not quoted back")
        void an_unknown_field_should_be_refused_without_naming_it() throws Exception {
            final String answered = answerTo(
                    "{\"" + TYPED_FIELD + "\":\"" + TYPED_VALUE + "\"}");

            assertThat(answered)
                    .as("the request contract is closed (FR-028), and the field's name is text "
                            + "somebody else chose - it is refused, not repeated")
                    .contains("\"status\":400")
                    .contains("unreadable-argument")
                    .doesNotContain(TYPED_FIELD)
                    .doesNotContain(TYPED_VALUE);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"not json at all", "{\"since\":", "[]", "{\"email\":\"maybe\"}"})
        @DisplayName("a body that will not parse is a 400 and never a stack trace")
        void a_body_that_will_not_parse_should_be_refused(final String body) throws Exception {
            final String answered = answerTo(body);

            assertThat(answered)
                    .contains("\"status\":400")
                    .contains("unreadable-argument")
                    .doesNotContain("Exception")
                    .doesNotContain("uk.gov.hmcts");
        }
    }

    @Nested
    @DisplayName("what never comes back")
    class TheNoEcho {

        @Test
        @DisplayName("no value the caller sent appears anywhere in a refusal")
        void a_refusal_should_carry_nothing_the_caller_sent() throws Exception {
            when(reports.report(any(), anyBoolean())).thenThrow(
                    new OperationsRefusedException(OperationsReason.UNREADABLE_ARGUMENT, "since",
                            new IllegalArgumentException(LIBRARY_WORDS)));

            final String answered = answerTo("{\"since\":\"" + TYPED_WINDOW + "\"}");

            assertThat(answered)
                    .contains("\"argument\":\"since\"")
                    .doesNotContain(TYPED_WINDOW)
                    .doesNotContain(LIBRARY_WORDS)
                    .doesNotContain("jdbc:");
        }

        @Test
        @DisplayName("an exception message never reaches the body")
        void an_exception_message_should_never_reach_the_body() throws Exception {
            when(reports.report(any(), anyBoolean())).thenThrow(
                    new OperationsRefusedException(OperationsReason.REPORT_NOT_BUILT, null,
                            new IllegalStateException(LIBRARY_WORDS)));

            final String answered = answerTo("{}");

            assertThat(answered)
                    .contains("report-not-built")
                    .doesNotContain(LIBRARY_WORDS)
                    .doesNotContain("IllegalStateException");
        }
    }

    @Nested
    @DisplayName("a failure nobody classified")
    class AnUnclassifiedDefect {

        @Test
        @DisplayName("it is answered 500 under a bounded code rather than left to the container")
        void an_unclassified_exception_should_be_answered_from_the_status_map() throws Exception {
            when(reports.report(any(), anyBoolean()))
                    .thenThrow(new IllegalStateException(LIBRARY_WORDS));

            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.reason").value(OperationsReason.UNEXPECTED.wire()))
                    .andExpect(jsonPath("$.detail").doesNotExist())
                    .andExpect(jsonPath("$.instance").doesNotExist());
        }

        @Test
        @DisplayName("and its own words stay where they were raised")
        void the_defects_message_should_not_reach_the_caller() throws Exception {
            when(reports.report(any(), anyBoolean()))
                    .thenThrow(new IllegalStateException(LIBRARY_WORDS));

            final String answered = answerTo("{}");

            assertThat(answered)
                    .contains(OperationsReason.UNEXPECTED.wire())
                    .doesNotContain(LIBRARY_WORDS)
                    .doesNotContain("jdbc:")
                    .doesNotContain("IllegalStateException");
        }

        @Test
        @DisplayName("a classified refusal is still answered under its own code")
        void a_bounded_refusal_should_not_be_taken_by_the_fallback() throws Exception {
            when(reports.report(any(), anyBoolean()))
                    .thenThrow(new OperationsRefusedException(OperationsReason.REPORT_NOT_BUILT));

            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.reason")
                            .value(OperationsReason.REPORT_NOT_BUILT.wire()));
        }
    }

    @Nested
    @DisplayName("a method the path does not answer on")
    class TheUnmappedMethod {

        @Test
        @DisplayName("it is refused without a stack trace and without the advice inventing a code")
        void an_unmapped_method_should_answer_without_a_stack_trace() throws Exception {
            final String answered = mvc.perform(get(PATH))
                    .andExpect(status().isMethodNotAllowed())
                    .andReturn().getResponse().getContentAsString();

            assertThat(answered)
                    .as("the advice's fallback is over RuntimeException, and an unmapped method "
                            + "is a checked ServletException the dispatcher raises before any "
                            + "handler of this package is chosen - so it keeps the framework's "
                            + "own answer, through OperationsErrorAttributes")
                    .doesNotContain("uk.gov.hmcts")
                    .doesNotContain("\tat ");
        }
    }

    @Nested
    @DisplayName("the two argument refusals no controller can reach today")
    class TheArgumentRefusals {

        /**
         * Every controller on this surface parses its own parameters as strings, so Spring
         * raises neither of these two exceptions and both handlers are unreachable. They are kept
         * deliberately: the day an endpoint takes a typed {@code @RequestParam} or
         * {@code @PathVariable}, removing them would turn the {@code 400} this service can explain
         * into the {@code 500} it cannot. Kept insurance is tested insurance - these two cases are
         * what say the promised 400 is the 400 that would arrive, and they call the advice
         * directly because no request can.
         */
        private final OperationsExceptionHandler advice = new OperationsExceptionHandler();

        @Test
        @DisplayName("an argument that will not read names the argument and never its value")
        void an_unreadable_argument_should_be_answered_400_naming_the_argument_alone() {
            final MethodArgumentTypeMismatchException mismatch =
                    new MethodArgumentTypeMismatchException(TYPED_VALUE, UUID.class, "batchId",
                            null, new IllegalArgumentException(TYPED_VALUE));

            final ResponseEntity<Object> answered = advice.unreadableArgument(mismatch);
            final ProblemDetail problem = (ProblemDetail) answered.getBody();

            assertThat(answered.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(problem).isNotNull();
            assertThat(problem.getProperties())
                    .containsEntry("reason", OperationsReason.UNREADABLE_ARGUMENT.wire())
                    .containsEntry("argument", "batchId");
            assertThat(problem.getProperties().toString())
                    .as("the characters that would not read reach no property of the body")
                    .doesNotContain(TYPED_VALUE);
            assertThat(problem.getDetail())
                    .as("nor the detail, which this advice never writes")
                    .isNull();
        }

        @Test
        @DisplayName("an argument that was not given names the argument, and this service defaults "
                + "nothing")
        void a_missing_argument_should_be_answered_400_naming_the_argument() {
            final MissingServletRequestParameterException missing =
                    new MissingServletRequestParameterException("date", "LocalDate");

            final ResponseEntity<Object> answered = advice.missingArgument(missing);
            final ProblemDetail problem = (ProblemDetail) answered.getBody();

            assertThat(answered.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(problem).isNotNull();
            assertThat(problem.getProperties())
                    .containsEntry("reason", OperationsReason.MISSING_ARGUMENT.wire())
                    .containsEntry("argument", "date");
        }
    }
}
