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
        Optional<JsonNode> payload = read(
                HearingPayloadCacheKey.cacheKey(keyPrefix, command.hearingId(),
                        command.hearingDay()),
                command);
        if (payload.isEmpty()) {
            payload = read(
                    HearingPayloadCacheKey.cacheKey(keyPrefix, command.hearingId(), null), command);
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
     * Reads one key, treating a cache that cannot answer as a cache with nothing in it.
     *
     * <p>The failure is logged at WARN with its cause, so a cache outage is visible rather than
     * inferred from a rise in query-side traffic. It is not rethrown, because the query side can
     * still answer and the function app's own behaviour here is to carry on.
     *
     * <p>The catch is deliberately as wide as the port. This class knows a cache capability and not
     * a cache technology, so it cannot name the exceptions a particular client throws — and naming a
     * few of them would mean the next client's failures escaped as unexpected pipeline errors rather
     * than as the fallback this method exists to perform.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private Optional<JsonNode> read(final String key, final DistributionCommand command) {
        Optional<JsonNode> found;
        try {
            found = cache.read(key);
        } catch (RuntimeException unreadable) {
            LOG.warn("Hearing payload cache could not be read; continuing to the query API. "
                            + "requestId={} hearingId={}",
                    command.requestId(), command.hearingId(), unreadable);
            found = Optional.empty();
        }
        return found;
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
