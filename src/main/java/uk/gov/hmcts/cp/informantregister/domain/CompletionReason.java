package uk.gov.hmcts.cp.informantregister.domain;

/**
 * Why a request completed.
 *
 * <p>A bounded set rather than free text: the value is written to
 * {@code processed_request.completion_reason} and read by support. In this increment the stub
 * pipeline produces no authorities, so there is exactly one member — an empty output set is a
 * legitimate business outcome, recorded as such, and not an error or a status of its own.
 */
public enum CompletionReason {

    /** The run produced no prosecuting-authority output. */
    NO_AUTHORITIES("no-authorities");

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
