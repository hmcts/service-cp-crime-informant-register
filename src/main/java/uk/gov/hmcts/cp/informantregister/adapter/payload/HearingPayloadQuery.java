package uk.gov.hmcts.cp.informantregister.adapter.payload;

import java.util.Optional;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;

/**
 * The query-side fallback for a payload the cache does not hold.
 *
 * <p>Internal to {@code adapter/payload}, for the same reason as {@link HearingPayloadCache}: the
 * composite adapter's ordering is worth testing on its own, and neither collaborator belongs in the
 * core.
 *
 * <p>Every unsuccessful outcome — an error, an exhausted retry, a body with nothing in it — is an
 * empty result, matching {@code getPrefixHearing}, which returns the body only when it has content
 * and {@code null} otherwise. Turning "no payload" into a settlement decision is the composite
 * adapter's job, not this one's.
 */
public interface HearingPayloadQuery {

    /**
     * Asks the query side for the hearing payload.
     *
     * @param command the validated request naming the hearing
     * @return the payload, or empty when the query side did not supply one
     */
    Optional<JsonNode> fetch(DistributionCommand command);
}
