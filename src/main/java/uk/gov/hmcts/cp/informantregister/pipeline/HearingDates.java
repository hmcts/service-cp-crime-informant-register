package uk.gov.hmcts.cp.informantregister.pipeline;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;

/**
 * The date handling of the legacy {@code NowsHelper/service/DateService.js}, ported as written.
 *
 * <p>This class exists because the legacy date behaviour is <em>wrong in a specific, observable
 * way</em> that the register output depends on, and reproducing it is the requirement (constitution
 * Principle I). Reaching for {@code Instant} and a correct offset would produce different strings on
 * every hearing resulted between late March and late October.
 *
 * <p><strong>The literal {@code Z}.</strong> {@link #localDateTime} formats in
 * {@code Europe/London} and then appends the character {@code Z}, exactly as
 * {@code DateService.getLocalDateTime} does. In British Summer Time that labels a local time as if it
 * were UTC: a shared time of {@code 2020-06-01T10:00:00Z} becomes {@code 2020-06-01T11:00:00Z}, an
 * hour that never happened at that instant. The legacy Jest suite asserts precisely that value, and
 * so do the parity goldens. It is not corrected here; a correction is a change to what prosecuting
 * authorities ingest and belongs on the deviations register with business sign-off, not in a port.
 *
 * <p><strong>One component has that sign-off.</strong> {@link #localFullTime} renders the London
 * wall clock with London's true offset instead, and it is used by exactly one call site — the
 * {@code hearingStartTime} of the outbound document ({@code CourtSessionMapper}). That is
 * {@code doc/DEVIATIONS.md} entry 15, a project-owner decision of 2026-08-23, and its scope is that
 * component alone. {@code registerDate}, {@code hearingDate} and every duration date still go out
 * through {@link #localDateTime} and {@link #formattedLocalDateTime} in the legacy's shape.
 *
 * <p><strong>Three parsing modes, because moment has three.</strong> {@code moment.tz(value, zone)}
 * resolves a value that carries an offset to that instant and then converts it to the zone, but
 * treats a value with no offset as already being in the zone. Both appear here: shared times arrive
 * as instants ({@code ...Z}), while ordered dates arrive as bare {@code YYYY-MM-DD} days that must be
 * read as London days. A value moment recognises as neither takes a third route — {@code new Date()},
 * whose result is read as UTC and <em>then</em> converted, landing an hour later in British Summer
 * Time. {@link #toLondon} reproduces that split rather than guessing one rule.
 *
 * <p><strong>Absent input means "now".</strong> {@code moment.tz(undefined, zone)} is the current
 * time, so a hearing shared without a shared time is stamped with the wall clock. Several legacy Jest
 * cases pass no shared time and so depend on it. The clock is injected rather than read from the
 * system so the transformation stays pure and testable with golden files alone (constitution
 * Principle V).
 *
 * <p><strong>An unreadable value is rendered, not refused.</strong> {@code moment} does not throw
 * when it cannot read a value: the moment is flagged invalid and {@code format} answers the literal
 * string {@code "Invalid date"}, whatever pattern it was given. So
 * {@code getLocalDateTime('not a date')} is {@code "Invalid dateZ"} and the hearing carries on.
 * Verified against the {@code moment-timezone} vendored with the function app. {@link #localDate}
 * and {@link #localDateTime} reproduce that, because their call sites — a sitting day
 * ({@code CourtSessionMapper.js:26-28}) and a next hearing's start ({@code ResultDataMapper.js:15})
 * — read the payload directly and see whatever the producer sent. {@link #orderingKey} is the one
 * exception and stays a refusal: it ports {@code DateService.parse}, which really does
 * {@code throw new Error('Invalid date format')}.
 */
public final class HearingDates {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static final DateTimeFormatter LOCAL_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final DateTimeFormatter LOCAL_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * RFC 3339 {@code full-time}: a time of day with its offset, seconds always written.
     *
     * <p>{@code XXX} is java.time's own offset rendering — the one {@code OffsetTime#toString()} and
     * {@code DateTimeFormatter#ISO_OFFSET_TIME} produce — which writes a zero offset as the single
     * character {@code Z}. The seconds are spelt out because {@code ISO_OFFSET_TIME} drops them when
     * they are zero and RFC 3339 does not allow that. See {@link #localFullTime}.
     */
    private static final DateTimeFormatter FULL_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ssXXX");

    /**
     * The leading year, month and day of a date-ish string, whatever separates them.
     *
     * <p>The last resort of {@link #dayWithoutOffset}: a day {@code moment.tz} does not recognise as
     * ISO and hands to {@code new Date(...)}. It is deliberately <em>narrower</em> than the token
     * walk {@link #orderingKey} uses, because it stands in for a different parser — V8's, not
     * moment's format walk — and V8 requires a four-digit year here. The forms V8 resolves and this
     * does not are recorded as deviations-register entry 13 rather than guessed at.
     */
    private static final Pattern LEADING_DATE =
            Pattern.compile("^(\\d{4})\\D(\\d{1,2})\\D(\\d{1,2})");

    /**
     * The day, month and year of a {@code DD/MM/YYYY} value, whatever separates them.
     *
     * <p>The counterpart of {@link #LEADING_DATE} for {@code DateService.formatDate}'s hard-coded
     * format. Same non-strict rule, different token order — which is the whole of defect D11.
     */
    private static final Pattern DAY_MONTH_YEAR =
            Pattern.compile("^\\D*(\\d{1,2})\\D*(\\d{1,2})\\D*(\\d{1,4})");

    /** What moment renders instead of throwing when it cannot read a value. */
    private static final String INVALID_DATE = "Invalid date";

    /**
     * moment's two-digit-year pivot: above this into the twentieth century, at or below into the
     * twenty-first ({@code parseTwoDigitYear}).
     */
    private static final int TWO_DIGIT_YEAR_PIVOT = 68;

    private final Clock clock;

    /**
     * Creates the date service.
     *
     * @param clock the clock an absent input resolves against
     */
    public HearingDates(final Clock clock) {
        this.clock = clock;
    }

    /**
     * The London calendar day of the given value, as {@code yyyy-MM-dd}.
     *
     * <p>Ports {@code DateService.getLocalDate}.
     *
     * @param value an instant, a local date-time, or a bare day; may be {@code null}
     * @return the London day, or the literal {@code "Invalid date"}
     */
    public String localDate(final String value) {
        final ZonedDateTime resolved = toLondon(value);
        return resolved == null ? INVALID_DATE : resolved.format(LOCAL_DATE);
    }

    /**
     * The London wall-clock time of the given value, with a literal {@code Z} appended.
     *
     * <p>Ports {@code DateService.getLocalDateTime}, including the misleading suffix — see the class
     * documentation.
     *
     * @param value an instant, a local date-time, or a bare day; may be {@code null}
     * @return the London wall-clock time labelled {@code Z}, or the literal {@code "Invalid dateZ"}
     */
    public String localDateTime(final String value) {
        final ZonedDateTime resolved = toLondon(value);
        return (resolved == null ? INVALID_DATE : resolved.format(LOCAL_DATE_TIME)) + "Z";
    }

    /**
     * The London wall-clock time of day of the given value, carrying London's true offset.
     *
     * <p><strong>This is the one sanctioned departure from the legacy rendering</strong>, and it is
     * confined to the {@code hearingStartTime} component of the outbound document —
     * {@code doc/DEVIATIONS.md} entry 15, decided by the project owner on 2026-08-23. Everything
     * else this class renders, {@code registerDate} and {@code hearingDate} included, still goes out
     * in D9's shape through {@link #localDateTime}.
     *
     * <p><strong>The parse is untouched.</strong> This method resolves its value through exactly the
     * same {@link #toLondon} that {@link #localDateTime} uses, so every quirk of how the legacy reads
     * a sitting day — the ISO day read as London, the slash-separated day read as UTC, the absent
     * value that means "now" — is preserved. Only the last step differs.
     *
     * <p><strong>What differs, and why.</strong> The legacy formats the resolved London time as
     * {@code YYYY-MM-DDTHH:mm:ss} and appends the character {@code Z}, which half the year labels a
     * British Summer Time reading as if it were UTC and which is, in either season, a date-time in a
     * component the contract types as a {@code time}
     * ({@code informantRegisterHearing.json}: {@code "hearingStartTime": {"type": "string",
     * "format": "time"}}). This renders the same wall clock — the digits are unchanged, which is the
     * "visually correct" half — as an RFC 3339 {@code full-time} whose offset is read from the
     * {@code Europe/London} rules on that date, which is the "semantically correct" half and the
     * first rendering of this component the declared format accepts.
     *
     * <p><strong>The offset is java.time's own.</strong> {@link #FULL_TIME}'s {@code XXX} is the
     * offset pattern {@code OffsetTime#toString()} and {@code DateTimeFormatter#ISO_OFFSET_TIME}
     * use, so a zero offset comes out as the single character {@code Z} rather than as
     * {@code +00:00} — a January sitting renders {@code 09:30:00Z} and a June one
     * {@code 09:30:00+01:00}. Both are RFC 3339 full-times; this is the one java.time emits, and it
     * is not hand-rolled. The seconds are the one thing forced: {@code ISO_OFFSET_TIME} elides a
     * zero seconds field ({@code 09:30Z}) and RFC 3339 {@code full-time} requires it.
     *
     * <p><strong>An unreadable value is unchanged.</strong> It still renders the literal
     * {@code "Invalid dateZ"} that {@code moment} produces, appended {@code Z} and all. The decision
     * was about how a time this port <em>can</em> read is labelled; a value it cannot read is
     * deviations-register entry 13's territory and is not quietly changed on the way past.
     *
     * @param value an instant, a local date-time, or a bare day; may be {@code null}
     * @return the London time of day with its true offset, or the literal {@code "Invalid dateZ"}
     */
    public String localFullTime(final String value) {
        final ZonedDateTime resolved = toLondon(value);
        if (resolved == null) {
            return INVALID_DATE + "Z";
        }
        return resolved.toOffsetDateTime().toOffsetTime().format(FULL_TIME);
    }

    /**
     * The London wall-clock time of a value first re-read as a {@code DD/MM/YYYY} day.
     *
     * <p>Ports {@code DateService.formatDateAndGetLocalDateTime}, which is two steps and one defect.
     * The first step is {@code DateService.formatDate}, whose {@code sourceFormat} and
     * {@code targetFormat} parameters exist only to make the call sites look configurable — the body
     * ignores both and hard-codes {@code moment(value, 'DD/MM/YYYY').format('YYYY-MM-DD')}. The
     * second step formats the result the same way {@link #localDateTime} does.
     *
     * <p><strong>A date in any other order is not read, and not refused either.</strong> moment
     * answers an unparseable value with the literal string {@code "Invalid date"} rather than by
     * throwing, and step two appends {@code Z} to it, so the value {@code "Invalid dateZ"} is what
     * reaches the outbound body — inside a field the frozen contract types as a date-time. Verified
     * against the {@code moment} vendored with the function app: {@code 26/02/2019} reads as 26
     * February, while the ISO {@code 2021-07-26} does not read at all. This is defect D11 and the
     * parity pack pins it; a {@code java.time} port that reads both correctly is a behaviour change
     * to values prosecuting authorities ingest, and needs a deviations-register entry first.
     *
     * <p>The parse itself is moment's non-strict one: it takes the numeric tokens in the order the
     * format names them — one or two digits of day, one or two of month, up to four of year — and
     * ignores whatever separates them, which is why a value with a time on the end still reads. Only
     * that leading run is reproduced; a value moment would resolve through some other route is not
     * reachable from this call site, whose inputs are duration dates.
     *
     * @param value the duration date to read; may be {@code null}
     * @return the London wall-clock time, labelled {@code Z}, or {@code "Invalid dateZ"}
     */
    public String formattedLocalDateTime(final String value) {
        final LocalDate day = asDayMonthYear(value);
        return day == null ? INVALID_DATE + "Z" : localDateTime(day.format(LOCAL_DATE));
    }

    /**
     * Reads a value as moment's non-strict {@code DD/MM/YYYY} does.
     *
     * @param value the value to read; may be {@code null}
     * @return the day, or {@code null} when moment would call the value invalid
     */
    private static LocalDate asDayMonthYear(final String value) {
        final Matcher matcher = value == null ? null : DAY_MONTH_YEAR.matcher(value);
        if (matcher == null || !matcher.find()) {
            return null;
        }
        try {
            return LocalDate.of(
                    Integer.parseInt(matcher.group(3)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(1)));
        } catch (DateTimeException notACalendarDay) {
            return null;
        }
    }

    /**
     * The sort key the legacy code orders dates by.
     *
     * <p>Ports {@code DateService.parse}, which is {@code moment(value, 'YYYY/MM/DD')} with no
     * strict flag, followed by a throw when the result is invalid. The legacy throw matters: it
     * propagates out of {@code RegisterFragmentService}'s comparator into a catch block that throws
     * again (defect D10), and out of {@code DefendantContextBaseService} with no catch at all
     * (parity pin {@code s06}) — either way the activity handler swallows it and the hearing
     * produces nothing. Returning a fallback here would turn that visible-by-absence outcome into a
     * silently different register.
     *
     * <p><strong>The format is not the shape it looks like.</strong> Non-strict moment does not
     * match the format as a pattern; it walks the format's tokens, gives each one a maximum width,
     * and skips whatever separates them. {@code YYYY} takes up to <em>four</em> digits, {@code MM}
     * up to two and {@code DD} up to two, so {@code 20-01-2020} — the ordered date both
     * {@code OutboundInformantRegister} fixtures carry — reads as the year 20, the month 1 and the
     * day 20, and the two-digit-year rule then makes the year 2020. Reading the format as written
     * would refuse that value, and refusing it loses a register the legacy files.
     * {@link #reachedByMoment} reproduces the walk; every expectation is taken from the
     * {@code moment} vendored with the function app.
     *
     * @param value the value to order by
     * @return the calendar date to order by
     * @throws TransformationFailedException if moment would call the value invalid
     */
    public LocalDate orderingKey(final String value) {
        final LocalDate parsed = value == null ? null : reachedByMoment(value);
        if (parsed == null) {
            // The legacy message, kept verbatim, but classified: a date this cannot read reads the
            // same way on every redelivery, so the delivery is parked rather than retried.
            throw new TransformationFailedException("Invalid date format");
        }
        return parsed;
    }

    /**
     * Reads a value as non-strict {@code moment(value, 'YYYY/MM/DD')} reads it.
     *
     * <p>Three tokens, each taking the next run of digits up to its own width, with everything
     * between them skipped. A month or a day the value runs out before is defaulted to 1, exactly as
     * moment defaults them — {@code 2020} is 1 January 2020. A year token that consumed exactly two
     * digits goes through moment's {@code parseTwoDigitYear}: above 68 into the twentieth century,
     * 68 and below into the twenty-first. A month or day of zero, or a combination that is not a
     * calendar day, is invalid rather than adjusted.
     *
     * @param value the value to read
     * @return the day moment would produce, or {@code null} where moment would be invalid
     */
    private static LocalDate reachedByMoment(final String value) {
        final Digits digits = new Digits(value);
        final String year = digits.take(4);
        if (year == null) {
            return null;
        }
        final String month = digits.take(2);
        final String day = digits.take(2);
        try {
            return LocalDate.of(
                    inACentury(year),
                    month == null ? 1 : Integer.parseInt(month),
                    day == null ? 1 : Integer.parseInt(day));
        } catch (DateTimeException notACalendarDay) {
            // moment reads the tokens and then rejects the combination — `2020-13-45`, `2020-02-30`
            // and `2020-01-0` are all `isValid() === false` — so this is the same refusal.
            return null;
        }
    }

    /**
     * A year token as moment resolves it.
     *
     * @param token the digits the year token consumed
     * @return the year
     */
    private static int inACentury(final String token) {
        final int year = Integer.parseInt(token);
        if (token.length() != 2) {
            return year;
        }
        return year > TWO_DIGIT_YEAR_PIVOT ? year + 1900 : year + 2000;
    }

    /** Walks a value's digit runs the way moment's non-strict tokeniser walks them. */
    private static final class Digits {

        private final String value;
        private int position;

        /* default */ Digits(final String value) {
            this.value = value;
        }

        /**
         * The next run of digits, at most {@code width} of them, skipping anything before it.
         *
         * @param width the token's maximum width
         * @return the digits, or {@code null} when the value has none left
         */
        /* default */ String take(final int width) {
            while (position < value.length() && !Character.isDigit(value.charAt(position))) {
                position++;
            }
            final int start = position;
            while (position < value.length()
                    && position - start < width
                    && Character.isDigit(value.charAt(position))) {
                position++;
            }
            return start == position ? null : value.substring(start, position);
        }
    }

    /**
     * Resolves a value the way {@code moment.tz(value, 'Europe/London')} does.
     *
     * @param value the value to resolve; may be {@code null}
     * @return the value as a London date-time, or {@code null} when moment would call it invalid
     */
    private ZonedDateTime toLondon(final String value) {
        if (value == null) {
            return ZonedDateTime.now(clock).withZoneSameInstant(LONDON);
        }
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(LONDON);
        } catch (DateTimeParseException carriesNoOffset) {
            return withoutOffset(value);
        }
    }

    /**
     * Resolves a value that carries no offset, and is therefore already London-local.
     *
     * @param value the value to resolve
     * @return the value as a London date-time, or {@code null} when moment would call it invalid
     */
    private ZonedDateTime withoutOffset(final String value) {
        try {
            return LocalDateTime.parse(value).atZone(LONDON);
        } catch (DateTimeParseException notADateTime) {
            return dayWithoutOffset(value);
        }
    }

    /**
     * Resolves a bare day, ISO-separated or not.
     *
     * <p>The two halves land an hour apart in summer and that is the legacy's answer, not a rounding
     * choice made here. {@code moment.tz} matches {@code 2020-06-19} against its ISO pattern and
     * reads it as a London day, so it is midnight London. It has no pattern for {@code 2020/06/19},
     * falls through to {@code new Date(...)}, and the result is read as a UTC day and then converted
     * — so the same date arrives as 01:00 in British Summer Time. Verified against the
     * {@code moment-timezone} vendored with the function app, and the answer does not depend on the
     * host's time zone.
     *
     * <p>An unreadable value answers {@code null}, and the two format methods turn that into the
     * literal {@code "Invalid date"} moment renders — see the class documentation. Only the
     * leading-date form is reproduced, and that narrowness is itself a divergence:
     * {@code moment.tz} resolves more through the same {@code new Date(...)} fallback
     * ({@code 2020-06}, {@code 2020/06/19 10:30}) than is read back here, so those forms render as
     * invalid where the legacy renders a date. Reproducing V8's date parser is not something a port
     * can do faithfully by guessing at it, so the gap is recorded as deviations-register entry 13
     * rather than approximated.
     *
     * @param value the value to resolve
     * @return the value as a London date-time, or {@code null} when moment would call it invalid
     */
    private static ZonedDateTime dayWithoutOffset(final String value) {
        try {
            return LocalDate.parse(value).atStartOfDay(LONDON);
        } catch (DateTimeParseException notAnIsoDay) {
            final Matcher matcher = LEADING_DATE.matcher(value);
            if (!matcher.matches()) {
                return null;
            }
            try {
                return LocalDate.of(
                                Integer.parseInt(matcher.group(1)),
                                Integer.parseInt(matcher.group(2)),
                                Integer.parseInt(matcher.group(3)))
                        .atStartOfDay(ZoneOffset.UTC)
                        .withZoneSameInstant(LONDON);
            } catch (DateTimeException notACalendarDay) {
                // `2020-13-45` reads as three numbers and is still not a day; moment answers the
                // same way it answers `not a date`, so this does too.
                return null;
            }
        }
    }
}
