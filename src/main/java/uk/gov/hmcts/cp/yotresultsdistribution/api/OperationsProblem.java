package uk.gov.hmcts.cp.yotresultsdistribution.api;

import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException;

/**
 * The one status map, and the one place a {@code ProblemDetail} is built from a bounded code.
 *
 * <p>Every refusal any operations endpoint can answer is a {@link OperationsReason}, and this is
 * the table that says which status each of them is. It is a table rather than a decision taken
 * again at every throw site because the CLI's three exit codes became eight statuses, and eight
 * statuses decided in seven controllers is seven chances for the same refusal to be a {@code 409}
 * here and a {@code 500} there. {@code OperationsExceptionHandler} is what applies it to
 * everything; this is what it applies.
 *
 * <p><strong>{@code 500} means a defect and nothing else.</strong> A {@code 500} this service can
 * explain is a {@code 409}, a {@code 503} or a {@code 502} it failed to classify (FR-023), so the
 * three codes that are {@code 500} here are the three that mean the call tried and could not
 * finish - not the ones that mean it refused.
 *
 * <p><strong>The map is closed.</strong> Every constant of {@link OperationsReason} appears in it,
 * and {@code OperationsExceptionHandlerTest} asserts that: a reason added without a status would
 * otherwise reach a caller as an unexplained {@code 500}, which is the silence this whole service
 * exists to end.
 *
 * <p><strong>And it is where a refusal reaches the audit event.</strong> The event carries the
 * outcome of the call (FR-046), and a refusal is an outcome; recording it here rather than at each
 * throw site is what makes "on the wire" and "in the event" the same statement.
 *
 * <p>Nothing a caller supplied reaches what this builds. {@code detail} is never populated - not
 * from an exception message, not from a store's words, not from a far end's - and {@code instance}
 * is set empty rather than left null, because Spring fills a null one with the request URI, which
 * on the notify path is the batch id the caller typed.
 */
final class OperationsProblem {

    /** An instance that names nowhere, which is how the field is kept out of the body. */
    private static final URI NOWHERE = URI.create("");

    /** The extension every refusal carries, and the only one a runbook greps for. */
    private static final String REASON = "reason";

    /** The extension a refusal about one argument carries: its name, never its value. */
    private static final String ARGUMENT = "argument";

    /** Which status each bounded code is, stated once for the whole surface. */
    private static final Map<OperationsReason, HttpStatus> BY_REASON = Map.ofEntries(
            Map.entry(OperationsReason.MISSING_ARGUMENT, HttpStatus.BAD_REQUEST),
            Map.entry(OperationsReason.UNREADABLE_ARGUMENT, HttpStatus.BAD_REQUEST),
            Map.entry(OperationsReason.OVERRIDE_REQUIRES_BATCH, HttpStatus.BAD_REQUEST),
            Map.entry(OperationsReason.SUPERSEDE_INSTANT_IN_FUTURE, HttpStatus.BAD_REQUEST),
            Map.entry(OperationsReason.SUPERSEDE_INSTANT_TOO_OLD, HttpStatus.BAD_REQUEST),
            Map.entry(OperationsReason.UNKNOWN_BATCH, HttpStatus.NOT_FOUND),
            Map.entry(OperationsReason.FLAG_OFF, HttpStatus.CONFLICT),
            Map.entry(OperationsReason.FLAG_UNREADABLE, HttpStatus.CONFLICT),
            Map.entry(OperationsReason.FLAG_ON, HttpStatus.CONFLICT),
            Map.entry(OperationsReason.OVERRIDDEN, HttpStatus.CONFLICT),
            Map.entry(OperationsReason.SETTLED, HttpStatus.CONFLICT),
            Map.entry(OperationsReason.ALREADY_NOTIFYING, HttpStatus.CONFLICT),
            Map.entry(OperationsReason.KEY_IN_FLIGHT, HttpStatus.CONFLICT),
            Map.entry(OperationsReason.OUTSIDE_THE_BOUND, HttpStatus.CONFLICT),
            Map.entry(OperationsReason.SCHEDULE_RUNNING, HttpStatus.CONFLICT),
            Map.entry(OperationsReason.EMAIL_OUTPUT_DISABLED, HttpStatus.CONFLICT),
            Map.entry(OperationsReason.EMAIL_OUTPUT_NOT_WIRED, HttpStatus.CONFLICT),
            Map.entry(OperationsReason.CLAIM_LOST, HttpStatus.INTERNAL_SERVER_ERROR),
            Map.entry(OperationsReason.INCOMPLETE, HttpStatus.INTERNAL_SERVER_ERROR),
            Map.entry(OperationsReason.REPORT_NOT_BUILT, HttpStatus.INTERNAL_SERVER_ERROR),
            Map.entry(OperationsReason.REPORT_NOT_DELIVERED, HttpStatus.INTERNAL_SERVER_ERROR),
            Map.entry(OperationsReason.GENERATION_FAILED, HttpStatus.INTERNAL_SERVER_ERROR),
            Map.entry(OperationsReason.RESEND_FAILED, HttpStatus.INTERNAL_SERVER_ERROR),
            Map.entry(OperationsReason.UNEXPECTED, HttpStatus.INTERNAL_SERVER_ERROR),
            Map.entry(OperationsReason.UNSUPPORTED_CONTENT_TYPE,
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE),
            Map.entry(OperationsReason.COMMAND_NOT_WIRED, HttpStatus.NOT_IMPLEMENTED),
            Map.entry(OperationsReason.DOWNSTREAM_REFUSED, HttpStatus.BAD_GATEWAY),
            Map.entry(OperationsReason.LISTING_FAILED, HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry(OperationsReason.SUPERSESSION_FAILED, HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry(OperationsReason.STORE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry(OperationsReason.AUDIT_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry(OperationsReason.DOWNSTREAM_UNAVAILABLE, HttpStatus.GATEWAY_TIMEOUT));

    /** Nothing is constructed: this is a table and two functions over it. */
    private OperationsProblem() {
    }

    /**
     * Which status one bounded code is answered with.
     *
     * @param reason the bounded code
     * @return the status, and {@code 500} for a code no row names - which is a defect in this
     *         table, and is what {@code OperationsExceptionHandlerTest} asserts cannot happen
     */
    /* default */ static HttpStatus statusOf(final OperationsReason reason) {
        return BY_REASON.getOrDefault(reason, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * The whole table, so a suite can assert it names every bounded code exactly once.
     *
     * @return the map from bounded code to status
     */
    /* default */ static Map<OperationsReason, HttpStatus> statuses() {
        return BY_REASON;
    }

    /**
     * The response one refusal is answered with.
     *
     * @param refused what the application service refused under
     * @return the {@code ProblemDetail}, carrying bounded codes, counts and identifiers only
     */
    /* default */ static ResponseEntity<Object> answering(
            final OperationsRefusedException refused) {
        return answering(statusOf(refused.reason()), refused.reason(), refused.argument(),
                refused.properties());
    }

    /**
     * The response one bounded code is answered with, where there is no exception to read it from.
     *
     * @param status    the status to answer
     * @param reason    the bounded code
     * @param argument  this service's own name for the offending argument, or {@code null}
     * @param carried   bounded extras, which may be empty
     * @return the {@code ProblemDetail}
     */
    /* default */ static ResponseEntity<Object> answering(final HttpStatus status,
            final OperationsReason reason, final String argument,
            final Map<String, Object> carried) {

        // The one place every refusal body is built is the one place the audit event learns what
        // the call came to: an outcome recorded at four throw sites would be four chances for a
        // refusal to be on the wire and not in the event.
        final OperationsAuditFacts facts = OperationsAuditFacts.current();
        if (facts != null) {
            facts.refusedWith(status.value(), reason.wire());
        }
        final ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(NOWHERE);
        problem.setProperty(REASON, reason.wire());
        if (argument != null) {
            problem.setProperty(ARGUMENT, argument);
        }
        carried.forEach(problem::setProperty);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
