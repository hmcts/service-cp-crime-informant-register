package uk.gov.hmcts.cp.informantregister.domain;

/**
 * What the guard has decided a delivery should do next.
 *
 * <p>A closed set of four: run the pipeline, or settle the delivery in one of the three ways the
 * broker offers. The guard decides; the listener performs. Nothing else is possible, which is the
 * point — a delivery left neither run nor settled is the silent loss this service exists to make
 * impossible (constitution Principle VI).
 *
 * <p>The same type serves both of the guard's surfaces. Admitting a delivery yields any of the four;
 * recording a run's outcome yields one of the three settlements, because the run has already
 * happened.
 */
public sealed interface GuardDecision {

    /**
     * Run the pipeline under this claim, then bring the outcome back to the guard.
     *
     * @param claim the claim acquired for the run — the token an outcome write must present
     */
    record Run(RunClaim claim) implements GuardDecision {
    }

    /**
     * Acknowledge the delivery: the work is durably done, and there is nothing left to do for it.
     *
     * @param reason why there is nothing to do — the run completed, or the record already had
     */
    record Complete(ReasonCode reason) implements GuardDecision {
    }

    /**
     * Return the delivery for redelivery. Never an acknowledgement: the work is not done.
     *
     * @param reason why this delivery is being handed back
     */
    record Abandon(ReasonCode reason) implements GuardDecision {
    }

    /**
     * Park the delivery on the dead-letter queue, visibly, with a bounded reason.
     *
     * @param reason the dead-letter category, which is also the metric label
     * @param detail the bounded reason code recorded alongside it
     */
    record DeadLetter(DeadLetterReason reason, ReasonCode detail) implements GuardDecision {
    }
}
