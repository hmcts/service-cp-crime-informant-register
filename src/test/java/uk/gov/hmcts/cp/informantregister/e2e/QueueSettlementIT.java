package uk.gov.hmcts.cp.informantregister.e2e;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.models.SubQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.informantregister.application.DistributionPipeline;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.inbound.DistributionCommandParser;
import uk.gov.hmcts.cp.informantregister.inbound.InformantRegisterMessageListener;
import uk.gov.hmcts.cp.informantregister.support.ServiceBusEmulatorTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The settlement contract again, this time with a broker on the other end of it.
 *
 * <p>{@code MessageListenerSettlementTest} proves the listener calls the right settlement; this
 * proves the broker agrees about what that settlement means. A mock cannot tell you that a completed
 * delivery really leaves the queue, that an abandoned one really comes back with its delivery count
 * incremented, or that a dead-lettered one really carries its reason onto the dead-letter queue —
 * and those three facts are the whole basis of the retry and parking behaviour the later stories
 * build on.
 *
 * <p>The listener is driven through a processor client this suite builds itself, deliberately
 * <em>not</em> through the application's consumer configuration: the point is the settlement, and a
 * suite that also had to boot a context would be proving two things at once.
 *
 * <p>Assertions filter by message identity throughout. The suite shares one emulator queue with the
 * other broker suites, and a case that asserted "the queue is empty" would be asserting something
 * about its neighbours.
 */
class QueueSettlementIT {

    private static final Logger LOG = LoggerFactory.getLogger(QueueSettlementIT.class);

    private static final Duration SETTLED_WITHIN = Duration.ofSeconds(20);
    private static final int PEEK_BATCH = 32;

    private static String connectionString;

    private final DistributionPipeline pipeline = mock(DistributionPipeline.class);

    private final InformantRegisterMessageListener listener = new InformantRegisterMessageListener(
            new DistributionCommandParser(JacksonConfig.contractObjectMapper()),
            pipeline,
            new ProcessingMetrics(new SimpleMeterRegistry()));

    /** Every delivery this suite's processor saw, as (messageId, deliveryCount). */
    private final List<Delivery> observed = new CopyOnWriteArrayList<>();

    private ServiceBusProcessorClient processor;

    private record Delivery(String messageId, long deliveryCount) {
    }

    @BeforeAll
    static void startEmulator() {
        connectionString = ServiceBusEmulatorTestSupport.connectionString();
    }

    @AfterEach
    void stopConsuming() {
        if (processor != null) {
            // Closed before the next case starts, so no two processors in this JVM ever compete for
            // the shared queue. A leaked consumer would make a neighbouring suite fail for reasons
            // that have nothing to do with it.
            processor.close();
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    private static ServiceBusClientBuilder clients() {
        return new ServiceBusClientBuilder().connectionString(connectionString);
    }

    private void consumeWith(final GuardDecision decision) {
        when(pipeline.process(any(DistributionCommand.class), any(DeliveryIdentity.class)))
                .thenReturn(decision);
        processor = clients().processor()
                .queueName(ServiceBusEmulatorTestSupport.QUEUE_NAME)
                .maxConcurrentCalls(1)
                .disableAutoComplete()
                .processMessage(context -> {
                    observed.add(new Delivery(
                            context.getMessage().getMessageId(),
                            context.getMessage().getDeliveryCount()));
                    listener.onMessage(context);
                })
                .processError(error -> LOG.warn("Processor error during the settlement suite: {}",
                        error.getErrorSource()))
                .buildProcessorClient();
        processor.start();
    }

    /**
     * Puts one valid request on the queue under a fresh identity, and returns that identity.
     */
    private static String sendRequest() {
        final String messageId = "RESULTS:" + UUID.randomUUID();
        final String body = """
                {
                  "source": "RESULTS",
                  "requestId": "%s",
                  "hearingId": "%s",
                  "hearingDay": "2026-08-21",
                  "sharedTime": "2026-08-21T08:00:00Z",
                  "eventType": "Hearing_Resulted"
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());
        try (ServiceBusSenderClient sender = clients().sender()
                .queueName(ServiceBusEmulatorTestSupport.QUEUE_NAME)
                .buildClient()) {
            sender.sendMessage(new ServiceBusMessage(BinaryData.fromString(body)).setMessageId(messageId));
        }
        return messageId;
    }

    private List<Delivery> deliveriesOf(final String messageId) {
        return observed.stream().filter(delivery -> messageId.equals(delivery.messageId())).toList();
    }

    /**
     * Peeks a sub-queue for one identity. A fresh receiver each time, so the peek cursor starts at
     * the beginning of the queue rather than wherever the previous poll left it.
     */
    private static Optional<ServiceBusReceivedMessage> peekFor(
            final String messageId, final SubQueue subQueue) {
        try (ServiceBusReceiverClient receiver = clients().receiver()
                .queueName(ServiceBusEmulatorTestSupport.QUEUE_NAME)
                .subQueue(subQueue)
                .buildClient()) {
            return receiver.peekMessages(PEEK_BATCH).stream()
                    .filter(message -> messageId.equals(message.getMessageId()))
                    .findFirst();
        }
    }

    // --- the three settlements, as the broker sees them ------------------------------------------

    @Test
    @DisplayName("an acknowledged delivery leaves the queue and is never delivered again")
    void should_remove_a_completed_delivery_from_the_queue() {
        consumeWith(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));

        final String messageId = sendRequest();

        await().atMost(SETTLED_WITHIN).until(() -> !deliveriesOf(messageId).isEmpty());
        await().atMost(SETTLED_WITHIN)
                .until(() -> peekFor(messageId, SubQueue.NONE).isEmpty());
        assertThat(deliveriesOf(messageId)).hasSize(1);
    }

    @Test
    @DisplayName("a delivery handed back comes round again, counted")
    void should_redeliver_an_abandoned_delivery_with_an_incremented_count() {
        consumeWith(new GuardDecision.Abandon(ReasonCode.CLAIM_NOT_ACQUIRED));

        final String messageId = sendRequest();

        await().atMost(SETTLED_WITHIN).until(() -> deliveriesOf(messageId).size() >= 2);
        assertThat(deliveriesOf(messageId))
                .extracting(Delivery::deliveryCount)
                .startsWith(1L, 2L);
    }

    @Test
    @DisplayName("a parked delivery lands on the dead-letter queue, saying why")
    void should_dead_letter_a_parked_delivery_with_its_reason_and_description() {
        consumeWith(new GuardDecision.DeadLetter(
                DeadLetterReason.COLLISION, ReasonCode.IDEMPOTENCY_COLLISION));

        final String messageId = sendRequest();

        await().atMost(SETTLED_WITHIN)
                .until(() -> peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE).isPresent());
        final ServiceBusReceivedMessage parked =
                peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE).orElseThrow();
        assertThat(parked.getDeadLetterReason()).isEqualTo(DeadLetterReason.COLLISION.label());
        assertThat(parked.getDeadLetterErrorDescription())
                .isEqualTo(ReasonCode.IDEMPOTENCY_COLLISION.code());
        assertThat(peekFor(messageId, SubQueue.NONE)).isEmpty();
    }
}
