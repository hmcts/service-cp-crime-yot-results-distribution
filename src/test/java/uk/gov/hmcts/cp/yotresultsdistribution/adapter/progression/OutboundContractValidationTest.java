package uk.gov.hmcts.cp.yotresultsdistribution.adapter.progression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ContractValidationException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ContractViolation;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterAddress;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterAlias;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterCaseOrApplication;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterCounsel;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterHearing;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterHearingVenue;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterOffence;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterParentGuardian;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterResult;

/**
 * The last thing that happens to a register before it is somebody else's problem.
 *
 * <p>Progression's {@code add-court-register} command and its nested
 * {@code courtRegisterDocument/*} schemas are frozen, closed and required-bearing, and the legacy
 * pipeline sends documents it has never checked against them. Three fields are the ones that bite:
 * {@code courtRegisterDefendant.address}, {@code courtRegisterParentGuardian.address} and, inside
 * either of those, {@code courtRegisterAddress.address1}. The address mapper answers nothing where
 * the payload holds no address, so a child with no address produces a document progression answers
 * 400 to — and the 400 is caught, logged and dropped (C1), so the hearing's whole register
 * disappears with no row, no metric and no message anywhere. It is the strongest single argument for
 * this migration, and its informant sibling was proved live.
 *
 * <p>So the document is validated <em>before</em> it is sent, against the same vendored schemas that
 * ship in main resources, and a document that would be refused becomes an explicit failure with a
 * bounded reason and the JSON pointer of the field at fault. Two things the reason deliberately does
 * not carry: the offending <em>value</em>, which is a child's address, and the validator's own
 * message, which quotes the document it choked on. Both would travel to the dead-letter description
 * and the log index.
 *
 * <p>The same check is C26's authority. The record family claims to be honest about the wire, and
 * the only proof of that is a document with every field those records declare, populated, passing a
 * schema that refuses anything it does not know.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> rows C26,
 *      C29
 */
@DisplayName("OutboundContractValidation")
class OutboundContractValidationTest {

    private static final String HEARING_ID = "1828f356-f746-4f2d-932b-79ef2df95c80";

    private static final String COURT_CENTRE_ID = "853b1ff8-fc2a-44d1-a621-0cd16419f54a";

    private static final String APPLICATION_ID = "6984d5b6-5c5d-472b-9ead-dff7a49c9600";

    private static final String FILE_NAME =
            "court-register_2020-06-01_B01LY00_" + HEARING_ID + ".pdf";

    private final OutboundContractValidator validator =
            new OutboundContractValidator(JacksonConfig.contractObjectMapper());

    @Nested
    @DisplayName("a document the contract accepts")
    class AcceptedDocuments {

        @Test
        @DisplayName("every field the records declare is a field the schemas declare")
        void records_match_the_vendored_schemas() {
            // C26. The records were written from the schemas; this is the assertion that they were
            // written from them correctly, and the one that fails the day a field is added to a
            // record that progression's closed contract does not know about.
            assertThatCode(() -> validator.validate(fullyPopulated())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a register carrying only what the contract requires is accepted")
        void a_minimal_register_is_accepted() {
            assertThatCode(() -> validator.validate(document(minimalDefendant())))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a register with no recipients at all is accepted, because none are required")
        void a_register_with_no_recipients_is_accepted() {
            // Absent is not empty. The command does not require recipients; it refuses an empty
            // array of them.
            assertThatCode(() -> validator.validate(
                    new CourtRegisterDocument(
                            "2020-06-01T10:00:00Z", "2020-01-20T00:00:00Z", HEARING_ID,
                            COURT_CENTRE_ID, FILE_NAME, null, venue(), null,
                            List.of(minimalDefendant()))))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("a child with no address (N25, C29)")
    class AddressLessDefendant {

        @Test
        @DisplayName("is an explicit failure, not a 400 nobody sees")
        void a_missing_required_address_is_an_explicit_failure() {
            assertThatThrownBy(() -> validator.validate(document(withoutAddress())))
                    .isInstanceOf(ContractValidationException.class);
        }

        @Test
        @DisplayName("names the missing field, and where on the document it was missing from")
        void names_the_missing_field_and_where() {
            assertThatThrownBy(() -> validator.validate(document(withoutAddress())))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                            ContractValidationException.class))
                    .satisfies(refused -> {
                        assertThat(refused.violation()).isEqualTo(ContractViolation.MISSING_FIELD);
                        assertThat(refused.field()).isEqualTo("/defendants/0/address");
                    });
        }

        @Test
        @DisplayName("says nothing about the child it was refused for")
        void says_nothing_about_the_child() {
            // The reason reaches the dead-letter description and the log index, and every defendant
            // on this register is a child.
            assertThatThrownBy(() -> validator.validate(document(withoutAddress())))
                    .isInstanceOf(ContractValidationException.class)
                    .hasMessageNotContaining("Fred Duncan Smith")
                    .hasMessageNotContaining("2008-04-17");
        }
    }

    @Nested
    @DisplayName("a parent with no address (N26, C29)")
    class AddressLessParentGuardian {

        @Test
        @DisplayName("is refused on the same terms as the child")
        void is_refused_on_the_same_terms() {
            final CourtRegisterDefendant defendant = withParentGuardian(
                    new CourtRegisterParentGuardian("Father - Fred Father - Smith", null));

            assertThatThrownBy(() -> validator.validate(document(defendant)))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                            ContractValidationException.class))
                    .satisfies(refused -> {
                        assertThat(refused.violation()).isEqualTo(ContractViolation.MISSING_FIELD);
                        assertThat(refused.field())
                                .isEqualTo("/defendants/0/parentGuardian/address");
                    });
        }

        @Test
        @DisplayName("and a parent with one is not")
        void and_a_parent_with_one_is_not() {
            final CourtRegisterDefendant defendant = withParentGuardian(
                    new CourtRegisterParentGuardian(
                            "Father - Fred Father - Smith", address("Father - Flat 1")));

            assertThatCode(() -> validator.validate(document(defendant)))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("an address with no first line (N27, C29)")
    class AddressWithNoFirstLine {

        @Test
        @DisplayName("is refused, because an address object is not an address")
        void an_empty_address_is_not_an_address() {
            final CourtRegisterDefendant defendant = withAddress(
                    new CourtRegisterAddress(null, "1 Old Road", "London", null, null, "SW99 1AA"));

            assertThatThrownBy(() -> validator.validate(document(defendant)))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                            ContractValidationException.class))
                    .satisfies(refused -> {
                        assertThat(refused.violation()).isEqualTo(ContractViolation.MISSING_FIELD);
                        assertThat(refused.field()).isEqualTo("/defendants/0/address/address1");
                    });
        }

        @Test
        @DisplayName("and the venue's address is held to it too")
        void the_venues_address_is_held_to_it_too() {
            final CourtRegisterDocument document = new CourtRegisterDocument(
                    "2020-06-01T10:00:00Z", "2020-01-20T00:00:00Z", HEARING_ID, COURT_CENTRE_ID,
                    FILE_NAME,
                    null,
                    new CourtRegisterHearingVenue(
                            "South West London Magistrates' Court",
                            "Lavender Hill Magistrates' Court",
                            new CourtRegisterAddress(null, null, null, null, null, "SW11 1JU")),
                    List.of(recipient()), List.of(minimalDefendant()));

            assertThatThrownBy(() -> validator.validate(document))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                            ContractValidationException.class))
                    .satisfies(refused -> assertThat(refused.field())
                            .isEqualTo("/hearingVenue/address/address1"));
        }
    }

    @Nested
    @DisplayName("what else the contract will not take")
    class OtherRefusals {

        @Test
        @DisplayName("a document with no file name")
        void a_document_with_no_file_name() {
            final CourtRegisterDocument document = new CourtRegisterDocument(
                    "2020-06-01T10:00:00Z", "2020-01-20T00:00:00Z", HEARING_ID, COURT_CENTRE_ID,
                    null, null, venue(), List.of(recipient()), List.of(minimalDefendant()));

            assertThatThrownBy(() -> validator.validate(document))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                            ContractValidationException.class))
                    .satisfies(refused -> {
                        assertThat(refused.violation()).isEqualTo(ContractViolation.MISSING_FIELD);
                        assertThat(refused.field()).isEqualTo("/fileName");
                    });
        }

        @Test
        @DisplayName("a venue that does not name the court house")
        void a_venue_that_does_not_name_the_court_house() {
            final CourtRegisterDocument document = new CourtRegisterDocument(
                    "2020-06-01T10:00:00Z", "2020-01-20T00:00:00Z", HEARING_ID, COURT_CENTRE_ID,
                    FILE_NAME,
                    null,
                    new CourtRegisterHearingVenue("South West London Magistrates' Court", null,
                            address("176A Lavender Hill")),
                    List.of(recipient()), List.of(minimalDefendant()));

            assertThatThrownBy(() -> validator.validate(document))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                            ContractValidationException.class))
                    .satisfies(refused -> assertThat(refused.field())
                            .isEqualTo("/hearingVenue/courtHouse"));
        }

        @Test
        @DisplayName("a register with no defendants on it")
        void a_register_with_no_defendants() {
            final CourtRegisterDocument document = new CourtRegisterDocument(
                    "2020-06-01T10:00:00Z", "2020-01-20T00:00:00Z", HEARING_ID, COURT_CENTRE_ID,
                    FILE_NAME, null, venue(), List.of(recipient()), List.of());

            assertThatThrownBy(() -> validator.validate(document))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                            ContractValidationException.class))
                    .satisfies(refused -> assertThat(refused.field()).isEqualTo("/defendants"));
        }

        @Test
        @DisplayName("an empty recipient list, where an absent one is fine")
        void an_empty_recipient_list() {
            // `minItems: 1` — the distinction the mappers keep between absent, null and empty is
            // this, and it is why every list on the record family keeps all three apart.
            final CourtRegisterDocument document = new CourtRegisterDocument(
                    "2020-06-01T10:00:00Z", "2020-01-20T00:00:00Z", HEARING_ID, COURT_CENTRE_ID,
                    FILE_NAME, null, venue(), List.of(), List.of(minimalDefendant()));

            assertThatThrownBy(() -> validator.validate(document))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                            ContractValidationException.class))
                    .satisfies(refused -> {
                        assertThat(refused.violation()).isEqualTo(ContractViolation.INVALID_FORMAT);
                        assertThat(refused.field()).isEqualTo("/recipients");
                    });
        }

        @Test
        @DisplayName("an application that gathered no offence, which is the register the legacy "
                + "loses to a swallowed 400")
        void an_empty_offence_list_on_a_case() {
            // The mapper keeps the legacy's `[]` rather than inventing an absence the legacy never
            // sends; `minItems: 1` is what makes it a refusal, and this is where the refusal is.
            final CourtRegisterDefendant defendant = withCases(
                    new CourtRegisterCaseOrApplication(
                            "ref", null, null, List.of(), null, null, null));

            assertThatThrownBy(() -> validator.validate(document(defendant)))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                            ContractValidationException.class))
                    .satisfies(refused -> {
                        assertThat(refused.violation()).isEqualTo(ContractViolation.INVALID_FORMAT);
                        assertThat(refused.field()).isEqualTo(
                                "/defendants/0/prosecutionCasesOrApplications/0/offences");
                    });
        }

        @Test
        @DisplayName("a case or application with no reference")
        void a_case_with_no_reference() {
            final CourtRegisterDefendant defendant = withCases(
                    new CourtRegisterCaseOrApplication(
                            null, null, null, null, null, null, "TFL0"));

            assertThatThrownBy(() -> validator.validate(document(defendant)))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                            ContractValidationException.class))
                    .satisfies(refused -> assertThat(refused.field()).isEqualTo(
                            "/defendants/0/prosecutionCasesOrApplications/0"
                                    + "/caseOrApplicationReference"));
        }

        @Test
        @DisplayName("a recipient with no email address to send to")
        void a_recipient_with_no_address_to_send_to() {
            final CourtRegisterDocument document = new CourtRegisterDocument(
                    "2020-06-01T10:00:00Z", "2020-01-20T00:00:00Z", HEARING_ID, COURT_CENTRE_ID,
                    FILE_NAME, null, venue(),
                    List.of(new CourtRegisterRecipient(
                            "Lavender Hill Youth Panel", null, null, "cr_standard")),
                    List.of(minimalDefendant()));

            assertThatThrownBy(() -> validator.validate(document))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                            ContractValidationException.class))
                    .satisfies(refused -> assertThat(refused.field())
                            .isEqualTo("/recipients/0/emailAddress1"));
        }
    }

    /**
     * A register carrying the given defendant and nothing else unusual.
     *
     * @param defendant the one defendant on it
     * @return the document
     */
    private CourtRegisterDocument document(final CourtRegisterDefendant defendant) {
        return new CourtRegisterDocument(
                "2020-06-01T10:00:00Z", "2020-01-20T00:00:00Z", HEARING_ID, COURT_CENTRE_ID,
                FILE_NAME, null, venue(), List.of(recipient()), List.of(defendant));
    }

    /**
     * A document with every field of every record populated — the only shape that proves the record
     * family and the closed contract agree.
     *
     * @return the document
     */
    private CourtRegisterDocument fullyPopulated() {
        final CourtRegisterDefendant defendant = new CourtRegisterDefendant(
                "6647df67-a065-4d07-90ba-a8daa064ecc4",
                "Fred Duncan Smith",
                "2008-04-17",
                address("Flat 1"),
                "British",
                "White - British",
                "MALE",
                "REMANDED_IN_CUSTODY",
                new CourtRegisterParentGuardian(
                        "Father - Fred Father - Smith", address("Father - Flat 1")),
                new CourtRegisterHearing(
                        "MAGISTRATES", "Sentence", true, "In person",
                        "Harold Benjamin Solicitors"),
                List.of(new CourtRegisterAlias("Mr", "John", "Duncan", "Smith")),
                List.of(new CourtRegisterCaseOrApplication(
                        "TFL4359536",
                        APPLICATION_ID,
                        "sample type",
                        List.of(new CourtRegisterOffence(
                                "PS90010", 1, "Public service vehicle - passenger use altered "
                                + "/ defaced   ticket",
                                "On 02/07/2018 at Bond street\nContrary to regulation 7(1)(a)",
                                "NOT_GUILTY", "INDICATED_GUILTY", "2019-11-14",
                                "Allocation decision - summary trial", "2019-11-14", "1234",
                                List.of(new CourtRegisterResult(
                                        "Pay by date", "cjsCode - O level")))),
                        List.of(new CourtRegisterResult("Case", "cjsCode - C level")),
                        List.of(new CourtRegisterCounsel(
                                "Prosecution Kieran Counsel", "Leading Counsel")),
                        "TFL0")),
                List.of(new CourtRegisterResult("collection order", "cjsCode - D level")),
                List.of(new CourtRegisterCounsel("James Benjamin Simpson", "Junior QC")));

        return new CourtRegisterDocument(
                "2020-06-01T10:00:00Z", "2020-01-20T00:00:00Z", HEARING_ID, COURT_CENTRE_ID,
                FILE_NAME,
                // Deliberately absent, and it is the one declared field this document leaves out.
                // `defendantType` is a legal field of the register document itself
                // (courtRegisterDocumentRequest.json:30-32) but not of the add-court-register
                // command this validator enforces, which is additionalProperties: false and does
                // not declare it. Setting it here refuses the document under UNKNOWN_FIELD
                // [/defendantType]. Which schema a recorded register is held to is T023's to settle.
                null,
                new CourtRegisterHearingVenue(
                        "South West London Magistrates' Court",
                        "Lavender Hill Magistrates' Court",
                        address("176A Lavender Hill")),
                List.of(new CourtRegisterRecipient(
                        "Youth Offending Service - South West London",
                        "yos.southwest@example.gov.uk",
                        "yos.duty@example.gov.uk",
                        "cr_youth")),
                List.of(defendant));
    }

    /** A defendant carrying the three fields the contract requires and nothing else. */
    private CourtRegisterDefendant minimalDefendant() {
        return new CourtRegisterDefendant(
                null, "Fred Duncan Smith", null, address("Flat 1"), null, null, null, null,
                null, null, null,
                List.of(new CourtRegisterCaseOrApplication(
                        "TFL4359536", null, null, null, null, null, null)),
                null, null);
    }

    /** The same defendant with no address at all — the C29 shape. */
    private CourtRegisterDefendant withoutAddress() {
        return withAddress(null);
    }

    /**
     * The minimal defendant, given the address named.
     *
     * @param address the address, or {@code null} for none
     * @return the defendant
     */
    private CourtRegisterDefendant withAddress(final CourtRegisterAddress address) {
        final CourtRegisterDefendant defendant = minimalDefendant();
        return new CourtRegisterDefendant(
                defendant.masterDefendantId(), defendant.name(), defendant.dateOfBirth(), address,
                defendant.nationality(), defendant.ethnicity(), defendant.gender(),
                defendant.postHearingCustodyStatus(), defendant.parentGuardian(),
                defendant.hearing(), defendant.aliases(),
                defendant.prosecutionCasesOrApplications(), defendant.defendantResults(),
                defendant.defenceCounsels());
    }

    /**
     * The minimal defendant, given the parent or guardian named.
     *
     * @param parentGuardian the parent or guardian
     * @return the defendant
     */
    private CourtRegisterDefendant withParentGuardian(
            final CourtRegisterParentGuardian parentGuardian) {
        final CourtRegisterDefendant defendant = minimalDefendant();
        return new CourtRegisterDefendant(
                defendant.masterDefendantId(), defendant.name(), defendant.dateOfBirth(),
                defendant.address(), defendant.nationality(), defendant.ethnicity(),
                defendant.gender(), defendant.postHearingCustodyStatus(), parentGuardian,
                defendant.hearing(), defendant.aliases(),
                defendant.prosecutionCasesOrApplications(), defendant.defendantResults(),
                defendant.defenceCounsels());
    }

    /**
     * The minimal defendant, given the cases and applications named.
     *
     * @param cases the cases and applications
     * @return the defendant
     */
    private CourtRegisterDefendant withCases(final CourtRegisterCaseOrApplication... cases) {
        final CourtRegisterDefendant defendant = minimalDefendant();
        return new CourtRegisterDefendant(
                defendant.masterDefendantId(), defendant.name(), defendant.dateOfBirth(),
                defendant.address(), defendant.nationality(), defendant.ethnicity(),
                defendant.gender(), defendant.postHearingCustodyStatus(),
                defendant.parentGuardian(), defendant.hearing(), defendant.aliases(),
                List.of(cases), defendant.defendantResults(), defendant.defenceCounsels());
    }

    /** The court the register was made at. */
    private CourtRegisterHearingVenue venue() {
        return new CourtRegisterHearingVenue(
                "South West London Magistrates' Court",
                "Lavender Hill Magistrates' Court",
                address("176A Lavender Hill"));
    }

    /** One recipient the contract accepts. */
    private CourtRegisterRecipient recipient() {
        return new CourtRegisterRecipient(
                "Lavender Hill Youth Panel", "panel@example.gov.uk", null, "cr_standard");
    }

    /**
     * An address with the first line named and the rest of a plausible one behind it.
     *
     * @param address1 the first line, which is the one the contract requires
     * @return the address
     */
    private CourtRegisterAddress address(final String address1) {
        return new CourtRegisterAddress(
                address1, "1 Old Road", "London", "Merton", "England", "SW99 1AA");
    }
}
