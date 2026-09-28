package uk.gov.hmcts.cp.yotresultsdistribution.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.azure.messaging.servicebus.models.SubQueue;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletionReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;
import uk.gov.hmcts.cp.yotresultsdistribution.support.NowSubscriptionFixtures;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport.Row;
import uk.gov.hmcts.cp.yotresultsdistribution.support.RegisterStackSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ServiceTestSupport;

/**
 * Where the hearing comes from, and what happens when it comes from nowhere.
 *
 * <p>{@code CachedHearingPayloadAdapterTest} proves the ordering against two mocks and
 * {@code LettuceHearingPayloadCacheIT} proves one read against a real server. Neither can say what a
 * cold cache is <em>worth</em>: the answer is a settlement, and a settlement is decided three layers
 * above the adapter. This suite holds the whole of it — cache, query side, pipeline, guard, broker —
 * to the three answers the pair can give.
 *
 * <ul>
 *   <li>the cache holds the hearing, and the query side is never asked at all — the ordering the
 *       function app has and the round trip it saves, proven through the running service;</li>
 *   <li>the cache is cold and the query side holds it, and the run completes exactly as if the cache
 *       had answered;</li>
 *   <li><strong>both miss</strong>, which is defect C32: the legacy stops there, silently and
 *       successfully. Here it is a transient failure, the delivery is handed back, and the
 *       redelivery — which finds the payload — completes the request.</li>
 * </ul>
 *
 * <p>The third case is made deterministic by the query side rather than by the suite: a 404 means
 * "not held" and is never retried inside a run, so a stub that answers 404 once and holds the hearing
 * afterwards produces exactly one failed delivery. Seeding the cache partway through instead would be
 * racing the broker, and the broker wins in well under a second.
 *
 * <p>An acceptance suite over assembled behaviour: it may legitimately pass on introduction, and its
 * first observed result is recorded rather than a red run.
 */
@DisplayName("the payload source, end to end")
class PayloadSourceEndToEndIT {

    private static final String OU_CODE = "B01LY00";
    private static final String YOUTH_HEARING = "hearing-with-surviving-youth-defendant.json";

    private static final Duration SETTLED_WITHIN = Duration.ofSeconds(90);
    private static final Duration POLL = Duration.ofSeconds(1);

    private static RegisterStackSupport stack;
    private static ConfigurableApplicationContext service;

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    private CapturedLog log;

    @BeforeAll
    static void startTheWholeStack() {
        ProcessedLogTestSupport.dataSource();
        stack = RegisterStackSupport.start();
        service = ServiceTestSupport.start(stack.postingSettings());
    }

    @AfterAll
    static void stopTheWholeStack() {
        // The context owns a running consumer on the shared emulator queue; closing it here stops it
        // competing with the suites that run after this one.
        service.close();
        stack.close();
    }

    @BeforeEach
    void forgetTheLastCase() {
        stack.reset();
        stack.subscriptionsInForce(NowSubscriptionFixtures.youthCourtRegisterSubscription(OU_CODE));
        log = CapturedLog.capturing("uk.gov.hmcts.cp.yotresultsdistribution");
    }

    @AfterEach
    void forgetThisHearing() {
        log.close();
        stack.notCached(hearingId, ServiceTestSupport.HEARING_DAY);
    }

    // --- the two ways a payload arrives -------------------------------------------------------------

    @Test
    @DisplayName("a hearing the cache holds is posted without the query side being asked")
    void should_complete_from_the_cache_without_asking_the_query_side() {
        stack.cached(hearingId, ServiceTestSupport.HEARING_DAY,
                RegisterStackSupport.payload(YOUTH_HEARING, hearingId));

        publish();
        awaitStatus(RequestStatus.COMPLETED);

        assertThat(requireRow().completionReason()).isEqualTo(CompletionReason.SUBMITTED.value());
        assertThat(stack.queriesFor(hearingId))
                .as("for this hearing, not for any: the queue is shared, and a neighbour's message "
                        + "reaching this consumer does ask the query side")
                .isZero();
        assertThat(stack.registersPosted()).isEqualTo(1);
    }

    @Test
    @DisplayName("a cold cache is answered by the query side, and the run completes the same way")
    void should_complete_from_the_query_side_when_the_cache_is_cold() {
        stack.queryHolds(hearingId, RegisterStackSupport.payload(YOUTH_HEARING, hearingId));

        publish();
        awaitStatus(RequestStatus.COMPLETED);

        assertThat(requireRow().completionReason()).isEqualTo(CompletionReason.SUBMITTED.value());
        assertThat(stack.queriesFor(hearingId))
                .as("once, after both key forms missed")
                .isEqualTo(1);
        assertThat(stack.registersPosted()).isEqualTo(1);
    }

    // --- and the way it does not (C32) ---------------------------------------------------------------

    @Test
    @DisplayName("a cache miss and a query miss hand the delivery back, and the redelivery completes it")
    void should_hand_back_a_hearing_nobody_holds_and_complete_it_on_redelivery() {
        stack.queryHoldsNothingThenHolds(
                hearingId, RegisterStackSupport.payload(YOUTH_HEARING, hearingId));

        final String messageId = publish();
        awaitStatus(RequestStatus.COMPLETED);

        // Two lines, from the two layers that have something different to say, and both of them are
        // the point: the adapter reports that neither source held the hearing — the state C32's
        // legacy has no word for at all — and the pipeline reports what it decided that is worth.
        // Neither carries anything from inside the payload; the bounded reason and the correlation
        // identifiers are the whole of it.
        assertThat(failuresAboutThisRequest().stream().map(ILoggingEvent::getFormattedMessage))
                .as("the pair is discovered once and settled once, with a bounded reason each time")
                .hasSize(2)
                .satisfiesExactly(
                        discovered -> assertThat(discovered)
                                .startsWith("The hearing payload is available from neither the "
                                        + "cache nor the query API.")
                                .contains(ReasonCode.PAYLOAD_UNAVAILABLE.code()),
                        settled -> assertThat(settled)
                                .startsWith("Pipeline run failed.")
                                .contains(ReasonCode.PAYLOAD_UNAVAILABLE.code())
                                .contains("classification=transient"));

        final Row completed = requireRow();
        assertThat(completed.completionReason())
                .as("the redelivery found the payload and the register went")
                .isEqualTo(CompletionReason.SUBMITTED.value());
        assertThat(completed.attempts())
                .as("two runs: the one that found nothing, and the one that did")
                .isEqualTo(2);
        assertThat(completed.failureReason())
                .as("cleared by the completion, which is why the failure has to be visible in the log")
                .isNull();
        assertThat(stack.registersPosted())
                .as("the delivery that found nothing posted nothing, so there is one register and "
                        + "not two")
                .isEqualTo(1);
        assertThat(ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE))
                .as("a hearing nobody held yet is not a hearing nobody will ever hold, so the "
                        + "delivery was handed back rather than parked")
                .isEmpty();
    }

    // --- helpers --------------------------------------------------------------------------------------

    private String publish() {
        return ServiceTestSupport.publish(ServiceTestSupport.validBody(requestId, hearingId));
    }

    private void awaitStatus(final RequestStatus status) {
        await().atMost(SETTLED_WITHIN).pollInterval(POLL).until(() ->
                row().filter(found -> status.name().equals(found.status())).isPresent());
    }

    /**
     * Every failure reported about this request, read through the MDC.
     *
     * <p>Through the MDC rather than by matching the identifier inside the text, because the MDC is
     * what the JSON encoder puts on every line and therefore what a log index makes searchable.
     *
     * @return the ERROR lines correlated to this request
     */
    private List<ILoggingEvent> failuresAboutThisRequest() {
        return log.events().stream()
                .filter(event -> Level.ERROR.equals(event.getLevel()))
                .filter(event ->
                        requestId.toString().equals(event.getMDCPropertyMap().get("requestId")))
                .toList();
    }

    private Optional<Row> row() {
        return ProcessedLogTestSupport.row(ServiceTestSupport.SOURCE, requestId);
    }

    private Row requireRow() {
        return ProcessedLogTestSupport.requireRow(ServiceTestSupport.SOURCE, requestId);
    }
}
