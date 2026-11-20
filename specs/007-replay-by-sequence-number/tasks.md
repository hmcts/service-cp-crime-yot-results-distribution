# Tasks: Replay a parked request by broker sequence number

**Input**: Design documents from `/specs/007-replay-by-sequence-number/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/replay-rule-delta.md,
quickstart.md

**Tests are MANDATORY** (Constitution Principle II). Every implementation task is strictly preceded
by the test task that guards it. Test case descriptions below are drawn from the plan's Test Matrix
and from `spec.md`'s FR-015 gate list — do not rename the intent of a case without updating both.

**Red-run convention**: a test task includes landing the minimal compile-safe seam its test needs
(a new field on a record signature, a method stub throwing `UnsupportedOperationException`, a new
empty class) so the recorded red run is a **failing assertion**, never a compile error. Quote the
failing assertion in the test task's commit; quote the green run in the paired implementation task's
commit.

**The defect-fix register in this feature**: no row is added and no row's claim changes (Constitution
Check, Principle I — the legacy function app had no processed log, no parking and no replay, so there
is no oracle for this feature to differ from). `doc/DEFECT-FIXES.md` row C17 is reviewed for wording
only at T044 and is **not** rewritten.

**Conventions**: package root `uk.gov.hmcts.cp.yotresultsdistribution`; production under
`src/main/java/…`, tests under `src/test/java/…`. `*IT` suites need Docker and run inside
`./gradlew test`. Conventional Commits on `007-replay-by-sequence-number`; no AI attribution anywhere.
**Every phase ends with a green `./gradlew jacocoTestReport build`, in that order**, and a review gate
(`code-reviewer`, `qa`, `spec-validator` per `.claude/rules/workflow.md`) before the next phase
starts. Never two committing agents at once in this tree.

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: Can run in parallel (different files, no dependencies on an incomplete task)
- **[Story]**: US1–US4, mapping to spec.md's four user stories. Setup, Foundational and Polish tasks
  carry no story label.

---

## Phase 1: Setup

**Purpose**: land the rule-and-agent-documentation delta *before* any production code relaxes the
guard's behaviour (constitution Governance; FR-013). This repository has no `doc/API_CONTRACTS.md`
for the delta to land in — the artefact of record is `.claude/rules/*` and `.claude/agents/*`.

- [X] T001 Apply `specs/007-replay-by-sequence-number/contracts/replay-rule-delta.md` verbatim to
      `.claude/rules/design_rules.md` (§1a, §1b), `.claude/rules/technical-rules.md` (§2), and
      `.claude/agents/{code-reviewer,qa,software-engineer,spec-validator}.md` (§3). Documentation-only
      — exempt from the TDD loop per `.claude/rules/workflow.md`'s "What Requires the Loop" table
      ("CLAUDE.md and rule updates" is listed Exempt). Commit before T007/T017 below, or in the same
      reviewed change — never after.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the schema, domain types, configuration and broker-metadata consolidation every user
story's tests are built on. No user-story work can begin until this phase is green.

### Tests for Foundational (write first) ⚠️

- [X] T002 [P] Write failing `SchemaMigrationIT` cases in
      `src/test/java/uk/gov/hmcts/cp/yotresultsdistribution/persistence/SchemaMigrationIT.java`: `V9`
      leaves every existing `FAILED` row valid; `processed_request_parking_triple_chk` admits an
      all-null triple and an all-present triple, and rejects a partially-set one;
      `processed_request_parking_category_chk` admits `NULL`, `'EXHAUSTED'`, `'NON_TRANSIENT'`, and
      rejects any other value.
- [X] T003 [P] Write failing `domain/ReasonCodeTest` (new file) in
      `src/test/java/uk/gov/hmcts/cp/yotresultsdistribution/domain/ReasonCodeTest.java`:
      `fromCode(String)` round-trips every declared `ReasonCode` value via its own `code()`, and
      returns `Optional.empty()` for `null`, blank, and an unrecognised string.
- [X] T004 [P] Write failing `domain/FailureCategoryMappingTest` (new file) in
      `src/test/java/uk/gov/hmcts/cp/yotresultsdistribution/domain/FailureCategoryMappingTest.java`:
      the seven non-transient-path codes (`PAYLOAD_READ_REFUSED`, `REFERENCE_DATA_REFUSED`,
      `TRANSFORMATION_FAILED`, `OUTBOUND_CONTRACT_VIOLATION`, `REGISTER_NOT_RECORDED`,
      `SUBMISSION_REJECTED`, `SUBMISSION_NOT_ACCEPTED`) map to `DeadLetterReason.NON_TRANSIENT`;
      every other declared `ReasonCode`, `null`, and an unrecognised string map to
      `DeadLetterReason.EXHAUSTED`; the method never throws.
- [X] T005 [P] Write failing `config/ConfigurationValidationTest` case in
      `src/test/java/uk/gov/hmcts/cp/yotresultsdistribution/config/ConfigurationValidationTest.java`:
      start-up refuses a configured `yotresultsdistribution.replay.legacy-margin` shorter than
      `yotresultsdistribution.servicebus.lock-duration`, naming only the two setting keys (no value
      echoed).
- [X] T006 [P] Write failing cases in
      `src/test/java/uk/gov/hmcts/cp/yotresultsdistribution/inbound/MessageListenerSettlementTest.java`
      and `SettlementFailureEdgeTest.java`: each of `getSequenceNumber()`, `getDeliveryCount()`,
      `getMessageId()`, `getLockToken()`, `getEnqueuedTime()` is invoked **at most once** per
      delivery (mock-verified); a message whose `getDeliveryCount()` (or any required accessor)
      throws still produces exactly one settlement, and that settlement is always `abandon()`,
      **never** `deadLetter()`, because the final-delivery position could not be determined; a
      `null`/absent enqueue time is recorded as absent and the delivery proceeds normally.
- [X] T006a [P] Satisfied by extending `support/ProcessedLogTestSupport.Row` (and its backing read)
      with the three new columns, rather than a new dedicated test method: `StaleRunnerRejectionIT`'s
      existing `a_stale_outcome_write_should_be_rejected(EXHAUSTION)` case already asserts the full
      row is unchanged (`assertThat(row()).isEqualTo(before)`) after a superseded runner's
      `recordExhaustion` write is refused — once `Row` carries `parkingSequenceNumber`/
      `parkingCategory`/`parkingAttemptCount`, that existing full-row comparison proves FR-002 for the
      three new columns for free, exactly mirroring the pre-existing owner/token-predicate proof for
      `status`/`failure_reason`. Verified green (`StaleRunnerRejectionIT`: 13/13).

### Implementation for Foundational

- [X] T007 [P] Create `src/main/resources/db/migration/V9__add_parking_sequence_category_and_attempts.sql`:
      `ALTER TABLE processed_request ADD COLUMN parking_sequence_number bigint, ADD COLUMN
      parking_category text, ADD COLUMN parking_attempt_count integer;` plus
      `processed_request_parking_category_chk` and `processed_request_parking_triple_chk`
      (data-model.md). Makes T002 pass.
- [X] T008 [P] `domain/DeliveryIdentity.java`: add `sequenceNumber` (`long`), `deliveryCount`
      (`long`), `enqueueTime` (`Instant`, nullable). Keep the existing 2-/3-arg convenience
      constructors, defaulting the three new fields to `-1L`, `0`, `null` respectively (data-model.md
      — these constructors are used by seven test files unrelated to replay and must not be forced
      to change). Add a new constructor or factory taking all six fields explicitly, for callers that
      do exercise replay.
- [X] T009 [P] `domain/RunClaim.java`: add `sequenceNumber` (`long`) — the claim's own delivery's
      sequence number, following the existing `messageId` field's javadoc convention verbatim.
- [X] T010 [P] `domain/ProcessedRequestRecord.java`: add `parkingSequenceNumber` (`Long`),
      `parkingCategory` (`String`), `parkingAttemptCount` (`Integer`), `updatedAt` (`Instant`).
- [X] T011 [P] `domain/ReasonCode.java`: add `public static Optional<ReasonCode> fromCode(String
      storedCode)`, total over the declared enum. Makes T003 pass.
- [X] T012 [P] Create `domain/FailureCategoryMapping.java`: `public static DeadLetterReason
      categoryFor(String storedReasonCode)` implementing the FR-010 table. Makes T004 pass.
- [X] T013 `config/YotResultsDistributionProperties.java`: add `lockDuration` (`@DefaultValue("1m")
      Duration`) to the existing `Servicebus` record, beside `maxDeliveryCount`; add a new nested
      `Replay(@DefaultValue("5m") Duration legacyMargin)` record and wire it into the top-level
      properties record's component list and constructor javadoc. Depends on T008 (same `Duration`
      import conventions already used there).
- [X] T014 `config/PropertiesValidator.java`: add a start-up check refusing
      `replay.legacyMargin < servicebus.lockDuration`, naming only the two setting keys, following
      the existing `validateLockOutlivesTheRun` pattern. Depends on T013. Makes T005 pass.
- [X] T015 [P] `src/main/resources/application.yaml`: add `servicebus.lock-duration: 1m` (comment:
      "Mirrors the broker queue's own setting. Must be changed here and on the queue together.",
      matching `max-delivery-count`'s existing comment style) and a new `replay:` block with
      `legacy-margin: 5m`. Depends on T013.
- [X] T016 `persistence/ProcessedRequestRepository.java`: extend `READ_RECORD`'s `SELECT` list with
      `parking_sequence_number, parking_category, parking_attempt_count, updated_at`, and the
      `read(...)` row mapper to populate the four new `ProcessedRequestRecord` fields; extend
      `RECORD_FAILED`'s `SET` clause with `parking_sequence_number = :sequenceNumber,
      parking_category = :category, parking_attempt_count = attempts` (the last is the bare column
      reference, not a bound parameter — FR-001), and `recordNonTransientFailure`/`recordExhaustion`
      to pass the claim's `sequenceNumber` and the calling branch's category — this `SET` clause
      stays predicated on `claim_owner = :owner AND claim_token = :token`, unchanged, which is what
      makes T006a's stale-runner case pass alongside the existing ones. Depends on T007, T009, T010.
      Makes T006a pass.
- [X] T017 `inbound/YotResultsDistributionMessageListener.java`: add a listener-local
      `BrokerDeliverySnapshot` record (`sequenceNumber`, `deliveryCount`, `messageId`, `lockToken`,
      `enqueueTime`, `finalPermittedDelivery`); build it once at the top of `process`'s existing call
      frame, inside `decide`/`examine`'s total `try`/`catch RuntimeException` boundary; rewrite
      `process`'s receipt-log line, `identityOf`, and `isFinalPermittedDelivery` to read the snapshot
      instead of calling `ServiceBusReceivedMessage` accessors directly; a thrown accessor must
      always resolve to `abandon()`, never `deadLetter()` (FR-012). Depends on T008. Makes T006 pass.

**Checkpoint**: migration, domain types, configuration and broker-metadata consolidation exist and
are proven in isolation. `./gradlew jacocoTestReport build` green; review gate passed.
`IdempotencyGuard.replayOrPark` is **not yet touched** — it still compares `messageId` only, so no
user-story behaviour has changed yet.

---

## Phase 3: User Story 1 - Support recovers a parked request by re-sending it unchanged (Priority: P1) 🎯

**Goal**: a delivery whose broker sequence number differs from the one recorded when a `FAILED`
request was parked is treated as a deliberate resubmission and replayed, whatever its `messageId`.

**Independent Test**: park a request; wait past the broker's duplicate-detection window for its
`messageId`; re-send the dead-lettered body unchanged (same `messageId`); observe `FAILED → RECEIVED`
and the pipeline running again.

### Tests for User Story 1 (write first) ⚠️

- [X] T018 [P] [US1] Write failing `IdempotencyGuardIT`/`FailedReplayIT` case: a `FAILED` request with
      a recorded parking sequence number, delivered under the **same `messageId`** but a **different**
      sequence number, is replayed — `FAILED → RECEIVED`, `attempts` preserved and incremented once,
      `failure_reason`/`exhausted_message_id`/`parking_sequence_number`/`parking_category`/
      `parking_attempt_count` all cleared, audit note written (FR-003 different-branch, FR-004,
      FR-007).
- [X] T019 [P] [US1] Write failing case: the replay's compare-and-set matches exactly the
      `(attempts, parking_sequence_number)` pair observed on the read; a delivery that read the row
      before a concurrent replay already ran and re-parked it is refused `REPLAY_NOT_ADMITTED` and
      never replays the newer failure (FR-006, the spec's "stale replay decision" edge case).
- [X] T019a [P] [US1] Write failing case — the opposite of T019, not a duplicate of it: a delivery
      arrives **after** a newer failure has already been recorded for the request, carrying a
      sequence number that differs from the **current** parking sequence number (a stray or delayed
      copy of an earlier attempt). Assert it is **admitted** and replays the current failure once,
      logged as a replay — the spec's "independent-enqueue policy" (Edge Cases, "A stale copy of an
      older parking delivery…"), which is accepted behaviour, not a race to be refused.
- [X] T020 [P] [US1] Write failing case: two concurrent re-sends for the same parked request admit
      exactly one replay; the loser is handed back `REPLAY_NOT_ADMITTED` (FR-006 concurrency).
- [X] T021 [P] [US1] Write failing case: a replay of a request whose register is already recorded in
      `record` mode (or, in `progression-post` mode, already `POSTED`) records/sends nothing a second
      time — the independent-enqueue regression, re-asserted under the new decision path.

      `FailedReplayIT.a_replay_of_an_already_posted_register_should_not_post_it_again`: a run POSTs
      the output row, the request itself still ends up `FAILED` (a non-transient failure unrelated to
      the POST), and a same-`messageId` replay admitted by sequence number alone finds the output
      claim refused. The qa review found the task checked off against
      `SubmissionRedeliveryIT.a_replay_of_a_posted_register_does_not_post_it_again` alone, which
      constructs its `RunClaim` directly and never exercises `guard.admit()` — it would pass
      identically whether T025/T026 existed or not. The new case goes through `guard.admit()` and
      reuses the parking `messageId`, so it is red against the pre-T025/T026 code (confirmed in a
      disposable worktree at `0abc5d44`) and green after.
- [X] T022 [US1] Write failing log-assertion case: an admitted replay logs the request identifiers,
      the parked delivery's sequence number, and the replaying delivery's sequence number —
      identifiers only, no defendant detail (FR-008).
- [X] T023 [P] [US1] Extend `e2e/QueueSettlementIT` and `e2e/DeliveryExhaustionIT` against the real
      Service Bus emulator: the sequence number is unchanged across an `abandon()`; a dead-letter
      re-send under the same `messageId` is accepted and enqueued with a **new** sequence number.
- [X] T024 [US1] Extend `e2e/QueueSettlementIT`: park a request, wait past the duplicate-detection
      window, re-send the dead-lettered body unchanged via the emulator's dead-letter sub-queue, and
      observe `FAILED → RECEIVED → COMPLETED` end to end (US1's own Independent Test).

### Implementation for User Story 1

- [X] T025 [US1] `persistence/ProcessedRequestRepository.java`: rewrite `REPLAY_FAILED`'s `WHERE`
      clause from `status = 'FAILED' AND exhausted_message_id <> :messageId` to `status = 'FAILED'
      AND attempts = :observedAttempts AND parking_sequence_number IS NOT DISTINCT FROM
      :observedParkingSequenceNumber`; extend its `SET` clause to clear `parking_sequence_number`,
      `parking_category`, `parking_attempt_count` alongside the existing `exhausted_message_id = NULL`.
      Depends on T016. Makes T018-T020 pass.
- [X] T026 [US1] `application/IdempotencyGuard.java`: rewrite `replayOrPark` for the row whose
      `parking_attempt_count` equals the current `attempts` (the "exact" row, data-model.md's decision
      table) — equal sequence number re-parks without running (the US2 half, carried forward
      unconditionally pending Phase 6's reason/category wiring), different sequence number replays
      via the new compare-and-set (T025). Depends on T025. Makes T018, T019, T019a, T020, T022 pass.
- [X] T027 [US1] `inbound/YotResultsDistributionMessageListener.java`: `identityOf` builds
      `DeliveryIdentity`/`RunClaim` from the `BrokerDeliverySnapshot`'s `sequenceNumber`,
      `deliveryCount` and `enqueueTime` (T017's new explicit constructor from T008). Depends on T017,
      T026. Makes T023, T024 pass.

      T024's independent test needed a second, test-only queue
      (`yotresultsdistribution.requests.resend-window-test`, `docker/servicebus-emulator/config.json`)
      with a ten-second duplicate-detection window rather than the production-mirroring five minutes,
      so the suite can wait the window out; mirrors the identical choice already taken in
      `service-cp-crime-informant-register` (`UnchangedResendRecoveryIT`). `QueueSettlementIT`'s own
      T023 sequence-number-on-an-accepted-resend assertion stays on the production queue under a
      **fresh** `messageId`, deliberately, so it is not itself discarded by the real five-minute
      window; `ServiceBusEmulatorTestSupport.peekFor` gained a queue-name overload to serve both.

**Checkpoint**: User Story 1 is independently testable — a portal-style dead-letter re-send with an
unchanged `messageId` recovers a parked request end to end.

---

## Phase 4: User Story 2 - The delivery that parked the current failure is never re-run (Priority: P1)

**Goal**: the delivery recorded as parking the **current** `FAILED` generation must never be re-run,
even if its own dead-letter operation did not settle and the same stored message returns.

**Independent Test**: park a request; arrange for the same message (same sequence number) to be
delivered again; observe that nothing runs and the message is dead-lettered again.

### Tests for User Story 2 (write first) ⚠️

- [X] T028 [P] [US2] Write failing `IdempotencyGuardIT`/`FailedReplayIT` case: a delivery carrying
      the **same** sequence number as the one recorded when the request was parked runs nothing,
      changes no state, and is dead-lettered again (FR-003 same-branch).

      `IdempotencyGuardIT.ParkedRequest.should_stay_parked_under_a_different_messageId_carrying_the_same_sequence_number`:
      a **different** `messageId` from the parking delivery, same sequence number — the case the
      pre-existing `should_stay_parked_under_the_identity_that_exhausted_the_retries` cannot
      distinguish from "same messageId" on its own. The two together pin the decision as sequence
      number alone, not messageId. (`FailedReplayIT` already carries the Phase 3 pair of the same
      shape, written before this phase existed.)
- [X] T029 [US2] Write failing case: exactly one explicit settlement attempt is made for the
      re-parked delivery (settlement-discipline regression under the new branch).

      Satisfied by composition, not a new test: `MessageListenerSettlementTest.Parked.should_dead_letter_it_exactly_once_with_a_bounded_reason_and_description`
      already asserts `verify(context).deadLetter(...)` (Mockito's default `times(1)`) for *any*
      `GuardDecision.DeadLetter` the listener is handed, and the listener's settlement code has no
      branch-specific logic to tell "equal-sequence re-park" apart from any other `DeadLetter` — it
      only ever sees the decision type. There is no additional code path for a dedicated mock-based
      test to regress. The T030 e2e test below additionally proves this holds for the real branch
      end to end: the message would not reach the dead-letter sub-queue at all if its settlement had
      failed or been attempted more than once.
- [X] T030 [P] [US2] Extend `e2e/DeliveryExhaustionIT`: a stored message released by a lost lock or a
      closed connection (this service's own intake-suspend/shutdown path) returns under its original
      sequence number and is parked again without running.

      `should_re_park_without_running_when_the_exhausting_deliverys_settlement_is_lost`: the
      exhausting (fifth) delivery is held in flight until confirmed, the Service Bus transport is
      cut through Toxiproxy underneath it (`ServiceBusEmulatorTestSupport.disconnect()`, the same
      technique `QueueOutageRecoveryIT` already uses), and only then released — its
      `processed_request` write reaches Postgres (an unaffected connection) but its `deadLetter()`
      settlement is refused by the severed transport. Once the transport is restored, the broker
      offers the same stored message again; the guard re-parks it under the sequence number it
      already carried, without the payload port being invoked a sixth time, and the message lands on
      the dead-letter queue under its original sequence number. Confirmed stable across four runs.

### Implementation for User Story 2

- [X] T031 [US2] `application/IdempotencyGuard.java`: confirm, under this story's own dedicated test
      names, the equal-sequence-number branch T026 already implements — add no new production code
      unless T028/T029/T030 find a gap (expected: none; this phase is the proof that the protection
      already holds, matching the spec's own framing of US2 as "the protection the current test
      exists for must survive"). Depends on T026.

      No gap found; no production code changed in this phase.

**Checkpoint**: User Stories 1 and 2 together complete the exact-row decision table (FR-003, FR-004,
FR-006, FR-007, FR-008). A re-park's dead-letter reason is still the pre-existing generic one until
Phase 6.

---

## Phase 5: User Story 3 - Requests parked before this change can be recovered (Priority: P2)

**Goal**: a `FAILED` row with no recorded parking sequence number — parked before this change, or by
an older consumer mid-rollout — is still recoverable, via the FR-005 heuristic.

**Independent Test**: create a `FAILED` record with no parking sequence number; deliver a newly sent
message for it (first delivery, enqueued well after parking) and observe a replay; deliver one that
fails either condition and observe a re-park.

### Tests for User Story 3 (write first) ⚠️

- [ ] T032 [P] [US3] Write failing case: a `FAILED` row with no recorded parking sequence number,
      delivered with count **0** and enqueue time later than `updated_at + legacy-margin`, is
      replayed (FR-005).
- [ ] T033 [P] [US3] Write failing case: the same row, delivered with count **≥ 1**, or enqueue time
      not later than `updated_at + legacy-margin`, or with no enqueue time at all, is re-parked
      (FR-005).
- [ ] T034 [US3] Write failing case: an enqueue time **exactly equal** to `updated_at + legacy-margin`
      re-parks — the boundary is exclusive.
- [ ] T034a [US3] Write failing case demonstrating, not just narrating, FR-005's accepted residual
      risk: a legacy row released without a delivery-count increment (the connection-closed path,
      not an abandon), delivered with an **enqueue time read as later than it genuinely was** by
      more than the margin (a simulated broker-clock lead over the database clock) — assert the
      heuristic still admits it as a replay. This is the one case FR-005's own text accepts rather
      than guards against; the test's purpose is to pin it as observed, reviewed behaviour rather
      than an assumption nothing exercises.
- [ ] T035 [US3] Write failing case: a legacy resubmission handed back once (store outage suspended
      intake, or it lost a compare-and-set race) is judged afresh on its next delivery, not
      remembered (US3 Acceptance Scenario 3).
- [ ] T036 [P] [US3] Write failing case: a row whose `parking_sequence_number`/`parking_category` are
      present but whose current `attempts` differs from its recorded `parking_attempt_count` is
      decided by the legacy heuristic, not as exact, and its stale `parking_category` is never read
      (FR-005a, research.md R6).
- [ ] T037 [US3] Write failing case for the **replay** half of the rolling-deployment edge case,
      distinct from T036's re-park half: a row parked by a newer consumer (parking triple set) is
      then moved `FAILED → RECEIVED` by a statement shaped like the *old* `REPLAY_FAILED` — one that
      clears only the pre-existing columns (`exhausted_message_id`, `failure_reason`, `audit_note`)
      and has no idea the three new columns exist. Assert the triple survives, stale and non-null,
      on the now-`RECEIVED` row, and that `processed_request_parking_triple_chk` is not violated
      (FR-014's "clearing is a write rule, not a database constraint" — this is the state that rule
      exists to permit). This is the precursor state to T036: T036 proves the *next* parking of this
      same row is judged as legacy by the `attempts` signal; T037 proves the intermediate state
      between the two parkings is itself constraint-valid. The "old" statement is simulated directly
      in the test (a hand-written `UPDATE` mirroring the pre-this-feature `REPLAY_FAILED` shape),
      not invoked through production code — there is no older version of this statement left to call.
      Pinned by T007's migration (the constraint under test already exists); no new production code
      in this phase guards this case.

### Implementation for User Story 3

- [ ] T038 [US3] `application/IdempotencyGuard.java`: add the legacy/stale branch to `replayOrPark` —
      for a row with absent or stale parking fields, apply `deliveryCount == 0 && enqueueTime >
      updatedAt.plus(legacyMargin)`, replaying via the same compare-and-set (its null-safe half
      matches whatever was observed) or re-parking otherwise. Depends on T026, T014 (needs
      `legacyMargin`). Makes T032-T034, T034a, T035-T037 pass.

**Checkpoint**: all three replay-admission paths — exact-equal, exact-different, legacy/stale
heuristic — are complete and independently provable.

---

## Phase 6: User Story 4 - A re-parked delivery says why the request failed (Priority: P3)

**Goal**: a re-parked delivery's dead-letter reason and category are the request's own recorded or
mapped values, never the generic `DELIVERY_LIMIT_EXHAUSTED`.

**Independent Test**: park a request with a non-transient failure (e.g. `OUTBOUND_CONTRACT_VIOLATION`
or `PAYLOAD_READ_REFUSED`); trigger a re-park; read the dead-letter reason and description.

### Tests for User Story 4 (write first) ⚠️

- [ ] T039 [P] [US4] Write failing case: a request parked `non-transient` with a recognised failure
      reason, re-parked, dead-letters with reason `non-transient` and that failure reason as detail
      (FR-009).
- [ ] T040 [US4] Write failing case: a request parked `exhausted` with a transient failure reason,
      re-parked, dead-letters `exhausted` with that reason as detail; the **first** parking of an
      exhausted request is unchanged (`exhausted`/`DELIVERY_LIMIT_EXHAUSTED`).
- [ ] T041 [P] [US4] Write failing case: a row with no recorded category (legacy, or stale per
      FR-005a) is re-parked using `FailureCategoryMapping.categoryFor` on its stored reason, never a
      stale recorded category.
- [ ] T042 [US4] Write failing case: a row with a missing or unrecognised stored failure reason is
      re-parked as `exhausted`/`UNEXPECTED_FAILURE`, logs a sanitised data-anomaly error, never
      emits the unrecognised text, never throws.

### Implementation for User Story 4

- [ ] T043 [US4] `application/IdempotencyGuard.java`: wire `ReasonCode.fromCode`/
      `FailureCategoryMapping.categoryFor` into both re-park branches (T026's exact-equal branch,
      T038's legacy branch), per data-model.md's decision table, replacing the generic exhaustion
      reason. Depends on T026, T038, T011, T012. Makes T039-T042 pass.

**Checkpoint**: every re-park, exact or legacy, reports the request's own recorded or mapped reason
and category. All four user stories and FR-001–FR-015 are behaviourally complete.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T044 [P] Review `doc/DEFECT-FIXES.md` row C17's pinning-test note for wording only (no rewrite —
      Constitution Check, Principle I); confirm `RequestDedupeIT`'s "distinct `messageId`s" framing
      still holds under the new decision path.
- [ ] T045 [P] Review/update `specs/001-court-register-port/spec.md` (User Story 2's narrative and
      acceptance scenario 4) and `specs/001-court-register-port/data-model.md` (processed-log
      constraint summary, state-transition reference) to defer to this feature's amended table.
- [ ] T046 [P] Review narrative-only mentions of "fresh `messageId`"/"the resubmission runbook" in
      `e2e/DuplicateDetectionIT`, `e2e/RequestDedupeIT`, `e2e/MessageAccountingIT` comments and
      javadoc; confirm `ServiceTestSupport.publish`'s fresh-`messageId` convention is re-justified as
      bypassing broker dedupe for test isolation, not as a replay requirement — correct the wording
      where it still claims the latter.
- [ ] T047 [P] Review `README.md` and any other location the spec's "Documents the implementation must
      update" names for a restated fresh-`messageId` rule.
- [ ] T048 Run `specs/007-replay-by-sequence-number/quickstart.md` end to end: the manual portal-style
      re-send reproduction, the legacy-heuristic reproduction, and the full verification commands.
- [ ] T049 Final gate: `./gradlew checkstyleMain checkstyleTest pmdMain pmdTest jacocoTestReport build`
      green; confirm the JaCoCo ratchet (LINE ≥ 0.88 / BRANCH ≥ 0.85) is not loosened by this feature.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies — can start immediately, and MUST land before Foundational's
  implementation tasks touch guard behaviour (Governance/FR-013).
- **Foundational (Phase 2)**: depends on Setup. BLOCKS every user story — none of T018-T043 compiles
  without the schema, domain fields and config this phase adds.
- **User Story 1 (Phase 3, P1)** and **User Story 2 (Phase 4, P1)**: both depend on Foundational.
  **Not independent of each other** in this feature, unlike the usual speckit guidance — they are
  the two complementary halves of one rewritten statement (`REPLAY_FAILED`) and one rewritten method
  (`replayOrPark`'s exact-row branch). Implement Phase 3 first (it lands the rewritten statement and
  method), then Phase 4 as the dedicated proof of the protection that implementation already carries.
- **User Story 3 (Phase 5, P2)**: depends on Phases 3-4 (adds a branch to the same method) and on
  T014 (`legacyMargin`).
- **User Story 4 (Phase 6, P3)**: depends on Phases 3-5 (wires the reason/category lookups into every
  re-park branch those phases created) and on T011/T012.
- **Polish (Phase 7)**: depends on all four user stories being complete.

### Parallel Opportunities

- Every `[P]` task within Phase 2's Tests block, and within its Implementation block, touches a
  different file and can run in parallel once the phase starts.
- T018-T023 and T019a (US1 tests) are independent files and can run in parallel; T024 depends on
  T017's snapshot wiring being in place to assert against the emulator meaningfully.
- T032-T034, T034a and T036 (US3 tests) are independent and can run in parallel; T035 and T037 are
  not marked `[P]` since they share fixture state with their neighbours within the same suite.
- Phase 7's T044-T047 are independent review tasks and can run in parallel.

---

## Implementation Strategy

### MVP

Phases 1-4 (Setup, Foundational, US1, US2) are the MVP: a parked request becomes recoverable by an
unchanged dead-letter re-send, with the pre-existing same-sequence protection intact. US1 and US2
cannot usefully ship separately here — stop after Phase 4, not after Phase 3, and validate both
independent tests before deciding whether to continue.

### Incremental Delivery

1. Phases 1-2 → foundation ready, no behaviour change yet.
2. Phases 3-4 → MVP: replay works, the parking delivery is still never re-run. Deploy/demo.
3. Phase 5 → requests parked before this change (or by an older consumer mid-rollout) are
   recoverable too.
4. Phase 6 → the dead-letter queue stops lying about why a request failed.
5. Phase 7 → documentation and cross-references catch up; final gate.

---

## Notes

- `[P]` tasks touch different files with no dependency on an incomplete task.
- Verify every test fails for the right reason — an assertion failure, never a compile error —
  before writing the implementation that makes it pass; quote both runs in commit narrative.
- Commit after each task or logical pair; never two committing agents at once in this tree.
- Stop at any checkpoint to validate a story independently before continuing.
- A task that finds itself wanting a `C`/`P` defect-fix number has found a defect in an earlier
  increment, not in this one — stop and ask rather than appending a row.
