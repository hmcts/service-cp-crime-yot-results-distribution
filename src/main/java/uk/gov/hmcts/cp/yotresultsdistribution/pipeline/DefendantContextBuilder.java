package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterResult;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ResultLevel;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationFailedException;

/**
 * Gathers a hearing's judicial results into one context per defendant.
 *
 * <p>Ports the shared kernel's {@code DefendantContextService} in the court register's own
 * configuration — {@code new DefendantContextService(hearingObj, true)}
 * ({@code SetCourtRegister/index.js:33}), which is {@code isRegister = true} and
 * {@code isInformantRegister = false}. That is the only configuration this service has; the
 * one-argument and three-argument calls the legacy Jest suite also makes belong to the NOWs and
 * informant-register flows, which are not ported here.
 *
 * <p>Four passes, in the legacy's order: defendant-case and offence results from the prosecution
 * cases, then the court applications, then the hearing's defendant-level results. A context with no
 * master defendant id is dropped at the end ({@code :48-53}), and the ordered date is the latest of
 * the results the context gathered.
 *
 * <p><strong>Defect C22 is fixed here.</strong> The legacy's eligibility gate
 * ({@code DefendantContextBaseService.js:179-187}) reads
 * {@code courtApplication.subject.masterDefendant !== undefined} and nothing else when
 * {@code isInformantRegister} is false — so a court application brought by anyone at all, a
 * defence-initiated application included, contributes its results to the register. The mapper's own
 * comment says the check is "applicant is prosecutingAuthority and subject is masterDefendant"; only
 * the subject half was ever written. This builder requires <strong>both</strong>, which is what the
 * comment claims, what the informant register enforces, and what the fix register records as C22.
 *
 * <p>The fixed gate is also total where the legacy's is not: an application carrying no
 * {@code applicant} at all, or no {@code subject}, is <em>not eligible</em> rather than a throw that
 * would lose the whole hearing's register. Answering the question the gate asks is the fix; dying on
 * the payload shape it is asked about is not.
 *
 * <p><strong>The legacy's write-backs onto the payload are not reproduced.</strong> Each pass
 * assigns {@code level}, {@code prosecutionCaseId}, {@code offenceId} and {@code offenceTitle} onto
 * the very {@code judicialResult} object it was handed — the producer's tree, which this service
 * does not own (constitution Principle IV) and which the Durable Functions serialisation boundary is
 * all that keeps the next activity from seeing edited. Nothing on the court-register path reads
 * them: every mapper that scopes by level, case or offence reads those from the gathered
 * {@code Result} wrapper ({@code OffenceMapper.js:26},
 * {@code ProsecutionCaseOrApplicationMapper.js:27,70}, {@code YouthDefendantMapper.js:105}), and the
 * offence title is read from the offence itself ({@code OffenceMapper.js:16}). So the gathered
 * result carries the producer's judicial result exactly as it arrived.
 */
// PMD.OnlyOneReturn: the eligibility gate and the ordered-date lookup answer where the legacy
// clause they stand for answers; one exit would hide which half of the gate refused an application.
@SuppressWarnings("PMD.OnlyOneReturn")
public final class DefendantContextBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(DefendantContextBuilder.class);

    private static final String ID = "id";
    private static final String OFFENCES = "offences";
    private static final String OFFENCE = "offence";
    private static final String SUBJECT = "subject";
    private static final String MASTER_DEFENDANT = "masterDefendant";
    private static final String MASTER_DEFENDANT_ID = "masterDefendantId";
    private static final String JUDICIAL_RESULTS = "judicialResults";
    private static final String JUDICIAL_RESULT = "judicialResult";
    private static final String ORDERED_DATE = "orderedDate";
    private static final String IS_DELETED = "isDeleted";
    private static final String PROSECUTION_CASE_ID = "prosecutionCaseId";
    private static final String COURT_APPLICATION_CASES = "courtApplicationCases";
    private static final String COURT_ORDER = "courtOrder";
    private static final String COURT_ORDER_OFFENCES = "courtOrderOffences";

    private final JsonNode hearing;
    private final Dates dates;

    /**
     * Creates the builder for one hearing.
     *
     * @param hearing the hearing payload, exactly as the producer sent it
     * @param dates   the register's date handling, for the latest-ordered-date sort
     */
    public DefendantContextBuilder(final JsonNode hearing, final Dates dates) {
        this.hearing = hearing;
        this.dates = dates;
    }

    /**
     * Gathers the hearing's defendants.
     *
     * @return one context per defendant carrying a master defendant id, in the order the legacy
     *     gathers them
     * @throws uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationFailedException if the payload
     *     cannot be read
     */
    /* default */ List<DefendantContext> build() {
        // A map keyed by master defendant id, nulls included: the legacy keys its Map with
        // `defendant.masterDefendantId` whatever that is, so every defendant carrying none shares
        // one entry — which is then dropped, along with everything gathered onto it.
        final Map<String, DefendantContext> gathered = new LinkedHashMap<>();

        gatherFromProsecutionCases(gathered);
        gatherFromCourtApplications(gathered);
        gatherDefendantLevelResults(gathered);

        final List<DefendantContext> defendants = new ArrayList<>(gathered.size());
        for (final DefendantContext defendant : gathered.values()) {
            if (named(defendant.masterDefendantId())) {
                defendant.orderedDate(latestOrderedDate(defendant));
                defendants.add(defendant);
            }
        }
        return defendants;
    }

    /**
     * The first pass: every defendant of every prosecution case, their case-level results and then
     * their offence-level ones ({@code DefendantContextBaseService.js:58-137}).
     *
     * @param gathered the contexts gathered so far
     */
    private void gatherFromProsecutionCases(final Map<String, DefendantContext> gathered) {
        for (final JsonNode prosecutionCase : Json.array(hearing, "prosecutionCases")) {
            final String caseId = Json.text(prosecutionCase, ID);
            for (final JsonNode member : Json.dereferencedArray(prosecutionCase, "defendants")) {
                gatherDefendant(gathered, caseId,
                        Json.dereferencedElement(member, "defendants"));
            }
        }
    }

    /**
     * One defendant of one prosecution case.
     *
     * @param gathered  the contexts gathered so far
     * @param caseId    the prosecution case's id
     * @param defendant the defendant
     */
    private void gatherDefendant(
            final Map<String, DefendantContext> gathered,
            final String caseId,
            final JsonNode defendant) {

        final String masterDefendantId = Json.text(defendant, MASTER_DEFENDANT_ID);
        final DefendantContext context =
                gathered.computeIfAbsent(masterDefendantId, key -> new DefendantContext());
        final String defendantId = Json.text(defendant, ID);

        context.cases().add(caseId);
        context.defendantIds().add(defendantId);

        if (Json.truthy(defendant, "defendantCaseJudicialResults")) {
            context.addResults(
                    caseLevelResults(defendant, caseId, defendantId, masterDefendantId));
        }
        context.addResults(offenceLevelResults(defendant, caseId, defendantId, masterDefendantId));

        if (!named(context.masterDefendantId())) {
            // `if (!defendantBase.masterDefendantId)` — the identity and the youth flag are set once,
            // together, from the first record that names the defendant. A later case naming the same
            // master defendant does not revise either.
            context.masterDefendantId(masterDefendantId);
            context.youthDefendant(Json.truthy(defendant, "isYouth"));
        }
    }

    /**
     * The results a prosecution case recorded against the defendant rather than against an offence.
     *
     * @param defendant         the defendant
     * @param caseId            the prosecution case's id
     * @param defendantId       the case-scoped defendant id
     * @param masterDefendantId the defendant's identity across cases
     * @return the gathered results
     */
    private List<RegisterResult> caseLevelResults(
            final JsonNode defendant,
            final String caseId,
            final String defendantId,
            final String masterDefendantId) {

        final List<RegisterResult> results = new ArrayList<>();
        for (final JsonNode member : Json.array(defendant, "defendantCaseJudicialResults")) {
            final JsonNode judicialResult =
                    Json.dereferencedElement(member, "defendantCaseJudicialResults");
            if (!Json.truthy(judicialResult, IS_DELETED)) {
                results.add(new RegisterResult(caseId, defendantId,
                        Json.text(judicialResult, "offenceId"), null, ResultLevel.CASE,
                        masterDefendantId, judicialResult, null, null));
            }
        }
        return results;
    }

    /**
     * The results the defendant's own offences were ordered against.
     *
     * @param defendant         the defendant
     * @param caseId            the prosecution case's id
     * @param defendantId       the case-scoped defendant id
     * @param masterDefendantId the defendant's identity across cases
     * @return the gathered results
     */
    private List<RegisterResult> offenceLevelResults(
            final JsonNode defendant,
            final String caseId,
            final String defendantId,
            final String masterDefendantId) {

        final List<RegisterResult> results = new ArrayList<>();
        for (final JsonNode member : Json.dereferencedArray(defendant, OFFENCES)) {
            final JsonNode offence = Json.dereferencedElement(member, OFFENCES);
            for (final JsonNode result : Json.array(offence, JUDICIAL_RESULTS)) {
                final JsonNode judicialResult = Json.dereferencedElement(result, JUDICIAL_RESULTS);
                if (!Json.truthy(judicialResult, IS_DELETED)) {
                    results.add(new RegisterResult(caseId, defendantId, Json.text(offence, ID),
                            null, ResultLevel.OFFENCE, masterDefendantId, judicialResult,
                            null, null));
                }
            }
        }
        return results;
    }

    /**
     * The second pass: every court application the fixed C22 gate admits
     * ({@code DefendantContextBaseService.js:139-177}).
     *
     * @param gathered the contexts gathered so far
     */
    private void gatherFromCourtApplications(final Map<String, DefendantContext> gathered) {
        for (final JsonNode member : Json.array(hearing, "courtApplications")) {
            final JsonNode application = Json.dereferencedElement(member, "courtApplications");
            if (isEligible(application)) {
                gatherApplication(gathered, application);
            }
        }
    }

    /**
     * Whether a court application contributes to this register — defect fix C22.
     *
     * <p>Both halves of the check the legacy's own comment describes: the subject is a master
     * defendant, <em>and</em> the applicant is a prosecuting authority. The legacy evaluates only the
     * first for this flow, so an application brought by a co-defendant, by the defence, or by any
     * third party puts its results on a register of the court's prosecutions.
     *
     * @param application the court application
     * @return whether it is eligible
     */
    private static boolean isEligible(final JsonNode application) {
        final JsonNode subject = Json.at(application, SUBJECT);
        final JsonNode applicant = Json.at(application, "applicant");
        return Json.truthy(subject, MASTER_DEFENDANT)
                && Json.truthy(applicant, "prosecutingAuthority");
    }

    /**
     * One eligible court application: its own results, its cases' offence results, and its court
     * order's.
     *
     * @param gathered    the contexts gathered so far
     * @param application the court application
     */
    private void gatherApplication(
            final Map<String, DefendantContext> gathered, final JsonNode application) {

        final JsonNode masterDefendant =
                Json.at(Json.dereferenced(application, SUBJECT), MASTER_DEFENDANT);
        final String subjectId = Json.text(masterDefendant, MASTER_DEFENDANT_ID);
        final DefendantContext context = gathered.getOrDefault(subjectId, new DefendantContext());

        context.defendantIds().add(subjectId);
        context.applications().add(Json.text(application, ID));

        addApplicationCases(context, application);
        context.addResults(applicationLevelResults(application, subjectId));
        context.addResults(applicationCaseResults(application, subjectId));
        context.addResults(courtOrderResults(application, subjectId));

        if (!named(context.masterDefendantId())) {
            context.masterDefendantId(subjectId);
            context.youthDefendant(Json.truthy(masterDefendant, "isYouth"));
            gathered.put(subjectId, context);
        }
    }

    /**
     * The prosecution cases an application reaches, whether through its own case list or through its
     * court order's offences. Each is added once.
     *
     * @param context     the defendant's context
     * @param application the court application
     */
    private static void addApplicationCases(
            final DefendantContext context, final JsonNode application) {

        for (final JsonNode applicationCase : Json.array(application, COURT_APPLICATION_CASES)) {
            addCaseOnce(context, Json.text(applicationCase, PROSECUTION_CASE_ID));
        }
        if (Json.truthy(application, COURT_ORDER)) {
            for (final JsonNode courtOrderOffence : Json.dereferencedArray(
                    Json.at(application, COURT_ORDER), COURT_ORDER_OFFENCES)) {
                addCaseOnce(context, Json.text(courtOrderOffence, PROSECUTION_CASE_ID));
            }
        }
    }

    /**
     * Adds a prosecution case to the context unless it is already there, or unless there is none to
     * add.
     *
     * <p>{@code DefendantContextBaseService.js:151-165} pushes the id it was given whatever it is,
     * so an application case naming no prosecution case leaves a bare {@code undefined} on the
     * list. That reference is not fatal there: it reaches
     * {@code ProsecutionCaseOrApplicationMapper.js:27-33}, matches no prosecution case, and is
     * warned about and skipped by the SNI-9005 guard ({@code 0781bbc2}) — the register is filed
     * without that case. A {@code null} here would not travel as far: {@code RegisterDefendant}
     * copies the list, and a hearing the legacy files would become an unexpected failure on the
     * dead-letter queue instead. So the skip happens at the gather, in the words the mapper's own
     * guard uses.
     *
     * <p><strong>The line names no application</strong> (constitution Principle VII). This is a
     * parity warning — it exists because the legacy warns about the same reference one stage later —
     * and nothing on the fix register authorises it to carry an identifier. The permitted
     * correlation set is {@code requestId}, {@code hearingId}, {@code hearingDay}, {@code source},
     * the court centre's id or OU code, counts and timings; a court application's id is none of
     * them, and this stage is pure and holds no correlation of its own to offer instead. The
     * run-aware layers put {@code requestId} and {@code hearingId} on the line through the MDC,
     * which is where every correlation on a transformation's logs comes from.
     *
     * <p>Exactly one warning in this service names a court application, and it is a different one:
     * {@code ProsecutionCaseOrApplicationMapper}'s unresolvable-application line, which the C20
     * register row authorises in as many words. A row authorises one warning, not a class of them.
     *
     * @param context the defendant's context
     * @param caseId  the prosecution case's id, if the record named one
     */
    private static void addCaseOnce(final DefendantContext context, final String caseId) {
        if (caseId == null) {
            LOG.warn("[Case ID: null] - Prosecution case not found in hearingJson.prosecutionCases,"
                    + " skipping");
            return;
        }
        if (!context.cases().contains(caseId)) {
            context.cases().add(caseId);
        }
    }

    /**
     * The results the application itself was resulted with
     * ({@code DefendantContextBaseService.js:189-207}).
     *
     * @param application the court application
     * @param subjectId   the subject's master defendant id
     * @return the gathered results
     */
    private static List<RegisterResult> applicationLevelResults(
            final JsonNode application, final String subjectId) {

        final List<RegisterResult> results = new ArrayList<>();
        if (!Json.nonEmptyArray(application, JUDICIAL_RESULTS)) {
            return results;
        }
        final String applicationId = Json.text(application, ID);
        for (final JsonNode member : Json.array(application, JUDICIAL_RESULTS)) {
            final JsonNode judicialResult = Json.dereferencedElement(member, JUDICIAL_RESULTS);
            if (!Json.truthy(judicialResult, IS_DELETED)) {
                results.add(new RegisterResult(null, null,
                        Json.text(judicialResult, "offenceId"), applicationId,
                        ResultLevel.APPLICATION, subjectId, judicialResult,
                        Boolean.TRUE, Boolean.TRUE));
            }
        }
        return results;
    }

    /**
     * The results the offences of an application's cases were ordered against
     * ({@code DefendantContextBaseService.js:209-240}).
     *
     * <p>These are tagged {@link ResultLevel#OFFENCE} and not {@link ResultLevel#APPLICATION}, which
     * is the whole of what {@code isRegister} decides ({@code :220-224}) and is what lets the
     * outbound mappers scope an application's results to the offence they were ordered against.
     *
     * @param application the court application
     * @param subjectId   the subject's master defendant id
     * @return the gathered results
     */
    private static List<RegisterResult> applicationCaseResults(
            final JsonNode application, final String subjectId) {

        final List<RegisterResult> results = new ArrayList<>();
        final String applicationId = Json.text(application, ID);
        for (final JsonNode applicationCase : Json.array(application, COURT_APPLICATION_CASES)) {
            for (final JsonNode member : Json.array(applicationCase, OFFENCES)) {
                final JsonNode offence = Json.dereferencedElement(member, OFFENCES);
                results.addAll(offenceResults(offence, Json.text(offence, ID),
                        applicationId, subjectId));
            }
        }
        return results;
    }

    /**
     * The results an application's court order's offences were ordered against
     * ({@code DefendantContextBaseService.js:242-268}).
     *
     * @param application the court application
     * @param subjectId   the subject's master defendant id
     * @return the gathered results
     */
    private static List<RegisterResult> courtOrderResults(
            final JsonNode application, final String subjectId) {

        final List<RegisterResult> results = new ArrayList<>();
        if (!Json.truthy(application, COURT_ORDER)
                || !Json.truthy(Json.at(application, COURT_ORDER), COURT_ORDER_OFFENCES)) {
            return results;
        }
        final String applicationId = Json.text(application, ID);
        for (final JsonNode courtOrderOffence : Json.dereferencedArray(
                Json.at(application, COURT_ORDER), COURT_ORDER_OFFENCES)) {
            final JsonNode offence = Json.dereferenced(courtOrderOffence, OFFENCE);
            results.addAll(offenceResults(offence, Json.text(offence, ID),
                    applicationId, subjectId));
        }
        return results;
    }

    /**
     * The undeleted results of one offence reached through a court application.
     *
     * @param offence       the offence
     * @param offenceId     its id
     * @param applicationId the application the offence was reached through
     * @param subjectId     the subject's master defendant id
     * @return the gathered results
     */
    private static List<RegisterResult> offenceResults(
            final JsonNode offence,
            final String offenceId,
            final String applicationId,
            final String subjectId) {

        final List<RegisterResult> results = new ArrayList<>();
        for (final JsonNode member : Json.array(offence, JUDICIAL_RESULTS)) {
            final JsonNode judicialResult = Json.dereferencedElement(member, JUDICIAL_RESULTS);
            if (!Json.truthy(judicialResult, IS_DELETED)) {
                results.add(new RegisterResult(null, null, offenceId, applicationId,
                        ResultLevel.OFFENCE, subjectId, judicialResult, null, Boolean.TRUE));
            }
        }
        return results;
    }

    /**
     * The third pass: the results the hearing recorded against a defendant rather than against any
     * case or application ({@code DefendantContextBaseService.js:270-292}).
     *
     * @param gathered the contexts gathered so far
     */
    private void gatherDefendantLevelResults(final Map<String, DefendantContext> gathered) {
        for (final JsonNode member : Json.array(hearing, "defendantJudicialResults")) {
            final JsonNode entry = Json.dereferencedElement(member, "defendantJudicialResults");
            final String masterDefendantId = Json.text(entry, MASTER_DEFENDANT_ID);
            final DefendantContext context = gathered.get(masterDefendantId);
            if (context == null) {
                // `defendantBase.results` on an entry naming a defendant no case gathered — the
                // legacy dies here, and the whole hearing's register dies with it, unrecorded. One
                // classified failure instead (defect C2's rule applied to an uncatalogued throw).
                throw new TransformationFailedException(
                        "a defendant judicial result names a defendant the hearing did not gather");
            }
            final JsonNode judicialResult = Json.dereferenced(entry, JUDICIAL_RESULT);
            if (!Json.truthy(judicialResult, IS_DELETED)) {
                context.addResults(List.of(new RegisterResult(null, null,
                        Json.text(judicialResult, "offenceId"), null, ResultLevel.DEFENDANT,
                        masterDefendantId, judicialResult, null, null)));
            }
        }
    }

    /**
     * Whether a master defendant id is one JavaScript would call truthy.
     *
     * @param masterDefendantId the id to test; may be {@code null}
     * @return whether the legacy's {@code if (base.masterDefendantId)} would be entered
     */
    private static boolean named(final String masterDefendantId) {
        return masterDefendantId != null && !masterDefendantId.isEmpty();
    }

    /**
     * The latest date any of a context's gathered results was ordered
     * ({@code DefendantContextBaseService.js:294-298}).
     *
     * @param defendant the gathered defendant
     * @return the ordered date, or {@code null} where nothing named one
     */
    private String latestOrderedDate(final DefendantContext defendant) {
        final List<JsonNode> orderedDates = new ArrayList<>(defendant.results().size());
        for (final RegisterResult result : defendant.results()) {
            orderedDates.add(Json.at(result.judicialResult(), ORDERED_DATE));
        }
        return OrderedDates.latest(orderedDates, dates);
    }
}
