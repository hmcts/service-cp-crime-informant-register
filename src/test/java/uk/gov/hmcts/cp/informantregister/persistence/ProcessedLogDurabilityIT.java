package uk.gov.hmcts.cp.informantregister.persistence;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import uk.gov.hmcts.cp.informantregister.application.IdempotencyGuard;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport.Row;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The processed log is durable, not remembered (spec US1-3).
 *
 * <p>A processed request is recorded, the database is restarted underneath the service, and the row
 * is read back unchanged. An in-memory guard — or one that never flushed — would pass every other
 * suite in this package and lose the register the first time a pod moved.
 *
 * <p>The restart is a real container restart rather than a reconnect: the process dies, its buffers
 * go with it, and what comes back is what reached disk.
 */
class ProcessedLogDurabilityIT {

    private static final Duration LEASE = Duration.ofMinutes(5);

    private final IdempotencyGuard guard = ProcessedLogTestSupport.guard(LEASE);
    private final DistributionCommand command = ProcessedLogTestSupport.command();

    @Test
    void a_recorded_request_should_survive_a_restart_of_the_store() {
        final GuardDecision admission =
                guard.admit(command, new DeliveryIdentity("msg-1", "runner-1/delivery-1"));
        assertThat(admission).isInstanceOf(GuardDecision.Run.class);
        guard.recordCompletion(
                ((GuardDecision.Run) admission).claim(), CompletionReason.NO_AUTHORITIES);
        final Row before = ProcessedLogTestSupport.requireRow(command.source(), command.requestId());

        restartStore();

        assertThat(ProcessedLogTestSupport.requireRow(command.source(), command.requestId()))
                .isEqualTo(before);
    }

    /**
     * Restarts the shared container in place, keeping its data directory and its published port, and
     * waits for the database to answer again through the same pool — the pool's dead connections are
     * discarded on the way, which is what the service itself would experience.
     */
    private static void restartStore() {
        final PostgreSQLContainer container = PostgresTestSupport.container();
        container.getDockerClient().restartContainerCmd(container.getContainerId()).exec();

        await().atMost(Duration.ofMinutes(1))
                .pollInterval(Duration.ofMillis(250))
                .ignoreExceptions()
                .until(() -> ProcessedLogTestSupport.jdbcClient()
                        .sql("SELECT 1")
                        .query(Integer.class)
                        .single() == 1);
    }
}
