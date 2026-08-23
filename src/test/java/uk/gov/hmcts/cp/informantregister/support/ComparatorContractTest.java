package uk.gov.hmcts.cp.informantregister.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;

/**
 * Pins the golden-file comparator against an adversarial vector pack.
 *
 * <p>The golden-parity suite is only worth the disk it sits on if the comparator underneath it is
 * lenient in exactly the dimensions the contract relaxes and strict everywhere else. A comparator
 * that is lenient in the WRONG dimension — one that sorts arrays, say — reports PASS for every
 * golden file while the ported pipeline reorders defendants, and the entire suite silently stops
 * being evidence of anything. This class is the test that the tests are real.
 *
 * <p>The contract being pinned, from {@code .claude/rules/design_rules.md:192-193} and
 * {@code .claude/agents/spec-validator.md:76}:
 *
 * <ul>
 *   <li>field-order-<strong>in</strong>sensitive</li>
 *   <li>array-order-<strong>sensitive</strong></li>
 *   <li>BigDecimal-tolerant (scale-insensitive, <em>not</em> approximate)</li>
 *   <li>{@code NON_EXTENSIBLE} — an unexpected field is a difference</li>
 * </ul>
 *
 * <p><strong>All 57 vectors are decided.</strong> Eight were once marked {@code UNSPECIFIED} —
 * five presence pairs, two Unicode normalisation pairs and the IEEE-754 artefact — and all eight
 * are now {@code DIFFERENT}, each with its derivation recorded in its {@code rationale}. They were
 * not decided by preference: the contract's baseline outside its three enumerated relaxations is
 * exact structural and value equality, {@code NON_EXTENSIBLE} makes any field-set mismatch a
 * difference, and no rule licenses a presence, normalisation or epsilon relaxation. Leaving them
 * undecided while deriving every {@code type/} vector from that same principle was an
 * inconsistency, not caution.
 *
 * <p>What genuinely stays open is a different question and lives in {@code README.md}: what to DO
 * when the port and a legacy golden disagree on one of those points — a registered deviation in
 * {@code doc/DEVIATIONS.md}, not a widened comparator.
 *
 * <p>The {@code UNSPECIFIED} mechanism remains. A vector added later whose verdict the contract
 * does not determine is reported as skipped, carrying the decision that has to be taken, rather
 * than asserted in either direction — encoding a guess would quietly turn one team member's
 * opinion into the parity contract. Recording a decision means editing that vector's verdict to
 * {@code EQUAL} or {@code DIFFERENT}; the test then starts enforcing it with no change to this
 * file.
 *
 * <p><strong>Drop-in:</strong> put this file at
 * {@code src/test/java/uk/gov/hmcts/cp/informantregister/support/ComparatorContractTest.java} and
 * {@code vectors.json} at {@code src/test/resources/comparator-vectors/vectors.json}.
 */
@DisplayName("Golden-file comparator contract")
class ComparatorContractTest {

    private static final Logger LOG = LoggerFactory.getLogger(ComparatorContractTest.class);

    /** Classpath location of the vector pack. */
    private static final String VECTORS_RESOURCE = "/comparator-vectors/vectors.json";

    /**
     * The mapper the vectors are parsed with.
     *
     * <p>This MUST be the service's contract mapper. {@code USE_BIG_DECIMAL_FOR_FLOATS}
     * ({@code JacksonConfig#applyContractDefaults}) is what makes {@code 1.0} and {@code 1.00}
     * arrive as two distinct {@code BigDecimal} scales in the first place. Parse these vectors with
     * a default mapper instead and both collapse to the same {@code double}, every numeric-scale
     * vector passes trivially, and this class stops testing the thing it exists to test.
     */
    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    // ------------------------------------------------------------------------------------------
    // BINDING POINT — the only place this file names the comparator under test.
    //
    // Repoint these two lines at whatever the comparator is called; nothing else below refers to
    // it. Today that is JsonParity#assertMatches, which signals a difference by throwing an
    // AssertionError, so the adapter inverts it into a boolean.
    //
    // The catch is deliberately narrowed to AssertionError. A RuntimeException from the comparator
    // is a crash, not a verdict, and must propagate and fail the test loudly — catching Throwable
    // here would turn "the comparator blew up" into "the trees differ", which is precisely the
    // silent-failure mode this service exists to remove.
    // ------------------------------------------------------------------------------------------

    private static boolean comparatorSaysEqual(final JsonNode left, final JsonNode right) {
        try {
            JsonParity.assertMatches(left, right, "comparator contract vector");
            return true;
        } catch (final AssertionError difference) {
            LOG.debug("comparator reported a difference: {}", difference.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------
    // End of binding point.
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("vectors the contract decides")
    class DecidedVectors {

        @ParameterizedTest(name = "{0}")
        @MethodSource(
                "uk.gov.hmcts.cp.informantregister.support.ComparatorContractTest#decidedVectors")
        @DisplayName("compare_decidedVector_should_return_the_contracted_verdict")
        void compare_decidedVector_should_return_the_contracted_verdict(final Vector vector) {
            final boolean equal = comparatorSaysEqual(vector.left(), vector.right());

            assertThat(equal)
                    .as("%s [%s]%n  expected verdict : %s%n  why              : %s%n"
                                    + "  left             : %s%n  right            : %s",
                            vector.name(), vector.dimension(), vector.verdict(),
                            vector.rationale(), vector.left(), vector.right())
                    .isEqualTo(vector.expectsEqual());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource(
                "uk.gov.hmcts.cp.informantregister.support.ComparatorContractTest#decidedVectors")
        @DisplayName("compare_argumentsSwapped_should_return_the_same_verdict")
        void compare_argumentsSwapped_should_return_the_same_verdict(final Vector vector) {
            final boolean forwards = comparatorSaysEqual(vector.left(), vector.right());
            final boolean backwards = comparatorSaysEqual(vector.right(), vector.left());

            assertThat(backwards)
                    .as("%s: the verdict changed when the arguments were swapped (forwards=%s, "
                                    + "backwards=%s). A comparator that walks only the expected "
                                    + "side's keys sees a missing field but never an extra one, so "
                                    + "half of every NON_EXTENSIBLE violation goes unreported.",
                            vector.name(), forwards, backwards)
                    .isEqualTo(forwards);
        }
    }

    @Nested
    @DisplayName("vectors the contract does not decide")
    class UndecidedVectors {

        // allowZeroInvocations: every vector in the pack is decided today, so this
        // source is legitimately empty. The nest stays because the mechanism is the
        // point — a vector added later with an UNSPECIFIED verdict must be REPORTED,
        // never quietly asserted in whichever direction the comparator happens to
        // answer. Deleting it would remove the only place that can say so.
        @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
        @MethodSource(
                "uk.gov.hmcts.cp.informantregister.support.ComparatorContractTest#undecidedVectors")
        @DisplayName("compare_undecidedVector_should_be_skipped_until_the_decision_is_recorded")
        void compare_undecidedVector_should_be_skipped_until_the_decision_is_recorded(
                final Vector vector) {

            Assumptions.abort(String.format(
                    "UNDECIDED — %s [%s]%n"
                            + "  The comparator contract does not determine this case, so no "
                            + "verdict is asserted.%n"
                            + "  %s%n"
                            + "  left  : %s%n"
                            + "  right : %s%n"
                            + "  The comparator currently answers %s. That is an implementation "
                            + "default, not a ratified decision.%n"
                            + "  To decide it: set this vector's \"verdict\" in vectors.json to "
                            + "EQUAL or DIFFERENT and record the reasoning where the parity "
                            + "contract lives. This test then enforces it automatically.",
                    vector.name(), vector.dimension(), vector.rationale(),
                    vector.left(), vector.right(),
                    comparatorSaysEqual(vector.left(), vector.right()) ? "EQUAL" : "DIFFERENT"));
        }
    }

    @Nested
    @DisplayName("the vector pack itself")
    class VectorPack {

        @Test
        @DisplayName("load_vectorPack_should_be_well_formed_and_cover_both_verdicts")
        void load_vectorPack_should_be_well_formed_and_cover_both_verdicts() throws Exception {
            final List<Vector> vectors = load();

            assertThat(vectors).as("the vector pack is empty").isNotEmpty();
            assertThat(vectors).extracting(Vector::name)
                    .as("vector names must be unique — a duplicate silently masks a case")
                    .doesNotHaveDuplicates();
            assertThat(vectors).extracting(Vector::verdict)
                    .as("unknown verdict token")
                    .isSubsetOf("EQUAL", "DIFFERENT", "UNSPECIFIED");

            final Set<String> decided = new HashSet<>();
            vectors.stream().map(Vector::verdict).filter(v -> !"UNSPECIFIED".equals(v))
                    .forEach(decided::add);
            assertThat(decided)
                    .as("a pack that only asserts one verdict cannot catch a comparator stuck "
                            + "answering that verdict for everything")
                    .containsExactlyInAnyOrder("EQUAL", "DIFFERENT");
        }

        @Test
        @DisplayName("report_undecidedVectors_should_name_every_outstanding_decision")
        void report_undecidedVectors_should_name_every_outstanding_decision() throws Exception {
            final List<String> outstanding = load().stream()
                    .filter(vector -> "UNSPECIFIED".equals(vector.verdict()))
                    .map(vector -> vector.dimension() + " :: " + vector.name())
                    .sorted()
                    .toList();

            LOG.info("comparator contract: {} decision(s) outstanding {}",
                    outstanding.size(), outstanding);

            // Deliberately not asserted empty. Zero outstanding is the state today and
            // the log line records it; a vector added later with an UNSPECIFIED verdict
            // must show up here rather than fail this test, so that the decision gets
            // taken instead of the vector getting deleted to make the build green.
            assertThat(outstanding)
                    .as("the outstanding-decision report must always be computable")
                    .isNotNull();
        }
    }

    /**
     * One adversarial pair and the verdict the contract requires for it.
     *
     * @param name      the vector's identifier, used as the test name
     * @param left      the left-hand tree
     * @param right     the right-hand tree
     * @param verdict   {@code EQUAL}, {@code DIFFERENT} or {@code UNSPECIFIED}
     * @param dimension the comparison dimension the vector probes
     * @param rationale why the verdict is what it is, or which decision is outstanding
     */
    record Vector(
            String name,
            JsonNode left,
            JsonNode right,
            String verdict,
            String dimension,
            String rationale) {

        boolean expectsEqual() {
            return "EQUAL".equals(verdict);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> decidedVectors() throws Exception {
        return vectorsWhere(verdict -> !"UNSPECIFIED".equals(verdict));
    }

    static Stream<Arguments> undecidedVectors() throws Exception {
        return vectorsWhere("UNSPECIFIED"::equals);
    }

    private static Stream<Arguments> vectorsWhere(
            final Predicate<String> verdictFilter) throws Exception {

        return load().stream()
                .filter(vector -> verdictFilter.test(vector.verdict()))
                .map(vector -> Arguments.of(Named.of(vector.name(), vector)));
    }

    private static List<Vector> load() throws Exception {
        try (InputStream stream =
                     ComparatorContractTest.class.getResourceAsStream(VECTORS_RESOURCE)) {

            if (stream == null) {
                throw new IllegalStateException(
                        "comparator vector pack not on the test classpath at " + VECTORS_RESOURCE
                                + " — it belongs at src/test/resources" + VECTORS_RESOURCE);
            }

            final JsonNode root = MAPPER.readTree(stream);
            if (!root.isArray()) {
                throw new IllegalStateException(
                        VECTORS_RESOURCE + " must contain a JSON array of vectors, but was "
                                + root.getClass().getSimpleName());
            }

            final List<Vector> vectors = new ArrayList<>();
            for (final JsonNode node : root) {
                vectors.add(new Vector(
                        text(node, "name"),
                        required(node, "left"),
                        required(node, "right"),
                        text(node, "verdict"),
                        text(node, "dimension"),
                        text(node, "rationale")));
            }
            return vectors;
        }
    }

    private static String text(final JsonNode vector, final String field) {
        final JsonNode value = vector.get(field);
        if (value == null || !value.isString()) {
            throw new IllegalStateException(
                    "vector is missing a string '" + field + "': " + vector);
        }
        return value.stringValue();
    }

    private static JsonNode required(final JsonNode vector, final String field) {
        final JsonNode value = vector.get(field);
        if (value == null) {
            throw new IllegalStateException("vector is missing '" + field + "': " + vector);
        }
        return value;
    }
}
