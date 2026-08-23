package uk.gov.hmcts.cp.informantregister.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.informantregister.domain.RegisterResult;

/**
 * Reduces each defendant to the results and prompts that may appear on a court extract.
 *
 * <p>A port of {@code filterResultsAvailableForCourtExtract} in
 * {@code NowsHelper/service/RegisterFragmentService.js}. Two filters, applied in that order: results
 * that are available for court extract and have not already been published for NOWs, then, within
 * those, the prompts that are available for court extract.
 *
 * <p><strong>The prompt fallback turns on absence, not on falsity.</strong> The legacy test is
 * {@code prompt.isAvailableForCourtExtract === undefined}, which is a strict check for the field
 * being <em>missing</em>. A prompt that carries the field as JSON {@code null} does not take the
 * fallback: it returns the null, which is falsy, and the prompt is dropped. Only a prompt with no
 * such field at all falls back to the older {@code courtExtract} spelling, where the value must be
 * {@code Y} in any case and a missing {@code courtExtract} means dropped. Collapsing "absent" and
 * "null" together — the obvious simplification — would silently keep prompts the legacy discards.
 */
final class CourtExtractFilter {

    private CourtExtractFilter() {
    }

    /**
     * Applies the court-extract filters to every defendant in the list.
     *
     * @param defendants the defendant contexts to filter, modified in place
     */
    /* default */ static void apply(final List<DefendantContext> defendants) {
        for (final DefendantContext defendant : defendants) {
            final List<RegisterResult> kept = new ArrayList<>();
            for (final RegisterResult result : defendant.results()) {
                if (availableForCourtExtract(result.judicialResult())) {
                    filterPrompts(result.judicialResult());
                    kept.add(result);
                }
            }
            defendant.results(kept);
        }
    }

    /**
     * Whether a judicial result may appear on a court extract.
     *
     * @param judicialResult the result to test
     * @return whether it survives the result-level filter
     */
    private static boolean availableForCourtExtract(final JsonNode judicialResult) {
        return Json.truthy(judicialResult, "isAvailableForCourtExtract")
                && !Json.truthy(judicialResult, "publishedForNows");
    }

    /**
     * Drops the prompts that may not appear on a court extract.
     *
     * <p>The result node here is one this pipeline copied and owns, so filtering it in place is safe
     * — the hearing payload the copy came from is untouched.
     *
     * @param judicialResult the result whose prompts to filter
     */
    private static void filterPrompts(final JsonNode judicialResult) {
        final JsonNode prompts = Json.at(judicialResult, "judicialResultPrompts");
        if (!Json.truthy(prompts) || !prompts.isArray()) {
            return;
        }
        final ArrayNode kept = ((ObjectNode) judicialResult).arrayNode();
        for (final JsonNode prompt : prompts) {
            if (promptAvailableForCourtExtract(prompt)) {
                kept.add(prompt);
            }
        }
        ((ObjectNode) judicialResult).set("judicialResultPrompts", kept);
    }

    /**
     * Whether one prompt may appear on a court extract.
     *
     * @param prompt the prompt to test
     * @return whether it survives the prompt-level filter
     */
    private static boolean promptAvailableForCourtExtract(final JsonNode prompt) {
        final JsonNode available = Json.at(prompt, "isAvailableForCourtExtract");
        if (available != null) {
            return Json.truthy(available);
        }
        final String courtExtract = Json.text(prompt, "courtExtract");
        return courtExtract != null && "Y".equals(courtExtract.toUpperCase(Locale.ROOT));
    }
}
