package uk.gov.hmcts.cp.informantregister.domain;

/**
 * Whether a failure is worth retrying.
 *
 * <p>The pipeline branches on this rather than on an exception type, because the later Results
 * adapter needs both from one exception: connect, IO, 5xx and 429 are retried, a 4xx contract
 * rejection is not.
 */
public enum FailureClassification {

    /** Worth retrying: the delivery is returned to the broker. */
    TRANSIENT("transient"),

    /** Not worth retrying: no redelivery will change the outcome. */
    NON_TRANSIENT("non-transient");

    private final String metricLabel;

    FailureClassification(final String label) {
        this.metricLabel = label;
    }

    /**
     * The metric label value.
     */
    public String label() {
        return metricLabel;
    }
}
