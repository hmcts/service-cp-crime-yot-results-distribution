package uk.gov.hmcts.cp.yotresultsdistribution.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.publicevents.DeliveryObserver;

/**
 * Whether outcomes are still arriving, reported loudly and never allowed to gate readiness.
 *
 * <p>The same rule {@link ServiceBusHealthIndicator} keeps for the intake broker, for the same
 * reason: a pod cannot heal a broker by restarting, so a broker in the readiness group turns a blip
 * into a rolling restart of every consumer at once while the thing that was actually wrong stays
 * exactly as wrong. It is its own health component instead, outside the group (spec FR-011).
 *
 * <p>What it reports is the subscription's state and the age of the last delivery. The second is the
 * one that matters at 18:30: a subscription that is connected and has heard nothing since the run
 * began is a broker problem nothing else will report until the next run gives up on every batch of
 * the night at once, which is a day later and a court centre's document late.
 *
 * <p><strong>Silence is reported, never judged.</strong> The status follows the subscription alone,
 * because this service hears from {@code public.event} only when somebody else renders something:
 * a night with no registers to generate produces no events at all, and a component that read that
 * as an outage would be reporting the working day rather than the broker. The age is published so
 * that a human, or an alert rule that also knows a run started, can draw the conclusion this
 * component has no business drawing on its own.
 *
 * <p>The details are three, and none of them carries anything but a bounded word, an instant and a
 * number:
 *
 * <ul>
 *   <li>{@code subscription} - {@code running} or {@code stopped};</li>
 *   <li>{@code lastDeliveryAt} - when an event last arrived, or {@code none};</li>
 *   <li>{@code lastDeliveryAgeSeconds} - how long ago that was, or {@code none}.</li>
 * </ul>
 *
 * <p>It is annotated with nothing and is registered by {@link GenerationHealth}, on a generating pod
 * only: a deployment running the intake half alone holds no subscription, and a component reporting
 * on one that was never meant to exist would put the whole health aggregate DOWN for a working pod.
 */
public class PublicEventsHealthIndicator implements HealthIndicator, DeliveryObserver {

    /** What the details call a subscription whose container is running. */
    private static final String RUNNING = "running";

    /** What the details call a subscription whose container is not. */
    private static final String STOPPED = "stopped";

    /** What the details say where there is no delivery to report an instant or an age for. */
    private static final String NONE = "none";

    /**
     * Whether the durable subscription's container is running.
     *
     * <p>Asked rather than remembered: the listener container is the only thing that knows, it
     * already answers the question, and a copy of its state kept here would be a second answer to
     * drift from the first.
     */
    private final BooleanSupplier subscriptionRunning;

    /** The clock the delivery age is measured against. */
    private final Clock clock;

    /** When an event was last delivered on the subscription; null until one has been. */
    private final AtomicReference<Instant> lastDelivery = new AtomicReference<>();

    /**
     * Creates the indicator.
     *
     * @param subscriptionRunning whether the durable subscription's container is running
     * @param clock               the clock the delivery age is measured against
     */
    public PublicEventsHealthIndicator(
            final BooleanSupplier subscriptionRunning, final Clock clock) {
        this.subscriptionRunning = subscriptionRunning;
        this.clock = clock;
    }

    /**
     * Records that an event was delivered on the subscription.
     *
     * <p>Every delivery, not only the two this service acts on: a document belonging to
     * progression's still-deployed leg arriving here is proof the subscription is being served, and
     * a component that counted only our own events would report a broker outage every evening the
     * legacy generated and this service did not.
     *
     * <p>The listener is what calls it, through {@link DeliveryObserver}, at the top of its handler
     * and before any of its three filters.
     */
    @Override
    public void recordDelivery() {
        lastDelivery.set(clock.instant());
    }

    /**
     * The subscription's state, and how long ago it last received anything.
     *
     * <p>The status follows the subscription and nothing else. The age is a detail rather than a
     * rule for the reason the class javadoc gives: a quiet night and a dead broker are the same
     * observation from here, and only a reader who also knows whether a run started can tell them
     * apart.
     *
     * @return what the subscription is doing, reported and never judged
     */
    @Override
    public Health health() {
        final boolean running = subscriptionRunning.getAsBoolean();
        final Instant delivery = lastDelivery.get();
        return (running ? Health.up() : Health.down())
                .withDetail("subscription", running ? RUNNING : STOPPED)
                .withDetail("lastDeliveryAt", delivery == null ? NONE : delivery.toString())
                .withDetail("lastDeliveryAgeSeconds", ageSeconds(delivery))
                .build();
    }

    /**
     * How long ago the last delivery was, in whole seconds.
     *
     * <p>{@code none} rather than zero where nothing has arrived yet: a pod that has just started
     * has no age to report, and a zero would read as a delivery that had just landed - which is the
     * opposite of what it means.
     *
     * @param delivery when an event last arrived, or null if none ever has
     * @return the age in seconds, or {@code none}
     */
    private Object ageSeconds(final Instant delivery) {
        return delivery == null
                ? NONE
                : Duration.between(delivery, clock.instant()).toSeconds();
    }
}
