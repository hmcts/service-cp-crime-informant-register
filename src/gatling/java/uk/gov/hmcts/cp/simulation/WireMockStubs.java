package uk.gov.hmcts.cp.simulation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configures WireMock stubs via the admin API ({@code /__admin/mappings}) for the three
 * outbound calls the informant-register pipeline makes:
 *
 * <ol>
 *   <li>Hearing payload fetch (results-query-api GET)
 *   <li>NOW subscriptions (referencedata-query-api GET)
 *   <li>Results add-informant-register (results-command-api POST)
 * </ol>
 *
 * <p>The mappings are the files under {@code src/gatling/resources/wiremock/mappings/} — the same
 * files {@code setup-wiremock-stubs.sh} installs in the pipeline, and the hearing payload is where
 * {@code verify-outputs.sh} reads the expected authorities from. Edit the files, not this class.
 *
 * <p>This path is for local runs. In the pipeline the Gatling process cannot reach WireMock (the
 * agent's istio sidecar routes localhost:8080 to the service pod), so these calls only log a
 * warning and the shell script is what configures the stubs.
 */
public final class WireMockStubs {

    private static final Logger LOG = LoggerFactory.getLogger(WireMockStubs.class);

    private static final List<String> MAPPINGS = List.of(
            "wiremock/mappings/hearing-payload.json",
            "wiremock/mappings/now-subscriptions.json",
            "wiremock/mappings/informant-register.json");

    private WireMockStubs() {
    }

    /**
     * Resets the request journal and installs all stubs.
     *
     * @param wiremockUrl the WireMock base URL (e.g. {@code http://localhost:8080})
     */
    public static void configure(String wiremockUrl) {
        try (HttpClient client = HttpClient.newHttpClient()) {
            resetJournal(client, wiremockUrl);
            MAPPINGS.forEach(mapping -> createStub(client, wiremockUrl, mapping));
            LOG.info("WireMock stubs configured at {}", wiremockUrl);
        }
    }

    private static void resetJournal(HttpClient client, String wiremockUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(wiremockUrl + "/__admin/requests"))
                    .DELETE()
                    .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
            LOG.info("WireMock request journal reset");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Failed to reset WireMock journal: {}", e.getMessage());
        }
    }

    private static void createStub(HttpClient client, String wiremockUrl, String mapping) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(wiremockUrl + "/__admin/mappings"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(read(mapping)))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201) {
                LOG.warn("WireMock stub creation for {} returned {}: {}",
                        mapping, response.statusCode(), response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("WireMock unreachable at {} — stubs may already be configured by the "
                    + "pipeline script: {}", wiremockUrl, e.getMessage());
        }
    }

    private static String read(String resource) {
        try (InputStream in = WireMockStubs.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing WireMock mapping on classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read WireMock mapping " + resource, e);
        }
    }
}
