package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.util.Objects;

/**
 * What a run learned when it read the {@code YotResultsDistributionService} flag.
 *
 * <p>Three answers and never an exception. The flag gates the start of every nightly run, so a
 * reader that threw would make an App Configuration outage indistinguishable from a job that
 * crashed; instead the read always answers, and a store that could not be reached answers
 * {@link Unreadable}. Fail-closed is the whole point: only {@link #ON} lets a run generate, and both
 * of the other two skip it and count the skip (constitution Cutover Rule).
 *
 * <p><strong>The unreadable case carries a bounded code and nothing else.</strong> An SDK message, a
 * URL or a store's response body would reach the skipped counter's {@code reason} label and the log
 * index, and neither is a place for another system's text.
 */
public sealed interface FlagDecision {

    /** The flag was read and this service is the implementation that generates. */
    FlagDecision ON = new Enabled();

    /** The flag was read and the legacy is the implementation that generates. */
    FlagDecision OFF = new Disabled();

    /**
     * Whether the run may go on to generate.
     *
     * @return true only where the flag was read and is on
     */
    boolean generates();

    /**
     * The bounded code this decision is logged and counted under.
     *
     * @return the bounded code, fixed per decision and never free text
     */
    String code();

    /**
     * Why a read produced no answer.
     *
     * <p>Bounded because it labels the skipped counter: one series per cause, so a store that is
     * absent, one that refuses this pod's identity and one that is merely slow are three signals
     * rather than one.
     */
    enum UnreadableReason {

        /** Generation is enabled and no App Configuration endpoint was configured. */
        NOT_CONFIGURED("unreadable-not-configured"),

        /** The store answered, and holds no setting under the configured key and label. */
        NOT_FOUND("unreadable-not-found"),

        /** The store refused this pod's identity. */
        ACCESS_DENIED("unreadable-access-denied"),

        /** The read did not answer inside its budget. */
        TIMED_OUT("unreadable-timed-out"),

        /** The setting was returned and is not a feature flag this service can read. */
        MALFORMED("unreadable-malformed"),

        /** The read failed in a way none of the above describes. */
        CALL_FAILED("unreadable-call-failed");

        private final String storedCode;

        UnreadableReason(final String code) {
            this.storedCode = code;
        }

        /**
         * The code as it is counted and logged.
         *
         * @return the bounded code for this cause
         */
        public String code() {
            return storedCode;
        }
    }

    /** The read succeeded and the flag is on. */
    record Enabled() implements FlagDecision {

        @Override
        public boolean generates() {
            return true;
        }

        @Override
        public String code() {
            return "on";
        }
    }

    /** The read succeeded and the flag is off. */
    record Disabled() implements FlagDecision {

        @Override
        public boolean generates() {
            return false;
        }

        @Override
        public String code() {
            return "off";
        }
    }

    /**
     * The flag could not be read, which this service treats exactly as off.
     *
     * @param reason the bounded cause, which is what the skipped counter is labelled with
     */
    record Unreadable(UnreadableReason reason) implements FlagDecision {

        /**
         * Refuses an unreadable decision that cannot say why, which would be a second spelling of
         * {@link FlagDecision#OFF} carrying none of its certainty.
         */
        public Unreadable {
            Objects.requireNonNull(reason, "an unreadable flag names its bounded cause");
        }

        @Override
        public boolean generates() {
            return false;
        }

        @Override
        public String code() {
            return reason.code();
        }
    }
}
