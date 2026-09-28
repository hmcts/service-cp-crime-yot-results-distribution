package uk.gov.hmcts.cp.yotresultsdistribution.adapter.report;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ExceptionReportSink;
import uk.gov.hmcts.cp.yotresultsdistribution.application.PayloadFileStore;
import uk.gov.hmcts.cp.yotresultsdistribution.application.PayloadMetadata;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ReportMailer;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationProperties;
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

/**
 * The report, written as a CSV into the file service and e-mailed to support by id.
 *
 * <p>The detail travels as the attachment and the summary travels in the body: Notify's body has a
 * length limit a bad morning's list would exceed, renders no table, and a truncated list of
 * exceptions is the silence this service exists to end (research §4). So the personalisation
 * carries the five counts and the window - and nothing else, no identifier and no address - and
 * everything a support engineer has to act on is in the thirteen columns of the file.
 *
 * <p><strong>Ids before calls.</strong> The file id is minted and written into this run's line
 * <em>before</em> the file-service write, so an attachment that exists under an id nothing recorded
 * is impossible: the same discipline the generation leg applies to a payload file id before
 * {@code generate-document}.
 *
 * <p><strong>Nobody is told about a file that is not there.</strong> A CSV that could not be
 * rendered or could not be stored ends the delivery under a bounded reason and sends nothing: an
 * e-mail whose attachment is missing is an e-mail with nothing in it, and support opening three of
 * those learns less than support opening none.
 *
 * <p><strong>One send per recipient, and one refusal is one resend.</strong> Each address gets its
 * own call under its own notification id, so a resend reaches that recipient's own attempt on
 * notificationnotify's side; a refusal is counted and the next address is still asked. The fold is
 * the sink's answer: everybody told is {@link DeliveryStatus#DELIVERED}, some told is
 * {@link DeliveryStatus#PARTIALLY_DELIVERED} with its counts, and nobody told is
 * {@link DeliveryStatus#NOT_DELIVERED} under the first bounded reason that produced it.
 *
 * <p><strong>This is the one place an address is in hand</strong>, so it is the one place masking
 * happens - one character of the local part and the domain, the shape {@code ListBatchesCli} prints
 * a recipient in. No address reaches an event, a metric label or the CSV, because no read this
 * feature makes selects one.
 *
 * <p>It holds two ports and no HTTP type, which is why its own suite is a unit one.
 */
public class EmailReportSink implements ExceptionReportSink {

    /** The attachment's name, less the day it was taken and the extension. */
    public static final String FILE_NAME_PREFIX = "court-register-exceptions_";

    /** What the file service is told the attachment is. */
    public static final String CONVERSION_FORMAT = "csv";

    /** The template name this file is written under; it renders nothing and names this report. */
    public static final String TEMPLATE_NAME = "yot-results-distribution-exception-report";

    private static final Logger LOG = LoggerFactory.getLogger(EmailReportSink.class);

    /** The thirteen columns, which are the entry's own components and no others. */
    private static final String HEADER = "kind,source,request_id,hearing_id,hearing_day,batch_id,"
            + "notification_id,court_centre_id,register_date,status,attempts,reason,age_seconds";

    private static final char SEPARATOR = ',';

    /**
     * CRLF record endings, stated once, which is the dialect RFC 4180 states.
     *
     * <p>This attachment is opened in a spreadsheet on somebody's desktop rather than parsed, so
     * the ending is the one the format specifies and not the one this JVM's platform happens to
     * use. A line break <em>inside</em> a quoted field is whatever the producing context wrote and
     * is not a record ending; only the endings written here are CRLF.
     */
    private static final String ROW_END = "\r\n";

    private static final char QUOTE = '"';

    /** What RFC 4180 says a field has to be quoted for. */
    private static final String NEEDS_QUOTING = ",\"\r\n";

    private static final String EXTENSION = ".csv";

    /** The zone the attachment's day is read in, which is the courts' and never the pod's. */
    private static final ZoneId COURTS_ZONE = ZoneId.of(GenerationProperties.COURTS_ZONE);

    /** Progression writes one page whatever the file turns out to be, and so does this. */
    private static final int ONE_PAGE = 1;

    /** What is left of an address once a line has been written about it. */
    private static final String MASK = "***";

    private static final char AT = '@';

    /** Nobody accepted and nobody refused, which is what a delivery that never started counts. */
    private static final int NONE = 0;

    /** Where the CSV goes, so that notificationnotify can attach what is under the id. */
    private final PayloadFileStore files;

    /** Who sends it, one call per recipient. */
    private final ReportMailer mailer;

    /** The notificationnotify template the report is sent under, from configuration. */
    private final UUID templateId;

    /** The addresses it goes to, resolved from configuration and never a value in this repo. */
    private final List<String> recipients;

    /**
     * Holds the two ports and the two settings that say what is sent and to whom.
     *
     * @param files      the file service, written to through the port
     * @param mailer     the report's own send
     * @param templateId the template the report is sent under
     * @param recipients the addresses it goes to, one send each
     */
    public EmailReportSink(final PayloadFileStore files, final ReportMailer mailer,
            final UUID templateId, final List<String> recipients) {
        this.files = Objects.requireNonNull(files, "the file store is required");
        this.mailer = Objects.requireNonNull(mailer, "the mailer is required");
        this.templateId = templateId;
        this.recipients = List.copyOf(recipients);
    }

    @Override
    public ReportSinkName name() {
        return ReportSinkName.EMAIL;
    }

    /**
     * Stores the CSV, then tells every configured recipient about it, and says how that went.
     *
     * @param report the report to deliver
     * @return the fold of the per-recipient outcomes, with its counts and its bounded reason
     */
    // PMD.OnlyOneReturn: the three exits are three different things that can happen to a morning's
    // e-mail - nobody to tell, nothing to attach, and the sends themselves - each answered where it
    // is decided. One exit would carry a verdict past the send loop that must not be entered.
    @SuppressWarnings("PMD.OnlyOneReturn")
    @Override
    public DeliveryOutcome deliver(final ExceptionReport report) {
        if (recipients.isEmpty()) {
            LOG.warn("The report's e-mail output is on and the resolved recipient list is empty, "
                            + "so nobody was told what went wrong overnight. run_id={} reason={}",
                    report.runId(), ReportDeliveryReason.NO_RECIPIENTS);
            return new DeliveryOutcome(ReportSinkName.EMAIL, DeliveryStatus.NOT_DELIVERED,
                    ReportDeliveryReason.NO_RECIPIENTS, NONE, NONE);
        }

        // Minted and said out loud before the write that uses it: an outcome that arrives for a
        // file nothing wrote down is an attachment this service cannot attribute to a morning.
        final UUID fileId = UUID.randomUUID();
        LOG.info("The morning report's attachment is being written under an id minted for it, and "
                        + "then sent. run_id={} file_id={} entries={} recipients={}",
                report.runId(), fileId, report.entries().size(), recipients.size());

        final ReportDeliveryReason attached = attached(fileId, report);
        if (attached != ReportDeliveryReason.NONE) {
            return new DeliveryOutcome(ReportSinkName.EMAIL, DeliveryStatus.NOT_DELIVERED,
                    attached, NONE, recipients.size());
        }
        return sent(fileId, report);
    }

    /**
     * Renders the CSV and stores it, answering the bounded reason it could not be attached.
     *
     * <p>Caught to classify and not to carry on: the port's contract is that a sink answers how it
     * went, so what a failure becomes here is a recorded {@link DeliveryOutcome} with a reason a
     * support engineer can act on, and the run's own line says the e-mail output did not take the
     * report. Nothing is absorbed and nothing is retried.
     *
     * @param fileId the id already minted and written down
     * @param report the report being delivered
     * @return {@link ReportDeliveryReason#NONE} where the CSV is stored, else why it is not
     */
    // PMD.AvoidCatchingGenericException: a rendering that could not be made arrives as whatever the
    // composition raised, and every one of them means the one thing the recipients can be told
    // about - there is no attachment - while a narrower catch would let the classes it does not
    // name leave a sink that is contracted to answer.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private ReportDeliveryReason attached(final UUID fileId, final ExceptionReport report) {
        ReportDeliveryReason attached = ReportDeliveryReason.NONE;
        try {
            final String csv = csvOf(report);
            files.storeText(fileId, csv, metadataFor(report, csv));
        } catch (PayloadStoreUnavailableException unavailable) {
            attached = notAttached(fileId, report,
                    ReportDeliveryReason.ATTACHMENT_STORE_UNAVAILABLE, unavailable);
        } catch (RuntimeException unwritable) {
            attached = notAttached(fileId, report, ReportDeliveryReason.ATTACHMENT_UNWRITABLE,
                    unwritable);
        }
        return attached;
    }

    /**
     * Says that nothing will be sent, and why, naming the cause by class alone.
     *
     * @param fileId  the id the attachment would have had
     * @param report  the report being delivered
     * @param reason  the bounded reason
     * @param cause   what refused, named by class because its message belongs to whatever raised it
     * @return the reason, so the caller answers with it
     */
    private static ReportDeliveryReason notAttached(final UUID fileId,
            final ExceptionReport report, final ReportDeliveryReason reason,
            final RuntimeException cause) {

        LOG.warn("The morning report's attachment could not be put where notificationnotify would "
                        + "read it, so nobody is being told about a file that is not there. "
                        + "run_id={} file_id={} reason={} cause={}",
                report.runId(), fileId, reason, cause.getClass().getName());
        return reason;
    }

    /**
     * Tells every recipient about the stored file, one call each, and folds what they answered.
     *
     * @param fileId the attachment's id, the same one on every mail
     * @param report the report being delivered
     * @return the fold: everybody, some, or nobody
     */
    private DeliveryOutcome sent(final UUID fileId, final ExceptionReport report) {
        final Map<String, String> personalisation = personalisationOf(report);
        int accepted = NONE;
        int refused = NONE;
        ReportDeliveryReason reason = ReportDeliveryReason.NONE;

        for (final String recipient : recipients) {
            final UUID notificationId = UUID.randomUUID();
            final MailOutcome outcome = answered(notificationId, recipient, fileId,
                    personalisation, report);
            if (outcome.status() == MailStatus.ACCEPTED) {
                accepted++;
                LOG.info("The morning report has been accepted for one recipient. run_id={} "
                                + "notification_id={} recipient={}",
                        report.runId(), notificationId, masked(recipient));
            } else {
                refused++;
                // The first refusal's reason is the delivery's reason. A later one of another kind
                // is still counted, and the line beside it says which recipient had which - one
                // reason on one outcome cannot describe three different answers, and the one that
                // stopped the morning first is the one to act on.
                reason = reason == ReportDeliveryReason.NONE ? reasonFor(outcome.status()) : reason;
                LOG.warn("The morning report was not accepted for one recipient, which is a resend "
                                + "for that recipient and not a morning that failed. run_id={} "
                                + "notification_id={} recipient={} reason={} status={}",
                        report.runId(), notificationId, masked(recipient),
                        reasonFor(outcome.status()), outcome.responseCode());
            }
        }
        return new DeliveryOutcome(ReportSinkName.EMAIL, statusOf(accepted), reason, accepted,
                refused);
    }

    /**
     * One recipient's send, with a mailer that broke answered rather than let out.
     *
     * <p>The port's contract is that it answers, so a throw reaching here is a broken mailer rather
     * than a refused send - and this is the one place that can still do the right thing with it,
     * which is to count it against this recipient and ask the next address. Let out, it reaches the
     * service's own catch, the whole delivery is classified as having failed, and the support
     * inboxes after this one in the list are never asked at all.
     *
     * <p>Caught to classify and not to carry on regardless: the answer is an {@code UNANSWERED}
     * outcome, which the fold below turns into a bounded reason and a refused count, and the line
     * names the failure by class because its message belongs to whatever raised it.
     *
     * <p>One {@code ReportMail} per recipient is the contract and not an allocation to hoist:
     * notificationnotify keys its aggregate on the notification id, so one object reused would be
     * one e-mail addressed to everybody and one resend that could only be made to all of them.
     *
     * @param notificationId  this recipient's own id, minted before the call
     * @param recipient       the address, masked wherever it is said
     * @param fileId          the attachment's id
     * @param personalisation the counts and the window
     * @param report          the report being delivered, for the correlation on the line
     * @return what that send answered, or an unanswered outcome where the mailer broke
     */
    // PMD.AvoidCatchingGenericException: a mailer that throws is a mailer that broke its contract,
    // and a broken collaborator is not a family a narrower catch can name. Nothing is swallowed:
    // the outcome is counted, the line says it happened, and the fold carries its bounded reason.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private MailOutcome answered(final UUID notificationId, final String recipient,
            final UUID fileId, final Map<String, String> personalisation,
            final ExceptionReport report) {

        MailOutcome outcome;
        try {
            outcome = mailer.send(new ReportMail(
                    notificationId, templateId, recipient, fileId, personalisation));
        } catch (RuntimeException broken) {
            LOG.warn("The report's mailer broke rather than answering for one recipient, so this "
                            + "one is a resend and the rest are still being asked. run_id={} "
                            + "notification_id={} recipient={} cause={}",
                    report.runId(), notificationId, masked(recipient),
                    broken.getClass().getName());
            outcome = new MailOutcome(MailStatus.UNANSWERED, null);
        }
        return outcome;
    }

    /**
     * How completely the report was delivered, from how many recipients took it.
     *
     * @param accepted how many took it
     * @return the bounded status
     */
    private DeliveryStatus statusOf(final int accepted) {
        final DeliveryStatus status;
        if (accepted == recipients.size()) {
            status = DeliveryStatus.DELIVERED;
        } else if (accepted == NONE) {
            status = DeliveryStatus.NOT_DELIVERED;
        } else {
            status = DeliveryStatus.PARTIALLY_DELIVERED;
        }
        return status;
    }

    /**
     * What one recipient's answer means for the delivery as a whole.
     *
     * @param status how that one send ended
     * @return the bounded reason it contributes
     */
    private static ReportDeliveryReason reasonFor(final MailStatus status) {
        return switch (status) {
            case ACCEPTED -> ReportDeliveryReason.NONE;
            case REFUSED -> ReportDeliveryReason.SEND_REFUSED;
            case FAILED -> ReportDeliveryReason.SEND_FAILED;
            case UNANSWERED -> ReportDeliveryReason.SEND_UNANSWERED;
        };
    }

    /**
     * The five counts and the window, as strings, and nothing else.
     *
     * <p>Zero-filled, because a morning with nothing wrong has to read as five noughts and a window
     * rather than as a body with fields missing (FR-012, scenario 4.4). The keys are the ones the
     * events and the CSV use, so a template, a query and a spreadsheet name the same things.
     *
     * @param report the report being delivered
     * @return the personalisation map, every value a string
     */
    private static Map<String, String> personalisationOf(final ExceptionReport report) {
        final Map<String, String> personalisation = new LinkedHashMap<>();
        final Map<ExceptionKind, Integer> counts = report.counts();
        for (final ExceptionKind kind : ExceptionKind.values()) {
            personalisation.put(kind.name().toLowerCase(Locale.ROOT),
                    String.valueOf(counts.get(kind)));
        }
        personalisation.put("window_from", report.window().from().toString());
        personalisation.put("window_to", report.window().to().toString());
        return Map.copyOf(personalisation);
    }

    /**
     * The file the framework is told about: the five keys, spelled the framework's way.
     *
     * <p>The name carries the run's own day <strong>in the courts' zone</strong>: a name taken off
     * the pod's clock would call a report written just after midnight in summer yesterday's, and
     * two files a day apart would sort into one morning's list.
     *
     * @param report the report being delivered
     * @param csv    the file itself, for the byte count that describes it
     * @return the metadata row
     */
    private static PayloadMetadata metadataFor(final ExceptionReport report, final String csv) {
        final String fileName = FILE_NAME_PREFIX
                + LocalDate.ofInstant(report.snapshotAt(), COURTS_ZONE) + EXTENSION;
        return new PayloadMetadata(fileName, CONVERSION_FORMAT, TEMPLATE_NAME, ONE_PAGE,
                csv.getBytes(StandardCharsets.UTF_8).length);
    }

    /**
     * The report as a CSV: one header row, then one row per entry in the entries' own order.
     *
     * @param report the report being delivered
     * @return the whole file
     */
    private static String csvOf(final ExceptionReport report) {
        final StringBuilder csv = new StringBuilder(HEADER).append(ROW_END);
        for (final ExceptionEntry entry : report.entries()) {
            row(csv, entry);
        }
        return csv.toString();
    }

    /**
     * One entry's thirteen fields, with an empty one wherever the kind does not carry it.
     *
     * <p>Empty rather than absent: a row with fewer fields than the header is not a CSV, and a
     * reader that lined its columns up by counting commas would read the wrong ones.
     *
     * @param csv   the file being built
     * @param entry the exception this row is about
     */
    private static void row(final StringBuilder csv, final ExceptionEntry entry) {
        final Object[] values = {
            entry.kind(), entry.source(), entry.requestId(), entry.hearingId(), entry.hearingDay(),
            entry.batchId(), entry.notificationId(), entry.courtCentreId(), entry.registerDate(),
            entry.status(), entry.attempts(), entry.reason(), entry.ageSeconds(),
        };
        for (int column = 0; column < values.length; column++) {
            if (column > 0) {
                csv.append(SEPARATOR);
            }
            field(csv, values[column]);
        }
        csv.append(ROW_END);
    }

    /**
     * One field, quoted the way RFC 4180 asks where it has to be.
     *
     * <p>Almost every value here is an identifier, a date or a bounded code and needs no quoting at
     * all. The producing context's name is the one field another system chose the text of, so the
     * rule is applied to every field rather than assumed away for twelve of them.
     *
     * @param csv     the file being built
     * @param carried the value, or {@code null} where the kind does not carry it
     */
    private static void field(final StringBuilder csv, final Object carried) {
        if (carried == null) {
            return;
        }
        final String text = carried.toString();
        if (text.chars().anyMatch(character -> NEEDS_QUOTING.indexOf(character) >= 0)) {
            csv.append(QUOTE).append(text.replace("\"", "\"\"")).append(QUOTE);
        } else {
            csv.append(text);
        }
    }

    /**
     * One recipient's address, masked to as little as tells one inbox from another.
     *
     * <p>The shape {@code ListBatchesCli} prints a recipient in: one character of the local part
     * and the domain, and not even that where the local part is a single character. An address with
     * no domain to show is masked entirely - an address that is not an address is exactly the value
     * somebody is looking for, and printing it because it parsed badly is the one case masking must
     * not fall through on.
     *
     * @param address the configured recipient
     * @return the masked address, which is never the address
     */
    private static String masked(final String address) {
        final int at = address == null ? -1 : address.lastIndexOf(AT);
        final String local = at > 0 ? address.substring(0, at) : "";
        final String kept = local.length() > 1 ? local.substring(0, 1) : "";
        return kept + MASK + (at < 0 ? "" : address.substring(at));
    }
}
