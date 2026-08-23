package uk.gov.hmcts.cp.informantregister.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.throwable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.domain.FailureClassification;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;

/**
 * JavaScript's truthiness rules, which the ported transformation branches on throughout.
 *
 * <p>The whole port is a set of {@code if (payload.field)} tests, so the rules below are not
 * incidental — each one decides which branch a real hearing takes. Two of them are counter-intuitive
 * to a Java reader and are the reason this class exists at all: an <strong>empty array is
 * truthy</strong>, and an <strong>empty string is falsy</strong>. The first is not hypothetical:
 * {@code hearing-results-from-prosecution-case.json} carries {@code "hearingDays": []}, and reading
 * that as "absent" produces a different hearing date on the fixture the legacy suite asserts.
 */
@DisplayName("Json — JavaScript truthiness over a canonical tree")
class JsonTest {

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    @Nested
    @DisplayName("truthy")
    class Truthy {

        @Test
        @DisplayName("treats an empty array as present, because JavaScript does")
        void treats_an_empty_array_as_present() {
            assertThat(truthy("{\"f\":[]}")).isTrue();
        }

        @Test
        @DisplayName("treats an empty object as present")
        void treats_an_empty_object_as_present() {
            assertThat(truthy("{\"f\":{}}")).isTrue();
        }

        @Test
        @DisplayName("treats a missing field as absent")
        void treats_a_missing_field_as_absent() {
            assertThat(truthy("{}")).isFalse();
        }

        @Test
        @DisplayName("treats an explicit null as absent")
        void treats_an_explicit_null_as_absent() {
            assertThat(truthy("{\"f\":null}")).isFalse();
        }

        @Test
        @DisplayName("reads a boolean as itself")
        void reads_a_boolean_as_itself() {
            assertThat(truthy("{\"f\":true}")).isTrue();
            assertThat(truthy("{\"f\":false}")).isFalse();
        }

        @Test
        @DisplayName("treats zero as falsy and any other number as truthy")
        void treats_zero_as_falsy() {
            assertThat(truthy("{\"f\":0}")).isFalse();
            assertThat(truthy("{\"f\":0.0}")).isFalse();
            assertThat(truthy("{\"f\":1}")).isTrue();
            assertThat(truthy("{\"f\":-1}")).isTrue();
            assertThat(truthy("{\"f\":0.5}")).isTrue();
        }

        @Test
        @DisplayName("treats an empty string as falsy and any other string as truthy")
        void treats_an_empty_string_as_falsy() {
            assertThat(truthy("{\"f\":\"\"}")).isFalse();
            assertThat(truthy("{\"f\":\"x\"}")).isTrue();
            assertThat(truthy("{\"f\":\"false\"}")).isTrue();
        }

        @Test
        @DisplayName("treats an absent parent as absent rather than failing")
        void treats_an_absent_parent_as_absent() {
            assertThat(Json.truthy(null, "f")).isFalse();
        }
    }

    @Nested
    @DisplayName("text")
    class Text {

        @Test
        @DisplayName("reads a string field")
        void reads_a_string_field() {
            assertThat(Json.text(tree("{\"f\":\"value\"}"), "f")).isEqualTo("value");
        }

        @Test
        @DisplayName("gives nothing for a missing field, so it can be dropped from the output")
        void gives_nothing_for_a_missing_field() {
            assertThat(Json.text(tree("{}"), "f")).isNull();
        }

        @Test
        @DisplayName("gives nothing for an explicit null")
        void gives_nothing_for_an_explicit_null() {
            assertThat(Json.text(tree("{\"f\":null}"), "f")).isNull();
        }

        @Test
        @DisplayName("gives nothing for an absent parent")
        void gives_nothing_for_an_absent_parent() {
            assertThat(Json.text(null, "f")).isNull();
        }
    }

    @Nested
    @DisplayName("array")
    class Array {

        @Test
        @DisplayName("gives the elements of an array field")
        void gives_the_elements_of_an_array_field() {
            assertThat(Json.array(tree("{\"f\":[1,2,3]}"), "f")).hasSize(3);
        }

        @Test
        @DisplayName("gives nothing to iterate for a missing field")
        void gives_nothing_to_iterate_for_a_missing_field() {
            assertThat(Json.array(tree("{}"), "f")).isEmpty();
        }

        @Test
        @DisplayName("gives nothing to iterate for an explicit null, as `|| []` does")
        void gives_nothing_to_iterate_for_an_explicit_null() {
            assertThat(Json.array(tree("{\"f\":null}"), "f")).isEmpty();
        }

        @Test
        @DisplayName("gives nothing to iterate for a falsy field, as `|| []` does")
        void gives_nothing_to_iterate_for_a_falsy_field() {
            assertThat(Json.array(tree("{\"f\":\"\"}"), "f")).isEmpty();
        }

        @Test
        @DisplayName("refuses an object where the legacy would iterate")
        void refuses_an_object_where_the_legacy_would_iterate() {
            // `({}).forEach` is not a function: the legacy throws a TypeError here, and the whole
            // hearing produces nothing. Answering "no elements" instead would turn a payload the
            // transformation cannot read into a legitimate empty business result.
            assertThatThrownBy(() -> Json.array(tree("{\"f\":{}}"), "f"))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("refuses a non-empty string where the legacy would iterate")
        void refuses_a_string_where_the_legacy_would_iterate() {
            assertThatThrownBy(() -> Json.array(tree("{\"f\":\"not an array\"}"), "f"))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("names the field it refused and never quotes what was in it")
        void names_the_field_it_refused() {
            assertThatThrownBy(() -> Json.array(tree("{\"f\":\"a defendant name\"}"), "f"))
                    .hasMessageContaining("f")
                    .hasMessageNotContaining("a defendant name");
        }

        @Test
        @DisplayName("classifies a refusal as non-transient, so no redelivery is spent on it")
        void classifies_a_refusal_as_non_transient() {
            assertThatThrownBy(() -> Json.array(tree("{\"f\":{}}"), "f"))
                    .asInstanceOf(throwable(TransformationFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.NON_TRANSIENT);
                        assertThat(failure.reason()).isEqualTo(ReasonCode.TRANSFORMATION_FAILED);
                    });
        }

        @Test
        @DisplayName("gives nothing to iterate for an absent parent")
        void gives_nothing_to_iterate_for_an_absent_parent() {
            assertThat(Json.array(null, "f")).isEmpty();
        }
    }

    @Nested
    @DisplayName("dereferencedArray")
    class DereferencedArray {

        @Test
        @DisplayName("gives the elements of an array field")
        void gives_the_elements_of_an_array_field() {
            assertThat(Json.dereferencedArray(tree("{\"f\":[1,2,3]}"), "f")).hasSize(3);
        }

        @Test
        @DisplayName("gives no elements for an empty array, which is a legal thing to iterate")
        void gives_no_elements_for_an_empty_array() {
            assertThat(Json.dereferencedArray(tree("{\"f\":[]}"), "f")).isEmpty();
        }

        @Test
        @DisplayName("refuses a missing field, because `undefined.forEach` throws")
        void refuses_a_missing_field() {
            assertThatThrownBy(() -> Json.dereferencedArray(tree("{}"), "f"))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("refuses an explicit null, because `null.forEach` throws")
        void refuses_an_explicit_null() {
            assertThatThrownBy(() -> Json.dereferencedArray(tree("{\"f\":null}"), "f"))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("refuses a value that is not an array")
        void refuses_a_value_that_is_not_an_array() {
            assertThatThrownBy(() -> Json.dereferencedArray(tree("{\"f\":{}}"), "f"))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("refuses an absent parent, because the dereference itself throws")
        void refuses_an_absent_parent() {
            assertThatThrownBy(() -> Json.dereferencedArray(null, "f"))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("names the field it refused and never quotes what was in it")
        void names_the_field_it_refused() {
            assertThatThrownBy(() ->
                    Json.dereferencedArray(tree("{\"f\":\"a defendant name\"}"), "f"))
                    .hasMessageContaining("f")
                    .hasMessageNotContaining("a defendant name");
        }

        @Test
        @DisplayName("classifies a refusal as non-transient, so no redelivery is spent on it")
        void classifies_a_refusal_as_non_transient() {
            assertThatThrownBy(() -> Json.dereferencedArray(tree("{}"), "f"))
                    .asInstanceOf(throwable(TransformationFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.NON_TRANSIENT);
                        assertThat(failure.reason()).isEqualTo(ReasonCode.TRANSFORMATION_FAILED);
                    });
        }
    }

    @Nested
    @DisplayName("dereferenced")
    class Dereferenced {

        @Test
        @DisplayName("refuses an absent field, because `undefined.anything` throws")
        void refuses_an_absent_field() {
            assertThatThrownBy(() -> Json.dereferenced(tree("{}"), "f"))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("refuses an explicit null field, which throws the same way")
        void refuses_an_explicit_null_field() {
            assertThatThrownBy(() -> Json.dereferenced(tree("{\"f\":null}"), "f"))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("returns every value a property read answers `undefined` for")
        void returns_values_a_property_read_answers_undefined_for() {
            // `(0).x`, `"".x`, `[].x` and `({}).x` are all `undefined` — falsy, never a TypeError —
            // so none of these is a refusal, and each behaves exactly as the legacy behaves.
            assertThat(Json.dereferenced(tree("{\"f\":0}"), "f")).isEqualTo(tree("0"));
            assertThat(Json.dereferenced(tree("{\"f\":\"\"}"), "f")).isEqualTo(tree("\"\""));
            assertThat(Json.dereferenced(tree("{\"f\":false}"), "f")).isEqualTo(tree("false"));
            assertThat(Json.dereferenced(tree("{\"f\":[]}"), "f")).isEqualTo(tree("[]"));
        }

        @Test
        @DisplayName("names the field it refused and never quotes what was in it")
        void names_the_field_it_refused() {
            assertThatThrownBy(() -> Json.dereferenced(tree("{}"), "courtCentre"))
                    .hasMessageContaining("courtCentre");
        }
    }

    @Nested
    @DisplayName("dereferencedElement")
    class DereferencedElement {

        @Test
        @DisplayName("refuses an explicit null element, because `null.anything` throws")
        void refuses_an_explicit_null_element() {
            assertThatThrownBy(() -> Json.dereferencedElement(tree("null"), "f"))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("refuses a Java null element, which is the absent one the legacy pushed")
        void refuses_a_java_null_element() {
            // `judicialResults.push(result.judicialResult)` pushes `undefined` when the result has
            // none, and `undefined.judicialResultPrompts` throws exactly as `null` does.
            assertThatThrownBy(() -> Json.dereferencedElement(null, "f"))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("passes through every value a property read answers `undefined` for")
        void passes_through_values_a_property_read_answers_undefined_for() {
            // `(0).x`, `"".x`, `[].x` and `({}).x` are all `undefined` — falsy, never a TypeError —
            // so none of these is a refusal, and each behaves exactly as the legacy behaves.
            assertThat(Json.dereferencedElement(tree("0"), "f")).isEqualTo(tree("0"));
            assertThat(Json.dereferencedElement(tree("\"\""), "f")).isEqualTo(tree("\"\""));
            assertThat(Json.dereferencedElement(tree("false"), "f")).isEqualTo(tree("false"));
            assertThat(Json.dereferencedElement(tree("[]"), "f")).isEqualTo(tree("[]"));
            assertThat(Json.dereferencedElement(tree("{}"), "f")).isEqualTo(tree("{}"));
        }

        @Test
        @DisplayName("names the collection it refused and never quotes what was in it")
        void names_the_collection_it_refused() {
            assertThatThrownBy(() -> Json.dereferencedElement(tree("null"), "judicialResults"))
                    .hasMessageContaining("judicialResults");
        }

        @Test
        @DisplayName("classifies a refusal as non-transient, so no redelivery is spent on it")
        void classifies_a_refusal_as_non_transient() {
            assertThatThrownBy(() -> Json.dereferencedElement(null, "f"))
                    .asInstanceOf(throwable(TransformationFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.NON_TRANSIENT);
                        assertThat(failure.reason()).isEqualTo(ReasonCode.TRANSFORMATION_FAILED);
                    });
        }
    }

    @Nested
    @DisplayName("nonEmptyArray")
    class NonEmptyArray {

        @Test
        @DisplayName("is true for an array with elements in it")
        void is_true_for_an_array_with_elements() {
            assertThat(Json.nonEmptyArray(tree("{\"f\":[1]}"), "f")).isTrue();
        }

        @Test
        @DisplayName("is false for an empty array, because `[].length > 0` is false")
        void is_false_for_an_empty_array() {
            assertThat(Json.nonEmptyArray(tree("{\"f\":[]}"), "f")).isFalse();
        }

        @Test
        @DisplayName("is false for a missing field and for an explicit null")
        void is_false_for_a_missing_field_and_an_explicit_null() {
            assertThat(Json.nonEmptyArray(tree("{}"), "f")).isFalse();
            assertThat(Json.nonEmptyArray(tree("{\"f\":null}"), "f")).isFalse();
        }

        @Test
        @DisplayName("is false — never a refusal — for a truthy value that is not an array")
        void is_false_for_a_truthy_value_that_is_not_an_array() {
            // `({}).length` is `undefined`, and `undefined > 0` is false. The legacy therefore
            // skips this branch quietly and carries on with the rest of the hearing; refusing here
            // would lose a register the legacy produces.
            assertThat(Json.nonEmptyArray(tree("{\"f\":{}}"), "f")).isFalse();
            assertThat(Json.nonEmptyArray(tree("{\"f\":\"xy\"}"), "f")).isFalse();
        }

        @Test
        @DisplayName("is false for an absent parent")
        void is_false_for_an_absent_parent() {
            assertThat(Json.nonEmptyArray(null, "f")).isFalse();
        }
    }

    /**
     * Whether field {@code f} of the given object is truthy.
     *
     * @param json the object as JSON text
     * @return whether the field is truthy
     */
    private boolean truthy(final String json) {
        return Json.truthy(tree(json), "f");
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
