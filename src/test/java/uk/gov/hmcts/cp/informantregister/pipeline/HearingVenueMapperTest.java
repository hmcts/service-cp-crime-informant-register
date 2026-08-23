package uk.gov.hmcts.cp.informantregister.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterHearing;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterHearingVenue;
import uk.gov.hmcts.cp.informantregister.domain.RegisterFragment;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;

/**
 * The JUnit twins of the legacy {@code HearingVenueMapper} and {@code CourtSessionMapper} Jest
 * suites — six cases between them, kept together because the venue is one line of mapping and a
 * single-element list holding the session.
 *
 * <p>The Jest {@code CourtSessionMapper} suite mocks the defendant mapper away and the
 * {@code HearingVenueMapper} suite mocks the session mapper away, so neither asserts anything below
 * its own line. The twins do not mock: the fragment they run against simply has no defendants, which
 * is the same input reaching the same collaborator for real.
 *
 * <p>The fourth session twin is the one that matters most. It carries the pinned rendering of
 * {@code hearingStartTime}, which is defect D9 — London wall-clock time labelled {@code Z} — and it
 * asserts the exact string for the reason the parity pack gives: comparing instants would pass for
 * the corrected value too and would pin nothing.
 */
@DisplayName("HearingVenueMapper and CourtSessionMapper — parity with the legacy mappers")
class HearingVenueMapperTest {

    private static final String AUTHORITY = "31af405e-7b60-4dd8-a244-c24c2d3fa595";

    /** No case here reads the clock; the fixed value only makes that visible. */
    private static final Clock FROZEN =
            Clock.fixed(Instant.parse("2021-06-15T09:30:00Z"), ZoneOffset.UTC);

    private final HearingDates dates = new HearingDates(FROZEN);

    private final ResultDataMapper resultDataMapper = new ResultDataMapper(dates);

    @Nested
    @DisplayName("HearingVenue mapper works correctly")
    class VenueTwins {

        @Test
        @DisplayName("when hearing and informant register then mapper should give court center name")
        void the_venue_takes_its_name_from_the_court_centre() {
            final ObjectNode hearing = sittingHearing();
            ((ObjectNode) hearing.get("courtCentre")).put("name", "CourtCenter");

            assertThat(venue(hearing).courtHouse()).isEqualTo("CourtCenter");
        }

        @Test
        @DisplayName("when lja name then mapper should set ljaName")
        void the_venue_takes_its_local_justice_area_from_the_court_centre() {
            final ObjectNode hearing = sittingHearing();
            final ObjectNode courtCentre = (ObjectNode) hearing.get("courtCentre");
            courtCentre.set("lja", courtCentre.objectNode().put("ljaName", "LJA-Name"));

            assertThat(venue(hearing).ljaName()).isEqualTo("LJA-Name");
        }
    }

    @Nested
    @DisplayName("CourtSession mapper works correctly")
    class SessionTwins {

        @Test
        @DisplayName("when hearing and informant register then mapper should give court room")
        void a_hearing_that_is_neither_box_work_nor_sjp_reports_its_room() {
            final ObjectNode hearing = sittingHearing();
            ((ObjectNode) hearing.get("courtCentre")).put("roomName", "Room-1");

            assertThat(session(hearing).courtRoom()).isEqualTo("Room-1");
        }

        @Test
        @DisplayName("when hearing is box work then mapper should set court room to N/A")
        void box_work_reports_no_room() {
            final ObjectNode hearing = sittingHearing();
            hearing.put("isBoxHearing", true);

            assertThat(session(hearing).courtRoom()).isEqualTo("N/A");
        }

        @Test
        @DisplayName("when hearing is SJP then mapper should set court room to N/A")
        void an_sjp_hearing_reports_no_room() {
            final ObjectNode hearing = sittingHearing();
            hearing.put("isSJPHearing", true);

            assertThat(session(hearing).courtRoom()).isEqualTo("N/A");
        }

        @Test
        @DisplayName("when hearing and informant register then mapper should give hearing start "
                + "time of earliest sitting day")
        void the_session_starts_when_the_first_listed_sitting_day_starts() {
            final ObjectNode hearing = ModelObjects.hearing();
            hearing.set("hearingDays", ModelObjects.array(
                    ModelObjects.hearingDay("2020-06-19T09:08:03.001Z", "13:00:00"),
                    ModelObjects.hearingDay("2020-06-21T09:00:00.000Z", "14:00:00")));

            // The Jest case computes its expectation the same way the code does, so the value it
            // asserts is the one below: London wall-clock time, an hour on from the instant in
            // British Summer Time, labelled Z regardless.
            assertThat(session(hearing).hearingStartTime()).isEqualTo("2020-06-19T10:08:03Z");
        }
    }

    @Nested
    @DisplayName("Pinned oddities — a correction here needs a deviations-register entry first")
    class PinnedOddities {

        /**
         * Parity-pack pinning case {@code d09-bst-local-time-labelled-as-utc}, at this mapper's call
         * site. Asserted as an exact string: an honest {@code java.time} port would render either
         * {@code 2020-06-19T10:08:03+01:00} or {@code 2020-06-19T09:08:03Z}, and both must fail here
         * until the correction has a deviations entry.
         */
        @Test
        @DisplayName("d09 — a summer sitting day is labelled Z an hour after the instant it names")
        void a_summer_sitting_day_is_london_local_labelled_z() {
            final ObjectNode hearing = ModelObjects.hearing();
            hearing.set("hearingDays", ModelObjects.array(
                    ModelObjects.hearingDay("2020-06-19T09:08:03.001Z", "13:00:00")));

            assertThat(session(hearing).hearingStartTime())
                    .isEqualTo("2020-06-19T10:08:03Z")
                    .isNotEqualTo("2020-06-19T09:08:03Z");
        }

        /**
         * The control the pinning case names: in January London is UTC, so the same rendering leaves
         * the value alone. The pair is what proves the extra hour is a British Summer Time artefact
         * rather than a constant offset somebody could "correct" by subtracting one.
         */
        @Test
        @DisplayName("d09 control — a winter sitting day is unchanged, because London is UTC then")
        void a_winter_sitting_day_is_unchanged() {
            final ObjectNode hearing = ModelObjects.hearing();
            hearing.set("hearingDays", ModelObjects.array(
                    ModelObjects.hearingDay("2020-01-20T10:00:00.000Z", "10:00:00")));

            assertThat(session(hearing).hearingStartTime()).isEqualTo("2020-01-20T10:00:00Z");
        }

        /**
         * The start time is the <em>first</em> sitting day in payload order, not the earliest. The
         * legacy Jest case is named "earliest sitting day" and its fixture happens to list them in
         * order, so nothing there distinguishes the two readings; a hearing whose days arrive out of
         * order does. Sorting them would be a correction, not a port.
         */
        @Test
        @DisplayName("the first sitting day wins, not the earliest, despite the legacy test name")
        void the_first_sitting_day_wins_even_when_a_later_one_is_earlier() {
            final ObjectNode hearing = ModelObjects.hearing();
            hearing.set("hearingDays", ModelObjects.array(
                    ModelObjects.hearingDay("2020-06-21T09:00:00.000Z", "14:00:00"),
                    ModelObjects.hearingDay("2020-06-19T09:08:03.001Z", "13:00:00")));

            assertThat(session(hearing).hearingStartTime()).isEqualTo("2020-06-21T10:00:00Z");
        }
    }

    @Nested
    @DisplayName("Branches the legacy suite never executes (parity-pack BS-14)")
    class UncoveredBranches {

        /**
         * BS-14, the covered-by-nothing half: a hearing whose first sitting day has no
         * {@code sittingDay} yields no start time at all. The session is still produced — the
         * contract requires the component, and the legacy sends it absent anyway.
         */
        @Test
        @DisplayName("BS-14 — a first hearing day with no sitting day yields no start time")
        void a_hearing_day_without_a_sitting_day_yields_no_start_time() {
            final ObjectNode hearing = ModelObjects.hearing();
            hearing.set("hearingDays",
                    ModelObjects.array(ModelObjects.hearing().objectNode().put("startTime", "13:00")));

            assertThat(session(hearing).hearingStartTime()).isNull();
        }

        /**
         * BS-14: an empty hearing-day list is legal — {@code length > 0} is false and the start time
         * is simply absent. It is the <em>missing</em> field that kills the legacy, not the empty
         * one, and the two are a line apart in the source.
         */
        @Test
        @DisplayName("BS-14 — an empty hearing-day list yields no start time and no refusal")
        void an_empty_hearing_day_list_yields_no_start_time() {
            final ObjectNode hearing = ModelObjects.hearing();
            hearing.set("hearingDays", ModelObjects.array());

            assertThat(session(hearing).hearingStartTime()).isNull();
        }

        /**
         * BS-14: {@code this.hearingJson.hearingDays.length} is dereferenced with no guard
         * (`CourtSessionMapper.js:26`), so a hearing with no {@code hearingDays} field kills the
         * whole flow in the legacy. Refused here, under deviations-register entry 7.
         */
        @Test
        @DisplayName("BS-14 — a hearing with no hearing days at all is refused")
        void a_hearing_without_hearing_days_should_refuse() {
            assertThatThrownBy(() -> session(ModelObjects.hearing()))
                    .isInstanceOf(TransformationFailedException.class);
        }

        /**
         * {@code this.hearingJson.courtCentre.name} and {@code .roomName} are both read with no
         * guard on {@code courtCentre} (`HearingVenueMapper.js:13`, `CourtSessionMapper.js:15`).
         */
        @Test
        @DisplayName("a hearing with no court centre is refused")
        void a_hearing_without_a_court_centre_should_refuse() {
            final ObjectNode hearing = ModelObjects.hearing();
            hearing.remove("courtCentre");
            hearing.set("hearingDays", ModelObjects.array());

            assertThatThrownBy(() -> venue(hearing))
                    .isInstanceOf(TransformationFailedException.class);
        }
    }

    /**
     * A hearing with one sitting day, so the session mapper has something to read.
     *
     * @return the hearing tree
     */
    private static ObjectNode sittingHearing() {
        final ObjectNode hearing = ModelObjects.hearing();
        hearing.set("hearingDays", ModelObjects.array(
                ModelObjects.hearingDay("2020-06-19T09:00:00.000Z", "13:00:00")));
        return hearing;
    }

    /**
     * Maps the venue of a hearing, for a fragment with no defendants.
     *
     * @param hearing the hearing tree
     * @return the mapped venue
     */
    private InformantRegisterHearingVenue venue(final ObjectNode hearing) {
        final RegisterFragment fragment = ModelObjects.fragment(AUTHORITY);
        return new HearingVenueMapper(hearing, fragment, dates, resultDataMapper).build();
    }

    /**
     * Maps the single session of a hearing.
     *
     * @param hearing the hearing tree
     * @return the mapped session
     */
    private InformantRegisterHearing session(final ObjectNode hearing) {
        final RegisterFragment fragment = ModelObjects.fragment(AUTHORITY);
        return new CourtSessionMapper(hearing, fragment, dates, resultDataMapper).build();
    }
}
