# Tasks: Exception report for production support

**Input**: Design documents from `/specs/003-exception-report/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests are MANDATORY** (Constitution Principle II) and every implementation task is strictly
preceded by the test task that guards it. Test names come from the plan's test matrix - do not
rename them without updating the matrix. Where a task lands a test the matrix does not name, the
task says so and the matrix gains the row in the same commit.

**Red-run convention (applies to every test task)**: a test task includes creating the minimal
**compile-safe seams** its test needs - interface declarations, record signatures, class skeletons
whose methods throw `UnsupportedOperationException` - so that the recorded red run is a **failing
assertion**, never a missing class or a compile error. The failing assertion is quoted in the test
task's commit narrative; the paired implementation task's narrative quotes the green run.

**Phase 1 tasks are infrastructure, not TDD pairs** (constitution mechanical exemption): their
commits record verification evidence instead of a red assertion. **Three Phase 1 tasks are exempt
from that exemption and carry ordinary red/green pairs**: the properties record and the properties
validator, whose defaults and refusals an `ApplicationContextRunner` asserts, and the `V4`
migration, whose facts a Testcontainers `*IT` asserts. None is mechanical: a migration that creates
the wrong index, a record that binds the wrong default and a validator that admits a zero threshold
all look exactly like a healthy pod.

**[A] Acceptance/characterisation tasks** verify assembled behaviour (end-to-end suites, the
privacy sweep run, the container smoke). No implementation task follows them and no red run is
required; the task records the initial observed result.

### Approved TDD exceptions

**None in advance.** This increment started with no pre-approved exception of any kind, and none is
granted in advance. Every task below is either a red/green pair, a Phase 1 infrastructure task
recording evidence, an `[A]` characterisation, or a documentation task exempt from the loop. If a
pair cannot be formed, the exception is written into this section with the design owner's dated
approval **before** the commit lands, in the shape 002's exception blocks use - never argued for
afterwards in a commit body.

Two were found afterwards, by review gates 1 and 7, and neither was written here before its commit
landed - which is the failure the paragraph above describes and not a licence. Both are now granted
and dated, and both are recorded here rather than argued for in a commit body:

- **`0e0e7b1`** "the `UUID_SHAPE` shape check on `yotresultsdistribution.report.email.template-id`", landed
  inside T003's green commit. What landed without a red is one rule in `config/PropertiesValidator`:
  a template id that is set but is not a UUID refuses startup. **No red run was recorded for it**
  because it was written as part of the settings surface T003 shipped rather than as an answer to a
  failing case, and no exception was recorded in this block before that commit landed - which is
  exactly what the paragraph above requires. It is held now by
  `ConfigurationValidationTest.ReportRefusals.a_malformed_template_id_refuses_to_start`, which
  landed at `de21621` and was **green on introduction**; its commit body records it as an `[A]`
  characterisation of an already-shipped rule and claims no exception for it. The grounds are that
  the rule is fix P9's rule one morning earlier - a verbatim reuse of the one already pinned by
  `ConfigurationValidationTest.a_template_id_that_is_not_a_uuid_should_fail_startup` - and that
  re-landing it as a pair would mean reverting a correct refusal in order to watch it fail.
  **Approved: design owner, 2026-09-15.**

- **`2a50b81`** "T068's `config/ReportEmailConfig`, landed with no test of its own." What landed
  without a red is a whole configuration class - two beans, one condition and an HTTP client - under
  a task whose named green was `T063`'s sink-selection cases, which are unit cases over the sink and
  assert nothing about which beans a context holds. **No red run was recorded for it** because the
  wiring was treated as the delivery of the sink the cases already covered rather than as a claim of
  its own, and no exception was written in this block before that commit landed. It is held now by
  `config/ReportEmailConfigTest`, which landed at `9aa0d47` with four red cases - the pod that holds
  two sinks, the pod that holds one, the `PayloadFileStore` on a context with the generation half
  off, and the startup refusal naming the half that asked - and by the defect fix those reds found,
  at `bd56721`: a pod with the generation half off and the e-mail output on had no
  `PayloadFileStore` and no file-service datasource, and would not have started. The grounds are
  that the pair has been formed after the fact, in full, and that rewriting unpushed history to
  interleave it was judged the higher risk - the same judgement 002's Phase 2 block records.
  **Approved: design owner, 2026-09-15.**
  The approval carries a condition: **a wiring task's test is its context case**, and a third
  occurrence of a configuration class landing without one is reverted and re-landed as a pair, not
  excused.

No other exception of this kind is pre-approved.

**No `doc/DEFECT-FIXES.md` row is added, amended or flipped anywhere in this increment.** There is
no legacy oracle for an exception report: neither the function app nor progression's leg produced
one, and the four instruments are design section 11 promises that were never built rather than
behaviours built wrongly. `RegisteredDefectFixes` and `DifferentialAuditTest` are untouched and must
stay green. A task that finds itself wanting a `C` or `P` number has found a defect in 001 or 002,
not in this increment, and it stops and asks.

**Conventions**: package root `uk.gov.hmcts.cp.yotresultsdistribution`; production code under
`src/main/java/uk/gov/hmcts/cp/yotresultsdistribution/`, tests under
`src/test/java/uk/gov/hmcts/cp/yotresultsdistribution/`. `*IT` suites need Docker and run inside
`./gradlew test`; there is no separate `integrationTest` task. Conventional Commits on
`003-exception-report`; the accepted types are `feat`, `fix`, `chore`, `docs`, `test`, `refactor`,
`build`, `ci` and `style` (`config` is not one of them; a configuration change is a `chore` or a
`build`). No AI attribution in any commit, comment, document or test name. Every phase ends with a
green `./gradlew build` and a review gate (new session) whose findings land as red test commit
followed by implementation commit, before the next phase starts. **Never two committing agents at
once.**

## Format: `[ID] [P?] [A?] [US#] Description`

- **[P]**: may run in parallel with other [P] tasks in the same phase (different files, no dependency
  on an unfinished task)
- **[A]**: acceptance/characterisation - see above
- **[US#]**: the spec user story the task traces to. Story-phase tasks only; Setup, Foundational,
  phase-close and Polish tasks carry no story label

---

## Phase 1: Setup (the configuration surface, its refusals, and the V4 indexes)

**Purpose**: the settings every later phase reads, the refusals that stop a morning that sends
nothing, and the two indexes that make the scheduled reads index scans rather than sequential ones.

### Tests first ⚠️

- [x] T001 [US5] `config/ConfigurationValidationTest` (extend) and `config/ReportPropertiesTest`
      (new) - **the defaults and the refusals, both red before either is written**.
      `report_defaults_are_the_documented_ones` reads every default off an
      `ApplicationContextRunner` - `enabled=false`, `cron="0 0 7 * * MON-FRI"`, `zone="Europe/London"`,
      `zoneOverrideAcknowledged=false`, `lockAtMostFor=15m`, `requestTerminalWithin=30m`,
      `notifiedWithin=15m`, `email.enabled=false`, and `yotresultsdistribution.intake.gauge-refresh` at `10m`.
      Then one case per refusal, each asserting the **message names the offending setting**:
      `a_negative_request_threshold_refuses_to_start`,
      `a_zero_batch_generated_within_refuses_to_start` (an explicitly set zero is refused; only an
      **unset** value resolves), `a_zero_notified_within_refuses_to_start`,
      `a_zero_gauge_refresh_refuses_to_start`, `a_lock_below_budget_plus_margin_refuses_to_start`,
      `a_zone_other_than_europe_london_refuses_without_the_acknowledgement`,
      `an_acknowledged_override_must_still_be_a_zone_the_jvm_knows`,
      `email_enabled_with_no_template_refuses_to_start`,
      `email_enabled_with_no_recipient_refuses_to_start`,
      `an_unparseable_recipient_refuses_to_start`, and the one resolution case,
      `an_unset_batch_generated_within_resolves_to_the_generation_grace_period`.
      Plus **SC-005 / user story 5 scenario 3**,
      `a_changed_threshold_takes_effect_in_that_environment_alone`: two `ApplicationContextRunner`s
      differing **only** in `yotresultsdistribution.report.request-terminal-within` (say `30m` and `90m`)
      yield two contexts whose resolved threshold differs and every other resolved setting matches,
      which is what "per environment, without a release" means when it is asserted rather than
      asserted-about. There is **no** window case, because there is no window setting, and **no**
      gauge-refresh resolution case, because that key has a literal default of its own.
      Seams: `config/ReportProperties` and its nested `Email` declared with their components and
      **no** `@DefaultValue`s; `config/YotResultsDistributionProperties` gains a nested
      `Intake(Duration gaugeRefresh)`, likewise undefaulted; `PropertiesValidator` gains a
      package-private `validateReport(...)` that returns without looking, and one new public constant
      `REPORT_RUN_BUDGET` declared as `Duration.ZERO`; the margin is the **existing**
      `PropertiesValidator.SCHEDULER_LOCK_MARGIN` and no second constant is introduced.
      Red: the defaults case reads `null` where `30m` was expected.
      (red at `814870a`: 152 tests, 12 failures, 0 errors, every one an assertion.
      `an_unset_batch_generated_within_resolves_to_the_generation_grace_period` on
      "expected: 10M but was: null" - the predicted red, on the resolution rather than on the
      defaults case, because AssertJ stops that case at its first failure and `cron` is read before
      the thresholds: `report_defaults_are_the_documented_ones` on
      "expected: \"0 0 7 * * MON-FRI\" but was: null". All ten refusals on
      "Expecting <Started application [...]> to have failed but context started successfully".
      Two deviations, both additive: the ten refusals live in `ConfigurationValidationTest`
      (the suite that owns every other startup refusal) and the defaults, the resolution and the
      SC-005 case in the new `ReportPropertiesTest`, which is how both files are touched without
      either restating the other; and `PropertiesValidator` gains a second new public member
      besides `REPORT_RUN_BUDGET` - `resolvedBatchGeneratedWithin(report, generation)`, seamed to
      answer the unresolved value, because the resolution case has to be able to read the resolved
      one. Two "should start" counterparts were added beside the refusals in the suite's own style,
      `an_acknowledged_override_of_a_known_zone_should_start` and
      `an_enabled_email_output_with_both_settings_should_start`; no listed name was changed.)

### Implementation

- [x] T002 [US5] `config/ReportProperties` and `config/YotResultsDistributionProperties` - the record bound at
      `@ConfigurationProperties(prefix = "yotresultsdistribution.report")` with its nested `Email` record and
      its `@DefaultValue`s, following `config/GenerationProperties`' style: `enabled=false`,
      `cron="0 0 7 * * MON-FRI"`, `zone="Europe/London"`, `zoneOverrideAcknowledged=false`,
      `lockAtMostFor=15m`, `requestTerminalWithin=30m`, `batchGeneratedWithin` **no default**,
      `notifiedWithin=15m`, `email.enabled=false`, `email.templateId`, `email.recipients`.
      **There is no `window` key**: the scheduled run's window runs from the previous occurrence of
      its own cron to now, so a duration beside the schedule would be the same fact written twice.
      In the same commit `YotResultsDistributionProperties.Intake` gains `@DefaultValue("10m") Duration
      gaugeRefresh` at `yotresultsdistribution.intake.gauge-refresh` - **not** under `yotresultsdistribution.report`,
      because the sweep refreshes the gauges on pods where the report and the generation half are
      both switched off, and a key on a record such a pod does not bind is a key it cannot read.
      **No validation logic here** - the refusals are T003's. Green: the defaults cases of T001.
      (green at `354855c`: `report_defaults_are_the_documented_ones` and
      `a_changed_threshold_takes_effect_in_that_environment_alone` pass; the resolution case stays
      red for T003. The zone default is written as `@DefaultValue(GenerationProperties.COURTS_ZONE)`
      rather than a second `"Europe/London"` literal, since it is the same fact and not a similar
      one.)
- [x] T003 [US5] `config/PropertiesValidator` (extend) - the ten report refusals of T001 written
      as the class's existing helpers write generation's, each naming its setting and quoting no
      operator-supplied value back; `REPORT_RUN_BUDGET = 5m` as the one new fixed constant, with the
      existing `SCHEDULER_LOCK_MARGIN` (10m) **reused** as the margin, so the shipped `15m` is
      exactly budget plus margin and is the same margin generation's 70m already uses - a second
      constant of the same value under a second name is a margin that can drift from itself; and the
      resolution of the **one** null duration, `batch-generated-within`, from
      `GenerationProperties.gracePeriod()`, published as a resolved value for the job to read, per
      data-model.md's resolution table. The zone rule is the **same rule** generation's schedule
      carries, not a similar one. Green: the refusal and resolution cases of T001.
      (green at `0e0e7b1`: `ReportPropertiesTest` + `ConfigurationValidationTest`, 152 tests,
      0 failures, 0 errors; `checkstyleMain`, `checkstyleTest`, `pmdMain`, `pmdTest` exit 0.
      The zone rule is stated once, as the package-private
      `GenerationProperties.requireTheCourtsZone(zone, acknowledged, zoneSetting, ackSetting,
      hour)`, which the generation record's own check now delegates to with its existing wording
      unchanged. `yotresultsdistribution.intake.gauge-refresh` is refused on the intake half's own list
      (`validate(YotResultsDistributionProperties)`) rather than inside `validateReport`, because that is
      where the sweep is and where the setting is; it is still one of T001's ten. The three
      duration refusals reuse `requirePositive`, which quotes the offending **duration** back as
      every other refusal in the class does - the "quote no operator-supplied value" rule is kept
      strictly where it matters, the template id and the recipients, neither of which is quoted.
      The same allowance covers the **zone id**, which the shared
      `GenerationProperties.requireTheCourtsZone` echoes in both its branches: that is generation's
      own wording, reused unchanged rather than restated, and a zone id is a tz-database name an
      operator typed rather than personal data or a credential. Review gate 1 asked for this to be
      said out loud rather than left to the reader, and it is now said: a duration and a zone id are
      echoed, a template id, an address and - since `789357d` - a cron expression are not.)
- [x] T004 [P] [US5] The `yotresultsdistribution.report` block and the one `yotresultsdistribution.intake.gauge-refresh`
      key in `src/main/resources/application.yaml` (and the matching keys in
      `src/main/resources/application-test.yaml` where the test profile needs them), every key
      written with a comment saying **what breaks without it**, following the
      `yotresultsdistribution.generation` block's shape and its "LOCAL DEFAULT ONLY" convention.
      **One mechanism per defaulted duration**, per data-model.md's resolution table:
      `lock-at-most-for` and `yotresultsdistribution.intake.gauge-refresh` are the two keys double-written
      **because something reads them through the placeholder resolver** - `@SchedulerLock`'s
      attribute and `@Scheduled(fixedDelayString)`'s, neither of which can see a record's
      `@DefaultValue`. They are not the only keys written in both places, and review gate 1 was
      right that the original wording said they were: the `yotresultsdistribution.report` block follows the
      `yotresultsdistribution.generation` block's convention and restates `enabled`, `cron`, `zone`,
      `zone-override-acknowledged`, `request-terminal-within`, `notified-within` and
      `email.enabled` beside their `@DefaultValue`s, so an operator reads a deployment's settings
      out of one file. What those seven have and the two above do not is a **single** reader - the
      record - so a yaml copy is documentation rather than a second mechanism.
      `batch-generated-within` gets **no** placeholder and no yaml key at all; and `yotresultsdistribution.intake.gauge-refresh` carries its own literal
      `10m` and borrows nothing from `yotresultsdistribution.generation.grace-period`, which an intake-only
      pod would not bind. There is no `yotresultsdistribution.report.window` key to write. Infrastructure: the
      commit records `./gradlew bootRun` still refusing for the documented reasons.
      (`1bbf07b`. `./gradlew bootRun` still refuses, for the documented reason and no new one:
      "IllegalStateException: yotresultsdistribution.results.system-user-id must be set when
      yotresultsdistribution.payload.mode is LIVE, because the payload fallback cannot be used without an
      identity to authorise with". `intake.gauge-refresh` sits beside `consumer` on the intake
      half rather than at the end of the file. `src/test/resources/application-test.yaml` gains
      `report.enabled: false` and `report.email.enabled: false` - not to bind, but for the reason
      it already pins `generation.enabled`: `application.yaml` reads
      YOTRESULTSDISTRIBUTION_REPORT_ENABLED, and the e-mail output's two settings become required at
      startup the moment it is true. HttpSurfaceTest, CliModeConfigTest and
      GenerationWiringContextTest, the three suites that boot the profile, are green.)
- [x] T005 [P] `persistence/SchemaMigrationV4IT` - `both_report_indexes_exist`,
      `the_non_terminal_index_carries_its_predicate` (read from `pg_indexes.indexdef`, asserting the
      predicate is spelled exactly as the query's own `WHERE` clause spells it, because Postgres
      matches a partial index by proving implication and a differently spelled equivalent is a
      planner coin toss), `v4_adds_no_column_constraint_or_table`, and
      `every_v1_to_v3_object_is_unchanged`. Seam: an empty
      `src/main/resources/db/migration/V4__processed_request_report_indexes.sql` carrying only its
      header comment, so Flyway has a V4 to apply. Red: `idx_request_non_terminal_created` is not in
      `pg_indexes`.
      (red at `5ecb612`: 4 tests, 3 failures, 0 errors, every one an assertion.
      `both_report_indexes_exist` on Expecting actual {flyway_schema_history_pk=..., idx_output_...}
      to contain key "idx_request_non_terminal_created";
      `the_non_terminal_index_carries_its_predicate` on "Expecting actual not to be null";
      `every_v1_to_v3_object_is_unchanged` on "Expected size: 16 but was: 14".
      The suite migrates a database of its own to V3, snapshots tables, columns, constraints and
      indexes, migrates the rest of the way and snapshots again - against the shared container,
      already migrated by every other persistence suite, "V4 changed nothing" is unobservable. The
      predicate is asserted on Postgres's own normalisation of the `IN` list, `status = ANY`
      carrying both states and neither terminal one, which is what the identically spelled query
      normalises to and therefore what the implication proof is trivial over.)
- [x] T006 `src/main/resources/db/migration/V4__processed_request_report_indexes.sql` exactly as
      data-model.md writes it: `idx_request_non_terminal_created ON processed_request (created_at)
      WHERE status IN ('RECEIVED', 'RETRYING')` and `idx_request_status_updated ON processed_request
      (status, updated_at)`, with the header comment saying why one is partial and the other is not.
      Additive and forward-only; never edited once applied. Green: T005.
      (green at `76fea55`: SchemaMigrationV4IT, 4 tests, 0 failures, 0 errors. The file is the
      data-model.md block verbatim, header comment included.)
- [x] T007 Phase close: `./gradlew build` green (PMD, Checkstyle at `maxWarnings = 0`, the JaCoCo
      gate unchanged and not loosened); **review gate 1** in a new session against
      `.claude/rules/workflow.md`; findings land as red/green pairs before Phase 2 starts.
      (build half done at `76fea55`: `./gradlew build` BUILD SUCCESSFUL, exit 0, 3302 tests over
      537 suites, 0 failures, 0 errors; Checkstyle at `maxWarnings = 0` over main and test, PMD
      over both, and the JaCoCo gate at LINE 0.88 / BRANCH 0.85, none of them loosened.
      **Review gate 1 ran against the committed Phase 1 content** with three read-only reviewers.
      Verdicts: `code-reviewer` **PASS** (1 medium, 2 low), `spec-validator` **DRIFT DETECTED**
      (1 medium, 4 low), `qa` **FAIL** - the last on coverage rather than on a failing suite, the
      build having been green throughout.
      Findings, and where each was closed:
      * a resolved rendering limit of zero was never checked positive - `resolvedBatchGeneratedWithin`
        was called for its side effect and had none - so `yotresultsdistribution.generation.grace-period=0s`
        started a pod whose every batch is late on its first morning. Red
        `a_zero_grace_period_makes_the_unset_rendering_limit_refuse` at `de21621`, green at
        `789357d`, which also names the key the value came from rather than the key nobody set.
      * `yotresultsdistribution.report.cron` had no validation at all, although `@Scheduled` reads it at
        refresh and `ReportWindow.sinceLastScheduledRun` reads it every run. Red
        `an_unparseable_cron_refuses_to_start` at `de21621`, green at `789357d` over
        `CronExpression.isValidExpression`.
      * the `UUID_SHAPE` refusal on the report's template id shipped at `0e0e7b1` untested.
        Characterised by `a_malformed_template_id_refuses_to_start` at `de21621`, **green on
        introduction and recorded as such**; the TDD gap is written into the "Approved TDD
        exceptions" block above as a proposal **awaiting the design owner's approval**, not as an
        approved exception.
      * `an_acknowledged_override_must_still_be_a_zone_the_jvm_knows` asserted only that the message
        named the setting, which the unacknowledged refusal also does, so it would have passed with
        the acknowledgement no longer read. Sharpened at `de21621` onto the words only the
        JVM-unknown branch says, with the other branch's words asserted absent.
      * an explicitly set `batch-generated-within`, a blank template id, and a recipient list with
        an empty entry (both spellings) had no case of their own. All three landed at `de21621` and
        all three were **green on introduction**, which their commit body says.
      * the `recipient == null` branch was unreachable - `List.copyOf` in `ReportProperties.Email`
        refuses a null element first - and the javadoc "Spring's own key ... the broker the
        completion events arrive on" sat above `INTAKE_GAUGE_REFRESH` rather than above
        `BROKER_URL`. Both closed at `789357d`.
      * two narrative corrections, closed in this commit: T004's "the ONLY key double-written", and
        T003's echo allowance, which covered the duration and not the zone id the shared zone helper
        also echoes.
      Nothing in the gate asked for a `doc/DEFECT-FIXES.md` row and none was added.)

**Checkpoint**: the settings exist, a bad one cannot start a pod, and the reads Phase 2 writes have
their indexes.

---

## Phase 2: Foundational (the repository reads, the schedule computation, the typed report model,
the two ports)

**⚠️ CRITICAL**: no user story work can begin until this phase is complete. Every later phase reads
these types.

### Tests first ⚠️

- [x] T008 [P] `persistence/ProcessedRequestReportReadsIT` - Testcontainers Postgres:
      `failed_since_returns_failed_rows_inside_the_window_and_nothing_else`,
      `non_terminal_older_than_returns_received_and_retrying_oldest_first`,
      `non_terminal_older_than_excludes_terminal_rows`,
      `oldest_non_terminal_answers_empty_on_an_empty_table`,
      `age_seconds_is_computed_by_the_database_not_the_jvm` (advance the JVM's clock and assert the
      age did not move), `every_read_goes_through_store_outage_translating` (an unreachable
      store is the intake half's own signal, not an untranslated driver failure), and
      `both_scheduled_reads_use_the_v4_indexes` - run `EXPLAIN` over `failedSince` and over
      `nonTerminalOlderThan` against a seeded table and assert the plan names
      `idx_request_status_updated` and `idx_request_non_terminal_created` respectively. **Run
      `ANALYZE` on the seeded table first and `SET enable_seqscan = off` for the session**: a table
      the planner has no statistics for is a table it will sequentially scan whatever indexes exist,
      and a handful of seeded rows is a table a sequential scan genuinely wins. With the seqscan
      disabled the assertion is "this query *can* use this index", which is the claim V4 makes; the
      unguarded form is a test that goes green on a small table and red on the production one, which
      is the wrong way round. The index is the whole reason V4 exists (SC-006, and a sweep read that
      runs every ten minutes for the life of every pod).
      Seams: `domain/ProcessedRequestSummary` record signature per data-model.md (ten components),
      and `persistence/ProcessedRequestRepository` gains `failedSince(Instant)`,
      `nonTerminalOlderThan(Instant)` and `oldestNonTerminal()` throwing
      `UnsupportedOperationException`. Red: the seam's throw is replaced by a failing assertion on
      the returned row count.
      (red at `e9b7145`: 7 tests, 7 failures, 0 errors, every one an assertion.
      `failed_since_returns_failed_rows_inside_the_window_and_nothing_else` on "Expecting actual:
      [] to contain exactly (and in same order): [994332d3-24ee-4d24-8156-93ad289c9e0a]";
      `both_scheduled_reads_use_the_v4_indexes` on "Expecting actual: \"Result  (cost=0.00..0.01
      rows=1 width=4)\" to contain: \"idx_request_non_terminal_created\"";
      `every_read_goes_through_store_outage_translating` on "Expecting actual throwable to be an
      instance of StoreUnavailableException but was UnsupportedOperationException". Three
      deviations, all additive. The suite migrates a **database of its own**, because "and nothing
      else" and "on an empty table" are unobservable against the container the other persistence
      suites share, and because an outage staged with `refuseConnectionsTo` must reach one suite's
      rows and no other's. The `EXPLAIN` case plans the statement the repository really prepared,
      taken off the driver by a recording connection rather than spelled a second time here - a
      copy proves what the copy can use - so `SET enable_seqscan = off` is issued on the same
      connection the plan is asked over, after `ANALYZE` on two thousand seeded rows. The
      recording connection and the private database live in a new
      `support/ReportReadsDatabase`, shared with T009, T010 and T011.)
- [x] T009 [P] `persistence/RegisterNotificationReportReadsIT` - Testcontainers Postgres:
      `failed_since_returns_failed_notifications_with_their_batchs_court_centre_and_register_date`,
      `failed_since_orders_oldest_first`, `failed_since_is_one_statement_not_one_per_row` (assert a
      single statement, because N+1 reads land on precisely the morning the list is longest),
      `age_seconds_is_computed_by_the_database_not_the_jvm`, and
      `the_email_address_column_is_never_selected` - the one personal value in the table, and a
      column that is never read cannot be logged by accident.
      Seams: `domain/FailedNotification` record signature per data-model.md (nine components), and
      `persistence/RegisterNotificationRepository.failedSince(Instant)` throwing. Red: the assertion
      on the joined court centre id fails against the seam's throw.
      (red at `58adec7`: 5 tests, 5 failures, 0 errors, every one an assertion.
      `failed_since_returns_failed_notifications_with_their_batchs_court_centre_and_register_date`
      on "Expecting actual: [] to contain exactly (and in same order):
      [84cff973-e4c4-40aa-a17a-6b53c3260048]"; `failed_since_is_one_statement_not_one_per_row` on
      "Expected size: 1 but was: 0 in: []". The two cases about the statement rather than the rows
      - the count and the select list - read the SQL the driver was asked to prepare, recorded by
      `support/ReportReadsDatabase`, because one statement and thirty produce the same list and a
      column that is never selected leaves no trace in a projection with no component for it.)
- [x] T010 [P] `persistence/RegisterBatchReportReadsIT` - Testcontainers Postgres, the **four** new
      batch reads, all of which answer `BatchException` and all of which compute their age in SQL:
      `late_pending_returns_pending_batches_assembled_before_the_cut_off_oldest_first`,
      `late_generating_returns_generating_batches_requested_before_the_cut_off_oldest_first`,
      `late_generated_returns_generated_batches_generated_before_the_cut_off_oldest_first`,
      `failed_since_returns_batches_failed_inside_the_window_and_nothing_else`,
      `every_read_carries_the_court_centre_the_register_date_and_the_bounded_failure_reason`,
      `every_age_is_computed_by_the_database_from_its_own_stage_timestamp` (advance the JVM's clock
      and assert no age moved; `latePending` measures from `assembled_at`, `lateGenerating` from
      `requested_at`, `lateGenerated` from `generated_at`, `failedSince` from `failed_at`), and
      `the_sdg_reason_column_is_never_selected_by_any_of_the_four` - it is systemdocgenerator's own
      words about a document, free text written by another system, and a column that is never read
      cannot reach a line, a label or the CSV (constitution Principle VII). One further case,
      `the_002_entity_reads_are_untouched`, asserts `pendingSince`, `generatingSince` and
      `generatedSince` still answer `RegisterBatch` and still answer what their own suites expect:
      the report needs projections carrying an age, and widening the generation leg's three reads to
      produce one would change a leg this increment does not otherwise touch.
      Seams: `domain/BatchException` record signature per data-model.md (seven components), and
      `persistence/RegisterBatchRepository` gains `latePending(Instant)`, `lateGenerating(Instant)`,
      `lateGenerated(Instant)` and `failedSince(Instant)` throwing `UnsupportedOperationException`.
      Red: the assertion on the returned row count fails against the seam's throw.
      (red at `dc6227c`: 8 tests, 8 failures, 0 errors, every one an assertion.
      `late_pending_returns_pending_batches_assembled_before_the_cut_off_oldest_first` on
      "Expecting actual: [] to contain exactly (and in same order):
      [1af67c46-8816-4f32-9895-029f0e0554db, 4ec6e473-3d84-4606-b59a-f57356ef6990]";
      `the_sdg_reason_column_is_never_selected_by_any_of_the_four` on "Expected size: 4 but was: 0
      in: []". The suite's timestamps are truncated to microseconds, which is what `timestamptz`
      holds, so `the_002_entity_reads_are_untouched` fails on the seam alone and its three
      assertions about 002's reads pass; that case also pins `pendingSince`'s own payload
      predicate, which is exactly why the report cannot borrow it.)
- [x] T011 [P] `persistence/RegisterStoreReportReadsIT` - Testcontainers Postgres, the fourth
      `BATCH_LATE` source: `recorded_unbatched_before_returns_registers_recorded_before_the_cut_off`,
      `recorded_unbatched_before_returns_exactly_the_rows_active_unbatched_returns_that_are_older`
      (the two reads share one predicate, written once, because two spellings of "active and
      unbatched" are two answers waiting to disagree),
      `a_register_recorded_while_the_flag_was_off_is_in_neither_read`,
      `age_seconds_is_computed_by_the_database_from_register_time`, and
      `active_unbatched_is_unchanged` - 002's read keeps the generation job as its caller and keeps
      answering entities.
      Seams: `domain/RecordedRegisterSummary` record signature per data-model.md (six components),
      and `application/RegisterStore.recordedUnbatchedBefore(Instant)` with
      `persistence/JdbcRegisterStore` throwing `UnsupportedOperationException` for it. Red: the
      assertion on the returned row count fails against the seam's throw.
      (red at `09a61b1`: 5 tests, 4 failures, 0 errors, every one an assertion;
      `active_unbatched_is_unchanged` passes, which is the point of it.
      `recorded_unbatched_before_returns_registers_recorded_before_the_cut_off` on "Expecting
      actual: [] to contain exactly (and in same order): [328c634a-c279-4468-8bbb-5f2efeaf59fe]".
      The shared-predicate case compares the two reads against each other rather than against two
      lists the suite wrote out, so it cannot pass by agreeing with a copy.)
- [x] T012 [P] `domain/LastScheduledRunTest` - the one most-recent-occurrence computation, which this
      increment needs **twice**: for the generation cron (a register the last scheduled generation
      run left unbatched) and for the report cron (the window `ReportWindow.sinceLastScheduledRun`
      opens). `the_most_recent_weekday_occurrence_before_an_instant_is_answered`,
      `a_bst_to_gmt_boundary_does_not_move_the_wall_clock_time`,
      `a_monday_morning_looks_back_to_fridays_run` (the case that makes a Monday report reach back
      to Friday rather than to Sunday morning),
      `a_register_recorded_after_the_last_run_is_not_late`, and
      `the_cron_and_the_zone_are_arguments_not_a_bound_setting` - the class answers for whichever
      schedule it is given, because two callers give it two. Seam: `domain/LastScheduledRun` - in
      `domain/`, not `batch/`, because it is a pure computation over a cron expression, a zone and an
      instant, and `ReportWindow` (a domain record) is one of its two callers, so a `batch` home
      would make a domain type depend on a batch one - with a `before(String cron, String zone,
      Instant instant)` throwing. Red: the answered instant is the next occurrence, not the previous
      one.
      (red at `5afd2dd`: 5 tests, 5 failures, 0 errors, every one an assertion.
      `the_most_recent_weekday_occurrence_before_an_instant_is_answered` on "expected:
      2026-09-16T17:00:00Z but was: 1970-01-01T00:00:00Z" - the predicted red on the answered
      instant, with the seam refusing rather than answering the next occurrence, which is the
      shape the red-run convention asks for. The Monday case is asserted against a daily cron as
      well as the weekday one, so it is about the schedule excluding the weekend rather than about
      the arithmetic happening to reach two days back.)
- [x] T013 [P] `domain/ExceptionReportModelTest` - **the plan's test matrix gains this row in this
      commit**, because two of these records carry behaviour and behaviour is pinned:
      `a_window_read_backwards_is_refused` (`from` must precede `to`, since a backwards
      window reports nothing and looks like a quiet morning),
      `counts_answers_zero_for_every_kind_that_has_none` (five numbers always, which is what makes
      an empty morning distinguishable from a morning the report did not run, FR-012),
      `since_last_scheduled_run_starts_at_the_previous_occurrence_of_the_cron` and
      `a_monday_window_starts_at_the_previous_friday_run` over
      `ReportWindow.sinceLastScheduledRun(cron, zone, now)` - **this and `LastScheduledRunTest` are
      the only two homes for the Monday-to-Friday arithmetic; every later suite that needs the
      window mocks it** - and `a_delivered_log_outcome_is_one_accepted_and_none_refused`.
      Seams: `domain/ExceptionKind` (**five** values), `domain/ReportWindow` with its factory,
      `domain/ExceptionEntry`, `domain/ExceptionReport`, `domain/DeliveryOutcome`,
      `domain/ReportSinkName`, `domain/DeliveryStatus` and `domain/ReportDeliveryReason` declared
      with their signatures and values per data-model.md, `ReportWindow`'s compact constructor empty
      and `counts()` throwing. Red: a backwards window is accepted.
      (red at `94b0c9d`: 5 tests, 5 failures, 0 errors, every one an assertion.
      `a_window_read_backwards_is_refused` on "Expecting code to raise a throwable" - the predicted
      red exactly; `counts_answers_zero_for_every_kind_that_has_none` on "Expecting code not to
      raise a throwable but caught UnsupportedOperationException: the report's counts are not
      computed yet". Two deviations. **The plan's test matrix already carries the
      `ExceptionReportModelTest` row**, written when the plan was, so no row was added and
      `plan.md` is untouched by this commit. And `DeliveryOutcome` gains a static
      `delivered(sink)` factory the data model does not spell out, because "a delivered LOG
      outcome is one accepted and none refused" is a shape, and a case asserting it over a
      constructor call would be asserting its own argument list. The window factory is asserted at
      the instant a run really asks - fifty milliseconds after its own occurrence - which is what
      decides whether the window opens at the run that last reported or at the one firing.)

### Implementation

- [x] T014 [P] `persistence/ProcessedRequestRepository` - the three reads written as data-model.md's
      SQL writes them, each returning `extract(epoch from (now() - <column>))::bigint AS age_seconds`
      in the same statement that selects the row, and each wrapped in `StoreOutage.translating(...)`
      like every other statement in the class. `failedSince` measures age from `updated_at` (the
      moment the row was parked); the other two from `created_at` (the moment it arrived).
      Green: T008.
      (green at `4d80259`: ProcessedRequestReportReadsIT, 7 tests, 0 failures, 0 errors; both
      scheduled reads plan onto `idx_request_status_updated` and
      `idx_request_non_terminal_created`. `checkstyleMain` and `pmdMain` exit 0. The three
      statements share one column-list constant and close it with their own age expression, the
      idiom `RegisterBatchRepository`'s `SELECT_BATCH` already uses; the in-flight predicate is
      the V4 index's own text, character for character.)
- [x] T015 [P] `persistence/RegisterNotificationRepository.failedSince(Instant)` - the join onto
      `register_batch` for the court centre id and register date, `ORDER BY n.sent_at`, age from
      `sent_at` **in SQL**, `email_address` deliberately not in the select list. Green: T009.
      (green at `b58cd42`: RegisterNotificationReportReadsIT, 5 tests, 0 failures, 0 errors; the
      one captured statement mentions no address column. `checkstyleMain` and `pmdMain` exit 0.)
- [x] T016 [P] `persistence/RegisterBatchRepository` - the four new reads exactly as data-model.md's
      SQL writes them, each answering `BatchException` and each computing `age_seconds` from its own
      stage timestamp in the same statement; `failure_reason` in every select list and `sdg_reason`
      in none. 002's `pendingSince`, `generatingSince` and `generatedSince` are **not** touched,
      renamed or widened: they answer entities for the generation leg and keep their callers.
      Green: T010.
      (green at `5c81087`: RegisterBatchReportReadsIT, 8 tests, 0 failures, 0 errors; the four
      captured statements mention no `sdg_reason`, and the three 002 entity reads still answer
      what their own suite expects. `checkstyleMain` and `pmdMain` exit 0. The four share one
      column-list constant and one private binding helper, because they are one shape asked of
      four stages and four copies of the binding would be four places for the projection to
      drift.)
- [x] T017 [P] `persistence/JdbcRegisterStore.recordedUnbatchedBefore(Instant)` and the matching
      `application/RegisterStore` port method - the projection carrying `age_seconds` from
      `register_time` in SQL, over `activeUnbatched()`'s **own** predicate extracted to one place and
      called by both reads, plus the `register_time < :recordedBefore` cut-off and
      `ORDER BY register_time`. `activeUnbatched()` itself is unchanged. Green: T011.
      (green at `5dbc9ac`: RegisterStoreReportReadsIT 5 tests, RegisterStoreIT 71 tests,
      RegisterBatchRepositoryIT 34 tests, 0 failures and 0 errors across all three.
      `checkstyleMain` and `pmdMain` exit 0. The predicate is now
      `ACTIVE_UNBATCHED_PREDICATE`, concatenated into both statements; the new read does not
      select the document, because nothing about a late register needs reading. Two column names
      the row views share are named once, which is what PMD's fourth occurrence of a literal
      asks for.)
- [x] T018 [P] `domain/LastScheduledRun` - the most recent occurrence of a **given** cron in a
      **given** zone strictly before a given instant, via Spring's `CronExpression`. One computation
      for two schedules: the generation cron for the never-batched `BATCH_LATE` rule, the report
      cron for the window. Green: T012.
      (green at `c08cb9c`: LastScheduledRunTest, 5 tests, 0 failures, 0 errors. `checkstyleMain`
      and `pmdMain` exit 0. `CronExpression` answers forwards only, so the most recent occurrence
      is found by walking one local day forwards at a time and stepping back until a day has one,
      with every candidate checked to still be on the day being searched - which is what keeps
      the walk from wandering into the next one across a clock change, where a local day is
      twenty-three or twenty-five hours long. The search is bounded at a year and a day so a cron
      that matches nothing is a refusal rather than an unbounded walk.)
- [x] T019 [P] The eight domain types of T013 filled in: `ExceptionKind` (**five** values, closed:
      `REQUEST_FAILED`, `REQUEST_LATE`, `BATCH_LATE`, `BATCH_FAILED`, `NOTIFICATION_FAILED`),
      `ReportWindow` (refusing a backwards window, plus the
      `sinceLastScheduledRun(cron, zone, now)` factory over `LastScheduledRun`), `ExceptionEntry`
      (the thirteen components of data-model.md's kind table **including `kind`**, every inapplicable
      field null), `ExceptionReport` (`runId` as the `String` the caller passed, `window`,
      `snapshotAt`, `entries` oldest first, plus `counts()` zero-filling all five kinds),
      `DeliveryOutcome`, `ReportSinkName`, `DeliveryStatus` and `ReportDeliveryReason` (the eight
      bounded values of data-model.md, `NONE` included, never raw exception text). Green: T013.
      (green at `11d7bab`: ExceptionReportModelTest, 5 tests, 0 failures, 0 errors.
      `checkstyleMain`, `checkstyleTest`, `pmdMain` and `pmdTest` exit 0.
      `sinceLastScheduledRun` takes **two** steps back through `LastScheduledRun`: the most recent
      occurrence before `now` is the run currently firing, so the window opens at the occurrence
      before that. One test line moved with the implementation - the window helper's backstop was
      a window at the epoch with no width, which the record now refuses before a case can read it;
      it runs forwards now and no assertion moved.)
- [x] T020 [P] Ports: `application/ExceptionReportSink` exactly as the plan's port contract writes it
      (`DeliveryOutcome deliver(ExceptionReport report)`, answering how it went rather than
      throwing); `application/PayloadFileStore` gains
      `storeText(UUID, String, PayloadMetadata) throws PayloadStoreUnavailableException` with the
      javadoc the plan gives; and `application/ReportMailer` (`MailOutcome send(ReportMail mail)`)
      with its three domain seams, `domain/ReportMail`, `domain/MailOutcome` and `domain/MailStatus`
      per data-model.md. `ReportMailer` is a **second** port rather than a widened `RegisterNotifier`
      because that port's argument is a batch's notification row and its body carries
      `personalisation.yotsName`: a report has neither, and bending the register's port around one
      would put an "unless it is a report" branch on the only path that e-mails Youth Offending Teams
      about real registers. The two existing `PayloadFileStore` implementers -
      `adapter/fileservice/FileServicePayloadStore` and the stub in `adapter/stub/` - gain a
      `storeText` that throws `UnsupportedOperationException`; the real one lands at T065. No Azure,
      JDBC, HTTP or logging type appears in any of the signatures. Infrastructure: a port declaration
      with its seams, recorded as a compiling `./gradlew compileJava`.
      (`a040f69`: `./gradlew compileJava compileTestJava` BUILD SUCCESSFUL, exit 0;
      `checkstyleMain` and `pmdMain` exit 0. Both `storeText` implementers refuse rather than
      pretend, because a store that accepted a CSV and wrote nothing would mint an id for a file
      notificationnotify would later find nothing under.)
- [x] T021 Phase close: `./gradlew build` green; **review gate 2** (ports and adapters, the
      read-only claim, no infrastructure type in `application/` or `domain/`); findings land as
      red/green pairs before Phase 3 starts.
      (build half done at `a040f69`: `./gradlew build` BUILD SUCCESSFUL, exit 0, 3337 tests over
      546 suites, 0 failures, 0 errors; Checkstyle at `maxWarnings = 0` over main and test, PMD
      over both, and the JaCoCo gate at LINE 0.88 / BRANCH 0.85, none of them loosened.
      **Review gate 2 ran against the committed Phase 2 content** with three read-only reviewers.
      Verdicts: `code-reviewer` **PASS** (2 medium, 5 low), `qa` **PASS**, `spec-validator`
      **DRIFT DETECTED** (1 medium, 1 low). Nothing in the gate asked for a
      `doc/DEFECT-FIXES.md` row and none was added; the ports-and-adapters and read-only claims the
      gate was called for were found clean - no Azure, JDBC, HTTP or logging type in `application/`
      or `domain/`, and every new statement a `SELECT`.
      Findings, and where each was closed:
      * **the window was one computation answering two questions.** `sinceLastScheduledRun` stepped
        back twice, which is right only for a caller that fired on its own occurrence: the 07:00
        run got the right period because it fires a few milliseconds late, and the bare CLI call
        FR-009 sends through the same factory reached a whole period too far back every time - at
        06:59 on a Tuesday it opened at the previous **Friday**. The design owner decided two
        factories: `forScheduledRun(cron, zone, firedAt)`, whose first step is
        `LastScheduledRun.atOrBefore` so an exact fire is not skipped past its own occurrence and
        whose `to` is `firedAt` so a late run widens its window rather than shrinking it, and
        `sinceLastScheduledRun(cron, zone, now)`, one step, for a caller with no occurrence of its
        own. No "near the occurrence" tolerance was added: it is a second boundary to get wrong.
        Red at `653be6c` (six window cases plus
        `LastScheduledRunTest.an_occurrence_at_the_instant_itself_is_answered_by_at_or_before_and_not_by_before`),
        green at `df050d5`. The FIRING_DELAY_MILLIS two-step reasoning moved into
        `forScheduledRun`'s javadoc, where it is now true.
      * the four `RegisterBatchRepository` report reads were the only statements in the class not
        wrapped in `StoreOutage.translating`, so an unreachable store reached the report as
        `CannotGetJdbcConnectionException` rather than as this service's own signal. Red
        `every_read_goes_through_store_outage_translating` at `bb836f2`, green at `0e58e9b`.
      * `RECORDED_UNBATCHED_BEFORE` ordered on `register_time` alone while `activeUnbatched()` -
        the read it shares a predicate with - has always ordered on `register_time, output_id`.
        Red `registers_recorded_at_the_same_moment_are_answered_in_output_id_order` at `bb836f2`
        (six registers on one instant, answered in scan order), green at `0e58e9b`.
      * the null-stage-timestamp question the gate raised was **answered by reading the write
        paths, and the reads were left alone**. A FAILED batch cannot carry a null `failed_at`:
        `JdbcRegisterStore.MARK_FAILED` writes `failed_at = now()` in the same `UPDATE` as the
        status and is the only production path to FAILED, `RegisterBatchRepository.compareAndSet`'s
        whole-row write having no production caller. A FAILED notification cannot carry a null
        `sent_at`: `RegisterNotifierService.settledAs` stamps `clock.instant()` on every terminal
        attempt, a refusal and an unanswered connection alike. So **no `COALESCE`** - a fallback
        would be a second answer to a question the write path only ever answers one way - and the
        invariant is pinned where it is produced, by
        `RegisterStoreIT.a_failed_batch_always_carries_its_failed_at` and
        `RegisterNotifierServiceTest.a_failed_notification_always_carries_its_sent_at`, both
        **green on introduction** at `bb836f2`. data-model.md now says the reads rely on it.
      * the window predicates' inclusive start had no case.
        `failed_since_includes_a_row_failed_exactly_at_the_window_start` landed in the request and
        notification suites at `bb836f2`, both **green on introduction**, each seeding at the row's
        own stored instant rather than near it.
      * five model gaps, all closed at `653be6c`/`df050d5`: `ExceptionEntry` admitted a null
        `kind`, `ExceptionReport` read a null entries list as an empty morning (which is the report
        a quiet night produces), `ReportWindow`'s refusal of a zero-width window was unasserted,
        `ReportMail`'s compact constructor was unpinned, and
        `counts_answers_zero_for_every_kind_that_has_none` closed on `containsValue(0)`, which a
        map holding one nought among four absences satisfies. The two refusals are red; the other
        three were **green on introduction**.
      * the four `age_seconds_is_computed_by_the_database_not_the_jvm` cases were vacuous: they
        advanced an `AdjustableClock` no repository holds and then asserted the database's answer
        had not moved, which it could not have. All four are now
        `age_seconds_is_answered_in_seconds_from_the_stage_timestamp` - the value against the
        seeded age, and the read's own recorded statement against `now()` and `extract(epoch` -
        and the `AdjustableClock` pretence is gone from the four suites. `bb836f2`, green on
        introduction, and the plan's matrix carries the new names.
      * `ReportReadsDatabase` created a database per suite and never dropped it, so a second
        `migrated(...)` under one name would fail in a `@BeforeAll` where no case could report it.
        A `drop()` over the new `PostgresTestSupport.dropDatabase` runs in each suite's
        `@AfterAll`; the outage mechanism is untouched. `0e58e9b`.
      * `StubPayloadFileStore.storeText` refused with no explanation of what it was. Its javadoc
        now says it is a seam - a stub that accepted a CSV and wrote nothing would mint an id
        notificationnotify would find no file under - and that it **must become a logging no-op**,
        in the shape `store` beside it is written in, when the e-mail sink lands at T065.
        `0e58e9b`.
      Phase-close build **after** the findings landed, at `0e58e9b`: `./gradlew build` BUILD
      SUCCESSFUL, exit 0, 3359 tests over 548 suites, 0 failures, 0 errors; Checkstyle at
      `maxWarnings = 0` over main and test, PMD over both, and the JaCoCo gate at LINE 0.88 /
      BRANCH 0.85, none of them loosened. Phase 3 may start.)

**Checkpoint**: the report's model, its two ports and its nine reads exist and the reads are proven
against a real Postgres. The three story phases below can now be worked independently.

---

## Phase 3: User Story 2 - the oldest unfinished request is measurable and alertable at any time (Priority: P1)

**Goal**: the two intake gauges design section 11 promised and 001 never built, refreshed on their
own schedule in **every** service JVM and under **no** lock, plus the request-duration timer, so an
alert fires within one refresh interval rather than the next morning.

**Independent Test**: insert an unfinished request older than the threshold; wait one refresh
interval; the oldest-age gauge reports at least its age in seconds and the over-threshold count
reports one; complete the request; wait one interval; both return to zero.

**Independent of US1 and US5's report half**: nothing in this phase reads `ExceptionReport`, the
sinks or the job. It needs Phase 2's repository reads and nothing else, and the sweep it builds runs
on a pod with the report and the generation half both switched off - which is the point of it.

### Tests first ⚠️

- [x] T022 [P] [US2] `config/ProcessingMetricsTest` (extend) -
      `the_two_intake_gauges_exist_from_construction_and_read_zero` (registered in the constructor,
      not on first use: a gauge that appears after the first incident is not an alerting surface, and
      it is the argument the class already makes for `yotresultsdistribution_intake_suspended`),
      `the_request_duration_timer_is_tagged_only_by_its_terminal_outcome`,
      `the_timing_token_is_opaque_and_carries_no_micrometer_type_into_the_caller` (the start method
      answers a `ProcessingMetrics.Timing` and the stop method takes one back, so the application
      layer holds a token and imports no `Timer.Sample` - Principle V, the same containment that
      keeps `StructuredArguments` inside one adapter),
      `the_four_counters_carry_only_bounded_labels` over
      `yotresultsdistribution_exception_report_runs_total{outcome}` (over `delivered`, `partial` and
      `failed`, the same three words the run line carries),
      `yotresultsdistribution_exception_report_deliveries_total{sink,outcome}`,
      `yotresultsdistribution_exceptions_reported_total{kind}` across **all five** kinds, and
      `yotresultsdistribution_intake_sweep_failures_total{reason}` - the counter that makes the sweep's one
      absorbed refusal visible, because a path that drops something must move a counter - and
      `no_identifier_is_ever_a_label` (no request id, hearing id, court centre id or address).
      Seams: `config/ProcessingMetrics` gains the six instrument-name constants, the nested
      `Timing` type and no-op recording methods. Red:
      `SimpleMeterRegistry.find(OLDEST_NON_TERMINAL_REQUEST_AGE).gauge()` is null on a freshly
      constructed `ProcessingMetrics`.
      (red at `7a486fd`: `ProcessingMetricsTest`, ten cases failing, every one an assertion.
      `the_two_intake_gauges_exist_from_construction_and_read_zero` on "expected: 0.0 but was:
      -1.0" - the suite's absent-meter sentinel, so a gauge that was never registered fails as an
      assertion rather than as a null. Four of the ten are the existing `Surface` cases widened:
      the gauge list is four rather than two
      (`the_two_gauges_should_be_registered_before_anything_happens` renamed to say so), the
      exercised-instrument list gains the seven new ones, and the label sets gain `sink` and
      `kind`.
      `the_timing_token_is_opaque_and_carries_no_micrometer_type_into_the_caller` was **green on
      introduction** and the commit records it as such: it characterises the seam's own shape -
      the start method answers this class's own token and the stop method takes it back - and had
      no earlier shape to fail against. It claims no TDD exception.)
- [x] T023 [P] [US2] `application/DistributionPipelineTest` (extend) -
      `a_completed_run_records_one_duration_sample_tagged_completed`,
      `a_parked_run_records_one_duration_sample_tagged_failed`,
      `a_write_the_guard_refused_records_no_sample` (a superseded runner's completion affects no rows
      and must not contribute a sample, which is exactly what `settled(...)` and `parked(...)`
      already refuse to count), `a_transient_failure_short_of_the_budget_records_no_sample`
      (the run has not reached a terminal state), and
      `the_pipeline_holds_a_timing_token_and_imports_no_micrometer_type`, asserted over the class's
      declared fields and its import list. Red: no sample is recorded at all, so the
      `assertThat(timer.count()).isOne()` fails on zero.
      (red at `2e371f3`: five cases over `yotresultsdistribution_request_duration`, two of them failing,
      both assertions. `a_completed_run_records_one_duration_sample_tagged_completed` on
      "expected: 1.0 but was: -1.0", and the same against `outcome=failed` for the parked run, the
      pipeline being untouched at that commit. The three negative cases pass on introduction,
      which is what a negative case does before the behaviour it bounds exists: they are what
      stops T026 recording a sample from a superseded runner's write, or from an attempt the queue
      is going to deliver again.)
- [x] T024 [P] [US2] `batch/IntakeAgeSweepTest` -
      `both_gauges_move_from_the_repositorys_answers`,
      `both_gauges_return_to_zero_when_nothing_is_unfinished`,
      `a_failed_read_leaves_the_gauges_at_their_last_reading_counted_and_does_not_cancel_the_schedule` -
      the one **absorbed** refusal in this increment, and it is absorbed because
      `.claude/rules/design_rules.md` says so: *"The one absorbed refusal is telemetry: a round-trip
      reading that cannot be taken ... stops where it happens, is counted, and is said at WARN."* The
      case asserts all three halves: the gauges keep their last value rather than dropping to a lie,
      `yotresultsdistribution_intake_sweep_failures_total{reason}` moves, and one WARN line is written naming
      the caught failure **by class** and never by message - plus that the fixed delay is not
      cancelled. `the_sweep_opens_its_own_run_id_and_removes_it`
      (`RunCorrelation`, because the scheduler's threads are pooled and an id left behind reads as a
      true correlation), and `the_sweep_is_scheduled_and_deliberately_unlocked` - the reflection
      case, written the way `GenerationReconcilerTest.ItsOwnSchedule` writes its own over
      `reconcileScheduled`: read `IntakeAgeSweep.class.getDeclaredMethod("sweepScheduled")`, assert
      the method is `void`, assert `@Scheduled.fixedDelayString` is
      `${yotresultsdistribution.intake.gauge-refresh}`, and assert the method carries **no** `@SchedulerLock`
      at all. A gauge describes the JVM that publishes it: lock the sweep and one replica reads while
      the others publish whatever they last saw, so a two-pod deployment shows one pod's view under
      two pod labels and the alert is a coin toss. Unlocked, every replica refreshes its own two
      gauges and an alert aggregates them across pods with `max()`. (The `scheduler` attribute is
      T042's case, not this one - it needs `IntakeSweepConfig`, which Phase 5 declares.)
      Seams: `batch/IntakeAgeSweep` with a constructor taking the repository, the metrics and the
      resolved threshold, `sweepScheduled()` `void` and a body method that throws. Red: the gauge is
      still zero after the sweep ran.
      (red at `b2fdfc2`: `IntakeAgeSweepTest`, five cases, four failing, every one an assertion.
      `both_gauges_move_from_the_repositorys_answers` on "expected: 900.0 but was: 0.0" - the seam
      publishes nothing, so the age the database computed never reaches the gauge.
      `both_gauges_return_to_zero_when_nothing_is_unfinished` passes on introduction, as a case
      about a reading coming back down must while no reading goes up; T027 is what gives it
      something to assert. The unlocked-schedule case is the reflection one, written the way
      `GenerationReconcilerTest.ItsOwnSchedule` writes its own: `void sweepScheduled()`,
      `fixedDelayString` `${yotresultsdistribution.intake.gauge-refresh}`, and no `@SchedulerLock` at all.)

### Implementation

- [x] T025 [US2] `config/ProcessingMetrics` (extend) - gauge
      `yotresultsdistribution_oldest_non_terminal_request_age` (seconds) and gauge
      `yotresultsdistribution_non_terminal_requests_over_threshold`, both **registered at zero in the
      constructor** behind an `AtomicLong`/`AtomicInteger` with `Gauge.builder(...).register(registry)`;
      timer `yotresultsdistribution_request_duration` tagged `outcome` from the terminal statuses and by
      nothing else, started and stopped through two methods over an opaque nested
      `ProcessingMetrics.Timing` token so that no Micrometer type crosses into `application/`;
      counters `yotresultsdistribution_exception_report_runs_total{outcome}`,
      `yotresultsdistribution_exception_report_deliveries_total{sink,outcome}`,
      `yotresultsdistribution_exceptions_reported_total{kind}` and
      `yotresultsdistribution_intake_sweep_failures_total{reason}`. Naming and style follow
      `config/GenerationMetrics`. Queue depth and dead-letter depth stay absent, as the class's
      javadoc already records. Green: T022.
      (green at `5301f6c`: `./gradlew test --tests ProcessingMetricsTest` - 49 tests, 0 failures,
      0 errors; `checkstyleMain`, `checkstyleTest`, `pmdMain` and `pmdTest` exit 0. Both gauges are
      registered at zero in the constructor behind an `AtomicLong` and an `AtomicInteger`, because
      a gauge that appears only after the first incident is not an alerting surface and a dashboard
      has to read them from a pod that has swept nothing yet. Seven instrument-name constants land
      rather than the six the task's seam note counts - two gauges, one timer and four counters -
      and `code(Enum)` is shared with `GenerationMetrics`, whose naming and style the class
      follows. Queue depth and dead-letter depth stay absent, as the javadoc records.)
- [x] T026 [US2] `application/DistributionPipeline` - a `ProcessingMetrics.Timing` token taken where
      the guard admits the run (the `admission instanceof GuardDecision.Run admitted` branch) and
      handed back with the terminal outcome in **`settled(GuardDecision, RunClaim, CompletionReason)`**
      under `outcome instanceof GuardDecision.Complete` and in **`parked(GuardDecision)`** under
      `outcome instanceof GuardDecision.DeadLetter` - the two places the class already refuses to
      count a write the guard rejected, which is precisely the behaviour the timer needs. The sample
      behind the token is monotonic and in-process; no JVM reading is compared against a stored
      timestamp, and the wall-clock answer is carried by the report's own database-computed
      `age_seconds`. The pipeline holds the token and imports no Micrometer type. Green: T023.
      (green at `daa8b59`: `./gradlew test --tests 'uk.gov.hmcts.cp.yotresultsdistribution.application.*'
      --tests 'uk.gov.hmcts.cp.yotresultsdistribution.inbound.*'` - 291 tests, 0 failures, 0 errors;
      `checkstyleMain`, `checkstyleTest`, `pmdMain` and `pmdTest` exit 0. The token is threaded as
      an ordinary parameter rather than held on the class, so `settled(...)` and `parked(...)` each
      gain one parameter over the signature the task names - the only deviation, and the
      alternative was thread-local state that reads as a correlation and is not one: the pipeline
      is a singleton and two deliveries run through it at once, so a field would time whichever run
      finished last. The sample is monotonic and in-process, no JVM reading is subtracted from a
      stored timestamp, and the class imports no Micrometer type.)
- [x] T027 [US2] `batch/IntakeAgeSweep` - `@Scheduled(fixedDelayString =
      "${yotresultsdistribution.intake.gauge-refresh}")` and **no** `@SchedulerLock` on a `void`
      `sweepScheduled()` that opens `RunCorrelation.under(...)` and delegates to a directly callable
      body; the body reads `oldestNonTerminal()` and the over-threshold count and sets the two
      gauges. A read it cannot take stops where it happens, moves
      `yotresultsdistribution_intake_sweep_failures_total{reason}`, is said once at WARN naming the caught
      failure by class, and leaves the gauges at their last reading - the design rules' one absorbed
      refusal, and the only one in this increment. It holds a clock and collaborators and no lock,
      driver, broker or HTTP client. Its read is bounded by the V4 partial index rather than by a
      deadline - a `LIMIT 1` and a count over the handful of rows still in flight - so there is no
      lock budget to state and none is stated. **Its bean is declared in Phase 5's
      `IntakeSweepConfig`, which is also where its `scheduler` attribute comes from (T048)**; nothing
      schedules it yet. Green: T024.
      (green at `ffd22f3`: `./gradlew test --tests 'uk.gov.hmcts.cp.yotresultsdistribution.batch.*'` green,
      all five `IntakeAgeSweepTest` cases passing in the phase-close run too; `checkstyleMain`,
      `checkstyleTest`, `pmdMain` and `pmdTest` exit 0. Both readings come from the one pass, so
      the age and the count describe one moment, and the age is the one the database computed in
      the statement that selected the row. The second catch is total on purpose and the PMD rule
      against it is suppressed with its reason: a fixed-delay schedule cancels the task that
      throws, so a failure this method let out would take both readings off the air for the life of
      the pod. Its bean is Phase 5's `IntakeSweepConfig`, along with the `scheduler` attribute
      (T048); nothing schedules it yet.)
- [x] T028 Phase close: `./gradlew build` green; **review gate 3** (the instruments' bounded labels,
      no PII, the Micrometer containment behind the `Timing` token, the sweep's
      absorbed-and-counted read failure as the one permitted absorbed refusal, quoted against
      `.claude/rules/design_rules.md`); findings land as red/green pairs.
      (build half done at `ffd22f3`: `./gradlew build` BUILD SUCCESSFUL, exit 0, 3375 tests over
      551 suites, 0 failures, 0 errors. `checkstyleMain` and `checkstyleTest` at `maxWarnings = 0`,
      `pmdMain` and `pmdTest`, and `jacocoTestCoverageVerification` at LINE 0.88 / BRANCH 0.85 all
      ran in that one invocation and none of them was loosened: nothing under `gradle/`, `config/`,
      `.github/` or `build.gradle` moved anywhere in Phase 3.
      **Review gate 3 ran against the committed Phase 3 content** with three read-only reviewers.
      Verdicts: `code-reviewer` **PASS** (2 medium, 3 low), `spec-validator` **COMPLIANT** (2 low),
      `qa` **FAIL** - narrow, and on the fixtures rather than on a failing suite, QA's own read of
      the tree at `7bca310` being 3375 tests, 0 failures, 0 errors. The three things the gate was
      called for were found clean: no PII on any label or at INFO, the Micrometer containment
      behind the `Timing` token holds (no `io.micrometer` import or field in `application/`), and
      the sweep's absorbed read failure is the only absorbed refusal in the increment - counted,
      said once at WARN by class, and not cancelling the schedule.
      Findings, and where each was closed:
      * **the two newest labels were bounded by review rather than by the compiler.**
        `exceptionReportRun(String)` and `intakeSweepFailure(String)` took the word itself, so the
        only thing keeping `yotresultsdistribution_exception_report_runs_total` to three series was that
        three callers spelled three words correctly. A mistyped label is not a wrong reading - it
        is a new series, on which the alert written against the right one is silent for ever.
        `domain/ReportRunOutcome` (`DELIVERED`, `PARTIAL`, `FAILED`) and `domain/SweepFailureReason`
        (`STORE_UNAVAILABLE`, `UNEXPECTED`) land as the seam and the String overloads are **removed**
        rather than deprecated; both render through the existing `code(Enum)` helper, so the
        published labels do not move. Red `the_run_outcome_label_is_one_of_three_bounded_words` and
        `the_sweep_failure_reason_label_is_one_of_two_bounded_codes` at `8820b64`, green at
        `37fe136`.
      * **the request-duration timer recorded whatever status it was handed.**
        `requestSettled(Timing, RequestStatus.RETRYING)` would publish a sample under a fifth
        `outcome` value nothing documents and no alert reads, turning the timer into a histogram of
        attempts - and a timer records in silence, so the caller would never find out.
        `RequestStatus.isTerminal()` is what the refusal asks, pinned in `domain/RequestStatusTest`
        so a fifth constant has to decide which side of the line it falls on. Red
        `a_non_terminal_status_is_refused_by_the_timer` at `8820b64` ("Expecting code to raise a
        throwable"), green at `37fe136`.
      * **the over-threshold gauge sized a list to get one number.** The sweep called
        `nonTerminalOlderThan(...)` and took `.size()`, so its cost grew with the backlog it was
        reporting - slowest on the morning the reading matters most - and every unfinished
        request's row was carried into the JVM to be counted and dropped, which on this register is
        a youth's case. `countNonTerminalOlderThan(Instant)` answers the number, spelled with the
        list read's predicate character for character so `idx_request_non_terminal_created` still
        matches, and wrapped in `StoreOutage.translating` like every other statement in the class.
        Red `ProcessedRequestReportReadsIT.count_non_terminal_older_than_answers_the_number_without_the_rows`
        and `IntakeAgeSweepTest.the_over_threshold_gauge_is_set_from_the_count_read_not_from_a_materialised_list`
        at `8820b64`, green at `37fe136`.
      * **the sweep's fixture contradicted the store it was standing in for** (QA). It answered a
        900-second row as the oldest while a 2400-second row sat in the same store, then counted
        the 900-second one as over a 1800-second threshold - so it could have passed against a
        sweep that had the two readings the wrong way round. Rewritten to **spec scenario 2.2 as
        written**: one unfinished request forty minutes old, a thirty-minute threshold, the age
        gauge at least 2400 and the count 1. `8820b64` / `37fe136`.
      * **the threshold boundary had no case at either level** (QA). The predicate is
        `created_at < :cutOff`, exclusive, so a request created exactly the threshold ago is not
        over it. Pinned in the store by
        `ProcessedRequestReportReadsIT.a_request_exactly_at_the_threshold_is_not_over_it`, asserted
        against the row's **own stored instant read back** rather than one derived from this
        suite's clock - a derived cut-off is near the boundary, and near is what the case exists to
        rule out - and in the sweep by
        `the_cut_off_is_the_threshold_ago_exactly_and_is_not_nudged_either_way`, which captures the
        instant the sweep passes. The report's REQUEST_LATE read shares that boundary, so a
        tolerance added to soften it would make the gauge and the morning report disagree about the
        same request. `8820b64` / `37fe136`.
      * **only the cause's text was asserted absent from the WARN line** (QA); the caught
        exception's own message was not, and the wrapper's message is the one that names the
        statement. Both absorbed-refusal cases now assert both, through a shared
        `assertTheOneWarningNames(...)`. `8820b64`.
      * `a_read_that_fails_for_any_other_reason_is_counted_unexpected` characterises the total
        second catch: an outage of theirs and a bug of ours are counted apart, because one counter
        for both would make the second invisible inside the first. **The branch was already there**
        - the red run's own log shows `reason=unexpected type=java.lang.IllegalStateException`
        written before any production change - so only the over-threshold assertion in that case
        was red, and the commit body says so.
      * `a_parked_run_that_was_not_dead_lettered_records_no_sample` (QA's sixth item, which asked
        for it only if `parked(...)` is reachable with a non-DeadLetter outcome in a unit test: it
        is, by stubbing `guard.recordExhaustion` to hand back an `Abandon`). The completion side of
        the guard's refusal had a case and the parking side did not, although they are separate
        `instanceof` branches and one could be widened without the other. **Green on introduction**,
        at `8820b64`.
      * LOW, folded into the green commit with no red of its own: the pipeline's import-list case
        read `DistributionPipeline.java` through a bare relative path, so it asserted about
        whatever file that resolved to. It resolves from `System.getProperty("user.dir")` now.
        `37fe136`.
      Record-keeping the Phase 3 tick got wrong, corrected here rather than by editing T022-T024:
      * T023's tick folds `the_pipeline_holds_a_timing_token_and_imports_no_micrometer_type` into
        "the three negative cases" without naming it. It is not a negative case - it characterises
        the seam's own shape and was **green on introduction**, as
        `the_timing_token_is_opaque_and_carries_no_micrometer_type_into_the_caller` is and says.
      * T022's tick omits `the_exceptions_counter_moves_by_the_number_reported`, which landed with
        the rest at `7a486fd`.
      * T022's tick says four `Surface` cases were widened; **five** were - the Prometheus
        gauge-scrape case `the_gauges_should_scrape_before_any_message_has_arrived` ("all four gauges scrape from a pod that has seen nothing") too.
      Nothing in the gate asked for a `doc/DEFECT-FIXES.md` row and none was added: every finding
      is about this increment's own instruments, not about a legacy behaviour.
      **Cross-branch note.** `a00cdcf` on the Phase 4 branch stands in `exceptionReportRun(String)`
      so the report's service can compile while Phase 3 is in flight, on the stated understanding
      that "the two branches declare the same members with the same signatures, so the merge
      resolves to Phase 3's version". That is now **false for one member**: this branch's signature
      is `exceptionReportRun(ReportRunOutcome)`. The merge must take this branch's
      `ProcessingMetrics` whole and move the report's call sites onto the enum - three words, the
      same three - rather than resolving member by member.
      Phase-close build **after** the findings landed, at `37fe136`: `./gradlew build` BUILD
      SUCCESSFUL, exit 0, 3387 tests over 552 suites, 0 failures, 0 errors; `checkstyleMain` and
      `checkstyleTest` at `maxWarnings = 0`, `pmdMain` and `pmdTest`, and
      `jacocoTestCoverageVerification` at LINE 0.88 / BRANCH 0.85 all ran in that one invocation
      and none of them was loosened - nothing under `gradle/`, `config/`, `.github/` or
      `build.gradle` moved anywhere in Phase 3 or in this gate. Phase 4 may start.)

**Checkpoint**: US2 is independently demonstrable through the actuator's Prometheus endpoint, on a
pod with the report and the generation half both switched off, once Phase 5 declares the bean in
`IntakeSweepConfig` - which is conditional on neither flag, precisely so that pod is covered.

---

## Phase 4: User Story 1 - support sees every failure and every late item in Log Analytics (Priority: P1) 🎯 MVP

**Goal**: the report itself - a typed `ExceptionReport` built from the repositories over a window,
delivered to the sinks its caller chose, with the log sink writing two structured events whose
fields Log Analytics reads without `parse()`.

**Independent Test**: seed a failed request, an unfinished request older than the threshold, a batch
past its rendering deadline, a batch that reached FAILED and a failed notification; build the report;
confirm one summary with the five counts and five exception events, each carrying the expected
identifiers and nothing else.

### Tests first ⚠️

- [x] T029 [P] [US1] `application/ExceptionReportServiceTest` -
      `a_failed_request_inside_the_window_is_one_request_failed_entry`,
      `an_unfinished_request_past_the_threshold_is_one_request_late_entry_whether_or_not_it_arrived_inside_the_window`
      (the late kinds are deliberately **not** bounded by the window: a request stuck for three days
      is late this morning, and a window filter would make the longest-running problem the first to
      disappear), `the_three_batch_sources_are_one_batch_late_kind` (over `latePending`,
      `lateGenerating` and `lateGenerated`),
      `a_failed_notification_inside_the_window_names_its_batch_and_its_notification_and_no_address`
      (there is no address to omit: no read selects one),
      `a_batch_failed_inside_the_window_is_one_batch_failed_entry_carrying_its_bounded_reason`,
      `a_batch_failed_entry_never_carries_the_generators_own_words` (`sdg_reason` is another system's
      free text, is in no select list and is not a component of `BatchException` - Principle VII),
      `every_age_on_every_entry_is_the_one_the_statement_computed` (the service derives no age from a
      timestamp; each projection carries its own),
      `a_request_is_reported_under_at_most_one_kind_per_run` (FR-013: the two intake predicates
      partition on status, so a request that was late and has since failed appears once),
      `each_late_batch_stage_is_asked_about_its_own_limit` (omitted from this list when it was
      written, landed with the rest at `3261883`: the two rendering stages are held to the
      rendering limit and a rendered batch to the notification limit, captured off the three reads
      - one duration passed to all three would make the three stages one question),
      `an_empty_window_yields_five_zero_counts_and_no_entries` (FR-012),
      `the_same_still_late_request_appears_in_two_consecutive_windows` (scenario 1.6: a report is a
      snapshot of a moment, not a ledger of new arrivals),
      `a_register_recorded_while_the_flag_was_off_is_never_late` (the shared predicate excludes it by
      construction; those rows are the existing `list-batches --recorded-while-off` command's
      concern), `unbatched_registers_are_late_only_before_the_most_recent_scheduled_generation_run`,
      `entries_are_ordered_oldest_first_across_all_five_kinds`,
      `the_run_id_on_the_report_is_the_one_the_caller_passed` (`build(window, runId)` takes it; the
      service never reads the MDC, which is what keeps `application/` free of it and makes FR-011
      true on the command's path as well as the job's), and
      `the_report_writes_nothing_back` (FR-014, `verifyNoMoreInteractions` over every write method of
      every repository the service holds).
      Seams: `application/ExceptionReportService` with its constructor and
      `ExceptionReport build(ReportWindow window, String runId)` throwing. Red: the returned report
      has no entries where one `REQUEST_FAILED` was seeded.
      (red at `3261883`: 16 tests, 12 failures, 0 errors, every one an assertion.
      `a_failed_request_inside_the_window_is_one_request_failed_entry` on "[a request the pipeline
      parked is the intake half's exception, and the report exists to name it on the morning
      after] Expected size: 1 but was: 0 in: []" - the predicted red exactly. The seam answers an
      empty report rather than throwing, so all twelve fail on what the fold produced. One
      correction landed later, at `e4c53b6`: the late-request fixture was seeded at 9 000 seconds
      against a twenty-four hour window, and its own closing assertion is that the row is older
      than the window is wide - the row a window-bounded read would have dropped - so it could not
      hold whatever the service did. Three days now, which is the case's own narrative.)
- [x] T030 [P] [US1] `application/ExceptionReportDeliveryTest` - over
      `List<DeliveryOutcome> deliver(ExceptionReport report, Collection<ExceptionReportSink> sinks)`,
      which takes the sinks from its **caller** rather than from the context, because the 07:00 run
      delivers to every sink and the command delivers to the log sink always and the e-mail sink
      only under `--email`:
      `the_sinks_delivered_to_are_the_ones_the_caller_passed`,
      `a_sink_that_fails_is_recorded_with_a_bounded_reason_and_the_other_sink_still_runs`,
      `one_of_two_sinks_delivered_is_partially_delivered`,
      `delivering_to_the_log_sink_alone_answers_one_outcome`,
      `both_sinks_failing_is_not_delivered_and_still_raises_nothing_out_of_the_service`,
      `every_delivery_outcome_is_counted_on_the_deliveries_counter`,
      `a_failed_sink_is_never_retried` (a report is regenerated in full by the next run or on demand,
      so a retry would re-send a list support is about to receive again anyway - which is why a sink
      failure maps to a recorded state rather than to a redelivery), and
      `a_caught_failure_is_named_by_class_and_never_by_message` (a message belongs to whatever
      library raised it and is exactly where a connection string turns up). Red: the second sink is
      never asked once the first throws.
      (red at `4629755`: 8 tests, 8 failures, 0 errors, every one an assertion.
      `the_sinks_delivered_to_are_the_ones_the_caller_passed` on "[the caller chooses: the 07:00
      run delivers to every sink on the context, and the command adds the e-mail sink only under
      --email] Expecting actual: [] to contain exactly (and in same order): [LOG, EMAIL]". One
      deviation, additive: the port gains `ReportSinkName name()` in the same commit, because the
      contract is that a sink answers rather than throws, so the one delivery nobody planned for
      is the one whose outcome has to be attributed by whoever asked.)
- [x] T031 [P] [US1] `adapter/report/LogEventReportSinkTest` (using `support/CapturedLog`) -
      `one_summary_event_carries_its_ten_fields` (`event`, `run_id`, `window_from`, `window_to`,
      `snapshot_at` and the **five** counts - `request_failed`, `request_late`, `batch_late`,
      `batch_failed`, `notification_failed`; ten is the number data-model.md states and this is the
      assertion that holds it),
      `the_summary_event_carries_no_delivery_status_of_any_kind` - a sink can observe neither its own
      arrival in an index nor the other sink's, so a `delivered_email` field written here would be a
      claim about an outcome nobody had observed; how the report travelled is the run's own fact and
      belongs on the job's `exception_report_run` line (T041),
      `one_exception_event_is_written_per_entry`,
      `a_batch_failed_event_carries_its_bounded_reason_and_no_generator_text`,
      `a_field_that_does_not_apply_to_the_kind_is_absent_rather_than_null` (so a KQL `isnotempty()`
      means what it says), `run_id_is_on_both_events`,
      `every_value_is_an_identifier_a_bounded_code_or_a_number`, and
      `no_identifier_appears_in_the_message_text` - the structured arguments are asserted as
      arguments, and the non-field text of every line is asserted to carry none of them, because a
      value rendered into the message is a value a saved query has to `parse()` back out.
      Seam: `adapter/report/LogEventReportSink` implementing `ExceptionReportSink` with `deliver`
      throwing. Red: no event is captured at all.
      (red at `6414ca3`: 9 tests, 8 failures, 0 errors, every one an assertion.
      `one_exception_event_is_written_per_entry` on "[one event per exception, so a saved query
      counts rows rather than parsing a list out of one line] Expected size: 3 but was: 0 in: []".
      The seam names itself and answers delivered without writing anything, so the ninth case -
      the one about the name and the outcome - is green on introduction and says so. The summary's
      ten fields are held to being exactly ten with `containsOnlyKeys`, so an addition fails as
      loudly as a removal.)
- [x] T032 [P] [US1] `config/TelemetryPrivacyTest.ShippedConfiguration` (extend) -
      `both_logback_files_declare_the_arguments_provider`, asserted over
      `src/main/resources/logback.xml` **and** `src/main/resources/logback-cli.xml`, beside the
      `<mdc/>` claim the nested class already makes: a command's lines reach the same index as a
      pod's, and without the provider every query needs `parse()`. Red: `<arguments/>` is absent from
      both files.
      (red at `602c148`: 5 tests in `ShippedConfiguration`, 2 failures, 0 errors, both assertions,
      one per parameterisation. `[2] configuration = "logback-cli.xml"` on "[without the arguments
      provider every field of both report events is rendered into the message and every query
      needs parse()] Expecting actual: "<configuration> ... to contain: "<arguments/>"".)
- [x] T033 [P] [US1] `support/GenerationLegs` (extend) - `THE_LEGS` gains `ExceptionReportService`
      and `LogEventReportSink`, and `driveEverything()` drives both so **every** LOG statement in
      them is reached, with `support/PersonalDataMarkers` wherever a person could be named. The
      remaining five classes (`ExceptionReportJob`, `IntakeAgeSweep`, `EmailReportSink`,
      `NotificationNotifyReportMailer`, `ReportExceptionsCli`) are added by their own phases'
      implementation tasks and the whole set of seven is swept at T071. Red: the coverage assertion
      names the LOG statements in `ExceptionReportService` and `LogEventReportSink` that no leg
      reaches.
      (red at `1b40f57`: 36 tests over `TelemetryPrivacyTest`, 3 failures, 0 errors, every one an
      assertion; two of the three are T032's, still red until T036.
      **The predicted red could not be shown as written, and the reason is recorded in the commit
      body.** It presupposes the statements exist, and both classes were still seams that write
      nothing: `LogStatement.everyOneIn` reads declarations out of the sources, so adding a class
      that declares none widens the list without widening the claim, and the sweep goes on
      reporting green while covering nothing of theirs. The case therefore gains the precondition
      this suite already writes twice over in its own idiom - a scan that found nothing would make
      the assertion vacuous - narrowed to the two classes just added:
      `[A] and the drive above reached every line the two legs can write` on "[the report's two
      classes are inside the enumeration now, and a class that declares no statement contributes
      nothing for the reach assertion below to cover] Expecting actual not to be empty". It fails
      at nought and has teeth the moment the lines land, which is also what makes the reach
      assertion below it mean anything for them.
      Two deviations, both additive. The precondition is an assertion inside
      `config/TelemetryPrivacyTest`, the suite that owns the sweep, so the commit touches that
      file as well as `support/GenerationLegs`; `THE_REPORT` is published beside `THE_LEGS` and
      `THE_LEGS` is composed from it, so the two cannot drift. And the report's
      `ProcessingMetrics` is registered on a registry of its own rather than the leg's, because
      the label vocabulary the leg sweep holds every series to is generation's - the report's
      three counters are Phase 3's to declare and T022's to sweep.
      The marker the drive carries is on the sink that breaks, whose failure names a team and an
      address: the rule is that a caught failure is named by class and never by message, and a
      failure that said nothing about anybody would leave that rule asserted against a string that
      could not have leaked.)

### Implementation

- [x] T034 [US1] `application/ExceptionReportService` - **two methods**.
      `ExceptionReport build(ReportWindow window, String runId)` makes the **eight** reads of
      data-model.md's predicate table - `ProcessedRequestRepository.failedSince` and
      `.nonTerminalOlderThan`; `RegisterBatchRepository.latePending`, `.lateGenerating`,
      `.lateGenerated` and `.failedSince`; `RegisterStore.recordedUnbatchedBefore` given
      `LastScheduledRun.before(the generation cron, its zone, now)`; and
      `RegisterNotificationRepository.failedSince` - and folds them into one `ExceptionReport`
      ordered oldest first, stamped with the run id it was **given**. Every entry's `ageSeconds` is
      the one its projection carried out of the statement; the service computes no age.
      `List<DeliveryOutcome> deliver(ExceptionReport report, Collection<ExceptionReportSink> sinks)`
      hands the report to the sinks the caller chose. A sink is caught only to classify: each catch
      produces a `DeliveryOutcome` with a bounded `ReportDeliveryReason`, counted and said at WARN,
      and the caller folds the outcomes rather than short-circuiting on the first; nothing is
      retried. It depends on the repositories and on nothing else, imports no logging library, no
      Micrometer type beyond the metrics facade and no MDC, and reads the `YotResultsDistributionService` flag
      nowhere. Green: T029, T030, and the `ExceptionReportService` half of T033.
      (green at `942e3c0`: `./gradlew test --tests '*ExceptionReportServiceTest*' --tests
      '*ExceptionReportDeliveryTest*' --tests '*TelemetryPrivacyTest*' -Dtest.noFailFast=true`,
      60 tests, 2 failures, 0 errors - both of them T032's two logback cases, which stay red until
      T036. T029, T030 and the `ExceptionReportService` half of T033 are green: the sweep's
      precondition now finds the service's line and the drive reaches it. `checkstyleMain` and
      `pmdMain` exit 0. The four `BATCH_LATE` sources fold into one kind carrying the stage it is
      stuck at as a bounded code - `awaiting-batch`, `awaiting-render-request`, `awaiting-render`,
      `awaiting-notification` - written as four constants rather than four sentences, because
      `reason` is a parsed slot and what goes in one comes from a vocabulary. `askedOf` carries
      the repository's usual `PMD.AvoidCatchingGenericException` / `PMD.OnlyOneReturn` pair with
      the comment the convention asks for: the port's contract is that a sink answers rather than
      throws, so the catch cannot be narrower than any broken sink, and the two exits answer one
      question - once in the sink's words and once in this service's.)
- [x] T035 [US1] `adapter/report/LogEventReportSink` - the two events of data-model.md written with
      `net.logstash.logback.argument.StructuredArguments.kv(...)`, **the only class in `src/main`
      that imports it**, which is the whole reason the log output is a sink rather than a `log.info`
      in the service: the summary's **ten** fields, and one exception event per entry over all five
      kinds. The summary carries **no delivery status** - that is the job's line, not a sink's claim.
      Fields that do not apply to a kind are omitted, not emitted as null, and nothing another system
      wrote is emitted at all. Green: T031, and the `LogEventReportSink` half of T033.
      (green at `78b2757`: `./gradlew test --tests '*LogEventReportSinkTest*' --tests
      '*TelemetryPrivacyTest*' -Dtest.noFailFast=true`, 45 tests, 2 failures, 0 errors - again
      T032's two, which stay red until T036. All nine of T031 are green, and so is the
      `LogEventReportSink` half of T033: the leg sweep now finds both of the sink's statements and
      the drive reaches both. `checkstyleMain` and `pmdMain` exit 0.
      **One deviation from this task's wording, and the sink's own contract decides it.** The
      arguments are `StructuredArguments.value(...)` rather than `kv(...)`. Both reach the
      `<arguments/>` provider as the same field; `kv` additionally renders as `name=value` where a
      `{}` placeholder consumes it, which is the whole point of `kv` - and neither of these lines
      has a placeholder, nor may ever grow one, which is what
      `no_identifier_appears_in_the_message_text` insists on. T031's `fieldsOf` reads each
      argument through the marker's public `toStringSelf()`, `getFieldValue()` being protected, so
      the value-only form is also the only one that suite can read a bare value out of. The class
      is still the only one in `src/main` that imports `StructuredArguments`.)
- [x] T036 [US1] The `<arguments/>` provider added to `src/main/resources/logback.xml` **and**
      `src/main/resources/logback-cli.xml`, inside the existing
      `LoggingEventCompositeJsonEncoder` provider list, beside `<mdc/>`. Nothing else in either file
      changes and no dependency is added - `logstash-logback-encoder` is already the only encoder
      either file declares. Green: T032.
      (green at `ac5ded4`: `./gradlew test --tests '*TelemetryPrivacyTest*'
      -Dtest.noFailFast=true` BUILD SUCCESSFUL, exit 0, 36 tests, 0 failures, 0 errors;
      `both_logback_files_declare_the_arguments_provider` passes over both parameterisations. The
      provider sits beside `<mdc/>` in both files and nothing else in either changed.)
- [x] T037 Phase close: `./gradlew build` green; **review gate 4** (FR-002's field list, the
      no-PII gate over both events, the read-only claim, the logging library's containment to one
      adapter, and that the summary event claims no delivery it could not observe); findings land as
      red/green pairs.
      (build half done at `49a15f4`: `./gradlew build -Dtest.noFailFast=true` BUILD SUCCESSFUL,
      exit 0, 3394 tests over 558 suites, 0 failures, 0 errors; Checkstyle at `maxWarnings = 0`
      over main and test, PMD over both, and the JaCoCo gate at LINE 0.88 / BRANCH 0.85, none of
      them loosened. The first phase-close run, at `ac5ded4`, was green on every task but
      `pmdTest`, which carried three findings in test files this phase had landed -
      `AvoidFieldNameMatchingMethodName` twice in `ExceptionReportDeliveryTest`, where both stub
      sinks held a field named `name` beside the port's `name()`, and `UseVarargs` once in
      `ExceptionReportServiceTest`. All three are style rather than behaviour and all three are
      closed at `49a15f4`.
      **Phase 4 was merged onto the increment branch before the gate ran**, at `77aff70`: the
      phase had been built on a branch taken before Phase 3 landed, and the two touched
      `config/ProcessingMetrics`. It resolved to **Phase 3's** version, whose
      `exceptionReportRun(ReportRunOutcome)` and `intakeSweepFailure(SweepFailureReason)` take
      enums; the Phase 4 stand-in `a00cdcf`, which declared String overloads so the report could
      compile while Phase 3 was in flight, is superseded by it. Nothing in the report needed
      changing - it calls only `exceptionReportDelivery(ReportSinkName, DeliveryStatus)` and
      `exceptionsReported(ExceptionKind, int)`, which the Phase 3 surface already carried with the
      same enum shape - and no other file conflicted. The merged tree built green with no fix:
      `./gradlew build` BUILD SUCCESSFUL, exit 0, 3422 tests, 0 failures, 0 errors.
      **Review gate 4 ran against the merged Phase 4 content** with three read-only reviewers.
      Verdicts: `code-reviewer` **PASS** (0 high, 0 medium, 4 low), `spec-validator`
      **COMPLIANT** (3 low), `qa` **FAIL** - narrow, and on two missing pins rather than on a
      failing suite. The five things the gate was called for were found clean: FR-002's field list
      matches data-model.md's ten and thirteen in the assertions that hold them, the no-PII gate
      holds over both events (every value a token, no address selected by any read, no
      `sdg_reason` anywhere), the read-only claim is verified over every repository the service
      holds, `net.logstash.logback.argument.StructuredArguments` appears in `LogEventReportSink`
      and nowhere else in `src/main`, and the summary event carries no `delivered_*` field of any
      kind.
      Findings, and where each was closed:
      * **the FR-013 case could not fail.** `a_request_is_reported_under_at_most_one_kind_per_run`
        seeded one read and asserted the entry that read produced, so no arrangement of the
        service could have failed it while it claimed to pin the partition. It seeds the same
        `(source, requestId)` in **both** intake answers now - what two statements taken a moment
        apart against a log the pipeline is still writing to produce, and what a status column
        that disagrees with itself produces - and asks for one entry, the failure. Red at
        `757f996` ("Expected size: 1 but was: 2"), green at `f0ba22f`: `build` folds the two
        answers on `(source, request_id)` with the failure winning, in the service because no
        single statement can see the other's answer.
      * **two exceptions of one age came back in whatever order the reads were made in** (QA).
        The sort stopped at the age and `List.sort` is stable, so a never-batched register - read
        *after* the dead batches - sorted below a failed batch of the same age for no reason
        anybody could state, and two mornings of one report could not be diffed against each
        other. `OLDEST_FIRST` falls through to `kind` in the enumeration's own order and then to
        the most specific identifier the entry carries. Red
        `entries_at_the_same_age_are_ordered_by_kind_then_identifier` at `757f996`, green at
        `f0ba22f`.
      * **a dead batch could be reported with no reason at all.** The reason is the whole of what
        `BATCH_FAILED` tells an operator - `BATCH_LATE` names the stage it stopped at, and a
        failed batch carrying nothing says only that a batch ended - and because the log sink
        omits absent fields rather than emitting nulls, such a row would leave no trace of its own
        emptiness. The read selects a column the table declares `NOT NULL` on a `FAILED` row, so
        an absent one is a projection that has drifted from its table. `ExceptionEntry`'s compact
        constructor refuses it. Red `a_batch_failed_entry_with_no_bounded_reason_is_refused` at
        `757f996`, green at `f0ba22f`.
      * **the one delivery nobody planned for was the one that escaped.** `askedOf` asked
        `sink.name()` **inside** the catch, and a sink names itself off the thing it delivers
        through - so the sink that has just broken is exactly the one that may no longer be able
        to answer. A throw there left `deliver`, taking the outcomes of every sink already asked
        with it, which is the loss the catch exists to prevent. The name is read once, before the
        sink is asked. Red
        `a_sink_whose_name_cannot_be_read_is_still_recorded_and_the_others_still_run` at
        `757f996`, green at `f0ba22f`.
      * **`<arguments/>` was asserted as characters** (spec-validator). A text search for the tag
        is satisfied by it sitting inside an XML comment - the one shape a well-meant edit
        actually takes, somebody quietening a local run - and by it sitting anywhere else in the
        file, where logback would never read it as a provider. `both_logback_files_declare_the_arguments_provider`
        parses the file and asks the encoder's own provider list. **Green on introduction**, at
        `757f996`: both shipped files declare it properly.
      * **the report's precondition was asked of the two classes together** (code-reviewer). The
        `isNotEmpty()` inside `should_have_reached_every_line_the_two_legs_can_write` was
        satisfied by `LogEventReportSink`'s two statements alone, so `ExceptionReportService`
        could have declared none and the sweep would still have said it covered them. It is
        `allSatisfy` over `GenerationLegs.THE_REPORT` now. **Green on introduction**, at
        `757f996`; `theReportsLoggers()` went with it.
      * **a read failure had no case at all** (QA). `build` has no catch and never had one, so an
        outage already leaves unchanged and nothing is counted - but nothing said so, and the
        distinction is the one FR-007's absorbed refusal turns on: a sink that refuses has a
        built report to be classified against, and a read that refuses leaves an all-clear about
        the half nobody could see. `a_read_failure_leaves_the_service_and_makes_no_report` is
        **green on introduction**, at `757f996`, and the commit body says so.
      * **an empty report had no case of its own** (code-reviewer, LOW). Every sink case seeded at
        least one entry, so the loop writing nothing was covered only by the count assertions.
        `an_empty_report_writes_exactly_one_event` is **green on introduction**, at `757f996`.
      * LOW, folded into the two commits with no red of their own: `stuckSinceFriday()` carried
        the parked request's id, which made the every-kind fixture seed one request twice
        (`757f996`); `GenerationLegs.anUnfinishedRequest()` carried it too, which the fold would
        have left with no REQUEST_LATE line to sweep (`f0ba22f`); and `kv(...)` was named in both
        logback files, `data-model.md`, `research.md` and `plan.md` while the sink has written
        `value(...)` since it was written - the same field, the wrong name (`f0ba22f`).
      Record-keeping this phase's ticks got wrong, corrected here rather than by editing T029:
      * T029's list omits `each_late_batch_stage_is_asked_about_its_own_limit`, which landed with
        the rest at `3261883`. Added to the list above and to plan.md's matrix row.
      Documentation this gate moved, against the earlier claim that `plan.md` was untouched by
      this phase: plan.md's matrix rows for `ExceptionReportServiceTest`,
      `ExceptionReportDeliveryTest`, `LogEventReportSinkTest`, `ExceptionReportModelTest` and
      `TelemetryPrivacyTest` carry the cases above; data-model.md records the fold, the tiebreak
      and the `BATCH_FAILED` reason as invariants, and its `ExceptionEntry` table now shows
      `hearingId` on `BATCH_LATE` from the never-batched source - which the code has always
      carried, and which is an identifier like every other value in that table.
      Nothing in the gate asked for a `doc/DEFECT-FIXES.md` row and none was added: every finding
      is about this increment's own report, not about a legacy behaviour.
      Phase-close build **after** the findings landed, at `f0ba22f`: `./gradlew build
      -Dtest.noFailFast=true` BUILD SUCCESSFUL, exit 0, 3427 tests over 562 suites, 0 failures,
      0 errors; `checkstyleMain` and `checkstyleTest` at `maxWarnings = 0`, `pmdMain` and
      `pmdTest`, and `jacocoTestCoverageVerification` at LINE 0.88 / BRANCH 0.85 all ran in that
      one invocation and none of them was loosened - nothing under `gradle/`, `config/`,
      `.github/` or `build.gradle` moved anywhere in Phase 4 or in this gate. Phase 5 may start.)

**Checkpoint**: the report exists and writes its events. Nothing schedules it yet - that is Phase 5.

---

## Phase 5: User Story 1 - the 07:00 run, on a scheduler of its own (Priority: P1) 🎯 MVP

**Goal**: the report runs at 07:00 Europe/London Monday to Friday, once across every replica, on a
pod where the generation half is switched off, and never on a JVM started for a command - and every
scheduled method in the service names the scheduler it runs on, so the three-scheduler separation
SC-008 rests on is a behaviour rather than a comment.

**Independent Test**: start a context with `yotresultsdistribution.report.enabled=true` and
`yotresultsdistribution.generation.enabled=false`; confirm the scheduling infrastructure is present, the
report scheduler is its own single thread, the report job and the sweep both exist and no generation
bean does - and that the context starts at all, which is what the relocated repository beans buy;
start it with both flags false and confirm the sweep still runs; start it with
`yotresultsdistribution.cli=true` and confirm none of the three configurations is contributed.

### Tests first ⚠️

- [x] T038 [P] [US1] `config/ReportSchedulingConfigTest` - **every infrastructure-presence case lives
      in this one class**, so there is one place to read what a given pod wires and one place to
      change it: `the_scheduling_infrastructure_is_present_whatever_the_two_flags_say` (parameterised
      over the four combinations of `yotresultsdistribution.report.enabled` and
      `yotresultsdistribution.generation.enabled`, because the sweep must refresh the gauges on a pod with
      both switched off - user story 2 scenario 4),
      `the_sweep_is_declared_whatever_the_two_flags_say` on its own single-thread `TaskScheduler`
      named `intake-sweep-`,
      `the_report_job_is_declared_only_when_the_report_is_enabled`,
      `the_report_has_its_own_single_thread_task_scheduler_named_exception_report`,
      `the_report_scheduler_is_neither_the_generation_scheduler_nor_the_sweeps` (SC-008: the 07:00
      run and the fixed-delay sweep cannot land on the thread the 18:00 run or the reconciler is
      using), `each_configuration_publishes_its_schedulers_bean_name_as_a_constant` -
      `SchedulingConfig.GENERATION_SCHEDULER` holds the name of the **existing**
      `registerGenerationScheduler` bean and declares no new one, `ReportSchedulingConfig.REPORT_SCHEDULER`
      and `IntakeSweepConfig.INTAKE_SWEEP_SCHEDULER` hold theirs, and each constant resolves to a
      `TaskScheduler` bean that is actually on the context -
      `there_is_exactly_one_lock_provider` (two providers over one `shedlock` table is a race
      dressed as configuration),
      `the_scheduler_lock_default_is_unchanged` (`@EnableSchedulerLock(defaultLockAtMostFor =
      RegisterGenerationJob.LOCK_AT_MOST_FOR)` moves verbatim: the default is a fallback for a
      `@SchedulerLock` that states none, all three locked methods state their own, so it is inert -
      which is exactly why it is not "tidied" to the report's budget on the way past), and
      `the_generation_beans_are_unchanged`.
      Seams: `config/SchedulingInfrastructureConfig`, `config/ReportSchedulingConfig` and
      `config/IntakeSweepConfig` as empty `@Configuration` classes, each declaring its scheduler-name
      constant. Red: no `LockProvider` is present when only the report is enabled.
      (red at `77564fb`: `./gradlew test --tests '*ReportSchedulingConfigTest*'
      -Dtest.noFailFast=true`, 15 tests, 12 failures, 0 errors, every one an assertion. The task's
      own red is among them - `there_is_exactly_one_lock_provider` on "[two providers over one
      shedlock table is a race dressed as configuration, and none at all is a report pod whose
      07:00 run is unlocked] Expecting actual: [] to have size 1" - because `@EnableSchedulerLock`
      and the provider sit inside a configuration conditional on the generation half. So does
      `the_scheduling_infrastructure_is_present_whatever_the_two_flags_say`, which fails on both
      pods that generate nothing, and `the_sweep_is_declared_whatever_the_two_flags_say`, which
      fails on all four. `the_generation_beans_are_unchanged` is green on introduction, which is
      the point of it.
      Two things about the commit rather than the task. `batch/ExceptionReportJob` lands here as a
      `void run()` over a body that throws, because
      `the_report_job_is_declared_only_when_the_report_is_enabled` asserts on the type and T041's
      suite would otherwise not compile; T041 pins what it must do. And the contexts are built with
      an `ApplicationContextRunner` over the six configurations under assertion plus one test
      configuration of doubles, rather than with `@SpringBootTest`: the parameterisation over four
      flag combinations needs a context per case, and what is under assertion is which beans a set
      of conditions contributes - a suite about wiring that needed a database would be a suite that
      stopped being run.)
- [x] T039 [US1] `config/ReportSchedulingConfigTest` (extend, same file as T038 so it follows it
      rather than running beside it) - `the_report_wires_with_generation_disabled`: an
      `ApplicationContextRunner` with `yotresultsdistribution.report.enabled=true` **and**
      `yotresultsdistribution.generation.enabled=false` starts, and holds `ExceptionReportService`,
      `ExceptionReportJob`, `RegisterBatchRepository` and `RegisterNotificationRepository`. This is
      FR-004's deployment and the MVP's own shape, and today it cannot start: both repository beans
      are declared inside `config/GenerationConfig`, which is conditional on
      `yotresultsdistribution.generation.enabled`, while the report reads both - `BATCH_LATE` and
      `BATCH_FAILED` from one, `NOTIFICATION_FAILED` from the other. Red: the context fails to start
      with a `NoSuchBeanDefinitionException` naming `RegisterBatchRepository`.
      (red at `d200f25`: `./gradlew test --tests '*ReportSchedulingConfigTest*'
      -Dtest.noFailFast=true`, 16 tests, 13 failures, 0 errors, every one an assertion. The new
      case fails on the first bean it asks for - "Expecting actual not to be empty", there being no
      `ExceptionReportService` on any context yet. **The predicted red did not happen and could
      not**: nothing on the context asks for `RegisterBatchRepository` until T045 contributes the
      job, so today the context starts and simply holds none of the four. The prediction is what
      T045 would have produced had T044 not moved the repositories first - which is why this case
      goes green at `1958a33` rather than at `87b092a`.)
- [x] T040 [P] [US1] `config/CliModeConfigTest` (extend) -
      `a_cli_context_contributes_no_scheduling_infrastructure`,
      `a_cli_context_contributes_no_report_scheduling` and
      `a_cli_context_contributes_no_intake_sweep`, so `@EnableScheduling` is absent and
      `@Scheduled` is never processed on a command JVM. Two of the three configurations are
      conditional on **nothing else**, which is exactly why this assertion is the one that matters.
      The existing assertions about the consumer, the public-event listener and
      `TheShippedDefault`'s three condition cases are untouched and **must keep passing**: a CLI JVM
      runs no scheduled task of any half. Red: the report configuration is contributed with
      `yotresultsdistribution.cli=true`.
      (red at `5499096`: `./gradlew test --tests '*CliModeConfigTest*' -Dtest.noFailFast=true`,
      14 tests, 3 failures, 0 errors, every one an assertion and every one the task's own red -
      "Expecting actual: [\"schedulingInfrastructureConfig\"] to be empty", and the same for the
      report's and the sweep's, the seams carrying `@Profile("!test")` and nothing else. The eleven
      existing cases, the ordinary pod's included, are untouched and all pass.)
- [x] T041 [P] [US1] `batch/ExceptionReportJobTest` -
      `the_report_is_scheduled_in_europe_london` (the reflection case, written as
      `GenerationReconcilerTest.ItsOwnSchedule` writes its own:
      `ExceptionReportJob.class.getDeclaredMethod("run")` is `void`, `@Scheduled.cron` is
      `${yotresultsdistribution.report.cron}`, `@Scheduled.zone` is `${yotresultsdistribution.report.zone}`,
      `@SchedulerLock.name` is `exception-report` and `@SchedulerLock.lockAtMostFor` is **the
      placeholder** `${yotresultsdistribution.report.lock-at-most-for}` and not a literal, so the duration is
      written once),
      `the_lock_name_is_neither_generations_nor_the_reconcilers`,
      `the_window_the_job_asks_for_is_the_one_since_the_previous_scheduled_run` - asserted over a
      **mocked** window computation, because the Monday-to-Friday arithmetic has exactly two homes,
      `LastScheduledRunTest` and `ExceptionReportModelTest`, and a third copy is a third place to
      change when the schedule changes,
      `the_run_id_is_opened_for_the_run_passed_into_build_and_removed_afterwards` (the scheduler's
      threads are pooled, and the service is given the id rather than reading the MDC),
      `the_run_line_is_written_after_every_sink_has_returned` - one flat
      `event=exception_report_run` line carrying `run_id`, `window_from`, `window_to`, `entries`,
      `delivered_log`, `delivered_email`, `outcome` and `duration_ms`, written **last**, the way
      `RegisterGenerationJob.recorded` writes `register_generation_run` after a night; a job that
      wrote it earlier would be reporting a delivery it had not yet observed,
      `delivered_email_is_disabled_where_the_sink_is_not_on_the_context` (and `skipped` is the
      command's word, not the job's - the job delivers to every sink there is),
      `a_run_delivered_to_both_sinks_is_outcome_delivered`,
      `a_run_delivered_to_one_sink_is_outcome_partial`,
      `a_run_delivered_to_neither_is_outcome_failed`,
      `every_outcome_is_counted_on_the_runs_counter_so_a_quiet_morning_and_a_missing_run_are_different_observations`,
      `a_read_failure_counts_the_run_failed_and_rethrows` - a `StoreOutage` part-way through the run
      counts `yotresultsdistribution_exception_report_runs_total{outcome=failed}`, still writes the run line,
      and **rethrows**, so ShedLock releases the lock and the failure is visible rather than logged
      and dropped. This is the other branch from the sweep's: a failed read here is the report
      itself, not telemetry about it (constitution Principle VI, and
      `.claude/rules/design_rules.md`'s "every other refusal still leaves"), and
      `the_cutover_flag_is_never_read` - the report reads and writes nothing the cutover decides, so
      it is not on the lever's circuit and adds no second reader.
      Seam: `batch/ExceptionReportJob` with a `void run()` and a body method throwing. Red: the
      reflection assertion fails because the method carries no `@SchedulerLock`.
      (red at `670ca5b`: `./gradlew test --tests '*ExceptionReportJobTest*'
      -Dtest.noFailFast=true`, 12 tests, 11 failures, 0 errors. The task's own red is
      `the_report_is_scheduled_in_europe_london` - "[a report nothing fires is a morning support
      hears nothing] Expecting actual not to be null" - the method carrying neither `@Scheduled`
      nor `@SchedulerLock`. `the_lock_name_is_neither_generations_nor_the_reconcilers` fails on that
      same absent annotation rather than on a name - the case reads `@SchedulerLock` through a null
      guard, so what it compares against `exception-report` is a null - and eight more fail out of
      the seam's body, which throws, while
      `a_read_failure_counts_the_run_failed_and_rethrows` fails as an assertion on the wrong
      throwable. `the_cutover_flag_is_never_read` is green on introduction and stays so: the job
      holds no field whose type names a flag.
      The seam itself landed one commit earlier, with T038, which asserts on the type.
      `the_report_names_the_report_scheduler` is in this file but belongs to T042 and landed with
      the other three.)
- [x] T042 [P] [US1] The **scheduler-attribute** reflection cases, one in each suite that owns a
      scheduled method, because three `TaskScheduler` beans on one context route nothing by
      themselves: Spring resolves a single scheduler for `@Scheduled` processing unless the method
      names one, so without this attribute SC-008's separation is a comment rather than a behaviour.
      The attribute exists on this classpath - `@Scheduled.scheduler()` arrived in Spring Framework
      6.1 and `javap -v` over `org/springframework/scheduling/annotation/Scheduled.class` in the
      `spring-context-7.0.9.jar` this Boot 4.1.1 BOM resolves lists
      `public abstract java.lang.String scheduler();` - so no `SchedulingConfigurer` fallback is
      needed. Four cases:
      `batch/RegisterGenerationJobTest.WhenItRuns` (extend) -
      `the_run_names_the_generation_scheduler`;
      `batch/GenerationReconcilerTest.ItsOwnSchedule` (extend) -
      `the_sweep_names_the_generation_scheduler` (the reconciler shares generation's scheduler
      exactly as it does today; naming it changes the routing from implicit to stated and moves no
      bean);
      `batch/ExceptionReportJobTest` (extend) - `the_report_names_the_report_scheduler`;
      `batch/IntakeAgeSweepTest` (extend) - `the_intake_sweep_names_its_own_scheduler`.
      Each reads `@Scheduled.scheduler()` off the declared method and asserts it equals the constant
      its configuration publishes. Seams: `SchedulingConfig.GENERATION_SCHEDULER` declared as a
      public constant holding `"registerGenerationScheduler"`, the **existing** bean's name - no bean
      is added, renamed or moved - beside `ReportSchedulingConfig.REPORT_SCHEDULER` and
      `IntakeSweepConfig.INTAKE_SWEEP_SCHEDULER` from T038. Red: `scheduler()` is `""` on all four
      methods, so every case fails on the empty string.
      (red at `8e7cf24`: `./gradlew test` over the four suites, 134 tests, 15 failures, 0 errors.
      Four of them are these. Three fail on the empty string exactly as predicted - `expected:
      "registerGenerationScheduler" but was: ""`, and the same for `intakeSweepScheduler` on the
      sweep - and the report's fails on **`null`** rather than on `""`, because
      `ExceptionReportJob.run()` carried no `@Scheduled` at all until T047 and the case reads the
      attribute through a null guard. The prediction held for the three methods that were already
      scheduled; the fourth was a seam. The other eleven are T041's. The attribute was checked rather than
      assumed before the cases were written: `javap -p` over
      `org/springframework/scheduling/annotation/Scheduled.class` in the resolved
      `spring-context-7.0.9.jar` lists `public abstract java.lang.String scheduler();` beside
      `cron`, `zone` and `fixedDelayString`, so no `SchedulingConfigurer` fallback was needed.)

### Implementation

- [x] T043 [US1] `config/SchedulingInfrastructureConfig` (new) - `@EnableScheduling`,
      `@EnableSchedulerLock` and the `LockProvider` bean moved out of `config/SchedulingConfig`,
      conditional on **nothing but not being a command JVM**
      (`@Conditional(CliModeConfig.NotCliMode.class)` plus the `@Profile("!test")` every
      database-touching configuration here carries) - **no enabled-flag condition at all**, because
      the sweep must be scheduled on a pod with the report and the generation half both switched off
      and a flag condition would leave exactly that pod without `@Scheduled` processing at all.
      `@EnableSchedulerLock` moves **verbatim**, still
      `defaultLockAtMostFor = RegisterGenerationJob.LOCK_AT_MOST_FOR`: it is the fallback for a
      `@SchedulerLock` that states no duration, all three locked methods state their own, and
      changing it here would be a change to generation's lock semantics made in a task about the
      report's wiring. `config/SchedulingConfig` slimmed to the generation `TaskScheduler`, the
      `RegisterGenerationJob` bean and the new `GENERATION_SCHEDULER` constant under its existing
      conditions; `config/CliModeConfig`'s javadoc updated to say the conditional now goes on **all
      three** new configurations, because the annotation moved and two of the three have no other
      condition. Green: T038, T040.
      (green at `d6d0f47`: `./gradlew test --tests '*ReportSchedulingConfigTest*' --tests
      '*CliModeConfigTest*' -Dtest.noFailFast=true`, 30 tests, 11 failures, 0 errors. T038's four
      infrastructure parameterisations, `there_is_exactly_one_lock_provider`,
      `the_scheduler_lock_default_is_unchanged` and T040's
      `a_cli_context_contributes_no_scheduling_infrastructure` are green, as are the eleven existing
      CLI cases; the eleven failures are T044 to T047's.
      `@EnableSchedulerLock(defaultLockAtMostFor = RegisterGenerationJob.LOCK_AT_MOST_FOR)` moved
      character for character. `checkstyleMain` exits 0; `pmdMain` reports six `UnusedPrivateField`
      violations, all of them T041's seam, which T047 fills in.)
- [x] T044 [US1] `config/ProcessedLogConfig` and `config/GenerationConfig` - move the
      `registerBatchRepository` and `registerNotificationRepository` `@Bean` methods out of
      `GenerationConfig` (conditional on `yotresultsdistribution.generation.enabled`) into the
      generation-neutral `ProcessedLogConfig`, verbatim, javadoc included: it is `@Profile("!test")`
      and nothing else, and it already declares `ProcessedRequestRepository`,
      `ProcessedOutputRepository`, `registerStore` and `idempotencyGuard` over the same `JdbcClient`
      and the same `PlatformTransactionManager` those two constructors take. `ProcessedRequestRepository`
      is already there and does not move. Nothing about either repository class changes - only where
      its bean is declared - and `GenerationConfig` keeps everything else it owns.
      Green: T039.
      (green at `87b092a`: `./gradlew test --tests '*ReportSchedulingConfigTest*'
      -Dtest.noFailFast=true`, 16 tests, 9 failures, 0 errors. `the_generation_beans_are_unchanged`
      stays green over both pods and the two report-pod contexts still start. T039 itself cannot go
      green here and does not: it asks for `ExceptionReportService` first, and nothing declares one
      until T045 - which is also the commit that would have raised the
      `NoSuchBeanDefinitionException` this task prevents. Both `@Bean` methods moved character for
      character, javadoc included, and `GenerationConfig` lost only the two now-unused imports.
      `checkstyleMain` exits 0.)
- [x] T045 [US1] `config/ReportSchedulingConfig` (new) - conditional on
      `yotresultsdistribution.report.enabled` and not CLI; its own single-thread `TaskScheduler` with thread
      prefix `exception-report-`, the public `REPORT_SCHEDULER` constant naming that bean, and the
      `ExceptionReportJob` bean. The sweep is **not** here: it is not the report's, and a pod with
      the report switched off still needs its gauges.
      Green: the report scheduler and job cases of T038.
      (green at `1958a33`: `./gradlew test --tests '*ReportSchedulingConfigTest*'
      -Dtest.noFailFast=true`, 16 tests, 6 failures, 0 errors. The report scheduler and job cases
      are green and so is T039's `the_report_wires_with_generation_disabled`; the six failures are
      all T046's sweep cases. `checkstyleMain` and `checkstyleTest` exit 0.
      **One decision this task did not name, taken here and worth reading.**
      `ExceptionReportService` and the `LogEventReportSink` bean are declared in
      `config/ProcessedLogConfig`, not in `ReportSchedulingConfig`. The report is asked for by two
      callers that never share a context - this job, which exists only where the schedule is on, and
      T057's command, which runs on a JVM that contributes no scheduling configuration at all - so a
      report declared beside the schedule would be a report the command could not ask for, and a
      bean that moved twice is a bean two phases change. `ProcessedLogConfig` already declares every
      other reader of the same database over the same client and clock, and `IdempotencyGuard` is
      the standing precedent for an application class living there.
      **One mechanical fix to T038's own suite**, in this commit rather than left to look like an
      assertion that changed: the two single-thread claims asked
      `ThreadPoolTaskScheduler.getPoolSize()`, which answers how many threads the executor has
      *started* - none, on a scheduler never handed a task. They ask the configured core size now;
      the claim is unchanged.)
- [x] T046 [US1] `config/IntakeSweepConfig` (new) - conditional on
      `@Conditional(CliModeConfig.NotCliMode.class)` and `@Profile("!test")` and **nothing else**;
      its own single-thread `TaskScheduler` with thread prefix `intake-sweep-`, the public
      `INTAKE_SWEEP_SCHEDULER` constant naming that bean, and the `IntakeAgeSweep` bean built in
      T027. The gauges belong to the intake half, so they refresh wherever the intake half runs -
      which is every service JVM - and the sweep's own scheduler keeps it off the thread the 07:00
      report, the 18:00 run and the reconciler use (SC-008).
      Green: the sweep cases of T038.
      (green at `9bfaa6f`: `./gradlew test --tests '*ReportSchedulingConfigTest*' --tests
      '*CliModeConfigTest*' -Dtest.noFailFast=true` BUILD SUCCESSFUL, exit 0, 30 tests, 0 failures,
      0 errors. Both wiring suites are wholly green: every pod shape this increment serves, and the
      command JVM that must serve none of them. The sweep's threshold is
      `ReportProperties.requestTerminalWithin`, which is the only thing it shares with the report
      and shares deliberately - the gauge and the morning report must agree about which requests are
      late. `checkstyleMain` exits 0.)
- [x] T047 [US1] `batch/ExceptionReportJob` - `@Scheduled(cron = "${yotresultsdistribution.report.cron}",
      zone = "${yotresultsdistribution.report.zone}")` and `@SchedulerLock(name = "exception-report",
      lockAtMostFor = "${yotresultsdistribution.report.lock-at-most-for}")` on a **`void`** `run()` that opens
      `RunCorrelation.under(...)` and delegates to a body returning the `ExceptionReport` - the body
      stays directly callable by the CLI and by a unit test without going through the proxy. After
      **every** sink has returned it writes one flat `event=exception_report_run` line carrying
      `run_id`, `window_from`, `window_to`, `entries`, `delivered_log`, `delivered_email`
      (`ok`/`failed`/`disabled` on this path), `outcome` (`delivered`/`partial`/`failed`) and
      `duration_ms`, exactly as `RegisterGenerationJob.recorded` writes its own; the run's outcome is
      counted on `yotresultsdistribution_exception_report_runs_total{outcome}` with the same word. A read
      failure counts `outcome=failed`, writes the line and rethrows. It holds a clock, a lock and
      collaborators and no driver, broker or HTTP client. Add `ExceptionReportJob` and
      `IntakeAgeSweep` to `support/GenerationLegs.THE_LEGS` and drive both in the same commit.
      Green: T041.
      (green at `5f94606`: `./gradlew test --tests '*ExceptionReportJobTest*' --tests
      '*TelemetryPrivacyTest*' --tests '*IntakeAgeSweepTest*' -Dtest.noFailFast=true`, 58 tests,
      2 failures, 0 errors - both of them T048's scheduler-attribute cases. All eleven of T041's
      other cases are green and the privacy sweep passes over the two widened legs: `THE_LEGS` gains
      `ExceptionReportJob` and `IntakeAgeSweep`, with an arrangement each for every line they can
      write - the morning that delivered, the morning whose store would not answer, and the gauge
      refresh that could not be taken. Their instruments go on a registry of their own, so the
      downstream half's two meter cases keep describing the downstream half.
      Two details decided here rather than in the task. Whether there is an e-mail sink at all is
      read **in the constructor**, while the sinks are whole, for the reason `askedOf` reads a
      sink's name before asking it to deliver: the sink that has just broken is the one that may no
      longer be able to say who it is. And a sink that only **partially** delivered is `failed` on
      this line rather than `ok` - some recipients were told and the rest are a resend, which is a
      thing to act on; the run's own `outcome=partial` is where that nuance is expressed.
      `checkstyleMain`, `checkstyleTest`, `pmdMain` and `pmdTest` exit 0, the catch carrying the
      `PMD.AvoidCatchingGenericException` suppression with the comment the convention asks for.)
- [x] T048 [US1] The `scheduler` attribute on all four scheduled methods, and the one new constant
      behind it: `SchedulingConfig.GENERATION_SCHEDULER` (`"registerGenerationScheduler"`, the
      existing bean; **no bean is added, renamed or moved**) named by
      `batch/RegisterGenerationJob.run()` and `batch/GenerationReconciler.reconcileScheduled()`;
      `ReportSchedulingConfig.REPORT_SCHEDULER` named by `batch/ExceptionReportJob.run()`;
      `IntakeSweepConfig.INTAKE_SWEEP_SCHEDULER` named by `batch/IntakeAgeSweep.sweepScheduled()`.
      Nothing else about the four methods changes - not the cron, not the zone, not the fixed delay,
      not a lock name. This is the task that turns three `TaskScheduler` beans into three schedulers
      that are actually used, which is what SC-008's claim rests on. Green: T042.
      (green at `750c3fc`: `./gradlew test` over the six suites the split touches, 164 tests,
      0 failures, 0 errors. All four reflection cases pass, and so do the two wiring suites and the
      two generation suites whose schedules moved from implicit routing to stated. Nothing else
      about the four methods changed - not a cron, not a zone, not a fixed delay, not a lock name -
      and no bean was added, renamed or moved. `checkstyleMain` and `pmdMain` exit 0.)
- [x] T049 [P] [US1] `docker-compose.yml` - `YOTRESULTSDISTRIBUTION_REPORT_ENABLED: "true"` and
      `YOTRESULTSDISTRIBUTION_INTAKE_GAUGE_REFRESH: 10m` on the `app` service, in the block 002 wrote, with
      the comments quickstart.md gives: the report is a read of this service's own store and must
      keep running on a pod that generates nothing, which is what makes an intake-only deployment
      alertable; and the gauges refresh in **every** service JVM, under no lock, so alerts aggregate
      across pods with `max()`. **The gauge-refresh variable belongs here rather than with the
      e-mail block**, because the sweep exists from Phase 3 and the e-mail sink does not exist yet.
      The three e-mail variables land at T068.
      Infrastructure: the commit records `docker compose up -d app`, the 07:00 schedule registered in
      the startup log, and the two gauges present on `/actuator/prometheus`.
      (done at `6e27131`. `docker compose up -d --build app` against the 002 dependency set; the app
      answered `/actuator/health` in about five seconds. **All four schedules registered**, read off
      `/actuator/scheduledtasks`: `RegisterGenerationJob.run` at `0 0 18 * * MON-FRI`,
      **`ExceptionReportJob.run` at `0 0 7 * * MON-FRI`** with next execution
      `2026-09-16T05:59:59.999Z` - 07:00 Europe/London in BST, so the zone is applied and not the
      container's UTC - and `GenerationReconciler.reconcileScheduled` and
      `IntakeAgeSweep.sweepScheduled` on fixed delays of 600000ms, the sweep having already run once
      with status SUCCESS. **Both gauges present** on `/actuator/prometheus`:
      `yotresultsdistribution_non_terminal_requests_over_threshold 0.0` and
      `yotresultsdistribution_oldest_non_terminal_request_age 0.0`, each with its HELP and TYPE, published
      from a real sweep against a real empty store rather than declared and never moved.
      `docker compose down` afterwards; no container of this project left running.
      Two things about the run rather than the change. The schedule is **not** in the startup log -
      nothing prints it at INFO - so the evidence is `/actuator/scheduledtasks`, which this service
      does not expose and which was exposed for the duration of the check through a compose override
      file outside the repository; nothing in `docker-compose.yml` changed about the endpoint list.
      And `fileservice-postgres`'s host mapping of 5433 was already taken on this machine by an
      unrelated container, so that service ran on another host port through the same override - the
      app reaches it over the compose network on 5432 either way.)
- [x] T050 Phase close: `./gradlew build` green; **review gate 5** (the scheduling split against
      `CliModeConfigTest`, one `LockProvider`, the unchanged `@EnableSchedulerLock` default, SC-008's
      three separate schedulers **named on all four scheduled methods**, FR-004's independence from
      the generation switch, and the relocated repository beans - that `GenerationConfig` still owns
      everything generation-only and that nothing else moved with them); findings land as red/green
      pairs.
      (build half done with the Phase 5 tick at `24710ec`: `./gradlew build -Dtest.noFailFast=true`
      BUILD SUCCESSFUL, exit 0, 3462 tests over 564 suites, 0 failures, 0 errors, with Checkstyle at
      `maxWarnings = 0` over main and test, PMD over both and the JaCoCo gate at LINE 0.88 /
      BRANCH 0.85, none of them loosened. The tree with this gate's fixes in it is built whole at
      T069, the next phase close; what closed each finding here is named beside it.
      **Review gate 5 ran with three read-only reviewers.** Verdicts: `code-reviewer` **PASS**
      (0 high, 1 medium, 2 low), `spec-validator` **COMPLIANT** (3 low), `qa` **PASS** with named
      gaps rather than a failing suite. The six things the gate was called for were found clean:
      the scheduling split holds against `CliModeConfigTest` - a command JVM contributes none of the
      three new configurations; there is exactly one `LockProvider`; `@EnableSchedulerLock`'s
      default moved across verbatim and is inert because all three locked methods state their own;
      all four scheduled methods name the scheduler their own configuration publishes, and there are
      three `TaskScheduler` beans and never a fourth; the report wires with
      `yotresultsdistribution.generation.enabled=false`, which is FR-004's deployment; and the two relocated
      repository beans left `GenerationConfig` owning everything generation-only, with nothing else
      moved beside them.
      Findings, and where each was closed:
      * **the body only its own wrapper could call correctly.** `report()` is documented as
        directly callable - that is why `run()` is `void` - but it read `RunCorrelation.current()`
        for the service and for its own line, so a direct call reported a run under no correlation
        at all, and T057's command path and this one were the same shape only by coincidence. It
        takes the run id as an argument now and `run()` hands in the one it opened. Red
        `the_body_takes_its_run_id_from_the_caller_and_never_reads_the_mdc` at `2a7e78e`
        (*expected: "a-run-the-caller-already-opened" but was: null*), green at `96257bd`.
      * **the rethrow was queued behind the run line** (MEDIUM). The failure path wrote the line
        and counted the outcome *inside* the catch and threw afterwards, so a registry or an
        appender that refused would have replaced the store outage with its own complaint - the one
        throwable nobody can act on standing in for the one they can. The recording is one
        statement in a `finally` now, so the failure is already in flight when the line is written
        and the same call is no longer spelled on two paths. Green at `96257bd`, under the two
        cases below.
      * **a one-sink morning that refused was unpinned** (QA). `outcomeOf` folds three states over
        however many sinks were asked, and every case about it held two; the MVP's own deployment
        holds **one**, and a fold that only counted refusals against a second sink would have
        called that morning delivered. `a_run_asked_of_one_sink_that_failed_is_outcome_failed` is
        **green on introduction**, at `2a7e78e`.
      * **the failure path's line was asserted on two of its nine fields** (QA). The morning that
        produced nothing is the morning whose line is read hardest, and nothing said it carries the
        window, `entries=0`, both delivery fields and the duration rather than a shorter line a
        saved query would have to allow for separately. `the_failure_paths_run_line_carries_every_field`
        is **green on introduction**, at `2a7e78e`.
      * **the lock-provider claim was made about two deployments of four** (spec-validator, LOW).
        The provider carries no flag condition, so the claim was always about all four; the case
        tried `true,false` and `false,false` while the two cases beside it are already
        four-combination parameterised ones. `there_is_exactly_one_lock_provider` is now one too,
        and **green on introduction**, at `2a7e78e`.
      * **the compose comment said EVERY service JVM** (LOW). `IntakeSweepConfig` is conditional on
        `NotCliMode`, which `CliModeConfigTest` holds it to, so a command JVM refreshes no gauge -
        deliberately, because a gauge published by a process that exits is a reading nothing
        scrapes. The comment says "every service JVM that is not a command" now, in
        `docker-compose.yml` and in the quickstart block it mirrors. Closed at `9b035d6`.
      * **three documentation statements had drifted from the code** (spec-validator, LOW; QA).
        plan.md's Project Structure did not record that `ExceptionReportService` and
        `logEventReportSink` are declared in `ProcessedLogConfig`, which the Phase 5 tick records as
        a decision and the structure did not carry; its test-matrix row for the job still said the
        window comes from `ReportWindow.sinceLastScheduledRun`, which is the **command's** factory
        (review gate 2 split the two, and the job uses `forScheduledRun`); and the same row, with
        the Summary's own rendering of the line, listed `skipped` among the job's `delivered_email`
        values, which is the one word that line cannot carry - the job asks every sink there is.
        All three corrected in this commit, with the gate's three new cases added to the row.
      * **two tick narratives described their red inaccurately.** T041's recorded red put nine
        failures on the seam's body; `the_lock_name_is_neither_generations_nor_the_reconcilers` is
        one of them and fails on the **absent annotation** read through a null guard, not on a
        name, so it is eight. T042 predicted `""` on all four scheduler attributes and recorded
        four; the report's fails on **`null`**, because `ExceptionReportJob.run()` carried no
        `@Scheduled` at all until T047. Both corrected in this commit. A red recorded as something
        it was not is the one part of this file a later reader has no way to check.
      **One finding is recorded and deliberately not acted on** (code-reviewer, LOW). Several
      shipped javadoc comments cite `.claude/rules/design_rules.md` **by path** -
      `ExceptionReportJob` and `ExceptionReportJobTest` among them - and a repository path under
      `.claude/` is a tooling fingerprint in a file that ships in the image. The rule it cites is a
      real rule and citing it is right; what is in question is the spelling. The decision is the
      design owner's and is pending, and the sweep - every occurrence, in one commit, citing "the
      service's design rules" by name instead - is deferred to Phase 8, where the documentation sync
      already lives. No code changed for it here.)

**Checkpoint**: 🎯 **MVP complete.** Phases 1 to 5 are US1 + US2 + US5, deployable with
`yotresultsdistribution.report.enabled=true`, `yotresultsdistribution.generation.enabled=false` and
`email.enabled=false`: the morning report reaches Log Analytics over a window that needs no setting,
the gauges are alertable on every pod, and every threshold is a setting with a refusal behind it.

---

## Phase 6: User Story 3 - support pulls the report on demand during an incident (Priority: P2)

**Goal**: a sixth operations command, `report-exceptions`, producing the same report for a window
given as an instant or a duration, as a table on standard output, optionally e-mailed.

**Independent Test**: with seeded exceptions, run the command with a window that covers them and
confirm the table lists each once with the same fields the events carry; run it with a window that
covers none and confirm a single line saying so; run it with a malformed window and confirm a refusal
that names the argument and never quotes the token.

**Depends on Phase 4's `ExceptionReportService`** - the command reuses it rather than composing a
second read path.

### Tests first ⚠️

- [x] T051 [P] [US3] `batch/cli/ArgsTest` (extend) - `since_is_an_option_and_email_is_a_flag`,
      `both_are_in_names`, and `permits_rejects_since_and_email_on_the_other_five_commands`
      (generate-register, notify-register, list-batches, supersede-before, check-flag). Seams:
      `Args.SINCE` and `Args.EMAIL` constants declared and added to `NAMES`. Red: `permits` accepts
      `--since` on `check-flag`.
      (done at `ceff426`. The seam is the two constants alone - `NAMES` still held eight - and the
      recorded red is `both_should_be_in_names`: *"Expecting SetN: [... eight names] to contain:
      ["since", "email"] but could not find the following element(s): ["since", "email"]"* and
      *"Expected size: 10 but was: 8"*. **The task's stated red is not reachable and was not
      recorded**: `permits` consults only the two sets a command declares and has never consulted
      `NAMES`, so it rejected both names before either constant existed. That case
      (`permits_should_reject_since_and_email_on_the_other_five_commands`, parameterised over the
      five commands' own declared sets) and `since_should_be_an_option_and_email_should_be_a_flag`
      are therefore characterisations, and `both_should_be_in_names` is the assertion T056 turned
      green. Names carry `should`, as this repository's method convention has them.)
- [x] T052 [P] [US3] `batch/cli/ReportExceptionsCliTest` - the `--since` parse table of
      data-model.md, one case per row:
      `an_iso_instant_is_the_windows_from`, `an_iso_duration_is_subtracted_from_now`,
      `the_day_hour_minute_and_second_shorthands_are_subtracted_from_now` (parameterised over `2d`,
      `2h`, `30m`, `90s`),
      `an_absent_since_uses_the_window_since_the_previous_scheduled_run` (the same
      `ReportWindow.sinceLastScheduledRun` the 07:00 job uses, so the bare command answers what the
      morning run would have answered - there is no `yotresultsdistribution.report.window` to fall back on;
      asserted over the same mock the job's case uses, not a third copy of the arithmetic),
      and the refusals -
      `a_zero_or_negative_duration_is_refused`, `a_shorthand_with_no_digits_is_refused`,
      `an_instant_in_the_future_is_refused`, each as `unreadable-argument` naming `--since` and
      **never quoting the token typed**, because an operator's terminal is pasted into tickets - plus
      **user story 3 scenario 4**, `an_option_the_command_does_not_accept_is_refused_with_the_usage_line`:
      an unsupported option exits with the refusal code and prints the usage line, exactly as the
      five existing commands do, and nothing is read and nothing is written.
      Then the output: `one_bounded_line_per_exception_oldest_first`,
      `every_line_uses_the_event_field_names` (`request_id`, `batch_id`, `notification_id`,
      `court_centre_id`, `register_date`, `age_seconds` - a key that differs between the table and
      the index is a key somebody greps for and does not find),
      `the_counts_line_carries_the_five_counts_and_the_window`,
      `an_empty_window_prints_one_line_saying_so`,
      `no_line_carries_a_recipient_address_at_all` - not masked, **absent**: no read this feature
      makes selects `email_address`, so a `NOTIFICATION_FAILED` line names its notification id, its
      batch and its response code and there is nothing to mask,
      `the_command_opens_its_own_run_correlation_and_passes_the_id_into_build` (FR-011: an on-demand
      report's lines and events carry a run id exactly as the 07:00 run's do),
      `the_last_line_is_the_runs_own_and_is_written_after_every_sink_has_returned` - the equivalent
      of the job's `exception_report_run` line, with `run_id`, the window, `entries`,
      `delivered_log`, `delivered_email` and `outcome`,
      `delivered_email_is_skipped_without_the_flag_and_disabled_where_the_output_is_off` ("nobody
      asked" and "nobody could" are different operational facts),
      `without_email_only_the_log_sink_is_delivered_to` and
      `with_email_the_email_sink_is_added` (the command chooses the sinks it passes to
      `deliver(...)`; a command that e-mailed support on every invocation would make an incident's
      third run an incident of its own),
      `email_is_refused_as_declined_when_the_email_output_is_disabled` (exit code 1, the code the
      five existing commands use for a refusal, the line naming the setting, and **nothing
      written**), and `a_sink_that_failed_exits_could_not` - exit **2** when any sink the command
      asked failed, which is the same fact `outcome=partial` states on the last line, so a shell
      learns it without reading a line (the report reached one of its two audiences; nothing is
      retried, because the next run regenerates it in full).
      Seams: `batch/cli/ReportExceptionsCli` implementing the command interface with a `run`
      throwing, and **`batch/RunCorrelation` promoted from package-private to `public`** - the class
      itself and `under(...)` / `current()` only, so `batch/cli` can open a run of its own. No other
      member changes and no behaviour changes; the existing
      `RegisterGenerationJobTest` MDC case (`the_run_id_should_not_outlive_the_run`) still pins the
      clear-on-exit contract and must stay green.
      Red: `--since 2h` is not parsed and the window is the since-the-previous-run one.
      (done at `0c324b4`. 36 cases in three groups - the window, the refusals, the table - and the
      red is all 36: *"java.lang.UnsupportedOperationException: the sixth command is not implemented
      yet at ReportExceptionsCli.run(ReportExceptionsCli.java:63)"*, so `--since 2h` is not parsed,
      the window is nobody's and nothing is delivered. Seams in this commit: `ReportExceptionsCli`
      with a `run` that throws, **`CliMain.REPORT_EXCEPTIONS`** - `USAGE` is written from it, so it
      has to exist for the suite to compile, and T053 asserts what is done with it - and
      `batch/RunCorrelation` promoted to `public` for the class, `under(...)` and `current()` only.
      **It was package-private before this commit**, contrary to the note that it might already be
      public. `RegisterGenerationJobTest.the_run_id_should_not_outlive_the_run` is untouched and
      green. Two cases beyond the task's list:
      `a_report_that_could_not_be_built_should_be_reported_as_a_failure`, because every other
      command catches a collaborator's refusal and reports it on exit 2, and a throw out of this one
      would have left the JVM on its own 1 - the code a runbook reads as "declined"; and
      `asking_what_the_command_takes_should_print_the_usage_and_read_nothing`, because `--help` is
      an argument every command takes and none declares, and a sixth command that answered it by
      reading a store would be the one command an operator cannot ask what it takes.)
- [x] T053 [P] [US3] `batch/cli/CliMainTest` (extend) - `report_exceptions_is_in_commands`,
      `report_exceptions_is_in_the_registry`, and `the_usage_line_lists_six_names`. Red: `COMMANDS`
      holds five.
      (done at `69bdeb4`. Red: 38 cases, 4 failed -
      `report_exceptions_should_be_in_commands` *"Expecting ListN: ["generate-register",
      "notify-register", "list-batches", "supersede-before", "check-flag"] to contain:
      ["report-exceptions"]"*, plus `report_exceptions_should_be_in_the_registry`,
      `the_usage_line_should_list_six_names` and the renamed
      `the_registry_should_carry_exactly_the_six_names_operations_has`. The usage case states the
      six literals rather than deriving them from `COMMANDS`, because a list compared against itself
      agrees with itself however many names it holds. The two existing five-name cases are renamed
      rather than left saying five while asserting six.)
- [x] T054 [P] [US3] `config/TelemetryPrivacyTest` (extend) - the operator-token group gains
      `report-exceptions`: every value the command reads, got wrong, is asserted never to reach a
      line, a label or an exception message. Red: the group has no case for the sixth command's
      `--since`.
      (done at `af453a5`. The group's doubled registry gains the sixth command and
      `invocationsAnOperatorGetsWrong` gains `report-exceptions' window` -
      `--since <OPERATOR_TOKEN>`. Red: 37 cases, 1 failed, *"java.lang.UnsupportedOperationException:
      the sixth command is not implemented yet"*. The doubles behind the command are unreachable by
      construction, which T057 then had to make true: the sink lookup was moved below the window so
      that a refusal really does touch nothing - see its note.)
- [x] T055 [P] [US3] `e2e/CliDispatchIT` (extend) - `report_exceptions_dispatches_out_of_the_image`
      (`startup.sh report-exceptions --help` exits 0 inside the built image) and
      `a_mistyped_command_name_lists_six`. This is a **red** test, not a characterisation: it runs
      before `docker/startup.sh` is changed, precisely because the `case` pattern and the
      `CLI_COMMANDS` string are two halves of one list and a task that edits one and forgets the
      other looks exactly like a task that edited both. Red: the built image exits non-zero on an
      unknown command name.
      (done at `1fe4ff8`. Run against the image built from the repo's own Dockerfile over the
      packaged jar, Docker present. Red: 6 cases, 2 failed - `report-exceptions --help` *"expected: 0
      but was: 2"*, the script having no arm for the name, and the mistyped-name case *"Expecting
      actual: "usage: startup.sh <command> [arguments]\n  generate-register\n  notify-register\n
      list-batches\n  supersede-before\n  check-flag\n" to contain: [... "report-exceptions"]"*.
      `a_mistyped_command_name_lists_six` landed as the sixth name on the existing mistyped-name
      case rather than as a second case: it is the same exec against the same container, and
      asserting one list twice is the duplication that case's own reason warns about.)

### Implementation

- [x] T056 [US3] `batch/cli/Args` - the `SINCE` option and the `EMAIL` flag, added to `NAMES`, with
      `permits` keeping both off the other five commands. A message repeats a name only where `NAMES`
      owns it. Green: T051.
      (done at `2197858`. `./gradlew test --tests '...batch.cli.ArgsTest'` BUILD SUCCESSFUL, 36
      tests, 0 failed. `permits` is unchanged, which is why the task's stated red was never
      reachable - see T051.)
- [x] T057 [US3] `batch/cli/ReportExceptionsCli` - the sixth command: open `RunCorrelation.under(...)`
      for the invocation, resolve the window from `--since` per data-model.md's table (`to` is always
      now, read from the injected `Clock`; absent, the previous scheduled run), call
      `ExceptionReportService.build(window, RunCorrelation.current())` and then
      `deliver(report, sinks)` with the **log sink always and the e-mail sink only where `--email`
      was given and accepted**, write one bounded line per exception oldest first using the event
      field names, then the counts line, then - **always, and after every sink has returned** - the
      run's own last line with `run_id`, the window, `entries`, `delivered_log`, `delivered_email`
      and `outcome`. `--email` is refused with `declined` (exit 1) when
      `yotresultsdistribution.report.email.enabled` is false; an unsupported option is refused the same way
      with the usage line; **exit 2** when any sink the command asked failed. There is **no**
      `--ignore-flag` and the `YotResultsDistributionService` flag is not read at all, because the report is
      not on the lever's circuit. No line carries a recipient address, because no read selects one.
      In the same commit, `batch/RunCorrelation` becomes `public` for the class and for
      `under(...)` / `current()`, and `ReportExceptionsCli` is added to
      `support/GenerationLegs.THE_LEGS` and driven. Green: T052, T054.
      (done at `d8911d5`, with its analysis findings closed at `bee1ad1`.
      `./gradlew test --tests '...ReportExceptionsCliTest' --tests '...TelemetryPrivacyTest'` BUILD
      SUCCESSFUL, 73 tests, 0 failed. `RunCorrelation`'s widening landed with T052's seam, where the
      task listed it; nothing else about that class changed. `ReportExceptionsCli` joins
      `THE_REPORT`, so the per-class reach assertion covers it, and it is driven straight after
      `theMorningRun()` leaves the request log refusing - the class declares exactly one statement,
      the ERROR about a report an operator could not be given, which is what that arrangement
      reaches. Three decisions worth naming:
      **(1) `--email` before Phase 7's sink exists.** With the output enabled and no `EMAIL` sink on
      the context the flag is declined (exit 1) under `email-output-not-wired`, beside
      `email-output-disabled` for the output being off; both name
      `yotresultsdistribution.report.email.enabled`. T065 makes the second branch unreachable on any
      deployment that enables the output.
      **(2) Nothing outside the class is touched until every argument has been read** - not even a
      sink's own name. The first shape put the sink lookup above the window, and T054's
      `verifyNoInteractions` caught it: a refusal that had spoken to a collaborator is a refusal that
      changed something.
      **(3) The three-state outcome fold is written here as well as in `ExceptionReportJob`.** The
      job's is private to a class that also counts the outcome on a meter and writes it to the log,
      and a command's JVM has neither a registry to increment nor that line to write.
      `duration_ms` is on the last line, as quickstart.md's own sample of this command's output has
      it, so the line is the job's equivalent rather than a subset of it.)
- [x] T058 [US3] `batch/cli/CliMain` - `REPORT_EXCEPTIONS` added to `COMMANDS` and to `registryOf`.
      Green: T053.
      (done at `854a2f2`. The command asks for its sinks through a bean provider rather than by
      type, because two of them ship and `getBean` over an interface with two implementations
      refuses rather than choosing; which of the two it delivers to is the command's decision and
      not the registry's. The help group's own doubled registry grows with the list in the same
      commit, which is what its first assertion exists to force. 221 tests over `batch.cli.*`, 1
      failed, and that one is `the_script_that_dispatches_should_recognise_the_same_six_and_no_others` -
      the half of the list T059 closes, and the reason T055 was written red before it.)
- [x] T059 [US3] `docker/startup.sh` - `report-exceptions` added in **both** places: the `case`
      pattern at line 36 and the `CLI_COMMANDS` string at line 27. The two lists are one list, and
      `CliDispatchIT` asks the built image for both halves. Green: T055.
      (done at `36868f1`. `./gradlew test --tests '...CliMainTest' --tests '...CliDispatchIT'` BUILD
      SUCCESSFUL, 44 tests, 0 failed: the built image exits 0 on `startup.sh report-exceptions
      --help`, lists all six under a mistyped name, and the script-list case that went red at T058 is
      green.)
- [x] T060 Phase close: `./gradlew build` green; **review gate 6** (the refusal codes and exit codes
      against the five existing commands, the operator-token rule, no token ever quoted back, and
      that `RunCorrelation`'s widened visibility changed nothing else about it); findings land as
      red/green pairs.
      (build half done at `bee1ad1`: `./gradlew build` BUILD SUCCESSFUL, 3510 tests, 0 failed,
      0 skipped, with `pmdMain`, `pmdTest`, `checkstyleMain`, `checkstyleTest` and the JaCoCo gate
      all green - the four analysis findings the phase brought were closed at that commit.
      **Review gate 6 ran with three read-only reviewers**, on the committed tree and its test
      results. Verdicts: `code-reviewer` **PASS** (0 high, 3 medium, 2 low), `spec-validator`
      **COMPLIANT** (4 low), `qa` **PASS** with named gaps rather than a failing suite. The four
      things the gate was called for were found clean: the sixth command's refusal codes and exit
      codes are the five existing commands' - `REFUSED` for an argument it will not read and for a
      declined `--email`, `FAILED` for a sink that refused - nothing an operator typed reaches a
      line, a log message or a metric label, the bounded reasons are the same vocabulary, and
      `RunCorrelation` gained visibility and nothing else: `under(...)` and `current()` are
      unchanged and `RegisterGenerationJobTest.the_run_id_should_not_outlive_the_run` is untouched
      and green.
      Findings, and where each was closed. The reds are at `2505125` and the greens at `3f8d502`
      unless another commit is named; `d6b9fcf` renames the two new factories for PMD and
      `b749460` closes the fixture finding:
      * **the default window was computed inside the `--since` guard** (MEDIUM). A window nobody
        typed that could not be computed came back as exit 1 `unreadable-argument` with the usage
        line under it, sending an operator to look for an argument they never gave. The two windows
        are two branches with a catch each now, and a default that cannot be computed is exit 2
        `report-not-built` - the same bounded reason a read that would not answer carries, because
        to whoever is reading the terminal they are the same fact: there is no report. Red
        `a_default_window_that_cannot_be_computed_is_the_reports_failure_not_the_operators`
        (*expected: 2 but was: 1*, over a terminal reading `reason=unreadable-argument`).
      * **the three-state fold and the `delivered_email` word were copied into both callers, and
        the copies had already diverged** (MEDIUM, and QA named the same shape). The job called an
        e-mail sink that is not on the context `disabled`; the command called the same absent sink
        `skipped`, which is its word for an output that is here and an invocation that did not want
        it. `ReportRunOutcome.from` and a new `domain/DeliveryWord` are the fold and the word, said
        once and used by both, and both callers now read presence off the sinks the context
        contributed rather than off the settings. Reds: the domain's own cases in
        `ExceptionReportModelTest.TheRunsOwnWords`, recorded as the seams' refusal rather than
        thrown (*"Expecting code not to raise a throwable but caught
        java.lang.UnsupportedOperationException: the run's own three-state fold lands next"*) with
        the answer each case is about behind it (*expected: PARTIAL but was: null*); and, over the
        command, `an_email_sink_that_is_not_here_should_be_disabled_in_the_words_the_job_uses`
        (*"...delivered_email=skipped ... to contain: delivered_email=disabled"*) and
        `a_context_with_no_log_sink_should_exit_could_not`
        (*"...delivered_log=failed ... to contain: delivered_log=disabled"*), which is the same
        divergence about the other field.
      * **`duration_ms` was read off `System.nanoTime()`** (MEDIUM). The command holds an injected
        clock, the job measures its own run on one, and a field measured off a second, un-injected
        clock is a field no case can state a value for - which is why the only assertion about it
        was that the key was present. It is measured between the invocation opening its correlation
        and the last line now, off the clock it was handed, and one reading of now serves the
        window's end and the duration's start. Red
        `the_last_line_should_carry_duration_ms_from_the_clock` (*"...outcome=delivered
        duration_ms=0 to contain: duration_ms=250"*) under a clock the case advances inside
        `deliver`.
      * **the `email-output-not-wired` branch named a setting that is correct** (LOW), and its
        constant still described itself as waiting for Phase 7 (spec-validator, LOW). Phase 7's
        `ReportEmailConfig` declares the e-mail sink on exactly the condition this command reads, so
        the branch is unreachable on a deployed context. **It is kept, deliberately**: nothing in
        the command's own type says so - the sinks are handed in - and the alternative to declining
        is running log-only while an operator believes support was e-mailed. What changed is the
        line, which names `sink=email` rather than a setting that is on and right. Red
        `email_asked_where_the_output_is_on_and_no_sink_is_wired_should_name_the_sink`
        (*"...reason=email-output-not-wired setting=yotresultsdistribution.report.email.enabled to contain:
        sink=email"*).
      * **`GenerationLegs` kept the length of every line the on-demand report wrote and read none
        of them back** (LOW). A write-only field is a field a later reader has to work out the
        purpose of before they can be sure deleting it is safe. The consumer refuses a null instead,
        which is the one thing that fixture has an opinion on about those lines. Closed at
        `b749460`.
      * **the ordering claim was a comment, not an assertion** (QA). The command's case for "the
        last line is written after every sink has returned" read the last line and nothing about
        when it was written. The sink now reads the printed lines at the moment it is asked to
        deliver, exactly as `ExceptionReportJobTest`'s equivalent reads the captured log, and the
        run line is not among them. **Green on introduction**, and said so here rather than left to
        look like a red: the ordering was already right. The job's own case was found equally
        strong already and was not touched.
      * **five refusals and one quiet window were asserted on one line of what they print** (QA).
        The e-mail decline was asserted on `printed.getFirst()` and the empty window on one line of
        three; both are asserted over every line they write now - the decline is the refusal and the
        usage line and nothing else, and a quiet window is the saying-so line, the five zeroes with
        their window, and the run's own line. The future instant joins
        `the_refusal_should_name_since_and_never_quote_the_token`, the boundary an instant exactly
        now falls on is pinned as the refusal the code makes it
        (`an_instant_exactly_now_should_be_refused`), and `--since` and `--email` given twice are
        pinned as refusals that name the argument in `ArgsTest`
        (`since_and_email_given_twice_should_be_refused_by_name`). All **green on introduction**.
      * **the documentation had drifted from the code in four places** (spec-validator, LOW).
        spec.md's US3 scenario 1 said the output "ends with the five counts", which the counts line
        is not - the run's own line is last, and that line is the one a runbook step reads;
        data-model.md's paragraph about the command's last line did not say that `duration_ms` is
        measured from the correlation being opened, nor that the fold and the word behind the other
        fields are shared with the job; data-model.md and quickstart.md both glossed
        `delivered_email=disabled` as the **setting** being false, which is its cause and not what
        either caller reads - both read presence off the sinks the context contributed, and saying
        otherwise is what let the two copies diverge in the first place; and T052's tick narrative
        said one case landed beyond the task's list when two did - `--help` is the second. All four
        corrected at `d6b9fcf`, which carries the documentation this gate touched as well as the
        rename, and the command's own class javadoc, which described neither new answer.
      Nothing in this gate changed an outbound contract, a bounded reason an operator's tooling
      already greps, or the five existing commands. `email-output-disabled` still names its setting,
      the exit codes are unchanged, and `delivered_email=disabled` is the word both callers now use
      for the context the MVP deploys.
      Phase close, the second half: `./gradlew build -Dtest.noFailFast=true` BUILD SUCCESSFUL,
      exit 0, 3570 tests over 573 suites, 0 failures, 0 errors, 0 skipped; Checkstyle at
      `maxWarnings = 0` over main and test, PMD over both, and the JaCoCo gate at LINE 0.88 /
      BRANCH 0.85, none of them loosened. The run before it was green over the whole suite and red
      on PMD alone - `ShortMethodName` on both new factories and `ConfusingTernary` on the word's
      two negated branches - closed at `d6b9fcf` as names and conditions, with no branch, value or
      line changed.
      **The finding gate 5 deferred is still deferred**: several shipped javadoc comments cite
      `.claude/rules/design_rules.md` by path, `ExceptionReportJob` among them, and the sweep that
      respells them belongs with Phase 8's documentation sync. No code changed for it here.)

**Checkpoint**: US3 is independently demonstrable through quickstart.md's `docker compose exec app
/startup.sh report-exceptions --since 2h`.

---

## Phase 7: User Story 4 - support receives the report by e-mail with the detail attached (Priority: P3)

**Goal**: the second sink - the exception list as a CSV in the framework file service, attached by
id to one `send-email-notification` per configured recipient, through a mailer port of the report's
own.

**Independent Test**: with the e-mail output enabled and WireMock standing in for notificationnotify,
trigger a run with seeded exceptions; confirm one send per recipient, each accepted, each referencing
an attachment whose content is the CSV of the same exceptions, and that the attachment carries
identifiers only.

**⚠️ Gating.** The **implementation is not gated** and lands here in full. **Deployment with
`yotresultsdistribution.report.email.enabled=true` is gated** on the Notify template, which the
notificationnotify team owns. Until that template exists the flag stays false in every environment,
stories 1, 2, 3 and 5 ship complete, and `--email` is refused with a bounded reason rather than
silently doing nothing. Do not enable the e-mail output in any deployed environment as part of this
phase.

**Depends on Phase 4's report and on Phase 2's `PayloadFileStore.storeText` and `ReportMailer`
seams.**

### Tests first ⚠️

- [x] T061 [P] [US4] `adapter/fileservice/FileServicePayloadStoreIT` (extend) -
      `store_text_issues_exactly_two_statements_content_before_metadata` (and no third; the order is
      forced, because `metadata.file_id` is a foreign key onto `content.file_id`),
      `store_text_writes_the_utf_8_bytes_of_the_string_into_the_bytea_column`, and
      `store_text_writes_the_five_metadata_keys` spelled the way the framework spells them.
      **This suite owns the port method's two-statement pin** - it is the suite that owns
      `FileServicePayloadStore`, and `storeText` is that class's method; what the CSV itself looks
      like is T062's, so neither suite asserts the other's claim. Red:
      `UnsupportedOperationException` from T020's seam, replaced by a failing assertion on the
      statement count.
      (red at `b0d7853`: `./gradlew test --tests '*FileServicePayloadStoreIT*'
      -Dtest.noFailFast=true`, 10 tests, 4 failed, 0 errors. Three fail through the suite's own
      `storeCsv` helper, which says "the write is expected to happen" as an assertion rather than
      letting the seam's refusal out as an error - *"Expecting code not to raise a throwable but
      caught java.lang.UnsupportedOperationException: the file service's text write is not written
      yet"* - and the fourth on the type of what an unreachable store threw. The six payload cases
      are untouched and green.
      **A fourth case beyond the task's three**, and worth saying why: the unreachable-store case.
      `storeText` promises the same `PayloadStoreUnavailableException` `store` does, the sink's
      whole ATTACHMENT_STORE_UNAVAILABLE branch is built on it, and nothing else asserted that the
      new method translates rather than letting the driver's own type out.
      The fixture carries a comma inside a quoted field and a multi-byte character, so the byte
      round trip says something about encoding rather than only about length. Checkstyle's
      `AvoidEscapedUnicodeCharacters` wants the escape on the line its tail comment is on, which is
      why the literal is split where it is.)
- [x] T062 [P] [US4] `adapter/report/EmailReportSinkStoreIT` - the sink's own composition against the
      file-service Testcontainers fixture seeded from
      `specs/002-consolidate-progression-leg/contracts/fileservice/`:
      `the_csv_has_one_header_row_and_one_row_per_entry_in_the_entries_own_order`,
      `the_csv_columns_are_the_union_of_the_entry_table_with_an_empty_field_where_the_kind_does_not_carry_it`
      (thirteen, `kind` included),
      `the_csv_is_utf_8_with_newline_endings_and_rfc_4180_quoting`,
      `the_metadata_names_the_file_court_register_exceptions_dated_in_europe_london`
      (`conversionFormat=csv`, `templateName=yot-results-distribution-exception-report`, `numberOfPages=1`,
      `fileSize` the byte count), `a_batch_failed_row_fills_the_existing_columns_and_adds_none`
      (batch id, court centre, register date, status, the bounded reason, age - the fifth kind is a
      row shape the thirteen columns already carry), and
      `the_csv_carries_no_address_no_defendant_data_and_no_generator_text`.
      **This suite owns the CSV's composition; the two-statement pin over `storeText` belongs to
      T061.** Red: the stored content is empty.
      (red at `51d699b`: `./gradlew test --tests '*EmailReportSinkStoreIT*' -Dtest.noFailFast=true`,
      6 tests, 6 failed, 0 errors, every one the same assertion through the suite's `deliver`
      helper - *"Expecting code not to raise a throwable but caught
      java.lang.UnsupportedOperationException: the report's e-mail is not written yet"*.
      **The task's predicted red is not reachable**: an empty stored CSV needs a seam that already
      does the storing, which is the body this task is written before. The seam is the class, its
      two ports, its two settings and a `deliver` that throws.
      Two decisions this task did not name. The file id is read **off the mail** rather than
      guessed, so the case that reads a row back is also the case that says the id on the mail is
      the id that was stored. And the snapshot is half past midnight in London, half past eleven
      the evening before in UTC, so a file name taken off the wrong clock fails rather than passes
      for five months of the year.
      It carries a PMD suppression for the seam's unread fields, closed at T067.)
- [x] T063 [P] [US4] `adapter/report/EmailReportSinkTest` - a **unit** suite, because the sink holds
      the `ReportMailer` port and no HTTP type:
      `the_csv_is_stored_before_any_send_and_its_file_id_is_the_one_on_every_mail` (ids before calls:
      the `fileId` is minted and written into the run's log line **before** the file-service write,
      so an attachment under an id nothing recorded is impossible),
      `one_mail_per_recipient`,
      `the_personalisation_carries_the_five_counts_and_the_window_as_strings`,
      `no_identifier_and_no_address_is_ever_a_personalisation_key_or_value` (the detail travels as
      the CSV, by file id),
      `one_refused_recipient_does_not_stop_the_other_two` (scenario 4.2, answering
      `PARTIALLY_DELIVERED` with accepted 2 and refused 1),
      `an_empty_report_is_still_sent` (FR-012, scenario 4.4: silence must never be the signal),
      `an_empty_resolved_recipient_list_at_run_time_is_no_recipients`,
      `a_store_failure_is_attachment_store_unavailable_and_no_mail_is_sent`, and
      `every_address_is_masked_in_every_line_the_sink_writes`.
      Seam: `adapter/report/EmailReportSink` implementing `ExceptionReportSink` with `deliver`
      throwing. Red: nothing reaches the `ReportMailer` mock.
      (red at `4f3a73a`: `./gradlew test --tests '*EmailReportSinkTest*' -Dtest.noFailFast=true`,
      9 tests, 9 failed, 0 errors. Nothing reaches the mailer mock, exactly as predicted, and every
      case says so as an assertion about the port's own contract rather than as an error: *"a sink
      answers how it went rather than throwing ... Expecting code not to raise a throwable but
      caught java.lang.UnsupportedOperationException"*.
      The seam landed one commit early, with T062, which needs the class to compile - the same
      shape T041's seam took with T038.
      The ids-before-calls case reads the log **at the moment the store is called**, through an
      answer on the mock, because "before" is an ordering claim and a capture read afterwards
      cannot make it.)
- [x] T064 [P] [US4] `adapter/notificationnotify/NotificationNotifyReportMailerTest` (WireMock,
      `dynamicPort()`) - the report's own send, against the **002-vendored** schema:
      `the_report_body_validates_against_the_vendored_schema`, validated against
      `specs/002-consolidate-progression-leg/contracts/notificationnotify/notificationnotify.email.json`
      (required `templateId` and `sendToAddress`, optional `fileId` and `personalisation`,
      top-level `additionalProperties: false`, **no `notificationId` in the body** - it is the path
      parameter);
      `arbitrary_personalisation_keys_are_contract_legal` - the schema's `personalisation` is
      `"type": "object"` with an empty `properties` block and **`"additionalProperties": true`**,
      which is what lets the counts and the window travel in the body at all (FR-006, scenario 4.4);
      `the_personalisation_values_are_strings` (Notify substitutes text);
      `one_post_per_mail`, `the_media_type_is_application_vnd_notificationnotify_email_json`,
      `the_cjscppuid_identity_header_is_sent`,
      `two_hundred_and_two_is_the_only_success`, `a_4xx_is_refused_and_never_retried`,
      `a_408_429_or_5xx_is_transient`, and
      `the_register_paths_body_is_unchanged` - `NotificationNotifyClientTest`'s existing cases run
      over the shared request builder and stay green, so the `personalisation.yotsName` body the
      register leg posts is byte-for-byte what it was.
      Nothing is re-vendored: the 002 copy is the single copy, as `contracts/README.md` states.
      Seam: `adapter/notificationnotify/NotificationNotifyReportMailer` implementing `ReportMailer`
      with `send` throwing. Red: no request reaches WireMock.
      (red at `473fc09`: `./gradlew test --tests '*NotificationNotifyReportMailerTest*'
      -Dtest.noFailFast=true`, 17 tests, 15 failed, 0 errors. No request reaches WireMock, as
      predicted, and each failure is an assertion about the port's contract - *"the port answers
      how the send went rather than throwing ... Expecting code not to raise a throwable but caught
      java.lang.UnsupportedOperationException"*.
      **Two are green on introduction and say so here.**
      `arbitrary_personalisation_keys_are_contract_legal` reads the vendored file itself and finds
      what research §4 said it would: `personalisation` is `"type": "object"` with an empty
      `properties` block and `"additionalProperties": true`, inside a body that is
      `"additionalProperties": false`. **Verified, not assumed** - it is the whole reason the counts
      may travel in a body at all. And `the_register_paths_body_is_unchanged` passes before the
      shared builder exists, which is the point of writing it here: it is the before of a
      before-and-after over a refactor of somebody else's path.
      The vendored-schema fixture is a second copy of `NotificationNotifyClientTest`'s on purpose.
      That suite is the one T066 must leave untouched, and reaching into it to share a helper is
      exactly the edit that would stop its green run meaning anything.)

### Implementation

- [x] T065 [US4] `adapter/fileservice/FileServicePayloadStore.storeText` - the same two inserts as
      `store`, character for character, in the same order: content first, then metadata; the CSV's
      UTF-8 bytes into the `bytea` column; the five metadata keys. The write-only, changeset-pinned
      framework contract is used exactly as 002 uses it and is not migrated or read through.
      Green: T061.
      (green at `f81802a`: `./gradlew test --tests '*FileServicePayloadStoreIT*'
      -Dtest.noFailFast=true` BUILD SUCCESSFUL, exit 0, 10 tests, 0 failures, 0 errors - T061's
      four and the six payload cases, which are untouched. `checkstyleMain` and `pmdMain` exit 0.
      Both callers now issue the two inserts through one private `write`, because a second copy of
      that order is a second thing to get wrong about a table this service does not own.
      **`StubPayloadFileStore.storeText` becomes the logging no-op its own javadoc said it must
      become when the sink landed**, in this commit rather than a later one: the sink exists now, a
      context on the stub profile has to be able to compose a report e-mail end to end, and a stub
      that refused would make the one path such a run could exercise the path where it does
      nothing. The line says plainly that nothing was written and under which id, in the shape
      `store`'s line is written in, and the CSV itself is never logged.)
- [x] T066 [US4] `adapter/notificationnotify/NotificationNotifyReportMailer` and the shared request
      builder - a **package-private** builder in `adapter/notificationnotify/` that composes the URI
      (`COMMAND_PATH` with the notification id as the path parameter), the
      `application/vnd.notificationnotify.email+json` media type, the `CJSCPPUID` identity header,
      the 202-and-nothing-else success rule and the refused/transient classification **once**, with
      `NotificationNotifyClient` refactored to call it and the new mailer calling it too. That is
      what makes "the same shape" a fact rather than a claim. The mailer serialises `ReportMail` as
      `templateId`, `sendToAddress`, `fileId` and the `personalisation` map, and answers a
      `MailOutcome`. **`RegisterNotifier`, `RegisterNotification` and the `yotsName` body are
      untouched**, and `NotificationNotifyClientTest` stays green unchanged. Add
      `NotificationNotifyReportMailer` to `support/GenerationLegs.THE_LEGS` and drive it in the same
      commit - it is the one class besides the sink that holds an address. Green: T064.
      (green at `0f19400`: `./gradlew test --tests '*NotificationNotifyReportMailerTest*' --tests
      '*NotificationNotifyClientTest*' --tests '*TelemetryPrivacyTest*' -Dtest.noFailFast=true`
      BUILD SUCCESSFUL, exit 0, 95 tests, 0 failures, 0 errors. `checkstyleMain`, `checkstyleTest`,
      `pmdMain` and `pmdTest` exit 0.
      The builder is `NotificationNotifyCommand`: the path, the media type, the identity header and
      a three-word taxonomy - taken, refused, retryable - over the shared `RetryPolicy`. It
      composes the request and **hands the response back unread**, because the two callers settle
      differently: one throws a classified failure at a run holding a retry budget, the other
      answers an outcome to a sink that carries on to the next recipient. A helper that decided for
      them would have to know which of the two it was serving.
      `NotificationNotifyClient`'s four public constants now name the builder's rather than
      spelling them again; its body, its three lines and its two exceptions are untouched, and
      `NotificationNotifyClientTest` is **not edited** and stays green - which is what makes
      `the_register_paths_body_is_unchanged`, green before the refactor, worth having.
      One analysis finding closed in the same commit: `OnlyOneReturn` over the mailer's `send`,
      which now assigns one non-final local, because "answers rather than throws" is what that
      costs on this path.
      `THE_LEGS` gains the mailer with three arrangements - a refusal, a retryable answer and an
      answer that never came - over a **marked** address, so the sweep's claim covers the second
      class in this increment that holds one.)
- [x] T067 [US4] `adapter/report/EmailReportSink` - render the CSV from the report's entries, mint
      the `fileId` and write it into the run's line, store the text through `PayloadFileStore`, then
      one `ReportMailer.send` per configured recipient, folding the per-recipient `MailOutcome`s into
      one `DeliveryOutcome` with its accepted and refused counts and a bounded
      `ReportDeliveryReason`. The personalisation map carries the five counts and the window as
      strings and nothing else. Every line masks its addresses; no address reaches an event, a label
      or the CSV. Add `EmailReportSink` to `support/GenerationLegs.THE_LEGS` and drive it in the same
      commit. Green: T062, T063.
      (green at `49c9cbe`: `./gradlew test --tests '*EmailReportSink*' --tests
      '*TelemetryPrivacyTest*' -Dtest.noFailFast=true` BUILD SUCCESSFUL, exit 0, 52 tests, 0
      failures, 0 errors - T063's nine, T062's six and the privacy sweep's thirty-seven over the
      widened leg. `checkstyleMain`, `checkstyleTest`, `pmdMain` and `pmdTest` exit 0.
      Three decisions this task did not name.
      **RFC 4180 quoting is applied to every field** rather than to the one that can need it. Only
      the producing context's name is text another system chose, and a rule applied to whichever
      field looked dangerous today is a rule the next column added is outside of.
      **An empty recipient list is answered before anything outside the class is touched** - no id
      minted, no CSV rendered, no write - because NO_RECIPIENTS is a configuration emptied under a
      running pod, and storing a file nobody will be told about would leave a row in somebody
      else's database for nothing.
      **The delivery's reason is the first refusal's**, and a later refusal of another kind is
      counted and named on its own line: one reason on one outcome cannot describe three different
      answers, and the one that came first is the one to act on.
      Two analysis findings closed here: `OnlyOneReturn` over the store-and-render step,
      restructured to one exit, and `AvoidInstantiatingObjectsInLoops` over the per-recipient
      `ReportMail`, suppressed with its reason - one mail per recipient is the contract, and one
      object reused would be one e-mail addressed to everybody.
      `GenerationLegs.THE_REPORT` becomes **four** classes and drives all five of the sink's lines.
      `TelemetryPrivacyTest`'s bounded-reason vocabulary gains `ReportDeliveryReason`, for the
      reason `BatchFailureReason` is already in it: the sink writes the constant into a `reason=`
      slot an alert keys on, and the sweep refused it as free text until the vocabulary said
      otherwise.)
- [x] T068 [US4] Wiring in `config/` - the `EmailReportSink` and `NotificationNotifyReportMailer`
      beans contributed only when `yotresultsdistribution.report.email.enabled` is true, so a context with the
      output off holds one sink and the job's run line reads `delivered_email=disabled`; plus the
      three e-mail variables on the `app` service in `docker-compose.yml`
      (`YOTRESULTSDISTRIBUTION_REPORT_EMAIL_ENABLED`, `CR_REPORT_TEMPLATE_ID`,
      `YOTRESULTSDISTRIBUTION_REPORT_RECIPIENTS`) exactly as quickstart.md writes them, with its comment:
      local-only dummies, never a real template id and never a real address, because in a deployed
      environment both arrive from Key Vault through the CSI driver and neither is ever a chart
      value. Green: the sink-selection cases of T063.
      (done at `2a50b81`: `./gradlew test --tests '*CliModeConfigTest*' --tests
      '*ReportSchedulingConfigTest*' --tests '*PropertiesValidator*' --tests '*ContextLoad*'
      -Dtest.noFailFast=true` BUILD SUCCESSFUL, exit 0, 33 tests, 0 failures, 0 errors.
      `checkstyleMain` and `pmdMain` exit 0.
      **A new configuration class, `config/ReportEmailConfig`**, which the plan's Project Structure
      did not name. Its beans cannot go on `LiveNotificationConfig`, which is conditional on
      `yotresultsdistribution.generation.enabled`, because this output has to work on the pod FR-004
      describes; and an HTTP client does not belong in `ProcessedLogConfig`, which is about this
      service's own database. So the mailer builds its own `RestClient` over the same endpoint,
      identity and timeouts.
      **The condition is the enabled flag and nothing else**, as this task's own wording asks and
      for the reason it gives: `report-exceptions --email` is the on-demand half of this output, so
      a `NotCliMode` condition would make a command decline a flag the deployment says is on. It is
      not conditional on `yotresultsdistribution.report.enabled` either - the schedule and the command are
      two callers of one report, and the sink belongs to neither.
      **One gap, recorded rather than papered over.** There is no context case asserting "two sinks
      with the output on, one with it off": `ReportEmailConfig` needs `YotResultsDistributionProperties` and
      the shared mapper, which the wiring suite's runner does not carry, and standing them up there
      would be a second copy of an application context inside a suite whose subject is which beans
      a condition contributes. T070's end-to-end run boots the real context **with the e-mail
      output on** and reads the sends back, which is where this becomes a behaviour rather than a
      bean count. Review gate 7 should decide whether that is enough.
      **A second gap of the same kind**, and the sharper one: with `generation.enabled=false` and
      `fileservice-mode` at its LIVE default there is no `PayloadFileStore` bean and no
      file-service datasource - both are behind the generation switch - so a pod in FR-004's shape
      with the e-mail output **on** would fail to start. No deployed environment can reach that
      combination while the deployment gate holds the flag false, and the local stack runs the
      generation half, but it is the same relocation the repository beans needed at T044 and it is
      not done. Raised here for review gate 7 rather than fixed inside an implementation task.)
- [x] T069 Phase close: `./gradlew build` green; **review gate 7** (the two consumed contracts used
      and not redefined, the shared request builder leaving the register path byte-identical, ids
      before calls, 202 and nothing else, no address anywhere, the deployment gate above restated in
      the PR narrative); findings land as red/green pairs.
      (build half done with this phase's tick: `./gradlew build -Dtest.noFailFast=true` BUILD
      SUCCESSFUL, exit 0, **3552 tests over 572 suites, 0 failures, 0 errors, 0 skipped**, with
      `checkstyleMain`, `checkstyleTest`, `pmdMain`, `pmdTest` and the JaCoCo gate at LINE 0.88 /
      BRANCH 0.85 all green, none of them loosened.
      **Review gate 7 ran with three read-only reviewers**, on the committed tree and its test
      results, with the two gaps T068 recorded put in front of it first. Verdicts: `code-reviewer`
      **NEEDS CHANGES** (1 high, 1 low), `spec-validator` **DRIFT DETECTED** (1 medium, 1 low),
      `qa` **FAIL**. What the gate was called for was found clean: the two consumed contracts are
      used and not redefined - the body notificationnotify is sent is the four fields its own
      vendored schema declares and the file-service write is the same two inserts in the same order
      through the pinned changesets - the file id is minted and written into the run's line before
      the write that uses it, 202 and nothing else is an acceptance on both adapters, and no
      address reaches a line, a label or the CSV. The deployment gate is unchanged: the output ships
      switched off until notificationnotify provides the template.
      Findings, and where each was closed. The reds are at `9aa0d47` and the greens at `bd56721`;
      `f9f5ddb` carries the one thing the phase's own build caught after them, which is
      `StubGenerationAdaptersTest` following the bean that moved:
      * **T068 shipped `config/ReportEmailConfig` with no test of its own** (HIGH, and the same
        shape `qa` failed on). A new configuration - two beans, one condition and an HTTP client -
        landed under a task whose named green was T063's sink-selection cases, which are unit cases
        over the sink and assert nothing about which beans a context holds. **That is a TDD
        violation and it is recorded as one**, in the "Approved TDD exceptions" block above, as a
        second entry marked proposed and **not granted**: the block requires the exception to be
        written with the design owner's dated approval *before* the commit lands, and it was not.
        The cases that should have preceded it are `config/ReportEmailConfigTest`, four of them red.
      * **A pod with the generation half off and the e-mail output on could not start** (the same
        HIGH finding's substance, and T068's own sharper recorded gap). The `PayloadFileStore` beans
        and the file-service datasource were behind `yotresultsdistribution.generation.enabled`, and the
        e-mail output writes a CSV into the file service before anybody is told about it - so
        FR-004's deployment with the second output on held neither. They are behind
        `config/FileServiceNeeded` now, which is generation-**or**-e-mail, in a new
        `config/FileServiceConfig` beside the relocated datasource configuration; the validator's
        `yotresultsdistribution.fileservice.url` rule follows the same sentence and names the half that
        asked. Nothing generation-only moved. Reds:
        `a_pod_with_generation_off_and_email_on_starts_and_holds_two_sinks` (*"to have not failed:
        but context failed to start: ... NoSuchBeanDefinitionException: No qualifying bean of type
        'uk.gov.hmcts.cp.yotresultsdistribution.application.PayloadFileStore' available"*),
        `a_pod_with_email_on_holds_the_mailer_and_the_email_sink_as_singletons` (the same, through
        `emailReportSink` parameter 0), `the_generating_pods_store_is_unchanged` (*"Expected size: 1
        but was: 0 in: []"*) and `the_file_service_url_is_required_whenever_either_half_needs_it`
        (*"to have failed but context started successfully"*).
      * **`ReportEmailConfig`'s javadoc described where the store came from rather than why it is
        there** (LOW). It read "the file store it is handed is the same one the generation half
        writes payloads through", which was the sentence the defect was hiding behind: true of the
        bean and wrong about the condition. It names `FileServiceNeeded` now, and says that a file
        in the file service is what the two outward legs have in common.
      * **The plan's Project Structure named neither the configuration nor the relocation**
        (spec-validator, MEDIUM). `config/ReportEmailConfig` was a class the plan did not have, and
        the "either half" condition existed in no document at all - which is why the validator rule
        could name the generation half alone and read as correct. plan.md gains `ReportEmailConfig`,
        `FileServiceConfig`, `FileServiceNeeded` and the two CHANGED lines beside them; Complexity
        Tracking row 1 records that the file-service beans followed the same relocation the
        scheduling split made, for the same reason; research §4 and data-model.md both state the
        "either half" condition and the validator rule that matches it.
      * **"the shared request builder leaves the register path byte-identical" was asserted only
        over parsed bodies** (spec-validator, LOW). Every case in
        `NotificationNotifyClientTest` reads `MAPPER.readTree(...)`, which normalises away exactly
        what the claim is about - the order the four fields were written in - so the suite would
        have gone on passing through a reordering, and reordering somebody else's command body is a
        change to their contract whether or not their parser tolerates it.
        `the_body_is_byte_for_byte_what_the_register_leg_has_always_sent` pins the exact serialised
        string against a literal. **Green on introduction**, and said so here rather than left to
        look like a red. Byte-identity *across* the refactor cannot be re-observed now that it has
        landed; what evidences it is that suite being untouched by T066 and green, and this case
        holding the same bytes from here on.
      * **The mailer's "one attempt, no loop" was a reading of the class rather than an
        assertion** (`qa`). `a_transient_status_is_one_request_and_no_retry` counts the requests for
        408, 429 and 503 - the three statuses a retry would hide behind - and
        `two_hundred_and_one_and_two_hundred_and_four_are_refused` covers the other two 2xx a proxy
        answers with, beside the 200 already pinned. Both **green on introduction**: there is no
        loop, and there never was one.
      * **Two of the fold's four refusal arms were unasserted, and the failure line was unread**
        (`qa`). `SEND_REFUSED` was the only reason any case named, so `SEND_FAILED` and
        `SEND_UNANSWERED` were reachable only through the masking case, which reads log lines and
        not the outcome.
        `a_failed_and_an_unanswered_send_carry_their_own_reasons_and_the_first_refusal_wins` asserts
        both orders, because which reason the delivery carries is which came first and not which is
        worst; `the_file_id_is_on_the_warn_line_when_the_store_fails` asserts that the minted id
        survives the failure, since that is the case where somebody has to go and look for a file.
        Both **green on introduction**.
      * **The CSV suite's awkward field had no line break, and its size case was ASCII** (`qa`).
        A sink that left a break unquoted would make one exception read as two, and a `fileSize`
        taken off `String.length()` agrees with the byte count of an ASCII file by accident. The
        awkward value now carries the separator, the quote, a line break and a character outside
        ASCII in one string, the quoting case asserts the file is three physical lines, and the
        metadata case is driven from the same value. Both **green on introduction**.
      **One finding is recorded and not fixed**, deliberately:
      `yotresultsdistribution.endpoints.notificationnotify` is required by the validator only where the
      generation half is enabled, and `ReportEmailConfig` builds its `RestClient` over it - so a pod
      with the e-mail output on and that endpoint unset starts clean and fails every morning's send.
      It is the same class of defect as the one this gate fixed and it is **not** on this gate's
      brief, which enumerated the file-service settings; fixing it here would be an uncatalogued
      change made inside a remediation commit. It is raised for **review gate 8** at T077.
      **Closed before Phase 8 began**, as a red/green pair of its own: `5be9abb`
      `ConfigurationValidationTest.ReportRefusals
      .the_notificationnotify_endpoint_is_required_whenever_either_half_sends` - the report on, the
      e-mail output on, generation off, the endpoint blank - red on *"to have failed but context
      started successfully"*; `7892e5d`
      `PropertiesValidator.validateWhicheverHalfSendsCanReachNotificationnotify`, mirroring the
      file-service rule this gate landed and naming the half that asked. The endpoint and not the
      identity: `yotresultsdistribution.endpoints.system-user-id` is asked of neither half today, so a rule
      for it would be a new refusal rather than this defect's fix, and it is left named in the new
      method's javadoc for whichever gate catalogues it.
      Nothing in this gate changed an outbound contract, a bounded reason an operator's tooling
      already greps, a status code, or readiness on any deployment. The register leg's body, its
      suite and its vendored-schema assertions are untouched.
      Phase close, the second half: the whole-tree `./gradlew build -Dtest.noFailFast=true` run over
      the greens was **3582 tests, 1 failed** - `StubGenerationAdaptersTest`'s
      `all_four_modes_together_should_contribute_all_four_stubs`, asserting a `PayloadFileStore` the
      configuration it imports no longer declares (*"to have a single bean of type
      <PayloadFileStore> but found no beans of that type"*). That is the relocation's own
      consequence and it is closed at `f9f5ddb`, which splits the four-port claim into the three
      ports `StubGenerationConfig` still holds and one case of its own over `FileServiceConfig` -
      the mode key unmoved, the e-mail half getting the same no-op, and a pod that writes no file
      building neither adapter.)

**Checkpoint**: US4 is implemented and proven under test. It stays switched off in every deployed
environment until the notificationnotify team provides the template.

---

## Phase 8: Polish, characterisation and documentation sync

**Note, not a task**: `doc/DEFECT-FIXES.md` is **explicitly unchanged by this increment** - no row
added, amended, flipped or renumbered. A new capability is not a deviation from a legacy oracle, and
inventing a `C` or `P` number for one would make the register say a defect existed where none was
catalogued. `RegisteredDefectFixes` and `DifferentialAuditTest` must be green at T077 without having
been touched.

- [x] T070 [A] `e2e/ExceptionReportEndToEndIT` - full context with
      `yotresultsdistribution.generation.enabled=false`, which is FR-004's deployment and the shape the
      relocated repository beans exist for; Testcontainers Postgres, WireMock standing in for
      notificationnotify. Four cases:
      **(a) one of each kind** - seed a FAILED request, a 40-minute-old RETRYING request, a batch
      GENERATING past the rendering limit, a FAILED batch and a FAILED notification; run the job;
      read **five** `yotresultsdistribution_exception` events and one `yotresultsdistribution_exception_report`
      summary carrying the five counts and its **ten** fields, plus the job's own
      `exception_report_run` line with `outcome=delivered`; with the e-mail output on, read one send
      per recipient and the CSV row for each exception. Covers SC-003 and SC-004.
      **(b) SC-001 at scale** - seed **at least fifty mixed records** across the five kinds and
      across the terminal states that are *not* exceptions (COMPLETED requests, NOTIFIED batches,
      ACCEPTED notifications, registers recorded while the flag was off), run the report, and assert
      every FAILED request appears **exactly once** under `REQUEST_FAILED` and that no non-exception
      row appears at all: zero misses and zero duplicates, which is SC-001 in as many words.
      **(c) SC-006** - seed a **10,000-row** processed log and assert a `--since 24h` report is built
      and written in **under ten seconds**, measured around the build-and-deliver call.
      **(d) SC-008** - run the report job **concurrently** with a generation run and assert the two
      executed on threads named from different prefixes (`exception-report-` and the generation
      scheduler's) and that the generation run line's `duration_ms` is within its normal bound. This
      is the case that makes SC-008 a measurement rather than an inference from bean names; the
      `scheduler` attribute (T048) is what makes it pass.
      Record the first observed result of each.
      **Done** (`2701798`). A database of this suite's own inside the shared container, migrated in
      full: every case here is a claim about what the report does *not* hold as much as what it
      does, and none of that is observable against the container the other e2e suites share.
      `GenerationStackSupport` supplies WireMock and the file-service database and gains
      `textUnder(fileId)`, so the CSV is read back out of the `content` row the sink wrote.
      **(a)** five `yotresultsdistribution_exception` events, one of each kind; one
      `yotresultsdistribution_exception_report` summary with exactly ten fields and the five counts at one
      each; the run line *"event=exception_report_run ... entries=5 delivered_log=ok
      delivered_email=ok outcome=delivered duration_ms=165"*; two sends, one per recipient, over one
      attachment; a six-line CSV, header plus one row per exception, the kind first on each.
      **(b)** 76 seeded records - 12 FAILED requests, 10 late RETRYING, 8 late GENERATING batches, 7
      FAILED batches with 7 refused notifications, against 20 COMPLETED requests, 5 NOTIFIED batches
      with 5 ACCEPTED sends and 3 registers recorded while the flag was off. `entries=44`, the
      twelve parked requests reported once each, no identifier duplicated, and no healthy row named.
      **(c)** 10,000 processed-request rows, 50 of them parked inside the window, a `--since 24h`
      window built and delivered in **PT0.184980042S** against the ten-second criterion.
      **(d)** generation on (the one case that needs it), both crons at `*/2 * * * * *` so the two
      runs are asked for on the same tick, in a second context of its own: the report's line written
      from `exception-report-1` and the night's from `register-generation-1`, the generation line at
      `duration_ms=247` against a stated five-second bound. The e-mail output is off in that context
      - which thread a run happens on is the whole subject, and a send would only add a POST a tick.
      Three arrangements the schema and the code corrected on the way in: a FAILED row carries the
      identity of the delivery that exhausted it; the batch shape check pairs `completed_by` with
      the two reasons an outcome taught this service; and the run line's per-sink word is `ok`, not
      `delivered` - `DeliveryWord`'s, which is the point of it being one class.
- [x] T071 [A] `config/TelemetryPrivacyTest` and `config/TelemetryPrivacyIT` full run - `THE_LEGS`
      now covers all **seven** new classes (`ExceptionReportJob`, `IntakeAgeSweep`,
      `ExceptionReportService`, `LogEventReportSink`, `EmailReportSink`,
      `NotificationNotifyReportMailer`, `ReportExceptionsCli`) and `driveEverything()` reaches
      **every** LOG statement in them; no personal-data marker reaches a line, a metric label, a
      personalisation value or the CSV, swept at TRACE including the rendered text of any attached
      exception; the operator-token group covers `report-exceptions`; `ShippedConfiguration` requires
      `<arguments/>` in both logback files. `support/LogStatementSweepTest` stays green unchanged: no
      throwable this service did not write is attached anywhere in the seven new classes. Covers
      SC-007. Record the result.
      **Done** - and it needed no change of any kind, which is the result. `THE_LEGS` already
      carried all seven classes and `driveEverything()` already reached every LOG statement in them,
      both landed with the phases that wrote them. `./gradlew test --tests '*TelemetryPrivacyTest*'
      --tests '*TelemetryPrivacyIT*' --tests '*LogStatementSweepTest*'` BUILD SUCCESSFUL, exit 0,
      **57 tests, 0 failures, 0 errors** - the two privacy suites and the log-statement sweep,
      unchanged. No commit of its own: a verification task that finds nothing to change has nothing
      to commit, and its evidence is this line.
- [x] T072 [A] `scripts/container-smoke.sh` extended to run `report-exceptions --since 1h` through
      the entrypoint beside the existing `check-flag` step, asserting exit 0 and both a counts line
      and the run's last line, and to report it as its own PASS/FAIL line. Like `check-flag` it reads
      and changes nothing, so a smoke run cannot leave a batch or a send behind. Record the run's
      output.
      **Done** (`30587ea`). Exit 0, with its own line beside `check-flag`'s:
      *"[container-smoke] PASS: startup.sh report-exceptions --since 1h printed its counts and its
      run line, and exited 0"*, after *"PASS: readiness reported UP within the 60s budget"* and
      *"PASS: startup.sh check-flag printed flag=ON and exited 0"*. The command's own lines, read
      off the same stack: `exceptions=none`; `counts request_failed=0 request_late=0 batch_late=0
      batch_failed=0 notification_failed=0 window_from=... window_to=...`; and
      `event=exception_report_run ... entries=0 delivered_log=ok delivered_email=skipped
      outcome=delivered duration_ms=47` - `skipped` because `--email` is deliberately not given,
      which is what keeps the step read-only on a stack whose e-mail output is switched on. The run
      line is matched anywhere on the stream rather than at the end of it: the command writes to the
      operator's stream and the JVM logs to the same one, so the last thing on it is whatever the
      context said on the way down. The host it ran on already had 5433 bound by an unrelated
      container, so the file service's published port was overridden for the run through
      `COMPOSE_FILE` alone; nothing in the repository changed for it.
- [x] T073 [P] `README.md` Status - a 003 entry naming what landed: the exception report and its two
      sinks, the four instruments of design section 11, the sixth operations command, and the e-mail
      output's deployment gate on the notificationnotify template. Docs-only, exempt from the loop.
      **Done** (`b5c9927`). The Status entry also records what this increment did not touch - no
      defect-fix row - and two lines beside it follow the same change: the Quickstart's description
      of `container-smoke.sh` names both commands it now runs, and the no-REST paragraph's list of
      operational actions gains the one `report-exceptions` adds.
- [x] T074 [P] `.claude/rules/design_rules.md` - the package map gains `adapter/report/`,
      `batch/ExceptionReportJob` and `batch/IntakeAgeSweep`, and the ports list becomes
      **fourteen** (`ExceptionReportSink` and `ReportMailer` join the twelve); the two-legs diagram
      gains the 07:00 report leg beside the 18:00 generation leg, showing `ExceptionReportJob` over
      `ExceptionReportService` over the two sinks, and the sweep on its own fixed delay, in every
      non-command JVM and under no lock. The **Persistence bullet is amended** to name the two new
      permitted repository readers: it reads today that the JdbcClient repositories are "accessed
      only by `ProcessingStateService`, `IdempotencyGuard` and `JdbcRegisterStore`", and after this
      increment `ExceptionReportService` and `IntakeAgeSweep` read them too - a rule that does not
      name them is a rule the next reviewer enforces against this increment's own code. State that
      the report is **not** on the cutover lever's circuit: it reads the flag nowhere and is gated by
      it nowhere. In the consumed-contracts table, the file-service row **already records the design
      owner's ruling closing design Q20** - that the file service is the only store outside this
      service's own that may be written directly (2026-09-14, on `main` at `94bd245`); this task does
      not restate it, it **adds** to that row that the exception CSV is a **second** write through
      the same pinned changesets 001-006, so a reader knows there are now two callers and not one.
      **And it sweeps the seven shipped javadoc citations of `.claude/rules/design_rules.md` by
      path** - `grep -rn '.claude/rules' src/main src/test` finds them, in `ExceptionReportJob`,
      `IntakeAgeSweep`, `ProcessingMetricsTest`, `ExceptionReportJobTest`, `IntakeAgeSweepTest`
      (two) and `PdfPayloadMapperTest` - rewording each to cite the rule **by name** ("the service's
      design rules on absorbed refusals") instead. A tooling path in shipped source is an attribution
      fingerprint: it names the harness that wrote the line rather than the rule the line is about,
      and it is the repository convention that no such trace is left in code, comments or docs. The
      two `technical-rules.md` citations in `ComparatorContractTest` and `JsonParity` are the same
      class of trace and go with them; the one in `src/test/resources/goldens/progression/
      PROVENANCE.md` does too. **Approved by the design owner, 2026-09-15.** Deferred here from
      review gates 5 and 6, which both recorded it as belonging with this sync. Docs-only, exempt
      from the loop.
      **Done** (`4fd46c7`). The diagram gains the 07:00 leg and the sweep, the ports become
      fourteen, the package map gains `adapter/report/` and the two `batch/` classes, the commands
      become six, the Persistence bullet names `ExceptionReportService` and `IntakeAgeSweep` as
      readers that write no row, a bullet beside it states the report is on no cutover circuit, and
      the file-service row keeps the Q20 ruling verbatim and adds the second caller. The sweep found
      **ten** citations rather than the nine the task enumerated: the seven `design_rules.md` ones,
      the two `technical-rules.md` ones, and `PROVENANCE.md`'s. Each now names the rule
      ("the service's design rules on absorbed refusals"), and the shipped-source grep for the rules
      directory is **empty**. `./gradlew checkstyleMain checkstyleTest pmdMain pmdTest test --tests
      '*LogStatementSweepTest*' --tests '*TelemetryPrivacyTest*'` BUILD SUCCESSFUL, exit 0 - docs
      and comments only, no behaviour, no assertion and no log statement changed.
- [x] T075 [P] `.specify/memory/constitution.md` - `### Increments` gains **003
      "exception-report"**, 002 moves from "current" to "complete", and the Sync Impact Report header
      is updated for a **MINOR** amendment, **3.1.0 → 3.2.0**, with the bump rationale (a new
      obligation: the report's structured events and the `runId` on the sweep's lines), the modified
      sections, and the templates reviewed. Principles I to VIII are unchanged in wording. Docs-only,
      exempt from the loop.
      **Done** (`a7eff60`). 3.1.0 → 3.2.0, MINOR: the report's output is structured events under
      Principle VII's bounded-code rule, and the `runId` that principle names for a scheduled run
      now has a third caller and a fourth kind of line under it. The previous amendment's report
      moves down a level rather than being replaced, as the one before it did, and the footer reads
      **Version: 3.2.0 | Last Amended: 2026-09-15**.
- [x] T076 [P] `CLAUDE.md` - the Key Documentation table's Specifications row gains
      `specs/003-exception-report/` (complete), and the Setup section's "current plan" pointer moves
      to `specs/003-exception-report/plan.md`. Nothing else in the file changes. Docs-only, exempt
      from the loop.
      **Done** (`1cdee61`). The Setup pointer already named the 003 plan and was verified rather
      than edited. Two agent definitions carried the same staleness and are corrected with it, since
      a brief naming the wrong increment sends the next review gate to the wrong tasks file:
      `.claude/agents/software-engineer.md`'s "Current story scope" now names all three increments
      complete and describes the report in two sentences, and
      `.claude/agents/spec-validator.md`'s Scope Gate names 003 and the one thing a validator would
      otherwise raise against it - that no DEFECT-FIXES row was added, because there is no legacy
      oracle for a capability that was never built. `.claude/agents/qa.md` needed nothing: it lists
      neither the ports nor the commands.
- [x] T077 [A] Final `./gradlew clean jacocoTestReport build` green on the branch (PMD over main and
      test, Checkstyle at `maxWarnings = 0`, the JaCoCo gate at its existing floors and not
      loosened); **review gate 8** over the whole increment against the seven gates of
      `.claude/rules/workflow.md`, checked rather than asserted: no REST surface anywhere under
      `src/main/java`, no `System.out` / `System.err` / `printStackTrace()`, no wildcard import, no
      empty catch, no PII at INFO or above, and `doc/DEFECT-FIXES.md` byte-identical to its state at
      `94bd245` - the commit on `main` that last changed it, which is the baseline the register has
      to be unchanged against; `720d659` is this branch's first commit and names the same bytes,
      but the register's own baseline is where the register last moved. Report the test totals and
      the coverage figures.
      **Build half done; the review gate is not.** `./gradlew clean jacocoTestReport build
      -Dtest.noFailFast=true` **BUILD SUCCESSFUL, exit 0**, 10m 45s, 24 tasks executed - the whole
      suite with PMD over main and test, Checkstyle at `maxWarnings = 0` and the JaCoCo gate at its
      existing floors, none of them loosened. **3589 tests, 0 failures, 0 errors, 0 skipped.**
      Coverage, read off `build/reports/jacoco/test/jacocoTestReport.xml`: **LINE 6482/6690 =
      96.89%** against the 0.88 floor and **BRANCH 1966/2189 = 89.81%** against the 0.85 floor
      (instruction 96.68%, method 98.31%, class 100%). **Review gate 8 remains open** and is the
      other half of this task.
      **Review gate 8 ran with four reviewers** over the whole increment. Verdicts: `code-reviewer`
      **PASS** (1 medium, 4 low), `qa` **PASS** (four follow-ups), `spec-validator` **COMPLIANT**
      (2 low), `codex` **NOT SAFE TO MERGE** (3 high, 4 medium, 2 low). The seven gates of
      `.claude/rules/workflow.md` were **checked rather than asserted** and are clean: no REST
      surface anywhere under `src/main/java` (no `@RestController`, `@Controller` or any mapping
      annotation), no `System.out` / `System.err` / `printStackTrace()` outside the javadoc of
      `batch/cli/StandardOutput` that forbids them, no wildcard import in main or test, no empty
      catch block in main, no PII at INFO or above (`TelemetryPrivacyTest`'s eleven groups green,
      including the two arms of the sweep this gate added to the drive), and **`doc/DEFECT-FIXES.md`
      byte-identical**: `git diff 94bd245 HEAD -- doc/DEFECT-FIXES.md` is empty. No row was added,
      which is right - this increment has no legacy oracle.
      The reds are at `a6e574e`, one commit carrying 31 failing assertions over 450 cases with the
      compile-safe seams they needed. Every finding and the commit that closed it:
      * **A scheduled run's window ended at the moment the scheduler fired it** (Codex, HIGH), so
        two consecutive windows overlapped by however long the pod was busy; and **the three
        window-bounded reads bound the start alone** (Codex, HIGH), so nothing closed them at the
        other end. `2b80c62`: `forScheduledRun` is `[before(occurrence), occurrence)`, the reads are
        `failedBetween(from, to)` over `>= :from AND < :to`, and the command's
        `sinceLastScheduledRun` still ends at `now` because a snapshot's exclusive end at this
        instant excludes nothing. Reds: *"expected: 2026-09-15T06:00:00Z but was:
        2026-09-15T06:00:00.050Z"*, *"Wanted but not invoked: requests.failedBetween(...)"* and
        three *"Expecting empty but was: [...status=FAILED...]"*. spec.md's FR-003 and data-model.md
        carry the half-open wording, and **"a skipped run loses its period" is kept as documented**.
      * **A report had no ceiling** (Codex, HIGH), judged as a configurable cap rather than a lock
        change. `179cf25`: `yotresultsdistribution.report.max-entries` at 5000, the oldest kept, the rest
        counted. The counts are **not** capped - `ExceptionReport` carries them as a component with
        a `whole(...)` factory - and the summary event's eleventh field and both run lines say
        `truncated=N`. Reds: *"expected: 5000 but was: 0"*, *"to have failed but context started
        successfully"*, *"to contain entries: ["truncated"="4"]"*.
      * **The send identity was required of neither half** (Codex, MEDIUM) - the finding review
        gate 7 named and left. `345138d`, beside the endpoint rule it mirrors; three test contexts
        that enable generation without it follow at `85a1549`.
      * **The report's mailer threw for every refusal but one** (Codex, MEDIUM), with the body
        composed outside its own `try`, so one recipient's problem became every recipient's.
        `bc97032`: the body moves inside, every `RuntimeException` answers `UNANSWERED`, and
        `EmailReportSink` closes the same gap from the other side.
      * **The five downstream reads were unindexed** (Codex, MEDIUM). `b93632f`:
        `V5__exception_report_batch_and_notification_indexes.sql`, five partial indexes, each on its
        own stage column and spelled as its statement spells its predicate. `SchemaMigrationV4IT` is
        targeted at V4 so it keeps measuring one migration.
      * **`ReportRunOutcome.from` called a partially delivered sink a failure** (Codex, MEDIUM).
        `e1be504`: FAILED only where every sink delivered nothing.
      * **The CSV ended its records LF** (Codex, LOW). `d6b5432`: CRLF, per RFC 4180, with the
        header's own ending pinned so two dialects in one file cannot pass.
      * **`SweepFailureReason` was in no bounded vocabulary and the sweep's `unexpected` arm had
        never been driven** (`qa`). `940acf1`, which also extends `GenerationLegs` to the two lines
        this gate's other fixes added.
      * **SC-001's case asserted identifiers and not counts** (`qa`). `9bc4e25` adds
        `exceptionEvents(written).hasSize(failures.size())` - **green on introduction**, and said so
        rather than left to look like a red - and tags the two wall-clock cases `@Tag("timing")`,
        wired as `-PexcludeTags=timing` with a README line.
      * **Two recordings for one settlement in `DistributionPipeline`** (`code-reviewer`, MEDIUM).
        `54dc7ae`: `requestSettled(Timing, RequestStatus)` counts and times, the `RequestOutcome`
        overload is gone, nothing published changes.
      * **The CLI's two empty catches, the duplicated delivery word, and two stale seam javadocs**
        (`code-reviewer`, LOW). `e22f54a`; `eb10374` for the last stale seam narrative.
      * **Documentation drift** (`spec-validator`, 2 LOW): plan.md's Project Structure and test
        matrix, and the DEFECT-FIXES baseline. `5237a87`.
      * `8b75b4c` carries the PMD and Checkstyle this gate's own changes left behind - an
        `IdenticalCatchBranches`, four `OnlyOneReturn`, a literal, a short method name, a
        suppression that stopped suppressing anything, two misplaced javadocs and three import
        orders.
      **Final build**: `./gradlew clean jacocoTestReport build -Dtest.noFailFast=true` **BUILD
      SUCCESSFUL, exit 0**, 10m 31s, 24 tasks executed - the whole suite with PMD over main and
      test, Checkstyle at `maxWarnings = 0` and the JaCoCo gate at its existing floors, none of them
      loosened. **3614 tests over 576 suites, 0 failures, 0 errors, 0 skipped.** Coverage, read off
      `build/reports/jacoco/test/jacocoTestReport.xml`: **LINE 6543/6753 = 96.89%** against the 0.88
      floor and **BRANCH 1987/2212 = 89.83%** against the 0.85 floor (instruction 96.68%, method
      98.32%, class 100%). **Review gate 8 is closed.**
      **Final re-check.** One independent pass over gate 8's own remediation, finding by finding.
      | Finding | Verdict | What the re-check found |
      |---|---|---|
      | F1 - the scheduled window ended at the moment the scheduler fired | Resolved | `forScheduledRun` is `[before(occurrence), occurrence)`; two consecutive windows abut and cannot overlap, however busy the pod was |
      | F2 - the three window-bounded reads bound the start alone | **Partial** | They are half-open at both ends now, so no row is read by two runs. The other side of that coin is not closed: a run that is a **whole period late** loses the missed period, because the window is aligned to the schedule rather than carried forward. It is **documented as kept behaviour** and **alerted on the runs counter** - a period nobody reported is a run that did not happen, and the counter is what says so. Closing it means persisting the last completed boundary and opening the window from there, which is a follow-up increment, not a remediation |
      | F3 - a report had no ceiling | **Partial** | The **budget stays advisory**: what actually bounds a run is the lock and the counters, not a deadline the run enforces on itself. The **cap** now bounds the recurring kinds only - see the HIGH below |
      | F4-F9 - the four mediums and the two lows | Resolved | `345138d`, `bc97032`, `b93632f`, `e1be504`, `d6b5432` and the tidy-ups above; nothing reopened, nothing regressed |
      The pass also raised **one new HIGH and two LOWs**, landed here as red/green pairs:
      * **The entry cap could permanently hide a failure** (HIGH). `yotresultsdistribution.report.max-entries`
        was applied in `ExceptionReportService.build` after ordering, across all five kinds. Since F1
        and F2 made the three failure reads half-open and aligned to the schedule, every failed row
        belongs to **exactly one** run's window - so a `REQUEST_FAILED`, `BATCH_FAILED` or
        `NOTIFICATION_FAILED` entry the cap dropped sat in a window no later run re-reads and was
        reported **nowhere**, on precisely the longest morning of the year. Fixed by design rather
        than by raising the number: the cap bounds `REQUEST_LATE` and `BATCH_LATE` alone - they are
        asked about a cut-off, so whatever one run leaves out the next reads again, older - and the
        three failure kinds are carried whole whatever the count. `truncated` counts dropped **late**
        entries only. Reds at `eb84f80`: *"Expecting actual: [(REQUEST_FAILED, 9000L),
        (REQUEST_FAILED, 8000L)] to contain exactly (and in same order): [(REQUEST_FAILED, 9000L),
        (REQUEST_FAILED, 8000L), (REQUEST_FAILED, 7000L), (REQUEST_LATE, 6000L), (REQUEST_LATE,
        5000L)]"* and *"Expecting actual: [9000L, 8000L] to contain exactly (and in same order):
        [9000L, 8000L, 7000L]"*; the third case,
        `the_cap_keeps_the_oldest_late_entries_and_drops_the_newest`, is **green on introduction**
        and pins the half that does not change. Closed at `c437293`:
        `ExceptionKind.recursEveryRun()` as a switch expression, so a sixth kind cannot be added
        without deciding which class it is in, and the setting's comment, `ReportProperties`,
        `ExceptionReport`, `LogEventReportSink`, `PropertiesValidator`, data-model.md, plan.md and
        quickstart.md's KQL comment all carry the narrowed `truncated` semantics.
      * **`PropertiesValidator` named the wrong window factory** (LOW). Its schedule javadoc called
        the scheduled run's window `ReportWindow.sinceLastScheduledRun`; the job uses
        `forScheduledRun` and the **command** uses `sinceLastScheduledRun`. Corrected to name both,
        in `c437293` beside the cap's own javadoc on the same file.
      * **The `-PexcludeTags` hatch had nothing saying where it stops** (LOW). It is kept - a
        developer on a loaded machine leaves the two wall-clock cases out **by name** rather than by
        disabling the suite that holds them - and CI was **checked rather than assumed**: the one
        workflow that tests, `ci-build-publish.yml`, runs `./gradlew jacocoTestReport check` and
        passes no `excludeTags` (`ci-draft.yml` and `ci-released.yml` call it, `codeql.yml` runs
        `-x test`, and `gradle.properties` sets nothing), so no acceptance criterion can be skipped
        on the way to a merge. `4b4e4bb` says so in the README note beside the tag.
      **Re-check build**: `./gradlew clean jacocoTestReport build -Dtest.noFailFast=true` **BUILD
      SUCCESSFUL, exit 0**, 10m 28s, 24 tasks executed - the whole suite with PMD over main and
      test, Checkstyle at `maxWarnings = 0` and the JaCoCo gate at its existing floors, none of them
      loosened. **3616 tests over 576 suites, 0 failures, 0 errors, 0 skipped.** Coverage, read off
      `build/reports/jacoco/test/jacocoTestReport.xml`: **LINE 6556/6766 = 96.90%** against the 0.88
      floor and **BRANCH 1995/2220 = 89.86%** against the 0.85 floor (instruction 96.68%, method
      98.33%, class 100%). **The final re-check is closed.**

---

## Dependencies & execution order

- Phase 1 → Phase 2 → **Phase 3 (US2) and Phase 4 (US1) in either order** → Phase 5 (US1) →
  Phase 6 (US3) → Phase 7 (US4) → Phase 8. Phases 3 and 4 touch different files and may run in
  parallel after Phase 2 if two implementers are available, but **their commits are serialised**.
- **US2 and US5 are independent of US1.** US5 is Phase 1 and blocks everything, because every later
  phase reads its settings. US2 (Phase 3) needs Phase 2's repository reads and nothing else: it
  touches no sink, no report and no job, and it is demonstrable on a pod with the report switched
  off, which is exactly what FR-008 and user story 2 scenario 4 require.
- **US3 depends on US1's service.** `ReportExceptionsCli` reuses `ExceptionReportService` rather than
  composing a second read path, so Phase 6 cannot start before Phase 4 has landed T034.
- **US4 depends on US1 and on Phase 2's two port seams.** `EmailReportSink` delivers an
  `ExceptionReport` (Phase 4) through `PayloadFileStore.storeText` and `ReportMailer` (the seams at
  T020, implemented at T065 and T066), so Phase 7 follows both. Its implementation is not gated; its
  deployment is.
- Within each "Tests first ⚠️" block every task is `[P]` **except where two tasks share a file**:
  T039 extends the same `ReportSchedulingConfigTest` T038 creates, so it follows T038 rather than
  running beside it; T042 extends `ExceptionReportJobTest` (T041) and `IntakeAgeSweepTest` (T024),
  so it follows both. The implementation block is serialised where a file is shared:
  `config/ProcessingMetrics` (T025), `config/PropertiesValidator` (T003), `config/SchedulingConfig`
  and `config/CliModeConfig` (T043, then T048's constant), `config/ProcessedLogConfig` and
  `config/GenerationConfig` (T044), `support/GenerationLegs` (T033, T047, T057, T066, T067 - five
  phases append to one file, so those five commits are strictly ordered), `batch/cli/CliMain` (T058)
  and `docker-compose.yml` (T049, T068).
- T020's `storeText` and `ReportMailer` seams block T061, T064, T065 and T066. **T018
  (`LastScheduledRun`) is in Phase 2, not Phase 4**, because two things need it:
  `ReportWindow.sinceLastScheduledRun` (T019, the scheduled window) and T034's never-batched
  `BATCH_LATE` case. T016 (the four `RegisterBatchRepository` reads) and T017
  (`recordedUnbatchedBefore`) block T034's `BATCH_LATE` and `BATCH_FAILED` cases.
  **T044 (the relocated repository beans) blocks T070**, which runs the whole context with
  `yotresultsdistribution.generation.enabled=false`. **T048 (the `scheduler` attributes) blocks T070's SC-008
  case**, which is the measurement those attributes exist for. T036 (`<arguments/>`) blocks nothing
  in code but is what makes T070's event reads meaningful. T071 cannot run before T067, the last
  task to append to `THE_LEGS`.
- **T055 precedes T059**, deliberately: `docker/startup.sh`'s `case` pattern and its `CLI_COMMANDS`
  string are two halves of one list, and a change that edits one and forgets the other looks exactly
  like a change that edited both until the image is asked.

### Parallel opportunities per story

```bash
# US5 (Phase 1) - the two red runs are independent files:
Task: "T001 ConfigurationValidationTest + ReportPropertiesTest defaults, refusals and SC-005"
Task: "T005 persistence/SchemaMigrationV4IT"

# Foundational (Phase 2) - six test tasks, then seven implementation tasks:
Task: "T008 persistence/ProcessedRequestReportReadsIT"
Task: "T009 persistence/RegisterNotificationReportReadsIT"
Task: "T010 persistence/RegisterBatchReportReadsIT"
Task: "T011 persistence/RegisterStoreReportReadsIT"
Task: "T012 domain/LastScheduledRunTest"
Task: "T013 domain/ExceptionReportModelTest"

# US2 (Phase 3) - three files, no shared seam:
Task: "T022 config/ProcessingMetricsTest"
Task: "T023 application/DistributionPipelineTest"
Task: "T024 batch/IntakeAgeSweepTest"

# US1 (Phase 4) - five test files, all independent:
Task: "T029 application/ExceptionReportServiceTest"
Task: "T030 application/ExceptionReportDeliveryTest"
Task: "T031 adapter/report/LogEventReportSinkTest"
Task: "T032 config/TelemetryPrivacyTest.ShippedConfiguration"
Task: "T033 support/GenerationLegs"

# US1 (Phase 5) - three wiring and job test files in parallel; T039 follows T038 (same file)
# and T042 follows T041 and T024 (it extends both):
Task: "T038 config/ReportSchedulingConfigTest"
Task: "T040 config/CliModeConfigTest"
Task: "T041 batch/ExceptionReportJobTest"

# US3 (Phase 6) - five test files:
Task: "T051 batch/cli/ArgsTest"
Task: "T052 batch/cli/ReportExceptionsCliTest"
Task: "T053 batch/cli/CliMainTest"
Task: "T054 config/TelemetryPrivacyTest operator-token group"
Task: "T055 e2e/CliDispatchIT"

# US4 (Phase 7) - four test files:
Task: "T061 adapter/fileservice/FileServicePayloadStoreIT"
Task: "T062 adapter/report/EmailReportSinkStoreIT"
Task: "T063 adapter/report/EmailReportSinkTest"
Task: "T064 adapter/notificationnotify/NotificationNotifyReportMailerTest"

# Polish (Phase 8) - the four documentation tasks touch four different files:
Task: "T073 README.md Status"
Task: "T074 .claude/rules/design_rules.md"
Task: "T075 .specify/memory/constitution.md"
Task: "T076 CLAUDE.md"
```

## Notes

- **MVP = Phases 1 to 5**, which is **US1 + US2 + US5**: the settings and their refusals, the reads,
  the instruments, the report and its log sink, and the 07:00 schedule over a window the schedule
  itself defines. It is deployable with `yotresultsdistribution.report.enabled=true`,
  `yotresultsdistribution.generation.enabled=false` and `yotresultsdistribution.report.email.enabled=false`, and it is the
  whole value the increment exists for - everything the report says is already recorded, and the
  point is making it visible where support looks. **US3 next** (Phase 6, the on-demand command),
  **US4 last** (Phase 7, the e-mail), because it is the only part that waits on another team.
- **Every phase ends with a green `./gradlew build` and a review gate in a new session.** Gate
  findings land as a red test commit followed by an implementation commit, exactly like any other
  change; a finding fixed without a test is a finding that can come back. **Never two committing
  agents at once in this repository.**
- Commit narrative convention: test commits quote the red assertion; implementation commits quote the
  green run. A Phase 1 infrastructure commit quotes the evidence it recorded instead.
- Conventional Commits on `003-exception-report`, from `feat`, `fix`, `chore`, `docs`, `test`,
  `refactor`, `build`, `ci`, `style`. A configuration change is a `chore` or a `build`, never a
  `config`. Any merge commit on this branch needs its own entry in plan.md's Complexity Tracking
  table with the design owner's dated approval, as 002 records: the constitution's rule is
  non-negotiable and merges are not exempt from it.
- **No AI attribution** anywhere: not in a commit subject or body, not in a code comment, not in a
  test name, not in any document this increment touches.
- 001's and 002's suites stay green throughout and are not adapted to fit this increment.
  `DifferentialAuditTest`, `RegisteredDefectFixes` and `LogStatementSweepTest` are untouched; if one
  of them goes red, the cause is in the new code and not in the assertion. The two generation suites
  gain exactly one case each (T042's `scheduler` attribute) and nothing else about them moves.
