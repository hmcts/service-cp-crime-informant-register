package uk.gov.hmcts.cp.informantregister.pipeline;

import java.util.List;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterHearingVenue;
import uk.gov.hmcts.cp.informantregister.domain.RegisterFragment;

/**
 * The venue a register's hearing sat at.
 *
 * <p>A port of {@code OutboundInformantRegister/InformantRegisterAggregationRequest/Mapper/
 * HearingVenueMapper.js} — three lines of mapping, one of which is a single-element list holding the
 * session. However many days a hearing ran, the register describes one session.
 *
 * <p>The court house is read straight off {@code courtCentre}, with no guard, so a hearing without
 * one is a refusal (deviations-register entry 7). The local justice area is guarded — {@code
 * (courtCentre.lja || {}).ljaName} — so a venue with no local justice area simply has no name for
 * one, which the contract allows.
 */
final class HearingVenueMapper {

    private final JsonNode hearing;
    private final RegisterFragment fragment;
    private final HearingDates dates;
    private final ResultDataMapper resultDataMapper;

    /**
     * Creates the mapper.
     *
     * @param hearing          the canonical hearing tree
     * @param fragment         the authority's fragment
     * @param dates            the date service the session's start time is rendered by
     * @param resultDataMapper the mapper for the detail hanging off each result
     */
    /* default */ HearingVenueMapper(
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
     * Builds the venue.
     *
     * @return the venue
     */
    /* default */ InformantRegisterHearingVenue build() {
        // `this.hearingJson.courtCentre.name` — dereferenced with no guard
        // (HearingVenueMapper.js:13).
        final JsonNode courtCentre = Json.dereferenced(hearing, "courtCentre");
        return new InformantRegisterHearingVenue(
                Json.text(Json.at(courtCentre, "lja"), "ljaName"),
                Json.text(courtCentre, "name"),
                List.of(new CourtSessionMapper(hearing, fragment, dates, resultDataMapper)
                        .build()));
    }
}
