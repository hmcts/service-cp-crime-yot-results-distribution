# Contract delta: the replay rule (FR-013)

This service has no OpenAPI surface for this feature — the operations API
(`src/main/resources/yot-results-distribution-openapi.yaml`) is unaffected, and FR-013 is explicit
that the inbound **body** schema does not change: "the rule concerns broker properties, not the
body." There is also no `doc/API_CONTRACTS.md` in this repository (unlike
`service-cp-crime-informant-register`, which holds the analogous delta there) — `CLAUDE.md` states
plainly that this repo carries no design narrative of that kind. The artefact this feature's "rule
changes before the code" discipline (constitution Governance; FR-013) actually binds on is the
**rule and agent documentation** that states the replay rule today: `.claude/rules/design_rules.md`,
`.claude/rules/technical-rules.md`, and the four `.claude/agents/*.md` files. This document is that
delta, to be applied — **before, or in the same reviewed change as**, the first production code that
relaxes the guard's behaviour (never after) — exactly as `contracts/replay-rule-delta.md` is applied
against `doc/API_CONTRACTS.md` in the informant-register precedent.

## 1. `.claude/rules/design_rules.md` — two sites

### 1a. Processing State Machine, `FAILED` rule (currently lines 210-215)

**Before**:

```text
- `COMPLETED` and `FAILED` are **terminal**, and are not treated alike on a resubmission:
  - `COMPLETED` — acknowledged and `complete()`d without reprocessing.
  - `FAILED` — **replayable**. A resubmitted message (fresh broker `messageId`, same `requestId`)
    makes the guard transition `FAILED` → `RECEIVED`, preserving `attempts` and writing an audit
    note, then reprocess. This is the supported way to recover a dead-lettered request.
  - Ordinary broker redelivery of the *same* message is unaffected.
```

**After**:

```text
- `COMPLETED` and `FAILED` are **terminal**, and are not treated alike on a resubmission:
  - `COMPLETED` — acknowledged and `complete()`d without reprocessing.
  - `FAILED` — **replayable**. A resubmitted message — the dead-lettered body re-sent unchanged,
    `messageId` included, or under a fresh `messageId`, either now works — makes the guard
    transition `FAILED` → `RECEIVED` once the broker assigns it a sequence number different from
    the one that parked the request (or, for a request parked before this change, once the FR-005
    heuristic admits it), preserving `attempts` and writing an audit note, then reprocess. This is
    the supported way to recover a dead-lettered request (spec 007-replay-by-sequence-number).
  - Ordinary broker redelivery of the *same* message — same sequence number — is unaffected: it is
    a redelivery, not a resubmission, and a terminal request short-circuits it, re-parking with its
    own recorded reason rather than a generic one.
```

### 1b. Queue and Topic Semantics bullet (currently line 307)

**Before**:

```text
- **Replay tooling always mints a fresh `messageId`** and keeps the original `requestId`.
```

**After**:

```text
- **A fresh `messageId` is no longer required for a replay, only still useful.** The guard decides
  a resubmission by the broker's own **sequence number**, not `messageId`
  (spec `007-replay-by-sequence-number`), so the dead-lettered body may be re-sent unchanged — the
  Azure portal's dead-letter "Re-send" does exactly this. Minting a fresh `messageId` still bypasses
  the broker's duplicate-detection window immediately rather than waiting it out, and normal
  producer publishing keeps the deterministic `messageId` regardless.
```

## 2. `.claude/rules/technical-rules.md` — one site (currently lines 58-60)

**Before**:

```text
- `messageId` = `"{source}:{requestId}"`; broker duplicate detection on; replay tooling always mints a
  fresh `messageId`
```

**After**:

```text
- `messageId` = `"{source}:{requestId}"`; broker duplicate detection on; a replay is decided by the
  broker's own sequence number, not `messageId` — a fresh `messageId` is no longer required, only
  still useful to bypass the duplicate-detection window immediately
  (spec `007-replay-by-sequence-number`)
```

## 3. `.claude/agents/*.md` — four sites

- `code-reviewer.md` — the HIGH finding "Replay reusing the original `messageId`" is rewritten in
  the style of the informant-register precedent: a replay/re-park decision keyed on `messageId`
  becomes the drift; comparing sequence numbers against the recorded parking sequence number is the
  correct guard.
- `qa.md` — the matrix entry naming a reused `messageId` as "the footgun it is" is rewritten: a
  resubmission that reuses the original `messageId` but carries a different broker sequence number
  must be recognised as a deliberate resubmission, not a footgun.
- `software-engineer.md` — the ASB consumer bullet's "Any replay/resubmit mints a **fresh**
  `messageId`" is rewritten to state the sequence-number rule, as above.
- `spec-validator.md` — two bullets: the message-contract bullet requiring a fresh `messageId` on
  any replay, and the idempotency-contract bullet describing a resubmission as "fresh broker
  `messageId`, same `requestId`" — both rewritten to the sequence-number rule, with the
  `IS NOT DISTINCT FROM` compare-and-set (FR-006) named as what must be checked in its place.

## 4. New failure-behaviour statements (previously undocumented, FR-009)

No "before" text exists for this — every re-park today uses the single generic reason
`DELIVERY_LIMIT_EXHAUSTED` regardless of cause, which is itself part of the defect this feature
fixes (spec Context, third bullet). The rule and agent documents above gain a statement to this
effect once the implementation lands:

> A re-park (the delivery that parked the current `FAILED` generation coming round again, or a
> legacy/stale row failing the FR-005 heuristic) dead-letters with the request's own recorded or
> mapped category and reason — never `DELIVERY_LIMIT_EXHAUSTED` as a substitute for the original
> cause. A missing or unrecognised stored reason dead-letters as `exhausted / UNEXPECTED_FAILURE`
> and never throws.

## 5. Not part of this contract

- The inbound JSON body schema (`src/main/resources/contracts/distribution-command.schema.json`) is
  untouched — this feature is entirely about broker-level delivery identity and the processed-log's
  state machine, not about the message body's fields.
- The register document, the `add-court-register` command, the operations API and the
  `YotResultsDistributionService` flag are untouched (spec, Out of Scope).
- `doc/DEFECT-FIXES.md` row C17 is **not rewritten** by this delta; the spec records it as flagged
  for review only, because the pinning-test note's wording ("under distinct broker `messageId`s so
  the queue's own dedupe cannot answer for the guard") stays true regardless of how a *parked*
  request's replay is decided — C17 is about ordinary duplicate delivery, not about replay.
