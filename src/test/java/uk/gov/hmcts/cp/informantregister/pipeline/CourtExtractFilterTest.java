package uk.gov.hmcts.cp.informantregister.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.domain.RegisterResult;
import uk.gov.hmcts.cp.informantregister.domain.ResultLevel;
import uk.gov.hmcts.cp.informantregister.support.LegacyFixtures;

/**
 * The court-extract filter's decision table, pinned directly.
 *
 * <p>These cases exist because the inherited fixtures do not reach them. The legacy Jest suite has
 * no case where a prompt carries {@code isAvailableForCourtExtract} as an explicit JSON
 * {@code null}, nor one that exercises the older {@code courtExtract} spelling in every combination —
 * so the golden comparison passes whether or not those branches are right. That was confirmed rather
 * than assumed: inverting the null handling in the filter leaves the whole nineteen-case parity suite
 * green.
 *
 * <p>A gap the source suite never covered is still a gap this port has to be correct across, because
 * production payloads are not limited to the shapes somebody wrote a fixture for. The expected
 * behaviour below is read from the legacy source, not invented: {@code prompt.isAvailableForCourtExtract
 * === undefined ? (prompt.courtExtract ? prompt.courtExtract.toUpperCase() === 'Y' : false) :
 * prompt.isAvailableForCourtExtract}.
 */
@DisplayName("CourtExtractFilter")
class CourtExtractFilterTest {

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    @Nested
    @DisplayName("result level")
    class ResultLevelFilter {

        @Test
        @DisplayName("keeps a result that is available for court extract and unpublished")
        void keeps_an_available_unpublished_result() {
            assertThat(filterResults(
                    "{\"isAvailableForCourtExtract\":true,\"publishedForNows\":false}"))
                    .hasSize(1);
        }

        @Test
        @DisplayName("drops a result that is not available for court extract")
        void drops_a_result_that_is_not_available() {
            assertThat(filterResults(
                    "{\"isAvailableForCourtExtract\":false,\"publishedForNows\":false}"))
                    .isEmpty();
        }

        @Test
        @DisplayName("drops a result that has already been published for NOWs")
        void drops_a_result_already_published_for_nows() {
            assertThat(filterResults(
                    "{\"isAvailableForCourtExtract\":true,\"publishedForNows\":true}"))
                    .isEmpty();
        }

        @Test
        @DisplayName("drops a result that does not mention court extract at all")
        void drops_a_result_with_no_court_extract_flag() {
            assertThat(filterResults("{\"publishedForNows\":false}")).isEmpty();
        }
    }

    @Nested
    @DisplayName("prompt level")
    class PromptLevelFilter {

        @Test
        @DisplayName("keeps a prompt flagged available")
        void keeps_a_prompt_flagged_available() {
            assertThat(promptsAfterFiltering("{\"isAvailableForCourtExtract\":true}")).hasSize(1);
        }

        @Test
        @DisplayName("drops a prompt flagged unavailable")
        void drops_a_prompt_flagged_unavailable() {
            assertThat(promptsAfterFiltering("{\"isAvailableForCourtExtract\":false}")).isEmpty();
        }

        @Test
        @DisplayName("drops a prompt whose flag is null rather than falling back to courtExtract")
        void drops_a_prompt_whose_flag_is_null_without_falling_back() {
            // The legacy test is `=== undefined`, which a JSON null does not satisfy: the null is
            // returned as the filter's answer and is falsy. A prompt that would have been kept by
            // the fallback is therefore dropped, and treating null as absent would wrongly keep it.
            assertThat(promptsAfterFiltering(
                    "{\"isAvailableForCourtExtract\":null,\"courtExtract\":\"Y\"}"))
                    .isEmpty();
        }

        @Test
        @DisplayName("falls back to courtExtract only when the flag is absent")
        void falls_back_to_court_extract_when_the_flag_is_absent() {
            assertThat(promptsAfterFiltering("{\"courtExtract\":\"Y\"}")).hasSize(1);
        }

        @Test
        @DisplayName("accepts a lower-case courtExtract, which the legacy upper-cases")
        void accepts_a_lower_case_court_extract() {
            assertThat(promptsAfterFiltering("{\"courtExtract\":\"y\"}")).hasSize(1);
        }

        @Test
        @DisplayName("drops a prompt whose courtExtract is not Y")
        void drops_a_prompt_whose_court_extract_is_not_yes() {
            assertThat(promptsAfterFiltering("{\"courtExtract\":\"N\"}")).isEmpty();
        }

        @Test
        @DisplayName("drops a prompt with neither flag nor courtExtract")
        void drops_a_prompt_with_neither_flag() {
            assertThat(promptsAfterFiltering("{\"promptReference\":\"anything\"}")).isEmpty();
        }

        @Test
        @DisplayName("drops a prompt whose courtExtract is empty, which is falsy")
        void drops_a_prompt_whose_court_extract_is_empty() {
            assertThat(promptsAfterFiltering("{\"courtExtract\":\"\"}")).isEmpty();
        }
    }

    /**
     * The JUnit twin of the legacy {@code RegisterFragmentService} Jest suite.
     *
     * <p>That suite declares four cases. Three of them assert only that an export
     * ({@code getLatestOrderedDate}, {@code getHearingDate},
     * {@code filterResultsAvailableForCourtExtract}) is an instance of {@code Function}, which is a
     * statement about the module's shape and not about any behaviour a port could differ on; a Java
     * twin of one would be tautologically true, which the constitution's TDD principle rejects on
     * sight. The three functions themselves are covered by behaviour: the filter here, and
     * {@code getLatestOrderedDate} and {@code getHearingDate} by the nineteen goldens in
     * {@link RegisterBuilderParityTest}, whose hearing dates are derived through both.
     *
     * <p>The fourth case is the substantive one and is twinned below, against the same fixture, copied
     * byte-identical.
     */
    @Nested
    @DisplayName("RegisterFragmentService — legacy Jest twins")
    class LegacyJestTwins {

        @Test
        @DisplayName("should filter results and prompts for court extract based on "
                + "isAvailableForCourtExtract, publishedForNows and courtExtract flags")
        void should_filter_results_and_prompts_for_court_extract() {
            final DefendantContext defendant = new DefendantContext();
            for (final JsonNode judicialResult : fixture()) {
                defendant.addResults(List.of(result(judicialResult)));
            }

            CourtExtractFilter.apply(List.of(defendant));

            final List<RegisterResult> kept = defendant.results();
            assertThat(kept).hasSize(3);
            assertThat(kept).allSatisfy(result -> {
                assertThat(Json.truthy(result.judicialResult(), "isAvailableForCourtExtract"))
                        .isTrue();
                assertThat(Json.truthy(result.judicialResult(), "publishedForNows")).isFalse();
            });

            assertThat(prompts(kept.get(0))).hasSize(1);
            assertThat(courtExtract(kept.get(0), 0)).isEqualTo("Y");

            assertThat(prompts(kept.get(1))).hasSize(1);
            assertThat(courtExtract(kept.get(1), 0)).isEqualTo("y");

            assertThat(prompts(kept.get(2))).hasSize(3);
            assertThat(courtExtract(kept.get(2), 0)).isNull();
            assertThat(Json.truthy(prompts(kept.get(2)).get(0), "isAvailableForCourtExtract"))
                    .isTrue();
            assertThat(courtExtract(kept.get(2), 1)).isEqualTo("Y");
            assertThat(courtExtract(kept.get(2), 2)).isEqualTo("Y");
        }

        /**
         * The prompts a surviving result still carries.
         *
         * @param result the surviving result
         * @return its prompts
         */
        private JsonNode prompts(final RegisterResult result) {
            return result.judicialResult().get("judicialResultPrompts");
        }

        /**
         * The {@code courtExtract} value of one surviving prompt.
         *
         * @param result the surviving result
         * @param index  the prompt's position
         * @return the value, or {@code null} where the prompt carries none
         */
        private String courtExtract(final RegisterResult result, final int index) {
            return Json.text(prompts(result).get(index), "courtExtract");
        }

        /**
         * The legacy fixture, copied byte-identical from
         * {@code NowsHelper/service/test/judicial-results-for-court-extract.json}.
         *
         * @return the judicial results the Jest case builds its defendant from
         */
        private JsonNode fixture() {
            return LegacyFixtures.read("judicial-results-for-court-extract.json");
        }
    }

    /**
     * Runs the filter over a single result and returns what survived.
     *
     * @param judicialResult the judicial result as JSON text
     * @return the surviving results
     */
    private List<RegisterResult> filterResults(final String judicialResult) {
        final DefendantContext defendant = new DefendantContext();
        defendant.addResults(List.of(result(judicialResult)));
        CourtExtractFilter.apply(List.of(defendant));
        return defendant.results();
    }

    /**
     * Runs the filter over a result carrying one prompt, and returns the prompts that survived.
     *
     * @param prompt the prompt as JSON text
     * @return the surviving prompts
     */
    private JsonNode promptsAfterFiltering(final String prompt) {
        final List<RegisterResult> kept = filterResults(
                "{\"isAvailableForCourtExtract\":true,\"publishedForNows\":false,"
                        + "\"judicialResultPrompts\":[" + prompt + "]}");
        assertThat(kept).as("the surrounding result must survive for the prompts to be observable")
                .hasSize(1);
        return kept.get(0).judicialResult().get("judicialResultPrompts");
    }

    /**
     * Wraps a judicial result as the kind of gathered result the filter operates on.
     *
     * @param judicialResult the judicial result as JSON text
     * @return the gathered result
     */
    private RegisterResult result(final String judicialResult) {
        return result(mapper.readTree(judicialResult));
    }

    /**
     * Wraps an already-parsed judicial result as the kind of gathered result the filter operates on.
     *
     * @param judicialResult the judicial result
     * @return the gathered result
     */
    private RegisterResult result(final JsonNode judicialResult) {
        return new RegisterResult(null, null, null, null, ResultLevel.OFFENCE, null,
                judicialResult, null, null);
    }
}
