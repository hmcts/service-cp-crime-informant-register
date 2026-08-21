package uk.gov.hmcts.cp.informantregister.persistence;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.informantregister.application.IdempotencyGuard;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.ProcessedRequestRecord;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.RequestFingerprint;
import uk.gov.hmcts.cp.informantregister.domain.RequestStatus;
import uk.gov.hmcts.cp.informantregister.domain.RunClaim;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport.Row;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A parked request is replayable, and only under a fresh message identity (spec FR-007, SC-003).
 *
 * <p>Which identity a delivery carries is the whole of the decision. A different one is a deliberate
 * resubmission by support: the record goes back to RECEIVED, the attempt count is carried forward
 * rather than reset, the failure reason and the exhausting identity are cleared, and an audit note
 * keeps the reason it was parked. The same one is dead-lettering that did not settle, and it must not
 * run anything.
 *
 * <p>The full SC-003 arithmetic is driven end to end — five failed deliveries, then one replay that
 * succeeds — because `attempts` = 6 is the number a support engineer reads, and it only comes out
 * right if every acquisition increments exactly once and the replay preserves what was there.
 */
class FailedReplayIT {

    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final int PERMITTED_DELIVERIES = 5;

    private final ProcessingMetrics metrics = new ProcessingMetrics(new SimpleMeterRegistry());
    private final IdempotencyGuard guard = ProcessedLogTestSupport.guard(LEASE, metrics);
    private final DistributionCommand command = ProcessedLogTestSupport.command();

    private static DeliveryIdentity delivery(final int number) {
        return new DeliveryIdentity("msg-" + number, "runner-1/delivery-" + number);
    }

    private static RunClaim runClaimOf(final GuardDecision decision) {
        assertThat(decision).isInstanceOf(GuardDecision.Run.class);
        return ((GuardDecision.Run) decision).claim();
    }

    private Row row() {
        return ProcessedLogTestSupport.requireRow(command.source(), command.requestId());
    }

    /** Fails the request through all five permitted deliveries, parking it on the fifth. */
    private void exhaustEveryPermittedDelivery() {
        for (int number = 1; number < PERMITTED_DELIVERIES; number++) {
            guard.recordTransientFailure(
                    runClaimOf(guard.admit(command, delivery(number))),
                    ReasonCode.PIPELINE_TRANSIENT_FAILURE);
        }
        guard.recordExhaustion(
                runClaimOf(guard.admit(command, delivery(PERMITTED_DELIVERIES))),
                ReasonCode.PIPELINE_TRANSIENT_FAILURE,
                delivery(PERMITTED_DELIVERIES));
    }

    @Test
    @DisplayName("five failed deliveries park the request with the identity that exhausted them")
    void the_fifth_failure_should_park_the_request() {
        exhaustEveryPermittedDelivery();

        final Row row = row();
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.attempts()).isEqualTo(PERMITTED_DELIVERIES);
        assertThat(row.failureReason()).isEqualTo("PIPELINE_TRANSIENT_FAILURE");
        assertThat(row.exhaustedMessageId()).isEqualTo("msg-5");
    }

    @Test
    @DisplayName("a fresh identity replays the request and the run proceeds")
    void a_replay_should_readmit_the_request_carrying_its_attempts_forward() {
        exhaustEveryPermittedDelivery();

        final RunClaim replay = runClaimOf(guard.admit(command, delivery(6)));

        final Row row = row();
        assertThat(row.status()).isEqualTo("RECEIVED");
        assertThat(row.attempts()).isEqualTo(PERMITTED_DELIVERIES + 1);
        assertThat(row.failureReason()).isNull();
        assertThat(row.exhaustedMessageId()).isNull();
        assertThat(row.auditNote()).contains("PIPELINE_TRANSIENT_FAILURE");
        assertThat(row.claimOwner()).isEqualTo(replay.owner());
        assertThat(row.claimToken()).isEqualTo(replay.token());
        assertThat(row.claimExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("the replayed run completes, leaving six lifetime attempts (SC-003)")
    void a_successful_replay_should_leave_the_record_completed_with_six_attempts() {
        exhaustEveryPermittedDelivery();

        guard.recordCompletion(
                runClaimOf(guard.admit(command, delivery(6))), CompletionReason.NO_AUTHORITIES);

        final Row row = row();
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.completionReason()).isEqualTo("no-authorities");
        assertThat(row.attempts()).isEqualTo(6);
        assertThat(row.claimOwner()).isNull();
    }

    @Test
    @DisplayName("the identity that exhausted the retries is dead-lettered again, and runs nothing")
    void a_redelivery_of_the_exhausting_identity_should_change_nothing() {
        exhaustEveryPermittedDelivery();
        final Row before = row();

        final GuardDecision decision = guard.admit(command, delivery(PERMITTED_DELIVERIES));

        assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                DeadLetterReason.EXHAUSTED, ReasonCode.DELIVERY_LIMIT_EXHAUSTED));
        assertThat(row()).isEqualTo(before);
    }

    /**
     * Zero rows on the replay update never means "the same identity" — that case is decided on the
     * read. It means the record moved between the read and the update: a concurrent replay won, or
     * the record is no longer parked. Per the no-spin rule the delivery is handed back rather than
     * re-read in a loop, and the broker's redelivery re-enters the state machine against whatever
     * the record says by then.
     *
     * <p>Driven with a repository whose read is deliberately stale, because that is precisely the
     * state the guard would have been in when the race was lost.
     */
    @Test
    @DisplayName("a replay whose record moved under it is handed back, not retried in a loop")
    void a_replay_update_matching_no_row_should_abandon_the_delivery() {
        guard.recordCompletion(
                runClaimOf(guard.admit(command, delivery(1))), CompletionReason.NO_AUTHORITIES);
        final Row before = row();

        final IdempotencyGuard staleReadingGuard = new IdempotencyGuard(
                new ProcessedRequestRepository(ProcessedLogTestSupport.jdbcClient(), LEASE) {
                    @Override
                    public Optional<ProcessedRequestRecord> read(
                            final String source, final UUID requestId) {
                        return Optional.of(new ProcessedRequestRecord(
                                RequestStatus.FAILED,
                                RequestFingerprint.of(command),
                                ReasonCode.PIPELINE_TRANSIENT_FAILURE.code(),
                                "msg-5",
                                5,
                                null,
                                null));
                    }
                },
                metrics);

        final GuardDecision decision = staleReadingGuard.admit(command, delivery(6));

        assertThat(decision).isEqualTo(new GuardDecision.Abandon(ReasonCode.REPLAY_NOT_ADMITTED));
        assertThat(row()).isEqualTo(before);
    }
}
