package uk.gov.hmcts.cp.yotresultsdistribution.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException;

/**
 * How a refusal taken in a filter is written, which is the shape the advice writes.
 *
 * <p>Two refusals on this surface never reach the {@code DispatcherServlet} and so never reach
 * {@code OperationsExceptionHandler}: the content type {@code cp-audit-filter-springboot} hands
 * down the chain unpublished ({@link OperationsContentTypeFilter}), and the audit filter's own
 * refusal to publish, which is raised outside the dispatcher
 * ({@link OperationsActionFilter}). Both are rendered from here so that a caller cannot tell from
 * the body which side of the dispatcher refused them, and so that the status is taken from the one
 * table in {@link OperationsProblem} rather than decided twice.
 *
 * <p>The body is written by hand rather than through a {@code ProblemDetail} and a message
 * converter, because at this depth there is no converter to reach: what a filter holds is a
 * {@link HttpServletResponse} and nothing else. The three fields are the ones every refusal on this
 * surface carries, and every one of them is bounded - a status, the status's own reason phrase and
 * a code from the closed set. Nothing the caller supplied is read, so nothing the caller supplied
 * can be echoed (Principle VII).
 */
final class OperationsRefusalWriter {

    /** Nothing is constructed: this is one function over the status map. */
    private OperationsRefusalWriter() {
    }

    /**
     * Writes a refusal that never reached the dispatcher, in the shape every other one has.
     *
     * @param response the response to write
     * @param refused  what was refused, and under which bounded code
     * @param facts    this call's audit facts, so the refusal is on the event as well as the wire,
     *                 or {@code null} where this thread is serving no operations call
     * @throws IOException where the response cannot be written, which is the caller having gone
     *         away and is nothing this service can refuse
     */
    /* default */ static void refuse(final HttpServletResponse response,
            final OperationsRefusedException refused, final OperationsAuditFacts facts)
            throws IOException {

        final int status = OperationsProblem.statusOf(refused.reason()).value();
        if (facts != null) {
            facts.refusedWith(status, refused.reason().wire());
        }
        response.reset();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":" + status + ",\"title\":\""
                + HttpStatus.valueOf(status).getReasonPhrase() + "\",\"reason\":\""
                + refused.reason().wire() + "\"}");
    }
}
