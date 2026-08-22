package uk.gov.hmcts.cp.informantregister.config;

import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Refuses to let the application start on a configuration that cannot be operated safely.
 *
 * <p>Two timing relationships, one credential rule and the retry policy are checked here rather than
 * discovered later: a run that can outlive its claim, a broker lock that can expire mid-run, an
 * ambiguous credential source and a policy that cannot make the call it exists to make all fail
 * quietly in production and loudly at startup, so startup is where they are made to fail.
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

    private static final String LEASE = "informantregister.claim.lease";
    private static final String PROCESSING_DEADLINE = "informantregister.claim.processing-deadline";
    private static final String RENEW_DURATION =
            "informantregister.servicebus.max-auto-lock-renew-duration";
    private static final String CONNECTION_STRING =
            "informantregister.servicebus.connection-string";
    private static final String NAMESPACE = "informantregister.servicebus.namespace";
    private static final String MAX_ATTEMPTS = "informantregister.results.max-attempts";
    private static final String INITIAL_BACKOFF = "informantregister.results.initial-backoff";
    private static final String MAX_BACKOFF = "informantregister.results.max-backoff";

    /** The first attempt is the POST itself, so a policy that permits fewer never sends one. */
    private static final int MINIMUM_ATTEMPTS = 1;

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
        validateTheRetryPolicyCanPost(properties);
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
     * The retry policy has to be able to make the call it exists to make.
     *
     * <p>{@code max-attempts} below one is the one that matters: the loop that POSTs the register
     * never runs, every hearing is handed back as an unresolved transient failure, and the queue
     * fills with deliveries that were never attempted — silent non-delivery wearing a retry policy's
     * clothes, and unobservable except as a queue that will not drain. A negative wait reaches
     * {@link Thread#sleep(java.time.Duration)} and throws from inside the retry, and a ceiling below
     * the first wait shortens the very back-off it exists to bound.
     */
    private static void validateTheRetryPolicyCanPost(final InformantRegisterProperties properties) {
        final InformantRegisterProperties.Results results = properties.results();
        if (results.maxAttempts() < MINIMUM_ATTEMPTS) {
            throw new IllegalStateException(
                    MAX_ATTEMPTS + " (" + results.maxAttempts() + ") must be at least "
                            + MINIMUM_ATTEMPTS + ": a policy with no attempts posts no register at "
                            + "all and hands every hearing back unsent");
        }
        if (results.initialBackoff().isNegative()) {
            throw new IllegalStateException(
                    INITIAL_BACKOFF + " (" + results.initialBackoff() + ") must not be negative: a "
                            + "negative wait throws from inside the retry rather than being taken");
        }
        if (results.maxBackoff().compareTo(results.initialBackoff()) < 0) {
            throw new IllegalStateException(
                    MAX_BACKOFF + " (" + results.maxBackoff() + ") must be at least "
                            + INITIAL_BACKOFF + " (" + results.initialBackoff() + "), or the ceiling "
                            + "shortens the very wait it exists to bound");
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
