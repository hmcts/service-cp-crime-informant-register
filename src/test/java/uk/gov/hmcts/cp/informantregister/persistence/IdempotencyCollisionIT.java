package uk.gov.hmcts.cp.informantregister.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gov.hmcts.cp.informantregister.application.IdempotencyGuard;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.RunClaim;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport.Row;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same key carrying a different request is a collision, not a duplicate (spec FR-018).
 *
 * <p>A duplicate is absorbed; a collision is a producer that has minted one identity for two
 * different requests, and absorbing it would silently drop one of them. So it is parked visibly, the
 * existing record is not touched at all, and nothing runs.
 *
 * <p>One case per immutable field, because the fingerprint has to cover all four. A suite that varied
 * `hearingId` alone would pass against a fingerprint of `hearingId` alone, and a re-share arriving
 * under a recycled identity would then overwrite the record of the original.
 */
class IdempotencyCollisionIT {

    private static final Duration LEASE = Duration.ofMinutes(5);

    private final IdempotencyGuard guard = ProcessedLogTestSupport.guard(LEASE);
    private final DistributionCommand original = ProcessedLogTestSupport.command();

    /** A request differing from the original in exactly one immutable field. */
    private record Variant(String field, DistributionCommand command) {
        @Override
        public String toString() {
            return field;
        }
    }

    static Stream<Variant> collidingRequests() {
        final DistributionCommand base = ProcessedLogTestSupport.command();
        return Stream.of(
                new Variant("hearingId", withHearingId(base, UUID.randomUUID())),
                new Variant("hearingDay", withHearingDay(base, LocalDate.of(2026, 8, 21))),
                new Variant("sharedTime", withSharedTime(base, Instant.parse("2026-08-20T10:00:00Z"))),
                new Variant("eventType", withEventType(base, "Hearing_Shared")));
    }

    private static DistributionCommand withHearingId(
            final DistributionCommand command, final UUID hearingId) {
        return new DistributionCommand(command.source(), command.requestId(), hearingId,
                command.hearingDay(), command.sharedTime(), command.eventType());
    }

    private static DistributionCommand withHearingDay(
            final DistributionCommand command, final LocalDate hearingDay) {
        return new DistributionCommand(command.source(), command.requestId(), command.hearingId(),
                hearingDay, command.sharedTime(), command.eventType());
    }

    private static DistributionCommand withSharedTime(
            final DistributionCommand command, final Instant sharedTime) {
        return new DistributionCommand(command.source(), command.requestId(), command.hearingId(),
                command.hearingDay(), sharedTime, command.eventType());
    }

    private static DistributionCommand withEventType(
            final DistributionCommand command, final String eventType) {
        return new DistributionCommand(command.source(), command.requestId(), command.hearingId(),
                command.hearingDay(), command.sharedTime(), eventType);
    }

    /** The same variant, re-keyed onto the record this test has already written. */
    private DistributionCommand collidingWith(final Variant variant) {
        return new DistributionCommand(
                original.source(),
                original.requestId(),
                variant.command().hearingId(),
                variant.command().hearingDay(),
                variant.command().sharedTime(),
                variant.command().eventType());
    }

    private Row row() {
        return ProcessedLogTestSupport.requireRow(original.source(), original.requestId());
    }

    private static RunClaim runClaimOf(final GuardDecision decision) {
        assertThat(decision).isInstanceOf(GuardDecision.Run.class);
        return ((GuardDecision.Run) decision).claim();
    }

    private RunClaim recordOriginal() {
        return runClaimOf(guard.admit(original, new DeliveryIdentity("msg-1", "runner-1/delivery-1")));
    }

    @ParameterizedTest(name = "a different {0}")
    @MethodSource("collidingRequests")
    @DisplayName("a request that differs in any immutable field is parked, and the record left alone")
    void a_colliding_request_should_be_dead_lettered_without_touching_the_record(final Variant variant) {
        recordOriginal();
        final Row before = row();

        final GuardDecision decision = guard.admit(
                collidingWith(variant), new DeliveryIdentity("msg-2", "runner-2/delivery-1"));

        assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                DeadLetterReason.COLLISION, ReasonCode.IDEMPOTENCY_COLLISION));
        assertThat(row()).isEqualTo(before);
    }

    @Test
    @DisplayName("a collision is decided before the state is, so a completed record collides too")
    void a_colliding_request_should_be_parked_whatever_state_the_record_is_in() {
        guard.recordCompletion(recordOriginal(), CompletionReason.NO_AUTHORITIES);
        final Row before = row();

        final GuardDecision decision = guard.admit(
                collidingWith(new Variant("hearingId", withHearingId(original, UUID.randomUUID()))),
                new DeliveryIdentity("msg-2", "runner-2/delivery-1"));

        assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                DeadLetterReason.COLLISION, ReasonCode.IDEMPOTENCY_COLLISION));
        assertThat(row()).isEqualTo(before);
    }

    @Test
    @DisplayName("no run is started for a collision")
    void a_collision_should_not_start_a_run() {
        recordOriginal();

        guard.admit(collidingWith(new Variant("hearingDay", withHearingDay(original,
                LocalDate.of(2026, 8, 21)))), new DeliveryIdentity("msg-2", "runner-2/delivery-1"));

        // The claim the original run holds is untouched, and no second run start was counted.
        assertThat(row().attempts()).isEqualTo(1);
        assertThat(row().claimOwner()).isEqualTo("runner-1/delivery-1");
    }

    @Test
    @DisplayName("an identical request is a duplicate, not a collision")
    void the_same_request_arriving_again_should_not_be_treated_as_a_collision() {
        guard.recordCompletion(recordOriginal(), CompletionReason.NO_AUTHORITIES);

        final GuardDecision decision =
                guard.admit(original, new DeliveryIdentity("msg-2", "runner-2/delivery-1"));

        assertThat(decision).isEqualTo(new GuardDecision.Complete(ReasonCode.ALREADY_COMPLETED));
    }
}
