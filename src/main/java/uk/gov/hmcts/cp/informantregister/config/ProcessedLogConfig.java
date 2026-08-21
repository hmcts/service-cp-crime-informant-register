package uk.gov.hmcts.cp.informantregister.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import uk.gov.hmcts.cp.informantregister.application.IdempotencyGuard;
import uk.gov.hmcts.cp.informantregister.persistence.ProcessedLogProbe;
import uk.gov.hmcts.cp.informantregister.persistence.ProcessedRequestRepository;

/**
 * The processed log and the guard over it.
 *
 * <p>Registered here rather than annotated as components, so the guard and its repository stay
 * plain constructor-injected objects that a unit test builds in one line. The persistence suites
 * already do exactly that, against a Testcontainers store and no Spring at all.
 *
 * <p>Excluded from the {@code test} profile because everything in it needs a {@code DataSource},
 * and that profile deliberately has none: the plain context-load tests must keep running with no
 * broker, no database and therefore no Docker (plan §Test profile).
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class ProcessedLogConfig {

    /**
     * The repository binds the claim lease once, because that is the only setting its statements
     * need — the expiry it produces is computed by the database, not here.
     */
    @Bean
    public ProcessedRequestRepository processedRequestRepository(
            final JdbcClient jdbcClient, final InformantRegisterProperties properties) {
        return new ProcessedRequestRepository(jdbcClient, properties.claim().lease());
    }

    /**
     * The availability question the consumer lifecycle controller and every delivery both ask.
     *
     * <p>A bean of its own rather than a method on the repository: the repository's statements are
     * the state machine, and "can this database be reached at all" is a different question asked at
     * a different moment — before a delivery is examined, and on a schedule while intake is stopped.
     */
    @Bean
    public ProcessedLogProbe processedLogProbe(final JdbcClient jdbcClient) {
        return new ProcessedLogProbe(jdbcClient);
    }

    @Bean
    public IdempotencyGuard idempotencyGuard(
            final ProcessedRequestRepository repository, final ProcessingMetrics metrics) {
        return new IdempotencyGuard(repository, metrics);
    }
}
