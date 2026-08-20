package uk.gov.hmcts.cp.informantregister.domain;

/**
 * How a request finished, for the terminal-outcome counter.
 */
public enum RequestOutcome {

    /** The pipeline ran and the outcome was recorded. */
    COMPLETED("completed"),

    /** The request was parked after exhausting its permitted deliveries. */
    FAILED("failed");

    private final String metricLabel;

    RequestOutcome(final String label) {
        this.metricLabel = label;
    }

    /**
     * The metric label value. Fixed here rather than derived from the constant name, so renaming a
     * constant cannot silently rename a dashboard's series.
     */
    public String label() {
        return metricLabel;
    }
}
