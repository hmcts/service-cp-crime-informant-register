package uk.gov.hmcts.cp.informantregister.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One resolution, two destinations — and they must never disagree.
 *
 * <p>This type answers "who is this run made as?" exactly once, and two things then read that
 * answer: {@link CallerIdentity#orSystem(String)} fills the {@code CJSCPPUID} header on every
 * outbound call, and {@link CallerIdentity#label()} names the choice on the delivery's receipt line
 * so support can settle an attribution complaint.
 *
 * <p>The label began life in the transport adapter, derived independently — truthfully, because the
 * two conditions were exact negations of each other. That is the arrangement this suite exists to
 * make impossible to return to. A future condition on {@code of(...)} — a nil-uuid guard, a
 * deactivated-user rule, a switch forcing the system identity — would have left the receipt line
 * printing {@code message-user} while the header carried the configured identity, and a log field
 * that lies about attribution is worse than no field at all, because settling attribution
 * complaints is its only purpose.
 */
class CallerIdentityTest {

    private static final String CONFIGURED_IDENTITY = "0dd0dd0d-dead-beef-cafe-facade000002";

    private final UUID sharingUser = UUID.randomUUID();

    private DistributionCommand commandSharedBy(final UUID userId) {
        return new DistributionCommand(
                "RESULTS", UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 8, 21),
                Instant.parse("2026-08-21T08:00:00Z"), "Hearing_Resulted",
                Optional.ofNullable(userId));
    }

    @Nested
    @DisplayName("the label and the header")
    class LabelAndHeader {

        /**
         * The agreement itself, stated as the property rather than as two remembered strings.
         */
        @Test
        @DisplayName("agree that a named user is the one the calls go out as")
        void should_label_a_run_message_user_exactly_when_the_header_carries_the_messages_user() {
            final CallerIdentity identity = CallerIdentity.of(commandSharedBy(sharingUser));

            assertThat(identity.label()).isEqualTo("message-user");
            assertThat(identity.orSystem(CONFIGURED_IDENTITY))
                    .as("the label claims the message's user, so the header must carry it")
                    .isEqualTo(sharingUser.toString());
        }

        @Test
        @DisplayName("agree that an unnamed user falls back to the configured identity")
        void should_label_a_run_system_identity_exactly_when_the_header_carries_the_configured_one() {
            final CallerIdentity identity = CallerIdentity.of(commandSharedBy(null));

            assertThat(identity.label()).isEqualTo("system-identity");
            assertThat(identity.orSystem(CONFIGURED_IDENTITY))
                    .as("the label claims the fallback, so the header must carry the configured one")
                    .isEqualTo(CONFIGURED_IDENTITY);
        }

        /**
         * The property both cases above are instances of, asserted as itself.
         *
         * <p>Whatever rules {@code of(...)} grows, the label says {@code system-identity} on exactly
         * those runs whose header carries the configured identity. A reviewer adding a condition has
         * to satisfy this, not merely remember to update two literals.
         */
        @Test
        @DisplayName("cannot disagree, whatever rules the resolution grows")
        void should_never_label_a_run_in_a_way_the_header_contradicts() {
            for (final CallerIdentity identity : new CallerIdentity[] {
                    CallerIdentity.of(commandSharedBy(sharingUser)),
                    CallerIdentity.of(commandSharedBy(null)),
                    CallerIdentity.SYSTEM}) {

                final boolean labelledSystem = "system-identity".equals(identity.label());
                final boolean headerIsConfigured =
                        CONFIGURED_IDENTITY.equals(identity.orSystem(CONFIGURED_IDENTITY));

                assertThat(labelledSystem)
                        .as("label=%s but header=%s", identity.label(),
                                identity.orSystem(CONFIGURED_IDENTITY))
                        .isEqualTo(headerIsConfigured);
            }
        }
    }

    @Nested
    @DisplayName("the label's vocabulary")
    class Vocabulary {

        /**
         * It is a log token, so its text is a contract with the log index and the searches saved
         * against it. Renaming it silently would leave every existing query returning nothing.
         */
        @Test
        @DisplayName("is a bounded pair of words, not free text")
        void should_only_ever_produce_one_of_the_two_agreed_tokens() {
            assertThat(CallerIdentity.SYSTEM.label()).isEqualTo("system-identity");
            assertThat(CallerIdentity.of(commandSharedBy(sharingUser)).label())
                    .isEqualTo("message-user");
        }

        @Test
        @DisplayName("never contains the user it is standing in for")
        void should_never_carry_the_user_id_in_the_label() {
            assertThat(CallerIdentity.of(commandSharedBy(sharingUser)).label())
                    .as("which identity, never whose")
                    .doesNotContain(sharingUser.toString());
        }
    }
}
