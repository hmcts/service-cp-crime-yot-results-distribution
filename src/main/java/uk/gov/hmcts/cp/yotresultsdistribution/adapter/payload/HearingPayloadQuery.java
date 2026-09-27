package uk.gov.hmcts.cp.yotresultsdistribution.adapter.payload;

import java.util.Optional;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;

/**
 * The query-side fallback for a payload the cache does not hold.
 *
 * <p>Internal to {@code adapter/payload}, for the same reason as {@link HearingPayloadCache}: the
 * composite adapter's ordering is worth testing on its own, and neither collaborator belongs in the
 * core.
 *
 * <p>Empty means <strong>the query side answered and had nothing</strong> — a 404, or the
 * {@code 200} with an empty body the results context returns for a hearing it does not hold
 * ({@code getPrefixHearing}, {@code HearingResultedCacheQuery/index.js:50-53}, returns the body only
 * when it has content). It does not mean the read failed: a read that could not be made raises,
 * because a transport failure the caller cannot tell from an empty answer is how the legacy loses a
 * register (defect fix C32).
 *
 * <p>Turning "no payload anywhere" into a settlement decision is
 * {@link CachedHearingPayloadAdapter}'s job, not this one's — it is the only participant that knows
 * both sources missed.
 */
public interface HearingPayloadQuery {

    /**
     * Asks the query side for the hearing payload.
     *
     * @param command the validated request naming the hearing
     * @return the payload, or empty when the query side answered and held none
     * @throws uk.gov.hmcts.cp.yotresultsdistribution.domain.PayloadUnavailableException if the query side
     *     could not be read at all — always transient
     */
    Optional<JsonNode> fetch(DistributionCommand command);
}
