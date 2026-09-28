package uk.gov.hmcts.cp.yotresultsdistribution.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.logstash.logback.marker.SingleFieldAppendingMarker;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ExceptionReportService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ExceptionReportSink;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.ExceptionReportJob;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionKind;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionReport;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportWindow;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;
import uk.gov.hmcts.cp.yotresultsdistribution.support.GenerationStackSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.GenerationStackSupport.SentEmail;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ReportReadsDatabase;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ServiceTestSupport;

/**
 * One morning, from the rows five reads find to the events, the CSV and the e-mails support is told
 * by, through the whole assembled service.
 *
 * <p>Every stage of the report has a suite of its own and each passes against a double of the stage
 * either side of it. What none of them can say is that the stages are joined: that a pod in FR-004's
 * shape - the deployment with {@code yotresultsdistribution.generation.enabled=false}, which renders nothing
 * and is what the relocated repository beans exist for - reads its own store, writes the two
 * structured events Log Analytics indexes, renders the exception list into the framework file
 * service and hands notificationnotify one send per support address.
 *
 * <p><strong>A database of this suite's own</strong>, migrated in full. Almost every claim here is a
 * claim about what the report does <em>not</em> hold - "every FAILED request exactly once and no
 * non-exception row at all" is SC-001 in as many words - and none of it is observable against the
 * container the other end-to-end suites share, where what else is in the table is whatever ran
 * first.
 *
 * <p>Four cases, and each is one of the spec's own criteria:
 * <ul>
 *   <li><strong>one of each kind</strong> (SC-003, SC-004) - five exceptions, five events, one
 *       ten-field summary, one run line, one send per recipient and one CSV row per exception;</li>
 *   <li><strong>SC-001 at scale</strong> - fifty-odd mixed records, every failure exactly once and
 *       nothing that is not one;</li>
 *   <li><strong>SC-006</strong> - a ten-thousand-row processed log, built and delivered inside ten
 *       seconds;</li>
 *   <li><strong>SC-008</strong> - the morning run beside a generation run, on schedulers of their
 *       own, neither waiting for the other.</li>
 * </ul>
 *
 * <p><strong>An acceptance suite (tasks.md [A]).</strong> Nothing here is driven test-first: it
 * records what the assembled service does, and the first observed result of each case is what the
 * task records.
 */
@DisplayName("the morning exception report, end to end")
class ExceptionReportEndToEndIT {

    /**
     * The tag the two wall-clock cases carry, so a loaded machine can be told to leave them out.
     *
     * <p>SC-006's ten seconds and SC-008's "both schedules fired" are the only two claims in this
     * repository whose answer depends on how busy the host is. They are real criteria and they stay
     * in the default selection; the tag exists so that a developer on a machine that is also
     * building something else can exclude them by name rather than by disabling the suite - see
     * README's Testing section.
     */
    private static final String TIMING = "timing";

    /** This suite's own database inside the shared container, named apart from every other. */
    private static final String DATABASE = "yotresultsdistribution_exception_report_e2e";

    /** The tables this suite seeds, named child before parent, which is the order they empty in. */
    private static final String[] THE_TABLES =
        {"register_notification", "processed_output", "register_batch", "processed_request"};

    private static final String SOURCE = ServiceTestSupport.SOURCE;

    /** The template support's copy is sent under; a UUID, as notificationnotify requires. */
    private static final String REPORT_TEMPLATE = "3b6f1c92-7d40-4a58-8e21-5c9d0f3a4b76";

    /** Two addresses, because "one send per recipient" is not a claim a single address can make. */
    private static final List<String> RECIPIENTS =
            List.of("cr-support@justice.gov.uk", "cr-duty@justice.gov.uk");

    /** The rendering limit this suite states rather than inherits, so "past it" is unambiguous. */
    private static final Duration RENDERING_LIMIT = Duration.ofMinutes(10);

    /** How long ago the batch still GENERATING asked for its render: well past the limit. */
    private static final Duration LONG_SINCE_REQUESTED = Duration.ofHours(2);

    /** How old the request still in flight is: past the shipped thirty-minute threshold. */
    private static final Duration FORTY_MINUTES = Duration.ofMinutes(40);

    /** Recent enough to be inside the SC-006 command's own twenty-four-hour window. */
    private static final Duration A_FEW_MINUTES = Duration.ofMinutes(5);

    /** The schedule the reporting pod runs on, which is also the window it reads. */
    private static final String REPORT_CRON = "0 0 7 * * MON-FRI";

    private static final String COURTS_ZONE = "Europe/London";

    /** The five kinds, one of each, which is what the first case seeds and asserts. */
    private static final int ONE_OF_EACH_KIND = 5;

    /** The number of fields data-model.md states the summary event carries. */
    private static final int TWELVE_SUMMARY_FIELDS = 12;

    /** SC-006's window, in the shape {@code report-exceptions --since 24h} builds it. */
    private static final Duration SINCE_24H = Duration.ofHours(24);

    /** SC-006's processed log, and its budget. */
    private static final int TEN_THOUSAND_ROWS = 10_000;

    private static final Duration TEN_SECONDS = Duration.ofSeconds(10);

    /** How many of those ten thousand rows are parked failures inside the window. */
    private static final int FAILURES_AMONG_THEM = 50;

    /** SC-008's schedule: both runs fire on the same tick, on schedulers of their own. */
    private static final String EVERY_TWO_SECONDS = "*/2 * * * * *";

    /**
     * What an unimpeded generation run may take, far above an empty run's real cost.
     *
     * <p>The bound is about interference and not about speed: a run that waited for the report
     * would be a run queued behind another job's whole execution, and the thread names beside it
     * are what say the two were never on one queue at all.
     */
    private static final Duration NORMAL_BOUND = Duration.ofSeconds(5);

    private static final Duration OBSERVED_WITHIN = Duration.ofSeconds(60);

    private static final Duration POLL = Duration.ofMillis(200);

    private static final String SERVICE_LOGGERS = "uk.gov.hmcts.cp.yotresultsdistribution";

    /** The event name the run's own line is indexed under. */
    private static final String RUN_EVENT = "event=exception_report_run";

    /** The generation run's, for the case that watches the two beside each other. */
    private static final String GENERATION_RUN_EVENT = "event=register_generation_run";

    private static final String SUMMARY_EVENT = "yotresultsdistribution_exception_report";

    private static final String EXCEPTION_EVENT = "yotresultsdistribution_exception";

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    private static ReportReadsDatabase database;

    private static GenerationStackSupport stack;

    /** The pod FR-004 describes: the report on, the e-mail output on, and nothing rendered. */
    private static ConfigurableApplicationContext service;

    @BeforeAll
    static void startTheReportingPod() {
        database = ReportReadsDatabase.migrated(DATABASE);
        stack = GenerationStackSupport.start();
        service = GenerationStackSupport.startService(reportingPod());
    }

    @AfterAll
    static void stopTheReportingPod() {
        service.close();
        stack.close();
        database.drop();
    }

    @BeforeEach
    void aStoreWithNothingWrongInIt() {
        database.empty(THE_TABLES);
        stack.reset();
    }

    // --- (a) one of each kind ---------------------------------------------------------------

    @Test
    @DisplayName("one of each kind reaches the index, the attachment and every recipient")
    void a_morning_with_one_of_each_kind_should_be_reported_to_both_sinks() {
        final UUID parked = seedRequest("FAILED", "store-unavailable", insideTheWindow());
        final UUID inFlight = seedRequest("RETRYING", null, FORTY_MINUTES);
        final UUID stillRendering = seedGeneratingBatch(LONG_SINCE_REQUESTED);
        final UUID deadBatch = seedFailedBatch(insideTheWindow());
        final UUID refused = seedNotification(deadBatch, "FAILED", insideTheWindow());

        final List<ILoggingEvent> written = runTheMorningReport();

        assertThat(exceptionEvents(written))
                .as("five things are wrong and five events say so, one per exception, which is "
                        + "what makes each of them a row a saved query can act on (SC-003)")
                .hasSize(ONE_OF_EACH_KIND)
                .extracting(event -> fieldsOf(event).get("kind"))
                .containsExactlyInAnyOrder(ExceptionKind.REQUEST_FAILED.name(),
                        ExceptionKind.REQUEST_LATE.name(), ExceptionKind.BATCH_LATE.name(),
                        ExceptionKind.BATCH_FAILED.name(), ExceptionKind.NOTIFICATION_FAILED.name());
        assertThat(identifiersIn(written))
                .as("and each of them names the row it is about")
                .contains(parked.toString(), inFlight.toString(), stillRendering.toString(),
                        deadBatch.toString(), refused.toString());

        final Map<String, String> summary = fieldsOf(summaryEvent(written));
        assertThat(summary)
                .as("twelve fields, the six counts among them, present even where they are nought")
                .hasSize(TWELVE_SUMMARY_FIELDS)
                .containsEntry("event", SUMMARY_EVENT)
                .containsEntry("request_failed", "1")
                .containsEntry("request_late", "1")
                .containsEntry("batch_late", "1")
                .containsEntry("batch_failed", "1")
                .containsEntry("notification_failed", "1")
                .containsEntry("batch_released", "0");

        assertThat(runLine(written))
                .as("the run's own line, written after every sink returned, saying the morning "
                        + "was delivered and to whom")
                .isPresent()
                .get(InstanceOfAssertFactories.STRING)
                .contains("outcome=delivered")
                .contains("delivered_log=ok")
                .contains("delivered_email=ok")
                .contains("entries=" + ONE_OF_EACH_KIND);

        final List<SentEmail> sent = stack.emailsSent();
        assertThat(addressedTo(sent))
                .as("one send per recipient and no batching of the two into one command, which "
                        + "is the contract notificationnotify owns (SC-004)")
                .containsExactlyInAnyOrderElementsOf(RECIPIENTS);
        assertThat(attachedFiles(sent))
                .as("both e-mails attach the one CSV this morning wrote, by file-service id")
                .hasSize(1);

        final String csv = stack.textUnder(attachedFiles(sent).iterator().next());
        assertThat(csv.lines())
                .as("a header and one row per exception, which is the list a support engineer "
                        + "opens rather than queries")
                .hasSize(1 + ONE_OF_EACH_KIND);
        assertThat(csv.lines().skip(1).map(row -> row.split(",", -1)[0]))
                .as("and the kind is the first column of every one of them")
                .containsExactlyInAnyOrder(ExceptionKind.REQUEST_FAILED.name(),
                        ExceptionKind.REQUEST_LATE.name(), ExceptionKind.BATCH_LATE.name(),
                        ExceptionKind.BATCH_FAILED.name(), ExceptionKind.NOTIFICATION_FAILED.name());
    }

    // --- (b) SC-001 at scale ----------------------------------------------------------------

    @Test
    @DisplayName("every failure appears exactly once and nothing that is not a failure appears")
    void a_store_of_mixed_records_should_yield_every_exception_once_and_no_other_row() {
        final List<String> failures = new ArrayList<>();
        final List<String> healthy = new ArrayList<>();

        for (int i = 0; i < 12; i++) {
            failures.add(seedRequest("FAILED", "schema-violation", insideTheWindow()).toString());
        }
        for (int i = 0; i < 10; i++) {
            failures.add(seedRequest("RETRYING", null, FORTY_MINUTES).toString());
        }
        for (int i = 0; i < 8; i++) {
            failures.add(seedGeneratingBatch(LONG_SINCE_REQUESTED).toString());
        }
        for (int i = 0; i < 7; i++) {
            final UUID dead = seedFailedBatch(insideTheWindow());
            failures.add(dead.toString());
            failures.add(seedNotification(dead, "FAILED", insideTheWindow()).toString());
        }
        for (int i = 0; i < 20; i++) {
            healthy.add(seedRequest("COMPLETED", null, A_FEW_MINUTES).toString());
        }
        for (int i = 0; i < 5; i++) {
            final UUID told = seedNotifiedBatch();
            healthy.add(told.toString());
            healthy.add(seedNotification(told, "ACCEPTED", A_FEW_MINUTES).toString());
        }
        for (int i = 0; i < 3; i++) {
            healthy.add(seedRegisterRecordedWhileTheFlagWasOff().toString());
        }

        final List<ILoggingEvent> written = runTheMorningReport();
        final List<String> identifiers = identifiersIn(written);

        assertThat(kindsOf(written, ExceptionKind.REQUEST_FAILED))
                .as("twelve parked requests, each reported once: zero misses and zero duplicates, "
                        + "which is SC-001 in as many words")
                .hasSize(12);
        assertThat(identifiers)
                .as("every one of the fifty-odd things that is wrong is named, each exactly once")
                .containsAll(failures)
                .doesNotHaveDuplicates();
        assertThat(identifiers)
                .as("and nothing that is not: a completed request, a notified batch, an accepted "
                        + "send and a register recorded while the flag was off are outcomes, not "
                        + "exceptions, and a report that named one would send support after "
                        + "something that is not missing")
                .doesNotContainAnyElementsOf(healthy);
        assertThat(exceptionEvents(written))
                .as("one event per exception and no event at all for the thirty-one healthy rows: "
                        + "the claim above is that no healthy identifier was NAMED, and an event "
                        + "carrying no identifier this case seeded would satisfy it while still "
                        + "sending support after a row that is not missing")
                .hasSize(failures.size());
    }

    // --- (c) SC-006 -------------------------------------------------------------------------

    @Test
    @Tag(TIMING)
    @DisplayName("a ten-thousand-row processed log is reported on inside ten seconds")
    void a_since_24h_report_over_ten_thousand_rows_should_be_built_and_written_inside_ten_seconds() {
        seedTenThousandRows();

        final ExceptionReportService reporting = service.getBean(ExceptionReportService.class);
        final List<ExceptionReportSink> sinks =
                service.getBeanProvider(ExceptionReportSink.class).orderedStream().toList();
        final Instant now = Instant.now();
        final ReportWindow since24h = new ReportWindow(now.minus(SINCE_24H), now);
        final String runId = UUID.randomUUID().toString();

        final long startedAt = System.nanoTime();
        final ExceptionReport report = reporting.build(since24h, runId);
        reporting.deliver(report, sinks);
        final Duration took = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(report.entries())
                .as("the report is a real one and not an empty answer that would be fast for the "
                        + "wrong reason")
                .hasSize(FAILURES_AMONG_THEM);
        assertThat(took)
                .as("built and written in %s; the criterion is ten seconds, and what it is really "
                        + "about is that the reads are indexed rather than scanned (SC-006)", took)
                .isLessThan(TEN_SECONDS);
    }

    // --- (d) SC-008 -------------------------------------------------------------------------

    @Test
    @Tag(TIMING)
    @DisplayName("the morning run and a generation run happen on schedulers of their own")
    void the_report_should_run_beside_a_generation_run_without_either_waiting_for_the_other() {
        stack.flagIs(true);
        try (CapturedLog log = CapturedLog.capturing(SERVICE_LOGGERS);
                ConfigurableApplicationContext ignored =
                        GenerationStackSupport.startService(bothSchedulesFiring())) {

            await().alias("both runs have fired at least once")
                    .atMost(OBSERVED_WITHIN)
                    .pollInterval(POLL)
                    .until(() -> lineFor(log.events(), RUN_EVENT).isPresent()
                            && lineFor(log.events(), GENERATION_RUN_EVENT).isPresent());

            final List<ILoggingEvent> events = log.events();
            assertThat(threadOf(events, RUN_EVENT))
                    .as("the morning run has a single-threaded scheduler of its own, which is what "
                            + "the scheduler attribute on the job selects (SC-008)")
                    .startsWith("exception-report-");
            assertThat(threadOf(events, GENERATION_RUN_EVENT))
                    .as("and the night's run is on the one it has always had, so a 07:00 report "
                            + "cannot be queued behind an 18:00 run that overran")
                    .startsWith("register-generation-");
            assertThat(durationOf(events, GENERATION_RUN_EVENT))
                    .as("with the report running beside it, the generation run's own line is "
                            + "still inside its normal bound")
                    .isLessThan(NORMAL_BOUND);
        }
    }

    // --- the pods ----------------------------------------------------------------------------

    /**
     * The deployment FR-004 describes: the report and its two outputs on, and nothing rendered.
     *
     * <p>The generation stack's settings without the generation half or its broker - what is kept
     * from them is notificationnotify, which the e-mail output posts through, and the file service,
     * which it writes the CSV into. Both of those are the two halves' shared downstreams, and this
     * pod is the one that proves they are reachable without the other half.
     *
     * @return the settings
     */
    private static Map<String, String> reportingPod() {
        final Map<String, String> settings = new LinkedHashMap<>(stack.settings());
        settings.keySet().removeIf(setting -> setting.startsWith("spring.artemis"));
        settings.put("spring.datasource.url", PostgresTestSupport.urlFor(DATABASE));
        settings.put("yotresultsdistribution.generation.enabled", "false");
        settings.put("yotresultsdistribution.report.enabled", "true");
        settings.put("yotresultsdistribution.report.batch-generated-within", RENDERING_LIMIT.toString());
        settings.put("yotresultsdistribution.report.email.enabled", "true");
        settings.put("yotresultsdistribution.report.email.template-id", REPORT_TEMPLATE);
        settings.put("yotresultsdistribution.report.email.recipients", String.join(",", RECIPIENTS));
        return settings;
    }

    /**
     * A pod with both schedules firing on the same tick, which is SC-008's arrangement.
     *
     * <p>The two crons are the same string so that the two runs are asked for at the same instant:
     * with one scheduler between them, the second could only start when the first had finished,
     * and the thread names are what say there are two. The e-mail output is off - what this case is
     * about is which thread a run happens on, and a send would only add a POST per tick.
     *
     * @return the settings
     */
    private static Map<String, String> bothSchedulesFiring() {
        final Map<String, String> settings = new LinkedHashMap<>(stack.settings());
        settings.put("spring.datasource.url", PostgresTestSupport.urlFor(DATABASE));
        settings.put("yotresultsdistribution.generation.cron", EVERY_TWO_SECONDS);
        settings.put("yotresultsdistribution.report.enabled", "true");
        settings.put("yotresultsdistribution.report.cron", EVERY_TWO_SECONDS);
        settings.put("yotresultsdistribution.report.batch-generated-within", RENDERING_LIMIT.toString());
        return settings;
    }

    // --- running the report ------------------------------------------------------------------

    /**
     * How long ago a row has to have failed to fall inside the window the 07:00 run will read.
     *
     * <p>The scheduled window is <strong>aligned to the schedule and half-open</strong>: it ends at
     * the run's own occurrence, not at the moment the run fires. A row seeded "five minutes ago" is
     * therefore after the end of the window on any morning this suite is run at a sensible hour,
     * and the three window-bounded kinds would be missing from a report that is otherwise correct -
     * which is a suite that fails at half past three and passes at five past seven.
     *
     * <p>So the age is computed from the window itself: five minutes before its end, which is
     * inside it for any schedule whose period is longer than five minutes. The two late kinds are
     * deliberately unbounded by the window and keep their own ages, because how long they have been
     * wrong is the whole of what makes them late.
     *
     * @return the age to seed a window-bounded row at
     */
    private static Duration insideTheWindow() {
        final Instant now = Instant.now();
        final ReportWindow window = ReportWindow.forScheduledRun(REPORT_CRON, COURTS_ZONE, now);
        return Duration.between(window.to().minus(A_FEW_MINUTES), now);
    }

    /**
     * One morning's run, through the job the schedule fires, with everything it said.
     *
     * @return the lines the run wrote, in the order it wrote them
     */
    private static List<ILoggingEvent> runTheMorningReport() {
        try (CapturedLog log = CapturedLog.capturing(SERVICE_LOGGERS)) {
            service.getBean(ExceptionReportJob.class).run();
            return log.events();
        }
    }

    // --- seeding ------------------------------------------------------------------------------

    /**
     * One request in the processed log, aged by the one stamp its read looks at.
     *
     * @param status        the status the row holds
     * @param failureReason the bounded code a parked row carries, or {@code null}
     * @param ago           how long ago it was created and last written to
     * @return the request identity, which is what the report names it by
     */
    private static UUID seedRequest(final String status, final String failureReason,
            final Duration ago) {

        final UUID requestId = UUID.randomUUID();
        database.jdbcClient()
                .sql("""
                        INSERT INTO processed_request (
                            source, request_id, hearing_id, hearing_day, shared_time, event_type,
                            request_fingerprint, status, attempts, failure_reason,
                            exhausted_message_id, created_at, updated_at)
                        VALUES (
                            :source, :requestId, :hearingId, CURRENT_DATE, now(),
                            'Hearing_Resulted', 'fingerprint', :status, 1, :failureReason,
                            :exhaustedMessageId,
                            now() - CAST(:ago AS interval), now() - CAST(:ago AS interval))
                        """)
                .param("source", SOURCE)
                .param("requestId", requestId)
                .param("hearingId", UUID.randomUUID())
                .param("status", status)
                .param("failureReason", failureReason, Types.VARCHAR)
                // A parked row carries the identity of the delivery that exhausted it: the log
                // says which message was dead-lettered, and the schema will not hold a FAILED row
                // that does not.
                .param("exhaustedMessageId",
                        "FAILED".equals(status) ? SOURCE + ':' + requestId : null, Types.VARCHAR)
                .param("ago", ago.toSeconds() + " seconds")
                .update();
        return requestId;
    }

    /**
     * A batch that asked for its render and has heard nothing since.
     *
     * @param requestedAgo how long ago the render was asked for
     * @return the batch identity
     */
    private static UUID seedGeneratingBatch(final Duration requestedAgo) {
        return seedBatch("GENERATING", null, null,
                "assembled_at = now() - CAST(:ago AS interval), "
                        + "requested_at = now() - CAST(:ago AS interval)", requestedAgo);
    }

    /**
     * A batch that failed inside the window, on a reason the run itself learned.
     *
     * @param failedAgo how long ago it failed
     * @return the batch identity
     */
    private static UUID seedFailedBatch(final Duration failedAgo) {
        // RENDER_REQUEST_FAILED and no completed_by: the shape check pairs the two reasons an
        // outcome taught this service with a completer, and this is not one of them.
        return seedBatch("FAILED", "RENDER_REQUEST_FAILED", null,
                "assembled_at = now(), requested_at = now(), "
                        + "failed_at = now() - CAST(:ago AS interval)", failedAgo);
    }

    /**
     * A batch whose teams were all told, which is a night that went right.
     *
     * @return the batch identity
     */
    private static UUID seedNotifiedBatch() {
        return seedBatch("NOTIFIED", null, "EVENT",
                "assembled_at = now(), requested_at = now(), generated_at = now(), "
                        + "notified_at = now()", Duration.ZERO);
    }

    private static UUID seedBatch(final String status, final String failureReason,
            final String completedBy, final String stamps, final Duration ago) {

        final UUID batchId = UUID.randomUUID();
        database.jdbcClient()
                .sql("""
                        INSERT INTO register_batch (
                            batch_id, court_centre_id, register_date, file_name, status,
                            failure_reason, completed_by, system_generated, attempts)
                        VALUES (
                            :batchId, :courtCentreId, CURRENT_DATE, 'court-register.pdf', :status,
                            :failureReason, :completedBy, true, 1)
                        """)
                .param("batchId", batchId)
                .param("courtCentreId", UUID.randomUUID())
                .param("status", status)
                .param("failureReason", failureReason, Types.VARCHAR)
                .param("completedBy", completedBy, Types.VARCHAR)
                .update();
        database.jdbcClient()
                .sql("UPDATE register_batch SET " + stamps + " WHERE batch_id = :batchId")
                .param("batchId", batchId)
                .param("ago", ago.toSeconds() + " seconds")
                .update();
        return batchId;
    }

    /**
     * One recipient's row against a batch, settled the way the case needs it.
     *
     * @param batchId the batch it belongs to
     * @param status  ACCEPTED or FAILED
     * @param sentAgo how long ago it was posted for
     * @return the notification identity
     */
    private static UUID seedNotification(final UUID batchId, final String status,
            final Duration sentAgo) {

        final UUID notificationId = UUID.randomUUID();
        database.jdbcClient()
                .sql("""
                        INSERT INTO register_notification (
                            notification_id, batch_id, email_address, recipient_name,
                            template_name, template_id, status, response_code, attempts, sent_at)
                        VALUES (
                            :notificationId, :batchId, :address, 'A Youth Offending Team',
                            'cr_standard', :templateId, :status, 500, 1,
                            now() - CAST(:ago AS interval))
                        """)
                .param("notificationId", notificationId)
                .param("batchId", batchId)
                .param("address", notificationId + "@example.gov.uk")
                .param("templateId", UUID.fromString(REPORT_TEMPLATE))
                .param("status", status)
                .param("ago", sentAgo.toSeconds() + " seconds")
                .update();
        return notificationId;
    }

    /**
     * A register recorded while the cutover flag was off, which the legacy may already have sent.
     *
     * <p>Unbatched and RECORDED, and therefore the row that would be reported as stranded were the
     * fourth predicate of the store's read not there. It is seeded precisely because it is the
     * healthy row easiest to mistake for an exception.
     *
     * @return the output identity, which nothing should name
     */
    private static UUID seedRegisterRecordedWhileTheFlagWasOff() {
        final UUID requestId = seedRequest("COMPLETED", null, A_FEW_MINUTES);
        final UUID outputId = UUID.randomUUID();
        database.jdbcClient()
                .sql("""
                        INSERT INTO processed_output (
                            output_id, source, request_id, court_centre_id, register_date,
                            file_name, status, document, hearing_id, hearing_date, register_time,
                            recorded_flag_state)
                        VALUES (
                            :outputId, :source, :requestId, :courtCentreId, CURRENT_DATE,
                            'court-register.pdf', 'RECORDED', '{}'::jsonb, :hearingId,
                            now() - CAST(:ago AS interval), now() - CAST(:ago AS interval), 'OFF')
                        """)
                .param("outputId", outputId)
                .param("source", SOURCE)
                .param("requestId", requestId)
                .param("courtCentreId", UUID.randomUUID())
                .param("hearingId", UUID.randomUUID())
                .param("ago", LONG_SINCE_REQUESTED.toSeconds() + " seconds")
                .update();
        return outputId;
    }

    /**
     * SC-006's processed log: ten thousand rows, fifty of them parked inside the window.
     *
     * <p>Written in one statement rather than ten thousand, because what the case needs is a table
     * the planner has statistics about and not ten thousand round trips. {@code ANALYZE} follows
     * it for the same reason: a table the planner believes is empty is a table it scans.
     */
    private static void seedTenThousandRows() {
        database.jdbcClient()
                .sql("""
                        INSERT INTO processed_request (
                            source, request_id, hearing_id, hearing_day, shared_time, event_type,
                            request_fingerprint, status, attempts, failure_reason,
                            exhausted_message_id, created_at, updated_at)
                        SELECT :source, gen_random_uuid(), gen_random_uuid(), CURRENT_DATE, now(),
                               'Hearing_Resulted', 'fingerprint',
                               CASE WHEN g <= :failures THEN 'FAILED' ELSE 'COMPLETED' END,
                               1,
                               CASE WHEN g <= :failures THEN 'store-unavailable' ELSE NULL END,
                               CASE WHEN g <= :failures THEN :source || ':' || g ELSE NULL END,
                               now() - (g || ' seconds')::interval,
                               now() - (g || ' seconds')::interval
                          FROM generate_series(1, :rows) AS g
                        """)
                .param("source", SOURCE)
                .param("failures", FAILURES_AMONG_THEM)
                .param("rows", TEN_THOUSAND_ROWS)
                .update();
        database.jdbcClient().sql("ANALYZE processed_request").update();
    }

    // --- reading what was written --------------------------------------------------------------

    private static List<ILoggingEvent> exceptionEvents(final List<ILoggingEvent> written) {
        return written.stream()
                .filter(event -> EXCEPTION_EVENT.equals(fieldsOf(event).get("event")))
                .toList();
    }

    private static List<ILoggingEvent> kindsOf(final List<ILoggingEvent> written,
            final ExceptionKind kind) {

        return exceptionEvents(written).stream()
                .filter(event -> kind.name().equals(fieldsOf(event).get("kind")))
                .toList();
    }

    private static ILoggingEvent summaryEvent(final List<ILoggingEvent> written) {
        return written.stream()
                .filter(event -> SUMMARY_EVENT.equals(fieldsOf(event).get("event")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no summary event was written"));
    }

    /**
     * Every identifier the report's exception events named, in the order they were written.
     *
     * <p>The five kinds carry different identifying fields, so what "the rows this report is
     * about" means is the union of them - which is exactly what a claim about misses and
     * duplicates has to be made over.
     *
     * @param written the lines the run wrote
     * @return one identifier per exception event
     */
    private static List<String> identifiersIn(final List<ILoggingEvent> written) {
        return exceptionEvents(written).stream()
                .map(ExceptionReportEndToEndIT::fieldsOf)
                .map(fields -> fields.getOrDefault("request_id",
                        fields.getOrDefault("notification_id", fields.get("batch_id"))))
                .toList();
    }

    private static Optional<String> lineFor(final List<ILoggingEvent> written, final String event) {
        return written.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains(event))
                .reduce((first, last) -> last);
    }

    private static Optional<String> runLine(final List<ILoggingEvent> written) {
        return lineFor(written, RUN_EVENT);
    }

    private static String threadOf(final List<ILoggingEvent> written, final String event) {
        return written.stream()
                .filter(line -> line.getFormattedMessage().contains(event))
                .map(ILoggingEvent::getThreadName)
                .reduce((first, last) -> last)
                .orElseThrow(() -> new AssertionError("no line was written for " + event));
    }

    /**
     * What one run's line says it took, read off the line rather than measured beside it.
     *
     * @param written the lines captured
     * @param event   the run event the duration is wanted from
     * @return the duration the line reports
     */
    private static Duration durationOf(final List<ILoggingEvent> written, final String event) {
        final String line = lineFor(written, event)
                .orElseThrow(() -> new AssertionError("no line was written for " + event));
        final String tail = line.substring(line.indexOf("duration_ms=") + "duration_ms=".length());
        final int end = tail.indexOf(' ');
        return Duration.ofMillis(Long.parseLong(end < 0 ? tail : tail.substring(0, end)));
    }

    /**
     * One event's structured arguments, read as the fields the encoder will emit.
     *
     * @param event one captured line
     * @return its fields, by name, in the order they were written
     */
    private static Map<String, String> fieldsOf(final ILoggingEvent event) {
        final Map<String, String> fields = new LinkedHashMap<>();
        for (final Object argument : event.getArgumentArray() == null
                ? new Object[0] : event.getArgumentArray()) {
            if (argument instanceof SingleFieldAppendingMarker field) {
                fields.put(field.getFieldName(), field.toStringSelf());
            }
        }
        return fields;
    }

    private static List<String> addressedTo(final List<SentEmail> sent) {
        return sent.stream()
                .map(email -> MAPPER.readTree(email.body()).path("sendToAddress").stringValue())
                .toList();
    }

    private static List<UUID> attachedFiles(final List<SentEmail> sent) {
        return sent.stream()
                .map(email -> MAPPER.readTree(email.body()))
                .map(body -> UUID.fromString(body.path("fileId").stringValue()))
                .distinct()
                .toList();
    }

}
