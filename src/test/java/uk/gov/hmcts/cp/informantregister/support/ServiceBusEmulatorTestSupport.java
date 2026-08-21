package uk.gov.hmcts.cp.informantregister.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.models.SubQueue;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import org.testcontainers.azure.ServiceBusEmulatorContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
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

    /** What the emulator is called on the shared network, so the proxy can reach it. */
    private static final String EMULATOR_ALIAS = "servicebus";

    /**
     * The proxy sitting between every client in this build and the broker.
     *
     * <p>Everything connects through it — the service under test and the suites' own senders and
     * receivers alike — so that an outage can be injected at the transport rather than staged by
     * doing something violent to a container. The plan's test matrix names Toxiproxy as the
     * recorded fallback for exactly this, and it is adopted here as that note.
     */
    private static final String TOXIPROXY_IMAGE = "ghcr.io/shopify/toxiproxy:2.12.0";

    private static final int TOXIPROXY_CONTROL_PORT = 8474;
    private static final int TOXIPROXY_LISTEN_PORT = 8666;
    private static final String PROXY_NAME = "servicebus";

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
                    .withNetworkAliases(EMULATOR_ALIAS);

    private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(TOXIPROXY_IMAGE)
            .withNetwork(NETWORK)
            .withExposedPorts(TOXIPROXY_CONTROL_PORT, TOXIPROXY_LISTEN_PORT);

    private static Proxy brokerProxy;

    private ServiceBusEmulatorTestSupport() {
        // Static fixture holder.
    }

    /**
     * Returns the shared emulator, starting it and its companion if this is the first call.
     */
    public static synchronized ServiceBusEmulatorContainer container() {
        // Started once per JVM and remembered, rather than asked whether it is running.
        if (!started) {
            MSSQL.start();
            EMULATOR.start();
            TOXIPROXY.start();
            brokerProxy = createProxy();
            started = true;
        }
        return EMULATOR;
    }

    private static Proxy createProxy() {
        try {
            return new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort())
                    .createProxy(PROXY_NAME,
                            "0.0.0.0:" + TOXIPROXY_LISTEN_PORT,
                            EMULATOR_ALIAS + ':' + AMQP_PORT);
        } catch (IOException unreachable) {
            throw new UncheckedIOException("could not put a proxy in front of the broker", unreachable);
        }
    }

    private static synchronized Proxy proxy() {
        container();
        return brokerProxy;
    }

    /**
     * The emulator connection string, ending {@code UseDevelopmentEmulator=true;}.
     */
    public static String connectionString() {
        container();
        return "Endpoint=sb://" + TOXIPROXY.getHost() + ':'
                + TOXIPROXY.getMappedPort(TOXIPROXY_LISTEN_PORT)
                + ";SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=SAS_KEY_VALUE;"
                + "UseDevelopmentEmulator=true;";
    }

    /**
     * Takes the broker away, at the transport, in a way a test can time.
     *
     * <p>The obvious ways do not work, and both of their failures are silent. <strong>Freezing</strong>
     * the container leaves the AMQP connection open and mute: an idle consumer sits there
     * indefinitely without being told anything is wrong — five minutes of it were measured, with no
     * callback of any kind — which is not a defect, because absence of traffic is not evidence of an
     * outage and research §8 says so deliberately. <strong>Stopping</strong> it does sever the
     * connections, but only a settlement <em>already in progress</em> then fails; one started
     * afterwards blocks, because the client is retrying a connection it cannot make. The window
     * between those two is milliseconds wide, so a suite that published a message and stopped a
     * container was betting on timing and could lose in either direction.
     *
     * <p>Disabling the proxy closes what is open and refuses what comes next, immediately and for
     * as long as the test wants. A settlement started after the cut fails at once instead of
     * hanging, so a suite can hold a delivery, cut the transport, and only then let the delivery
     * finish — which is a sequence rather than a race. The plan's test matrix names Toxiproxy as the
     * recorded fallback for exactly this situation; this is that note.
     */
    public static void disconnect() {
        try {
            proxy().disable();
        } catch (IOException unreachable) {
            throw new UncheckedIOException("could not cut the broker connection", unreachable);
        }
    }

    /**
     * Puts the broker back, and waits until it is really answering.
     *
     * <p>Waiting on a real round trip rather than on the proxy's own state: the proxy accepts
     * connections again the instant it is enabled, and the emulator behind it is untouched, but the
     * client has its own reconnection to complete and a suite that began asserting in between would
     * be measuring nothing.
     */
    public static void reconnect() {
        try {
            proxy().enable();
        } catch (IOException unreachable) {
            throw new UncheckedIOException("could not restore the broker connection", unreachable);
        }
        await().atMost(BROKER_RETURNS_WITHIN)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(ServiceBusEmulatorTestSupport::assertBrokerAnswers);
    }

    /**
     * Puts the broker back if a suite left it cut, whether or not it did.
     *
     * <p>Idempotent, because the outage suites restore from an {@code @AfterEach} so that a failing
     * assertion cannot leave the rest of the build without a broker.
     */
    public static void restore() {
        if (!proxy().isEnabled()) {
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
            throw new AssertionError("the broker did not answer through the proxy", unreachable);
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
