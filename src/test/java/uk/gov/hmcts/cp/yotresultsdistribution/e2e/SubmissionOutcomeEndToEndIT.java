package uk.gov.hmcts.cp.yotresultsdistribution.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.models.SubQueue;
import java.time.Duration;
import java.time.Instant;
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
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeadLetterReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.support.NowSubscriptionFixtures;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport.OutputRow;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport.Row;
import uk.gov.hmcts.cp.yotresultsdistribution.support.RegisterStackSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ServiceTestSupport;

/**
 * What progression's answer is worth, and what happens to a register progression is never shown.
 *
 * <p>This is defect C1 end to end. {@code ProcessOutboundCourtRegister/index.js:17-25} catches every
 * error the POST can raise, logs it and returns; the status is never inspected. A register that was
 * delivered and a register that was lost are the same run, the same log line and the same absence of
 * a record. Every case here is a way that stops being true:
 *
 * <ul>
 *   <li>a register the frozen contract refuses reaches <strong>no socket at all</strong> — the C29
 *       half, and the assertion only this suite can make, because "nothing was posted" is a claim
 *       about the one server that would have received it;</li>
 *   <li>a {@code 400} is a recorded, parked failure carrying the status progression answered;</li>
 *   <li>a {@code 500} followed by a {@code 202} is one register, retried and accepted (C3);</li>
 *   <li>a {@code 200} is <strong>not</strong> success: the contract says 202 and nothing else, and
 *       anything else answering is something other than the command endpoint;</li>
 *   <li>a refusal on every delivery is parked when the queue's budget runs out, carrying the identity
 *       of the delivery that spent it;</li>
 *   <li>a hearing shared twice is two requests, two POSTs and two rows — because
 *       {@code add-court-register} appends rather than replaces, and absorbing the duplicate is
 *       progression's read-side job and not this service's.</li>
 * </ul>
 *
 * <p>Every case reads {@code processed_output} as well as {@code processed_request}, because the two
 * answer different questions and only the second one matters to a redelivery: the request-level row
 * says whether this request was dealt with, and the output row says whether this hearing's register
 * has already gone.
 *
 * <p>An acceptance suite over assembled behaviour: it may legitimately pass on introduction, and its
 * first observed result is recorded rather than a red run.
 */
@DisplayName("the submission, end to end")
class SubmissionOutcomeEndToEndIT {

    private static final String OU_CODE = "B01LY00";
    private static final String YOUTH_HEARING = "hearing-with-surviving-youth-defendant.json";
    private static final String ADDRESS_LESS_HEARING =
            "hearing-with-address-less-youth-and-parent.json";

    private static final Duration SETTLED_WITHIN = Duration.ofSeconds(90);

    /** Five deliveries, each with a broker round trip and a retry policy inside it. */
    private static final Duration PARKED_WITHIN = Duration.ofSeconds(120);

    private static final Duration POLL = Duration.ofSeconds(1);

    /** The queue's delivery budget, as declared in the emulator configuration the suites mount. */
    private static final int PERMITTED_DELIVERIES = 5;

    /** The attempts one submission makes against a retryable answer, from this stack's settings. */
    private static final int ATTEMPTS_PER_DELIVERY = 2;

    private static final int ACCEPTED = 202;
    private static final int REFUSED = 400;
    private static final int WRONG_SUCCESS = 200;
    private static final int UNAVAILABLE = 503;

    private static RegisterStackSupport stack;
    private static ConfigurableApplicationContext service;

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

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
    void addressOneRegisterToOneSubscriber() {
        stack.reset();
        stack.subscriptionsInForce(NowSubscriptionFixtures.youthCourtRegisterSubscription(OU_CODE));
    }

    @AfterEach
    void forgetThisHearing() {
        stack.notCached(hearingId, ServiceTestSupport.HEARING_DAY);
    }

    // --- the register that is never sent (C29 with C1) ------------------------------------------------

    @Test
    @DisplayName("a register the frozen contract refuses is parked, and progression is never asked "
            + "to take it")
    void should_refuse_an_invalid_register_before_any_socket_is_opened() {
        cache(ADDRESS_LESS_HEARING);

        final String messageId = publish();
        awaitStatus(RequestStatus.FAILED);

        final Row failed = requireRow();
        assertThat(failed.failureReason())
                .isEqualTo(ReasonCode.OUTBOUND_CONTRACT_VIOLATION.code());
        assertThat(stack.registersPosted())
                .as("not one request reached progression — the whole of C29, which the legacy "
                        + "discovers as a 400 and then swallows")
                .isZero();
        assertThat(ProcessedLogTestSupport.outputRow(ServiceTestSupport.SOURCE, requestId))
                .as("and no output row was claimed, so no later delivery reads this as a possible send")
                .isEmpty();
        assertParkedBy(messageId, DeadLetterReason.NON_TRANSIENT,
                ReasonCode.OUTBOUND_CONTRACT_VIOLATION);
    }

    // --- what progression answers ---------------------------------------------------------------------

    @Test
    @DisplayName("a 400 is a recorded, parked failure carrying the status progression answered")
    void should_record_and_park_a_register_progression_refused() {
        cache(YOUTH_HEARING);
        stack.progressionAnswers(REFUSED);

        final String messageId = publish();
        awaitStatus(RequestStatus.FAILED);

        assertThat(requireRow().failureReason()).isEqualTo(ReasonCode.SUBMISSION_REJECTED.code());
        assertThat(stack.registersPosted())
                .as("attempted once: a refusal is answered identically however often it is asked")
                .isEqualTo(1);

        final OutputRow output = requireOutputRow();
        assertThat(output.status()).isEqualTo("FAILED");
        assertThat(output.responseCode())
                .as("the status is the fact the legacy loses, and it is worth more after a failure "
                        + "than after a success")
                .isEqualTo(REFUSED);
        assertThat(output.requestDigest())
                .as("the digest of what was attempted stays behind, for reconciliation")
                .isNotBlank();
        assertParkedBy(messageId, DeadLetterReason.NON_TRANSIENT, ReasonCode.SUBMISSION_REJECTED);
    }

    @Test
    @DisplayName("a 500 then a 202 is one register, retried and accepted")
    void should_retry_a_transient_refusal_and_record_the_acceptance() {
        cache(YOUTH_HEARING);
        stack.progressionAnswers(UNAVAILABLE, ACCEPTED);

        publish();
        awaitStatus(RequestStatus.COMPLETED);

        assertThat(requireRow().completionReason()).isEqualTo(CompletionReason.SUBMITTED.value());
        assertThat(stack.registersPosted())
                .as("two attempts inside one delivery, which is the retry the legacy's wrapper is "
                        + "bypassed for entirely (C3)")
                .isEqualTo(ATTEMPTS_PER_DELIVERY);

        final OutputRow output = requireOutputRow();
        assertThat(output.status()).isEqualTo("POSTED");
        assertThat(output.responseCode())
                .as("what was answered last, not what was answered first")
                .isEqualTo(ACCEPTED);
    }

    @Test
    @DisplayName("a 200 is not an acceptance: the contract says 202 and nothing else")
    void should_refuse_a_success_that_is_not_the_one_the_contract_names() {
        cache(YOUTH_HEARING);
        stack.progressionAnswers(WRONG_SUCCESS);

        final String messageId = publish();
        awaitStatus(RequestStatus.FAILED);

        assertThat(requireRow().failureReason())
                .as("something other than the command endpoint answered, which is not a register "
                        + "progression has")
                .isEqualTo(ReasonCode.SUBMISSION_NOT_ACCEPTED.code());
        assertThat(requireOutputRow().responseCode()).isEqualTo(WRONG_SUCCESS);
        assertThat(requireOutputRow().status()).isEqualTo("FAILED");
        assertParkedBy(messageId, DeadLetterReason.NON_TRANSIENT,
                ReasonCode.SUBMISSION_NOT_ACCEPTED);
    }

    @Test
    @DisplayName("a progression that never recovers parks the request with the identity of the "
            + "delivery that spent the budget")
    void should_park_the_request_when_every_delivery_meets_the_same_outage() {
        cache(YOUTH_HEARING);
        stack.progressionAnswers(UNAVAILABLE);

        final String messageId = publish();
        await().atMost(PARKED_WITHIN).pollInterval(POLL).until(() ->
                ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE)
                        .isPresent());

        final Row parked = requireRow();
        assertThat(parked.status()).isEqualTo(RequestStatus.FAILED.name());
        assertThat(parked.attempts()).isEqualTo(PERMITTED_DELIVERIES);
        assertThat(parked.failureReason()).isEqualTo(ReasonCode.SUBMISSION_TRANSIENT.code());
        assertThat(parked.exhaustedMessageId())
                .as("which delivery ran out of budget, written in the statement that parked it")
                .isEqualTo(messageId);
        assertThat(stack.registersPosted())
                .as("every delivery spent its whole retry policy before handing the message back")
                .isEqualTo(PERMITTED_DELIVERIES * ATTEMPTS_PER_DELIVERY);
        assertThat(requireOutputRow().status())
                .as("the last attempt's outcome, and never POSTED — nothing was ever accepted")
                .isEqualTo("FAILED");
        assertParkedBy(messageId, DeadLetterReason.EXHAUSTED, ReasonCode.DELIVERY_LIMIT_EXHAUSTED);
    }

    // --- the same hearing, shared twice ----------------------------------------------------------------

    @Test
    @DisplayName("a hearing shared again is a second request, a second POST and a second row")
    void should_treat_a_re_share_as_a_register_of_its_own() {
        cache(YOUTH_HEARING);

        publish();
        awaitStatus(RequestStatus.COMPLETED);

        final UUID reShared = UUID.randomUUID();
        ServiceTestSupport.publish(ServiceTestSupport.validBody(
                reShared, hearingId, Instant.parse("2026-08-31T16:45:00Z")));
        await().atMost(SETTLED_WITHIN).pollInterval(POLL).until(() ->
                ProcessedLogTestSupport.row(ServiceTestSupport.SOURCE, reShared)
                        .filter(found -> RequestStatus.COMPLETED.name().equals(found.status()))
                        .isPresent());

        assertThat(stack.registersPosted())
                .as("the idempotency guard is keyed on the request, and a re-share is a different "
                        + "request about the same hearing")
                .isEqualTo(2);
        assertThat(requireOutputRow().status()).isEqualTo("POSTED");
        assertThat(ProcessedLogTestSupport.requireOutputRow(ServiceTestSupport.SOURCE, reShared)
                .status())
                .as("two POSTED rows, and the duplicate register is absorbed downstream by "
                        + "progression's own max(register_time) sweep, exactly like any re-share")
                .isEqualTo("POSTED");
    }

    // --- helpers ----------------------------------------------------------------------------------------

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
     * The message is on the dead-letter queue, parked by this service and for its own reason.
     *
     * <p>Both halves matter. A message the broker parks once the delivery budget runs out carries the
     * broker's reason and leaves no record behind it, so the reason on the queue is the difference
     * between a request that was parked and a request that was dropped; and a parked message still
     * sitting on the queue it arrived on would be delivered again.
     *
     * @param messageId the delivery's identity
     * @param reason    the bounded dead-letter reason
     * @param code      the bounded reason code carried as the description
     */
    private static void assertParkedBy(final String messageId, final DeadLetterReason reason,
            final ReasonCode code) {
        final ServiceBusReceivedMessage parked = ServiceBusEmulatorTestSupport
                .peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE).orElseThrow();
        assertThat(parked.getDeadLetterReason()).isEqualTo(reason.label());
        assertThat(parked.getDeadLetterErrorDescription()).isEqualTo(code.code());
        assertThat(ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.NONE))
                .as("and has left the queue it arrived on")
                .isEmpty();
    }

    private Optional<Row> row() {
        return ProcessedLogTestSupport.row(ServiceTestSupport.SOURCE, requestId);
    }

    private Row requireRow() {
        return ProcessedLogTestSupport.requireRow(ServiceTestSupport.SOURCE, requestId);
    }

    private OutputRow requireOutputRow() {
        return ProcessedLogTestSupport.requireOutputRow(ServiceTestSupport.SOURCE, requestId);
    }
}
