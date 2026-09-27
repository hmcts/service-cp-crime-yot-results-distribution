package uk.gov.hmcts.cp.yotresultsdistribution.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What a pod with no generating half answers for the three paths that need one.
 *
 * <p>This is exactly what {@code CliMain} answered on such a pod, and the status is the point: a
 * {@code 404} would read as a mistyped URL and send an operator to check the path they typed, a
 * {@code 500} would read as a bean-definition error they cannot act on, and
 * {@code 501 command-not-wired} says the one true thing - this deployment does not hold that half
 * of the service (FR-052).
 *
 * <p>That the other four endpoints are served on such a pod is asserted where it can only be
 * asserted, over the real component scan:
 * {@code config/HttpSurfaceTest.OnAPodThatRendersNothing}.
 *
 * <p>Both estate filters are off. The authorisation and audit conditions have suites of their own.
 */
@WebMvcTest(controllers = NotWiredController.class, properties = {
    "authz.http.enabled=false",
    "audit.http.enabled=false",
    "cp.audit.enabled=false",
    // The shape this controller exists for, and the only one it is registered in.
    "yotresultsdistribution.generation.enabled=false",
})
@DisplayName("a pod with no generation half")
class NotWiredControllerTest {

    /** A value nothing else in this repository produces, so a leak can only be this one. */
    private static final String TYPED = "ZQX7NOTAUUID";

    private final MockMvc mvc;

    @Autowired
    NotWiredControllerTest(final MockMvc mockMvc) {
        this.mvc = mockMvc;
    }

    @Test
    @DisplayName("the batch listing answers 501 command-not-wired")
    void the_batch_listing_should_answer_501_command_not_wired() throws Exception {
        mvc.perform(get("/operations/batches").param("date", "2026-09-04"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.reason").value("command-not-wired"));
    }

    @Test
    @DisplayName("the regeneration answers 501 command-not-wired")
    void the_regeneration_should_answer_501_command_not_wired() throws Exception {
        mvc.perform(post("/operations/batches/generate"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.reason").value("command-not-wired"));
    }

    @Test
    @DisplayName("the resend answers 501 command-not-wired")
    void the_resend_should_answer_501_command_not_wired() throws Exception {
        mvc.perform(post("/operations/batches/" + TYPED + "/notify"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.reason").value("command-not-wired"));
    }

    @Test
    @DisplayName("and none of the three quotes anything the caller asked with")
    void none_of_the_three_should_echo_what_was_asked_for() throws Exception {
        final String body = mvc.perform(post("/operations/batches/" + TYPED + "/notify"))
                .andReturn().getResponse().getContentAsString();

        Assertions.assertThat(body)
                .as("a pod that cannot serve a path still must not repeat what was typed at it")
                .doesNotContain(TYPED)
                .doesNotContain("/operations");
    }
}
