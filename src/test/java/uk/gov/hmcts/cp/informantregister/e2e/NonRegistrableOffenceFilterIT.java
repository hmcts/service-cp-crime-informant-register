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
 * A hearing with one prosecution case (TFL), one defendant and two offences. Offence 1 is resulted
 * with "Absolute discharge" (informant-registrable: {@code publishedForNows: false}). Offence 2
 * has all its judicial results marked {@code publishedForNows: true} — not informant-registrable —
 * so the court-extract filter removes them all.
 *
 * <p>The filter condition in {@code CourtExtractFilter} is:
 * {@code isAvailableForCourtExtract == true && publishedForNows != true}. A result with
 * {@code publishedForNows: true} means "already distributed via NOWs", so it is excluded from the
 * informant register. When <em>all</em> results on an offence are excluded, the offence may still
 * appear in the outbound document but its {@code offenceResults} key is absent — not an empty
 * array, because the contract declares {@code minItems: 1}.
 *
 * <p>The hearing is derived from the {@code base__case-and-application} parity fixture. The TFL
 * case has two defendants, each with one offence. The modification:
 * <ul>
 *   <li>The second prosecution case (TVL) and court applications are removed.</li>
 *   <li>Defendant 2's offence is moved onto defendant 1's offences array (two offences, one
 *       defendant).</li>
 *   <li>Defendant 2 is removed.</li>
 *   <li>Offence 1's first judicial result is set to "Absolute discharge" — it already has
 *       {@code publishedForNows: false}, so it survives the filter.</li>
 *   <li>Offence 2's <em>all</em> judicial results are set to {@code publishedForNows: true} —
 *       none survive the filter.</li>
 * </ul>
 *
 * <p>The test asserts:
 * <ol>
 *   <li>The pipeline completes successfully.</li>
 *   <li>Exactly one POST — one case, one authority.</li>
 *   <li>The outbound document contains "Absolute discharge" (offence 1's result is present).</li>
 *   <li>Exactly one offence carries {@code offenceResults} — offence 2 has none.</li>
 *   <li>The {@code recipients} key is absent — no subscription match.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("End to end: one registrable offence, one non-registrable — "
        + "only the registrable offence carries results")
class NonRegistrableOffenceFilterIT {

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

    /**
     * The base fixture uses the same offence {@code id} on both defendants (they are charged with
     * the same statutory offence). When we move defendant 2's offence onto defendant 1, the two
     * offences must carry <em>different</em> ids — otherwise the pipeline's
     * {@code ResultMapper.offenceLevel(offenceId)} returns the union of both offences' results for
     * each offence, defeating the filter test.
     */
    private static final String OFFENCE_2_ID = "b265e939-2345-58f1-c9e6-f92338942e05";

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
     * Returns a deep copy of the base hearing trimmed to one case (TFL), one defendant, two
     * offences — offence 1 registrable ("Absolute discharge"), offence 2 not registrable (all
     * results {@code publishedForNows: true}).
     *
     * <p>The base TFL case has two defendants, each with one offence. This method moves defendant
     * 2's offence onto defendant 1, removes defendant 2, then:
     * <ul>
     *   <li>Offence 1: sets {@code judicialResults[0]} to "Absolute discharge" — it already has
     *       {@code publishedForNows: false} so it survives the court-extract filter.</li>
     *   <li>Offence 2: sets <em>every</em> judicial result to {@code publishedForNows: true} — the
     *       court-extract filter removes them all, leaving no registrable results.</li>
     * </ul>
     */
    private static JsonNode filteredOffenceHearing() {
        final ObjectNode hearing = (ObjectNode) BASE_CASE.hearing().deepCopy();

        // Keep only the first prosecution case (TFL).
        final ArrayNode cases = (ArrayNode) hearing.get("prosecutionCases");
        while (cases.size() > 1) {
            cases.remove(cases.size() - 1);
        }

        // Remove court applications.
        hearing.set("courtApplications", hearing.arrayNode());

        final ArrayNode defendants = (ArrayNode) cases.get(0).get("defendants");

        // Move defendant 2's offence onto defendant 1's offences array, assigning a distinct id.
        // The base fixture gives both defendants the same offence id (same statutory offence); to
        // test per-offence result filtering the second offence needs its own id so
        // ResultMapper.offenceLevel() can distinguish them.
        final ObjectNode secondDefendantOffence = (ObjectNode) defendants.get(1)
                .path("offences").get(0).deepCopy();
        secondDefendantOffence.put("id", OFFENCE_2_ID);
        final ArrayNode offences = (ArrayNode) defendants.get(0).get("offences");
        offences.add(secondDefendantOffence);

        // Remove defendant 2 — one defendant with two offences.
        defendants.remove(1);

        // Offence 1: set the surviving result (publishedForNows: false) to Absolute Discharge.
        final ObjectNode offence1Result = (ObjectNode) offences.get(0)
                .path("judicialResults").get(0);
        offence1Result.put("label", RESULT_TEXT);
        offence1Result.put("resultText", RESULT_TEXT);

        // Offence 2: mark ALL judicial results as publishedForNows: true — not registrable.
        final ArrayNode offence2JudicialResults =
                (ArrayNode) offences.get(1).get("judicialResults");
        for (int i = 0; i < offence2JudicialResults.size(); i++) {
            ((ObjectNode) offence2JudicialResults.get(i)).put("publishedForNows", true);
        }

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
                        MAPPER.writeValueAsString(filteredOffenceHearing()));
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
     * Collects every {@code resultText} from offence results in the document.
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
     * Counts offences that carry at least one {@code offenceResult}.
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
     * Counts offences that have NO {@code offenceResults} — either the key is absent or its array
     * is empty.
     */
    private static int countOffencesWithoutResults(final JsonNode document) {
        int count = 0;
        for (final JsonNode session : iterable(document.path("hearingVenue").path("courtSessions"))) {
            for (final JsonNode defendant : iterable(session.path("defendants"))) {
                for (final JsonNode caseOrApp
                        : iterable(defendant.path("prosecutionCasesOrApplications"))) {
                    for (final JsonNode offence : iterable(caseOrApp.path("offences"))) {
                        final JsonNode offenceResults = offence.path("offenceResults");
                        if (offenceResults.isMissingNode() || offenceResults.isEmpty()) {
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
    @DisplayName("offence 1 registrable (Absolute Discharge), offence 2 not registrable "
            + "(publishedForNows) — only offence 1 carries results")
    void registrable_offence_carries_results_and_non_registrable_offence_does_not() {

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

        // --- offence 1: Absolute Discharge result is present ----------------------------------------

        final List<String> resultTexts = allResultTexts(body);
        assertThat(resultTexts)
                .as("offence 1's Absolute Discharge must appear in the outbound document")
                .contains(RESULT_TEXT);

        assertThat(countOffencesWithResults(body))
                .as("only offence 1 survives the court-extract filter with results — offence 2's "
                        + "results are all publishedForNows: true and are removed")
                .isEqualTo(1);

        // --- offence 2: no results (publishedForNows filtered them all out) -------------------------

        assertThat(countOffencesWithoutResults(body))
                .as("offence 2 has no registrable results — all its judicial results were "
                        + "publishedForNows: true, so the court-extract filter removed them. The "
                        + "offence entry may still appear but offenceResults is absent.")
                .isGreaterThanOrEqualTo(1);

        // --- recipients must be absent ---------------------------------------------------------------

        assertThat(body.has("recipients"))
                .as("no NOW subscription matches the prosecution authority code %s",
                        AUTHORITY_CODE)
                .isFalse();

        // --- identity on the POST -------------------------------------------------------------------

        assertThat(commands.getFirst().getHeader(ResultsCommandGateway.IDENTITY_HEADER))
                .as("the command is posted as the sharing user, not the configured system identity")
                .isEqualTo(SHARING_USER_ID);
    }
}
