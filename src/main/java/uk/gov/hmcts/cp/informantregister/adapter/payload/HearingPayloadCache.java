package uk.gov.hmcts.cp.informantregister.adapter.payload;

import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * The hearing payload cache, as the adapter needs to see it.
 *
 * <p>Internal to {@code adapter/payload}: the application core knows only
 * {@link uk.gov.hmcts.cp.informantregister.application.HearingPayloadSource}, and this seam exists
 * so the composite adapter's ordering and failure rules can be tested without a broker, a cache or a
 * network.
 *
 * <p>An unreachable cache is reported as an empty result rather than as a failure, so a cache that is
 * down is not a reason to abandon a request the query side can still answer (design rules,
 * "Transient … both Redis and the fallback unavailable"). The function app absorbs a failed
 * {@code GET} the same way; a failed <em>connection</em> it does not, which is registered deviation 5
 * ({@code doc/DEVIATIONS.md}). An implementation absorbs the failures of its own technology and
 * nothing else — anything wider would hide a defect in this service behind the fallback.
 */
public interface HearingPayloadCache {

    /**
     * Reads the payload stored under a key.
     *
     * @param key the key to read
     * @return the payload, or empty when the key is absent, unreadable or unparseable
     */
    Optional<JsonNode> read(String key);
}
