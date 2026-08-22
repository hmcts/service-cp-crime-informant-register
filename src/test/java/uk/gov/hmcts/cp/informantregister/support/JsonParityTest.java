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
