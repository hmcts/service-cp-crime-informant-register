package uk.gov.hmcts.cp.informantregister.config;

import java.time.Clock;
import java.time.Duration;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Whether the broker is reachable — reported, never used to gate readiness.
 *
 * <p>Seam only at this point: the rule it will implement is described in research §8, and
 * {@code ReadinessPolicyIT} asserts it. Until the implementation task it answers UNKNOWN, which is
 * the honest answer for a component that has not been written and is what makes the suite's
 * assertions fail on the assertion rather than on a missing class.
 */
public class ServiceBusHealthIndicator implements HealthIndicator {

    private final Duration staleness;
    private final ProcessingMetrics metrics;
    private final Clock clock;

    public ServiceBusHealthIndicator(
            final Duration staleness, final ProcessingMetrics metrics, final Clock clock) {
        this.staleness = staleness;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Records a transport fault the processor reported outside a delivery.
     *
     * @param failure what the processor reported
     */
    public void recordProcessorError(final Throwable failure) {
        // Implementation follows in T040.
    }

    /**
     * Records that the broker answered: a receive or a settlement succeeded.
     */
    public void recordTraffic() {
        // Implementation follows in T040.
    }

    @Override
    public Health health() {
        return Health.unknown()
                .withDetail("stalenessWindow", staleness.toString())
                .withDetail("observedAt", clock.instant().toString())
                .withDetail("instruments", metrics.getClass().getSimpleName())
                .build();
    }
}
