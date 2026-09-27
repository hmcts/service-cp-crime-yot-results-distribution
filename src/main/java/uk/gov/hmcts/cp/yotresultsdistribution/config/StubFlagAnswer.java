package uk.gov.hmcts.cp.yotresultsdistribution.config;

/**
 * What the stub feature-flag reader answers with.
 *
 * <p>Set only by tests and the local profile, like {@link PayloadFailureMode} and for the same
 * reason: it is deliberately not a field in the message and not an HTTP endpoint, either of which
 * would let something outside the deployment decide whether a night's registers are generated.
 *
 * <p><strong>{@link #ON} is the default, and that is the opposite of the live reader's
 * disposition.</strong> The live reader fails closed because an App Configuration outage must never
 * be mistaken for a cutover; the stub has no store to fail against, so a stub that answered OFF
 * would leave every local run and every batch-state-machine suite skipping before it began, and the
 * job would be untestable without a store. The other two answers are here so the skip paths can be
 * exercised deliberately.
 *
 * <p>Not to be confused with {@code RecordedFlagState}, which is a fact stamped on a recorded row
 * rather than a setting: this enum says what the stub reader will say, that one says what the reader
 * said when a particular register arrived.
 */
public enum StubFlagAnswer {

    /** The stub says this service generates, which is what a local run wants. */
    ON,

    /** The stub says the legacy generates, so the run is skipped and counted. */
    OFF,

    /** The stub says the flag could not be read at all, which is treated exactly as off. */
    UNREADABLE
}
