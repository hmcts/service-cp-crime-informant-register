package uk.gov.hmcts.cp.informantregister.domain;

import java.util.List;

/**
 * One court session within a venue's entry in the register.
 *
 * <p>Named for its schema, {@code informantRegisterHearing.json}, rather than for the property that
 * holds it, {@code courtSessions} — the two disagree in the contract itself and renaming the type
 * would hide that rather than resolve it. The property name is the one that reaches the wire.
 *
 * <p>{@code hearingStartTime} is a string. The schema marks it {@code format: time}, but the
 * results-side binding holds it as {@code String}, and every example body in the results repository
 * — the RAML example and the integration-test template alike — puts a date in it. The value written
 * here is whatever the ported pipeline renders; the disagreement between the declared format and
 * the observed payloads is recorded as an open question rather than settled by this record.
 *
 * @param courtRoom        the room the session sat in; required by the schema
 * @param hearingStartTime the session's start time, as rendered; required by the schema
 * @param defendants       the defendants heard in the session; required by the schema
 */
public record InformantRegisterHearing(
        String courtRoom,
        String hearingStartTime,
        List<InformantRegisterDefendant> defendants) {
}
