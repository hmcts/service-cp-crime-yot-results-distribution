package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * Whether a generation run starts, and what it is logged and counted under.
 *
 * <p>{@link FlagDecision} is what the store said; this is what the service did about it. The two are
 * separate because they answer to different rules: the flag has three readings and one of them means
 * "no answer", while a run either starts or does not, and a run may start over a flag that did not
 * say ON because an operator told the CLI to ignore it. Folding the second into the first would make
 * {@code --ignore-flag} look like a fourth thing the store can say.
 *
 * <p>Fail-closed, as the Cutover Rule requires: only a flag read as ON, or an explicit override,
 * starts a run. Every other reading skips it, and a skip is counted under its bounded reason so that
 * a store that is off, one that is unreachable and a night the scheduler never fired are three
 * different signals rather than one silence.
 */
public sealed interface GateDecision {

    /**
     * Why a run was skipped, or why one went ahead that the flag would have stopped.
     *
     * <p>Bounded because it labels the skipped counter and reaches the log index: one series per
     * cause, and no message from another system in either.
     */
    enum Reason {

        /** The flag was read and says the legacy generates. */
        FLAG_OFF("flag-off"),

        /** The flag could not be read, which this service treats exactly as off. */
        FLAG_UNREADABLE("flag-unreadable"),

        /** The flag did not say ON and an operator overrode it deliberately. */
        OVERRIDDEN("overridden");

        private final String storedCode;

        Reason(final String code) {
            this.storedCode = code;
        }

        /**
         * The code this reason is counted and logged under. Fixed here rather than derived from the
         * constant name, so renaming a constant cannot silently rename a dashboard's series.
         *
         * @return the bounded code
         */
        public String code() {
            return storedCode;
        }
    }

    /**
     * The run goes ahead.
     *
     * @param overridden whether it goes ahead over a flag that did not say ON, which is the
     *                   difference between a cutover and somebody standing in for one; such a run is
     *                   logged under {@link Reason#OVERRIDDEN} and the run report says so out loud
     */
    record Proceed(boolean overridden) implements GateDecision {
    }

    /**
     * The run does not start, and the reason is why.
     *
     * @param reason the bounded cause, which is what the skipped counter is labelled with
     */
    record Skipped(Reason reason) implements GateDecision {
    }
}
