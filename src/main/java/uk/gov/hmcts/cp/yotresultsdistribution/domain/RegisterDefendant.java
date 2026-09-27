package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * One defendant as they appear on a register fragment: what was gathered for them, and the eighteen
 * facts a subscription is matched against.
 *
 * <p>The shared kernel's {@code DefendantContextBase}
 * ({@code NowsHelper/service/DefendantContextBaseService.js:4-14}) with the vocabulary
 * {@code SetCourtRegister/index.js:65} attaches to it, component for component and in the same
 * order. The gather itself happens in the package-private {@code DefendantContext}, which is
 * mutable because the legacy builds it by accumulation; this is the frozen form that leaves the
 * pipeline.
 *
 * <p><strong>Not filtered to youths.</strong> Every defendant the hearing gathered is here, adult
 * and youth alike — the youth filter runs later, at the aggregation stage
 * ({@code OutboundCourtRegister/index.js:22}). That ordering is what makes defect C31 possible, and
 * a list quietly pre-filtered here would hide it rather than fix it.
 *
 * @param defendantIds      every case-scoped or application-scoped id this defendant was found under
 * @param results           the judicial results gathered for them, after court-extract filtering
 * @param cases             the prosecution cases they were found in
 * @param applications      the court applications they were found in
 * @param masterDefendantId their identity across cases and applications; the gather drops a context
 *                          without one
 * @param youthDefendant    the legacy's {@code isYouthDefendant}; the register's whole business rule
 * @param orderedDate       the latest date one of their results was ordered
 * @param vocabulary        the eighteen facts a subscription is matched against
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterDefendant(
        List<String> defendantIds,
        List<RegisterResult> results,
        List<String> cases,
        List<String> applications,
        String masterDefendantId,
        @JsonProperty("isYouthDefendant") boolean youthDefendant,
        String orderedDate,
        RegisterVocabulary vocabulary) {

    /** Freezes the four lists, so a fragment cannot be edited after it is built. */
    public RegisterDefendant {
        defendantIds = defendantIds == null ? List.of() : List.copyOf(defendantIds);
        results = results == null ? List.of() : List.copyOf(results);
        cases = cases == null ? List.of() : List.copyOf(cases);
        applications = applications == null ? List.of() : List.copyOf(applications);
    }
}
