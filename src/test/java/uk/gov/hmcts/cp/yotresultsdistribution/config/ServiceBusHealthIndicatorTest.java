package uk.gov.hmcts.cp.yotresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.core.amqp.exception.AmqpErrorCondition;
import com.azure.core.amqp.exception.AmqpErrorContext;
import com.azure.core.amqp.exception.AmqpException;
import com.azure.messaging.servicebus.ServiceBusErrorSource;
import com.azure.messaging.servicebus.ServiceBusException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import uk.gov.hmcts.cp.yotresultsdistribution.support.AdjustableClock;

/**
 * The reachability rule, exercised at its edges with a clock the test moves.
 *
 * <p>Nothing here polls the broker. The component answers from what the SDK already reports — a
 * processor error, a delivery that arrived, and the fate of a settlement — and from how old each of
 * those is, which is why the clock is injected and why every interesting case sits a second either
 * side of the staleness window rather than being slept out.
 *
 * <p>The scenario that earns this suite its place is the one the aging rule must never absolve: a
 * consumer that recorded a connection fault and has <em>never once</em> been answered. The SDK rolls
 * its message pump silently when the broker is gone, so no second fault will arrive to refresh the
 * first — the fault simply grows old. Aging out a fault is the right answer only for a consumer the
 * broker has answered before, where an idle queue explains the silence; for one it has never
 * answered, the silence <em>is</em> the outage, and the component must keep saying so until first
 * contact.
 *
 * <p>The other half is what may be treated as a fault at all. A refused settlement is a round trip
 * the broker was asked to complete and did not, so it counts — <strong>unless</strong> the refusal
 * is recognisably about one message, which arrives over a connection that plainly worked. Reading it
 * the other way round would put the broker on a dashboard every time a run took slightly too long.
 */
class ServiceBusHealthIndicatorTest {

    private static final Duration STALENESS = Duration.ofSeconds(60);
    private static final Instant STARTED = Instant.parse("2026-08-31T04:00:00Z");

    private static final String ERROR_SOURCE = "RECEIVE";
    private static final String ENTITY_PATH = "yotresultsdistribution.requests";

    private final AdjustableClock clock = AdjustableClock.startingAt(STARTED);
    private final ServiceBusHealthIndicator indicator = new ServiceBusHealthIndicator(
            STALENESS, new ProcessingMetrics(new SimpleMeterRegistry()), clock);

    private void aConnectionFaultIsRecorded() {
        indicator.recordProcessorError(
                ERROR_SOURCE, ENTITY_PATH, new IOException("connection reset"));
    }

    private Status status() {
        return indicator.health().getStatus();
    }

    @Nested
    @DisplayName("a consumer the broker has never answered")
    class NeverAnswered {

        @Test
        void should_stay_down_after_a_fault_older_than_the_staleness_window() {
            indicator.recordIntakeStarted();
            aConnectionFaultIsRecorded();

            clock.advance(STALENESS.plusSeconds(1));

            assertThat(status())
                    .as("a fault nothing has answered does not age into health: with no traffic "
                            + "ever, old silence is still silence")
                    .isEqualTo(Status.DOWN);
            assertThat(indicator.reachableNow())
                    .as("and the gauge answers the same question the same way")
                    .isFalse();
        }

        @Test
        void should_report_a_fresh_fault_at_once_with_no_startup_grace() {
            indicator.recordIntakeStarted();
            aConnectionFaultIsRecorded();

            assertThat(status())
                    .as("grace is the benefit of the doubt for a silence that carries no evidence; "
                            + "a recorded connection fault is evidence, and a wrong DOWN costs one "
                            + "health cycle where a wrong UP hides the outage")
                    .isEqualTo(Status.DOWN);
        }

        @Test
        void should_recover_on_first_contact() {
            indicator.recordIntakeStarted();
            aConnectionFaultIsRecorded();
            clock.advance(STALENESS.plusSeconds(1));

            indicator.recordTraffic();

            assertThat(status())
                    .as("any answer at all ends the outage")
                    .isEqualTo(Status.UP);
        }
    }

    @Nested
    @DisplayName("a consumer the broker has answered before")
    class AnsweredBefore {

        @Test
        void should_age_out_a_fault_nothing_has_repeated_when_the_queue_is_merely_idle() {
            indicator.recordIntakeStarted();
            indicator.recordTraffic();
            clock.advance(Duration.ofMinutes(5));
            aConnectionFaultIsRecorded();

            clock.advance(STALENESS.plusSeconds(1));

            assertThat(status())
                    .as("no traffic since the fault is the normal state of a healthy idle queue, "
                            + "because this consumer has been answered before")
                    .isEqualTo(Status.UP);
        }

        @Test
        void should_report_a_fresh_fault_until_traffic_answers_it() {
            indicator.recordIntakeStarted();
            indicator.recordTraffic();
            clock.advance(Duration.ofMinutes(5));
            aConnectionFaultIsRecorded();

            clock.advance(STALENESS.minusSeconds(1));

            assertThat(status()).isEqualTo(Status.DOWN);
        }

        @Test
        void should_treat_traffic_after_the_fault_as_the_answer_to_it() {
            indicator.recordIntakeStarted();
            aConnectionFaultIsRecorded();
            clock.advance(Duration.ofSeconds(5));

            indicator.recordTraffic();

            assertThat(status()).isEqualTo(Status.UP);
        }
    }

    @Nested
    @DisplayName("with no fault recorded")
    class NoFault {

        @Test
        void should_give_a_starting_consumer_one_grace_window_and_then_report_the_silence() {
            indicator.recordIntakeStarted();

            clock.advance(STALENESS.plusSeconds(1));

            assertThat(status())
                    .as("a consumer that has never once been answered says so after one window")
                    .isEqualTo(Status.DOWN);
        }

        @Test
        void should_stay_up_while_the_startup_grace_lasts() {
            indicator.recordIntakeStarted();

            clock.advance(STALENESS.minusSeconds(1));

            assertThat(status()).isEqualTo(Status.UP);
        }

        @Test
        void should_hold_no_opinion_before_intake_has_started() {
            clock.advance(Duration.ofHours(1));

            assertThat(status())
                    .as("a pod gated on its store has not asked the broker anything yet")
                    .isEqualTo(Status.UP);
        }
    }

    /**
     * The input the SDK's own error callback does not supply.
     *
     * <p>Measured against the emulator, {@code processError} reports nothing at all about a broker
     * that has gone away while the consumer is idle: the processor treats a lost connection as
     * retryable and rolls its message pump silently. A refused settlement is the counterpart signal —
     * it costs nothing extra, pollutes no queue, and exists precisely when an outage matters most,
     * which is while there is work in hand.
     */
    @Nested
    @DisplayName("a settlement the broker refused")
    class RefusedSettlements {

        private ServiceBusException aboutTheConnection() {
            return new ServiceBusException(
                    new AmqpException(true, AmqpErrorCondition.CONNECTION_FORCED,
                            "the connection was forced closed", new AmqpErrorContext("localhost")),
                    ServiceBusErrorSource.COMPLETE);
        }

        private ServiceBusException aboutThisMessage() {
            return new ServiceBusException(
                    new AmqpException(false, AmqpErrorCondition.MESSAGE_LOCK_LOST,
                            "the lock supplied is no longer valid",
                            new AmqpErrorContext("localhost")),
                    ServiceBusErrorSource.COMPLETE);
        }

        @Test
        void should_read_a_refusal_that_is_not_about_the_message_as_a_transport_fault() {
            indicator.recordIntakeStarted();
            indicator.recordTraffic();
            clock.advance(Duration.ofMinutes(5));

            indicator.recordSettlementRefusal(aboutTheConnection());

            assertThat(status())
                    .as("a round trip the broker was asked to complete and did not is evidence "
                            + "about the transport, and it is the evidence processError never sends")
                    .isEqualTo(Status.DOWN);
        }

        @Test
        void should_not_read_a_refusal_about_one_message_as_a_transport_fault() {
            indicator.recordIntakeStarted();
            indicator.recordTraffic();
            clock.advance(Duration.ofMinutes(5));

            indicator.recordSettlementRefusal(aboutThisMessage());

            assertThat(status())
                    .as("a lock that ran out is the delivery's own business, and it arrived over a "
                            + "connection that plainly worked")
                    .isEqualTo(Status.UP);
        }

        @Test
        void should_treat_a_settlement_the_broker_accepted_as_traffic() {
            indicator.recordIntakeStarted();
            aConnectionFaultIsRecorded();
            clock.advance(Duration.ofSeconds(1));

            indicator.recordSettlementAccepted();

            assertThat(status())
                    .as("a consumer working through a backlog it received before a blip is "
                            + "completing round trips constantly; counting only receives would "
                            + "report an outage it is plainly not having")
                    .isEqualTo(Status.UP);
        }

        @Test
        void should_not_report_an_outage_for_a_fault_that_is_not_the_transport() {
            indicator.recordIntakeStarted();
            indicator.recordTraffic();
            clock.advance(Duration.ofMinutes(5));

            indicator.recordProcessorError(
                    ERROR_SOURCE, ENTITY_PATH, new IllegalStateException("this service's own defect"));

            assertThat(status())
                    .as("only a connection-class failure means the queue is unreachable")
                    .isEqualTo(Status.UP);
        }
    }

    @Nested
    @DisplayName("the boundary of the staleness window")
    class Boundary {

        @Test
        void should_still_report_a_fault_that_is_exactly_as_old_as_the_window() {
            indicator.recordIntakeStarted();
            indicator.recordTraffic();
            clock.advance(Duration.ofMinutes(5));
            aConnectionFaultIsRecorded();

            clock.advance(STALENESS);

            assertThat(status())
                    .as("older than the window is the rule; exactly the window is not older than it")
                    .isEqualTo(Status.DOWN);
        }

        @Test
        void should_report_a_started_consumer_that_has_heard_nothing_for_exactly_one_window() {
            indicator.recordIntakeStarted();

            clock.advance(STALENESS);

            assertThat(status())
                    .as("the grace is strictly inside the window, so a completed one reports the "
                            + "silence — both edges answer DOWN, which costs an operator a look "
                            + "rather than a register")
                    .isEqualTo(Status.DOWN);
        }
    }
}
