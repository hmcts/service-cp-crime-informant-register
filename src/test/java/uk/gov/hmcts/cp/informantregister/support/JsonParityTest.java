package uk.gov.hmcts.cp.informantregister.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;

/**
 * The parity comparator's own tests.
 *
 * <p>This comparator is what decides whether the golden-parity suite passes, so a fault in it is
 * invisible in exactly the way that matters: a comparator that quietly matched everything would leave
 * nineteen green twins proving nothing. Both directions are therefore pinned — what it must accept,
 * and what it must reject.
 */
@DisplayName("JsonParity")
class JsonParityTest {

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    @Nested
    @DisplayName("accepts")
    class Accepts {

        @Test
        @DisplayName("objects whose fields are in a different order")
        void objects_whose_fields_are_in_a_different_order() {
            assertMatches("{\"a\":1,\"b\":2}", "{\"b\":2,\"a\":1}");
        }

        @Test
        @DisplayName("numbers that differ only in representation")
        void numbers_that_differ_only_in_representation() {
            // The service reads floating point as BigDecimal so money stays exact; the golden was
            // written by a runtime with one numeric type. These are the same number.
            assertMatches("{\"amount\":1}", "{\"amount\":1.0}");
            assertMatches("{\"amount\":1.50}", "{\"amount\":1.5}");
        }

        @Test
        @DisplayName("nested trees that are equal throughout")
        void nested_trees_that_are_equal_throughout() {
            assertMatches(
                    "[{\"x\":{\"y\":[1,2,{\"z\":null}]}}]",
                    "[{\"x\":{\"y\":[1,2,{\"z\":null}]}}]");
        }
    }

    @Nested
    @DisplayName("rejects")
    class Rejects {

        @Test
        @DisplayName("arrays whose elements are in a different order")
        void arrays_whose_elements_are_in_a_different_order() {
            // Order is meaning in this tree — which authority is first, which defendant is first.
            assertDiffers("[1,2]", "[2,1]", "/0");
        }

        @Test
        @DisplayName("a field the port emits that the legacy never did")
        void a_field_the_port_emits_that_the_legacy_never_did() {
            assertDiffers("{\"a\":1}", "{\"a\":1,\"b\":2}", "unexpected field");
        }

        @Test
        @DisplayName("a field the legacy emits that the port dropped")
        void a_field_the_legacy_emits_that_the_port_dropped() {
            assertDiffers("{\"a\":1,\"b\":2}", "{\"a\":1}", "missing field");
        }

        @Test
        @DisplayName("an array of the wrong length")
        void an_array_of_the_wrong_length() {
            assertDiffers("[1,2,3]", "[1,2]", "element(s)");
        }

        @Test
        @DisplayName("a value that changed type")
        void a_value_that_changed_type() {
            assertDiffers("{\"a\":\"1\"}", "{\"a\":1}", "/a");
        }

        @Test
        @DisplayName("null where a value was expected")
        void null_where_a_value_was_expected() {
            assertDiffers("{\"a\":1}", "{\"a\":null}", "/a");
        }

        @Test
        @DisplayName("a difference buried deep in the tree")
        void a_difference_buried_deep_in_the_tree() {
            assertDiffers(
                    "[{\"x\":{\"y\":[1,2,{\"z\":\"kept\"}]}}]",
                    "[{\"x\":{\"y\":[1,2,{\"z\":\"lost\"}]}}]",
                    "/0/x/y/2/z");
        }
    }

    /**
     * The one component the comparator does not compare by equality.
     *
     * <p>{@code doc/DEVIATIONS.md} entry 15 changed how {@code hearingStartTime} is rendered, and the
     * recorded golden files still carry the Node oracle's rendering — they are the oracle's truth and
     * are never edited. So the comparator <em>derives</em>: it re-reads the golden's own wall clock,
     * asks the {@code Europe/London} rules what the offset was on that date, and requires the port to
     * have written exactly that. It is a stricter check than equality on everything except the label,
     * and it is emphatically not an exemption — which is what the rejections below exist to prove.
     */
    @Nested
    @DisplayName("hearingStartTime — the registered field deviation (entry 15)")
    class RegisteredFieldDeviation {

        @Test
        @DisplayName("accepts the golden's own wall clock re-rendered with the summer offset")
        void accepts_the_derived_summer_rendering() {
            assertMatches(
                    "{\"hearingStartTime\":\"2021-09-29T11:00:00Z\"}",
                    "{\"hearingStartTime\":\"11:00:00+01:00\"}");
        }

        @Test
        @DisplayName("accepts the golden's own wall clock re-rendered with the winter zero offset")
        void accepts_the_derived_winter_rendering() {
            assertMatches(
                    "{\"hearingStartTime\":\"2021-03-11T00:00:00Z\"}",
                    "{\"hearingStartTime\":\"00:00:00Z\"}");
        }

        @Test
        @DisplayName("rejects the offset of the wrong season, which an exemption would have allowed")
        void rejects_the_offset_of_the_wrong_season() {
            // THE CASE THAT MATTERS. A comparator that simply skipped this field would pass here,
            // and a hard-coded "+01:00" in the renderer would ship every winter hearing an hour out.
            assertDiffers(
                    "{\"hearingStartTime\":\"2021-03-11T00:00:00Z\"}",
                    "{\"hearingStartTime\":\"00:00:00+01:00\"}",
                    "/hearingStartTime");
        }

        @Test
        @DisplayName("rejects a wall clock the golden does not carry")
        void rejects_a_wall_clock_the_golden_does_not_carry() {
            // The other half of the same point: the deviation moved the label, not the time. A port
            // that re-rendered the instant as 10:00:00Z would be "correct" by some readings and is
            // still a change to what an authority receives.
            assertDiffers(
                    "{\"hearingStartTime\":\"2021-09-29T11:00:00Z\"}",
                    "{\"hearingStartTime\":\"10:00:00Z\"}",
                    "/hearingStartTime");
        }

        @Test
        @DisplayName("rejects the legacy rendering itself, so a silent revert cannot pass")
        void rejects_the_legacy_rendering_itself() {
            assertDiffers(
                    "{\"hearingStartTime\":\"2021-09-29T11:00:00Z\"}",
                    "{\"hearingStartTime\":\"2021-09-29T11:00:00Z\"}",
                    "/hearingStartTime");
        }

        @Test
        @DisplayName("rejects the field going missing, exactly as equality would")
        void rejects_the_field_going_missing() {
            assertDiffers(
                    "{\"hearingStartTime\":\"2021-09-29T11:00:00Z\"}", "{}", "missing field");
        }

        @Test
        @DisplayName("leaves the golden's Invalid dateZ alone, because entry 15 does not touch it")
        void leaves_the_unreadable_rendering_alone() {
            // Deviation 13 territory. An unreadable sitting day still renders the literal moment
            // produces, appended Z and all, so here the derivation is the identity.
            assertMatches(
                    "{\"hearingStartTime\":\"Invalid dateZ\"}",
                    "{\"hearingStartTime\":\"Invalid dateZ\"}");
            assertDiffers(
                    "{\"hearingStartTime\":\"Invalid dateZ\"}",
                    "{\"hearingStartTime\":\"00:00:00Z\"}",
                    "/hearingStartTime");
        }

        @Test
        @DisplayName("names the deviation in the failure, so nobody has to guess why it differs")
        void names_the_deviation_in_the_failure() {
            assertDiffers(
                    "{\"hearingStartTime\":\"2021-03-11T00:00:00Z\"}",
                    "{\"hearingStartTime\":\"00:00:00+01:00\"}",
                    "DEVIATIONS.md entry 15");
        }

        @Test
        @DisplayName("reports a golden it cannot derive from rather than passing it")
        void reports_a_golden_it_cannot_derive_from() {
            // The golden files are never edited, so a value in a shape this deviation does not
            // describe means the deviation no longer covers the field — which must be reported, not
            // waved through on the grounds that the field is "registered".
            assertDiffers(
                    "{\"hearingStartTime\":\"11:00:00+01:00\"}",
                    "{\"hearingStartTime\":\"11:00:00+01:00\"}",
                    "cannot derive");
        }
    }

    /**
     * Asserts the comparator accepts two trees as equal.
     *
     * @param expected the golden tree as JSON text
     * @param actual   the ported tree as JSON text
     */
    private void assertMatches(final String expected, final String actual) {
        assertThatCode(() -> JsonParity.assertMatches(tree(expected), tree(actual), "case"))
                .doesNotThrowAnyException();
    }

    /**
     * Asserts the comparator rejects two trees, naming where.
     *
     * @param expected the golden tree as JSON text
     * @param actual   the ported tree as JSON text
     * @param mention  text the failure message must contain, so the report is usable
     */
    private void assertDiffers(
            final String expected, final String actual, final String mention) {
        assertThatThrownBy(() -> JsonParity.assertMatches(tree(expected), tree(actual), "case"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("case")
                .hasMessageContaining(mention);
    }

    /**
     * Parses JSON text.
     *
     * @param json the text
     * @return the tree
     */
    private JsonNode tree(final String json) {
        return mapper.readTree(json);
    }
}
