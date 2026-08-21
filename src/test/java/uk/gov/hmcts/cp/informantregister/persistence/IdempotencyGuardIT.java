package uk.gov.hmcts.cp.informantregister.persistence;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import uk.gov.hmcts.cp.informantregister.domain.RunClaim;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport.Row;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One case per row of the data model's transition table, plus the guard decisions that are not
 * transitions, driven through the guard against a real processed log.
 *
 * <p>The table is the contract this suite exists to hold: every branch is asserted on both of the
 * things it produces — the decision the delivery is handed, and the row the database is left
 * holding. A branch that returned the right decision while writing the wrong row would be a duplicate
 * register in production and a green build here, so neither half is taken on trust.
 *
 * <p>Each test mints its own request, so the cases share the container without sharing state.
 */
class IdempotencyGuardIT {

    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final String OWNER = "runner-1/delivery-1";
    private static final String OTHER_OWNER = "runner-2/delivery-1";

    private final ProcessingMetrics metrics = new ProcessingMetrics(new SimpleMeterRegistry());
    private final IdempotencyGuard guard = ProcessedLogTestSupport.guard(LEASE, metrics);
    private final DistributionCommand command = ProcessedLogTestSupport.command();

    // --- helpers ---------------------------------------------------------------------------

    private static DeliveryIdentity delivery(final String messageId, final String owner) {
        return new DeliveryIdentity(messageId, owner);
    }

    private static DeliveryIdentity delivery(final String messageId) {
        return delivery(messageId, OWNER);
    }

    private Row row() {
        return ProcessedLogTestSupport.requireRow(command.source(), command.requestId());
    }

    private static RunClaim runClaimOf(final GuardDecision decision) {
        assertThat(decision).isInstanceOf(GuardDecision.Run.class);
        return ((GuardDecision.Run) decision).claim();
    }

    /** Admits a delivery and returns the claim it may run under. */
    private RunClaim admitted(final String messageId) {
        return runClaimOf(guard.admit(command, delivery(messageId)));
    }

    /** Drives the record to RETRYING, leaving no claim held. */
    private void driveToRetrying(final String messageId) {
        guard.recordTransientFailure(admitted(messageId), ReasonCode.PIPELINE_TRANSIENT_FAILURE);
    }

    /** Drives the record to FAILED under the given exhausting identity. */
    private void driveToFailed(final String messageId) {
        guard.recordExhaustion(admitted(messageId), ReasonCode.PIPELINE_TRANSIENT_FAILURE);
    }

    // --- (none) -> RECEIVED ------------------------------------------------------------------

    @Nested
    @DisplayName("a request never seen before")
    class NewRequest {

        @Test
        void should_be_recorded_received_with_the_claim_and_the_first_attempt() {
            final RunClaim claim = admitted("msg-1");

            final Row row = row();
            assertThat(row.status()).isEqualTo("RECEIVED");
            assertThat(row.attempts()).isEqualTo(1);
            assertThat(row.claimOwner()).isEqualTo(OWNER);
            assertThat(row.claimToken()).isEqualTo(claim.token());
            assertThat(row.claimExpiresAt()).isNotNull();
            assertThat(claim.source()).isEqualTo(command.source());
            assertThat(claim.requestId()).isEqualTo(command.requestId());
        }

        @Test
        void should_store_the_request_and_its_fingerprint_verbatim() {
            admitted("msg-1");

            final Row row = row();
            assertThat(row.hearingId()).isEqualTo(command.hearingId());
            assertThat(row.hearingDay()).isEqualTo(command.hearingDay());
            assertThat(row.sharedTime()).isEqualTo(command.sharedTime());
            assertThat(row.eventType()).isEqualTo(command.eventType());
            assertThat(row.requestFingerprint()).isEqualTo(RequestFingerprint.of(command));
        }

        @Test
        void should_leave_every_outcome_column_empty() {
            admitted("msg-1");

            final Row row = row();
            assertThat(row.completionReason()).isNull();
            assertThat(row.failureReason()).isNull();
            assertThat(row.exhaustedMessageId()).isNull();
            assertThat(row.auditNote()).isNull();
        }
    }

    // --- RECEIVED / RETRYING -> COMPLETED ----------------------------------------------------

    @Nested
    @DisplayName("a run that succeeds")
    class RunSucceeds {

        @Test
        void should_complete_a_received_request_and_release_the_claim() {
            final RunClaim claim = admitted("msg-1");

            final GuardDecision decision = guard.recordCompletion(claim, CompletionReason.NO_AUTHORITIES);

            assertThat(decision).isEqualTo(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));
            final Row row = row();
            assertThat(row.status()).isEqualTo("COMPLETED");
            assertThat(row.completionReason()).isEqualTo("no-authorities");
            assertThat(row.claimOwner()).isNull();
            assertThat(row.claimToken()).isNull();
            assertThat(row.claimExpiresAt()).isNull();
            assertThat(row.attempts()).isEqualTo(1);
        }

        @Test
        void should_complete_a_retrying_request_carrying_its_attempts_forward() {
            driveToRetrying("msg-1");

            final RunClaim second = admitted("msg-2");
            guard.recordCompletion(second, CompletionReason.NO_AUTHORITIES);

            final Row row = row();
            assertThat(row.status()).isEqualTo("COMPLETED");
            assertThat(row.attempts()).isEqualTo(2);
            assertThat(row.claimOwner()).isNull();
        }

        @Test
        void should_move_the_update_timestamp_on() {
            final RunClaim claim = admitted("msg-1");
            // Seeded an hour back, so "moved on" can be asserted strictly. Against a timestamp
            // written moments earlier, a statement that forgot `updated_at = now()` would still
            // satisfy "not before what it was", and the assertion would be no assertion at all.
            ProcessedLogTestSupport.ageUpdatedAt(command.source(), command.requestId());
            final Row before = row();

            guard.recordCompletion(claim, CompletionReason.NO_AUTHORITIES);

            assertThat(row().updatedAt()).isAfter(before.updatedAt());
            assertThat(row().createdAt()).isEqualTo(before.createdAt());
        }
    }

    // --- RECEIVED / RETRYING -> RETRYING -----------------------------------------------------

    @Nested
    @DisplayName("a run that fails with deliveries remaining")
    class RunFailsTransiently {

        @Test
        void should_record_the_failure_release_the_claim_and_ask_for_redelivery() {
            final RunClaim claim = admitted("msg-1");

            final GuardDecision decision =
                    guard.recordTransientFailure(claim, ReasonCode.PIPELINE_TRANSIENT_FAILURE);

            assertThat(decision)
                    .isEqualTo(new GuardDecision.Abandon(ReasonCode.PIPELINE_TRANSIENT_FAILURE));
            final Row row = row();
            assertThat(row.status()).isEqualTo("RETRYING");
            assertThat(row.failureReason()).isEqualTo("PIPELINE_TRANSIENT_FAILURE");
            assertThat(row.claimOwner()).isNull();
            assertThat(row.claimToken()).isNull();
            assertThat(row.claimExpiresAt()).isNull();
            assertThat(row.exhaustedMessageId()).isNull();
            assertThat(row.attempts()).isEqualTo(1);
        }

        @Test
        void should_stay_retrying_and_count_every_run_when_it_fails_again() {
            driveToRetrying("msg-1");
            driveToRetrying("msg-2");

            final Row row = row();
            assertThat(row.status()).isEqualTo("RETRYING");
            assertThat(row.attempts()).isEqualTo(2);
        }
    }

    // --- RECEIVED / RETRYING -> FAILED -------------------------------------------------------

    @Nested
    @DisplayName("a run that fails on the final permitted delivery")
    class RunExhaustsDeliveries {

        @Test
        void should_park_the_request_with_the_exhausting_identity_and_ask_for_dead_lettering() {
            final RunClaim claim = admitted("msg-5");

            final GuardDecision decision =
                    guard.recordExhaustion(claim, ReasonCode.PIPELINE_TRANSIENT_FAILURE);

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.DELIVERY_LIMIT_EXHAUSTED));
            final Row row = row();
            assertThat(row.status()).isEqualTo("FAILED");
            assertThat(row.failureReason()).isEqualTo("PIPELINE_TRANSIENT_FAILURE");
            // Written by the same statement as the state, so a FAILED row can never exist without
            // the identity that parked it — the V1 check would refuse it in any case.
            assertThat(row.exhaustedMessageId()).isEqualTo("msg-5");
            assertThat(row.claimOwner()).isNull();
            assertThat(row.claimToken()).isNull();
            assertThat(row.claimExpiresAt()).isNull();
        }

        @Test
        void should_park_the_delivery_that_was_running_rather_than_any_other() {
            // The claim carries the identity of the delivery that acquired it, so the request can
            // only ever be parked under the delivery that was actually running. Parked under some
            // other identity, the record would replay when that delivery came back and re-park when
            // the real one did — the two halves of FR-007 pointing at the wrong messages.
            final RunClaim claim = admitted("msg-5");
            guard.recordCompletion(claim, CompletionReason.NO_AUTHORITIES);
            final RunClaim second = admitted("msg-9");

            guard.recordExhaustion(second, ReasonCode.PIPELINE_TRANSIENT_FAILURE);

            assertThat(row().exhaustedMessageId()).isEqualTo(second.messageId()).isEqualTo("msg-9");
        }

        @Test
        void should_park_a_retrying_request_the_same_way() {
            driveToRetrying("msg-1");

            driveToFailed("msg-2");

            final Row row = row();
            assertThat(row.status()).isEqualTo("FAILED");
            assertThat(row.exhaustedMessageId()).isEqualTo("msg-2");
            assertThat(row.attempts()).isEqualTo(2);
        }
    }

    // --- FAILED -> RECEIVED, and FAILED -> FAILED --------------------------------------------

    @Nested
    @DisplayName("a delivery for a parked request")
    class ParkedRequest {

        @Test
        void should_replay_under_a_fresh_identity_and_run_again() {
            driveToFailed("msg-5");

            final RunClaim replay = admitted("msg-6");

            final Row row = row();
            assertThat(row.status()).isEqualTo("RECEIVED");
            assertThat(row.attempts()).isEqualTo(2);
            assertThat(row.failureReason()).isNull();
            assertThat(row.exhaustedMessageId()).isNull();
            assertThat(row.auditNote()).contains("PIPELINE_TRANSIENT_FAILURE");
            assertThat(row.claimToken()).isEqualTo(replay.token());
        }

        @Test
        void should_stay_parked_under_the_identity_that_exhausted_the_retries() {
            driveToFailed("msg-5");
            final Row before = row();

            final GuardDecision decision = guard.admit(command, delivery("msg-5"));

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.DELIVERY_LIMIT_EXHAUSTED));
            assertThat(row()).isEqualTo(before);
        }
    }

    // --- COMPLETED is terminal ---------------------------------------------------------------

    @Nested
    @DisplayName("a delivery for a completed request")
    class CompletedRequest {

        @Test
        void should_be_acknowledged_without_a_run_and_leave_the_row_alone() {
            guard.recordCompletion(admitted("msg-1"), CompletionReason.NO_AUTHORITIES);
            final Row before = row();

            final GuardDecision decision = guard.admit(command, delivery("msg-2", OTHER_OWNER));

            assertThat(decision).isEqualTo(new GuardDecision.Complete(ReasonCode.ALREADY_COMPLETED));
            assertThat(row()).isEqualTo(before);
        }
    }

    // --- RETRYING redelivered after a long gap ------------------------------------------------

    @Nested
    @DisplayName("a retrying request redelivered after a long gap")
    class RetryingRedelivered {

        @Test
        void should_run_again_under_a_fresh_claim_with_the_state_left_where_it_was() {
            driveToRetrying("msg-1");
            final Row before = row();

            final RunClaim reclaimed = admitted("msg-2");

            final Row row = row();
            // The reclaim moves the claim and the attempt counter, never the state: only an outcome
            // moves the state, and this delivery has not produced one yet.
            assertThat(row.status()).isEqualTo("RETRYING");
            assertThat(row.attempts()).isEqualTo(2);
            assertThat(row.claimOwner()).isEqualTo(OWNER);
            assertThat(row.claimToken()).isEqualTo(reclaimed.token());
            assertThat(row.claimToken()).isNotEqualTo(before.claimToken());
            assertThat(row.failureReason()).isEqualTo(before.failureReason());
        }
    }

    // --- the record that was not there --------------------------------------------------------

    @Nested
    @DisplayName("a record that vanished between the insert and the read")
    class VanishedRecord {

        /**
         * Nothing in this service deletes a processed-request row — the schema even refuses a delete
         * that would orphan an output. The branch still has to exist, because the alternative to
         * deciding something is an unhandled exception on the consumer thread, so it is proven here
         * with a repository whose read comes back empty.
         */
        @Test
        void should_be_handed_back_to_the_broker_rather_than_guessed_at() {
            admitted("msg-1");

            final IdempotencyGuard blindGuard = new IdempotencyGuard(
                    new ProcessedRequestRepository(ProcessedLogTestSupport.jdbcClient(), LEASE) {
                        @Override
                        public Optional<ProcessedRequestRecord> read(
                                final String source, final UUID requestId) {
                            return Optional.empty();
                        }
                    },
                    metrics);

            final GuardDecision decision = blindGuard.admit(command, delivery("msg-2"));

            assertThat(decision).isEqualTo(new GuardDecision.Abandon(ReasonCode.RECORD_ABSENT));
        }
    }
}
