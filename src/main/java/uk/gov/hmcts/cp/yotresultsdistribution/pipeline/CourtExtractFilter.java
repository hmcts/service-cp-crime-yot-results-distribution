package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterResult;

/**
 * Keeps only what a court extract may carry — at result level and then at prompt level.
 *
 * <p>Ports {@code NowsHelper/service/RegisterFragmentService.js:3-23}, which
 * {@code SetCourtRegister/index.js:43} runs over every gathered defendant before their vocabulary is
 * attached. Two filters, in this order:
 *
 * <ol>
 *   <li><strong>Results</strong> — kept when {@code judicialResult.isAvailableForCourtExtract} is
 *       truthy <em>and</em> {@code judicialResult.publishedForNows} is falsy. A result already
 *       published through the NOWs route is not published again through the register.</li>
 *   <li><strong>Prompts</strong>, on the results that survived — kept on
 *       {@code prompt.isAvailableForCourtExtract} where the prompt carries that field at all, and
 *       otherwise on the older {@code courtExtract} spelling, upper-cased and compared to
 *       {@code 'Y'}. The fallback is reached only when the newer field is <em>absent</em>: the
 *       legacy tests {@code === undefined}, which a JSON {@code null} does not satisfy, so a null
 *       flag is returned as the filter's own answer and drops the prompt.</li>
 * </ol>
 *
 * <p><strong>The legacy filters in place; this does not.</strong>
 * {@code r.judicialResult.judicialResultPrompts = …filter(…)} edits the hearing payload the activity
 * was handed, and is saved from being seen downstream only by the Durable Functions serialisation
 * boundary between activities. A Java pipeline passes references, so the payload would really be
 * edited — and the inbound tree is owned by the producer, not by this service (constitution
 * Principle IV). The filter derives new results instead and leaves the tree it was given untouched.
 */
// PMD.OnlyOneReturn: the prompt predicate answers at the clause that decides it, which is the shape
// of the legacy's own nested conditional; one exit would flatten a three-branch decision into a
// single expression that no longer reads against the source it ports.
@SuppressWarnings("PMD.OnlyOneReturn")
public final class CourtExtractFilter {

    /** The newer, boolean flag a prompt carries. */
    private static final String AVAILABLE = "isAvailableForCourtExtract";

    /** The older, single-letter spelling the fallback reads. */
    private static final String COURT_EXTRACT = "courtExtract";

    /** The prompts hanging off a judicial result. */
    private static final String PROMPTS = "judicialResultPrompts";

    private CourtExtractFilter() {
    }

    /**
     * Replaces each defendant's results with the ones a court extract may carry.
     *
     * @param defendants the gathered defendants
     */
    /* default */ static void apply(final List<DefendantContext> defendants) {
        for (final DefendantContext defendant : defendants) {
            defendant.results(keptResults(defendant.results()));
        }
    }

    /**
     * The results of one defendant that a court extract may carry.
     *
     * @param results the defendant's gathered results
     * @return the surviving results, each carrying only its showable prompts
     */
    private static List<RegisterResult> keptResults(final List<RegisterResult> results) {
        final List<RegisterResult> kept = new ArrayList<>(results.size());
        for (final RegisterResult result : results) {
            if (availableForCourtExtract(result.judicialResult())) {
                kept.add(withFilteredPrompts(result));
            }
        }
        return kept;
    }

    /**
     * Whether a result may appear on a court extract at all.
     *
     * @param judicialResult the result
     * @return whether it survives the result-level filter
     */
    private static boolean availableForCourtExtract(final JsonNode judicialResult) {
        return Json.truthy(judicialResult, AVAILABLE)
                && !Json.truthy(judicialResult, "publishedForNows");
    }

    /**
     * The same gathered result, carrying only the prompts a court extract may show.
     *
     * <p>A result whose prompts are absent — or are a falsy value of any kind — is returned exactly
     * as it arrived: {@code if (r.judicialResult.judicialResultPrompts)} is not entered, so the
     * legacy neither filters nor invents an empty array, and neither does this.
     *
     * @param result the surviving result
     * @return the result with its prompts filtered
     */
    private static RegisterResult withFilteredPrompts(final RegisterResult result) {
        final JsonNode judicialResult = result.judicialResult();
        if (!Json.truthy(judicialResult, PROMPTS) || !judicialResult.isObject()) {
            return result;
        }
        final ObjectNode derived = ((ObjectNode) judicialResult).deepCopy();
        final ArrayNode kept = derived.putArray(PROMPTS);
        for (final JsonNode prompt : Json.array(judicialResult, PROMPTS)) {
            if (showable(prompt)) {
                kept.add(prompt);
            }
        }
        return new RegisterResult(result.prosecutionCaseId(), result.defendantId(),
                result.offenceId(), result.applicationId(), result.level(),
                result.masterDefendantId(), derived, result.includeInNcesResult(),
                result.isApplicant());
    }

    /**
     * Whether one prompt may appear on a court extract.
     *
     * @param prompt the prompt
     * @return whether it survives the prompt-level filter
     */
    private static boolean showable(final JsonNode prompt) {
        if (Json.at(prompt, AVAILABLE) != null) {
            // `=== undefined` is the legacy's test, and an explicit JSON null does not satisfy it:
            // the null is returned as the filter's own answer, and is falsy.
            return Json.truthy(prompt, AVAILABLE);
        }
        // `prompt.courtExtract ? prompt.courtExtract.toUpperCase() === 'Y' : false` — an empty
        // string never reaches toUpperCase, and ROOT is the locale JavaScript upper-cases in.
        final String courtExtract = Json.text(prompt, COURT_EXTRACT);
        return Json.truthy(prompt, COURT_EXTRACT)
                && courtExtract != null
                && "Y".equals(courtExtract.toUpperCase(Locale.ROOT));
    }
}
