package uk.gov.hmcts.cp.yotresultsdistribution.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.web.filter.OncePerRequestFilter;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException;

/**
 * Refuses the one content type that would reach an operations action with no audit event.
 *
 * <p>{@code cp-audit-filter-springboot} 1.0.5 begins by reading {@code getContentType()} and, where
 * it starts {@code multipart/}, calls the chain and returns - publishing neither the request event
 * nor the response one. Nothing on this surface consumes a multipart body, but nothing refused one
 * either: no mapping declares {@code consumes}, notify takes no body at all and the report's body
 * is optional with a default window, so a caller who declared one was served and audited nowhere.
 * An endpoint reachable unaudited is an endpoint that may not exist (Principle III(b)), so it is
 * refused {@code 415 UNSUPPORTED_CONTENT_TYPE} here instead.
 *
 * <p><strong>Its place in the chain is the whole point of it being its own filter.</strong> It sits
 * at {@code HIGHEST_PRECEDENCE + 40}: <em>inside</em> {@code cp-auth-rules-filter} at {@code +30}
 * and <em>outside</em> {@code cp-audit-filter-springboot} at {@code +50}. Outside the audit filter
 * because by the time that one has decided to skip there is nothing left to refuse. Inside the
 * authorisation filter because who may act is decided before what they may send: an anonymous
 * caller declaring a multipart body is answered {@code 401}, not told what this surface consumes,
 * and a caller in the wrong group is answered {@code 403}. This is the one thing it does not share
 * with {@link OperationsActionFilter}, which must be outermost because both estate filters read the
 * action name that one derives.
 *
 * <p>Only this service's own paths are refused. The actuator is not on this surface and this filter
 * refuses nothing on its behalf.
 */
public class OperationsContentTypeFilter extends OncePerRequestFilter {

    /** The content-type family the audit filter hands on unaudited, and this surface never takes. */
    private static final String MULTIPART = "multipart/";

    /** The root every path this service serves sits under, and nothing else of the pod's does. */
    private static final String OPERATIONS_ROOT = "/operations";

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
            final HttpServletResponse response, final FilterChain chain)
            throws ServletException, IOException {

        if (ours(pathOf(request)) && unauditable(request)) {
            OperationsRefusalWriter.refuse(response, new OperationsRefusedException(
                    OperationsReason.UNSUPPORTED_CONTENT_TYPE), OperationsAuditFacts.current());
        } else {
            chain.doFilter(request, response);
        }
    }

    /**
     * Whether a request would be handed down the chain with no audit event published for it.
     *
     * @param request the request as it arrived
     * @return {@code true} where the declared content type is one the audit filter skips
     */
    private static boolean unauditable(final HttpServletRequest request) {
        final String declared = request.getContentType();
        return declared != null && declared.toLowerCase(Locale.ROOT).startsWith(MULTIPART);
    }

    /**
     * Whether a path is one of this service's own.
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
