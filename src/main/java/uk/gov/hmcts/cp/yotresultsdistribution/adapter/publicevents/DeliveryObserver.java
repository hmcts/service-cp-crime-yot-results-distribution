package uk.gov.hmcts.cp.yotresultsdistribution.adapter.publicevents;

/**
 * Told that the durable subscription handed this service a message.
 *
 * <p>One method, and deliberately nothing else. What the listener knows is that the broker delivered
 * something; what that says about the subscription's health is somebody else's reading, and a
 * listener that depended on a {@code HealthIndicator} to say so would be a JMS adapter holding an
 * actuator type.
 *
 * <p><strong>Every delivery, before any filter.</strong> The observation is "the broker is serving
 * this subscription", and that is as true of progression's still-deployed leg announcing its own
 * document as it is of one of ours: an observer told only about the two events this service acts on
 * would report a silent broker every evening the legacy generated and this service did not.
 */
@FunctionalInterface
public interface DeliveryObserver {

    /** An observer for a context that has nobody to tell, which does nothing rather than refuse. */
    DeliveryObserver NONE = () -> {
    };

    /** Records that a message was delivered on the subscription, whatever it turns out to be. */
    void recordDelivery();
}
