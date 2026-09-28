package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * How one report e-mail ended.
 *
 * <p>Four states rather than a boolean, because a send that was refused and a send that was never
 * answered are different investigations: the first is a template, an address or a body
 * notificationnotify would not take, and the second is a route. Only {@link #ACCEPTED} means an
 * e-mail is on its way, and only a 202 produces it.
 */
public enum MailStatus {

    /** notificationnotify answered 202, which is the only answer that is an acceptance. */
    ACCEPTED,

    /** It answered 4xx, or a 2xx that is not 202. Never retried. */
    REFUSED,

    /** It answered 5xx after the shared attempt budget. */
    FAILED,

    /** It never answered at all: connect failure, read timeout, dropped connection. */
    UNANSWERED
}
