package uk.gov.hmcts.cp.informantregister.pipeline;

import java.util.Collections;
import java.util.List;
import tools.jackson.databind.JsonNode;

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
     * The elements of an array field, or an empty list when the field is absent or not an array.
     *
     * <p>Callers that need to distinguish "absent" from "empty" — because the legacy code branches on
     * truthiness before iterating — must ask {@link #truthy(JsonNode, String)} separately. This
     * method is for the iteration itself, which is the same either way.
     *
     * @param node  the object to read; may be {@code null}
     * @param field the field name
     * @return the elements, never {@code null}
     */
    static List<JsonNode> array(final JsonNode node, final String field) {
        final JsonNode value = at(node, field);
        if (value == null || !value.isArray()) {
            return Collections.emptyList();
        }
        return value.valueStream().toList();
    }
}
