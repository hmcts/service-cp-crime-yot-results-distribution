package uk.gov.hmcts.cp.yotresultsdistribution.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.support.DifferentialCorpus.RecordedCase;
import uk.gov.hmcts.cp.yotresultsdistribution.support.RegisteredDefectFixes.Claim;
import uk.gov.hmcts.cp.yotresultsdistribution.support.RegisteredDefectFixes.Divergence;
import uk.gov.hmcts.cp.yotresultsdistribution.support.RegisteredDefectFixes.GoldenDeviation;
import uk.gov.hmcts.cp.yotresultsdistribution.support.RegisteredDefectFixes.PortOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.support.RegisteredDefectFixes.PortResult;
import uk.gov.hmcts.cp.yotresultsdistribution.support.RegisteredDefectFixes.ProgressionRow;

/**
 * The register read adversarially: every entry, shown rejecting what it does not explain.
 *
 * <p><strong>Why a suite of its own.</strong> The differential audit can only prove that every
 * difference the corpus <em>happens to produce</em> is claimed by exactly one row. It cannot prove
 * the converse — that a row claims nothing else — because a claim that waved through half the tree
 * would look identical on a corpus that never built the other half. A predicate written one notch
 * too wide is therefore invisible to the audit and fatal to it: it turns a C-number into an
 * exclusion, and the first regression in that component ships inside a green build.
 *
 * <p><strong>How each entry is read.</strong> Every test is a pair. First a <em>control</em>: the
 * shape the row actually describes, asserted to be claimed by that row and by exactly one row.
 * Then a <em>near miss</em>: the same shape with one detail wrong — a code the payload does not
 * name, a failure classified as something else, a day computed from the wrong reading — asserted to
 * be claimed by <em>nothing</em>. The pair is what makes the assertion worth making: a near miss
 * nobody claims proves the predicate is narrow only if the control proves it is not simply dead.
 *
 * <p><strong>The {@code P} rows are read the same way, because the rule is one rule.</strong>
 * {@link RegisteredDefectFixes.ProgressionRow} catalogues how this port may differ from
 * progression's own recorded output rather than from the 001 function app's, and a predicate one
 * notch too wide there is invisible to the audit for exactly the reason it is invisible here: the
 * recorded goldens produce the deviations they produce, and a row that also waved through a
 * deviation nobody has recorded yet would look identical. So P10 gets a control and its near
 * misses like every {@code C} entry - a golden that recorded an answer, a refusal at a dereference
 * the row does not name, and an answer that is not the default the row specifies.
 *
 * <p>The two <em>derivations</em> ({@link RegisteredDefectFixes.Fix}) are read the same way at the
 * end. Their behaviour through the comparator is pinned in {@code JsonParityTest}; what is pinned
 * here is the derivation itself — the set of renderings it permits for a recorded value, and the
 * emptiness it answers with for a value it does not describe.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a>
 */
@DisplayName("RegisteredDefectFixes — every entry rejects what it does not explain")
class RegisteredDefectFixesRejectionTest {

    /** The mapper the recordings themselves are read with, so a synthetic case arrives the same. */
    private static final ObjectMapper MAPPER = ContractJson.mapper();

    /** The hearing identifier C11's derived file name is built from. */
    private static final String HEARING_ID = "1828f356-f746-4f2d-932b-79ef2df95c80";

    /** A hearing carrying nothing but the identifier every recorded case has. */
    private static final String BARE_HEARING = "{\"id\":\"" + HEARING_ID + "\"}";

    /** The clock the corpus was recorded at, as its recordings carry it. */
    private static final String CLOCK_PIN = "{\"clockPinIso\":\"2026-08-21T09:15:00.000Z\"}";

    /** The same instant as {@code getLocalDateTime} renders it — a London wall clock, then a 'Z'. */
    private static final String CLOCK_PIN_AS_LEGACY_WROTE_IT = "\"2026-08-21T10:15:00Z\"";

    /** The court house every synthetic subscription question is asked about. */
    private static final String OU_CODE = "B01LY00";

    /** A hearing sitting at that court house. */
    private static final String HEARING_AT_THE_COURT_HOUSE =
            "{\"id\":\"" + HEARING_ID + "\",\"courtCentre\":{\"code\":\"" + OU_CODE + "\"}}";

    /** The first of P10's two recorded refusals, as {@code synthetic__…-without-flags} wrote it. */
    private static final String UNBOXED_APPEAL_FLAG =
            "java.lang.NullPointerException: Cannot invoke \"java.lang.Boolean.booleanValue()\" "
                    + "because the return value of "
                    + "\"uk.gov.justice.core.courts.CourtApplicationType.getAppealFlag()\" is null";

    /** The second, as {@code synthetic__respondents-absent} wrote it. */
    private static final String ABSENT_RESPONDENT_LIST =
            "java.lang.NullPointerException: Cannot invoke \"java.util.List.stream()\" because the "
                    + "return value of "
                    + "\"uk.gov.justice.core.courts.CourtApplication.getRespondents()\" is null";

    @Test
    @DisplayName("C11 rejects a file name that is right about the code and wrong about the day")
    void c11_rejects_a_file_name_that_is_wrong_about_the_day() {
        final RecordedCase recorded = recording("{}");
        // 23:00 on a June wall clock is 22:00Z, so the register is filed under the 1st. Taking the
        // day from London's calendar instead files it under the 2nd — the port defect the audit
        // caught, and the one this claim must never absorb.
        isClaimedBy(fileName(recorded, "court-register_2020-06-01_" + OU_CODE), "C11");
        claimedByNothing(fileName(recorded, "court-register_2020-06-02_" + OU_CODE));
        claimedByNothing(fileName(recorded, "court-register_2020-06-01_B99XX00"));
    }

    @Test
    @DisplayName("C23 rejects a code the payload's own verdict does not name")
    void c23_rejects_a_code_the_payload_does_not_name() {
        final RecordedCase recorded = recording("{\"hearing\":{\"id\":\"" + HEARING_ID + "\","
                + "\"prosecutionCases\":[{\"defendants\":[{\"offences\":[{"
                + "\"offenceCode\":\"TH68001\",\"orderIndex\":1,"
                + "\"verdict\":{\"verdictType\":{\"categoryType\":\"GUILTY_CONVICTED\"}}}]}]}]},"
                + "\"expected\":{\"defendants\":[{\"prosecutionCasesOrApplications\":[{\"offences\":"
                + "[{\"offenceCode\":\"TH68001\",\"orderIndex\":1}]}]}]}}");

        isClaimedBy(verdict(recorded, "\"GUILTY_CONVICTED\""), "C23");
        // Code-shaped, upper case, underscore-separated — everything the old predicate asked for,
        // and a verdict this hearing never recorded.
        claimedByNothing(verdict(recorded, "\"NOT_GUILTY\""));
        claimedByNothing(verdict(recorded, "\"GUILTY\""));
    }

    @Test
    @DisplayName("C23 rejects an offence that resolves back to no verdict at all")
    void c23_rejects_an_offence_that_resolves_to_no_verdict() {
        // The recorded offence names an order index the payload's offence does not, so the register
        // entry cannot be joined to a verdict and the difference is not attributable.
        final RecordedCase recorded = recording("{\"hearing\":{\"id\":\"" + HEARING_ID + "\","
                + "\"prosecutionCases\":[{\"defendants\":[{\"offences\":[{"
                + "\"offenceCode\":\"TH68001\",\"orderIndex\":1,"
                + "\"verdict\":{\"verdictType\":{\"categoryType\":\"GUILTY_CONVICTED\"}}}]}]}]},"
                + "\"expected\":{\"defendants\":[{\"prosecutionCasesOrApplications\":[{\"offences\":"
                + "[{\"offenceCode\":\"TH68001\",\"orderIndex\":2}]}]}]}}");

        claimedByNothing(verdict(recorded, "\"GUILTY_CONVICTED\""));
    }

    @Test
    @DisplayName("C9 rejects a recorded defendantPresent that is not the false it can only be")
    void c9_rejects_a_defendant_present_the_legacy_could_not_have_written() {
        final RecordedCase recorded = recording("{}");
        isClaimedBy(field(recorded, "/defendants/0/hearing/defendantPresent", "false", "true"),
                "C9");
        claimedByNothing(
                field(recorded, "/defendants/0/hearing/defendantPresent", "true", "false"));
    }

    @Test
    @DisplayName("C9 rejects an appearance rendering that is not one of the mapper's three")
    void c9_rejects_an_appearance_rendering_that_is_not_one_of_three() {
        final RecordedCase recorded = recording("{}");
        final String path = "/defendants/0/hearing/defendantAppearanceDetails";
        isClaimedBy(field(recorded, path, "absent", "\"In person\""), "C9");
        claimedByNothing(field(recorded, path, "absent", "\"Attended\""));
    }

    @Test
    @DisplayName("C22 rejects a dropped application the hearing shows a prosecutor brought")
    void c22_rejects_a_drop_the_hearing_does_not_account_for() {
        final RecordedCase recorded = recording("{\"hearing\":{\"id\":\"" + HEARING_ID + "\","
                + "\"courtApplications\":["
                + "{\"id\":\"app-defence\",\"applicant\":{}},"
                + "{\"id\":\"app-crown\",\"applicant\":{\"prosecutingAuthority\":{\"name\":\"CPS\"}}}"
                + "]}}");
        final String both = "[{\"caseOrApplicationReference\":\"A\","
                + "\"courtApplicationId\":\"app-defence\"},"
                + "{\"caseOrApplicationReference\":\"B\",\"courtApplicationId\":\"app-crown\"}]";

        isClaimedBy(field(recorded, "/defendants/0/prosecutionCasesOrApplications", both,
                "[{\"caseOrApplicationReference\":\"B\","
                        + "\"courtApplicationId\":\"app-crown\"}]"), "C22");
        // The port kept the defence-initiated one and dropped the Crown's: the same shape of
        // difference, at the same path, for a reason C22 does not authorise.
        claimedByNothing(field(recorded, "/defendants/0/prosecutionCasesOrApplications", both,
                "[{\"caseOrApplicationReference\":\"A\",\"courtApplicationId\":\"app-defence\"}]"));
    }

    @Test
    @DisplayName("C26 rejects an omitted field the frozen contract was not already refusing")
    void c26_rejects_an_omission_the_contract_was_not_refusing() {
        final String path = "/defendants/0/prosecutionCasesOrApplications/0/offences/0/orderIndex";
        final RecordedCase refused = recording(
                "{\"violations\":[{\"pointer\":\"" + path + "\"}]}");
        final RecordedCase refusedElsewhere = recording(
                "{\"violations\":[{\"pointer\":\"/defendants/0/address\"}]}");

        isClaimedBy(new Divergence.Field(refused, register(), path, json("null"), null), "C26");
        claimedByNothing(
                new Divergence.Field(refusedElsewhere, register(), path, json("null"), null));
    }

    @Test
    @DisplayName("C36 rejects a register the legacy did address somebody with")
    void c36_rejects_a_register_that_reached_somebody() {
        final RecordedCase reachedNobody = recording("{\"expected\":{\"recipients\":[]}}");
        final RecordedCase reachedSomebody = recording(
                "{\"expected\":{\"recipients\":[{\"emailAddress\":\"clerk@example.test\"}]}}");

        isClaimedBy(new Divergence.Outcome(reachedNobody, noRegister("no-subscriptions")), "C36");
        claimedByNothing(
                new Divergence.Outcome(reachedSomebody, noRegister("no-subscriptions")));
    }

    @Test
    @DisplayName("C29 rejects a refusal at a field the recorder's own validator never named")
    void c29_rejects_a_refusal_for_the_wrong_reason() {
        final RecordedCase recorded = recording("{\"contractStatus\":\"SCHEMA_INVALID\","
                + "\"violations\":[{\"pointer\":\"/defendants/0/address\"}]}");

        isClaimedBy(new Divergence.Outcome(recorded, refused("/defendants/0/address/address1")),
                "C29");
        claimedByNothing(new Divergence.Outcome(recorded, refused("/hearingDate")));
    }

    @Test
    @DisplayName("C31 rejects a lost register that was never a multi-defendant one")
    void c31_rejects_a_hearing_with_only_one_register_defendant() {
        isClaimedBy(new Divergence.Outcome(matchedNobodyFor(2), register()), "C31");
        claimedByNothing(new Divergence.Outcome(matchedNobodyFor(1), register()));
    }

    @Test
    @DisplayName("C7 rejects a genuine group-proceedings skip, and a register lost some other way")
    void c7_rejects_a_genuine_skip_and_a_register_lost_some_other_way() {
        final RecordedCase looselyTyped = skippedGroupProceedings("\"false\"");
        final RecordedCase genuine = skippedGroupProceedings("true");

        isClaimedBy(new Divergence.Outcome(looselyTyped, register()), "C7");
        isClaimedBy(new Divergence.Outcome(looselyTyped, refused("/defendants/0/address")), "C7");
        // A hearing that really is group proceedings: the port owes the same suppression.
        claimedByNothing(new Divergence.Outcome(genuine, register()));
        // Proceeding is not the fix — reaching the register the skip was suppressing is. A port
        // that proceeded and then lost it anyway is a port defect wearing C7's number.
        claimedByNothing(new Divergence.Outcome(looselyTyped, noRegister("no-subscriptions")));
        claimedByNothing(new Divergence.Outcome(looselyTyped, failed()));
    }

    @Test
    @DisplayName("C19 rejects a swallowed TypeError that is not the personDetails one")
    void c19_rejects_an_unrelated_swallowed_type_error() {
        isClaimedBy(new Divergence.Outcome(swallowing("reading 'personDetails'"), register()),
                "C19");
        claimedByNothing(
                new Divergence.Outcome(swallowing("reading 'somethingElse'"), register()));
    }

    @Test
    @DisplayName("C8 rejects an attendance failure the port did not survive")
    void c8_rejects_an_attendance_failure_the_port_did_not_survive() {
        final RecordedCase recorded = swallowing("reading 'attendanceDays'");
        isClaimedBy(new Divergence.Outcome(recorded, register()), "C8");
        // The whole of C8 is that the register survives. A classified failure is a different
        // outcome, however defensible it might be on its own.
        claimedByNothing(new Divergence.Outcome(recorded, failed()));
    }

    @Test
    @DisplayName("C2 rejects a swallowed failure the port reported as a success of its own")
    void c2_rejects_a_swallowed_failure_the_port_also_completed() {
        final RecordedCase unreadableTime = swallowing("Invalid time value");
        final RecordedCase refdataSilent = recording(
                "{\"observed\":{\"outcome\":\"swallowed-exception\"},\"subscriptions\":null}");

        isClaimedBy(new Divergence.Outcome(unreadableTime, failed()), "C2");
        isClaimedBy(new Divergence.Outcome(refdataSilent, notTransformed()), "C2");
        // Ending in a register is not a classified failure: C2's sentence is that every path ends
        // in a recorded terminal state, not that any ending will do.
        claimedByNothing(new Divergence.Outcome(unreadableTime, register()));
        claimedByNothing(new Divergence.Outcome(refdataSilent, register()));
    }

    @Test
    @DisplayName("C4 rejects a lost register with no informant-code coincidence behind it")
    void c4_rejects_a_lost_register_without_the_coincidence() {
        isClaimedBy(new Divergence.Outcome(
                subscribedAs("\"informantCode\":\"" + OU_CODE + "\""),
                noRegister("no-subscriptions")), "C4");
        claimedByNothing(new Divergence.Outcome(
                subscribedAs("\"informantCode\":\"B99XX00\""),
                noRegister("no-subscriptions")));
    }

    @Test
    @DisplayName("C5 rejects an accidental route carried by an entry the filter drops")
    void c5_rejects_an_accidental_route_on_a_non_register_entry() {
        isClaimedBy(new Divergence.Outcome(
                subscribedAs("\"isNowSubscription\":true"),
                noRegister("no-subscriptions")), "C5");
        // The upstream filter keeps only court-register subscriptions, so a NOWs flag on an entry
        // that never reached the matcher is not a route into it.
        claimedByNothing(new Divergence.Outcome(
                recording(subscriptionsCase(
                        "\"isCourtRegisterSubscription\":false,\"isNowSubscription\":true",
                        "B02OT00")),
                noRegister("no-subscriptions")));
    }

    @Test
    @DisplayName("C30 rejects a covered court house whose subscription asks for no major creditor")
    void c30_rejects_a_subscription_that_asks_for_no_major_creditor() {
        isClaimedBy(new Divergence.Outcome(
                recording(subscriptionsCase("\"isCourtRegisterSubscription\":true,"
                        + "\"subscriptionVocabulary\":{\"anyMajorCreditor\":true}", OU_CODE)),
                noRegister("no-subscriptions")), "C30");
        claimedByNothing(new Divergence.Outcome(
                recording(subscriptionsCase("\"isCourtRegisterSubscription\":true,"
                        + "\"subscriptionVocabulary\":{\"anyMajorCreditor\":false}", OU_CODE)),
                noRegister("no-subscriptions")));
    }

    @Test
    @DisplayName("C25 rejects an ethnicity the payload never recorded for anybody")
    void c25_rejects_an_ethnicity_the_payload_does_not_hold() {
        final RecordedCase recorded = recording("{\"hearing\":{\"id\":\"" + HEARING_ID + "\","
                + "\"prosecutionCases\":[{\"defendants\":[{\"personDefendant\":{\"personDetails\":"
                + "{\"ethnicity\":{\"selfDefinedEthnicityDescription\":\"White British\"}}}}]}]}}");

        isClaimedBy(field(recorded, "/defendants/0/ethnicity", "absent", "\"White British\""), "C25");
        claimedByNothing(field(recorded, "/defendants/0/ethnicity", "absent", "\"Any Other\""));
    }

    @Test
    @DisplayName("C21 rejects an ASN failure the port did not survive either")
    void c21_rejects_an_asn_failure_the_port_did_not_survive() {
        final RecordedCase recorded = swallowing("reading 'arrestSummonsNumber'");
        isClaimedBy(new Divergence.Outcome(recorded, register()), "C21");
        claimedByNothing(new Divergence.Outcome(recorded, noRegister("no-subscriptions")));
    }

    @Test
    @DisplayName("C35 rejects a recorded hearing date that is not the corpus's own clock")
    void c35_rejects_a_hearing_date_that_is_not_the_clock() {
        final RecordedCase recorded = recording("{\"params\":" + CLOCK_PIN + "}");
        isClaimedBy(new Divergence.Field(recorded, register(), "/hearingDate",
                json(CLOCK_PIN_AS_LEGACY_WROTE_IT), json("\"2019-11-14\"")), "C35");
        // A perfectly ordinary recorded date. If C35 claimed this, every hearingDate in the corpus
        // would be licensed to differ.
        claimedByNothing(new Divergence.Field(recorded, register(), "/hearingDate",
                json("\"2026-08-20T10:15:00Z\""), json("\"2019-11-14\"")));
    }

    @Test
    @DisplayName("C35 rejects a contract refusal that does not name the hearing date")
    void c35_rejects_a_refusal_at_another_pointer() {
        final RecordedCase recorded = recording("{\"params\":" + CLOCK_PIN + ",\"expected\":"
                + "{\"hearingDate\":" + CLOCK_PIN_AS_LEGACY_WROTE_IT + "}}");

        isClaimedBy(new Divergence.Outcome(recorded, refused("/hearingDate")), "C35");
        claimedByNothing(new Divergence.Outcome(recorded, refused("/defendants/0/address")));
    }

    @Test
    @DisplayName("C35 rejects a swallowed dereference that is not one of its two")
    void c35_rejects_an_unrelated_swallowed_dereference() {
        isClaimedBy(new Divergence.Outcome(
                swallowing("hearingDays.find is not a function"), failed()), "C35");
        isClaimedBy(new Divergence.Outcome(swallowing("reading 'sittingDay'"), failed()), "C35");
        claimedByNothing(new Divergence.Outcome(
                swallowing("reading 'somethingElse'"), failed()));
    }

    @Test
    @DisplayName("C12 rejects any day difference but the one the share instant accounts for")
    void c12_rejects_a_day_difference_the_share_instant_does_not_account_for() {
        final RecordedCase eveningShare = recording(
                "{\"params\":{\"sharedTime\":\"2020-06-01T23:00:00Z\"}}");
        final RecordedCase morningShare = recording(
                "{\"params\":{\"sharedTime\":\"2020-06-01T10:00:00Z\"}}");

        isClaimedBy(day(eveningShare, "2020-06-02", "2020-06-01"), "C12");
        // The port owes the UTC day of the share and nothing else, so a day off in either
        // direction is a port defect rather than C10's relabelling.
        claimedByNothing(day(eveningShare, "2020-06-02", "2020-05-31"));
        claimedByNothing(day(eveningShare, "2020-06-02", "2020-06-02"));
        // A 10:00Z share is the same day in London, so a legacy day of the 2nd is not this row.
        claimedByNothing(day(morningShare, "2020-06-02", "2020-06-01"));
    }

    @Test
    @DisplayName("P10 rejects a golden that recorded an answer rather than a refusal")
    void p10_rejects_a_golden_progressions_own_rule_answered() {
        isClaimedBy(unreadableApplication(UNBOXED_APPEAL_FLAG, "Applicant"), "P10");
        isClaimedBy(unreadableApplication(ABSENT_RESPONDENT_LIST, "Applicant"), "P10");
        // Progression answered this one, so the golden is an oracle for the answer and the port
        // owes exactly it. P10 is about the shapes there is no answer to be equal to.
        claimedByNothing(new GoldenDeviation(
                "synthetic__respondent", "Respondent", null, "Applicant"));
    }

    @Test
    @DisplayName("P10 rejects a refusal at a dereference the row does not name")
    void p10_rejects_a_refusal_the_row_does_not_name() {
        // The row names two dereferences and reaches its third shape through a synthesised hearing
        // in DefendantTypeResolverTest. A NullPointerException somewhere else in progression's
        // command is not this row's, however similarly it reads.
        claimedByNothing(unreadableApplication("java.lang.NullPointerException: Cannot invoke "
                + "\"java.util.List.stream()\" because the return value of "
                + "\"uk.gov.justice.core.courts.CourtApplication.getApplicant()\" is null",
                "Applicant"));
        claimedByNothing(unreadableApplication(
                "java.lang.ClassCastException: CourtApplicationType", "Applicant"));
        claimedByNothing(new GoldenDeviation(
                "synthetic__respondents-absent", null, null, "Applicant"));
    }

    @Test
    @DisplayName("P10 rejects any answer but the default it specifies")
    void p10_rejects_an_answer_that_is_not_the_rules_own_default() {
        // Answering *something* is not the fix. P10's sentence is that an unreadable court
        // application answers the rule's own default, so a port that guessed a side of the
        // application progression never got far enough to decide is a port defect wearing this
        // row's number.
        claimedByNothing(unreadableApplication(UNBOXED_APPEAL_FLAG, "Respondent"));
        claimedByNothing(unreadableApplication(UNBOXED_APPEAL_FLAG, "Appellant"));
        claimedByNothing(unreadableApplication(UNBOXED_APPEAL_FLAG, null));
    }

    @Test
    @DisplayName("C10's derivation permits the instant the recording names, and nothing else")
    void c10_derivation_permits_only_the_instant_the_recording_names() {
        final RegisteredDefectFixes.Fix registerDate =
                RegisteredDefectFixes.forProperty("registerDate");

        assertThat(registerDate.permittedFor("2020-06-01T11:00:00Z"))
                .containsExactly("2020-06-01T10:00:00Z");
        // The repeated autumn hour is the one place two answers are honest; every other value has
        // exactly one, and the recording's own rendering is never among them in summer.
        assertThat(registerDate.permittedFor("2020-10-25T01:30:00Z"))
                .containsExactly("2020-10-25T00:30:00Z", "2020-10-25T01:30:00Z");
        // Values the fix does not describe are answered with nothing, which the comparator reports
        // as a difference rather than waving the component through.
        assertThat(registerDate.permittedFor("2020-06-01T11:00:00+01:00")).isEmpty();
        assertThat(registerDate.permittedFor("01/06/2020")).isEmpty();
        assertThat(registerDate.permittedFor(null)).isEmpty();
    }

    @Test
    @DisplayName("C24's derivation permits the sentinel re-joined, and nothing else")
    void c24_derivation_permits_only_the_sentinel_rejoined() {
        final RegisteredDefectFixes.Fix wording = RegisteredDefectFixes.forProperty("wording");

        assertThat(wording.permittedFor("Stole a bicycle.####Contrary to section 1."))
                .containsExactly("Stole a bicycle.\nContrary to section 1.");
        assertThat(wording.permittedFor("Stole a bicycle.####undefined"))
                .containsExactly("Stole a bicycle.");
        assertThat(wording.permittedFor("undefined####Contrary to section 1."))
                .containsExactly("Contrary to section 1.");
        // The legacy writes the sentinel unconditionally, so a recording without one is not a value
        // this fix describes; neither is the pair it writes when the offence carries neither half.
        assertThat(wording.permittedFor("Stole a bicycle.")).isEmpty();
        assertThat(wording.permittedFor("undefined####undefined")).isEmpty();
        assertThat(wording.permittedFor(null)).isEmpty();
    }

    // --- what a divergence is asserted to be -----------------------------------------------------

    /**
     * Asserts that exactly one row claims a divergence, and that it is the expected one.
     *
     * @param divergence the divergence
     * @param row        the C-number the claiming row opens with
     */
    private static void isClaimedBy(final Divergence divergence, final String row) {
        final List<String> claimed = RegisteredDefectFixes.claimedBy(divergence).stream()
                .map(Claim::reference).toList();
        assertThat(claimed)
                .describedAs("the control shape should be claimed by %s alone", row)
                .hasSize(1);
        assertThat(claimed.get(0)).startsWith(row + " ");
    }

    /**
     * Asserts that exactly one progression-leg row explains a deviation, and that it is the
     * expected one.
     *
     * @param deviation the deviation
     * @param row       the P-number the claiming row opens with
     */
    private static void isClaimedBy(final GoldenDeviation deviation, final String row) {
        final List<String> claimed = RegisteredDefectFixes.claimedBy(deviation).stream()
                .map(ProgressionRow::reference).toList();
        assertThat(claimed)
                .describedAs("the control shape should be claimed by %s alone", row)
                .hasSize(1);
        assertThat(claimed.get(0)).startsWith(row + " ");
    }

    /**
     * Asserts that no row claims a divergence at all.
     *
     * @param divergence the divergence
     */
    private static void claimedByNothing(final Divergence divergence) {
        assertThat(RegisteredDefectFixes.claimedBy(divergence))
                .describedAs("no registered row explains this, so the audit must call it a port "
                        + "defect rather than attribute it")
                .isEmpty();
    }

    /**
     * Asserts that no progression-leg row claims a deviation at all.
     *
     * @param deviation the deviation
     */
    private static void claimedByNothing(final GoldenDeviation deviation) {
        assertThat(RegisteredDefectFixes.claimedBy(deviation))
                .describedAs("no registered P row explains this, so the audit must call it a port "
                        + "defect rather than attribute it")
                .isEmpty();
    }

    // --- the divergences themselves --------------------------------------------------------------

    /**
     * A defendant-type golden that recorded a refusal rather than an answer.
     *
     * @param refusal    the stack the recorder wrote instead of an answer
     * @param portAnswer what this port answers, or {@code null} where it answers nothing
     * @return the deviation
     */
    private static GoldenDeviation unreadableApplication(
            final String refusal, final String portAnswer) {
        return new GoldenDeviation(
                "synthetic__master-defendant-without-flags", null, refusal, portAnswer);
    }

    /**
     * A difference at a component of a document both sides produced.
     *
     * @param recorded the recorded case
     * @param path     the JSON pointer of the component
     * @param oracle   what the recording carries there, as JSON
     * @param port     what the port wrote there, as JSON
     * @return the divergence
     */
    private static Divergence field(final RecordedCase recorded, final String path,
            final String oracle, final String port) {
        return new Divergence.Field(recorded, register(), path, json(oracle), json(port));
    }

    /**
     * A {@code /fileName} difference against C11's own recorded name.
     *
     * @param recorded the recorded case
     * @param stem     the day and code half of the name the port wrote
     * @return the divergence
     */
    private static Divergence fileName(final RecordedCase recorded, final String stem) {
        return field(recorded, "/fileName",
                "\"court-register_2020-06-01T23:00:00Z_" + OU_CODE + ".pdf\"",
                "\"" + stem + "_" + HEARING_ID + ".pdf\"");
    }

    /**
     * A {@code verdictCode} difference the recording carries nothing at.
     *
     * @param recorded the recorded case
     * @param port     the code the port wrote, as JSON
     * @return the divergence
     */
    private static Divergence verdict(final RecordedCase recorded, final String port) {
        return field(recorded,
                "/defendants/0/prosecutionCasesOrApplications/0/offences/0/verdictCode",
                "absent", port);
    }

    /**
     * A difference in the day the two runs read their subscription set for.
     *
     * @param recorded  the recorded case
     * @param oracleDay the day the legacy asked for
     * @param portDay   the day the port asks for
     * @return the divergence
     */
    private static Divergence day(final RecordedCase recorded,
            final String oracleDay, final String portDay) {
        return new Divergence.ReferenceDataDay(recorded, register(), oracleDay, portDay);
    }

    // --- what the port did -----------------------------------------------------------------------

    /**
     * A port run that assembled a register.
     *
     * @return the outcome
     */
    private static PortOutcome register() {
        return new PortOutcome(PortResult.REGISTER, null, null, null, "");
    }

    /**
     * A port run that completed with one of the bounded no-register reasons.
     *
     * @param reason the reason
     * @return the outcome
     */
    private static PortOutcome noRegister(final String reason) {
        return new PortOutcome(PortResult.NO_REGISTER, null, reason, null, "");
    }

    /**
     * A port run whose document the frozen contract refused.
     *
     * @param pointer the field it named
     * @return the outcome
     */
    private static PortOutcome refused(final String pointer) {
        return new PortOutcome(
                PortResult.FAILED, null, null, "OUTBOUND_CONTRACT_VIOLATION", pointer);
    }

    /**
     * A port run that ended in a classified failure of some other kind.
     *
     * @return the outcome
     */
    private static PortOutcome failed() {
        return new PortOutcome(PortResult.FAILED, null, null, "TRANSFORMATION_FAILED", "");
    }

    /**
     * A port run whose inputs never arrived.
     *
     * @return the outcome
     */
    private static PortOutcome notTransformed() {
        return PortOutcome.notTransformed("reference data never answered");
    }

    // --- the recordings a claim reads ------------------------------------------------------------

    /**
     * A recorded case carrying only what the claim under test reads.
     *
     * <p>Everything a claim can ask a recording is defaulted to the ordinary answer — a run that
     * produced a document, a reference-data read that answered, a hearing carrying nothing but its
     * identifier — so each test's JSON says exactly what that test is about and nothing else.
     *
     * @param source the fields this case overrides, as JSON
     * @return the case
     */
    private static RecordedCase recording(final String source) {
        final JsonNode overrides = json(source);
        return new RecordedCase(
                "synthetic",
                text(overrides, "contractStatus", RecordedCase.NO_DOCUMENT),
                RecordedCase.IN_CONTRACT,
                override(overrides, "observed", "{\"outcome\":\"document\"}"),
                MAPPER.createObjectNode(),
                override(overrides, "params", "{}"),
                override(overrides, "violations", "absent"),
                false,
                override(overrides, "hearing", BARE_HEARING),
                override(overrides, "subscriptions", "{\"nowSubscriptions\":[]}"),
                override(overrides, "expected", "absent"));
    }

    /**
     * One component of a synthetic recording, defaulted where the test did not say.
     *
     * <p>An explicit JSON {@code null} in a test's own literal means the recorder captured nothing
     * there — a reference-data read that never answered, a run that produced no document — which is
     * the {@code null} the recorded corpus itself hands the audit, and not a JSON null node.
     *
     * @param overrides the fields the test supplied
     * @param name      the component
     * @param fallback  the literal to use where the test said nothing, or {@code absent} for none
     * @return the component
     */
    private static JsonNode override(
            final JsonNode overrides, final String name, final String fallback) {

        final JsonNode supplied = overrides.get(name);
        final JsonNode chosen = supplied == null ? json(fallback) : supplied;
        return chosen == null || chosen.isNull() ? null : chosen;
    }

    /**
     * A recording of a run the orchestrator skipped on a group-proceedings flag.
     *
     * @param flag the value the hearing carried, as JSON
     * @return the case
     */
    private static RecordedCase skippedGroupProceedings(final String flag) {
        return recording("{\"observed\":{\"outcome\":\"skipped-group-proceedings\","
                + "\"noDocumentReason\":\"CourtRegisterOrchestrator/index.js:22 — "
                + "isGroupProceedings is neither null nor false, so SetCourtRegister is never "
                + "called\"},\"hearing\":{\"id\":\"" + HEARING_ID + "\","
                + "\"isGroupProceedings\":" + flag + "}}");
    }

    /**
     * A recording of a run whose exception the legacy caught, logged and discarded.
     *
     * @param message the text the recorder captured
     * @return the case
     */
    private static RecordedCase swallowing(final String message) {
        return recording("{\"observed\":{\"outcome\":\"swallowed-exception\","
                + "\"swallowedErrors\":[\"TypeError: " + message + "\"]}}");
    }

    /**
     * A recording of a run that matched nobody, for a register of a given size.
     *
     * @param registerDefendants how many defendants the fragment gathered
     * @return the case
     */
    private static RecordedCase matchedNobodyFor(final int registerDefendants) {
        return recording("{\"observed\":{\"outcome\":\"no-document\","
                + "\"noDocumentReason\":\"OutboundCourtRegister/index.js:18-20 — no matched "
                + "subscriptions\",\"matchedSubscriptionCount\":0,"
                + "\"registerDefendantCount\":" + registerDefendants + "}}");
    }

    /**
     * A recording of a run the legacy produced a register for, over one subscription entry.
     *
     * @param entry the entry's own fields, as JSON
     * @return the case
     */
    private static RecordedCase subscribedAs(final String entry) {
        return recording(subscriptionsCase(
                "\"isCourtRegisterSubscription\":true," + entry, "B02OT00"));
    }

    /**
     * The overrides for a case whose register the legacy produced and whose subscription set
     * carries one entry.
     *
     * @param entry     the entry's own fields, as JSON
     * @param courtHouse the court house the entry selected
     * @return the overrides, as JSON
     */
    private static String subscriptionsCase(final String entry, final String courtHouse) {
        return "{\"hearing\":" + HEARING_AT_THE_COURT_HOUSE
                + ",\"expected\":{\"recipients\":[{\"emailAddress\":\"clerk@example.test\"}]}"
                + ",\"subscriptions\":{\"nowSubscriptions\":[{" + entry
                + ",\"selectedCourtHouses\":[\"" + courtHouse + "\"]}]}}";
    }

    // --- reading the literals --------------------------------------------------------------------

    /**
     * A JSON literal as a tree, with one word reserved.
     *
     * <p>{@code absent} is not a literal but the absence itself: a field the recording never wrote,
     * or a value the port never produced, which several claims distinguish from an explicit JSON
     * {@code null} because the payload spells the two differently and the mappers read them
     * differently.
     *
     * @param source the literal, or {@code absent}
     * @return the tree, or {@code null} for an absence
     */
    private static JsonNode json(final String source) {
        return "absent".equals(source) ? null : MAPPER.readTree(source);
    }

    /**
     * One of a node's strings, or a default where it carries none.
     *
     * @param node     the node
     * @param name     the property name
     * @param fallback the value to use where the property is absent
     * @return the value
     */
    private static String text(final JsonNode node, final String name, final String fallback) {
        final JsonNode value = node.get(name);
        return value == null ? fallback : value.stringValue();
    }
}
