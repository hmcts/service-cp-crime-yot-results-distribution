package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Thrown when one recipient's {@code send-email-notification} did not succeed.
 *
 * <p>Per recipient and never per batch. A batch whose first recipient is refused still has the rest
 * to tell, and an exception that ended the batch would turn one bad address into a night's silence
 * for a whole court centre; the notifier catches this one per row and settles the batch NOTIFIED,
 * PARTIALLY_NOTIFIED or NOTIFIED_NOBODY on the tally.
 *
 * <p>The two facts a failed row records are its status and the status line, so those are the two
 * this carries. There is no bounded reason code beyond them because there is nowhere to write one:
 * {@code register_notification} has {@code status} and {@code response_code} and nothing else that
 * could hold another system's opinion, and the address in the row is already the thing support
 * needs.
 *
 * <p><strong>It carries one thing the row does not: what the answer asked to be waited.</strong> A
 * {@code Retry-After} is notificationnotify saying when it expects to be able to take the command,
 * and the whole point of the header is that the server knows that better than a client's schedule.
 * It is read on any retryable answer, in delta-seconds only and bounded by {@code max-backoff}, by
 * the shared {@code adapter/http/RetryPolicy} - the same reading the other three clients get from
 * the same object (defect fix C3) - and it travels here because the object that spends the wait is
 * the one holding the attempt budget, not the client that saw the header. Empty is the ordinary
 * case and means the back-off schedule, which is also what an unusable value falls back to.
 */
public class NotificationFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FailureClassification failureClassification;
    private final Integer status;
    private final Duration asked;

    /**
     * Creates a failure with no status line to record.
     *
     * @param classification whether a resend could change the answer
     */
    public NotificationFailedException(final FailureClassification classification) {
        this(classification, null);
    }

    /**
     * Creates a failure carrying the status notificationnotify answered with.
     *
     * @param classification whether a resend could change the answer
     * @param responseCode   the status notificationnotify answered with, or {@code null} where
     *                       nothing answered at all
     */
    public NotificationFailedException(
            final FailureClassification classification, final Integer responseCode) {
        this(classification, responseCode, null);
    }

    /**
     * Creates a failure carrying the status and the wait the answer asked for.
     *
     * @param classification whether a resend could change the answer
     * @param responseCode   the status notificationnotify answered with, or {@code null} where
     *                       nothing answered at all
     * @param retryAfter     the wait the answer asked for, already read as the shared policy reads
     *                       one, or {@code null} where it asked for nothing this service can act on
     */
    public NotificationFailedException(final FailureClassification classification,
            final Integer responseCode, final Duration retryAfter) {
        super(classification.name());
        this.failureClassification = classification;
        this.status = responseCode;
        this.asked = retryAfter;
    }

    /**
     * Whether a resend could change the answer.
     *
     * @return the classification the throw site gave
     */
    public FailureClassification classification() {
        return failureClassification;
    }

    /**
     * The status notificationnotify answered with, where it answered.
     *
     * @return the status line, or empty where the attempt reached no verdict
     */
    public OptionalInt responseCode() {
        return status == null ? OptionalInt.empty() : OptionalInt.of(status);
    }

    /**
     * The wait the answer asked for, where it asked for one this service can act on.
     *
     * @return the {@code Retry-After} as the shared policy reads one, or empty - which is the
     *     back-off schedule, the same outcome as no header at all
     */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(asked);
    }
}
