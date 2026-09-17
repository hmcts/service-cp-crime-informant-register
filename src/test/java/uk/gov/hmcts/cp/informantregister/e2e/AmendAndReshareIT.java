package uk.gov.hmcts.cp.informantregister.e2e;

import java.util.List;
import java.util.UUID;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.informantregister.adapter.results.ResultsCommandGateway;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.RequestStatus;
import uk.gov.hmcts.cp.informantregister.support.AbstractRqaIT;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ServiceTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static uk.gov.hmcts.cp.informantregister.support.RegisterDocumentAssertions.allResultTexts;
import static uk.gov.hmcts.cp.informantregister.support.RegisterDocumentAssertions.bodyForAuthority;

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
@Tag("RQA-AD-07")
@DisplayName("RQA-AD-07: amend-and-reshare reflects the amended result in the register")
class AmendAndReshareIT extends AbstractRqaIT {

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

    // --- lifecycle override ---

    /**
     * This test manages cache seeding and API stubbing across its two phases manually. Only reset
     * stubs here — the test method handles everything else.
     */
    @Override
    @BeforeEach
    protected void seedTheWorld() {
        resetStubs();
    }

    /**
     * Not called by the overridden lifecycle, but satisfies the abstract contract. Returns the
     * initial-phase hearing.
     */
    @Override
    protected JsonNode buildHearing() {
        return hearingWithResult(INITIAL_RESULT_TEXT);
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

    private static void awaitCompleted(final UUID reqId) {
        await().atMost(COMPLETED_WITHIN).pollInterval(POLL).until(() ->
                ProcessedLogTestSupport.row(ProcessedLogTestSupport.SOURCE, reqId)
                        .filter(row -> RequestStatus.COMPLETED.name().equals(row.status()))
                        .isPresent());
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
