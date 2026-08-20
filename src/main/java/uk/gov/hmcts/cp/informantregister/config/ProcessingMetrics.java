package uk.gov.hmcts.cp.informantregister.config;

import io.micrometer.core.instrument.MeterRegistry;
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
    public static final String DEADLETTERED = "informantregister_deadlettered_total";
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

    private final MeterRegistry registry;

    public ProcessingMetrics(final MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * A request reached a terminal outcome.
     */
    public void requestSettled(final RequestOutcome outcome) {
        // Not implemented yet.
    }

    /**
     * A pipeline run failed — every failed run, including a transient one that ends in RETRYING,
     * not only terminal exhaustion.
     */
    public void pipelineFailed(final FailureClassification classification) {
        // Not implemented yet.
    }

    /**
     * Intake moved into SUSPENDED.
     */
    public void intakeSuspended() {
        // Not implemented yet.
    }

    /**
     * Intake moved back into RUNNING.
     */
    public void intakeResumed() {
        // Not implemented yet.
    }

    /**
     * A delivery was parked on the dead-letter queue.
     */
    public void deadLettered(final DeadLetterReason reason) {
        // Not implemented yet.
    }

    /**
     * A settlement call itself failed.
     */
    public void settlementFailed(final SettlementOperation operation) {
        // Not implemented yet.
    }

    /**
     * The delivery lock was lost before settlement.
     */
    public void lockLost() {
        // Not implemented yet.
    }

    /**
     * An outcome write was rejected by the owner-and-token predicate.
     */
    public void staleRunnerRejected() {
        // Not implemented yet.
    }

    /**
     * Mirrors the Service Bus health component.
     */
    public void serviceBusUp(final boolean up) {
        // Not implemented yet.
    }

    protected MeterRegistry registry() {
        return registry;
    }
}
