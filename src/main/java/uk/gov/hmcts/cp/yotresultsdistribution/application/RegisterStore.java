package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletedBy;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtCentreDay;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GuardDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedRegisterSummary;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterRecord;

/**
 * Where an assembled register is kept, and how a batch's progress is written against it.
 *
 * <p>The port that replaces {@link RegisterSubmissionClient} as the pipeline's last step. The
 * register is no longer somebody else's to hold: it is recorded here, in this service's own store,
 * and progression's {@code add-court-register} is not called at all unless
 * {@code yotresultsdistribution.output=progression-post} is set for the documented fallback sequencing.
 *
 * <p>Nothing here names SQL, a transaction manager or a row. The adapter behind it owns all three,
 * and it is the adapter that makes {@link #recordAndComplete} atomic - the insert, the supersession
 * of the hearing's earlier active row and the completion of the command are one transaction, so no
 * reader can see two active registers for one hearing and one day (research §8) and no delivery can
 * stop between the register and the completion it belongs to.
 *
 * <p><strong>Every {@code mark} is scoped to one batch.</strong> That is defect fix P3 stated as a
 * signature: progression flips rows by court centre, so a document generated for Monday marks
 * Tuesday's rows generated too and Tuesday's register is never sent. A batch identity is the
 * argument here precisely so the widening cannot be reintroduced without changing the port.
 */
public interface RegisterStore {

    /**
     * Records one hearing's register and completes the command that produced it, in one transaction.
     *
     * <p>One transaction, and the whole of it: the new row is inserted RECORDED, any earlier
     * RECORDED, unsuperseded, unbatched row for the same hearing within the same
     * {@link CourtCentreDay} is marked SUPERSEDED and pointed at the new one, and the completion of
     * the command is written beside them. A row that already carries a {@code batch_id} is never
     * touched - it is on its way to a PDF, and rewriting it would take a register out of a batch the
     * renderer has already been asked about.
     *
     * <p><strong>The completion is passed in rather than written here.</strong> What a completion
     * means, and what it is worth when the claim behind it has been reclaimed, belongs to
     * {@link IdempotencyGuard} and to nothing in this package; what belongs here is the commit
     * boundary the two writes share. So the caller hands over the completion as the thing to do
     * inside the transaction, the adapter runs it there, and the guard's own contract is untouched -
     * every other outcome a run can have is still written by the guard alone, outside any store.
     *
     * <p><strong>They stand or fall together.</strong> A completion that throws takes the recording
     * back with it, and so does a completion the guard did not admit: a register recorded under a
     * claim somebody else holds is a register the redelivery records again and this one only
     * supersedes. There is therefore no window in which a register is recorded against a request the
     * broker will deliver again.
     *
     * <p><strong>Idempotent on the command even so.</strong> A delivery can still arrive for a
     * command this store has recorded - the transaction committed and the broker never learned the
     * message was settled - and it is answered with the row that was already written, the row it
     * superseded included, with nothing written a second time. The guard settles most of those
     * before they reach here; this is what makes the ones that do reach here harmless.
     *
     * <p>The court centre's OU code is an argument because it is the one fact the batch needs that
     * the document does not carry: the transformation resolves it from reference data (001 carries
     * it on {@code ProcessedOutputClaim} for the same reason), {@code assemble} copies it from the
     * batch's first row, and the render payload and the file name are built from it. Nothing
     * downstream can re-derive it from a row that did not record it.
     *
     * @param document           the validated register document, stored exactly as it will be
     *                           rendered
     * @param command            the request this register was produced for
     * @param courtCentreOuCode  the court centre's OU code as the transformation resolved it, or
     *                           {@code null} where reference data named none
     * @param defendantType      {@code Applicant} / {@code Appellant} / {@code Respondent}, or
     *                           {@code null} where the hearing carried no court application
     * @param flagState          the cutover flag as last read, which decides whether the row is
     *                           batched automatically at all (research §12)
     * @param completion         the completion of this command, run inside the recording's own
     *                           transaction; a decision other than
     *                           {@link GuardDecision.Complete} takes the recording back with it
     * @return the row that was written, the row it superseded if any, and what the completion
     *         answered
     */
    RecordedCompletion recordAndComplete(DistributionCommand command,
            CourtRegisterDocument document, String courtCentreOuCode, String defendantType,
            RecordedFlagState flagState, Supplier<GuardDecision> completion);

    /**
     * The registers waiting to be batched.
     *
     * <p>RECORDED, unsuperseded, unbatched and recorded while the flag was ON. The last of those four
     * is the one that is easy to forget and expensive to get wrong: a register recorded while the
     * legacy was generating may already have been sent by the legacy, and batching it would send a
     * second copy to the same Youth Offending Team.
     *
     * @return every register eligible for automatic batching, oldest first
     */
    List<RegisterRecord> activeUnbatched();

    /**
     * The registers still recorded, active and unbatched that were recorded before a given moment.
     *
     * <p>The report's fourth BATCH_LATE source: a register the most recent scheduled generation run
     * left where it was. Shares {@link #activeUnbatched()}'s predicate, written once, and adds the
     * cut-off and an age computed in the same statement that selects the row - which is why it is a
     * projection rather than a filter over the entity read, an age derived in the JVM from a stored
     * timestamp being the cross-clock comparison V1 forbids.
     *
     * <p>A register recorded while the flag was not ON is absent here exactly as it is absent
     * there, because the predicate is the same one: those rows are the existing
     * {@code list-batches --recorded-while-off} command's concern and are not exceptions.
     *
     * @param recordedBefore the cut-off, which the caller chose - the previous occurrence of the
     *                       generation schedule
     * @return every register waiting since before it, oldest first
     */
    List<RecordedRegisterSummary> recordedUnbatchedBefore(Instant recordedBefore);

    /**
     * The registers one batch was assembled from, oldest first.
     *
     * <p>Read back by identity rather than carried through from the assembly, because the batch
     * identity is the only thing that says which rows a document is built from once the run has
     * stamped them - the same predicate {@link #markGenerated} moves rows under, asked in the other
     * direction. A caller that kept the grouping in memory and rendered from that would be
     * rendering from what it read before the stamp, and the stamp is the moment the batch became a
     * fact.
     *
     * <p>The order is the assembly order, because the first record names the file and the render
     * payload is progression's array of documents in the order the batch holds them.
     *
     * @param batchId the batch whose registers are wanted
     * @return the registers stamped with this batch, in the order they were assembled
     */
    List<RegisterRecord> batched(UUID batchId);

    /**
     * The batches already recorded for these keys, whatever state they reached.
     *
     * <p>What the assembler is given so that the supplementary rule can be decided at all (design
     * Q27). A key whose earlier batch is still PENDING, GENERATING or GENERATED has its registers
     * left waiting - the schema admits one in-flight batch per key - and a key whose batches are all
     * terminal may be followed by a supplementary one, which names the batch it follows and carries
     * the next index. Neither decision can be made from the registers alone, and a run that read
     * nothing here would answer "no earlier batch" for every key and assemble every late re-share as
     * if it were a day's first document.
     *
     * <p>Asked for the keys in play rather than for everything, because that is what the decision
     * needs: another court centre's finished document, or the same court centre's other day, says
     * nothing about whether this day may be rendered again.
     *
     * @param keys the court centre and register days a run holds active registers for
     * @return every batch recorded for those keys, in no particular order; empty where none of them
     *         has ever been batched
     */
    List<RegisterBatch> batchesFor(Collection<CourtCentreDay> keys);

    /**
     * The batches recorded for one register day, whatever state each of them reached.
     *
     * <p>The read a person's regeneration starts from, and the one thing
     * {@link #batchesFor(Collection)} cannot answer: a support call is about a day rather than about
     * a set of keys, and the day's FAILED batches are precisely the ones whose registers still carry
     * a stamp and are therefore outside {@link #activeUnbatched()}. A command that could only read
     * the keys the active registers fall under would silently leave out the court centre whose whole
     * night failed, which is the call that is actually made at 08:00.
     *
     * <p>Every state, because what to do with each is the caller's decision and they differ: a
     * FAILED batch may be re-assembled, a notified one is what a supplementary index is counted
     * over, and one still in flight is why a key is left alone (design Q27). A read that filtered to
     * FAILED here would leave the second and third of those unanswerable from the same page, and the
     * day would be re-rendered against a history it could not see.
     *
     * <p><strong>Court centre, then supplementary index, then identity.</strong> The order is part
     * of the contract rather than an implementation's convenience, because a caller that releases
     * the day's FAILED batches takes them in the order this answers them: a key's earlier batches
     * come before its supplements, which is the order a release has to make - the supplement holds
     * the register the base batch's row was replaced by, and asking for the supplement first would
     * ask {@link #releaseFailed} to supersede the newer register against the older one, which it
     * refuses. The identity breaks the tie the index would otherwise leave to the planner, so two
     * runs of the same command read the day the same way.
     *
     * @param registerDate the London register day being asked about
     * @return every batch recorded for that day, by court centre, then supplementary index, then
     *         identity; empty where the day holds none
     */
    List<RegisterBatch> batchesOn(LocalDate registerDate);

    /**
     * The batches these identities name, as the rows stand at the moment of asking.
     *
     * <p>The read behind the run report's settled counts, and it is asked by identity because that
     * is the only thing that says <em>tonight's</em> batches. {@link #batchesFor(Collection)} reads
     * a key's whole history and {@link #batchesOn(LocalDate)} reads a day's, so both answer with
     * batches earlier runs assembled; a run that counted what came back from either would credit
     * itself with last night's documents.
     *
     * <p><strong>One statement for the whole set.</strong> The caller holds every identity it
     * assembled and asks once, because a read per batch would make a run's own reporting scale with
     * the size of the estate - the requesting leg is already sequential and one statement per court
     * centre at the end of it is a second pass over the night.
     *
     * <p>Whatever state each has reached, and only the ones that exist: a batch whose stamp was
     * refused has no row and a batch the run deadline never reached was never written down, so an
     * identity nothing answers for is a batch that is not there rather than an error. The order is
     * nobody's business here - the caller counts them - so none is stated.
     *
     * @param batchIds the batches being asked about, which for a run is every identity it assembled
     * @return the batches those identities name, in no particular order; empty where none of them
     *         has a row
     */
    List<RegisterBatch> batchesNamed(Collection<UUID> batchIds);

    /**
     * Writes the batch the assembler decided on and stamps its identity onto the rows.
     *
     * <p><strong>The batch is an argument, not something this port invents.</strong> Which identity
     * a day's document is correlated on, what the file is called, which batch it follows and at what
     * supplementary index are the assembler's decisions - it is the only thing that has seen the
     * key's history - and a store that minted its own identity and named its own file would be
     * deciding all four again, differently, at the moment the rows are stamped. What the adapter
     * still owns is the moment ({@code assembled_at}) and the OU code, which is a column of the
     * register's own row and is copied from it.
     *
     * <p>All of it or none of it. A register that was superseded or batched elsewhere between the
     * read and the stamp means this is not the batch that was asked for, and the batch is refused
     * <em>and</em> undone: a refused batch that left its row behind would hold that court centre and
     * day against every later run, and the day would never be rendered at all.
     *
     * @param batch   the batch as the assembler decided it: identity, file name, trigger source and
     *                the supplementary link
     * @param records the registers that belong to it, in the order the batch holds them
     * @return the batch as the row now stands, carrying the stamps the database made
     */
    RegisterBatch assemble(RegisterBatch batch, List<RegisterRecord> records);

    /**
     * Records the file-service id this batch's payload is about to be written under.
     *
     * <p>Before the write, and that is the whole of why this is a separate mark. An id minted, used
     * for an insert and only then written down is an id that exists in the file service and nowhere
     * in this service if the pod dies in between - a payload nothing points at, and, if the render
     * request got out first, a document that comes back attributable to nothing. The batch stays
     * PENDING: the id says which payload the render will be about, not that one was asked for.
     *
     * @param batchId       the batch the payload belongs to
     * @param payloadFileId the file-service id the caller minted for it
     */
    void markPayloadMinted(UUID batchId, UUID payloadFileId);

    /**
     * Records that systemdocgenerator accepted the render request for this batch.
     *
     * <p>The payload id is named again rather than assumed from {@link #markPayloadMinted}, because
     * this is the statement that moves the batch and a move that read a column it also depends on
     * would be two reads of one fact. The two must be the same id; a run that passed a different one
     * would be saying the render it just had accepted was about a payload it never stored.
     *
     * @param batchId       the batch that was requested
     * @param payloadFileId the file-service id the payload was stored under
     */
    void markRequested(UUID batchId, UUID payloadFileId);

    /**
     * Records the document this batch generated, and moves this batch's rows to GENERATED.
     *
     * <p><strong>This batch's rows and no others (defect fix P3).</strong>
     *
     * <p>Which mechanism learned the outcome is an argument rather than a later write. A batch state
     * change is a compare-and-set through {@code BatchStatus}, so there is no moment either side of
     * the transition in which {@code completed_by} could be set on its own: before the mark the
     * batch is still GENERATING and the write would have to guess the outcome, and after it the only
     * move left is GENERATED to GENERATED, which the machine refuses. It travels with the mark and
     * is written by the mark's own statement.
     *
     * @param batchId        the batch the document belongs to
     * @param documentFileId the rendered document's file-service id
     * @param generatedAt    when systemdocgenerator generated it
     * @param completedBy    the mechanism that learned the document exists, which is the event
     *                       listener
     */
    void markGenerated(UUID batchId, UUID documentFileId, Instant generatedAt,
            CompletedBy completedBy);

    /**
     * Fails the batch under a bounded reason, leaving its rows where the reason says they belong.
     *
     * <p>{@code completedBy} is nullable here and only here: most of the reasons are this service's
     * own verdict about a render it could not ask for, could not get an answer about, or stopped
     * waiting for, and naming a completion mechanism for those would credit a decision nobody
     * outside this service made. The reasons that are somebody's answer - the ones
     * {@link uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason#isGeneratorAttributed()} names
     * - carry the mechanism that brought it, which for the one this service still writes, a
     * {@code generation-failed} event, is EVENT. The rule is asked of the enum rather than restated
     * here, so this port cannot drift from what the store enforces.
     *
     * <p>Where the reason releases the rows, a row the hearing has since been re-shared for is
     * superseded against the re-share as its stamp is cleared, exactly as {@link #releaseFailed}
     * does it: a recording supersedes an incumbent that is unbatched, so a re-share that arrived
     * while the row was stamped left the key holding two rows, and unstamping the older one is what
     * would make both active. Only a register that came <em>after</em> the released one can
     * supersede it, under the order {@link #releaseFailed} states, and for the reason it gives -
     * sharpened here by the release being a branch of the statement that marks the batch, so a
     * refusal takes the mark with it.
     *
     * <p><strong>And only a register, which the store holds fewer of than it holds rows.</strong>
     * The same table carries increment 001's record of what was POSTed to progression, on the same
     * key and with a moment of its own beside it, and one of those is not a register that could
     * replace anything. Which rows may be a replacement is stated as the states a live register is
     * in - recorded, generated or notified - rather than as the states it is not, for the reason
     * {@link #releaseFailed} gives.
     *
     * @param batchId     the batch that failed
     * @param reason      the bounded reason it is failed under
     * @param sdgReason   systemdocgenerator's own words, for support only, or {@code null} where it
     *                    said nothing
     * @param completedBy the mechanism that learned the render failed, or {@code null} where this
     *                    service failed the batch on its own account
     */
    void markFailed(UUID batchId, BatchFailureReason reason, String sdgReason,
            CompletedBy completedBy);

    /**
     * Gives one FAILED batch's registers back, so that a person may have the day rendered again.
     *
     * <p>The other half of {@link #markFailed}. Three of the six reasons leave no document to
     * wait for, and those release the stamp as they fail - their registers are active and unbatched
     * by the time any later run reads them. The other three say systemdocgenerator was asked, so a
     * document may yet exist and the rows keep their stamp: re-rendering that day is a decision a
     * person makes (data-model.md), and this is the statement that decision is written as.
     *
     * <p><strong>From FAILED only, and the refusal matters more than the release.</strong> A stamp
     * cleared off a GENERATING batch's rows would let the next run assemble a second batch for a day
     * systemdocgenerator is still rendering, and both would reach the same Youth Offending Team. The
     * batch row itself is left FAILED, carrying what happened to it, because the register store is
     * the audit of what this service decided and a re-run is exactly when that audit is read.
     *
     * <p>The released registers are answered rather than left to be read back, so that a caller
     * cannot assemble a row this call did not release: between a release and a second read a
     * re-share can supersede a row, and a caller re-assembling what it read a moment earlier would
     * be stamping a register the store no longer calls active.
     *
     * <p><strong>A register the hearing has since been re-shared for is superseded rather than given
     * back.</strong> A recording only supersedes an incumbent that is active and <em>unbatched</em>,
     * so a re-share that arrives while the first register is stamped into this batch leaves the key
     * holding two rows rather than one - and giving the older of them its stamp back is what would
     * make both active. Where the key holds another unsuperseded row, this call therefore writes the
     * stale one SUPERSEDED against it as it clears the stamp, and leaves it out of the answer: it is
     * not a register this day is owed a document from, and re-assembling it would send a Youth
     * Offending Team the register the estate has already replaced. A re-share that arrives
     * <em>after</em> the release supersedes the released row in the ordinary way, this call having
     * left it active and unbatched.
     *
     * <p><strong>The row it is superseded against has to be a later one.</strong> The key can hold
     * the pair either way round - a first batch that kept its stamp, or one that reached NOTIFIED,
     * leaves its register beside the re-share rather than superseded by it - so the batch a person
     * releases may be the one holding the newer row. Superseding that against the register it
     * replaced would withdraw the current register for good: it would be neither active nor
     * unbatched, no later run and no command would reach it again, and the row left renderable
     * would be the one the re-share corrected, with this call answering as though the day held
     * nothing to release.
     *
     * <p><strong>Later means the order the store persists, which is
     * {@code (registerTime, the moment the row was taken, identity)}.</strong> The register instant
     * is the results' own shared moment and the estate sets it, so one hearing can be shared twice
     * at one instant; the recorder settles such a pair by arrival - it supersedes an incumbent whose
     * instant is not <em>after</em> the arriving register's - so the register that arrived second is
     * the current one however the two identities sort. This call reads the same rule as a ranking
     * and must therefore rank by arrival too: a tie broken on the identity alone agrees with the
     * recorder in one of the two orders and, in the other, does not see the successor at all,
     * unstamps the older row into a second active register for the key and loses the whole write to
     * the index. The identity is the last tie-break and nothing more, for two rows the store's clock
     * could not separate.
     *
     * <p><strong>And it has to be a register, which is narrower than a row of the key.</strong> The
     * store's table is also increment 001's record of what was POSTed to progression: one such row
     * exists for every register that leg sent, it carries the same hearing, court centre and
     * register day, and it carries a moment of its own that can outrank a register's. It is
     * evidence about a POST and not a register that could replace one. Which rows may be a
     * replacement is therefore stated as the states a live register is in - recorded, generated or
     * notified - and not as the states it is not: a rule written the second way admits whatever the
     * next release adds to the column, and here that was the whole of 001's submission log. Where a
     * POST row is taken for the successor the register is withdrawn against it, this call answers
     * with nothing, and the operator is told the day released nothing while the register it is owed
     * a document from is reachable by no later run - the loss this ordering rule exists to prevent,
     * arrived at from a row that was never a register.
     *
     * @param batchId the FAILED batch whose registers are to be released
     * @return the registers whose stamp was cleared and that are still this day's to render, in the
     *         order the batch held them; empty where the batch's own failure had already released
     *         them, or where every register it held has since been replaced
     */
    List<RegisterRecord> releaseFailed(UUID batchId);

    /**
     * Fails every batch still awaiting its render past its cutoff, and gives its registers back.
     *
     * <p>What the nightly run does first, after the flag and before it assembles anything. A batch
     * still PENDING or GENERATING when the next run begins, and in that state for long enough, is
     * failed under {@link BatchFailureReason#NOT_COMPLETED_BY_NEXT_RUN} and its registers released,
     * so that the same run's assembly puts them in a batch tonight and the court centre gets its
     * document tonight rather than never.
     *
     * <p><strong>Atomic per batch, fenced on the staleness rule itself - and that is why this is a
     * method here rather than a loop in the caller.</strong> A read, then a mark, then a release is
     * not an acceptable shape for it, and the two reasons are the two failures this increment
     * exists to end:
     *
     * <ul>
     *   <li><strong>A stranded register.</strong> {@link #markFailed} and {@link #releaseFailed}
     *       are separate operations. A crash between them leaves registers stamped to a terminal
     *       batch, and {@link #activeUnbatched()} means unbatched - so no later run and no command
     *       ever reaches those rows again, and a hearing's youth defendants quietly stop reaching
     *       a court register at all. Here the failure and the release are one act, or neither
     *       happens.</li>
     *   <li><strong>A refused mark ending the night.</strong> Between a read and a mark the
     *       outcome sink can move the batch, and the state machine would then refuse the mark -
     *       which, run inline in the night's generation, ends the run. One batch that came good in
     *       the wrong second would cost every court centre its document that night. Here such a
     *       batch simply does not match.</li>
     * </ul>
     *
     * <p>So <strong>zero rows is an answer, not an error</strong>: a batch that ceased to be stale
     * between this call and the row being written is one the operation did not change, and it is
     * named in neither list. A store that cannot be reached at all still fails the way every other
     * unreachable store does.
     *
     * <p><strong>Per batch, and that is a promise rather than an implementation detail.</strong>
     * FR-003a says no single batch's outcome may end the run, and one transaction over every stale
     * batch is a way of ending it that no care in the caller can undo: a refusal met on one court
     * centre's registers would roll back every other court centre's release with it. Each batch is
     * therefore failed and released by a statement of its own, in a transaction of its own - the
     * failure and the release of <em>that</em> batch still one act, which is what the requirement
     * was ever about.
     *
     * <p><strong>A transaction of its own whoever calls this, and from where.</strong> The
     * separation is the implementation's to enforce and not the caller's to remember: a caller
     * already inside a transaction would otherwise have every attempt join it, the first refusal
     * would abort it, and every batch released before that one would be rolled back at the end -
     * the run-ending outcome again, reached by obeying the port rather than by breaking it. So
     * each batch's release suspends whatever the caller had open and commits or rolls back by
     * itself, and a caller may wrap this call in a transaction of its own without changing what
     * any batch's ending means.
     *
     * <p><strong>The key keeps one active register, in both directions.</strong> A hearing can hold
     * more than one register for a day, and the release decides between them the same way the
     * recorder does: the later share is the one the day is still to render. A register the estate
     * replaced while the batch was in flight supersedes the one being given back; a share the
     * batched register <em>overtook</em> - a delivery that arrived behind the register it belongs in
     * front of, and was recorded active because a batched register is not the recorder's to
     * supersede - is superseded by it. Neither is handed back beside the other, and a batch whose
     * key holds such a share is released like any other rather than contended for ever.
     *
     * <p><strong>What a re-share can do, and what the caller is told about it.</strong> One thing a
     * staleness predicate cannot fence is a register re-shared while the operation is running: the
     * replacement is not in the snapshot the operation reads, so the release would give the
     * replaced register back beside its replacement and the store would refuse the second active
     * row for the key. That is a lost race rather than a rule, so the adapter makes that batch's
     * statement again on a fresh snapshot that has the re-share in it. A batch whose every attempt
     * met the same refusal is <strong>reported, not thrown</strong>: it is named in
     * {@link StaleReleaseOutcome#contended()}, left exactly as it was found, and the operation goes
     * on to the batches after it and answers normally. Nothing about one batch reaches the caller
     * as an exception, because the pass runs inline in the night's generation and an exception
     * there costs every court centre its document over one hearing that was re-shared three times
     * in a few milliseconds. A contended batch is stale still and untouched, so the next run
     * reaches it again; meanwhile the 07:00 report names its court centre day as a late batch every
     * morning, which is the surface support already watches.
     *
     * <p>A batch holding a document is never matched, at any age - somebody is owed e-mails about
     * it. A batch an operator asked for is given the longer cutoff, because a manual generation
     * holds no run lock and may still be requesting its renders when the schedule fires. Both
     * cutoffs are the caller's to compute, from its own clock and its own settings: this port
     * defaults neither, and a pass that let the store decide what "too long" means would be a
     * setting nobody could change.
     *
     * <p><strong>The one refusal that is still raised.</strong> The race for the day's key is the
     * only refusal this operation knows what to do about. A release refused by any other rule -
     * another unique key, or a constraint that is no key at all, such as a bounded reason a store
     * left short of its migrations does not admit - is a rule nobody wrote this statement against,
     * and the same row meets it on every attempt and on every run. So it is raised as
     * {@link uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotReleasedException}, a fault in the
     * schema or in the statement rather than a race, and the pass may let it end the run as it
     * lets any programming error end one. FR-003a is about a batch's <em>ordinary</em> ending, and
     * that one is reported.
     *
     * <p><strong>So no refusal reaches the pass as an {@code org.springframework.dao} type</strong>
     * and it never has to catch a bare {@code RuntimeException} to read one (Principle V). What
     * does still cross, here and from every method on this port, is the store's own contention
     * signal - a deadlock or a serialisation failure, which is the store answering rather than a
     * rule being broken, and which is the run's ordinary transient failure rather than anything
     * this operation decides about. That is the adapter's store-wide policy and it is stated here
     * so the pass is not written against a promise the package does not make.
     *
     * <p><strong>And the account is told as it is made, not only returned.</strong> Each batch is
     * committed by itself, so a walk that ends in a throw still leaves the batches before it
     * durably failed and released. {@code progress} is told about each of them where it is
     * settled, so a caller keeping an account has the part that happened whether the walk finished
     * or not; {@link StaleReleaseProgress#NONE} is for a caller that keeps none. The return value
     * is the same account, whole, for a walk that got to the end.
     *
     * @param scheduledCutoff the stamp at or before which a batch the schedule made is stale
     * @param manualCutoff    the stamp at or before which a batch an operator asked for is stale
     * @param progress        told about each batch as its own transaction commits, so that an
     *                        interrupted walk still leaves an account of what it did
     * @return the batches this operation changed, oldest day first, each with the count of
     *         registers still that day's to render, and beside them the batches it left exactly as
     *         it found them because every attempt at them lost the same race for the day's key
     * @throws uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotReleasedException if a release was
     *         refused by a rule this operation does not account for
     * @throws uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException if the store could not
     *         be reached
     */
    StaleReleaseOutcome failAndReleaseStale(Instant scheduledCutoff, Instant manualCutoff,
            StaleReleaseProgress progress);

    /**
     * The same release, for a caller that keeps no account of the pass as it goes.
     *
     * <p>The operations commands and the suites that ask the store about one day read the whole
     * answer out of the return value and have no run report to write, so they are not made to pass
     * an observer that would be told nothing they use. Only the nightly pass, whose numbers are
     * the night's own account, tells the difference between the two forms.
     *
     * @param scheduledCutoff the stamp at or before which a batch the schedule made is stale
     * @param manualCutoff    the stamp at or before which a batch an operator asked for is stale
     * @return what the operation released and what it could not release
     * @throws uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotReleasedException if a release was
     *         refused by a rule this operation does not account for
     * @throws uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException if the store could not
     *         be reached
     */
    default StaleReleaseOutcome failAndReleaseStale(final Instant scheduledCutoff,
            final Instant manualCutoff) {
        return failAndReleaseStale(scheduledCutoff, manualCutoff, StaleReleaseProgress.NONE);
    }

    /**
     * Settles the batch on its notification tally, and moves its rows to NOTIFIED.
     *
     * @param batchId the batch whose recipients have all been attempted
     * @param summary the tally and the terminal state it produces
     */
    void markNotified(UUID batchId, NotificationSummary summary);

    /**
     * The registers automatic batching passed over, because the flag did not say ON when they
     * arrived.
     *
     * <p>{@link #activeUnbatched} and this read are the two halves of one predicate: RECORDED,
     * unsuperseded and unbatched in both, and the flag state as recorded is what separates them -
     * ON there, anything else here (research §12). A register recorded while the legacy was
     * generating may already have been sent by the legacy, so it is deliberately left out of every
     * automatic run; without a read that names those rows a rollback would leave every one of them
     * waiting for somebody to notice it, which is what {@code list-batches --recorded-while-off}
     * exists to prevent (FR-016).
     *
     * <p>UNKNOWN is here as well as OFF, and for the same reason the batching excludes it: a row
     * labelled from a reading that had not come back yet is a row nobody can say the legacy did not
     * also send.
     *
     * @return every RECORDED, unsuperseded, unbatched register whose recorded flag state is not ON,
     *         oldest first
     */
    List<RegisterRecord> recordedWhileOff();

    /**
     * Supersedes the registers shared before an instant, so no run will batch them again.
     *
     * <p>The rollback lever's other half, and the write behind {@code supersede-before
     * --shared-before T}. The bound is read against the register's own shared instant - the
     * {@code registerTime} the document was recorded under - because that is the moment the estate
     * agrees on: the period the legacy has taken back over is a period of hearings, not a period of
     * this pod's writes.
     *
     * <p><strong>Only rows that are still this service's to claim.</strong> RECORDED, unsuperseded
     * and unbatched, exactly as {@link #activeUnbatched} reads active - so a row already stamped
     * into a batch is left alone (it is the renderer's: systemdocgenerator has been asked about that
     * batch and a document may exist under it, so what happens to the row is that batch's ending to
     * decide and not a period's), a row already superseded is not superseded twice, and a GENERATED
     * or NOTIFIED row is not rewritten to say a register that was sent was never claimed. Whether
     * the flag was on when a row arrived is not part of the predicate: a rollback supersedes the
     * period, and a row recorded while the flag was off is in that period too.
     *
     * <p><strong>Which leaves the rollback one thing it does not settle.</strong> A stamped row
     * belongs to a batch that may still be failed or released afterwards, and both
     * {@link #markFailed} on a releasing reason and {@link #releaseFailed} unstamp such a row back
     * to RECORDED and unbatched - back into {@link #activeUnbatched}, its recorded flag state
     * unchanged - even where its register instant falls inside a period this call has already taken
     * back. Nothing in the store records that a period was rolled back, so no statement here can
     * refuse it. A rollback of a period whose batches are not all terminal is therefore not final
     * on its own: this call is made again after any release of that period's batches, and the count
     * it answers with is what says whether it had anything left to take.
     *
     * <p>Supersession rather than deletion: the rows stay, carrying what was recorded and when,
     * because the register store is the audit of what this service decided and a rollback is
     * exactly when that audit is read.
     *
     * @param sharedBefore the exclusive upper bound on the registers' shared instant, which the
     *                     caller states and this port never defaults
     * @return how many registers were superseded, which is zero where the period held none
     */
    int supersedeSharedBefore(Instant sharedBefore);
}
