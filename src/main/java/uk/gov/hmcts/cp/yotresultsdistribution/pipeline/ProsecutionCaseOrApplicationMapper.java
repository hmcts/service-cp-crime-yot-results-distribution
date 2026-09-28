package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterCaseOrApplication;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterResult;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ResultLevel;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationAnomaly;

/**
 * Maps the cases and applications a defendant appeared on.
 *
 * <p>Ports {@code .../Mappers/ProsecutionCaseOrApplication/ProsecutionCaseOrApplicationMapper.js}.
 * Prosecution cases first, court applications second, concatenated ({@code :16-20}) — an order the
 * register prints and the comparator holds to. Each carries its reference, its case- or
 * application-level results, its offences, its counsel and the defendant's ASN.
 *
 * <p><strong>Three catalogued defects and an asymmetry.</strong> The case path was guarded against a
 * missing case by SNI-9005 and warns and skips; the application path was left unguarded, so an
 * absent {@code courtApplications} array or an unmatched application id throws and kills the whole
 * register (C20). {@code getASN} filters on {@code d.personDefendant.arrestSummonsNumber} with no
 * guard, so a legal-entity record carrying this defendant's own master id throws the same way
 * (C21). And the comment at {@code :64} says the applicant must be a prosecuting authority while the
 * code checks only the subject (C22) — the highest-value content question on the register.
 *
 * <p>The guarded skips are counted through {@code anomalies} rather than failing the transformation:
 * one unresolvable application must not cost a child their entry on the register, and it must not be
 * invisible either. <strong>The case path's skip stays uncounted</strong>: SNI-9005's guard predates
 * the court register and C20 names only the application it left unguarded, so counting this one too
 * would be a behaviour change nothing on the register authorises. The asymmetry is deliberate and is
 * asserted, so changing it later has to be a decision.
 *
 * <p>An application whose applicant prosecutes nothing, or whose subject is another defendant, is
 * <em>ineligible</em> rather than unresolvable: it is skipped in silence, because there is nothing
 * wrong with the payload. Only a dangling reference is counted.
 *
 * <p>Two legacy methods are dead — {@code getApplicationReference} and
 * {@code getRespondentCounsels}, called from nowhere (C26). They are not reproduced.
 */
// PMD.OnlyOneReturn: the reference fallback, the ASN search and the eligibility gate each answer
// where the legacy expression they port answers.
@SuppressWarnings("PMD.OnlyOneReturn")
final class ProsecutionCaseOrApplicationMapper {

    private static final Logger LOG =
            LoggerFactory.getLogger(ProsecutionCaseOrApplicationMapper.class);

    /** The identity a defendant is known by across cases and applications. */
    private static final String MASTER_DEFENDANT_ID = "masterDefendantId";

    /** The person half of a defendant record; a legal entity carries none. */
    private static final String PERSON_DEFENDANT = "personDefendant";

    /** The defendant's arrest summons number, where they are a person and carry one. */
    private static final String ARREST_SUMMONS_NUMBER = "arrestSummonsNumber";

    /** The case- or application-scoped defendant records. */
    private static final String DEFENDANTS = "defendants";

    /** The identity of a case, an application or a counsel. */
    private static final String ID = "id";

    /** The application's subject, whose master defendant the register is about. */
    private static final String SUBJECT = "subject";

    /** The subject's master defendant. */
    private static final String MASTER_DEFENDANT = "masterDefendant";

    /** Where a skipped case or application is counted; called once per skip. */
    private final Consumer<TransformationAnomaly> anomalies;

    /**
     * Creates the mapper.
     *
     * @param anomalyRecorder where each guarded skip is counted
     */
    /* default */ ProsecutionCaseOrApplicationMapper(
            final Consumer<TransformationAnomaly> anomalyRecorder) {
        this.anomalies = anomalyRecorder;
    }

    /**
     * Maps one defendant's cases and applications.
     *
     * @param registerDefendant the gathered defendant, carrying the case and application ids and the
     *                          level-tagged results scoped onto them
     * @param hearing           the hearing payload
     * @return the mapped cases and applications, cases first
     */
    /* default */ List<CourtRegisterCaseOrApplication> map(
            final RegisterDefendant registerDefendant, final JsonNode hearing) {

        final List<CourtRegisterCaseOrApplication> mapped =
                new ArrayList<>(prosecutionCases(registerDefendant, hearing));
        mapped.addAll(courtApplications(registerDefendant, hearing));
        return List.copyOf(mapped);
    }

    /**
     * The prosecution cases the defendant was gathered from, in the order the fragment names them.
     *
     * @param registerDefendant the gathered defendant
     * @param hearing           the hearing payload
     * @return the mapped cases
     */
    private List<CourtRegisterCaseOrApplication> prosecutionCases(
            final RegisterDefendant registerDefendant, final JsonNode hearing) {

        final List<CourtRegisterCaseOrApplication> mapped = new ArrayList<>();
        if (!Json.nonEmptyArray(hearing, "prosecutionCases")) {
            return mapped;
        }
        final List<JsonNode> cases = Json.array(hearing, "prosecutionCases");
        for (final String caseId : registerDefendant.cases()) {
            final JsonNode prosecutionCase = identified(cases, caseId);
            if (prosecutionCase == null) {
                // SNI-9005's guard (`0781bbc2`), kept as it stands — warned about and not
                // counted. C20 names the application path; this one was already guarded.
                //
                // The id the legacy's message quotes is not repeated (Principle VII). A prosecution
                // case id is outside the permitted correlation set, and no register row authorises
                // one here: C20 authorises an identifier in the application warning below, which is
                // a different line about a different reference. What an operator acts on is that
                // this guard fired, and `requestId`/`hearingId` reach the line through the MDC.
                LOG.warn("[Case ID: unnamed] - Prosecution case not found in "
                        + "hearingJson.prosecutionCases, skipping");
                continue;
            }
            mapped.add(new CourtRegisterCaseOrApplication(
                    caseReference(prosecutionCase),
                    null,
                    null,
                    OffenceMapper.map(caseOffences(prosecutionCase, registerDefendant),
                            registerDefendant),
                    ResultMapper.map(scopedResults(
                            registerDefendant, ResultLevel.CASE, RegisterResult::prosecutionCaseId,
                            caseId)),
                    CounselMapper.map(prosecutionCounsels(hearing, prosecutionCase)),
                    arrestSummonsNumber(prosecutionCase, registerDefendant)));
        }
        return mapped;
    }

    /**
     * The court applications the defendant was gathered from — defect fixes C20 and C22.
     *
     * @param registerDefendant the gathered defendant
     * @param hearing           the hearing payload
     * @return the mapped applications
     */
    private List<CourtRegisterCaseOrApplication> courtApplications(
            final RegisterDefendant registerDefendant, final JsonNode hearing) {

        final List<CourtRegisterCaseOrApplication> mapped = new ArrayList<>();
        final List<JsonNode> applications = Json.array(hearing, "courtApplications");
        for (final String applicationId : registerDefendant.applications()) {
            final JsonNode application = identified(applications, applicationId);
            if (application == null) {
                // C20: `this.hearingJson.courtApplications.find(...)` with no array guard and no
                // result guard. An absent array or a dangling id is a TypeError there and the whole
                // hearing's register goes with it; here it is one skipped reference, said out loud.
                LOG.warn("Court application not found on the hearing, skipping. "
                                + "applicationId={} reason={}",
                        applicationId, TransformationAnomaly.UNRESOLVABLE_APPLICATION.value());
                anomalies.accept(TransformationAnomaly.UNRESOLVABLE_APPLICATION);
                continue;
            }
            if (!eligible(application, registerDefendant)) {
                continue;
            }
            mapped.add(new CourtRegisterCaseOrApplication(
                    Json.text(application, "applicationReference"),
                    Json.text(application, ID),
                    Json.text(Json.at(application, "type"), "type"),
                    OffenceMapper.map(applicationOffences(application), registerDefendant),
                    ResultMapper.map(scopedResults(
                            registerDefendant, ResultLevel.APPLICATION,
                            RegisterResult::applicationId, applicationId)),
                    CounselMapper.map(applicantCounsels(
                            hearing, Json.text(Json.at(application, "applicant"), ID))),
                    applicationArrestSummonsNumber(application, registerDefendant)));
        }
        return mapped;
    }

    /**
     * Whether this application belongs on this defendant's register — defect fix C22.
     *
     * <p>Both halves of the check the legacy's own comment describes: the subject is this defendant,
     * <em>and</em> the applicant is a prosecuting authority. The legacy evaluates only the first, so
     * a defence-initiated application reaches the register. The context builder applies the same
     * rule upstream; the mapper is held to it too, because it is the rule the code claims and the
     * two gates are read by different people.
     *
     * @param application       the court application
     * @param registerDefendant the gathered defendant
     * @return whether the application is this register's business
     */
    private static boolean eligible(
            final JsonNode application, final RegisterDefendant registerDefendant) {

        final JsonNode masterDefendant =
                Json.at(Json.at(application, SUBJECT), MASTER_DEFENDANT);
        return Json.truthy(masterDefendant)
                && Json.truthy(Json.at(application, "applicant"), "prosecutingAuthority")
                && registerDefendant.masterDefendantId()
                        .equals(Json.text(masterDefendant, MASTER_DEFENDANT_ID));
    }

    /**
     * The case's reference: its URN, or the prosecuting authority's own reference where it has none.
     *
     * @param prosecutionCase the prosecution case
     * @return the reference
     */
    private static String caseReference(final JsonNode prosecutionCase) {
        final JsonNode identifier = Json.dereferenced(prosecutionCase, "prosecutionCaseIdentifier");
        return Json.truthy(identifier, "caseURN")
                ? Json.text(identifier, "caseURN")
                : Json.text(identifier, "prosecutionAuthorityReference");
    }

    /**
     * This defendant's offences on the case, gathered across every record carrying their master id.
     *
     * @param prosecutionCase   the prosecution case
     * @param registerDefendant the gathered defendant
     * @return the payload offences, in payload order
     */
    private static List<JsonNode> caseOffences(
            final JsonNode prosecutionCase, final RegisterDefendant registerDefendant) {

        final List<JsonNode> offences = new ArrayList<>();
        for (final JsonNode defendant : Json.array(prosecutionCase, DEFENDANTS)) {
            if (registerDefendant.masterDefendantId()
                    .equals(Json.text(defendant, MASTER_DEFENDANT_ID))) {
                offences.addAll(Json.array(defendant, "offences"));
            }
        }
        return offences;
    }

    /**
     * An application's offences: its cases' offences first, then its court order's — defect fix
     * C26's {@code courtApplicationId} sits on the record these hang off.
     *
     * @param application the court application
     * @return the payload offences, in the order the legacy gathers them
     */
    private static List<JsonNode> applicationOffences(final JsonNode application) {
        final List<JsonNode> offences = new ArrayList<>();
        for (final JsonNode applicationCase : Json.array(application, "courtApplicationCases")) {
            offences.addAll(Json.array(applicationCase, "offences"));
        }
        for (final JsonNode courtOrderOffence
                : Json.array(Json.at(application, "courtOrder"), "courtOrderOffences")) {
            offences.add(Json.at(courtOrderOffence, "offence"));
        }
        return offences;
    }

    /**
     * The defendant's arrest summons number off their own case record — defect fix C21.
     *
     * <p>{@code d.personDefendant.arrestSummonsNumber} with no guard on {@code personDefendant}: a
     * legal-entity record carrying this defendant's own master id throws, and the register goes with
     * it. Such a record contributes no ASN and the search carries on to one that does.
     *
     * @param prosecutionCase   the prosecution case
     * @param registerDefendant the gathered defendant
     * @return the ASN, or {@code null} where no record of theirs carries one
     */
    private static String arrestSummonsNumber(
            final JsonNode prosecutionCase, final RegisterDefendant registerDefendant) {

        for (final JsonNode defendant : Json.array(prosecutionCase, DEFENDANTS)) {
            if (!registerDefendant.masterDefendantId()
                    .equals(Json.text(defendant, MASTER_DEFENDANT_ID))) {
                continue;
            }
            final JsonNode personDefendant = Json.at(defendant, PERSON_DEFENDANT);
            if (Json.truthy(personDefendant, ARREST_SUMMONS_NUMBER)) {
                return Json.text(personDefendant, ARREST_SUMMONS_NUMBER);
            }
        }
        return null;
    }

    /**
     * The defendant's arrest summons number off an application's subject.
     *
     * @param application       the court application
     * @param registerDefendant the gathered defendant
     * @return the ASN, or {@code null}
     */
    private static String applicationArrestSummonsNumber(
            final JsonNode application, final RegisterDefendant registerDefendant) {

        final JsonNode masterDefendant = Json.at(Json.at(application, SUBJECT), MASTER_DEFENDANT);
        if (!registerDefendant.masterDefendantId()
                .equals(Json.text(masterDefendant, MASTER_DEFENDANT_ID))) {
            return null;
        }
        return Json.text(Json.at(masterDefendant, PERSON_DEFENDANT), ARREST_SUMMONS_NUMBER);
    }

    /**
     * The counsel prosecuting this case.
     *
     * @param hearing         the hearing payload
     * @param prosecutionCase the prosecution case
     * @return the counsel records, in payload order
     */
    private static List<JsonNode> prosecutionCounsels(
            final JsonNode hearing, final JsonNode prosecutionCase) {

        return counselsNaming(
                Json.array(hearing, "prosecutionCounsels"),
                "prosecutionCases",
                Json.text(prosecutionCase, ID));
    }

    /**
     * The counsel appearing for this application's applicant.
     *
     * @param hearing     the hearing payload
     * @param applicantId the applicant's id
     * @return the counsel records, in payload order
     */
    private static List<JsonNode> applicantCounsels(
            final JsonNode hearing, final String applicantId) {

        return counselsNaming(Json.array(hearing, "applicantCounsels"), "applicants", applicantId);
    }

    /**
     * The counsel records whose named list includes the given id.
     *
     * @param counsels the hearing's counsel records of one kind
     * @param named    the field naming what each counsel appears for
     * @param id       the case or applicant id to look for
     * @return the matching records, in payload order
     */
    private static List<JsonNode> counselsNaming(
            final List<JsonNode> counsels, final String named, final String id) {

        final List<JsonNode> matching = new ArrayList<>();
        for (final JsonNode counsel : counsels) {
            for (final JsonNode appearsFor : Json.array(counsel, named)) {
                if (appearsFor.isString() && appearsFor.stringValue().equals(id)) {
                    matching.add(counsel);
                    break;
                }
            }
        }
        return matching;
    }

    /**
     * The gathered results recorded at one level against one case or application.
     *
     * @param registerDefendant the gathered defendant carrying the level-tagged results
     * @param level             the level to scope by
     * @param scope             the result's own id at that level
     * @param id                the case or application id
     * @return the judicial results, in the order they were gathered
     */
    private static List<JsonNode> scopedResults(
            final RegisterDefendant registerDefendant,
            final ResultLevel level,
            final Function<RegisterResult, String> scope,
            final String id) {

        return registerDefendant.results().stream()
                .filter(result -> result.level() == level)
                .filter(result -> Objects.equals(scope.apply(result), id))
                .map(RegisterResult::judicialResult)
                .toList();
    }

    /**
     * The member of a list whose {@code id} is the one given.
     *
     * @param candidates the cases or applications the hearing carries
     * @param id         the id to look for
     * @return the match, or {@code null} where the hearing carries none
     */
    private static JsonNode identified(final List<JsonNode> candidates, final String id) {
        for (final JsonNode candidate : candidates) {
            if (id.equals(Json.text(candidate, ID))) {
                return candidate;
            }
        }
        return null;
    }
}
