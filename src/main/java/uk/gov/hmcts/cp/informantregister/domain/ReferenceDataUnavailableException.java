package uk.gov.hmcts.cp.informantregister.domain;

/**
 * Thrown when the now-subscriptions reference data a register is addressed with cannot be obtained.
 *
 * <p>Transient by construction, for the same reason {@link PayloadUnavailableException} is: reference
 * data that cannot be reached now is a network, a gateway or a downstream problem, and every one of
 * those is worth a redelivery. The classification is fixed rather than supplied so no caller can park
 * a recoverable request.
 *
 * <p><strong>Why this is a failure at all.</strong> The legacy catches every transport failure of
 * that query and returns {@code null} ({@code ReferenceDataService.js:52}), which the matching step
 * cannot tell apart from "reference data answered, nobody is subscribed"
 * ({@code InformantRegisterSubscriptions/index.js:22}). The register is then built, POSTed, and
 * reaches nobody, with nothing anywhere recording that it was addressed during an outage — the
 * parity pack's pinning entry {@code d03} records exactly that and requires the port to classify it
 * rather than reproduce it. Registered as {@code doc/DEVIATIONS.md} entry 14.
 *
 * <p>Like every other failure this service reports, it carries a bounded {@link ReasonCode} and never
 * a raw message from the layer beneath it.
 */
public class ReferenceDataUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ReasonCode reasonCode;

    /**
     * Creates the failure.
     *
     * @param reason the bounded reason reference data could not be asked
     */
    public ReferenceDataUnavailableException(final ReasonCode reason) {
        super(reason.code());
        this.reasonCode = reason;
    }

    /**
     * Always {@link FailureClassification#TRANSIENT} — see the class comment.
     *
     * @return the classification
     */
    public FailureClassification classification() {
        return FailureClassification.TRANSIENT;
    }

    /**
     * The bounded code recorded for this failure.
     *
     * @return the reason code
     */
    public ReasonCode reason() {
        return reasonCode;
    }
}
