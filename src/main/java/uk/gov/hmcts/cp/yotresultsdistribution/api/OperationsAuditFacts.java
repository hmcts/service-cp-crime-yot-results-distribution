package uk.gov.hmcts.cp.yotresultsdistribution.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The bounded facts one operations call puts into its audit event, and nothing else.
 *
 * <p>{@code audit.http.include-payload-body} is <strong>false</strong>, because the library's
 * default would put every response this surface writes onto the audit topic - the batch listing's
 * masked addresses and court-centre ids, the exception report's whole entry table, every
 * {@code ProblemDetail}. What an audit event actually needs is none of that: it is the action, what
 * came of it, whether the cutover flag was overridden, the run id of a regeneration and the count a
 * supersession gave up. The generic filter can infer not one of those from a body, so they are
 * supplied here and merged into the payload by {@link OperationsAuditService} (FR-046, research
 * R10).
 *
 * <p><strong>Every field is a bounded code, a count or an identifier.</strong> Nothing a caller
 * typed reaches this, no exception message does, and no defendant detail could: the values are an
 * action name from a closed set of seven, a status and a reason from the closed set the status map
 * is built on, a boolean, a run id this service minted and an integer.
 *
 * <p><strong>Per request, on a thread-local rather than in a request-scoped bean.</strong> The
 * audit filter is a servlet filter and publishes from the request's own thread, and so does every
 * controller and the advice; a thread-local opened by the outermost filter and cleared in its
 * {@code finally} is the same mechanism {@code batch/RunCorrelation} uses for a run and MDC uses
 * for a delivery, and it needs no scoped proxy on a singleton the starter constructs. Only whoever
 * opened it clears it - the container's threads are pooled, and a set of facts left behind would be
 * inherited by whatever ran next on that thread, which is worse than none because it reads as true.
 */
public final class OperationsAuditFacts {

    /** What the event calls a call that was answered without a refusal. */
    private static final String SUCCEEDED = "SUCCEEDED";

    /** The facts of the call being served on this thread, or {@code null} between requests. */
    private static final ThreadLocal<OperationsAuditFacts> SERVING = new ThreadLocal<>();

    /** The server-derived action name, which is never the caller's own header. */
    private String derivedAction;

    /** The status a refusal was answered under, or {@code null} where none was. */
    private Integer status;

    /** The bounded reason a refusal carried, or {@code null} where none was. */
    private String reason;

    /** Whether a regeneration went ahead over a flag that would have stopped it. */
    private Boolean flagOverride;

    /** The run id a regeneration answered with. */
    private String runId;

    /** How many registers a supersession gave up. */
    private Integer supersededCount;

    /** Whether the request event has been published, which is how the response event is known. */
    private boolean requestEventPublished;

    private OperationsAuditFacts() {
    }

    /**
     * Opens a set of facts for the call about to be served on this thread.
     *
     * @return the facts, so the opener can close the same ones it opened
     */
    public static OperationsAuditFacts open() {
        final OperationsAuditFacts facts = new OperationsAuditFacts();
        SERVING.set(facts);
        return facts;
    }

    /**
     * The facts of the call being served on this thread.
     *
     * @return them, or {@code null} where this thread is serving no operations call
     */
    public static OperationsAuditFacts current() {
        return SERVING.get();
    }

    /**
     * Forgets the facts of the call this thread has finished with.
     *
     * <p>Called only by whoever opened them, in a {@code finally}: the container's threads are
     * pooled.
     */
    public static void clear() {
        SERVING.remove();
    }

    /**
     * Records the action this call is, as this service derived it from the path and the method.
     *
     * @param derived the action name, or {@code null} where this service names none
     */
    public void action(final String derived) {
        this.derivedAction = derived;
    }

    /**
     * Records the refusal this call was answered with.
     *
     * @param answeredStatus the status
     * @param boundedReason  the bounded code, from the closed set
     */
    public void refusedWith(final int answeredStatus, final String boundedReason) {
        this.status = answeredStatus;
        this.reason = boundedReason;
    }

    /**
     * Records that a regeneration went ahead, and whether it overrode the one lever.
     *
     * @param overridden whether the flag was overridden
     * @param acceptedAs the run id the caller was answered with
     */
    public void regeneration(final boolean overridden, final String acceptedAs) {
        this.flagOverride = overridden;
        this.runId = acceptedAs;
    }

    /**
     * Records how many registers a supersession gave up, dry run or not.
     *
     * @param count the number superseded, or the number that would have been
     */
    public void superseded(final int count) {
        this.supersededCount = count;
    }

    /**
     * Whether this is the first publish of the call, which is its request event.
     *
     * <p>The library's payload does not say which of the two events it is - both carry the same
     * {@code _metadata.name} - and the filter publishes exactly two, the request before the chain
     * and the response after it. So the first is the request's, and this is the only place that
     * fact can be kept.
     *
     * @return true the first time it is asked, false afterwards
     */
    public boolean firstPublish() {
        final boolean first = !requestEventPublished;
        requestEventPublished = true;
        return first;
    }

    /**
     * The action this call is.
     *
     * @return the derived action name, or {@code null} where this service names none
     */
    public String actionName() {
        return derivedAction;
    }

    /**
     * The run id a regeneration answered its caller with.
     *
     * @return the run id, or {@code null} where this call was not one
     */
    public String acceptedRunId() {
        return runId;
    }

    /**
     * The facts as the audit event carries them.
     *
     * <p>A field nothing recorded is left out rather than written as a null a reader has to
     * interpret - the same rule the exception report's entries follow.
     *
     * @return the bounded fields, in a stable order
     */
    public Map<String, Object> asPublished() {
        final Map<String, Object> published = new LinkedHashMap<>();
        if (derivedAction != null) {
            published.put("action", derivedAction);
        }
        published.put("outcome", outcome());
        if (flagOverride != null) {
            published.put("flagOverride", flagOverride);
        }
        if (runId != null) {
            published.put("runId", runId);
        }
        if (supersededCount != null) {
            published.put("superseded", supersededCount);
        }
        return published;
    }

    /**
     * What came of the call, as one bounded word or as a status and a bounded code.
     *
     * <p>A call that reached no refusal succeeded: every refusal on this surface is answered either
     * by the advice or by the action filter's own catch, and both record themselves here. So the
     * absence of a recorded refusal is a fact rather than a gap, which is why this needs no
     * interceptor reading a status code back off the response.
     *
     * @return {@code SUCCEEDED}, or the status and the bounded reason of the refusal
     */
    private String outcome() {
        return status == null ? SUCCEEDED : status + " " + reason;
    }
}
