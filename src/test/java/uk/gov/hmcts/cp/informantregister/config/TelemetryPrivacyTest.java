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

    /** The correlation set every processing line must carry (spec FR-012). */
    private static final Set<String> CORRELATION =
            Set.of("requestId", "hearingId", "hearingDay");

    private static final String PAYLOAD_MARKER = "PAYLOADMARKERZQX7";
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
        final ServiceBusReceivedMessage message = mock(ServiceBusReceivedMessage.class);
        when(message.getBody()).thenReturn(BinaryData.fromString(body));
        when(message.getMessageId()).thenReturn("RESULTS:" + UUID.randomUUID());
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
        final IdempotencyGuard guard = mock(IdempotencyGuard.class);
        final RunClaim claim = new RunClaim(
                "RESULTS", requestId, "instance/lock", UUID.randomUUID(), "RESULTS:message");
        when(guard.admit(any(DistributionCommand.class), any(DeliveryIdentity.class)))
                .thenReturn(new GuardDecision.Run(claim));
        when(guard.recordCompletion(any(RunClaim.class), any(CompletionReason.class)))
                .thenReturn(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));

        final DistributionPipeline pipeline = new DistributionPipeline(
                guard, payloads, mock(RegisterSubmissionClient.class),
                new ProcessingMetrics(new SimpleMeterRegistry()), Clock.systemUTC(), RUN_DEADLINE);

        return new InformantRegisterMessageListener(
                new DistributionCommandParser(JacksonConfig.contractObjectMapper()),
                pipeline,
                new ProcessingMetrics(new SimpleMeterRegistry()),
                QueueHealthTestSupport.unwatched(),
                StoreGateTestSupport.open(),
                MAX_DELIVERY_COUNT);
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
                new InformantRegisterProperties.Stub(PayloadFailureMode.NONE));
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
}
