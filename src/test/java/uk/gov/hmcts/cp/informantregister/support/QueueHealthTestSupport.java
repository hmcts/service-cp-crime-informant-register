package uk.gov.hmcts.cp.informantregister.support;

import java.time.Clock;
import java.time.Duration;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.config.ServiceBusHealthIndicator;

/**
 * A queue-health indicator for the suites that have to pass one and have nothing to say about it.
 *
 * <p>The listener reports a refused settlement to the indicator, so every suite that builds a
 * listener needs one — including the settlement suites, whose subject is which broker call was
 * made rather than what the broker's health looks like afterwards. Naming that fact here keeps
 * those suites reading as though the collaborator were not there, which is the truth about them.
 */
public final class QueueHealthTestSupport {

    /** The shipped default, so a suite that does look at it sees the deployed behaviour. */
    private static final Duration STALENESS = Duration.ofSeconds(60);

    private QueueHealthTestSupport() {
        // Static fixture holder.
    }

    /**
     * An indicator wired to instruments nothing reads and a real clock.
     */
    public static ServiceBusHealthIndicator unwatched() {
        return new ServiceBusHealthIndicator(
                STALENESS, new ProcessingMetrics(new SimpleMeterRegistry()), Clock.systemUTC());
    }
}
