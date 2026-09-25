package uk.gov.hmcts.cp.simulation;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.nothingFor;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.stressPeakUsers;
import static io.gatling.javaapi.jms.JmsDsl.jms;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.jms.JmsProtocolBuilder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exploratory stress/spike simulation — no assertions, report-only.
 *
 * <p>Use this to find the breaking point. Gatling results are informational; this simulation
 * has no Gatling assertions. Its publishes are still recorded in the {@link PublishManifest}
 * because it shares the WireMock journal with the capacity run: {@code verify-outputs.sh} must
 * attribute every POST to a publish. Its missing/extra outputs and latency are reported, never
 * gated — only {@code GATED_SIMULATIONS} (default {@code CapacitySimulation}) fail the run.
 *
 * <p>Run locally:
 * <pre>
 *   ./gradlew gatlingRun --simulation=uk.gov.hmcts.cp.simulation.StressSimulation
 * </pre>
 */
public class StressSimulation extends Simulation {

    private static final Logger LOG = LoggerFactory.getLogger(StressSimulation.class);

    private static final String QUEUE_NAME =
            System.getProperty("gatling.queueName", "informantregister.requests");
    private static final String WIREMOCK_URL =
            System.getProperty("gatling.wiremockUrl", "http://localhost:8080");
    private static final String ACTUATOR_URL =
            System.getProperty("gatling.actuatorUrl", "http://localhost:8082/informantregister");

    private static final String SIMULATION = StressSimulation.class.getSimpleName();

    private final AtomicInteger publishedCount = new AtomicInteger(0);

    private final JmsProtocolBuilder jmsProtocol = jms
            .connectionFactory(ServiceBusConnectionFactory.create());

    private final ScenarioBuilder rampScenario = PublishScenarios.publishing(
            SIMULATION, "Stress Ramp", "Publish", QUEUE_NAME, publishedCount);

    private final ScenarioBuilder spikeScenario = PublishScenarios.publishing(
            SIMULATION, "Spike Burst", "Publish (spike)", QUEUE_NAME, publishedCount);

    @Override
    public void before() {
        LOG.info("Stress test: verifying service is healthy at {}", ACTUATOR_URL);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ACTUATOR_URL + "/actuator/health"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException(
                        "ABORTING: Service health check returned " + response.statusCode());
            }
            LOG.info("Health check passed.");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Health check failed: " + e.getMessage(), e);
        }

        LOG.info("Configuring WireMock stubs at {}", WIREMOCK_URL);
        WireMockStubs.configure(WIREMOCK_URL);
        RedisPayloadSeeder.verifyReady();
    }

    @Override
    public void after() {
        PublishManifest.close();
        RedisPayloadSeeder.close();
        LOG.info("Stress test complete. Published {} messages total, recorded in {}. Outputs are "
                        + "verified by src/gatling/scripts/verify-outputs.sh.",
                publishedCount.get(), PublishManifest.path());
    }

    {
        setUp(
                rampScenario.injectOpen(
                        rampUsersPerSec(0).to(50).during(Duration.ofMinutes(10))
                ),
                spikeScenario.injectOpen(
                        nothingFor(Duration.ofSeconds(30)),
                        stressPeakUsers(200).during(Duration.ofSeconds(10)),
                        constantUsersPerSec(20).during(Duration.ofMinutes(2))
                )
        ).protocols(jmsProtocol);
        // No assertions — this is exploratory. Inspect the HTML report manually.
    }
}
