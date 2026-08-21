package uk.gov.hmcts.cp.informantregister.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

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
     * Creates an empty, unmigrated database inside the shared container and returns its JDBC URL.
     *
     * <p>For the one suite that has to watch a migration happen. Against the shared database, which
     * every other suite has already migrated, "the deferred migration ran" is unobservable — Flyway
     * would find its own history table and do nothing, and the assertion would pass whether the
     * migration was invoked or not. A database with nothing in it makes the question answerable.
     *
     * @param name the database to create; must be a plain identifier
     * @return the JDBC URL of the new, empty database
     */
    public static String createEmptyDatabase(final String name) {
        if (!name.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("not a plain database identifier: " + name);
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl(), username(), password());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException failed) {
            throw new IllegalStateException("could not create the database " + name, failed);
        }
        return "jdbc:postgresql://" + container().getHost() + ':'
                + container().getFirstMappedPort() + '/' + name;
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
     * Thaws a container frozen by {@link #pause()}, whether or not it is frozen.
     *
     * <p>Idempotent deliberately. The outage suites thaw the store from an {@code @AfterEach} so
     * that a failing assertion cannot leave the rest of the build running against a frozen
     * database; Docker refuses an unpause of a running container with a 500, and that refusal would
     * replace the assertion the suite actually failed on with a fixture error nobody can read.
     */
    public static void unpause() {
        if (paused()) {
            container().getDockerClient()
                    .unpauseContainerCmd(container().getContainerId())
                    .exec();
        }
    }

    private static boolean paused() {
        return Boolean.TRUE.equals(container().getDockerClient()
                .inspectContainerCmd(container().getContainerId())
                .exec()
                .getState()
                .getPaused());
    }
}
