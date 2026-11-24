# Phase 0 Research: Replay a parked request by broker sequence number

No item in Technical Context is `NEEDS CLARIFICATION`: language, framework, storage, test stack,
target platform and estate conventions are fixed by the constitution and already in force in this
codebase. What Phase 0 resolves is the design decisions the spec leaves implicit — each one a point
where more than one implementation would satisfy the functional requirements, and the wrong pick
would either violate Principle V (ports and adapters) or leave an FR-015 gate unprovable. R1-R5 and
R7 mirror the decisions already taken and implemented in `service-cp-crime-informant-register`'s
`specs/001-replay-by-sequence-number/research.md`, re-derived against this repository's own code
rather than copied; R6 has no informant precedent — it is this repository's own addition (spec
Edge Cases, "Rolling deployment").

## R1 — How the parking category is represented in storage and in code

**Decision**: Store the parking category as the stored name of the existing `DeadLetterReason` enum
(`EXHAUSTED` / `NON_TRANSIENT`), in a new `parking_category text` column, read back through
`DeadLetterReason.valueOf(...)`. No new enum.

**Rationale**: `DeadLetterReason` (`domain/DeadLetterReason.java`) is already the closed set the
spec's "category" is — its `label()` already supplies `"exhausted"` / `"non-transient"` for the
dead-letter call, and `recordNonTransientFailure`/`recordExhaustion` in `IdempotencyGuard` already
know unambiguously which of the two applies at the point they call
`ProcessedRequestRepository.recordFailed` (`DistributionPipeline:987-989` dispatches to one or the
other by `FailureClassification`, never both). Storing the enum name, not the label, keeps the
column `Enum.valueOf`-round-trippable without a translation table, consistent with how
`RequestStatus` is stored (`status = 'FAILED'`, read back via `valueOf`).

**Alternatives considered**: A boolean `parked_as_non_transient` column — rejected; `DeadLetterReason`
is the constitution's own closed dead-letter-category set and a boolean loses the name the moment a
third value is needed. Deriving the category from `failure_reason` every time — rejected; FR-010
requires a *recorded* category to be used as recorded, never reconstructed, and the mapping is for
the no-category (legacy) case only.

## R2 — The null-safe compare-and-set predicate, and the generation key (FR-006)

**Decision**: PostgreSQL `IS NOT DISTINCT FROM` for the parking-sequence-number half of the replay's
`WHERE` clause — `parking_sequence_number IS NOT DISTINCT FROM :observedParkingSequenceNumber` —
alongside `attempts = :observedAttempts` and `status = 'FAILED'`. `attempts` is the generation key,
exactly as it already is in the current `REPLAY_FAILED` statement's intent (it is carried forward
and incremented on replay today); this feature only adds it to the predicate.

**Rationale**: `IS NOT DISTINCT FROM` is PostgreSQL's null-aware equality (`NULL IS NOT DISTINCT
FROM NULL` is `true`, where plain `=` is `NULL` and matches nothing). FR-006 requires the
compare-and-set to admit a legacy row ("so 'still none' matches for a legacy record"); plain `=`
would never match a legacy row's `NULL` to the decision's observed `NULL`, so the legacy replay path
(User Story 3) could never be admitted. `attempts` already changes on every transition out of and
back into `FAILED` (insert, reclaim and replay each touch it; nothing else does while the row is
`FAILED` — see the constitution's "Generation" assumption, and R6 below), so comparing it pins the
update to the exact failure generation the delivery observed — which is what rules out "A stale
replay decision" in the spec's Edge Cases.

**Alternatives considered**: A dedicated `version`/`generation` column — rejected as redundant; this
repository's file-level javadoc on `ProcessedRequestRepository` already states the house rule ("the
affected-row count is the decision"), and `attempts` already has the needed monotonic property. A
second locking column would be the first statement in the file not to follow that rule.

## R3 — Reading broker metadata exactly once (FR-011/FR-012)

**Decision**: Introduce one new listener-local record — `BrokerDeliverySnapshot` (name settled at
implementation time) — built by a single method at the top of
`YotResultsDistributionMessageListener#process` (the method that today reads
`message.getDeliveryCount()` directly and calls `isFinalPermittedDelivery(message)`), capturing
`sequenceNumber`, `deliveryCount`, `messageId`, `lockToken` and (optional) `enqueueTime` in one pass.
Every later use in the class — the receipt log line (`process`, line ~344-346), `identityOf`
(renamed to build `DeliveryIdentity` from the snapshot, currently line ~605-609) and
`isFinalPermittedDelivery` (currently line ~630-631) — reads the snapshot's fields and never calls a
`ServiceBusReceivedMessage` accessor a second time.

**Rationale**: the existing listener already violates the will-be-FR-011 rule, demonstrably:
`message.getDeliveryCount()` is called at line 345 (the receipt log), again inside
`isFinalPermittedDelivery` at line 346, and a third time inside `identityOf`'s own call to
`isFinalPermittedDelivery` at line 609 — three calls for one delivery, for the one accessor FR-011
names explicitly. `message.getSequenceNumber()` and `message.getEnqueuedTime()` (or its broker
equivalent) are not read at all today. Capturing once, at the top of `process` (which already runs
inside `decide`'s total `try`/`catch RuntimeException` boundary — see `decide`/`examine`, lines
293-333), means a throw from any accessor is caught by the same branch that already classifies
"anything else at all" as `unexpectedFailure`, which FR-012 requires to still settle exactly once.
Because the snapshot would be unavailable in that failure, `finalDelivery` cannot be read either —
so FR-012's conservative rule ("where the final-delivery position cannot be determined... hand the
delivery back") means this failure mode must always `abandon()`, never `deadLetter()`, even on what
might have been the last delivery.

**Alternatives considered**: Lazy/memoised accessors on `ServiceBusReceivedMessage` itself —
rejected; the SDK type is not safe to wrap, and several `Supplier` fields are harder to audit for
"called at most once" than one record built in one place. A second `DeliveryIdentity`-shaped type
for "everything read from the broker" — rejected as duplicating `DeliveryIdentity`; instead
`DeliveryIdentity` itself gains the new fields (data-model.md) and the snapshot is a listener-local
capture step only, never passed past the inbound-adapter boundary (Principle V: `application`/
`domain` see only `DeliveryIdentity`, never a `ServiceBusReceivedMessage`).

## R4 — Where the re-park reason/category lookup and the legacy mapping live

**Decision**: Two small, pure, total (no exception) domain-level lookups:

- `ReasonCode.fromCode(String)` → `Optional<ReasonCode>`, added to `ReasonCode` itself, following
  this repository's own house convention ("Include a `fromValue(String)`/`fromCode(String)` factory
  method... unknown values are an explicit non-transient failure, never a silent default",
  `technical-rules.md`). Used by the re-park path to turn stored `failure_reason` text back into a
  `ReasonCode` for the dead-letter detail (FR-009); an empty `Optional` is the "missing or
  unrecognised" case, which dead-letters as `exhausted / UNEXPECTED_FAILURE` and logs the anomaly —
  never the raw stored text.
- `FailureCategoryMapping.categoryFor(String storedReasonCode)` → `DeadLetterReason`, encoding
  FR-010's explicit table. The seven reason codes the non-transient path can record today —
  `PAYLOAD_READ_REFUSED`, `REFERENCE_DATA_REFUSED`, `TRANSFORMATION_FAILED`,
  `OUTBOUND_CONTRACT_VIOLATION`, `REGISTER_NOT_RECORDED`, `SUBMISSION_REJECTED`,
  `SUBMISSION_NOT_ACCEPTED` (`domain/ReasonCode.java`, cross-checked against every
  `recordNonTransientFailure` call site reachable from `DistributionPipeline`) — map to
  `NON_TRANSIENT`; every other value, including an unrecognised one, maps to `EXHAUSTED`. Used only
  when `parking_category` is absent or stale (FR-005a).

**Rationale**: both are pure functions over bounded, already-closed vocabularies, so they belong in
`domain/`, consistent with Principle V and with keeping `IdempotencyGuard` (which already carries
substantial branching) from growing a third responsibility. Two functions, not one, because they
answer different questions at different times: `fromCode` runs on every re-park, recognised or not;
`categoryFor` runs only on the legacy/stale-row path.

**Alternatives considered**: A single combined decision value object computed once and threaded
through `IdempotencyGuard` — plausible, left to `tasks.md`/implementation as an internal detail; it
does not change the two lookups or their test surface.

## R5 — The legacy replay margin and the new lock-duration setting (FR-005)

**Decision**: a new `legacy-margin` setting (`yotresultsdistribution.replay.legacy-margin`, default
`5m`) in a new `Replay` nested record on `YotResultsDistributionProperties`, and a new
`lock-duration` setting (`yotresultsdistribution.servicebus.lock-duration`, default `1m`) added
*beside* `max-delivery-count` in the existing `Servicebus` record — because, exactly like
`max-delivery-count`, it mirrors a broker queue setting and must be changed together with it
(`docker/servicebus-emulator/config.json`'s `LockDuration: "PT1M"`). `PropertiesValidator` gains a
check refusing start-up if the margin is shorter than the lock duration.

**Rationale**: every other timing constant this service depends on
(`servicebus.max-auto-lock-renew-duration`, `claim.lease`, `claim.processing-deadline`) is bound
through `YotResultsDistributionProperties` and cross-checked at start-up by `PropertiesValidator`
rather than defended against at the point of use — `validateLockOutlivesTheRun` and
`validateRunFinishesBeforeTheClaimExpires` are the existing precedent for exactly this shape of
check. A margin shorter than the lock duration is the same class of defect: the spec's own
Assumptions state the shipped legacy margin (5m) "is therefore well above the lock duration" (1m),
so a configuration that violates its own documented precondition must fail loudly at start-up, not
silently produce the "false replay" risk the spec accepts only at a *correct* margin.
`max-auto-lock-renew-duration` (5m) is **not** the lock duration — it is how long the *renewal*
window may extend a succession of 1-minute locks — so it cannot stand in for the new setting; this
service has never had to know the underlying lock's own duration before this feature.

**Alternatives considered**: A constant, not a property — rejected; `max-delivery-count` is already
configurable to mirror the broker's own queue setting, so a margin derived from a broker timing
that is itself configurable cannot safely be a compile-time constant. Reusing
`max-auto-lock-renew-duration` as the floor — rejected per the previous paragraph; it answers a
different question and comparing against it would accept margins shorter than the real lock
duration.

## R6 — Detecting a rolling-deployment re-park (FR-001, FR-005a) — this repository's own addition

**Decision**: write `parking_attempt_count` (the `attempts` value at the moment of parking) as a
**third** column alongside `parking_sequence_number`/`parking_category`, set together and cleared
together with them. A `FAILED` row whose current `attempts` differs from its recorded
`parking_attempt_count` is decided exactly as a legacy row (FR-005, FR-010) rather than by exact
sequence-number equality, even though `parking_sequence_number`/`parking_category` are non-null on
it.

**Rationale**: the informant-register service's own spec review records that it found no in-band
signal for an older consumer re-parking a row a newer consumer already parked during a rolling
deployment, and accepted the gap. This repository has one: `attempts` already increments on every
path back into `FAILED` (`insertNew`, `reclaimStaleClaim` and a successful replay each touch it —
see R2's "Generation" reasoning), so a consumer that parks a row without knowing the new columns
still increments `attempts` doing it (`RECORD_FAILED`'s `WHERE claim_owner = :owner AND claim_token
= :token` predicate is unchanged by this feature, and the claim it is predicated on was itself
acquired by a statement that increments `attempts`). An older consumer's re-park therefore leaves
`parking_sequence_number`/`parking_category` stale (describing a parking that is no longer the
row's latest) **underneath** an `attempts` value that has moved on past what they describe — a fact
a newer consumer can read without adding any new write. Adopting it removes a residual
false-*replay* case the informant precedent otherwise carries into this repository's rollout.

**Alternatives considered**: Not adopting the signal (parity with informant) — rejected once the
signal was found to already exist for free in a column this feature writes anyway; leaving it unused
would be a known, avoidable gap. A fourth column recording "the consumer version that parked this
row" — rejected as heavier than the comparison this repository's own `attempts` counter already
gives for nothing.

## R7 — Proving the broker-emulator-backed FR-015 gates

**Decision**: extend the existing `QueueSettlementIT` and `DeliveryExhaustionIT` Testcontainers
Service-Bus-emulator suites with the new assertions (sequence number unchanged across an abandon; a
portal-style dead-letter re-send gets a new one; an unchanged re-send made after the
duplicate-detection window recovers a parked request end to end) rather than a new IT class.

**Rationale**: both suites already stand up the emulator and already drive abandon/redeliver/
exhaustion scenarios end to end; the new assertions are additional observations on deliveries the
suites already produce. A third IT class would pay the emulator's start-up cost again for
assertions that fit naturally alongside what is already being observed there — the same conclusion
the informant precedent reached for the identical choice.

**Alternatives considered**: A dedicated `SequenceNumberIT` — rejected for the same reason; kept as
a fallback if the existing suites prove awkward to extend once implementation starts.
