package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterParentGuardian;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.support.LegacyFixtures;

/**
 * The adult the court writes to about a child.
 *
 * <p>Twins the one case in {@code $DF/…/Mappers/ParentGuardian/test/ParentGuardianMapper.test.js},
 * which loads a hearing whose single defendant carries one associated person in the role
 * {@code parent} and asserts the composed name, four address lines and the post code. It is the one
 * place in the corpus where the {@code postcode} → {@code postCode} case change is genuinely pinned,
 * so that assertion is kept exactly as the legacy makes it.
 *
 * <p>Three of the mapper's four decisions are untouched by that case, and each of them decides
 * whether a parent appears on a child's register at all:
 *
 * <ul>
 *   <li><strong>The guardian fallback</strong> ({@code ParentGuardianMapper.js:24-31}) — a parent is
 *       looked for first and a guardian second. The fixture has a parent, so the second half has
 *       never run.</li>
 *   <li><strong>The {@code person} test</strong> ({@code :28,30}) — an associated person in the right
 *       role but carrying no {@code person} block is not chosen, and the search continues.</li>
 *   <li><strong>{@code role.toLowerCase()}</strong> ({@code :28}) — the role is read as a string with
 *       no check that it is one. A payload carrying a coded role — a number, an object — throws a
 *       {@code TypeError} that the whole hearing's register is then lost to. No C-number covers it,
 *       so the register is not kept: a role the port cannot read is a classified, non-transient
 *       refusal, which loses exactly the register the legacy loses and loses it where support can
 *       see it. Passing the entry over would send a register the legacy never sends.</li>
 * </ul>
 *
 * <p>Two vacuities in the legacy case are named rather than inherited. Its
 * {@code expect(result.address.address5).toBe(undefined)} compares absent with absent, because the
 * fixture's parent has four address lines; the twin keeps that assertion honest by saying the
 * <em>fixture</em> carries no fifth line, and asserts the pass-through against a person who does. And
 * a parent with no address at all — the shape that makes the outbound document schema-invalid,
 * because {@code courtRegisterParentGuardian.address} is required — is asserted here as what this
 * mapper answers, which is a parent guardian carrying a name and nothing else. Refusing that
 * document is the validator's job (C29), not this mapper's; what matters here is that the absence
 * reaches it as an absence rather than as an empty address.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> row C29
 */
@DisplayName("ParentGuardianMapper")
class ParentGuardianMapperTest {

    /** The one master defendant every fixture and every constructed hearing below is about. */
    private static final String MASTER = "6647df67-a065-4d07-90ba-a8daa064ecc4";

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    @Nested
    @DisplayName("Parent guardian mapper > Should return correct values — legacy Jest twin")
    class LegacyTwin {

        @Test
        @DisplayName("the name is composed from the parts the person carries")
        void the_name_is_composed_from_the_parts_the_person_carries() {
            assertThat(legacyParent().name()).isEqualTo("Father - Fred Father - Smith");
        }

        @Test
        @DisplayName("the address lines pass straight through")
        void the_address_lines_pass_straight_through() {
            assertThat(legacyParent().address().address1()).isEqualTo("Father - Flat 1");
            assertThat(legacyParent().address().address2()).isEqualTo("Father - 1 Old Road");
            assertThat(legacyParent().address().address3()).isEqualTo("Father - London");
            assertThat(legacyParent().address().address4()).isEqualTo("Father - Merton");
        }

        @Test
        @DisplayName("the post code changes case on the way out")
        void the_post_code_changes_case_on_the_way_out() {
            // The payload spells it `postcode` and the wire spells it `postCode`. This assertion is
            // the only place in the whole legacy corpus where that change is actually observed.
            assertThat(legacyParent().address().postCode()).isEqualTo("Father - SW99 1AA");
        }

        @Test
        @DisplayName("the fifth address line this person does not carry is absent")
        void the_fifth_address_line_is_absent() {
            // The legacy asserts `toBe(undefined)` here against a fixture whose parent has four
            // lines, so it compares absent with absent and would pass whatever the mapper did. Said
            // plainly instead: this person has no fifth line, so neither does the address.
            assertThat(legacyParent().address().address5()).isNull();
        }
    }

    @Nested
    @DisplayName("the fifth address line the legacy fixture leaves empty")
    class FifthAddressLine {

        @Test
        @DisplayName("a person carrying a fifth address line has it carried")
        void a_fifth_address_line_is_carried() {
            final CourtRegisterParentGuardian parent =
                    map(hearingWithAssociatedPersons(mapper.createArrayNode()
                            .add(associatedPerson("parent", personWithFifthLine()))));

            assertThat(parent.address().address5()).isEqualTo("Father - Attic");
            assertThat(parent.address().address4()).isEqualTo("Father - Merton");
        }
    }

    @Nested
    @DisplayName("which associated person is chosen")
    class WhoIsChosen {

        @Test
        @DisplayName("a guardian stands in where there is no parent")
        void a_guardian_stands_in_where_there_is_no_parent() {
            // `ParentGuardianMapper.js:29-31`. Never once exercised: the only fixture has a parent.
            final CourtRegisterParentGuardian parent = mapPersons(
                    associatedPerson("sibling", person("Sister")),
                    associatedPerson("guardian", person("Guardian")));

            assertThat(parent.name()).isEqualTo("Guardian Person");
        }

        @Test
        @DisplayName("a parent is preferred to a guardian on the same defendant")
        void a_parent_is_preferred_to_a_guardian() {
            final CourtRegisterParentGuardian parent = mapPersons(
                    associatedPerson("guardian", person("Guardian")),
                    associatedPerson("parent", person("Parent")));

            assertThat(parent.name()).isEqualTo("Parent Person");
        }

        @Test
        @DisplayName("the role is read without regard to its case")
        void the_role_is_read_without_regard_to_its_case() {
            assertThat(mapPersons(associatedPerson("PARENT", person("Shouted"))).name())
                    .isEqualTo("Shouted Person");
        }

        @Test
        @DisplayName("a right-role entry carrying no person is passed over")
        void a_right_role_entry_carrying_no_person_is_passed_over() {
            // `:28` tests the role *and* `associatedPerson.person`; an entry with the role and no
            // person is not the parent, and the guardian search still gets its turn.
            final ObjectNode roleOnly = mapper.createObjectNode();
            roleOnly.put("role", "parent");

            assertThat(mapPersons(roleOnly, associatedPerson("guardian", person("Guardian"))).name())
                    .isEqualTo("Guardian Person");
        }

        @Test
        @DisplayName("a defendant with no associated persons has no parent guardian")
        void a_defendant_with_no_associated_persons_has_no_parent_guardian() {
            assertThat(map(hearingWithAssociatedPersons(null))).isNull();
        }

        @Test
        @DisplayName("an empty associated-persons list has no parent guardian")
        void an_empty_associated_persons_list_has_no_parent_guardian() {
            assertThat(map(hearingWithAssociatedPersons(mapper.createArrayNode()))).isNull();
        }

        @Test
        @DisplayName("associated persons who are neither parent nor guardian have no parent guardian")
        void neither_parent_nor_guardian_has_no_parent_guardian() {
            assertThat(mapPersons(
                    associatedPerson("sibling", person("Sister")),
                    associatedPerson("social worker", person("Worker"))))
                    .isNull();
        }
    }

    @Nested
    @DisplayName("a role that is not a string")
    class RoleThatIsNotAString {

        @Test
        @DisplayName("is a classified refusal, not a skip: the legacy loses the register here")
        void a_coded_role_is_refused() {
            // `role.toLowerCase()` on a number is a TypeError, the exception is swallowed at
            // `OutboundCourtRegister/index.js:62-64`, and every child on the hearing loses their
            // register. Passing over the entry would produce a register the legacy never sends, for
            // a defendant it never sends one for — an uncatalogued content change. The port refuses
            // the payload instead, out loud and with somewhere to replay it from.
            final ObjectNode coded = mapper.createObjectNode();
            coded.put("role", 42);
            coded.set("person", person("Coded"));

            assertThatThrownBy(
                    () -> mapPersons(coded, associatedPerson("parent", person("Parent"))))
                    .isInstanceOf(TransformationFailedException.class)
                    .satisfies(refused -> {
                        assertThat(((TransformationFailedException) refused).classification())
                                .isEqualTo(FailureClassification.NON_TRANSIENT);
                        assertThat(((TransformationFailedException) refused).reason())
                                .isEqualTo(ReasonCode.TRANSFORMATION_FAILED);
                    });
        }

        @Test
        @DisplayName("says which field was the wrong shape and nothing about the person")
        void names_the_field_and_not_the_person() {
            final ObjectNode coded = mapper.createObjectNode();
            coded.set("role", mapper.createObjectNode().put("code", "PARENT"));
            coded.set("person", person("Coded"));

            assertThatThrownBy(() -> mapPersons(coded))
                    .isInstanceOf(TransformationFailedException.class)
                    .hasMessageContaining("role")
                    .hasMessageNotContaining("PARENT")
                    .hasMessageNotContaining("Coded");
        }

        @Test
        @DisplayName("is never reached where a parent was already found ahead of it")
        void is_never_reached_where_a_parent_came_first() {
            // `find` stops at the first match, so a coded role behind one is never read — and a
            // register the legacy sends is still sent.
            final ObjectNode coded = mapper.createObjectNode();
            coded.put("role", 42);
            coded.set("person", person("Coded"));

            assertThat(mapPersons(associatedPerson("parent", person("Parent")), coded).name())
                    .isEqualTo("Parent Person");
        }
    }

    @Nested
    @DisplayName("a parent the payload has no address for (C29)")
    class AddressLessParent {

        @Test
        @DisplayName("is still a parent guardian, with a name and no address")
        void is_still_a_parent_guardian_with_no_address() {
            // The shape that makes the outbound document schema-invalid, because the contract
            // requires `courtRegisterParentGuardian.address`. Refusing the document is the pre-send
            // validator's job; this mapper's job is to let the absence arrive as an absence.
            final ObjectNode nameOnly = mapper.createObjectNode();
            nameOnly.put("firstName", "Homeless");
            nameOnly.put("lastName", "Parent");

            final CourtRegisterParentGuardian parent =
                    mapPersons(associatedPerson("parent", nameOnly));

            assertThat(parent.name()).isEqualTo("Homeless Parent");
            assertThat(parent.address()).isNull();
        }
    }

    /**
     * The legacy fixture's parent, mapped exactly as the Jest case maps it.
     *
     * @return the mapped parent guardian
     */
    private CourtRegisterParentGuardian legacyParent() {
        return map(LegacyFixtures.readCourtRegister("mappers/parentguardian/hearing-resulted.json"));
    }

    /**
     * Maps the parent or guardian of the one register defendant every case here is about.
     *
     * @param hearing the hearing payload
     * @return the mapped parent guardian, or {@code null} where the defendant has neither
     */
    private CourtRegisterParentGuardian map(final JsonNode hearing) {
        return ParentGuardianMapper.map(registerDefendant(), hearing);
    }

    /**
     * Maps against a hearing built around the given associated persons.
     *
     * @param associatedPersons the entries, in the order the payload carries them
     * @return the mapped parent guardian, or {@code null}
     */
    private CourtRegisterParentGuardian mapPersons(final JsonNode... associatedPersons) {
        final ArrayNode entries = mapper.createArrayNode();
        for (final JsonNode entry : associatedPersons) {
            entries.add(entry);
        }
        return map(hearingWithAssociatedPersons(entries));
    }

    /**
     * The rebuilt parent-guardian defendant context, carrying the eighteen-key vocabulary.
     *
     * @return the gathered defendant
     */
    private RegisterDefendant registerDefendant() {
        return mapper.treeToValue(
                LegacyFixtures.readRebuilt("mappers/parentguardian/defendant-context-base.json"),
                RegisterDefendant.class);
    }

    /**
     * The smallest hearing this mapper reads: one prosecution case, one defendant record for the
     * master defendant under test, and whatever associated persons the case supplies.
     *
     * @param associatedPersons the entries, or {@code null} to leave the field absent entirely
     * @return the hearing payload
     */
    private JsonNode hearingWithAssociatedPersons(final ArrayNode associatedPersons) {
        final ObjectNode defendant = mapper.createObjectNode();
        defendant.put("id", MASTER);
        defendant.put("masterDefendantId", MASTER);
        if (associatedPersons != null) {
            defendant.set("associatedPersons", associatedPersons);
        }

        final ObjectNode prosecutionCase = mapper.createObjectNode();
        prosecutionCase.put("id", "c10e3b71-6a6d-45ef-9b62-34df4d54971a");
        prosecutionCase.set("defendants", mapper.createArrayNode().add(defendant));

        final ObjectNode hearing = mapper.createObjectNode();
        hearing.put("id", "1828f356-f746-4f2d-932b-79ef2df95c80");
        hearing.set("prosecutionCases", mapper.createArrayNode().add(prosecutionCase));
        return hearing;
    }

    /**
     * One {@code associatedPersons} entry.
     *
     * @param role   the role, as the payload spells it
     * @param person the person block
     * @return the entry
     */
    private ObjectNode associatedPerson(final String role, final JsonNode person) {
        final ObjectNode entry = mapper.createObjectNode();
        entry.put("role", role);
        entry.set("person", person);
        return entry;
    }

    /**
     * A person with a first name, a last name and an address.
     *
     * @param firstName the first name
     * @return the person block
     */
    private ObjectNode person(final String firstName) {
        final ObjectNode person = mapper.createObjectNode();
        person.put("firstName", firstName);
        person.put("lastName", "Person");
        final ObjectNode address = mapper.createObjectNode();
        address.put("address1", "1 Old Road");
        address.put("postcode", "SW99 1AA");
        person.set("address", address);
        return person;
    }

    /**
     * The legacy fixture's parent, given the fifth address line the fixture does not carry — so the
     * assertion the legacy makes vacuously has something to be about.
     *
     * @return the person block
     */
    private ObjectNode personWithFifthLine() {
        final ObjectNode person = mapper.createObjectNode();
        person.put("firstName", "Father - Fred");
        person.put("lastName", "Father - Smith");
        final ObjectNode address = mapper.createObjectNode();
        address.put("address1", "Father - Flat 1");
        address.put("address2", "Father - 1 Old Road");
        address.put("address3", "Father - London");
        address.put("address4", "Father - Merton");
        address.put("address5", "Father - Attic");
        address.put("postcode", "Father - SW99 1AA");
        person.set("address", address);
        return person;
    }
}
