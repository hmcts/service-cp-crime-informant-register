package uk.gov.hmcts.cp.informantregister.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;

/**
 * The legacy date behaviour, pinned including the parts of it that are wrong.
 *
 * <p>The register's timestamps are the field prosecuting authorities key their ingest on, and the
 * legacy produces them by formatting a London wall-clock time and appending the character {@code Z}.
 * Half the year that labels a time as UTC that is not UTC. These cases exist so that nobody "fixes"
 * it without noticing they have changed what every downstream authority receives — a change that
 * needs business sign-off and a deviations-register entry, not a tidier-looking method.
 */
@DisplayName("HearingDates — the legacy DateService behaviour")
class HearingDatesTest {

    private static final Clock FROZEN =
            Clock.fixed(Instant.parse("2021-06-15T09:30:00Z"), ZoneOffset.UTC);

    private final HearingDates dates = new HearingDates(FROZEN);

    @Nested
    @DisplayName("localDateTime")
    class LocalDateTime {

        @Test
        @DisplayName("shifts a summer instant into British Summer Time and still labels it Z")
        void shifts_a_summer_instant_and_still_labels_it_zulu() {
            // The legacy Jest suite asserts exactly this: 10:00 UTC becomes 11:00, labelled Z.
            assertThat(dates.localDateTime("2020-06-01T10:00:00Z"))
                    .isEqualTo("2020-06-01T11:00:00Z");
        }

        @Test
        @DisplayName("leaves a winter instant alone, when London is already UTC")
        void leaves_a_winter_instant_alone() {
            assertThat(dates.localDateTime("2021-03-11T22:18:24.506Z"))
                    .isEqualTo("2021-03-11T22:18:24Z");
        }

        @Test
        @DisplayName("reads a bare day as a London day, not as UTC midnight")
        void reads_a_bare_day_as_a_london_day() {
            assertThat(dates.localDateTime("2020-01-20")).isEqualTo("2020-01-20T00:00:00Z");
        }

        @Test
        @DisplayName("reads an offset-less date-time as already being London-local")
        void reads_an_offsetless_date_time_as_london_local() {
            assertThat(dates.localDateTime("2021-06-15T09:30:00"))
                    .isEqualTo("2021-06-15T09:30:00Z");
        }

        @Test
        @DisplayName("reads a slash-separated day as a UTC day, as moment's Date fallback does")
        void reads_a_slash_separated_day_as_a_utc_day() {
            // `moment.tz` has no ISO match for "2020/06/19", so it falls through to `new Date(...)`
            // and the result is read as UTC before being converted to London — an hour later than
            // the same day written with hyphens. Verified against the vendored moment-timezone in
            // `cpp-context-azure-legalaidagency`, and independent of the host time zone.
            assertThat(dates.localDateTime("2020/06/19")).isEqualTo("2020-06-19T01:00:00Z");
        }

        @Test
        @DisplayName("shifts a slash-separated winter day by nothing, because London is UTC then")
        void reads_a_slash_separated_winter_day_unshifted() {
            assertThat(dates.localDateTime("2020/01/19")).isEqualTo("2020-01-19T00:00:00Z");
        }

        @Test
        @DisplayName("renders a value it cannot read as the literal Invalid dateZ, as moment does")
        void renders_a_value_it_cannot_read() {
            // moment does not throw on an unreadable value: the moment is flagged invalid and
            // `format` answers the literal string "Invalid date", to which getLocalDateTime appends
            // its Z. Verified against the moment-timezone vendored with the function app. Refusing
            // here instead would park a hearing the legacy renders — and both call sites of this
            // method, a sitting day and a next hearing's start, read the payload directly.
            assertThat(dates.localDateTime("not a date")).isEqualTo("Invalid dateZ");
        }

        @Test
        @DisplayName("renders three numbers that are not a calendar day as Invalid dateZ too")
        void renders_an_impossible_calendar_day_as_invalid() {
            // moment.tz('2020-13-45', zone).isValid() is false, so it renders the same way.
            assertThat(dates.localDateTime("2020-13-45")).isEqualTo("Invalid dateZ");
        }

        @Test
        @DisplayName("renders an empty sitting day as Invalid dateZ rather than as the clock")
        void renders_an_empty_value_as_invalid() {
            // Only an absent value is "now"; an empty string is an unreadable one.
            assertThat(dates.localDateTime("")).isEqualTo("Invalid dateZ");
        }

        @Test
        @DisplayName("falls back to the clock when there is no value at all")
        void falls_back_to_the_clock_when_absent() {
            // moment.tz(undefined, zone) is "now" — which is why a hearing shared without a shared
            // time is stamped with the wall clock rather than rejected.
            assertThat(dates.localDateTime(null)).isEqualTo("2021-06-15T10:30:00Z");
        }
    }

    @Nested
    @DisplayName("localDate")
    class Localised {

        @Test
        @DisplayName("gives the London day of an instant near midnight in summer")
        void gives_the_london_day_of_a_late_evening_instant() {
            // 23:30 UTC on 14 June is already 15 June in London.
            assertThat(dates.localDate("2021-06-14T23:30:00Z")).isEqualTo("2021-06-15");
        }

        @Test
        @DisplayName("gives the day of a bare day unchanged")
        void gives_the_day_of_a_bare_day_unchanged() {
            assertThat(dates.localDate("2020-01-20")).isEqualTo("2020-01-20");
        }

        @Test
        @DisplayName("gives the day of a slash-separated day unchanged")
        void gives_the_day_of_a_slash_separated_day_unchanged() {
            assertThat(dates.localDate("2020/06/19")).isEqualTo("2020-06-19");
        }

        @Test
        @DisplayName("gives the literal Invalid date for a value it cannot read")
        void gives_the_literal_invalid_date() {
            // The same moment behaviour as localDateTime, without the appended Z — and it reaches
            // the wire, because the file name is built out of this: the legacy files a register
            // whose register date it could not read as `InformantRegister_TFL_Invalid date.csv`.
            assertThat(dates.localDate("not a date")).isEqualTo("Invalid date");
        }
    }

    @Nested
    @DisplayName("orderingKey")
    class Ordering {

        @Test
        @DisplayName("reads a bare ordered date")
        void reads_a_bare_ordered_date() {
            assertThat(dates.orderingKey("2020-01-20")).isEqualTo(LocalDate.of(2020, 1, 20));
        }

        @Test
        @DisplayName("reads only the leading date of a full timestamp, as the legacy format does")
        void reads_only_the_leading_date_of_a_timestamp() {
            assertThat(dates.orderingKey("2021-03-11T22:18:24.506Z"))
                    .isEqualTo(LocalDate.of(2021, 3, 11));
        }

        @Test
        @DisplayName("accepts slash separators, which the legacy format nominally asks for")
        void accepts_slash_separators() {
            assertThat(dates.orderingKey("2021/03/11")).isEqualTo(LocalDate.of(2021, 3, 11));
        }

        @Test
        @DisplayName("refuses a value with no leading date rather than inventing an order")
        void refuses_a_value_with_no_leading_date() {
            assertThatThrownBy(() -> dates.orderingKey("not a date"))
                    .isInstanceOf(TransformationFailedException.class)
                    .hasMessage("Invalid date format");
        }

        @Test
        @DisplayName("refuses an absent value rather than ordering it as now")
        void refuses_an_absent_value() {
            assertThatThrownBy(() -> dates.orderingKey(null))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("refuses three numbers that are not a calendar day, as moment's parse does")
        void refuses_an_impossible_calendar_day() {
            // moment('2020-13-45', 'YYYY/MM/DD').isValid() is false, so DateService.parse throws.
            // The tokens read; the combination does not — and the refusal must still be the
            // classified one, not a raw DateTimeException the pipeline would retry.
            assertThatThrownBy(() -> dates.orderingKey("2020-13-45"))
                    .isInstanceOf(TransformationFailedException.class)
                    .hasMessage("Invalid date format");
        }

        /**
         * Every token width moment's non-strict parse actually uses, and it uses three different
         * ones.
         *
         * <p>The parse is {@code moment(value, 'YYYY/MM/DD')} with no strict flag, so moment walks
         * the format's tokens and gives each a width: up to four digits for the year, up to two for
         * the month and up to two for the day, skipping whatever separates them. Reading it as "four
         * digits, a separator, one or two, a separator, one or two" — the shape the format looks
         * like — refuses values the legacy reads perfectly well, and the two
         * {@code OutboundInformantRegister} fixtures in the parity corpus are exactly that: their
         * ordered date is {@code 20-01-2020}, which moment reads as 20 January 2020 and a
         * four-digit-year reader refuses. Refusing there loses a register the legacy produces, which
         * is the one direction this port must not drift in.
         *
         * <p>Each expectation below was taken from the {@code moment} vendored with the function
         * app, not from a reading of what the format ought to mean.
         */
        @Test
        @DisplayName("reads a two-digit year the way moment reads one, mapping it into a century")
        void reads_a_two_digit_year() {
            // moment('20-01-2020', 'YYYY/MM/DD') is 2020-01-20: the year token takes "20", the
            // two-digit-year rule makes it 2020, the month takes "01", and the day token takes only
            // the first two digits of "2020".
            assertThat(dates.orderingKey("20-01-2020")).isEqualTo(LocalDate.of(2020, 1, 20));
            // '25-03-2019' is 2025-03-20 — the year is 2025 and the day is 20, which is nobody's
            // idea of what that string means and is what the legacy orders by.
            assertThat(dates.orderingKey("25-03-2019")).isEqualTo(LocalDate.of(2025, 3, 20));
            // The century boundary moment uses: 68 goes forward, 69 goes back.
            assertThat(dates.orderingKey("68-01-02")).isEqualTo(LocalDate.of(2068, 1, 2));
            assertThat(dates.orderingKey("69-01-02")).isEqualTo(LocalDate.of(1969, 1, 2));
            // Four digits are a year as written, even when they are small.
            assertThat(dates.orderingKey("0020-01-20")).isEqualTo(LocalDate.of(20, 1, 20));
        }

        @Test
        @DisplayName("defaults a month or a day the value does not carry, as moment defaults them")
        void defaults_a_missing_month_or_day() {
            assertThat(dates.orderingKey("2020")).isEqualTo(LocalDate.of(2020, 1, 1));
            assertThat(dates.orderingKey("20-1")).isEqualTo(LocalDate.of(2020, 1, 1));
            assertThat(dates.orderingKey("2020-06")).isEqualTo(LocalDate.of(2020, 6, 1));
        }

        @Test
        @DisplayName("skips whatever separates the numbers, including none at all")
        void skips_whatever_separates_the_numbers() {
            assertThat(dates.orderingKey("20200120")).isEqualTo(LocalDate.of(2020, 1, 20));
            assertThat(dates.orderingKey("2020--01--20")).isEqualTo(LocalDate.of(2020, 1, 20));
            assertThat(dates.orderingKey("  2020-01-20")).isEqualTo(LocalDate.of(2020, 1, 20));
            assertThat(dates.orderingKey("abc2020-01-20")).isEqualTo(LocalDate.of(2020, 1, 20));
            assertThat(dates.orderingKey("a1b2c3")).isEqualTo(LocalDate.of(1, 2, 3));
        }

        @Test
        @DisplayName("refuses a zero month or day, which moment calls invalid rather than defaults")
        void refuses_a_zero_month_or_day() {
            assertThatThrownBy(() -> dates.orderingKey("2020-0-5"))
                    .isInstanceOf(TransformationFailedException.class);
            assertThatThrownBy(() -> dates.orderingKey("2020-01-0"))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("refuses a value with no digits in it at all")
        void refuses_a_value_with_no_digits() {
            assertThatThrownBy(() -> dates.orderingKey(""))
                    .isInstanceOf(TransformationFailedException.class);
            assertThatThrownBy(() -> dates.orderingKey("not/a/date"))
                    .isInstanceOf(TransformationFailedException.class);
        }
    }

    /**
     * The JUnit twins of the legacy {@code DateService} Jest suite.
     *
     * <p>That suite declares eight cases across five functions; the informant register reaches three
     * of those functions, and only those three are ported. The twins below carry the Jest names and
     * the Jest values verbatim:
     *
     * <ul>
     *   <li>{@code parse} → {@link HearingDates#orderingKey}</li>
     *   <li>{@code getLocalDate} → {@link HearingDates#localDate}</li>
     *   <li>{@code getLocalDateTime} → {@link HearingDates#localDateTime}, twice</li>
     * </ul>
     *
     * <p>The remaining four cases cover {@code isGreater} (two cases), {@code getLocalTime} and
     * {@code formatDateAndGetLocalDateTime}. Nothing on the informant-register path calls any of
     * them — {@code SetInformantRegister} and {@code RegisterFragmentService} use {@code parse},
     * {@code getLocalDate} and {@code getLocalDateTime} and nothing else — so they are deliberately
     * not ported. Twinning them would mean writing production code no hearing can reach in order to
     * have something to assert against, which buys a green tick and a maintenance liability. The
     * omission is recorded here rather than left to be noticed.
     *
     * <p>Two of the Jest cases assert against {@code moment-timezone} rather than against a literal,
     * so the literal is computed here the way the Jest expression computes it:
     * {@code moment.tz('2020-06-19T09:00:00.000Z', 'Europe/London')} is 10:00 on a June morning,
     * British Summer Time.
     */
    @Nested
    @DisplayName("DateService — legacy Jest twins")
    class LegacyJestTwins {

        /** The instant three of the four twinned Jest cases are written around. */
        private static final String JUNE_MORNING = "2020-06-19T09:00:00.000Z";

        @Test
        @DisplayName("it should return the correct date")
        void it_should_return_the_correct_date() {
            // The Jest case builds `${2020}-${10}-${29}` and asserts the parsed year, month and day.
            assertThat(dates.orderingKey("2020-10-29")).isEqualTo(LocalDate.of(2020, 10, 29));
        }

        @Test
        @DisplayName("should return local date")
        void should_return_local_date() {
            assertThat(dates.localDate(JUNE_MORNING)).isEqualTo("2020-06-19");
        }

        @Test
        @DisplayName("should return local date time")
        void should_return_local_date_time() {
            assertThat(dates.localDateTime(JUNE_MORNING)).isEqualTo("2020-06-19T10:00:00Z");
        }

        @Test
        @DisplayName("should return local date time when time missing")
        void should_return_local_date_time_when_time_missing() {
            assertThat(dates.localDateTime("2020-06-19")).isEqualTo("2020-06-19T00:00:00Z");
        }
    }

    /**
     * The hard-coded {@code DD/MM/YYYY} re-read that duration dates go through, and defect D11 with
     * it. Every expectation below was taken from the {@code moment} vendored with the function app,
     * by calling {@code DateService.formatDateAndGetLocalDateTime} on the same input — none of them
     * is a reading of what moment "ought" to do.
     *
     * <p>The pair that matters is the third and fourth: the same calendar day written the producer's
     * way reads correctly, and written the ISO way does not read at all. A port that accepted both
     * would be a change to values prosecuting authorities ingest.
     */
    @Nested
    @DisplayName("formattedLocalDateTime — the DD/MM/YYYY re-read (D11)")
    class FormattedLocalDateTime {

        @Test
        @DisplayName("reads a day-first date, which is the form the producer sends")
        void reads_a_day_first_date() {
            assertThat(dates.formattedLocalDateTime("26/02/2019"))
                    .isEqualTo("2019-02-26T00:00:00Z");
        }

        @Test
        @DisplayName("reads single-digit day and month, as the non-strict parse allows")
        void reads_single_digit_day_and_month() {
            assertThat(dates.formattedLocalDateTime("1/2/2019")).isEqualTo("2019-02-01T00:00:00Z");
        }

        @Test
        @DisplayName("ignores anything after the leading date, as the non-strict parse does")
        void ignores_anything_after_the_leading_date() {
            assertThat(dates.formattedLocalDateTime("26/02/2019T10:00:00Z"))
                    .isEqualTo("2019-02-26T00:00:00Z");
        }

        @Test
        @DisplayName("D11 — an ISO date is not read, and ships as the literal 'Invalid dateZ'")
        void an_iso_date_ships_as_the_literal_invalid_date() {
            assertThat(dates.formattedLocalDateTime("2021-07-26")).isEqualTo("Invalid dateZ");
        }

        @Test
        @DisplayName("D11 — a full ISO timestamp is not read either")
        void a_full_iso_timestamp_ships_as_the_literal_invalid_date() {
            assertThat(dates.formattedLocalDateTime("2020-06-19T09:08:03.001Z"))
                    .isEqualTo("Invalid dateZ");
        }

        @Test
        @DisplayName("D11 — an empty value is answered, not refused")
        void an_empty_value_ships_as_the_literal_invalid_date() {
            assertThat(dates.formattedLocalDateTime("")).isEqualTo("Invalid dateZ");
        }

        @Test
        @DisplayName("a date that is not a calendar day is invalid, as moment's validation says")
        void a_non_calendar_day_ships_as_the_literal_invalid_date() {
            assertThat(dates.formattedLocalDateTime("31/02/2019")).isEqualTo("Invalid dateZ");
        }
    }
}
