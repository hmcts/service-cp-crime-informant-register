package uk.gov.hmcts.cp.informantregister.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.azure.core.amqp.exception.AmqpErrorCondition;
import com.azure.core.amqp.exception.AmqpErrorContext;
import com.azure.core.amqp.exception.AmqpException;
import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.application.DistributionPipeline;
import uk.gov.hmcts.cp.informantregister.application.HearingPayloadSource;
import uk.gov.hmcts.cp.informantregister.application.IdempotencyGuard;
import uk.gov.hmcts.cp.informantregister.application.RegisterSubmissionClient;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.RunClaim;
import uk.gov.hmcts.cp.informantregister.inbound.DistributionCommandParser;
import uk.gov.hmcts.cp.informantregister.inbound.InformantRegisterMessageListener;
import uk.gov.hmcts.cp.informantregister.inbound.ServiceBusConsumerConfig;
import uk.gov.hmcts.cp.informantregister.support.CapturedLog;
import uk.gov.hmcts.cp.informantregister.support.QueueHealthTestSupport;
import uk.gov.hmcts.cp.informantregister.support.StoreGateTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What this service is allowed to write down (constitution Principle VII, spec FR-012).
 *
 * <p>The rules are not the same at every level, and the difference matters:
 *
 * <ul>
 *   <li><strong>Correlation is required at INFO and above.</strong> Every line about processing
 *       carries {@code requestId}, {@code hearingId} and {@code hearingDay}, because a line that
 *       cannot be tied to a request is a line nobody can act on (spec SC-005).</li>
 *   <li><strong>Defendant data is forbidden at INFO and above</strong> — names, dates of birth,
 *       addresses, and the free text that hides them.</li>
 *   <li><strong>Whole payloads are forbidden at every level</strong> in a deployed configuration.
 *       Not "kept out of INFO", not "only at DEBUG in practice": there is no level at which a
 *       hearing payload may be written by the code this build ships, because the level a deployed
 *       environment runs at is not this repository's decision to rely on.</li>
 *   <li><strong>Secrets are forbidden at every level</strong>, and a connection string is a secret
 *       whether or not the log line calls it one.</li>
 * </ul>
 *
 * <p>Every assertion is made against a capture of <em>everything</em>, at TRACE, including the
 * rendered text of any exception attached to a line. A stack trace reaches a log index exactly as a
 * message does, and an exception somebody else wrote is the commonest way a payload fragment or a
 * credential escapes.
 *
 * <p>The markers are deliberately implausible strings. A test looking for the word "name" would
 * fail on a field called {@code loggerName}; a test looking for a value nothing else could produce
 * fails only when that value really was written.
 */
class TelemetryPrivacyTest {

    /** The correlation set every processing line must carry (spec FR-012, technical-rules MDC). */
    private static final Set<String> CORRELATION =
            Set.of("source", "requestId", "hearingId", "hearingDay");

    private static final String PAYLOAD_MARKER = "PAYLOADMARKERZQX7";
    private static final String MESSAGE_ID_MARKER = "MESSAGEIDMARKERZQX7";
    private static final String FIELD_NAME_MARKER = "FIELDNAMEMARKERZQX7";
    private static final String TRANSPORT_MARKER = "TRANSPORTMARKERZQX7";
    private static final String SETTLEMENT_MARKER = "SETTLEMENTMARKERZQX7";
    private static final String ADAPTER_MARKER = "ADAPTERMARKERZQX7";
    private static final String DEFENDANT_MARKER = "DEFENDANTMARKERZQX7";
    private static final String BODY_MARKER = "BODYMARKERZQX7";
    private static final String SECRET_MARKER = "SECRETMARKERZQX7";

    private static final int MAX_DELIVERY_COUNT = 5;
    private static final Duration RUN_DEADLINE = Duration.ofMinutes(4);

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    // --- fixtures --------------------------------------------------------------------------

    /**
     * A payload shaped like the ones the real adapter will fetch: large, foreign, and full of the
     * personal data this service must never write down.
     */
    private JsonNode hearingPayload() {
        return JacksonConfig.contractObjectMapper().readTree("""
                {
                  "hearingId": "%s",
                  "marker": "%s",
                  "defendants": [
                    {
                      "name": "%s",
                      "dateOfBirth": "1985-04-02",
                      "address": "12 Example Street, Exampleton",
                      "nationalInsuranceNumber": "QQ123456C"
                    }
                  ]
                }
                """.formatted(hearingId, PAYLOAD_MARKER, DEFENDANT_MARKER));
    }

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

    private static ServiceBusReceivedMessageContext deliveryOf(final String body) {
        return deliveryOf(body, "RESULTS:" + UUID.randomUUID());
    }

    private static ServiceBusReceivedMessageContext deliveryOf(
            final String body, final String messageId) {
        final ServiceBusReceivedMessage message = mock(ServiceBusReceivedMessage.class);
        when(message.getBody()).thenReturn(BinaryData.fromString(body));
        when(message.getMessageId()).thenReturn(messageId);
        when(message.getLockToken()).thenReturn(UUID.randomUUID().toString());
        when(message.getDeliveryCount()).thenReturn(0L);

        final ServiceBusReceivedMessageContext context =
                mock(ServiceBusReceivedMessageContext.class);
        when(context.getMessage()).thenReturn(message);
        return context;
    }

    /**
     * The whole delivery path, with a guard that admits and records and ports that behave.
     */
    private InformantRegisterMessageListener listenerOver(final HearingPayloadSource payloads) {
        return new InformantRegisterMessageListener(
                new DistributionCommandParser(JacksonConfig.contractObjectMapper()),
                pipelineOver(payloads),
                new ProcessingMetrics(new SimpleMeterRegistry()),
                QueueHealthTestSupport.unwatched(),
                StoreGateTestSupport.open(),
                MAX_DELIVERY_COUNT);
    }

    private DistributionPipeline pipelineOver(final HearingPayloadSource payloads) {
        final IdempotencyGuard guard = mock(IdempotencyGuard.class);
        final RunClaim claim = new RunClaim(
                "RESULTS", requestId, "instance/lock", UUID.randomUUID(), "RESULTS:message");
        when(guard.admit(any(DistributionCommand.class), any(DeliveryIdentity.class)))
                .thenReturn(new GuardDecision.Run(claim));
        when(guard.recordCompletion(any(RunClaim.class), any(CompletionReason.class)))
                .thenReturn(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));
        when(guard.recordTransientFailure(any(RunClaim.class), any(ReasonCode.class)))
                .thenReturn(new GuardDecision.Abandon(ReasonCode.UNEXPECTED_FAILURE));

        // A transformation that produces nothing: what is under test here is what the run *says*,
        // and a hearing with no register in it exercises every log line the run emits.
        return new DistributionPipeline(
                guard, payloads, (hearing, sharedTime) -> List.of(),
                mock(RegisterSubmissionClient.class),
                new ProcessingMetrics(new SimpleMeterRegistry()), Clock.systemUTC(), RUN_DEADLINE);
    }

    private static List<ILoggingEvent> processingLines(final CapturedLog log) {
        return log.events().stream()
                .filter(event -> event.getLoggerName()
                        .startsWith("uk.gov.hmcts.cp.informantregister"))
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                .toList();
    }

    // --- the payload never reaches the log -----------------------------------------------------

    @Test
    @DisplayName("a hearing payload is not written at any level, and its defendants least of all")
    void should_never_log_the_hearing_payload_or_anything_in_it() {
        final HearingPayloadSource payloads = mock(HearingPayloadSource.class);
        when(payloads.fetch(any(DistributionCommand.class))).thenReturn(hearingPayload());

        try (CapturedLog log = CapturedLog.everything()) {
            listenerOver(payloads).onMessage(deliveryOf(validBody()));

            assertThat(log.renderings())
                    .as("no level, anywhere, may write the payload out")
                    .noneMatch(line -> line.contains(PAYLOAD_MARKER))
                    .as("and a defendant's own details least of all")
                    .noneMatch(line -> line.contains(DEFENDANT_MARKER));
        }
    }

    @Test
    @DisplayName("a message body is never quoted back, whether it validates or not")
    void should_never_log_the_message_body() {
        final HearingPayloadSource payloads = mock(HearingPayloadSource.class);
        when(payloads.fetch(any(DistributionCommand.class))).thenReturn(hearingPayload());
        final InformantRegisterMessageListener listener = listenerOver(payloads);

        final String unparseable = "{ this is not json " + BODY_MARKER;
        final String unknownField = """
                {
                  "source": "RESULTS",
                  "requestId": "%s",
                  "hearingId": "%s",
                  "hearingDay": "2026-08-21",
                  "sharedTime": "2026-08-21T08:00:00Z",
                  "eventType": "Hearing_Resulted",
                  "extra": "%s"
                }
                """.formatted(requestId, hearingId, BODY_MARKER);

        try (CapturedLog log = CapturedLog.everything()) {
            listener.onMessage(deliveryOf(unparseable));
            listener.onMessage(deliveryOf(unknownField));

            assertThat(log.renderings())
                    .as("a rejection says what rule was broken, never what the producer sent")
                    .noneMatch(line -> line.contains(BODY_MARKER));
        }
    }

    // --- correlation ---------------------------------------------------------------------------

    @Test
    @DisplayName("every processing line at INFO and above carries the correlation identifiers")
    void should_correlate_every_processing_line() {
        final HearingPayloadSource payloads = mock(HearingPayloadSource.class);
        when(payloads.fetch(any(DistributionCommand.class))).thenReturn(hearingPayload());

        try (CapturedLog log = CapturedLog.everything()) {
            listenerOver(payloads).onMessage(deliveryOf(validBody()));

            final List<ILoggingEvent> lines = processingLines(log);
            assertThat(lines)
                    .as("a delivery that logged nothing would satisfy the assertion below vacuously")
                    .isNotEmpty();
            for (final ILoggingEvent line : lines) {
                assertThat(line.getMDCPropertyMap())
                        .as("uncorrelated line: %s", line.getFormattedMessage())
                        .containsKeys(CORRELATION.toArray(String[]::new));
            }
        }
    }

    // --- secrets ---------------------------------------------------------------------------

    @Test
    @DisplayName("a connection string is never written out, not even while it is being used")
    void should_never_log_the_broker_credential() {
        final InformantRegisterProperties properties = credentialledWith(
                "Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;"
                        + "SharedAccessKey=" + SECRET_MARKER + ";UseDevelopmentEmulator=true;");

        try (CapturedLog log = CapturedLog.everything()) {
            // Building the client is where the settings are read and announced. Nothing connects:
            // the processor client is lazy, so this exercises the logging and not the broker.
            new ServiceBusConsumerConfig()
                    .informantRegisterProcessorClient(
                            properties,
                            listenerOver(mock(HearingPayloadSource.class)),
                            new ServiceBusHealthIndicator(
                                    Duration.ofSeconds(60),
                                    new ProcessingMetrics(new SimpleMeterRegistry()),
                                    Clock.systemUTC()))
                    .close();

            assertThat(log.renderings())
                    .as("the startup line names which credential source was chosen, never the credential")
                    .noneMatch(line -> line.contains(SECRET_MARKER));
        }
    }

    private static InformantRegisterProperties credentialledWith(final String connectionString) {
        return new InformantRegisterProperties(
                new InformantRegisterProperties.Consumer(true),
                new InformantRegisterProperties.Servicebus(
                        connectionString, null, "informantregister.requests", 2, MAX_DELIVERY_COUNT,
                        Duration.ofMinutes(5), Duration.ofSeconds(60)),
                new InformantRegisterProperties.Claim(Duration.ofMinutes(5), RUN_DEADLINE),
                new InformantRegisterProperties.Store(Duration.ofSeconds(10)),
                new InformantRegisterProperties.Stub(PayloadFailureMode.NONE),
                new InformantRegisterProperties.Payload(
                        PayloadSourceMode.STUB,
                        new InformantRegisterProperties.Redis("localhost", 6379, null, false,
                                "INT_", Duration.ofSeconds(5), Duration.ofSeconds(5)),
                        new InformantRegisterProperties.Fallback(3, Duration.ofSeconds(1),
                                Duration.ofSeconds(5), Duration.ofSeconds(30))),
                new InformantRegisterProperties.Results(
                        "http://localhost:8080", null, null, 4, Duration.ofMillis(500),
                        Duration.ofSeconds(20), Duration.ofSeconds(5), Duration.ofSeconds(30)));
    }

    // --- the configuration that makes correlation reach the index ---------------------------------

    @Test
    @DisplayName("the shipped logging configuration actually emits the MDC")
    void should_ship_a_logging_configuration_that_carries_the_correlation_fields() throws Exception {
        final String logback =
                Files.readString(Path.of("src", "main", "resources", "logback.xml"));

        assertThat(logback)
                .as("without the MDC provider the identifiers are put in place and then thrown away")
                .contains("<mdc/>");
    }

    // --- everything the outside world chooses the text of -----------------------------------------

    @Test
    @DisplayName("a broker-chosen message identity is never written out")
    void should_never_log_a_message_identity_the_producer_chose() {
        final HearingPayloadSource payloads = mock(HearingPayloadSource.class);
        when(payloads.fetch(any(DistributionCommand.class))).thenReturn(hearingPayload());

        try (CapturedLog log = CapturedLog.everything()) {
            // Two paths that used to quote it: a body that cannot validate, and a store that is
            // not there to check the body against.
            listenerOver(payloads).onMessage(deliveryOf("{ not json", "RESULTS:" + MESSAGE_ID_MARKER));
            listenerWithNoStore(payloads)
                    .onMessage(deliveryOf(validBody(), "RESULTS:" + MESSAGE_ID_MARKER));

            assertThat(log.renderings())
                    .as("the identity is the producer's text, and it lands in the log index verbatim")
                    .noneMatch(line -> line.contains(MESSAGE_ID_MARKER));
        }
    }

    @Test
    @DisplayName("a producer-chosen field name is reported as a placeholder, never as itself")
    void should_never_log_the_name_of_an_unknown_field() {
        final HearingPayloadSource payloads = mock(HearingPayloadSource.class);
        when(payloads.fetch(any(DistributionCommand.class))).thenReturn(hearingPayload());
        final String unknownField = """
                {
                  "source": "RESULTS",
                  "requestId": "%s",
                  "hearingId": "%s",
                  "hearingDay": "2026-08-21",
                  "sharedTime": "2026-08-21T08:00:00Z",
                  "eventType": "Hearing_Resulted",
                  "%s": "anything"
                }
                """.formatted(requestId, hearingId, FIELD_NAME_MARKER);

        try (CapturedLog log = CapturedLog.everything()) {
            listenerOver(payloads).onMessage(deliveryOf(unknownField));

            assertThat(log.renderings())
                    .as("a name that looks harmless is still a name somebody else chose")
                    .noneMatch(line -> line.contains(FIELD_NAME_MARKER));
        }
    }

    @Test
    @DisplayName("a transport fault is reported by its condition, never by its words")
    void should_never_log_the_text_of_a_transport_failure() {
        try (CapturedLog log = CapturedLog.everything()) {
            queueHealth().recordProcessorError(
                    "RECEIVE",
                    "informantregister.requests",
                    new AmqpException(true, AmqpErrorCondition.CONNECTION_FORCED,
                            "the broker said " + TRANSPORT_MARKER,
                            new AmqpErrorContext("sbemulatorns")));

            assertThat(log.renderings())
                    .as("a transport fault's message is written by the far end, not by us")
                    .noneMatch(line -> line.contains(TRANSPORT_MARKER));
        }
    }

    @Test
    @DisplayName("a settlement the broker refuses is reported by its operation, never by its words")
    void should_never_log_the_text_of_a_refused_settlement() {
        final HearingPayloadSource payloads = mock(HearingPayloadSource.class);
        when(payloads.fetch(any(DistributionCommand.class))).thenReturn(hearingPayload());

        final ServiceBusReceivedMessageContext refusing = deliveryOf(validBody());
        doThrow(new IllegalStateException("the broker said " + SETTLEMENT_MARKER))
                .when(refusing).complete();

        try (CapturedLog log = CapturedLog.everything()) {
            listenerOver(payloads).onMessage(refusing);

            assertThat(log.renderings())
                    .noneMatch(line -> line.contains(SETTLEMENT_MARKER));
        }
    }

    @Test
    @DisplayName("an adapter that fails in a way nothing anticipated is reported by its type only")
    void should_never_log_the_text_of_a_payload_adapter_failure() {
        final HearingPayloadSource payloads = mock(HearingPayloadSource.class);
        // The real adapter fetches a hearing payload over a cache client. An exception from one of
        // those routinely quotes the key it was asked for and, on a parse failure, the bytes it
        // choked on — which is the payload, arriving by the back door.
        when(payloads.fetch(any(DistributionCommand.class)))
                .thenThrow(new IllegalStateException("failed reading INT_" + ADAPTER_MARKER));

        try (CapturedLog log = CapturedLog.everything()) {
            listenerOver(payloads).onMessage(deliveryOf(validBody()));

            assertThat(log.renderings())
                    .noneMatch(line -> line.contains(ADAPTER_MARKER));
        }
    }

    private static ServiceBusHealthIndicator queueHealth() {
        return new ServiceBusHealthIndicator(
                Duration.ofSeconds(60), new ProcessingMetrics(new SimpleMeterRegistry()),
                Clock.systemUTC());
    }

    /**
     * The same listener, over a store that is not there.
     */
    private InformantRegisterMessageListener listenerWithNoStore(final HearingPayloadSource payloads) {
        return new InformantRegisterMessageListener(
                new DistributionCommandParser(JacksonConfig.contractObjectMapper()),
                pipelineOver(payloads),
                new ProcessingMetrics(new SimpleMeterRegistry()),
                QueueHealthTestSupport.unwatched(),
                StoreGateTestSupport.closed(),
                MAX_DELIVERY_COUNT);
    }
}
