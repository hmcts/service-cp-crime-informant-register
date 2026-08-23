package uk.gov.hmcts.cp.informantregister.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * Every message and every {@code toString()} in the exception's whole cause chain.
     *
     * <p>Asserting on the outer message alone is not enough: a retained cause travels with the
     * exception into the dead-letter description and the log index, and a parser's own message
     * routinely quotes the input it choked on.
     */
    private static List<String> chainText(final Throwable thrown) {
        final List<String> texts = new ArrayList<>();
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            texts.add(String.valueOf(current.getMessage()));
            texts.add(current.toString());
        }
        return texts;
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

    /**
     * The sharing user, which a message may carry and may equally leave out.
     *
     * <p>Both shapes have to parse, because both are published: the producer names the user who
     * shared the results, while a replay that does not carry the original body — and every producer
     * build predating the field — names nobody. A present value is held to the identifier shape the schema declares — an
     * attribution that is not an identity is worse than none, because it reaches a downstream
     * service as a {@code CJSCPPUID} it will refuse.
     */
    @Nested
    @DisplayName("the optional sharing user")
    class SharingUser {

        private static final String USER = "0b7a5c2e-4d19-4a6b-8c30-9e1f5d7b2a48";

        private String bodyWithUser(final String rawValue) {
            return VALID_BODY.replace("\"eventType\": \"Hearing_Resulted\"",
                    "\"eventType\": \"Hearing_Resulted\",\n  \"userId\": " + rawValue);
        }

        @Test
        void parse_a_body_naming_a_user_should_carry_that_user() {
            final DistributionCommand command = parser.parse(bodyWithUser("\"" + USER + "\""));

            assertThat(command.userId()).contains(UUID.fromString(USER));
        }

        @Test
        void parse_a_body_naming_no_user_should_carry_none() {
            final DistributionCommand command = parser.parse(VALID_BODY);

            assertThat(command.userId()).isEmpty();
        }

        @Test
        void parse_an_uppercase_user_should_normalise_it() {
            final DistributionCommand command = parser.parse(
                    bodyWithUser("\"" + USER.toUpperCase(java.util.Locale.ROOT) + "\""));

            assertThat(command.userId()).contains(UUID.fromString(USER));
        }

        @Test
        void parse_a_user_that_is_not_a_uuid_should_report_an_invalid_format() {
            assertThatThrownBy(() -> parser.parse(bodyWithUser("\"not-a-uuid\"")))
                    .isInstanceOf(ContractValidationException.class)
                    .satisfies(thrown ->
                            assertThat(violationOf(thrown)).isEqualTo(ContractViolation.INVALID_FORMAT));
        }

        @Test
        void parse_a_user_in_a_non_canonical_layout_should_report_an_invalid_format() {
            // UUID.fromString would accept the abbreviated form; the contract's `uuid` format does
            // not, and the identity that goes on the wire must be the one the producer sent.
            assertThatThrownBy(() -> parser.parse(bodyWithUser("\"0b7a5c2e4d194a6b8c309e1f5d7b2a48\"")))
                    .isInstanceOf(ContractValidationException.class)
                    .satisfies(thrown ->
                            assertThat(violationOf(thrown)).isEqualTo(ContractViolation.INVALID_FORMAT));
        }

        @Test
        void parse_an_explicitly_null_user_should_report_an_invalid_format() {
            // Absence is how a message says there is no user. An explicit null is a present property
            // of the wrong type, which the schema refuses, so the parser refuses it too rather than
            // quietly reading it as "no user".
            assertThatThrownBy(() -> parser.parse(bodyWithUser("null")))
                    .isInstanceOf(ContractValidationException.class)
                    .satisfies(thrown ->
                            assertThat(violationOf(thrown)).isEqualTo(ContractViolation.INVALID_FORMAT));
        }

        @Test
        void parse_a_blank_user_should_report_an_invalid_format() {
            assertThatThrownBy(() -> parser.parse(bodyWithUser("\"\"")))
                    .isInstanceOf(ContractValidationException.class)
                    .satisfies(thrown ->
                            assertThat(violationOf(thrown)).isEqualTo(ContractViolation.INVALID_FORMAT));
        }

        @Test
        void parse_a_user_that_is_not_a_string_should_report_an_invalid_format() {
            assertThatThrownBy(() -> parser.parse(bodyWithUser("42")))
                    .isInstanceOf(ContractValidationException.class)
                    .satisfies(thrown ->
                            assertThat(violationOf(thrown)).isEqualTo(ContractViolation.INVALID_FORMAT));
        }

        @Test
        void parse_a_rejected_user_should_never_quote_the_value_back() {
            // The value identifies a person. It must not reach a dead-letter description or a log
            // index on its way to being refused — only the field's name, which the contract owns.
            assertThatThrownBy(() -> parser.parse(bodyWithUser("\"" + USER + "-not-a-uuid\"")))
                    .isInstanceOf(ContractValidationException.class)
                    .satisfies(thrown -> assertThat(chainText(thrown))
                            .as("no rejection may carry the identity it refused")
                            .noneMatch(text -> text.contains(USER)));
        }

        @Test
        void parse_a_body_naming_a_user_should_keep_it_out_of_the_correlation_set() {
            // Correlation is what a support engineer searches by, and it reaches the log index. A
            // user identifier is not a correlation key here; the record, the hearing and the day are.
            final DistributionCommandParser.Correlation correlation =
                    parser.canonicalCorrelation(bodyWithUser("\"" + USER + "\""));

            assertThat(correlation.toString()).doesNotContain(USER);
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

        /**
         * Deliberately <em>not</em> the producer's own name.
         *
         * <p>The name is chosen by the far end and this rejection travels into a dead-letter
         * description and a log index verbatim. Bounding its shape was not enough: a perfectly
         * well-formed identifier can carry a token, a surname or a payload fragment, and it would
         * be written out because it looked like a field name. Nothing is lost — the reader needs to
         * know that a field arrived which this service does not know about, the producer's release
         * notes say which one, and the body itself is on the dead-letter queue.
         */
        @Test
        void parse_a_body_with_an_unknown_field_should_report_a_placeholder_and_not_the_name() {
            final String withExtra = VALID_BODY.replace(
                    "  \"eventType\": \"Hearing_Resulted\"",
                    "  \"eventType\": \"Hearing_Resulted\",\n  \"courtCentreId\": \"abc\"");

            assertThatThrownBy(() -> parser.parse(withExtra))
                    .isInstanceOf(ContractValidationException.class)
                    .satisfies(thrown -> {
                        assertThat(violationOf(thrown)).isEqualTo(ContractViolation.UNKNOWN_FIELD);
                        assertThat(((ContractValidationException) thrown).field())
                                .isEqualTo("<unknown-field>")
                                .doesNotContain("courtCentreId");
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
    @DisplayName("no rejection leaks any part of the body")
    class NoLeakage {

        private void assertNothingInTheChainMentions(final String body, final String... forbidden) {
            final Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> parser.parse(body));

            assertThat(thrown).isInstanceOf(ContractValidationException.class);
            assertThat(chainText(thrown))
                    .as("the whole cause chain of %s", thrown)
                    .allSatisfy(text -> assertThat(text).doesNotContain(forbidden));
        }

        @Test
        void a_malformed_body_should_not_travel_with_the_parser_exception_that_read_it() {
            final String body = "{\"source\": \"MARKER-c0ffee\", \"requestId\": ";

            assertNothingInTheChainMentions(body, "MARKER-c0ffee");
        }

        @Test
        void a_malformed_body_rejection_should_retain_no_cause_at_all() {
            // The underlying parser exception is translated, not wrapped: its message is written by
            // a library that quotes the source it choked on, and nothing downstream needs it.
            assertThatThrownBy(() -> parser.parse("{\"source\": \"MARKER-c0ffee\", "))
                    .isInstanceOf(ContractValidationException.class)
                    .hasNoCause();
        }

        @Test
        void an_impossible_date_should_not_travel_with_the_date_parser_exception() {
            assertNothingInTheChainMentions(
                    VALID_BODY.replace("2026-08-20\"", "2026-02-30\""), "2026-02-30");
        }

        @Test
        void an_out_of_contract_instant_should_not_travel_with_the_time_parser_exception() {
            assertNothingInTheChainMentions(
                    VALID_BODY.replace("2026-08-20T09:00:00Z", "2026-08-20T09:00:00+01:00:30"),
                    "+01:00:30", "2026-08-20T09:00:00+01:00:30");
        }

        @Test
        void an_out_of_contract_identifier_should_not_be_quoted() {
            assertNothingInTheChainMentions(
                    VALID_BODY.replace("3f4a2b1c-5d6e-4f70-8912-a3b4c5d6e7f8", "MARKER-identifier"),
                    "MARKER-identifier");
        }

        @Test
        void a_value_outside_an_enumeration_should_not_be_quoted() {
            assertNothingInTheChainMentions(
                    VALID_BODY.replace("\"RESULTS\"", "\"MARKER-source\""), "MARKER-source");
        }

        @Test
        void a_value_of_the_wrong_type_should_not_be_quoted() {
            assertNothingInTheChainMentions(VALID_BODY.replace("\"RESULTS\"", "4242424242"), "4242424242");
        }

        @Test
        void a_hostile_unknown_field_name_should_be_replaced_rather_than_echoed() {
            // The placeholder is now unconditional rather than reserved for names that fail a shape
            // check: a well-formed identifier is just as capable of carrying somebody else's text.
            final String withHostileField = VALID_BODY.replace(
                    "  \"eventType\": \"Hearing_Resulted\"",
                    "  \"eventType\": \"Hearing_Resulted\",\n  \"<script>MARKER</script>\": \"x\"");

            assertNothingInTheChainMentions(withHostileField, "MARKER", "<script>");
            assertThatThrownBy(() -> parser.parse(withHostileField))
                    .isInstanceOf(ContractValidationException.class)
                    .hasMessageContaining("<unknown-field>");
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
