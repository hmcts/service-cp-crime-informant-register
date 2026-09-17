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
import static uk.gov.hmcts.cp.informantregister.support.RegisterDocumentAssertions.countOffencesWithResults;
import static uk.gov.hmcts.cp.informantregister.support.RegisterDocumentAssertions.countOffencesWithoutResults;

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
@Tag("RQA-AD-02")
@DisplayName("RQA-AD-02: one registrable offence, one non-registrable — "
        + "only the registrable offence carries results")
class NonRegistrableOffenceFilterIT extends AbstractRqaIT {

    /**
     * The base fixture uses the same offence {@code id} on both defendants (they are charged with
     * the same statutory offence). When we move defendant 2's offence onto defendant 1, the two
     * offences must carry <em>different</em> ids — otherwise the pipeline's
     * {@code ResultMapper.offenceLevel(offenceId)} returns the union of both offences' results for
     * each offence, defeating the filter test.
     */
    private static final String OFFENCE_2_ID = "b265e939-2345-58f1-c9e6-f92338942e05";

    // --- hearing builder ---

    @Override
    protected JsonNode buildHearing() {
        return filteredOffenceHearing();
    }

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

    // --- the test ---------------------------------------------------------------------------------

    @Test
    @Tag("RQA-AD-02")
    @DisplayName("RQA-AD-02: offence 1 registrable (Absolute Discharge), offence 2 not "
            + "registrable (publishedForNows) — only offence 1 carries results")
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
                        + "offence entry still appears but offenceResults is absent.")
                .isEqualTo(1);

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
