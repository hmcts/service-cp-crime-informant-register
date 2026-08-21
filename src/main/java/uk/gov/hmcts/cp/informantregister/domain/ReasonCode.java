package uk.gov.hmcts.cp.informantregister.domain;

/**
 * The bounded reason codes the guard decides with and records.
 *
 * <p>One of these — never a raw exception message and never a fragment of the message body — is what
 * reaches {@code processed_request.failure_reason}, a dead-letter description, and a log's reason
 * field. Both of the alternatives are producer-influenced content, and both would leak into the DLQ
 * and the log index (research §11, constitution Principle VII).
 *
 * <p>The stored code is declared explicitly rather than derived from the constant name, so renaming
 * a constant cannot silently rename a value already written to thousands of rows.
 */
public enum ReasonCode {

    /** A delivery arrived for a record already COMPLETED; acknowledged without a run. */
    ALREADY_COMPLETED("ALREADY_COMPLETED"),

    /** A run's outcome was recorded successfully. */
    RUN_COMPLETED("RUN_COMPLETED"),

    /**
     * A conditional claim acquisition matched no row.
     *
     * <p>Deliberately one code for all three causes — a live runner holds the claim, another
     * delivery won the race, or the row turned terminal in between. Telling them apart would need a
     * second read, and the no-spin rule (data-model §Guard operations 2) forbids one: broker
     * redelivery is the retry mechanism.
     */
    CLAIM_NOT_ACQUIRED("CLAIM_NOT_ACQUIRED"),

    /**
     * The replay update matched no row: the record changed between the read and the update.
     *
     * <p>It never means "the same message identity" — that case is decided on the read.
     */
    REPLAY_NOT_ADMITTED("REPLAY_NOT_ADMITTED"),

    /** The key was reused for a request with different immutable fields (spec FR-018). */
    IDEMPOTENCY_COLLISION("IDEMPOTENCY_COLLISION"),

    /** The delivery that exhausted the permitted deliveries, or a redelivery of that identity. */
    DELIVERY_LIMIT_EXHAUSTED("DELIVERY_LIMIT_EXHAUSTED"),

    /** An outcome write was refused by the owner-and-token predicate: the claim was reclaimed. */
    STALE_RUNNER("STALE_RUNNER"),

    /** The record was absent when the guard read it back after losing the insert race. */
    RECORD_ABSENT("RECORD_ABSENT"),

    /** A pipeline run failed in a way redelivery may fix. */
    PIPELINE_TRANSIENT_FAILURE("PIPELINE_TRANSIENT_FAILURE"),

    /**
     * A run reached its processing deadline and stopped itself.
     *
     * <p>Distinct from an ordinary transient failure on purpose: the run did not fail, it ran out of
     * the time its claim guarantees it. A rise in this code means runs are approaching their leases,
     * which is a capacity signal rather than a downstream one.
     */
    PROCESSING_DEADLINE_EXCEEDED("PROCESSING_DEADLINE_EXCEEDED");

    private final String storedCode;

    ReasonCode(final String code) {
        this.storedCode = code;
    }

    /**
     * The code as it is written to the processed log and reported onward.
     */
    public String code() {
        return storedCode;
    }
}
