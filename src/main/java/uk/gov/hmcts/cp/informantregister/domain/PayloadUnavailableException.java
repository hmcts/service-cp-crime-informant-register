package uk.gov.hmcts.cp.informantregister.domain;

/**
 * Thrown when the hearing payload for a request cannot be obtained.
 *
 * <p>Transient by construction. There is no non-transient payload-fetch case: a payload that cannot
 * be read now is a cache, a network or a query-side problem, and every one of those is worth a
 * redelivery. The classification is therefore fixed rather than supplied, so no caller can raise a
 * payload failure that quietly parks a recoverable request.
 *
 * <p>Like every other failure this service reports, it carries a bounded {@link ReasonCode} and
 * never a raw message from the layer beneath it — the code travels into {@code failure_reason}, a
 * dead-letter description and the log index.
 */
public class PayloadUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ReasonCode reasonCode;

    public PayloadUnavailableException(final ReasonCode reason) {
        super(reason.code());
        this.reasonCode = reason;
    }

    /**
     * Always {@link FailureClassification#TRANSIENT} — see the class comment.
     */
    public FailureClassification classification() {
        throw new UnsupportedOperationException("payload unavailability is transient by construction");
    }

    /**
     * The bounded code recorded for this failure.
     */
    public ReasonCode reason() {
        return reasonCode;
    }
}
