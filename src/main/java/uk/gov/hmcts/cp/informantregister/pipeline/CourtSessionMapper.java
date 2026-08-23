package uk.gov.hmcts.cp.informantregister.pipeline;

import java.util.List;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterHearing;
import uk.gov.hmcts.cp.informantregister.domain.RegisterFragment;

/**
 * The one court session a register carries, and the defendants heard in it.
 *
 * <p>A port of {@code OutboundInformantRegister/InformantRegisterAggregationRequest/Mapper/
 * CourtSessionMapper.js}. There is exactly one session per document — the venue mapper wraps this in
 * a single-element list — however many days the hearing actually sat.
 *
 * <p><strong>The room is the court centre's, unless the hearing had no room.</strong> Box work and
 * SJP hearings report the literal {@code "N/A"} rather than omitting the component, because the
 * contract requires it.
 *
 * <p><strong>The start time is the first sitting day's, not the earliest.</strong> The legacy Jest
 * case is named for the earliest and its fixture happens to list the days in order, so the two
 * readings are indistinguishable there; {@code hearingDays[0]} is what the code does and it is what
 * is ported. Sorting would be a correction.
 *
 * <p><strong>That time is the one sanctioned deviation in this mapper.</strong> The legacy renders it
 * with {@code DateService.getLocalDateTime}, so a summer sitting day becomes a London wall-clock
 * <em>date-time</em> labelled {@code Z} — an hour on from the instant it names, and a date-time in a
 * component {@code informantRegisterHearing.json} declares to be a {@code time}. That is defect D9.
 * Here it is rendered by {@link HearingDates#localFullTime} instead: the same wall clock, carrying
 * London's true offset on the day, which is the RFC 3339 {@code full-time} the schema's
 * {@code "format": "time"} asks for. {@code doc/DEVIATIONS.md} entry 15, decided by the project
 * owner on 2026-08-23.
 *
 * <p>The scope is this component and no other. The parse is unchanged, an unreadable sitting day
 * still renders the literal {@code "Invalid dateZ"}, and D9's rendering survives untouched on the
 * document's own {@code registerDate} and {@code hearingDate}.
 */
final class CourtSessionMapper {

    /** What the legacy reports when the hearing had no room. */
    private static final String NO_ROOM = "N/A";

    private final JsonNode hearing;
    private final RegisterFragment fragment;
    private final HearingDates dates;
    private final ResultDataMapper resultDataMapper;

    /**
     * Creates the mapper.
     *
     * @param hearing          the canonical hearing tree
     * @param fragment         the authority's fragment
     * @param dates            the date service the start time is rendered by
     * @param resultDataMapper the mapper for the detail hanging off each result
     */
    /* default */ CourtSessionMapper(
            final JsonNode hearing,
            final RegisterFragment fragment,
            final HearingDates dates,
            final ResultDataMapper resultDataMapper) {
        this.hearing = hearing;
        this.fragment = fragment;
        this.dates = dates;
        this.resultDataMapper = resultDataMapper;
    }

    /**
     * Builds the session.
     *
     * @return the session
     */
    /* default */ InformantRegisterHearing build() {
        return new InformantRegisterHearing(
                courtRoom(),
                hearingStartTime(),
                new DefendantMapper(hearing, fragment, resultDataMapper).build());
    }

    /**
     * The room the session sat in, or {@code "N/A"} for a hearing that had none.
     *
     * @return the room
     */
    private String courtRoom() {
        if (Json.truthy(hearing, "isBoxHearing") || Json.truthy(hearing, "isSJPHearing")) {
            return NO_ROOM;
        }
        // `this.hearingJson.courtCentre.roomName` — dereferenced with no guard
        // (CourtSessionMapper.js:15).
        return Json.text(Json.dereferenced(hearing, "courtCentre"), "roomName");
    }

    /**
     * The first sitting day's start, rendered as an RFC 3339 {@code full-time}.
     *
     * @return the start time, or {@code null} when the hearing has no listed days
     */
    private String hearingStartTime() {
        // `this.hearingJson.hearingDays.length` — dereferenced with no guard
        // (CourtSessionMapper.js:26). An empty list is legal; an absent one is not.
        final List<JsonNode> hearingDays = Json.dereferencedArray(hearing, "hearingDays");
        if (hearingDays.isEmpty()) {
            return null;
        }
        final JsonNode first = hearingDays.getFirst();
        // The legacy calls DateService.getLocalDateTime here (CourtSessionMapper.js:28); this is the
        // sanctioned re-rendering of that value — doc/DEVIATIONS.md entry 15.
        return Json.truthy(first, "sittingDay")
                ? dates.localFullTime(Json.text(first, "sittingDay"))
                : null;
    }
}
