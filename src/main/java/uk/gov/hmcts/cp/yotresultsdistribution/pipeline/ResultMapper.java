package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.util.List;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterResult;

/**
 * Maps judicial results onto the register's results.
 *
 * <p>Ports {@code .../Mappers/Result/ResultMapper.js}: the result's text, and its {@code cjsCode}
 * under the name {@code cjsResultCode}. Both guards answer with nothing — {@code null} and an empty
 * list alike ({@code :6}) — which is what keeps an empty array out of a contract that puts
 * {@code minItems: 1} on every result list.
 *
 * <p>Called at three scopes with three differently-filtered lists: defendant-level results on the
 * defendant, case- and application-level results on the case or application, and offence-level
 * results on the offence they were ordered against. The filtering is the caller's; this maps
 * whatever it is handed.
 */
// PMD.OnlyOneReturn: the guard is the legacy's own early return. PMD.ReturnEmptyCollectionRather
// ThanNull: every result list on the frozen contract carries `minItems: 1`, and this mapper is
// called at three scopes where an empty filtered list is the normal case — so `[]` is not a quieter
// way of saying "no results", it is a document progression rejects.
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.ReturnEmptyCollectionRatherThanNull"})
final class ResultMapper {

    private ResultMapper() {
    }

    /**
     * Maps a list of judicial results.
     *
     * @param judicialResults the judicial results, already scoped by the caller
     * @return the mapped results, or {@code null} where there were none — empty included
     */
    /* default */ static List<CourtRegisterResult> map(final List<JsonNode> judicialResults) {
        if (judicialResults == null || judicialResults.isEmpty()) {
            // `if (this.judicialResults && this.judicialResults.length)` — and the empty half is the
            // one that keeps `results: []`, which every result list's `minItems: 1` refuses, off the
            // wire at all three scopes this is called from.
            return null;
        }
        return judicialResults.stream().map(ResultMapper::result).toList();
    }

    /**
     * Maps one judicial result — its text, and its CJS code under the name the register gives it.
     *
     * @param judicialResult the published judicial result
     * @return the mapped result
     */
    private static CourtRegisterResult result(final JsonNode judicialResult) {
        return new CourtRegisterResult(
                Json.text(judicialResult, "resultText"), Json.text(judicialResult, "cjsCode"));
    }
}
