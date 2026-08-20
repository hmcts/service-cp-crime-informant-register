package uk.gov.hmcts.cp.informantregister.config;

import java.time.Duration;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Refuses to let the application start on a configuration that cannot be operated safely.
 *
 * <p>Two timing relationships and one credential rule are checked here rather than discovered later:
 * a run that can outlive its claim, a broker lock that can expire mid-run, and an ambiguous
 * credential source all fail quietly in production and loudly at startup, so startup is where they
 * are made to fail.
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

    private final InformantRegisterProperties properties;

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
     * A blank value counts as unset: a deployed environment overrides the local connection string
     * with an empty value rather than deleting the key, and treating that as "set" would fail every
     * deployment as ambiguous.
     */
    private static boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }
}
