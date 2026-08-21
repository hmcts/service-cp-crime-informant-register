package uk.gov.hmcts.cp.informantregister.e2e;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.models.SubQueue;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.informantregister.domain.RequestStatus;
import uk.gov.hmcts.cp.informantregister.inbound.InformantRegisterMessageListener;
import uk.gov.hmcts.cp.informantregister.support.CapturedLog;
import uk.gov.hmcts.cp.informantregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport.Row;
import uk.gov.hmcts.cp.informantregister.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ServiceTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * What a store outage must cost: one abandoned delivery, and nothing else (spec FR-015).
 *
 * <p>The processed log is a precondition, not a step. Without it the service cannot tell a first
 * delivery from a redelivery, cannot record that it ran, and cannot recognise a request it has
 * already completed — so a delivery that arrives while the store is down is handed straight back,
 * unexamined, and intake stops. Stopping intake is the whole point: a service that kept consuming
 * would abandon delivery after delivery, and the broker's delivery budget would carry perfectly
 * good work onto the dead-letter queue while nothing was wrong with any of it.
 *
 * <p>Three consequences are asserted separately because each can break on its own:
 *
 * <ul>
 *   <li>the in-hand delivery is <strong>abandoned</strong> — never acknowledged, never parked;</li>
 *   <li>intake <strong>suspends</strong>, and says so in both instruments, and <strong>resumes</strong>
 *       when the store comes back, which is the half a gauge that only ever goes up would miss;</li>
 *   <li>a message that can never validate is <strong>not examined</strong> while the store is down.
 *       Availability is checked first, so the service does not get as far as reading the body, and
 *       the dead-letter it deserves arrives when the store does.</li>
 * </ul>
 *
 * <p>The fourth case is the one that only happens once: a pod that starts while the store is
 * already down. Context refresh must complete anyway — actuator has to be up to report readiness
 * DOWN honestly, and a pod that cannot start cannot tell anyone why — and nothing may be consumed
 * until the first successful probe, which is also what runs the deferred migration. It is asserted
 * against a database with nothing in it, because against an already-migrated one "the migration
 * ran" is unobservable.
 *
 * <p>Every test here starts its own service and closes it, rather than sharing a cached context.
 * Two reasons: the start-with-the-store-down case has to decide what the world looks like before
 * the context refreshes, and every case counts what its own message did on a queue the whole build
 * shares — which is only a fact about this service if no other consumer is alive at the time.
 */
class StoreOutageIT {

    private static final Duration OBSERVED_WITHIN = Duration.ofSeconds(90);
    private static final Duration POLL = Duration.ofSeconds(1);

    /** Long enough that a service which had ignored the outage would have consumed by now. */
    private static final Duration LEFT_ALONE_FOR = Duration.ofSeconds(8);

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    @BeforeAll
    static void migrateTheSharedStore() {
        // The shared database, migrated once, is what the first three cases run against.
        ProcessedLogTestSupport.dataSource();
    }

    @AfterEach
    void thawTheStore() {
        PostgresTestSupport.unpause();
    }

    // --- helpers ---------------------------------------------------------------------------

    private static double gauge(final MeterRegistry registry, final String name) {
        final Gauge found = registry.find(name).gauge();
        return found == null ? Double.NaN : found.value();
    }

    private static double counter(final MeterRegistry registry, final String name) {
        final Counter found = registry.find(name).counter();
        return found == null ? 0 : found.count();
    }

    private static double counter(
            final MeterRegistry registry, final String name, final String tag, final String value) {
        final Counter found = registry.find(name).tag(tag, value).counter();
        return found == null ? 0 : found.count();
    }

    private static MeterRegistry meters(final ConfigurableApplicationContext context) {
        return context.getBean(MeterRegistry.class);
    }

    private Optional<Row> row() {
        return ProcessedLogTestSupport.row(ProcessedLogTestSupport.SOURCE, requestId);
    }

    private static Optional<ServiceBusReceivedMessage> onQueue(final String messageId) {
        return ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.NONE);
    }

    private static Optional<ServiceBusReceivedMessage> onDeadLetterQueue(final String messageId) {
        return ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE);
    }

    // --- the outage ------------------------------------------------------------------------

    @Test
    @DisplayName("a delivery arriving during a store outage is handed back, and intake suspends")
    void should_abandon_the_delivery_and_suspend_intake_until_the_store_returns() {
        try (ConfigurableApplicationContext context = ServiceTestSupport.start(Map.of())) {
            final MeterRegistry registry = meters(context);
            final double suspensionsBefore =
                    counter(registry, ProcessingMetrics.INTAKE_SUSPENSIONS);

            PostgresTestSupport.pause();
            final String messageId =
                    ServiceTestSupport.publish(ServiceTestSupport.validBody(requestId, hearingId));

            await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                    .until(() -> gauge(registry, ProcessingMetrics.INTAKE_SUSPENDED) == 1);

            assertThat(counter(registry, ProcessingMetrics.INTAKE_SUSPENSIONS))
                    .as("suspending intake is an incident, and it is counted")
                    .isGreaterThan(suspensionsBefore);
            assertThat(onDeadLetterQueue(messageId))
                    .as("a store outage is not the message's fault; nothing may park it")
                    .isEmpty();
            assertThat(onQueue(messageId))
                    .as("handed back, so it is still there to be processed when the store returns")
                    .isPresent();

            PostgresTestSupport.unpause();

            await().atMost(OBSERVED_WITHIN).pollInterval(POLL).until(() ->
                    row().filter(found -> RequestStatus.COMPLETED.name().equals(found.status()))
                            .isPresent());
            assertThat(gauge(registry, ProcessingMetrics.INTAKE_SUSPENDED))
                    .as("the gauge comes back down; an incident that never ends is not an incident")
                    .isEqualTo(0);
            assertThat(onDeadLetterQueue(messageId)).isEmpty();
        }
    }

    @Test
    @DisplayName("a message that can never validate is not even read until the store is back")
    void should_not_examine_a_contract_invalid_message_during_the_outage() {
        try (ConfigurableApplicationContext context = ServiceTestSupport.start(Map.of());
             CapturedLog listenerLog = CapturedLog.of(InformantRegisterMessageListener.class)) {
            final MeterRegistry registry = meters(context);
            final double parkedBefore = counter(registry, ProcessingMetrics.DEAD_LETTERED,
                    ProcessingMetrics.REASON_TAG, DeadLetterReason.VALIDATION.label());

            PostgresTestSupport.pause();
            final String messageId = ServiceTestSupport.publish(
                    ServiceTestSupport.contractInvalidBody(requestId, hearingId));

            await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                    .until(() -> gauge(registry, ProcessingMetrics.INTAKE_SUSPENDED) == 1);

            await().during(LEFT_ALONE_FOR).atMost(OBSERVED_WITHIN).pollInterval(POLL)
                    .until(() -> onQueue(messageId).isPresent());
            assertThat(listenerLog.messages())
                    .as("availability is checked first, so the body was never read")
                    .noneMatch(line -> line.startsWith("Message body failed contract validation"));
            assertThat(counter(registry, ProcessingMetrics.DEAD_LETTERED,
                    ProcessingMetrics.REASON_TAG, DeadLetterReason.VALIDATION.label()))
                    .isEqualTo(parkedBefore);

            PostgresTestSupport.unpause();

            await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                    .until(() -> onDeadLetterQueue(messageId).isPresent());
            final ServiceBusReceivedMessage parked = onDeadLetterQueue(messageId).orElseThrow();
            assertThat(parked.getDeadLetterReason())
                    .as("validated and parked as normal, once there was a store to check first")
                    .isEqualTo(DeadLetterReason.VALIDATION.label());
            assertThat(row())
                    .as("a contract-invalid message never enters the state machine")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("a pod that starts with the store down comes up, stays unready, and consumes nothing")
    void should_start_with_the_store_already_down_and_migrate_on_the_first_successful_probe() {
        final String database = "informantregister_gated_" + UUID.randomUUID().toString().replace("-", "");
        final String jdbcUrl = PostgresTestSupport.createEmptyDatabase(database);

        PostgresTestSupport.pause();
        final ConfigurableApplicationContext context = assertDoesNotThrow(
                () -> ServiceTestSupport.start(Map.of("spring.datasource.url", jdbcUrl)),
                "context refresh must complete with the store down: a pod that cannot start "
                        + "cannot report why it is not ready");
        try (context) {
            final HealthEndpoint health = context.getBean(HealthEndpoint.class);
            assertThat(health.healthForPath("readiness").getStatus())
                    .as("up enough to say it is not ready — which is the only honest thing to say")
                    .isEqualTo(Status.DOWN);

            final String messageId =
                    ServiceTestSupport.publish(ServiceTestSupport.validBody(requestId, hearingId));
            await().during(LEFT_ALONE_FOR).atMost(OBSERVED_WITHIN).pollInterval(POLL)
                    .until(() -> onQueue(messageId).isPresent());

            PostgresTestSupport.unpause();

            await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                    .until(() -> Status.UP.equals(health.healthForPath("readiness").getStatus()));

            final JdbcClient migrated = JdbcClient.create(unpooledDataSource(jdbcUrl));
            await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                    .until(() -> tableExists(migrated, "processed_request"));

            await().atMost(OBSERVED_WITHIN).pollInterval(POLL).until(() ->
                    ProcessedLogTestSupport.row(migrated, ProcessedLogTestSupport.SOURCE, requestId)
                            .filter(found -> RequestStatus.COMPLETED.name().equals(found.status()))
                            .isPresent());
            assertThat(onDeadLetterQueue(messageId))
                    .as("nothing was burned while the pod waited for its store")
                    .isEmpty();
        }
    }

    /**
     * A connection to a database this suite created, with no pool behind it: it is read from a
     * handful of times and a pool would only add a lifecycle to get wrong.
     */
    private static DataSource unpooledDataSource(final String jdbcUrl) {
        return new DriverManagerDataSource(
                jdbcUrl, PostgresTestSupport.username(), PostgresTestSupport.password());
    }

    private static boolean tableExists(final JdbcClient client, final String table) {
        return client.sql("SELECT to_regclass(:table) IS NOT NULL")
                .param("table", table)
                .query(Boolean.class)
                .single();
    }
}
