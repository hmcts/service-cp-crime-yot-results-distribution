package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Everything written about a court register in the breath before it is posted.
 *
 * <p>One value rather than a parameter list because every component of it is known at the same
 * moment — the document has been assembled and validated, and nothing remains but the POST — and
 * because four of them are strings or identifiers that a positional call would happily accept in the
 * wrong order.
 *
 * <p>The court register produces at most one output per request, so {@code (source, requestId)} is
 * the whole key; the court centre, its OU code, the register day and the file name are descriptive
 * columns support reads, not part of what makes the row unique.
 *
 * <p><strong>The key is deliberately not here.</strong> It comes from the
 * {@link RunClaim} the output operation is fenced on, so the row a runner writes and the request
 * claim that authorises it cannot name different requests. Carrying {@code source} and
 * {@code requestId} in this record as well would make that mismatch representable, and a value that
 * disagrees with the fence is a register written against somebody else's hearing.
 *
 * <p>{@code requestDigest} and {@code anomalies} are the two things worth more after a failure than
 * after a success: what was attempted, and which parts of the register were skipped to produce it.
 * Both are written before the POST and neither is erased by its outcome.
 *
 * @param outputId          identity for a row written for the first time; a re-claim leaves the
 *                          existing row's identity alone
 * @param courtCentreId     the court centre the register was assembled for
 * @param courtCentreOuCode that court centre's OU code, or {@code null} where the hearing carried
 *                          none
 * @param registerDate      the register day derived from the command's shared time (fix C10)
 * @param fileName          the file name the document was built under (fix C11)
 * @param requestDigest     SHA-256 of exactly the bytes about to be sent
 * @param anomalies         how many of each guarded transformation anomaly this register survived;
 *                          empty where there were none
 */
public record ProcessedOutputClaim(
        UUID outputId,
        UUID courtCentreId,
        String courtCentreOuCode,
        LocalDate registerDate,
        String fileName,
        String requestDigest,
        Map<TransformationAnomaly, Integer> anomalies) {

    /**
     * A SHA-256 written the one way this service writes it: sixty-four hex characters, lower case.
     *
     * <p>The case matters as much as the length. Half the estate's hex formatters produce upper
     * case, and a column holding both forms of the same hash cannot be compared for equality — which
     * is the only thing the column exists to support.
     */
    private static final Pattern SHA_256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    /**
     * Refuses a claim that could not be reconciled or could not be told to an operator.
     *
     * @throws IllegalArgumentException if the digest is absent or is not a lower-case SHA-256, or if
     *                                  an anomaly is counted a number of times that is not positive
     */
    public ProcessedOutputClaim {
        if (requestDigest == null || !SHA_256_HEX.matcher(requestDigest).matches()) {
            // The value is not quoted. This message travels into a log line like every other bounded
            // reason here, and what it refused is the fingerprint of a document about a child.
            throw new IllegalArgumentException(
                    "requestDigest must be a SHA-256 as 64 lower-case hex characters");
        }
        anomalies = countsTaken(anomalies);
    }

    /**
     * Takes a copy of the counts, holding each to the one thing a count can be wrong about.
     *
     * <p>A copy because the transformation accumulates these as it walks the hearing: a claim
     * holding that same map would describe whatever the transformation did next rather than what it
     * sent. Absent and empty are the same statement — nothing was skipped — so the record settles it
     * once instead of leaving every reader to ask which it has.
     *
     * <p>Zero is refused rather than dropped. It asserts that an anomaly happened no times, which is
     * what an absent key already says; written to the column it reads as an incident that did not
     * occur, and the metric it is meant to agree with was never incremented.
     */
    private static Map<TransformationAnomaly, Integer> countsTaken(
            final Map<TransformationAnomaly, Integer> counted) {
        final Map<TransformationAnomaly, Integer> taken = new EnumMap<>(TransformationAnomaly.class);
        if (counted != null) {
            counted.forEach((anomaly, count) -> {
                if (count == null || count <= 0) {
                    throw new IllegalArgumentException(
                            "an anomaly is counted a positive number of times, or not at all: "
                                    + anomaly.value());
                }
                taken.put(anomaly, count);
            });
        }
        return Collections.unmodifiableMap(taken);
    }
}
