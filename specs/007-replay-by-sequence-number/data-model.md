# Phase 1 Data Model: Replay a parked request by broker sequence number

This is a delta against the schema and domain types described in
`specs/001-court-register-port/data-model.md` and implemented in
`src/main/resources/db/migration/V1__create_processed_log.sql` and the `domain`/`persistence`
packages. Nothing here replaces that document; FR-014 requires this change to be additive, and the
spec's "Documents the implementation must update" names it as one the implementation itself updates.

## Schema change — `processed_request` (new migration `V9`)

The next free Flyway version in `src/main/resources/db/migration/` is `V9` (V1–V8 exist).

```sql
ALTER TABLE processed_request
    ADD COLUMN parking_sequence_number bigint,
    ADD COLUMN parking_category        text,
    ADD COLUMN parking_attempt_count   integer;

ALTER TABLE processed_request
    ADD CONSTRAINT processed_request_parking_category_chk
        CHECK (parking_category IS NULL OR parking_category IN ('EXHAUSTED', 'NON_TRANSIENT')),
    ADD CONSTRAINT processed_request_parking_triple_chk
        CHECK ((parking_sequence_number IS NULL) = (parking_category IS NULL)
           AND (parking_sequence_number IS NULL) = (parking_attempt_count IS NULL));
```

- **`parking_sequence_number`** (`bigint`, nullable): the broker sequence number of the delivery
  that parked this request's current `FAILED` generation. `bigint` because
  `ServiceBusReceivedMessage#getSequenceNumber()` returns `long`. Nullable: absent on a row parked
  before this change, or by an older consumer mid-rollout (FR-005, FR-014).
- **`parking_category`** (`text`, nullable): the `DeadLetterReason` enum name (`EXHAUSTED` or
  `NON_TRANSIENT`) the parking write used (research.md R1), stored as recorded and never
  reconstructed once present and current (FR-010). Nullable for the same reason as above.
- **`parking_attempt_count`** (`integer`, nullable): the `attempts` value at the moment the row was
  parked — this repository's own addition beyond the informant-register precedent (research.md R6).
  Compared against the row's **current** `attempts` to tell an exact parking apart from one an older
  consumer has since re-parked without knowing these columns (FR-005a); never itself compared to a
  delivery's anything.
- **`processed_request_parking_triple_chk`**: the three columns are set together and cleared
  together (FR-001, FR-014) — the schema-level half of that rule; the write-together half is
  statement-level (below).
- **No change** to `exhausted_message_id` or `processed_request_exhausted_id_chk`: kept for audit
  (FR-001), and the column is still written on every parking write.
- **Existing rows stay valid.** A `FAILED` row from before this migration has all three new columns
  `NULL`, which satisfies both new constraints and is the documented legacy case (FR-005, FR-010,
  FR-014).

## Statement changes — `ProcessedRequestRepository`

| Statement | Change |
|---|---|
| `READ_RECORD` (statement 2) | Select additionally `parking_sequence_number`, `parking_category`, `parking_attempt_count`, `updated_at`. `updated_at` is new to the read: for a `FAILED` row it **is** the parking time FR-005's margin test needs (nothing updates it while the row stays `FAILED` — see research.md R2/R6). |
| `RECORD_FAILED` (statement 4, used by `recordNonTransientFailure` and `recordExhaustion`) | Additionally sets `parking_sequence_number = :sequenceNumber, parking_category = :category, parking_attempt_count = attempts` in the same statement that sets `status = 'FAILED'` and `exhausted_message_id` — `attempts` on the right-hand side, not a bound parameter, because the row's own current value at the moment of the write *is* the generation being parked (FR-001). Written only by the statement already predicated on `claim_owner = :owner AND claim_token = :token` (FR-002 — unchanged predicate, new columns). |
| `REPLAY_FAILED` (statement 5) | `WHERE` clause changes from `status = 'FAILED' AND exhausted_message_id <> :messageId` to `status = 'FAILED' AND attempts = :observedAttempts AND parking_sequence_number IS NOT DISTINCT FROM :observedParkingSequenceNumber` (FR-006; research.md R2). `SET` clause additionally clears `parking_sequence_number = NULL, parking_category = NULL, parking_attempt_count = NULL` alongside the existing `exhausted_message_id = NULL` (FR-007, FR-014). |

No other statement changes: `INSERT_NEW`, `RECLAIM_STALE_CLAIM`, `RECORD_COMPLETED`,
`RECORD_RETRYING` and the four report/sweep reads (`FAILED_BETWEEN`, `NON_TERMINAL_OLDER_THAN`,
`COUNT_NON_TERMINAL_OLDER_THAN`, `OLDEST_NON_TERMINAL`) are untouched.

## Domain type changes

### `ProcessedRequestRecord` (gains three fields, plus `updatedAt`)

```java
public record ProcessedRequestRecord(
        RequestStatus status,
        String fingerprint,
        String failureReason,
        String exhaustedMessageId,
        int attempts,
        String claimOwner,
        Instant claimExpiresAt,
        Long parkingSequenceNumber,   // new — null on a legacy or stale row
        String parkingCategory,      // new — the stored DeadLetterReason name, or null
        Integer parkingAttemptCount, // new — the attempts value recorded with the above, or null
        Instant updatedAt) {         // new — the parking time, for a FAILED row
}
```

### `DeliveryIdentity` (gains the broker facts FR-003–FR-005 and FR-011 decide on)

```java
public record DeliveryIdentity(
        String messageId,
        String claimOwner,
        boolean finalPermittedDelivery,
        long sequenceNumber,   // new — the broker's identity for this delivery
        long deliveryCount,    // new — for the legacy heuristic, FR-005
        Instant enqueueTime) { // new — nullable; absent is recorded, not defaulted
}
```

`messageId` is retained — FR-004 still replays regardless of `messageId`, and FR-008's audit trail
still wants it recorded. **The existing two- and three-argument convenience constructors are kept**,
not replaced: `new DeliveryIdentity(messageId, claimOwner[, finalPermittedDelivery])` defaults
`sequenceNumber` to the sentinel `-1L`, `deliveryCount` to `0` and `enqueueTime` to `null`. This is
safe because every call site using them today — `IdempotencyCollisionIT`, `ProcessedLogDurabilityIT`,
`CrashWindowIT`, `OutputClaimFencingIT`, `StaleRunnerRejectionIT`, `PipelineCompositionTest` — drives
only the insert/collision/claim/crash-window paths and never reaches `replayOrPark`'s sequence-number
comparison; touching seven files that have nothing to do with this feature to satisfy a record's
canonical constructor would be exactly the needless blast radius Principle V's minimalism exists to
avoid. A **new** factory/constructor taking the broker facts explicitly is added for the persistence- and
listener-level tests that construct a `DeliveryIdentity` by hand and do exercise replay —
`FailedReplayIT`'s delivery-building helper and its siblings in `IdempotencyGuardIT` and
`MessageListenerSettlementTest` — each synthetic delivery distinct on both axes there. The
emulator-backed suites (`DeliveryExhaustionIT`, `QueueSettlementIT`, `DuplicateDetectionIT`,
`RequestDedupeIT`, `MessageAccountingIT`) need no such change: their sequence numbers come from the
real broker automatically; they only gain new *assertions* reading `message.getSequenceNumber()`
off deliveries they already drive.

### `RunClaim` (gains the parking delivery's own sequence number)

```java
public record RunClaim(
        String source,
        UUID requestId,
        String owner,
        UUID token,
        String messageId,
        long sequenceNumber) { // new — the claim's own delivery, for RECORD_FAILED
}
```

Same reasoning as the existing `messageId` field's javadoc: "the delivery that acquired it is by
definition the one whose failure parks the request," so the sequence number travels with the claim
rather than being passed separately into `recordNonTransientFailure`/`recordExhaustion`.

`RunClaim` has no auxiliary constructor today — every call site supplies all five existing
components directly — and thirteen test files do (`PipelineCompositionTest`, `TelemetryPrivacyTest`,
`GeneratedRegisters`, `ProgressionRegisterSubmissionClientTest`, `RegisterStoreReportReadsIT`,
`MessageListenerSettlementTest`, `ProcessedOutputRepositoryIT`, `RegisterStoreIT`,
`StaleReleaseConcurrencyIT`, `DistributionPipelineTest`, `SubmissionRedeliveryIT`,
`OutcomeOvertakesRequestIT`, `OperationsConcurrencyIT`), none of which exercise replay. The same
minimalism applies as to `DeliveryIdentity`: a new 5-arg constructor is added, defaulting
`sequenceNumber` to the sentinel `-1L`, so none of the thirteen needs to change; only the one
production call site, `IdempotencyGuard.admit`, is updated to supply the real value via the 6-arg
canonical constructor, from the `DeliveryIdentity` it was handed.

### New: `BrokerDeliverySnapshot` (listener-local; never crosses into `application`/`domain`)

```java
record BrokerDeliverySnapshot(
        long sequenceNumber,
        long deliveryCount,
        String messageId,
        String lockToken,
        Instant enqueueTime,           // null when the broker annotation is absent
        boolean finalPermittedDelivery) {
}
```

Built once, inside `process`'s existing call frame (research.md R3), itself already inside
`decide`'s total `try`/`catch` boundary. `DeliveryIdentity` is constructed from it, never from the
`ServiceBusReceivedMessage` directly, once built. An inbound-adapter implementation detail, not a
port or a core type: Principle V's boundary is `application`/`domain`, and those already receive
exactly the facts they need via the enlarged `DeliveryIdentity`.

### New: pure lookups (`domain` package)

```java
// ReasonCode — one new method, alongside the existing code().
public static Optional<ReasonCode> fromCode(String storedCode)

// New type — settled at implementation time whether a small final class or a package-private one.
public final class FailureCategoryMapping {
    public static DeadLetterReason categoryFor(String storedReasonCode) { ... }
}
```

`fromCode` is total over the full `ReasonCode` enum; any string not matching a declared code
returns empty, which FR-009 maps to `UNEXPECTED_FAILURE` at the call site, logging the anomaly
rather than the unrecognised text. `categoryFor` implements FR-010's closed table (the seven
non-transient-path reason codes — `PAYLOAD_READ_REFUSED`, `REFERENCE_DATA_REFUSED`,
`TRANSFORMATION_FAILED`, `OUTBOUND_CONTRACT_VIOLATION`, `REGISTER_NOT_RECORDED`,
`SUBMISSION_REJECTED`, `SUBMISSION_NOT_ACCEPTED` → `NON_TRANSIENT`; everything else → `EXHAUSTED`)
and never throws.

## Decision table — `IdempotencyGuard.replayOrPark` (replaces the current one-branch version)

| Recorded parking fields | Delivery's sequence number | Decision |
|---|---|---|
| present, **and `parking_attempt_count` = current `attempts`** (exact) | **equal** to the parking sequence number | Re-park. No run, no state change. `ReasonCode.fromCode(failure_reason)` decides detail: if it resolves, detail = that reason, category = stored `parking_category`. If it does **not** resolve, FR-009 forces `exhausted / UNEXPECTED_FAILURE` outright — the recorded category is **not** used, because it answers for a specific original cause this service can no longer name. |
| present, exact (as above) | **different** | Replay: compare-and-set against `(status='FAILED', attempts, parking_sequence_number)` as observed on the read (FR-004, FR-006). Success → `FAILED → RECEIVED` (FR-007). Failure (0 rows) → `Abandon(REPLAY_NOT_ADMITTED)`, unchanged from today. |
| **absent**, OR present but **stale** (`parking_attempt_count` ≠ current `attempts` — FR-005a) | — | Apply the FR-005 heuristic: delivery count **0** *and* enqueue time **>** `updated_at + legacy-margin` ⇒ replay via the **same** compare-and-set (its null-safe half matches the observed value, whatever it is, FR-006). Otherwise (either condition fails, or enqueue time is absent) ⇒ re-park: if `ReasonCode.fromCode(failure_reason)` resolves, detail = that reason, category = `FailureCategoryMapping.categoryFor(failure_reason)` (FR-010, the mapping, not the stale stored value); otherwise FR-009 forces `exhausted / UNEXPECTED_FAILURE` outright, as above. |

The existing `exhausted_message_id` equality check is **removed** as the deciding test — FR-004 is
explicit that a different sequence number replays "regardless of its `messageId`" — but the column
is still written (audit) and still read into `ProcessedRequestRecord` for the replay note's
wording, unchanged from today.

## State-transition table — delta only

| From | To | Trigger | Write |
|---|---|---|---|
| FAILED | RECEIVED | delivery whose sequence number differs from the recorded parking sequence number (exact row), or, for a legacy/stale row, the FR-005 heuristic admits it | `attempts` preserved and incremented; `failure_reason`, `exhausted_message_id`, `parking_sequence_number`, `parking_category`, `parking_attempt_count` all cleared; `audit_note` written; claim taken. Predicated additionally on the observed `attempts` and `parking_sequence_number` matching what was read (compare-and-set, FR-006). |
| RECEIVED/RETRYING | FAILED | non-transient failure at any delivery, or transient failure on the final permitted delivery | same statement as today; additionally writes `parking_sequence_number`, `parking_category` and `parking_attempt_count` from the claim/classification/current `attempts` (FR-001). |

## Invariants added

1. `parking_sequence_number`, `parking_category` and `parking_attempt_count` are set together and
   cleared together, never independently (schema constraint + statement-level predicate,
   FR-001/FR-014).
2. An **exact** `FAILED` row (`parking_attempt_count` = current `attempts`) is decided by
   sequence-number equality alone; `messageId` is never consulted for that decision again (FR-004).
3. A row with no recorded parking fields, or a **stale** one, is decided by the two-condition legacy
   heuristic and by nothing else; the same row reached again after a hand-back is judged afresh, not
   remembered (spec, User Story 3, Acceptance Scenario 3).
4. The replay compare-and-set's predicate is exactly what was observed on the read that produced the
   decision to replay — never the current, possibly-moved-on, state (FR-006).
5. A re-park's dead-letter reason is the request's own recorded (or mapped) category and reason,
   never `DELIVERY_LIMIT_EXHAUSTED` as a substitute (FR-009).
6. A row whose `attempts` has moved past its recorded `parking_attempt_count` is a row an older
   consumer re-parked without knowing these columns; it is treated as stale rather than exact, and
   its stale `parking_category` is never used (FR-005a, research.md R6).
