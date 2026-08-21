package uk.gov.hmcts.cp.informantregister.inbound;

/**
 * The two things a delivery needs to know about the processed log before it is examined.
 *
 * <p>Spec FR-015 makes store availability a <strong>precondition</strong> rather than a step: a
 * delivery arriving without a store is handed straight back, unexamined, and intake stops until the
 * store returns. Both halves are here because both belong to the same moment — the callback that
 * discovers the outage is the callback that has to give its delivery back and ask for intake to
 * stop.
 *
 * <p>Asking is all the callback may do. Stopping a processor from inside its own message callback
 * deadlocks the shutdown, because the shutdown waits for that very callback to return, so the
 * request is enqueued and carried out somewhere else (research §7).
 */
public interface StoreGate {

    /**
     * @return whether the processed log can be reached right now
     */
    boolean storeAvailable();

    /**
     * Asks for intake to stop. Returns immediately, having decided nothing and stopped nothing.
     */
    void suspendIntake();
}
