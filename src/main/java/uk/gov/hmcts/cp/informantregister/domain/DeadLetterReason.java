package uk.gov.hmcts.cp.informantregister.domain;

/**
 * Why a delivery was parked on the dead-letter queue.
 *
 * <p>A closed, low-cardinality set: it is both a metric label and part of the dead-letter reason, so
 * it can never carry an identifier or an exception message.
 */
public enum DeadLetterReason {

    /** The body did not satisfy the inbound contract. */
    VALIDATION("validation"),

    /** The identity was reused for a different request. */
    COLLISION("collision"),

    /** The permitted deliveries were exhausted. */
    EXHAUSTED("exhausted"),

    /** The run failed in a way no redelivery would fix. */
    NON_TRANSIENT("non-transient");

    private final String label;

    DeadLetterReason(final String label) {
        this.label = label;
    }

    /**
     * The metric label value, and the reason recorded on the dead-lettered message.
     */
    public String label() {
        return label;
    }
}
