package uk.gov.hmcts.cp.informantregister.support;

import org.testcontainers.containers.GenericContainer;

/**
 * Shared Redis fixture for the payload-cache suite.
 *
 * <p>Same shape as {@link PostgresTestSupport}, and for the same reasons: one container per JVM,
 * started on first use, left to the Ryuk reaper at exit. A static holder rather than
 * {@code @Testcontainers} because the suites here decide for themselves when a dependency exists.
 *
 * <p>A real server rather than an embedded fake or a mocked client. What is worth testing about a
 * cache read is that the key this service builds finds the value the producer wrote, and a mock
 * answers that question by agreeing with whatever the test already assumed.
 *
 * <p>Plain TCP, no TLS. Deployed connections are TLS with certificates verified (registered
 * deviation 1), but a container with a self-signed certificate would only prove that the suite can
 * be told to trust one — which is the opposite of the property that matters.
 */
public final class RedisTestSupport {

    private static final String IMAGE = "redis:7-alpine";
    private static final int PORT = 6379;

    private static final GenericContainer<?> CONTAINER =
            new GenericContainer<>(IMAGE).withExposedPorts(PORT);

    private RedisTestSupport() {
        // Static fixture holder.
    }

    /**
     * Returns the shared container, starting it if this is the first call.
     */
    public static GenericContainer<?> container() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
        return CONTAINER;
    }

    /**
     * The URI a client should connect to the shared container on.
     */
    public static String uri() {
        return "redis://" + host() + ':' + port();
    }

    /**
     * The address the shared container answers on, for a suite that configures the service rather
     * than building a client of its own.
     */
    public static String host() {
        return container().getHost();
    }

    /**
     * The port the shared container answers on.
     */
    public static int port() {
        return container().getMappedPort(PORT);
    }
}
