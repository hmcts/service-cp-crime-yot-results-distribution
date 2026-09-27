package uk.gov.hmcts.cp.yotresultsdistribution.adapter.notificationnotify;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ReportMailer;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.MailOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.MailStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportMail;

/**
 * The report's own send, wired to the same notificationnotify command the register leg uses.
 *
 * <p>Beside {@link NotificationNotifyClient} and not inside it: that client's argument is a batch's
 * notification row and its body carries {@code personalisation.yotsName}, and bending it around a
 * report would put an "unless it is a report" branch on the one path in this service that e-mails
 * Youth Offending Teams about real registers (research §4).
 *
 * <p>What the two share is the <strong>shape</strong> - the URI with the notification id as its
 * path parameter, the {@code application/vnd.notificationnotify.email+json} media type, the
 * {@code CJSCPPUID} identity header and the 202-and-nothing-else rule - and they share it through
 * {@link NotificationNotifyCommand}, which both call, so "the same shape" is a fact rather than a
 * claim.
 *
 * <p>The body is four fields: the template, the one recipient this call is for, the CSV's
 * file-service id - the exception list travels by reference, exactly as the register PDF does - and
 * the personalisation the sink composed, which the vendored schema admits because its
 * {@code personalisation} object is {@code additionalProperties: true} inside a body that is
 * otherwise closed.
 *
 * <p><strong>It answers rather than throws, about everything.</strong> One recipient's refusal is a
 * resend for that recipient and not the end of the morning's report, so the sink reads the outcome,
 * counts it and carries on to the next address. That is the difference between this port and
 * {@code RegisterNotifier}, whose caller holds a retry budget and needs a classified failure - and
 * it is total: the body is composed inside the same {@code try} as the call, and anything that
 * refuses before a verdict exists is {@code UNANSWERED} rather than a throw. A throw here would
 * reach the sink's own catch and become <em>every</em> recipient's failure, with the addresses
 * after this one never asked at all.
 *
 * <p><strong>One attempt.</strong> There is no loop here and no wait: a 4xx is a refusal that
 * asking again cannot change, and a 408, a 429 or a server error is a resend the support engineer
 * reading the run's line decides on - the report is a morning's statement, and a mailer that
 * retried inside a scheduled run would spend a budget it cannot see.
 *
 * <p>No address is logged, masked or otherwise. The sink is the one place an address is in hand and
 * the one place masking happens; a line here names the notification, the file and a status code.
 */
public class NotificationNotifyReportMailer implements ReportMailer {

    private static final Logger LOG =
            LoggerFactory.getLogger(NotificationNotifyReportMailer.class);

    /** The client, carrying the notificationnotify base URL and its timeouts. */
    private final RestClient restClient;

    /** The {@code CJSCPPUID} identity a run naming no user is made under; never logged. */
    private final String systemUserId;

    /** The shared mapper, so this body is written exactly as every other JSON in this service. */
    private final ObjectMapper objectMapper;

    /**
     * Builds the mailer over an already-configured HTTP client.
     *
     * @param restClient   the client, carrying the notificationnotify base URL and its timeouts
     * @param systemUserId the identity the POST is made under; a secret, never logged
     * @param objectMapper the shared mapper
     */
    public NotificationNotifyReportMailer(final RestClient restClient, final String systemUserId,
            final ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.systemUserId = systemUserId;
        this.objectMapper = objectMapper;
    }

    // PMD.AvoidCatchingGenericException: the port's whole contract is that it answers, and the
    // things that can stop one send are not a family a narrower catch can name - the body's
    // serialisation, a client that will not build a request, a pool that has been shut down. Every
    // one of them means the one thing the sink can act on: this recipient was not told. Nothing is
    // swallowed - the outcome is returned, counted and said at WARN, and the run's line carries it.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Override
    public MailOutcome send(final ReportMail mail) {
        // Not final: the catch below assigns it too, which is what "answers rather than throws"
        // costs on this path.
        MailOutcome outcome;
        try {
            final byte[] body = objectMapper.writeValueAsBytes(new SendReport(
                    mail.templateId(), mail.sendToAddress(), mail.fileId(),
                    mail.personalisation()));
            outcome = NotificationNotifyCommand.post(restClient, mail.notificationId(),
                    systemUserId, body, (sent, answer) -> outcomeOf(answer.getStatusCode(), mail));
        } catch (RuntimeException noVerdict) {
            // Everything that can stop one send before a verdict exists, and they are one fact to
            // the caller: a connect failure or a read timeout (ResourceAccessException), a body
            // that could not be written, a client that would not build a request. The body is
            // composed INSIDE the try for exactly that reason - composed outside it, a
            // serialisation failure left this method as a throw, reached the sink's own catch, and
            // became the whole delivery failing rather than one recipient's, with the addresses
            // after it in the list never asked at all.
            outcome = unanswered(mail, noVerdict);
        }
        return outcome;
    }

    /**
     * Says that this recipient reached no verdict, and answers with one rather than throwing.
     *
     * <p>Only the <strong>class</strong> of what was caught travels with the line: the message
     * belongs to whatever raised it, and is exactly where a host, a connection string or an address
     * turns up (constitution Principle VII). Nothing is swallowed - the sink folds this into its own
     * bounded reason, counts it and asks the next address, and the run's line says the e-mail output
     * did not take the report.
     *
     * @param mail    the mail nobody answered about
     * @param refusal what stopped it, read for its class and for nothing else
     * @return the outcome: nothing was sent, and no status came back to name
     */
    private static MailOutcome unanswered(final ReportMail mail, final RuntimeException refusal) {
        LOG.warn("The report's send-email-notification command reached no verdict, so whether "
                + "support was told is unknown. notificationId={} fileId={} cause={}",
                mail.notificationId(), mail.fileId(), refusal.getClass().getName());
        return new MailOutcome(MailStatus.UNANSWERED, null);
    }

    /**
     * What notificationnotify's answer means for one report e-mail.
     *
     * <p>Nothing it wrote is read. The body of a refusal is another system's prose about an e-mail,
     * and what a support engineer acts on is the bounded status beside the notification's id.
     *
     * @param statusCode what came back
     * @param mail       the mail it came back about, for the identifiers on the line
     * @return the outcome, carrying the status line that produced it
     */
    private static MailOutcome outcomeOf(final HttpStatusCode statusCode, final ReportMail mail) {
        final int status = statusCode.value();
        return switch (NotificationNotifyCommand.verdictOf(statusCode)) {
            case TAKEN -> new MailOutcome(MailStatus.ACCEPTED, status);
            case RETRYABLE -> {
                LOG.warn("notificationnotify could not take the report's e-mail command, so this "
                        + "recipient is a resend. notificationId={} fileId={} status={}",
                        mail.notificationId(), mail.fileId(), status);
                yield new MailOutcome(MailStatus.FAILED, status);
            }
            case REFUSED -> {
                // A 4xx, or a 2xx that is not 202. Both mean no e-mail can be assumed: the second
                // means something other than the command endpoint answered, and calling it a
                // delivery would tell support a report is on its way that nobody was asked to send.
                LOG.error("notificationnotify did not accept the report's e-mail command, and "
                        + "asking again cannot change that. notificationId={} fileId={} status={}",
                        mail.notificationId(), mail.fileId(), status);
                yield new MailOutcome(MailStatus.REFUSED, status);
            }
        };
    }

    /**
     * The command body: the four fields the report sends, and no fifth.
     *
     * <p>The identity is not among them. The API-side schema is
     * {@code additionalProperties: false} and declares no {@code notificationId}, so a body carrying
     * one would be a 400 rather than a field notificationnotify ignored; the framework lifts it off
     * the path instead.
     *
     * @param templateId      the template the report is sent under, from configuration
     * @param sendToAddress   the one recipient this call is for
     * @param fileId          the CSV's file-service id, written down before the write that made it
     * @param personalisation the five counts and the window, every value a string
     */
    private record SendReport(
            UUID templateId,
            String sendToAddress,
            UUID fileId,
            Map<String, String> personalisation) {
    }
}
