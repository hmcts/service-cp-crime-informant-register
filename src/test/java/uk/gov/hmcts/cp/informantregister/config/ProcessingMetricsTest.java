package uk.gov.hmcts.cp.informantregister.config;

import java.util.List;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.informantregister.domain.FailureClassification;
import uk.gov.hmcts.cp.informantregister.domain.RequestOutcome;
import uk.gov.hmcts.cp.informantregister.domain.SettlementOperation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One case per instrument in the research table: the name, the type, the label set and the condition
 * that moves it.
 *
 * <p>The names and labels are asserted literally because they are a published surface — dashboards
 * and alert rules are written against them, and a rename is a breaking change even though nothing in
 * this repository would notice.
 *
 * <p>Absences are asserted too: no instrument may carry an identifier as a label, and there is no
 * dead-letter depth gauge, because depth is read from the platform's own queue metric rather than
 * polled by service code.
 */
class ProcessingMetricsTest {

    /** Returned when a meter is absent, so a missing instrument fails as an assertion. */
    private static final double ABSENT = -1;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProcessingMetrics metrics = new ProcessingMetrics(registry);

    private double counter(final String name) {
        final Counter counter = registry.find(name).counter();
        return counter == null ? ABSENT : counter.count();
    }

    private double counter(final String name, final String tag, final String value) {
        final Counter counter = registry.find(name).tag(tag, value).counter();
        return counter == null ? ABSENT : counter.count();
    }

    private double gauge(final String name) {
        final Gauge gauge = registry.find(name).gauge();
        return gauge == null ? ABSENT : gauge.value();
    }

    private List<String> tagKeysOf(final String name) {
        final Meter meter = registry.find(name).meter();
        return meter == null
                ? List.of("<meter absent>")
                : meter.getId().getTags().stream().map(io.micrometer.core.instrument.Tag::getKey).toList();
    }

    @Nested
    @DisplayName("informantregister_processed_total")
    class Processed {

        @Test
        void a_completed_request_should_increment_the_completed_series() {
            metrics.requestSettled(RequestOutcome.COMPLETED);

            assertThat(counter(ProcessingMetrics.PROCESSED, "outcome", "completed")).isEqualTo(1);
        }

        @Test
        void a_parked_request_should_increment_the_failed_series() {
            metrics.requestSettled(RequestOutcome.FAILED);

            assertThat(counter(ProcessingMetrics.PROCESSED, "outcome", "failed")).isEqualTo(1);
        }

        @Test
        void the_two_outcomes_should_be_separate_series() {
            metrics.requestSettled(RequestOutcome.COMPLETED);
            metrics.requestSettled(RequestOutcome.COMPLETED);
            metrics.requestSettled(RequestOutcome.FAILED);

            assertThat(counter(ProcessingMetrics.PROCESSED, "outcome", "completed")).isEqualTo(2);
            assertThat(counter(ProcessingMetrics.PROCESSED, "outcome", "failed")).isEqualTo(1);
        }

        @Test
        void it_should_carry_the_outcome_label_and_nothing_else() {
            metrics.requestSettled(RequestOutcome.COMPLETED);

            assertThat(tagKeysOf(ProcessingMetrics.PROCESSED)).containsExactly("outcome");
        }
    }

    @Nested
    @DisplayName("informantregister_processing_failures_total")
    class ProcessingFailures {

        @Test
        void a_transient_failure_ending_in_retrying_should_increment_it() {
            // Every failed run counts, not only the one that exhausts the delivery budget: a
            // request retrying quietly forever is exactly what this service exists to make visible.
            metrics.pipelineFailed(FailureClassification.TRANSIENT);

            assertThat(counter(ProcessingMetrics.PROCESSING_FAILURES, "classification", "transient"))
                    .isEqualTo(1);
        }

        @Test
        void a_non_transient_failure_should_increment_its_own_series() {
            metrics.pipelineFailed(FailureClassification.NON_TRANSIENT);

            assertThat(counter(ProcessingMetrics.PROCESSING_FAILURES, "classification", "non-transient"))
                    .isEqualTo(1);
        }

        @Test
        void it_should_carry_the_classification_label_and_nothing_else() {
            metrics.pipelineFailed(FailureClassification.TRANSIENT);

            assertThat(tagKeysOf(ProcessingMetrics.PROCESSING_FAILURES)).containsExactly("classification");
        }
    }

    @Nested
    @DisplayName("informantregister_intake_suspensions_total and informantregister_intake_suspended")
    class Intake {

        @Test
        void suspending_intake_should_increment_the_counter_and_raise_the_gauge() {
            metrics.intakeSuspended();

            assertThat(counter(ProcessingMetrics.INTAKE_SUSPENSIONS)).isEqualTo(1);
            assertThat(gauge(ProcessingMetrics.INTAKE_SUSPENDED)).isEqualTo(1);
        }

        @Test
        void resuming_intake_should_lower_the_gauge_without_touching_the_counter() {
            metrics.intakeSuspended();
            metrics.intakeResumed();

            assertThat(gauge(ProcessingMetrics.INTAKE_SUSPENDED)).isZero();
            assertThat(counter(ProcessingMetrics.INTAKE_SUSPENSIONS)).isEqualTo(1);
        }

        @Test
        void the_gauge_should_read_zero_before_anything_happens() {
            assertThat(gauge(ProcessingMetrics.INTAKE_SUSPENDED)).isZero();
        }

        @Test
        void a_second_suspension_should_count_again() {
            metrics.intakeSuspended();
            metrics.intakeResumed();
            metrics.intakeSuspended();

            assertThat(counter(ProcessingMetrics.INTAKE_SUSPENSIONS)).isEqualTo(2);
        }

        @Test
        void neither_should_carry_a_label() {
            metrics.intakeSuspended();

            assertThat(tagKeysOf(ProcessingMetrics.INTAKE_SUSPENSIONS)).isEmpty();
            assertThat(tagKeysOf(ProcessingMetrics.INTAKE_SUSPENDED)).isEmpty();
        }
    }

    @Nested
    @DisplayName("informantregister_deadlettered_total")
    class DeadLettered {

        @Test
        void every_reason_should_have_its_own_series() {
            metrics.deadLettered(DeadLetterReason.VALIDATION);
            metrics.deadLettered(DeadLetterReason.COLLISION);
            metrics.deadLettered(DeadLetterReason.EXHAUSTED);
            metrics.deadLettered(DeadLetterReason.NON_TRANSIENT);

            assertThat(counter(ProcessingMetrics.DEADLETTERED, "reason", "validation")).isEqualTo(1);
            assertThat(counter(ProcessingMetrics.DEADLETTERED, "reason", "collision")).isEqualTo(1);
            assertThat(counter(ProcessingMetrics.DEADLETTERED, "reason", "exhausted")).isEqualTo(1);
            assertThat(counter(ProcessingMetrics.DEADLETTERED, "reason", "non-transient")).isEqualTo(1);
        }

        @Test
        void it_should_carry_the_reason_label_and_nothing_else() {
            metrics.deadLettered(DeadLetterReason.VALIDATION);

            assertThat(tagKeysOf(ProcessingMetrics.DEADLETTERED)).containsExactly("reason");
        }
    }

    @Nested
    @DisplayName("informantregister_settlement_failures_total")
    class SettlementFailures {

        @Test
        void every_settlement_call_should_have_its_own_series() {
            metrics.settlementFailed(SettlementOperation.COMPLETE);
            metrics.settlementFailed(SettlementOperation.ABANDON);
            metrics.settlementFailed(SettlementOperation.DEADLETTER);

            assertThat(counter(ProcessingMetrics.SETTLEMENT_FAILURES, "operation", "complete")).isEqualTo(1);
            assertThat(counter(ProcessingMetrics.SETTLEMENT_FAILURES, "operation", "abandon")).isEqualTo(1);
            assertThat(counter(ProcessingMetrics.SETTLEMENT_FAILURES, "operation", "deadletter"))
                    .isEqualTo(1);
        }

        @Test
        void it_should_carry_the_operation_label_and_nothing_else() {
            metrics.settlementFailed(SettlementOperation.ABANDON);

            assertThat(tagKeysOf(ProcessingMetrics.SETTLEMENT_FAILURES)).containsExactly("operation");
        }
    }

    @Nested
    @DisplayName("the unlabelled counters")
    class UnlabelledCounters {

        @Test
        void a_lost_lock_should_increment_its_counter() {
            metrics.lockLost();

            assertThat(counter(ProcessingMetrics.LOCK_LOSS)).isEqualTo(1);
            assertThat(tagKeysOf(ProcessingMetrics.LOCK_LOSS)).isEmpty();
        }

        @Test
        void a_rejected_stale_runner_should_increment_its_counter() {
            metrics.staleRunnerRejected();

            assertThat(counter(ProcessingMetrics.STALE_RUNNER_REJECTIONS)).isEqualTo(1);
            assertThat(tagKeysOf(ProcessingMetrics.STALE_RUNNER_REJECTIONS)).isEmpty();
        }
    }

    @Nested
    @DisplayName("informantregister_servicebus_up")
    class ServiceBusUp {

        @Test
        void it_should_start_up_because_no_outage_has_been_observed() {
            assertThat(gauge(ProcessingMetrics.SERVICEBUS_UP)).isEqualTo(1);
        }

        @Test
        void it_should_mirror_the_health_component_in_both_directions() {
            metrics.serviceBusUp(false);
            assertThat(gauge(ProcessingMetrics.SERVICEBUS_UP)).isZero();

            metrics.serviceBusUp(true);
            assertThat(gauge(ProcessingMetrics.SERVICEBUS_UP)).isEqualTo(1);
        }

        @Test
        void it_should_not_carry_a_label() {
            assertThat(tagKeysOf(ProcessingMetrics.SERVICEBUS_UP)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the surface as a whole")
    class Surface {

        @Test
        void the_two_gauges_should_be_registered_before_anything_happens() {
            // Gauges are state, not events: a dashboard must be able to read them from a pod that
            // has not yet seen a message.
            assertThat(registry.getMeters().stream().map(meter -> meter.getId().getName()).toList())
                    .containsExactlyInAnyOrder(
                            ProcessingMetrics.INTAKE_SUSPENDED,
                            ProcessingMetrics.SERVICEBUS_UP);
        }

        @Test
        void exercising_everything_should_register_exactly_the_documented_instruments() {
            metrics.requestSettled(RequestOutcome.COMPLETED);
            metrics.pipelineFailed(FailureClassification.TRANSIENT);
            metrics.intakeSuspended();
            metrics.deadLettered(DeadLetterReason.VALIDATION);
            metrics.settlementFailed(SettlementOperation.ABANDON);
            metrics.lockLost();
            metrics.staleRunnerRejected();

            assertThat(registry.getMeters().stream()
                    .map(meter -> meter.getId().getName())
                    .distinct()
                    .toList())
                    .containsExactlyInAnyOrder(
                            ProcessingMetrics.PROCESSED,
                            ProcessingMetrics.PROCESSING_FAILURES,
                            ProcessingMetrics.INTAKE_SUSPENSIONS,
                            ProcessingMetrics.DEADLETTERED,
                            ProcessingMetrics.SETTLEMENT_FAILURES,
                            ProcessingMetrics.LOCK_LOSS,
                            ProcessingMetrics.STALE_RUNNER_REJECTIONS,
                            ProcessingMetrics.INTAKE_SUSPENDED,
                            ProcessingMetrics.SERVICEBUS_UP);
        }

        @Test
        void no_instrument_should_carry_an_identifying_label() {
            metrics.requestSettled(RequestOutcome.COMPLETED);
            metrics.pipelineFailed(FailureClassification.TRANSIENT);
            metrics.deadLettered(DeadLetterReason.COLLISION);
            metrics.settlementFailed(SettlementOperation.COMPLETE);

            assertThat(registry.getMeters().stream()
                    .flatMap(meter -> meter.getId().getTags().stream())
                    .map(io.micrometer.core.instrument.Tag::getKey)
                    .distinct()
                    .toList())
                    .containsExactlyInAnyOrder("outcome", "classification", "reason", "operation");
        }

        @Test
        void there_should_be_no_dead_letter_depth_gauge() {
            // Depth comes from the platform's own queue metric. Polling it here would cost a
            // receiver connection and race with support tooling draining the queue.
            assertThat(registry.find("informantregister_deadletter_depth").gauge()).isNull();
        }
    }
}
