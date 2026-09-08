package uk.gov.hmcts.cp.informantregister.inbound;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.informantregister.application.DistributionPipeline;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.support.CapturedLog;
import uk.gov.hmcts.cp.informantregister.support.QueueHealthTestSupport;
import uk.gov.hmcts.cp.informantregister.support.StoreGateTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What second and third line get told when a delivery arrives.
 *
 * <p>The questions a support engineer actually arrives with are not answered by the request
 * identifiers alone. "Is the queue backed up or is this fresh?" is the gap between the broker's
 * {@code enqueuedTime} and now. "Did the run overrun its lock?" needs the lock's own deadline. "How
 * stale is the share this hearing is being resulted from?" is {@code sharedTime}. "Which identity
 * did the outbound calls go out as?" decides whether an attribution complaint is a producer defect
 * or the documented fallback working. And "where is this message in the broker's own view?" needs
 * the one handle a management API indexes on that is not producer text — the sequence number.
 *
 * <p>Every field asserted here is stamped by the <strong>broker</strong> or normalised by the
 * parser. That is not incidental: {@code messageId} is text the producer chose and {@code userId}
 * names a person, and both are already forbidden by {@code TelemetryPrivacyTest}. This suite pins
 * the diagnostics that were available without reopening either door — and, because the end-to-end
 * suites match this line by its opening words only, it is the sole guard on its content.
 */
class DeliveryReceiptLogTest {

    private static final int MAX_DELIVERY_COUNT = 5;
    private static final String SEQUENCE_NUMBER = "sequenceNumber";
    private static final String DELIVERY_COUNT = "deliveryCount";

    private static final long SEQUENCE = 4815162342L;
    private static final OffsetDateTime ENQUEUED =
            OffsetDateTime.of(2026, 8, 21, 8, 0, 30, 0, ZoneOffset.UTC);
    private static final OffsetDateTime LOCKED_UNTIL =
            OffsetDateTime.of(2026, 8, 21, 8, 5, 30, 0, ZoneOffset.UTC);

    /** A real uuid, because a marker word would be rejected before the run could log anything. */
    private static final String SHARING_USER = "0dd0dd0d-dead-beef-cafe-facade000001";

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    private final DistributionPipeline pipeline = mock(DistributionPipeline.class);

    // --- fixtures --------------------------------------------------------------------------

    private InformantRegisterMessageListener listenerOver(final StoreGate gate) {
        when(pipeline.process(any(DistributionCommand.class), any(DeliveryIdentity.class)))
                .thenReturn(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));
        return new InformantRegisterMessageListener(
                new DistributionCommandParser(JacksonConfig.contractObjectMapper()),
                pipeline, new ProcessingMetrics(new SimpleMeterRegistry()),
                QueueHealthTestSupport.unwatched(), gate, MAX_DELIVERY_COUNT);
    }

    private InformantRegisterMessageListener listener() {
        return listenerOver(StoreGateTestSupport.open());
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

    private String bodyNamingTheSharingUser() {
        return validBody().replace("\"eventType\": \"Hearing_Resulted\"",
                "\"eventType\": \"Hearing_Resulted\",\n  \"userId\": \"" + SHARING_USER + "\"");
    }

    /** A delivery stamped the way the broker stamps one. */
    private static ServiceBusReceivedMessage stampedMessage(final String body) {
        final ServiceBusReceivedMessage message = mock(ServiceBusReceivedMessage.class);
        when(message.getBody()).thenReturn(BinaryData.fromString(body));
        when(message.getMessageId()).thenReturn("RESULTS:" + UUID.randomUUID());
        when(message.getLockToken()).thenReturn(UUID.randomUUID().toString());
        when(message.getDeliveryCount()).thenReturn(0L);
        when(message.getSequenceNumber()).thenReturn(SEQUENCE);
        when(message.getEnqueuedTime()).thenReturn(ENQUEUED);
        when(message.getLockedUntil()).thenReturn(LOCKED_UNTIL);
        return message;
    }

    private static ServiceBusReceivedMessageContext deliveryOf(
            final ServiceBusReceivedMessage message) {
        final ServiceBusReceivedMessageContext context =
                mock(ServiceBusReceivedMessageContext.class);
        when(context.getMessage()).thenReturn(message);
        return context;
    }

    private static ServiceBusReceivedMessageContext deliveryOf(final String body) {
        return deliveryOf(stampedMessage(body));
    }

    /** Only what the listener itself wrote: a third-party line must not decide these tests. */
    private static List<ILoggingEvent> listenerLines(final CapturedLog log) {
        return log.events().stream()
                .filter(event -> InformantRegisterMessageListener.class.getName()
                        .equals(event.getLoggerName()))
                .toList();
    }

    private static String receiptLine(final CapturedLog log) {
        final List<String> found = listenerLines(log).stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("Delivery received."))
                .toList();
        assertThat(found).as("exactly one receipt line per delivery").hasSize(1);
        return found.getFirst();
    }

    // --- what the line says --------------------------------------------------------------------

    @Nested
    @DisplayName("the receipt line")
    class ReceiptLine {

        /**
         * The line an investigation starts from has to be legible on its own.
         *
         * <p>They are in the MDC too, and the shipped encoder emits it — but a line pasted into a
         * ticket, read in a terminal, or shown by a reader that displays the message and not the
         * structured fields would otherwise say nothing about which request it was.
         */
        @Test
        @DisplayName("names the request and hearing itself, not only through the MDC")
        void should_name_the_request_and_hearing_in_the_line_itself() {
            try (CapturedLog log = CapturedLog.everything()) {
                listener().onMessage(deliveryOf(validBody()));

                assertThat(receiptLine(log))
                        .contains("requestId=" + requestId)
                        .contains("hearingId=" + hearingId)
                        .contains("hearingDay=2026-08-21");
            }
        }

        @Test
        @DisplayName("carries the broker facts a queue question is answered with")
        void should_report_the_brokers_own_timings_and_position_on_receipt() {
            try (CapturedLog log = CapturedLog.everything()) {
                listener().onMessage(deliveryOf(validBody()));

                assertThat(receiptLine(log))
                        .as("when the broker took it, which answers 'is the queue backed up?'")
                        .contains("enqueuedTime=" + ENQUEUED)
                        .as("when this delivery's lock runs out — 'did the run overrun?'")
                        .contains("lockedUntil=" + LOCKED_UNTIL)
                        .as("how stale the share being resulted is")
                        .contains("sharedTime=2026-08-21T08:00:00Z");
            }
        }

        @Test
        @DisplayName("says a delivery is not the last one the queue will give it")
        void should_report_a_delivery_that_has_budget_left_as_not_the_final_one() {
            try (CapturedLog log = CapturedLog.everything()) {
                final ServiceBusReceivedMessage message = stampedMessage(validBody());
                when(message.getDeliveryCount()).thenReturn(3L);

                listener().onMessage(deliveryOf(message));

                assertThat(receiptLine(log)).contains("finalPermittedDelivery=false");
            }
        }

        /**
         * The boundary the listener's own javadoc calls quiet and damaging in both directions.
         *
         * <p>The count is zero-based, so with a budget of five the last delivery a message is
         * entitled to carries four. Read a delivery early and a retry the queue was willing to give
         * is thrown away; read it late and this service parks nothing, the broker parks the message
         * under its own reason, and no FAILED record is left behind. Nothing else in the repository
         * pins the {@code true} side of it on this line.
         */
        @Test
        @DisplayName("says a delivery is the last one, on the delivery the broker means")
        void should_report_the_last_permitted_delivery_as_the_final_one() {
            try (CapturedLog log = CapturedLog.everything()) {
                final ServiceBusReceivedMessage message = stampedMessage(validBody());
                when(message.getDeliveryCount()).thenReturn((long) MAX_DELIVERY_COUNT - 1);

                listener().onMessage(deliveryOf(message));

                assertThat(receiptLine(log)).contains("finalPermittedDelivery=true");
            }
        }

        /**
         * Both stamps come off AMQP annotations a message need not carry.
         *
         * <p>Rendered as the literal {@code null} they read as a defect in this service, on the one
         * delivery somebody came to look at. The codebase's convention for "there was nothing here"
         * is a token that says so.
         */
        @Test
        @DisplayName("says so out loud when the broker stamped no timings at all")
        void should_name_the_absence_of_a_broker_stamp_rather_than_render_null() {
            try (CapturedLog log = CapturedLog.everything()) {
                final ServiceBusReceivedMessage message = stampedMessage(validBody());
                when(message.getEnqueuedTime()).thenReturn(null);
                when(message.getLockedUntil()).thenReturn(null);

                listener().onMessage(deliveryOf(message));

                assertThat(receiptLine(log))
                        .contains("enqueuedTime=none")
                        .contains("lockedUntil=none")
                        .doesNotContain("null");
            }
        }
    }

    // --- the broker's handle, on every line ----------------------------------------------------

    @Nested
    @DisplayName("the broker's handle on the message")
    class BrokerHandle {

        @Test
        @DisplayName("qualifies every line the listener writes about the delivery")
        void should_correlate_on_the_sequence_number_so_the_broker_and_the_log_can_be_joined() {
            try (CapturedLog log = CapturedLog.everything()) {
                listener().onMessage(deliveryOf(validBody()));

                final List<ILoggingEvent> lines = listenerLines(log).stream()
                        .filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                        .toList();
                assertThat(lines).as("a delivery that logged nothing proves nothing").isNotEmpty();
                for (final ILoggingEvent line : lines) {
                    assertThat(line.getMDCPropertyMap())
                            .as("line the broker's view cannot be joined to: %s",
                                    line.getFormattedMessage())
                            .containsEntry(SEQUENCE_NUMBER, Long.toString(SEQUENCE))
                            .containsEntry(DELIVERY_COUNT, "0");
                }
            }
        }

        @Test
        @DisplayName("is there even where the body was never read")
        void should_correlate_on_the_sequence_number_even_where_the_body_was_never_read() {
            try (CapturedLog log = CapturedLog.everything()) {
                listenerOver(StoreGateTestSupport.closed()).onMessage(deliveryOf(validBody()));

                assertThat(listenerLines(log))
                        .as("the outage line has no request id to carry, so this is the only "
                                + "handle on it — and 'is the same message coming back' is exactly "
                                + "the question an outage raises")
                        .isNotEmpty()
                        .allSatisfy(line -> assertThat(line.getMDCPropertyMap())
                                .containsEntry(SEQUENCE_NUMBER, Long.toString(SEQUENCE)));
            }
        }
    }

    // --- describing a delivery must not cost the delivery --------------------------------------

    /**
     * The broker's accessors are not safe getters, and describing a message must not lose it.
     *
     * <p>{@code getSequenceNumber()} casts an annotation whose type it does not check and
     * {@code getDeliveryCount()} unboxes a {@code Long} a header need not carry, so a message
     * stamped unusually — a hand-built republish, a dead-letter resubmission — throws while being
     * <em>described</em> rather than while being processed. Read outside the catch-and-settle
     * boundary that would take the delivery with it: no decision, so no settlement, so a lock left
     * to expire and five redeliveries into the same failure, ending on the dead-letter queue under
     * the broker's reason with nothing recorded. The twin of
     * {@code ExceptionalRouteSignalTest.should_account_for_a_delivery_whose_body_cannot_be_read},
     * which exists because the body read was once outside it too.
     */
    @Nested
    @DisplayName("a delivery the broker's own accessors cannot describe")
    class UndescribableDelivery {

        @Test
        @DisplayName("is still settled, exactly once")
        void should_settle_a_delivery_whose_broker_stamps_cannot_be_read() {
            final ServiceBusReceivedMessage message = stampedMessage(validBody());
            when(message.getSequenceNumber())
                    .thenThrow(new ClassCastException("annotation is not a Long"));
            final ServiceBusReceivedMessageContext context = deliveryOf(message);

            listener().onMessage(context);

            verify(context).abandon();
            verify(context, never()).complete();
            verify(context, never()).deadLetter(any());
        }

        @Test
        @DisplayName("is reported, so the delivery is not merely survived in silence")
        void should_report_a_delivery_whose_broker_stamps_cannot_be_read() {
            try (CapturedLog log = CapturedLog.everything()) {
                final ServiceBusReceivedMessage message = stampedMessage(validBody());
                when(message.getSequenceNumber())
                        .thenThrow(new ClassCastException("annotation is not a Long"));

                listener().onMessage(deliveryOf(message));

                assertThat(listenerLines(log))
                        .anySatisfy(line -> {
                            assertThat(line.getLevel()).isEqualTo(Level.ERROR);
                            assertThat(line.getFormattedMessage())
                                    .contains(ReasonCode.UNEXPECTED_FAILURE.code());
                        });
            }
        }
    }

    // --- which identity the run went out as ----------------------------------------------------

    @Nested
    @DisplayName("the identity a run is attributed to")
    class Attribution {

        @Test
        @DisplayName("is named as the message's user, without naming the user")
        void should_report_that_a_run_was_attributed_to_the_message_user() {
            try (CapturedLog log = CapturedLog.everything()) {
                listener().onMessage(deliveryOf(bodyNamingTheSharingUser()));

                assertThat(receiptLine(log)).contains("attributedTo=message-user");
                assertThat(log.renderings())
                        .as("which identity, never whose — the user id stays out of the index")
                        .noneMatch(line -> line.contains(SHARING_USER));
            }
        }

        @Test
        @DisplayName("is named as the system identity where the message named no user")
        void should_report_that_a_run_fell_back_to_the_system_identity() {
            try (CapturedLog log = CapturedLog.everything()) {
                listener().onMessage(deliveryOf(validBody()));

                assertThat(receiptLine(log))
                        .as("a producer that sent no user and this service's fallback are the two "
                                + "halves of every attribution question, and only this separates them")
                        .contains("attributedTo=system-identity");
            }
        }
    }
}
