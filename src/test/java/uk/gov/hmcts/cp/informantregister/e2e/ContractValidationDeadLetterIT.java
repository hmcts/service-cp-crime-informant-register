package uk.gov.hmcts.cp.informantregister.e2e;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.models.SubQueue;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.inbound.InformantRegisterMessageListener;
import uk.gov.hmcts.cp.informantregister.support.CapturedLog;
import uk.gov.hmcts.cp.informantregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ServiceBusEmulatorTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A body that can never be valid is parked at once — spec FR-003, on a real broker.
 *
 * <p>Retrying a message that cannot validate is pointless: no redelivery turns an unknown field into
 * a known one. Worse, it is destructive, because the delivery budget is spent on the impossible and
 * the message eventually reaches the dead-letter queue under the <em>broker's</em> reason rather than
 * this service's, with nothing recorded about what was actually wrong with it.
 *
 * <p>Four bodies, one per failure class the closed contract recognises. Each is asserted on four
 * counts:
 *
 * <ul>
 *   <li><strong>Parked immediately</strong>, with this service's own bounded reason on the queue.</li>
 *   <li><strong>One delivery only</strong> — the budget is untouched, so a genuine transient failure
 *       on some later message still gets all five of its attempts.</li>
 *   <li><strong>No processed-request record</strong>. The state machine starts only once validation
 *       has passed; an invalid body may not even carry a usable key, and a row keyed on a value the
 *       parser rejected would be a record of something that never happened.</li>
 *   <li><strong>Counted</strong>, under the validation reason, so the parking is visible without
 *       reading the queue.</li>
 * </ul>
 *
 * <p>The reason travelling with the message is asserted to be exactly the bounded code — never a
 * parser message and never a fragment of the body. Both are producer-influenced content, and the
 * dead-letter reason is read by support tooling and shipped to the log index.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// The context owns a running consumer on the shared emulator queue. Closing it with the class stops
// that consumer competing with the suites that run after this one.
class ContractValidationDeadLetterIT {

    private static final Duration PARKED_WITHIN = Duration.ofSeconds(60);

    /**
     * How long "still only one delivery" must hold. Short, because a wrong implementation hands the
     * body back and the broker returns it within milliseconds — this is not a race being outwaited.
     */
    private static final Duration NO_FURTHER_DELIVERY_WITHIN = Duration.ofSeconds(5);

    private static String connectionString;

    @Autowired
    private MeterRegistry registry;

    private CapturedLog deliveryLog;

    @DynamicPropertySource
    static void wireTheContainers(final DynamicPropertyRegistry registry) {
        connectionString = ServiceBusEmulatorTestSupport.connectionString();
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
        registry.add("informantregister.servicebus.connection-string", () -> connectionString);
    }

    @BeforeEach
    void watchEveryDelivery() {
        deliveryLog = CapturedLog.of(InformantRegisterMessageListener.class);
    }

    @AfterEach
    void releaseTheDeliveryLog() {
        deliveryLog.close();
    }

    // --- the corpus ------------------------------------------------------------------------

    /**
     * One body per failure class.
     *
     * @param label     what is wrong with it, for the test name
     * @param body      the message body
     * @param requestId the key the body claims, or {@code null} when it does not carry one — a body
     *                  that is not JSON at all has no key to look a record up by, which is precisely
     *                  why an invalid message is accounted for by its dead-letter entry rather than
     *                  by the processed log
     */
    record Invalid(String label, String body, UUID requestId) {

        @Override
        public String toString() {
            return label;
        }
    }

    static Stream<Invalid> invalidBodies() {
        final UUID missingField = UUID.randomUUID();
        final UUID unknownField = UUID.randomUUID();
        final UUID badEnum = UUID.randomUUID();
        return Stream.of(
                new Invalid("a body that is not JSON at all", "not json at all", null),
                new Invalid("a body missing a required field", """
                        {
                          "source": "RESULTS",
                          "requestId": "%s",
                          "hearingId": "%s",
                          "hearingDay": "2026-08-21",
                          "sharedTime": "2026-08-21T08:00:00Z"
                        }
                        """.formatted(missingField, UUID.randomUUID()), missingField),
                new Invalid("a body carrying a field the closed contract does not declare", """
                        {
                          "source": "RESULTS",
                          "requestId": "%s",
                          "hearingId": "%s",
                          "hearingDay": "2026-08-21",
                          "sharedTime": "2026-08-21T08:00:00Z",
                          "eventType": "Hearing_Resulted",
                          "defendantName": "a field this service never agreed to receive"
                        }
                        """.formatted(unknownField, UUID.randomUUID()), unknownField),
                new Invalid("a body whose eventType is outside the agreed enumeration", """
                        {
                          "source": "RESULTS",
                          "requestId": "%s",
                          "hearingId": "%s",
                          "hearingDay": "2026-08-21",
                          "sharedTime": "2026-08-21T08:00:00Z",
                          "eventType": "SJP_Resulted"
                        }
                        """.formatted(badEnum, UUID.randomUUID()), badEnum));
    }

    // --- helpers ---------------------------------------------------------------------------

    private static String publish(final String body) {
        final String messageId = "RESULTS:" + UUID.randomUUID();
        try (ServiceBusSenderClient sender = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .sender()
                .queueName(ServiceBusEmulatorTestSupport.QUEUE_NAME)
                .buildClient()) {
            sender.sendMessage(new ServiceBusMessage(BinaryData.fromString(body)).setMessageId(messageId));
        }
        return messageId;
    }

    /**
     * Every failure this body was reported for, from the listener's own ERROR lines.
     *
     * <p>Tied to the report by the correlation the body yielded, not by the broker's identity for
     * the message. Two reasons, and the second is the interesting one. A message identity is text
     * the producer chose and this service will not write it out. And an invalid body is <em>not</em>
     * uncorrelated: an unknown extra field, a missing field, an unagreed enum — each leaves the
     * request id untouched, so the rejection is findable by the identifier support would search
     * for. Only a body that is not JSON at all yields nothing, and this suite carries one on
     * purpose, so both halves of the rule are exercised.
     */
    private List<String> reportsFor(final Invalid invalid) {
        return deliveryLog.events().stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .filter(event -> event.getFormattedMessage().startsWith(VALIDATION_REPORT))
                .filter(event -> reportsOn(event, invalid))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static boolean reportsOn(final ILoggingEvent event, final Invalid invalid) {
        final String correlated = event.getMDCPropertyMap().get(REQUEST_ID);
        return invalid.requestId() == null
                ? correlated == null
                : invalid.requestId().toString().equals(correlated);
    }

    private static final String VALIDATION_REPORT = "Message body failed contract validation";

    private static final String REQUEST_ID = "requestId";

    private double deadLetteredAsInvalid() {
        final Counter counter = registry.find(ProcessingMetrics.DEAD_LETTERED)
                .tag(ProcessingMetrics.REASON_TAG, DeadLetterReason.VALIDATION.label())
                .counter();
        return counter == null ? 0 : counter.count();
    }

    // --- parked at once, and only once ------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    @DisplayName("a body that cannot validate is parked immediately, unrecorded and uncounted against")
    void should_park_an_invalid_body_without_spending_a_delivery_or_writing_a_record(
            final Invalid invalid) {

        final double deadLetteredBefore = deadLetteredAsInvalid();

        final String messageId = publish(invalid.body());

        await().atMost(PARKED_WITHIN).until(() ->
                ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE).isPresent());

        final ServiceBusReceivedMessage parked = ServiceBusEmulatorTestSupport
                .peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE).orElseThrow();
        assertThat(parked.getDeadLetterReason())
                .as("parked by this service under the validation reason, not by the broker's own rule")
                .isEqualTo(DeadLetterReason.VALIDATION.label());
        assertThat(parked.getDeadLetterErrorDescription())
                .as("a bounded code, never a parser message and never a fragment of the body")
                .isEqualTo(ReasonCode.CONTRACT_VALIDATION_FAILED.code());
        assertThat(ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.NONE)).isEmpty();

        await().during(NO_FURTHER_DELIVERY_WITHIN).atMost(PARKED_WITHIN)
                .until(() -> reportsFor(invalid).size() == 1);
        assertThat(reportsFor(invalid))
                .as("one delivery, one report: the delivery budget is not spent on the impossible")
                .hasSize(1);

        if (invalid.requestId() != null) {
            assertThat(ProcessedLogTestSupport.row(ProcessedLogTestSupport.SOURCE, invalid.requestId()))
                    .as("the state machine starts only once the contract has been satisfied")
                    .isEmpty();
        }

        assertThat(deadLetteredAsInvalid() - deadLetteredBefore)
                .as("the parking is countable without reading the queue")
                .isEqualTo(1);
    }
}
