package uk.gov.hmcts.cp.informantregister.inbound;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import uk.gov.hmcts.cp.informantregister.application.DistributionPipeline;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.support.CapturedLog;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.QueueHealthTestSupport;
import uk.gov.hmcts.cp.informantregister.support.StoreGateTestSupport;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Spec FR-015's ordering, made load-bearing: without a store the body is <strong>not read</strong>.
 *
 * <p>The end-to-end suite shows that a contract-invalid message survives an outage and is parked
 * afterwards, which is the observable consequence. This asserts the mechanism that produces it,
 * because the consequence can be reached by accident. A listener that read and validated the body
 * and only then noticed the outage would look identical from the queue — the message would still be
 * handed back and still be parked on resume — while having already spent judgement it was not fit to
 * make. The difference shows up the day the store outage coincides with a producer release: the
 * bodies that "failed validation" during the outage were never validated against anything.
 *
 * <p>So the assertions are about what did <em>not</em> happen: the body was never asked for, the
 * parser and the pipeline were never touched, and the two obligations that remain — hand this
 * delivery back, and ask for intake to stop — each happened exactly once.
 */
class StorePreconditionTest {

    private static final String STORE_UNAVAILABLE = "STORE_UNAVAILABLE";

    /** Every settlement the SDK offers, so the count cannot be fooled by an overload. */
    private static final Set<String> SETTLEMENT_METHODS =
            Set.of("complete", "abandon", "deadLetter", "defer");

    private static final int MAX_DELIVERY_COUNT = 5;

    private final DistributionCommandParser parser = mock(DistributionCommandParser.class);
    private final DistributionPipeline pipeline = mock(DistributionPipeline.class);
    private final ProcessingMetrics metrics = new ProcessingMetrics(new SimpleMeterRegistry());
    private final StoreGateTestSupport.Recording closedGate = StoreGateTestSupport.closed();
    private final StoreGateTestSupport.Recording openGate = StoreGateTestSupport.open();

    private final InformantRegisterMessageListener listener = listenerOver(closedGate);

    private InformantRegisterMessageListener listenerOver(final StoreGate gate) {
        return new InformantRegisterMessageListener(
                parser, pipeline, metrics, QueueHealthTestSupport.unwatched(),
                gate, MAX_DELIVERY_COUNT);
    }

    private static ServiceBusReceivedMessage message() {
        final ServiceBusReceivedMessage message = mock(ServiceBusReceivedMessage.class);
        when(message.getMessageId()).thenReturn("RESULTS:" + UUID.randomUUID());
        when(message.getDeliveryCount()).thenReturn(0L);
        return message;
    }

    private static List<String> settlementsOn(final ServiceBusReceivedMessageContext context) {
        return mockingDetails(context).getInvocations().stream()
                .map(invocation -> invocation.getMethod().getName())
                .filter(SETTLEMENT_METHODS::contains)
                .toList();
    }

    @Test
    @DisplayName("with no store the body is never read, and the delivery goes back untouched")
    void should_not_examine_the_body_at_all_when_the_store_is_unavailable() {
        final ServiceBusReceivedMessage message = message();
        final ServiceBusReceivedMessageContext context =
                mock(ServiceBusReceivedMessageContext.class);
        when(context.getMessage()).thenReturn(message);

        listener.onMessage(context);

        verify(message, never()).getBody();
        verifyNoInteractions(parser);
        verifyNoInteractions(pipeline);

        assertThat(settlementsOn(context))
                .as("handed back, exactly once, and never acknowledged or parked")
                .containsExactly("abandon");
        assertThat(closedGate.suspensionsRequested())
                .as("and intake was asked to stop, because one delivery must not become five")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a store that dies after the precondition still stops intake")
    void should_suspend_intake_when_the_store_fails_after_it_answered_the_precondition() {
        final ServiceBusReceivedMessage message = message();
        when(message.getBody()).thenReturn(BinaryData.fromString("{}"));
        final ServiceBusReceivedMessageContext context =
                mock(ServiceBusReceivedMessageContext.class);
        when(context.getMessage()).thenReturn(message);

        when(parser.parse(any(String.class))).thenReturn(ProcessedLogTestSupport.command());
        when(pipeline.process(any(DistributionCommand.class), any(DeliveryIdentity.class)))
                .thenThrow(new CannotGetJdbcConnectionException(
                        "the pool could not hand out a connection"));

        try (CapturedLog listenerLog = CapturedLog.of(InformantRegisterMessageListener.class)) {
            listenerOver(openGate).onMessage(context);

            assertThat(settlementsOn(context))
                    .as("handed back — never parked, because the message was never the problem")
                    .containsExactly("abandon");
            assertThat(openGate.suspensionsRequested())
                    .as("an outage discovered mid-run is the same outage, and costs the same one "
                            + "delivery only if intake stops")
                    .isEqualTo(1);
            assertThat(listenerLog.events().stream()
                    .filter(event -> Level.ERROR.equals(event.getLevel()))
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList())
                    .as("reported once, under the bounded code a support tool reads")
                    .singleElement(as(InstanceOfAssertFactories.STRING))
                    .contains(STORE_UNAVAILABLE);
        }
    }

    /**
     * The store answering with a complaint is the store <em>answering</em>. A constraint violation
     * or a broken statement is a fault in one request's conversation with a perfectly reachable
     * database; suspending the whole queue for it turns one poison message into an intake outage —
     * and because the controller's probe would find the store healthy, into a suspend/resume cycle
     * repeated on every redelivery. The delivery is still handed back, but the queue keeps moving.
     */
    @Test
    @DisplayName("a statement fault on a reachable store hands the delivery back without stopping intake")
    void should_not_suspend_intake_for_a_statement_fault_the_store_answered_with() {
        final ServiceBusReceivedMessage message = message();
        when(message.getBody()).thenReturn(BinaryData.fromString("{}"));
        final ServiceBusReceivedMessageContext context =
                mock(ServiceBusReceivedMessageContext.class);
        when(context.getMessage()).thenReturn(message);

        when(parser.parse(any(String.class))).thenReturn(ProcessedLogTestSupport.command());
        when(pipeline.process(any(DistributionCommand.class), any(DeliveryIdentity.class)))
                .thenThrow(new DataIntegrityViolationException("a constraint refused the row"));

        listenerOver(openGate).onMessage(context);

        assertThat(settlementsOn(context))
                .as("handed back for redelivery, exactly once")
                .containsExactly("abandon");
        assertThat(openGate.suspensionsRequested())
                .as("but the queue is not stopped: the store answered, so there is no outage")
                .isZero();
    }

    /** The other statement-fault shape the classification names: a statement the store refused. */
    @Test
    @DisplayName("a broken statement on a reachable store does not stop intake either")
    void should_not_suspend_intake_for_a_statement_the_store_refused_to_parse() {
        final StoreGateTestSupport.Recording gate = StoreGateTestSupport.open();

        onMessageWith(gate, new BadSqlGrammarException(
                "read", "SELECT broken", new SQLException("syntax error")));

        assertThat(gate.suspensionsRequested()).isZero();
    }

    /**
     * A deadlock is the store <em>answering</em> — two writers met on one row, and the loser's
     * delivery simply comes round again. Suspending the whole queue for one contended row would be
     * the statement-fault mistake wearing a transient exception type: {@code ConcurrencyFailure}
     * extends {@code TransientDataAccessException}, so it has to be told apart explicitly.
     */
    @Test
    @DisplayName("a lost deadlock race hands the delivery back without stopping intake")
    void should_not_suspend_intake_for_a_concurrency_failure_the_store_answered_with() {
        final StoreGateTestSupport.Recording gate = StoreGateTestSupport.open();

        onMessageWith(gate, new DeadlockLoserDataAccessException(
                "the claim insert lost a deadlock race", new SQLException("deadlock detected")));

        assertThat(gate.suspensionsRequested()).isZero();
    }

    /** The transient outage class the suspension triple names, pinned so a narrowing is noticed. */
    @Test
    @DisplayName("a query timeout is an outage: the delivery goes back and intake stops")
    void should_suspend_intake_for_a_query_timeout() {
        final StoreGateTestSupport.Recording gate = StoreGateTestSupport.open();

        onMessageWith(gate, new QueryTimeoutException("the store stopped answering mid-query"));

        assertThat(gate.suspensionsRequested()).isEqualTo(1);
    }

    private void onMessageWith(final StoreGateTestSupport.Recording gate,
                               final RuntimeException pipelineFault) {
        final ServiceBusReceivedMessage message = message();
        when(message.getBody()).thenReturn(BinaryData.fromString("{}"));
        final ServiceBusReceivedMessageContext context =
                mock(ServiceBusReceivedMessageContext.class);
        when(context.getMessage()).thenReturn(message);
        when(parser.parse(any(String.class))).thenReturn(ProcessedLogTestSupport.command());
        when(pipeline.process(any(DistributionCommand.class), any(DeliveryIdentity.class)))
                .thenThrow(pipelineFault);

        listenerOver(gate).onMessage(context);
    }
}
