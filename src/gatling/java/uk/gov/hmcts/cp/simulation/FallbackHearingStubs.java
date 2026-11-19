package uk.gov.hmcts.cp.simulation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Registers an exact-path WireMock stub for each hearing that is not seeded into Redis, before it
 * is published, so the Results query fallback answers with that hearing's own {@code hearing.id}.
 *
 * <p>The shared hearing-payload mapping echoes the hearingId through response templating, which the
 * pipeline's WireMock (a WAR in WildFly) does not apply: every fallback hearing would come back with
 * a literal {@code {{regexExtract ...}}} id and its Results POSTs could not be correlated. A static
 * stub per hearing needs no templating. It outranks the shared mapping, which stays as a catch-all —
 * a fallback hearing that somehow has no stub still produces an uncorrelatable POST, and the
 * verifier's {@code unexpected} gate reports it.
 *
 * <p>Enabled by {@code gatling.wiremockAdminUrl}, the pipeline's port-forward to the WireMock pod.
 * Absent, it does nothing: local runs use a WireMock that templates. A stub that cannot be
 * registered fails the virtual user before the send, so a message whose output could never be
 * verified is never published; the send then fails as a KO under the publish request, which counts
 * against the run's success gate, and {@link #close()} logs how many registrations failed so the KO
 * is not mistaken for a broker fault.
 *
 * <p>Assumes a single WireMock pod: the stub goes to the pod behind the port-forward, while the
 * service reaches WireMock through the ingress. {@code setup-wiremock-stubs.sh} enforces it.
 */
final class FallbackHearingStubs {

    private static final Logger LOG = LoggerFactory.getLogger(FallbackHearingStubs.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int STUB_PRIORITY = 1;
    private static final int CREATED = 201;
    private static final int OK = 200;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final FallbackHearingStubs INSTANCE = create();

    private final HttpClient client;
    private final URI adminUri;
    // One registration per hearing: a duplicate reuses its original's hearingId and waits on the
    // same registration rather than racing a publish ahead of the stub
    private final ConcurrentMap<UUID, CompletableFuture<Void>> registrations = new ConcurrentHashMap<>();
    private final AtomicLong failures = new AtomicLong();

    private FallbackHearingStubs(final String adminUrl) {
        adminUri = URI.create(adminUrl.endsWith("/") ? adminUrl : adminUrl + '/');
        // HTTP/1.1: no h2c upgrade attempt against WildFly through kubectl port-forward
        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(TIMEOUT)
                .build();
        LOG.info("Fallback hearings will get a per-hearing WireMock stub via {}", adminUri);
    }

    /**
     * Fails fast, before any load, if the WireMock admin API is configured but unreachable.
     *
     * @throws IllegalStateException if WireMock does not answer its admin API
     */
    static void verifyReady() {
        if (INSTANCE == null) {
            return;
        }
        final HttpResponse<String> response = INSTANCE.send(HttpRequest.newBuilder()
                .uri(INSTANCE.adminUri.resolve("__admin/mappings?limit=1"))
                .timeout(TIMEOUT)
                .GET()
                .build());
        if (response.statusCode() != OK) {
            throw new IllegalStateException(
                    "WireMock admin API at " + INSTANCE.adminUri + " returned " + response.statusCode());
        }
    }

    /**
     * Registers this message's hearing stub, once per hearingId, blocking until it is in place.
     *
     * @param message a message whose hearing is not seeded into Redis
     * @throws IllegalStateException if WireMock does not accept the stub
     */
    static void register(final PublishedMessage message) {
        if (INSTANCE == null) {
            return;
        }
        INSTANCE.registerOnce(message.hearingId());
    }

    /** Logs how many stubs were registered and how many registrations failed, then closes. */
    static void close() {
        if (INSTANCE != null) {
            LOG.info("Fallback hearing stubs registered: {}", INSTANCE.registrations.size());
            if (INSTANCE.failures.get() > 0) {
                LOG.error("Fallback hearing stub registrations failed: {}. Each failed publish shows as a "
                        + "KO on the publish request; those messages were never sent.",
                        INSTANCE.failures.get());
            }
            INSTANCE.client.close();
        }
    }

    private void registerOnce(final UUID hearingId) {
        final CompletableFuture<Void> mine = new CompletableFuture<>();
        final CompletableFuture<Void> existing = registrations.putIfAbsent(hearingId, mine);
        if (existing != null) {
            try {
                existing.join();
            } catch (CompletionException e) {
                throw new IllegalStateException("Stub registration for hearing " + hearingId + " failed",
                        e.getCause());
            }
            return;
        }
        try {
            post(hearingId);
            mine.complete(null);
        } catch (RuntimeException e) {
            // Let a later publish of this hearing try again rather than inherit the failure
            registrations.remove(hearingId, mine);
            mine.completeExceptionally(e);
            failures.incrementAndGet();
            throw e;
        }
    }

    private void post(final UUID hearingId) {
        final HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(adminUri.resolve("__admin/mappings"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(
                        HearingPayloadFixture.stubFor(hearingId, STUB_PRIORITY))))
                .build());
        if (response.statusCode() != CREATED) {
            throw new IllegalStateException("WireMock refused the stub for hearing " + hearingId
                    + " with " + response.statusCode() + ": " + response.body());
        }
    }

    private HttpResponse<String> send(final HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("WireMock admin API unreachable at " + adminUri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling the WireMock admin API", e);
        }
    }

    private static FallbackHearingStubs create() {
        final String adminUrl = System.getProperty("gatling.wiremockAdminUrl", "");
        return adminUrl.isBlank() ? null : new FallbackHearingStubs(adminUrl);
    }
}
