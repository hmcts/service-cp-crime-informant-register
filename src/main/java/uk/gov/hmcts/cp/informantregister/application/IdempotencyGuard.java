package uk.gov.hmcts.cp.informantregister.application;

import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.RunClaim;
import uk.gov.hmcts.cp.informantregister.persistence.ProcessedRequestRepository;

/**
 * The processed log's state machine: what a delivery may do, and what a run may record.
 *
 * <p>Seam only at this task: every method throws, so each guard test fails on the behaviour it is
 * about rather than on a stub that quietly returns something plausible.
 */
public class IdempotencyGuard {

    private final ProcessedRequestRepository repository;
    private final ProcessingMetrics metrics;

    public IdempotencyGuard(
            final ProcessedRequestRepository repository,
            final ProcessingMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    /**
     * Decides what this delivery may do with the request it carries.
     */
    public GuardDecision admit(final DistributionCommand command, final DeliveryIdentity delivery) {
        throw new UnsupportedOperationException("T021 implements the guard");
    }

    /**
     * Records a run that succeeded.
     */
    public GuardDecision recordCompletion(final RunClaim claim, final CompletionReason reason) {
        throw new UnsupportedOperationException("T021 implements the guard");
    }

    /**
     * Records a run that failed transiently, with deliveries of this message remaining.
     */
    public GuardDecision recordTransientFailure(final RunClaim claim, final ReasonCode reason) {
        throw new UnsupportedOperationException("T021 implements the guard");
    }

    /**
     * Records a run that failed on the final permitted delivery, parking the request.
     */
    public GuardDecision recordExhaustion(
            final RunClaim claim,
            final ReasonCode reason,
            final DeliveryIdentity delivery) {
        throw new UnsupportedOperationException("T021 implements the guard");
    }
}
