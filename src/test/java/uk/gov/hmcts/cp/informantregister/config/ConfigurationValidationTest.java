package uk.gov.hmcts.cp.informantregister.config;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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

    /**
     * The identity the query-side fallback authorises with. Carried by every case here that is not
     * about it, because the live payload source cannot work without one and startup says so.
     */
    private static final String IDENTITY_PROPERTY =
            "informantregister.system-user-id=9f61bdbb-6f1a-4c0f-9a3d-6b8f0f1c2a44";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(PropertiesTestConfiguration.class)
                    .withPropertyValues(IDENTITY_PROPERTY);

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

    @Nested
    @DisplayName("the live payload source needs an identity to fall back with")
    class PayloadIdentity {

        /**
         * Without it the fallback cannot be used at all: the client says so and returns nothing, so
         * every cold-cache request is abandoned, redelivered and finally dead-lettered by a pod that
         * reports itself perfectly healthy throughout. A mount that did not arrive is a deployment
         * fault, and a deployment fault belongs at startup.
         */
        @Test
        void live_mode_without_an_identity_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.system-user-id=",
                    "informantregister.payload.mode=LIVE").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("informantregister.system-user-id")
                                .hasMessageContaining("informantregister.payload.mode");
                    });
        }

        @Test
        void live_mode_with_an_identity_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.payload.mode=LIVE")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * The identity is the live source's requirement and nobody else's. A local run on the stub
         * fetches nothing and so authorises with nobody.
         */
        @Test
        void stub_mode_without_an_identity_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.system-user-id=",
                    "informantregister.payload.mode=STUB")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("the stub payload source is not reachable where the service is deployed")
    class StubReachability {

        /**
         * Constitution Principle V: a stub must not be reachable in a production profile once the
         * real adapter lands, and it has landed. The discriminator is the credential source already
         * used for exactly this distinction — a namespace means workload identity, which means a
         * deployed pod. Such a pod running the stub would settle every message and produce nothing,
         * which is the failure this service exists to end.
         */
        @Test
        void stub_mode_on_the_deployed_credential_source_should_fail_startup() {
            runner.withPropertyValues(NAMESPACE_PROPERTY,
                    "informantregister.payload.mode=STUB").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("informantregister.payload.mode")
                                .hasMessageContaining("informantregister.servicebus.namespace");
                    });
        }

        @Test
        void stub_mode_on_the_local_credential_source_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.payload.mode=STUB")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        void live_mode_on_the_deployed_credential_source_should_start() {
            runner.withPropertyValues(NAMESPACE_PROPERTY,
                    "informantregister.payload.mode=LIVE")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("the payload settings must describe a source that can answer")
    class PayloadReachability {

        @Test
        void a_fallback_with_no_attempts_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.payload.fallback.max-attempts=0").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(
                                        "informantregister.payload.fallback.max-attempts");
                    });
        }

        @Test
        void a_single_attempt_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.payload.fallback.max-attempts=1")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        void a_negative_retry_interval_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.payload.fallback.retry-interval=-1s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(
                                        "informantregister.payload.fallback.retry-interval");
                    });
        }

        @Test
        void a_timeout_that_never_expires_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.payload.redis.command-timeout=0s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(
                                        "informantregister.payload.redis.command-timeout");
                    });
        }

        @Test
        void a_cache_with_no_address_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.payload.redis.host=").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("informantregister.payload.redis.host");
                    });
        }

        @Test
        void a_cache_with_no_key_prefix_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.payload.redis.key-prefix=").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("informantregister.payload.redis.key-prefix");
                    });
        }

        /**
         * The fetch happens inside the run, and the run must stop before its claim can be reclaimed.
         * A fallback whose own worst case outlasts the processing deadline therefore guarantees the
         * thing the deadline exists to prevent: a runner still waiting on a socket while another
         * runner takes its request.
         */
        @Test
        void a_fallback_that_can_outlast_the_processing_deadline_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.claim.processing-deadline=1m",
                    "informantregister.payload.fallback.read-timeout=30s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("informantregister.payload.fallback")
                                .hasMessageContaining(
                                        "informantregister.claim.processing-deadline");
                    });
        }

        @Test
        void a_fallback_that_finishes_inside_the_processing_deadline_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY)
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * The fallback is not the whole fetch. Two cache reads precede it — the dated key and the
         * legacy undated twin, registered deviation 4 — and each of them can spend its connect and
         * command timeouts before the query side is asked at all. A budget that counts only the
         * HTTP half licences a fetch that overruns the deadline by everything the cache cost.
         */
        @Test
        void a_fetch_whose_cache_reads_push_it_past_the_deadline_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.claim.processing-deadline=2m",
                    "informantregister.payload.redis.connect-timeout=5s",
                    "informantregister.payload.redis.command-timeout=5s",
                    "informantregister.payload.fallback.max-attempts=1",
                    "informantregister.payload.fallback.connect-timeout=5s",
                    // 105s of query side alone fits inside 120s; the 20s of cache reads in front of
                    // it does not.
                    "informantregister.payload.fallback.read-timeout=100s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("informantregister.payload")
                                .hasMessageContaining(
                                        "informantregister.claim.processing-deadline");
                    });
        }

        /**
         * A fetch that fills the deadline exactly leaves the rest of the run nothing, and the run
         * only checks the deadline once the fetch has returned. The deadline is a bound that is
         * reached rather than passed — the same reading {@code DistributionPipeline} takes of it,
         * and the same reading the lease rule above takes.
         */
        @Test
        void a_fetch_that_exactly_fills_the_deadline_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.claim.processing-deadline=2m",
                    "informantregister.payload.redis.connect-timeout=5s",
                    "informantregister.payload.redis.command-timeout=5s",
                    "informantregister.payload.fallback.max-attempts=1",
                    "informantregister.payload.fallback.connect-timeout=5s",
                    // 20s of cache reads plus 100s of query side is the deadline to the second.
                    "informantregister.payload.fallback.read-timeout=95s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("informantregister.payload")
                                .hasMessageContaining(
                                        "informantregister.claim.processing-deadline");
                    });
        }

        @Test
        void a_fetch_that_finishes_one_second_inside_the_deadline_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.claim.processing-deadline=2m",
                    "informantregister.payload.redis.connect-timeout=5s",
                    "informantregister.payload.redis.command-timeout=5s",
                    "informantregister.payload.fallback.max-attempts=1",
                    "informantregister.payload.fallback.connect-timeout=5s",
                    "informantregister.payload.fallback.read-timeout=94s")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * The cache and the query side belong to the live source, and STUB selects neither bean.
         * Holding a local stub run to settings nothing will read is the same mistake the identity
         * rule already avoids ({@link PayloadIdentity#stub_mode_without_an_identity_should_start}):
         * it fails a run that is configured exactly as it means to be.
         */
        @Test
        void stub_mode_should_not_be_held_to_the_live_payload_settings() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.payload.mode=STUB",
                    "informantregister.payload.redis.host=",
                    "informantregister.payload.fallback.max-attempts=0")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }
}
