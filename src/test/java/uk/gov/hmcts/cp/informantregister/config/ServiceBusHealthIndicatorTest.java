package uk.gov.hmcts.cp.informantregister.config;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import uk.gov.hmcts.cp.informantregister.support.AdjustableClock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reachability rule, exercised at its edges with a clock the test moves.
 *
 * <p>The scenario that earns this suite its place is the one the aging rule must never absolve: a
 * consumer that recorded a connection fault and has <em>never once</em> been answered. The SDK rolls
 * its pump silently when the broker is gone, so no second fault will arrive to refresh the first —
 * the fault simply grows old. Aging out a fault is the right answer only for a consumer the broker
 * has answered before, where an idle queue explains the silence; for one it has never answered, the
 * silence <em>is</em> the outage, and the component must keep saying so until first contact.
 */
class ServiceBusHealthIndicatorTest {

    private static final Duration STALENESS = Duration.ofSeconds(60);
    private static final Instant STARTED = Instant.parse("2026-08-21T04:00:00Z");

    private final AdjustableClock clock = AdjustableClock.startingAt(STARTED);
    private final ServiceBusHealthIndicator indicator = new ServiceBusHealthIndicator(
            STALENESS, new ProcessingMetrics(new SimpleMeterRegistry()), clock);

    private void aConnectionFaultIsRecorded() {
        indicator.recordProcessorError(
                "RECEIVE", "informantregister.requests", new IOException("connection reset"));
    }

    private Status status() {
        return indicator.health().getStatus();
    }

    @Nested
    @DisplayName("a consumer the broker has never answered")
    class NeverAnswered {

        @Test
        void should_stay_down_after_a_fault_older_than_the_staleness_window() {
            indicator.recordIntakeStarted();
            aConnectionFaultIsRecorded();

            clock.advance(STALENESS.plusSeconds(1));

            assertThat(status())
                    .as("a fault nothing has answered does not age into health: with no traffic "
                            + "ever, old silence is still silence")
                    .isEqualTo(Status.DOWN);
            assertThat(indicator.reachableNow())
                    .as("and the gauge answers the same question the same way")
                    .isFalse();
        }

        @Test
        void should_report_a_fresh_fault_at_once_with_no_startup_grace() {
            indicator.recordIntakeStarted();
            aConnectionFaultIsRecorded();

            assertThat(status())
                    .as("grace is the benefit of the doubt for a silence that carries no evidence; "
                            + "a recorded connection fault is evidence, and a wrong DOWN costs one "
                            + "health cycle where a wrong UP hides the outage")
                    .isEqualTo(Status.DOWN);
        }

        @Test
        void should_recover_on_first_contact() {
            indicator.recordIntakeStarted();
            aConnectionFaultIsRecorded();
            clock.advance(STALENESS.plusSeconds(1));

            indicator.recordTraffic();

            assertThat(status())
                    .as("any answer at all ends the outage")
                    .isEqualTo(Status.UP);
        }
    }

    @Nested
    @DisplayName("a consumer the broker has answered before")
    class AnsweredBefore {

        @Test
        void should_age_out_a_fault_nothing_has_repeated_when_the_queue_is_merely_idle() {
            indicator.recordIntakeStarted();
            indicator.recordTraffic();
            clock.advance(Duration.ofMinutes(5));
            aConnectionFaultIsRecorded();

            clock.advance(STALENESS.plusSeconds(1));

            assertThat(status())
                    .as("no traffic since the fault is the normal state of a healthy idle queue, "
                            + "because this consumer has been answered before")
                    .isEqualTo(Status.UP);
        }

        @Test
        void should_report_a_fresh_fault_until_traffic_answers_it() {
            indicator.recordIntakeStarted();
            indicator.recordTraffic();
            clock.advance(Duration.ofMinutes(5));
            aConnectionFaultIsRecorded();

            clock.advance(STALENESS.minusSeconds(1));

            assertThat(status()).isEqualTo(Status.DOWN);
        }

        @Test
        void should_treat_traffic_after_the_fault_as_the_answer_to_it() {
            indicator.recordIntakeStarted();
            aConnectionFaultIsRecorded();
            clock.advance(Duration.ofSeconds(5));

            indicator.recordTraffic();

            assertThat(status()).isEqualTo(Status.UP);
        }
    }

    @Nested
    @DisplayName("with no fault recorded")
    class NoFault {

        @Test
        void should_give_a_starting_consumer_one_grace_window_and_then_report_the_silence() {
            indicator.recordIntakeStarted();

            clock.advance(STALENESS.plusSeconds(1));

            assertThat(status())
                    .as("a consumer that has never once been answered says so after one window")
                    .isEqualTo(Status.DOWN);
        }

        @Test
        void should_stay_up_while_the_startup_grace_lasts() {
            indicator.recordIntakeStarted();

            clock.advance(STALENESS.minusSeconds(1));

            assertThat(status()).isEqualTo(Status.UP);
        }

        @Test
        void should_hold_no_opinion_before_intake_has_started() {
            clock.advance(Duration.ofHours(1));

            assertThat(status())
                    .as("a pod gated on its store has not asked the broker anything yet")
                    .isEqualTo(Status.UP);
        }
    }
}
