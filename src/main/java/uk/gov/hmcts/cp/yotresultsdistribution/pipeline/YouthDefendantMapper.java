package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterResult;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ResultLevel;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationAnomaly;

/**
 * Maps the youth defendants the register is about.
 *
 * <p>Ports {@code .../Mappers/YouthDefendant/YouthDefendantMapper.js}, the mapper that calls most of
 * the others: name, date of birth, address, gender, nationality, ethnicity and post-hearing custody
 * status off the payload's person details, then parent or guardian, hearing details, aliases, cases
 * and applications, defendant-level results and defence counsel.
 *
 * <p><strong>Two catalogued defects.</strong> C19: the mapper takes {@code defendants[0]} with no
 * length check and then {@code personDefendant.personDetails} with no legal-entity fallback
 * ({@code :32,34}), so an unmatched or non-person defendant throws — and the throw is swallowed one
 * level up, losing the whole hearing's register for every other child on it. The fix skips that
 * defendant, counts it through {@code anomalies} and keeps the register. C25: ethnicity is emitted
 * only when the payload holds both an observed and a self-defined description ({@code :70-74}), so a
 * self-defined-only child has theirs dropped and the {@code ||} at {@code :72} is unreachable.
 *
 * <p>Two more things this mapper does that nothing has ever asserted: it composes the name from
 * first, middle and last while the one legacy case asserts first-plus-last against a fixture with no
 * middle name, and it reads a real {@code postHearingCustodyStatus} only when the defendant carries
 * case judicial results — which the fixture's empty list means it never has.
 */
// PMD.OnlyOneReturn: the resolution guard, the ethnicity fallback and the custody-status search
// each answer where the legacy expression they port answers.
@SuppressWarnings("PMD.OnlyOneReturn")
final class YouthDefendantMapper {

    private static final Logger LOG = LoggerFactory.getLogger(YouthDefendantMapper.class);

    /** What a defendant's post-hearing custody status says when it says nothing. */
    private static final String NOT_APPLICABLE = "Not Applicable";

    /** The person half of a defendant record; a legal entity carries none. */
    private static final String PERSON_DEFENDANT = "personDefendant";

    /** The person details the register prints a child from. */
    private static final String PERSON_DETAILS = "personDetails";

    /** The status a defendant was remanded under after the hearing. */
    private static final String POST_HEARING_CUSTODY_STATUS = "postHearingCustodyStatus";

    /** The hearing's defence counsel records. */
    private static final String DEFENCE_COUNSELS = "defenceCounsels";

    /** Where an unresolvable defendant is counted; called once per skip. */
    private final Consumer<TransformationAnomaly> anomalies;

    /**
     * Creates the mapper.
     *
     * @param anomalyRecorder where each skipped youth defendant is counted
     */
    /* default */ YouthDefendantMapper(final Consumer<TransformationAnomaly> anomalyRecorder) {
        this.anomalies = anomalyRecorder;
    }

    /**
     * Maps the register's youth defendants.
     *
     * @param youthDefendants the gathered defendants the youth filter left, in fragment order
     * @param hearing         the hearing payload
     * @return the mapped defendants, one per resolvable youth defendant
     */
    /* default */ List<CourtRegisterDefendant> map(
            final List<RegisterDefendant> youthDefendants, final JsonNode hearing) {

        final List<CourtRegisterDefendant> mapped = new ArrayList<>(youthDefendants.size());
        for (final RegisterDefendant registerDefendant : youthDefendants) {
            final JsonNode defendant = resolve(hearing, registerDefendant);
            if (defendant == null) {
                // C19. The bounded reason and the hearing, and nothing else: every defendant on this
                // register is a child, and these lines reach a log index shared across the estate.
                LOG.warn("Youth defendant omitted from the register; their record could not be "
                                + "resolved. reason={} hearingId={}",
                        TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT.value(),
                        Json.text(hearing, "id"));
                anomalies.accept(TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT);
                continue;
            }
            mapped.add(youthDefendant(registerDefendant, defendant, hearing));
        }
        return List.copyOf(mapped);
    }

    /**
     * The payload record this defendant is printed from — defect fix C19.
     *
     * <p>{@code defendants[0]} with no length check ({@code :32}) and then
     * {@code personDefendant.personDetails} with no legal-entity fallback ({@code :34}). Either an
     * unmatched master defendant id or a company standing where a child was expected throws, the
     * throw is swallowed at {@code OutboundCourtRegister/index.js:62-64}, and the whole hearing's
     * register is lost — for every other child on it, over one record.
     *
     * @param hearing           the hearing payload
     * @param registerDefendant the gathered defendant
     * @return their payload record, or {@code null} where nothing on the hearing resolves them
     */
    private static JsonNode resolve(
            final JsonNode hearing, final RegisterDefendant registerDefendant) {

        final List<JsonNode> defendants =
                DefendantMapper.defendantsOf(hearing, registerDefendant.masterDefendantId());
        if (defendants.isEmpty()) {
            return null;
        }
        final JsonNode defendant = defendants.get(0);
        return Json.truthy(Json.at(defendant, PERSON_DEFENDANT), PERSON_DETAILS)
                ? defendant
                : null;
    }

    /**
     * Maps one child, calling most of the register's other mappers on the way.
     *
     * @param registerDefendant the gathered defendant
     * @param defendant         their payload record
     * @param hearing           the hearing payload
     * @return the mapped defendant
     */
    private CourtRegisterDefendant youthDefendant(
            final RegisterDefendant registerDefendant,
            final JsonNode defendant,
            final JsonNode hearing) {

        final JsonNode details = Json.at(Json.at(defendant, PERSON_DEFENDANT), PERSON_DETAILS);

        return new CourtRegisterDefendant(
                registerDefendant.masterDefendantId(),
                JsStrings.composedName(details),
                Json.text(details, "dateOfBirth"),
                AddressMapper.map(Json.at(details, "address")),
                Json.text(details, "nationalityDescription"),
                ethnicity(details),
                Json.text(details, "gender"),
                postHearingCustodyStatus(defendant),
                ParentGuardianMapper.map(registerDefendant, hearing),
                HearingMapper.map(hearing, registerDefendant, defendant),
                AliasMapper.map(Json.at(defendant, "aliases")),
                new ProsecutionCaseOrApplicationMapper(anomalies).map(registerDefendant, hearing),
                ResultMapper.map(defendantResults(registerDefendant)),
                CounselMapper.map(defenceCounsels(hearing, registerDefendant)));
    }

    /**
     * The ethnicity the register prints — defect fix C25.
     *
     * <p>The legacy emits one only when the payload holds <em>both</em> an observed and a
     * self-defined description, and then returns the observed — so its {@code ||} can never reach
     * its right-hand side and a child with only a self-defined ethnicity has theirs dropped. The fix
     * is observed-else-self-defined, which is what the expression was evidently written to mean.
     *
     * @param details the child's person details
     * @return the ethnicity description, or {@code null} where the payload carries neither
     */
    private static String ethnicity(final JsonNode details) {
        final JsonNode ethnicity = Json.at(details, "ethnicity");
        final String observed = Json.text(ethnicity, "observedEthnicityDescription");
        return observed == null
                ? Json.text(ethnicity, "selfDefinedEthnicityDescription")
                : observed;
    }

    /**
     * Where the child was remanded after the hearing.
     *
     * <p>{@code filter(j => j.postHearingCustodyStatus !== 'Not Applicable')[0]} ({@code :60-65}):
     * the first case result that says something other than the default, and the default where none
     * does. Never executed by the legacy suite, whose fixture carries an empty result list.
     *
     * @param defendant the child's payload record
     * @return the status
     */
    private static String postHearingCustodyStatus(final JsonNode defendant) {
        for (final JsonNode caseResult
                : Json.array(defendant, "defendantCaseJudicialResults")) {
            final String status = Json.text(caseResult, POST_HEARING_CUSTODY_STATUS);
            if (!NOT_APPLICABLE.equals(status)) {
                return status;
            }
        }
        return NOT_APPLICABLE;
    }

    /**
     * The gathered results recorded against the defendant rather than a case or an offence.
     *
     * @param registerDefendant the gathered defendant
     * @return the judicial results, in the order they were gathered
     */
    private static List<JsonNode> defendantResults(final RegisterDefendant registerDefendant) {
        return registerDefendant.results().stream()
                .filter(result -> result.level() == ResultLevel.DEFENDANT)
                .map(RegisterResult::judicialResult)
                .toList();
    }

    /**
     * The counsel who named one of this defendant's own ids.
     *
     * @param hearing           the hearing payload
     * @param registerDefendant the gathered defendant
     * @return the counsel records, in payload order
     */
    private static List<JsonNode> defenceCounsels(
            final JsonNode hearing, final RegisterDefendant registerDefendant) {

        final List<JsonNode> appearing = new ArrayList<>();
        for (final JsonNode defenceCounsel : Json.array(hearing, DEFENCE_COUNSELS)) {
            // `defenceCounsel.defendants.some(...)` — read through without a guard.
            for (final JsonNode defendantId
                    : Json.dereferencedArray(defenceCounsel, "defendants")) {
                if (defendantId.isString()
                        && registerDefendant.defendantIds().contains(defendantId.stringValue())) {
                    appearing.add(defenceCounsel);
                    break;
                }
            }
        }
        return appearing;
    }
}
