package uk.gov.hmcts.cp.simulation;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.nothingFor;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
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
 * Production-capacity validation simulation (pipeline gate — assertions fail the build).
 *
 * <p>Production peak load: ~1.51 msg/sec (5,420 messages in the busiest hour).
 * We test at 10x peak (15.1 msg/sec) to verify headroom.
 *
 * <p>Two separate scenarios with independent injection profiles:
 *   <ul>
 *     <li>Warm-up: short ramp to prime JIT/class-loading, no assertions
 *     <li>Capacity: measured load with assertions
 *   </ul>
 *
 * <p>The Gatling assertions below cover the broker publish only. Output completeness (exactly one
 * Results POST per unique request per expected authority, no extras) and end-to-end
 * publish-to-POST latency are gated by {@code src/gatling/scripts/verify-outputs.sh}, which
 * correlates the {@link PublishManifest} against the WireMock journal after the pipeline drains.
 * The validation pipeline also seeds 80% of hearing claim checks into ephemeral Redis before
 * publishing; the remaining hearings exercise the Results query fallback.
 *
 * <p>Run locally:
 * <pre>
 *   ./gradlew gatlingRun --simulation=uk.gov.hmcts.cp.simulation.CapacitySimulation
 * </pre>
 *
 * <p>Run against DEV ASB namespace (DefaultAzureCredential):
 * <pre>
 *   az login   # or set AZURE_CLIENT_ID / AZURE_TENANT_ID / AZURE_CLIENT_SECRET
 *   ./gradlew gatlingRun --simulation=uk.gov.hmcts.cp.simulation.CapacitySimulation \
 *     -Dgatling.asbNamespace=sbdevccm01.servicebus.windows.net \
 *     -Dgatling.wiremockUrl=http://zwiremock:8080 \
 *     -Dgatling.actuatorUrl=http://service:8082/informantregister
 * </pre>
 */
public class CapacitySimulation extends Simulation {

    private static final Logger LOG = LoggerFactory.getLogger(CapacitySimulation.class);

    private static final String QUEUE_NAME =
            System.getProperty("gatling.queueName", "informantregister.requests");
    private static final String WIREMOCK_URL =
            System.getProperty("gatling.wiremockUrl", "http://localhost:8080");
    private static final String ACTUATOR_URL =
            System.getProperty("gatling.actuatorUrl", "http://localhost:8082/informantregister");

    private static final double PRODUCTION_PEAK_RPS = 1.51;
    private static final double HEADROOM_MULTIPLIER = 10.0;
    private static final double TARGET_RPS = PRODUCTION_PEAK_RPS * HEADROOM_MULTIPLIER;

    private static final String SIMULATION = CapacitySimulation.class.getSimpleName();

    private final AtomicInteger publishedCount = new AtomicInteger(0);

    private final JmsProtocolBuilder jmsProtocol = jms
            .connectionFactory(ServiceBusConnectionFactory.create());

    private final ScenarioBuilder warmUpScenario = PublishScenarios.publishing(
            SIMULATION, "Warm-up", "Publish (warm-up)", QUEUE_NAME, publishedCount);

    private final ScenarioBuilder capacityScenario = PublishScenarios.publishing(
            SIMULATION, "Capacity", "Publish", QUEUE_NAME, publishedCount);

    @Override
    public void before() {
        LOG.info("Sanity check: verifying service is healthy at {}", ACTUATOR_URL);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ACTUATOR_URL + "/actuator/health"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException(
                        "ABORTING: Service health check returned " + response.statusCode()
                        + ". Is the service running?");
            }
            String body = response.body();
            if (body.contains("\"status\":\"DOWN\"")) {
                throw new RuntimeException(
                        "ABORTING: Service reports DOWN. Check consumer and database connectivity.");
            }
            LOG.info("Health check passed (HTTP {}).", response.statusCode());
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
        LOG.info("Simulation complete. Published {} messages, recorded in {}. Output completeness "
                        + "and publish-to-POST latency are verified by "
                        + "src/gatling/scripts/verify-outputs.sh once the pipeline drains.",
                publishedCount.get(), PublishManifest.path());
    }

    {
        setUp(
                warmUpScenario.injectOpen(
                        rampUsersPerSec(0).to(PRODUCTION_PEAK_RPS)
                                .during(Duration.ofMinutes(1))
                ),
                capacityScenario.injectOpen(
                        nothingFor(Duration.ofMinutes(1)),
                        constantUsersPerSec(PRODUCTION_PEAK_RPS)
                                .during(Duration.ofMinutes(5)),
                        rampUsersPerSec(PRODUCTION_PEAK_RPS).to(TARGET_RPS)
                                .during(Duration.ofMinutes(2)),
                        constantUsersPerSec(TARGET_RPS)
                                .during(Duration.ofMinutes(5))
                )
        ).protocols(jmsProtocol)
                // Broker publish latency only; end-to-end latency is gated by verify-outputs.sh
                .assertions(
                        details("Publish").responseTime().percentile(95).lt(100),
                        details("Publish").responseTime().percentile(99).lt(500),
                        details("Publish").successfulRequests().percent().gt(99.9)
                );
    }
}
