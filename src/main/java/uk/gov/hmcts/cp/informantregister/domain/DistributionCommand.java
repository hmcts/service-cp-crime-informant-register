package uk.gov.hmcts.cp.informantregister.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The validated form of an inbound queue message.
 *
 * <p>A typed record rather than a JSON tree because this is the service's own closed contract, not a
 * foreign payload: the six fields are agreed jointly with the publishing context and the schema is
 * {@code additionalProperties: false}. Inbound hearing payloads — which this service does not own —
 * stay canonical trees; see the constitution's Principle IV.
 *
 * <p>Every component is normalised at parse time, so an uppercase-hex identifier and an
 * offset-bearing instant arrive here in the same canonical shape as their lowercase and {@code Z}
 * equivalents.
 *
 * <p>Canonical schema: {@code src/main/resources/contracts/distribution-command.schema.json}.
 *
 * @param source      publishing system; namespaces {@code requestId} in the idempotency key
 * @param requestId   publisher-minted, deterministic from the hearing, day and shared time
 * @param hearingId   the resulted hearing
 * @param hearingDay  the hearing day this share relates to
 * @param sharedTime  the instant at which the hearing was shared, normalised to UTC
 * @param eventType   the publishing event; {@code Hearing_Resulted} only
 */
public record DistributionCommand(
        String source,
        UUID requestId,
        UUID hearingId,
        LocalDate hearingDay,
        Instant sharedTime,
        String eventType) {
}
