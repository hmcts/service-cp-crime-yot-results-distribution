package uk.gov.hmcts.cp.yotresultsdistribution.api;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.cp.yotresultsdistribution.application.FeatureFlagReader;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision;

/**
 * What {@code GET /operations/flag} answers, for each of the three things the lever can say.
 *
 * <p>Both estate filters are off: this slice is about the mapping, and the authorisation and audit
 * conditions have suites of their own. Generation is switched on because the flag reader is
 * contributed only where it is - which is the same sentence the controller's own condition carries.
 *
 * <p><strong>The unreadable case is the one worth reading.</strong> It is a {@code 200}, and the
 * assertion says so: an operator asking what the lever says has been answered even when the answer
 * is that nobody can read it, and a {@code 503} would have been a claim about this pod.
 */
@WebMvcTest(controllers = FlagController.class, properties = {
    "authz.http.enabled=false",
    "audit.http.enabled=false",
    "cp.audit.enabled=false",
    "yotresultsdistribution.generation.enabled=true",
})
@DisplayName("the flag endpoint")
class FlagControllerTest {

    /** The path, written out so a change to it fails here rather than silently 404ing. */
    private static final String PATH = "/operations/flag";

    /** The slice's entry point. */
    @Autowired
    private MockMvc mvc;

    /** The one lever's reader, which the controller must ask and must not remember. */
    @MockitoBean
    private FeatureFlagReader reader;

    @Nested
    @DisplayName("the three readings")
    class Readings {

        @Test
        void a_flag_that_is_on_should_answer_200_and_say_so() throws Exception {
            when(reader.read()).thenReturn(FlagDecision.ON);

            mvc.perform(get(PATH))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("application/json"))
                    .andExpect(jsonPath("$.flag").value("ON"))
                    .andExpect(jsonPath("$.reason").doesNotExist());
        }

        @Test
        void a_flag_that_is_off_should_answer_200_and_say_so() throws Exception {
            when(reader.read()).thenReturn(FlagDecision.OFF);

            mvc.perform(get(PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.flag").value("OFF"))
                    .andExpect(jsonPath("$.reason").doesNotExist());
        }

        @Test
        void a_flag_nobody_could_read_should_answer_200_and_the_bounded_cause() throws Exception {
            when(reader.read()).thenReturn(new FlagDecision.Unreadable(
                    FlagDecision.UnreadableReason.ACCESS_DENIED));

            mvc.perform(get(PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.flag").value("UNREADABLE"))
                    .andExpect(jsonPath("$.reason").value("unreadable-access-denied"));
        }

        @ParameterizedTest
        @EnumSource(FlagDecision.UnreadableReason.class)
        void every_unreadable_cause_should_reach_the_body_as_its_own_code(
                final FlagDecision.UnreadableReason cause) throws Exception {
            when(reader.read()).thenReturn(new FlagDecision.Unreadable(cause));

            mvc.perform(get(PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.flag").value("UNREADABLE"))
                    .andExpect(jsonPath("$.reason").value(cause.code()));
        }
    }

    @Nested
    @DisplayName("how the reader is used")
    class TheReader {

        @Test
        void it_should_be_asked_exactly_once_per_call() throws Exception {
            when(reader.read()).thenReturn(FlagDecision.ON);

            mvc.perform(get(PATH)).andExpect(status().isOk());

            verify(reader).read();
            verifyNoMoreInteractions(reader);
        }

        @Test
        void a_second_call_should_ask_again_rather_than_answer_from_a_cache() throws Exception {
            when(reader.read()).thenReturn(FlagDecision.ON, FlagDecision.OFF);

            mvc.perform(get(PATH)).andExpect(jsonPath("$.flag").value("ON"));
            mvc.perform(get(PATH)).andExpect(jsonPath("$.flag").value("OFF"));

            verify(reader, times(2)).read();
        }
    }

    @Nested
    @DisplayName("what the endpoint does not serve")
    class NotServed {

        @Test
        void a_method_the_endpoint_does_not_answer_should_not_reach_the_reader() throws Exception {
            mvc.perform(post(PATH));

            verifyNoMoreInteractions(reader);
        }
    }
}
