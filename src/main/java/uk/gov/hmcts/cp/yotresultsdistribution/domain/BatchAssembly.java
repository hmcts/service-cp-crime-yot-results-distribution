package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.util.List;

/**
 * What one run's assembly produced, and what it left for a later one.
 *
 * <p>Two lists rather than one, because a key that produced no batch is not the same as a key that
 * was never there. A re-share recorded for a court centre and day whose batch is still in flight
 * waits, since the schema admits one in-flight batch per key (design Q27); if the assembler
 * answered with the batches alone, that key would leave the run with nothing at all to say about
 * it, and a register waiting on a document that never came back would look exactly like a night
 * with no registers in it. That silence is the shape defect P5 is about, one step earlier in the
 * flow.
 *
 * <p>{@link #deferred()} therefore carries the keys the run passed over, so the run report can
 * count them and an operator can see the day is owed a second document. It is a list of keys and
 * not of records: a court centre and a date are bounded values, and the registers behind them
 * carry defendants (constitution Principle VII).
 *
 * @param batches  the batches to render this run, each with the registers it was assembled from,
 *                 in the order the registers were recorded
 * @param deferred the keys whose registers wait on a batch that has not finished; empty on a run
 *                 that assembled everything it read
 */
public record BatchAssembly(List<AssembledBatch> batches, List<CourtCentreDay> deferred) {

    /**
     * Freezes both lists, and settles absent and empty as one statement.
     *
     * <p>Unlike the outbound document's lists, where {@code null} and {@code []} say different
     * things to a schema, there is nothing here for the difference to mean: a run that assembled
     * nothing and a run that deferred nothing are both runs with an empty list, and a caller
     * counting either should not have to ask which kind of nothing it was given.
     */
    public BatchAssembly {
        batches = batches == null ? List.of() : List.copyOf(batches);
        deferred = deferred == null ? List.of() : List.copyOf(deferred);
    }
}
