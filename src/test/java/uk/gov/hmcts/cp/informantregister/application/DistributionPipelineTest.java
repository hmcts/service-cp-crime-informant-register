package uk.gov.hmcts.cp.informantregister.application;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.AuthoritySubmission;
import uk.gov.hmcts.cp.informantregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.FailureClassification;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterDefendant;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterDocument;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterHearing;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterHearingVenue;
import uk.gov.hmcts.cp.informantregister.domain.PayloadUnavailableException;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.ReferenceDataUnavailableException;
import uk.gov.hmcts.cp.informantregister.domain.RunClaim;
import uk.gov.hmcts.cp.informantregister.domain.SubmissionFailedException;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
    private final RegisterTransformer transformer = mock(RegisterTransformer.class);
    private final RegisterSubmissionClient submissionClient = mock(RegisterSubmissionClient.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProcessingMetrics metrics = new ProcessingMetrics(registry);
    private final SteppingClock clock = new SteppingClock();

    private final DistributionPipeline pipeline = new DistributionPipeline(
            guard, payloadSource, transformer, submissionClient, metrics, clock,
            PROCESSING_DEADLINE);

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
        return mapper.readTree("{\"isReshare\":false,\"hearingDay\":\"2026-08-21\","
                + "\"sharedTime\":\"2026-08-21T07:45:00Z\",\"hearing\":{\"id\":\"stub\"}}");
    }

    /** A minimal outbound document: the port contract's shape, not its content. */
    private static InformantRegisterDocument document() {
        final InformantRegisterDefendant defendant = new InformantRegisterDefendant(
                "SMITH, John", null, "1 High Street", null, null, null, null,
                null, null, null, null, null, null, null);
        final InformantRegisterHearing session =
                new InformantRegisterHearing("Court 1", "10:00:00Z", List.of(defendant));
        final InformantRegisterHearingVenue venue =
                new InformantRegisterHearingVenue(null, "Bristol Magistrates' Court", List.of(session));
        return new InformantRegisterDocument(
                ZonedDateTime.parse("2026-08-20T11:00:00Z"),
                ZonedDateTime.parse("2026-08-19T10:00:00Z"),
                UUID.fromString("11111111-2222-4333-8444-555555555555"),
                UUID.fromString("3f4a2b1c-5d6e-4f70-8912-a3b4c5d6e7f8"),
                "CPS", null, null, null,
                "informant-register-CPS-20260820.pdf", null, venue, null);
    }

    /** A second document, so "one per authority" can be told apart from "one". */
    private static InformantRegisterDocument secondDocument() {
        return new InformantRegisterDocument(
                ZonedDateTime.parse("2026-08-20T11:00:00Z"),
                ZonedDateTime.parse("2026-08-19T10:00:00Z"),
                UUID.fromString("11111111-2222-4333-8444-555555555555"),
                UUID.fromString("9c8b7a65-4321-4fed-8cba-098765432100"),
                "TVL", null, null, null,
                "InformantRegister_TVL_2026-08-20.csv", null, null, null);
    }

    private void guardAdmitsTheDelivery() {
        when(guard.admit(command, delivery)).thenReturn(new GuardDecision.Run(claim));
        when(payloadSource.fetch(command)).thenReturn(payload());
        when(transformer.transform(any(), any(), any())).thenReturn(List.of());
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

    // --- the transformation seam (design_rules.md, "Pipeline Architecture") --------------------

    /**
     * What the run does once the transformation port actually produces something.
     *
     * <p>The two ends of the seam are what matter. The payload the source answers with is a
     * wrapper, and the transformation is handed what the legacy orchestrator hands its first
     * activity ({@code InformantRegisterOrchestrator/index.js:21-24}): the wrapper's
     * {@code hearing} member, under the wrapper's own {@code sharedTime} — not the wrapper
     * itself, not the command's copy of the shared time, and not the wall clock. Every document
     * it produces becomes exactly one submission, in the order it produced them, keyed by the
     * request the authority belongs to. Order is the half that is easy to lose: nothing
     * downstream sorts, so the first authority the legacy names is the first authority POSTed.
     */
    @Nested
    @DisplayName("a hearing the transformation produces authorities for")
    class ProducedAuthorities {

        private void transformationProduces(final List<InformantRegisterDocument> documents) {
            when(guard.admit(command, delivery)).thenReturn(new GuardDecision.Run(claim));
            when(payloadSource.fetch(command)).thenReturn(payload());
            when(transformer.transform(any(), any(), any())).thenReturn(documents);
            when(guard.recordCompletion(claim, CompletionReason.AUTHORITIES_SUBMITTED))
                    .thenReturn(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));
        }

        @Test
        void should_transform_the_unwrapped_hearing_under_the_shared_time_the_payload_carries() {
            transformationProduces(List.of(document()));

            pipeline.process(command, delivery);

            verify(transformer).transform(
                    payload().path("hearing"), "2026-08-21T07:45:00Z", CallerIdentity.SYSTEM);
        }

        @Test
        void should_submit_one_document_per_authority_in_the_order_they_were_produced() {
            final InformantRegisterDocument first = document();
            final InformantRegisterDocument second = secondDocument();
            transformationProduces(List.of(first, second));

            pipeline.process(command, delivery);

            final InOrder order = inOrder(submissionClient);
            order.verify(submissionClient).submit(new AuthoritySubmission(
                    command.source(), command.requestId(),
                    first.prosecutionAuthorityId().toString(), first, CallerIdentity.SYSTEM));
            order.verify(submissionClient).submit(new AuthoritySubmission(
                    command.source(), command.requestId(),
                    second.prosecutionAuthorityId().toString(), second, CallerIdentity.SYSTEM));
            order.verifyNoMoreInteractions();
        }

        /**
         * The legacy's single-{@code cjscppuid} semantics, at the level that decides them.
         *
         * <p>{@code InformantRegisterEventGridTrigger/index.js:15} copies the envelope's
         * {@code userId} into the orchestration input once, and
         * {@code InformantRegisterOrchestrator/index.js:13,31,46} hands that one value to the
         * payload read, the subscriptions read and the POST. Three calls, one caller. A run that
         * read as one caller and posted as another would be attributable to nobody, so this is the
         * pipeline's property to hold and not each adapter's.
         */
        @Test
        void every_call_of_one_run_should_be_made_as_the_user_the_message_named() {
            final UUID user = UUID.fromString("0b7a5c2e-4d19-4a6b-8c30-9e1f5d7b2a48");
            final DistributionCommand attributed = new DistributionCommand(
                    command.source(), command.requestId(), command.hearingId(),
                    command.hearingDay(), command.sharedTime(), command.eventType(),
                    Optional.of(user));
            final RunClaim attributedClaim = new RunClaim(
                    attributed.source(), attributed.requestId(), OWNER, UUID.randomUUID(),
                    MESSAGE_ID);
            final InformantRegisterDocument first = document();
            final InformantRegisterDocument second = secondDocument();
            when(guard.admit(attributed, delivery))
                    .thenReturn(new GuardDecision.Run(attributedClaim));
            when(payloadSource.fetch(attributed)).thenReturn(payload());
            when(transformer.transform(any(), any(), any())).thenReturn(List.of(first, second));
            when(guard.recordCompletion(attributedClaim, CompletionReason.AUTHORITIES_SUBMITTED))
                    .thenReturn(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));

            pipeline.process(attributed, delivery);

            final CallerIdentity expected = new CallerIdentity(Optional.of(user));
            // The payload port is handed the command itself, so it reads the user from the same
            // field the other two are given; asserting the command is asserting the identity.
            verify(payloadSource).fetch(attributed);
            verify(transformer).transform(any(), any(), eq(expected));
            final ArgumentCaptor<AuthoritySubmission> submitted =
                    ArgumentCaptor.forClass(AuthoritySubmission.class);
            verify(submissionClient, times(2)).submit(submitted.capture());
            assertThat(submitted.getAllValues())
                    .extracting(AuthoritySubmission::identity)
                    .containsExactly(expected, expected);
        }

        @Test
        void a_run_the_message_named_no_user_for_should_be_made_as_the_system() {
            // A replayed message, and every message published before the field existed. There is
            // still exactly one caller for the run; it is just not a person.
            final InformantRegisterDocument only = document();
            transformationProduces(List.of(only));

            pipeline.process(command, delivery);

            verify(transformer).transform(any(), any(), eq(CallerIdentity.SYSTEM));
            final ArgumentCaptor<AuthoritySubmission> submitted =
                    ArgumentCaptor.forClass(AuthoritySubmission.class);
            verify(submissionClient).submit(submitted.capture());
            assertThat(submitted.getValue().identity()).isEqualTo(CallerIdentity.SYSTEM);
        }

        @Test
        void should_record_a_completion_that_says_authorities_were_submitted() {
            transformationProduces(List.of(document()));

            pipeline.process(command, delivery);

            verify(guard).recordCompletion(claim, CompletionReason.AUTHORITIES_SUBMITTED);
            verify(guard, never()).recordCompletion(claim, CompletionReason.NO_AUTHORITIES);
        }

        @Test
        void should_record_no_authorities_when_the_transformation_produced_none() {
            // The legacy's orchestrator skips the rest of the flow for this hearing and still
            // reports success; here it is a recorded business outcome (deviations entry 6).
            guardAdmitsTheDelivery();
            when(guard.recordCompletion(claim, CompletionReason.NO_AUTHORITIES))
                    .thenReturn(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));

            pipeline.process(command, delivery);

            verify(guard).recordCompletion(claim, CompletionReason.NO_AUTHORITIES);
            verifyNoInteractions(submissionClient);
        }

        @Test
        void should_park_a_transformation_the_payload_cannot_survive() {
            when(guard.admit(command, delivery)).thenReturn(new GuardDecision.Run(claim));
            when(payloadSource.fetch(command)).thenReturn(payload());
            when(transformer.transform(any(), any(), any()))
                    .thenThrow(new TransformationFailedException("unreadable hearing"));
            final GuardDecision parked = new GuardDecision.DeadLetter(
                    DeadLetterReason.NON_TRANSIENT, ReasonCode.TRANSFORMATION_FAILED);
            when(guard.recordNonTransientFailure(claim, ReasonCode.TRANSFORMATION_FAILED))
                    .thenReturn(parked);

            final GuardDecision decision = pipeline.process(command, delivery);

            assertThat(decision).isEqualTo(parked);
            verifyNoInteractions(submissionClient);
        }

        /**
         * A reference-data outage must never become a register that reaches nobody. The legacy
         * catches it, answers {@code null}, and POSTs a body with no recipients at all
         * ({@code ReferenceDataService.js:52}); the parity pack's pinning entry {@code d03} requires
         * this port to classify it instead, and transiently, because the next delivery may find
         * reference data up.
         */
        @Test
        void should_hand_back_a_delivery_whose_reference_data_could_not_be_reached() {
            when(guard.admit(command, delivery)).thenReturn(new GuardDecision.Run(claim));
            when(payloadSource.fetch(command)).thenReturn(payload());
            when(transformer.transform(any(), any(), any())).thenThrow(
                    new ReferenceDataUnavailableException(ReasonCode.REFERENCE_DATA_UNAVAILABLE));
            final GuardDecision handedBack =
                    new GuardDecision.Abandon(ReasonCode.REFERENCE_DATA_UNAVAILABLE);
            when(guard.recordTransientFailure(claim, ReasonCode.REFERENCE_DATA_UNAVAILABLE))
                    .thenReturn(handedBack);

            final GuardDecision decision = pipeline.process(command, delivery);

            assertThat(decision).isEqualTo(handedBack);
            verify(guard, never()).recordNonTransientFailure(any(), any());
            verifyNoInteractions(submissionClient);
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
            verifyNoInteractions(payloadSource, transformer, submissionClient);
        }

        @Test
        void should_hand_back_a_contested_delivery_without_touching_a_port() {
            final GuardDecision contested = new GuardDecision.Abandon(ReasonCode.CLAIM_NOT_ACQUIRED);
            when(guard.admit(command, delivery)).thenReturn(contested);

            final GuardDecision decision = pipeline.process(command, delivery);

            assertThat(decision).isEqualTo(contested);
            verifyNoInteractions(payloadSource, transformer, submissionClient);
        }

        @Test
        void should_hand_back_a_collision_without_touching_a_port() {
            final GuardDecision collision = new GuardDecision.DeadLetter(
                    DeadLetterReason.COLLISION, ReasonCode.IDEMPOTENCY_COLLISION);
            when(guard.admit(command, delivery)).thenReturn(collision);

            final GuardDecision decision = pipeline.process(command, delivery);

            assertThat(decision).isEqualTo(collision);
            verifyNoInteractions(payloadSource, transformer, submissionClient);
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

        /**
         * The recovery path is a store write, and a store that dies inside it must not be dressed
         * up as anything else: the failure escapes the catch block as itself, so the transport
         * adapter's own store-outage handling — hand the delivery back, stop intake — takes over.
         * A catch here that absorbed it would be the swallowed exception this service exists to
         * remove, wearing a recovery's clothes.
         */
        @Test
        void should_let_a_failure_of_the_recording_write_itself_escape() {
            when(guard.admit(command, delivery)).thenReturn(new GuardDecision.Run(claim));
            when(payloadSource.fetch(command)).thenThrow(fault);
            final IllegalStateException storeDied =
                    new IllegalStateException("the store went away under the recording write");
            when(guard.recordTransientFailure(claim, ReasonCode.UNEXPECTED_FAILURE))
                    .thenThrow(storeDied);

            assertThatThrownBy(() -> pipeline.process(command, delivery)).isSameAs(storeDied);
        }
    }

    // --- a failure no redelivery can fix (design_rules.md, "Processing State Machine") ---------

    /**
     * The classification the ports carry is the branch, not the delivery count.
     *
     * <p>The state machine lists transformation errors and 4xx contract rejections as non-transient:
     * they go straight to FAILED and the dead-letter queue. Deciding those on
     * {@code finalPermittedDelivery} instead would abandon them back to the broker four more times,
     * spending the whole delivery budget re-reading a payload that reads the same every time, and
     * parking it at the end under {@code DELIVERY_LIMIT_EXHAUSTED} — a reason that says the service
     * ran out of tries, not that the payload was unusable. Support reads that reason.
     */
    @Nested
    @DisplayName("a failure the ports classify as non-transient")
    class NonTransientRunFailure {

        private final GuardDecision parked = new GuardDecision.DeadLetter(
                DeadLetterReason.NON_TRANSIENT, ReasonCode.TRANSFORMATION_FAILED);

        @Test
        void should_park_the_request_at_once_although_deliveries_remain() {
            when(guard.admit(command, delivery)).thenReturn(new GuardDecision.Run(claim));
            when(payloadSource.fetch(command))
                    .thenThrow(new TransformationFailedException("unreadable hearing"));
            when(guard.recordNonTransientFailure(claim, ReasonCode.TRANSFORMATION_FAILED))
                    .thenReturn(parked);

            final GuardDecision decision = pipeline.process(command, delivery);

            verify(guard).recordNonTransientFailure(claim, ReasonCode.TRANSFORMATION_FAILED);
            verify(guard, never()).recordTransientFailure(any(), any());
            verify(guard, never()).recordExhaustion(any(), any());
            assertThat(decision).isEqualTo(parked);
        }

        @Test
        void should_carry_the_reason_a_submission_rejection_names_rather_than_a_generic_one() {
            when(guard.admit(command, delivery)).thenReturn(new GuardDecision.Run(claim));
            when(payloadSource.fetch(command)).thenThrow(new SubmissionFailedException(
                    FailureClassification.NON_TRANSIENT, ReasonCode.CONTRACT_VALIDATION_FAILED));
            when(guard.recordNonTransientFailure(claim, ReasonCode.CONTRACT_VALIDATION_FAILED))
                    .thenReturn(new GuardDecision.DeadLetter(
                            DeadLetterReason.NON_TRANSIENT, ReasonCode.CONTRACT_VALIDATION_FAILED));

            pipeline.process(command, delivery);

            verify(guard).recordNonTransientFailure(claim, ReasonCode.CONTRACT_VALIDATION_FAILED);
        }

        @Test
        void should_hand_back_a_submission_failure_that_says_it_is_worth_retrying() {
            when(guard.admit(command, delivery)).thenReturn(new GuardDecision.Run(claim));
            when(payloadSource.fetch(command)).thenThrow(new SubmissionFailedException(
                    FailureClassification.TRANSIENT, ReasonCode.PIPELINE_TRANSIENT_FAILURE));
            when(guard.recordTransientFailure(claim, ReasonCode.PIPELINE_TRANSIENT_FAILURE))
                    .thenReturn(new GuardDecision.Abandon(ReasonCode.PIPELINE_TRANSIENT_FAILURE));

            pipeline.process(command, delivery);

            verify(guard).recordTransientFailure(claim, ReasonCode.PIPELINE_TRANSIENT_FAILURE);
            verify(guard, never()).recordNonTransientFailure(any(), any());
        }

        @Test
        void should_count_the_parked_run_as_non_transient() {
            when(guard.admit(command, delivery)).thenReturn(new GuardDecision.Run(claim));
            when(payloadSource.fetch(command))
                    .thenThrow(new TransformationFailedException("unreadable hearing"));
            when(guard.recordNonTransientFailure(claim, ReasonCode.TRANSFORMATION_FAILED))
                    .thenReturn(parked);

            pipeline.process(command, delivery);

            assertThat(counter(ProcessingMetrics.PROCESSING_FAILURES,
                    ProcessingMetrics.CLASSIFICATION_TAG, "non-transient")).isEqualTo(1.0);
            assertThat(counter(ProcessingMetrics.PROCESSED,
                    ProcessingMetrics.OUTCOME_TAG, "failed")).isEqualTo(1.0);
        }

        @Test
        void should_never_complete_a_run_it_parked() {
            when(guard.admit(command, delivery)).thenReturn(new GuardDecision.Run(claim));
            when(payloadSource.fetch(command))
                    .thenThrow(new TransformationFailedException("unreadable hearing"));
            when(guard.recordNonTransientFailure(claim, ReasonCode.TRANSFORMATION_FAILED))
                    .thenReturn(parked);

            pipeline.process(command, delivery);

            verify(guard, never()).recordCompletion(any(), any());
            verifyNoInteractions(submissionClient);
        }
    }

    // --- a failure that says whether it is worth retrying (design rules, state machine) --------

    /**
     * What the pipeline does with a failure that carries its own classification.
     *
     * <p>A submission failure is one of the two kinds there are — the class above covers a
     * transformation that cannot read its payload. The Results command refusing a body is not
     * worth a redelivery, while a 5xx or an unresolved outcome is. Everything else the run can meet
     * is transient by construction.
     *
     * <p><strong>Why the failure is raised at the payload port.</strong> The classified failure's
     * production source is the submission port, and that port cannot be reached from any test yet —
     * the transformation is not wired into the run, so it produces an empty authority set and
     * submits nothing (spec
     * FR-010), which the suite above asserts. What is under test here is the run frame's handling of
     * a classified failure, and the frame does not care which line of the run raised it; injecting
     * it at the port that can be reached tests the branch that exists rather than mocking a
     * transformation that does not.
     */
    @Nested
    @DisplayName("a failure that carries its classification")
    class ClassifiedRunFailure {

        private final SubmissionFailedException refused = new SubmissionFailedException(
                FailureClassification.NON_TRANSIENT, ReasonCode.SUBMISSION_REJECTED);
        private final SubmissionFailedException unresolved = new SubmissionFailedException(
                FailureClassification.TRANSIENT, ReasonCode.PIPELINE_TRANSIENT_FAILURE);

        private final GuardDecision parked = new GuardDecision.DeadLetter(
                DeadLetterReason.NON_TRANSIENT, ReasonCode.SUBMISSION_REJECTED);

        private DeliveryIdentity theRunFailsWith(
                final SubmissionFailedException failure, final boolean lastChance) {
            final DeliveryIdentity identity = new DeliveryIdentity(MESSAGE_ID, OWNER, lastChance);
            when(guard.admit(command, identity)).thenReturn(new GuardDecision.Run(claim));
            when(payloadSource.fetch(command)).thenThrow(failure);
            return identity;
        }

        @Test
        void should_park_a_refusal_at_once_though_the_queue_would_deliver_it_again() {
            final DeliveryIdentity identity = theRunFailsWith(refused, false);
            when(guard.recordNonTransientFailure(claim, ReasonCode.SUBMISSION_REJECTED))
                    .thenReturn(parked);

            final GuardDecision decision = pipeline.process(command, identity);

            assertThat(decision).isEqualTo(parked);
            verify(guard).recordNonTransientFailure(claim, ReasonCode.SUBMISSION_REJECTED);
            verify(guard, never()).recordTransientFailure(any(), any());
            verify(guard, never()).recordExhaustion(any(), any());
        }

        @Test
        void should_report_the_reason_the_failure_carried_rather_than_a_catch_all() {
            final DeliveryIdentity identity = theRunFailsWith(refused, false);
            when(guard.recordNonTransientFailure(claim, ReasonCode.SUBMISSION_REJECTED))
                    .thenReturn(parked);

            pipeline.process(command, identity);

            verify(guard, never())
                    .recordNonTransientFailure(claim, ReasonCode.UNEXPECTED_FAILURE);
            assertThat(counter(ProcessingMetrics.PROCESSING_FAILURES,
                    ProcessingMetrics.CLASSIFICATION_TAG, "non-transient")).isEqualTo(1.0);
        }

        @Test
        void should_count_a_parked_refusal_as_a_failed_request() {
            final DeliveryIdentity identity = theRunFailsWith(refused, false);
            when(guard.recordNonTransientFailure(claim, ReasonCode.SUBMISSION_REJECTED))
                    .thenReturn(parked);

            pipeline.process(command, identity);

            assertThat(counter(ProcessingMetrics.PROCESSED,
                    ProcessingMetrics.OUTCOME_TAG, "failed")).isEqualTo(1.0);
        }

        @Test
        void should_not_count_a_parking_a_superseded_runner_was_refused() {
            final DeliveryIdentity identity = theRunFailsWith(refused, false);
            when(guard.recordNonTransientFailure(claim, ReasonCode.SUBMISSION_REJECTED))
                    .thenReturn(new GuardDecision.Abandon(ReasonCode.STALE_RUNNER));

            pipeline.process(command, identity);

            assertThat(counter(ProcessingMetrics.PROCESSED,
                    ProcessingMetrics.OUTCOME_TAG, "failed")).isZero();
        }

        @Test
        void should_hand_back_an_unresolved_submission_while_deliveries_remain() {
            final DeliveryIdentity identity = theRunFailsWith(unresolved, false);
            final GuardDecision handedBack =
                    new GuardDecision.Abandon(ReasonCode.PIPELINE_TRANSIENT_FAILURE);
            when(guard.recordTransientFailure(claim, ReasonCode.PIPELINE_TRANSIENT_FAILURE))
                    .thenReturn(handedBack);

            final GuardDecision decision = pipeline.process(command, identity);

            assertThat(decision).isEqualTo(handedBack);
            verify(guard, never()).recordNonTransientFailure(any(), any());
        }

        @Test
        void should_park_an_unresolved_submission_on_the_final_permitted_delivery() {
            final DeliveryIdentity identity = theRunFailsWith(unresolved, true);
            final GuardDecision exhausted = new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.DELIVERY_LIMIT_EXHAUSTED);
            when(guard.recordExhaustion(claim, ReasonCode.PIPELINE_TRANSIENT_FAILURE))
                    .thenReturn(exhausted);

            final GuardDecision decision = pipeline.process(command, identity);

            assertThat(decision).isEqualTo(exhausted);
            verify(guard, never()).recordNonTransientFailure(any(), any());
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

        /**
         * The deadline holds <em>inside</em> the submission loop, not merely before it.
         *
         * <p>The clock advances 90s per reading against a 4m deadline, so: the run starts at T0,
         * the transformation lands at T+1m30s, the first submission is checked at T+3m and goes,
         * and the second is checked at T+4m30s — past the deadline, with one authority still
         * unsent.
         *
         * <p>What a check only before the loop costs: each authority can spend
         * {@code max-attempts x (connect + read)} plus capped back-offs, so a run admitted with
         * seconds of budget left POSTs every remaining authority minutes past a lease a redelivery
         * has already reclaimed — and the new runner is granted every authority not yet POSTED.
         * Both runners then POST, and {@code add-informant-register} is not idempotent.
         */
        private void theRunReachesItsDeadlineBetweenSubmissions() {
            when(guard.admit(command, delivery)).thenReturn(new GuardDecision.Run(claim));
            when(payloadSource.fetch(command)).thenReturn(payload());
            when(transformer.transform(any(), any(), any()))
                    .thenReturn(List.of(document(), secondDocument()));
            clock.stepBy(Duration.ofSeconds(90));
            when(guard.recordTransientFailure(claim, ReasonCode.PROCESSING_DEADLINE_EXCEEDED))
                    .thenReturn(handedBack);
        }

        @Test
        void should_abort_when_the_deadline_passes_between_two_submissions() {
            theRunReachesItsDeadlineBetweenSubmissions();

            final GuardDecision decision = pipeline.process(command, delivery);

            verify(guard).recordTransientFailure(claim, ReasonCode.PROCESSING_DEADLINE_EXCEEDED);
            assertThat(decision).isEqualTo(handedBack);
        }

        @Test
        void should_leave_the_authority_it_ran_out_of_time_for_unsent() {
            // Partial progress is the point: the authority already POSTed is recorded as such and
            // skipped on the redelivery, so only the outstanding one is repeated.
            theRunReachesItsDeadlineBetweenSubmissions();

            pipeline.process(command, delivery);

            verify(submissionClient, times(1)).submit(any());
        }

        @Test
        void should_not_record_a_completion_for_a_run_that_submitted_only_some() {
            theRunReachesItsDeadlineBetweenSubmissions();

            pipeline.process(command, delivery);

            verify(guard, never()).recordCompletion(any(), any());
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
            final InformantRegisterDocument document = document();

            final AuthoritySubmission submission =
                    new AuthoritySubmission("RESULTS", UUID.randomUUID(), "PA-1", document,
                            CallerIdentity.SYSTEM);

            assertThat(submission.prosecutionAuthorityId()).isEqualTo("PA-1");
            assertThat(submission.document()).isSameAs(document);
        }

        /**
         * What goes out is typed, and the compiler is what enforces it.
         *
         * <p>Principle IV is not symmetrical: the hearing payload crosses this service as a tree
         * because it is owned elsewhere, while the {@code add-informant-register} body is typed
         * because the contract is closed — {@code additionalProperties: false} — and a field the
         * service cannot name is then a field it cannot send.
         */
        @Test
        void an_authority_submission_should_carry_a_typed_document_not_an_arbitrary_tree() {
            assertThat(AuthoritySubmission.class.getRecordComponents())
                    .filteredOn(component -> "document".equals(component.getName()))
                    .singleElement()
                    .extracting(RecordComponent::getType)
                    .isEqualTo(InformantRegisterDocument.class);
        }
    }
}
