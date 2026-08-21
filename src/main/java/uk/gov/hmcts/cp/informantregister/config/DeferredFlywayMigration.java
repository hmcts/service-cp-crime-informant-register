package uk.gov.hmcts.cp.informantregister.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Takes the migration off the context-refresh path — and does nothing else (research §7).
 *
 * <p>By default Flyway migrates while the context is refreshing, which means a pod cannot start
 * without its database. That is the wrong trade for this service: the one thing a pod must be able
 * to do when the store is down is <strong>start and say so</strong>. A context that refuses to
 * refresh cannot serve a readiness endpoint, so it cannot report DOWN, so all anybody sees is a
 * crash loop with the reason buried in the logs of a container that no longer exists. Retry
 * settings only postpone that.
 *
 * <p>So the Flyway bean is still configured — {@code spring.flyway.enabled} stays true, the
 * migrations are still validated and still run — but <em>when</em> they run becomes a decision the
 * consumer lifecycle controller makes: on the first successful store probe, before the processor is
 * started. That ordering is the point. Migration success is part of the probe-gated start, so there
 * is no window in which a message could be consumed against a schema that does not exist yet.
 *
 * <p>This strategy is therefore deliberately, loudly empty. It is not a way of skipping migrations;
 * it is a way of moving them somewhere a failure can be reported.
 */
@Configuration(proxyBeanMethods = false)
public class DeferredFlywayMigration {

    private static final Logger LOG = LoggerFactory.getLogger(DeferredFlywayMigration.class);

    /**
     * The strategy that declines to migrate here, so the controller can migrate there.
     */
    @Bean
    public FlywayMigrationStrategy deferredFlywayMigrationStrategy() {
        return flyway -> LOG.info(
                "Schema migration deferred off context refresh; the consumer lifecycle controller "
                        + "runs it on the first successful store probe, before intake starts.");
    }
}
