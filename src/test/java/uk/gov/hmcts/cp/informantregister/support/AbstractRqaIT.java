package uk.gov.hmcts.cp.informantregister.support;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.adapter.refdata.ReferenceDataNowSubscriptionsClient;
import uk.gov.hmcts.cp.informantregister.adapter.results.ResultsCommandGateway;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Shared infrastructure for the RQA (Results QA) E2E integration tests. Each subclass supplies a
 * scenario-specific hearing via {@link #buildHearing()} and inherits the full container wiring,
 * lifecycle management and common builders.
 *
 * <p>Override {@link #seedTheWorld()} when the default {@code @BeforeEach} (reset stubs, seed cache,
 * stub APIs) does not fit the scenario — for example the amend-and-reshare test manages cache
 * seeding across its two phases.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractRqaIT {

    protected static final ParityCase BASE_CASE =
            ParityCase.load("recorded", "base__case-and-application");

    protected static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    protected static final UUID HEARING_ID =
            UUID.fromString(BASE_CASE.hearing().get("id").stringValue());

    protected static final String HEARING_DAY = "2021-03-11";

    protected static final String SHARING_USER_ID = "3d5f7a91-2c4e-4b86-9f10-7ac5be3d2081";

    /** Deliberately different from the sharing user — catches mis-resolution of the caller identity. */
    protected static final String SYSTEM_USER_ID = "00000000-0000-4000-8000-0000000000ff";

    protected static final String RESULT_TEXT = "Absolute discharge";

    protected static final String AUTHORITY_CODE = "TFL";

    protected static final Duration COMPLETED_WITHIN = Duration.ofSeconds(90);
    protected static final Duration POLL = Duration.ofSeconds(1);

    protected static WireMockServer results;
    protected static WireMockServer referenceData;
    protected static RedisClient cacheClient;
    protected static StatefulRedisConnection<String, String> cache;

    protected final UUID requestId = UUID.randomUUID();

    // --- lifecycle ---

    @DynamicPropertySource
    static void wireTheContainers(final DynamicPropertyRegistry registry) {
        results = new WireMockServer(wireMockConfig().dynamicPort());
        results.start();
        referenceData = new WireMockServer(wireMockConfig().dynamicPort());
        referenceData.start();
        cacheClient = RedisClient.create(RedisURI.create(RedisTestSupport.uri()));
        cache = cacheClient.connect();

        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
        registry.add("informantregister.servicebus.connection-string",
                ServiceBusEmulatorTestSupport::connectionString);
        registry.add("informantregister.payload.mode", () -> "LIVE");
        registry.add("informantregister.payload.redis.host", RedisTestSupport::host);
        registry.add("informantregister.payload.redis.port", RedisTestSupport::port);
        registry.add("informantregister.referencedata.mode", () -> "LIVE");
        registry.add("informantregister.referencedata.base-url", referenceData::baseUrl);
        registry.add("informantregister.referencedata.system-user-id", () -> SYSTEM_USER_ID);
        registry.add("informantregister.results.base-url", results::baseUrl);
        registry.add("informantregister.results.system-user-id", () -> SYSTEM_USER_ID);
    }

    @AfterAll
    static void closeTheFixtures() {
        cache.close();
        cacheClient.shutdown();
        referenceData.stop();
        results.stop();
    }

    @BeforeEach
    protected void seedTheWorld() {
        resetStubs();
        seedCache(buildHearing());
        stubApis();
    }

    // --- template hook ---

    protected abstract JsonNode buildHearing();

    // --- shared helpers ---

    protected void resetStubs() {
        results.resetAll();
        referenceData.resetAll();
    }

    protected void stubApis() {
        referenceData.stubFor(get(urlPathEqualTo(ReferenceDataNowSubscriptionsClient.PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", ReferenceDataNowSubscriptionsClient.ACCEPT)
                        .withBody(MAPPER.writeValueAsString(BASE_CASE.subscriptions()))));

        results.stubFor(post(urlEqualTo(ResultsCommandGateway.INFORMANT_REGISTER_PATH))
                .willReturn(aResponse().withStatus(202)));
    }

    protected void seedCache(final JsonNode hearing) {
        cache.sync().set(cacheKey(), cacheDocument(hearing));
    }

    protected static String cacheKey() {
        return "INT_" + HEARING_ID + '_' + HEARING_DAY + "_result_";
    }

    protected static String cacheDocument(final JsonNode hearing) {
        return cacheDocument(hearing, BASE_CASE.sharedTime(), false);
    }

    protected static String cacheDocument(
            final JsonNode hearing, final String sharedTime, final boolean isReshare) {
        return """
                {"isReshare":%s,"hearingDay":"%s","sharedTime":"%s","hearing":%s}\
                """.formatted(isReshare, HEARING_DAY, sharedTime,
                        MAPPER.writeValueAsString(hearing));
    }

    protected String messageBody() {
        return messageBody(requestId, BASE_CASE.sharedTime());
    }

    protected static String messageBody(final UUID requestId, final String sharedTime) {
        return """
                {
                  "source": "RESULTS",
                  "requestId": "%s",
                  "hearingId": "%s",
                  "hearingDay": "%s",
                  "sharedTime": "%s",
                  "eventType": "Hearing_Resulted",
                  "userId": "%s"
                }
                """.formatted(requestId, HEARING_ID, HEARING_DAY, sharedTime, SHARING_USER_ID);
    }

    protected static List<LoggedRequest> commandsForThisHearing() {
        return results
                .findAll(postRequestedFor(
                        urlEqualTo(ResultsCommandGateway.INFORMANT_REGISTER_PATH)))
                .stream()
                .filter(request -> HEARING_ID.toString().equals(
                        MAPPER.readTree(request.getBodyAsString())
                                .path("hearingId").stringValue()))
                .toList();
    }
}
