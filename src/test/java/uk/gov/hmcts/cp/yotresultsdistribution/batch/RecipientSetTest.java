package uk.gov.hmcts.cp.yotresultsdistribution.batch;

import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtCentreDay;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterHearingVenue;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterRecord;

/**
 * Who one batch's document is e-mailed to, read off the registers the batch holds.
 *
 * <p>One batch is one PDF and one set of Youth Offending Teams told once, so this is the question
 * that decides whether a subscriber matched for the third hearing of a court centre's day receives
 * the document that lists their child. The answer is the de-duplicated union across the batch's
 * records, keyed on {@code emailAddress1}, each address named from the first record it appeared on,
 * in the order the addresses were first seen.
 *
 * <p><strong>Defect fix P4 and the mutation the pin is written against.</strong> The progression leg
 * filters the batch's requests down to those carrying a non-empty {@code recipients} list and then
 * takes {@code findFirst()} ({@code CourtCentreAggregate.java:71-83}, verified at {@code PROG}
 * {@code 79edf7cf3d}); every later record's recipients are discarded, and the aggregate e-mails that
 * one list. {@code recipients_are_the_union_across_the_batch_not_the_first_rows} is therefore
 * written so that it fails against <em>both</em> readings of a first-row-only implementation - the
 * literal first record of the batch, and progression's own first record that carried anybody - by
 * putting a record that matched nobody first and two records with different subscribers behind it.
 * Returning either row's list alone answers one recipient or none where the union answers two, and
 * the case names the address the mutation loses.
 *
 * <p>Subscriptions are keyed on the court centre, so in the common case every record in a batch
 * matches the same teams and the union equals the first list; the deviation bites only where
 * subscriptions differ within one court centre, which is exactly the case the legacy loses in
 * silence. It is content-affecting, so the register row carries the sign-off-before-cutover marker
 * (research §9) - and this suite is the pin that row names.
 *
 * <p><strong>Addresses are compared as the exact recorded string</strong> (as progression). The
 * legacy compares addresses nowhere at all - it keeps a list whole - so there is no case-folding
 * behaviour to port, and folding case here would be an uncatalogued content change in the direction
 * that loses an e-mail: two subscriptions whose reference data spells one mailbox differently are
 * two rows in {@code register_notification} and two teams who each get the register, not one team
 * chosen by whichever spelling was recorded first.
 *
 * <p>The empty union is not a degenerate case but a recorded outcome: a batch that matched nobody is
 * the input to NOTIFIED_NOBODY, which is defect fix P1's terminal state, and it has to be
 * distinguishable here from a batch whose records simply have not been read yet.
 *
 * <p>Nothing this suite handles may be logged. An address and a recipient name are the two
 * components that never appear at INFO or above (constitution Principle VII), which is why the
 * fixtures below are the only place they are spelled out and why the assertion descriptions name the
 * behaviour rather than the value.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("who the batch's register is e-mailed to")
class RecipientSetTest {

    /** Quoted by every case, so the red run names the seam it is waiting on. */
    private static final String PENDING = "T057 implements the union; this is its red run";

    /** What a refused seam is read as, so a missing answer fails as an assertion and not an NPE. */
    private static final List<CourtRegisterRecipient> NOBODY = List.of();

    private static final String LEEDS_YOT = "Leeds Youth Offending Team";
    private static final String LEEDS_ADDRESS = "referrals@leeds-yot.example.gov.uk";
    private static final String WAKEFIELD_YOT = "Wakefield Youth Offending Team";
    private static final String WAKEFIELD_ADDRESS = "referrals@wakefield-yot.example.gov.uk";
    private static final String BRADFORD_YOT = "Bradford Youth Offending Team";
    private static final String BRADFORD_ADDRESS = "referrals@bradford-yot.example.gov.uk";
    private static final String KIRKLEES_YOT = "Kirklees Youth Offending Team";
    private static final String KIRKLEES_ADDRESS = "referrals@kirklees-yot.example.gov.uk";
    private static final String CALDERDALE_YOT = "Calderdale Youth Offending Team";
    private static final String CALDERDALE_ADDRESS = "referrals@calderdale-yot.example.gov.uk";
    private static final String YORK_YOT = "York Youth Offending Team";
    private static final String YORK_ADDRESS = "referrals@york-yot.example.gov.uk";

    /** The same mailbox as {@link #LEEDS_ADDRESS} to a reader, and a different string to a map. */
    private static final String LEEDS_ADDRESS_SHOUTED = "REFERRALS@LEEDS-YOT.EXAMPLE.GOV.UK";

    /** The name the same team was recorded under on a later hearing, after reference data moved. */
    private static final String LEEDS_YOT_RENAMED = "Leeds Youth Justice Service";

    private static final String DEFAULT_TEMPLATE = "cr_standard";
    private static final String OTHER_TEMPLATE = "cr_leeds_pilot";

    private static final UUID LEEDS = UUID.fromString("00000000-0000-4000-8000-00000000010e");
    private static final LocalDate REGISTER_DAY = LocalDate.of(2026, 8, 20);
    private static final CourtCentreDay LEEDS_DAY = new CourtCentreDay(LEEDS, REGISTER_DAY);

    private static final String COURT_HOUSE = "Leeds Youth Court";
    private static final Instant HEARING_TIME = Instant.parse("2026-08-20T09:00:00Z");
    private static final Instant REGISTER_TIME = Instant.parse("2026-08-20T16:30:00Z");

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Puts one batch's registers in front of the union and asks who the document goes to.
     *
     * <p>The call is made inside {@code assertThatCode(...).doesNotThrowAnyException()} so that the
     * seam's refusal is recorded as an assertion rather than ending the case, and the answer it did
     * not produce is then read as {@link #NOBODY} - so every case's own assertions run, and the red
     * run is the assertion each of them makes rather than the exception one of them met.
     *
     * @param records the batch's registers, in the order the batch holds them
     * @return the union, or no recipients at all where the seam refused
     */
    private List<CourtRegisterRecipient> union(final List<RegisterRecord> records) {
        final AtomicReference<List<CourtRegisterRecipient>> answered = new AtomicReference<>();
        softly.assertThatCode(() -> answered.set(RecipientSet.unionOf(records)))
                .as(PENDING)
                .doesNotThrowAnyException();
        softly.assertThat(answered.get()).as(PENDING).isNotNull();
        return answered.get() == null ? NOBODY : answered.get();
    }

    /** One subscribing organisation, as the recipient mapper recorded it. */
    private static CourtRegisterRecipient yot(final String name, final String address) {
        return new CourtRegisterRecipient(name, address, null, DEFAULT_TEMPLATE);
    }

    /** One subscribing organisation carrying every component the contract lets it carry. */
    private static CourtRegisterRecipient yot(final String name, final String address,
            final String secondAddress, final String template) {
        return new CourtRegisterRecipient(name, address, secondAddress, template);
    }

    /** One recorded register whose document matched the given organisations. */
    private static RegisterRecord recorded(final CourtRegisterRecipient... recipients) {
        return recordedWith(List.of(recipients));
    }

    /**
     * One recorded register whose document matched nobody at all.
     *
     * <p>{@code null} rather than an empty list, because that is what the pipeline records: the
     * frozen contract puts {@code minItems: 1} on {@code recipients} and the mapper answers with
     * nothing rather than an empty array, so a register that matched nobody has no list at all.
     */
    private static RegisterRecord matchedNobody() {
        return recordedWith(null);
    }

    /** One recorded register, as the batch half reads a {@code processed_output} row back. */
    private static RegisterRecord recordedWith(final List<CourtRegisterRecipient> recipients) {
        final UUID hearingId = UUID.randomUUID();
        final String fileName = "yotresultsdistribution_2026-08-20.json";
        final CourtRegisterDocument document = new CourtRegisterDocument(
                REGISTER_TIME.toString(), HEARING_TIME.toString(), hearingId.toString(),
                LEEDS.toString(), fileName, null,
                new CourtRegisterHearingVenue("West Yorkshire", COURT_HOUSE, null), recipients,
                null);
        return new RegisterRecord(UUID.randomUUID(), hearingId, HEARING_TIME, LEEDS_DAY,
                REGISTER_TIME, fileName, null, RecordedFlagState.ON, document);
    }

    /**
     * The union itself, which is what defect fix P4 changes about the leg.
     */
    @Nested
    @DisplayName("the union across the batch")
    class TheUnion {

        /**
         * The P4 pin, written to fail against a first-row-only implementation.
         *
         * <p><strong>The mutation.</strong> Replace the union with progression's own
         * {@code CourtCentreAggregate.java:71-83} - filter the records down to those carrying a
         * non-empty recipient list and answer {@code findFirst()}'s list - and this case answers
         * only Leeds where it expects Leeds and Wakefield. Replace it with the narrower mutation
         * that reads the literal first record's list and it answers nobody at all, because the
         * record put first here is one that matched nobody. Either way the assertion below names
         * Wakefield as the address the leg loses, and Wakefield is a team that <em>was</em> matched
         * for a child listed in the document that is about to be sent.
         */
        @Test
        void recipients_are_the_union_across_the_batch_not_the_first_rows() {
            final List<CourtRegisterRecipient> recipients = union(List.of(
                    matchedNobody(),
                    recorded(yot(LEEDS_YOT, LEEDS_ADDRESS)),
                    recorded(yot(WAKEFIELD_YOT, WAKEFIELD_ADDRESS))));

            softly.assertThat(recipients)
                    .as("defect fix P4: progression keeps the first non-empty recipient list it "
                            + "sees for the court centre and discards every later record's, so a "
                            + "subscription that exists on the day's second hearing and not on its "
                            + "first is never e-mailed the register it was matched for - and "
                            + "nothing in the legacy says so")
                    .extracting(CourtRegisterRecipient::recipientName,
                            CourtRegisterRecipient::emailAddress1)
                    .containsExactly(tuple(LEEDS_YOT, LEEDS_ADDRESS),
                            tuple(WAKEFIELD_YOT, WAKEFIELD_ADDRESS));
        }

        @Test
        void one_address_on_several_records_should_be_one_recipient() {
            final List<CourtRegisterRecipient> recipients = union(List.of(
                    recorded(yot(LEEDS_YOT, LEEDS_ADDRESS)),
                    recorded(yot(LEEDS_YOT, LEEDS_ADDRESS)),
                    recorded(yot(LEEDS_YOT, LEEDS_ADDRESS))));

            softly.assertThat(recipients)
                    .as("one address, one e-mail, one register_notification row: the same Youth "
                            + "Offending Team matched on ten of the day's hearings is told once, "
                            + "and a union that kept the duplicates would send the same document "
                            + "ten times and record ten attempts against one mailbox")
                    .extracting(CourtRegisterRecipient::emailAddress1)
                    .containsExactly(LEEDS_ADDRESS);
        }

        @Test
        void the_union_should_keep_every_address_that_appeared_anywhere_in_the_batch() {
            final List<CourtRegisterRecipient> recipients = union(List.of(
                    recorded(yot(LEEDS_YOT, LEEDS_ADDRESS), yot(BRADFORD_YOT, BRADFORD_ADDRESS)),
                    recorded(yot(BRADFORD_YOT, BRADFORD_ADDRESS)),
                    recorded(yot(WAKEFIELD_YOT, WAKEFIELD_ADDRESS),
                            yot(LEEDS_YOT, LEEDS_ADDRESS))));

            softly.assertThat(recipients)
                    .as("the union is over every recipient of every record, not over the records' "
                            + "first entries: a record matching two teams contributes both, and an "
                            + "address that only ever appears second on a list is still a team "
                            + "that was matched")
                    .extracting(CourtRegisterRecipient::emailAddress1)
                    .containsExactly(LEEDS_ADDRESS, BRADFORD_ADDRESS, WAKEFIELD_ADDRESS);
        }

        @Test
        void a_record_that_matched_nobody_should_contribute_nobody_and_stop_nothing() {
            final List<CourtRegisterRecipient> recipients = union(List.of(
                    recorded(yot(LEEDS_YOT, LEEDS_ADDRESS)),
                    matchedNobody(),
                    recorded(yot(WAKEFIELD_YOT, WAKEFIELD_ADDRESS))));

            softly.assertThat(recipients)
                    .as("a register that matched no subscription is an ordinary register and one "
                            + "of this flow's two most common outcomes; it adds nobody to the "
                            + "union and it does not end the walk over the records behind it")
                    .extracting(CourtRegisterRecipient::emailAddress1)
                    .containsExactly(LEEDS_ADDRESS, WAKEFIELD_ADDRESS);
        }

        @Test
        void a_batch_whose_records_all_matched_nobody_should_union_to_no_recipients() {
            final List<CourtRegisterRecipient> recipients =
                    union(List.of(matchedNobody(), matchedNobody()));

            softly.assertThat(recipients)
                    .as("this is the input to NOTIFIED_NOBODY, which is defect fix P1's terminal "
                            + "state: the batch is generated, nobody subscribed, and the answer "
                            + "has to be an empty union rather than a refusal, or the batch sits "
                            + "GENERATED for ever waiting on an e-mail nobody is owed")
                    .isEmpty();
        }

        @Test
        void a_batch_with_no_records_should_union_to_no_recipients() {
            softly.assertThat(union(List.of()))
                    .as("the degenerate call answers nothing rather than throwing, so a caller "
                            + "that assembled nothing does not have to ask whether it may ask")
                    .isEmpty();
        }
    }

    /**
     * Which of an address's occurrences supplies the rest of the recipient, once the address itself
     * has decided that they are one recipient.
     */
    @Nested
    @DisplayName("the occurrence that names an address")
    class NamedFromTheFirstOccurrence {

        @Test
        void the_name_should_be_taken_from_the_addresss_first_occurrence() {
            final List<CourtRegisterRecipient> recipients = union(List.of(
                    recorded(yot(LEEDS_YOT, LEEDS_ADDRESS)),
                    recorded(yot(LEEDS_YOT_RENAMED, LEEDS_ADDRESS))));

            softly.assertThat(recipients)
                    .as("reference data can spell one mailbox's organisation two ways across a "
                            + "day's hearings, and the personalisation the e-mail is rendered with "
                            + "has to be decided by the batch's own order rather than by which "
                            + "record a map happened to overwrite with")
                    .extracting(CourtRegisterRecipient::recipientName)
                    .containsExactly(LEEDS_YOT);
        }

        @Test
        void the_template_and_second_address_should_come_from_that_same_occurrence() {
            final List<CourtRegisterRecipient> recipients = union(List.of(
                    recorded(yot(LEEDS_YOT, LEEDS_ADDRESS, "duty@leeds-yot.example.gov.uk",
                            DEFAULT_TEMPLATE)),
                    recorded(yot(LEEDS_YOT_RENAMED, LEEDS_ADDRESS, null, OTHER_TEMPLATE))));

            softly.assertThat(recipients)
                    .as("the recipient handed on is the first occurrence, not a merge of the "
                            + "occurrences: the template decides which e-mail notificationnotify "
                            + "renders, so a recipient whose name came from one record and whose "
                            + "template came from another is a message no record ever described")
                    .containsExactly(yot(LEEDS_YOT, LEEDS_ADDRESS,
                            "duty@leeds-yot.example.gov.uk", DEFAULT_TEMPLATE));
        }
    }

    /**
     * The order the union comes back in, which the run report and the notification rows both read.
     */
    @Nested
    @DisplayName("the order the recipients come back in")
    class StableOrder {

        @Test
        void the_recipients_should_be_ordered_by_where_each_address_was_first_seen() {
            final List<CourtRegisterRecipient> recipients = union(List.of(
                    recorded(yot(YORK_YOT, YORK_ADDRESS), yot(KIRKLEES_YOT, KIRKLEES_ADDRESS)),
                    recorded(yot(BRADFORD_YOT, BRADFORD_ADDRESS)),
                    recorded(yot(KIRKLEES_YOT, KIRKLEES_ADDRESS),
                            yot(CALDERDALE_YOT, CALDERDALE_ADDRESS)),
                    recorded(yot(LEEDS_YOT, LEEDS_ADDRESS)),
                    recorded(yot(WAKEFIELD_YOT, WAKEFIELD_ADDRESS))));

            softly.assertThat(recipients)
                    .as("six addresses in an order no hash agrees with: the union is the order the "
                            + "addresses were first seen, so a batch read twice produces the same "
                            + "recipients in the same order and a run report a person compares by "
                            + "eye reads the same way twice")
                    .extracting(CourtRegisterRecipient::emailAddress1)
                    .containsExactly(YORK_ADDRESS, KIRKLEES_ADDRESS, BRADFORD_ADDRESS,
                            CALDERDALE_ADDRESS, LEEDS_ADDRESS, WAKEFIELD_ADDRESS);
        }

        @Test
        void the_same_batch_should_union_the_same_way_every_time_it_is_read() {
            final List<RegisterRecord> records = List.of(
                    recorded(yot(YORK_YOT, YORK_ADDRESS), yot(KIRKLEES_YOT, KIRKLEES_ADDRESS)),
                    recorded(yot(BRADFORD_YOT, BRADFORD_ADDRESS)),
                    recorded(yot(CALDERDALE_YOT, CALDERDALE_ADDRESS)));

            softly.assertThat(union(records))
                    .as("the resend path unions the batch again to decide which rows it is "
                            + "re-requesting, so a second reading that ordered or named the "
                            + "recipients differently would resend against an identity the first "
                            + "reading never minted")
                    .isEqualTo(union(records));
        }
    }

    /**
     * How two recorded addresses are decided to be one, which is the one comparison this class
     * makes and the one the legacy never made.
     */
    @Nested
    @DisplayName("comparing two recorded addresses")
    class HowAddressesAreCompared {

        @Test
        void two_addresses_differing_only_in_case_should_be_two_recipients() {
            final List<CourtRegisterRecipient> recipients = union(List.of(
                    recorded(yot(LEEDS_YOT, LEEDS_ADDRESS)),
                    recorded(yot(LEEDS_YOT_RENAMED, LEEDS_ADDRESS_SHOUTED))));

            softly.assertThat(recipients)
                    .as("the exact recorded string, as progression: the legacy compares addresses "
                            + "nowhere, so there is no case-folding to port, and folding here "
                            + "would drop one of two subscriptions whose reference data spells the "
                            + "mailbox differently - an uncatalogued content change in the "
                            + "direction that loses an e-mail")
                    .extracting(CourtRegisterRecipient::emailAddress1)
                    .containsExactly(LEEDS_ADDRESS, LEEDS_ADDRESS_SHOUTED);
        }

        @Test
        void the_address_should_be_carried_through_exactly_as_it_was_recorded() {
            final List<CourtRegisterRecipient> recipients =
                    union(List.of(recorded(yot(LEEDS_YOT, LEEDS_ADDRESS))));

            softly.assertThat(recipients)
                    .as("the mapper already trimmed the address and dropped the subscription that "
                            + "had none (C29), so the union re-writes nothing: what goes in the "
                            + "notification row's sendToAddress is the string the register was "
                            + "recorded with")
                    .extracting(CourtRegisterRecipient::emailAddress1)
                    .containsExactly(LEEDS_ADDRESS);
        }
    }
}
