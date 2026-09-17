package uk.gov.hmcts.cp.informantregister.e2e;

import java.util.List;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
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
import static uk.gov.hmcts.cp.informantregister.support.RegisterDocumentAssertions.countDefendants;
import static uk.gov.hmcts.cp.informantregister.support.RegisterDocumentAssertions.countOffencesWithResults;

/**
 * A hearing with one prosecution case (TFL) and two defendants, both resulted with "Absolute
 * discharge". The register is shared and the outbound payload is asserted to contain all offences
 * with their results. The {@code recipients} object must be absent because the subscriptions'
 * informant codes (CDE02–CDE05) do not match TFL's prosecution authority code — there is no
 * {@code majorCreditorCode} on TFL's {@code prosecutionCaseIdentifier}.
 *
 * <p>This test fills the gap between:
 * <ul>
 *   <li>{@code NoMatchingSubscriptionIT} — proves no-subscription/recipients-absent, but with a
 *       single defendant. It does not verify that the pipeline correctly renders <em>multiple</em>
 *       defendants under the same authority.</li>
 *   <li>{@code SharedMasterDefendantIT} — exercises two defendants across two different authorities
 *       (shared {@code masterDefendantId}), not two defendants within the same prosecution case.</li>
 * </ul>
 *
 * <p>The hearing is derived from the {@code base__case-and-application} parity fixture. The first
 * prosecution case (TFL) naturally carries two defendants, each with one offence. The modification:
 * <ul>
 *   <li>The second prosecution case (TVL) is removed — one case only.</li>
 *   <li>Court applications are removed — prosecution cases only.</li>
 *   <li>Both defendants' first surviving judicial result is set to "Absolute discharge".</li>
 * </ul>
 *
 * <p>The test asserts:
 * <ol>
 *   <li>The pipeline completes successfully with one authority submitted.</li>
 *   <li>Exactly one {@code add-informant-register} POST is made (one prosecution case = one
 *       authority).</li>
 *   <li>The outbound document contains two defendants, each with an offence carrying "Absolute
 *       discharge".</li>
 *   <li>The {@code recipients} key is absent — no subscription match.</li>
 * </ol>
 *
 * <p>Tracked as <strong>RQA-AD-04</strong> in the Results QA Absolute Discharge test matrix.
 */
@Tag("RQA-AD-04")
@DisplayName("RQA-AD-04: one case, two defendants, Absolute Discharge — "
        + "both defendants in payload, no recipients")
class TwoDefendantsSingleCaseIT extends AbstractRqaIT {

    // --- hearing builder ---

    @Override
    protected JsonNode buildHearing() {
        return twoDefendantHearing();
    }

    /**
     * Returns a deep copy of the base hearing trimmed to one prosecution case (TFL) with its
     * natural two defendants, both offences resulted with "Absolute discharge".
     *
     * <p>Starting from the base fixture:
     * <ul>
     *   <li>The second prosecution case (TVL) is removed — one case remains.</li>
     *   <li>Court applications are removed.</li>
     *   <li>Defendant 1's first judicial result is set to "Absolute discharge".</li>
     *   <li>Defendant 2's first judicial result is set to "Absolute discharge".</li>
     * </ul>
     *
     * <p>Filter-relevant flags ({@code isAvailableForCourtExtract}, {@code publishedForNows},
     * {@code orderedDate}) are preserved from the base fixture.
     */
    private static JsonNode twoDefendantHearing() {
        final ObjectNode hearing = (ObjectNode) BASE_CASE.hearing().deepCopy();

        // Keep only the first prosecution case (TFL).
        final ArrayNode cases = (ArrayNode) hearing.get("prosecutionCases");
        while (cases.size() > 1) {
            cases.remove(cases.size() - 1);
        }

        // Remove court applications — one prosecution case only.
        hearing.set("courtApplications", hearing.arrayNode());

        // The TFL case has two defendants — set both offences' first result to Absolute Discharge.
        final ArrayNode defendants = (ArrayNode) cases.get(0).get("defendants");

        final ObjectNode firstDefendantResult = (ObjectNode) defendants.get(0)
                .path("offences").get(0)
                .path("judicialResults").get(0);
        firstDefendantResult.put("label", RESULT_TEXT);
        firstDefendantResult.put("resultText", RESULT_TEXT);

        final ObjectNode secondDefendantResult = (ObjectNode) defendants.get(1)
                .path("offences").get(0)
                .path("judicialResults").get(0);
        secondDefendantResult.put("label", RESULT_TEXT);
        secondDefendantResult.put("resultText", RESULT_TEXT);

        return hearing;
    }

    // --- the test ---------------------------------------------------------------------------------

    @Test
    @Tag("RQA-AD-04")
    @DisplayName("RQA-AD-04: one case, two defendants, both Absolute Discharge — payload has all "
            + "offences with results, recipients absent")
    void two_defendants_absolute_discharge_should_produce_register_with_both_and_no_recipients() {

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

        // --- both defendants present ----------------------------------------------------------------

        assertThat(countDefendants(body))
                .as("the TFL case has two defendants — both must appear in the outbound document")
                .isEqualTo(2);

        // --- all offences carry results -------------------------------------------------------------

        assertThat(countOffencesWithResults(body))
                .as("each defendant has one offence with results — two offences total, each with "
                        + "exactly one offenceResult entry")
                .isEqualTo(2);

        // --- Absolute Discharge appears for both defendants -----------------------------------------

        final List<String> resultTexts = allResultTexts(body);
        assertThat(resultTexts)
                .as("the payload must contain the Absolute Discharge results we injected on both "
                        + "defendants' offences")
                .filteredOn(RESULT_TEXT::equals)
                .hasSize(2);

        // --- recipients must be absent ---------------------------------------------------------------

        assertThat(body.has("recipients"))
                .as("no NOW subscription matches the prosecution authority code %s — the "
                        + "subscriptions carry informant codes CDE02–CDE05, none of which is %s — "
                        + "so RecipientMapper returns null and @JsonInclude(NON_NULL) omits the key "
                        + "entirely. An empty array would violate the contract's minItems: 1.",
                        AUTHORITY_CODE, AUTHORITY_CODE)
                .isFalse();

        // --- identity on the POST -------------------------------------------------------------------

        assertThat(commands.getFirst().getHeader(ResultsCommandGateway.IDENTITY_HEADER))
                .as("the command is posted as the sharing user, not the configured system identity")
                .isEqualTo(SHARING_USER_ID);
    }
}
