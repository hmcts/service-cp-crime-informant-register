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
 */
public final class HearingDates {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static final DateTimeFormatter LOCAL_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final DateTimeFormatter LOCAL_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * The leading year, month and day of a date-ish string, whatever separates them.
     *
     * <p>This stands in for {@code moment(value, 'YYYY/MM/DD')} in the legacy
     * {@code DateService.parse}, which is a non-strict parse: moment reads the numeric tokens in the
     * order the format names them and does not require the separators to match. That is why an
     * ordered date of {@code 2020-01-20} parses against a {@code YYYY/MM/DD} format at all, and why a
     * full timestamp parses as just its date part. Only the leading date is captured, because only
     * the leading date is what moment uses.
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
     * @return the London day
     */
    public String localDate(final String value) {
        return toLondon(value).format(LOCAL_DATE);
    }

    /**
     * The London wall-clock time of the given value, with a literal {@code Z} appended.
     *
     * <p>Ports {@code DateService.getLocalDateTime}, including the misleading suffix — see the class
     * documentation.
     *
     * @param value an instant, a local date-time, or a bare day; may be {@code null}
     * @return the London wall-clock time, labelled {@code Z}
     */
    public String localDateTime(final String value) {
        return toLondon(value).format(LOCAL_DATE_TIME) + "Z";
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
     * <p>Ports {@code DateService.parse}, which reads only the leading calendar date and throws when
     * it cannot. The legacy throw matters: it propagates out of the comparator, out of the builder,
     * and is swallowed by the activity handler, so the hearing produces nothing at all. Returning a
     * fallback here would turn that visible-by-absence outcome into a silently different register.
     *
     * @param value the value to order by
     * @return the calendar date to order by
     * @throws TransformationFailedException if no leading calendar date can be read
     */
    public LocalDate orderingKey(final String value) {
        final Matcher matcher = value == null ? null : LEADING_DATE.matcher(value);
        if (matcher == null || !matcher.find()) {
            // The legacy message, kept verbatim, but classified: a date this cannot read reads the
            // same way on every redelivery, so the delivery is parked rather than retried.
            throw new TransformationFailedException("Invalid date format");
        }
        return LocalDate.of(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)));
    }

    /**
     * Resolves a value the way {@code moment.tz(value, 'Europe/London')} does.
     *
     * @param value the value to resolve; may be {@code null}
     * @return the value as a London date-time
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
     * @return the value as a London date-time
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
     * <p>Only the leading-date form is reproduced. {@code moment} accepts more than that through the
     * same fallback ({@code 2020-06}, {@code 2020/06/19 10:30}), and it answers an unreadable value
     * with the literal string {@code "Invalid date"} rather than by throwing. Neither is reachable
     * on this path: every value formatted here has already been through {@link #orderingKey}, which
     * requires a leading calendar date and refuses anything without one, exactly as the legacy
     * {@code DateService.parse} does before {@code getHearingDate} is ever called. So the rest is
     * refused rather than guessed — and refused as a classified transformation failure, because an
     * unclassified parse error would be read as transient and retried until the delivery budget ran
     * out on a payload no redelivery can change.
     *
     * @param value the value to resolve
     * @return the value as a London date-time
     * @throws TransformationFailedException if no calendar day can be read from it
     */
    private static ZonedDateTime dayWithoutOffset(final String value) {
        try {
            return LocalDate.parse(value).atStartOfDay(LONDON);
        } catch (DateTimeParseException notAnIsoDay) {
            final Matcher matcher = LEADING_DATE.matcher(value);
            if (!matcher.matches()) {
                // The legacy message, kept verbatim, and classified for the same reason
                // `orderingKey` classifies its own.
                throw new TransformationFailedException("Invalid date format");
            }
            return LocalDate.of(
                            Integer.parseInt(matcher.group(1)),
                            Integer.parseInt(matcher.group(2)),
                            Integer.parseInt(matcher.group(3)))
                    .atStartOfDay(ZoneOffset.UTC)
                    .withZoneSameInstant(LONDON);
        }
    }
}
