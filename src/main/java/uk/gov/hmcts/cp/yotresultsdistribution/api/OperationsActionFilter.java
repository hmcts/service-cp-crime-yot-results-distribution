package uk.gov.hmcts.cp.yotresultsdistribution.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException;

/**
 * Names the action a request is, from its path and its method, and lets nobody else name it.
 *
 * <p>{@code cp-auth-rules-filter} resolves the action in a strict priority - a vendor token in
 * {@code Content-Type}, then one in {@code Accept}, then the {@code CPP-ACTION} header verbatim,
 * then the computed fallback {@code "<METHOD> <path>"}. This service's endpoints take and return
 * plain {@code application/json}, so without this filter every call would fall through to the
 * fallback, which matches no rule in {@code acl/operations-rules.drl} and is therefore refused.
 *
 * <p><strong>And the priority above the fallback is the caller's own header.</strong> A caller
 * authorised for {@code list-batches} could send
 * {@code CPP-ACTION: yot-results-distribution-operations.generate-register} and have the rules evaluated
 * against an action they were never admitted to. So the header is not read here, it is
 * <em>written</em>: the request is wrapped so the server's value is what
 * {@code getHeader("CPP-ACTION")} answers, whatever arrived on the wire (research R2).
 *
 * <p><strong>And the two priorities above that one are media types.</strong> The resolver reads
 * {@code getContentType()} and then {@code Accept} before it reads {@code CPP-ACTION} at all, so a
 * caller could name an action in either of them and be authorised against it while being served
 * the endpoint their path and method actually reach. On this service's own paths the wrapper
 * therefore answers a vendor media type as {@code application/json}, which leaves the resolver with
 * no vendor token to find and the derived name as the only action there is.
 *
 * <p>The filter sits at {@link org.springframework.core.Ordered#HIGHEST_PRECEDENCE}, ahead of the
 * authorisation filter at {@code +30} and the audit filter at {@code +50}, because both of those
 * read the action this one derives.
 *
 * <p>A request this filter names no action for - an actuator path, a method one of these paths does
 * not answer, a {@code HEAD} on a path served for {@code GET} - is passed through with the header
 * <strong>removed</strong>. It is not given an action name that could match a rule, and it does not
 * keep the caller's either: the whole point of deriving the name server-side is lost if a path this
 * service does not recognise is the way round it. What such a request is authorised as is the
 * library's own computed {@code "<METHOD> <path>"}, which matches no rule in
 * {@code acl/operations-rules.drl} and is therefore refused.
 *
 * <p><strong>The content type the audit filter would not see is refused elsewhere.</strong>
 * {@link OperationsContentTypeFilter} takes that one, at {@code +40}: outside the audit filter,
 * because by the time it has decided to skip there is nothing left to refuse, and <em>inside</em>
 * the authorisation filter, because who may act is decided before what they may send. Being
 * outermost is what this filter is for and is exactly what that guard must not be - an anonymous
 * caller answered {@code 415} is an anonymous caller who was never answered {@code 401}.
 *
 * <p><strong>It also opens and closes the call's audit facts, and catches the one refusal that
 * cannot reach the advice.</strong> Being outermost is what makes it the right place for both. The
 * facts ({@link OperationsAuditFacts}) are a thread-local that must be cleared in a
 * {@code finally} by whoever opened it, because the container's threads are pooled; and a request
 * whose audit event could not be published is refused <em>by the audit filter</em>, which runs
 * outside the {@code DispatcherServlet} and therefore outside every {@code @RestControllerAdvice}.
 * That refusal is rendered here, from the same status map every other refusal on this surface is
 * rendered from, so it carries a bounded reason rather than arriving as the container's own 500.
 */
public class OperationsActionFilter extends OncePerRequestFilter {

    /** The header the authorisation filter reads the action out of. */
    /* default */ static final String ACTION_HEADER = "CPP-ACTION";

    /** Every action this service names, prefixed with the surface it belongs to. */
    private static final String PREFIX = "yot-results-distribution-operations.";

    /** What separates a method from a path in this filter's one lookup key. */
    private static final char SPACE = ' ';

    /** The six endpoints whose path is a fixed string, keyed by method and path together. */
    private static final Map<String, String> FIXED = Map.of(
            "GET /operations/flag", PREFIX + "check-flag",
            "GET /operations/batches", PREFIX + "list-batches",
            "GET /operations/registers/recorded-while-off", PREFIX + "list-recorded-while-off",
            "POST /operations/batches/generate", PREFIX + "generate-register",
            "POST /operations/registers/supersede", PREFIX + "supersede-before",
            "POST /operations/exception-reports", PREFIX + "report-exceptions");

    /** The seventh, and the one path parameter in the whole document. */
    private static final Pattern NOTIFY = Pattern.compile("/operations/batches/[^/]+/notify");

    /** The method that endpoint answers on; anything else is not it. */
    private static final String POST = "POST";

    /** The action a notify request is. */
    private static final String NOTIFY_ACTION = PREFIX + "notify-register";

    /** The root every path this service serves sits under, and nothing else of the pod's does. */
    private static final String OPERATIONS_ROOT = "/operations";

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
            final HttpServletResponse response, final FilterChain chain)
            throws ServletException, IOException {

        final String path = pathOf(request);
        final String action = actionFor(request.getMethod(), path);
        final OperationsAuditFacts facts = OperationsAuditFacts.open();
        facts.action(action);
        try {
            chain.doFilter(new ActionRequestWrapper(request, action, ours(path)), response);
        } catch (OperationsRefusedException refused) {
            // The audit filter runs outside the DispatcherServlet, so a refusal it raises reaches
            // no advice. Rendered here from the one status map, rather than left to arrive as the
            // container's own 500 with the path the caller typed in it.
            OperationsRefusalWriter.refuse(response, refused, facts);
        } finally {
            OperationsAuditFacts.clear();
        }
    }

    /**
     * Which action a method and a path are, or {@code null} where they are none of this service's.
     *
     * @param method the request's method, as the container reports it
     * @param path   the request's path, without the context path
     * @return the action name to authorise against, or {@code null} where this service names no
     *         action for the request - which the wrapper turns into the header's absence
     */
    // PMD.OnlyOneReturn: the absence of an action is not a value to carry down the method; saying
    // so where it is decided is what keeps the notify lookup off a path that is not one.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private static String actionFor(final String method, final String path) {
        if (method == null || path == null) {
            return null;
        }
        final String fixed = FIXED.get(method.toUpperCase(Locale.ROOT) + SPACE + path);
        if (fixed != null) {
            return fixed;
        }
        if (POST.equalsIgnoreCase(method) && NOTIFY.matcher(path).matches()) {
            return NOTIFY_ACTION;
        }
        return null;
    }

    /**
     * Whether a path is one of this service's own, and so one a vendor media type is stripped on.
     *
     * <p>By path alone rather than by the derived action, so that a method one of these endpoints
     * does not answer is covered too: the resolver reads {@code Content-Type} and {@code Accept}
     * ahead of the header this filter writes, and a {@code HEAD} on a path served for {@code GET}
     * reaches a handler with no action derived for it.
     *
     * @param path the request's path, without the context path
     * @return {@code true} where the path is {@code /operations} or sits beneath it
     */
    private static boolean ours(final String path) {
        return path != null
                && (OPERATIONS_ROOT.equals(path) || path.startsWith(OPERATIONS_ROOT + '/'));
    }

    /**
     * The path this request is for, preferring what the servlet mapping resolved.
     *
     * @param request the request as it arrived
     * @return the path to match on, which is never {@code null} for a request that reached a filter
     */
    private static String pathOf(final HttpServletRequest request) {
        final String servletPath = request.getServletPath();
        return servletPath == null || servletPath.isEmpty()
                ? request.getRequestURI()
                : servletPath;
    }
}
