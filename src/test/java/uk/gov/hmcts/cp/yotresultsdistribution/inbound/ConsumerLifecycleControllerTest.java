package uk.gov.hmcts.cp.yotresultsdistribution.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.yotresultsdistribution.config.ProcessingMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.config.ServiceBusHealthIndicator;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.ProcessedLogProbe;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;

/**
 * Stopping intake, and what the state is allowed to say about it.
 *
 * <p>Suspension exists for one reason: a store outage must not be paid for one delivery at a time,
 * five deliveries per message, until the broker parks work whose only fault was arriving during an
 * outage of ours (spec FR-015). Everything that reaches the queue while the store is away is handed
 * back, so the only thing that actually ends the bleeding is the processor stopping.
 *
 * <p>Which makes the ordering the whole of it. The state is what every later suspension request is
 * tested against — a request arriving while the state already says {@code SUSPENDED} is a deliberate
 * no-op, because with concurrent deliveries one outage produces several requests and an incident
 * counter that moved once per delivery would make every dashboard read wrong. So a state that
 * announces the stop <em>before</em> the stop has happened is not a cosmetic inaccuracy: a
 * {@code stop()} that throws leaves a processor still consuming under a state that says it is not,
 * every later request is swallowed by the no-op, and the outage runs to the end of the delivery
 * budget with nothing in the logs and nothing on the gauge.
 *
 * <p>The failure is not exotic. {@code stop()} closes AMQP links and waits for callbacks to drain,
 * and it is called at the exact moment the pod is already having a bad time. And it is called from a
 * task on the transition executor, where an exception is not reported by anybody: it ends the task
 * and is lost, which is the swallowing constitution Principle VI forbids.
 *
 * <p>The probe interval is a few milliseconds so the retries this suite is about happen while it
 * watches. Awaitility waits on the conditions themselves, never on the clock.
 */
class ConsumerLifecycleControllerTest {

    /** Fast enough that a retry happens while the test is looking, slow enough to be a schedule. */
    private static final Duration PROBE_INTERVAL = Duration.ofMillis(20);

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private final ServiceBusProcessorClient processor = mock(ServiceBusProcessorClient.class);
    private final ProcessedLogProbe storeProbe = mock(ProcessedLogProbe.class);
    private final Flyway flyway = mock(Flyway.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProcessingMetrics metrics = new ProcessingMetrics(registry);
    private final ServiceBusHealthIndicator health = new ServiceBusHealthIndicator(
            Duration.ofSeconds(60), metrics, Clock.systemUTC());

    private final ConsumerLifecycleController controller = new ConsumerLifecycleController(
            processor, storeProbe, () -> flyway, metrics, health, PROBE_INTERVAL);

    private CapturedLog controllerLog;

    @BeforeEach
    void captureWhatTheControllerReports() {
        controllerLog = CapturedLog.capturing(ConsumerLifecycleController.class);
    }

    /**
     * Shutdown is not this suite's subject, so it is allowed to close cleanly.
     *
     * <p>Several cases leave a processor whose {@code stop()} refuses, and the context teardown path
     * stops the same processor. Left refusing, every one of those cases would end in a teardown
     * failure that says nothing about what the case was asserting.
     */
    @AfterEach
    void stopTheController() {
        doNothing().when(processor).stop();
        controller.stop();
        controllerLog.close();
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * A pod that has got as far as consuming — the only state a suspension means anything from.
     */
    private void givenIntakeIsRunning() {
        when(storeProbe.available()).thenReturn(true);
        controller.start();
        await().atMost(PATIENCE).until(controller::intakeStarted);
        // And then the store goes away, so the probe cannot resume underneath the assertions.
        when(storeProbe.available()).thenReturn(false);
    }

    private double counter(final String name) {
        final Counter found = registry.find(name).counter();
        return found == null ? 0 : found.count();
    }

    private double gauge(final String name) {
        final Gauge found = registry.find(name).gauge();
        return found == null ? 0 : found.value();
    }

    private List<String> errorsReported() {
        return controllerLog.events().stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    // --- the ordinary suspension ------------------------------------------------------------------

    @Nested
    @DisplayName("a store outage that stops intake cleanly")
    class SuspensionSucceeds {

        @Test
        @DisplayName("the processor is stopped, and the outage is counted once")
        void should_stop_the_processor_and_count_the_outage_once() {
            givenIntakeIsRunning();

            controller.suspendIntake();

            await().atMost(PATIENCE).untilAsserted(() -> verify(processor).stop());
            await().atMost(PATIENCE).untilAsserted(() ->
                    assertThat(counter(ProcessingMetrics.INTAKE_SUSPENSIONS)).isEqualTo(1));
            assertThat(gauge(ProcessingMetrics.INTAKE_SUSPENDED))
                    .as("the gauge is what an alert reads while the outage is going on")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a second request during the same outage stops nothing and counts nothing")
        void should_ignore_a_second_request_once_intake_has_actually_stopped() {
            givenIntakeIsRunning();

            controller.suspendIntake();
            await().atMost(PATIENCE).untilAsserted(() -> verify(processor).stop());
            controller.suspendIntake();

            await().during(Duration.ofMillis(PROBE_INTERVAL.toMillis() * 10)).atMost(PATIENCE)
                    .untilAsserted(() -> verify(processor, times(1)).stop());
            assertThat(counter(ProcessingMetrics.INTAKE_SUSPENSIONS))
                    .as("one outage, one incident — however many deliveries noticed it")
                    .isEqualTo(1);
        }
    }

    // --- the suspension that could not stop the processor -------------------------------------------

    @Nested
    @DisplayName("a store outage whose stop the processor refuses")
    class SuspensionFails {

        @Test
        @DisplayName("the refusal is reported rather than lost on the transition thread")
        void should_report_a_stop_that_failed() {
            doThrow(new IllegalStateException("the links would not close"))
                    .when(processor).stop();
            givenIntakeIsRunning();

            controller.suspendIntake();

            await().atMost(PATIENCE).untilAsserted(() -> assertThat(errorsReported()).hasSize(1));
            assertThat(errorsReported().getFirst())
                    .as("reported by type, and never by the text a broker client arrives with")
                    .contains(IllegalStateException.class.getName())
                    .doesNotContain("the links would not close");
        }

        @Test
        @DisplayName("a stop that failed is not counted as an outage that was contained")
        void should_not_count_an_outage_it_did_not_actually_stop() {
            doThrow(new IllegalStateException("the links would not close"))
                    .when(processor).stop();
            givenIntakeIsRunning();

            controller.suspendIntake();

            await().atMost(PATIENCE).untilAsserted(() -> verify(processor).stop());
            assertThat(counter(ProcessingMetrics.INTAKE_SUSPENSIONS))
                    .as("the counter records outages that were stopped, not ones that were intended")
                    .isZero();
            assertThat(gauge(ProcessingMetrics.INTAKE_SUSPENDED))
                    .as("a gauge reading SUSPENDED over a processor that is still consuming is the "
                            + "one reading nobody would question")
                    .isZero();
            assertThat(counter(ProcessingMetrics.INTAKE_SUSPENSION_FAILURES))
                    .as("so the only instrument that can report it must")
                    .isEqualTo(1);
        }

        /**
         * The whole point. A pod that failed to stop is still consuming, so the next delivery meets
         * the same dead store and asks for the same thing — and that request has to be honoured. A
         * state that had already moved to SUSPENDED turns every one of those requests into a no-op
         * and the outage runs to the end of the delivery budget.
         */
        @Test
        @DisplayName("the next delivery's request tries again rather than meeting a no-op")
        void should_try_again_when_the_next_delivery_asks() {
            doThrow(new IllegalStateException("the links would not close"))
                    .when(processor).stop();
            givenIntakeIsRunning();

            controller.suspendIntake();
            await().atMost(PATIENCE).untilAsserted(() -> verify(processor).stop());

            controller.suspendIntake();

            await().atMost(PATIENCE).untilAsserted(() -> verify(processor, times(2)).stop());
        }

        @Test
        @DisplayName("the retry that succeeds is the one that counts the outage")
        void should_count_the_outage_once_when_a_later_attempt_succeeds() {
            doThrow(new IllegalStateException("the links would not close"))
                    .doNothing()
                    .when(processor).stop();
            givenIntakeIsRunning();

            controller.suspendIntake();
            await().atMost(PATIENCE).untilAsserted(() -> verify(processor).stop());

            controller.suspendIntake();

            await().atMost(PATIENCE).untilAsserted(() ->
                    assertThat(counter(ProcessingMetrics.INTAKE_SUSPENSIONS)).isEqualTo(1));
            assertThat(gauge(ProcessingMetrics.INTAKE_SUSPENDED)).isEqualTo(1);
        }

        /**
         * And the recovery still works from there. A pod left half-way through a suspension must
         * come back when its store does, or a failed stop would strand it until somebody restarted
         * it by hand.
         */
        @Test
        @DisplayName("intake still resumes once the store answers again")
        void should_resume_after_a_stop_that_never_succeeded() {
            doThrow(new IllegalStateException("the links would not close"))
                    .when(processor).stop();
            givenIntakeIsRunning();

            controller.suspendIntake();
            await().atMost(PATIENCE).untilAsserted(() -> verify(processor).stop());

            doNothing().when(processor).stop();
            when(storeProbe.available()).thenReturn(true);

            await().atMost(PATIENCE).untilAsserted(() -> verify(processor, atLeast(2)).start());
        }
    }
}
