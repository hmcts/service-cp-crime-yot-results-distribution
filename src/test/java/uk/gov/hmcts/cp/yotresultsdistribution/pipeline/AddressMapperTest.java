package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterAddress;
import uk.gov.hmcts.cp.yotresultsdistribution.support.LegacyFixtures;

/**
 * The address mapper — six fields, and the one legacy test file that has never run.
 *
 * <p>{@code $DF/…/Mappers/Address/test/AddressMapperTest.js} misses Jest's {@code *.test.js}
 * pattern, so neither of its two cases has ever executed (defect C28). That is not a technicality:
 * both cases are wrong, and running them would have said so.
 *
 * <ul>
 *   <li>Its first case ({@code :4}) asserts {@code toEqual([])} against a mapper whose absent branch
 *       returns {@code undefined} ({@code AddressMapper.js:6-8}). The repaired assertion is the one
 *       the mapper actually makes, and the distinction matters beyond tidiness — absent, null and
 *       empty are three different answers on the wire, which is the rule the comparator vectors
 *       exist to hold, and an address that arrives as an empty object rather than as nothing is
 *       exactly the C29 shape that the frozen contract rejects.</li>
 *   <li>Its second case ({@code :13}) calls {@code new Address(fakeAddress).build()} — {@code
 *       Address} is the model, not the mapper, and is not even imported into that file. It would
 *       throw {@code ReferenceError} on the first line of the act. The repaired case maps through
 *       the mapper, and adds the two assertions the original could not have made: {@code address5},
 *       which its inline data has no value for, and the {@code postcode} → {@code postCode} case
 *       change, which is the only transformation this mapper performs.</li>
 * </ul>
 *
 * <p>The populated case is driven from {@code mappers/parentguardian/hearing-resulted.json} rather
 * than from inline data: those five {@code "Father - …"} values are where the legacy file's inline
 * data came from, and reading them from the fixture keeps the twin anchored to a payload the legacy
 * suite really carries.
 */
@DisplayName("AddressMapper")
class AddressMapperTest {

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    @Nested
    @DisplayName("when there is no address")
    class NoAddress {

        @Test
        @DisplayName("an absent address maps to nothing, not to an empty address")
        void absent_address_maps_to_nothing() {
            assertThat(AddressMapper.map(null)).isNull();
        }

        @Test
        @DisplayName("an address explicitly null in the payload maps to nothing")
        void an_explicitly_null_address_maps_to_nothing() {
            final JsonNode person = mapper.readTree("{\"address\":null}");

            assertThat(AddressMapper.map(Json.at(person, "address"))).isNull();
        }

        @Test
        @DisplayName("an address object carrying nothing is still an address")
        void an_empty_address_object_is_still_an_address() {
            final CourtRegisterAddress address = AddressMapper.map(mapper.readTree("{}"));

            assertThat(address).isNotNull();
            assertThat(address.address1()).isNull();
            assertThat(address.postCode()).isNull();
        }
    }

    @Nested
    @DisplayName("when there is an address")
    class PopulatedAddress {

        @Test
        @DisplayName("the five address lines pass straight through")
        void address_lines_pass_straight_through() {
            final CourtRegisterAddress address = AddressMapper.map(parentAddress());

            assertThat(address).isNotNull();
            assertThat(address.address1()).isEqualTo("Father - Flat 1");
            assertThat(address.address2()).isEqualTo("Father - 1 Old Road");
            assertThat(address.address3()).isEqualTo("Father - London");
            assertThat(address.address4()).isEqualTo("Father - Merton");
        }

        @Test
        @DisplayName("an address line the payload does not carry is absent, not empty")
        void an_absent_address_line_is_absent() {
            assertThat(AddressMapper.map(parentAddress()).address5()).isNull();
        }

        @Test
        @DisplayName("an address line the payload carries empty stays empty")
        void an_empty_address_line_stays_empty() {
            assertThat(AddressMapper.map(courtCentreAddress()).address5()).isEmpty();
        }

        @Test
        @DisplayName("the post code changes case on the way out")
        void postcode_case_changes_on_the_way_out() {
            assertThat(AddressMapper.map(parentAddress()).postCode())
                    .isEqualTo("Father - SW99 1AA");
            assertThat(AddressMapper.map(courtCentreAddress()).postCode()).isEqualTo("SW11 1JU");
        }

        @Test
        @DisplayName("a field this mapper does not carry does not arrive by another name")
        void an_unmapped_field_is_not_carried() {
            final JsonNode payload = mapper.readTree(
                    "{\"address1\":\"1 High Street\",\"postCode\":\"SW11 1JU\","
                            + "\"country\":\"England\"}");

            final CourtRegisterAddress address = AddressMapper.map(payload);

            assertThat(address.address1()).isEqualTo("1 High Street");
            assertThat(address.postCode()).isNull();
        }
    }

    /**
     * The parent's address from the legacy parent-guardian fixture — address1 to address4 and a post
     * code, with no address5.
     *
     * @return the address node
     */
    private JsonNode parentAddress() {
        final JsonNode hearing =
                LegacyFixtures.readCourtRegister("mappers/parentguardian/hearing-resulted.json");
        return hearing.get("prosecutionCases").get(0).get("defendants").get(0)
                .get("associatedPersons").get(0).get("person").get("address");
    }

    /**
     * The court centre's address from the authored base hearing — the one court-register address
     * that carries an address5, and carries it empty.
     *
     * @return the address node
     */
    private JsonNode courtCentreAddress() {
        return LegacyFixtures.readBase("hearing-with-complete-court-centre.json")
                .get("hearing").get("courtCentre").get("address");
    }
}
