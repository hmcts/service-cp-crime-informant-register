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
    }
}
