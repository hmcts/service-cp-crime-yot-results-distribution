package uk.gov.hmcts.cp.yotresultsdistribution.support;

import uk.gov.hmcts.cp.yotresultsdistribution.inbound.StoreGate;

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
     * A gate onto a store that answers, remembering whether it was asked to stop intake.
     *
     * <p>It remembers even though most suites never look: a store that answers the precondition and
     * then dies mid-run must still stop intake, and a gate that could not be asked would let that go
     * untested.
     *
     * @return the gate
     */
    public static Recording open() {
        return new Recording(true);
    }

    /**
     * A gate onto a store that does not answer, remembering whether it was asked to stop intake.
     *
     * @return the gate
     */
    public static Recording closed() {
        return new Recording(false);
    }

    /**
     * A gate that cannot say whether the store is there.
     *
     * <p>The probe behind the real gate turns a data-access failure into an answer, which is what it
     * is for — but it is not the only thing that can go wrong on the way to one. A pool closed
     * underneath a callback, a driver that raises outside Spring's hierarchy, or simply a defect
     * three lines further in all reach the listener as a failure rather than as {@code false}, and
     * the listener holds a delivery nobody else can settle.
     *
     * @return the gate
     */
    public static StoreGate unanswerable() {
        return new Unanswerable();
    }

    /**
     * A gate onto a store that is away, and that cannot be asked to stop intake either.
     *
     * <p>The worst moment for a second failure: the outage has already been discovered, the delivery
     * is owed a hand-back, and the call that was supposed to stop the queue is the one that throws.
     *
     * @return the gate
     */
    public static StoreGate closedAndUnstoppable() {
        return new Unstoppable();
    }

    /** A gate that remembers what was asked of it. */
    public static final class Recording implements StoreGate {

        private final boolean available;
        private int suspensions;

        private Recording(final boolean available) {
            this.available = available;
        }

        @Override
        public boolean storeAvailable() {
            return available;
        }

        @Override
        public void suspendIntake() {
            suspensions++;
        }

        /**
         * How many times intake was asked to stop.
         *
         * @return the number of suspension requests
         */
        public int suspensionsRequested() {
            return suspensions;
        }
    }

    /** A gate whose availability question fails rather than answers. */
    private static final class Unanswerable implements StoreGate {

        @Override
        public boolean storeAvailable() {
            throw new IllegalStateException("the connection pool has been closed");
        }

        @Override
        public void suspendIntake() {
            throw new IllegalStateException("nothing should have got this far");
        }
    }

    /** A gate onto a store that is away, and whose suspension request fails. */
    private static final class Unstoppable implements StoreGate {

        @Override
        public boolean storeAvailable() {
            return false;
        }

        @Override
        public void suspendIntake() {
            throw new IllegalStateException("the transition thread rejected the stop");
        }
    }
}
