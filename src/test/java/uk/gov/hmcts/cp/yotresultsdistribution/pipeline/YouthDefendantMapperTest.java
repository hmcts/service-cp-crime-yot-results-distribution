package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterAlias;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterCaseOrApplication;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterCounsel;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterResult;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterResult;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ResultLevel;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationAnomaly;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;
import uk.gov.hmcts.cp.yotresultsdistribution.support.LegacyFixtures;

/**
 * The children the register is about, and the mapper that calls most of the others.
 *
 * <p>Twins the single case of
 * {@code $DF/…/Mappers/YouthDefendant/test/YouthDefendantMapper.test.js}, which asserts thirteen
 * fields off one defendant. Two of the thirteen are vacuous, one asserts a composition the fixture
 * cannot exercise, one reaches a default it cannot get past, and the mapper's whole treatment of
 * ethnicity, of unresolvable defendants and of defence counsel is asserted nowhere at all.
 *
 * <ul>
 *   <li><strong>Vacuous</strong> — the fixture's {@code personDetails} carries no
 *       {@code nationalityDescription} and no {@code address5}, so
 *       {@code expect(result[0].nationality).toBe(expected.nationalityDescription)} and the
 *       {@code address5} assertion beside it compare absent with absent.</li>
 *   <li><strong>Uncomposed</strong> — the case asserts {@code firstName + ' ' + lastName} while the
 *       mapper joins first, middle and last ({@code :37}); the fixture has no middle name, so the
 *       three-part path has never run.</li>
 *   <li><strong>Defaulted</strong> — {@code defendantCaseJudicialResults} is {@code []}, so
 *       {@code postHearingCustodyStatus} reaches its {@code 'Not Applicable'} default and the branch
 *       that reads a real status ({@code :60-65}) has never executed. No test in the legacy repo
 *       drives a real custody status onto a register.</li>
 * </ul>
 *
 * <p>So the twin is written twice: once against the legacy fixture, saying plainly what that fixture
 * does and does not carry, and once against the authored base hearing, whose child has three names,
 * a nationality, both ethnicity descriptions and two case results the second of which is a real
 * custody status.
 *
 * <p><strong>Defect C19.</strong> {@code :32} takes {@code defendants[0]} with no length check and
 * {@code :34} reads {@code personDefendant.personDetails} with no legal-entity fallback. An
 * unmatched master defendant id, or a defendant that is a company rather than a person, throws — and
 * the throw is swallowed at {@code OutboundCourtRegister/index.js:62-64}, so the hearing's whole
 * register is lost, for every other child on it, over one record. The fix omits that defendant,
 * counts them as {@code unresolvable-youth-defendant} and keeps the register. It is deliberately not
 * a transformation failure: that classification is for a document that cannot be built at all.
 *
 * <p><strong>Defect C25.</strong> {@code :70-74} emits an ethnicity only when the payload holds
 * <em>both</em> an observed and a self-defined description, and then returns the observed — so the
 * {@code ||} on {@code :72} can never reach its right-hand side and a child with only a self-defined
 * ethnicity has it dropped. The fix is observed-else-self-defined, which is what the expression was
 * evidently written to mean. It is also the one fix on the register that adds personal data to a
 * document rather than correcting it, which is why its row gates on information governance and not
 * only on the business.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> rows C19,
 *      C25, C29
 */
@DisplayName("YouthDefendantMapper")
class YouthDefendantMapperTest {

    private static final String YOUTH_ID = "6647df67-a065-4d07-90ba-a8daa064ecc4";

    private static final String SECOND_YOUTH = "b21c7e94-3f5a-4d18-9c60-7ea4d3f61b28";

    private static final String CASE_ID = "c10e3b71-6a6d-45ef-9b62-34df4d54971a";

    private static final String APPLICATION_ID = "6984d5b6-5c5d-472b-9ead-dff7a49c9600";

    private static final String NOT_APPLICABLE = "Not Applicable";

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    /** Every skipped defendant the mapper counted, in the order it counted them. */
    private final List<TransformationAnomaly> anomalies = new ArrayList<>();

    @Nested
    @DisplayName("Should return correct values when there are matching defendants in prosecution "
            + "case — YD1")
    class LegacyTwin {

        @Test
        @DisplayName("one defendant is mapped, under the master id the fragment gathered them by")
        void one_defendant_is_mapped() {
            assertThat(legacyYouth()).hasSize(1);
            assertThat(legacyYouth().get(0).masterDefendantId()).isEqualTo(YOUTH_ID);
        }

        @Test
        @DisplayName("the name is two parts, because this person has no middle name")
        void the_name_is_two_parts() {
            // The legacy asserts `firstName + ' ' + lastName` against a fixture with no middle name,
            // so the composition it means to pin is not the one it observes. Said plainly here.
            assertThat(legacyYouth().get(0).name()).isEqualTo("Fred Smith");
        }

        @Test
        @DisplayName("the date of birth and gender are carried")
        void the_date_of_birth_and_gender_are_carried() {
            assertThat(legacyYouth().get(0).dateOfBirth()).isEqualTo("1965-12-27");
            assertThat(legacyYouth().get(0).gender()).isEqualTo("MALE");
        }

        @Test
        @DisplayName("the address lines pass through and the post code changes case")
        void the_address_passes_through() {
            assertThat(legacyYouth().get(0).address().address1()).isEqualTo("Flat 1");
            assertThat(legacyYouth().get(0).address().address2()).isEqualTo("1 Old Road");
            assertThat(legacyYouth().get(0).address().address3()).isEqualTo("London");
            assertThat(legacyYouth().get(0).address().address4()).isEqualTo("Merton");
            assertThat(legacyYouth().get(0).address().postCode()).isEqualTo("SW99 1AA");
        }

        @Test
        @DisplayName("the fifth address line this fixture does not carry is absent")
        void the_fifth_address_line_is_absent() {
            // One of the two vacuous assertions: absent compared with absent.
            assertThat(legacyYouth().get(0).address().address5()).isNull();
        }

        @Test
        @DisplayName("the nationality this fixture does not carry is absent")
        void the_nationality_is_absent() {
            // The other. The fixture's person has no `nationalityDescription` at all.
            assertThat(legacyYouth().get(0).nationality()).isNull();
        }

        @Test
        @DisplayName("an empty ethnicity block yields no ethnicity")
        void an_empty_ethnicity_block_yields_none() {
            assertThat(legacyYouth().get(0).ethnicity()).isNull();
        }

        @Test
        @DisplayName("the custody status defaults, because this defendant has no case results")
        void the_custody_status_defaults() {
            // Reached by the default branch only: `defendantCaseJudicialResults` is `[]`.
            assertThat(legacyYouth().get(0).postHearingCustodyStatus()).isEqualTo(NOT_APPLICABLE);
        }

        @Test
        @DisplayName("the alias is carried, and the legal entity name beside it is not")
        void the_alias_is_carried_without_its_legal_entity_name() {
            assertThat(legacyYouth().get(0).aliases())
                    .extracting(CourtRegisterAlias::firstName, CourtRegisterAlias::middleName,
                            CourtRegisterAlias::lastName)
                    .containsExactly(org.assertj.core.api.Assertions.tuple(
                            "John", "Duncan", "Smith"));
        }

        @Test
        @DisplayName("the defence counsel named for this defendant is carried")
        void the_defence_counsel_is_carried() {
            assertThat(legacyYouth().get(0).defenceCounsels())
                    .extracting(CourtRegisterCounsel::name, CourtRegisterCounsel::status)
                    .containsExactly(org.assertj.core.api.Assertions.tuple(
                            "James Benjamin Simpson", "Junior QC"));
        }

        @Test
        @DisplayName("a defendant with no gathered results carries none")
        void a_defendant_with_no_results_carries_none() {
            assertThat(legacyYouth().get(0).defendantResults()).isNull();
        }
    }

    @Nested
    @DisplayName("the child the legacy fixture never had")
    class TheChildTheFixtureNeverHad {

        @Test
        @DisplayName("has all three parts of their name")
        void has_all_three_parts_of_their_name() {
            assertThat(survivingYouth().name()).isEqualTo("Fred Duncan Smith");
        }

        @Test
        @DisplayName("has a nationality")
        void has_a_nationality() {
            assertThat(survivingYouth().nationality()).isEqualTo("British");
        }

        @Test
        @DisplayName("has a date of birth that makes them a youth")
        void has_a_date_of_birth_that_makes_them_a_youth() {
            assertThat(survivingYouth().dateOfBirth()).isEqualTo("2008-04-17");
        }

        @Test
        @DisplayName("has a real custody status, which no legacy test has ever produced")
        void has_a_real_custody_status() {
            // The second of their two case results carries it; the first is `Not Applicable`, which
            // is what makes this the `filteredCustodyStatuses[0]` case rather than a `[0]` case.
            assertThat(survivingYouth().postHearingCustodyStatus())
                    .isEqualTo("REMANDED_IN_CUSTODY");
        }
    }

    @Nested
    @DisplayName("the space a child's name is trimmed of — JavaScript's set, not Java's")
    class TrimmedTheWayTheLegacyTrims {

        @Test
        @DisplayName("a non-breaking space either side of the name is not part of the name")
        void a_non_breaking_space_either_side_is_not_part_of_the_name() {
            // `YouthDefendantMapper.js:37-39` joins the three parts and calls
            // String.prototype.trim(), which strips the whole Unicode whitespace set. Java's
            // String.trim() strips codepoints up to U+0020 and String.strip() asks
            // Character.isWhitespace, which answers false for U+00A0 — so this padding survives
            // here and does not survive there. The recorded corpus catches it at
            // mut__surviving-youth-defendant__unicode-name__nbsp-padded, where the legacy wrote
            // "Fred Smith" and this port wrote a child's name with a space either side of it.
            assertThat(youthNamed("\u00a0Fred", null, "Smith\u00a0").name())
                    .isEqualTo("Fred Smith");
        }

        @Test
        @DisplayName("and neither is any other space JavaScript recognises")
        void nor_is_any_other_space_javascript_recognises() {
            // The rest of the set, so the fix is the rule and not a patch for one character: an
            // ideographic space, a line separator and a byte-order mark are all whitespace to
            // String.prototype.trim() and none of them is to Java.
            assertThat(youthNamed("\u3000Fred", null, "Smith\u2028").name())
                    .isEqualTo("Fred Smith");
            assertThat(youthNamed("\ufeffFred", null, "Smith\u205f").name())
                    .isEqualTo("Fred Smith");
        }

        @Test
        @DisplayName("a zero-width space is not space, and stays in the name it was sent in")
        void a_zero_width_space_is_not_space() {
            // U+200B is a format character, not whitespace, and String.prototype.trim() leaves it
            // alone. A fix that reached for Character.isWhitespace or for a regex of its own
            // choosing would take it out, and the child would be renamed on the way to court.
            assertThat(youthNamed("\u200bFred", null, "Smith\u200b").name())
                    .isEqualTo("\u200bFred Smith\u200b");
        }

        @Test
        @DisplayName("a unit separator is not space either, whatever Java's own trim thinks")
        void a_unit_separator_is_not_space_either() {
            // The over-trim in the other direction: String.trim() strips every codepoint up to
            // U+0020, which includes the four separator controls JavaScript does not strip. The
            // port owes the legacy's answer in both directions or it is not the legacy's rule.
            assertThat(youthNamed("\u001fFred", null, "Smith").name())
                    .isEqualTo("\u001fFred Smith");
        }

        @Test
        @DisplayName("space between the parts is the join's, and is left where it is")
        void space_between_the_parts_is_left_where_it_is() {
            assertThat(youthNamed("Fred", "\u00a0", "Smith").name())
                    .isEqualTo("Fred \u00a0 Smith");
        }

        /**
         * The surviving youth, renamed.
         *
         * @param firstName  their first name
         * @param middleName their middle name, or {@code null} for none
         * @param lastName   their last name
         * @return the mapped defendant
         */
        private CourtRegisterDefendant youthNamed(
                final String firstName, final String middleName, final String lastName) {

            final ObjectNode hearing = survivingYouthHearing();
            final ObjectNode details = personDetails(hearing);
            details.put("firstName", firstName);
            details.put("lastName", lastName);
            if (middleName == null) {
                details.remove("middleName");
            } else {
                details.put("middleName", middleName);
            }
            return map(hearing, youth(YOUTH_ID)).get(0);
        }
    }

    @Nested
    @DisplayName("which ethnicity is printed (C25)")
    class Ethnicity {

        @Test
        @DisplayName("the observed one, where the payload carries both")
        void the_observed_one_where_both_are_carried() {
            assertThat(survivingYouth().ethnicity()).isEqualTo("White - British");
        }

        @Test
        @DisplayName("the observed one, where that is all the payload carries")
        void the_observed_one_where_that_is_all() {
            // Fails against the legacy, which requires both descriptions and answers nothing here.
            assertThat(youthWithEthnicity("observedEthnicityDescription", "White - Irish")
                    .ethnicity()).isEqualTo("White - Irish");
        }

        @Test
        @DisplayName("the self-defined one, where that is all the payload carries")
        void the_self_defined_one_where_that_is_all() {
            // Fails against the legacy for the same reason — and this is the branch its `||` was
            // evidently written for and can never reach.
            assertThat(youthWithEthnicity("selfDefinedEthnicityDescription", "Asian - Indian")
                    .ethnicity()).isEqualTo("Asian - Indian");
        }

        @Test
        @DisplayName("the observed one where there is one, and the self-defined one otherwise")
        void ethnicity_prefers_observed_and_falls_back_to_self_defined() {
            // The fix in a sentence, and the two halves the legacy gets wrong in one place: it
            // needs both descriptions before it emits anything, and then always emits the observed.
            assertThat(survivingYouth().ethnicity()).isEqualTo("White - British");
            assertThat(youthWithEthnicity("selfDefinedEthnicityDescription", "Asian - Indian")
                    .ethnicity()).isEqualTo("Asian - Indian");
        }

        @Test
        @DisplayName("none, where the payload carries neither")
        void none_where_the_payload_carries_neither() {
            final ObjectNode hearing = survivingYouthHearing();
            personDetails(hearing).set("ethnicity", mapper.createObjectNode());

            assertThat(map(hearing, youth(YOUTH_ID)).get(0).ethnicity()).isNull();
        }

        @Test
        @DisplayName("none, where the payload carries no ethnicity block at all")
        void none_where_there_is_no_block() {
            final ObjectNode hearing = survivingYouthHearing();
            personDetails(hearing).remove("ethnicity");

            assertThat(map(hearing, youth(YOUTH_ID)).get(0).ethnicity()).isNull();
        }
    }

    @Nested
    @DisplayName("which custody status is printed")
    class PostHearingCustodyStatus {

        @Test
        @DisplayName("the default, where the defendant has no case results")
        void the_default_where_there_are_no_case_results() {
            assertThat(customStatuses().postHearingCustodyStatus()).isEqualTo(NOT_APPLICABLE);
        }

        @Test
        @DisplayName("the default, where the defendant carries no case-results field at all")
        void the_default_where_the_field_is_absent() {
            final ObjectNode hearing = survivingYouthHearing();
            caseDefendant(hearing).remove("defendantCaseJudicialResults");

            assertThat(map(hearing, youth(YOUTH_ID)).get(0).postHearingCustodyStatus())
                    .isEqualTo(NOT_APPLICABLE);
        }

        @Test
        @DisplayName("the default, where every case result says it does not apply")
        void the_default_where_every_result_says_so() {
            assertThat(customStatuses(NOT_APPLICABLE, NOT_APPLICABLE).postHearingCustodyStatus())
                    .isEqualTo(NOT_APPLICABLE);
        }

        @Test
        @DisplayName("the first that says anything else")
        void the_first_that_says_anything_else() {
            assertThat(customStatuses(NOT_APPLICABLE, "REMANDED_ON_BAIL", "REMANDED_IN_CUSTODY")
                    .postHearingCustodyStatus()).isEqualTo("REMANDED_ON_BAIL");
        }

        @Test
        @DisplayName("the first result's, where the first already says something")
        void the_first_results_where_it_says_something() {
            assertThat(customStatuses("REMANDED_IN_CUSTODY", "REMANDED_ON_BAIL")
                    .postHearingCustodyStatus()).isEqualTo("REMANDED_IN_CUSTODY");
        }
    }

    @Nested
    @DisplayName("which counsel defended this child")
    class DefenceCounsels {

        @Test
        @DisplayName("the one who named one of the defendant's own ids")
        void the_one_who_named_one_of_their_ids() {
            assertThat(survivingYouth().defenceCounsels())
                    .extracting(CourtRegisterCounsel::name)
                    .containsExactly("James Benjamin Simpson");
        }

        @Test
        @DisplayName("and not the one who named somebody else's")
        void and_not_the_one_who_named_somebody_elses() {
            final ObjectNode hearing = survivingYouthHearing();
            ((ObjectNode) hearing.get("defenceCounsels").get(0))
                    .set("defendants", mapper.createArrayNode().add(SECOND_YOUTH));

            assertThat(map(hearing, youth(YOUTH_ID)).get(0).defenceCounsels()).isNull();
        }

        @Test
        @DisplayName("none, where the hearing names no defence counsel at all")
        void none_where_the_hearing_names_nobody() {
            final ObjectNode hearing = survivingYouthHearing();
            hearing.remove("defenceCounsels");

            assertThat(map(hearing, youth(YOUTH_ID)).get(0).defenceCounsels()).isNull();
        }
    }

    @Nested
    @DisplayName("a youth defendant nothing on the hearing resolves (C19)")
    class UnresolvableYouthDefendant {

        @Test
        @DisplayName("is left off the register rather than ending it")
        void is_left_off_rather_than_ending_it() {
            // Fails against the legacy, where `defendants[0]` on an empty list is `undefined` and
            // reading `personDefendant` off it throws.
            assertThat(map(survivingYouthHearing(), youth("no-such-master-defendant"))).isEmpty();
        }

        @Test
        @DisplayName("is left off when their record is a company rather than a person")
        void is_left_off_when_the_record_is_a_company() {
            final ObjectNode hearing = survivingYouthHearing();
            final ObjectNode record = caseDefendant(hearing);
            record.remove("personDefendant");
            record.set("legalEntityDefendant",
                    mapper.createObjectNode().put("organisationName", "Acme Holdings Ltd"));

            assertThat(map(hearing, youth(YOUTH_ID))).isEmpty();
        }

        @Test
        @DisplayName("is counted under the bounded reason the anomaly summary carries")
        void an_unresolvable_youth_defendant_is_skipped_and_reported() {
            final List<CourtRegisterDefendant> mapped =
                    map(survivingYouthHearing(), youth("no-such-master-defendant"));

            assertThat(mapped).isEmpty();
            assertThat(anomalies)
                    .containsExactly(TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT);
            assertThat(TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT.value())
                    .isEqualTo("unresolvable-youth-defendant");
        }

        @Test
        @DisplayName("leaves the other children on the hearing their register")
        void leaves_the_other_children_their_register() {
            // The whole argument for the fix: one bad record costs that record, not every child on
            // the hearing.
            final List<CourtRegisterDefendant> mapped = map(
                    survivingYouthHearing(), youth("no-such-master-defendant"), youth(YOUTH_ID));

            assertThat(mapped).hasSize(1);
            assertThat(mapped.get(0).name()).isEqualTo("Fred Duncan Smith");
        }

        @Test
        @DisplayName("is warned about, without the child's name or date of birth")
        void is_warned_about_without_personal_data() {
            // Every defendant on this register is a child, and these lines reach a log index shared
            // across the estate. The bounded reason is what an operator needs.
            try (CapturedLog log = CapturedLog.capturing(YouthDefendantMapper.class)) {
                map(survivingYouthHearing(), youth("no-such-master-defendant"));

                assertThat(warnings(log)).singleElement().satisfies(message ->
                        assertThat(message)
                                .contains(TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT.value())
                                .doesNotContain("Fred")
                                .doesNotContain("2008-04-17"));
            }
        }

        @Test
        @DisplayName("a resolvable defendant is neither counted nor warned about")
        void a_resolvable_defendant_is_neither() {
            try (CapturedLog log = CapturedLog.capturing(YouthDefendantMapper.class)) {
                map(survivingYouthHearing(), youth(YOUTH_ID));

                assertThat(anomalies).isEmpty();
                assertThat(warnings(log)).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("a child the payload has no address for (C29)")
    class AddressLessYouth {

        @Test
        @DisplayName("is still mapped, with no address")
        void is_still_mapped_with_no_address() {
            // Absent, not an empty address: the contract requires `address` on a defendant, so this
            // document is one the pre-send validator refuses rather than one progression loses.
            final ObjectNode hearing = hearingOf("hearing-with-address-less-youth-and-parent.json");

            assertThat(map(hearing, youth(YOUTH_ID)).get(0).address()).isNull();
        }

        @Test
        @DisplayName("and a child whose parent has no address keeps the parent")
        void a_child_whose_parent_has_no_address_keeps_the_parent() {
            final ObjectNode hearing = hearingOf("hearing-with-address-less-youth-and-parent.json");
            final CourtRegisterDefendant mapped = map(hearing, youth(SECOND_YOUTH)).get(0);

            assertThat(mapped.address()).isNotNull();
            assertThat(mapped.parentGuardian()).isNotNull();
            assertThat(mapped.parentGuardian().address()).isNull();
        }
    }

    @Nested
    @DisplayName("the mappers this one calls")
    class WiredMappers {

        @Test
        @DisplayName("the parent or guardian is composed onto the defendant")
        void the_parent_is_composed_onto_the_defendant() {
            assertThat(survivingYouth().parentGuardian().name())
                    .isEqualTo("Father - Fred Father - Smith");
        }

        @Test
        @DisplayName("the hearing details are attached, read for this defendant")
        void the_hearing_details_are_attached() {
            assertThat(survivingYouth().hearing()).isNotNull();
            assertThat(survivingYouth().hearing().jurisdiction()).isEqualTo("MAGISTRATES");
        }

        @Test
        @DisplayName("the cases and applications are attached")
        void the_cases_and_applications_are_attached() {
            assertThat(survivingYouth().prosecutionCasesOrApplications())
                    .extracting(CourtRegisterCaseOrApplication::caseOrApplicationReference)
                    .contains("TFL4359536");
        }

        @Test
        @DisplayName("only the defendant-level results reach the defendant")
        void only_defendant_level_results_reach_the_defendant() {
            final RegisterDefendant gathered = new RegisterDefendant(
                    List.of(YOUTH_ID),
                    List.of(
                            result(ResultLevel.DEFENDANT, "cjsCode - D level"),
                            result(ResultLevel.CASE, "cjsCode - C level"),
                            result(ResultLevel.OFFENCE, "cjsCode - O level")),
                    List.of(CASE_ID),
                    List.of(APPLICATION_ID),
                    YOUTH_ID,
                    true,
                    "2020-01-20",
                    null);

            assertThat(map(survivingYouthHearing(), gathered).get(0).defendantResults())
                    .extracting(CourtRegisterResult::cjsResultCode)
                    .containsExactly("cjsCode - D level");
        }
    }

    @Nested
    @DisplayName("nothing to map")
    class NothingToMap {

        @Test
        @DisplayName("no youth defendants at all answers an empty register")
        void no_youth_defendants_answers_an_empty_register() {
            // Naming this outcome is the caller's job — `no-youth-defendants` — and it needs an
            // answer it can look at rather than one it has to guard against.
            assertThat(new YouthDefendantMapper(anomalies::add)
                    .map(List.of(), survivingYouthHearing())).isEmpty();
        }

        @Test
        @DisplayName("defendants come out in the order the fragment gathered them")
        void defendants_keep_their_order() {
            final ObjectNode hearing = hearingOf("hearing-with-address-less-youth-and-parent.json");

            assertThat(map(hearing, youth(SECOND_YOUTH), youth(YOUTH_ID)))
                    .extracting(CourtRegisterDefendant::masterDefendantId)
                    .containsExactly(SECOND_YOUTH, YOUTH_ID);
        }
    }

    /**
     * The legacy fixture's youth defendants, mapped as the Jest case maps them.
     *
     * @return the mapped defendants
     */
    private List<CourtRegisterDefendant> legacyYouth() {
        final List<RegisterDefendant> gathered = List.copyOf(
                LegacyFixtures.readRebuilt("mappers/youthdefendant/youth-defendants.json")
                        .valueStream()
                        .map(node -> mapper.treeToValue(node, RegisterDefendant.class))
                        .toList());

        return new YouthDefendantMapper(anomalies::add).map(gathered,
                LegacyFixtures.readCourtRegister("mappers/youthdefendant/hearing-resulted.json"));
    }

    /**
     * The authored base hearing's youth, mapped.
     *
     * @return the mapped defendant
     */
    private CourtRegisterDefendant survivingYouth() {
        return map(survivingYouthHearing(), youth(YOUTH_ID)).get(0);
    }

    /**
     * The base hearing's youth, given exactly the ethnicity description named and no other.
     *
     * @param description the ethnicity field to keep
     * @param value       its value
     * @return the mapped defendant
     */
    private CourtRegisterDefendant youthWithEthnicity(
            final String description, final String value) {

        final ObjectNode hearing = survivingYouthHearing();
        final ObjectNode ethnicity = mapper.createObjectNode();
        ethnicity.put(description, value);
        personDetails(hearing).set("ethnicity", ethnicity);

        return map(hearing, youth(YOUTH_ID)).get(0);
    }

    /**
     * The base hearing's youth, given exactly the post-hearing custody statuses named.
     *
     * @param statuses the statuses, one case judicial result each
     * @return the mapped defendant
     */
    private CourtRegisterDefendant customStatuses(final String... statuses) {
        final ObjectNode hearing = survivingYouthHearing();
        final ArrayNode results = mapper.createArrayNode();
        for (final String status : statuses) {
            results.add(mapper.createObjectNode().put("postHearingCustodyStatus", status));
        }
        caseDefendant(hearing).set("defendantCaseJudicialResults", results);

        return map(hearing, youth(YOUTH_ID)).get(0);
    }

    /**
     * Runs the mapper, collecting whatever it skips.
     *
     * @param hearing          the hearing payload
     * @param youthDefendants  the gathered defendants, in fragment order
     * @return the mapped defendants
     */
    private List<CourtRegisterDefendant> map(
            final JsonNode hearing, final RegisterDefendant... youthDefendants) {
        return new YouthDefendantMapper(anomalies::add).map(List.of(youthDefendants), hearing);
    }

    /**
     * A gathered youth defendant, carrying the base hearing's case and application and no results.
     *
     * @param masterDefendantId the identity to gather by
     * @return the gathered defendant
     */
    private RegisterDefendant youth(final String masterDefendantId) {
        return new RegisterDefendant(
                List.of(masterDefendantId),
                List.of(),
                List.of(CASE_ID),
                List.of(APPLICATION_ID),
                masterDefendantId,
                true,
                "2020-01-20",
                null);
    }

    /**
     * One gathered result at the given level.
     *
     * @param level   the level it was recorded at
     * @param cjsCode the code the register prints
     * @return the gathered result
     */
    private RegisterResult result(final ResultLevel level, final String cjsCode) {
        return new RegisterResult(
                CASE_ID, YOUTH_ID, null, APPLICATION_ID, level, YOUTH_ID,
                mapper.createObjectNode().put("cjsCode", cjsCode).put("resultText", "text"),
                null, null);
    }

    /** The authored hearing whose child survives every filter, as an editable copy. */
    private ObjectNode survivingYouthHearing() {
        return hearingOf("hearing-with-surviving-youth-defendant.json");
    }

    /**
     * One base payload's hearing, deep-copied so a case can change it without changing the fixture
     * for the next one.
     *
     * @param fixture the file name below {@code fixtures/base/}
     * @return the hearing
     */
    private ObjectNode hearingOf(final String fixture) {
        return (ObjectNode) LegacyFixtures.readBase(fixture).get("hearing").deepCopy();
    }

    /**
     * The first prosecution case's first defendant record on a hearing.
     *
     * @param hearing the hearing
     * @return the record
     */
    private ObjectNode caseDefendant(final ObjectNode hearing) {
        return (ObjectNode) hearing.get("prosecutionCases").get(0).get("defendants").get(0);
    }

    /**
     * That record's person details.
     *
     * @param hearing the hearing
     * @return the person details
     */
    private ObjectNode personDetails(final ObjectNode hearing) {
        return (ObjectNode) caseDefendant(hearing).get("personDefendant").get("personDetails");
    }

    /**
     * Every WARN line the capture holds.
     *
     * @param log the capture
     * @return the messages
     */
    private static List<String> warnings(final CapturedLog log) {
        return log.events().stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
