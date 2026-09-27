package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A name a defendant has also been known by.
 *
 * <p>Ports {@code Models/Alias.js} against the vendored {@code courtRegisterDefendantAlias.json};
 * four name parts, copied across one for one by {@code Mappers/Alias/AliasMapper.js:11-16}.
 *
 * <p>The alias record the payload carries has more on it than this — {@code legalEntityName} among
 * them — and the mapper copies none of it. That is not an omission to tidy up later: the register is
 * a document about children, the four parts here are what it prints, and a field that arrives on the
 * wire without being asked for is a disclosure nobody reviewed.
 *
 * @param title      the alias title
 * @param firstName  the alias fore name
 * @param middleName the alias middle name
 * @param lastName   the alias last name
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CourtRegisterAlias(
        String title,
        String firstName,
        String middleName,
        String lastName) {
}
