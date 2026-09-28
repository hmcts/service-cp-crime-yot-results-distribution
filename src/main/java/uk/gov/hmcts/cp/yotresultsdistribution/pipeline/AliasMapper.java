package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.util.List;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterAlias;

/**
 * Maps a defendant's aliases onto the register's aliases.
 *
 * <p>Ports {@code .../Mappers/Alias/AliasMapper.js}. Four name parts copied one for one; everything
 * else on the payload's alias — {@code legalEntityName} among it — is deliberately not carried.
 *
 * <p><strong>The asymmetry is load-bearing.</strong> This mapper guards on truthiness alone
 * ({@code :9}), so an <em>absent</em> alias list answers with nothing while an <em>empty</em> one
 * answers with an empty list. Its counsel counterpart guards on {@code counsels && counsels.length}
 * and answers with nothing for both. Absent, null and empty are three different answers across these
 * two mappers, and the comparator vectors exist to keep them that way.
 */
// PMD.OnlyOneReturn: the guard is the legacy's own early return. PMD.ReturnEmptyCollectionRather
// ThanNull: nothing and an empty list are two different answers here, and the difference between
// them is this mapper's whole asymmetry with CounselMapper — an empty array maps to an empty list,
// an absent one maps to nothing, and the contract's `minItems: 1` refuses the one that is invented.
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.ReturnEmptyCollectionRatherThanNull"})
final class AliasMapper {

    /** The field the alias list hangs off a defendant, named so a refusal can say what was wrong. */
    private static final String ALIASES = "aliases";

    private AliasMapper() {
    }

    /**
     * Maps a defendant's aliases.
     *
     * @param defendantAliases the payload's alias array, or {@code null} where the defendant has
     *                         none
     * @return the mapped aliases — empty for an empty array, {@code null} for an absent one
     */
    /* default */ static List<CourtRegisterAlias> map(final JsonNode defendantAliases) {
        if (!Json.truthy(defendantAliases)) {
            // `if (this.defendantAliases)` with no `else`: an absent or null list falls off the end
            // of the function. An *empty* array is truthy, so it does not come here — it maps to an
            // empty list, which is the half of the asymmetry with CounselMapper that matters.
            return null;
        }
        return Json.elements(defendantAliases, ALIASES).stream()
                .map(AliasMapper::alias)
                .toList();
    }

    /**
     * Maps one alias — four name parts, and nothing else the payload's alias carries.
     *
     * @param defendantAlias the payload's alias
     * @return the mapped alias
     */
    private static CourtRegisterAlias alias(final JsonNode defendantAlias) {
        return new CourtRegisterAlias(
                Json.text(defendantAlias, "title"),
                Json.text(defendantAlias, "firstName"),
                Json.text(defendantAlias, "middleName"),
                Json.text(defendantAlias, "lastName"));
    }
}
