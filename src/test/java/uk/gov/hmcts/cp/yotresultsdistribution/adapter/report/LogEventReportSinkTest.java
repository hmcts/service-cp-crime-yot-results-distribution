package uk.gov.hmcts.cp.yotresultsdistribution.adapter.report;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import net.logstash.logback.marker.SingleFieldAppendingMarker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionEntry;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionKind;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionReport;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportSinkName;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportWindow;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PersonalDataMarkers;

/**
 * The two events the report reaches Log Analytics as.
 *
 * <p>The whole reason the log output is a sink rather than a {@code log.info} in the service is
 * that the field shape is an adapter's concern: the values have to reach the encoder as
 * <strong>structured arguments</strong>, so a saved query reads {@code kind}, {@code request_id}
 * and {@code batch_id} as fields rather than parsing them back out of a sentence. So the assertions
 * below read the arguments as arguments, and then read the message text to confirm that none of
 * those values is also in it.
 *
 * <p><strong>The summary event carries no delivery status of any kind</strong>, and that is the
 * case worth reading twice. It is written by a sink, while the other sink may not have been asked
 * yet and this one cannot observe its own write reaching an index. A line claiming a delivery
 * nobody had observed is worse than no claim at all; how the report travelled is the run's own fact
 * and belongs to whoever held every outcome.
 */
@DisplayName("the report, written as events")
class LogEventReportSinkTest {

    private static final Instant WINDOW_FROM = Instant.parse("2026-09-14T06:00:00Z");

    private static final Instant WINDOW_TO = Instant.parse("2026-09-15T06:00:00Z");

    private static final Instant SNAPSHOT_AT = Instant.parse("2026-09-15T06:00:01Z");

    private static final String RUN_ID = "run-4b19c7e0";

    private static final String SOURCE = "cpp-context-results";

    private static final UUID REQUEST_ID =
            UUID.fromString("7a1c4d90-3e62-4b58-9f07-2d4a8c1e6503");

    private static final UUID HEARING_ID =
            UUID.fromString("2e8b5f41-9c03-4d76-8a15-6b0e3f7c2d94");

    private static final UUID BATCH_ID =
            UUID.fromString("11111111-2222-4333-8444-555555555555");

    private static final UUID COURT_CENTRE =
            UUID.fromString("2f6b8d10-4a3c-4e57-9b21-8c0d5e7f1a94");

    private static final LocalDate HEARING_DAY = LocalDate.of(2026, 9, 14);

    private static final LocalDate REGISTER_DATE = LocalDate.of(2026, 9, 14);

    private static final long AGE_SECONDS = 4_000L;

    private static final int ATTEMPTS = 3;

    /**
     * The twelve fields the summary event carries, stated once in data-model.md and once here.
     *
     * <p>Eleven since the entry cap: a report that dropped entries has to say so in the same line
     * as its counts, because the counts are the full ones and a reader comparing them against the
     * exception events would otherwise find events missing and nothing saying why.
     *
     * <p><strong>Twelve since increment 004</strong>, which added the sixth kind. The list is one
     * count per {@link ExceptionKind} plus the five fields around them, and it has to stay that
     * way for the reason the {@code truncated} field exists at all: the counts are of what the
     * reads found and the events are of what the report carries, so a count this line leaves out
     * is a morning whose events outnumber its counts, which is the shape of a sink that broke.
     * A released batch is exactly such a morning - the one kind that can be the only thing a night
     * produced - so its absence here was not a cosmetic gap.
     */
    private static final List<String> THE_TWELVE_SUMMARY_FIELDS = List.of(
            "event", "run_id", "window_from", "window_to", "snapshot_at",
            "request_failed", "request_late", "batch_late", "batch_failed", "notification_failed",
            "batch_released", "truncated");

    /**
     * What an identifier, a bounded code, a date or a number looks like, and what free text does
     * not: every value below is one token, with no whitespace and no sentence punctuation in it.
     */
    private static final Pattern NOTHING_BUT_A_TOKEN = Pattern.compile("[A-Za-z0-9:_.+\\-]+");

    private final LogEventReportSink sink = new LogEventReportSink();

    /**
     * The one line a run writes about the report as a whole.
     */
    @Nested
    @DisplayName("the summary event")
    class TheSummary {

        /**
         * The counts are the full ones and the events are not, so the line has to say the number.
         *
         * <p>A capped report writes an event per entry it kept and counts every entry the reads
         * found, which is the right way round - a count that shrank with the cap would make a bad
         * morning look like a quieter one - but it leaves a query able to find fewer events than
         * the summary counts and nothing saying why. {@code truncated} is that field, and it is
         * nought on every ordinary morning rather than absent, for the reason the five counts are.
         */
        @Test
        void the_summary_event_carries_the_truncated_count() {
            final Map<String, String> capped = fieldsOf(summaryFrom(new ExceptionReport(RUN_ID,
                    new ReportWindow(WINDOW_FROM, WINDOW_TO), SNAPSHOT_AT,
                    List.of(requestFailed()), 4,
                    Map.of(ExceptionKind.REQUEST_FAILED, 5))));

            assertThat(capped)
                    .as("how many the cap dropped, so a query that finds one event under a count "
                            + "of five can tell a truncated morning from a missing write")
                    .containsEntry("truncated", "4");
            assertThat(fieldsOf(summaryFrom(reportOf(requestFailed()))))
                    .as("and nought rather than absent on an ordinary morning, for the reason the "
                            + "five counts are always present: absent is a field a dashboard has "
                            + "to interpret")
                    .containsEntry("truncated", "0");
        }

        @Test
        void one_summary_event_carries_its_twelve_fields() {
            final Map<String, String> fields = fieldsOf(summaryFrom(reportOf(requestFailed())));

            assertThat(fields)
                    .as("twelve is the number data-model.md states, and this is the assertion that "
                            + "holds it: a field added here is a field a saved query does not read "
                            + "and a field removed is one it reads as absent")
                    .containsOnlyKeys(THE_TWELVE_SUMMARY_FIELDS.toArray(new String[0]));
            assertThat(fields)
                    .containsEntry("event", "yotresultsdistribution_exception_report")
                    .containsEntry("run_id", RUN_ID)
                    .containsEntry("window_from", WINDOW_FROM.toString())
                    .containsEntry("window_to", WINDOW_TO.toString())
                    .containsEntry("snapshot_at", SNAPSHOT_AT.toString())
                    .containsEntry("request_failed", "1");
            assertThat(fields)
                    .as("and a kind with nothing in it is a zero, not an absence (FR-012)")
                    .containsEntry("request_late", "0")
                    .containsEntry("batch_late", "0")
                    .containsEntry("batch_failed", "0")
                    .containsEntry("notification_failed", "0")
                    .containsEntry("batch_released", "0");
        }

        /**
         * The regression the T046 walkthrough found: a released batch counted nowhere on the line.
         *
         * <p>Increment 004 added {@code BATCH_RELEASED} and extended the CSV sink's header and the
         * command's counts line to six, and left this one at five. A morning whose only exception
         * was a released batch therefore wrote a summary of five noughts beside one
         * {@code yotresultsdistribution_exception} event - which is precisely what this sink's own contract
         * says never happens, and what a reader is told to treat as a sink that broke. It is a
         * regression of 004 against 003 and not a legacy defect, so it earns no
         * {@code DEFECT-FIXES.md} row; it earns this case.
         */
        @Test
        void a_released_batch_is_counted_on_the_summary_line() {
            final Map<String, String> fields = fieldsOf(summaryFrom(reportOf(batchReleased())));

            assertThat(fields)
                    .as("the one kind that can be the whole of a morning: if it is not counted "
                            + "here, the summary says nothing was wrong beside an event saying "
                            + "something was, and no query can tell that from a lost write")
                    .containsEntry("batch_released", "1")
                    .containsEntry("batch_failed", "0");
        }

        @Test
        void an_empty_report_writes_exactly_one_event() {
            final List<ILoggingEvent> written = eventsFrom(reportOf());

            assertThat(written)
                    .as("a quiet morning is a report and not a silence: the summary is written "
                            + "whether or not anything is wrong, which is what lets an index tell "
                            + "a morning with nothing wrong from a morning the report did not run "
                            + "at all (FR-012) - and it is one event, because an exception event "
                            + "written for an empty report would be an exception nobody had")
                    .singleElement()
                    .satisfies(only -> {
                        assertThat(fieldsOf(only))
                                .containsEntry("event", "yotresultsdistribution_exception_report")
                                .containsOnlyKeys(THE_TWELVE_SUMMARY_FIELDS.toArray(new String[0]));
                        assertThat(fieldsOf(only))
                                .as("and the six counts are six noughts, present and readable")
                                .containsEntry("request_failed", "0")
                                .containsEntry("request_late", "0")
                                .containsEntry("batch_late", "0")
                                .containsEntry("batch_failed", "0")
                                .containsEntry("notification_failed", "0")
                                .containsEntry("batch_released", "0");
                    });
        }

        @Test
        void the_summary_event_carries_no_delivery_status_of_any_kind() {
            final Map<String, String> fields = fieldsOf(summaryFrom(reportOf(requestFailed())));

            assertThat(fields.keySet())
                    .as("a sink can observe neither its own arrival in an index nor the other "
                            + "sink's, so a delivered_* field written here would be a claim about "
                            + "an outcome nobody had observed; how the report travelled is the "
                            + "run's own fact and belongs on the job's exception_report_run line")
                    .noneMatch(field -> field.startsWith("delivered"))
                    .doesNotContain("outcome", "sink", "status");
        }
    }

    /**
     * One line per thing wrong, carrying only what applies to its kind.
     */
    @Nested
    @DisplayName("the exception events")
    class TheExceptions {

        @Test
        void one_exception_event_is_written_per_entry() {
            final List<ILoggingEvent> written = eventsFrom(reportOf(
                    requestFailed(), batchFailed(), notificationFailed()));

            assertThat(exceptionEvents(written))
                    .as("one event per exception, so a saved query counts rows rather than "
                            + "parsing a list out of one line")
                    .hasSize(3)
                    .extracting(event -> fieldsOf(event).get("kind"))
                    .containsExactly(ExceptionKind.REQUEST_FAILED.name(),
                            ExceptionKind.BATCH_FAILED.name(),
                            ExceptionKind.NOTIFICATION_FAILED.name());
            assertThat(written)
                    .as("and the summary beside them, once")
                    .hasSize(4);
        }

        @Test
        void a_batch_failed_event_carries_its_bounded_reason_and_no_generator_text() {
            final Map<String, String> fields =
                    fieldsOf(theOneExceptionEvent(eventsFrom(reportOf(batchFailed()))));

            assertThat(fields)
                    .as("what is reported is this service's own bounded code, which is what a "
                            + "support engineer pastes into a ticket")
                    .containsEntry("reason", BatchFailureReason.GENERATION_FAILED.name());
            assertThat(fields.values())
                    .as("systemdocgenerator's own words about a document whose every defendant is "
                            + "a child are in no select list this feature writes, so there is "
                            + "nothing here for them to have reached (Principle VII)")
                    .noneMatch(value -> value.contains(PersonalDataMarkers.GENERATOR_REASON));
        }

        @Test
        void a_field_that_does_not_apply_to_the_kind_is_absent_rather_than_null() {
            final Map<String, String> fields =
                    fieldsOf(theOneExceptionEvent(eventsFrom(reportOf(requestFailed()))));

            assertThat(fields.keySet())
                    .as("an absent field is what makes a KQL isnotempty() mean what it says; a "
                            + "null emitted here would read as 'was not known' rather than as "
                            + "'does not apply to this kind'")
                    .doesNotContain("batch_id", "notification_id", "court_centre_id",
                            "register_date");
            assertThat(fields.values())
                    .as("and nothing is emitted as the word null either")
                    .doesNotContain("null");
            assertThat(fields)
                    .containsEntry("source", SOURCE)
                    .containsEntry("request_id", REQUEST_ID.toString())
                    .containsEntry("hearing_id", HEARING_ID.toString())
                    .containsEntry("hearing_day", HEARING_DAY.toString())
                    .containsEntry("status", RequestStatus.FAILED.name())
                    .containsEntry("attempts", String.valueOf(ATTEMPTS))
                    .containsEntry("age_seconds", String.valueOf(AGE_SECONDS));
        }
    }

    /**
     * What is true of both events, which is what makes them one report in an index.
     */
    @Nested
    @DisplayName("what holds of every event")
    class BothEvents {

        @Test
        void run_id_is_on_both_events() {
            final List<ILoggingEvent> written = eventsFrom(reportOf(requestFailed(), batchLate()));

            assertThat(written)
                    .as("every line of a run carries the correlation the caller opened, which is "
                            + "what lets a support engineer read one morning's report as one "
                            + "thing (FR-011)")
                    .isNotEmpty()
                    .allSatisfy(event ->
                            assertThat(fieldsOf(event)).containsEntry("run_id", RUN_ID));
        }

        @Test
        void every_value_is_an_identifier_a_bounded_code_or_a_number() {
            final List<ILoggingEvent> written = eventsFrom(reportOf(
                    requestFailed(), batchLate(), batchFailed(), notificationFailed()));

            assertThat(written)
                    .isNotEmpty()
                    .allSatisfy(event -> assertThat(fieldsOf(event).values())
                            .as("free text down here is another system's prose or somebody's "
                                    + "address; everything the report carries is a token")
                            .allMatch(value -> NOTHING_BUT_A_TOKEN.matcher(value).matches()));
        }

        @Test
        void no_identifier_appears_in_the_message_text() {
            try (CapturedLog log = CapturedLog.capturing(LogEventReportSink.class)) {
                sink.deliver(reportOf(requestFailed(), batchFailed()));

                assertThat(log.messages())
                        .as("a drive that wrote nothing would satisfy the claim below vacuously")
                        .isNotEmpty();
                assertThat(log.messages())
                        .as("a value rendered into the message is a value a saved query has to "
                                + "parse() back out, which is exactly what the structured "
                                + "arguments exist to avoid")
                        .allSatisfy(message -> assertThat(message)
                                .doesNotContain(RUN_ID)
                                .doesNotContain(REQUEST_ID.toString())
                                .doesNotContain(HEARING_ID.toString())
                                .doesNotContain(BATCH_ID.toString())
                                .doesNotContain(COURT_CENTRE.toString())
                                .doesNotContain(WINDOW_FROM.toString()));
            }
        }

        @Test
        void the_log_sink_names_itself_and_answers_delivered() {
            assertThat(sink.name())
                    .as("the service has to be able to name the sink it asked, whichever way the "
                            + "delivery went")
                    .isEqualTo(ReportSinkName.LOG);
            assertThat(sink.deliver(reportOf(requestFailed())))
                    .as("one audience, told")
                    .isEqualTo(DeliveryOutcome.delivered(ReportSinkName.LOG));
        }
    }

    private List<ILoggingEvent> eventsFrom(final ExceptionReport report) {
        try (CapturedLog log = CapturedLog.capturing(LogEventReportSink.class)) {
            sink.deliver(report);
            return log.events();
        }
    }

    private ILoggingEvent summaryFrom(final ExceptionReport report) {
        return eventsFrom(report).stream()
                .filter(event -> "yotresultsdistribution_exception_report"
                        .equals(fieldsOf(event).get("event")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no summary event was written"));
    }

    /**
     * The one exception event a single-entry report writes, refused as an assertion where there
     * is none rather than as whatever a read off an empty list raises.
     *
     * @param written everything the sink wrote
     * @return the one exception event
     */
    private static ILoggingEvent theOneExceptionEvent(final List<ILoggingEvent> written) {
        final List<ILoggingEvent> events = exceptionEvents(written);
        assertThat(events)
                .as("one exception is one event, and a report of one that wrote none has nothing "
                        + "for the fields below to be read off")
                .hasSize(1);
        return events.getFirst();
    }

    private static List<ILoggingEvent> exceptionEvents(final List<ILoggingEvent> written) {
        return written.stream()
                .filter(event -> "yotresultsdistribution_exception".equals(fieldsOf(event).get("event")))
                .toList();
    }

    /**
     * One event's structured arguments, read as the fields the encoder will emit.
     *
     * @param event one captured line
     * @return its fields, by name, in the order they were written
     */
    private static Map<String, String> fieldsOf(final ILoggingEvent event) {
        final Map<String, String> fields = new LinkedHashMap<>();
        for (final Object argument : event.getArgumentArray()) {
            final SingleFieldAppendingMarker field = (SingleFieldAppendingMarker) argument;
            fields.put(field.getFieldName(), field.toStringSelf());
        }
        return fields;
    }

    private static ExceptionReport reportOf(final ExceptionEntry... entries) {
        return ExceptionReport.whole(RUN_ID, new ReportWindow(WINDOW_FROM, WINDOW_TO),
                SNAPSHOT_AT, List.of(entries));
    }

    private static ExceptionEntry requestFailed() {
        return new ExceptionEntry(ExceptionKind.REQUEST_FAILED, SOURCE, REQUEST_ID, HEARING_ID,
                HEARING_DAY, null, null, null, null, RequestStatus.FAILED.name(), ATTEMPTS,
                "schema-violation", AGE_SECONDS);
    }

    private static ExceptionEntry batchLate() {
        return new ExceptionEntry(ExceptionKind.BATCH_LATE, null, null, null, null, BATCH_ID, null,
                COURT_CENTRE, REGISTER_DATE, BatchStatus.GENERATING.name(), null,
                "awaiting-render", AGE_SECONDS);
    }

    private static ExceptionEntry batchFailed() {
        return new ExceptionEntry(ExceptionKind.BATCH_FAILED, null, null, null, null, BATCH_ID,
                null, COURT_CENTRE, REGISTER_DATE, BatchStatus.FAILED.name(), null,
                BatchFailureReason.GENERATION_FAILED.name(), AGE_SECONDS);
    }

    private static ExceptionEntry batchReleased() {
        return new ExceptionEntry(ExceptionKind.BATCH_RELEASED, null, null, null, null, BATCH_ID,
                null, COURT_CENTRE, REGISTER_DATE, BatchStatus.FAILED.name(), null,
                BatchFailureReason.NOT_COMPLETED_BY_NEXT_RUN.name(), AGE_SECONDS);
    }

    private static ExceptionEntry notificationFailed() {
        return new ExceptionEntry(ExceptionKind.NOTIFICATION_FAILED, null, null, null, null,
                BATCH_ID, UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa"), COURT_CENTRE,
                REGISTER_DATE, "FAILED", ATTEMPTS, "502", AGE_SECONDS);
    }
}
