package uk.gov.hmcts.cp.informantregister.e2e;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.azure.core.amqp.exception.AmqpErrorCondition;
import com.azure.core.amqp.exception.AmqpErrorContext;
import com.azure.core.amqp.exception.AmqpException;
import com.azure.messaging.servicebus.ServiceBusErrorSource;
import com.azure.messaging.servicebus.ServiceBusException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.application.HearingPayloadSource;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.config.ServiceBusHealthIndicator;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.support.AdjustableClock;
import uk.gov.hmcts.cp.informantregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ServiceTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
    private static final String STARTUP_COMPONENT = "intakeStartup";
    private static final String BROKER_COMPONENT = "servicebus";

    private static final Duration OBSERVED_WITHIN = Duration.ofSeconds(120);
    private static final Duration POLL = Duration.ofSeconds(1);

    /** The default window, so the boundary asserted below is the one the service ships with. */
    private static final Duration STALENESS = Duration.ofSeconds(60);

    /** So a failure is reported as a failure rather than as a hang. */
    private static final Duration HELD_AT_MOST = Duration.ofMinutes(2);

    /** Nothing that resembles hearing content: this increment handles no defendant data. */
    private static final JsonNode PLACEHOLDER =
            JacksonConfig.contractObjectMapper().readTree("{\"stub\":true}");

    private static final String SOURCE = "RECEIVE";
    private static final String ENTITY_PATH = "informantregister.requests";

    private static String connectionString;

    @MockitoBean
    private HearingPayloadSource payloadSource;

    @Autowired
    private HealthEndpoint healthEndpoint;

    /** The request whose run is held open across the transport cut. */
    private final UUID held = UUID.randomUUID();

    /** Raised when that run has genuinely started. */
    private final CountDownLatch inFlight = new CountDownLatch(1);

    /** Lowered once the transport has been cut underneath it. */
    private final CountDownLatch release = new CountDownLatch(1);

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

    @BeforeEach
    void holdOneRunOpen() {
        when(payloadSource.fetch(any(DistributionCommand.class))).thenAnswer(this::payloadFor);
    }

    @AfterEach
    void thawEverything() {
        release.countDown();
        PostgresTestSupport.unpause();
        ServiceBusEmulatorTestSupport.restore();
    }

    /**
     * The payload port. A neighbouring suite's message is handed the placeholder and passes through.
     */
    private JsonNode payloadFor(final InvocationOnMock invocation) throws InterruptedException {
        final DistributionCommand command = invocation.getArgument(0);
        if (held.equals(command.requestId())) {
            inFlight.countDown();
            release.await(HELD_AT_MOST.toSeconds(), TimeUnit.SECONDS);
        }
        return PLACEHOLDER;
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
                .as("the store gates readiness, and so does this pod's own gated start — a database "
                        + "that replies is not a service in a position to use it")
                .containsOnlyKeys(STORE_COMPONENT, STARTUP_COMPONENT);

        assertThat(overall().getComponents())
                .as("the broker is still observable — as its own component, outside readiness")
                .containsKey(BROKER_COMPONENT);
    }

    @Test
    @DisplayName("a store outage takes readiness down, and readiness comes back with the store")
    void should_report_readiness_down_while_the_store_is_unreachable() {
        // Waited for rather than assumed: readiness now also covers this pod's own gated start, and
        // the start is on a probe interval. A test that asserted UP the instant the context came up
        // would be asserting that the start had already happened, which is a different claim.
        await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                .until(() -> Status.UP.equals(readinessStatus()));

        PostgresTestSupport.pause();
        await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                .until(() -> Status.DOWN.equals(readinessStatus()));

        PostgresTestSupport.unpause();
        await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                .until(() -> Status.UP.equals(readinessStatus()));
    }

    @Test
    @DisplayName("a queue outage leaves readiness up and shows itself in the broker component")
    void should_keep_readiness_up_and_report_the_broker_down_during_a_queue_outage()
            throws InterruptedException {
        // The outage is staged with work in hand, because that is the only kind the SDK reports: a
        // processor with nothing to do treats a lost connection as retryable and rolls its message
        // pump silently, and five minutes against a broker that had been stopped outright produced
        // no callback of any kind. The evidence research §8 is built on is a round trip that
        // failed, so there has to be one.
        //
        // Cutting the transport through the proxy makes that a sequence rather than a race: hold a
        // delivery, cut, then let it finish into a settlement that is refused at once.
        ServiceTestSupport.publish(ServiceTestSupport.validBody(held, UUID.randomUUID()));
        assertThat(inFlight.await(OBSERVED_WITHIN.toSeconds(), TimeUnit.SECONDS))
                .as("the run must genuinely be in flight before the transport is cut")
                .isTrue();

        ServiceBusEmulatorTestSupport.disconnect();
        release.countDown();
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
    @DisplayName("an unresolved error older than the staleness window, on an idle queue, is not an outage")
    void should_stop_reporting_an_error_that_nothing_has_contradicted_or_repeated() {
        final AdjustableClock clock = AdjustableClock.startingAt(Instant.parse("2026-08-21T09:00:00Z"));
        final ServiceBusHealthIndicator indicator = indicatorOn(clock);

        // The broker has answered this consumer before: that is what entitles a later silence to
        // the idle-queue reading. A consumer never answered at all keeps reporting the fault — the
        // SDK will not repeat it, so aging it out would hide a total outage — and that case is
        // asserted in ServiceBusHealthIndicatorTest.
        indicator.recordTraffic();
        clock.advance(Duration.ofMinutes(5));

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

    @Test
    @DisplayName("a consumer that has not started yet says nothing about a broker it has not used")
    void should_not_report_an_outage_before_intake_has_started() {
        final AdjustableClock clock = AdjustableClock.startingAt(Instant.parse("2026-08-21T09:00:00Z"));
        final ServiceBusHealthIndicator indicator = indicatorOn(clock);

        // A pod gated on its store can wait a long time. Nothing has been asked of the broker, so
        // there is nothing to report about it — readiness already says this pod is not ready, and a
        // broker-DOWN alert for a store outage would send somebody to the wrong system.
        clock.advance(STALENESS.multipliedBy(10));

        assertThat(indicator.health().getStatus())
                .as("silence about a broker nobody has spoken to is not an outage")
                .isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("a started consumer that has never once been answered says so, on the boundary")
    void should_report_an_outage_when_a_started_consumer_has_never_heard_anything() {
        final AdjustableClock clock = AdjustableClock.startingAt(Instant.parse("2026-08-21T09:00:00Z"));
        final ServiceBusHealthIndicator indicator = indicatorOn(clock);

        indicator.recordIntakeStarted();

        clock.advance(STALENESS.minusMillis(1));
        assertThat(indicator.health().getStatus())
                .as("inside the grace an ordinary start is entitled to")
                .isEqualTo(Status.UP);

        clock.advance(Duration.ofMillis(1));
        assertThat(indicator.health().getStatus())
                .as("the window is complete: a consumer answered by nothing at all has a problem")
                .isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("an unresolved error exactly on the staleness boundary is still an outage")
    void should_still_report_an_error_that_is_exactly_as_old_as_the_window() {
        final AdjustableClock clock = AdjustableClock.startingAt(Instant.parse("2026-08-21T09:00:00Z"));
        final ServiceBusHealthIndicator indicator = indicatorOn(clock);

        indicator.recordProcessorError(SOURCE, ENTITY_PATH, connectionFailure());
        clock.advance(STALENESS);

        assertThat(indicator.health().getStatus())
                .as("older than the window is the rule; exactly the window is not older than it")
                .isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("a settlement the broker accepted answers the error before it, as a receive would")
    void should_treat_an_accepted_settlement_as_evidence_the_broker_is_there() {
        final AdjustableClock clock = AdjustableClock.startingAt(Instant.parse("2026-08-21T09:00:00Z"));
        final ServiceBusHealthIndicator indicator = indicatorOn(clock);

        indicator.recordProcessorError(SOURCE, ENTITY_PATH, connectionFailure());
        clock.advance(Duration.ofSeconds(1));
        // A settlement the broker took is a round trip it completed. Counting only receives means a
        // consumer working steadily through a backlog it received before the blip reports an outage
        // it is plainly not having.
        indicator.recordSettlementAccepted();

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("the gauge answers a scrape correctly without the health endpoint being asked first")
    void should_expose_the_broker_gauge_to_a_scrape_that_never_calls_health() {
        final AdjustableClock clock = AdjustableClock.startingAt(Instant.parse("2026-08-21T09:00:00Z"));
        final SimpleMeterRegistry scraped = new SimpleMeterRegistry();
        final ServiceBusHealthIndicator indicator =
                new ServiceBusHealthIndicator(STALENESS, new ProcessingMetrics(scraped), clock);

        indicator.recordProcessorError(SOURCE, ENTITY_PATH, connectionFailure());

        // Deliberately no health() call. Prometheus does not visit the health endpoint on its way
        // past, and a gauge that is only correct after somebody else has asked the same question is
        // a dashboard that disagrees with the probe for as long as nobody probes.
        assertThat(scraped.find(ProcessingMetrics.SERVICEBUS_UP).gauge().value())
                .as("the gauge and the component answer from the same live state")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("a settlement refused about one message is not an outage of the queue")
    void should_not_report_an_outage_for_a_refusal_that_was_about_the_message() {
        final AdjustableClock clock = AdjustableClock.startingAt(Instant.parse("2026-08-21T09:00:00Z"));
        final ServiceBusHealthIndicator indicator = indicatorOn(clock);
        indicator.recordIntakeStarted();

        // A lock that ran out is the delivery's own business, and it arrived over a connection that
        // plainly worked. Counting it as an outage would put the broker on a dashboard every time a
        // run took slightly too long.
        indicator.recordSettlementRefusal(new ServiceBusException(
                new AmqpException(true, AmqpErrorCondition.MESSAGE_LOCK_LOST,
                        "the lock had already expired", new AmqpErrorContext("sbemulatorns")),
                ServiceBusErrorSource.COMPLETE));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }
}
