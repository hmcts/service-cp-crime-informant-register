package uk.gov.hmcts.cp.informantregister.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import uk.gov.hmcts.cp.informantregister.application.IdempotencyGuard;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport.Row;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The processed log is durable, not remembered (spec US1-3).
 *
 * <p>A request is recorded, the database is restarted underneath the service, and the row is read
 * back unchanged. An in-memory guard — or one that never committed — passes every other suite in this
 * package and loses the register the first time a pod or a database moves.
 *
 * <p>This suite owns its container rather than sharing the one the other suites use, because a
 * restarted container is published on a new host port and the shared fixture's pool would be pointing
 * at the old one for every suite that ran afterwards.
 */
class ProcessedLogDurabilityIT {

    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final String DATABASE = "informantregister";
    private static final int POSTGRES_PORT = 5432;

    private static PostgreSQLContainer container;
    private static HikariDataSource dataSource;

    private final DistributionCommand command = ProcessedLogTestSupport.command();

    @BeforeAll
    static void startOwnStore() {
        container = new PostgreSQLContainer("postgres:16")
                .withDatabaseName(DATABASE)
                .withUsername(DATABASE)
                .withPassword(DATABASE);
        container.start();
        Flyway.configure()
                .dataSource(jdbcUrl(), DATABASE, DATABASE)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        openPool();
    }

    @AfterAll
    static void stopOwnStore() {
        closePool();
        container.stop();
    }

    @Test
    @DisplayName("a recorded request survives a restart of the store")
    void a_recorded_request_should_be_read_back_unchanged_after_a_restart() {
        final IdempotencyGuard guard = new IdempotencyGuard(
                new ProcessedRequestRepository(JdbcClient.create(dataSource), LEASE),
                new ProcessingMetrics(new SimpleMeterRegistry()));
        final GuardDecision admission =
                guard.admit(command, new DeliveryIdentity("msg-1", "runner-1/delivery-1"));
        assertThat(admission).isInstanceOf(GuardDecision.Run.class);
        guard.recordCompletion(
                ((GuardDecision.Run) admission).claim(), CompletionReason.NO_AUTHORITIES);
        final Row before = row();

        restartStore();

        assertThat(row()).isEqualTo(before);
    }

    private Row row() {
        return ProcessedLogTestSupport.requireRow(
                JdbcClient.create(dataSource), command.source(), command.requestId());
    }

    /**
     * Restarts the container in place — the data directory survives, the process does not — and
     * reopens the pool against whichever host port Docker publishes the second time round.
     */
    private static void restartStore() {
        closePool();
        container.getDockerClient().restartContainerCmd(container.getContainerId()).exec();

        await().atMost(Duration.ofMinutes(1))
                .pollInterval(Duration.ofMillis(250))
                .ignoreExceptions()
                .until(ProcessedLogDurabilityIT::storeAnswers);
        openPool();
    }

    private static boolean storeAnswers() throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(), DATABASE, DATABASE)) {
            return connection.isValid(1);
        }
    }

    private static void openPool() {
        dataSource = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(jdbcUrl())
                .username(DATABASE)
                .password(DATABASE)
                .build();
    }

    private static void closePool() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    /**
     * The current URL, re-inspected each time.
     *
     * <p>Testcontainers caches the port mapping it saw at start-up, and Docker publishes a freshly
     * chosen host port when a container with a dynamic mapping restarts, so the mapping is read back
     * from the daemon rather than remembered.
     */
    private static String jdbcUrl() {
        final Ports.Binding[] bindings = container.getDockerClient()
                .inspectContainerCmd(container.getContainerId())
                .exec()
                .getNetworkSettings()
                .getPorts()
                .getBindings()
                .get(new ExposedPort(POSTGRES_PORT));
        return "jdbc:postgresql://" + container.getHost() + ":" + bindings[0].getHostPortSpec()
                + "/" + DATABASE;
    }
}
