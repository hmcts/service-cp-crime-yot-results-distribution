# Contracts - increment 003

**Nothing is vendored here, and nothing new is consumed.** This increment adds no contract of its
own and changes none of the four platform contracts this service already adapts to (constitution
Principle III). The report has no inbound message, no REST surface and no event of its own on any
topic; the two structured log events it writes are telemetry, not a contract, and are described in
`../data-model.md` rather than as a schema.

Two contracts **002 already vendored** are used, unchanged, from where 002 put them:

| What | Where it lives | Used here by |
|---|---|---|
| notificationnotify `send-email-notification` - the body of `POST /notifications/{notificationId}` under `application/vnd.notificationnotify.email+json` (required `templateId`, `sendToAddress`; optional `fileId`, `personalisation{}`; `additionalProperties: false`; **no `notificationId` in the body**, it is the path parameter), 202 and nothing else | `specs/002-consolidate-progression-leg/contracts/notificationnotify/notificationnotify.email.json` (and the handler-side twin beside it) | `EmailReportSink`, through the existing `adapter/notificationnotify/NotificationNotifyClient` shape. `EmailReportSinkTest` validates the body it posts against that schema, so a drift upstream is a failing test here as well as in `NotificationNotifyClientTest` |
| The framework file service's `metadata` + `content` tables, write-only, pinned to liquibase changesets 001-006 (`content(file_id, content bytea, deleted, deleted_at)`, `metadata(file_id, metadata jsonb)`, `metadata.file_id` a foreign key onto `content.file_id`) | `specs/002-consolidate-progression-leg/contracts/fileservice/` (plus `008-DEPLOYED-NOTE.md`, the deployed index the local clone predates) | `PayloadFileStore.storeText`, the CSV attachment, through `adapter/fileservice/FileServicePayloadStore`. `EmailReportSinkStoreIT` seeds a Testcontainers Postgres from those changesets and asserts that exactly the two framework inserts are issued, content first |

Neither is re-vendored, copied or re-pointed. A single vendored copy per contract is the point: two
copies is how one increment's tests come to pass against a shape the other increment's tests have
already stopped believing in. The provenance, the verification history (including the SIT check of
2026-09-07 that closed 002's T005) and the REST paths, media types and ACLs are all in
`specs/002-consolidate-progression-leg/contracts/README.md`, which remains the authority for both.

Also unchanged and still authoritative, from 001: the inbound
`src/main/resources/contracts/distribution-command.schema.json` and the frozen register document
under `src/main/resources/contracts/progression/` (`criminal-court-public-model` 17.103.13). This
increment reads neither and writes neither.

One platform contract is deliberately **not** touched: the `YotResultsDistributionService` App Configuration
flag. The report reads it nowhere and is gated by it nowhere, so this increment adds no second
reader and no second lever (constitution Cutover Rule).
