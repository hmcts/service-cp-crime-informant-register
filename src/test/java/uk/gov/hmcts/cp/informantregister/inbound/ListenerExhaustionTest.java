package uk.gov.hmcts.cp.informantregister.inbound;

import java.util.UUID;

import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import uk.gov.hmcts.cp.informantregister.application.DistributionPipeline;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.support.QueueHealthTestSupport;
import uk.gov.hmcts.cp.informantregister.support.StoreGateTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The exhaustion rule applied to the two hand-backs the <em>listener</em> makes up itself.
 *
 * <p>The guard escalates the hand-backs it produces, on admission and on the outcome writes. Two
 * more never reach it: {@code STORE_UNAVAILABLE}, returned when the processed log cannot be reached
 * and the body is deliberately never read, and {@code UNEXPECTED_FAILURE}, returned when a fault
 * nothing anticipated escapes the run. Both are manufactured here, in the class that owns the
 * settlement decision, so the guard's rule cannot cover them.
 *
 * <p>Unescalated they are the same silent failure the rule exists to end. Service Bus makes an
 * abandoned message available again immediately with no back-off, so a fault that keeps recurring
 * spends the whole budget back-to-back and the broker parks the message under <em>its own</em>
 * reason: no reason code of ours, no {@code deadlettered} metric, and nothing in the log index to
 * search for. A message parked as {@code STORE_UNAVAILABLE} tells support to look at the database; a
 * message parked as {@code MaxDeliveryCountExceeded} tells them nothing at all, and these are the
 * two failures where "nothing at all" is most expensive, because neither of them is the message's
 * fault.
 *
 * <p><strong>Nothing is written, and nothing could be.</strong> On the store path the log is
 * unreachable — that is the whole premise — and the body was never read, so there is no key to write
 * under. On the unexpected-fault path the listener holds no claim: whatever the run did or did not
 * record, this class is not the holder and every terminal write is predicated on the claim. What the
 * budget buys here is attribution, exactly as it is on the guard's admission paths.
 *
 * <p>Intake is still suspended on the store path. The store being down is a fact about the store, not
 * about this message, and it does not stop being true because this particular delivery was parked.
 */
class ListenerExhaustionTest {

    private static final int MAX_DELIVERY_COUNT = 5;

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    private final DistributionPipeline pipeline = mock(DistributionPipeline.class);
    private final ProcessingMetrics metrics = new ProcessingMetrics(new SimpleMeterRegistry());

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

    /**
     * A delivery at a chosen point in the budget. The count is zero-based, so the last delivery a
     * message is entitled to carries {@code maxDeliveryCount - 1}.
     */
    private ServiceBusReceivedMessageContext deliveryNumbered(final long deliveryCount) {
        final ServiceBusReceivedMessage message = mock(ServiceBusReceivedMessage.class);
        when(message.getBody()).thenReturn(BinaryData.fromString(validBody()));
        when(message.getMessageId()).thenReturn("RESULTS:" + requestId);
        when(message.getLockToken()).thenReturn(UUID.randomUUID().toString());
        when(message.getDeliveryCount()).thenReturn(deliveryCount);

        final ServiceBusReceivedMessageContext context =
                mock(ServiceBusReceivedMessageContext.class);
        when(context.getMessage()).thenReturn(message);
        return context;
    }

    private ServiceBusReceivedMessageContext finalPermittedDelivery() {
        return deliveryNumbered(MAX_DELIVERY_COUNT - 1L);
    }

    private InformantRegisterMessageListener listenerOver(final StoreGate gate) {
        return new InformantRegisterMessageListener(
                new DistributionCommandParser(JacksonConfig.contractObjectMapper()),
                pipeline, metrics, QueueHealthTestSupport.unwatched(), gate, MAX_DELIVERY_COUNT);
    }

    /** What the message was parked as, read off the settlement the broker was actually given. */
    private static DeadLetterOptions parkingOf(final ServiceBusReceivedMessageContext context) {
        final ArgumentCaptor<DeadLetterOptions> parked =
                ArgumentCaptor.forClass(DeadLetterOptions.class);
        verify(context).deadLetter(parked.capture());
        return parked.getValue();
    }

    @Nested
    @DisplayName("the processed log could not be reached")
    class StoreUnavailable {

        @ParameterizedTest
        @ValueSource(longs = {MAX_DELIVERY_COUNT - 1L, MAX_DELIVERY_COUNT})
        @DisplayName("on or beyond the final permitted delivery the message is parked with our own reason")
        void a_store_outage_on_or_beyond_the_final_delivery_should_park_rather_than_abandon(
                final long deliveryCount) {
            final ServiceBusReceivedMessageContext context = deliveryNumbered(deliveryCount);

            listenerOver(StoreGateTestSupport.closed()).onMessage(context);

            assertThat(parkingOf(context))
                    .extracting(DeadLetterOptions::getDeadLetterReason,
                            DeadLetterOptions::getDeadLetterErrorDescription)
                    .containsExactly(
                            DeadLetterReason.EXHAUSTED.label(),
                            ReasonCode.STORE_UNAVAILABLE.code());
            verify(context, never()).abandon();
            verify(context, never()).complete();
        }

        @Test
        @DisplayName("intake still stops: the store is down whatever became of this message")
        void a_store_outage_on_the_final_delivery_should_still_suspend_intake() {
            final StoreGateTestSupport.Recording gate = StoreGateTestSupport.closed();

            listenerOver(gate).onMessage(finalPermittedDelivery());

            assertThat(gate.suspensionsRequested())
                    .as("parking one message does not make the store reachable again")
                    .isEqualTo(1);
        }

        @ParameterizedTest
        @ValueSource(longs = {0L, MAX_DELIVERY_COUNT - 2L})
        @DisplayName("with deliveries remaining the message still goes back untouched")
        void a_store_outage_with_budget_remaining_should_hand_the_delivery_back(
                final long deliveryCount) {
            final ServiceBusReceivedMessageContext context = deliveryNumbered(deliveryCount);

            listenerOver(StoreGateTestSupport.closed()).onMessage(context);

            verify(context).abandon();
            verify(context, never()).deadLetter(any());
            verify(context, never()).complete();
        }
    }

    @Nested
    @DisplayName("a fault nothing anticipated")
    class UnexpectedFailure {

        private void theRunFailsUnexpectedly() {
            when(pipeline.process(any(DistributionCommand.class), any(DeliveryIdentity.class)))
                    .thenThrow(new IllegalStateException("something nobody planned for"));
        }

        @ParameterizedTest
        @ValueSource(longs = {MAX_DELIVERY_COUNT - 1L, MAX_DELIVERY_COUNT})
        @DisplayName("on or beyond the final permitted delivery the message is parked with our own reason")
        void an_unexpected_fault_on_or_beyond_the_final_delivery_should_park_rather_than_abandon(
                final long deliveryCount) {
            theRunFailsUnexpectedly();
            final ServiceBusReceivedMessageContext context = deliveryNumbered(deliveryCount);

            listenerOver(StoreGateTestSupport.open()).onMessage(context);

            assertThat(parkingOf(context))
                    .extracting(DeadLetterOptions::getDeadLetterReason,
                            DeadLetterOptions::getDeadLetterErrorDescription)
                    .containsExactly(
                            DeadLetterReason.EXHAUSTED.label(),
                            ReasonCode.UNEXPECTED_FAILURE.code());
            verify(context, never()).abandon();
            verify(context, never()).complete();
        }

        @ParameterizedTest
        @ValueSource(longs = {0L, MAX_DELIVERY_COUNT - 2L})
        @DisplayName("with deliveries remaining it is still handed back for another attempt")
        void an_unexpected_fault_with_budget_remaining_should_hand_the_delivery_back(
                final long deliveryCount) {
            theRunFailsUnexpectedly();
            final ServiceBusReceivedMessageContext context = deliveryNumbered(deliveryCount);

            listenerOver(StoreGateTestSupport.open()).onMessage(context);

            verify(context).abandon();
            verify(context, never()).deadLetter(any());
            verify(context, never()).complete();
        }
    }
}
