package uk.gov.hmcts.cp.yotresultsdistribution.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.models.SubQueue;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.inbound.YotResultsDistributionMessageListener;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport.Row;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ServiceTestSupport;

/**
 * The first line of defence, characterised: a republish under an identity the broker has already
 * seen never reaches this service at all.
 *
 * <p>Nothing here is this service's behaviour. Duplicate detection is a property of the queue, set
 * in {@code docker/servicebus-emulator/config.json} and provisioned the same way in a deployed
 * environment, and the suite exists to pin it: two independent rules depend on the broker really
 * discarding a repeated identity.
 *
 * <p>The first is the reason the resubmission runbook insists on a <strong>fresh</strong> message
 * identity. A support replay that reuses the identity of the message it is replaying is not rejected,
 * not parked and not logged — it is silently dropped, and the operator watches for a register that
 * never arrives. The service cannot detect that case, which is why it is a documented operational
 * rule rather than a code path.
 *
 * <p>The second is the honest scope of the processed log. It is the <em>second</em> line of defence,
 * catching the duplicates the broker's window has expired on; if the broker discarded nothing, every
 * ordinary republish would arrive and the guard would be doing work the queue was configured to make
 * unnecessary.
 *
 * <p>Acceptance over committed configuration, so it may legitimately pass on introduction: what it
 * records is the observed behaviour of the emulator this repository pins, not a change to this
 * service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// The context owns a running consumer on the shared emulator queue. Closing it with the class stops
// that consumer competing with the suites that run after this one.
class DuplicateDetectionIT {

    private static final Duration PROCESSED_WITHIN = Duration.ofSeconds(60);

    /**
     * How often a broker-backed condition is re-asked. Deliberately a second rather than
     * Awaitility's default hundred milliseconds: every peek opens its own AMQP connection, and a
     * condition polled ten times a second for a minute opens six hundred of them — which the broker
     * client answers by starting a reactor thread apiece.
     */
    private static final Duration POLL = Duration.ofSeconds(1);

    /**
     * How long the republished identity must stay unseen. A broker that was not discarding it would
     * deliver it within milliseconds, so this is a window in which the wrong behaviour shows itself,
     * not a race being outwaited.
     */
    private static final Duration STAYS_UNSEEN_FOR = Duration.ofSeconds(10);

    private static final String DELIVERY_RECEIVED = "Delivery received.";

    private static String connectionString;

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    /** The identity used for both sends — the whole point of the suite. */
    private final String messageId = "RESULTS:" + UUID.randomUUID();

    private CapturedLog deliveryLog;

    @DynamicPropertySource
    static void wireTheContainers(final DynamicPropertyRegistry registry) {
        connectionString = ServiceBusEmulatorTestSupport.connectionString();
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
        registry.add("yotresultsdistribution.servicebus.connection-string", () -> connectionString);
        ServiceTestSupport.stubPayloadSource(registry);
        registry.add("yotresultsdistribution.progression.system-user-id",
                () -> ServiceTestSupport.SYSTEM_USER_ID);
        registry.add("yotresultsdistribution.results.system-user-id",
                () -> ServiceTestSupport.SYSTEM_USER_ID);
    }

    @BeforeEach
    void watchEveryDelivery() {
        deliveryLog = CapturedLog.capturing(YotResultsDistributionMessageListener.class);
    }

    @AfterEach
    void releaseTheDeliveryLog() {
        deliveryLog.close();
    }

    // --- helpers ---------------------------------------------------------------------------

    private String body() {
        return ServiceTestSupport.validBody(requestId, hearingId);
    }

    /**
     * Sends the same body under the same identity, every time.
     */
    private void publish() {
        try (ServiceBusSenderClient sender = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .sender()
                .queueName(ServiceBusEmulatorTestSupport.QUEUE_NAME)
                .buildClient()) {
            sender.sendMessage(
                    new ServiceBusMessage(BinaryData.fromString(body())).setMessageId(messageId));
        }
    }

    private Optional<Row> row() {
        return ProcessedLogTestSupport.row(ProcessedLogTestSupport.SOURCE, requestId);
    }

    private int deliveriesSeen() {
        return (int) deliveryLog.events().stream()
                .filter(event -> requestId.toString().equals(event.getMDCPropertyMap().get("requestId")))
                .filter(event -> event.getFormattedMessage().startsWith(DELIVERY_RECEIVED))
                .count();
    }

    // --- the broker's own dedupe ------------------------------------------------------------------

    @Test
    @DisplayName("a republish under an identity the broker has seen never reaches the service")
    void should_discard_a_republished_identity_inside_the_detection_window() {
        publish();

        await().atMost(PROCESSED_WITHIN).pollInterval(POLL).until(() -> row()
                .filter(found -> RequestStatus.COMPLETED.name().equals(found.status()))
                .isPresent());
        final Row afterFirstSend =
                ProcessedLogTestSupport.requireRow(ProcessedLogTestSupport.SOURCE, requestId);
        final Instant firstWrite = afterFirstSend.updatedAt();

        // The same identity again, well inside the queue's five-minute detection window.
        publish();

        await().during(STAYS_UNSEEN_FOR).atMost(PROCESSED_WITHIN).pollInterval(POLL)
                .until(() -> deliveriesSeen() == 1);

        assertThat(deliveriesSeen())
                .as("the service saw one delivery; the second send was discarded before it")
                .isEqualTo(1);
        assertThat(ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.NONE))
                .as("the discarded send is not sitting on the queue waiting to be delivered")
                .isEmpty();
        assertThat(ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE))
                .as("a discarded duplicate is not parked either — the broker drops it silently")
                .isEmpty();

        final Row afterSecondSend =
                ProcessedLogTestSupport.requireRow(ProcessedLogTestSupport.SOURCE, requestId);
        assertThat(afterSecondSend.attempts()).isEqualTo(1);
        assertThat(afterSecondSend.updatedAt())
                .as("nothing reached the processed log to write to it")
                .isEqualTo(firstWrite);
    }
}
