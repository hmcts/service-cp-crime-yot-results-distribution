# Workflow: Mandatory Build Loop

Every non-trivial code change MUST follow this cycle:

```
Contract → Failing test → Write → Code Review (agent) → QA (agent) → Contract Validate (agent) → Fix → Ship
```

- **Contract:** this service has three contracts:
  1. the **inbound ASB message schema** — the JSON schema under `src/main/resources/contracts/`
     (semantics documented on the Confluence design page);
  2. the **register document** — the `courtRegisterDocument/*` schemas frozen at
     `criminal-court-public-model` 17.103.13, `additionalProperties: false`, vendored under
     `src/main/resources/contracts/progression/` and enforced at the write into the register store
     (in 001 these were progression's `add-court-register` command; progression no longer receives
     it, and the schemas stay frozen); and
  3. the **operations API** — `src/main/resources/yot-results-distribution-openapi.yaml`, owned here since increment 005 and
     versioned with the repo. It describes every `/operations/**` endpoint, its body, its success
     shape and every bounded `reason` it refuses under; a contract test asserts the controllers
     against it, and `cp-audit-filter-springboot` reads it at runtime to resolve path parameters, so
     an endpoint missing from it is an endpoint whose audit event is wrong.

  Update the contract documentation BEFORE writing code that changes any of the three shapes. A
  change to the register document is a change to somebody else's frozen contract: never widen it
  locally. A defect fix that changes a register-document component's shape is a cross-team event
  (constitution Principle III). A new `/operations/**` path that is not a named operator action is a
  **constitution amendment**, not a spec.
- **Contract Validate:** run the `spec-validator` agent to check the code against the message
  contract, the frozen register schemas, the OpenAPI document, and the contract and defect-fix gates
  (below).

Loop repeats until ALL agents return PASS / COMPLIANT.

## What Requires the Loop

| Must Go Through Loop                            | Exempt                         |
|-------------------------------------------------|--------------------------------|
| New / modified Java class                       | Markdown / docs only           |
| New / modified test class or golden fixture     | Whitespace / import only       |
| Message-contract or schema change               | CLAUDE.md and rule updates     |
| New / modified `/operations/**` endpoint        |                                |
| `yot-results-distribution-openapi.yaml` or `acl/*.drl` change |                                |
| Flyway migration                                | README changes                 |
| ASB consumer / settlement configuration         |                                |
| Dockerfile changes                              |                                |
| CI/CD pipeline config                           |                                |
| Helm chart or values                            |                                |

## TDD is Non-Negotiable

- Write the failing test first; confirm it fails for the *correct* reason (assertion failure, not a
  compilation error)
- Then write the minimum production code to make it pass
- Then refactor with the test still green
- Commit history MUST show the failing test was authored at or before the production code —
  **every commit**, no exceptions, no "tests to follow"

## Gates (replace the API-first gate)

A change ships only when all applicable gates are green:

1. **Message-contract gate.** The inbound message is parsed and validated against the documented
   schema. Unknown fields, missing required fields, and unparseable messages have explicit,
   tested behaviour, and all of it is dead-lettering with a reason — never a silent drop. The
   contract is **closed** (`additionalProperties: false`, per the schema and FR-002): an unknown
   extra field is a contract violation and dead-letters like
   any other, because tolerating it would hide producer drift until it mattered. The offending
   field's name is never quoted back (producer-chosen text); the reason code is.
2. **Settlement gate.** Every path through the message listener performs exactly one explicit
   `complete()` / `abandon()` / `deadLetter()`. Tests must cover the success, transient-failure and
   non-transient-failure paths. A path that can return unsettled fails review.
3. **Idempotency gate.** Redelivery of the same `(source, requestId)` must not produce a second
   submission. Proven by test, not by inspection.
4. **Golden gate.** Every ported behaviour has a JUnit twin of the corresponding Jest case; twins
   whose legacy behaviour a fix changes are re-pointed at the FIXED behaviour and named in the fix's
   register row. Golden files encode fixed behaviour; a golden changes only in the same commit as a
   `doc/DEFECT-FIXES.md` entry.
5. **Defect-fix gate.** Every catalogued defect (C1–C34) is fixed and has a `doc/DEFECT-FIXES.md`
   row naming its pinning test; a reproduced catalogued defect fails the gate. Any difference from
   function-app behaviour that is NOT catalogued needs written sign-off before merge — "it looked
   wrong so I fixed it" is a failed gate; the differential audit fails on any unexplained
   difference.
6. **No-swallowed-exception gate.** No empty catch, no catch-and-continue, no success returned from
   a catch block. Reviewers reject on sight.
7. **No-PII gate.** No defendant PII (names, addresses, DOB, ASN, URN) at `info` level or above.
8. **Operations-API gate.** Every `/operations/**` endpoint satisfies all four conditions of
   constitution Principle III — authorised by an explicit drools allow rule, audited, reading the
   flag exactly where the command it replaced read it (override recorded in the audit event and on
   the run report), and answering in bounded codes with no operator input echoed. An endpoint with
   no rule, no OpenAPI entry, or a `ProblemDetail` carrying exception text fails the gate.

## Agent Definitions

### code-reviewer (Read only)
- Spawned as sub-agent with Read-only tools
- Analyses code for: logic errors, null safety, ports-and-adapters violations (infrastructure types
  leaking into the application layer), swallowed exceptions, unsettled message paths, secrets, PII in
  logs, `System.out` usage
- Returns: **PASS** or **NEEDS CHANGES** with severity-rated findings
- NEVER modifies code — reports only

### qa (Read only)
- Spawned as sub-agent with read-only tools; `Bash` for inspection and for running the existing
  suite only
- Judges the tests that exist against the test matrix in `.claude/agents/qa.md` (JUnit Jupiter + Mockito +
  AssertJ; Testcontainers `servicebus-emulator` and Postgres; WireMock for HTTP stubs) and names
  every gap
- Verifies TDD discipline (test tasks ordered before implementation tasks; a red run recorded before
  the green run)
- Runs `./gradlew test`
- Returns: **PASS** or **FAIL** with test results and missing-coverage findings
- NEVER writes tests and NEVER fixes production code — reports only

### software-engineer (Full access)
- For full feature implementation tasks
- **Writes every test**, test-first, under the TDD rule below — the `qa` agent reviews them, it does
  not author them
- Follows all rules in `technical-rules.md` and `design_rules.md`
- Runs `./gradlew build` after changes

### spec-validator (Read only)
- Spawned as sub-agent with Read-only tools
- Checks:
  - the inbound message record and its validation against the JSON schema under `src/main/resources/contracts/`
  - the register document against the vendored frozen schemas (no extra fields —
    `additionalProperties: false`)
  - **the operations API against its four conditions** (constitution Principle III), in place of the
    old "absence of REST" check: every `/operations/**` endpoint is described in
    `src/main/resources/yot-results-distribution-openapi.yaml` and matches its controller; every action has an explicit allow
    rule in `src/main/resources/acl/operations-rules.drl` naming the groups admitted, and no action
    is reachable without one; every endpoint is inside the audit filter's scope; the flag is read at
    exactly the point the CLI command it replaced read it and an override is recorded in the audit
    event and on the run report; and every response — success and refusal alike — carries bounded
    codes, counts and identifiers only, with no operator input echoed back
  - settlement discipline: one explicit settlement on every listener path
  - state-machine completeness: every terminal path persists a status before settling
  - golden fixtures present and referenced; the defect-fix register consistent with the assertions (every row's pinning test exists and passes)
- Returns: **COMPLIANT** or **DRIFT DETECTED** with severity-rated findings
- NEVER modifies code — reports only

### research (Read, Glob, Grep, WebSearch)
- For deep codebase investigation, including the Node source under
  `cpp-context-azure-legalaidagency/azure-functions/durable-functions/` when establishing parity
- Cross-references design documents
- Returns structured findings with citations

## Critical Principle

**Agents are reporters, not fixers.** The parent agent (or developer) reads agent reports and applies
all fixes. This prevents conflicting changes and keeps the team in control.
