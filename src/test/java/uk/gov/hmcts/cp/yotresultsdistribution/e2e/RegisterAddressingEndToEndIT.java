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
import tools.jackson.databind.JsonNode;
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
 * Who the register is addressed to, and what a reference-data outage is worth.
 *
 * <p>The split this suite proves is the one the design calls CS1, and it is the difference between
 * two answers the legacy cannot tell apart. {@code CourtRegisterSubscriptions/index.js:22} reads an
 * absent {@code nowSubscriptions} and an unreachable reference-data context the same way, and both
 * end the orchestration reporting success. Here:
 *
 * <ul>
 *   <li><strong>reference data that will not answer</strong> is a transient failure — the delivery is
 *       handed back and a redelivery, which is answered, posts the register;</li>
 *   <li><strong>reference data that answers with nobody</strong> is a completion under its own
 *       reason, which is this flow's commonest legitimate outcome and has to stay
 *       distinguishable from the outage above.</li>
 * </ul>
 *
 * <p>The third case is defect C31, whole, through the running service.
 * {@code CourtRegisterSubscriptions/index.js:49} matches on {@code registerDefendants[0].vocabulary}
 * — one vocabulary, taken from whichever defendant the hearing happened to gather first. A register
 * carries every defendant, adults included, because the youth filter runs a stage later; so a hearing
 * whose first defendant is an adult is matched against adult vocabulary, and a subscription keyed on
 * {@code youthDefendant} — which is what every court-register subscription is keyed on — matches
 * nothing at all. The register is built, the run reports success, and a child's register reaches
 * nobody. The fixed pipeline matches per defendant, and the assertion is the one that would have
 * caught it: the POST happens, and the document on the wire carries the youth and only the youth.
 *
 * <p>An acceptance suite over assembled behaviour: it may legitimately pass on introduction, and its
 * first observed result is recorded rather than a red run.
 */
@DisplayName("addressing the register, end to end")
class RegisterAddressingEndToEndIT {

    private static final String OU_CODE = "B01LY00";
    private static final String YOUTH_HEARING = "hearing-with-surviving-youth-defendant.json";
    private static final String ADULT_FIRST_HEARING = "hearing-with-adult-first-youth-second.json";

    private static final Duration SETTLED_WITHIN = Duration.ofSeconds(90);
    private static final Duration POLL = Duration.ofSeconds(1);

    /** The attempts one now-subscriptions read makes, from this stack's settings. */
    private static final int ATTEMPTS_PER_DELIVERY = 2;

    private static final int UNAVAILABLE = 503;

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
        log = CapturedLog.capturing("uk.gov.hmcts.cp.yotresultsdistribution");
    }

    @AfterEach
    void forgetThisHearing() {
        log.close();
        stack.notCached(hearingId, ServiceTestSupport.HEARING_DAY);
    }

    // --- the two halves of the CS1 split ---------------------------------------------------------------

    @Test
    @DisplayName("reference data that will not answer hands the delivery back, and the redelivery "
            + "posts the register")
    void should_hand_back_a_register_it_could_not_address_and_post_it_on_redelivery() {
        cache(YOUTH_HEARING);
        stack.subscriptionsRefuseThenAnswer(UNAVAILABLE, ATTEMPTS_PER_DELIVERY,
                NowSubscriptionFixtures.youthCourtRegisterSubscription(OU_CODE));

        final String messageId = publish();
        awaitStatus(RequestStatus.COMPLETED);

        assertThat(failuresAboutThisRequest())
                .as("the outage is settled once, at the layer that decided what to do about it")
                .singleElement()
                .satisfies(failure -> assertThat(failure.getFormattedMessage())
                        .startsWith("Pipeline run failed.")
                        .contains(ReasonCode.REFERENCE_DATA_UNAVAILABLE.code())
                        .contains("classification=transient"));

        final Row completed = requireRow();
        assertThat(completed.completionReason()).isEqualTo(CompletionReason.SUBMITTED.value());
        assertThat(completed.attempts())
                .as("two runs: the one that could not ask, and the one that could")
                .isEqualTo(2);
        assertThat(stack.registersPosted())
                .as("a register that could not be addressed was never sent to nobody")
                .isEqualTo(1);
        assertThat(ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE))
                .as("a context that was down is a context that may be up next time")
                .isEmpty();
    }

    @Test
    @DisplayName("reference data that answers with nobody completes under its own reason")
    void should_complete_no_subscriptions_when_reference_data_answers_with_nobody() {
        cache(YOUTH_HEARING);
        stack.subscriptionsInForce();

        publish();
        awaitStatus(RequestStatus.COMPLETED);

        assertThat(requireRow().completionReason())
                .as("an empty answer is an answer, and it is not the same event as an outage")
                .isEqualTo(CompletionReason.NO_SUBSCRIPTIONS.value());
        assertThat(failuresAboutThisRequest())
                .as("and nothing failed, so nothing is reported as having failed")
                .isEmpty();
        assertThat(stack.registersPosted()).isZero();
    }

    // --- C31 ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an adult ahead of a youth still produces the youth's register")
    void should_address_a_register_whose_first_defendant_is_an_adult() {
        cache(ADULT_FIRST_HEARING);
        stack.subscriptionsInForce(NowSubscriptionFixtures.youthCourtRegisterSubscription(OU_CODE));

        publish();
        awaitStatus(RequestStatus.COMPLETED);

        assertThat(requireRow().completionReason())
                .as("the legacy matches defendant[0]'s vocabulary alone, so this hearing reaches "
                        + "nobody and reports success (C31)")
                .isEqualTo(CompletionReason.SUBMITTED.value());
        assertThat(stack.registersPosted()).isEqualTo(1);

        final JsonNode posted = stack.postedRegister(0);
        assertThat(posted.get("recipients").size())
                .as("the subscriber the youth's vocabulary satisfied")
                .isEqualTo(1);
        assertThat(posted.get("defendants").size())
                .as("the adult was matched against and then filtered out: a court register carries "
                        + "children only, and the matching runs before that filter")
                .isEqualTo(1);
    }

    // --- helpers ------------------------------------------------------------------------------------------

    private void cache(final String fixture) {
        stack.cached(hearingId, ServiceTestSupport.HEARING_DAY,
                RegisterStackSupport.payload(fixture, hearingId));
    }

    private String publish() {
        return ServiceTestSupport.publish(ServiceTestSupport.validBody(requestId, hearingId));
    }

    private void awaitStatus(final RequestStatus status) {
        await().atMost(SETTLED_WITHIN).pollInterval(POLL).until(() ->
                row().filter(found -> status.name().equals(found.status())).isPresent());
    }

    /**
     * Every failure reported about this request, read through the MDC rather than by matching the
     * identifier inside the text — the MDC is what a log index makes searchable.
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
