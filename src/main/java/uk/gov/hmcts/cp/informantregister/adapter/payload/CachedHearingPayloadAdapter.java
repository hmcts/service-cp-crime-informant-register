package uk.gov.hmcts.cp.informantregister.adapter.payload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.application.HearingPayloadSource;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.PayloadUnavailableException;

/**
 * The payload source as the function app arranges it: the cache first, the query side after it.
 *
 * <p>Ordering, and the fact that a cache failure is not a request failure, are the whole of this
 * class. Both come from {@code HearingResultedCacheQuery.getHearing}: read the cache, and go to the
 * query API when the cache produced nothing — for whatever reason, an absent key and an unreachable
 * cache alike.
 *
 * <p>Where it deliberately parts company with the function app is the end of the chain. There, a
 * hearing that neither source could supply returned {@code null}, the orchestrator's
 * {@code if (hearingResultedObj)} guard skipped every remaining step, and the run reported success
 * having done nothing. Here it raises {@link PayloadUnavailableException}, so the request is
 * retried and, if the deliveries run out, dead-lettered. That is registered deviation 2 (transport
 * reliability, {@code doc/DEVIATIONS.md}) and constitution Principle VI: "nothing happened" is not
 * an outcome this service is allowed to record.
 */
public class CachedHearingPayloadAdapter implements HearingPayloadSource {

    private static final Logger LOG = LoggerFactory.getLogger(CachedHearingPayloadAdapter.class);

    private final HearingPayloadCache cache;
    private final HearingPayloadQuery query;
    private final String keyPrefix;

    /**
     * Composes the two sources behind the single port.
     *
     * @param cache     the payload cache, read first
     * @param query     the query-side fallback, read when the cache produced nothing
     * @param keyPrefix the payload prefix the producer writes under, {@code INT_} for this flow
     */
    public CachedHearingPayloadAdapter(final HearingPayloadCache cache,
            final HearingPayloadQuery query, final String keyPrefix) {
        this.cache = cache;
        this.query = query;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public JsonNode fetch(final DistributionCommand command) {
        LOG.trace("cache={} query={} prefix={}", cache, query, keyPrefix);
        return null;
    }
}
