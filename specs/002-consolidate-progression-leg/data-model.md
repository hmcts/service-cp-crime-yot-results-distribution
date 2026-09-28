# Data Model: Consolidate the progression court-register leg

The 001 processed log (`processed_request`, `processed_output`) is unchanged in its role; this file
records what 002 adds. All migrations are Flyway, service-owned Postgres: `V2__register_store.sql`
and `V3__active_row_unique.sql`. The second datasource (framework file service) is **not** migrated
by this service — its DDL is vendored under `contracts/fileservice/` for tests only.

## `processed_output` — becomes the register store

Existing (V1): `output_id PK`, `(source, request_id)` FK → `processed_request` ON DELETE RESTRICT,
`court_centre_id`, `court_centre_ou_code`, `register_date`, `file_name`, `request_digest`, `status`,
`response_code`, `sent_at`, `UNIQUE (source, request_id)`.

V2 adds:

| Column | Type | Notes |
|---|---|---|
| `document` | `jsonb` | The `CourtRegisterDocument` as recorded, validated **as it is written** against the vendored `courtRegisterDocumentRequest.json` (schema v17.103.13) - the register-document schema rather than the `add-court-register` command's, because the recorded document carries `defendantType` and the command declares no such field; `request_digest` is now its SHA-256. Nullable in the column, required of a recorded row by the shape check below |
| `hearing_id` | `uuid` | From the document; same nullability rule |
| `hearing_date` | `timestamptz` | From the document; same nullability rule |
| `court_house` | `text` | `hearingVenue.courtHouse` (as progression's column) |
| `register_time` | `timestamptz` | The document's `registerDate` instant (progression's `register_time`); `register_date` stays the London date part. Same nullability rule |
| `defendant_type` | `text` | `Applicant` / `Appellant` / `Respondent` / null (no court application) |
| `batch_id` | `uuid` FK → `register_batch` | NULL until assembled |
| `superseded_at` | `timestamptz` | Set in the recording transaction that superseded this row |
| `superseded_by` | `uuid` FK → `processed_output(output_id)` | The newer row |
| `recorded_flag_state` | `text NOT NULL DEFAULT 'UNKNOWN'` | `ON` / `OFF` / `UNKNOWN` — the flag as last read when recorded. The default is what an unread flag means, so a writer that omits the column states something true |
| `status` | widened | `RECORDED` → `GENERATED` → `NOTIFIED`; `SUPERSEDED`; `FAILED` (001's `PENDING`/`POSTED` remain valid for `progression-post` mode) |

Check: `processed_output_recorded_shape_chk CHECK (status NOT IN ('RECORDED','GENERATED','NOTIFIED',
'SUPERSEDED') OR (document IS NOT NULL AND hearing_id IS NOT NULL AND hearing_date IS NOT NULL AND
register_time IS NOT NULL))` — the four register columns are required of every row the recorder
writes and of no other.

**Rollout (expand now, contract later).** The four register columns are deliberately nullable rather
than `NOT NULL`. Flyway runs deferred, at the new pod's startup, so for the length of a rolling
deployment a pod on the previous release is still serving the queue against a schema that has
already moved, and it writes the old `progression-post` shape: no `document`, no `hearing_id`, no
`hearing_date`, no `register_time`. `NOT NULL` on those columns would fail every one of those
inserts and lose every register in flight until the rollout completed. The shape check carries the
invariant instead, and binds only the four statuses the recorder produces. A later **contract**
migration may tighten the four to `NOT NULL` and drop the check, once no pre-002 release can still
be running. `recorded_flag_state` needs no such treatment: a default is safe there because `UNKNOWN`
is exactly what a row written without reading the flag means.

Indexes: `idx_output_active_unbatched ON processed_output (court_centre_id, register_date) WHERE
status = 'RECORDED' AND superseded_at IS NULL AND batch_id IS NULL`; `idx_output_hearing ON
processed_output (hearing_id)`; and V3's `idx_output_active_register_key` below.

Invariants (asserted by `RegisterStoreIT`):
- At most one **active** row (RECORDED, unsuperseded) per `(hearing_id, court_centre_id, register_date)`.
- A row with a `batch_id` is never superseded and never edited except by `mark*` for its own batch.
- `superseded_by` points to a **register** with the same `hearing_id` and batch key that is **later
  in the order the table persists**, which is `(register_time, created_at, output_id)`: the register
  instant the estate shared, then the instant this database took the row, then the identity as the
  last deterministic tie-break. The instant alone is not enough - the estate can share one hearing
  twice at one instant, and the recorder settles that pair by arrival (statement 1's `<=`). A
  register and not merely a row of the key: 001's PENDING/POSTED/FAILED rows share the key and carry
  a `register_time` of their own, and a supersession against one of those names a replacement that
  does not exist (statements 9 and 9a).

**Enforcement of "at most one active row" (V3).** The recorder keeps the invariant by reading the
hearing's active row and superseding what it finds, and a read is what two re-shares of one hearing
can both do before either has committed: both find the same incumbent, both supersede it and both
insert an active register (`RegisterStoreIT.two_concurrent_re_shares_leave_exactly_one_active_row`).
`V3__active_row_unique.sql` therefore adds `idx_output_active_register_key UNIQUE ON
processed_output (hearing_id, court_centre_id, register_date) WHERE status = 'RECORDED' AND
superseded_at IS NULL AND batch_id IS NULL` - the same predicate the sweep and the recorder read
"active" with, so a superseded row, a batched row and 001's PENDING/POSTED rows are all outside it
(`SchemaMigrationV3IT`). `JdbcRegisterStore.recordAndComplete` meets the refusal as a
`DuplicateKeyException`, rolls the attempt back, re-reads the incumbent the winner left and records
against that instead, up to three attempts; a losing re-share is therefore recorded rather than
failed, and only a key that lost the race three times over raises a `ConcurrencyFailureException`
(the store answering: the delivery is handed back, intake keeps running). V1's
`processed_output_unique_request` is a different refusal wearing the same exception type and is told
apart from it on the driver's own message: that one is this command delivered again, and is answered
from the row it already wrote rather than retried as a race. Both keys are recognised by name and
neither by elimination: a duplicate-key refusal that is neither of them is a constraint the recorder
cannot act on, and it is raised as a classified non-transient `RegisterNotRecordedException` rather
than retried (`RegisterStoreIT.a_unique_violation_that_is_not_the_active_row_race_is_propagated_not_retried`). The recording statement supersedes **before** it
inserts, because the row being replaced holds the key until the update takes it out of the index.

**The five statements a person's own command reaches (Phase 7).** Nothing but the operations CLI
asks for these, so they are recorded here with the invariants they keep; three read or write
`processed_output` and two read `register_batch`.

- `JdbcRegisterStore` statement **4b**, `batchesOn(registerDate)` - every batch recorded for one
  register day, whatever state it reached. The read a regeneration starts from, and the one thing
  statement 4a cannot answer: a support call is about a day rather than a set of keys, and the day's
  FAILED batches are precisely the ones whose rows still carry a stamp and are therefore outside
  `ACTIVE_UNBATCHED`. Every state, for the reason 4a reads every state (design Q27): a FAILED batch
  may be released and re-assembled, a notified one is what a supplementary index is counted over,
  and one still in flight is why a key is left alone.
- Statement **9a**, `releaseFailed(batchId)` - a FAILED batch's registers given back, so the day may
  be rendered again. The other half of statement 9: two of the six failure reasons release the stamp
  as they fail and the other four leave it, because systemdocgenerator was asked and a document may
  yet exist, so no later run will ever pick those rows up. Re-rendering is a decision a person
  makes, and this is that decision written as a statement. Fenced on the batch's own FAILED in the
  statement as well as before it, because a count of nought otherwise means two different things,
  and it answers the rows it released rather than leaving them to be read back. A row the hearing
  has since been re-shared for is superseded against the re-share as its stamp is cleared, and is
  left out of the answer: a recording supersedes an incumbent that is unbatched, so a re-share that
  arrived while the row was stamped left the key holding two rows, and unstamping the older one is
  what would make both active - the invariant is kept by this statement rather than defended by
  `idx_output_active_register_key`. The row it is superseded against has to be a **later** one:
  the key can hold the pair either way round, because a first batch that kept its stamp or reached
  NOTIFIED leaves its register beside the re-share rather than superseded by it, so the batch being
  released may be the one holding the newer row - and superseding that against the register it
  replaced would withdraw the current register for good while the statement answered as though the
  day held nothing to release. **Later is the whole of `(register_time, created_at, output_id)`**,
  which is statement 1's `<=` read as a total order rather than asked of one incumbent: the estate
  sets `register_time`, so one hearing can be shared twice at one instant, and the row that arrived
  second is then the current one - which is what the database clock wrote in `created_at`. A tie
  broken on the identity alone agrees with the recorder in one of the two orders and, in the other,
  does not see the successor at all: the older row is unstamped into a second active register for the
  key, `idx_output_active_register_key` refuses the write, and the release fails on a key nothing
  said was wrong. Statement **9** releases under the same rule and for a sharper reason: its release
  is a branch of the statement that marks the batch, so a collision there takes the mark down with it
  and leaves the batch calling itself in flight under a run that had already given up on it. Both
  directions of the equal-instant pair are pinned per statement, in `RegisterStoreIT`'s `Failure` and
  `Releasing` (`..._an_equal_time_re_share_that_sorts_first` / `..._that_sorts_last`).
  **And the successor has to be a register, which is fewer rows than the key holds.** This table is
  also 001's submission log: `ProcessedOutputRepository` writes a PENDING, POSTED or FAILED row for
  every register POSTed to progression, on the same `(hearing_id, court_centre_id, register_date)`
  and with the instant of the claim in `register_time`, and a rolling deployment has that writer
  live against a schema that has already moved. Both statements therefore name the states a live
  register is in - **`status IN ('RECORDED', 'GENERATED', 'NOTIFIED')`** - rather than excluding the
  ones it is not (`<> 'SUPERSEDED'`, which is what they said and what admitted 001's three): the
  list is closed by `processed_output_status_chk`, which bounds the column at seven, of which the
  recorder writes four and SUPERSEDED is the one of those that is by definition not live. Against a
  POST row the register is written SUPERSEDED, unstamped, left out of the answer and reachable by no
  later run and no command - neither statement collides with `idx_output_active_register_key` on the
  way, a POST row being outside that partial index, so the day's document is lost silently and
  `generate-register` prints a day it released nothing for and exits 0. Pinned per statement by
  `Failure`/`Releasing.a_..._should_give_its_register_back_though_a_post_row_shares_the_key`, whose
  POST row is written through `claimPending` itself rather than as a shape the suite invented.
- Statement **11**, `recordedWhileOff()` - the registers automatic batching passed over. Statement
  2's predicate with its fourth test turned round, and deliberately one predicate with it: RECORDED,
  unsuperseded and unbatched in both, `recorded_flag_state <> 'ON'` the only difference (research
  §12), oldest first by the register instant as statement 2 is. `<> 'ON'` and not `IN ('OFF',
  'UNKNOWN')`, because the column is NOT NULL over a vocabulary the schema bounds and a state added
  later is one this read must answer with by default.
- Statement **12**, `supersedeSharedBefore(instant)` - the rollback lever's other half. Bounded
  strictly before the instant on `register_time`, the register's own shared instant, because the
  period the legacy has taken back over is a period of hearings and not of this pod's writes. It
  takes statement 2's first three predicates and not its fourth: a stamped row is the renderer's,
  so what becomes of it is its batch's ending to decide rather than a period's, a superseded row is
  not superseded twice, and a GENERATED or NOTIFIED row is not rewritten to say a register that was
  sent was never claimed. Whether the flag was on when a row arrived is outside the predicate: a
  rollback supersedes the period, and a row recorded while the flag was off is in that period too.
  The stamped row it leaves is the one thing the write does not settle: statements 9 and 9a both
  unstamp a RECORDED row back to unbatched and neither can consult a rollback, nothing in the schema
  recording that a period was taken back - so a rollback over a day whose batches are still open is
  run again after any release of them, and the count it answers with is what says whether it had
  anything left to take.
- `RegisterBatchRepository` statement **12**, `findByRegisterDate(registerDate)` - the read behind
  `list-batches --date D`, ordered court house then identity, which is what makes the output stable
  across two runs; a court house the hearing venue never named sorts last.

All five are pinned against a real Postgres rather than through a doubled store: `RegisterStoreIT`'s
`DayBatches`, `Releasing`, `RecordedWhileOff` and `Rollback` and `RegisterBatchRepositoryIT`'s
`DateListing`, sixteen cases red at `903d33b` and green at `e43cca3`. `Rollback` works in a register
day of its own (2019-01-07), earlier than every register any container-backed suite records, because
`supersedeSharedBefore` is the one write in that suite not scoped to a court centre: it takes back a
period, so it reads the whole shared table and its answer counts the whole table.

## `register_batch`

| Column | Type | Notes |
|---|---|---|
| `batch_id` | `uuid PK` | Minted at assembly; sent to SDG as `sourceCorrelationId`, and the **only** identity an outcome is attributed by (see the note under `payload_file_id`) |
| `court_centre_id` | `uuid NOT NULL` | |
| `court_centre_ou_code` | `text` | |
| `court_house` | `text` | |
| `register_date` | `date NOT NULL` | |
| `file_name` | `text NOT NULL` | First record's `fileName` (as progression) |
| `payload_file_id` | `uuid` | Minted **before** the file-service insert; sent as `payloadFileServiceId`. On the way back it is a **cross-check and never a lookup**: `DocumentOutcomeSink` finds the batch by `sourceCorrelationId` alone and requires the event's `payloadFileServiceId` to equal this column, counting an event where they disagree on `yotresultsdistribution_public_events_ignored_total{reason=payload-mismatch}` and applying it nowhere. An outcome whose correlation names no batch is counted `{reason=unknown-correlation}` and is **not** looked up by payload instead: an event that has lost or crossed its correlation would otherwise complete a batch it was never about. `RegisterBatchRepository` therefore offers no read by payload at all: the reconciler's sweep reads the payload id off the batch row it is already holding, so nothing needs one |
| `document_file_id` | `uuid` | From `document-available` (`documentFileServiceId`) or the query API |
| `status` | `text NOT NULL` | `PENDING` → `GENERATING` → `GENERATED` → `NOTIFIED` \| `PARTIALLY_NOTIFIED` \| `NOTIFIED_NOBODY` \| `FAILED` |
| `failure_reason` | `text` | `PAYLOAD_STORE_UNAVAILABLE` \| `RENDER_REQUEST_FAILED` \| `RENDER_REQUEST_REJECTED` \| `GENERATION_FAILED` \| `GENERATION_TIMED_OUT` \| `ASSEMBLY_FAILED` |
| `sdg_reason` | `varchar(512)` | SDG's `reason` from `generation-failed` / query, never logged at INFO. Bounded before the write by `RegisterBatch.boundedReason`: a longer message is stored as its first 500 characters plus the marker ` [truncated]`, so the row is exactly 512 and a reader can tell there is more |
| `system_generated` | `boolean NOT NULL` | true from the schedule, false from the CLI (progression's flag) |
| `completed_by` | `text` | `EVENT` \| `RECONCILER`, feeds the `reconciled` metric. Written by the `mark` that learned the outcome, in that mark's own statement: a batch state change is a compare-and-set, so there is no moment either side of the transition in which this could be set on its own. NOT NULL on `GENERATED` and on the three notified states it is reached through; on `FAILED`, set exactly for the two generator-attributed reasons (`GENERATION_FAILED`, `GENERATION_TIMED_OUT`, which is `BatchFailureReason.isGeneratorAttributed()`) and NULL for the other four, which are this service's own verdict about a render nobody outside it answered for |
| `assembled_at`, `requested_at`, `generated_at`, `notified_at`, `failed_at` | `timestamptz` | |
| `attempts` | `int NOT NULL DEFAULT 0` | Lifetime tally, never a control variable |
| `supplement_of` | `uuid` FK → `register_batch(batch_id)` | The batch this one follows for the same key; NULL on a day's first batch |
| `supplement_index` | `int NOT NULL DEFAULT 0` | 0 on a day's first batch, counting up from 1 on each supplementary one; the file name is built from it |
| `notifying_since`, `notifier_token` | `timestamptz`, `uuid` | The notifying leg's claim (below). `notifying_since` is the instant the lease currently runs from - the moment of the claim, then of each renewal - and `notifier_token` is what every renewal, release and settlement is fenced on. Written and cleared together - `register_batch_notifier_claim_chk` requires that - and **not** components of the `RegisterBatch` domain record: what a batch is does not include who is currently telling its recipients, and a claim on the record would be a field every caller of the whole-row compare-and-set could overwrite |

Constraint: `UNIQUE (court_centre_id, register_date) WHERE status IN ('PENDING','GENERATING',
'GENERATED')` (partial unique) - one **in-flight** batch per key, those being the three states in
which a batch is still owed something (a render request, a render outcome, an e-mail). The four
terminal states are alike: a FAILED batch may be re-assembled (new `batch_id`, rows re-stamped), and
a NOTIFIED / PARTIALLY_NOTIFIED / NOTIFIED_NOBODY one may be followed by a supplementary batch
(below).

Check: `register_batch_completed_by_shape_chk`, the `completed_by` rule above as a shape the row
keeps, for the writers that do not go through the store (the CLI's whole-row write): NULL on
`PENDING` / `GENERATING`, which are the states that have no outcome for a mechanism to have learned;
NOT NULL on `GENERATED` / `NOTIFIED` / `PARTIALLY_NOTIFIED` / `NOTIFIED_NOBODY`; and on `FAILED`
present exactly when `failure_reason` is one of the two generator-attributed reasons. Written as
three implications rather than a disjunction with an "everything else" arm, so all seven states are
covered and none is covered by omission; a status outside the vocabulary is
`register_batch_status_chk`'s refusal to report. `JdbcRegisterStore` refuses a
contradictory `markGenerated` or `markFailed` before it issues a statement and
`RegisterBatchRepository` refuses an attribution on a batch that has not finished, so the same rule
is enforced twice and stated once (`BatchFailureReason.isGeneratorAttributed()`).

**The notifying leg is single-runner, under a claim on the batch (decided 2026-09-07).** Two
mechanisms reach one generated batch - the outcome sink on a delivered `document-available`, and an
operator's `notify-register --batch` resend - and both derive the same owed set from the same
records. Without a claim both POST for every recipient and both then settle the rows and the batch:
a Youth Offending Team gets a register about children twice, an unconditional settlement can write
FAILED over the ACCEPTED row the other run has just written, the two runs' attempt counts are lost
against each other, and the second tally is taken while the first run is still writing.

`RegisterNotifierService.notify` and `.resendFailed` therefore both claim the batch first.
`RegisterBatchRepository.claimForNotification(batchId, token)` takes
`pg_advisory_xact_lock(hashtext(batch_id::text))` and then compare-and-sets `notifying_since = now()`
and `notifier_token = :token` **where the batch is unclaimed or its claim is past the lease**, both
statements in one short transaction; `releaseNotificationClaim(batchId, token)` clears the pair,
fenced on the token, in a `finally`. The advisory lock serialises the claim attempts so the
compare-and-set that follows is the only one running; being transaction-scoped, it is given back
when that short transaction commits, which is before the first POST.

**The claim answers three things and not two (revised 2026-09-07).** A compare-and-set that changed
no row means another notifier holds a live claim, or it means this store holds no batch under that
identity, and the two are not the same night: the first is an ordinary evening with the outcome sink
and an operator's resend both reaching one generated batch, and the second is a caller acting on a
correlation nothing was ever assembled under. Answering the first for the second put a lost
correlation into the reading a claim nobody can take is chased by, and reported a batch that does not
exist as a batch somebody is busy telling. So the method answers
`NotificationClaim` - `CLAIMED` | `ALREADY_CLAIMED` | `ABSENT` - with the existence read
(`SELECT EXISTS (SELECT 1 FROM register_batch WHERE batch_id = :batchId)`) made only where the claim
was refused, inside the same transaction and under the same advisory lock, so the two answers are two
readings of one moment: a batch assembled between a failed claim and a later read would otherwise be
answered absent when it is merely somebody else's. `RegisterNotifierService` counts and logs
`already-notifying` for `ALREADY_CLAIMED` alone; `ABSENT` raises the not-found failure the service
already had (`no register batch <id> to tell the recipients of`), a step earlier than before. Pinned
by `RegisterBatchRepositoryIT
.claiming_a_batch_this_store_never_assembled_should_say_there_is_no_such_batch` and
`RegisterNotifierServiceTest.AnAbsentBatchIsNotContention`.

**Why a claim and not one transaction round the cycle.** The alternative considered was a single
transaction from the read through the POSTs to the settlement, with the advisory lock held across
it. That is rejected: the cycle POSTs to notificationnotify once per recipient and waits
`initial-backoff`-to-`max-backoff` between its own retries, so the transaction would hold a
connection and a row lock for as long as another service takes to answer a whole batch's e-mails.
The claim is the same shape the intake half's `RunClaim` has, for the same reason, and the lease
answers the same question: a pod that died mid-notification leaves the claim behind, and a claim
nothing can ever take is a batch no resend and no reconciliation could pick up. Expiry is decided by
the database comparing its own `now()` against the stored instant, never by a JVM clock reading. The
loser of the claim posts nothing and answers `NotificationDisposition.ALREADY_NOTIFYING` with the
rows as they stood, which is the winner's work part-done: a caller branches on the disposition, never
on those counts.

**The lease is the notifying leg's own, and it is renewed (revised 2026-09-07).** It was
`yotresultsdistribution.generation.grace-period` - ten minutes, on the argument that how long a notifier is
given to finish is how long the safety net waits before it looks. That is wrong twice over. The two
questions are different: how long a batch may hold a document before the reconciler looks is no bound
at all on telling that batch's recipients, whose cost is the number of Youth Offending Teams the
batch is addressed to times whatever notificationnotify makes of each of them - a batch with twelve
subscribers, each costing up to `yotresultsdistribution.endpoints.max-attempts` POSTs with a connect timeout, a
read timeout and a back-off wait apiece, can outlast ten minutes without anything having gone wrong. And ownership was
never rechecked: once the lease lapsed a second notifier could take the claim while the first was
still posting and settling under a token the row no longer carried, which is the state the claim
exists to prevent.

So the lease is **`yotresultsdistribution.notification.claim-lease`** (default `15m`), and
`RegisterBatchRepository.renewNotificationClaim(batchId, token)` -
`UPDATE register_batch SET notifying_since = now() WHERE batch_id = :batchId AND notifier_token =
:token` - is asked **before every POST, the retries of one recipient included, before each row's
settlement and before the batch's own**. Before every POST and not once per recipient: renewing once
for the recipient and spending that renewal across its retry cycle fenced the first attempt and none
of the others, and the others are the ones made after a read timeout and a back-off wait, which is
precisely how long a lease has been left unrenewed. One statement, because the re-check and the
extension are one question asked at one moment: is the batch still yours, and if so let the lease
cover what you are about to do. Token-fenced for the reason the release is - a renewal keyed on the
batch alone would let a notifier whose claim had been taken over extend the claim of the notifier
that took it.

The lease therefore bounds **one recipient's retry cycle** rather than a whole batch of them, and
startup (`PropertiesValidator.validateTheNotificationClaimOutlastsOnePostCycle`) refuses any value
below

```
NOTIFICATION_LEASE_MARGIN (2) x (max-attempts x (connect-timeout + read-timeout)
                                 + (max-attempts - 1) x max-backoff)
```

read off the shared `yotresultsdistribution.endpoints.*` transport - `2 x (3 x (5s + 10s) + 2 x 2s)` = **98s**
at the shipped values, which is what makes the shipped `15m` generous. The connect timeout is charged
because an attempt that hangs on the connect and then on the read is the longest single thing this
leg does, and the waits are the gaps **between** attempts, of which there is one fewer than there are
attempts (`max-backoff` per wait rather than the doubling schedule, because a `Retry-After` is
honoured on every retryable answer and the ceiling is the only thing bounding what the other side can
ask for). The rule is unconditional, because the way this breaks in practice is a deployment
lengthening `connect-timeout` or `read-timeout`, or raising `max-attempts`, and leaving the lease
where it was.

A renewal that is refused means the batch has been taken over, and the notifier that lost it **stops
and settles nothing further**: no further POST, because the notifier that now holds the batch derives
the same owed set from the same records; no row settlement and no batch settlement, because either
would be written over that notifier's work. It answers `NotificationDisposition.CLAIM_LOST` with the
rows and the state as they stood, counted on
`yotresultsdistribution_notifications_ignored_total{reason=claim-lost}` - apart from `already-notifying`,
which is contention rather than loss: a notifier that never got the claim, whereas this one held it
and began the cycle. **How much of the cycle it got through is not fixed.** The renewal is asked in
front of every POST, so the one that is refused can be the renewal before the very first POST - a
cycle that posted for nobody - or the one before the batch's own settlement, with every recipient
already posted for; zero or more POSTs were really made. The rows it left unsettled stay under the
identities they hold and are re-requested by a later run, whose POST reaches notificationnotify's
own aggregate rather than asking for a second e-mail.

**Every settlement write is token-fenced; the tally-only write after a lost claim deliberately is
not.** The renewal statement *is* the ownership re-check, so each row's settlement and the batch's
own mark are made only by a notifier that has just re-established that the batch is its own. The one
write a notifier that has lost the claim still makes is the attempt tally
(`RegisterNotificationRepository.tallyAttempts`: `attempts = attempts + :posts` and no other column),
and it is fenced on the row's identity alone - not on the claim, which has already gone, and not on
the row's status. The POSTs made before the renewal was refused were really made, notificationnotify
has them, and leaving them off the row's lifetime total loses exactly the attempts spent in the window
two notifiers were in the cycle at once, which is the window the count is reached for: a row two
notifiers posted for would read as one notifier's work. A tally that finds no row to add to is the
same fault `NotificationSettlement.ABSENT` names and is reported the same way, on
`{reason=settlement-row-absent}`.

**What a call to this leg answers is `NotificationDisposition`, and it is four things.** A caller
branches on it and the `reason` label is derived from it; the counts it travels with cannot say any
of this, because the counts a loser reads are somebody else's work in progress and a batch state is
where the batch stands rather than what this call decided.

| Disposition | What the call did |
|---|---|
| `SETTLED` | Held the claim throughout, posted for whoever was owed an e-mail, and settled the batch on the tally |
| `ALREADY_NOTIFYING` | Never got the claim, so posted nothing and settled nothing. Contention and not loss: the batch is being told by somebody else, and this caller has nothing left to do. Counted `{reason=already-notifying}` |
| `CLAIM_LOST` | Held the claim, began the cycle, and had a renewal refused part way through it - which can be the renewal before the very first POST, so zero or more POSTs were made. Any that were are on their rows' tallies; the settlements belong to the notifier that now holds the batch, and an absent row met by that tally is reported without changing this answer. Counted `{reason=claim-lost}` |
| `INCOMPLETE` | Held the claim throughout and could not finish the cycle: a **settlement** was made for a row this run read back or minted and the store does not hold it. Reserved for that write, and so for a notifier that still owned the batch. The batch is **not** settled. The fault itself is counted `{reason=settlement-row-absent}` |

Pinned by `RegisterBatchRepositoryIT.Claiming` (the store's half: the advisory lock and
compare-and-set, the second notifier's refusal, the token-fenced release, the takeover past the lease,
`…renewing_the_claim_should_extend_it_only_for_the_notifier_that_holds_it` and
`…a_renewal_after_a_takeover_should_change_nothing_for_the_old_token`),
`RegisterNotifierServiceTest.TwoNotifiersOnOneBatch` and `…TheClaimTakenOverMidCycle` (the service's
half), and `ConfigurationValidationTest.NotificationLeaseAgainstOnePostCycle` (the startup rule). The
claim is **not** defence enough on its own, which is why the row-level fences below exist too.

**Supplementary batches for late re-shares (design Q27, decided 2026-09-06).** A same-day re-share
recorded after its (court centre, register date) batch has been **sent** becomes a **supplementary
batch for the same key**, assembled by the next run once **every** earlier batch for that key is
terminal. The supplementary batch names the batch it follows in `supplement_of` and carries the next
`supplement_index` (1 for the first supplement, counting up); a day's first batch has `supplement_of`
NULL and `supplement_index` 0. While any batch for the key is still in flight the rows simply wait,
because the narrowed partial unique index above admits one PENDING / GENERATING / GENERATED batch per
key and no more.

**Which terminal predecessor is followed, and which is replaced.** All four terminal states free the
key and they are alike only in that; the two halves of the sentence above are two different answers.
A **FAILED** predecessor produced no document and told nobody, so its rows are re-assembled as the
day's first document - `supplement_of` NULL, `supplement_index` 0, the register's own `fileName` -
and a supplement that failed is re-assembled at the index it failed at rather than one past it.
`BatchAssembler` therefore takes the next index over the key's NOTIFIED / PARTIALLY_NOTIFIED /
NOTIFIED_NOBODY batches only (`BatchAssemblerTest
.a_key_whose_only_earlier_batch_failed_should_be_re_assembled_not_supplemented`,
`…a_failed_supplement_should_be_re_assembled_at_the_index_it_failed_at`). The distinction is visible
outside this service: the file name is what the file-service `metadata` row records and what
systemdocgenerator renders under, so counting a failure would name a day's first document
`-supplementary-1` and leave nothing for it to be a supplement to.

A supplementary batch's **file name** is the first row's `fileName` with `-supplementary-<index>`
inserted before the extension - `yotresultsdistribution_2026-08-20.json` becomes
`yotresultsdistribution_2026-08-20-supplementary-1.json`. `BatchAssembler` (T043) builds it; the schema only
records the index it is built from.

The alternatives Q27 weighed were a second unrelated live batch for the key, which records nothing
about why a day has two documents, and surfacing such rows through list-batches for CLI generation,
which makes a routine re-share an operator's job. The link keeps the day's documents ordered and
attributable, and leaves the nightly job able to send them without being asked.

## `register_notification`

| Column | Type | Notes |
|---|---|---|
| `notification_id` | `uuid PK` | Sent to NN as `notificationId`; minted **before** the POST; reused on retry |
| `batch_id` | `uuid NOT NULL` FK → `register_batch` | |
| `email_address` | `text NOT NULL` | `recipient.emailAddress1` |
| `recipient_name` | `text` | `recipient.recipientName` → `personalisation.yotsName` |
| `template_name` | `text NOT NULL` | `cr_standard` |
| `template_id` | `uuid NOT NULL` | Resolved at startup |
| `status` | `text NOT NULL` | `PENDING` → `ACCEPTED` \| `FAILED` |
| `response_code` | `int` | |
| `sent_at` | `timestamptz` | **The settlement instant of every terminal attempt**, an acceptance and a refusal alike: what it records is when this service decided how the attempt ended, not when NN accepted anything. So a FAILED row carries it too, and a connect failure that reached no verdict is settled at the instant the run gave up on it. Empty on one row shape only - a minted row, written PENDING before its POST, which has nothing to stamp yet |
| `attempts` | `int NOT NULL DEFAULT 0` | Accumulates the POSTs made for the row, not the runs that made them: a transient refusal retried inside one call adds each attempt, and so does an attempt whose settlement the row's own ACCEPTED held off (below), and so do the POSTs a notifier had already made when its claim was taken over, written on the way out by the tally-only statement (below) - a POST that happened is on the total whatever state the row is in and whoever settles it |

Constraint: `UNIQUE (batch_id, email_address)`.

**ACCEPTED is terminal at the row level, the attempt tally is not, and both are computed in SQL.**
The batch claim above serialises the ordinary case; this is what holds when it does not - a claim
whose lease ran out under the run holding it leaves two notifiers in the cycle at once. So the
settlement statement reads the row under `FOR UPDATE` in a `held` term, sets each of `status`,
`response_code` and `sent_at` through a `CASE WHEN held.status = 'ACCEPTED' THEN <the column> ELSE
<the parameter> END`, always sets `attempts = attempts + :posts`, and answers
`RETURNING held.status <> 'ACCEPTED'`.

A run can read a row as unsettled, POST for it, and only then find the other run's POST was accepted
in between: an unconditional settlement would demote that row to FAILED, so the team that has been
told reads as untold, the batch goes back to PARTIALLY_NOTIFIED, and the resend that follows sends
the register again. The settlement columns therefore keep what the acceptance wrote. **The tally is
outside that fence deliberately**: the POST was really made, and fencing it - which
`WHERE ... AND status <> 'ACCEPTED'` did - left off the lifetime total exactly the attempts made in
the window the fence exists for, so a row two notifiers posted for read as one notifier's work in
the one situation where knowing otherwise matters. The `attempts` arithmetic is the statement's for
the neighbouring reason: two runs that each read the row at nought and each write an absolute total
both write the same number, so one run's POSTs are simply lost.

`RegisterNotificationRepository.update` therefore answers `NotificationSettlement` - `APPLIED`,
`ATTEMPTS_ONLY`, or `ABSENT` for a row the store does not hold - rather than a changed-row count,
which could not tell the last two apart because both changed nothing.
`RegisterNotifierService` counts each on `yotresultsdistribution_notifications_ignored_total`:
`{reason=late-failure-ignored}` where this run's own attempt failed against an accepted row,
`{reason=late-acceptance-ignored}` where it was accepted against one - two 202s for one recipient is
a Youth Offending Team holding two copies of a register about children, and filing it under the
milder reason would hide it - and `{reason=settlement-row-absent}`, whose only honest reading is a
row this service wrote and the store has lost. Two further reasons share the counter and are about
the claim rather than about a row: `{reason=already-notifying}` is the notifier that never got the
claim, which is contention and not loss, and `{reason=claim-lost}` the one that had a renewal refused
part way through the cycle. Something that changed nothing has to be visible, or the only trace is a
row that looks untouched. Pinned by `RegisterNotificationRepositoryIT
.a_late_failure_against_an_accepted_row_should_be_tallied_and_not_settled`,
`…a_late_acceptance_against_an_accepted_row_should_be_tallied_and_not_re_settled`,
`…settling_a_recipient_this_service_never_minted_should_say_there_is_no_such_row` and
`…two_settlements_computed_from_one_read_should_each_add_their_own_attempts`, and at service level by
`RegisterNotifierServiceTest.AnAcceptedRowIsTerminal`.

**`ABSENT` ends the cycle, and it is the one answer that leaves the batch unsettled.** A settlement
made for a row this run read back or minted, and that the store then does not hold, means the
attempt is recorded nowhere: no tally taken afterwards is a complete account of what this batch's
recipients were sent. So the recipients after it are not asked (a batch that cannot be settled is
not made more settleable by more POSTs), the claim is given back, `markNotified` is never reached,
and the call answers `NotificationDisposition.INCOMPLETE`. **That answer belongs to the settlement
write alone**, and so to a notifier that still owned the batch: the same absent row met by the
tally-only write a lost claim leaves behind is the same fault, reported the same way, but that call
answers `CLAIM_LOST`, because the batch is no longer its own. Settling anyway was the worse answer,
and silently so where the vanished row was the only one: nought rows tallies to `NOTIFIED_NOBODY` -
P1's terminal state, saying the document was rendered and there was nobody to send it to - written
over a batch addressed to a Youth Offending Team all along, and terminal, so no later resend could
revisit it.

**The batch stays where it stands instead, and nothing recovers it unasked.** What recovers it is
an operator's explicit `notify-register --batch` resend (the Phase 7 CLI), and nothing else: the
outcome sink drives `notify(batchId)` once, on the transition into GENERATED, and suppresses the
callback for a batch that is already GENERATED, so no event redelivery revisits it. The resend
derives the owed set from the records again, **mints the missing row**, posts under it and settles
the batch on a tally that then accounts for every recipient
(`RegisterNotifierServiceTest.AVanishedRowEndsTheCycle`). The reconciler is not that call: its third
read over GENERATED batches names them and publishes `yotresultsdistribution_oldest_generated_age` from them,
and settles nothing.

**The batch is re-read before it is settled**, inside the same claim. The row a run started from is
minutes old by the time the last recipient has been posted for, and `markNotified` is a
compare-and-set against the state the caller read: a stale state is either a write the store refuses
or - where the machine happens to draw the move - a second settlement of a batch something else has
already finished. The tally is taken off the table at that same moment, so both halves of the verdict
are read at one instant (`RegisterNotifierServiceTest
.a_batch_another_mechanism_moved_should_be_recognised_rather_than_re_settled`).

**PENDING is unsettled, not untouched.** The row is minted PENDING before the POST and settled
after it, so a run that stopped in between - the pod died, the store blipped on the update, the
listener's session rolled the JMS delivery back after the JDBC mark had already committed - leaves a
row that cannot say whether the e-mail was asked for. Both entry points therefore treat it as owed:
`RegisterNotifierService.notify` and `.resendFailed` re-request every row whose `status <>
'ACCEPTED'`, and mint only for the addresses the batch holds no row for, so either can be run again
at all - minting for every recipient a second time is what `UNIQUE (batch_id, email_address)`
refuses. An ambiguous downstream outcome is retried, and the retry is safe because it goes out under
the `notification_id` the row already holds: NN keys its aggregate on it, so the second POST reaches
the attempt it is retrying (§10).

**A row that was never written is owed too, and that is why the debt is read off the records.** Both
entry points take the recipient union across the batch's records (`RecipientSet`, P4) as the set of
teams owed an e-mail and the rows as what this service has written down about it: a run that stopped
between the GENERATED mark and the first insert, or between two inserts, leaves a GENERATED batch
whose teams have no row at all. A resend that read only the rows
(`RegisterNotificationRepository.findUnsettledByBatchId`) found nothing to send and settled such a
batch `NOTIFIED_NOBODY` - P1's terminal state, on a batch that had recipients all along - so
`NOTIFIED_NOBODY` is now reachable only where the union itself is empty. The whole batch is read
(`findByBatchId`) because the sending path has to know which addresses are held as well as which are
owed; `findUnsettledByBatchId` answers what is outstanding, for the `notify-register` report and an
operator's question. Pinned by `RegisterNotifierServiceTest.RecoveringAnUnsettledRow` and
`RegisterNotificationRepositoryIT
.reading_the_resendable_recipients_should_answer_with_every_row_never_accepted`.

**A transient refusal is retried before the row is settled.** A 408, 429 or 5xx, or a transport
failure, is asked again up to `yotresultsdistribution.endpoints.max-attempts` under the same
`notification_id` with the shared `RetryPolicy`'s back-off, and only then FAILED with the last
status; a NON_TRANSIENT refusal is settled on the first attempt (research §11,
`RegisterNotifierServiceTest.RetryingWhatMayAnswerDifferently`). No deadline is measured against
each attempt, which is the one place this differs from the generation leg: that leg runs inside the
nightly run's claim and charges every attempt against what is left of it, whereas this leg holds a
notification claim whose lease it renews before every POST instead - the retries included, so no
attempt is made by a notifier that has not just asked whether the batch is still its own. What bounds
one recipient's cycle is therefore the attempt budget and `max-backoff`, and startup holds the lease
to twice that cycle (above).

## `shedlock`

Standard ShedLock JDBC schema: `name varchar(64) PK`, `lock_until timestamptz`, `locked_at
timestamptz`, `locked_by varchar(255)`.

## State machines

**Per command** (unchanged from 001 except the last leg):
`RECEIVED → … → COMPLETED{recorded | group-proceedings | no-defendants | no-subscriptions |
no-youth-defendants} | FAILED{SCHEMA_INVALID | …}`. The output row is **written RECORDED in the
same transaction that completes the command**. The completion is a second statement against
`processed_request` and it belongs to `IdempotencyGuard`, not to the store, so the pipeline hands it
to `RegisterStore.recordAndComplete` as the thing to do inside the recording's transaction and the
adapter issues it there, through a client over the same datasource: neither write survives without
the other (`RegisterStoreIT.a_completion_that_could_not_be_written_takes_the_recording_with_it`,
`…a_completion_the_guard_refused_takes_the_recording_with_it`, and
`CrashWindowIT.a_crash_between_the_register_and_its_completion_should_leave_neither`). A completion
the guard refuses - the claim was reclaimed while the run worked - rolls the recording back for the
same reason it would be wrong to keep it: the new owner records the register again, and this one
would only be superseded.

There is therefore no window between the register and its command's completion. A delivery can still
stop *after* both, before the broker learns the message was settled, and the message is delivered
again; the guard answers most of those `ALREADY_COMPLETED` without reaching the store, and the
recording is **idempotent on `(source, request_id)`** for the rest - each attempt reads that key
inside the recording transaction and answers a command it has already recorded with the row it
wrote, the row that recording superseded included, writing nothing and superseding nothing
(`RegisterStoreIT.a_redelivered_command_is_answered_with_the_register_it_already_recorded` and
`…a_redelivered_re_share_supersedes_nothing_a_second_time`). It is the property 001's POST path gets
from `ON CONFLICT (source, request_id)`, kept rather than lost.

**Per batch**:

```
PENDING ──payload stored + 202──▶ GENERATING ──document-available──▶ GENERATED ──all 202──▶ NOTIFIED
   │                                 │                                  │             ├─ some FAILED ──▶ PARTIALLY_NOTIFIED
   │                                 ├─ generation-failed ──▶ FAILED    │             └─ no recipients ▶ NOTIFIED_NOBODY
   │                                 └─ grace period → reconcile → GENERATED | FAILED(GENERATION_FAILED | GENERATION_TIMED_OUT)
   ├─ store unavailable ──▶ FAILED(PAYLOAD_STORE_UNAVAILABLE)   [rows stay RECORDED; next run re-assembles]
   ├─ request rejected / exhausted ──▶ FAILED(RENDER_REQUEST_*)
   └─ grace period, payload_file_id set → reconcile → GENERATED
                                                    | FAILED(GENERATION_FAILED
                                                            | GENERATION_TIMED_OUT
                                                            | RENDER_REQUEST_FAILED)
PARTIALLY_NOTIFIED ──notify-register --batch (resend the rows never ACCEPTED)──▶ NOTIFIED
GENERATED ──grace period, rows unsettled──▶ (reported on yotresultsdistribution_oldest_generated_age)
          ──notify-register --batch | notify(batchId) again──▶ NOTIFIED | PARTIALLY_NOTIFIED
FAILED ──generate-register --batch (new batch_id)──▶ PENDING
```

**The batch parked at GENERATED, and why it has a reading of its own.** Notification follows
`markGenerated` in one step of one code path, so a store that went away in between - or a listener
session that rolled the delivery back after that mark had committed - leaves the batch holding its
document with rows nothing settled. `generatingSince` reads GENERATING and `pendingSince` reads
PENDING, so until `yotresultsdistribution_oldest_generated_age` existed the one state that leaves a Youth
Offending Team untold was the one state no gauge moved for - P1's failure mode by another route. The
reconciler takes that reading from a third read (`RegisterBatchRepository.generatedSince`, statement
5) and settles nothing there: the document exists, so the batch is owed its e-mails and both
`resendFailed` and `notify` are re-entrant and send exactly those, while failing it would throw the
document away.

**A notifying arrow is not drawn for a cycle that could not account for a row.** The `ABSENT` answer
above leaves the batch unmarked: a run that posted under a row the store no longer holds gives the
claim back without reaching `markNotified` at all, so GENERATED stays GENERATED (on
`yotresultsdistribution_oldest_generated_age`) and PARTIALLY_NOTIFIED stays PARTIALLY_NOTIFIED, and both are
states either entry point can pick up again. The resend that follows mints the missing row, posts
under it and settles the batch on a tally that accounts for every recipient. Nor is one drawn for
`CLAIM_LOST`: the notifier that took the batch over is the one whose tally settles it.

**The last arrow out of PENDING, and why it is drawn.** `RegisterGenerationService.storeAndRequest`
mints the payload id, writes it down (`markPayloadMinted`), stores the payload, POSTs, and only then
`markRequested`. A pod that dies between the 202 and that mark - or a store blip on the mark itself -
leaves a batch PENDING with `payload_file_id` set and its rows stamped, and until this arrow existed
nothing revisited it: the reconciler read `generatingSince()` only, the stamped rows are outside
`activeUnbatched()`, `idx_register_batch_live_key` defers every later re-share of that key for ever,
and `oldest_generating_age` reads GENERATING. So the reconciler also sweeps PENDING batches whose
`payload_file_id` is set and whose `assembled_at` is older than the grace period, asks
systemdocgenerator about that payload, and applies the answer through the same `DocumentOutcomeSink`:
a document makes the batch **GENERATED** (`completed_by = RECONCILER`) and a refusal FAILED
`GENERATION_FAILED`. The two endings that are not an answer are told apart on whether
systemdocgenerator knows the payload at all, and not on which read found the batch. An answer that
names neither a document nor a refusal is a payload systemdocgenerator has and is still rendering, so
the request did reach the renderer and the renderer is what has not come back: FAILED
**`GENERATION_TIMED_OUT`** with `completed_by = RECONCILER`, exactly as an overdue GENERATING batch
with the same empty answer is failed. No record of the payload at all is the only shape that shows
the request never arrived: FAILED **`RENDER_REQUEST_FAILED`** with `completed_by` NULL - this
service's own verdict about a request it cannot show was ever accepted. Their age is published on
`yotresultsdistribution_oldest_pending_age`, the fifth gauge; the batch parked at GENERATED is on
`yotresultsdistribution_oldest_generated_age`, the seventh.

`PENDING → GENERATED` is therefore a drawn arrow rather than a batch skipping GENERATING: the render
really was accepted and the mark that says so is what was lost, and refusing the move would throw
away a document that exists (`BatchStateTest`, `GenerationReconcilerTest.StillPending`,
`RegisterBatchRepositoryIT.NeverRequested`).

Rows follow their batch: `RECORDED → GENERATED` on `document-available` (**only this batch's rows**),
`GENERATED → NOTIFIED` when the batch reaches NOTIFIED / PARTIALLY_NOTIFIED / NOTIFIED_NOBODY; a
FAILED batch leaves its rows RECORDED with `batch_id` cleared on re-assembly.

**Per run**: `STARTED → FLAG_READ{ON | OFF | UNREADABLE} → (SKIPPED | ASSEMBLING → REQUESTING →
DONE)`, recorded as the run report (log + gauges), not as a table.

## Entities the pipeline adds

- `RegisterRecord` (a `processed_output` row view: id, hearing, key, document, recipients,
  `defendantType`, flag state).
- `CourtCentreDay(court_centre_id, register_date)` — the batch key.
- `RenderRequest(payloadFileId, batchId, templateIdentifier, conversionFormat, originatingSource)`.
- `DocumentStatus` (from the query API: `documentFileServiceId?`, `generatedTime?`, `failedTime?`,
  `reason?`).
- `RegisterDocumentValidator` - the port the core asks before the write, served by a second
  `OutboundContractValidator` instance over `courtRegisterDocumentRequest.json`. The document
  the transformation validated is not the document that is stored: `defendantType` is attached
  after the command check, and since 002 the stored document is what the batch reads back and
  what the PDF payload is built from, so the final document is held to the schema that
  describes it.
- `FlagDecision` — `ON | OFF | UNREADABLE(reason)`.
- `RunReport` — counts per outcome, flag decision, duration, reconciled count.

## File-service tables (not owned; vendored DDL for tests)

From `contracts/fileservice/` (changesets 001–006): `metadata(file_id uuid PK, metadata jsonb)`;
`content(file_id uuid PK, content bytea, deleted boolean default false,
deleted_at timestamp with time zone)`. The timestamp column is named `deleted_at`, not
`date_deleted`: only the changeset file (`006-add-date-deleted-column-to-content-table.xml`) carries
the older name, and the column it adds is `deleted_at`.
This service issues exactly, and in this order:

```sql
INSERT INTO content(file_id, content, deleted) VALUES (?, ?, false);
INSERT INTO metadata(metadata, file_id) VALUES (to_json(?::json), ?);
```

**Content first: `metadata.file_id` references `content(file_id)`.** Changeset 001 makes the metadata
row's key a foreign key onto the content row's, so the order is the schema's requirement and not a
preference - the metadata insert refuses until the content row it names exists.
`FileServicePayloadStoreIT` pins both the statements and the order they are prepared in. They are two
statements under autocommit rather than one transaction, because the store is handed a `JdbcClient`
over somebody else's pool and nothing else: a metadata write that fails behind a content write that
succeeded leaves one orphan `content` row carrying an id no batch is waiting on, the batch fails
PAYLOAD_STORE_UNAVAILABLE, and the next run mints a fresh id.

with metadata `{"fileName": …, "conversionFormat": "pdf", "templateName": "OEE_Layout5",
"numberOfPages": 1, "fileSize": <bytes>}`. No reads, no updates, no deletes (role permits INSERT only).
