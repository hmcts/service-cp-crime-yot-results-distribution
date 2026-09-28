package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.util.List;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterCounsel;

/**
 * Maps counsel records onto the register's counsels — defence, prosecution and applicant alike.
 *
 * <p>Ports {@code .../Mappers/Counsel/CounselMapper.js}. The status is copied; the name is composed
 * from first, middle and last, dropping the parts that are absent so a counsel with no middle name
 * gets one space and not two ({@code :11}). No legacy case asserts the composition.
 *
 * <p>Guards on {@code counsels && counsels.length}, so <strong>absent and empty both answer with
 * nothing</strong> — the opposite of {@link AliasMapper}, which answers with an empty list for an
 * empty array. Both halves of that asymmetry are pinned.
 */
// PMD.OnlyOneReturn: the guard is the legacy's own early return. PMD.ReturnEmptyCollectionRather
// ThanNull: an empty counsel list is `undefined` on the wire and `[]` is a document progression
// refuses (`minItems: 1`), so the null is the answer rather than an omission.
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.ReturnEmptyCollectionRatherThanNull"})
final class CounselMapper {

    private CounselMapper() {
    }

    /**
     * Maps a list of counsel records.
     *
     * <p>The list is gathered by the caller — by case, by applicant, or by the defendant ids a
     * defence counsel appears for — so it arrives as a Java list of payload nodes rather than as a
     * field off the hearing.
     *
     * @param counsels the counsel records, or {@code null} where none were gathered
     * @return the mapped counsels, or {@code null} where there were none — empty included
     */
    /* default */ static List<CourtRegisterCounsel> map(final List<JsonNode> counsels) {
        if (counsels == null || counsels.isEmpty()) {
            // `if (this.counsels && this.counsels.length)` — absent and empty answer alike, where
            // the alias mapper answers an empty array with an empty list.
            return null;
        }
        return counsels.stream().map(CounselMapper::counsel).toList();
    }

    /**
     * Maps one counsel — a composed name and a copied status.
     *
     * @param counselInfo the payload's counsel record
     * @return the mapped counsel
     */
    private static CourtRegisterCounsel counsel(final JsonNode counselInfo) {
        return new CourtRegisterCounsel(
                JsStrings.composedName(counselInfo), Json.text(counselInfo, "status"));
    }
}
