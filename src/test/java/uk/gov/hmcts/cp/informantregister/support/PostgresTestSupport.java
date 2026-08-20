package uk.gov.hmcts.cp.informantregister.support;

import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared Postgres fixture for the persistence and end-to-end suites.
 *
 * <p>One container per JVM, started on first use and left to the Ryuk reaper at exit, so the
 * several {@code *IT} suites that need a processed-log store pay the start-up cost once between
 * them rather than once each.
 *
 * <p>Production migration is deliberately off the context-refresh path (research §7: a no-op
 * {@code FlywayMigrationStrategy}, with the lifecycle controller migrating on the first successful
 * store probe). Persistence-slice suites therefore boot no controller and must migrate their own
 * container — {@link #applyFlyway()} is how they do it.
 */
public final class PostgresTestSupport {

    private static final String IMAGE = "postgres:16";

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer(IMAGE)
            .withDatabaseName("informantregister")
            .withUsername("informantregister")
            .withPassword("informantregister");

    private PostgresTestSupport() {
        // Static fixture holder.
    }

    /**
     * Returns the shared container, starting it if this is the first call.
     */
    public static PostgreSQLContainer container() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
        return CONTAINER;
    }

    public static String jdbcUrl() {
        return container().getJdbcUrl();
    }

    public static String username() {
        return container().getUsername();
    }

    public static String password() {
        return container().getPassword();
    }

    /**
     * Applies the committed Flyway migrations to the shared container.
     *
     * <p>Idempotent: Flyway skips migrations already recorded in its schema history, so suites may
     * call this from every {@code @BeforeAll} without coordinating with each other.
     */
    public static void applyFlyway() {
        Flyway.configure()
                .dataSource(jdbcUrl(), username(), password())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /**
     * Freezes the database process, severing every open connection without touching the volume.
     *
     * <p>This is how the store-outage suites produce an outage: the driver sees the connections
     * die, and {@link #unpause()} brings the same data back. Toxiproxy stays the recorded fallback
     * should pausing ever report a misleading error class (plan §Test matrix).
     */
    public static void pause() {
        container().getDockerClient()
                .pauseContainerCmd(container().getContainerId())
                .exec();
    }

    /**
     * Thaws a container frozen by {@link #pause()}.
     */
    public static void unpause() {
        container().getDockerClient()
                .unpauseContainerCmd(container().getContainerId())
                .exec();
    }
}
