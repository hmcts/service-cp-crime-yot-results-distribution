package uk.gov.hmcts.cp.yotresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtCentreDay;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterRecord;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterNotificationRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PersonalDataMarkers;

/**
 * The two listings a support call about a register is answered from.
 *
 * <p>{@code ListBatchesCli}'s reads, moved here so that no controller holds a repository. The cases
 * below are that command's, re-pointed at values: the same reads in the same order, the same
 * masking rule character for character, and the same refusal to hand back a listing that was only
 * partly read.
 *
 * <p><strong>The output is about children, and this suite is what holds it to saying as little as
 * it can</strong> (constitution Principle VII). The records the counts are taken from carry every
 * field a register document has for a child, and the listing carries a count; the recipients are
 * masked addresses and never names.
 */
@DisplayName("the batch listings")
class BatchListingServiceTest {

    /** The register date a support call is about. */
    private static final LocalDate DATE = LocalDate.parse("2026-09-04");

    private static final UUID COURT_CENTRE =
            UUID.fromString("2a3d5e70-1c4b-4a8e-9f61-70b2c9d4e5f6");

    private static final Instant MOMENT = Instant.parse("2026-09-04T17:31:02Z");

    /** The template every register e-mail goes out under. */
    private static final UUID TEMPLATE = UUID.fromString("9c1f4b2e-88a7-4d35-b0e6-1f7a3c5d9e20");

    /** The file name a batch's document is rendered under, stated once. */
    private static final String FILE_NAME = "court-register_2026-09-04_B01LY00.pdf";

    private final RegisterBatchRepository batches = mock(RegisterBatchRepository.class);
    private final RegisterNotificationRepository notifications =
            mock(RegisterNotificationRepository.class);
    private final RegisterStore store = mock(RegisterStore.class);

    private final BatchListingService listings =
            new BatchListingService(batches, notifications, store);

    private static RegisterBatch batch(final String courtHouse, final BatchStatus status) {
        return new RegisterBatch(UUID.randomUUID(), COURT_CENTRE, "B01LY00", courtHouse, DATE,
                FILE_NAME, UUID.randomUUID(), UUID.randomUUID(), status, null, null, true, null,
                MOMENT, MOMENT, MOMENT, null, null, 1, null, 0);
    }

    private static RegisterNotification notification(final UUID batchId, final String address,
            final NotificationStatus status) {

        return new RegisterNotification(UUID.randomUUID(), batchId, address,
                PersonalDataMarkers.RECIPIENT_ORGANISATION, "cr_standard", TEMPLATE, status, null,
                MOMENT, 1);
    }

    /**
     * One recorded register, carrying a child in every field the document has for one.
     *
     * <p>The markers are what the privacy case looks for: a value nothing else in this repository
     * produces fails only when it really did reach the listing.
     */
    private static RegisterRecord record(final RecordedFlagState flagState) {
        final CourtRegisterDefendant defendant = new CourtRegisterDefendant(
                UUID.randomUUID().toString(), PersonalDataMarkers.CHILD_NAME,
                PersonalDataMarkers.DATE_OF_BIRTH, null, null, PersonalDataMarkers.ETHNICITY,
                "MALE", "Not Applicable", null, null, null, null, null, null);
        final CourtRegisterDocument document = new CourtRegisterDocument("2026-09-04",
                "2026-09-03T00:00:00Z", UUID.randomUUID().toString(), COURT_CENTRE.toString(),
                FILE_NAME, null, null, null, List.of(defendant));
        return new RegisterRecord(UUID.randomUUID(), UUID.randomUUID(), MOMENT,
                new CourtCentreDay(COURT_CENTRE, DATE), MOMENT, FILE_NAME, null, flagState,
                document);
    }

    /** Stubs a batch's two reads: how many registers it holds and who it was addressed to. */
    private void holding(final RegisterBatch batch, final List<RegisterRecord> records,
            final List<RegisterNotification> recipients) {

        when(store.batched(batch.batchId())).thenReturn(records);
        when(notifications.findByBatchId(batch.batchId())).thenReturn(recipients);
    }

    @Nested
    @DisplayName("a date's batches")
    class ADate {

        @Test
        void it_should_carry_each_batch_with_its_count_and_its_recipients() {
            final RegisterBatch batch = batch("Lewes Youth Court", BatchStatus.NOTIFIED);
            when(batches.findByRegisterDate(DATE)).thenReturn(List.of(batch));
            holding(batch, List.of(record(RecordedFlagState.ON), record(RecordedFlagState.ON)),
                    List.of(notification(batch.batchId(), "jane.doe@yot.example.gov.uk",
                            NotificationStatus.ACCEPTED)));

            assertThat(listings.batchesOn(DATE)).singleElement().satisfies(listed -> {
                assertThat(listed.batchId()).isEqualTo(batch.batchId());
                assertThat(listed.courtHouse()).isEqualTo("Lewes Youth Court");
                assertThat(listed.state()).isEqualTo(BatchStatus.NOTIFIED);
                assertThat(listed.records()).isEqualTo(2);
                assertThat(listed.recipients()).containsExactly(new BatchListing.Recipient(
                        "j***@yot.example.gov.uk", NotificationStatus.ACCEPTED));
            });
        }

        @Test
        void it_should_hand_the_batches_back_in_the_statements_order() {
            final RegisterBatch first = batch("Aylesbury Youth Court", BatchStatus.NOTIFIED);
            final RegisterBatch second = batch("Brighton Youth Court", BatchStatus.FAILED);
            final RegisterBatch third = batch("Crawley Youth Court", BatchStatus.GENERATED);
            when(batches.findByRegisterDate(DATE)).thenReturn(List.of(first, second, third));
            holding(first, List.of(), List.of());
            holding(second, List.of(), List.of());
            holding(third, List.of(), List.of());

            assertThat(listings.batchesOn(DATE))
                    .extracting(BatchListing::batchId)
                    .containsExactly(first.batchId(), second.batchId(), third.batchId());
        }

        @Test
        void it_should_hand_the_recipients_back_in_the_statements_order() {
            final RegisterBatch batch = batch("Lewes Youth Court", BatchStatus.PARTIALLY_NOTIFIED);
            when(batches.findByRegisterDate(DATE)).thenReturn(List.of(batch));
            holding(batch, List.of(), List.of(
                    notification(batch.batchId(), "anna@yot.example.gov.uk",
                            NotificationStatus.ACCEPTED),
                    notification(batch.batchId(), "brian@yot.example.gov.uk",
                            NotificationStatus.FAILED),
                    notification(batch.batchId(), "carol@yot.example.gov.uk",
                            NotificationStatus.PENDING)));

            assertThat(listings.batchesOn(DATE)).singleElement()
                    .extracting(BatchListing::recipients)
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                    .containsExactly(
                            new BatchListing.Recipient("a***@yot.example.gov.uk",
                                    NotificationStatus.ACCEPTED),
                            new BatchListing.Recipient("b***@yot.example.gov.uk",
                                    NotificationStatus.FAILED),
                            new BatchListing.Recipient("c***@yot.example.gov.uk",
                                    NotificationStatus.PENDING));
        }

        @Test
        void a_date_with_no_batches_should_be_an_empty_listing_rather_than_silence() {
            when(batches.findByRegisterDate(DATE)).thenReturn(List.of());

            assertThat(listings.batchesOn(DATE)).isEmpty();
        }

        @Test
        void a_batch_with_no_court_house_should_carry_none_rather_than_a_placeholder() {
            final RegisterBatch batch = batch(null, BatchStatus.PENDING);
            when(batches.findByRegisterDate(DATE)).thenReturn(List.of(batch));
            holding(batch, List.of(), List.of());

            assertThat(listings.batchesOn(DATE)).singleElement()
                    .extracting(BatchListing::courtHouse).isNull();
        }

        @Test
        void a_batch_whose_court_house_is_empty_should_carry_none_either() {
            final RegisterBatch batch = batch("", BatchStatus.PENDING);
            when(batches.findByRegisterDate(DATE)).thenReturn(List.of(batch));
            holding(batch, List.of(), List.of());

            assertThat(listings.batchesOn(DATE)).singleElement()
                    .extracting(BatchListing::courtHouse).isNull();
        }

        @Test
        void it_should_carry_nothing_of_the_children_the_registers_are_about() {
            final RegisterBatch batch = batch("Lewes Youth Court", BatchStatus.NOTIFIED);
            when(batches.findByRegisterDate(DATE)).thenReturn(List.of(batch));
            holding(batch, List.of(record(RecordedFlagState.ON)),
                    List.of(notification(batch.batchId(), "jane.doe@yot.example.gov.uk",
                            NotificationStatus.ACCEPTED)));

            assertThat(listings.batchesOn(DATE).toString())
                    .doesNotContain(PersonalDataMarkers.CHILD_NAME)
                    .doesNotContain(PersonalDataMarkers.DATE_OF_BIRTH)
                    .doesNotContain(PersonalDataMarkers.ETHNICITY)
                    .doesNotContain(PersonalDataMarkers.RECIPIENT_ORGANISATION)
                    .doesNotContain("jane.doe@yot.example.gov.uk");
        }
    }

    @Nested
    @DisplayName("the masking rule, character for character")
    class Masking {

        @ParameterizedTest
        @CsvSource({
            "jane.doe@yot.example.gov.uk, j***@yot.example.gov.uk",
            "ab@yot.example.gov.uk,       a***@yot.example.gov.uk",
            "a@yot.example.gov.uk,        ***@yot.example.gov.uk",
            "@yot.example.gov.uk,         ***@yot.example.gov.uk",
            "yot.example.gov.uk,          ***",
            "'',                          ***",
        })
        void an_address_should_be_masked_exactly_as_the_command_masked_it(final String address,
                final String masked) {

            final RegisterBatch batch = batch("Lewes Youth Court", BatchStatus.NOTIFIED);
            when(batches.findByRegisterDate(DATE)).thenReturn(List.of(batch));
            holding(batch, List.of(), List.of(notification(batch.batchId(), address,
                    NotificationStatus.ACCEPTED)));

            assertThat(listings.batchesOn(DATE)).singleElement()
                    .extracting(listed -> listed.recipients().getFirst().address())
                    .isEqualTo(masked);
        }

        @Test
        void a_row_with_no_address_at_all_should_be_masked_whole() {
            final RegisterBatch batch = batch("Lewes Youth Court", BatchStatus.NOTIFIED);
            when(batches.findByRegisterDate(DATE)).thenReturn(List.of(batch));
            holding(batch, List.of(), List.of(notification(batch.batchId(), null,
                    NotificationStatus.FAILED)));

            assertThat(listings.batchesOn(DATE)).singleElement()
                    .extracting(listed -> listed.recipients().getFirst().address())
                    .isEqualTo("***");
        }
    }

    @Nested
    @DisplayName("the registers recorded while the flag was off")
    class WhileOff {

        @Test
        void it_should_carry_the_record_the_hearing_the_date_and_the_flag() {
            final RegisterRecord waiting = record(RecordedFlagState.OFF);
            when(store.recordedWhileOff()).thenReturn(List.of(waiting));

            assertThat(listings.recordedWhileOff()).containsExactly(new RecordedWhileOff(
                    waiting.outputId(), waiting.hearingId(), DATE, RecordedFlagState.OFF));
        }

        @Test
        void it_should_carry_a_record_whose_flag_was_never_read_as_such() {
            when(store.recordedWhileOff()).thenReturn(List.of(record(RecordedFlagState.UNKNOWN)));

            assertThat(listings.recordedWhileOff()).singleElement()
                    .extracting(RecordedWhileOff::flag).isEqualTo(RecordedFlagState.UNKNOWN);
        }

        @Test
        void nothing_waiting_should_be_an_empty_listing_rather_than_silence() {
            when(store.recordedWhileOff()).thenReturn(List.of());

            assertThat(listings.recordedWhileOff()).isEmpty();
        }

        @Test
        void it_should_make_neither_of_the_dates_two_reads() {
            when(store.recordedWhileOff()).thenReturn(List.of(record(RecordedFlagState.OFF)));

            listings.recordedWhileOff();

            verifyNoInteractions(batches, notifications);
        }

        @Test
        void it_should_carry_nothing_of_the_children_the_registers_are_about() {
            when(store.recordedWhileOff()).thenReturn(List.of(record(RecordedFlagState.OFF)));

            assertThat(listings.recordedWhileOff().toString())
                    .doesNotContain(PersonalDataMarkers.CHILD_NAME)
                    .doesNotContain(PersonalDataMarkers.DATE_OF_BIRTH)
                    .doesNotContain(PersonalDataMarkers.ETHNICITY);
        }
    }

    @Nested
    @DisplayName("a read that could not be made")
    class NotRead {

        @Test
        void a_date_whose_batches_will_not_read_should_propagate_rather_than_answer_partly() {
            when(batches.findByRegisterDate(DATE))
                    .thenThrow(new StoreUnavailableException("listing-failed",
                            new SQLException("the store's own words")));

            assertThatThrownBy(() -> listings.batchesOn(DATE))
                    .isInstanceOf(StoreUnavailableException.class);
        }

        @Test
        void a_count_that_will_not_read_should_propagate_rather_than_answer_partly() {
            final RegisterBatch first = batch("Aylesbury Youth Court", BatchStatus.NOTIFIED);
            final RegisterBatch second = batch("Brighton Youth Court", BatchStatus.NOTIFIED);
            when(batches.findByRegisterDate(DATE)).thenReturn(List.of(first, second));
            holding(first, List.of(), List.of());
            when(store.batched(second.batchId()))
                    .thenThrow(new StoreUnavailableException("listing-failed",
                            new SQLException("the store's own words")));

            assertThatThrownBy(() -> listings.batchesOn(DATE))
                    .isInstanceOf(StoreUnavailableException.class);
        }

        @Test
        void recipients_that_will_not_read_should_propagate_rather_than_answer_partly() {
            final RegisterBatch batch = batch("Lewes Youth Court", BatchStatus.NOTIFIED);
            when(batches.findByRegisterDate(DATE)).thenReturn(List.of(batch));
            when(store.batched(batch.batchId())).thenReturn(List.of());
            when(notifications.findByBatchId(batch.batchId()))
                    .thenThrow(new StoreUnavailableException("listing-failed",
                            new SQLException("the store's own words")));

            assertThatThrownBy(() -> listings.batchesOn(DATE))
                    .isInstanceOf(StoreUnavailableException.class);
        }

        @Test
        void a_waiting_listing_that_will_not_read_should_propagate() {
            when(store.recordedWhileOff())
                    .thenThrow(new StoreUnavailableException("listing-failed",
                            new SQLException("the store's own words")));

            assertThatThrownBy(listings::recordedWhileOff)
                    .isInstanceOf(StoreUnavailableException.class);
        }
    }
}
