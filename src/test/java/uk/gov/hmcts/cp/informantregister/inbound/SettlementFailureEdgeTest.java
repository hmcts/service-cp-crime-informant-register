package uk.gov.hmcts.cp.informantregister.inbound;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.azure.core.amqp.exception.AmqpErrorCondition;
import com.azure.core.amqp.exception.AmqpErrorContext;
import com.azure.core.amqp.exception.AmqpException;
import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusErrorSource;
import com.azure.messaging.servicebus.ServiceBusException;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.informantregister.application.DistributionPipeline;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.config.ServiceBusHealthIndicator;
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.SettlementOperation;
import uk.gov.hmcts.cp.informantregister.support.CapturedLog;
import uk.gov.hmcts.cp.informantregister.support.QueueHealthTestSupport;
import uk.gov.hmcts.cp.informantregister.support.StoreGateTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What happens when the settlement itself is the thing that fails — spec FR-016.
 *
 * <p>Everywhere else the settlement is the last thing that can go wrong; here it is the thing that
 * has gone wrong. Three properties have to hold whatever the broker does.
 *
 * <p><strong>One attempt stays one attempt.</strong> A settlement that throws must not be followed
 * by a second settlement of another kind. Handing a delivery back because acknowledging it failed
 * would either double-settle a lock this service still holds or, worse, succeed — turning work that
 * <em>is</em> durably recorded into a redelivery that runs again. The recorded outcome is what makes
 * the redelivery safe, so the right answer is to report the failure loudly and let the message come
 * round to a state machine that already knows the answer.
 *
 * <p><strong>A lost lock is not a settlement failure.</strong> The one attempt is always made — the
 * broker is the authority on its own lock, never a local clock reading — and a refusal that names
 * the lock is classified as loss: logged, counted under its own instrument, and recovery is left to
 * the broker's redelivery. The record decides what that redelivery does.
 *
 * <p><strong>Only the call itself is the settlement.</strong> A fault in what follows a settlement —
 * the counter, the log line — is not the settlement failing, and reporting it as one would say a
 * parked message was never parked. It is allowed to surface as itself.
 *
 * <p>What this suite does <em>not</em> assert is that the record stays COMPLETED or FAILED — that is
 * the guard's property, proven against a real store in {@code IdempotencyGuardIT} and
 * {@code FailedReplayIT}. What is added here is the listener's half: that a failed settlement is
 * reported rather than swallowed, that it is never compensated for with a second settlement, and
 * that the redelivery which follows is settled from the record without the listener repeating any
 * work of its own.
 */
class SettlementFailureEdgeTest {

    /** Every settlement the SDK offers, so the count cannot be fooled by an overload. */
    private static final Set<String> SETTLEMENT_METHODS =
            Set.of("complete", "abandon", "deadLetter", "defer");

    private static final String MESSAGE_ID = "RESULTS:abcd";
    private static final String LOCK_TOKEN = "5a4f2f1e-0000-0000-0000-00000000000a";

    /** The queue's delivery budget. Every delivery here is its message's first. */
    private static final int MAX_DELIVERY_COUNT = 5;

    private final DistributionPipeline pipeline = mock(DistributionPipeline.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProcessingMetrics metrics = new ProcessingMetrics(registry);
    private final DistributionCommandParser parser =
            new DistributionCommandParser(JacksonConfig.contractObjectMapper());

    private final InformantRegisterMessageListener listener =
            new InformantRegisterMessageListener(
                    parser, pipeline, metrics, QueueHealthTestSupport.unwatched(),
                    StoreGateTestSupport.open(), MAX_DELIVERY_COUNT);

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    private CapturedLog listenerLog;

    @BeforeEach
    void captureWhatTheListenerReports() {
        listenerLog = CapturedLog.of(InformantRegisterMessageListener.class);
    }

    @AfterEach
    void releaseTheListenerLog() {
        listenerLog.close();
    }

    // --- helpers ---------------------------------------------------------------------------

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

    /**
     * A delivery whose lock is still good for another minute — the ordinary case.
     */
    private ServiceBusReceivedMessageContext deliveryWithALiveLock() {
        return delivery(OffsetDateTime.now().plusMinutes(1));
    }

    /**
     * A delivery whose lock ran out while the work was being done.
     */
    private ServiceBusReceivedMessageContext deliveryWhoseLockHasGone() {
        return delivery(OffsetDateTime.now().minusSeconds(30));
    }

    private ServiceBusReceivedMessageContext delivery(final OffsetDateTime lockedUntil) {
        final ServiceBusReceivedMessage message = mock(ServiceBusReceivedMessage.class);
        when(message.getBody()).thenReturn(BinaryData.fromString(validBody()));
        when(message.getMessageId()).thenReturn(MESSAGE_ID);
        when(message.getLockToken()).thenReturn(LOCK_TOKEN);
        when(message.getDeliveryCount()).thenReturn(0L);
        when(message.getLockedUntil()).thenReturn(lockedUntil);

        final ServiceBusReceivedMessageContext context = mock(ServiceBusReceivedMessageContext.class);
        when(context.getMessage()).thenReturn(message);
        return context;
    }

    private void pipelineDecides(final GuardDecision decision) {
        when(pipeline.process(any(DistributionCommand.class), any(DeliveryIdentity.class)))
                .thenReturn(decision);
    }

    /**
     * The broker refusing a settlement, in the shape the SDK actually reports it.
     */
    private static ServiceBusException brokerRefusal(final ServiceBusErrorSource source) {
        return new ServiceBusException(
                new AmqpException(true, "the link detached", new AmqpErrorContext("localhost")),
                source);
    }

    private static List<String> settlementsOn(final ServiceBusReceivedMessageContext context) {
        return mockingDetails(context).getInvocations().stream()
                .map(invocation -> invocation.getMethod().getName())
                .filter(SETTLEMENT_METHODS::contains)
                .toList();
    }

    private List<String> errorsReported() {
        return listenerLog.events().stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private double counter(final String name, final String tag, final String value) {
        final Counter counter = registry.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }

    private double counter(final String name) {
        final Counter counter = registry.find(name).counter();
        return counter == null ? 0 : counter.count();
    }

    // --- the acknowledgement that failed ------------------------------------------------------

    @Nested
    @DisplayName("an acknowledgement the broker refuses, after the outcome is durably recorded")
    class AcknowledgementFails {

        @Test
        void should_report_and_count_it_without_settling_the_delivery_a_second_time() {
            final ServiceBusReceivedMessageContext context = deliveryWithALiveLock();
            pipelineDecides(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));
            doThrow(brokerRefusal(ServiceBusErrorSource.COMPLETE)).when(context).complete();

            listener.onMessage(context);

            assertThat(counter(ProcessingMetrics.SETTLEMENT_FAILURES,
                    ProcessingMetrics.OPERATION_TAG, SettlementOperation.COMPLETE.label()))
                    .isEqualTo(1);
            assertThat(errorsReported())
                    .as("reported once, and reported at all — nothing is swallowed here")
                    .hasSize(1);
            assertThat(settlementsOn(context))
                    .as("a failed acknowledgement is not compensated for by handing the delivery back")
                    .containsExactly("complete");
        }

        @Test
        void should_acknowledge_the_redelivery_from_the_record_without_repeating_the_work() {
            final ServiceBusReceivedMessageContext refused = deliveryWithALiveLock();
            pipelineDecides(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));
            doThrow(brokerRefusal(ServiceBusErrorSource.COMPLETE)).when(refused).complete();
            listener.onMessage(refused);

            // The broker redelivers what it could not see acknowledged; the record already says the
            // work is done, so the guard acknowledges it without a run.
            final ServiceBusReceivedMessageContext redelivered = deliveryWithALiveLock();
            pipelineDecides(new GuardDecision.Complete(ReasonCode.ALREADY_COMPLETED));

            listener.onMessage(redelivered);

            verify(redelivered).complete();
            assertThat(settlementsOn(redelivered)).containsExactly("complete");
        }
    }

    // --- the parking that failed ----------------------------------------------------------------

    @Nested
    @DisplayName("a dead-letter the broker refuses, after the request is recorded FAILED")
    class DeadLetteringFails {

        @Test
        void should_report_and_count_it_without_settling_the_delivery_a_second_time() {
            final ServiceBusReceivedMessageContext context = deliveryWithALiveLock();
            pipelineDecides(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.DELIVERY_LIMIT_EXHAUSTED));
            doThrow(brokerRefusal(ServiceBusErrorSource.ABANDON))
                    .when(context).deadLetter(any(DeadLetterOptions.class));

            listener.onMessage(context);

            assertThat(counter(ProcessingMetrics.SETTLEMENT_FAILURES,
                    ProcessingMetrics.OPERATION_TAG, SettlementOperation.DEADLETTER.label()))
                    .isEqualTo(1);
            assertThat(errorsReported()).hasSize(1);
            assertThat(settlementsOn(context)).containsExactly("deadLetter");
        }

        @Test
        void should_not_count_a_dead_letter_the_broker_never_accepted() {
            final ServiceBusReceivedMessageContext context = deliveryWithALiveLock();
            pipelineDecides(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.DELIVERY_LIMIT_EXHAUSTED));
            doThrow(brokerRefusal(ServiceBusErrorSource.ABANDON))
                    .when(context).deadLetter(any(DeadLetterOptions.class));

            listener.onMessage(context);

            assertThat(counter(ProcessingMetrics.DEAD_LETTERED,
                    ProcessingMetrics.REASON_TAG, DeadLetterReason.EXHAUSTED.label()))
                    .as("the counter records dead-letters that happened, not ones that were intended")
                    .isZero();
        }

        @Test
        void should_attempt_the_dead_letter_again_when_the_same_identity_comes_round() {
            final ServiceBusReceivedMessageContext refused = deliveryWithALiveLock();
            pipelineDecides(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.DELIVERY_LIMIT_EXHAUSTED));
            doThrow(brokerRefusal(ServiceBusErrorSource.ABANDON))
                    .when(refused).deadLetter(any(DeadLetterOptions.class));
            listener.onMessage(refused);

            // The lock expired rather than the parking settling, so the broker delivers the same
            // identity again. The record stays FAILED and the guard asks for the parking again.
            final ServiceBusReceivedMessageContext redelivered = deliveryWithALiveLock();

            listener.onMessage(redelivered);

            verify(redelivered).deadLetter(any(DeadLetterOptions.class));
            assertThat(settlementsOn(redelivered)).containsExactly("deadLetter");
        }
    }

    // --- the handing back that failed ------------------------------------------------------------

    @Nested
    @DisplayName("a hand-back the broker refuses")
    class AbandonFails {

        @Test
        void should_report_and_count_it_without_settling_the_delivery_a_second_time() {
            final ServiceBusReceivedMessageContext context = deliveryWithALiveLock();
            pipelineDecides(new GuardDecision.Abandon(ReasonCode.CLAIM_NOT_ACQUIRED));
            doThrow(brokerRefusal(ServiceBusErrorSource.ABANDON)).when(context).abandon();

            listener.onMessage(context);

            assertThat(counter(ProcessingMetrics.SETTLEMENT_FAILURES,
                    ProcessingMetrics.OPERATION_TAG, SettlementOperation.ABANDON.label()))
                    .isEqualTo(1);
            assertThat(errorsReported()).hasSize(1);
            assertThat(settlementsOn(context)).containsExactly("abandon");
        }
    }

    // --- the failure that happened after the broker agreed ----------------------------------------

    /**
     * The settlement boundary must not be wider than the settlement.
     *
     * <p>Recording the dead-letter and writing the log line happen after the broker has accepted the
     * call. If they sit inside the same guard as the call itself, a failure in either is reported as
     * "the broker refused the settlement" and counted against a settlement that in fact succeeded —
     * three wrongs at once: a message that <em>is</em> parked looks unparked, the settlement-failure
     * series gains a reading that never happened, and the real fault (an unreachable meter registry,
     * say) is swallowed behind somebody else's name.
     */
    @Nested
    @DisplayName("telemetry that fails after the broker has accepted the settlement")
    class TelemetryFailsAfterTheSettlement {

        private final ProcessingMetrics unreachable = mock(ProcessingMetrics.class);

        private final InformantRegisterMessageListener listenerWithFailingTelemetry =
                new InformantRegisterMessageListener(
                        parser, pipeline, unreachable, QueueHealthTestSupport.unwatched(),
                        StoreGateTestSupport.open(), MAX_DELIVERY_COUNT);

        private ServiceBusReceivedMessageContext aParkingWhoseCounterIsGone() {
            final ServiceBusReceivedMessageContext context = deliveryWithALiveLock();
            pipelineDecides(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.DELIVERY_LIMIT_EXHAUSTED));
            doThrow(new IllegalStateException("the meter registry is gone"))
                    .when(unreachable).deadLettered(DeadLetterReason.EXHAUSTED);
            return context;
        }

        @Test
        void should_let_the_real_failure_surface_rather_than_disguise_it() {
            final ServiceBusReceivedMessageContext context = aParkingWhoseCounterIsGone();

            assertThatThrownBy(() -> listenerWithFailingTelemetry.onMessage(context))
                    .as("the delivery is parked, so there is nothing left to lose by saying what "
                            + "actually broke")
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void should_not_count_a_settlement_the_broker_accepted_as_a_settlement_failure() {
            final ServiceBusReceivedMessageContext context = aParkingWhoseCounterIsGone();

            try {
                listenerWithFailingTelemetry.onMessage(context);
            } catch (IllegalStateException expected) {
                // The subject of the next assertion, not of this one.
            }

            verify(context).deadLetter(any(DeadLetterOptions.class));
            verify(unreachable, never()).settlementFailed(any(SettlementOperation.class));
        }

        @Test
        void should_not_report_the_broker_as_having_refused_a_call_it_accepted() {
            final ServiceBusReceivedMessageContext context = aParkingWhoseCounterIsGone();

            try {
                listenerWithFailingTelemetry.onMessage(context);
            } catch (IllegalStateException expected) {
                // As above.
            }

            assertThat(errorsReported())
                    .as("no refusal was reported, because none happened")
                    .isEmpty();
            assertThat(settlementsOn(context))
                    .as("and the delivery really was parked, exactly once")
                    .containsExactly("deadLetter");
        }

        /**
         * The same rule, applied to the queue-health recorder rather than to a counter.
         *
         * <p>Recording that the broker answered is telemetry too, and it is telemetry that sits
         * closer to the settlement call than anything else does — which is exactly why it must sit
         * <em>outside</em> the guard. Inside it, its own failure is caught by the handler for "the
         * broker refused", and the broker is then reported as having refused a settlement it had
         * just accepted: a settlement-failure counter moves for a settlement that happened, and the
         * queue-health component records a transport fault against a connection that had plainly
         * worked. A broker in perfect health goes DOWN on a dashboard because a clock threw.
         */
        @Test
        void should_not_report_a_transport_fault_when_the_recovery_recorder_is_what_failed() {
            final ServiceBusHealthIndicator failingRecorder = mock(ServiceBusHealthIndicator.class);
            doThrow(new IllegalStateException("the clock is gone"))
                    .when(failingRecorder).recordSettlementAccepted();
            final ProcessingMetrics counters = mock(ProcessingMetrics.class);
            final InformantRegisterMessageListener listenerWithAFailingRecorder =
                    new InformantRegisterMessageListener(
                            parser, pipeline, counters, failingRecorder,
                            StoreGateTestSupport.open(), MAX_DELIVERY_COUNT);

            final ServiceBusReceivedMessageContext context = deliveryWithALiveLock();
            pipelineDecides(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));

            assertThatThrownBy(() -> listenerWithAFailingRecorder.onMessage(context))
                    .as("the delivery is acknowledged, so there is nothing left to lose by saying "
                            + "what actually broke")
                    .isInstanceOf(IllegalStateException.class);

            verify(context).complete();
            verify(counters, never())
                    .settlementFailed(any(SettlementOperation.class));
            verify(failingRecorder, never()).recordSettlementRefusal(any(Throwable.class));
            assertThat(errorsReported())
                    .as("no refusal was reported, because none happened")
                    .isEmpty();
            assertThat(settlementsOn(context))
                    .as("and the delivery really was acknowledged, exactly once")
                    .containsExactly("complete");
        }
    }

    // --- the lock the broker says is gone ---------------------------------------------------------

    /**
     * Lock loss is the broker's fact, learned from the refused settlement — never from comparing the
     * broker's {@code lockedUntil} with a local clock, which is the multi-node skew the data model's
     * single-time-authority rule exists to rule out. A skewed pod that trusted its own reading would
     * skip settlements the broker was still willing to accept, completed work included.
     */
    @Nested
    @DisplayName("a settlement the broker refuses because the lock has gone")
    class LockLost {

        private ServiceBusException lockLostRefusal() {
            return new ServiceBusException(
                    new AmqpException(false, AmqpErrorCondition.MESSAGE_LOCK_LOST,
                            "the lock supplied is no longer valid", new AmqpErrorContext("localhost")),
                    ServiceBusErrorSource.COMPLETE);
        }

        @Test
        void should_attempt_the_settlement_even_when_the_local_reading_says_the_lock_expired() {
            final ServiceBusReceivedMessageContext context = deliveryWhoseLockHasGone();
            pipelineDecides(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));

            listener.onMessage(context);

            assertThat(settlementsOn(context))
                    .as("the broker is the authority on its own lock; the local reading is not")
                    .containsExactly("complete");
        }

        @Test
        void should_report_a_lock_lost_refusal_once_and_count_it_under_its_own_instrument() {
            final ServiceBusReceivedMessageContext context = deliveryWithALiveLock();
            pipelineDecides(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));
            doThrow(lockLostRefusal()).when(context).complete();

            listener.onMessage(context);

            assertThat(counter(ProcessingMetrics.LOCK_LOSS)).isEqualTo(1);
            assertThat(errorsReported())
                    .as("a lost lock is loud: it is the one failure with no settlement to show for it")
                    .hasSize(1);
        }

        @Test
        void should_not_count_a_lost_lock_as_a_settlement_failure() {
            final ServiceBusReceivedMessageContext context = deliveryWithALiveLock();
            pipelineDecides(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));
            doThrow(lockLostRefusal()).when(context).complete();

            listener.onMessage(context);

            assertThat(counter(ProcessingMetrics.SETTLEMENT_FAILURES,
                    ProcessingMetrics.OPERATION_TAG, SettlementOperation.COMPLETE.label()))
                    .as("the lock was lost; the settlement machinery did not fail")
                    .isZero();
            assertThat(settlementsOn(context))
                    .as("and no second settlement compensates for it")
                    .containsExactly("complete");
        }

        /** The classification lives in the shared guard, so every operation gets it — pinned. */
        @Test
        void should_classify_a_lock_lost_dead_letter_refusal_the_same_way() {
            final ServiceBusReceivedMessageContext context = deliveryWithALiveLock();
            pipelineDecides(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.DELIVERY_LIMIT_EXHAUSTED));
            doThrow(lockLostRefusal()).when(context).deadLetter(any(DeadLetterOptions.class));

            listener.onMessage(context);

            assertThat(counter(ProcessingMetrics.LOCK_LOSS)).isEqualTo(1);
            assertThat(counter(ProcessingMetrics.SETTLEMENT_FAILURES,
                    ProcessingMetrics.OPERATION_TAG, SettlementOperation.DEADLETTER.label()))
                    .isZero();
            assertThat(settlementsOn(context)).containsExactly("deadLetter");
        }

        @Test
        void should_not_record_a_transport_fault_for_a_refusal_about_one_message() {
            final ServiceBusHealthIndicator health = mock(ServiceBusHealthIndicator.class);
            final InformantRegisterMessageListener watched = new InformantRegisterMessageListener(
                    parser, pipeline, metrics, health,
                    StoreGateTestSupport.open(), MAX_DELIVERY_COUNT);
            final ServiceBusReceivedMessageContext context = deliveryWithALiveLock();
            pipelineDecides(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));
            doThrow(lockLostRefusal()).when(context).complete();

            watched.onMessage(context);

            verify(health, never()).recordSettlementRefusal(any(Throwable.class));
        }
    }
}
