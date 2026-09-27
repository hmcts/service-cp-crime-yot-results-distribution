package uk.gov.hmcts.cp.yotresultsdistribution.adapter.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import uk.gov.hmcts.cp.yotresultsdistribution.application.PayloadFileStore;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ReportMailer;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionEntry;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionKind;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionReport;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.MailOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.MailStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.PayloadStoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportDeliveryReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportMail;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportSinkName;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportWindow;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;

/**
 * What the e-mail sink does with one report, over the two ports it holds.
 *
 * <p>A <strong>unit</strong> suite, because the sink holds {@code PayloadFileStore} and
 * {@code ReportMailer} and no HTTP type at all: what goes on the wire is the mailer's suite, what
 * the CSV is composed of is {@code EmailReportSinkStoreIT}'s, and what is left - the order, the
 * fold, the personalisation and the masking - is here.
 *
 * <p><strong>Ids before calls.</strong> The file id is minted and written into the run's line
 * <em>before</em> the file-service write, so an attachment under an id nothing recorded is
 * impossible - the same discipline 002 applies to the payload file id before
 * {@code generate-document}.
 *
 * <p><strong>A sink answers rather than throws</strong> (FR-007), so every case below goes through
 * {@link #delivered}, which says that as an assertion: one refused recipient is a resend for that
 * recipient, not the end of the morning's report.
 */
@DisplayName("the report's e-mail sink")
class EmailReportSinkTest {

    private static final UUID TEMPLATE_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    /** Three Youth Offending Team inboxes, which is what "one send per recipient" is about. */
    private static final String FIRST = "first.inbox@example.invalid";

    private static final String SECOND = "second.inbox@example.invalid";

    private static final String THIRD = "third.inbox@example.invalid";

    private static final List<String> THREE = List.of(FIRST, SECOND, THIRD);

    private static final Instant SNAPSHOT_AT = Instant.parse("2026-09-15T06:00:00Z");

    private static final ReportWindow WINDOW =
            new ReportWindow(Instant.parse("2026-09-14T06:00:00Z"), SNAPSHOT_AT);

    private static final String RUN_ID = "a-run-the-caller-already-opened";

    /**
     * One count per kind, spelled as the events and the CSV spell them.
     *
     * <p>Six since increment 004: {@code batch_released} is the informational kind, a batch a run
     * gave up on and re-rendered the same night (FR-019). It travels zero-filled like the rest,
     * because a key that only appeared on the mornings it was non-zero would be a personalisation
     * the template could not lay out.
     */
    private static final List<String> THE_PER_KIND_COUNTS = List.of("request_failed", "request_late",
            "batch_late", "batch_failed", "notification_failed", "batch_released");

    private static final UUID REQUEST_ID = UUID.fromString("4c8e1a70-9b2d-4f36-8a57-c1d0e9f3b284");

    private static final UUID HEARING_ID = UUID.fromString("9e2b4c60-1d38-4a75-9f04-6b3c8d1e5a72");

    private final PayloadFileStore files = mock(PayloadFileStore.class);

    private final ReportMailer mailer = mock(ReportMailer.class);

    @Test
    void the_csv_is_stored_before_any_send_and_its_file_id_is_the_one_on_every_mail() {
        try (CapturedLog log = CapturedLog.capturing(EmailReportSink.class)) {
            final AtomicReference<List<String>> saidByThen = new AtomicReference<>();
            accepted();
            doAnswer(call -> {
                saidByThen.set(log.renderings());
                return null;
            }).when(files).storeText(any(), any(), any());

            delivered(aReportOf(oneRequestFailed()), THREE);

            final ArgumentCaptor<UUID> stored = ArgumentCaptor.forClass(UUID.class);
            final InOrder order = inOrder(files, mailer);
            order.verify(files).storeText(stored.capture(), any(), any());
            order.verify(mailer, times(3)).send(any());
            assertThat(saidByThen.get())
                    .as("ids before calls: an outcome that arrives for a file nothing wrote down "
                            + "is an attachment this service cannot attribute, so the id is in the "
                            + "line before the write that uses it")
                    .anyMatch(line -> line.contains("file_id=" + stored.getValue()));
            assertThat(everyMail())
                    .as("one file, attached by reference: three recipients reading three different "
                            + "ids would be three different mornings")
                    .allMatch(mail -> stored.getValue().equals(mail.fileId()));
        }
    }

    @Test
    void one_mail_per_recipient() {
        accepted();

        final DeliveryOutcome outcome = delivered(aReportOf(oneRequestFailed()), THREE);

        verify(mailer, times(3)).send(any());
        assertThat(everyMail()).extracting(ReportMail::sendToAddress)
                .as("one call per recipient and never a batched one: notificationnotify keys its "
                        + "aggregate on the notification id, so one call for three teams is one "
                        + "resend that can only be made to all three")
                .containsExactly(FIRST, SECOND, THIRD);
        assertThat(everyMail()).extracting(ReportMail::notificationId)
                .as("and each under an identity of its own, so a resend reaches that recipient's "
                        + "attempt")
                .doesNotHaveDuplicates();
        assertThat(outcome)
                .isEqualTo(new DeliveryOutcome(ReportSinkName.EMAIL, DeliveryStatus.DELIVERED,
                        ReportDeliveryReason.NONE, 3, 0));
    }

    @Test
    void the_personalisation_carries_the_five_counts_and_the_window_as_strings() {
        accepted();

        delivered(aReportOf(oneRequestFailed()), List.of(FIRST));

        final Map<String, String> personalisation = everyMail().getFirst().personalisation();
        assertThat(personalisation)
                .as("the summary travels in the body and the detail travels as the CSV: the "
                        + "vendored schema's personalisation is additionalProperties: true inside "
                        + "a body that is otherwise closed, which is what lets the counts travel "
                        + "at all")
                .containsOnlyKeys("request_failed", "request_late", "batch_late", "batch_failed",
                        "notification_failed", "batch_released", "window_from", "window_to");
        assertThat(personalisation).containsEntry("request_failed", "1");
        assertThat(personalisation)
                .as("zero-filled, so a morning with nothing wrong reads as a row of noughts and "
                        + "a window rather than as a body with fields missing (FR-012)")
                .containsEntry("batch_failed", "0");
        assertThat(personalisation).containsEntry("window_from", WINDOW.from().toString());
        assertThat(personalisation).containsEntry("window_to", WINDOW.to().toString());
        assertThat(personalisation)
                .as("Notify substitutes text, so a count travels as the characters of its number "
                        + "and the window as the characters of its two instants")
                .containsEntry("request_late", "0");
    }

    @Test
    void no_identifier_and_no_address_is_ever_a_personalisation_key_or_value() {
        accepted();

        delivered(aReportOf(oneRequestFailed()), THREE);

        assertThat(everyMail()).allSatisfy(mail -> {
            assertThat(mail.personalisation().keySet())
                    .as("every key is one of the per-kind counts or a window boundary")
                    .allSatisfy(key -> assertThat(THE_PER_KIND_COUNTS.contains(key)
                            || key.startsWith("window_")).isTrue());
            assertThat(mail.personalisation().values())
                    .as("the detail travels as the CSV, by file id: an identifier in the body "
                            + "would be a register's own keys inside an e-mail that is read by a "
                            + "template and stored by Notify")
                    .noneMatch(value -> value.contains("@"))
                    .noneMatch(value -> value.contains(REQUEST_ID.toString()))
                    .noneMatch(value -> value.contains(HEARING_ID.toString()));
        });
    }

    @Test
    void one_refused_recipient_does_not_stop_the_other_two() {
        when(mailer.send(any()))
                .thenReturn(new MailOutcome(MailStatus.ACCEPTED, 202))
                .thenReturn(new MailOutcome(MailStatus.REFUSED, 400))
                .thenReturn(new MailOutcome(MailStatus.ACCEPTED, 202));

        final DeliveryOutcome outcome = delivered(aReportOf(oneRequestFailed()), THREE);

        verify(mailer, times(3)).send(any());
        assertThat(outcome)
                .as("scenario 4.2: two teams were told and one is a resend, which is a thing to "
                        + "act on rather than a morning that failed")
                .isEqualTo(new DeliveryOutcome(ReportSinkName.EMAIL,
                        DeliveryStatus.PARTIALLY_DELIVERED, ReportDeliveryReason.SEND_REFUSED,
                        2, 1));
    }

    /**
     * A mailer that breaks is one recipient's problem, not the other two recipients' problem.
     *
     * <p>The port's contract is that it answers, so a throw reaching this loop is a broken mailer
     * rather than a refused send - and the sink is the one place that can still do the right thing
     * with it, which is to count it and ask the next address. Let out, it reaches the service's own
     * catch and the whole delivery is classified as having failed, so two support inboxes that
     * would have received the morning's report do not.
     */
    @Test
    void a_mailer_that_throws_for_one_recipient_does_not_stop_the_others() {
        when(mailer.send(any()))
                .thenReturn(new MailOutcome(MailStatus.ACCEPTED, 202))
                .thenThrow(new IllegalStateException("the mail client refused to build a request"))
                .thenReturn(new MailOutcome(MailStatus.ACCEPTED, 202));

        final DeliveryOutcome outcome = delivered(aReportOf(oneRequestFailed()), THREE);

        verify(mailer, times(3)).send(any());
        assertThat(outcome)
                .as("two told and one to resend, exactly as a refusal would be: which of the "
                        + "mailer's own problems stopped one send is not a reason to stop the "
                        + "sends after it")
                .isEqualTo(new DeliveryOutcome(ReportSinkName.EMAIL,
                        DeliveryStatus.PARTIALLY_DELIVERED, ReportDeliveryReason.SEND_UNANSWERED,
                        2, 1));
    }

    @Test
    void an_empty_report_is_still_sent() {
        accepted();

        final DeliveryOutcome outcome = delivered(aReportOf(), List.of(FIRST));

        verify(files).storeText(any(), any(), any());
        verify(mailer).send(any());
        assertThat(everyMail().getFirst().personalisation())
                .as("FR-012 and scenario 4.4: silence must never be the signal, so the morning "
                        + "nothing was wrong on is an e-mail saying so")
                .containsEntry("request_failed", "0")
                .containsEntry("notification_failed", "0");
        assertThat(outcome.status()).isEqualTo(DeliveryStatus.DELIVERED);
    }

    @Test
    void an_empty_resolved_recipient_list_at_run_time_is_no_recipients() {
        final DeliveryOutcome outcome = delivered(aReportOf(oneRequestFailed()), List.of());

        assertThat(outcome)
                .as("startup refuses this configuration, so an empty list here can only mean it "
                        + "was emptied under a running pod - which is a bounded reason and not a "
                        + "delivery")
                .isEqualTo(new DeliveryOutcome(ReportSinkName.EMAIL, DeliveryStatus.NOT_DELIVERED,
                        ReportDeliveryReason.NO_RECIPIENTS, 0, 0));
        verifyNoInteractions(files, mailer);
    }

    @Test
    void a_store_failure_is_attachment_store_unavailable_and_no_mail_is_sent() {
        doThrow(new PayloadStoreUnavailableException(
                "the file service could not be reached to write the report's content row"))
                .when(files).storeText(any(), any(), any());

        final DeliveryOutcome outcome = delivered(aReportOf(oneRequestFailed()), THREE);

        assertThat(outcome)
                .as("an e-mail whose attachment is not there is an e-mail with nothing attached, "
                        + "and support opening three of them learns less than support opening none")
                .isEqualTo(new DeliveryOutcome(ReportSinkName.EMAIL, DeliveryStatus.NOT_DELIVERED,
                        ReportDeliveryReason.ATTACHMENT_STORE_UNAVAILABLE, 0, 3));
        verify(mailer, never()).send(any());
    }

    /**
     * The two refusal kinds the fold had no case of its own for, and which of them it reports.
     *
     * <p>{@code SEND_REFUSED} was the only reason any case asserted, so the other two arms of
     * {@code reasonFor} were reachable only through the masking case, which reads log lines and not
     * the outcome. The claim here is the one the sink's own comment makes: the first refusal's
     * reason is the delivery's, a later one of another kind is still counted, and the counts say
     * how many of each there were.
     */
    @Test
    void a_failed_and_an_unanswered_send_carry_their_own_reasons_and_the_first_refusal_wins() {
        when(mailer.send(any()))
                .thenReturn(new MailOutcome(MailStatus.FAILED, 503))
                .thenReturn(new MailOutcome(MailStatus.UNANSWERED, null))
                .thenReturn(new MailOutcome(MailStatus.ACCEPTED, 202));

        assertThat(delivered(aReportOf(oneRequestFailed()), THREE))
                .as("the one that stopped the morning first is the one to act on: a 503 is a "
                        + "resend and an unanswered send is a send nobody knows the fate of, and "
                        + "one reason on one outcome cannot describe both")
                .isEqualTo(new DeliveryOutcome(ReportSinkName.EMAIL,
                        DeliveryStatus.PARTIALLY_DELIVERED, ReportDeliveryReason.SEND_FAILED,
                        1, 2));

        when(mailer.send(any()))
                .thenReturn(new MailOutcome(MailStatus.UNANSWERED, null))
                .thenReturn(new MailOutcome(MailStatus.FAILED, 503))
                .thenReturn(new MailOutcome(MailStatus.REFUSED, 400));

        assertThat(delivered(aReportOf(oneRequestFailed()), THREE))
                .as("and the other way round it is the unanswered one, because which reason the "
                        + "delivery carries is which came first and not which is worst")
                .isEqualTo(new DeliveryOutcome(ReportSinkName.EMAIL, DeliveryStatus.NOT_DELIVERED,
                        ReportDeliveryReason.SEND_UNANSWERED, 0, 3));
    }

    /**
     * The id that was minted is on the line that says nothing was sent under it.
     *
     * <p>Ids before calls is only worth the discipline if the id survives the failure: an
     * attachment that could not be stored is the case where somebody has to look for a file, and a
     * warning naming the reason but not the id sends them to look for nothing.
     */
    @Test
    void the_file_id_is_on_the_warn_line_when_the_store_fails() {
        try (CapturedLog log = CapturedLog.capturing(EmailReportSink.class)) {
            doThrow(new PayloadStoreUnavailableException(
                    "the file service could not be reached to write the report's content row"))
                    .when(files).storeText(any(), any(), any());

            delivered(aReportOf(oneRequestFailed()), THREE);

            final ArgumentCaptor<UUID> minted = ArgumentCaptor.forClass(UUID.class);
            verify(files).storeText(minted.capture(), any(), any());
            assertThat(log.renderings())
                    .as("the warning names the file nobody is being told about, its bounded reason "
                            + "and the class of what refused - and never that class's message, "
                            + "which is where a connection string turns up")
                    .anyMatch(line -> line.contains("file_id=" + minted.getValue())
                            && line.contains(
                                    "reason=" + ReportDeliveryReason.ATTACHMENT_STORE_UNAVAILABLE)
                            && line.contains(
                                    "cause=" + PayloadStoreUnavailableException.class.getName()));
        }
    }

    @Test
    void every_address_is_masked_in_every_line_the_sink_writes() {
        try (CapturedLog log = CapturedLog.capturing(EmailReportSink.class)) {
            when(mailer.send(any()))
                    .thenReturn(new MailOutcome(MailStatus.ACCEPTED, 202))
                    .thenReturn(new MailOutcome(MailStatus.UNANSWERED, null))
                    .thenReturn(new MailOutcome(MailStatus.FAILED, 503));

            delivered(aReportOf(oneRequestFailed()), THREE);

            assertThat(log.renderings())
                    .as("this class is the one place an address is in hand, so it is the one place "
                            + "masking has to happen - and the line about the recipient that was "
                            + "not told is the one somebody pastes into a ticket")
                    .isNotEmpty()
                    .noneMatch(line -> line.contains(FIRST))
                    .noneMatch(line -> line.contains(SECOND))
                    .noneMatch(line -> line.contains(THIRD))
                    .anyMatch(line -> line.contains("s***@example.invalid"));
        }
    }

    /** Every mailer answers 202, which is the only answer that is an acceptance. */
    private void accepted() {
        when(mailer.send(any())).thenReturn(new MailOutcome(MailStatus.ACCEPTED, 202));
    }

    /**
     * Delivers one report through a sink over the given recipients, insisting it answered.
     *
     * @param report     the report
     * @param recipients the addresses this deployment is configured with
     * @return how the delivery went
     */
    private DeliveryOutcome delivered(final ExceptionReport report, final List<String> recipients) {
        final AtomicReference<DeliveryOutcome> answered = new AtomicReference<>();

        assertThatCode(() -> answered.set(
                new EmailReportSink(files, mailer, TEMPLATE_ID, recipients).deliver(report)))
                .as("a sink answers how it went rather than throwing: one recipient's refusal is a "
                        + "resend for that recipient and not the end of the morning's report "
                        + "(FR-007)")
                .doesNotThrowAnyException();
        return answered.get();
    }

    /** Every mail the sink asked for, in the order it asked. */
    private List<ReportMail> everyMail() {
        final ArgumentCaptor<ReportMail> asked = ArgumentCaptor.forClass(ReportMail.class);
        verify(mailer, atLeastOnce()).send(asked.capture());
        return asked.getAllValues();
    }

    private static ExceptionReport aReportOf(final ExceptionEntry... entries) {
        return ExceptionReport.whole(RUN_ID, WINDOW, SNAPSHOT_AT, List.of(entries));
    }

    private static ExceptionEntry oneRequestFailed() {
        return new ExceptionEntry(ExceptionKind.REQUEST_FAILED, "cpp-context-results", REQUEST_ID,
                HEARING_ID, LocalDate.of(2026, 9, 14), null, null, null, null, "FAILED", 3,
                "DEAD_LETTERED", 600);
    }
}
