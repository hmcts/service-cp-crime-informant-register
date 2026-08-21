package uk.gov.hmcts.cp.informantregister.e2e;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.models.SubQueue;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.RequestStatus;
import uk.gov.hmcts.cp.informantregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport.Row;
import uk.gov.hmcts.cp.informantregister.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ServiceTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The outage that outlasts the delivery budget (spec, "store unavailable across many retry
 * intervals").
 *
 * <p>This is the case suspension exists for. A service that kept consuming through a store outage
 * would abandon each delivery and get it straight back; five of those round trips cost seconds, and
 * at the end of them the broker parks a message whose only fault was arriving at a bad moment. The
 * work is then on the dead-letter queue, where somebody has to notice it and resubmit it — for an
 * outage that fixed itself.
 *
 * <p>"More than five nominal retry intervals" is measured the way the damage would happen: the
 * outage is held open far longer than five abandon-and-redeliver cycles take against this broker,
 * and the assertion is that the message's delivery count did not move. A suspended consumer takes
 * no deliveries at all, so the budget is not merely unspent — it is untouched, and the message is
 * still on the queue rather than beside it.
 *
 * <p>The suite starts its own service and closes it, so the delivery count it reads is a fact about
 * this consumer and not about whichever other suite's context happened to be cached.
 */
class ProlongedStoreOutageIT {

    private static final Duration OBSERVED_WITHIN = Duration.ofSeconds(120);
    private static final Duration POLL = Duration.ofSeconds(1);

    /**
     * The outage window. Against this broker an abandoned delivery comes back immediately, so five
     * nominal retry intervals — the whole delivery budget — is a matter of seconds; thirty is
     * comfortably more than five of them and still bounded enough to sit in a build.
     */
    private static final Duration OUTAGE = Duration.ofSeconds(30);

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    @BeforeAll
    static void migrateTheSharedStore() {
        ProcessedLogTestSupport.dataSource();
    }

    @AfterEach
    void thawTheStore() {
        PostgresTestSupport.unpause();
    }

    private static double gauge(final MeterRegistry registry, final String name) {
        final Gauge found = registry.find(name).gauge();
        return found == null ? Double.NaN : found.value();
    }

    private static Optional<ServiceBusReceivedMessage> onQueue(final String messageId) {
        return ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.NONE);
    }

    private Optional<Row> row() {
        return ProcessedLogTestSupport.row(ProcessedLogTestSupport.SOURCE, requestId);
    }

    @Test
    @DisplayName("an outage longer than the delivery budget still parks nothing, and processes on resume")
    void should_leave_the_message_recoverable_however_long_the_store_is_down() {
        try (ConfigurableApplicationContext context = ServiceTestSupport.start(Map.of())) {
            final MeterRegistry registry = context.getBean(MeterRegistry.class);

            PostgresTestSupport.pause();
            final String messageId =
                    ServiceTestSupport.publish(ServiceTestSupport.validBody(requestId, hearingId));

            // The claim of this suite, asserted first because it is the one that matters: through
            // the whole window the message stays exactly where it was, and its delivery budget is
            // not being spent. Held as a condition rather than checked once at the end, so a
            // message that visited the dead-letter queue and was replaced could not slip through.
            await().during(OUTAGE).atMost(OBSERVED_WITHIN).pollInterval(POLL)
                    .until(() -> onQueue(messageId)
                            .filter(message -> message.getDeliveryCount() == 0)
                            .isPresent());
            assertThat(ServiceBusEmulatorTestSupport
                    .peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE))
                    .as("recoverable work must never be parked for an outage of ours")
                    .isEmpty();
            assertThat(gauge(registry, ProcessingMetrics.INTAKE_SUSPENDED))
                    .as("the budget survives because intake stopped, not because nothing arrived")
                    .isEqualTo(1);

            PostgresTestSupport.unpause();

            await().atMost(OBSERVED_WITHIN).pollInterval(POLL).until(() ->
                    row().filter(found -> RequestStatus.COMPLETED.name().equals(found.status()))
                            .isPresent());

            final Row processed =
                    ProcessedLogTestSupport.requireRow(ProcessedLogTestSupport.SOURCE, requestId);
            assertThat(processed.attempts())
                    .as("one run, on the first delivery the service was fit to take")
                    .isEqualTo(1);
            assertThat(gauge(registry, ProcessingMetrics.INTAKE_SUSPENDED)).isEqualTo(0);
            assertThat(ServiceBusEmulatorTestSupport
                    .peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE)).isEmpty();
        }
    }
}
