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

    /**
     * A lock duration above every lease the cases below use, so each timing case tests one rule
     * only. Without it the packaged 5m lock makes a 5m lease break the lease rule as well, and a
     * case meant to prove one relationship would pass on another's refusal.
     */
    private static final String ROOMY_LOCK_DURATION =
            "informantregister.servicebus.lock-duration=6m";

    /**
     * The identity the query-side fallback authorises with. Carried by every case here that is not
     * about it, because the live payload source cannot work without one and startup says so.
     */
    private static final String IDENTITY_PROPERTY =
            "informantregister.results.system-user-id=9f61bdbb-6f1a-4c0f-9a3d-6b8f0f1c2a44";

    /**
     * The endpoint and identity the live now-subscriptions source needs. Carried by every case here
     * that is not about them, for the same reason {@link #IDENTITY_PROPERTY} is: the live source is
     * the default, and startup refuses one that cannot ask reference data anything.
     */
    private static final String REFDATA_ENDPOINT_PROPERTY =
            "informantregister.referencedata.base-url=http://localhost:8080";

    private static final String REFDATA_IDENTITY_PROPERTY =
            "informantregister.referencedata.system-user-id=2c7b1e64-0f4a-4f0e-9b2c-8d1a6f3e5c07";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(PropertiesTestConfiguration.class)
                    .withPropertyValues(IDENTITY_PROPERTY, REFDATA_ENDPOINT_PROPERTY,
                            REFDATA_IDENTITY_PROPERTY);

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
                assertThat(properties.servicebus().lockDuration()).isEqualTo(Duration.ofMinutes(5));
                assertThat(properties.servicebus().maxAutoLockRenewDuration())
                        .isEqualTo(Duration.ofMinutes(5));
                assertThat(properties.servicebus().healthStaleness()).isEqualTo(Duration.ofSeconds(60));

                assertThat(properties.claim().lease())
                        .isEqualTo(Duration.ofMinutes(4).plusSeconds(30));
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
                    "informantregister.servicebus.lock-duration=9m",
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
                        assertThat(properties.servicebus().lockDuration())
                                .isEqualTo(Duration.ofMinutes(9));
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
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, ROOMY_LOCK_DURATION,
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
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, ROOMY_LOCK_DURATION,
                    "informantregister.claim.lease=5m",
                    "informantregister.claim.processing-deadline=6m").run(context ->
                            assertThat(context).hasFailed());
        }

        @Test
        void a_deadline_shorter_than_the_lease_should_start() {
            // The renewal is raised alongside the deadline so this case tests one rule only: at
            // 4m59s the default 5m renewal would break the lock rule, which has its own cases below.
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, ROOMY_LOCK_DURATION,
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
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, ROOMY_LOCK_DURATION,
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
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY, ROOMY_LOCK_DURATION,
                    "informantregister.claim.lease=5m",
                    "informantregister.claim.processing-deadline=4m",
                    "informantregister.servicebus.max-auto-lock-renew-duration=PT4M30S").run(context ->
                            assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("the claim must lapse before the broker redelivers")
    class LeaseAgainstLockDuration {

        @Test
        void a_lease_equal_to_the_lock_duration_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.servicebus.lock-duration=5m",
                    "informantregister.claim.lease=5m").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("informantregister.claim.lease")
                                .hasMessageContaining("informantregister.servicebus.lock-duration");
                    });
        }

        @Test
        void a_lease_longer_than_the_lock_duration_should_fail_startup() {
            // The case the design names: a dead runner's claim outlives the lock, so the redelivery
            // finds it live, abandons with CLAIM_NOT_ACQUIRED and — abandon having no back-off —
            // burns the delivery budget back-to-back into a broker-reasoned dead-letter.
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.servicebus.lock-duration=1m",
                    "informantregister.claim.lease=5m").run(context ->
                            assertThat(context).hasFailed());
        }

        @Test
        void a_lease_shorter_than_the_lock_duration_should_start() {
            // The packaged values: 30s of margin between the claim lapsing and the redelivery.
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.servicebus.lock-duration=5m",
                    "informantregister.claim.lease=PT4M30S").run(context ->
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
                    "informantregister.results.system-user-id=",
                    "informantregister.payload.mode=LIVE").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("informantregister.results.system-user-id")
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
                    "informantregister.results.system-user-id=",
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
    @DisplayName("the live now-subscriptions source must be able to ask reference data")
    class SubscriptionsReachability {

        /**
         * The hole the payload story left open on its own live mode, closed here for this one.
         * Without an endpoint the client has nowhere to send the query, so every hearing that
         * produced a register is abandoned, redelivered and finally parked — by a pod whose
         * readiness, liveness and queue metrics all say the deployment succeeded.
         */
        @Test
        void live_mode_without_an_endpoint_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.referencedata.base-url=").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("informantregister.referencedata.base-url")
                                .hasMessageContaining("informantregister.referencedata.mode");
                    });
        }

        /**
         * {@code CJSCPPUID} is part of the reference-data query's own contract
         * ({@code ReferenceDataService.js:44}) and its access-control rules authorise on it, so an
         * anonymous query is a refused query — every time, for ever.
         */
        @Test
        void live_mode_without_an_identity_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.referencedata.system-user-id=").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(
                                        "informantregister.referencedata.system-user-id")
                                .hasMessageContaining("informantregister.referencedata.mode");
                    });
        }

        @Test
        void live_mode_with_an_endpoint_and_an_identity_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.referencedata.mode=LIVE")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /** Both are the live source's requirement and nobody else's; the stub asks nobody. */
        @Test
        void stub_mode_without_an_endpoint_or_an_identity_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.referencedata.mode=STUB",
                    "informantregister.referencedata.base-url=",
                    "informantregister.referencedata.system-user-id=")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * Constitution Principle V, the same rule the payload stub is held to. The refusing stub
         * fails loudly rather than quietly, but a deployed pod running it can never address a
         * register at all: every hearing that produces one is parked, for ever.
         */
        @Test
        void stub_mode_on_the_deployed_credential_source_should_fail_startup() {
            runner.withPropertyValues(NAMESPACE_PROPERTY,
                    "informantregister.referencedata.mode=STUB").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("informantregister.referencedata.mode")
                                .hasMessageContaining("informantregister.servicebus.namespace");
                    });
        }

        @Test
        void a_source_with_no_attempts_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.referencedata.max-attempts=0").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("informantregister.referencedata.max-attempts");
                    });
        }

        @Test
        void a_negative_wait_between_attempts_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.referencedata.retry-interval=-1s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining(
                                        "informantregister.referencedata.retry-interval");
                    });
        }

        @Test
        void a_timeout_that_never_expires_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.referencedata.read-timeout=0s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("informantregister.referencedata.read-timeout");
                    });
        }

        /**
         * Every timeout here is positive and every attempt count is at least one, and the read can
         * still outlast the run: ten attempts against a minute-long read is over ten minutes of
         * waiting that startup would otherwise accept. The claim becomes reclaimable long before
         * that, so another delivery starts processing the request while this runner is still
         * blocked on the socket — the outcome the processing deadline exists to prevent, reached by
         * a configuration each individual rule calls valid.
         */
        @Test
        void a_read_that_can_outlast_the_processing_deadline_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.referencedata.max-attempts=10",
                    "informantregister.referencedata.read-timeout=1m").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("informantregister.referencedata")
                                .hasMessageContaining(
                                        "informantregister.claim.processing-deadline");
                    });
        }

        /**
         * The waits between attempts count too: they are spent inside the same run as the reads.
         * The shipped three attempts of 5s + 30s is 105s, comfortably inside the 4m deadline —
         * until the two waits between them are lengthened, which no other rule looks at.
         *
         * <p>The deadline is left at its default here, and in the two cases below, because the
         * payload rule is checked first and its own worst case has to keep fitting: shortening the
         * deadline would fail these on the payload's message rather than on reference data's.
         */
        @Test
        void the_waits_between_attempts_should_count_towards_the_deadline() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    // 105s of reads, and two 70s waits between the three attempts, is 245s.
                    "informantregister.referencedata.retry-interval=70s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("informantregister.referencedata")
                                .hasMessageContaining(
                                        "informantregister.claim.processing-deadline");
                    });
        }

        /**
         * The same reading of the bound the payload rule takes: a read that fills the deadline
         * exactly leaves the rest of the run nothing, because the run only tests the deadline once
         * the read has returned.
         */
        @Test
        void a_read_that_exactly_fills_the_deadline_should_fail_startup() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.referencedata.max-attempts=2",
                    "informantregister.referencedata.retry-interval=10s",
                    // Two attempts of 5s + 110s, with a 10s wait between them, is the 4m deadline
                    // to the second.
                    "informantregister.referencedata.read-timeout=110s").run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasMessageContaining("informantregister.referencedata")
                                .hasMessageContaining(
                                        "informantregister.claim.processing-deadline");
                    });
        }

        @Test
        void a_read_that_finishes_one_second_inside_the_deadline_should_start() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.referencedata.max-attempts=2",
                    "informantregister.referencedata.retry-interval=10s",
                    "informantregister.referencedata.read-timeout=PT109.5S")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        /**
         * The timing rule belongs to the live adapter, which STUB does not build — the same reason
         * the endpoint and the identity are not asked of a stub run.
         */
        @Test
        void stub_mode_should_not_be_held_to_the_live_read_timings() {
            runner.withPropertyValues(CONNECTION_STRING_PROPERTY,
                    "informantregister.referencedata.mode=STUB",
                    "informantregister.referencedata.max-attempts=10",
                    "informantregister.referencedata.read-timeout=1m")
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
     * naming {@code INFORMANT_REGISTER_SYSTEM_USER_ID} is not a binding, and a deployment that sets it and
     * still fails to start with "system-user-id is required" is a deployment nobody can debug from
     * the configuration in front of them.
     */
    @Nested
    @DisplayName("the shipped application.yaml")
    class ShippedConfiguration {

        /**
         * Deliberately not built from {@code runner}: that one carries {@link #IDENTITY_PROPERTY} so
         * the cases about something else are not refused startup by the live payload source's
         * identity rule, and a property value set on the runner outranks the file. A test about what
         * the file binds has to let the file be the only thing that binds it.
         *
         * <p>The one property supplied is the broker connection string, because the shipped file
         * deliberately carries no credential at all ({@code PackagedDefaultsTest}) and the
         * exactly-one-source rule would otherwise refuse startup before any case here reached its
         * subject — the same reason {@code shippedOnTheStub} takes the payload-identity rule out of
         * the way. No case in this nest asserts the connection-string binding, so outranking the
         * file on that one value costs nothing.
         */
        private final ApplicationContextRunner shipped = new ApplicationContextRunner()
                .withUserConfiguration(PropertiesTestConfiguration.class)
                .withPropertyValues("informantregister.servicebus.connection-string="
                        + "Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;"
                        + "SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;")
                .withInitializer(new ConfigDataApplicationContextInitializer());

        /**
         * The subject here is what the file binds, not which payload source is selected. The shipped
         * file ships {@code payload.mode: LIVE}, and a live source without an identity is refused
         * startup by design ({@link PayloadIdentity}); selecting the stub takes that rule out of the
         * way of a test that is about the binding of a value.
         */
        private final ApplicationContextRunner shippedOnTheStub = shipped
                .withPropertyValues("informantregister.payload.mode=STUB",
                        "informantregister.referencedata.mode=STUB");

        @Test
        void the_identity_should_arrive_from_the_environment_variable_the_file_documents() {
            shipped.withSystemProperties("INFORMANT_REGISTER_SYSTEM_USER_ID=b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(InformantRegisterProperties.class)
                                .results().systemUserId())
                                .isEqualTo("b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234");
                    });
        }

        @Test
        void the_endpoint_should_arrive_from_the_environment_variable_the_file_documents() {
            shippedOnTheStub.withSystemProperties("RESULTS_BASE_URL=http://results.internal:8080")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(InformantRegisterProperties.class)
                                .results().baseUrl())
                                .isEqualTo("http://results.internal:8080");
                    });
        }

        @Test
        void the_reference_data_endpoint_should_arrive_from_the_variable_the_file_documents() {
            shippedOnTheStub
                    .withSystemProperties("REFERENCEDATA_BASE_URL=http://referencedata.internal:8080")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(InformantRegisterProperties.class)
                                .referencedata().baseUrl())
                                .isEqualTo("http://referencedata.internal:8080");
                    });
        }

        /**
         * One identity, because the function app has one: {@code input.cjscppuid} authorises the
         * payload read and the now-subscriptions read alike ({@code ReferenceDataService.js:44}).
         * An environment that mounts the Results identity is therefore not asked for a second one,
         * which is what keeps this change out of the deployment's way.
         */
        @Test
        void the_reference_data_identity_should_fall_back_to_the_one_the_results_calls_use() {
            shippedOnTheStub
                    .withSystemProperties("INFORMANT_REGISTER_SYSTEM_USER_ID=b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(InformantRegisterProperties.class)
                                .referencedata().systemUserId())
                                .isEqualTo("b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234");
                    });
        }

        @Test
        void the_reference_data_identity_should_be_settable_on_its_own() {
            shippedOnTheStub.withSystemProperties(
                    "INFORMANT_REGISTER_SYSTEM_USER_ID=b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234",
                    "REFERENCEDATA_SYSTEM_USER_ID=2c7b1e64-0f4a-4f0e-9b2c-8d1a6f3e5c07")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(InformantRegisterProperties.class)
                                .referencedata().systemUserId())
                                .isEqualTo("2c7b1e64-0f4a-4f0e-9b2c-8d1a6f3e5c07");
                    });
        }

        @Test
        void an_unset_identity_should_stay_unset_so_a_local_run_borrows_nobodys() {
            shippedOnTheStub.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(InformantRegisterProperties.class)
                        .results().systemUserId())
                        .as("absent is absent; the gateway refuses to start on it, which is the point")
                        .isNullOrEmpty();
            });
        }
    }
}
