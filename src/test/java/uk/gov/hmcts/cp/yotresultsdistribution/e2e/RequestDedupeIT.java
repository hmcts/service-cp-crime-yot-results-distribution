package uk.gov.hmcts.cp.yotresultsdistribution.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.models.SubQueue;
import java.time.Duration;
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
import uk.gov.hmcts.cp.yotresultsdistribution.application.IdempotencyGuard;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeadLetterReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.inbound.YotResultsDistributionMessageListener;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport.Row;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ServiceTestSupport;

/**
 * The <strong>second</strong> line of defence against a repeated request, end to end — defect-fix
 * C17.
 *
 * <p>{@code DuplicateDetectionIT} pins the first line: a republish under a broker identity the queue
 * has already seen is discarded before this service is involved at all. That is the queue's property,
 * not this service's, and it has a window. This suite is about what happens after the window — and
 * about every deliberate replay, because the resubmission runbook <em>requires</em> a fresh
 * {@code messageId} and the body's {@code requestId} to stay as it was.
 *
 * <p>So every send here carries a distinct broker identity, on purpose. Reusing one would let the
 * queue answer the question and the suite would pin nothing about the processed log. What is under
 * test is the real composition — the emulator, the real listener, the real guard, real Postgres —
 * rather than the guard in isolation, because that composition is what C17's row claims and the
 * guard's own suites (`IdempotencyGuardIT`, `IdempotencyCollisionIT`) deliberately do not reach: a
 * guard that decided correctly over a listener that ran the pipeline anyway, or settled the wrong
 * way, would leave both of those suites green.
 *
 * <p>The two cases are the two halves of the rule the processed-log key encodes.
 *
 * <ul>
 *   <li><strong>Same key, same content.</strong> The request has been done. The redelivery is
 *       acknowledged from the record — no run, no second POST, and not one column of the row moved.
 *       A second run would append a second register for the hearing, which progression records as a
 *       separate document.</li>
 *   <li><strong>Same key, different content.</strong> The producer has reused an identity for a
 *       different request. Absorbing it would silently drop one of the two hearings' registers, so
 *       it is parked with this service's own bounded reason and the record of the first request is
 *       left exactly as it was.</li>
 * </ul>
 *
 * <p>Acceptance over behaviour the guard already implements, so both cases may legitimately pass on
 * introduction: what they add is the end-to-end pinning C17 was missing, not a change to the
 * service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// The context owns a running consumer on the shared emulator queue. Closing it with the class stops
// that consumer competing with the suites that run after this one.
class RequestDedupeIT {

    private static final Duration PROCESSED_WITHIN = Duration.ofSeconds(60);

    /**
     * How often a broker-backed condition is re-asked. Deliberately a second rather than
     * Awaitility's default hundred milliseconds: every peek opens its own AMQP connection, and a
     * condition polled ten times a second for a minute opens six hundred of them — which the broker
     * client answers by starting a reactor thread apiece.
     */
    private static final Duration POLL = Duration.ofSeconds(1);

    private static final String ACKNOWLEDGED_ALREADY_COMPLETED =
            "Delivery acknowledged. reason=" + ReasonCode.ALREADY_COMPLETED.code();

    private static final String CLAIMED = "Request recorded and claimed.";

    private static final String REQUEST_ID = "requestId";

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    private CapturedLog deliveryLog;
    private CapturedLog guardLog;

    @DynamicPropertySource
    static void wireTheContainers(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
        registry.add("yotresultsdistribution.servicebus.connection-string",
                ServiceBusEmulatorTestSupport::connectionString);
        ServiceTestSupport.stubPayloadSource(registry);
        registry.add("yotresultsdistribution.progression.system-user-id",
                () -> ServiceTestSupport.SYSTEM_USER_ID);
        registry.add("yotresultsdistribution.results.system-user-id",
                () -> ServiceTestSupport.SYSTEM_USER_ID);
    }

    @BeforeEach
    void watchTheDeliveriesAndTheGuard() {
        deliveryLog = CapturedLog.capturing(YotResultsDistributionMessageListener.class);
        guardLog = CapturedLog.capturing(IdempotencyGuard.class);
    }

    @AfterEach
    void releaseTheLogs() {
        deliveryLog.close();
        guardLog.close();
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * Publishes under a <strong>fresh</strong> broker identity, which is what makes this suite about
     * the processed log rather than about the queue's duplicate detection.
     *
     * @return the identity the message was published under
     */
    private String publish(final String body) {
        return ServiceTestSupport.publish(body);
    }

    private String body() {
        return ServiceTestSupport.validBody(requestId, hearingId);
    }

    /**
     * The same key, one immutable field different — a different hearing under a reused identity.
     *
     * <p>{@code hearingId} is one of the four components the fingerprint is taken over, and it is
     * the one whose reuse actually costs something: two hearings, one key, and only one of them can
     * have a register unless the collision is refused.
     */
    private String bodyForADifferentHearing() {
        return ServiceTestSupport.validBody(requestId, UUID.randomUUID());
    }

    private Optional<Row> row() {
        return ProcessedLogTestSupport.row(ProcessedLogTestSupport.SOURCE, requestId);
    }

    private Row requireRow() {
        return ProcessedLogTestSupport.requireRow(ProcessedLogTestSupport.SOURCE, requestId);
    }

    /**
     * Waits until the first send has been processed to a completion, and returns the row it left.
     */
    private Row aCompletedRequest() {
        publish(body());
        await().atMost(PROCESSED_WITHIN).pollInterval(POLL).until(() -> row()
                .filter(found -> RequestStatus.COMPLETED.name().equals(found.status()))
                .isPresent());
        return requireRow();
    }

    /** How many lines this request's correlation carries, from the given capture. */
    private long linesSaying(final CapturedLog log, final String prefix) {
        return log.events().stream()
                .filter(event -> requestId.toString().equals(event.getMDCPropertyMap().get(REQUEST_ID)))
                .filter(event -> event.getFormattedMessage().startsWith(prefix))
                .count();
    }

    // --- the same request, delivered twice --------------------------------------------------------

    @Test
    @DisplayName("an identical request delivered again runs once and the second delivery completes")
    void should_acknowledge_an_identical_redelivery_without_running_it_a_second_time() {
        final Row afterFirstDelivery = aCompletedRequest();

        // A distinct identity, so the broker delivers it rather than discarding it — a deliberate
        // replay, or an ordinary duplicate arriving after the detection window.
        final String redelivered = publish(body());

        await().atMost(PROCESSED_WITHIN).pollInterval(POLL)
                .until(() -> linesSaying(deliveryLog, ACKNOWLEDGED_ALREADY_COMPLETED) == 1);

        assertThat(linesSaying(guardLog, CLAIMED))
                .as("one run: the claim is taken once, however many deliveries arrive")
                .isEqualTo(1);
        assertThat(requireRow())
                .as("and not one column of the record moved — attempts and updated_at included")
                .isEqualTo(afterFirstDelivery);
        assertThat(afterFirstDelivery.attempts())
                .as("the single run the record counted")
                .isEqualTo(1);
        assertThat(ServiceBusEmulatorTestSupport.peekFor(redelivered, SubQueue.NONE))
                .as("the second delivery was settled, not left on the queue to come round again")
                .isEmpty();
        assertThat(ServiceBusEmulatorTestSupport.peekFor(
                redelivered, SubQueue.DEAD_LETTER_QUEUE))
                .as("and completed rather than parked: a duplicate is not a fault")
                .isEmpty();
    }

    // --- the same key, a different request -----------------------------------------------------------

    @Test
    @DisplayName("a redelivery whose immutable content changed is parked, record untouched")
    void should_park_a_colliding_redelivery_without_touching_the_record() {
        final Row afterFirstDelivery = aCompletedRequest();

        final String colliding = publish(bodyForADifferentHearing());

        await().atMost(PROCESSED_WITHIN).pollInterval(POLL).until(() -> ServiceBusEmulatorTestSupport
                .peekFor(colliding, SubQueue.DEAD_LETTER_QUEUE).isPresent());

        final ServiceBusReceivedMessage parked = ServiceBusEmulatorTestSupport
                .peekFor(colliding, SubQueue.DEAD_LETTER_QUEUE).orElseThrow();
        assertThat(parked.getDeadLetterReason())
                .as("parked by this service under the collision reason, not by the broker's own rule")
                .isEqualTo(DeadLetterReason.COLLISION.label());
        assertThat(parked.getDeadLetterErrorDescription())
                .as("a bounded code, never the field that differed and never a fragment of the body")
                .isEqualTo(ReasonCode.IDEMPOTENCY_COLLISION.code());

        assertThat(linesSaying(guardLog, CLAIMED))
                .as("the colliding request never entered the state machine")
                .isEqualTo(1);
        assertThat(requireRow())
                .as("the first hearing's record is what a collision must not cost")
                .isEqualTo(afterFirstDelivery);
    }
}
