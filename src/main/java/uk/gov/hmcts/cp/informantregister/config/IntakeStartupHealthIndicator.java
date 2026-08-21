package uk.gov.hmcts.cp.informantregister.config;

import java.util.Optional;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import uk.gov.hmcts.cp.informantregister.inbound.ConsumerLifecycleController;

/**
 * Has this pod actually started working yet?
 *
 * <p>A readiness group containing only the store's own contributor answers a narrower question than
 * readiness is asked: it says the database replies, not that this service is in a position to use
 * it. Between those two there is a whole start-up — the deferred migration, and then the processor
 * — and both can fail. A pod whose migration is throwing reports the database UP, is therefore
 * ready, is sent traffic by the platform, counts as a healthy replica in a rolling deployment, and
 * consumes nothing at all. The deployment completes; the queue quietly grows.
 *
 * <p>So the gated start reports on itself. It stays DOWN until the migration has run and the
 * processor has been started, and thereafter says nothing: a later store outage is the store's
 * contributor to report, and intake suspension is not an unreadiness — the pod is working correctly
 * and waiting, and rolling it would help nobody.
 *
 * <p>Where no consumer is configured at all there is no start to wait for, and the answer is UP.
 * That is not a loophole: a service with intake switched off has nothing to be unready about, and
 * failing the group's membership check instead would be an obscure way to say so.
 */
public class IntakeStartupHealthIndicator implements HealthIndicator {

    private static final String NO_CONSUMER = "no-consumer-configured";
    private static final String STARTED = "started";
    private static final String AWAITING_STORE = "awaiting-store";

    private final Optional<ConsumerLifecycleController> lifecycle;

    public IntakeStartupHealthIndicator(final Optional<ConsumerLifecycleController> lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    public Health health() {
        return lifecycle
                .map(controller -> controller.intakeStarted()
                        ? Health.up().withDetail("intake", STARTED).build()
                        : Health.down().withDetail("intake", AWAITING_STORE).build())
                .orElseGet(() -> Health.up().withDetail("intake", NO_CONSUMER).build());
    }
}
