package uk.gov.hmcts.cp.yotresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
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
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PersonalDataMarkers;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport.Row;
import uk.gov.hmcts.cp.yotresultsdistribution.support.RegisterStackSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ServiceTestSupport;

/**
 * What this service may write down, asked of the adapters that actually hold the data.
 *
 * <p>{@code TelemetryPrivacyTest} runs the same claim over the assembled bean graph with the four
 * outward ports doubled. That covers the transformation — the twelve mappers, the fragment builder,
 * the contract validator — which is where most of a register's content is handled. What it cannot
 * cover is the four classes it replaces with doubles, and those four are exactly the ones holding a
 * child's data in one hand and somebody else's text in the other:
 *
 * <ul>
 *   <li>the Lettuce cache, which reads the claim-check payload and whose parser quotes the token it
 *       choked on;</li>
 *   <li>the results-query client, whose {@code RestClientResponseException} carries the whole
 *       response body in its message;</li>
 *   <li>the reference-data client, which is handed the recipients a register is addressed to;</li>
 *   <li>the progression gateway, which is handed whatever progression says about a document it
 *       refused — and a refusal from {@code add-court-register} is about a court register.</li>
 * </ul>
 *
 * <p>So this suite runs the live ones: a real Redis container holding a marked payload under the key
 * the producer writes, and one HTTP server answering the results-query, reference-data and
 * progression contracts. Five legs, and the failing four are the point — a success leg proves the
 * happy path is quiet, and quiet is easy when nothing has gone wrong. What leaks personal data is an
 * adapter explaining itself.
 *
 * <p><strong>What "any level" means here, exactly.</strong> The unit suite lowers the root logger to
 * TRACE, which is the right reading where nothing but this service is running. It is the wrong one
 * here: an HTTP client, a cache client and a broker client all log the bytes they carried at TRACE,
 * because that is what TRACE is for, and a suite that turned it on across a live stack would be
 * asserting that third-party libraries do not do what they are designed to do. The shipped
 * configuration puts the root at INFO and {@code TelemetryPrivacyTest} refuses any level below it,
 * so those lines cannot exist in a deployed pod. This capture is therefore everything <em>any</em>
 * logger writes at the shipped threshold — which is what a deployed pod emits — plus every level of
 * {@code uk.gov.hmcts.cp.yotresultsdistribution}, the one package whose lines this repository writes and
 * could be asked to turn up for a local diagnosis.
 *
 * <p>An acceptance suite over assembled behaviour: it may legitimately pass on introduction, and its
 * first observed result is recorded rather than a red run.
 */
@DisplayName("what the live adapters may write down")
class TelemetryPrivacyIT {

    /** The court house the marked subscription selects, which is the base hearings' own. */
    private static final String OU_CODE = "B01LY00";

    private static final String YOUTH_HEARING = "hearing-with-surviving-youth-defendant.json";

    /** The package whose every level is captured, because every line in it is this repo's. */
    private static final String THIS_SERVICE = "uk.gov.hmcts.cp.yotresultsdistribution";

    private static final Duration SETTLED_WITHIN = Duration.ofSeconds(90);
    private static final Duration POLL = Duration.ofSeconds(1);

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
    void forgetTheLastCase() {
        stack.reset();
    }

    @AfterEach
    void forgetThisHearing() {
        stack.notCached(hearingId, ServiceTestSupport.HEARING_DAY);
    }

    // --- the register that goes --------------------------------------------------------------------

    @Test
    @DisplayName("a register assembled from a live cache and posted over a live socket names nobody")
    void should_write_nothing_identifying_while_a_register_is_fetched_addressed_and_posted() {
        try (CapturedLog log = CapturedLog.everythingAndAllOf(THIS_SERVICE)) {
            stack.cached(hearingId, ServiceTestSupport.HEARING_DAY, markedPayload());
            stack.subscriptionsInForce(PersonalDataMarkers.markedSubscription(OU_CODE));

            publishAndAwait(RequestStatus.COMPLETED);

            assertThat(requireRow().completionReason())
                    .as("the whole path ran: cache read, subscriptions read, twelve mappers, "
                            + "contract validation and a POST that progression accepted")
                    .isEqualTo(CompletionReason.SUBMITTED.value());
            assertThat(stack.registersPosted()).isEqualTo(1);
            assertThat(stack.postedBody(0))
                    .as("and the child really was on the document, so the silence below is about "
                            + "the log rather than about an empty register")
                    .contains(PersonalDataMarkers.CHILD_NAME);
            assertNothingIdentifyingWasWritten(log);
        }
    }

    // --- the four ways an adapter is invited to quote what it read ---------------------------------

    @Test
    @DisplayName("a cached payload that will not parse is reported by type, never by its contents")
    void should_write_nothing_identifying_when_the_cache_holds_something_unparseable() {
        try (CapturedLog log = CapturedLog.everythingAndAllOf(THIS_SERVICE)) {
            // A truncated write: the everyday shape of an unparseable cache entry, and the token the
            // parser would quote is a child's name.
            stack.cachedRaw(hearingId, ServiceTestSupport.HEARING_DAY,
                    "{\"hearing\":{\"defendants\":[{\"firstName\":\""
                            + PersonalDataMarkers.CHILD_NAME + "\"");
            stack.queryHoldsNothing(hearingId);

            publishAndAwaitAFailedRun();

            assertNothingIdentifyingWasWritten(log);
        }
    }

    @Test
    @DisplayName("a query side that refuses in words of its own is reported by status alone")
    void should_write_nothing_identifying_when_the_query_side_explains_a_refusal() {
        try (CapturedLog log = CapturedLog.everythingAndAllOf(THIS_SERVICE)) {
            // A 500 body reaches the client inside RestClientResponseException's own message, which
            // is the commonest way a payload fragment escapes: nobody has to log the body for it to
            // be logged.
            stack.queryAnswers(hearingId, 500,
                    "{\"error\":\"could not read the hearing of "
                            + PersonalDataMarkers.CHILD_NAME + ", born "
                            + PersonalDataMarkers.DATE_OF_BIRTH + "\"}");

            publishAndAwaitAFailedRun();

            assertNothingIdentifyingWasWritten(log);
        }
    }

    @Test
    @DisplayName("reference data's own words about a subscriber are not written down")
    void should_write_nothing_identifying_when_reference_data_explains_a_refusal() {
        try (CapturedLog log = CapturedLog.everythingAndAllOf(THIS_SERVICE)) {
            stack.cached(hearingId, ServiceTestSupport.HEARING_DAY, markedPayload());
            stack.subscriptionsAnswer(503,
                    "{\"error\":\"" + PersonalDataMarkers.RECIPIENT_ORGANISATION
                            + " at " + PersonalDataMarkers.RECIPIENT_EMAIL + " is unavailable\"}");

            publishAndAwaitAFailedRun();

            assertNothingIdentifyingWasWritten(log);
        }
    }

    @Test
    @DisplayName("progression's own words about a refused register are not written down")
    void should_write_nothing_identifying_when_progression_refuses_the_command() {
        try (CapturedLog log = CapturedLog.everythingAndAllOf(THIS_SERVICE)) {
            stack.cached(hearingId, ServiceTestSupport.HEARING_DAY, markedPayload());
            stack.subscriptionsInForce(PersonalDataMarkers.markedSubscription(OU_CODE));
            // A refusal from add-court-register is a refusal about a court register, so its body is
            // the one response in this service that can be relied upon to name a child.
            stack.progressionAnswers(422,
                    "{\"error\":\"defendant " + PersonalDataMarkers.CHILD_NAME + " of "
                            + PersonalDataMarkers.CHILD_ADDRESS + " is not known\"}");

            publishAndAwait(RequestStatus.FAILED);

            assertThat(stack.registersPosted())
                    .as("the refusal really was met: a leg that never posted would prove nothing "
                            + "about what a refusal is worth")
                    .isEqualTo(1);
            assertNothingIdentifyingWasWritten(log);
        }
    }

    // --- helpers -----------------------------------------------------------------------------------

    /**
     * Waits for the request to reach a state, and returns nothing but the wait.
     *
     * @param status the state the processed log should reach
     */
    private void publishAndAwait(final RequestStatus status) {
        ServiceTestSupport.publish(ServiceTestSupport.validBody(requestId, hearingId));
        await().atMost(SETTLED_WITHIN).pollInterval(POLL).until(() ->
                row().filter(found -> status.name().equals(found.status())).isPresent());
    }

    /**
     * Waits for the run to have failed once and been recorded.
     *
     * <p>Once, not exhausted: what these legs are about is what an adapter says while explaining
     * itself, and it says it on the first delivery. Waiting for the delivery budget to run out would
     * add four redeliveries of the same evidence to every case.
     */
    private void publishAndAwaitAFailedRun() {
        ServiceTestSupport.publish(ServiceTestSupport.validBody(requestId, hearingId));
        await().atMost(SETTLED_WITHIN).pollInterval(POLL).until(() -> row()
                .filter(found -> found.failureReason() != null)
                .isPresent());
    }

    /**
     * The claim, made of every marker at once and of the capture as a whole.
     *
     * <p>Rendered rather than formatted: a stack trace reaches a log index exactly as a message
     * does, and an exception somebody else wrote is the commonest way a fragment of a payload
     * escapes.
     *
     * @param log everything anything wrote while the leg ran
     */
    private void assertNothingIdentifyingWasWritten(final CapturedLog log) {
        // A backstop rather than the guard. What actually stops a leg passing on silence is the
        // leg itself: the two success-shaped ones assert the register was posted and what the body
        // carried, and the three failing ones await a processed_request row with a failureReason -
        // so a stub that stopped producing its refusal times out rather than sweeping a quiet log
        // clean. This is why the enumeration TelemetryPrivacyTest keeps, and the key-collision
        // check that goes with it, are deliberately not shared here (Phase 8 finding 16): that
        // machinery exists to stop "the drive reached every declared statement" passing vacuously,
        // and this suite makes no such claim - it sweeps what five live legs wrote for markers, and
        // each leg proves separately that it ran.
        assertThat(linesFromThisService(log))
                .as("a leg that logged nothing would satisfy the assertions below vacuously")
                .isNotEmpty();
        final List<String> written = log.renderings();
        for (final String marker : PersonalDataMarkers.PERSONAL) {
            assertThat(written)
                    .as("a child's own details reached the log index: %s", marker)
                    .noneMatch(line -> line.contains(marker));
        }
        for (final String marker : PersonalDataMarkers.RECIPIENT) {
            assertThat(written)
                    .as("a subscriber's contact details reached the log index: %s", marker)
                    .noneMatch(line -> line.contains(marker));
        }
    }

    /**
     * Every line this service wrote at the level a deployed pod writes at.
     *
     * <p>Used only to refuse a vacuous pass: a leg that logged nothing would satisfy a
     * "no marker appears" assertion perfectly. The assertions themselves read the whole capture,
     * this service's lower levels included.
     */
    private static List<ILoggingEvent> linesFromThisService(final CapturedLog log) {
        return log.events().stream()
                .filter(event -> event.getLoggerName().startsWith(THIS_SERVICE))
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                .toList();
    }

    /** The base hearing, re-identified as this case's own, with its child made of markers. */
    private JsonNode markedPayload() {
        return PersonalDataMarkers.marked(RegisterStackSupport.payload(YOUTH_HEARING, hearingId));
    }

    private Optional<Row> row() {
        return ProcessedLogTestSupport.row(ServiceTestSupport.SOURCE, requestId);
    }

    private Row requireRow() {
        return ProcessedLogTestSupport.requireRow(ServiceTestSupport.SOURCE, requestId);
    }
}
