package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterResult;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ResultLevel;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;
import uk.gov.hmcts.cp.yotresultsdistribution.support.LegacyFixtures;

/**
 * The gather, in the one configuration the court register has.
 *
 * <p>The legacy {@code DefendantContextService} takes three flags and the Jest suite exercises three
 * combinations of them; this service only ever makes one call —
 * {@code new DefendantContextService(hearingObj, true)}, {@code SetCourtRegister/index.js:33} — so
 * every case below runs in that configuration and the two contrast cases say explicitly what the
 * other calls answer.
 *
 * <p><strong>C22 is the fix in this file</strong>, and it is the one the design document calls the
 * highest-value content question in the register: the legacy admits a court application on the
 * strength of its <em>subject</em> alone, so an application brought by anybody — a defence
 * application, a third party's — contributes its results to a register of a court's prosecutions.
 * The eligibility cases assert the fixed gate and therefore fail against the legacy, which is what
 * they are for. Every expectation in the file was taken by running the vendored
 * {@code DefendantContextBaseService.js} under {@code TZ=Europe/London}, once as it stands and once
 * with the fixed gate patched in, so both columns are measured rather than reasoned about.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> row C22
 */
@DisplayName("DefendantContextBuilder")
class DefendantContextBuilderTest {

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    private final Dates dates = new Dates();

    @Nested
    @DisplayName("court-application eligibility (C22)")
    class Eligibility {

        /**
         * C22, and the case the fix specification names. {@code application-case-level.json}'s
         * applicant is another master defendant — the fixture is a defendant-brought application —
         * and the legacy gathers its two judicial results onto the subject's register anyway.
         */
        @Test
        @DisplayName("admits an application only when a prosecuting authority brought it")
        void applications_require_a_prosecuting_authority_applicant() {
            assertThat(gather("application-case-level.json"))
                    .as("the applicant is a master defendant, so the application is not the "
                            + "court's own and contributes nothing")
                    .isEmpty();

            assertThat(gather("application-case-level-prosecuting-applicant.json"))
                    .as("the same application, brought by Derbyshire Police")
                    .singleElement()
                    .satisfies(defendant -> assertThat(defendant.applications())
                            .containsExactly("1ff65571-c05c-4610-9a3f-f2f3f1728119"));
        }

        @Test
        @DisplayName("leaves the defendant's own case results untouched when it drops an application")
        void leaves_the_case_results_untouched_when_it_drops_an_application() {
            // The legacy gathers nine results onto this defendant and orders the register by
            // 2021-03-25, a date that reaches it only through the application. With the application
            // dropped the defendant keeps the two results their own prosecution case ordered, and
            // the register is dated by those.
            final List<DefendantContext> gathered = gather(
                    "hearing-results-from-prosecution-case-and-court-application-for-ordered-date"
                            + ".json");

            assertThat(gathered).hasSize(2);
            assertThat(gathered.get(0).results()).hasSize(2);
            assertThat(gathered.get(0).applications()).isEmpty();
            assertThat(gathered.get(0).orderedDate()).isEqualTo("2020-04-19");
            assertThat(gathered.get(1).results()).hasSize(4);
            assertThat(gathered.get(1).orderedDate()).isEqualTo("2020-04-17");
        }

        @Test
        @DisplayName("gathers nobody from a hearing that is only an ineligible application")
        void gathers_nobody_from_a_hearing_that_is_only_an_ineligible_application() {
            // The whole hearing is one defendant-brought application, so there is no prosecution
            // case to fall back to. The legacy produces a register defendant with seven results;
            // the fixed gather produces none, which the pipeline records as `no-defendants` rather
            // than as a register nobody asked for.
            assertThat(gather("hearing-results-from-court-application-for-ordered-date.json"))
                    .isEmpty();
        }

        @Test
        @DisplayName("keeps an application a prosecuting authority brought against a subject")
        void keeps_an_application_a_prosecuting_authority_brought() {
            final List<DefendantContext> gathered = gather("linked-application.json");

            assertThat(gathered).hasSize(2);
            assertThat(gathered.get(1).applications())
                    .containsExactly("71ee29f4-f092-4d2a-b984-044dcf13bb39");
            assertThat(gathered.get(1).results()).hasSize(7);
        }

        @Test
        @DisplayName("drops an application with no applicant at all rather than failing on it")
        void drops_an_application_with_no_applicant_at_all() {
            // `courtApplication.applicant.prosecutingAuthority` dereferences an absent applicant.
            // The gate has to answer "not eligible" for a payload shape it cannot read the applicant
            // of, because refusing the whole hearing there would lose a register the legacy files.
            assertThat(gatherJson("""
                {"courtApplications":[{"id":"app-1",
                  "subject":{"masterDefendant":{"masterDefendantId":"master-1","isYouth":true}},
                  "judicialResults":[{"judicialResultId":"jr-1","orderedDate":"2020-04-17"}]}]}"""))
                    .isEmpty();
        }

        @Test
        @DisplayName("drops an application whose subject is not a master defendant")
        void drops_an_application_whose_subject_is_not_a_master_defendant() {
            // The half of the gate the legacy did implement, which the fix keeps.
            assertThat(gatherJson("""
                {"courtApplications":[{"id":"app-1",
                  "applicant":{"prosecutingAuthority":{"prosecutionAuthorityId":"auth-1"}},
                  "subject":{"id":"subject-1"},
                  "judicialResults":[{"judicialResultId":"jr-1","orderedDate":"2020-04-17"}]}]}"""))
                    .isEmpty();
        }
    }

    /**
     * DC5's twin, repointed. The Jest case is written against the <em>informant</em> register's
     * three-argument call, and its name says so: "should not consider results from application when
     * applicant is not prosecuting authority from linked application for informant register". The
     * court register's two-argument call reaches the other branch of the same {@code if} and admits
     * the application it excludes.
     *
     * <p>The contrast is the point. Before C22 these two flows disagree about a payload neither of
     * them owns; after it they agree, and the case below is what that agreement looks like — the
     * court register's own answer for the fixture the informant register was given to prove its
     * exclusion.
     */
    @Nested
    @DisplayName("DC5 — the informant contrast, which C22 closes")
    class InformantContrast {

        @Test
        @DisplayName("should not consider results from application when applicant is not "
                + "prosecuting authority")
        void should_not_consider_results_from_a_non_prosecuting_applicant() {
            final List<DefendantContext> gathered =
                    gather("linked-application-not-eligible-for-registers.json");

            assertThat(gathered).hasSize(2);
            assertThat(gathered).allSatisfy(defendant -> {
                assertThat(defendant.results()).hasSize(6);
                assertThat(defendant.results())
                        .allSatisfy(result -> assertThat(result.applicationId()).isNull());
                assertThat(defendant.applications()).isEmpty();
            });
        }

        @Test
        @DisplayName("answers the same for that fixture as the informant register's own call does")
        void answers_the_same_as_the_informant_registers_own_call() {
            // The legacy's court-register call gathers a seventh, application-level result onto the
            // second defendant; its informant call does not. Running both fixtures through this
            // builder is the cheapest statement of what C22 changes: the eligible one keeps its
            // application, the ineligible one does not, and the difference is the applicant.
            final List<DefendantContext> ineligible =
                    gather("linked-application-not-eligible-for-registers.json");
            final List<DefendantContext> eligible = gather("linked-application.json");

            assertThat(ineligible.get(1).results()).hasSize(6);
            assertThat(eligible.get(1).results()).hasSize(7);
        }
    }

    @Nested
    @DisplayName("result levels in the register configuration")
    class ResultLevels {

        @Test
        @DisplayName("tags an application's own judicial results at application level")
        void tags_an_applications_own_results_at_application_level() {
            final List<RegisterResult> results =
                    gather("application-case-level-prosecuting-applicant.json").get(0).results();

            assertThat(results.get(0).level()).isEqualTo(ResultLevel.APPLICATION);
            assertThat(results.get(0).applicationId())
                    .isEqualTo("1ff65571-c05c-4610-9a3f-f2f3f1728119");
            assertThat(results.get(0).offenceId()).isNull();
        }

        @Test
        @DisplayName("tags an application case's offence results at offence level, not application")
        void tags_application_case_offences_at_offence_level() {
            // `isRegister` is the whole of this: DefendantContextBaseService.js:221-225 writes
            // OFFENCE for a register and APPLICATION otherwise. It is what lets the outbound mappers
            // scope an application's results to the offence they were ordered against.
            final List<RegisterResult> results =
                    gather("application-case-level-prosecuting-applicant.json").get(0).results();

            assertThat(results.get(1).level())
                    .isEqualTo(ResultLevel.OFFENCE)
                    .isNotEqualTo(ResultLevel.APPLICATION);
            assertThat(results.get(1).offenceId())
                    .isEqualTo("962d9ad3-8786-492f-b0f1-b00979e0925f");
        }

        @Test
        @DisplayName("tags a court order's offence results at offence level too")
        void tags_court_order_offences_at_offence_level() {
            final List<RegisterResult> results =
                    gather("application-court-order-level.json").get(0).results();

            assertThat(results.get(2).level()).isEqualTo(ResultLevel.OFFENCE);
            assertThat(results.get(2).offenceId())
                    .isEqualTo("f4a88647-fa70-4954-a51b-e502ab504d03");
        }

        @Test
        @DisplayName("tags case, offence and defendant results by where they were found")
        void tags_case_offence_and_defendant_results_by_where_they_were_found() {
            final List<RegisterResult> results =
                    gather("hearing-results-for-prosecution-case.json").get(0).results();

            assertThat(results).extracting(RegisterResult::level).containsExactly(
                    ResultLevel.CASE, ResultLevel.OFFENCE, ResultLevel.OFFENCE,
                    ResultLevel.DEFENDANT, ResultLevel.DEFENDANT);
        }
    }

    @Nested
    @DisplayName("what the gather drops")
    class Dropped {

        @ParameterizedTest
        @ValueSource(strings = {
            """
            {"prosecutionCases":[{"id":"case-1","defendants":[
              {"id":"def-1","masterDefendantId":"master-1","offences":[],
               "defendantCaseJudicialResults":[
                {"judicialResultId":"kept","orderedDate":"2020-04-17"},
                {"judicialResultId":"gone","orderedDate":"2020-04-19","isDeleted":true}]}]}]}""",
            """
            {"prosecutionCases":[{"id":"case-1","defendants":[
              {"id":"def-1","masterDefendantId":"master-1","defendantCaseJudicialResults":[],
               "offences":[{"id":"off-1","judicialResults":[
                {"judicialResultId":"kept","orderedDate":"2020-04-17"},
                {"judicialResultId":"gone","orderedDate":"2020-04-19",
                 "isDeleted":true}]}]}]}]}""",
            """
            {"courtApplications":[{"id":"app-1",
              "applicant":{"prosecutingAuthority":{"prosecutionAuthorityId":"auth-1"}},
              "subject":{"masterDefendant":{"masterDefendantId":"master-1"}},
              "judicialResults":[
               {"judicialResultId":"kept","orderedDate":"2020-04-17"},
               {"judicialResultId":"gone","orderedDate":"2020-04-19","isDeleted":true}]}]}""",
        })
        @DisplayName("drops a deleted judicial result wherever it was recorded")
        void drops_a_deleted_judicial_result(final String hearing) {
            // Every one of the legacy's gather passes guards on `!isDeleted`, and a deleted result
            // that survived would not only appear on the register, it would date it: the ordered
            // date of each deleted result here is the later of the two.
            final List<DefendantContext> gathered = gatherJson(hearing);

            assertThat(gathered).singleElement().satisfies(defendant ->
                    assertThat(defendant.results())
                            .singleElement()
                            .satisfies(result ->
                                    assertThat(judicialResultId(result)).isEqualTo("kept")));
        }

        @Test
        @DisplayName("drops a defendant with no identity across cases")
        void drops_a_defendant_with_no_identity_across_cases() {
            // DefendantContextBaseService.js:50 — a context with no masterDefendantId is never
            // pushed onto the list. Nothing downstream can match subscriptions for a defendant it
            // cannot name across the hearing's cases.
            assertThat(gatherJson("""
                {"prosecutionCases":[{"id":"case-1","defendants":[
                  {"id":"def-1","offences":[],"defendantCaseJudicialResults":[
                   {"judicialResultId":"jr-1","orderedDate":"2020-04-17"}]}]}]}"""))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the youth flag")
    class Youth {

        @Test
        @DisplayName("carries a youth subject's flag from the application that names them")
        void carries_a_youth_subjects_flag_from_the_application() {
            assertThat(gather("application-case-level-prosecuting-applicant.json").get(0)
                    .youthDefendant()).isTrue();
        }

        @Test
        @DisplayName("carries an adult subject's flag as false rather than as absent")
        void carries_an_adult_subjects_flag_as_false() {
            assertThat(gather("application-court-order-level.json").get(0).youthDefendant())
                    .isFalse();
        }

        @Test
        @DisplayName("takes the flag from the first record that names the defendant")
        void takes_the_flag_from_the_first_record_that_names_the_defendant() {
            // `if (!defendantBase.masterDefendantId)` — the flag is set once, with the id, and a
            // later case naming the same master defendant does not revise it. The register's youth
            // filter is the whole business rule of this flow, so which record it is read from is
            // not a detail.
            assertThat(gatherJson("""
                {"prosecutionCases":[
                  {"id":"case-1","defendants":[{"id":"def-1","masterDefendantId":"master-1",
                   "isYouth":true,"offences":[],"defendantCaseJudicialResults":[
                    {"judicialResultId":"jr-1","orderedDate":"2020-04-17"}]}]},
                  {"id":"case-2","defendants":[{"id":"def-2","masterDefendantId":"master-1",
                   "isYouth":false,"offences":[],"defendantCaseJudicialResults":[
                    {"judicialResultId":"jr-2","orderedDate":"2020-04-18"}]}]}]}""")
                    .get(0).youthDefendant()).isTrue();
        }
    }

    @Nested
    @DisplayName("an application case that names no prosecution case")
    class UnnamedApplicationCases {

        /**
         * Uncatalogued legacy behaviour, restored. {@code DefendantContextBaseService.js:151-165}
         * pushes {@code courtApplicationCase.prosecutionCaseId} onto {@code defendantBase.cases}
         * with no guard at all, so an application case — or a court-order offence — that names no
         * prosecution case puts a bare {@code undefined} on the list. Nothing there fails on it:
         * the reference travels as far as
         * {@code ProsecutionCaseOrApplicationMapper.js:27-33}, finds no prosecution case of that
         * id, and is <em>warned about and skipped</em> — the SNI-9005 guard ({@code 0781bbc2}),
         * whose own Jest case (PC3) is written for exactly this shape. The register is filed
         * without that case.
         *
         * <p>The port cannot carry the {@code undefined} the same distance: a {@code null} in the
         * gathered case list reaches {@code RegisterDefendant}'s {@code List.copyOf} and throws,
         * which turns a hearing the legacy files into an {@code UNEXPECTED_FAILURE} on the
         * dead-letter queue. So the skip happens where the reference is gathered instead, and it
         * is said out loud in the same words the mapper's guard uses.
         */
        @ParameterizedTest(name = "[{index}]")
        @ValueSource(strings = {
            """
            {"courtApplications":[{"id":"app-1",
              "applicant":{"prosecutingAuthority":{"prosecutionAuthorityId":"auth-1"}},
              "subject":{"masterDefendant":{"masterDefendantId":"master-1","isYouth":true}},
              "courtApplicationCases":[{"prosecutionCaseId":"case-1"},{"offences":[]}],
              "judicialResults":[{"judicialResultId":"jr-1","orderedDate":"2020-04-17"}]}]}""",
            """
            {"courtApplications":[{"id":"app-1",
              "applicant":{"prosecutingAuthority":{"prosecutionAuthorityId":"auth-1"}},
              "subject":{"masterDefendant":{"masterDefendantId":"master-1","isYouth":true}},
              "courtApplicationCases":[{"prosecutionCaseId":"case-1"}],
              "courtOrder":{"courtOrderOffences":[{"offence":{"id":"offence-1"}}]},
              "judicialResults":[{"judicialResultId":"jr-1","orderedDate":"2020-04-17"}]}]}""",
        })
        @DisplayName("warns and skips it, where the legacy files the register without it")
        void warns_and_skips_an_application_case_naming_no_prosecution_case(final String hearing) {
            try (CapturedLog log = CapturedLog.capturing(DefendantContextBuilder.class)) {
                final List<DefendantContext> gathered = gatherJson(hearing);

                assertThat(gathered)
                        .as("the hearing is still gathered, as the legacy gathers it")
                        .singleElement()
                        .satisfies(defendant -> assertThat(defendant.cases())
                                .as("the case the application does name, and nothing standing "
                                        + "for the one it does not")
                                .containsExactly("case-1"));
                assertThat(log.messages())
                        .as("the skip is not silent — the legacy warns about the same reference "
                                + "one stage later")
                        .anySatisfy(message -> assertThat(message)
                                .contains("Prosecution case not found")
                                .contains("skipping"));
            }
        }

        /**
         * The same warning, read as Principle VII reads it.
         *
         * <p>This line is a <strong>parity</strong> warning: it exists because the legacy skips the
         * same reference one stage later, and nothing on the register's fix list authorises it to
         * carry an identifier. The permitted correlation set at INFO and above is
         * {@code requestId}, {@code hearingId}, {@code hearingDay}, {@code source}, the court
         * centre's id or OU code, counts and timings — a court application's id is none of them,
         * and this builder is a pure transformation stage that holds no correlation of its own
         * anyway. The run-aware layers put {@code requestId} and {@code hearingId} on the line
         * through the MDC; what this stage adds is the bounded code and nothing else.
         *
         * <p>Exactly one warning in this service names a court application, and it is a different
         * one: {@code ProsecutionCaseOrApplicationMapper}'s unresolvable-application line, which
         * the C20 register row authorises in as many words. A row authorises one warning, not a
         * class of them.
         *
         * <p>Written as a refusal rather than as an assertion about wording, because that is the
         * property that matters: not "the message says X" but "the identifier is nowhere in the
         * output at all".
         */
        @ParameterizedTest(name = "[{index}]")
        @ValueSource(strings = {
            """
            {"courtApplications":[{"id":"app-1",
              "applicant":{"prosecutingAuthority":{"prosecutionAuthorityId":"auth-1"}},
              "subject":{"masterDefendant":{"masterDefendantId":"master-1","isYouth":true}},
              "courtApplicationCases":[{"prosecutionCaseId":"case-1"},{"offences":[]}],
              "judicialResults":[{"judicialResultId":"jr-1","orderedDate":"2020-04-17"}]}]}""",
            """
            {"courtApplications":[{"id":"app-1",
              "applicant":{"prosecutingAuthority":{"prosecutionAuthorityId":"auth-1"}},
              "subject":{"masterDefendant":{"masterDefendantId":"master-1","isYouth":true}},
              "courtApplicationCases":[{"prosecutionCaseId":"case-1"}],
              "courtOrder":{"courtOrderOffences":[{"offence":{"id":"offence-1"}}]},
              "judicialResults":[{"judicialResultId":"jr-1","orderedDate":"2020-04-17"}]}]}""",
        })
        @DisplayName("never names the application, which is outside the permitted correlations")
        void never_names_the_application_it_skipped_a_case_for(final String hearing) {
            try (CapturedLog log = CapturedLog.capturing(DefendantContextBuilder.class)) {
                gatherJson(hearing);

                assertThat(log.renderings())
                        .as("a court application's id is not in the permitted correlation set, and "
                                + "only C20's row authorises an id — in a different warning")
                        .isNotEmpty()
                        .noneMatch(line -> line.contains("app-1"));
            }
        }
    }

    /**
     * The JUnit twins of the legacy {@code DefendantContextBaseService} Jest suite.
     *
     * <p>That suite declares eleven cases across three configurations. Nine are twinned below under
     * their Jest names; the two that are not are the ones written against a configuration this
     * service does not have — the informant register's three-argument call
     * ({@code …for informant register}), whose subject is C22 and which is twinned in its own nest
     * above instead.
     *
     * <p>Four of the twins run against a configuration the Jest case does not use. DC1–DC4, DC6, DC7
     * and DC9 call {@code new DefendantContextService(hearingResults)} with no flags at all, which is
     * the NOWs gather; this service always passes {@code true}. Where that changes the answer the
     * twin says so:
     *
     * <ul>
     *   <li>{@code …with application case level judicial results} and {@code …with application court
     *       order level judicial results} assert {@code APPLICATION} for results the register tags
     *       {@code OFFENCE}. Their {@code …for registers} counterparts assert what this service
     *       produces, and both are kept so the split is visible as a pair rather than as one value
     *       somebody could quietly change.</li>
     *   <li>{@code should return latest ordered date from court application} and its combined
     *       counterpart assert dates the register no longer reaches, because C22 drops the
     *       applications those fixtures carry. The eligibility nest above holds the fixed answers;
     *       these twins record what moved.</li>
     * </ul>
     */
    @Nested
    @DisplayName("DefendantContextBaseService — legacy Jest twins")
    class LegacyJestTwins {

        @Test
        @DisplayName("should return latest ordered date from prosecution case")
        void should_return_latest_ordered_date_from_prosecution_case() {
            final List<DefendantContext> gathered =
                    gather("hearing-results-from-prosecution-case-for-ordered-date.json");

            assertThat(gathered).hasSize(2);
            assertThat(gathered.get(0).orderedDate()).isEqualTo("2020-04-19");
            assertThat(gathered.get(1).orderedDate()).isEqualTo("2020-04-17");
        }

        @Test
        @DisplayName("should return latest ordered date from court application — repointed at C22")
        void should_return_latest_ordered_date_from_court_application() {
            // The Jest case asserts one defendant ordered 2021-03-25, every result of which reaches
            // them through an application their co-defendant brought.
            assertThat(gather("hearing-results-from-court-application-for-ordered-date.json"))
                    .isEmpty();
        }

        @Test
        @DisplayName("should return latest ordered date from both prosecution case and court "
                + "application — repointed at C22")
        void should_return_latest_ordered_date_from_both() {
            // The Jest case asserts 2021-03-25 and 2020-04-17. The first defendant's 2021-03-25
            // comes from the dropped application; their own case ordered 2020-04-19.
            final List<DefendantContext> gathered = gather(
                    "hearing-results-from-prosecution-case-and-court-application-for-ordered-date"
                            + ".json");

            assertThat(gathered).hasSize(2);
            assertThat(gathered.get(0).orderedDate()).isEqualTo("2020-04-19");
            assertThat(gathered.get(1).orderedDate()).isEqualTo("2020-04-17");
        }

        @Test
        @DisplayName("should return results for a defendant at appropriate level for prosecution "
                + "case")
        void should_return_results_at_appropriate_level_for_prosecution_case() {
            final List<DefendantContext> gathered =
                    gather("hearing-results-for-prosecution-case.json");

            assertThat(gathered).hasSize(1);
            final DefendantContext defendant = gathered.get(0);
            final List<RegisterResult> results = defendant.results();

            assertThat(results).hasSize(5);
            assertThat(judicialResultId(results.get(0)))
                    .isEqualTo("509ae32f-2083-43d3-885d-35da2a769f7d");
            assertThat(results.get(0).level()).isEqualTo(ResultLevel.CASE);
            assertThat(judicialResultId(results.get(1)))
                    .isEqualTo("34a84855-821e-4261-bb0e-2c9656fa1ac0");
            assertThat(results.get(1).level()).isEqualTo(ResultLevel.OFFENCE);
            assertThat(judicialResultId(results.get(2)))
                    .isEqualTo("6f7c14a5-e81e-46a6-a4a3-9b2fb161dcae");
            assertThat(results.get(2).level()).isEqualTo(ResultLevel.OFFENCE);
            assertThat(judicialResultId(results.get(3)))
                    .isEqualTo("9eea9c9b-9528-4a17-a86c-fa7452c0b9f2");
            assertThat(results.get(3).level()).isEqualTo(ResultLevel.DEFENDANT);
            assertThat(judicialResultId(results.get(4)))
                    .isEqualTo("fe3d78c2-9901-458e-a282-572519eb8713");
            assertThat(results.get(4).level()).isEqualTo(ResultLevel.DEFENDANT);

            assertThat(defendant.defendantIds())
                    .containsExactly("8bd0c5e1-49bb-46c5-ad30-315090b772cc");
            assertThat(defendant.cases())
                    .containsExactly("79683c78-2259-4fe3-bff4-9b305a33dfdc");
            assertThat(defendant.masterDefendantId())
                    .isEqualTo("8bd0c5e1-49bb-46c5-ad30-315090b772cc");
            assertThat(defendant.youthDefendant()).isFalse();
            assertThat(defendant.applications()).isEmpty();
        }

        @Test
        @DisplayName("should return results for a defendant at application level from linked "
                + "application")
        void should_return_results_at_application_level_from_linked_application() {
            final List<DefendantContext> gathered = gather("linked-application.json");

            assertThat(gathered).hasSize(2);
            assertThat(gathered.get(0).results()).hasSize(6);
            assertThat(gathered.get(0).results())
                    .allSatisfy(result -> assertThat(result.applicationId()).isNull());
            assertThat(gathered.get(0).orderedDate()).isEqualTo("2021-03-15");
            assertThat(gathered.get(0).applications()).isEmpty();
            assertThat(gathered.get(0).youthDefendant()).isFalse();

            assertThat(gathered.get(1).results()).hasSize(7);
            assertThat(gathered.get(1).orderedDate()).isEqualTo("2021-03-15");
            assertThat(gathered.get(1).results().subList(0, 6))
                    .allSatisfy(result -> assertThat(result.applicationId()).isNull());
            assertThat(gathered.get(1).results().get(6).applicationId())
                    .isEqualTo("71ee29f4-f092-4d2a-b984-044dcf13bb39");
            assertThat(gathered.get(1).results().get(6).level())
                    .isEqualTo(ResultLevel.APPLICATION);
            assertThat(gathered.get(1).applications())
                    .containsExactly("71ee29f4-f092-4d2a-b984-044dcf13bb39");
            assertThat(gathered.get(1).youthDefendant()).isFalse();
        }

        @Test
        @DisplayName("it should return DefendantContextService with application case level "
                + "judicial results — the non-register tagging this service does not use")
        void application_case_level_results_are_not_tagged_the_non_register_way() {
            // The Jest case's flagless call tags both results APPLICATION. This service passes
            // isRegister, so the second is an offence result; asserting the inequality is what stops
            // the flag being dropped in a refactor with nothing to notice it.
            final List<RegisterResult> results =
                    gather("application-case-level-prosecuting-applicant.json").get(0).results();

            assertThat(results).hasSize(2);
            assertThat(results.get(1).level()).isNotEqualTo(ResultLevel.APPLICATION);
        }

        @Test
        @DisplayName("it should return DefendantContextService with application case level "
                + "judicial results for registers")
        void application_case_level_judicial_results_for_registers() {
            final List<DefendantContext> gathered =
                    gather("application-case-level-prosecuting-applicant.json");

            assertThat(gathered).hasSize(1);
            final DefendantContext defendant = gathered.get(0);

            assertThat(defendant.results()).hasSize(2);
            assertThat(judicialResultId(defendant.results().get(0)))
                    .isEqualTo("d7718a03-9f5c-417c-9d33-16bd046c7b6d");
            assertThat(defendant.results().get(0).level()).isEqualTo(ResultLevel.APPLICATION);
            assertThat(judicialResultId(defendant.results().get(1)))
                    .isEqualTo("3c38631f-053e-4be6-aa93-0afaf80d03c1");
            assertThat(defendant.results().get(1).level()).isEqualTo(ResultLevel.OFFENCE);
            assertThat(defendant.applications())
                    .containsExactly("1ff65571-c05c-4610-9a3f-f2f3f1728119");
            assertThat(defendant.orderedDate()).isEqualTo("2020-04-17");
            assertThat(defendant.youthDefendant()).isTrue();
        }

        @Test
        @DisplayName("it should return DefendantContextService with application court order level "
                + "judicial results — the non-register tagging this service does not use")
        void application_court_order_results_are_not_tagged_the_non_register_way() {
            final List<RegisterResult> results =
                    gather("application-court-order-level.json").get(0).results();

            assertThat(results).hasSize(3);
            assertThat(results.get(1).level()).isNotEqualTo(ResultLevel.APPLICATION);
            assertThat(results.get(2).level()).isNotEqualTo(ResultLevel.APPLICATION);
        }

        @Test
        @DisplayName("it should return DefendantContextService with application court order level "
                + "judicial results for registers")
        void application_court_order_level_judicial_results_for_registers() {
            final List<DefendantContext> gathered = gather("application-court-order-level.json");

            assertThat(gathered).hasSize(1);
            final DefendantContext defendant = gathered.get(0);

            assertThat(defendant.results()).hasSize(3);
            assertThat(judicialResultId(defendant.results().get(0)))
                    .isEqualTo("d7718a03-9f5c-417c-9d33-16bd046c7b6d");
            assertThat(defendant.results().get(0).level()).isEqualTo(ResultLevel.APPLICATION);
            assertThat(judicialResultId(defendant.results().get(1)))
                    .isEqualTo("3c38631f-053e-4be6-aa93-0afaf80d03c1");
            assertThat(defendant.results().get(1).level()).isEqualTo(ResultLevel.OFFENCE);
            assertThat(judicialResultId(defendant.results().get(2)))
                    .isEqualTo("8d75084d-a1c7-45ab-899a-d9a165cb45d6");
            assertThat(defendant.results().get(2).offenceId())
                    .isEqualTo("f4a88647-fa70-4954-a51b-e502ab504d03");
            assertThat(defendant.results().get(2).level()).isEqualTo(ResultLevel.OFFENCE);
            assertThat(defendant.applications())
                    .containsExactly("1ff65571-c05c-4610-9a3f-f2f3f1728119");
            assertThat(defendant.orderedDate()).isEqualTo("2020-04-17");
            assertThat(defendant.youthDefendant()).isFalse();
        }
    }

    /**
     * Gathers a legacy fixture, in the court register's configuration.
     *
     * @param fixture the fixture file name
     * @return the gathered defendants
     */
    private List<DefendantContext> gather(final String fixture) {
        return new DefendantContextBuilder(LegacyFixtures.read(fixture), dates).build();
    }

    /**
     * Gathers a hearing written inline, in the court register's configuration.
     *
     * @param hearing the hearing as JSON text
     * @return the gathered defendants
     */
    private List<DefendantContext> gatherJson(final String hearing) {
        return new DefendantContextBuilder(readTree(hearing), dates).build();
    }

    /**
     * Parses a hearing the way a fetched payload is parsed.
     *
     * @param hearing the hearing as JSON text
     * @return the parsed tree
     */
    private JsonNode readTree(final String hearing) {
        return mapper.readTree(hearing);
    }

    /**
     * The identity of a gathered result's judicial result.
     *
     * @param result the gathered result
     * @return its {@code judicialResultId}
     */
    private static String judicialResultId(final RegisterResult result) {
        return result.judicialResult().get("judicialResultId").stringValue();
    }
}
