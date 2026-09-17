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
@Tag("RQA-AD-03")
@DisplayName("RQA-AD-03: one case, one defendant, two offences with Absolute Discharge — "
        + "both offences in payload, no recipients")
class TwoOffencesSingleDefendantIT extends AbstractRqaIT {

    // --- hearing builder ---

    @Override
    protected JsonNode buildHearing() {
        return twoOffenceHearing();
    }

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

    // --- the test ---------------------------------------------------------------------------------

    @Test
    @Tag("RQA-AD-03")
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
                .isEqualTo(2);

        // --- Absolute Discharge appears for both offences -------------------------------------------

        final List<String> resultTexts = allResultTexts(body);
        assertThat(resultTexts)
                .as("each of the two offences has two surviving judicial results whose "
                        + "resultText is set to Absolute Discharge — four entries total")
                .filteredOn(RESULT_TEXT::equals)
                .hasSize(4);

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
