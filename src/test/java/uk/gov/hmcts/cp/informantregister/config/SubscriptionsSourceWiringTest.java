package uk.gov.hmcts.cp.informantregister.config;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import uk.gov.hmcts.cp.informantregister.adapter.refdata.ReferenceDataNowSubscriptionsClient;
import uk.gov.hmcts.cp.informantregister.adapter.stub.RefusingNowSubscriptionsSource;
import uk.gov.hmcts.cp.informantregister.application.NowSubscriptionsSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which adapter is actually behind the subscriptions port, and what it is configured with.
 *
 * <p>The port's claim is the same one the payload port makes: replacing the adapter behind it costs
 * nothing elsewhere. "The class exists" does not establish that the replacement happened, and here
 * the wrong answer is loud rather than silent — a deployed pod still on the refusing stub would park
 * every hearing that produced a register — so the direction of the default is asserted separately.
 *
 * <p>The stub stays reachable deliberately, for local runs and for the container suites whose
 * subject is settlement and the processed log rather than the register's recipients.
 */
class SubscriptionsSourceWiringTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(InformantRegisterProperties.class)
    @Import({JacksonConfig.class, LiveSubscriptionsConfig.class, StubSubscriptionsConfig.class})
    static class SubscriptionsTestConfiguration {
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                    .withUserConfiguration(SubscriptionsTestConfiguration.class);

    @Nested
    @DisplayName("adapter selection")
    class Selection {

        @Test
        void the_subscriptions_port_should_be_served_by_the_real_adapter_when_nothing_says_otherwise() {
            runner.run(context -> assertThat(context)
                    .getBean(NowSubscriptionsSource.class)
                    .isInstanceOf(ReferenceDataNowSubscriptionsClient.class));
        }

        @Test
        void the_subscriptions_port_should_be_served_by_the_real_adapter_in_live_mode() {
            runner.withPropertyValues("informantregister.referencedata.mode=LIVE")
                    .run(context -> assertThat(context)
                            .getBean(NowSubscriptionsSource.class)
                            .isInstanceOf(ReferenceDataNowSubscriptionsClient.class));
        }

        @Test
        void the_subscriptions_port_should_be_served_by_the_stub_when_stub_mode_is_asked_for() {
            runner.withPropertyValues("informantregister.referencedata.mode=STUB")
                    .run(context -> assertThat(context)
                            .getBean(NowSubscriptionsSource.class)
                            .isInstanceOf(RefusingNowSubscriptionsSource.class));
        }

        /**
         * Two adapters behind one port would be an ambiguous injection point and a startup failure,
         * which is the good outcome; asserting it here means the pair stays mutually exclusive if
         * either condition is ever edited.
         */
        @Test
        void exactly_one_adapter_should_serve_the_subscriptions_port() {
            runner.run(context -> assertThat(
                    context.getBeanNamesForType(NowSubscriptionsSource.class)).hasSize(1));
        }
    }

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        void every_reference_data_setting_should_fall_back_to_the_documented_default() {
            runner.run(context -> {
                final InformantRegisterProperties.Referencedata referencedata =
                        context.getBean(InformantRegisterProperties.class).referencedata();

                assertThat(referencedata.mode()).isEqualTo(SubscriptionsSourceMode.LIVE);
                assertThat(referencedata.headers()).isEmpty();
                // AxiosRetryWrapper.js:10-11 — the same wrapper defaults the payload fallback ports.
                assertThat(referencedata.maxAttempts()).isEqualTo(3);
                assertThat(referencedata.retryInterval()).isEqualTo(Duration.ofSeconds(1));
                assertThat(referencedata.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
                assertThat(referencedata.readTimeout()).isEqualTo(Duration.ofSeconds(30));
            });
        }

        /**
         * The endpoint and the identity have no default in code for the reason the Results ones have
         * none: an endpoint a service invents is an endpoint it can talk to by mistake, and an
         * identity is a secret. The local development values live in {@code application.yaml}, where
         * {@code ConfigurationValidationTest.ShippedConfiguration} pins them.
         */
        @Test
        void the_reference_data_endpoint_and_identity_should_have_no_default() {
            runner.run(context -> {
                final InformantRegisterProperties.Referencedata referencedata =
                        context.getBean(InformantRegisterProperties.class).referencedata();

                assertThat(referencedata.baseUrl()).isNull();
                assertThat(referencedata.systemUserId()).isNull();
            });
        }

        @Test
        void every_reference_data_setting_should_be_overridable() {
            runner.withPropertyValues(
                    "informantregister.referencedata.base-url=http://referencedata.internal:8080",
                    "informantregister.referencedata.system-user-id=1b2c3d4e-5f60-4718-8293-a4b5c6d7e8f9",
                    "informantregister.referencedata.headers.X-Mesh-Route=referencedata",
                    "informantregister.referencedata.max-attempts=5",
                    "informantregister.referencedata.retry-interval=250ms",
                    "informantregister.referencedata.connect-timeout=2s",
                    "informantregister.referencedata.read-timeout=7s").run(context -> {
                        final InformantRegisterProperties.Referencedata referencedata =
                                context.getBean(InformantRegisterProperties.class).referencedata();

                        assertThat(referencedata.baseUrl())
                                .isEqualTo("http://referencedata.internal:8080");
                        assertThat(referencedata.systemUserId())
                                .isEqualTo("1b2c3d4e-5f60-4718-8293-a4b5c6d7e8f9");
                        assertThat(referencedata.headers())
                                .isEqualTo(Map.of("X-Mesh-Route", "referencedata"));
                        assertThat(referencedata.maxAttempts()).isEqualTo(5);
                        assertThat(referencedata.retryInterval())
                                .isEqualTo(Duration.ofMillis(250));
                        assertThat(referencedata.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                        assertThat(referencedata.readTimeout()).isEqualTo(Duration.ofSeconds(7));
                    });
        }
    }
}
