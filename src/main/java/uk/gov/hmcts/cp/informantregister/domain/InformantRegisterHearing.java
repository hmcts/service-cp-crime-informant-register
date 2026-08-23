package uk.gov.hmcts.cp.informantregister.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One court session within a venue's entry in the register.
 *
 * <p>Named for its schema, {@code informantRegisterHearing.json}, rather than for the property that
 * holds it, {@code courtSessions} — the two disagree in the contract itself and renaming the type
 * would hide that rather than resolve it. The property name is the one that reaches the wire.
 *
 * <p>{@code hearingStartTime} is a string, because the results-side binding holds it as one. The
 * schema marks it {@code format: time} — an RFC 3339 {@code full-time} — while the function app
 * renders a full date-time labelled {@code Z} and every example body in the results repository, the
 * RAML example and the integration-test template alike, puts a bare date in it. Three sources, three
 * shapes, and for as long as the port reproduced the function app it filled this component with one
 * the declared format refuses.
 *
 * <p>That is settled as of 2026-08-23: the pipeline renders the London wall clock with London's true
 * offset on the day, so the value written here is a {@code full-time} and satisfies the schema.
 * {@code doc/DEVIATIONS.md} entry 15, and {@code CourtSessionMapper} is where it is produced. The
 * component stays a {@code String} — retyping it would be a change to a frozen, results-owned
 * contract rather than a change to what this service renders into it.
 *
 * @param courtRoom        the room the session sat in; required by the schema
 * @param hearingStartTime the session's start time as an RFC 3339 full-time; required by the schema
 * @param defendants       the defendants heard in the session; required by the schema
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InformantRegisterHearing(
        String courtRoom,
        String hearingStartTime,
        List<InformantRegisterDefendant> defendants) {

    /**
     * Freezes the defendant list so the session cannot be changed after it is built.
     */
    public InformantRegisterHearing {
        defendants = ContractLists.frozen(defendants);
    }
}
