package uk.gov.hmcts.cp.informantregister.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The characters {@code String.prototype.trim} removes, and the ones it leaves.
 *
 * <p>The set was enumerated from the Node runtime the function app runs on — every code point up to
 * {@code U+3000} whose one-character string trims to empty — rather than recalled from the
 * specification. Two Java predicates each get part of it wrong and neither gets all of it right,
 * which is why this class exists at all: {@link String#trim()} stops at {@code U+0020}, and
 * {@link String#strip()} follows {@link Character#isWhitespace}, which calls the four C0 information
 * separators whitespace and the non-breaking spaces not.
 */
@DisplayName("JsStrings — ECMAScript's trim, not Java's")
class JsStringsTest {

    @ParameterizedTest
    @ValueSource(chars = {
        0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x00A0, 0x1680,
        0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
        0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF})
    @DisplayName("strips every character ECMAScript calls trimmable")
    void strips_every_character_ecmascript_trims(final char trimmable) {
        assertThat(JsStrings.trim(trimmable + "value" + trimmable)).isEqualTo("value");
    }

    @ParameterizedTest
    @ValueSource(chars = {0x001C, 0x001D, 0x001E, 0x001F})
    @DisplayName("keeps the C0 information separators, which only Java calls whitespace")
    void keeps_the_c0_information_separators(final char kept) {
        assertThat(JsStrings.trim(kept + "value")).isEqualTo(kept + "value");
    }

    @Test
    @DisplayName("leaves the inside of a value alone")
    void leaves_the_inside_alone() {
        assertThat(JsStrings.trim("  one  two  ")).isEqualTo("one  two");
    }

    @Test
    @DisplayName("answers empty for a value that is nothing but whitespace")
    void answers_empty_for_whitespace_only() {
        assertThat(JsStrings.trim(" \t\n" + Character.toString(0x00A0))).isEmpty();
    }

    @Test
    @DisplayName("answers null for an absent value, as the legacy's truthiness guard does")
    void answers_null_for_an_absent_value() {
        assertThat(JsStrings.trim(null)).isNull();
    }
}
