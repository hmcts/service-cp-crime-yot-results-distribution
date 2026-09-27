package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterResult;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterVocabulary;

/**
 * Computes one defendant's vocabulary — the eighteen facts a NOW subscription is matched against.
 *
 * <p>Ports the shared kernel's {@code VocabularyService} in the court register's own construction,
 * {@code new VocabularyService(hearingObj, defendantContextBase)}
 * ({@code SetCourtRegister/index.js:65}). Two arguments, not four: the major-creditor map and the
 * compliance-enforcement list belong to the NOWs and enforcement flows, and their absence is what
 * makes both creditor lists unconditionally empty here.
 *
 * <p>Custody is read from every prosecution case <em>and</em> every court application whose subject
 * is this defendant ({@code VocabularyService.js:194-244}) — the application scan is not gated on
 * the eligibility C22 fixes, because it is a fact about where the defendant is held rather than
 * about whose application it is. Attendance counts only days on which one of this defendant's own
 * results was ordered, and only attendance records naming one of their own defendant ids
 * ({@code :245-274}).
 *
 * <p><strong>Defect C30's vocabulary half is here.</strong> The two creditor lists are always
 * empty, and are carried as present-and-empty so the matcher can tell empty from absent — the
 * matcher half of the fix, where {@code anyMajorCreditor} is vacuously true on an empty list while
 * its two siblings can never match at all, belongs to {@code SubscriptionRules}.
 *
 * <p>Two of the eighteen are constants and are written as constants. {@code adultOrYouthDefendant}
 * is {@code youth || !youth} and {@code anyCourtHearing} is {@code welsh || !welsh}
 * ({@code :163,166}); reference data still declares them as predicates a subscription may require,
 * which is why they are carried rather than dropped.
 */
// PMD.OnlyOneReturn: the searches below answer at the element that decides them, which is what the
// legacy's labelled `break` statements do; one exit would turn each into a full scan and lose the
// short-circuit the legacy is written around.
@SuppressWarnings("PMD.OnlyOneReturn")
public final class VocabularyBuilder {

    /** The prompt reference that makes a result custodial ({@code PromptTypesConstant.PRISON}). */
    private static final String PRISON_PROMPT = "prisonOrganisationName";

    /** {@code LocationTypeEnum.POLICE_STATION}. */
    private static final String POLICE_STATION = "Police Station";

    /** {@code LocationTypeEnum.PRISON}. */
    private static final String PRISON = "Prison";

    private static final String COURT_CENTRE = "courtCentre";
    private static final String PROSECUTION_CASES = "prosecutionCases";
    private static final String MASTER_DEFENDANT_ID = "masterDefendantId";
    private static final String PERSON_DEFENDANT = "personDefendant";
    private static final String CUSTODIAL_ESTABLISHMENT = "custodialEstablishment";
    private static final String CUSTODY = "custody";
    private static final String PROMPT_REFERENCE = "promptReference";
    private static final String JUDICIAL_RESULT_PROMPTS = "judicialResultPrompts";
    private static final String ORDERED_DATE = "orderedDate";

    private final JsonNode hearing;

    /**
     * Creates the builder for one hearing.
     *
     * @param hearing the hearing payload, exactly as the producer sent it
     */
    public VocabularyBuilder(final JsonNode hearing) {
        this.hearing = hearing;
    }

    /**
     * Computes the vocabulary of one gathered defendant.
     *
     * @param defendant the gathered defendant
     * @return their vocabulary
     * @throws uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationFailedException if the payload
     *     cannot be read
     */
    /* default */ RegisterVocabulary build(final DefendantContext defendant) {
        final Custody custody = custodyOf(defendant);
        final Attendance attendance = attendanceOf(defendant);

        final boolean custodialResult = hasCustodialResult(defendant);
        final boolean allNonCustodialResults = !custodialResult;
        // `let atleastOneNonCustodialResult = allNonCustodialResults; if (!it) it = recompute()` —
        // so a defendant with no prompts at all reports a non-custodial result without one being
        // looked for, and only a custodial defendant is scanned for the other kind.
        final boolean nonCustodialResult =
                allNonCustodialResults || hasNonCustodialResult(defendant);

        final boolean youth = Boolean.TRUE.equals(defendant.youthDefendant());
        final boolean welsh =
                Json.truthy(Json.dereferenced(hearing, COURT_CENTRE), "welshCourtCentre");

        return new RegisterVocabulary(
                custody.police(),
                custody.prison(),
                custodialResult,
                allNonCustodialResults,
                nonCustodialResult,
                attendance.inPerson(),
                attendance.byVideoLink(),
                cpsProsecuted(),
                attendance.byVideoLink() || attendance.inPerson(),
                custody.prison() || custody.police(),
                youth,
                !youth,
                true,
                welsh,
                !welsh,
                true,
                List.of(),
                List.of());
    }

    /**
     * Where the defendant is held, read from the hearing's cases and then from its applications.
     *
     * @param defendant the gathered defendant
     * @return the two custody flags
     */
    private Custody custodyOf(final DefendantContext defendant) {
        boolean police = false;
        boolean prison = false;

        for (final JsonNode prosecutionCase : Json.array(hearing, PROSECUTION_CASES)) {
            for (final JsonNode member : Json.dereferencedArray(prosecutionCase, "defendants")) {
                if (Objects.equals(Json.text(member, MASTER_DEFENDANT_ID),
                        defendant.masterDefendantId())) {
                    final String custody = custodyAt(Json.at(member, PERSON_DEFENDANT));
                    police = police || POLICE_STATION.equals(custody);
                    prison = prison || PRISON.equals(custody);
                }
            }
        }
        for (final JsonNode application : Json.array(hearing, "courtApplications")) {
            final JsonNode subject =
                    Json.at(Json.at(application, "subject"), "masterDefendant");
            if (subject != null && Objects.equals(Json.text(subject, MASTER_DEFENDANT_ID),
                    defendant.masterDefendantId())) {
                final String custody = custodyAt(Json.at(subject, PERSON_DEFENDANT));
                police = police || POLICE_STATION.equals(custody);
                prison = prison || PRISON.equals(custody);
            }
        }
        return new Custody(police, prison);
    }

    /**
     * The custody location recorded against a person, if there is one.
     *
     * <p>{@code LocationTypeEnum} also declares {@code DETENTIONCENTRE}, and the legacy's
     * {@code switch} has no case for it — so a defendant held in one is, as far as every
     * subscription can tell, not in custody at all. Returning the raw value and comparing it against
     * the two the switch names is what preserves that.
     *
     * @param personDefendant the person record; may be {@code null}
     * @return the custody location, or {@code null} where none is recorded
     */
    private static String custodyAt(final JsonNode personDefendant) {
        if (!Json.truthy(personDefendant, CUSTODIAL_ESTABLISHMENT)) {
            return null;
        }
        return Json.text(Json.at(personDefendant, CUSTODIAL_ESTABLISHMENT), CUSTODY);
    }

    /**
     * Whether the defendant appeared, and how.
     *
     * @param defendant the gathered defendant
     * @return the two appearance flags
     */
    private Attendance attendanceOf(final DefendantContext defendant) {
        boolean inPerson = false;
        boolean byVideoLink = false;

        for (final JsonNode record : Json.array(hearing, "defendantAttendance")) {
            if (!defendant.defendantIds().contains(Json.text(record, "defendantId"))) {
                continue;
            }
            for (final JsonNode day : Json.dereferencedArray(record, "attendanceDays")) {
                if (resultedOn(defendant, Json.text(day, "day"))) {
                    final String type = Json.text(day, "attendanceType");
                    inPerson = inPerson || "IN_PERSON".equals(type);
                    byVideoLink = byVideoLink || "BY_VIDEO".equals(type);
                }
            }
        }
        return new Attendance(inPerson, byVideoLink);
    }

    /**
     * Whether any of the defendant's own results was ordered on the given day.
     *
     * @param defendant the gathered defendant
     * @param day       the day attended
     * @return whether the day carries one of their results
     */
    private static boolean resultedOn(final DefendantContext defendant, final String day) {
        for (final RegisterResult result : defendant.results()) {
            if (Objects.equals(Json.text(result.judicialResult(), ORDERED_DATE), day)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any of the defendant's results carries the prison prompt.
     *
     * @param defendant the gathered defendant
     * @return whether a result is custodial
     */
    private static boolean hasCustodialResult(final DefendantContext defendant) {
        for (final RegisterResult result : defendant.results()) {
            for (final JsonNode prompt
                    : Json.array(result.judicialResult(), JUDICIAL_RESULT_PROMPTS)) {
                if (PRISON_PROMPT.equals(Json.text(prompt, PROMPT_REFERENCE))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether any of the defendant's results carries a prompt that is not the prison one.
     *
     * @param defendant the gathered defendant
     * @return whether a result is non-custodial
     */
    private static boolean hasNonCustodialResult(final DefendantContext defendant) {
        for (final RegisterResult result : defendant.results()) {
            for (final JsonNode prompt
                    : Json.array(result.judicialResult(), JUDICIAL_RESULT_PROMPTS)) {
                final String reference = Json.text(prompt, PROMPT_REFERENCE);
                if (Json.truthy(prompt, PROMPT_REFERENCE) && !PRISON_PROMPT.equals(reference)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether any prosecution case in the hearing is prosecuted by the CPS.
     *
     * <p>{@code prosecutionCase.prosecutor.isCps === true} — a strict comparison, so the string
     * {@code "true"} is not a CPS prosecution and neither is any other truthy value.
     *
     * @return whether the hearing is CPS-prosecuted
     */
    private boolean cpsProsecuted() {
        for (final JsonNode prosecutionCase : Json.array(hearing, PROSECUTION_CASES)) {
            final JsonNode cpsFlag = Json.at(Json.at(prosecutionCase, "prosecutor"), "isCps");
            if (cpsFlag != null && cpsFlag.isBoolean() && cpsFlag.booleanValue()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The custody half of a vocabulary.
     *
     * @param police whether the defendant is held at a police station
     * @param prison whether the defendant is held at a prison
     */
    private record Custody(boolean police, boolean prison) {
    }

    /**
     * The appearance half of a vocabulary.
     *
     * @param inPerson    whether the defendant attended in person on a resulted day
     * @param byVideoLink whether the defendant attended by video on a resulted day
     */
    private record Attendance(boolean inPerson, boolean byVideoLink) {
    }
}
