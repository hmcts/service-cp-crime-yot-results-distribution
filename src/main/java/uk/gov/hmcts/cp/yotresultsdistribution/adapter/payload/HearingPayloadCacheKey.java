package uk.gov.hmcts.cp.yotresultsdistribution.adapter.payload;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The cache key the hearing payload is published under.
 *
 * <p>A direct port of {@code HearingResultedCacheQuery/index.js:83-88}'s {@code getCacheKey}: the
 * prefix, the hearing identifier, the hearing day when there is one, and the literal
 * {@code _result_} suffix. The producer writes the key and this service only reads it, so its shape
 * is not this service's to improve — and a key that is nearly right reads nothing, which is
 * indistinguishable from a hearing that was never cached.
 *
 * <p>Two forms, because the producer publishes two. The dated form is what a current share writes;
 * the undated form is its legacy twin, still readable and still read (the legacy builds exactly one
 * key, from whatever {@code hearingDate} it was handed — K6 pins the dated form and K1/K4 the
 * undated one).
 */
public final class HearingPayloadCacheKey {

    /** The literal the producer terminates every payload key with. */
    private static final String SUFFIX = "_result_";

    private HearingPayloadCacheKey() {
        // Key construction only.
    }

    /**
     * Builds the key for a hearing, with the hearing day when one is supplied.
     *
     * @param prefix     the payload prefix the producer writes under, {@code INT_} for this flow
     * @param hearingId  the hearing the payload belongs to
     * @param hearingDay the hearing day, or {@code null} for the legacy undated form
     * @return the key to read
     */
    public static String cacheKey(final String prefix, final UUID hearingId,
            final LocalDate hearingDay) {
        // UUID.toString and LocalDate.toString are both the canonical forms the producer writes:
        // lower-case hexadecimal and a zero-padded ISO date. Redis keys are bytes, so a key that
        // differs only in case or in a missing leading zero is simply a different key, and reading
        // it finds nothing at all.
        final String day = hearingDay == null ? "" : "_" + hearingDay;
        return prefix + hearingId + day + SUFFIX;
    }
}
