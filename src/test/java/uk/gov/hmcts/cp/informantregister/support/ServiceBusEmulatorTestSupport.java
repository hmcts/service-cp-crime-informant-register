package uk.gov.hmcts.cp.informantregister.support;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.models.SubQueue;
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

    /** How many messages one peek reads. The search pages, so this bounds a round trip, not a scan. */
    private static final int PEEK_PAGE_SIZE = 32;

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

    /**
     * Freezes the broker, severing every open AMQP connection without losing the queue.
     *
     * <p>The same technique the store-outage suites use on Postgres, and for the same reason:
     * stopping the container would change its mapped port, so every connection string a running
     * context holds would be pointing at nothing even after the broker came back — which is a
     * different outage from the one operations staff meet, and not the one spec SC-004 describes.
     * A pause severs the connections and leaves the endpoint exactly where it was.
     */
    public static void pause() {
        container().getDockerClient()
                .pauseContainerCmd(container().getContainerId())
                .exec();
    }

    /**
     * Thaws a broker frozen by {@link #pause()}, whether or not it is frozen.
     *
     * <p>Idempotent for the same reason the store's helper is: an outage suite thaws from an
     * {@code @AfterEach}, and Docker's 500 for "not paused" would replace the assertion the suite
     * really failed on with a fixture error.
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

    /**
     * Looks for one message by its broker identity, on the queue or on its dead-letter queue.
     *
     * <p>Every broker suite in this repository shares one queue, so they all assert about their own
     * message rather than about the queue's contents. That only works if the search really covers the
     * queue: a single {@code peekMessages(n)} reads the first {@code n} messages and no further, so a
     * target sitting behind a neighbour's backlog is reported absent — a false pass for "the message
     * left the queue", and a timeout for "the message reached the dead-letter queue". Both are
     * failures that would be blamed on the service.
     *
     * <p>It therefore pages from the beginning of the queue until the message is found or a page
     * comes back empty. Peeking never locks or consumes, so paging costs nothing but round trips and
     * leaves the queue exactly as it was.
     *
     * @param messageId the broker identity to look for
     * @param subQueue  {@link SubQueue#NONE} for the queue itself, {@link SubQueue#DEAD_LETTER_QUEUE}
     *                  for its dead-letter queue
     * @return the message, if the queue holds one under that identity
     */
    public static Optional<ServiceBusReceivedMessage> peekFor(
            final String messageId, final SubQueue subQueue) {
        try (ServiceBusReceiverClient receiver = new ServiceBusClientBuilder()
                .connectionString(connectionString())
                .receiver()
                .queueName(QUEUE_NAME)
                .subQueue(subQueue)
                .buildClient()) {
            return pageFor(receiver, messageId);
        }
    }

    private static Optional<ServiceBusReceivedMessage> pageFor(
            final ServiceBusReceiverClient receiver, final String messageId) {
        Optional<ServiceBusReceivedMessage> found = Optional.empty();
        long fromSequenceNumber = 0L;
        boolean queueHasMore = true;
        while (found.isEmpty() && queueHasMore) {
            final List<ServiceBusReceivedMessage> page =
                    receiver.peekMessages(PEEK_PAGE_SIZE, fromSequenceNumber).stream().toList();
            queueHasMore = !page.isEmpty();
            if (queueHasMore) {
                found = page.stream()
                        .filter(message -> messageId.equals(message.getMessageId()))
                        .findFirst();
                // Strictly past the last message read, so the next page cannot repeat one and the
                // loop cannot fail to advance.
                fromSequenceNumber = page.get(page.size() - 1).getSequenceNumber() + 1;
            }
        }
        return found;
    }
}
