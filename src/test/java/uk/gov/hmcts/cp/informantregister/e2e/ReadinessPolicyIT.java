package uk.gov.hmcts.cp.informantregister.e2e;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.azure.core.amqp.exception.AmqpErrorCondition;
import com.azure.core.amqp.exception.AmqpErrorContext;
import com.azure.core.amqp.exception.AmqpException;
import com.azure.messaging.servicebus.ServiceBusErrorSource;
import com.azure.messaging.servicebus.ServiceBusException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.config.ServiceBusHealthIndicator;
import uk.gov.hmcts.cp.informantregister.support.AdjustableClock;
import uk.gov.hmcts.cp.informantregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ServiceTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The readiness policy, proven against both dependencies going away (spec FR-011, research §8).
 *
 * <p>The policy is asymmetric on purpose, and the asymmetry is the whole test:
 *
 * <ul>
 *   <li><strong>The store gates readiness.</strong> Processing is unsafe without the processed log —
 *       a pod that cannot record what it has done must not be sent work — so {@code db} is the
 *       readiness group's only member and a store outage takes the pod out of service.</li>
 *   <li><strong>The queue never gates readiness.</strong> A pod cannot heal a broker by restarting,
 *       so putting the broker in readiness converts a blip into a rolling restart. It is reported as
 *       its own health component and its own gauge, and that is all.</li>
 * </ul>
 *
 * <p>The staleness rule is asserted against the indicator directly, with a clock the test moves.
 * Its content is entirely "how long ago was that error?", and both interesting cases sit a
 * millisecond either side of the window: a suite that slept could not land on either deliberately,
 * and one that waited a real minute would trade an exact assertion for a slow, approximate one.
 *
 * <p>Both container suites here freeze a dependency the whole build shares. That is safe because
 * Gradle runs this build's suites sequentially in one JVM — no other suite is running while a
 * container is paused — and because every freeze is undone in {@code @AfterEach}, including when an
 * assertion fails. The alternative, a dedicated pair of containers for the outage suites, was
 * rejected: it doubles the slowest part of the build for isolation the execution model already
 * provides, and "the shared store went away" is the fact production presents.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// The context owns a running consumer on the shared emulator queue, and this suite deliberately
// breaks that consumer's dependencies. Closing it with the class keeps both facts local.
class ReadinessPolicyIT {

    private static final String STORE_COMPONENT = "db";
    private static final String BROKER_COMPONENT = "servicebus";

    private static final Duration OBSERVED_WITHIN = Duration.ofSeconds(120);
    private static final Duration POLL = Duration.ofSeconds(1);

    /** The default window, so the boundary asserted below is the one the service ships with. */
    private static final Duration STALENESS = Duration.ofSeconds(60);

    /** How much work is in flight when the broker is taken away. */
    private static final int BURST = 60;

    private static final String SOURCE = "RECEIVE";
    private static final String ENTITY_PATH = "informantregister.requests";

    private static String connectionString;

    @Autowired
    private HealthEndpoint healthEndpoint;

    @DynamicPropertySource
    static void wireTheContainers(final DynamicPropertyRegistry registry) {
        connectionString = ServiceBusEmulatorTestSupport.connectionString();
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
        registry.add("informantregister.servicebus.connection-string", () -> connectionString);
        // A frozen container swallows the connection attempt rather than refusing it, so the driver
        // waits out its connect timeout. The deployed default is thirty seconds, which would make
        // every health poll in this suite a thirty-second block; three keeps the outage observable
        // without changing what is being observed.
        registry.add("spring.datasource.hikari.connection-timeout", () -> "3000");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "2000");
        // And a socket timeout, because a frozen container stops answering on connections it never
        // closes: a query over a connection the pool already holds would otherwise wait for ever.
        registry.add("spring.datasource.hikari.data-source-properties.socketTimeout", () -> "5");
    }

    @AfterEach
    void thawEverything() {
        PostgresTestSupport.unpause();
        ServiceBusEmulatorTestSupport.restore();
    }

    // --- helpers ---------------------------------------------------------------------------

    private Status readinessStatus() {
        return healthEndpoint.healthForPath("readiness").getStatus();
    }

    private CompositeHealthDescriptor readiness() {
        return (CompositeHealthDescriptor) healthEndpoint.healthForPath("readiness");
    }

    private CompositeHealthDescriptor overall() {
        return (CompositeHealthDescriptor) healthEndpoint.health();
    }

    private Status brokerComponentStatus() {
        final var component = overall().getComponents().get(BROKER_COMPONENT);
        return component == null ? Status.UNKNOWN : component.getStatus();
    }

    /**
     * A connection-class fault: the link the receiver was using was torn down under it.
     */
    private static Throwable connectionFailure() {
        return new ServiceBusException(
                new AmqpException(true, AmqpErrorCondition.CONNECTION_FORCED,
                        "the connection was forced closed",
                        new AmqpErrorContext("sbemulatorns")),
                ServiceBusErrorSource.RECEIVE);
    }

    private static ServiceBusHealthIndicator indicatorOn(final Clock clock) {
        return new ServiceBusHealthIndicator(
                STALENESS, new ProcessingMetrics(new SimpleMeterRegistry()), clock);
    }

    // --- the policy ------------------------------------------------------------------------

    @Test
    @DisplayName("readiness names the store and never the broker")
    void should_gate_readiness_on_the_store_alone() {
        assertThat(readiness().getComponents())
                .as("the store gates readiness; nothing else does")
                .containsOnlyKeys(STORE_COMPONENT);

        assertThat(overall().getComponents())
                .as("the broker is still observable — as its own component, outside readiness")
                .containsKey(BROKER_COMPONENT);
    }

    @Test
    @DisplayName("a store outage takes readiness down, and readiness comes back with the store")
    void should_report_readiness_down_while_the_store_is_unreachable() {
        assertThat(readinessStatus()).isEqualTo(Status.UP);

        PostgresTestSupport.pause();
        await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                .until(() -> Status.DOWN.equals(readinessStatus()));

        PostgresTestSupport.unpause();
        await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                .until(() -> Status.UP.equals(readinessStatus()));
    }

    @Test
    @DisplayName("a queue outage leaves readiness up and shows itself in the broker component")
    void should_keep_readiness_up_and_report_the_broker_down_during_a_queue_outage() {
        // The outage is staged with work in hand, because that is the only kind the SDK reports.
        // A processor with nothing to do treats a lost connection as retryable and rolls its
        // message pump silently and indefinitely — measured at five minutes against a broker whose
        // container had been stopped outright, with no callback of any kind. A settlement in
        // progress when the connection dies fails at once and is evidence; one started afterwards
        // blocks indefinitely and is not. The broker is therefore taken away in the middle of a
        // burst of real work, which is what makes this a test rather than a race.
        final List<UUID> burst = ServiceTestSupport.publishBurst(BURST);
        await().atMost(OBSERVED_WITHIN).pollInterval(Duration.ofMillis(200)).until(() ->
                ProcessedLogTestSupport.row(ProcessedLogTestSupport.SOURCE, burst.getFirst())
                        .isPresent());

        ServiceBusEmulatorTestSupport.disconnect();
        try {
            await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                    .until(() -> Status.DOWN.equals(brokerComponentStatus()));

            assertThat(readinessStatus())
                    .as("a broker blip must never roll the pods")
                    .isEqualTo(Status.UP);
        } finally {
            ServiceBusEmulatorTestSupport.restore();
        }

        await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                .until(() -> Status.UP.equals(brokerComponentStatus()));
    }

    @Test
    @DisplayName("an unresolved error older than the staleness window, with no traffic, is not an outage")
    void should_stop_reporting_an_error_that_nothing_has_contradicted_or_repeated() {
        final AdjustableClock clock = AdjustableClock.startingAt(Instant.parse("2026-08-21T09:00:00Z"));
        final ServiceBusHealthIndicator indicator = indicatorOn(clock);

        indicator.recordProcessorError(SOURCE, ENTITY_PATH, connectionFailure());
        assertThat(indicator.health().getStatus())
                .as("a fresh, unresolved connection failure is an outage")
                .isEqualTo(Status.DOWN);

        clock.advance(STALENESS.minusSeconds(1));
        assertThat(indicator.health().getStatus())
                .as("still inside the window, and still unresolved")
                .isEqualTo(Status.DOWN);

        clock.advance(Duration.ofSeconds(2));
        assertThat(indicator.health().getStatus())
                .as("an idle queue produces no traffic, and absence of traffic is not an outage")
                .isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("traffic more recent than the error resolves it at once")
    void should_report_up_as_soon_as_the_broker_answers_again() {
        final AdjustableClock clock = AdjustableClock.startingAt(Instant.parse("2026-08-21T09:00:00Z"));
        final ServiceBusHealthIndicator indicator = indicatorOn(clock);

        indicator.recordProcessorError(SOURCE, ENTITY_PATH, connectionFailure());
        clock.advance(Duration.ofSeconds(1));
        indicator.recordTraffic();

        assertThat(indicator.health().getStatus())
                .as("a receive that succeeded after the error is the answer to the error")
                .isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("a fault that is not the transport is not a queue outage")
    void should_not_report_an_outage_for_a_fault_the_broker_did_not_cause() {
        final AdjustableClock clock = AdjustableClock.startingAt(Instant.parse("2026-08-21T09:00:00Z"));
        final ServiceBusHealthIndicator indicator = indicatorOn(clock);

        indicator.recordProcessorError(
                SOURCE, ENTITY_PATH, new IllegalStateException("this service's own defect"));

        assertThat(indicator.health().getStatus())
                .as("only a connection-class failure means the queue is unreachable")
                .isEqualTo(Status.UP);
    }
}
