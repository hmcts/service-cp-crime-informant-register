package uk.gov.hmcts.cp.informantregister.domain;

/**
 * Thrown when a message body does not satisfy the inbound contract.
 *
 * <p>Non-transient by construction: no amount of redelivery turns an invalid body into a valid one,
 * so the listener parks the delivery immediately rather than consuming retry attempts.
 *
 * <p>The exception carries a bounded {@link ContractViolation} and, where one is known, the name of
 * the offending field. It never carries the field's value: the message is producer-supplied content
 * and this exception's text reaches the dead-letter description and the log index.
 */
public class ContractValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ContractViolation violation;
    private final String field;

    public ContractValidationException(final ContractViolation violation, final String field) {
        super(violation + (field == null ? "" : " [" + field + "]"));
        this.violation = violation;
        this.field = field;
    }

    public ContractValidationException(final ContractViolation violation,
                                       final String field,
                                       final Throwable cause) {
        super(violation + (field == null ? "" : " [" + field + "]"), cause);
        this.violation = violation;
        this.field = field;
    }

    public ContractViolation violation() {
        return violation;
    }

    /**
     * The offending field's name, or {@code null} when the failure is not attributable to one field.
     */
    public String field() {
        return field;
    }
}
