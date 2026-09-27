package uk.gov.hmcts.cp.yotresultsdistribution.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.azure.messaging.servicebus.models.SubQueue;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * A run that takes longer than its claim guarantees it stops itself, and the redelivery gets a whole
 * fresh budget.
 *
 * <p>The reason this matters more here than in the service it was cloned from is that progression's
 * {@code add-court-register} <strong>appends</strong> a register rather than replacing one. A runner
 * still working when its claim becomes reclaimable is a second runner starting the same request, and
 * a second POST is a second register for the hearing — not a wasted retry. So the run carries one
 * cumulative budget across the fetch, the reference-data read and the send, and reads what is left of
 * it before each of them.
 *
 * <p><strong>What this suite may inject is decided by the startup validation, and it is the finding
 * worth recording.</strong> {@code PropertiesValidator} budgets every step's connect and read
 * timeouts, plus the waits its retry policy can take, plus a fixed thirty-second margin, and refuses
 * to start unless the total is strictly shorter than the processing deadline. On any configuration
 * this service will start on, therefore, a delay long enough to reach the deadline is a delay the
 * read timeout cuts short first — which is a payload or reference-data failure, and a different
 * scenario. A fixed delay cannot produce an overrun at all.
 *
 * <p>What can, and what actually happens in production, is a response that arrives <em>slowly</em>
 * rather than late: a body delivered in pieces trips no read timeout, because each individual read
 * returns promptly, and nothing but the run's own budget notices that the whole exchange outlasted
 * it. That is what WireMock's chunked dribble injects here, over a reference-data read whose own
 * timeout is two seconds and whose chunks arrive a second apart.
 *
 * <p>The settings are this suite's own and are deliberately at the floor: the fixed margin means no
 * deadline below thirty seconds is startable, so a forty-second deadline with every timeout in
 * hundreds of milliseconds is the fastest honest version of this scenario there is.
 *
 * <p>An acceptance suite over assembled behaviour: it may legitimately pass on introduction, and its
 * first observed result is recorded rather than a red run.
 */
@DisplayName("the run's own deadline, end to end")
class RunDeadlineEndToEndIT {

    private static final String OU_CODE = "B01LY00";
    private static final String YOUTH_HEARING = "hearing-with-surviving-youth-defendant.json";

    /** The run's budget, at the floor the fixed thirty-second margin leaves. */
    private static final Duration DEADLINE = Duration.ofSeconds(40);

    /** How long the slow answer takes to arrive: longer than the whole budget. */
    private static final int DRIBBLE_MILLIS = 42_000;

    /** In pieces a second apart, which the two-second read timeout never notices. */
    private static final int DRIBBLE_CHUNKS = 42;

    private static final Duration SETTLED_WITHIN = Duration.ofSeconds(180);
    private static final Duration POLL = Duration.ofSeconds(1);

    private static RegisterStackSupport stack;
    private static ConfigurableApplicationContext service;

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    @BeforeAll
    static void startTheWholeStack() {
        ProcessedLogTestSupport.dataSource();
        stack = RegisterStackSupport.start();
        service = ServiceTestSupport.start(atTheFloorOfTheBudget());
    }

    @AfterAll
    static void stopTheWholeStack() {
        // The context owns a running consumer on the shared emulator queue; closing it here stops it
        // competing with the suites that run after this one.
        service.close();
        stack.close();
    }

    /**
     * The stack's settings, with every timeout at the floor and the deadline just above the margin.
     *
     * <p>The arithmetic startup checks: two cache reads of 400ms, one query attempt of 1.2s, one
     * reference-data attempt of 2.2s and one POST attempt of 1.2s is 5.4s, and 5.4s plus the fixed
     * 30s margin is 35.4s — strictly shorter than the 40s deadline, which is in turn strictly shorter
     * than the 45s claim lease, with the broker's lock renewal covering the deadline plus its own 30s
     * margin.
     *
     * @return the settings
     */
    private static Map<String, String> atTheFloorOfTheBudget() {
        final Map<String, String> settings = new LinkedHashMap<>(stack.postingSettings());
        settings.put("yotresultsdistribution.claim.processing-deadline", DEADLINE.toString());
        settings.put("yotresultsdistribution.claim.lease", "45s");
        settings.put("yotresultsdistribution.servicebus.max-auto-lock-renew-duration", "75s");
        settings.put("yotresultsdistribution.payload.redis.connect-timeout", "200ms");
        settings.put("yotresultsdistribution.payload.redis.command-timeout", "200ms");
        settings.put("yotresultsdistribution.payload.fallback.max-attempts", "1");
        settings.put("yotresultsdistribution.payload.fallback.connect-timeout", "200ms");
        settings.put("yotresultsdistribution.payload.fallback.read-timeout", "1s");
        settings.put("yotresultsdistribution.referencedata.max-attempts", "1");
        settings.put("yotresultsdistribution.referencedata.connect-timeout", "200ms");
        settings.put("yotresultsdistribution.referencedata.read-timeout", "2s");
        settings.put("yotresultsdistribution.progression.max-attempts", "1");
        settings.put("yotresultsdistribution.progression.connect-timeout", "200ms");
        settings.put("yotresultsdistribution.progression.read-timeout", "1s");
        return settings;
    }

    @Test
    @DisplayName("a run that outlasts its budget is handed back, and the redelivery posts the register")
    void should_abandon_a_run_that_outlasts_its_budget_and_complete_it_on_redelivery() {
        try (CapturedLog log = CapturedLog.capturing("uk.gov.hmcts.cp.yotresultsdistribution")) {
            stack.cached(hearingId, ServiceTestSupport.HEARING_DAY,
                    RegisterStackSupport.payload(YOUTH_HEARING, hearingId));
            stack.subscriptionsDribbleThenAnswer(DRIBBLE_CHUNKS, DRIBBLE_MILLIS,
                    NowSubscriptionFixtures.youthCourtRegisterSubscription(OU_CODE));

            final String messageId =
                    ServiceTestSupport.publish(ServiceTestSupport.validBody(requestId, hearingId));
            await().atMost(SETTLED_WITHIN).pollInterval(POLL).until(() ->
                    row().filter(found -> RequestStatus.COMPLETED.name().equals(found.status()))
                            .isPresent());

            assertThat(failuresAboutThisRequest(log))
                    .as("the overrun is reported once, under its own bounded reason — not as a "
                            + "payload fault, not as a reference-data fault, and not as silence")
                    .singleElement()
                    .satisfies(overrun -> assertThat(overrun.getFormattedMessage())
                            .startsWith("Pipeline run failed.")
                            .contains(ReasonCode.PROCESSING_DEADLINE_EXCEEDED.code())
                            .contains("classification=transient"));

            final Row completed = requireRow();
            assertThat(completed.completionReason())
                    .as("the redelivery had the whole budget again, and used a fraction of it")
                    .isEqualTo(CompletionReason.SUBMITTED.value());
            assertThat(completed.attempts())
                    .as("two runs: the one that ran out of time, and the one that did not")
                    .isEqualTo(2);
            assertThat(stack.registersPosted())
                    .as("the overrun was noticed before anything was sent, which is the whole "
                            + "purpose of a budget on a command progression appends")
                    .isEqualTo(1);
            assertThat(ServiceBusEmulatorTestSupport
                    .peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE))
                    .as("a slow dependency is not a poison message: abandoned, never parked")
                    .isEmpty();
        } finally {
            stack.notCached(hearingId, ServiceTestSupport.HEARING_DAY);
        }
    }

    // --- helpers ------------------------------------------------------------------------------------

    /**
     * Every failure reported about this request, read through the MDC rather than by matching the
     * identifier inside the text — the MDC is what a log index makes searchable.
     *
     * @param log what the service said while this case ran
     * @return the ERROR lines correlated to this request
     */
    private List<ILoggingEvent> failuresAboutThisRequest(final CapturedLog log) {
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
