package uk.gov.hmcts.cp.yotresultsdistribution.api;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException;

/**
 * The one place an operations refusal becomes a status, for every endpoint at once.
 *
 * <p>The CLI had three exit codes. HTTP has eight statuses worth using, and the whole value of
 * that refinement is lost the moment two endpoints answer the same refusal differently - so no
 * controller decides a status. Each one raises, or lets through, an
 * {@link OperationsRefusedException} naming a bounded {@link OperationsReason}, and
 * {@link OperationsProblem}'s closed table says which status that is.
 *
 * <p><strong>Permitted here and nowhere else.</strong> {@code @ControllerAdvice} is scoped to the
 * {@code api} package by declaration, because the message listeners and the scheduled jobs convert
 * an exception into a settlement or a persisted state rather than into a response: an advice
 * reachable from them would be a swallowed exception with a status code on it
 * ({@code technical-rules.md}).
 *
 * <p><strong>Nothing the caller sent comes back.</strong> {@code detail} is never populated, from
 * an exception message or from anything else; a refusal about an argument names the argument using
 * this service's own word for it and never the value; and a body that will not parse is refused
 * without quoting the field that broke it, because that field's name is producer-chosen text
 * (FR-024, constitution Principle VII).
 *
 * <p><strong>And there is one fallback, over {@code RuntimeException}.</strong> It does not guess a
 * code: it answers the one bounded code that means nobody classified this,
 * {@link OperationsReason#UNEXPECTED}, so that the failure is written down here rather than
 * somewhere nothing writes it down at all. The reason it has to be written down here is the audit
 * filter: {@code AuditFilter.doFilterInternal} has no {@code try}/{@code finally} around the chain,
 * so an exception that leaves the dispatcher never reaches {@code performResponseAudit} - the
 * trail is left with a request event, no response event and no counter, and FR-046 says the event
 * carries the outcome. Answering through {@link OperationsProblem} puts the response on the wire
 * through the library's own caching wrapper and the outcome onto the event.
 *
 * <p>What it still does <strong>not</strong> catch is as deliberate. The fallback is over
 * {@code RuntimeException} and not over {@code Exception}, and it is scoped to this package's
 * handlers: an unmapped path and an unmapped method are checked {@code ServletException}s the
 * dispatcher raises before any handler of this package is chosen, and they keep the framework's
 * own answer through {@link OperationsErrorAttributes}, in the same bounded shape and without a
 * stack trace.
 */
@RestControllerAdvice(basePackages = "uk.gov.hmcts.cp.yotresultsdistribution.api")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OperationsExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(OperationsExceptionHandler.class);

    /**
     * A refusal an application service classified, answered from the one status map.
     *
     * @param refused what was refused, and under which bounded code
     * @return the {@code ProblemDetail}
     */
    @ExceptionHandler(OperationsRefusedException.class)
    public ResponseEntity<Object> refused(final OperationsRefusedException refused) {
        return OperationsProblem.answering(refused);
    }

    /**
     * A body that will not parse, or one that carries a field this service does not take.
     *
     * <p>Both are {@code 400 unreadable-argument} and neither names a field. The request contract
     * is closed (FR-028): an unknown field is a contract violation rather than something to
     * tolerate, because tolerating it would hide a caller's mistake until it mattered. The field's
     * name is not quoted back, for the same reason the inbound queue message never quotes one -
     * it is text somebody else chose.
     *
     * @param notReadable what Jackson or the converter refused, named by class alone
     * @return the {@code 400}
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> unreadableBody(
            final HttpMessageNotReadableException notReadable) {

        LOG.warn("An operations request carried a body this service will not read. cause={}",
                notReadable.getClass().getName());
        return OperationsProblem.answering(HttpStatus.BAD_REQUEST,
                OperationsReason.UNREADABLE_ARGUMENT, null, Map.of());
    }

    /**
     * A failure nobody classified, which is the only thing a {@code 500} of this service's may be.
     *
     * <p>Not a swallowed exception: it is classified here - as the one code that says it was not -
     * logged at ERROR naming the class alone, and answered. Nothing of the failure's own words
     * reaches the log or the body; the message belongs to whatever library raised it and is
     * exactly where a connection string turns up.
     *
     * @param defect what nothing else on this surface claimed, named by class alone
     * @return the {@code 500}, carrying the bounded code and no detail
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Object> unexpected(final RuntimeException defect) {
        LOG.error("An operations call ended in a failure this service did not classify, which is "
                        + "a defect rather than a refusal. reason={} cause={}",
                OperationsReason.UNEXPECTED.wire(), defect.getClass().getName());
        return OperationsProblem.answering(HttpStatus.INTERNAL_SERVER_ERROR,
                OperationsReason.UNEXPECTED, null, Map.of());
    }

    /**
     * A query or path parameter that will not read as the type it has to be.
     *
     * @param mismatch which parameter, by the name this service gave it
     * @return the {@code 400}
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> unreadableArgument(
            final MethodArgumentTypeMismatchException mismatch) {

        return OperationsProblem.answering(HttpStatus.BAD_REQUEST,
                OperationsReason.UNREADABLE_ARGUMENT, mismatch.getName(), Map.of());
    }

    /**
     * A required parameter that was not given, which this service never defaults.
     *
     * @param missing which parameter, by the name this service gave it
     * @return the {@code 400}
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Object> missingArgument(
            final MissingServletRequestParameterException missing) {

        return OperationsProblem.answering(HttpStatus.BAD_REQUEST,
                OperationsReason.MISSING_ARGUMENT, missing.getParameterName(), Map.of());
    }
}
