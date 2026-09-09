package uk.gov.hmcts.cp.informantregister.adapter.payload;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.observability.FaultSummary;

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

    /**
     * The header carrying the user identity the query side authorises against.
     *
     * <p>Its value is the run's caller: the user who shared the results where the message named one,
     * and the configured system identity otherwise ({@code doc/API_CONTRACTS.md}, "User
     * attribution").
     */
    public static final String USER_ID_HEADER = "CJSCPPUID";

    /**
     * The legacy wrapper's cut-off: a response carrying this status or below is never retried, which
     * is why 429 is not retried either. Ported as written ({@code AxiosRetryWrapper.retryGet}).
     */
    private static final int LEGACY_RETRY_CUT_OFF = 429;

    /** The attempt budget at which the current try is the last one. */
    private static final int LAST_ATTEMPT = 1;

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
     * @param systemUserId  the fallback identity, used for a message that names no user; blank means
     *                      such a message cannot use the fallback at all
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
        // The user who shared the results where the message named one, and the configured system
        // identity otherwise: the legacy makes this read as `input.cjscppuid`, which the trigger
        // copied from the envelope's userId (HearingResultedCacheQuery/index.js:40,
        // InformantRegisterOrchestrator/index.js:13).
        final String caller = CallerIdentity.of(command).orSystem(systemUserId);
        Optional<JsonNode> payload = Optional.empty();
        if (caller == null || caller.isBlank()) {
            // Neither the message nor the configuration named anybody. The query side authorises on
            // this header, so an anonymous read is a 403 dressed up as a cache miss.
            LOG.warn("No user identity is available, so the payload fallback cannot be "
                            + "used. requestId={} hearingId={}",
                    command.requestId(), command.hearingId());
        } else {
            payload = attempt(command, caller);
        }
        return payload;
    }

    /**
     * The legacy retry loop: at most {@code maxAttempts} tries, and none of them after a response
     * has arrived carrying a status at or below the cut-off.
     */
    private Optional<JsonNode> attempt(final DistributionCommand command, final String caller) {
        Optional<JsonNode> payload = Optional.empty();
        boolean tryAgain = true;
        for (int attemptsLeft = maxAttempts; tryAgain && attemptsLeft > 0; attemptsLeft--) {
            final boolean lastAttempt = attemptsLeft <= LAST_ATTEMPT;
            try {
                payload = content(get(command.hearingId(), caller));
                tryAgain = false;
            } catch (RestClientResponseException answered) {
                final int status = answered.getStatusCode().value();
                if (lastAttempt || status <= LEGACY_RETRY_CUT_OFF) {
                    refused(command, status);
                    tryAgain = false;
                }
            } catch (RestClientException unanswered) {
                if (lastAttempt) {
                    LOG.warn("The results query API did not answer. requestId={} hearingId={}",
                            command.requestId(), command.hearingId(), unanswered);
                    tryAgain = false;
                }
            }
            tryAgain = tryAgain && pause(command);
        }
        return payload;
    }

    /** Issues the read. Kept apart so the retry loop above reads as the rule it ports. */
    private String get(final UUID hearingId, final String caller) {
        return restClient.get()
                .uri(PATH, hearingId)
                .header(HttpHeaders.ACCEPT, ACCEPT)
                .header(USER_ID_HEADER, caller)
                .retrieve()
                .body(String.class);
    }

    /**
     * Decides whether a 2xx body actually carried a payload.
     *
     * <p>The query side answers a hearing it does not hold with {@code 200} and an empty object, so
     * a successful status proves nothing. The function app's own test is the same one — the body
     * counts only when it has content — and a body that is not JSON at all is treated the same way,
     * because there is no payload in it either.
     */
    private Optional<JsonNode> content(final String body) {
        Optional<JsonNode> payload = Optional.empty();
        if (body != null && !body.isBlank()) {
            try {
                final JsonNode parsed = objectMapper.readTree(body);
                if (parsed != null && !parsed.isNull() && !parsed.isMissingNode()
                        && !parsed.isEmpty()) {
                    payload = Optional.of(parsed);
                }
            } catch (JacksonException notJson) {
                // By type, never by message. A parser quotes the token it choked on, and in a
                // truncated hearing response that token is a name, an address or a URN — which this
                // line would then carry into a log index (constitution Principle VII).
                LOG.warn("The results query API answered with something that is not JSON. type={}",
                        FaultSummary.typeChain(notJson));
            }
        }
        return payload;
    }

    /**
     * Records a refusal.
     *
     * <p>The status is logged because it is the query side's own answer and bounded by HTTP; the
     * body is not, because it is text somebody else wrote and this line reaches the log index.
     */
    private void refused(final DistributionCommand command, final int status) {
        LOG.warn("The results query API refused the payload read. requestId={} hearingId={} "
                + "status={}", command.requestId(), command.hearingId(), status);
    }

    /**
     * Waits out the retry interval, reporting whether waiting is still allowed.
     *
     * <p>An interrupt is not ignored: the flag is restored and the caller stops, which leaves the
     * request with no payload and so transiently failed, rather than with a thread that has quietly
     * lost its interrupt.
     */
    private boolean pause(final DistributionCommand command) {
        boolean mayContinue = true;
        if (!retryInterval.isZero() && !retryInterval.isNegative()) {
            try {
                Thread.sleep(retryInterval);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOG.warn("Interrupted while waiting to retry the payload read. requestId={} "
                        + "hearingId={}", command.requestId(), command.hearingId());
                mayContinue = false;
            }
        }
        return mayContinue;
    }
}
