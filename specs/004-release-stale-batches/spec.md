# Feature Specification: Release stale in-flight batches before batching

**Feature Branch**: `004-release-stale-batches`
**Created**: 2026-09-19
**Status**: Planned
**Input**: User description: "Release stale in-flight batches before batching, and retire the reconciler's timer and its systemdocgenerator query"

## Context

Increment 002 gave every batch two ways to learn what became of its render: the `document-available`
and `generation-failed` events on the shared `public.event` topic, and — for the batch whose event
never arrived — a grace-period reconciler that ran on its own ten-minute timer, asked
systemdocgenerator's query API what had happened to the payload, and applied the answer. The query
was the flow's one synchronous coupling to the renderer, and the timer was one of the two schedules
the generation half carried.

The design owner's decision of 2026-09-19 retires both. What replaces them is a single pass at the
start of the nightly run: a batch that is still waiting for its render when the next run begins,
and has been waiting longer than a minimum age, is failed under a new bounded reason and its
registers are given back, so the assembler puts them in a batch tonight and the court centre gets
its document tonight. Nothing is asked of systemdocgenerator between runs.

The grounds for the change, recorded here because this is the document that outlives the decision:

- **The event path already handles a slow render.** A render that takes twenty minutes still
  announces itself, and the batch still reaches GENERATED and still notifies. The query only ever
  mattered for an outcome that was *lost*, not for one that was late.
- **Visibility is already covered.** The 07:00 exception report has its own late-batch threshold and
  reports a batch that has been awaiting its render too long, every weekday morning, with no
  dependence on the query or the timer.
- **The query bought one cheap re-render and nothing else.** When it found a document, it saved a
  second render of the same batch; when it found a refusal, it recorded a bounded reason the next
  run would have reached anyway; when it found silence, it failed the batch and stranded its
  registers behind an operator decision. A re-render is cheap. The stranding was not.
- **Progression's leg, the oracle for this half, had no timeout at all.** Removing the timeout is a
  move back towards the behaviour this service ported, not away from it.

**The trade, recorded plainly**: a render that goes silent is now detected at the next scheduled run
rather than within ten minutes of going silent. Between 18:00 and the following 18:00, a batch whose
outcome is lost stays GENERATING and its registers stay stamped. The 07:00 report names it the next
morning; the run that follows releases it. This is a deliberate exchange of detection latency for
the removal of a synchronous dependency, a timer, a lock and an entire failure mode
(a batch failed on the strength of a query the renderer could not answer).

This is **not a defect fix**. Neither oracle — the function app for the intake half, progression's
leg for the downstream half — had a reconciler, a query or a stale-release pass, so nothing here is a
catalogued defect being fixed. No `doc/DEFECT-FIXES.md` row is added. One existing row (`P2`) names a
pinning test that this increment removes, and that row's pinning-test cell is re-pointed at the test
that holds the same promise afterwards — an amendment to a cell, not a new row and not a change to
what P2 claims. The differential audit is unaffected in both directions.

## Clarifications

### Session 2026-09-19

Five decision points were found by the ambiguity scan. Each was answered from the design owner's
decision of 2026-09-19 and from what the existing code makes possible; where the decision left
genuine latitude, the option that changes the least behaviour was taken. Each answer is applied in
the section named beside it.

- Q: Is a stale batch released by failing it and then asking for a separate release, or by a failure
  that releases in the same act? → A: **The same act.** The new reason joins the reasons that release
  a batch's registers as part of failing it. Two statements leave a window in which the batch is
  FAILED and its registers are still stamped, and a run that stopped in that window would strand them
  exactly as the behaviour this increment removes did. *(FR-003, Assumptions.)*
- Q: Does the run line's released count count batches or registers? → A: **Both, as two numbers.**
  A batch is one document and one e-mail; a register is one hearing's youth defendants, and the run
  line already keeps both accounts of a night because neither answers the other's question. The
  released registers are re-batched by the same run and are therefore *also* counted in that night's
  row totals: the released numbers are a diagnostic beside the night's accounts, not a third sum to
  add to them. **A third number joined them at Phase 4** (coordinator, 2026-09-20): the batches the
  pass could not give back, on the line as `contended=`. *(FR-009, Assumptions.)*
- Q: Are the retired timeout reason and the retired completion mechanism removed from the bounded
  vocabularies, or kept? → A: **Removed** — from the enums and from the schema's bounded lists, in
  a forward migration of their own. *(Revised twice: by design review, and again on 2026-09-19 when
  implementation found that the admission and the removal cannot share one migration — see the third
  session below.)*
- Q: What becomes of the deployment shape that learned outcomes only from the query? → A: **Removed
  outright**, rather than left as a setting with one legal value. After this change it would mean
  "learn no outcome, fail every batch at the next run, render every day twice" — strictly worse than
  refusing to start. A deployment with the generation half enabled must be configured for the
  public-event topic, which is what every deployment of this service is configured for today.
  *(FR-013, Edge Cases, Assumptions.)*
- Q: Does the 07:00 report's rendering limit follow the renamed setting, or get a value of its own?
  → A: **Its own value, keeping today's ten minutes.** The two durations now answer different
  questions — "when should support be told a render is late" and "when does a run give up and
  re-batch" — and following the renamed setting would silently move the report's threshold from ten
  minutes to thirty as a side effect of this increment. *(FR-014, Assumptions.)*

### Session 2026-09-19 (design review)

Two independent reviews of the design above found four things the first pass had wrong or missing and
revised one of its answers. Each is a decision taken by the reviewers and the coordinator, recorded
here in the same form.

- Q: Is the fail-and-release safe against a crash or a race? → A: **No, as first specified, and it
  must be one fenced statement.** Today `markFailed` releases rows only for the two reasons in
  `RELEASING_REASONS`, and `releaseFailed` is a separate operation preceded by its own status read; a
  crash between a mark and a release leaves registers stamped to a terminal batch and invisible to
  `activeUnbatched`, whose predicate is `batch_id IS NULL` — a **lost register**, which is the exact
  failure this increment exists to end. The pass therefore asks the store for **one operation**,
  `failAndReleaseStale`, whose single statement selects, fails and releases in one transaction and is
  **fenced on the cutoff predicate itself**: a batch that stopped being stale between any two moments
  simply does not match, and a lost race is a zero-row result rather than an exception.
  *(FR-003, FR-017.)*
  **Narrowed at review gate 3 (2026-09-20):** the fence is unchanged, but the unit of atomicity is
  **one batch** — the predicate is read once into a list that decides nothing, and each batch is then
  failed and released by that same fenced statement narrowed to its own id, in its own transaction
  and with its own bounded retry on the day's active-register key; a batch every attempt was refused
  over is reported, not thrown. One transaction over every stale batch was atomic in the wrong unit.
  FR-003a is the live statement of this; this answer is the record of the moment it was taken.
- Q: What happens when the pass and the outcome sink race at 18:00? → A: **Both orders are safe, and
  neither may end the run.** Sink first: the batch is GENERATED, the pass's predicate no longer
  matches it, nothing happens. Pass first: the sink's mark is refused by the state machine, the
  listener rethrows, the broker redelivers, and the sink then reads a FAILED batch and drops the
  outcome — self-healing. Running the pass as a read-then-mark loop would instead let a refused mark
  throw out of the run and lose the whole night's generation, which is why the fenced statement is a
  correctness requirement and not a tidiness preference. *(FR-003, Edge Cases.)*
  **Superseded in part at review gate 3 (2026-09-20)**: the same argument turned out to apply to the
  pass's own scope, so the statement is now made per batch — see the answer above.
- Q: Is a late outcome for an already-ended batch really *counted* today? → A: **No — it is logged at
  WARN and counted nowhere.** The public-events ignored counter fires for a foreign source, an
  unknown correlation, a payload mismatch and three envelope faults, but not for an outcome the state
  machine refuses; the `late-acceptance-ignored` and `late-failure-ignored` labels belong to the
  *notifications* counter and are a different thing entirely. The design rules' "every drop is
  counted under a bounded reason" is therefore not true of this drop. This increment makes it true
  with a new bounded reason, because the drop is now the guarantee that stops a double e-mail.
  *(FR-008.)*
- Q: May the pass touch a batch an operator asked for by hand? → A: **Not until it has had the full
  requesting deadline.** A manual generation holds no run lock and is allowed sixty minutes to ask
  for its renders; one that started at 17:25 would have a batch older than thirty minutes at 18:00,
  and failing it would orphan a render the manual run is still making and throw its own
  `markRequested` out. A batch the schedule did not make is therefore stale only after the longer of
  the minimum age and the run's own lock duration. *(FR-017.)*
- Q: Should a released batch appear in the 07:00 report as a failure? → A: **No — as its own
  informational kind.** Its registers were re-rendered the same night, so reporting it beside the
  batches that genuinely failed would send support after something that has already been put right.
  *(FR-019.)*
- Q (revised): the retired timeout reason and the retired completion mechanism. → A: **Removed, not
  kept.** Nothing is deployed, so no row that anyone must be able to read carries either; keeping two
  values nothing writes would leave a vocabulary that describes a mechanism that no longer exists.
  They go from the enums and from the schema's bounded lists — in a **second** forward migration,
  for the sequencing reason the third session below records. The one operational consequence is in
  Assumptions: that migration refuses to apply to a store that still holds such a row, so a local or
  test database that does is cleaned or recreated. *(FR-012, Assumptions.)*

### Session 2026-09-19 (implementation)

One decision point that only implementation could find. The Phase 1 implementer declined the
vocabulary tasks twice, with evidence, and was right to.

- Q: Can the new reason be admitted and the retired values removed in one forward migration? → A:
  **No — it is a cycle, and it takes two migrations.** The retired pass is the **only** writer of
  both retired values, and it is not deleted until late in the increment; removing them earlier
  would either fail to compile or force a live write to claim that a different mechanism learned an
  outcome, which is a false claim in the one column that exists to say which mechanism did. Yet the
  first write of the **new** reason comes before that deletion, so its admission cannot wait. One
  migration therefore admits and widens only, refusing on nothing; a second narrows the three
  constraints and removes the two constants, immediately after the pass that wrote them is gone. The
  behaviour and the end state are unchanged — only the sequencing, and the sentence in FR-012 that
  said "the same forward migration". *(FR-012, Assumptions.)*

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A night's registers are never stranded by a render nobody heard about (Priority: P1)

A batch was assembled, its payload stored and its render requested, and the outcome never came back —
the event was lost, the pod died between the request and the mark, or systemdocgenerator simply never
answered. Today that batch sits in flight and its registers keep its stamp, so the next run passes
its court centre day over and the court centre gets no document, night after night, until a person
notices. After this change the next run's first act is to give up on it: the batch is failed under a
bounded reason that says exactly that, its registers are released, and the assembler puts them in
tonight's batch for the same court centre and register date.

**Why this priority**: This is the reason the increment exists. Everything else in it is the removal
of the machinery this replaces.

**Independent Test**: Seed a batch in GENERATING whose render was requested the previous evening, with
two registers stamped into it; run the nightly run; confirm the batch is FAILED under the new reason,
its two registers are unstamped and active, and tonight's assembly contains a new batch for the same
court centre and register date holding those two registers.

**Acceptance Scenarios**:

1. **Given** a batch in GENERATING whose render was requested longer ago than the minimum age,
   **When** the run starts, **Then** it is FAILED with the new bounded reason before any assembly
   happens, its registers are released, and it names no completion mechanism, because nobody outside
   this service reported anything about it.
2. **Given** a batch in PENDING that minted a payload longer ago than the minimum age and was never
   recorded as requested, **When** the run starts, **Then** it is FAILED under the same reason and
   released the same way: whether the request reached the renderer is a question this service can no
   longer ask, and both endings are "it did not complete before the next run".
3. **Given** a released batch's registers and a court centre day the assembler would otherwise have
   passed over because a batch of that key was in flight, **When** assembly runs in the same run,
   **Then** the key is no longer in flight, the registers are assembled into a new batch, and the
   court centre receives its document that night.
4. **Given** several stale batches, **When** the run starts, **Then** each is its own attempt: one
   that cannot be failed leaves the others to be failed anyway, oldest first.
5. **Given** a stale batch and a register that has since been re-shared for the same hearing,
   **When** the stale batch's registers are released, **Then** supersession decides which register is
   active, exactly as it does for the two existing releasing reasons — a release never makes two
   registers active for one hearing.
6. **Given** no stale batch at all, **When** the run starts, **Then** the pass does nothing, writes
   no row, and the run proceeds to assembly as it does today.

---

### User Story 2 - A render requested minutes ago is left alone (Priority: P1)

An operator asked for a court centre's register to be generated by hand at 17:52, and the nightly run
starts at 18:00. That batch is eight minutes into a render that is very probably going to succeed.
The run must not fail it, must not release its registers, and must not assemble a second batch for
the same court centre day — which is the double document and the double e-mail this service's
in-flight rule exists to prevent.

**Why this priority**: Without the minimum age the pass is not a safety net, it is a nightly
destruction of whatever the evening was doing. The rule is only safe because of the age.

**Independent Test**: Seed a batch in GENERATING requested ten minutes before the run against a
thirty-minute minimum age; run the nightly run; confirm the batch is untouched, its registers are
still stamped, and the assembler passed its court centre day over as it does today.

**Acceptance Scenarios**:

1. **Given** a batch in flight for less than the minimum age, **When** the run starts, **Then** it is
   not failed, its registers are not released, and nothing about it is counted.
2. **Given** that same batch, **When** assembly runs, **Then** its court centre day is deferred by the
   existing in-flight rule and appears in the run report's deferred counts, exactly as today.
3. **Given** a batch exactly at the minimum age, **When** the run starts, **Then** the boundary is
   decided once and stated: a batch is stale when it has been in flight for **at least** the minimum
   age, and the two cutoffs are computed once per pass, from the run's own clock, so that every
   batch in one pass is judged against the same moment (amended 2026-09-21: the clause read "the
   store's own clock rather than a pod's", which the plan, the statement and the boundary test never
   implemented — the cutoffs are instants the pass passes in, not a `now()` the statement takes).
4. **Given** a batch in GENERATED that has a document but has not been notified, **When** the run
   starts, **Then** it is not touched by this pass at any age: it holds a document somebody is owed
   e-mails about, and failing it would throw that document away.
5. **Given** a batch an operator asked for by hand at 17:25, whose renders are still being requested
   when the schedule fires at 18:00, **When** the run starts, **Then** it is not stale — an
   operator's batch is given the longer of the minimum age and the run's own lock duration — so the
   manual run's renders are not orphaned and its own record of having made them is not refused.
6. **Given** a batch that turns GENERATED in the instant between the run starting and the release
   being written, **When** the release is written, **Then** it does not match and is not changed, the
   run counts it as a batch it did not release, and the night goes on to assemble.

---

### User Story 3 - A Youth Offending Team is never told twice (Priority: P1)

The batch this service gave up on at 18:00 may still be alive inside systemdocgenerator, and its
`document-available` may arrive at 18:05 — after the registers have been released and re-batched.
That late outcome must move nothing. It is what stops the same court centre's register going out
twice: once from the batch the outcome belongs to and once from the batch tonight's run made.

**Why this priority**: It is the correctness guarantee the whole change rests on, and the behaviour is
**unchanged** — a late or duplicate outcome for a batch already FAILED has always moved nothing and
been counted. What is new is that this increment creates a new way to reach that state, so the rule
is pinned for the new reason by name.

**Independent Test**: Fail a batch under the new reason, then deliver a `document-available` for it;
confirm the batch is unchanged, no notification is sent, and the drop is counted under its existing
bounded reason.

**Acceptance Scenarios**:

1. **Given** a batch failed under the new reason, **When** a `document-available` for it arrives on
   the topic, **Then** nothing moves, no e-mail is sent, and the drop is counted under a bounded
   reason.
2. **Given** that same batch, **When** a `generation-failed` for it arrives, **Then** nothing moves,
   the batch keeps the reason this service gave it, and the drop is counted.
3. **Given** a batch failed under the new reason and re-batched, **When** the new batch completes
   normally, **Then** the court centre's Youth Offending Teams receive exactly one e-mail for that
   register date from that run.

---

### User Story 4 - The night says what it released, and asks nothing between runs (Priority: P2)

The run report is how support reads a night. It must say how many batches this run gave up on and
released, where it used to say how many outcomes the reconciler had to fetch. Between runs, nothing
queries systemdocgenerator at all, and no timer or lock exists for a reconciliation that no longer
happens. The readings that describe how long the oldest in-flight batch has been waiting must survive
the timer that used to take them, because a reading taken once a day is not a reading.

**Why this priority**: The removal is most of the work, and a removal that quietly took three
continuous measurements with it would be a regression nobody asked for.

**Independent Test**: Run a night with two stale batches and one fresh one; confirm the run line
carries the released count and no reconciled count; confirm no request is made to systemdocgenerator
other than the render requests the run itself makes; confirm the in-flight age readings are still
refreshed between runs.

**Acceptance Scenarios**:

1. **Given** a run that released two stale batches, **When** it writes its line, **Then** the line
   carries a released count of two and carries no reconciled count.
2. **Given** a run that released none, **When** it writes its line, **Then** the released count is
   zero — a night that released nothing and a night that did not report are different lines.
3. **Given** a run the flag stopped, **When** it writes its line, **Then** the release pass did not
   run at all: a run that may not generate may not decide a batch it is not allowed to re-render has
   failed, and the released count is zero.
4. **Given** the service running between two nightly runs, **When** nothing is scheduled to happen,
   **Then** systemdocgenerator receives no request of any kind, and the service holds no lock and no
   timer for reconciliation.
5. **Given** a batch that has been in flight for hours, **When** the in-flight age readings are taken,
   **Then** they report its age within one refresh interval, in every instance that is not a command
   and carries the generation half, as they did when the retired timer took them.

---

### User Story 5 - The minimum age is a setting with a safe default (Priority: P2)

An operator can change what "too long in flight" means, per environment, without a release, and the
service refuses at start-up a value that cannot work.

**Why this priority**: Thirty minutes is a first guess. The pass is destructive — it fails a batch and
re-renders a day — so the guess must be correctable in production, and a zero or negative value must
be refused rather than discovered at 18:00 by a run that fails every batch it can see.

**Independent Test**: Start the service with nothing set and confirm the documented default; start it
with zero and with a negative value and confirm each refusal names the setting.

**Acceptance Scenarios**:

1. **Given** no value is provided, **When** the service starts, **Then** the minimum age is thirty
   minutes.
2. **Given** a zero or negative value, **When** the service starts, **Then** start-up is refused and
   the message names the setting.
3. **Given** a changed value in one environment, **When** that environment's next run starts,
   **Then** it uses the changed value and no other environment is affected.

---

### Edge Cases

- **A batch that is stale and whose outcome arrives during the run.** Both orders are safe and
  neither may cost the night. *Outcome first*: the batch is GENERATED, so the pass's own staleness
  predicate no longer matches it and nothing is written for it — no read of a state that has since
  moved, and therefore no refused write. *Pass first*: the outcome's mark is refused by the state
  machine, the listener rethrows, the broker redelivers, and the sink then reads a FAILED batch and
  drops the outcome under its bounded reason. The second is self-healing, and the redelivery is
  counted under the late-outcome reason rather than as an unknown correlation.
- **The release cannot be written.** A batch that ceased to be stale is a batch the operation did not
  change, which is a number and not an error. A store that cannot be reached at all fails the run the
  way any other unreachable store does — reported on the run line and rethrown, never logged and
  continued. What must never happen is one batch's refusal ending a night: the whole reason the
  operation is fenced rather than looped is that a refused mark in a loop would throw out of the run
  and nothing would be assembled that night.
- **A batch an operator asked for by hand while the run is starting.** A manual generation holds no
  run lock and has the whole requesting deadline to ask for its renders. Its batch is therefore given
  the longer grace, so the schedule cannot fail a render an operator is still making.
- **A batch left PENDING with no payload id at all.** The retired reads never saw it and nothing else
  in the flow revisited it, so its court centre day was deferred at every run for ever. The pass sees
  it, because staleness is state and age and not progress.
- **A night the flag says the legacy is live.** Nothing is released, and in-flight batches stay in
  flight until the flag returns. Accepted, and stated as an accepted cost rather than designed
  around.
- **A run started by hand from the operations surface.** The pass belongs to the scheduled run's
  sequence. Whether the on-demand generation command runs it too is answered in the Assumptions
  below, because it decides whether an operator regenerating one court centre can disturb another's
  in-flight batch.
- **A stale batch whose registers a later re-share has superseded.** The existing supersession order
  decides, and it already covers exactly this case for the two reasons that release today.
- **A stale batch whose registers a re-share has been overtaken by** — a share of the hearing
  delivered *behind* the register the batch already holds, which a broker that redelivers produces.
  It is recorded active and unbatched, because a batched register is not the recorder's to supersede,
  so it holds the day's active-register key against the release. **The same supersession order
  decides it, read the other way [gate 4]**: the register coming back is the later of the two, so the
  release supersedes the overtaken share against it in the same statement and the key keeps exactly
  one active row. Without that the batch is refused by the key on every attempt and reported
  contended by every run for ever, because no fresh snapshot removes a row committed before the
  statement began and the recorder will not supersede a batched register on its behalf.
- **Two pods.** Only one runs the nightly run; the pass is inside it and inherits its lock. The
  in-flight age readings are per pod, as every gauge in this service is, and an alert aggregates them
  with `max()`.
- **The retired reason on rows already written.** Rows written before this change may carry the retired
  timeout reason and the retired completion mechanism. Every read of those rows must keep working:
  the report reads FAILED batches every morning, and a read that could not name a value the row holds
  would fail on the one row support most needs to see.
- **The escape hatch that depended on the query.** One deployment shape existed in which the broker
  was not subscribed to and outcomes were learned only by the query. With the query gone, that shape
  would learn no outcome at all and would re-render every batch every night. It is addressed in the
  Requirements rather than left to mean something it no longer can.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The scheduled nightly run MUST, after reading the cutover flag and before assembling
  anything, fail every batch that is still awaiting its render and has been doing so for at least the
  configured minimum age, and MUST release that batch's registers so that the same run's assembly
  includes them.
- **FR-002**: "Still awaiting its render" MUST mean a batch in PENDING or in GENERATING and nothing
  else. A batch that holds a document MUST never be failed by this pass, at any age.
- **FR-003**: The failure MUST carry a new bounded reason of this service's own, distinct from every
  existing reason, meaning "this batch had not completed by the time the next run began". It MUST
  name no completion mechanism, because nobody outside this service reported anything about it, and
  it MUST be one of the reasons that release a batch's registers.
- **FR-003a**: The failure and the release of **one batch** MUST be **one atomic operation, fenced on
  the staleness predicate itself**. No sequence of a read, then a mark, then a release is acceptable:
  a crash or a concurrent outcome between any two of those leaves registers stamped to a terminal
  batch, where no later run can see them. A batch that ceased to be stale between the operation being
  asked for and the row being written MUST simply not be changed, and that MUST be reported as a batch
  the pass did not release rather than as an error. **No single batch's outcome may end the run**:
  whatever happens to one batch, the run goes on to assemble. That has two consequences the
  implementation MUST meet, both of them ways of ending the run that no care in the caller could undo:
  - **Contention is isolated per batch.** The batches are read once by the staleness predicate and
    each is then failed and released by a statement of its own, in a transaction of its own, with a
    bounded retry of its own on the day's active-register key. One transaction over every stale batch
    is atomic in the wrong unit: a refusal met on one court centre's registers would roll back every
    other court centre's release with it.
  - **Exhaustion is reported, never thrown.** A batch whose every attempt met that refusal MUST be
    left exactly as it was found, named in the operation's answer under a bounded account of its own,
    counted by the pass's line and its counter, and the operation MUST go on to the batches after it
    and return normally. A contended batch is stale still and untouched, so the next run reaches it
    again; meanwhile the 07:00 report names its court centre day as a late batch (FR-019's kinds),
    which is the surface support already watches.
- **FR-004**: A batch in flight for less than the minimum age MUST be left exactly as it is, and the
  existing rule that defers a court centre day whose batch is in flight MUST continue to apply to it
  unchanged.
- **FR-005**: A run the flag stopped MUST NOT run the pass. A run that may not generate may not decide
  that a batch it would not be allowed to re-render has failed.
- **FR-006**: The service MUST NOT query systemdocgenerator about a payload's fate, on any schedule or
  at any point in a run. The only calls this service makes to systemdocgenerator are the render
  requests the run itself makes.
- **FR-007**: The service MUST NOT carry a scheduled reconciliation, nor a lock for one. After this
  change the generation half carries exactly one schedule: the nightly run.
- **FR-008**: An outcome that arrives for a batch this service has already ended MUST move nothing,
  MUST send no e-mail, and MUST be **counted under a bounded reason** — including for the new reason,
  and including for an outcome that arrives after the registers have been released and re-batched.
  Today that drop is logged and counted nowhere; this increment adds the bounded reason, because the
  drop is what stops a Youth Offending Team being told twice and "it is in the log index" is not an
  alerting surface. A redelivery of such an outcome MUST be counted under that same reason and never
  as an unknown correlation.
- **FR-009**: The run report MUST state how many **batches** the run released, how many
  **registers** came back with them and how many batches the pass **could not** give back, in place
  of the count of outcomes it used to fetch, and MUST state zero rather than nothing where any of
  the three is none. On the run's line they are `released_batches=`, `released_registers=` and
  `contended=`. None of the three is a third sum: the registers counted by `released_registers` are
  re-batched by the same run and are therefore already inside that run's row totals, and the
  contended batches are in no total at all because nothing happened to them. For the same reason the
  registers counted are those still the day's to render: a register the estate re-shared while the
  pass was giving it back is superseded rather than handed back, nothing will re-batch it, and
  counting it would put a register in the run's diagnostic that is in none of its totals.
  `contended=` MUST carry the same number as `yotresultsdistribution_generation_contended_total` moved by
  that run, so the line and the counter say one thing. **The third number is the coordinator's
  decision of 2026-09-20**, taken at Phase 4 and recorded in `plan.md` and `data-model.md`: the
  batches the pass could not give back are work the night left undone — stale still and untouched,
  so the next run reaches them again and the 07:00 report names their court centre days every
  morning meanwhile (FR-003a, FR-019) — and a run line carrying only the two released numbers would
  describe as complete a night that had left a court centre without its document, which is the
  silence this service exists to end.
- **FR-010**: The minimum age MUST be a configuration setting with a documented default of thirty
  minutes, and start-up MUST be refused, naming the setting, for a zero or negative value.
- **FR-011**: The readings that say how long the oldest batch awaiting a render, the oldest batch that
  never reached the renderer and the oldest batch holding an unnotified document have been waiting MUST
  continue to be refreshed between nightly runs, on a configurable interval, in every instance that is
  not a command and carries the generation half, and MUST settle nothing and hold no lock.
  (Amended 2026-09-21 at the increment gate, to the design `research.md` §"the sweep's reach" and
  `plan.md` state and `BatchSweepConfig` implements: the readings are of batches, so the instances
  that take them are the instances that have the generation half. The earlier wording, "every
  instance that is not a command", claimed a reach one setting narrower than the one that was
  built.)
- **FR-012**: The bounded vocabularies MUST be left describing only mechanisms that exist. The
  timeout reason and the completion mechanism that named the retired pass MUST be removed from the
  enums **and** from the schema's bounded lists, so that no vocabulary outlives the thing it names.
  **This MUST happen in a second forward migration, after the retired pass itself is deleted, and
  not in the migration that admits the new reason.** The reason is not a preference: the retired
  pass is the only writer of both values, so while it still exists the schema must go on admitting
  them, and the new reason must be admitted before that — the first write of it comes earlier than
  the deletion. One migration therefore admits and widens only; a second narrows once the writer is
  gone. Nothing is deployed, so no row anyone must be able to read carries either value; a store that
  does hold one is cleaned before the **second** migration, which is the one operational consequence
  and is recorded as such.
- **FR-013**: The deployment shape that learned outcomes only by the query MUST be removed rather than
  left to mean "learn no outcome at all". A deployment with the generation half enabled MUST learn its
  outcomes from the public-event topic, and MUST be refused at start-up if it is not configured to.
- **FR-014**: The setting that today names the reconciler's grace period MUST be renamed to name what
  it now decides. Every other setting that borrowed its value MUST either be re-pointed at the renamed
  setting or given a value of its own, and the choice MUST be stated in the plan rather than left to
  the reader of two records.
- **FR-015**: Every line this pass writes MUST carry the run's own correlation identifier, MUST name a
  batch by identifier only, and MUST carry no defendant or recipient personal data and no free text
  another system wrote.
- **FR-016**: The repository's own documentation of the flow — the two-leg diagram, the batch state
  machine, the rule about what the retired pass was allowed to invent, the consumed-contracts table,
  the README's account of the generation half, and the agent scope paragraphs — MUST be updated in the
  same increment, so that no document in this repository describes a query that no longer exists.
- **FR-017**: A batch an operator asked for by hand MUST be given the whole of the requesting
  deadline before the pass may touch it: it is stale only after the **longer** of the minimum age and
  the nightly run's own lock duration. A manual generation holds no run lock and may legitimately
  still be asking for renders when the schedule fires; failing its batch would orphan a render it is
  making and refuse its own record of having made it.
- **FR-018**: On a night the cutover flag says the legacy is live, the pass MUST NOT run, and batches
  left in flight from an earlier night MUST stay in flight. This is accepted rather than worked
  around: a service that may not generate may not decide that a batch it would not be allowed to
  re-render has failed. The 07:00 report's late-batch entry is the signal in the meantime, and the
  first night the flag is ON again releases them.
- **FR-019**: The 07:00 exception report MUST report a batch released by this pass under a kind of
  its own, informational, and **not** among the batches that failed. Its registers were re-rendered
  the same night, so reporting it as a failure sends support after something already put right.
- **FR-020**: The pass MUST cover the batch the retired pass could not: one left PENDING with **no**
  payload id at all, which the retired reads excluded and which therefore sat in flight for ever,
  deferring its court centre day at every subsequent run. Staleness is decided by state and age, not
  by how far a batch got.

### Key Entities *(include if data involved)*

- **Stale batch**: A batch in PENDING or GENERATING whose in-flight stamp is at least the minimum age
  old — or, where the batch was asked for by hand rather than by the schedule, at least the longer of
  the minimum age and the run's own lock duration old.
- **Minimum age**: How long a batch the schedule made may be in flight before a run gives up on it.
  One setting, thirty minutes by default.
- **Release**: Failing a stale batch under the new bounded reason and giving its registers back, as
  one atomic operation fenced on the staleness rule, so the batch row remains the audit of what
  happened and the registers become assemblable.
- **Released counts**: How many batches one run released and how many registers came back with them,
  carried on the run report as two numbers and counted.
- **Ignored outcome**: An outcome the flow acknowledges and does not apply, carrying a bounded reason
  that says which kind of not-applied it was.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A batch whose render outcome is lost costs its court centre **one night** rather than
  every night until a person intervenes: the run after the loss releases it and produces the document.
- **SC-002**: Across a night with a mix of stale and fresh batches, every batch older than the minimum
  age is released and no batch younger than it is touched: zero false releases over a seeded set
  covering both sides of the boundary.
- **SC-003**: No Youth Offending Team receives two e-mails for one court centre and register date as a
  result of a release, verified by delivering a late outcome for a released batch whose registers have
  been re-batched and completed.
- **SC-004**: The service makes **zero** requests to systemdocgenerator between two nightly runs,
  verified over a window covering at least one former reconciliation interval.
- **SC-005**: The run line answers "how many did this night give up on" for every run, including the
  nights that gave up on none and the nights the flag stopped.
- **SC-006**: The in-flight age readings are no less current after this change than before it: a batch
  stuck for an hour is visible on them within one refresh interval, not at the next nightly run.
- **SC-007**: Changing the minimum age takes effect on the next run in that environment alone, with no
  release; a non-positive value stops the service starting and the refusal names the setting.
- **SC-008**: No document in this repository refers to a systemdocgenerator query, a grace period or a
  scheduled reconciliation after this increment, and the full quality gates — including the coverage
  ratchet and the differential and consolidation audits — are green.
- **SC-009**: **No register is ever stranded by the pass.** Over a concurrency test that races the
  release against both a render acceptance and a document arrival, in both orders and repeatedly,
  every register ends either stamped to exactly one live batch or active and unbatched — never
  stamped to a terminal batch — and exactly one notification aggregate exists per court centre and
  register date.
- **SC-010**: Every outcome the flow drops moves a counter with a bounded reason, verified by
  delivering a `document-available` and a `generation-failed` for a released batch and reading the
  counter before and after. "It is in the log index" is not an alerting surface, and this is the one
  drop the guarantee against a double e-mail rests on.

## Out of Scope (this increment)

- Any change to what a batch does once its outcome **is** learned: the event path, the notification
  leg, the supersession rule and the register document are untouched.
- Any change to the intake half or to the cutover lever. The 07:00 exception report is touched in
  exactly two places and no others: it gains the informational kind of FR-019, and its late-batch
  threshold stops borrowing the renamed setting and keeps its present value. Its schedule, its
  window, its sinks and its other four kinds are untouched, and it remains gated by the flag nowhere.
- Any retry of a released batch's render inside the same run beyond the ordinary assembly the release
  makes possible. A released batch is re-rendered because its registers are assemblable again, not
  because anything re-requests the old batch.
- Deleting historical rows, or migrating rows that carry a retired value to a new one. They are
  history and are read as such.
- Any REST or HTTP surface. This service has none and gains none here.
- The prison court register, SJP, and the legacy repositories.

## Outside this repository (flag, do not do here)

- **The Confluence design document** (*Court Register Service*, CRA space) sections describing the
  grace-period reconciler and the query API: the design owner's own write-up.
- **The Gliffy diagram**: the dashed service-to-systemdocgenerator query arrow and its step label.
- **STE and environment values**: *checked, and probably empty.* Both keys are literals in
  `application.yaml` with no `${...}` placeholder, so this repository defines no environment variable
  for either. The only thing to do outside is to confirm that no deployment branch sets a raw
  `yotresultsdistribution.generation.grace-period` or `yotresultsdistribution.generation.completion` override; if none
  does — which is what the absence of a placeholder suggests — there is nothing to change.
- Nothing in this increment requires a change by another team: no consumed contract changes, and a
  contract this service simply stops calling is not a contract change.

## Assumptions

- **The minimum age is thirty minutes and the boundary is inclusive.** A batch is stale at exactly
  thirty minutes. Thirty minutes is comfortably longer than a render of this size takes and
  comfortably shorter than the gap between runs, and an inclusive boundary means the rule can be
  stated in one clause rather than two.
- **The age is measured from the stamp each state already carries**: from when the render was
  requested for a GENERATING batch, and from when the batch was assembled for a PENDING one. Both are
  columns the store already keeps and the retired pass already read.
- **The release is one fenced statement per batch, not a loop of two statements** (narrowed at
  review gate 3). The new reason joins the reasons that release a batch's registers as part of failing
  it, and one batch's failure and release are a single statement whose own predicate is the staleness
  rule. Two statements leave a window in which a batch is FAILED and its registers are still stamped —
  invisible to every later run, because unbatched means `batch_id IS NULL` — and a read-then-mark loop
  leaves a refused mark able to throw out of the run. What the pass repeats is the fenced statement
  itself, once per stale batch and in a transaction of its own, so that the refusal one court centre's
  registers can raise is not also the other court centres' ending (FR-003a).
- **`released` is two numbers, batches and registers.** Neither answers the other's question, and the
  run line already keeps both accounts of a night. They are a diagnostic beside those accounts and
  not a third sum: the registers counted are re-batched by the same run and are already inside its
  row totals.
- **The on-demand generation command does not run the pass.** An operator regenerating one court centre
  must not, as a side effect, give up on another court centre's in-flight batch. The pass belongs to
  the scheduled run. If an operator needs a stale batch released, the existing per-batch release the
  operations surface already offers is the supported way, and it is unchanged.
- **The retired timeout reason and the retired completion mechanism are removed outright**, from the
  enums and from the schema's bounded lists — in a **second** forward migration, after the pass that
  writes them is deleted. Nothing is deployed, so no row anyone must be able to read carries either.
  **The one operational consequence**: a CHECK constraint cannot be narrowed on a table that still holds a
  violating row, so the **second** migration refuses to apply to any store — a developer's local
  volume, a seeded container, a replayed SIT snapshot — that still holds a batch failed under the
  timeout reason or completed by the retired mechanism. (The first migration only widens, and
  refuses on nothing.) Such a store is cleaned or recreated before the
  migration runs, and the quickstart says so. This is cheap now and stops being cheap the first
  evening the service runs in an environment somebody cares about, which is the reason the decision
  is taken in this increment rather than deferred.
- **The escape hatch that learned outcomes only by the query is removed outright** rather than left as
  a setting with one legal value. After this change it would mean "learn no outcome, fail every batch
  at the next run, and render every day twice" — strictly worse than refusing to start. A deployment
  with the generation half enabled must be configured for the public-event topic, which is what every
  deployment of this service is configured for today.
- **The report's own rendering limit stops borrowing from the generation half and gets a value of its
  own, keeping today's ten minutes.** The two durations now answer different questions — one is "when
  should support be told a render is late", the other is "when does a run give up and re-batch" — and
  a borrowed value would silently change the 07:00 report's behaviour as a side effect of this
  increment. Ten minutes preserves the report exactly as it is.
- **The in-flight age readings keep a refresh of their own.** They were taken by the retired timer; they
  move to a refresh that holds no lock and settles nothing, in the shape this service already uses for
  its intake gauges. This is the one addition in an otherwise subtractive increment, and it exists only
  so that the removal does not cost three continuous measurements.
- **The `P2` defect-fix row's pinning-test cell is re-pointed, and nothing else about the row changes.**
  P2 promises that a failed render is never silently dropped; the event half of that promise is
  untouched, and the half that named the retired pass's timeout test is re-pointed at the test that
  holds the equivalent promise afterwards — that a batch nothing was ever learned about reaches an
  explicit recorded failure rather than sitting in flight. No new row is added and no row's claim
  changes.
