package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationFailedException;

/**
 * Which ordered date the register is dated by — and, just as importantly, which ones are compared.
 *
 * <p>Ports {@code NowsHelper/service/RegisterFragmentService.js:30-44}, whose body is a descending
 * sort indexed at zero. Defect C13 is the {@code catch} wrapped around that sort: it calls
 * {@code this.context.log} in an arrow-function module export where {@code this} is unbound, so the
 * handler that was supposed to report a bad date throws a {@code TypeError} of its own, the original
 * cause is lost, and {@code SetCourtRegister} swallows the lot into {@code Success: true}. There is
 * no catch here; a date the comparator cannot read is the one classified failure {@link Dates}
 * raises.
 *
 * <p><strong>What the sort compares is not everything it is given</strong>, and reproducing that is
 * the point of this class rather than of a one-line {@code max}. ECMA-262 gives two rules that
 * decide whether an unreadable ordered date ever reaches the comparator at all:
 *
 * <ul>
 *   <li><strong>Fewer than two elements are never compared.</strong> A hearing whose only ordered
 *       date is unreadable carries it straight through, and the refusal — if there is one — happens
 *       later, where the value is rendered.</li>
 *   <li><strong>{@code undefined} elements are removed before the comparator runs</strong>
 *       ({@code SortIndexedProperties}), so a result recorded with no {@code orderedDate} at all
 *       cannot decide, or destroy, a hearing's date.</li>
 * </ul>
 *
 * <p>An <em>explicit</em> JSON null is not covered by the second rule: JavaScript {@code null} is an
 * ordinary value to {@code sort}, is handed to the comparator, and is not a date. That is why the
 * ordered dates travel through here as nodes rather than as strings — the difference between
 * "absent" and "null" is the difference between a register and a refusal, and a Java {@code String}
 * cannot carry it.
 */
// PMD.OnlyOneReturn: the three answers below are three different readings of the same sort — an
// empty comparison, an uncompared sole element, and a real comparison — and naming each where it is
// reached is what makes the ECMA-262 rules above auditable against the code.
@SuppressWarnings("PMD.OnlyOneReturn")
final class OrderedDates {

    /** The size at which {@code sort} answers without ever calling the comparator. */
    private static final int SOLE_ELEMENT = 1;

    private OrderedDates() {
    }

    /**
     * The latest of the ordered dates a hearing recorded, as {@code sort(...)[0]} answers it.
     *
     * @param orderedDates the ordered-date values in the order the legacy collected them; a
     *                     {@code null} element is an absent field, JavaScript's {@code undefined}
     * @param dates        the date handling whose ordering key the comparator uses
     * @return the latest ordered date, or {@code null} when the hearing recorded none
     * @throws TransformationFailedException if the comparator meets a date it cannot read
     */
    /* default */ static String latest(
            final Collection<JsonNode> orderedDates, final Dates dates) {

        final List<JsonNode> compared = new ArrayList<>(orderedDates.size());
        for (final JsonNode orderedDate : orderedDates) {
            if (orderedDate != null) {
                compared.add(orderedDate);
            }
        }
        if (compared.isEmpty()) {
            // Every element was `undefined`, so `sorted[0]` is `undefined` too.
            return null;
        }
        if (compared.size() == SOLE_ELEMENT) {
            // One element: `sort` returns it without ever calling the comparator, so a date nothing
            // can read is carried rather than refused here.
            return textOf(compared.get(0));
        }
        return compared.stream()
                .map(OrderedDates::textOf)
                .max(Comparator.comparing(dates::orderingKey))
                .orElse(null);
    }

    /**
     * An ordered-date value as the comparator sees it.
     *
     * @param orderedDate the node
     * @return its text, or {@code null} for an explicit JSON null — which the comparator refuses
     */
    private static String textOf(final JsonNode orderedDate) {
        return orderedDate.isNull() ? null : orderedDate.stringValue();
    }
}
