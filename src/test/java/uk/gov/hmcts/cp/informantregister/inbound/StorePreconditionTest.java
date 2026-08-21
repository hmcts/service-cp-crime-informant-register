package uk.gov.hmcts.cp.informantregister.inbound;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.informantregister.application.DistributionPipeline;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.support.QueueHealthTestSupport;
import uk.gov.hmcts.cp.informantregister.support.StoreGateTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Spec FR-015's ordering, made load-bearing: without a store the body is <strong>not read</strong>.
 *
 * <p>The end-to-end suite shows that a contract-invalid message survives an outage and is parked
 * afterwards, which is the observable consequence. This asserts the mechanism that produces it,
 * because the consequence can be reached by accident. A listener that read and validated the body
 * and only then noticed the outage would look identical from the queue — the message would still be
 * handed back and still be parked on resume — while having already spent judgement it was not fit to
 * make. The difference shows up the day the store outage coincides with a producer release: the
 * bodies that "failed validation" during the outage were never validated against anything.
 *
 * <p>So the assertions are about what did <em>not</em> happen: the body was never asked for, the
 * parser and the pipeline were never touched, and the two obligations that remain — hand this
 * delivery back, and ask for intake to stop — each happened exactly once.
 */
class StorePreconditionTest {

    /** Every settlement the SDK offers, so the count cannot be fooled by an overload. */
    private static final Set<String> SETTLEMENT_METHODS =
            Set.of("complete", "abandon", "deadLetter", "defer");

    private static final int MAX_DELIVERY_COUNT = 5;

    private final DistributionCommandParser parser = mock(DistributionCommandParser.class);
    private final DistributionPipeline pipeline = mock(DistributionPipeline.class);
    private final ProcessingMetrics metrics = new ProcessingMetrics(new SimpleMeterRegistry());
    private final StoreGateTestSupport.Recording gate = StoreGateTestSupport.closed();

    private final InformantRegisterMessageListener listener =
            new InformantRegisterMessageListener(
                    parser, pipeline, metrics, QueueHealthTestSupport.unwatched(),
                    gate, MAX_DELIVERY_COUNT);

    private static ServiceBusReceivedMessage message() {
        final ServiceBusReceivedMessage message = mock(ServiceBusReceivedMessage.class);
        when(message.getMessageId()).thenReturn("RESULTS:" + UUID.randomUUID());
        when(message.getDeliveryCount()).thenReturn(0L);
        return message;
    }

    private static List<String> settlementsOn(final ServiceBusReceivedMessageContext context) {
        return mockingDetails(context).getInvocations().stream()
                .map(invocation -> invocation.getMethod().getName())
                .filter(SETTLEMENT_METHODS::contains)
                .toList();
    }

    @Test
    @DisplayName("with no store the body is never read, and the delivery goes back untouched")
    void should_not_examine_the_body_at_all_when_the_store_is_unavailable() {
        final ServiceBusReceivedMessage message = message();
        final ServiceBusReceivedMessageContext context =
                mock(ServiceBusReceivedMessageContext.class);
        when(context.getMessage()).thenReturn(message);

        listener.onMessage(context);

        verify(message, never()).getBody();
        verifyNoInteractions(parser);
        verifyNoInteractions(pipeline);

        assertThat(settlementsOn(context))
                .as("handed back, exactly once, and never acknowledged or parked")
                .containsExactly("abandon");
        assertThat(gate.suspensionsRequested())
                .as("and intake was asked to stop, because one delivery must not become five")
                .isEqualTo(1);
    }
}
