package uk.gov.hmcts.cp.yotresultsdistribution.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.notificationnotify.NotificationNotifyClient;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.publicevents.DocumentEventListener;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterNotifierService;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.RegisterGenerationJob;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletedBy;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunReport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.GeneratedRegisters;
import uk.gov.hmcts.cp.yotresultsdistribution.support.GeneratedRegisters.Notified;
import uk.gov.hmcts.cp.yotresultsdistribution.support.GenerationStackSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.GenerationStackSupport.SentEmail;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;

/**
 * One night, from a recorded register to the e-mails its Youth Offending Teams are told by, through
 * the whole assembled service (T052, completed at T060).
 *
 * <p>Every stage of this has its own suite and every one of them passes against a double of the
 * stage either side of it. What none of them can say is that the stages are joined: that a run reads
 * the flag from the store its settings name, writes the payload into the file service its second
 * datasource points at, sends {@code generate-document} to the endpoint it was given, and then hears
 * the answer on a durable subscription and moves the batch and its registers with it. Phase 5
 * shipped with none of that wired, and this is the test that would have said so.
 *
 * <p>Five outsides, four of them real: a Postgres for the register store, a second Postgres for the
 * framework file service seeded from the vendored schema, WireMock for App Configuration,
 * systemdocgenerator and notificationnotify, and an in-VM Artemis carrying {@code public.event}.
 * Only the App Configuration credential is stood in for, because a federated-token exchange happens
 * against Entra ID and not against the store.
 *
 * <p><strong>T060 carried the night past GENERATED.</strong> Until Phase 6 the batch stopped there,
 * because there was nothing wired to tell anybody about it; now the mark that records the document
 * is the same step that asks notificationnotify for one e-mail per recipient, so the state a
 * delivered outcome leaves this batch in is NOTIFIED and not GENERATED. This suite is where that
 * join is asserted end to end - that the recipients the batch is addressed to are the union across
 * its records (defect fix P4), that each of them is asked for exactly once, under the identity the
 * {@code register_notification} row was minted with, attaching the document
 * <em>systemdocgenerator</em> said it rendered rather than the payload it rendered it from.
 *
 * <p><strong>Two records, three recipients.</strong> One hearing matched Leeds and Wakefield and the
 * other Wakefield and Bradford, which is US3's own independent test: the union is three teams, the
 * team on both hearings is told once, and a first-row-only leg would never write to Bradford at all.
 *
 * <p><strong>An acceptance suite (tasks.md [A]).</strong> Nothing here is driven test-first: it
 * records what the assembled service does.
 */
@DisplayName("a night's generation, end to end")
class GenerationEndToEndIT {

    private static final LocalDate REGISTER_DAY = LocalDate.parse("2026-08-21");

    private static final Instant REGISTER_TIME = Instant.parse("2026-08-21T16:30:00Z");

    /** The day's second hearing, on the same register day and so in the same batch. */
    private static final Instant LATER_THAT_AFTERNOON = Instant.parse("2026-08-21T16:47:00Z");

    /** A team the first hearing matched and the second did not. */
    private static final CourtRegisterRecipient LEEDS = new CourtRegisterRecipient(
            "Leeds Youth Offending Team", "leeds.yot@example.gov.uk", null, "cr_standard");

    /** The team both hearings matched, which is one e-mail and not two. */
    private static final CourtRegisterRecipient WAKEFIELD = new CourtRegisterRecipient(
            "Wakefield Youth Offending Team", "wakefield.yot@example.gov.uk", null, "cr_standard");

    /**
     * A team only the second hearing matched.
     *
     * <p>The recipient the progression leg loses: its {@code findFirst()} over the records carrying
     * a non-empty list keeps the first hearing's teams and discards the rest, so Bradford - matched
     * for a child listed in the document that is about to be sent - is never written to (P4).
     */
    private static final CourtRegisterRecipient BRADFORD = new CourtRegisterRecipient(
            "Bradford Youth Offending Team", "bradford.yot@example.gov.uk", null, "cr_standard");

    /** The document systemdocgenerator says it rendered, which the e-mail would attach. */
    private static final UUID DOCUMENT_FILE_ID =
            UUID.fromString("2f9d4c37-8a15-4b6e-90c2-7d3f1a5b8e04");

    /** When it says it finished, with the offset the estate publishes times under. */
    private static final Instant GENERATED_AT = Instant.parse("2026-08-21T17:04:11Z");

    /** A third hearing of the same day, shared after the night's first run had been and gone. */
    private static final Instant AFTER_THE_RUN = Instant.parse("2026-08-21T18:12:00Z");

    /**
     * How long a batch has been waiting in the case that is about the other side of the boundary.
     *
     * <p>Inside {@code yotresultsdistribution.generation.stale-after}, which this repository ships at thirty
     * minutes: a batch this young is one the estate may still be rendering, and giving up on it
     * would throw away a document somebody is about to be sent (FR-002).
     */
    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    /**
     * How long the batch an operator asked for has been waiting.
     *
     * <p>Past the schedule's own thirty minutes and inside the seventy of
     * {@code yotresultsdistribution.generation.lock-at-most-for}, which is the grace a batch a person asked
     * for is judged by instead: a manual generation holds no run lock and has the whole requesting
     * deadline to work in, so failing it at forty minutes would orphan a render that run is still
     * making (FR-017). The same batch, stamped as the schedule's, would be released at this age.
     */
    private static final Duration FORTY_MINUTES = Duration.ofMinutes(40);

    /** How long the outcome is given to travel the topic and reach the batch row. */
    private static final Duration DELIVERED_WITHIN = Duration.ofSeconds(30);

    private static final Duration POLL = Duration.ofMillis(200);

    /** The shared contract mapper, which is what wrote the metadata row being read back. */
    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    /**
     * This case's registers, at a court centre nobody else holds a row for.
     *
     * <p>Minted per case, so what this suite asserts about a batch is about its own batch: the
     * shared store holds other suites' active registers and a night is entitled to batch those too.
     */
    private final GeneratedRegisters registers =
            new GeneratedRegisters(service, UUID.randomUUID());

    private static GenerationStackSupport stack;

    private static ConfigurableApplicationContext service;

    @BeforeAll
    static void startTheWholeStack() {
        PostgresTestSupport.applyFlyway();
        ProcessedLogTestSupport.dataSource();
        stack = GenerationStackSupport.start();
        service = GenerationStackSupport.startService(stack.settings());
    }

    @AfterAll
    static void stopTheWholeStack() {
        service.close();
        stack.close();
    }

    @BeforeEach
    void aNightWithOneCourtCentreWaiting() {
        stack.reset();
        stack.flagIs(true);
        registers.record(UUID.randomUUID(), REGISTER_DAY, REGISTER_TIME, List.of(LEEDS, WAKEFIELD));
        registers.record(UUID.randomUUID(), REGISTER_DAY, LATER_THAT_AFTERNOON,
                List.of(WAKEFIELD, BRADFORD));
    }

    @Test
    @DisplayName("the payload is written to the file service before the render is asked for")
    void the_run_should_store_the_payload_and_ask_systemdocgenerator_to_render_it() {
        final RunReport report = run();

        assertThat(report.gateDecision()).isEqualTo(new GateDecision.Proceed(false));
        assertThat(registers.batchStatuses())
                .as("GENERATING says a render was asked for and not that a document exists")
                .containsExactly(BatchStatus.GENERATING.name());

        final UUID payloadFileId = registers.payloadFileId().orElseThrow();
        assertThat(stack.payloadStoredUnder(payloadFileId))
                .as("systemdocgenerator is given an id and nothing else, and renders whatever the "
                        + "file service holds under it; a request for an id nothing was written "
                        + "under is a render of a file that does not exist")
                .isTrue();
        final JsonNode metadata = MAPPER.readTree(stack.metadataUnder(payloadFileId));
        assertThat(metadata.path("templateName").stringValue())
                .as("progression's five keys, and this is the one that names the template the "
                        + "document is rendered from; read as a tree because how Postgres prints "
                        + "jsonb back is not this service's decision")
                .isEqualTo("OEE_Layout5");
        assertThat(metadata.path("conversionFormat").stringValue()).isEqualTo("pdf");
        assertThat(metadata.path("numberOfPages").intValue()).isEqualTo(1);
        assertThat(metadata.path("fileName").stringValue())
                .as("the file the day is rendered under, as the first register named it")
                .isEqualTo(GeneratedRegisters.fileNameFor(REGISTER_DAY));
        assertThat(requestsNaming(registers.batches().getFirst()))
                .as("one render request, correlated on the batch identity the event will answer on")
                .hasSize(1);
    }

    @Test
    @DisplayName("the document-available event carries the batch and its registers to NOTIFIED")
    void an_outcome_on_the_public_event_topic_should_generate_and_notify_the_batch() {
        final RunReport report = run();
        final UUID batchId = registers.batches().getFirst();
        final UUID payloadFileId = registers.payloadFileId().orElseThrow();

        assertThat(report.outcomes())
                .as("the report is the requesting half's account of the night, and GENERATING is as "
                        + "far as a run can carry a batch itself: on this night the document "
                        + "arrives after the run has ended, so its outcome map cannot name a "
                        + "notified batch. What the store had settled by the time the line was "
                        + "written is a separate reading, on RunReport.settled")
                .containsKey(BatchStatus.GENERATING)
                .doesNotContainKey(BatchStatus.NOTIFIED);
        assertThat(report.releasedBatches())
                .as("nothing was left in flight by an earlier run, so the pass gave nothing back")
                .isZero();

        publishDocumentAvailable(batchId, payloadFileId);

        await().alias("the batch reaches NOTIFIED off the public-event topic")
                .atMost(DELIVERED_WITHIN)
                .pollInterval(POLL)
                .until(() -> registers.batchStatuses()
                        .equals(List.of(BatchStatus.NOTIFIED.name())));
        assertThat(registers.documentFileId())
                .as("the rendered document's id, which is what every e-mail attached")
                .contains(DOCUMENT_FILE_ID);
        assertThat(registers.completedBy())
                .as("the topic learned it, and the topic is the only thing that can: a batch "
                        + "nothing was ever learned about is what the next run's first act gives "
                        + "back")
                .contains(CompletedBy.EVENT.name());
        assertThat(registers.statuses())
                .as("and the batch's own registers move with it, both of them - this batch's rows "
                        + "and no others, which is defect fix P3")
                .containsExactly(BatchStatus.NOTIFIED.name(), BatchStatus.NOTIFIED.name());
    }

    @Test
    @DisplayName("every matched team is asked for once, with the document that was rendered")
    void the_notified_batch_should_have_asked_notificationnotify_once_per_distinct_recipient() {
        run();
        publishDocumentAvailable(
                registers.batches().getFirst(), registers.payloadFileId().orElseThrow());
        await().alias("the batch reaches NOTIFIED off the public-event topic")
                .atMost(DELIVERED_WITHIN)
                .pollInterval(POLL)
                .until(() -> registers.batchStatuses()
                        .equals(List.of(BatchStatus.NOTIFIED.name())));

        final List<SentEmail> sent = stack.emailsSent();
        assertThat(addressedTo(sent))
                .as("the union across the batch's records, each address once: Wakefield matched "
                        + "both hearings and is told once, and Bradford - which the progression "
                        + "leg's first-row-only reading discards - is told at all (P4)")
                .containsExactlyInAnyOrder(LEEDS.emailAddress1(), WAKEFIELD.emailAddress1(),
                        BRADFORD.emailAddress1());
        assertThat(greetedBy(sent))
                .as("each recipient's own name, which is the one personalisation the cr_standard "
                        + "template takes")
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        LEEDS.emailAddress1(), LEEDS.recipientName(),
                        WAKEFIELD.emailAddress1(), WAKEFIELD.recipientName(),
                        BRADFORD.emailAddress1(), BRADFORD.recipientName()));
        assertThat(sent).allSatisfy(email -> {
            assertThat(email.contentType())
                    .as("the framework routes the command on its media type, so it is not a "
                            + "formality")
                    .isEqualTo(NotificationNotifyClient.EMAIL_MEDIA_TYPE);
            final JsonNode body = MAPPER.readTree(email.body());
            assertThat(body.path("fileId").stringValue())
                    .as("the document systemdocgenerator said it rendered, and never the payload "
                            + "it was rendered from: the register travels by reference")
                    .isEqualTo(DOCUMENT_FILE_ID.toString());
            assertThat(body.has("notificationId"))
                    .as("the identity is the path parameter; the API-side schema declares no such "
                            + "property and refuses a body that carries one")
                    .isFalse();
        });

        final List<Notified> rows = registers.notifications();
        assertThat(rows)
                .as("one row per distinct recipient, each accepted on its one attempt under the "
                        + "one configured template")
                .extracting(Notified::status, Notified::responseCode, Notified::attempts,
                        Notified::templateName)
                .containsOnly(tuple(NotificationStatus.ACCEPTED.name(),
                        NotificationNotifyClient.ACCEPTED, 1,
                        RegisterNotifierService.TEMPLATE_NAME));
        assertThat(sent).extracting(SentEmail::path)
                .as("and each POST was made under its own row's identity, which is what makes a "
                        + "resend reach the attempt it is retrying rather than send a second e-mail")
                .containsExactlyInAnyOrderElementsOf(rows.stream()
                        .map(row -> GenerationStackSupport.notificationPathFor(
                                row.notificationId()))
                        .toList());
    }

    @Test
    @DisplayName("a batch inside the minimum age is left in flight, and its day deferred as today")
    void a_batch_not_yet_stale_should_be_left_alone_and_its_court_centre_day_deferred() {
        run();
        final UUID inFlight = registers.batches().getFirst();
        registers.record(UUID.randomUUID(), REGISTER_DAY, AFTER_THE_RUN, List.of(LEEDS));
        registers.hasBeenWaitingFor(TEN_MINUTES);

        final RunReport report = run();

        assertThat(report.releasedBatches())
                .as("ten minutes is inside the minimum age, so the pass gave nothing back: a batch "
                        + "the estate may still be rendering is not a batch to fail (FR-002)")
                .isZero();
        assertThat(registers.batches())
                .as("and no second batch was assembled for the day, because the first is still in "
                        + "flight - two live batches for one court centre day would be two "
                        + "documents and two e-mails")
                .containsExactly(inFlight);
        assertThat(registers.batchStatuses())
                .as("the batch is exactly as the first run left it")
                .containsExactly(BatchStatus.GENERATING.name());
        assertThat(registers.registersIn(inFlight))
                .as("the two registers the first run batched are still that batch's")
                .isEqualTo(2);
        assertThat(registers.registersWaiting())
                .as("and the hearing shared since is recorded and in no batch, which is what its "
                        + "court centre day being deferred looks like in the store")
                .isEqualTo(1);
        assertThat(report.deferredKeys())
                .as("and the run says so rather than passing over the key in silence; a count "
                        + "rather than an identity, and the store above is where this day's own "
                        + "deferral is read - the register store is shared by every suite in this "
                        + "JVM, so the number is the night's")
                .isPositive();
        assertThat(report.deferredRows()).isPositive();
    }

    @Test
    @DisplayName("a batch an operator asked for is judged by the longer of the two cutoffs")
    void a_batch_an_operator_asked_for_should_be_judged_by_the_longer_cutoff() {
        run();
        final UUID byHand = registers.batches().getFirst();
        registers.wasAskedForByAnOperator();
        registers.hasBeenWaitingFor(FORTY_MINUTES);

        final RunReport report = run();

        assertThat(report.releasedBatches())
                .as("forty minutes is past the schedule's own cutoff and inside the operator's: a "
                        + "manual generation holds no run lock and has the whole requesting "
                        + "deadline to work in, so this batch is one the 18:00 run must not fail "
                        + "underneath it (FR-017)")
                .isZero();
        assertThat(registers.batches())
                .as("nothing was released, so nothing was re-batched")
                .containsExactly(byHand);
        assertThat(registers.batchStatuses()).containsExactly(BatchStatus.GENERATING.name());
        assertThat(registers.registersIn(byHand))
                .as("and its registers are still the batch's, which is what being left alone means "
                        + "for the rows a released batch would have given back")
                .isEqualTo(2);
        assertThat(registers.registersWaiting())
                .as("nothing came back to be batched by the schedule underneath the person who "
                        + "asked for this one")
                .isZero();
    }

    /**
     * Which addresses this stack was asked to write to.
     *
     * @param sent the e-mails notificationnotify received
     * @return their {@code sendToAddress} values, in arrival order
     */
    private static List<String> addressedTo(final List<SentEmail> sent) {
        return sent.stream().map(field("sendToAddress")).toList();
    }

    /**
     * What each address's e-mail greets its recipient by.
     *
     * @param sent the e-mails notificationnotify received
     * @return address to {@code personalisation.yotsName}
     */
    private static Map<String, String> greetedBy(final List<SentEmail> sent) {
        final Map<String, String> greetings = new LinkedHashMap<>();
        for (final SentEmail email : sent) {
            final JsonNode body = MAPPER.readTree(email.body());
            greetings.put(body.path("sendToAddress").stringValue(),
                    body.path("personalisation").path("yotsName").stringValue());
        }
        return greetings;
    }

    /**
     * Reads one top-level string off a command body.
     *
     * @param name the field to read
     * @return the reader
     */
    private static Function<SentEmail, String> field(final String name) {
        return email -> MAPPER.readTree(email.body()).path(name).stringValue();
    }

    /**
     * Publishes systemdocgenerator's announcement onto the topic, as the estate publishes it.
     *
     * <p>A framework {@code JsonEnvelope} - {@code _metadata} and the event's own fields beside it -
     * with {@code CPPNAME} as the string property the broker's selector filters on, and the times
     * carrying the offset the platform sends them with rather than in UTC.
     *
     * @param batchId       the batch the render was requested for
     * @param payloadFileId the payload it was rendered from
     */
    private static void publishDocumentAvailable(final UUID batchId, final UUID payloadFileId) {
        final String body = """
                {"_metadata":{"id":"%s","name":"%s"},
                 "sourceCorrelationId":"%s",
                 "payloadFileServiceId":"%s",
                 "documentFileServiceId":"%s",
                 "templateIdentifier":"OEE_Layout5",
                 "originatingSource":"%s",
                 "generatedTime":"%s"}
                """.formatted(UUID.randomUUID(), DocumentEventListener.DOCUMENT_AVAILABLE, batchId,
                payloadFileId, DOCUMENT_FILE_ID, DocumentEventListener.ORIGINATING_SOURCE,
                OffsetDateTime.ofInstant(GENERATED_AT, ZoneOffset.UTC));

        try (JMSContext jms = service.getBean(ConnectionFactory.class).createContext()) {
            jms.createProducer()
                    .setProperty(DocumentEventListener.EVENT_NAME_PROPERTY,
                            DocumentEventListener.DOCUMENT_AVAILABLE)
                    .send(jms.createTopic("public.event"), body);
        }
    }

    /**
     * The render requests this stack received that name one batch.
     *
     * @param batchId the batch the render was requested for
     * @return the bodies naming it, in arrival order
     */
    private static List<String> requestsNaming(final UUID batchId) {
        return stack.renderRequests().stream()
                .filter(body -> body.contains(batchId.toString()))
                .toList();
    }

    /**
     * Runs the night through the bean the schedule would have fired.
     *
     * @return what the run reported
     */
    private static RunReport run() {
        return service.getBean(RegisterGenerationJob.class).run();
    }
}
