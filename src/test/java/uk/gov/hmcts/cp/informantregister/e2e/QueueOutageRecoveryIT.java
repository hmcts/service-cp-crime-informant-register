package uk.gov.hmcts.cp.informantregister.e2e;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.azure.messaging.servicebus.models.SubQueue;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
 * The broker goes away and comes back, and the pod neither restarts nor stays broken (spec SC-004,
 * US4-2).
 *
 * <p>Two halves, and the second is the one that is easy to get wrong. Reporting the outage is
 * straightforward; <strong>recovering from it without help</strong> is not. A client left on a long
 * default back-off will eventually reconnect, but "eventually" is not a service level: the budget is
 * sixty seconds from the queue returning, which is why the retry options are configured explicitly
 * rather than inherited.
 *
 * <p><strong>The outage is staged with work in hand, deliberately and not incidentally.</strong> The
 * SDK reports nothing at all about a broker that has gone away while the consumer is idle: it treats
 * a lost connection as retryable and rolls its message pump silently, and five minutes against a
 * stopped container produced no callback of any kind. A settlement <em>in progress</em> when the
 * connection dies fails at once and is evidence; a settlement <em>started</em> after it blocks
 * indefinitely and is not. So the broker is taken away in the middle of a burst of real work, which
 * is what makes this a test rather than a race — and it is also the case that matters
 * operationally, because an outage while there is nothing to do costs nothing.
 *
 * <p>The gauge is asserted alongside the health component because they answer the same question to
 * two different audiences — a probe and a dashboard — and a pair that can disagree is worse than
 * either alone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// The context owns a running consumer on the shared emulator queue, and this suite deliberately
// takes that broker away underneath it. Closing it with the class keeps both facts local.
class QueueOutageRecoveryIT {

    private static final String BROKER_COMPONENT = "servicebus";

    private static final Duration OBSERVED_WITHIN = Duration.ofSeconds(90);
    private static final Duration POLL = Duration.ofSeconds(1);

    /** Spec SC-004's budget, measured from the moment the queue comes back. */
    private static final Duration RESUMES_WITHIN = Duration.ofSeconds(60);

    /**
     * How much work is in flight when the broker is taken away.
     *
     * <p>Enough that the service is still working through it while the container stops, so a
     * settlement is in progress when the connection dies — see {@code publishBurst}.
     */
    private static final int BURST = 60;

    private static String connectionString;

    @Autowired
    private HealthEndpoint healthEndpoint;

    @Autowired
    private MeterRegistry registry;

    private final UUID afterwards = UUID.randomUUID();

    @DynamicPropertySource
    static void wireTheContainers(final DynamicPropertyRegistry registry) {
        connectionString = ServiceBusEmulatorTestSupport.connectionString();
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
        registry.add("informantregister.servicebus.connection-string", () -> connectionString);
    }

    @AfterEach
    void bringTheBrokerBack() {
        ServiceBusEmulatorTestSupport.restore();
    }

    // --- helpers ---------------------------------------------------------------------------

    private Status brokerStatus() {
        final CompositeHealthDescriptor overall =
                (CompositeHealthDescriptor) healthEndpoint.health();
        final HealthDescriptor broker = overall.getComponents().get(BROKER_COMPONENT);
        return broker == null ? Status.UNKNOWN : broker.getStatus();
    }

    private Status readinessStatus() {
        return healthEndpoint.healthForPath("readiness").getStatus();
    }

    private double brokerGauge() {
        final Gauge found = registry.find(ProcessingMetrics.SERVICEBUS_UP).gauge();
        return found == null ? Double.NaN : found.value();
    }

    private static Optional<Row> row(final UUID requestId) {
        return ProcessedLogTestSupport.row(ProcessedLogTestSupport.SOURCE, requestId);
    }

    // --- the outage and the recovery ---------------------------------------------------------

    @Test
    @DisplayName("the broker goes down and comes back; readiness never moves and consumption resumes")
    void should_report_the_outage_stay_ready_and_resume_consuming_when_the_queue_returns() {
        final List<UUID> burst = ServiceTestSupport.publishBurst(BURST);
        await().atMost(OBSERVED_WITHIN).pollInterval(Duration.ofMillis(200))
                .until(() -> row(burst.getFirst())
                        .filter(found -> RequestStatus.COMPLETED.name().equals(found.status()))
                        .isPresent());

        // Taken away mid-burst, so a settlement is in progress when the connection dies.
        ServiceBusEmulatorTestSupport.disconnect();

        await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                .until(() -> Status.DOWN.equals(brokerStatus()));
        assertThat(readinessStatus())
                .as("a pod cannot heal a broker by restarting, so the broker never gates readiness")
                .isEqualTo(Status.UP);
        assertThat(brokerGauge())
                .as("the gauge and the health component answer the same question")
                .isEqualTo(0);

        ServiceBusEmulatorTestSupport.restore();

        // Everything below is inside SC-004's sixty seconds, counted from the queue's return.
        final String messageId = ServiceTestSupport.publish(
                ServiceTestSupport.validBody(afterwards, UUID.randomUUID()));
        await().atMost(RESUMES_WITHIN).pollInterval(POLL).until(() ->
                row(afterwards).filter(found -> RequestStatus.COMPLETED.name().equals(found.status()))
                        .isPresent());

        assertThat(brokerStatus())
                .as("a receive that succeeded is the answer to the error that preceded it")
                .isEqualTo(Status.UP);
        assertThat(brokerGauge()).isEqualTo(1);
        assertThat(ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE))
                .as("an outage of the broker's is nobody's poison message")
                .isEmpty();
    }
}
