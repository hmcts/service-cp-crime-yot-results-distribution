package uk.gov.hmcts.cp.yotresultsdistribution.config;

/**
 * Which now-subscriptions source the service runs with.
 *
 * <p>A setting rather than a Spring profile, for the same reason {@link PayloadSourceMode} is one:
 * the choice has to be stated, not inherited.
 */
public enum SubscriptionsSourceMode {

    /** The reference-data query-API adapter. The deployed value, and the default. */
    LIVE,

    /**
     * The stub that asks reference data nothing and answers that nobody is subscribed.
     *
     * <p>For local runs and for the container suites whose subject is settlement and the processed
     * log rather than the register's recipients.
     *
     * <p>An empty answer <em>is</em> a legitimate business outcome — {@code no-subscriptions} — so
     * the danger a refusal was once meant to avert is real: a real hearing completing as though
     * reference data had been asked. A refusal cannot avert it, though. The core reads this port
     * before the transformation, exactly where the legacy orchestrator reads it, so a stub that
     * threw would make every stubbed run a transient failure and a queue that never drains, which
     * is a different failure rather than a safer one. What averts it is startup: {@code
     * PropertiesValidator} refuses this mode wherever the deployed credential source is in use,
     * <strong>and</strong> refuses it beside a live payload source — the only configuration in
     * which a hearing anybody could mistake for a real one would ever be answered this way.
     */
    STUB
}
