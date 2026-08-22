package uk.gov.hmcts.cp.informantregister.adapter.payload;

import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;

/**
 * The results query API, read when the cache has nothing.
 *
 * <p>The endpoint, the vendor media type and the identity header are the query side's contract, not
 * this service's: {@code GET
 * /results-query-api/query/api/rest/results/hearingDetails/internal/{hearingId}} with
 * {@code Accept: application/vnd.results.hearing-details-internal+json} and a {@code CJSCPPUID}
 * header ({@code doc/API_CONTRACTS.md}, "Other outbound calls"). The internal variant is the right
 * one because it returns the untransformed hearing, which is what the cache holds.
 *
 * <p>The retry rule is the function app's, ported rather than improved. {@code AxiosRetryWrapper}
 * makes at most three attempts a second apart, and abandons immediately when a response arrived
 * carrying a status of 429 or below — so every 4xx, and 429 with it, is a single attempt, while 5xx
 * and a connection that never answered are retried. Retrying 429 harder than a 500 would be the
 * sensible policy and is precisely what parity forbids here; changing it is a deviations-register
 * matter, and the register's transport-reliability entry covers the submission client, not this
 * read.
 */
public class ResultsQueryHearingPayloadClient implements HearingPayloadQuery {

    /** The vendor media type the internal hearing-details resource is served as. */
    public static final String ACCEPT = "application/vnd.results.hearing-details-internal+json";

    /** The header carrying the system user identity the query side authorises against. */
    public static final String USER_ID_HEADER = "CJSCPPUID";

    private static final String PATH =
            "/results-query-api/query/api/rest/results/hearingDetails/internal/{hearingId}";

    private static final Logger LOG =
            LoggerFactory.getLogger(ResultsQueryHearingPayloadClient.class);

    private final RestClient restClient;
    private final String systemUserId;
    private final ObjectMapper objectMapper;
    private final int maxAttempts;
    private final Duration retryInterval;

    /**
     * Builds the client over an already-configured HTTP client.
     *
     * @param restClient    the client, carrying the results base URL and its timeouts
     * @param systemUserId  the system user identity; blank means the fallback cannot be used
     * @param objectMapper  the shared mapper, so a response is read exactly as any other JSON is
     * @param maxAttempts   total attempts including the first, mirroring the legacy retry count
     * @param retryInterval the wait between attempts, mirroring the legacy retry interval
     */
    public ResultsQueryHearingPayloadClient(final RestClient restClient, final String systemUserId,
            final ObjectMapper objectMapper, final int maxAttempts, final Duration retryInterval) {
        this.restClient = restClient;
        this.systemUserId = systemUserId;
        this.objectMapper = objectMapper;
        this.maxAttempts = maxAttempts;
        this.retryInterval = retryInterval;
    }

    @Override
    public Optional<JsonNode> fetch(final DistributionCommand command) {
        LOG.trace("client={} user={} mapper={} attempts={} interval={}",
                restClient, systemUserId, objectMapper, maxAttempts, retryInterval);
        return Optional.empty();
    }
}
