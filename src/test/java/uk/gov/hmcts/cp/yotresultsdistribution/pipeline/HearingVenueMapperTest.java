package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.throwable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterAddress;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterHearingVenue;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.support.LegacyFixtures;

/**
 * The court centre as the register prints it — a court house, a local justice area, and an address
 * that has never been mapped.
 *
 * <p>Twins {@code $DF/…/Mappers/HearingVenue/test/HearingVenueMapper.test.js}, whose one case
 * supplies a court centre of a name and an LJA and asserts those two fields. Its inline data has no
 * address, and neither does any court-register fixture but one — so {@code address()}
 * ({@code HearingVenueMapper.js:18-30}) has only ever taken its own early return, and the second
 * writing of the {@code postcode} → {@code postCode} case change, copied out of {@link AddressMapper}
 * rather than reused, has never executed anywhere in the tree.
 *
 * <p>The address body is therefore driven from {@code base/hearing-with-complete-court-centre.json},
 * which was authored with the address {@code setcourtregister/hearing-results-for-court-register.json}
 * carries — the one court-register fixture that has one, and one the venue mapper never sees because
 * that fixture belongs to an earlier stage.
 *
 * <p>Two guards sit either side of it. An absent {@code lja} answers with no LJA name, the ternary at
 * {@code :13} that the legacy case's fixture always satisfies. An absent court centre is the other
 * direction: the legacy constructor reads {@code this.hearingJson.courtCentre.address} eagerly at
 * {@code :7}, so it throws a TypeError before {@code build()} is even entered, and
 * {@code OutboundCourtRegister:62-64} swallows it along with the register. The port keeps the
 * refusal and classifies it — {@code courtHouse} is a required field of the frozen contract, so a
 * hearing with no court centre has no venue to print and the failure is real — but it arrives as a
 * named, non-transient transformation failure rather than as a silence.
 */
@DisplayName("HearingVenueMapper")
class HearingVenueMapperTest {

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    @Nested
    @DisplayName("Hearing Venue Mapper > Should return correct values — legacy Jest twin")
    class LegacyTwin {

        @Test
        @DisplayName("the court house is the court centre's name and the LJA name its own")
        void court_house_and_lja_name_are_copied() {
            final CourtRegisterHearingVenue venue = HearingVenueMapper.map(mapper.readTree(
                    "{\"courtCentre\":{\"name\":\"Carmarthen Magistrates' Court\","
                            + "\"lja\":{\"ljaName\":\"Mersey Courts\"}}}"));

            assertThat(venue.courtHouse()).isEqualTo("Carmarthen Magistrates' Court");
            assertThat(venue.ljaName()).isEqualTo("Mersey Courts");
        }

        @Test
        @DisplayName("a court centre with no address gives a venue with no address")
        void a_court_centre_with_no_address_gives_no_address() {
            final CourtRegisterHearingVenue venue = HearingVenueMapper.map(
                    mapper.readTree("{\"courtCentre\":{\"name\":\"Carmarthen Magistrates' "
                            + "Court\"}}"));

            assertThat(venue.address()).isNull();
        }
    }

    @Nested
    @DisplayName("the address the legacy suite never maps")
    class VenueAddress {

        @Test
        @DisplayName("the address lines pass straight through")
        void the_address_lines_pass_straight_through() {
            final CourtRegisterAddress address = completeVenue().address();

            assertThat(address).isNotNull();
            assertThat(address.address1()).isEqualTo("176A Lavender Hill");
            assertThat(address.address2()).isEqualTo("London");
            assertThat(address.address3()).isEmpty();
            assertThat(address.address4()).isEmpty();
            assertThat(address.address5()).isEmpty();
        }

        @Test
        @DisplayName("the post code changes case on the way out")
        void the_post_code_changes_case_on_the_way_out() {
            assertThat(completeVenue().address().postCode()).isEqualTo("SW11 1JU");
        }

        @Test
        @DisplayName("the court house and the LJA come off the same complete court centre")
        void the_complete_court_centre_names_its_court_house_and_lja() {
            final CourtRegisterHearingVenue venue = completeVenue();

            assertThat(venue.courtHouse()).isEqualTo("Lavender Hill Magistrates' Court");
            assertThat(venue.ljaName()).isEqualTo("South West London Magistrates' Court");
        }
    }

    @Nested
    @DisplayName("what the court centre does not carry")
    class SparseCourtCentre {

        @Test
        @DisplayName("a court centre with no local justice area names none")
        void a_court_centre_with_no_lja_names_none() {
            final CourtRegisterHearingVenue venue = HearingVenueMapper.map(
                    mapper.readTree("{\"courtCentre\":{\"name\":\"Carmarthen Magistrates' "
                            + "Court\"}}"));

            assertThat(venue.ljaName()).isNull();
        }

        @Test
        @DisplayName("a court centre with no name leaves the court house absent")
        void a_court_centre_with_no_name_leaves_the_court_house_absent() {
            final CourtRegisterHearingVenue venue =
                    HearingVenueMapper.map(mapper.readTree("{\"courtCentre\":{}}"));

            assertThat(venue.courtHouse()).isNull();
            assertThat(venue.ljaName()).isNull();
            assertThat(venue.address()).isNull();
        }
    }

    @Nested
    @DisplayName("when there is no court centre at all")
    class NoCourtCentre {

        @Test
        @DisplayName("a hearing with no court centre is a named transformation failure")
        void a_hearing_with_no_court_centre_fails_by_name() {
            assertThatThrownBy(() -> HearingVenueMapper.map(mapper.readTree("{}")))
                    .asInstanceOf(throwable(TransformationFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.NON_TRANSIENT);
                        assertThat(failure.reason()).isEqualTo(ReasonCode.TRANSFORMATION_FAILED);
                        assertThat(failure).hasMessageContaining("courtCentre");
                    });
        }

        @Test
        @DisplayName("a court centre explicitly null fails the same way")
        void a_null_court_centre_fails_the_same_way() {
            assertThatThrownBy(
                    () -> HearingVenueMapper.map(mapper.readTree("{\"courtCentre\":null}")))
                    .isInstanceOf(TransformationFailedException.class);
        }
    }

    /**
     * The venue mapped from the authored base hearing's complete court centre.
     *
     * @return the mapped venue
     */
    private CourtRegisterHearingVenue completeVenue() {
        final JsonNode hearing =
                LegacyFixtures.readBase("hearing-with-complete-court-centre.json").get("hearing");
        return HearingVenueMapper.map(hearing);
    }
}
