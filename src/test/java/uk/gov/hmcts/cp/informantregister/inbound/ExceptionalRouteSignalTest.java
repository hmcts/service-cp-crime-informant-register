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
import org.slf4j.MDC;
import uk.gov.hmcts.cp.informantregister.application.DistributionPipeline;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.FailureClassification;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.RunClaim;
import uk.gov.hmcts.cp.informantregister.support.CapturedLog;
import uk.gov.hmcts.cp.informantregister.support.QueueHealthTestSupport;
import uk.gov.hmcts.cp.informantregister.support.StoreGateTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    private static final String SOURCE = "source";
    private static final String REQUEST_ID = "requestId";
    private static final String HEARING_ID = "hearingId";
    private static final String HEARING_DAY = "hearingDay";
    private static final String SEQUENCE_NUMBER = "sequenceNumber";
    private static final String DELIVERY_COUNT = "deliveryCount";

    /** Distinctive, so asserting the broker's handle is not satisfied by a mock's default zero. */
    private static final long SEQUENCE = 4_815_162_342L;

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
        when(message.getSequenceNumber()).thenReturn(SEQUENCE);

        final ServiceBusReceivedMessageContext context =
                mock(ServiceBusReceivedMessageContext.class);
        when(context.getMessage()).thenReturn(message);
        return context;
    }

    /**
     * What the broker stamped, which every line carries whatever else it could not work out.
     *
     * <p>The request identifiers are the producer's to supply and a rejected body may carry none.
     * These two are not: they are put in place before anything is judged, so they are the handle on
     * precisely the lines that have no other one — which is the ERROR routes, the ones a support
     * engineer reaches for first.
     */
    private static void assertJoinableToTheQueue(final ILoggingEvent line) {
        assertThat(line.getMDCPropertyMap())
                .as("line the broker's view cannot be joined to: %s", line.getFormattedMessage())
                .containsEntry(SEQUENCE_NUMBER, Long.toString(SEQUENCE))
                .containsEntry(DELIVERY_COUNT, "0");
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
                    .containsEntry(SOURCE, "RESULTS")
                    .containsEntry(REQUEST_ID, requestId.toString())
                    .containsEntry(HEARING_ID, hearingId.toString())
                    .containsEntry(HEARING_DAY, "2026-08-21");
            assertJoinableToTheQueue(errors.getFirst());
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
                    .doesNotContainKeys(SOURCE, REQUEST_ID, HEARING_ID, HEARING_DAY);
            assertJoinableToTheQueue(errors.getFirst());
        }
    }

    @Test
    @DisplayName("a delivery leaves no correlation behind for the next one's lines")
    void should_clear_every_correlation_key_when_the_delivery_ends() {
        when(pipeline.process(any(DistributionCommand.class), any(DeliveryIdentity.class)))
                .thenReturn(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));

        listener.onMessage(deliveryOf(validBody()));

        assertThat(MDC.get(SOURCE)).isNull();
        assertThat(MDC.get(REQUEST_ID)).isNull();
        assertThat(MDC.get(HEARING_ID)).isNull();
        assertThat(MDC.get(HEARING_DAY)).isNull();
        assertThat(MDC.get(SEQUENCE_NUMBER))
                .as("the broker's handle is this delivery's, and a stale one would be believed")
                .isNull();
        assertThat(MDC.get(DELIVERY_COUNT)).isNull();
    }

    @Test
    @DisplayName("a source the contract does not permit is not correlated on")
    void should_ignore_a_source_value_outside_the_permitted_set() {
        // The security point of the canonical-only rule, applied to the enumerated field: a
        // producer-chosen string must not reach the log index by being called source.
        final String hostile = validBody().replace("\"RESULTS\"", "\"EVIL-SOURCE\"");

        try (CapturedLog log = CapturedLog.of(InformantRegisterMessageListener.class)) {
            listener.onMessage(deliveryOf(hostile));

            final List<ILoggingEvent> errors = errorsIn(log);
            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().getMDCPropertyMap())
                    .as("the other identifiers still correlate; the rejected value stays out")
                    .doesNotContainKey(SOURCE)
                    .containsEntry(REQUEST_ID, requestId.toString());
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
            assertJoinableToTheQueue(errorsIn(log).getFirst());
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
            assertJoinableToTheQueue(errorsIn(log).getFirst());
            assertThat(transientFailures())
                    .as("a defect in this service is still a failure of the delivery")
                    .isEqualTo(before + 1);
        }
    }

    @Test
    @DisplayName("a body that cannot even be read is still settled, reported and counted")
    void should_account_for_a_delivery_whose_body_cannot_be_read() {
        final ServiceBusReceivedMessage message = mock(ServiceBusReceivedMessage.class);
        // The SDK decodes a received message when it is asked for the body, and an empty, corrupt
        // or already-disposed one throws rather than returning something disappointing. Read
        // outside the catch-and-settle boundary that takes the delivery with it: no decision, so no
        // settlement, so a message locked until its lease runs out and then delivered again, four
        // more times, into the same failure.
        when(message.getBody()).thenThrow(new IllegalStateException("the body could not be decoded"));
        when(message.getMessageId()).thenReturn("RESULTS:" + UUID.randomUUID());
        when(message.getLockToken()).thenReturn(UUID.randomUUID().toString());
        when(message.getDeliveryCount()).thenReturn(0L);
        when(message.getSequenceNumber()).thenReturn(SEQUENCE);
        final ServiceBusReceivedMessageContext context =
                mock(ServiceBusReceivedMessageContext.class);
        when(context.getMessage()).thenReturn(message);

        final double before = transientFailures();

        try (CapturedLog log = CapturedLog.of(InformantRegisterMessageListener.class)) {
            listener.onMessage(context);

            assertThat(errorsIn(log))
                    .as("as accounted for as a body that cannot be parsed")
                    .hasSize(1);
            assertJoinableToTheQueue(errorsIn(log).getFirst());
            assertThat(transientFailures()).isEqualTo(before + 1);
        }
        verify(context).abandon();
    }
}
