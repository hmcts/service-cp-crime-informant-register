package uk.gov.hmcts.cp.informantregister.config;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the configuration surface to the plan's table, and holds startup to the rules that make the
 * service safe to run.
 *
 * <p>The two timing relationships and the credential rule are checked at startup precisely because
 * they fail quietly otherwise: a run that outlives its claim shows up as a duplicate submission
 * weeks later, and a silently preferred credential source is how a deployed pod ends up talking to
 * the wrong broker.
 *
 * <p>The plan's Spring-level rows — datasource, Flyway, server and management — are not asserted
 * here. They arrive with `application.yaml` and are proven by the context boot and the HTTP-surface
 * and readiness suites.
 */
class ConfigurationValidationTest {

    /** The emulator connection string, the local and CI credential. */
    private static final String CONNECTION_STRING =
            "Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;"
                    + "SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;";

    private static final String NAMESPACE = "informantregister.servicebus.windows.net";

    private static final String CONNECTION_STRING_PROPERTY =
            "informantregister.servicebus.connection-string=" + CONNECTION_STRING;

    private static final String NAMESPACE_PROPERTY =
            "informantregister.servicebus.namespace=" + NAMESPACE;

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(PropertiesTestConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(InformantRegisterProperties.class)
    @Import(PropertiesValidator.class)
    static class PropertiesTestConfiguration {
    }

    @Nested
    @DisplayName("binding")
    class Binding {

        @Test
        void every_setting_should_fall_back_to_the_documented_default() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY).run(context -> {
                assertThat(context).hasNotFailed();
                final InformantRegisterProperties properties =
                        context.getBean(InformantRegisterProperties.class);

                assertThat(properties.consumer().enabled()).isTrue();

                assertThat(properties.servicebus().connectionString()).isEqualTo(CONNECTION_STRING);
                assertThat(properties.servicebus().namespace()).isNull();
                assertThat(properties.servicebus().queueName()).isEqualTo("informantregister.requests");
                assertThat(properties.servicebus().maxConcurrentCalls()).isEqualTo(2);
                assertThat(properties.servicebus().maxDeliveryCount()).isEqualTo(5);
                assertThat(properties.servicebus().maxAutoLockRenewDuration())
                        .isEqualTo(Duration.ofMinutes(5));
                assertThat(properties.servicebus().healthStaleness()).isEqualTo(Duration.ofSeconds(60));

                assertThat(properties.claim().lease()).isEqualTo(Duration.ofMinutes(5));
                assertThat(properties.claim().processingDeadline()).isEqualTo(Duration.ofMinutes(4));

                assertThat(properties.store().probeInterval()).isEqualTo(Duration.ofSeconds(10));

                assertThat(properties.stub().payloadFailureMode()).isEqualTo(PayloadFailureMode.NONE);
            });
        }

        @Test
        void every_setting_should_be_overridable() {
            runner.withPropertyValues(
                    NAMESPACE_PROPERTY,
                    "informantregister.consumer.enabled=false",
                    "informantregister.servicebus.queue-name=other.requests",
                    "informantregister.servicebus.max-concurrent-calls=8",
                    "informantregister.servicebus.max-delivery-count=3",
                    "informantregister.servicebus.max-auto-lock-renew-duration=9m",
                    "informantregister.servicebus.health-staleness=90s",
                    "informantregister.claim.lease=8m",
                    "informantregister.claim.processing-deadline=7m",
                    "informantregister.store.probe-interval=45s",
                    "informantregister.stub.payload-failure-mode=TRANSIENT").run(context -> {
                        assertThat(context).hasNotFailed();
                        final InformantRegisterProperties properties =
                                context.getBean(InformantRegisterProperties.class);

                        assertThat(properties.consumer().enabled()).isFalse();

                        assertThat(properties.servicebus().connectionString()).isNull();
                        assertThat(properties.servicebus().namespace()).isEqualTo(NAMESPACE);
                        assertThat(properties.servicebus().queueName()).isEqualTo("other.requests");
                        assertThat(properties.servicebus().maxConcurrentCalls()).isEqualTo(8);
                        assertThat(properties.servicebus().maxDeliveryCount()).isEqualTo(3);
                        assertThat(properties.servicebus().maxAutoLockRenewDuration())
                                .isEqualTo(Duration.ofMinutes(9));
                        assertThat(properties.servicebus().healthStaleness())
                                .isEqualTo(Duration.ofSeconds(90));

                        assertThat(properties.claim().lease()).isEqualTo(Duration.ofMinutes(8));
                        assertThat(properties.claim().processingDeadline())
                                .isEqualTo(Duration.ofMinutes(7));

                        assertThat(properties.store().probeInterval()).isEqualTo(Duration.ofSeconds(45));

                        assertThat(properties.stub().payloadFailureMode())
                                .isEqualTo(PayloadFailureMode.TRANSIENT);
                    });
        }
    }

    @Nested
    @DisplayName("the run must finish before the claim can be reclaimed")
    class ProcessingDeadlineAgainstLease {

        @Test
        void a_deadline_equal_to_the_lease_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.claim.lease=5m",
                    "informantregister.claim.processing-deadline=5m").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("informantregister.claim.processing-deadline")
                                .hasMessageContaining("informantregister.claim.lease");
                    });
        }

        @Test
        void a_deadline_longer_than_the_lease_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.claim.lease=5m",
                    "informantregister.claim.processing-deadline=6m").run(context ->
                            assertThat(context).hasFailed());
        }

        @Test
        void a_deadline_shorter_than_the_lease_should_start() {
            // The renewal is raised alongside the deadline so this case tests one rule only: at
            // 4m59s the default 5m renewal would break the lock rule, which has its own cases below.
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.claim.lease=5m",
                    "informantregister.claim.processing-deadline=PT4M59S",
                    "informantregister.servicebus.max-auto-lock-renew-duration=PT5M29S").run(context ->
                            assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("the broker lock must outlive any legitimate run")
    class LockRenewalAgainstDeadline {

        @Test
        void a_renewal_shorter_than_the_deadline_plus_the_margin_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.claim.lease=5m",
                    "informantregister.claim.processing-deadline=4m",
                    "informantregister.servicebus.max-auto-lock-renew-duration=PT4M29S").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(
                                        "informantregister.servicebus.max-auto-lock-renew-duration")
                                .hasMessageContaining("informantregister.claim.processing-deadline");
                    });
        }

        @Test
        void a_renewal_exactly_the_deadline_plus_the_margin_should_start() {
            // The margin is a fixed 30 seconds, so this is the boundary the rule allows.
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.claim.lease=5m",
                    "informantregister.claim.processing-deadline=4m",
                    "informantregister.servicebus.max-auto-lock-renew-duration=PT4M30S").run(context ->
                            assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("exactly one credential source")
    class CredentialSelection {

        @Test
        void a_connection_string_alone_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY)
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        void a_namespace_alone_should_start() {
            runner.withPropertyValues(NAMESPACE_PROPERTY)
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        void both_credential_sources_should_fail_startup_with_a_clear_message() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, NAMESPACE_PROPERTY).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasMessageContaining("informantregister.servicebus.connection-string")
                        .hasMessageContaining("informantregister.servicebus.namespace")
                        .hasMessageContaining("exactly one");
            });
        }

        @Test
        void neither_credential_source_should_fail_startup_with_a_clear_message() {
            runner.run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasMessageContaining("informantregister.servicebus.connection-string")
                        .hasMessageContaining("informantregister.servicebus.namespace")
                        .hasMessageContaining("exactly one");
            });
        }

        @Test
        void a_blank_connection_string_should_count_as_unset() {
            // A deployed environment overrides the local default with an empty value rather than
            // deleting the key, so blank must mean absent or every deployment would fail as
            // "both set".
            runner.withPropertyValues("informantregister.servicebus.connection-string=",
                    NAMESPACE_PROPERTY).run(context -> assertThat(context).hasNotFailed());
        }
    }

    /**
     * The retry policy must be capable of making the call it exists to make.
     *
     * <p>These settings fail in the same quiet way the timing rules do. A {@code max-attempts} of
     * zero attempts no POST at all: the loop that would send the register never runs, every hearing
     * comes back transient, and the queue fills with deliveries that were never even tried — the
     * silent non-delivery this service exists to remove, wearing a retry policy's clothes. A
     * negative wait reaches {@code Thread.sleep} and throws from inside the retry, and a ceiling
     * below the first wait is a bound that shortens the very back-off it is meant to bound.
     */
    @Nested
    @DisplayName("the retry policy must be able to make the call")
    class RetryPolicy {

        @Test
        void no_attempts_at_all_should_fail_startup_rather_than_post_nothing() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.results.max-attempts=0").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("informantregister.results.max-attempts");
                    });
        }

        @Test
        void a_negative_attempt_count_should_fail_startup_the_same_way() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.results.max-attempts=-1").run(context ->
                            assertThat(context).hasFailed());
        }

        @Test
        void a_single_attempt_should_start_because_no_retry_is_a_policy_too() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.results.max-attempts=1").run(context ->
                            assertThat(context).hasNotFailed());
        }

        @Test
        void a_negative_initial_backoff_should_fail_startup_rather_than_throw_mid_retry() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.results.initial-backoff=-1s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("informantregister.results.initial-backoff");
                    });
        }

        @Test
        void a_ceiling_below_the_first_wait_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.results.initial-backoff=10s",
                    "informantregister.results.max-backoff=5s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("informantregister.results.max-backoff")
                                .hasMessageContaining("informantregister.results.initial-backoff");
                    });
        }

        @Test
        void the_shipped_defaults_should_satisfy_their_own_rules() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY)
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    /**
     * What the shipped {@code application.yaml} actually binds.
     *
     * <p>Asserted against the real file rather than against property values a test invents, because
     * the failure this covers is a documented environment variable that reaches nothing. A comment
     * naming {@code RESULTS_SYSTEM_USER_ID} is not a binding, and a deployment that sets it and
     * still fails to start with "system-user-id is required" is a deployment nobody can debug from
     * the configuration in front of them.
     */
    @Nested
    @DisplayName("the shipped application.yaml")
    class ShippedConfiguration {

        private final ApplicationContextRunner shipped = runner
                .withInitializer(new ConfigDataApplicationContextInitializer());

        @Test
        void the_identity_should_arrive_from_the_environment_variable_the_file_documents() {
            shipped.withSystemProperties("RESULTS_SYSTEM_USER_ID=b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(InformantRegisterProperties.class)
                                .results().systemUserId())
                                .isEqualTo("b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234");
                    });
        }

        @Test
        void the_endpoint_should_arrive_from_the_environment_variable_the_file_documents() {
            shipped.withSystemProperties("RESULTS_BASE_URL=http://results.internal:8080")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(InformantRegisterProperties.class)
                                .results().baseUrl())
                                .isEqualTo("http://results.internal:8080");
                    });
        }

        @Test
        void an_unset_identity_should_stay_unset_so_a_local_run_borrows_nobodys() {
            shipped.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(InformantRegisterProperties.class)
                        .results().systemUserId())
                        .as("absent is absent; the gateway refuses to start on it, which is the point")
                        .isNullOrEmpty();
            });
        }
    }
}
