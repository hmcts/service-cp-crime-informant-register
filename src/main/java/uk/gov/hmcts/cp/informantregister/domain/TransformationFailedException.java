package uk.gov.hmcts.cp.informantregister.domain;

/**
 * Thrown when a hearing payload cannot be transformed into register fragments.
 *
 * <p>Non-transient by construction. A payload the transformation cannot read is the same payload on
 * every redelivery, so a retry spends a delivery to reach the same answer; the design's failure
 * table lists a transformation error among the failures that go straight to {@code FAILED} and the
 * dead-letter queue (`doc/TECHNICAL_DESIGN.md`, "Non-transient"; `design_rules.md`, "Processing
 * State Machine"). The classification is fixed rather than supplied so no throw site can quietly
 * ask for a retry that cannot help.
 *
 * <p>The legacy swallows these: {@code setInformantRegisterHandler} catches everything, logs, and
 * returns {@code undefined}, so the orchestrator skips the rest of the flow and the hearing produces
 * nothing at all with no signal. Surfacing them instead is the sanctioned transport-reliability
 * change already on the deviations register (entry 2) — the register's <em>content</em> is
 * untouched; only what happens when it cannot be built at all is different.
 *
 * <p>Like every other failure this service reports it carries a bounded {@link ReasonCode} and never
 * a fragment of the payload: the message names the shape that was wrong, never the value that was
 * in it.
 */
public class TransformationFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ReasonCode reasonCode;

    /**
     * Creates the failure.
     *
     * @param detail a bounded description of what could not be transformed; never payload content
     */
    public TransformationFailedException(final String detail) {
        super(detail);
        this.reasonCode = ReasonCode.TRANSFORMATION_FAILED;
    }

    /**
     * Always {@link FailureClassification#NON_TRANSIENT} — see the class comment.
     *
     * @return the classification
     */
    public FailureClassification classification() {
        return FailureClassification.NON_TRANSIENT;
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
