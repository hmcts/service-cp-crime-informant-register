package uk.gov.hmcts.cp.informantregister.domain;

/**
 * Thrown when a per-authority submission does not succeed.
 *
 * <p>Unlike a payload failure this one <strong>carries</strong> its classification, because the
 * submission adapter the later story attaches needs both from one exception: a connect, an IO
 * failure, a 5xx and a 429 are worth retrying, while a 4xx contract rejection is not. Callers
 * therefore branch on the classification rather than on the exception type.
 *
 * <p>The reason is a bounded {@link ReasonCode}; the response body, the URL and the underlying
 * exception's message are deliberately not carried, because this exception's text reaches a
 * dead-letter description and the log index.
 */
public class SubmissionFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FailureClassification failureClassification;
    private final ReasonCode reasonCode;

    public SubmissionFailedException(
            final FailureClassification classification, final ReasonCode reason) {
        super(reason.code());
        this.failureClassification = classification;
        this.reasonCode = reason;
    }

    /**
     * Whether a redelivery could change the outcome.
     */
    public FailureClassification classification() {
        return failureClassification;
    }

    /**
     * The bounded code recorded for this failure.
     */
    public ReasonCode reason() {
        return reasonCode;
    }
}
