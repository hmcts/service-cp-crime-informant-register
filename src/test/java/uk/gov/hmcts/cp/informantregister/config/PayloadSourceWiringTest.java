package uk.gov.hmcts.cp.informantregister.config;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import uk.gov.hmcts.cp.informantregister.adapter.payload.CachedHearingPayloadAdapter;
import uk.gov.hmcts.cp.informantregister.adapter.stub.StubHearingPayloadSource;
import uk.gov.hmcts.cp.informantregister.application.HearingPayloadSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which adapter is actually behind the payload port, and what it is configured with.
 *
 * <p>The port's whole claim is that replacing the adapter behind it costs nothing elsewhere. That
 * claim is only worth anything if the replacement genuinely happened, and "the class exists" does not
 * establish it — a deployed pod running the stub would look healthy, settle every message and
 * produce nothing, which is the precise failure this service was written to end.
 *
 * <p>The default is asserted separately from the override, because the direction of the default is
 * the safety property: a service that has to be told to fetch payloads is a service that will one
 * day be deployed not fetching them.
 */
class PayloadSourceWiringTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(InformantRegisterProperties.class)
    @Import({JacksonConfig.class, LivePayloadConfig.class, StubPayloadConfig.class})
    static class PayloadTestConfiguration {
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(PayloadTestConfiguration.class);

    @Nested
    @DisplayName("adapter selection")
    class Selection {

        @Test
        void the_payload_port_should_be_served_by_the_real_adapter_when_nothing_says_otherwise() {
            runner.run(context -> assertThat(context)
                    .getBean(HearingPayloadSource.class)
                    .isInstanceOf(CachedHearingPayloadAdapter.class));
        }

        @Test
        void the_payload_port_should_be_served_by_the_real_adapter_in_live_mode() {
            runner.withPropertyValues("informantregister.payload.mode=LIVE")
                    .run(context -> assertThat(context)
                            .getBean(HearingPayloadSource.class)
                            .isInstanceOf(CachedHearingPayloadAdapter.class));
        }

        @Test
        void the_payload_port_should_be_served_by_the_stub_when_stub_mode_is_asked_for() {
            runner.withPropertyValues("informantregister.payload.mode=STUB")
                    .run(context -> assertThat(context)
                            .getBean(HearingPayloadSource.class)
                            .isInstanceOf(StubHearingPayloadSource.class));
        }

        /**
         * Two adapters behind one port would be an ambiguous injection point and a startup failure,
         * which is the good outcome; asserting it here means the pair stays mutually exclusive if
         * either condition is ever edited.
         */
        @Test
        void exactly_one_adapter_should_serve_the_payload_port() {
            runner.run(context ->
                    assertThat(context.getBeanNamesForType(HearingPayloadSource.class)).hasSize(1));
        }
    }

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        void every_payload_setting_should_fall_back_to_the_documented_default() {
            runner.run(context -> {
                final InformantRegisterProperties.Payload payload =
                        context.getBean(InformantRegisterProperties.class).payload();

                assertThat(payload.mode()).isEqualTo(PayloadSourceMode.LIVE);
                assertThat(payload.redis().host()).isEqualTo("localhost");
                assertThat(payload.redis().port()).isEqualTo(6379);
                assertThat(payload.redis().password()).isNull();
                assertThat(payload.redis().ssl()).isFalse();
                assertThat(payload.redis().keyPrefix()).isEqualTo("INT_");
                assertThat(payload.redis().connectTimeout()).isEqualTo(Duration.ofSeconds(5));
                assertThat(payload.redis().commandTimeout()).isEqualTo(Duration.ofSeconds(5));
                assertThat(payload.fallback().maxAttempts()).isEqualTo(3);
                assertThat(payload.fallback().retryInterval()).isEqualTo(Duration.ofSeconds(1));
                assertThat(payload.fallback().connectTimeout()).isEqualTo(Duration.ofSeconds(5));
                assertThat(payload.fallback().readTimeout()).isEqualTo(Duration.ofSeconds(30));
            });
        }

        @Test
        void the_results_base_url_should_fall_back_to_the_local_development_default() {
            runner.run(context -> assertThat(
                    context.getBean(InformantRegisterProperties.class).results().baseUrl())
                    .isEqualTo("http://localhost:8080"));
        }

        /**
         * The system user identity is an identity mounted from Key Vault, so it has no default for
         * the same reason the broker credentials have none: a service that invents one talks to a
         * downstream context as somebody it is not.
         */
        @Test
        void the_system_user_identity_should_have_no_default() {
            runner.run(context -> assertThat(
                    context.getBean(InformantRegisterProperties.class).systemUserId()).isNull());
        }

        @Test
        void the_payload_settings_should_bind_from_configuration() {
            runner.withPropertyValues(
                            "informantregister.payload.redis.host=cache.internal",
                            "informantregister.payload.redis.port=6380",
                            "informantregister.payload.redis.ssl=true",
                            "informantregister.payload.redis.key-prefix=INT_",
                            "informantregister.payload.fallback.max-attempts=5",
                            "informantregister.results.base-url=http://results.internal",
                            "informantregister.system-user-id=a-system-user")
                    .run(context -> {
                        final InformantRegisterProperties properties =
                                context.getBean(InformantRegisterProperties.class);

                        assertThat(properties.payload().redis().host()).isEqualTo("cache.internal");
                        assertThat(properties.payload().redis().port()).isEqualTo(6380);
                        assertThat(properties.payload().redis().ssl()).isTrue();
                        assertThat(properties.payload().fallback().maxAttempts()).isEqualTo(5);
                        assertThat(properties.results().baseUrl())
                                .isEqualTo("http://results.internal");
                        assertThat(properties.systemUserId()).isEqualTo("a-system-user");
                    });
        }
    }
}
