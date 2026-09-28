package uk.gov.hmcts.cp.yotresultsdistribution.application;

/**
 * What a call to the notifying leg actually did, as against what the batch's rows add up to.
 *
 * <p>Two mechanisms can reach one generated batch at the same moment - the outcome sink on a
 * delivered {@code document-available}, and an operator's resend - and only one of them may post.
 * The one that does not needs a way to say so that a caller can act on and a metric can carry, and
 * a tally cannot say it: the counts a loser reads are the winner's work in progress, and a batch
 * state is where the batch stands rather than what this call decided.
 *
 * <p>Bounded, and deliberately four constants rather than a message. A caller branches on it, a
 * {@code reason} label is derived from it, and neither may ever carry a court centre, a batch
 * identity or a recipient (constitution Principle VII).
 */
public enum NotificationDisposition {

    /**
     * This call held the batch's notification claim, posted for whoever was owed an e-mail, and
     * settled the batch on the tally.
     */
    SETTLED("settled"),

    /**
     * Another notifier held the claim, so this call posted nothing and settled nothing.
     *
     * <p>Not a failure and not a refusal: the batch is being told by somebody else, and the caller
     * that meets this has nothing left to do. The tally that travels with it is the batch as it
     * stood when the claim was refused, which is the winner's work part-done.
     */
    ALREADY_NOTIFYING("already-notifying"),

    /**
     * This call held the claim, began the cycle, and lost the claim part way through it.
     *
     * <p>The third answer, and a different event from {@link #ALREADY_NOTIFYING}: that one is a
     * notifier that never got the claim, and this is one that held it, began the cycle and then
     * found the batch was no longer its own. <strong>How far it had got is not fixed.</strong> The
     * renewal that is refused may be the one asked before the first POST, which ends the cycle
     * having posted for nobody, or the one asked before the batch's own settlement, by which point
     * every recipient has been posted for. So zero or more POSTs were really made, and whatever the
     * rows and the batch do carry was written by two notifiers rather than one.
     *
     * <p>The claim is renewed before every POST and before every settlement, so what this says is
     * that a renewal was refused: the lease ran out under this notifier - notificationnotify is
     * answering one recipient more slowly than the lease covers - and something else has taken the
     * batch over. The run stops there rather than writing over the work of the notifier that now
     * holds it, and the rows it did not settle are re-requested by a later run under the identities
     * they already hold.
     *
     * <p>The one write it does still make is the attempt tally, deliberately unfenced: the POSTs it
     * had already made are added to their row's lifetime {@code attempts} by a statement that
     * touches no settlement column, because a POST made in the window the fence exists for was
     * still really made. Where that write finds no row to add to, the fault is reported exactly as
     * a settlement's absent row is - and this is still the answer the call gives. What it lost was
     * the claim; {@link #INCOMPLETE} is reserved for the notifier that never lost one.
     */
    CLAIM_LOST("claim-lost"),

    /**
     * This call held the claim throughout and could not finish the cycle, so the batch is not
     * settled.
     *
     * <p>The fourth answer, and the one that is about this service's own store rather than about
     * two notifiers. A settlement made for a row this run read back or minted, and that the store
     * then does not hold, means the cycle cannot account for one of the batch's recipients: the
     * attempt is recorded nowhere, so the tally the batch would be settled from is a tally over
     * rows that no longer describe what was sent.
     *
     * <p><strong>Reserved for the settlement write, and so for a notifier that still owned the
     * batch.</strong> The same absent row met by the tally-only write a lost claim leaves behind is
     * the same fault and is reported the same way, but that call answers {@link #CLAIM_LOST}: it no
     * longer holds the batch, so what became of the batch is not its to say.
     *
     * <p>Settling anyway was the worse answer, and quietly. A batch whose only recipient row had
     * gone tallied to nought rows and reached NOTIFIED_NOBODY - the terminal state that says the
     * document was rendered and there was nobody to send it to, defect fix P1's own words, written
     * over a batch addressed to a Youth Offending Team all along and terminal, so no later resend
     * could revisit it. So the cycle stops, the claim is given back, and the batch stays where it
     * stands with whatever rows are settled.
     *
     * <p><strong>GENERATED is recoverable, and nothing recovers it unasked.</strong>
     * {@code yotresultsdistribution_oldest_generated_age} names such a batch, which is the reading that says
     * a batch has been standing there - and a reading settles nothing, because there is a document
     * and nothing to fail; the run's stale-batch pass does not touch a GENERATED batch at any age,
     * for the same reason. What recovers the batch is an operator's explicit {@code notify-register --batch}
     * resend, and nothing else: the outcome sink drives one notify call per transition into
     * GENERATED and suppresses the callback for a batch already there. The resend derives the owed
     * set from the records again, mints the row the store has no record of, posts under it and
     * settles the batch on a tally that then accounts for every recipient.
     *
     * <p>The bounded reason it is counted under is {@code settlement-row-absent}, which is the
     * fault itself; this is what the call did about it.
     */
    INCOMPLETE("incomplete");

    private final String storedCode;

    NotificationDisposition(final String code) {
        this.storedCode = code;
    }

    /**
     * The code this answer is reported and counted under.
     *
     * <p>Fixed here rather than derived from the constant name, so renaming a constant cannot
     * silently rename what a runbook greps for or what a dashboard's series is called - the same
     * reason {@code GateDecision.Reason} carries its own codes. Bounded and about this call rather
     * than about the batch, so it never names a court centre, a batch or a recipient (constitution
     * Principle VII).
     *
     * @return the bounded code
     */
    public String code() {
        return storedCode;
    }
}
