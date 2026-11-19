# Feature Specification: Replay a parked request by broker sequence number

**Feature Branch**: `007-replay-by-sequence-number`
**Created**: 2026-11-19
**Status**: Draft
**Input**: User description: "Port the informant-register replay-by-sequence-number change (that service's spec `001-replay-by-sequence-number`, PR #79, implemented in #80). Today the idempotency guard tells 'a redelivery of the delivery that parked a request' apart from 'a deliberate resubmission' by comparing the delivery's messageId with the stored exhausted_message_id. Producers publish with a deterministic messageId and the Azure portal's dead-letter 'Re-send' keeps it, so every such resubmission is re-parked without running and reported as exhausted / DELIVERY_LIMIT_EXHAUSTED, hiding the real cause. Identify the parking delivery by the broker sequence number, handle rows parked before the change, make replay a compare-and-set on the failure generation, make a re-park report the recorded reason and category, read broker metadata once inside the settlement boundary, and relax the fresh-messageId replay rule in the constitution and rules."

## Context

When a request on `yotresultsdistribution.requests` cannot be completed — the results query API or
reference data refuses the read, the hearing cannot be transformed into a register, the register
fails the frozen document contract, the register store refuses the recording, progression refuses
the `add-court-register` command (in `progression-post` mode), or the delivery budget runs out — the
service records the request as `FAILED` in `processed_request` and parks the message on the
dead-letter queue. Parking is meant to be recoverable: the supported way to recover a parked request
is to **resubmit** it, at which point the processed log moves it from `FAILED` back to `RECEIVED` and
runs it again, and a register already recorded (or, in `progression-post` mode, already `POSTED`)
for the request is not recorded or sent a second time.

To decide whether a delivery of a `FAILED` request is a deliberate resubmission or simply the parking
delivery coming round again (because its dead-lettering was not accepted), the guard compares the
delivery's broker `messageId` with the `messageId` it stamped into `exhausted_message_id` when it
parked the request. Both parking paths — the non-transient failure and the final permitted delivery —
stamp it, through the same statement. Same identity means "redelivery — park it again without
running"; a different identity means "resubmission — replay it".

That test is the wrong one in practice:

- **The usual resubmission route reuses the original identity.** The results producer publishes with
  a deterministic `messageId` (`{source}:{requestId}`), and the dead-letter "Re-send" in the Azure
  portal's Service Bus Explorer — the tool support actually has — copies the message as it stands,
  `messageId` included. Every such resubmission is read as a redelivery and parked again without
  running. A parked court register therefore cannot be recovered without bespoke tooling that mints a
  fresh `messageId`, and this service provides none: the operations API has no replay surface by
  design (increment 005, Out of Scope), so nothing in or around the service mints one.
- **The broker does not need a fresh identity.** Duplicate detection only remembers identities for a
  short window after each accepted send (5 minutes on this queue), so outside that window the broker
  accepts a same-identity re-send. The service's own guard is the only thing refusing it.
- **The re-park hides the cause.** A delivery parked again is dead-lettered as
  `exhausted / DELIVERY_LIMIT_EXHAUSTED`, whatever the request originally failed with. A register
  that progression rejected, or that the frozen contract refused, reads on the dead-letter queue as
  though retries had run out, and the real reason is only visible in the processed log. This is the
  same misreport the non-transient path was built to avoid on the first parking (its own comment
  says so); it comes back on the second.
- **The replay is not tied to the failure it read.** The replay statement admits any `FAILED` row
  whose stored identity differs from the delivery's. A delivery that read the row before it was
  replayed, run and parked again can replay the newer failure on the strength of the older read.

The broker already provides the right identity: the **sequence number** it assigns when it accepts a
message onto the queue. It is unique within the queue and does not change when the same stored
message is redelivered (after an abandon, a lock expiry, a closed connection or a dead-letter
operation that was not accepted). Every newly **accepted and enqueued** send — portal re-send, replay
tooling or producer republish — receives a new one; a send discarded by duplicate detection receives
none and never reaches the service. This feature makes the sequence number, not the `messageId`, the
test of "is this the delivery that parked the request?".

The same defect, in the same code, was found and fixed in the informant-register service (its
`001-replay-by-sequence-number`). Two consequences follow for this repository beyond the defect
itself:

- **This repository's increment 001 inherits its transport, settlement and idempotency requirements
  "verbatim" from the informant CRA-220 specification** (`specs/001-court-register-port/spec.md`,
  Functional Requirements preamble). That specification has now been amended to the
  sequence-number rule, so until this feature lands the inherited text and this service's code
  disagree.
- **The fresh-`messageId` rule is in the constitution** (Technology Stack & Deployment, Messaging
  bullet), so relaxing it is an amendment under the Governance procedure, proposed below and made
  in this specification's PR (5.3.0).

This is a change to **transport recovery** inside the service's own state machine. The legacy
function app had no processed log, no parking and no replay, so there is no legacy behaviour for it
to differ from: register content, recipients, the generation leg and the successful path are
untouched, and no new defect-fix (C-number) row is needed (see Documents the implementation must
update for the C17 row).

## Constitution amendment proposal (Governance step 1)

The Governance section requires an amendment to be **proposed in a feature spec**, versioned, and
followed by a `/speckit-analyze` re-run against every in-flight feature spec. This section is that
proposal. The design owner has ratified it, and the amendment is made in this specification's PR
(constitution 5.3.0), ahead of the code: until the implementation lands the service still applies
the 5.2.0 rule. The rule and agent files that restate it follow in the implementation, contract
first.

**Proposal**: rewrite the Technology Stack & Deployment Messaging sub-bullet "**Replay tooling MUST
always mint a fresh `messageId`** — deliberate replay must not be silently discarded by duplicate
detection." to say: **a replay is decided by the broker's own sequence number, never by
`messageId`**. The guard identifies the delivery that parked a request by the sequence number the
broker assigned it on accept, so a support resubmission MAY re-send the dead-lettered message
unchanged, `messageId` included. Minting a fresh `messageId` is no longer required by the service;
it remains the way to re-send *inside* the broker's duplicate-detection window, where a
same-`messageId` re-send is still silently discarded by the broker whatever this service does.
Normal producer publishing keeps the deterministic `messageId`. Add a 007 entry to the Increments
list.

**Version**: MINOR (5.2.0 → 5.3.0). Materially expanded guidance on an existing bullet; no Core
Principle (I–VIII) states the replay rule and none is touched. Existing practice is relaxed, not
invalidated: a resubmission under a fresh `messageId` keeps working exactly as before.

**Step 3**: re-run `/speckit-analyze` on every feature spec in flight when the amendment lands.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Support recovers a parked request by re-sending it unchanged (Priority: P1)

A support engineer finds a parked message on the dead-letter queue, fixes (or confirms the fix of)
whatever caused it to fail, and uses the dead-letter "Re-send" to put the message back on the queue
exactly as it is — same body, same `messageId`. The service recognises this as a deliberate
resubmission and replays the request.

**Why this priority**: This is the defect. Without it a parked court register is effectively
unrecoverable with the tools support has, which defeats the purpose of parking it — and a register
that is not recorded is not in the 18:00 batch for its court centre.

**Independent Test**: Park a request (for example by making the payload read fail with a refused
4xx, or by delivering it five times into a transient failure), wait until the broker's
duplicate-detection window for its `messageId` has passed, re-send the dead-lettered message
unchanged, and observe the request move `FAILED → RECEIVED` and run again.

**Acceptance Scenarios**:

1. **Given** a request recorded `FAILED` with the sequence number of the delivery that parked it,
   **When** a delivery arrives for the same request with the **same `messageId`** but a **different
   sequence number**, **Then** the service treats it as a resubmission: it moves the request
   `FAILED → RECEIVED`, carries the lifetime attempt count forward and increments it once for the
   admitted replay, writes an audit note, and runs it again.
2. **Given** a replayed request whose earlier run had already recorded its register (or, in
   `progression-post` mode, already `POSTED` it), **When** the replay runs, **Then** the register is
   not recorded or sent a second time.
3. **Given** a request recorded `FAILED`, **When** a delivery arrives with a **fresh `messageId`** and
   a different sequence number, **Then** it is replayed exactly as in scenario 1 — the existing replay
   route keeps working.
4. **Given** a replay is admitted, **When** support searches the logs for the request, **Then** a line
   records the replay with the parked delivery's sequence number and the replaying delivery's sequence
   number (identifiers only — no defendant detail, every defendant on this register being a youth).

---

### User Story 2 - The delivery that parked the current failure is never re-run (Priority: P1)

The protection the current test exists for must survive. The delivery recorded as parking the
**current** `FAILED` generation must not be re-run. If its dead-letter operation is not accepted and
that same stored message returns — after a lost lock or a closed connection — its sequence number
still matches the recorded parking sequence number, so it is parked again without running.

**Why this priority**: Removing the protection would let a request that was deliberately parked run
again on its own, beyond the delivery budget, with nobody having asked for it.

**Independent Test**: Park a request, arrange for the same message (same sequence number) to be
delivered again, and observe that nothing runs and the message is dead-lettered again.

**Acceptance Scenarios**:

1. **Given** a request recorded `FAILED` with the sequence number of the delivery that parked it,
   **When** a delivery arrives carrying **that same sequence number**, **Then** the service runs
   nothing, changes no state, and dead-letters the delivery again.
2. **Given** the same situation, **When** the delivery is re-parked, **Then** exactly one explicit
   settlement attempt is made for it.

---

### User Story 3 - Requests parked before this change can be recovered (Priority: P2)

Requests parked before this change have no sequence number recorded, so the exact test of User
Story 2 is not available for them. Support must still be able to recover them with "Re-send". FR-005
uses the strongest available two-condition heuristic: a delivery failing either condition is
re-parked, and the documented residual false-replay risk is explicitly accepted.

**Why this priority**: Requests already sitting on the dead-letter queue when the change ships are
the first ones support will want to recover.

**Independent Test**: Create a `FAILED` record with no parking sequence number, deliver a newly sent
message for it (first delivery, enqueued well after the request was parked) and observe a replay;
deliver one that fails either condition and observe a re-park.

**Acceptance Scenarios**:

1. **Given** a `FAILED` request with no recorded parking sequence number, **When** a delivery arrives
   whose broker delivery count is 0 (the broker's count is zero-based: a first delivery carries 0)
   **and** whose broker enqueue time is later than the time the request was parked by more than the
   safety margin, **Then** it is treated as a resubmission and replayed.
2. **Given** the same request, **When** a delivery arrives that fails either condition (delivery count
   1 or more, enqueue time not later than the parking time plus the margin, or no enqueue time),
   **Then** it is re-parked without running.
3. **Given** a legacy resubmission was handed back before replay admission (for example because the
   store was unavailable and intake was suspended, or another replay won the compare-and-set),
   **When** it later reaches the same legacy `FAILED` record with a delivery count of 1 or more,
   **Then** it is re-parked and support sends it again; the new send is judged afresh.

---

### User Story 4 - A re-parked delivery says why the request failed (Priority: P3)

When a delivery is parked again, the dead-letter queue shows the reason the request actually failed
and the category it was originally parked under, not a generic "delivery limit exhausted".

**Why this priority**: It does not block recovery, but it is what support reads first. A register
progression rejected, or one the frozen contract refused, must not look like a request that ran out
of retries.

**Independent Test**: Park a request with a non-transient failure (for example
`OUTBOUND_CONTRACT_VIOLATION` or `PAYLOAD_READ_REFUSED`), trigger a re-park, and read the dead-letter
reason and description on the re-parked message.

**Acceptance Scenarios**:

1. **Given** a request parked as `non-transient` with failure reason `OUTBOUND_CONTRACT_VIOLATION`,
   **When** a delivery for it is re-parked, **Then** the dead-letter reason is `non-transient` and the
   description is `OUTBOUND_CONTRACT_VIOLATION`.
2. **Given** a request parked as `exhausted` with a transient failure reason (for example
   `PAYLOAD_UNAVAILABLE`), **When** a delivery for it is re-parked, **Then** the dead-letter reason is
   `exhausted` and the description is that transient failure reason. The *first* parking of an
   exhausted request is unchanged and still reads `exhausted / DELIVERY_LIMIT_EXHAUSTED`.
3. **Given** a request with no recorded parking category (parked before this change, or by a consumer
   that predates it), **When** a delivery for it is re-parked, **Then** the category is the one the
   explicit mapping assigns to its stored failure reason.
4. **Given** a request whose stored failure reason is missing or not recognised, **When** a delivery
   for it is re-parked, **Then** it is dead-lettered as `exhausted / UNEXPECTED_FAILURE`, a sanitised
   error records the data anomaly, the unrecognised text is not emitted, and nothing throws.

---

### Edge Cases

- **Two re-sends of the same parked message** (for example a double click): each is a distinct
  accepted enqueue, so each is an independent replay request. If the first replay completes, the
  second is acknowledged as already completed. If the first replay fails and parks again, the second
  replays that newer failure. Both are logged; a register already recorded or `POSTED` is not
  recorded or sent again. While the first replay runs, the second finds the claim held and is handed
  back (`CLAIM_NOT_ACQUIRED`); if the run outlasts its five deliveries, the broker dead-letters that
  stray copy under its own delivery-limit reason, with no `FAILED` record behind it. Support should
  read the processed log for the request before acting on such a copy: the request itself may well
  have completed.
- **Two deliveries racing to replay the same failure**: only one replay is admitted. The other finds
  that the record has moved on and is handed back (`REPLAY_NOT_ADMITTED`).
- **A stale replay decision**: a delivery that read the record before it was replayed, run and parked
  again must not replay the newer failure using what it read earlier. Replay only succeeds against the
  exact failure generation the delivery observed. Today's replay statement does not ensure this.
- **A stale copy of an older parking delivery** returning after a later failure has been recorded has
  a different sequence number from the current parking delivery, so it replays that later failure
  once. This is accepted by the independent-enqueue policy: the run is still subject to the
  single-runner claim and to the register store's and processed output's own redelivery handling,
  and it is logged as a replay.
- **Re-send inside the duplicate-detection window**: if a same-`messageId` re-send is made within the
  broker's duplicate-detection window of the last accepted send with that `messageId`, the broker
  silently discards it and the service never sees it. Support must wait for the window to pass, or
  re-send with a fresh `messageId`.
- **Re-send of a request that is not `FAILED`**: a `COMPLETED` request is acknowledged without
  running; a `RECEIVED`/`RETRYING` request follows the existing claim rules. Unchanged.
- **Body edited before re-sending**: any change to the fingerprinted fields (`hearingId`,
  `hearingDay`, `sharedTime`, `eventType`) is an idempotency collision and is dead-lettered as one.
  Removing the optional `userId` is not, since it is outside the fingerprint (the inbound schema
  describes exactly this replay). Unchanged.
- **Replay of an older share after a newer share was recorded**: the register store's write-time
  supersession compares the replayed register only with the hearing's **unbatched** active register.
  While the newer register is still unbatched, the replayed older one is recorded superseded and the
  newer one stays active. Once the newer register **has been batched**, there is nothing unbatched to
  compare with, so the replayed older share is recorded as active and goes out in a later batch: the
  Youth Offending Team receives the superseded content after the current one. That is existing store
  behaviour for any late delivery, but an unchanged portal Re-send makes it routine rather than
  rare. This feature does not change the store; support MUST check whether a later share of the same
  hearing has already been batched before re-sending a parked older share, and the operator guidance
  says so.
- **Cutover flag at replay time**: a replayed run is labelled with the cutover flag as this pod last
  read it when the replay arrives, as every run is. Unchanged.
- **The 07:00 exception report and the intake gauges**: a re-park changes no state, so a parked
  request stays listed under the day it was parked; a replay that fails and parks again is listed
  again under its new parking time. A replay, though, returns an old row to `RECEIVED`, and in-flight
  age is measured from the row's creation time, not from the replay. A request parked days ago and
  replayed now therefore shows at once as the oldest unfinished request and as over the intake
  threshold — a spike on the intake-age gauges, and a `REQUEST_LATE` entry if a report runs while it
  is in flight. This is a consequence of replays becoming easy, not a new rule; the measurement is
  unchanged, and the operator guidance notes it.
- **Broker metadata cannot be read**: retrieving the message, or reading its sequence number, delivery
  count, `messageId` or lock token, throws or yields nothing. The delivery still receives exactly one
  explicit settlement attempt, through the unexpected-failure route. A missing enqueue time is not a
  failure: it is recorded as absent, logged as `none`, and (for a legacy record only) means re-park.
- **Queue deleted and recreated**: sequence numbers restart. A new message could, in principle, get
  the same sequence number as a recorded parking delivery. That can only cause a false *re-park*
  (visible, recoverable by re-sending again), never a false *replay*.
- **Producer republish of a `FAILED` request** after the duplicate-detection window is now a
  resubmission and replays. This is intended: it is the same unit of work.
- **Re-send not consumed in time**: the queue dead-letters a message whose time-to-live (1 hour)
  expires. A re-send made while intake is suspended — during a processed-log outage, for example —
  or while no consumer is running, and not picked up before it expires, goes back to the dead-letter
  queue without ever reaching the service; nothing is recorded, and support re-sends it once intake
  is back.
- **Rolling deployment**: while an older consumer is still running it can park requests without
  recording the new fields. Those rows are handled exactly like requests parked before the change.
  That covers an older consumer parking a row **for the first time** during the overlap. It does
  **not** cover an older consumer **re-parking** a row a newer consumer already parked: the older
  consumer's own `FAILED → RECEIVED → FAILED` cycle does not know the new fields, so a prior
  parking's sequence number and category survive underneath it, describing a parking that is no
  longer the row's latest, and a newer consumer can then read the row's actual parking delivery as a
  resubmission and run it again. The informant service judged there to be no in-band signal for
  this. There is one: every path out of `FAILED` and back in increments the attempt count, so a row
  whose attempt count differs from the count recorded when the new fields were written has been
  re-parked by something that did not write them. This feature adopts that signal (FR-001, FR-005a)
  and keeps the rollout rule (FR-014) as well, because the signal makes such a row a legacy row —
  judged by the heuristic — rather than exact again.

## Requirements *(mandatory)*

### Functional Requirements

**Recording the parking delivery**

- **FR-001**: When the service parks a request (both the non-transient path and the
  final-permitted-delivery path), it MUST record, in the same atomic write that sets `FAILED`: the
  broker sequence number of the parking delivery, the dead-letter category it was parked under
  (`non-transient` or `exhausted`), taken from the failure classification the parking path used, and
  the attempt count the row holds at that moment. The existing record of the parking delivery's
  `messageId` is kept for audit.
- **FR-002**: The parking sequence number, category and parking attempt count MUST be written only by the run that holds the
  claim; a stale runner MUST NOT be able to overwrite them.

**Deciding a delivery of a `FAILED` request**

- **FR-003**: For a `FAILED` request with a recorded parking sequence number, a delivery with the
  **same** sequence number MUST be re-parked without running and without any state change.
- **FR-004**: For a `FAILED` request with a recorded parking sequence number, a delivery with a
  **different** sequence number MUST be treated as a resubmission and replayed, regardless of its
  `messageId`.
- **FR-005**: For a `FAILED` request with **no** recorded parking sequence number, a delivery MUST be
  treated as a resubmission only when **both** hold: its broker delivery count is 0, **and** its broker
  enqueue time is later than the time the request was parked by more than a configured safety margin
  (default 5 minutes). This service has no setting for the queue's lock duration today — only the
  lock *renewal* window — so it gains one mirroring the queue's lock duration (default 1 minute,
  changed together with the queue, as the delivery limit already is); a margin shorter than it MUST
  be refused at start-up. Otherwise, including when the enqueue time is absent, it MUST
  be re-parked. Neither condition is proof on its own and the rule is an explicitly accepted
  compatibility heuristic: the broker does not increment the delivery count when a message is
  released by a closed connection — which this service causes deliberately whenever it suspends
  intake during a processed-log outage, as well as on every stop — and the enqueue-time test compares
  broker time with database time. A false replay requires both a release without increment **and** a
  broker clock ahead of the database clock by more than the margin. Removing the residual risk
  entirely would need the exact parking sequence numbers backfilled from the dead-letter queue, which
  is out of scope.
- **FR-005a**: A `FAILED` request whose recorded parking fields are present but whose current attempt
  count differs from the attempt count recorded with them MUST be decided as though it had no
  recorded parking sequence number (FR-005) and no recorded category (FR-010): the fields describe an
  earlier parking, written over by a consumer that did not know them. The decision is logged as such,
  with identifiers only.
- **FR-006**: A replay MUST be a compare-and-set against the failure generation the delivery observed:
  it succeeds only if the record is still `FAILED` with the same attempt count and the same parking
  sequence number (compared null-safely, so "still none" matches for a legacy record) that were read.
  If it does not succeed, the delivery MUST be handed back with `REPLAY_NOT_ADMITTED`. This service
  has no escalation of an admission hand-back on the final permitted delivery (the informant service
  does); this feature does not add one, and the hand-back is unchanged in that respect.
- **FR-007**: A replay MUST keep today's semantics: `FAILED → RECEIVED`, lifetime attempt count
  carried forward and incremented, audit note written (carrying the reason it was parked for), failure
  reason, parking `messageId`, sequence number, category and parking attempt count cleared, and a
  register already recorded
  or `POSTED` for the request not recorded or sent again.
- **FR-008**: The replay and re-park decisions MUST each be logged with the request identifiers, the
  parked delivery's sequence number (or `none`) and the current delivery's sequence number, delivery
  count and enqueue time. No defendant detail.

**Re-park reason**

- **FR-009**: A re-park with a recognised stored failure reason MUST dead-letter with the request's
  category and that reason, and MUST NOT use `DELIVERY_LIMIT_EXHAUSTED` as a substitute for the
  original cause. A missing or unrecognised stored reason MUST dead-letter as
  `exhausted / UNEXPECTED_FAILURE`, MUST log a sanitised error identifying the data anomaly, and MUST
  neither emit the unrecognised text nor throw.
- **FR-010**: When a `FAILED` request has no recorded category — parked before this change, or by an
  older consumer during a rolling deployment — the category MUST come from an explicit, reviewed
  mapping of its stored failure reason, applied when the decision is made (so rows written after any
  migration are covered too). The reasons the non-transient path can record today
  (`PAYLOAD_READ_REFUSED`, `REFERENCE_DATA_REFUSED`, `TRANSFORMATION_FAILED`,
  `OUTBOUND_CONTRACT_VIOLATION`, `REGISTER_NOT_RECORDED`, `SUBMISSION_REJECTED`,
  `SUBMISSION_NOT_ACCEPTED`) map to `non-transient`; all others map to `exhausted`. Where a category
  is recorded, it is used as recorded and never reconstructed from the reason code. A reason code
  later added to the non-transient path MUST be added to the mapping in the same change.

**Broker metadata**

- **FR-011**: Reading the received message's broker metadata MUST happen once per delivery, inside
  the boundary that guarantees a settlement attempt. Sequence number, delivery count, `messageId` and
  lock token are required; enqueue time is optional and captured as absent when missing. Every later
  use (the receipt log line, the delivery identity, the claim, the final-delivery check) MUST use
  those captured values, and no accessor MUST be called more than once. Today the message is already
  fetched inside that boundary and every metadata read already sits inside it; what changes is that
  the delivery count is read up to three times per delivery and the sequence number and enqueue time
  are not read at all.
- **FR-012**: A failure to retrieve the message or to read any required metadata MUST still result in
  exactly one explicit settlement attempt; no such failure may escape before that attempt. Where the
  final-delivery position cannot be determined, the decision is the conservative one: hand the
  delivery back with `UNEXPECTED_FAILURE`. The order of today's boundary is kept: the processed-log
  availability check still comes before the body or the metadata is examined, and a store outage
  still hands the delivery back and suspends intake.

**Contract and schema**

- **FR-013**: The replay rule MUST be changed **before** the code, by the constitution amendment
  above (made in this specification's PR) and the matching rule and agent text: a fresh `messageId` is no longer required by
  the service. It is still needed by the broker when re-sending within the duplicate-detection window
  of the last accepted send with that `messageId`. Normal producer publishing keeps the deterministic
  `messageId`. The inbound body schema is unchanged: the rule concerns broker properties, not the
  body.
- **FR-014**: The schema change MUST be additive and forward-only. Existing rows stay valid; the
  existing constraint that a `FAILED` request has a parking `messageId` is kept; a `FAILED` row with no
  parking sequence number and no parking category is a valid state (FR-005, FR-010). New `FAILED`
  writes MUST set sequence number, category and parking attempt count together, and every write
  this version makes that takes the row out of `FAILED` MUST clear them. Clearing is a **write
  rule, not a database constraint**: an older consumer's replay statement does not know the new
  columns and leaves them set on a non-`FAILED` row, so a constraint tying them to `FAILED` would
  make that consumer's replay fail. The schema adds only the columns, a check that the three are
  present or absent together, and a check on the category's vocabulary. The service MUST NOT assume
  that a migration alone prevents a `FAILED` row without the new fields, or a non-`FAILED` row with
  stale ones: during a rolling deployment an older consumer can write either. The rollout MUST
  ensure that no older consumer of `yotresultsdistribution.requests` remains in service, or resumes
  consuming, once any pod records these fields — drain the older version to zero first; a rollback
  follows the same rule. An ordinary rolling update, which keeps older pods consuming beside newer
  ones, is exactly the overlap described under Edge Cases.

**Quality gates**

- **FR-015**: Every requirement above MUST be proven by a test written before the code, covering at
  least:
  - same sequence number re-parks; different sequence number with the **same** `messageId` replays;
  - legacy record: count 0 and enqueued after parking plus margin replays; count ≥ 1 re-parks;
    enqueued before or within the margin re-parks; no enqueue time re-parks; a legacy parking-message
    redelivery released with count 0 but enqueued before parking re-parks; a legacy resubmission
    handed back once is re-parked on its next delivery; an enqueue time exactly equal to the parking
    time plus the margin re-parks; a configured margin shorter than the configured lock duration is
    rejected at start-up; a simulated broker clock lead greater than the margin demonstrates the
    accepted residual false-replay case;
  - parking fields left by an earlier parking under a row re-parked by an older consumer (attempt
    count moved on) are judged by the legacy rules, not as exact;
  - a row an older consumer replays keeps its stale parking fields without violating any constraint;
  - two concurrent re-sends admit one replay; the FAILED→RECEIVED→FAILED race cannot replay a newer
    failure; the compare-and-set matches a null parking sequence correctly;
  - the independent-enqueue policy after a first replay has produced a newer failure;
  - a stale runner cannot overwrite the parking metadata;
  - a re-park carries the recorded category and reason; a missing category uses the mapping; every
    non-transient reason code the pipeline can record is in the mapping; a missing or unrecognised
    reason uses `exhausted / UNEXPECTED_FAILURE` without throwing;
  - each broker accessor is called at most once; a failure of message retrieval or of any required
    accessor still makes exactly one settlement attempt; an absent enqueue time continues normally;
  - a replay in `record` mode of a request whose register is already recorded records nothing new, and
    in `progression-post` mode of one already `POSTED` sends nothing;
  - the migration leaves existing rows valid;
  - against the broker emulator: the sequence number is unchanged across an abandon, a re-send gets a
    new one, and an unchanged re-send made after the duplicate-detection window recovers a parked
    request end to end.

### Key Entities

- **Processed request** (`processed_request`): gains the **parking sequence number** (the broker
  sequence number of the delivery that parked it), the **parking category** (`non-transient` or
  `exhausted`) and the **parking attempt count** (the attempt count when they were written). All
  three are set together with `FAILED` by this version, may all be absent on a row parked otherwise,
  and are cleared by this version's replay; an older consumer's replay can leave them set. The existing parking `messageId`
  (`exhausted_message_id`, despite its name written on both parking paths), failure reason, attempt
  count, audit note and update time keep their current meaning; for a `FAILED` row the update time is
  the time it was parked.
- **Delivery identity**: the facts about one delivery that the guard decides on. It gains the sequence
  number, delivery count and (optional) enqueue time captured from the broker, alongside the
  `messageId`, claim owner and final-permitted-delivery flag it carries today.
- **Dead-letter reason**: unchanged set (`validation`, `collision`, `exhausted`, `non-transient`); a
  re-park now uses the request's category instead of always choosing `exhausted`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For a request parked by this version, every accepted re-send carrying a different
  sequence number is replayed, whatever its `messageId`. For a request parked before this version, an
  unchanged re-send is replayed when it reaches the decision on its first delivery and was enqueued
  after the parking time plus the margin; otherwise support sends it again.
- **SC-002**: No run is started by a delivery whose sequence number equals the parking sequence
  number recorded for the current `FAILED` generation.
- **SC-003**: No register is recorded twice, and none is posted to progression twice, as a result of
  a replay, a re-send or a race between them, in normal operation.
- **SC-004**: Every re-park with a recognised stored failure reason shows that reason and its
  category on the dead-letter queue. A missing or unrecognised reason shows
  `exhausted / UNEXPECTED_FAILURE` and never causes a delivery to escape unsettled.
- **SC-005**: A support engineer can recover a parked request using only the dead-letter "Re-send",
  with no message edits and no database changes.
- **SC-006**: Every delivery, including one whose broker metadata cannot be read, receives exactly one
  explicit settlement attempt; no second attempt is made if the broker refuses the first.

## Out of Scope (this increment)

- Why a read, a transformation, the store or progression refused the request. A replay only succeeds
  if the cause has been fixed; this feature makes the replay possible, not successful.
- Network access and role assignments needed to use the portal against a given namespace.
- Clearing old copies off the dead-letter queue after a successful replay.
- Backfilling exact parking sequence numbers for requests parked before this change.
- A replay or status endpoint. The operations API stays as increment 005 bounded it — no replay
  surface — and adding one would need its own constitution amendment. Replay remains "re-send the
  message".
- Escalating an admission hand-back on the final permitted delivery to a dead-letter of the
  service's own (FR-006). This is an **existing gap**, not a choice made here: increment 001 inherits
  the CRA-220 transport rules "verbatim" (`specs/001-court-register-port/spec.md`, Functional
  Requirements preamble), and the informant service applies that escalation to `CLAIM_NOT_ACQUIRED`,
  `RECORD_ABSENT` and `REPLAY_NOT_ADMITTED`, while this service's guard applies it to none. A
  follow-up story is recommended; no ticket is known.
- Any change to the inbound body schema, the frozen register document, the `add-court-register`
  command, register content, recipients, batching, rendering or notification.

## Documents the implementation must update

The constitution is amended in this specification's PR (5.3.0: the Messaging bullet, the Sync
Impact Report, the version line and a 007 Increments entry); on completion the implementation marks
the 007 entry complete. Everything below is not edited in this PR. The implementation updates it,
with the contract first:

- `.claude/rules/design_rules.md` — the processing state machine's `FAILED` rule ("A resubmitted
  message (fresh broker `messageId`, same `requestId`)") and the queue semantics bullet ("Replay
  tooling always mints a fresh `messageId`").
- `.claude/rules/technical-rules.md` — the Messaging bullet ("replay tooling always mints a fresh
  `messageId`").
- `.claude/agents/` definitions that state or check the replay rule: `code-reviewer.md` (the HIGH
  finding "Replay reusing the original `messageId`"), `qa.md` (the matrix entry treating a reused
  `messageId` as a footgun), `software-engineer.md` (the ASB consumer bullet) and `spec-validator.md`
  (two bullets: the message-contract bullet requiring any replay to mint a fresh `messageId`, and the
  idempotency-contract bullet describing a resubmission as "fresh broker `messageId`").
- `src/main/resources/application.yaml` and
  `src/main/java/uk/gov/hmcts/cp/yotresultsdistribution/config/YotResultsDistributionProperties.java`
  (with `PropertiesValidator`) — the new lock-duration setting and the legacy margin, and the
  start-up refusal of a margin shorter than the lock duration (FR-005).
- Source comments that state the old rule: `IdempotencyGuard` (the class table's two `FAILED`
  lines, the `recordNonTransientFailure` Javadoc on "a fresh identity replays", and the
  `replayOrPark` Javadoc on replay tooling minting a fresh `messageId`); the `REPLAY_FAILED`,
  `recordFailed` and `replayFailed` Javadoc in `ProcessedRequestRepository`; `DeliveryIdentity`,
  `RunClaim` and `ProcessedRequestRecord` (the message identity deciding the `FAILED` branch); and
  `RequestStatus` ("replayable under a fresh message identity").
- `V1__create_processed_log.sql` states the old rule in its `exhausted_message_id` comment, but it
  is an applied migration and **cannot be edited**; the new migration's own comment supersedes it.
- `specs/001-court-register-port/spec.md` (User Story 2's narrative and acceptance scenario 4, which
  describe replay under a fresh message identity) and `specs/001-court-register-port/data-model.md`
  (the processed-log constraint summary and the state-transition reference, which defers to the
  informant table now amended).
- `doc/DEFECT-FIXES.md` row C17 — no new C-number, but the row's pinning-test notes say
  `RequestDedupeIT` sends under distinct `messageId`s "so the queue's own dedupe cannot answer for
  the guard"; review whether the row's wording needs maintenance once replay no longer depends on a
  fresh identity.
- Tests that assert identity by `messageId` or the old re-park reason: `FailedReplayIT`,
  `IdempotencyGuardIT`, `MessageListenerSettlementTest`, `SettlementFailureEdgeTest`,
  `DeliveryExhaustionIT` and `SchemaMigrationIT`; `QueueSettlementIT` as the home of the
  sequence-number observations against the emulator; and the narrative in `DuplicateDetectionIT` and
  `RequestDedupeIT`, which cite "the resubmission runbook" requiring a fresh `messageId`. Swept and
  needing no change: `MessageAccountingIT`, `YotResultsDistributionEndToEndIT` and
  `ServiceTestSupport.publish` use a fresh `messageId` only so the broker's duplicate detection cannot
  answer for the guard, which stays true; `GenerationFailureEndToEndIT`'s "fresh identity" is a
  notification id, not a broker one.
- Test helpers that find messages or rows by `messageId` — `ServiceBusEmulatorTestSupport.peekFor`
  and `ProcessedLogTestSupport` — which become ambiguous once several accepted sends can share one;
  and `docker/servicebus-emulator/config.json` if a test queue with a short duplicate-detection
  window is added for the end-to-end re-send test.
- Flagged to their owners, not edited as part of this work: the Confluence design page *Court
  Register Service* (CRA space), its working copy
  `analysis/results-distribution/CourtRegister/service-cp-crime-court-register-design.md`, and any
  support runbook that tells operators to mint a fresh `messageId`.

## Assumptions

Each assumption below was checked against the broker documentation, the queue's definitions or the
code before this specification was written, or is marked where it was not.

- **Sequence numbers.** The broker assigns a sequence number when it accepts a message; it is unique
  within the entity, and a message resubmitted from the dead-letter queue receives a new sequence
  number and enqueue time. Redelivery does not re-enqueue, so it keeps the number.
- **Delivery count.** The broker's count is zero-based (a first delivery carries 0; the final
  permitted delivery carries `maxDeliveryCount - 1`, i.e. 4), as already observed against the emulator
  in `QueueSettlementIT`. It is incremented when a lock expires or a message is abandoned, but **not**
  when a message is released because its connection closed. This service stops its processor on every
  shutdown and also whenever it suspends intake because the processed log is unreachable. FR-005 is
  written with that in mind.
- **Queue shape and timings.** The queue's emulator definition (`docker/servicebus-emulator/config.json`)
  and its infrastructure definition (`cpp-terraform-azurerm-servicebus` `vars/ste.tfvars` on `main`,
  as `yotresultsdistribution.requests` since that repository's PR #28 renamed it from
  `courtregister.requests`) agree: duplicate-detection window 5 minutes,
  lock duration 1 minute, delivery limit 5, time-to-live 1 hour with dead-lettering on expiry, no
  sessions, no forwarding of messages or dead letters. The legacy margin (FR-005) of 5 minutes is
  therefore well above the lock duration. The service itself does not know the lock duration today
  (its only lock setting is the 5-minute renewal window), which is why FR-005 adds a mirroring
  setting. The queue and its namespace are **not partitioned**: the module on `main` sets no
  partitioning option anywhere, so the provider default (off) applies. For a partitioned queue sequence numbers would still
  be unique and usable for equality, but must not be used to order messages. If the
  duplicate-detection window were lengthened, same-`messageId` re-sends would be discarded for
  longer, and support would need a fresh `messageId` within it.
- **Portal re-send.** The portal's dead-letter re-send copies the `messageId`, and a message re-sent
  that way can be read normally by the service, sequence number included.
- **Dead-letter copies.** A dead-lettered message keeps the sequence number and enqueue time it had on
  the queue, so the dead-letter queue shows which delivery was parked.
- **No replay tooling in this service.** Nothing in the service mints a `messageId` or publishes to
  `yotresultsdistribution.requests`. The operations API's endpoints act on batches, registers, the
  flag and the exception report; the notify endpoint re-requests e-mails for a batch and does not
  touch the queue or the processed log.
- **Generation.** While a row is `FAILED`, nothing in the service changes its attempt count or update
  time: every write to `processed_request` is predicated on a live claim or on a non-terminal status,
  and a `FAILED` row holds no claim. Every path back out of `FAILED` and in again increments the
  attempt count. That is what makes the attempt count a sufficient generation for FR-006, and the
  update time the parking time for FR-005.
- **Classification.** The category of a failure is decided by the parking path's failure
  classification, not by its reason code: the typed failures are classified where they are raised,
  and an unexpected failure (and a run that reaches its processing deadline) is classified transient
  by the pipeline. FR-010's list is every reason a throw site currently classifies non-transient.
- **Policy.** A deliberate resubmission is any accepted new send. The service does not try to work out
  *who* sent it.
