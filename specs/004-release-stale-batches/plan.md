# Implementation Plan: Release stale in-flight batches before batching

**Branch**: `004-release-stale-batches` | **Date**: 2026-09-19 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/004-release-stale-batches/spec.md`
**Revised**: 2026-09-19 after two independent design reviews; the changes they forced are marked
**[review]** throughout and summarised in "What the reviews changed" below.

## Summary

A batch that is still waiting for its render when the next nightly run begins, and has been waiting
long enough, is failed `BatchFailureReason.NOT_COMPLETED_BY_NEXT_RUN` and its registers are released,
so the run's own assembly puts them in a batch tonight. The pass is the first thing the run does
after the flag gate and before `store.activeUnbatched()`.

**The release is one fenced store operation, atomic per batch [review, revised at gate 3].**
`RegisterStore.failAndReleaseStale(scheduledCutoff, manualCutoff)` reads the staleness predicate once
into a list that decides nothing, then issues **one statement per batch**, narrowed by batch id and in
a transaction of its own, whose `WHERE` clause *is* the staleness rule: it fails the batch under the
new reason and releases its registers in the same statement, and the operation answers with the
batches it actually changed and, beside them, the batches it could not change. There is no read
followed by a mark: a batch that stopped being stale in between simply does not match, so there is no
refused transition to throw out of the run, and there is no window in which a batch is FAILED while
its registers are still stamped to it — which would be a **lost register**, invisible to every later
run because `activeUnbatched` means `batch_id IS NULL`. The unit is one batch because a refusal met on
one court centre's registers must not roll back another's release, and a batch whose every attempt met
that refusal is **reported on `StaleReleaseOutcome.contended()`, never thrown**: no single batch's
outcome may end the run (FR-003a).

Everything else is **removal**. `GenerationReconciler` goes, and with it its `@Scheduled`/
`@SchedulerLock` timer, its lock, its cadence read from the grace period, the end-of-run
`tally.chased(reconciler.reconcile())`, and the entire systemdocgenerator query path:
`DocumentRenderer.query`, `SystemDocGeneratorClient.query` (`GET document/{payloadFileId}` and its
answer parsing), `StubDocumentRenderer.query` and `domain/DocumentStatus`. The
`yotresultsdistribution.generation.completion` setting goes with them, because `poll-only` named a way of
learning an outcome that no longer exists. `BatchFailureReason.GENERATION_TIMED_OUT` and
`CompletedBy.RECONCILER` go too, from the enums **and** from the schema's bounded lists
**[review, revised]** — nothing is deployed, so no row anybody must read carries them.

Two small things replace what the removal would otherwise take with it:

- **`batch/StaleBatchReleaser`** — the pass. Two cutoffs from the clock and the settings, one call to
  `failAndReleaseStale`, one line per released batch, **three** counters and three numbers back to
  the run — the two released ones the line carries, and the batches the store could not release,
  which FR-003a asks be counted and which the design rules ask of any path that leaves something
  undone. No schedule, no lock, no renderer, no HTTP client.
- **`batch/BatchAgeSweep`** — the three readings the retired timer took on its way past
  (`yotresultsdistribution_oldest_generating_age`, `_pending_age`, `_generated_age`) plus the "this batch holds
  a document nobody was told about" WARN, on its own lockless fixed delay in every non-command JVM
  that has the generation half. A Micrometer gauge never decays, so leaving these to a once-a-night
  pass would freeze three readings at whatever the last run saw; the GENERATED-but-unnotified one is
  the only in-hours signal that a document is owed its e-mails.

Three further consequences the reviews surfaced, each now a requirement:

- **A late outcome for an ended batch is not counted today [review].** `DocumentOutcomeSinkImpl`
  logs the refused transition at WARN and moves no counter; the ignored counter fires for a foreign
  source, an unknown correlation, a payload mismatch and three envelope faults, and
  `late-acceptance-ignored` / `late-failure-ignored` belong to the *notifications* counter and are a
  different thing. This increment adds the bounded reason `terminal-batch`, because after 004 that
  drop is the guarantee against a double e-mail (FR-008, SC-010).
- **An operator's batch gets the longer grace [review].** A manual generation holds no run lock and
  has the whole sixty-minute requesting deadline; a batch it assembled at 17:25 is over thirty
  minutes old at 18:00. A `system_generated = false` batch is therefore stale only after
  `max(staleAfter, lockAtMostFor)` (FR-017).
- **The 07:00 report reports a released batch under its own kind [review].** `ExceptionKind` gains
  `BATCH_RELEASED`, informational, because the registers of a released batch were re-rendered the
  same night and reporting them beside real failures sends support after something already fixed
  (FR-019).

The run report's `reconciled` becomes **three** numbers - `released_batches`, `released_registers`
and, by the coordinator's decision of 2026-09-20 taken at Phase 4, `contended` for the batches the
pass could not give back, which are a night's undone work and must not be silent on the line that
describes the night - and `yotresultsdistribution_generation_reconciled_total` is retired in favour of
`yotresultsdistribution_generation_released_batches_total` and `_released_registers_total`, beside
`yotresultsdistribution_generation_contended_total` for the batches a run could not give back.
`yotresultsdistribution.generation.grace-period` becomes `yotresultsdistribution.generation.stale-after` (default `10m`
→ `30m`), and `yotresultsdistribution.report.batch-generated-within` stops resolving from it and takes its own
`@DefaultValue("10m")`.

**Two** additive migrations, because the admission and the removals cannot share one:
`V6__admit_stale_release_reason.sql` widens the failure-reason vocabulary by the new value and
refuses on nothing, and `V7__retire_reconciler_vocabulary.sql` — landing immediately after the
reconciler is deleted, since until then it is the only writer of the retired values — narrows all
three constraints. See data-model.md.

Nothing about the register document, the inbound message, the file service, notificationnotify or the
App Configuration flag changes. One consumed contract stops being *called* — systemdocgenerator's
query endpoint — which is not a contract change.

Design authority: the design owner's decision of 2026-09-19 (spec Context), as revised by the two
design reviews of the same day (spec Clarifications, second session).

## What the reviews changed

| # | Finding | Effect on this plan |
|---|---|---|
| 1 | `failure_reason` is CHECK-constrained to six values in `V2`; the new reason would be rejected by Postgres | `V6` **and** `V7` (the split found in implementation, below), and `SchemaMigrationV2IT` extended to hold the enum and the constraint to each other in both directions after each |
| 2 | `markFailed` + `releaseFailed` is two operations with a read between them; a crash or a race strands registers on a terminal batch | The pass becomes `failAndReleaseStale`: one fenced statement per batch, each in its own transaction and with its own bounded retry (narrowed at gate 3); concurrent `*IT`s in both race orders; SC-009 |
| 3 | The refused-transition drop is logged, not counted | New bounded reason `terminal-batch` on `yotresultsdistribution_public_events_ignored_total`; FR-008, SC-010 |
| 4 | The retirement's blast radius is wider than the three classes named | Full file inventory below, including the three gauges, the counter, `RunReport`, `RunCorrelation`'s nesting javadoc and sixteen suites |
| 5 | The report's late-batch threshold derives from the grace period | Its own setting at the previous derived value |
| 6 → revised | Keep the retired vocabulary as history / **remove it** | Removed from the enums and the schema; the migration's one operational caveat recorded |
| A | A refused mark inside the run ends the whole night | Fenced statement, and an explicit "no single batch may end the run" requirement |
| B | `poll-only` would re-render every batch nightly for ever | The setting is deleted |
| C | Three gauges lose their only publisher and freeze | `BatchAgeSweep` |
| D | A manual generation spanning 18:00 would have its render orphaned | The longer grace for `system_generated = false` |
| E | A released batch would be reported as a failure at 07:00 | `ExceptionKind.BATCH_RELEASED` |
| F | The pass is behind the flag gate, so a flag-OFF night releases nothing | Accepted and stated (FR-018) |
| G | Further files; and the pass closes the gap the retired reads left for PENDING batches with no payload id | Inventory below; FR-020 and a pinning test |
| H | Column is `assembled_at`; `grace-period` has no env placeholder; `attributionOf` takes `null` | Reflected throughout; the outside-repo STE item softened to "check, probably nothing" |

**One review claim did not check out and is recorded as such**: `docker/sdg-echo/sdg-echo.py` does
**not** implement the query endpoint — it only watches WireMock's journal and publishes
`document-available` onto the topic. The query stub is a WireMock mapping, and that is what is
deleted. `sdg-echo.py` is untouched.

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.1 (Gradle wrapper; unchanged from 001–003).

**Primary Dependencies**: **none added, and one use removed.** ShedLock loses a lock (the run keeps
its own); `@Scheduled` loses a trigger and gains one; `RestClient` loses a call. Micrometer,
`JdbcClient` and Jackson are untouched.

**Storage**: PostgreSQL 16. **Two** additive, forward-only migrations, adding no column, no table
and no index. `V6__admit_stale_release_reason.sql` widens `register_batch_failure_reason_chk` by the
new reason, leaves the two attribution constraints alone, and refuses on nothing.
`V7__retire_reconciler_vocabulary.sql`, after the reconciler is deleted, narrows all three:
`register_batch_failure_reason_chk` (− `GENERATION_TIMED_OUT`), `register_batch_completed_by_chk`
(− `RECONCILER`) and `register_batch_completed_by_shape_chk` (its attributed list to
`GENERATION_FAILED` alone). V7 is the one that refuses on a pre-004 row. The new statement,
`failAndReleaseStale`, adds no index either: it filters on `status` and the two stamps, which the
existing in-flight reads already filter on.

**Testing**: as 002 and 003, plus **two concurrency `*IT`s** that are new in kind for this repository
and are the evidence for SC-009: one races `markRequested` against the pass and one races
`markGenerated` against it, each in both winner orders, each asserting no register is stamped to a
terminal batch and exactly one notification aggregate exists per key. Otherwise: `StaleBatchReleaserTest`
and `BatchAgeSweepTest` replace `GenerationReconcilerTest`; `RegisterGenerationJobTest`,
`DocumentOutcomeSinkTest`, `RegisterStoreIT`, `SchemaMigrationV2IT`, `RegisterBatchRepositoryIT`,
`RegisterBatchReportReadsIT`, `ExceptionReportServiceTest`, `ReportExceptionsCliTest`,
`BatchFailureReasonTest`, `BatchStateTest`, `GenerationMetricsTest`, `ConfigurationValidationTest`,
`ReportPropertiesTest`, `GenerationWiringContextTest`, `CliModeConfigTest`,
`ReportSchedulingConfigTest`, `PublicEventsHealthIndicatorTest`, `DocumentEventListenerTest`/`IT`,
`TelemetryPrivacyTest`, `SystemDocGeneratorClientTest`, `StubGenerationAdaptersTest`,
`GenerationEndToEndIT`, `GenerationFailureEndToEndIT` and the three `support/` classes all follow.

**Target Platform**: AKS, port 4550 (local 8082). After this change the generation half carries
**one** ShedLock lock (`register-generation`) and **one** cron, plus one lockless fixed delay on a
scheduler of its own: four `TaskScheduler` beans where there were three, and four `@Scheduled`
methods where there were four — the reconciliation timer out, the batch-age sweep in.

**Performance Goals**: the pass is **one statement per stale batch**, and on an ordinary night there
are none, at the front of a run whose budget is sixty minutes. SC-004 (zero calls to systemdocgenerator between runs) is met by construction once the query
path is deleted: no code remains that could make one.

**Constraints**: constitution v3.2.0 — ids before calls (the pass mints none); bounded reason codes
and bounded metric labels; `runId` on every line of a scheduled run (Principle VII), which the
releaser gets by **adopting** the run's ambient correlation and the sweep by **opening** its own; no
PII; no throwable this service did not write attached to a line; Flyway additive and forward-only; no
AI attribution; TDD red-run convention, including for deletion.

**Scale/Scope**: five user stories, twenty functional requirements, ten success criteria.

### Configuration (this increment)

| Property | Before | After | Note |
|---|---|---|---|
| `yotresultsdistribution.generation.grace-period` | `10m` | **renamed** `yotresultsdistribution.generation.stale-after`, default `30m` | How long a batch the **schedule** made may be in flight before the next run gives up on it. The positive-value refusal is kept verbatim under the new key. Removed, not aliased: there is no `${...}` placeholder for the old key in this repository, so nothing outside can be relying on one |
| `yotresultsdistribution.generation.completion` | `event` \| `poll-only` | **removed** | `poll-only` would mean "learn no outcome and re-render nightly for ever". `PublicEventsConfig` subscribes whenever generation is enabled; `PropertiesValidator`'s broker rule drops its conjunct and applies unconditionally |
| `yotresultsdistribution.report.batch-generated-within` | unset, resolved from `generation.grace-period` | own `@DefaultValue("10m")` | The two now answer different questions. `resolvedBatchGeneratedWithin` and the "a zero grace period makes the unset limit refuse" case go |
| `yotresultsdistribution.generation.batch-age-refresh` | — | **new**, `10m` | `BatchAgeSweep`'s fixed delay, written twice (placeholder + `@DefaultValue`) for the reason `yotresultsdistribution.intake.gauge-refresh` is. Refused if not positive |
| *(no new key for the manual grace)* | — | derived | The manual cutoff is `max(staleAfter, lockAtMostFor)`, both of which are already settings and already validated. A third key would be a third thing to get wrong and a second answer to "how long may a run take" |

Three `application.yaml` comment sites move with the rename (the intake block's "a value borrowed
from …grace-period", the notification block's "rather than the reconciler's grace-period above", and
the report block's paragraph about resolving the rendering limit), plus the `completion` block, which
is deleted.

## Constitution Check

*GATE: evaluated against constitution v3.2.0 (2026-09-15). Re-checked after the design reviews; one
verdict's evidence changed and no verdict moved.*

| # | Principle | Verdict |
|---|---|---|
| I | Defect-Fix-First with Characterised Legacy | **PASS.** Neither oracle had a reconciler, a query or a stale-release pass; progression's leg had **no render timeout at all**, so removing the timeout moves towards the oracle. **No row is added and no row's claim changes.** One cell moves: `P2`'s pinning-test list names `GenerationReconcilerTest.a_batch_nothing_can_be_learned_about_should_be_failed_generation_timed_out`, deleted with its class, and is re-pointed at `StaleBatchReleaserTest.a_batch_still_generating_past_the_minimum_age_should_be_failed_and_released`, which holds the same promise — a render nobody reported reaches an explicit recorded failure instead of silence. The differential audit is over the **intake** half and is untouched. |
| II | Test-Driven Development | **PASS.** Every task is a red/green pair or a documented `[A]`. **Deletion has a red run too**: a removal task lands first the test that asserts the behaviour is *gone* — no bean of that type, no such method on the port, no request to that path, no such key bound, no such annotation — watches it fail, and only then deletes. "The suite still compiles" is not a red run. **[review]** T030's late-outcome case is now a genuine pair rather than a characterisation, because the counter it asserts does not exist yet. |
| III | Message-Contract First | **PASS.** No contract changes. systemdocgenerator's command and its two public events are untouched; the service stops *calling* its query endpoint, which is exercising less of somebody else's contract. The flag gains no new reader: the pass is inside the run, behind the same single gate read (FR-005, FR-018). |
| IV | Canonical JSON In, Typed Models Out | **PASS.** `DocumentStatus` — the one type modelling somebody else's JSON answer — is deleted. The pass parses nothing; its inputs are two instants and its output is a list of typed records. |
| V | SOLID with Ports and Adapters | **PASS, and the port surface shrinks.** `DocumentRenderer` goes from two methods to one. `RegisterStore` gains one method and the estate's port count is unchanged at fourteen. `StaleBatchReleaser` holds the store, the metrics, two `Duration`s and a `Clock`; `BatchAgeSweep` holds a repository, the metrics and a clock and no lock. Neither holds a driver, a broker client or an HTTP client. **[review]** Putting the fence in the store rather than in the pass is the ports rule doing its job: the atomicity is a property of the statement, and the statement belongs where the statements are. |
| VI | Explicit Failure — Nothing Is Ever Swallowed | **PASS.** Nothing is caught to continue. A batch that ceased to be stale is a row the statement did not match — a number, not a swallowed error. A store that cannot be reached leaves the pass, the run reports what it had done and rethrows, which is the run's existing behaviour. `BatchAgeSweep` takes the design rules' **one absorbed refusal** clause verbatim, as `IntakeAgeSweep` does: the reading is kept at its last value, counted, said at WARN by class, and the delay is not cancelled. **[review]** And the increment *removes* a swallow: the refused-transition drop in `DocumentOutcomeSinkImpl` is logged and counted nowhere today, which is a drop that moves no counter — the exact thing the design rules forbid. |
| VII | Privacy in Telemetry | **PASS.** Every new line names a batch by id, a count and a bounded code. The new reason and the new ignored reason are closed-enumeration label values. `runId` on every line. `TelemetryPrivacyTest`'s drive covers both new classes. |
| VIII | Estate Conventions | **PASS.** Gradle; PMD and Checkstyle; the JaCoCo ratchet stays at **0.88 line / 0.85 branch** and is not loosened — a subtractive increment is the one shape in which coverage falls unnoticed, so the gate is re-run and quoted. Conventional Commits, no AI attribution. |

## Project Structure

### Documentation (this feature)

```text
specs/004-release-stale-batches/
├── spec.md          plan.md          research.md       data-model.md
├── quickstart.md    contracts/README.md                checklists/requirements.md
└── tasks.md
```

### Source Code — the full inventory

**New**

```text
batch/StaleBatchReleaser.java          the pass
batch/BatchAgeSweep.java               the three readings, lockless, own fixed delay
config/BatchSweepConfig.java           the sweep's TaskScheduler bean + BATCH_SWEEP_SCHEDULER
resources/db/migration/V6__admit_stale_release_reason.sql   (Phase 1: widens only)
resources/db/migration/V7__retire_reconciler_vocabulary.sql (Phase 5: narrows, after the deletions)
```

**Deleted**

```text
batch/GenerationReconciler.java        the class, its lock and what is left of it; its
                                       @Scheduled, its cadence constant and the two imports they
                                       needed went early, at the Phase 4 gate, because a timer
                                       firing every stale-after interval reached a stale batch
                                       before the run's pass did and failed it under a reason that
                                       releases nothing
domain/DocumentStatus.java             the query's answer
(and, from other files) DocumentRenderer.query, SystemDocGeneratorClient.query + parsing,
StubDocumentRenderer.query, GenerationProperties.completion + its two constants,
GenerationMetrics.GENERATION_RECONCILED + reconciled(), BatchFailureReason.GENERATION_TIMED_OUT,
CompletedBy.RECONCILER, PropertiesValidator.resolvedBatchGeneratedWithin,
docker/wiremock's GET document/{id} mapping
```

**Changed — application and domain**

```text
application/RegisterStore.java         + failAndReleaseStale(scheduledCutoff, manualCutoff)
application/ReleasedBatch.java         new - one batch the pass changed, and its register count
application/StaleReleaseOutcome.java   new [gate 3] - what the pass released, and what it could
                                       not release because every attempt lost the day's key
application/DocumentRenderer.java      one method; javadoc loses "two conversations"
application/DocumentOutcomeSink.java   javadoc loses "the reconciler fetches the ones that did not"
application/DocumentOutcomeSinkImpl.java  the refused transition is counted [review]
application/RegisterNotifierService.java  javadoc ~541/566 mention the reconciler
application/NotificationDisposition.java, NotificationSummary.java   javadoc only
domain/BatchFailureReason.java         + NOT_COMPLETED_BY_NEXT_RUN, - GENERATION_TIMED_OUT,
                                       isGeneratorAttributed narrows to GENERATION_FAILED
domain/CompletedBy.java                - RECONCILER (one constant left, and it stays a type)
domain/BatchStatus.java, RegisterBatch.java   javadoc naming the reconciler
domain/RunReport.java                  reconciled -> releasedBatches + releasedRegisters
                                       + contended [Phase 4 decision]
domain/ExceptionKind.java              + BATCH_RELEASED [review]
```

**Changed — batch, persistence, config, resources**

```text
batch/RegisterGenerationJob.java       releaser first; no end-of-run chase; two released numbers
batch/RunCorrelation.java              the nesting javadoc names the run and the sweep, not the
                                       reconciler; the ambient-adoption branch stays live because
                                       the releaser calls under() from inside the run
persistence/JdbcRegisterStore.java     failAndReleaseStale - the predicate read once, then one
                                       fenced statement per batch in its own transaction with its
                                       own bounded retry [gate 3]; NOT_COMPLETED_BY_NEXT_RUN joins
                                       RELEASING_REASONS; the statement writes completed_by = NULL
                                       directly, and it is
                                       register_batch_completed_by_shape_chk rather than
                                       attributionOf that refuses an attribution [gate 4]
persistence/RegisterBatchRepository.java  generatingSince/pendingSince/generatedSince keep their
                                       javadoc's claims but lose "the reconciler asks"; the
                                       PENDING-with-no-payload gap it documents is now covered
application/ExceptionReportService.java   a FAILED batch under the new reason is BATCH_RELEASED;
                                       check nameOf(dead) and the "four stages" text for
                                       exhaustive switches [review]
config/GenerationProperties.java       staleAfter (30m), batchAgeRefresh (10m), - completion
config/GenerationConfig.java            generationReconciler bean deleted with the class (T022);
                                       the sweep is BatchSweepConfig (Phase 6)
config/SchedulingConfig.java           staleBatchReleaser bean beside the job, under both the
                                       generation-enabled and the not-CLI conditions - the only
                                       configuration carrying the pair the pass answers to (T020);
                                       the job takes ObjectProvider<StaleBatchReleaser>; the
                                       scheduler bean and constant unchanged, and its javadoc
                                       stops describing two surfaces sharing it once the
                                       reconciler's timer goes
config/SchedulingInfrastructureConfig.java, config/ProcessedLogConfig.java,
config/YotResultsDistributionProperties.java, config/PublicEventsConfig.java,
config/PublicEventsHealthIndicator.java, config/PropertiesValidator.java,
config/GenerationMetrics.java, config/ReportProperties.java
                                       config/CliModeConfig.java is the 005 worktree's and was
                                       taken back out in gate round 1; its two sentences are
                                       handed over rather than written here
adapter/publicevents/DocumentEventListener.java   comments, and the one counter gate round 1 asked
                                       for: the two incomplete-outcome drops now move
                                       yotresultsdistribution_public_events_ignored_total under the bounded
                                       reason incomplete-outcome (GenerationMetrics with it), pinned
                                       by two cases in DocumentEventListenerTest
resources/application.yaml             the four sites
docker/wiremock/README.md              loses the query line
```

**Documentation** (FR-016): `.claude/rules/design_rules.md`, `README.md`,
`.claude/agents/{spec-validator,software-engineer,qa,code-reviewer}.md`,
`specs/002-consolidate-progression-leg/quickstart.md`,
`specs/003-exception-report/quickstart.md`, `doc/DEFECT-FIXES.md` (the P2 cell only).

## Design Decisions (summary; full rationale in research.md)

1. **The pass is a class, not a method on the job** — one object, one method, a count back.
2. **The release is one fenced statement per batch in the store [review, narrowed at gate 3]** — the
   predicate is the fence, a lost race is zero rows, and there is no read-then-write to be refused;
   one batch is the unit, and a batch no attempt can release is reported, not thrown (FR-003a).
3. **PENDING and GENERATING are one rule** — with no query there is nothing to tell them apart, and
   the rule covers the PENDING batch with no payload id that the retired reads never saw (FR-020).
4. **`GENERATED` is never touched by the pass** — it holds a document somebody is owed e-mails
   about; the sweep names it and settles nothing.
5. **The gauges survive the timer** — split out, lockless, per pod, aggregated with `max()`.
6. **The retired vocabulary is removed, not kept [review, revised]** — with its one migration caveat.
7. **An operator's batch gets the longer grace [review]** — derived from two existing settings.
8. **A released batch is informational at 07:00 [review]** — its registers went out the same night.

## Test Matrix

| Area | Suite | Kind | What it holds |
|---|---|---|---|
| The pass | `batch/StaleBatchReleaserTest` (new) | U | FR-001/002/003/017/020: both cutoffs computed from the clock and the settings; the manual cutoff is the longer of the two, asserted **each way round** so neither ordering of the two settings is assumed; the two numbers returned; one line per batch naming it by id; nothing else read. **P2's re-pointed pinning test lives here.** **Phase 3** adds two more: a batch the store reports on `contended()` is counted on `yotresultsdistribution_generation_contended_total`, said once at WARN by identity, and the pass answers normally (FR-003a); and a store that went away leaves the pass as the port's own `StoreUnavailableException`, which is the per-method outage proof gate 4 deferred here from `RegisterStoreIT`. |
| The statement | `persistence/RegisterStoreIT` (extended) | IT | The predicate: PENDING by `assembled_at`, GENERATING by `requested_at`, GENERATED never, `system_generated = false` on the longer cutoff, PENDING with a null `payload_file_id` included (FR-020); the mark and the release in one transaction; supersession against a later re-share and against the share the register coming back overtook; the failure names no completion mechanism — the statement writes `completed_by = NULL` and `register_batch_completed_by_shape_chk` is what refuses the contrary, pinned by `the_failure_names_no_completion_mechanism`; a refusal on a rule that is no key at all reaches the caller as the domain's own class [gate 5]; a batch that no longer matches yields zero rows and no error. |
| Atomicity | `persistence/StaleReleaseConcurrencyIT` (new) | IT | **SC-009 [review]**: the pass raced against `markRequested` and against `markGenerated`, both winner orders, repeated; and against a re-share of one of the batch's own hearings, including one committed **inside the statement's own window**, where the snapshot cannot see it — no register ever stamped to a terminal batch, at most one live batch per key (a released day has none until it is re-assembled), exactly one notification aggregate, and nothing escaping the pass. **Gate 3**: and a batch whose every attempt is refused for the key is reported on `contended()` and left untouched while another court centre's stale batch is failed and released anyway. **Gate 4**: that refusal is now staged by a trigger scoped to the round's hearing, which takes the day's active register back inside every attempt — the refusal a re-share committing inside each window produces — because the out-of-order share it used to be staged with is one the release now decides, and the release supersedes it. **Gate 5**: the batch that must be released anyway stands on the **day after** the contended one, because the pass walks its batches by register date and then by batch id — so "the pass goes on to the batches after a contended one" is asserted on every run rather than on the runs where two random identities happen to fall the right way. |
| The run | `batch/RegisterGenerationJobTest` (extended) | U | The `InOrder` gate → releaser → `activeUnbatched`; a skipped run releases nothing, driven over both `FLAG_OFF` and `FLAG_UNREADABLE` (FR-005/FR-018); `released_batches=`, `released_registers=` and `contended=` on the line, zero when none, `reconciled=` nowhere; a releaser that throws still writes a line and rethrows; **a batch the pass could not release does not stop the run** (FR-003a), as `a_batch_the_pass_could_not_release_should_not_stop_the_run`. |
| The drop | `application/DocumentOutcomeSinkTest` (extended) | U | **FR-008 / SC-010 [review]**: `document-available` and `generation-failed` for a `NOT_COMPLETED_BY_NEXT_RUN` batch each move nothing, notify nobody, and **move the ignored counter under `terminal-batch`**; a redelivery is counted under the same reason and never as an unknown correlation. |
| The schema | `persistence/SchemaMigrationV2IT` (extended) | IT | After **V6**: the new reason is admitted, still refuses an attribution, and the two retired values are still admitted (the widening widens only). After **V7**: both retired values are refused, and V7 refuses to apply at all to a store holding one. The enum and the constraint agree in **both** directions after each migration. |
| The footprint | `persistence/SchemaMigrationV6IT` (new) | IT | What **V6** did to the store *besides* widening the list: two snapshots of one private database, pinned at **both** ends (`target("5")` then `target("6")`, never the head). Exactly one constraint definition changes, and it is `register_batch_failure_reason_chk`, the six plus the new reason; no column, table or index is added; every other constraint — including the two attribution ones on the very table V6 opens — survives byte-identical. The companion of `SchemaMigrationV4IT` and `SchemaMigrationV5IT`; **V7 narrows this same constraint and needs its own**. |
| The footprint, again | `persistence/SchemaMigrationV7IT` (new) | IT | What **V7** did to the store *besides* narrowing the two vocabularies: the same two-snapshot arrangement, pinned at both ends (`target("6")` then `target("7")`). Exactly **three** constraint definitions change — `register_batch_failure_reason_chk`, `register_batch_completed_by_chk` and `register_batch_completed_by_shape_chk` — every other constraint survives byte-identical, and no column, table or index is added **or dropped**. Three `DROP`/`ADD` pairs on a table of eight constraints, the third of them a five-clause boolean covering all seven statuses, is three chances to lose a neighbour or an arm; nothing else asks. |
| The enums | `domain/BatchFailureReasonTest`, `domain/BatchStateTest` (extended) | U | Six reasons after the swap, the new one releasing and unattributed; `isGeneratorAttributed()` true for `GENERATION_FAILED` alone; `CompletedBy` has one constant. |
| The report | `application/ExceptionReportServiceTest` (extended) | U | **FR-019 [review]**: a released batch is `BATCH_RELEASED` and not `BATCH_FAILED`; the switch over kinds stays exhaustive; the log event carries the new kind. ~~**The CLI half is owed by the 005 tree**~~ — **discharged by 005's T053/T058, 2026-09-21.** `batch/cli/ReportExceptionsCliTest` and the printed table it was about no longer exist: increment 005 deleted the CLI. The CSV half needs no case of its own either, because `EmailReportSink` renders every entry over `entry.kind()` and every count over `ExceptionKind.values()` — a kind travels by name and there is no per-kind branch for one to be missing from. Where the kind itself is asserted is `ExceptionReportServiceTest`, `LogEventReportSinkTest` and `ExceptionReportModelTest`. |
| The port | `application/DocumentRendererTest` (new, reflection), `adapter/systemdocgenerator/SystemDocGeneratorClientTest`, `adapter/stub/StubGenerationAdaptersTest` | U/W | FR-006: one method on the port; **no** request to `document/{id}` over a whole generation. |
| The readings | `batch/BatchAgeSweepTest` (new) | U | FR-011: the three gauges, zero where there is none, the parked-batch WARN that settles nothing, the absorbed read refusal, the fixed-delay placeholder, its own scheduler, and **no** `@SchedulerLock`. |
| The wiring | `config/GenerationWiringContextTest`, `CliModeConfigTest`, `ReportSchedulingConfigTest`, `PublicEventsHealthIndicatorTest` (all extended) | U | No reconciler bean anywhere; a releaser and a sweep where the generation half is on and neither on a command JVM; one lock, one cron, four schedulers; subscription on `generation.enabled` alone. |
| The settings | `config/ConfigurationValidationTest`, `config/ReportPropertiesTest` (extended) | U | FR-010/FR-014: `stale-after` 30m and its refusal; `batch-age-refresh` 10m and its refusal; `batch-generated-within` 10m without reading the generation half; `completion` unbound. |
| End to end | `e2e/GenerationFailureEndToEndIT` (extended), `e2e/GenerationEndToEndIT` (follows) | IT | SC-001 and SC-003 in one case: a batch left GENERATING overnight is released, re-batched, rendered and notified **once**, and its own late `document-available` moves nothing and is counted. |
| Privacy | `config/TelemetryPrivacyTest` (extended) | U | Both new classes on the `GenerationLegs` drive; no identifier but a batch id; no throwable. |

## Complexity Tracking

| Item | Why it is here | Why the simpler thing was rejected |
|---|---|---|
| A new store method rather than reusing `markFailed` per batch | Atomicity and the fence are the correctness of the feature | A loop of `markFailed` strands registers on a crash and lets one refused transition end the night |
| `BatchAgeSweep`, a new class in a subtractive increment | Three gauges would otherwise freeze at whatever the last run saw, and a Micrometer gauge never decays | Leaving them in the pass makes them a daily sample under the run's lock; folding them into `IntakeAgeSweep` puts generation readings on a sweep that runs where generation is off |
| `ExceptionKind.BATCH_RELEASED` | A released batch's registers went out the same night | Reporting it as `BATCH_FAILED` sends support after something already put right, every morning after any lost outcome |
| Removing `yotresultsdistribution.generation.completion` rather than pinning it | `poll-only` would re-render everything nightly for ever | A setting with one legal value reads as a choice and is not one |
| Two released numbers rather than one | A batch is a document and an e-mail; a register is a hearing. The run line already keeps both accounts | One number answers only one of the two questions a night raises |
