package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterCaseOrApplication;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;

/**
 * Which side of a court application the register's defendants are on.
 *
 * <p>The port of progression's {@code CourtRegisterHandler.getDefendantType}
 * ({@code progression-command/progression-command-handler/src/main/java/uk/gov/moj/cpp/progression/
 * handler/CourtRegisterHandler.java:131-153}) together with the application lookup that feeds it,
 * {@code getCourtApplicationId} ({@code :226-235}) - the first defendant's first case-or-application
 * entry, and the application it names. Both are carried verbatim in
 * {@code src/test/resources/goldens/progression/harness/CourtRegisterHandlerRule.java}, which is
 * what recorded the goldens this resolver is held to.
 *
 * <p>The rule, in progression's own order: {@code Applicant} unless something else is proved;
 * {@code Appellant} where the application has an applicant with a master defendant and the
 * application type carries both the appeal and the applicant-appellant flags; {@code Respondent}
 * where the application has an applicant <em>without</em> a master defendant and one of the
 * application's respondents is a master defendant this register covers. A hearing with no court
 * application has no defendant type at all, which is why the answer is an {@link Optional} rather
 * than the string {@code Applicant} progression's own default would produce for it.
 *
 * <p><strong>"No court application" is the id, not the lookup.</strong> Progression's caller reads
 * the aggregate only when {@code getCourtApplicationId} answers something ({@code :84-92}), so a
 * document whose first defendant names no application is where the empty answer comes from. Once an
 * id is in hand the rule runs whatever the lookup produced, and progression's own method answers
 * {@code Applicant} for a null application - so an id this hearing does not carry answers
 * {@code Applicant} here too, rather than nothing, which is the answer progression would give for
 * the same pair of arguments.
 *
 * <p><strong>The application is read from the hearing, not from the aggregate</strong> (research
 * §5). Progression reads the court application's current respondents through
 * {@code AggregateService}; this service reads the as-at-hearing copy the payload carries. The two
 * agree on the type flags and can differ only where a respondent list was edited after the hearing,
 * which is a deviation recorded in the design (Q25) and pinned by
 * {@code DefendantTypeResolverTest.respondents_are_read_from_the_hearing_not_the_aggregate}.
 *
 * <p><strong>Three shapes progression throws on are answered here, and they are defect fix P10</strong>
 * ({@code doc/DEFECT-FIXES.md}). An application type without the two flags unboxes {@code null} in
 * progression, an application without a respondent list dereferences {@code null} there, and a
 * respondent carrying no {@code masterDefendant} dereferences {@code null} again. The first two are
 * recorded as {@code NullPointerException} by the {@code synthetic__master-defendant-without-flags}
 * and {@code synthetic__respondents-absent} goldens; the third reaches no recorded case and is
 * synthesised from the one that does. An absent flag is read here as not set, an absent respondent
 * list as no respondents, and a respondent who is nobody is passed over, so each answers
 * {@code Applicant}: one unreadable application must not cost every child on the register their
 * entry.
 *
 * <p>The respondents-absent shape is the one that matters: {@code respondents} is <em>not</em> in
 * {@code courtApplication.json}'s required list, so a hearing carrying it is in contract, and where
 * progression's command fails this service records a register. The answers are pinned by
 * {@code DefendantTypeResolverTest.the_shapes_progression_throws_on_are_answered_applicant} and
 * {@code …a_respondent_without_a_master_defendant_is_answered_applicant}, which P10 names as its
 * pinning tests; the row carries the sign-off-before-cutover marker, and this class no longer owes
 * the register anything.
 *
 * <p>Pure, and a singleton for it: reference data is not consulted, no clock is read, and nothing it
 * is handed is edited (constitution Principle V).
 */
// PMD.OnlyOneReturn: the branches answer where the legacy expression they port answers - the
// applicant guard, the master-defendant fork and the lookup each end the question they ask, and
// funnelling them through one exit would reshape the control flow this port is reviewed against.
@SuppressWarnings("PMD.OnlyOneReturn")
public class DefendantTypeResolver {

    /** The default the rule starts from and returns to whenever nothing else is proved. */
    private static final String APPLICANT_TYPE = "Applicant";

    /** The applicant of an appeal who is themselves a master defendant. */
    private static final String APPELLANT_TYPE = "Appellant";

    /** A master defendant the register covers who is responding to somebody else's application. */
    private static final String RESPONDENT_TYPE = "Respondent";

    /** The hearing's court applications, which the document's application id is looked up in. */
    private static final String COURT_APPLICATIONS = "courtApplications";

    /** The identity of a court application on the hearing. */
    private static final String ID = "id";

    /** The party who brought the application. */
    private static final String APPLICANT = "applicant";

    /** The parties the application is brought against. */
    private static final String RESPONDENTS = "respondents";

    /** The party's master defendant, where the party is one. */
    private static final String MASTER_DEFENDANT = "masterDefendant";

    /** The identity a defendant is known by across cases and applications. */
    private static final String MASTER_DEFENDANT_ID = "masterDefendantId";

    /** The application's type, which carries the two flags an appeal is proved by. */
    private static final String TYPE = "type";

    /** Whether the application is an appeal. */
    private static final String APPEAL_FLAG = "appealFlag";

    /** Whether an appeal's applicant is its appellant. */
    private static final String APPLICANT_APPELLANT_FLAG = "applicantAppellantFlag";

    /**
     * The defendant type this register's hearing puts its defendants in.
     *
     * @param hearing  the hearing as the claim-check payload carries it, whose
     *                 {@code courtApplications[]} the application is looked up in
     * @param document the assembled register, which names the application through its first
     *                 defendant and carries the master defendant ids a respondent is matched against
     * @return {@code Applicant}, {@code Appellant} or {@code Respondent}, or empty where the hearing
     *         carried no court application for this register
     */
    public Optional<String> resolve(final JsonNode hearing, final CourtRegisterDocument document) {
        return courtApplicationId(document)
                .map(applicationId ->
                        defendantType(courtApplication(hearing, applicationId), document));
    }

    /**
     * The application the register is about, as {@code getCourtApplicationId} names it.
     *
     * <p>The first defendant's first case-or-application entry and nothing else: element zero is the
     * prosecution case wherever the register covers one, which carries no application id, so most
     * registers answer nothing here and take no defendant type at all.
     *
     * @param document the assembled register
     * @return the application id, or empty where the register names none
     */
    private static Optional<String> courtApplicationId(final CourtRegisterDocument document) {
        final List<CourtRegisterDefendant> defendants = document.defendants();
        if (defendants == null || defendants.isEmpty()) {
            return Optional.empty();
        }
        final List<CourtRegisterCaseOrApplication> casesOrApplications =
                defendants.get(0).prosecutionCasesOrApplications();
        if (casesOrApplications == null || casesOrApplications.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(casesOrApplications.get(0).courtApplicationId());
    }

    /**
     * The hearing's own copy of that application.
     *
     * @param hearing         the hearing payload
     * @param applicationId   the application the register names
     * @return the application, or {@code null} where the hearing carries no such application
     */
    private static JsonNode courtApplication(final JsonNode hearing, final String applicationId) {
        for (final JsonNode application : Json.array(hearing, COURT_APPLICATIONS)) {
            if (applicationId.equals(Json.text(application, ID))) {
                return application;
            }
        }
        return null;
    }

    /**
     * The rule itself, clause for clause.
     *
     * @param application the application the register names, or {@code null}
     * @param document    the assembled register
     * @return the defendant type
     */
    private static String defendantType(final JsonNode application,
                                        final CourtRegisterDocument document) {

        final JsonNode applicant = Json.at(application, APPLICANT);
        if (!Json.truthy(applicant)) {
            return APPLICANT_TYPE;
        }
        if (Json.truthy(applicant, MASTER_DEFENDANT)) {
            return appeal(application) ? APPELLANT_TYPE : APPLICANT_TYPE;
        }
        return respondsOnThisRegister(application, document) ? RESPONDENT_TYPE : APPLICANT_TYPE;
    }

    /**
     * Whether the application is an appeal whose applicant is its appellant.
     *
     * @param application the application the register names
     * @return whether both flags are set
     */
    private static boolean appeal(final JsonNode application) {
        final JsonNode type = Json.at(application, TYPE);
        return Json.truthy(type, APPEAL_FLAG) && Json.truthy(type, APPLICANT_APPELLANT_FLAG);
    }

    /**
     * Whether one of the application's respondents is a master defendant this register covers.
     *
     * <p>A respondent who is not a master defendant is passed over rather than matched against a
     * defendant who has no master id of their own: progression dereferences that respondent and
     * throws, and answering "matched" for two absent identities is the one reading it never gives.
     * That is the third of the shapes P10 names, and it is pinned by
     * {@code DefendantTypeResolverTest.a_respondent_without_a_master_defendant_is_answered_applicant}.
     *
     * @param application the application the register names
     * @param document    the assembled register, whose defendants carry the ids matched against
     * @return whether any respondent is on the register
     */
    private static boolean respondsOnThisRegister(final JsonNode application,
                                                  final CourtRegisterDocument document) {

        final List<String> onTheRegister = document.defendants().stream()
                .map(CourtRegisterDefendant::masterDefendantId)
                .toList();
        return Json.array(application, RESPONDENTS).stream()
                .map(respondent -> Json.text(Json.at(respondent, MASTER_DEFENDANT),
                        MASTER_DEFENDANT_ID))
                .filter(Objects::nonNull)
                .anyMatch(onTheRegister::contains);
    }
}
