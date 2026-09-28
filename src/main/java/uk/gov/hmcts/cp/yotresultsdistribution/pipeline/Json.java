package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.util.Collections;
import java.util.List;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationFailedException;

/**
 * Reads a hearing tree with JavaScript's semantics rather than Java's.
 *
 * <p>The pipeline being ported is a long sequence of truthiness tests over an untyped object graph,
 * and the difference between JavaScript's rules and the intuitive Java ones changes which register
 * gets built. The three that bite here:
 *
 * <ul>
 *   <li><strong>An empty array is truthy.</strong> {@code if (hearingObj.hearingDays)} is entered
 *       when {@code hearingDays} is {@code []}, and the {@code SetCourtRegister} fixture has exactly
 *       that. Treating "empty" as "absent" takes the other branch and gives the register no hearing
 *       date at all.</li>
 *   <li><strong>Absent and null are both falsy</strong>, and so is an empty string and a zero. A
 *       field present as JSON {@code null} behaves the same as a missing one — except where the
 *       legacy tests {@code === undefined}, which is a different question and is asked with
 *       {@link #at} rather than with {@link #truthy}.</li>
 *   <li><strong>Reading a property off {@code undefined} throws.</strong> Where the legacy
 *       dereferences without a guard, a payload missing that field kills the whole hearing and no
 *       register is produced for anybody. {@link #dereferenced}, {@link #dereferencedArray} and
 *       {@link #dereferencedElement} are the methods that say so; reading such a field as "nothing
 *       there" would carry on and emit a register the legacy never sent.</li>
 * </ul>
 *
 * <p>Nothing here mutates: every method reads. The tree belongs to whoever fetched it, and the core
 * treats it as immutable (constitution Principle IV).
 */
// PMD.OnlyOneReturn: the early returns mirror the legacy expressions these methods stand in for,
// clause for clause; funnelling them through a single exit would reshape the very control flow the
// port is reviewed against. PMD.ShortClassName/ShortMethodName: Json and its at() are terse on
// purpose, so a ported line reads at the density of the legacy property access it replaces.
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.ShortClassName", "PMD.ShortMethodName"})
final class Json {

    private Json() {
    }

    /**
     * The value of a field, or {@code null} if the parent or the field is absent.
     *
     * @param node  the object to read; may be {@code null}
     * @param field the field name
     * @return the field's node, or {@code null}
     */
    /* default */ static JsonNode at(final JsonNode node, final String field) {
        return node == null ? null : node.get(field);
    }

    /**
     * The text of a field, or {@code null} when the field is absent or JSON null.
     *
     * <p>Absence maps to {@code null} rather than to an empty string because the legacy pushes the
     * missing value straight into its output, where {@code undefined} is dropped from an object and
     * written as {@code null} inside an array.
     *
     * @param node  the object to read; may be {@code null}
     * @param field the field name
     * @return the field's text, or {@code null}
     */
    /* default */ static String text(final JsonNode node, final String field) {
        final JsonNode value = at(node, field);
        return value == null || value.isNull() ? null : value.stringValue();
    }

    /**
     * Whether a field would satisfy {@code if (parent.field)} in JavaScript.
     *
     * @param node  the object to read; may be {@code null}
     * @param field the field name
     * @return whether the field is truthy
     */
    /* default */ static boolean truthy(final JsonNode node, final String field) {
        return truthy(at(node, field));
    }

    /**
     * Whether a value would satisfy {@code if (value)} in JavaScript.
     *
     * @param value the value to test; may be {@code null}
     * @return whether the value is truthy
     */
    /* default */ static boolean truthy(final JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            return value.doubleValue() != 0d;
        }
        if (value.isString()) {
            return !value.stringValue().isEmpty();
        }
        return true;
    }

    /**
     * The elements of an array field, exactly as {@code (parent.field || []).forEach} — or an
     * {@code if} on the same field followed by a {@code forEach} — would iterate them.
     *
     * <p>A <strong>falsy</strong> field is replaced by an empty array and iterates over nothing. A
     * <strong>truthy value that is not an array</strong> is not: {@code ({}).forEach} is not a
     * function, so the legacy throws, the activity handler swallows it, and the hearing produces
     * nothing at all. Answering "no elements" there would be the one answer the legacy never gives —
     * it would turn a payload the transformation cannot read into a legitimate empty business result
     * and complete the request with nothing to replay.
     *
     * <p>Callers that need to distinguish "absent" from "empty" — because the legacy branches on
     * truthiness before iterating — must ask {@link #truthy(JsonNode, String)} separately.
     *
     * @param node  the object to read; may be {@code null}
     * @param field the field name
     * @return the elements, never {@code null}
     * @throws TransformationFailedException if the field holds a truthy value that is not an array
     */
    /* default */ static List<JsonNode> array(final JsonNode node, final String field) {
        return elements(at(node, field), field);
    }

    /**
     * The elements of a value the caller already holds, on the same terms as {@link #array}.
     *
     * <p>The mappers that are handed an array rather than the object it hangs off — the alias list
     * is the one that matters, because its own truthiness decides between an empty answer and no
     * answer at all — need the iteration rule without the field lookup. The field name is still
     * taken, because it is what the refusal names, and naming the shape that was wrong is the whole
     * value of the refusal.
     *
     * @param value the value to iterate; may be {@code null}
     * @param field the field the value came from, for the failure message
     * @return the elements, never {@code null}
     * @throws TransformationFailedException if the value is truthy and is not an array
     */
    /* default */ static List<JsonNode> elements(final JsonNode value, final String field) {
        if (!truthy(value)) {
            return Collections.emptyList();
        }
        if (!value.isArray()) {
            // The field name is this service's own vocabulary, so it is safe to name. The value is
            // the producer's, and may be defendant detail, so it is never quoted.
            throw new TransformationFailedException(
                    "hearing field '" + field + "' is not an array");
        }
        return value.valueStream().toList();
    }

    /**
     * The elements of an array field the legacy dereferences <strong>without</strong> a guard, as
     * {@code parent.field.forEach} would iterate them.
     *
     * <p>The difference from {@link #array} is the absent case, and it decides whether a register
     * exists at all. {@code undefined.forEach} throws: where the legacy writes
     * {@code prosecutionCase.defendants.forEach(...)} with no {@code || []} and no enclosing
     * {@code if}, a payload missing that field kills the whole hearing. So an absent field, an
     * explicit null and a value that is not an array are refused alike. An <em>empty</em> array is
     * not: iterating one is legal and yields nothing.
     *
     * @param node  the object being dereferenced; may be {@code null}, which is itself a refusal
     * @param field the field name
     * @return the elements, never {@code null}
     * @throws TransformationFailedException if the field cannot be iterated
     */
    /* default */ static List<JsonNode> dereferencedArray(final JsonNode node, final String field) {
        final JsonNode value = at(node, field);
        if (value == null || !value.isArray()) {
            throw new TransformationFailedException(
                    "hearing field '" + field + "' cannot be iterated");
        }
        return value.valueStream().toList();
    }

    /**
     * A field the legacy reads <strong>through</strong> without a guard, as {@code parent.field.x}
     * would reach it.
     *
     * <p>The scalar counterpart of {@link #dereferencedArray}. In JavaScript only {@code undefined}
     * and {@code null} throw when a property is read off them; anything else answers
     * {@code undefined} and the expression carries on. So an absent field and an explicit JSON null
     * are refused here, while a value of the wrong shape is returned untouched.
     *
     * @param node  the object being dereferenced; may be {@code null}, which is itself a refusal
     * @param field the field name
     * @return the field's value, never {@code null}
     * @throws TransformationFailedException if the field is absent or JSON null
     */
    /* default */ static JsonNode dereferenced(final JsonNode node, final String field) {
        final JsonNode value = at(node, field);
        if (value == null || value.isNull()) {
            throw new TransformationFailedException(
                    "hearing field '" + field + "' cannot be read through");
        }
        return value;
    }

    /**
     * An array element the legacy reads <strong>through</strong>, as {@code element.x} would reach
     * it.
     *
     * <p>JSON arrays can hold nulls, and an iteration that reads a property off every member meets
     * one eventually — {@code judicialResult.isDeleted} at four separate gather passes,
     * {@code defendant.masterDefendantId}, {@code offence.judicialResults}. Every one of those is a
     * throw in the legacy, which kills the hearing, so reading the member as "nothing set" and
     * emitting a register the legacy never sent is the one direction this port must not drift in.
     *
     * @param element    the member being dereferenced; may be {@code null}, which is itself a refusal
     * @param collection the collection it came from, for the failure message
     * @return the element, never {@code null}
     * @throws TransformationFailedException if the element cannot be read through
     */
    /* default */ static JsonNode dereferencedElement(
            final JsonNode element, final String collection) {

        if (element == null || element.isNull() || element.isMissingNode()) {
            throw new TransformationFailedException(
                    "a member of '" + collection + "' cannot be read through");
        }
        return element;
    }

    /**
     * Whether a field would satisfy {@code parent.field && parent.field.length > 0}.
     *
     * <p>The second half is why this is not {@code !array(node, field).isEmpty()}. A truthy value
     * that is <em>not</em> an array has no {@code length}, and {@code undefined > 0} is
     * {@code false} — so the legacy skips the guarded block quietly and carries on with the rest of
     * the hearing, where {@link #array} would refuse and lose a register the legacy produces.
     *
     * @param node  the object to read; may be {@code null}
     * @param field the field name
     * @return whether the field is an array with at least one element
     */
    /* default */ static boolean nonEmptyArray(final JsonNode node, final String field) {
        final JsonNode value = at(node, field);
        return value != null && value.isArray() && !value.isEmpty();
    }
}
