package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * The bounded reasons a register batch ends FAILED.
 *
 * <p>One of these - never systemdocgenerator's own words and never an exception message - is what
 * reaches {@code register_batch.failure_reason}, the batches counter's outcome label and the run
 * report. The renderer's {@code reason} is kept separately in {@code sdg_reason}, where it is
 * available to support and is never logged at INFO, because it is another system's text about a
 * document whose every defendant is a child (constitution Principle VII).
 *
 * <p>The six are six different investigations. Two of them say no payload was ever stored to
 * render from or that the batch could not be assembled at all, two say the renderer refused to
 * start, one says it started and said it had failed, and one says this service stopped waiting for
 * it - and only the first pair and the last leave the batch's rows RECORDED for the next run to
 * re-assemble.
 *
 * <p><strong>What none of them says is whether the render request left this service.</strong>
 * {@link #RENDER_REQUEST_FAILED} is the ending of a request that was made and answered nothing
 * inside the run's budget <em>and</em> of a batch the run reached with too little budget left to
 * start a single attempt, which asked systemdocgenerator nothing at all. So that fact is carried by
 * {@code BatchOutcome.renderRequested}, set where the call is made, and the run report's
 * {@code requested} count reads it from there - and from the announcement the requesting leg makes
 * at the call itself, because an outcome is only returned once the batch's ending has been written
 * down and the store can go away on that write. A predicate over these constants answered it for a
 * while and overcounted exactly the second case, reporting a renderer that had refused a document
 * it was never sent.
 */
public enum BatchFailureReason {

    /** The file service could not be written to, so no payload exists to render. */
    PAYLOAD_STORE_UNAVAILABLE,

    /** The render request could not be delivered within the run deadline. */
    RENDER_REQUEST_FAILED,

    /** The render request was answered with something other than the contract's 202. */
    RENDER_REQUEST_REJECTED,

    /** systemdocgenerator said the generation failed, on its {@code generation-failed} event. */
    GENERATION_FAILED,

    /** The batch could not be assembled into a payload at all (defect fix P5). */
    ASSEMBLY_FAILED,

    /**
     * The next scheduled run began and the batch was still waiting for its render.
     *
     * <p>This service's own verdict, and the only one of the six that is about the passage of
     * time rather than about something that happened: the batch had been in flight longer than
     * {@code yotresultsdistribution.generation.stale-after}, so the run stopped waiting for it, failed it
     * and gave its registers back to be re-assembled tonight.
     *
     * <p>It names no completion mechanism, because none was involved - no event arrived and nothing
     * was asked. It says nothing about whether systemdocgenerator ever received the request either,
     * and deliberately so: that is not a question this service can ask, and the ending is the same
     * either way. A register that is late is recoverable; a register stranded in a batch nothing
     * will finish is not, and this reason is how the second is turned into the first.
     */
    NOT_COMPLETED_BY_NEXT_RUN;

    /**
     * Whether this ending was reported by a completion mechanism outside this service.
     *
     * <p>{@link #GENERATION_FAILED} is the one that is: systemdocgenerator's own verdict about the
     * render, carried on the {@code generation-failed} event. It arrived because the renderer said
     * so, so the row names the mechanism that brought it - which is what
     * {@code register_batch.completed_by} holds.
     *
     * <p>There were two while the grace-period reconciler existed, the second being its verdict
     * about the renderer's silence. Nothing goes and asks any more, so no ending can arrive that
     * way, and a batch nothing is learned about is failed {@link #NOT_COMPLETED_BY_NEXT_RUN} by the
     * next scheduled run instead - this service deciding for itself, and therefore attributed to
     * nobody.
     *
     * <p>The other five are this service's own verdict about a render it could not ask for, could
     * not hear about, or stopped waiting for, and naming a mechanism on one of them would credit a
     * decision nobody outside this service made.
     *
     * <p>Stated here once, and asked here by everything that enforces it: {@code JdbcRegisterStore}
     * refuses a mark whose attribution disagrees with its reason, and
     * {@code register_batch_completed_by_shape_chk} names the same reason for the writers that do
     * not go through the store.
     *
     * @return true where the ending carries a {@link CompletedBy}, and false where it must not
     */
    public boolean isGeneratorAttributed() {
        return this == GENERATION_FAILED;
    }
}
