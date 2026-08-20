package uk.gov.hmcts.cp.informantregister.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.inbound.DistributionCommandParser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parser's own behaviour: what a valid body becomes, and which bounded reason each class of
 * invalid body earns.
 *
 * <p>Agreement between this parser and the committed schema is the separate concern of
 * {@link DistributionCommandSchemaCorpusTest}.
 */
class DistributionCommandParserTest {

    private static final String VALID_BODY = """
            {
              "source": "RESULTS",
              "requestId": "3f4a2b1c-5d6e-4f70-8912-a3b4c5d6e7f8",
              "hearingId": "11111111-2222-4333-8444-555555555555",
              "hearingDay": "2026-08-20",
              "sharedTime": "2026-08-20T09:00:00Z",
              "eventType": "Hearing_Resulted"
            }
            """;

    private final ObjectMapper objectMapper = JacksonConfig.contractObjectMapper();
    private final DistributionCommandParser parser = new DistributionCommandParser(objectMapper);

    private static ContractViolation violationOf(final Throwable thrown) {
        return ((ContractValidationException) thrown).violation();
    }

    @Nested
    @DisplayName("a valid body")
    class ValidBody {

        @Test
        void parse_a_canonical_body_should_yield_the_six_typed_fields() {
            final DistributionCommand command = parser.parse(VALID_BODY);

            assertThat(command.source()).isEqualTo("RESULTS");
            assertThat(command.requestId())
                    .isEqualTo(UUID.fromString("3f4a2b1c-5d6e-4f70-8912-a3b4c5d6e7f8"));
            assertThat(command.hearingId())
                    .isEqualTo(UUID.fromString("11111111-2222-4333-8444-555555555555"));
            assertThat(command.hearingDay()).isEqualTo(LocalDate.of(2026, 8, 20));
            assertThat(command.sharedTime()).isEqualTo(Instant.parse("2026-08-20T09:00:00Z"));
            assertThat(command.eventType()).isEqualTo("Hearing_Resulted");
        }

        @Test
        void parse_an_uppercase_identifier_should_normalise_it() {
            final DistributionCommand command = parser.parse(VALID_BODY
                    .replace("3f4a2b1c-5d6e-4f70-8912-a3b4c5d6e7f8",
                            "3F4A2B1C-5D6E-4F70-8912-A3B4C5D6E7F8"));

            assertThat(command.requestId())
                    .isEqualTo(UUID.fromString("3f4a2b1c-5d6e-4f70-8912-a3b4c5d6e7f8"));
        }

        @Test
        void parse_an_offset_bearing_instant_should_normalise_it_to_utc() {
            final DistributionCommand command = parser.parse(
                    VALID_BODY.replace("2026-08-20T09:00:00Z", "2026-08-20T10:00:00+01:00"));

            assertThat(command.sharedTime()).isEqualTo(Instant.parse("2026-08-20T09:00:00Z"));
        }

        @Test
        void parse_an_instant_with_fractional_seconds_should_keep_the_fraction() {
            final DistributionCommand command = parser.parse(
                    VALID_BODY.replace("2026-08-20T09:00:00Z", "2026-08-20T09:00:00.123456Z"));

            assertThat(command.sharedTime()).isEqualTo(Instant.parse("2026-08-20T09:00:00.123456Z"));
        }
    }

    @Nested
    @DisplayName("an invalid body earns a bounded reason")
    class InvalidBody {

        @Test
        void parse_a_body_that_is_not_json_should_report_malformed_json() {
            assertThatThrownBy(() -> parser.parse("this is not json"))
                    .isInstanceOf(ContractValidationException.class)
                    .satisfies(thrown ->
                            assertThat(violationOf(thrown)).isEqualTo(ContractViolation.MALFORMED_JSON));
        }

        @Test
        void parse_a_json_array_should_report_that_the_body_is_not_an_object() {
            assertThatThrownBy(() -> parser.parse("[]"))
                    .isInstanceOf(ContractValidationException.class)
                    .satisfies(thrown ->
                            assertThat(violationOf(thrown)).isEqualTo(ContractViolation.NOT_AN_OBJECT));
        }

        @Test
        void parse_a_body_missing_a_required_field_should_name_that_field() {
            final String withoutHearingDay = VALID_BODY.replace("  \"hearingDay\": \"2026-08-20\",\n", "");

            assertThatThrownBy(() -> parser.parse(withoutHearingDay))
                    .isInstanceOf(ContractValidationException.class)
                    .satisfies(thrown -> {
                        assertThat(violationOf(thrown)).isEqualTo(ContractViolation.MISSING_FIELD);
                        assertThat(((ContractValidationException) thrown).field()).isEqualTo("hearingDay");
                    });
        }

        @Test
        void parse_a_body_with_a_null_required_field_should_report_a_missing_field() {
            assertThatThrownBy(() -> parser.parse(
                    VALID_BODY.replace("\"RESULTS\"", "null")))
                    .isInstanceOf(ContractValidationException.class)
                    .satisfies(thrown ->
                            assertThat(violationOf(thrown)).isEqualTo(ContractViolation.MISSING_FIELD));
        }

        @Test
        void parse_a_body_with_an_empty_required_field_should_report_a_missing_field() {
            assertThatThrownBy(() -> parser.parse(
                    VALID_BODY.replace("\"RESULTS\"", "\"\"")))
                    .isInstanceOf(ContractValidationException.class)
                    .satisfies(thrown ->
                            assertThat(violationOf(thrown)).isEqualTo(ContractViolation.MISSING_FIELD));
        }

        @Test
        void parse_a_body_with_an_unknown_field_should_name_that_field() {
            final String withExtra = VALID_BODY.replace(
                    "  \"eventType\": \"Hearing_Resulted\"",
                    "  \"eventType\": \"Hearing_Resulted\",\n  \"courtCentreId\": \"abc\"");

            assertThatThrownBy(() -> parser.parse(withExtra))
                    .isInstanceOf(ContractValidationException.class)
                    .satisfies(thrown -> {
                        assertThat(violationOf(thrown)).isEqualTo(ContractViolation.UNKNOWN_FIELD);
                        assertThat(((ContractValidationException) thrown).field()).isEqualTo("courtCentreId");
                    });
        }

        @Test
        void parse_a_body_with_an_unagreed_source_should_report_an_invalid_enum_value() {
            assertThatThrownBy(() -> parser.parse(VALID_BODY.replace("\"RESULTS\"", "\"SJP\"")))
                    .isInstanceOf(ContractValidationException.class)
                    .satisfies(thrown -> {
                        assertThat(violationOf(thrown)).isEqualTo(ContractViolation.INVALID_ENUM_VALUE);
                        assertThat(((ContractValidationException) thrown).field()).isEqualTo("source");
                    });
        }

        @Test
        void parse_a_body_with_an_unagreed_event_type_should_report_an_invalid_enum_value() {
            assertThatThrownBy(() -> parser.parse(
                    VALID_BODY.replace("\"Hearing_Resulted\"", "\"SJP_Resulted\"")))
                    .isInstanceOf(ContractValidationException.class)
                    .satisfies(thrown -> {
                        assertThat(violationOf(thrown)).isEqualTo(ContractViolation.INVALID_ENUM_VALUE);
                        assertThat(((ContractValidationException) thrown).field()).isEqualTo("eventType");
                    });
        }

        @Test
        void parse_a_body_with_a_non_canonical_identifier_should_report_an_invalid_format() {
            assertThatThrownBy(() -> parser.parse(VALID_BODY.replace(
                    "3f4a2b1c-5d6e-4f70-8912-a3b4c5d6e7f8", "3f4a2b1c5d6e4f708912a3b4c5d6e7f8")))
                    .isInstanceOf(ContractValidationException.class)
                    .satisfies(thrown -> {
                        assertThat(violationOf(thrown)).isEqualTo(ContractViolation.INVALID_FORMAT);
                        assertThat(((ContractValidationException) thrown).field()).isEqualTo("requestId");
                    });
        }

        @Test
        void parse_a_body_with_a_date_that_does_not_exist_should_report_an_invalid_format() {
            assertThatThrownBy(() -> parser.parse(VALID_BODY.replace("2026-08-20\"", "2026-02-30\"")))
                    .isInstanceOf(ContractValidationException.class)
                    .satisfies(thrown -> {
                        assertThat(violationOf(thrown)).isEqualTo(ContractViolation.INVALID_FORMAT);
                        assertThat(((ContractValidationException) thrown).field()).isEqualTo("hearingDay");
                    });
        }

        @Test
        void parse_a_body_whose_field_is_the_wrong_json_type_should_report_an_invalid_format() {
            assertThatThrownBy(() -> parser.parse(VALID_BODY.replace("\"RESULTS\"", "42")))
                    .isInstanceOf(ContractValidationException.class)
                    .satisfies(thrown ->
                            assertThat(violationOf(thrown)).isEqualTo(ContractViolation.INVALID_FORMAT));
        }

        @Test
        void a_rejection_should_never_quote_the_offending_value() {
            assertThatThrownBy(() -> parser.parse(VALID_BODY.replace("\"RESULTS\"", "\"SJP\"")))
                    .isInstanceOf(ContractValidationException.class)
                    .hasMessageNotContaining("SJP");
        }
    }

    @Nested
    @DisplayName("the shared mapper")
    class SharedMapper {

        @Test
        void a_json_number_with_a_fraction_should_materialise_as_a_big_decimal() {
            // Principle IV: monetary values must round-trip exactly, so the shared mapper is
            // configured with USE_BIG_DECIMAL_FOR_FLOATS — no binary-float drift into a register.
            final JsonNode tree = objectMapper.readTree("{\"amount\": 1234.56}");

            assertThat(tree.get("amount").isBigDecimal()).isTrue();
            assertThat(tree.get("amount").decimalValue()).isEqualTo(new BigDecimal("1234.56"));
        }

        @Test
        void a_high_precision_number_should_not_lose_a_digit() {
            final JsonNode tree = objectMapper.readTree("{\"amount\": 0.1234567890123456789}");

            assertThat(tree.get("amount").decimalValue())
                    .isEqualTo(new BigDecimal("0.1234567890123456789"));
        }
    }
}
