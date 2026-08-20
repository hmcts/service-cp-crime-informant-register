package uk.gov.hmcts.cp.informantregister.config;

/**
 * The simulated failure the stub payload source produces.
 *
 * <p>Set only by tests and the local profile. It is deliberately not a field in the message and not
 * an HTTP endpoint: a message-driven or endpoint-driven failure switch would be a production
 * fault-injection surface on a service whose whole purpose is not losing work.
 */
public enum PayloadFailureMode {

    /** The stub returns its fixed payload. */
    NONE,

    /** The stub raises a transient failure, so the retry and parking paths can be exercised. */
    TRANSIENT
}
