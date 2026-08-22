package uk.gov.hmcts.cp.informantregister.e2e;

import java.time.Duration;
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
import uk.gov.hmcts.cp.informantregister.adapter.stub.StubHearingPayloadSource;
import uk.gov.hmcts.cp.informantregister.adapter.results.ResultsRegisterSubmissionClient;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.RequestStatus;
import uk.gov.hmcts.cp.informantregister.support.CapturedLog;
import uk.gov.hmcts.cp.informantregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport.Row;
import uk.gov.hmcts.cp.informantregister.support.ServiceBusEmulatorTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The skeleton, walking: a real broker, a real store, the real application context, and one message.
 *
 * <p>This is spec SC-006's demonstrable end-to-end sequence and the quickstart's single command. It
 * asserts the four things the increment claims — the request is recorded and completed with
 * {@code no-authorities}, the payload port really was reached, the submission port really was not,
 * and the message really left the queue without being parked — plus the duplicate rule, which is why
 * the processed log exists at all.
 *
 * <p>The stub evidence is read from the log rather than from a spied bean on purpose. "The payload
 * stub logged its invocation" is the observation an operator makes about a running pod, and asserting
 * it the same way keeps the stub's loudness a tested property rather than a courtesy.
 *
 * <p>Every assertion is keyed on this test's own request and message identities. The queue is shared
 * with the other broker suites, so "the queue is empty" would be a claim about them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// The context owns a running consumer on the shared emulator queue. Closing it with the class stops
// that consumer competing with the suites that run after this one.
class WalkingSkeletonIT {

    private static final Duration PROCESSED_WITHIN = Duration.ofSeconds(60);
    private static final Duration NO_FURTHER_RUN_WITHIN = Duration.ofSeconds(10);

    private static String connectionString;

    private CapturedLog payloadStubLog;
    private CapturedLog submissionStubLog;

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    @DynamicPropertySource
    static void wireTheContainers(final DynamicPropertyRegistry registry) {
        connectionString = ServiceBusEmulatorTestSupport.connectionString();
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
        registry.add("informantregister.servicebus.connection-string", () -> connectionString);
    }

    @BeforeEach
    void captureTheStubLogs() {
        payloadStubLog = CapturedLog.of(StubHearingPayloadSource.class);
        submissionStubLog = CapturedLog.of(ResultsRegisterSubmissionClient.class);
    }

    @AfterEach
    void releaseTheStubLogs() {
        payloadStubLog.close();
        submissionStubLog.close();
    }

    // --- helpers ---------------------------------------------------------------------------

    private static ServiceBusClientBuilder clients() {
        return new ServiceBusClientBuilder().connectionString(connectionString);
    }

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
     * Publishes this test's request under a fresh broker identity, and returns that identity.
     *
     * <p>Fresh every time: the queue has duplicate detection on, so a republish under the original
     * identity would be discarded by the broker and the test would be asserting nothing.
     */
    private String publish() {
        final String messageId = "RESULTS:" + UUID.randomUUID();
        try (ServiceBusSenderClient sender = clients().sender()
                .queueName(ServiceBusEmulatorTestSupport.QUEUE_NAME)
                .buildClient()) {
            sender.sendMessage(
                    new ServiceBusMessage(BinaryData.fromString(body())).setMessageId(messageId));
        }
        return messageId;
    }

    private Optional<Row> row() {
        return ProcessedLogTestSupport.row(ProcessedLogTestSupport.SOURCE, requestId);
    }

    private Row completedRow() {
        await().atMost(PROCESSED_WITHIN).until(() ->
                row().filter(found -> RequestStatus.COMPLETED.name().equals(found.status())).isPresent());
        return ProcessedLogTestSupport.requireRow(ProcessedLogTestSupport.SOURCE, requestId);
    }

    // --- the walking skeleton --------------------------------------------------------------------

    @Test
    @DisplayName("one valid request in, one completed record out, and the queue is clear of it")
    void should_process_a_valid_request_end_to_end() {
        final String messageId = publish();

        final Row processed = completedRow();

        assertThat(processed.status()).isEqualTo(RequestStatus.COMPLETED.name());
        assertThat(processed.completionReason()).isEqualTo(CompletionReason.NO_AUTHORITIES.value());
        assertThat(processed.attempts()).isEqualTo(1);
        assertThat(processed.hearingId()).isEqualTo(hearingId);
        assertThat(processed.failureReason()).isNull();
        assertThat(processed.exhaustedMessageId()).isNull();
        assertThat(processed.claimOwner()).isNull();

        assertThat(payloadStubLog.messages())
                .as("the payload port was reached, and said so")
                .isNotEmpty();
        assertThat(submissionStubLog.messages())
                .as("no authorities, so nothing to submit — the port exists and is never called")
                .isEmpty();

        await().atMost(PROCESSED_WITHIN).until(() -> ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.NONE).isEmpty());
        assertThat(ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE)).isEmpty();
    }

    @Test
    @DisplayName("a completed request delivered again is acknowledged without running")
    void should_not_run_a_second_time_for_a_request_already_completed() {
        publish();
        final Row firstRun = completedRow();

        final String secondIdentity = publish();

        await().atMost(PROCESSED_WITHIN)
                .until(() -> ServiceBusEmulatorTestSupport.peekFor(secondIdentity, SubQueue.NONE).isEmpty());
        await().during(NO_FURTHER_RUN_WITHIN).atMost(PROCESSED_WITHIN).until(() ->
                row().filter(found -> found.attempts() == 1).isPresent());

        final Row afterSecondDelivery =
                ProcessedLogTestSupport.requireRow(ProcessedLogTestSupport.SOURCE, requestId);
        assertThat(afterSecondDelivery.attempts()).isEqualTo(1);
        assertThat(afterSecondDelivery.status()).isEqualTo(RequestStatus.COMPLETED.name());
        assertThat(afterSecondDelivery.updatedAt())
                .as("a completed record is not written to again")
                .isEqualTo(firstRun.updatedAt());
        assertThat(ServiceBusEmulatorTestSupport.peekFor(secondIdentity, SubQueue.DEAD_LETTER_QUEUE)).isEmpty();
    }
}
