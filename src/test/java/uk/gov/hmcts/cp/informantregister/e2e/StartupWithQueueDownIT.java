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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * A pod that starts while the queue is unavailable (spec US4-3).
 *
 * <p>The ordinary case at a bad moment: a deployment, a node drain, an autoscaler — a pod can start
 * at any instant, including one where the broker is not answering. Three things must hold, and each
 * one is a different mistake if it does not.
 *
 * <ul>
 *   <li><strong>It starts.</strong> A context that refuses to refresh without a broker turns a
 *       transient outage into a crash loop, and a crash loop reports nothing.</li>
 *   <li><strong>It becomes ready.</strong> Readiness is gated by the store, and the store is fine.
 *       A pod that stayed unready would be removed from service for a dependency it does not need
 *       to be safe.</li>
 *   <li><strong>It reports the broker down, and consumes as soon as the broker appears — with no
 *       restart.</strong> A pod that only ever connects at startup would sit there healthy and
 *       idle, which is the silent failure this service was commissioned to remove.</li>
 * </ul>
 *
 * <p>The broker is frozen before the context is built, which is why this suite starts its own
 * service rather than sharing one: the state of the world before refresh is the scenario.
 */
class StartupWithQueueDownIT {

    private static final String BROKER_COMPONENT = "servicebus";

    private static final Duration OBSERVED_WITHIN = Duration.ofSeconds(90);
    private static final Duration POLL = Duration.ofSeconds(1);

    /** Spec SC-004's budget, measured from the moment the queue appears. */
    private static final Duration CONSUMES_WITHIN = Duration.ofSeconds(60);

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    @BeforeAll
    static void migrateTheSharedStore() {
        ProcessedLogTestSupport.dataSource();
    }

    @AfterEach
    void thawTheBroker() {
        ServiceBusEmulatorTestSupport.restore();
    }

    private static Status brokerStatus(final ConfigurableApplicationContext context) {
        final CompositeHealthDescriptor overall =
                (CompositeHealthDescriptor) context.getBean(HealthEndpoint.class).health();
        final HealthDescriptor broker = overall.getComponents().get(BROKER_COMPONENT);
        return broker == null ? Status.UNKNOWN : broker.getStatus();
    }

    private static double brokerGauge(final ConfigurableApplicationContext context) {
        final Gauge found = context.getBean(MeterRegistry.class)
                .find(ProcessingMetrics.SERVICEBUS_UP).gauge();
        return found == null ? Double.NaN : found.value();
    }

    private Optional<Row> row() {
        return ProcessedLogTestSupport.row(ProcessedLogTestSupport.SOURCE, requestId);
    }

    @Test
    @DisplayName("started with no broker, it comes up ready, says so, and consumes when the queue appears")
    void should_start_ready_report_the_broker_down_and_consume_once_it_returns() {
        // Started first so the connection string is real, then frozen: the scenario is a broker
        // that exists and is not answering, not a broker that was never configured.
        ServiceBusEmulatorTestSupport.container();
        ServiceBusEmulatorTestSupport.disconnect();

        final ConfigurableApplicationContext context = assertDoesNotThrow(
                // A short staleness window, because it is also the grace this consumer is given
                // before "we have never once heard from the broker" becomes something worth
                // reporting. The deployed minute would make the suite wait out a minute to observe
                // a rule that does not depend on its length.
                () -> ServiceTestSupport.start(
                        Map.of("informantregister.servicebus.health-staleness", "5s")),
                "context refresh must complete with the broker down: a pod that crash-loops "
                        + "reports nothing");
        try (context) {
            await().atMost(OBSERVED_WITHIN).pollInterval(POLL).until(() ->
                    Status.UP.equals(
                            context.getBean(HealthEndpoint.class)
                                    .healthForPath("readiness").getStatus()));

            await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                    .until(() -> Status.DOWN.equals(brokerStatus(context)));
            assertThat(brokerGauge(context))
                    .as("the dashboard is told the same thing the health endpoint is")
                    .isEqualTo(0);

            ServiceBusEmulatorTestSupport.restore();

            final String messageId =
                    ServiceTestSupport.publish(ServiceTestSupport.validBody(requestId, hearingId));
            await().atMost(CONSUMES_WITHIN).pollInterval(POLL).until(() ->
                    row().filter(found -> RequestStatus.COMPLETED.name().equals(found.status()))
                            .isPresent());

            assertThat(brokerStatus(context)).isEqualTo(Status.UP);
            assertThat(brokerGauge(context)).isEqualTo(1);
            assertThat(ProcessedLogTestSupport
                    .requireRow(ProcessedLogTestSupport.SOURCE, requestId).attempts())
                    .as("consumed once, by a pod that never restarted")
                    .isEqualTo(1);
            assertThat(ServiceBusEmulatorTestSupport
                    .peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE))
                    .as("nothing was parked for having arrived at an awkward moment")
                    .isEmpty();
        }
    }
}
