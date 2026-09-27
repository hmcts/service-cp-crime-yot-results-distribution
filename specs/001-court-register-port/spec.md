# Feature Specification: YOT Results Distribution Service — full pipeline port, fix-first

**Feature Branch**: `main`
**Created**: 2026-08-31
**Status**: Approved for implementation
**Input**: User description: "Start implementation of courtregister-service per the design.
Fix all 34 defects and document them in a separate file in the git repo, referenced from
README.md. Use TDD, multiple git commits, review at every phase."

## Context

The court register is the record of youth-defendant hearing outcomes sent nightly to Youth
Offending Teams. Today the first half of that flow is a Node.js Durable Functions app
(`courtregister-azure-functions`) that swallows every failure: the final POST to the Progression
context inspects no status, the orchestrator reports `Success: true` on four distinct silent-skip
paths, and a youth defendant without an address makes Progression reject the whole register with a
400 that nobody ever sees — the register is simply lost (defect C29, the single strongest business
argument for this migration).

This service replaces that app with a Spring Boot queue consumer, cloning the delivery machinery
proven by `service-cp-crime-informant-register` (explicit settlement, durable idempotency guard,
suspend-on-store-outage, health honesty) and porting the register-building pipeline.

Unlike the informant port, this increment is **fix-first, not parity-first**: of the 34 defects
catalogued in the design defect register (C1–C34), the 31 that live in this service are fixed
outright and documented in `doc/DEFECT-FIXES.md`, each with its legacy behaviour, its fixed
behaviour and the test that pins the fix; the remaining three (C18/C28/C34) are externally-owned
remediations registered as PENDING with an owner and a trigger, tracked to conclusion before
cutover. Legacy behaviour remains the oracle for everything *not* catalogued; a behaviour change
without a DEFECT-FIXES entry is itself a defect. Fixes that change register content or recipient
sets are implemented now and flagged for business/progression sign-off **before cutover** — the
sign-off gates deployment, not implementation.

The transformation itself is materially simpler than the informant's (one fragment per hearing, no
authority fan-out, no CSV/Notify leg) but mapper-heavier: twelve outbound mappers building the
youth-defendant document that Progression batches per court centre and renders to PDF at 18:00.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A resulted hearing becomes a submitted court register, reliably (Priority: P1)

The producer announces each resulted hearing on the service's dedicated queue. The service fetches
the hearing payload (cache, then query fallback), builds the register fragment, matches Youth
Offending Team subscriptions from reference data, maps the outbound document, and POSTs it to the
Progression context. Success is `202 Accepted` and nothing else. Every failure is classified,
retried when transient, parked visibly when not, and always recorded.

**Why this priority**: this is the flow's reason to exist, and the failure taxonomy is the
migration's core improvement — today a failed submission is indistinguishable from a successful
one.

**Independent Test**: place one valid request on the queue with a hearing payload available and a
matching subscription; observe exactly one POST carrying the mapped document, a 202, the request
recorded COMPLETED with reason `submitted`, and a `processed_output` row recording POSTED with the
response code.

**Acceptance Scenarios**:

1. **Given** a valid request and a cached hearing payload with one youth defendant and one matching
   court-register subscription, **When** the request is processed, **Then** exactly one POST is
   made to `progression.add-court-register` with content type
   `application/vnd.progression.add-court-register+json` and the `CJSCPPUID` header carrying the
   command's user id, and a 202 completes the request with reason `submitted`.
2. **Given** the hearing payload is absent from the cache, **When** the request is processed,
   **Then** the results query API fallback is consulted (dated key form first, then the legacy
   undated twin against the cache; the query fallback carries no date parameter), and processing
   continues normally on a hit.
3. **Given** both the cache and the query fallback miss, **When** the request is processed,
   **Then** the outcome is a recorded transient failure and the message is returned for retry —
   never a silent success (fixes C32).
4. **Given** Progression answers anything other than 202, **When** the POST completes, **Then** a
   non-2xx is classified per the failure taxonomy (429/408/5xx/connect retry with bounded backoff
   honouring delta-seconds `Retry-After`; other 4xx park immediately) and a 2xx other than 202 is
   the non-transient `SUBMISSION_NOT_ACCEPTED` (fixes C1, C3).
5. **Given** a mapped document that violates the outbound schema (for example an address-less youth
   defendant), **When** submission is attempted, **Then** the document is rejected by the service's
   own pre-send contract validation, the request is recorded FAILED with a bounded reason, and the
   message is parked — the register loss is loud, attributable and replayable (fixes C29).

---

### User Story 2 - Duplicate and redelivered requests never cause duplicate registers (Priority: P1)

Requests legitimately arrive more than once. The service recognises a request it has completed and
acknowledges it without reprocessing; concurrent deliveries of one request never run concurrently;
a replay of a parked request under a fresh message identity runs afresh. The legacy app had no
idempotency at all — duplicate Event Grid deliveries started duplicate orchestrations and duplicate
POSTs (C17).

**Why this priority**: `progression.add-court-register` is not idempotent (every POST appends a new
row; Progression's generation sweep absorbs duplicates per hearing, but the rows accumulate), so
the guard is a precondition of attaching the real submission adapter.

**Independent Test**: deliver the same request twice; observe one pipeline run, one POST, and the
second delivery acknowledged without processing.

**Acceptance Scenarios** (the informant-proven guard, re-asserted here):

1. **Given** a request recorded COMPLETED, **When** the same (source, requestId) is delivered
   again, **Then** it is acknowledged with no pipeline run and the processed-log is unchanged.
2. **Given** a non-terminal record with a live single-runner claim, **When** a competing delivery
   arrives, **Then** it is returned for retry, never acknowledged.
3. **Given** a delivery whose (source, requestId) matches an existing record with different
   immutable fields, **Then** it is dead-lettered as an idempotency collision and the original
   record is untouched.
4. **Given** a request recorded FAILED, **When** a delivery arrives under a fresh message identity,
   **Then** the record transitions to RECEIVED (attempts preserved, audit note) and the pipeline
   runs again; under the exhausting identity the record stays FAILED and dead-lettering repeats.

---

### User Story 3 - Every catalogued defect is fixed and pinned (Priority: P1)

The design defect register catalogues 34 defects (C1–C34) across the legacy pipeline: swallowed
failures, a `find(d => d.defendantId = defendantId)` assignment-for-comparison, dates mislabelled
as UTC, a verdict description shipped in a code field, an unreachable ethnicity branch, unguarded
dereferences that silently destroy whole registers, and more. Each is fixed in this increment and
recorded as a row in `doc/DEFECT-FIXES.md` naming the legacy behaviour (file:line), the fixed
behaviour, the rationale, **the test that pins the fix**, and the sign-off status. Three
(C18/C28/C34) are legacy-repository items recorded as PENDING with owner and trigger rather than
fixed here.

**Why this priority**: it is the commissioning instruction for this increment, and half the fixes
(the silent-failure family) are inseparable from building the pipeline at all.

**Independent Test**: for any C-number, the register row names a test; running that test proves the
fixed behaviour; the differential audit (US5) proves no *uncatalogued* behaviour changed.

**Acceptance Scenarios**:

1. **Given** the DEFECT-FIXES register, **When** its rows are checked against the codebase, **Then**
   all 34 are present, every FIXED row names at least one passing test, and `README.md` links the
   register from its Documentation table.
2. **Given** a fix that changes register content, recipients or the wire (C4, C5, C7, C8, C9,
   C10–C12, C19–C21, C22, C23, C24, C25, C29, C30, C31 — the register's sign-off-pending rows,
   which are authoritative), **Then** its row carries `sign-off pending` with the cutover gate,
   naming who decides.
3. **Given** the attendance mapper (C8/C9), **When** a multi-defendant hearing is mapped, **Then**
   the correct defendant's attendance record is selected without mutating the input, and
   `defendantPresent` reflects a date-compatible comparison — no longer constantly false.
4. **Given** a hearing whose court application's applicant is not a prosecuting authority, **Then**
   the application no longer reaches the register (C22 — the eligibility the legacy comment claims
   is now enforced).

---

### User Story 4 - Nothing-to-publish is a state, and operations can see it (Priority: P2)

Most hearings legitimately produce no court register: group proceedings are skipped, hearings with
no youth defendants produce nothing, court centres without a subscription reach nobody. Today all
of these — and every genuine failure — are one undifferentiated `Success: true`. The service
records four distinguishable no-op terminal outcomes (`group-proceedings`, `no-defendants`,
`no-subscriptions`, `no-youth-defendants`) plus `submitted`, exposes them as metrics, and keeps
the informant-proven health model (store gates readiness, the queue never does).

**Why this priority**: the completion-reason distribution is the operational baseline — without
it, cutover day cannot distinguish "working as designed" from "broken", because no-op outcomes are
the flow's most common results.

**Independent Test**: drive one hearing through each no-op path and assert four distinct completion
reasons in the processed-log and in the completions metric.

**Acceptance Scenarios**:

1. **Given** `isGroupProceedings` is strictly boolean `true`, **Then** no register is produced and
   the request completes with reason `group-proceedings` (the skip is preserved; the silence is
   not — C7).
2. **Given** a hearing whose defendants are all adults, **Then** completion reason
   `no-youth-defendants`; **Given** no matching subscription, **Then** `no-subscriptions`; and the
   two are distinguishable (C33).
3. **Given** reference data returns an empty subscription set, **Then** the request completes
   `no-subscriptions`; **Given** the reference-data call fails, **Then** the outcome is a transient
   retry — an unanswered lookup is never a register that reaches nobody.
4. **Given** a processed request, **Then** its logs carry `requestId`, `hearingId`, `hearingDay`
   for correlation and no defendant-identifying information at INFO level or above.

---

### User Story 5 - The port is audited differentially against the legacy oracle (Priority: P3)

A recorded corpus of legacy pipeline runs (produced by executing the Node code with a pinned clock
and timezone) is replayed through the Java pipeline. Every difference between the legacy output and
the port's output must map to a C-number in DEFECT-FIXES; an unexplained difference is a port
defect. This is how fix-first keeps the informant migration's central guarantee — nothing changed
that we did not choose to change.

**Why this priority**: valuable assurance, but it depends on everything else existing first, and
the twin/new test suites already pin each behaviour individually.

**Independent Test**: run the differential audit suite; the report shows zero differences that lack
a C-number attribution.

**Acceptance Scenarios**:

1. **Given** the recorded legacy corpus and the Java pipeline, **When** the audit runs, **Then**
   every difference carries a C-number from the fixes register and the report is committed under
   `specs/001-court-register-port/checklists/`.
2. **Given** a corpus case whose input violates the outbound contract, **Then** it is classified
   `SCHEMA_INVALID` and asserted as a classified, recorded failure — never golden equality.

---

### Edge Cases

- **Group proceedings typing**: `null`/absent proceeds; boolean `true` skips with a recorded
  reason; the legacy's loose `==` (string `"false"` suppressed a register) is replaced by strict
  boolean interpretation with a WARN on any non-boolean value (C7 — content-affecting, sign-off
  flagged).
- **Legal-entity defendant, or an unmatched `masterDefendantId`**: the register survives — guards
  and fallbacks replace the legacy TypeError that silently destroyed the whole document (C19,
  C20, C21); the skipped element is logged with a bounded reason.
- **Address-less youth defendant or parent/guardian**: pre-send contract validation fails the
  request loudly (C29); the business decision on a placeholder-vs-refuse policy is a DEFECT-FIXES
  sign-off item.
- **The three dates**: the trigger's `hearingDay` keys the cache only; the document's
  `hearingDate` derives from the latest ordered date; `registerDate` derives from `sharedTime` —
  and both are now real instants, not BST local time wearing a `Z` (C10; the reference-data `on=`
  day and the filename follow, C11/C12).
- **Same request, different immutable fields**: idempotency collision → dead-letter, record
  untouched.
- **Store outage**: intake suspends; the in-flight delivery is returned for retry; nothing is
  dead-lettered by an outage.
- **Crash between run completion and outcome write**: the accepted at-least-once window; the
  redelivered request runs again sequentially and the duplicate POST is absorbed by Progression's
  latest-per-hearing sweep (assumption below).
- **Contract-invalid message**: dead-lettered immediately with a sanitised reason, no record, no
  retry burn.
- **An empty aliases array vs an absent one**: preserved distinction (`[]` vs absent) — the
  comparator treats absent ≠ null ≠ empty, and the mappers' asymmetries are pinned deliberately.

## Requirements *(mandatory)*

### Functional Requirements

Transport, settlement, idempotency, state machine, store-outage suspension, health policy,
telemetry privacy and container/CI requirements are **inherited verbatim from the informant
specification** (FR-001 – FR-018 of
`service-cp-crime-informant-register/specs/CRA-220-informant-register-initial-poc/spec.md`), with
`no-authorities` replaced by the four completion reasons below and the queue renamed
`yotresultsdistribution.requests`. They are re-proven here by the cloned test suites, not respecified.
The requirements below are this increment's own.

- **FR-101**: The service MUST fetch the hearing payload for a command by Redis claim-check first
  (key prefix `INT_`, dated key form first, then the legacy undated twin) and the results query API
  second; TLS verification MUST be enabled on the cache connection (C15); a cache **and** fallback
  miss MUST be a recorded transient failure (C32); an unreachable cache is a miss, not an error
  (the query side must still be asked).
- **FR-102**: The pipeline MUST build at most one register fragment per hearing: defendant contexts
  with the register configuration and court-application eligibility requiring **both** a subject
  master defendant **and** a prosecuting-authority applicant (C22); the court-extract filter;
  vocabulary carrying exactly the 18 agreed keys; `courtCentreId` and `courtCentreOUCode` from the
  hearing's court centre (the legacy `courtCenterId` misspelling is not reproduced, C26).
- **FR-103**: Subscription matching MUST filter reference data to court-register subscriptions,
  feed the court-centre OU code to the court-house rule only (C4), and evaluate vocabulary
  predicates **per defendant** — a register is matched if any register defendant's vocabulary
  satisfies the subscription (C31). Major-creditor predicates are consistently unmatchable for
  this flow (C30). An unanswered reference-data call is transient; an empty answer is
  `no-subscriptions`.
- **FR-104**: The outbound document MUST carry youth defendants only, with per-case/application
  offence scoping preserved (the legacy's one correctness advantage), fixed attendance semantics
  (C8/C9), guarded person/legal-entity handling (C19–C21), verdict code from
  `verdictType.verdictCode` falling back to `verdictType.categoryType`, never the prose
  description (C23), offence wording joined with a newline and omitting absent
  legislation (C24), ethnicity from observed-else-self-defined (C25), recipients defaulting to the
  `cr_standard` template with letter-delivery and missing-email drops logged and counted (C27).
- **FR-105**: The document fileName MUST be unique per hearing and filesystem-safe:
  `court-register_<registerDate as yyyy-MM-dd>_<courtCentreCode>_<hearingId>.pdf` (C11 — no
  colons, no collision between two hearings at one centre).
- **FR-106**: Every outbound document MUST be validated against the vendored Progression contract
  (`courtRegisterDocument/*` at `criminal-court-public-model` v17.103.13,
  `additionalProperties: false`) before submission; a violation is a non-transient recorded
  failure (C29, C26).
- **FR-107**: Submission MUST treat 202 as the only success; retry connect/IO/5xx/429/408 with
  exponential backoff bounded by configuration, honour `Retry-After` in delta-seconds only
  (an HTTP-date value is classified, never parsed); park other 4xx and non-202 2xx (C1, C3).
- **FR-108**: The four no-op completion reasons (`group-proceedings`, `no-defendants`,
  `no-subscriptions`, `no-youth-defendants`) plus `submitted` MUST be recorded as bounded codes,
  distinguishable in the processed-log and in the completions metric (C2, C6, C7, C33).
- **FR-109**: One `processed_output` row per submitted command — key `(source, request_id)`, no
  fan-out dimension — carrying court centre id and OU code, register date, file name, the SHA-256
  digest of exactly the bytes sent (written before the POST, kept after failure), status
  PENDING/POSTED/FAILED and the response code.
- **FR-110**: `doc/DEFECT-FIXES.md` MUST hold one row per catalogued defect C1–C34 (legacy
  behaviour with file:line, fixed behaviour, rationale, pinning test, sign-off status);
  `README.md` MUST reference it; a fix without a row, or a row without a passing named test, fails
  review.

### Key Entities

- **Distribution request (queue message)**: unchanged six-field contract
  (`source`, `requestId`, `hearingId`, `hearingDay`, `sharedTime`, `eventType`), closed, jointly
  owned; `eventType` agreed value `Hearing_Resulted` (no SJP variant for this flow).
- **Register fragment**: the internal single-per-hearing unit — register defendants with contexts
  and vocabulary, court centre id + OU code, the three dates, matched subscriptions.
- **Court register document (outbound)**: the Progression-owned shape — hearing venue, recipients,
  youth defendants (parent/guardian, aliases, counsels, cases/applications, offences, results),
  register/hearing dates, unique file name. Validated pre-send against the vendored schemas.
- **Processed request / processed output records**: as the informant service, with
  `processed_output` keyed `(source, request_id)` and carrying court-centre columns.
- **Defect-fix register (`doc/DEFECT-FIXES.md`)**: the audit ledger this increment exists to
  honour.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-101**: `./gradlew build` passes with the estate gates (all tests including `*IT`, Checkstyle
  zero warnings, JaCoCo line ≥ 0.88 / branch ≥ 0.85).
- **SC-102**: All 34 DEFECT-FIXES rows present; every FIXED row's named pinning test exists and
  passes; the three PENDING rows name owner and trigger; README links the register.
- **SC-103**: End-to-end on a development machine: one documented command sequence takes a queued
  request to a 202-acknowledged POST with `processed_output.status = POSTED`, and drives each of
  the four no-op reasons observably.
- **SC-104**: Zero silent loss and zero duplicate processing, re-proven by the cloned accounting
  and contention suites (informant SC-001/SC-002 semantics).
- **SC-105**: The differential audit report shows every legacy-vs-port difference attributed to a
  C-number, with zero unexplained differences.
- **SC-106**: A reviewer can trace any request end-to-end from logs alone via `requestId`, and no
  defendant PII appears at INFO+.

## Out of Scope (this increment)

- Cutover, rollback and the `YotResultsDistributionService` feature flag in App Configuration; the producer
  increment in `cpp-context-results` (`CourtRegisterQueuePublisher`); Event Grid subscription
  changes.
- The legacy-repository fixes C18 (trigger kill-switch wiring), C28 (dead `AddressMapperTest.js`
  rename — its two broken cases are instead repaired as Java tests here), C34 (assembly ships test
  fixtures) — recorded as PENDING rows.
- Progression-side defects P1–P9, the 18:00 generation leg, PH.04 consolidation.
- KEDA scale-out (single replica, as the informant increment); alert wiring beyond metrics and
  ERROR logs (inherited waiver).
- Business sign-off itself for content-changing fixes — the register records who signs off; this
  increment implements and flags.

## Assumptions

- Delivery contract as the informant service records it: at-most-once submission in normal
  operation; at-least-once across the crash window, the duplicate absorbed by Progression's
  `max(register_time) per hearing_id` sweep (design §3.5 — to be confirmed with the Progression
  team before cutover, not before implementation).
- The vendored `criminal-court-public-model` v17.103.13 schemas are what Progression compiles; the
  version is re-checked at cutover.
- Queue provisioning (dedicated queue + DLQ, delivery limit 5, duplicate detection on
  `messageId = source:requestId`) is external; locally the emulator declares it.
- `requestId` is minted deterministically by the producer exactly as for the informant flow; the
  same hearing therefore carries the same requestId on both queues — safe (separate queues,
  separate logs) and deliberately convenient for support correlation.
- Fix definitions are as specified in `doc/DEFECT-FIXES.md`; where a fix is content-changing the
  implemented behaviour is the register's row, and cutover is gated on its sign-off state.
