package uk.gov.hmcts.cp.informantregister.pipeline;

/**
 * The one string operation the port needs to perform the way JavaScript performs it.
 *
 * <p>{@code String.prototype.trim} and {@link String#trim()} do not strip the same characters, and
 * the difference reaches the wire. ECMAScript trims {@code WhiteSpace} plus {@code LineTerminator}
 * plus the byte-order mark, which includes the non-breaking space {@code U+00A0} and the other
 * Unicode spaces; Java's {@code trim} strips only characters at or below {@code U+0020}, and
 * {@code strip} follows {@link Character#isWhitespace}, which excludes exactly the non-breaking
 * ones. So an email address pasted into reference data behind a {@code U+00A0} is trimmed by
 * {@code RecipientMapper.js:41} and would not be trimmed here — a different address on a real
 * register.
 *
 * <p>The set below was enumerated from the Node runtime the function app runs on rather than
 * recalled. It is {@link Character#isWhitespace} ∪ {@link Character#isSpaceChar} ∪ {@code U+FEFF},
 * less the four C0 information separators {@code U+001C}–{@code U+001F}, which Java calls
 * whitespace and ECMAScript does not.
 */
// PMD.OnlyOneReturn: the early returns mirror the legacy source's own, line for line —
// funnelling them through a single exit would reshape the very control flow the parity
// harness pins (constitution Principle I, bug-for-bug parity).
@SuppressWarnings("PMD.OnlyOneReturn")
final class JsStrings {

    /** The byte-order mark, which ECMAScript trims and no Java predicate calls whitespace. */
    private static final char BYTE_ORDER_MARK = 0xFEFF;

    /** The first C0 information separator: Java calls it whitespace and ECMAScript does not. */
    private static final char FIRST_SEPARATOR = 0x001C;

    /** The last of the four. */
    private static final char LAST_SEPARATOR = 0x001F;

    private JsStrings() {
    }

    /**
     * A string with its surrounding whitespace removed, as {@code String.prototype.trim} removes it.
     *
     * @param value the string to trim; may be {@code null}
     * @return the trimmed string, or {@code null} when there was none
     */
    /* default */ static String trim(final String value) {
        if (value == null) {
            return null;
        }
        int start = 0;
        int end = value.length();
        while (start < end && isTrimmable(value.charAt(start))) {
            start++;
        }
        while (end > start && isTrimmable(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * Whether ECMAScript's {@code trim} would remove this character.
     *
     * @param character the character to test
     * @return whether it is trimmed
     */
    private static boolean isTrimmable(final char character) {
        if (character >= FIRST_SEPARATOR && character <= LAST_SEPARATOR) {
            return false;
        }
        return character == BYTE_ORDER_MARK
                || Character.isWhitespace(character)
                || Character.isSpaceChar(character);
    }
}
