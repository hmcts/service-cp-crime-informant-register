package uk.gov.hmcts.cp.informantregister.adapter.payload;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.application.HearingPayloadSource;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.PayloadUnavailableException;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;

/**
 * The payload source as the function app arranges it: the cache first, the query side after it.
 *
 * <p>Ordering, and the fact that a cache failure is not a request failure, are the whole of this
 * class. Both come from {@code HearingResultedCacheQuery.getHearing}: read the cache, and go to the
 * query API when the cache produced nothing — for whatever reason, an absent key and an unreachable
 * cache alike. Deciding that a cache which cannot answer has nothing to say belongs to the cache
 * adapter, which knows what its own failures look like; this class catches nothing, so a fault that
 * is not the cache's own reaches the pipeline and is recorded there rather than being spent on a
 * fallback.
 *
 * <p>Two keys are read, not one. The producer publishes the payload under a dated key and a legacy
 * undated twin (design doc §2.1, {@code doc/API_CONTRACTS.md}), and both are read here; the function
 * app reads exactly one, built from the hearing date it was given. That is registered deviation 4
 * ({@code doc/DEVIATIONS.md}).
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
        Optional<JsonNode> payload = cache.read(
                HearingPayloadCacheKey.cacheKey(keyPrefix, command.hearingId(),
                        command.hearingDay()));
        if (payload.isEmpty()) {
            payload = cache.read(
                    HearingPayloadCacheKey.cacheKey(keyPrefix, command.hearingId(), null));
        }
        if (payload.isEmpty()) {
            LOG.info("Hearing payload not cached; querying the results query API. "
                            + "requestId={} hearingId={} hearingDay={}",
                    command.requestId(), command.hearingId(), command.hearingDay());
            payload = query.fetch(command);
        }
        return payload.orElseThrow(() -> unavailable(command));
    }

    /**
     * Builds the failure for a payload no source supplied.
     *
     * <p>Transient by construction — {@link PayloadUnavailableException} fixes the classification —
     * and carrying the bounded reason code only, because the value reaches the processed log, the
     * dead-letter description and the log index.
     */
    private PayloadUnavailableException unavailable(final DistributionCommand command) {
        LOG.error("Hearing payload unavailable from the cache and the query API. "
                        + "requestId={} hearingId={} hearingDay={} reason={}",
                command.requestId(), command.hearingId(), command.hearingDay(),
                ReasonCode.PIPELINE_TRANSIENT_FAILURE.code());
        return new PayloadUnavailableException(ReasonCode.PIPELINE_TRANSIENT_FAILURE);
    }
}
