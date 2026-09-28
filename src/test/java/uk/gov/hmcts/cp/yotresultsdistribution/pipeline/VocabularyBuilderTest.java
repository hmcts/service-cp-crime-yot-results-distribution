package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterVocabulary;
import uk.gov.hmcts.cp.yotresultsdistribution.support.LegacyFixtures;

/**
 * The eighteen facts a subscription is matched against, and the seven the fixtures know about.
 *
 * <p>The key set is the first thing this suite asserts and the most important. Reference data
 * matches a subscription by looking each of its declared vocabulary flags up <em>by name</em>, so a
 * component this service spells differently is a predicate that silently never matches — and seven
 * of the court register's own Jest fixtures carry a seven-key block with two of those names
 * mis-capitalised. Nothing in the legacy suite inspects a vocabulary object, so a port that took its
 * key set from a fixture would lose subscription matching in production on {@code youthDefendant},
 * which is this flow's entire business rule, with every legacy test still green.
 *
 * <p>Every expectation below was taken by running the vendored
 * {@code NowsHelper/service/VocabularyService.js} under {@code TZ=Europe/London} in the court
 * register's own two-argument construction.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> row C30
 */
@DisplayName("VocabularyBuilder")
class VocabularyBuilderTest {

    /**
     * The vocabulary reference data names, in {@code VocabularyInfo}'s own order.
     *
     * <p>{@code atleastOne…} carries a lower-case {@code l}. That is not a typo here; it is the
     * kernel's spelling, and the fixtures' {@code atLeastOne…} is the typo.
     */
    private static final String[] REAL_KEYS = {
        "custodyLocationIsPolice",
        "custodyLocationIsPrison",
        "atleastOneCustodialResult",
        "allNonCustodialResults",
        "atleastOneNonCustodialResult",
        "appearedInPerson",
        "appearedByVideoLink",
        "isCpsProsecuted",
        "anyAppearance",
        "inCustody",
        "youthDefendant",
        "adultDefendant",
        "adultOrYouthDefendant",
        "welshCourtHearing",
        "englishCourtHearing",
        "anyCourtHearing",
        "prosecutorMajorCreditor",
        "nonProsecutorMajorCreditor",
    };

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    private final Dates dates = new Dates();

    @Nested
    @DisplayName("the key set")
    class KeySet {

        @Test
        @DisplayName("carries exactly the eighteen keys reference data matches by name")
        void carries_only_the_eighteen_keys_reference_data_names() {
            assertThat(keysOf(vocabularyOf(defendantHeldAt("Prison"))))
                    .containsExactly(REAL_KEYS);
        }

        @Test
        @DisplayName("spells atleastOne the way the kernel does, not the way the fixtures do")
        void spells_atleast_one_the_way_the_kernel_does() {
            // The two keys the seven court-register fixtures get wrong. A subscription requiring
            // `atleastOneCustodialResult` is looked up by that name; a vocabulary carrying
            // `atLeastOneCustodialResult` answers nothing and the subscription never matches.
            assertThat(keysOf(vocabularyOf(defendantHeldAt("Prison"))))
                    .contains("atleastOneCustodialResult", "atleastOneNonCustodialResult")
                    .doesNotContain("atLeastOneCustodialResult", "atLeastOneNonCustodialResult");
        }

        @Test
        @DisplayName("carries the eleven flags no court-register fixture has ever carried")
        void carries_the_eleven_flags_no_fixture_has_carried() {
            // Named individually rather than counted, because two of them decide who receives the
            // register: `youthDefendant` is the flow's business rule and `anyCourtHearing` is what
            // the kernel's own court-register subscription case actually matches on.
            assertThat(keysOf(vocabularyOf(defendantHeldAt("Prison")))).contains(
                    "isCpsProsecuted", "anyAppearance", "inCustody", "youthDefendant",
                    "adultDefendant", "adultOrYouthDefendant", "welshCourtHearing",
                    "englishCourtHearing", "anyCourtHearing", "prosecutorMajorCreditor",
                    "nonProsecutorMajorCreditor");
        }

        @ParameterizedTest
        @CsvSource({
            "courtregistersubscriptions/register-defendant.json, /0/vocabulary",
            "outboundcourtregister/court-register-fragment.json, /registerDefendants/0/vocabulary",
            "mappers/offence/defendant-context-base.json, /vocabulary",
            "mappers/parentguardian/defendant-context-base.json, /vocabulary",
            "mappers/prosecutioncaseorapplication/defendant-context-base.json, /vocabulary",
            "mappers/youthdefendant/youth-defendants.json, /0/vocabulary",
            "mappers/youthdefendant/defendant-context-base.json, /vocabulary",
        })
        @DisplayName("is the key set every rebuilt fixture now carries too")
        void is_the_key_set_every_rebuilt_fixture_carries(final String path, final String pointer) {
            // A guard on the repair rather than on the port: these seven files are the raw material
            // of the subscription-matching and mapper suites, and the reason the defect is invisible
            // in the legacy is that no test ever looked at one of these blocks. This one does.
            assertThat(List.copyOf(LegacyFixtures.readRebuilt(path).at(pointer).propertyNames()))
                    .containsExactly(REAL_KEYS);
        }
    }

    @Nested
    @DisplayName("custody")
    class Custody {

        @Test
        @DisplayName("is police when the defendant is held at a police station")
        void is_police_when_held_at_a_police_station() {
            final RegisterVocabulary vocabulary = vocabularyOf(defendantHeldAt("Police Station"));
            assertThat(vocabulary.custodyLocationIsPolice()).isTrue();
            assertThat(vocabulary.custodyLocationIsPrison()).isFalse();
            assertThat(vocabulary.inCustody()).isTrue();
        }

        @Test
        @DisplayName("is prison when the defendant is held at a prison")
        void is_prison_when_held_at_a_prison() {
            final RegisterVocabulary vocabulary = vocabularyOf(defendantHeldAt("Prison"));
            assertThat(vocabulary.custodyLocationIsPrison()).isTrue();
            assertThat(vocabulary.custodyLocationIsPolice()).isFalse();
            assertThat(vocabulary.inCustody()).isTrue();
        }

        @Test
        @DisplayName("is neither for a location the legacy's switch does not name")
        void is_neither_for_an_unrecognised_location() {
            // LocationTypeEnum declares DETENTIONCENTRE and the switch has no case for it, so a
            // defendant held in one is not in custody as far as any subscription can tell.
            assertThat(vocabularyOf(defendantHeldAt("DETENTIONCENTRE")).inCustody()).isFalse();
        }

        @Test
        @DisplayName("is read from a court application's subject as well as from a case")
        void is_read_from_a_court_application_subject() {
            assertThat(vocabularyOf("""
                {"courtCentre":{"name":"Lavender Hill"},
                 "courtApplications":[{"id":"app-1",
                  "applicant":{"prosecutingAuthority":{"prosecutionAuthorityId":"auth-1"}},
                  "subject":{"masterDefendant":{"masterDefendantId":"master-1",
                   "personDefendant":{"custodialEstablishment":{"custody":"Prison"}}}},
                  "judicialResults":[{"judicialResultId":"jr-1","orderedDate":"2020-01-20"}]}]}""")
                    .custodyLocationIsPrison()).isTrue();
        }

        @Test
        @DisplayName("is not borrowed from a co-defendant held elsewhere")
        void is_not_borrowed_from_a_co_defendant() {
            assertThat(vocabularyOf("master-1", """
                {"courtCentre":{"name":"Lavender Hill"},
                 "prosecutionCases":[{"id":"case-1",
                  "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
                   "defendantCaseJudicialResults":[
                    {"judicialResultId":"jr-1","orderedDate":"2020-01-20"}]}]},
                  {"id":"case-2",
                   "defendants":[{"id":"def-2","masterDefendantId":"master-2","offences":[],
                    "defendantCaseJudicialResults":[
                     {"judicialResultId":"jr-2","orderedDate":"2020-01-20"}],
                    "personDefendant":{"custodialEstablishment":{"custody":"Prison"}}}]}]}""")
                    .inCustody()).isFalse();
        }
    }

    @Nested
    @DisplayName("appearance")
    class Appearance {

        @Test
        @DisplayName("is in person when the defendant attended on a day a result was ordered")
        void is_in_person_when_attended_on_a_resulted_day() {
            final RegisterVocabulary vocabulary = vocabularyOf(attended("IN_PERSON", "2020-01-20"));
            assertThat(vocabulary.appearedInPerson()).isTrue();
            assertThat(vocabulary.appearedByVideoLink()).isFalse();
            assertThat(vocabulary.anyAppearance()).isTrue();
        }

        @Test
        @DisplayName("is by video link when the defendant attended that way")
        void is_by_video_when_attended_that_way() {
            final RegisterVocabulary vocabulary = vocabularyOf(attended("BY_VIDEO", "2020-01-20"));
            assertThat(vocabulary.appearedByVideoLink()).isTrue();
            assertThat(vocabulary.appearedInPerson()).isFalse();
            assertThat(vocabulary.anyAppearance()).isTrue();
        }

        @Test
        @DisplayName("does not count a day on which nothing was ordered")
        void does_not_count_a_day_with_no_result() {
            assertThat(vocabularyOf(attended("IN_PERSON", "2019-05-05")).anyAppearance()).isFalse();
        }

        @Test
        @DisplayName("does not count an attendance type the legacy does not name")
        void does_not_count_an_unnamed_attendance_type() {
            assertThat(vocabularyOf(attended("NOT_PRESENT", "2020-01-20")).anyAppearance())
                    .isFalse();
        }

        @Test
        @DisplayName("does not count attendance recorded against another defendant")
        void does_not_count_attendance_of_another_defendant() {
            assertThat(vocabularyOf("""
                {"courtCentre":{"name":"Lavender Hill"},
                 "prosecutionCases":[{"id":"case-1",
                  "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
                   "defendantCaseJudicialResults":[
                    {"judicialResultId":"jr-1","orderedDate":"2020-01-20"}]}]}],
                 "defendantAttendance":[{"defendantId":"someone-else",
                  "attendanceDays":[{"day":"2020-01-20","attendanceType":"IN_PERSON"}]}]}""")
                    .anyAppearance()).isFalse();
        }
    }

    @Nested
    @DisplayName("custodial results")
    class CustodialResults {

        @Test
        @DisplayName("are found by the prison prompt reference and nothing else")
        void are_found_by_the_prison_prompt() {
            final RegisterVocabulary vocabulary =
                    vocabularyOf(promptedWith("prisonOrganisationName"));
            assertThat(vocabulary.atleastOneCustodialResult()).isTrue();
            assertThat(vocabulary.allNonCustodialResults()).isFalse();
            assertThat(vocabulary.atleastOneNonCustodialResult()).isFalse();
        }

        @Test
        @DisplayName("are absent when every prompt is something else")
        void are_absent_when_every_prompt_is_something_else() {
            final RegisterVocabulary vocabulary = vocabularyOf(promptedWith("durationElement"));
            assertThat(vocabulary.atleastOneCustodialResult()).isFalse();
            assertThat(vocabulary.allNonCustodialResults()).isTrue();
            assertThat(vocabulary.atleastOneNonCustodialResult()).isTrue();
        }

        @Test
        @DisplayName("report a non-custodial result alongside a custodial one")
        void report_a_non_custodial_result_alongside_a_custodial_one() {
            // The one shape that reaches getHasAtleastOneNonCustodialResult at all: the flag starts
            // as the negation of the custodial one and is only recomputed when that is true.
            final RegisterVocabulary vocabulary = vocabularyOf("""
                {"courtCentre":{"name":"Lavender Hill"},
                 "prosecutionCases":[{"id":"case-1",
                  "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
                   "defendantCaseJudicialResults":[{"judicialResultId":"jr-1",
                    "orderedDate":"2020-01-20","judicialResultPrompts":[
                     {"promptReference":"prisonOrganisationName"},
                     {"promptReference":"durationElement"}]}]}]}]}""");

            assertThat(vocabulary.atleastOneCustodialResult()).isTrue();
            assertThat(vocabulary.atleastOneNonCustodialResult()).isTrue();
        }

        @Test
        @DisplayName("treat a result with no prompts at all as wholly non-custodial")
        void treat_a_result_with_no_prompts_as_non_custodial() {
            final RegisterVocabulary vocabulary = vocabularyOf(defendantHeldAt("Prison"));
            assertThat(vocabulary.atleastOneCustodialResult()).isFalse();
            assertThat(vocabulary.allNonCustodialResults()).isTrue();
            assertThat(vocabulary.atleastOneNonCustodialResult()).isTrue();
        }
    }

    @Nested
    @DisplayName("prosecutor, youth and court-centre flags")
    class RemainingFlags {

        @Test
        @DisplayName("marks a CPS prosecutor only when the flag is a real boolean true")
        void marks_a_cps_prosecutor_only_for_boolean_true() {
            // `prosecutionCase.prosecutor.isCps === true` — a strict comparison, so the string
            // "true" is not a CPS prosecution.
            assertThat(vocabularyOf(prosecutedBy("true")).isCpsProsecuted()).isTrue();
            assertThat(vocabularyOf(prosecutedBy("false")).isCpsProsecuted()).isFalse();
            assertThat(vocabularyOf(prosecutedBy("\"true\"")).isCpsProsecuted()).isFalse();
        }

        @Test
        @DisplayName("marks a youth defendant, and never both youth and adult")
        void marks_a_youth_defendant() {
            final RegisterVocabulary youth = vocabularyOf("""
                {"courtCentre":{"name":"Lavender Hill"},
                 "prosecutionCases":[{"id":"case-1",
                  "defendants":[{"id":"def-1","masterDefendantId":"master-1","isYouth":true,
                   "offences":[],"defendantCaseJudicialResults":[
                    {"judicialResultId":"jr-1","orderedDate":"2020-01-20"}]}]}]}""");

            assertThat(youth.youthDefendant()).isTrue();
            assertThat(youth.adultDefendant()).isFalse();
            assertThat(youth.adultOrYouthDefendant()).isTrue();
        }

        @Test
        @DisplayName("marks an adult defendant when the youth flag is absent")
        void marks_an_adult_defendant_when_the_youth_flag_is_absent() {
            // `!!defendantContextBase.isYouthDefendant` — an absent flag is an adult, which is the
            // default the whole register is filtered against later.
            final RegisterVocabulary adult = vocabularyOf(defendantHeldAt("Prison"));
            assertThat(adult.youthDefendant()).isFalse();
            assertThat(adult.adultDefendant()).isTrue();
            assertThat(adult.adultOrYouthDefendant()).isTrue();
        }

        @Test
        @DisplayName("marks a Welsh hearing, and never both Welsh and English")
        void marks_a_welsh_hearing() {
            final RegisterVocabulary welsh = vocabularyOf("""
                {"courtCentre":{"name":"Cardiff","welshCourtCentre":true},
                 "prosecutionCases":[{"id":"case-1",
                  "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
                   "defendantCaseJudicialResults":[
                    {"judicialResultId":"jr-1","orderedDate":"2020-01-20"}]}]}]}""");

            assertThat(welsh.welshCourtHearing()).isTrue();
            assertThat(welsh.englishCourtHearing()).isFalse();
            assertThat(welsh.anyCourtHearing()).isTrue();
        }

        @Test
        @DisplayName("marks an English hearing when the court centre is not Welsh")
        void marks_an_english_hearing() {
            final RegisterVocabulary english = vocabularyOf(defendantHeldAt("Prison"));
            assertThat(english.welshCourtHearing()).isFalse();
            assertThat(english.englishCourtHearing()).isTrue();
            assertThat(english.anyCourtHearing()).isTrue();
        }
    }

    /**
     * C30's vocabulary half. The court register constructs the kernel service with two arguments,
     * so {@code complianceEnforcementList} is undefined and
     * {@code buildApplicableMajorCreditorList} returns {@code []} before it looks at anything
     * ({@code VocabularyService.js:329-334}). Both lists are therefore empty on every register this
     * service will ever produce.
     *
     * <p>Empty is not the same as absent, and the difference is the defect. The matcher's
     * {@code anyMajorCreditor} predicate tests {@code != null} and an empty array is not null, so it
     * passes vacuously; its two siblings require {@code .length > 0} and can never pass at all. The
     * fix makes all three require a non-empty list, and it can only do that if the vocabulary keeps
     * carrying the lists rather than dropping them — which is what these cases pin.
     */
    @Nested
    @DisplayName("major creditors (C30)")
    class MajorCreditors {

        @Test
        @DisplayName("leaves both creditor lists empty, whatever the hearing carries")
        void leaves_both_creditor_lists_empty() {
            assertThat(vocabularyOf(defendantHeldAt("Prison")).prosecutorMajorCreditor()).isEmpty();
            assertThat(vocabularyOf(defendantHeldAt("Prison")).nonProsecutorMajorCreditor())
                    .isEmpty();
        }

        @Test
        @DisplayName("keeps them present and empty rather than absent, so the matcher can tell")
        void keeps_them_present_and_empty_rather_than_absent() {
            final JsonNode serialised = serialise(vocabularyOf(defendantHeldAt("Prison")));

            assertThat(serialised.get("prosecutorMajorCreditor").isArray()).isTrue();
            assertThat(serialised.get("prosecutorMajorCreditor").isEmpty()).isTrue();
            assertThat(serialised.get("nonProsecutorMajorCreditor").isArray()).isTrue();
            assertThat(serialised.get("nonProsecutorMajorCreditor").isEmpty()).isTrue();
        }

        @Test
        @DisplayName("stays empty for a defendant carrying a compliance correlation of their own")
        void stays_empty_for_a_defendant_with_a_compliance_correlation() {
            // The field the creditor walk keys on exists in the payload; what does not exist for
            // this flow is the enforcement list to walk. A port that "completed" the branch would
            // be changing which subscriptions match, with no register entry behind it.
            assertThat(vocabularyOf("""
                {"courtCentre":{"name":"Lavender Hill"},
                 "prosecutionCases":[{"id":"case-1",
                  "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
                   "complianceCorrelationId":"62c08034-791c-4210-9447-db2fdd2a223d",
                   "defendantCaseJudicialResults":[
                    {"judicialResultId":"jr-1","orderedDate":"2020-01-20"}]}]}]}""")
                    .prosecutorMajorCreditor()).isEmpty();
        }
    }

    /**
     * The JUnit twins of the legacy {@code VocabularyService} Jest suite.
     *
     * <p>That suite declares six cases. Four are twinned below; the two that are not are
     * {@code Should set the correct flag for non prosecutor major creditors} and its prosecutor
     * counterpart, both written against the <em>four</em>-argument construction with a creditor map
     * and an enforcement list. This service passes two arguments, which is what makes both lists
     * unconditionally empty — pinned in the {@code MajorCreditors} nest above as the behaviour it
     * is, rather than twinned by building a creditor branch this flow never enters.
     *
     * <p><strong>How the mocked input is reconstructed.</strong> Each of the first three Jest cases
     * pairs a mocked hearing with a <em>separately</em> mocked {@code DefendantContextBase} the
     * hearing would not produce: the mocked hearing's defendants carry no results at all, while the
     * mocked context carries two, both ordered 2020-05-11, and a youth flag. This port has no way to
     * hand a vocabulary a context that did not come from the hearing, so the hearing below carries
     * both halves. Every asserted value is the Jest case's, unaltered; only the assembly differs.
     *
     * <p>The fourth twin runs against a real fixture and in this service's exact configuration —
     * {@code new DefendantContextService(hearingResulted, true, false)} — which is why it needs the
     * repaired copy: the fixture's application is brought by a master defendant, so under C22 the
     * defendant whose custody it asserts is no longer gathered at all.
     */
    @Nested
    @DisplayName("VocabularyService — legacy Jest twins")
    class LegacyJestTwins {

        @Test
        @DisplayName("Should set the correct police custody")
        void should_set_the_correct_police_custody() {
            assertMockedHearing(vocabularyOf(mockedHearing("Police Station", "false")), true, false);
        }

        @Test
        @DisplayName("Should set the correct prison custody")
        void should_set_the_correct_prison_custody() {
            assertMockedHearing(vocabularyOf(mockedHearing("Prison", "false")), false, true);
        }

        @Test
        @DisplayName("Should set the correct cps flag")
        void should_set_the_correct_cps_flag() {
            final RegisterVocabulary vocabulary = vocabularyOf(mockedHearing("Prison", "true"));

            assertThat(vocabulary.welshCourtHearing()).isTrue();
            assertThat(vocabulary.englishCourtHearing()).isFalse();
            assertThat(vocabulary.anyCourtHearing()).isTrue();
            assertThat(vocabulary.isCpsProsecuted()).isTrue();
        }

        @Test
        @DisplayName("Should set custody location info with both application and prosecution case "
                + "in the hearing")
        void should_set_custody_location_info_with_both_application_and_prosecution_case() {
            final RegisterVocabulary vocabulary = vocabularyOf(
                    "216cb569-92a8-4c4b-8742-ad3552f62bcb",
                    LegacyFixtures.read("hearing-resulted-with-application-and-prosecution-case-"
                            + "prosecuting-applicant.json"));

            assertThat(vocabulary.custodyLocationIsPolice()).isFalse();
            assertThat(vocabulary.custodyLocationIsPrison()).isTrue();
        }

        @Test
        @DisplayName("does not give the case defendant the application subject's custody")
        void does_not_give_the_case_defendant_the_application_subjects_custody() {
            // The control the Jest case does not have: the same hearing gathers two defendants and
            // only one of them is held. Asserting the held one alone would pass on a port that read
            // custody from any application in the hearing.
            final RegisterVocabulary vocabulary = vocabularyOf(
                    "ddba1595-31f1-41f5-95d5-622323930344",
                    LegacyFixtures.read("hearing-resulted-with-application-and-prosecution-case-"
                            + "prosecuting-applicant.json"));

            assertThat(vocabulary.inCustody()).isFalse();
        }

        /**
         * The sixteen flags the two custody cases assert, which differ only in where the defendant
         * is held.
         *
         * @param vocabulary the computed vocabulary
         * @param police     the expected {@code custodyLocationIsPolice}
         * @param prison     the expected {@code custodyLocationIsPrison}
         */
        private void assertMockedHearing(
                final RegisterVocabulary vocabulary, final boolean police, final boolean prison) {

            assertThat(vocabulary.custodyLocationIsPolice()).isEqualTo(police);
            assertThat(vocabulary.custodyLocationIsPrison()).isEqualTo(prison);
            assertThat(vocabulary.atleastOneCustodialResult()).isFalse();
            assertThat(vocabulary.appearedInPerson()).isTrue();
            assertThat(vocabulary.appearedByVideoLink()).isFalse();
            assertThat(vocabulary.allNonCustodialResults()).isTrue();
            assertThat(vocabulary.atleastOneNonCustodialResult()).isTrue();
            assertThat(vocabulary.anyAppearance()).isTrue();
            assertThat(vocabulary.inCustody()).isTrue();
            assertThat(vocabulary.youthDefendant()).isTrue();
            assertThat(vocabulary.adultDefendant()).isFalse();
            assertThat(vocabulary.adultOrYouthDefendant()).isTrue();
            assertThat(vocabulary.welshCourtHearing()).isTrue();
            assertThat(vocabulary.englishCourtHearing()).isFalse();
            assertThat(vocabulary.anyCourtHearing()).isTrue();
            assertThat(vocabulary.isCpsProsecuted()).isFalse();
        }

        /**
         * The Jest suite's {@code getMockedHearingResulted}, carrying the results and youth flag its
         * separately-mocked {@code DefendantContextBase} supplies — see the class comment.
         *
         * @param custody the custody location, as {@code LocationTypeEnum} spells it
         * @param isCps   the raw JSON value of the second case's {@code prosecutor.isCps}
         * @return the hearing as JSON text
         */
        private String mockedHearing(final String custody, final String isCps) {
            return """
                {"prosecutionCases":[
                  {"id":"c10e3b71-6a6d-45ef-9b62-34df4d54971a",
                   "defendants":[{"id":"6647df67-a065-4d07-90ba-a8daa064ecc4",
                    "masterDefendantId":"6647df67-a065-4d07-90ba-a8daa064ecc4","isYouth":true,
                    "personDefendant":{"custodialEstablishment":{"custody":"%s"}},
                    "offences":[],
                    "defendantCaseJudicialResults":[
                     {"judicialResultId":"jr-1","orderedDate":"2020-05-11"},
                     {"judicialResultId":"jr-2","orderedDate":"2020-05-11"}]}]},
                  {"id":"07e0a2b1-6dfe-4c5f-9f6f-9d5f2d3d7a4c",
                   "prosecutor":{"prosecutorId":"cf73207f-3ced-488a-82a0-3fba79c2ce81",
                    "prosecutorCode":"TFL","prosecutorName":"TFL12348","isCps":%s},
                   "defendants":[{"id":"6647df67-a065-4d07-90ba-a8daa064ecc4",
                    "masterDefendantId":"6647df67-a065-4d07-90ba-a8daa064ecc4","isYouth":true,
                    "personDefendant":{"custodialEstablishment":{"custody":"%s"}},
                    "offences":[],"defendantCaseJudicialResults":[]}]}],
                 "defendantAttendance":[{
                  "defendantId":"6647df67-a065-4d07-90ba-a8daa064ecc4",
                  "attendanceDays":[{"attendanceType":"IN_PERSON","day":"2020-05-11"}]}],
                 "courtCentre":{"id":"6647df67-a065-4d07-90ba-a8daa064ecd9",
                  "name":"Lavender Hill","welshCourtCentre":true}}"""
                    .formatted(custody, isCps, custody);
        }
    }

    /**
     * A hearing whose single defendant is held at the given location.
     *
     * @param custody the custody location
     * @return the hearing as JSON text
     */
    private static String defendantHeldAt(final String custody) {
        return """
            {"courtCentre":{"name":"Lavender Hill"},
             "prosecutionCases":[{"id":"case-1",
              "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
               "personDefendant":{"custodialEstablishment":{"custody":"%s"}},
               "defendantCaseJudicialResults":[
                {"judicialResultId":"jr-1","orderedDate":"2020-01-20"}]}]}]}"""
                .formatted(custody);
    }

    /**
     * A hearing whose single defendant attended in the given way, on the given day.
     *
     * @param attendanceType the attendance type
     * @param day            the day attended
     * @return the hearing as JSON text
     */
    private static String attended(final String attendanceType, final String day) {
        return """
            {"courtCentre":{"name":"Lavender Hill"},
             "prosecutionCases":[{"id":"case-1",
              "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
               "defendantCaseJudicialResults":[
                {"judicialResultId":"jr-1","orderedDate":"2020-01-20"}]}]}],
             "defendantAttendance":[{"defendantId":"def-1",
              "attendanceDays":[{"day":"%s","attendanceType":"%s"}]}]}"""
                .formatted(day, attendanceType);
    }

    /**
     * A hearing whose single result carries one prompt with the given reference.
     *
     * @param promptReference the prompt reference
     * @return the hearing as JSON text
     */
    private static String promptedWith(final String promptReference) {
        return """
            {"courtCentre":{"name":"Lavender Hill"},
             "prosecutionCases":[{"id":"case-1",
              "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
               "defendantCaseJudicialResults":[{"judicialResultId":"jr-1",
                "orderedDate":"2020-01-20","judicialResultPrompts":[
                 {"promptReference":"%s"}]}]}]}]}"""
                .formatted(promptReference);
    }

    /**
     * A hearing whose prosecutor carries the given {@code isCps} value verbatim.
     *
     * @param isCps the raw JSON value to place in {@code isCps}
     * @return the hearing as JSON text
     */
    private static String prosecutedBy(final String isCps) {
        return """
            {"courtCentre":{"name":"Lavender Hill"},
             "prosecutionCases":[{"id":"case-1",
              "prosecutor":{"isCps":%s},
              "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
               "defendantCaseJudicialResults":[
                {"judicialResultId":"jr-1","orderedDate":"2020-01-20"}]}]}]}"""
                .formatted(isCps);
    }

    /**
     * Gathers a hearing's single defendant and computes their vocabulary.
     *
     * @param hearing the hearing as JSON text
     * @return the vocabulary
     */
    private RegisterVocabulary vocabularyOf(final String hearing) {
        final JsonNode tree = mapper.readTree(hearing);
        final List<DefendantContext> gathered = new DefendantContextBuilder(tree, dates).build();
        assertThat(gathered).as("these cases are written around a single defendant").hasSize(1);
        return new VocabularyBuilder(tree).build(gathered.get(0));
    }

    /**
     * Computes the vocabulary of one named defendant in a hearing that gathers several.
     *
     * @param masterDefendantId the defendant to compute for
     * @param hearing           the hearing as JSON text
     * @return that defendant's vocabulary
     */
    private RegisterVocabulary vocabularyOf(
            final String masterDefendantId, final String hearing) {

        return vocabularyOf(masterDefendantId, mapper.readTree(hearing));
    }

    /**
     * Computes the vocabulary of one named defendant in an already-parsed hearing.
     *
     * @param masterDefendantId the defendant to compute for
     * @param hearing           the hearing
     * @return that defendant's vocabulary
     */
    private RegisterVocabulary vocabularyOf(
            final String masterDefendantId, final JsonNode hearing) {

        final DefendantContext defendant =
                new DefendantContextBuilder(hearing, dates).build().stream()
                        .filter(candidate ->
                                masterDefendantId.equals(candidate.masterDefendantId()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                                "the hearing gathered no defendant " + masterDefendantId));
        return new VocabularyBuilder(hearing).build(defendant);
    }

    /**
     * The vocabulary as it reaches reference data.
     *
     * @param vocabulary the computed vocabulary
     * @return its serialised form
     */
    private JsonNode serialise(final RegisterVocabulary vocabulary) {
        return mapper.readTree(mapper.writeValueAsString(vocabulary));
    }

    /**
     * The names a vocabulary is matched by, in the order it writes them.
     *
     * @param vocabulary the computed vocabulary
     * @return the key names
     */
    private List<String> keysOf(final RegisterVocabulary vocabulary) {
        return List.copyOf(serialise(vocabulary).propertyNames());
    }
}
