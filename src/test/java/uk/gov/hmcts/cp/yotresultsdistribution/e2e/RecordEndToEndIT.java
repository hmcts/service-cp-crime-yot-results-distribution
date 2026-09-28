package uk.gov.hmcts.cp.yotresultsdistribution.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.models.SubQueue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletionReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeadLetterReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.support.NowSubscriptionFixtures;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport.OutputRow;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport.Row;
import uk.gov.hmcts.cp.yotresultsdistribution.support.RegisterStackSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ServiceTestSupport;

/**
 * Spec US1, end to end: a hearing arrives on the queue and the register it produces is
 * <em>kept here</em>, with progression never asked for anything at all.
 *
 * <p>{@code YotResultsDistributionEndToEndIT} is the same sequence under {@code progression-post}, the
 * documented fallback shape. This is the sequence under {@code yotresultsdistribution.output=record}, which
 * is the default and therefore what a deployed pod does - so this suite takes
 * {@link RegisterStackSupport#settings()} plain, and the mode it runs in is the one nobody had to
 * choose.
 *
 * <p><strong>The whole middle is real.</strong> The bean graph is the one {@code PipelineConfig}
 * assembles, the payload comes out of a Redis container under the key the producer writes, the
 * subscriptions come over HTTP from something answering the reference-data contract, and the
 * register is serialised, validated against the vendored progression schemas and, here, written
 * into this service's own store. {@code DistributionPipelineTest} proves the recording stage with
 * every port doubled; what it cannot prove is that the adapters behind those ports agree with the
 * graph, which is the only thing that fails in a deployment.
 *
 * <p><strong>The assertion the doubles cannot make is the negative one.</strong> "Progression was
 * not called" is a claim about a socket, and only a suite that owns the socket can make it. The
 * stack's WireMock answers {@code add-court-register} with a 202 throughout, exactly as it does for
 * the POST suites, so a pipeline that had kept the 001 last stage would succeed rather than fail -
 * and be caught here by the count, which is the only place the count is zero on purpose.
 *
 * <p><strong>What a RECORDED row has to carry.</strong> After 002 this row <em>is</em> the register:
 * nothing downstream re-derives it, so the payload mapper, the file name and the recipient union all
 * read what was written here. The five facts asserted are the five nothing else can supply: the
 * document itself, the court centre's OU code (which the document does not carry), the defendant
 * type the hearing's own court application resolved to, the cutover flag as it stood, and the digest
 * of the stored document, which is what reconciliation compares.
 *
 * <p><strong>And the two other ways the sequence ends.</strong> A hearing shared again supersedes
 * the register it replaces in the recording transaction itself, rather than leaving a read-side
 * sweep to guess between two rows (research §8); and a document the frozen contract refuses never
 * becomes a row at all, because a register that could not legally be sent must not be one a batch
 * later picks up (FR-004).
 *
 * <p>An acceptance suite over assembled behaviour: it may legitimately pass on introduction, and its
 * first observed result is recorded rather than a red run.
 */
@DisplayName("recording the register, end to end")
class RecordEndToEndIT {

    /** The court house every subscription here selects, which is the base hearings' own. */
    private static final String OU_CODE = "B01LY00";

    /** The court centre the base hearings carry, and the one the recorded row keys on. */
    private static final UUID COURT_CENTRE_ID =
            UUID.fromString("853b1ff8-fc2a-44d1-a621-0cd16419f54a");

    /**
     * The day the base hearings' own share instant falls on.
     *
     * <p>{@code 2020-06-01T10:00:00Z}, which is the register's day and therefore the day the
     * subscriptions were read for - and, after 002, the day the batch groups by.
     */
    private static final LocalDate REGISTER_DAY = LocalDate.parse("2020-06-01");

    /** A second share of the same results, later on the same London day (C10, C12). */
    private static final Instant SHARED_AGAIN = Instant.parse("2020-06-01T16:45:00Z");

    private static final String YOUTH_HEARING = "hearing-with-surviving-youth-defendant.json";

    /** The hearing whose register the frozen contract refuses: a youth and a parent with no address. */
    private static final String ADDRESS_LESS_HEARING =
            "hearing-with-address-less-youth-and-parent.json";

    private static final Duration SETTLED_WITHIN = Duration.ofSeconds(90);
    private static final Duration POLL = Duration.ofSeconds(1);

    /** The one mapper the store serialises and reads the document with, and so the digest is over. */
    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    private static RegisterStackSupport stack;
    private static ConfigurableApplicationContext service;

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    @BeforeAll
    static void startTheWholeStack() {
        ProcessedLogTestSupport.dataSource();
        stack = RegisterStackSupport.start();
        // Plain settings(), not postingSettings(): record is the default, and the point of this
        // suite is the mode a pod runs in without anybody stating one.
        service = ServiceTestSupport.start(stack.settings());
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

    // --- the register that is kept ------------------------------------------------------------------

    @Test
    @DisplayName("a hearing with a youth, a court centre and a subscriber is recorded RECORDED, and "
            + "progression is never asked for anything")
    void should_record_the_register_and_ask_progression_for_nothing() {
        cache(RegisterStackSupport.payload(YOUTH_HEARING, hearingId));

        publishAndAwaitCompletion(requestId);

        assertThat(requireRow().completionReason())
                .as("the run is complete once the register is in this service's own store; there "
                        + "is nobody left to accept it")
                .isEqualTo(CompletionReason.RECORDED.value());
        assertThat(stack.registersPosted())
                .as("not one request reached progression - which the stack would have answered 202, "
                        + "so a pipeline still POSTing would pass every other assertion here")
                .isZero();

        final OutputRow output = requireOutputRow(requestId);
        assertThat(output.status()).isEqualTo("RECORDED");
        assertThat(output.responseCode())
                .as("nothing answered, because nothing was asked: the response code is 001's fact "
                        + "about a POST and a recorded register has no POST to have one")
                .isNull();
        assertThat(output.courtCentreId()).isEqualTo(COURT_CENTRE_ID);
        assertThat(output.courtCentreOuCode())
                .as("the one fact the batch needs that the document does not carry, so a row that "
                        + "did not record it leaves the file name and the render payload unbuildable")
                .isEqualTo(OU_CODE);
        assertThat(output.registerDate())
                .as("the day the register covers is the day its recipients were read for (C12), and "
                        + "after 002 it is also the day the batch groups by")
                .isEqualTo(REGISTER_DAY);
        assertThat(output.fileName()).isNotBlank();
        assertThat(output.anomalySummary())
                .as("this register was assembled without skipping anything")
                .isNull();

        final Recorded recorded = requireRecorded(requestId);
        assertThat(recorded.flagState())
                .as("this stack records and does not generate, so no flag reader is contributed and "
                        + "nothing has been read about the one lever: UNKNOWN is what that means "
                        + "rather than a placeholder, and a row not recorded under a known ON is "
                        + "one the nightly sweep leaves alone (research §12). A pod that generates "
                        + "labels its rows ON or OFF, which DistributionPipelineTest and "
                        + "MessageListenerSettlementTest pin at the two seams the label crosses")
                .isEqualTo(RecordedFlagState.UNKNOWN.name());
        assertThat(recorded.supersededAt())
                .as("nothing has replaced it, so it is the active register for its hearing and day")
                .isNull();
        assertThat(recorded.supersededBy()).isNull();
        assertThat(recorded.batchId())
                .as("and no batch has picked it up: assembly is the nightly job's, not the run's")
                .isNull();

        final CourtRegisterDocument stored = documentOf(recorded);
        assertThat(stored.hearingId())
                .as("the register that was stored is this hearing's, read back through the same "
                        + "contract mapper the store wrote it with")
                .isEqualTo(hearingId.toString());
        assertThat(stored.defendants())
                .as("and it is the register itself and not a marker: after 002 this row is the only "
                        + "place the document exists")
                .isNotEmpty();
        assertThat(recorded.digest())
                .as("the digest is of exactly the document that was stored, so reconciliation can "
                        + "tell whether a re-share changed the register rather than only its row")
                .isEqualTo(digestOf(MAPPER.writeValueAsString(stored)));
    }

    @Test
    @DisplayName("and the row carries the side of the court application the hearing put the child on")
    void should_record_the_defendant_type_the_hearing_resolved_to() {
        // Not the surviving-youth fixture: none of the six base hearings reaches a defendant type
        // at all, so a row asserted against one of them would be asserted against null and a
        // pipeline that had dropped the field would satisfy it. The appeal shape is the one
        // `synthetic__appellant` was recorded against, driven here from the queue rather than
        // handed to the resolver.
        cache(RegisterStackSupport.payloadOfAnAppealTheChildIsTheAppellantOn(hearingId));

        publishAndAwaitCompletion(requestId);

        final Recorded recorded = requireRecorded(requestId);
        assertThat(recorded.defendantType())
                .as("progression resolves this after the POST, from an aggregate this service does "
                        + "not read; 002 resolves it from the hearing's own court application and "
                        + "writes it where the render payload will find it (FR-002)")
                .isEqualTo("Appellant");
        assertThat(documentOf(recorded).defendantType())
                .as("and the column says what the document says: the row is not a second opinion "
                        + "about the register it holds")
                .isEqualTo(recorded.defendantType());
        assertThat(stack.registersPosted()).isZero();
    }

    // --- the hearing shared again ---------------------------------------------------------------------

    /**
     * Supersession settled where the register is written, rather than swept up where it is read.
     *
     * <p>Progression takes the greatest {@code register_time} per hearing at generation time, which
     * gets the same answer most nights and cannot say, between two reads, which of two registers
     * will not be sent. Here the recording that replaces a register is the event that says so, and
     * the row it replaced names its replacement - which is the only thing that leads from a dropped
     * register to the one that went instead.
     *
     * <p>Two requests, because a re-share is a request of its own: the guard is keyed on the
     * request, so nothing about the second is a duplicate. What makes it a replacement rather than a
     * second register is the hearing and the day, which are the same, and the share instant, which
     * is later.
     */
    @Nested
    @DisplayName("the same hearing, shared again")
    class ASecondShare {

        @Test
        void a_re_share_should_supersede_the_register_it_replaces_and_still_post_nothing() {
            final JsonNode firstShare = RegisterStackSupport.payload(YOUTH_HEARING, hearingId);
            cache(firstShare);

            publishAndAwaitCompletion(requestId);
            final Recorded first = requireRecorded(requestId);

            final UUID reShared = UUID.randomUUID();
            cache(RegisterStackSupport.sharedAgainAt(firstShare, SHARED_AGAIN));
            publishAndAwaitCompletion(reShared);

            final Recorded second = requireRecorded(reShared);
            final Recorded replaced = requireRecorded(requestId);

            assertThat(replaced.status())
                    .as("the later register wins on register_time, and the earlier row is kept as "
                            + "evidence of what was assembled before the results were shared again")
                    .isEqualTo("SUPERSEDED");
            assertThat(replaced.supersededAt())
                    .as("superseded_at is what takes the register out of the nightly sweep")
                    .isNotNull();
            assertThat(replaced.supersededBy())
                    .as("and superseded_by is the only thing that leads from a dropped register to "
                            + "its replacement")
                    .isEqualTo(second.outputId());
            assertThat(second.status())
                    .as("the register that won carries neither, or the sweep that reads "
                            + "superseded_at IS NULL would leave it out too and the day would be "
                            + "rendered without the hearing entirely")
                    .isEqualTo("RECORDED");
            assertThat(second.supersededAt()).isNull();
            assertThat(second.registerDate())
                    .as("both shares are the same court centre and the same London day, which is "
                            + "what makes the second a replacement rather than a register of its own")
                    .isEqualTo(first.registerDate());
            assertThat(stack.registersPosted())
                    .as("and neither share was sent anywhere: progression's own read-side "
                            + "max(register_time) sweep is what 002 replaces, not what it feeds")
                    .isZero();
        }
    }

    // --- the register that must never become a row -----------------------------------------------------

    @Test
    @DisplayName("a register the frozen contract refuses is parked, and no row is written for it")
    void should_record_nothing_for_a_register_the_frozen_contract_refuses() {
        cache(RegisterStackSupport.payload(ADDRESS_LESS_HEARING, hearingId));

        final String messageId =
                ServiceTestSupport.publish(ServiceTestSupport.validBody(requestId, hearingId));
        await().atMost(SETTLED_WITHIN).pollInterval(POLL).until(() ->
                row(requestId).filter(found -> RequestStatus.FAILED.name().equals(found.status()))
                        .isPresent());

        assertThat(requireRow().failureReason())
                .isEqualTo(ReasonCode.OUTBOUND_CONTRACT_VIOLATION.code());
        assertThat(ProcessedLogTestSupport.outputRow(ServiceTestSupport.SOURCE, requestId))
                .as("FR-004: the contract is enforced before the write, so a document that could "
                        + "never be a legal register never becomes a row a batch could pick up")
                .isEmpty();
        assertThat(stack.registersPosted())
                .as("and nothing reached progression either, which is where 001 would have "
                        + "discovered the refusal as a 400 and swallowed it (C1 with C29)")
                .isZero();

        final ServiceBusReceivedMessage parked = ServiceBusEmulatorTestSupport
                .peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE).orElseThrow();
        assertThat(parked.getDeadLetterReason())
                .as("parked here and now rather than carried to exhaustion: the same bytes meet the "
                        + "same refusal on every redelivery")
                .isEqualTo(DeadLetterReason.NON_TRANSIENT.label());
        assertThat(parked.getDeadLetterErrorDescription())
                .isEqualTo(ReasonCode.OUTBOUND_CONTRACT_VIOLATION.code());
        assertThat(ServiceBusEmulatorTestSupport.peekFor(messageId, SubQueue.NONE))
                .as("and it has left the queue it arrived on")
                .isEmpty();
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private void cache(final JsonNode payload) {
        stack.cached(hearingId, ServiceTestSupport.HEARING_DAY, payload);
    }

    private void publishAndAwaitCompletion(final UUID request) {
        ServiceTestSupport.publish(ServiceTestSupport.validBody(request, hearingId));
        await().atMost(SETTLED_WITHIN).pollInterval(POLL).until(() ->
                row(request).filter(found -> RequestStatus.COMPLETED.name().equals(found.status()))
                        .isPresent());
    }

    private static Optional<Row> row(final UUID request) {
        return ProcessedLogTestSupport.row(ServiceTestSupport.SOURCE, request);
    }

    private Row requireRow() {
        return ProcessedLogTestSupport.requireRow(ServiceTestSupport.SOURCE, requestId);
    }

    private static OutputRow requireOutputRow(final UUID request) {
        return ProcessedLogTestSupport.requireOutputRow(ServiceTestSupport.SOURCE, request);
    }

    /**
     * The register half of {@code processed_output}: the ten columns V2 added, as the row holds them.
     *
     * <p>Read here rather than through {@code ProcessedLogTestSupport.OutputRow}, which is the 001
     * submission view and is shared with the suites that still assert on it. These are the columns
     * that only exist because the register is now kept, and this is the only suite that drives all
     * of them from the queue.
     *
     * @param outputId     the row's own identity, and what a superseding row points back at
     * @param status       RECORDED, GENERATED, NOTIFIED or SUPERSEDED
     * @param registerDate the day the register covers, which is the batch key
     * @param defendantType the side of the hearing's court application, or {@code null}
     * @param flagState    the cutover flag as it stood when the row was written
     * @param document     the register itself, exactly as it will be rendered
     * @param digest       the SHA-256 of the stored document
     * @param batchId      the batch that picked the row up, or {@code null}
     * @param supersededAt when the row stopped being the active register, or {@code null}
     * @param supersededBy the register that replaced it, or {@code null}
     */
    private record Recorded(
            UUID outputId,
            String status,
            LocalDate registerDate,
            String defendantType,
            String flagState,
            String document,
            String digest,
            UUID batchId,
            Instant supersededAt,
            UUID supersededBy) {
    }

    private static Recorded requireRecorded(final UUID request) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT output_id, status, register_date, defendant_type,
                               recorded_flag_state, document, request_digest, batch_id,
                               superseded_at, superseded_by
                          FROM processed_output
                         WHERE source = :source AND request_id = :requestId
                        """)
                .param("source", ServiceTestSupport.SOURCE)
                .param("requestId", request)
                .query((rs, rowNumber) -> recorded(rs))
                .single();
    }

    private static Recorded recorded(final ResultSet rs) throws SQLException {
        return new Recorded(
                rs.getObject("output_id", UUID.class),
                rs.getString("status"),
                rs.getObject("register_date", LocalDate.class),
                rs.getString("defendant_type"),
                rs.getString("recorded_flag_state"),
                rs.getString("document"),
                rs.getString("request_digest"),
                rs.getObject("batch_id", UUID.class),
                instant(rs.getObject("superseded_at", OffsetDateTime.class)),
                rs.getObject("superseded_by", UUID.class));
    }

    private static Instant instant(final OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * The stored register, read back the way the batch half reads it.
     *
     * <p>Through the store's own contract mapper, because that is what the digest is taken over: the
     * column is {@code jsonb} and Postgres is free to reorder and respace what it holds, so hashing
     * the text the driver hands back would be hashing the database's formatting rather than the
     * register.
     *
     * @param recorded the row
     * @return the document it holds
     */
    private static CourtRegisterDocument documentOf(final Recorded recorded) {
        return MAPPER.readValue(recorded.document(), CourtRegisterDocument.class);
    }

    /**
     * The SHA-256 of a serialised document, in the lower-case hexadecimal the store writes.
     *
     * @param json the document as the store serialised it
     * @return the digest
     */
    private static String digestOf(final String json) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is not available", unavailable);
        }
    }
}
