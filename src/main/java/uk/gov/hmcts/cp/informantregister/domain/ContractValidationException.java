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
 *
 * <p>There is deliberately no constructor taking a cause. Underlying parser exceptions are
 * translated rather than wrapped, because a library's message is written to help a developer and may
 * quote the input it choked on — and a cause travels wherever the exception travels. Offering the
 * constructor would make leaking the body the easy thing to do.
 */
public class ContractValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ContractViolation violationCode;
    private final String fieldName;

    /** Creates the violation; {@code field} is null when no single field is at fault. */
    public ContractValidationException(final ContractViolation violation, final String field) {
        super(violation + (field == null ? "" : " [" + field + "]"));
        this.violationCode = violation;
        this.fieldName = field;
    }

    /** The violated contract rule. */
    public ContractViolation violation() {
        return violationCode;
    }

    /**
     * The offending field's name, or {@code null} when the failure is not attributable to one field.
     */
    public String field() {
        return fieldName;
    }
}
