package uk.gov.hmcts.cp.informantregister.e2e;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.informantregister.adapter.refdata.ReferenceDataNowSubscriptionsClient;
import uk.gov.hmcts.cp.informantregister.adapter.results.ResultsCommandGateway;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.RequestStatus;
import uk.gov.hmcts.cp.informantregister.support.ParityCase;
import uk.gov.hmcts.cp.informantregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.RedisTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ServiceTestSupport;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A hearing with two prosecution cases — TFL and TVL — each with its own defendant, but both
 * defendants share the same {@code masterDefendantId}. This is the cross-case identity: different
 * {@code id} (case-specific), same real person.
 *
 * <p>The pipeline groups defendants by {@code masterDefendantId} globally (not per authority) via
 * {@code DefendantContextBuilder}, so the shared identity produces one merged defendant context
 * carrying case IDs from both prosecution cases. {@code RegisterBuilder} then includes this merged
 * defendant in both authority fragments. The test verifies that despite the shared identity:
 * <ul>
 *   <li>Two outbound documents are produced — one per prosecution authority.</li>
 *   <li>Each document carries its own case reference and "Absolute discharge" result.</li>
 *   <li>The shared defendant appears in both documents.</li>
 * </ul>
 *
 * <p>The hearing is derived from the {@code base__case-and-application} parity fixture, modified
 * programmatically:
 * <ul>
 *   <li>TFL's second defendant is removed (one defendant per case).</li>
 *   <li>Court applications are removed (prosecution cases only).</li>
 *   <li>TVL's defendant's {@code masterDefendantId} is set to match TFL's — creating the shared
 *       identity the test is designed to exercise.</li>
 *   <li>Both offences' first surviving judicial result is set to "Absolute discharge".</li>
 * </ul>
 *
 * <p>Tracked as <strong>RQA-AD-05</strong> in the Results QA Absolute Discharge test matrix.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("RQA-AD-05")
@DisplayName("RQA-AD-05: shared master defendant across two authorities — per-authority isolation")
class SharedMasterDefendantIT {

    private static final ParityCase BASE_CASE =
            ParityCase.load("recorded", "base__case-and-application");

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    private static final UUID HEARING_ID =
            UUID.fromString(BASE_CASE.hearing().get("id").stringValue());

    private static final String HEARING_DAY = "2021-03-11";

    private static final String SHARING_USER_ID = "3d5f7a91-2c4e-4b86-9f10-7ac5be3d2081";

    /** Deliberately different from the sharing user — catches mis-resolution of the caller identity. */
    private static final String SYSTEM_USER_ID = "00000000-0000-4000-8000-0000000000ff";

    private static final String RESULT_TEXT = "Absolute discharge";

    private static final String TFL_AUTHORITY_CODE = "TFL";
    private static final String TVL_AUTHORITY_CODE = "TVL";

    /**
     * The case reference that appears in TFL's outbound document, sourced from the first
     * prosecution case's {@code prosecutionCaseIdentifier.prosecutionAuthorityReference}.
     */
    private static final String TFL_CASE_REFERENCE = "TFL4359536";

    /**
     * The case reference that appears in TVL's outbound document, sourced from the second
     * prosecution case's {@code prosecutionCaseIdentifier.prosecutionAuthorityReference}.
     */
    private static final String TVL_CASE_REFERENCE = "TVL298320922";

    /**
     * TFL's first defendant's {@code masterDefendantId}, which we also assign to TVL's defendant
     * to create the shared identity the test exercises.
     */
    private static final String SHARED_MASTER_DEFENDANT_ID =
            BASE_CASE.hearing().path("prosecutionCases").get(0)
                    .path("defendants").get(0)
                    .path("masterDefendantId").stringValue();

    private static final Duration COMPLETED_WITHIN = Duration.ofSeconds(90);
    private static final Duration POLL = Duration.ofSeconds(1);

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
    void seedTheWorld() {
        results.resetAll();
        referenceData.resetAll();

        cache.sync().set(cacheKey(), cacheDocument());

        referenceData.stubFor(get(urlPathEqualTo(ReferenceDataNowSubscriptionsClient.PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", ReferenceDataNowSubscriptionsClient.ACCEPT)
                        .withBody(MAPPER.writeValueAsString(BASE_CASE.subscriptions()))));

        results.stubFor(post(urlEqualTo(ResultsCommandGateway.INFORMANT_REGISTER_PATH))
                .willReturn(aResponse().withStatus(202)));
    }

    // --- fixture helpers -------------------------------------------------------------------------

    /**
     * Returns a deep copy of the base hearing with:
     * <ol>
     *   <li>TFL's second defendant removed — one defendant per case.</li>
     *   <li>Court applications removed — prosecution cases only.</li>
     *   <li>TVL's defendant given the same {@code masterDefendantId} as TFL's.</li>
     *   <li>Both offences' first judicial result set to "Absolute discharge".</li>
     * </ol>
     */
    private static JsonNode sharedDefendantHearing() {
        final ObjectNode hearing = (ObjectNode) BASE_CASE.hearing().deepCopy();

        // Keep only the first defendant in the TFL case.
        final ArrayNode tflDefendants =
                (ArrayNode) hearing.path("prosecutionCases").get(0).get("defendants");
        while (tflDefendants.size() > 1) {
            tflDefendants.remove(tflDefendants.size() - 1);
        }

        // Remove court applications — this test exercises prosecution cases only.
        hearing.set("courtApplications", hearing.arrayNode());

        // Give TVL's defendant the same masterDefendantId as TFL's — they are now the same person
        // appearing in two different prosecution cases under two different authorities.
        final ObjectNode tvlDefendant = (ObjectNode) hearing
                .path("prosecutionCases").get(1)
                .path("defendants").get(0);
        tvlDefendant.put("masterDefendantId", SHARED_MASTER_DEFENDANT_ID);

        // Set TFL's offence result to Absolute Discharge.
        final ObjectNode tflResult = (ObjectNode) hearing
                .path("prosecutionCases").get(0)
                .path("defendants").get(0)
                .path("offences").get(0)
                .path("judicialResults").get(0);
        tflResult.put("label", RESULT_TEXT);
        tflResult.put("resultText", RESULT_TEXT);

        // Set TVL's offence result to Absolute Discharge.
        final ObjectNode tvlResult = (ObjectNode) hearing
                .path("prosecutionCases").get(1)
                .path("defendants").get(0)
                .path("offences").get(0)
                .path("judicialResults").get(0);
        tvlResult.put("label", RESULT_TEXT);
        tvlResult.put("resultText", RESULT_TEXT);

        return hearing;
    }

    private static String cacheKey() {
        return "INT_" + HEARING_ID + '_' + HEARING_DAY + "_result_";
    }

    private static String cacheDocument() {
        return """
                {"isReshare":false,"hearingDay":"%s","sharedTime":"%s","hearing":%s}\
                """.formatted(
                        HEARING_DAY,
                        BASE_CASE.sharedTime(),
                        MAPPER.writeValueAsString(sharedDefendantHearing()));
    }

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
                """.formatted(requestId, HEARING_ID, HEARING_DAY, BASE_CASE.sharedTime(),
                        SHARING_USER_ID);
    }

    private static List<LoggedRequest> commandsForThisHearing() {
        return results
                .findAll(postRequestedFor(
                        urlEqualTo(ResultsCommandGateway.INFORMANT_REGISTER_PATH)))
                .stream()
                .filter(request -> HEARING_ID.toString().equals(
                        MAPPER.readTree(request.getBodyAsString())
                                .path("hearingId").stringValue()))
                .toList();
    }

    /**
     * Finds the outbound document for the given authority code.
     */
    private static JsonNode bodyForAuthority(
            final List<LoggedRequest> commands, final String authorityCode) {
        return commands.stream()
                .map(request -> MAPPER.readTree(request.getBodyAsString()))
                .filter(body -> authorityCode.equals(
                        body.path("prosecutionAuthorityCode").stringValue()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no outbound document for authority " + authorityCode));
    }

    /**
     * Collects every {@code caseOrApplicationReference} from the document, across all court
     * sessions, defendants and their cases.
     */
    private static List<String> allCaseReferences(final JsonNode document) {
        final List<String> refs = new ArrayList<>();
        for (final JsonNode session : iterable(document.path("hearingVenue").path("courtSessions"))) {
            for (final JsonNode defendant : iterable(session.path("defendants"))) {
                for (final JsonNode caseOrApp
                        : iterable(defendant.path("prosecutionCasesOrApplications"))) {
                    final String ref =
                            caseOrApp.path("caseOrApplicationReference").stringValue();
                    if (ref != null) {
                        refs.add(ref);
                    }
                }
            }
        }
        return refs;
    }

    /**
     * Collects every {@code resultText} from offence results that appear under a specific
     * {@code caseOrApplicationReference} entry. This is the per-case isolation assertion: it proves
     * a particular case's results are present without relying on document-wide flattening.
     */
    private static List<String> resultTextsForCase(
            final JsonNode document, final String caseReference) {
        final List<String> texts = new ArrayList<>();
        for (final JsonNode session : iterable(document.path("hearingVenue").path("courtSessions"))) {
            for (final JsonNode defendant : iterable(session.path("defendants"))) {
                for (final JsonNode caseOrApp
                        : iterable(defendant.path("prosecutionCasesOrApplications"))) {
                    if (!caseReference.equals(
                            caseOrApp.path("caseOrApplicationReference").stringValue())) {
                        continue;
                    }
                    for (final JsonNode offence : iterable(caseOrApp.path("offences"))) {
                        for (final JsonNode result : iterable(offence.path("offenceResults"))) {
                            final String text = result.path("resultText").stringValue();
                            if (text != null) {
                                texts.add(text);
                            }
                        }
                    }
                }
            }
        }
        return texts;
    }

    /**
     * Counts the total defendants across all court sessions in the document.
     */
    private static int countDefendants(final JsonNode document) {
        int count = 0;
        for (final JsonNode session : iterable(document.path("hearingVenue").path("courtSessions"))) {
            final JsonNode defendants = session.path("defendants");
            if (!defendants.isMissingNode()) {
                count += defendants.size();
            }
        }
        return count;
    }

    /**
     * Makes a possibly-missing {@code JsonNode} iterable without a null check at every level.
     */
    private static Iterable<JsonNode> iterable(final JsonNode node) {
        return node.isMissingNode() || node.isNull() ? List.of() : node;
    }

    // --- the test ---------------------------------------------------------------------------------

    @Test
    @Tag("RQA-AD-05")
    @DisplayName("RQA-AD-05: two cases sharing a master defendant — one record per authority, "
            + "each with its own case and Absolute Discharge result")
    void shared_master_defendant_should_produce_one_record_per_authority_with_correct_results() {

        ServiceTestSupport.publish(messageBody());

        // --- wait for the request to complete -------------------------------------------------------

        await().atMost(COMPLETED_WITHIN).pollInterval(POLL).until(() ->
                ProcessedLogTestSupport.row(ProcessedLogTestSupport.SOURCE, requestId)
                        .filter(row -> RequestStatus.COMPLETED.name().equals(row.status()))
                        .isPresent());

        // --- the processed log records the right outcome --------------------------------------------

        final ProcessedLogTestSupport.Row processed =
                ProcessedLogTestSupport.requireRow(ProcessedLogTestSupport.SOURCE, requestId);
        assertThat(processed.completionReason())
                .as("two prosecution authorities produce a normal submission")
                .isEqualTo(CompletionReason.AUTHORITIES_SUBMITTED.value());
        assertThat(processed.hearingId()).isEqualTo(HEARING_ID);
        assertThat(processed.attempts()).isEqualTo(1);
        assertThat(processed.failureReason()).isNull();

        // --- one record per authority ---------------------------------------------------------------

        final List<LoggedRequest> commands = commandsForThisHearing();
        assertThat(commands)
                .as("two prosecution cases (TFL, TVL) — one POST per authority")
                .hasSize(2);

        // --- TFL's document -------------------------------------------------------------------------

        final JsonNode tflBody = bodyForAuthority(commands, TFL_AUTHORITY_CODE);

        assertThat(tflBody.path("prosecutionAuthorityCode").stringValue())
                .isEqualTo(TFL_AUTHORITY_CODE);

        assertThat(allCaseReferences(tflBody))
                .as("TFL's document must carry TFL's case reference")
                .contains(TFL_CASE_REFERENCE);

        assertThat(resultTextsForCase(tflBody, TFL_CASE_REFERENCE))
                .as("TFL's case entry must carry the Absolute Discharge result we injected")
                .contains(RESULT_TEXT);

        assertThat(countDefendants(tflBody))
                .as("TFL's document has the shared defendant")
                .isGreaterThanOrEqualTo(1);

        // --- TVL's document -------------------------------------------------------------------------

        final JsonNode tvlBody = bodyForAuthority(commands, TVL_AUTHORITY_CODE);

        assertThat(tvlBody.path("prosecutionAuthorityCode").stringValue())
                .isEqualTo(TVL_AUTHORITY_CODE);

        assertThat(allCaseReferences(tvlBody))
                .as("TVL's document must carry TVL's case reference")
                .contains(TVL_CASE_REFERENCE);

        assertThat(resultTextsForCase(tvlBody, TVL_CASE_REFERENCE))
                .as("TVL's case entry must carry the Absolute Discharge result we injected")
                .contains(RESULT_TEXT);

        assertThat(countDefendants(tvlBody))
                .as("TVL's document has the shared defendant")
                .isGreaterThanOrEqualTo(1);

        // --- the identity on every POST -------------------------------------------------------------

        for (final LoggedRequest command : commands) {
            assertThat(command.getHeader(ResultsCommandGateway.IDENTITY_HEADER))
                    .as("every POST is attributed to the sharing user, not the system identity")
                    .isEqualTo(SHARING_USER_ID);
        }
    }
}
