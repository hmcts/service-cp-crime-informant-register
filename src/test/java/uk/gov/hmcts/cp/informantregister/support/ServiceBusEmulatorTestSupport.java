package uk.gov.hmcts.cp.informantregister.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.models.SubQueue;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.azure.ServiceBusEmulatorContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.utility.MountableFile;

import static org.awaitility.Awaitility.await;

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

    /** The emulator's AMQP port inside the container. */
    private static final int AMQP_PORT = 5672;

    /**
     * The host port the emulator is pinned to.
     *
     * <p>Pinned, rather than left to Docker, because the broker-outage suites take the broker away
     * by stopping the container and bring it back by starting it again — and Docker assigns a fresh
     * host port on every start of a dynamically published container. A moving endpoint would mean
     * the connection string a running service holds pointed at nothing even after the broker
     * returned, which is a different outage from the one spec SC-004 describes and would prove the
     * wrong thing.
     */
    private static final int HOST_PORT = freeHostPort();

    private static final Duration BROKER_RETURNS_WITHIN = Duration.ofSeconds(120);

    private static final Network NETWORK = Network.newNetwork();

    private static boolean started;

    private static final MSSQLServerContainer MSSQL = new MSSQLServerContainer(MSSQL_IMAGE)
            .acceptLicense()
            .withNetwork(NETWORK);

    private static final ServiceBusEmulatorContainer EMULATOR =
            new ServiceBusEmulatorContainer(EMULATOR_IMAGE)
                    .acceptLicense()
                    .withNetwork(NETWORK)
                    .withConfig(MountableFile.forHostPath(CONFIG_PATH))
                    .withMsSqlServerContainer(MSSQL)
                    .withCreateContainerCmdModifier(command -> command.getHostConfig()
                            .withPortBindings(new PortBinding(
                                    Ports.Binding.bindPort(HOST_PORT),
                                    new ExposedPort(AMQP_PORT))));

    private ServiceBusEmulatorTestSupport() {
        // Static fixture holder.
    }

    /**
     * Returns the shared emulator, starting it and its companion if this is the first call.
     */
    public static synchronized ServiceBusEmulatorContainer container() {
        // Started once per JVM and remembered, rather than asked whether it is running. A suite
        // that has deliberately stopped the broker would otherwise see "not running" here and have
        // Testcontainers build a second one underneath it, at a different endpoint, mid-outage.
        if (!started) {
            MSSQL.start();
            EMULATOR.start();
            started = true;
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
     * Takes the broker away, the way a broker actually goes away.
     *
     * <p>The store-outage suites freeze Postgres, and that works because the driver notices a
     * severed connection the next time it uses one. The broker does not behave like that: a frozen
     * emulator leaves the AMQP connection open and silent, and an idle consumer — one with no
     * message in flight — sits there indefinitely without ever being told anything is wrong. Five
     * minutes of it were measured, and the client never reported a fault, which is not a defect:
     * absence of traffic is not evidence of an outage, and research §8 says so deliberately.
     *
     * <p>So the container is stopped. Connections close, the consumer is told at once, and the
     * scenario is the one operations staff meet — a broker that went away, not a broker that went
     * quiet. The endpoint survives because the host port is pinned.
     */
    public static void disconnect() {
        container().getDockerClient()
                .stopContainerCmd(container().getContainerId())
                .withTimeout(0)
                .exec();
    }

    /**
     * Brings the broker back at the same endpoint, and waits until it is really answering.
     *
     * <p>Waiting on a real round trip rather than on the container's state: "started" is when
     * Docker has run the process, and the emulator spends several seconds after that reaching its
     * own state store before it will accept an AMQP connection. A suite that began asserting in
     * between would be measuring the emulator's start-up, not this service's recovery.
     *
     * <p>Restarting loses whatever was on the queue; the emulator has no restart persistence
     * (research §10). Every suite that stops the broker publishes what it needs afterwards.
     */
    public static void reconnect() {
        container().getDockerClient()
                .startContainerCmd(container().getContainerId())
                .exec();
        await().atMost(BROKER_RETURNS_WITHIN)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(ServiceBusEmulatorTestSupport::assertBrokerAnswers);
    }

    /**
     * Brings the broker back if a suite left it stopped, whether or not it did.
     *
     * <p>Idempotent, because the outage suites restore from an {@code @AfterEach} so that a failing
     * assertion cannot leave the rest of the build without a broker.
     */
    public static void restore() {
        if (!running()) {
            reconnect();
        }
    }

    /**
     * One attempt at a real round trip, as a retrying assertion.
     *
     * <p>The failure is wrapped and rethrown rather than ignored: while the budget lasts it is why
     * this attempt failed, and when the budget runs out it is the cause hanging off the timeout —
     * the difference between "the broker never came back" and a fixture that says only that it
     * waited (constitution Principle VI, which does not exempt tests).
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    // The SDK reports an unreachable broker as any of several types; what matters here is that the
    // round trip did not happen, whichever type carried that news.
    private static void assertBrokerAnswers() {
        try (ServiceBusReceiverClient receiver = new ServiceBusClientBuilder()
                .connectionString(connectionString())
                .receiver()
                .queueName(QUEUE_NAME)
                .buildClient()) {
            receiver.peekMessage();
        } catch (RuntimeException unreachable) {
            throw new AssertionError("the restarted broker did not answer", unreachable);
        }
    }

    private static boolean running() {
        return Boolean.TRUE.equals(container().getDockerClient()
                .inspectContainerCmd(container().getContainerId())
                .exec()
                .getState()
                .getRunning());
    }

    /**
     * A host port nothing is using, claimed by opening and closing a socket on it.
     */
    private static int freeHostPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException unavailable) {
            throw new UncheckedIOException("no free host port for the broker emulator", unavailable);
        }
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
