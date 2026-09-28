# Tasks: Release stale in-flight batches before batching

**Input**: Design documents from `/specs/004-release-stale-batches/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md
**Revised**: 2026-09-19, after the two design reviews the spec's second Clarifications session
records. The tasks below are the revised list; nothing from the pre-review draft survives unchanged
in Phases 1, 2 and 6.

**Tests are MANDATORY** (Constitution Principle II) and every implementation task is strictly
preceded by the test task that guards it. Test names come from the plan's test matrix — do not
rename them without updating the matrix. Where a task lands a test the matrix does not name, the
task says so and the matrix gains the row in the same commit.

**Red-run convention (applies to every test task)**: a test task includes creating the minimal
**compile-safe seams** its test needs — interface declarations, record signatures, class skeletons
whose methods throw `UnsupportedOperationException` — so that the recorded red run is a **failing
assertion**, never a missing class or a compile error. The failing assertion is quoted in the test
task's commit narrative; the paired implementation task's narrative quotes the green run.

**The red-run convention for a subtractive increment.** More than half of this increment is deletion,
and "the suite still compiles" is not a red run. A task that removes behaviour lands, *first*, the
test that asserts the behaviour is **gone** — no bean of that type on the context, no such method
declared on the port, no request to that path in WireMock's journal, no such key bound, no such
annotation on any method, no such constant in the enum — watches it fail against the code that still
has it, and only then deletes. A deletion whose only evidence is a still-green suite is a deletion
nobody tested, and the thing it removed can come back in a merge with nothing to catch it.

**Minimal implementation is part of the convention, not a shortcut.** Two pairs here are split
deliberately so the second test has a real red to record; where that is the point of a split, the
task says so in as many words.

### Approved TDD exceptions

**None in advance**, and none is granted in advance. Every task below is either a red/green pair, an
`[A]` characterisation, or a documentation task exempt from the loop. If a pair cannot be formed, the
exception is written into this section with the design owner's dated approval **before** the commit
lands, in the shape 002's and 003's exception blocks use — never argued for afterwards in a commit
body. 003's second exception carried a live condition from the design owner: **a wiring task's test
is its context case**, and a third occurrence of a configuration class landing without one is
reverted and re-landed as a pair. That condition applies to `config/BatchSweepConfig` (T032) by name.

### The defect-fix register in this increment

**No row is added, and no row's claim changes.** Neither oracle had a reconciler, a query or a
stale-release pass: the function app never saw a batch, and progression's leg had no render timeout
at all. A task that finds itself wanting a `C` or `P` number has found a defect in 001 or 002, not in
this increment, and it stops and asks.

**One row is touched in one cell.** `P2`'s pinning-test list names
`GenerationReconcilerTest.a_batch_nothing_can_be_learned_about_should_be_failed_generation_timed_out`,
deleted with its class at T022. T045 re-points the cell at
`StaleBatchReleaserTest.a_batch_still_generating_past_the_minimum_age_should_be_failed_and_released`
and adds one dated sentence saying the mechanism changed in 004. The row's legacy behaviour, fixed
behaviour, rationale and status are untouched. `RegisteredDefectFixes` and `DifferentialAuditTest`
stay green throughout.

**Conventions**: package root `uk.gov.hmcts.cp.yotresultsdistribution`; production under
`src/main/java/…`, tests under `src/test/java/…`. `*IT` suites need Docker and run inside
`./gradlew test`. Conventional Commits on `004-release-stale-batches`; accepted types `feat`, `fix`,
`chore`, `docs`, `test`, `refactor`, `build`, `ci`, `style`. No AI attribution anywhere. **Every phase
ends with a green `./gradlew jacocoTestReport build`, in that order** — `build` does not produce a
coverage report, only the gate that reads one, so a close that quotes ratios without asking for the
report is quoting whatever report an earlier increment left on disk — every `gradlew` behind the
shared `flock`, never two Gradle builds at once — **and a review gate in a new session**, whose findings land as a red test commit
then an implementation commit before the next phase starts. **Never two committing agents at once in
this tree.**

**Decided 2026-09-19: two migrations, not one.** The Phase 1 implementer found a genuine cycle and
declined T003-T007 twice with evidence, which was the right call. `GenerationReconciler` is the only
writer of `BatchFailureReason.GENERATION_TIMED_OUT` and `CompletedBy.RECONCILER` (lines ~159, ~414,
~422) and is not deleted until **T022**/**T025**, so the constants cannot go in Phase 1 without
re-pointing a live write at `EVENT` and putting a false claim in the one column that exists to say
which mechanism learned an outcome. Meanwhile Phase 2's `T009` writes `NOT_COMPLETED_BY_NEXT_RUN`
over Testcontainers Postgres, so the **admission** must land no later than Phase 2. The original
FR-012 asked for the admission and the removals in *one* forward migration, and those three
sentences do not fit together in any ordering.

The design owner's answer splits the migration and changes no behaviour:

- **`V6__admit_stale_release_reason.sql` (Phase 1, T006)** — admits `NOT_COMPLETED_BY_NEXT_RUN`
  **beside** the two retired values. It removes nothing, narrows nothing, and therefore refuses on no
  existing row.
- **`V7__retire_reconciler_vocabulary.sql` (Phase 5, T049)** — lands immediately after the reconciler
  and its query leg are deleted, removes the two retired constants from the enums and narrows all
  three CHECK constraints. **This** is the migration that refuses on a pre-004 row, so the
  `docker compose down -v` note and the local-stack observation travel to Phase 5 with it.

The state the increment reaches at the end of Phase 5 is exactly the one `data-model.md` describes.
What changed is the sequencing and FR-012's wording, not the destination.

## Format: `[ID] [P?] [A?] [US#] Description`

- **[P]**: may run in parallel with other [P] tasks in the same phase (different files, no dependency
  on an unfinished task)
- **[A]**: acceptance/characterisation — verifies assembled behaviour; no red run required, and the
  task records the observed result
- **[US#]**: the spec user story the task traces to. Story-phase tasks only

---

## Phase 1: Setup and Foundational — the settings, the vocabulary and the schema

**Purpose**: everything every later phase reads. The settings the pass works to, the reason it
writes, the reason it stops writing, and the constraints that admit one and refuse the other.
Nothing here changes behaviour on its own.

**Setup and Foundational are one phase here** because there is no project initialisation to do and
each of the four below is a blocking prerequisite for every user story.

### Tests first ⚠️

- [x] T001 [P] `config/ConfigurationValidationTest` (extend) and `config/ReportPropertiesTest`
      (extend) — the renamed setting, the new one, the removed one and the un-borrowed one.
      `stale_after_defaults_to_thirty_minutes`; `a_zero_stale_after_refuses_to_start` and
      `a_negative_stale_after_refuses_to_start`, each asserting the message names
      `yotresultsdistribution.generation.stale-after`; `batch_age_refresh_defaults_to_ten_minutes` and
      `a_non_positive_batch_age_refresh_refuses_to_start` naming
      `yotresultsdistribution.generation.batch-age-refresh`; `the_completion_setting_is_no_longer_bound`,
      which sets `yotresultsdistribution.generation.completion=poll-only` and asserts the context binds no
      such value; and in `ReportPropertiesTest`,
      `batch_generated_within_defaults_to_ten_minutes_without_reading_the_generation_half`, over a
      context whose `stale-after` is `30m` — the case that would otherwise have silently tripled the
      07:00 report's threshold. Delete `a_zero_grace_period_makes_the_unset_rendering_limit_refuse`,
      whose subject no longer exists. Red: the keys do not exist and the resolution still reads the
      generation half.
      (red on the seam tree: 166 tests, 7 failures, 0 errors, every one an assertion.
      `stale_after_defaults_to_thirty_minutes` on "expected: 30M but was: 10M";
      `batch_age_refresh_defaults_to_ten_minutes` on "expected: 10M but was: null";
      `a_zero_stale_after_refuses_to_start`, `a_negative_stale_after_refuses_to_start` and
      `a_non_positive_batch_age_refresh_refuses_to_start` each on "Expecting
      <Started application [...]> to have failed but context started successfully";
      `batch_generated_within_defaults_to_ten_minutes_without_reading_the_generation_half` and
      `report_defaults_are_the_documented_ones` on "expected: 10M but was: 1M".
      The seams are the record components themselves, since a configuration default has no other
      seam: `staleAfter` and `batchAgeRefresh` landed on `GenerationProperties` carrying the old
      ten minutes and no default at all, and `ReportProperties.batchGeneratedWithin` landed
      carrying one minute, so every red is an assertion on a value rather than a missing accessor.
      Two deviations, both forced and both additive. First, `the_completion_setting_is_no_longer_bound`
      and the broker rule's own cases live in a renamed nested class,
      `GenerationOutcomeAndDurations`, because the class they were in was named after the setting
      that goes; `poll_only_completion_without_a_broker_should_start` is deleted with its subject and
      `event_completion_with_generation_disabled_should_start` is renamed
      `a_disabled_generation_without_a_broker_should_start`. Second,
      `an_unset_batch_generated_within_resolves_to_the_generation_grace_period` is deleted with
      `resolvedBatchGeneratedWithin` and `an_explicit_batch_generated_within_is_honoured` keeps its
      claim without naming the generation half.)
- [x] T003 [P] `domain/BatchFailureReasonTest` (extend) and `domain/BatchStateTest` (extend) — the
      vocabulary **gains** the new reason; nothing is taken away here (the removals are T047/T048,
      after the reconciler that writes the retired values is gone).
      `the_seven_reasons_are_the_bounded_set`;
      `not_completed_by_next_run_is_not_generator_attributed`;
      `not_completed_by_next_run_releases_its_rows`; and in `BatchStateTest`, the schema-vocabulary
      case extended so the enum and `register_batch_failure_reason_chk` are held to each other in
      **both** directions over all seven values — which is the assertion that makes T006 and T048
      each provably complete, and the one the implementer correctly said could not be green against
      a half-done vocabulary. Red: the new constant does not exist (seam: the constant).
      (red with T005 on the seam tree: 128 tests, 8 failures, 0 errors, every one an assertion.
      `the_seven_reasons_are_the_bounded_set` and
      `the_failure_reasons_should_be_exactly_the_seven_the_schema_enumerates` each on "Expecting
      actual: [… six reasons …] to contain exactly in any order: [… seven …]";
      `not_completed_by_next_run_is_not_generator_attributed` on "Expecting Optional to contain a
      value but it was empty"; `not_completed_by_next_run_releases_its_rows` on "Expecting actual:
      [PAYLOAD_STORE_UNAVAILABLE, ASSEMBLY_FAILED] to contain exactly in any order" the three;
      `the_attribution_table_should_classify_every_reason_and_no_others` and its new twin
      `the_release_table_should_classify_every_reason_and_no_others` on the same shortfall from the
      other side.
      **The seam is the constant's name, not the constant.** A test that named
      `BatchFailureReason.NOT_COMPLETED_BY_NEXT_RUN` could not compile before T004, and the
      convention forbids a compile error as a red run — so both tables are keyed by the constant's
      *name*, which is what reaches `register_batch.failure_reason`, a metric label and the run
      report anyway, and a reason can therefore be specified here before it exists. `reasonNamed`
      looks the constant up in `values()`, so its absence is a failing assertion about the
      vocabulary.
      Three deviations, all additive. First, `BatchFailureReasonTest.ATTRIBUTION` is re-keyed from
      the constant to its name for the reason above, and gains `RELEASES_ROWS` beside it —
      data-model.md's "releases rows" column, held to `values()` in both directions and cross-checked
      against `isGeneratorAttributed()` by `an_attributed_reason_should_never_release_its_rows`, an
      ending somebody else reported having nothing to give back. `JdbcRegisterStore.RELEASING_REASONS`
      is private and is Phase 2's to change, so the table is the classification's home until
      `RegisterStoreIT` observes the release over a real batch at T008.
      Second, `the_failure_reasons_should_be_exactly_the_six_the_schema_enumerates` is renamed for
      the seventh value and now **reads the migrations** rather than carrying a hand-transcribed
      copy of the constraint: `schemaFailureReasons()` concatenates `db/migration/V*.sql` in version
      order, takes the last definition of `register_batch_failure_reason_chk` and extracts its `IN`
      list. A hand-written list agrees with whatever it was typed from and cannot make T006 provably
      complete; the constraint's own text can. The case is therefore red from the constant landing
      until V6 lands.
      Third, `BatchStateTest` gains four private helpers and the imports they need; nothing in the
      state machine or the flag-decision nests is touched.)
- [x] T005 [P] `persistence/SchemaMigrationV2IT` (extend) — what V6 must make true, and what it
      must leave alone. `v6_admits_not_completed_by_next_run` (a FAILED batch under the new reason
      with a null attribution succeeds); `the_new_reason_refuses_an_attribution`, which
      `register_batch_completed_by_shape_chk` must enforce for it exactly as it does for the four
      other unattributed reasons; `v6_still_admits_the_retired_timeout_reason` and
      `v6_still_admits_the_retired_attribution`, which pin that this migration **widens only** — a
      narrowing here would refuse on a row the reconciler is still writing until Phase 5. Red: the
      first two fail against V1–V5.
      (red with T003 on the seam tree, in the same 128-test run: `v6_admits_not_completed_by_next_run`
      on "Expecting code not to raise a throwable but caught … violates check constraint
      \"register_batch_failure_reason_chk\"", and `the_new_reason_refuses_an_attribution` on the same
      refusal of its precondition row. The last two are green against V1–V5 and are meant to be:
      they assert what V6 must **not** change, so a green before and a green after is the whole
      claim, and a red on either would mean the retired vocabulary had already gone.
      One deviation, and it is what the task predicted only half of. Written as a bare refusal,
      `the_new_reason_refuses_an_attribution` **passed** against V1–V5: the attributed row violates
      the vocabulary constraint *and* the shape constraint, Postgres reported the shape one, and the
      assertion matched. A case that passes against the code it is written to change proves nothing,
      so the admitted row was made its explicit precondition — the reason is admitted, therefore the
      refusal that follows is about the attribution alone — which is both the truer statement and a
      real red.)
- [~] T007 **Moved to Phase 5 (T050)** — the local stack's clean-store step. Nothing in Phase 1
      needs it: `V6` widens only and refuses on no existing row, so there is no volume to clean
      before it. The observation belongs to `V7`, which is the migration that narrows, and it travels
      there with it. The id is kept and left here as the pointer; nothing is renumbered.

### Implementation

- [x] T002 `config/GenerationProperties.java`, `config/PropertiesValidator.java`,
      `config/ReportProperties.java`, `src/main/resources/application.yaml` — make T001 green.
      `gracePeriod` → `staleAfter` with `@DefaultValue("30m")`; add `batchAgeRefresh`
      `@DefaultValue("10m")`; remove the `completion` component and its two constants and its
      validation. In the validator: rename `GENERATION_GRACE_PERIOD` → `GENERATION_STALE_AFTER`
      keeping the positive refusal verbatim, add the same refusal for the refresh, drop the
      `completion == event` conjunct from the broker rule so it applies whenever generation is
      enabled, and delete `resolvedBatchGeneratedWithin` and its call sites. In `application.yaml`:
      `grace-period: 10m` → `stale-after: 30m`; add `batch-age-refresh: 10m` with the written-twice
      note the intake key carries; delete the `completion` key and its comment block; and re-point
      the three comment sites that describe the retired machinery (the intake block's "borrowed
      from …grace-period", the notification block's "rather than the reconciler's grace-period
      above", and the report block's paragraph about resolving the rendering limit, replaced by the
      key itself).
      (green: `ConfigurationValidationTest` and `ReportPropertiesTest`, 166 tests, 0 failures,
      0 errors. Four files beyond the four this task names had to move with the removal, because the
      component and the key they read no longer exist: `config/PublicEventsConfig` (the subscription
      autostarts on `generation.enabled()` rather than on the completion mechanism — FR-013's end
      state, reached here because there is nothing else left to read),
      `config/ProcessedLogConfig` (the report's rendering limit is now `report.batchGeneratedWithin()`
      rather than the deleted resolution), `config/GenerationConfig` and `batch/GenerationReconciler`
      (the transitional reconciler's `@Scheduled` placeholder is re-pointed at
      `${yotresultsdistribution.generation.stale-after}`, so its cadence is thirty minutes until T022 deletes
      it, and `GenerationReconcilerTest`'s placeholder assertion follows).
      `PropertiesValidator.validateReport` loses its `GenerationProperties` parameter, which nothing
      in it read any more, and `YotResultsDistributionProperties`'s javadoc reference to the grace period is
      re-pointed.
      **Two files of the other tree's had to be touched, and they are named here rather than left
      to the diff** (recorded at gate round 1 of the increment gate, 2026-09-21, having been
      omitted when this task landed). `GenerationProperties` is a record, so removing `completion`
      and adding `staleAfter` and `batchAgeRefresh` changes its arity, and every hand-written
      construction of it in the suite is a compile error until it is updated -
      `batch/cli/CliMainTest.settings()` and `batch/cli/GenerateRegisterCliTest.settings()` among
      them. The coordination contract gives `batch/cli` tests to the 005 tree, so this is the
      report the contract asks for: the edit is the constructor call and nothing else - no case
      added, none removed, no behaviour asserted differently - and it cannot be reverted without
      leaving the suite uncompilable. **Hand-off to the 005 tree**: its rebase must keep 004's two
      `settings()` helpers, which pass `Duration.ofMinutes(30)` for `staleAfter` and
      `Duration.ofMinutes(10)` for `batchAgeRefresh` where they used to pass a grace period and
      `GenerationProperties.COMPLETION_EVENT`.)
- [x] T004 `domain/BatchFailureReason.java` — make T003 green. Add `NOT_COMPLETED_BY_NEXT_RUN`
      with the javadoc data-model.md gives it: this service's own verdict, releasing, and naming no
      completion mechanism. `isGeneratorAttributed()` is **unchanged** here — it already answers
      `false` for a reason it does not name, and narrowing it is T048's, once the mechanism it names
      no longer exists. `CompletedBy` is not touched in this phase.
      (red re-run before the change, `BatchFailureReasonTest` and `BatchStateTest`: 50 tests,
      6 failures, 0 errors, every one an assertion — `not_completed_by_next_run_is_not_generator_attributed`
      on "Expecting Optional to contain a value but it was empty", the two table cases and the two
      bounded-set cases on the missing seventh name.
      green: 51 tests, 1 failure, 0 errors. `BatchFailureReasonTest` is 14 of 14, so every claim
      the enumeration can answer on its own is green. The one remaining failure is
      `the_failure_reasons_should_be_exactly_the_seven_the_schema_enumerates`, and it is the red
      T003 predicted for exactly this window: its **first** assertion — the enumeration holds the
      seven — now passes, and it fails on its **second**, "the values
      register_batch_failure_reason_chk admits after every committed migration", because V6 has not
      landed. The constant landing before its migration is the half-done vocabulary that case exists
      to refuse, and it goes green at T006. `checkstyleMain` and `pmdMain` green.
      Two deviations, both prose and both forced by the seventh constant. The type javadoc's "the
      six are six different investigations" becomes seven and names the new grouping, since only the
      first pair *and the last* leave their rows RECORDED; and `isGeneratorAttributed()`'s "the
      other four are this service's own verdict" becomes five, "could not ask for, could not hear
      about, or stopped waiting for". Neither sentence is a rule anything reads; the method's
      expression is untouched and `CompletedBy` is not opened.)
- [x] T006 `src/main/resources/db/migration/V6__admit_stale_release_reason.sql` — make T005 green.
      One statement: `register_batch_failure_reason_chk` is replaced by the same list plus
      `NOT_COMPLETED_BY_NEXT_RUN`, the two retired values still in it. The two attribution
      constraints are **not** touched: the new reason is not generator-attributed, so it falls in
      `register_batch_completed_by_shape_chk`'s third arm's `false = false` case with no edit.
      Additive and forward-only; `V2` is not edited; no column, table or index is added; and because
      it only widens, it applies to any store in any state.
      (red before the migration, `SchemaMigrationV2IT` over Testcontainers Postgres: 78 tests,
      3 failures, 0 errors, every one an assertion. `v6_admits_not_completed_by_next_run` and
      `the_new_reason_refuses_an_attribution` each on "Expecting code not to raise a throwable but
      caught … violates check constraint \"register_batch_failure_reason_chk\"" — the second on its
      precondition row, so the refusal it goes on to make is about the attribution alone; and
      `failure_reason_check_should_name_exactly_the_bounded_reasons`, which reads the live
      constraint against the enumeration and went red the moment T004 landed the constant, on
      "Expecting actual: \"CHECK (((failure_reason IS NULL) OR (failure_reason = ANY (ARRAY[… six
      …]))))\" to contain … NOT_COMPLETED_BY_NEXT_RUN". `v6_still_admits_the_retired_timeout_reason`
      and `v6_still_admits_the_retired_attribution` were green before and are green after, which is
      their whole claim: this migration takes nothing away.
      green: `SchemaMigrationV2IT`, `BatchStateTest` and `BatchFailureReasonTest` together,
      129 tests, 0 failures, 0 errors — so the two directions of the vocabulary cross-check close on
      the same commit, `the_failure_reasons_should_be_exactly_the_seven_the_schema_enumerates`
      against the migration text and
      `failure_reason_check_should_name_exactly_the_bounded_reasons` against the constraint Postgres
      actually holds. The migration itself has no deviations: one dropped constraint, one added, the
      same list plus the new value, and neither attribution constraint opened.
      One file beyond it had to move, and the full build is what found it.
      `persistence/SchemaMigrationV5IT` snapshots the schema at V4 and again after
      `flyway().load().migrate()` — the **head**, not V5 — and then asserts that the difference is
      five indexes and no constraint. That was right only while V5 was the last migration; with V6
      applied the suite was making a claim about every migration since V4 and failed on the
      constraint V6 widened. It is pinned to `target("5")`, which is what `SchemaMigrationV4IT`
      already does at the same line and for the same stated reason, and the only alternative — to
      stop asserting that a migration changed nothing else — would give up the suite's whole
      subject. No assertion is relaxed and the V6 cases are untouched.
      The full build also surfaced a `pmdTest` failure this range did not cause: T003's three new
      `BatchStateTest` fields — `MIGRATIONS`, `FAILURE_REASON_CHECK`, `QUOTED_CODE` — landed below
      `drawnMoves()`, three `FieldDeclarationsShouldBeAtStartOfClass` violations, and `pmdTest` runs
      in `build`. They are moved above the first method and nothing else about them changes.
      `pmdTest`, `checkstyleTest` and `BatchStateTest` green.)

**Phase close**: `flock … ./gradlew build` green; review gate.
(green at `20bab2b`: `flock -w 7200 … ./gradlew build -Dtest.noFailFast=true` BUILD SUCCESSFUL,
exit 0, 3630 tests over 576 suites, 0 failures, 0 errors; `checkstyleMain` and `checkstyleTest` at
`maxWarnings = 0`, `pmdMain` and `pmdTest`, and `jacocoTestCoverageVerification` against the
unchanged gate of LINE 0.88 / BRANCH 0.85 — none of them loosened. **No ratio is quoted for this
tree, because none was measured on it**: `build` runs the gate and not the report, and the figures
this close first carried were read off a report increment 003 had left in `build/`. What ran here
is the gate, and it passed. The
phase closes on the tree as committed, which is what the two commits beyond T004 and T006 were for:
`290d898` pins `SchemaMigrationV5IT` to V5, and `20bab2b` moves `BatchStateTest`'s three
migration-reading fields above the first method. Review gate to follow.)

**Review gate 1 ran against the committed Phase 1 content** with three read-only reviewers plus
Codex. One finding above LOW, and where it was closed:

* nothing observed **V6's footprint**. T006's narrative claims "no column, table or index is added"
  and that neither attribution constraint was opened, but V4 and V5 each have a snapshot-diff suite
  saying so of themselves and V6 had none — and pinning `SchemaMigrationV5IT` to V5 removed the one
  head-running suite that would have failed noisily on a V6 that added anything else. Closed by
  `persistence/SchemaMigrationV6IT`, in the `SchemaMigrationV4IT`/`V5IT` shape and pinned at **both**
  ends (`target("5")` then `target("6")`), at `e4ddb89`, with the Test Matrix row in the same commit.
  A pin of an already-correct migration cannot be red against the tree, so it was made red against
  the migration instead: V6 was temporarily given an extra `ADD COLUMN` (red on
  `v6_should_add_no_column_table_or_index`, `register_batch.stale_note` in the diff), then a
  re-spelled `register_batch_completed_by_chk` (red on
  `v6_should_rewrite_only_the_failure_reason_check`). The second mutation is what earned the suite
  its shape: `v6_should_leave_every_other_constraint_untouched` was first written subtracting the
  diff from both snapshots, which makes the two sets equal by construction, and it **passed** the
  mutation. It now subtracts the one constraint V6 may rewrite **by name**, and both cases are red
  against that mutation. Green against the tree as committed: 4 of 4.

Two LOW findings were closed here as well, both cheap and both in files this phase already owns:

* `failure_reason_check_should_name_exactly_the_bounded_reasons` said "exactly" and asserted
  `contains`, which proves every constant reaches the database and nothing at all about a code the
  column admits and no constant names. The codes are now taken out of the live definition and
  compared `containsExactlyInAnyOrder` against `BatchFailureReason`, which is the direction **V7**
  needs and which `BatchStateTest` cannot supply, reading the migration *files* rather than the
  database they were supposed to produce. Red against a V6 temporarily carrying an eighth code
  (`ABANDONED`), green against the tree: 78 of 78. At `69dee54`.
* V6's comment said an attributed row under the new reason evaluates as `true = false` in
  `register_batch_completed_by_shape_chk`'s third arm. It evaluates as `false = true` — the reason
  is not in that arm's attributed list, and the attribution is present — and the same sentence sat
  in `the_new_reason_refuses_an_attribution`'s javadoc, where it also conflated the refused row
  with the admitted one. Both corrected; the row is refused either way and no behaviour changes.
  Editing V6's text changes its Flyway checksum, which is safe only because V6 has been applied to
  no environment: **V6 is frozen from here**, and the same correction after cutover would have been
  a V8. At `ecc3309`.

The remaining LOW findings are left, each with its reason, for the reviewers to re-judge: the five
stale reason-cardinality sentences (`JdbcRegisterStore` 586 and 1524, `RegisterStore` 295,
`RegisterStoreIT` 1777, `GenerationMetricsTest` 156) and `RELEASING_REASONS` itself belong to the
Phase 2 commit that opens `JdbcRegisterStore` — T008/T009 — and the enum shrinks back to six at
T048; `design_rules`' six-reason list is T041's; and renaming the Phase 1 cases to the `should_`
form would rewrite the red runs recorded against their current names at T003, T005 and T006.

Green after the remediation: `flock -w 7200 … ./gradlew build -Dtest.noFailFast=true` BUILD
SUCCESSFUL, exit 0, 3634 tests over 577 suites, 0 failures, 0 errors — four more than the phase
close, being `SchemaMigrationV6IT`'s — with `checkstyleMain`, `checkstyleTest`, `pmdMain`, `pmdTest`
and `jacocoTestCoverageVerification` all green and none of them loosened.

---

## Phase 2: User Story 1 — the fenced store operation (Priority: P1) 🎯 MVP

**Goal**: one statement that fails every stale batch and releases its registers, atomically, fenced
on the staleness rule itself. **This is the correctness of the whole increment** and it is a phase of
its own for that reason.

**Independent test**: `RegisterStoreIT` and `StaleReleaseConcurrencyIT` against Testcontainers
Postgres. No pass, no run, no Spring context.

### Tests first ⚠️

- [x] T008 [US1] `persistence/RegisterStoreIT` (extend) — the predicate and the write.
      `a_generating_batch_past_its_cutoff_is_failed_and_its_registers_released`;
      `a_pending_batch_past_its_cutoff_is_failed_and_its_registers_released`;
      `a_pending_batch_with_no_payload_id_is_released_too` (FR-020 — the gap the retired reads left);
      `a_generated_batch_is_never_matched_at_any_age` (FR-002);
      `a_manually_generated_batch_uses_the_longer_cutoff` (FR-017);
      `a_batch_inside_its_cutoff_is_not_matched`;
      `the_mark_and_the_release_are_one_transaction`, asserting no intermediate state is observable;
      `the_failure_names_no_completion_mechanism`, so `attributionOf` is satisfied with `null`;
      `a_release_supersedes_against_a_later_re_share` (US1.5);
      `a_batch_that_no_longer_matches_yields_zero_rows_and_no_error` (FR-003a);
      `the_operation_returns_what_it_changed_with_its_register_counts`. Seam:
      `RegisterStore.failAndReleaseStale` and the `ReleasedBatch` record, with the Jdbc
      implementation throwing `UnsupportedOperationException`. Red: a failing assertion on the first
      case.
      (red: `./gradlew test --tests '*RegisterStoreIT*' -Dtest.noFailFast=true`, 83 tests,
      **11 failures**, 0 errors - the eleven new cases and nothing else, every failure an assertion.
      The first case's second failure is the property under test:
      "Expecting actual: Optional[BatchOutcome[status=GENERATING, failureReason=null,
      sdgReason=null]] to contain: BatchOutcome[status=FAILED,
      failureReason=NOT_COMPLETED_BY_NEXT_RUN, sdgReason=null] but did not", and its third is
      "expected: 0L but was: 2L" on the stamped rows - the mark and the release, each named
      separately, because a mark without its release is the lost register this increment exists to
      end. The seam refusal is recorded as each case's *first* soft failure rather than as a stack
      trace out of the arrangement, which is what the suite's soft-assertion convention is for.
      Seams: `application/ReleasedBatch` (new record), `RegisterStore.failAndReleaseStale`, and
      `JdbcRegisterStore.failAndReleaseStale` throwing `UnsupportedOperationException`.
      Three deviations from the task's letter, each because the property could not otherwise be
      red. **The ages are written, not waited for**: `ageBatch` moves a batch's `assembled_at` and
      `requested_at` back by the database's own clock, and every cutoff a case states is in the
      **past** - a cutoff in the future would name every batch in the shared container, including
      the ones another suite is holding. **The COALESCE direction is pinned inside the first case**
      rather than as a case of its own: it ages `assembled_at` alone, asserts nothing matched, then
      ages `requested_at` and asserts the release. **The transaction case refuses the release on
      purpose**, under a partial unique index on this court centre that admits one unbatched
      register, and then asserts the mark went down with it - a single-threaded case cannot
      otherwise observe that there is no intermediate state, and the second half of the same case
      (index dropped, the same call made again) is what is red against the seam.
      `checkstyleMain`, `checkstyleTest`, `pmdMain` and `pmdTest` green; `pmdTest` needed the
      fixture's row count taken out of the `if` as `ONE_BATCH`, in the constant block at the top of
      the class, for the reason T003's three fields were moved there.)
- [x] T010 [US1] `persistence/StaleReleaseConcurrencyIT` (new) — **SC-009**, the reason the operation
      is fenced. `a_render_acceptance_racing_the_release_leaves_no_stranded_register` and
      `a_document_arrival_racing_the_release_leaves_no_stranded_register`, each run in **both**
      winner orders and repeated, asserting after every round that no `register_record` is stamped to
      a batch in a terminal state, that at most one live batch exists per (court centre, register
      date), and that at most one notification aggregate exists per key. Red: against T009's
      implementation this passes; **against a deliberately staged read-then-mark variant it does
      not**, and the task records that staged failure as its red, because the assertion being made is
      about the shape of the operation and a test that cannot fail against the wrong shape proves
      nothing. The staged variant is not committed.
      (red: `./gradlew test --tests '*StaleReleaseConcurrencyIT*' -Dtest.noFailFast=true`, 2 tests,
      **2 failures**, 0 errors, **7 failing assertions each** - six rounds' "the loser of a race is
      refused by the state machine and by nothing else" ("Expecting actual throwable to be an
      instance of: java.lang.IllegalStateException but was:
      java.lang.UnsupportedOperationException"), plus the staged first round's ending: "expected:
      Ending[status=FAILED, failureReason=NOT_COMPLETED_BY_NEXT_RUN] but was:
      Ending[status=GENERATED, failureReason=null]".
      **The staged read-then-mark variant is T009's, not this task's, and the task text is amended
      to say so.** The variant cannot be staged before the statement it is a mutation of exists:
      there is nothing to take apart at this point in the phase. The red recorded here is therefore
      the seam's, captured as an assertion rather than as a thrown refusal - the contenders' results
      are collected by `escaping(...)`, which is what the suite is about anyway, since the loser of
      a race is refused and that refusal is the subject. The mutation that proves the suite can
      fail against the wrong *shape* is run at T009 and recorded in its narrative, in the shape
      review gate 1 used for `SchemaMigrationV6IT`.
      Each round mints its own court centre and every cutoff stated is in the past, so no round can
      reach a batch another suite sharing the container is holding; `settleTheNight` then runs the
      rest of the night - a GENERATED batch is notified once, registers the pass gave back are
      assembled, rendered and notified - so the "one notification aggregate per key and address"
      invariant has rows to count rather than being an assertion about an empty table.
      `checkstyleTest` and `pmdTest` green: `concurrently` holds its pool in a try-with-resources
      (`CloseResource`) and `escaping` uses AssertJ's `catchThrowable` rather than a
      `catch (RuntimeException)` (`AvoidCatchingGenericException`, `OnlyOneReturn`).)

### Implementation

- [x] T009 [US1] `application/RegisterStore.java`, `persistence/JdbcRegisterStore.java` — make T008
      green. The port method and its `ReleasedBatch` answer; the single statement of data-model.md,
      with `COALESCE(requested_at, assembled_at)` and the `system_generated` `CASE`; the reuse of
      `MARK_FAILED`'s existing release-and-supersede branch over the matched set; and
      `NOT_COMPLETED_BY_NEXT_RUN` added to `RELEASING_REASONS`, so a per-batch `markFailed` from the
      operations surface releases on it too. The javadoc states the fence and says what a
      read-then-mark shape would cost — a stranded register that `activeUnbatched` can never see, and
      a refused transition that ends a night — because that is the reason the method exists at all
      rather than being a loop in the caller.
      (green: `./gradlew test --tests '*RegisterStoreIT*' --tests '*StaleReleaseConcurrencyIT*'
      -Dtest.noFailFast=true`, 85 tests, 0 failures, 0 errors — `RegisterStoreIT$StaleRelease` 11 of
      11 and `StaleReleaseConcurrencyIT` 2 of 2, with the other fifteen nested suites unchanged.
      `checkstyleMain`, `checkstyleTest`, `pmdMain` and `pmdTest` green.
      **The predicate is in the UPDATE's own `WHERE`, not in a CTE that feeds it**, and that is a
      correctness point rather than a style one. Under READ COMMITTED an `UPDATE` that meets a row
      another transaction has just committed re-evaluates *its own* qualification against the new
      row version and skips it where it no longer matches. A staleness rule computed in a preceding
      `SELECT` is evaluated once, against the statement's snapshot, and the update would then fail a
      batch whose render had been accepted in between. The fence is the re-check, so the rule has to
      be written where the re-check can see it.
      **The count answered is the registers still the day's to render**, which excludes one a
      re-share superseded as its stamp was cleared: FR-009 says the released registers are already
      inside the run's row totals because the same run re-batches them, and a superseded register is
      not one anything will re-batch. Recorded here as a decision, since the FR does not spell it
      out.
      `attributionOf` is **not** called by this statement — there is no batch identity to name
      before the rows are chosen. The statement writes `completed_by = NULL` and
      `register_batch_completed_by_shape_chk` is what refuses the contrary, which is the same rule
      rather than a second one. Where data-model.md says the reason is "called with `null`" it is
      describing an operator's per-batch `markFailed`, and that path is covered by the reason
      joining `RELEASING_REASONS`.
      The six stale reason-cardinality sentences review gate 1 left for this commit are re-pointed
      here: `RegisterStore` 295 and 330, `JdbcRegisterStore` 587, 639 and 696,
      `RegisterStoreIT`'s `Failure` javadoc and `GenerationMetricsTest`'s series-count comment.
      `JdbcRegisterStore` 1524's "the four reasons `RELEASING_REASONS` does not name" is **left
      alone**: seven less three is still four, and it was already right. (It stopped being right at
      T048, which took `GENERATION_TIMED_OUT` out of the enumeration — six less three is three —
      and it is corrected there along with the two other sentences that count the reasons keeping
      the stamp.)
      **T010 was strengthened in this commit, and it had to be.** Staging the read-then-mark
      variant — the read, a 150 ms window, then a per-batch `markFailed` — showed the suite as
      committed at `dc4106c` **passing against it**: the loser's refusal is an `IllegalStateException`
      either way, no register is stranded without a crash, and nothing is notified twice because the
      batch the variant wrongly failed never reaches the notifier. Two assertions were added, and
      both are red against the variant: the pass's own escapes must be **empty** (FR-003a's "no
      single batch's outcome may end the run" — the variant's `markFailed` throws "batch … may not
      move from GENERATED to FAILED" straight out of the pass, which run inline in `generate()` costs
      every court centre its document), and **no batch may end FAILED under the new reason with a
      stamp later than the cutoff the round gave the pass** — which is the fence itself, and which
      the variant breaks in the render-acceptance race by failing a batch whose render had just been
      accepted. Mutation result: 2 tests, 2 failures, 3 and 4 failing assertions, all on the
      `TOGETHER` rounds. Reverted; green against the tree as committed. The variant is not
      committed.
      Per-method outage translation for `failAndReleaseStale` is covered by `StoreOutageTest` plus
      inspection of the call site rather than by a case of its own: the translator's seven cases
      hold it to its own rules and name no store method, and every statement in this class is made
      through `StoreOutage.translating`. A case over a closed `DataSource` would be the stronger
      proof and is not here.)

**Phase close**: `flock … ./gradlew jacocoTestReport build` green; review gate. **This gate is the
one to read closely**: everything after it assumes the statement is atomic and fenced.
(at the tree carrying T008, T010 and T009 — `e1ac365` — `flock -w 7200 … ./gradlew build
-Dtest.noFailFast=true` BUILD SUCCESSFUL, exit 0, 10m 5s, 3647 tests over 579 suites, 0 failures,
0 errors, `checkstyleMain`, `checkstyleTest`, `pmdMain`, `pmdTest` and
`jacocoTestCoverageVerification` all green and none of them loosened. No migration was added: the
phase writes `NOT_COMPLETED_BY_NEXT_RUN`, which V6 already admits, and adds no column, table or
index.
**This close is superseded by review gate 2's, and it is worth saying why rather than quietly
replacing it.** The tree it was taken on carried a production defect the suite did not yet ask
about — the re-share race below — so a green suite was not evidence that the statement was fenced,
only that nothing had asked. And the coverage ratios it quoted, LINE 0.9690 / BRANCH 0.8986, were
read off a report increment 003 had left in `build/`: `build` runs the gate and not the report, so
a close that quotes ratios without asking for one is quoting whatever is on disk. The close that
counts is under review gate 2 below, and the convention at the top of this file now names
`jacocoTestReport build`.)

**Review gate 1 ran against the committed Phase 2 content** with three read-only reviewers plus
Codex. What it found above LOW, and where each was closed:

* `StaleReleaseConcurrencyIT`'s **notification invariant was vacuous in the rounds the pass won**.
  A round the race left GENERATING had no half of the night left to run, so the day ended having
  told nobody and "no Youth Offending Team holds two aggregates for one register date" was
  satisfied by an empty list. Closed at `e1ac365`: the fixture now walks whatever the race left
  owed — PENDING, GENERATING or holding a document — to its document and its one e-mail, exactly as
  the renderer and the notifier do, and the invariant is read as the one aggregate SC-009 asks for.
  The same commit corrects `escaping()`'s javadoc, which still named `assertInvariants` by a
  signature that changed when the cutoff and the escapes became its arguments.
* **The re-share race** (HIGH at Codex, MEDIUM at `code-reviewer`) was **not closed at this gate**.
  It is closed at gate 2 below, where every reviewer re-raised it.

**Review gate 2 ran against the same phase after that remediation**, three read-only reviewers plus
Codex. What it found above LOW, and where each was closed:

* **The re-share race** — BLOCKER at Codex, HIGH at all three reviewers, and the finding this gate
  exists for. `failAndReleaseStale` is one statement and a statement reads one snapshot, so a
  hearing re-shared after this one began is a successor the `stamped` search cannot find, however
  plainly it is one by the time the write lands. The release then cleared the stale register's
  stamp *beside* the replacement it could not see, `idx_output_active_register_key` refused the
  second active row for the key, and the `DuplicateKeyException` escaped the pass — which, run
  inline in the night's generation, costs every court centre its document (FR-003a) and rolls back
  every other court centre's release with it.
  Pinned first, at `98d8826`, by `StaleReleaseConcurrencyIT`'s third contender and its
  `INSIDE_THE_WINDOW` round: the batch row the statement writes first is held `FOR UPDATE` by a
  session of its own, which stops the statement after its snapshot and before its release; the
  re-share is committed against the held statement and the row let go. Red on five assertions, all
  on that round — the pass escapes rather than escaping nothing, the batch is left GENERATING
  instead of FAILED under `NOT_COMPLETED_BY_NEXT_RUN`, the key holds three live registers rather
  than two, the re-shared hearing holds two active registers rather than one, and one address holds
  two notification aggregates.
  Closed at `9811570` by the idiom `recordAndComplete` already uses for the same index one method
  above: catch `DuplicateKeyException`, and where it `violates(…, ACTIVE_ROW_KEY)` make the whole
  statement again on a fresh snapshot — which has the re-share in it and supersedes against it — up
  to `RECORD_ATTEMPTS` times; a refusal on any other key is the store saying the write may never be
  made and is rethrown as itself. Each attempt is its own transaction, which the javadoc states
  along with the corollary that the call may not be put behind an outer transaction, or the first
  refusal would abort it and all three attempts would fail inside it. Green: `StaleReleaseConcurrencyIT`
  3 of 3, `RegisterStoreIT` 83 of 83.
  **One branch of that fix went in untested and left untested**, recorded here so the next reviewer
  does not re-derive it: `9811570`'s exhaustion path threw `StoreContendedException` after
  `RECORD_ATTEMPTS` refusals, and the only round that reached the retry — `INSIDE_THE_WINDOW` — pins
  one refusal followed by a successful attempt, so nothing ever drove the throw. It was deleted at
  gate 3 (`425beec`), still untested, and what replaced it is pinned by
  `a_batch_no_attempt_can_release_is_reported_while_the_others_are_released`. HEAD is clean; the
  range's history carries one production branch no test ever ran.
* **What escapes an exhausted retry** — the decision gate 1 was asked to re-judge, and the
  reviewers split on it. Codex and `qa` accepted `ConcurrencyFailureException` provided the port
  said so; `spec-validator` refused it, on the ground that the class which has to decide is Phase
  3's `StaleBatchReleaser` in `batch/`, which may name no `org.springframework.dao` type
  (constitution Principle V) and could therefore only catch it as `RuntimeException` — the catch
  that swallows every programming error beside it. The narrower reading wins, because it is the one
  that satisfies all three: exhaustion escapes as a new `domain/StoreContendedException`, beside
  `StoreUnavailableException` and `StoreRefusedRowException`, and `RegisterStore`'s `@throws` says
  what it is and that the **pass**, not the port, decides between reporting it as a pass that
  released nothing and rethrowing it. `recordAndComplete` keeps `ConcurrencyFailureException`
  deliberately: its contention is settled by the listener's `catch (RuntimeException)` and never
  crosses into `batch/`.
* **The coverage figures at both phase closes had never been measured on the trees they describe**
  (MEDIUM at `code-reviewer`, `qa`, `spec-validator` and Codex). Phase 2's are requoted below from
  a report regenerated by `jacocoTestReport build` on the tree that carries the fix. Phase 1's
  cannot honestly be requoted — its tree is two remediations gone — so that close now states the
  gate that actually ran, and no ratios. The convention at the top of this file names
  `jacocoTestReport build`, in that order, so the trap does not recur.
* **This range had no full-build evidence at all**: `build/` held one filtered red run and a report
  from 2026-09-15. Closed by the run recorded below, whose XMLs are left in place.

Six LOW findings were closed here as well, all of them raised by more than one reviewer:

* `the_mark_and_the_release_are_one_transaction` accepted any `RuntimeException`. It now names
  `DuplicateKeyException` **and the index that refused it**, which is also what pins the other half
  of the retry: a refusal on a key other than the active-register one is rethrown as itself rather
  than made again three times.
* the inclusive `<=` staleness boundary the spec decides was asserted nowhere, because every case
  ages a batch by a duration against a cutoff taken from its own clock and the two can never be the
  same instant. A `stampBatch` fixture writes both in-flight stamps to an instant the case names,
  and `a_batch_stamped_exactly_at_its_cutoff_is_stale` puts one batch on the cutoff and one a
  second inside it.
* "never matched" pinned GENERATED alone, while the predicate is PENDING and GENERATING and nothing
  else. `no_batch_a_run_has_finished_with_is_ever_matched_at_any_age` stands an aged FAILED batch
  and an aged NOTIFIED one beside the day still waiting: re-failing the first would overwrite the
  reason support reads and hand back registers a resend may be about, and the second would say a
  night that worked did not.
  All three are at `2d2413c`, and all three are mutation-checked: widening the predicate to admit
  FAILED and NOTIFIED and narrowing `<=` to `<` fails four cases, the two new ones among them.
* `plan.md`'s Atomicity row said "exactly one live batch per key" where the suite asserts at most
  one — a released day has none until it is re-assembled — and did not name the third contender.
  Both corrected.
* the hard ordering above read T008→T009→T010; the phase was executed, and had to be executed,
  T008→T010→T009. Corrected.
* T009's narrative now records that per-method outage translation for this method is covered by
  `StoreOutageTest` plus inspection rather than by a case of its own.
* `spec.md`'s FR-009 now carries the decision T009's narrative had been the only record of: the
  registers counted are those still the day's to render, and one a re-share superseded during the
  release is not among them because nothing will re-batch it.

Two LOW findings are left, each with its reason, for the reviewers to re-judge: Codex's note that
`COALESCE(requested_at, assembled_at)` leans on a timestamp/state shape neither the columns nor
`RegisterBatchRepository.insert` enforce — latent, reachable only through a row no ordinary flow
writes, and the fix is a constraint rather than a predicate, so it belongs with the schema work
rather than inside this phase's statement; and Codex's note that
`StaleReleaseConcurrencyIT`'s `allSatisfy` on the losing contender's escapes passes on an empty
list and that live-batch uniqueness is read after settlement — both are about the two committed
rounds' assertions rather than about the pass, and tightening them means teaching the fixture which
contender was let go first, which is what `Order` deliberately does not decide for the `TOGETHER`
rounds.

**Green after the remediation**: `flock -w 7200 … ./gradlew jacocoTestReport build
-Dtest.noFailFast=true` BUILD SUCCESSFUL, exit 0, 10m 14s, **3650 tests over 579 suites, 0 failures,
0 errors** — three more than the superseded close, being `StaleReleaseConcurrencyIT`'s third case
and `RegisterStoreIT$StaleRelease`'s two — with `checkstyleMain` and `checkstyleTest` at
`maxWarnings = 0`, `pmdMain`, `pmdTest` and `jacocoTestCoverageVerification` all green and none of
them loosened. The coverage report was regenerated in that same run and reads **LINE 6572/6782 =
0.9690 and BRANCH 1994/2218 = 0.8990** against the unchanged gate of LINE 0.88 / BRANCH 0.85; it
contains `failAndReleaseStale`, which is how a reader can tell it is this tree's report and not an
earlier increment's. That run was made on the tree this gate's five commits produce, before this
record was written into it; the same command was then made again against the tree **as committed**
and answered identically — BUILD SUCCESSFUL, exit 0, 10m 19s, the same 3650 over 579 and the same
two counters — and it is that second run's XMLs and report that are left in `build/` for the next
gate to read.

**Review gate 3 ran against the same phase again**, and every reviewer's remaining finding turned
on one question gate 2 had left open: what the operation does with a batch it cannot release. The
design owner settled it on 2026-09-20, and the settlement changed the operation's shape rather than
only its `@throws` line.

* **The unit of atomicity was the whole pass, and it should be one batch** — the finding gate 2's
  fix had made visible without closing. `9811570` retried the statement on the active-row key, but
  the statement was still one `UPDATE` over *every* stale batch: a refusal met on one court centre's
  registers rolled back every other court centre's release with it, and an exhausted retry then left
  the run with an exception. Two different ways for one hearing shared at the wrong moment to cost
  the whole country its documents, and FR-003a forbids both.
  The staleness predicate is now read once, into a list that **decides nothing** — every batch it
  names is judged again by the statement that writes its row, so a batch that stopped being stale in
  between matches nothing exactly as before — and each batch is then failed and released by a
  statement of its own, narrowed by batch id, in its own transaction, with its own bounded retry.
  The fence is unchanged and deliberately so: the rule stays in the `UPDATE`'s own `WHERE`, which is
  where READ COMMITTED re-evaluates it against the row as it stands.
* **Exhaustion is reported, never thrown.** A batch whose every attempt met the same refusal is left
  exactly as it was found and named on `StaleReleaseOutcome.contended()`; the operation goes on to
  the batches after it and answers normally. `StoreContendedException`, which gate 2 had introduced
  for the opposite decision, has no writer and no reader and is deleted. The port's javadoc and
  FR-003a say what happens instead, and T011/T012 carry the pass's half of it: the contended batches
  are counted, said at WARN, and the run goes on to assemble.
  The gate-2 reasoning that produced the exception is not wrong and is worth keeping in view — a
  `batch/` class may name no `org.springframework.dao` type, so the persistence layer does have to
  translate. What changed is that there is now nothing to translate: the operation no longer has a
  failure to hand up, because no one batch's ending is the operation's ending.
* **The pinning test, and one deviation from the decision's letter.** The decision named a
  `StaleReleaseConcurrencyIT` round committing a fresh re-share inside every one of the
  `RECORD_ATTEMPTS` windows, staged with the `INSIDE_THE_WINDOW` fixture. That was **built and
  abandoned**, and the reason is recorded here because it is a fact about the fixture rather than a
  preference. Chaining the windows means the holder of window *k+1* must own the batch row before
  the attempt that follows window *k* asks for it, and the gap between a refused attempt's rollback
  and the next holder's grant cannot be closed from the test: the attempt is a client round trip and
  the grant is a server wakeup, and the run recorded window 3's attempt slipping past its holder
  while windows 1 and 2 held. A round that passes on the scheduler's goodwill is not a pin.
  `a_batch_no_attempt_can_release_is_reported_while_the_others_are_released` stages the same refusal
  **as data**: a share of the batch's own hearing stamped *earlier* than the register the release
  would have to give back. The recorder writes it active — a batched register is not its to
  supersede — and the release's successor search is the mirror of the same ordering, so it is never
  a successor. Every attempt meets the second active row for the key, and no fresh snapshot helps.
  That is a share delivered out of order, which a broker that redelivers produces. A second court
  centre's stale batch stands beside it and is failed and released anyway, which is the isolation
  itself; the contended one is named, untouched, and nothing escapes.
  `a_batch_contended_once_is_released_by_the_attempt_that_follows` keeps the windowed fixture for
  the case it is reliable for — one refusal, then the attempt that reads a snapshot the re-share is
  in — and asserts it on the answer rather than on the rows.
  **The red run is the isolation's own evidence.** Against the seam, the out-of-order key did not
  only fail its own round: the store-wide statement carried the refusal into every other suite
  sharing the container, and `RegisterStoreIT$StaleRelease` and all three existing concurrency
  rounds went red with "a register was re-shared inside the statement's own window on each of 3
  attempts … so none of them was released". That is the blast radius the finding is about, observed
  rather than argued. Green after the fix: `RegisterStoreIT` 85 of 85 (`StaleRelease` 13 of 13),
  `StaleReleaseConcurrencyIT` 5 of 5. (The 83 quoted at gate 2 is correct for the moment it
  describes: `2d2413c` added the boundary and finished-batch cases after it.)
  The round gives the held key up when it is done, because the operation answers for the whole
  store: a key left held would have every other suite spend its three attempts on this round's batch
  at every call.
* The LOW findings gate 3 listed were **already closed at gate 2** and were re-checked rather than
  re-done: `DuplicateKeyException` named with its index at `RegisterStoreIT:3378`, the `<=` boundary
  at `a_batch_stamped_exactly_at_its_cutoff_is_stale`, the aged FAILED and NOTIFIED batches at
  `no_batch_a_run_has_finished_with_is_ever_matched_at_any_age`, `plan.md`'s "at most one live batch
  per key", the T008→T010→T009 hard ordering, and Phase 1's close stating the gate that ran rather
  than ratios it never measured.

**Green after gate 3's remediation**: `flock -w 7200 … ./gradlew jacocoTestReport build
-Dtest.noFailFast=true` BUILD SUCCESSFUL, exit 0, 10m 18s, **3652 tests over 579 suites, 0 failures,
0 errors** — two more than gate 2's close, being the two new rounds — with `checkstyleMain` and
`checkstyleTest` at `maxWarnings = 0`, `pmdMain`, `pmdTest` and `jacocoTestCoverageVerification` all
green and none of them loosened. The coverage report was regenerated in that same run and reads
**LINE 6589/6796 = 0.9695 and BRANCH 1998/2220 = 0.9000** against the unchanged gate of LINE 0.88 /
BRANCH 0.85; it contains `failAndReleaseStale`, which is how a reader can tell it is this tree's
report. That run was made on the tree this gate's commits produce, before this record was written
into it, and it is the report it regenerated that is left in `build/`. `flock -w 7200 … ./gradlew
build -Dtest.noFailFast=true` was then run against the tree **as committed** and answered
identically - BUILD SUCCESSFUL, exit 0, 10m 5s, the same 3652 over 579 - and it is that run's XMLs
that are left beside the report; `build` runs the coverage gate and not the report, so the ratios
above are the earlier run's measurement of the same code and are not requoted from a second one.

**Review gate 4 ran against the same phase again**, with the three read-only reviewers. It found no
BLOCKER and no HIGH in the code: what it found above LOW was that the design artefacts had been left
behind by gate 3's change of shape.

* **The design artefacts still described the retired shape** (MEDIUM at `code-reviewer`). Gate 3
  changed the operation — the predicate read once into a list that decides nothing, one fenced
  statement per batch in a transaction of its own, exhaustion reported rather than thrown — and
  re-pointed the port's javadoc and FR-003a at it, and nothing else. `data-model.md` still gave the
  return type as `List<ReleasedBatch>` and the shape as "one statement, in one transaction" over
  every stale batch, and its flow diagram, `plan.md`'s summary, inventory and decision list,
  `research.md`'s D2 and `spec.md`'s Assumptions all said the same. All five are re-pointed at
  `cd45160`, and the two dated Clarifications answers are kept as the record of the moment they were
  taken, each with a pointer to what gate 3 narrowed: a Q/A session is history, and history is not
  retro-edited.
* **The refusal no retry can settle crossed the port as a Spring type** (LOW at `code-reviewer`,
  `spec-validator` and the Codex wrapper's own observation — three reviewers, so it was closed
  rather than deferred). `attemptedRelease` rethrew `DuplicateKeyException` on any key but the
  active-register one. The class that has to read it is Phase 3's `StaleBatchReleaser` in `batch/`,
  which may name no `org.springframework.dao` type (constitution Principle V), so it could only have
  caught it as `RuntimeException` — the catch that swallows every programming error beside it, which
  is the same argument gate 2 settled the exhaustion question on. It is translated into
  `domain/RegisterNotReleasedException` with the collision as its cause, which is the idiom
  `recordAndComplete` uses for the same table one operation above, and the port's javadoc now says
  what it is and that it is allowed to end the run: FR-003a is about a batch's **ordinary** ending,
  the lost race for the day's key, and that one is reported and never raised. Red at `87bb6f5` on the
  assertion (`RegisterNotReleasedException` expected, `DuplicateKeyException` was), green at
  `5a26d3d`.
* **Two things the concurrency suite left to goodwill** (LOW at `qa`, the second of them the finding
  gate 2 left open for re-judgement), both closed at `4dc8768`. `assertInvariants` asserts the losing
  contender's escape with `allSatisfy`, which passes on an empty list, so a `markRequested` or a
  `markGenerated` that silently moved nothing against a FAILED batch would have left the staged
  rounds green; in `RELEASE_FIRST` the winner is known by construction, so `theRefusalExists` asserts
  exactly one escape — and only for that order, because which contender won a `TOGETHER` round is the
  decision `Order` deliberately declines to make. Mutation-checked at `hasSize(2)`: the two staged
  rounds go red and the re-share round, whose contender cannot be refused, does not. And
  `letTheKeyGo` ran as the round's last statement, so a read that threw before it would have left the
  out-of-order share holding the day's key for every other suite on the shared container; it runs in
  a `finally` now.
* **The other two settled endings were outside the predicate by construction alone** (LOW at `qa`),
  closed at `5410e9d`. `no_batch_a_run_has_finished_with_is_ever_matched_at_any_age` stood an aged
  FAILED batch and an aged NOTIFIED one beside the waiting day; `PARTIALLY_NOTIFIED` and
  `NOTIFIED_NOBODY` are endings a run has finished with too, and both now stand in the case at the
  same age, walked there by `walkedToSettled`, which takes the tally rather than assuming everybody
  was told. Mutation-checked: admitting either status to the predicate fails the case. The same
  commit corrects the `releaseFailed` class comment, which still said four of **six** reasons leave
  the stamp in place — the enum has been seven since `NOT_COMPLETED_BY_NEXT_RUN`, as the two
  statements it describes already say.
* **Gate 3's green was requoted wrong** (LOW at `spec-validator`): it said `RegisterStoreIT` 83 of 83
  where the XMLs read 85, `StaleRelease` being 13 since `2d2413c`. Corrected above, with the note
  that gate 2's own 83 is right for the moment it describes. The same commit records that
  `9811570`'s exhaustion branch went in untested and was retired untested.

**The whole-increment Codex gate did not run at gate 3, and has not run since.** Every call to
`mcp__codex__codex` on that round — the full review prompt and a one-word probe alike — was refused
with "You've hit your usage limit … try again at 1:00 PM", so that round has no Codex findings, and
the three items above attributed to "the Codex wrapper" are the wrapper's own observations rather
than Codex's. **No Phase 2 close and no Phase 3 start may be recorded on the strength of gate 3 or
gate 4**: the Codex leg of this range's gate is unmet and is owed a run. Nothing in the wrapper's own
checks blocks it — tree clean, every commit on `004-release-stale-batches`, no untracked files, every
touched file inside this tree's coordination scope.

Findings left open at this gate, each with its reason:

* ~~**A permanently contended batch has no operator remedy in this increment**~~ (LOW, the
  wrapper's observation) — **withdrawn at the second remediation below, along with the 005 hand-off
  it proposed.** The finding was that a batch whose hearing holds an earlier-stamped active
  unbatched share is reported contended by every run for ever, and that the remedy therefore had to
  come from the operations surface. The design owner settled it the other way on 2026-09-20: the
  release decides the share instead, so the condition no longer exists and there is nothing to hand
  off. Nothing is owed to 005 by this.
* **`STALE_BATCHES`' `batch_id` tiebreak is unasserted** (LOW at `qa`, marked optional there). The
  ordering case proves `register_date` with two days; proving the tiebreak needs two live batches on
  one date, which `idx_register_batch_live_key` allows only at two different court centres — and
  every `mine*` filter in `RegisterStoreIT` is written against the one court centre a case speaks
  for. That is fixture work of its own rather than an assertion, and the ordering it would pin is a
  presentation detail of a list the pass logs.
* **No case drives `failAndReleaseStale` over a broken `DataSource`** (LOW at `qa`). Outage
  translation for it is covered by `StoreOutageTest` plus the call site, as T009's narrative already
  records; the stronger proof belongs with Phase 3's `StaleBatchReleaserTest`, which owns the
  caller's half.
* ~~**Nothing pins that the call is made outside a transaction**~~ (LOW at `qa`, raised again as a
  MEDIUM at the Codex gate) — **closed at the Codex remediation below**, and closed the other way
  round from the reasoning recorded here. The finding was that the javadoc states the precondition
  and nothing enforces it, so a Phase 3 caller wrapping the pass in a `TransactionTemplate` would
  turn every retry into "current transaction is aborted" with no test going red. That was left for
  T011/T012 on the grounds that the guard belongs where the caller is. Codex's objection is the
  better one: a precondition nothing enforces is a comment, and the store is where the boundary can
  be taken rather than asked for. Each attempt now runs `REQUIRES_NEW`, and
  `a_release_is_committed_though_the_callers_transaction_rolls_back` pins it.
* **`COALESCE(requested_at, assembled_at)` leans on a timestamp/state shape the columns do not
  enforce** (LOW at Codex, gate 2, left open there for the same reason). Latent, reachable only
  through a row no ordinary flow writes, and the fix is a constraint rather than a predicate, so it
  belongs with the schema work.

**Green after gate 4's remediation**: `flock -w 7200 … ./gradlew jacocoTestReport build
-Dtest.noFailFast=true` BUILD SUCCESSFUL, exit 0, 10m 11s, **3652 tests over 579 suites, 0 failures,
0 errors** — the same count as gate 3's close, because this round added assertions to existing cases
and no case of its own — with `checkstyleMain` and `checkstyleTest` at `maxWarnings = 0`, `pmdMain`,
`pmdTest` and `jacocoTestCoverageVerification` all green and none of them loosened. The coverage
report was regenerated in that same run and reads **LINE 6592/6799 = 0.9696 and BRANCH 1998/2220 =
0.9000** against the unchanged gate of LINE 0.88 / BRANCH 0.85; it contains `failAndReleaseStale`,
which is how a reader can tell it is this tree's report. That run was made on the tree this gate's
commits produce, before this record was written into it, and it is the report it regenerated that is
left in `build/`. `flock -w 7200 … ./gradlew build -Dtest.noFailFast=true` was then run against the
tree **as committed** and answered identically - BUILD SUCCESSFUL, exit 0, 10m 10s, the same 3652
over 579, 0 failures and 0 errors - and it is that run's XMLs that are left beside the report;
`build` runs the coverage gate and not the report, so the ratios above are the first run's
measurement of the same code.

**Gate 4's two MEDIUM findings were closed in a second remediation**, after the gate's own
artefact fixes above. Neither is a BLOCKER and neither changed what the operation is for; one is an
assertion the suite was leaving to timing, and one is a decision the design owner took about a batch
that could never be released.

* **The re-check the whole fence rests on was pinned only by luck** (MEDIUM at `qa`,
  `StaleReleaseConcurrencyIT`). The statement is safe because of a rule of READ COMMITTED - an
  `UPDATE` that waited on a row re-evaluates *its own* `WHERE` against the version it is granted -
  and that is why the staleness rule is written in the `UPDATE`'s predicate rather than in a clause
  that feeds it. What asserted it was the `TOGETHER` rounds, where the two contenders have to land
  inside the same handful of microseconds for the re-check to be reached at all: a property held at
  the scheduler's discretion is not a property.
  Closed at `f4fe47f` by two rounds with no timing in them, built on the `holdingTheBatchRow`
  fixture the re-share round already uses. A session takes the batch row `FOR UPDATE`; the pass
  reads `STALE_BATCHES` and blocks on its write; the **holder's own transaction** then moves the
  batch - `markRequested` on a PENDING one, `markGenerated` on a GENERATING one - and commits. The
  pass is granted a row version the rule no longer matches and changes nothing about it, which is
  asserted as three separate claims: the batch is not released, it is not reported **contended**
  either (a batch nothing was refused over lost no race), and the row stands exactly where the
  holder left it with both its registers still stamped to it.
  Red first, against the mutation the finding is about - the predicate lifted out of the `UPDATE`
  into a preceding `candidate` CTE, which is the read-then-mark shape written as one statement:
  `./gradlew test --tests '*StaleReleaseConcurrencyIT*' -Dtest.noFailFast=true`, 7 tests,
  **3 failures**, 0 errors. The render round failed **4** assertions ("expected:
  Ending[status=GENERATING, failureReason=null] but was: Ending[status=FAILED,
  failureReason=NOT_COMPLETED_BY_NEXT_RUN]", the batch named in `released`, "expected: 2L but was:
  0L" on its stamped registers, and the suite's own `prematurelyFailed` reading at 1) and the
  document round **2**; one `TOGETHER` round of an existing case went red beside them, which is the
  same defect found the old way. Mutation reverted. Green against the tree as committed: 7 of 7,
  `checkstyleTest` and `pmdTest` green - `() -> {}` rather than `() -> { }`, which `WhitespaceAround`
  refuses.
* **A batch whose key held a share it had overtaken could never be released** (MEDIUM at
  `code-reviewer`, `JdbcRegisterStore` ~953) - **a design decision, taken by the design owner on
  2026-09-20**, and the finding gate 4 had recorded as an accepted permanent condition with a 005
  hand-off. A share of the hearing delivered *behind* the register a batch already holds is recorded
  active and unbatched, because the recorder's incumbent search is over unbatched rows and a batched
  register is not its to supersede. It therefore holds `idx_output_active_register_key` for the day,
  the release's successor search is the mirror of the same ordering and never finds it, and the
  release is refused on every attempt - by every run, for ever, because no fresh snapshot removes a
  row committed before the statement began.
  The rule now reads the same total order **both ways**, which is what `recordAndComplete` already
  does one operation above: the register coming back is the later share, so the release supersedes
  the earlier active unbatched row against it (`superseded_by` = the register coming back) in the
  same statement, while a later-stamped active row supersedes the register coming back as before.
  Latest share wins in both directions, and the key keeps exactly one active row whichever of the
  two writers is the one that finds the pair together.
  Pinned by `RegisterStoreIT$StaleRelease.a_release_supersedes_the_share_it_overtook`; the
  later-stamped direction is `a_release_supersedes_against_a_later_re_share`, unchanged. Red at
  `5ca98f0` on **6** assertions, every one of them an assertion and not a refusal, because the
  contention is reported rather than thrown: the batch left `GENERATING` instead of FAILED under the
  new reason, its id in `contended`, the overtaken share's `superseded_by` empty, the key holding
  `["RECORDED", "RECORDED"]`, the wrong register left active and unbatched, and nothing in
  `released`. Green at `8d565ea`: `RegisterStoreIT` 86 of 86, `StaleReleaseConcurrencyIT` 7 of 7.
  **The supersession is chained ahead of the release, and that is a correctness point rather than a
  style one** - the same requirement `RECORD_REGISTER` has for the same index. The overtaken row
  holds the key until its update takes it out of the index, so a release issued first collides with
  the very row it is about to supersede; the release's source therefore counts the supersession's
  rows, because Postgres does not otherwise say which clause of one statement runs first.
  Mutation-checked: with the chain removed the new case fails on all six assertions again, three
  runs out of three.
* **The exhaustion round had to be re-staged, and the reason is worth recording.** Gate 3 staged
  `a_batch_no_attempt_can_release_is_reported_while_the_others_are_released` **as data** - an
  out-of-order share holding the key against every attempt - precisely because chaining three timed
  windows could not be made reliable. That share is exactly what the decision above makes
  releasable, so the round would have stopped being about contention at all. And there is no
  arrangement of rows that replaces it: any active row for the key that is committed before the
  statement begins is in its snapshot, so the release either supersedes it or is superseded against
  it. The only refusal left is a share committing **after** the snapshot and before the write, on
  each of the three attempts.
  So it is staged by the database rather than by the scheduler: an `AFTER UPDATE` trigger, scoped by
  `WHEN` to the round's own hearing and dropped in a `finally`, takes the day's active register back
  the moment the release gives its own up - an existing superseded share of the same key, invisible
  to the statement's snapshot, made active again inside the attempt's own transaction. Every attempt
  meets `idx_output_active_register_key` and rolls back with it, which is what a re-share committing
  inside each window produces. **What is staged is the refusal; what is asserted is what the
  operation does with a batch it cannot release**, which is the contract the round exists for, and
  the round's own assertions are what prove the exhaustion path was reached - a trigger that did
  nothing would leave the batch released and `contended` empty. Scoped and dropped in the idiom
  `withOneUnbatchedRegisterAllowed` already uses in `RegisterStoreIT`, so no other suite sharing the
  container can see it, and nothing is left held afterwards: the share stays superseded, so the next
  run releases the batch like any other. The `letTheKeyGo` fixture gate 3 needed for that is
  therefore deleted.

The port's javadoc, `data-model.md`, `spec.md`'s edge-case list and `plan.md`'s Atomicity row are
re-pointed at the decision in the same commits, and the `COALESCE` note and the three remaining LOW
findings above are untouched by any of it.

**Green after the second remediation**: `flock -w 7200 … ./gradlew jacocoTestReport check
-Dtest.noFailFast=true` BUILD SUCCESSFUL, exit 0, 10m 16s, **3655 tests over 579 suites, 0 failures,
0 errors** — three more than gate 4's close, being the two staged re-check rounds and the overtaken
share — with `checkstyleMain` and `checkstyleTest` at `maxWarnings = 0`, `pmdMain`, `pmdTest` and
`jacocoTestCoverageVerification` all green and none of them loosened. The coverage report was
regenerated in that same run and reads **LINE 6592/6799 = 0.9696 and BRANCH 1998/2220 = 0.9000**
against the unchanged gate of LINE 0.88 / BRANCH 0.85; it contains `failAndReleaseStale`, which is
how a reader can tell it is this tree's report. The ratios are unchanged from gate 4's because the
decision is a change to one statement's SQL rather than to any Java branch, and the three new cases
cover code that was already covered. **Which run left which artefact**: gate 5 read the report and
the results this close left in `build/` and found them to be two runs of the same source - the
coverage report from the run quoted here, and the test XMLs from a later run of the same suite,
which answered identically at 3655 over 579 with 0 failures and 0 errors. Either describes the tree
as committed, so the verdict above stands under both, and the third remediation below says the same
about its own two runs.

**Gate 5's two MEDIUM findings were closed in a third remediation.** Neither is a BLOCKER and
neither changed what the operation does: one is a claim the suite was leaving to two random
identities, and one is a promise the port's javadoc was making more widely than the statement kept
it.

* **The per-batch isolation was asserted at UUID's discretion** (MEDIUM at `qa`,
  `StaleReleaseConcurrencyIT`). `a_batch_no_attempt_can_release_is_reported_while_the_others_are_released`
  is the round that says the pass **goes on** to the batches after one it could not release - and it
  stood both of its batches on the same day. `STALE_BATCHES` answers `ORDER BY register_date,
  batch_id`, so which of the two was reached first was decided by two random identities, and a
  regression that stopped the walk at a contended batch - an early return, a `break`, or an
  exception on exhaustion - would have been caught only on the runs where the contended one
  happened to be walked first.
  Closed at `e4d1cf2` by standing the batch that must be released anyway on the **day after** the
  contended one: `staleBatch` and `batchFor` take the register date the round wants, the second
  court centre's registers are shared on `TUESDAY_SHARED`, and the walk therefore reaches it after
  the contended batch on every run. Nothing else about the round changed.
  Mutation-checked against the regression it is about - `eachStaleBatch` given a `break` as soon as
  a batch comes back contended: `./gradlew test --tests '*StaleReleaseConcurrencyIT*'
  -Dtest.noFailFast=true`, 7 tests, 1 failed, and that one failed on **3** assertions - the other
  court centre's batch still `Ending[status=GENERATING, failureReason=null]` where
  `FAILED/NOT_COMPLETED_BY_NEXT_RUN` was expected, its id absent from `released`, and `2L` registers
  still stamped to it where `0L` was expected. **Three runs out of three**, which is the point: the
  day is what makes the order a property rather than a coin. Mutation reverted; 7 of 7 green with
  `checkstyleTest` and `pmdTest`.
* **A refusal that is no key at all crossed the port as a Spring type** (MEDIUM at `code-reviewer`,
  `RegisterStore` ~479). The port promised that no `org.springframework.dao` type reaches it, and
  `attemptedRelease` translated only `DuplicateKeyException`: a `DataIntegrityViolationException`
  that is not one - a CHECK the bounded reason does not satisfy, which is what a pod running against
  a store `V6` never reached meets on **every** stale batch - was caught by nothing and crossed into
  `batch/`, where Phase 3's pass may name no such type and could only have read it as
  `RuntimeException`, the catch that swallows every programming error beside it.
  Closed in two halves. The **translation** at `0e99ef1`: a second `catch` arm after the
  `DuplicateKeyException` one hands the refusal to the same `unaccountedForRelease`, which now takes
  the wider class, and it is not attempted again - a rule is not a race. Red at `9ee0d74` with
  `RegisterStoreIT$StaleRelease.a_refusal_that_is_not_a_key_at_all_is_the_domains_own_class_too`,
  which narrows a CHECK onto this case's court centre (`NOT VALID`, dropped in a `finally`, the
  idiom `withOneUnbatchedRegisterAllowed` already uses) and asserts the class that escapes: 87 tests,
  1 failed, **2** assertions, both of them assertions - `RegisterNotReleasedException` expected and
  `DataIntegrityViolationException` was, and the same on the cause - while the other two claims
  passed red, because one statement rolls back whole and the mark went down with the release either
  way. Green at `0e99ef1`.
  And the **claim itself**, in the same commit: the port now says that no *refusal* crosses as a
  Spring type, and says what does - the store's own contention signal, a deadlock or a serialisation
  failure, which `StoreOutage.translating` hands on unchanged from **every** method on this port and
  which is the run's ordinary transient failure rather than anything this operation decides about.
  That is the adapter's store-wide policy (`recordAndComplete` raises a `ConcurrencyFailureException`
  of its own one operation above), and it is stated at the port so Phase 3 is not written against a
  promise the package does not make. The `@throws` clause that had wrapped into a five-word column
  is re-flowed in the same commit, which is gate 5's other cosmetic LOW.

**Four of gate 5's LOW findings are closed beside them**; three are left open with their reasons.

* `plan.md`'s inventory and test-matrix row said the fenced statement calls `attributionOf` with
  `null`, which T009 found it does not: it writes `completed_by = NULL` directly and
  `register_batch_completed_by_shape_chk` is what refuses an attribution. Both lines are re-pointed,
  and the matrix row now names `the_failure_names_no_completion_mechanism` as the pin, the overtaken
  share and gate 5's own two additions.
* The two test-only commits that recorded no mutation now have one each.
  **`e1ac365`** (the fixture settles the night, so the aggregate invariant is not vacuous): with
  `settleTheNight` made to return at once, `a_render_acceptance_racing_the_release_leaves_no_stranded_register`,
  `a_document_arrival_racing_the_release_leaves_no_stranded_register` and
  `a_re_share_racing_the_release_is_superseded_rather_than_unstamped` all go red on the aggregate
  assertion - "Expecting actual: [] to contain exactly (and in same order): [1L]" - 3 of 7 failed.
  **The re-staged exhaustion round's trigger**: with its `WHEN` clause re-pointed at a hearing
  nothing matches, so the trigger fires on nothing, the round goes red on **4** assertions -
  `contended` empty where the batch was expected, its id in `released`, its ending
  `FAILED/NOT_COMPLETED_BY_NEXT_RUN` where `GENERATING` was expected, and `0L` stamped registers
  where `2L` were. So the trigger is what stages the refusal, and the round's own assertions are
  what prove the exhaustion path is reached. Both mutations reverted.
* **Left open, and why.** (1) *The lost-then-won retry leaves no trace* - `JdbcRegisterStore` has no
  logger at all and is deliberately silent; giving one operation a log line is a change to the
  package's shape rather than to this statement, and it belongs with whatever gives the whole
  adapter a voice. (2) *Nothing pins that a `RegisterNotReleasedException` on one batch leaves the
  batches released before it committed* - `RegisterStoreIT`'s fixtures are single-court-centre by
  construction (`mine`, `mineReleased` and `mineContended` all filter on the case's own court
  centre), so the case wants a second court centre read by hand, and the per-batch **transaction**
  is the same property the concurrency suite's round now asserts deterministically on the ordinary
  path. (3) The three LOW findings gate 4 left open are untouched, as before.

**Green after the third remediation**: `flock -w 7200 … ./gradlew jacocoTestReport check
-Dtest.noFailFast=true` BUILD SUCCESSFUL, exit 0, 10m 18s, **3656 tests over 579 suites, 0 failures,
0 errors** — one more than the second remediation's close, being the refusal that is no key — with
`checkstyleMain` and `checkstyleTest` at `maxWarnings = 0`, `pmdMain`, `pmdTest` and
`jacocoTestCoverageVerification` all green and none of them loosened. The coverage report was
regenerated in that same run and reads **LINE 6594/6801 = 0.9696 and BRANCH 1998/2220 = 0.9000**
against the unchanged gate of LINE 0.88 / BRANCH 0.85; it contains `failAndReleaseStale`, which is
how a reader can tell it is this tree's report. That run was made on the tree these commits produce,
before this record was written into it, and it is the report it regenerated that is left in
`build/`. `flock -w 7200 … ./gradlew build -Dtest.noFailFast=true` was then run against the tree
**as committed** and answered identically - BUILD SUCCESSFUL, exit 0, 10m 10s, the same 3656 over
579, 0 failures and 0 errors - and it is that run's XMLs that are left beside the report; `build`
runs the coverage gate and not the report, so the ratios above are the first run's measurement of
the same code.

**The Codex leg is still owed and is still unmet.** Nothing in this remediation changes that: the
whole-increment Codex gate has not run since gate 2, and **no Phase 2 close and no Phase 3 start may
be recorded until it has**. *(Superseded by review gate 6 below, which is that run.)*

---

## Review gate 6 — the Codex leg, against Phase 2 (2026-09-20)

**The Codex leg owed since gate 2 has now run** against Phase 2 as committed at `68e3cae`. It
returned **two findings, no BLOCKER of its own beyond the first, and nothing else above LOW**. Both
are closed in the two test/fix pairs below, test-first, and both were closed in the adapter rather
than deferred to a caller.

* **The driver's account of a refused register travelled on the refusal** (BLOCKER at Codex,
  Principle VII). `RegisterNotReleasedException` carried the store's own
  `DataIntegrityViolationException` as its cause. `FAIL_AND_RELEASE_STALE` updates
  `processed_output`, and a `processed_output` row holds the register document itself, so Postgres
  reporting the refusal by quoting the row — `Failing row contains (...)` for a CHECK,
  `Key (...)=(...) already exists` for a unique index — puts a youth defendant's name and date of
  birth on the driver's message. The failure is raised out of the nightly run and written at ERROR
  into the estate's log index, and a stack trace is written whole. `persistence/StoreOutage` already
  discards an integrity refusal's cause for exactly this reason, so the release was the one place
  the rule was not kept.
  **Closed** by carrying a bounded classification written in this repository — `a unique key`, or
  `an integrity rule that is no key` — plus the batch's identity, and no cause at all. The
  constraint's own name goes with the cause: the only safe idiom this class has for naming one,
  `violates(DuplicateKeyException, String)`, asks the driver whether the refusal is a key *this
  class already knows by name*, and a refusal that reaches this translation is by construction none
  of them, so extracting a name would mean reading the message that may not be kept.
  **Red** (`42b7825`, the two `RegisterStoreIT` cases that had *required* the unsafe cause
  re-pointed at its absence): `flock -w 7200 … ./gradlew test --tests '…RegisterStoreIT'
  -Dtest.noFailFast=true` → **87 tests completed, 2 failed**, 4 assertion failures each and no
  compile error — the classification absent from the message, `hasNoCause` unmet, an
  `org.springframework.dao` type in the chain, and the driver's words in what the chain says.
  **Green** (`e6deb85`): the same command, BUILD SUCCESSFUL, 87 of 87, with `checkstyleMain` and
  `pmdMain` green.
  The two cases now walk the whole cause chain rather than the exception alone — a later change
  re-attaching the refusal underneath would otherwise leave them green — and assert against both
  detail lines Postgres uses, the court centre, and the fixture's `SMITH, John` / `2008-04-11`.
  `TelemetryPrivacyTest` was **not** extended: it is a Spring-context test over the intake pipeline's
  log lines and has no hook for an exception a store raised, so the assertion is made where the
  refusal is produced.

* **The per-batch isolation held only for a caller outside a transaction** (MEDIUM at Codex; the
  same thing `qa` raised as a LOW at gate 5 and this file left open for T011/T012). `release()` ran
  straight at the `JdbcClient` with no boundary of its own, and the javadoc asked not to be wrapped.
  A caller inside a transaction would have every attempt join it: the first refusal aborts that
  transaction, the attempts after it are made inside an aborted one, and every court centre already
  released is rolled back at the end — FR-003a's run-ending outcome reached by obeying the port
  rather than by breaking it.
  **Closed** by taking the boundary instead of asking for it. Each attempt runs through a second
  `TransactionTemplate` at `PROPAGATION_REQUIRES_NEW`, so the caller's transaction is suspended for
  the length of an attempt and resumed after it. `JdbcRegisterStore` now takes the
  `PlatformTransactionManager` rather than a template, because it needs two boundaries over the one
  data source and only one of them is the ordinary kind; `ProcessedLogConfig` and the four test
  construction sites hand it the manager, and `ProcessedLogTestSupport.transactionManager()` and
  `ReportReadsDatabase.transactionManager()` are the fixtures' half of that.
  **Red** (`aa44357`, `StaleReleaseConcurrencyIT.a_release_is_committed_though_the_callers_transaction_rolls_back`):
  `flock -w 7200 … ./gradlew test --tests '…StaleReleaseConcurrencyIT' -Dtest.noFailFast=true` →
  **8 tests completed, 1 failed**, 5 assertion failures and no compile error — what escaped was
  `org.springframework.jdbc.UncategorizedSQLException … SQL state [25P02]`, which is
  "current transaction is aborted" exactly as the finding predicted, and with it the released
  batch's ending, its stamps and both halves of the account.
  **Green** (`51307fd`): `flock -w 7200 … ./gradlew test --tests
  'uk.gov.hmcts.cp.yotresultsdistribution.persistence.*' -Dtest.noFailFast=true` BUILD SUCCESSFUL, **427
  tests, 0 failures, 0 errors**, with `checkstyleMain` and `pmdMain` green.
  The round stands the released batch on the **earlier** day — the reverse of the exhaustion
  round's staging — so it is walked *before* the batch no attempt can release, and reads every row
  back **after** the surrounding transaction has ended, which is what makes "committed" the claim
  rather than "written". The port javadoc now says the separation is the implementation's to keep
  rather than the caller's to remember.

**What the exhaustion round's trigger actually is, recorded because Codex accepted it on this
description.** `withTheKeyTakenBackInsideEveryAttempt` is **deterministic failure injection at the
exception/retry boundary**, not a literal three-commit race. It stages the refusal the retry is
bounded for — the day's active-register key taken back inside every attempt — with an
`AFTER UPDATE` trigger scoped by `WHEN` to the round's hearing, which makes an existing superseded
share of the same key active again the moment the release clears the stamp it is giving back,
inside the attempt's own transaction. Chaining three genuine commit windows by timing was built and
abandoned at review gate 3: the gap between a refused attempt's rollback and the next holder taking
the row cannot be closed from the test. What is *staged* is the refusal; what is *asserted* is what
the operation does with a batch it cannot release, and that is the contract the round is for.

**Green after the Codex remediation**: `flock -w 7200 … ./gradlew build -Dtest.noFailFast=true`
BUILD SUCCESSFUL, exit 0, **3657 tests over 579 suites, 0 failures, 0 errors** — one more than gate
5's close, being the round that calls the pass from inside a caller's transaction — with
`checkstyleMain`, `checkstyleTest`, `pmdMain`, `pmdTest` and `jacocoTestCoverageVerification` all
green and none of them loosened. The coverage report regenerated by the preceding
`jacocoTestReport check` run reads **LINE 6599/6807 = 0.9694 and BRANCH 1999/2222 = 0.8996** against
the unchanged gate of LINE 0.88 / BRANCH 0.85.

That `jacocoTestReport check` run is also what caught the one thing this remediation got wrong on
the way: `ReportReadsDatabase`'s new accessor and the field behind it were given the same name, and
`pmdTest`'s `AvoidFieldNameMatchingMethodName` refused it. The field is
`platformTransactionManager` and the accessor is `transactionManager()`; the suite, the coverage
gate and both Checkstyle tasks were already green in that run, so nothing but the name changed.

**Phase 2 may close on this gate.** The Codex leg is met, both of its findings are closed in the
tree, and the three reviewer legs passed at gate 5 with their remaining findings recorded above.

## Review gate 7 — the workflow leg, against Phase 2 as committed at `c07af34` (2026-09-20)

One HIGH, and it is about the tree rather than the code: *"the tree was left dirty after the last
commit"*, to be closed by committing or removing whatever a shell redirection had left behind.

**It does not reproduce, and nothing was lost closing it.** At `c07af34` — the gate-6 remediation's
last commit — `git status --porcelain` is empty, `git status --porcelain --ignored=matching -uall`
names nothing outside `build/` and `.gradle/`, which `.gitignore` has always carried, and
`git stash list` is empty. The 35 commits of `90001de..c07af34` add no file at the repository root
and touch nothing outside this tree's half of the coordination contract, so no redirection artefact
was committed either. Whatever the gate read was gone before this round opened; there was no file
to commit and none to remove, and the round therefore changed no code.

**Re-verified green at `c07af34`**: `flock -w 7200 … ./gradlew build -Dtest.noFailFast=true`
BUILD SUCCESSFUL, exit 0, **3657 tests, 0 failures, 0 errors** — the same count gate 6 closed on —
with `checkstyleMain`, `checkstyleTest`, `pmdMain`, `pmdTest` and `jacocoTestCoverageVerification`
all green, and `git status --porcelain` still empty after the build.

## The two MEDIUM findings the Phase 2 gate left, closed before Phase 3 opened (2026-09-20)

Neither is a BLOCKER and neither changes what the release is for. One is a refusal the translator
does not know about, and one is a supersession the fenced statement learned at gate 4 and the two
older statements beside it did not.

* **A store lost between the read and a per-batch transaction escaped as a Spring type.**
  `StoreOutage.translating` names `org.springframework.dao` classes only, and since the Codex
  remediation each stale batch's release takes a `REQUIRES_NEW` boundary of its own *inside* that
  translation. A store that goes away in the gap between the `STALE_BATCHES` read and an attempt's
  `getTransaction` therefore refuses with
  `org.springframework.transaction.CannotCreateTransactionException`, which is outside every branch
  the translator lists: it crossed the port as itself, contrary to `failAndReleaseStale`'s own
  `@throws StoreUnavailableException`, and reached a pass in `batch/` that may name no Spring type
  at all (constitution Principle V) and could only have caught it as `RuntimeException`.
  **Closed** by a fourth branch: a `TransactionException` is the store going away exactly as a
  failure to acquire a connection is, so it becomes `StoreUnavailableException` carrying the
  statement's own name and the cause, and none of the driver's words.
  *(The second finding's record is below this one.)*
  **Red** (`StoreOutageTest.a_transaction_that_cannot_be_begun_becomes_the_domains_own_signal`, a
  `PlatformTransactionManager` whose `getTransaction` throws): `flock -w 7200 … ./gradlew test
  --tests '*StoreOutageTest*' -Dtest.noFailFast=true` → **8 tests completed, 1 failed**, one
  assertion and no compile error — "Expecting actual throwable to be an instance of:
  uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException but was:
  org.springframework.transaction.CannotCreateTransactionException".
  **Green**: the same command with `checkstyleMain`, `checkstyleTest`, `pmdMain` and `pmdTest`
  beside it, BUILD SUCCESSFUL, **8 of 8**, all four analysis tasks green. The branch is the outage
  arm rather than one of its own, because a store that will not begin a transaction has said the
  same thing as a store that will not give a connection; `translatingWrite`'s refusal arm is
  untouched, and no other suite in this repository names a `org.springframework.transaction` type.

* **The overtaken share was decided by the fenced statement alone, and by neither statement beside
  it.** Gate 4 taught `FAIL_AND_RELEASE_STALE` to read the key's total order both ways, so a share
  the batched register *overtook* - a delivery that arrived behind the register it belongs in front
  of, which the recorder writes active because a batched register is not its to supersede - is
  superseded against the register coming back. `MARK_FAILED`'s released branch (statement 9, the one
  `:releaseRows` selects) and `RELEASE_FAILED` (statement 9a) still read it one way only: they clear
  the stamp beside the earlier active row, `idx_output_active_register_key` refuses the second active
  row for the day, and the whole statement goes down with it - taking the failure mark with it in
  statement 9's case, and an operator's `release-batch` in 9a's. One rule about one index, kept in
  one statement out of three.
  **Closed** by folding the same `overtaken` clause, chained ahead of the release exactly as it is in
  the fenced statement and for the same index, into both. In statement 9 it is guarded by
  `:releaseRows` through `stamped`, which already carries that guard, so a reason that keeps the
  stamp supersedes nothing.
  **Red** (`RegisterStoreIT`'s twins `Failure.a_failure_that_never_left_should_supersede_the_share_it_overtook`
  and `Releasing.a_release_should_supersede_the_share_it_overtook`): `flock -w 7200 … ./gradlew test
  --tests '*RegisterStoreIT*' -Dtest.noFailFast=true` → **89 tests completed, 2 failed**, **6
  assertion failures each** and no compile error. The first of the six in each is the refusal itself,
  caught as an assertion by the suite's soft-assertion convention - "Expecting code not to raise a
  throwable but caught org.springframework.dao.DuplicateKeyException … `idx_output_active_register_key`"
  - and the five after it are the properties the refusal takes with it: the batch left GENERATING or
  its release answering with nothing, the overtaken share's `superseded_by` empty, the key holding two
  RECORDED rows, the stamp still in place, and the wrong register left assemblable.
  **Green**: `flock -w 7200 … ./gradlew test --tests '*RegisterStoreIT*' --tests
  '*StaleReleaseConcurrencyIT*' checkstyleMain pmdMain checkstyleTest pmdTest
  -Dtest.noFailFast=true` BUILD SUCCESSFUL, **97 tests, 0 failures, 0 errors** — `RegisterStoreIT`
  89 of 89 and `StaleReleaseConcurrencyIT` 8 of 8 — with all four analysis tasks green.
  `stamped` gains the five columns the clause matches on in both statements, which is the whole of
  the change beside the clause itself; nothing else about either statement moved, and the eleven
  existing supersession cases across `Failure` and `Releasing` pass unchanged, which is what says
  the later-stamped direction is untouched.

---

## Phase 3: User Stories 1 and 2 — the pass and its cutoffs (Priority: P1) 🎯 MVP

**Goal**: the class that computes the two cutoffs, asks the store once, and reports what came back.

**Independent test**: `StaleBatchReleaserTest` — a plain object over the store, the metrics, two
durations and a clock. No Spring context, no Docker.

### Tests first ⚠️

- [x] T011 [US1] `batch/StaleBatchReleaserTest` (new) — the call and the account.
      `a_batch_still_generating_past_the_minimum_age_should_be_failed_and_released` (**P2's
      re-pointed pinning test**, driven through the store mock);
      `the_two_numbers_are_batches_and_registers`;
      `one_line_per_released_batch_names_it_by_id_and_nothing_else`;
      `a_pass_that_released_nothing_says_so`;
      `a_contended_batch_is_counted_and_the_pass_goes_on` (**review gate 3**: the store reports a
      batch it could not release on `StaleReleaseOutcome.contended()` rather than throwing, so the
      account the pass keeps has a third number and the pass returns normally with it);
      `the_pass_adopts_the_runs_correlation`. Seam: the class with `releaseStale()` throwing
      `UnsupportedOperationException`. Red: a failing assertion on the first case.
      (red: `flock -w 7200 … ./gradlew test --tests '*StaleBatchReleaserTest*'
      -Dtest.noFailFast=true`, **7 tests, 7 failed**, 0 errors — every failure an assertion, the
      seam's refusal recorded as each case's *first* soft failure in the convention `RegisterStoreIT`
      uses rather than as a stack trace out of the arrangement. The first case's second failure is
      the property under test: "expected: ReleaseTally[batches=1, registers=2, contended=0] but was:
      ReleaseTally[batches=-1, registers=-1, contended=-1]", the sentinel being what a pass that
      answered nothing reads as.
      **Seven cases, not six**: `a_store_that_cannot_be_reached_leaves_the_pass` is the seventh, and
      it is the per-method outage proof review gate 4 deferred from `RegisterStoreIT` to this suite.
      A store that went away is the *run's* failure and not one batch's, so it leaves the pass as the
      port's own `StoreUnavailableException` - asserted `isSameAs`, so a pass that wrapped or
      swallowed it fails - and nothing is counted for a pass that learned nothing.
      **Three seams, and one of them is an instrument.** `batch/StaleBatchReleaser` with
      `releaseStale()` refusing and the nested `ReleaseTally(batches, registers, contended)` record;
      and `GenerationMetrics`' three counters, which a test cannot name before they exist. The seam
      class deliberately holds **no fields**: five fields nothing reads is five `pmdMain` violations,
      so the constructor takes its five collaborators and T012 lands the fields with the code that
      reads them. `GenerationMetricsTest`'s two surface cases gain the three names in the same
      commit, so "exercising everything registers exactly the documented instruments" stays a claim
      about all of them.
      **The third counter is a deviation from the letter of T012's "two counters" and is recorded
      as one.** FR-003a says a contended batch is "counted by the pass's line and its counter", and
      the design rules say a path that leaves something undone moves one; `data-model.md`'s
      instrument table named only the two released totals. So
      `yotresultsdistribution_generation_contended_total` is added beside them, unlabelled - a batch id may
      never be a series (cardinality, and privacy on a register whose every defendant is a child) -
      and `data-model.md` gains its row at T012.
      `checkstyleMain`, `checkstyleTest`, `pmdMain` and `pmdTest` green.)
- [x] T013 [US2] `batch/StaleBatchReleaserTest` (extend) — the two cutoffs, which T012's minimal
      implementation is deliberately allowed not to compute.
      `the_scheduled_cutoff_is_the_clock_minus_the_minimum_age`;
      `the_manual_cutoff_is_the_longer_of_the_minimum_age_and_the_run_lock` (FR-017), with a case
      each way round so neither ordering of the two settings is assumed;
      `a_batch_at_exactly_the_minimum_age_is_stale` (US2.3), asserted on the cutoff argument rather
      than on an outcome, because the boundary lives in the predicate. Red: T012 passes `now` for
      both.
      (red: `flock -w 7200 … ./gradlew test --tests '*StaleBatchReleaserTest*'
      -Dtest.noFailFast=true`, **11 tests, 4 failed**, 0 errors — the four new cases and nothing
      else, every failure an assertion and none of them the seam's: T012 is green, and what these
      four are red against is the minimal implementation's `now` for both cutoffs. "expected:
      2026-09-21T16:30:00Z but was: 2026-09-21T17:00:00Z" for the scheduled cutoff,
      "2026-09-21T15:50:00Z" for the manual one against a seventy-minute lock, the same
      16:30 for the manual one against a **ten-minute** lock - which is the case each way round,
      so an implementation that simply always took the lock fails - and "expected: 30M but was: 0S"
      for the boundary, asserted as the distance from the clock rather than on an outcome, because
      the `<=` itself is the store's and is pinned there.
      `checkstyleTest` and `pmdTest` green; `SHORT_LOCK` is the one fixture the cases add.)

### Implementation

- [x] T012 [US1] `batch/StaleBatchReleaser.java` — make T011 green **and no more**: one call to
      `failAndReleaseStale`, the lines, the two counters, the two numbers back, all under
      `RunCorrelation.under(...)`, which adopts the run's ambient id. The contended batches the
      answer names are counted and said at WARN — a path that drops something moves a counter — and
      the pass returns; the run goes on to assemble what the rest of the pass gave back (FR-003a).
      The split from T013 is deliberate: computing the cutoffs here would leave T013 with nothing to
      fail against.
      (green: `flock -w 7200 … ./gradlew test --tests '*StaleBatchReleaserTest*' --tests
      '*GenerationMetricsTest*' checkstyleMain checkstyleTest pmdMain pmdTest
      -Dtest.noFailFast=true` BUILD SUCCESSFUL, **52 tests, 0 failures, 0 errors** —
      `StaleBatchReleaserTest` 7 of 7 and `GenerationMetricsTest` 45 of 45 — with all four analysis
      tasks green.
      **The split is kept in the fields as well as in the arithmetic**: `staleAfter` and `runLock`
      are constructor parameters here and become fields at T014, because two fields nothing reads
      are two `pmdMain` violations and a deliberately minimal implementation should not have to
      suppress a rule to stay minimal. Both cutoffs are `clock.instant()`, which is exactly what
      T013 is red against.
      **Three counters, and the third is the deviation T011's record states**: the released batches,
      the released registers and `yotresultsdistribution_generation_contended_total`. Every run moves all
      three, by nought where it released nothing, so the series exist to be alerted on from the
      first quiet night rather than appearing the first time something goes wrong.
      **The pass's own lines**: one INFO per released batch naming the batch, the court centre and
      the register date with the count; one WARN per contended batch naming it by identity alone;
      and one INFO summary carrying `released_batches=`, `released_registers=` and `contended=`,
      which is the line a quiet night still writes. All of them carry the run's `runId`, because
      `releaseStale()` runs under `RunCorrelation.under(...)`.
      `data-model.md`'s instrument table gains the contended row in the same commit.
      **One thing the full build then required, and it is Phase 8's work reached early.**
      `TelemetryPrivacyTest`'s `[A]` case reads every meter name `GenerationMetrics` declares and
      fails on one the drive never moved, so three new counters are three unswept series the moment
      they are declared. `support/GenerationLegs` therefore gains `theStaleBatchPass()` - one batch
      given back and one the store could not give back, which moves all three counters and writes
      all three of the pass's lines - and `StaleBatchReleaser` joins `THE_LEGS`, so its lines are
      inside the label and statement sweeps from the commit that wrote them rather than from T039.
      T039/T040 are left with `BatchAgeSweep` alone.
- [x] T014 [US2] `batch/StaleBatchReleaser.java` — make T013 green. Both cutoffs computed once per
      pass from the injected clock and the two settings. The javadoc states the rule the retired
      class stated differently: PENDING and GENERATING are **one** rule, because with no query there
      is nothing to tell them apart; GENERATED is on no arm; and an operator's batch is given the
      longer grace because a manual generation holds no lock and has the whole deadline to work in.
      (green: `flock -w 7200 … ./gradlew test --tests '*StaleBatchReleaserTest*' checkstyleMain
      checkstyleTest pmdMain pmdTest -Dtest.noFailFast=true` BUILD SUCCESSFUL, **11 tests, 0
      failures, 0 errors**, all four analysis tasks green.
      Both cutoffs come off one read of the clock, so every batch in one pass is judged against the
      same moment; the manual one is `max(staleAfter, runLock)` written as a comparison rather than
      as `Duration.max`, which this JDK does not offer. `staleAfter` and `runLock` become fields in
      this commit, which is where the code that reads them lands.
      The javadoc states the rule the retired class stated differently: PENDING and GENERATING are
      **one** rule, because with no query there is nothing that could tell them apart; GENERATED is
      on neither arm at any age; and which of the two cutoffs a batch is judged by is the store's to
      decide from `system_generated`, in the statement's own predicate.)

**Phase close**: `flock … ./gradlew build` green; review gate.
(at the tree carrying T011, T012, T013, T014 and the two Phase 2 gate closures before them,
`flock -w 7200 … ./gradlew jacocoTestReport build -Dtest.noFailFast=true` BUILD SUCCESSFUL, exit 0,
10m 24s, **3671 tests over 580 suites, 0 failures, 0 errors** — fourteen more than gate 7's close,
being `StaleBatchReleaserTest`'s eleven, `StoreOutageTest`'s one and `RegisterStoreIT`'s two twins —
with `checkstyleMain`, `checkstyleTest`, `pmdMain`, `pmdTest` and `jacocoTestCoverageVerification`
all green and none of them loosened. The coverage report was regenerated in that same run and reads
**LINE 6642/6849 = 0.9698 and BRANCH 2009/2228 = 0.9017** against the unchanged gate of LINE 0.88 /
BRANCH 0.85; it contains `releaseStale`, which is how a reader can tell it is this tree's report.
No migration was added and no schema changed: the phase adds one class, three counters and one
clause to two existing statements.
The pass is **not wired into the run**, which is Phase 4's T015-T020: it has no bean, the job's
constructor is untouched, and the only thing that constructs it outside its own suite is the
telemetry drive. The reconciler is untouched too, which is Phase 5's.)

## Review gate 8 — Phase 3's first round, remediated (2026-09-20)

Two MEDIUM and four cheap LOW findings, no BLOCKER and no HIGH. Nothing here changes what the pass
is for or what the store does about a batch; one of them narrows a branch the Phase 2 closure above
made one class too wide.

* **MEDIUM - the outage arm read the whole `TransactionException` family as a store that went
  away.** The closure recorded above named `CannotCreateTransactionException` and caught its
  supertype, which puts a propagation this code asked for and cannot have
  (`IllegalTransactionStateException`) and a transaction a participant had already marked
  rollback-only (`UnexpectedRollbackException`) on the transient side: the intake leg would abandon
  and redeliver into the same defect on every delivery the broker allows, and the run would write a
  nightly outage line about a run that was never near the store's health. It also contradicted the
  class's own stated rule that the list is **named** rather than taken from a supertype.
  **Closed** by naming two classes instead of the family - a transaction that could not be begun,
  which is where a store lost before a `REQUIRES_NEW` boundary refuses, and
  `TransactionSystemException`, which is where a store lost after the last statement refuses at
  commit. The second is kept deliberately and has its own sentence in the javadoc: a write that
  reached the commit is **ambiguous rather than lost**, and an ambiguous write is retried by
  preference, because supersession absorbs a duplicate and nothing absorbs a silent loss.
  Everything else in the family falls through to the arm that dead-letters with a reason.
  **Red** (`StoreOutageTest.a_transaction_usage_fault_is_handed_on_unchanged`, over
  `IllegalTransactionStateException` and `UnexpectedRollbackException`): `flock -w 7200 … ./gradlew
  test --tests '*StoreOutageTest*' -Dtest.noFailFast=true` -> **10 tests completed, 1 failed**, one
  assertion and no compile error - "Expecting actual: StoreUnavailableException … and actual:
  IllegalTransactionStateException … to refer to the same object".
  **Green** (`c620099`): the same command with `checkstyleMain` and `pmdMain` beside it, BUILD
  SUCCESSFUL, **10 of 10**. A second WentAway case pins the commit-time arm, so both halves of the
  narrowed rule have one.

* **MEDIUM - the `overtaken` clause's negative arm was unpinned in `MARK_FAILED`.** The record above
  says "a reason that keeps the stamp supersedes nothing", and the guard that makes it true is
  `:releaseRows` on `stamped`; no case paired an earlier overtaken share with one of the four
  reasons that keep the stamp, so the sentence rested on the guard being inherited rather than on an
  assertion.
  **Closed** by `Failure.a_failure_holding_the_stamp_should_leave_the_share_it_overtook_alone`
  (`85ddaf9`): the batch is failed `GENERATION_FAILED` with systemdocgenerator's own word beside it,
  the overtaken share's supersession pair is empty, both rows stay RECORDED, the stamp stays on, and
  the day's one assemblable register is the overtaken share. It is a **characterisation** and passed
  on its first run: the guard was already there, and what was missing was the case that says so.

* **LOW, taken - the three keys of the pass's summary line, and the correlation it opens alone.**
  `released_batches=`, `released_registers=` and `contended=` were asserted nowhere, though Phase 4
  is to read them off that line; and "a pass driven outside a run opens a correlation of its own"
  was asserted only by the removal afterwards, which a pass that opened none also satisfies. Both
  are now cases in `StaleBatchReleaserTest` (`4f290cb`), on the quiet night and on the mixed one.

* **LOW, taken - the account's two lists.** `StaleReleaseOutcome` had no compact constructor, so a
  port implementation answering with a missing list turned the run's first act into an
  `NullPointerException` reported as an unexpected failure rather than as the store's own signal.
  It now copies both and refuses a missing one where the account is built (`5ad02ee`, `5278de0`),
  pinned by `application/StaleReleaseOutcomeTest`.

* **LOW, taken - the `successor_id IS NULL` guard on the two folded clauses.** Neither `MARK_FAILED`
  nor `RELEASE_FAILED` had a case where the failing batch's register has **both** a later successor
  and an earlier share beside it, which is the arrangement the guard exists for: a register that is
  itself being superseded is leaving the active index as it goes, takes no key, and displaces
  nobody. Both twins are added (`c1fa3b7`), and reaching the arrangement is itself the finding's
  answer - `idx_register_batch_live_key` allows one live batch per court centre day, so the third
  row only exists where the successor's own batch has already ended under a reason that keeps its
  stamp.

**Declined, with the reason.** `GenerationMetricsTest`'s TDD note ("the three counters landed
implemented rather than as throwing seams") is recorded by its own reporter as needing no change
this phase; the red is indirect through `StaleBatchReleaserTest`'s `NO_METER` sentinel and is
recorded honestly at T011. And `support/GenerationLegs`' ownership is a note for the **coordination
contract**, which lives outside this repository: `src/test/**/support/*` is edited by 004 alone in
this range and should be written down as 004-owned before any 005 tree touches it.

**Green after the remediation**: `flock -w 7200 … ./gradlew jacocoTestReport build
-Dtest.noFailFast=true` BUILD SUCCESSFUL, exit 0, 10m 10s, **3678 tests over 581 suites, 0 failures,
0 errors** — seven more than the phase close above, being `StoreOutageTest`'s two, `RegisterStoreIT`'s
three and `StaleReleaseOutcomeTest`'s two — with `checkstyleMain`, `checkstyleTest`, `pmdMain`,
`pmdTest` and `jacocoTestCoverageVerification` all green and none of them loosened. The coverage
report regenerated in that same run reads **LINE 6645/6852 = 0.9698 and BRANCH 2009/2228 = 0.9017**
against the unchanged gate of LINE 0.88 / BRANCH 0.85. No migration was added and no schema changed;
the only production code that moved is the outage arm's class list and the account's compact
constructor.

---

## Phase 4: User Stories 1, 2 and 4 — the run calls the pass and says so (Priority: P1) 🎯 MVP

**Goal**: the pass runs first, on the right nights, and the run's line carries the two released
numbers.

### Tests first ⚠️

- [x] T015 [US1] `batch/RegisterGenerationJobTest` (extend) — where the pass sits.
      `the_releaser_runs_after_the_gate_and_before_the_store_is_read`, an `InOrder` over the gate, the
      releaser and `store.activeUnbatched()` — the assertion the whole increment rests on, because a
      pass after assembly would release into a batch already made;
      `a_skipped_run_does_not_release_anything` (FR-005, FR-018);
      `released_registers_reach_the_assembler_in_the_same_run` (US1.3);
      `a_releaser_that_throws_still_writes_a_line_and_rethrows`. Seam: the constructor takes a
      `StaleBatchReleaser` in place of the `GenerationReconciler`. Red: the job does not call it.
      **Seams landed**: the job's constructor parameter and field swap type; the end-of-run
      `tally.chased(reconciler.reconcile())` goes with them, because a releaser has nothing to
      reconcile and the class would not otherwise compile (T016 keeps the javadoc that describes
      it); `SchedulingConfig.registerGenerationJob` builds a `StaleBatchReleaser` **inline** from
      the store and the two durations it already holds, deliberately **not** as a bean, so that
      T019's context case has a real red to record; `support/GenerationLegs` passes the releaser it
      already builds. The throwing case uses `StoreUnavailableException`, which is what a store
      lost under the pass raises, and asserts it leaves the run after the line is written.
      **Red**: `flock -w 7200 … ./gradlew test --tests '*RegisterGenerationJobTest*'
      -Dtest.noFailFast=true` → **67 tests completed, 3 failed**, three assertions and no compile
      error — `the_releaser_runs_after_the_gate_and_before_the_store_is_read`
      ("Wanted but not invoked: staleBatchReleaser.releaseStale()"),
      `released_registers_reach_the_assembler_in_the_same_run` ("Argument(s) are different!", the
      assembler was handed the empty list the store answers before the pass has run) and
      `a_releaser_that_throws_still_writes_a_line_and_rethrows` ("No interactions wanted here …
      found these interactions on mock 'registerStore'", the run having read the store the pass
      should have ended it before). `a_skipped_run_does_not_release_anything` is a guard and passes
      on the red run: a job that calls nothing cannot call it on a skipped night either.
- [x] T017 [US4] `batch/RegisterGenerationJobTest` (extend) and `domain/RunReportTest` (extend) — the
      line. `the_run_line_carries_both_released_numbers`; `a_run_that_released_nothing_says_zero`;
      `the_run_line_carries_no_reconciled_anywhere`, a whole-line assertion rather than a field one,
      because the word must be gone from the format string and not merely zero;
      `the_released_registers_are_not_added_to_either_total`, which pins the one thing a reader of a
      line of totals will otherwise assume. Red: the record and the line still say `reconciled`.
      **`domain/RunReportTest` is new, not extended** — there was no such file; the record's own
      claims were asserted only through the job's suite.
      **Seams landed**: `RunReport`'s `reconciled` component becomes `releasedBatches`,
      `releasedRegisters` and `contended`, with the javadoc that says the last two are a diagnostic
      and not a third sum, and `RunTally.reportOf` answers with what the pass gave back (T016
      already held it). The job's format string still carries a literal `reconciled=0`, which is
      what T018 replaces and what three of these cases fail on. The **third number is the
      orchestrator's decision of 2026-09-20**: a batch the pass could not release is work the night
      left undone, and the run line may not be silent about it, so `contended=` joins the two
      released keys and the accounting note names all three.
      **Red** (`flock -w 7200 … ./gradlew test --tests '*RegisterGenerationJobTest*' --tests
      '*RunReportTest*' -Dtest.noFailFast=true`): **74 tests completed, 11 failed**, every one an
      assertion and none a compile error — the three new line cases, plus the five whole-line
      expectations that now name the three keys (`every_field_of_the_report_should_be_on_the_line`,
      the skipped night's, and the three lines an unfinished run writes),
      `nothing_on_the_line_should_be_free_text` over the bounded-field pattern, and
      `a_releaser_that_throws_still_writes_a_line_and_rethrows`. Samples: "expected: 4 but was: -1"
      for `released_batches`, and the whole-line diffs showing `reconciled=0` where the three keys
      belong. **[A]** `RunReportTest`'s three cases and
      `the_released_registers_are_not_added_to_either_total` pass on the red run: they characterise
      the record the seam leaves and the total it deliberately does not grow, and the total was
      already right — what was missing was the case that says so.
- [x] T019 [US1] `config/GenerationWiringContextTest` (extend) — the bean.
      `a_generation_enabled_context_holds_a_stale_batch_releaser`;
      `a_command_jvm_holds_no_stale_batch_releaser`;
      `the_releaser_takes_the_two_durations_and_not_the_whole_record`. Red: no such bean.
      **The command JVM is a nested context of its own**, because a nested `@SpringBootTest` does
      **not** inherit the enclosing one's `properties` — the first attempt loaded a pod configured
      with nothing but `yotresultsdistribution.cli=true` and failed to start. The outer list is now named
      constants that both annotations share, so the pair differs by that one property and by
      nothing else, which is what makes the absence attributable to it.
      **Red** (`flock -w 7200 … ./gradlew test --tests '*GenerationWiringContextTest*'
      -Dtest.noFailFast=true`): **11 tests completed, 1 failed** — `holds the pass the run gives
      back stale batches through`, "Expecting actual not to be empty", an assertion and no compile
      error. The command JVM's case is a guard and passes on the red run, as it must: a bean no
      configuration declares is absent from every context. **[A]**
      `the_releaser_takes_the_two_durations_and_not_the_whole_record` is green on introduction — it
      states the constructor Phase 3 landed, asserted here because the wiring is where that shape
      is easiest to lose.

### Implementation

- [x] T016 [US1] `batch/RegisterGenerationJob.java` — make T015 green. `generate(tally)` calls
      `tally.released(releaser.releaseStale())` as its **first** statement, before
      `store.activeUnbatched()`; the end-of-run `tally.chased(reconciler.reconcile())` is deleted;
      the field, the constructor parameter and both javadoc mentions of the reconciler go. The class
      javadoc's paragraph "The reconciler runs whatever the night held, and on nights the run does
      not" is replaced by what is true now: the release is the run's own first act, it happens on the
      nights the run happens, and a flag-OFF night releases nothing — which is the accepted cost
      FR-018 states.
      **Green** (`flock -w 7200 … ./gradlew test --tests '*RegisterGenerationJobTest*'
      -Dtest.noFailFast=true`): BUILD SUCCESSFUL, **67 of 67 over nine nested suites**, 0 failures
      and 0 errors, the three red assertions among them. The tally keeps what the pass answered
      from the moment it answers — the numbers are a diagnostic beside the night's two accounts and
      are not folded into either — and `RunReport` still carries `reconciled`, which is T017/T018's
      to move.
- [x] T018 [US4] `domain/RunReport.java`, `batch/RegisterGenerationJob.java`,
      `config/GenerationMetrics.java` — make T017 green. `reconciled` → `releasedBatches` and
      `releasedRegisters` on the record, in `RunTally` and in the `recorded(...)` format string, with
      the javadoc saying — where it says what **does** add up — that these two do not, because the
      registers they count are re-batched by the same run and are already inside `rows()`. Retire
      `GENERATION_RECONCILED` and `reconciled()`; add
      `yotresultsdistribution_generation_released_batches_total` and `_released_registers_total`.
      **What Phase 3 had already landed**, per the gate record above: both released counters and
      `yotresultsdistribution_generation_release_contended_total` already existed and are already exercised,
      so this task is the line and the retirement. The record and `RunTally` moved with T017's
      seam; what landed here is the format string's three keys, the paragraph in `recorded(...)`
      that says the two released numbers are in neither account and what `contended` means, the
      removal of `GENERATION_RECONCILED` and `reconciled()`, and the two prose paragraphs that
      described the retired series.
      **The retirement is pinned by absence** (added in gate round 1):
      `GenerationMetricsTest.Surface.no_series_should_be_named_for_the_retired_reconciler` asserts
      that no `yotresultsdistribution_generation_reconciled_total` meter is registered after
      `exerciseEveryInstrument()`, and — reflectively — that `GenerationMetrics` declares no
      `GENERATION_RECONCILED` field and no `reconciled()` method. The surface case above could not
      carry the claim: it lists what `exerciseEveryInstrument` exercises, so a re-added
      `reconciled()` would register a series no case there ever asks for. Run subtractively as the
      convention asks: the constant and the method were temporarily restored, **red** (`flock … test
      --tests '*GenerationMetricsTest*'` → **47 tests completed, 1 failed**, "Expecting [… \"
      GENERATION_RECONCILED\" …] not to contain …", an assertion and not a compile error), then
      removed again, **green** (the same command → BUILD SUCCESSFUL, 47 tests, 0 failures). The run
      line's `doesNotContain("reconciled")` in `RegisterGenerationJobTest` remains the pin on the
      line; this is the pin on the series.
      **The reconciler is left compiling with its call pointed at nothing** (orchestrator's
      instruction): `GenerationReconciler.settle` no longer counts, under a comment naming T022 as
      the deletion, and the six counter assertions in `GenerationReconcilerTest` go with the
      series — the four cases they shared with a `completed` assertion keep that one and are
      renamed to what they now claim. **Noted for T022**: the reconciler still answers a count
      nothing reads, and its schedule is still live until the class is deleted.
      **Green** (`flock -w 7200 … ./gradlew test --tests '*RegisterGenerationJobTest*' --tests
      '*RunReportTest*' --tests '*GenerationMetricsTest*' --tests '*GenerationReconcilerTest*'
      -Dtest.noFailFast=true`): BUILD SUCCESSFUL, **162 tests, 0 failures, 0 errors**, the eleven
      red assertions among them.
- [x] T020 [US1] `config/GenerationConfig.java`, `config/SchedulingConfig.java` — make T019 green. The
      `generationReconciler` bean becomes `staleBatchReleaser`, taking `properties.staleAfter()` and
      `properties.lockAtMostFor()`; the job bean's `ObjectProvider<GenerationReconciler>` becomes
      `ObjectProvider<StaleBatchReleaser>`. **A wiring task's test is its context case**, which T019
      is.
      **Landed on `SchedulingConfig` and not on `GenerationConfig`, and the reconciler's bean
      stays.** Two reasons, both of them the task's own: `GenerationConfig` carries the
      generation-enabled condition but **not** `CliModeConfig`'s, so a `staleBatchReleaser`
      declared there would be held by a command JVM and T019's second case could not be true;
      `SchedulingConfig` carries both, which is exactly the pair of conditions the pass answers to.
      `generationReconciler` is untouched here and T022 deletes it with the class. The job bean no
      longer asks for it: the completeness check names the releaser in its place, and so does its
      WARN line. **Its timer went in gate round 1** (see the phase close): the bean is still
      contributed, but nothing fires it, so the run's pass is the only thing that decides a stale
      batch.
      **Green** (`flock -w 7200 … ./gradlew test --tests '*GenerationWiringContextTest*' --tests
      '*CliModeConfigTest*' -Dtest.noFailFast=true`): BUILD SUCCESSFUL, **25 tests, 0 failures, 0
      errors** over the three contexts — the generating pod, the command JVM and
      `CliModeConfigTest`'s own pair, which still holds the run on the ordinary pod and none of it
      on the command one.

**Phase close**: `flock … ./gradlew build` green; review gate. The new behaviour is live from here;
the reconciler is dead code, its bean still contributed and its timer removed, which the next phase
deletes outright.

**The wording this replaces was wrong, and gate round 1 caught it.** The close first said the
reconciler was left "dead code with a timer still on it, which the next phase removes", as though a
timer on dead code cost nothing. It was not dead while the timer was on it: `@Scheduled` fires every
`stale-after` interval in every generating pod, reads the same overdue batches from the same cutoff
as the run's pass, and therefore reached almost every stale batch first — a run fires once a night
and the sweep fires all night. What it did to them is the defect this increment exists to end:
`GENERATION_TIMED_OUT` is not one of `JdbcRegisterStore.RELEASING_REASONS`, so the batch was FAILED
with its registers still stamped to it and the court centre waited another day. So the timer is
removed here rather than at T022, which is the smallest change that makes the pass the only thing
that fails a stale batch and leaves Phase 4 safe to merge on its own. FR-007 asks for exactly one
schedule on the generation half; from here there is exactly one.

**Red** (`flock -w 7200 … ./gradlew test --tests '*GenerationReconcilerTest*'
-Dtest.noFailFast=true`): **45 tests completed, 2 failed**, both assertions and neither a compile
error — `the_reconciler_should_carry_no_timer_of_its_own` ("expected: null but was:
@org.springframework.scheduling.annotation.Scheduled(scheduler=\"registerGenerationScheduler\",
… fixedDelayString=\"${yotresultsdistribution.generation.stale-after}\")") and
`no_method_on_the_reconciler_should_be_scheduled` ("Expecting empty but was:
[\"reconcileScheduled\"]"). **Green** (the same command plus `*CliModeConfigTest*`,
`*ReportSchedulingConfigTest*` and `*GenerationWiringContextTest*`): BUILD SUCCESSFUL, 0 failures —
the ordinary pod still schedules the run, the command JVM still schedules nothing, and the
reconciler bean is still on the generating context and still absent from the command one.

**Phase 4 closed (2026-09-20).** `flock -w 7200 … ./gradlew jacocoTestReport build
-Dtest.noFailFast=true` → **BUILD SUCCESSFUL, exit 0, 10m 25s, 3689 tests over 583 suites, 0
failures, 0 errors** — eleven more than the Phase 3 gate above, being `RunReportTest`'s three, the
job's seven new cases and `GenerationMetricsTest`'s three released ones, less the two the retired
counter's own class held. `checkstyleMain`, `checkstyleTest`, `pmdMain`, `pmdTest` and
`jacocoTestCoverageVerification` all ran and all passed, none of them loosened; the coverage report
from that same run reads **LINE 6647/6854 = 0.9698 and BRANCH 2009/2228 = 0.9017** against the
unchanged gate of LINE 0.88 / BRANCH 0.85. No migration was added and no schema changed.

Two things the range met on the way that are worth the next phase knowing:

* **The leg drive had to be told what the pass answers.** `support/GenerationLegs.readyToGenerate`
  now stubs `failAndReleaseStale` with an empty outcome, because the run's first act is a store
  call: without it every arrangement that drives the run ended inside that first statement, and
  `TelemetryPrivacyTest` caught it exactly as it is written to — two lines the sweep claims to
  cover (`RegisterGenerationJob:426`, the batch that could not be stamped, and `:536`, the run's
  own batches read back) were no longer being written. The pass's own two endings are still driven
  by `theStaleBatchPass()`.
* **`GenerationMetricsTest`'s unlabelled `counter(String)` helper was orphaned** by the retirement
  and PMD's `UnusedPrivateMethod` refused the build over it. Closed by giving the three released
  series the published-surface class the retired one had, rather than by deleting the helper: the
  names are what a dashboard is written against, and nought on all three is the expected reading.

**For T022**: `GenerationReconciler.settle` still counts its completions into a local that only its
own answer reads, under a comment naming this; `GenerationConfig.generationReconciler` is still a
bean, though nothing fires it any more — its `@Scheduled`, its `GRACE_PERIOD` constant and the two
imports they needed went in gate round 1, and `@SchedulerLock` is kept only because
`ExceptionReportJobTest` still asserts the report's lock differs from `LOCK_NAME`; and
`GenerationReconcilerTest` keeps four cases renamed to what they now claim, the counter assertions
having gone with the series. **Its two gauges are now set by nothing between runs** —
`yotresultsdistribution_oldest_generating_age` and `_oldest_generated_age` were refreshed by the timer and
the run stopped calling `reconcile()` at T016 — which is the gap FR-011 names and Phase 6's
`BatchAgeSweep` (T024-T026) closes. A gauge that stands still is a reading nobody is owed tonight;
a register stranded in a dead batch is a court centre that never gets its document, so the trade is
the right way round and it is named here so Phase 6 does not have to rediscover it.

**Gate round 1 (2026-09-20)** — what the three reviewers sent back and what was done:

* **The reconciler's timer (MEDIUM).** Removed, as above. Two cases in
  `GenerationReconcilerTest.ItsOwnSchedule` that asserted the schedule now assert its absence, and
  `SchedulingConfig`'s prose about two surfaces sharing `GENERATION_SCHEDULER` follows.
* **`spec.md` FR-009 (MEDIUM).** Amended to name all three run-line keys, to say that none of them
  is a third sum and why, and to tie `contended=` to `yotresultsdistribution_generation_contended_total`,
  citing the coordinator's decision of 2026-09-20 as `data-model.md` does. The ambiguity-scan answer
  it belongs to carries the same note. The decision had been recorded in `plan.md` and
  `data-model.md` only, so the document the increment treats as outliving the decision disagreed
  with both the code and the plan.
* **The retired series (MEDIUM).** `GenerationMetricsTest.Surface`
  `no_series_should_be_named_for_the_retired_reconciler`, recorded under T018 above and run
  subtractively.
* **The incomplete-context branch (LOW).** `GenerationWiringContextTest.AnIncompleteContext`, two
  `[A]` cases driving `SchedulingConfig.registerGenerationJob` over providers: a context holding
  everything but the pass contributes no run and names `releaser=false` without accusing the four
  that were present, and the WARN line names no reconciler. The branch was reachable from no test,
  there being no set of properties that produces it.
* **Both skipped nights, and the contended one (LOW).**
  `a_skipped_run_does_not_release_anything` is parameterised over `FLAG_OFF` and `FLAG_UNREADABLE`,
  so FR-018's fail-closed night is asserted rather than inferred from the shared `Skipped` branch;
  and `a_batch_the_pass_could_not_release_should_not_stop_the_run` gives FR-003a the case the plan's
  matrix claimed for this suite. Both `[A]`.
* **Two ragged rewraps (LOW).** `RunReport`'s `Settled` javadoc and the test's whole-line constant
  re-flowed at the 100 columns the rest of both files wrap at.
* **`plan.md`'s inventory (LOW).** The releaser bean moved to the `SchedulingConfig` line with
  T020's reasoning, the `GenerationConfig` line reworded, the deletion line noting that the timer
  went early, and the run suite's matrix row naming the FR-003a case and the two skip reasons.

**Re-gated after the round.** `flock -w 7200 … ./gradlew build -Dtest.noFailFast=true` →
**BUILD SUCCESSFUL, exit 0, 10m, 3694 tests over 584 suites, 0 failures, 0 errors** — five more
than the close above: `GenerationMetricsTest`'s absence case, the wiring suite's two
incomplete-context cases, the run suite's contended one, the second parameter of the skipped one,
and the reconciler suite's two schedule cases replaced by two absence cases. `checkstyleMain`,
`checkstyleTest`, `pmdMain`, `pmdTest` and `jacocoTestCoverageVerification` all ran and all passed,
none of them loosened. The report from `flock … ./gradlew jacocoTestReport` over that run reads
**LINE 6651/6854 = 0.9704 and BRANCH 2022/2228 = 0.9075** against the unchanged gate of LINE 0.88 /
BRANCH 0.85 — thirteen more covered branches than the close's 2009, being `SchedulingConfig`'s
incomplete branch and the gate decisions the parameterised skip case now drives. No migration was
added and no schema changed.

---

## Phase 5: User Story 4 — the removal, then the vocabulary retirement

**Goal**: the reconciler, its timer, its lock, the query path and the deployment mode that depended
on it are gone from the source, the wiring, the stubs and the local stack — and, once the last writer
of the two retired values has gone with them, the vocabulary they named is retired too (T047-T050,
the other half of the migration split).

**This is the longest phase and it has an internal boundary.** T021-T027 remove the mechanisms;
T047-T050 remove the words for them; T028 closes the phase by characterising the finished suite. The
boundary is not a preference: until T022 and T025 land, `GenerationReconciler` writes both retired
values on every pass.

### Tests first ⚠️

- [X] T021 [US4] `config/GenerationWiringContextTest` and `config/CliModeConfigTest` (both extend) —
      the reconciler's absence. `no_context_holds_a_generation_reconciler`;
      `the_generation_half_carries_exactly_one_scheduler_lock`, a reflection sweep over the `batch`
      package naming the one it expects; `the_generation_half_carries_exactly_one_cron`. Red: the
      bean and the second lock exist.

      The absence is asserted over **bean names and a class name**, never over the type: a case that
      imported `GenerationReconciler` would be deleted with it, and an assertion about a deletion
      that cannot outlive the deletion is no assertion at all. `no_context_holds_a_generation_
      reconciler` therefore states both halves - no bean definition names one, and `Class.forName`
      on the binary name raises `ClassNotFoundException` - because a bean nobody fires and a class
      nobody has are different claims and only the second one keeps. The lock sweep reads the
      `batch` package's own **sources**, loads each class and collects every `@SchedulerLock` name,
      so a class added beside the run is in the claim the moment it is saved; `batch/cli` is left
      out because a command holds no scheduler at all, which `CliModeConfigTest` is what asserts.

      **Red** (`flock -w 7200 … ./gradlew test --tests '*GenerationWiringContextTest*' --tests
      '*CliModeConfigTest*' -Dtest.noFailFast=true`): **30 tests completed, 2 failed**, both
      assertions and neither a compile error - `no_context_holds_a_generation_reconciler`
      ("Expecting no elements of: [… "generationConfig", "generationReconciler", …] to satisfy
      the given assertions requirements, but these elements did: ["generationReconciler"]") and
      `the_generation_half_carries_exactly_one_scheduler_lock` ("Expecting actual:
      ["exception-report", "register-reconciliation", "register-generation"] to contain
      exactly in any order: ["register-generation", "exception-report"] but the following
      elements were unexpected: ["register-reconciliation"]").

      `the_generation_half_carries_exactly_one_cron` is **green on introduction** and recorded as a
      characterisation rather than claimed as a red: the reconciler's timer went early, at
      `f23812c5` in Phase 4's gate round, so the property it states was already true when it was
      written. It is asserted anyway because it is the one of the three that says what FR-007 asks
      for in the end state - one wall-clock decision about a batch, and no second. Its first
      expectation named the 07:00 report's cron beside the run's and failed on a context that does
      not carry it (`yotresultsdistribution.report.enabled` is unset in this pair, so `ReportSchedulingConfig`
      contributes nothing); the expectation was narrowed to the run's own cron, which is what "the
      generation half carries exactly one" means on this context.
- [X] T024 [US4] `application/DocumentRendererTest` (new, reflection) and
      `adapter/systemdocgenerator/SystemDocGeneratorClientTest` (extend) — the query's absence.
      `the_renderer_port_declares_one_method`, which is what stops the query being reintroduced
      quietly; `a_whole_generation_makes_no_request_to_the_document_endpoint`, over WireMock's
      journal. Red: two methods, and the client still has the call.

      The journal case carries **two halves, and neither is the other**. The journal is what a
      generation did — every request the client made over the one conversation a batch has with
      systemdocgenerator, read off `getAllServeEvents` so that a call to a path nothing stubbed
      still appears. The published surface is what a generation *could* have done: a client still
      declaring a second method is one line of a later change from making a second request, with
      the journal still clean because nothing has called it yet. A journal assertion on its own
      would have been green against the code that still had the query, which is why the second
      half is in the same case rather than trusted to the port's own suite.

      **Red** (`flock -w 7200 … ./gradlew test --tests '*DocumentRendererTest*' --tests
      '*SystemDocGeneratorClientTest*' -Dtest.noFailFast=true`): **45 tests completed, 2 failed**,
      both assertions — `the_renderer_port_declares_one_method` ("Expecting actual:
      ["requestRender", "query"] to contain exactly (and in same order): ["requestRender"]
      but some elements were not expected: ["query"]") and
      `a_whole_generation_makes_no_request_to_the_document_endpoint`, failing on its second half
      with the same two-against-one comparison over `SystemDocGeneratorClient`'s published calls.
- [X] T026 [US4] `config/ConfigurationValidationTest` and `config/PublicEventsHealthIndicatorTest`
      (both extend) — the completion mode's absence.
      `a_generation_enabled_context_subscribes_without_being_told_to`;
      `a_generation_enabled_context_without_the_broker_configuration_refuses_to_start`, now
      unconditional. Red: `PublicEventsConfig` still reads `completion` and the refusal is still
      conditional.

      **No red was available, and the task is recorded as a characterisation.** The red the task
      predicted had already been spent: `39b6aeaf` (Phase 1, T002) retired
      `yotresultsdistribution.generation.completion` from `GenerationProperties` and dropped the conjunct
      from `PropertiesValidator`'s broker rule in the same commit that renamed the grace period, so
      by the time this task opened neither half of the predicted failure could be produced without
      first putting the setting back. Both cases are therefore green on introduction and are
      asserted anyway, because what they state is what T027 must not undo: the subscription starts
      on `yotresultsdistribution.generation.enabled` and on nothing else, and the broker rule has one
      antecedent.

      `a_generation_enabled_context_subscribes_without_being_told_to` is stated over the
      **container** the factory makes rather than over the flag it was handed — auto-startup is
      what the container does, and a setter nothing reads is what survives a refactor — and it
      carries both directions: a generating deployment's container auto-starts, an intake-only
      one's does not, which is what makes it a claim about one setting rather than about a
      constant. Its third assertion is the record having no `completion` component at all.

      **Green** (`flock -w 7200 … ./gradlew test --tests '*PublicEventsHealthIndicatorTest*'
      --tests '*ConfigurationValidationTest*' -Dtest.noFailFast=true`): BUILD SUCCESSFUL, **171
      tests, 0 failures**. The one failure met on the way was the case's own scaffolding — a
      `SimpleJmsListenerEndpoint` with no message listener raises `IllegalStateException: No
      MessageListener set` when the factory creates a container from it — and not a claim about the
      subject.

      **The subtractive red, staged and recorded at gate round 1 of the increment gate
      (2026-09-21).** "No red was available" is an exception argued afterwards, and this section
      grants none in advance, so the red the convention asks for was produced the other way round:
      the two production expressions were put back to what they were before `39b6aeaf` decided
      them, the two suites were run, and the change was reverted. It is a scratch reversal and is
      in no commit - what is recorded is the run. The stand-ins are the retired setting's own two
      values, because the setting is gone from `GenerationProperties` and restoring the component
      would have meant editing the other tree's fixtures again: `PublicEventsConfig` was returned
      to `factory.setAutoStartup("event".equals(RETIRED_COMPLETION))` with the constant holding
      `poll-only`, and `PropertiesValidator`'s broker rule regained a completion conjunct that
      reads `poll-only`.
      **Red** (same two suites, `-Dtest.noFailFast=true`): **171 tests, 3 failed, every one an
      assertion.** `PublicEventsHealthIndicatorTest.a_generation_enabled_context_subscribes_without_being_told_to`
      on "[the generation half is on, so the one route an outcome arrives by is up; nothing else is
      asked and nothing else can say otherwise] Expecting value to be true but was false";
      `ConfigurationValidationTest$GenerationOutcomeAndDurations.a_generation_enabled_context_without_the_broker_configuration_refuses_to_start`
      and `.the_completion_setting_is_no_longer_bound` each on "Expecting <Started application […]>
      to have failed but context started successfully" - the second of them with its own message,
      "and the value that used to buy a deployment its way out of needing a broker now buys nothing
      at all". So both cases bite on exactly the behaviour T027 must not undo, and the suite is
      green again on the reverted tree.

### Implementation

- [X] T022 [US4] Delete `batch/GenerationReconciler.java` and `batch/GenerationReconcilerTest.java`;
      delete the leftover bean in `config/GenerationConfig`; delete the `register-reconciliation`
      lock name. Make T021 green.

      **Four test files came with it, because a deletion that leaves the suite uncompilable is not
      a deletion.** Each named the class by type and each is on T028's list; what travelled here is
      only what the compiler forced, and T028 keeps the rest (the query wiring, the `GRACE_PERIOD`
      constants and the recorded suite result).
      * `support/GenerationLegs` — the reconciler leaves `THE_LEGS`, its field, its construction,
        the `GRACE_PERIOD` constant, `theReconciler()` and the six arrangements under it, and
        `reconcilingOne()`. `generatedDocument()` went with them: it was the query answer only that
        drive read, and PMD's `UnusedPrivateMethod` refused the build over it. The privacy sweep's
        floor is fifty statements and is nowhere near threatened - what left the scan is the
        deleted class's own lines, which is what a sweep over the sources is for.
      * `e2e/GenerationFailureEndToEndIT` — the `an_outcome_that_never_arrived_should_be_fetched_
        and_the_batch_notified` case, and the class is three cases rather than four. The outcome
        that never arrived is not a case this suite can hold any more: there is nothing to fetch,
        and what happens to such a batch is the next run's first act, which is T037's to assert
        here and `StaleBatchReleaserTest`'s to prove.
      * `batch/ExceptionReportJobTest` — `the_lock_name_is_neither_generations_nor_the_reconcilers`
        becomes `the_lock_name_is_not_generations`, there being one other lock to differ from.
      * `config/ReportSchedulingConfigTest` — the two reconciler-bean assertions in
        `the_generation_beans_are_unchanged` re-point at `StaleBatchReleaser`, which is the bean
        that now stands where the reconciler stood: present on a generating pod, absent on a report
        one.

      **Green** (`flock -w 7200 … ./gradlew test --tests '*GenerationWiringContextTest*' --tests
      '*CliModeConfigTest*' --tests '*ReportSchedulingConfigTest*' --tests '*ExceptionReportJobTest*'
      --tests '*TelemetryPrivacyTest*' -Dtest.noFailFast=true`): BUILD SUCCESSFUL, **103 tests, 0
      failures** — `no_context_holds_a_generation_reconciler` and
      `the_generation_half_carries_exactly_one_scheduler_lock` both green, and the report's own
      wiring and lock untouched. `pmdMain`, `pmdTest`, `checkstyleMain` and `checkstyleTest` all
      green in the same round; the three Checkstyle warnings it opened with were T024's and T026's
      import order and an empty lambda block, both fixed here rather than left for the phase gate.
      `DocumentRendererTest` is still red at this commit, which is T025's red and not a regression.
- [X] T023 [US4] `batch/RunCorrelation.java`, `persistence/RegisterBatchRepository.java`,
      `application/{DocumentOutcomeSink,RegisterNotifierService,NotificationDisposition,NotificationSummary}.java`,
      `domain/{BatchStatus,RegisterBatch}.java`, `config/{SchedulingConfig,SchedulingInfrastructureConfig,
      ProcessedLogConfig,YotResultsDistributionProperties}.java`,
      `adapter/publicevents/DocumentEventListener.java` — **the javadoc and comment sweep**. Every
      place that names the reconciler, the grace period or the query now names what is there instead.
      `RunCorrelation`'s "two independently scheduled units … and the first calls into the second"
      becomes the run and the sweep, with the ambient-adoption branch kept and explained: the releaser
      calls `under(...)` from inside the run and adopts its id. `RegisterBatchRepository`'s three
      in-flight reads keep their claims and lose "the reconciler asks", and the paragraph documenting
      the PENDING-with-no-payload batch nothing revisits is replaced by the note that the pass now
      covers it. **A comment that describes a mechanism that no longer exists is a defect in this
      repository**, which is why this is a task and not a tidy-up.

      **Six files beyond the named list came with it**, on the same rule: `batch/IntakeAgeSweep`
      (its correlation javadoc cited the retired class's own split, and now cites
      `StaleBatchReleaser`'s, which is the live case of the same thing),
      `application/DocumentOutcomeSinkImpl` and `application/RegisterStore`,
      `persistence/JdbcRegisterStore`, `config/GenerationMetrics`,
      `config/PublicEventsHealthIndicator` and `batch/ExceptionReportJob`. Each described the
      retired mechanism as a live one — "the listener and the reconciler both arrive here", "both
      legs that take the reading", "the {@code reconciled} count beside it", a `void` entry point
      justified by `reconcileScheduled`'s shape. What was **not** touched is history stated as
      history ("borrowed the generation half's grace period until 004 renamed it", "the shape the
      retired reconciler used"), which is the record of a decision and not a description of what is
      there; nor the prose naming `CompletedBy.RECONCILER` and `GENERATION_TIMED_OUT`, which are
      constants that still exist and go with them at T047-T048.

      The two `DocumentEventListener` WARN lines changed wording — "the reconciler is what asks
      again" is no longer true of anything — so their `LogStatement` keys changed with them; the
      telemetry sweep enumerates from the sources and its drive reaches both, which the run below
      confirms.

      **Green** (`flock -w 7200 … ./gradlew compileJava checkstyleMain pmdMain`, then
      `… ./gradlew test --tests '*TelemetryPrivacyTest*' --tests '*DocumentEventListenerTest*'
      --tests '*LogStatementSweepTest*' -Dtest.noFailFast=true`): BUILD SUCCESSFUL both times, 0
      failures. Documentation-only within the loop's exemption, so no red is claimed: nothing here
      changes behaviour, and the two suites above are what stops a re-worded line escaping the
      sweep. **Two of the edits are not javadoc**: the `DocumentEventListener` WARN texts above are
      runtime log output, and it is `LogStatementSweepTest` and `TelemetryPrivacyTest` — which
      enumerate from the sources — that re-cover them, not the exemption. Recorded here rather than
      argued, because the exemption as written covers comments.

      **Gate round 2 took `config/CliModeConfig` back out of the list above.** Three reviewers read
      the same finding: the file belongs to the 005 worktree under the coordination contract, and
      the contract is about a rebase collision and not about how many lines an edit is. The hunk is
      reverted to `3c0fbeb0` at `a71ab390`, which leaves that file naming the grace-period
      reconciler and the three schedules until the owning tree applies the wording — the two
      sentences are in the revert's commit body for it to take. `config/YotResultsDistributionProperties`
      and the one `docker-compose.yml` comment are in neither tree's list; both are kept, because
      the paragraphs they change are about the generation half's `stale-after` and the local stub
      of the generation half, and the coordinator is asked to assign them to 004 (open point).
- [X] T025 [US4] Delete `DocumentRenderer.query`, `SystemDocGeneratorClient.query` and its answer
      parsing, `StubDocumentRenderer.query` and `domain/DocumentStatus.java`; delete the
      `GET document/{id}` WireMock mapping and its line in `docker/wiremock/README.md`. Make T024
      green. `docker/sdg-echo/sdg-echo.py` is **not** touched — it implements no query endpoint.
      `DocumentRenderer`'s javadoc drops its "two conversations" paragraph.

      Gone with them: `SystemDocGeneratorClient`'s `QUERY_PATH`, `DOCUMENT_MEDIA_TYPE`, the four
      answer-field names, `answerAbout`, `documentStatus` and the three optional-field readers;
      `SystemDocGeneratorClientTest`'s whole `Query` nested class and its five helpers;
      `support/GenerationLegs`'s six query arrangements, its two stubs and `refusedDocument()`;
      `support/GenerationStackSupport`'s `sdgQueryAnswersDocument`, `sdgQueryKnowsNothing` and the
      query path builder; and `docker-compose.yml`'s "command + query" comment beside the stub
      container.

      **Two suites had to be re-pointed, and the second is the interesting one.**
      `StubGenerationAdaptersTest.the_stubbed_renderer_should_report_no_verdict_rather_than_invent_a_
      document` was a call to a method that no longer exists; it becomes
      `..._should_declare_no_way_of_inventing_a_document`, over the stand-in's declared methods,
      which is the same claim made where it can still be made (synthetics filtered — JaCoCo's
      `$jacocoInit` is on every instrumented class).

      `TelemetryPrivacyTest.should_keep_the_generator_s_own_words_below_info` asserted that
      systemdocgenerator's words appear **somewhere** below INFO, as its guard against passing
      vacuously. That is no longer true of anything: the retired client's DEBUG line was the one
      place those words were ever written, and with it gone they reach **no** line at any level and
      live only in `sdg_reason`. The case now says exactly that, and its vacuity guard moves to
      where the words land — `GenerationLegs.generatorWordsReachedTheStore()`, read at the moment
      the refusal is applied rather than at the end of the drive, because `reset` clears a mock's
      recorded invocations and several later arrangements reset the store. The class javadoc's
      paragraph explaining why the sweep is scoped to INFO and above is restated in terms of the
      rule rather than of the one line that used to depend on it.

      **`.claude/agents/spec-validator.md` was not touched.** Contract row 3 carries no
      query-endpoint mention to remove — it already names the command and the two public events and
      nothing else. The file's two other reconciler mentions (the read-these-files list at line 49,
      and the outcome-is-learned rule at line 102) are the agent's scope and rule paragraphs, which
      this tree's coordination contract assigns elsewhere and T044 covers in Phase 9. Carried as an
      open point rather than fixed here.

      **Green** (`flock -w 7200 … ./gradlew test --tests '*DocumentRendererTest*' --tests
      '*SystemDocGeneratorClientTest*' --tests '*StubGenerationAdaptersTest*' --tests
      '*TelemetryPrivacyTest*' -Dtest.noFailFast=true`): BUILD SUCCESSFUL, **86 tests, 0
      failures** — `the_renderer_port_declares_one_method` and
      `a_whole_generation_makes_no_request_to_the_document_endpoint` both green. `pmdMain`,
      `pmdTest`, `checkstyleMain` and `checkstyleTest` green in the same round, after the eight
      imports the deletion orphaned and one javadoc the edit had left above the wrong method.
- [X] T027 [US4] `config/PublicEventsConfig.java`, `config/PropertiesValidator.java` — make T026
      green. Subscribe on `generation.enabled` alone; the broker rule loses its conjunct;
      `PublicEventsConfig`'s javadoc drops the `poll-only` sentence.

      **Both behavioural halves were already done at `39b6aeaf`** (Phase 1, T002), which is why
      T026 above could produce no red. What was left here was the prose that still described the
      setting as live, and the properties five test files were still setting.

      `PublicEventsConfig`'s auto-startup paragraph now says the rule has one antecedent and
      records why the second went — a pod in that shape would learn no outcome at all and re-render
      every court centre every night, which is strictly worse than refusing to start (FR-013). Two
      other paragraphs in the same file still explained a delay by the retired ten-minute grace
      period and a CLI JVM's dropped delivery by it; both now name the next run.
      `PropertiesValidator`'s own summary loses "an event-driven completion with no broker", which
      named a choice there no longer is, for "a generation half with no broker to hear an outcome
      from".

      **The dead property went from five places**: `GenerationWiringContextTest` and
      `CliModeConfigTest` (the `COMPLETION_EVENT` constant and the three `@SpringBootTest` lists it
      was on), `HttpSurfaceTest` and `support/GenerationStackSupport`. A value nothing binds is
      ignored by Spring in silence, so a suite still setting one is a suite whose properties list
      no longer describes the deployment it claims to be. The single remaining mention is
      deliberate: `ConfigurationValidationTest.the_completion_setting_is_no_longer_bound` sets
      `completion=poll-only` on purpose, to assert that the value which used to buy a deployment
      its way out of needing a broker now buys nothing at all.

      **Green** (`flock -w 7200 … ./gradlew test --tests '*PublicEventsHealthIndicatorTest*'
      --tests '*ConfigurationValidationTest*' --tests '*GenerationWiringContextTest*' --tests
      '*CliModeConfigTest*' --tests '*HttpSurfaceTest*' -Dtest.noFailFast=true`): BUILD SUCCESSFUL,
      **207 tests, 0 failures** — the two contexts boot without being told how they complete, and
      the HTTP surface is still Boot's error fallback and nothing else. `pmdMain`, `pmdTest`,
      `checkstyleMain` and `checkstyleTest` green in the same round.
**Phase 5, first half closed (2026-09-20).** T021, T024, T026, T022, T023, T025 and T027 are
landed; the mechanisms are gone and the words for two of them are not, which is the boundary this
phase was split on. `flock -w 7200 … ./gradlew build -Dtest.noFailFast=true` → **BUILD SUCCESSFUL,
exit 0, 10m 12s, 3646 tests over 576 suites, 0 failures, 0 errors**. `checkstyleMain`,
`checkstyleTest`, `pmdMain`, `pmdTest` and `jacocoTestCoverageVerification` all ran and all passed,
none of them loosened. No migration was added and no schema changed.

**Read the coverage on this half, because it is the shape the ratchet is met by accident in.**
`flock -w 7200 … ./gradlew jacocoTestReport check -Dtest.noFailFast=true` → BUILD SUCCESSFUL, exit
0, and the report from that run reads **LINE 6512/6715 = 0.9698 and BRANCH 1995/2198 = 0.9076**
against the unchanged gate of LINE 0.88 / BRANCH 0.85. Against the Phase 4 gate's 6651/6854 and
2022/2228 that is **139 fewer covered lines and 27 fewer covered branches, out of 139 fewer lines
and 30 fewer branches in the codebase at all** — which is what a deletion looks like when the thing
deleted was covered: the ratios moved by 0.0006 and 0.0001, the denominator fell by as much as the
numerator, and the gate was not adjusted. The suite is 48 cases smaller than the Phase 4 re-gate's
3694, being the reconciler's own suite and the query cases of the client's, less the four absence
cases T021 and T024 added.

**Still open at this boundary**, and deliberately: `BatchFailureReason.GENERATION_TIMED_OUT` and
`CompletedBy.RECONCILER` are still in the enums and still admitted by the schema, with nothing left
that writes either — T047-T050 retire them and narrow the three constraints under V7, and T028
characterises the finished suite after that. `.claude/agents/spec-validator.md` still names the
reconciler in its read-these-files list and in its outcome-is-learned rule; both are the agent's own
scope and rule paragraphs, which this tree does not own, and T044 covers them in Phase 9.

**Gate round 1 on this half (2026-09-20), and what it sent back.** Three reviewers read
`3c0fbeb0..b3233ec8`. Seven findings were acted on and the rest are named below.

1. **`config/CliModeConfig` is not this tree's to write in** (HIGH, all three reviewers). The T023
   hunk is reverted at `a71ab390` and the wording handed to the 005 worktree in the revert's commit
   body. The file therefore still names the grace-period reconciler and three schedules; that is
   the ownership line's cost, carried as an open point. `CliModeConfigTest` keeps its edits: they
   are T021's one-cron case and T027's removal of a property nothing binds, and reverting them
   would delete a pinning test and put a dead property back into two `@SpringBootTest` lists.
2. **The renderer client still opened on "two conversations"** (MEDIUM) — rewritten at `383d4d11`
   as the one conversation it now has, with the same C3 sentence about the shared retry policy.
3. **`RegisterBatchRepository` claimed a live publisher for the batch-age readings** (MEDIUM). The
   three in-flight reads have had no caller since the reconciler went, so the class javadoc now
   says so by name and says what takes them next (Phase 6's `BatchAgeSweep`) and why the three
   gauges stay registered and unrefreshed in between. US4 scenario 5 is met at T029-T032 and not
   at this boundary, which is the plan's own order.
4. **The listener's last two silent drops are counted** (MEDIUM). A `document-available` with no
   document or no instant, and a `generation-failed` with no instant, were acknowledged, dropped
   and counted nowhere. Test-first at `ef4afda0` (red: `expected 1.0 but was -1.0`), green at
   `3df47ef3`: one new bounded reason, `incomplete-outcome`, on
   `yotresultsdistribution_public_events_ignored_total`, with two cases beside the other five reasons in
   `DocumentEventListenerTest`. It is **not** Phase 7's `terminal-batch`, which is the sink's
   refused-transition drop; T033/T034 are untouched and still owe their own reason.
5. **Comments the sweep missed** (LOW) — `docker-compose.yml`'s sdg-echo note and
   `application.yaml`'s endpoints note both named the retired query or reconciler; reworded at
   `383d4d11`, as is `RegisterStore.markFailed`'s javadoc, which now asks
   `BatchFailureReason#isGeneratorAttributed` for the rule instead of counting reasons that T048 is
   about to change.
6. **Three inline fully-qualified names** (LOW) — imported and shortened at `e3cab4d6`.
7. **The one-lock sweep read one directory and one type per file** (LOW) — it now walks the `batch`
   sources, skips `cli` by path and collects nested types, at `3f6ccbe9`.

**Re-gated after the remediation.** `flock -w 7200 … ./gradlew build -Dtest.noFailFast=true` →
**BUILD SUCCESSFUL, exit 0, 10m 10s, 3648 tests over 576 suites, 0 failures, 0 errors** — two more
cases than the close above, being the two the new bounded reason is pinned by. `flock -w 7200 …
./gradlew jacocoTestReport check -Dtest.noFailFast=true` → BUILD SUCCESSFUL, exit 0, and the report
from that run reads **LINE 6516/6719 = 0.9698 and BRANCH 1995/2198 = 0.9076** against the unchanged
gate of LINE 0.88 / BRANCH 0.85. Four more lines in the codebase and four more covered, no branch
either way: the counter's two call sites and the method they call are all reached by the two cases.
Checkstyle, PMD and the coverage verification all ran; none was loosened.

**Left standing, with the reason.** The `spring.jms` `subscription-durable` comment still says "the
reconciler is the safety net, not the transport": it is outside the two `application.yaml` blocks
this tree owns and the reviewer that raised it asked for an owner rather than a silent fix.
`config/YotResultsDistributionProperties` and `docker-compose.yml` are in neither tree's ownership list and
their edits are kept - both paragraphs are about the generation half - with an assignment to 004
asked of the coordinator. `doc/DEFECT-FIXES.md`'s P2 cell still names the deleted
`GenerationReconcilerTest` case; T045 owns it, and the pointer to it two sections above this one
said T042 and T023 where it meant T045 and T022, now corrected.

---

### The vocabulary retirement — after the deletions above, and only after them

These four are the other half of the migration split (see "Decided" above Phase 1). They land
**after T022 and T025**, because until those two commits `GenerationReconciler` is still the writer
of both retired values and removing them would either fail to compile or force a false `completed_by`
claim. T028 runs after them, not before, so the suite it characterises is the finished one.

- [X] T047 [US4] `domain/BatchFailureReasonTest` and `domain/BatchStateTest` (both extend) — the
      **deletion reds**. `the_seven_reasons_are_the_bounded_set` becomes
      `the_six_reasons_are_the_bounded_set`; `generation_timed_out_is_no_longer_a_reason`;
      `completed_by_has_one_constant`; `only_generation_failed_is_generator_attributed`; and the
      both-directions schema-vocabulary case of T003 re-run against the narrowed lists, which is
      what makes the retirement provably complete rather than merely started. Red: the enums still
      hold both constants. (Nothing here is new behaviour — these assertions were T003's in the
      pre-split list and have simply moved to where they can be true.)

      **Every case here is keyed by a constant's name, and that is what lets a red exist at all.**
      T003 made the same choice for the opposite reason - a reason can be specified before it
      exists - and it is the only way a *removal* can be pinned either: a case that wrote
      `BatchFailureReason.GENERATION_TIMED_OUT` would stop compiling in the commit that removed it
      and would be deleted with its subject, which is an assertion that cannot outlive the deletion
      it is about. `generation_timed_out_is_no_longer_a_reason` therefore asks `reasonNamed` for the
      name and expects nothing, exactly as T021's `Class.forName` case does for the class.

      `only_generation_failed_is_generator_attributed` is asked of
      `isGeneratorAttributed()` over the whole enumeration **first** and of `ATTRIBUTION` second.
      Written the other way round it was green on introduction: the table is this suite's own
      specification, so narrowing the table is not a claim about the method the store and
      `register_batch_completed_by_shape_chk` each enforce. The table assertion is kept beside it so
      neither can drift alone.

      The schema-vocabulary case is renamed
      `the_failure_reasons_should_be_exactly_the_six_the_schema_enumerates` and **stays red past
      T048**, deliberately: its second half reads the constraint's own text out of the migrations as
      they stand, so it is red from the constants leaving the enumeration until V7 narrows the
      constraint at T049. That is the same window T003 recorded from the other side, and it is what
      makes each half of the retirement provably complete rather than merely started.

      **Red** (`flock -w 7200 … ./gradlew test --tests '*BatchFailureReasonTest*' --tests
      '*BatchStateTest*' -Dtest.noFailFast=true`): **53 tests completed, 9 failed**, every one an
      assertion and none a compile error — `the_six_reasons_are_the_bounded_set` and
      `the_failure_reasons_should_be_exactly_the_six_the_schema_enumerates` ("… to contain exactly
      in any order: [six] but the following elements were unexpected: ["GENERATION_TIMED_OUT"]"),
      `generation_timed_out_is_no_longer_a_reason` ("Expecting an empty Optional but was containing
      value: GENERATION_TIMED_OUT"), `completed_by_has_one_constant` ("Expecting actual: ["EVENT",
      "RECONCILER"] to contain exactly … ["EVENT"]"),
      `only_generation_failed_is_generator_attributed`, the parameterised
      `every_reason_should_be_classified_the_way_the_attribution_table_says` at `[5] reason =
      GENERATION_TIMED_OUT`, the two table-direction cases, and
      `an_attributed_reason_should_never_release_its_rows` ("[GENERATION_TIMED_OUT was reported
      about a render that happened] Expecting actual not to be null" — the retired constant is
      attributed and the narrowed release table no longer classifies it, which is the two tables
      catching the same removal from opposite ends). `checkstyleTest` and `pmdTest` green in the
      same round.
- [X] T048 [US4] `domain/BatchFailureReason.java`, `domain/CompletedBy.java` — make T047 green.
      Remove `GENERATION_TIMED_OUT`; narrow `isGeneratorAttributed()` to `GENERATION_FAILED`; remove
      `CompletedBy.RECONCILER`. `CompletedBy` stays a type with one constant, and its javadoc says
      why: it is an argument carried through the outcome sink into the store's marks, and a second
      mechanism is exactly the kind of thing that comes back.

      **Six test files came with it, on T022's rule: a deletion that leaves the suite uncompilable
      is not a deletion.** Three were compile errors (`DocumentOutcomeSinkTest`, `RegisterStoreIT`,
      `RegisterBatchRepositoryIT`, all of which name `CompletedBy.RECONCILER` as a constant), two
      were store fixtures writing a value the schema is about to refuse (`EmailReportSinkStoreIT`,
      `FileServicePayloadStoreIT`), and one was prose alone (`BatchStateTest`); none of the six is a
      claim about the retired constants that T047 does not now make by name.

      **`batch/cli/ReportExceptionsCliTest` is not among them, and no commit up to this point in
      the branch touches it.** (**Corrected at gate round 1 of the increment gate, 2026-09-21.**
      This paragraph was written at T048 and read as a claim about the whole range, which it never
      was. Two statements of it are now wrong and are restated here. First, `batch/cli` is touched
      in the range twice: `39b6aeaf` (T001/T002) edits `CliMainTest` and `GenerateRegisterCliTest`
      for the `GenerationProperties` arity change, recorded as a hand-off at T002 above; and
      `75e6c5f3` (T036), which lands *after* this paragraph was written, edits
      `ReportExceptionsCliTest`'s counts-line literal for the sixth `ExceptionKind`, recorded as a
      hand-off at T036 below. Second, `git log 4d3f9e00..HEAD -- src/…/batch/cli/` is therefore no
      longer empty - it names `75e6c5f3` - and `git log 92f5705..HEAD` over the same path names
      `39b6aeaf` as well. What stays true is what this task's own deletion did: the vocabulary
      retirement forced six test files and `ReportExceptionsCliTest` was not one of them.) It
      belongs to the 005 tree under the increment's coordination contract, and it did not have to
      change for T048: `ExceptionEntry.reason` is a `String`, so its `"GENERATION_TIMED_OUT"`
      literal is a bounded code read back out of a row the case never writes, not a reference to the
      constant that left `BatchFailureReason`, and the case is about the CLI's printed lines. A
      first draft of this task did edit it; that edit is not in the branch's history — the two
      commits carrying it were rebuilt with the path left at its `4d3f9e00` state, so
      `git log 4d3f9e00..HEAD -- src/…/batch/cli/` is empty rather than net-zero. The file runs
      green untouched — `--tests '*ReportExceptionsCliTest*'`, **44 tests, 0 failures**. Nothing on
      004's side had to be cut to make that true, and the surviving `"GENERATION_TIMED_OUT"`
      literal is a code V7 no longer admits at the insert, which is 005's to change when it deletes
      the command.
      * `application/DocumentOutcomeSinkTest` — three `CompletedBy.RECONCILER` arguments become
        `EVENT`, and the prose with them. The two `@EnumSource(CompletedBy.class)` cases are
        **kept**: they now drive one value, and what they say is "every mechanism the type offers",
        which is the claim the type exists to keep answerable when a second one arrives. The
        redelivery cases stop crediting "a reconciler racing an in-flight event" for a second
        delivery a shared durable subscription guarantees on its own.
      * `persistence/RegisterStoreIT` — **two cases are deleted with their subject**,
        `a_timed_out_generation_without_attribution_is_refused` and
        `a_timed_out_generation_the_reconciler_reported_should_be_recorded_as_its_verdict`. What
        they pinned was the store's rule over the *second* attributed reason; there is one, and
        `a_generator_failure_without_attribution_is_refused` pins the refusing direction over it
        while `generation_records_who_completed_the_batch` pins the accepting one. That case is
        re-pointed rather than halved: both marks that take an attribution are still driven, the
        generated one and the failed one, so the claim stays "on both marks rather than on one and
        an assumption".
      * `persistence/RegisterBatchRepositoryIT` — five `CompletedBy.RECONCILER` arguments and the
        `hasMessageContaining("RECONCILER")` that read one back become `EVENT`.
      * `adapter/report/EmailReportSinkStoreIT`, `adapter/fileservice/FileServicePayloadStoreIT` —
        two store fixtures whose dead batch was failed `GENERATION_TIMED_OUT`, which V7 is about to
        refuse at the insert; `RENDER_REQUEST_FAILED` instead, which is a reason something still
        writes. The CSV one is about UTF-8 and quoting, and neither property depends on which
        bounded code the row carries. **Both are 004-owned** under the coordination contract, which
        gives this tree `src/test/**` except `batch/cli` and any `api/` package — as is
        `config/TelemetryPrivacyTest`, edited at T028 for the same reason.
      * `domain/BatchStateTest` — three `@CsvSource` move descriptions and one javadoc named the
        reconciler or its grace period as what makes a drawn arrow necessary. The arrows are
        unchanged; PENDING → GENERATED is now justified by the announcement that finds the batch by
        its payload id, which is what actually reaches it.

      **The `reconciled` metric is named nowhere any more either.** It went with the reconciler in
      Phase 5's first half, and four javadoc paragraphs across these files still explained
      `completed_by` by what that counter counted. They now explain it by what the column is: the
      only record of which mechanism delivered an outcome.

      **Green** (`flock -w 7200 … ./gradlew test --tests '*BatchFailureReasonTest*' --tests
      '*BatchStateTest*' --tests '*DocumentOutcomeSinkTest*' --tests '*ReportExceptionsCliTest*'
      -Dtest.noFailFast=true`): **125 tests completed, 1 failed** — and the one failure is
      `the_failure_reasons_should_be_exactly_the_six_the_schema_enumerates`, which is T047's
      recorded red on the schema half and stays red until V7 lands at T049. Every other case T047
      opened is green, `generation_timed_out_is_no_longer_a_reason` and
      `completed_by_has_one_constant` among them. The four suites the constants' removal reached in
      Postgres (`RegisterStoreIT`, `RegisterBatchRepositoryIT`, `EmailReportSinkStoreIT`,
      `FileServicePayloadStoreIT`) ran green over Testcontainers in the same round, **140 tests, 0
      failures**. `checkstyleMain`, `checkstyleTest`, `pmdMain` and `pmdTest` all green.
- [X] T049 [US4] `persistence/SchemaMigrationV2IT` (extend) and
      `src/main/resources/db/migration/V7__retire_reconciler_vocabulary.sql` — the narrowing, as a
      pair in one commit because the IT's red *is* the migration's absence. The test:
      `v7_refuses_the_retired_timeout_reason` and `v7_refuses_the_retired_attribution`; and
      `v7_refuses_to_apply_to_a_store_holding_a_retired_row`, which seeds a violating row on a fresh
      container and asserts the migration fails rather than silently dropping it — the one behaviour
      an operator has to know about, and therefore the one worth a test rather than a sentence. The
      migration: the three constraint rewrites `data-model.md` gives under V7, in that order.

      **The migration is `data-model.md`'s three statements verbatim and in its order**, and the
      shape constraint keeps its three-arm structure and its `COALESCE` — only its attributed list
      narrows, from two reasons to one. A rewrite that collapsed the arms would stop covering the
      seven states by construction and start covering them by omission.

      **Three cases the narrowing took away were replaced rather than deleted.**
      `v6_still_admits_the_retired_timeout_reason` and `v6_still_admits_the_retired_attribution`
      were the assertion that V6 widened only, and V6's own footprint suite still makes it:
      `SchemaMigrationV6IT` is pinned to `target("5")` and `target("6")` and says what V6 did
      whatever V7 does afterwards. Against the shared head-migrated container the same two rows are
      now refusals, which is what the two `v7_` cases assert.
      `completed_by_shape_check_should_require_a_mechanism_on_a_timed_out_generation` becomes
      `..._on_a_failed_generation`: with one attributed reason left, what that case now says is that
      narrowing the attributed list to one did not narrow it to none, which is how a rewritten
      three-arm CHECK goes wrong. And
      `completed_by_check_should_name_exactly_the_two_completion_mechanisms` becomes
      `..._the_one_completion_mechanism` and stops using `contains`: it reads the codes out of the
      live definition and compares them as a set, because `contains` says every constant reaches the
      database and nothing about a second code the column still admits — which is exactly what V7
      exists to take away. `admittedCodesOf` reads the narrowed definition unchanged, the constraint
      being a single equality rather than an `IN` list and the pattern reading quoted codes rather
      than list syntax.

      **`v7_refuses_the_retired_timeout_reason` seeds its row unattributed, and that is not a
      detail.** Written attributed it failed green-side on
      `register_batch_completed_by_shape_chk`: the retired reason is out of the shape constraint's
      attributed list too, so such a row violates two constraints and Postgres names whichever it
      reached — a refusal about the wrong rule. The sibling case makes the mirror-image choice for
      the same reason, seeding its attribution on GENERATED rather than on FAILED.

      **Red** (`flock -w 7200 … ./gradlew test --tests '*SchemaMigrationV2IT*'
      -Dtest.noFailFast=true`): **79 tests completed, 5 failed**, every one an assertion — the two
      `v7_` refusals on "Expecting code to raise a throwable", the two both-directions cases on the
      retired value still being admitted, and
      `v7_refuses_to_apply_to_a_store_holding_a_retired_row` on Flyway answering "No migration with
      a target version 7 could be found" where the constraint's name was expected, which is the
      migration's absence stated exactly.

      **Green** (`flock -w 7200 … ./gradlew test --tests '*SchemaMigration*' --tests
      '*BatchStateTest*' --tests '*BatchFailureReasonTest*' -Dtest.noFailFast=true`): BUILD
      SUCCESSFUL, **187 tests, 0 failures** — and with it
      `the_failure_reasons_should_be_exactly_the_six_the_schema_enumerates`, red since T048, which
      is the retirement being provably complete in both directions: the constants are out of the
      enumerations and out of the constraint, and each half is read from where it actually lives.
      `checkstyleMain`, `checkstyleTest`, `pmdMain` and `pmdTest` green in the same round, after one
      `CheckResultSet` violation on a `ResultSet.next()` asserted rather than branched on.

      **Added at gate round 3: `persistence/SchemaMigrationV7IT`, V7's footprint suite.** T049 as
      first landed said what V7's three constraints now admit and refuse; nothing said what V7 did
      to the store *besides* that, which is the question `SchemaMigrationV6IT` was written to ask of
      V6 and which V7 needs more, not less: V6 was one `DROP`/`ADD` pair, V7 is three, and the third
      rewrites a five-clause boolean whose arms cover all seven batch statuses. The suite is the
      `SchemaMigrationV4IT`/`V5IT`/`V6IT` shape — a private database of its own, migrated to
      `target("6")` and snapshotted (tables, columns, constraint definitions, index definitions),
      migrated to `target("7")` and snapshotted again — and **both ends are pinned**, so a V8 finds
      it already saying what it meant to say. Five cases: exactly three definitions appear and
      exactly three disappear and all six are one of the three named constraints; every other
      constraint is byte-identical across the migration, the subtracted set found **by name** rather
      than by the diff (subtracting the diff from both sides leaves two sets equal by construction);
      the narrowed reason list holds the six that stay and not the retired one; the attribution is
      `EVENT` alone and the shape's attributed arm is `GENERATION_FAILED` alone; and no column,
      table or index is added **or dropped**, stated as set equality so it covers both directions.

      **Red** (V7 mutated with `ALTER TABLE register_batch ADD COLUMN mutation_probe TEXT` and a
      fourth constraint, `register_batch_attempts_chk`, dropped and re-added with a different
      definition; `flock -w 7200 … ./gradlew test --tests '*SchemaMigrationV7IT*'
      -Dtest.noFailFast=true`): **5 tests completed, 3 failed**, every one an assertion — the
      count case on "Expected size: 3 but was: 4", the byte-identical case on the attempts
      definition differing between the two snapshots, and the column case on
      `register_batch.mutation_probe` being in the later set and not the earlier. The mutation was
      reverted, not edited around: the re-add was deliberately given a *different* definition,
      because a constraint dropped and re-added byte-identically is invisible to a snapshot
      comparison and would have recorded a red the suite had not actually earned.

      **Green** (the mutation reverted, same command): BUILD SUCCESSFUL, **5 tests, 0 failures**.

      **Also added at gate round 3: `v7_refuses_to_apply_to_a_store_holding_the_retired_attribution_alone`.**
      V7 has **two** narrowing statements and an operator can be stopped by either, but
      `v7_refuses_to_apply_to_a_store_holding_a_retired_row` seeds a row that violates both at once
      — `GENERATION_TIMED_OUT` *and* `RECONCILER` — so Flyway stops at statement 1 and statement 2's
      refusal was never reached by any case. The new one seeds the row only statement 2 can refuse:
      a **GENERATED** batch completed by `RECONCILER` carrying no failure reason at all, which is
      valid under V6 in every other respect. It asserts the refusal names
      `register_batch_completed_by_chk`, that the row is still there afterwards, and that V7 applies
      once it is deleted. Operationally it is the row nobody would think to look for: a batch failed
      `GENERATION_TIMED_OUT` is a visible dead end, whereas a batch that generated perfectly well
      and merely recorded who told it so looks like an ordinary success, and clearing only the
      failed ones leaves the pod still refusing to start.

      **Red** (V7's statement 2 commented out; `--tests '*SchemaMigrationV2IT*'`): **80 tests
      completed, 3 failed**, every one an assertion — the new case on "Expecting code to raise a
      throwable", and `v7_refuses_the_retired_attribution` and
      `completed_by_check_should_name_exactly_the_one_completion_mechanism` alongside it, which is
      the statement's absence stated from three directions. **Green** (statement 2 restored, same
      command plus `--tests '*SchemaMigrationV7IT*'`): BUILD SUCCESSFUL, **85 tests, 0 failures**
      (80 in `SchemaMigrationV2IT`, 5 in `SchemaMigrationV7IT`).
- [X] T050 [A] [US4] `docker/`, `specs/004-release-stale-batches/quickstart.md` — **[A]**, and this
      is T007 arriving where it belongs. Record that `docker compose down -v` is required before
      **V7** on any volume holding a pre-004 row, and confirm on a real local volume that the
      migration refuses without it and applies with it. No pair: it is an observation about Postgres,
      not a behaviour this repository implements.

      **Confirmed, on this compose file's own `postgres-data` volume and not on a fresh
      container.** The volume already on this machine was a pre-004 local run's, carrying V1–V4;
      V5 and V6 applied to it, one batch was seeded FAILED / `GENERATION_TIMED_OUT` /
      `RECONCILER` - the exact row the caveat is about - and V7 then stopped on

      ```
      ERROR:  check constraint "register_batch_failure_reason_chk" of relation "register_batch"
              is violated by some row
      ```

      with the row still in the table afterwards. `docker compose down -v`, a fresh `up -d
      postgres`, and V1–V7 applied in order: `register_batch_failure_reason_chk` ends admitting the
      six and `register_batch_completed_by_chk` ends as `completed_by IS NULL OR completed_by =
      'EVENT'`. The stack was taken down with `-v` again afterwards, so nothing of this is left on
      the machine.

      **One thing the walkthrough learned that the caveat did not say.** Flyway wraps a migration in
      a transaction on PostgreSQL, so a refused V7 leaves the schema exactly at V6 and the pod goes
      on failing to start - which is the good ending. Applying the file by hand through `psql`
      without a `BEGIN` does not: statement 1's `DROP CONSTRAINT` commits before its `ADD` fails,
      and the table is left with no failure-reason constraint at all. Both are now in
      `quickstart.md`, because the second is what a developer poking at a local database by hand
      will actually meet.

      Written down in two places, each for its own reader: `quickstart.md`'s "Local dependencies",
      for somebody walking the increment, and a comment above the `postgres` service in
      `docker-compose.yml`, for somebody who starts the stack and never opens the spec. Nothing else
      under `docker/` is touched - `startup.sh` belongs to the other tree, and no stub, mapping or
      helper has anything to say about a migration.

- [X] T028 [A] [US4] `adapter/stub/StubGenerationAdaptersTest`, `e2e/GenerationEndToEndIT`,
      `e2e/GenerationFailureEndToEndIT`, `config/GenerationMetricsTest`,
      `persistence/RegisterBatchRepositoryIT`, `persistence/RegisterBatchReportReadsIT`,
      `adapter/publicevents/DocumentEventListenerTest` and `…IT`, `support/GenerationLegs`,
      `support/GenerationStackSupport`, `support/GeneratedRegisters` — remove the query wiring, the
      reconciler construction, the retired counter and the `GRACE_PERIOD` constants, and record the
      observed suite result. A characterisation of the deletion: what these hold is other suites'
      scaffolding, and the behaviour is asserted by T021, T024 and T026.

      **Five of the eleven had nothing left to remove**, and that is the task's first finding rather
      than a shortfall. `StubGenerationAdaptersTest`, `GenerationEndToEndIT`,
      `GenerationStackSupport` and `GenerationLegs`' construction went at T022 and T025, when the
      compiler forced them; `GenerationFailureEndToEndIT` and `GenerationMetricsTest` were already
      re-pointed there and each now carries an explicit statement that the mechanism is gone
      (`no_series_should_be_named_for_the_retired_reconciler`, and the class javadoc saying the
      three release counters stand where `yotresultsdistribution_generation_reconciled_total` stood). What
      was left everywhere was **prose describing a live mechanism that is not there**, which in this
      repository is a defect and not a tidy-up.
      * `persistence/RegisterBatchRepositoryIT` — the bulk of it. `GRACE_EDGE` becomes `AGE_CUTOFF`
        and three case names lose "inside its grace period" for "inside the cutoff": the three reads
        are in-flight **age** reads with no caller at this commit, and Phase 6's `BatchAgeSweep`
        takes them, so a constant named after a mechanism nothing has would be the next reader's
        first wrong turn. Nine `as(...)` texts stop saying "reconciling" and say what the read
        answers; the nest javadocs say the reads see past each other rather than that a safety net
        finds batches with them.
      * `persistence/RegisterBatchReportReadsIT` — the two 002-reads-untouched assertions cited
        "the reconciler's own suite" and "the reconciler can only ask about a payload"; they cite
        `RegisterBatchRepositoryIT` and the read's own predicate now.
      * `adapter/publicevents/DocumentEventListenerTest` and `…IT` — three places where a lost
        completion was explained by what the reconciler would do about it. It is the next run
        releasing the batch that does, which is the same guarantee reached a different way, and the
        restart case's "the reconciler is the safety net and not the mechanism" becomes the batch
        never being completed at all.
      * `support/GeneratedRegisters` — `hasBeenWaitingFor`'s javadoc explained itself as the only
        way to reach the grace-period reconciler without shortening the grace period; it is now the
        only way to reach the staleness rule without shortening
        `yotresultsdistribution.generation.stale-after`, which is the same argument about the setting that
        exists.
      * `support/GenerationLegs` — the leg's one-line summary still had the outcome coming back
        "by topic or by query".

      **Three files beyond the list came with it**, on the rule the T023 sweep was written to:
      `application/RegisterNotifierServiceTest` ("the reconciler only reports and ages it" about a
      batch nothing settles), `config/TelemetryPrivacyTest` (the eight sources it enumerates are
      named by leg, and one of them was the reconciler; and the latency meter's exemption note still
      credited it with timing an ending) and `batch/RegisterGenerationJobTest` (three paragraphs
      about a night the reconciler is also settling). Gate round 1's finding 5 was comments the
      sweep missed, so these are folded in here rather than left for a second round.

      **Left standing deliberately**: every sentence that states the retirement as history rather
      than as a description of what is there - `StaleBatchReleaserTest`'s "the retired reconciler's
      timeout test is re-pointed at", `DocumentEventListenerTest`'s "that mattered more once the
      grace-period reconciler went", `SchemaMigrationV6IT`'s "a value the reconciler is still
      writing until Phase 5" (a suite pinned to `target("6")`, describing that moment on purpose),
      and the two domain javadocs that say what `CompletedBy` and `isGeneratorAttributed()` used to
      admit. And the ordinary English word: the digest that exists "for reconciliation", the
      differential audit's `RECONCILED_IN_001`, and the comparator that "reconciles" a derivation
      have nothing to do with the deleted class.

      **The recorded suite result** (`flock -w 7200 … ./gradlew build -Dtest.noFailFast=true`):
      **BUILD SUCCESSFUL, exit 0, 10m 10s, 3642 tests over 577 suites, 0 failures, 0 errors, 0
      skipped**. `checkstyleMain`, `checkstyleTest`, `pmdMain`, `pmdTest` and
      `jacocoTestCoverageVerification` all ran and all passed, none of them loosened. Against the
      first half's re-gate at 3648 over 576 suites that is **six fewer cases in one more suite**,
      and every one of the six is accounted for: +2 for T047's two new cases; -2 for the
      `RegisterStoreIT` pair deleted with its subject at T048; **-7 for parameterisation**, because
      six `@EnumSource(CompletedBy.class)` cases now offer one value instead of two and
      `@EnumSource(BatchFailureReason.class)` offers six instead of seven; and +1 net at T049, where
      the two `v6_still_admits_*` cases were replaced by the two `v7_refuses_*` ones and the new
      `RetiredVocabulary` nest added the migration-refusal case and the one extra suite. The
      parameterised loss is what a retired constant costs a suite that drives every value of a
      type, and is the one number here that is not a case anybody wrote or deleted.

**Phase close**: `flock … ./gradlew build` green; review gate. **Read coverage on this gate**: a phase
that deleted several hundred covered lines is the one shape in which the ratchet is met by accident.
Quote the numbers; do not adjust the gate.

**Phase 5 closed (2026-09-20).** T047, T048, T049, T050 and T028 are landed on top of the first
half's seven, and the vocabulary is retired in both places it lived: the two constants are out of
the enumerations, and `V7__retire_reconciler_vocabulary.sql` is out of the constraints that admitted
them. `flock -w 7200 … ./gradlew build -Dtest.noFailFast=true` → **BUILD SUCCESSFUL, exit 0,
10m 10s, 3642 tests over 577 suites, 0 failures, 0 errors, 0 skipped**. `checkstyleMain`,
`checkstyleTest`, `pmdMain`, `pmdTest` and `jacocoTestCoverageVerification` all ran and all passed,
none of them loosened.

**Re-gated after gate round 3** (`flock -w 7200 … ./gradlew build -Dtest.noFailFast=true`) →
**BUILD SUCCESSFUL, exit 0, 4m 21s, 3648 tests over 578 suites, 0 failures, 0 errors, 0 skipped**.
The six cases and the one suite that round added are exactly `SchemaMigrationV7IT`'s five in its own
new suite, and `v7_refuses_to_apply_to_a_store_holding_the_retired_attribution_alone` in the
existing `RetiredVocabulary` nest. Nothing was removed: the prose sweep moved no assertion, and
`batch/cli/ReportExceptionsCliTest` is back to the literal it started with.

**The coverage, read as this phase's note asks.** `flock -w 7200 … ./gradlew jacocoTestReport check
-Dtest.noFailFast=true` → BUILD SUCCESSFUL, exit 0, and the report from that run reads
**LINE 6514/6717 = 0.9698 and BRANCH 1993/2196 = 0.9076** against the unchanged gate of LINE 0.88 /
BRANCH 0.85 — **unchanged by gate round 3 to every digit**, which is what a round that added a
migration-footprint suite, a migration-refusal case and a pile of comments should read: none of
them is production Java, and the six new cases exercise Flyway and `pg_constraint` rather than a
branch of this service's own. Against the first half's re-gate at 6516/6719 and 1995/2198 that is
**two fewer covered lines out of two fewer lines, and two fewer covered branches out of two fewer
branches** — both
ratios identical to four places, because what left the codebase this half is one enum constant, one
enum constant and one `||` in `isGeneratorAttributed()`, all of them covered. The migration itself
adds no Java. **The gate was not adjusted**, and on a half this small it could not have been met by
accident: the denominator moved by two.

**The stale-count and stale-prose sweep (gate round 3).** Every comment that counted the failure
reasons was counting seven, and there are six; every comment that explained what happens to a batch
no event reaches was still explaining it by the reconciler. Both are now corrected wherever this
tree owns the file:
* **the counts** — `application/RegisterStore` ("three of the six… the other three"),
  `persistence/JdbcRegisterStore` at statement 9's javadoc, at statement 9a's and at
  `RELEASING_REASONS`' call sites, `persistence/RegisterStoreIT` twice, and
  `config/GenerationMetricsTest`'s "six reasons multiplied by seven outcomes" (the outcomes are the
  seven batch statuses and stay seven). `domain/BatchStatus`, `V2__register_store.sql` and the
  several "the six commands" comments are **not** touched: those count statuses and CLI commands,
  both of which are unchanged.
* **the retired query** — `config/LiveGenerationConfig` ("served by systemdocgenerator's command
  API", not "command and query APIs") and `config/YotResultsDistributionProperties` (the endpoints comment
  no longer lists `document/{id}`, which FR-006 removed).
* **the reconciler** — `docker/sdg-echo/sdg-echo.py`'s header,
  `adapter/systemdocgenerator/SystemDocGeneratorClient`'s undefined-success comment, and
  `application.yaml`'s `spring.jms` `subscription-durable` comment, which called the reconciler the
  safety net and now says the subscription is the only way an outcome arrives. `README`'s increment
  002 bullet keeps its historical sentence and says the reconciler was retired by 004, because that
  bullet is a record of what 002 shipped and not a description of the code as it stands.

~~**Hand-off to the 005 tree**~~ — **discharged, 2026-09-21.** `batch/cli/GenerateRegisterCli.java`
said "the four failure reasons that leave the stamp in place" and there were three. 005's T053 took
the sentence with the method: the whole file is deleted and the count is nobody's to correct. The
same is true of the other note this phase left about a `batch/cli` file — see `plan.md`'s report
row, where the printed-table half of the `BATCH_RELEASED` case is discharged the same way.

**Still open at the phase's end**, and each owned elsewhere: `doc/DEFECT-FIXES.md`'s P2 cell still
names the deleted `GenerationReconcilerTest` case and promises the `GENERATION_TIMED_OUT` half of
that fix, which is now doubly stale — T045 owns it. `.claude/agents/spec-validator.md` still names
the reconciler in its read-these-files list and its outcome-is-learned rule (T044, Phase 9).
`config/YotResultsDistributionProperties`, `application.yaml`'s `spring.jms` block and `docker-compose.yml`
are in neither tree's explicit ownership list and are edited here as comment-only corrections to
mechanisms 004 removed; the coordinator is still asked to assign them to 004. `README.md`'s
`## Status` section sits beside the same question: the increment-002 bullet edited here is in
neither the generation nor the operations section the contract names. And the three
in-flight age reads still have no caller until Phase 6's `BatchAgeSweep` — T028 renamed their
fixtures off the retired mechanism but did not give them one.

**And one document no task owns.** `.specify/memory/constitution.md` (3.2.0) still describes the
retired mechanism as live in four places — the `runId` rule's "its grace-period reconciler",
the eleven-lines narrative around `GenerationReconciler.reconcileScheduled()`, "reconciled once
through the SDG query API and otherwise fails `GENERATION_TIMED_OUT`", and "the public.event
listener with the query-API reconciler". The constitution wins where documents disagree, so this
outranks the three document tasks Phase 9 already has (T041 `design_rules`, T044
`spec-validator.md`, T045 the P2 cell) — and the file is on **004's must-not-touch list**, so this
tree cannot amend it and has not. The coordinator is asked to assign the amendment (3.2.0 → 3.3.0,
or a note that 004's retirement supersedes those passages) to whichever tree owns the file, to land
alongside Phase 9's document tasks and before the increment gate.

**Ownership, for the gate record.** `adapter/fileservice/FileServicePayloadStoreIT`,
`adapter/report/EmailReportSinkStoreIT` and `config/TelemetryPrivacyTest` are all **004-owned**: the
coordination contract gives this tree `src/test/**` except tests under `batch/cli` and any `api/`
package, and none of the three is either. An earlier gate asked whether editing them crossed a tree
boundary; it did not. The one file that did was `batch/cli/ReportExceptionsCliTest`, reverted at
gate round 3 and taken out of the branch's history at gate round 4.

**Gate round 4.** Two findings, both about what the range says rather than what it does.

* **The history, not just the diff.** Gate round 3 reverted the `batch/cli/ReportExceptionsCliTest`
  edit, which left `git diff 4d3f9e00..HEAD` empty for the path but left two commits in the range
  that touched it — and the coordination contract is a rule about commits, not about net effects,
  because it is what lets the other tree rebase on this one without meeting a change to its own
  file. The branch carries no upstream, so the range was rebuilt with the path pinned to its
  `4d3f9e00` blob in every commit: `feat(domain): retire the vocabulary the reconciler wrote` no
  longer touches it (and its message now says **six** test files rather than seven, which the
  narrative above had already had to correct), and the revert commit became the tasks.md correction
  it really was. `git log 4d3f9e00..HEAD -- src/…/batch/cli/` is now **empty**, and the tree at the
  new HEAD differs from the old one only by this paragraph and the sentences below.
* **Three more stale counts.** The round-3 sweep corrected every comment that counted the reasons
  themselves and missed the three that count the reasons `RELEASING_REASONS` does **not** name — a
  second cardinality that also fell by one when `GENERATION_TIMED_OUT` left, from four to three
  (`RENDER_REQUEST_FAILED`, `RENDER_REQUEST_REJECTED`, `GENERATION_FAILED`). Corrected at
  `JdbcRegisterStore` statement 9's javadoc, statement 9a's, and `releaseFailed`'s, and at the one
  `RegisterStoreIT` javadoc that repeats it. `RegisterGenerationService`'s "the four reasons this
  class can produce" is **not** touched: that counts what the generation service itself writes —
  `ASSEMBLY_FAILED`, `PAYLOAD_STORE_UNAVAILABLE`, `RENDER_REQUEST_FAILED`,
  `RENDER_REQUEST_REJECTED` — and is still four. Neither is `V2__register_store.sql`'s "the other
  four reasons": it is an applied migration and its comment is a record of what V2 enumerated.

**Re-gated after gate round 4** (`flock -w 7200 … ./gradlew jacocoTestReport build
-Dtest.noFailFast=true`) → **BUILD SUCCESSFUL, exit 0, 4m 43s, 3648 tests over 578 suites, 0
failures, 0 errors, 0 skipped**, `checkstyleMain`, `checkstyleTest`, `pmdMain`, `pmdTest` and
`jacocoTestCoverageVerification` all run and all green, none of them loosened. Coverage from that
same run reads **LINE 6514/6717 = 0.9698 and BRANCH 1993/2196 = 0.9076** — identical to gate round
3 in every digit, including both denominators, which is the round stating what it was: a history
rebuild that changed no tree but one paragraph of this file, and four comment sentences. A round
that moved a single covered line would be a round that had done something it did not say it was
doing.

---

## Phase 6: FR-011 — the readings survive the timer

**Goal**: the three in-flight age gauges keep their cadence, on a sweep that holds no lock and
settles nothing. **A Micrometer gauge never decays**: without this phase they would freeze at
whatever the last reconciliation saw and go on looking live.

### Tests first ⚠️

- [x] T029 `batch/BatchAgeSweepTest` (new) — the readings.
      `the_three_gauges_report_the_age_of_the_oldest_of_each_kind`;
      `a_kind_with_nothing_in_flight_reads_zero`, which is what brings a gauge back down;
      `a_batch_holding_a_document_nobody_was_told_about_is_named_and_settled_nothing`, the WARN the
      retired pass wrote plus the assertion that no store call follows it;
      `a_read_that_refuses_keeps_the_last_value_and_is_counted`, the design rules' one absorbed
      refusal — telemetry may not cost a Youth Offending Team its e-mail — asserting the WARN names
      the failure **by class**, the counter moves, and nothing is rethrown. Seam: the class with
      `sweep()` throwing `UnsupportedOperationException`. Red: a failing assertion on the first case.
      (red: `flock -w 7200 … ./gradlew test --tests '*BatchAgeSweepTest*'
      --tests '*GenerationMetricsTest*' -Dtest.noFailFast=true`, **53 tests, 6 failed**, 0 errors —
      the six are every case of the new suite and every failure is an assertion, the seam's refusal
      recorded as each case's *first* soft failure in the convention `StaleBatchReleaserTest` uses
      rather than as a stack trace out of the arrangement. The first case's remaining three failures
      are the properties under test: "expected: 5400.0 but was: 0.0" for the GENERATING reading and
      the same shape for the other two, nought being what a gauge registered from construction and
      never published to reads.
      **Six cases, not four, and one of them is an instrument.** The two beyond T029's list are
      `a_read_that_refuses_for_any_other_reason_is_counted_unexpected` — the second half of the
      absorbed refusal, which the named case cannot reach, and which is the reason the total catch
      does not hide a bug of ours inside an outage of theirs — and
      `the_sweep_opens_its_own_run_id_and_removes_it`, which is T030's `RunCorrelation.under(...)`
      stated as a case rather than as a sentence. The instrument is
      `yotresultsdistribution_batch_sweep_failures_total{reason}` on `GenerationMetrics`, which a test cannot
      name before it exists: the generation half's twin of
      `yotresultsdistribution_intake_sweep_failures_total`, its own series because a pod with the generation
      half switched off publishes one of them and not the other. `GenerationMetricsTest`'s two
      surface cases gain the name in the same commit, so "exercising everything registers exactly
      the documented instruments" stays a claim about all of them.
      **The three reads are taken with the clock itself as the cutoff**, not with a grace period:
      the retired pass read `now - gracePeriod` because it was about to ask systemdocgenerator about
      what it found, and the gauges' own documented meaning is the oldest batch of each kind. With
      nothing to ask, the reading is the whole purpose, so `plan.md`'s "holds a repository, the
      metrics and a clock" is met literally — the sweep takes no `Duration` at all.
      `checkstyleMain`, `checkstyleTest`, `pmdMain` and `pmdTest` green.)
- [x] T031 `batch/BatchAgeSweepTest` (extend), `config/ReportSchedulingConfigTest` and
      `config/CliModeConfigTest` (both extend) — the schedule it is on.
      `the_fixed_delay_reads_the_batch_age_refresh_key`, a reflection case over the annotation
      attribute, because a placeholder nobody asserts is one a later edit inlines;
      `the_sweep_names_its_own_scheduler`; `the_sweep_carries_no_scheduler_lock`, because a gauge
      describes the JVM that publishes it and a lock would make every other pod publish nothing;
      `a_command_jvm_runs_no_batch_age_sweep`; `the_context_holds_four_task_schedulers`. Red: no
      annotation, no scheduler bean, no exclusion.
      (red: `flock -w 7200 … ./gradlew test --tests '*BatchAgeSweepTest*'
      --tests '*ReportSchedulingConfigTest*' --tests '*CliModeConfigTest*' -Dtest.noFailFast=true`,
      **46 tests, 11 failed**, 0 errors — every failure an assertion. Five of the eleven are T029's
      seam, still refusing; the six this task adds are the three reds it names and the three halves
      they are stated in. "expected: \"${yotresultsdistribution.generation.batch-age-refresh}\" but was:
      null" and "expected: \"batchSweepScheduler\" but was: null" for the annotation;
      "Expecting empty but was: [\"batchSweepConfig\"]" for the exclusion; and "Expected size: 4
      but was: 3 in: [registerGenerationScheduler, exceptionReportScheduler, intakeSweepScheduler]"
      for the thread.
      **The seam is a configuration and not a constant holder**, and that is what makes three of
      the four reds possible: a class carrying only `BATCH_SWEEP_SCHEDULER` would have made the
      exclusion case green by accident, because a bean that is nowhere declared is absent from a
      command JVM for the wrong reason. So `config/BatchSweepConfig` lands at T031 declaring the
      sweep bean behind `yotresultsdistribution.generation.enabled` and **no** scheduler bean and **no**
      CLI-mode condition; T032 adds both. The exclusion is asserted twice over - the beans' absence
      from the CLI context, and the `@Conditional` itself by reflection - because an absence alone
      cannot tell a condition that is right from a configuration that was never imported.
      `the_sweep_carries_no_scheduler_lock` is green from the first run and is kept: it is a claim
      about an annotation that must never appear, and the case is what would fail the day somebody
      adds it.
      **The ordinary pod's half of the CLI pair lands here too**
      (`an_ordinary_pod_should_hold_the_batch_age_sweep`), because the suite's shape is a pair of
      contexts differing by one property and a claim made only about the absent half says nothing
      about what the property does.
      `checkstyleMain`, `checkstyleTest`, `pmdMain` and `pmdTest` green.)

### Implementation

- [x] T030 `batch/BatchAgeSweep.java` — make T029 green. The three reads, the three
      `metrics.oldest…Age` calls, the parked-batch WARN and the one absorbed refusal with its
      counter. It holds a repository, the metrics and a clock, and no store, no renderer and no lock.
      `RunCorrelation.under(...)` **opens** an id of its own, because a sweep on its own schedule is
      a unit of work in its own right — unlike the releaser, which adopts the run's.
      (green: `flock -w 7200 … ./gradlew test --tests '*BatchAgeSweepTest*' -Dtest.noFailFast=true`,
      **9 tests, 2 failed**, 0 errors — T029's six are all green and the two left are T031's
      annotation cases, which T032 makes green: `the_fixed_delay_reads_the_batch_age_refresh_key`
      and `the_sweep_names_its_own_scheduler`, both still reading `null` off a method that carries
      no `@Scheduled` yet. `checkstyleMain` and `pmdMain` green.
      **The three reads take the clock itself as their cutoff.** The retired pass read
      `now - gracePeriod` because it was about to ask systemdocgenerator about whatever it found;
      a reading whose own meaning is "the oldest batch of this kind" has no window to take, and a
      windowed read would make the series a step function keyed to a setting rather than a
      continuous measurement. It is also what lets the sweep hold no `Duration` at all, which is
      what `plan.md` says it holds.
      **The minimum is taken rather than the first row.** All three reads return oldest first and
      the retired pass relied on that; taking `min` instead means a read whose ordering changed
      would move a reading rather than silently publish the wrong batch's age.
      **The parked-batch WARN is carried over verbatim in substance** — the count, the oldest
      stamp and the oldest batch id — with "through this pass" become "through this sweep", and
      nothing follows it: the sweep holds no store, so the three reads are the whole of what it
      asks of the database.)
- [x] T032 `config/BatchSweepConfig.java` (new), `batch/BatchAgeSweep.java`,
      `config/CliModeConfig.java` — make T031 green. The config declares `BATCH_SWEEP_SCHEDULER` and
      a single-threaded `TaskScheduler`, in the shape `IntakeSweepConfig` uses and for its reason: a
      ten-minute reading queued behind a run that is asking for renders is a reading taken an hour
      late. **This is a configuration class, so T031's context case is its test** — the live condition
      from 003's second exception.
      (green: `flock -w 7200 … ./gradlew test --tests '*BatchAgeSweepTest*'
      --tests '*ReportSchedulingConfigTest*' --tests '*CliModeConfigTest*'
      --tests '*GenerationWiringContextTest*' checkstyleMain checkstyleTest pmdMain pmdTest
      -Dtest.noFailFast=true` → **BUILD SUCCESSFUL**, every case of the four suites green.
      **`config/CliModeConfig` is touched comment-only**, which is what this tree's coordination
      contract permits: the counts its javadoc keeps of the configurations carrying its condition
      move from five to six and from three to four, the schedule list gains the batch-age refresh,
      and one sentence says why `BatchSweepConfig` is *not* one of the two configurations
      conditional on the CLI property alone — it carries the generation half's switch beside it,
      because three gauges about batches belong to the pod that can hold one. No code of that
      class changed.)

**Phase close**: `flock … ./gradlew build` green; review gate.

**Phase 6 closed (2026-09-20).** `flock -w 7200 … ./gradlew build -Dtest.noFailFast=true` →
**BUILD SUCCESSFUL, exit 0, 4m 22s, 3660 tests over 579 suites, 0 failures, 0 errors, 0 skipped**.
`checkstyleMain`, `checkstyleTest`, `pmdMain`, `pmdTest` and `jacocoTestCoverageVerification` all
ran and all passed, none of them loosened. Twelve cases more than Phase 5's 3648: the nine of
`BatchAgeSweepTest`, `the_context_holds_four_task_schedulers`,
`a_command_jvm_runs_no_batch_age_sweep` and `an_ordinary_pod_should_hold_the_batch_age_sweep`.

**One thing landed early, and it is named rather than left to the diff.** The first phase-close
build was red on `TelemetryPrivacyTest`'s "the drive above moved every meter the downstream half
can publish": `yotresultsdistribution_batch_sweep_failures_total` existed and nothing drove it. That
assertion is the existing test going red at the arrival of production code, so the fix belongs in
this phase — `support/GenerationLegs` gains `theBatchAgeRefresh()` (the parked-batch WARN and both
absorbed arms) and `BatchAgeSweep` joins `THE_LEGS`, and `boundedLabelVocabulary()` gains
`SweepFailureReason`'s two codes, which the generation half had never before been labelled from.
**T039 and T040 keep their content**: what is owed there is the explicit case that the drive names
the two classes 004 added and no longer names the one it deleted, plus whatever line the sweep is
found to be rejected for — none was, on this run.

---

## Phase 7: User Story 3 and FR-019 — the drop is counted, and the morning says the right thing

**Goal**: the two guarantees that keep a Youth Offending Team from being told twice and keep support
from being sent after something already put right.

### Tests first ⚠️

- [x] T033 [US3] `application/DocumentOutcomeSinkTest` (extend) — **the drop that is not counted
      today**. `a_document_available_for_a_batch_not_completed_by_the_next_run_moves_nothing_and_is_counted`
      and `a_generation_failed_for_one_moves_nothing_and_is_counted`, each asserting the batch is
      unchanged, no notification is made, **and the ignored counter moves under `terminal-batch`**;
      `a_redelivery_of_such_an_outcome_is_counted_under_the_same_reason`, never as an unknown
      correlation. Red: the counter does not move — the current code logs at WARN and counts nothing.
      (red: `flock -w 7200 … ./gradlew test --tests '*DocumentOutcomeSinkTest*'
      -Dtest.noFailFast=true`, **32 tests, 3 failed**, 0 errors — the three new cases and nothing
      else, every failure an assertion and every one of them the counter: "expected: 1.0 but was:
      -1.0" twice and "expected: 2.0 but was: -1.0" for the redelivery, `-1.0` being what a series
      that does not exist reads as. The three *behavioural* halves of each case — the batch
      unchanged, the notifier untouched, the correlation not counted as unknown — are green from
      the first run, which is the point: the behaviour is unchanged and what was missing was the
      evidence.
      **The seam is the instrument**: `GenerationMetrics.TERMINAL_BATCH` and
      `terminalBatchIgnored()`, which a test cannot name before they exist. No surface case in
      `GenerationMetricsTest` moves, because the meter is
      `yotresultsdistribution_public_events_ignored_total` and only a seventh bounded reason is new;
      `TelemetryPrivacyTest`'s bounded vocabulary picks the constant up by reflection.)
- [x] T035 `application/ExceptionReportServiceTest` (extend) and
      `batch/cli/ReportExceptionsCliTest` (extend) — **FR-019**.
      `a_batch_released_by_the_run_is_reported_as_batch_released_not_batch_failed`;
      `a_batch_that_genuinely_failed_is_still_batch_failed`;
      `the_kind_switch_stays_exhaustive`, over `nameOf(dead)` and the stage naming; and in the CLI
      suite, that the table and the CSV carry the new kind. Red: a released batch reports as
      `BATCH_FAILED` and the kind does not exist (seam: the constant).
      (red: `flock -w 7200 … ./gradlew test --tests '*ExceptionReportServiceTest*'
      -Dtest.noFailFast=true`, **26 tests, 2 failed**, 0 errors — both failures assertions and both
      the kind: "expected: BATCH_RELEASED but was: BATCH_FAILED", and
      "[BATCH_FAILED, BATCH_FAILED] to contain exactly in any order [BATCH_FAILED,
      BATCH_RELEASED]". The seam is `ExceptionKind.BATCH_RELEASED` plus its arm of
      `recursEveryRun()`, which a switch expression makes a compile requirement rather than a
      choice - which is why `the_kind_switch_stays_exhaustive` is green from the first run. It is
      kept: it is the case that would fail the day a `default` arm was added to silence the
      compiler, and it pins which of the two halves the new kind is in.
      **`batch/cli/ReportExceptionsCliTest` is NOT extended here, and this is the one half of T035
      this tree could not do.** The coordination contract gives 004 `src/test/**` *except* tests
      under `batch/cli`, and Phase 5's gate round 4 rebuilt this branch's history specifically to
      take an edit to that file back out. The CLI table and CSV read `ExceptionKind` and
      `ExceptionEntry` and need no change to carry a sixth kind - the rendering is by name - so
      nothing is broken by the omission; what is owed is the *case* that says the table and the CSV
      carry it. It is handed to the 005 tree with the two sentences Phase 5 already handed over.)

### Implementation

- [x] T034 [US3] `application/DocumentOutcomeSinkImpl.java`, `config/GenerationMetrics.java` — make
      T033 green. The refused-transition branch counts `yotresultsdistribution_public_events_ignored_total`
      under a new bounded reason `terminal-batch`, beside the WARN it already writes. The constant's
      javadoc says why it exists and why it is not one of the notification counter's late-* labels,
      which describe two notifiers racing over one recipient and are a different event entirely.
      (green: `flock -w 7200 … ./gradlew test --tests '*DocumentOutcomeSinkTest*'
      --tests '*DocumentEventListenerTest*' --tests '*TelemetryPrivacyTest*' checkstyleMain
      pmdMain -Dtest.noFailFast=true` → **BUILD SUCCESSFUL**, all three suites green.
      **It is two branches and not one, which T034's own sentence did not foresee.** A
      `generation-failed` for a batch already FAILED does not reach the refused-transition branch
      at all: the outcome's state *equals* the batch's, so it lands in the first branch, the one
      that says "already stands at FAILED". Counting only the third branch would have left
      spec scenario US3.2 — a `generation-failed` for a released batch — dropped and counted
      nowhere, which is the exact gap this task exists to close. So the counter is moved by
      `countIfAlreadyEnded`, called from both non-marking branches and moving only where the batch
      stands in a state the machine draws **no move out of**.
      **Terminal is asked of the machine rather than listed.** `BatchStatus.canTransitionTo`
      already refuses every move out of a terminal state, so "a state nothing can follow" is
      derived from it; a second copy of that list inside the sink would be a copy to forget to
      update, and no new method was added to the enumeration for a predicate one caller needs.
      The log levels are untouched: the redelivery stays DEBUG and the undrawn move stays WARN.
      A redelivered `document-available` for a batch standing at GENERATED is therefore **not**
      counted - it is a batch mid-journey being told what it already knows, and nought is what
      this series must read on a healthy estate for an alert to be worth writing on it.)
- [x] T036 `domain/ExceptionKind.java`, `application/ExceptionReportService.java` — make T035 green.
      Add `BATCH_RELEASED`, read over the window like the other failure kinds; derive it from the
      reason at the one place FAILED batches become entries; check the "four stages" text and any
      switch over kinds for exhaustiveness. The javadoc says what makes it different from the others:
      it is informational, because its registers were re-rendered the same night.
      (green: `flock -w 7200 … ./gradlew test --tests '*ReportExceptionsCliTest*'
      --tests '*ExceptionReportServiceTest*' checkstyleTest pmdTest -Dtest.noFailFast=true` →
      **BUILD SUCCESSFUL**. The kind is derived at `kindOf(dead)`, the one place a FAILED batch
      becomes an entry, from the bounded reason on its own row - one read answers for both kinds,
      because one read is what the store holds and a second statement would have to keep this
      one's window and order. `recursEveryRun()` puts it with the terminal kinds: a released batch
      is a thing that happened once, so the report that leaves it out is the only report that
      would ever have stated it.
      **One file of the other tree's had to be touched, and it is named here rather than left to
      the diff.** `batch/cli/ReportExceptionsCliTest`'s counts-line literal enumerates one number
      per `ExceptionKind`, so a sixth kind makes it read
      `… notification_failed=1 batch_released=0 window_from=…` and the case went red on production
      code this task is required to write. The coordination contract puts `batch/cli` tests in the
      005 tree and says to report a file that had to be touched; this is that report. The edit is
      the literal and the two sentences around it and nothing else — no case added, none removed,
      no behaviour asserted differently. T035's other CLI half, the case that says the table and
      the CSV *carry* the new kind, is still owed and still 005's.)

**Phase close**: `flock … ./gradlew build` green; review gate.

**Phase 7 closed (2026-09-21).** `flock -w 7200 … ./gradlew build -Dtest.noFailFast=true` →
**BUILD SUCCESSFUL, exit 0, 4m 27s, 3666 tests over 580 suites, 0 failures, 0 errors, 0 skipped**,
with `checkstyleMain`, `checkstyleTest`, `pmdMain`, `pmdTest` and `jacocoTestCoverageVerification`
all run and all green.

**One commit of T036's belongs to the record rather than to the diff.** The sixth `ExceptionKind`
made three suites red that name every kind by hand — `domain/ExceptionReportModelTest`'s counts
map, `adapter/report/EmailReportSinkTest`'s personalisation keys and
`config/ProcessingMetricsTest`'s series list — and each was enumerating five. They are this tree's
own files and the edit is the enumeration plus the sentences that said "five", landed as
`test: carry the sixth exception kind through the suites that enumerate the kinds`; no case was
added, none removed and no behaviour is asserted differently. The sentences now say "one per kind"
rather than a number, so the next kind corrects a list and not a count as well.

---

## Phase 8: The proof and the privacy sweep

**Goal**: the assembled behaviour, end to end, and the sweep that holds every new line to Principle
VII.

### Tasks

- [x] T037 [A] [US1] [US3] `e2e/GenerationFailureEndToEndIT` (extend) — one case carrying **SC-001**
      and **SC-003**: a batch left GENERATING since the previous evening is released by the run, its
      registers are assembled into a new batch for the same court centre and register date, that
      batch renders and notifies, **exactly one** notification exists per recipient for that key, and
      the original batch's late `document-available`, delivered afterwards, moves nothing and is
      counted under `terminal-batch`.
      (green: `flock -w 7200 … ./gradlew test --tests '*GenerationFailureEndToEndIT*'
      -Dtest.noFailFast=true` → **BUILD SUCCESSFUL**, 4 tests, 0 failed, 0 errors; the new case runs
      in 1.0s against the assembled stack. **An [A] task, so there is no red to record**: every
      mechanism it asserts landed in Phases 2-7 and this case is the statement that they are joined
      - the release pass, the assembler's supplementary index, the render, the topic, the notifying
      leg and the terminal-batch drop, in one run of the real service against a real Postgres, a
      real file service, WireMock and an in-VM Artemis.
      **Two seams in the fixture, and they are readings rather than behaviour.**
      `GeneratedRegisters` grew `statusOf`, `payloadFileIdOf`, `documentFileIdOf` and
      `failureReasonOf`, because every reading it had answers for a court centre holding **one**
      batch and this is the first case whose court centre holds two - the one that was given up on
      and tonight's. `run()` now returns the `RunReport` it was dropping, since what the run's first
      act gave back is half of what SC-001 says. Neither changes a case that existed.
      **The late outcome is asserted as a delta and not as a level**: the ignored counter is the
      JVM's, so the case reads it before and awaits one more, which is what makes it independent of
      whatever order the four cases run in.)
- [x] T038 [A] [US2] `e2e/GenerationEndToEndIT` (extend) — the other side of the boundary and the
      operator's batch: a batch ten minutes old is untouched and its court centre day is deferred as
      today; a `system_generated = false` batch forty minutes old is untouched because its cutoff is
      the longer one.
      (green: `flock -w 7200 … ./gradlew test --tests '*GenerationEndToEndIT*'
      -Dtest.noFailFast=true` → **BUILD SUCCESSFUL**, 5 tests, 0 failed, 0 errors. An [A] task, so
      no red: both cases assert the arm the release pass already had, through the assembled
      service.
      **A register's status is not its batch's, and the first draft of both cases said it was.**
      The draft asserted `statuses()` would read GENERATING for the rows a live batch holds; the run
      answered `RECORDED`, because assembly stamps the batch id and the row moves only when the
      document does. That is the store's own rule and not a defect, so the assertion was replaced
      rather than the code: `registersIn(batchId)` and `registersWaiting()` say what a deferred day
      and an untouched batch look like - two registers still the batch's, one recorded register
      behind them in no batch at all. (The failing draft is recorded here rather than as a red run:
      it was a test asserting the wrong thing, not the behaviour being absent.)
      **`wasAskedForByAnOperator()` landed one commit early**, inside T037's, because the two tasks
      share `GeneratedRegisters`; it is the `system_generated = false` stamp this task's second case
      needs and nothing else reads it.
      **The deferred counts are asserted as positive, not as one.** `deferredKeys` and
      `deferredRows` are the night's across every court centre and the register store is shared by
      every suite in the JVM, so this day's own deferral is read from its rows and the report is
      asserted only to have said that something was passed over - which is the claim that would
      fail if a run stopped counting deferrals at all.)
- [x] T039 `config/TelemetryPrivacyTest` (extend) — the privacy sweep over the two new classes.
      `GenerationLegs` drives `StaleBatchReleaser` and `BatchAgeSweep` and no longer names
      `GenerationReconciler`; every line they produce carries only a batch id, a count and a bounded
      code, and no throwable this service did not write. Red: the drive names a class that no longer
      exists and does not name the two that do.
- [x] T040 Make T039 green: update `support/GenerationLegs`'s drive and fix any line the sweep
      rejects. **A line that has to be changed to pass is a privacy finding** and is called one in the
      commit body, not a test adjustment.
      (green: `flock -w 7200 … ./gradlew test --tests '*TelemetryPrivacyTest*'
      -Dtest.noFailFast=true` → **BUILD SUCCESSFUL**, the new case among them, followed by
      `checkstyleMain checkstyleTest pmdMain` → BUILD SUCCESSFUL.
      **T040's work had already landed, and Phase 6's close is where it is recorded.** The drive was
      not updated here because it could not wait until here: the moment `BatchAgeSweep` existed,
      `TelemetryPrivacyTest`'s standing claim that the drive moves every meter the downstream half
      can publish went red on `yotresultsdistribution_batch_sweep_failures_total`, and that is an existing
      assertion failing at the arrival of production code rather than a task deferred. So Phase 6
      gave `GenerationLegs` `theBatchAgeRefresh()`, put `BatchAgeSweep` into `THE_LEGS` and added
      `SweepFailureReason`'s two codes to the bounded vocabulary, and said so in its close.
      **No line was rejected by the sweep, at that phase's close or at this one**, so there is no
      privacy finding to call one: every line the two classes write carries a batch id, a count, a
      court centre and register date, or a bounded code, and none of them attaches a throwable -
      which the whole-of-`src/main` attachment case enforces anyway.
      **What T039 adds is the claim the two phases left implicit**: that the enumeration bounding
      every claim in that group names both classes, that it names the retired reconciler nowhere,
      and that each of the two really brought statements with it. Without the last of those, a class
      added to `THE_LEGS` that declares no line widens the list and not the claim - which is exactly
      the failure `THE_REPORT` has its own assertion for.

**Phase close**: `flock … ./gradlew build` green; review gate.

**Phase 8 closed (2026-09-21).** `flock -w 7200 … ./gradlew build -Dtest.noFailFast=true` →
**BUILD SUCCESSFUL, exit 0, 4m 28s, 3670 tests over 580 suites, 0 failures, 0 errors, 0 skipped**,
`checkstyleMain`, `checkstyleTest`, `pmdMain`, `pmdTest` and `jacocoTestCoverageVerification` all
run and all green, none of them loosened. Four cases more than Phase 7's close, which is exactly
what this phase added: T037's one, T038's two and T039's one.

---

## Phase 9: Polish — the documents, the register cell and the gates

- [x] T041 [P] `.claude/rules/design_rules.md` — six edits and no others: the two-leg flow diagram
      loses the `GenerationReconciler` line and gains the release pass and the sweep; the batch state
      machine's `neither, past the grace period ───▶ FAILED, GENERATION_TIMED_OUT (reconciler)` arm
      becomes the one data-model.md gives; the bounded failure-reason list swaps one value for
      another; **"The reconciler invents nothing" is reworded** to the rule that replaces it — *a
      batch nothing can be learned about is failed by the next run through the store, with its rows
      released, and no outcome is applied, because there is no outcome and applying one would be
      inventing evidence*; the consumed-contracts table's systemdocgenerator row loses the query
      endpoint; and the package-structure list's `batch/` line swaps the reconciler for
      `StaleBatchReleaser` and `BatchAgeSweep`. The "one absorbed refusal" clause gains the sweep
      beside `IntakeAgeSweep`, and the "every drop is counted under a bounded reason" rule gains
      `terminal-batch` and `incomplete-outcome` (the latter landed in Phase 5's gate round).
      Nothing else on the page is touched.
      (**Five edits were owed and one was already true.** The consumed-contracts table's
      systemdocgenerator row has never named the query endpoint — it reads *"`generate-document`
      (REST, 202) + the `document-available` / `generation-failed` public events"*, and the only
      query mentions on the page are the results query API's, which are the intake half's and
      nothing to do with this. The endpoint that does still need removing is
      `.claude/agents/spec-validator.md`'s contract row, which is T044's.
      **The sweep is drawn beside `IntakeAgeSweep` rather than under the run**, because that is
      what it is: the same shape, the same absence of a lock, the same reason. Putting it under
      `RegisterGenerationJob` would have drawn it as something the run calls, which is the one
      thing it must never be - a gauge refreshed only by a run is a gauge that stands still all
      day.
      The `batch/` package line now reads `StaleBatchReleaser` and `BatchAgeSweep` where it read
      `GenerationReconciler`; the failure-reason list swaps `GENERATION_TIMED_OUT` for
      `NOT_COMPLETED_BY_NEXT_RUN`; the state machine's last GENERATING arm is data-model.md's; and
      "The reconciler invents nothing" is now the rule that replaces it, which says the same thing
      about a mechanism that exists.
      **Three of the six edits are outside the sections the contract grants this tree, and they are
      named here rather than left to the diff** (recorded at gate round 1 of the increment gate,
      2026-09-21). The coordination contract gives 004 `design_rules.md`'s **flow diagram**, its
      **batch state machine** and its **consumed-contracts table**. The package-structure tree's
      `batch/` line, the "every drop is counted under a bounded reason" bullet and the "one
      absorbed refusal" bullet are none of those three. Each names a mechanism 004 removed or a
      reason 004 added, so leaving them would have left the page describing a class that is not
      there, and each edit is the name or the list and nothing else. **Hand-off to the 005 tree**,
      which is editing the same file for the REST surface: those three paragraphs carry a 004 edit,
      so its rebase meets them rather than a clean file. The coordinator is asked to extend 004's
      grant to them retrospectively, or to move them into 005's change.)
- [x] T042 [P] `README.md` — the generation section's "with a grace-period reconciler for the
      outcomes that never arrive" becomes the release pass, and a Status entry for increment 004 in
      the shape 001–003 use.
      (**The first half was already done, at Phase 5's gate, and deliberately in the other
      direction.** That sentence is inside the *increment 002* Status bullet, which is a record of
      what 002 shipped rather than a description of the code as it stands, so Phase 5 kept the
      historical clause and appended "since retired by 004 in favour of the next run releasing a
      stale batch". Rewriting it now would make the 002 bullet claim 002 shipped a mechanism it did
      not. The release pass is stated where it belongs instead: in the new 004 entry, in the same
      shape and at the same length as 001-003.
      **One stale line is left alone and named here**: the Design table's Specifications row still
      reads "`001-court-register-port` (complete) and `002-consolidate-progression-leg` (in
      progress)", which has been wrong since 003 shipped. It is outside this task's two named edits
      and outside every other task in the phase, so it is reported rather than fixed.)
- [x] T043 [P] `specs/002-consolidate-progression-leg/quickstart.md` and
      `specs/003-exception-report/quickstart.md` — the WireMock line loses "query document"; the 003
      quickstart's sample exception event stops using the retired reason. Historical quickstarts are
      still runnable ones.
      (The 002 line now reads *systemdocgenerator (command 202)*, which is what
      `docker/wiremock/mappings/` holds — there has been no query mapping there since Phase 5. The
      003 sample's `BATCH_FAILED` line carries `reason=GENERATION_FAILED`, a reason the store can
      still hold, rather than the retired one.
      **Two further words in that sample were made true rather than left runnable-but-wrong**: its
      counts line now carries `batch_released=0`, because the command really does print one count
      per kind and a walkthrough whose expected output is missing a key is one an operator reads as
      a failure; and "the five kinds together are proved by" loses its number, which is the same
      correction the suites took in Phase 7. Nothing else in either file is touched.
      **`specs/003-exception-report/quickstart.md` is outside the contract's grant, and it is named
      here rather than left to the diff** (recorded at gate round 1 of the increment gate,
      2026-09-21). The contract gives this tree the **002** quickstart's grace-period mentions and
      `specs/004-*`; the 003 quickstart is in neither list and is in no other tree's either. The
      edit is doc-only and is what FR-016 asks for - a walkthrough whose expected output names a
      reason the store can no longer hold is a walkthrough that fails when it is followed - so it
      is reported for ratification rather than reverted. The coordinator is asked to add the file
      to 004's grant.)
- [x] T044 [P] `.claude/agents/{spec-validator,software-engineer,qa,code-reviewer}.md` — the scope
      paragraphs name **004-release-stale-batches** alongside the three complete increments;
      `spec-validator`'s generation-leg read list swaps `batch/GenerationReconciler` for
      `batch/StaleBatchReleaser` and `batch/BatchAgeSweep`, its contract row loses the query
      endpoint, and its "the outcome is learned, never assumed" bullet is re-pointed at the new arm.
      (**Two of the four edits are this tree's and they are the two that landed.** The coordination
      contract gives 004 the *reconciler and grace-period sentences* in these four files and only
      those; the scope paragraphs belong to the 005 tree, which is editing the same paragraphs for
      the REST surface, and an increment name written into them from here is a conflict on a file
      neither tree can merge around. So `spec-validator.md`'s read list now names
      `batch/StaleBatchReleaser` and `batch/BatchAgeSweep`, and its "the outcome is learned, never
      assumed" bullet says the batch nothing can be learned about is failed
      `NOT_COMPLETED_BY_NEXT_RUN` by the next run's release pass, through the store, with its rows
      released.
      **The contract row needed nothing**: row 3 has always read *"`generate-document` (REST
      command, 202) and its public `document-available` / `generation-failed` events"*, with no
      query endpoint in it — the same thing T041 found on the design rules' own table.
      **Owed elsewhere and named here**: the four scope paragraphs still list 001, 002 and 003 and
      not 004. Handed to the 005 tree with the two hand-offs Phases 5 and 7 already made.)
- [x] T045 `doc/DEFECT-FIXES.md` — the one cell. `P2`'s pinning-test list replaces
      `GenerationReconcilerTest.a_batch_nothing_can_be_learned_about_should_be_failed_generation_timed_out`
      with `StaleBatchReleaserTest.a_batch_still_generating_past_the_minimum_age_should_be_failed_and_released`,
      and the status cell gains one dated sentence: the mechanism that keeps the promise changed in
      increment 004, from a grace-period query to the next run's release pass, and the promise — a
      failed or lost render is never silently dropped — is unchanged. **No new row, no changed claim,
      no changed status.** `RegisteredDefectFixes` green in the same commit.
      (green: `flock -w 7200 … ./gradlew test --tests '*DifferentialAuditTest*'
      --tests '*RegisteredDefectFixes*' --tests '*StaleBatchReleaserTest*' -Dtest.noFailFast=true`
      → **BUILD SUCCESSFUL**: `DifferentialAuditTest` 389, `RegisteredDefectFixesRejectionTest` 28
      and `StaleBatchReleaserTest` 11, none failed. The audit is the suite that reads this file, and
      it reads the row ids: no row was added, removed or renumbered, so it answers the same.
      **Two cells, and both were named in the task.** The pinning cell now reads
      `StaleBatchReleaserTest.a_batch_still_generating_past_the_minimum_age_should_be_failed_and_released`
      for the half the row also promises, and the status cell gains one dated sentence saying the
      mechanism changed in 004 while the promise did not, naming the end-to-end case as well.
      **Everything else in the row is left as written, deliberately.** The status cell describes
      what T048 landed: that is what increment 002 shipped, and the dated sentence is what tells a
      reader the rest is history. Rewriting it would make the row claim 002 shipped a mechanism it
      did not - the same reasoning T042 applied to the README's 002 bullet.
      **Amended at gate round 1 of the increment gate (2026-09-21): the fixed-behaviour cell was
      not left as written after all.** All three reviewers read the same thing - the cell's own
      account of the fix still named the grace-period reconciler and `GENERATION_TIMED_OUT` as the
      live mechanism for the half of P2 nothing ever answers about, which is a reason the store no
      longer admits and a class that no longer exists, and SC-008 says no document refers to the
      grace period after this increment. A **fixed-behaviour** cell describes the fix that is live,
      unlike a status cell, which is a dated record. So the clause gains one dated amendment saying
      the mechanism changed in 004 and the next run's release pass fails such a batch
      `NOT_COMPLETED_BY_NEXT_RUN` and releases its registers. The row's claim, its rationale and
      its status are unchanged; `RegisteredDefectFixesRejectionTest` (28) and `DifferentialAuditTest`
      (389) are green over the amended file, which is what says no row was added, removed or
      renumbered.
      **One more mention of the deleted class is left alone and named here**: the *"What T068 ran"*
      paragraph above the table lists `GenerationReconcilerTest` among the suites that run
      executed. It is a record of a run that happened, not a pointer to a file, and correcting it
      would be editing a dated observation.)
- [x] T046 [A] The gates, recorded. `flock … ./gradlew build` with the JaCoCo ratchet at **0.88 line /
      0.85 branch** unchanged, PMD over main, Checkstyle over main and test, the differential audit
      and the consolidation audit; then the `quickstart.md` walkthrough end to end on a **clean** local
      stack, including step 6's other side of the boundary and the three startup refusals. Quote the
      numbers and the observed output. **If coverage falls, it is fixed with tests, not by moving the
      gate.**
      (`flock -w 7200 … ./gradlew jacocoTestReport build -Dtest.noFailFast=true` →
      **BUILD SUCCESSFUL, exit 0, 4m 22s, 3670 tests over 580 suites, 0 failures, 0 errors, 0
      skipped.** `jacocoTestCoverageVerification`, `pmdMain`, `pmdTest`, `checkstyleMain` and
      `checkstyleTest` all ran and all passed; `jacocoTestReport` runs before the verification, so
      the report the numbers below are read from is that run's own.
      **Coverage, against the unchanged ratchet of LINE 0.88 / BRANCH 0.85**:
      **LINE 6573/6776 = 0.9700** and **BRANCH 1999/2202 = 0.9078**. Against Phase 5's gate round 4
      (6514/6717 and 1993/2196) that is **59 more covered lines out of 59 more lines, and 6 more
      covered branches out of 6 more branches** - every line and every branch this increment's
      Phases 6 and 7 added is covered, which is what the ratios holding to four places on a
      denominator that moved says. **The gate was not touched.**
      **The two audits ran inside `check`**: `differential.DifferentialAuditTest`, 389 cases, no
      failure - which is also the suite that re-reads `doc/DEFECT-FIXES.md` after T045 - and the
      progression corpus by manifest digest, `PdfPayloadMapperTest$EveryRecordedGolden` 169 cases
      and `DefendantTypeResolverTest$EveryRecordedGolden` 7, none failed.
      **What this record does NOT cover, stated rather than implied.** The `quickstart.md`
      walkthrough has **not** been driven on a clean local `docker compose` stack in this pass. Its
      subject matter is covered by automated suites and each is named here so a reviewer can see
      what is and is not evidence: steps 1-5 by `e2e/GenerationFailureEndToEndIT`'s new case, which
      runs the same sequence against a real Postgres, a real file service, WireMock and an in-VM
      Artemis; step 6's other side of the boundary by `e2e/GenerationEndToEndIT`'s two new cases;
      and the three startup refusals by `config/ConfigurationValidationTest` -
      `a_zero_stale_after_refuses_to_start`, `a_negative_stale_after_refuses_to_start`,
      `a_non_positive_batch_age_refresh_refuses_to_start` and
      `the_completion_setting_is_no_longer_bound`.
      What no suite can stand in for is the **V7-on-a-dirty-volume** paragraph of the quickstart's
      own preamble, which was observed on a real volume when the file was written, and the image's
      entrypoint dispatch. The walkthrough is owed before the increment's merge and is carried as
      an open point.
      **The task is therefore `[~]` and not `[x]`**: the first half - the gates, the numbers and the
      two audits - ran and is quoted above; the second half, the walkthrough on a clean stack, has
      not. "It needs `docker compose`" explains the delay and does not stand in for the run, so the
      tick says half rather than claiming both. What closes it is the walkthrough itself, quoted
      here.

      **THE WALKTHROUGH, DRIVEN 2026-09-21** on the local `docker compose` stack, out of the image
      built from this branch's own jar (`./gradlew bootJar`, then `docker compose build app`). Every
      step's observed output is below, bounded, with no defendant detail. **One deviation from the
      stack file was forced and belongs to the laptop, not the service**: host port 5433 was held by
      an unrelated container, so `fileservice-postgres` was published on 15433 through a compose
      override. The app reaches it as `fileservice-postgres:5432` inside the network, so no
      observation below depends on the host mapping.

      **The preamble - V7 on a dirty volume, which no suite stands in for.** A clean volume was
      migrated to V6 only (`SPRING_FLYWAY_TARGET=6` -> *"Successfully applied 6 migrations ... now
      at version v6"*), one batch was seeded through `psql` FAILED / `GENERATION_TIMED_OUT` /
      completed by `RECONCILER` - the row a pre-004 local run leaves - and the app was then started
      normally:

      ```text
      Migrating schema "public" to version "7 - retire reconciler vocabulary"
      Migration of schema "public" to version "7 - retire reconciler vocabulary" failed!
          Changes successfully rolled back.
      Intake could not be started; the next probe will try again.
          type=org.flywaydb.core.internal.exception.FlywayMigrateException
      ```

      and from Postgres's own log, verbatim as `quickstart.md` quotes it:

      ```text
      ERROR:  check constraint "register_batch_failure_reason_chk" of relation "register_batch"
              is violated by some row
      ```

      `/actuator/health/readiness` answered `{"status":"DOWN"}` and went on answering it, retrying
      every ten seconds; `max(version)` in `flyway_schema_history` stayed **6**; and the seeded row
      was still there afterwards, unchanged. The migration refuses rather than dropping what it
      cannot admit, which is the behaviour the file argues for.
      **One thing the quickstart leaves implicit and this run makes explicit**: the constraint name
      is in the *database's* log, not the service's. The service names a caught exception by class
      and never carries a library's message (design_rules.md, *"never attach a throwable this
      service did not write"*), so a pod's own log says `FlywayMigrateException` and an operator
      goes to Postgres for the constraint. Worth a sentence in the quickstart; it is not a defect.
      After `docker compose down -v` and a fresh `up`: *"Successfully applied 7 migrations"*,
      readiness `UP`, and the two narrowed constraints read back exactly as V7 writes them -
      `failure_reason` admitting the six with `NOT_COMPLETED_BY_NEXT_RUN` among them and
      `GENERATION_TIMED_OUT` gone, `completed_by` admitting `'EVENT'` alone.

      **Step 1 - a batch that will never hear anything.** `sdg-echo` was never started, per the
      quickstart's *"note the omission"*. Two registers for one court centre day were recorded, then
      `./startup.sh generate-register --date 2026-09-21 --ignore-flag` out of the built image:

      ```text
      systemdocgenerator accepted the render request for batch 89786463-…, which now waits for
          its document on the public-event topic.
      batch=89786463-… state=GENERATING records=2
      date=2026-09-21 released=0 registers=2 batches=1 requested=1 deferred=0
      ```

      and in the store: `GENERATING`, `requested_at` = now, `failure_reason` NULL, a
      `payload_file_id` minted, two rows stamped with the batch. Exactly the two reads the
      quickstart asks for.

      **Step 2 - nothing chases it.** Fifteen minutes later (09:29 -> 09:44 London), WireMock's
      whole request journal was

      ```text
      ["/kv/.appconfig.featureflag%2FYotResultsDistributionService?api-version=2023-11-01&label=LOCAL",
       "/systemdocgenerator-command-api/command/api/rest/systemdocgenerator/generate-document"]
      ```

      the flag read and the one command, and **no `document/{id}`, on any schedule**. That is SC-004
      seen from outside, and it is the whole of the removal. The readings were still moving:
      `yotresultsdistribution_oldest_generating_age` **497.0** seconds, `yotresultsdistribution_oldest_pending_age`
      0.0, `yotresultsdistribution_oldest_generated_age` 0.0 - FR-011 holding with the reconciler gone. (The
      third of the three is `oldest_generated_age`; the quickstart names only the first.)

      **Steps 3 and 4 - and the one place the quickstart's own numbers are wrong.** The batch step 1
      makes is made by the **operations command**, so its row carries `system_generated = false`,
      and `StaleBatchReleaser.manualGrace()` judges that kind by the longer of `stale-after` (30m)
      and `lock-at-most-for` (70m) - FR-017, and the design the spec states. Aging it by the
      quickstart's **31 minutes** therefore releases nothing, and the run observed at 47 minutes of
      age said so:

      ```text
      event=register_generation_run run_id=fc54e1ec-… gate=proceed reason=flag-on batches=0
      requested=0 generating=0 failed=0 pending=0 deferred=0 rows=0 … released_batches=0
      released_registers=0 contended=0 duration_ms=12
      ```

      Aged past 70 minutes instead (1h 18m), and with the schedule brought forward
      (`YOTRESULTSDISTRIBUTION_GENERATION_CRON='0 */2 * * * *'`, the quickstart's own local-only override),
      the run is the line the quickstart prints:

      ```text
      event=register_generation_run run_id=c0200717-… gate=proceed reason=flag-on batches=1
      requested=1 generating=1 failed=0 pending=0 deferred=0 rows=2 rows_generating=2 …
      snapshot=taken generated=0 notified=0 released_batches=1 released_registers=2 contended=0
      duration_ms=84
      ```

      beside the pass's own line, *"stale-batch pass gave back what the night before had not
      finished, and the run goes on to assemble it. released_batches=1 released_registers=2
      contended=0"*. And in the store:

      ```text
      89786463-…  FAILED      NOT_COMPLETED_BY_NEXT_RUN   completed_by NULL   system_generated f
      82fb5822-…  GENERATING  NULL                        completed_by NULL   system_generated t
      the two registers are on the NEW batch (count 2), and none on the old one
      ```

      The old batch keeps its row carrying what happened to it, its registers moved, and the court
      centre is getting its document tonight. **`quickstart.md` step 3 is wrong as written** - a
      31-minute age against a command-made batch, when the file's own step 1 makes one with the
      command - and the file's `reason=overridden` is `reason=flag-on` here, because the local
      WireMock stub answers the flag ON rather than the run being overridden. Both are the
      document's, not the code's: the code did exactly what FR-017 and the spec say. **No code was
      changed for either.**

      **Step 5 - the late outcome, and nothing happening.** `sdg-echo` cannot deliver this one: it
      ignores every `generate-document` already in the journal when it starts, deliberately, so a
      one-shot publisher built from `docker/sdg-echo/sdg-echo.py`'s own frame and envelope published
      a `document-available` naming the **old** batch's correlation and payload id. The listener
      acknowledged it and dropped it:

      ```text
      yotresultsdistribution_public_events_ignored_total{reason="terminal-batch"}  COUNT = 1.0
      ```

      and the store was unchanged - still `FAILED`, still `NOT_COMPLETED_BY_NEXT_RUN`,
      `document_file_id` NULL, and **0 rows in `register_notification`** for that batch. That is
      SC-003: the Youth Offending Team is told once, by the batch that actually rendered.

      **The 07:00 report's `BATCH_RELEASED` entry.** `./startup.sh report-exceptions` out of the
      same image:

      ```text
      event=yotresultsdistribution_exception run_id=7551227e-… kind=BATCH_RELEASED batch_id=89786463-…
          court_centre_id=33333333-… register_date=2026-09-21 status=FAILED
          reason=NOT_COMPLETED_BY_NEXT_RUN age_seconds=66
      counts request_failed=0 request_late=0 batch_late=0 batch_failed=0 notification_failed=0
          batch_released=1 window_from=2026-09-21T06:00:00Z window_to=2026-09-21T08:49:05Z
      event=exception_report_run run_id=7551227e-… entries=1 truncated=0 delivered_log=ok
          delivered_email=skipped outcome=delivered duration_ms=56
      ```

      The kind is right, it is informational rather than a failure, and `delivered_email=skipped`
      is the command's own rule (`--email` was not given). **One defect was found here and is
      recorded below rather than fixed.**

      **Step 6 - the other side of the boundary.** Two more registers were recorded for the same
      court centre day while the new batch was two minutes into `GENERATING` - well inside the
      30-minute cutoff a schedule-made batch is judged by - and the next run:

      ```text
      event=register_generation_run run_id=202134dd-… gate=proceed reason=flag-on batches=0
      requested=0 generating=0 failed=0 pending=0 deferred=1 rows=2 rows_generating=0
      rows_failed=0 rows_pending=0 rows_deferred=2 … released_batches=0 released_registers=0
      contended=0 duration_ms=8
      ```

      The in-flight batch was untouched, its two registers were still stamped, the two new ones were
      **deferred** and the assembler passed the court centre day over - US2.1 and US2.2, and the
      quickstart's `released=0 deferred=1` (the line's fields are `released_batches` and
      `released_registers`; the file's prose writes `released=`).

      **The three start-up refusals**, each against the built image:

      ```text
      YOTRESULTSDISTRIBUTION_GENERATION_STALE_AFTER=0s
        -> yotresultsdistribution.generation.stale-after (PT0S) must be positive — a timeout that never
           expires is a run that never ends            (PropertiesValidator, exit 1)
      YOTRESULTSDISTRIBUTION_GENERATION_BATCH_AGE_REFRESH=-1m
        -> yotresultsdistribution.generation.batch-age-refresh (PT-1M) must be positive — a timeout that
           never expires is a run that never ends      (PropertiesValidator, exit 1)
      YOTRESULTSDISTRIBUTION_GENERATION_COMPLETION=poll-only
        -> "Started Application in 2.819 seconds", and the string `completion` appears NOWHERE in
           the start-up log: the key is gone and a deployment still setting it is setting nothing
      ```

      **What the walkthrough could not be driven verbatim from, and what it had to substitute.**
      `quickstart.md` step 1 names `./scripts/put-message.sh` and `docker/samples/distribution-command.json`,
      step 5 names `./scripts/publish-document-available.sh`, and steps 1 and 4 write
      `java -jar build/libs/*.jar generate-register`. **None of the three files exists in this
      repository**, and the `java -jar` form cannot dispatch a command at all - a Boot 4 fat jar's
      manifest names `JarLauncher`, which is the whole reason `docker/startup.sh` runs `CliMain`
      through `PropertiesLauncher` and says so in a comment. The command was therefore run as
      `docker compose exec app ./startup.sh generate-register …`, which is the deployed form and
      what the brief asks for. The registers were seeded through `psql` because the compose stack
      runs `YOTRESULTSDISTRIBUTION_PAYLOAD_MODE=STUB` and records nothing - the 002 quickstart states that
      plainly - so there is no message that produces a register on this stack at all. The
      quickstart's `SELECT … FROM register_record` is `processed_output`; there is no
      `register_record` table. All five are `quickstart.md`'s to correct and none of them is the
      service's behaviour.

      **THE ONE DEFECT THE WALKTHROUGH FOUND. Recorded, NOT fixed** (the brief for this run forbids
      a fix, and this record is the report).
      `adapter/report/LogEventReportSink`'s summary event `yotresultsdistribution_exception_report` carries
      **five** counts and omits `batch_released`, while `EmailReportSink`'s CSV header carries six
      (*"Six since increment 004"*, `EmailReportSinkTest:85`) and `ReportExceptionsCli`'s counts line
      carries six. The morning observed above therefore wrote a summary reading
      `request_failed=0 request_late=0 batch_late=0 batch_failed=0 notification_failed=0
      truncated=0` beside **one** `yotresultsdistribution_exception` event. That is precisely the state the
      sink's own javadoc says must never occur: *"without that eleventh field a query would find
      fewer events than the counts imply and nothing would say whether a sink had broken"*. The
      omission is pinned in place by `LogEventReportSinkTest.THE_ELEVEN_SUMMARY_FIELDS`, which 004
      did not extend when it added the sixth kind, so no suite failed.
      **Severity MEDIUM, impact LOW-to-MEDIUM**: `BATCH_RELEASED` is informational, so no alert
      misfires and nothing is lost - but the Log Analytics surface is the primary one for a
      deployed environment, and a night that released a batch reads there as a report whose sink
      dropped an event. The fix is one `value("batch_released", …)` and the test's list, test-first;
      it touches `adapter/report/` and `src/test/**/adapter/report/`, both this tree's. **It is left
      for the coordinator to assign**, because a fix here is a code change this run was told not to
      make. T036's instruction was to check *"any switch over kinds for exhaustiveness"*, and this
      sink writes a hand-listed set of `value(...)` calls rather than a switch, which is how a sixth
      kind passed it.

      **CLOSED the same day, on the coordinator's ruling, test-first.** Not a `DEFECT-FIXES.md` row:
      the register's two oracles are the function app and progression's leg, and this is neither -
      it is **004 regressing against 003**, caught by 004's own walkthrough, so it is recorded here
      where the walkthrough that found it is recorded.
      *Red*: `LogEventReportSinkTest`'s `THE_ELEVEN_SUMMARY_FIELDS` became
      `THE_TWELVE_SUMMARY_FIELDS` with `batch_released` in it, the empty-morning case went from five
      noughts to six, and a new case - `a_released_batch_is_counted_on_the_summary_line` - asserts
      `batch_released=1` on a report whose only entry is a released batch, which is the morning the
      walkthrough actually produced. `ExceptionReportEndToEndIT`'s `ELEVEN_SUMMARY_FIELDS` became
      `TWELVE_SUMMARY_FIELDS`.
      `flock … ./gradlew test --tests '*LogEventReportSinkTest*'` → **12 tests completed, 3 failed**,
      all three assertion failures and none a compile error, as the red convention requires.
      *Green*: one `value("batch_released", counts.get(ExceptionKind.BATCH_RELEASED))` between
      `notification_failed` and `truncated`, the message text's *"five counts"* now *"six counts"*,
      and the class javadoc restated as **one count per `ExceptionKind`** with the regression named
      in it so the next kind does not repeat it.
      `flock … ./gradlew test --tests '*LogEventReportSinkTest*' --tests '*ExceptionReportEndToEndIT*'
      --tests '*EmailReportSinkTest*' --tests '*ReportExceptionsCliTest*'
      --tests '*ExceptionReportServiceTest*' checkstyleMain checkstyleTest pmdMain` →
      **BUILD SUCCESSFUL, exit 0**; `ExceptionReportEndToEndIT` 4 tests / 0 failures,
      `LogEventReportSinkTest$TheSummary` 5 tests / 0 failures.
      **The other two surfaces were already right** and are left alone: `EmailReportSink`'s CSV
      header has carried six since 004 (*"Six since increment 004"*) and `ReportExceptionsCli`'s
      counts line prints six. All three now agree.
      **Four numbers in increment 003's documents moved with it**, dated, because they state the
      shape of a line 004 changed and would otherwise contradict the code:
      `003/data-model.md`'s *"Eleven fields"* (the one place the number is stated),
      `003/plan.md`'s summary sentence and its `LogEventReportSinkTest` matrix row, and
      `003/quickstart.md`'s KQL, which now projects `batchReleased`. Ratification (d) in the
      close note is the precedent: a completed increment's document that describes behaviour this
      increment changed is amended with a dated clause rather than left wrong.

      **The tick was `[~]` for a few hours and is now `[x]`.** The walkthrough ran end to end and
      the service behaved correctly at every step - the release, the re-batching, the ignored late
      outcome, the deferral, the three refusals and the V7 refusal all as designed. What held it at
      half was that two steps did **not** behave as `quickstart.md` wrote them (step 3's 31 minutes
      against a command-made batch, and the five artefacts named above that do not exist) and that
      the run exposed one defect. **Both are closed**: the defect is fixed test-first and recorded
      immediately above (`b0f87b54`), and `quickstart.md` is corrected against the stack this run
      was driven on (`4de381bd`) - the 71-minute cutoff with FR-017's reason and the
      `system_generated = true` alternative, the psql seeding under `YOTRESULTSDISTRIBUTION_PAYLOAD_MODE=STUB`
      in place of the three files that do not exist, `./startup.sh` in place of the `java -jar`
      form that cannot dispatch, the by-hand publisher for the late outcome with `sdg-echo`'s
      start-up behaviour explained, `processed_output` for `register_record`, `reason=flag-on` for
      `reason=overridden`, the extra recorded register step 6 needs to make `deferred=1` reachable,
      the sentence saying the constraint name is in Postgres's log and not the pod's, and the
      5433 host-port collision with the CPP dev environment's own container.
      **The corrected quickstart now matches what was observed at every step**, which is what `[x]`
      says.)

**Phase close**: `flock … ./gradlew build` green; whole-increment review gate (code-reviewer, qa,
spec-validator, then Codex) before the merge to `main`.

**Phase 9 closed (2026-09-21), and with it T029-T046.** The gate above is the phase's close and the
increment's: `flock -w 7200 … ./gradlew jacocoTestReport build -Dtest.noFailFast=true` →
**BUILD SUCCESSFUL, exit 0, 3670 tests over 580 suites, 0 failures**, LINE 0.9700, BRANCH 0.9078.

**Three things are owed and none of them is this tree's to do.**

1. The `quickstart.md` walkthrough on a clean local stack (T046 above), which needs `docker compose`
   and the built image.
2. The four `.claude/agents/*.md` scope paragraphs, which still list 001-003 and not 004. The
   coordination contract puts those paragraphs in the 005 tree, which is editing the same sentences
   for the REST surface (T044 above).
3. `.specify/memory/constitution.md` (3.2.0), which still describes the retired reconciler as live
   in four places and is on this tree's must-not-touch list. Phase 5's close asked the coordinator
   to assign the amendment; it is still unassigned, and the constitution outranks every document
   Phase 9 did amend.

**And one hand-off from Phase 7 is still open**: the `batch/cli/ReportExceptionsCliTest` case that
says the table and the CSV carry `BATCH_RELEASED`. The kind travels by name, so nothing is broken
without it; what is missing is the case that says so.

## The increment gate, round 1 (2026-09-21)

The whole range `92f5705..HEAD` read at once by `code-reviewer`, `qa` and `spec-validator`. Thirteen
findings between them, most of them the same three seen three times. What each one changed:

* **CLAUDE.md (HIGH, all three).** `10eeb140` rewrote the SPECKIT block to name `specs/004` as the
  current plan and 003 as complete, and CLAUDE.md is on this tree's must-not-touch list. Reverted to
  its `92f5705` text (`chore(core): put CLAUDE.md's speckit pointer back as the other tree owns it`),
  with the two-line wording handed to the 005 tree in the commit body. Nothing compiles against the
  block, so nothing else moved.
* **The two `batch/cli` fixtures (HIGH, all three).** `39b6aeaf`'s edits to `CliMainTest.settings()`
  and `GenerateRegisterCliTest.settings()` are forced by `GenerationProperties`' arity and cannot be
  reverted without leaving the suite uncompilable. Recorded as a hand-off at **T002**, in the shape
  T036's took, and **T048**'s claim that no commit in the branch touches `batch/cli` is corrected
  there: it held for `4d3f9e00..HEAD` when it was written and holds for neither range now.
* **A pass interrupted partway lost its account (MEDIUM, code-reviewer).** Each stale batch commits
  by itself, so a store that went away between two batches left the batches before it durably
  released with nothing said about them: no line, no counter, and a run line reading
  `released_batches=0`. Fixed test-first - `StaleReleaseProgress` on the port, the store telling it
  as each transaction commits, and the pass counting and saying each batch there rather than after
  an answer it may never get. Red recorded on `StaleBatchReleaserTest` (five assertions) and
  `RegisterStoreIT` (one); green on both, plus `StaleReleaseConcurrencyIT` and the privacy sweep,
  which found the new WARN unreached and now drives it.
* **`design_rules.md` beyond its three granted sections (MEDIUM, code-reviewer).** Named at **T041**
  with the reason each was needed and handed to the 005 tree; not reverted, because each paragraph
  would otherwise describe a class that is not there.
* **T026's missing red (MEDIUM, qa).** Staged as the subtractive red the convention asks for and
  recorded at **T026**: the two production expressions put back, 3 assertions red, reverted, green
  again. No TDD exception is claimed and the "Approved TDD exceptions: None" section is unchanged.
* **The FR-019 matrix row (MEDIUM, qa).** `plan.md`'s row named a `ReportExceptionsCliTest` case
  that does not exist. The row now says the CLI half is owed by the 005 tree, which is where the
  hand-off already sat, so the matrix and the suite agree.
* **P2's fixed-behaviour cell (MEDIUM, spec-validator; LOW, code-reviewer).** Amended with a dated
  clause naming the release pass, for the reason recorded at **T045**: a fixed-behaviour cell
  describes the fix that is live, and SC-008 says no document refers to the grace period after this
  increment.
* **`RegisterBatchRepository`'s transitional javadoc (LOW).** Reworded: the three in-flight reads
  are `BatchAgeSweep`'s, on its own fixed delay.
* **`spec.md` US2 scenario 3 (LOW, spec-validator).** The clause said the age is measured against
  the store's own clock; the plan, the statement and the boundary test all compute the two cutoffs
  from the run's clock and pass them in. The spec is amended to the design that was built, with the
  amendment dated, rather than the statement changed at a gate.

**Not done here, and why.**

* `.specify/memory/constitution.md` (3.2.0) still describes the reconciler as live in four places.
  It is on this tree's must-not-touch list and the finding itself says so; it is the third item of
  the three owed above and the coordinator is asked, for the third time, to assign the amendment
  before the merge.
* `.specify/feature.json` is in neither tree's ownership list and `6c95556d` points it at
  `specs/004`. Left as it is: whichever increment is active is what the pointer is for, and 005
  will set it to its own. Flagged so the merge conflict is expected rather than found.
* The `quickstart.md` walkthrough (T046) is still owed on a clean local stack, unchanged from the
  Phase 9 close.
* The two optional coverage suggestions - `failAndReleaseStale` over a closed `DataSource` in
  `RegisterStoreIT`, and parameterising `GenerationWiringContextTest`'s incomplete-context case over
  each missing collaborator - are left open, as they were at gates 4 and 5.

**Re-gated after round 1.** `flock -w 7200 … ./gradlew build -Dtest.noFailFast=true` →
**BUILD SUCCESSFUL, exit 0, 4m 16s, 3672 tests over 580 suites, 0 failures, 0 errors, 0 skipped**,
and `flock -w 7200 … ./gradlew jacocoTestReport check -Dtest.noFailFast=true` →
**BUILD SUCCESSFUL, exit 0, 4m 29s**, with `checkstyleMain`, `checkstyleTest`, `pmdMain`, `pmdTest`
and `jacocoTestCoverageVerification` all run and all green. **Coverage, against the unchanged
ratchet of LINE 0.88 / BRANCH 0.85: LINE 6586/6789 = 0.9701 and BRANCH 2001/2204 = 0.9079.**
Against T046's own run (6573/6776 and 1999/2202) that is thirteen more covered lines out of
thirteen more lines and two more covered branches out of two more - the observer, the account and
the interrupted line, all of them driven. The two new cases are the pass's interrupted account and
the store's announcement of each batch where it settles it. **The gate was not touched.**

**Gate round 2 (2026-09-21) sent back no finding about the code.** All three reviewers returned
nothing at all - twice each - and the round's three BLOCKERs are that absence, filed against
`(workflow)` with the hint "rerun the gate; check the reviewer prompt and tools". There is nothing
in this tree to fix for them: the three definitions under `.claude/agents/` are intact and
untouched by this range, and a reviewer that returns no result is a harness failure rather than a
defect of the branch. The round was therefore a verification, and it found the range where round 1
left it - fifty tasks ticked (T007 `[~]`, moved to Phase 5 as T050, which is ticked), the tree
clean at `5296c20e`, the ratchet in `gradle/test.gradle` untouched at LINE 0.88 / BRANCH 0.85, and
the boundary holding: `CLAUDE.md`'s net diff over the range is empty, `CliModeConfig`'s is
comment-only as the contract permits, and no commit in the range touches `src/main/**/batch/cli`,
an `api/` package, `openapi.yaml`, `acl/`, `docker/startup.sh`, the constitution or the build files.
(The three edits under `src/test/**/batch/cli` are the recorded hand-offs at **T002** and **T036**
- the two compile-forced `settings()` helpers and `ReportExceptionsCliTest`'s counts line - and not
a claim that nothing under `batch/cli` was touched at all.)

**Re-gated after round 2** (`flock -w 7200 … ./gradlew build -Dtest.noFailFast=true`) →
**BUILD SUCCESSFUL, exit 0, 4m 13s, 3672 tests over 580 suites, 0 failures, 0 errors, 0 skipped**,
with `checkstyleMain`, `checkstyleTest`, `pmdMain`, `pmdTest` and `jacocoTestCoverageVerification`
all run and all green. Identical to round 1's re-gate in every count, which is what a round that
changed no code should read.

## The increment gate, round 3 (2026-09-21)

Round 2's three reviewers returned eleven findings above LOW; ten of them name a file this tree may
not touch or a decision the coordinator owns, and are carried below unchanged. One was a defect and
is fixed.

**Fixed, test-first.**

* **An interrupted pass still lost its account on the run's own line (MEDIUM, qa).** Round 1 gave
  the pass a `StaleReleaseProgress` so that each batch is counted and said where it commits, and a
  store lost partway now leaves the WARN line and the three counters carrying the committed part.
  The run's line did not: `RunTally.releaseTally` was assigned from `releaseStale()`'s return, and
  a throw never delivers one - so a night whose pass had given a batch back wrote
  `released_batches=0` beside a pass line saying one, two accounts of one night under one `run_id`.
  `releaseStale` now has a second form taking the caller's account, told in the same `finally` the
  counters and the pass's line are written from, and the run passes its own. Red on
  `StaleBatchReleaserTest`'s two new cases (`ReleaseTally[-1, -1, -1]` against the expected
  `[1, 2, 1]`, the sentinel for a pass that said nothing at all), green on both plus the job's new
  `a_pass_the_store_interrupted_still_puts_its_account_on_the_run_line`
  (commits `e060de1f`, `850c1f74`).

**Amended, in files this tree owns.**

* **T046 is `[~]`, not `[x]` (MEDIUM, qa).** Its second half - the quickstart walkthrough on a
  clean local `docker compose` stack - has still not run. The task now says half rather than
  claiming both, and names what closes it.
* **Round 2's boundary sentence (LOW, spec-validator).** It said no commit in the range touches
  `batch/cli`; that is true of `src/main` only. Corrected, naming the three `src/test` hand-offs.
* **FR-011's reach (LOW, spec-validator).** FR-011, its US2 scenario, the flow diagram and the
  README said the three batch-age readings are refreshed in every non-command instance;
  `BatchSweepConfig` is additionally conditional on `yotresultsdistribution.generation.enabled`, which is
  what `research.md` and `plan.md` describe. All four now say "that carries the generation half",
  with the spec's amendment dated.
* **`SchedulingInfrastructureConfig`'s javadoc (LOW, code-reviewer).** It listed three schedules a
  command JVM must not replicate; there are four.

**Carried, not fixed, each for the reason the finding's own `fix_hint` gives.**

* The two `batch/cli` `settings()` helpers and the three out-of-grant `design_rules.md` paragraphs
  (HIGH/MEDIUM, all three reviewers): every one of the six findings asks for ratification rather
  than a revert, and a revert of the first two leaves the suite uncompilable. Unchanged since
  round 1, still owed by the coordinator.
* `.specify/memory/constitution.md` (MEDIUM, spec-validator): asked for the fourth time.
* `.specify/feature.json`, `specs/003-exception-report/quickstart.md` and `CLAUDE.md`'s SPECKIT
  block (LOW): ownership items for the merge, unchanged.
* `design_rules.md`'s Persistence bullet, which does not name `BatchAgeSweep` among the repository
  readers (LOW, code-reviewer): the bullet is outside this tree's three granted sections, so it
  rides with the same paragraph hand-off rather than becoming a fourth out-of-grant edit.
* The two optional coverage suggestions and the `batch/cli` FR-019 CLI case (LOW): unchanged.

**Re-gated after round 3** (`flock -w 7200 … ./gradlew jacocoTestReport build -Dtest.noFailFast=true`)
→ **BUILD SUCCESSFUL, exit 0, 4m 19s, 3675 tests over 580 suites, 0 failures, 0 errors, 0 skipped**,
with `checkstyleMain`, `checkstyleTest`, `pmdMain`, `pmdTest` and `jacocoTestCoverageVerification`
all run and all green. **LINE 6589/6792 = 0.9701** and **BRANCH 2001/2204 = 0.9079** against the
unchanged ratchet of LINE 0.88 / BRANCH 0.85 - sixteen more covered lines out of sixteen more, and
two more covered branches out of two more, so every line and branch this round added is covered.
The gate was not touched.

## Increment gate — close (2026-09-21)

Three review rounds kept re-raising the same handful of items, every one of them a question of
ownership rather than of correctness, and each round's reviewers asked for a ratification rather
than a revert. This note is that ratification, made on the orchestrator's authority so the record
says the items are settled and no fourth round re-opens them.

**(a) The two `batch/cli` `settings()` helpers are 004's, and ACCEPTED.** `39b6aeaf`'s edits to
`src/test/**/batch/cli/CliMainTest.settings()` and `GenerateRegisterCliTest.settings()` were forced
by `GenerationProperties`' arity change: the record gained and lost constructor parameters in this
range, and a helper that builds one cannot compile against the old shape. Reverting them leaves the
suite uncompilable, which is why all three reviewers asked for ratification. They are accepted in
this range, recorded as hand-offs at **T002** and **T036**, and **the 005 rebase keeps them** — it
is editing the same two files for the REST surface and inherits the current arity, not the old one.

**(b) The three out-of-grant `design_rules.md` paragraphs are 004's, and ACCEPTED.** The
package-structure `batch/` line, the *"every drop is counted"* bullet and the *"one absorbed
refusal"* bullet all fall outside the three sections 004 was granted, and each was edited because
the paragraph would otherwise describe a class this increment deleted or a counter it added. They
are accepted as 004's. The Persistence bullet that does not name `BatchAgeSweep` (round 3, LOW)
rides with them rather than becoming a fourth out-of-grant edit, and is the 005 tree's to add when
it next touches that section.

**(c) The constitution is NOT 004's to amend, and the four reconciler/grace-period passages are
handed to 005.** `.specify/memory/constitution.md` at **3.2.0** still describes the reconciler and
the grace period as live in four places; the finding has been raised at every gate since Phase 5.
It is settled here in the other direction: **the 005 branch already carries the constitution at
5.0.1** and will re-point those four passages during its rebase, where the document is already open
and already moving. Amending 3.2.0 on this branch would be an edit 005 has to resolve twice.
Recorded as a hand-off to 005, **together with the four other ownership items** that have been
carried since round 1 and are hereby all 005's:

| Handed to 005 | What is owed |
|---|---|
| `.specify/memory/constitution.md` | the four reconciler / grace-period passages, re-pointed at the release pass |
| `CLAUDE.md` | the SPECKIT pointer block (reverted here by `10eeb140`'s undo; the wording is in that commit body) |
| `src/main/**/batch/cli/GenerateRegisterCli.java` | the *"four failure reasons"* comment, which is six |
| `src/test/**/batch/cli/ReportExceptionsCliTest` | the case that says the printed table and the CSV carry `BATCH_RELEASED` |
| `.specify/feature.json` | the active-increment pointer, which 005 sets to its own |

**(d) `specs/003-exception-report/quickstart.md`'s FR-016 edit is RATIFIED as 004's.** T043 touched
a completed increment's quickstart because the sentence it corrects describes behaviour 004
changed, and a completed spec that describes a retired mechanism is worse than a spec with a dated
amendment in it. It stands.

**(e) The two test flakes from gate round 1 are NOT defects.** The `TTLExpiredException` on the
Service Bus emulator and the three-minute `await` both occurred while the machine was asleep, not
under load: the emulator's lock and the awaited condition are both wall-clock bound, and a
suspended host expires the first and exhausts the second without anything in this repository having
gone wrong. Neither has reproduced on any run since, including every re-gate above. No quarantine,
no `@Disabled`, no widened timeout.

**The walkthrough (T046).** Driven end to end on 2026-09-21 against the local `docker compose`
stack and the image built from this branch — the V7-on-a-dirty-volume preamble, steps 1 to 6 and
the three start-up refusals — and recorded in full at **T046** above. **The service behaved
correctly at every step**: the stale command-made batch was failed `NOT_COMPLETED_BY_NEXT_RUN` at
its own (longer) cutoff and its registers were re-batched and re-rendered in the same run; the late
`document-available` for the ended batch moved nothing and was counted under `terminal-batch`; the
07:00 report carried the batch as the informational `BATCH_RELEASED`; a batch inside its cutoff was
left alone and its court centre day deferred; WireMock's journal held no `document/{id}` after
fifteen minutes; and V7 refused a dirty volume by name, left the schema at V6 and left the row
where it was.

**The walkthrough left two things open. The coordinator ruled the same day that both are fixed
before the merge, and both are now CLOSED.**

1. **The defect — CLOSED, `b0f87b54`, test-first.** `adapter/report/LogEventReportSink`'s summary
   event carried five counts and omitted `batch_released`, while the CSV sink and the command both
   carried six, so a morning whose only exception was a released batch wrote five noughts beside
   one `yotresultsdistribution_exception` event - the shape that sink's own contract tells a reader to read
   as a broken sink. Red on three assertions in `LogEventReportSinkTest` (the field list at twelve,
   the empty morning at six noughts, and a new
   `a_released_batch_is_counted_on_the_summary_line`), green on one
   `value("batch_released", …)`; `ExceptionReportEndToEndIT` moved with it, and four numbers in
   increment 003's documents were amended with dated clauses so they no longer contradict the code.
   **No `DEFECT-FIXES.md` row**: the register's two oracles are the function app and progression's
   leg, and this is 004 regressing against 003 - a row there would misfile it. Full record at
   **T046**.
2. **`quickstart.md` — CLOSED, `4de381bd`, documentation only.** Five corrections, all of them the
   document's and none of them the code's: step 3's cutoff is 71 minutes for a command-made batch
   with FR-017's reason written out (and the `system_generated = true` alternative for walking the
   schedule's arm at 31); the three helper files that do not exist are replaced by the psql seeding
   the STUB payload mode forces and by `./startup.sh`, which is also the deployed form; the
   `java -jar … generate-register` form is named as one that cannot dispatch and why; the late
   outcome is published by hand, with `sdg-echo`'s ignore-the-journal-at-start-up behaviour
   explained; `register_record` becomes `processed_output`. Four smaller ones went with them: the
   constraint name appears in Postgres's log and not the pod's (the never-attach rule), the 5433
   host-port collision with the CPP dev environment's `postgres-ccm`, `reason=flag-on` rather than
   `reason=overridden`, and the extra recorded register step 6 needs before `deferred=1` is
   reachable at all.

**Re-gated after the two fixes** (`flock -w 7200 … ./gradlew jacocoTestReport check
-Dtest.noFailFast=true`) → **BUILD SUCCESSFUL, exit 0, 4m 32s, 3676 tests over 580 suites,
0 failures, 0 errors, 0 skipped**, with `checkstyleMain`, `checkstyleTest`, `pmdMain`, `pmdTest` and
`jacocoTestCoverageVerification` all run and all green. **LINE 6590/6793 = 0.9701** and
**BRANCH 2001/2204 = 0.9079** against the unchanged ratchet of LINE 0.88 / BRANCH 0.85 - one more
covered line out of one more line and no new branch, which is what a single added `value(...)` call
and its three cases should read. **The gate was not touched.**

**The increment gate is closed on the orchestrator's authority**, with the five ratifications above,
the walkthrough as recorded at **T046** (now `[x]`) and both of its open items closed. **The next
step is the merge to `main`.**

---

## Dependencies & execution order

```text
Phase 1  (T001-T007)  settings, vocabulary, schema
   │   nothing can write the new reason until the constraint admits it
   ▼
Phase 2  (T008-T010)  the fenced store operation          [US1]
   │   the pass has nothing to call until this exists
   ▼
Phase 3  (T011-T014)  the pass and its cutoffs            [US1, US2]
   │   the run has nothing to call until this exists
   ▼
Phase 4  (T015-T020)  the run calls it, and says so       [US1, US2, US4]
   │   the reconciler's last caller goes here; only then is it dead code
   ▼
Phase 5  (T021-T028, T047-T050)  the removal, then the vocabulary retirement   [US4]
   │   the sweep replaces readings the removal took
   ▼
Phase 6  (T029-T032)  the readings survive                 [FR-011]
   │
   ▼
Phase 7  (T033-T036)  the drop is counted; the morning kind [US3, FR-019]
   │   the end-to-end case asserts both
   ▼
Phase 8  (T037-T040)  the proof and the privacy sweep
   ▼
Phase 9  (T041-T046)  documents, the P2 cell, the gates
```

**Hard orderings inside phases**: T001→T002, T003→T004, T005→T006 (pairs); T008→T010→T009;
T011→T012→T013→T014 (T013's red depends on T012 being minimal); T015→T016, T017→T018, T019→T020;
T021→T022, T024→T025, T026→T027; then T047→T048 and T049 and T050, all of them after T022 and T025;
T028 after every one of those, so the suite it characterises is the finished one;
T029→T030→T031→T032;
T033→T034, T035→T036; T039→T040.

**Cross-phase**: T045 depends on T011 (the test it names must exist) and T022 (the test it replaces
must be gone). T037 depends on T034 (the counter it asserts). T046 depends on everything.

### Parallel opportunities per phase

```text
# Phase 1 - three independent red runs; T007 is an observation and needs none of them:
T001  config/ConfigurationValidationTest + config/ReportPropertiesTest
T003  domain/BatchFailureReasonTest + domain/BatchStateTest
T005  persistence/SchemaMigrationV2IT

# Phase 4 - T015 and T019 in parallel; T017 follows T015 (same file)

# Phase 5 - three independent absence cases:
T021  config/GenerationWiringContextTest + config/CliModeConfigTest
T024  application/DocumentRendererTest + adapter/systemdocgenerator/SystemDocGeneratorClientTest
T026  config/ConfigurationValidationTest + config/PublicEventsHealthIndicatorTest

# Phase 7 - two independent test files:
T033  application/DocumentOutcomeSinkTest
T035  application/ExceptionReportServiceTest + batch/cli/ReportExceptionsCliTest

# Phase 9 - four documentation tasks, four different files:
T041  .claude/rules/design_rules.md
T042  README.md
T043  specs/002-.../quickstart.md + specs/003-.../quickstart.md
T044  .claude/agents/*.md
```

## Implementation strategy

**MVP is Phases 1–4.** After T020 the promise is met: a stale batch is released, its court centre
gets its document that night, and the run says so. Phases 5 and 6 are the removal and its one
replacement; 7 and 8 are the guarantees and the proof; 9 is the documents.

**Two orderings to respect under pressure.** Phase 2 must not be collapsed into Phase 3 — the
atomicity is the correctness of the feature and it belongs in the store with its own gate. And Phase
5 must not ship without Phase 6: it deletes the timer that takes three continuous readings, and a
Micrometer gauge whose publisher goes away does not fall to zero, it freezes at the last value it was
given and goes on looking live.

## Notes

- **The `[A]` tasks are verification, not exemption.** T007 records an observation about Postgres;
  T028 characterises a deletion whose behaviour three other tasks assert; T050 records what V7 does
  to a store that still holds a pre-004 row; T037, T038 and T046 verify
  assembled behaviour. None is a licence to skip a pair that could have been formed.
- **Nothing in this increment touches the intake half**, the register document, the inbound message,
  supersession's rules, the notification leg or the cutover lever. The 07:00 report is touched in
  exactly two places (T036's kind and T002's un-borrowed threshold) and nowhere else. A task that
  finds itself editing anything else has found a dependency the plan missed and stops.
- **Three things are outside this repository** and are flagged, not done: the Confluence design
  document's sections on the reconciler and the query; the Gliffy diagram's dashed query arrow and
  its step label; and a check of the deployment branches for a raw
  `yotresultsdistribution.generation.grace-period` or `.completion` override — probably empty, since neither
  key has an environment placeholder in this repository, which is also why T002 removes the old key
  rather than aliasing it.
