package uk.gov.hmcts.cp.simulation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pre-load sanity check shared by every simulation: is the service in a position to process?
 *
 * <p>Gates on the <strong>readiness</strong> group ({@code db} and {@code intakeStartup}), the
 * service's own definition of ready, not on the aggregate {@code /actuator/health}. The aggregate
 * also carries the {@code servicebus} component, which the service deliberately keeps out of
 * readiness, and which reports DOWN once a consumer that has never received a delivery outlives its
 * startup grace ({@code health-staleness}, 60s). A perf run purges the queue before the service
 * starts, so the first delivery is Gatling's own publish: gating on the aggregate deadlocks any run
 * whose Gatling starts more than a minute after the service (build 962604). A broker the consumer
 * really cannot reach still fails the run, as missing outputs in {@code verify-outputs.sh}.
 *
 * <p>Whatever the outcome, the aggregate body is logged, so a broker fault ({@code condition},
 * {@code lastErrorAt}) is on the record rather than inferred from a status code.
 */
final class ServiceReadiness {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceReadiness.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int OK = 200;

    private ServiceReadiness() {
    }

    /**
     * Aborts the simulation unless the service's readiness group is UP.
     *
     * @param actuatorUrl the service's actuator base URL (the pipeline's pod port-forward)
     * @throws IllegalStateException if readiness is not UP or the actuator cannot be reached
     */
    static void verify(final String actuatorUrl) {
        LOG.info("Sanity check: verifying service readiness at {}", actuatorUrl);
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            final HttpResponse<String> readiness = get(client, actuatorUrl + "/actuator/health/readiness");
            final HttpResponse<String> aggregate = get(client, actuatorUrl + "/actuator/health");
            LOG.info("Service health (HTTP {}): {}", aggregate.statusCode(), aggregate.body());
            if (readiness.statusCode() != OK) {
                throw new IllegalStateException("ABORTING: service readiness returned "
                        + readiness.statusCode() + ": " + readiness.body());
            }
            LOG.info("Readiness check passed (HTTP {}).", readiness.statusCode());
        }
    }

    private static HttpResponse<String> get(final HttpClient client, final String url) {
        try {
            return client.send(HttpRequest.newBuilder().uri(URI.create(url)).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("ABORTING: service actuator unreachable at " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted checking service readiness at " + url, e);
        }
    }
}
