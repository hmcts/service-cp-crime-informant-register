package uk.gov.hmcts.cp.informantregister.adapter.results;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
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
 * <p><strong>Success is {@code 202 Accepted} and nothing else.</strong> The contract declares one
 * success status, so any other 2xx is treated as a failure rather than as a lenient success: a 200
 * from a proxy or a re-pointed route would otherwise mark the authority POSTED for a command that
 * was never enqueued, and the register would be gone with the log saying it had been sent. It is not
 * retried either — the body may already have been applied — so it is reported non-transient under
 * its own code and parked where somebody can look at the endpoint.
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
    private static final int ACCEPTED = 202;
    private static final int TOO_MANY_REQUESTS = 429;
    private static final String RETRY_AFTER_HEADER = "Retry-After";

    /**
     * Delta-seconds, and short enough that the value cannot overflow a {@code long}.
     *
     * <p>Matching before parsing rather than parsing and catching: a header this client cannot act on
     * is a value to be classified, not a failure to be caught, and the pattern says which forms are
     * acted on without a {@code try} block having to imply it.
     */
    private static final Pattern DELTA_SECONDS = Pattern.compile("\\d{1,10}");

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
     * @throws IllegalArgumentException if no base URL or no identity is configured — a service that
     *                                  guesses at an endpoint is a service that can post a register
     *                                  to the wrong one, and a service that posts without
     *                                  {@code CJSCPPUID} is a service every register is refused
     *                                  from. Both are startup faults, not runtime ones: the bean is
     *                                  built when the context refreshes, so a deployment missing
     *                                  either fails to start instead of dead-lettering every hearing
     *                                  it is given
     */
    public ResultsCommandGateway(
            final InformantRegisterProperties.Results settings, final SubmissionPause pause) {
        if (isBlank(settings.baseUrl())) {
            throw new IllegalArgumentException("informantregister.results.base-url is required");
        }
        if (isBlank(settings.systemUserId())) {
            throw new IllegalArgumentException(
                    "informantregister.results.system-user-id is required: CJSCPPUID is part of the "
                            + "add-informant-register contract and an anonymous command is refused");
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
            if (outcome.refusal().isPresent()) {
                throw new SubmissionFailedException(
                        FailureClassification.NON_TRANSIENT, outcome.refusal().get());
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
        Outcome outcome;
        try {
            outcome = restClient.post()
                    .uri(INFORMANT_REGISTER_PATH)
                    .contentType(MediaType.parseMediaType(ADD_INFORMANT_REGISTER_MEDIA_TYPE))
                    .headers(headers -> {
                        // Unconditional: the constructor has already refused to build a gateway
                        // without an identity, so there is no anonymous request to guard against
                        // here — and a branch would only make one look possible.
                        headers.add(IDENTITY_HEADER, systemUserId);
                        extraHeaders.forEach(headers::add);
                    })
                    .body(body)
                    .exchange((request, response) -> classify(response, attempt), false);
        } catch (ResourceAccessException unreachable) {
            // Connect failure, read timeout, connection dropped: the request may or may not have been
            // applied. Unknown is not failed, and it is retried rather than written off.
            //
            // The exception travels with the line rather than only its type. Classifying it is what
            // the pipeline acts on; what it *was* — a refused connection, a read that timed out, a
            // route the mesh dropped — is what a human acts on, and the bounded ReasonCode this
            // failure is eventually reported under is deliberately incapable of carrying it. If this
            // line does not keep the cause, nothing does, and a classification that discards its
            // evidence is a swallow with a log line in front of it. It is safe to keep: a transport
            // exception is raised instead of a response, so it carries the endpoint and the socket
            // error and never a register body.
            LOG.warn("Submission attempt did not reach a verdict; retrying. attempt={}",
                    attempt, unreachable);
            outcome = Outcome.retryable(Optional.empty());
        }
        return outcome;
    }

    private Outcome classify(final ClientHttpResponse response, final int attempt) throws IOException {
        final HttpStatusCode status = response.getStatusCode();
        final Outcome outcome;

        if (status.value() == ACCEPTED) {
            outcome = Outcome.ACCEPTED;
        } else if (status.is2xxSuccessful()) {
            // The contract's success is 202 and nothing else. A 200 or a 204 means something other
            // than the command endpoint answered — a proxy, or a route that no longer reaches it —
            // and calling it success would mark the authority POSTED for a command that was never
            // enqueued, which is the silently lost register this service exists to prevent.
            LOG.error("Results answered a success this contract does not define; the command cannot "
                    + "be assumed enqueued. status={}", status.value());
            outcome = Outcome.NOT_ACCEPTED;
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
     * <p>Delta-seconds only. RFC 9110 also permits an HTTP-date, and this client deliberately does
     * not read one: acting on it would mean subtracting a remote clock's idea of now from this
     * pod's, and a server whose clock is a few minutes ahead would park a run past the claim it
     * holds. A header in any other form falls back to the exponential back-off, which is the same
     * outcome as no header at all and is bounded by the same ceiling.
     *
     * <p>Nothing here is caught, because nothing here throws: the form is recognised before it is
     * read, so an unusable header is classified rather than raised and absorbed.
     */
    private static Optional<Duration> retryAfter(final ClientHttpResponse response) {
        final String header = response.getHeaders().getFirst(RETRY_AFTER_HEADER);
        final String asked = header == null ? "" : header.trim();
        final Optional<Duration> wait;
        if (DELTA_SECONDS.matcher(asked).matches()) {
            wait = Optional.of(Duration.ofSeconds(Long.parseLong(asked)));
        } else {
            if (!asked.isEmpty()) {
                LOG.warn("Retry-After was not a number of seconds; using the back-off instead.");
            }
            wait = Optional.empty();
        }
        return wait;
    }

    /** A blank setting counts as unset: an environment overrides a value, it does not delete a key. */
    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
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

    /**
     * What one attempt came back as, and how long the server asked to be left alone for.
     *
     * <p>An attempt is one of three things: accepted, worth another attempt, or over — and an
     * attempt that is over carries the bounded code the failure is reported under, because the two
     * ways it can be over are two different investigations.
     */
    private record Outcome(
            boolean accepted, Optional<ReasonCode> refusal, Optional<Duration> retryAfter) {

        private static final Outcome ACCEPTED =
                new Outcome(true, Optional.empty(), Optional.empty());
        private static final Outcome REFUSED = new Outcome(
                false, Optional.of(ReasonCode.SUBMISSION_REJECTED), Optional.empty());
        private static final Outcome NOT_ACCEPTED = new Outcome(
                false, Optional.of(ReasonCode.SUBMISSION_NOT_ACCEPTED), Optional.empty());

        private static Outcome retryable(final Optional<Duration> retryAfter) {
            return new Outcome(false, Optional.empty(), retryAfter);
        }
    }
}
