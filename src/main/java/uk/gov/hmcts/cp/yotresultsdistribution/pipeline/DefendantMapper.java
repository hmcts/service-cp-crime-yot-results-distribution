package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Finds the payload defendant records a register defendant was gathered from.
 *
 * <p>Ports {@code .../Mappers/Defendant/DefendantMapper.js}. The one mapper that produces no
 * document component: it answers with the hearing's own defendant records, which the youth-defendant
 * and parent-guardian mappers then read personal details off.
 *
 * <p><strong>The precedence is the behaviour.</strong> Prosecution cases are searched first, by
 * {@code masterDefendantId} across every case's defendants; court applications are consulted
 * <em>only</em> when that search finds nothing ({@code :11-20}). The four legacy cases cover both
 * halves and the neither-matched case, but none states the precedence itself — a defendant present
 * in both places is never constructed.
 *
 * <p>The empty answer is where defect C19 begins: the youth mapper takes {@code defendants[0]} with
 * no length check, so an unmatched master defendant id throws, the throw is swallowed, and the whole
 * hearing's register is lost.
 */
// PMD.OnlyOneReturn: the precedence is the behaviour, and it is an `else` in the legacy — the case
// answer is returned where it is found, and the applications are only consulted where it was not.
// A single exit would search both and then choose, which is a different function.
@SuppressWarnings("PMD.OnlyOneReturn")
final class DefendantMapper {

    /** The identity a defendant is known by across cases and applications. */
    private static final String MASTER_DEFENDANT_ID = "masterDefendantId";

    private DefendantMapper() {
    }

    /**
     * Finds the hearing's defendant records for one master defendant.
     *
     * @param hearing           the hearing payload
     * @param masterDefendantId the defendant's identity across cases and applications
     * @return the matching defendant records, cases first and applications only if there were none;
     *         empty where nothing matched
     */
    /* default */ static List<JsonNode> defendantsOf(
            final JsonNode hearing, final String masterDefendantId) {

        final List<JsonNode> fromCases = new ArrayList<>();
        for (final JsonNode prosecutionCase : Json.array(hearing, "prosecutionCases")) {
            for (final JsonNode defendant : Json.array(prosecutionCase, "defendants")) {
                if (masterDefendantId.equals(Json.text(defendant, MASTER_DEFENDANT_ID))) {
                    fromCases.add(defendant);
                }
            }
        }
        if (!fromCases.isEmpty()) {
            // `if (matchingDefendantsFromCases.length === 0) { … } else { return … }` — the court
            // applications are consulted only where the cases found nobody, and no legacy fixture
            // constructs a defendant who is in both.
            return List.copyOf(fromCases);
        }

        final List<JsonNode> fromApplications = new ArrayList<>();
        for (final JsonNode application : Json.array(hearing, "courtApplications")) {
            final JsonNode masterDefendant =
                    Json.at(Json.at(application, "subject"), "masterDefendant");
            if (Json.truthy(masterDefendant)
                    && masterDefendantId.equals(Json.text(masterDefendant, MASTER_DEFENDANT_ID))) {
                fromApplications.add(masterDefendant);
            }
        }
        return List.copyOf(fromApplications);
    }
}
