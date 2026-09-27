# Research: Release stale in-flight batches before batching

**Feature**: `004-release-stale-batches` | **Date**: 2026-09-19 | **Plan**: [plan.md](plan.md)
**Revised**: 2026-09-19 after two independent design reviews. D2 and D3 were **reversed** by them;
D7–D11 are theirs. Each entry names what was chosen, why, and what was rejected.

---

## D1 — The pass is a class, called by the run, and not a method on the job

**Decision**: `batch/StaleBatchReleaser`, one object with one method returning what it released.

**Rationale**: `RegisterGenerationJob` already holds the flag gate, the store, the assembler, the
service and the metrics; a sixth responsibility inside it would be untestable without the whole run.

**Rejected**: a private method on the job (untestable in isolation); a scheduled class of its own
(the whole point of the increment is that the decision belongs to the run).

---

## D2 — The release is one fenced statement per batch in the store **[reversed by review, narrowed at gate 3]**

**Decision**: `RegisterStore.failAndReleaseStale(scheduledCutoff, manualCutoff)` — the staleness
predicate read once into a list that decides nothing, then **one statement per batch**, narrowed by
batch id and in a transaction of its own, whose `WHERE` clause *is* the staleness rule, with a bounded
retry on the day's active-register key; answering with `StaleReleaseOutcome`: the batches it changed,
and beside them the batches every attempt was refused over.

**What gate 3 narrowed, and why the fence is unchanged**: the first implementation made the rule the
`WHERE` clause of **one** `UPDATE` over every stale batch. The fence was right and stays; the *unit*
was wrong. A re-share that takes the day's active-register key refuses the release of the batch that
holds it, and under one statement that refusal rolled back every other court centre's release with it
— and an exhausted retry then left the run with an exception to carry. One hearing shared at the wrong
moment, the whole country's documents lost, twice over, which is what FR-003a forbids. Per batch, the
ending of one batch says nothing about the ending of another, and **exhaustion is reported rather than
thrown**: the contended batch is left exactly as it was found, named on `StaleReleaseOutcome.contended()`,
counted by the pass, and the next run reaches it again while the 07:00 report names its court centre
day as a late batch meanwhile.

**What the first pass got wrong**: it specified the pass as a read (`pendingSince`, `generatingSince`)
followed by a `markFailed` per batch, with `NOT_COMPLETED_BY_NEXT_RUN` added to `RELEASING_REASONS`
so the mark released the rows. That is *nearly* right — the reason does join `RELEASING_REASONS` —
but the read-then-mark shape has two failures the reviews found:

- **A lost register.** `markFailed` and `releaseFailed` are separate operations and the first pass
  reasoned about them as if a single `markFailed` were atomic with its release for every reason. Where
  the two are genuinely separate (an operator's path), a crash between them leaves registers stamped
  to a terminal batch. `activeUnbatched`'s predicate is `batch_id IS NULL`, so those registers are
  invisible to every later run: the hearing's youth defendants never reach a court register again.
  That is the precise failure this increment exists to end, reintroduced by the fix for it.
- **A refused mark ending the night.** Between the read and the mark, the outcome sink can commit a
  `markGenerated`. `JdbcRegisterStore.permitted()` then throws `IllegalStateException`, which — run
  inline in `generate()` — propagates out of `correlatedRun`, which reports and **rethrows**. One
  batch that came good in the wrong second would cost every court centre its document that night.

The fenced statement removes both. There is no moment between the decision and the write — the list
the predicate is read into decides nothing, because every batch on it is judged again by the statement
that writes its row: a batch that stopped being stale does not match, and zero rows is an answer.

**Rejected**: a read-then-mark loop with a per-batch `try`/`catch` (the shape the retired reconciler
used). It survives the race but not the crash, and it makes the correctness of the feature depend on
a catch block being wide enough — which is exactly the kind of claim the wide catch in
`correlatedRun` exists to stop having to make. The per-batch shape gate 3 settled on is *not* that
loop: what it repeats is the fenced statement itself, which fails and releases one batch in one act,
so neither the race nor the crash has a window to land in — only the *scope* of the transaction
changed, from every stale batch to one.

---

## D3 — The retired vocabulary is removed **[reversed by review]**

**Decision**: `BatchFailureReason.GENERATION_TIMED_OUT` and `CompletedBy.RECONCILER` are removed from
the enums and from the schema's bounded lists. *(In `V7`, not `V6` — see **D12**, which splits the
migration for a sequencing reason implementation found. The decision to remove them is this one; when
it lands is that one.)*

**What the first pass got wrong**: it kept both as "readable history", on the grounds that
`RegisterBatchRepository` deserialises both columns with `valueOf` and the 07:00 report reads FAILED
batches, so a historical row would throw. That reasoning is sound **about a service that has run**.
This one has not: nothing is deployed, and no environment anybody depends on holds such a row. Keeping
two values that nothing writes would leave the vocabulary describing a mechanism that does not exist,
and a vocabulary is the one place in this service where that is never allowed.

**The cost, stated rather than discovered**: a CHECK constraint cannot be narrowed on a table holding
a violating row, so `V7` refuses to apply to a local volume, a seeded container or a replayed SIT
snapshot that still holds one. Those are cleaned or recreated; `quickstart.md` says so. This is the
cheapest this decision will ever be.

**Rejected**: keeping both and refusing them on the write path (two unproduced values and a rule that
has to be asserted rather than being true by construction); keeping them and mapping unknown strings
to `null` on read (turns a bounded vocabulary into best-effort).

---

## D4 — `yotresultsdistribution.generation.completion` is removed, not pinned

**Decision**: the setting, both constants and the record component go. `PublicEventsConfig` subscribes
whenever the generation half is enabled; `PropertiesValidator`'s broker rule drops its
`completion == event` conjunct and applies unconditionally.

**Rationale**: `poll-only` meant "do not subscribe; the reconciler's query will learn the outcomes".
With the query gone it would mean "do not subscribe and never learn an outcome", under which every
batch reaches the next run in flight, is released and is rendered again — for ever, invisibly, with
every night looking busy. Worse than refusing to start.

**Rejected**: keeping the key with one legal value (reads as a choice and is not one); giving
`poll-only` a new meaning (a retired word repurposed).

---

## D5 — The 07:00 report's rendering limit gets its own value

**Decision**: `yotresultsdistribution.report.batch-generated-within` takes `@DefaultValue("10m")`;
`PropertiesValidator.resolvedBatchGeneratedWithin` and its "a zero grace period makes the unset limit
refuse" case are deleted.

**Rationale**: the borrowing was justified in 003 by the two durations answering the same question,
because the grace period *was* the interval after which something decided a render had not happened.
After 004 they answer different questions — "when should support be told" against "when does a run
give up and re-batch" — and the second is now the longer. Following the rename would triple the
report's threshold as a silent side effect of this increment.

**Rejected**: re-pointing the resolution at `stale-after`; setting `stale-after` to 10m to preserve
the borrowing (which would make the destructive pass run on the shortest plausible age).

---

## D6 — The three in-flight age readings move to a sweep of their own

**Decision**: `batch/BatchAgeSweep`, on `yotresultsdistribution.generation.batch-age-refresh` (`10m`), under no
lock, in every non-command JVM that has the generation half, settling nothing, keeping the
"holds a document nobody was told about" WARN.

**Rationale**: **a Micrometer gauge never decays.** A gauge whose publisher goes away does not fall to
zero; it holds the last value it was given, for ever. Deleting the timer without replacing it would
therefore not merely make three readings stale — it would freeze them at whatever the last
reconciliation saw and leave them looking live. The GENERATED-but-never-notified read is the only
in-hours signal that a document is owed its e-mails. The shape is not invented here: `IntakeAgeSweep`
is the same thing for the intake half, under the rule the design rules already state — a gauge
describes the JVM that publishes it, so it holds no lock and an alert aggregates with `max()`.

**Rejected**: leaving the readings in the pass (a daily sample, taken under the run's lock); folding
them into `IntakeAgeSweep` (which runs where the generation half is off, and would publish three
readings about batches its pod cannot have); dropping them (an unasked-for regression in an increment
whose argument is that the existing visibility suffices).

---

## D7 — The refused-transition drop gets a bounded reason **[review]**

**Decision**: `yotresultsdistribution_public_events_ignored_total{reason="terminal-batch"}`, moved in
`DocumentOutcomeSinkImpl`'s `else` branch, with a pinning test for a late `document-available` on a
`NOT_COMPLETED_BY_NEXT_RUN` batch.

**Rationale**: the design rules say every acknowledged-and-dropped path on the subscription carries a
bounded reason on that counter, and this path does not: it is a WARN and nothing else. The existing
`late-acceptance-ignored` and `late-failure-ignored` labels are on the **notifications** counter and
describe two notifiers racing over one recipient — a different event entirely, and reusing them here
would hide a rendering fact inside a notification series. After 004 this drop is the guarantee that a
released batch's late outcome does not produce a second e-mail, and a guarantee that moves no counter
cannot be alerted on or asserted.

**Rejected**: reusing `unknown-correlation` (untrue: the batch is known); reusing the notification
labels (wrong counter, wrong meaning); leaving it at WARN ("it is in the log index" is not an alerting
surface).

---

## D8 — An operator's batch gets the longer grace **[review]**

**Decision**: a `system_generated = false` batch is stale only after `max(staleAfter, lockAtMostFor)`.

**Rationale**: a manual generation holds no run lock and is allowed the whole `run-deadline` to ask
for its renders, with `lock-at-most-for` already validated to be that plus a margin. A batch it
assembled at 17:25 is over thirty minutes old at 18:00; failing it would orphan a render the manual
run is still making, and the manual run's own `markRequested` — fenced on PENDING — would then throw
out of `RegisterGenerationService`. `max(...)` rather than `lockAtMostFor` alone so that a deployment
which lengthens `stale-after` past the lock does not accidentally shorten an operator's grace.

**Rejected**: a third setting (a second answer to "how long may a run take"); exempting manual batches
for ever (they would then be the batches that strand); ignoring the case (it is the one case where
this increment could destroy work in progress).

---

## D9 — A released batch is informational at 07:00 **[review]**

**Decision**: `ExceptionKind.BATCH_RELEASED`, derived from the reason, read over the window like the
other failure kinds.

**Rationale**: a `BATCH_FAILED` entry asks support to look at something that went wrong and is still
wrong. A released batch's registers were re-assembled, re-rendered and sent the same night; reporting
it as a failure would send support after something already put right, every morning after any lost
outcome — which is exactly the noise that makes a report stop being read.

**Rejected**: leaving it as `BATCH_FAILED` (false alarms); leaving it out of the report entirely (a
night that had to undo work is worth knowing about, and the counter alone is not a per-batch record).

---

## D10 — The flag-OFF night is accepted **[review]**

**Decision**: the pass stays behind the gate. On a night the flag says the legacy is live, nothing is
released and in-flight batches stay in flight until the flag returns.

**Rationale**: a service that may not generate may not decide that a batch it would not be allowed to
re-render has failed — and a release on such a night would strand the registers until the flag came
back anyway, since nothing would assemble them. The 07:00 report's `BATCH_LATE` entry is the signal
meanwhile, and the first ON night releases and sends those days.

**Rejected**: moving the pass before the gate (it would fail batches on nights the service is not in
charge, and the registers would sit unbatched with no run to pick them up).

---

## D11 — The pass closes the PENDING-with-no-payload gap **[review]**

**Decision**: staleness is state and age; how far a batch got is not part of the rule. A PENDING batch
with a null `payload_file_id` is therefore released like any other.

**Rationale**: the retired reads excluded it — the overdue read was GENERATING only and the stalled
read looked for a minted payload — so such a batch sat in flight for ever, deferring its court centre
day at every subsequent run, with nothing in the flow that would ever revisit it. It is a real gap and
the new rule closes it for free, which is worth saying out loud and pinning rather than discovering.

---

## D12 — The admission and the removals take two migrations **[found in implementation]**

**Decision**: `V6__admit_stale_release_reason.sql` widens the failure-reason vocabulary by
`NOT_COMPLETED_BY_NEXT_RUN` and removes nothing; `V7__retire_reconciler_vocabulary.sql`, landing
immediately after the reconciler and its query leg are deleted, removes `GENERATION_TIMED_OUT` and
`CompletedBy.RECONCILER` from the enums and narrows all three CHECK constraints.

**What D3 got wrong**: not the destination, only the claim that one migration could reach it. D3 and
FR-012 both said the admission and the removals were *the same forward migration*. Implementation
showed that those cannot both be satisfied:

- `GenerationReconciler` is the **only** writer of both retired values — its silence ending is built
  from `GENERATION_TIMED_OUT` and `CompletedBy.RECONCILER`, and it passes `RECONCILER` into the
  store's two marks. It is deleted three phases after the vocabulary work was scheduled. Removing the
  constants earlier either fails to compile or forces its marks to claim `EVENT`, which would write
  into `completed_by` — the one column that exists to say which mechanism learned an outcome — a
  claim that is false.
- The **admission** cannot wait for that deletion, because the first write of the new reason comes
  earlier: the store operation is built and exercised against Testcontainers Postgres well before
  the reconciler goes.
- `SchemaMigrationV2IT` holds the CHECK constraints against the enums in both directions, so a
  narrowed constraint and an enum that still has the constants cannot both be green.

Three true sentences with no ordering that satisfies all of them: a cycle, not a sequencing
preference. The way out is to stop requiring one migration. Splitting changes no behaviour and no end
state — after `V7` the schema and the enums are exactly what D3 described — only the order and
FR-012's wording.

**It also improves the operational story.** The caveat that a narrowing migration refuses on a
pre-004 row now attaches to `V7` alone. `V6` widens only, so it applies to any store in any state,
and the phase that lands it needs no clean-volume step at all.

**Rejected**: moving the whole vocabulary block to after the deletions (ruled out — it would put the
admission after the first write of the reason it admits); re-pointing the reconciler's marks at
`EVENT` for the two phases in between (a false claim in the audit column, to save one migration);
keeping both retired values for ever (D3's superseded answer, and it leaves the vocabulary naming a
mechanism that does not exist).

**Credit where it is due**: this was found by the Phase 1 implementer, which declined the tasks twice
with evidence rather than ticking them. An unticked task is the honest record of a phase that cannot
be finished yet.

## Facts established while researching (not decisions)

- `register_batch` has **`assembled_at`**, not `created_at`. `COALESCE(requested_at, assembled_at)`
  expresses "the stamp for whichever state it is in" in one expression.
- `register_batch_completed_by_shape_chk`'s third arm is an equality between "the reason is
  generator-attributed" and "an attribution is present", so the new reason needs no special case —
  only the narrowing that D3 brings.
- `yotresultsdistribution.generation.grace-period` and `.completion` are **literals** in `application.yaml`
  with no `${...}` placeholder, so this repository defines no environment variable for either. The
  outside-repo item is a check of the deployment branches for a raw override, probably empty.
- **`docker/sdg-echo/sdg-echo.py` does not implement the query endpoint**, contrary to one review's
  file list: it watches WireMock's journal and publishes `document-available` onto the topic. The
  query stub is a WireMock mapping, and that is what is deleted.
- `RunCorrelation`'s ambient-adoption branch exists because the run called the reconciler. It stays
  live because `StaleBatchReleaser` calls `RunCorrelation.under(...)` from inside the run and adopts
  its id; only the javadoc's account of which two units nest has to change.
- `doc/DEFECT-FIXES.md` row `P2` is the only register row naming a test this increment removes.
