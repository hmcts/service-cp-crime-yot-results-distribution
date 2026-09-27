package uk.gov.hmcts.cp.yotresultsdistribution.application;

import uk.gov.hmcts.cp.yotresultsdistribution.domain.MailOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportMail;

/**
 * Who sends the report's own e-mail.
 *
 * <p>A <strong>second</strong> port rather than a widened {@link RegisterNotifier}, and that is a
 * decision rather than an oversight: that port's argument is a batch's notification row and its
 * body carries {@code personalisation.yotsName}. A report has neither, and bending the register's
 * port around one would put an "unless it is a report" branch on the only path that e-mails Youth
 * Offending Teams about real registers.
 *
 * <p>The HTTP shape the two adapters post is shared through a package-private request builder, so
 * "the same shape" is a fact rather than a claim.
 */
public interface ReportMailer {

    /**
     * Sends one report e-mail, answering how it went rather than throwing.
     *
     * <p>Answered rather than thrown for the reason the sink's own {@code deliver} is: one
     * recipient's refusal is a resend for that recipient and not the end of the morning's report,
     * so the sink reads the outcome, counts it and carries on to the next address.
     *
     * @param mail the e-mail, carrying the identity the POST is made under
     * @return how the send ended, and what notificationnotify answered where anything did
     */
    MailOutcome send(ReportMail mail);
}
