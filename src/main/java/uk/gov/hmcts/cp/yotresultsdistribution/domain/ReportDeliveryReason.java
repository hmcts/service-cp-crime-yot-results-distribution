package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * The bounded reasons a delivery went the way it did.
 *
 * <p>The only thing a failed delivery contributes to a line or to a label. Never a raw exception
 * message, never a status line quoted back, never a fragment of a downstream's body: constitution
 * Principle VII bars free text this service did not write from telemetry, and a reason a support
 * engineer pastes into a ticket has to be a code with a fixed meaning.
 *
 * <p>{@link #NONE} is here because the reason of a success is not absent, it is {@code NONE}: an
 * absent field on half the outcomes is a field every query has to special-case.
 */
public enum ReportDeliveryReason {

    /** Delivered. The reason of a success is not absent, it is this. */
    NONE,

    /** The CSV could not be rendered at all. */
    ATTACHMENT_UNWRITABLE,

    /** The file-service write did not durably store the CSV, for any reason. */
    ATTACHMENT_STORE_UNAVAILABLE,

    /** notificationnotify answered 4xx, or a 2xx that is not 202. Never retried. */
    SEND_REFUSED,

    /** notificationnotify answered 5xx after the shared attempt budget. */
    SEND_FAILED,

    /** The send was never answered: connect failure, read timeout, dropped connection. */
    SEND_UNANSWERED,

    /**
     * The e-mail output is on and the resolved recipient list is empty at run time.
     *
     * <p>Startup refuses that configuration, so it can only mean the list was emptied under a
     * running pod.
     */
    NO_RECIPIENTS,

    /** The log sink's own write threw, which is a broken appender rather than a broken report. */
    LOG_WRITE_FAILED
}
