package uk.gov.hmcts.cp.yotresultsdistribution.adapter.notificationnotify;

import java.util.UUID;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy;

/**
 * The one request this service makes of notificationnotify, composed in one place.
 *
 * <p>Two adapters post to this command - the register leg's
 * {@link NotificationNotifyClient} and the exception report's
 * {@link NotificationNotifyReportMailer} - and what they have in common is the <em>shape</em>: the
 * URI with the notification id as its path parameter, the vendor media type the framework routes
 * on, the {@code CJSCPPUID} identity, and what an answer means. What they do not have in common is
 * the body or what they answer their own caller, which is why this composes the request and hands
 * the response back rather than returning a result of its own.
 *
 * <p>Written once because "the same shape" has to be a fact rather than a claim. Two copies of a
 * media type, a path and a 202 rule are two things to change on the morning notificationnotify
 * changes one of them, and the copy that did not get changed is the one that goes on sending.
 *
 * <p>Package-private on purpose: this is how the adapters in this package talk to that context, and
 * nothing outside it has any business composing the request.
 */
/* default */ final class NotificationNotifyCommand {

    /**
     * The command's path under the notificationnotify context, exactly as its RAML declares it.
     *
     * <p>The notification's own identity is the path parameter, and the whole of what makes a retry
     * idempotent on the other side: notificationnotify keys its {@code Notification} aggregate on
     * it, so a second POST under the id the caller minted reaches the attempt it is retrying.
     */
    /* default */ static final String COMMAND_PATH =
            "/notificationnotify-command-api/command/api/rest/notificationnotify/notifications/"
                    + "{notificationId}";

    /** The command's vendor media type; the framework routes on it, so it is not a formality. */
    /* default */ static final String EMAIL_MEDIA_TYPE =
            "application/vnd.notificationnotify.email+json";

    /**
     * The CPP identity header. Its value is never logged - it is either a secret or a user
     * identifier, and neither belongs in a log index.
     */
    /* default */ static final String IDENTITY_HEADER = "CJSCPPUID";

    /** The one status the contract calls success. */
    /* default */ static final int ACCEPTED = 202;

    private NotificationNotifyCommand() {
        // The path, the media type, the identity header and the taxonomy, and nothing else.
    }

    /**
     * Posts one command and hands the answer to the caller, unread.
     *
     * <p>Nothing is decided here. The response goes to whoever asked, because the two callers
     * settle differently - one throws a classified failure at a run holding a retry budget, the
     * other answers an outcome to a sink that carries on to the next recipient - and a shared
     * helper that decided for them would have to know which of the two it was serving.
     *
     * @param restClient     the client, carrying the notificationnotify base URL and its timeouts
     * @param notificationId the identity this command is made under, which is its path parameter
     * @param identity       the {@code CJSCPPUID} value; a secret or a user, never logged
     * @param body           the command body, as the caller serialised it
     * @param answered       what the caller makes of the response
     * @param <T>            what the caller answers
     * @return whatever the caller's handler answered
     */
    /* default */ static <T> T post(final RestClient restClient, final UUID notificationId,
            final String identity, final byte[] body,
            final RestClient.RequestHeadersSpec.ExchangeFunction<T> answered) {

        return restClient.post()
                .uri(COMMAND_PATH, notificationId)
                .headers(headers -> {
                    headers.setContentType(MediaType.parseMediaType(EMAIL_MEDIA_TYPE));
                    headers.set(IDENTITY_HEADER, identity);
                })
                .body(body)
                .exchange(answered);
    }

    /**
     * What notificationnotify's answer means, in the three words both callers act on.
     *
     * <p><strong>202 and nothing else is success.</strong> A 2xx that is not 202 means something
     * other than the command endpoint answered - a proxy, or a route that no longer reaches it -
     * and treating it as an acceptance would record somebody as told about an e-mail nobody was
     * asked to send, which is the silent-success failure this service was commissioned to end.
     *
     * <p>What is worth asking again is the shared {@code adapter/http/RetryPolicy}'s opinion and
     * not this class's, so a fourth client does not get a fourth answer to the same question
     * (defect fix C3).
     *
     * @param statusCode what came back
     * @return whether it was taken, refused, or worth asking again
     */
    /* default */ static Verdict verdictOf(final HttpStatusCode statusCode) {
        final Verdict verdict;
        if (statusCode.value() == ACCEPTED) {
            verdict = Verdict.TAKEN;
        } else if (!statusCode.is2xxSuccessful() && RetryPolicy.retryable(statusCode.value())) {
            verdict = Verdict.RETRYABLE;
        } else {
            verdict = Verdict.REFUSED;
        }
        return verdict;
    }

    /** The three things an answer can mean, which is what both callers switch on. */
    /* default */ enum Verdict {

        /** 202: the command was taken and an e-mail is on its way. */
        TAKEN,

        /** A 4xx, or a 2xx that is not 202. Asking again under the same identity cannot change it. */
        REFUSED,

        /** 408, 429 or a server error: the same command may be worth making again. */
        RETRYABLE
    }
}
