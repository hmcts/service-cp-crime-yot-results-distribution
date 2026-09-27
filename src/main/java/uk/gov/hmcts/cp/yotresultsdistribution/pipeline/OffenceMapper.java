package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterOffence;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterResult;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ResultLevel;

/**
 * Maps a case's or application's offences onto the register's offences.
 *
 * <p>Ports {@code .../Mappers/Offence/OffenceMapper.js}. Most fields are copied off the payload's
 * offence; three are not.
 *
 * <ul>
 *   <li><strong>{@code wording}</strong> — the legacy joins it to {@code offenceLegislation} with a
 *       {@code ####} sentinel progression substitutes for a newline when it renders, and with a
 *       literal {@code undefined} where there is no legislation (defect C24). Fixed: the two are
 *       joined with a real newline, which progression's {@code replaceAll("####", "\n")} passes
 *       through unchanged, and an absent legislation is simply not there.</li>
 *   <li><strong>{@code verdictCode}</strong> — the legacy writes the verdict type's prose
 *       description into a field named for a code (defect C23). Fixed: the verdict type's own
 *       {@code verdictCode}, falling back to its {@code categoryType} where the payload carries no
 *       code — live payloads have been observed carrying only {@code category},
 *       {@code categoryType} and {@code id}. Never the description.</li>
 *   <li><strong>{@code results}</strong> — the gathered results scoped to this offence by level and
 *       offence id ({@code :24-26}), which is why the register defendant is a parameter. The only
 *       legacy offence fixture's context has an empty result list, so this scoping — the court
 *       register's one correctness advantage over its informant sibling — has never executed.</li>
 * </ul>
 */
final class OffenceMapper {

    /** The offence's own identity, which its results are scoped by. */
    private static final String ID = "id";

    /** The plea block, which carries both the plea value and the plea date. */
    private static final String PLEA = "plea";

    private OffenceMapper() {
    }

    /**
     * Maps a list of offences.
     *
     * <p><strong>An empty gather maps to an empty list</strong>, which is what
     * {@code this.offences.map(...)} answers and what the legacy posts. It is a document
     * progression's {@code minItems: 1} refuses — so the register is lost either way, and the
     * choice is only ever about how. The legacy loses it to a 400 nobody sees (C29 with C1); this
     * loses it to an explicit, classified FAILED with the offending pointer named, and adds no
     * content of its own on the way. Inventing an absence here would send progression a register
     * the legacy never sends, which is an uncatalogued behaviour change; refusing it is
     * {@link uk.gov.hmcts.cp.yotresultsdistribution.adapter.progression.OutboundContractValidator}'s.
     *
     * @param offences          the payload offences, gathered by the caller from a prosecution case
     *                          or from an application's cases and court order
     * @param registerDefendant the gathered defendant, whose results are scoped onto each offence
     * @return the mapped offences, empty where the caller gathered none
     */
    /* default */ static List<CourtRegisterOffence> map(
            final List<JsonNode> offences, final RegisterDefendant registerDefendant) {

        return offences == null
                ? List.of()
                : offences.stream().map(offence -> offence(offence, registerDefendant)).toList();
    }

    /**
     * Maps one offence.
     *
     * @param offenceInfo       the payload's offence
     * @param registerDefendant the gathered defendant whose results are scoped onto it
     * @return the mapped offence
     */
    private static CourtRegisterOffence offence(
            final JsonNode offenceInfo, final RegisterDefendant registerDefendant) {

        return new CourtRegisterOffence(
                Json.text(offenceInfo, "offenceCode"),
                orderIndex(offenceInfo),
                Json.text(offenceInfo, "offenceTitle"),
                wording(offenceInfo),
                Json.text(Json.at(offenceInfo, PLEA), "pleaValue"),
                Json.text(Json.at(offenceInfo, "indicatedPlea"), "indicatedPleaValue"),
                Json.text(Json.at(offenceInfo, PLEA), "pleaDate"),
                Json.text(Json.at(offenceInfo, "allocationDecision"), "motReasonDescription"),
                Json.text(offenceInfo, "convictionDate"),
                verdictCode(offenceInfo),
                ResultMapper.map(offenceResults(offenceInfo, registerDefendant)));
    }

    /**
     * The offence's index within its case, where the payload records one as a number.
     *
     * @param offenceInfo the payload's offence
     * @return the index, or {@code null}
     */
    private static Integer orderIndex(final JsonNode offenceInfo) {
        final JsonNode orderIndex = Json.at(offenceInfo, "orderIndex");
        return orderIndex == null || !orderIndex.isNumber() ? null : orderIndex.intValue();
    }

    /**
     * The charge's wording with its legislation joined onto it — defect fix C24.
     *
     * <p>{@code wording + '####' + offenceLegislation} becomes a newline join of the parts that are
     * there: both where there are both, either alone where there is one, and nothing at all where
     * there is neither. The legacy's third answer to that last case is the string
     * {@code "undefined####undefined"}, printed on the register.
     *
     * @param offenceInfo the payload's offence
     * @return the wording, or {@code null} where the offence carries neither part
     */
    private static String wording(final JsonNode offenceInfo) {
        return JsStrings.joinedOnTruth(
                "\n",
                Json.text(offenceInfo, "wording"),
                Json.text(offenceInfo, "offenceLegislation"));
    }

    /**
     * The verdict against the offence — defect fix C23.
     *
     * @param offenceInfo the payload's offence
     * @return the verdict code, its category type where there is no code, or {@code null}
     */
    private static String verdictCode(final JsonNode offenceInfo) {
        final JsonNode verdictType =
                Json.at(Json.at(offenceInfo, "verdict"), "verdictType");
        final String verdictCode = Json.text(verdictType, "verdictCode");
        return verdictCode == null ? Json.text(verdictType, "categoryType") : verdictCode;
    }

    /**
     * The gathered results ordered against this offence and nothing else.
     *
     * @param offenceInfo       the payload's offence
     * @param registerDefendant the gathered defendant carrying the level-tagged results
     * @return the judicial results scoped to this offence, in the order they were gathered
     */
    private static List<JsonNode> offenceResults(
            final JsonNode offenceInfo, final RegisterDefendant registerDefendant) {

        final String offenceId = Json.text(offenceInfo, ID);
        return registerDefendant.results().stream()
                .filter(result -> result.level() == ResultLevel.OFFENCE)
                .filter(result -> Objects.equals(result.offenceId(), offenceId))
                .map(RegisterResult::judicialResult)
                .toList();
    }
}
