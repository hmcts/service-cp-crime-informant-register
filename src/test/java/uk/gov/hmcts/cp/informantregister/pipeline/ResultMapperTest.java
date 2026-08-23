package uk.gov.hmcts.cp.informantregister.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterResult;
import uk.gov.hmcts.cp.informantregister.domain.RegisterDefendant;
import uk.gov.hmcts.cp.informantregister.domain.ResultLevel;

/**
 * The JUnit twins of the legacy {@code ResultMapper} Jest suite.
 *
 * <p>Eight twins, one per Jest case, in the order the legacy file declares them. The suite is built
 * from the legacy {@code ModelObjects} helper rather than from fixtures, so the twins are built from
 * its Java translation, {@link ModelObjects}.
 *
 * <p>The Jest cases cover three of the four levels. The fourth — application level — is executed by
 * nothing in the legacy repository at all, which the parity pack records as BS-07; it is covered here
 * rather than left to the first real court application to discover.
 */
@DisplayName("ResultMapper — parity with the legacy ResultMapper")
class ResultMapperTest {

    /** No case here reads the clock; the fixed value only makes that visible. */
    private static final Clock FROZEN =
            Clock.fixed(Instant.parse("2021-06-15T09:30:00Z"), ZoneOffset.UTC);

    private static final String FIXTURES = "/fixtures/outboundinformantregister/mapper/";

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    private final ResultDataMapper resultDataMapper = new ResultDataMapper(new HearingDates(FROZEN));

    @Nested
    @DisplayName("Result mapper works correctly")
    class LegacyTwins {

        @Test
        @DisplayName("when defendant level results then mapper should give results")
        void defendant_level_with_one_result_should_map_it() {
            final RegisterDefendant defendant = ModelObjects.defendantContextBase(
                    "MASTER_10001",
                    ModelObjects.taggedResult(
                            ResultLevel.DEFENDANT,
                            ModelObjects.judicialResult("cjsCode_1", "Text_1"),
                            null, null, null));

            final List<InformantRegisterResult> results = mapperFor(defendant).defendantLevel();

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().cjsResultCode()).isEqualTo("cjsCode_1");
            assertThat(results.getFirst().resultText()).isEqualTo("Text_1");
            assertThat(results.getFirst().resultData()).isNull();
        }

        @Test
        @DisplayName("should map to results with result data for defendant level results")
        void defendant_level_with_a_next_hearing_should_carry_the_result_data() {
            final RegisterDefendant defendant = ModelObjects.defendantContextBase(
                    "MASTER_10001",
                    ModelObjects.taggedResult(
                            ResultLevel.DEFENDANT,
                            fixture("judicialResult-with-nextHearing.json"),
                            null, null, null));

            final List<InformantRegisterResult> results = mapperFor(defendant).defendantLevel();

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().cjsResultCode()).isEqualTo("4028");
            assertThat(results.getFirst().resultText()).startsWith("Remanded in custody");
            assertThat(results.getFirst().resultData().nextHearingDate())
                    .isEqualTo("2020-12-10T14:00:00Z");
            assertThat(results.getFirst().resultData().nextCourtLocation())
                    .isEqualTo("Westminster Magistrates' Court");
            assertThat(results.getFirst().resultData().durationUnit()).isNull();
            assertThat(results.getFirst().resultData().durationValue()).isNull();
            assertThat(results.getFirst().resultData().secondaryDurationUnit()).isNull();
            assertThat(results.getFirst().resultData().secondaryDurationValue()).isNull();
            assertThat(results.getFirst().resultData().durationStartDate()).isNull();
            assertThat(results.getFirst().resultData().durationEndDate()).isNull();
            assertThat(results.getFirst().resultData().amount()).isNull();
        }

        @Test
        @DisplayName("when defendant level results is empty then mapper should return undefined")
        void defendant_level_with_no_results_should_produce_nothing() {
            assertThat(mapperFor(ModelObjects.defendantContextBase("MASTER_10001"))
                    .defendantLevel())
                    .isNull();
        }

        @Test
        @DisplayName("when multiple defendant level results then mapper should give multiple "
                + "results")
        void defendant_level_with_two_results_should_map_both() {
            final RegisterDefendant defendant = ModelObjects.defendantContextBase(
                    "MASTER_10001",
                    ModelObjects.taggedResult(ResultLevel.DEFENDANT,
                            ModelObjects.judicialResult("cjsCode_1", "Text_1"), null, null, null),
                    ModelObjects.taggedResult(ResultLevel.DEFENDANT,
                            ModelObjects.judicialResult("cjsCode_2", "Text_2"), null, null, null));

            assertThat(mapperFor(defendant).defendantLevel()).hasSize(2);
        }

        @Test
        @DisplayName("when multiple defendant case level results then mapper should give multiple "
                + "results")
        void case_level_with_two_results_on_one_case_should_map_both() {
            final RegisterDefendant defendant = ModelObjects.defendantContextBase(
                    "MASTER_10001",
                    ModelObjects.taggedResult(ResultLevel.CASE,
                            ModelObjects.judicialResult("cjsCode_1", "Text_1"),
                            "caseId_1", null, null),
                    ModelObjects.taggedResult(ResultLevel.CASE,
                            ModelObjects.judicialResult("cjsCode_2", "Text_2"),
                            "caseId_1", null, null));

            assertThat(mapperFor(defendant).caseLevel("caseId_1")).hasSize(2);
        }

        @Test
        @DisplayName("should filter defendant case level results")
        void case_level_should_return_only_the_requested_case() {
            final RegisterDefendant defendant = ModelObjects.defendantContextBase(
                    "MASTER_10001",
                    ModelObjects.taggedResult(ResultLevel.CASE,
                            ModelObjects.judicialResult("cjsCode_1", "Text_1"),
                            "caseId_1", null, null),
                    ModelObjects.taggedResult(ResultLevel.CASE,
                            ModelObjects.judicialResult("cjsCode_2", "Text_2"),
                            "caseId_2", null, null));

            assertThat(mapperFor(defendant).caseLevel("caseId_1")).hasSize(1);
        }

        @Test
        @DisplayName("when offence level results then mapper should give results")
        void offence_level_with_one_result_should_map_it() {
            final RegisterDefendant defendant = ModelObjects.defendantContextBase(
                    "MASTER_10001",
                    ModelObjects.taggedResult(ResultLevel.OFFENCE,
                            ModelObjects.judicialResult("cjsCode_1", "Text_1"),
                            null, "offence-id-1", null));

            final List<InformantRegisterResult> results =
                    mapperFor(defendant).offenceLevel(offenceWithId("offence-id-1"));

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().cjsResultCode()).isEqualTo("cjsCode_1");
            assertThat(results.getFirst().resultText()).isEqualTo("Text_1");
            assertThat(results.getFirst().resultData()).isNull();
        }

        @Test
        @DisplayName("when multiple offence level results then mapper should give results")
        void offence_level_with_two_results_should_map_both() {
            final RegisterDefendant defendant = ModelObjects.defendantContextBase(
                    "MASTER_10001",
                    ModelObjects.taggedResult(ResultLevel.OFFENCE,
                            ModelObjects.judicialResult("cjsCode_1", "Text_1"),
                            null, "offence-id-1", null),
                    ModelObjects.taggedResult(ResultLevel.OFFENCE,
                            ModelObjects.judicialResult("cjsCode_2", "Text_2"),
                            null, "offence-id-1", null));

            assertThat(mapperFor(defendant).offenceLevel(offenceWithId("offence-id-1"))).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Branches the legacy suite never executes (parity-pack BS-07)")
    class UncoveredBranches {

        /**
         * BS-07: {@code buildApplicationLevelResults} and its {@code map} callback record zero
         * executions across the whole legacy repository, yet the branch is what puts an application's
         * results on the register. The behaviour is the same shape as the case-level branch, keyed on
         * {@code applicationId} instead.
         */
        @Test
        @DisplayName("BS-07 — application level results are selected by application id")
        void application_level_should_return_only_the_requested_application() {
            final RegisterDefendant defendant = ModelObjects.defendantContextBase(
                    "MASTER_10001",
                    ModelObjects.taggedResult(ResultLevel.APPLICATION,
                            ModelObjects.judicialResult("cjsCode_1", "Text_1"),
                            null, null, "application-id-1"),
                    ModelObjects.taggedResult(ResultLevel.APPLICATION,
                            ModelObjects.judicialResult("cjsCode_2", "Text_2"),
                            null, null, "application-id-2"));

            final List<InformantRegisterResult> results =
                    mapperFor(defendant).applicationLevel("application-id-1");

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().cjsResultCode()).isEqualTo("cjsCode_1");
        }

        /**
         * BS-07: the empty answer on the same branch. Every level returns {@code undefined} rather
         * than an empty array, which is what keeps the {@code results} key off the outbound body.
         */
        @Test
        @DisplayName("BS-07 — an application with no results produces nothing, not an empty list")
        void application_level_with_no_matching_results_should_produce_nothing() {
            final RegisterDefendant defendant = ModelObjects.defendantContextBase(
                    "MASTER_10001",
                    ModelObjects.taggedResult(ResultLevel.APPLICATION,
                            ModelObjects.judicialResult("cjsCode_1", "Text_1"),
                            null, null, "application-id-1"));

            assertThat(mapperFor(defendant).applicationLevel("application-id-2")).isNull();
        }

        /**
         * The level tag is part of the filter on every branch, not just the identifier. A case-level
         * result recorded against the same identifier must not surface as an application's.
         */
        @Test
        @DisplayName("BS-07 — the level tag filters as well as the identifier")
        void application_level_should_ignore_a_case_level_result_with_the_same_identifier() {
            final RegisterDefendant defendant = ModelObjects.defendantContextBase(
                    "MASTER_10001",
                    ModelObjects.taggedResult(ResultLevel.CASE,
                            ModelObjects.judicialResult("cjsCode_1", "Text_1"),
                            "shared-id", null, "shared-id"));

            assertThat(mapperFor(defendant).applicationLevel("shared-id")).isNull();
        }
    }

    /**
     * A mapper over one defendant's results.
     *
     * @param defendant the defendant whose results are being mapped
     * @return the mapper
     */
    private ResultMapper mapperFor(final RegisterDefendant defendant) {
        return new ResultMapper(defendant, resultDataMapper);
    }

    /**
     * An offence carrying nothing but the id the offence-level filter matches on.
     *
     * @param id the offence id
     * @return the offence tree
     */
    private JsonNode offenceWithId(final String id) {
        return mapper.readTree("{\"id\":\"" + id + "\"}");
    }

    /**
     * Reads one of the byte-identical fixture copies.
     *
     * @param name the fixture file name
     * @return the parsed tree
     */
    private JsonNode fixture(final String name) {
        final String resource = FIXTURES + name;
        try (InputStream source = ResultMapperTest.class.getResourceAsStream(resource)) {
            if (source == null) {
                throw new IllegalStateException("missing fixture " + resource);
            }
            return mapper.readTree(source);
        } catch (java.io.IOException cannotRead) {
            throw new IllegalStateException("could not read " + resource, cannotRead);
        }
    }
}
