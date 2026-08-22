package uk.gov.hmcts.cp.informantregister.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The real payload source: the cache, and the query side behind it.
 *
 * <p>Selected by {@code informantregister.payload.mode}, and selected by default — a service that
 * has to be told to fetch payloads is a service that will one day be deployed not fetching them.
 * {@link StubPayloadConfig} is the other half of the pair, and exactly one of the two contributes a
 * bean.
 *
 * <p>Excluded from the {@code test} profile alongside the rest of the pipeline wiring, which that
 * profile has no store for.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "informantregister.payload", name = "mode", havingValue = "LIVE",
        matchIfMissing = true)
public class LivePayloadConfig {
}
