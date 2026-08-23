package uk.gov.hmcts.cp.informantregister.domain;

/**
 * Why a request completed.
 *
 * <p>A bounded set rather than free text: the value is written to
 * {@code processed_request.completion_reason} and read by support. Both members are successes; what
 * separates them is whether anything was sent, which is the first question asked of a request nobody
 * can find a register for. An empty output set is a legitimate business outcome, recorded as such,
 * and not an error or a status of its own (`design_rules.md`, "Processing State Machine").
 */
public enum CompletionReason {

    /** The run produced no prosecuting-authority output. */
    NO_AUTHORITIES("no-authorities"),

    /**
     * Every prosecuting authority the hearing produced was submitted.
     *
     * <p>Recorded rather than left blank so that "nothing was sent" and "everything was sent" are
     * two answers a support query can tell apart without joining {@code processed_output}.
     */
    AUTHORITIES_SUBMITTED("authorities-submitted");

    private final String storedValue;

    CompletionReason(final String value) {
        this.storedValue = value;
    }

    /**
     * The value as it is written to the processed log.
     */
    public String value() {
        return storedValue;
    }
}
