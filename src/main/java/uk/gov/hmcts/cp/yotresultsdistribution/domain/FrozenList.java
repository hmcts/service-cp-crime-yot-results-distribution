package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.util.List;

/**
 * How every outbound record freezes a list without inventing one.
 *
 * <p>The four records of the {@link CourtRegisterDocument} family each carry between one and four
 * lists, and every one of them has the same two things to say: the list is frozen when the record is
 * built, and {@code null} is not quietly promoted to empty. The second half is the load-bearing one.
 * Every list on the vendored progression schemas carries {@code minItems: 1}, so an empty array is
 * not a quieter way of saying "nothing" — it is a document progression rejects — and the mappers
 * behind those lists disagree with each other on purpose about which they answer with (the alias
 * mapper says {@code []} for an empty input where the counsel mapper says nothing for both).
 *
 * <p>Written once here rather than ten times inline because ten copies of one rule are ten places
 * for it to drift, and because the comparator that guards this port treats absent, null and empty as
 * three different statements — a rule worth being able to read in a single place.
 */
final class FrozenList {

    private FrozenList() {
    }

    /**
     * An unmodifiable copy of the list, or {@code null} where there was no list.
     *
     * @param list the list to freeze, or {@code null}
     * @param <T>  the element type
     * @return the frozen copy, or {@code null}
     */
    /* default */ static <T> List<T> frozen(final List<T> list) {
        return list == null ? null : List.copyOf(list);
    }
}
