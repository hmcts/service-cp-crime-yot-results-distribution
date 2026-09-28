package uk.gov.hmcts.cp.yotresultsdistribution.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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
import java.util.Locale;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.cp.yotresultsdistribution.application.BatchListing;
import uk.gov.hmcts.cp.yotresultsdistribution.application.BatchListingService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.NotificationSummary;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OperationsRunLauncher;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OperationsRunLauncher.RunAccepted;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterNotifierService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterRegenerationService.Selection;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NoSuchBatchException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;

/**
 * What {@code GET /operations/batches?date=D} answers, and what it refuses.
 *
 * <p>The listing itself has a suite of its own; this one is about the mapping and the refusals -
 * that the shape is data-model §2, that the one argument is refused by name and never by value,
 * and that a store which will not answer is a {@code 503} carrying a bounded code rather than a
 * stack trace.
 *
 * <p>Both estate filters are off. The authorisation and audit conditions have suites of their own.
 */
@WebMvcTest(controllers = BatchesController.class, properties = {
    "authz.http.enabled=false",
    "audit.http.enabled=false",
    "cp.audit.enabled=false",
    // Not incidental: two of this controller's three endpoints need the generating half, so the
    // whole class is conditional on it and a slice that said nothing would 404 every case here.
    "yotresultsdistribution.generation.enabled=true",
})
@DisplayName("the batch listing endpoint")
class BatchesControllerTest {

    /** The path, written out so a change to it fails here rather than silently 404ing. */
    private static final String PATH = "/operations/batches";

    /** The register date a support call is about. */
    private static final String TYPED_DATE = "2026-09-04";

    private static final LocalDate DATE = LocalDate.parse(TYPED_DATE);

    /**
     * The same day, spelled the other way ISO_LOCAL_DATE admits.
     *
     * <p>Sent where a case is about the {@code date} a success record carries, because a request
     * made in the canonical spelling and answered with the same characters proves nothing either
     * way: it reads identically whether this service parsed the day or copied the string. A
     * parseable spelling that is not the canonical one can only come back canonical if it was
     * parsed (FR-025).
     */
    private static final String THE_SAME_DAY_SPELLED_OTHERWISE = "+002026-09-04";

    /** A value nothing else in this repository produces, so a leak can only be this one. */
    private static final String NOT_A_DATE = "ZQX7NOTADATE";

    private static final UUID BATCH = UUID.fromString("11111111-2222-4333-8444-555555555555");

    /** The generate endpoint's path, written out for the same reason the listing's is. */
    private static final String GENERATE = "/operations/batches/generate";

    /** The run id the launcher answers with, which is what a caller correlates the run by. */
    private static final String RUN_ID = "9f2b6d44-6b1a-4f0a-9d24-0cc2b0d1f3aa";

    /** A value nothing else in this repository produces, so a leak can only be this one. */
    private static final String NOT_A_UUID = "ZQX7NOTAUUID";

    /** And another, for the instant that will not read. */
    private static final String NOT_AN_INSTANT = "ZQX7NOTANINSTANT";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BatchListingService listings;

    @MockitoBean
    private OperationsRunLauncher launcher;

    @MockitoBean
    private RegisterNotifierService notifier;

    @Nested
    @DisplayName("a date that was asked for")
    class ADate {

        @Test
        void it_should_answer_the_listing_shape() throws Exception {
            when(listings.batchesOn(DATE)).thenReturn(List.of(new BatchListing(BATCH,
                    "Lewes Youth Court", BatchStatus.NOTIFIED, 12,
                    List.of(new BatchListing.Recipient("j***@yot.example.gov.uk",
                            NotificationStatus.ACCEPTED)))));

            mvc.perform(get(PATH).param("date", THE_SAME_DAY_SPELLED_OTHERWISE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.date").value(TYPED_DATE))
                    .andExpect(jsonPath("$.batches.length()").value(1))
                    .andExpect(jsonPath("$.batches[0].batchId").value(BATCH.toString()))
                    .andExpect(jsonPath("$.batches[0].courtHouse").value("Lewes Youth Court"))
                    .andExpect(jsonPath("$.batches[0].state").value("NOTIFIED"))
                    .andExpect(jsonPath("$.batches[0].records").value(12))
                    .andExpect(jsonPath("$.batches[0].recipients[0].address")
                            .value("j***@yot.example.gov.uk"))
                    .andExpect(jsonPath("$.batches[0].recipients[0].outcome").value("ACCEPTED"));
        }

        @Test
        void a_batch_with_no_court_house_should_omit_the_key() throws Exception {
            when(listings.batchesOn(DATE)).thenReturn(List.of(new BatchListing(BATCH, null,
                    BatchStatus.PENDING, 0, List.of())));

            mvc.perform(get(PATH).param("date", TYPED_DATE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.batches[0].courtHouse").doesNotExist())
                    .andExpect(jsonPath("$.batches[0].recipients").isArray());
        }

        @Test
        void a_date_holding_nothing_should_answer_an_empty_listing_rather_than_silence()
                throws Exception {
            when(listings.batchesOn(DATE)).thenReturn(List.of());

            mvc.perform(get(PATH).param("date", TYPED_DATE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.date").value(TYPED_DATE))
                    .andExpect(jsonPath("$.batches").isArray())
                    .andExpect(jsonPath("$.batches.length()").value(0));
        }
    }

    @Nested
    @DisplayName("the one argument")
    class TheArgument {

        @Test
        void an_absent_date_should_be_refused_400_missing_argument() throws Exception {
            mvc.perform(get(PATH))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("missing-argument"))
                    .andExpect(jsonPath("$.argument").value("date"));

            verifyNoInteractions(listings);
        }

        @Test
        void an_empty_date_should_be_refused_400_missing_argument() throws Exception {
            mvc.perform(get(PATH).param("date", "  "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("missing-argument"));

            verifyNoInteractions(listings);
        }

        @Test
        void a_date_that_will_not_read_should_be_refused_400_by_the_arguments_name()
                throws Exception {
            mvc.perform(get(PATH).param("date", NOT_A_DATE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("unreadable-argument"))
                    .andExpect(jsonPath("$.argument").value("date"));

            verifyNoInteractions(listings);
        }

        @Test
        void a_date_that_will_not_read_should_not_come_back_in_the_body() throws Exception {
            final String body = mvc.perform(get(PATH).param("date", NOT_A_DATE))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            Assertions.assertThat(body)
                    .doesNotContain(NOT_A_DATE)
                    .doesNotContain(PATH);
        }
    }

    @Nested
    @DisplayName("a store that will not answer")
    class NotRead {

        @Test
        void it_should_be_refused_503_listing_failed() throws Exception {
            when(listings.batchesOn(any())).thenThrow(new StoreUnavailableException(
                    "listing-failed", new SQLException("ZQX7STOREWORDS")));

            mvc.perform(get(PATH).param("date", TYPED_DATE))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.reason").value("listing-failed"));
        }

        @Test
        void it_should_carry_nothing_the_store_said_about_itself() throws Exception {
            when(listings.batchesOn(any())).thenThrow(new StoreUnavailableException(
                    "listing-failed", new SQLException("ZQX7STOREWORDS")));

            final String body = mvc.perform(get(PATH).param("date", TYPED_DATE))
                    .andReturn().getResponse().getContentAsString();

            Assertions.assertThat(body)
                    .doesNotContain("ZQX7STOREWORDS")
                    .doesNotContain("SQLException")
                    .doesNotContain(PATH);
        }

        @Test
        void a_statement_the_repository_did_not_translate_should_be_refused_the_same_way()
                throws Exception {
            when(listings.batchesOn(any())).thenThrow(
                    new DataAccessResourceFailureException("ZQX7STOREWORDS"));

            mvc.perform(get(PATH).param("date", TYPED_DATE))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.reason").value("listing-failed"));
        }

        @Test
        void a_failure_the_driver_says_is_worth_retrying_should_be_refused_the_same_way()
                throws Exception {
            when(listings.batchesOn(any())).thenThrow(
                    new TransientDataAccessResourceException("ZQX7STOREWORDS"));

            mvc.perform(get(PATH).param("date", TYPED_DATE))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.reason").value("listing-failed"));
        }
    }

    /**
     * A defect is not an outage, and must not be answered as one.
     *
     * <p>FR-023 and the design rules' status map keep {@code 503} for a dependency this endpoint
     * exists to read being unavailable, and {@code 500} for an unexpected defect. The distinction
     * is what a runbook acts on: a 503 is retried, and a defect retried is a defect retried for
     * ever. So anything out of the listing that is not one of the store's two unreachable shapes
     * leaves this controller untouched and is answered by the advice's one fallback -
     * {@code 500 UNEXPECTED}, the bounded code that says nobody classified it, carrying nothing of
     * the defect's own words.
     */
    @Nested
    @DisplayName("a defect in the listing")
    class ADefect {

        /** It is not an outage, and it is not the caller's business what it was. */
        @Test
        @DisplayName("it is answered 500 UNEXPECTED, never 503")
        void it_should_not_be_answered_as_the_store_being_unavailable() throws Exception {
            when(listings.batchesOn(any())).thenThrow(new IllegalStateException("ZQX7DEFECT"));

            mvc.perform(get(PATH).param("date", TYPED_DATE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.reason").value("UNEXPECTED"))
                    .andExpect(jsonPath("$.detail").doesNotExist());
        }

        @Test
        @DisplayName("a violated constraint is this service's defect, not the store being down")
        void a_defect_wearing_the_stores_exception_type_should_not_be_an_outage_either()
                throws Exception {
            when(listings.batchesOn(any())).thenThrow(
                    new DataIntegrityViolationException("ZQX7DEFECT"));

            mvc.perform(get(PATH).param("date", TYPED_DATE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.reason").value("UNEXPECTED"));
        }

        @Test
        void a_query_this_service_built_wrongly_should_not_be_an_outage_either() throws Exception {
            when(listings.batchesOn(any())).thenThrow(
                    new InvalidDataAccessApiUsageException("ZQX7DEFECT"));

            final String answered = mvc.perform(get(PATH).param("date", TYPED_DATE))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.reason").value("UNEXPECTED"))
                    .andReturn().getResponse().getContentAsString();

            Assertions.assertThat(answered)
                    .as("and nothing of the defect's own words comes back with it")
                    .doesNotContain("ZQX7DEFECT");
        }
    }

    /**
     * The regeneration an operator asks for: accepted, refused, or refused by name.
     *
     * <p>Every decision belongs to {@link OperationsRunLauncher} and this asserts the mapping - the
     * {@code 202} and its three fields, the three values parsed here so a refusal can name the
     * argument, and the fact that none of the four ways a request can be wrong ever quotes a
     * character the caller typed.
     */
    @Nested
    @DisplayName("the regeneration an operator asks for")
    class Generating {

        /**
         * Asks for a regeneration with the body given.
         *
         * @param body the JSON an operator posted
         * @return the request builder
         */
        private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder asking(
                final String body) {
            return post(GENERATE).contentType(MediaType.APPLICATION_JSON).content(body);
        }

        @Test
        void an_accepted_run_should_answer_202_with_the_run_id() throws Exception {
            when(launcher.launch(any())).thenReturn(new RunAccepted(RUN_ID, DATE, false));

            mvc.perform(asking("{\"date\":\"" + THE_SAME_DAY_SPELLED_OTHERWISE + "\"}"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.runId").value(RUN_ID))
                    .andExpect(jsonPath("$.date").value(TYPED_DATE))
                    .andExpect(jsonPath("$.overridden").value(false));
        }

        @Test
        void the_narrowing_should_reach_the_launcher_as_this_services_own_parse() throws Exception {
            when(launcher.launch(any())).thenReturn(new RunAccepted(RUN_ID, DATE, false));

            mvc.perform(asking("{\"date\":\"" + TYPED_DATE + "\",\"courtHouse\":\"Lewes "
                            + "Youth Court\",\"batchId\":\"" + BATCH + "\",\"recordedBefore\""
                            + ":\"2026-09-04T17:00:00Z\"}"))
                    .andExpect(status().isAccepted());

            final ArgumentCaptor<Selection> asked = ArgumentCaptor.forClass(Selection.class);
            verify(launcher).launch(asked.capture());
            Assertions.assertThat(asked.getValue())
                    .isEqualTo(new Selection(DATE, "Lewes Youth Court", BATCH,
                            Instant.parse("2026-09-04T17:00:00Z"), false));
        }

        @Test
        void an_accepted_override_should_say_so_on_the_answer() throws Exception {
            when(launcher.launch(any())).thenReturn(new RunAccepted(RUN_ID, DATE, true));

            mvc.perform(asking("{\"date\":\"" + TYPED_DATE + "\",\"batchId\":\"" + BATCH
                            + "\",\"ignoreFlag\":true}"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.overridden").value(true));
        }

        @Test
        void an_override_without_a_batch_should_be_refused_400_by_the_launchers_own_code()
                throws Exception {
            when(launcher.launch(any())).thenThrow(new OperationsRefusedException(
                    OperationsReason.OVERRIDE_REQUIRES_BATCH,
                    OperationsRunLauncher.IGNORE_FLAG, null));

            mvc.perform(asking("{\"date\":\"" + TYPED_DATE + "\",\"ignoreFlag\":true}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("OVERRIDE_REQUIRES_BATCH"))
                    .andExpect(jsonPath("$.argument").value("ignoreFlag"));
        }

        @Test
        void an_absent_date_should_be_refused_400_missing_argument() throws Exception {
            mvc.perform(asking("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("missing-argument"))
                    .andExpect(jsonPath("$.argument").value("date"));

            verifyNoInteractions(launcher);
        }

        @Test
        void no_body_at_all_should_be_refused_the_same_way() throws Exception {
            mvc.perform(post(GENERATE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("missing-argument"));

            verifyNoInteractions(launcher);
        }

        @Test
        void a_date_that_will_not_read_should_be_refused_by_the_arguments_name() throws Exception {
            mvc.perform(asking("{\"date\":\"" + NOT_A_DATE + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("unreadable-argument"))
                    .andExpect(jsonPath("$.argument").value("date"));

            verifyNoInteractions(launcher);
        }

        @Test
        void a_batch_id_that_will_not_read_should_be_refused_by_the_arguments_name()
                throws Exception {
            mvc.perform(asking("{\"date\":\"" + TYPED_DATE + "\",\"batchId\":\"" + NOT_A_UUID
                            + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("unreadable-argument"))
                    .andExpect(jsonPath("$.argument").value("batchId"));

            verifyNoInteractions(launcher);
        }

        @Test
        void a_bound_that_will_not_read_should_be_refused_by_the_arguments_name() throws Exception {
            mvc.perform(asking("{\"date\":\"" + TYPED_DATE + "\",\"recordedBefore\":\""
                            + NOT_AN_INSTANT + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("unreadable-argument"))
                    .andExpect(jsonPath("$.argument").value("recordedBefore"));

            verifyNoInteractions(launcher);
        }

        @Test
        void nothing_the_caller_typed_should_come_back_in_any_of_the_refusals() throws Exception {
            final String body = mvc.perform(asking("{\"date\":\"" + NOT_A_DATE
                            + "\",\"batchId\":\"" + NOT_A_UUID + "\",\"recordedBefore\":\""
                            + NOT_AN_INSTANT + "\",\"courtHouse\":\"ZQX7COURTHOUSE\"}"))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            Assertions.assertThat(body)
                    .as("a refusal names the argument, never the value (FR-024)")
                    .doesNotContain(NOT_A_DATE)
                    .doesNotContain(NOT_A_UUID)
                    .doesNotContain(NOT_AN_INSTANT)
                    .doesNotContain("ZQX7COURTHOUSE")
                    .doesNotContain(GENERATE);
        }

        @Test
        void a_flag_that_says_off_should_be_refused_409() throws Exception {
            when(launcher.launch(any())).thenThrow(
                    new OperationsRefusedException(OperationsReason.FLAG_OFF));

            mvc.perform(asking("{\"date\":\"" + TYPED_DATE + "\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.reason").value("flag-off"));
        }

        @Test
        void a_flag_that_cannot_be_read_should_be_refused_409() throws Exception {
            when(launcher.launch(any())).thenThrow(
                    new OperationsRefusedException(OperationsReason.FLAG_UNREADABLE));

            mvc.perform(asking("{\"date\":\"" + TYPED_DATE + "\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.reason").value("flag-unreadable"));
        }

        @Test
        void a_field_this_service_does_not_take_should_be_refused_400() throws Exception {
            mvc.perform(asking("{\"date\":\"" + TYPED_DATE + "\",\"zqx7Unknown\":true}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("unreadable-argument"));

            verifyNoInteractions(launcher);
        }
    }

    /**
     * The resend an operator asks for: settled, refused, or failed part-way.
     *
     * <p>Every decision is {@code RegisterNotifierService}'s claim and its four dispositions, and
     * this asserts that the three that are not {@code SETTLED} are three different answers rather
     * than one. It also asserts the distinctions the command could not make - a store that will
     * not answer is a {@code 503} and a far end that refused is a {@code 502}, where the CLI
     * caught both as one {@code RuntimeException} - and the one that is deliberately <em>not</em>
     * made by status alone: the notifier's {@code IllegalStateException} covers two endings of a
     * batch that <em>does</em> exist, so those keep the command's {@code resend-failed} rather
     * than telling an operator their identifier is wrong when it is right. The one that is about
     * the identifier - {@code NoSuchBatchException}, an identity nothing was ever assembled under
     * - has its own type now, and is the {@code 404} of data-model section 5.
     */
    @Nested
    @DisplayName("the resend an operator asks for")
    class Notifying {

        /** The notify path for the one batch these cases are about. */
        private static final String NOTIFY = "/operations/batches/" + "11111111-2222-4333-8444-"
                + "555555555555" + "/notify";

        @Test
        void a_settled_resend_should_answer_200_with_the_tally() throws Exception {
            when(notifier.resendFailed(BATCH))
                    .thenReturn(new NotificationSummary(3, 0, BatchStatus.NOTIFIED));

            // Carried in upper case, which a UUID reads and never writes back: the identifier on
            // the answer is the one this endpoint resolved, not the characters that arrived.
            mvc.perform(post("/operations/batches/"
                    + BATCH.toString().toUpperCase(Locale.ROOT) + "/notify"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.batchId").value(BATCH.toString()))
                    .andExpect(jsonPath("$.accepted").value(3))
                    .andExpect(jsonPath("$.failed").value(0))
                    .andExpect(jsonPath("$.state").value("NOTIFIED"))
                    .andExpect(jsonPath("$.disposition").value("settled"));
        }

        @Test
        void a_batch_somebody_else_is_telling_should_be_refused_409() throws Exception {
            when(notifier.resendFailed(BATCH)).thenReturn(NotificationSummary.alreadyNotifying(
                    1, 0, BatchStatus.GENERATED));

            mvc.perform(post(NOTIFY))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.reason").value("already-notifying"))
                    .andExpect(jsonPath("$.batchId").value(BATCH.toString()));
        }

        @Test
        void a_claim_lost_part_way_should_be_answered_500_under_its_own_code() throws Exception {
            when(notifier.resendFailed(BATCH)).thenReturn(NotificationSummary.claimLost(
                    2, 1, BatchStatus.GENERATED));

            mvc.perform(post(NOTIFY))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.reason").value("claim-lost"))
                    .andExpect(jsonPath("$.accepted").value(2))
                    .andExpect(jsonPath("$.failed").value(1));
        }

        @Test
        void a_recipient_the_store_could_not_account_for_should_be_answered_500() throws Exception {
            when(notifier.resendFailed(BATCH)).thenReturn(NotificationSummary.incomplete(
                    2, 0, BatchStatus.GENERATED));

            mvc.perform(post(NOTIFY))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.reason").value("incomplete"));
        }

        @Test
        void a_batch_id_that_is_not_an_identity_should_be_refused_400_by_the_arguments_name()
                throws Exception {
            mvc.perform(post("/operations/batches/" + NOT_A_UUID + "/notify"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("unreadable-argument"))
                    .andExpect(jsonPath("$.argument").value("batchId"));

            verifyNoInteractions(notifier);
        }

        @Test
        void a_batch_id_that_is_not_an_identity_should_not_come_back_in_the_body()
                throws Exception {
            final String body = mvc.perform(post("/operations/batches/" + NOT_A_UUID + "/notify"))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            Assertions.assertThat(body)
                    .as("the path parameter is a value the caller typed like any other")
                    .doesNotContain(NOT_A_UUID);
        }

        @Test
        void a_batch_that_was_never_assembled_should_be_answered_404_under_its_own_code()
                throws Exception {
            when(notifier.resendFailed(BATCH)).thenThrow(new NoSuchBatchException(BATCH));

            mvc.perform(post(NOTIFY))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.reason").value("UNKNOWN_BATCH"))
                    .andExpect(jsonPath("$.batchId").value(BATCH.toString()));
        }

        @Test
        void a_resend_the_notifier_would_not_make_should_answer_500_under_the_commands_code()
                throws Exception {
            when(notifier.resendFailed(BATCH)).thenThrow(
                    new IllegalStateException("register batch holds no row for that address"));

            mvc.perform(post(NOTIFY))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.reason").value("resend-failed"));
        }

        @Test
        void a_batch_that_carries_no_document_should_not_be_answered_as_no_such_batch()
                throws Exception {
            when(notifier.resendFailed(BATCH)).thenThrow(new IllegalStateException(
                    "register batch " + BATCH + " stands at PENDING and carries no document, so "
                            + "there is nothing to attach"));

            final String body = mvc.perform(post(NOTIFY))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.reason").value("resend-failed"))
                    .andReturn().getResponse().getContentAsString();

            Assertions.assertThat(body)
                    .as("a batch that exists and has not been generated is not a batch that does "
                            + "not exist, and sending an operator to check an identifier that is "
                            + "right is the one thing a bounded code must not do - which is why "
                            + "only NoSuchBatchException earns the 404")
                    .doesNotContain(OperationsReason.UNKNOWN_BATCH.wire())
                    .doesNotContain("PENDING");
        }

        @Test
        void a_store_that_will_not_answer_should_be_distinguished_from_it_with_503()
                throws Exception {
            when(notifier.resendFailed(BATCH)).thenThrow(new StoreUnavailableException(
                    "resend", new SQLException("ZQX7STOREWORDS")));

            mvc.perform(post(NOTIFY))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.reason").value("STORE_UNAVAILABLE"));
        }

        @Test
        void a_store_outage_should_carry_nothing_the_store_said_about_itself() throws Exception {
            when(notifier.resendFailed(BATCH)).thenThrow(new StoreUnavailableException(
                    "resend", new SQLException("ZQX7STOREWORDS")));

            final String body = mvc.perform(post(NOTIFY))
                    .andReturn().getResponse().getContentAsString();

            Assertions.assertThat(body)
                    .doesNotContain("ZQX7STOREWORDS")
                    .doesNotContain("SQLException");
        }

        @Test
        void notificationnotify_refusing_should_be_answered_502() throws Exception {
            when(notifier.resendFailed(BATCH)).thenThrow(
                    new NotificationFailedException(FailureClassification.NON_TRANSIENT, 400));

            mvc.perform(post(NOTIFY))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.reason").value("DOWNSTREAM_REFUSED"));
        }

        @Test
        void notificationnotify_not_answering_at_all_should_be_answered_504() throws Exception {
            when(notifier.resendFailed(BATCH)).thenThrow(
                    new NotificationFailedException(FailureClassification.TRANSIENT));

            mvc.perform(post(NOTIFY))
                    .andExpect(status().isGatewayTimeout())
                    .andExpect(jsonPath("$.reason").value("DOWNSTREAM_UNAVAILABLE"));
        }

        @Test
        @DisplayName("a violated constraint is a defect, answered 500 UNEXPECTED and not 503")
        void a_defect_in_the_resend_should_not_be_answered_as_a_dependency_failing()
                throws Exception {
            when(notifier.resendFailed(BATCH)).thenThrow(
                    new DataIntegrityViolationException("ZQX7DEFECT"));

            mvc.perform(post(NOTIFY))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.reason").value("UNEXPECTED"))
                    .andExpect(jsonPath("$.detail").doesNotExist());
        }
    }
}
