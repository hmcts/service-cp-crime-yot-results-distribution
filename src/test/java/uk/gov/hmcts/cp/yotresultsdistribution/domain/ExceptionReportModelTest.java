package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import static org.assertj.core.api.Assertions.entry;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The records of the report's model that carry behaviour, and the one shape a success has.
 *
 * <p>Most of this model is data: a kind, an entry, a bounded reason. Several things are not, and
 * each of them is a way a morning could look like something it is not. A window read backwards - or
 * read with no width at all - would report nothing and read as a quiet night. A count map that
 * omitted the kinds with nothing in them would make an empty morning indistinguishable from a
 * morning the report did not run. An entry with no kind is a row nothing can count and no query can
 * select. And a delivered outcome whose accepted count was left at nought would say the log sink
 * told nobody.
 *
 * <p><strong>The two windows are two different questions</strong>, which is why there are two
 * factories and a case for each. A scheduled run knows the instant it fired and is asking about the
 * period its own occurrence closes; an operator typing the bare command knows only what time it is
 * now and is asking what has gone wrong since the last run reported. One factory answering both
 * would be a period too wide for one of them, and this and {@code LastScheduledRunTest} are the only
 * two homes for that arithmetic: every later suite that needs a window mocks it.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the exception report's model")
class ExceptionReportModelTest {

    private static final ZoneId COURTS = ZoneId.of("Europe/London");

    private static final String COURTS_ZONE = "Europe/London";

    /** The morning report: 07:00 on a weekday, read in the courts' own zone. */
    private static final String REPORT_CRON = "0 0 7 * * MON-FRI";

    private static final String RUN_ID = "run-7f1c9204";

    /**
     * What every read here is made through, so a case reports the property it is about.
     *
     * <p>These are pure computations over a window, a count or a fold, and none of the cases below
     * hands one an argument it refuses. Made through {@code assertThatCode}, a refusal is recorded
     * as a failure of its own rather than stopping the case at the arrangement, which is what lets
     * one run report every property rather than the first one that broke.
     */
    private static final String SEAM =
            "the model computes rather than refuses: this call answered";

    /** How long after its own occurrence a scheduled run really asks for its window. */
    private static final long FIRING_DELAY_MILLIS = 50;

    private static final UUID NOTIFICATION_ID =
            UUID.fromString("9c4e1d70-2b85-4a36-b1f8-6d0a74c9e523");

    private static final UUID TEMPLATE_ID =
            UUID.fromString("5f0b2d71-8c43-4e19-a6d2-7b1e4c093a58");

    private static final UUID FILE_ID =
            UUID.fromString("3a7f1c92-6d84-4b05-9e73-1c2b8a4e07d5");

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * What a window may be, and the two ways of asking for one.
     */
    @Nested
    @DisplayName("the window a report is about")
    class TheWindow {

        @Test
        void a_window_read_backwards_is_refused() {
            final Instant morning = london(2026, 9, 14, 7, 0);
            final Instant evening = london(2026, 9, 14, 19, 0);

            softly.assertThatThrownBy(() -> new ReportWindow(evening, morning))
                    .as("a window read backwards reports nothing and looks exactly like a quiet "
                            + "morning, which is the one failure this report exists to make "
                            + "impossible")
                    .isInstanceOf(IllegalArgumentException.class);
            softly.assertThatCode(() -> new ReportWindow(morning, evening))
                    .as("and a window read forwards is the ordinary case")
                    .doesNotThrowAnyException();
        }

        @Test
        void a_window_with_no_width_is_refused() {
            final Instant morning = london(2026, 9, 14, 7, 0);

            softly.assertThatThrownBy(() -> new ReportWindow(morning, morning))
                    .as("a window of no width reports exactly as much as a backwards one - "
                            + "nothing - and reads as the same quiet morning, so it is refused "
                            + "where it is made rather than puzzled over where it is read")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void a_run_firing_exactly_on_the_occurrence_still_starts_at_the_previous_run() {
            final ReportWindow window = scheduledRun(london(2026, 9, 15, 7, 0));

            softly.assertThat(window.from())
                    .as("a run handed its trigger on the very instant it was due has not skipped "
                            + "a period: the occurrence at or before it is its own, and the window "
                            + "opens at the run before that")
                    .isEqualTo(london(2026, 9, 14, 7, 0));
            softly.assertThat(window.to())
                    .as("and it ends at the moment the run started")
                    .isEqualTo(london(2026, 9, 15, 7, 0));
        }

        @Test
        void a_run_firing_fifty_millis_late_starts_at_the_previous_run() {
            final Instant firing = london(2026, 9, 15, 7, 0).plusMillis(FIRING_DELAY_MILLIS);

            final ReportWindow window = scheduledRun(firing);

            softly.assertThat(window.from())
                    .as("the ordinary case, and it answers the same window the exact one does: a "
                            + "scheduler hands a run its trigger a moment after the instant it was "
                            + "due, and a period boundary is not a thing to be on the wrong side of")
                    .isEqualTo(london(2026, 9, 14, 7, 0));
            softly.assertThat(window.to())
                    .as("and it ends at its own occurrence rather than at the moment the trigger "
                            + "arrived: the fifty milliseconds between the two belong to the next "
                            + "window, which opens on exactly this instant")
                    .isEqualTo(london(2026, 9, 15, 7, 0));
        }

        @Test
        void a_scheduled_runs_window_ends_at_its_own_occurrence_not_at_its_start_time() {
            final Instant firing = london(2026, 9, 15, 7, 0).plusSeconds(2);

            final ReportWindow window = scheduledRun(firing);

            softly.assertThat(window.to())
                    .as("the end is the occurrence the schedule names and not the reading the "
                            + "scheduler happened to hand the run: an end taken off the firing "
                            + "instant moves with how busy the pod was, and a boundary that moves "
                            + "is a boundary the next run cannot open on")
                    .isEqualTo(london(2026, 9, 15, 7, 0));
            softly.assertThat(window.from())
                    .as("and the start is the occurrence before that one, which is where the run "
                            + "that last reported closed")
                    .isEqualTo(london(2026, 9, 14, 7, 0));
        }

        @Test
        void a_scheduled_window_is_half_open_so_consecutive_runs_never_overlap() {
            final ReportWindow monday = scheduledRun(london(2026, 9, 14, 7, 0).plusSeconds(3));
            final ReportWindow tuesday = scheduledRun(london(2026, 9, 15, 7, 0).plusSeconds(9));

            softly.assertThat(monday.to())
                    .as("one window closes exactly where the next opens, whatever the two runs' "
                            + "own start times were: a row failing on that instant belongs to "
                            + "exactly one of them, and the ends are exclusive so it is the later")
                    .isEqualTo(tuesday.from());
            softly.assertThat(tuesday.to())
                    .as("and the later window closes on its own occurrence in turn, so a failure "
                            + "after it waits for tomorrow rather than being read by both")
                    .isEqualTo(london(2026, 9, 15, 7, 0));
        }

        @Test
        void a_late_start_at_half_past_still_starts_at_the_previous_run() {
            final Instant firing = london(2026, 9, 15, 7, 30);

            final ReportWindow window = scheduledRun(firing);

            softly.assertThat(window.from())
                    .as("a run held up half an hour still covers its own period: the window is "
                            + "aligned to the schedule rather than to the scheduler, so a delay "
                            + "moves neither end and no failure falls between two reports")
                    .isEqualTo(london(2026, 9, 14, 7, 0));
            softly.assertThat(window.to())
                    .as("and the end is the occurrence it was due at; what went wrong in the half "
                            + "hour it was held up is the next run's to report, once")
                    .isEqualTo(london(2026, 9, 15, 7, 0));
        }

        @Test
        void a_monday_run_starts_at_fridays_run() {
            final Instant firing = london(2026, 9, 14, 7, 0).plusMillis(FIRING_DELAY_MILLIS);

            softly.assertThat(scheduledRun(firing).from())
                    .as("a weekday schedule has no weekend occurrence, so a Monday morning reaches "
                            + "back to Friday and the weekend is inside a window rather than "
                            + "outside every one")
                    .isEqualTo(london(2026, 9, 11, 7, 0));
        }

        @Test
        void a_bare_call_at_six_fifty_nine_on_tuesday_starts_at_mondays_run() {
            softly.assertThat(bareCall(london(2026, 9, 15, 6, 59)).from())
                    .as("an operator typing the bare command a minute before the morning run "
                            + "reads the window that run is about to read, which opens at "
                            + "yesterday's run and not at the one before it")
                    .isEqualTo(london(2026, 9, 14, 7, 0));
        }

        @Test
        void a_bare_call_at_nine_on_tuesday_starts_at_todays_run() {
            softly.assertThat(bareCall(london(2026, 9, 15, 9, 0)).from())
                    .as("and two hours after that run, the same bare command answers what has "
                            + "gone wrong since this morning's report rather than repeating it")
                    .isEqualTo(london(2026, 9, 15, 7, 0));
        }
    }

    /**
     * What a report and its entries refuse to be, and what a run of every kind counts to.
     */
    @Nested
    @DisplayName("the report and its entries")
    class TheReport {

        @Test
        void counts_answers_zero_for_every_kind_that_has_none() {
            final ExceptionReport report = report(
                    exception(ExceptionKind.REQUEST_FAILED),
                    exception(ExceptionKind.REQUEST_FAILED),
                    exception(ExceptionKind.BATCH_LATE));

            softly.assertThat(counts(report))
                    .as("one number per kind always, so an empty kind is a nought a dashboard "
                            + "can read rather than an absence it has to interpret")
                    .containsOnly(
                            entry(ExceptionKind.REQUEST_FAILED, 2),
                            entry(ExceptionKind.REQUEST_LATE, 0),
                            entry(ExceptionKind.BATCH_LATE, 1),
                            entry(ExceptionKind.BATCH_FAILED, 0),
                            entry(ExceptionKind.NOTIFICATION_FAILED, 0),
                            entry(ExceptionKind.BATCH_RELEASED, 0));
            softly.assertThat(counts(report()))
                    .as("and a morning with nothing wrong is a nought for every kind - every one "
                            + "of them read, because a map holding one nought among five absences "
                            + "would satisfy a weaker assertion and tell a dashboard nothing")
                    .containsOnlyKeys(ExceptionKind.values())
                    .allSatisfy((kind, count) -> softly.assertThat(count)
                            .as("the count of %s on a morning with nothing wrong", kind)
                            .isZero());
        }

        @Test
        void an_entry_without_a_kind_is_refused() {
            softly.assertThatThrownBy(() -> new ExceptionEntry(null, null, null, null, null, null,
                            null, null, null, null, null, null, 0))
                    .as("the kind is the one field every one of them carries, so an entry "
                            + "without one is a row no count can be taken of, no query can select "
                            + "and no CSV column can hold")
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void a_batch_failed_entry_with_no_bounded_reason_is_refused() {
            softly.assertThatThrownBy(() -> new ExceptionEntry(ExceptionKind.BATCH_FAILED, null,
                            null, null, null, UUID.randomUUID(), null, null, null,
                            BatchStatus.FAILED.name(), null, null, 0))
                    .as("the reason is the whole of what a dead batch tells an operator: BATCH_LATE "
                            + "says which stage it stopped at and a support engineer knows what to "
                            + "look at, and BATCH_FAILED without one says a batch ended and "
                            + "nothing else. The read selects a NOT NULL column on a row whose "
                            + "status is FAILED, so an absent one is a projection that has drifted "
                            + "from the table - which is a thing to be told about, not a field to "
                            + "leave out of an event")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void a_report_without_entries_is_refused() {
            softly.assertThatThrownBy(
                            () -> ExceptionReport.whole(RUN_ID, aWindow(), aWindow().to(),
                                    null))
                    .as("an absent list is not an empty morning: a report that quietly read as "
                            + "nothing wrong is exactly the silence this service exists to end, "
                            + "and the rest of this model refuses a null rather than interpreting "
                            + "one")
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void a_delivered_log_outcome_is_one_accepted_and_none_refused() {
            final AtomicReference<DeliveryOutcome> answered = new AtomicReference<>(null);
            softly.assertThatCode(
                            () -> answered.set(DeliveryOutcome.delivered(ReportSinkName.LOG)))
                    .as(SEAM)
                    .doesNotThrowAnyException();

            softly.assertThat(answered.get())
                    .as("the log sink has one audience, so a delivered outcome is one accepted and "
                            + "none refused, and its reason is NONE rather than absent")
                    .isEqualTo(new DeliveryOutcome(ReportSinkName.LOG, DeliveryStatus.DELIVERED,
                            ReportDeliveryReason.NONE, 1, 0));
        }
    }

    /**
     * The one e-mail record that carries behaviour: the map the template substitutes from.
     */
    @Nested
    @DisplayName("the report e-mail")
    class TheMail {

        @Test
        void a_mail_with_no_personalisation_carries_an_empty_map() {
            softly.assertThat(mail(null).personalisation())
                    .as("a template with nothing to substitute is an empty map rather than a null "
                            + "every adapter and every log line has to remember to guard")
                    .isEmpty();
        }

        @Test
        void personalisation_is_copied() {
            final Map<String, String> given = new HashMap<>(Map.of("request_failed", "2"));

            final ReportMail mail = mail(given);
            given.put("request_failed", "9");

            softly.assertThat(mail.personalisation())
                    .as("what the sink composed is what the adapter posts: a map the caller can "
                            + "still edit is a body that changes between being written and being "
                            + "sent")
                    .containsExactly(entry("request_failed", "2"));
            softly.assertThatThrownBy(() -> mail.personalisation().put("batch_late", "1"))
                    .as("and it is frozen on the way out too, so nothing downstream can add a key "
                            + "the template never had")
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    /**
     * The fold and the word the run line is written from, which the job and the command share.
     *
     * <p>They shared nothing until review gate 6: each held a private copy of the three-state fold
     * and of the {@code delivered_email} word, and the copies had already diverged - the job called
     * an absent e-mail sink {@code disabled} and the command called the same absent sink
     * {@code skipped}. A dashboard filtered on the counter and an operator reading a terminal have
     * to partition a morning the same way, so the fold and the word live here, are asserted here,
     * and the two callers agree by construction rather than by review.
     *
     * <p>The four words are four different operational facts and the cases below are one per fact,
     * because the pair that is easy to conflate - "nobody asked" and "nobody could" - is exactly
     * the pair the two copies conflated.
     */
    @Nested
    @DisplayName("the run's own fold and the word it says about a sink")
    class TheRunsOwnWords {

        @Test
        void a_run_every_sink_it_asked_took_is_delivered() {
            softly.assertThat(outcomeOf(List.of(took(ReportSinkName.LOG),
                            took(ReportSinkName.EMAIL))))
                    .as("both audiences were told, which is the only shape that is simply done")
                    .isEqualTo(ReportRunOutcome.DELIVERED);
        }

        @Test
        void a_run_one_of_two_sinks_took_is_partial() {
            softly.assertThat(outcomeOf(List.of(took(ReportSinkName.LOG),
                            refused(ReportSinkName.EMAIL))))
                    .as("a report that reached one of its two audiences is neither a success nor a "
                            + "silence: it is a resend (FR-007)")
                    .isEqualTo(ReportRunOutcome.PARTIAL);
        }

        @Test
        void a_run_no_sink_took_is_failed() {
            softly.assertThat(outcomeOf(List.of(refused(ReportSinkName.LOG),
                            refused(ReportSinkName.EMAIL))))
                    .as("nobody was told what went wrong, which is the one outcome that has to be "
                            + "alertable on its own")
                    .isEqualTo(ReportRunOutcome.FAILED);
        }

        @Test
        void a_run_that_asked_nobody_is_failed() {
            softly.assertThat(outcomeOf(List.of()))
                    .as("a run that asked nobody is a run that could not build a report at all, or "
                            + "one on a context holding no sink - and either way nothing was told")
                    .isEqualTo(ReportRunOutcome.FAILED);
        }

        @Test
        void a_lone_partially_delivered_sink_is_partial_not_failed() {
            softly.assertThat(outcomeOf(List.of(new DeliveryOutcome(ReportSinkName.EMAIL,
                            DeliveryStatus.PARTIALLY_DELIVERED, ReportDeliveryReason.SEND_FAILED,
                            2, 1))))
                    .as("two of the three recipients hold the report and the third is a resend, "
                            + "which is the definition of partial: calling it failed tells an "
                            + "operator nobody was told, and the two people reading it are")
                    .isEqualTo(ReportRunOutcome.PARTIAL);
        }

        @Test
        void a_run_is_failed_only_when_no_sink_delivered_anything() {
            softly.assertThat(outcomeOf(List.of(took(ReportSinkName.LOG),
                            new DeliveryOutcome(ReportSinkName.EMAIL,
                                    DeliveryStatus.PARTIALLY_DELIVERED,
                                    ReportDeliveryReason.SEND_REFUSED, 1, 2))))
                    .as("a morning in which the index holds the report and some of support does "
                            + "is a resend for the rest, not a morning nobody was told about")
                    .isEqualTo(ReportRunOutcome.PARTIAL);
            softly.assertThat(outcomeOf(List.of(
                            new DeliveryOutcome(ReportSinkName.LOG, DeliveryStatus.NOT_DELIVERED,
                                    ReportDeliveryReason.LOG_WRITE_FAILED, 0, 1),
                            new DeliveryOutcome(ReportSinkName.EMAIL,
                                    DeliveryStatus.PARTIALLY_DELIVERED,
                                    ReportDeliveryReason.SEND_REFUSED, 1, 2))))
                    .as("and failed is reserved for the morning in which every sink delivered "
                            + "nothing at all, which is the one an alert has to be able to mean")
                    .isEqualTo(ReportRunOutcome.PARTIAL);
        }

        @Test
        void the_word_for_a_sink_that_was_asked_is_ok_or_failed() {
            softly.assertThat(wordFor(ReportSinkName.LOG, List.of(took(ReportSinkName.LOG)), true))
                    .as("asked, and it took it")
                    .isEqualTo(DeliveryWord.OK);
            softly.assertThat(wordFor(ReportSinkName.LOG, List.of(refused(ReportSinkName.LOG)),
                            true))
                    .as("asked, and it did not")
                    .isEqualTo(DeliveryWord.FAILED);
        }

        @Test
        void the_word_for_a_sink_that_is_here_and_was_not_asked_is_skipped() {
            softly.assertThat(wordFor(ReportSinkName.EMAIL, List.of(took(ReportSinkName.LOG)),
                            false))
                    .as("nobody asked: the output exists on this deployment and this invocation "
                            + "did not want it, which only the command can produce")
                    .isEqualTo(DeliveryWord.SKIPPED);
        }

        @Test
        void the_word_for_a_sink_that_is_not_on_this_context_is_disabled_asked_or_not() {
            final List<DeliveryOutcome> onlyTheLog = List.of(took(ReportSinkName.LOG));

            softly.assertThat(answered(() -> DeliveryWord.forSink(ReportSinkName.EMAIL, onlyTheLog,
                            false, false), null))
                    .as("nobody could: there is no e-mail output here at all, which is a different "
                            + "fact from an invocation choosing not to use one")
                    .isEqualTo(DeliveryWord.DISABLED);
            softly.assertThat(answered(() -> DeliveryWord.forSink(ReportSinkName.EMAIL, onlyTheLog,
                            false, true), null))
                    .as("and a caller that asks every sink there is - which is what the 07:00 run "
                            + "does - says the same word about the sink that is not there")
                    .isEqualTo(DeliveryWord.DISABLED);
        }

        @Test
        void the_job_and_the_command_say_the_same_word_about_the_same_context() {
            final List<DeliveryOutcome> onlyTheLog = List.of(took(ReportSinkName.LOG));

            softly.assertThat(answered(() -> DeliveryWord.forSink(ReportSinkName.EMAIL, onlyTheLog,
                            false, true), null))
                    .as("the divergence this type exists to end: one caller asks every sink and "
                            + "the other asks the ones it chose, and an e-mail sink that is not on "
                            + "the context is the same absence to both of them")
                    .isEqualTo(answered(() -> DeliveryWord.forSink(ReportSinkName.EMAIL, onlyTheLog,
                            false, false), null));
        }

        @Test
        void the_word_is_written_the_way_every_bounded_label_here_is() {
            softly.assertThat(answered(DeliveryWord.DISABLED::said, ""))
                    .as("lower case, said once here rather than spelled by each caller: a label a "
                            + "caller renders is a label a caller can render differently")
                    .isEqualTo("disabled");
        }

        private ReportRunOutcome outcomeOf(final List<DeliveryOutcome> delivered) {
            return answered(() -> ReportRunOutcome.from(delivered), null);
        }

        private DeliveryWord wordFor(final ReportSinkName sink,
                final List<DeliveryOutcome> delivered, final boolean asked) {

            return answered(() -> DeliveryWord.forSink(sink, delivered, true, asked), null);
        }

        private DeliveryOutcome took(final ReportSinkName sink) {
            return DeliveryOutcome.delivered(sink);
        }

        private DeliveryOutcome refused(final ReportSinkName sink) {
            return new DeliveryOutcome(sink, DeliveryStatus.NOT_DELIVERED,
                    ReportDeliveryReason.SEND_FAILED, 0, 1);
        }
    }

    // --- the model, asked so that a seam's refusal is recorded rather than thrown ---------------

    /**
     * The window a scheduled run asks for, with a refusal recorded rather than thrown.
     *
     * <p>The backstop is a window at the epoch rather than a null, so a case that could not get an
     * answer fails on the instant it read and not on a null pointer - and it runs forwards, because
     * the record refuses one that does not.
     *
     * @param firedAt the moment the run was handed its trigger
     * @return the window, or the epoch backstop where the seam refused
     */
    private ReportWindow scheduledRun(final Instant firedAt) {
        return answered(() -> ReportWindow.forScheduledRun(REPORT_CRON, COURTS_ZONE, firedAt),
                new ReportWindow(Instant.EPOCH, Instant.EPOCH.plusSeconds(1)));
    }

    /**
     * The window a bare command asks for, with a refusal recorded rather than thrown.
     *
     * @param now the moment the operator asked
     * @return the window, or the epoch backstop where the seam refused
     */
    private ReportWindow bareCall(final Instant now) {
        return answered(() -> ReportWindow.sinceLastScheduledRun(REPORT_CRON, COURTS_ZONE, now),
                new ReportWindow(Instant.EPOCH, Instant.EPOCH.plusSeconds(1)));
    }

    private Map<ExceptionKind, Integer> counts(final ExceptionReport report) {
        return answered(report::counts, Map.of());
    }

    private <T> T answered(final Supplier<T> asked, final T whenRefused) {
        final AtomicReference<T> answer = new AtomicReference<>(whenRefused);
        softly.assertThatCode(() -> answer.set(asked.get())).as(SEAM).doesNotThrowAnyException();
        return answer.get();
    }

    // --- fixtures -----------------------------------------------------------------------------

    private static ExceptionReport report(final ExceptionEntry... entries) {
        return ExceptionReport.whole(RUN_ID, aWindow(), london(2026, 9, 14, 7, 0),
                List.of(entries));
    }

    private static ReportWindow aWindow() {
        return new ReportWindow(london(2026, 9, 11, 7, 0), london(2026, 9, 14, 7, 0));
    }

    private static ReportMail mail(final Map<String, String> personalisation) {
        return new ReportMail(NOTIFICATION_ID, TEMPLATE_ID, "yot@example.gov.uk", FILE_ID,
                personalisation);
    }

    /**
     * One entry of a kind, carrying only what that kind needs for a count to be about the kind.
     *
     * <p>The other twelve components are what the kind table says they are on the kinds that carry
     * them; a case about counting says nothing about them and leaves them empty rather than
     * inventing values a later assertion could come to depend on.
     */
    private static ExceptionEntry exception(final ExceptionKind kind) {
        return new ExceptionEntry(kind, null, null, null, null, null, null, null, null, null, null,
                null, 0);
    }

    private static Instant london(final int year, final int month, final int day, final int hour,
            final int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, COURTS).toInstant();
    }
}
