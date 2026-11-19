package uk.gov.hmcts.cp.informantregister.persistence;

import java.time.Duration;
import java.time.Instant;
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
import uk.gov.hmcts.cp.informantregister.support.CapturedLog;
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

    /**
     * A delivery under an explicit broker sequence number — for the sequence-number decision table
     * (spec FR-003, FR-004, FR-006), where `messageId` alone can no longer express "same identity"
     * or "different identity".
     */
    private static DeliveryIdentity delivery(final String messageId, final long sequenceNumber) {
        return new DeliveryIdentity(messageId, OWNER, false, sequenceNumber, 0L, null);
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

    /** Admits a delivery under an explicit sequence number and returns the claim it may run under. */
    private RunClaim admitted(final String messageId, final long sequenceNumber) {
        return runClaimOf(guard.admit(command, delivery(messageId, sequenceNumber)));
    }

    /** Drives the record to FAILED under the given exhausting delivery's sequence number. */
    private void driveToFailed(final String messageId, final long sequenceNumber) {
        guard.recordExhaustion(
                admitted(messageId, sequenceNumber), ReasonCode.PIPELINE_TRANSIENT_FAILURE);
    }

    /**
     * Drives the record to FAILED via the non-transient path, under the given sequence number —
     * for the category this parking path records (spec FR-001, FR-009).
     */
    private void driveToFailedNonTransient(
            final String messageId, final long sequenceNumber, final ReasonCode reason) {
        guard.recordNonTransientFailure(admitted(messageId, sequenceNumber), reason);
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
            assertThat(row.failureReason())
                    .as("failure_reason describes the current status, and a completed request has "
                            + "none: a COMPLETED row still carrying its old transient reason reads "
                            + "as a contradiction to the support engineer the log exists for; the "
                            + "retry history lives in the logs, as it does after a replay")
                    .isNull();
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

    // --- RECEIVED / RETRYING -> FAILED, whatever the budget ----------------------------------

    /**
     * A failure no redelivery could change ends the request there and then.
     *
     * <p>The Results command refusing a body is the case: the next delivery would send the same
     * bytes to the same refusal, so the remaining budget buys nothing and only delays the
     * dead-letter support acts on. The row is parked under this delivery's identity exactly as an
     * exhausted one is, so a redelivery of it re-parks while a deliberate resubmission replays.
     */
    @Nested
    @DisplayName("a run that fails in a way no redelivery could fix")
    class RunFailsNonTransiently {

        @Test
        void should_park_the_request_at_once_and_ask_for_dead_lettering_under_its_own_reason() {
            final RunClaim claim = admitted("msg-4");

            final GuardDecision decision =
                    guard.recordNonTransientFailure(claim, ReasonCode.SUBMISSION_REJECTED);

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.NON_TRANSIENT, ReasonCode.SUBMISSION_REJECTED));
            final Row row = row();
            assertThat(row.status()).isEqualTo("FAILED");
            assertThat(row.failureReason()).isEqualTo("SUBMISSION_REJECTED");
            assertThat(row.exhaustedMessageId()).isEqualTo("msg-4");
            assertThat(row.claimOwner()).isNull();
            assertThat(row.claimToken()).isNull();
            assertThat(row.claimExpiresAt()).isNull();
        }

        @Test
        void should_leave_a_parked_request_replayable_under_a_fresh_identity() {
            guard.recordNonTransientFailure(admitted("msg-4"), ReasonCode.SUBMISSION_REJECTED);

            final GuardDecision decision = guard.admit(command, delivery("msg-5"));

            assertThat(decision).isInstanceOf(GuardDecision.Run.class);
            assertThat(row().status()).isEqualTo("RECEIVED");
        }

        @Test
        void should_refuse_the_write_from_a_runner_whose_claim_was_reclaimed() {
            final RunClaim superseded = admitted("msg-4");
            ProcessedLogTestSupport.expireClaim(command.source(), command.requestId());
            guard.admit(command, delivery("msg-5", OTHER_OWNER));

            final GuardDecision decision =
                    guard.recordNonTransientFailure(superseded, ReasonCode.SUBMISSION_REJECTED);

            assertThat(decision).isEqualTo(new GuardDecision.Abandon(ReasonCode.STALE_RUNNER));
            assertThat(row().status()).isEqualTo("RECEIVED");
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
                    DeadLetterReason.EXHAUSTED, ReasonCode.PIPELINE_TRANSIENT_FAILURE));
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
            driveToRetrying("msg-1");
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

            // spec FR-009: the re-park's detail is the request's own recorded reason, never the
            // generic DELIVERY_LIMIT_EXHAUSTED — driveToFailed parks it with
            // PIPELINE_TRANSIENT_FAILURE.
            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.PIPELINE_TRANSIENT_FAILURE));
            assertThat(row()).isEqualTo(before);
        }

        /**
         * The sequence-number decision table (spec FR-003, FR-004, FR-006): the broker's own
         * sequence number decides, never {@code messageId}.
         */
        @Nested
        @DisplayName("decided by broker sequence number, not messageId")
        class SequenceNumberDecision {

            @Test
            @DisplayName("the same sequence number re-parks with no state change, whatever happens "
                    + "to messageId")
            void a_redelivery_with_the_same_sequence_number_should_change_nothing() {
                driveToFailed("msg-5", 500L);
                final Row before = row();

                // A redelivery of the stored message carries the SAME messageId by definition, but
                // the decision must be made on the sequence number alone.
                final GuardDecision decision = guard.admit(command, delivery("msg-5", 500L));

                assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                        DeadLetterReason.EXHAUSTED, ReasonCode.PIPELINE_TRANSIENT_FAILURE));
                assertThat(row()).isEqualTo(before);
            }

            @Test
            @DisplayName("a different sequence number replays, even under the SAME messageId — the "
                    + "defect this feature fixes")
            void a_resend_with_a_different_sequence_number_should_replay_regardless_of_message_id() {
                driveToFailed("msg-5", 500L);

                // The documented resubmission: the dead-lettered body re-sent byte for byte,
                // messageId included — but the broker assigned it a NEW sequence number because it
                // accepted it as a fresh send.
                final RunClaim replay = admitted("msg-5", 501L);

                final Row row = row();
                assertThat(row.status()).isEqualTo("RECEIVED");
                assertThat(row.attempts()).isEqualTo(2);
                assertThat(row.failureReason()).isNull();
                assertThat(row.exhaustedMessageId()).isNull();
                assertThat(row.parkingSequenceNumber()).isNull();
                assertThat(row.parkingCategory()).isNull();
                assertThat(row.auditNote()).contains("PIPELINE_TRANSIENT_FAILURE");
                assertThat(row.claimToken()).isEqualTo(replay.token());
            }

            @Test
            @DisplayName("the parking sequence number is recorded when the request is parked")
            void parking_should_record_the_exhausting_deliverys_sequence_number_and_category() {
                driveToFailed("msg-5", 500L);

                final Row row = row();
                assertThat(row.status()).isEqualTo("FAILED");
                assertThat(row.parkingSequenceNumber()).isEqualTo(500L);
                assertThat(row.parkingCategory()).isEqualTo("EXHAUSTED");
            }

            @Test
            @DisplayName("a replay's compare-and-set is predicated on the exact attempts and "
                    + "parking sequence number observed on the read")
            void a_replay_should_be_admitted_only_against_the_generation_it_observed() {
                driveToFailed("msg-5", 500L);
                final Row observed = row();
                assertThat(observed.attempts()).isEqualTo(1);
                assertThat(observed.parkingSequenceNumber()).isEqualTo(500L);

                // A delivery reading the exact generation above is admitted.
                final RunClaim replay = admitted("msg-5", 777L);

                assertThat(row().status()).isEqualTo("RECEIVED");
                assertThat(row().claimToken()).isEqualTo(replay.token());
            }

            /**
             * Spec FR-008: a replay's log line names the parked delivery's sequence number, the
             * replaying delivery's sequence number, its delivery count and its enqueue time — and
             * nothing else, since every one of those is a broker fact rather than anything about a
             * person.
             */
            @Test
            @DisplayName("a replay is logged with the parked and replaying sequence numbers, "
                    + "delivery count and enqueue time, no PII")
            void a_replay_should_be_logged_with_the_broker_facts_FR_008() {
                driveToFailed("msg-5", 500L);
                final Instant enqueueTime = Instant.parse("2026-08-20T09:30:00Z");
                final DeliveryIdentity resend = new DeliveryIdentity(
                        "msg-5", OWNER, false, 777L, 2L, enqueueTime);

                try (CapturedLog log = CapturedLog.of(IdempotencyGuard.class)) {
                    final GuardDecision decision = guard.admit(command, resend);

                    assertThat(decision).isInstanceOf(GuardDecision.Run.class);
                    assertThat(log.messages())
                            .as("the replay line carries only the request's identifiers, the two "
                                    + "sequence numbers, the delivery count and the enqueue time — "
                                    + "every one a broker or key fact, never anything about a person")
                            .anySatisfy(message -> {
                                assertThat(message).contains("replayed under a different sequence "
                                        + "number");
                                assertThat(message).contains("parkedSequenceNumber=500");
                                assertThat(message).contains("deliverySequenceNumber=777");
                                assertThat(message).contains("deliveryCount=2");
                                assertThat(message).contains(enqueueTime.toString());
                            });
                }
            }

            /**
             * Spec FR-008, the re-park side: the same broker facts as the replay line, written
             * when the decision is "no run" rather than "run". US2's own protection and US1's fix
             * are one decision with two outcomes, and this is proof the log line was not forgotten
             * on the half that does not run anything.
             */
            @Test
            @DisplayName("a re-park is logged with the same broker facts as a replay, no PII")
            void a_repark_should_be_logged_with_the_broker_facts_FR_008() {
                driveToFailed("msg-5", 500L);
                final Instant enqueueTime = Instant.parse("2026-08-20T09:31:00Z");
                final DeliveryIdentity redelivery = new DeliveryIdentity(
                        "msg-5", OWNER, false, 500L, 3L, enqueueTime);

                try (CapturedLog log = CapturedLog.of(IdempotencyGuard.class)) {
                    final GuardDecision decision = guard.admit(command, redelivery);

                    // The re-park's detail is the request's own recorded reason (spec FR-009), not
                    // the generic DELIVERY_LIMIT_EXHAUSTED — driveToFailed parks it with
                    // PIPELINE_TRANSIENT_FAILURE, so that is what a re-park must show too.
                    assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                            DeadLetterReason.EXHAUSTED, ReasonCode.PIPELINE_TRANSIENT_FAILURE));
                    assertThat(log.messages())
                            .as("the re-park line carries only identifiers, the two sequence "
                                    + "numbers, the delivery count and the enqueue time")
                            .anySatisfy(message -> {
                                assertThat(message).contains(
                                        "Redelivery of the sequence number that parked the request");
                                assertThat(message).contains("parkedSequenceNumber=500");
                                assertThat(message).contains("deliverySequenceNumber=500");
                                assertThat(message).contains("deliveryCount=3");
                                assertThat(message).contains(enqueueTime.toString());
                            });
                }
            }

            /**
             * Spec FR-009, FR-010 (Acceptance Scenarios US4-1, US4-2): a re-park's dead-letter
             * reason and description are the request's own recorded category and failure reason,
             * never the generic {@code DELIVERY_LIMIT_EXHAUSTED}.
             */
            @Test
            @DisplayName("a re-park of a non-transient parking shows its own category and reason")
            void a_repark_of_a_non_transient_parking_should_show_its_own_reason() {
                driveToFailedNonTransient("msg-5", 500L, ReasonCode.SUBMISSION_REJECTED);

                final GuardDecision decision = guard.admit(command, delivery("msg-5", 500L));

                assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                        DeadLetterReason.NON_TRANSIENT, ReasonCode.SUBMISSION_REJECTED));
            }

            @Test
            @DisplayName("a re-park of an exhausted parking shows its own transient reason")
            void a_repark_of_an_exhausted_parking_should_show_its_own_reason() {
                driveToFailed("msg-5", 500L);

                final GuardDecision decision = guard.admit(command, delivery("msg-5", 500L));

                assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                        DeadLetterReason.EXHAUSTED, ReasonCode.PIPELINE_TRANSIENT_FAILURE));
            }

            /**
             * Acceptance Scenario US4-3, and the precedence rule FR-010 states but the two tests
             * above do not exercise: a row with a <strong>recorded</strong> category is used exactly
             * as recorded and never reconstructed from the reason code, even where the mapping
             * would disagree with it. Constructed directly, since the guard itself always writes
             * the two together and so cannot produce this combination on its own — the point is to
             * prove the read path prefers the stored value over the mapping, not that the guard can
             * create a mismatch.
             */
            @Test
            @DisplayName("a recorded category is used as recorded, never reconstructed from the reason")
            void a_recorded_category_should_win_over_the_mapping() {
                driveToFailedNonTransient("msg-5", 500L, ReasonCode.SUBMISSION_REJECTED);
                // SUBMISSION_REJECTED alone would map to NON_TRANSIENT; overwrite the stored
                // category directly to EXHAUSTED, so the only way the re-park could still answer
                // EXHAUSTED is by reading the stored value rather than recomputing it.
                final int updated = ProcessedLogTestSupport.jdbcClient()
                        .sql("""
                                UPDATE processed_request SET parking_category = 'EXHAUSTED'
                                 WHERE source = :source AND request_id = :requestId
                                """)
                        .param("source", command.source())
                        .param("requestId", command.requestId())
                        .update();
                assertThat(updated).isEqualTo(1);

                final GuardDecision decision = guard.admit(command, delivery("msg-5", 500L));

                assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                        DeadLetterReason.EXHAUSTED, ReasonCode.SUBMISSION_REJECTED));
            }

            /**
             * Acceptance Scenario US4-3: a legacy row (no recorded category) is categorised by the
             * explicit mapping over its stored reason.
             */
            @Test
            @DisplayName("a legacy row with no recorded category uses the explicit reason mapping")
            void a_legacy_row_should_use_the_category_mapping() {
                driveToFailed("msg-5", 500L);
                final int cleared = ProcessedLogTestSupport.jdbcClient()
                        .sql("""
                                UPDATE processed_request
                                   SET parking_sequence_number = NULL, parking_category = NULL
                                 WHERE source = :source AND request_id = :requestId
                                """)
                        .param("source", command.source())
                        .param("requestId", command.requestId())
                        .update();
                assertThat(cleared).isEqualTo(1);
                final Instant beforeParking = row().updatedAt().minusSeconds(30);

                // Fails the legacy heuristic (enqueued before parking), so it re-parks — showing
                // the mapped category for its stored PIPELINE_TRANSIENT_FAILURE reason.
                final GuardDecision decision = guard.admit(
                        command, new DeliveryIdentity(
                                "msg-resubmitted", OWNER, false, 999L, 0L, beforeParking));

                assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                        DeadLetterReason.EXHAUSTED, ReasonCode.PIPELINE_TRANSIENT_FAILURE));
            }

            /**
             * Acceptance Scenario US4-4 (spec FR-009): a missing or unrecognised stored reason
             * dead-letters as {@code exhausted} / {@code UNEXPECTED_FAILURE}, logs a sanitised
             * anomaly identifying the data corruption without emitting the unrecognised text, and
             * never throws.
             */
            @Test
            @DisplayName("an unrecognised stored reason dead-letters as UNEXPECTED_FAILURE, never throws")
            void an_unrecognised_stored_reason_should_dead_letter_as_unexpected_failure() {
                driveToFailed("msg-5", 500L);
                final String corruptReason = "NOT_A_REAL_REASON_CODE";
                final int corrupted = ProcessedLogTestSupport.jdbcClient()
                        .sql("""
                                UPDATE processed_request SET failure_reason = :reason
                                 WHERE source = :source AND request_id = :requestId
                                """)
                        .param("reason", corruptReason)
                        .param("source", command.source())
                        .param("requestId", command.requestId())
                        .update();
                assertThat(corrupted).isEqualTo(1);

                try (CapturedLog log = CapturedLog.of(IdempotencyGuard.class)) {
                    final GuardDecision decision = guard.admit(command, delivery("msg-5", 500L));

                    assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                            DeadLetterReason.EXHAUSTED, ReasonCode.UNEXPECTED_FAILURE));
                    assertThat(log.messages())
                            .as("the anomaly is logged, but the unrecognised text itself never is")
                            .anySatisfy(message -> assertThat(message)
                                    .containsIgnoringCase("unrecognised")
                                    .doesNotContain(corruptReason));
                }
            }

            /**
             * The same anomaly (spec FR-009), but on a row recorded under {@code non-transient} —
             * the one combination {@link #an_unrecognised_stored_reason_should_dead_letter_as_unexpected_failure}
             * does not reach, since {@code driveToFailed} always records {@code exhausted} and an
             * anomaly forced to {@code exhausted} would look the same whether the override ran or
             * the stored category was simply read back unchanged. Driving the row to
             * {@code non-transient} first makes the override observable: if FR-009's "regardless of
             * what category happens to be recorded" were not honoured, this would answer
             * {@code non-transient} instead.
             */
            @Test
            @DisplayName("an unrecognised reason overrides a recorded non-transient category too")
            void an_unrecognised_reason_should_override_a_recorded_non_transient_category() {
                driveToFailedNonTransient("msg-5", 500L, ReasonCode.SUBMISSION_REJECTED);
                final String corruptReason = "NOT_A_REAL_REASON_CODE";
                final int corrupted = ProcessedLogTestSupport.jdbcClient()
                        .sql("""
                                UPDATE processed_request SET failure_reason = :reason
                                 WHERE source = :source AND request_id = :requestId
                                """)
                        .param("reason", corruptReason)
                        .param("source", command.source())
                        .param("requestId", command.requestId())
                        .update();
                assertThat(corrupted).isEqualTo(1);
                assertThat(row().parkingCategory())
                        .as("the row still records non-transient; only the decision is overridden")
                        .isEqualTo("NON_TRANSIENT");

                final GuardDecision decision = guard.admit(command, delivery("msg-5", 500L));

                assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                        DeadLetterReason.EXHAUSTED, ReasonCode.UNEXPECTED_FAILURE));
            }
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
