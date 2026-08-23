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
     * The canonical hearing identifier, chosen to contain a-f hex digits in every group.
     *
     * <p>That is not decoration. An identifier of digits alone is unchanged by upper-casing, so a
     * case-normalisation test written against one asserts nothing at all.
     */
    private static final String HEARING_ID = "a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5";

    /**
     * SHA-256 of
     * {@code a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5|2026-08-20|2026-08-20T09:00:00Z|Hearing_Resulted},
     * computed independently of this codebase.
     */
    private static final String GOLDEN_FINGERPRINT =
            "24c81c4737a070294090814da857583c50d0d3aab2556ac7be03d0f0f6d910e7";

    private static final String CANONICAL_BODY = """
            {
              "source": "RESULTS",
              "requestId": "3f4a2b1c-5d6e-4f70-8912-a3b4c5d6e7f8",
              "hearingId": "a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5",
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
                UUID.fromString(HEARING_ID),
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
            final String upperCaseId = HEARING_ID.toUpperCase(java.util.Locale.ROOT);
            final String uppercased = CANONICAL_BODY.replace(HEARING_ID, upperCaseId);

            // Guard against the test quietly becoming a tautology again: if the identifier is ever
            // swapped for one without hex letters, these two wire forms are the same string and the
            // assertion below proves nothing.
            assertThat(upperCaseId).isNotEqualTo(HEARING_ID);
            assertThat(uppercased).isNotEqualTo(CANONICAL_BODY);

            // Pins where the normalisation happens. The data model says components are normalised
            // after parsing, and this is that claim made testable: were the command ever to carry
            // the identifier as wire text rather than a parsed UUID, this fails.
            assertThat(parser.parse(uppercased).hearingId()).hasToString(HEARING_ID);

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

        @Test
        void the_sharing_user_should_not_change_the_fingerprint() {
            // The fingerprint answers "is this the same unit of work?", and the user who happened to
            // share the results is not part of that. It matters most on the recovery path: a support
            // replay of an attributed request carries no user at all, and a fingerprint that
            // included one would call that replay an idempotency collision and dead-letter the very
            // message somebody sent to fix the problem.
            final String attributed = CANONICAL_BODY.replace(
                    "\"eventType\": \"Hearing_Resulted\"",
                    "\"eventType\": \"Hearing_Resulted\",\n  \"userId\": "
                            + "\"0b7a5c2e-4d19-4a6b-8c30-9e1f5d7b2a48\"");

            assertThat(attributed).isNotEqualTo(CANONICAL_BODY);
            assertThat(parser.parse(attributed).userId()).isPresent();
            assertThat(fingerprintOf(attributed)).isEqualTo(GOLDEN_FINGERPRINT);
        }
    }

    @Nested
    @DisplayName("a changed immutable field is a different request")
    class ChangedImmutableField {

        @Test
        void a_different_hearing_should_change_the_fingerprint() {
            assertThat(fingerprintOf(
                    CANONICAL_BODY.replace(HEARING_ID, "f9e8d7c6-b5a4-4392-8180-7f6e5d4c3b2a")))
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
