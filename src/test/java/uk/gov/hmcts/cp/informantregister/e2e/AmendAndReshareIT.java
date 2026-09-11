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
 * The amend-and-reshare flow, end to end: a hearing is resulted and shared, then a result on one
 * offence is amended and the hearing is reshared. Both shares must produce the correct outbound
 * registers — the first carrying the original result, the second carrying the amendment.
 *
 * <p>This test fills a gap the existing suites leave open:
 * <ul>
 *   <li>{@code OutboundRegisterPipelineIT} proves the single-share pipeline — one message in,
 *       golden-parity documents out — but never changes the payload between shares.</li>
 *   <li>The {@code mut__*__re-share-duplicate__*} parity tests prove idempotency — same bytes in
 *       twice, identical documents out — but a duplicate delivery is not an amendment.</li>
 * </ul>
 *
 * <p>Neither can catch a fault where the service caches or reuses a stale transformation result.
 * Here the Redis cache is updated between shares with a hearing whose judicial result on one
 * offence has changed, and the second share's outbound POSTs are asserted to carry the amended
 * result text, not the original. Two independent requests (different {@code requestId}, same
 * {@code hearingId}, later {@code sharedTime}) reach the pipeline, each fetching the hearing
 * payload that is current in the cache at its own processing time.
 *
 * <p>The hearing is the {@code base__case-and-application} parity fixture, modified
 * programmatically: the first prosecution case's first defendant's first offence has its surviving
 * judicial result changed to "Absolute discharge" for the initial share and then to "Conditional
 * discharge" for the reshare. The modification touches {@code label}, {@code resultText}, and
 * injects a {@code majorCreditorCode} ({@code CDE04}) on TFL's
 * {@code prosecutionCaseIdentifier} so that the subscription matcher finds a match and the
 * outbound document carries a populated {@code recipients} array. Every filter-relevant attribute
 * ({@code isAvailableForCourtExtract}, {@code publishedForNows}, {@code orderedDate}) is preserved,
 * so the authority count remains identical to the base case.
 *
 * <p>Assertions are structural rather than golden-comparison: specific result texts are checked on
 * the modified offence, and the unmodified offences are verified to be unchanged. This is deliberate
 * — the hearing was altered from the oracle's recording, so byte-level parity is not owed.
 *
 * <p><strong>RQA-AD-07</strong> — Results QA acceptance scenario: amend and reshare with informant
 * register verification.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("RQA-AD-07")
@DisplayName("RQA-AD-07: amend-and-reshare reflects the amended result in the register")
class AmendAndReshareIT {

    /** The base parity case whose hearing we modify for each share. */
    private static final ParityCase BASE_CASE = ParityCase.load("recorded", "base__case-and-application");

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    private static final UUID HEARING_ID =
            UUID.fromString(BASE_CASE.hearing().get("id").stringValue());

    private static final String HEARING_DAY = "2021-03-11";

    /** The user the message says shared the results — every outbound call must be made as them. */
    private static final String SHARING_USER_ID = "3d5f7a91-2c4e-4b86-9f10-7ac5be3d2081";

    /** Deliberately different from the sharing user so the identity assertion catches a mis-resolution. */
    private static final String SYSTEM_USER_ID = "00000000-0000-4000-8000-0000000000ff";

    /** The sharedTime for the initial share — the base case's own. */
    private static final String INITIAL_SHARED_TIME = BASE_CASE.sharedTime();

    /**
     * The sharedTime for the reshare — later on the same day so the reference-data query date does
     * not change and the same subscriptions stub answers both shares.
     */
    private static final String AMENDED_SHARED_TIME = "2021-03-11T23:30:00.000Z";

    private static final String INITIAL_RESULT_TEXT = "Absolute discharge";
    private static final String AMENDED_RESULT_TEXT = "Conditional discharge";

    /**
     * The result text the second defendant's offence carries in the unmodified fixture — asserted
     * on both shares to prove the amendment did not disturb it.
     */
    private static final String UNMODIFIED_RESULT_TEXT = "Imprisonment court order";

    /**
     * The authority code of the prosecution case whose offence is modified: the first prosecution
     * case's prosecuting authority in the base fixture is TFL.
     */
    private static final String MODIFIED_AUTHORITY_CODE = "TFL";

    /**
     * The informant code injected as the TFL prosecution case's {@code majorCreditorCode}. This
     * matches two subscriptions in the base fixture (Arun District Council and Cambridge County
     * Council — Educational Welfare), both with {@code forDistribution: true},
     * {@code emailDelivery: true}, and a valid {@code recipient.emailAddress1}, so
     * {@code RecipientMapper} will produce two recipients in the outbound document.
     *
     * <p>The base fixture's hearing carries <em>no</em> {@code majorCreditorCode} on TFL or TVL
     * (and DERPF's {@code PF30} matches no subscription), so without this injection every document
     * would have {@code recipients} absent — which is the scenario {@code NoMatchingSubscriptionIT}
     * already covers. Here we deliberately create the match to verify the recipients are present.
     */
    private static final String MATCHING_INFORMANT_CODE = "CDE04";

    private static final int EXPECTED_AUTHORITIES = 3;

    private static final Duration COMPLETED_WITHIN = Duration.ofSeconds(90);
    private static final Duration POLL = Duration.ofSeconds(1);

    private static WireMockServer results;
    private static WireMockServer referenceData;
    private static RedisClient cacheClient;
    private static StatefulRedisConnection<String, String> cache;

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
    void resetStubs() {
        results.resetAll();
        referenceData.resetAll();
    }

    // --- fixture helpers -------------------------------------------------------------------------

    /**
     * Returns a deep copy of the base hearing with two modifications:
     * <ol>
     *   <li>The first prosecution case's first defendant's first surviving judicial result's text
     *       is changed to the given value ({@code label} and {@code resultText}).</li>
     *   <li>The first prosecution case's {@code prosecutionCaseIdentifier.majorCreditorCode} is
     *       set to {@link #MATCHING_INFORMANT_CODE} so that the subscription matcher can find a
     *       match and {@code RecipientMapper} produces a populated {@code recipients} array.</li>
     * </ol>
     *
     * <p>Every filter-relevant flag ({@code isAvailableForCourtExtract}, {@code publishedForNows},
     * {@code orderedDate}) is preserved, so the court-extract filter produces the same authorities
     * and the same offence structure as the base case.
     */
    private static JsonNode hearingWithResult(final String resultText) {
        final JsonNode hearing = BASE_CASE.hearing().deepCopy();

        // Inject majorCreditorCode so TFL matches subscriptions with informantCode CDE04.
        final ObjectNode caseIdentifier = (ObjectNode) hearing
                .path("prosecutionCases").get(0)
                .path("prosecutionCaseIdentifier");
        caseIdentifier.put("majorCreditorCode", MATCHING_INFORMANT_CODE);

        // Set the surviving judicial result to the given text.
        final ObjectNode targetResult = (ObjectNode) hearing
                .path("prosecutionCases").get(0)
                .path("defendants").get(0)
                .path("offences").get(0)
                .path("judicialResults").get(0);
        targetResult.put("label", resultText);
        targetResult.put("resultText", resultText);
        return hearing;
    }

    private static String cacheDocument(
            final JsonNode hearing, final String sharedTime, final boolean isReshare) {
        return """
                {"isReshare":%s,"hearingDay":"%s","sharedTime":"%s","hearing":%s}\
                """.formatted(
                        isReshare,
                        HEARING_DAY,
                        sharedTime,
                        MAPPER.writeValueAsString(hearing));
    }

    private static String cacheKey() {
        return "INT_" + HEARING_ID + '_' + HEARING_DAY + "_result_";
    }

    private static String messageBody(final UUID requestId, final String sharedTime) {
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

    private void stubApis() {
        results.stubFor(post(urlEqualTo(ResultsCommandGateway.INFORMANT_REGISTER_PATH))
                .willReturn(aResponse().withStatus(202)));
        referenceData.stubFor(get(urlPathEqualTo(ReferenceDataNowSubscriptionsClient.PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", ReferenceDataNowSubscriptionsClient.ACCEPT)
                        .withBody(MAPPER.writeValueAsString(BASE_CASE.subscriptions()))));
    }

    /**
     * The commands Results received for this hearing since the last WireMock reset.
     */
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
     * Waits for the given request to reach COMPLETED in the processed log.
     */
    private static void awaitCompleted(final UUID requestId) {
        await().atMost(COMPLETED_WITHIN).pollInterval(POLL).until(() ->
                ProcessedLogTestSupport.row(ProcessedLogTestSupport.SOURCE, requestId)
                        .filter(row -> RequestStatus.COMPLETED.name().equals(row.status()))
                        .isPresent());
    }

    // --- assertions on outbound bodies -----------------------------------------------------------

    /**
     * Finds the outbound document for the given authority code.
     */
    private static JsonNode bodyForAuthority(
            final List<LoggedRequest> commands, final String authorityCode) {
        return commands.stream()
                .map(request -> MAPPER.readTree(request.getBodyAsString()))
                .filter(body -> authorityCode.equals(body.path("prosecutionAuthorityCode").stringValue()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no outbound document for authority " + authorityCode));
    }

    /**
     * Collects every {@code resultText} from every offence in the document, across all defendants
     * and all court sessions. A flat list that can be searched for the presence or absence of a
     * specific result.
     */
    private static List<String> allResultTexts(final JsonNode document) {
        final List<String> texts = new ArrayList<>();
        for (final JsonNode session : iterable(document.path("hearingVenue").path("courtSessions"))) {
            for (final JsonNode defendant : iterable(session.path("defendants"))) {
                for (final JsonNode caseOrApp : iterable(defendant.path("prosecutionCasesOrApplications"))) {
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
     * Makes a possibly-missing {@code JsonNode} iterable without a null check at every level.
     */
    private static Iterable<JsonNode> iterable(final JsonNode node) {
        return node.isMissingNode() || node.isNull() ? List.of() : node;
    }

    // --- the test ---------------------------------------------------------------------------------

    @Test
    @Tag("RQA-AD-07")
    @DisplayName("RQA-AD-07: initial share carries Absolute Discharge; reshare after amendment carries the new result")
    void amend_and_reshare_should_reflect_amended_result_in_outbound_register() {

        // ===========================================================================================
        // Phase 1: initial share — the offence is resulted with Absolute Discharge
        // ===========================================================================================

        stubApis();
        cache.sync().set(cacheKey(),
                cacheDocument(hearingWithResult(INITIAL_RESULT_TEXT), INITIAL_SHARED_TIME, false));

        final UUID firstRequestId = UUID.randomUUID();
        ServiceTestSupport.publish(messageBody(firstRequestId, INITIAL_SHARED_TIME));
        awaitCompleted(firstRequestId);

        final List<LoggedRequest> initialCommands = commandsForThisHearing();
        assertThat(initialCommands)
                .as("all three authorities receive their register on the initial share")
                .hasSize(EXPECTED_AUTHORITIES);

        // The modified authority (TFL) must carry the injected Absolute Discharge.
        final JsonNode initialTfl = bodyForAuthority(initialCommands, MODIFIED_AUTHORITY_CODE);
        final List<String> initialTflResults = allResultTexts(initialTfl);
        assertThat(initialTflResults)
                .as("the first defendant's offence result should be the injected Absolute Discharge")
                .contains(INITIAL_RESULT_TEXT);
        assertThat(initialTflResults)
                .as("the second defendant's unmodified result is still present")
                .anyMatch(text -> text.contains("Imprisonment"));

        // The TFL document must carry recipients — the injected majorCreditorCode CDE04 matches
        // two subscriptions (Arun District Council and Cambridge County Council), both with
        // forDistribution, emailDelivery, and a valid recipient.emailAddress1.
        assertThat(initialTfl.has("recipients"))
                .as("TFL has majorCreditorCode CDE04 which matches subscriptions — recipients "
                        + "must be present")
                .isTrue();
        final JsonNode initialRecipients = initialTfl.get("recipients");
        assertThat(initialRecipients.size())
                .as("two subscriptions carry informantCode CDE04 in the fixture")
                .isEqualTo(2);
        assertThat(initialRecipients.get(0).path("emailAddress1").stringValue())
                .as("the first matched subscription's recipient email")
                .isNotBlank();

        // Quick check that the processed log recorded the right outcome.
        final ProcessedLogTestSupport.Row firstRow =
                ProcessedLogTestSupport.requireRow(ProcessedLogTestSupport.SOURCE, firstRequestId);
        assertThat(firstRow.completionReason())
                .isEqualTo(CompletionReason.AUTHORITIES_SUBMITTED.value());
        assertThat(firstRow.hearingId()).isEqualTo(HEARING_ID);

        // ===========================================================================================
        // Phase 2: amend the result and reshare
        // ===========================================================================================

        // Reset WireMock so the second share's captured traffic is isolated from the first.
        results.resetAll();
        referenceData.resetAll();
        stubApis();

        // The hearing is now amended: the same offence carries Conditional Discharge instead.
        cache.sync().set(cacheKey(),
                cacheDocument(hearingWithResult(AMENDED_RESULT_TEXT), AMENDED_SHARED_TIME, true));

        final UUID secondRequestId = UUID.randomUUID();
        ServiceTestSupport.publish(messageBody(secondRequestId, AMENDED_SHARED_TIME));
        awaitCompleted(secondRequestId);

        final List<LoggedRequest> reshareCommands = commandsForThisHearing();
        assertThat(reshareCommands)
                .as("all three authorities receive their register on the reshare too")
                .hasSize(EXPECTED_AUTHORITIES);

        // The modified authority now carries the amended result, not the original.
        final JsonNode reshareTfl = bodyForAuthority(reshareCommands, MODIFIED_AUTHORITY_CODE);
        final List<String> reshareTflResults = allResultTexts(reshareTfl);
        assertThat(reshareTflResults)
                .as("the amended result must appear in the reshare's outbound document")
                .contains(AMENDED_RESULT_TEXT);
        assertThat(reshareTflResults)
                .as("the original result must NOT appear — the pipeline must have read the updated payload")
                .doesNotContain(INITIAL_RESULT_TEXT);
        assertThat(reshareTflResults)
                .as("the second defendant's unmodified result survives the amendment untouched")
                .anyMatch(text -> text.contains("Imprisonment"));

        // The reshare must also carry the same recipients — the majorCreditorCode injection and
        // subscriptions are identical between shares, so the match is the same.
        assertThat(reshareTfl.has("recipients"))
                .as("the reshare's TFL document must also carry recipients")
                .isTrue();
        final JsonNode reshareRecipients = reshareTfl.get("recipients");
        assertThat(reshareRecipients.size())
                .as("same two CDE04 subscriptions match on the reshare")
                .isEqualTo(2);
        assertThat(reshareRecipients.get(0).path("emailAddress1").stringValue())
                .as("recipient email is still present on the reshare")
                .isNotBlank();

        // Both shares are independent COMPLETED requests in the processed log.
        final ProcessedLogTestSupport.Row secondRow =
                ProcessedLogTestSupport.requireRow(ProcessedLogTestSupport.SOURCE, secondRequestId);
        assertThat(secondRow.completionReason())
                .isEqualTo(CompletionReason.AUTHORITIES_SUBMITTED.value());
        assertThat(secondRow.hearingId()).isEqualTo(HEARING_ID);
        assertThat(secondRow.attempts()).isEqualTo(1);

        // The identity carried on every POST must be the sharing user, not the system identity.
        for (final LoggedRequest command : reshareCommands) {
            assertThat(command.getHeader(ResultsCommandGateway.IDENTITY_HEADER))
                    .as("the reshare is attributed to the user who shared, not the system identity")
                    .isEqualTo(SHARING_USER_ID);
        }
    }
}
