package uk.gov.hmcts.cp.informantregister.e2e;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.azure.messaging.servicebus.models.SubQueue;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ConfigurableApplicationContext;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.RequestStatus;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport.Row;
import uk.gov.hmcts.cp.informantregister.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ServiceTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The broker goes away and comes back, and the pod neither restarts nor stays broken (spec SC-004,
 * US4-2).
 *
 * <p>Two halves, and the second is the one that is easy to get wrong. Reporting the outage is
 * straightforward; <strong>recovering from it without help</strong> is not. A client left on a long
 * default back-off will eventually reconnect, but "eventually" is not a service level: the budget
 * is sixty seconds from the queue returning, which is why the retry options are configured
 * explicitly rather than inherited.
 *
 * <p>The gauge is asserted alongside the health component because they answer the same question to
 * two different audiences — a probe and a dashboard — and a pair that can disagree is worse than
 * either alone.
 *
 * <p>The suite owns its service: it starts one, breaks the broker underneath it, and closes it. A
 * cached context belonging to another suite would be competing for the same queue while this one
 * counted what its message did.
 */
class QueueOutageRecoveryIT {

    private static final String BROKER_COMPONENT = "servicebus";

    private static final Duration OBSERVED_WITHIN = Duration.ofSeconds(90);
    private static final Duration POLL = Duration.ofSeconds(1);

    /** Spec SC-004's budget, measured from the moment the queue comes back. */
    private static final Duration RESUMES_WITHIN = Duration.ofSeconds(60);

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    @BeforeAll
    static void migrateTheSharedStore() {
        ProcessedLogTestSupport.dataSource();
    }

    @AfterEach
    void thawTheBroker() {
        ServiceBusEmulatorTestSupport.unpause();
    }

    // --- helpers ---------------------------------------------------------------------------

    private static Status brokerStatus(final ConfigurableApplicationContext context) {
        final CompositeHealthDescriptor overall =
                (CompositeHealthDescriptor) context.getBean(HealthEndpoint.class).health();
        final HealthDescriptor broker = overall.getComponents().get(BROKER_COMPONENT);
        return broker == null ? Status.UNKNOWN : broker.getStatus();
    }

    private static Status readinessStatus(final ConfigurableApplicationContext context) {
        return context.getBean(HealthEndpoint.class).healthForPath("readiness").getStatus();
    }

    private static double brokerGauge(final ConfigurableApplicationContext context) {
        final Gauge found = context.getBean(MeterRegistry.class)
                .find(ProcessingMetrics.SERVICEBUS_UP).gauge();
        return found == null ? Double.NaN : found.value();
    }

    private Optional<Row> row() {
        return ProcessedLogTestSupport.row(ProcessedLogTestSupport.SOURCE, requestId);
    }

    // --- the outage and the recovery ---------------------------------------------------------

    @Test
    @DisplayName("the broker goes down and comes back; readiness never moves and consumption resumes")
    void should_report_the_outage_stay_ready_and_resume_consuming_when_the_queue_returns() {
        try (ConfigurableApplicationContext context = ServiceTestSupport.start(Map.of())) {
            assertThat(brokerStatus(context)).isEqualTo(Status.UP);

            ServiceBusEmulatorTestSupport.pause();

            await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                    .until(() -> Status.DOWN.equals(brokerStatus(context)));
            assertThat(readinessStatus(context))
                    .as("a pod cannot heal a broker by restarting, so the broker never gates readiness")
                    .isEqualTo(Status.UP);
            assertThat(brokerGauge(context))
                    .as("the gauge and the health component answer the same question")
                    .isEqualTo(0);

            ServiceBusEmulatorTestSupport.unpause();

            // Everything below is inside SC-004's sixty seconds, counted from here.
            final String messageId =
                    ServiceTestSupport.publish(ServiceTestSupport.validBody(requestId, hearingId));
            await().atMost(RESUMES_WITHIN).pollInterval(POLL).until(() ->
                    row().filter(found -> RequestStatus.COMPLETED.name().equals(found.status()))
                            .isPresent());

            assertThat(brokerStatus(context))
                    .as("a receive that succeeded is the answer to the error that preceded it")
                    .isEqualTo(Status.UP);
            assertThat(brokerGauge(context)).isEqualTo(1);
            assertThat(ServiceBusEmulatorTestSupport
                    .peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE))
                    .as("an outage of the broker's is nobody's poison message")
                    .isEmpty();
        }
    }
}
