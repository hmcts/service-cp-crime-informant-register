package uk.gov.hmcts.cp.informantregister.e2e;

import java.time.Duration;
import java.util.UUID;

import com.azure.messaging.servicebus.models.SubQueue;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.RequestStatus;
import uk.gov.hmcts.cp.informantregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.RedisTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ServiceTestSupport;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The real payload adapter, wired to everything that decides what a delivery is worth.
 *
 * <p>Every other suite here selects the stub payload source, because settlement, the processed log
 * and health have nothing to do with a hearing payload. That leaves one composition untested: the
 * live adapter's outcomes reaching the guard, the processed log and the broker. Both adapter suites
 * and every end-to-end suite can pass with a payload failure wired to the wrong settlement, because
 * no suite holds both halves at once.
 *
 * <p>Two ends of the range are enough to hold the wiring:
 *
 * <ul>
 *   <li>a hearing the cache holds is fetched and the request completes, with the query side
 *       untouched — the ordering the function app fixed, proven through the running service rather
 *       than through a mock of it;</li>
 *   <li>a cold cache and a query side that refuses the read leaves nothing to process, so the
 *       request is failed and the delivery parked where support can see it. A 403 is not retried
 *       (the ported cut-off), so what is proven is the settlement, not the retry loop.</li>
 * </ul>
 *
 * <p>The refusal ends FAILED rather than RETRYING because the delivery budget runs out first: a
 * payload nobody can supply is transient by construction, and the transport gives it every delivery
 * the queue allows before parking it. Silence is the one outcome it may never have.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// The context owns a running consumer on the shared emulator queue, and unlike its neighbours this
// one fetches payloads for real. Closing it with the class keeps it from answering another suite's
// message out of a cache that has nothing in it.
@DisplayName("The live payload adapter, end to end")
class LivePayloadPipelineIT {

    /** The hearing day every body published here carries. */
    private static final String HEARING_DAY = "2026-08-21";

    private static final String INTERNAL_HEARING_DETAILS =
            "/results-query-api/query/api/rest/results/hearingDetails/internal/.*";

    /** Five deliveries, each with a broker round trip between them. */
    private static final Duration PARKED_WITHIN = Duration.ofSeconds(120);

    private static final Duration COMPLETED_WITHIN = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofSeconds(1);

    private static WireMockServer queryApi;
    private static RedisClient cacheClient;
    private static StatefulRedisConnection<String, String> cache;

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    @DynamicPropertySource
    static void wireTheContainers(final DynamicPropertyRegistry registry) {
        queryApi = new WireMockServer(wireMockConfig().dynamicPort());
        queryApi.start();
        cacheClient = RedisClient.create(RedisURI.create(RedisTestSupport.uri()));
        cache = cacheClient.connect();

        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
        registry.add("informantregister.servicebus.connection-string",
                ServiceBusEmulatorTestSupport::connectionString);
        // The real adapter, which is the whole point of this suite.
        registry.add("informantregister.payload.mode", () -> "LIVE");
        registry.add("informantregister.payload.redis.host", RedisTestSupport::host);
        registry.add("informantregister.payload.redis.port", RedisTestSupport::port);
        registry.add("informantregister.results.base-url", queryApi::baseUrl);
        registry.add("informantregister.results.system-user-id",
                () -> "9f61bdbb-6f1a-4c0f-9a3d-6b8f0f1c2a44");
        // The legacy interval is a second, and a parked request spends it on every attempt of every
        // delivery. What is under test here is the settlement, not the wait.
        registry.add("informantregister.payload.fallback.retry-interval", () -> "0s");
    }

    @AfterAll
    static void closeTheFixtures() {
        cache.close();
        cacheClient.shutdown();
        queryApi.stop();
    }

    private static String cacheKey(final UUID hearingId) {
        return "INT_" + hearingId + '_' + HEARING_DAY + "_result_";
    }

    private static String envelope(final UUID hearingId) {
        return """
                {"hearing":{"id":"%s","prosecutionCases":[]},"sharedTime":"2026-08-21T08:00:00Z"}
                """.formatted(hearingId);
    }

    @Test
    @DisplayName("a hearing the cache holds completes without the query side being asked")
    void should_complete_a_request_whose_payload_the_cache_holds() {
        cache.sync().set(cacheKey(hearingId), envelope(hearingId));
        queryApi.resetAll();

        ServiceTestSupport.publish(ServiceTestSupport.validBody(requestId, hearingId));

        await().atMost(COMPLETED_WITHIN).pollInterval(POLL).until(() ->
                ProcessedLogTestSupport.row(ProcessedLogTestSupport.SOURCE, requestId)
                        .filter(row -> RequestStatus.COMPLETED.name().equals(row.status()))
                        .isPresent());

        // For this hearing, not for any: the queue is shared, so a neighbouring suite's message
        // could reach this consumer and be answered from a cache that has nothing of its own in it.
        queryApi.verify(0, getRequestedFor(urlPathMatching(".*/internal/" + hearingId)));
    }

    @Test
    @DisplayName("a cold cache and a refused query read park the request rather than complete it")
    void should_park_a_request_whose_payload_no_source_will_supply() {
        queryApi.resetAll();
        queryApi.stubFor(get(urlPathMatching(INTERNAL_HEARING_DETAILS))
                .willReturn(aResponse().withStatus(403)));

        final String messageId =
                ServiceTestSupport.publish(ServiceTestSupport.validBody(requestId, hearingId));

        await().atMost(PARKED_WITHIN).pollInterval(POLL).until(() ->
                ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE)
                        .isPresent());

        final ProcessedLogTestSupport.Row parked =
                ProcessedLogTestSupport.requireRow(ProcessedLogTestSupport.SOURCE, requestId);
        assertThat(parked.status()).isEqualTo(RequestStatus.FAILED.name());
        assertThat(parked.failureReason())
                .isEqualTo(ReasonCode.PIPELINE_TRANSIENT_FAILURE.code());
    }
}
