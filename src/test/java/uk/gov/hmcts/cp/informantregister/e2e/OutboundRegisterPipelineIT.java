package uk.gov.hmcts.cp.informantregister.e2e;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.azure.messaging.servicebus.models.SubQueue;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.adapter.refdata.ReferenceDataNowSubscriptionsClient;
import uk.gov.hmcts.cp.informantregister.adapter.results.ResultsCommandGateway;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.RequestStatus;
import uk.gov.hmcts.cp.informantregister.support.JsonParity;
import uk.gov.hmcts.cp.informantregister.support.ParityCase;
import uk.gov.hmcts.cp.informantregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.RedisTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ServiceTestSupport;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The whole service, end to end: one queue message becomes the Results commands it is worth.
 *
 * <p>Every other suite in this package stops short of the outbound edge, and deliberately.
 * {@code WalkingSkeletonIT} proves settlement and the processed log against the stub adapters;
 * {@code LivePayloadPipelineIT} proves the real payload adapter, but over a hearing carrying no
 * prosecution cases, so it produces no authorities. {@link ServiceTestSupport} says so in as many
 * words — "no suite produces an authority, so nothing is ever posted". That leaves the composition
 * this service exists for untested through the running application: <strong>a message arrives, the
 * payload is fetched, the ported transformation runs, and one {@code add-informant-register} command
 * per prosecuting authority is POSTed to Results.</strong>
 *
 * <p>Each half of that chain has its own suite, and none of them can fail if the halves disagree.
 * {@code RegisterTransformerParityTest} runs the transformation against the Node oracle with the
 * ports mocked; {@code ResultsRegisterSubmissionClientTest} and {@code ResultsCommandGatewayTest}
 * hold the outbound adapter to the frozen contract against a mocked transformation. A wiring fault
 * between them — the payload wrapper opened at the wrong seam, the caller identity resolved twice,
 * the mapper that serialises the body configured differently from the one the parity tests compare
 * with — passes every one of those suites and loses every register in production. Here the broker,
 * the cache, the store and both HTTP peers are real, and the only thing supplied is the input.
 *
 * <p><strong>The oracle is a recorded parity case, not an expectation written here.</strong>
 * {@code base__case-and-application} is a byte-identical copy of the function app's own Jest fixture
 * replayed through the real Node chain, chosen because it is the multi-document base: three
 * authorities across prosecution cases and a court application. Its {@code expected.json} is the
 * document array {@code ProcessOutboundInformantRegister} received, so asserting the POSTed bodies
 * against it asks the only question worth asking — would Results have received what the function app
 * sent it? The bodies are compared with {@link JsonParity}, under the same rules the parity suite
 * uses: field-order-insensitive, array-order-sensitive, BigDecimal-tolerant, with registered
 * deviations checked by derivation rather than skipped.
 *
 * <p><strong>The clock is left alone.</strong> The case's {@code meta.json} records
 * {@code clockDependent: false} — its register and hearing dates derive from the payload's
 * {@code sharedTime}, not from "now" — so pinning the context's {@code Clock} bean would buy nothing
 * and would freeze the one the Service Bus health indicator measures staleness against.
 *
 * <p><strong>The identity is the message's, and that is asserted.</strong> The message names the user
 * who shared the results, and the legacy threads that one value into the payload read, the
 * now-subscriptions read and the POST ({@code InformantRegisterOrchestrator/index.js:13,31,46}). A
 * register read as one caller and posted as another is attributable to nobody, so both outbound
 * calls are checked to carry it rather than the configured system identity — which is set to a
 * different value here precisely so the two cannot be confused.
 *
 * <p>Every assertion is keyed on this test's own request, hearing and message identities. The
 * emulator queue is shared with the other broker suites, so anything phrased about the queue's
 * contents, or about this stub server's traffic in total, would be a claim about them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// The context owns a running consumer on the shared emulator queue, and this one fetches payloads,
// reads reference data and POSTs for real. Closing it with the class keeps it from answering a
// neighbouring suite's message out of a cache and a pair of stub servers that know nothing about it.
@DisplayName("End to end: a queue message becomes one add-informant-register command per authority")
class OutboundRegisterPipelineIT {

    /** The multi-document base of the recorded corpus — three authorities, cases and an application. */
    private static final String CASE_ID = "base__case-and-application";

    private static final ParityCase PARITY_CASE = ParityCase.load("recorded", CASE_ID);

    /** The shared contract mapper, so a body is read here exactly as the service wrote it. */
    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    /**
     * The hearing the fixture is about, taken from the fixture rather than restated.
     *
     * <p>The message and the cache key are built from it, so the request this suite publishes names
     * the hearing whose payload it seeded — and the recorded documents' own {@code hearingId} is then
     * a real assertion rather than a constant agreeing with itself.
     */
    private static final UUID HEARING_ID =
            UUID.fromString(PARITY_CASE.hearing().get("id").stringValue());

    /**
     * The hearing day, which belongs to the cache key and to nothing else.
     *
     * <p>The register is stamped with the payload's {@code sharedTime}, not with this, so the value
     * is free; it is the fixture's own date so the seeded key reads the way a real one does.
     */
    private static final String HEARING_DAY = "2021-03-11";

    /** The user the message says shared the results. Every outbound call must be made as them. */
    private static final String SHARING_USER_ID = "3d5f7a91-2c4e-4b86-9f10-7ac5be3d2081";

    /**
     * The configured fallback identity — deliberately not {@link #SHARING_USER_ID}.
     *
     * <p>If the run ever posted as this, the identity was resolved from configuration rather than
     * from the message, and the assertion on the {@code CJSCPPUID} header would catch it. Two equal
     * values would make that assertion pass either way.
     */
    private static final String SYSTEM_USER_ID = "00000000-0000-4000-8000-0000000000ff";

    private static final int EXPECTED_AUTHORITIES = 3;

    private static final Duration COMPLETED_WITHIN = Duration.ofSeconds(90);
    private static final Duration POLL = Duration.ofSeconds(1);

    /** One server for both Results APIs, because one base URL configures both. */
    private static WireMockServer results;
    private static WireMockServer referenceData;
    private static RedisClient cacheClient;
    private static StatefulRedisConnection<String, String> cache;

    private final UUID requestId = UUID.randomUUID();

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

        // Every adapter live. This suite is the one that has no business substituting any of them:
        // what it exists to prove is that the real ones compose.
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

    /**
     * Seeds the cache and both peers before every test.
     *
     * <p>The stubs are reset first so the request journals this test reads hold its own traffic only.
     */
    @BeforeEach
    void seedTheWorld() {
        results.resetAll();
        referenceData.resetAll();

        cache.sync().set(cacheKey(), cacheDocument());

        referenceData.stubFor(get(urlPathEqualTo(ReferenceDataNowSubscriptionsClient.PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", ReferenceDataNowSubscriptionsClient.ACCEPT)
                        .withBody(MAPPER.writeValueAsString(PARITY_CASE.subscriptions()))));

        // 202 and nothing else: the contract declares one success status and the gateway refuses
        // every other 2xx, so a stub answering 200 would be testing the refusal instead.
        results.stubFor(post(urlEqualTo(ResultsCommandGateway.INFORMANT_REGISTER_PATH))
                .willReturn(aResponse().withStatus(202)));
    }

    // --- fixtures ----------------------------------------------------------------------------

    /** The key the producer publishes this hearing's payload under, dated form. */
    private static String cacheKey() {
        return "INT_" + HEARING_ID + '_' + HEARING_DAY + "_result_";
    }

    /**
     * The cache document, in the shape the producer writes it.
     *
     * <p>{@code {isReshare, hearingDay, sharedTime, hearing}} — the wrapper, not the hearing. Which
     * member the pipeline opens, and which {@code sharedTime} stamps the register, is exactly the
     * seam this suite is here to hold: the register carries the <em>payload's</em> shared time, and
     * the message's own is kept for the processed log and the request fingerprint.
     */
    private static String cacheDocument() {
        return """
                {"isReshare":false,"hearingDay":"%s","sharedTime":"%s","hearing":%s}
                """.formatted(
                        HEARING_DAY,
                        PARITY_CASE.sharedTime(),
                        MAPPER.writeValueAsString(PARITY_CASE.hearing()));
    }

    /**
     * The queue message: this suite's request, the fixture's hearing, and a named sharing user.
     *
     * <p>Its {@code sharedTime} is the fixture's too, so the message a real producer would have
     * published for this share is the message that is published.
     */
    private String messageBody() {
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
                """.formatted(requestId, HEARING_ID, HEARING_DAY, PARITY_CASE.sharedTime(),
                        SHARING_USER_ID);
    }

    /**
     * The commands Results received for this hearing, in the order they arrived.
     *
     * <p>Filtered by the hearing rather than taken wholesale. The emulator queue is shared, so a
     * neighbouring suite's message can reach this consumer; one would fetch nothing from a cache
     * that has nothing of its own in it and would never reach a POST, but an assertion that depends
     * on that reasoning holding is an assertion that breaks the day it stops holding.
     */
    private static List<LoggedRequest> commandsForThisHearing() {
        return results
                .findAll(postRequestedFor(
                        urlEqualTo(ResultsCommandGateway.INFORMANT_REGISTER_PATH)))
                .stream()
                .filter(request -> HEARING_ID.toString().equals(
                        MAPPER.readTree(request.getBodyAsString()).path("hearingId").stringValue()))
                .toList();
    }

    /** The per-authority rows this request left behind, in authority order. */
    private List<Output> outputRows() {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT prosecution_authority_id, status, request_digest
                          FROM processed_output
                         WHERE source = :source AND request_id = :requestId
                         ORDER BY prosecution_authority_id
                        """)
                .param("source", ProcessedLogTestSupport.SOURCE)
                .param("requestId", requestId)
                .query((rs, rowNumber) -> new Output(
                        rs.getString("prosecution_authority_id"),
                        rs.getString("status"),
                        rs.getString("request_digest")))
                .list();
    }

    /** The columns of {@code processed_output} this suite has anything to say about. */
    private record Output(String prosecutionAuthorityId, String status, String requestDigest) {
    }

    // --- the whole chain -------------------------------------------------------------------------

    @Test
    @DisplayName("one message in, three registers out, byte for byte what the function app sent")
    void should_turn_one_queue_message_into_one_results_command_per_authority() {
        final String messageId = ServiceTestSupport.publish(messageBody());

        await().atMost(COMPLETED_WITHIN).pollInterval(POLL).until(() ->
                ProcessedLogTestSupport.row(ProcessedLogTestSupport.SOURCE, requestId)
                        .filter(row -> RequestStatus.COMPLETED.name().equals(row.status()))
                        .isPresent());

        // --- the register the run recorded -------------------------------------------------------

        final ProcessedLogTestSupport.Row processed =
                ProcessedLogTestSupport.requireRow(ProcessedLogTestSupport.SOURCE, requestId);
        assertThat(processed.completionReason())
                .as("authorities were produced and submitted, which is a different row from the "
                        + "no-authorities completion the skeleton suite records")
                .isEqualTo(CompletionReason.AUTHORITIES_SUBMITTED.value());
        assertThat(processed.attempts()).isEqualTo(1);
        assertThat(processed.hearingId()).isEqualTo(HEARING_ID);
        assertThat(processed.failureReason()).isNull();
        assertThat(processed.claimOwner())
                .as("the claim is released by the completion, not left live for the lease")
                .isNull();

        // --- what Results actually received ------------------------------------------------------

        final List<LoggedRequest> commands = commandsForThisHearing();
        assertThat(commands)
                .as("one add-informant-register command per prosecuting authority the legacy "
                        + "produced a fragment for")
                .hasSize(EXPECTED_AUTHORITIES);

        final JsonNode expected = PARITY_CASE.expected();
        assertThat(expected.size())
                .as("the recorded oracle for %s is the three-document base", CASE_ID)
                .isEqualTo(EXPECTED_AUTHORITIES);

        for (int authority = 0; authority < EXPECTED_AUTHORITIES; authority++) {
            final LoggedRequest command = commands.get(authority);

            // The bodies are compared in the order they were POSTed against the order the oracle
            // recorded. Order is meaning here: which authority is the first fragment is decided by
            // the transformation and by nothing downstream, so a comparison that sorted would hide
            // exactly the regression this suite is placed to catch.
            JsonParity.assertMatches(
                    expected.get(authority),
                    MAPPER.readTree(command.getBodyAsString()),
                    CASE_ID + " document " + authority + " as Results received it");

            assertThat(command.getHeader("Content-Type"))
                    .as("the framework routes on the vendor media type, so it is not a formality")
                    .startsWith(ResultsCommandGateway.ADD_INFORMANT_REGISTER_MEDIA_TYPE);
            assertThat(command.getHeader(ResultsCommandGateway.IDENTITY_HEADER))
                    .as("the command is posted as the user the message named, not as the "
                            + "configured system identity")
                    .isEqualTo(SHARING_USER_ID);
        }

        // --- the read that decided who the register is addressed to -------------------------------

        referenceData.verify(moreThanOrExactly(1),
                getRequestedFor(urlPathEqualTo(ReferenceDataNowSubscriptionsClient.PATH))
                        .withQueryParam(ReferenceDataNowSubscriptionsClient.ON,
                                equalTo(PARITY_CASE.recordedRefdataQueryDate()))
                        .withHeader("Accept",
                                equalTo(ReferenceDataNowSubscriptionsClient.ACCEPT))
                        .withHeader(ReferenceDataNowSubscriptionsClient.IDENTITY_HEADER,
                                equalTo(SHARING_USER_ID)));

        // --- the per-authority evidence -----------------------------------------------------------

        final List<Output> outputs = outputRows();
        assertThat(outputs)
                .as("one processed_output row per authority, which is what makes a redelivery safe "
                        + "against a command that is not idempotent")
                .hasSize(EXPECTED_AUTHORITIES);
        assertThat(outputs).allSatisfy(output -> {
            assertThat(output.status()).isEqualTo("POSTED");
            assertThat(output.requestDigest())
                    .as("the digest of the bytes that were sent, kept for reconciliation")
                    .isNotBlank();
        });
        assertThat(outputs).extracting(Output::prosecutionAuthorityId)
                .containsExactlyInAnyOrderElementsOf(
                        expected.valueStream()
                                .map(document -> document.get("prosecutionAuthorityId").stringValue())
                                .toList());

        // --- and the delivery itself ---------------------------------------------------------------

        await().atMost(COMPLETED_WITHIN).pollInterval(POLL).until(() ->
                ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.NONE).isEmpty());
        assertThat(ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE))
                .as("a hearing that produced its registers is completed, never parked")
                .isEmpty();
    }
}
