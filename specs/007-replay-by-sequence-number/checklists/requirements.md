# Specification Quality Checklist: Replay a parked request by broker sequence number

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-11-19
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs) - the broker concepts (sequence
      number, delivery count, `messageId`, dead-letter reason) are the domain vocabulary of a change
      to message-transport identity, not leakage; no class, method, statement or setting key is
      named in a requirement
- [x] Focused on user value and business needs - a parked court register that support can recover
      with the tool it has, and a dead-letter queue that says why
- [x] Written for non-technical stakeholders - qualified: the user stories, success criteria and
      out-of-scope list read for support and the service owner, but the requirements are written in
      broker and processed-log vocabulary for the engineers and reviewers who must apply them, as in
      the informant specification
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified - including the rolling-deployment re-park overlap found during the
      informant implementation, and this service's own: intake suspension during a store outage,
      write-time supersession, the cutover-flag label and the 07:00 report
- [x] Scope is clearly bounded - the operations API gains no replay surface, and the missing
      final-delivery escalation of an admission hand-back is named as out of scope rather than
      silently inherited from the informant service
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Ported from the informant-register specification of the same name (PR #79), taking in the two
  amendments its implementation (#80) made to it: the rolling-deployment re-park overlap (now an edge
  case and part of FR-014) and the "legacy margin" wording. The final-delivery escalation of
  `REPLAY_NOT_ADMITTED` that #80 added to FR-006 is **not** ported: this service's guard has no such
  escalation for any admission hand-back, and adding one is a separate decision.
- Every statement about current behaviour was checked against this repository's code. The
  differences from the informant specification are deliberate and follow the code: the
  non-transient reason list in FR-010 is this service's seven codes; FR-011/FR-012 say the message
  and its metadata are already read inside the settlement boundary here, the defect being repeated
  reads and the missing sequence number and enqueue time; "authorities skipped" becomes "a register
  already recorded or `POSTED` is not recorded or sent again", there being one output per request;
  the lock duration is 1 minute, not 5.
- The fresh-`messageId` rule is in this repository's constitution, so the specification carries a
  Governance step-1 amendment proposal (MINOR, 5.2.0 → 5.3.0) in the style of increment 005. The
  design owner ratified it, so the amendment itself is made in this PR (constitution 5.3.0); the
  rule and agent files that restate the old rule follow in the implementation.
- There is no `doc/API_CONTRACTS.md`, `doc/DEVIATIONS.md` or `doc/CHANGELOG.md` here; the inbound
  contract is the JSON schema plus Principle III, and the schema needs no change. The defect-fix
  register needs no new C-number (the legacy function app had no replay); the C17 row is flagged for
  review only.
- **Review iteration 2 (2026-11-19).** An independent review found the start-up floor in FR-005
  had nothing to compare against (this service has no lock-duration setting, only the renewal
  window); FR-005 now adds a mirroring setting. Also applied: the rollout rule says "remains in
  service", not "resumes"; the claimed absence of an in-band signal was wrong, and the parking
  attempt count is adopted as one (FR-001, FR-005a); clearing on leaving `FAILED` is a write rule,
  not a constraint (FR-014); the supersession edge case now covers an already-batched newer share;
  the intake-age effect of replaying an old row and the stray copy of a double Re-send are noted;
  the documents list gains the second `spec-validator` bullet, the configuration files, the source
  comments and the uneditable V1 comment, with the swept tests recorded; and the missing
  final-delivery escalation is recorded as an existing gap with a recommended follow-up. Items
  re-ticked after those changes, every statement about current code re-checked.
- The schema is described at entity level only (what is recorded and when); column types, migration
  numbering and SQL are left to the plan.
- Success criteria are stated as operator-observable outcomes (replayed vs re-parked, duplicate
  registers, dead-letter reason shown, settlement attempts per delivery).
