package uk.gov.hmcts.cp.informantregister.e2e;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.models.SubQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.gov.hmcts.cp.informantregister.domain.RequestStatus;
import uk.gov.hmcts.cp.informantregister.inbound.InformantRegisterMessageListener;
import uk.gov.hmcts.cp.informantregister.support.CapturedLog;
import uk.gov.hmcts.cp.informantregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport.Row;
import uk.gov.hmcts.cp.informantregister.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ServiceTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The first line of defence, characterised: a republish under an identity the broker has already
 * seen never reaches this service at all (spec US2-3).
 *
 * <p>Nothing here is this service's behaviour. Duplicate detection is a property of the queue, set
 * in {@code docker/servicebus-emulator/config.json} and provisioned the same way in a deployed
 * environment, and the suite exists to pin it: two independent rules depend on the broker really
 * discarding a repeated identity.
 *
 * <p>The first is the reason the resubmission runbook insists on a <strong>fresh</strong> message
 * identity. A support replay that reuses the identity of the message it is replaying is not rejected,
 * not parked and not logged — it is silently dropped, and the operator watches for a register that
 * never arrives. The service cannot detect that case, which is why it is a documented operational
 * rule rather than a code path (spec Assumptions, {@code doc/API_CONTRACTS.md} replay rule).
 *
 * <p>The second is the honest scope of the processed log. It is the <em>second</em> line of defence,
 * catching the duplicates the broker's window has expired on; if the broker discarded nothing, every
 * ordinary republish would arrive and the guard would be doing work the queue was configured to make
 * unnecessary.
 *
 * <p>Acceptance over committed configuration, so it may legitimately pass on introduction: what it
 * records is the observed behaviour of the emulator this repository pins, not a change to this
 * service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// The context owns a running consumer on the shared emulator queue. Closing it with the class stops
// that consumer competing with the suites that run after this one.
class DuplicateDetectionIT {

    private static final Duration PROCESSED_WITHIN = Duration.ofSeconds(60);

    /**
     * How long the republished identity must stay unseen. A broker that was not discarding it would
     * deliver it within milliseconds, so this is a window in which the wrong behaviour shows itself,
     * not a race being outwaited.
     */
    private static final Duration STAYS_UNSEEN_FOR = Duration.ofSeconds(10);

    private static final String DELIVERY_RECEIVED = "Delivery received.";

    private static String connectionString;

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    /** The identity used for both sends — the whole point of the suite. */
    private final String messageId = "RESULTS:" + UUID.randomUUID();

    private CapturedLog deliveryLog;

    @DynamicPropertySource
    static void wireTheContainers(final DynamicPropertyRegistry registry) {
        connectionString = ServiceBusEmulatorTestSupport.connectionString();
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
        registry.add("informantregister.servicebus.connection-string", () -> connectionString);
        // CJSCPPUID. The service refuses to start without one, because a command sent
        // anonymously is a command Results refuses; no suite here ever reaches Results.
        registry.add("informantregister.results.system-user-id",
                () -> ServiceTestSupport.SYSTEM_USER_ID);
    }

    @BeforeEach
    void watchEveryDelivery() {
        deliveryLog = CapturedLog.of(InformantRegisterMessageListener.class);
    }

    @AfterEach
    void releaseTheDeliveryLog() {
        deliveryLog.close();
    }

    // --- helpers ---------------------------------------------------------------------------

    private String body() {
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
     * Sends the same body under the same identity, every time.
     */
    private void publish() {
        try (ServiceBusSenderClient sender = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .sender()
                .queueName(ServiceBusEmulatorTestSupport.QUEUE_NAME)
                .buildClient()) {
            sender.sendMessage(
                    new ServiceBusMessage(BinaryData.fromString(body())).setMessageId(messageId));
        }
    }

    private Optional<Row> row() {
        return ProcessedLogTestSupport.row(ProcessedLogTestSupport.SOURCE, requestId);
    }

    private int deliveriesSeen() {
        return (int) deliveryLog.events().stream()
                .filter(event -> requestId.toString().equals(event.getMDCPropertyMap().get("requestId")))
                .filter(event -> event.getFormattedMessage().startsWith(DELIVERY_RECEIVED))
                .count();
    }

    // --- the broker's own dedupe ------------------------------------------------------------------

    @Test
    @DisplayName("a republish under an identity the broker has seen never reaches the service")
    void should_discard_a_republished_identity_inside_the_detection_window() {
        publish();

        await().atMost(PROCESSED_WITHIN).until(() -> row()
                .filter(found -> RequestStatus.COMPLETED.name().equals(found.status()))
                .isPresent());
        final Row afterFirstSend =
                ProcessedLogTestSupport.requireRow(ProcessedLogTestSupport.SOURCE, requestId);
        final Instant firstWrite = afterFirstSend.updatedAt();

        // The same identity again, well inside the queue's five-minute detection window.
        publish();

        await().during(STAYS_UNSEEN_FOR).atMost(PROCESSED_WITHIN)
                .until(() -> deliveriesSeen() == 1);

        assertThat(deliveriesSeen())
                .as("the service saw one delivery; the second send was discarded before it")
                .isEqualTo(1);
        assertThat(ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.NONE))
                .as("the discarded send is not sitting on the queue waiting to be delivered")
                .isEmpty();
        assertThat(ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE))
                .as("a discarded duplicate is not parked either — the broker drops it silently")
                .isEmpty();

        final Row afterSecondSend =
                ProcessedLogTestSupport.requireRow(ProcessedLogTestSupport.SOURCE, requestId);
        assertThat(afterSecondSend.attempts()).isEqualTo(1);
        assertThat(afterSecondSend.updatedAt())
                .as("nothing reached the processed log to write to it")
                .isEqualTo(firstWrite);
    }
}
