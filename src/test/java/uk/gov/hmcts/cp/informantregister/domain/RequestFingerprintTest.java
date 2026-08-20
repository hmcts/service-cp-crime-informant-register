package uk.gov.hmcts.cp.informantregister.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.inbound.DistributionCommandParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the fingerprint's canonicalisation.
 *
 * <p>The point of the rule is that the wire text is irrelevant: a request written with uppercase-hex
 * identifiers, or with an offset instead of {@code Z}, or with trailing zeros in its fractional
 * seconds, is the same request and must produce the same hash. Only a genuine change to an immutable
 * field is a collision — anything looser turns a harmless republish into a dead-letter.
 *
 * <p>Cases that begin from wire text go through the real parser, because the data model normalises
 * <em>after</em> parsing, and a fingerprint computed from an already-normalised record could not
 * prove that.
 */
class RequestFingerprintTest {

    /**
     * SHA-256 of
     * {@code 11111111-2222-4333-8444-555555555555|2026-08-20|2026-08-20T09:00:00Z|Hearing_Resulted},
     * computed independently of this codebase.
     */
    private static final String GOLDEN_FINGERPRINT =
            "567eca5ee9b28127ed93d836f646cec0964f9a99b3993637cc57e4ba84523c77";

    private static final String CANONICAL_BODY = """
            {
              "source": "RESULTS",
              "requestId": "3f4a2b1c-5d6e-4f70-8912-a3b4c5d6e7f8",
              "hearingId": "11111111-2222-4333-8444-555555555555",
              "hearingDay": "2026-08-20",
              "sharedTime": "2026-08-20T09:00:00Z",
              "eventType": "Hearing_Resulted"
            }
            """;

    private final DistributionCommandParser parser =
            new DistributionCommandParser(JacksonConfig.contractObjectMapper());

    private String fingerprintOf(final String body) {
        return RequestFingerprint.of(parser.parse(body));
    }

    private static DistributionCommand command() {
        return new DistributionCommand(
                "RESULTS",
                UUID.fromString("3f4a2b1c-5d6e-4f70-8912-a3b4c5d6e7f8"),
                UUID.fromString("11111111-2222-4333-8444-555555555555"),
                LocalDate.of(2026, 8, 20),
                Instant.parse("2026-08-20T09:00:00Z"),
                "Hearing_Resulted");
    }

    @Nested
    @DisplayName("the hash itself")
    class TheHash {

        @Test
        void of_the_canonical_command_should_match_the_independently_computed_digest() {
            assertThat(RequestFingerprint.of(command())).isEqualTo(GOLDEN_FINGERPRINT);
        }

        @Test
        void of_any_command_should_be_sixty_four_lowercase_hex_characters() {
            assertThat(RequestFingerprint.of(command())).matches("^[0-9a-f]{64}$");
        }
    }

    @Nested
    @DisplayName("wire text that means the same request")
    class EquivalentWireText {

        @Test
        void an_uppercase_hex_identifier_should_produce_the_same_fingerprint() {
            final String uppercased = CANONICAL_BODY
                    .replace("11111111-2222-4333-8444-555555555555",
                            "11111111-2222-4333-8444-555555555555".toUpperCase(java.util.Locale.ROOT));

            assertThat(fingerprintOf(uppercased)).isEqualTo(GOLDEN_FINGERPRINT);
        }

        @Test
        void an_offset_bearing_instant_should_produce_the_same_fingerprint_as_its_utc_equivalent() {
            final String offsetBearing =
                    CANONICAL_BODY.replace("2026-08-20T09:00:00Z", "2026-08-20T10:00:00+01:00");

            assertThat(fingerprintOf(offsetBearing)).isEqualTo(GOLDEN_FINGERPRINT);
        }

        @Test
        void trailing_zeros_in_the_fraction_should_produce_the_same_fingerprint() {
            final String withTrailingZeros =
                    CANONICAL_BODY.replace("2026-08-20T09:00:00Z", "2026-08-20T09:00:00.000Z");

            assertThat(fingerprintOf(withTrailingZeros)).isEqualTo(GOLDEN_FINGERPRINT);
        }

        @Test
        void a_different_identity_should_not_change_the_fingerprint() {
            // source and requestId are the key the fingerprint is compared under, not part of what
            // is compared.
            final String differentRequestId = CANONICAL_BODY
                    .replace("3f4a2b1c-5d6e-4f70-8912-a3b4c5d6e7f8",
                            "99999999-8888-4777-8666-555555555555");

            assertThat(fingerprintOf(differentRequestId)).isEqualTo(GOLDEN_FINGERPRINT);
        }
    }

    @Nested
    @DisplayName("a changed immutable field is a different request")
    class ChangedImmutableField {

        @Test
        void a_different_hearing_should_change_the_fingerprint() {
            assertThat(fingerprintOf(CANONICAL_BODY
                    .replace("11111111-2222-4333-8444-555555555555",
                            "22222222-3333-4444-8555-666666666666")))
                    .isNotEqualTo(GOLDEN_FINGERPRINT);
        }

        @Test
        void a_different_hearing_day_should_change_the_fingerprint() {
            assertThat(fingerprintOf(CANONICAL_BODY.replace("\"2026-08-20\"", "\"2026-08-21\"")))
                    .isNotEqualTo(GOLDEN_FINGERPRINT);
        }

        @Test
        void a_different_shared_time_should_change_the_fingerprint() {
            assertThat(fingerprintOf(
                    CANONICAL_BODY.replace("2026-08-20T09:00:00Z", "2026-08-20T09:00:00.123Z")))
                    .isNotEqualTo(GOLDEN_FINGERPRINT);
        }

        @Test
        void a_different_event_type_should_change_the_fingerprint() {
            final DistributionCommand other = new DistributionCommand(
                    command().source(),
                    command().requestId(),
                    command().hearingId(),
                    command().hearingDay(),
                    command().sharedTime(),
                    "Hearing_Reshared");

            assertThat(RequestFingerprint.of(other)).isNotEqualTo(GOLDEN_FINGERPRINT);
        }
    }
}
