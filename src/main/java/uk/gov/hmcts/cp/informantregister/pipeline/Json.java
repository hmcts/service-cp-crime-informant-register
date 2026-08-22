package uk.gov.hmcts.cp.informantregister.pipeline;

import java.util.Collections;
import java.util.List;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;

/**
 * Reads a canonical hearing tree with JavaScript's semantics rather than Java's.
 *
 * <p>The transformation being ported is a set of truthiness tests over an untyped object graph, and
 * the difference between JavaScript's rules and the intuitive Java ones changes the output. The two
 * that bite here:
 *
 * <ul>
 *   <li><strong>An empty array is truthy.</strong> {@code if (hearingObj.hearingDays)} is entered
 *       when {@code hearingDays} is {@code []}, and the fixture
 *       {@code hearing-results-from-prosecution-case.json} has exactly that. Treating "empty" as
 *       "absent" would take the other branch and produce a different hearing date.</li>
 *   <li><strong>Absent and null are both falsy, and so is an empty string.</strong> A field present
 *       as JSON {@code null} must behave the same as a missing one.</li>
 * </ul>
 *
 * <p>Nothing here mutates: every method reads. The tree belongs to whoever fetched it, and the core
 * treats it as immutable (constitution Principle IV).
 */
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
    static JsonNode at(final JsonNode node, final String field) {
        return node == null ? null : node.get(field);
    }

    /**
     * The text of a field, or {@code null} when the field is absent or JSON null.
     *
     * <p>Absence maps to {@code null} rather than to an empty string because the legacy code pushes
     * the missing value straight into its output, where {@code undefined} is dropped from an object
     * and written as {@code null} inside an array.
     *
     * @param node  the object to read; may be {@code null}
     * @param field the field name
     * @return the field's text, or {@code null}
     */
    static String text(final JsonNode node, final String field) {
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
    static boolean truthy(final JsonNode node, final String field) {
        return truthy(at(node, field));
    }

    /**
     * Whether a value would satisfy {@code if (value)} in JavaScript.
     *
     * @param value the value to test; may be {@code null}
     * @return whether the value is truthy
     */
    static boolean truthy(final JsonNode value) {
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
     * The elements of an array field, exactly as {@code (parent.field || []).forEach} would iterate
     * them.
     *
     * <p>That expression has two halves and this method reproduces both. A <strong>falsy</strong>
     * field — absent, JSON {@code null}, {@code false}, {@code 0} or an empty string — is replaced by
     * an empty array and iterates over nothing. A <strong>truthy value that is not an array</strong>
     * is not: {@code ({}).forEach} is not a function, so the legacy throws a {@code TypeError}, the
     * activity handler swallows it, and the hearing produces nothing at all. Returning "no elements"
     * for that case would be the one answer the legacy never gives — it would turn a payload the
     * transformation cannot read into a legitimate empty business result, complete the request, and
     * leave nothing to replay. So it is refused, non-transiently, and the delivery is parked where
     * support can see it. The register's content is untouched by that; only the handling of a payload
     * that has no register in it changes, which is deviations-register entry 2.
     *
     * <p>Callers that need to distinguish "absent" from "empty" — because the legacy code branches on
     * truthiness before iterating — must ask {@link #truthy(JsonNode, String)} separately. This
     * method is for the iteration itself, which is the same either way.
     *
     * <p>The looser half is deliberate and bounded: where the legacy dereferences an array with no
     * {@code || []} guard at all it would also throw on an <em>absent</em> field, and this method
     * still answers "nothing to iterate" there. Tightening that needs a per-call-site audit against
     * the legacy source, since the two forms appear side by side, and it is not what this method
     * decides.
     *
     * @param node  the object to read; may be {@code null}
     * @param field the field name
     * @return the elements, never {@code null}
     * @throws TransformationFailedException if the field holds a truthy value that is not an array
     */
    static List<JsonNode> array(final JsonNode node, final String field) {
        final JsonNode value = at(node, field);
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
}
