package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.util.Map;
import java.util.UUID;

/**
 * One report e-mail, as the sink asks for it and the adapter posts it.
 *
 * <p>The exception list travels as the CSV attachment, referenced by {@code fileId} - the way the
 * register PDF does - because Notify's body has a length limit a bad morning's list would exceed,
 * renders no table, and truncating a list of exceptions is the silence this service exists to end.
 *
 * <p><strong>{@code personalisation} is a map because the vendored schema permits one.</strong> In
 * notificationnotify's {@code send-email-notification} body the {@code personalisation} property is
 * an object with {@code additionalProperties: true}, inside a body that is otherwise closed, so the
 * counts and the window travel there as strings and the template renders them. Every value in it is
 * a count, a window boundary or a bounded code: no identifier and no address is ever a key or a
 * value.
 *
 * @param notificationId the identity the POST is made under, which is its path parameter and not a
 *                       body field, and which a retry reuses so it reaches the same aggregate
 * @param templateId     the notificationnotify template the report is sent under
 * @param sendToAddress  the one recipient this e-mail is for; one call per recipient, never a batch
 * @param fileId         the file-service id of the CSV, written down before this is asked for
 * @param personalisation the values the template substitutes, every one of them a string
 */
public record ReportMail(
        UUID notificationId,
        UUID templateId,
        String sendToAddress,
        UUID fileId,
        Map<String, String> personalisation) {

    /** Freezes the map, and treats an absent one as none rather than as a null to guard against. */
    public ReportMail {
        personalisation = personalisation == null ? Map.of() : Map.copyOf(personalisation);
    }
}
