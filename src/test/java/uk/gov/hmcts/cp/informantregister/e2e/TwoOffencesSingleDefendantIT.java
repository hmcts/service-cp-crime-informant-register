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
 * A hearing with one prosecution case (TFL), one defendant and two offences, both resulted with
 * "Absolute discharge". The register is shared and the outbound payload is asserted to contain
 * both offences with their results. The {@code recipients} object must be absent because the
 * subscriptions' informant codes (CDE02–CDE05) do not match TFL's prosecution authority code.
 *
 * <p>This complements {@code TwoDefendantsSingleCaseIT} (RQA-AD-04) which proves the pipeline
 * correctly renders multiple <em>defendants</em>. This test proves it correctly renders multiple
 * <em>offences</em> on a single defendant — a different axis of multiplicity.
 *
 * <p>The hearing is derived from the {@code base__case-and-application} parity fixture. The TFL
 * case naturally has two defendants, each with one offence. The modification:
 * <ul>
 *   <li>The second prosecution case (TVL) and court applications are removed.</li>
 *   <li>Defendant 2's offence is moved onto defendant 1's offences array — giving one defendant
 *       two structurally distinct offences (different offence IDs, codes, and judicial results).</li>
 *   <li>Defendant 2 is then removed.</li>
 *   <li>Both offences' first surviving judicial result is set to "Absolute discharge".</li>
 * </ul>
 *
 * <p>The test asserts:
 * <ol>
 *   <li>The pipeline completes successfully with one authority submitted.</li>
 *   <li>Exactly one POST — one case, one authority.</li>
 *   <li>The outbound document contains one defendant with two offences, each carrying "Absolute
 *       discharge".</li>
 *   <li>The {@code recipients} key is absent — no subscription match.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("RQA-AD-03: one case, one defendant, two offences with Absolute Discharge — "
        + "both offences in payload, no recipients")
class TwoOffencesSingleDefendantIT {

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

    private static final String AUTHORITY_CODE = "TFL";

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
     * Returns a deep copy of the base hearing trimmed to one prosecution case (TFL), one defendant
     * carrying two offences, both resulted with "Absolute discharge".
     *
     * <p>The base TFL case has two defendants, each with one offence. This method:
     * <ol>
     *   <li>Removes the second prosecution case (TVL) and court applications.</li>
     *   <li>Copies defendant 2's offence onto defendant 1's offences array — giving one defendant
     *       two structurally distinct offences.</li>
     *   <li>Removes defendant 2.</li>
     *   <li>Sets both offences' first judicial result to "Absolute discharge".</li>
     * </ol>
     */
    private static JsonNode twoOffenceHearing() {
        final ObjectNode hearing = (ObjectNode) BASE_CASE.hearing().deepCopy();

        // Keep only the first prosecution case (TFL).
        final ArrayNode cases = (ArrayNode) hearing.get("prosecutionCases");
        while (cases.size() > 1) {
            cases.remove(cases.size() - 1);
        }

        // Remove court applications.
        hearing.set("courtApplications", hearing.arrayNode());

        final ArrayNode defendants = (ArrayNode) cases.get(0).get("defendants");

        // Move defendant 2's offence onto defendant 1's offences array.
        final JsonNode secondDefendantOffence = defendants.get(1)
                .path("offences").get(0).deepCopy();
        final ArrayNode firstDefendantOffences = (ArrayNode) defendants.get(0).get("offences");
        firstDefendantOffences.add(secondDefendantOffence);

        // Remove defendant 2 — one defendant remains with two offences.
        defendants.remove(1);

        // Set both offences' first judicial result to Absolute Discharge.
        final ObjectNode firstOffenceResult = (ObjectNode) firstDefendantOffences.get(0)
                .path("judicialResults").get(0);
        firstOffenceResult.put("label", RESULT_TEXT);
        firstOffenceResult.put("resultText", RESULT_TEXT);

        final ObjectNode secondOffenceResult = (ObjectNode) firstDefendantOffences.get(1)
                .path("judicialResults").get(0);
        secondOffenceResult.put("label", RESULT_TEXT);
        secondOffenceResult.put("resultText", RESULT_TEXT);

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
                        MAPPER.writeValueAsString(twoOffenceHearing()));
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
     * Collects every {@code resultText} from offence results across all court sessions,
     * defendants and offences in the document.
     */
    private static List<String> allResultTexts(final JsonNode document) {
        final List<String> texts = new ArrayList<>();
        for (final JsonNode session : iterable(document.path("hearingVenue").path("courtSessions"))) {
            for (final JsonNode defendant : iterable(session.path("defendants"))) {
                for (final JsonNode caseOrApp
                        : iterable(defendant.path("prosecutionCasesOrApplications"))) {
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
     * Counts offences that carry at least one offenceResult across the entire document.
     */
    private static int countOffencesWithResults(final JsonNode document) {
        int count = 0;
        for (final JsonNode session : iterable(document.path("hearingVenue").path("courtSessions"))) {
            for (final JsonNode defendant : iterable(session.path("defendants"))) {
                for (final JsonNode caseOrApp
                        : iterable(defendant.path("prosecutionCasesOrApplications"))) {
                    for (final JsonNode offence : iterable(caseOrApp.path("offences"))) {
                        final JsonNode offenceResults = offence.path("offenceResults");
                        if (!offenceResults.isMissingNode() && !offenceResults.isEmpty()) {
                            count++;
                        }
                    }
                }
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
    @DisplayName("RQA-AD-03: one case, one defendant, two offences with Absolute Discharge — "
            + "payload has both offences with results, recipients absent")
    void two_offences_absolute_discharge_should_produce_register_with_both_and_no_recipients() {

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
                .as("one prosecution case, one authority — normal submission")
                .isEqualTo(CompletionReason.AUTHORITIES_SUBMITTED.value());
        assertThat(processed.hearingId()).isEqualTo(HEARING_ID);
        assertThat(processed.attempts()).isEqualTo(1);
        assertThat(processed.failureReason()).isNull();

        // --- exactly one command for the single authority -------------------------------------------

        final List<LoggedRequest> commands = commandsForThisHearing();
        assertThat(commands)
                .as("one prosecution case means one authority, one POST")
                .hasSize(1);

        final JsonNode body = MAPPER.readTree(commands.getFirst().getBodyAsString());

        // --- authority identity ----------------------------------------------------------------------

        assertThat(body.path("prosecutionAuthorityCode").stringValue())
                .as("the single register is for TFL")
                .isEqualTo(AUTHORITY_CODE);

        // --- one defendant --------------------------------------------------------------------------

        assertThat(countDefendants(body))
                .as("one defendant after merging the second defendant's offence and removing them")
                .isEqualTo(1);

        // --- both offences carry results ------------------------------------------------------------

        assertThat(countOffencesWithResults(body))
                .as("the single defendant has two offences — both must carry offenceResult entries")
                .isGreaterThanOrEqualTo(2);

        // --- Absolute Discharge appears for both offences -------------------------------------------

        final List<String> resultTexts = allResultTexts(body);
        assertThat(resultTexts)
                .as("the payload must contain the Absolute Discharge results we injected on both "
                        + "offences")
                .filteredOn(RESULT_TEXT::equals)
                .hasSizeGreaterThanOrEqualTo(2);

        // --- recipients must be absent ---------------------------------------------------------------

        assertThat(body.has("recipients"))
                .as("no NOW subscription matches the prosecution authority code %s — the "
                        + "subscriptions carry informant codes CDE02–CDE05, none of which is %s — "
                        + "so RecipientMapper returns null and @JsonInclude(NON_NULL) omits the key",
                        AUTHORITY_CODE, AUTHORITY_CODE)
                .isFalse();

        // --- identity on the POST -------------------------------------------------------------------

        assertThat(commands.getFirst().getHeader(ResultsCommandGateway.IDENTITY_HEADER))
                .as("the command is posted as the sharing user, not the configured system identity")
                .isEqualTo(SHARING_USER_ID);
    }
}
