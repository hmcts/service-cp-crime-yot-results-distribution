package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.lang.reflect.Method;
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
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterCaseOrApplication;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterCounsel;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterOffence;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterResult;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationAnomaly;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;
import uk.gov.hmcts.cp.yotresultsdistribution.support.LegacyFixtures;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ModelObjects;

/**
 * What a child was in court about: the cases and applications, in that order.
 *
 * <p>Twins the seven cases of
 * {@code $DF/…/Mappers/ProsecutionCaseOrApplication/test/ProsecutionCaseOrApplicationMapper.test.js}
 * — the largest mapper suite in the corpus and the one carrying the most defects.
 *
 * <p><strong>The construction is repaired first.</strong> Four of the seven Jest cases call
 * {@code new ProsecutionCaseOrApplicationMapper(fakeRegisterDefendant, fakeHearingResultedJson)}
 * against a three-argument constructor {@code (context, registerDefendant, hearingJson)}, so the
 * mapper under test is built with the defendant as its logging context, the hearing as its
 * defendant, and nothing at all as its hearing. It happens not to matter because those four then
 * call {@code getCourtApplicationOffences} directly, a helper that reads only its argument — which
 * is exactly the problem: the four cases about how an application's offences are gathered never
 * touch the object that gathers them. Here they are driven through {@code map} with a register
 * defendant that names the application, so the gathering is observed where it actually happens.
 *
 * <p><strong>Three catalogued defects, and the asymmetry between two of them.</strong> The
 * prosecution-case path was guarded by SNI-9005 (commit {@code 0781bbc2}): a case id the hearing
 * does not carry is warned about and skipped, and PC3 pins it. The application path, four lines
 * below, was left as {@code this.hearingJson.courtApplications.find(...)} with no array guard and no
 * result guard, so a hearing with no applications array, or an application id nothing matches,
 * throws a {@code TypeError} that {@code OutboundCourtRegister/index.js:62-64} swallows — and every
 * child on the hearing loses their register over one dangling reference (C20). {@code getASN}
 * filters on {@code d.personDefendant.arrestSummonsNumber} with no guard on {@code personDefendant},
 * so a legal-entity record carrying this defendant's own master id throws the same way (C21).
 *
 * <p>Both fixes make the skip <em>visible</em> rather than fatal: a WARN line naming the id and a
 * count through the anomaly recorder, which is what reaches
 * {@code processed_output.anomaly_summary} as {@code unresolvable-application:1} and the anomaly
 * metric. The case path keeps its SNI-9005 shape exactly — warn and skip, uncounted — because that
 * guard predates the register and C20 names only the application path; the asymmetry in the
 * <em>counting</em> is deliberate and is asserted, so a later change to it has to be a decision.
 *
 * <p><strong>C22 is asserted here as the mapper's own gate.</strong> The comment at {@code :64} says
 * "Check if applicant is prosecutingAuthority and subject is masterDefendant" and the code below it
 * checks only the subject. The register's context builder already applies both halves, so an
 * ineligible application never reaches this mapper in the assembled pipeline; the mapper is held to
 * the same rule anyway, because it is the rule the code claims and because the two gates are read by
 * different people.
 *
 * <p><strong>Two dead methods are not reproduced</strong> (C26): {@code getApplicationReference}
 * ({@code :102}) and {@code getRespondentCounsels} ({@code :162}) are called from nowhere in the
 * legacy repo. Asserted both ways — the methods do not exist, and a hearing carrying
 * {@code respondentCounsels} puts no counsel on an application.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> rows C20,
 *      C21, C22, C26
 */
@DisplayName("ProsecutionCaseOrApplicationMapper")
class ProsecutionCaseOrApplicationMapperTest {

    /** The one master defendant every fixture and every constructed hearing below is about. */
    private static final String MASTER = "6647df67-a065-4d07-90ba-a8daa064ecc4";

    private static final String CASE_ID = "c10e3b71-6a6d-45ef-9b62-34df4d54971a";

    private static final String APPLICATION_ID = "6984d5b6-5c5d-472b-9ead-dff7a49c9600";

    private static final String APPLICANT_ID = "3b0ec9c2-e85e-4fe8-a3c2-1271420f4c0a";

    private static final String CASES_AND_APPLICATIONS =
            "mappers/prosecutioncaseorapplication/"
                    + "hearing-resulted-with-matching-prosecutionCases-and-courtApplications.json";

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    /** Every anomaly the mapper counted, in the order it counted them. */
    private final List<TransformationAnomaly> anomalies = new ArrayList<>();

    @Nested
    @DisplayName("Should return correct values when matching cases are there only in "
            + "prosecutionCases — PC1")
    class CasesOnly {

        @Test
        @DisplayName("one case, and the application whose subject is nobody is not one of them")
        void one_case_and_no_application() {
            // The fixture's single application has no `subject.masterDefendant` at all, so it is not
            // this defendant's — the legacy's own gate, kept.
            assertThat(legacyCases()).hasSize(1);
        }

        @Test
        @DisplayName("the reference falls back to the prosecuting authority's own")
        void the_reference_falls_back_to_the_authority_reference() {
            // `getCaseReference` is `caseURN || prosecutionAuthorityReference` and this identifier
            // carries no URN.
            assertThat(legacyCases().get(0).caseOrApplicationReference()).isEqualTo("TFL4359536");
        }

        @Test
        @DisplayName("the arrest summons number comes off the defendant's own case record")
        void the_arrest_summons_number_is_the_defendants_own() {
            assertThat(legacyCases().get(0).arrestSummonsNumber()).isEqualTo("TFL0");
        }

        @Test
        @DisplayName("the prosecution counsel named on this case is carried, with their status")
        void the_prosecution_counsel_is_carried() {
            assertThat(legacyCases().get(0).prosecutionCounsels())
                    .extracting(CourtRegisterCounsel::name, CourtRegisterCounsel::status)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple(
                                    "Prosecution Kieran Counsel", "Leading Counsel"));
        }

        @Test
        @DisplayName("the case carries the results recorded at case level against it")
        void the_case_carries_its_case_level_results() {
            assertThat(legacyCases().get(0).results())
                    .extracting(CourtRegisterResult::resultText, CourtRegisterResult::cjsResultCode)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple("Case", "cjsCode - C level"));
        }

        @Test
        @DisplayName("the case carries the offences of this defendant's own record")
        void the_case_carries_this_defendants_offences() {
            // Unasserted by the legacy case, and the reason the mapper filters case defendants by
            // master id at all: a co-defendant's offences must not reach this child's register.
            assertThat(legacyCases().get(0).offences())
                    .extracting(CourtRegisterOffence::offenceCode)
                    .containsExactly("PS90010");
        }
    }

    @Nested
    @DisplayName("Should return correct values when matching cases are there in prosecutionCases "
            + "and courtApplications — PC2")
    class CasesAndApplications {

        @Test
        @DisplayName("the case comes first and the application second")
        void the_case_comes_first_and_the_application_second() {
            // Concatenation order, and the register prints them in it.
            assertThat(casesAndApplications()).hasSize(2);
            assertThat(casesAndApplications().get(0).caseOrApplicationReference())
                    .isEqualTo("TFL4359536");
            assertThat(casesAndApplications().get(1).caseOrApplicationReference())
                    .isEqualTo("APR35890458");
        }

        @Test
        @DisplayName("the application's arrest summons number comes off its subject")
        void the_applications_arrest_summons_number_comes_off_its_subject() {
            assertThat(casesAndApplications().get(1).arrestSummonsNumber()).isEqualTo("TFL1");
        }

        @Test
        @DisplayName("the applicant's counsel is the application's prosecution counsel")
        void the_applicants_counsel_is_the_applications_counsel() {
            assertThat(casesAndApplications().get(1).prosecutionCounsels())
                    .extracting(CourtRegisterCounsel::name, CourtRegisterCounsel::status)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple(
                                    "David Kieran Walsh", "Leading QC"));
        }

        @Test
        @DisplayName("the application carries the results recorded at application level against it")
        void the_application_carries_its_application_level_results() {
            assertThat(casesAndApplications().get(1).results())
                    .extracting(CourtRegisterResult::resultText, CourtRegisterResult::cjsResultCode)
                    .containsExactly(org.assertj.core.api.Assertions.tuple(
                            "Application", "cjsCode - A level"));
        }

        @Test
        @DisplayName("the application type is carried")
        void the_application_type_is_carried() {
            assertThat(casesAndApplications().get(1).applicationType()).isEqualTo("sample type");
        }

        @Test
        @DisplayName("the application's own id is carried, which no legacy model declares")
        void the_applications_own_id_is_carried() {
            // `:82` writes `courtApplicationId` onto an object whose class does not declare it
            // (C26); nothing asserts it and nothing downstream can rely on it. The record declares
            // it, so it is asserted.
            assertThat(casesAndApplications().get(1).courtApplicationId()).isEqualTo(APPLICATION_ID);
        }

        @Test
        @DisplayName("a case record with a person but no summons number contributes none")
        void a_case_record_with_no_summons_number_contributes_none() {
            // This fixture's case defendant has `personDefendant` and no `arrestSummonsNumber`, so
            // the filter empties and `getASN` falls off its end.
            assertThat(casesAndApplications().get(0).arrestSummonsNumber()).isNull();
        }

        @Test
        @DisplayName("a case- and an application-level result do not reach each other")
        void case_and_application_results_do_not_cross() {
            assertThat(casesAndApplications().get(0).results())
                    .extracting(CourtRegisterResult::cjsResultCode)
                    .containsExactly("cjsCode - C level");
        }
    }

    @Nested
    @DisplayName("Should skip prosecution case and log a warning when caseId is not found — PC3")
    class UnresolvableCase {

        @Test
        @DisplayName("the case is skipped and nothing takes its place")
        void the_case_is_skipped() {
            assertThat(mapWithoutMatchingCases()).isEmpty();
        }

        /**
         * The skip is said out loud, and said in bounded terms.
         *
         * <p>The SNI-9005 guard's own message quotes the case id, and this port does not — the same
         * sweep that took the application id out of {@code DefendantContextBuilder}'s parity
         * warning. A prosecution case id is not in Principle VII's permitted correlation set
         * ({@code requestId}, {@code hearingId}, {@code hearingDay}, {@code source}, the court
         * centre's id or OU code, counts, timings), and no register row authorises it here: C20
         * authorises an identifier in the <em>application</em> warning below, which is a different
         * line about a different reference. A row authorises one warning, not a class of them.
         *
         * <p>The line still says which guard fired and that a case was skipped, which is what an
         * operator acts on; {@code requestId} and {@code hearingId} reach it through the MDC, from
         * the run-aware layer that has them.
         */
        @Test
        @DisplayName("a warning says a case was skipped, and never which one")
        void a_warning_says_a_case_was_skipped_and_never_which_one() {
            try (CapturedLog log = CapturedLog.capturing(ProsecutionCaseOrApplicationMapper.class)) {
                mapWithoutMatchingCases();

                assertThat(warnings(log))
                        .singleElement()
                        .satisfies(message -> assertThat(message)
                                .contains("Prosecution case not found")
                                .doesNotContain(CASE_ID));
            }
        }

        @Test
        @DisplayName("the skip is not counted, which is the SNI-9005 guard exactly as it stands")
        void the_skip_is_not_counted() {
            // Deliberate asymmetry with the application path below. SNI-9005's guard predates the
            // court register and C20 names only the application it left unguarded; counting this one
            // too would be a behaviour change nothing on the register authorises. Asserted so that
            // changing it later has to be a decision rather than a drift.
            mapWithoutMatchingCases();

            assertThat(anomalies).isEmpty();
        }
    }

    @Nested
    @DisplayName("where an application's offences are gathered from — PC4 to PC7")
    class ApplicationOffences {

        @Test
        @DisplayName("from the application's own cases — PC4")
        void from_the_applications_own_cases() {
            final ObjectNode application = application();
            application.set("courtApplicationCases", ModelObjects.array(
                    ModelObjects.courtApplicationCase(offence("oc1"), "urn", "ref")));

            assertThat(offenceCodesOf(application)).containsExactly("oc1");
        }

        @Test
        @DisplayName("from the application's court order — PC5")
        void from_the_applications_court_order() {
            final ObjectNode application = application();
            application.set("courtOrder", ModelObjects.courtOrder(
                    ModelObjects.courtOrderOffence(offence("oc1"), "urn", "ref")));

            assertThat(offenceCodesOf(application)).containsExactly("oc1");
        }

        @Test
        @DisplayName("from both, cases first and court order second — PC6")
        void from_both_cases_first() {
            // Order-sensitive, and the comparator that guards this port is order-sensitive too.
            final ObjectNode application = application();
            application.set("courtApplicationCases", ModelObjects.array(
                    ModelObjects.courtApplicationCase(offence("oc_cc"), "urn", "ref")));
            application.set("courtOrder", ModelObjects.courtOrder(
                    ModelObjects.courtOrderOffence(offence("oc_co"), "urn", "ref")));

            assertThat(offenceCodesOf(application)).containsExactly("oc_cc", "oc_co");
        }

        @Test
        @DisplayName("from the court order alone when a case carries no offences — PC7")
        void from_the_court_order_when_a_case_has_no_offences() {
            final ObjectNode applicationCase =
                    ModelObjects.courtApplicationCase(offence("oc_cc"), "urn", "ref");
            applicationCase.remove("offences");

            final ObjectNode application = application();
            application.set("courtApplicationCases", ModelObjects.array(applicationCase));
            application.set("courtOrder", ModelObjects.courtOrder(
                    ModelObjects.courtOrderOffence(offence("oc_co"), "urn", "ref")));

            assertThat(offenceCodesOf(application)).containsExactly("oc_co");
        }

        @Test
        @DisplayName("an application with neither carries the empty list the legacy sends, which "
                + "the pre-send validator then refuses (C29)")
        void an_application_with_neither_carries_an_empty_offence_list() {
            // `offences.map(...)` over an empty array is `[]`, and `[]` is what the legacy posts.
            // The contract's `minItems: 1` refuses it — loudly here, silently there (C29 with C1).
            assertThat(mapApplication(application()).get(0).offences()).isEmpty();
        }
    }

    @Nested
    @DisplayName("an application the hearing cannot resolve (C20)")
    class UnresolvableApplication {

        @Test
        @DisplayName("is skipped rather than fatal when the hearing carries no applications at all")
        void a_hearing_with_no_applications_is_survivable() {
            // Fails against the legacy, which reads `.find` off an absent array and throws.
            final List<CourtRegisterCaseOrApplication> mapped =
                    map(defendantNaming(List.of(), List.of(APPLICATION_ID)), hearing());

            assertThat(mapped).isEmpty();
        }

        @Test
        @DisplayName("is skipped when the id matches no application on the hearing")
        void an_unmatched_application_id_is_skipped() {
            final ObjectNode hearing = hearing();
            hearing.set("courtApplications", ModelObjects.array(application()));

            assertThat(map(defendantNaming(List.of(), List.of("no-such-application")), hearing))
                    .isEmpty();
        }

        @Test
        @DisplayName("is counted once, under the bounded reason the anomaly summary carries")
        void an_unresolvable_application_is_skipped_and_reported() {
            final List<CourtRegisterCaseOrApplication> mapped =
                    map(defendantNaming(List.of(), List.of(APPLICATION_ID)), hearing());

            assertThat(mapped).isEmpty();
            assertThat(anomalies)
                    .containsExactly(TransformationAnomaly.UNRESOLVABLE_APPLICATION);
            assertThat(TransformationAnomaly.UNRESOLVABLE_APPLICATION.value())
                    .isEqualTo("unresolvable-application");
        }

        @Test
        @DisplayName("is counted once per dangling reference, not once per document")
        void each_dangling_reference_is_counted() {
            map(defendantNaming(List.of(), List.of("no-such-one", "no-such-two")), hearing());

            assertThat(anomalies).containsExactly(
                    TransformationAnomaly.UNRESOLVABLE_APPLICATION,
                    TransformationAnomaly.UNRESOLVABLE_APPLICATION);
        }

        @Test
        @DisplayName("a warning names the application id that could not be found")
        void a_warning_names_the_application_id() {
            try (CapturedLog log = CapturedLog.capturing(ProsecutionCaseOrApplicationMapper.class)) {
                map(defendantNaming(List.of(), List.of(APPLICATION_ID)), hearing());

                assertThat(warnings(log)).singleElement()
                        .satisfies(message -> assertThat(message).contains(APPLICATION_ID));
            }
        }

        @Test
        @DisplayName("leaves the cases that did resolve on the register")
        void the_cases_that_resolved_survive() {
            // The whole point of the fix: one bad reference costs the reference, not the child's
            // entry and not the other children on the hearing.
            final JsonNode hearing = legacyHearing();
            final List<CourtRegisterCaseOrApplication> mapped =
                    map(defendantNaming(List.of(CASE_ID), List.of("no-such-application")), hearing);

            assertThat(mapped).hasSize(1);
            assertThat(mapped.get(0).caseOrApplicationReference()).isEqualTo("TFL4359536");
            assertThat(anomalies).containsExactly(TransformationAnomaly.UNRESOLVABLE_APPLICATION);
        }
    }

    @Nested
    @DisplayName("a case record with no person on it (C21)")
    class LegalEntityCaseRecord {

        @Test
        @DisplayName("contributes no arrest summons number, and does not end the register")
        void asn_ignores_records_without_person_defendant() {
            // Fails against the legacy, where `d.personDefendant.arrestSummonsNumber` on a record
            // with no person throws. Only reachable with a record carrying *this* defendant's own
            // master id — the `:48` filter excludes genuine co-defendants first — so it is built
            // deliberately.
            final JsonNode hearing = hearingWhoseCaseHas(personlessRecord());

            assertThat(map(defendantNaming(List.of(CASE_ID), List.of()), hearing).get(0)
                    .arrestSummonsNumber()).isNull();
        }

        @Test
        @DisplayName("is passed over in favour of a record that does carry a person")
        void is_passed_over_for_a_record_that_carries_one() {
            final ObjectNode withPerson = mapper.createObjectNode();
            withPerson.put("id", "second-record");
            withPerson.put("masterDefendantId", MASTER);
            withPerson.set("personDefendant",
                    mapper.createObjectNode().put("arrestSummonsNumber", "TFL9"));

            final JsonNode hearing = hearingWhoseCaseHas(personlessRecord(), withPerson);

            assertThat(map(defendantNaming(List.of(CASE_ID), List.of()), hearing).get(0)
                    .arrestSummonsNumber()).isEqualTo("TFL9");
        }
    }

    @Nested
    @DisplayName("an application whose applicant prosecutes nothing (C22)")
    class NonProsecutingApplicant {

        @Test
        @DisplayName("is not this register's business, even where its subject is this defendant")
        void is_not_this_registers_business() {
            // The mapper's own comment says both halves; its code checks only the subject. The
            // context builder applies the same rule upstream, so in the assembled pipeline this
            // application never reaches here — the gate is asserted at both ends because it is read
            // at both ends.
            final ObjectNode hearing = (ObjectNode) legacyHearing(CASES_AND_APPLICATIONS);
            ((ObjectNode) hearing.get("courtApplications").get(0).get("applicant"))
                    .remove("prosecutingAuthority");

            assertThat(map(registerDefendant(), hearing))
                    .hasSize(1)
                    .allSatisfy(entry -> assertThat(entry.applicationType()).isNull());
        }

        @Test
        @DisplayName("is an ineligible application, not an unresolvable one")
        void is_ineligible_rather_than_unresolvable() {
            final ObjectNode hearing = (ObjectNode) legacyHearing(CASES_AND_APPLICATIONS);
            ((ObjectNode) hearing.get("courtApplications").get(0).get("applicant"))
                    .remove("prosecutingAuthority");

            map(registerDefendant(), hearing);

            assertThat(anomalies).isEmpty();
        }

        @Test
        @DisplayName("an application whose subject is another defendant is left alone too")
        void an_application_for_another_defendant_is_left_alone() {
            final ObjectNode hearing = (ObjectNode) legacyHearing(CASES_AND_APPLICATIONS);
            ((ObjectNode) hearing.get("courtApplications").get(0)
                    .get("subject").get("masterDefendant"))
                    .put("masterDefendantId", "b21c7e94-3f5a-4d18-9c60-7ea4d3f61b28");

            assertThat(map(registerDefendant(), hearing)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("the two methods nothing calls (C26)")
    class DeadMethods {

        @Test
        @DisplayName("are not reproduced")
        void are_not_reproduced() {
            assertThat(ProsecutionCaseOrApplicationMapper.class.getDeclaredMethods())
                    .extracting(Method::getName)
                    .doesNotContain("getApplicationReference", "getRespondentCounsels");
        }

        @Test
        @DisplayName("and a hearing's respondent counsels reach no application")
        void respondent_counsels_reach_no_application() {
            final ObjectNode counsel = mapper.createObjectNode();
            counsel.put("firstName", "Respondent");
            counsel.put("lastName", "Counsel");
            counsel.set("respondents", ModelObjects.array(mapper.getNodeFactory()
                    .textNode(APPLICANT_ID)));

            final ObjectNode hearing = hearing();
            hearing.set("courtApplications", ModelObjects.array(application()));
            hearing.set("respondentCounsels", ModelObjects.array(counsel));

            assertThat(map(defendantNaming(List.of(), List.of("app-1")), hearing).get(0)
                    .prosecutionCounsels()).isNull();
        }
    }

    /**
     * The legacy fixture's cases, mapped exactly as PC1 maps them.
     *
     * @return the mapped cases and applications
     */
    private List<CourtRegisterCaseOrApplication> legacyCases() {
        return map(registerDefendant(), legacyHearing());
    }

    /**
     * The second legacy fixture's case and application, as PC2 maps them.
     *
     * @return the mapped cases and applications
     */
    private List<CourtRegisterCaseOrApplication> casesAndApplications() {
        return map(registerDefendant(), legacyHearing(CASES_AND_APPLICATIONS));
    }

    /**
     * PC3's mutation: every prosecution case renamed, so the register defendant's case id matches
     * none of them.
     *
     * @return the mapped cases and applications
     */
    private List<CourtRegisterCaseOrApplication> mapWithoutMatchingCases() {
        final ObjectNode hearing = (ObjectNode) legacyHearing();
        for (final JsonNode prosecutionCase : hearing.get("prosecutionCases")) {
            ((ObjectNode) prosecutionCase).put("id", "non-matching-id");
        }
        return map(registerDefendant(), hearing);
    }

    /**
     * The offence codes an application contributes, gathered through the mapper rather than through
     * the helper the legacy cases reach past it into.
     *
     * @param application the court application
     * @return the offence codes, in order
     */
    private List<String> offenceCodesOf(final ObjectNode application) {
        return mapApplication(application).get(0).offences().stream()
                .map(CourtRegisterOffence::offenceCode)
                .toList();
    }

    /**
     * Maps one constructed application, on a hearing that carries nothing else.
     *
     * @param application the court application
     * @return the mapped cases and applications
     */
    private List<CourtRegisterCaseOrApplication> mapApplication(final ObjectNode application) {
        final ObjectNode hearing = hearing();
        hearing.set("courtApplications", ModelObjects.array(application));
        return map(defendantNaming(List.of(), List.of("app-1")), hearing);
    }

    /**
     * Runs the mapper, collecting whatever it counts.
     *
     * @param registerDefendant the gathered defendant
     * @param hearing           the hearing payload
     * @return the mapped cases and applications
     */
    private List<CourtRegisterCaseOrApplication> map(
            final RegisterDefendant registerDefendant, final JsonNode hearing) {
        return new ProsecutionCaseOrApplicationMapper(anomalies::add)
                .map(registerDefendant, hearing);
    }

    /**
     * The rebuilt defendant context: the legacy fixture's case and application, a case-level and an
     * application-level result, and the eighteen-key vocabulary.
     *
     * @return the gathered defendant
     */
    private RegisterDefendant registerDefendant() {
        return mapper.treeToValue(
                LegacyFixtures.readRebuilt(
                        "mappers/prosecutioncaseorapplication/defendant-context-base.json"),
                RegisterDefendant.class);
    }

    /**
     * A gathered defendant naming exactly the cases and applications given, and carrying no results.
     *
     * @param cases        the prosecution case ids
     * @param applications the court application ids
     * @return the gathered defendant
     */
    private RegisterDefendant defendantNaming(
            final List<String> cases, final List<String> applications) {
        return new RegisterDefendant(
                List.of(MASTER), List.of(), cases, applications, MASTER, true, null, null);
    }

    private JsonNode legacyHearing() {
        return legacyHearing("mappers/prosecutioncaseorapplication/hearing-resulted.json");
    }

    private JsonNode legacyHearing(final String path) {
        return LegacyFixtures.readCourtRegister(path);
    }

    /** The smallest hearing this mapper reads: an id, and nothing it is obliged to carry. */
    private ObjectNode hearing() {
        final ObjectNode hearing = mapper.createObjectNode();
        hearing.put("id", "1828f356-f746-4f2d-932b-79ef2df95c80");
        return hearing;
    }

    /**
     * A hearing whose one prosecution case carries exactly the defendant records given.
     *
     * @param records the case-defendant records, in payload order
     * @return the hearing payload
     */
    private JsonNode hearingWhoseCaseHas(final JsonNode... records) {
        final ArrayNode defendants = mapper.createArrayNode();
        for (final JsonNode record : records) {
            defendants.add(record);
        }

        final ObjectNode prosecutionCase = mapper.createObjectNode();
        prosecutionCase.put("id", CASE_ID);
        prosecutionCase.set("prosecutionCaseIdentifier",
                mapper.createObjectNode().put("caseURN", "URN-1"));
        prosecutionCase.set("defendants", defendants);

        final ObjectNode hearing = hearing();
        hearing.set("prosecutionCases", ModelObjects.array(prosecutionCase));
        return hearing;
    }

    /**
     * A case-defendant record carrying this defendant's own master id and no {@code personDefendant}
     * — the legal-entity shape, and the only construction that reaches C21.
     *
     * @return the record
     */
    private ObjectNode personlessRecord() {
        final ObjectNode record = mapper.createObjectNode();
        record.put("id", "legal-entity-record");
        record.put("masterDefendantId", MASTER);
        record.set("legalEntityDefendant",
                mapper.createObjectNode().put("organisationName", "Acme Holdings Ltd"));
        return record;
    }

    /**
     * A court application for this defendant, brought by a prosecuting authority.
     *
     * @return the application
     */
    private ObjectNode application() {
        final ObjectNode applicant = ModelObjects.applicant(
                ModelObjects.prosecutingAuthority(
                        "bdc190e7-c939-37ca-be4b-9f615d6ef40e", "DERPF"));
        applicant.put("id", APPLICANT_ID);

        final ObjectNode application = ModelObjects.courtApplication(
                ModelObjects.subject(ModelObjects.masterDefendant(MASTER)), applicant);
        application.put("id", "app-1");
        application.put("applicationReference", "APR-1");
        application.set("type", mapper.createObjectNode().put("type", "sample type"));
        return application;
    }

    /**
     * One offence with a code and nothing else that matters here.
     *
     * @param offenceCode the CJS code
     * @return the offence
     */
    private ObjectNode offence(final String offenceCode) {
        return ModelObjects.offence(offenceCode, 1, "An offence");
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
