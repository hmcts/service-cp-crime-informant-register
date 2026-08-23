package uk.gov.hmcts.cp.informantregister.adapter.refdata;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.application.NowSubscriptionsSource;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.ReferenceDataUnavailableException;

/**
 * The now-subscriptions read, against the reference-data query API.
 *
 * <p>The endpoint, the {@code on} query parameter, the vendor media type and the identity header are
 * reference data's contract, not this service's: {@code GET
 * /referencedata-query-api/query/api/rest/referencedata/now-subscriptions?on={YYYY-MM-DD}} with
 * {@code Accept: application/vnd.referencedata.query.get-now-subscriptions+json} and a
 * {@code CJSCPPUID} header ({@code ReferenceDataService.js:40-47}). The contract's own declaration
 * agrees on every part of it — {@code referencedata-query-api.raml:2352-2374} declares the path, the
 * {@code on} parameter and the same media type, and its response schema requires
 * {@code nowSubscriptions}.
 *
 * <p><strong>The day is chosen upstream, and it is chosen bug-for-bug.</strong>
 * {@code ReferenceDataService.js:38} derives {@code on} as
 * {@code new Date(registerDate).toISOString().slice(0, 10)}, so it reads the register date's
 * misleading literal {@code Z} at face value (design defect D9). That computation lives with the
 * transformation, in {@code RegisterTransformationChain.queryDate}, because the legacy makes it
 * outside its own try block and it can fail there; this client is handed the day it produced and
 * renders it as the plain {@code YYYY-MM-DD} the parameter takes.
 *
 * <p><strong>The retry rule is the function app's, ported rather than improved.</strong> This call
 * goes through the same {@code AxiosRetryWrapper.getWrapperWithDefault} the payload fallback does
 * ({@code ReferenceDataService.js:48}, {@code AxiosRetryWrapper.js:74-76}), which makes at most three
 * attempts a second apart and abandons immediately when a response arrived carrying a status of 429
 * or below ({@code AxiosRetryWrapper.js:34}). So every 4xx, and 429 with it, is a single attempt,
 * while 5xx and a connection that never answered are retried. Retrying 429 harder than a 500 would be
 * the sensible policy and is precisely what parity forbids here.
 *
 * <p><strong>What is not ported is the answer on failure.</strong> The legacy catches everything and
 * returns {@code null} ({@code ReferenceDataService.js:50-53}), which
 * {@code InformantRegisterSubscriptions/index.js:22} cannot tell from "reference data answered,
 * nobody is subscribed" — so an outage files a register that reaches nobody and nothing records why.
 * Every failure to obtain the body is reported here instead, as a transient
 * {@link ReferenceDataUnavailableException}: {@code doc/DEVIATIONS.md} entry 14.
 *
 * <p>That rule is drawn at the body, not at the status. An answer this client cannot read as JSON is
 * a fetch that produced no now-subscriptions body — a gateway's error page served with a 200 is the
 * everyday case — so it is reported for the same reason a 502 is. An answer it <em>can</em> read is
 * passed through whatever its shape: no body at all, a body with no {@code nowSubscriptions} member,
 * or one carrying no informant-register subscriptions are all legitimate business outcomes the legacy
 * carries on from ({@code index.js:22-33}), and the matching step is what reads the shape.
 */
public class ReferenceDataNowSubscriptionsClient implements NowSubscriptionsSource {

    /** The resource's path under the reference-data context. */
    public static final String PATH =
            "/referencedata-query-api/query/api/rest/referencedata/now-subscriptions";

    /** The query parameter carrying the day the subscription set is read as at. */
    public static final String ON = "on";

    /** The vendor media type the now-subscriptions resource is served as. */
    public static final String ACCEPT =
            "application/vnd.referencedata.query.get-now-subscriptions+json";

    /** The header carrying the system user identity reference data authorises against. */
    public static final String IDENTITY_HEADER = "CJSCPPUID";

    /**
     * The legacy wrapper's cut-off: a response carrying this status or below is never retried, which
     * is why 429 is not retried either. Ported as written ({@code AxiosRetryWrapper.js:34}).
     */
    private static final int LEGACY_RETRY_CUT_OFF = 429;

    /** The attempt budget at which the current try is the last one. */
    private static final int LAST_ATTEMPT = 1;

    private static final Logger LOG =
            LoggerFactory.getLogger(ReferenceDataNowSubscriptionsClient.class);

    private final RestClient restClient;
    private final String systemUserId;
    private final Map<String, String> extraHeaders;
    private final ObjectMapper objectMapper;
    private final int maxAttempts;
    private final Duration retryInterval;

    /**
     * Builds the client over an already-configured HTTP client.
     *
     * @param restClient    the client, carrying the reference-data base URL and its timeouts
     * @param systemUserId  the {@code CJSCPPUID} identity; a secret, never logged
     * @param extraHeaders  any further headers the mesh requires, name to value
     * @param objectMapper  the shared mapper, so a response is read exactly as any other JSON is
     * @param maxAttempts   total attempts including the first, mirroring the legacy retry count
     * @param retryInterval the wait between attempts, mirroring the legacy retry interval
     */
    public ReferenceDataNowSubscriptionsClient(final RestClient restClient,
            final String systemUserId, final Map<String, String> extraHeaders,
            final ObjectMapper objectMapper, final int maxAttempts, final Duration retryInterval) {
        this.restClient = restClient;
        this.systemUserId = systemUserId;
        this.extraHeaders = Map.copyOf(extraHeaders);
        this.objectMapper = objectMapper;
        this.maxAttempts = maxAttempts;
        this.retryInterval = retryInterval;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every path out of this method either answers with what reference data said or reports that
     * it could not be asked. There is no third one, and in particular no {@code null} standing in for
     * a failure — that substitution is the defect entry 14 records.
     */
    @Override
    public JsonNode fetch(final LocalDate on) {
        for (int attemptsLeft = maxAttempts; attemptsLeft > 0; attemptsLeft--) {
            final boolean lastAttempt = attemptsLeft <= LAST_ATTEMPT;
            try {
                return content(get(on));
            } catch (RestClientResponseException answered) {
                final int status = answered.getStatusCode().value();
                if (lastAttempt || status <= LEGACY_RETRY_CUT_OFF) {
                    // The status is reference data's own answer and is bounded by HTTP; the body is
                    // text somebody else wrote and this line reaches the log index.
                    LOG.warn("Reference data refused the now-subscriptions read, so the register "
                            + "cannot be addressed. queryDate={} status={}", on, status);
                    throw unavailable();
                }
            } catch (RestClientException unanswered) {
                if (lastAttempt) {
                    LOG.warn("Reference data did not answer the now-subscriptions read, so the "
                            + "register cannot be addressed. queryDate={}", on, unanswered);
                    throw unavailable();
                }
            }
            pause(on);
        }
        // Unreachable while max-attempts is at least one, which startup validation insists on. A
        // budget of zero would otherwise fall out of the loop having asked nobody, and answering
        // "nobody is subscribed" to that is the one answer this adapter must never give.
        LOG.error("The now-subscriptions read was never attempted. queryDate={}", on);
        throw unavailable();
    }

    /** Issues the read. Kept apart so the retry loop above reads as the rule it ports. */
    private String get(final LocalDate on) {
        final RestClient.RequestHeadersSpec<?> request = restClient.get()
                .uri(uri -> uri.path(PATH).queryParam(ON, on).build())
                .header(HttpHeaders.ACCEPT, ACCEPT)
                .header(IDENTITY_HEADER, systemUserId);
        extraHeaders.forEach(request::header);
        return request.retrieve().body(String.class);
    }

    /**
     * Reads the answered body.
     *
     * <p>An empty body is {@code response.data === ''} in the legacy, which is falsy, so
     * {@code InformantRegisterSubscriptions/index.js:22} returns the fragments untouched — an empty
     * answer, not an outage. A body that is not JSON at all is neither: nothing was obtained, and
     * reporting it is the same decision entry 14 records for a 502.
     */
    private JsonNode content(final String body) {
        JsonNode answer = null;
        if (body != null && !body.isBlank()) {
            try {
                answer = objectMapper.readTree(body);
            } catch (JacksonException notJson) {
                // By type, never by message. A parser quotes the token it choked on, and a
                // subscription body names organisations and email addresses (Principle VII).
                LOG.warn("Reference data answered the now-subscriptions read with something that is "
                        + "not JSON, so the register cannot be addressed. type={}",
                        notJson.getClass().getName());
                throw unavailable();
            }
        }
        return answer;
    }

    /** Waits out the retry interval; an interrupt ends the attempts rather than being dropped. */
    private void pause(final LocalDate on) {
        if (!retryInterval.isZero() && !retryInterval.isNegative()) {
            try {
                Thread.sleep(retryInterval);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOG.warn("Interrupted while waiting to retry the now-subscriptions read. "
                        + "queryDate={}", on);
                throw unavailable();
            }
        }
    }

    /** The one failure this adapter raises, always transient and always bounded. */
    private static ReferenceDataUnavailableException unavailable() {
        return new ReferenceDataUnavailableException(ReasonCode.REFERENCE_DATA_UNAVAILABLE);
    }
}
