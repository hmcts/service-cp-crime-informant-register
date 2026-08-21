package uk.gov.hmcts.cp.informantregister.support;

import uk.gov.hmcts.cp.informantregister.inbound.StoreGate;

/**
 * A store gate for the suites whose subject is not the store.
 *
 * <p>Store availability is a precondition every delivery passes through, so every suite that builds
 * a listener has to supply one — including the settlement suites, whose subject is which broker call
 * was made. {@link #open()} is the ordinary world those suites mean: the store answers, and nobody
 * asks for intake to stop.
 *
 * <p>{@link #closed()} is the other one, for a suite that wants the precondition to fail without a
 * container: it records whether suspension was asked for, because "the delivery was handed back" and
 * "intake was asked to stop" are two separate obligations and a gate that only proved the first
 * would let the second rot.
 */
public final class StoreGateTestSupport {

    private StoreGateTestSupport() {
        // Static fixture holder.
    }

    /**
     * A gate onto a store that answers.
     */
    public static StoreGate open() {
        return new Recording(true);
    }

    /**
     * A gate onto a store that does not answer, remembering whether it was asked to stop intake.
     */
    public static Recording closed() {
        return new Recording(false);
    }

    /** A gate that remembers what was asked of it. */
    public static final class Recording implements StoreGate {

        private final boolean available;
        private int suspensionsRequested;

        private Recording(final boolean available) {
            this.available = available;
        }

        @Override
        public boolean storeAvailable() {
            return available;
        }

        @Override
        public void suspendIntake() {
            suspensionsRequested++;
        }

        /**
         * @return how many times intake was asked to stop
         */
        public int suspensionsRequested() {
            return suspensionsRequested;
        }
    }
}
