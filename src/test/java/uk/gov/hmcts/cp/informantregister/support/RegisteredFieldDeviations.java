package uk.gov.hmcts.cp.informantregister.support;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The components the golden comparator checks by derivation instead of by equality.
 *
 * <p><strong>Why this exists at all.</strong> Every file under {@code src/test/resources/parity/} is
 * a recording of the real Node function app and is the oracle's truth. It is never regenerated and
 * never edited — a golden file somebody adjusted to agree with the port has stopped being evidence.
 * So when a deviation is sanctioned and the port deliberately renders a component differently, the
 * recording still carries the legacy rendering and something has to reconcile the two.
 *
 * <p><strong>The mechanism, and what it is not.</strong> It is not an exclusion. Excluding the field
 * would make roughly five hundred golden assertions stop looking at it, and the next mistake in that
 * component — a hard-coded offset, a re-rendered instant, a dropped seconds field — would sail
 * through a green suite. Instead each registered component carries a <em>derivation</em>: given the
 * value the oracle recorded, it computes the value the port is now required to produce, and the
 * comparator demands exactly that. The check is as strict as equality was; only the expected string
 * moved.
 *
 * <p>Consequently the register can fail in a third way, and does so loudly: an oracle value the
 * derivation does not describe is reported as a difference rather than waved through, because a
 * field being "registered" is not a licence to stop comparing it.
 *
 * <p><strong>Registered here means registered there.</strong> Nothing belongs in this class that is
 * not a numbered entry of {@code doc/DEVIATIONS.md}, and every entry quotes its number into the
 * failure message so a reader of a red build is one grep from the reasoning and the sign-off.
 */
public final class RegisteredFieldDeviations {

    /** {@code Europe/London} — the zone whose real rules decide the offsets below. */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /** How the Node oracle renders a London time: a wall-clock date-time, then a literal {@code Z}. */
    private static final DateTimeFormatter LEGACY_RENDERING =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** RFC 3339 {@code full-time}, rendered exactly as {@code HearingDates} renders it. */
    private static final DateTimeFormatter FULL_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ssXXX");

    /** What {@code moment} renders for a value it cannot read, and what this port renders too. */
    private static final String INVALID_DATE_Z = "Invalid dateZ";

    /**
     * The register, by the property name the component reaches the wire under.
     *
     * <p>Matched by property name rather than by JSON pointer because the comparator is handed
     * fragments as well as whole documents, and the same component sits at a different depth in
     * each. {@code hearingStartTime} occurs at exactly one place in this contract, so the two
     * readings coincide.
     */
    private static final Map<String, Deviation> REGISTER = Map.of(
            "hearingStartTime",
            new Deviation(
                    "doc/DEVIATIONS.md entry 15 (project-owner decision, 2026-08-23)",
                    RegisteredFieldDeviations::hearingStartTime));

    private RegisteredFieldDeviations() {
    }

    /**
     * The deviation registered against a property name, if any.
     *
     * @param propertyName the property name as it reaches the wire
     * @return the deviation, or {@code null} when the property is compared by equality
     */
    public static Deviation forProperty(final String propertyName) {
        return REGISTER.get(propertyName);
    }

    /**
     * One registered component and the derivation that reconciles the port with the oracle.
     *
     * @param reference  the {@code doc/DEVIATIONS.md} entry, quoted into every failure message
     * @param derivation the renderings the port may produce, given the value the oracle recorded;
     *                   empty when the oracle's value is not one this deviation describes
     */
    public record Deviation(String reference, Function<String, List<String>> derivation) {

        /**
         * The renderings the port may produce for a value the oracle recorded.
         *
         * @param oracleValue the value in the golden file
         * @return the permitted renderings, or empty when the oracle's value is undescribed
         */
        public List<String> permittedFor(final String oracleValue) {
            return derivation.apply(oracleValue);
        }
    }

    /**
     * The {@code hearingStartTime} derivation — {@code doc/DEVIATIONS.md} entry 15.
     *
     * <p>The oracle records {@code DateService.getLocalDateTime}'s output: a London wall-clock
     * date-time with the character {@code Z} appended, whatever the real offset was. The port now
     * renders the same wall clock as an RFC 3339 {@code full-time} carrying London's true offset. So
     * the derivation reads the wall clock straight back out of the oracle's own string, asks the
     * {@code Europe/London} rules which offsets that wall clock had on that date, and renders it.
     *
     * <p><strong>It derives, it does not re-run the port.</strong> The wall clock comes from the
     * recording rather than from {@code HearingDates}, so a fault in this port's parsing cannot
     * cancel itself out — if the port read the sitting day differently from Node, the wall clocks
     * disagree and the comparator says so.
     *
     * <p><strong>The repeated autumn hour permits two answers, and honestly so.</strong> London
     * repeats 01:00–02:00 once a year, and the oracle's rendering — wall clock plus a meaningless
     * {@code Z} — genuinely does not record which of the two it was. Both offsets the zone allows
     * are therefore accepted, which still fixes the digits exactly and still rejects every other
     * offset. No case in the corpus falls in that hour; the allowance is here so that one arriving
     * later is reconciled rather than mysteriously red.
     *
     * <p><strong>An unreadable value is the identity.</strong> The decision moved a label on times
     * this port can read; {@code "Invalid dateZ"} is deviations-register entry 13's territory and is
     * unchanged, so the oracle's value is the only permitted one.
     *
     * @param oracleValue the value in the golden file
     * @return the permitted renderings, or empty when the oracle's value is undescribed
     */
    private static List<String> hearingStartTime(final String oracleValue) {
        if (oracleValue == null || !oracleValue.endsWith("Z")) {
            return List.of();
        }
        if (INVALID_DATE_Z.equals(oracleValue)) {
            return List.of(INVALID_DATE_Z);
        }
        final LocalDateTime wallClock;
        try {
            wallClock = LocalDateTime.parse(
                    oracleValue.substring(0, oracleValue.length() - 1), LEGACY_RENDERING);
        } catch (DateTimeParseException notTheLegacyRendering) {
            return List.of();
        }
        final List<ZoneOffset> offsets = LONDON.getRules().getValidOffsets(wallClock);
        return offsets.stream()
                .map(offset -> wallClock.toLocalTime().atOffset(offset).format(FULL_TIME))
                .toList();
    }
}
