package uk.gov.hmcts.cp.informantregister.inbound;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import uk.gov.hmcts.cp.informantregister.application.DistributionPipeline;
import uk.gov.hmcts.cp.informantregister.application.HearingPayloadSource;
import uk.gov.hmcts.cp.informantregister.application.IdempotencyGuard;
import uk.gov.hmcts.cp.informantregister.application.RegisterSubmissionClient;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.RunClaim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The settlement contract, asserted one delivery at a time.
 *
 * <p>Every case makes the same structural assertion beneath its own: <strong>exactly one settlement
 * call</strong>, counted across every settlement method and every overload the SDK offers, on every
 * path this handler controls. Two settlements is an error the broker reports and a lock the service
 * no longer holds; none at all is a delivery left to time out — the silent loss this service exists
 * to cure (spec FR-001, constitution Principle VI).
 *
 * <p>The other half is ordering: {@code complete()} may only follow an outcome the guard recorded
 * durably. A delivery acknowledged before the write is a request the processed log has never heard
 * of and the broker will never deliver again.
 */
class MessageListenerSettlementTest {

    /** Every settlement the SDK offers, so the count cannot be fooled by an overload. */
    private static final Set<String> SETTLEMENT_METHODS =
            Set.of("complete", "abandon", "deadLetter", "defer");

    private static final String MESSAGE_ID = "RESULTS:abcd";
    private static final String LOCK_TOKEN = "5a4f2f1e-0000-0000-0000-00000000000a";

    /** The pipeline's own bound; nothing here comes near it. */
    private static final Duration RUN_DEADLINE = Duration.ofMinutes(4);

    /** How long the reworked timing case will wait for a thread to get where it is going. */
    private static final Duration PATIENCE = Duration.ofSeconds(5);

    /**
     * The held write's own escape hatch, deliberately far longer than {@link #PATIENCE}: the fixture
     * must never be the first thing to give up, or a failing assertion would be reported as a
     * timeout in the scaffolding instead of as itself.
     */
    private static final Duration FIXTURE_ESCAPE = Duration.ofSeconds(60);

    /**
     * How long "nothing has been settled" must stay true while the write is held. Short, because it
     * is a window in which a wrong implementation settles immediately, not a race being outwaited.
     */
    private static final Duration WHILE_THE_WRITE_IS_HELD = Duration.ofMillis(300);

    private final DistributionPipeline pipeline = mock(DistributionPipeline.class);
    private final ProcessingMetrics metrics = new ProcessingMetrics(new SimpleMeterRegistry());
    private final DistributionCommandParser parser =
            new DistributionCommandParser(JacksonConfig.contractObjectMapper());

    private final InformantRegisterMessageListener listener =
            new InformantRegisterMessageListener(parser, pipeline, metrics);

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    // --- helpers ---------------------------------------------------------------------------

    private String validBody() {
        return """
                {
                  "source": "RESULTS",
                  "requestId": "%s",
                  "hearingId": "%s",
                  "hearingDay": "2026-08-21",
                  "sharedTime": "2026-08-21T08:00:00Z",
                  "eventType": "Hearing_Resulted"
                }
                """.formatted(requestId, hearingId);
    }

    private ServiceBusReceivedMessageContext deliveryOf(final String body) {
        final ServiceBusReceivedMessage message = mock(ServiceBusReceivedMessage.class);
        when(message.getBody()).thenReturn(BinaryData.fromString(body));
        when(message.getMessageId()).thenReturn(MESSAGE_ID);
        when(message.getLockToken()).thenReturn(LOCK_TOKEN);
        when(message.getDeliveryCount()).thenReturn(1L);

        final ServiceBusReceivedMessageContext context = mock(ServiceBusReceivedMessageContext.class);
        when(context.getMessage()).thenReturn(message);
        return context;
    }

    private ServiceBusReceivedMessageContext validDelivery() {
        return deliveryOf(validBody());
    }

    private void pipelineDecides(final GuardDecision decision) {
        when(pipeline.process(any(DistributionCommand.class), any(DeliveryIdentity.class)))
                .thenReturn(decision);
    }

    /**
     * Every settlement made on this delivery so far, whichever methods they were.
     */
    private static List<String> settlementsOn(final ServiceBusReceivedMessageContext context) {
        return mockingDetails(context).getInvocations().stream()
                .map(invocation -> invocation.getMethod().getName())
                .filter(SETTLEMENT_METHODS::contains)
                .toList();
    }

    /**
     * The structural assertion every case shares: one settlement, whichever one it was.
     */
    private static void assertSettledExactlyOnce(final ServiceBusReceivedMessageContext context) {
        assertThat(settlementsOn(context)).hasSize(1);
    }

    // --- acknowledgement ----------------------------------------------------------------------

    @Nested
    @DisplayName("a delivery whose work the guard has recorded as done")
    class Acknowledged {

        @Test
        void should_acknowledge_it_exactly_once() {
            final ServiceBusReceivedMessageContext context = validDelivery();
            pipelineDecides(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));

            listener.onMessage(context);

            verify(context).complete();
            assertSettledExactlyOnce(context);
        }

        @Test
        void should_neither_hand_back_nor_park_a_delivery_it_acknowledges() {
            final ServiceBusReceivedMessageContext context = validDelivery();
            pipelineDecides(new GuardDecision.Complete(ReasonCode.ALREADY_COMPLETED));

            listener.onMessage(context);

            verify(context, never()).abandon();
            verify(context, never()).deadLetter(any(DeadLetterOptions.class));
        }
    }

    // --- when the acknowledgement happens ---------------------------------------------------------

    /**
     * The half of spec FR-001 that an invocation-order check cannot reach.
     *
     * <p>Verifying that {@code complete()} was recorded after the pipeline call proves only that the
     * two calls were <em>entered</em> in that order — Mockito records an invocation on entry — and it
     * says nothing at all about the guard, because a mocked pipeline never reaches one. An
     * implementation that acknowledged the delivery while the outcome write was still in flight would
     * satisfy it, and that implementation loses requests: the broker deletes the message, the write
     * then fails, and the processed log has never heard of a request that will never be delivered
     * again.
     *
     * <p>So this case uses the real pipeline over a guard whose durable write blocks, and asserts on
     * the delivery <em>while the write has not returned</em>: no settlement of any kind may have
     * happened yet. Only when the write is released may the acknowledgement appear.
     */
    @Nested
    @DisplayName("the moment a delivery is acknowledged")
    class AcknowledgementTiming {

        private final IdempotencyGuard guard = mock(IdempotencyGuard.class);
        private final HearingPayloadSource payloadSource = mock(HearingPayloadSource.class);
        private final RegisterSubmissionClient submissionClient = mock(RegisterSubmissionClient.class);

        /** Raised by the fixture the instant the outcome write begins. */
        private final CountDownLatch writeInFlight = new CountDownLatch(1);

        /** Lowered by the test when it is ready for the write to return. */
        private final CountDownLatch releaseTheWrite = new CountDownLatch(1);

        /**
         * Unconditionally, so a failing assertion is reported as itself rather than as the held
         * write timing out afterwards — and so no blocked thread outlives the case.
         */
        @AfterEach
        void releaseAnyHeldWrite() {
            releaseTheWrite.countDown();
        }

        @Test
        void should_not_acknowledge_until_the_outcome_write_has_returned() throws InterruptedException {
            final RunClaim claim = new RunClaim(
                    "RESULTS", requestId, "runner-1/" + LOCK_TOKEN, UUID.randomUUID(), MESSAGE_ID);
            when(guard.admit(any(DistributionCommand.class), any(DeliveryIdentity.class)))
                    .thenReturn(new GuardDecision.Run(claim));
            when(payloadSource.fetch(any(DistributionCommand.class)))
                    .thenReturn(JacksonConfig.contractObjectMapper().readTree("{}"));
            when(guard.recordCompletion(claim, CompletionReason.NO_AUTHORITIES))
                    .thenAnswer(aDurableWriteThatTakesItsTime());

            final ServiceBusReceivedMessageContext context = validDelivery();
            final Thread delivery = new Thread(() -> listenerOverTheRealPipeline().onMessage(context),
                    "one-delivery");
            delivery.start();

            assertThat(writeInFlight.await(PATIENCE.toSeconds(), TimeUnit.SECONDS))
                    .as("the outcome write should have started")
                    .isTrue();
            await().during(WHILE_THE_WRITE_IS_HELD).atMost(PATIENCE)
                    .until(() -> settlementsOn(context).isEmpty());

            releaseTheWrite.countDown();
            delivery.join(PATIENCE.toMillis());

            verify(context).complete();
            assertSettledExactlyOnce(context);
        }

        private InformantRegisterMessageListener listenerOverTheRealPipeline() {
            return new InformantRegisterMessageListener(
                    parser,
                    new DistributionPipeline(guard, payloadSource, submissionClient, metrics,
                            Clock.systemUTC(), RUN_DEADLINE),
                    metrics);
        }

        /** A write that has been issued and has not yet come back — a slow store, in one fixture. */
        private Answer<GuardDecision> aDurableWriteThatTakesItsTime() {
            return invocation -> {
                writeInFlight.countDown();
                if (!releaseTheWrite.await(FIXTURE_ESCAPE.toSeconds(), TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the outcome write was never released");
                }
                return new GuardDecision.Complete(ReasonCode.RUN_COMPLETED);
            };
        }
    }

    // --- return for retry ---------------------------------------------------------------------

    @Nested
    @DisplayName("a delivery the guard hands back")
    class HandedBack {

        @Test
        void should_abandon_it_exactly_once_and_never_acknowledge_it() {
            final ServiceBusReceivedMessageContext context = validDelivery();
            pipelineDecides(new GuardDecision.Abandon(ReasonCode.CLAIM_NOT_ACQUIRED));

            listener.onMessage(context);

            verify(context).abandon();
            verify(context, never()).complete();
            assertSettledExactlyOnce(context);
        }
    }

    // --- parking --------------------------------------------------------------------------------

    @Nested
    @DisplayName("a delivery the guard parks")
    class Parked {

        @Test
        void should_dead_letter_it_exactly_once_with_a_bounded_reason_and_description() {
            final ServiceBusReceivedMessageContext context = validDelivery();
            pipelineDecides(new GuardDecision.DeadLetter(
                    DeadLetterReason.COLLISION, ReasonCode.IDEMPOTENCY_COLLISION));

            listener.onMessage(context);

            final ArgumentCaptor<DeadLetterOptions> parked =
                    ArgumentCaptor.forClass(DeadLetterOptions.class);
            verify(context).deadLetter(parked.capture());
            assertThat(parked.getValue().getDeadLetterReason())
                    .isEqualTo(DeadLetterReason.COLLISION.label());
            assertThat(parked.getValue().getDeadLetterErrorDescription())
                    .isEqualTo(ReasonCode.IDEMPOTENCY_COLLISION.code());
            assertSettledExactlyOnce(context);
        }

        @Test
        void should_never_acknowledge_a_delivery_it_parks() {
            final ServiceBusReceivedMessageContext context = validDelivery();
            pipelineDecides(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.DELIVERY_LIMIT_EXHAUSTED));

            listener.onMessage(context);

            verify(context, never()).complete();
        }
    }

    // --- the paths that never reach the guard -----------------------------------------------------

    @Nested
    @DisplayName("a body that does not satisfy the contract")
    class ContractInvalid {

        @Test
        void should_settle_the_delivery_exactly_once_without_running_the_pipeline() {
            final ServiceBusReceivedMessageContext context = deliveryOf("{\"source\":\"RESULTS\"}");

            listener.onMessage(context);

            verifyNoInteractions(pipeline);
            assertSettledExactlyOnce(context);
        }

        @Test
        void should_never_acknowledge_a_body_it_could_not_read() {
            final ServiceBusReceivedMessageContext context = deliveryOf("not json at all");

            listener.onMessage(context);

            verify(context, never()).complete();
            assertSettledExactlyOnce(context);
        }
    }

    @Nested
    @DisplayName("a run that fails in a way nothing anticipated")
    class UnexpectedFailure {

        @Test
        void should_hand_the_delivery_back_rather_than_acknowledge_work_that_did_not_happen() {
            final ServiceBusReceivedMessageContext context = validDelivery();
            when(pipeline.process(any(DistributionCommand.class), any(DeliveryIdentity.class)))
                    .thenThrow(new IllegalStateException("the store went away mid-run"));

            listener.onMessage(context);

            verify(context).abandon();
            verify(context, never()).complete();
            assertSettledExactlyOnce(context);
        }
    }

    // --- what the guard is told about the delivery -------------------------------------------------

    @Nested
    @DisplayName("the identity the delivery is processed under")
    class Identity {

        @Test
        void should_carry_the_broker_message_id_and_a_runner_identity_naming_this_delivery() {
            final ServiceBusReceivedMessageContext context = validDelivery();
            pipelineDecides(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));

            listener.onMessage(context);

            final ArgumentCaptor<DeliveryIdentity> identity =
                    ArgumentCaptor.forClass(DeliveryIdentity.class);
            verify(pipeline).process(any(DistributionCommand.class), identity.capture());
            assertThat(identity.getValue().messageId()).isEqualTo(MESSAGE_ID);
            assertThat(identity.getValue().claimOwner()).contains(LOCK_TOKEN);
        }

        @Test
        void should_carry_the_command_the_body_parsed_to() {
            final ServiceBusReceivedMessageContext context = validDelivery();
            pipelineDecides(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));

            listener.onMessage(context);

            final ArgumentCaptor<DistributionCommand> parsed =
                    ArgumentCaptor.forClass(DistributionCommand.class);
            verify(pipeline).process(parsed.capture(), any(DeliveryIdentity.class));
            assertThat(parsed.getValue().requestId()).isEqualTo(requestId);
            assertThat(parsed.getValue().hearingId()).isEqualTo(hearingId);
        }
    }
}
