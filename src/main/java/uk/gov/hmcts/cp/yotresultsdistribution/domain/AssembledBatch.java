package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.util.List;
import java.util.Objects;

/**
 * One batch and the registers it was assembled from, kept together.
 *
 * <p>The pairing is the assembler's whole answer, and it is a pair rather than a batch alone
 * because the grouping cannot be re-derived from the batch afterwards. A key's records are not all
 * of that key's records: the ones recorded while the cutover flag was off belong to the legacy and
 * are left where they are, so a caller re-deriving "the records of this key" from the night's
 * active rows would find rows the assembler deliberately did not batch and stamp them anyway
 * (research §12, FR-015).
 *
 * <p>The records travel because two things are owed to them: the store stamps the batch identity
 * onto exactly these rows, and the payload mapper renders exactly these rows into the document. A
 * batch whose membership had to be worked out twice is a batch that can be rendered from one set of
 * registers and stamped onto another.
 *
 * @param batch   the batch, carrying the identity every downstream call correlates on
 * @param records the registers it groups, in the order they were recorded
 */
public record AssembledBatch(RegisterBatch batch, List<RegisterRecord> records) {

    /**
     * Refuses a pairing that is missing either half.
     *
     * <p>A batch with no records is a court centre and day held against every later run with
     * nothing to render, and records with no batch are registers nobody can correlate an event
     * back to.
     */
    public AssembledBatch {
        Objects.requireNonNull(batch, "an assembled batch names the batch it assembled");
        records = List.copyOf(Objects.requireNonNull(records,
                "an assembled batch names the registers it was assembled from"));
        if (records.isEmpty()) {
            throw new IllegalArgumentException(
                    "a batch is assembled from at least one register: " + batch.batchId());
        }
    }
}
