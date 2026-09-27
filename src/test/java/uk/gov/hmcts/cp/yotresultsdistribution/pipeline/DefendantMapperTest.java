package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.support.LegacyFixtures;

/**
 * Finding the payload defendant records one register defendant was gathered from.
 *
 * <p>Twins the four cases of {@code $DF/…/Mappers/Defendant/test/DefendantMapper.test.js}, each
 * against the byte-identical fixture the legacy case names, and each asserting the count that case
 * asserts: a match among the prosecution cases, a match among the court applications, a match where
 * there are no prosecution cases at all, and no match anywhere.
 *
 * <p><strong>What those four do not say is the precedence</strong>, and the precedence is the
 * behaviour. {@code DefendantMapper.js:11-20} searches every prosecution case's defendants first and
 * consults the court applications <em>only</em> when that search comes back empty — an else, not a
 * concatenation. All four legacy fixtures are arranged so that at most one of the two places
 * matches, so a mapper that searched applications first, or that returned both, passes every one of
 * them. The construction that tells them apart is already in the tree and is reached by asking a
 * different question of it: {@code hearing-resulted-with-no-matching-defendants.json} is named for
 * master defendant {@code …ecc5}, which matches nothing, but it carries {@code …ecc4} in a
 * prosecution case <em>and</em> as a court application's subject. Asked for {@code …ecc4} it is the
 * one fixture in the tree where both halves match, and the answer says which half wins.
 *
 * <p>The two answers are told apart by shape rather than by count: a case defendant carries an
 * {@code id} and a {@code prosecutionCaseId}, an application's {@code subject.masterDefendant}
 * carries neither. A count alone would not distinguish the right record from the wrong one, which is
 * exactly what the four legacy cases assert.
 *
 * <p>The empty answer is where defect C19 begins — the youth mapper takes {@code defendants[0]} off
 * this list with no length check — so the no-match case is pinned here as returning an empty list
 * rather than nothing, and the guard against it is asserted in {@code YouthDefendantMapperTest}.
 */
@DisplayName("DefendantMapper")
class DefendantMapperTest {

    private static final String MASTER_DEFENDANT = "6647df67-a065-4d07-90ba-a8daa064ecc4";

    private static final String UNKNOWN_DEFENDANT = "6647df67-a065-4d07-90ba-a8daa064ecc5";

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    @Nested
    @DisplayName("DefendantMapper Mapper — legacy Jest twins")
    class LegacyTwins {

        @Test
        @DisplayName("when there are matching defendants in prosecutionCase but not in "
                + "courtApplication")
        void matching_in_prosecution_case_only() {
            assertThat(defendantsOf("hearing-resulted.json", MASTER_DEFENDANT)).hasSize(1);
        }

        @Test
        @DisplayName("when there are matching defendants in court application but not in "
                + "prosecution case")
        void matching_in_court_application_only() {
            assertThat(defendantsOf(
                    "hearing-resulted-with-courtApplication-and-prosecutioncase.json",
                    MASTER_DEFENDANT)).hasSize(1);
        }

        @Test
        @DisplayName("when there are no prosecution case but matching court application")
        void matching_court_application_with_no_prosecution_case() {
            assertThat(defendantsOf(
                    "hearing-resulted-with-only-courtApplication.json", MASTER_DEFENDANT))
                    .hasSize(1);
        }

        @Test
        @DisplayName("when there are no matching defendants in both Prosecution Case and Court "
                + "Application")
        void matching_nowhere() {
            assertThat(defendantsOf(
                    "hearing-resulted-with-no-matching-defendants.json", UNKNOWN_DEFENDANT))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("which record comes back")
    class WhichRecord {

        @Test
        @DisplayName("a match among the prosecution cases answers with the case's own defendant")
        void a_case_match_answers_with_the_case_record() {
            final JsonNode found =
                    defendantsOf("hearing-resulted.json", MASTER_DEFENDANT).get(0);

            assertThat(found.get("id").stringValue()).isEqualTo(MASTER_DEFENDANT);
            assertThat(found.get("prosecutionCaseId")).isNotNull();
        }

        @Test
        @DisplayName("a match among the applications answers with the subject's master defendant")
        void an_application_match_answers_with_the_subject_record() {
            final JsonNode found = defendantsOf(
                    "hearing-resulted-with-courtApplication-and-prosecutioncase.json",
                    MASTER_DEFENDANT).get(0);

            assertThat(found.get("masterDefendantId").stringValue()).isEqualTo(MASTER_DEFENDANT);
            assertThat(found.get("id")).isNull();
            assertThat(found.get("prosecutionCaseId")).isNull();
        }
    }

    @Nested
    @DisplayName("precedence")
    class Precedence {

        @Test
        @DisplayName("cases are searched first, and an application matching the same defendant is "
                + "not consulted")
        void the_prosecution_cases_win() {
            final List<JsonNode> found = defendantsOf(
                    "hearing-resulted-with-no-matching-defendants.json", MASTER_DEFENDANT);

            assertThat(found).hasSize(1);
            assertThat(found.get(0).get("id")).isNotNull();
            assertThat(found.get(0).get("prosecutionCaseId")).isNotNull();
        }

        @Test
        @DisplayName("every prosecution case is searched, not only the first")
        void every_prosecution_case_is_searched() {
            final JsonNode hearing = mapper.readTree(
                    "{\"prosecutionCases\":["
                            + "{\"defendants\":[{\"id\":\"a\",\"masterDefendantId\":\"m\"}]},"
                            + "{\"defendants\":[{\"id\":\"b\",\"masterDefendantId\":\"m\"}]}]}");

            assertThat(DefendantMapper.defendantsOf(hearing, "m"))
                    .extracting(defendant -> defendant.get("id").stringValue())
                    .containsExactly("a", "b");
        }
    }

    @Nested
    @DisplayName("when the hearing carries little")
    class SparseHearing {

        @Test
        @DisplayName("a hearing with neither cases nor applications answers with an empty list")
        void a_bare_hearing_answers_with_an_empty_list() {
            assertThat(DefendantMapper.defendantsOf(mapper.readTree("{}"), MASTER_DEFENDANT))
                    .isEmpty();
        }

        @Test
        @DisplayName("an application whose subject names no master defendant is passed over")
        void an_application_with_no_master_defendant_is_passed_over() {
            assertThat(defendantsOf("hearing-resulted.json", UNKNOWN_DEFENDANT)).isEmpty();
        }
    }

    /**
     * Finds the defendant records for a master defendant on one legacy fixture.
     *
     * @param fixture           the fixture's file name under {@code mappers/defendant/}
     * @param masterDefendantId the defendant's identity across cases and applications
     * @return the matching records
     */
    private List<JsonNode> defendantsOf(final String fixture, final String masterDefendantId) {
        return DefendantMapper.defendantsOf(
                LegacyFixtures.readCourtRegister("mappers/defendant/" + fixture),
                masterDefendantId);
    }
}
