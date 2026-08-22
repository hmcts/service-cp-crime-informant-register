package uk.gov.hmcts.cp.informantregister.adapter.results;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import uk.gov.hmcts.cp.informantregister.config.InformantRegisterProperties;
import uk.gov.hmcts.cp.informantregister.domain.FailureClassification;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.SubmissionFailedException;

/**
 * The one HTTP call this service makes outwards: {@code add-informant-register}, once per authority.
 *
 * <p>The contract is results-owned and frozen. The path, the vendor media type and the identity
 * header are constants here because they are constants there, and the body is passed through as the
 * bytes the caller produced — nothing in this class inspects, reshapes or adds to it. A gateway that
 * touched the body would be a second place the outbound contract was defined.
 *
 * <p><strong>The retry policy is this delivery's one sanctioned behaviour change</strong>
 * ({@code doc/DEVIATIONS.md} #2). The function app made this call with a bare {@code axios.post},
 * caught whatever came back and logged it, so a hearing could be lost in silence. Here:
 *
 * <ul>
 *   <li>a connect or read failure, and a connection dropped mid-flight, are retried — the outcome is
 *       <em>unknown</em>, not failed;</li>
 *   <li>a 5xx is retried, with the wait doubling each time;</li>
 *   <li>a 429 is retried after the delay the server asked for, capped;</li>
 *   <li>any other 4xx is a refusal, is never retried, and comes back non-transient: the same bytes
 *       will be refused again and the delivery budget is finite.</li>
 * </ul>
 *
 * <p><strong>An unknown outcome is retried, and that is not at-most-once.</strong> A POST that timed
 * out may have been applied. Retrying it can therefore create a duplicate register row — which the
 * 19:00 sweep absorbs, taking the latest row per hearing — while not retrying it can lose the
 * hearing, which nothing absorbs and nobody sees. The trade is made deliberately in that direction
 * and no code or comment here promises more than it.
 *
 * <p>Nothing the server says is ever carried out of this class. The failure reason is one of a
 * bounded set of codes, because it reaches a dead-letter description and the log index, and a
 * response body from a register command can name a defendant.
 */
public class ResultsCommandGateway {

    /** The command's path under the Results context, exactly as the command API's RAML declares it. */
    public static final String INFORMANT_REGISTER_PATH =
            "/results-command-api/command/api/rest/results/informant-register";

    /** The command's vendor media type; the framework routes on it, so it is not a formality. */
    public static final String ADD_INFORMANT_REGISTER_MEDIA_TYPE =
            "application/vnd.results.add-informant-register+json";

    /** The CPP identity header. Its value is a secret and is never logged. */
    public static final String IDENTITY_HEADER = "CJSCPPUID";

    private static final Logger LOG = LoggerFactory.getLogger(ResultsCommandGateway.class);
    private static final int TOO_MANY_REQUESTS = 429;
    private static final String RETRY_AFTER_HEADER = "Retry-After";

    private final RestClient restClient;
    private final String systemUserId;
    private final Map<String, String> extraHeaders;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final SubmissionPause pause;

    /**
     * Builds the client the adapter posts through.
     *
     * @param settings the Results endpoint, identity and retry policy
     * @param pause    how a wait between attempts is taken
     * @throws IllegalArgumentException if no base URL is configured — a service that guesses at an
     *                                  endpoint is a service that can post a register to the wrong one
     */
    public ResultsCommandGateway(
            final InformantRegisterProperties.Results settings, final SubmissionPause pause) {
        if (settings.baseUrl() == null || settings.baseUrl().isBlank()) {
            throw new IllegalArgumentException("informantregister.results.base-url is required");
        }
        final SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(settings.connectTimeout());
        requestFactory.setReadTimeout(settings.readTimeout());

        this.restClient = RestClient.builder()
                .baseUrl(settings.baseUrl())
                .requestFactory(requestFactory)
                .build();
        this.systemUserId = settings.systemUserId();
        this.extraHeaders = settings.headers();
        this.maxAttempts = settings.maxAttempts();
        this.initialBackoff = settings.initialBackoff();
        this.maxBackoff = settings.maxBackoff();
        this.pause = pause;
    }

    /**
     * Posts one {@code add-informant-register} body, retrying only what a retry could fix.
     *
     * @param body the serialised document, sent byte for byte
     * @throws SubmissionFailedException carrying {@code NON_TRANSIENT} when the command was refused,
     *                                   and {@code TRANSIENT} when the attempts ran out with the
     *                                   outcome still unresolved
     */
    public void post(final byte[] body) {
        Duration backoff = initialBackoff;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            final Outcome outcome = attempt(body, attempt);
            if (outcome.accepted()) {
                return;
            }
            if (outcome.refused()) {
                throw new SubmissionFailedException(
                        FailureClassification.NON_TRANSIENT, ReasonCode.SUBMISSION_REJECTED);
            }
            if (attempt < maxAttempts) {
                waitFor(capped(outcome.retryAfter().orElse(backoff)));
                backoff = capped(backoff.multipliedBy(2));
            }
        }

        LOG.error("Submission attempts exhausted with the outcome unresolved; the delivery is handed "
                + "back. attempts={}", maxAttempts);
        throw new SubmissionFailedException(
                FailureClassification.TRANSIENT, ReasonCode.PIPELINE_TRANSIENT_FAILURE);
    }

    /**
     * One attempt, classified.
     *
     * <p>{@code exchange} rather than a plain {@code retrieve}: the default error handling turns a
     * non-2xx into an exception before the status can be read, and this class's whole job is to tell
     * three kinds of non-2xx apart.
     */
    private Outcome attempt(final byte[] body, final int attempt) {
        try {
            return restClient.post()
                    .uri(INFORMANT_REGISTER_PATH)
                    .contentType(MediaType.parseMediaType(ADD_INFORMANT_REGISTER_MEDIA_TYPE))
                    .headers(headers -> {
                        if (systemUserId != null && !systemUserId.isBlank()) {
                            headers.add(IDENTITY_HEADER, systemUserId);
                        }
                        extraHeaders.forEach(headers::add);
                    })
                    .body(body)
                    .exchange((request, response) -> classify(response, attempt), false);
        } catch (ResourceAccessException unreachable) {
            // Connect failure, read timeout, connection dropped: the request may or may not have been
            // applied. Unknown is not failed, and it is retried rather than written off.
            LOG.warn("Submission attempt did not reach a verdict; retrying. attempt={} type={}",
                    attempt, unreachable.getClass().getSimpleName());
            return Outcome.retryable(Optional.empty());
        }
    }

    private Outcome classify(final ClientHttpResponse response, final int attempt) throws IOException {
        final HttpStatusCode status = response.getStatusCode();
        final Outcome outcome;

        if (status.is2xxSuccessful()) {
            outcome = Outcome.ACCEPTED;
        } else if (status.value() == TOO_MANY_REQUESTS) {
            LOG.warn("Results asked this service to slow down. attempt={}", attempt);
            outcome = Outcome.retryable(retryAfter(response));
        } else if (status.is5xxServerError()) {
            LOG.warn("Results could not process the command. attempt={} status={}",
                    attempt, status.value());
            outcome = Outcome.retryable(Optional.empty());
        } else {
            // 4xx other than 429, and anything else that is not a success: the request was understood
            // and declined, so the same bytes will be declined again.
            LOG.error("Results refused the command; no redelivery can change that. status={}",
                    status.value());
            outcome = Outcome.REFUSED;
        }
        return outcome;
    }

    /**
     * The delay the server asked for, if it asked in a form this client can act on.
     *
     * <p>Delta-seconds only. RFC 9110 also permits an HTTP-date, and this client does not read one:
     * an unreadable header falls back to the exponential back-off rather than to a guess, which is
     * the same outcome as no header at all and one less way to be wrong.
     */
    private static Optional<Duration> retryAfter(final ClientHttpResponse response) {
        final String header = response.getHeaders().getFirst(RETRY_AFTER_HEADER);
        Optional<Duration> asked = Optional.empty();
        if (header != null) {
            try {
                final long seconds = Long.parseLong(header.trim());
                if (seconds >= 0) {
                    asked = Optional.of(Duration.ofSeconds(seconds));
                }
            } catch (NumberFormatException notDeltaSeconds) {
                LOG.warn("Retry-After was not a number of seconds; using the back-off instead.");
            }
        }
        return asked;
    }

    /**
     * Caps a wait at the configured ceiling.
     *
     * <p>Which applies to a server-supplied {@code Retry-After} as much as to the back-off. A run
     * holds a claim for a bounded lease, and an hour-long delay asked for by a struggling or
     * misconfigured server would leave this run waiting long after its claim had been reclaimed.
     */
    private Duration capped(final Duration wait) {
        return wait.compareTo(maxBackoff) > 0 ? maxBackoff : wait;
    }

    /**
     * Waits, and treats an interrupt as the shutdown it is.
     *
     * <p>The interrupt is restored and the attempt given up as transient — never swallowed, and never
     * turned into a success. A delivery interrupted mid-retry is redelivered.
     */
    private void waitFor(final Duration wait) {
        try {
            pause.pause(wait);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SubmissionFailedException(
                    FailureClassification.TRANSIENT, ReasonCode.PIPELINE_TRANSIENT_FAILURE);
        }
    }

    /** What one attempt came back as, and how long the server asked to be left alone for. */
    private record Outcome(boolean accepted, boolean refused, Optional<Duration> retryAfter) {

        private static final Outcome ACCEPTED = new Outcome(true, false, Optional.empty());
        private static final Outcome REFUSED = new Outcome(false, true, Optional.empty());

        private static Outcome retryable(final Optional<Duration> retryAfter) {
            return new Outcome(false, false, retryAfter);
        }
    }
}
