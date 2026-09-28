package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Consumer;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ProcessedOutputClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationAnomaly;

/**
 * What one run skipped, counted as the run goes.
 *
 * <p>The sink the twelve mappers have been counting into since they were written. Each of them
 * takes a {@link Consumer} rather than reaching for a counter of its own, which keeps the
 * transformation pure (constitution Principle V); this is the thing on the other end of that seam,
 * and it belongs to the application layer because what a skip is worth — a metric, a column, a
 * warning — is a question about the run rather than about the mapper that met it.
 *
 * <p><strong>One per run.</strong> Not a bean, not a field of the chain: the chain is a singleton in
 * the running service, so a counter it held would accumulate every hearing the pod had ever
 * transformed and the register that was finally sent would be stamped with somebody else's skips.
 * A run makes one of these, hands it down, and reads it once at the end.
 *
 * <p><strong>Bounded, in both senses a bounded reason has to be.</strong> The keys are a closed enum,
 * so nothing a payload carries can become a code, and neither {@link #summary()} nor
 * {@link #counts()} can put a name, an address or a fragment of a message body into a log index or a
 * database column (constitution Principle VII). And the counts saturate rather than wrapping: an
 * {@code int} that overflowed would go negative, and {@link ProcessedOutputClaim} refuses a count
 * that is not positive — at the exact moment a register was about to be sent, which is the worst
 * moment this service has to refuse anything.
 *
 * <p>Single-threaded by construction: one run is one thread from the guard's admission to the
 * outcome it records, so there is no synchronisation here and none is needed. A second thread
 * counting into one of these would be a second runner sharing a claim, which the processed log
 * exists to make impossible.
 */
public final class RunAnomalies implements Consumer<TransformationAnomaly> {

    private final Map<TransformationAnomaly, Integer> counted =
            new EnumMap<>(TransformationAnomaly.class);

    /**
     * Counts one occurrence.
     *
     * @param anomaly the guarded skip that was met
     */
    @Override
    public void accept(final TransformationAnomaly anomaly) {
        counted.merge(anomaly, 1, RunAnomalies::saturating);
    }

    /**
     * What this run skipped, frozen.
     *
     * <p>A copy, because the map it is read from goes on being written to while the run finishes:
     * the claim written in the breath before a POST has to describe the register in that POST, not
     * whatever the transformation counted afterwards. Unmodifiable for the mirror-image reason —
     * nothing downstream may add to a run's own record of itself.
     *
     * @return the counts, by reason; empty where nothing was skipped
     */
    public Map<TransformationAnomaly, Integer> counts() {
        return Collections.unmodifiableMap(new EnumMap<>(counted));
    }

    /**
     * Whether this run skipped anything at all.
     *
     * @return whether nothing was counted
     */
    public boolean isEmpty() {
        return counted.isEmpty();
    }

    /**
     * The bounded codes and their counts, as one line that is safe to log anywhere.
     *
     * <p>{@code letter-delivery-dropped:2,recipient-missing-email:1} — the same form
     * {@code processed_output.anomaly_summary} carries, so an operator reading a warning and an
     * operator reading a row are reading the same statement. In reason-declaration order, which is
     * stable across runs and across pods.
     *
     * @return the summary; empty where nothing was skipped
     */
    public String summary() {
        final StringJoiner summary = new StringJoiner(",");
        counted.forEach((anomaly, count) -> summary.add(anomaly.value() + ':' + count));
        return summary.toString();
    }

    /**
     * One more, unless one more would wrap.
     *
     * @param current the count so far
     * @param one     the increment, which is always one
     * @return the new count
     */
    private static Integer saturating(final Integer current, final Integer one) {
        return current == Integer.MAX_VALUE ? current : current + one;
    }
}
