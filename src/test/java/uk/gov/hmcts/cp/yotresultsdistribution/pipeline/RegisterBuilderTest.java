package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterFragment;
import uk.gov.hmcts.cp.yotresultsdistribution.support.LegacyFixtures;

/**
 * The register fragment: three dates, one court centre, and a vocabulary per defendant.
 *
 * <p>The legacy {@code SetCourtRegister} suite has exactly one case. It asserts the two dates and
 * the hearing id, the defendant count and the result count — and nothing else about a fragment that
 * carries six fields, one of which decides who the register is addressed to. This suite twins that
 * case, repointed at the fixed dates, and then covers the three things it never looked at.
 *
 * <p><strong>What the twin changes, and why it is not a parity break.</strong> The Jest case asserts
 * {@code registerDate === '2020-06-01T11:00:00Z'} from a shared time of {@code 10:00:00Z}: the extra
 * hour is British Summer Time, formatted as a London wall clock and then labelled {@code Z}. That is
 * defect C10, so the twin asserts the instant the producer sent. It fails against the legacy, which
 * is what the fix register demands of it.
 *
 * <p>Three things the Jest case does not reach are pinned here for the first time:
 *
 * <ul>
 *   <li>{@code courtCentreId} — written to every register progression stores, asserted by nothing in
 *       the legacy suite, and {@code undefined} in production because the fragment declares
 *       {@code courtCenterId} and the fixture supplies {@code courtCentreId} (defect C26);</li>
 *   <li>{@code courtCentreOUCode} — the value every subscription is matched by, and the input to
 *       both C4 and C5;</li>
 *   <li>the attached vocabulary — eighteen keys, computed per defendant, and never inspected by any
 *       legacy test at all.</li>
 * </ul>
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> rows C6,
 *     C10 and C26
 */
@DisplayName("RegisterBuilder")
class RegisterBuilderTest {

    /** Room for the handful of inline results a fixture hearing carries. */
    private static final int RESULTS_BUFFER = 256;

    /** The hearing the legacy {@code SetCourtRegister} case is written against. */
    private static final String S1_HEARING_ID = "1828f356-f746-4f2d-932b-79ef2df95c80";

    /** The instant that case shares its results at. */
    private static final String S1_SHARED_TIME = "2020-06-01T10:00:00Z";

    /** The court centre that hearing sat at. */
    private static final String S1_COURT_CENTRE_ID = "f8254db1-1683-483e-afb3-b87fde5a0a26";

    /** That court centre's OU code — the value the whole matching stage turns on. */
    private static final String S1_OU_CODE = "B01LY00";

    /**
     * The vocabulary key set, as {@code VocabularyBuilderTest} pins it. Repeated here rather than
     * shared, because this suite's claim is a different one: not that the builder computes the
     * eighteen keys, but that every defendant on a fragment leaves this stage carrying them.
     */
    private static final String[] VOCABULARY_KEYS = {
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

    private final RegisterBuilder builder = new RegisterBuilder(new Dates());

    /**
     * The twin of the legacy {@code SetCourtRegister} Jest suite — its only case, repointed at the
     * one date the fix register moves.
     */
    @Nested
    @DisplayName("SetCourtRegister — legacy Jest twin")
    class LegacyJestTwin {

        @Test
        @DisplayName("should return the correct court register fragment")
        void should_return_the_correct_court_register_fragment() {
            final RegisterFragment fragment = buildS1();

            assertThat(fragment.registerDefendants()).hasSize(1);
            assertThat(fragment.registerDefendants().get(0).results()).hasSize(4);
            assertThat(fragment.registerDefendants().get(0).results())
                    .allSatisfy(result -> assertThat(
                            result.judicialResult().get("publishedForNows").booleanValue())
                            .isFalse());
            assertThat(fragment.hearingDate()).isEqualTo("2020-01-20T00:00:00Z");
            assertThat(fragment.hearingId()).isEqualTo(S1_HEARING_ID);

            // The one assertion this twin moves. The Jest case expects 11:00:00Z — see C10 below.
            assertThat(fragment.registerDate()).isEqualTo(S1_SHARED_TIME);
        }

        @Test
        @DisplayName("gathers only the defendant the hearing names a master defendant for")
        void gathers_only_the_defendant_with_a_master_defendant_id() {
            // The fixture's prosecution case carries three defendants and one masterDefendantId; the
            // count in the twin above is a consequence of that, and asserting it as a count alone
            // would pass on a builder that gathered the wrong one of the three.
            assertThat(buildS1().registerDefendants())
                    .extracting(RegisterDefendant::masterDefendantId)
                    .containsExactly("dba49c16-13d9-4ee0-98de-9b2d78fc8686");
        }
    }

    /**
     * The three dates the fragment carries, which are three different things and are computed three
     * different ways. The legacy Jest case asserts two of them against one hearing; between them the
     * cases below name where each comes from.
     */
    @Nested
    @DisplayName("the three dates")
    class ThreeDates {

        /**
         * Defect C10. The legacy formats the shared time as a {@code Europe/London} wall clock and
         * appends a literal {@code Z}, so a 10:00 UTC share is stored, printed and looked up as
         * 11:00 "UTC" for the half of the year the country is on BST.
         */
        @Test
        @DisplayName("register date is the instant the results were shared, not a London clock")
        void register_date_is_the_instant_the_results_were_shared() {
            assertThat(buildS1().registerDate())
                    .as("the legacy answers 2020-06-01T11:00:00Z for this share")
                    .isEqualTo("2020-06-01T10:00:00Z");
        }

        @Test
        @DisplayName("hearing date is the latest date any of the hearing's results was ordered")
        void hearing_date_is_the_latest_ordered_date() {
            assertThat(build(hearingOrderedOn("2020-01-20", "2020-03-05", "2020-02-11"))
                    .hearingDate())
                    .isEqualTo("2020-03-05T00:00:00Z");
        }

        @Test
        @DisplayName("takes the ordered dates before the court-extract filter removes any results")
        void takes_the_ordered_dates_before_court_extract_filtering() {
            // `SetCourtRegister/index.js:40-43` collects the dates, picks the latest, and only then
            // filters. The later of these two results is published through the NOWs route and never
            // reaches the register — and still decides which day the register covers. Filtering
            // first would answer 2020-01-20 and would look perfectly reasonable.
            final JsonNode hearing = mapper.readTree("""
                {"id":"hearing-1","hearingDays":[],
                 "courtCentre":{"id":"cc-1","name":"Lavender Hill","code":"B01LY00"},
                 "prosecutionCases":[{"id":"case-1",
                  "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
                   "defendantCaseJudicialResults":[
                    {"judicialResultId":"jr-1","orderedDate":"2020-01-20",
                     "isAvailableForCourtExtract":true,"publishedForNows":false},
                    {"judicialResultId":"jr-2","orderedDate":"2020-03-05",
                     "isAvailableForCourtExtract":true,"publishedForNows":true}]}]}]}""");

            final RegisterFragment fragment = build(hearing);

            assertThat(fragment.hearingDate()).isEqualTo("2020-03-05T00:00:00Z");
            assertThat(fragment.registerDefendants().get(0).results()).hasSize(1);
        }

        @Test
        @DisplayName("prefers a sitting day that falls on the latest ordered date")
        void prefers_a_sitting_day_falling_on_the_ordered_date() {
            // `RegisterFragmentService.getHearingDate` matches the hearing's own sitting days against
            // the ordered date and, where one matches, carries the sitting day's time as well as its
            // date. Without a matching day it carries the ordered date, which has no time at all.
            assertThat(build(sittingOn("2020-01-20T09:30:00Z")).hearingDate())
                    .isEqualTo("2020-01-20T09:30:00Z");
        }

        @Test
        @DisplayName("falls back to the ordered date when no sitting day falls on it")
        void falls_back_to_the_ordered_date_when_no_sitting_day_matches() {
            assertThat(build(sittingOn("2020-01-19T09:30:00Z")).hearingDate())
                    .isEqualTo("2020-01-20T00:00:00Z");
        }

        @Test
        @DisplayName("has no hearing date at all when the hearing carries no sitting days")
        void has_no_hearing_date_when_the_hearing_carries_no_sitting_days() {
            // `getHearingDate` returns nothing when `hearingObj.hearingDays` is absent — it has no
            // else branch — and an absent hearing date is what the register then carries. An empty
            // array is not absent: it is truthy in JavaScript, so it takes the branch and falls
            // through to the ordered date, which is what the Jest twin's fixture does.
            final JsonNode hearing = mapper.readTree("""
                {"id":"hearing-1",
                 "courtCentre":{"id":"cc-1","name":"Lavender Hill","code":"B01LY00"},
                 "prosecutionCases":[{"id":"case-1",
                  "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
                   "defendantCaseJudicialResults":[
                    {"judicialResultId":"jr-1","orderedDate":"2020-01-20",
                     "isAvailableForCourtExtract":true}]}]}]}""");

            assertThat(build(hearing).hearingDate()).isNull();
        }

        @Test
        @DisplayName("carries the hearing's own id, whatever the results were ordered under")
        void carries_the_hearings_own_id() {
            // The gathered results name an `orderedHearingId` of their own, which is the hearing that
            // ordered them and not necessarily this one. The fragment's `hearingId` is the payload's.
            assertThat(buildS1().hearingId()).isEqualTo(S1_HEARING_ID);
        }
    }

    /**
     * The court centre, which the legacy suite never asserts and which decides both what the
     * register says it is and who it reaches.
     */
    @Nested
    @DisplayName("the court centre")
    class CourtCentre {

        /**
         * Defect C26. The legacy fragment declares {@code courtCenterId} — "Center" — and the
         * outbound mapper reads the same misspelling, while every fixture supplies
         * {@code courtCentreId}. Both sides are {@code undefined}, so the one Jest assertion that
         * names the field compares {@code undefined} to {@code undefined} and passes.
         */
        @Test
        @DisplayName("carries the court centre id, spelled the way the rest of the estate spells it")
        void carries_the_court_centre_id_spelled_correctly() {
            assertThat(buildS1().courtCentreId()).isEqualTo(S1_COURT_CENTRE_ID);
        }

        @Test
        @DisplayName("carries the court centre's OU code, which is what subscriptions are matched by")
        void carries_the_court_centre_ou_code() {
            // Nothing in the legacy suite asserts this field, and it is the only input to the
            // court-house rule (C4) and to the explicit court-register branch (C5). A fragment that
            // reached matching without it would match nobody, silently.
            assertThat(buildS1().courtCentreOUCode()).isEqualTo(S1_OU_CODE);
        }

        @Test
        @DisplayName("carries no OU code where the court centre has none, rather than inventing one")
        void carries_no_ou_code_where_the_court_centre_has_none() {
            // Every court-register fixture except the `SetCourtRegister` one omits `courtCentre.code`
            // — which is why the filename convention was never pinned either. An absent code has to
            // reach matching as absent, so `no-subscriptions` is the answer rather than a match
            // against the wrong centre.
            final JsonNode hearing = mapper.readTree("""
                {"id":"hearing-1","hearingDays":[],
                 "courtCentre":{"id":"cc-1","name":"Lavender Hill"},
                 "prosecutionCases":[{"id":"case-1",
                  "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
                   "defendantCaseJudicialResults":[
                    {"judicialResultId":"jr-1","orderedDate":"2020-01-20",
                     "isAvailableForCourtExtract":true}]}]}]}""");

            final RegisterFragment fragment = build(hearing);

            assertThat(fragment.courtCentreOUCode()).isNull();
            assertThat(fragment.courtCentreId()).isEqualTo("cc-1");
        }
    }

    /**
     * The vocabulary the builder attaches to each gathered defendant
     * ({@code SetCourtRegister/index.js:63-68}). No legacy test inspects one, and the seven fixtures
     * that carry a vocabulary block carry seven keys where the kernel writes eighteen.
     */
    @Nested
    @DisplayName("the vocabulary attached to each defendant")
    class AttachedVocabulary {

        @Test
        @DisplayName("gives every defendant the full eighteen-key vocabulary")
        void gives_every_defendant_the_eighteen_key_vocabulary() {
            assertThat(buildS1().registerDefendants()).allSatisfy(defendant ->
                    assertThat(List.copyOf(mapper.readTree(
                            mapper.writeValueAsString(defendant.vocabulary())).propertyNames()))
                            .containsExactly(VOCABULARY_KEYS));
        }

        @Test
        @DisplayName("computes it per defendant rather than copying the first defendant's")
        void computes_it_per_defendant() {
            // The fragment is not filtered to youths — that happens at the aggregation stage — so a
            // hearing routinely carries both, each with their own vocabulary. A stage that shared one
            // vocabulary across the list would look right on every single-defendant fixture in the
            // legacy suite, and is the shape defect C31 takes one stage later.
            final RegisterFragment fragment = build(adultThenYouth());

            assertThat(fragment.registerDefendants()).hasSize(2);
            assertThat(fragment.registerDefendants().get(0).vocabulary().youthDefendant()).isFalse();
            assertThat(fragment.registerDefendants().get(0).vocabulary().adultDefendant()).isTrue();
            assertThat(fragment.registerDefendants().get(1).vocabulary().youthDefendant()).isTrue();
            assertThat(fragment.registerDefendants().get(1).vocabulary().adultDefendant()).isFalse();
        }

        @Test
        @DisplayName("carries the youth flag on the defendant as well as in their vocabulary")
        void carries_the_youth_flag_on_the_defendant() {
            // Two readers, one fact: the aggregation stage filters the register on the defendant's
            // own flag while the matcher reads the vocabulary's. They must not be able to disagree.
            final RegisterFragment fragment = build(adultThenYouth());

            assertThat(fragment.registerDefendants().get(0).youthDefendant()).isFalse();
            assertThat(fragment.registerDefendants().get(1).youthDefendant()).isTrue();
        }
    }

    /**
     * Defect C6. {@code SetCourtRegister/index.js:35-38} guards the gather with
     * {@code if (!defendantContextBaseList) return;}, which can never fire — the gather always
     * returns an array. A hearing that gathers nobody therefore flows on as an empty register, and
     * the orchestrator's next guard reports the whole run as a success indistinguishable from a
     * delivered register.
     */
    @Nested
    @DisplayName("a hearing that gathers nobody (C6)")
    class NoDefendants {

        @Test
        @DisplayName("no defendants is a named outcome")
        void no_defendants_is_a_named_outcome() {
            // The builder answers with a fragment, not with nothing: an absent return is
            // indistinguishable from a thrown-and-swallowed build, which is exactly how the legacy
            // loses a register without a word. The naming itself happens one stage up, where
            // `DistributionPipelineTest.no_op_outcomes_are_distinguishable` records this shape as
            // `no-defendants`; what this stage owes that stage is a fragment it can recognise.
            final RegisterFragment fragment = build(hearingGatheringNobody());

            assertThat(fragment).isNotNull();
            assertThat(fragment.registerDefendants()).isEmpty();
        }

        @Test
        @DisplayName("still carries the hearing and court centre the empty register was for")
        void still_carries_the_hearing_and_court_centre() {
            // Without these the outcome is recordable but not investigable: "no defendants" for
            // which hearing, at which court centre, on which day.
            final RegisterFragment fragment = build(hearingGatheringNobody());

            assertThat(fragment.hearingId()).isEqualTo("hearing-1");
            assertThat(fragment.courtCentreId()).isEqualTo("cc-1");
            assertThat(fragment.courtCentreOUCode()).isEqualTo(S1_OU_CODE);
            assertThat(fragment.registerDate()).isEqualTo(S1_SHARED_TIME);
        }

        @Test
        @DisplayName("has no hearing date, because no result named a day")
        void has_no_hearing_date_because_no_result_named_a_day() {
            // There are no ordered dates to sort, so there is no latest one and nothing for the
            // sitting days to be matched against.
            assertThat(build(hearingGatheringNobody()).hearingDate()).isNull();
        }
    }

    /**
     * Builds the fragment the legacy {@code SetCourtRegister} case builds, from the same fixture and
     * the same shared time.
     *
     * @return the fragment
     */
    private RegisterFragment buildS1() {
        return builder.build(
                LegacyFixtures.readCourtRegister(
                        "setcourtregister/hearing-results-for-court-register.json").get("hearing"),
                S1_SHARED_TIME);
    }

    /**
     * Builds a fragment from a hearing, at the shared time the legacy case uses.
     *
     * @param hearing the hearing payload
     * @return the fragment
     */
    private RegisterFragment build(final JsonNode hearing) {
        return builder.build(hearing, S1_SHARED_TIME);
    }

    /**
     * A hearing whose single defendant carries one result per ordered date given.
     *
     * @param orderedDates the dates the results were ordered on
     * @return the hearing
     */
    private JsonNode hearingOrderedOn(final String... orderedDates) {
        final StringBuilder results = new StringBuilder(RESULTS_BUFFER);
        for (int index = 0; index < orderedDates.length; index++) {
            results.append(index == 0 ? "" : ",")
                    .append("{\"judicialResultId\":\"jr-").append(index)
                    .append("\",\"orderedDate\":\"").append(orderedDates[index])
                    .append("\",\"isAvailableForCourtExtract\":true}");
        }
        return mapper.readTree("""
            {"id":"hearing-1","hearingDays":[],
             "courtCentre":{"id":"cc-1","name":"Lavender Hill","code":"B01LY00"},
             "prosecutionCases":[{"id":"case-1",
              "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
               "defendantCaseJudicialResults":[%s]}]}]}""".formatted(results));
    }

    /**
     * A hearing sitting on the given day, whose single result was ordered on 2020-01-20.
     *
     * @param sittingDay the sitting day, as an instant
     * @return the hearing
     */
    private JsonNode sittingOn(final String sittingDay) {
        return mapper.readTree("""
            {"id":"hearing-1","hearingDays":[{"sittingDay":"%s"}],
             "courtCentre":{"id":"cc-1","name":"Lavender Hill","code":"B01LY00"},
             "prosecutionCases":[{"id":"case-1",
              "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
               "defendantCaseJudicialResults":[
                {"judicialResultId":"jr-1","orderedDate":"2020-01-20",
                 "isAvailableForCourtExtract":true}]}]}]}""".formatted(sittingDay));
    }

    /**
     * A hearing carrying an adult defendant first and a youth second — the shape defect C31 turns
     * on, and the only shape in which a per-defendant vocabulary is observable.
     *
     * @return the hearing
     */
    private JsonNode adultThenYouth() {
        return mapper.readTree("""
            {"id":"hearing-1","hearingDays":[],
             "courtCentre":{"id":"cc-1","name":"Lavender Hill","code":"B01LY00"},
             "prosecutionCases":[{"id":"case-1","defendants":[
              {"id":"def-1","masterDefendantId":"master-adult","offences":[],
               "defendantCaseJudicialResults":[
                {"judicialResultId":"jr-1","orderedDate":"2020-01-20",
                 "isAvailableForCourtExtract":true}]},
              {"id":"def-2","masterDefendantId":"master-youth","isYouth":true,"offences":[],
               "defendantCaseJudicialResults":[
                {"judicialResultId":"jr-2","orderedDate":"2020-01-20",
                 "isAvailableForCourtExtract":true}]}]}]}""");
    }

    /**
     * A hearing whose defendants carry no master defendant id, which is what the gather drops them
     * for — the commonest way a real hearing produces an empty register.
     *
     * @return the hearing
     */
    private JsonNode hearingGatheringNobody() {
        return mapper.readTree("""
            {"id":"hearing-1","hearingDays":[],
             "courtCentre":{"id":"cc-1","name":"Lavender Hill","code":"B01LY00"},
             "prosecutionCases":[{"id":"case-1",
              "defendants":[{"id":"def-1","offences":[],
               "defendantCaseJudicialResults":[
                {"judicialResultId":"jr-1","orderedDate":"2020-01-20",
                 "isAvailableForCourtExtract":true}]}]}]}""");
    }
}
