package uk.gov.hmcts.cp.yotresultsdistribution.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;

/**
 * The body of every answer this service does not write itself.
 *
 * <p>{@code cp-auth-rules-filter} refuses through {@code HttpServletResponse.sendError}, which
 * forwards to {@code /error} and never reaches a {@code @RestControllerAdvice} (research R5). So
 * the 401 and the 403 - the two refusals an operations caller is most likely to meet - are written
 * by the container's own error handling, and so is the answer to a path nothing is mapped to.
 *
 * <p>Boot's default body carries {@code timestamp}, {@code status}, {@code error}, {@code message}
 * and <strong>{@code path}</strong>, and the path is a string the caller typed: on an unmapped path
 * it is whatever they tried, echoed back (FR-025, FR-027). {@code message} and {@code trace} are
 * worse - they are an exception's own words, and an exception's words are exactly where a
 * connection string or a fragment of a statement turns up.
 *
 * <p>This class replaces all of it with three fields and no more: the status, a title from the
 * framework's own closed set of reason phrases, and a <strong>bounded</strong> {@code reason} code
 * from the closed set below. Nothing is copied from the request, from an exception or from a
 * header, and the options the caller of {@code getErrorAttributes} asks for are deliberately
 * ignored - a body that grows when somebody switches {@code server.error.include-message} on would
 * be one configuration key away from the thing this class exists to prevent.
 */
public class OperationsErrorAttributes extends DefaultErrorAttributes {

    /** The status the answer carries, as an integer the operator's tooling reads. */
    /* default */ static final String STATUS = "status";

    /** The status's own phrase, from the framework's closed set and never from a message. */
    /* default */ static final String TITLE = "title";

    /** The bounded code a runbook greps for. */
    /* default */ static final String REASON = "reason";

    /** Nobody was named on the request, so nobody could be authorised. */
    private static final String NOT_IDENTIFIED = "not-identified";

    /** Somebody was named and is not admitted to this action. */
    private static final String NOT_PERMITTED = "not-permitted";

    /** Nothing is served at what was asked for. */
    private static final String NO_SUCH_PATH = "no-such-path";

    /** Something is served there, and not on that method. */
    private static final String METHOD_NOT_ALLOWED = "method-not-allowed";

    /** A refusal of the request that none of the four above describes. */
    private static final String REQUEST_REFUSED = "request-refused";

    /** A failure this service could not classify, which is the only thing a 5xx here may be. */
    private static final String UNEXPECTED_FAILURE = "unexpected-failure";

    /** The phrase used where the status is not one the framework knows a phrase for. */
    private static final String UNTITLED = "Error";

    /** The four statuses whose refusal has a name of its own. */
    private static final Map<Integer, String> NAMED = Map.of(
            HttpStatus.UNAUTHORIZED.value(), NOT_IDENTIFIED,
            HttpStatus.FORBIDDEN.value(), NOT_PERMITTED,
            HttpStatus.NOT_FOUND.value(), NO_SUCH_PATH,
            HttpStatus.METHOD_NOT_ALLOWED.value(), METHOD_NOT_ALLOWED);

    /** Where the container records the status it is rendering an error page for. */
    private static final String STATUS_ATTRIBUTE = "jakarta.servlet.error.status_code";

    /** The status assumed where the container recorded none, which is the honest one. */
    private static final int UNRECORDED = HttpStatus.INTERNAL_SERVER_ERROR.value();

    /** The lowest status this service will call a refusal of the request rather than a failure. */
    private static final int LOWEST_REFUSAL = 400;

    /** The lowest status that is this service's own failure. */
    private static final int LOWEST_FAILURE = 500;

    @Override
    public Map<String, Object> getErrorAttributes(final WebRequest request,
            final ErrorAttributeOptions options) {
        final int status = statusOf(request);
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put(STATUS, status);
        body.put(TITLE, titleOf(status));
        body.put(REASON, reasonOf(status));
        return body;
    }

    /**
     * The status the container is rendering, or a 500 where it recorded none.
     *
     * @param request the error request, as the container forwarded it
     * @return the status to answer under
     */
    private static int statusOf(final WebRequest request) {
        final Object recorded = request.getAttribute(STATUS_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return recorded instanceof Integer status ? status : UNRECORDED;
    }

    /**
     * The status's phrase, taken from the framework's closed set.
     *
     * @param status the status being answered
     * @return the phrase, or a fixed word where the status is not one the framework names
     */
    private static String titleOf(final int status) {
        final HttpStatus known = HttpStatus.resolve(status);
        return known == null ? UNTITLED : known.getReasonPhrase();
    }

    /**
     * The bounded code the answer is grepped by.
     *
     * @param status the status being answered
     * @return one of the six codes this class knows, and never anything else
     */
    // PMD.OnlyOneReturn: the named statuses are decided by a lookup and the two families by their
    // own comparison; carrying a code down the method would only put the lookup and the families
    // in the wrong order to read.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private static String reasonOf(final int status) {
        final String named = NAMED.get(status);
        if (named != null) {
            return named;
        }
        if (status >= LOWEST_REFUSAL && status < LOWEST_FAILURE) {
            return REQUEST_REFUSED;
        }
        return UNEXPECTED_FAILURE;
    }
}
