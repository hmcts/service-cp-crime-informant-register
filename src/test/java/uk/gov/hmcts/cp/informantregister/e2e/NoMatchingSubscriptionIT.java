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

/**
 * A hearing with one case, one defendant and one offence is shared when the prosecutor has no
 * matching entry in the NOW subscriptions for the informant register. The register must still be
 * produced and POSTed, but the {@code recipients} object must be absent from the outbound payload.
 *
 * <p>This is not an error — it is a business outcome. The prosecutor's authority has no one
 * subscribed to receive the register by email, but the register row is still written by Results
 * (the 19:00 CSV sweep handles delivery separately). The contract enforces this through
 * {@code minItems: 1} on the {@code recipients} array: an empty array would violate the schema,
 * so the pipeline correctly omits the key entirely when there are no matched subscriptions.
 *
 * <p>The hearing is derived from the {@code base__case-and-application} parity fixture, trimmed
 * programmatically to a single prosecution case (TFL), single defendant, and single offence. The
 * offence's surviving judicial result is set to "Absolute discharge". The subscriptions fixture is
 * the base case's own — its informant codes (CDE02–CDE05) do not match the prosecution authority
 * code TFL, so no subscription matches and {@code RecipientMapper} returns {@code null}, which
 * {@code @JsonInclude(NON_NULL)} omits from the wire.
 *
 * <p>The test asserts:
 * <ol>
 *   <li>The pipeline completes successfully (not a failure, not no-authorities)</li>
 *   <li>Exactly one {@code add-informant-register} POST is made (one authority)</li>
 *   <li>The outbound body contains the offence with "Absolute discharge" as a result</li>
 *   <li>The {@code recipients} key is absent from the outbound body</li>
 * </ol>
 *
 * <p>Tracked as <strong>RQA-AD-06</strong> in the Results QA Absolute Discharge test matrix.
 */
@Tag("RQA-AD-06")
@DisplayName("RQA-AD-06: no matching NOW subscription — register produced without recipients")
class NoMatchingSubscriptionIT extends AbstractRqaIT {

    // --- hearing builder ---

    @Override
    protected JsonNode buildHearing() {
        return singleCaseHearing();
    }

    /**
     * A hearing trimmed to one prosecution case, one defendant, one offence, with the surviving
     * judicial result set to the given text.
     *
     * <p>Starting from the base fixture:
     * <ul>
     *   <li>The second prosecution case is removed (TVL), leaving only the first (TFL).</li>
     *   <li>The second defendant of the first case is removed, leaving one defendant.</li>
     *   <li>All court applications are removed.</li>
     *   <li>The first (and only surviving) judicial result on the sole offence has its
     *       {@code label} and {@code resultText} set to "Absolute discharge".</li>
     * </ul>
     *
     * <p>Filter-relevant flags ({@code isAvailableForCourtExtract}, {@code publishedForNows},
     * {@code orderedDate}) are preserved from the base fixture, so the court-extract filter
     * produces the same survival/removal pattern and subscription matching runs the same way.
     */
    private static JsonNode singleCaseHearing() {
        final ObjectNode hearing = (ObjectNode) BASE_CASE.hearing().deepCopy();

        // Keep only the first prosecution case (TFL).
        final ArrayNode cases = (ArrayNode) hearing.get("prosecutionCases");
        while (cases.size() > 1) {
            cases.remove(cases.size() - 1);
        }

        // Keep only the first defendant of that case.
        final ArrayNode defendants = (ArrayNode) cases.get(0).get("defendants");
        while (defendants.size() > 1) {
            defendants.remove(defendants.size() - 1);
        }

        // Remove all court applications — this hearing has prosecution cases only.
        hearing.set("courtApplications", hearing.arrayNode());

        // Set the surviving judicial result to Absolute Discharge.
        final ObjectNode targetResult = (ObjectNode) defendants.get(0)
                .path("offences").get(0)
                .path("judicialResults").get(0);
        targetResult.put("label", RESULT_TEXT);
        targetResult.put("resultText", RESULT_TEXT);

        return hearing;
    }

    // --- the test ---------------------------------------------------------------------------------

    @Test
    @Tag("RQA-AD-06")
    @DisplayName("RQA-AD-06: one case, one defendant, one offence, no matching subscription — "
            + "register produced without recipients")
    void share_with_no_matching_subscription_should_produce_register_without_recipients() {

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
                .as("a hearing with one authority still produces a submission, even without recipients")
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

        // --- the authority identity ------------------------------------------------------------------

        assertThat(body.path("prosecutionAuthorityCode").stringValue())
                .as("the single register is for TFL, the only prosecution case left after trimming")
                .isEqualTo(AUTHORITY_CODE);

        // --- the offence and its result are present --------------------------------------------------

        final JsonNode courtSessions = body.path("hearingVenue").path("courtSessions");
        assertThat(courtSessions.isEmpty())
                .as("the venue must have at least one court session")
                .isFalse();

        final JsonNode defendants = courtSessions.get(0).path("defendants");
        assertThat(defendants.size())
                .as("one defendant after trimming")
                .isEqualTo(1);

        final JsonNode casesOrApps = defendants.get(0).path("prosecutionCasesOrApplications");
        assertThat(casesOrApps.size())
                .as("one prosecution case for this defendant")
                .isEqualTo(1);

        final JsonNode offences = casesOrApps.get(0).path("offences");
        assertThat(offences.size())
                .as("one offence on the case")
                .isEqualTo(1);

        final JsonNode offenceResults = offences.get(0).path("offenceResults");
        assertThat(offenceResults.isEmpty())
                .as("the offence must carry its results — the court-extract filter lets through "
                        + "the result whose isAvailableForCourtExtract is true and publishedForNows "
                        + "is false")
                .isFalse();

        assertThat(offenceResults.get(0).path("resultText").stringValue())
                .as("the offence result is the Absolute Discharge we injected")
                .isEqualTo(RESULT_TEXT);

        // --- recipients must be absent ---------------------------------------------------------------

        assertThat(body.has("recipients"))
                .as("no NOW subscription matches the prosecution authority code %s — the "
                        + "subscriptions carry informant codes CDE02–CDE05, none of which is %s — "
                        + "so RecipientMapper returns null and @JsonInclude(NON_NULL) omits the key "
                        + "entirely. An empty array would violate the contract's minItems: 1.",
                        AUTHORITY_CODE, AUTHORITY_CODE)
                .isFalse();

        // --- the identity on the POST ---------------------------------------------------------------

        assertThat(commands.getFirst().getHeader(ResultsCommandGateway.IDENTITY_HEADER))
                .as("the command is posted as the sharing user, not the configured system identity")
                .isEqualTo(SHARING_USER_ID);
    }
}
