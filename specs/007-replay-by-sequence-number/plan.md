# Implementation Plan: Replay a parked request by broker sequence number

**Branch**: `007-replay-by-sequence-number` | **Date**: 2026-11-20 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/007-replay-by-sequence-number/spec.md`

## Summary

`IdempotencyGuard.replayOrPark` currently tells a deliberate resubmission of a `FAILED` request
apart from a redelivery of the delivery that parked it by comparing the delivery's broker
`messageId` against the row's `exhausted_message_id`. Because the Azure portal's dead-letter
"Re-send" — the only recovery tool support has, the operations API having no replay surface by
design (increment 005, Out of Scope) — copies the message including its `messageId`, every real
resubmission reads as a redelivery and is re-parked, unrecovered, under the generic reason
`exhausted / DELIVERY_LIMIT_EXHAUSTED`, hiding whatever the request actually failed with.

This plan replaces that test with the broker-assigned **sequence number**, which a re-send always
changes and a redelivery never does, decided as a null-safe compare-and-set against the exact
failure generation observed (`attempts` and `parking_sequence_number` together, FR-006) so a stale
decision can never replay a newer failure; adds a two-condition heuristic for rows with no recorded
parking sequence number (parked before this change); adds a **third** recorded field,
`parking_attempt_count`, beyond the `service-cp-crime-informant-register` precedent, so a row an
older consumer re-parked during a rolling deployment is judged by the same heuristic rather than
read as exact (FR-005a — this repository's own addition, research.md R6); makes a re-park report
the request's own recorded failure reason and category instead of the generic exhaustion reason
(FR-009/FR-010); and consolidates the listener's broker-metadata reads — `message.getDeliveryCount()`
is read three times per delivery today — into one capture inside the existing settlement boundary
(FR-011/FR-012). The replay rule stated in `.claude/rules/design_rules.md`,
`.claude/rules/technical-rules.md` and four `.claude/agents/*.md` files is relaxed to match, per the
rule-and-agent-documentation delta in `contracts/replay-rule-delta.md` (FR-013) — this repository
has no `doc/API_CONTRACTS.md` for that delta to land in, unlike the informant-register precedent.

Technical approach: one additive Flyway migration (`V9`, three nullable columns, two constraints);
the existing `DeliveryIdentity`/`RunClaim`/`ProcessedRequestRecord` records each gain the broker
facts the decision needs (data-model.md); `IdempotencyGuard.replayOrPark` is rewritten per the
decision table there; `ProcessedRequestRepository`'s `READ_RECORD`, `RECORD_FAILED` and
`REPLAY_FAILED` statements change to match; `YotResultsDistributionMessageListener` gains a single
broker-delivery snapshot built once per delivery, replacing the repeated `getDeliveryCount()` calls;
two new small pure lookups (`ReasonCode.fromCode`, `FailureCategoryMapping.categoryFor`) resolve the
re-park reason/category; one new `lock-duration` setting (mirroring the broker queue's own, beside
`max-delivery-count`) and one new `Replay.legacyMargin` setting, each validated at start-up. No
register content, no outbound contract (progression-post mode's `add-court-register`), and no
payload handling is touched — this is entirely the processed-log's own state machine, the one area
this feature touches having no legacy oracle at all (Constitution Check, Principle I).

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.1 (Gradle wrapper; Maven forbidden, Principle VIII).

**Primary Dependencies**: none added. `azure-messaging-servicebus`
(`ServiceBusReceivedMessage#getSequenceNumber()`/`getEnqueuedTime()`, read only in the inbound
adapter); Spring's `JdbcClient` over hand-written SQL (no ORM — "the affected-row count is the
decision", `ProcessedRequestRepository`'s own file-level javadoc); Flyway; SLF4J + Logback.

**Storage**: PostgreSQL. One additive migration (`V9`) on the existing `processed_request` table
(`V1`–`V8` exist today; `data-model.md`); `processed_output` is untouched.

**Testing**: JUnit Jupiter + Mockito + AssertJ; Testcontainers (PostgreSQL; the Service Bus emulator
via the existing `*IT` support, `docker/servicebus-emulator/config.json`). The existing suites this
feature extends: `FailedReplayIT`, `IdempotencyGuardIT`, `SchemaMigrationIT` (persistence);
`MessageListenerSettlementTest`, `SettlementFailureEdgeTest` (inbound); `DeliveryExhaustionIT`,
`QueueSettlementIT`, `DuplicateDetectionIT`, `RequestDedupeIT`, `MessageAccountingIT` (e2e, real
emulator). No new `*IT` class is anticipated (research.md R7).

**Target Platform**: AKS via Flux (deployed); Docker Compose (local). No platform change.

**Project Type**: single Spring Boot service, message-driven, no business REST surface (constitution
Principle III) — the existing repository layout is used as-is.

**Performance Goals**: none new. The broker-metadata capture must not add a second AMQP round trip —
it replaces repeated in-process accessor calls with a single capture, never a call to the broker.
The replay/re-park decision adds one additional indexed-by-PK column comparison to statements
already keyed on `(source, request_id)`; no new query shape, no new index.

**Constraints**: the rule-and-agent-documentation delta (`contracts/replay-rule-delta.md`, FR-013)
lands before, or in the same reviewed change as, the first production code that relaxes the guard
(constitution Governance). TDD (Principle II) — every FR-015 bullet is a test written before its
implementation. Explicit failure (Principle VI) — the broker-metadata consolidation must still
settle every delivery exactly once, including when a required accessor throws. No PII (Principle
VII) — sequence number, delivery count and enqueue time are broker facts, not personal data, and
`sequenceNumber`/`deliveryCount` are already logged today. Estate conventions (Principle VIII) —
Checkstyle/PMD/JaCoCo gates apply to every new and changed class; Flyway, never Liquibase;
Conventional Commits; no AI attribution.

**Scale/Scope**: four user stories, fifteen functional requirements (FR-001–FR-015, with FR-005a),
six success criteria. Six existing production classes changed (`DeliveryIdentity`, `RunClaim`,
`ProcessedRequestRecord`, `ProcessedRequestRepository`, `IdempotencyGuard`,
`YotResultsDistributionMessageListener`), two small new domain-level pure types, one new
`YotResultsDistributionProperties.Replay` record plus a `lock-duration` field on the existing
`Servicebus` record, one new `PropertiesValidator` start-up check, one additive Flyway migration,
and a double-digit number of test classes extended. Nine documents outside `src/` are updated as
implementation tasks (the spec's "Documents the implementation must update", carried into
`tasks.md` unchanged).

### Configuration (this increment)

| Property | Before | After | Note |
|---|---|---|---|
| `yotresultsdistribution.servicebus.lock-duration` | — | **new**, default `1m` | Mirrors the broker queue's own `LockDuration` (`docker/servicebus-emulator/config.json`'s `PT1M`), beside `max-delivery-count` for the same reason: must be changed here and on the queue together. Validated at start-up against the new legacy margin (FR-005). |
| `yotresultsdistribution.replay.legacy-margin` | — | **new**, default `5m` | The FR-005 heuristic's safety margin. Refused at start-up if shorter than `servicebus.lock-duration` (research.md R5). |

No existing key changes shape or default; `servicebus.max-auto-lock-renew-duration` (5m) is
unaffected and is not reused for this feature (research.md R5 explains why it cannot stand in for
the new `lock-duration` setting).

## Constitution Check

*GATE: evaluated against constitution v5.3.0 (2026-11-19, PR #54). Re-checked after Phase 1 design
below — no principle verdict changed.*

| # | Principle | Verdict |
|---|---|---|
| I | Defect-Fix-First with Characterised Legacy Behaviour | **PASS — not applicable to this feature's own change.** The legacy function app had no processed log, no parking and no replay (spec Context); there is no oracle for this feature to differ from, and no C-number is added. `doc/DEFECT-FIXES.md` row C17 is reviewed for wording only (its pinning-test note stays true regardless of how a *parked* request's replay is decided — `contracts/replay-rule-delta.md` §5) and is **not** rewritten. |
| II | Test-Driven Development | **PASS.** FR-015 is an exhaustive, spec-authored test list; `/speckit-tasks` must order every bullet as a test task before the implementation task it guards, per this repository's existing TDD red-run convention (`CLAUDE.md`, "Repository Conventions"). |
| III | Message-Contract First | **PASS.** FR-013 is explicit that the inbound body schema is untouched — this feature is entirely broker-property and processed-log state, not the message body. No endpoint is added to the operations API; the `YotResultsDistributionService` flag gains no new reader. The replay-rule documentation that *does* change (`.claude/rules/*`, `.claude/agents/*`) is not one of this principle's three contracts, but the same "documentation before code" discipline is applied to it via Governance and `contracts/replay-rule-delta.md`. |
| IV | Canonical JSON In, Typed Models Out | **N/A — untouched.** This feature is entirely about broker delivery identity and the processed-log state machine; the hearing payload (`JsonNode`) is never read differently. |
| V | SOLID with Ports and Adapters | **PASS, with one design decision protecting it.** The new `BrokerDeliverySnapshot` (research.md R3) is built and consumed entirely inside `inbound/YotResultsDistributionMessageListener` — never passed to `DistributionPipeline` or `IdempotencyGuard`. Those two see only the existing, enlarged `DeliveryIdentity` record (`domain`), which already crosses this boundary today. `code-reviewer` must specifically check that no `ServiceBusReceivedMessage` (or its package) appears outside `inbound/` once this lands. |
| VI | Explicit Failure — Nothing Is Ever Swallowed | **PASS, and this is the point of FR-011/FR-012.** The snapshot is built inside the existing total `try`/`catch RuntimeException` boundary in `decide`/`examine` (lines 293-333 today), under the same catch-all that already classifies "anything else at all" as `unexpectedFailure` → exactly one settlement. The one new rule: a failure to build the snapshot can never determine `finalPermittedDelivery`, so FR-012 requires it always `abandon()`s, never `deadLetter()`s, even on what might be the last delivery — the conservative choice, stated explicitly so a reviewer does not "fix" it into an escalation. This repository's guard has **no** final-delivery escalation of an admission hand-back for any reason today (unlike the informant service), and this feature does not add one (spec, Out of Scope) — FR-006's `REPLAY_NOT_ADMITTED` hand-back is therefore unchanged in that respect. |
| VII | Privacy in Telemetry | **PASS.** Sequence number, delivery count and enqueue time are broker facts about the message, not about a person. Nothing from `failure_reason` or `parking_category` is free text; both are bounded codes already subject to the no-PII gate. Every defendant on this register is a youth (Principle VII); nothing here touches a defendant field. |
| VIII | Estate Conventions | **PASS.** One additive Flyway migration (`V9`, forward-only, FR-014). No new static-analysis suppressions anticipated; if the two new pure lookups need one (unlikely — small switch expressions), it must be inline, narrow and reasoned. |

No violations. Complexity Tracking is not filled in.

## Project Structure

### Documentation (this feature)

```text
specs/007-replay-by-sequence-number/
├── plan.md              # This file
├── research.md           # Phase 0 output
├── data-model.md         # Phase 1 output
├── quickstart.md         # Phase 1 output
├── contracts/
│   └── replay-rule-delta.md   # Phase 1 output — the rule/agent-doc text delta (FR-013)
├── checklists/
│   └── requirements.md   # already present, from PR #54
└── tasks.md               # Phase 2 output (/speckit-tasks — not created by this command)
```

### Source Code — additions and changes

**New**

```text
domain/FailureCategoryMapping.java     NEW — pure, FR-010's reason→category table
resources/db/migration/V9__add_parking_sequence_category_and_attempts.sql   NEW — additive only
```

**Changed — domain**

```text
domain/DeliveryIdentity.java         + sequenceNumber, deliveryCount, enqueueTime
domain/RunClaim.java                 + sequenceNumber
domain/ProcessedRequestRecord.java   + parkingSequenceNumber, parkingCategory, parkingAttemptCount,
                                      updatedAt
domain/ReasonCode.java               + fromCode(String): Optional<ReasonCode>
```

**Changed — application, persistence, inbound, config**

```text
application/IdempotencyGuard.java                      replayOrPark rewritten per data-model.md's
                                                        decision table; recordNonTransientFailure/
                                                        recordExhaustion javadoc drops "a fresh
                                                        identity replays"
persistence/ProcessedRequestRepository.java            READ_RECORD, RECORD_FAILED, REPLAY_FAILED
                                                        statements changed (data-model.md); REPLAY_FAILED
                                                        javadoc drops "under a fresh message identity"
inbound/YotResultsDistributionMessageListener.java     BrokerDeliverySnapshot captured once per
                                                        delivery (research.md R3); process,
                                                        identityOf, isFinalPermittedDelivery updated
                                                        to read it instead of the SDK message
config/YotResultsDistributionProperties.java           Servicebus + lockDuration; + Replay(legacyMargin)
config/PropertiesValidator.java                        + margin-vs-lockDuration start-up check
resources/application.yaml                             the two new keys, with the house-style
                                                        "mirrors the broker queue's own setting"
                                                        comment for lock-duration
```

**Changed — tests**

```text
persistence/{FailedReplayIT,IdempotencyGuardIT,SchemaMigrationIT}.java        extended per FR-015
inbound/{MessageListenerSettlementTest,SettlementFailureEdgeTest}.java       extended
e2e/{DeliveryExhaustionIT,QueueSettlementIT,DuplicateDetectionIT,
     RequestDedupeIT,MessageAccountingIT}.java                               extended (emulator-
                                                                              backed FR-015 bullets
                                                                              in the first two;
                                                                              narrative-only touch-up
                                                                              in the last three, which
                                                                              cite "the resubmission
                                                                              runbook")
support/ServiceTestSupport.java, test delivery-building helpers              gain a distinct sequence
                                                                              number per synthetic
                                                                              delivery
domain/{ReasonCodeTest,FailureCategoryMappingTest} (new)                     total/never-throws tests
                                                                              for the two lookups
```

**Documentation** (FR-013, spec's "Documents the implementation must update"):
`.claude/rules/design_rules.md`, `.claude/rules/technical-rules.md`,
`.claude/agents/{code-reviewer,qa,software-engineer,spec-validator}.md`,
`src/main/resources/db/migration/V1__create_processed_log.sql`'s comment is **not** edited (an
applied migration cannot be edited) — the new migration's own comment supersedes it in intent;
`specs/001-court-register-port/{spec.md,data-model.md}`; `doc/DEFECT-FIXES.md` row C17 (reviewed,
not rewritten — see Constitution Check, Principle I).

**Structure Decision**: no structural change to the repository — this feature is entirely
additive/modifying within the existing `domain → application → persistence`/`inbound` layering
`design_rules.md` already mandates. `FailureCategoryMapping` goes in `domain/` because it is a
closed-vocabulary lookup with no I/O, consistent with `ReasonCode` and `DeadLetterReason` already
there. The new `Replay` config record follows the existing nested-record convention on
`YotResultsDistributionProperties` (`Claim`, `Store`, `Notification`, …); `lock-duration` joins
`Servicebus` rather than `Replay`, because it mirrors a broker *queue* setting the way
`max-delivery-count` already does, not a *replay-policy* setting — `legacy-margin` is the latter.

## Test Matrix

| Area | Suite | What it holds |
|---|---|---|
| The decision table | `persistence/IdempotencyGuardIT`, `persistence/FailedReplayIT` | Same sequence number re-parks; different sequence number with the **same** `messageId` replays (FR-003/FR-004); a stray/delayed copy arriving after a newer failure is already recorded is admitted and replays the current failure once — the **independent-enqueue policy** (Edge Cases; T019a, distinct from the stale-decision refusal below); legacy/stale record heuristic — count 0 and enqueued after parking plus margin replays, count ≥ 1 re-parks, enqueued before or within the margin re-parks, no enqueue time re-parks, an enqueue time exactly equal to parking-plus-margin re-parks, and a simulated broker-clock lead past the margin still replays — the one case FR-005 accepts rather than guards against, demonstrated rather than narrated (FR-005; T034a); parking fields left by an earlier parking under a row re-parked by an older consumer (`attempts` moved on) judged by the legacy rules, not as exact (FR-005a); the **replay** half of that same rolling-deployment case — a stale triple surviving non-null on a `RECEIVED` row without violating the schema constraint (FR-014; T037); two concurrent re-sends admit one replay, the FAILED→RECEIVED→FAILED race cannot replay a newer failure (the stale-decision refusal, `REPLAY_NOT_ADMITTED`), the compare-and-set matches a null parking sequence correctly (FR-006); a stale runner cannot overwrite the parking metadata (FR-002; T006a); a re-park carries the recorded category and reason, a missing category uses the mapping, every non-transient reason code in the mapping, a missing/unrecognised reason uses `exhausted / UNEXPECTED_FAILURE` without throwing (FR-009/FR-010). |
| The schema | `persistence/SchemaMigrationIT` | `V9` leaves existing rows valid; the parking-triple check constraint; the category vocabulary check. |
| The listener | `inbound/MessageListenerSettlementTest`, `inbound/SettlementFailureEdgeTest` | Each broker accessor called at most once (FR-011); a failure of message retrieval or of any required accessor still makes exactly one settlement attempt, and never `deadLetter()`s on an undetermined final-delivery position (FR-012); an absent enqueue time continues normally. |
| Configuration | `config/ConfigurationValidationTest` | A configured margin shorter than the configured lock duration is rejected at start-up (FR-005). |
| The lookups | `domain/ReasonCodeTest`, `domain/FailureCategoryMappingTest` (new) | `fromCode` total over every declared code, empty on anything else; `categoryFor`'s table matches the seven reason codes the non-transient path can record today, everything else `EXHAUSTED`, never throws. |
| Against the real emulator | `e2e/QueueSettlementIT`, `e2e/DeliveryExhaustionIT` (extended, research.md R7) | The sequence number is unchanged across an abandon; a re-send gets a new one; an unchanged re-send made after the duplicate-detection window recovers a parked request end to end. |
| Output write-idempotency under replay | `persistence/FailedReplayIT` or `e2e/DuplicateDetectionIT` | A replay in `record` mode of a request whose register is already recorded records nothing new, and in `progression-post` mode of one already `POSTED` sends nothing (unchanged behaviour, re-asserted under the new decision path). Not to be confused with the independent-enqueue *policy* row above, which is about accepting a distinct delivery as its own unit of work, not about output idempotency. |
| Narrative-only | `e2e/DuplicateDetectionIT`, `e2e/RequestDedupeIT`, `e2e/MessageAccountingIT` | Cite "the resubmission runbook requiring a fresh `messageId`" in comments/javadoc only; reviewed for wording, no behavioural change expected (`ServiceTestSupport.publish`'s fresh-`messageId` convention stays true for an unrelated reason — bypassing broker dedupe for test isolation — per the spec's documents list). |

## Complexity Tracking

*No entries — the Constitution Check above found no violation requiring justification.*
