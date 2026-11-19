package uk.gov.hmcts.cp.informantregister.config;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The legacy-record replay margin's own startup rule (spec FR-005; FR-015 "a configured margin
 * shorter than the queue's lock duration is rejected").
 *
 * <p>The spec's own assumptions name the floor explicitly: the margin must be at least the queue's
 * lock duration, or a release without a delivery-count increment (which the broker does not count,
 * on a closed connection) inside one lock's lifetime could be read as a resubmission. A
 * configuration that violates its own documented precondition fails loudly at startup, the same
 * rule every other timing constant in {@link PropertiesValidator} is already held to.
 */
class PropertiesValidatorTest {

    private static final String SYSTEM_USER = "cjscppuid-marker";

    /**
     * A fully valid baseline, so each test varies exactly the one setting its name says it does.
     */
    private static InformantRegisterProperties validProperties(
            final Duration lockDuration, final Duration legacyMargin) {
        return new InformantRegisterProperties(
                new InformantRegisterProperties.Consumer(true),
                new InformantRegisterProperties.Servicebus(
                        "Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;"
                                + "SharedAccessKey=key;UseDevelopmentEmulator=true;",
                        null, "informantregister.requests", 2, 5,
                        lockDuration, Duration.ofMinutes(5), Duration.ofSeconds(60)),
                new InformantRegisterProperties.Claim(Duration.ofMinutes(4).plusSeconds(30),
                        Duration.ofMinutes(4)),
                new InformantRegisterProperties.Store(Duration.ofSeconds(10)),
                new InformantRegisterProperties.Stub(PayloadFailureMode.NONE),
                new InformantRegisterProperties.Payload(
                        PayloadSourceMode.STUB,
                        new InformantRegisterProperties.Redis("localhost", 6379, null, false,
                                "INT_", Duration.ofSeconds(5), Duration.ofSeconds(5)),
                        new InformantRegisterProperties.Fallback(3, Duration.ofSeconds(1),
                                Duration.ofSeconds(5), Duration.ofSeconds(30))),
                new InformantRegisterProperties.Results(
                        "http://localhost:8080", SYSTEM_USER, null, 4,
                        Duration.ofMillis(500), Duration.ofSeconds(20), Duration.ofSeconds(5),
                        Duration.ofSeconds(30)),
                new InformantRegisterProperties.Referencedata(
                        SubscriptionsSourceMode.STUB, "http://localhost:8080", SYSTEM_USER,
                        null, 3, Duration.ofSeconds(1), Duration.ofSeconds(5),
                        Duration.ofSeconds(30)),
                new InformantRegisterProperties.Replay(legacyMargin));
    }

    @Test
    @DisplayName("a margin at least as long as the lock duration starts cleanly")
    void a_margin_at_least_the_lock_duration_should_be_accepted() {
        final InformantRegisterProperties properties =
                validProperties(Duration.ofMinutes(5), Duration.ofMinutes(5));

        assertThatCode(() -> PropertiesValidator.validate(properties)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a margin longer than the lock duration starts cleanly")
    void a_margin_longer_than_the_lock_duration_should_be_accepted() {
        final InformantRegisterProperties properties =
                validProperties(Duration.ofMinutes(5), Duration.ofMinutes(10));

        assertThatCode(() -> PropertiesValidator.validate(properties)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a margin shorter than the lock duration is rejected at startup")
    void a_margin_shorter_than_the_lock_duration_should_be_rejected() {
        final InformantRegisterProperties properties =
                validProperties(Duration.ofMinutes(5), Duration.ofMinutes(4));

        assertThatThrownBy(() -> PropertiesValidator.validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("informantregister.replay.legacy-margin")
                .hasMessageContaining("informantregister.servicebus.lock-duration");
    }
}
