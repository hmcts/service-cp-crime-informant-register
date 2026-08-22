package uk.gov.hmcts.cp.informantregister.pipeline;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
 * <p><strong>Two parsing modes, because moment has two.</strong> {@code moment.tz(value, zone)}
 * resolves a value that carries an offset to that instant and then converts it to the zone, but
 * treats a value with no offset as already being in the zone. Both appear here: shared times arrive
 * as instants ({@code ...Z}), while ordered dates arrive as bare {@code YYYY-MM-DD} days that must be
 * read as London days. {@link #toLondon} reproduces that split rather than guessing one rule.
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
            return LocalDate.parse(value).atStartOfDay(LONDON);
        }
    }
}
