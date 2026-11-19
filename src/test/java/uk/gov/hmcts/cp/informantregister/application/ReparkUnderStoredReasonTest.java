package uk.gov.hmcts.cp.informantregister.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
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
import uk.gov.hmcts.cp.informantregister.support.CapturedLog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FR-009: a re-park of an already-{@code FAILED} request must dead-letter under the request's own
 * recorded cause, unless that cause is itself a data anomaly — missing or unrecognised — in which
 * case the category is forced to {@code EXHAUSTED} regardless of what {@code parking_category}
 * happens to record, because a recorded {@code non-transient} category answers for the specific
 * cause the anomaly means this service can no longer identify.
 */
class ReparkUnderStoredReasonTest {

    private static final String SOURCE = "RESULTS";
    private static final String MESSAGE_ID = "RESULTS:redelivery";
    private static final String OWNER = "this-runner/lock-token";
    private static final long PARKING_SEQUENCE_NUMBER = 42L;

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

    /** A redelivery of the exact sequence number that parked the request: a re-park, not a replay. */
    private DeliveryIdentity redeliveryOfTheParkingSequenceNumber() {
        return new DeliveryIdentity(
                MESSAGE_ID, OWNER, false, PARKING_SEQUENCE_NUMBER, 1L,
                Instant.parse("2026-08-20T09:04:00Z"));
    }

    private void requestAlreadyRecorded() {
        when(repository.insertNew(any(), anyString(), any(RunClaim.class))).thenReturn(false);
    }

    private void recordReadsAs(final ProcessedRequestRecord record) {
        when(repository.read(SOURCE, command.requestId())).thenReturn(Optional.of(record));
    }

    private ProcessedRequestRecord failedRowParkedUnder(
            final String storedFailureReason, final String storedParkingCategory) {
        return new ProcessedRequestRecord(
                RequestStatus.FAILED,
                RequestFingerprint.of(command),
                storedFailureReason,
                "RESULTS:the-delivery-that-parked-it",
                5,
                null,
                null,
                PARKING_SEQUENCE_NUMBER,
                storedParkingCategory,
                Instant.parse("2026-08-20T09:04:00Z"));
    }

    @Test
    @DisplayName("an unrecognised stored reason re-parks as exhausted/UNEXPECTED_FAILURE, "
            + "not the recorded non-transient category")
    void repark_with_unrecognised_reason_should_force_exhausted_and_unexpected_failure() {
        requestAlreadyRecorded();
        recordReadsAs(failedRowParkedUnder("NO_LONGER_A_DECLARED_REASON", "NON_TRANSIENT"));

        final GuardDecision decision = guard.admit(command, redeliveryOfTheParkingSequenceNumber());

        assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                DeadLetterReason.EXHAUSTED, ReasonCode.UNEXPECTED_FAILURE));
    }

    @Test
    @DisplayName("a missing stored reason re-parks as exhausted/UNEXPECTED_FAILURE, "
            + "not the recorded non-transient category")
    void repark_with_missing_reason_should_force_exhausted_and_unexpected_failure() {
        requestAlreadyRecorded();
        recordReadsAs(failedRowParkedUnder(null, "NON_TRANSIENT"));

        final GuardDecision decision = guard.admit(command, redeliveryOfTheParkingSequenceNumber());

        assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                DeadLetterReason.EXHAUSTED, ReasonCode.UNEXPECTED_FAILURE));
    }

    @Test
    @DisplayName("a recognised stored reason still re-parks under its own recorded category")
    void repark_with_recognised_reason_should_use_the_recorded_category() {
        requestAlreadyRecorded();
        recordReadsAs(failedRowParkedUnder(
                ReasonCode.SUBMISSION_REJECTED.code(), "NON_TRANSIENT"));

        final GuardDecision decision = guard.admit(command, redeliveryOfTheParkingSequenceNumber());

        assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                DeadLetterReason.NON_TRANSIENT, ReasonCode.SUBMISSION_REJECTED));
    }

    @Test
    @DisplayName("the receipt line never emits the raw unrecognised reason, "
            + "even though the re-park decision is correct")
    void repark_with_unrecognised_reason_should_not_log_the_raw_text() {
        final String corruptReason = "A_REASON_THIS_SERVICE_NO_LONGER_DECLARES";
        requestAlreadyRecorded();
        recordReadsAs(failedRowParkedUnder(corruptReason, "NON_TRANSIENT"));

        try (CapturedLog log = CapturedLog.of(IdempotencyGuard.class)) {
            guard.admit(command, redeliveryOfTheParkingSequenceNumber());

            final String receiptLine = log.messages().stream()
                    .filter(message -> message.contains("Delivery matched an existing record"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no receipt line was logged"));
            assertThat(receiptLine).contains("priorFailureReason=unrecognised");
            assertThat(log.messages()).noneMatch(message -> message.contains(corruptReason));
        }
    }

    @Test
    @DisplayName("a legacy FAILED row (no recorded parking sequence number) re-parks the same way "
            + "when its stored reason is unrecognised")
    void legacy_repark_with_unrecognised_reason_should_force_exhausted_and_unexpected_failure() {
        requestAlreadyRecorded();
        // No recorded parking sequence number or category: the legacy state (FR-005, FR-010). A
        // delivery count of 1 (not 0) fails the legacy-resubmission heuristic unconditionally, so
        // this reaches the re-park branch — legacyReplayOrPark's own call to
        // reparkUnderStoredReason — rather than attemptReplay.
        recordReadsAs(new ProcessedRequestRecord(
                RequestStatus.FAILED,
                RequestFingerprint.of(command),
                "A_REASON_THIS_SERVICE_NO_LONGER_DECLARES",
                "RESULTS:the-delivery-that-parked-it",
                5,
                null,
                null,
                null,
                null,
                Instant.parse("2026-08-20T09:04:00Z")));
        final DeliveryIdentity notAResubmission = new DeliveryIdentity(
                MESSAGE_ID, OWNER, false, PARKING_SEQUENCE_NUMBER, 1L,
                Instant.parse("2026-08-20T09:10:00Z"));

        final GuardDecision decision = guard.admit(command, notAResubmission);

        assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                DeadLetterReason.EXHAUSTED, ReasonCode.UNEXPECTED_FAILURE));
    }
}
