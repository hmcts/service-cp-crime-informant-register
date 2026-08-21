package uk.gov.hmcts.cp.informantregister.inbound;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import uk.gov.hmcts.cp.informantregister.application.DistributionPipeline;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
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
     * The structural assertion every case shares: one settlement, whichever one it was.
     */
    private static void assertSettledExactlyOnce(final ServiceBusReceivedMessageContext context) {
        final List<String> settlements = mockingDetails(context).getInvocations().stream()
                .map(invocation -> invocation.getMethod().getName())
                .filter(SETTLEMENT_METHODS::contains)
                .toList();
        assertThat(settlements).hasSize(1);
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
        void should_acknowledge_only_after_the_outcome_write_returned() {
            final ServiceBusReceivedMessageContext context = validDelivery();
            pipelineDecides(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));

            listener.onMessage(context);

            final InOrder settlementFollowsTheWrite = inOrder(pipeline, context);
            settlementFollowsTheWrite.verify(pipeline)
                    .process(any(DistributionCommand.class), any(DeliveryIdentity.class));
            settlementFollowsTheWrite.verify(context).complete();
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
