package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterOffence;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterResult;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.support.LegacyFixtures;

/**
 * An offence as the register prints it — mostly copies, and three fields that are not.
 *
 * <p>Twins the one case in {@code $DF/…/Mappers/Offence/test/OffenceMapper.test.js}, which asserts
 * eight fields off a one-offence fixture and a defendant context whose result list is empty. Three
 * of those eight are worth more than the copy they look like.
 *
 * <ul>
 *   <li><strong>C23</strong> — {@code verdictCode} is written from
 *       {@code verdict.verdictType.description} ({@code OffenceMapper.js:20}): free-text prose in a
 *       field named for a code, which progression then renders into the PDF's verdict column. The
 *       fix writes {@code verdictType.verdictCode}, a real field of the platform verdict model that
 *       the legacy fixture itself carries alongside the description, and falls back to
 *       {@code categoryType} where a payload has no code — live payloads have been observed carrying
 *       only {@code category}, {@code categoryType} and {@code id}. Never the description. The
 *       legacy fixture answers {@code "desc1234"} today and {@code "1234"} under the fix, so the
 *       twin's own assertion is the one that fails against the legacy.</li>
 *   <li><strong>C24</strong> — {@code wording} is {@code wording + '####' + offenceLegislation}
 *       ({@code :17}), a client-side rendering convention baked into the data, and a literal
 *       {@code "…####undefined"} whenever there is no legislation. Progression substitutes
 *       {@code ####} for a newline at render time
 *       ({@code CourtRegisterPdfPayloadGenerator.java:336}) and passes a real newline through
 *       unchanged, so joining with the newline itself renders identically and loses the
 *       {@code undefined}.</li>
 *   <li><strong>{@code results}</strong> — the gathered results scoped to this offence by level and
 *       offence id ({@code :24-26}). The legacy fixture's context has an empty result list, so the
 *       scoping has never executed: the court register's one correctness advantage over its
 *       informant sibling is, today, entirely untested. It is pinned here against a context carrying
 *       offence-level results for two different offence ids, plus a case-level and a
 *       defendant-level result that must reach neither.</li>
 * </ul>
 *
 * <p>Two of the eight legacy assertions are also vacuous: the fixture carries
 * {@code "indicatedPlea": {}} and {@code "allocationDecision": {}}, so
 * {@code expect(result[0].indicatedPleaValue).toBe(fakeOffences[0].indicatedPlea.indicatedPleaValue)}
 * compares {@code undefined} with {@code undefined}, and {@code allocationDecision} is asserted
 * nowhere at all. {@code convictionDate} is present on the fixture and unasserted. The twin keeps the
 * legacy fixture and its empty objects — that is a real payload shape and its answer is absent — and
 * a second nest reads the repaired offences, which carry a real indicated plea and a real allocation
 * decision, so both fields are asserted against a value for the first time.
 */
@DisplayName("OffenceMapper")
class OffenceMapperTest {

    private static final String LEGISLATION =
            "Contrary to regulation 7(1)(a) of the Public Service Vehicles (Conduct of Drivers, "
                    + "Inspectors, Conductors and Passengers) Regulations 1990 and section 25 of "
                    + "the Public Passenger Vehicles Act 1981.";

    private static final String WORDING =
            "On 02/07/2018 at Bond street, being a passenger on a vehicle, namely Volvo B9TL 30x, "
                    + "used for the carriage of passengers at separate fares, used a ticket which "
                    + "had been altered or defaced.";

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    @Nested
    @DisplayName("Offence Mapper > Should return correct values — legacy Jest twin, repaired")
    class LegacyTwin {

        @Test
        @DisplayName("the offence's own fields are copied")
        void the_offences_own_fields_are_copied() {
            final CourtRegisterOffence offence = legacyOffence();

            assertThat(offence.offenceCode()).isEqualTo("PS90010");
            assertThat(offence.offenceTitle()).isEqualTo(
                    "Public service vehicle - passenger use altered / defaced   ticket");
            assertThat(offence.orderIndex()).isEqualTo(1);
        }

        @Test
        @DisplayName("the plea is copied by value and by date")
        void the_plea_is_copied_by_value_and_by_date() {
            final CourtRegisterOffence offence = legacyOffence();

            assertThat(offence.pleaValue()).isEqualTo("NOT_GUILTY");
            assertThat(offence.pleaDate()).isEqualTo("2019-11-14");
        }

        @Test
        @DisplayName("the conviction date the legacy case never looked at is carried")
        void the_conviction_date_is_carried() {
            assertThat(legacyOffence().convictionDate()).isEqualTo("2019-11-14");
        }

        @Test
        @DisplayName("an indicated plea and an allocation decision carrying nothing answer absent")
        void empty_plea_and_allocation_objects_answer_absent() {
            final CourtRegisterOffence offence = legacyOffence();

            assertThat(offence.indicatedPleaValue()).isNull();
            assertThat(offence.allocationDecision()).isNull();
        }

        @Test
        @DisplayName("a defendant context with no results scopes no results onto the offence")
        void a_context_with_no_results_scopes_none() {
            assertThat(legacyOffence().results()).isNull();
        }
    }

    @Nested
    @DisplayName("the plea and the decision the legacy fixture leaves empty")
    class RepairedFields {

        @Test
        @DisplayName("the indicated plea value is carried")
        void the_indicated_plea_value_is_carried() {
            assertThat(repairedOffences().get(0).indicatedPleaValue())
                    .isEqualTo("INDICATED_GUILTY");
        }

        @Test
        @DisplayName("the allocation decision is its reason description")
        void the_allocation_decision_is_its_reason_description() {
            assertThat(repairedOffences().get(0).allocationDecision())
                    .isEqualTo("Allocation decision - summary trial");
        }

        @Test
        @DisplayName("an offence carrying neither answers absent for both")
        void an_offence_carrying_neither_answers_absent() {
            final CourtRegisterOffence offence = repairedOffences().get(1);

            assertThat(offence.indicatedPleaValue()).isNull();
            assertThat(offence.allocationDecision()).isNull();
            assertThat(offence.convictionDate()).isNull();
        }
    }

    @Nested
    @DisplayName("C23 — what verdictCode carries")
    class VerdictCode {

        @Test
        @DisplayName("verdict code carries the verdict code")
        void verdict_code_carries_the_verdict_code() {
            assertThat(legacyOffence().verdictCode()).isEqualTo("1234");
        }

        @Test
        @DisplayName("verdict code falls back to category type when absent")
        void verdict_code_falls_back_to_category_type_when_absent() {
            assertThat(repairedOffences().get(1).verdictCode()).isEqualTo("GUILTY_CONVICTED");
        }

        @Test
        @DisplayName("the prose description is never what verdictCode carries")
        void the_description_is_never_carried() {
            final CourtRegisterOffence offence = mapOne(
                    "{\"verdict\":{\"verdictType\":{\"description\":\"Guilty by plea\"}}}");

            assertThat(offence.verdictCode()).isNull();
        }

        @Test
        @DisplayName("an offence with no verdict has no verdict code")
        void an_offence_with_no_verdict_has_no_verdict_code() {
            assertThat(mapOne("{\"offenceCode\":\"PS90010\"}").verdictCode()).isNull();
        }
    }

    @Nested
    @DisplayName("C24 — how wording and legislation are joined")
    class Wording {

        @Test
        @DisplayName("wording joins with a newline and omits absent legislation")
        void wording_joins_with_a_newline_and_omits_absent_legislation() {
            assertThat(repairedOffences().get(0).wording())
                    .isEqualTo(WORDING + "\n" + LEGISLATION);

            final CourtRegisterOffence noLegislation = repairedOffences().get(1);
            assertThat(noLegislation.wording())
                    .isEqualTo("On 03/07/2018 at Bond street stole one bicycle to the value of "
                            + "£120 belonging to another.")
                    .doesNotContain("####")
                    .doesNotContain("undefined");
        }

        @Test
        @DisplayName("legislation with no wording is not preceded by a newline")
        void legislation_with_no_wording_stands_alone() {
            assertThat(mapOne("{\"offenceLegislation\":\"Section 1\"}").wording())
                    .isEqualTo("Section 1");
        }

        @Test
        @DisplayName("an offence with neither has no wording at all")
        void an_offence_with_neither_has_no_wording() {
            assertThat(mapOne("{\"offenceCode\":\"PS90010\"}").wording()).isNull();
        }
    }

    @Nested
    @DisplayName("the offence-level result scoping the legacy suite never runs")
    class ResultScoping {

        @Test
        @DisplayName("each offence carries the results recorded against its own id")
        void each_offence_carries_its_own_results() {
            final List<CourtRegisterOffence> offences = scopedOffences();

            assertThat(offences).hasSize(2);
            assertThat(offences.get(0).results())
                    .extracting(CourtRegisterResult::cjsResultCode)
                    .containsExactly("cjsCode - O level - first offence");
            assertThat(offences.get(1).results())
                    .extracting(CourtRegisterResult::cjsResultCode)
                    .containsExactly("cjsCode - O level - second offence");
        }

        @Test
        @DisplayName("the result text comes off the judicial result, not off the result record")
        void the_result_text_comes_off_the_judicial_result() {
            assertThat(scopedOffences().get(1).results().get(0).resultText())
                    .isEqualTo("Compensation to be paid");
        }

        @Test
        @DisplayName("case-level and defendant-level results reach no offence")
        void results_at_other_levels_reach_no_offence() {
            assertThat(scopedOffences())
                    .flatExtracting(CourtRegisterOffence::results)
                    .extracting(CourtRegisterResult::cjsResultCode)
                    .doesNotContain("cjsCode - C level", "cjsCode - D level");
        }

        @Test
        @DisplayName("an offence no result names carries no results, not an empty list")
        void an_unresulted_offence_carries_no_results() {
            final CourtRegisterOffence offence = OffenceMapper.map(
                    List.of(mapper.readTree("{\"id\":\"no-such-offence\"}")),
                    defendantContext("defendant-context-base-with-offence-results.json")).get(0);

            assertThat(offence.results()).isNull();
        }

        @Test
        @DisplayName("offences are mapped in the order the caller gathered them")
        void offences_are_mapped_in_order() {
            assertThat(scopedOffences())
                    .extracting(CourtRegisterOffence::offenceCode)
                    .containsExactly("PS90010", "TH68001");
        }
    }

    /**
     * The one offence of the legacy fixture, mapped against the legacy defendant context — whose
     * result list is empty, exactly as the legacy suite has it.
     *
     * @return the mapped offence
     */
    private CourtRegisterOffence legacyOffence() {
        final List<JsonNode> offences =
                List.copyOf(LegacyFixtures.readCourtRegister("mappers/offence/offences.json")
                        .valueStream().toList());
        return OffenceMapper.map(offences, defendantContext("defendant-context-base.json")).get(0);
    }

    /**
     * The two repaired offences — one carrying a real indicated plea, a real allocation decision and
     * a verdict code, the other carrying no legislation and a verdict type with no code.
     *
     * @return the mapped offences
     */
    private List<CourtRegisterOffence> repairedOffences() {
        return OffenceMapper.map(
                repairedOffenceNodes(), defendantContext("defendant-context-base.json"));
    }

    /**
     * The two repaired offences mapped against the context that records offence-level results for
     * both of their ids, plus a case-level and a defendant-level result for neither.
     *
     * @return the mapped offences
     */
    private List<CourtRegisterOffence> scopedOffences() {
        return OffenceMapper.map(
                repairedOffenceNodes(),
                defendantContext("defendant-context-base-with-offence-results.json"));
    }

    /**
     * The repaired offence payloads.
     *
     * @return the offence nodes
     */
    private List<JsonNode> repairedOffenceNodes() {
        return List.copyOf(LegacyFixtures
                .readRebuilt("mappers/offence/offences-with-real-plea-and-allocation.json")
                .valueStream().toList());
    }

    /**
     * Maps a single offence written inline, against a context with no results.
     *
     * @param offence the offence payload as JSON text
     * @return the mapped offence
     */
    private CourtRegisterOffence mapOne(final String offence) {
        return OffenceMapper.map(
                List.of(mapper.readTree(offence)),
                defendantContext("defendant-context-base.json")).get(0);
    }

    /**
     * One of the rebuilt offence-mapper defendant contexts, as the gathered register defendant.
     *
     * @param fixture the file name under {@code rebuilt/mappers/offence/}
     * @return the register defendant
     */
    private RegisterDefendant defendantContext(final String fixture) {
        return mapper.treeToValue(
                LegacyFixtures.readRebuilt("mappers/offence/" + fixture), RegisterDefendant.class);
    }
}
