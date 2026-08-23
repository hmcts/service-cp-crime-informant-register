package uk.gov.hmcts.cp.informantregister.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import uk.gov.hmcts.cp.informantregister.adapter.stub.RefusingNowSubscriptionsSource;
import uk.gov.hmcts.cp.informantregister.application.NowSubscriptionsSource;

/**
 * The now-subscriptions source that asks nobody, kept for local runs and for the suites that need no
 * reference-data server.
 *
 * <p>The container suites whose subject is settlement, the processed log and health have no interest
 * in who a register is addressed to, and standing an HTTP stub up for them would make what they prove
 * depend on infrastructure their scenarios never mention. They select this instead.
 *
 * <p>Never the default, and not selectable where the service is deployed. It contributes a bean only
 * when {@code informantregister.referencedata.mode} says {@code STUB}, so an environment that says
 * nothing gets the real adapter — and {@link PropertiesValidator} refuses {@code STUB} outright
 * wherever the deployed credential source is in use, because a pod that can never address a register
 * is a pod that parks every hearing carrying one (constitution Principle V).
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "informantregister.referencedata", name = "mode",
        havingValue = "STUB")
public class StubSubscriptionsConfig {

    /**
     * The subscriptions port, served by the refusal.
     *
     * @return the port
     */
    @Bean
    public NowSubscriptionsSource nowSubscriptionsSource() {
        return new RefusingNowSubscriptionsSource();
    }
}
