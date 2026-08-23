package uk.gov.hmcts.cp.informantregister.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterResultData;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;

/**
 * The JUnit twins of the legacy {@code ResultDataMapper} Jest suite.
 *
 * <p>Five twins, one per Jest case, in the order the legacy file declares them, each running against
 * a byte-identical copy of the fixture the Jest case requires. The Jest assertions are transcribed
 * exactly — including every {@code toBeUndefined()}, which here is an assertion that the component is
 * {@code null} and so absent from the wire under {@code @JsonInclude(NON_NULL)}.
 *
 * <p>Beyond the twins, this suite carries the pinning cases the parity pack requires for this mapper
 * and the coverage findings it names. Those are marked as such and cite the identifier they answer.
 */
@DisplayName("ResultDataMapper — parity with the legacy ResultDataMapper")
class ResultDataMapperTest {

    /** No case here reads the clock; the fixed value only makes that visible. */
    private static final Clock FROZEN =
            Clock.fixed(Instant.parse("2021-06-15T09:30:00Z"), ZoneOffset.UTC);

    private static final String FIXTURES = "/fixtures/outboundinformantregister/mapper/";

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    private final ResultDataMapper resultDataMapper = new ResultDataMapper(new HearingDates(FROZEN));

    @Nested
    @DisplayName("ResultData mapper works as expected")
    class LegacyTwins {

        @Test
        @DisplayName("should get hearingDate and nextCourtLocation details from nextHearing when "
                + "available")
        void build_with_a_next_hearing_should_map_the_date_and_the_court_location() {
            final InformantRegisterResultData resultData =
                    resultDataMapper.build(fixture("judicialResult-with-nextHearing.json"));

            assertThat(resultData.nextHearingDate()).isEqualTo("2020-12-10T14:00:00Z");
            assertThat(resultData.nextCourtLocation()).isEqualTo("Westminster Magistrates' Court");

            assertThat(resultData.durationUnit()).isNull();
            assertThat(resultData.durationValue()).isNull();
            assertThat(resultData.secondaryDurationUnit()).isNull();
            assertThat(resultData.secondaryDurationValue()).isNull();
            assertThat(resultData.durationStartDate()).isNull();
            assertThat(resultData.durationEndDate()).isNull();
            assertThat(resultData.amount()).isNull();
        }

        @Test
        @DisplayName("should get duration details from durationElement when available")
        void build_with_a_duration_element_should_map_every_duration_component() {
            final InformantRegisterResultData resultData =
                    resultDataMapper.build(fixture("judicialResult-with-durationElement.json"));

            assertThat(resultData.durationUnit()).isEqualTo("M");
            assertThat(resultData.durationValue()).isEqualTo("5");
            assertThat(resultData.secondaryDurationUnit()).isEqualTo("Y");
            assertThat(resultData.secondaryDurationValue()).isEqualTo("2");
            assertThat(resultData.durationStartDate()).isEqualTo("2019-02-26T00:00:00Z");
            assertThat(resultData.durationEndDate()).isEqualTo("2021-07-26T00:00:00Z");

            assertThat(resultData.nextHearingDate()).isNull();
            assertThat(resultData.nextCourtLocation()).isNull();
            assertThat(resultData.amount()).isNull();
        }

        @Test
        @DisplayName("should get the amount if result is a financial result")
        void build_with_a_financial_result_should_map_the_imposed_amount() {
            final InformantRegisterResultData resultData =
                    resultDataMapper.build(fixture("judicialResult-with-financialResult.json"));

            assertThat(resultData.amount()).isEqualTo("£188.00");

            assertThat(resultData.durationUnit()).isNull();
            assertThat(resultData.durationValue()).isNull();
            assertThat(resultData.secondaryDurationUnit()).isNull();
            assertThat(resultData.secondaryDurationValue()).isNull();
            assertThat(resultData.durationStartDate()).isNull();
            assertThat(resultData.durationEndDate()).isNull();
            assertThat(resultData.nextHearingDate()).isNull();
            assertThat(resultData.nextCourtLocation()).isNull();
        }

        @Test
        @DisplayName("should not get the amount if there are no prompts in the financial result")
        void build_with_a_financial_result_and_no_prompts_should_produce_nothing() {
            assertThat(resultDataMapper.build(
                    fixture("judicialResult-with-financialResult-and-empty-prompts.json")))
                    .isNull();
        }

        @Test
        @DisplayName("should return undefined when no result data details present")
        void build_with_no_result_data_details_should_produce_nothing() {
            assertThat(resultDataMapper.build(
                    fixture("judicialResult-with-no-resultdata-details.json")))
                    .isNull();
        }
    }

    @Nested
    @DisplayName("Pinned oddities — a correction here needs a deviations-register entry first")
    class PinnedOddities {

        /**
         * Parity-pack pinning case {@code d11-duration-dates-parsed-as-ddmmyyyy}.
         *
         * <p>The assertion the pack requires, reproduced at the level this mapper works at: a
         * {@code DD/MM/YYYY} duration date is read correctly, and an ISO one is not read at all — it
         * becomes the literal string {@code "Invalid dateZ"} and is shipped into a field the frozen
         * contract types as a date-time. A {@code java.time} port using {@code ISO_LOCAL_DATE}, or a
         * lenient multi-format parser that gets both dates right, must fail here.
         */
        @Test
        @DisplayName("d11 — an ISO duration date is not understood and ships as 'Invalid dateZ'")
        void build_with_an_iso_duration_date_should_ship_the_literal_invalid_date() {
            final JsonNode judicialResult = mapper.readTree("""
                    {"durationElement":{
                       "primaryDurationUnit":"M","primaryDurationValue":5,
                       "secondaryDurationUnit":"Y","secondaryDurationValue":2,
                       "durationStartDate":"03/04/2019","durationEndDate":"2021-07-26"}}""");

            final InformantRegisterResultData resultData = resultDataMapper.build(judicialResult);

            assertThat(resultData.durationStartDate()).isEqualTo("2019-04-03T00:00:00Z");
            assertThat(resultData.durationEndDate()).isEqualTo("Invalid dateZ");
            assertThat(resultData.durationValue()).isEqualTo("5");
            assertThat(resultData.secondaryDurationValue()).isEqualTo("2");
            assertThat(resultData.durationUnit()).isEqualTo("M");
            assertThat(resultData.secondaryDurationUnit()).isEqualTo("Y");
        }

        /**
         * Parity-pack pinning case {@code d09-bst-local-time-labelled-as-utc}, at this mapper's own
         * call site. {@code ResultDataMapper.js:15} sends the next hearing's start through
         * {@code getLocalDateTime}, so a summer next-hearing time is labelled {@code Z} an hour after
         * the instant it names. Asserted on the exact string: parsing it back into an instant would
         * pass for the corrected answer too and would pin nothing.
         */
        @Test
        @DisplayName("d09 — a summer next-hearing time is London-local labelled Z")
        void build_with_a_summer_next_hearing_should_label_london_local_time_as_utc() {
            final JsonNode judicialResult = mapper.readTree("""
                    {"nextHearing":{"listedStartDateTime":"2020-06-01T10:00:00Z",
                                    "courtCentre":{"name":"Lavender Hill"}}}""");

            assertThat(resultDataMapper.build(judicialResult).nextHearingDate())
                    .isEqualTo("2020-06-01T11:00:00Z");
        }
    }

    @Nested
    @DisplayName("Branches the legacy suite never executes (parity-pack BS-10)")
    class UncoveredBranches {

        /**
         * BS-10, first uncovered leg: a next hearing with no {@code listedStartDateTime}. The legacy
         * writes {@code undefined} rather than formatting nothing, so the component is absent while
         * the court location is still mapped.
         */
        @Test
        @DisplayName("BS-10 — a next hearing without a listed start has no next hearing date")
        void build_with_a_next_hearing_without_a_start_should_map_only_the_location() {
            final JsonNode judicialResult = mapper.readTree(
                    "{\"nextHearing\":{\"courtCentre\":{\"name\":\"Lavender Hill\"}}}");

            final InformantRegisterResultData resultData = resultDataMapper.build(judicialResult);

            assertThat(resultData.nextHearingDate()).isNull();
            assertThat(resultData.nextCourtLocation()).isEqualTo("Lavender Hill");
        }

        /**
         * BS-10, second uncovered leg: {@code nextHearing.courtCentre.name} is dereferenced with no
         * guard on {@code courtCentre} (`ResultDataMapper.js:16`), so a next hearing without one is a
         * {@code TypeError} in the legacy and the whole hearing produces nothing. Refused here rather
         * than read as "no location", under deviations-register entry 7 — reading it would emit a
         * register the legacy never sent.
         */
        @Test
        @DisplayName("BS-10 — a next hearing with no court centre is refused, not read as absent")
        void build_with_a_next_hearing_without_a_court_centre_should_refuse() {
            final JsonNode judicialResult = mapper.readTree(
                    "{\"nextHearing\":{\"listedStartDateTime\":\"2020-06-01T10:00:00Z\"}}");

            assertThatThrownBy(() -> resultDataMapper.build(judicialResult))
                    .isInstanceOf(TransformationFailedException.class);
        }

        /**
         * BS-10, remaining uncovered legs: every optional duration component absent. The legacy
         * assigns {@code undefined} to each, so the whole {@code resultData} is present — the
         * duration element itself made it creatable — with nothing in it.
         */
        @Test
        @DisplayName("BS-10 — an empty duration element yields result data with no components")
        void build_with_an_empty_duration_element_should_yield_empty_result_data() {
            final InformantRegisterResultData resultData =
                    resultDataMapper.build(mapper.readTree("{\"durationElement\":{}}"));

            assertThat(resultData).isNotNull();
            assertThat(resultData.durationUnit()).isNull();
            assertThat(resultData.durationValue()).isNull();
            assertThat(resultData.secondaryDurationUnit()).isNull();
            assertThat(resultData.secondaryDurationValue()).isNull();
            assertThat(resultData.durationStartDate()).isNull();
            assertThat(resultData.durationEndDate()).isNull();
        }

        /**
         * {@code .find(prompt => prompt.isFinancialImposition)} (`ResultDataMapper.js:28`) reads a
         * property off every prompt it reaches, so a null one is a {@code TypeError}. Reading it as
         * "not the imposition" instead would emit result data with no amount on it — a register the
         * legacy never sent, reaching a real prosecuting authority.
         */
        @Test
        @DisplayName("a null prompt is refused, not read as a prompt that imposes nothing")
        void build_with_a_null_prompt_should_refuse() {
            final JsonNode judicialResult = mapper.readTree(
                    "{\"isFinancialResult\":true,\"judicialResultPrompts\":[null]}");

            assertThatThrownBy(() -> resultDataMapper.build(judicialResult))
                    .isInstanceOf(TransformationFailedException.class);
        }

        /**
         * The other half of {@code find}'s behaviour: it stops at the first match, so a null prompt
         * <em>after</em> the imposition is never dereferenced and the amount is still reported.
         * Refusing on a pre-pass over the array would lose that register.
         */
        @Test
        @DisplayName("a null prompt after the imposition is never reached, and the amount stands")
        void build_with_a_null_prompt_after_the_match_should_still_report_the_amount() {
            final JsonNode judicialResult = mapper.readTree(
                    "{\"isFinancialResult\":true,\"judicialResultPrompts\":"
                            + "[{\"isFinancialImposition\":true,\"value\":\"£188.00\"},null]}");

            assertThat(resultDataMapper.build(judicialResult).amount()).isEqualTo("£188.00");
        }

        /**
         * BS-10: a financial result whose prompts contain no financial imposition. The legacy still
         * creates the result data — the prompt list is non-empty, which is all
         * {@code canCreateResultData} asks — and simply leaves the amount unset.
         */
        @Test
        @DisplayName("BS-10 — a financial result with no imposition prompt has no amount")
        void build_with_no_financial_imposition_prompt_should_leave_the_amount_unset() {
            final JsonNode judicialResult = mapper.readTree("""
                    {"isFinancialResult":true,
                     "judicialResultPrompts":[{"isFinancialImposition":false,"value":"£10.00"}]}""");

            final InformantRegisterResultData resultData = resultDataMapper.build(judicialResult);

            assertThat(resultData).isNotNull();
            assertThat(resultData.amount()).isNull();
        }

        /**
         * BS-10: the first financial-imposition prompt wins, not the last and not the largest —
         * {@code Array.prototype.find} stops at the first match.
         */
        @Test
        @DisplayName("BS-10 — the first financial-imposition prompt supplies the amount")
        void build_with_two_imposition_prompts_should_take_the_first() {
            final JsonNode judicialResult = mapper.readTree("""
                    {"isFinancialResult":true,
                     "judicialResultPrompts":[{"isFinancialImposition":true,"value":"£1.00"},
                                              {"isFinancialImposition":true,"value":"£2.00"}]}""");

            assertThat(resultDataMapper.build(judicialResult).amount()).isEqualTo("£1.00");
        }

        /**
         * BS-10: prompts present but {@code isFinancialResult} false. The financial half of
         * {@code canCreateResultData} needs all three, so nothing is created at all.
         */
        @Test
        @DisplayName("BS-10 — prompts without the financial flag do not create result data")
        void build_with_prompts_but_no_financial_flag_should_produce_nothing() {
            assertThat(resultDataMapper.build(mapper.readTree(
                    "{\"judicialResultPrompts\":[{\"isFinancialImposition\":true,\"value\":\"£1\"}]}")))
                    .isNull();
        }
    }

    /**
     * Reads one of the byte-identical fixture copies.
     *
     * @param name the fixture file name
     * @return the parsed tree
     */
    private JsonNode fixture(final String name) {
        final String resource = FIXTURES + name;
        try (InputStream source = ResultDataMapperTest.class.getResourceAsStream(resource)) {
            if (source == null) {
                throw new IllegalStateException("missing fixture " + resource);
            }
            return mapper.readTree(source);
        } catch (java.io.IOException cannotRead) {
            throw new IllegalStateException("could not read " + resource, cannotRead);
        }
    }
}
