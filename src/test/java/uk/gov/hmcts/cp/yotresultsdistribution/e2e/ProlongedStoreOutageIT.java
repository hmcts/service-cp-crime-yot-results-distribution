package uk.gov.hmcts.cp.yotresultsdistribution.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.models.SubQueue;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import uk.gov.hmcts.cp.yotresultsdistribution.config.ProcessingMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport.Row;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ServiceTestSupport;

/**
 * The outage that outlasts the delivery budget.
 *
 * <p>This is the case suspension exists for. A service that kept consuming through a store outage
 * would abandon each delivery and get it straight back; five of those round trips cost seconds, and
 * at the end of them the broker parks a message whose only fault was arriving at a bad moment. The
 * work is then on the dead-letter queue, where somebody has to notice it and resubmit it — for an
 * outage that fixed itself.
 *
 * <p>"More than five nominal retry intervals" is measured the way the damage would happen: the
 * outage is held open far longer than five abandon-and-redeliver cycles take against this broker,
 * and the assertion is that the message's delivery count never moves past the cost of discovering
 * the outage ({@code DELIVERIES_AN_OUTAGE_MAY_COST}). A suspended consumer takes no deliveries at
 * all, so the rest of the budget is untouched, and the message is still on the queue rather than
 * beside it.
 *
 * <p>The suite starts its own service and closes it, so the delivery count it reads is a fact about
 * this consumer and not about whichever other suite's context happened to be cached.
 */
class ProlongedStoreOutageIT {

    private static final String SERVICE_LOGGERS = "uk.gov.hmcts.cp.yotresultsdistribution";

    private static final Duration OBSERVED_WITHIN = Duration.ofSeconds(120);
    private static final Duration POLL = Duration.ofSeconds(1);

    /**
     * The outage window. Against this broker an abandoned delivery comes back immediately, so five
     * nominal retry intervals — the whole delivery budget — is a matter of seconds; thirty is
     * comfortably more than five of them and still bounded enough to sit in a build.
     */
    private static final Duration OUTAGE = Duration.ofSeconds(30);

    /**
     * The deliveries an outage may cost before suspension takes hold: the one that discovered it,
     * and at most one more that the hand-back itself races.
     *
     * <p>Spec FR-015 requires the discovering delivery to be returned. The stop cannot be made from
     * inside the callback that discovered the outage, so it is enqueued and lands a moment after the
     * abandon — and the processor holds credit for {@code maxConcurrentCalls} (two) deliveries, so
     * the broker may hand the returned message straight back on the idle credit before the link
     * closes. That second delivery never reaches the listener; it is held on the closed link until
     * its lock expires. Which side wins is timing, so a bound of one passed alone and failed
     * intermittently - reproducibly so in CI's class order. Suspension is what stops the remaining three being spent
     * behind these two, and the count must not move past this bound for the whole window.
     *
     * <p>The bound alone cannot tell that race from a slow stop that lets the listener itself take
     * a second delivery, so the listener's own account is asserted too: one store-unavailable ERROR
     * per delivery it handles, and exactly one in the window.
     */
    private static final long DELIVERIES_AN_OUTAGE_MAY_COST = 2;

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    @BeforeAll
    static void migrateTheSharedStore() {
        ProcessedLogTestSupport.dataSource();
    }

    @AfterEach
    void thawTheStore() {
        PostgresTestSupport.unpause();
    }

    private static double gauge(final MeterRegistry registry, final String name) {
        final Gauge found = registry.find(name).gauge();
        return found == null ? Double.NaN : found.value();
    }

    private static Optional<ServiceBusReceivedMessage> onQueue(final String messageId) {
        return ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.NONE);
    }

    private Optional<Row> row() {
        return ProcessedLogTestSupport.row(ProcessedLogTestSupport.SOURCE, requestId);
    }

    @Test
    @DisplayName("an outage longer than the delivery budget still parks nothing, and processes on resume")
    void should_leave_the_message_recoverable_however_long_the_store_is_down() {
        try (ConfigurableApplicationContext context = ServiceTestSupport.startConsuming(Map.of());
             CapturedLog log = CapturedLog.capturing(SERVICE_LOGGERS)) {
            final MeterRegistry registry = context.getBean(MeterRegistry.class);

            PostgresTestSupport.pause();
            final String messageId =
                    ServiceTestSupport.publish(ServiceTestSupport.validBody(requestId, hearingId));

            // The claim of this suite, asserted first because it is the one that matters: through
            // the whole window the message stays on the queue and its delivery budget stops being
            // spent. The delivery that discovered the outage is spent, which the spec requires to be
            // handed back, and at most the one its hand-back races (see the bound's own note) - and
            // then intake stops and nothing takes it again. Five would put it on the dead-letter
            // queue. Held as a condition rather than checked once at the end, so a message that
            // visited the dead-letter queue and was replaced could not slip through.
            await().during(OUTAGE).atMost(OBSERVED_WITHIN).pollInterval(POLL)
                    .until(() -> onQueue(messageId)
                            .filter(message ->
                                    message.getDeliveryCount() <= DELIVERIES_AN_OUTAGE_MAY_COST)
                            .isPresent());
            assertThat(ServiceBusEmulatorTestSupport
                    .peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE))
                    .as("recoverable work must never be parked for an outage of ours")
                    .isEmpty();
            assertThat(gauge(registry, ProcessingMetrics.INTAKE_SUSPENDED))
                    .as("the budget survives because intake stopped, not because nothing arrived")
                    .isEqualTo(1);
            assertThat(log.events().stream()
                    .filter(event -> Level.ERROR.equals(event.getLevel()))
                    .filter(event -> event.getFormattedMessage()
                            .contains(ReasonCode.STORE_UNAVAILABLE.code())))
                    .as("the listener met the outage on one delivery; any second one was the race")
                    .hasSize(1);

            PostgresTestSupport.unpause();

            await().atMost(OBSERVED_WITHIN).pollInterval(POLL).until(() ->
                    row().filter(found -> RequestStatus.COMPLETED.name().equals(found.status()))
                            .isPresent());

            final Row processed =
                    ProcessedLogTestSupport.requireRow(ProcessedLogTestSupport.SOURCE, requestId);
            assertThat(processed.attempts())
                    .as("one run, on the first delivery the service was fit to take")
                    .isEqualTo(1);
            assertThat(gauge(registry, ProcessingMetrics.INTAKE_SUSPENDED)).isEqualTo(0);
            assertThat(ServiceBusEmulatorTestSupport
                    .peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE)).isEmpty();
        }
    }
}
