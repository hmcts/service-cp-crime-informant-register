package uk.gov.hmcts.cp.informantregister.e2e;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

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
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.application.HearingPayloadSource;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.FailureClassification;
import uk.gov.hmcts.cp.informantregister.domain.PayloadUnavailableException;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.RequestOutcome;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Spec SC-003, end to end and in full: a request that keeps failing is retried, parked visibly after
 * exactly five deliveries, and then reprocessed to completion from one resubmission.
 *
 * <p>Three separate facts are asserted, because the story is only useful if all three hold:
 *
 * <ul>
 *   <li><strong>Every failed delivery is recorded</strong> — RETRYING, the bounded failure reason,
 *       and the cumulative attempt count. Support can see a request struggling before it is parked.</li>
 *   <li><strong>The fifth delivery parks it</strong> — FAILED with the identity of the delivery that
 *       exhausted the budget, and the message on the dead-letter queue carrying this service's own
 *       reason. Exactly five deliveries: parking on the fourth loses a retry the queue was willing to
 *       give, and letting the broker park it on the sixth means the record never says FAILED at all
 *       and no dead-letter reason of ours ever reaches the queue.</li>
 *   <li><strong>A resubmission replays it</strong> — under a fresh message identity, attempts
 *       preserved rather than reset, ending COMPLETED with six cumulative attempts.</li>
 * </ul>
 *
 * <p>The state each run saw when it started is captured from the payload port rather than polled:
 * a poller racing broker redelivery would read whichever state it happened to catch, and the
 * intermediate RETRYING rows would be asserted only by luck. Reading the row at the point the run
 * begins is deterministic — the single-runner claim guarantees the runs are sequential — and it is
 * the honest question anyway: what did the processed log say when this attempt started?
 *
 * <p><strong>Why the port is a test double.</strong> The stub's failure switch is a configuration
 * property read once at construction (spec FR-009 deliberately keeps it out of the message and off
 * any endpoint), and this scenario needs the fault repaired <em>between</em> the parking and the
 * replay, inside one running service. The double is still test-controlled configuration of the
 * adapter — the property's purpose, with the timing the scenario requires — and it is scoped to this
 * suite's own request: a neighbouring suite's message travelling the shared queue is handed the
 * placeholder and passes through untouched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// The context owns a running consumer on the shared emulator queue. Closing it with the class stops
// that consumer competing with the suites that run after this one.
class DeliveryExhaustionIT {

    /** Five deliveries, each with a broker round trip between them. */
    private static final Duration PARKED_WITHIN = Duration.ofSeconds(120);

    private static final Duration REPLAYED_WITHIN = Duration.ofSeconds(60);

    /** The queue's delivery budget, and the number of deliveries this request must receive. */
    private static final int PERMITTED_DELIVERIES = 5;

    private static final String DELIVERY_RECEIVED = "Delivery received.";

    private static String connectionString;

    /**
     * Nothing that resembles hearing content: this increment handles no defendant data, and a
     * placeholder that looked like a payload would invite an assertion to depend on its shape.
     */
    private static final JsonNode PLACEHOLDER =
            JacksonConfig.contractObjectMapper().readTree("{\"stub\":true}");

    @MockitoBean
    private HearingPayloadSource payloadSource;

    @Autowired
    private MeterRegistry registry;

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    /** False while the request must fail; raised when support has fixed whatever was wrong. */
    private final AtomicBoolean payloadAvailable = new AtomicBoolean(false);

    /** The processed-log row as each run of this request saw it at the moment it started. */
    private final List<Row> runStartedWith = new CopyOnWriteArrayList<>();

    private CapturedLog deliveryLog;

    @DynamicPropertySource
    static void wireTheContainers(final DynamicPropertyRegistry registry) {
        connectionString = ServiceBusEmulatorTestSupport.connectionString();
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
        registry.add("informantregister.servicebus.connection-string", () -> connectionString);
        ServiceTestSupport.stubPayloadSource(registry);
        // CJSCPPUID. The service refuses to start without one, because a command sent
        // anonymously is a command Results refuses; no suite here ever reaches Results.
        registry.add("informantregister.results.system-user-id",
                () -> ServiceTestSupport.SYSTEM_USER_ID);
    }

    @BeforeEach
    void controlThePayloadPortAndWatchEveryDelivery() {
        when(payloadSource.fetch(any(DistributionCommand.class))).thenAnswer(this::payloadFor);
        deliveryLog = CapturedLog.of(InformantRegisterMessageListener.class);
    }

    @AfterEach
    void releaseTheDeliveryLog() {
        deliveryLog.close();
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * The payload port, for this request and for anybody else's.
     *
     * <p>A neighbouring suite's message on the shared queue is none of this suite's business and is
     * handed the placeholder, so it completes and leaves rather than being dragged into this
     * scenario's failure.
     */
    private JsonNode payloadFor(final InvocationOnMock invocation) {
        final DistributionCommand command = invocation.getArgument(0);
        if (!requestId.equals(command.requestId())) {
            return PLACEHOLDER;
        }
        runStartedWith.add(ProcessedLogTestSupport.requireRow(ProcessedLogTestSupport.SOURCE, requestId));
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
     * Publishes this request under a fresh broker identity, and returns that identity.
     *
     * <p>Fresh every time: the queue has duplicate detection on, so a resubmission reusing an
     * identity already seen would be discarded by the broker and the replay would never arrive.
     */
    private String publish() {
        final String messageId = "RESULTS:" + UUID.randomUUID();
        try (ServiceBusSenderClient sender = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .sender()
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

    private Row requireRow() {
        return ProcessedLogTestSupport.requireRow(ProcessedLogTestSupport.SOURCE, requestId);
    }

    /**
     * The broker's delivery count for every delivery of this request the listener has seen.
     *
     * <p>Read from the listener's own receipt line, filtered by the correlation identifier it puts
     * in place, so it counts deliveries rather than runs: a delivery that arrived and did no work
     * would still be counted here, and this suite's claim is about the broker's budget.
     */
    private List<Long> deliveryCounts() {
        return deliveryLog.events().stream()
                .filter(event -> requestId.toString().equals(event.getMDCPropertyMap().get("requestId")))
                .filter(event -> event.getFormattedMessage().startsWith(DELIVERY_RECEIVED))
                .map(event -> (Long) event.getArgumentArray()[2])
                .toList();
    }

    private double counter(final String name, final String tag, final String value) {
        final Counter counter = registry.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }

    /** The three counters this scenario moves, so they can be asserted as deltas. */
    private record Signals(double transientFailures, double parkedRequests, double deadLettered) {

        Signals minus(final Signals earlier) {
            return new Signals(
                    transientFailures - earlier.transientFailures,
                    parkedRequests - earlier.parkedRequests,
                    deadLettered - earlier.deadLettered);
        }
    }

    private Signals signals() {
        return new Signals(
                counter(ProcessingMetrics.PROCESSING_FAILURES,
                        ProcessingMetrics.CLASSIFICATION_TAG, FailureClassification.TRANSIENT.label()),
                counter(ProcessingMetrics.PROCESSED,
                        ProcessingMetrics.OUTCOME_TAG, RequestOutcome.FAILED.label()),
                counter(ProcessingMetrics.DEAD_LETTERED,
                        ProcessingMetrics.REASON_TAG, DeadLetterReason.EXHAUSTED.label()));
    }

    // --- retried, parked, resubmitted -------------------------------------------------------------

    @Test
    @DisplayName("five failed deliveries park the request; one fresh identity replays it to completion")
    void should_park_after_exactly_five_deliveries_and_complete_on_a_fresh_identity() {
        final Signals before = signals();

        final String exhausting = publish();

        await().atMost(PARKED_WITHIN).until(() ->
                ServiceBusEmulatorTestSupport.peekFor(exhausting, SubQueue.DEAD_LETTER_QUEUE).isPresent());

        assertEveryFailedDeliveryWasRecorded();
        assertTheFifthDeliveryParkedTheRequest(exhausting);
        assertTheMessageWasParkedByThisService(exhausting);

        payloadAvailable.set(true);
        final String replay = publish();

        assertTheReplayRanAndCompleted(replay);

        assertThat(signals().minus(before))
                .as("every failed run counted, one request parked, one dead-letter performed")
                .isEqualTo(new Signals(PERMITTED_DELIVERIES, 1, 1));
    }

    /**
     * What the processed log said at the start of each of the five runs.
     *
     * <p>The first run starts against a freshly inserted RECEIVED row; every later run starts against
     * the RETRYING row its predecessor's failure recorded, carrying the bounded reason and one more
     * attempt. That is spec US3-1, asserted five times rather than asserted once and assumed.
     */
    private void assertEveryFailedDeliveryWasRecorded() {
        assertThat(runStartedWith).hasSize(PERMITTED_DELIVERIES);
        assertThat(runStartedWith.get(0).status()).isEqualTo(RequestStatus.RECEIVED.name());
        assertThat(runStartedWith.get(0).attempts()).isEqualTo(1);
        assertThat(runStartedWith.get(0).failureReason()).isNull();

        for (int run = 1; run < PERMITTED_DELIVERIES; run++) {
            final Row seen = runStartedWith.get(run);
            assertThat(seen.status())
                    .as("the previous delivery's failure was recorded before this one arrived")
                    .isEqualTo(RequestStatus.RETRYING.name());
            assertThat(seen.attempts()).isEqualTo(run + 1);
            assertThat(seen.failureReason())
                    .isEqualTo(ReasonCode.PIPELINE_TRANSIENT_FAILURE.code());
        }
    }

    private void assertTheFifthDeliveryParkedTheRequest(final String exhausting) {
        final Row parked = requireRow();
        assertThat(parked.status()).isEqualTo(RequestStatus.FAILED.name());
        assertThat(parked.attempts()).isEqualTo(PERMITTED_DELIVERIES);
        assertThat(parked.failureReason()).isEqualTo(ReasonCode.PIPELINE_TRANSIENT_FAILURE.code());
        assertThat(parked.exhaustedMessageId())
                .as("the identity of the delivery that exhausted the budget, written with the status")
                .isEqualTo(exhausting);
        assertThat(parked.claimOwner()).isNull();
        assertThat(parked.completionReason()).isNull();

        assertThat(deliveryCounts())
                .as("exactly five deliveries, and the broker counts them from zero")
                .containsExactly(0L, 1L, 2L, 3L, 4L);
    }

    /**
     * The dead-letter is this service's, not the broker's.
     *
     * <p>A message the broker parks once the delivery budget runs out carries the broker's own
     * reason and leaves no FAILED record behind it. The reason on the queue is therefore the
     * difference between a request that was parked and a request that was merely dropped.
     */
    private void assertTheMessageWasParkedByThisService(final String exhausting) {
        final ServiceBusReceivedMessage parked = ServiceBusEmulatorTestSupport
                .peekFor(exhausting, SubQueue.DEAD_LETTER_QUEUE).orElseThrow();
        assertThat(parked.getDeadLetterReason()).isEqualTo(DeadLetterReason.EXHAUSTED.label());
        assertThat(parked.getDeadLetterErrorDescription())
                .isEqualTo(ReasonCode.DELIVERY_LIMIT_EXHAUSTED.code());
        assertThat(ServiceBusEmulatorTestSupport.peekFor(exhausting, SubQueue.NONE)).isEmpty();
    }

    private void assertTheReplayRanAndCompleted(final String replay) {
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
        assertThat(runStartedWith)
                .as("the replay really ran; it was not acknowledged as already done")
                .hasSize(PERMITTED_DELIVERIES + 1);

        await().atMost(REPLAYED_WITHIN).until(() ->
                ServiceBusEmulatorTestSupport.peekFor(replay, SubQueue.NONE).isEmpty());
        assertThat(ServiceBusEmulatorTestSupport.peekFor(replay, SubQueue.DEAD_LETTER_QUEUE)).isEmpty();
    }
}
