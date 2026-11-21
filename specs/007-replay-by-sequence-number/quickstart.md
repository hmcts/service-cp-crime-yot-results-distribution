# Quickstart — Replay a parked request by broker sequence number

This feature has no new runtime switch and no new endpoint — there is nothing to turn on. What
changes is how an already-`FAILED` request responds to a re-send. This quickstart is about *proving*
that, locally and in CI, since the whole point of the defect being fixed is that it was invisible
without a real broker.

## Prerequisites

Java 25, Gradle, Docker running — the Service Bus emulator and Postgres are containers
(`docker/servicebus-emulator/config.json`, mounted by both `docker-compose.yml` and the `*IT` test
support).

## Running the feature's own test suites

```bash
# Persistence-level: the guard's decision table, against the real Postgres-backed repository fixture.
./gradlew test --tests 'uk.gov.hmcts.cp.yotresultsdistribution.persistence.FailedReplayIT'
./gradlew test --tests 'uk.gov.hmcts.cp.yotresultsdistribution.persistence.IdempotencyGuardIT'
./gradlew test --tests 'uk.gov.hmcts.cp.yotresultsdistribution.persistence.SchemaMigrationIT'

# Listener-level: settlement discipline, broker-metadata-read-once, the re-park reason/category.
./gradlew test --tests 'uk.gov.hmcts.cp.yotresultsdistribution.inbound.MessageListenerSettlementTest'
./gradlew test --tests 'uk.gov.hmcts.cp.yotresultsdistribution.inbound.SettlementFailureEdgeTest'

# Against a real emulator: sequence-number behaviour across abandon/redeliver/exhaustion, and (this
# feature) that a re-send gets a new sequence number while a redelivery keeps the old one, and that
# an unchanged re-send after the duplicate-detection window recovers a parked request end to end.
./gradlew test --tests 'uk.gov.hmcts.cp.yotresultsdistribution.e2e.DeliveryExhaustionIT'
./gradlew test --tests 'uk.gov.hmcts.cp.yotresultsdistribution.e2e.QueueSettlementIT'
./gradlew test --tests 'uk.gov.hmcts.cp.yotresultsdistribution.e2e.DuplicateDetectionIT'
./gradlew test --tests 'uk.gov.hmcts.cp.yotresultsdistribution.e2e.RequestDedupeIT'
```

All need Docker; the first three do not need the Service Bus emulator specifically (they drive the
guard and the repository directly against Testcontainers Postgres), the rest do.

## Manually proving the fix end to end (what support will actually do)

The defect this feature fixes is specifically about the **Azure portal's dead-letter "Re-send"**,
which this quickstart cannot drive directly — there is no portal against the local emulator. The
emulator supports receiving from, and sending back onto, the dead-letter sub-queue
(`com.azure.messaging.servicebus.models.SubQueue.DEAD_LETTER`), which is the same operation the
portal's button performs — copy the body, including `messageId`, from the DLQ back onto the live
queue.

1. Bring the stack up and run the service: `docker compose up -d` (the servicebus emulator and
   Postgres), then `./gradlew bootRun`.
2. Send a distribution request onto `yotresultsdistribution.requests`, then force it to fail
   non-transiently (for example, make the register store refuse the recording) or deliver it five
   times into a transient failure, until `processed_request.status = 'FAILED'`. Note the row's
   `parking_sequence_number`, `parking_category` and `parking_attempt_count`.
3. **Before re-sending, check whether a later share of the same hearing has already been batched** —
   query `processed_output.hearing_id` for the active (`superseded_at IS NULL`) row recorded for
   this hearing, and whether its `batch_id` is non-null (join to `register_batch` on `batch_id` for
   that batch's own status). This feature does not change the register store's write-time
   supersession, which compares a replay only against the hearing's **unbatched** active register
   (Edge Cases, "Replay of an older share after a newer share was recorded"): once the newer share
   has been batched — the active row's `batch_id` is set — re-sending this older, parked one would
   record it as active and send it in a later batch — the Youth Offending Team receiving superseded
   content *after* the current one. If the active row is already batched, do not re-send; this is
   exactly what the check exists to catch.
4. **Wait past the broker's duplicate-detection window** (`PT5M` in
   `docker/servicebus-emulator/config.json`) from the original send noted in step 2, or mint a fresh
   `messageId` for the body before re-sending. Skipping this is the one way the next step silently
   does nothing at all — see step 8.
5. Using a short AMQP client, receive the message from the queue's dead-letter sub-queue and
   re-send its body — **byte for byte, same `messageId`** — onto `yotresultsdistribution.requests`.
6. **Before this feature**: the service dead-letters it again, under `exhausted` /
   `DELIVERY_LIMIT_EXHAUSTED`, and the row stays `FAILED` unchanged. This is the defect.
7. **After this feature**: because the broker accepted and enqueued the re-send, it carries a
   **new sequence number**, different from the one recorded on the row. The service treats it as a
   resubmission: `processed_request.status` moves to `RECEIVED`, then (assuming the underlying
   cause is fixed) to `COMPLETED`, and the dead-letter entry from step 2 is the last one for this
   request.
8. Repeat step 5 **without waiting**, immediately after step 2 (skipping step 4), and the broker's
   own duplicate-detection window silently discards the second re-send — nothing arrives at the
   service at all. This is unchanged by this feature and is the one case where a fresh `messageId`
   still helps (see `contracts/replay-rule-delta.md`) — which is exactly what step 4 offers as the
   alternative to waiting.

## Proving the legacy/stale-row heuristic (User Story 3)

A row parked by the code **before** this feature, or re-parked by an older consumer mid-rollout,
has `parking_sequence_number IS NULL` (or an `attempts` value past its `parking_attempt_count`).
The cleanest local reproduction is to insert such a row directly, then re-send:

- A re-send whose delivery count is 0 and whose enqueue time is safely past
  `updated_at + yotresultsdistribution.replay.legacy-margin` (default 5 minutes) replays.
- A re-send that arrives sooner, or whose delivery count is already ≥ 1 for any reason, re-parks —
  the accepted residual false-*re-park* case the spec documents, never a false replay.

## Full verification before commit

```bash
./gradlew build                            # compile + entire suite (unit + *IT) + Checkstyle + PMD
./gradlew jacocoTestReport check            # coverage report, then the gate that reads it
./gradlew checkstyleMain checkstyleTest
./gradlew pmdMain pmdTest
```

Additionally for this feature, because it touches rule and agent documents cross-referenced from
several places (see the spec's "Documents the implementation must update"): confirm
`.claude/rules/design_rules.md`, `.claude/rules/technical-rules.md`, the four `.claude/agents/*.md`
files, `src/main/resources/application.yaml`, `YotResultsDistributionProperties`,
`PropertiesValidator`, `specs/001-court-register-port/{spec.md,data-model.md}` and
`doc/DEFECT-FIXES.md` row C17 were all reviewed in the same change as the code, not after.
