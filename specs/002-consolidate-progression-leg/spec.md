# Feature Specification: Consolidate the progression court-register leg into the service

**Feature Branch**: `002-consolidate-progression-leg`
**Created**: 2026-09-05
**Status**: Draft
**Input**: User description: "Consolidate the progression court-register leg into the service: record instead of POST, nightly batch to PDF via systemdocgenerator, e-mail via notificationnotify, one-flag cutover"

## Context

Increment 001 replaced the court-register function app: the service consumes one command per
resulted hearing, builds the youth-defendant register document, validates it against the frozen
register contract and submits it to the progression context, which batches the documents per court
centre and register date, renders a PDF at 18:00 each weekday and e-mails it to the matched Youth
Offending Teams. Progression's half of that flow has six silent-failure defects nobody owns (a
register with no recipients sticks forever; a failed render is dropped; one evening's generation
flips other days' rows; only the first hearing's recipients are kept; a failed batch is logged and
forgotten; a missing e-mail template drops the e-mail with an INFO line), and leaving it there
splits one document's lifecycle across a function app, a context and a table.

Design revision 2 (Confluence: *Court Register Service*, CRA space; working copy
`analysis/results-distribution/CourtRegister/service-cp-crime-court-register-design.md`, §4.4.1,
§4.5.1, §4.6, §5.5, §7.3, §9, §10) decided to move that half into this service. The service records
each register in its own store, runs the 18:00 job itself in London time, renders through the
platform's document generator using the unchanged register template, learns the outcome from the
generator's published events, and e-mails through the platform's notification service with the PDF
attached. Progression drops out of the flow and its court-register code is retired after soak. The
whole flow — intake and generation — is switched between the legacy implementation and this service
by the one existing feature flag, read by the results producer, by the legacy triggers and now by
this service's nightly job.

This increment implements that design. It does **not** cut over (cutover is a runbook action once
both increments are signed off) and does **not** touch progression's code (the retirement is a
separate, later change in that repository).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A resulted hearing becomes a recorded register, exactly once (Priority: P1)

A hearing is resulted and its command arrives. The service builds and validates the register
document as today, but instead of sending it to another system it records the document in its own
register store, keyed to the hearing's court centre and register date, and marks the command
complete with the reason "recorded". A later re-share of the same hearing on the same register date
supersedes the earlier record inside the same transaction; a re-share on a later date starts a fresh
record. Nothing is sent anywhere at this point.

**Why this priority**: it is the seam between the two halves; every downstream story depends on a
correct, deduplicated store, and it is the step that removes the dependency on progression.

**Independent Test**: publish one command, assert one recorded row with the validated document and
completion reason "recorded"; publish a re-share for the same hearing and day, assert the first row
is superseded and the second is the only active one; publish a schema-invalid document, assert the
command fails with the schema-invalid reason and nothing is recorded.

**Acceptance Scenarios**:

1. **Given** a valid command for a hearing with at least one youth defendant and a matched
   subscription, **When** it is processed, **Then** exactly one active record exists for that hearing
   carrying the validated document, the court centre, the register date, the register time, the file
   name, the derived defendant type and the recipients, and the command's terminal state is
   COMPLETED / recorded.
2. **Given** an active record for hearing H on register date D, **When** a re-share of H with a later
   register time on the same date D is processed, **Then** the earlier record is marked superseded by
   the new one and only the new record is eligible for batching.
3. **Given** an active record for hearing H on date D that has already been generated, **When** a
   re-share of H arrives with register date D+1, **Then** a new active record exists for D+1 and the
   generated record for D is unchanged.
4. **Given** a document that violates the frozen register contract, **When** it is processed, **Then**
   the command ends FAILED with reason SCHEMA_INVALID, is dead-lettered, and no record is created.
5. **Given** the register store is unavailable, **When** a command is processed, **Then** the delivery
   is abandoned for redelivery and intake is suspended, exactly as for the processed log today.

---

### User Story 2 - The nightly batch renders one PDF per court centre and register date (Priority: P1)

At 18:00 London time on a weekday the service groups every active, unbatched record by court centre
and register date, produces the PDF payload for each group in the same shape progression produced
it, places that payload where the platform's document generator can read it, asks the generator to
render the register template, and waits for the generator's published outcome. A generated batch
knows its document; a failed batch knows why.

**Why this priority**: it is the half of the flow that produces the artefact Youth Offending Teams
receive; without it nothing leaves the service.

**Independent Test**: seed three active records across two court centres, trigger the job, assert
two batches are requested with payloads equal to the recorded goldens and both records of the shared
court centre are in one batch; simulate a "document available" event for one and a "generation
failed" event for the other, assert GENERATED with the document id and FAILED with the reason.

**Acceptance Scenarios**:

1. **Given** active records for court centre A on date D and court centre B on date D, **When** the
   job runs, **Then** two batches exist, each PENDING then GENERATING, each with a payload whose
   content matches the golden produced by progression's generator for the same records, and every
   record carries its batch id.
2. **Given** a batch in GENERATING, **When** the generator publishes "document available" for it,
   **Then** the batch is GENERATED with the document reference and generated time, and only that
   batch's records move to GENERATED.
3. **Given** a batch in GENERATING, **When** the generator publishes "generation failed" for it,
   **Then** the batch is FAILED with reason GENERATION_FAILED and the generator's own reason recorded,
   its records remain RECORDED, and a failure metric is emitted.
4. **Given** a batch in GENERATING for longer than the configured grace period with no event, **When**
   the reconciler runs, **Then** it asks the generator directly, applies the answer through the same
   path an event would, and otherwise fails the batch with GENERATION_TIMED_OUT.
5. **Given** the payload store cannot be written, **When** the job runs, **Then** the batch is FAILED
   with PAYLOAD_STORE_UNAVAILABLE, its records remain RECORDED, and the next run retries them.
6. **Given** the generator's "document available" event for a document this service did not request
   (a different originating source), **When** it is received, **Then** it is acknowledged and ignored.
7. **Given** two runs are due at once (two instances, or a manual run during the scheduled one),
   **When** they start, **Then** exactly one proceeds.

---

### User Story 3 - Every matched Youth Offending Team receives the register once (Priority: P1)

When a batch is generated, the service takes the union of recipients across all the batch's records,
sends one e-mail request per distinct recipient with the PDF attached, and records the outcome per
recipient. A batch whose recipients all accept is NOTIFIED; one with some failures is PARTIALLY
NOTIFIED and can be resent for the failures only; one with no recipients is a counted terminal
state rather than a row stuck forever.

**Why this priority**: delivery is the flow's purpose; this story also closes four of the six
legacy silent-failure defects.

**Independent Test**: generate a two-record batch whose records carry overlapping recipient sets,
assert one e-mail request per distinct address with the document attached and the recipient's name in
the personalisation; make one request fail, assert PARTIALLY_NOTIFIED with that recipient FAILED
and the others ACCEPTED; resend, assert only the failed one is re-requested and the batch becomes
NOTIFIED.

**Acceptance Scenarios**:

1. **Given** a GENERATED batch with records carrying recipients {X, Y} and {Y, Z}, **When**
   notification runs, **Then** exactly three e-mail requests are made, for X, Y and Z, each with the
   batch's document attached and the recipient's name as personalisation, and each has its own
   notification identity recorded before the request is made.
2. **Given** an e-mail request is retried after an ambiguous outcome, **When** it is re-sent, **Then**
   it carries the same notification identity so no second e-mail is produced.
3. **Given** one recipient's request is refused, **When** notification completes, **Then** the batch is
   PARTIALLY_NOTIFIED, that recipient is FAILED with the response recorded, the others are ACCEPTED,
   and an operator can resend the failed recipients only.
4. **Given** a GENERATED batch whose recipient union is empty, **When** notification runs, **Then** the
   batch ends NOTIFIED_NOBODY, counted and alertable, and nothing is sent.
5. **Given** the configured e-mail template identifier is blank or malformed, **When** the service
   starts in live mode, **Then** it refuses to start rather than dropping e-mails at run time.

---

### User Story 4 - One flag decides which implementation is live (Priority: P1)

Operations flip the existing `YotResultsDistributionService` feature flag. When it is on, the results
producer publishes to this service, the legacy triggers stand down, and this service's nightly job
generates. When it is off — or cannot be read — this service's job does nothing and the legacy,
whose schedule is never touched, generates as it does today. Cutover and rollback are that single
flip; nothing else moves.

**Why this priority**: the ability to return to the legacy implementation with one action is a
stated requirement and the safety net for everything else in this increment.

**Independent Test**: with the flag off, trigger the job and assert it skips with reason
"flag-off" and no batch is assembled; make the flag unreadable and assert the skip reason
"flag-unreadable"; with the flag on, assert the job proceeds. Assert the manual regeneration refuses
when the flag is off unless explicitly overridden.

**Acceptance Scenarios**:

1. **Given** the flag reads OFF, **When** 18:00 arrives, **Then** the run ends immediately, no batch is
   assembled, records stay RECORDED, and a skip is logged and counted with reason flag-off.
2. **Given** the flag cannot be read within the timeout, **When** 18:00 arrives, **Then** the run ends
   immediately with a counted skip flag-unreadable, and the service remains ready.
3. **Given** the flag reads ON, **When** 18:00 arrives, **Then** the run assembles and requests batches.
4. **Given** the flag is OFF, **When** an operator runs the manual regeneration without the explicit
   override, **Then** it refuses; with the override it proceeds and says so in its output.
5. **Given** commands were still on the queue when the flag went OFF, **When** they are processed,
   **Then** they are recorded but marked as recorded-while-off and are excluded from automatic
   batching until an operator includes or supersedes them.

---

### User Story 5 - Operations can regenerate, resend, list and review without an API (Priority: P2)

An operator with access to the pod can regenerate a register date (optionally for one court house),
resend a batch's failed notifications, list batches for a date with their states, review records
recorded while the flag was off, and check the flag, all through a command-line tool shipped in the
service image. No HTTP endpoint is added.

**Why this priority**: it replaces progression's by-date generation and query endpoints for support,
and it is the rollback runbook's lever for records recorded but not yet generated.

**Independent Test**: run each command against a seeded store and assert its effect and output;
assert the image contains the tool and that no controller exists.

**Acceptance Scenarios**:

1. **Given** FAILED and RECORDED rows for date D, **When** `generate-register --date D` runs with the
   flag on, **Then** the FAILED batches are re-assembled and requested and unbatched rows are included.
2. **Given** a PARTIALLY_NOTIFIED batch, **When** `notify-register --batch B` runs, **Then** only the
   FAILED recipients are re-requested.
3. **Given** batches for date D, **When** `list-batches --date D` runs, **Then** each batch, its state,
   its record count and its recipient outcomes are listed.
4. **Given** records recorded while the flag was off, **When** `list-batches --recorded-while-off`
   runs, **Then** they are listed with their hearing identifiers and register dates.

---

### User Story 6 - Every progression-side defect is fixed and pinned (Priority: P2)

The six progression-leg defects that this increment fixes by construction (no-recipients dead end,
missing failure branch, cross-date flip, first-recipients-only, swallowed assembly error, silent
blank template) and the three it makes moot or retires are recorded in the defect-fix register as
`P` rows, each fixed row naming the test that pins the new behaviour and its sign-off state.

**Why this priority**: the register is the constitution's quality gate; a fix without a row is the
defect.

**Independent Test**: every `P` row with status FIXED names a test that exists and passes; the two
content-affecting rows (cross-date flip, recipient union) carry the sign-off marker.

**Acceptance Scenarios**:

1. **Given** the register, **When** the build runs, **Then** every FIXED `P` row's named test passes.
2. **Given** P3 and P4 change what a recipient sees, **When** they are recorded, **Then** they carry
   the sign-off-before-cutover marker and a deviation note.

---

### User Story 7 - The nightly outcome is visible without opening a database (Priority: P2)

Every run ends with a run report — batches assembled, requested, generated, notified, failed, rows
per outcome, whether the flag was read and what it said, how many outcomes came from the reconciler
rather than an event — and the metrics behind it; a run that does not start by 18:30 on a weekday is
itself a visible condition.

**Why this priority**: the leg being absorbed failed silently for years; visibility is the reason
for absorbing it.

**Independent Test**: run the job and assert the report line and gauges; stop the job and assert the
"no run" condition is detectable from metrics alone.

**Acceptance Scenarios**:

1. **Given** a run completes, **When** metrics are scraped, **Then** batch outcomes by reason,
   generation latency, reconciled count, notification outcomes, oldest unbatched age and oldest
   generating age are present, and one structured run-report log line exists.
2. **Given** the run report shows reconciled > 0, **When** an operator reads it, **Then** the event
   subscription's health is visible alongside it.

### Edge Cases

- A re-share arrives for a hearing whose batch is GENERATING: the new record is active for its own
  date and is not added to the in-flight batch; if the date is the same it waits for the next run.
- The generator publishes "document available" twice for the same batch (a re-render): the second is
  idempotent.
- The e-mail service accepts a request and later fails to deliver: out of scope for this increment
  (the service records acceptance, not delivery); the design names the follow-on.
- The flag flips ON at 17:59: the run at 18:00 reads it fresh and proceeds; the producer and triggers
  read it independently, so hearings resulted in that minute may be recorded by both paths — an
  operational window the runbook places outside court hours.
- The job runs on a bank holiday: it runs (weekday cron) and finds fewer or no rows; that is not an
  error.
- Two pods: the lock guarantees a single run; the event listener's subscription identity must be
  unique per pod or shared explicitly.
- A payload file is written but the render request fails: the batch is FAILED and re-assembly mints a
  new payload id; the orphaned file is the retention question in the design, not a correctness one.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The service MUST record each validated register document in its own store instead of
  submitting it to progression, keyed by court centre and register date, with the hearing identifier,
  register time, file name, derived defendant type and recipients, and complete the command with
  reason "recorded".
- **FR-002**: The service MUST derive the defendant type (Applicant / Appellant / Respondent) from the
  hearing's own court application by the same rule progression applies, and record the case where
  the hearing's copy could differ from progression's current aggregate as a registered deviation.
- **FR-003**: Recording MUST supersede any earlier active record for the same hearing within the
  same court centre and register date in the same transaction; records already generated MUST never
  be altered by a later share.
- **FR-004**: The frozen register contract MUST be enforced at the write; a violating document MUST
  fail the command with reason SCHEMA_INVALID and be dead-lettered, never recorded.
- **FR-005**: A scheduled job MUST run at 18:00 Europe/London Monday to Friday, guarded so that at
  most one run proceeds at a time across instances, and MUST refuse to start with any other zone
  unless an explicit override acknowledgement is configured.
- **FR-006**: The job MUST first read the `YotResultsDistributionService` feature flag (once per run, no
  cache, bounded timeout) and MUST skip the run, counting the reason, when the flag is OFF or
  unreadable.
- **FR-007**: The job MUST group active, unbatched, recorded-while-on records by court centre and
  register date into batches and MUST produce for each batch a PDF payload identical to the payload
  progression's generator produces for the same records, including the legacy line-break sentinel
  substitution.
- **FR-008**: The service MUST place the payload where the platform document generator reads it,
  minting and persisting the payload identity before the write, and MUST then request rendering of
  the unchanged register template with its own originating-source tag and the batch identity as the
  correlation.
- **FR-009**: The service MUST learn each batch's outcome from the generator's published
  "document available" and "generation failed" events, filtered to its own originating-source tag
  and correlated by batch identity, and MUST fall back to querying the generator directly for a
  batch still generating after a configurable grace period, applying either answer through one code
  path.
- **FR-010**: On generation, only the batch's own records MUST move to GENERATED; a failed generation
  MUST leave them RECORDED and record the generator's reason.
- **FR-011**: The service MUST send one e-mail request per distinct recipient address in the union of
  the batch's records' recipients, with the generated document attached by its platform file
  reference and the recipient's name as personalisation, minting and persisting each notification
  identity before the request and reusing it on retry.
- **FR-012**: The service MUST record each recipient's outcome and derive the batch state — NOTIFIED,
  PARTIALLY_NOTIFIED, NOTIFIED_NOBODY — and MUST allow failed recipients to be resent alone.
- **FR-013**: The e-mail template identifier MUST be validated at startup in live mode; the service
  MUST refuse to start without a valid one.
- **FR-014**: Every downstream request MUST treat "202 Accepted" and nothing else as success; other
  2xx are non-transient failures; transient failures retry within the run deadline using the
  existing retry policy.
- **FR-015**: Records created from commands processed while the flag is OFF MUST be marked
  recorded-while-off and excluded from automatic batching until an operator includes or supersedes
  them.
- **FR-016**: The service MUST ship a command-line tool in its image providing: regenerate by date and
  optional court house (flag-checked, explicit override), resend a batch's failed notifications,
  list batches by date, list recorded-while-off records, supersede records recorded before an
  instant, and check the flag. No HTTP endpoint MUST be added.
- **FR-017**: The service MUST emit a structured run report and metrics for batch outcomes by reason,
  generation latency, reconciler-completed count, notification outcomes, oldest unbatched age,
  oldest generating age, flag read outcome and skipped runs, and MUST expose the event
  subscription's health without gating readiness on it.
- **FR-018**: The payload-store connection MUST be a readiness input only while a run is in progress;
  broker connectivity MUST never gate readiness.
- **FR-019**: The previous behaviour — submitting the document to progression — MUST remain
  available behind a configuration switch for the documented fallback sequencing, defaulting to
  record.
- **FR-020**: The defect-fix register MUST gain `P1`–`P9` rows with the dispositions in the design
  (six FIXED with pinning tests, two RETIRED, one MOOT), P3 and P4 carrying the sign-off marker.
- **FR-021**: The constitution MUST be amended (Principle III and the technology section) before code
  that changes the outbound shape lands, per its own governance.

### Key Entities *(include if feature involves data)*

- **Register record**: one recorded register document per command — hearing, court centre, register
  date and time, file name, defendant type, recipients, the document, state (RECORDED, GENERATED,
  NOTIFIED, SUPERSEDED, FAILED), supersession pointer, batch membership, flag state at recording.
- **Register batch**: one per court centre and register date — file name, payload identity, document
  identity, state (PENDING, GENERATING, GENERATED, NOTIFIED, PARTIALLY_NOTIFIED, NOTIFIED_NOBODY,
  FAILED), failure reason, generator's reason, timestamps, whether system-generated.
- **Register notification**: one per batch and recipient — notification identity, address, name,
  template, state (PENDING, ACCEPTED, FAILED), response, sent time.
- **Run**: one per scheduled or manual execution — flag outcome, counts per outcome, duration.
- **Feature flag**: the external `YotResultsDistributionService` toggle, read per run.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For a replayed day of production-shaped hearings, 100% of the documents this service
  records are content-identical to those progression recorded for the same hearings, after the
  registered deviations, and the PDF payload for every batch is byte-identical to progression's.
- **SC-002**: Every batch reaches a terminal state within one hour of the run start in normal
  operation, and every non-terminal batch older than the grace period is visible as such.
- **SC-003**: Zero registers are lost silently: every command and every batch has a recorded
  terminal state with a bounded reason, verifiable by the build's differential and state tests.
- **SC-004**: Switching the flag off stops this service from generating on the next run and switching
  it on starts it, with no other configuration change, demonstrated in a pre-production stack in
  both directions.
- **SC-005**: A Youth Offending Team matched by two hearings in one batch receives exactly one e-mail
  for that batch.
- **SC-006**: Every one of the six fixed progression-side defects has a named, passing pinning test;
  the build fails if any is reverted.
- **SC-007**: The service exposes no business HTTP endpoint; all operational actions are available
  through the shipped command-line tool.

## Out of Scope (this increment)

- Cutover itself and the retirement of progression's court-register code (separate release, after
  soak; progression repository).
- Changes to systemdocgenerator, notificationnotify, the platform file service or the register
  template.
- Consuming the notification service's delivery events (the first post-cutover increment).
- The prison court register.
- Migration of historical rows from progression's table.
- The results producer's queue publisher and the legacy triggers' flag gate (externally owned, C18).

## Assumptions

- The platform grants this service a write-only role on the shared file-service database and read
  access to the App Configuration store; both are design sign-off items, not assumed approved.
- The hearing payload carries the court application fields needed to derive the defendant type
  (verified on results integration fixtures).
- The legacy nightly job remains scheduled through cutover and soak and is cancelled only by the
  retirement change.
- Payload and document file retention follows the legacy (no deletion) until the design's open
  question is answered.
- The e-mail template identifier and per-stack flag labels are provisioned by the platform.
