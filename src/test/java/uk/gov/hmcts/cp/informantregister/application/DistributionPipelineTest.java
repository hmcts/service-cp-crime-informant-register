package uk.gov.hmcts.cp.informantregister.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.AuthoritySubmission;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.FailureClassification;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.PayloadUnavailableException;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.RunClaim;
import uk.gov.hmcts.cp.informantregister.domain.SubmissionFailedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What a run does between the guard admitting a delivery and the guard recording its outcome.
 *
 * <p>A pure unit: no Spring, no containers, no broker and — deliberately — no sleeping. The
 * processing deadline is exercised through a clock the test steps forward, because a test that
 * proved the deadline by waiting four minutes would be a test nobody runs.
 *
 * <p>Two of the assertions here look like assertions about nothing, and are the most important in
 * the suite. The submission port is never invoked, because with no transformation port the pipeline
 * produces an empty authority set — a stub that is never called is the intended shape of this
 * increment. And a run that reaches its deadline records a failure rather than a completion: a
 * completion written after the claim could have been reclaimed is the duplicate register this
 * service exists to prevent.
 */
class DistributionPipelineTest {

    private static final Duration PROCESSING_DEADLINE = Duration.ofMinutes(4);
    private static final Instant RUN_STARTED = Instant.parse("2026-08-21T09:00:00Z");
    private static final String OWNER = "runner-1/delivery-1";
    private static final String MESSAGE_ID = "RESULTS:1";

    private final IdempotencyGuard guard = mock(IdempotencyGuard.class);
    private final HearingPayloadSource payloadSource = mock(HearingPayloadSource.class);
    private final RegisterSubmissionClient submissionClient = mock(RegisterSubmissionClient.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProcessingMetrics metrics = new ProcessingMetrics(registry);
    private final SteppingClock clock = new SteppingClock();

    private final DistributionPipeline pipeline = new DistributionPipeline(
            guard, payloadSource, submissionClient, metrics, clock, PROCESSING_DEADLINE);

    private final DistributionCommand command = new DistributionCommand(
            "RESULTS",
            UUID.randomUUID(),
            UUID.randomUUID(),
            LocalDate.of(2026, 8, 21),
            Instant.parse("2026-08-21T08:00:00Z"),
            "Hearing_Resulted");

    private final DeliveryIdentity delivery = new DeliveryIdentity(MESSAGE_ID, OWNER);

    private final RunClaim claim = new RunClaim(
            command.source(), command.requestId(), OWNER, UUID.randomUUID(), MESSAGE_ID);

    // --- helpers ---------------------------------------------------------------------------

    /** A clock the test moves, so a deadline can be reached without a single second passing. */
    private static final class SteppingClock extends Clock {

        private Instant reading = RUN_STARTED;
        private Duration step = Duration.ZERO;

        void stepBy(final Duration amount) {
            this.step = amount;
        }

        @Override
        public Instant instant() {
            final Instant current = reading;
            reading = reading.plus(step);
            return current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }
    }

    private static JsonNode payload() {
        final ObjectMapper mapper = JacksonConfig.contractObjectMapper();
        return mapper.readTree("{\"hearing\":{\"id\":\"stub\"}}");
    }

    private void guardAdmitsTheDelivery() {
        when(guard.admit(command, delivery)).thenReturn(new GuardDecision.Run(claim));
        when(payloadSource.fetch(command)).thenReturn(payload());
    }

    private double counter(final String name, final String tag, final String value) {
        return registry.find(name).tag(tag, value).counter() == null
                ? 0
                : registry.get(name).tag(tag, value).counter().count();
    }

    // --- the happy path ----------------------------------------------------------------------

    @Nested
    @DisplayName("a delivery the guard admits")
    class AdmittedDelivery {

        @Test
        void should_consult_the_guard_with_the_command_and_the_delivering_identity() {
            guardAdmitsTheDelivery();
            when(guard.recordCompletion(claim, CompletionReason.NO_AUTHORITIES))
                    .thenReturn(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));

            pipeline.process(command, delivery);

            verify(guard).admit(command, delivery);
        }

        @Test
        void should_fetch_the_hearing_payload_exactly_once() {
            guardAdmitsTheDelivery();
            when(guard.recordCompletion(claim, CompletionReason.NO_AUTHORITIES))
                    .thenReturn(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));

            pipeline.process(command, delivery);

            verify(payloadSource, times(1)).fetch(command);
        }

        @Test
        void should_never_submit_because_the_run_produces_no_authorities() {
            guardAdmitsTheDelivery();
            when(guard.recordCompletion(claim, CompletionReason.NO_AUTHORITIES))
                    .thenReturn(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));

            pipeline.process(command, delivery);

            verifyNoInteractions(submissionClient);
        }

        @Test
        void should_record_completion_with_no_authorities_under_the_claim_it_was_granted() {
            guardAdmitsTheDelivery();
            final GuardDecision recorded = new GuardDecision.Complete(ReasonCode.RUN_COMPLETED);
            when(guard.recordCompletion(claim, CompletionReason.NO_AUTHORITIES)).thenReturn(recorded);

            final GuardDecision decision = pipeline.process(command, delivery);

            verify(guard).recordCompletion(claim, CompletionReason.NO_AUTHORITIES);
            assertThat(decision).isEqualTo(recorded);
        }

        @Test
        void should_count_the_request_as_completed() {
            guardAdmitsTheDelivery();
            when(guard.recordCompletion(claim, CompletionReason.NO_AUTHORITIES))
                    .thenReturn(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));

            pipeline.process(command, delivery);

            assertThat(counter(ProcessingMetrics.PROCESSED, ProcessingMetrics.OUTCOME_TAG, "completed"))
                    .isEqualTo(1.0);
        }

        @Test
        void should_not_count_a_completion_the_guard_refused_from_a_superseded_runner() {
            guardAdmitsTheDelivery();
            when(guard.recordCompletion(claim, CompletionReason.NO_AUTHORITIES))
                    .thenReturn(new GuardDecision.Abandon(ReasonCode.STALE_RUNNER));

            final GuardDecision decision = pipeline.process(command, delivery);

            assertThat(decision).isEqualTo(new GuardDecision.Abandon(ReasonCode.STALE_RUNNER));
            assertThat(counter(ProcessingMetrics.PROCESSED, ProcessingMetrics.OUTCOME_TAG, "completed"))
                    .isZero();
        }
    }

    // --- decisions that are not a run --------------------------------------------------------

    @Nested
    @DisplayName("a delivery the guard does not admit")
    class UnadmittedDelivery {

        @Test
        void should_hand_back_an_acknowledgement_without_touching_a_port() {
            final GuardDecision alreadyDone = new GuardDecision.Complete(ReasonCode.ALREADY_COMPLETED);
            when(guard.admit(command, delivery)).thenReturn(alreadyDone);

            final GuardDecision decision = pipeline.process(command, delivery);

            assertThat(decision).isEqualTo(alreadyDone);
            verifyNoInteractions(payloadSource, submissionClient);
        }

        @Test
        void should_hand_back_a_contested_delivery_without_touching_a_port() {
            final GuardDecision contested = new GuardDecision.Abandon(ReasonCode.CLAIM_NOT_ACQUIRED);
            when(guard.admit(command, delivery)).thenReturn(contested);

            final GuardDecision decision = pipeline.process(command, delivery);

            assertThat(decision).isEqualTo(contested);
            verifyNoInteractions(payloadSource, submissionClient);
        }

        @Test
        void should_hand_back_a_collision_without_touching_a_port() {
            final GuardDecision collision = new GuardDecision.DeadLetter(
                    DeadLetterReason.COLLISION, ReasonCode.IDEMPOTENCY_COLLISION);
            when(guard.admit(command, delivery)).thenReturn(collision);

            final GuardDecision decision = pipeline.process(command, delivery);

            assertThat(decision).isEqualTo(collision);
            verifyNoInteractions(payloadSource, submissionClient);
        }
    }

    // --- the simulated transient failure (spec FR-009) ----------------------------------------

    @Nested
    @DisplayName("a payload the source cannot supply")
    class PayloadUnavailable {

        private final GuardDecision handedBack =
                new GuardDecision.Abandon(ReasonCode.PIPELINE_TRANSIENT_FAILURE);

        private void payloadFetchFails() {
            when(guard.admit(command, delivery)).thenReturn(new GuardDecision.Run(claim));
            when(payloadSource.fetch(command)).thenThrow(
                    new PayloadUnavailableException(ReasonCode.PIPELINE_TRANSIENT_FAILURE));
            when(guard.recordTransientFailure(claim, ReasonCode.PIPELINE_TRANSIENT_FAILURE))
                    .thenReturn(handedBack);
        }

        @Test
        void should_record_a_transient_failure_and_hand_the_delivery_back() {
            payloadFetchFails();

            final GuardDecision decision = pipeline.process(command, delivery);

            verify(guard).recordTransientFailure(claim, ReasonCode.PIPELINE_TRANSIENT_FAILURE);
            assertThat(decision).isEqualTo(handedBack);
        }

        @Test
        void should_never_complete_a_run_that_never_produced_anything() {
            payloadFetchFails();

            pipeline.process(command, delivery);

            verify(guard, never()).recordCompletion(any(), any());
            verifyNoInteractions(submissionClient);
        }

        @Test
        void should_count_the_failed_run_as_transient() {
            payloadFetchFails();

            pipeline.process(command, delivery);

            assertThat(counter(ProcessingMetrics.PROCESSING_FAILURES,
                    ProcessingMetrics.CLASSIFICATION_TAG, "transient")).isEqualTo(1.0);
        }
    }

    // --- the failure nothing anticipated (spec FR-004, FR-009) --------------------------------

    @Nested
    @DisplayName("an unexpected failure inside an admitted run")
    class UnexpectedRunFailure {

        private final RuntimeException fault = new IllegalStateException("adapter fault");

        /**
         * The claim must be released by an outcome write, not leaked. An exception that escaped the
         * pipeline here would leave {@code claim_owner} live for the rest of the lease, so every
         * redelivery would bounce off {@code CLAIM_NOT_ACQUIRED} until the broker parked the message
         * under its own reason with no FAILED record behind it.
         */
        @Test
        void should_record_a_transient_failure_so_the_claim_is_released_before_the_hand_back() {
            when(guard.admit(command, delivery)).thenReturn(new GuardDecision.Run(claim));
            when(payloadSource.fetch(command)).thenThrow(fault);
            final GuardDecision handedBack = new GuardDecision.Abandon(ReasonCode.UNEXPECTED_FAILURE);
            when(guard.recordTransientFailure(claim, ReasonCode.UNEXPECTED_FAILURE))
                    .thenReturn(handedBack);

            final GuardDecision decision = pipeline.process(command, delivery);

            verify(guard).recordTransientFailure(claim, ReasonCode.UNEXPECTED_FAILURE);
            assertThat(decision).isEqualTo(handedBack);
        }

        @Test
        void should_park_the_request_when_the_failure_ends_the_final_permitted_delivery() {
            final DeliveryIdentity lastChance = new DeliveryIdentity(MESSAGE_ID, OWNER, true);
            when(guard.admit(command, lastChance)).thenReturn(new GuardDecision.Run(claim));
            when(payloadSource.fetch(command)).thenThrow(fault);
            final GuardDecision parked = new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.DELIVERY_LIMIT_EXHAUSTED);
            when(guard.recordExhaustion(claim, ReasonCode.UNEXPECTED_FAILURE)).thenReturn(parked);

            final GuardDecision decision = pipeline.process(command, lastChance);

            verify(guard).recordExhaustion(claim, ReasonCode.UNEXPECTED_FAILURE);
            assertThat(decision).isEqualTo(parked);
        }

        @Test
        void should_never_complete_a_run_that_failed() {
            when(guard.admit(command, delivery)).thenReturn(new GuardDecision.Run(claim));
            when(payloadSource.fetch(command)).thenThrow(fault);
            when(guard.recordTransientFailure(claim, ReasonCode.UNEXPECTED_FAILURE))
                    .thenReturn(new GuardDecision.Abandon(ReasonCode.UNEXPECTED_FAILURE));

            pipeline.process(command, delivery);

            verify(guard, never()).recordCompletion(any(), any());
            verifyNoInteractions(submissionClient);
        }

        @Test
        void should_count_the_unexpected_failure_as_transient() {
            when(guard.admit(command, delivery)).thenReturn(new GuardDecision.Run(claim));
            when(payloadSource.fetch(command)).thenThrow(fault);
            when(guard.recordTransientFailure(claim, ReasonCode.UNEXPECTED_FAILURE))
                    .thenReturn(new GuardDecision.Abandon(ReasonCode.UNEXPECTED_FAILURE));

            pipeline.process(command, delivery);

            assertThat(counter(ProcessingMetrics.PROCESSING_FAILURES,
                    ProcessingMetrics.CLASSIFICATION_TAG, "transient")).isEqualTo(1.0);
        }
    }

    // --- the enforced processing deadline (data-model invariant 8) -----------------------------

    @Nested
    @DisplayName("a run that reaches its processing deadline")
    class DeadlineReached {

        private final GuardDecision handedBack =
                new GuardDecision.Abandon(ReasonCode.PROCESSING_DEADLINE_EXCEEDED);

        private void theRunOverrunsBy(final Duration overrun) {
            guardAdmitsTheDelivery();
            clock.stepBy(PROCESSING_DEADLINE.plus(overrun));
            when(guard.recordTransientFailure(claim, ReasonCode.PROCESSING_DEADLINE_EXCEEDED))
                    .thenReturn(handedBack);
        }

        /**
         * Thirty seconds past the four-minute deadline and thirty seconds short of the five-minute
         * lease. The overrun is deliberately <em>inside</em> the lease: the property under test is
         * that a run stops itself while its claim is still unambiguously its own, and an overrun
         * that reached the lease would prove only that the guard rejects a superseded runner —
         * which is a different mechanism, tested elsewhere.
         */
        private void theRunOverrunsItsDeadline() {
            theRunOverrunsBy(Duration.ofSeconds(30));
        }

        @Test
        void should_abort_as_a_transient_failure_before_the_lease_can_lapse() {
            theRunOverrunsItsDeadline();

            final GuardDecision decision = pipeline.process(command, delivery);

            verify(guard).recordTransientFailure(claim, ReasonCode.PROCESSING_DEADLINE_EXCEEDED);
            assertThat(decision).isEqualTo(handedBack);
        }

        @Test
        void should_not_record_a_completion_it_no_longer_has_the_claim_to_settle() {
            theRunOverrunsItsDeadline();

            pipeline.process(command, delivery);

            verify(guard, never()).recordCompletion(any(), any());
        }

        @Test
        void should_count_the_aborted_run_as_a_transient_failure() {
            theRunOverrunsItsDeadline();

            pipeline.process(command, delivery);

            assertThat(counter(ProcessingMetrics.PROCESSING_FAILURES,
                    ProcessingMetrics.CLASSIFICATION_TAG, "transient")).isEqualTo(1.0);
        }

        /**
         * The boundary itself. The invariant is that a run aborts when the deadline is
         * <em>reached</em>, not once it has been passed, so a run standing exactly on it has
         * already run out of the time its claim guarantees and may not write a completion.
         */
        @Test
        void should_abort_a_run_standing_exactly_on_its_deadline() {
            theRunOverrunsBy(Duration.ZERO);

            final GuardDecision decision = pipeline.process(command, delivery);

            verify(guard).recordTransientFailure(claim, ReasonCode.PROCESSING_DEADLINE_EXCEEDED);
            verify(guard, never()).recordCompletion(any(), any());
            assertThat(decision).isEqualTo(handedBack);
        }

        @Test
        void should_complete_a_run_that_finishes_inside_its_deadline() {
            guardAdmitsTheDelivery();
            // A minute short of the deadline: the same clock, stepped less far.
            clock.stepBy(Duration.ofMinutes(3));
            final GuardDecision recorded = new GuardDecision.Complete(ReasonCode.RUN_COMPLETED);
            when(guard.recordCompletion(claim, CompletionReason.NO_AUTHORITIES)).thenReturn(recorded);

            final GuardDecision decision = pipeline.process(command, delivery);

            assertThat(decision).isEqualTo(recorded);
        }
    }

    // --- the port contracts the pipeline is built on ------------------------------------------

    @Nested
    @DisplayName("the port contracts")
    class PortContracts {

        @Test
        void payload_unavailability_should_be_transient_by_construction() {
            final PayloadUnavailableException unavailable =
                    new PayloadUnavailableException(ReasonCode.PIPELINE_TRANSIENT_FAILURE);

            assertThat(unavailable.classification()).isEqualTo(FailureClassification.TRANSIENT);
            assertThat(unavailable.reason()).isEqualTo(ReasonCode.PIPELINE_TRANSIENT_FAILURE);
        }

        @Test
        void a_submission_failure_should_report_the_classification_it_carries() {
            final SubmissionFailedException retryable = new SubmissionFailedException(
                    FailureClassification.TRANSIENT, ReasonCode.PIPELINE_TRANSIENT_FAILURE);
            final SubmissionFailedException rejected = new SubmissionFailedException(
                    FailureClassification.NON_TRANSIENT, ReasonCode.IDEMPOTENCY_COLLISION);

            assertThat(retryable.classification()).isEqualTo(FailureClassification.TRANSIENT);
            assertThat(rejected.classification()).isEqualTo(FailureClassification.NON_TRANSIENT);
        }

        @Test
        void a_submission_failure_should_carry_a_bounded_reason_and_no_free_text() {
            final SubmissionFailedException failure = new SubmissionFailedException(
                    FailureClassification.NON_TRANSIENT, ReasonCode.IDEMPOTENCY_COLLISION);

            assertThat(failure.reason()).isEqualTo(ReasonCode.IDEMPOTENCY_COLLISION);
            assertThat(failure.getMessage()).isEqualTo(ReasonCode.IDEMPOTENCY_COLLISION.code());
        }

        @Test
        void an_authority_submission_should_carry_the_authority_and_its_document_untouched() {
            final JsonNode document = payload();

            final AuthoritySubmission submission = new AuthoritySubmission("PA-1", document);

            assertThat(submission.prosecutionAuthorityId()).isEqualTo("PA-1");
            assertThat(submission.document()).isSameAs(document);
        }
    }
}
