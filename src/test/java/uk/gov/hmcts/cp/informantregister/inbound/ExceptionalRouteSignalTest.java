package uk.gov.hmcts.cp.informantregister.inbound;

import java.util.List;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.informantregister.application.DistributionPipeline;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.FailureClassification;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.RunClaim;
import uk.gov.hmcts.cp.informantregister.support.CapturedLog;
import uk.gov.hmcts.cp.informantregister.support.QueueHealthTestSupport;
import uk.gov.hmcts.cp.informantregister.support.StoreGateTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spec FR-017, applied to the routes nobody plans for.
 *
 * <p>Two obligations, on <em>every</em> failure: a sanitised ERROR a person can act on, and a
 * movement in an instrument an alert can fire on. The planned failures have both. The exceptional
 * ones — a body that cannot validate, a fault nothing anticipated, a decision that reached
 * settlement when it should have reached the guard — had the log and not the metric, which is the
 * half nobody notices is missing until an incident is being reconstructed from a dashboard that
 * says the service was fine.
 *
 * <p>The other half is correlation. A contract-validation failure was reported with no identifiers
 * at all, so the single search a support engineer performs — "show me everything about this
 * request" — returned nothing for precisely the messages somebody was asking about. The producer
 * usually did supply them: an unknown extra field leaves the other six untouched. They are read
 * back out only where they are canonical, so nothing a producer wrote reaches the index by being
 * called {@code requestId}.
 */
class ExceptionalRouteSignalTest {

    private static final int MAX_DELIVERY_COUNT = 5;

    private static final String REQUEST_ID = "requestId";
    private static final String HEARING_ID = "hearingId";
    private static final String HEARING_DAY = "hearingDay";

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    private final DistributionPipeline pipeline = mock(DistributionPipeline.class);
    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final ProcessingMetrics metrics = new ProcessingMetrics(registry);

    private final InformantRegisterMessageListener listener =
            new InformantRegisterMessageListener(
                    new DistributionCommandParser(JacksonConfig.contractObjectMapper()),
                    pipeline, metrics, QueueHealthTestSupport.unwatched(),
                    StoreGateTestSupport.open(), MAX_DELIVERY_COUNT);

    // --- fixtures --------------------------------------------------------------------------

    private String bodyWithAnUnknownField() {
        return """
                {
                  "source": "RESULTS",
                  "requestId": "%s",
                  "hearingId": "%s",
                  "hearingDay": "2026-08-21",
                  "sharedTime": "2026-08-21T08:00:00Z",
                  "eventType": "Hearing_Resulted",
                  "courtCentreId": "abc"
                }
                """.formatted(requestId, hearingId);
    }

    private String validBody() {
        return bodyWithAnUnknownField().replace(",\n  \"courtCentreId\": \"abc\"", "");
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

    private static List<ILoggingEvent> errorsIn(final CapturedLog log) {
        return log.events().stream()
                .filter(event -> Level.ERROR.equals(event.getLevel()))
                .toList();
    }

    private double transientFailures() {
        final Counter found = registry.find(ProcessingMetrics.PROCESSING_FAILURES)
                .tag(ProcessingMetrics.CLASSIFICATION_TAG, FailureClassification.TRANSIENT.label())
                .counter();
        return found == null ? 0 : found.count();
    }

    // --- correlation on a rejected body ------------------------------------------------------

    @Test
    @DisplayName("a rejected body is still findable by the identifiers it carried")
    void should_correlate_a_contract_validation_failure_it_could_read_the_identifiers_from() {
        try (CapturedLog log = CapturedLog.of(InformantRegisterMessageListener.class)) {
            listener.onMessage(deliveryOf(bodyWithAnUnknownField()));

            final List<ILoggingEvent> errors = errorsIn(log);
            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().getMDCPropertyMap())
                    .as("the six agreed fields were all there; only the seventh was the problem")
                    .containsEntry(REQUEST_ID, requestId.toString())
                    .containsEntry(HEARING_ID, hearingId.toString())
                    .containsEntry(HEARING_DAY, "2026-08-21");
            assertThat(errors.getFirst().getFormattedMessage())
                    .as("and the offending name is still not quoted")
                    .doesNotContain("courtCentreId");
        }
    }

    @Test
    @DisplayName("a body that yields no identifiers is reported without inventing any")
    void should_report_an_unreadable_body_with_no_correlation_at_all() {
        try (CapturedLog log = CapturedLog.of(InformantRegisterMessageListener.class)) {
            listener.onMessage(deliveryOf("{ this is not json at all"));

            final List<ILoggingEvent> errors = errorsIn(log);
            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().getMDCPropertyMap())
                    .as("absent is the honest answer; a placeholder would be searched for and found")
                    .doesNotContainKeys(REQUEST_ID, HEARING_ID, HEARING_DAY);
        }
    }

    @Test
    @DisplayName("a value that is not canonical is not correlated on")
    void should_ignore_an_identifier_that_is_not_the_shape_the_contract_requires() {
        final String hostile = validBody()
                .replace(requestId.toString(), "not-a-uuid-<script>alert(1)</script>");

        try (CapturedLog log = CapturedLog.of(InformantRegisterMessageListener.class)) {
            listener.onMessage(deliveryOf(hostile));

            assertThat(errorsIn(log)).hasSize(1);
            assertThat(errorsIn(log).getFirst().getMDCPropertyMap())
                    .as("being called requestId is not a reason to write something out")
                    .doesNotContainKey(REQUEST_ID);
        }
    }

    // --- an instrument on every exceptional route ------------------------------------------------

    @Test
    @DisplayName("a fault nothing anticipated moves a failure counter, not only a log line")
    void should_count_an_unexpected_failure() {
        when(pipeline.process(any(DistributionCommand.class), any(DeliveryIdentity.class)))
                .thenThrow(new IllegalStateException("something nobody planned for"));
        final double before = transientFailures();

        try (CapturedLog log = CapturedLog.of(InformantRegisterMessageListener.class)) {
            listener.onMessage(deliveryOf(validBody()));

            assertThat(errorsIn(log))
                    .as("reported once")
                    .hasSize(1);
            assertThat(transientFailures())
                    .as("and counted, so a dashboard cannot say the service was fine")
                    .isEqualTo(before + 1);
        }
    }

    @Test
    @DisplayName("a run that reached settlement moves a failure counter, not only a log line")
    void should_count_a_run_decision_that_reached_settlement() {
        when(pipeline.process(any(DistributionCommand.class), any(DeliveryIdentity.class)))
                .thenReturn(new GuardDecision.Run(new RunClaim(
                        "RESULTS", requestId, "instance/lock", UUID.randomUUID(), "RESULTS:message")));
        final double before = transientFailures();

        try (CapturedLog log = CapturedLog.of(InformantRegisterMessageListener.class)) {
            listener.onMessage(deliveryOf(validBody()));

            assertThat(errorsIn(log)).hasSize(1);
            assertThat(transientFailures())
                    .as("a defect in this service is still a failure of the delivery")
                    .isEqualTo(before + 1);
        }
    }
}
