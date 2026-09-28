package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;

/**
 * Which side of a court application the register's defendants are on, held to progression's answer.
 *
 * <p>The oracle is not this suite's reading of the rule, it is what progression's own
 * {@code CourtRegisterHandler.getDefendantType} ({@code :131-153}) answered when it was run. T004
 * recorded those answers under {@code src/test/resources/goldens/progression/defendant-type/}: one
 * file per case, each naming the hearing it was given, the document it was given and the answer it
 * produced, with {@code PROVENANCE.md} and {@code INDEX.json} beside them. Every case below reads
 * its inputs through the paths the golden itself names, so a re-record moves this suite with it
 * rather than around it.
 *
 * <p>Three of the four answers had to be synthesised, and the goldens say so. Element zero of every
 * recorded document's {@code prosecutionCasesOrApplications} is the prosecution case, which carries
 * no application id, so {@code getCourtApplicationId} ({@code :226-235}) answers {@code null} for
 * all three base fixtures and no recorded hearing can reach the {@code Appellant} or the
 * {@code Respondent} branch. The no-application case is the real one; the other three come from
 * {@code defendant-type/synthetic/}, which carry only the five fields the rule reads.
 *
 * <p><strong>"No court application" is empty here and {@code ""} there.</strong> Progression's
 * caller seeds the type with {@code StringUtils.EMPTY} ({@code CourtRegisterHandler:84}) and
 * replaces it only when an application was found, so its recorded answer for those hearings is the
 * empty string; this port answers {@link Optional#empty()} and the document leaves the field
 * absent. They are one statement in two vocabularies, which is what {@link #NO_TYPE} maps and the
 * only place the two spellings meet.
 *
 * <p><strong>Three shapes are a difference from the legacy, and they are defect fix P10.</strong>
 * {@code synthetic__master-defendant-without-flags} and {@code synthetic__respondents-absent} record
 * progression throwing {@link NullPointerException} - unboxing an absent {@code appealFlag} and
 * dereferencing an absent respondent list - which is the shape all six base fixtures carry, and from
 * which progression is protected only by both flags being {@code required} in
 * {@code courtApplicationType.json}. The respondents-absent shape is in contract even so:
 * {@code respondents} is not in {@code courtApplication.json}'s required list. The third shape is
 * the same rule's third unguarded dereference - a respondent carrying no {@code masterDefendant} -
 * which no recorded case reaches and which is therefore synthesised below from the one that does.
 * This port answers {@code Applicant} for all three, so a hearing progression loses the whole
 * register of is recorded here.
 *
 * <p>The obligation the pin carries is {@code doc/DEFECT-FIXES.md} row <strong>P10</strong>, which
 * names both cases below as its pinning tests and carries the sign-off-before-cutover marker
 * (constitution Principle I). The javadoc notes here and on {@link DefendantTypeResolver} that once
 * said a row was owed describe how the deviation was found, not where it now lives.
 *
 * @see <a href="file:../../../../../../../../specs/002-consolidate-progression-leg/research.md">research.md</a> §5
 */
@DisplayName("DefendantTypeResolver")
class DefendantTypeResolverTest {

    /** Where T004 wrote the recorded answers, on the test classpath. */
    private static final String GOLDENS = "/goldens/progression/defendant-type/";

    /** The prefix the goldens' repo-relative input paths carry and the classpath does not. */
    private static final String SOURCE_ROOT = "src/test/resources";

    /** Progression's answer for a hearing with no court application, which this port leaves empty. */
    private static final String NO_TYPE = "";

    /** The mapper the service reads payloads and stored documents with. */
    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    private final DefendantTypeResolver resolver = new DefendantTypeResolver();

    @Nested
    @DisplayName("the three answers the rule can give")
    class TheAnswersTheRuleGives {

        @Test
        @DisplayName("an applicant with a master defendant and both flags false is Applicant")
        void an_applicant_with_a_master_defendant_and_the_flags_false_is_applicant() {
            assertThat(answerFor(golden("synthetic__applicant-flags-false"))).contains("Applicant");
        }

        @Test
        @DisplayName("and so is one whose respondents are nobody this register covers")
        void an_applicant_whose_respondents_are_not_on_the_register_is_applicant() {
            // The default survives the respondent branch as well as the master-defendant one: the
            // rule proves Respondent or it answers Applicant, and it never answers nothing.
            assertThat(answerFor(golden("synthetic__applicant-no-respondent-match")))
                    .contains("Applicant");
        }

        @Test
        @DisplayName("an appeal brought by an applicant who is a master defendant is Appellant")
        void an_appeal_brought_by_the_applicant_is_appellant() {
            assertThat(answerFor(golden("synthetic__appellant"))).contains("Appellant");
        }

        @Test
        @DisplayName("a respondent who is one of the register's defendants is Respondent")
        void a_respondent_who_is_on_the_register_is_respondent() {
            assertThat(answerFor(golden("synthetic__respondent"))).contains("Respondent");
        }
    }

    @Nested
    @DisplayName("a hearing with no court application")
    class NoCourtApplication {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "hearing-with-address-less-youth-and-parent",
            "hearing-with-non-prosecuting-authority-application",
            "hearing-with-surviving-youth-defendant"})
        @DisplayName("has no defendant type at all")
        void a_hearing_with_no_court_application_has_no_defendant_type(final String goldenId) {
            // Not "Applicant". The first defendant's first case-or-application entry is the
            // prosecution case, which carries no application id, so progression's caller never
            // reaches the rule and leaves the empty string behind.
            assertThat(answerFor(golden(goldenId))).isEmpty();
        }
    }

    @Nested
    @DisplayName("every recorded golden")
    class EveryRecordedGolden {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "hearing-with-address-less-youth-and-parent",
            "hearing-with-non-prosecuting-authority-application",
            "hearing-with-surviving-youth-defendant",
            "synthetic__appellant",
            "synthetic__applicant-flags-false",
            "synthetic__applicant-no-respondent-match",
            "synthetic__respondent"})
        @DisplayName("is answered exactly as progression answered it")
        void every_recorded_answer_is_the_one_progression_recorded(final String goldenId) {
            final Golden recorded = golden(goldenId);

            assertThat(answerFor(recorded).orElse(NO_TYPE)).isEqualTo(recorded.defendantType());
        }
    }

    @Nested
    @DisplayName("the three shapes progression throws on (P10)")
    class TheShapesProgressionThrowsOn {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "synthetic__master-defendant-without-flags",
            "synthetic__respondents-absent"})
        @DisplayName("are answered Applicant rather than losing the whole register")
        void the_shapes_progression_throws_on_are_answered_applicant(final String goldenId) {
            // The goldens recorded a refusal rather than an answer, so there is no recorded value
            // to be equal to and the case says both halves of the difference: what progression did,
            // as the golden recorded it, and what this port does instead. An absent flag is read
            // here as not set and an absent respondent list as no respondents, because one
            // unreadable court application must not cost every child on the register their entry.
            final Golden recorded = golden(goldenId);

            assertThat(recorded.threw())
                    .as("the oracle for this shape is a stack trace, not an answer")
                    .startsWith("java.lang.NullPointerException");
            assertThat(recorded.defendantType())
                    .as("and progression therefore recorded no defendant type at all")
                    .isNull();
            assertThat(answerFor(recorded))
                    .as("this port answers the rule's own default, which is the register being "
                            + "recorded where progression's command fails")
                    .contains("Applicant");
        }

        /**
         * The third shape P10 names, and the one no golden covers.
         *
         * <p>Progression reads {@code respondent.getMasterDefendant().getMasterDefendantId()} with
         * no guard, so a respondent who is a party to the application without being a defendant
         * anywhere throws there exactly as an absent respondent list throws a line earlier. No
         * recorded case reaches it - element zero of a register's first defendant is the
         * prosecution case in all six base fixtures - so it is synthesised from the recorded
         * {@code Respondent} case by taking that respondent's master defendant away, in the same
         * way the as-at-hearing deviation synthesises its edited respondent list.
         *
         * <p>P10 specifies all three shapes and this is the third of them, so it is asserted here
         * whether or not the resolver needed changing for it: a row's shapes are pinned by the
         * row, not by whichever of them a previous commit happened to reach.
         */
        @Test
        @DisplayName("and so is a respondent the register has nothing to match against")
        void a_respondent_without_a_master_defendant_is_answered_applicant() {
            final Golden recorded = golden("synthetic__respondent");
            final JsonNode withARespondentWhoIsNobody =
                    withoutTheRespondentsMasterDefendant(recorded.hearing());

            assertThat(answerFor(withARespondentWhoIsNobody, recorded.document()))
                    .as("the respondent is passed over rather than dereferenced, so the rule "
                            + "falls back to its own default and the register is recorded")
                    .contains("Applicant");
        }
    }

    @Nested
    @DisplayName("the application is the hearing's, not the aggregate's")
    class TheAsAtHearingDeviation {

        @Test
        @DisplayName("a respondent list edited after the hearing cannot change the answer")
        void respondents_are_read_from_the_hearing_not_the_aggregate() {
            // Research §5's recorded deviation, design Q25. Progression asks the
            // ApplicationAggregate for the application's respondents as they stand when the
            // register is built; this service reads the copy the hearing carried when it was
            // resulted. The two agree on the type flags and can differ only where the respondent
            // list was edited afterwards - so what is pinned is that the answer follows the
            // hearing's own copy, with the document, which is what an aggregate lookup would be
            // keyed from, identical across both calls.
            final Golden recorded = golden("synthetic__respondent");
            final JsonNode editedAfterTheHearing = withoutTheRespondent(recorded.hearing());

            assertThat(answerFor(recorded)).contains("Respondent");
            assertThat(answerFor(editedAfterTheHearing, recorded.document())).contains("Applicant");
        }
    }

    /**
     * The resolver's answer, with a refusal carried into the value rather than out of the test.
     *
     * <p>The red-run convention asks a test task to run against the seam already on the branch and
     * to record a failing assertion, and the seam T022 replaces refuses with an
     * {@link UnsupportedOperationException}. Carrying the refusal into the answer makes that red run
     * a failing comparison naming what was thrown, and it keeps saying something afterwards: an
     * implementation that throws where a golden recorded an answer fails the same assertion, with
     * the exception's type and message in the failure rather than swallowed.
     *
     * @param hearing  the hearing whose {@code courtApplications[]} the application is looked up in
     * @param document the assembled register the application is named from
     * @return the defendant type, empty where there is none, or the refusal that came instead
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    // Which type a refusal arrives as is the resolver's business and not this suite's: what the
    // assertion needs is the news that an answer did not come, whichever type carried it.
    private Optional<String> answerFor(final JsonNode hearing, final CourtRegisterDocument document) {
        Optional<String> answer;
        try {
            answer = resolver.resolve(hearing, document);
        } catch (RuntimeException refused) {
            answer = Optional.of(refused.getClass().getSimpleName() + ": " + refused.getMessage());
        }
        return answer;
    }

    /**
     * The resolver's answer for one recorded case.
     *
     * @param golden the recorded case, carrying the inputs progression was given
     * @return the defendant type, empty where there is none, or the refusal that came instead
     */
    private Optional<String> answerFor(final Golden golden) {
        return answerFor(golden.hearing(), golden.document());
    }

    /**
     * One recorded case: the two inputs the rule was given and the answer it produced.
     *
     * @param id            the golden's identifier, as {@code INDEX.json} names it
     * @param hearing       the hearing the application is looked up in
     * @param document      the assembled register
     * @param defendantType progression's own answer, {@code ""} where it found no application and
     *                      {@code null} where it produced no answer at all
     * @param threw         what progression threw instead of answering, or {@code null}
     */
    private record Golden(String id, JsonNode hearing, CourtRegisterDocument document,
                          String defendantType, String threw) {
    }

    /**
     * Reads one golden and, through the paths it names, the two inputs behind it.
     *
     * <p>Both input shapes carry the hearing under {@code hearing} - the base fixtures because they
     * are claim-check payloads, the synthesised inputs because they were written to match - and the
     * synthesised ones carry the document under {@code document} where a recorded case is the
     * document itself.
     *
     * @param goldenId the golden's identifier, as {@code INDEX.json} names it
     * @return the recorded case
     */
    private static Golden golden(final String goldenId) {
        final JsonNode recorded = read(GOLDENS + goldenId + ".json");
        final JsonNode hearingSource = read(classpath(recorded.get("hearingFixture").stringValue()));
        final JsonNode documentSource =
                read(classpath(recorded.get("documentSource").stringValue()));
        return new Golden(goldenId, hearingOf(hearingSource), documentOf(documentSource),
                text(recorded, "defendantType"), text(recorded, "threw"));
    }

    /**
     * One recorded field, where a golden that recorded a refusal carries nothing under it.
     *
     * @param recorded the golden
     * @param field    the field to read
     * @return its text, or {@code null} where the golden recorded none
     */
    private static String text(final JsonNode recorded, final String field) {
        final JsonNode value = recorded.get(field);
        return value == null || value.isNull() ? null : value.stringValue();
    }

    /**
     * The same hearing with the application's respondents emptied, standing in for the aggregate
     * whose respondent list was edited after the hearing was resulted.
     *
     * @param hearing the hearing as the payload carried it
     * @return a copy whose only difference is that respondent list
     */
    private static JsonNode withoutTheRespondent(final JsonNode hearing) {
        final ObjectNode edited = (ObjectNode) hearing.deepCopy();
        final ObjectNode application = (ObjectNode) edited.get("courtApplications").get(0);
        application.set("respondents", MAPPER.createArrayNode());
        return edited;
    }

    /**
     * The same hearing with the application's one respondent stripped of its master defendant.
     *
     * <p>Stands in for a respondent who is a party to the application without being a defendant
     * anywhere: the third shape P10 names, which progression reads through and no recorded case
     * reaches, so it is made here from the case that does reach the respondent branch rather than
     * left unpinned.
     *
     * @param hearing the hearing as the payload carried it
     * @return a copy whose only difference is that respondent's master defendant
     */
    private static JsonNode withoutTheRespondentsMasterDefendant(final JsonNode hearing) {
        final ObjectNode edited = (ObjectNode) hearing.deepCopy();
        final ObjectNode respondent = (ObjectNode) edited.get("courtApplications").get(0)
                .get("respondents").get(0);
        respondent.remove("masterDefendant");
        return edited;
    }

    /**
     * The hearing an input file carries.
     *
     * @param source the parsed input file
     * @return the hearing tree
     */
    private static JsonNode hearingOf(final JsonNode source) {
        final JsonNode hearing = source.get("hearing");
        if (hearing == null) {
            throw new IllegalStateException("the golden's hearing fixture carries no hearing");
        }
        return hearing;
    }

    /**
     * The register document an input file carries, read as the store reads a recorded one.
     *
     * @param source the parsed input file
     * @return the document
     */
    private static CourtRegisterDocument documentOf(final JsonNode source) {
        final JsonNode document = source.has("document") ? source.get("document") : source;
        return MAPPER.treeToValue(document, CourtRegisterDocument.class);
    }

    /**
     * The classpath resource behind one of the repo-relative paths a golden names.
     *
     * @param recordedPath the path as the golden records it
     * @return the resource path
     */
    private static String classpath(final String recordedPath) {
        if (!recordedPath.startsWith(SOURCE_ROOT)) {
            throw new IllegalStateException("golden names an input outside the test resources: "
                    + recordedPath);
        }
        return recordedPath.substring(SOURCE_ROOT.length());
    }

    /**
     * Reads one JSON file from the test classpath through the service's own contract mapper.
     *
     * @param resource the absolute resource path
     * @return the parsed tree
     */
    private static JsonNode read(final String resource) {
        try (InputStream stream = DefendantTypeResolverTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing test resource " + resource);
            }
            return MAPPER.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException cannotRead) {
            throw new IllegalStateException("could not read " + resource, cannotRead);
        }
    }
}
