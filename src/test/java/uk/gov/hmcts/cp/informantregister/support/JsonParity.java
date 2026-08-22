package uk.gov.hmcts.cp.informantregister.support;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Compares a ported result against a golden file captured from the legacy function app.
 *
 * <p>The comparison rules are fixed by {@code .claude/rules/design_rules.md} and are not preferences:
 *
 * <ul>
 *   <li><strong>Field-order-insensitive.</strong> Object key order is an artefact of how each
 *       language happens to build its objects, and neither the JSON specification nor any consumer
 *       gives it meaning.</li>
 *   <li><strong>Array-order-sensitive.</strong> Order in this tree <em>is</em> meaning — which
 *       authority is the first fragment, which defendant is the first defendant, which result comes
 *       first. The legacy's ordering quirks are part of what is being preserved, so a comparison that
 *       sorted arrays would hide exactly the regressions this harness exists to catch.</li>
 *   <li><strong>BigDecimal-tolerant.</strong> This service reads floating-point values as
 *       {@code BigDecimal} so monetary amounts stay exact, while the golden files were written by a
 *       runtime with one numeric type. {@code 1}, {@code 1.0} and {@code 1.00} are therefore the same
 *       number here, compared by value rather than by representation or by node class.</li>
 * </ul>
 *
 * <p>Differences are reported together, with the JSON pointer of each, rather than one per run: a
 * parity failure is usually a systematic difference across many nodes, and being told about it one
 * node per test run turns a single fix into a dozen cycles.
 */
public final class JsonParity {

    /** The number of differences to report before truncating. */
    private static final int MAX_REPORTED = 25;

    private JsonParity() {
    }

    /**
     * Asserts that a ported result matches its golden file.
     *
     * @param expected the golden tree
     * @param actual   the ported tree
     * @param what     the case name, for the failure message
     * @throws AssertionError if the trees differ
     */
    public static void assertMatches(
            final JsonNode expected, final JsonNode actual, final String what) {

        final List<String> differences = new ArrayList<>();
        compare(expected, actual, "", differences);
        if (differences.isEmpty()) {
            return;
        }

        final StringBuilder message = new StringBuilder()
                .append(what)
                .append(" does not match the golden captured from the legacy function app — ")
                .append(differences.size())
                .append(" difference(s):");
        differences.stream().limit(MAX_REPORTED)
                .forEach(difference -> message.append("\n  ").append(difference));
        if (differences.size() > MAX_REPORTED) {
            message.append("\n  ... and ")
                    .append(differences.size() - MAX_REPORTED)
                    .append(" more");
        }
        throw new AssertionError(message.toString());
    }

    /**
     * Collects the differences between two nodes.
     *
     * @param expected    the golden node
     * @param actual      the ported node
     * @param path        the JSON pointer of this node
     * @param differences the differences found so far
     */
    private static void compare(
            final JsonNode expected,
            final JsonNode actual,
            final String path,
            final List<String> differences) {

        if (expected.isObject() && actual.isObject()) {
            compareObjects(expected, actual, path, differences);
            return;
        }
        if (expected.isArray() && actual.isArray()) {
            compareArrays(expected, actual, path, differences);
            return;
        }
        if (expected.isNumber() && actual.isNumber()) {
            if (expected.decimalValue().compareTo(actual.decimalValue()) != 0) {
                differences.add(at(path) + ": expected " + expected + " but was " + actual);
            }
            return;
        }
        if (!expected.equals(actual)) {
            differences.add(at(path) + ": expected " + describe(expected)
                    + " but was " + describe(actual));
        }
    }

    /**
     * Collects the differences between two objects, ignoring key order.
     *
     * @param expected    the golden object
     * @param actual      the ported object
     * @param path        the JSON pointer of this node
     * @param differences the differences found so far
     */
    private static void compareObjects(
            final JsonNode expected,
            final JsonNode actual,
            final String path,
            final List<String> differences) {

        final Set<String> names = new LinkedHashSet<>();
        expected.propertyNames().forEach(names::add);
        actual.propertyNames().forEach(names::add);

        for (final String name : names) {
            final JsonNode expectedValue = expected.get(name);
            final JsonNode actualValue = actual.get(name);
            final String childPath = path + "/" + name;

            if (expectedValue == null) {
                differences.add(at(childPath) + ": unexpected field, was "
                        + describe(actualValue));
            } else if (actualValue == null) {
                differences.add(at(childPath) + ": missing field, expected "
                        + describe(expectedValue));
            } else {
                compare(expectedValue, actualValue, childPath, differences);
            }
        }
    }

    /**
     * Collects the differences between two arrays, respecting order.
     *
     * @param expected    the golden array
     * @param actual      the ported array
     * @param path        the JSON pointer of this node
     * @param differences the differences found so far
     */
    private static void compareArrays(
            final JsonNode expected,
            final JsonNode actual,
            final String path,
            final List<String> differences) {

        if (expected.size() != actual.size()) {
            differences.add(at(path) + ": expected " + expected.size()
                    + " element(s) but was " + actual.size());
        }
        final int shared = Math.min(expected.size(), actual.size());
        for (int index = 0; index < shared; index++) {
            compare(expected.get(index), actual.get(index), path + "/" + index, differences);
        }
    }

    /**
     * Renders a node briefly enough to read in a failure message.
     *
     * @param node the node to render; may be {@code null}
     * @return a short rendering
     */
    private static String describe(final JsonNode node) {
        if (node == null) {
            return "absent";
        }
        final String rendered = node.toString();
        return rendered.length() <= 120 ? rendered : rendered.substring(0, 120) + "...";
    }

    /**
     * Renders a path, naming the root explicitly rather than as an empty string.
     *
     * @param path the JSON pointer
     * @return the path to show
     */
    private static String at(final String path) {
        return path.isEmpty() ? "(root)" : path;
    }
}
