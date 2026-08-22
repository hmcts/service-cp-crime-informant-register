package uk.gov.hmcts.cp.informantregister.domain;

import java.util.List;

/**
 * The venue whose sessions this authority's register covers.
 *
 * <p>Required on the command, and the only branch of the document that the schema insists on: a
 * register with no venue, or a venue with no sessions, is not a valid body.
 *
 * @param ljaName       the local justice area name
 * @param courtHouse    the venue name; required by the schema
 * @param courtSessions the sessions held at the venue; required by the schema
 */
public record InformantRegisterHearingVenue(
        String ljaName,
        String courtHouse,
        List<InformantRegisterHearing> courtSessions) {
}
