package uk.gov.hmcts.cp.informantregister.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.ProcessedRequestRecord;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.RequestFingerprint;
import uk.gov.hmcts.cp.informantregister.domain.RequestStatus;
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
 * What the guard does with a delivery it cannot admit when the delivery budget has run out.
 *
 * <p>Three admission paths hand a delivery back rather than running it: the claim could not be taken
 * ({@code CLAIM_NOT_ACQUIRED}), a parked record moved under the replay ({@code REPLAY_NOT_ADMITTED}),
 * and the record was absent after the insert race ({@code RECORD_ABSENT}). All three currently return
 * {@code Abandon} unconditionally, and {@code DeliveryIdentity#finalPermittedDelivery} is consulted
 * only inside a run — never on an admission path.
 *
 * <p>That is the defect. Service Bus makes an abandoned message available immediately, with no
 * back-off, so an admission hand-back that keeps recurring consumes the whole delivery budget
 * back-to-back and the broker parks the message under <em>its own</em> reason: no reason code of
 * ours, no {@code deadlettered} metric, nothing in the log index to search for. The service that
 * exists to end silent failure fails silently.
 *
 * <p><strong>What this suite does not assert, and why.</strong> It does not expect a {@code FAILED}
 * row. On all three of these paths the runner holds no claim — it never acquired one — and every
 * terminal write is predicated on {@code claim_owner} and {@code claim_token}, so it has nothing to
 * write with. Nor may it write around that predicate: {@code CLAIM_NOT_ACQUIRED} cannot distinguish
 * a dead holder from a live one (the guard deliberately refuses the second read that would tell them
 * apart), so parking the request from underneath the holder would overwrite the outcome of a run that
 * is still succeeding. The guard's own invariant is that a non-holder writes nothing, and a runner
 * that never held the claim is less entitled to write than a superseded one. What the budget buys is
 * therefore attribution, not state: the parking becomes ours, with a bounded reason, a metric and a
 * log line, and the row is left to whoever owns it.
 */
class AdmissionExhaustionTest {

    private static final String SOURCE = "RESULTS";
    private static final String MESSAGE_ID = "RESULTS:final-delivery";
    private static final String OWNER = "this-runner/lock-token";
    private static final String HELD_BY_ANOTHER = "another-runner/another-lock-token";

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

    /** A delivery with budget left behind it. */
    private DeliveryIdentity deliveryWithBudgetRemaining() {
        return new DeliveryIdentity(MESSAGE_ID, OWNER, false);
    }

    /** Whatever the branch, the request is already on the log: the insert never wins. */
    private void requestAlreadyRecorded() {
        when(repository.insertNew(any(), anyString(), any(RunClaim.class))).thenReturn(false);
    }

    private void recordReadsAs(final ProcessedRequestRecord record) {
        when(repository.read(SOURCE, command.requestId())).thenReturn(Optional.of(record));
    }

    /** Nothing terminal was written, by any route. */
    private void assertNothingWasParked() {
        verify(repository, never()).recordFailed(any(RunClaim.class), anyString());
        verify(repository, never()).recordRetrying(any(RunClaim.class), anyString());
        verify(repository, never()).recordCompleted(any(RunClaim.class), anyString());
    }

    @Nested
    @DisplayName("the claim is held and could not be taken")
    class ClaimNotAcquired {

        private void aLiveClaimHeldByAnotherRunner() {
            requestAlreadyRecorded();
            recordReadsAs(new ProcessedRequestRecord(
                    RequestStatus.RECEIVED,
                    RequestFingerprint.of(command),
                    null,
                    null,
                    1,
                    HELD_BY_ANOTHER,
                    Instant.parse("2026-08-20T09:04:30Z")));
            when(repository.reclaimStaleClaim(any(RunClaim.class))).thenReturn(false);
        }

        @Test
        @DisplayName("on the final permitted delivery it is parked with our own reason")
        void admit_on_the_final_delivery_should_park_rather_than_abandon() {
            aLiveClaimHeldByAnotherRunner();

            final GuardDecision decision = guard.admit(command, finalPermittedDelivery());

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.CLAIM_NOT_ACQUIRED));
        }

        @Test
        @DisplayName("parking it writes nothing: the row belongs to the runner holding the claim")
        void admit_on_the_final_delivery_should_not_write_the_holders_row() {
            aLiveClaimHeldByAnotherRunner();

            guard.admit(command, finalPermittedDelivery());

            assertNothingWasParked();
        }

        @Test
        @DisplayName("with deliveries remaining it is still handed back for redelivery")
        void admit_with_budget_remaining_should_hand_the_delivery_back() {
            aLiveClaimHeldByAnotherRunner();

            final GuardDecision decision = guard.admit(command, deliveryWithBudgetRemaining());

            assertThat(decision)
                    .isEqualTo(new GuardDecision.Abandon(ReasonCode.CLAIM_NOT_ACQUIRED));
        }
    }

    @Nested
    @DisplayName("a parked record moved under the replay")
    class ReplayNotAdmitted {

        private void aParkedRecordThatChangesUnderTheReplay() {
            requestAlreadyRecorded();
            recordReadsAs(new ProcessedRequestRecord(
                    RequestStatus.FAILED,
                    RequestFingerprint.of(command),
                    ReasonCode.PIPELINE_TRANSIENT_FAILURE.code(),
                    "RESULTS:the-delivery-that-exhausted-the-budget",
                    5,
                    null,
                    null));
            when(repository.replayFailed(any(RunClaim.class), anyString())).thenReturn(false);
        }

        @Test
        @DisplayName("on the final permitted delivery it is parked with our own reason")
        void admit_on_the_final_delivery_should_park_rather_than_abandon() {
            aParkedRecordThatChangesUnderTheReplay();

            final GuardDecision decision = guard.admit(command, finalPermittedDelivery());

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.REPLAY_NOT_ADMITTED));
        }

        @Test
        @DisplayName("with deliveries remaining it is still handed back for redelivery")
        void admit_with_budget_remaining_should_hand_the_delivery_back() {
            aParkedRecordThatChangesUnderTheReplay();

            final GuardDecision decision = guard.admit(command, deliveryWithBudgetRemaining());

            assertThat(decision)
                    .isEqualTo(new GuardDecision.Abandon(ReasonCode.REPLAY_NOT_ADMITTED));
        }
    }

    @Nested
    @DisplayName("the record was absent after the insert race")
    class RecordAbsent {

        private void noRecordToReadBack() {
            requestAlreadyRecorded();
            when(repository.read(SOURCE, command.requestId())).thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("on the final permitted delivery it is parked with our own reason")
        void admit_on_the_final_delivery_should_park_rather_than_abandon() {
            noRecordToReadBack();

            final GuardDecision decision = guard.admit(command, finalPermittedDelivery());

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.RECORD_ABSENT));
        }

        @Test
        @DisplayName("with deliveries remaining it is still handed back for redelivery")
        void admit_with_budget_remaining_should_hand_the_delivery_back() {
            noRecordToReadBack();

            final GuardDecision decision = guard.admit(command, deliveryWithBudgetRemaining());

            assertThat(decision)
                    .isEqualTo(new GuardDecision.Abandon(ReasonCode.RECORD_ABSENT));
        }
    }
}
