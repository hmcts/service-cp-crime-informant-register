package uk.gov.hmcts.cp.informantregister.inbound;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.health.contributor.Status;
import uk.gov.hmcts.cp.informantregister.config.IntakeStartupHealthIndicator;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.config.ServiceBusHealthIndicator;
import uk.gov.hmcts.cp.informantregister.persistence.ProcessedLogProbe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The gated start, and what readiness is allowed to say while it is going on.
 *
 * <p>Readiness is asked "is this pod in a position to be sent work". A group containing only the
 * store's contributor answers a narrower question — "does the database reply" — and between those
 * two sits a whole start-up that can fail. A pod whose deferred migration is throwing reports the
 * database UP, is therefore ready, is counted as a healthy replica by a rolling deployment, and
 * consumes nothing at all. The deployment completes; the queue quietly grows. That is the failure
 * this suite exists to make impossible.
 *
 * <p>The two halves of the start are asserted separately because they fail separately and recover
 * differently. A migration that fails must be retried; a start that fails after a migration that
 * succeeded must <strong>not</strong> re-run the migration, because a migration is not an operation
 * anybody wants attempted more times than necessary.
 *
 * <p>The probe interval is a few milliseconds so the retries this suite is about happen while it
 * watches. Awaitility waits on the conditions themselves, never on the clock.
 */
class ConsumerLifecycleControllerTest {

    /** Fast enough that a retry happens while the test is looking, slow enough to be a schedule. */
    private static final Duration PROBE_INTERVAL = Duration.ofMillis(20);

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private final ServiceBusProcessorClient processor = mock(ServiceBusProcessorClient.class);
    private final ProcessedLogProbe storeProbe = mock(ProcessedLogProbe.class);
    private final Flyway flyway = mock(Flyway.class);
    private final ProcessingMetrics metrics = new ProcessingMetrics(new SimpleMeterRegistry());
    private final ServiceBusHealthIndicator health = new ServiceBusHealthIndicator(
            Duration.ofSeconds(60), metrics, Clock.systemUTC());

    private final ConsumerLifecycleController controller = new ConsumerLifecycleController(
            processor, storeProbe, () -> flyway, metrics, health, PROBE_INTERVAL);

    private final IntakeStartupHealthIndicator readiness =
            new IntakeStartupHealthIndicator(Optional.of(controller));

    @AfterEach
    void stopTheController() {
        controller.stop();
    }

    private Status readinessStatus() {
        return readiness.health().getStatus();
    }

    // --- the ordinary start --------------------------------------------------------------------

    @Test
    @DisplayName("the migration runs before the processor, and only then is the pod ready")
    void should_migrate_then_start_and_only_then_report_ready() {
        when(storeProbe.available()).thenReturn(true);

        assertThat(readinessStatus())
                .as("nothing has happened yet, and readiness must not pretend otherwise")
                .isEqualTo(Status.DOWN);

        controller.start();

        await().atMost(PATIENCE).until(controller::intakeStarted);

        final InOrder order = inOrder(flyway, processor);
        order.verify(flyway).migrate();
        order.verify(processor).start();
        assertThat(readinessStatus()).isEqualTo(Status.UP);
    }

    // --- a migration that fails ------------------------------------------------------------------

    @Test
    @DisplayName("a migration that fails keeps the pod unready and starts nothing")
    void should_not_start_consuming_or_report_ready_when_the_migration_fails() {
        when(storeProbe.available()).thenReturn(true);
        doThrow(new IllegalStateException("the migration could not be applied"))
                .when(flyway).migrate();

        controller.start();

        // Retried, because a schema that could not be applied now may apply in a moment — and a
        // service that stopped asking would never come back without a restart.
        await().atMost(PATIENCE).untilAsserted(() -> verify(flyway, atLeast(2)).migrate());

        verify(processor, never()).start();
        assertThat(controller.intakeStarted()).isFalse();
        assertThat(readinessStatus())
                .as("a pod that has consumed nothing is not a healthy replica")
                .isEqualTo(Status.DOWN);
    }

    // --- a start that fails after a migration that did not --------------------------------------

    @Test
    @DisplayName("a start that fails is retried without re-running the migration that succeeded")
    void should_retry_only_the_start_when_the_migration_has_already_succeeded() {
        when(storeProbe.available()).thenReturn(true);
        doThrow(new IllegalStateException("the processor refused to start"))
                .doNothing()
                .when(processor).start();

        controller.start();

        await().atMost(PATIENCE).until(controller::intakeStarted);

        verify(flyway, times(1))
                .migrate();
        verify(processor, atLeast(2)).start();
        assertThat(readinessStatus()).isEqualTo(Status.UP);
    }

    // --- while the store is away ------------------------------------------------------------------

    @Test
    @DisplayName("a pod whose store never answers stays unready and consumes nothing")
    void should_stay_unready_while_the_store_has_never_answered() {
        when(storeProbe.available()).thenReturn(false);

        controller.start();

        await().atMost(PATIENCE).untilAsserted(() -> verify(storeProbe, atLeast(3)).available());

        verify(flyway, never()).migrate();
        verify(processor, never()).start();
        assertThat(readinessStatus()).isEqualTo(Status.DOWN);
    }
}
