package uk.gov.hmcts.cp.yotresultsdistribution.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * Compares a ported result against a golden file captured from the legacy function app.
 *
 * <p>The comparison rules are fixed by the service's technical rules on golden-parity tests and
 * are not preferences:
 *
 * <ul>
 *   <li><strong>Field-order-insensitive.</strong> Object key order is an artefact of how each
 *       language happens to build its objects, and neither the JSON specification nor any consumer
 *       gives it meaning.</li>
 *   <li><strong>Array-order-sensitive.</strong> Order in this tree <em>is</em> meaning — which
 *       defendant is the first defendant, which result comes first, which recipient heads the list.
 *       The legacy's ordering quirks are part of what is being preserved wherever no C-number says
 *       otherwise, so a comparison that sorted arrays would hide exactly the regressions this
 *       harness exists to catch.</li>
 *   <li><strong>BigDecimal-tolerant.</strong> This service reads floating-point values as
 *       {@code BigDecimal} so monetary amounts stay exact, while the golden files were written by a
 *       runtime with one numeric type. {@code 1}, {@code 1.0} and {@code 1.00} are therefore the same
 *       number here, compared by value rather than by representation or by node class.</li>
 * </ul>
 *
 * <p><strong>One rule was added, and it subtracts nothing.</strong> A component named in
 * {@link RegisteredDefectFixes} is checked by <em>derivation</em> instead of by equality: the golden
 * file keeps the Node oracle's rendering, and the comparator computes from it the value the port is
 * now required to write. Nothing is skipped and nothing is excluded — the check is as strict as
 * equality was, and the register itself is tested in {@code JsonParityTest}. Each registered entry
 * carries the C-number of the {@code doc/DEFECT-FIXES.md} row that authorises it.
 *
 * <p>Differences are reported together, with the JSON pointer of each, rather than one per run: a
 * parity failure is usually a systematic difference across many nodes, and being told about it one
 * node per test run turns a single fix into a dozen cycles.
 *
 * <p><strong>They are also reported as data.</strong> {@link #differences} answers with the nodes
 * either side of each difference and not only with a sentence about them, because the differential
 * audit has to ask a further question of every one — which {@code doc/DEFECT-FIXES.md} row explains
 * this — and a register predicate reading a difference back out of a formatted string would be
 * parsing this class's prose.
 */
public final class JsonParity {

    /** The number of differences to report before truncating. */
    private static final int MAX_REPORTED = 25;

    /** How much of a node is rendered into a failure message before it is elided. */
    private static final int RENDER_LIMIT = 120;

    /** Room for the preamble of a failure message, before the differences are appended. */
    private static final int MESSAGE_BUFFER = 256;

    private JsonParity() {
    }

    /**
     * Asserts that a ported result matches its golden file, reconciled against the defect-fix
     * register.
     *
     * @param expected the golden tree
     * @param actual   the ported tree
     * @param what     the case name, for the failure message
     * @throws AssertionError if the trees differ
     */
    public static void assertMatches(
            final JsonNode expected, final JsonNode actual, final String what) {
        assertMatches(expected, actual, what, RegisteredDefectFixes::forProperty);
    }

    /**
     * The same assertion against a caller-supplied register.
     *
     * <p>The register is a parameter for one reason: {@link RegisteredDefectFixes} is legitimately
     * empty until a fix earns a row there, and a derivation mechanism nobody can reach is a
     * mechanism nobody has tested. {@code JsonParityTest} supplies its own lookup so the branch is
     * pinned from the day it is vendored, and the first real entry lands on machinery that already
     * works. Every caller that is comparing against the oracle uses the three-argument form, which
     * is the register.
     *
     * @param expected the golden tree
     * @param actual   the ported tree
     * @param what     the case name, for the failure message
     * @param register the fix registered against a property name, or {@code null} for none
     * @throws AssertionError if the trees differ
     */
    public static void assertMatches(
            final JsonNode expected,
            final JsonNode actual,
            final String what,
            final Function<String, RegisteredDefectFixes.Fix> register) {

        final List<Difference> differences = differences(expected, actual, register);
        if (differences.isEmpty()) {
            return;
        }

        final StringBuilder message = new StringBuilder(MESSAGE_BUFFER)
                .append(what)
                .append(" does not match the golden captured from the legacy function app — ")
                .append(differences.size())
                .append(" difference(s):");
        differences.stream().limit(MAX_REPORTED)
                .forEach(difference -> message.append("\n  ").append(difference.message()));
        if (differences.size() > MAX_REPORTED) {
            message.append("\n  ... and ")
                    .append(differences.size() - MAX_REPORTED)
                    .append(" more");
        }
        throw new AssertionError(message.toString());
    }

    /**
     * Every difference between a ported result and its golden file, reconciled against the
     * defect-fix register.
     *
     * @param expected the golden tree
     * @param actual   the ported tree
     * @return the differences, empty where the trees agree
     */
    public static List<Difference> differences(final JsonNode expected, final JsonNode actual) {
        return differences(expected, actual, RegisteredDefectFixes::forProperty);
    }

    /**
     * The same, against a caller-supplied register.
     *
     * @param expected the golden tree
     * @param actual   the ported tree
     * @param register the fix registered against a property name, or {@code null} for none
     * @return the differences, empty where the trees agree
     */
    public static List<Difference> differences(
            final JsonNode expected,
            final JsonNode actual,
            final Function<String, RegisteredDefectFixes.Fix> register) {

        final List<Difference> differences = new ArrayList<>();
        compare(expected, actual, "", differences, register);
        return List.copyOf(differences);
    }

    /**
     * One difference, as the nodes either side of it and the sentence about them.
     *
     * @param path     the JSON pointer of the component, or the empty string for the root
     * @param expected the golden node, or {@code null} where the golden carries nothing there
     * @param actual   the ported node, or {@code null} where the port wrote nothing there
     * @param message  the difference as a reader of a red build meets it
     */
    public record Difference(String path, JsonNode expected, JsonNode actual, String message) {
    }

    /**
     * Collects the differences between two nodes.
     *
     * @param expected    the golden node
     * @param actual      the ported node
     * @param path        the JSON pointer of this node
     * @param differences the differences found so far
     * @param register    the fix registered against a property name, or {@code null} for none
     */
    private static void compare(
            final JsonNode expected,
            final JsonNode actual,
            final String path,
            final List<Difference> differences,
            final Function<String, RegisteredDefectFixes.Fix> register) {

        if (expected.isObject() && actual.isObject()) {
            compareObjects(expected, actual, path, differences, register);
        } else if (expected.isArray() && actual.isArray()) {
            compareArrays(expected, actual, path, differences, register);
        } else if (expected.isNumber() && actual.isNumber()) {
            if (expected.decimalValue().compareTo(actual.decimalValue()) != 0) {
                differences.add(difference(path, expected, actual,
                        "expected " + expected + " but was " + actual));
            }
        } else if (!expected.equals(actual)) {
            differences.add(difference(path, expected, actual,
                    "expected " + describe(expected) + " but was " + describe(actual)));
        }
    }

    /**
     * Collects the differences between two objects, ignoring key order.
     *
     * @param expected    the golden object
     * @param actual      the ported object
     * @param path        the JSON pointer of this node
     * @param differences the differences found so far
     * @param register    the fix registered against a property name, or {@code null} for none
     */
    private static void compareObjects(
            final JsonNode expected,
            final JsonNode actual,
            final String path,
            final List<Difference> differences,
            final Function<String, RegisteredDefectFixes.Fix> register) {

        final Set<String> names = new LinkedHashSet<>();
        expected.propertyNames().forEach(names::add);
        actual.propertyNames().forEach(names::add);

        for (final String name : names) {
            final JsonNode expectedValue = expected.get(name);
            final JsonNode actualValue = actual.get(name);
            final String childPath = path + "/" + name;

            if (expectedValue == null) {
                differences.add(difference(childPath, null, actualValue,
                        "unexpected field, was " + describe(actualValue)));
            } else if (actualValue == null) {
                differences.add(difference(childPath, expectedValue, null,
                        "missing field, expected " + describe(expectedValue)));
            } else {
                final RegisteredDefectFixes.Fix fix = register.apply(name);
                if (fix == null) {
                    compare(expectedValue, actualValue, childPath, differences, register);
                } else {
                    compareUnderFix(fix, expectedValue, actualValue, childPath, differences);
                }
            }
        }
    }

    /**
     * Checks a registered component by derivation rather than by equality.
     *
     * <p>The golden file keeps the legacy rendering — it is the Node oracle's truth and is never
     * edited — so the fix computes what the port is now required to write and this demands exactly
     * that. Three outcomes, all of them loud: the derived value matches, the port wrote something
     * else, or the golden is in a shape the fix does not describe and the field is therefore no
     * longer covered by it.
     *
     * @param fix         the registered defect fix
     * @param expected    the golden node
     * @param actual      the ported node
     * @param path        the JSON pointer of this node
     * @param differences the differences found so far
     */
    private static void compareUnderFix(
            final RegisteredDefectFixes.Fix fix,
            final JsonNode expected,
            final JsonNode actual,
            final String path,
            final List<Difference> differences) {

        final List<String> permitted = expected.isString()
                ? fix.permittedFor(expected.stringValue())
                : List.of();

        if (permitted.isEmpty()) {
            differences.add(difference(path, expected, actual, "registered defect fix " + fix.reference()
                    + " cannot derive the required rendering from the golden value "
                    + describe(expected)
                    + " — the golden is not a value this fix describes, so the component is "
                    + "no longer covered by it and the difference stands"));
            return;
        }
        if (!actual.isString() || !permitted.contains(actual.stringValue())) {
            differences.add(difference(path, expected, actual, "registered defect fix " + fix.reference()
                    + " requires the golden " + describe(expected) + " to be re-rendered as "
                    + String.join(" or ", permitted) + " but was " + describe(actual)));
        }
    }

    /**
     * Collects the differences between two arrays, respecting order.
     *
     * @param expected    the golden array
     * @param actual      the ported array
     * @param path        the JSON pointer of this node
     * @param differences the differences found so far
     * @param register    the fix registered against a property name, or {@code null} for none
     */
    private static void compareArrays(
            final JsonNode expected,
            final JsonNode actual,
            final String path,
            final List<Difference> differences,
            final Function<String, RegisteredDefectFixes.Fix> register) {

        if (expected.size() != actual.size()) {
            differences.add(difference(path, expected, actual, "expected " + expected.size()
                    + " element(s) but was " + actual.size()));
        }
        final int shared = Math.min(expected.size(), actual.size());
        for (int index = 0; index < shared; index++) {
            compare(expected.get(index), actual.get(index), path + "/" + index, differences,
                    register);
        }
    }

    /**
     * Renders a node briefly enough to read in a failure message.
     *
     * @param node the node to render; may be {@code null}
     * @return a short rendering
     */
    private static String describe(final JsonNode node) {
        final String described;
        if (node == null) {
            described = "absent";
        } else {
            final String rendered = node.toString();
            described = rendered.length() <= RENDER_LIMIT
                    ? rendered
                    : rendered.substring(0, RENDER_LIMIT) + "...";
        }
        return described;
    }

    /**
     * One difference, with its path, its two nodes and the sentence a reader meets.
     *
     * @param path     the JSON pointer of the component
     * @param expected the golden node, or {@code null} where the golden carries nothing there
     * @param actual   the ported node, or {@code null} where the port wrote nothing there
     * @param what     the difference, in words
     * @return the difference
     */
    private static Difference difference(
            final String path, final JsonNode expected, final JsonNode actual, final String what) {
        return new Difference(path, expected, actual, pathLabel(path) + ": " + what);
    }

    /**
     * Renders a path, naming the root explicitly rather than as an empty string.
     *
     * @param path the JSON pointer
     * @return the path to show
     */
    private static String pathLabel(final String path) {
        return path.isEmpty() ? "(root)" : path;
    }
}
