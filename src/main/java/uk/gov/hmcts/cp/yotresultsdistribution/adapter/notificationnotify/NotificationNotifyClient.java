package uk.gov.hmcts.cp.yotresultsdistribution.adapter.notificationnotify;

import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.notificationnotify.NotificationNotifyCommand.Verdict;
import uk.gov.hmcts.cp.yotresultsdistribution.application.NotificationOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterNotifier;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotification;

/**
 * The notifier port, wired to notificationnotify's command API.
 *
 * <p>{@code POST {NN}/notificationnotify-command-api/command/api/rest/notificationnotify/
 * notifications/{notificationId}} with media type
 * {@code application/vnd.notificationnotify.email+json}, carrying the caller identity as
 * {@code CJSCPPUID} and classifying its one attempt through the shared
 * {@code adapter/http/RetryPolicy}, so this client cannot hold a different opinion about what is
 * worth asking again than the clients 001 and the renderer already built (defect fix C3).
 *
 * <p><strong>The identity is in the path and not in the body.</strong> The API-side schema
 * {@code notificationnotify.email.json} is {@code additionalProperties: false} and declares no
 * {@code notificationId}: the framework lifts it off the path and adds it before the internal
 * {@code notificationnotify.send-email-notification} command reaches its handler. A body carrying
 * one of its own would be a 400 rather than a field notificationnotify ignored.
 *
 * <p>The body is therefore four fields: {@code templateId} as the row records it,
 * {@code sendToAddress} the recipient's own address, {@code fileId} the rendered document's
 * file-service id - the register travels by reference, so a document about children is never carried
 * through this service twice - and {@code personalisation.yotsName} the recipient's name, which is
 * what the template greets.
 *
 * <p><strong>202 and nothing else is success.</strong> A 2xx that is not 202 means something other
 * than the command endpoint answered - a proxy, or a route that no longer reaches it - and calling it
 * success would settle a row ACCEPTED for an e-mail nobody was asked to send. It is non-transient,
 * and the recipient's row is failed with the status that made it one.
 *
 * <p><strong>A retry reuses the same {@code notificationId}, which is why it is in the path.</strong>
 * notificationnotify keys its {@code Notification} aggregate on that id, so a second POST under the
 * id the row was minted with reaches the attempt it is retrying; a fresh one would send a second
 * e-mail to the same Youth Offending Team (research §10).
 *
 * <p>One attempt per call, classified and handed back, exactly as the renderer's client does it: the
 * waiting and the counting belong to the object that holds the run's budget, and a client that
 * retried underneath it would spend a budget it cannot see. What a retryable answer's
 * {@code Retry-After} asked for is handed back with the classification for the same reason in
 * reverse: this client is the only participant that sees the header, and the object holding the
 * budget is the only one that can spend the wait. The form is the shared policy's - delta-seconds
 * only, bounded by {@code max-backoff} - so a fourth client does not get a fourth opinion about it
 * (defect fix C3).
 *
 * <p>Nothing that identifies a recipient is logged. The address and the recipient name are the two
 * components that never reach a line at INFO or above (constitution Principle VII), so a line here
 * carries the notification id, the batch id and a status.
 */
public class NotificationNotifyClient implements RegisterNotifier {

    /**
     * The command's path under the notificationnotify context, exactly as its RAML declares it.
     *
     * <p>The notification's own identity is the path parameter, and the whole of what makes a retry
     * idempotent on the other side. Named here as the shared builder spells it rather than spelled
     * again: two copies of a path are two things to change on the morning it moves.
     */
    public static final String COMMAND_PATH = NotificationNotifyCommand.COMMAND_PATH;

    /** The command's vendor media type; the framework routes on it, so it is not a formality. */
    public static final String EMAIL_MEDIA_TYPE = NotificationNotifyCommand.EMAIL_MEDIA_TYPE;

    /**
     * The CPP identity header. Its value is never logged - it is either a secret or a user
     * identifier, and neither belongs in a log index.
     */
    public static final String IDENTITY_HEADER = NotificationNotifyCommand.IDENTITY_HEADER;

    /** The one status the contract calls success. */
    public static final int ACCEPTED = NotificationNotifyCommand.ACCEPTED;

    private static final Logger LOG = LoggerFactory.getLogger(NotificationNotifyClient.class);

    /** The client, carrying the notificationnotify base URL and its timeouts. */
    private final RestClient restClient;

    /** The {@code CJSCPPUID} identity for a run naming no user; a secret, never logged. */
    private final String systemUserId;

    /** The shared mapper, so a body is written exactly as every other JSON in this service is. */
    private final ObjectMapper objectMapper;

    /**
     * Builds the client over an already-configured HTTP client.
     *
     * @param restClient   the client, carrying the notificationnotify base URL and its timeouts
     * @param systemUserId the {@code CJSCPPUID} identity for a run naming no user; a secret, never
     *                     logged
     * @param objectMapper the shared mapper, so the command body is written exactly as every other
     *                     JSON in this service is
     */
    public NotificationNotifyClient(final RestClient restClient, final String systemUserId,
            final ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.systemUserId = systemUserId;
        this.objectMapper = objectMapper;
    }

    @Override
    public NotificationOutcome send(final RegisterNotification notification,
            final UUID documentFileId, final CallerIdentity caller)
            throws NotificationFailedException {
        // Resolved once, exactly as the clients 001 built and the renderer's client resolve it: the
        // run's user where the message named one, and the configured identity otherwise.
        final String identity = caller.orSystem(systemUserId);
        final byte[] body = objectMapper.writeValueAsBytes(new SendEmail(
                notification.templateId(),
                notification.emailAddress(),
                documentFileId,
                new Personalisation(notification.recipientName())));
        final int status;
        try {
            // The row's own identity, and the client has none of its own to put here: the row
            // arrives persisted, so a resend is this call again with the same row and therefore
            // the same path (research section 10). The request itself is composed by the builder
            // this package's two adapters share, so the shape the report posts and the shape this
            // posts are one shape and not two that agree today.
            status = NotificationNotifyCommand.post(restClient, notification.notificationId(),
                    identity, body, (sent, answer) -> classify(answer, notification));
        } catch (ResourceAccessException unreachable) {
            // Connect failure, read timeout, connection dropped: the request may or may not have
            // reached notificationnotify, and the e-mail may already be on its way. Unknown is not
            // refused, so it is handed back for the run to ask again - which is safe because the
            // resend reuses this row's id and so reaches the same aggregate - and it carries no
            // status, because an invented one would say an attempt was answered when nothing
            // answered.
            //
            // Only the exception's type travels with the line. What the run acts on is the
            // classification; what a human acts on is what it *was*, and the class carries that
            // much. The message does not go with it: the message belongs to whatever raised it,
            // and a line carries this service's own words (Principle VII). The claim is made by
            // construction, so this cannot quietly go back - see the sweep in
            // TelemetryPrivacyTest.
            LOG.warn("The send-email-notification command reached no verdict, so whether the e-mail "
                    + "was asked for is unknown. notificationId={} batchId={} cause={}",
                    notification.notificationId(), notification.batchId(),
                    unreachable.getClass().getName());
            throw new NotificationFailedException(FailureClassification.TRANSIENT);
        }
        return new NotificationOutcome(NotificationStatus.ACCEPTED, status);
    }

    /**
     * What notificationnotify's answer to the command means.
     *
     * <p>Nothing it wrote is read. The body of a refusal is about an e-mail addressed to a Youth
     * Offending Team about children, and the row this decision settles has {@code status} and
     * {@code response_code} and nowhere to put another system's prose.
     */
    private static int classify(
            final ClientHttpResponse response, final RegisterNotification notification)
            throws IOException {
        final HttpStatusCode statusCode = response.getStatusCode();
        final int status = statusCode.value();
        final Verdict verdict = NotificationNotifyCommand.verdictOf(statusCode);
        if (verdict != Verdict.TAKEN) {
            if (statusCode.is2xxSuccessful()) {
                // The contract declares one success. A 200 or a 204 means something other than the
                // command endpoint answered - a proxy, or a route that no longer reaches it - and
                // settling the row ACCEPTED on it would record a Youth Offending Team as told about
                // a register nobody was asked to send, which no resend would ever revisit.
                LOG.error("notificationnotify answered a success this contract does not define, so "
                        + "no e-mail can be assumed. notificationId={} batchId={} status={}",
                        notification.notificationId(), notification.batchId(), status);
                throw refused(status);
            }
            if (verdict == Verdict.RETRYABLE) {
                // 408, 429 and every server error, from the one policy all this service's clients
                // hold (C3). The asking again is the run's: this client is told nothing about what
                // is left of the budget, so a loop here would spend one it cannot see.
                //
                // What the answer asked to be waited travels with it, because this client is the
                // only participant that sees the header and the run is the only one that can spend
                // it. Read on any retryable answer rather than on a 429 alone - a 503 carrying one
                // is a service saying when it expects to be back - and read by the shared policy,
                // so the form it accepts (delta-seconds, bounded by max-backoff) is stated once for
                // all four clients rather than a fourth time here.
                LOG.warn("notificationnotify could not take the e-mail command, so the run may ask "
                        + "again under the same identity. notificationId={} batchId={} status={}",
                        notification.notificationId(), notification.batchId(), status);
                throw new NotificationFailedException(FailureClassification.TRANSIENT, status,
                        RetryPolicy.retryAfter(response.getHeaders()).orElse(null));
            }
            // Any other 4xx: the command was understood and declined, and the same command under the
            // same identity will be declined again.
            LOG.error("notificationnotify refused the e-mail command, and asking again cannot "
                    + "change that. notificationId={} batchId={} status={}",
                    notification.notificationId(), notification.batchId(), status);
            throw refused(status);
        }
        return status;
    }

    /** A refusal no further attempt can change, carrying the status that made it one. */
    private static NotificationFailedException refused(final int status) {
        return new NotificationFailedException(FailureClassification.NON_TRANSIENT, status);
    }

    /**
     * The command body: the four fields this service sends, and no fifth.
     *
     * <p>The identity is not among them. The API-side schema is
     * {@code additionalProperties: false} and declares no {@code notificationId}, so a body carrying
     * one would be a 400 rather than a field notificationnotify ignored; the framework lifts it off
     * the path instead.
     *
     * @param templateId      the template's UUID, as the row records it
     * @param sendToAddress   the recipient's own address
     * @param fileId          the rendered document's file-service id, which is how the register is
     *                        attached - by reference, so a document about children never travels
     *                        through this service twice
     * @param personalisation what the template greets the recipient by
     */
    private record SendEmail(
            UUID templateId,
            String sendToAddress,
            UUID fileId,
            Personalisation personalisation) {
    }

    /**
     * The one personalisation the {@code cr_standard} template takes.
     *
     * @param yotsName the recipient's name, or {@code null} where the record carried none
     */
    private record Personalisation(String yotsName) {
    }
}
