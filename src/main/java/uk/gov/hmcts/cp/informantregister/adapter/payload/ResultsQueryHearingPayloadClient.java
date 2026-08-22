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
        Optional<JsonNode> payload = Optional.empty();
        if (systemUserId == null || systemUserId.isBlank()) {
            LOG.warn("No system user identity is configured, so the payload fallback cannot be "
                            + "used. requestId={} hearingId={}",
                    command.requestId(), command.hearingId());
        } else {
            payload = attempt(command);
        }
        return payload;
    }

    /**
     * The legacy retry loop: at most {@code maxAttempts} tries, and none of them after a response
     * has arrived carrying a status at or below the cut-off.
     */
    private Optional<JsonNode> attempt(final DistributionCommand command) {
        Optional<JsonNode> payload = Optional.empty();
        boolean tryAgain = true;
        for (int attemptsLeft = maxAttempts; tryAgain && attemptsLeft > 0; attemptsLeft--) {
            final boolean lastAttempt = attemptsLeft <= LAST_ATTEMPT;
            try {
                payload = content(get(command.hearingId()));
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
    private String get(final UUID hearingId) {
        return restClient.get()
                .uri(PATH, hearingId)
                .header(HttpHeaders.ACCEPT, ACCEPT)
                .header(USER_ID_HEADER, systemUserId)
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
                        notJson.getClass().getName());
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
