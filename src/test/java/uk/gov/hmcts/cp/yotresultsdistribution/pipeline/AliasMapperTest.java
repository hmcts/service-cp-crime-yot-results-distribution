package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterAlias;
import uk.gov.hmcts.cp.yotresultsdistribution.support.LegacyFixtures;

/**
 * The alias mapper — four name parts, and one half of an asymmetry.
 *
 * <p>Twins {@code $DF/…/Mappers/Alias/test/AliasMapper.test.js}. Its first case is named "Should
 * return empty array if null alias" and asserts {@code toEqual(undefined)}: the name is wrong and
 * the assertion is right, so the twin keeps the assertion and renames the case after what it
 * actually holds.
 *
 * <p><strong>The asymmetry is the behaviour.</strong> {@code AliasMapper.js:9} guards on truthiness
 * alone, so an <em>absent</em> alias list answers with nothing while an <em>empty</em> one answers
 * with an empty list — an empty array being truthy in JavaScript. Its counsel counterpart guards on
 * {@code counsels && counsels.length} and answers with nothing for both. No legacy case constructs
 * the empty array on either side, so the difference between the two mappers is invisible in the Jest
 * suite and would be lost to any port that reasoned about it rather than reading it. The empty case
 * is added here and its opposite number in {@link CounselMapperTest}; between them they are the pair
 * the comparator's absent-null-empty vectors exist to hold.
 *
 * <p>The legacy fixture carries a {@code legalEntityName} the mapper does not map, and the legacy
 * case does not look at it. Asserting the four fields it does map would pass on a mapper that
 * carried a fifth, so the twin asserts the mapped alias carries those four properties and no others.
 */
@DisplayName("AliasMapper")
class AliasMapperTest {

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    @Nested
    @DisplayName("when there are no aliases")
    class NoAliases {

        @Test
        @DisplayName("an absent alias list maps to nothing")
        void absent_aliases_map_to_nothing() {
            assertThat(AliasMapper.map(null)).isNull();
        }

        @Test
        @DisplayName("an alias list explicitly null in the payload maps to nothing")
        void an_explicitly_null_alias_list_maps_to_nothing() {
            final JsonNode defendant = mapper.readTree("{\"aliases\":null}");

            assertThat(AliasMapper.map(Json.at(defendant, "aliases"))).isNull();
        }

        @Test
        @DisplayName("an empty alias list maps to an empty list, not to nothing")
        void an_empty_alias_list_maps_to_an_empty_list() {
            assertThat(AliasMapper.map(mapper.readTree("[]"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("when there are aliases")
    class PopulatedAliases {

        @Test
        @DisplayName("Alias Mapper > Should return correct values — legacy Jest twin")
        void should_return_correct_values() {
            final List<CourtRegisterAlias> aliases = AliasMapper.map(legacyAliases());

            assertThat(aliases).hasSize(1);
            assertThat(aliases.get(0).title()).isEqualTo("Mr");
            assertThat(aliases.get(0).firstName()).isEqualTo("John");
            assertThat(aliases.get(0).middleName()).isEqualTo("Duncan");
            assertThat(aliases.get(0).lastName()).isEqualTo("Smith");
        }

        @Test
        @DisplayName("the legal entity name the payload carries is not carried on")
        void the_legal_entity_name_is_not_carried() {
            final CourtRegisterAlias alias = AliasMapper.map(legacyAliases()).get(0);

            assertThat(List.copyOf(mapper.valueToTree(alias).propertyNames()))
                    .containsExactlyInAnyOrder("title", "firstName", "middleName", "lastName");
        }

        @Test
        @DisplayName("a name part the payload does not carry is absent, not empty")
        void an_absent_name_part_is_absent() {
            final JsonNode aliases =
                    mapper.readTree("[{\"firstName\":\"John\",\"lastName\":\"Smith\"}]");

            final CourtRegisterAlias alias = AliasMapper.map(aliases).get(0);

            assertThat(alias.firstName()).isEqualTo("John");
            assertThat(alias.middleName()).isNull();
            assertThat(alias.title()).isNull();
        }

        @Test
        @DisplayName("every alias on the list is mapped, in the order the payload gives them")
        void every_alias_is_mapped_in_order() {
            final JsonNode aliases = mapper.readTree(
                    "[{\"firstName\":\"John\"},{\"firstName\":\"Jonathan\"}]");

            assertThat(AliasMapper.map(aliases))
                    .extracting(CourtRegisterAlias::firstName)
                    .containsExactly("John", "Jonathan");
        }
    }

    /**
     * The one alias on the legacy youth-defendant fixture — the same four name parts and the same
     * unmapped {@code legalEntityName} the legacy Jest case inlines.
     *
     * @return the alias array node
     */
    private JsonNode legacyAliases() {
        return LegacyFixtures.readCourtRegister("mappers/youthdefendant/hearing-resulted.json")
                .get("prosecutionCases").get(0).get("defendants").get(0).get("aliases");
    }
}
