package uk.gov.hmcts.cp.yotresultsdistribution.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.azure.messaging.servicebus.models.SubQueue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HexFormat;
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
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.support.NowSubscriptionFixtures;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport.OutputRow;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport.Row;
import uk.gov.hmcts.cp.yotresultsdistribution.support.RegisterStackSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ServiceTestSupport;

/**
 * Spec SC-103: a hearing arrives on the queue and a court register reaches progression — and every
 * legitimate way that does <em>not</em> happen is a different, named answer.
 *
 * <p>This is the quickstart sequence, run as a test. {@code quickstart.md} tells a reader to run this
 * class to see the service work, so what it does is what the quickstart claims: it boots the whole
 * context against the Service Bus emulator, Postgres and a real payload cache, publishes a request,
 * and asserts that the POST reached progression, that it was answered {@code 202}, that the request
 * completed {@code submitted} and that {@code processed_output} holds a POSTED row carrying that
 * status and the digest of exactly the bytes that were sent.
 *
 * <p><strong>It states the mode it is about.</strong> {@code yotresultsdistribution.output} defaults to
 * {@code record}, under which the register is written into this service's own store and progression
 * is not called at all; every assertion below is about the POST, so this suite boots the pod under
 * {@code progression-post} - the documented fallback shape - rather than inheriting a default that
 * would leave it watching a socket nothing was sent to. The default mode's own end-to-end sequence
 * is {@code RecordEndToEndIT}.
 *
 * <p><strong>Nothing between the queue and the socket is doubled.</strong> The bean graph is the one
 * {@code PipelineConfig} assembles, the payload comes out of a Redis container under the key the
 * producer writes, the subscriptions come over HTTP from something answering the reference-data
 * contract, and the register is serialised, validated against the vendored progression schemas and
 * POSTed to something answering progression's. {@code PipelineCompositionTest} proves the same graph
 * with the four outward ports doubled; what it cannot prove is that the adapters behind those ports
 * agree with the graph, which is the only thing that fails in a deployment.
 *
 * <p><strong>The four no-op reasons are the other half, and for this flow they are the ordinary
 * half.</strong> Two of them — {@code no-subscriptions} and {@code no-youth-defendants} — are this
 * flow's commonest legitimate outcomes, so an undifferentiated success is not a simplification but
 * the reason a court centre nobody subscribes to and a pipeline that has quietly stopped working look
 * identical from outside. That is defect C33, and the assertion that ends it is that four runs which
 * all completed and all sent nothing are told apart in the processed log by
 * {@code completion_reason} alone.
 *
 * <p><strong>And what a duplicate costs, counted rather than inferred.</strong> The one claim a
 * component-level dedupe suite cannot make is the one an operator needs: that a second delivery of a
 * completed request touched nothing outside the pod. Here the three contexts are real and their
 * requests are countable, so "no second POST" is a fact about the socket that would have received
 * it.
 *
 * <p>An acceptance suite over assembled behaviour: it may legitimately pass on introduction, and its
 * first observed result is recorded rather than a red run.
 */
@DisplayName("the court register, end to end")
class YotResultsDistributionEndToEndIT {

    /** The court house every subscription here selects, which is the base hearings' own. */
    private static final String OU_CODE = "B01LY00";

    /** The court centre the base hearings carry, and the one the output row records. */
    private static final UUID COURT_CENTRE_ID =
            UUID.fromString("853b1ff8-fc2a-44d1-a621-0cd16419f54a");

    /**
     * The day the base hearings' own share instant falls on.
     *
     * <p>{@code 2020-06-01T10:00:00Z}, which is the register's day and therefore the day the
     * subscriptions were read for — the value {@code processed_output.register_date} records (C12).
     * British Summer Time is the trap the C10/C12 pair is about, and it does not move this one.
     */
    private static final LocalDate REGISTER_DAY = LocalDate.parse("2020-06-01");

    private static final Duration SETTLED_WITHIN = Duration.ofSeconds(90);
    private static final Duration POLL = Duration.ofSeconds(1);

    private static final int ACCEPTED = 202;

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

    // --- the register that goes -------------------------------------------------------------------

    @Test
    @DisplayName("a hearing with a youth, a court centre and a subscriber posts one register and "
            + "records it POSTED")
    void should_post_the_register_and_record_what_progression_answered() {
        stack.cached(hearingId, ServiceTestSupport.HEARING_DAY,
                RegisterStackSupport.payload("hearing-with-surviving-youth-defendant.json",
                        hearingId));
        stack.subscriptionsInForce(NowSubscriptionFixtures.youthCourtRegisterSubscription(OU_CODE));

        publishAndAwaitCompletion();

        assertThat(requireRow().completionReason())
                .as("the run is only submitted once progression has accepted the register")
                .isEqualTo(CompletionReason.SUBMITTED.value());
        assertThat(stack.registersPosted())
                .as("exactly one POST: add-court-register appends a register per call, so a second "
                        + "one inside a run is a second register for the hearing")
                .isEqualTo(1);

        final OutputRow output =
                ProcessedLogTestSupport.requireOutputRow(ServiceTestSupport.SOURCE, requestId);
        assertThat(output.status()).isEqualTo("POSTED");
        assertThat(output.responseCode())
                .as("what progression actually answered, which is the fact C1's legacy swallows")
                .isEqualTo(ACCEPTED);
        assertThat(output.requestDigest())
                .as("the digest is of exactly the bytes that went, so reconciliation can tell "
                        + "whether a re-send changed the body")
                .isEqualTo(digestOf(stack.postedBody(0)));
        assertThat(output.courtCentreId()).isEqualTo(COURT_CENTRE_ID);
        assertThat(output.courtCentreOuCode()).isEqualTo(OU_CODE);
        assertThat(output.registerDate())
                .as("the day the register covers is the day its recipients were read for (C12)")
                .isEqualTo(REGISTER_DAY);
        assertThat(output.fileName()).isNotBlank();
        assertThat(output.anomalySummary())
                .as("this register was assembled without skipping anything")
                .isNull();
    }

    // --- the request that arrives twice ------------------------------------------------------------

    /**
     * A duplicate costs the world outside this pod nothing — asserted against the world, not against
     * the log.
     *
     * <p>{@code RequestDedupeIT} makes the same claim over a service whose payload port is a stub and
     * whose submission port is one too, so what it can show is that the guard decided correctly and
     * the delivery was settled. What it cannot show is the claim an operator actually cares about:
     * that the second delivery <em>touched nothing</em> — no cache read turning into a query, no
     * subscription read, and above all no second POST of a command progression appends. Those are
     * facts about three sockets, and only a suite that owns the sockets can count them.
     *
     * <p>So the payload is deliberately left out of the cache and served by the query side, which
     * turns the fetch into an HTTP call this fixture can count. The first delivery is worth exactly
     * one of each: one payload read, one now-subscriptions read, one register POSTed. The second is
     * worth none of them, and the row it did not run is unchanged down to {@code updated_at}.
     *
     * <p>A fresh broker identity on the second send, so the queue's own duplicate detection cannot be
     * what settles it: the processed log has to be.
     */
    @Test
    @DisplayName("a duplicate delivery reaches no payload source, no reference data and no "
            + "progression")
    void should_touch_nothing_outside_the_pod_when_a_completed_request_is_delivered_again() {
        stack.queryHolds(hearingId,
                RegisterStackSupport.payload("hearing-with-surviving-youth-defendant.json",
                        hearingId));
        stack.subscriptionsInForce(NowSubscriptionFixtures.youthCourtRegisterSubscription(OU_CODE));

        publishAndAwaitCompletion();
        final Row afterFirstDelivery = requireRow();

        assertThat(afterFirstDelivery.completionReason())
                .isEqualTo(CompletionReason.SUBMITTED.value());
        assertThat(stack.queriesFor(hearingId)).isEqualTo(1);
        assertThat(stack.subscriptionReads()).isEqualTo(1);
        assertThat(stack.registersPosted()).isEqualTo(1);

        final String duplicate =
                ServiceTestSupport.publish(ServiceTestSupport.validBody(requestId, hearingId));
        await().atMost(SETTLED_WITHIN).pollInterval(POLL).until(() ->
                ServiceBusEmulatorTestSupport.peekFor(duplicate, SubQueue.NONE).isEmpty());

        assertThat(stack.queriesFor(hearingId))
                .as("the duplicate asked the query side for nothing: the guard answers from the "
                        + "record, before the payload port is reached at all")
                .isEqualTo(1);
        assertThat(stack.subscriptionReads())
                .as("and addressed nobody a second time")
                .isEqualTo(1);
        assertThat(stack.registersPosted())
                .as("and above all posted nothing: add-court-register appends, so a second POST is "
                        + "a second register for the hearing and no downstream sweep calls it one")
                .isEqualTo(1);
        assertThat(requireRow())
                .as("not one column of the record moved — attempts and updated_at included")
                .isEqualTo(afterFirstDelivery);
        assertThat(ServiceBusEmulatorTestSupport.peekFor(duplicate, SubQueue.DEAD_LETTER_QUEUE))
                .as("and it was completed rather than parked: a duplicate is not a fault")
                .isEmpty();
    }

    // --- the four ways a run legitimately produces nothing ------------------------------------------

    @Test
    @DisplayName("a group-proceedings hearing completes under its own reason, having asked reference "
            + "data nothing")
    void should_complete_group_proceedings_without_addressing_anybody() {
        stack.cached(hearingId, ServiceTestSupport.HEARING_DAY,
                RegisterStackSupport.payload("hearing-with-group-proceedings.json", hearingId));
        stack.subscriptionsInForce(NowSubscriptionFixtures.youthCourtRegisterSubscription(OU_CODE));

        publishAndAwaitCompletion();

        assertCompletedWithNothingSent(CompletionReason.GROUP_PROCEEDINGS);
    }

    @Test
    @DisplayName("a hearing that gathers nobody completes no-defendants")
    void should_complete_no_defendants_when_the_hearing_gathers_nobody() {
        stack.cached(hearingId, ServiceTestSupport.HEARING_DAY,
                RegisterStackSupport.payloadWithNothingToGather(hearingId));
        stack.subscriptionsInForce(NowSubscriptionFixtures.youthCourtRegisterSubscription(OU_CODE));

        publishAndAwaitCompletion();

        assertCompletedWithNothingSent(CompletionReason.NO_DEFENDANTS);
    }

    @Test
    @DisplayName("a register nobody is subscribed to completes no-subscriptions")
    void should_complete_no_subscriptions_when_nothing_in_force_matches() {
        stack.cached(hearingId, ServiceTestSupport.HEARING_DAY,
                RegisterStackSupport.payload("hearing-with-surviving-youth-defendant.json",
                        hearingId));
        stack.subscriptionsInForce();

        publishAndAwaitCompletion();

        assertCompletedWithNothingSent(CompletionReason.NO_SUBSCRIPTIONS);
    }

    @Test
    @DisplayName("an addressed register with no child on it completes no-youth-defendants")
    void should_complete_no_youth_defendants_when_the_youth_filter_empties_the_register() {
        stack.cached(hearingId, ServiceTestSupport.HEARING_DAY,
                RegisterStackSupport.payload("hearing-with-complete-court-centre.json", hearingId));
        // Keyed on any defendant, so the register is addressed first and found empty afterwards:
        // the youth filter runs a stage after the matching, and a youth-keyed subscription would
        // have answered no-subscriptions instead.
        stack.subscriptionsInForce(NowSubscriptionFixtures.forAnyDefendant(OU_CODE));

        publishAndAwaitCompletion();

        assertCompletedWithNothingSent(CompletionReason.NO_YOUTH_DEFENDANTS);
    }

    // --- helpers -----------------------------------------------------------------------------------

    private void publishAndAwaitCompletion() {
        ServiceTestSupport.publish(ServiceTestSupport.validBody(requestId, hearingId));
        await().atMost(SETTLED_WITHIN).pollInterval(POLL).until(() ->
                row().filter(found -> RequestStatus.COMPLETED.name().equals(found.status()))
                        .isPresent());
    }

    /**
     * A run that ended well and sent nothing, told apart from the other three by its reason alone.
     *
     * <p>The absent output row is the second half and is worth asserting: the output cardinality is
     * 0..1 per request, and a claimed row left behind by a run that never posted would be read by the
     * next delivery as evidence that a POST may have been made.
     *
     * @param reason the reason this run should have completed under
     */
    private void assertCompletedWithNothingSent(final CompletionReason reason) {
        final Row completed = requireRow();
        assertThat(completed.completionReason()).isEqualTo(reason.value());
        assertThat(completed.failureReason()).isNull();
        assertThat(stack.registersPosted())
                .as("a run that produced no register posts nothing at all")
                .isZero();
        assertThat(ProcessedLogTestSupport.outputRow(ServiceTestSupport.SOURCE, requestId))
                .as("and claims no output row, which a later delivery would read as a possible send")
                .isEmpty();
    }

    private Optional<Row> row() {
        return ProcessedLogTestSupport.row(ServiceTestSupport.SOURCE, requestId);
    }

    private Row requireRow() {
        return ProcessedLogTestSupport.requireRow(ServiceTestSupport.SOURCE, requestId);
    }

    /**
     * The SHA-256 of a body, in the lower-case hexadecimal the submission adapter writes.
     *
     * @param body the bytes that were sent
     * @return the digest
     */
    private static String digestOf(final String body) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is not available", unavailable);
        }
    }
}
