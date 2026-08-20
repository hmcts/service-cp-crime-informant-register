package uk.gov.hmcts.cp.informantregister.support;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.testcontainers.azure.ServiceBusEmulatorContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Shared Service Bus emulator fixture for the broker and end-to-end suites.
 *
 * <p>One emulator per JVM with its SQL Server companion, both tags pinned: a moved tag on a broker
 * emulator can change settlement or delivery-count behaviour under a green build, which is exactly
 * the behaviour these suites exist to pin (research §10).
 *
 * <p>The queue definition is mounted from {@code docker/servicebus-emulator/config.json} — the same
 * file {@code docker-compose.yml} mounts — so the delivery limit and duplicate-detection settings
 * cannot drift between the local stack and CI.
 */
public final class ServiceBusEmulatorTestSupport {

    public static final String QUEUE_NAME = "informantregister.requests";

    private static final String EMULATOR_IMAGE =
            "mcr.microsoft.com/azure-messaging/servicebus-emulator:1.1.2";

    /**
     * The emulator's state store. {@code mcr.microsoft.com/azure-sql-edge} is retired and must not
     * be reintroduced (research §10).
     */
    private static final String MSSQL_IMAGE = "mcr.microsoft.com/mssql/server:2022-latest";

    private static final Path CONFIG_PATH =
            Paths.get("docker", "servicebus-emulator", "config.json").toAbsolutePath();

    private static final Network NETWORK = Network.newNetwork();

    private static final MSSQLServerContainer MSSQL = new MSSQLServerContainer(MSSQL_IMAGE)
            .acceptLicense()
            .withNetwork(NETWORK);

    private static final ServiceBusEmulatorContainer EMULATOR =
            new ServiceBusEmulatorContainer(EMULATOR_IMAGE)
                    .acceptLicense()
                    .withNetwork(NETWORK)
                    .withConfig(MountableFile.forHostPath(CONFIG_PATH))
                    .withMsSqlServerContainer(MSSQL);

    private ServiceBusEmulatorTestSupport() {
        // Static fixture holder.
    }

    /**
     * Returns the shared emulator, starting it and its companion if this is the first call.
     */
    public static ServiceBusEmulatorContainer container() {
        if (!EMULATOR.isRunning()) {
            if (!MSSQL.isRunning()) {
                MSSQL.start();
            }
            EMULATOR.start();
        }
        return EMULATOR;
    }

    /**
     * The emulator connection string, ending {@code UseDevelopmentEmulator=true;}.
     */
    public static String connectionString() {
        return container().getConnectionString();
    }
}
