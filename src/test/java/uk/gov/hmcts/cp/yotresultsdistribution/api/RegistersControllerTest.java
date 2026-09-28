package uk.gov.hmcts.cp.yotresultsdistribution.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.cp.yotresultsdistribution.application.BatchListingService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OperationsSupersessionService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RecordedWhileOff;
import uk.gov.hmcts.cp.yotresultsdistribution.application.Supersession;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;

/**
 * What the two register endpoints answer: what is waiting, and what a rollback gives up.
 *
 * <p>The listing takes nothing, so the whole of its surface is the shape of the answer and the one
 * refusal it can make. The rollback is the opposite: almost all of its surface is refusals, and
 * every one of them is a guard that has to <strong>stop the write</strong> rather than merely
 * report on it.
 */
@WebMvcTest(controllers = RegistersController.class, properties = {
    "authz.http.enabled=false",
    "audit.http.enabled=false",
    "cp.audit.enabled=false",
})
@DisplayName("the register endpoints")
class RegistersControllerTest {

    /** The path, written out so a change to it fails here rather than silently 404ing. */
    private static final String PATH = "/operations/registers/recorded-while-off";

    private static final UUID RECORD = UUID.fromString("3f2504e0-4f89-11d3-9a0c-0305e82c3301");

    private static final UUID HEARING = UUID.fromString("9e2b4c60-1d38-4a75-9f04-6b3c8d1e5a72");

    @Autowired
    private MockMvc mvc;

    /** The rollback's path, written out so a change to it fails here rather than 404ing. */
    private static final String SUPERSEDE = "/operations/registers/supersede";

    /** A bound inside the age a rollback may reach back over. */
    private static final String TYPED_BOUND = "2026-09-04T17:00:00Z";

    private static final Instant BOUND = Instant.parse(TYPED_BOUND);

    /**
     * The same instant, spelled with the milliseconds ISO-8601 admits and this service drops.
     *
     * <p>Sent where a case is about the {@code sharedBefore} a success record carries: the
     * canonical spelling answered with the same characters would read identically whether the
     * instant was parsed or the string copied, and a parseable spelling that is not the canonical
     * one can only come back canonical if it was parsed (FR-025).
     */
    private static final String THE_SAME_INSTANT_SPELLED_OTHERWISE = "2026-09-04T17:00:00.000Z";

    /** A value nothing else in this repository produces, so a leak can only be this one. */
    private static final String NOT_AN_INSTANT = "ZQX7NOTANINSTANT";

    private static final int SUPERSEDED = 47;

    @MockitoBean
    private BatchListingService listings;

    @MockitoBean
    private OperationsSupersessionService supersession;

    @Nested
    @DisplayName("what is waiting")
    class Waiting {

        @Test
        void it_should_answer_the_listing_shape() throws Exception {
            when(listings.recordedWhileOff()).thenReturn(List.of(new RecordedWhileOff(RECORD,
                    HEARING, LocalDate.parse("2026-09-04"), RecordedFlagState.OFF)));

            mvc.perform(get(PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records.length()").value(1))
                    .andExpect(jsonPath("$.records[0].recordId").value(RECORD.toString()))
                    .andExpect(jsonPath("$.records[0].hearingId").value(HEARING.toString()))
                    .andExpect(jsonPath("$.records[0].registerDate").value("2026-09-04"))
                    .andExpect(jsonPath("$.records[0].flag").value("OFF"));
        }

        @Test
        void nothing_waiting_should_answer_an_empty_listing_rather_than_silence() throws Exception {
            when(listings.recordedWhileOff()).thenReturn(List.of());

            mvc.perform(get(PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records").isArray())
                    .andExpect(jsonPath("$.records.length()").value(0));
        }

        @Test
        void a_record_whose_flag_was_never_read_should_say_so() throws Exception {
            when(listings.recordedWhileOff()).thenReturn(List.of(new RecordedWhileOff(RECORD,
                    HEARING, LocalDate.parse("2026-09-04"), RecordedFlagState.UNKNOWN)));

            mvc.perform(get(PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records[0].flag").value("UNKNOWN"));
        }
    }

    @Nested
    @DisplayName("a store that will not answer")
    class NotRead {

        @Test
        void it_should_be_refused_503_listing_failed() throws Exception {
            when(listings.recordedWhileOff()).thenThrow(new StoreUnavailableException(
                    "listing-failed", new SQLException("ZQX7STOREWORDS")));

            mvc.perform(get(PATH))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.reason").value("listing-failed"));
        }

        @Test
        void it_should_carry_nothing_the_store_said_about_itself() throws Exception {
            when(listings.recordedWhileOff()).thenThrow(new StoreUnavailableException(
                    "listing-failed", new SQLException("ZQX7STOREWORDS")));

            final String body = mvc.perform(get(PATH))
                    .andReturn().getResponse().getContentAsString();

            Assertions.assertThat(body)
                    .doesNotContain("ZQX7STOREWORDS")
                    .doesNotContain("SQLException")
                    .doesNotContain(PATH);
        }

        @Test
        void a_statement_the_store_did_not_translate_should_be_refused_the_same_way()
                throws Exception {
            when(listings.recordedWhileOff()).thenThrow(
                    new DataAccessResourceFailureException("ZQX7STOREWORDS"));

            mvc.perform(get(PATH))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.reason").value("listing-failed"));
        }

        @Test
        void a_failure_the_driver_says_is_worth_retrying_should_be_refused_the_same_way()
                throws Exception {
            when(listings.recordedWhileOff()).thenThrow(
                    new TransientDataAccessResourceException("ZQX7STOREWORDS"));

            mvc.perform(get(PATH))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.reason").value("listing-failed"));
        }
    }

    /**
     * A defect is not an outage, for the reason {@code BatchesControllerTest} states it.
     *
     * <p>{@code 503} is the dependency being unavailable and {@code 500} is an unexpected defect
     * (FR-023): a runbook retries the first, and a defect retried is a defect retried for ever.
     */
    @Nested
    @DisplayName("a defect in the listing")
    class ADefect {

        @Test
        @DisplayName("it is answered 500 UNEXPECTED, never 503")
        void it_should_not_be_answered_as_the_store_being_unavailable() throws Exception {
            when(listings.recordedWhileOff()).thenThrow(new IllegalStateException("ZQX7DEFECT"));

            mvc.perform(get(PATH))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.reason").value("UNEXPECTED"))
                    .andExpect(jsonPath("$.detail").doesNotExist());
        }

        @Test
        @DisplayName("a violated constraint is this service's defect, not the store being down")
        void a_defect_wearing_the_stores_exception_type_should_not_be_an_outage_either()
                throws Exception {
            when(listings.recordedWhileOff()).thenThrow(
                    new DataIntegrityViolationException("ZQX7DEFECT"));

            mvc.perform(get(PATH))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.reason").value("UNEXPECTED"));
        }

        @Test
        void a_query_this_service_built_wrongly_should_not_be_an_outage_either() throws Exception {
            when(listings.recordedWhileOff()).thenThrow(
                    new InvalidDataAccessApiUsageException("ZQX7DEFECT"));

            final String answered = mvc.perform(get(PATH))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.reason").value("UNEXPECTED"))
                    .andReturn().getResponse().getContentAsString();

            Assertions.assertThat(answered)
                    .as("and nothing of the defect's own words comes back with it")
                    .doesNotContain("ZQX7DEFECT");
        }
    }

    @Nested
    @DisplayName("the rollback")
    class TheRollback {

        @Test
        @DisplayName("it answers the count, the bound and whether anything was written")
        void it_should_answer_the_count_and_the_bound() throws Exception {
            when(supersession.supersede(BOUND, false))
                    .thenReturn(new Supersession(SUPERSEDED, BOUND, false));

            mvc.perform(post(SUPERSEDE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sharedBefore\":\""
                                    + THE_SAME_INSTANT_SPELLED_OTHERWISE + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.superseded").value(SUPERSEDED))
                    .andExpect(jsonPath("$.sharedBefore").value(TYPED_BOUND))
                    .andExpect(jsonPath("$.dryRun").value(false));
        }

        @Test
        @DisplayName("a dry run says so and carries the count it would have taken")
        void a_dry_run_should_say_so() throws Exception {
            when(supersession.supersede(BOUND, true))
                    .thenReturn(new Supersession(SUPERSEDED, BOUND, true));

            mvc.perform(post(SUPERSEDE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sharedBefore\":\"" + TYPED_BOUND
                                    + "\",\"dryRun\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.superseded").value(SUPERSEDED))
                    .andExpect(jsonPath("$.dryRun").value(true));
        }

        @Test
        @DisplayName("an absent instant is a 400 and is never defaulted")
        void an_absent_instant_should_be_refused_by_name() throws Exception {
            when(supersession.supersede(eq(null), anyBoolean())).thenThrow(
                    new OperationsRefusedException(OperationsReason.MISSING_ARGUMENT,
                            "sharedBefore", null));

            mvc.perform(post(SUPERSEDE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("missing-argument"))
                    .andExpect(jsonPath("$.argument").value("sharedBefore"));
        }

        @Test
        @DisplayName("no body at all is refused exactly as an empty one is, and defaults nothing")
        void no_body_at_all_should_be_refused_the_same_way() throws Exception {
            when(supersession.supersede(eq(null), anyBoolean())).thenThrow(
                    new OperationsRefusedException(OperationsReason.MISSING_ARGUMENT,
                            "sharedBefore", null));

            mvc.perform(post(SUPERSEDE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("missing-argument"))
                    .andExpect(jsonPath("$.argument").value("sharedBefore"));
        }

        @Test
        @DisplayName("an instant that will not read is a 400 naming the argument, not the value")
        void an_unreadable_instant_should_not_be_echoed() throws Exception {
            final String answered = mvc.perform(post(SUPERSEDE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sharedBefore\":\"" + NOT_AN_INSTANT + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("unreadable-argument"))
                    .andExpect(jsonPath("$.argument").value("sharedBefore"))
                    .andReturn().getResponse().getContentAsString();

            Assertions.assertThat(answered)
                    .as("the parse is this controller's, so the refusal is this service's own "
                            + "words about its own argument (FR-025)")
                    .doesNotContain(NOT_AN_INSTANT);
            verifyNoInteractions(supersession);
        }

        @Test
        @DisplayName("an instant that has not happened yet is a 400 under its own code")
        void an_instant_in_the_future_should_answer_400() throws Exception {
            when(supersession.supersede(any(), anyBoolean())).thenThrow(
                    new OperationsRefusedException(
                            OperationsReason.SUPERSEDE_INSTANT_IN_FUTURE, "sharedBefore", null));

            mvc.perform(post(SUPERSEDE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sharedBefore\":\"" + TYPED_BOUND + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("SUPERSEDE_INSTANT_IN_FUTURE"))
                    .andExpect(jsonPath("$.argument").value("sharedBefore"));
        }

        @Test
        @DisplayName("an instant older than the configured bound is a 400 under its own code")
        void an_instant_too_old_should_answer_400() throws Exception {
            when(supersession.supersede(any(), anyBoolean())).thenThrow(
                    new OperationsRefusedException(
                            OperationsReason.SUPERSEDE_INSTANT_TOO_OLD, "sharedBefore", null));

            mvc.perform(post(SUPERSEDE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sharedBefore\":\"" + TYPED_BOUND + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("SUPERSEDE_INSTANT_TOO_OLD"));
        }

        @Test
        @DisplayName("the flag ON is a 409 - this service is the implementation that generates")
        void the_flag_on_should_answer_409() throws Exception {
            when(supersession.supersede(any(), anyBoolean()))
                    .thenThrow(new OperationsRefusedException(OperationsReason.FLAG_ON));

            mvc.perform(post(SUPERSEDE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sharedBefore\":\"" + TYPED_BOUND + "\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.reason").value("FLAG_ON"));
        }

        @Test
        @DisplayName("a flag that cannot be read is a 409, fail-closed")
        void an_unreadable_flag_should_answer_409() throws Exception {
            when(supersession.supersede(any(), anyBoolean()))
                    .thenThrow(new OperationsRefusedException(OperationsReason.FLAG_UNREADABLE));

            mvc.perform(post(SUPERSEDE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sharedBefore\":\"" + TYPED_BOUND + "\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.reason").value("flag-unreadable"));
        }

        @Test
        @DisplayName("a store that will not answer is a 503 and never a count")
        void a_store_that_refused_should_answer_503() throws Exception {
            when(supersession.supersede(any(), anyBoolean())).thenThrow(
                    new OperationsRefusedException(OperationsReason.SUPERSESSION_FAILED));

            mvc.perform(post(SUPERSEDE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sharedBefore\":\"" + TYPED_BOUND + "\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.reason").value("supersession-failed"))
                    .andExpect(jsonPath("$.superseded").doesNotExist());
        }

        @Test
        @DisplayName("a field this endpoint does not take is refused and is not quoted back")
        void an_unknown_field_should_be_refused() throws Exception {
            final String answered = mvc.perform(post(SUPERSEDE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sharedBefore\":\"" + TYPED_BOUND
                                    + "\",\"ignoreFlag\":true}"))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            Assertions.assertThat(answered)
                    .as("there is no override here and there never will be, so ignoreFlag is a "
                            + "field this request does not take (FR-021, FR-028)")
                    .contains("unreadable-argument")
                    .doesNotContain("ignoreFlag");
            verifyNoInteractions(supersession);
        }
    }
}
