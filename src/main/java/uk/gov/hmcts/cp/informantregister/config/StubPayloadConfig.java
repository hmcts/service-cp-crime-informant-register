package uk.gov.hmcts.cp.informantregister.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The payload source that fetches nothing, kept for local runs and for the suites that do not need
 * one.
 *
 * <p>The container suites whose subject is settlement, the processed log and health have no interest
 * in a hearing payload, and standing a cache and an HTTP stub up for them would make what they prove
 * depend on infrastructure their scenarios never mention. They select this instead.
 *
 * <p>Never the default. It contributes a bean only when {@code informantregister.payload.mode} says
 * {@code STUB}, so a deployed environment that says nothing gets the real adapter.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "informantregister.payload", name = "mode", havingValue = "STUB")
public class StubPayloadConfig {
}
