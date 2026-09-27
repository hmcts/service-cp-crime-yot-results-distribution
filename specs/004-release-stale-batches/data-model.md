# Data Model: Release stale in-flight batches before batching

**Feature**: `004-release-stale-batches` | **Date**: 2026-09-19 | **Plan**: [plan.md](plan.md)
**Revised**: 2026-09-19 after the two design reviews.

No table is added, no column is added, no column is dropped and no row is migrated. Three CHECK
constraints are rewritten, one bounded statement is added to the store, and two vocabularies change.

## The migrations — two, and why

The admission and the removals **cannot share a migration**. `GenerationReconciler` is the only
writer of `BatchFailureReason.GENERATION_TIMED_OUT` and `CompletedBy.RECONCILER`, and it is not
deleted until the removal phase; while it exists the schema must go on admitting both. But the first
write of `NOT_COMPLETED_BY_NEXT_RUN` comes earlier than that deletion, so its admission cannot wait
for it. One migration therefore widens, and a second narrows once the writer is gone. Both are
additive and forward-only in the Flyway sense; `V2` is never edited.

### `V6__admit_stale_release_reason.sql` — widens only

Lands with the vocabulary's new constant, before anything writes it.

```sql
ALTER TABLE register_batch DROP CONSTRAINT register_batch_failure_reason_chk;
ALTER TABLE register_batch ADD CONSTRAINT register_batch_failure_reason_chk
    CHECK (failure_reason IS NULL
        OR failure_reason IN ('PAYLOAD_STORE_UNAVAILABLE', 'RENDER_REQUEST_FAILED',
                              'RENDER_REQUEST_REJECTED', 'GENERATION_FAILED',
                              'GENERATION_TIMED_OUT', 'ASSEMBLY_FAILED',
                              'NOT_COMPLETED_BY_NEXT_RUN'));
```

- The two retired values are **still in the list**. The reconciler is still writing them at this
  point in the increment, and a migration that refused them would break the running service.
- `register_batch_completed_by_chk` and `register_batch_completed_by_shape_chk` are **not touched**.
  The new reason is not generator-attributed, so it lands in the shape constraint's third arm's
  `false = false` case with no edit, and `attributionOf` is called with `null` for it.
- **It refuses on nothing.** A constraint that only widens admits every row the old one did, so this
  migration applies to any store in any state. There is no clean-the-volume step before it.

### `V7__retire_reconciler_vocabulary.sql` — narrows, once the writer is gone

Lands immediately after the reconciler and its query leg are deleted.

```sql
-- 1. The failure vocabulary: the reason nothing produces any more.
ALTER TABLE register_batch DROP CONSTRAINT register_batch_failure_reason_chk;
ALTER TABLE register_batch ADD CONSTRAINT register_batch_failure_reason_chk
    CHECK (failure_reason IS NULL
        OR failure_reason IN ('PAYLOAD_STORE_UNAVAILABLE', 'RENDER_REQUEST_FAILED',
                              'RENDER_REQUEST_REJECTED', 'GENERATION_FAILED', 'ASSEMBLY_FAILED',
                              'NOT_COMPLETED_BY_NEXT_RUN'));

-- 2. The attribution vocabulary: the mechanism that no longer exists goes.
ALTER TABLE register_batch DROP CONSTRAINT register_batch_completed_by_chk;
ALTER TABLE register_batch ADD CONSTRAINT register_batch_completed_by_chk
    CHECK (completed_by IS NULL OR completed_by = 'EVENT');

-- 3. The shape: the attributed list narrows to the one reason somebody outside this service
--    still reports. The three-arm structure and its COALESCE are unchanged.
ALTER TABLE register_batch DROP CONSTRAINT register_batch_completed_by_shape_chk;
ALTER TABLE register_batch ADD CONSTRAINT register_batch_completed_by_shape_chk
    CHECK ((status NOT IN ('PENDING', 'GENERATING') OR completed_by IS NULL)
       AND (status NOT IN ('GENERATED', 'NOTIFIED', 'PARTIALLY_NOTIFIED', 'NOTIFIED_NOBODY')
                OR completed_by IS NOT NULL)
       AND (status <> 'FAILED'
                OR ((COALESCE(failure_reason, '') = 'GENERATION_FAILED')
                     = (completed_by IS NOT NULL))));
```

**The one operational caveat belongs to V7 alone, and it is the reason this is a decision and not a
tidy-up.** A CHECK constraint cannot be added to a table that already holds a violating row.
Statements 1 and 2 therefore **refuse to apply** to any store still holding a batch failed
`GENERATION_TIMED_OUT` or completed by `RECONCILER`. Nothing is deployed, so no environment anybody
depends on holds one; a developer's local volume, a seeded container or a replayed SIT snapshot may,
and is cleaned or recreated before V7 runs. `quickstart.md` says so. Doing this now costs a
`docker compose down -v`; doing it after the first real evening costs a data migration.

### Where each one sits

| | Admits `NOT_COMPLETED_BY_NEXT_RUN` | Holds the retired values | Refuses on an existing row |
|---|---|---|---|
| after `V5` (today) | no | yes | — |
| after `V6` | **yes** | yes | no |
| after `V7` | yes | **no** | yes, if one is present |

The end state — the third row — is the one this document described before the split, and is
unchanged by it.

## The store operation

`RegisterStore.failAndReleaseStale(Instant scheduledCutoff, Instant manualCutoff)` →
`StaleReleaseOutcome` **[gate 3]**, which is two lists: `released`, one `ReleasedBatch` per batch the
operation changed (`batchId`, `courtCentreId`, `registerDate`, `releasedRegisters`), and `contended`,
the ids of the batches it left exactly as it found them.

**One statement per batch, each in its own transaction, whose `WHERE` clause is the staleness rule
[gate 3].** The staleness predicate is read once, into a list that **decides nothing** — every batch
it names is judged again by the statement that writes its row, so a batch that stopped being stale in
between matches nothing — and each batch is then failed and released by one statement of its own,
narrowed by batch id, with its own bounded retry on the day's active-register key. There is still no
read followed by a mark, and that is the whole point: a read-then-mark pass can be overtaken between
the two, and then either the mark is refused — which, run inline in `generate()`, throws out of the
run and loses the entire night's generation — or, worse, the mark lands and the separate release does
not, leaving registers stamped to a terminal batch where no later run can see them, because
`activeUnbatched`'s predicate is `batch_id IS NULL`. That is a **lost register**, and it is the exact
failure this increment exists to end.

**The unit of atomicity is one batch, not the pass [gate 3].** One transaction over every stale batch
is atomic in the wrong unit: a refusal met on one court centre's registers rolls back every other
court centre's release with it, so one hearing shared at the wrong moment costs the whole country its
documents. And exhaustion is **reported, never thrown**: a batch whose every attempt met the same
refusal — a re-share that commits inside each attempt's own window, and so holds the day's
active-register key against every snapshot the operation reads — is
left exactly as it was found, named on `StaleReleaseOutcome.contended()`, counted by the pass, and
the operation goes on to the batches after it and answers normally. No single batch's outcome may end
the run (FR-003a). A contended batch is stale still and untouched, so the next run reaches it again,
and the 07:00 report names its court centre day as a late batch every morning meanwhile.

The predicate:

```sql
WHERE status IN ('PENDING', 'GENERATING')
  AND COALESCE(requested_at, assembled_at)
        <= CASE WHEN system_generated THEN :scheduledCutoff ELSE :manualCutoff END
```

- `COALESCE(requested_at, assembled_at)` is the rule in one expression: a GENERATING batch has a
  `requested_at` and is measured from it; a PENDING one has none and is measured from
  `assembled_at`. The column is `assembled_at` — there is no `created_at` on this table.
- `system_generated` picks the cutoff. A batch the schedule made gets `now - staleAfter`; a batch an
  operator asked for gets `now - max(staleAfter, lockAtMostFor)`, because a manual generation holds
  no run lock and has the whole requesting deadline to work in (FR-017).
- `GENERATED` is not in the list, at any age (FR-002).
- A batch with a null `payload_file_id` **is** included — it is PENDING and it is old, and how far it
  got is not the question. That is the gap the retired reads left open, in which a batch sat in
  flight for ever and deferred its court centre day at every run (FR-020).
- A batch that ceased to match between the operation being asked for and the row being written is
  simply not among the rows returned. Zero rows is an answer, not an error.

The write, in the same statement's scope: `status = 'FAILED'`,
`failure_reason = 'NOT_COMPLETED_BY_NEXT_RUN'`, `completed_by = NULL`, and the **existing** release
and supersession branch of `MARK_FAILED` (the one `:releaseRows` selects) applied to the matched
batch's registers. `NOT_COMPLETED_BY_NEXT_RUN` joins `JdbcRegisterStore.RELEASING_REASONS`, so a
per-batch `markFailed` — which the operations surface may still make — releases on it too.

**The key keeps one active register, in both directions [gate 4].** The release reads the total
order `(register_time, created_at, output_id)` over a key's registers the same way the recorder
does, and both ways round:

| What the key also holds | What the release does with it |
|---|---|
| a **later** active register — the estate re-shared the hearing while the batch was in flight | the register being given back is superseded against it, and is not counted among the registers the day is still to render |
| an **earlier** active, unbatched, RECORDED register — a share the batched register overtook, recorded active because a batched register is not the recorder's to supersede | it is superseded against the register being given back, in the same statement (`superseded_by` = the register coming back), which then goes back active |

The second row is the one gate 4 added, and it is a correctness requirement rather than a tidiness
one: the earlier row holds `idx_output_active_register_key` for the day, no fresh snapshot removes a
row committed before the statement began, and the recorder will not supersede a batched register on
its behalf — so without it that batch is reported **contended by every run for ever** and its court
centre day is never rendered. It applies only where the given-back register has no successor of its
own; a register that is itself being superseded takes no key and displaces nothing.

The supersession of the earlier row is **chained ahead of** the release in the statement, exactly as
`RECORD_REGISTER` chains `replaced` ahead of its insert and for the same index: the row being
superseded holds the key until its update takes it out of the index, so a release issued first
collides with the row it is about to supersede. Postgres does not otherwise order the clauses of one
statement, so the release's source counts the supersession's rows.

**And the same clause belongs to the two statements beside it** (closed before Phase 3 opened). The
`MARK_FAILED` released branch — the one `:releaseRows` selects — and `RELEASE_FAILED`, which an
operator's `release-batch` makes, hand a register back through the same index and read the same key.
Left reading the order one way only they clear the stamp beside an earlier active share, the index
refuses the second active row for the day, and the whole statement goes down with it: the failure
mark in the first case and the operator's command in the second, for ever, because no re-run removes
a row committed before it. Both now carry the `overtaken` clause, chained the same way and — in
`MARK_FAILED` — guarded by `:releaseRows` through `stamped`, which already carries that guard. Pinned
by `RegisterStoreIT`'s twins `Failure.a_failure_that_never_left_should_supersede_the_share_it_overtook`
and `Releasing.a_release_should_supersede_the_share_it_overtook`.

## Vocabulary

### `BatchFailureReason` — six, one in and one out

| Constant | Produced by | Releases rows | Attributed |
|---|---|---|---|
| `PAYLOAD_STORE_UNAVAILABLE` | the requesting leg | yes | no |
| `ASSEMBLY_FAILED` | the requesting leg | yes | no |
| `RENDER_REQUEST_FAILED` | the requesting leg | no | no |
| `RENDER_REQUEST_REJECTED` | the requesting leg | no | no |
| `GENERATION_FAILED` | the `generation-failed` event | no | **yes** (`EVENT`) |
| `NOT_COMPLETED_BY_NEXT_RUN` | **new** — the stale-batch pass | **yes** | no |
| ~~`GENERATION_TIMED_OUT`~~ | **removed** — nothing produced it after the reconciler went | — | — |

`NOT_COMPLETED_BY_NEXT_RUN` means: *this batch was still waiting for its render when the next
scheduled run began, and had been waiting long enough, so this service stopped waiting and gave its
registers back.* It says nothing about whether systemdocgenerator ever received the request, because
after 004 that is not a question this service can ask — and the ending is the same either way.

`isGeneratorAttributed()` narrows to `GENERATION_FAILED` alone.

### `CompletedBy` — one

`EVENT`. `RECONCILER` is removed with the mechanism it named. The type stays a type rather than
collapsing into a boolean: it is an argument carried through `DocumentOutcomeSink` into the store's
`mark` calls, and a second mechanism is exactly the kind of thing that comes back.

### `ExceptionKind` — six **[review]**

`REQUEST_FAILED`, `REQUEST_LATE`, `BATCH_LATE`, `BATCH_FAILED`, `NOTIFICATION_FAILED`, and new:

- **`BATCH_RELEASED`** — a batch the nightly run gave up on and released. Read over the window like
  the other failure kinds, so it lands in exactly one report. **Informational**: its registers were
  re-rendered the same night, so support is being told what happened, not asked to act. The service
  derives it from the reason: a FAILED batch under `NOT_COMPLETED_BY_NEXT_RUN` is `BATCH_RELEASED`,
  every other FAILED batch is `BATCH_FAILED`. `ExceptionReportService`'s `nameOf(dead)` and its
  "four stages" text are both checked for exhaustiveness in the same task.

*(003's spec says the bounded set is five. That spec is the record of what 003 did and is not
retro-edited; this document is the live statement of the vocabulary.)*

### The ignored-outcome vocabulary **[review]**

`yotresultsdistribution_public_events_ignored_total{reason}` gains **`terminal-batch`**: an outcome for a batch
the state machine will not move. Today that drop is a WARN and nothing else — the counter fires for a
foreign source, an unknown correlation, a payload mismatch and three envelope faults, and
`late-acceptance-ignored` / `late-failure-ignored` belong to the *notifications* counter and describe
a different thing entirely (a second notifier racing over one recipient). After 004 this drop is the
guarantee that a released batch's late `document-available` does not produce a second e-mail, and a
guarantee that moves no counter is not one you can alert on.

A **redelivery** of such an outcome — which is what happens when the pass wins the race, the sink's
mark is refused, the listener rethrows and the broker offers the message again — is counted under the
same reason, never as an unknown correlation.

## The batch state machine, after

```text
recorded rows, active and unbatched
   ▼ (18:00 run, flag ON)
   ▼ STALE-BATCH RELEASE PASS   ── one statement per batch: every PENDING or GENERATING batch
   │                               older than its cutoff (stale-after for the schedule's own
   │                               batches, the longer of stale-after and the run lock for an
   │                               operator's), each in a transaction of its own
   │                               ▼ FAILED / NOT_COMPLETED_BY_NEXT_RUN, rows released
   │                               ▼ or, where every attempt lost the day's active-register key,
   │                                 reported contended and left for the next run
   ▼ BatchAssembler groups by (court centre, register date)
   │  a court centre day whose batch is still in flight - and younger than its cutoff, because
   │  the pass has just run - is DEFERRED
   ▼ PENDING            the batch exists and holds its rows
   ▼ assemble payload → PayloadFileStore  ⇒ FAILED/ASSEMBLY_FAILED
   │                                        or FAILED/PAYLOAD_STORE_UNAVAILABLE
   ▼ mint ids, then request the render (ids before calls, always)
   ▼ GENERATING         systemdocgenerator accepted (202)
   │     ├─ refused / undeliverable ─▶ FAILED, RENDER_REQUEST_REJECTED / RENDER_REQUEST_FAILED
   │     ├─ document-available (public.event) ─▶ GENERATED
   │     ├─ generation-failed  (public.event) ─▶ FAILED, GENERATION_FAILED (+ sdg_reason)
   │     └─ neither, and still so at the next run ─▶ FAILED, NOT_COMPLETED_BY_NEXT_RUN,
   │                                                 rows released into that run's batches
   ▼ GENERATED          the PDF exists in the file service
   ▼ notify every matched Youth Offending Team, once each
   ├─ all accepted ──────────────────▶ NOTIFIED
   ├─ some accepted ─────────────────▶ PARTIALLY_NOTIFIED   (the rest are resendable)
   └─ nobody to tell ────────────────▶ NOTIFIED_NOBODY

  any outcome arriving for a batch already past the state it would move to:
      acknowledged, not applied, counted terminal-batch
```

What changed: the last arm of the GENERATING branch, which used to read *"neither, past the grace
period → FAILED, GENERATION_TIMED_OUT (reconciler)"*, decided between runs by a timer that asked
systemdocgenerator. A PENDING batch that never reached the renderer takes the same arm now, where it
used to be asked about separately and failed `RENDER_REQUEST_FAILED` on the strength of a query's
silence — and a PENDING batch that never even minted a payload takes it too, where it used to take
no arm at all.

## Ages and where they are read from

| Batch state | Stamp | Used by |
|---|---|---|
| `PENDING` | `assembled_at` | the pass (via `COALESCE`), `BatchAgeSweep` (`pendingSince`) |
| `GENERATING` | `requested_at` | the pass (via `COALESCE`), `BatchAgeSweep` (`generatingSince`) |
| `GENERATED` | `generated_at` | `BatchAgeSweep` only (`generatedSince`) — never the pass |

Cutoffs are computed once per pass from the injected clock: `scheduledCutoff = now - staleAfter`,
`manualCutoff = now - max(staleAfter, lockAtMostFor)`. A batch is stale when its stamp is **at or
before** its cutoff, so a batch at exactly the minimum age is stale.

## Instruments

| Name | Before | After |
|---|---|---|
| `yotresultsdistribution_generation_reconciled_total` | outcomes the reconciler fetched | **retired** |
| `yotresultsdistribution_generation_released_batches_total` | — | **new**: batches a run released |
| `yotresultsdistribution_generation_released_registers_total` | — | **new**: registers that came back with them |
| `yotresultsdistribution_generation_contended_total` | — | **new [Phase 3]**: batches the pass could not release, every attempt at them having lost the day's active-register key. Unlabelled, because a batch id may never be a series; FR-003a asks for it by name ("counted by the pass's line and its counter") and the design rules ask for it generally — a path that leaves something undone moves a counter |
| `yotresultsdistribution_oldest_generating_age` | the retired timer | `BatchAgeSweep`, same meaning and cadence |
| `yotresultsdistribution_oldest_pending_age` | the retired timer | `BatchAgeSweep`, same meaning and cadence |
| `yotresultsdistribution_oldest_generated_age` | the retired timer | `BatchAgeSweep`, same meaning and cadence |
| `yotresultsdistribution_batches_total{outcome}` | six failure reasons | six, with the swap |
| `yotresultsdistribution_public_events_ignored_total{reason}` | six reasons | seven: + `terminal-batch` |

The three gauges are now **per pod**, which they were not before: under the retired timer they were
taken under a lock, so one pod published and the others published nothing. An alert aggregates them
with `max()`, which is the rule the design rules already state for `IntakeAgeSweep`'s gauges. **A
Micrometer gauge never decays** — this is why the sweep exists at all: a gauge whose publisher goes
away does not fall to zero, it holds the last value it was given for ever.

## The run report

`RunReport.reconciled` → `releasedBatches` and `releasedRegisters`; the line's `reconciled=` becomes
`released_batches=` and `released_registers=`. **Neither is a third sum.** The registers counted by
`releasedRegisters` are re-batched by the same run and are therefore already inside `rows=` and the
row outcomes; the two numbers are a diagnostic about what the night had to undo, beside the two
accounts the report already claims add up. `RunReport`'s javadoc says so where it says what does add
up, because a number on a line of totals that is not part of any total has to say so or it will be
added to one.

**And a third key, `contended=`** (coordinator's decision, 2026-09-20, taken at Phase 4). The pass
answers with three numbers and the batches it could not give back are work the night left undone:
they are stale still and untouched, so the next run reaches them again and the 07:00 report names
their court centre days every morning meanwhile. A run line that carried the two released numbers
and said nothing about those would describe as complete a night that had left a court centre
without its document, which is the silence this service exists to end. So `RunReport` carries
`contended` beside the two released numbers, the line carries `contended=`, and the accounting
paragraph names all three as being in neither of the night's totals. It is the same number
`yotresultsdistribution_generation_contended_total` already carries — the line and the counter say one thing,
as they do for every other reading a run publishes.
