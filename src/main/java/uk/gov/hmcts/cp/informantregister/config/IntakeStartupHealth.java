package uk.gov.hmcts.cp.informantregister.config;

import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import uk.gov.hmcts.cp.informantregister.inbound.ConsumerLifecycleController;

/**
 * Registers the gated start's own readiness contribution.
 *
 * <p>Deliberately not inside the consumer's configuration, which is switched off with
 * {@code informantregister.consumer.enabled}. Spring validates health-group membership at startup,
 * so a readiness group naming a contributor that a property had removed would fail the context with
 * a message about health groups rather than about the setting somebody changed. The contributor
 * therefore always exists and asks for the controller rather than requiring one.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class IntakeStartupHealth {

    /**
     * Named so that Spring's contributor naming yields {@code intakeStartup} — the name the
     * readiness group, a probe and a runbook all use.
     */
    @Bean
    public IntakeStartupHealthIndicator intakeStartupHealthIndicator(
            final ObjectProvider<ConsumerLifecycleController> lifecycle) {
        return new IntakeStartupHealthIndicator(
                Optional.ofNullable(lifecycle.getIfAvailable()));
    }
}
