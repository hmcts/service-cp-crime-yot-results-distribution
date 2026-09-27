package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The states one register batch moves through, from assembly to a terminal outcome.
 *
 * <p>The constant names are the values written to {@code register_batch.status} and enumerated by
 * the V2 check constraint, so a rename here is a schema change rather than a refactor.
 *
 * <p>Four of the seven are terminal, and they are terminal for four different reasons: everybody who
 * subscribes was told, somebody was not, there was nobody to tell, or the document never came. An
 * undifferentiated "finished" would collapse the three good endings into the bad one, which is the
 * shape the progression leg ends a batch in and the shape defect P1 is about.
 *
 * <p><strong>The refusals are the point.</strong> {@link #canTransitionTo(BatchStatus)} permits the
 * nine moves the data-model diagram draws and refuses the other forty pairs the enumeration
 * admits, including every move out of a terminal state and every move of a state to itself. A
 * machine that permitted an undrawn move would record the same "somewhere in the middle" the
 * progression leg left behind, one column further on; {@code BatchStateTest} guards the list.
 */
public enum BatchStatus {

    /** Assembled and stamped onto its rows; nothing has been asked of the renderer yet. */
    PENDING,

    /** The payload is in the file service and the render request was accepted with a 202. */
    GENERATING,

    /** The document exists, learned from the public event, which is the one way it is learned. */
    GENERATED,

    /** Every recipient of the batch was accepted by notificationnotify. */
    NOTIFIED,

    /** The document went to some recipients and not to others; the rest are resendable. */
    PARTIALLY_NOTIFIED,

    /** The document was generated and the batch had no recipients at all (defect fix P1). */
    NOTIFIED_NOBODY,

    /** The batch ended without a document, under one bounded {@link BatchFailureReason}. */
    FAILED;

    /** What a state with nowhere left to go permits, and what a state nobody drew permits. */
    private static final Set<BatchStatus> NO_MOVES =
            Collections.unmodifiableSet(EnumSet.noneOf(BatchStatus.class));

    /** The nine arrows of the data-model diagram, read once and never rebuilt. */
    private static final Map<BatchStatus, Set<BatchStatus>> PERMITTED_MOVES = permittedMoves();

    /**
     * The state machine of data-model.md, transcribed arrow for arrow.
     *
     * <p>{@code FAILED} keeps nothing: the diagram's {@code FAILED -> PENDING} is the re-assembly
     * CLI minting a <em>new</em> {@code batch_id}, so it writes a second row that starts at PENDING
     * rather than reviving this one. Reviving it would leave systemdocgenerator's verdict about the
     * old identity attached to a batch being rendered again.
     *
     * <p>{@code PENDING -> GENERATED} is the one arrow that is not this service moving its own
     * batch along. It is an outcome arriving for a batch whose {@code markRequested} never landed:
     * a render systemdocgenerator accepted, and a pod that died between the 202 and the mark,
     * leaves a batch that says nobody asked and a document that exists. The event carries the batch
     * identity, so the sink reaches it anyway. Refusing the move would mean throwing the document
     * away rather than sending it.
     *
     * @return the permitted next states of every drawn state, unmodifiable
     */
    private static Map<BatchStatus, Set<BatchStatus>> permittedMoves() {
        final Map<BatchStatus, Set<BatchStatus>> moves = new EnumMap<>(BatchStatus.class);
        moves.put(PENDING, unmodifiable(EnumSet.of(GENERATING, GENERATED, FAILED)));
        moves.put(GENERATING, unmodifiable(EnumSet.of(GENERATED, FAILED)));
        moves.put(GENERATED, unmodifiable(EnumSet.of(NOTIFIED, PARTIALLY_NOTIFIED, NOTIFIED_NOBODY)));
        moves.put(PARTIALLY_NOTIFIED, unmodifiable(EnumSet.of(NOTIFIED)));
        moves.put(NOTIFIED, NO_MOVES);
        moves.put(NOTIFIED_NOBODY, NO_MOVES);
        moves.put(FAILED, NO_MOVES);
        return Collections.unmodifiableMap(moves);
    }

    private static Set<BatchStatus> unmodifiable(final Set<BatchStatus> states) {
        return Collections.unmodifiableSet(states);
    }

    /**
     * Whether this batch may move to the given state.
     *
     * <p>Asked before every write rather than after, so a transition the state machine does not
     * permit is refused where it is attempted and never inferred from a row that already changed. A
     * state is never a move to itself: a redelivered {@code document-available} is settled by
     * recognising the batch is already where the event would put it, not by re-stamping it.
     *
     * <p>A state added to this enumeration and not drawn into {@link #permittedMoves()} permits
     * nothing, so the diagram is what widens the machine rather than the enumeration.
     *
     * @param next the state a transition would move the batch to, never null
     * @return whether the data-model state machine permits the move
     * @throws NullPointerException where no next state is named, which is a caller that lost the
     *         state it meant to write rather than a move the machine forbids
     */
    public boolean canTransitionTo(final BatchStatus next) {
        Objects.requireNonNull(next, "a transition names the next state it moves to");
        return PERMITTED_MOVES.getOrDefault(this, NO_MOVES).contains(next);
    }
}
