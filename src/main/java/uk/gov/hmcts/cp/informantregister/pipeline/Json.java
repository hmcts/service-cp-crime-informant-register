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
// PMD.OnlyOneReturn: the early returns mirror the legacy source's own, line for line —
// funnelling them through a single exit would reshape the very control flow the parity
// harness pins (constitution Principle I, bug-for-bug parity).
// PMD.ShortClassName/ShortMethodName: Json and its at() are terse on purpose, so a ported line
// reads at the density of the legacy property access it stands in for. Widening them would
// rewrite the shape of every mirrored line in this package.
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
     * <p>Absence maps to {@code null} rather than to an empty string because the legacy code pushes
     * the missing value straight into its output, where {@code undefined} is dropped from an object
     * and written as {@code null} inside an array.
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
     * that has no register in it changes, which is deviations-register entry 7.
     *
     * <p>Callers that need to distinguish "absent" from "empty" — because the legacy code branches on
     * truthiness before iterating — must ask {@link #truthy(JsonNode, String)} separately. This
     * method is for the iteration itself, which is the same either way.
     *
     * <p>Use this method <strong>only</strong> where the legacy guards the iteration — with
     * {@code || []}, or with an {@code if} on the same field. Where it dereferences the array with no
     * guard at all, an absent field throws there too, and {@link #dereferencedArray} is the method
     * that says so. The per-call-site audit those two forms need has been done; each call site names
     * the legacy line it reproduces.
     *
     * @param node  the object to read; may be {@code null}
     * @param field the field name
     * @return the elements, never {@code null}
     * @throws TransformationFailedException if the field holds a truthy value that is not an array
     */
    /* default */ static List<JsonNode> array(final JsonNode node, final String field) {
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

    /**
     * The elements of an array field the legacy dereferences <strong>without</strong> a guard, as
     * {@code parent.field.forEach} would iterate them.
     *
     * <p>The difference from {@link #array} is the absent case, and it decides whether a register
     * exists. {@code undefined.forEach} is a {@code TypeError}: where the legacy writes
     * {@code prosecutionCase.defendants.forEach(...)} with no {@code || []} and no enclosing
     * {@code if}, a payload missing that field kills the whole hearing and no register is produced
     * for anybody. Reading it as "nothing to iterate" would carry on and emit a register the legacy
     * never sent — to a real prosecuting authority — which is the one direction a bug-for-bug port
     * must never drift in (`.claude/rules/design_rules.md`, "Parity and the Deviations Register").
     *
     * <p>So this refuses an absent field, an explicit null, and a value that is not an array alike:
     * all three are the same {@code TypeError} in the legacy. An <em>empty</em> array is not refused
     * — iterating one is legal and yields nothing.
     *
     * @param node  the object being dereferenced; may be {@code null}, which is itself a refusal
     * @param field the field name
     * @return the elements, never {@code null}
     * @throws TransformationFailedException if the field cannot be iterated
     */
    /* default */ static List<JsonNode> dereferencedArray(final JsonNode node, final String field) {
        final JsonNode value = at(node, field);
        if (value == null || !value.isArray()) {
            // The field name is this service's own vocabulary, so it is safe to name. The value is
            // the producer's, and may be defendant detail, so it is never quoted.
            throw new TransformationFailedException(
                    "hearing field '" + field + "' cannot be iterated");
        }
        return value.valueStream().toList();
    }

    /**
     * A field the legacy reads <strong>through</strong> without a guard, as {@code parent.field.x}
     * would reach it.
     *
     * <p>The scalar counterpart of {@link #dereferencedArray}, and it refuses the same two values for
     * the same reason. In JavaScript only {@code undefined} and {@code null} throw when a property is
     * read off them; anything else answers {@code undefined} and the expression carries on. So an
     * absent field and an explicit JSON null are refused here — the legacy dies there and the hearing
     * produces no register for anybody — while a value of the wrong shape is returned, because
     * reading a property off it is legal and yields nothing.
     *
     * <p>Reading a missing parent as "no value" instead would carry on and emit a register the legacy
     * never sent, to a real prosecuting authority. That is the one direction a bug-for-bug port must
     * not drift in; the refusal is deviations-register entry 7.
     *
     * @param node  the object being dereferenced; may be {@code null}, which is itself a refusal
     * @param field the field name
     * @return the field's value, never {@code null}
     * @throws TransformationFailedException if the field is absent or JSON null
     */
    /* default */ static JsonNode dereferenced(final JsonNode node, final String field) {
        final JsonNode value = at(node, field);
        if (value == null || value.isNull()) {
            // The field name is this service's own vocabulary, so it is safe to name. The value is
            // the producer's, and may be defendant detail, so it is never quoted.
            throw new TransformationFailedException(
                    "hearing field '" + field + "' cannot be read through");
        }
        return value;
    }

    /**
     * An array element the legacy reads <strong>through</strong>, as {@code element.x} would reach
     * it.
     *
     * <p>The element counterpart of {@link #dereferenced}. JSON arrays can hold nulls and the
     * fragment tree deliberately preserves them (`FragmentLists`), so an iteration that reads a
     * property off every member meets one eventually — {@code subscription.forDistribution}
     * (`RecipientMapper.js:15`), {@code prompt.isFinancialImposition} (`ResultDataMapper.js:28`),
     * {@code offence.offenceCode} (`OffenceMapper.js:62`), {@code s.isInformantRegisterSubscription}
     * (`InformantRegisterSubscriptions/index.js:28`). Every one of those is a {@code TypeError} in
     * the legacy, which kills the hearing, so reading the member as "nothing set" and emitting a
     * register the legacy never sent is the one direction this port must not drift in
     * (`.claude/rules/design_rules.md`, "Parity and the Deviations Register").
     *
     * <p>Only {@code null} and {@code undefined} throw on a property read. A number, a string, an
     * array or an empty object all answer {@code undefined} and are simply falsy, so those are
     * passed through untouched and behave exactly as they do in the legacy.
     *
     * <p>Callers must apply this <strong>where the legacy dereferences</strong> and nowhere else.
     * JavaScript's iteration methods are lazy: {@code some} and {@code find} stop at the first hit,
     * so a null after the deciding element is never reached, while {@code filter} and
     * {@code forEach} always complete the pass. Each call site names the legacy line whose reach it
     * reproduces.
     *
     * @param element    the member being dereferenced; may be {@code null}, which is itself a refusal
     * @param collection the collection it came from, for the failure message
     * @return the element, never {@code null}
     * @throws TransformationFailedException if the element cannot be read through
     */
    /* default */ static JsonNode dereferencedElement(final JsonNode element, final String collection) {
        if (element == null || element.isNull() || element.isMissingNode()) {
            // The collection name is this service's own vocabulary, so it is safe to name. The
            // element is the producer's, and may be defendant detail, so it is never quoted.
            throw new TransformationFailedException(
                    "a member of '" + collection + "' cannot be read through");
        }
        return element;
    }

    /**
     * Whether a field would satisfy {@code parent.field && parent.field.length > 0}.
     *
     * <p>The second half is the reason this is not {@code !array(node, field).isEmpty()}. A truthy
     * value that is <em>not</em> an array has no {@code length}, and {@code undefined > 0} is
     * {@code false} — so the legacy skips the guarded block quietly and carries on with the rest of
     * the hearing. Refusing there, as {@link #array} would, would lose a register the legacy
     * produces.
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
