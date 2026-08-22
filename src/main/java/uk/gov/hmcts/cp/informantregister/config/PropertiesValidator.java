package uk.gov.hmcts.cp.informantregister.config;

import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Refuses to let the application start on a configuration that cannot be operated safely.
 *
 * <p>Everything checked here fails quietly in production and loudly at startup, so startup is where
 * it is made to fail: a run that can outlive its claim, a broker lock that can expire mid-run, an
 * ambiguous credential source, a payload source that cannot fetch anything, and a payload fetch
 * whose own worst case outlasts the run it happens inside.
 *
 * <p>The payload rules are the ones a healthy-looking pod hides. A live source with no identity, a
 * fallback with no attempts and a cache with no address all produce a service that consumes
 * normally, settles nothing usefully, and dead-letters every request it is given — while readiness,
 * liveness and the queue's own metrics say the deployment succeeded.
 */
@Component
// The properties record is registered here, explicitly, rather than left to a scan: without it the
// packaged application starts no context at all ("No qualifying bean of type
// InformantRegisterProperties"), which the container smoke found and no JUnit suite did.
@EnableConfigurationProperties(InformantRegisterProperties.class)
public class PropertiesValidator implements InitializingBean {

    /**
     * The fixed margin between the longest legitimate run and the broker's lock renewal, so the lock
     * is never the thing that ends a run.
     */
    public static final Duration RENEWAL_MARGIN = Duration.ofSeconds(30);

    /**
     * How many cache reads one payload fetch makes, and therefore how many of them the run's time
     * budget has to cover.
     *
     * <p>Two: the dated key and the legacy undated twin, which
     * {@link uk.gov.hmcts.cp.informantregister.adapter.payload.CachedHearingPayloadAdapter} reads in
     * turn before the query side is asked at all (registered deviation 4). A budget that counts only
     * the query side licences a fetch that overruns the deadline by everything the cache cost.
     */
    private static final int CACHE_READS_PER_FETCH = 2;

    private static final String LEASE = "informantregister.claim.lease";
    private static final String PROCESSING_DEADLINE = "informantregister.claim.processing-deadline";
    private static final String RENEW_DURATION =
            "informantregister.servicebus.max-auto-lock-renew-duration";
    private static final String CONNECTION_STRING =
            "informantregister.servicebus.connection-string";
    private static final String NAMESPACE = "informantregister.servicebus.namespace";
    private static final String PAYLOAD_MODE = "informantregister.payload.mode";
    private static final String SYSTEM_USER_ID = "informantregister.system-user-id";
    private static final String FALLBACK = "informantregister.payload.fallback";
    private static final String MAX_ATTEMPTS = FALLBACK + ".max-attempts";
    private static final String RETRY_INTERVAL = FALLBACK + ".retry-interval";
    private static final String REDIS = "informantregister.payload.redis";

    private final InformantRegisterProperties properties;

    /** Creates the validator over the bound properties. */
    public PropertiesValidator(final InformantRegisterProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        validate(properties);
    }

    /**
     * Checks the settings that must hold for the service to be safe to run.
     *
     * @param properties the bound settings
     * @throws IllegalStateException if any rule is broken
     */
    public static void validate(final InformantRegisterProperties properties) {
        validateRunFinishesBeforeTheClaimExpires(properties);
        validateLockOutlivesTheRun(properties);
        validateExactlyOneCredentialSource(properties);
        validateThePayloadSourceCanFetch(properties);
    }

    private static void validateRunFinishesBeforeTheClaimExpires(
            final InformantRegisterProperties properties) {
        final Duration deadline = properties.claim().processingDeadline();
        final Duration lease = properties.claim().lease();
        if (deadline.compareTo(lease) >= 0) {
            throw new IllegalStateException(
                    PROCESSING_DEADLINE + " (" + deadline + ") must be strictly shorter than " + LEASE
                            + " (" + lease + "), so a slow run stops before its claim can be reclaimed");
        }
    }

    private static void validateLockOutlivesTheRun(final InformantRegisterProperties properties) {
        final Duration deadline = properties.claim().processingDeadline();
        final Duration renewal = properties.servicebus().maxAutoLockRenewDuration();
        final Duration required = deadline.plus(RENEWAL_MARGIN);
        if (renewal.compareTo(required) < 0) {
            throw new IllegalStateException(
                    RENEW_DURATION + " (" + renewal + ") must be at least " + PROCESSING_DEADLINE
                            + " plus the " + RENEWAL_MARGIN + " renewal margin (" + required
                            + "), so the broker lock outlives any legitimate run");
        }
    }

    private static void validateExactlyOneCredentialSource(
            final InformantRegisterProperties properties) {
        final boolean hasConnectionString = hasText(properties.servicebus().connectionString());
        final boolean hasNamespace = hasText(properties.servicebus().namespace());
        if (hasConnectionString == hasNamespace) {
            throw new IllegalStateException(
                    "Set exactly one of " + CONNECTION_STRING + " (local and CI) or " + NAMESPACE
                            + " (deployed) — currently "
                            + (hasConnectionString ? "both are set" : "neither is set"));
        }
    }

    /**
     * The payload source must be one that can actually produce a payload.
     *
     * <p>Three separate ways a deployment can look healthy and fetch nothing: the stub selected
     * where the service is deployed, a live source with no identity to authorise its fallback with,
     * and a cache or fallback configured out of existence.
     *
     * <p>Each rule is asked of the source actually selected. The cache and the query side belong to
     * the live adapter, and STUB builds neither of them ({@link LivePayloadConfig},
     * {@link StubPayloadConfig}), so holding a stub run to settings nothing will read would fail a
     * local run that is configured exactly as it means to be — the same reason the identity is the
     * live source's requirement and nobody else's.
     */
    private static void validateThePayloadSourceCanFetch(
            final InformantRegisterProperties properties) {
        final InformantRegisterProperties.Payload payload = properties.payload();
        if (payload.mode() == PayloadSourceMode.STUB) {
            validateTheStubIsNotDeployed(properties);
        } else {
            validateTheLiveSourceHasAnIdentity(properties);
            validateTheCacheIsAddressable(payload.redis());
            validateTheFallbackIsAttempted(payload.fallback());
            validateTheFetchFinishesInsideTheRun(properties);
        }
    }

    /**
     * Constitution Principle V: the stub must not be reachable in a production profile now that the
     * real adapter has landed. A namespace means workload identity, which means a deployed pod —
     * the same discriminator the credential rule above already draws deployment on.
     */
    private static void validateTheStubIsNotDeployed(
            final InformantRegisterProperties properties) {
        if (hasText(properties.servicebus().namespace())) {
            throw new IllegalStateException(
                    PAYLOAD_MODE + " is STUB while " + NAMESPACE + " is set, which is a deployed"
                            + " environment — the stub fetches nothing, so every request would be"
                            + " settled having produced no register at all");
        }
    }

    /**
     * The query-side fallback authorises with the system user identity, and without one it cannot be
     * used: every cold-cache request would be abandoned, redelivered and dead-lettered by a pod
     * reporting itself healthy throughout.
     */
    private static void validateTheLiveSourceHasAnIdentity(
            final InformantRegisterProperties properties) {
        if (!hasText(properties.systemUserId())) {
            throw new IllegalStateException(
                    SYSTEM_USER_ID + " must be set when " + PAYLOAD_MODE + " is LIVE, because the"
                            + " payload fallback cannot be used without an identity to authorise"
                            + " with");
        }
    }

    private static void validateTheCacheIsAddressable(
            final InformantRegisterProperties.Redis redis) {
        if (!hasText(redis.host())) {
            throw new IllegalStateException(REDIS + ".host must name the payload cache");
        }
        if (!hasText(redis.keyPrefix())) {
            throw new IllegalStateException(
                    REDIS + ".key-prefix must be the prefix the producer writes the payload under,"
                            + " INT_ for this flow — an empty prefix reads a key nobody writes");
        }
        requirePositive(redis.connectTimeout(), REDIS + ".connect-timeout");
        requirePositive(redis.commandTimeout(), REDIS + ".command-timeout");
    }

    private static void validateTheFallbackIsAttempted(
            final InformantRegisterProperties.Fallback fallback) {
        if (fallback.maxAttempts() < 1) {
            throw new IllegalStateException(
                    MAX_ATTEMPTS + " (" + fallback.maxAttempts() + ") must be at least 1 — at zero"
                            + " every cache miss skips a query side that could have answered, and"
                            + " the request is retried to the dead-letter queue instead");
        }
        if (fallback.retryInterval().isNegative()) {
            throw new IllegalStateException(
                    RETRY_INTERVAL + " (" + fallback.retryInterval() + ") must not be negative");
        }
        requirePositive(fallback.connectTimeout(), FALLBACK + ".connect-timeout");
        requirePositive(fallback.readTimeout(), FALLBACK + ".read-timeout");
    }

    /**
     * The fetch happens inside the run, and the run must finish before its claim can be reclaimed.
     *
     * <p>So the whole fetch's worst case has to fit inside the processing deadline: the two cache
     * reads that come first, each able to spend its connect and command timeouts, and then every
     * fallback attempt spending its connect and read timeouts with an interval between them. A
     * configuration where it does not guarantees exactly what the deadline exists to prevent: a
     * runner still waiting on a socket while another runner takes its request.
     *
     * <p>Strictly shorter, not merely no longer. The run measures the deadline before the fetch and
     * tests it after, so a fetch that fills the deadline exactly leaves the rest of the run nothing
     * and can only end at {@code PROCESSING_DEADLINE_EXCEEDED} — the same reading of the bound that
     * {@code DistributionPipeline} takes, and that the lease rule above takes.
     */
    private static void validateTheFetchFinishesInsideTheRun(
            final InformantRegisterProperties properties) {
        final InformantRegisterProperties.Redis redis = properties.payload().redis();
        final InformantRegisterProperties.Fallback fallback = properties.payload().fallback();
        final Duration deadline = properties.claim().processingDeadline();
        final Duration cacheReads = redis.connectTimeout()
                .plus(redis.commandTimeout())
                .multipliedBy(CACHE_READS_PER_FETCH);
        final Duration queryReads = fallback.connectTimeout()
                .plus(fallback.readTimeout())
                .multipliedBy(fallback.maxAttempts())
                .plus(fallback.retryInterval().multipliedBy(fallback.maxAttempts() - 1L));
        final Duration worstCase = cacheReads.plus(queryReads);
        if (worstCase.compareTo(deadline) >= 0) {
            throw new IllegalStateException(
                    "The payload settings allow a fetch of up to " + worstCase + " — " + REDIS
                            + " reads of " + cacheReads + " ahead of " + FALLBACK + " attempts of "
                            + queryReads + " — which is not strictly shorter than "
                            + PROCESSING_DEADLINE + " (" + deadline + "); a run must be able to stop"
                            + " itself while its claim is still its own");
        }
    }

    private static void requirePositive(final Duration value, final String setting) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalStateException(
                    setting + " (" + value + ") must be positive — a timeout that never expires is"
                            + " a run that never ends");
        }
    }

    /**
     * A blank value counts as unset: a deployed environment overrides the local connection string
     * with an empty value rather than deleting the key, and treating that as "set" would fail every
     * deployment as ambiguous.
     */
    private static boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }
}
