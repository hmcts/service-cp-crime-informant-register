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
 * <p>An unreachable cache is reported as an empty result rather than as a failure, because that is
 * what the function app does with it: {@code getResultFromCache} catches every error and returns
 * {@code null}, and the caller then goes to the query API. A cache that is down is not a reason to
 * abandon a request that the query side can still answer.
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
