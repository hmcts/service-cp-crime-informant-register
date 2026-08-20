package uk.gov.hmcts.cp.informantregister.domain;

/**
 * The three settlement calls this service makes, for the counter that records a settlement failing.
 */
public enum SettlementOperation {

    /** The work is durably done. */
    COMPLETE("complete"),

    /** Return the delivery for redelivery. */
    ABANDON("abandon"),

    /** Park the delivery with a reason. */
    DEADLETTER("deadletter");

    private final String metricLabel;

    SettlementOperation(final String label) {
        this.metricLabel = label;
    }

    /**
     * The metric label value.
     */
    public String label() {
        return metricLabel;
    }
}
