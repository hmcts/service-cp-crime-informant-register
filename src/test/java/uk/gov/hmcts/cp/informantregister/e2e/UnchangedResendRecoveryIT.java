package uk.gov.hmcts.cp.informantregister.e2e;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.models.SubQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.application.HearingPayloadSource;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.PayloadUnavailableException;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.RequestStatus;
import uk.gov.hmcts.cp.informantregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport.Row;
import uk.gov.hmcts.cp.informantregister.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ServiceTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Spec 001-replay-by-sequence-number, tasks.md T023: the defect's own recovery path, end to end —
 * a {@code FAILED} request is recovered by re-sending its <strong>unchanged</strong> message (same
 * {@code messageId}, same body) once the broker's duplicate-detection window has elapsed.
 *
 * <p>This is the scenario {@code quickstart.md}'s manual walkthrough describes for support: receive
 * the dead-lettered body and re-send it byte for byte, same identity. Every other suite proving
 * replay (@code FailedReplayIT}, {@code DeliveryExhaustionIT}) deliberately resubmits under a
 * <strong>fresh</strong> identity, specifically to avoid the broker's duplicate-detection window —
 * which means the one case the defect was actually about, an operator reusing the original identity,
 * was otherwise asserted only by the manual quickstart, never by an automated suite.
 *
 * <p>Runs against a second, test-only queue ({@link
 * ServiceBusEmulatorTestSupport#RESEND_WINDOW_TEST_QUEUE_NAME}) rather than the production-mirroring
 * {@link ServiceBusEmulatorTestSupport#QUEUE_NAME}. Its duplicate-detection window is shortened to
 * ten seconds so this suite can wait the window out in CI; every other property — delivery count,
 * lock duration — mirrors the real queue exactly, and {@code DuplicateDetectionIT} is left asserting
 * the real five-minute value against the real queue, so the production-fidelity claim is not weakened
 * anywhere.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// The context owns a running consumer on this suite's own test-only queue. Closing it with the class
// stops that consumer competing with the suites that run after this one.
class UnchangedResendRecoveryIT {

    private static final Duration PARKED_WITHIN = Duration.ofSeconds(120);

    private static final Duration REPLAYED_WITHIN = Duration.ofSeconds(60);

    /** The test-only queue's shortened window (config.json), plus a safety margin. */
    private static final Duration PAST_THE_DUPLICATE_DETECTION_WINDOW = Duration.ofSeconds(12);

    private static final int PERMITTED_DELIVERIES = 5;

    private static String connectionString;

    private static final JsonNode PLACEHOLDER =
            JacksonConfig.contractObjectMapper().readTree("{\"stub\":true,\"hearing\":{}}");

    @MockitoBean
    private HearingPayloadSource payloadSource;

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    /** The one identity used for every send in this suite — the whole point of the scenario. */
    private final String messageId = "RESULTS:" + UUID.randomUUID();

    /** False while the request must fail; raised once the row is confirmed parked. */
    private final AtomicBoolean payloadAvailable = new AtomicBoolean(false);

    @DynamicPropertySource
    static void wireTheContainers(final DynamicPropertyRegistry registry) {
        connectionString = ServiceBusEmulatorTestSupport.connectionString();
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
        registry.add("informantregister.servicebus.connection-string", () -> connectionString);
        registry.add("informantregister.servicebus.queue-name",
                () -> ServiceBusEmulatorTestSupport.RESEND_WINDOW_TEST_QUEUE_NAME);
        ServiceTestSupport.stubPayloadSource(registry);
        // CJSCPPUID. The service refuses to start without one, because a command sent anonymously is
        // a command Results refuses; no suite here ever reaches Results.
        registry.add("informantregister.results.system-user-id",
                () -> ServiceTestSupport.SYSTEM_USER_ID);
    }

    // --- helpers ---------------------------------------------------------------------------

    private JsonNode payload(final InvocationOnMock invocation) {
        if (!payloadAvailable.get()) {
            throw new PayloadUnavailableException(ReasonCode.PIPELINE_TRANSIENT_FAILURE);
        }
        return PLACEHOLDER;
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
     * Sends the same body under the same identity, every time — the scenario under test.
     */
    private void publishUnchanged() {
        try (ServiceBusSenderClient sender = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .sender()
                .queueName(ServiceBusEmulatorTestSupport.RESEND_WINDOW_TEST_QUEUE_NAME)
                .buildClient()) {
            sender.sendMessage(
                    new ServiceBusMessage(BinaryData.fromString(body())).setMessageId(messageId));
        }
    }

    private Optional<Row> row() {
        return ProcessedLogTestSupport.row(ProcessedLogTestSupport.SOURCE, requestId);
    }

    private Row requireRow() {
        return ProcessedLogTestSupport.requireRow(ProcessedLogTestSupport.SOURCE, requestId);
    }

    // --- parked by five real deliveries, recovered by the unchanged resend -----------------------

    @Test
    @DisplayName("an unchanged re-send, after the duplicate-detection window elapses, completes the request")
    void should_recover_a_failed_request_from_an_unchanged_resend_after_the_window() {
        when(payloadSource.fetch(any(DistributionCommand.class))).thenAnswer(this::payload);

        publishUnchanged();

        await().atMost(PARKED_WITHIN).until(() -> row()
                .filter(found -> RequestStatus.FAILED.name().equals(found.status()))
                .isPresent());
        final Row parked = requireRow();
        assertThat(parked.attempts())
                .as("parked after exactly the delivery budget's five attempts")
                .isEqualTo(PERMITTED_DELIVERIES);
        assertThat(parked.exhaustedMessageId())
                .as("the identity of the delivery that exhausted the budget is this suite's own")
                .isEqualTo(messageId);

        // The window is why the resend is admitted at all rather than silently discarded by the
        // broker before this service ever sees it (DuplicateDetectionIT proves the inverse, inside
        // the window, against the real production queue).
        await().atMost(PAST_THE_DUPLICATE_DETECTION_WINDOW.plusSeconds(5))
                .pollDelay(PAST_THE_DUPLICATE_DETECTION_WINDOW)
                .until(() -> true);

        payloadAvailable.set(true);
        publishUnchanged();

        await().atMost(REPLAYED_WITHIN).until(() -> row()
                .filter(found -> RequestStatus.COMPLETED.name().equals(found.status()))
                .isPresent());
        final Row completed = requireRow();
        assertThat(completed.attempts())
                .as("attempts is a lifetime tally: five failures and one successful replay make six")
                .isEqualTo(PERMITTED_DELIVERIES + 1);
        assertThat(completed.completionReason()).isEqualTo(CompletionReason.NO_AUTHORITIES.value());
        assertThat(completed.failureReason()).isNull();
        assertThat(completed.exhaustedMessageId()).isNull();
        assertThat(completed.auditNote())
                .as("the replay leaves a note, because the transition clears the reason it was parked for")
                .startsWith("REPLAYED_AFTER_FAILURE");

        await().atMost(REPLAYED_WITHIN).until(() ->
                ServiceBusEmulatorTestSupport.peekFor(
                        ServiceBusEmulatorTestSupport.RESEND_WINDOW_TEST_QUEUE_NAME, messageId, SubQueue.NONE)
                        .isEmpty());
        // Not asserted empty: the original parking from the first exhaustion is still sitting on the
        // dead-letter queue, under this same identity, exactly as quickstart.md documents ("the
        // dead-letter entry from step 2 is the last one for this request"). Distinguishing that entry
        // from a fresh re-park by messageId alone is impossible here — both would carry the identical
        // identity — which is exactly why the authoritative signal is the row's COMPLETED status
        // above: a re-park would have left it FAILED, not COMPLETED.
    }
}
