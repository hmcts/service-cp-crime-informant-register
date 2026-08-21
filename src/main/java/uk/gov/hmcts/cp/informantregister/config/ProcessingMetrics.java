package uk.gov.hmcts.cp.informantregister.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.informantregister.domain.FailureClassification;
import uk.gov.hmcts.cp.informantregister.domain.RequestOutcome;
import uk.gov.hmcts.cp.informantregister.domain.SettlementOperation;

/**
 * The service's whole instrument surface, declared in one place.
 *
 * <p>Names and label sets are fixed here so the tests assert on them and the alert rules written
 * later have a stable surface to fire on. Labels are low-cardinality enumerations only: a request
 * id, hearing id, message id, authority id or exception message must never be a label value — that
 * is both a cardinality explosion and, for hearing data, a privacy breach. Correlation identifiers
 * live in the structured logs instead.
 *
 * <p>Dead-letter <em>depth</em> is deliberately absent: it is read from Azure Monitor's native
 * queue metric. This service counts the dead-letters it performs, which is a different question.
 */
@Component
public class ProcessingMetrics {

    public static final String PROCESSED = "informantregister_processed_total";
    public static final String PROCESSING_FAILURES = "informantregister_processing_failures_total";
    public static final String INTAKE_SUSPENSIONS = "informantregister_intake_suspensions_total";
    public static final String DEAD_LETTERED = "informantregister_deadlettered_total";
    public static final String SETTLEMENT_FAILURES = "informantregister_settlement_failures_total";
    public static final String LOCK_LOSS = "informantregister_lock_loss_total";
    public static final String STALE_RUNNER_REJECTIONS =
            "informantregister_stale_runner_rejections_total";
    public static final String INTAKE_SUSPENDED = "informantregister_intake_suspended";
    public static final String SERVICEBUS_UP = "informantregister_servicebus_up";

    public static final String OUTCOME_TAG = "outcome";
    public static final String CLASSIFICATION_TAG = "classification";
    public static final String REASON_TAG = "reason";
    public static final String OPERATION_TAG = "operation";

    private static final int UP = 1;
    private static final int DOWN = 0;

    private final MeterRegistry registry;

    /**
     * Gauge state. Held here rather than read from a collaborator so the gauges exist from
     * construction: a dashboard must be able to read them from a pod that has not yet seen a
     * message, and a gauge that only appears after the first incident is not an alerting surface.
     */
    private final AtomicInteger intakeSuspendedState = new AtomicInteger(DOWN);

    /**
     * How the Service Bus gauge answers, at the moment it is asked.
     *
     * <p>A supplier rather than a remembered number, because the state it reports is partly a
     * function of time: an error goes stale, and a consumer that has never been answered stops
     * being given the benefit of the doubt. A value written at the last state change would be
     * whatever it was when something last happened, which for exactly those two transitions is the
     * wrong answer for as long as nothing happens. Up until something says otherwise, which is the
     * honest starting position for a healthy pod.
     */
    private final AtomicReference<BooleanSupplier> serviceBusState =
            new AtomicReference<>(() -> true);

    /** Registers the service's gauges against the given registry. */
    public ProcessingMetrics(final MeterRegistry registry) {
        this.registry = registry;

        Gauge.builder(INTAKE_SUSPENDED, intakeSuspendedState, AtomicInteger::get)
                .description("1 while intake is suspended, 0 while it is running")
                .register(registry);
        Gauge.builder(SERVICEBUS_UP, serviceBusState,
                        state -> state.get().getAsBoolean() ? UP : DOWN)
                .description("1 while the Service Bus health component is up, 0 while it is down")
                .register(registry);
    }

    /**
     * A request reached a terminal outcome.
     */
    public void requestSettled(final RequestOutcome outcome) {
        counter(PROCESSED, OUTCOME_TAG, outcome.label()).increment();
    }

    /**
     * A pipeline run failed — every failed run, including a transient one that ends in RETRYING,
     * not only terminal exhaustion. A request retrying quietly forever is exactly what this service
     * exists to make visible.
     */
    public void pipelineFailed(final FailureClassification classification) {
        counter(PROCESSING_FAILURES, CLASSIFICATION_TAG, classification.label()).increment();
    }

    /**
     * Intake moved into SUSPENDED.
     */
    public void intakeSuspended() {
        intakeSuspendedState.set(UP);
        counter(INTAKE_SUSPENSIONS).increment();
    }

    /**
     * Intake moved back into RUNNING. Deliberately not counted: the counter records incidents, and
     * recovering from one is not a second incident.
     */
    public void intakeResumed() {
        intakeSuspendedState.set(DOWN);
    }

    /**
     * A delivery was parked on the dead-letter queue.
     */
    public void deadLettered(final DeadLetterReason reason) {
        counter(DEAD_LETTERED, REASON_TAG, reason.label()).increment();
    }

    /**
     * A settlement call itself failed.
     */
    public void settlementFailed(final SettlementOperation operation) {
        counter(SETTLEMENT_FAILURES, OPERATION_TAG, operation.label()).increment();
    }

    /**
     * The delivery lock was lost before settlement.
     */
    public void lockLost() {
        counter(LOCK_LOSS).increment();
    }

    /**
     * An outcome write was rejected by the owner-and-token predicate.
     */
    public void staleRunnerRejected() {
        counter(STALE_RUNNER_REJECTIONS).increment();
    }

    /**
     * Mirrors the Service Bus health component.
     */
    public void serviceBusUp(final boolean up) {
        serviceBusState.set(() -> up);
    }

    /**
     * Points the Service Bus gauge at the component that knows the answer.
     *
     * <p>So that a scrape and a health check read the same live state rather than the same
     * remembered one, whichever of them happens first and whether or not the other ever happens at
     * all. A Prometheus scrape does not call the health endpoint on its way past.
     *
     * @param liveState answers, on demand, whether the broker is reachable
     */
    public void bindServiceBusUp(final BooleanSupplier liveState) {
        serviceBusState.set(liveState);
    }

    private Counter counter(final String name) {
        return Counter.builder(name).register(registry);
    }

    private Counter counter(final String name, final String tag, final String value) {
        return Counter.builder(name).tag(tag, value).register(registry);
    }
}
