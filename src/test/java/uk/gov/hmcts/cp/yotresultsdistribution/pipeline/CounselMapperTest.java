package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterCounsel;
import uk.gov.hmcts.cp.yotresultsdistribution.support.LegacyFixtures;

/**
 * The counsel mapper — a status copied, a name composed, and the other half of the asymmetry.
 *
 * <p>Twins {@code $DF/…/Mappers/Counsel/test/CounselMapper.test.js}. Its first case is named "Should
 * return empty array if null counsels" and asserts {@code toEqual(undefined)}, the same wrong name
 * over the right assertion the alias suite carries; its second asserts one field, {@code status},
 * off a fixture whose three name parts the mapper composes into the one field that is not a copy.
 *
 * <p><strong>Absent and empty answer the same here, and that is the point.</strong>
 * {@code CounselMapper.js:7} guards on {@code counsels && counsels.length}, so an empty list answers
 * with nothing — where {@link AliasMapper}, guarding on truthiness alone, answers an empty array
 * with an empty list. Neither legacy case constructs the empty input, so the difference between the
 * two mappers appears in no test in the tree. Both halves are pinned, here and in
 * {@link AliasMapperTest}, because a port that unified them would look tidier and would send a
 * different document.
 *
 * <p>The composition is {@code [first, middle, last].filter(item => item).join(' ').trim()}
 * ({@code :11}), and every clause of it earns a case. The legacy fixture has all three parts, so the
 * suite could not have told a mapper that dropped the middle name from one that kept it, nor a
 * mapper that joined with two spaces where a part is missing. The filter is on truthiness, not on
 * null, so a name part carried as an empty string is dropped exactly as an absent one is — and a
 * counsel with no name at all composes to the empty string rather than to nothing, which is what
 * {@code trim()} on an empty join leaves behind.
 */
@DisplayName("CounselMapper")
class CounselMapperTest {

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    @Nested
    @DisplayName("when there are no counsels")
    class NoCounsels {

        @Test
        @DisplayName("an absent counsel list maps to nothing")
        void absent_counsels_map_to_nothing() {
            assertThat(CounselMapper.map(null)).isNull();
        }

        @Test
        @DisplayName("an empty counsel list maps to nothing too — unlike an empty alias list")
        void an_empty_counsel_list_maps_to_nothing() {
            assertThat(CounselMapper.map(List.of())).isNull();
        }
    }

    @Nested
    @DisplayName("when there are counsels")
    class PopulatedCounsels {

        @Test
        @DisplayName("Counsel Mapper > Should return correct values — legacy Jest twin")
        void should_return_correct_values() {
            final List<CourtRegisterCounsel> counsels = CounselMapper.map(legacyCounsels());

            assertThat(counsels).hasSize(1);
            assertThat(counsels.get(0).status()).isEqualTo("Junior QC");
        }

        @Test
        @DisplayName("the name is composed from first, middle and last")
        void the_name_is_composed_from_three_parts() {
            assertThat(CounselMapper.map(legacyCounsels()).get(0).name())
                    .isEqualTo("James Benjamin Simpson");
        }

        @Test
        @DisplayName("a counsel with no middle name gets one space, not two")
        void a_counsel_with_no_middle_name_gets_one_space() {
            assertThat(nameOf("{\"firstName\":\"James\",\"lastName\":\"Simpson\"}"))
                    .isEqualTo("James Simpson");
        }

        @Test
        @DisplayName("a name part carried empty is dropped as an absent one is")
        void an_empty_name_part_is_dropped() {
            assertThat(nameOf(
                    "{\"firstName\":\"James\",\"middleName\":\"\",\"lastName\":\"Simpson\"}"))
                    .isEqualTo("James Simpson");
        }

        @Test
        @DisplayName("a counsel with no name at all composes to nothing written down")
        void a_counsel_with_no_name_composes_to_an_empty_string() {
            assertThat(nameOf("{\"status\":\"Junior QC\"}")).isEmpty();
        }

        @Test
        @DisplayName("the title the payload carries is not part of the name")
        void the_title_is_not_part_of_the_name() {
            assertThat(nameOf("{\"title\":\"QC\",\"firstName\":\"James\"}")).isEqualTo("James");
        }

        @Test
        @DisplayName("every counsel gathered is mapped, in the order they were gathered")
        void every_counsel_is_mapped_in_order() {
            final List<JsonNode> counsels = List.of(
                    mapper.readTree("{\"firstName\":\"James\",\"lastName\":\"Simpson\"}"),
                    mapper.readTree("{\"firstName\":\"David\",\"lastName\":\"Walsh\"}"));

            assertThat(CounselMapper.map(counsels))
                    .extracting(CourtRegisterCounsel::name)
                    .containsExactly("James Simpson", "David Walsh");
        }
    }

    /**
     * Maps a single counsel and returns the name it composed.
     *
     * @param counsel the counsel record as JSON text
     * @return the composed name
     */
    private String nameOf(final String counsel) {
        return CounselMapper.map(List.of(mapper.readTree(counsel))).get(0).name();
    }

    /**
     * The one defence counsel on the legacy youth-defendant fixture — the same three name parts and
     * the same status the legacy Jest case inlines.
     *
     * @return the counsel records
     */
    private List<JsonNode> legacyCounsels() {
        return List.copyOf(
                LegacyFixtures.readCourtRegister("mappers/youthdefendant/hearing-resulted.json")
                        .get("defenceCounsels").valueStream().toList());
    }
}
