package uk.gov.hmcts.cp.informantregister.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.RunClaim;
import uk.gov.hmcts.cp.informantregister.persistence.ProcessedRequestRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The other half of the exhaustion rule: hand-backs that come from the <em>outcome writes</em>.
 *
 * <p>{@code AdmissionExhaustionTest} covers the three hand-backs the guard produces when it refuses
 * to admit a delivery, and {@code IdempotencyGuard.admit} escalates all of them over one surface.
 * {@code STALE_RUNNER} is not one of them. It is returned when a terminal write affects no row —
 * this runner's claim was reclaimed while it worked — and that happens after admission, on the way
 * out, so the admission-side escalation never sees it.
 *
 * <p>Left unescalated it is the same silent failure, and under {@code recordExhaustion} it is sharper
 * than any of the three: that method is <strong>only ever called on the final permitted delivery</strong>,
 * so a reclaimed claim there hands back a delivery that has nothing left. Service Bus makes an
 * abandoned message available again immediately, so the broker parks it under its own reason — no
 * reason code of ours, no {@code deadlettered} metric, nothing in the log index naming a stale
 * runner. A rise in stale runners is exactly the signal that leases are too short, and it would be
 * invisible in the one place it matters most.
 *
 * <p><strong>Why no row is written.</strong> The same invariant as the admission paths, and here it
 * is stronger. A stale runner is a non-holder by definition — the write it just attempted was
 * predicated on {@code claim_owner} and {@code claim_token} and affected nothing — and the row now
 * belongs to the runner that reclaimed it, which may be succeeding at this moment. Writing around
 * the predicate would overwrite a live run's outcome with a superseded one's. What the budget buys is
 * attribution, not state.
 *
 * <p>The claim under test is obtained from {@code admit} rather than hand-built, so what is asserted
 * is the whole path a real delivery takes: the budget is known at admission and has to survive as far
 * as the outcome write, which is the part that was missing.
 */
class OutcomeWriteExhaustionTest {

    private static final String SOURCE = "RESULTS";
    private static final String MESSAGE_ID = "RESULTS:final-delivery";
    private static final String OWNER = "this-runner/lock-token";

    private final ProcessedRequestRepository repository = mock(ProcessedRequestRepository.class);
    private final ProcessingMetrics metrics = new ProcessingMetrics(new SimpleMeterRegistry());
    private final IdempotencyGuard guard = new IdempotencyGuard(repository, metrics);

    private final DistributionCommand command = new DistributionCommand(
            SOURCE,
            UUID.randomUUID(),
            UUID.randomUUID(),
            LocalDate.of(2026, 8, 20),
            Instant.parse("2026-08-20T09:00:00Z"),
            "Hearing_Resulted");

    /** The delivery the broker will not send again: the budget ends here. */
    private DeliveryIdentity finalPermittedDelivery() {
        return new DeliveryIdentity(MESSAGE_ID, OWNER, true);
    }

    private DeliveryIdentity deliveryWithBudgetRemaining() {
        return new DeliveryIdentity(MESSAGE_ID, OWNER, false);
    }

    /**
     * The claim a granted run actually holds, taken from the guard rather than assembled here.
     */
    private RunClaim claimGrantedOn(final DeliveryIdentity delivery) {
        when(repository.insertNew(any(), anyString(), any(RunClaim.class))).thenReturn(true);
        final GuardDecision granted = guard.admit(command, delivery);
        assertThat(granted)
                .as("the run has to have been granted for its outcome write to mean anything")
                .isInstanceOf(GuardDecision.Run.class);
        return ((GuardDecision.Run) granted).claim();
    }

    /** Every terminal write affects nothing: the claim was reclaimed while this runner worked. */
    private void theClaimWasReclaimed() {
        when(repository.recordCompleted(any(RunClaim.class), anyString())).thenReturn(false);
        when(repository.recordRetrying(any(RunClaim.class), anyString())).thenReturn(false);
        when(repository.recordFailed(any(RunClaim.class), anyString())).thenReturn(false);
    }

    private static GuardDecision parkedWith(final ReasonCode reason) {
        return new GuardDecision.DeadLetter(DeadLetterReason.EXHAUSTED, reason);
    }

    @ParameterizedTest
    @EnumSource(value = ReasonCode.class, names = {
        "PROCESSING_DEADLINE_EXCEEDED", "REFERENCE_DATA_UNAVAILABLE",
        "PIPELINE_TRANSIENT_FAILURE", "UNEXPECTED_FAILURE"
    })
    void recordExhaustion_should_preserve_the_failure_reason_in_the_row_and_dead_letter(
            final ReasonCode reason) {
        final RunClaim claim = claimGrantedOn(finalPermittedDelivery());
        when(repository.recordFailed(claim, reason.code())).thenReturn(true);

        final GuardDecision decision = guard.recordExhaustion(claim, reason);

        assertThat(decision).isEqualTo(parkedWith(reason));
        verify(repository).recordFailed(claim, reason.code());
    }

    @Nested
    @DisplayName("a run that exhausted the budget and then lost its claim")
    class Exhaustion {

        /**
         * The sharpest case: {@code recordExhaustion} is reached only on the final delivery, so a
         * hand-back from here is always a hand-back with nothing behind it.
         */
        @Test
        @DisplayName("is parked with our own reason rather than handed back into nothing")
        void recordExhaustion_on_a_reclaimed_claim_should_park_rather_than_abandon() {
            final RunClaim claim = claimGrantedOn(finalPermittedDelivery());
            theClaimWasReclaimed();

            final GuardDecision decision =
                    guard.recordExhaustion(claim, ReasonCode.PIPELINE_TRANSIENT_FAILURE);

            assertThat(decision).isEqualTo(parkedWith(ReasonCode.STALE_RUNNER));
        }

        @Test
        @DisplayName("writes nothing: the row belongs to the runner that reclaimed it")
        void recordExhaustion_on_a_reclaimed_claim_should_not_write_the_holders_row() {
            final RunClaim claim = claimGrantedOn(finalPermittedDelivery());
            theClaimWasReclaimed();

            guard.recordExhaustion(claim, ReasonCode.PIPELINE_TRANSIENT_FAILURE);

            verify(repository, never()).recordRetrying(any(RunClaim.class), anyString());
            verify(repository, never()).recordCompleted(any(RunClaim.class), anyString());
        }
    }

    @Nested
    @DisplayName("a run that lost its claim with deliveries still to come")
    class BudgetRemaining {

        @Test
        @DisplayName("is still handed back, because a redelivery can still resolve it")
        void a_reclaimed_claim_with_budget_left_should_hand_the_delivery_back() {
            final RunClaim claim = claimGrantedOn(deliveryWithBudgetRemaining());
            theClaimWasReclaimed();

            final GuardDecision decision =
                    guard.recordTransientFailure(claim, ReasonCode.PIPELINE_TRANSIENT_FAILURE);

            assertThat(decision).isEqualTo(new GuardDecision.Abandon(ReasonCode.STALE_RUNNER));
        }
    }

    /**
     * The rule is the claim's, not any one write's.
     *
     * <p>All four outcome writes fall back to the same rejection, so escalating inside that one
     * rejection is what makes a fifth write added later inherit the rule instead of having to
     * remember it — the same reasoning that put the admission-side escalation over the whole
     * decision rather than at its three sites.
     */
    @Nested
    @DisplayName("every outcome write, on the final permitted delivery")
    class EveryWrite {

        @Test
        @DisplayName("parks a completion whose claim was reclaimed")
        void recordCompletion_on_a_reclaimed_claim_should_park_on_the_final_delivery() {
            final RunClaim claim = claimGrantedOn(finalPermittedDelivery());
            theClaimWasReclaimed();

            assertThat(guard.recordCompletion(claim, CompletionReason.NO_AUTHORITIES))
                    .isEqualTo(parkedWith(ReasonCode.STALE_RUNNER));
        }

        @Test
        @DisplayName("parks a transient failure whose claim was reclaimed")
        void recordTransientFailure_on_a_reclaimed_claim_should_park_on_the_final_delivery() {
            final RunClaim claim = claimGrantedOn(finalPermittedDelivery());
            theClaimWasReclaimed();

            assertThat(guard.recordTransientFailure(
                    claim, ReasonCode.PIPELINE_TRANSIENT_FAILURE))
                    .isEqualTo(parkedWith(ReasonCode.STALE_RUNNER));
        }

        @Test
        @DisplayName("parks a non-transient failure whose claim was reclaimed")
        void recordNonTransientFailure_on_a_reclaimed_claim_should_park_on_the_final_delivery() {
            final RunClaim claim = claimGrantedOn(finalPermittedDelivery());
            theClaimWasReclaimed();

            assertThat(guard.recordNonTransientFailure(
                    claim, ReasonCode.TRANSFORMATION_FAILED))
                    .isEqualTo(parkedWith(ReasonCode.STALE_RUNNER));
        }
    }
}
